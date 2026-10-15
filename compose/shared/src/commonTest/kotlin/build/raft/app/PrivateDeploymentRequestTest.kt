package build.raft.app

import build.raft.app.data.SharedApiEnvironmentStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PrivateDeploymentRequestTest {
    @Test fun splitWebAndApiHosts() {
        val request = privateDeploymentRequest("https://raft.company.com/", "https://api.raft.company.com")!!
        assertEquals(SharedApiEnvironmentStore.CustomKey, request.key)
        assertEquals("raft.company.com", request.label)
        assertEquals("https://raft.company.com", request.webUrl)
        assertEquals("https://api.raft.company.com/api/", request.apiBaseUrl)
        assertEquals("https://api.raft.company.com", request.socketBaseUrl)
    }

    @Test fun schemeDefaultsToHttpsAndAnApiSuffixIsKept() {
        val request = privateDeploymentRequest(" raft.company.com ", "raft.company.com/api/")!!
        assertEquals("https://raft.company.com", request.webUrl)
        assertEquals("https://raft.company.com/api/", request.apiBaseUrl)
        assertEquals("https://raft.company.com", request.socketBaseUrl)
    }

    @Test fun portsAndPlainHttpArePreserved() {
        val request = privateDeploymentRequest("http://10.0.0.5:5173", "http://10.0.0.5:3000")!!
        assertEquals("http://10.0.0.5:5173", request.webUrl)
        assertEquals("http://10.0.0.5:3000/api/", request.apiBaseUrl)
        assertEquals("http://10.0.0.5:3000", request.socketBaseUrl)
    }

    @Test fun rejectsBlankNonHttpOrHostlessInput() {
        assertNull(privateDeploymentRequest("", "https://api.example.com"))
        assertNull(privateDeploymentRequest("https://raft.example.com", " "))
        assertNull(privateDeploymentRequest("ftp://raft.example.com", "https://api.example.com"))
        assertNull(privateDeploymentRequest("https://", "https://api.example.com"))
        assertNull(privateDeploymentRequest("https://raft example.com", "https://api.example.com"))
    }

    @Test fun rejectsMalformedAuthorities() {
        val api = "https://api.example.com"
        for (web in listOf(
            "https://user@",
            "https://user@raft.example.com",
            "https://[::1",
            "https://[::1]x",
            "https://host:abc",
            "https://host:",
            "https://host:70000",
            "https://host:0",
            "https://ra_ft.example.com",
            "https://raft..example.com",
            "https://-raft.example.com",
        )) {
            assertNull(privateDeploymentRequest(web, api), web)
            assertNull(privateDeploymentRequest("https://raft.example.com", web), "api $web")
        }
    }

    @Test fun acceptsIpv6AndPortsAndDropsQueryAndFragment() {
        val request = privateDeploymentRequest("https://raft.example.com/?utm=x#top", "http://[fd00::1]:3000")!!
        assertEquals("https://raft.example.com", request.webUrl)
        assertEquals("http://[fd00::1]:3000/api/", request.apiBaseUrl)
        assertEquals("http://[fd00::1]:3000", request.socketBaseUrl)
    }
}
