package build.raft.app.network

import com.tencent.kmm.network.export.NetworkBody
import com.tencent.kmm.network.export.NetworkRequest
import com.tencent.kmm.network.export.NetworkRequestPolicy
import com.tencent.kmm.network.export.VBTransportMethod
import com.tencent.kmm.network.service.NetworkClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.TimeSource

data class SharedDownloadedBytes(
    val bytes: ByteArray,
    val contentType: String?,
    val responseHeaders: Map<String, List<String>> = emptyMap()
)

internal class BinaryDownloadHttpException(val statusCode: Int) :
    IllegalStateException("Download failed: HTTP $statusCode")

fun interface BinaryDownloadDiagnosticsLogger {
    fun record(tag: String, message: String)
}

/**
 * Binary downloads over NetworkKMM (task #12; formerly Ktor).
 *
 * [download] streams the response body chunk-by-chunk via
 * NetworkClient.downloadStream (fork #8) — the whole payload is never held in
 * memory. The total size comes from the response Content-Length delivered at
 * stream start (raft.6 onResponseStart), so progress is determinate whenever
 * the server sends that header.
 */
object SharedBinaryDownloader {
    private const val DOWNLOAD_TIMEOUT_MILLIS = 60_000L
    private const val DIAGNOSTICS_TAG = "SlockBinaryDownload"

    private var diagnosticsLogger: BinaryDownloadDiagnosticsLogger? = null

    fun setDiagnosticsLogger(logger: BinaryDownloadDiagnosticsLogger?) {
        diagnosticsLogger = logger
    }

    private val client: NetworkClient by lazy {
        ensureNetworkKmmInitialized { warning ->
            recordDiagnostics("operation=networkkmm_init status=warning message=${warning.toSafeDiagnosticsToken()}")
        }
        NetworkClient(
            slockNetworkClientConfig(
                defaultPolicy = NetworkRequestPolicy(timeoutMillis = DOWNLOAD_TIMEOUT_MILLIS),
                diagnosticsLogger = { message ->
                    recordDiagnostics("operation=engine_diagnostics $message")
                }
            )
        )
    }

    suspend fun fetchBytes(url: String): SharedDownloadedBytes {
        return fetchBytes(url, emptyMap(), Long.MAX_VALUE)
    }

    internal suspend fun fetchBytes(
        url: String,
        headers: Map<String, String>,
        maxBytes: Long
    ): SharedDownloadedBytes {
        val chunks = mutableListOf<ByteArray>()
        var totalBytes = 0
        var responseHeaders: Map<String, List<String>> = emptyMap()
        var validationFailure: String? = null
        val contentType =
            streamDownload(
                url = url,
                operation = "fetch_bytes",
                failurePrefix = "Download failed",
                headers = headers,
                onResponseStart = { contentLength, receivedHeaders ->
                    if (contentLength != null && contentLength > maxBytes) {
                        validationFailure = "Download exceeded maximum size"
                    }
                    responseHeaders = receivedHeaders
                },
                onChunk = { bytes, _, _ ->
                    if (totalBytes.toLong() + bytes.size > maxBytes) {
                        validationFailure = "Download exceeded maximum size"
                    } else if (validationFailure == null) {
                        totalBytes += bytes.size
                        chunks += bytes.copyOf()
                    }
                }
            )
        validationFailure?.let { throw IllegalStateException(it) }
        return SharedDownloadedBytes(
            bytes = combineDownloadedChunks(chunks, totalBytes),
            contentType = contentType,
            responseHeaders = responseHeaders
        )
    }

    suspend fun download(
        url: String,
        onStart: (totalBytes: Long?) -> Unit,
        beforeRead: () -> Unit = {},
        onChunk: (bytes: ByteArray, downloadedBytes: Long, totalBytes: Long?) -> Unit
    ): String? =
        streamDownload(
            url = url,
            operation = "download_stream",
            failurePrefix = "Attachment download failed",
            onResponseStart = { totalBytes, _ -> onStart(totalBytes) },
            beforeRead = beforeRead,
            onChunk = onChunk
        )

