package build.raft.app.shared

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal object SlockAndroidAvatarCacheTimeouts : SlockAvatarCacheTimeoutScheduler {
    private val executor =
        ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "slock-avatar-cache-timeouts").apply { isDaemon = true }
        }.apply {
            removeOnCancelPolicy = true
            setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
        }

    override fun schedule(delayMillis: Long, block: () -> Unit): SlockAvatarCacheCancellation {
        val future = executor.schedule(block, delayMillis.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        return SlockAvatarCacheCancellation { future.cancel(false) }
    }
}

/** Coordinates typed descriptor rendering, native await/finalize, and one network flight. */
internal class SlockAndroidAvatarCacheManager(
    private val store: SlockAndroidAvatarDiskStore,
    private val networkFetcher: SlockAndroidAvatarNetworkFetcher,
    private val contentValidator: (ByteArray, String?) -> Boolean,
    private val workExecutor: SlockAvatarCacheWorkExecutor,
    private val timeoutScheduler: SlockAvatarCacheTimeoutScheduler = SlockAndroidAvatarCacheTimeouts,
    private val resultTimeoutMillis: Long = SlockAndroidAvatarCacheContract.ResultTimeoutMillis,
    private val rollbackGraceMillis: Long = SlockAndroidAvatarCacheContract.RollbackGraceMillis
) {
    private class Operation(
        val request: SlockAndroidAvatarRequest,
        val processEpoch: Long,
        val order: Long
    ) {
        var networkStarted = false
        var result: SlockAndroidAvatarLoadResult? = null
        var stage: SlockAndroidAvatarStage? = null
        var rollbackRecord: SlockAndroidAvatarRollbackRecord? = null
        var rollbackEligible = false
        var resultTimeout: SlockAvatarCacheCancellation? = null
        var rollbackCleanup: SlockAvatarCacheCancellation? = null
        var closed = false
        var finalized = false
        var nextWaiterId = 0L
        val resultWaiters = linkedMapOf<Long, (SlockAndroidAvatarLoadResult) -> Unit>()
        val renderWaiters = linkedMapOf<Long, (SlockAndroidAvatarBytePayload?) -> Unit>()
    }

    private data class PreparedOperation(
        val processEpoch: Long,
        val order: Long
    )

    private val lock = Any()
    private val order = AtomicLong()
    private val operations = linkedMapOf<SlockAndroidAvatarOperationKey, Operation>()
    private val preparedOperations = linkedMapOf<SlockAndroidAvatarOperationKey, PreparedOperation>()
    private val latestGenerationBySlot = mutableMapOf<SlockAndroidAvatarSlotKey, Long>()
    private val retiredOwnerFloor = mutableMapOf<String, Long>()
    private val retiredSubjectFloor = mutableMapOf<SlockAndroidAvatarSlotKey, Long>()
    private var clearGenerationFloor = 0L
    private var maxSeenGeneration = 0L
    private var processEpoch = 0L

    init {
        require(resultTimeoutMillis > 0L)
        require(rollbackGraceMillis > 0L)
    }

    fun managedBytes(): Long = store.managedBytes()

    fun inspect(
        key: SlockAndroidAvatarEntryKey,
        commitGeneration: Long
    ): SlockAndroidAvatarInspection? {
        val operationKey = SlockAndroidAvatarOperationKey(key, commitGeneration)
        val slotKey = SlockAndroidAvatarSlotKey(key.partitionKey, key.subjectKey)
        synchronized(lock) {
            val floor =
                maxOf(
                    clearGenerationFloor,
                    retiredOwnerFloor[key.partitionKey] ?: 0L,
                    retiredSubjectFloor[slotKey] ?: 0L
                )
            val latest = latestGenerationBySlot[slotKey] ?: 0L
            if (commitGeneration <= floor || commitGeneration < latest) return null
            if (commitGeneration > latest) {
                latestGenerationBySlot[slotKey] = commitGeneration
                preparedOperations.keys.removeAll { preparedKey ->
                    preparedKey.entryKey.partitionKey == key.partitionKey &&
                        preparedKey.entryKey.subjectKey == key.subjectKey &&
                        preparedKey.generation < commitGeneration
                }
            }
            maxSeenGeneration = maxOf(maxSeenGeneration, commitGeneration)
            if (operations[operationKey] == null) {
                preparedOperations.getOrPut(operationKey) {
                    PreparedOperation(processEpoch = processEpoch, order = order.incrementAndGet())
                }
            }
            prunePreparedOperationsLocked()
        }
        val payload = store.read(key)
        val decodable = payload?.let { contentValidator(it.bytes, it.contentType) } == true
        if (payload != null && !decodable) store.invalidate(key)
        return SlockAndroidAvatarInspection(
            bytesAvailable = payload != null && decodable,
            bytesDecodable = decodable,
            managedBytes = store.managedBytes()
        )
    }

    fun awaitResult(
        request: SlockAndroidAvatarRequest,
        callback: (SlockAndroidAvatarLoadResult) -> Unit
    ) {
        val immediate: SlockAndroidAvatarLoadResult?
        val operation: Operation?
        synchronized(lock) {
            operation = admittedOperationLocked(request)
            if (operation == null) {
                immediate = SlockAndroidAvatarLoadResult.TemporaryTransport
            } else {
                immediate = operation.result
                if (immediate == null) {
                    operation.resultWaiters[operation.nextWaiterId++] = callback
                    ensureTimeoutLocked(operation)
                }
            }
        }
        if (immediate != null) {
            callback(immediate)
            return
        }
        operation?.let(::startForAwait)
    }

    fun load(
        request: SlockAndroidAvatarRequest,
        callback: (SlockAndroidAvatarBytePayload?) -> Unit
    ) {
        val operation: Operation?
        var terminalFetch: Boolean? = null
        synchronized(lock) {
            operation = admittedOperationLocked(request)
            if (operation != null && request.readMode == SlockAndroidAvatarReadMode.Fetch) {
                terminalFetch =
                    when {
                        operation.finalized && operation.result?.status == "success" -> true
                        operation.result != null && operation.result?.status != "success" -> false
                        else -> null
                    }
                if (terminalFetch == null) {
                    operation.renderWaiters[operation.nextWaiterId++] = callback
                    ensureTimeoutLocked(operation)
                }
            }
        }
        if (operation == null) {
            runCatching { callback(null) }
            return
        }
        if (request.readMode == SlockAndroidAvatarReadMode.Fetch && terminalFetch != null) {
            if (terminalFetch == true) {
                workExecutor.execute { runCatching { callback(readValidated(request.entryKey)) } }
            } else {
                runCatching { callback(null) }
            }
            return
        }
        when (request.readMode) {
            SlockAndroidAvatarReadMode.Suppressed -> {
                runCatching { callback(null) }
                publish(operation, SlockAndroidAvatarLoadResult.Suppressed)
            }
            SlockAndroidAvatarReadMode.RenderFresh,
            SlockAndroidAvatarReadMode.RenderStaleSuppressed,
            SlockAndroidAvatarReadMode.RenderStaleRevalidate ->
                workExecutor.execute {
                    val payload = readValidated(request.entryKey)
                    runCatching { callback(payload) }
                    when (request.readMode) {
                        SlockAndroidAvatarReadMode.RenderFresh,
                        SlockAndroidAvatarReadMode.RenderStaleSuppressed ->
                            publish(
                                operation,
                                if (payload != null) {
                                    SlockAndroidAvatarLoadResult.Rendered
                                } else {
                                    SlockAndroidAvatarLoadResult.TemporaryTransport
                                }
                            )
                        SlockAndroidAvatarReadMode.RenderStaleRevalidate -> startNetwork(operation)
                        else -> Unit
                    }
                }
            SlockAndroidAvatarReadMode.Fetch -> startNetwork(operation)
        }
    }

    /** Adapter memory hit still participates in the same bridge completion. */
    fun recordMemoryRendered(request: SlockAndroidAvatarRequest) {
        val operation = synchronized(lock) { admittedOperationLocked(request) } ?: return
        when (request.readMode) {
            SlockAndroidAvatarReadMode.RenderFresh,
            SlockAndroidAvatarReadMode.RenderStaleSuppressed ->
                publish(operation, SlockAndroidAvatarLoadResult.Rendered)
            SlockAndroidAvatarReadMode.RenderStaleRevalidate -> startNetwork(operation)
            SlockAndroidAvatarReadMode.Suppressed -> publish(operation, SlockAndroidAvatarLoadResult.Suppressed)
            SlockAndroidAvatarReadMode.Fetch -> startNetwork(operation)
        }
    }

    fun finalize(request: SlockAndroidAvatarRequest, outcome: String): SlockAndroidAvatarMutation {
        val renderCallbacks = mutableListOf<(SlockAndroidAvatarBytePayload?) -> Unit>()
        var renderPayload: SlockAndroidAvatarBytePayload? = null
        val mutation =
            synchronized(lock) {
                val operation = operations[request.operationKey]
                    ?.takeIf { it.request == request && !it.closed }
                    ?: return@synchronized SlockAndroidAvatarMutation.Failed("avatar_cache_operation_missing")
                if (operation.finalized) return@synchronized SlockAndroidAvatarMutation.Accepted
                val result = operation.result
                    ?: return@synchronized SlockAndroidAvatarMutation.Failed("avatar_cache_result_pending")
                if (!outcomeMatches(result.status, outcome)) {
                    return@synchronized SlockAndroidAvatarMutation.Failed("avatar_cache_finalize_outcome_mismatch")
                }
                if (!isCurrentLocked(operation)) {
                    discardOperationFilesLocked(operation)
                    return@synchronized SlockAndroidAvatarMutation.Failed("avatar_cache_generation_stale")
                }
                when (outcome) {
                    "successful_bytes" -> {
                        val stage = operation.stage
                            ?: return@synchronized SlockAndroidAvatarMutation.Failed("avatar_cache_stage_missing")
                        val rollback = store.promote(stage, result.contentType)
                            ?: return@synchronized SlockAndroidAvatarMutation.Failed("avatar_cache_atomic_finalize_failed")
                        operation.stage = null
                        operation.rollbackRecord = rollback
                        operation.rollbackEligible = true
                        val admittedPayload = readValidated(request.entryKey)
                        if (admittedPayload == null) {
                            if (store.rollback(rollback)) {
                                operation.rollbackRecord = null
                                operation.rollbackEligible = false
                            }
                            return@synchronized SlockAndroidAvatarMutation.Failed(
                                "avatar_cache_finalized_bytes_unreadable"
                            )
                        }
                        operation.finalized = true
                        renderPayload = admittedPayload
                        renderCallbacks += operation.renderWaiters.values
                        operation.renderWaiters.clear()
                        scheduleRollbackCleanupLocked(operation, rollback)
                    }
                    "not_modified" -> {
                        if (readValidated(request.entryKey) == null) {
                            return@synchronized SlockAndroidAvatarMutation.Failed("avatar_cache_not_modified_bytes_missing")
                        }
                        operation.finalized = true
                    }
                    "resource_absent", "invalid_content", "temporary_transport" -> {
                        if (!discardOperationFilesLocked(operation)) {
                            return@synchronized SlockAndroidAvatarMutation.Failed(
                                "avatar_cache_transient_cleanup_failed"
                            )
                        }
                        operation.finalized = true
                    }
                    else -> return@synchronized SlockAndroidAvatarMutation.Failed("avatar_cache_finalize_outcome_unknown")
                }
                pruneOperationsLocked()
                SlockAndroidAvatarMutation.Accepted
            }
        renderCallbacks.forEach { callback -> runCatching { callback(renderPayload) } }
        return mutation
    }

    fun rollback(request: SlockAndroidAvatarRequest): SlockAndroidAvatarMutation {
        val renderCallbacks = mutableListOf<(SlockAndroidAvatarBytePayload?) -> Unit>()
        val accepted =
            synchronized(lock) {
                preparedOperations.remove(request.operationKey)
                val operation = operations[request.operationKey]
                    ?.takeIf { it.request == request }
                    ?: return@synchronized true
                operation.rollbackCleanup?.cancel()
                operation.rollbackCleanup = null
                val diskRestored =
                    when {
                        operation.rollbackRecord == null -> true
                        !operation.rollbackEligible -> false
                        else -> store.rollback(requireNotNull(operation.rollbackRecord))
                    }
                if (diskRestored) {
                    operation.rollbackRecord = null
                    operation.rollbackEligible = false
                }
                val stageDeleted = store.deleteStage(operation.stage)
                if (stageDeleted) operation.stage = null
                operation.closed = true
                renderCallbacks += operation.renderWaiters.values
                operation.renderWaiters.clear()
                operation.resultTimeout?.cancel()
                operation.resultTimeout = null
                val cleaned = diskRestored && stageDeleted
                if (cleaned) operations.remove(request.operationKey)
                cleaned
            }
        renderCallbacks.forEach { callback -> runCatching { callback(null) } }
        return if (accepted) {
            SlockAndroidAvatarMutation.Accepted
        } else {
            SlockAndroidAvatarMutation.Failed("avatar_cache_rollback_failed")
        }
    }

    fun retireOwner(partitionKey: String): SlockAndroidAvatarMutation {
        lateinit var deliveries: ClosedDeliveries
        val removed = synchronized(lock) {
            retiredOwnerFloor[partitionKey] = maxOf(retiredOwnerFloor[partitionKey] ?: 0L, maxSeenGeneration)
            latestGenerationBySlot.keys.removeAll { it.partitionKey == partitionKey }
            retiredSubjectFloor.keys.removeAll { it.partitionKey == partitionKey }
            preparedOperations.keys.removeAll { it.entryKey.partitionKey == partitionKey }
            deliveries = closeOperationsLocked { it.request.nativePartitionKey == partitionKey }
            val indexedRemoved = store.retireOwner(partitionKey)
            deliveries.filesCleaned && indexedRemoved
        }
        deliveries.deliver()
        return if (removed) SlockAndroidAvatarMutation.Accepted
        else SlockAndroidAvatarMutation.Failed("avatar_cache_owner_retire_failed")
    }

    fun retireSubject(partitionKey: String, subjectKey: String): SlockAndroidAvatarMutation {
        val slot = SlockAndroidAvatarSlotKey(partitionKey, subjectKey)
        lateinit var deliveries: ClosedDeliveries
        val removed = synchronized(lock) {
            retiredSubjectFloor[slot] = maxOf(retiredSubjectFloor[slot] ?: 0L, maxSeenGeneration)
            latestGenerationBySlot.remove(slot)
            preparedOperations.keys.removeAll {
                it.entryKey.partitionKey == partitionKey && it.entryKey.subjectKey == subjectKey
            }
            deliveries = closeOperationsLocked { it.request.slotKey == slot }
            val indexedRemoved = store.retireSubject(partitionKey, subjectKey)
            deliveries.filesCleaned && indexedRemoved
        }
        deliveries.deliver()
        return if (removed) SlockAndroidAvatarMutation.Accepted
        else SlockAndroidAvatarMutation.Failed("avatar_cache_subject_retire_failed")
    }

    fun clearAll(): Boolean {
        lateinit var deliveries: ClosedDeliveries
        val cleared = synchronized(lock) {
            processEpoch++
            clearGenerationFloor = maxOf(clearGenerationFloor, maxSeenGeneration)
            latestGenerationBySlot.clear()
            retiredOwnerFloor.clear()
            retiredSubjectFloor.clear()
            preparedOperations.clear()
            deliveries = closeOperationsLocked { true }
            val indexedCleared = store.clearAll()
            deliveries.filesCleaned && indexedCleared
        }
        deliveries.deliver()
        return cleared
    }

    internal fun activeNetworkCount(): Int = synchronized(lock) {
        operations.values.count { it.networkStarted && it.result == null && !it.closed }
    }

    internal fun retainedOperationCount(): Int = synchronized(lock) { operations.size }

    internal fun retainedPreparationCount(): Int = synchronized(lock) { preparedOperations.size }

    private fun startForAwait(operation: Operation) {
        when (operation.request.readMode) {
            SlockAndroidAvatarReadMode.Suppressed ->
                publish(operation, SlockAndroidAvatarLoadResult.Suppressed)
            SlockAndroidAvatarReadMode.RenderFresh,
            SlockAndroidAvatarReadMode.RenderStaleSuppressed ->
                workExecutor.execute {
                    val payload = readValidated(operation.request.entryKey)
                    publish(
                        operation,
                        if (payload != null) {
                            SlockAndroidAvatarLoadResult.Rendered
                        } else {
                            SlockAndroidAvatarLoadResult.TemporaryTransport
                        }
                    )
                }
            SlockAndroidAvatarReadMode.RenderStaleRevalidate,
            SlockAndroidAvatarReadMode.Fetch -> startNetwork(operation)
        }
    }

    private fun startNetwork(operation: Operation) {
        val shouldStart =
            synchronized(lock) {
                if (
                    operation.closed || operation.result != null || operation.networkStarted ||
                    operations[operation.request.operationKey] !== operation || !isCurrentLocked(operation)
                ) {
                    false
                } else {
                    operation.networkStarted = true
                    ensureTimeoutLocked(operation)
                    true
                }
            }
        if (!shouldStart) return
        workExecutor.execute {
            val hasAdmittedBytes = store.entry(operation.request.entryKey) != null
            val response =
                runCatching { networkFetcher.fetch(operation.request, hasAdmittedBytes) }
                    .getOrElse { SlockAndroidAvatarNetworkResponse(statusCode = 0) }
            completeNetwork(operation, response)
        }
    }

    private fun completeNetwork(
        operation: Operation,
        response: SlockAndroidAvatarNetworkResponse
    ) {
        val result = classifyNetworkResponse(operation.request, response)
        if (result.status == "success") {
            val staged =
                synchronized(lock) {
                    if (!canAcceptNetworkLocked(operation)) return@synchronized null
                    store.stage(
                        key = operation.request.entryKey,
                        generation = operation.request.commitGeneration,
                        bytes = response.bytes
                    )?.also { operation.stage = it }
                }
            if (staged == null) {
                publish(operation, SlockAndroidAvatarLoadResult.TemporaryTransport)
                return
            }
        }
        publish(operation, result)
    }

    private fun classifyNetworkResponse(
        request: SlockAndroidAvatarRequest,
        response: SlockAndroidAvatarNetworkResponse
    ): SlockAndroidAvatarLoadResult {
        return when (response.statusCode) {
            304 ->
                if (store.entry(request.entryKey) != null) {
                    SlockAndroidAvatarLoadResult.notModified(response.sanitizedValidators())
                } else {
                    SlockAndroidAvatarLoadResult.TemporaryTransport
                }
            404, 410 -> SlockAndroidAvatarLoadResult.ResourceAbsent
            in 200..299 -> {
                val contentType = response.contentType.safeHeaderValue(maxLength = 256)
                if (
                    response.exceededMaxBytes ||
                    response.bytes.isEmpty() ||
                    response.bytes.size > SlockAndroidAvatarCacheContract.MaxEntryBytes ||
                    (contentType != null && !contentType.startsWith("image/", ignoreCase = true)) ||
                    !contentValidator(response.bytes, contentType)
                ) {
                    SlockAndroidAvatarLoadResult.InvalidContent
                } else {
                    SlockAndroidAvatarLoadResult.success(
                        response.sanitizedValidators().copy(contentType = contentType)
                    )
                }
            }
            else -> SlockAndroidAvatarLoadResult.TemporaryTransport
        }
    }

    private fun SlockAndroidAvatarNetworkResponse.sanitizedValidators(): SlockAndroidAvatarNetworkResponse =
        copy(
            eTag = eTag.safeHeaderValue(maxLength = 2048),
            lastModified = lastModified.safeHeaderValue(maxLength = 2048)
        )

    private fun String?.safeHeaderValue(maxLength: Int): String? =
        this?.trim()?.takeIf {
            it.isNotEmpty() && it.length <= maxLength && it.none(Char::isISOControl)
        }

    private fun publish(operation: Operation, result: SlockAndroidAvatarLoadResult) {
        val resultCallbacks = mutableListOf<(SlockAndroidAvatarLoadResult) -> Unit>()
        val renderCallbacks = mutableListOf<(SlockAndroidAvatarBytePayload?) -> Unit>()
        var deliveredResult = result
        synchronized(lock) {
            if (operation.closed || operation.result != null || operations[operation.request.operationKey] !== operation) {
                val cleaned = discardOperationFilesLocked(operation)
                if (cleaned && operation.closed && operations[operation.request.operationKey] === operation) {
                    operations.remove(operation.request.operationKey)
                }
                return
            }
            if (!isCurrentLocked(operation)) {
                deliveredResult = SlockAndroidAvatarLoadResult.TemporaryTransport
                operation.closed = true
                if (discardOperationFilesLocked(operation)) {
                    operations.remove(operation.request.operationKey)
                }
            }
            operation.result = deliveredResult
            operation.resultTimeout?.cancel()
            operation.resultTimeout = null
            resultCallbacks += operation.resultWaiters.values
            operation.resultWaiters.clear()
            if (deliveredResult.status != "success") {
                renderCallbacks += operation.renderWaiters.values
                operation.renderWaiters.clear()
            }
            pruneOperationsLocked()
        }
        resultCallbacks.forEach { callback -> runCatching { callback(deliveredResult) } }
        renderCallbacks.forEach { callback -> runCatching { callback(null) } }
    }

    private fun admittedOperationLocked(request: SlockAndroidAvatarRequest): Operation? {
        operations[request.operationKey]?.let { existing ->
            return existing.takeIf { it.request == request && !it.closed }
        }
        val prepared = preparedOperations.remove(request.operationKey) ?: return null
        if (prepared.processEpoch != processEpoch) return null
        val floor =
            maxOf(
                clearGenerationFloor,
                retiredOwnerFloor[request.nativePartitionKey] ?: 0L,
                retiredSubjectFloor[request.slotKey] ?: 0L
            )
        val latest = latestGenerationBySlot[request.slotKey] ?: 0L
        if (request.commitGeneration <= floor || request.commitGeneration < latest) return null
        if (request.commitGeneration > latest) {
            latestGenerationBySlot[request.slotKey] = request.commitGeneration
        }
        maxSeenGeneration = maxOf(maxSeenGeneration, request.commitGeneration)
        pruneOperationsLocked(SlockAndroidAvatarCacheContract.MaxRetainedOperations - 1)
        if (operations.size >= SlockAndroidAvatarCacheContract.MaxRetainedOperations) return null
        return Operation(request, processEpoch, order.incrementAndGet()).also {
            operations[request.operationKey] = it
            pruneOperationsLocked()
        }
    }

    private fun isCurrentLocked(operation: Operation): Boolean =
        !operation.closed &&
            operation.processEpoch == processEpoch &&
            latestGenerationBySlot[operation.request.slotKey] == operation.request.commitGeneration &&
            operation.request.commitGeneration > clearGenerationFloor &&
            operation.request.commitGeneration > (retiredOwnerFloor[operation.request.nativePartitionKey] ?: 0L) &&
            operation.request.commitGeneration > (retiredSubjectFloor[operation.request.slotKey] ?: 0L)

    private fun canAcceptNetworkLocked(operation: Operation): Boolean =
        operations[operation.request.operationKey] === operation &&
            operation.result == null &&
            isCurrentLocked(operation)

    private fun ensureTimeoutLocked(operation: Operation) {
        if (operation.resultTimeout != null || operation.result != null || operation.closed) return
        operation.resultTimeout =
            timeoutScheduler.schedule(resultTimeoutMillis) {
                publish(operation, SlockAndroidAvatarLoadResult.TemporaryTransport)
            }
    }

    private fun scheduleRollbackCleanupLocked(
        operation: Operation,
        record: SlockAndroidAvatarRollbackRecord
    ) {
        operation.rollbackCleanup?.cancel()
        operation.rollbackCleanup =
            timeoutScheduler.schedule(rollbackGraceMillis) {
                synchronized(lock) {
                    if (operation.rollbackRecord === record) {
                        operation.rollbackEligible = false
                        if (store.commitRollback(record)) {
                            operation.rollbackRecord = null
                        }
                        operation.rollbackCleanup = null
                        pruneOperationsLocked()
                    }
                }
            }
    }

    private fun readValidated(key: SlockAndroidAvatarEntryKey): SlockAndroidAvatarBytePayload? {
        val payload = store.read(key) ?: return null
        if (contentValidator(payload.bytes, payload.contentType)) return payload
        store.invalidate(key)
        return null
    }

    private fun discardOperationFilesLocked(operation: Operation): Boolean {
        val deleted = store.deleteStage(operation.stage)
        if (deleted) operation.stage = null
        return deleted
    }

    private fun closeOperationsLocked(predicate: (Operation) -> Boolean): ClosedDeliveries {
        val resultCallbacks = mutableListOf<(SlockAndroidAvatarLoadResult) -> Unit>()
        val renderCallbacks = mutableListOf<(SlockAndroidAvatarBytePayload?) -> Unit>()
        var filesCleaned = true
        val iterator = operations.entries.iterator()
        while (iterator.hasNext()) {
            val operation = iterator.next().value
            if (!predicate(operation)) continue
            operation.closed = true
            operation.resultTimeout?.cancel()
            operation.rollbackCleanup?.cancel()
            operation.resultTimeout = null
            operation.rollbackCleanup = null
            val rollbackDeleted = operation.rollbackRecord?.let(store::deleteRollbackArtifacts) ?: true
            if (rollbackDeleted) {
                operation.rollbackRecord = null
                operation.rollbackEligible = false
            }
            val stageDeleted = discardOperationFilesLocked(operation)
            val operationFilesCleaned = rollbackDeleted && stageDeleted
            filesCleaned = filesCleaned && operationFilesCleaned
            resultCallbacks += operation.resultWaiters.values
            renderCallbacks += operation.renderWaiters.values
            operation.resultWaiters.clear()
            operation.renderWaiters.clear()
            if (operationFilesCleaned) iterator.remove()
        }
        return ClosedDeliveries(resultCallbacks, renderCallbacks, filesCleaned)
    }

    private fun pruneOperationsLocked(
        maxSize: Int = SlockAndroidAvatarCacheContract.MaxRetainedOperations
    ) {
        if (operations.size <= maxSize) return
        val removable =
            operations.values
                .filter {
                    it.result != null &&
                        it.stage == null &&
                        it.rollbackRecord == null &&
                        it.resultWaiters.isEmpty() &&
                        it.renderWaiters.isEmpty()
                }
                .sortedBy { it.order }
                .iterator()
        while (operations.size > maxSize && removable.hasNext()) {
            val operation = removable.next()
            operation.resultTimeout?.cancel()
            operations.remove(operation.request.operationKey)
        }
    }

    private fun prunePreparedOperationsLocked() {
        if (preparedOperations.size <= SlockAndroidAvatarCacheContract.MaxRetainedOperations) return
        preparedOperations.entries
            .sortedBy { it.value.order }
            .take(preparedOperations.size - SlockAndroidAvatarCacheContract.MaxRetainedOperations)
            .forEach { preparedOperations.remove(it.key) }
    }

    private fun outcomeMatches(resultStatus: String, outcome: String): Boolean =
        when (outcome) {
            "successful_bytes" -> resultStatus == "success"
            "not_modified" -> resultStatus == "not_modified"
            "resource_absent" -> resultStatus == "resource_absent"
            "invalid_content" -> resultStatus == "invalid_content"
            "temporary_transport" -> resultStatus == "temporary_transport"
            else -> false
        }

    private data class ClosedDeliveries(
        val resultCallbacks: List<(SlockAndroidAvatarLoadResult) -> Unit>,
        val renderCallbacks: List<(SlockAndroidAvatarBytePayload?) -> Unit>,
        val filesCleaned: Boolean
    ) {
        fun deliver() {
            resultCallbacks.forEach { callback ->
                runCatching { callback(SlockAndroidAvatarLoadResult.TemporaryTransport) }
            }
            renderCallbacks.forEach { callback -> runCatching { callback(null) } }
        }
    }
}
