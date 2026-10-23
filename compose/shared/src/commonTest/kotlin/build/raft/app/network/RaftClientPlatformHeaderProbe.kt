package build.raft.app.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode

/**
 * Sends one plain and one multipart API request through the production [KtorRaftHttpExecutor]
 * (engine stubbed with MockEngine) and reports the `X-Raft-Client-Platform` value each carried. The
 * platform tests assert `android` / `ios`.
 */
internal object RaftClientPlatformHeaderProbe {
    private const val BASE_URL = "https://api.test/api"

    /** Request path (relative to the API base) → the header value it carried, or null. */
    suspend fun clientPlatformByPath(): Map<String, String?> {
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
            )
        executor.execute(
            priority = RaftRequestPriority.Foreground,
            url = "$BASE_URL/auth/register",
            method = "POST",
            headers = emptyMap(),
            bodyText = "{}",
            jsonBody = true,
        )
        executor.executeMultipart(
            priority = RaftRequestPriority.Foreground,
            url = "$BASE_URL/attachments/upload",
            fields = emptyMap(),
            files = listOf(MultipartFile(name = "files", fileName = "a.txt", mimeType = "text/plain", bytes = byteArrayOf(1))),
            headers = emptyMap(),
        )
        return sent.associate { request ->
            request.url.toString().removePrefix("$BASE_URL/") to request.headers[RAFT_CLIENT_PLATFORM_HEADER]
        }
    }

    val expectedPaths: Set<String> = setOf("auth/register", "attachments/upload")
}
