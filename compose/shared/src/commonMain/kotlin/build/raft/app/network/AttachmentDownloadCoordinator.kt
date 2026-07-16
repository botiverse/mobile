package build.raft.app.network

import com.tencent.kmm.network.export.NetworkBody
import com.tencent.kmm.network.export.NetworkRequest
import com.tencent.kmm.network.export.NetworkRequestPolicy
import com.tencent.kmm.network.export.NetworkResponse
import com.tencent.kmm.network.export.NetworkStreamTimeoutPolicy
import com.tencent.kmm.network.export.VBTransportMethod
import com.tencent.kmm.network.service.NetworkCall
import com.tencent.kmm.network.service.NetworkClient
import com.tencent.kmm.network.service.NetworkClientConfig
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.math.max
import kotlin.time.TimeSource

private val AttachmentDownloadMonotonicOrigin = TimeSource.Monotonic.markNow()

/** Progress emitted by the common download owner. Platform hosts only render this state. */
data class AttachmentDownloadProgress(
    val downloadId: String,
    val attemptToken: String,
    val sequence: Long,
    val phase: String,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val percent: Int?,
    val bytesPerSecond: Long?
)

data class AttachmentDownloadResult(
    val downloadId: String,
    val attemptToken: String,
    val status: String,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val contentType: String?,
    val errorKind: String?,
    val message: String?
)

/**
 * Host callback for the common coordinator. Implementations must return quickly; callback failures
 * are contained and converted into a failed attempt instead of escaping into NetworkKMM.
 */
interface AttachmentDownloadObserver {
    fun onProgress(progress: AttachmentDownloadProgress)

    fun onComplete(result: AttachmentDownloadResult)
}

/**
 * Single owner for attachment transfer lifecycle, attempt fencing and progress on every platform.
 *
 * NetworkKMM owns transport. [createAttachmentDownloadAtomicSink] owns only platform filesystem
 * staging and atomic publication. ArkTS/Android/iOS must not start a second HTTP transport.
 */
object AttachmentDownloadCoordinator {
    private val engine = AttachmentDownloadCoordinatorEngine()

    fun start(
        downloadId: String,
        targetKey: String,
        url: String,
        targetPath: String,
        observer: AttachmentDownloadObserver
    ): String =
        engine.start(
            request =
                AttachmentDownloadRequest(
                    downloadId = downloadId,
                    targetKey = targetKey,
                    url = url,
                    targetPath = targetPath
                ),
            observer = observer
        )

    fun control(downloadId: String, action: String): String = engine.control(downloadId, action)
}

internal data class AttachmentDownloadRequest(
    val downloadId: String,
    val targetKey: String,
    val url: String,
    val targetPath: String,
    val connectTimeoutMillis: Long = 30_000L,
    val responseHeadersTimeoutMillis: Long = 30_000L,
    val interChunkIdleTimeoutMillis: Long = 60_000L,
    val wholeTransferTimeoutMillis: Long = 15L * 60L * 1_000L
)

internal interface AttachmentDownloadAtomicSink {
    fun begin(): Boolean

    fun write(bytes: ByteArray): Boolean

    fun commit(): Boolean

    fun abort()
}

internal expect fun createAttachmentDownloadAtomicSink(
    targetPath: String,
    attemptToken: String
): AttachmentDownloadAtomicSink

internal expect fun attachmentDownloadPauseMillis(durationMillis: Long)

internal fun interface AttachmentDownloadSinkFactory {
    fun create(targetPath: String, attemptToken: String): AttachmentDownloadAtomicSink
}

internal interface AttachmentDownloadTransportCall {
    fun cancel()
}

internal class AttachmentDownloadTransportCallbacks(
    val onResponseStart: (statusCode: Int?, contentLength: Long?, headers: Map<String, List<String>>) -> Unit,
    val onChunk: (ByteArray) -> Unit,
    val onComplete: (NetworkResponse) -> Unit
)

internal fun interface AttachmentDownloadTransport {
    fun start(request: AttachmentDownloadRequest, callbacks: AttachmentDownloadTransportCallbacks): AttachmentDownloadTransportCall
}