    private suspend fun streamDownload(
        url: String,
        operation: String,
        failurePrefix: String,
        headers: Map<String, String> = emptyMap(),
        onResponseStart: (totalBytes: Long?, headers: Map<String, List<String>>) -> Unit,
        beforeRead: () -> Unit = {},
        onChunk: (bytes: ByteArray, downloadedBytes: Long, totalBytes: Long?) -> Unit
    ): String? {
        // Chunks are delivered straight off the transport without buffering the
        // whole payload; total comes from the response Content-Length at stream
        // start (raft.6) so progress is determinate when the server sends it.
        var downloadedBytes = 0L
        var totalBytes: Long? = null
        val startedAt = TimeSource.Monotonic.markNow()
        val urlSummary = binaryDownloadUrlDiagnostics(url)
        recordDiagnostics("operation=$operation status=start $urlSummary")
        return suspendCancellableCoroutine { continuation ->
            val call =
                client.downloadStream(
                    request =
                        NetworkRequest(
                            method = VBTransportMethod.GET,
                            url = url,
                            body = NetworkBody.Empty,
                            policy = NetworkRequestPolicy(timeoutMillis = DOWNLOAD_TIMEOUT_MILLIS)
                        ).apply {
                            setHeader("Accept", "*/*")
                            headers.forEach { (name, value) -> setHeader(name, value) }
                        },
                    onResponseStart = { _, contentLength, responseHeaders ->
                        totalBytes = contentLength?.takeIf { it > 0L }
                        recordDiagnostics(
                            "operation=$operation status=response_start expectedBytes=${totalBytes ?: "unknown"} $urlSummary"
                        )
                        onResponseStart(totalBytes, responseHeaders)
                    },
                    onChunk = { chunk ->
                        beforeRead()
                        downloadedBytes += chunk.size.toLong()
                        onChunk(chunk, downloadedBytes, totalBytes)
                    },
                    onComplete = { response ->
                        if (!continuation.isActive) return@downloadStream
                        val status = response.statusCode
                        if (status == null || status !in 200..299) {
                            val safeErrorMessage = response.downloadErrorMessage()
                            recordDiagnostics(
                                "operation=$operation status=failed http=${status ?: 0} " +
                                    "downloadedBytes=$downloadedBytes expectedBytes=${totalBytes ?: "unknown"} " +
                                    "durationMs=${startedAt.elapsedNow().inWholeMilliseconds} " +
                                    "errorKind=${response.error?.kind ?: "none"} " +
                                    "errorRawCode=${response.error?.rawCode ?: response.rawResponse?.errorCode ?: "none"} " +
                                    "message=$safeErrorMessage $urlSummary"
                            )
                            continuation.resumeWithException(BinaryDownloadHttpException(status ?: 0))
                        } else {
                            val contentType = response.contentTypeHeader()
                            recordDiagnostics(
                                "operation=$operation status=complete http=$status " +
                                    "downloadedBytes=$downloadedBytes expectedBytes=${totalBytes ?: "unknown"} " +
                                    "contentType=${contentType ?: "unknown"} " +
                                    "durationMs=${startedAt.elapsedNow().inWholeMilliseconds} $urlSummary"
                            )
                            continuation.resume(contentType)
                        }
                    }
                )
            continuation.invokeOnCancellation {
                recordDiagnostics(
                    "operation=$operation status=canceled downloadedBytes=$downloadedBytes " +
                        "expectedBytes=${totalBytes ?: "unknown"} " +
                        "durationMs=${startedAt.elapsedNow().inWholeMilliseconds} $urlSummary"
                )
                call.cancel()
            }
        }
    }

    private fun recordDiagnostics(message: String) {
        diagnosticsLogger?.record(DIAGNOSTICS_TAG, message)
    }

    private fun com.tencent.kmm.network.export.NetworkResponse.contentTypeHeader(): String? =
        headers.entries
            .firstOrNull { (name, _) -> name.equals("Content-Type", ignoreCase = true) }
            ?.value
            ?.firstOrNull()
            ?.substringBefore(';')
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun com.tencent.kmm.network.export.NetworkResponse.downloadErrorMessage(): String =
        listOfNotNull(error?.message, rawResponse?.errorMessage)
            .firstOrNull { it.isNotBlank() }
            ?.toSafeDiagnosticsToken()
            ?: "none"
}

internal fun combineDownloadedChunks(chunks: List<ByteArray>, totalBytes: Int): ByteArray {
    val output = ByteArray(totalBytes)
    var offset = 0
    chunks.forEach { chunk ->
        chunk.copyInto(output, destinationOffset = offset)
        offset += chunk.size
    }
    return output
}

fun binaryDownloadUrlDiagnostics(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    val scheme = trimmed.substringBefore("://", missingDelimiterValue = "").lowercase()
    val afterScheme = trimmed.substringAfter("://", missingDelimiterValue = trimmed)
    val hostPort = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
    val host = hostPort.substringBefore(':').takeIf { it.isNotBlank() } ?: "none"
    val pathWithPrefix =
        afterScheme.substringAfter('/', missingDelimiterValue = "")
            .substringBefore('?')
            .substringBefore('#')
    val normalizedPath = pathWithPrefix.takeIf { it.isNotBlank() }?.let { "/$it" } ?: "/"
    return "scheme=${scheme.ifBlank { "unknown" }} host=${host.toSafeDiagnosticsToken()} " +
        "pathHash=${normalizedPath.hashCode().toString(16)} urlHash=${trimmed.hashCode().toString(16)}"
}
