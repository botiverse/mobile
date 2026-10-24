package build.raft.app

import build.raft.app.network.MultipartFile
import build.raft.app.network.RaftHttpExecutor
import build.raft.app.network.RaftHttpExecutorResponse
import build.raft.app.network.RaftRequestPriority
import build.raft.app.network.RaftWireTransferProgress
import kotlinx.coroutines.runBlocking
import build.raft.app.data.SharedApiEnvironmentStore
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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
            "https://[gg::1]",
            "https://[]",
            "https://[1:2]",
            "https://[1::2::3]",
            "https://[12345::1]",
            "https://[1:2:3:4:5:6:7:8:9]",
            "https://[::1.2.3.256]",
            "https://[fe80::1%25eth0]",
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
        for (host in listOf("::", "::1", "1::", "2001:db8::8a2e:370:7334", "1:2:3:4:5:6:7:8", "::ffff:192.0.2.1", "1:2:3:4:5:6:1.2.3.4")) {
            assertEquals("https://[$host]", privateDeploymentRequest("https://[$host]", "https://api.example.com")?.webUrl, host)
        }
    }

    @Test fun webAddressMustAnswer() = runBlocking {
        fun executor(answer: () -> RaftHttpExecutorResponse) = object : RaftHttpExecutor {
            override suspend fun execute(priority: RaftRequestPriority, url: String, method: String,
                headers: Map<String, String>, bodyText: String?, jsonBody: Boolean): RaftHttpExecutorResponse = answer()
            override suspend fun executeMultipart(priority: RaftRequestPriority, url: String,
                fields: Map<String, String>, files: List<MultipartFile>, headers: Map<String, String>,
                onUploadProgress: ((RaftWireTransferProgress) -> Unit)?): RaftHttpExecutorResponse = error("Unexpected multipart")
        }
        val web = "https://raft.example.com"
        assertTrue(privateDeploymentWebReachable(executor { RaftHttpExecutorResponse(200, "<html>", null) }, web))
        assertTrue(privateDeploymentWebReachable(executor { RaftHttpExecutorResponse(302, null, null) }, web))
        assertFalse(privateDeploymentWebReachable(executor { RaftHttpExecutorResponse(404, null, null) }, web))
        assertFalse(privateDeploymentWebReachable(executor { RaftHttpExecutorResponse(0, null, null) }, web))
        assertFalse(privateDeploymentWebReachable(executor { throw IllegalStateException("dns") }, web))
    }
}