internal class AttachmentDownloadCoordinatorEngine(
    private val transport: AttachmentDownloadTransport = NetworkKmmAttachmentDownloadTransport,
    private val sinkFactory: AttachmentDownloadSinkFactory =
        AttachmentDownloadSinkFactory(::createAttachmentDownloadAtomicSink),
    private val nowMillis: () -> Long = { AttachmentDownloadMonotonicOrigin.elapsedNow().inWholeMilliseconds }
) {
    private val lock = SynchronizedObject()
    private val activeByDownloadId = mutableMapOf<String, AttachmentDownloadAttempt>()
    private val activeByTargetKey = mutableMapOf<String, AttachmentDownloadAttempt>()
    private val nextAttempt = atomic(0L)

    fun start(request: AttachmentDownloadRequest, observer: AttachmentDownloadObserver): String {
        if (request.downloadId.isBlank() || request.targetKey.isBlank() || request.url.isBlank() || request.targetPath.isBlank()) {
            observer.safeComplete(
                AttachmentDownloadResult(
                    downloadId = request.downloadId,
                    attemptToken = "",
                    status = "failed",
                    downloadedBytes = 0L,
                    totalBytes = null,
                    contentType = null,
                    errorKind = "invalid_request",
                    message = "Missing download request field"
                )
            )
            return ""
        }
        val attemptToken =
            "${request.downloadId.hashCode().toUInt().toString(16)}-${nextAttempt.incrementAndGet()}"
        val attempt =
            AttachmentDownloadAttempt(
                request = request,
                attemptToken = attemptToken,
                sink = sinkFactory.create(request.targetPath, attemptToken),
                observer = observer,
                nowMillis = nowMillis,
                isCurrent = { candidate -> isCurrent(candidate) },
                onTerminal = { candidate -> removeIfCurrent(candidate) }
            )
        val replaced = mutableSetOf<AttachmentDownloadAttempt>()
        synchronized(lock) {
            activeByDownloadId.put(request.downloadId, attempt)?.let(replaced::add)
            activeByTargetKey.put(request.targetKey, attempt)?.let(replaced::add)
        }
        replaced.forEach { previous -> previous.cancel("replaced") }
        attempt.start(transport)
        return attemptToken
    }

    fun control(downloadId: String, action: String): String {
        val attempt = synchronized(lock) { activeByDownloadId[downloadId] } ?: return "missing"
        return when (action.lowercase()) {
            "pause" -> {
                attempt.pause()
                "paused"
            }
            "resume" -> {
                attempt.resume()
                "resumed"
            }
            "stop", "cancel" -> {
                attempt.cancel("cancelled")
                "cancelled"
            }
            else -> "failed"
        }
    }

    private fun isCurrent(attempt: AttachmentDownloadAttempt): Boolean =
        synchronized(lock) {
            activeByDownloadId[attempt.request.downloadId] === attempt &&
                activeByTargetKey[attempt.request.targetKey] === attempt
        }

    private fun removeIfCurrent(attempt: AttachmentDownloadAttempt) {
        synchronized(lock) {
            if (activeByDownloadId[attempt.request.downloadId] === attempt) {
                activeByDownloadId.remove(attempt.request.downloadId)
            }
            if (activeByTargetKey[attempt.request.targetKey] === attempt) {
                activeByTargetKey.remove(attempt.request.targetKey)
            }
        }
    }
}

private object NetworkKmmAttachmentDownloadTransport : AttachmentDownloadTransport {
    private val client: NetworkClient by lazy {
        ensureNetworkKmmInitialized { }
        NetworkClient(NetworkClientConfig())
    }

