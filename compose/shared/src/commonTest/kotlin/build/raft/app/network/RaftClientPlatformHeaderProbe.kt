package build.raft.app.network

import com.tencent.kmm.network.export.NetworkRequest
import com.tencent.kmm.network.export.NetworkResponse
import com.tencent.kmm.network.export.NetworkResponseBody

/**
 * Sends one plain and one multipart API request through the production [NetworkKmmRaftHttpExecutor]
 * (transport stubbed) and reports the `X-Raft-Client-Platform` value each carried. The platform tests
 * assert `android` / `ios`.
 */
internal object RaftClientPlatformHeaderProbe {
    private const val BASE_URL = "https://api.test/api"

    /** Request path (relative to the API base) → the header value it carried, or null. */
    suspend fun clientPlatformByPath(): Map<String, String?> {
        val sent = mutableListOf<NetworkRequest>()
        val executor =
            NetworkKmmRaftHttpExecutor(
                multipartTransportOverride = { request ->
                    sent += request
                    ok(request)
                },
                requestStarterOverride = { request, onComplete ->
                    sent += request
                    onComplete(ok(request))
                    RaftNetworkRequestCancelHandle {}
                },
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
            request.url.removePrefix("$BASE_URL/") to request.headers[RAFT_CLIENT_PLATFORM_HEADER]
        }
    }

    val expectedPaths: Set<String> = setOf("auth/register", "attachments/upload")

    private fun ok(request: NetworkRequest) =
        NetworkResponse(
            request = request,
            statusCode = 200,
            headers = emptyMap(),
            body = NetworkResponseBody(bytes = "{}".encodeToByteArray()),
        )
}
