package build.raft.app.network.sync

import build.raft.app.canonical.message.CanonicalMessageCarry
import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.canonical.message.MessageAggregateField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Frozen T3 pure RED matrix. Production remains a dormant seam until these schedules are reviewed. */
class CurrentGrantMessageShadowCommitLaneRedTest {

    private fun partition(
        server: String = "server-1",
        principal: String = "principal-1",
        consumer: String = SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID,
    ) = DurablePartitionKey(server, principal, consumer)

    private fun scope(partition: DurablePartitionKey = partition(), channel: String = "channel-1") =
        SyncScopeKey(partition = partition, scopeId = channel, scopeKind = "channel")

    private fun row(
        id: String = "m1",
        channel: String = "channel-1",
        seq: Long = 1L,
        content: String = "body-$id",
    ) = CanonicalMessageRow(
        id = id,
        channelId = channel,
        seq = seq,
        senderType = "human",
        senderId = "u1",
        content = content,
        messageType = "chat",
        createdAt = "2026-07-15T00:00:${seq.toString().padStart(2, '0')}Z",
        senderName = "Alice",
    )

    private fun input(
        kind: CanonicalMessageFoldKind,
        row: CanonicalMessageRow,
        aggregates: Set<MessageAggregateField> = emptySet(),
    ) = CanonicalMessageFoldInput.from(
        kind,
        CanonicalMessageCarry(row = row, presentAggregates = aggregates, taskFields = null),
    )

    private fun cursor(latest: Long, epoch: String? = null) =
        PersistedCursor(
            latestSeq = latest,
            oldestLoadedSeq = latest,
            newestLoadedSeq = latest,
            hasLoadedOldest = false,
            hasLoadedNewest = true,
            epoch = epoch,
        )

    private class FakeDurableStore {
        val snapshots = mutableMapOf<SyncScopeKey, CommittedMessageScopeSnapshot>()
        var writeInvocations = 0
        var commits = 0
        var rollbacks = 0
        var reads = 0
        var corruption: StrictMessageRestoreCorruption? = null
        var readFailure: Throwable? = null
        var beforeWrite: (suspend () -> Unit)? = null
        var beforeCommitFailure: Throwable? = null
        var afterCommitFailure: Throwable? = null

        fun snapshot(scope: SyncScopeKey): CommittedMessageScopeSnapshot =
            snapshots[scope] ?: CommittedMessageScopeSnapshot(scope, emptyList(), null)

        val reader = StrictMessageScopeSnapshotReader { scope ->
            reads += 1
            readFailure?.let { throw it }
            corruption?.let { return@StrictMessageScopeSnapshotReader StrictMessageRestoreResult.Corrupt(it) }
            StrictMessageRestoreResult.Valid(snapshot(scope))
        }

        val adapter = object : FoldPersistenceAdapter {
            override suspend fun applyFrame(frame: FoldPersistFrame) {
                writeInvocations += 1
                beforeWrite?.invoke()
                beforeCommitFailure?.let {
                    beforeCommitFailure = null
                    rollbacks += 1
                    throw it
                }
                snapshots[frame.scope] = CommittedMessageScopeSnapshot(frame.scope, frame.facts, frame.cursor)
                commits += 1
                afterCommitFailure?.let {
                    afterCommitFailure = null
                    throw it
                }
            }

            override suspend fun restore(scope: SyncScopeKey): RestoredFoldState =
                snapshot(scope).let { RestoredFoldState(it.facts, it.cursor) }
        }
    }

    private fun lane(
        store: FakeDurableStore,
        beforePublishedSwap: () -> Unit = {},
        enabled: (String) -> Boolean = { true },
    ) = CurrentGrantMessageShadowCommitLane(
        strictReader = store.reader,
        persistence = store.adapter,
        isEnabledForServer = enabled,
        faultHooks = CurrentGrantMessageShadowFaultHooks(beforePublishedSwap = beforePublishedSwap),
    )

