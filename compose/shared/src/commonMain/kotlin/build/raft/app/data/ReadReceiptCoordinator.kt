package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.network.sync.ReadStateV1ProductionOwner
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Identity that owns a read receipt: environment + account + server. Every segment is REQUIRED.
 * There is no owner-less/"legacy" receipt: the production owner validator only admits an owner
 * equal to the live environment/session, so a constant placeholder could never be executed and
 * would only let two unknown identities share one prediction queue (task #1121).
 */
data class ReadReceiptOwner(
    val environmentId: String,
    val accountId: String,
    val serverId: String
) {
    init {
        require(environmentId.isNotBlank()) { "environmentId must not be blank" }
        require(accountId.isNotBlank()) { "accountId must not be blank" }
        require(serverId.isNotBlank()) { "serverId must not be blank" }
    }
}

internal fun ReadReceiptOwner.toReadStateV1ProductionOwner(): ReadStateV1ProductionOwner =
    ReadStateV1ProductionOwner(environmentId, accountId, serverId)

internal fun ReadStateV1ProductionOwner.toReadReceiptOwner(): ReadReceiptOwner =
    ReadReceiptOwner(environmentId, accountId, serverId)

sealed interface ReadReceiptTarget {
    data object All : ReadReceiptTarget
    data class ToSeq(val seq: Long) : ReadReceiptTarget
}

data class ReadReceiptPrediction(
    val owner: ReadReceiptOwner,
    val channelId: ChannelId,
    val revision: Long,
    val target: ReadReceiptTarget
)

/**
 * Process-scoped owner for visible read receipts.
 *
 * A channel has one monotonic optimistic prediction and one request queue. A read-all target
 * covers any concurrent read-to-seq request, while a newer target supersedes older completion
 * rollback. UI stores consume [ReadReceiptPrediction] instead of owning independent predictions.
 */
class ReadReceiptCoordinator(
    private val scope: CoroutineScope,
    private val repository: ReadReceiptRepository,
    private val requestRunner:
        suspend (ReadReceiptRequest, suspend () -> DataResult<ReadReceiptResult>) ->
            DataResult<ReadReceiptResult> =
        { _, block -> block() },
    private val ownerValidator: suspend (ReadReceiptOwner) -> Boolean = { true },
    private val onOptimisticRead: (ChannelId) -> Unit = {},
    private val onPrediction: (ReadReceiptPrediction) -> Unit = {},
    private val onOwnerRevoked: (ReadReceiptOwner) -> Unit = {},
    private val onResult: (ReadReceiptRequest, DataResult<ReadReceiptResult>) -> Unit = { _, _ -> },
    private val onFailure: (ReadReceiptRequest, DataResult<ReadReceiptResult>) -> Unit = { _, _ -> }
) {
    private val lock = SynchronizedObject()
    private val channels = mutableMapOf<ReadReceiptKey, ChannelQueue>()
    private val acknowledgedSeqs = mutableMapOf<ReadReceiptKey, Long>()
    private var nextRevision = 0L
    private var generation = 0L
    private val _predictions = MutableSharedFlow<ReadReceiptPrediction>(extraBufferCapacity = 64)
    val predictions = _predictions.asSharedFlow()
    private val _failures = MutableSharedFlow<ReadReceiptFailure>(extraBufferCapacity = 64)
    val failures = _failures.asSharedFlow()
    private val _ownerRevocations = MutableSharedFlow<ReadReceiptOwnerRevocation>(extraBufferCapacity = 16)
    val ownerRevocations = _ownerRevocations.asSharedFlow()

    fun markReadToSeq(
        owner: ReadReceiptOwner,
        channelId: ChannelId,
        seq: Long
    ): ReadReceiptPrediction =
        enqueue(owner, channelId, if (seq > 0L) ReadReceiptTarget.ToSeq(seq) else ReadReceiptTarget.All).prediction

    fun markReadAll(
        owner: ReadReceiptOwner,
        channelId: ChannelId
    ): ReadReceiptPrediction =
        enqueue(owner, channelId, ReadReceiptTarget.All).prediction

    suspend fun awaitReadToSeq(
        owner: ReadReceiptOwner,
        channelId: ChannelId,
        seq: Long
    ): DataResult<ReadReceiptResult> =
        enqueue(owner, channelId, if (seq > 0L) ReadReceiptTarget.ToSeq(seq) else ReadReceiptTarget.All)
            .completion
            .await()

    suspend fun awaitReadAll(
        owner: ReadReceiptOwner,
        channelId: ChannelId
    ): DataResult<ReadReceiptResult> =
        enqueue(owner, channelId, ReadReceiptTarget.All).completion.await()

    fun clear() {
        val jobs =
            synchronized(lock) {
                generation += 1L
                val active = channels.values.mapNotNull(ChannelQueue::job)
                channels.values.forEach { queue ->
                    queue.pending?.waiters?.forEach { waiter -> waiter.cancel() }
                    queue.inFlight?.waiters?.forEach { waiter -> waiter.cancel() }
                }
                channels.clear()
                acknowledgedSeqs.clear()
                active
            }
        jobs.forEach(Job::cancel)
    }

    /**
     * Revokes only one verified owner. Its queued/in-flight commands and optimistic overlay are discarded,
     * while other environments/accounts/servers continue independently.
     */
    fun revoke(owner: ReadReceiptOwner) {
        val jobs =
            synchronized(lock) {
                val revoked = channels.filterKeys { key -> key.owner == owner }
                revoked.values.forEach { queue ->
                    queue.pending?.waiters?.forEach { waiter -> waiter.cancel() }
                    queue.inFlight?.waiters?.forEach { waiter -> waiter.cancel() }
                }
                revoked.keys.forEach { key -> channels.remove(key) }
                acknowledgedSeqs.keys.removeAll { key -> key.owner == owner }
                revoked.values.mapNotNull(ChannelQueue::job)
            }
        jobs.forEach(Job::cancel)
        onOwnerRevoked(owner)
        _ownerRevocations.tryEmit(ReadReceiptOwnerRevocation(owner))
    }

    private fun enqueue(
        owner: ReadReceiptOwner,
        channelId: ChannelId,
        requestedTarget: ReadReceiptTarget
    ): EnqueueResult {
        val target =
            if (requestedTarget is ReadReceiptTarget.ToSeq && requestedTarget.seq <= 0L) {
                ReadReceiptTarget.All
            } else {
                requestedTarget
            }
        val waiter = CompletableDeferred<DataResult<ReadReceiptResult>>()
        var emittedPrediction: ReadReceiptPrediction? = null
        var jobToStart: Job? = null
        val result =
            synchronized(lock) {
                val key = ReadReceiptKey(owner, channelId)
                val queue = channels.getOrPut(key) { ChannelQueue() }
                val acknowledgedSeq = acknowledgedSeqs[key]
                if (target is ReadReceiptTarget.ToSeq && acknowledgedSeq != null && target.seq <= acknowledgedSeq) {
                    val prediction =
                        queue.currentPrediction
                            ?: ReadReceiptPrediction(owner, channelId, nextRevision, target)
                    waiter.complete(
                        DataResult.Content(
                            ReadReceiptResult(
                                channelId = channelId,
                                seq = acknowledgedSeq,
                                changed = false
                            )
                        )
                    )
                    return@synchronized EnqueueResult(prediction, waiter)
                }
                val covering = queue.coveringRequest(target)
                if (covering != null) {
                    covering.waiters += waiter
                    return@synchronized EnqueueResult(covering.prediction, waiter)
                }

                val prediction =
                    ReadReceiptPrediction(
                        owner = owner,
                        channelId = channelId,
                        revision = ++nextRevision,
                        target = target
                    )
                emittedPrediction = prediction
                queue.currentPrediction = prediction
                val pending = queue.pending
                if (pending == null) {
                    queue.pending = PendingRequest(prediction, generation, mutableListOf(waiter))
                } else if (target.covers(pending.prediction.target)) {
                    pending.waiters += waiter
                    queue.pending = PendingRequest(prediction, generation, pending.waiters)
                } else {
                    pending.waiters += waiter
                    queue.pending = PendingRequest(prediction, generation, pending.waiters)
                }
                if (queue.job == null) {
                    queue.job = scope.launch(start = CoroutineStart.LAZY) { drain(key, queue) }
                    jobToStart = queue.job
                }
                EnqueueResult(prediction, waiter)
            }
        emittedPrediction?.let { prediction ->
            onOptimisticRead(prediction.channelId)
            onPrediction(prediction)
            _predictions.tryEmit(prediction)
        }
        jobToStart?.start()
        return result
    }

    private suspend fun drain(key: ReadReceiptKey, queue: ChannelQueue) {
        try {
            while (true) {
                val inFlight =
                    synchronized(lock) {
                        val pending = queue.pending ?: return
                        queue.pending = null
                        InFlightRequest(
                            prediction = pending.prediction,
                            generation = pending.generation,
                            request = pending.prediction.toRequest(),
                            waiters = pending.waiters
                        ).also { queue.inFlight = it }
                    }
                val result =
                    if (!ownerValidator(inFlight.prediction.owner)) {
                        DataResult.Error(ScreenError(code = "read_receipt_owner_stale", retryable = false))
                    } else {
                        requestRunner(inFlight.request) {
                            when (val target = inFlight.prediction.target) {
                                is ReadReceiptTarget.ToSeq ->
                                    repository.markChannelReadToSeq(key.channelId, target.seq)
                                ReadReceiptTarget.All -> repository.markChannelReadAll(key.channelId)
                            }
                        }
                    }
                val completion =
                    synchronized(lock) {
                        val stillCurrent =
                            generation == inFlight.generation &&
                                channels[key] === queue &&
                                queue.inFlight === inFlight
                        if (!stillCurrent) return@synchronized CompletionDisposition.Stale
                        if (result.isReadReceiptSuccess() && inFlight.prediction.target is ReadReceiptTarget.ToSeq) {
                            val seq = inFlight.prediction.target.seq
                            acknowledgedSeqs[key] = maxOf(acknowledgedSeqs[key] ?: seq, seq)
                        }
                        queue.inFlight = null
                        CompletionDisposition.Current(
                            superseded = queue.currentPrediction?.revision != inFlight.prediction.revision
                        )
                    }
                if (completion is CompletionDisposition.Stale) continue
                inFlight.waiters.forEach { waiter -> waiter.complete(result) }
                onResult(inFlight.request, result)
                if (
                    !result.isReadReceiptSuccess() &&
                    completion is CompletionDisposition.Current &&
                    !completion.superseded
                ) {
                    onFailure(inFlight.request, result)
                    _failures.tryEmit(ReadReceiptFailure(inFlight.request, result))
                }
            }
        } finally {
            val successor =
                synchronized(lock) {
                    if (channels[key] !== queue) return@synchronized null
                    queue.inFlight = null
                    queue.job = null
                    if (queue.pending == null) {
                        channels.remove(key)
                        null
                    } else {
                        scope.launch(start = CoroutineStart.LAZY) { drain(key, queue) }
                            .also { queue.job = it }
                    }
                }
            successor?.start()
        }
    }

    private data class ReadReceiptKey(
        val owner: ReadReceiptOwner,
        val channelId: ChannelId
    )

    private data class ChannelQueue(
        var pending: PendingRequest? = null,
        var inFlight: InFlightRequest? = null,
        var currentPrediction: ReadReceiptPrediction? = null,
        var job: Job? = null
    ) {
        fun coveringRequest(target: ReadReceiptTarget): MutableRequest? {
            inFlight?.takeIf { it.prediction.target.covers(target) }?.let { return it }
            pending?.takeIf { it.prediction.target.covers(target) }?.let { return it }
            return null
        }
    }

    private sealed interface MutableRequest {
        val prediction: ReadReceiptPrediction
        val waiters: MutableList<CompletableDeferred<DataResult<ReadReceiptResult>>>
    }

    private data class PendingRequest(
        override val prediction: ReadReceiptPrediction,
        val generation: Long,
        override val waiters: MutableList<CompletableDeferred<DataResult<ReadReceiptResult>>>
    ) : MutableRequest

    private data class InFlightRequest(
        override val prediction: ReadReceiptPrediction,
        val generation: Long,
        val request: ReadReceiptRequest,
        override val waiters: MutableList<CompletableDeferred<DataResult<ReadReceiptResult>>>
    ) : MutableRequest

    private data class EnqueueResult(
        val prediction: ReadReceiptPrediction,
        val completion: Deferred<DataResult<ReadReceiptResult>>
    )

    private sealed interface CompletionDisposition {
        data object Stale : CompletionDisposition
        data class Current(val superseded: Boolean) : CompletionDisposition
    }
}

data class ReadReceiptFailure(
    val request: ReadReceiptRequest,
    val result: DataResult<ReadReceiptResult>
)

data class ReadReceiptOwnerRevocation(val owner: ReadReceiptOwner)

sealed interface ReadReceiptRequest {
    val owner: ReadReceiptOwner
    val channelId: ChannelId
    val revision: Long

    data class ToSeq(
        override val channelId: ChannelId,
        val seq: Long,
        override val owner: ReadReceiptOwner,
        override val revision: Long = 0L
    ) : ReadReceiptRequest

    data class All(
        override val channelId: ChannelId,
        override val owner: ReadReceiptOwner,
        override val revision: Long = 0L
    ) : ReadReceiptRequest
}

private fun ReadReceiptPrediction.toRequest(): ReadReceiptRequest =
    when (val target = target) {
        is ReadReceiptTarget.ToSeq ->
            ReadReceiptRequest.ToSeq(
                channelId = channelId,
                seq = target.seq,
                owner = owner,
                revision = revision
            )
        ReadReceiptTarget.All ->
            ReadReceiptRequest.All(
                channelId = channelId,
                owner = owner,
                revision = revision
            )
    }

private fun ReadReceiptTarget.covers(other: ReadReceiptTarget): Boolean =
    when (this) {
        ReadReceiptTarget.All -> true
        is ReadReceiptTarget.ToSeq -> other is ReadReceiptTarget.ToSeq && seq >= other.seq
    }

private fun DataResult<ReadReceiptResult>.isReadReceiptSuccess(): Boolean =
    this is DataResult.Content || this is DataResult.Empty
