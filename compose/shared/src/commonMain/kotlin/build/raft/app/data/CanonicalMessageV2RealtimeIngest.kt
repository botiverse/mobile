package build.raft.app.data

import build.raft.app.network.sync.CanonicalMessageFoldInput
import build.raft.app.network.sync.CanonicalMessageFoldKind
import build.raft.app.network.sync.CanonicalMessageV2CanaryOutcome
import build.raft.app.network.sync.CanonicalMessageV2CommitResult
import build.raft.app.network.sync.CanonicalMessageV2EnvelopeConversion
import build.raft.app.network.sync.CanonicalMessageV2GenerationEngine
import build.raft.app.network.sync.CanonicalMessageV2LiveCoverageTracker
import build.raft.app.network.sync.CanonicalMessageV2ProductionCanary
import build.raft.app.network.sync.CanonicalMessageV2ScopePoisoned
import build.raft.app.network.sync.CanonicalMessageV2ThreadParent
import build.raft.app.network.sync.DurablePartitionKey
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.frameLabel
import build.raft.app.network.sync.partialLabel
import build.raft.app.network.sync.toCanonicalMessageV2Conversion
import build.raft.app.network.sync.unknownFieldsByFactId
import build.raft.app.network.sync.v2ScopeOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * How long the realtime owner lets one frame's durable V2 commit hold its display (message storage Phase 3a,
 * fail-open): beyond it the frame is displayed without waiting. The commit itself is never cancelled by this: it
 * still runs, in order, on the ingest's worker.
 */
internal const val CANONICAL_MESSAGE_V2_REALTIME_COMMIT_TIMEOUT_MS: Long = 1_500L

/** Frames waiting for the ingest worker; one more is skipped and ends its channel's continuity. */
internal const val CANONICAL_MESSAGE_V2_REALTIME_QUEUE_CAPACITY: Int = 256

/** A loose write runs partition retention once per this many loose writes of its partition (scope retention always). */
internal const val CANONICAL_MESSAGE_V2_REALTIME_PARTITION_RETENTION_EVERY: Int = 32

/** What [CanonicalMessageV2RealtimeIngest] did with one realtime frame. */
internal sealed interface CanonicalMessageV2RealtimeIngestOutcome {
    /** The live coverage tracker proved the frame continuous: written, and the head segment extended. */
    data object Covered : CanonicalMessageV2RealtimeIngestOutcome

    /** Written as a loose fact (no segment claims it). */
    data object Loose : CanonicalMessageV2RealtimeIngestOutcome

    /** Not written, by rule (flag off, not a storable V2 fact, the same frame again, …). */
    data class Skipped(val reason: String) : CanonicalMessageV2RealtimeIngestOutcome

    /** The write threw; nothing was committed (one SQLite transaction). */
    data class Failed(val reason: String) : CanonicalMessageV2RealtimeIngestOutcome
}

