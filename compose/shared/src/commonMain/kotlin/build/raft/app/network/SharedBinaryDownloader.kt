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
    val contentType: String?
)

/**
 * Status-preserving response used by the platform avatar cache on the same
 * NetworkKMM client as ordinary binary downloads.
 *
 * [exceededMaxBytes] is reported without retaining an over-limit response in
 * memory. Callers still receive the terminal HTTP status so they can classify
 * 304/404/410 independently from transport failures.
 */
data class SharedConditionalDownloadedBytes(
    val statusCode: Int,
    val bytes: ByteArray,
    val contentType: String?,
    val eTag: String?,
    val lastModified: String?,
    val exceededMaxBytes: Boolean
)

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
        val chunks = mutableListOf<ByteArray>()
        var totalBytes = 0
        val contentType =
            streamDownload(
                url = url,
                operation = "fetch_bytes",
                failurePrefix = "Download failed",
                onStart = {},
                onChunk = { bytes, _, _ ->
                    totalBytes += bytes.size
                    chunks += bytes.copyOf()
                }
            )
        return SharedDownloadedBytes(
            bytes = combineDownloadedChunks(chunks, totalBytes),
            contentType = contentType
        )
    }

    suspend fun fetchConditionalBytes(
        url: String,
        ifNoneMatch: String?,
        ifModifiedSince: String?,
        maxBytes: Int
    ): SharedConditionalDownloadedBytes {
        require(maxBytes > 0)
        val chunks = mutableListOf<ByteArray>()
        var retainedBytes = 0
        var downloadedBytes = 0L
        var exceededMaxBytes = false
        var responseStartStatusCode = 0
        var responseHeaders: Map<String, List<String>> = emptyMap()
        val startedAt = TimeSource.Monotonic.markNow()
        val urlSummary = binaryDownloadUrlDiagnostics(url)
        recordDiagnostics("operation=fetch_conditional_bytes status=start $urlSummary")
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
                            setHeader("Accept", "image/*")
                            ifNoneMatch?.trim()?.takeIf(String::isNotEmpty)?.let {
                                setHeader("If-None-Match", it)
                            }
                            ifModifiedSince?.trim()?.takeIf(String::isNotEmpty)?.let {
                                setHeader("If-Modified-Since", it)
                            }
                        },
                    onResponseStart = { statusCode, contentLength, headers ->
                        responseStartStatusCode = statusCode
                        responseHeaders = headers
                        if (contentLength != null && contentLength > maxBytes.toLong()) {
                            exceededMaxBytes = true
                        }
                        recordDiagnostics(
                            "operation=fetch_conditional_bytes status=response_start http=$statusCode " +
                                "expectedBytes=${contentLength ?: "unknown"} maxBytes=$maxBytes $urlSummary"
                        )
                    },
                    onChunk = { chunk ->
                        downloadedBytes += chunk.size.toLong()
                        if (!exceededMaxBytes && retainedBytes + chunk.size <= maxBytes) {
                            chunks += chunk.copyOf()
                            retainedBytes += chunk.size
                        } else if (!exceededMaxBytes) {
                            exceededMaxBytes = true
                            chunks.clear()
                            retainedBytes = 0
                        }
                    },
                    onComplete = { response ->
                        if (!continuation.isActive) return@downloadStream
                        val status = response.statusCode ?: responseStartStatusCode
                        val headers = response.headers.takeIf { it.isNotEmpty() } ?: responseHeaders
                        val bytes =
                            if (exceededMaxBytes) {
                                ByteArray(0)
                            } else {
                                combineDownloadedChunks(chunks, retainedBytes)
                            }
                        recordDiagnostics(
                            "operation=fetch_conditional_bytes status=complete http=$status " +
                                "downloadedBytes=$downloadedBytes retainedBytes=${bytes.size} " +
                                "maxExceeded=$exceededMaxBytes " +
                                "contentType=${headers.firstHeaderValue("Content-Type") ?: "unknown"} " +
                                "durationMs=${startedAt.elapsedNow().inWholeMilliseconds} $urlSummary"
                        )
                        continuation.resume(
                            SharedConditionalDownloadedBytes(
                                statusCode = status,
                                bytes = bytes,
                                contentType =
                                    headers.firstHeaderValue("Content-Type")
                                        ?.substringBefore(';')
                                        ?.trim()
                                        ?.takeIf(String::isNotBlank),
                                eTag = headers.firstHeaderValue("ETag")?.trim()?.takeIf(String::isNotEmpty),
                                lastModified =
                                    headers.firstHeaderValue("Last-Modified")?.trim()?.takeIf(String::isNotEmpty),
                                exceededMaxBytes = exceededMaxBytes
                            )
                        )
                    }
                )
            continuation.invokeOnCancellation {
                recordDiagnostics(
                    "operation=fetch_conditional_bytes status=canceled downloadedBytes=$downloadedBytes " +
                        "durationMs=${startedAt.elapsedNow().inWholeMilliseconds} $urlSummary"
                )
                call.cancel()
            }
        }
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
            onStart = onStart,
            beforeRead = beforeRead,
            onChunk = onChunk
        )

    private suspend fun streamDownload(
        url: String,
        operation: String,
        failurePrefix: String,
        onStart: (totalBytes: Long?) -> Unit,
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
                        },
                    onResponseStart = { _, contentLength, _ ->
                        totalBytes = contentLength?.takeIf { it > 0L }
                        recordDiagnostics(
                            "operation=$operation status=response_start expectedBytes=${totalBytes ?: "unknown"} $urlSummary"
                        )
                        onStart(totalBytes)
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
                            continuation.resumeWithException(
                                IllegalStateException(
                                    "$failurePrefix: HTTP ${status ?: 0}" +
                                        safeErrorMessage.takeUnless { it == "none" }?.let { " ($it)" }.orEmpty()
                                )
                            )
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

    private fun Map<String, List<String>>.firstHeaderValue(name: String): String? =
        entries
            .firstOrNull { (headerName, _) -> headerName.equals(name, ignoreCase = true) }
            ?.value
            ?.firstOrNull()

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
