package build.raft.app.network

import kotlinx.coroutines.CancellationException
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
 * Binary downloads (images, avatars, attachments) on the app's Ktor client, Visible lane
 * (artin 2026-10-23; NetworkKMM before). [download] streams the body chunk by chunk ([raftStreamingGet])
 * so the whole payload is never held in memory, and the total comes from Content-Length, so progress
 * is determinate whenever the server sends it.
 */
object SharedBinaryDownloader {
    private const val DIAGNOSTICS_TAG = "SlockBinaryDownload"

    private var diagnosticsLogger: BinaryDownloadDiagnosticsLogger? = null

    fun setDiagnosticsLogger(logger: BinaryDownloadDiagnosticsLogger?) {
        diagnosticsLogger = logger
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
        val response =
            runDownload(operation, startedAt, urlSummary, { downloadedBytes }, { totalBytes }) {
                raftStreamingGet(
                    url = url,
                    requestHeaders = sharedBinaryConditionalHeaders(validators),
                    priority = RaftRequestPriority.Visible,
                    connectTimeoutMillis = RAFT_KTOR_CONNECT_TIMEOUT_MILLIS,
                    socketTimeoutMillis = null,
                    totalTimeoutMillis = SHARED_BINARY_DOWNLOAD_TIMEOUT_MILLIS,
                    onResponseStart = { statusCode, contentLength, _ ->
                        responseStatus = statusCode
                        totalBytes = contentLength?.takeIf { it > 0L }
                        recordDiagnostics(
                            "operation=$operation status=response_start http=$statusCode " +
                                "expectedBytes=${totalBytes ?: "unknown"} $urlSummary"
                        )
                        val modified = statusCode in 200..299
                        if (modified) onStart(totalBytes)
                        modified
                    },
                    onChunk = { chunk ->
                        beforeRead()
                        downloadedBytes += chunk.size.toLong()
                        onChunk(chunk, downloadedBytes, totalBytes)
                    }
                )
            }
        val result = sharedBinaryConditionalResult(statusCode = response.statusCode, headers = response.headers)
        if (result == null) {
            recordDiagnostics(
                "operation=$operation status=failed http=${response.statusCode} " +
                    "downloadedBytes=$downloadedBytes expectedBytes=${totalBytes ?: "unknown"} " +
                    "durationMs=${startedAt.elapsedNow().inWholeMilliseconds} errorKind=none message=none $urlSummary"
            )
            throw sharedBinaryConditionalFailure(
                statusCode = response.statusCode,
                failurePrefix = failurePrefix,
                safeErrorMessage = "none"
            )
        }
        recordDiagnostics(
            "operation=$operation status=complete http=${result.statusCode} " +
                "downloadedBytes=$downloadedBytes expectedBytes=${totalBytes ?: "unknown"} " +
                "durationMs=${startedAt.elapsedNow().inWholeMilliseconds} $urlSummary"
        )
        return result
    }

