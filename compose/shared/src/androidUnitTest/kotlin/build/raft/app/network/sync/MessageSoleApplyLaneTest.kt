/*
 * Sole-apply slice-2 stage A — actor-level test for MessageSoleApplyLane: the thin per-scope actor
 * wrapping the pure engine. Commit-first delivery (fan-out only after the one-txn adapter returns),
 * post-failure darkness + single-flight rebaseline surfacing, and rebaseline recovery. Plain
 * coroutines (Unconfined — the mailbox consumer runs inline on the sender), NO SQLite: the adapter is
 * an in-memory fake. Production never constructs a lane in slice-2 (see the engine test's gate 8);
 * this constructs it directly, the stage-A test-only path.
 */
package build.raft.app.network.sync

import build.raft.app.canonical.message.CanonicalMessageCarry
import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.canonical.message.MessageAggregateField
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.ThreadId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageSoleApplyLaneTest {

    private val serverId = "server-1"
    private val channelId = "channel-1"
    private val scopeKey = SyncScopeKey(
        partition = DurablePartitionKey(serverId, "principal-1", SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID, "env-test"),
        scopeId = channelId,
        scopeKind = "channel",
    )

    /** In-memory one-txn stand-in: records applied frames; can be armed to fail the next N commits. */
    private class RecordingAdapter : FoldPersistenceAdapter {
        val applied = mutableListOf<FoldPersistFrame>()
        var failNextCommits = 0
        var failNextRestores = 0
        var restoreCount = 0
        var blockNextCommit: CompletableDeferred<Unit>? = null
        var commitEntered: CompletableDeferred<Unit>? = null
        var blockNextRestore: CompletableDeferred<Unit>? = null
        var restoreEntered: CompletableDeferred<Unit>? = null
        var durableFacts: List<CanonicalMessageRow> = emptyList()
        var durableCursor: PersistedCursor? = PersistedCursor(
            latestSeq = 0,
            oldestLoadedSeq = null,
            newestLoadedSeq = 0,
            epoch = null,
        )

        override suspend fun applyFrame(frame: FoldPersistFrame) {
            blockNextCommit?.let { gate ->
                blockNextCommit = null
                commitEntered?.complete(Unit)
                gate.await()
            }
            if (failNextCommits > 0) {
                failNextCommits--
                throw RuntimeException("commit failed (injected)")
            }
            applied += frame
            durableFacts = frame.facts
            durableCursor = frame.cursor
        }

        override suspend fun restore(scope: SyncScopeKey): RestoredFoldState {
            restoreCount++
            blockNextRestore?.let { gate ->
                blockNextRestore = null
                restoreEntered?.complete(Unit)
                gate.await()
            }
            if (failNextRestores > 0) {
                failNextRestores--
                throw RuntimeException("restore failed (injected)")
            }
            return RestoredFoldState(durableFacts, durableCursor)
        }
    }

    private fun canonicalRow(id: String, seq: Long) = CanonicalMessageRow(
        id = id,
        channelId = channelId,
        seq = seq,
        senderType = "human",
        senderId = "u1",
        content = "body-$id",
        messageType = "chat",
        createdAt = "2026-07-13T00:00:0${seq}Z",
        senderName = "Alice",
    )

    private fun frame(id: String, seq: Long): MessageSoleApplyLane.Frame {
        val event = MessageNew(
            message = Message(
                id = MessageId(id),
                threadId = ThreadId(channelId),
                channelId = ChannelId(channelId),
                seq = seq,
                author = MemberProfile(MemberId("u1"), "Alice", "alice", MemberKind.Human),
                timestampLabel = "now",
                body = "body-$id",
            ),
        )
        return MessageSoleApplyLane.Frame(
            event = event,
            decoded = CanonicalMessageCarry(
                row = canonicalRow(id, seq),
                presentAggregates = emptySet<MessageAggregateField>(),
            ),
            updated = false,
            epoch = null,
        )
    }

    private fun laneWith(
        adapter: FoldPersistenceAdapter,
        fannedOut: MutableList<RealtimeEvent>,
        rebaselines: MutableList<Pair<SyncScopeKey, Long>>,
        config: SoleApplyConfig = SoleApplyConfig(),
        logs: MutableList<String> = mutableListOf(),
        beforeFrameOffer: () -> Unit = {},
        onRebaseline: (Long) -> Unit = {},
    ): MessageSoleApplyLane = MessageSoleApplyLane(
        scopeKey = scopeKey,
        adapter = adapter,
        fanOut = { fannedOut += it },
        requestRebaseline = { scope, generation ->
            rebaselines += scope to generation
            onRebaseline(generation)
        },
        laneScope = CoroutineScope(Dispatchers.Unconfined),
        config = config,
        logger = { logs += it },
        beforeFrameOffer = beforeFrameOffer,
    )

    @Test
    fun commitSuccessFansOutInOrder_afterTheOneTxnCommit() {
        val adapter = RecordingAdapter()
        val fannedOut = mutableListOf<RealtimeEvent>()
        val rebaselines = mutableListOf<Pair<SyncScopeKey, Long>>()
        val lane = laneWith(adapter, fannedOut, rebaselines)

        lane.enqueue(frame("m1", 1))
        lane.enqueue(frame("m2", 2))
        lane.enqueue(frame("m3", 3))

        // Commit-first, exact enqueue order, no drop/double.
        assertEquals(
            listOf("m1", "m2", "m3"),
            fannedOut.map { (it as MessageNew).message.id.value },
        )
        assertEquals(3, adapter.applied.size, "every fan-out was preceded by its own one-txn commit")
        assertEquals(
            listOf(1L, 2L, 3L),
            adapter.applied.map { it.cursor.latestSeq },
            "the committed cursor advances with each frame",
        )
        assertTrue(rebaselines.isEmpty(), "healthy commits surface no rebaseline request")
        lane.shutdown()
    }

    @Test
    fun commitThrow_noFanOut_rebaselineCallbackInvoked_recoveryViaRebaseline() {
        val adapter = RecordingAdapter().apply { failNextCommits = 1 }
        val fannedOut = mutableListOf<RealtimeEvent>()
        val rebaselines = mutableListOf<Pair<SyncScopeKey, Long>>()
        val lane = laneWith(adapter, fannedOut, rebaselines)

        lane.enqueue(frame("m1", 1))
        assertEquals(emptyList(), fannedOut, "a failed commit must not fan out")
        assertEquals(listOf(scopeKey), rebaselines.map { it.first }, "the failure surfaces exactly one scope-qualified rebaseline")

        // Post-failure frames stay dark and do not re-request (single-flight, engine-enforced).
        lane.enqueue(frame("m2", 2))
        assertEquals(emptyList(), fannedOut, "no post-failure leak")
        assertEquals(1, rebaselines.size)

        // seq2 arrived after the first recovery request, so the first matching completion cannot
        // prove its snapshot covered seq2; it installs a baseline but rearms one clean flight.
        lane.rebaselineResolved(generation = rebaselines.single().second, success = true)
        assertTrue(lane.currentState().applyBlocked)
        assertEquals(2, rebaselines.size)
        lane.rebaselineResolved(generation = rebaselines.last().second, success = true)
        assertEquals(emptyList(), fannedOut, "superseded frames never surface")
        lane.enqueue(frame("m3", 3))
        assertEquals(listOf("m3"), fannedOut.map { (it as MessageNew).message.id.value })
        assertEquals(listOf(3L), adapter.applied.map { it.cursor.latestSeq })
        assertEquals(
            listOf("m3"),
            adapter.applied.single().facts.map { it.id },
            "failed seq1 and the old polluted core must have no revival path after fresh-core reseed",
        )
        lane.shutdown()
    }

    @Test
    fun capacityOneBlockedCommit_controlsAreNotLost_andOverflowIsCoalescedIntoEngine() = runBlocking {
        val commitGate = CompletableDeferred<Unit>()
        val adapter = RecordingAdapter().apply {
            blockNextCommit = commitGate
            commitEntered = CompletableDeferred()
        }
        val fannedOut = mutableListOf<RealtimeEvent>()
        val rebaselines = mutableListOf<Pair<SyncScopeKey, Long>>()
        val logs = mutableListOf<String>()
        val lane = laneWith(
            adapter = adapter,
            fannedOut = fannedOut,
            rebaselines = rebaselines,
            config = SoleApplyConfig(holdQueueCapacity = 1),
            logs = logs,
        )

        lane.enqueue(frame("m1", 1))
        adapter.commitEntered!!.await()
        lane.enqueue(frame("m2", 2)) // occupies the one-slot frame mailbox
        lane.enqueue(frame("m3", 3)) // overflow signal
        lane.enqueue(frame("m4", 4)) // coalesced into the same signal

        // Both control kinds are queued while apply is suspended. The expected generation is derived
        // from the current barrier; the engine will publish that exact request before consuming this
        // completion, proving the control channel retained ordering and content.
        val expectedGeneration = lane.currentState().generation + 1L
        lane.eligibilityRevoked()
        lane.rebaselineResolved(generation = expectedGeneration, success = true)

        commitGate.complete(Unit)
        repeat(4) { yield() }

        assertEquals(1, rebaselines.size, "overflow/revoke share one engine-owned rebaseline flight")
        assertEquals(expectedGeneration, rebaselines.single().second)
        assertEquals(
            1,
            logs.count { "reason=$SOLE_APPLY_DIAG_MAILBOX_OVERFLOW" in it },
            "many frame offer failures coalesce to one explicit engine transition/telemetry signal",
        )
        assertEquals(SoleApplyMode.LEGACY, lane.currentState().mode, "queued revoke and completion were not lost")
        assertFalse(lane.currentState().rebaselineInFlight)
        assertEquals(
            listOf("m1"),
            fannedOut.map { (it as MessageNew).message.id.value },
            "pre-rebaseline mailbox backlog is snapshot-superseded, not replayed after the barrier",
        )
        assertEquals(listOf("m1"), adapter.durableFacts.map { it.id })
        lane.shutdown()
    }

    @Test
    fun restoreFailureAndStaleOrDuplicateCompletion_neverUnlockOrCreateConcurrentFlight() {
        val adapter = RecordingAdapter().apply {
            failNextCommits = 1
            failNextRestores = 1
        }
        val fannedOut = mutableListOf<RealtimeEvent>()
        val rebaselines = mutableListOf<Pair<SyncScopeKey, Long>>()
        val logs = mutableListOf<String>()
        val lane = laneWith(adapter, fannedOut, rebaselines, logs = logs)

        lane.enqueue(frame("m1", 1))
        val firstGeneration = rebaselines.single().second
        lane.rebaselineResolved(generation = firstGeneration, success = true)

        val retryGeneration = rebaselines.last().second
        assertTrue(retryGeneration > firstGeneration)
        assertEquals(SoleApplyMode.BROKEN_CANONICAL, lane.currentState().mode)
        assertTrue(lane.currentState().applyBlocked)
        assertEquals(retryGeneration, lane.currentState().rebaselineGeneration)
        assertEquals(2, rebaselines.size, "restore failure resolves one flight and rearms exactly one retry")

        // Old completion is stale after the retry token was issued: no restore, unlock, or third request.
        val restoresBeforeStale = adapter.restoreCount
        lane.rebaselineResolved(generation = firstGeneration, success = true)
        assertEquals(restoresBeforeStale, adapter.restoreCount)
        assertEquals(2, rebaselines.size)
        assertTrue(lane.currentState().applyBlocked)

        lane.rebaselineResolved(generation = retryGeneration, success = true)
        assertEquals(SoleApplyMode.FOLD, lane.currentState().mode)
        assertFalse(lane.currentState().applyBlocked)
        assertNull(lane.currentState().rebaselineGeneration)

        // Duplicate success after unlock is also stale and cannot create a second restore/request.
        val restoresAfterSuccess = adapter.restoreCount
        lane.rebaselineResolved(generation = retryGeneration, success = true)
        assertEquals(restoresAfterSuccess, adapter.restoreCount)
        assertEquals(2, rebaselines.size)
        assertTrue(logs.any { "reason=$SOLE_APPLY_DIAG_STALE_REBASELINE_COMPLETION" in it })
        lane.shutdown()
    }

    @Test
    fun postSnapshotCutArrival_rearmsRecoveryUntilANewerSnapshotCoversIt() {
        val adapter = RecordingAdapter().apply { failNextCommits = 1 }
        val fannedOut = mutableListOf<RealtimeEvent>()
        val rebaselines = mutableListOf<Pair<SyncScopeKey, Long>>()
        val lane = laneWith(adapter, fannedOut, rebaselines)

        lane.enqueue(frame("m1", 1))
        val firstGeneration = rebaselines.single().second

        // Host snapshot g2 was cut/persisted at seq1. seq2 arrives after that cut but before the
        // matching completion reaches the actor, so g2 must not unlock.
        adapter.durableFacts = listOf(canonicalRow("m1", 1))
        adapter.durableCursor = PersistedCursor(1, 1, 1, epoch = null)
        lane.enqueue(frame("m2", 2))
        lane.rebaselineResolved(firstGeneration, success = true)

        val retryGeneration = rebaselines.last().second
        assertTrue(retryGeneration > firstGeneration)
        assertEquals(2, rebaselines.size)
        assertTrue(lane.currentState().applyBlocked)
        assertEquals(SoleApplyMode.BROKEN_CANONICAL, lane.currentState().mode)
        assertEquals(emptyList(), fannedOut)

        // The retry snapshot now covers seq2. Only this clean flight unlocks; seq3 then commits the
        // complete canonical state and proves seq2 was not lost between snapshot and completion.
        adapter.durableFacts = listOf(canonicalRow("m1", 1), canonicalRow("m2", 2))
        adapter.durableCursor = PersistedCursor(2, 1, 2, epoch = null)
        lane.rebaselineResolved(retryGeneration, success = true)
        assertEquals(SoleApplyMode.FOLD, lane.currentState().mode)
        assertFalse(lane.currentState().applyBlocked)

        lane.enqueue(frame("m3", 3))
        assertEquals(listOf("m3"), fannedOut.map { (it as MessageNew).message.id.value })
        assertEquals(listOf("m1", "m2", "m3"), adapter.applied.last().facts.map { it.id })
        lane.shutdown()
    }

    @Test
    fun mailboxOverflowDuringRestore_makesTheCurrentCompletionRearm() = runBlocking {
        val restoreGate = CompletableDeferred<Unit>()
        val adapter = RecordingAdapter().apply {
            failNextCommits = 1
            blockNextRestore = restoreGate
            restoreEntered = CompletableDeferred()
        }
        val fannedOut = mutableListOf<RealtimeEvent>()
        val rebaselines = mutableListOf<Pair<SyncScopeKey, Long>>()
        val logs = mutableListOf<String>()
        val lane = laneWith(
            adapter,
            fannedOut,
            rebaselines,
            config = SoleApplyConfig(holdQueueCapacity = 1),
            logs = logs,
        )

        lane.enqueue(frame("m1", 1))
        val firstGeneration = rebaselines.single().second
        adapter.durableFacts = listOf(canonicalRow("m1", 1))
        adapter.durableCursor = PersistedCursor(1, 1, 1, epoch = null)
        lane.rebaselineResolved(firstGeneration, success = true)
        adapter.restoreEntered!!.await()

        lane.enqueue(frame("m2", 2)) // one-slot mailbox
        lane.enqueue(frame("m3", 3)) // overflow while g2 restore is in flight
        restoreGate.complete(Unit)
        repeat(4) { yield() }

        assertEquals(2, rebaselines.size, "g2 completion must rearm g3 after an in-flight overflow")
        assertTrue(rebaselines.last().second > firstGeneration)
        assertTrue(lane.currentState().applyBlocked)
        assertEquals(SoleApplyMode.BROKEN_CANONICAL, lane.currentState().mode)
        assertEquals(emptyList(), fannedOut)
        assertTrue(logs.any { "reason=$SOLE_APPLY_DIAG_REBASELINE_DIRTY_REARMED" in it })

        // The delayed overflow control is attributed to g2 and must be stale after g2 rearms g3.
        // A clean g3 snapshot that covers the lost frames unlocks exactly once — no manufactured g4.
        val retryGeneration = rebaselines.last().second
        adapter.durableFacts = listOf(
            canonicalRow("m1", 1),
            canonicalRow("m2", 2),
            canonicalRow("m3", 3),
        )
        adapter.durableCursor = PersistedCursor(3, 1, 3, epoch = null)
        lane.rebaselineResolved(retryGeneration, success = true)
        repeat(2) { yield() }

        assertEquals(SoleApplyMode.FOLD, lane.currentState().mode)
        assertFalse(lane.currentState().applyBlocked)
        assertEquals(2, rebaselines.size, "the old g2 overflow control must not dirty g3 or create g4")
        assertTrue(logs.any { "reason=stale_mailbox_overflow_control" in it })
        lane.shutdown()
    }

    @Test
    fun recoveryStartingBetweenEnqueueEntryAndOffer_isObservedAtTheOfferCut() = runBlocking {
        val commitGate = CompletableDeferred<Unit>()
        val adapter = RecordingAdapter().apply {
            failNextCommits = 1
            blockNextCommit = commitGate
            commitEntered = CompletableDeferred()
        }
        val secondOfferReached = CountDownLatch(1)
        val releaseSecondOffer = CountDownLatch(1)
        val recoveryRequested = CountDownLatch(1)
        val offerCount = AtomicInteger(0)
        val fannedOut = mutableListOf<RealtimeEvent>()
        val rebaselines = mutableListOf<Pair<SyncScopeKey, Long>>()
        val lane = laneWith(
            adapter = adapter,
            fannedOut = fannedOut,
            rebaselines = rebaselines,
            beforeFrameOffer = {
                if (offerCount.incrementAndGet() == 2) {
                    secondOfferReached.countDown()
                    check(releaseSecondOffer.await(5, TimeUnit.SECONDS))
                }
            },
            onRebaseline = { recoveryRequested.countDown() },
        )

        lane.enqueue(frame("m1", 1))
        adapter.commitEntered!!.await()
        val secondEnqueue = launch(Dispatchers.Default) { lane.enqueue(frame("m2", 2)) }
        assertTrue(secondOfferReached.await(5, TimeUnit.SECONDS))

        // m2 entered enqueue but has not linearized its offer. Start g2 in that interval, then let
        // m2 perform the sample+trySend critical section; it must observe/dirty g2, not use null/g1.
        commitGate.complete(Unit)
        assertTrue(recoveryRequested.await(5, TimeUnit.SECONDS))
        val firstGeneration = rebaselines.single().second
        releaseSecondOffer.countDown()
        secondEnqueue.join()

        adapter.durableFacts = listOf(canonicalRow("m1", 1), canonicalRow("m2", 2))
        adapter.durableCursor = PersistedCursor(2, 1, 2, epoch = null)
        lane.rebaselineResolved(firstGeneration, success = true)

        assertEquals(2, rebaselines.size, "g2 must be dirty and rearm because m2 offered after g2 started")
        assertTrue(lane.currentState().applyBlocked)
        assertEquals(SoleApplyMode.BROKEN_CANONICAL, lane.currentState().mode)
        assertEquals(emptyList(), fannedOut)

        val retryGeneration = rebaselines.last().second
        lane.rebaselineResolved(retryGeneration, success = true)
        assertEquals(2, rebaselines.size, "the dirty g2 offer must rearm exactly one clean g3")
        assertEquals(SoleApplyMode.FOLD, lane.currentState().mode)
        assertFalse(lane.currentState().applyBlocked)
        lane.shutdown()
    }

    @Test
    fun recoveryStartingBeforeFailedOffer_attributesOverflowToThatFlight_only() = runBlocking {
        val commitGate = CompletableDeferred<Unit>()
        val adapter = RecordingAdapter().apply {
            failNextCommits = 1
            blockNextCommit = commitGate
            commitEntered = CompletableDeferred()
        }
        val thirdOfferReached = CountDownLatch(1)
        val releaseThirdOffer = CountDownLatch(1)
        val recoveryRequested = CountDownLatch(1)
        val releaseRecoveryCallback = CountDownLatch(1)
        val retryRequested = CountDownLatch(1)
        val offerCount = AtomicInteger(0)
        val requestCount = AtomicInteger(0)
        val fannedOut = mutableListOf<RealtimeEvent>()
        val rebaselines = mutableListOf<Pair<SyncScopeKey, Long>>()
        val logs = mutableListOf<String>()
        val lane = laneWith(
            adapter = adapter,
            fannedOut = fannedOut,
            rebaselines = rebaselines,
            config = SoleApplyConfig(holdQueueCapacity = 1),
            logs = logs,
            beforeFrameOffer = {
                if (offerCount.incrementAndGet() == 3) {
                    thirdOfferReached.countDown()
                    check(releaseThirdOffer.await(5, TimeUnit.SECONDS))
                }
            },
            onRebaseline = {
                if (requestCount.incrementAndGet() == 1) {
                    recoveryRequested.countDown()
                    check(releaseRecoveryCallback.await(5, TimeUnit.SECONDS))
                } else {
                    retryRequested.countDown()
                }
            },
        )

        lane.enqueue(frame("m1", 1))
        adapter.commitEntered!!.await()
        lane.enqueue(frame("m2", 2)) // fills the one-slot mailbox while m1 commit is suspended
        val thirdEnqueue = launch(Dispatchers.Default) { lane.enqueue(frame("m3", 3)) }
        assertTrue(thirdOfferReached.await(5, TimeUnit.SECONDS))

        // Resume the failed commit on another thread and hold its g2 callback so the frame mailbox
        // remains full. Queue g2 completion first, then let m3 fail its offer while observing g2.
        val commitRelease = launch(Dispatchers.Default) { commitGate.complete(Unit) }
        assertTrue(recoveryRequested.await(5, TimeUnit.SECONDS))
        val firstGeneration = rebaselines.single().second
        adapter.durableFacts = listOf(
            canonicalRow("m1", 1),
            canonicalRow("m2", 2),
            canonicalRow("m3", 3),
        )
        adapter.durableCursor = PersistedCursor(3, 1, 3, epoch = null)
        lane.rebaselineResolved(firstGeneration, success = true)
        releaseThirdOffer.countDown()
        thirdEnqueue.join()
        releaseRecoveryCallback.countDown()
        commitRelease.join()
        assertTrue(retryRequested.await(5, TimeUnit.SECONDS))

        val retryGeneration = rebaselines.last().second
        lane.rebaselineResolved(retryGeneration, success = true)
        repeat(4) { yield() }

        assertEquals(2, rebaselines.size, "g2 overflow must rearm one clean g3 and never manufacture g4")
        assertEquals(SoleApplyMode.FOLD, lane.currentState().mode)
        assertFalse(lane.currentState().applyBlocked)
        assertEquals(emptyList(), fannedOut)
        assertTrue(logs.any { "reason=stale_mailbox_overflow_control" in it })
        lane.shutdown()
    }
}
