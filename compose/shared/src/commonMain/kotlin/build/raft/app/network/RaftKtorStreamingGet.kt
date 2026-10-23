package build.raft.app.network

import io.ktor.client.plugins.timeout
import io.ktor.client.request.headers
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import kotlinx.io.EOFException

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
 *
 * A body that ends before its declared Content-Length is a transport failure ([EOFException]), never a
 * success. When the server drops the connection mid-body, Ktor's OkHttp engine does not always surface
 * OkHttp's "unexpected end of stream": depending on timing the body channel just ends, and the caller
 * used to get the truncated bytes as a complete download. Only checked for identity-encoded bodies, where
 * Content-Length counts the bytes the channel delivers (OkHttp strips it after transparent gzip;
 * NSURLSession keeps the compressed length while handing back decoded bytes).
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
                var received = 0L
                while (true) {
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read < 0) break
                    if (read > 0) {
                        received += read
                        onChunk(buffer.copyOf(read))
                    }
                }
                val identityEncoded =
                    response.headers[HttpHeaders.ContentEncoding]?.trim().let { it.isNullOrEmpty() || it.equals("identity", ignoreCase = true) }
                if (contentLength != null && identityEncoded && received < contentLength) {
                    throw EOFException("unexpected end of stream: received $received of $contentLength bytes")
                }
            }
            RaftStreamingGetResponse(status, headers)
        }