    private fun seedReady(
        lane: CurrentGrantMessageShadowCommitLane,
        snapshot: CommittedMessageScopeSnapshot,
    ) {
        lane.workingByScope[snapshot.scope] =
            MessageShadowWorkingCell(state = MessageShadowWorkingState.Ready(snapshot))
        lane.published.replace(PublishedMessageShadowState(snapshotsByScope = mapOf(snapshot.scope to snapshot)))
    }

    private fun published(
        lane: CurrentGrantMessageShadowCommitLane,
        scope: SyncScopeKey,
    ): CommittedMessageScopeSnapshot? = lane.published.value().snapshotsByScope[scope]

    @Test
    fun a1ChangedFrameCommitsThenPublishesOneExactPartitionSnapshot() = runBlocking {
        val store = FakeDurableStore()
        val lane = lane(store)
        val scope = scope()
        seedReady(lane, store.snapshot(scope))

        val outcome = lane.commit(
            partition = scope.partition,
            legacyKey = LegacyRealtimeDedupKey("message:new:server-1:m1:1"),
            foldInput = input(CanonicalMessageFoldKind.New, row()),
        )

        assertEquals(CurrentGrantMessageShadowCommitOutcome.Committed, outcome)
        assertEquals(1, store.writeInvocations)
        assertEquals(1, store.commits)
        assertEquals(1, store.reads, "post-COMMIT readback is one consistent read snapshot")
        assertEquals(listOf("m1"), published(lane, scope)?.facts?.map { it.id })
    }

    @Test
    fun p1SameChannelIsolatedByPrincipal() = runBlocking {
        val store = FakeDurableStore()
        val lane = lane(store)
        val p1 = partition(principal = "p1")
        val p2 = partition(principal = "p2")
        seedReady(lane, store.snapshot(scope(p1)))
        seedReady(lane, store.snapshot(scope(p2)))

        lane.commit(p1, LegacyRealtimeDedupKey("message:new:server-1:m1:1"), input(CanonicalMessageFoldKind.New, row()))
        lane.commit(p2, LegacyRealtimeDedupKey("message:new:server-1:m1:1"), input(CanonicalMessageFoldKind.New, row()))

        assertEquals(2, store.commits)
        assertEquals(listOf("m1"), published(lane, scope(p1))?.facts?.map { it.id })
        assertEquals(listOf("m1"), published(lane, scope(p2))?.facts?.map { it.id })
        assertEquals(2, lane.published.value().snapshotsByScope.size)
    }

    @Test
    fun p2ServerFlagAndStateArePartitioned() = runBlocking {
        val store = FakeDurableStore()
        val lane = lane(store, enabled = { server -> server == "server-2" })
        val s1 = partition(server = "server-1")
        val s2 = partition(server = "server-2")
        seedReady(lane, store.snapshot(scope(s1)))
        seedReady(lane, store.snapshot(scope(s2)))

        val off = lane.commit(s1, LegacyRealtimeDedupKey("message:new:server-1:m1:1"), input(CanonicalMessageFoldKind.New, row()))
        val on = lane.commit(s2, LegacyRealtimeDedupKey("message:new:server-2:m1:1"), input(CanonicalMessageFoldKind.New, row()))

        assertEquals(CurrentGrantMessageShadowCommitOutcome.Disabled, off)
        assertEquals(CurrentGrantMessageShadowCommitOutcome.Committed, on)
        assertEquals(1, store.commits)
        assertTrue(published(lane, scope(s1))?.facts.orEmpty().isEmpty())
        assertEquals(listOf("m1"), published(lane, scope(s2))?.facts?.map { it.id })
    }

    @Test
    fun p3SameScopeIsolatedByConsumer() = runBlocking {
        val store = FakeDurableStore()
        val lane = lane(store)
        val c1 = partition(consumer = "consumer-1")
        val c2 = partition(consumer = "consumer-2")
        seedReady(lane, store.snapshot(scope(c1)))
        seedReady(lane, store.snapshot(scope(c2)))

        lane.commit(c1, LegacyRealtimeDedupKey("message:new:server-1:m1:1"), input(CanonicalMessageFoldKind.New, row()))
        lane.commit(c2, LegacyRealtimeDedupKey("message:new:server-1:m1:1"), input(CanonicalMessageFoldKind.New, row()))

        assertEquals(2, store.commits)
        assertEquals(2, lane.published.value().snapshotsByScope.size)
    }

