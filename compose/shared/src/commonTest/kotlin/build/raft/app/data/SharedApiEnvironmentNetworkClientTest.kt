package build.raft.app.data

import build.raft.app.network.MultipartFile
import build.raft.app.network.SlockHttpExecutor
import build.raft.app.network.SlockHttpExecutorResponse
import build.raft.app.network.SlockRequestPriority
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class SharedApiEnvironmentNetworkClientTest {
    @Test
    fun currentEnvironmentAuthDataSourceFactoryReadsLatestApiEnvironment() {
        val environmentStore = SharedApiEnvironmentStore(InMemorySlockKeyValueStore())
        val sessionStore = InMemorySlockAuthSessionStore()
        val capturedBaseUrls = mutableListOf<String>()

        createSharedAuthDataSourceForCurrentEnvironment(
            apiEnvironmentStore = environmentStore,
            authStore = sessionStore,
            transportFactory = { baseUrl ->
                capturedBaseUrls += baseUrl
                NoopAuthTransport
            }
        )
        environmentStore.select("staging")
        createSharedAuthDataSourceForCurrentEnvironment(
            apiEnvironmentStore = environmentStore,
            authStore = sessionStore,
            transportFactory = { baseUrl ->
                capturedBaseUrls += baseUrl
                NoopAuthTransport
            }
        )

        assertEquals(
            listOf(
                SharedApiEnvironmentStore.Production.apiBaseUrl,
                SharedApiEnvironmentStore.Staging.apiBaseUrl
            ),
            capturedBaseUrls
        )
    }

    @Test
    fun sharedNetworkClientReadsLatestApiEnvironmentForEachRequest() = runBlocking {
        val environmentStore = SharedApiEnvironmentStore(InMemorySlockKeyValueStore())
        val executor = RecordingHttpExecutor()
        val sessionStore =
            InMemorySlockAuthSessionStore(
                StoredAuthSession(
                    user = StoredAuthUser(id = "user-1", email = "user@raft.build"),
                    accessToken = "access-token",
                    refreshToken = "refresh-token",
                    selectedServerId = "server-1",
                    servers = listOf(StoredAuthServer(id = "server-1", name = "Raft"))
                )
            )
        val client =
            SharedSlockNetworkClient(
                sessionStore = sessionStore,
                baseUrlProvider = { environmentStore.current().apiBaseUrl },
                executor = executor
            )

        client.request(
            priority = DataRequestPriority.Foreground,
            path = "channels",
            method = "GET",
            query = emptyList(),
            serverIdOverride = null,
            bodyText = null
        )
        environmentStore.select("staging")
        client.request(
            priority = DataRequestPriority.Foreground,
            path = "channels",
            method = "GET",
            query = emptyList(),
            serverIdOverride = null,
            bodyText = null
        )

        assertEquals(
            listOf(
                "${SharedApiEnvironmentStore.Production.apiBaseUrl.trimEnd('/')}/channels",
                "${SharedApiEnvironmentStore.Staging.apiBaseUrl.trimEnd('/')}/channels"
            ),
            executor.urls
        )
    }

    private object NoopAuthTransport : SlockAuthTransport {
        override suspend fun login(bodyText: String): AuthNetworkResponse = noop()
        override suspend fun completeSocialLogin(provider: String, bodyText: String): AuthNetworkResponse = noop()
        override suspend fun startMobileOAuth(bodyText: String, authorization: String?): AuthNetworkResponse = noop()
        override suspend fun completeMobileOAuth(bodyText: String, authorization: String?): AuthNetworkResponse = noop()
        override suspend fun startMobileOAuthLink(
            provider: String,
            bodyText: String,
            authorization: String
        ): AuthNetworkResponse = noop()
        override suspend fun completeMobileOAuthLink(
            provider: String,
            bodyText: String,
            authorization: String
        ): AuthNetworkResponse = noop()
        override suspend fun register(bodyText: String): AuthNetworkResponse = noop()
        override suspend fun forgotPassword(bodyText: String): AuthNetworkResponse = noop()
        override suspend fun resendVerification(authorization: String): AuthNetworkResponse = noop()
        override suspend fun currentUser(authorization: String): AuthNetworkResponse = noop()
        override suspend fun refresh(bodyText: String): AuthNetworkResponse = noop()
        override suspend fun logout(bodyText: String): AuthNetworkResponse = noop()
        override suspend fun servers(authorization: String): AuthNetworkResponse = noop()
        override suspend fun serverOrder(authorization: String): AuthNetworkResponse = noop()
        override suspend fun createServer(authorization: String, bodyText: String): AuthNetworkResponse = noop()

        private fun noop(): AuthNetworkResponse =
            AuthNetworkResponse(code = 200, bodyText = "{}")
    }

    private class RecordingHttpExecutor : SlockHttpExecutor {
        val urls = mutableListOf<String>()

        override suspend fun execute(
            priority: SlockRequestPriority,
            url: String,
            method: String,
            headers: Map<String, String>,
            bodyText: String?,
            jsonBody: Boolean
        ): SlockHttpExecutorResponse {
            urls += url
            return SlockHttpExecutorResponse(code = 200, bodyText = "{}", errorBodyText = null)
        }

        override suspend fun executeMultipart(
            priority: SlockRequestPriority,
            url: String,
            fields: Map<String, String>,
            files: List<MultipartFile>,
            headers: Map<String, String>
        ): SlockHttpExecutorResponse {
            urls += url
            return SlockHttpExecutorResponse(code = 200, bodyText = "{}", errorBodyText = null)
        }
    }
}