/**
 * Message storage design 2026-10-27, Phase 3a (P3): every decoded realtime `message:new` / `message:updated` is
 * stored as a V2 fact, whether or not this process ever entered its scope.
 *
 * Grant and threading. The realtime owner calls [partitionGeneration] inside the exact-current-grant slot (an
 * in-memory read), so a frame of a revoked grant (sign-out, server access lost) never gets here. The disk work then
 * runs OFF that slot and off the caller's thread: [enqueue] hands the frame to one worker coroutine on [dispatcher],
 * which processes frames strictly in arrival order. The owner awaits the result (bounded, fail-open) before it
 * displays, so commit-then-display order holds without holding the authority registry or the main thread during
 * SQLite work. A partition cleared after the grant check (server access lost) bumps its generation: the frame is
 * then dropped, and a loose write is refused by the engine (I12).
 *
 * Per frame, on the worker:
 * 1. The [tracker] (the canary's continuity rules) goes first. When it proves the frame continuous with the head
 *    segment it writes the frame and extends the head segment: [CanonicalMessageV2RealtimeIngestOutcome.Covered].
 *    A tracker failure is reported back to it ([CanonicalMessageV2LiveCoverageTracker.noteFrameUnrecorded]) so no
 *    claim ever grows over the frame, and the frame is still written loose.
 * 2. Otherwise — no window in this process, a discontinuous one, an older connection's frame, a frame below the
 *    window, an enter in flight — the frame is written as a LOOSE fact: one `applyCoverage(coverage = null)` under
 *    the scope writer, bound to the generation read under the grant, with `revAtRequest` = the current revision (a
 *    realtime frame is never older than what is stored), insert-only for a `New` (a stored fact may already be
 *    newer), the existing I1 merge rules, `userDriven = false` (a scope nobody opened ranks least recently used),
 *    no scope-commit-generation bump (it claims nothing, so it never makes a fetched page stale) and partition
 *    retention only every [partitionRetentionEvery] loose writes of the partition (scope retention always). A
 *    missing scope header is created: a top-level scope from the frame's typed context, a reply thread naming the
 *    parent the frame claims (design Q6; an enter confirms or rewrites it).
 *
 * A frame that cannot be queued (more than [queueCapacity] waiting: the disk is stuck) is skipped and reported as
 * unrecorded to the tracker.
 *
 * Not written: the `sync_core_messages` flag is off for the server; a frame without a storable V2 scope or that
 * does not convert to a (partial) V2 fact; a `message:updated` naming a reply thread (its reply count is not in
 * the frame, and writing 0 would erase the stored one: Phase 3c's ReplyCount marker); a frame equal to one of the
 * last few written for the same fact (another owner entry delivered it again); a reply whose claimed parent
 * differs from the stored header's.
 */
