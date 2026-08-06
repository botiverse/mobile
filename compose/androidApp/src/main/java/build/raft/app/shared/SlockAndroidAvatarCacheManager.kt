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

internal enum class SlockAndroidAvatarMemoryDelivery {
    Delivered,
    Miss,
    Rejected
}

/** Coordinates typed descriptor rendering, native await/finalize, and one network flight. */
internal class SlockAndroidAvatarCacheManager(
    private val store: SlockAndroidAvatarDiskStore,
    private val networkFetcher: SlockAndroidAvatarNetworkFetcher,
    private val contentValidator: (ByteArray, String?) -> Boolean,
    private val workExecutor: SlockAvatarCacheWorkExecutor,
    private val timeoutScheduler: SlockAvatarCacheTimeoutScheduler = SlockAndroidAvatarCacheTimeouts,
    private val resultTimeoutMillis: Long = SlockAndroidAvatarCacheContract.ResultTimeoutMillis,
    private val rollbackGraceMillis: Long = SlockAndroidAvatarCacheContract.RollbackGraceMillis,
    private val clearDecodedMemory: () -> Unit = {},
    private val retireDecodedOwner: (String) -> Unit = {},
    private val retireDecodedSubject: (String, String) -> Unit = { _, _ -> }
) {
    private class PendingDelivery(
        val id: Long,
        val callback: (SlockAndroidAvatarBytePayload?) -> Unit
    )

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
        val pendingDeliveries = linkedMapOf<Long, PendingDelivery>()
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
    private var terminalMutationInProgress = false

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
            if (terminalMutationInProgress) return null
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
        var operation: Operation? = null
        var delivery: PendingDelivery? = null
        var terminalFetch: Boolean? = null
        synchronized(lock) {
            operation = admittedOperationLocked(request)
            val admitted = operation
            if (admitted != null) {
                delivery = registerDeliveryLocked(admitted, callback)
            }
            if (admitted != null && request.readMode == SlockAndroidAvatarReadMode.Fetch) {
                terminalFetch =
                    when {
                        admitted.finalized &&
                            (admitted.result?.status == "success" || admitted.result?.status == "not_modified") -> true
                        admitted.result != null &&
                            admitted.result?.status != "success" &&
                            admitted.result?.status != "not_modified" -> false
                        else -> null
                    }
                if (terminalFetch == null) ensureTimeoutLocked(admitted)
            }
        }
        val admitted = operation
        val pendingDelivery = delivery
        if (admitted == null || pendingDelivery == null) {
            runCatching { callback(null) }
            return
        }
        if (request.readMode == SlockAndroidAvatarReadMode.Fetch && terminalFetch != null) {
            if (terminalFetch == true) {
                scheduleReadDelivery(admitted, pendingDelivery)
            } else {
                deliverPending(admitted, pendingDelivery, null)
            }
            return
        }
        when (request.readMode) {
            SlockAndroidAvatarReadMode.Suppressed -> {
                if (deliverPending(admitted, pendingDelivery, null)) {
                    publish(admitted, SlockAndroidAvatarLoadResult.Suppressed)
                }
            }
            SlockAndroidAvatarReadMode.RenderFresh,
            SlockAndroidAvatarReadMode.RenderStaleSuppressed,
            SlockAndroidAvatarReadMode.RenderStaleRevalidate ->
                scheduleReadDelivery(admitted, pendingDelivery) { payload ->
                    when (request.readMode) {
                        SlockAndroidAvatarReadMode.RenderFresh,
                        SlockAndroidAvatarReadMode.RenderStaleSuppressed ->
                            publish(
                                admitted,
                                if (payload != null) {
                                    SlockAndroidAvatarLoadResult.Rendered
                                } else {
                                    SlockAndroidAvatarLoadResult.TemporaryTransport
                                }
                            )
                        SlockAndroidAvatarReadMode.RenderStaleRevalidate -> startNetwork(admitted)
                        else -> Unit
                    }
                }
            SlockAndroidAvatarReadMode.Fetch -> startNetwork(admitted)
        }
    }

    /** Decoded-memory lookup and drawable delivery share the terminal generation/epoch lease. */
    fun <T : Any> deliverMemory(
        request: SlockAndroidAvatarRequest,
        payloadProvider: () -> T?,
        callback: (T) -> Unit
    ): SlockAndroidAvatarMemoryDelivery {
        var operation: Operation? = null
        val outcome =
            synchronized(lock) {
                val admitted = admittedOperationLocked(request)
                    ?: return@synchronized SlockAndroidAvatarMemoryDelivery.Rejected
                if (!isCurrentLocked(admitted)) {
                    return@synchronized SlockAndroidAvatarMemoryDelivery.Rejected
                }
                operation = admitted
                val payload = runCatching(payloadProvider).getOrNull()
                    ?: return@synchronized SlockAndroidAvatarMemoryDelivery.Miss
                runCatching { callback(payload) }
                SlockAndroidAvatarMemoryDelivery.Delivered
            }
        if (outcome != SlockAndroidAvatarMemoryDelivery.Delivered) return outcome
        val deliveredOperation = operation ?: return SlockAndroidAvatarMemoryDelivery.Rejected
        when (request.readMode) {
            SlockAndroidAvatarReadMode.RenderFresh,
            SlockAndroidAvatarReadMode.RenderStaleSuppressed ->
                publish(deliveredOperation, SlockAndroidAvatarLoadResult.Rendered)
            SlockAndroidAvatarReadMode.RenderStaleRevalidate -> startNetwork(deliveredOperation)
            SlockAndroidAvatarReadMode.Suppressed ->
                publish(deliveredOperation, SlockAndroidAvatarLoadResult.Suppressed)
            SlockAndroidAvatarReadMode.Fetch -> startNetwork(deliveredOperation)
        }
        return outcome
    }

    fun finalize(request: SlockAndroidAvatarRequest, outcome: String): SlockAndroidAvatarMutation {
        var operationForDelivery: Operation? = null
        var deliveriesToSchedule: List<PendingDelivery> = emptyList()
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
                        val admittedPayload =
                            store.readPromoted(rollback)?.takeIf { payload ->
                                contentValidator(payload.bytes, payload.contentType)
                            }
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
                        operationForDelivery = operation
                        deliveriesToSchedule = operation.pendingDeliveries.values.toList()
                        scheduleRollbackCleanupLocked(operation, rollback)
                    }
                    "not_modified" -> {
                        val admittedPayload = readValidated(request.entryKey)
                        if (admittedPayload == null) {
                            return@synchronized SlockAndroidAvatarMutation.Failed("avatar_cache_not_modified_bytes_missing")
                        }
                        operation.finalized = true
                        renderPayload = admittedPayload
                        operationForDelivery = operation
                        deliveriesToSchedule = operation.pendingDeliveries.values.toList()
                    }
                    "resource_absent", "invalid_content" -> {
                        val transientsDeleted = discardOperationFilesLocked(operation)
                        val activeMappingDeleted = store.invalidate(request.entryKey)
                        if (!transientsDeleted || !activeMappingDeleted) {
                            return@synchronized SlockAndroidAvatarMutation.Failed(
                                "avatar_cache_authoritative_failure_cleanup_failed"
                            )
                        }
                        operation.finalized = true
                        if (request.readMode == SlockAndroidAvatarReadMode.Fetch) {
                            closePendingDeliveriesLocked(operation)
                        }
                    }
                    "temporary_transport" -> {
                        if (!discardOperationFilesLocked(operation)) {
                            return@synchronized SlockAndroidAvatarMutation.Failed(
                                "avatar_cache_transient_cleanup_failed"
                            )
                        }
                        operation.finalized = true
                        if (request.readMode == SlockAndroidAvatarReadMode.Fetch) {
                            closePendingDeliveriesLocked(operation)
                        }
                    }
                    else -> return@synchronized SlockAndroidAvatarMutation.Failed("avatar_cache_finalize_outcome_unknown")
                }
                pruneOperationsLocked()
                SlockAndroidAvatarMutation.Accepted
            }
        val deliveryOperation = operationForDelivery
        if (mutation == SlockAndroidAvatarMutation.Accepted && deliveryOperation != null) {
            scheduleDeliveries(deliveryOperation, deliveriesToSchedule, renderPayload)
        }
        return mutation
    }

    fun rollback(request: SlockAndroidAvatarRequest): SlockAndroidAvatarMutation {
        val accepted =
            synchronized(lock) {
                preparedOperations.remove(request.operationKey)
                val operation = operations[request.operationKey]
                    ?.takeIf { it.request == request }
                    ?: return@synchronized !store.hasRecoveredArtifacts(request.entryKey)
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
                val recoveredAbsent = !store.hasRecoveredArtifacts(request.entryKey)
                operation.closed = true
                closePendingDeliveriesLocked(operation)
                operation.resultTimeout?.cancel()
                operation.resultTimeout = null
                val cleaned = diskRestored && stageDeleted && recoveredAbsent
                if (cleaned) operations.remove(request.operationKey)
                cleaned
            }
        return if (accepted) {
            SlockAndroidAvatarMutation.Accepted
        } else {
            SlockAndroidAvatarMutation.Failed("avatar_cache_rollback_failed")
        }
    }

    fun retireOwner(partitionKey: String): SlockAndroidAvatarMutation {
        val removed =
            synchronized(lock) {
                if (terminalMutationInProgress) return@synchronized false
                terminalMutationInProgress = true
                try {
                    retiredOwnerFloor[partitionKey] =
                        maxOf(retiredOwnerFloor[partitionKey] ?: 0L, maxSeenGeneration)
                    latestGenerationBySlot.keys.removeAll { it.partitionKey == partitionKey }
                    retiredSubjectFloor.keys.removeAll { it.partitionKey == partitionKey }
                    preparedOperations.keys.removeAll { it.entryKey.partitionKey == partitionKey }
                    val deliveries = closeOperationsLocked { it.request.nativePartitionKey == partitionKey }
                    val indexedRemoved = store.retireOwner(partitionKey)
                    val decodedRemoved = runCatching { retireDecodedOwner(partitionKey) }.isSuccess
                    deliveries.deliver()
                    deliveries.filesCleaned && indexedRemoved && decodedRemoved
                } finally {
                    terminalMutationInProgress = false
                }
            }
        return if (removed) SlockAndroidAvatarMutation.Accepted
        else SlockAndroidAvatarMutation.Failed("avatar_cache_owner_retire_failed")
    }

    fun retireSubject(partitionKey: String, subjectKey: String): SlockAndroidAvatarMutation {
        val slot = SlockAndroidAvatarSlotKey(partitionKey, subjectKey)
        val removed =
            synchronized(lock) {
                if (terminalMutationInProgress) return@synchronized false
                terminalMutationInProgress = true
                try {
                    retiredSubjectFloor[slot] = maxOf(retiredSubjectFloor[slot] ?: 0L, maxSeenGeneration)
                    latestGenerationBySlot.remove(slot)
                    preparedOperations.keys.removeAll {
                        it.entryKey.partitionKey == partitionKey && it.entryKey.subjectKey == subjectKey
                    }
                    val deliveries = closeOperationsLocked { it.request.slotKey == slot }
                    val indexedRemoved = store.retireSubject(partitionKey, subjectKey)
                    val decodedRemoved =
                        runCatching { retireDecodedSubject(partitionKey, subjectKey) }.isSuccess
                    deliveries.deliver()
                    deliveries.filesCleaned && indexedRemoved && decodedRemoved
                } finally {
                    terminalMutationInProgress = false
                }
            }
        return if (removed) SlockAndroidAvatarMutation.Accepted
        else SlockAndroidAvatarMutation.Failed("avatar_cache_subject_retire_failed")
    }

    fun clearAll(): Boolean {
        return synchronized(lock) {
            if (terminalMutationInProgress) return@synchronized false
            terminalMutationInProgress = true
            try {
                processEpoch++
                clearGenerationFloor = maxOf(clearGenerationFloor, maxSeenGeneration)
                latestGenerationBySlot.clear()
                retiredOwnerFloor.clear()
                retiredSubjectFloor.clear()
                preparedOperations.clear()
                val deliveries = closeOperationsLocked { true }
                val indexedCleared = store.clearAll()
                val decodedCleared = runCatching(clearDecodedMemory).isSuccess
                deliveries.deliver()
                deliveries.filesCleaned && indexedCleared && decodedCleared
            } finally {
                terminalMutationInProgress = false
            }
        }
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
            if (
                operation.request.readMode == SlockAndroidAvatarReadMode.Fetch &&
                deliveredResult.status != "success" &&
                deliveredResult.status != "not_modified"
            ) {
                closePendingDeliveriesLocked(operation)
            }
            pruneOperationsLocked()
            resultCallbacks.forEach { callback -> runCatching { callback(deliveredResult) } }
        }
    }

    private fun admittedOperationLocked(request: SlockAndroidAvatarRequest): Operation? {
        if (terminalMutationInProgress) return null
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

    private fun registerDeliveryLocked(
        operation: Operation,
        callback: (SlockAndroidAvatarBytePayload?) -> Unit
    ): PendingDelivery {
        val delivery = PendingDelivery(operation.nextWaiterId++, callback)
        operation.pendingDeliveries[delivery.id] = delivery
        return delivery
    }

    private fun scheduleReadDelivery(
        operation: Operation,
        delivery: PendingDelivery,
        afterDelivery: (SlockAndroidAvatarBytePayload?) -> Unit = {}
    ) {
        workExecutor.execute {
            val payload = readValidated(operation.request.entryKey)
            workExecutor.execute {
                if (deliverPending(operation, delivery, payload)) afterDelivery(payload)
            }
        }
    }

    private fun scheduleDeliveries(
        operation: Operation,
        deliveries: List<PendingDelivery>,
        payload: SlockAndroidAvatarBytePayload?
    ) {
        deliveries.forEach { delivery ->
            workExecutor.execute { deliverPending(operation, delivery, payload) }
        }
    }

    /**
     * Claims one pending drawable delivery while holding the generation/epoch
     * lease. Decode, decoded-cache insertion, and the drawable callback all run
     * synchronously inside [PendingDelivery.callback], so a terminal mutation
     * cannot return until that delivery is complete.
     */
    private fun deliverPending(
        operation: Operation,
        delivery: PendingDelivery,
        payload: SlockAndroidAvatarBytePayload?
    ): Boolean =
        synchronized(lock) {
            if (operation.pendingDeliveries[delivery.id] !== delivery) return@synchronized false
            operation.pendingDeliveries.remove(delivery.id)
            val admitted =
                operations[operation.request.operationKey] === operation && isCurrentLocked(operation)
            runCatching { delivery.callback(payload.takeIf { admitted }) }
            admitted
        }

    private fun closePendingDeliveriesLocked(operation: Operation) {
        val callbacks = operation.pendingDeliveries.values.map(PendingDelivery::callback)
        operation.pendingDeliveries.clear()
        callbacks.forEach { callback -> runCatching { callback(null) } }
    }

    private fun discardOperationFilesLocked(operation: Operation): Boolean {
        val deleted = store.deleteStage(operation.stage)
        if (deleted) operation.stage = null
        return deleted
    }

    private fun closeOperationsLocked(predicate: (Operation) -> Boolean): ClosedDeliveries {
        val resultCallbacks = mutableListOf<(SlockAndroidAvatarLoadResult) -> Unit>()
        val deliveryCallbacks = mutableListOf<(SlockAndroidAvatarBytePayload?) -> Unit>()
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
            deliveryCallbacks += operation.pendingDeliveries.values.map(PendingDelivery::callback)
            operation.resultWaiters.clear()
            operation.pendingDeliveries.clear()
            if (operationFilesCleaned) iterator.remove()
        }
        return ClosedDeliveries(resultCallbacks, deliveryCallbacks, filesCleaned)
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
                        it.pendingDeliveries.isEmpty()
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
        val deliveryCallbacks: List<(SlockAndroidAvatarBytePayload?) -> Unit>,
        val filesCleaned: Boolean
    ) {
        fun deliver() {
            resultCallbacks.forEach { callback ->
                runCatching { callback(SlockAndroidAvatarLoadResult.TemporaryTransport) }
            }
            deliveryCallbacks.forEach { callback -> runCatching { callback(null) } }
        }
    }
}
