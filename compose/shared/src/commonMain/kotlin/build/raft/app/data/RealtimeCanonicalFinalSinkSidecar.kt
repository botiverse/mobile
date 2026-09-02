package build.raft.app.data

import build.raft.app.model.RealtimeEvent
import build.raft.app.network.sync.CurrentGrantMessageShadowCommitOutcome
import build.raft.app.network.sync.DurablePartitionKey
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.coroutineContext

internal data class RealtimeCanonicalFinalSinkEnvelope(
    val grant: RealtimeGrantRecord,
    val partition: DurablePartitionKey,
    val eventKey: String,
    val event: RealtimeEvent,
    val source: String,
    val capturedRegistrations: List<RealtimeCanonicalFinalSinkCapturedRegistration>,
)

internal enum class RealtimeCanonicalFinalSinkRouteDecision {
    LegacyDirect,
    ReserveThenCommit,
}

internal fun realtimeCanonicalFinalSinkRouteDecision(
    preflightEnabled: Boolean,
    hasDecodedCanonical: Boolean,
): RealtimeCanonicalFinalSinkRouteDecision =
    if (preflightEnabled && hasDecodedCanonical) {
        RealtimeCanonicalFinalSinkRouteDecision.ReserveThenCommit
    } else {
        RealtimeCanonicalFinalSinkRouteDecision.LegacyDirect
    }

internal enum class RealtimeCanonicalFinalSinkReservationState {
    Reserved,
    Published,
    Finalizing,
    Terminal,
}

internal data class RealtimeCanonicalFinalSinkDeliveryLedgerKey(
    val partition: DurablePartitionKey,
    val surfaceKey: RealtimeCanonicalFinalSinkSurfaceKey,
)

internal class RealtimeCanonicalFinalSinkReservation internal constructor(
    internal val grant: RealtimeGrantRecord,
    internal val id: Long,
) {
    internal val state = atomic(RealtimeCanonicalFinalSinkReservationState.Reserved)
}

internal class RealtimeCanonicalFinalSinkReceipt internal constructor(
    private val completion: CompletableDeferred<Boolean>,
) {
    suspend fun await(): Boolean = completion.await()

    val isCompleted: Boolean
        get() = completion.isCompleted
}

internal data class RealtimeCanonicalFinalSinkDebugSnapshot(
    val reserveCount: Int,
    val publishCount: Int,
    val releaseCount: Int,
    val outstandingReservations: Int,
    val activeWorkerCount: Int,
    val workerKeyCount: Int,
    /** Compatibility/debug field retained for the migrated oracle; T4 never writes a legacy/core cursor. */
    val coreCursorWriteCount: Int,
    val registrationCount: Int,
    val workerScopeCancellationCount: Int,
    val deliveredLedgerLimitPerSurface: Int = 1_024,
    val deliveredLedgerSizes: Map<RealtimeCanonicalFinalSinkDeliveryLedgerKey, Int> = emptyMap(),
    val latestReservationState: RealtimeCanonicalFinalSinkReservationState? = null,
    val publishedItemCount: Int = 0,
)