    override fun start(
        request: AttachmentDownloadRequest,
        callbacks: AttachmentDownloadTransportCallbacks
    ): AttachmentDownloadTransportCall {
        val networkCall: NetworkCall =
            client.downloadStream(
                request =
                    NetworkRequest(
                        method = VBTransportMethod.GET,
                        url = request.url,
                        body = NetworkBody.Empty,
                        policy =
                            NetworkRequestPolicy(
                                timeoutMillis = 0L,
                                streamTimeouts =
                                    NetworkStreamTimeoutPolicy(
                                        connectTimeoutMillis = request.connectTimeoutMillis,
                                        responseHeadersTimeoutMillis = request.responseHeadersTimeoutMillis,
                                        interChunkIdleTimeoutMillis = request.interChunkIdleTimeoutMillis,
                                        wholeTransferTimeoutMillis = request.wholeTransferTimeoutMillis
                                    )
                            )
                    ).apply { setHeader("Accept", "*/*") },
                onResponseStart = callbacks.onResponseStart,
                onChunk = callbacks.onChunk,
                onComplete = callbacks.onComplete
            )
        return object : AttachmentDownloadTransportCall {
            override fun cancel() {
                networkCall.cancel()
            }
        }
    }
}

private class AttachmentDownloadAttempt(
    val request: AttachmentDownloadRequest,
    private val attemptToken: String,
    private val sink: AttachmentDownloadAtomicSink,
    private val observer: AttachmentDownloadObserver,
    private val nowMillis: () -> Long,
    private val isCurrent: (AttachmentDownloadAttempt) -> Boolean,
    private val onTerminal: (AttachmentDownloadAttempt) -> Unit
) {
    private val callbackLock = SynchronizedObject()
    private val terminal = atomic(false)
    private val paused = atomic(false)
    private val callRef = atomic<AttachmentDownloadTransportCall?>(null)
    private var sequence = 0L
    private var downloadedBytes = 0L
    private var totalBytes: Long? = null
    private var contentType: String? = null
    private var previousSampleAtMillis: Long? = null
    private var previousSampleBytes = 0L
    private var lastEmittedAtMillis: Long? = null

    fun start(transport: AttachmentDownloadTransport) {
        if (!sink.begin()) {
            finishFailure("storage_failed", "Unable to create download staging file")
            return
        }
        val call =
            runCatching {
                transport.start(
                    request,
                    AttachmentDownloadTransportCallbacks(
                        onResponseStart = ::onResponseStart,
                        onChunk = ::onChunk,
                        onComplete = ::onComplete
                    )
                )
            }.getOrElse { error ->
                finishFailure("transport_start", error.message ?: "Unable to start download")
                return
            }
        callRef.value = call
        if (terminal.value) {
            runCatching { call.cancel() }
        }
    }

    fun pause() {
        if (!terminal.value) paused.value = true
    }

    fun resume() {
        synchronized(callbackLock) {
            previousSampleAtMillis = nowMillis()
            previousSampleBytes = downloadedBytes
            lastEmittedAtMillis = null
            paused.value = false
        }
    }

    fun cancel(reason: String) {
        synchronized(callbackLock) {
            if (!terminal.compareAndSet(expect = false, update = true)) return
            paused.value = false
            runCatching { callRef.value?.cancel() }
            runCatching { sink.abort() }
            onTerminal(this)
            observer.safeComplete(
                result(status = "cancelled", errorKind = reason, message = "Download cancelled")
            )
        }
    }

    private fun onResponseStart(statusCode: Int?, contentLength: Long?, headers: Map<String, List<String>>) {
        synchronized(callbackLock) {
            if (!canAcceptCallback()) return
            totalBytes = contentLength?.takeIf { it > 0L }
            contentType = headers.contentTypeHeader()
            previousSampleAtMillis = nowMillis()
            emitProgress(phase = "response_wait", percent = null, bytesPerSecond = null)
        }
    }

    private fun onChunk(bytes: ByteArray) {
        while (paused.value && !terminal.value) {
            attachmentDownloadPauseMillis(25L)
        }
        synchronized(callbackLock) {
            if (!canAcceptCallback()) return
            if (!runCatching { sink.write(bytes) }.getOrDefault(false)) {
                finishFailureLocked("storage_write", "Unable to write download staging file")
                return
            }
            downloadedBytes += bytes.size.toLong()
            val now = nowMillis()
            val percent = attachmentDownloadPercent(downloadedBytes, totalBytes)
            val lastAt = lastEmittedAtMillis
            val shouldEmit =
                lastAt == null || downloadedBytes == bytes.size.toLong() || now - lastAt >= 250L ||
                    (percent != null && percent >= 99)
            if (shouldEmit) {
                val previousAt = previousSampleAtMillis
                val deltaMillis = previousAt?.let { now - it } ?: 0L
                val deltaBytes = downloadedBytes - previousSampleBytes
                val bytesPerSecond =
                    if (previousSampleBytes > 0L && deltaMillis > 0L && deltaBytes > 0L) {
                        max(1L, deltaBytes * 1_000L / deltaMillis)
                    } else {
                        null
                    }
                previousSampleAtMillis = now
                previousSampleBytes = downloadedBytes
                lastEmittedAtMillis = now
                emitProgress("receiving", percent, bytesPerSecond)
            }
        }
    }

    private fun onComplete(response: NetworkResponse) {
        synchronized(callbackLock) {
            if (!canAcceptCallback()) return
            val statusCode = response.statusCode
            val error = response.error
            if (error != null || statusCode == null || statusCode !in 200..299) {
                val kind = error?.kind?.name?.lowercase() ?: "http_${statusCode ?: 0}"
                val message = error?.message ?: "Download failed: HTTP ${statusCode ?: 0}"
                finishFailureLocked(kind, message)
                return
            }
            contentType = contentType ?: response.headers.contentTypeHeader()
            if (!isCurrent(this)) {
                finishFailureLocked("replaced", "Download attempt was replaced")
                return
            }
            if (!runCatching { sink.commit() }.getOrDefault(false)) {
                finishFailureLocked("storage_commit", "Unable to publish downloaded file")
                return
            }
            if (!terminal.compareAndSet(expect = false, update = true)) return
            emitProgress(
                phase = "complete",
                percent = attachmentDownloadPercent(downloadedBytes, totalBytes)?.let { 100 },
                bytesPerSecond = null
            )
            onTerminal(this)
            observer.safeComplete(result(status = "complete", errorKind = null, message = null))
        }
    }

    private fun finishFailure(errorKind: String, message: String) {
        synchronized(callbackLock) { finishFailureLocked(errorKind, message) }
    }

    private fun finishFailureLocked(errorKind: String, message: String) {
        if (!terminal.compareAndSet(expect = false, update = true)) return
        paused.value = false
        runCatching { callRef.value?.cancel() }
        runCatching { sink.abort() }
        onTerminal(this)
        observer.safeComplete(result(status = "failed", errorKind = errorKind, message = message))
    }

    private fun canAcceptCallback(): Boolean = !terminal.value && isCurrent(this)

    private fun emitProgress(phase: String, percent: Int?, bytesPerSecond: Long?) {
        if (!canAcceptCallback() && phase != "complete") return
        val progress =
            AttachmentDownloadProgress(
                downloadId = request.downloadId,
                attemptToken = attemptToken,
                sequence = ++sequence,
                phase = phase,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                percent = percent,
                bytesPerSecond = bytesPerSecond
            )
        runCatching { observer.onProgress(progress) }
            .onFailure { error -> finishFailureLocked("progress_callback", error.message ?: "Progress callback failed") }
    }

    private fun result(status: String, errorKind: String?, message: String?): AttachmentDownloadResult =
        AttachmentDownloadResult(
            downloadId = request.downloadId,
            attemptToken = attemptToken,
            status = status,
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            contentType = contentType,
            errorKind = errorKind,
            message = message
        )
}

private fun AttachmentDownloadObserver.safeComplete(result: AttachmentDownloadResult) {
    runCatching { onComplete(result) }
}

private fun Map<String, List<String>>.contentTypeHeader(): String? =
    entries.firstOrNull { (name, _) -> name.equals("Content-Type", ignoreCase = true) }
        ?.value
        ?.firstOrNull()
        ?.substringBefore(';')
        ?.trim()
        ?.takeIf { it.isNotBlank() }

private fun attachmentDownloadPercent(downloadedBytes: Long, totalBytes: Long?): Int? {
    val total = totalBytes?.takeIf { it > 0L } ?: return null
    if (downloadedBytes <= 0L) return null
    if (downloadedBytes >= total) return 100
    return ((downloadedBytes * 100L) / total).toInt().coerceIn(1, 99)
}
