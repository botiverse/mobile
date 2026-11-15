package build.raft.app.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode

/**
 * Sends sign-in, refresh, plain and multipart API requests through the production [KtorRaftHttpExecutor]
 * (engine stubbed with MockEngine) with an app identity installed, and reports the
 * `X-Raft-Client-Platform` / `X-Raft-Client-Version` values each carried. The platform tests assert
 * `android` / `ios` and the version name.
 */
internal object RaftClientPlatformHeaderProbe {
    private const val BASE_URL = "https://api.test/api"

    /** The version name of the identity the probe installs; the header carries it without the build number. */
    const val VERSION_NAME: String = "1.26.0"

    data class ClientHeaders(val platform: String?, val version: String?)

    /** Request path (relative to the API base) → the client headers it carried (null where absent). */
    suspend fun clientHeadersByPath(): Map<String, ClientHeaders> {
        RaftAppIdentityRegistry.install(
            RaftAppIdentity(name = "Raft", version = VERSION_NAME, buildNumber = "12600001", buildType = "release")
        )
        try {
            return sendRequests()
        } finally {
            RaftAppIdentityRegistry.resetForTest()
        }
    }

    private suspend fun sendRequests(): Map<String, ClientHeaders> {
        val sent = mutableListOf<HttpRequestData>()
        val client =
            HttpClient(
                MockEngine { request ->
                    sent += request
                    respond("{}", HttpStatusCode.OK)
                }
            ) { configureRaftKtorClient() }
        val executor =
            KtorRaftHttpExecutor(
                clients = RaftKtorClientProvider { _, _ -> client },
                requestLifetimeCoordinator = RaftNetworkRequestLifetimeCoordinator(),
                transportFacts = { null },
                engineLabel = "ktor_probe",
                uploadProgress = BodyWriterUploadProgressTracker,
            )
        for (path in listOf("auth/register", "auth/login", "auth/refresh")) {
            executor.execute(
                priority = RaftRequestPriority.Foreground,
                url = "$BASE_URL/$path",
                method = "POST",
                headers = emptyMap(),
                bodyText = "{}",
                jsonBody = true,
            )
        }
        executor.executeMultipart(
            priority = RaftRequestPriority.Foreground,
            url = "$BASE_URL/attachments/upload",
            fields = emptyMap(),
            files = listOf(MultipartFile(name = "files", fileName = "a.txt", mimeType = "text/plain", bytes = byteArrayOf(1))),
            headers = emptyMap(),
        )
        return sent.associate { request ->
            request.url.toString().removePrefix("$BASE_URL/") to
                ClientHeaders(
                    platform = request.headers[RAFT_CLIENT_PLATFORM_HEADER],
                    version = request.headers[RAFT_CLIENT_VERSION_HEADER],
                )
        }
    }

    val expectedPaths: Set<String> = setOf("auth/register", "auth/login", "auth/refresh", "attachments/upload")
}
