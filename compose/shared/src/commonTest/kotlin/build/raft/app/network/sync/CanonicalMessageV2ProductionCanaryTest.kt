package build.raft.app.network.sync

import build.raft.app.canonical.message.CanonicalConversationContextWire
import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.canonical.message.MessageAggregateField
import build.raft.app.data.commitEnterBaselineForTest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The #2991 transport-epoch rules on the task #168 step 3 canary: a window comes only from an enter
 * snapshot (committed under the epoch recorded when it was requested); every realtime frame of another
 * connection makes it display only until the next enter — nothing re-snapshots in the background.
 */
class CanonicalMessageV2ProductionCanaryTest {
    private val partition =
        DurablePartitionKey("server-1", "principal-1", SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID, "env-test")
    private val scope = SyncScopeKey(partition, "channel-1", "channel")

    @Test
    fun flagOffDoesZeroV2Work() = runBlocking {
        val fixture = Fixture(enabled = false)

        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, fixture.accept(fixture.input("m1", 1L)))
        assertEquals(0, fixture.store.snapshotCalls)
        assertEquals(0, fixture.store.incrementalCalls)
        assertEquals(0, fixture.store.reads)
        assertEquals(CanonicalMessageV2ScopeContinuity.NoWindow, fixture.canary.continuityOf(scope))
    }

    @Test
    fun aScopeWithoutAWindowIsDisplayOnlyWithoutTouchingTheStore() = runBlocking {
        val fixture = Fixture(enabled = true)
        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, fixture.accept(fixture.input("m1", 1L)))
        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, fixture.accept(fixture.input("m2", 2L)))
        assertEquals(0, fixture.store.snapshotCalls)
        assertEquals(0, fixture.store.incrementalCalls)
        assertEquals(0, fixture.store.reads, "the display path never waits for a store lookup")
    }

    @Test
    fun anEnteredWindowAppendsTheNextNewIncrementally() = runBlocking {
        val fixture = Fixture(enabled = true)
        fixture.enter(1L)

        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, fixture.accept(fixture.input("m2", 2L)))
        assertEquals(1, fixture.store.snapshotCalls)
        assertEquals(1, fixture.store.incrementalCalls)
        assertEquals(CanonicalMessageV2ScopeContinuity.Continuous, fixture.canary.continuityOf(scope))
    }

    @Test
    fun everyWindowedChannelIsWrittenNotOnlyOne() = runBlocking {
        val fixture = Fixture(enabled = true)
        fixture.enter(1L)
        fixture.enter(1L, channelId = "channel-2")

        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, fixture.accept(fixture.input("m2", 2L)))
        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, fixture.accept(fixture.input("n2", 2L, channelId = "channel-2")))
        assertEquals(2, fixture.store.incrementalCalls)
    }

    @Test
    fun unsupportedProducerMetadataMakesTheWindowDiscontinuous() = runBlocking {
        val fixture = Fixture(enabled = true)
        fixture.enter(1L)
        val unsupported = fixture.input("m2", 2L).copy(
            row = fixture.input("m2", 2L).row.copy(
                actionMetadata = buildJsonObject {
                    put("kind", JsonPrimitive("future-action"))
                    put("payload", JsonPrimitive("opaque"))
                },
            ),
        )

        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, fixture.accept(unsupported))
        assertEquals(0, fixture.store.incrementalCalls)
        assertEquals(CanonicalMessageV2ScopeContinuity.Discontinuous, fixture.canary.continuityOf(scope))
        // The window now misses m2: m3 must not be appended after m1.
        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, fixture.accept(fixture.input("m3", 3L)))
        assertEquals(0, fixture.store.incrementalCalls)
    }

    @Test
    fun flagRevokeClearsTheWindowWithoutANetworkBarrierAndReturnsTheScopeToLegacy() = runBlocking {
        val fixture = Fixture(enabled = true)
        fixture.enter(1L)
        assertEquals(1, fixture.store.restore(scope).envelopes.size)

        fixture.enabled = false
        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, fixture.accept(fixture.input("m2", 2L)))
        assertEquals(emptyList(), fixture.store.restore(scope).envelopes)
        assertEquals(CanonicalMessageV2ScopeContinuity.NoWindow, fixture.canary.continuityOf(scope))
    }

    @Test
    fun aThreadReplyIsNeverWrittenByTheRealtimePath() = runBlocking {
        val fixture = Fixture(enabled = true)
        fixture.enter(1L)
        val threadInput = fixture.input("reply-1", 2L).copy(
            row = fixture.input("reply-1", 2L).row.copy(
                conversationContext = CanonicalConversationContextWire(
                    channelType = "thread",
                    parentChannelId = "channel-1",
                    parentChannelType = "channel",
                    parentMessageId = "root-1",
                ),
            ),
        )

        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, fixture.accept(threadInput))
        assertEquals(0, fixture.store.incrementalCalls)
        assertEquals(CanonicalMessageV2ScopeContinuity.Continuous, fixture.canary.continuityOf(scope))
    }

    @Test
    fun aReconnectMakesTheWindowDisplayOnlyUntilTheNextEnter() = runBlocking {
        val fixture = Fixture(enabled = true)
        fixture.canary.noteTransportEpoch(partition, 1L)
        fixture.enter(1L)
        fixture.accept(fixture.input("m2", 2L))
        assertEquals(1, fixture.store.incrementalCalls)

        // m3 is delivered while the socket is down and never reaches the writer; the socket comes back as epoch 2.
        fixture.canary.noteTransportEpoch(partition, 2L)

        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, fixture.accept(fixture.input("m4", 4L)))
        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, fixture.accept(fixture.input("m5", 5L)))
        assertEquals(1, fixture.store.incrementalCalls, "m4 / m5 must not be appended after m2")
        assertEquals(1, fixture.store.snapshotCalls, "no background re-snapshot (task #168 step 3)")
        assertEquals(CanonicalMessageV2ScopeContinuity.Discontinuous, fixture.canary.continuityOf(scope))

        // The user opens the channel: its enter snapshot (requested under epoch 2) is the new baseline.
        fixture.enter(5L)
        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, fixture.accept(fixture.input("m6", 6L)))
        assertEquals(2, fixture.store.incrementalCalls)
    }

    @Test
    fun withoutAReconnectTheNextFrameStaysIncremental() = runBlocking {
        val fixture = Fixture(enabled = true)
        fixture.canary.noteTransportEpoch(partition, 1L)
        fixture.enter(1L)
        fixture.accept(fixture.input("m2", 2L))
        // A repeated signal for the same epoch is not a reconnect.
        fixture.canary.noteTransportEpoch(partition, 1L)

        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, fixture.accept(fixture.input("m4", 4L)))
        assertEquals(2, fixture.store.incrementalCalls)
    }

    @Test
    fun aRestoredWindowIsDisplayOnlyAfterProcessRestartUntilOpened() = runBlocking {
        val store = FakeStore()
        val beforeRestart = Fixture(enabled = true, store = store)
        beforeRestart.enter(1L)
        beforeRestart.accept(beforeRestart.input("m2", 2L))
        assertEquals(1, store.incrementalCalls)

        // New process: same durable store, fresh canary and engine. Frames may have been missed meanwhile.
        val afterRestart = Fixture(enabled = true, store = store)
        val readsBefore = store.reads
        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, afterRestart.accept(afterRestart.input("m4", 4L)))
        assertEquals(1, store.incrementalCalls, "a restored window is never appended onto")
        assertEquals(readsBefore, store.reads, "and the frame never waited for a store lookup")

        afterRestart.enter(4L)
        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, afterRestart.accept(afterRestart.input("m5", 5L)))
        assertEquals(2, store.incrementalCalls)
    }

    @Test
    fun aReconnectOnAnotherServerOrAccountDoesNotTouchThisWindow() = runBlocking {
        val fixture = Fixture(enabled = true)
        fixture.canary.noteTransportEpoch(partition, 1L)
        fixture.enter(1L)

        fixture.canary.noteTransportEpoch(partition.copy(serverId = "server-2"), 2L)
        fixture.canary.noteTransportEpoch(partition.copy(principalId = "principal-2"), 2L)

        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, fixture.accept(fixture.input("m4", 4L)))
        assertEquals(1, fixture.store.incrementalCalls)
    }

    @Test
    fun aRestartedTransportCountingFromOneIsStillAReconnect() = runBlocking {
        val fixture = Fixture(enabled = true)
        fixture.canary.noteTransportEpoch(partition, 5L)
        fixture.enter(1L)

        // The native transport object was rebuilt (server switch, re-login) and numbers from 1 again.
        fixture.canary.noteTransportEpoch(partition, 1L)

        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, fixture.accept(fixture.input("m4", 4L)))
        assertEquals(0, fixture.store.incrementalCalls, "a smaller recorded epoch is a different connection too")
    }

    @Test
    fun aReconnectDuringTheEnterFetchKeepsTheNewWindowDisplayOnly() = runBlocking {
        val fixture = Fixture(enabled = true)
        fixture.canary.noteTransportEpoch(partition, 1L)
        // The request is sent under epoch 1; the socket reconnects as epoch 2 before the page commits.
        fixture.enter(1L, beforeCommit = { fixture.canary.noteTransportEpoch(partition, 2L) })

        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, fixture.accept(fixture.input("m2", 2L)))
        assertEquals(0, fixture.store.incrementalCalls, "the page predates epoch 2")

        // Twin: no reconnect during the fetch.
        val twin = Fixture(enabled = true)
        twin.canary.noteTransportEpoch(partition, 1L)
        twin.enter(1L)
        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, twin.accept(twin.input("m2", 2L)))
    }

    @Test
    fun aFrameFromTheNewConnectionIsDisplayOnlyEvenWhenItBeatsTheReconnectSignal() = runBlocking {
        val fixture = Fixture(enabled = true)
        fixture.canary.noteTransportEpoch(partition, 1L)
        fixture.enter(1L)
        fixture.accept(fixture.input("m2", 2L), transportEpoch = 1L)
        assertEquals(1, fixture.store.incrementalCalls)

        // The socket came back as epoch 2, but its first frame is processed before the epoch-2 signal.
        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, fixture.accept(fixture.input("m4", 4L), transportEpoch = 2L))
        assertEquals(1, fixture.store.incrementalCalls, "m4 must not be appended after m2")

        fixture.canary.noteTransportEpoch(partition, 2L)
        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, fixture.accept(fixture.input("m5", 5L), transportEpoch = 2L))
        assertEquals(1, fixture.store.incrementalCalls, "still display only: only an enter makes it continuous again")
    }

    @Test
    fun aLateFrameOfAnOlderConnectionIsShownButNeverWrittenAndChangesNothing() = runBlocking {
        val fixture = Fixture(enabled = true)
        fixture.canary.noteTransportEpoch(partition, 2L)
        fixture.enter(104L)

        // m110 left the old socket before it closed; its coroutine only now reaches the writer.
        assertEquals(
            CanonicalMessageV2CanaryOutcome.NotSelected,
            fixture.accept(fixture.input("m110", 110L), transportEpoch = 1L),
            "an older connection's frame goes to the legacy display path only",
        )
        assertEquals(0, fixture.store.incrementalCalls)
        assertEquals(CanonicalMessageV2ScopeContinuity.Continuous, fixture.canary.continuityOf(scope), "no state change")

        // Twin: the same frame under the current connection is appended.
        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, fixture.accept(fixture.input("m110", 110L), transportEpoch = 2L))
        assertEquals(1, fixture.store.incrementalCalls)
    }

    @Test
    fun framesWithoutATransportEpochStillFollowTheReconnectSignal() = runBlocking {
        val fixture = Fixture(enabled = true)
        fixture.canary.noteTransportEpoch(partition, 1L)
        fixture.enter(1L)
        fixture.accept(fixture.input("m2", 2L))
        assertEquals(1, fixture.store.incrementalCalls)

        fixture.canary.noteTransportEpoch(partition, 2L)
        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, fixture.accept(fixture.input("m4", 4L)))
        assertEquals(1, fixture.store.incrementalCalls)
    }

    private inner class Fixture(var enabled: Boolean = true, val store: FakeStore = FakeStore()) {
        private val engine = CanonicalMessageV2GenerationEngine(
            coordinator = CanonicalMessageV2BaselineCoordinator(),
            store = store,
            snapshotCommitObserver = CanonicalMessageV2SnapshotCommitObserver {},
        )
        val canary = CanonicalMessageV2ProductionCanary(
            isEnabledForServer = { this.enabled },
            generationEngine = engine,
        )

        suspend fun accept(
            input: CanonicalMessageFoldInput,
            transportEpoch: Long? = null,
        ): CanonicalMessageV2CanaryOutcome =
            canary.accept(partition = partition, foldInput = input, frameTransportEpoch = transportEpoch)

        /** The user opens the channel: its enter page (one message at [seq]) commits as the baseline. */
        suspend fun enter(seq: Long, channelId: String = "channel-1", beforeCommit: suspend () -> Unit = {}) {
            val target = SyncScopeKey(partition, channelId, "channel")
            val envelope = requireNotNull(input("e$seq", seq, channelId).toCanonicalMessageV2Envelope(partition, replyCount = 0))
            val frame = CanonicalMessageV2SnapshotFrame(target, listOf(envelope), cursor(seq))
            val baseline = requireNotNull(canary.beginEnterBaseline(partition, channelId, nonMember = false))
            beforeCommit()
            engine.withScopeWriter(target) {
                assertEquals(CanonicalMessageV2CommitResult.Committed, engine.applySnapshot(frame))
                canary.onEnterCommitted(baseline, frame)
            }
        }

        fun input(
            id: String,
            seq: Long,
            channelId: String = "channel-1",
            kind: CanonicalMessageFoldKind = CanonicalMessageFoldKind.New,
        ): CanonicalMessageFoldInput =
            CanonicalMessageFoldInput(
                kind = kind,
                row = CanonicalMessageRow(
                    id = id,
                    channelId = channelId,
                    seq = seq,
                    senderType = "user",
                    senderId = "user-1",
                    content = "body-$id",
                    messageType = "chat",
                    createdAt = "2026-07-17T00:00:00.000Z",
                    senderName = "Alice",
                    conversationContext = CanonicalConversationContextWire("channel"),
                ),
                presentAggregates = MessageAggregateField.entries.toList(),
            )
    }

    private class FakeStore : CanonicalMessageV2Store {
        var snapshotCalls = 0
        var incrementalCalls = 0

        /** Every read the canary could make on a frame's display path. */
        var reads = 0
        private val restored = mutableMapOf<SyncScopeKey, RestoredCanonicalMessageV2Scope>()

        override suspend fun applySnapshot(frame: CanonicalMessageV2SnapshotFrame): CanonicalMessageV2StoreWriteResult {
            snapshotCalls += 1
            restored[frame.scope] = RestoredCanonicalMessageV2Scope(frame.envelopes, frame.cursor)
            return CanonicalMessageV2StoreWriteResult()
        }

        override suspend fun applyIncremental(frame: CanonicalMessageV2IncrementalFrame): CanonicalMessageV2StoreWriteResult {
            incrementalCalls += 1
            val previous = restored[frame.scope]?.envelopes.orEmpty().associateBy { it.fact.id }
            restored[frame.scope] = RestoredCanonicalMessageV2Scope(
                envelopes = (previous + frame.envelopes.associateBy { it.fact.id }).values.toList(),
                cursor = frame.cursor,
            )
            return CanonicalMessageV2StoreWriteResult()
        }

        override suspend fun restore(scope: SyncScopeKey): RestoredCanonicalMessageV2Scope {
            reads += 1
            return restored[scope] ?: RestoredCanonicalMessageV2Scope(emptyList(), null)
        }

        override suspend fun baselinedScopeKinds(partition: DurablePartitionKey, scopeId: SyncScopeId): List<String> {
            reads += 1
            return emptyList()
        }

        override suspend fun readCursor(scope: SyncScopeKey): PersistedCursor? {
            reads += 1
            return restored[scope]?.cursor
        }

        override suspend fun containsFact(scope: SyncScopeKey, factId: String): Boolean {
            reads += 1
            return restored[scope]?.envelopes.orEmpty().any { it.fact.id == factId }
        }

        override suspend fun clearScope(scope: SyncScopeKey): Set<SyncScopeKey> {
            restored.remove(scope)
            return setOf(scope)
        }

        override suspend fun clearPartition(partition: DurablePartitionKey) {
            restored.keys.removeAll { it.partition == partition }
        }
    }

    private fun cursor(seq: Long) = PersistedCursor(
        latestSeq = seq,
        oldestLoadedSeq = seq,
        newestLoadedSeq = seq,
        hasLoadedOldest = false,
        hasLoadedNewest = true,
    )
}
