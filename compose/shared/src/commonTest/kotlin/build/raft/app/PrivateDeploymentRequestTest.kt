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
}