    private suspend fun streamDownload(
        url: String,
        operation: String,
        failurePrefix: String,
        onStart: (totalBytes: Long?) -> Unit,
        beforeRead: () -> Unit = {},
        onChunk: (bytes: ByteArray, downloadedBytes: Long, totalBytes: Long?) -> Unit
    ): String? {
        // Chunks are delivered straight off the transport without buffering the whole payload.
        var downloadedBytes = 0L
        var totalBytes: Long? = null
        val startedAt = TimeSource.Monotonic.markNow()
        val urlSummary = binaryDownloadUrlDiagnostics(url)
        recordDiagnostics("operation=$operation status=start $urlSummary")
        val response =
            runDownload(operation, startedAt, urlSummary, { downloadedBytes }, { totalBytes }) {
                raftStreamingGet(
                    url = url,
                    requestHeaders = mapOf("Accept" to "*/*"),
                    priority = RaftRequestPriority.Visible,
                    connectTimeoutMillis = RAFT_KTOR_CONNECT_TIMEOUT_MILLIS,
                    socketTimeoutMillis = null,
                    totalTimeoutMillis = SHARED_BINARY_DOWNLOAD_TIMEOUT_MILLIS,
                    onResponseStart = { statusCode, contentLength, _ ->
                        totalBytes = contentLength?.takeIf { it > 0L }
                        recordDiagnostics(
                            "operation=$operation status=response_start http=$statusCode " +
                                "expectedBytes=${totalBytes ?: "unknown"} $urlSummary"
                        )
                        val ok = statusCode in 200..299
                        if (ok) onStart(totalBytes)
                        ok
                    },
                    onChunk = { chunk ->
                        beforeRead()
                        downloadedBytes += chunk.size.toLong()
                        onChunk(chunk, downloadedBytes, totalBytes)
                    }
                )
            }
        val status = response.statusCode
        if (status !in 200..299) {
            recordDiagnostics(
                "operation=$operation status=failed http=$status " +
                    "downloadedBytes=$downloadedBytes expectedBytes=${totalBytes ?: "unknown"} " +
                    "durationMs=${startedAt.elapsedNow().inWholeMilliseconds} errorKind=none message=none $urlSummary"
            )
            throw IllegalStateException("$failurePrefix: HTTP $status")
        }
        val contentType = response.headers.firstHeaderValue("Content-Type")?.substringBefore(';')?.trim()?.takeIf { it.isNotBlank() }
        recordDiagnostics(
            "operation=$operation status=complete http=$status " +
                "downloadedBytes=$downloadedBytes expectedBytes=${totalBytes ?: "unknown"} " +
                "contentType=${contentType ?: "unknown"} " +
                "durationMs=${startedAt.elapsedNow().inWholeMilliseconds} $urlSummary"
        )
        return contentType
    }

    /**
     * Runs one transfer: a cancelled caller logs `status=canceled` and rethrows; a transport failure
     * logs its kind and becomes the same `HTTP 0` IllegalStateException the NetworkKMM path threw.
     */
    private suspend fun runDownload(
        operation: String,
        startedAt: TimeSource.Monotonic.ValueTimeMark,
        urlSummary: String,
        downloadedBytes: () -> Long,
        totalBytes: () -> Long?,
        block: suspend () -> RaftStreamingGetResponse,
    ): RaftStreamingGetResponse =
        try {
            block()
        } catch (error: Throwable) {
            val kind = classifyRaftKtorFailure(error)
            if (error is CancellationException && kind == RaftTransportFailureKind.CANCELLED) {
                recordDiagnostics(
                    "operation=$operation status=canceled downloadedBytes=${downloadedBytes()} " +
                        "expectedBytes=${totalBytes() ?: "unknown"} " +
                        "durationMs=${startedAt.elapsedNow().inWholeMilliseconds} $urlSummary"
                )
                throw error
            }
            val safeMessage = error.message?.toSafeDiagnosticsToken() ?: "none"
            recordDiagnostics(
                "operation=$operation status=failed http=0 downloadedBytes=${downloadedBytes()} " +
                    "expectedBytes=${totalBytes() ?: "unknown"} " +
                    "durationMs=${startedAt.elapsedNow().inWholeMilliseconds} " +
                    "errorKind=${kind.name} message=$safeMessage $urlSummary"
            )
            throw IllegalStateException("Download failed: HTTP 0 (${kind.errorToken}: $safeMessage)", error)
        }

    private fun recordDiagnostics(message: String) {
        diagnosticsLogger?.record(DIAGNOSTICS_TAG, message)
    }

}

internal fun sharedBinaryConditionalHeaders(validators: SharedBinaryConditionalValidators?): Map<String, String> =
    buildMap {
        put("Accept", "*/*")
        validators?.eTag?.let { put("If-None-Match", it) }
        validators?.lastModified?.let { put("If-Modified-Since", it) }
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