    @Test
    fun r1RollbackKeepsPublishedOldAndSameRawReplayRepairs() = runBlocking {
        val store = FakeDurableStore().also { it.beforeCommitFailure = IllegalStateException("rollback") }
        val lane = lane(store)
        val scope = scope()
        val old = store.snapshot(scope)
        seedReady(lane, old)
        val key = LegacyRealtimeDedupKey("message:new:server-1:m1:1")
        val input = input(CanonicalMessageFoldKind.New, row())

        val failed = lane.commit(scope.partition, key, input)
        assertIs<CurrentGrantMessageShadowCommitOutcome.FailedOpen>(failed)
        assertEquals(old, published(lane, scope))

        val repaired = lane.commit(scope.partition, key, input)
        assertEquals(CurrentGrantMessageShadowCommitOutcome.Committed, repaired)
        assertEquals(2, store.writeInvocations)
        assertEquals(1, store.rollbacks)
        assertEquals(1, store.commits)
        assertEquals(2, store.reads, "restore before retry plus post-COMMIT readback")
        assertEquals(listOf("m1"), published(lane, scope)?.facts?.map { it.id })
    }

    @Test
    fun f1aCommittedPreSwapFaultRepairsWithRestoreOnlyAndNoSecondWrite() = runBlocking {
        val store = FakeDurableStore()
        var failSwap = true
        val lane = lane(store, beforePublishedSwap = {
            if (failSwap) {
                failSwap = false
                throw IllegalStateException("pre-swap")
            }
        })
        val scope = scope()
        seedReady(lane, store.snapshot(scope))
        val key = LegacyRealtimeDedupKey("message:new:server-1:m1:1")
        val input = input(CanonicalMessageFoldKind.New, row())

        assertIs<CurrentGrantMessageShadowCommitOutcome.FailedOpen>(lane.commit(scope.partition, key, input))
        assertTrue(published(lane, scope)?.facts.orEmpty().isEmpty())
        assertEquals(CurrentGrantMessageShadowCommitOutcome.DurableNoChange, lane.commit(scope.partition, key, input))
        assertEquals(1, store.writeInvocations)
        assertEquals(1, store.commits)
        assertEquals(2, store.reads)
        assertEquals(listOf("m1"), published(lane, scope)?.facts?.map { it.id })
    }

    @Test
    fun f1bReopenAfterCommitBeforePublishUsesOneLazyRestoreAndZeroExtraWrite() = runBlocking {
        val store = FakeDurableStore().also { it.afterCommitFailure = IllegalStateException("commit-return-lost") }
        val firstLane = lane(store)
        val scope = scope()
        seedReady(firstLane, store.snapshot(scope))
        val key = LegacyRealtimeDedupKey("message:new:server-1:m1:1")
        val input = input(CanonicalMessageFoldKind.New, row())

        assertIs<CurrentGrantMessageShadowCommitOutcome.FailedOpen>(firstLane.commit(scope.partition, key, input))
        assertEquals(1, store.commits)
        assertTrue(published(firstLane, scope)?.facts.orEmpty().isEmpty())

        val reopened = lane(store)
        val outcome = reopened.commit(scope.partition, key, input)
        assertEquals(CurrentGrantMessageShadowCommitOutcome.DurableNoChange, outcome)
        assertEquals(1, store.writeInvocations)
        assertEquals(1, store.reads)
        assertEquals(listOf("m1"), published(reopened, scope)?.facts?.map { it.id })
    }

