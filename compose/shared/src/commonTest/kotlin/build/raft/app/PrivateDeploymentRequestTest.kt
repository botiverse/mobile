package build.raft.app

import build.raft.app.network.MultipartFile
import build.raft.app.network.RaftHttpExecutor
import build.raft.app.network.RaftHttpExecutorResponse
import build.raft.app.network.RaftRequestPriority
import build.raft.app.network.RaftWireTransferProgress
import build.raft.app.thread.key
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

    @Test fun rejectsBlankNonHttpOrHostlessInput() {
        assertNull(privateDeploymentRequest("", "https://api.example.com"))
        assertNull(privateDeploymentRequest("https://raft.example.com", " "))
        assertNull(privateDeploymentRequest("ftp://raft.example.com", "https://api.example.com"))
        assertNull(privateDeploymentRequest("https://", "https://api.example.com"))
        assertNull(privateDeploymentRequest("https://raft example.com", "https://api.example.com"))
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
