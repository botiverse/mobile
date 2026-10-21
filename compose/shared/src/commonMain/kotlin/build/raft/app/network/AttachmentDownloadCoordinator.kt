package build.raft.app.network

import build.raft.app.raftLogInfo
import com.tencent.kmm.network.export.NetworkBody
import com.tencent.kmm.network.export.NetworkRequest
import com.tencent.kmm.network.export.NetworkRequestPolicy
import com.tencent.kmm.network.export.NetworkResponse
import com.tencent.kmm.network.export.NetworkStreamTimeoutPolicy
import com.tencent.kmm.network.export.VBTransportMethod
import com.tencent.kmm.network.service.NetworkCall
import com.tencent.kmm.network.service.NetworkClient
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

data class AttachmentDownloadCleanupReservation(
    val token: String,
    val status: String
)

data class AttachmentDownloadDirectoryCleanupReservation(
    val token: String,
    val targetKeys: Set<String>
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
 * staging and atomic publication. Android/iOS must not start a second HTTP transport.
 */
object AttachmentDownloadCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engine = AttachmentDownloadCoordinatorEngine(watchdogScope = scope)

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

    fun control(downloadId: String, expectedAttemptToken: String? = null, action: String): String =
        engine.control(downloadId, expectedAttemptToken, action)

    /**
     * Controls only work that already belongs to the current export operation.
     *
     * Unlike [control], a missing pre-start attempt never creates a cancellation receipt for a
     * future operation that happens to reuse the same download id. Export cancellation also waits
     * for the attempt's sink cleanup before returning so the successor can safely start.
     */
    fun controlExisting(downloadId: String, expectedAttemptToken: String? = null, action: String): String =
        engine.controlExisting(downloadId, expectedAttemptToken, action)

    fun beginTargetCleanup(
        targetKey: String,
        expectedAttemptToken: String? = null
    ): AttachmentDownloadCleanupReservation =
        engine.beginTargetCleanup(targetKey, expectedAttemptToken)

    fun finishTargetCleanup(targetKey: String, token: String) = engine.finishTargetCleanup(targetKey, token)

    fun beginDirectoryCleanup(targetPrefix: String): AttachmentDownloadDirectoryCleanupReservation =
        engine.beginDirectoryCleanup(targetPrefix)

    fun finishDirectoryCleanup(targetPrefix: String, token: String) =
        engine.finishDirectoryCleanup(targetPrefix, token)
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

    /** Flushes and closes staging bytes without making them visible at the target path. */
    fun prepare(): Boolean

    /** Performs the same-directory atomic replacement after coordinator PUBLISHING admission. */
    fun publish(): Boolean

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
    private val watchdogScope: CoroutineScope? = null,
    private val beforePublishAdmission: (String) -> Unit = {},
    private val beforeMissingControlAdmission: (String) -> Unit = {},
    private val beforeChunkAdmission: (String) -> Unit = {},
    private val observerDispatcher: ((() -> Unit) -> Unit)? = null,
    private val nowMillis: () -> Long = { AttachmentDownloadMonotonicOrigin.elapsedNow().inWholeMilliseconds }
) {
    private val lock = SynchronizedObject()
    private val activeByDownloadId = mutableMapOf<String, AttachmentDownloadAttempt>()
    private val activeByTargetKey = mutableMapOf<String, AttachmentDownloadAttempt>()
    private val deletingTargetOwners = mutableMapOf<String, MutableSet<String>>()
    private val deletingDirectoryOwners = mutableMapOf<String, MutableSet<String>>()
    private val pendingPreStartCancels = linkedMapOf<String, Long>()
    private val nextAttempt = atomic(0L)
    private val nextCleanupReservation = atomic(0L)

    init {
        watchdogScope?.launch {
            while (isActive) {
                delay(100L)
                checkTimeouts(nowMillis())
            }
        }
    }

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
                admitPublishIfCurrent = { candidate -> admitPublishIfCurrent(candidate) },
                beforePublishAdmission = beforePublishAdmission,
                beforeChunkAdmission = beforeChunkAdmission,
                dispatchCleanup = { cleanup ->
                    if (watchdogScope == null) cleanup() else watchdogScope.launch { cleanup() }
                },
                dispatchObserver = { drain ->
                    when {
                        observerDispatcher != null -> observerDispatcher.invoke(drain)
                        watchdogScope == null -> drain()
                        else -> watchdogScope.launch { drain() }
                    }
                },
                onTerminal = { candidate -> removeIfCurrent(candidate) }
            )
        val replaced = mutableSetOf<AttachmentDownloadAttempt>()
        val cancelledBeforeStartReason =
            synchronized(lock) {
                prunePendingPreStartCancelsLocked(nowMillis())
                when {
                    deletingTargetOwners[request.targetKey].orEmpty().isNotEmpty() ->
                        "target_deleted"
                    deletingDirectoryOwners.any { (prefix, owners) ->
                        owners.isNotEmpty() && request.targetKey.startsWith(prefix)
                    } ->
                        "target_directory_deleted"
                    pendingPreStartCancels.remove(request.downloadId) != null ->
                        "cancelled_before_start"
                    activeByTargetKey[request.targetKey]?.isPublishing() == true ->
                        "target_publishing"
                    activeByDownloadId[request.downloadId]?.isPublishing() == true ->
                        "download_publishing"
                    else -> {
                        activeByDownloadId.put(request.downloadId, attempt)?.let(replaced::add)
                        activeByTargetKey.put(request.targetKey, attempt)?.let(replaced::add)
                        null
                    }
                }
            }
        if (cancelledBeforeStartReason != null) {
            attempt.cancel(cancelledBeforeStartReason)
            return attemptToken
        }
        replaced.forEach { previous -> previous.cancel("replaced") }
        attempt.start(transport)
        return attemptToken
    }

    fun control(downloadId: String, expectedAttemptToken: String?, action: String): String =
        control(
            downloadId = downloadId,
            expectedAttemptToken = expectedAttemptToken,
            action = action,
            recordPreStartCancel = true,
            synchronousCancelCleanup = false
        )

    fun controlExisting(downloadId: String, expectedAttemptToken: String?, action: String): String =
        control(
            downloadId = downloadId,
            expectedAttemptToken = expectedAttemptToken,
            action = action,
            recordPreStartCancel = false,
            synchronousCancelCleanup = true
        )

    private fun control(
        downloadId: String,
        expectedAttemptToken: String?,
        action: String,
        recordPreStartCancel: Boolean,
        synchronousCancelCleanup: Boolean
    ): String {
        if (downloadId.isBlank()) return "missing"
        beforeMissingControlAdmission(downloadId)
        val attemptOrReceipt =
            synchronized(lock) {
                val active = activeByDownloadId[downloadId]
                if (active != null) return@synchronized ControlAdmission.Active(active)
                if (recordPreStartCancel &&
                    (action.equals("stop", ignoreCase = true) || action.equals("cancel", ignoreCase = true)) &&
                    expectedAttemptToken.isNullOrBlank()
                ) {
                    val now = nowMillis()
                    prunePendingPreStartCancelsLocked(now)
                    pendingPreStartCancels.remove(downloadId)
                    pendingPreStartCancels[downloadId] = now
                    while (pendingPreStartCancels.size > MaxPendingPreStartCancels) {
                        pendingPreStartCancels.remove(pendingPreStartCancels.keys.first())
                    }
                    ControlAdmission.Receipt
                } else {
                    ControlAdmission.Missing
                }
            }
        if (attemptOrReceipt === ControlAdmission.Receipt) return "cancelled"
        if (attemptOrReceipt === ControlAdmission.Missing) return "missing"
        val attempt = (attemptOrReceipt as ControlAdmission.Active).attempt
        if (!expectedAttemptToken.isNullOrBlank() && attempt.attemptToken != expectedAttemptToken) return "stale"
        return when (action.lowercase()) {
            "pause" -> if (attempt.pause()) "paused" else "finishing"
            "resume" -> if (attempt.resume()) "resumed" else "finishing"
            "stop", "cancel" ->
                if (attempt.cancel("cancelled", synchronousCleanup = synchronousCancelCleanup)) {
                    "cancelled"
                } else {
                    "finishing"
                }
            else -> "failed"
        }
    }

    fun beginTargetCleanup(
        targetKey: String,
        expectedAttemptToken: String? = null
    ): AttachmentDownloadCleanupReservation {
        val reservationToken = "cleanup-${nextCleanupReservation.incrementAndGet()}"
        var staleAttempt = false
        val attempt =
            synchronized(lock) {
                val active = activeByTargetKey[targetKey]
                if (!expectedAttemptToken.isNullOrBlank() &&
                    active != null &&
                    active.attemptToken != expectedAttemptToken
                ) {
                    staleAttempt = true
                    null
                } else {
                    deletingTargetOwners.getOrPut(targetKey) { mutableSetOf() } += reservationToken
                    active
                }
            }
        if (staleAttempt) return AttachmentDownloadCleanupReservation(token = "", status = "stale")
        val status =
            when {
                attempt == null -> "missing"
                attempt.cancel("target_deleted", synchronousCleanup = true) -> "cancelled"
                attempt.awaitTerminalCleanup() -> "complete"
                else -> "timeout"
            }
        return AttachmentDownloadCleanupReservation(reservationToken, status)
    }

    fun finishTargetCleanup(targetKey: String, token: String) {
        synchronized(lock) {
            val owners = deletingTargetOwners[targetKey] ?: return@synchronized
            owners.remove(token)
            if (owners.isEmpty()) deletingTargetOwners.remove(targetKey)
        }
    }

    fun beginDirectoryCleanup(targetPrefix: String): AttachmentDownloadDirectoryCleanupReservation {
        require(targetPrefix.isNotBlank())
        val reservationToken = "directory-cleanup-${nextCleanupReservation.incrementAndGet()}"
        val targetKeys =
            synchronized(lock) {
                deletingDirectoryOwners.getOrPut(targetPrefix) { mutableSetOf() } += reservationToken
                activeByTargetKey.keys.filterTo(linkedSetOf()) { it.startsWith(targetPrefix) }
            }
        return AttachmentDownloadDirectoryCleanupReservation(reservationToken, targetKeys)
    }

    fun finishDirectoryCleanup(targetPrefix: String, token: String) {
        synchronized(lock) {
            val owners = deletingDirectoryOwners[targetPrefix] ?: return@synchronized
            owners.remove(token)
            if (owners.isEmpty()) deletingDirectoryOwners.remove(targetPrefix)
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

    private fun admitPublishIfCurrent(attempt: AttachmentDownloadAttempt): Boolean =
        synchronized(lock) {
            if (activeByDownloadId[attempt.request.downloadId] !== attempt ||
                activeByTargetKey[attempt.request.targetKey] !== attempt
            ) {
                false
            } else {
                attempt.tryAdmitPublishing()
            }
        }

    internal fun checkTimeouts(nowMillis: Long = this.nowMillis()) {
        val attempts = synchronized(lock) { activeByDownloadId.values.distinct() }
        attempts.forEach { attempt -> attempt.checkTimeout(nowMillis) }
    }

    private fun prunePendingPreStartCancelsLocked(nowMillis: Long) {
        pendingPreStartCancels.entries.removeAll { (_, recordedAtMillis) ->
            nowMillis - recordedAtMillis >= PendingPreStartCancelTtlMillis
        }
    }

    private companion object {
        const val MaxPendingPreStartCancels = 256
        const val PendingPreStartCancelTtlMillis = 60_000L
    }

    private sealed interface ControlAdmission {
        data class Active(val attempt: AttachmentDownloadAttempt) : ControlAdmission
        data object Receipt : ControlAdmission
        data object Missing : ControlAdmission
    }
}

private object NetworkKmmAttachmentDownloadTransport : AttachmentDownloadTransport {
    private val client: NetworkClient by lazy {
        ensureNetworkKmmInitialized { }
        NetworkClient(
            raftNetworkClientConfig(
                diagnosticsLogger = { message ->
                    raftLogInfo("SlockAttachmentDownload", message)
                }
            )
        )
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
    val attemptToken: String,
    private val sink: AttachmentDownloadAtomicSink,
    private val observer: AttachmentDownloadObserver,
    private val nowMillis: () -> Long,
    private val isCurrent: (AttachmentDownloadAttempt) -> Boolean,
    private val admitPublishIfCurrent: (AttachmentDownloadAttempt) -> Boolean,
    private val beforePublishAdmission: (String) -> Unit,
    private val beforeChunkAdmission: (String) -> Unit,
    private val dispatchCleanup: ((() -> Unit) -> Unit),
    private val dispatchObserver: ((() -> Unit) -> Unit),
    private val onTerminal: (AttachmentDownloadAttempt) -> Unit
) {
    private val operationLock = SynchronizedObject()
    private val sinkLock = SynchronizedObject()
    private val observerGateLock = SynchronizedObject()
    private val state = atomic(StateActive)
    private val paused = atomic(false)
    private val callRef = atomic<AttachmentDownloadTransportCall?>(null)
    private val sinkState = atomic(SinkNotStarted)
    private val publishedDownloadedBytes = atomic(0L)
    private val publishedTotalBytes = atomic<Long?>(null)
    private val publishedContentType = atomic<String?>(null)
    private val startedAtMillis = nowMillis()
    private val responseStartedAtMillis = atomic<Long?>(null)
    private val lastActivityAtMillis = atomic(startedAtMillis)
    private var sequence = 0L
    private var downloadedBytes = 0L
    private var totalBytes: Long? = null
    private var responseStarted = false
    private var contentType: String? = null
    private var previousSampleAtMillis: Long? = null
    private var previousSampleBytes = 0L
    private var lastEmittedAtMillis: Long? = null
    private val observerEventQueue = mutableListOf<() -> Unit>()
    private var observerQueueDraining = false
    private var terminalObserverQueued = false

    fun start(transport: AttachmentDownloadTransport) {
        if (!isActiveAndCurrent()) return
        val began =
            synchronized(sinkLock) {
                if (!isActiveAndCurrent()) return@synchronized false
                if (!runCatching { sink.begin() }.getOrDefault(false)) return@synchronized false
                if (!sinkState.compareAndSet(expect = SinkNotStarted, update = SinkActive)) {
                    runCatching { sink.abort() }
                    return@synchronized false
                }
                true
            }
        if (!began) {
            if (state.value != StateActive) return
            finishFailure("storage_failed", "Unable to create download staging file")
            return
        }
        if (!isActiveAndCurrent()) {
            abortSinkSerialized()
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
        if (state.value != StateActive || !isCurrent(this)) {
            runCatching { call.cancel() }
            abortSinkSerialized()
        }
    }

    fun pause(): Boolean =
        synchronized(sinkLock) {
            if (state.value != StateActive) return@synchronized false
            paused.value = true
            true
        }

    fun resume(): Boolean {
        synchronized(sinkLock) {
            if (state.value != StateActive) return false
            val now = nowMillis()
            synchronized(operationLock) {
                previousSampleAtMillis = now
                previousSampleBytes = downloadedBytes
                lastEmittedAtMillis = null
                lastActivityAtMillis.value = now
            }
            paused.value = false
        }
        return true
    }

    fun cancel(reason: String, synchronousCleanup: Boolean = false): Boolean {
        var wonTerminal = false
        while (!wonTerminal) {
            val current = state.value
            if (current != StateActive && current != StateFinishing) return false
            wonTerminal = state.compareAndSet(expect = current, update = StateTerminal)
        }
        paused.value = false
        runCatching { callRef.value?.cancel() }
        onTerminal(this)
        queueTerminal(result(status = "cancelled", errorKind = reason, message = "Download cancelled"))
        val cleanup = ::abortSinkSerialized
        if (synchronousCleanup) cleanup() else dispatchCleanup(cleanup)
        return true
    }

    fun checkTimeout(nowMillis: Long) {
        val currentState = state.value
        if (currentState != StateActive && currentState != StateFinishing) return
        val wholeDeadline = request.wholeTransferTimeoutMillis
        val wholeElapsed = nowMillis - startedAtMillis
        if (wholeDeadline > 0L && wholeElapsed >= wholeDeadline) {
            finishPrePublishFailure("whole_timeout", "Download exceeded whole-transfer deadline")
            return
        }
        if (currentState != StateActive) return
        val responseAt = responseStartedAtMillis.value
        if (responseAt == null) {
            val responseBudget = saturatingAdd(request.connectTimeoutMillis, request.responseHeadersTimeoutMillis)
            if (responseBudget > 0L && wholeElapsed >= responseBudget) {
                finishFailure("response_headers_timeout", "Download response headers timed out")
            }
            return
        }
        val idleBudget = request.interChunkIdleTimeoutMillis
        // User pause freezes the inter-chunk idle clock; whole-transfer deadline still includes pause.
        if (paused.value) return
        if (idleBudget > 0L && nowMillis - lastActivityAtMillis.value >= idleBudget) {
            finishFailure("idle_timeout", "Download inter-chunk idle deadline exceeded")
        }
    }

    fun awaitTerminalCleanup(timeoutMillis: Long = 30_000L): Boolean {
        val started = nowMillis()
        while (state.value != StateTerminal || sinkState.value != SinkEnded) {
            if (nowMillis() - started >= timeoutMillis) return false
            attachmentDownloadPauseMillis(10L)
        }
        return true
    }

    private fun onResponseStart(statusCode: Int?, contentLength: Long?, headers: Map<String, List<String>>) {
        val progress =
            synchronized(operationLock) {
                if (!isActiveAndCurrent()) return
                responseStarted = true
                totalBytes = contentLength?.takeIf { it >= 0L }
                contentType = headers.contentTypeHeader()
                publishedTotalBytes.value = totalBytes
                publishedContentType.value = contentType
                responseStartedAtMillis.value = nowMillis()
                lastActivityAtMillis.value = responseStartedAtMillis.value ?: startedAtMillis
                previousSampleAtMillis = nowMillis()
                progressLocked(phase = "response_wait", percent = null, bytesPerSecond = null)
            }
        deliverProgress(progress)
    }

    private fun onChunk(bytes: ByteArray) {
        var failure: Pair<String, String>? = null
        var wrote = false
        while (!wrote) {
            beforeChunkAdmission(attemptToken)
            val admission =
                synchronized(sinkLock) {
                    when {
                        !isActiveAndCurrent() || sinkState.value != SinkActive -> ChunkAdmission.Terminal
                        paused.value -> ChunkAdmission.Paused
                        runCatching { sink.write(bytes) }.getOrDefault(false) -> ChunkAdmission.Written
                        else -> ChunkAdmission.Failed
                    }
                }
            when (admission) {
                ChunkAdmission.Terminal -> return
                ChunkAdmission.Paused -> attachmentDownloadPauseMillis(25L)
                ChunkAdmission.Written -> wrote = true
                ChunkAdmission.Failed -> {
                    failure = "storage_write" to "Unable to write download staging file"
                    break
                }
            }
        }
        val progress =
            synchronized(operationLock) {
                if (!isActiveAndCurrent()) return
                if (failure != null) {
                    null
                } else {
                    downloadedBytes = saturatingAdd(downloadedBytes, bytes.size.toLong())
                    publishedDownloadedBytes.value = downloadedBytes
                    lastActivityAtMillis.value = nowMillis()
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
                                max(1L, saturatingMultiply(deltaBytes, 1_000L) / deltaMillis)
                            } else {
                                null
                            }
                        previousSampleAtMillis = now
                        previousSampleBytes = downloadedBytes
                        lastEmittedAtMillis = now
                        progressLocked("receiving", percent, bytesPerSecond)
                    } else {
                        null
                    }
                }
            }
        failure?.let { (kind, message) ->
            finishFailure(kind, message)
            return
        }
        progress?.let(::deliverProgress)
    }

    private fun onComplete(response: NetworkResponse) {
        if (!isActiveAndCurrent()) return
        val statusCode = response.statusCode
        val error = response.error
        if (error != null || statusCode == null || statusCode !in 200..299) {
            val kind = error?.kind?.name?.lowercase() ?: "http_${statusCode ?: 0}"
            val message = error?.message ?: "Download failed: HTTP ${statusCode ?: 0}"
            finishFailure(kind, message)
            return
        }
        val sizeFailure =
            synchronized(operationLock) {
                when {
                    !responseStarted -> "missing_response_start" to "Download completed without response start"
                    totalBytes != null && downloadedBytes != totalBytes ->
                        "content_length_mismatch" to
                            "Expected ${totalBytes ?: 0L} bytes but received $downloadedBytes"
                    else -> null
                }
            }
        if (sizeFailure != null) {
            finishFailure(sizeFailure.first, sizeFailure.second)
            return
        }
        val finalProgress =
            synchronized(operationLock) {
                if (!isActiveAndCurrent()) return
                contentType = contentType ?: response.headers.contentTypeHeader()
                publishedContentType.value = contentType
                if (!state.compareAndSet(expect = StateActive, update = StateFinishing)) return
                progressLocked(
                    phase = "complete",
                    percent = totalBytes?.let { 100 },
                    bytesPerSecond = null
                )
            }
        enqueueObserverEvent { finishSuccessAfterFinalProgress(finalProgress) }
    }

    private fun finishSuccessAfterFinalProgress(finalProgress: AttachmentDownloadProgress) {
        if (state.value != StateFinishing) return
        val finalProgressFailure = runCatching { observer.onProgress(finalProgress) }.exceptionOrNull()
        if (finalProgressFailure != null) {
            finishReservedFailure("progress_callback", finalProgressFailure.message ?: "Progress callback failed")
            return
        }
        val prepared =
            synchronized(sinkLock) {
                if (state.value != StateFinishing || sinkState.value != SinkActive) return@synchronized false
                runCatching { sink.prepare() }.getOrDefault(false)
            }
        if (!prepared) {
            if (state.value != StateFinishing) return
            finishReservedFailure("storage_prepare", "Unable to prepare downloaded file")
            return
        }
        beforePublishAdmission(attemptToken)
        if (!admitPublishIfCurrent(this)) {
            if (state.value == StateFinishing) {
                finishReservedFailure("stale_attempt", "Download was replaced before publication")
            }
            return
        }
        val published =
            synchronized(sinkLock) {
                if (state.value != StatePublishing || sinkState.value != SinkActive) return@synchronized false
                runCatching { sink.publish() }.getOrDefault(false)
            }
        if (!published) {
            finishPublishingFailure("storage_publish", "Unable to publish downloaded file")
            return
        }
        sinkState.value = SinkEnded
        if (!state.compareAndSet(expect = StatePublishing, update = StateTerminal)) return
        onTerminal(this)
        queueTerminal(result(status = "complete", errorKind = null, message = null))
    }

    fun tryAdmitPublishing(): Boolean =
        state.compareAndSet(expect = StateFinishing, update = StatePublishing)

    fun isPublishing(): Boolean = state.value == StatePublishing

    private fun finishFailure(errorKind: String, message: String) {
        if (!state.compareAndSet(expect = StateActive, update = StateTerminal)) return
        paused.value = false
        runCatching { callRef.value?.cancel() }
        onTerminal(this)
        queueTerminal(result(status = "failed", errorKind = errorKind, message = message))
        val cleanup = ::abortSinkSerialized
        dispatchCleanup(cleanup)
    }

    private fun finishReservedFailure(errorKind: String, message: String) {
        if (!state.compareAndSet(expect = StateFinishing, update = StateTerminal)) return
        paused.value = false
        onTerminal(this)
        queueTerminal(result(status = "failed", errorKind = errorKind, message = message))
        dispatchCleanup(::abortSinkSerialized)
    }

    private fun finishPublishingFailure(errorKind: String, message: String) {
        if (!state.compareAndSet(expect = StatePublishing, update = StateTerminal)) return
        paused.value = false
        onTerminal(this)
        queueTerminal(result(status = "failed", errorKind = errorKind, message = message))
        dispatchCleanup(::abortSinkSerialized)
    }

    private fun deliverProgress(progress: AttachmentDownloadProgress) {
        enqueueObserverEvent {
            runCatching { observer.onProgress(progress) }
                .onFailure { error -> finishPrePublishFailure("progress_callback", error.message ?: "Progress callback failed") }
        }
    }

    private fun finishPrePublishFailure(errorKind: String, message: String) {
        var wonTerminal = false
        while (!wonTerminal) {
            val current = state.value
            if (current != StateActive && current != StateFinishing) return
            wonTerminal = state.compareAndSet(expect = current, update = StateTerminal)
        }
        paused.value = false
        runCatching { callRef.value?.cancel() }
        onTerminal(this)
        queueTerminal(result(status = "failed", errorKind = errorKind, message = message))
        dispatchCleanup(::abortSinkSerialized)
    }

    private fun queueTerminal(result: AttachmentDownloadResult) {
        val shouldDrain =
            synchronized(observerGateLock) {
                if (terminalObserverQueued) {
                    false
                } else {
                    terminalObserverQueued = true
                    observerEventQueue += { observer.safeComplete(result) }
                    if (observerQueueDraining) {
                        false
                    } else {
                        observerQueueDraining = true
                        true
                    }
                }
            }
        if (shouldDrain) dispatchObserver(::drainObserverQueue)
    }

    private fun enqueueObserverEvent(event: () -> Unit) {
        val shouldDrain =
            synchronized(observerGateLock) {
                if (terminalObserverQueued) {
                    false
                } else {
                    observerEventQueue += event
                    if (observerQueueDraining) {
                        false
                    } else {
                        observerQueueDraining = true
                        true
                    }
                }
            }
        if (!shouldDrain) return
        dispatchObserver(::drainObserverQueue)
    }

    private fun drainObserverQueue() {
        while (true) {
            val next =
                synchronized(observerGateLock) {
                    if (observerEventQueue.isEmpty()) {
                        observerQueueDraining = false
                        null
                    } else {
                        observerEventQueue.removeAt(0)
                    }
                } ?: return
            runCatching(next)
        }
    }

    private fun progressLocked(phase: String, percent: Int?, bytesPerSecond: Long?): AttachmentDownloadProgress =
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

    private fun abortSinkSerialized() {
        synchronized(sinkLock) {
            if (sinkState.getAndSet(SinkEnded) == SinkActive) {
                runCatching { sink.abort() }
            }
        }
    }

    private fun isActiveAndCurrent(): Boolean = state.value == StateActive && isCurrent(this)

    private fun result(status: String, errorKind: String?, message: String?): AttachmentDownloadResult =
        AttachmentDownloadResult(
            downloadId = request.downloadId,
            attemptToken = attemptToken,
            status = status,
            downloadedBytes = publishedDownloadedBytes.value,
            totalBytes = publishedTotalBytes.value,
            contentType = publishedContentType.value,
            errorKind = errorKind,
            message = message
        )

    private companion object {
        const val StateActive = 0
        const val StateFinishing = 1
        const val StatePublishing = 2
        const val StateTerminal = 3
        const val SinkNotStarted = 0
        const val SinkActive = 1
        const val SinkEnded = 2
    }

    private enum class ChunkAdmission {
        Terminal,
        Paused,
        Written,
        Failed
    }
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

internal fun attachmentDownloadPercent(downloadedBytes: Long, totalBytes: Long?): Int? {
    val total = totalBytes?.takeIf { it > 0L } ?: return null
    if (downloadedBytes <= 0L) return null
    if (downloadedBytes >= total) return 100
    return ((downloadedBytes.toDouble() / total.toDouble()) * 100.0).toInt().coerceIn(1, 99)
}

internal fun saturatingAdd(left: Long, right: Long): Long =
    when {
        right > 0L && left > Long.MAX_VALUE - right -> Long.MAX_VALUE
        right < 0L && left < Long.MIN_VALUE - right -> Long.MIN_VALUE
        else -> left + right
    }

internal fun saturatingMultiply(left: Long, right: Long): Long {
    if (left == 0L || right == 0L) return 0L
    if (left > 0L && right > 0L && left > Long.MAX_VALUE / right) return Long.MAX_VALUE
    if (left < 0L && right < 0L && left < Long.MAX_VALUE / right) return Long.MAX_VALUE
    if (left > 0L && right < 0L && right < Long.MIN_VALUE / left) return Long.MIN_VALUE
    if (left < 0L && right > 0L && left < Long.MIN_VALUE / right) return Long.MIN_VALUE
    return left * right
}
