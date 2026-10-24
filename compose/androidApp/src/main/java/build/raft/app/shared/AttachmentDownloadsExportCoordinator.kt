package build.raft.app.shared

import build.raft.app.transfer.RaftTransferMeter
import build.raft.app.transfer.RaftTransferProgress
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal data class AttachmentDownloadsExportRequest(
    val downloadId: String,
    val operationId: String = "",
    val source: File,
    val displayName: String,
    val mimeType: String,
    val owner: AndroidAttachmentOwnerLease? = null
)

/** One sample of a MediaStore export: identity, the wire [phase] word and the export's meter state. */
internal data class AttachmentDownloadsExportProgress(
    val downloadId: String,
    val attemptToken: String,
    val sequence: Long,
    val phase: String,
    val transfer: RaftTransferProgress
)

internal data class AttachmentDownloadsExportResult(
    val status: String,
    val message: String,
    val uri: String? = null,
    val fileName: String? = null,
    val sizeBytes: Long = 0L,
    val failurePhase: String? = null,
    /** Internal, non-user-visible storage diagnosis. Never bridge raw URI/name/path details. */
    val storageDiagnosticCode: String? = null,
    val storageVerificationAttempts: Int = 0,
    val storageCommitAttempts: Int = 0
)

internal interface AttachmentDownloadsExportBackend {
    fun insert(displayName: String, mimeType: String): String?
    fun openOutput(uri: String): OutputStream?
    fun publish(uri: String): Boolean
    /** Deletes only while IS_PENDING=1, so a late successful publish can never be removed. */
    fun deletePending(uri: String): Boolean
    fun rowState(uri: String): AttachmentDownloadsRowState
}

internal enum class AttachmentDownloadsRowState {
    Pending,
    Published,
    Missing,
    Unknown
}

/**
 * Owns the cache -> public Downloads export lifecycle.
 *
 * MediaStore calls are allowed to block inside platform code, so the bridge thread must never be
 * the terminal authority. A watchdog publishes a bounded terminal, cancels the worker, and removes
 * the pending row. Same-source/name exports join one state instead of creating concurrent pending
 * MediaStore rows.
 */