internal class CanonicalMessageV2RealtimeIngest(
    private val tracker: CanonicalMessageV2LiveCoverageTracker,
    private val generationEngine: CanonicalMessageV2GenerationEngine,
    private val isEnabledForServer: (serverId: String) -> Boolean,
    logger: (String) -> Unit = {},
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val queueCapacity: Int = CANONICAL_MESSAGE_V2_REALTIME_QUEUE_CAPACITY,
    private val partitionRetentionEvery: Int = CANONICAL_MESSAGE_V2_REALTIME_PARTITION_RETENTION_EVERY,
) {
    private sealed interface Work {
        class Commit(
            val partition: DurablePartitionKey,
            val foldInput: CanonicalMessageFoldInput,
            val transportEpoch: Long?,
            val expectedPartitionGeneration: Long,
            val done: CompletableDeferred<CanonicalMessageV2RealtimeIngestOutcome>,
        ) : Work

        class Barrier(val done: CompletableDeferred<Unit>) : Work
    }

    /** Never throws: a throwing log sink must not take the worker (and with it all realtime storage) down. */
    private val logger: (String) -> Unit = { line ->
        try {
            logger(line)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Diagnostics are best-effort.
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val queue = Channel<Work>(capacity = queueCapacity)

    /**
     * Frames skipped because the queue was full, recorded synchronously in [enqueue] and reported to the tracker by
     * the worker before its next [CanonicalMessageV2LiveCoverageTracker.accept], so a frame queued after a skipped
     * one can never extend a claim over it. Guarded by [pendingUnrecordedLock].
     */
    private val pendingUnrecordedLock = SynchronizedObject()
    private val pendingUnrecorded = ArrayList<PendingSkip>()

    /** One skipped frame waiting for its continuity report, with the reports that already failed. */
    private class PendingSkip(val partition: DurablePartitionKey, val foldInput: CanonicalMessageFoldInput) {
        var failedReports = 0
    }

    // Worker-confined (only the single worker coroutine reads or writes these).
    private val recentLooseByFact = LinkedHashMap<Pair<DurablePartitionKey, String>, ArrayDeque<CanonicalMessageFoldInput>>()
    private val looseWritesByPartition = mutableMapOf<DurablePartitionKey, Long>()

    init {
        launchWorker()
    }

    /**
     * The single ordered worker. Every item is guarded as a whole: whatever one frame (or its logging) throws, the
     * frame completes as failed and the loop goes on. Should the worker still end while the ingest is open, it is
     * relaunched, so storage never silently stops while callers wait out their timeout on every frame.
     */
    private fun launchWorker() {
        scope.launch {
            try {
                for (work in queue) {
                    try {
                        when (work) {
                            is Work.Barrier -> {
                                reportPendingUnrecorded()
                                work.done.complete(Unit)
                            }
                            is Work.Commit -> work.done.complete(commitNow(work))
                        }
                    } catch (cancelled: CancellationException) {
                        // Only the ingest's own shutdown ends the worker; a stray cancellation is one failed frame.
                        if (!isActive) throw cancelled
                        work.fail("cancelled")
                    } catch (throwable: Throwable) {
                        work.fail("error=${throwable::class.simpleName ?: "unknown"}")
                    }
                }
            } finally {
                if (scope.isActive && !queue.isClosedForReceive) launchWorker()
            }
        }
    }

    /** Completes a work item that threw, without logging (the logger may be what threw). */
    private fun Work.fail(reason: String) {
        when (this) {
            is Work.Barrier -> done.complete(Unit)
            is Work.Commit -> done.complete(CanonicalMessageV2RealtimeIngestOutcome.Failed(reason))
        }
    }

    /** Worker only: hands every skipped frame to the tracker, in skip order. */
    private suspend fun reportPendingUnrecorded() {
        val pending = synchronized(pendingUnrecordedLock) {
            if (pendingUnrecorded.isEmpty()) return
            pendingUnrecorded.toList()
        }
        withContext(NonCancellable) {
            pending.forEach { skip ->
                // Removed only once reported: a report that throws keeps its skip (retried before the next frame)
                // and never costs the skips after it. After MAX_FAILED_SKIP_REPORTS failures the whole partition is
                // reported as a hole instead, once, and the skip is dropped (no unbounded retries or growth).
                val failure = try {
                    tracker.noteFrameUnrecorded(skip.partition, skip.foldInput)
                    null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    throwable
                }
                if (failure == null) {
                    synchronized(pendingUnrecordedLock) { pendingUnrecorded.remove(skip) }
                    return@forEach
                }
                skip.failedReports += 1
                val label = "server=${skip.partition.serverId} scope=${skip.foldInput.row.channelId} ${skip.foldInput.frameLabel()}"
                if (skip.failedReports == 1) {
                    logger("canonical_v2_realtime unrecorded_report_failed $label error=${failure::class.simpleName ?: "unknown"}")
                }
                if (skip.failedReports >= MAX_FAILED_SKIP_REPORTS) {
                    val escalated = runCatching { tracker.notePartitionHole(skip.partition, "unrecorded_report_failed") }.isSuccess
                    synchronized(pendingUnrecordedLock) { pendingUnrecorded.remove(skip) }
                    logger(
                        "canonical_v2_realtime unrecorded_report_dropped $label attempts=${skip.failedReports} " +
                            "partitionHole=${if (escalated) "reported" else "failed"}",
                    )
                }
            }
        }
    }

    /** Whether a skipped frame of [foldInput]'s channel is still waiting for its continuity report. */
    private fun hasPendingSkip(partition: DurablePartitionKey, foldInput: CanonicalMessageFoldInput): Boolean =
        synchronized(pendingUnrecordedLock) {
            pendingUnrecorded.any { it.partition == partition && it.foldInput.row.channelId == foldInput.row.channelId }
        }

    /** Whether the `sync_core_messages` flag lets [serverId]'s frames be stored at all. */
    fun isEnabledFor(serverId: String): Boolean = isEnabledForServer(serverId)

    /**
     * The flag is off: nothing is stored and no disk is touched, but the frame is still reported to the tracker so a
     * window it held cannot claim across the frames missed while the flag was off. In-memory only.
     */
    suspend fun noteFlagOffFrame(partition: DurablePartitionKey, foldInput: CanonicalMessageFoldInput) {
        tracker.noteFrameUnrecorded(partition, foldInput)
    }

    /** The generation a commit of [partition] is bound to; read it under the exact current grant. */
    suspend fun partitionGeneration(partition: DurablePartitionKey): Long = generationEngine.partitionGeneration(partition)

    /**
     * Queues one frame. The result completes once the frame is processed; awaiting it may be abandoned (the owner's
     * fail-open timeout) without cancelling the commit.
     */
    fun enqueue(
        partition: DurablePartitionKey,
        foldInput: CanonicalMessageFoldInput,
        transportEpoch: Long?,
        expectedPartitionGeneration: Long,
    ): Deferred<CanonicalMessageV2RealtimeIngestOutcome> {
        require(partition.consumerId == SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
        val done = CompletableDeferred<CanonicalMessageV2RealtimeIngestOutcome>()
        val queued = queue.trySend(Work.Commit(partition, foldInput, transportEpoch, expectedPartitionGeneration, done))
        if (queued.isFailure) {
            // Recorded before this call returns: the worker reports it ahead of any frame queued after it.
            synchronized(pendingUnrecordedLock) { pendingUnrecorded += PendingSkip(partition, foldInput) }
            done.complete(skip(partition, foldInput, if (queued.isClosed) "ingest_closed" else "queue_full capacity=$queueCapacity"))
        }
        return done
    }

    /** [enqueue] with the generation read now, awaited (direct callers and tests). */
    suspend fun commit(
        partition: DurablePartitionKey,
        foldInput: CanonicalMessageFoldInput,
        transportEpoch: Long?,
    ): CanonicalMessageV2RealtimeIngestOutcome =
        enqueue(partition, foldInput, transportEpoch, partitionGeneration(partition)).await()

    /** Returns once every frame queued before this call is processed (tests, diagnostics). */
    internal suspend fun awaitIdle() {
        val done = CompletableDeferred<Unit>()
        queue.send(Work.Barrier(done))
        done.await()
    }

    /** Stops the worker (tests; production keeps one ingest for the process). */
    internal fun close() {
        queue.close()
        scope.cancel()
    }

    private suspend fun commitNow(work: Work.Commit): CanonicalMessageV2RealtimeIngestOutcome {
        val partition = work.partition
        val foldInput = work.foldInput
        if (generationEngine.partitionGeneration(partition) != work.expectedPartitionGeneration) {
            // Cleared after the grant check (server access lost): the canary's state went with it.
            return skip(partition, foldInput, "partition_revoked")
        }
        reportPendingUnrecorded()
        // A skip of this channel whose report has not landed yet: the tracker may still believe the stream whole, so
        // this frame must not extend any claim. Stored loose instead.
        if (hasPendingSkip(partition, foldInput)) {
            if (!isEnabledForServer(partition.serverId)) return skip(partition, foldInput, "flag_off")
            return writeLoose(work, CanonicalMessageV2CanaryOutcome.Held("unrecorded_report_pending"))
        }
        val tracked = try {
            tracker.accept(partition, foldInput, work.transportEpoch)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { tracker.noteFrameUnrecorded(partition, foldInput) }
            throw cancelled
        } catch (throwable: Throwable) {
            tracker.noteFrameUnrecorded(partition, foldInput)
            logger(
                "canonical_v2_realtime tracker_failed server=${partition.serverId} scope=${foldInput.row.channelId} " +
                    "${foldInput.frameLabel()} error=${throwable::class.simpleName ?: "unknown"}",
            )
            CanonicalMessageV2CanaryOutcome.Held("tracker_failed")
        }
        if (tracked == CanonicalMessageV2CanaryOutcome.Committed) return CanonicalMessageV2RealtimeIngestOutcome.Covered
        if (!isEnabledForServer(partition.serverId)) return skip(partition, foldInput, "flag_off")
        return writeLoose(work, tracked)
    }

    private suspend fun writeLoose(
        work: Work.Commit,
        tracked: CanonicalMessageV2CanaryOutcome,
    ): CanonicalMessageV2RealtimeIngestOutcome {
        val partition = work.partition
        val foldInput = work.foldInput
        val scope = foldInput.v2ScopeOrNull(partition) ?: return skip(partition, foldInput, "no_v2_scope")
        val row = foldInput.row
        if (foldInput.kind == CanonicalMessageFoldKind.Updated && row.threadId != null) {
            return skip(partition, foldInput, "updated_thread")
        }
        val converted = when (
            val conversion = foldInput.toCanonicalMessageV2Conversion(partition, replyCount = 0, allowPartial = true)
        ) {
            is CanonicalMessageV2EnvelopeConversion.Converted -> conversion
            is CanonicalMessageV2EnvelopeConversion.NotFull -> return skip(partition, foldInput, "not_full_v2 ${conversion.detail}")
        }
        val key = partition to row.id
        if (recentLooseByFact[key]?.contains(foldInput) == true) return skip(partition, foldInput, "duplicate_delivery")
        val threadParent = if (scope.scopeKind == REPLY_THREAD_SCOPE_KIND) {
            val context = checkNotNull(row.conversationContext)
            CanonicalMessageV2ThreadParent(
                parentScopeKind = checkNotNull(context.parentChannelType),
                parentScopeId = checkNotNull(context.parentChannelId),
                rootMessageId = checkNotNull(context.parentMessageId),
            )
        } else {
            null
        }
        val writes = (looseWritesByPartition[partition] ?: 0L) + 1L
        looseWritesByPartition[partition] = writes
        val result = try {
            generationEngine.withScopeWriter(scope) {
                generationEngine.applyCoverage(
                    scope = scope,
                    envelopes = listOf(converted.envelope),
                    coverage = null,
                    revAtRequest = generationEngine.storedLocalRev(partition),
                    userDriven = false,
                    expectedPartitionGeneration = work.expectedPartitionGeneration,
                    threadParent = threadParent,
                    insertOnly = foldInput.kind == CanonicalMessageFoldKind.New,
                    unknownFieldsByFactId = converted.unknownFieldsByFactId(),
                    runPartitionRetention = writes % partitionRetentionEvery == 0L,
                    countsAsScopeCommit = false,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (poisoned: CanonicalMessageV2ScopePoisoned) {
            // A stored header naming another parent, or a stored row this frame cannot merge with.
            return fail(partition, foldInput, "poisoned fact=${poisoned.factId.orEmpty()}")
        } catch (throwable: Throwable) {
            return fail(partition, foldInput, "write_failed error=${throwable::class.simpleName ?: "unknown"}")
        }
        return when (result) {
            CanonicalMessageV2CommitResult.Committed -> {
                val recent = recentLooseByFact.remove(key) ?: ArrayDeque()
                recent.addLast(foldInput)
                while (recent.size > RECENT_FRAMES_PER_FACT) recent.removeFirst()
                recentLooseByFact[key] = recent
                while (recentLooseByFact.size > RECENT_FACT_MEMORY) recentLooseByFact.remove(recentLooseByFact.keys.first())
                logger(
                    "canonical_v2_realtime outcome=loose server=${partition.serverId} scope=${scope.scopeId} " +
                        "scopeKind=${scope.scopeKind} ${foldInput.frameLabel()} tracker=${tracked.label()}" +
                        converted.partialLabel(),
                )
                CanonicalMessageV2RealtimeIngestOutcome.Loose
            }
            CanonicalMessageV2CommitResult.PartitionRevoked -> skip(partition, foldInput, "partition_revoked")
            CanonicalMessageV2CommitResult.ScopeEvicted -> skip(partition, foldInput, "retention_evicted")
            is CanonicalMessageV2CommitResult.BaselineRequired -> skip(partition, foldInput, "baseline_required")
        }
    }

    private fun skip(
        partition: DurablePartitionKey,
        foldInput: CanonicalMessageFoldInput,
        reason: String,
    ): CanonicalMessageV2RealtimeIngestOutcome {
        logger(
            "canonical_v2_realtime outcome=skipped server=${partition.serverId} scope=${foldInput.row.channelId} " +
                "${foldInput.frameLabel()} reason=$reason",
        )
        return CanonicalMessageV2RealtimeIngestOutcome.Skipped(reason)
    }

    private fun fail(
        partition: DurablePartitionKey,
        foldInput: CanonicalMessageFoldInput,
        reason: String,
    ): CanonicalMessageV2RealtimeIngestOutcome {
        logger(
            "canonical_v2_realtime outcome=failed server=${partition.serverId} scope=${foldInput.row.channelId} " +
                "${foldInput.frameLabel()} reason=$reason",
        )
        return CanonicalMessageV2RealtimeIngestOutcome.Failed(reason)
    }

    private fun CanonicalMessageV2CanaryOutcome.label(): String = when (this) {
        CanonicalMessageV2CanaryOutcome.Committed -> "covered"
        CanonicalMessageV2CanaryOutcome.NotSelected -> "no_claim"
        CanonicalMessageV2CanaryOutcome.Pending -> "enter_pending"
        is CanonicalMessageV2CanaryOutcome.Held -> "held"
    }

    private companion object {
        const val REPLY_THREAD_SCOPE_KIND = "thread"

        /** Facts whose recent loose frames are remembered, and frames remembered per fact. */
        const val RECENT_FACT_MEMORY = 256
        const val RECENT_FRAMES_PER_FACT = 8

        /** A skipped frame's continuity report is tried this many times before the whole partition is reported. */
        const val MAX_FAILED_SKIP_REPORTS = 3
    }
}

/**
 * The provider's realtime gateway (message storage Phase 3a): [captureUnderGrant] runs inside the exact-current-grant
 * slot and reads only the partition generation; the returned commit runs after the slot, on the ingest's worker.
 */
internal class RealtimeIngestCanonicalMessageV2OwnerGateway(
    private val ingest: CanonicalMessageV2RealtimeIngest,
    private val canary: CanonicalMessageV2ProductionCanary,
) : CanonicalMessageV2OwnerGateway {
    override suspend fun accept(
        grant: RealtimeGrantRecord,
        foldInput: CanonicalMessageFoldInput,
        transportEpoch: Long?,
    ): CanonicalMessageV2CanaryOutcome {
        val partition = grant.durablePartition(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
        return ingest.enqueue(partition, foldInput, transportEpoch, ingest.partitionGeneration(partition)).await().toCanaryOutcome()
    }

    override suspend fun captureUnderGrant(
        grant: RealtimeGrantRecord,
        foldInput: CanonicalMessageFoldInput,
        transportEpoch: Long?,
    ): suspend () -> Unit {
        val partition = grant.durablePartition(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
        // Flag off: no queue round-trip and no disk, only the in-memory continuity report.
        if (!ingest.isEnabledFor(partition.serverId)) return { ingest.noteFlagOffFrame(partition, foldInput) }
        val expectedGeneration = ingest.partitionGeneration(partition)
        return { ingest.enqueue(partition, foldInput, transportEpoch, expectedGeneration).await() }
    }

    override suspend fun noteTransportEpoch(grant: RealtimeGrantRecord, transportEpoch: Long) {
        canary.noteTransportEpoch(grant.durablePartition(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID), transportEpoch)
    }

    private fun CanonicalMessageV2RealtimeIngestOutcome.toCanaryOutcome(): CanonicalMessageV2CanaryOutcome = when (this) {
        CanonicalMessageV2RealtimeIngestOutcome.Covered,
        CanonicalMessageV2RealtimeIngestOutcome.Loose,
        -> CanonicalMessageV2CanaryOutcome.Committed
        is CanonicalMessageV2RealtimeIngestOutcome.Skipped -> CanonicalMessageV2CanaryOutcome.NotSelected
        is CanonicalMessageV2RealtimeIngestOutcome.Failed -> CanonicalMessageV2CanaryOutcome.Held(reason)
    }
}
