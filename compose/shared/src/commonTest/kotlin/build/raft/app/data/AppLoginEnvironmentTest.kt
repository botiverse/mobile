package build.raft.app.data

import build.raft.app.network.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class AppLoginEnvironmentTest {
    private fun SharedApiEnvironmentStore.custom(host: String) = save(
        SharedSettingsApiEnvironmentRequest("custom", "Test", "https://$host/api/", "https://$host", "https://$host/web")
    )

    @Test fun sameCustomKeyWithDifferentEndpointsCannotExchange() = runBlocking {
        val environments = SharedApiEnvironmentStore(InMemoryRaftKeyValueStore())
        environments.custom("a.example")
        var exchanges = 0
        val runtime = createAppLoginRuntime(environments, InMemoryRaftAuthSessionStore(), transportFactory = {
            object : RaftAuthTransport by AuthDataSourceTest.FakeAuthApi() {
                override suspend fun completeAppLogin(bodyText: String): AuthNetworkResponse {
                    exchanges++
                    return AuthNetworkResponse(500, null)
                }
            }
        })
        environments.custom("b.example")
        assertFalse(runtime.complete("code", "verifier"))
        assertEquals(0, exchanges)
        assertNotEquals(runtime.environment, appLoginEnvironmentIdentity(environments.current()))
    }

    @Test fun switchDuringHydrationNeverSendsCredentialsToNewOriginOrCommitsSession() = runBlocking {
        val environments = SharedApiEnvironmentStore(InMemoryRaftKeyValueStore())
        environments.custom("a.example")
        val sessions = InMemoryRaftAuthSessionStore()
        val urls = mutableListOf<String>()
        val executor = object : RaftHttpExecutor {
            override suspend fun execute(priority: RaftRequestPriority, url: String, method: String,
                headers: Map<String, String>, bodyText: String?, jsonBody: Boolean): RaftHttpExecutorResponse {
                urls += url
                val body = when {
                    url.endsWith("auth/app-login/complete") ->
                        """{"user":{"id":"user-1","email":"test@example.com","displayName":"Test"},"accessToken":"access-token","refreshToken":"refresh-token"}"""
                    url.endsWith("/servers") -> {
                        environments.custom("b.example")
                        "[]"
                    }
                    else -> "[]"
                }
                return RaftHttpExecutorResponse(200, body, null)
            }
            override suspend fun executeMultipart(priority: RaftRequestPriority, url: String,
                fields: Map<String, String>, files: List<MultipartFile>, headers: Map<String, String>,
                onUploadProgress: ((RaftWireTransferProgress) -> Unit)?): RaftHttpExecutorResponse = error("Unexpected multipart")
        }
        val runtime = createAppLoginRuntime(environments, sessions, transportFactory = { base ->
            SharedRaftAuthTransport(baseUrl = base, executor = executor)
        })
        assertFalse(runtime.complete("code", "verifier"))
        assertTrue(urls.size >= 2, "Completion must reach server hydration")
        assertTrue(urls.all { it.startsWith("https://a.example/api/") }, urls.toString())
        assertEquals("https://b.example/api/", environments.current().apiBaseUrl)
        assertNull(sessions.readSession())
    }
}