internal class AttachmentDownloadsExportCoordinator(
    private val worker: ExecutorService = Executors.newCachedThreadPool(exportThreadFactory("worker")),
    private val watchdog: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor(exportThreadFactory("watchdog")),
    private val cleanupWorker: ExecutorService =
        Executors.newCachedThreadPool(exportThreadFactory("cleanup")),
    private val terminalWorker: ExecutorService =
        Executors.newCachedThreadPool(exportThreadFactory("terminal")),
    private val observerWorker: ExecutorService =
        Executors.newCachedThreadPool(exportThreadFactory("observer")),
    private val stageTimeoutMs: Long = 45_000L,
    private val wholeTimeoutMs: Long = 150_000L,
    private val copyBufferBytes: Int = 256 * 1024,
    private val progressCadenceMs: Long = 250L,
    private val cleanupAttemptTimeoutMs: Long = 2_000L,
    private val cleanupAttempts: Int = 3,
    private val insertAdmissionWaitMs: Long = 2_000L,
    private val nowNanos: () -> Long = System::nanoTime,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val cancelReceiptTtlMs: Long = 60_000L,
    private val deferredCleanupDelayMs: Long = 5_000L,
    private val afterInsertOwnerAcquired: () -> Unit = {},
    private val beforeOwnerRegistration: () -> Unit = {},
    private val afterQueuedReservation: () -> Unit = {},
    private val ownerRegistry: AndroidAttachmentOwnerRegistry = ProcessAndroidAttachmentOwners
) {
    private val lock = Any()
    private val activeByKey = mutableMapOf<String, ExportState>()
    private val activeByDownloadId = mutableMapOf<String, ExportState>()
    private val sourceDirectoryCleanupOwners = mutableMapOf<String, MutableSet<String>>()
    private val cancelReceipts = linkedMapOf<String, Long>()

    fun export(
        request: AttachmentDownloadsExportRequest,
        backend: AttachmentDownloadsExportBackend,
        observer: ((AttachmentDownloadsExportProgress) -> Unit)? = null
    ): AttachmentDownloadsExportResult =
        exportIfCurrent(request, backend, operationIsCurrent = { true }, observer = observer)

    fun exportIfCurrent(
        request: AttachmentDownloadsExportRequest,
        backend: AttachmentDownloadsExportBackend,
        operationIsCurrent: () -> Boolean,
        observer: ((AttachmentDownloadsExportProgress) -> Unit)? = null
    ): AttachmentDownloadsExportResult {
        require(request.downloadId.isNotBlank())
        if (!operationIsCurrent()) return cancelledOperationResult()
        beforeOwnerRegistration()
        val ownerPrefix = request.owner?.let { "${it.scopeId}\u0000${it.generation}\u0000" }.orEmpty()
        val key = "$ownerPrefix${request.source.absolutePath}\u0000${request.displayName}"
        val queueDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(wholeTimeoutMs)
        while (true) {
            var registration: ObserverRegistration? = null
            var state: ExportState? = null
            var reservedFinishedState: ExportState? = null
            var start = false
            var queuedReservation: java.util.concurrent.CountDownLatch? = null
            val stateRef = AtomicReference<ExportState?>()
            val registerState = {
                synchronized(lock) {
                    if (!operationIsCurrent()) return@synchronized cancelledOperationResult()
                    if (sourceDirectoryCleanupOwners.any { (prefix, owners) ->
                            owners.isNotEmpty() && request.source.absolutePath.startsWith(prefix)
                        }) {
                        return@synchronized AttachmentDownloadsExportResult(
                            status = "storage_failed",
                            message = "Attachment staging is being cleared",
                            failurePhase = "private_cleanup"
                        )
                    }
                    pruneCancelReceiptsLocked()
                    val controlKey = controlKey(request.downloadId, request.owner, request.operationId)
                    if (request.operationId.isBlank() && cancelReceipts.remove(controlKey) != null) {
                        return@synchronized AttachmentDownloadsExportResult(
                            status = "cancelled",
                            message = "Save stopped"
                        )
                    }
                    val existing = activeByKey[key]
                    if (existing != null && existing.request.operationId != request.operationId) {
                        // The wait must happen after registerOperation releases the owner gate.
                        // Otherwise logout/scope retirement can block behind slow MediaStore cleanup.
                        queuedReservation = existing.keyReservationReleased
                        return@synchronized null
                    }
                    if (existing != null && existing.finished.get()) {
                        reservedFinishedState = existing
                    } else {
                        val reserved = existing ?: ExportState(key, request, backend).also {
                            activeByKey[key] = it
                            start = true
                        }
                        state = reserved
                        stateRef.set(reserved)
                        registration = reserved.addObserver(request.downloadId, observer)
                        activeByDownloadId[controlKey] = reserved
                    }
                    null
                }
            }
            val ownerRegistration =
                request.owner?.let { owner ->
                    ownerRegistry.registerOperation(
                        lease = owner,
                        operationId = "export:${request.operationId.ifBlank { request.downloadId }}",
                        hook = AndroidAttachmentRetirementHook {
                            stateRef.get()?.invalidateOwnerLocked()
                        },
                        block = registerState
                    ) ?: return AttachmentDownloadsExportResult(
                        status = "cancelled",
                        message = "Save stopped after account changed"
                    )
                }
            val immediate =
                if (request.owner == null) {
                    registerState()
                } else {
                    checkNotNull(ownerRegistration).first
                }
            if (immediate != null) {
                ownerRegistration?.second?.close()
                return immediate
            }
            val queued = queuedReservation
            if (queued != null) {
                ownerRegistration?.second?.close()
                afterQueuedReservation()
                val remainingNanos = queueDeadline - System.nanoTime()
                if (remainingNanos <= 0L || !queued.await(remainingNanos, TimeUnit.NANOSECONDS)) {
                    return AttachmentDownloadsExportResult(
                        status = "storage_failed",
                        message = "Previous save cleanup did not finish",
                        failurePhase = "export_cleanup"
                    )
                }
                continue
            }
            reservedFinishedState?.let { finishedState ->
                ownerRegistration?.second?.close()
                return finishedState.terminal.join()
            }
            val reserved = checkNotNull(state)
            ownerRegistration?.second?.let(reserved::installOwnerRegistration)
            val activeRegistration = checkNotNull(registration)
            reserved.snapshot.get()?.let { activeRegistration.admit(it) }
            if (!operationIsCurrent()) {
                finish(reserved, cancelledOperationResult(), abortPending = true)
            } else if (start) {
                start(reserved)
            }
            return reserved.terminal.join()
        }
    }

    fun control(
        downloadId: String,
        expectedAttemptToken: String?,
        action: String,
        owner: AndroidAttachmentOwnerLease? = null
    ): String = control(downloadId, "", expectedAttemptToken, action, owner, existingOnly = false)

    fun controlExisting(
        downloadId: String,
        operationId: String,
        expectedAttemptToken: String?,
        action: String,
        owner: AndroidAttachmentOwnerLease? = null
    ): String = control(downloadId, operationId, expectedAttemptToken, action, owner, existingOnly = true)

    private fun control(
        downloadId: String,
        operationId: String,
        expectedAttemptToken: String?,
        action: String,
        owner: AndroidAttachmentOwnerLease?,
        existingOnly: Boolean
    ): String {
        if (owner != null && !ownerRegistry.admit(owner)) return "missing"
        val state =
            synchronized(lock) {
                pruneCancelReceiptsLocked()
                val controlKey = controlKey(downloadId, owner, operationId)
                activeByDownloadId[controlKey]
                    ?: if (!existingOnly && operationId.isBlank() && action.lowercase() in setOf("cancel", "stop")) {
                        cancelReceipts[controlKey] = nowMillis()
                        while (cancelReceipts.size > 256) {
                            cancelReceipts.remove(cancelReceipts.keys.first())
                        }
                        return "cancelled"
                    } else {
                        return "missing"
                    }
            }
        val registration = state.registration(downloadId) ?: return "missing"
        val isStop = action.lowercase() in setOf("cancel", "stop")
        if (!isStop && !expectedAttemptToken.isNullOrBlank() && registration.attemptToken != expectedAttemptToken) {
            return "missing"
        }
        return when (action.lowercase()) {
            "cancel", "stop" -> {
                finish(
                    state,
                    AttachmentDownloadsExportResult(
                        status = "cancelled",
                        message = "Save stopped",
                        failurePhase = state.phase.get()
                    ),
                    abortPending = true
                )
                "cancelled"
            }
            "pause", "resume" -> "unsupported"
            else -> "failed"
        }
    }

    private fun controlKey(
        downloadId: String,
        owner: AndroidAttachmentOwnerLease?,
        operationId: String = ""
    ): String {
        val ownerPrefix = owner?.let { "${it.scopeId}\u0000${it.generation}\u0000" }.orEmpty()
        val operationSuffix = operationId.takeIf(String::isNotBlank)?.let { "\u0000$it" }.orEmpty()
        return "$ownerPrefix$downloadId$operationSuffix"
    }

    internal fun awaitKeyReservationReleaseForTesting(
        request: AttachmentDownloadsExportRequest,
        timeoutMs: Long
    ): Boolean {
        val ownerPrefix = request.owner?.let { "${it.scopeId}\u0000${it.generation}\u0000" }.orEmpty()
        val key = "$ownerPrefix${request.source.absolutePath}\u0000${request.displayName}"
        val state = synchronized(lock) { activeByKey[key] } ?: return true
        return state.keyReservationReleased.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    internal fun keyReservationOwnerForTesting(request: AttachmentDownloadsExportRequest): Any? {
        val ownerPrefix = request.owner?.let { "${it.scopeId}\u0000${it.generation}\u0000" }.orEmpty()
        val key = "$ownerPrefix${request.source.absolutePath}\u0000${request.displayName}"
        return synchronized(lock) { activeByKey[key] }
    }

    internal fun releaseKeyReservationOwnerForTesting(owner: Any) {
        (owner as? ExportState)?.let(::releaseKeyReservation)
    }

    fun runWithSourceDirectoryCleanup(targetPrefix: String, cleanup: () -> Boolean): Boolean {
        require(targetPrefix.isNotBlank())
        val token = UUID.randomUUID().toString()
        val states =
            synchronized(lock) {
                sourceDirectoryCleanupOwners.getOrPut(targetPrefix) { mutableSetOf() } += token
                activeByKey.values
                    .filter { state -> state.request.source.absolutePath.startsWith(targetPrefix) }
                    .distinct()
            }
        return try {
            states.forEach { state ->
                finish(
                    state,
                    AttachmentDownloadsExportResult(
                        status = "cancelled",
                        message = "Attachment staging cleared",
                        failurePhase = state.phase.get()
                    ),
                    abortPending = true
                )
            }
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(wholeTimeoutMs)
            val released =
                states.all { state ->
                    val remainingNanos = deadline - System.nanoTime()
                    remainingNanos > 0L &&
                        state.keyReservationReleased.await(remainingNanos, TimeUnit.NANOSECONDS)
                }
            released && cleanup()
        } finally {
            synchronized(lock) {
                val owners = sourceDirectoryCleanupOwners[targetPrefix]
                owners?.remove(token)
                if (owners?.isEmpty() == true) sourceDirectoryCleanupOwners.remove(targetPrefix)
            }
        }
    }

    internal fun sourceDirectoryCleanupActiveForTesting(targetPrefix: String): Boolean =
        synchronized(lock) { sourceDirectoryCleanupOwners[targetPrefix].orEmpty().isNotEmpty() }

    private fun pruneCancelReceiptsLocked() {
        val cutoff = nowMillis() - cancelReceiptTtlMs
        val iterator = cancelReceipts.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value < cutoff) iterator.remove()
        }
    }

    private fun start(state: ExportState) {
        val wholeWatchdog =
            watchdog.schedule(
                {
                    finish(
                        state,
                        timeoutResult(state.phase.get(), "Save timed out"),
                        abortPending = true
                    )
                },
                wholeTimeoutMs,
                TimeUnit.MILLISECONDS
            )
        state.wholeWatchdog.set(wholeWatchdog)
        // Publish the cancellable owner before execution. ExecutorService.submit() can start the
        // task before its Future is stored, letting a concurrent stop miss cancellation entirely.
        val task = FutureTask<Unit> {
            if (state.tryBeginInsertOwner()) {
                try {
                    afterInsertOwnerAcquired()
                    runExport(state)
                } catch (throwable: Throwable) {
                    val ownsLateCleanup = state.resolveInsertAdmission()
                    if (ownsLateCleanup && state.pendingUri.get() == null) {
                        releaseKeyReservation(state)
                    }
                    throw throwable
                }
            }
        }
        state.workerFuture.set(task)
        if (state.isStopped()) {
            state.wholeWatchdog.getAndSet(null)?.cancel(false)
            state.closeInsertAdmissionIfIdle()
            task.cancel(true)
        } else {
            worker.execute(task)
        }
    }

    private fun runExport(state: ExportState) {
        val request = state.request
        var insertAdmissionResolved = false
        try {
            enterPhase(state, "export_insert", 0L)
            val uri =
                try {
                    state.backend.insert(request.displayName, request.mimeType)
                } catch (throwable: Throwable) {
                    val ownsLateCleanup = state.resolveInsertAdmission()
                    insertAdmissionResolved = true
                    if (ownsLateCleanup) releaseKeyReservation(state)
                    throw throwable
                }
            if (uri != null) state.pendingUri.set(uri)
            val ownsLateCleanup = state.resolveInsertAdmission()
            insertAdmissionResolved = true
            if (uri == null) {
                if (ownsLateCleanup) releaseKeyReservation(state)
                return finish(
                    state,
                    failureResult("export_insert", "Unable to create Downloads file"),
                    abortPending = false
                )
            }
            if (state.isStopped()) {
                if (ownsLateCleanup) startLateInsertCleanup(state)
                return
            }

            enterPhase(state, "export_open", 0L)
            val output = state.backend.openOutput(uri)
                ?: return finish(
                    state,
                    failureResult("export_open", "Unable to open Downloads file"),
                    abortPending = true
                )
            if (state.isStopped()) {
                runCatching { output.close() }
                return
            }
            output.use { stream -> copy(state, stream) }
            if (state.isStopped()) {
                return
            }

            enterPhase(state, "export_publish", request.source.length())
            val published: Boolean? =
                if (request.owner == null) {
                    state.backend.publish(uri)
                } else {
                    ownerRegistry.runIfCurrent(request.owner) { state.backend.publish(uri) }
                }
            if (published == null) {
                return finish(
                    state,
                    AttachmentDownloadsExportResult(
                        status = "cancelled",
                        message = "Save stopped after account changed",
                        failurePhase = "export_publish"
                    ),
                    abortPending = true
                )
            }
            if (!published) {
                return finish(
                    state,
                    failureResult("export_publish", "Unable to publish attachment in Downloads"),
                    abortPending = true
                )
            }
            finish(
                state,
                AttachmentDownloadsExportResult(
                    status = "saved",
                    message = "Saved to Downloads/Raft",
                    uri = uri,
                    fileName = request.displayName,
                    sizeBytes = request.source.length()
                ),
                abortPending = false
            )
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            if (!state.isStopped()) {
                finish(state, failureResult(state.phase.get(), "Save interrupted"), abortPending = true)
            }
        } catch (throwable: Throwable) {
            if (!state.isStopped()) {
                finish(state, failureResult(state.phase.get(), "Unable to save attachment to Downloads"), true)
            }
        } finally {
            if (!insertAdmissionResolved) {
                val ownsLateCleanup = state.resolveInsertAdmission()
                if (ownsLateCleanup && state.pendingUri.get() == null) {
                    releaseKeyReservation(state)
                }
            }
        }
    }

    private fun copy(state: ExportState, output: OutputStream) {
        val total = state.request.source.length().coerceAtLeast(0L)
        val buffer = ByteArray(copyBufferBytes.coerceAtLeast(8 * 1024))
        var copied = 0L
        var lastProgressAt = nowNanos()
        enterPhase(state, "export_copy", copied)
        FileInputStream(state.request.source).use { input ->
            while (true) {
                if (Thread.currentThread().isInterrupted || state.isStopped()) {
                    throw InterruptedException("export stopped")
                }
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                if (state.isStopped()) throw InterruptedException("export stopped")
                copied += count
                val now = nowNanos()
                if (copied == total || now - lastProgressAt >= TimeUnit.MILLISECONDS.toNanos(progressCadenceMs)) {
                    emit(state, "export_copy", copied)
                    armStageDeadline(state, "export_copy")
                    lastProgressAt = now
                }
            }
            output.flush()
        }
        if (copied != total) throw IllegalStateException("Export byte count mismatch")
    }

    private fun enterPhase(state: ExportState, phase: String, downloadedBytes: Long) {
        if (state.isStopped()) throw InterruptedException("export stopped")
        state.phase.set(phase)
        emit(state, phase, downloadedBytes)
        armStageDeadline(state, phase)
    }

    private fun armStageDeadline(state: ExportState, phase: String) {
        val epoch = state.phaseEpoch.incrementAndGet()
        watchdog.schedule(
            {
                if (state.phaseEpoch.get() == epoch && !state.isStopped()) {
                    finish(state, timeoutResult(phase, "Save timed out during $phase"), abortPending = true)
                }
            },
            stageTimeoutMs,
            TimeUnit.MILLISECONDS
        )
    }

    private fun emit(state: ExportState, phase: String, downloadedBytes: Long) {
        synchronized(state.deliveryLock) {
            if (state.isStopped()) return
            val total = state.request.source.length().coerceAtLeast(0L)
            val transfer =
                when (phase) {
                    "export_copy" -> state.meter.bytes(downloadedBytes.coerceIn(0L, total), total)
                    // Bytes copied, the row is not published yet: still 99%.
                    "export_publish" -> state.meter.finishing()
                    else -> state.meter.total(total)
                }
            val snapshot = ProgressSnapshot(phase = phase, transfer = transfer)
            state.snapshot.set(snapshot)
            state.observers.forEach { it.admit(snapshot) }
        }
    }

    private fun finish(
        state: ExportState,
        result: AttachmentDownloadsExportResult,
        abortPending: Boolean
    ) {
        synchronized(state.deliveryLock) {
            if (!state.finished.compareAndSet(false, true)) return
        }
        state.ownerRegistration.getAndSet(null)?.close()
        state.wholeWatchdog.getAndSet(null)?.cancel(false)
        state.workerFuture.getAndSet(null)?.cancel(true)
        state.closeInsertAdmissionIfIdle()
        if (abortPending) state.observers.forEach { it.close() }
        terminalWorker.execute {
            // Give an in-flight insert a bounded chance to publish its MediaStore URI before the
            // terminal reconciles it. If it remains unresolved, report export_cleanup and arm an
            // identity-bound late-result owner instead of hanging the UI or claiming cancellation.
            val insertResolvedInTime =
                runCatching {
                    state.insertAdmission.get(insertAdmissionWaitMs, TimeUnit.MILLISECONDS)
                    true
                }.getOrDefault(false)
            val unresolvedInsert =
                !insertResolvedInTime && state.armLateInsertCleanupIfUnresolved()
            val terminalResult =
                if (unresolvedInsert) {
                    cleanupFailureResult()
                } else if (abortPending) {
                    reconcilePendingRow(state, result)
                } else {
                    state.pendingUri.set(null)
                    result
                }
            if (!abortPending) {
                val drainDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500L)
                state.observers.forEach { registration ->
                    val remainingNanos = (drainDeadline - System.nanoTime()).coerceAtLeast(0L)
                    registration.awaitIdle(TimeUnit.NANOSECONDS.toMillis(remainingNanos))
                }
                state.observers.forEach { it.close() }
            }
            state.terminal.complete(terminalResult)
            synchronized(lock) {
                state.observers.forEach { registration ->
                    val controlKey = controlKey(
                        registration.downloadId,
                        state.request.owner,
                        state.request.operationId
                    )
                    if (activeByDownloadId[controlKey] === state) {
                        activeByDownloadId.remove(controlKey)
                    }
                }
            }
            if (!unresolvedInsert && state.pendingUri.get() == null) {
                releaseKeyReservation(state)
            }
        }
    }

    private fun startLateInsertCleanup(state: ExportState) {
        cleanupWorker.execute {
            reconcilePendingRow(state, cleanupFailureResult())
            if (state.pendingUri.get() == null) releaseKeyReservation(state)
        }
    }

    private fun releaseKeyReservation(state: ExportState) {
        synchronized(lock) {
            if (activeByKey[state.key] === state) activeByKey.remove(state.key)
        }
        state.keyReservationReleased.countDown()
    }

    private fun reconcilePendingRow(
        state: ExportState,
        desiredResult: AttachmentDownloadsExportResult
    ): AttachmentDownloadsExportResult {
        val uri = state.pendingUri.get() ?: return desiredResult
        repeat(cleanupAttempts.coerceAtLeast(1)) {
            val future =
                cleanupWorker.submit<AttachmentDownloadsRowState> {
                    when (val before = state.backend.rowState(uri)) {
                        AttachmentDownloadsRowState.Missing,
                        AttachmentDownloadsRowState.Published -> before
                        AttachmentDownloadsRowState.Pending,
                        AttachmentDownloadsRowState.Unknown -> {
                            state.backend.deletePending(uri)
                            state.backend.rowState(uri)
                        }
                    }
                }
            val resolved =
                runCatching {
                    future.get(cleanupAttemptTimeoutMs, TimeUnit.MILLISECONDS)
                }.getOrElse {
                    future.cancel(true)
                    AttachmentDownloadsRowState.Unknown
                }
            when (resolved) {
                AttachmentDownloadsRowState.Missing -> {
                    state.pendingUri.compareAndSet(uri, null)
                    return desiredResult
                }
                AttachmentDownloadsRowState.Published -> {
                    state.pendingUri.compareAndSet(uri, null)
                    return savedResult(state, uri)
                }
                AttachmentDownloadsRowState.Pending,
                AttachmentDownloadsRowState.Unknown -> Unit
            }
        }
        // Do not report an ordinary cancel/timeout when the pending-row cleanup itself could not
        // be confirmed. The explicit phase keeps the failure observable and prevents a false PASS.
        return cleanupFailureResult().also { scheduleDeferredCleanup(state, uri) }
    }

    private fun cleanupFailureResult() =
        AttachmentDownloadsExportResult(
            status = "storage_failed",
            message = "Unable to clean up the pending Downloads file",
            failurePhase = "export_cleanup"
        )

    private fun scheduleDeferredCleanup(state: ExportState, uri: String) {
        watchdog.schedule(
            {
                cleanupWorker.execute {
                    if (state.pendingUri.get() != uri) return@execute
                    val resolved =
                        runCatching {
                            when (state.backend.rowState(uri)) {
                                AttachmentDownloadsRowState.Missing,
                                AttachmentDownloadsRowState.Published -> true
                                AttachmentDownloadsRowState.Pending,
                                AttachmentDownloadsRowState.Unknown -> {
                                    state.backend.deletePending(uri)
                                    when (state.backend.rowState(uri)) {
                                        AttachmentDownloadsRowState.Missing,
                                        AttachmentDownloadsRowState.Published -> true
                                        AttachmentDownloadsRowState.Pending,
                                        AttachmentDownloadsRowState.Unknown -> false
                                    }
                                }
                            }
                        }.getOrDefault(false)
                    if (resolved) {
                        state.pendingUri.compareAndSet(uri, null)
                        releaseKeyReservation(state)
                    } else {
                        scheduleDeferredCleanup(state, uri)
                    }
                }
            },
            deferredCleanupDelayMs,
            TimeUnit.MILLISECONDS
        )
    }

    private fun savedResult(state: ExportState, uri: String) =
        AttachmentDownloadsExportResult(
            status = "saved",
            message = "Saved to Downloads/Raft",
            uri = uri,
            fileName = state.request.displayName,
            sizeBytes = state.request.source.length()
        )

    private fun failureResult(phase: String, message: String) =
        AttachmentDownloadsExportResult(
            status = "storage_failed",
            message = message,
            failurePhase = phase
        )

    private fun timeoutResult(phase: String, message: String) =
        AttachmentDownloadsExportResult(
            status = "storage_failed",
            message = message,
            failurePhase = phase
        )

    private fun cancelledOperationResult() =
        AttachmentDownloadsExportResult(
            status = "cancelled",
            message = "Save stopped"
        )

    private inner class ExportState(
        val key: String,
        val request: AttachmentDownloadsExportRequest,
        val backend: AttachmentDownloadsExportBackend
    ) {
        val observers = CopyOnWriteArrayList<ObserverRegistration>()
        val deliveryLock = Any()
        val terminal = CompletableFuture<AttachmentDownloadsExportResult>()
        val finished = AtomicBoolean(false)
        private val ownerInvalidated = AtomicBoolean(false)
        val ownerRegistration = AtomicReference<AndroidAttachmentOwnerRegistration?>()
        val pendingUri = AtomicReference<String?>()
        val phase = AtomicReference("export_insert")
        val phaseEpoch = AtomicLong(0L)
        val snapshot = AtomicReference<ProgressSnapshot?>()
        /** The export's meter (under [deliveryLock]): the copy's percent and its 99 cap until published. */
        val meter = RaftTransferMeter()
        val workerFuture = AtomicReference<Future<*>?>()
        val wholeWatchdog = AtomicReference<Future<*>?>()
        val insertAdmission = CompletableFuture<Unit>()
        val keyReservationReleased = java.util.concurrent.CountDownLatch(1)
        private val insertAdmissionLock = Any()
        private var insertOwnerStarted = false
        private var insertResolved = false
        private var lateInsertCleanupArmed = false
        private var lateInsertCleanupClaimed = false
        private val registrations = ConcurrentHashMap<String, ObserverRegistration>()

        fun isStopped(): Boolean = finished.get() || ownerInvalidated.get()

        fun invalidateOwnerLocked(): (() -> Unit)? {
            if (!ownerInvalidated.compareAndSet(false, true)) return null
            observers.forEach { it.close() }
            return {
                finish(
                    this,
                    AttachmentDownloadsExportResult(
                        status = "cancelled",
                        message = "Save stopped after account changed",
                        failurePhase = phase.get()
                    ),
                    abortPending = true
                )
            }
        }

        fun installOwnerRegistration(registration: AndroidAttachmentOwnerRegistration) {
            if (!ownerRegistration.compareAndSet(null, registration)) {
                registration.close()
                return
            }
            if (isStopped() && ownerRegistration.compareAndSet(registration, null)) {
                registration.close()
            }
        }

        fun tryBeginInsertOwner(): Boolean =
            synchronized(insertAdmissionLock) {
                if (isStopped()) {
                    insertAdmission.complete(Unit)
                    false
                } else {
                    insertOwnerStarted = true
                    true
                }
            }

        fun closeInsertAdmissionIfIdle() {
            synchronized(insertAdmissionLock) {
                if (!insertOwnerStarted) {
                    insertResolved = true
                    insertAdmission.complete(Unit)
                }
            }
        }

        fun resolveInsertAdmission(): Boolean =
            synchronized(insertAdmissionLock) {
                insertResolved = true
                insertAdmission.complete(Unit)
                if (lateInsertCleanupArmed && !lateInsertCleanupClaimed) {
                    lateInsertCleanupClaimed = true
                    true
                } else {
                    false
                }
            }

        fun armLateInsertCleanupIfUnresolved(): Boolean =
            synchronized(insertAdmissionLock) {
                if (insertResolved) {
                    false
                } else {
                    lateInsertCleanupArmed = true
                    true
                }
            }

        fun addObserver(
            downloadId: String,
            observer: ((AttachmentDownloadsExportProgress) -> Unit)?
        ): ObserverRegistration =
            registrations.computeIfAbsent(downloadId) {
                ObserverRegistration(downloadId, observer, observerWorker).also { registration ->
                    observers.add(registration)
                }
            }

        fun registration(downloadId: String): ObserverRegistration? = registrations[downloadId]
    }

    private data class ProgressSnapshot(
        val phase: String,
        val transfer: RaftTransferProgress
    )

    private class ObserverRegistration(
        val downloadId: String,
        private val observer: ((AttachmentDownloadsExportProgress) -> Unit)?,
        private val executor: ExecutorService
    ) {
        val attemptToken: String = "export-${UUID.randomUUID()}"
        private val sequence = AtomicLong(0L)
        private val lock = Any()
        private val queue = ArrayDeque<ProgressSnapshot>()
        private var draining = false
        private var closed = false

        fun admit(snapshot: ProgressSnapshot) {
            synchronized(lock) {
                if (closed || observer == null) return
                queue.addLast(snapshot)
                if (draining) return
                draining = true
            }
            executor.execute(::drain)
        }

        fun close() {
            synchronized(lock) {
                closed = true
                queue.clear()
            }
        }

        fun awaitIdle(timeoutMs: Long) {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (System.nanoTime() < deadline) {
                val idle = synchronized(lock) { !draining && queue.isEmpty() }
                if (idle) return
                Thread.sleep(5L)
            }
        }

        private fun drain() {
            while (true) {
                val snapshot =
                    synchronized(lock) {
                        if (closed || queue.isEmpty()) {
                            draining = false
                            return
                        }
                        queue.removeFirst()
                    }
                deliver(snapshot)
            }
        }

        private fun deliver(snapshot: ProgressSnapshot) {
            runCatching {
                observer?.invoke(
                    AttachmentDownloadsExportProgress(
                        downloadId = downloadId,
                        attemptToken = attemptToken,
                        sequence = sequence.incrementAndGet(),
                        phase = snapshot.phase,
                        transfer = snapshot.transfer
                    )
                )
            }
        }
    }

    companion object {
        private fun exportThreadFactory(role: String): ThreadFactory =
            ThreadFactory { runnable ->
                Thread(runnable, "attachment-export-$role").apply { isDaemon = true }
            }
    }
}
