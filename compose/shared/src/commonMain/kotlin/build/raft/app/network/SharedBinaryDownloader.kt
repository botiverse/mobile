package build.raft.app.network

import com.tencent.kmm.network.export.NetworkBody
import com.tencent.kmm.network.export.NetworkRequest
import com.tencent.kmm.network.export.NetworkRequestPolicy
import com.tencent.kmm.network.export.VBTransportMethod
import com.tencent.kmm.network.service.NetworkClient
import com.tencent.kmm.network.service.NetworkClientConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class SharedDownloadedBytes(
    val bytes: ByteArray,
    val contentType: String?
)

/**
 * Binary downloads over NetworkKMM (task #12; formerly Ktor).
 *
 * [download] streams the response body chunk-by-chunk via
 * NetworkClient.downloadStream (fork #8, raft.5) — the whole payload is never
 * held in memory. Total size is not known up front (it would need the
 * response's Content-Length before the first chunk), so progress is
 * indeterminate for now; a later raft revision exposes response headers at
 * stream start for determinate progress.
 */
object SharedBinaryDownloader {
    private const val DOWNLOAD_TIMEOUT_MILLIS = 60_000L

    private val client: NetworkClient by lazy {
        NetworkClient(
            NetworkClientConfig(
                defaultPolicy = NetworkRequestPolicy(timeoutMillis = DOWNLOAD_TIMEOUT_MILLIS)
            )
        )
    }

    suspend fun fetchBytes(url: String): SharedDownloadedBytes {
        val response = fetch(url)
        return SharedDownloadedBytes(
            bytes = response.bytes,
            contentType = response.contentType
        )
    }

    suspend fun download(
        url: String,
        onStart: (totalBytes: Long?) -> Unit,
        beforeRead: () -> Unit = {},
        onChunk: (bytes: ByteArray, downloadedBytes: Long, totalBytes: Long?) -> Unit
    ): String? {
        // Total is unknown until the stream completes, so progress is
        // indeterminate for now (raft.5). Chunks are delivered straight off the
        // transport without buffering the whole payload.
        onStart(null)
        var downloadedBytes = 0L
        return suspendCancellableCoroutine { continuation ->
            val call =
                client.downloadStream(
                    request =
                        NetworkRequest(
                            method = VBTransportMethod.GET,
                            url = url,
                            body = NetworkBody.Empty,
                            policy = NetworkRequestPolicy(timeoutMillis = DOWNLOAD_TIMEOUT_MILLIS)
                        ),
                    onChunk = { chunk ->
                        beforeRead()
                        downloadedBytes += chunk.size.toLong()
                        onChunk(chunk, downloadedBytes, null)
                    },
                    onComplete = { response ->
                        if (!continuation.isActive) return@downloadStream
                        val status = response.statusCode
                        if (status == null || status !in 200..299) {
                            continuation.resumeWithException(
                                IllegalStateException(
                                    "Attachment download failed: HTTP ${status ?: 0}" +
                                        (response.error?.message?.let { " ($it)" } ?: "")
                                )
                            )
                        } else {
                            continuation.resume(response.contentTypeHeader())
                        }
                    }
                )
            continuation.invokeOnCancellation { call.cancel() }
        }
    }

    private class FetchedBytes(val bytes: ByteArray, val contentType: String?)

    private suspend fun fetch(url: String, failurePrefix: String = "Download failed"): FetchedBytes {
        val response =
            client.execute(
                NetworkRequest(
                    method = VBTransportMethod.GET,
                    url = url,
                    body = NetworkBody.Empty,
                    policy = NetworkRequestPolicy(timeoutMillis = DOWNLOAD_TIMEOUT_MILLIS)
                )
            )
        val status = response.statusCode
        if (status == null || status !in 200..299) {
            throw IllegalStateException(
                "$failurePrefix: HTTP ${status ?: 0}${response.error?.message?.let { " ($it)" } ?: ""}"
            )
        }
        return FetchedBytes(
            bytes = response.body.bytes ?: ByteArray(0),
            contentType = response.contentTypeHeader()
        )
    }

    private fun com.tencent.kmm.network.export.NetworkResponse.contentTypeHeader(): String? =
        headers.entries
            .firstOrNull { (name, _) -> name.equals("Content-Type", ignoreCase = true) }
            ?.value
            ?.firstOrNull()
            ?.substringBefore(';')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
}
