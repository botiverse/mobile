package build.raft.app.network

import io.ktor.client.plugins.timeout
import io.ktor.client.request.headers
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable

internal const val RAFT_DOWNLOAD_CHUNK_BYTES = 64 * 1024

/** Status and headers of a [raftStreamingGet] that reached the server. */
internal class RaftStreamingGetResponse(
    val statusCode: Int,
    val headers: Map<String, List<String>>,
)

/**
 * A streamed GET on the app's Ktor client (binary downloads, images, download to file). The response
 * body is read entirely inside Ktor's `execute { }` block, chunk by chunk, so no channel outlives the
 * call and a cancelled caller cancels the transfer. [onResponseStart] decides whether the body is
 * read (false for 304 / errors); [onChunk] receives each chunk in order. Transport failures throw
 * (classify them with [classifyRaftKtorFailure]); HTTP statuses never do.
 */
internal suspend fun raftStreamingGet(
    url: String,
    requestHeaders: Map<String, String>,
    priority: RaftRequestPriority,
    connectTimeoutMillis: Long,
    socketTimeoutMillis: Long?,
    totalTimeoutMillis: Long?,
    onResponseStart: (statusCode: Int, contentLength: Long?, headers: Map<String, List<String>>) -> Boolean,
    onChunk: (ByteArray) -> Unit,
    clients: RaftKtorClientProvider = RaftKtorProcessClients.provider,
): RaftStreamingGetResponse =
    clients.client(priority, RaftKtorCallKind.Idempotent)
        .prepareGet(url) {
            headers { raftTraceHeaders(requestHeaders).forEach { (name, value) -> append(name, value) } }
            timeout {
                this.connectTimeoutMillis = connectTimeoutMillis
                socketTimeoutMillis?.let { this.socketTimeoutMillis = it }
                totalTimeoutMillis?.let { requestTimeoutMillis = it }
            }
        }
        .execute { response ->
            val headers = response.headers.entries().associate { (name, values) -> name to values }
            val contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.takeIf { it >= 0L }
            val status = response.status.value
            if (onResponseStart(status, contentLength, headers)) {
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(RAFT_DOWNLOAD_CHUNK_BYTES)
                while (true) {
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read < 0) break
                    if (read > 0) onChunk(buffer.copyOf(read))
                }
            }
            RaftStreamingGetResponse(status, headers)
        }