@Suppress("unused", "UNUSED_PARAMETER")
internal class RealtimeCanonicalFinalSinkSidecar(
    private val scope: CoroutineScope,
    private val capacity: Int,
    private val runIfCurrentGrant: suspend (RealtimeGrantRecord, suspend () -> Unit) -> Boolean,
    onCoreCursorWrite: (DurablePartitionKey, RealtimeEvent) -> Unit = { _, _ -> },
    registrationIdFactory: (() -> String)? = null,
    private val registrationDirectory: RealtimeCanonicalFinalSinkRegistrationDirectory =
        RealtimeCanonicalFinalSinkRegistrationDirectory(registrationIdFactory),
    private val workerDispatcherForGrant: (RealtimeGrantRecord) -> CoroutineDispatcher? = { null },
    private val deliveredLedgerLimitPerSurface: Int = 1_024,
    private val afterReservationPublished: suspend (RealtimeCanonicalFinalSinkReservation) -> Unit = {},
) {
    init {
        require(capacity > 0) { "canonical final sink capacity must be positive" }
        require(deliveredLedgerLimitPerSurface > 0) { "canonical final sink delivered ledger limit must be positive" }
    }

    private data class QueuedItem(
        val reservation: RealtimeCanonicalFinalSinkReservation,
        val envelope: RealtimeCanonicalFinalSinkEnvelope,
        val completion: CompletableDeferred<Boolean>,
    )

    private data class GrantQueue(
        val workerId: Long,
        val items: ArrayDeque<QueuedItem> = ArrayDeque(),
    )

    private val lock = SynchronizedObject()
    private val capacitySemaphore = Semaphore(capacity)
    private val reservationsById = mutableMapOf<Long, RealtimeCanonicalFinalSinkReservation>()
    private val publishedItemsByReservationId = mutableMapOf<Long, QueuedItem>()
    private val queuesByGrant = mutableMapOf<RealtimeGrantRecord, GrantQueue>()
    private val deliveredLedgers =
        mutableMapOf<
            RealtimeCanonicalFinalSinkDeliveryLedgerKey,
            LinkedHashMap<String, RealtimeCanonicalFinalSinkRegistrationHandle>,
        >()
    private val activeWorkerIds = mutableSetOf<Long>()
    private var nextReservationId = 0L
    private var nextWorkerId = 0L
    private var reserveCount = 0
    private var publishCount = 0
    private var releaseCount = 0
    private var outstandingReservations = 0
    private var activeWorkerCount = 0
    private var workerScopeCancellationCount = 0
    private var latestReservation: RealtimeCanonicalFinalSinkReservation? = null

    fun register(
        slot: RealtimeCanonicalFinalSinkRegistrationSlot,
        sink: RealtimeCanonicalFinalSink,
    ): RealtimeCanonicalFinalSinkRegistrationHandle = registrationDirectory.register(slot, sink)

    fun unregister(handle: RealtimeCanonicalFinalSinkRegistrationHandle): Boolean = registrationDirectory.unregister(handle)

    fun captureRegistrations(
        scopeLeaseId: String,
        serverId: String,
    ): List<RealtimeCanonicalFinalSinkCapturedRegistration> = registrationDirectory.capture(scopeLeaseId, serverId)

    suspend fun reserve(grant: RealtimeGrantRecord): RealtimeCanonicalFinalSinkReservation {
        capacitySemaphore.acquire()
        try {
            coroutineContext.ensureActive()
            return synchronized(lock) {
                reserveCount += 1
                outstandingReservations += 1
                RealtimeCanonicalFinalSinkReservation(grant = grant, id = ++nextReservationId).also {
                    reservationsById[it.id] = it
                    latestReservation = it
                }
            }
        } catch (throwable: Throwable) {
            capacitySemaphore.release()
            throw throwable
        }
    }

    suspend fun publishReserved(
        reservation: RealtimeCanonicalFinalSinkReservation,
        outcome: CurrentGrantMessageShadowCommitOutcome,
        envelope: RealtimeCanonicalFinalSinkEnvelope,
        legacyDirect: suspend () -> Unit = {},
    ): RealtimeCanonicalFinalSinkReceipt {
        val completion = CompletableDeferred<Boolean>()
        if (outcome == CurrentGrantMessageShadowCommitOutcome.Disabled) {
            releaseReservation(reservation)
            legacyDirect()
            completion.complete(true)
            return RealtimeCanonicalFinalSinkReceipt(completion)
        }
        if (!envelopeMatchesReservation(reservation, envelope)) {
            releaseReservation(reservation)
            completion.complete(false)
            return RealtimeCanonicalFinalSinkReceipt(completion)
        }
        val item = QueuedItem(reservation = reservation, envelope = envelope, completion = completion)
        var workerIdToStart: Long? = null
        val published =
            synchronized(lock) {
                if (
                    !reservation.state.compareAndSet(
                        RealtimeCanonicalFinalSinkReservationState.Reserved,
                        RealtimeCanonicalFinalSinkReservationState.Published,
                    )
                ) {
                    return@synchronized false
                }
                publishCount += 1
                publishedItemsByReservationId[reservation.id] = item
                val currentQueue = queuesByGrant[envelope.grant]
                if (currentQueue != null) {
                    currentQueue.items.addLast(item)
                } else {
                    val workerId = ++nextWorkerId
                    queuesByGrant[envelope.grant] =
                        GrantQueue(workerId = workerId).also { queue -> queue.items.addLast(item) }
                    activeWorkerIds += workerId
                    workerIdToStart = workerId
                }
                true
            }
        if (!published) {
            completion.complete(false)
            return RealtimeCanonicalFinalSinkReceipt(completion)
        }
        afterReservationPublished(reservation)
        val currentWorkerId = workerIdToStart
        if (
            currentWorkerId != null &&
            synchronized(lock) { queuesByGrant[envelope.grant]?.workerId == currentWorkerId }
        ) {
            try {
                val dispatcher = workerDispatcherForGrant(envelope.grant)
                scope.launch(dispatcher ?: EmptyCoroutineContext) {
                    drainGrantQueue(envelope.grant, currentWorkerId)
                }.invokeOnCompletion { cause ->
                    cleanupWorkerQueue(envelope.grant, currentWorkerId, cause)
                }
            } catch (throwable: Throwable) {
                releaseReservation(reservation)
                completion.completeExceptionally(throwable)
                throw throwable
            }
        }
        return RealtimeCanonicalFinalSinkReceipt(completion)
    }

    fun invalidateExcept(currentGrants: Set<RealtimeGrantRecord>) {
        val candidates =
            synchronized(lock) {
                reservationsById.values
                    .filter { reservation -> reservation.grant !in currentGrants }
                    .toList()
            }
        candidates.forEach { reservation ->
            terminalBeforeFinalizing(reservation) { completion -> completion.complete(false) }
        }
    }

    fun debugSnapshot(): RealtimeCanonicalFinalSinkDebugSnapshot =
        synchronized(lock) {
            RealtimeCanonicalFinalSinkDebugSnapshot(
                reserveCount = reserveCount,
                publishCount = publishCount,
                releaseCount = releaseCount,
                outstandingReservations = outstandingReservations,
                activeWorkerCount = activeWorkerCount,
                workerKeyCount = activeWorkerIds.size,
                coreCursorWriteCount = 0,
                registrationCount = registrationDirectory.registrationCount(),
                workerScopeCancellationCount = workerScopeCancellationCount,
                deliveredLedgerLimitPerSurface = deliveredLedgerLimitPerSurface,
                deliveredLedgerSizes = deliveredLedgers.mapValues { (_, ledger) -> ledger.size },
                latestReservationState = latestReservation?.state?.value,
                publishedItemCount = publishedItemsByReservationId.size,
            )
        }

    internal fun abandonReserved(reservation: RealtimeCanonicalFinalSinkReservation) {
        if (
            reservation.state.compareAndSet(
                RealtimeCanonicalFinalSinkReservationState.Reserved,
                RealtimeCanonicalFinalSinkReservationState.Terminal,
            )
        ) {
            finishTerminalAccounting(reservation)
        }
    }

    private suspend fun drainGrantQueue(
        grant: RealtimeGrantRecord,
        workerId: Long,
    ) {
        while (true) {
            val item = claimNextItem(grant, workerId) ?: return
            try {
                val applied = applyCurrentGrantFinal(item.reservation, item.envelope)
                item.completion.complete(applied)
            } catch (cancellation: CancellationException) {
                if (!coroutineContext.isActive) {
                    synchronized(lock) { workerScopeCancellationCount += 1 }
                    item.completion.completeExceptionally(cancellation)
                    throw cancellation
                }
                item.completion.completeExceptionally(cancellation)
            } catch (throwable: Throwable) {
                item.completion.completeExceptionally(throwable)
            } finally {
                synchronized(lock) { activeWorkerCount -= 1 }
                releaseReservation(item.reservation)
            }
        }
    }

    private fun claimNextItem(
        grant: RealtimeGrantRecord,
        workerId: Long,
    ): QueuedItem? =
        synchronized(lock) {
            val queue = queuesByGrant[grant]
            if (queue == null || queue.workerId != workerId) return@synchronized null
            while (queue.items.isNotEmpty()) {
                val item = queue.items.removeFirst()
                if (
                    item.reservation.state.compareAndSet(
                        RealtimeCanonicalFinalSinkReservationState.Published,
                        RealtimeCanonicalFinalSinkReservationState.Finalizing,
                    )
                ) {
                    publishedItemsByReservationId.remove(item.reservation.id)
                    activeWorkerCount += 1
                    return@synchronized item
                }
            }
            queuesByGrant.remove(grant)
            activeWorkerIds.remove(workerId)
            null
        }

    private suspend fun applyCurrentGrantFinal(
        reservation: RealtimeCanonicalFinalSinkReservation,
        envelope: RealtimeCanonicalFinalSinkEnvelope,
    ): Boolean {
        coroutineContext.ensureActive()
        if (!envelopeMatchesReservation(reservation, envelope)) return false
        return runIfCurrentGrant(envelope.grant) {
            coroutineContext.ensureActive()
            val claimed = registrationDirectory.claimCurrent(envelope.capturedRegistrations)
            if (claimed.isEmpty()) return@runIfCurrentGrant

            claimed.forEach { registration ->
                coroutineContext.ensureActive()
                val ledgerKey =
                    RealtimeCanonicalFinalSinkDeliveryLedgerKey(
                        partition = envelope.partition,
                        surfaceKey = registration.handle.slot.surfaceKey,
                    )
                val alreadyDelivered =
                    synchronized(lock) {
                        deliveredLedgers[ledgerKey]?.get(envelope.eventKey) == registration.handle
                    }
                if (alreadyDelivered) return@forEach
                try {
                    registration.sink.deliver(envelope.event)
                    synchronized(lock) {
                        val ledger = deliveredLedgers.getOrPut(ledgerKey) { LinkedHashMap() }
                        ledger.remove(envelope.eventKey)
                        ledger[envelope.eventKey] = registration.handle
                        while (ledger.size > deliveredLedgerLimitPerSurface) {
                            ledger.remove(ledger.entries.first().key)
                        }
                    }
                } catch (cancellation: CancellationException) {
                    if (!coroutineContext.isActive) throw cancellation
                } catch (_: Throwable) {
                    // Surface-local failures are repairable by a future replay of the same raw event.
                }
            }
        }
    }

    private fun envelopeMatchesReservation(
        reservation: RealtimeCanonicalFinalSinkReservation,
        envelope: RealtimeCanonicalFinalSinkEnvelope,
    ): Boolean =
        reservation.grant == envelope.grant &&
            envelope.partition == reservation.grant.durablePartition(SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID)

    private fun terminalBeforeFinalizing(
        reservation: RealtimeCanonicalFinalSinkReservation,
        completePublished: (CompletableDeferred<Boolean>) -> Unit,
    ): Boolean {
        while (true) {
            when (val state = reservation.state.value) {
                RealtimeCanonicalFinalSinkReservationState.Terminal,
                RealtimeCanonicalFinalSinkReservationState.Finalizing,
                -> return false

                RealtimeCanonicalFinalSinkReservationState.Reserved,
                RealtimeCanonicalFinalSinkReservationState.Published,
                -> if (reservation.state.compareAndSet(state, RealtimeCanonicalFinalSinkReservationState.Terminal)) {
                    val queued = finishTerminalAccounting(reservation)
                    if (queued != null) completePublished(queued.completion)
                    return true
                }
            }
        }
    }

    private fun releaseReservation(reservation: RealtimeCanonicalFinalSinkReservation) {
        val previous = reservation.state.getAndSet(RealtimeCanonicalFinalSinkReservationState.Terminal)
        if (previous == RealtimeCanonicalFinalSinkReservationState.Terminal) return
        finishTerminalAccounting(reservation)
    }

    private fun finishTerminalAccounting(
        reservation: RealtimeCanonicalFinalSinkReservation,
    ): QueuedItem? {
        val queued =
            synchronized(lock) {
                reservationsById.remove(reservation.id)
                val item = publishedItemsByReservationId.remove(reservation.id)
                if (item != null) removeQueuedItemLocked(item)
                releaseCount += 1
                outstandingReservations -= 1
                item
            }
        capacitySemaphore.release()
        return queued
    }

    private fun removeQueuedItemLocked(item: QueuedItem) {
        val queue = queuesByGrant[item.envelope.grant] ?: return
        val retained = ArrayDeque<QueuedItem>()
        while (queue.items.isNotEmpty()) {
            val candidate = queue.items.removeFirst()
            if (candidate.reservation.id != item.reservation.id) retained.addLast(candidate)
        }
        queue.items.addAll(retained)
        if (queue.items.isEmpty()) {
            queuesByGrant.remove(item.envelope.grant)
            activeWorkerIds.remove(queue.workerId)
        }
    }

    private fun cleanupWorkerQueue(
        grant: RealtimeGrantRecord,
        workerId: Long,
        cause: Throwable?,
    ) {
        val pending =
            synchronized(lock) {
                val queue = queuesByGrant[grant]
                if (queue == null || queue.workerId != workerId) return@synchronized emptyList()
                queuesByGrant.remove(grant)
                activeWorkerIds.remove(workerId)
                buildList {
                    while (queue.items.isNotEmpty()) add(queue.items.removeFirst())
                }
            }
        val terminalCause = cause ?: CancellationException("canonical final sink worker stopped")
        pending.forEach { item ->
            terminalBeforeFinalizing(item.reservation) { completion ->
                completion.completeExceptionally(terminalCause)
            }
        }
    }
}

