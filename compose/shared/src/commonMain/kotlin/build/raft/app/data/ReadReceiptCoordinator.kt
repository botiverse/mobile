package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.network.sync.ReadStateV1ProductionOwner
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
 *
 * With a [pendingStore], a read survives the process: it is saved before its POST and cleared by the
 * ack. [replayPendingReads] sends what is left, always as "read to seq". A read kept for a later
 * attempt is not reported as a failure, since nothing has to roll back. The server's read position
 * only moves forward on a read (`GREATEST`, botiverse/slock `applyScopeBoundary`), so sending a read
 * twice or late cannot lower it, but it can undo a Mark as Unread: it raises a cursor that mark moved
 * back, and it clears a "marked unread" dot. So [markUnread] and [settleReadsBeforeUnread] cancel the
 * saved read first, and a replay drops a read that a fresh read state shows was marked unread from
 * another device afterwards ([ReadReceiptPendingEntry.isSupersededBy]). A read saved with nothing
 * known about the scope has nothing to compare with, and the last write wins.
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
    private val onFailure: (ReadReceiptRequest, DataResult<ReadReceiptResult>) -> Unit = { _, _ -> },
    private val pendingStore: ReadReceiptPendingStore? = null,
    /**
     * Asked only when [ownerValidator] refused: true when the owner's account is still the signed-in
     * one and has lost the server, so its saved reads go. Any other refusal (signed out, another
     * account or environment) keeps them for when that owner comes back.
     */
    private val ownerAccessLost: suspend (ReadReceiptOwner) -> Boolean = { false },
    private val replayRetryDelayMillis: (Int) -> Long = { attempt -> readReceiptReplayRetryDelayMillis(attempt) },
    /**
     * A fresh read state per scope for the owner, asked before a replay and only when reads are waiting.
     * Null when no fresh answer can be had: the reads stay saved and unsent.
     */
    private val replayFrontierLoader: suspend (ReadReceiptOwner) -> Map<ChannelId, ReadReceiptFrontier>? = { emptyMap() },
    /** A saved read was dropped because the conversation was marked unread after it. */
    private val onReplayDropped: (ReadReceiptOwner, ChannelId) -> Unit = { _, _ -> }
) {
    private val lock = SynchronizedObject()
    private val channels = mutableMapOf<ReadReceiptKey, ChannelQueue>()
    private val acknowledgedSeqs = mutableMapOf<ReadReceiptKey, Long>()
    private val knownFrontiers = mutableMapOf<ReadReceiptKey, ReadReceiptFrontier>()
    private var nextRevision = 0L
    private var generation = 0L
    private var replayAttempt = 0
    private var replayRetryJob: Job? = null
    private var replayPassJob: Job? = null
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

    /**
     * [knownSeq] is the newest message this read is known to cover. It is what gets saved in case the
     * POST fails: a later replay reads to it, never "all". Without one, nothing is saved.
     */
    fun markReadAll(
        owner: ReadReceiptOwner,
        channelId: ChannelId,
        knownSeq: Long? = null
    ): ReadReceiptPrediction =
        enqueue(owner, channelId, ReadReceiptTarget.All, knownSeq = knownSeq).prediction

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
                knownFrontiers.clear()
                replayAttempt = 0
                active + listOfNotNull(replayRetryJob, replayPassJob).also {
                    replayRetryJob = null
                    replayPassJob = null
                }
            }
        jobs.forEach(Job::cancel)
    }

    /**
     * Sends the saved reads of every owner the validator admits, one at a time. Runs on process start
     * and on reconnect, and restarts the retry backoff. It stops at the first network failure and
     * tries again later: up to [READ_RECEIPT_REPLAY_MAX_ATTEMPTS] times, then at the next reconnect.
     */
    fun replayPendingReads() {
        if (pendingStore == null) return
        val retry =
            synchronized(lock) {
                replayAttempt = 0
                replayRetryJob.also { replayRetryJob = null }
            }
        retry?.cancel()
        startReplayPass()
    }

    /** The saved reads of [owner] by channel: what Home keeps showing as read until the ack. */
    fun pendingReadSeqs(owner: ReadReceiptOwner): Map<ChannelId, Long> =
        pendingStore?.seqsFor(owner).orEmpty()

    /**
     * The reads a Home response for [owner] must not undo: those still saved, plus those [atRequest]
     * (taken when the request went out) acknowledged since, which the response may not reflect.
     */
    fun pendingReadOverlay(owner: ReadReceiptOwner, atRequest: Map<ChannelId, Long>): Map<ChannelId, Long> {
        val saved = pendingReadSeqs(owner)
        if (atRequest.isEmpty()) return saved
        val acknowledgedSince =
            synchronized(lock) {
                atRequest.filter { (channelId, seq) ->
                    val acknowledged = acknowledgedSeqs[ReadReceiptKey(owner, channelId)]
                    acknowledged != null && acknowledged >= seq
                }
            }
        return acknowledgedSince + saved
    }

    /**
     * Mark as Unread is about to go out for ([owner], [channelId]): cancels the saved read, so a kill
     * from here on cannot replay it over the mark, then waits for reads already on their way.
     */
    suspend fun settleReadsBeforeUnread(owner: ReadReceiptOwner, channelId: ChannelId) {
        pendingStore?.remove(owner, channelId)
        forgetAcknowledged(owner, channelId)
        // This device is moving the cursor back: what it knew no longer holds for its next read.
        synchronized(lock) { knownFrontiers.remove(ReadReceiptKey(owner, channelId)) }
        val behind = synchronized(lock) { channels[ReadReceiptKey(owner, channelId)]?.job }
        behind?.join()
    }

    /**
     * Forgets the read point acknowledged for ([owner], [channelId]), so the next read-to-seq goes out
     * even at a seq already read. Mark as Unread calls it: the server's read point moved back, or stayed
     * and the conversation was marked unread (botiverse/slock #11795); either way only a new read
     * write reads it again, and a remembered acknowledgement would swallow that write.
     */
    fun forgetAcknowledged(owner: ReadReceiptOwner, channelId: ChannelId) {
        synchronized(lock) {
            acknowledgedSeqs.remove(ReadReceiptKey(owner, channelId))
        }
    }

    /**
     * Mark as Unread (`POST /channels/:id/unread`) for ([owner], [channelId]) from a page that reads
     * through this coordinator (an open thread's menus, botiverse/slock #11687). It goes out after
     * the channel's queued and in-flight reads, as web queues the Unread behind the conversation's
     * read toggles, so an earlier read cannot land after it; then the acknowledged read point is
     * forgotten ([forgetAcknowledged]) so the next read writes again.
     */
    suspend fun markUnread(owner: ReadReceiptOwner, channelId: ChannelId): DataResult<ReadReceiptResult> {
        settleReadsBeforeUnread(owner, channelId)
        val result = repository.markChannelUnread(channelId)
        if (result is DataResult.Content) {
            forgetAcknowledged(owner, channelId)
            noteUnreadAnswer(owner, channelId, result.data)
        }
        return result
    }

    /**
     * What an answer or a push says about ([owner], [channelId]): the base a read made now is saved
     * with. The newest `readStateVersion` wins, so a lagging answer cannot replace a newer fact.
     */
    fun noteReadFrontier(owner: ReadReceiptOwner, channelId: ChannelId, frontier: ReadReceiptFrontier) {
        synchronized(lock) {
            val key = ReadReceiptKey(owner, channelId)
            val known = knownFrontiers[key]
            if (known == null || (frontier.readStateVersion ?: -1L) >= (known.readStateVersion ?: -1L)) {
                knownFrontiers[key] = frontier
            }
        }
    }

    /** Mark as Unread's answer for ([owner], [channelId]). A 202 carries no read state and notes nothing. */
    fun noteUnreadAnswer(owner: ReadReceiptOwner, channelId: ChannelId, answer: ReadReceiptResult) {
        val maxReadSeq = answer.maxReadSeq ?: return
        noteReadFrontier(owner, channelId, ReadReceiptFrontier(maxReadSeq, answer.readStateVersion, answer.markedUnread == true))
    }

    /**
     * Revokes only one verified owner. Its queued/in-flight commands and optimistic overlay are discarded,
     * while other environments/accounts/servers continue independently. Its saved reads stay: the
     * validator keeps them from being sent, and only a confirmed loss of access removes them.
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
                knownFrontiers.keys.removeAll { key -> key.owner == owner }
                revoked.values.mapNotNull(ChannelQueue::job)
            }
        jobs.forEach(Job::cancel)
        onOwnerRevoked(owner)
        _ownerRevocations.tryEmit(ReadReceiptOwnerRevocation(owner))
    }

    private fun enqueue(
        owner: ReadReceiptOwner,
        channelId: ChannelId,
        requestedTarget: ReadReceiptTarget,
        knownSeq: Long? = null,
        replay: Boolean = false
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
                    if (replay) pendingStore?.clearThrough(owner, channelId, acknowledgedSeq)
                    return@synchronized EnqueueResult(prediction, waiter)
                }
                // Saved before the POST can start. A replay never writes: its entry is already there,
                // or Mark as Unread just cancelled it and it must stay cancelled.
                if (!replay) {
                    val savedSeq = (target as? ReadReceiptTarget.ToSeq)?.seq ?: knownSeq
                    if (savedSeq != null) pendingStore?.record(owner, channelId, savedSeq, knownFrontiers[key])
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
                // A replay is not a new read: the person read this earlier, and may have newer
                // messages (and notifications for them) now.
                if (!replay) emittedPrediction = prediction
                queue.currentPrediction = prediction
                val pending = queue.pending
                if (pending == null) {
                    queue.pending = PendingRequest(prediction, generation, mutableListOf(waiter), replay)
                } else if (target.covers(pending.prediction.target)) {
                    pending.waiters += waiter
                    queue.pending = PendingRequest(prediction, generation, pending.waiters, replay)
                } else {
                    pending.waiters += waiter
                    queue.pending = PendingRequest(prediction, generation, pending.waiters, replay)
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
                            waiters = pending.waiters,
                            replay = pending.replay,
                            savedSeqAtSend = pendingStore?.seq(key.owner, key.channelId)
                        ).also { queue.inFlight = it }
                    }
                if (inFlight.replay && inFlight.savedSeqAtSend == null) {
                    // Mark as Unread cancelled it between the replay's read of the store and now.
                    synchronized(lock) { if (queue.inFlight === inFlight) queue.inFlight = null }
                    val cancelled =
                        DataResult.Error(ScreenError(code = READ_RECEIPT_REPLAY_CANCELLED_CODE, retryable = false))
                    inFlight.waiters.forEach { waiter -> waiter.complete(cancelled) }
                    continue
                }
                val result =
                    if (!ownerValidator(inFlight.prediction.owner)) {
                        DataResult.Error(ScreenError(code = READ_RECEIPT_OWNER_STALE_CODE, retryable = false))
                    } else {
                        requestRunner(inFlight.request) {
                            when (val target = inFlight.prediction.target) {
                                is ReadReceiptTarget.ToSeq ->
                                    repository.markChannelReadToSeq(key.channelId, target.seq)
                                ReadReceiptTarget.All -> repository.markChannelReadAll(key.channelId)
                            }
                        }
                    }
                val kept = settleSavedRead(key, inFlight, result)
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
                if (kept && result.isReadReceiptRetryableFailure()) scheduleReplayRetry()
                if (
                    !result.isReadReceiptSuccess() &&
                    !kept &&
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

    /** Applies [result] to the saved read. True when the read stays saved for a later attempt. */
    private suspend fun settleSavedRead(
        key: ReadReceiptKey,
        inFlight: InFlightRequest,
        result: DataResult<ReadReceiptResult>
    ): Boolean {
        val store = pendingStore ?: return false
        if (result.isReadReceiptSuccess()) {
            val sentSeq =
                (inFlight.prediction.target as? ReadReceiptTarget.ToSeq)?.seq ?: inFlight.savedSeqAtSend ?: 0L
            val ack = (result as? DataResult.Content)?.data
            ack?.maxReadSeq?.let { maxReadSeq ->
                noteReadFrontier(key.owner, key.channelId, ReadReceiptFrontier(maxReadSeq, ack.readStateVersion))
            }
            store.clearThrough(key.owner, key.channelId, maxOf(sentSeq, ack?.maxReadSeq ?: 0L, ack?.seq ?: 0L))
            synchronized(lock) { replayAttempt = 0 }
            return false
        }
        if (result.isReadReceiptOwnerStale()) {
            if (ownerAccessLost(key.owner)) {
                store.remove(key.owner, key.channelId)
                return false
            }
            return store.seq(key.owner, key.channelId) != null
        }
        if (result.isPermanentReadRefusal()) {
            store.remove(key.owner, key.channelId)
            return false
        }
        return store.seq(key.owner, key.channelId) != null
    }

    private fun startReplayPass() {
        val store = pendingStore ?: return
        val job = scope.launch(start = CoroutineStart.LAZY) { replaySavedReads(store) }
        val previous = synchronized(lock) { replayPassJob.also { replayPassJob = job } }
        previous?.cancel()
        job.start()
    }

    private suspend fun replaySavedReads(store: ReadReceiptPendingStore) {
        val frontiersByOwner = mutableMapOf<ReadReceiptOwner, Map<ChannelId, ReadReceiptFrontier>>()
        for (listed in store.entries()) {
            if (!ownerValidator(listed.owner)) {
                if (ownerAccessLost(listed.owner)) store.remove(listed.owner, listed.channelId)
                continue
            }
            val frontiers =
                frontiersByOwner[listed.owner]
                    ?: replayFrontierLoader(listed.owner)?.also { frontiersByOwner[listed.owner] = it }
            if (frontiers == null) {
                scheduleReplayRetry()
                return
            }
            // As saved now: this pass may have waited, and a new read or Mark as Unread changes the entry.
            val entry = store.entry(listed.owner, listed.channelId) ?: continue
            if (entry.isSupersededBy(frontiers[entry.channelId])) {
                store.remove(entry.owner, entry.channelId)
                onReplayDropped(entry.owner, entry.channelId)
                continue
            }
            val result =
                try {
                    enqueue(entry.owner, entry.channelId, ReadReceiptTarget.ToSeq(entry.seq), replay = true)
                        .completion
                        .await()
                } catch (cancellation: CancellationException) {
                    // This pass was cancelled, or only this entry's owner was revoked: carry on then.
                    currentCoroutineContext().ensureActive()
                    continue
                }
            if (result.isReadReceiptRetryableFailure()) break
        }
    }

    private fun scheduleReplayRetry() {
        val job =
            synchronized(lock) {
                if (replayRetryJob?.isActive == true) return
                if (replayAttempt >= READ_RECEIPT_REPLAY_MAX_ATTEMPTS) return
                val attempt = ++replayAttempt
                scope.launch(start = CoroutineStart.LAZY) {
                    delay(replayRetryDelayMillis(attempt))
                    synchronized(lock) { replayRetryJob = null }
                    startReplayPass()
                }.also { replayRetryJob = it }
            }
        job.start()
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
        override val waiters: MutableList<CompletableDeferred<DataResult<ReadReceiptResult>>>,
        val replay: Boolean = false
    ) : MutableRequest

    private data class InFlightRequest(
        override val prediction: ReadReceiptPrediction,
        val generation: Long,
        val request: ReadReceiptRequest,
        override val waiters: MutableList<CompletableDeferred<DataResult<ReadReceiptResult>>>,
        val replay: Boolean = false,
        val savedSeqAtSend: Long? = null
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

internal fun DataResult<ReadReceiptResult>.isReadReceiptSuccess(): Boolean =
    this is DataResult.Content || this is DataResult.Empty

private const val READ_RECEIPT_OWNER_STALE_CODE = "read_receipt_owner_stale"
private const val READ_RECEIPT_REPLAY_CANCELLED_CODE = "read_receipt_replay_cancelled"
internal const val READ_RECEIPT_REPLAY_MAX_ATTEMPTS = 6
private const val READ_RECEIPT_REPLAY_BASE_DELAY_MILLIS = 5_000L
private const val READ_RECEIPT_REPLAY_MAX_DELAY_MILLIS = 300_000L

/** 5 s, 15 s, 45 s, ... up to 5 minutes. */
internal fun readReceiptReplayRetryDelayMillis(attempt: Int): Long {
    var delayMillis = READ_RECEIPT_REPLAY_BASE_DELAY_MILLIS
    repeat((attempt - 1).coerceIn(0, READ_RECEIPT_REPLAY_MAX_ATTEMPTS)) { delayMillis *= 3 }
    return delayMillis.coerceAtMost(READ_RECEIPT_REPLAY_MAX_DELAY_MILLIS)
}

private fun DataResult<ReadReceiptResult>.isReadReceiptOwnerStale(): Boolean =
    this is DataResult.Error && error.code == READ_RECEIPT_OWNER_STALE_CODE

/**
 * The server said no and asking again will not change that. 403 is a lost membership or delegation;
 * 404 is how `POST /channels/:id/read` answers a channel the person can no longer see; any other 4xx
 * is a request the server will always refuse. 401 (expired sign-in), 408, 425 and 429 are not.
 */
internal fun DataResult<ReadReceiptResult>.isPermanentReadRefusal(): Boolean =
    when (this) {
        is DataResult.NoPermission -> blocker.code == "server_forbidden"
        is DataResult.Error -> {
            val status = error.code.removePrefix("http_").takeIf { error.code.startsWith("http_") }?.toIntOrNull()
            status != null && status in 400..499 && status != 408 && status != 425 && status != 429
        }
        is DataResult.Content,
        is DataResult.Empty -> false
    }

private fun DataResult<ReadReceiptResult>.isReadReceiptRetryableFailure(): Boolean =
    !isReadReceiptSuccess() &&
        !isPermanentReadRefusal() &&
        !isReadReceiptOwnerStale() &&
        !(this is DataResult.Error && error.code == READ_RECEIPT_REPLAY_CANCELLED_CODE)