    @Test
    fun s1CandidateIsInvisibleUntilCommitAndRollbackLeavesOldSnapshot() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = FakeDurableStore().also {
            it.beforeWrite = {
                entered.complete(Unit)
                release.await()
            }
            it.beforeCommitFailure = IllegalStateException("rollback")
        }
        val lane = lane(store)
        val scope = scope()
        val old = store.snapshot(scope)
        seedReady(lane, old)

        val job = launch {
            lane.commit(
                scope.partition,
                LegacyRealtimeDedupKey("message:new:server-1:m1:1"),
                input(CanonicalMessageFoldKind.New, row()),
            )
        }
        assertTrue(
            withTimeoutOrNull(500L) { entered.await(); true } == true,
            "changed candidate must synchronously enter the write before it can publish",
        )
        assertEquals(old, published(lane, scope), "staged candidate must remain invisible before COMMIT")
        release.complete(Unit)
        job.join()
        assertEquals(old, published(lane, scope), "rollback must leave published committed state unchanged")
    }

    @Test
    fun s2MissingBaseUpdatedDoesNotWriteOrCommitDedupThenAppliesAfterNew() = runBlocking {
        val store = FakeDurableStore()
        val lane = lane(store)
        val scope = scope()
        seedReady(lane, store.snapshot(scope))
        val updateKey = LegacyRealtimeDedupKey("message:updated:server-1:m1:1:")
        val updated = input(CanonicalMessageFoldKind.Updated, row(content = "edited"))

        assertEquals(CurrentGrantMessageShadowCommitOutcome.IgnoredMissingUpdatedBase, lane.commit(scope.partition, updateKey, updated))
        assertEquals(0, store.writeInvocations)
        assertTrue(lane.published.value().committedAccelerationByScope[scope].orEmpty().isEmpty())

        assertEquals(
            CurrentGrantMessageShadowCommitOutcome.Committed,
            lane.commit(
                scope.partition,
                LegacyRealtimeDedupKey("message:new:server-1:m1:1"),
                input(CanonicalMessageFoldKind.New, row(content = "original")),
            ),
        )
        assertEquals(CurrentGrantMessageShadowCommitOutcome.Committed, lane.commit(scope.partition, updateKey, updated))
        assertEquals("edited", published(lane, scope)?.facts?.single()?.content)
        assertEquals(2, store.commits)
    }

    @Test
    fun s3CorruptRestorePoisonsWorkingStateAndNeverPublishesOrWrites() = runBlocking {
        val corruption = StrictMessageRestoreCorruption(StrictMessageRestoreCorruptionKind.FactScalarMismatch, "m1")
        val store = FakeDurableStore().also { it.corruption = corruption }
        val lane = lane(store)
        val scope = scope()

        val outcome = lane.commit(
            scope.partition,
            LegacyRealtimeDedupKey("message:new:server-1:m1:1"),
            input(CanonicalMessageFoldKind.New, row()),
        )

        assertIs<CurrentGrantMessageShadowCommitOutcome.FailedOpen>(outcome)
        assertEquals(MessageShadowWorkingState.Poisoned(corruption), lane.workingByScope[scope]?.state)
        assertEquals(1, store.reads)
        assertEquals(0, store.writeInvocations)
        assertNull(published(lane, scope))
    }

    @Test
    fun s4PreCommitCancellationClearsInFlightRollsBackAndRethrows() = runBlocking {
        val store = FakeDurableStore().also { it.beforeCommitFailure = CancellationException("cancel") }
        val lane = lane(store)
        val scope = scope()
        seedReady(lane, store.snapshot(scope))

        assertFailsWith<CancellationException> {
            lane.commit(
                scope.partition,
                LegacyRealtimeDedupKey("message:new:server-1:m1:1"),
                input(CanonicalMessageFoldKind.New, row()),
            )
        }
        assertEquals(1, store.rollbacks)
        assertNull(lane.workingByScope[scope]?.inFlight)
        assertTrue(published(lane, scope)?.facts.orEmpty().isEmpty())
    }

    @Test
    fun s4PostCommitPreSwapCancellationRestoresOnReplayWithoutSecondWrite() = runBlocking {
        val store = FakeDurableStore()
        var cancelSwap = true
        val lane = lane(store, beforePublishedSwap = {
            if (cancelSwap) {
                cancelSwap = false
                throw CancellationException("cancel-pre-swap")
            }
        })
        val scope = scope()
        seedReady(lane, store.snapshot(scope))
        val key = LegacyRealtimeDedupKey("message:new:server-1:m1:1")
        val input = input(CanonicalMessageFoldKind.New, row())

        assertFailsWith<CancellationException> { lane.commit(scope.partition, key, input) }
        assertEquals(1, store.commits)
        assertTrue(published(lane, scope)?.facts.orEmpty().isEmpty())

        assertEquals(CurrentGrantMessageShadowCommitOutcome.DurableNoChange, lane.commit(scope.partition, key, input))
        assertEquals(1, store.writeInvocations)
        assertEquals(2, store.reads)
        assertEquals(listOf("m1"), published(lane, scope)?.facts?.map { it.id })
    }

    @Test
    fun s5ObservedCollisionIsRejectedBeforeRestoreOrWriteEvenAfterRollback() = runBlocking {
        val store = FakeDurableStore().also { it.beforeCommitFailure = IllegalStateException("rollback") }
        val lane = lane(store)
        val scope = scope()
        seedReady(lane, store.snapshot(scope))
        val key = LegacyRealtimeDedupKey("message:new:server-1:m1:1")

        lane.commit(scope.partition, key, input(CanonicalMessageFoldKind.New, row(content = "first")))
        val readsBeforeCollision = store.reads
        val outcome = lane.commit(scope.partition, key, input(CanonicalMessageFoldKind.New, row(content = "different")))

        assertEquals(CurrentGrantMessageShadowCommitOutcome.LegacyDedupCollision, outcome)
        assertEquals(readsBeforeCollision, store.reads, "observed collision must be decided before restore")
        assertEquals(1, store.writeInvocations)
        assertTrue(published(lane, scope)?.facts.orEmpty().isEmpty())
    }

    @Test
    fun s6UpdatedFrameNeverRewindsDurableCursor() = runBlocking {
        val store = FakeDurableStore()
        val lane = lane(store)
        val scope = scope()
        val base = row(seq = 1, content = "old")
        val snapshot = CommittedMessageScopeSnapshot(scope, listOf(base), cursor(latest = 9, epoch = "epoch-a"))
        store.snapshots[scope] = snapshot
        seedReady(lane, snapshot)

        val outcome = lane.commit(
            scope.partition,
            LegacyRealtimeDedupKey("message:updated:server-1:m1:1:t1"),
            input(CanonicalMessageFoldKind.Updated, base.copy(content = "new")),
        )

        assertEquals(CurrentGrantMessageShadowCommitOutcome.Committed, outcome)
        assertEquals("new", published(lane, scope)?.facts?.single()?.content)
        assertEquals(9L, published(lane, scope)?.cursor?.latestSeq)
        assertEquals("epoch-a", published(lane, scope)?.cursor?.epoch)
    }

    @Test
    fun restoreOnlyMissingBaseMayPublishPreEventSnapshotWithoutWriting() = runBlocking {
        val store = FakeDurableStore()
        val scope = scope()
        val durable = CommittedMessageScopeSnapshot(scope, listOf(row(id = "base", seq = 4)), cursor(4))
        store.snapshots[scope] = durable
        val lane = lane(store)

        val outcome = lane.commit(
            scope.partition,
            LegacyRealtimeDedupKey("message:updated:server-1:missing:5:"),
            input(CanonicalMessageFoldKind.Updated, row(id = "missing", seq = 5, content = "edit")),
        )

        assertEquals(CurrentGrantMessageShadowCommitOutcome.IgnoredMissingUpdatedBase, outcome)
        assertEquals(1, store.reads)
        assertEquals(0, store.writeInvocations)
        assertEquals(durable, published(lane, scope))
        assertTrue(lane.published.value().committedAccelerationByScope[scope].orEmpty().isEmpty())
    }
}
