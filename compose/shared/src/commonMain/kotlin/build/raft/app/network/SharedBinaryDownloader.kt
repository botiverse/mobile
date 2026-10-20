package build.raft.app.network

import com.tencent.kmm.network.export.NetworkBody
import com.tencent.kmm.network.export.NetworkRequest
import com.tencent.kmm.network.export.VBTransportMethod
import com.tencent.kmm.network.service.NetworkClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.TimeSource

private const val SHARED_BINARY_DOWNLOAD_TIMEOUT_MILLIS = 60_000L

data class SharedDownloadedBytes(
    val bytes: ByteArray,
    val contentType: String?
)

data class SharedBinaryConditionalValidators(
    val eTag: String? = null,
    val lastModified: String? = null
) {
    init {
        require(eTag == null || (eTag.isNotBlank() && eTag == eTag.trim()))
        require(lastModified == null || (lastModified.isNotBlank() && lastModified == lastModified.trim()))
        require(eTag != null || lastModified != null)
    }
}

sealed interface SharedBinaryConditionalDownloadResult {
    val statusCode: Int
    val eTag: String?
    val lastModified: String?

    data class Modified(
        override val statusCode: Int,
        val contentType: String?,
        override val eTag: String?,
        override val lastModified: String?
    ) : SharedBinaryConditionalDownloadResult {
        init {
            require(statusCode in 200..299)
        }
    }

    data class NotModified(
        override val eTag: String?,
        override val lastModified: String?
    ) : SharedBinaryConditionalDownloadResult {
        override val statusCode: Int = 304
    }
}

/**
 * Typed HTTP failure for [SharedBinaryDownloader.downloadConditional].
 *
 * Ordinary [SharedBinaryDownloader.download] and [SharedBinaryDownloader.fetchBytes] retain their
 * existing exception contract. The status is exposed only so mounted avatar policy can distinguish
 * authoritative 404/410 retirement from temporary HTTP failures without parsing diagnostics text.
 */
class SharedBinaryConditionalHttpException internal constructor(
    val statusCode: Int,
    message: String
) : IllegalStateException(message) {
    init {
        require(statusCode in 100..599 && statusCode !in 200..299 && statusCode != 304)
    }
}

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
            raftNetworkClientConfig(
                defaultPolicy = raftNetworkRequestPolicy(SHARED_BINARY_DOWNLOAD_TIMEOUT_MILLIS),
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

    /**
     * Streams one typed conditional request through the same client as ordinary downloads. A 304
     * is body-less and explicit; redirects and every other non-2xx status retain failure semantics.
     */
    suspend fun downloadConditional(
        url: String,
        validators: SharedBinaryConditionalValidators?,
        onStart: (totalBytes: Long?) -> Unit,
        beforeRead: () -> Unit = {},
        onChunk: (bytes: ByteArray, downloadedBytes: Long, totalBytes: Long?) -> Unit
    ): SharedBinaryConditionalDownloadResult {
        val operation = "conditional_download_stream"
        val failurePrefix = "Conditional download failed"
        var responseStatus = 0
        var downloadedBytes = 0L
        var totalBytes: Long? = null
        val startedAt = TimeSource.Monotonic.markNow()
        val urlSummary = binaryDownloadUrlDiagnostics(url)
        recordDiagnostics("operation=$operation status=start $urlSummary")
        return suspendCancellableCoroutine { continuation ->
            val call =
                client.downloadStream(
                    request = sharedBinaryConditionalRequest(url, validators),
                    onResponseStart = { statusCode, contentLength, _ ->
                        responseStatus = statusCode
                        totalBytes = contentLength?.takeIf { it > 0L }
                        recordDiagnostics(
                            "operation=$operation status=response_start http=$statusCode " +
                                "expectedBytes=${totalBytes ?: "unknown"} $urlSummary"
                        )
                        if (statusCode in 200..299) onStart(totalBytes)
                    },
                    onChunk = { chunk ->
                        if (responseStatus in 200..299) {
                            beforeRead()
                            downloadedBytes += chunk.size.toLong()
                            onChunk(chunk, downloadedBytes, totalBytes)
                        }
                    },
                    onComplete = { response ->
                        if (!continuation.isActive) return@downloadStream
                        val status = response.statusCode
                        val result =
                            status?.let {
                                sharedBinaryConditionalResult(
                                    statusCode = it,
                                    headers = response.headers
                                )
                            }
                        if (result == null) {
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
                                sharedBinaryConditionalFailure(
                                    statusCode = status,
                                    failurePrefix = failurePrefix,
                                    safeErrorMessage = safeErrorMessage
                                )
                            )
                        } else {
                            recordDiagnostics(
                                "operation=$operation status=complete http=${result.statusCode} " +
                                    "downloadedBytes=$downloadedBytes expectedBytes=${totalBytes ?: "unknown"} " +
                                    "durationMs=${startedAt.elapsedNow().inWholeMilliseconds} $urlSummary"
                            )
                            continuation.resume(result)
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
                            policy = raftNetworkRequestPolicy(SHARED_BINARY_DOWNLOAD_TIMEOUT_MILLIS)
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

    private fun com.tencent.kmm.network.export.NetworkResponse.downloadErrorMessage(): String =
        listOfNotNull(error?.message, rawResponse?.errorMessage)
            .firstOrNull { it.isNotBlank() }
            ?.toSafeDiagnosticsToken()
            ?: "none"
}

internal fun sharedBinaryConditionalRequest(
    url: String,
    validators: SharedBinaryConditionalValidators?
): NetworkRequest =
    NetworkRequest(
        method = VBTransportMethod.GET,
        url = url,
        body = NetworkBody.Empty,
        policy = raftNetworkRequestPolicy(SHARED_BINARY_DOWNLOAD_TIMEOUT_MILLIS)
    ).apply {
        setHeader("Accept", "*/*")
        validators?.eTag?.let { setHeader("If-None-Match", it) }
        validators?.lastModified?.let { setHeader("If-Modified-Since", it) }
    }

internal fun sharedBinaryConditionalResult(
    statusCode: Int,
    headers: Map<String, List<String>>
): SharedBinaryConditionalDownloadResult? {
    val eTag = headers.firstHeaderValue("ETag")
    val lastModified = headers.firstHeaderValue("Last-Modified")
    return when (statusCode) {
        304 -> SharedBinaryConditionalDownloadResult.NotModified(eTag, lastModified)
        in 200..299 ->
            SharedBinaryConditionalDownloadResult.Modified(
                statusCode = statusCode,
                contentType =
                    headers.firstHeaderValue("Content-Type")
                        ?.substringBefore(';')
                        ?.trim()
                        ?.takeIf(String::isNotBlank),
                eTag = eTag,
                lastModified = lastModified
            )
        else -> null
    }
}

internal fun sharedBinaryConditionalFailure(
    statusCode: Int?,
    failurePrefix: String,
    safeErrorMessage: String
): IllegalStateException {
    val message =
        "$failurePrefix: HTTP ${statusCode ?: 0}" +
            safeErrorMessage.takeUnless { it == "none" }?.let { " ($it)" }.orEmpty()
    return statusCode
        ?.takeIf { it in 100..599 && it !in 200..299 && it != 304 }
        ?.let { SharedBinaryConditionalHttpException(it, message) }
        ?: IllegalStateException(message)
}

private fun Map<String, List<String>>.firstHeaderValue(name: String): String? =
    entries.firstOrNull { (headerName, _) -> headerName.equals(name, ignoreCase = true) }
        ?.value
        ?.firstOrNull()
        ?.trim()
        ?.takeIf(String::isNotBlank)

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