internal data class RealtimeCanonicalFinalSinkLazyRouterDebugSnapshot(
    val constructionCount: Int,
    val sidecar: RealtimeCanonicalFinalSinkDebugSnapshot?,
)

internal sealed interface RealtimeCanonicalFinalPreparedReservationResult {
    data class Published(val receipt: RealtimeCanonicalFinalSinkReceipt) :
        RealtimeCanonicalFinalPreparedReservationResult

    data class NotPublished(val accepted: Boolean) : RealtimeCanonicalFinalPreparedReservationResult
}

@Suppress("unused")
internal class RealtimeCanonicalFinalSinkLazyRouter(
    private val runIfCurrentGrant: suspend (RealtimeGrantRecord, suspend () -> Unit) -> Boolean,
    private val sidecarFactory: () -> RealtimeCanonicalFinalSinkSidecar,
    private val afterMissingSidecarObserved: suspend () -> Unit = {},
) {
    private val lock = SynchronizedObject()
    private var sidecar: RealtimeCanonicalFinalSinkSidecar? = null
    private var constructionCount = 0

    /**
     * Prepared T4 bridge. Capacity is reserved outside the caller-owned exact authority gate; the callback
     * either publishes one receipt inside that gate or returns a terminal non-publish decision. A caller
     * cancellation after publication cannot cancel the shared queued item because abandoning Published is a
     * no-op and the receipt owns no worker job.
     */
    suspend fun acceptPrepared(
        grant: RealtimeGrantRecord,
        exactCommitAndPublish:
            suspend (
                RealtimeCanonicalFinalSinkSidecar,
                RealtimeCanonicalFinalSinkReservation,
            ) -> RealtimeCanonicalFinalPreparedReservationResult,
    ): Boolean {
        val router = sidecarForPreparedEvent()
        val reservation = router.reserve(grant)
        return try {
            when (val result = exactCommitAndPublish(router, reservation)) {
                is RealtimeCanonicalFinalPreparedReservationResult.Published -> result.receipt.await()
                is RealtimeCanonicalFinalPreparedReservationResult.NotPublished -> {
                    router.abandonReserved(reservation)
                    result.accepted
                }
            }
        } catch (throwable: Throwable) {
            router.abandonReserved(reservation)
            throw throwable
        }
    }

    suspend fun accept(
        preflightEnabled: Boolean,
        hasDecodedCanonical: Boolean,
        grant: RealtimeGrantRecord,
        commit: suspend () -> CurrentGrantMessageShadowCommitOutcome,
        envelope: (RealtimeCanonicalFinalSinkSidecar) -> RealtimeCanonicalFinalSinkEnvelope,
        legacyDirect: suspend () -> Unit,
    ): Boolean {
        if (
            realtimeCanonicalFinalSinkRouteDecision(preflightEnabled, hasDecodedCanonical) ==
            RealtimeCanonicalFinalSinkRouteDecision.LegacyDirect
        ) {
            return runIfCurrentGrant(grant, legacyDirect)
        }
        val router = sidecarForPreparedEvent()
        val reservation = router.reserve(grant)
        var receipt: RealtimeCanonicalFinalSinkReceipt? = null
        val current =
            try {
                runIfCurrentGrant(grant) {
                    val outcome = commit()
                    val committedEnvelope = envelope(router)
                    receipt =
                        router.publishReserved(
                            reservation = reservation,
                            outcome = outcome,
                            envelope = committedEnvelope,
                            legacyDirect = legacyDirect,
                        )
                }
            } catch (throwable: Throwable) {
                router.abandonReserved(reservation)
                throw throwable
            }
        if (!current) {
            router.abandonReserved(reservation)
            return false
        }
        return requireNotNull(receipt).await()
    }

    private suspend fun sidecarForPreparedEvent(): RealtimeCanonicalFinalSinkSidecar {
        val existing = synchronized(lock) { sidecar }
        return existing ?: run {
            afterMissingSidecarObserved()
            synchronized(lock) {
                sidecar ?: sidecarFactory().also {
                    sidecar = it
                    constructionCount += 1
                }
            }
        }
    }

    fun debugSnapshot(): RealtimeCanonicalFinalSinkLazyRouterDebugSnapshot =
        synchronized(lock) {
            RealtimeCanonicalFinalSinkLazyRouterDebugSnapshot(
                constructionCount = constructionCount,
                sidecar = sidecar?.debugSnapshot(),
            )
        }

    /** Eager authority invalidation without constructing the lazy sidecar. */
    fun invalidateIfConstructed(currentGrants: Set<RealtimeGrantRecord>) {
        val existing = synchronized(lock) { sidecar }
        existing?.invalidateExcept(currentGrants)
    }
}
