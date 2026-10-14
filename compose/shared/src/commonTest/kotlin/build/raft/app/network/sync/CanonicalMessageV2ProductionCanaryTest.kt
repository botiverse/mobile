package build.raft.app.network.sync

import build.raft.app.canonical.message.CanonicalConversationContextWire
import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.canonical.message.MessageAggregateField
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class CanonicalMessageV2ProductionCanaryTest {
    private val partition =
        DurablePartitionKey("server-1", "principal-1", SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID, "env-test")

    @Test
    fun flagOffDoesZeroV2Work() = runBlocking {
        val fixture = Fixture(enabled = false)

        assertEquals(
            CanonicalMessageV2CanaryOutcome.NotSelected,
            fixture.accept(fixture.input("m1", 1L)),
        )
        assertEquals(0, fixture.loaderCalls)
        assertEquals(0, fixture.store.snapshotCalls)
        assertEquals(0, fixture.store.incrementalCalls)
        assertEquals(CanonicalMessageV2CanaryMode.Off, fixture.canary.modeFor("server-1", "channel-1"))
    }

    @Test
    fun firstEventBaselinesThenNextNewEventCommitsIncrementally() = runBlocking {
        val fixture = Fixture(enabled = true)

        // Task #168: the baselining frame never waits for its snapshot; the fixture lets the job finish.
        assertEquals(CanonicalMessageV2CanaryOutcome.Pending, fixture.accept(fixture.input("m1", 1L)))
        assertEquals(1, fixture.loaderCalls)
        assertEquals(1, fixture.store.snapshotCalls)
        assertEquals(0, fixture.store.incrementalCalls)

        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, fixture.accept(fixture.input("m2", 2L)))
        assertEquals(1, fixture.loaderCalls)
        assertEquals(1, fixture.store.snapshotCalls)
        assertEquals(1, fixture.store.incrementalCalls)
        assertEquals(CanonicalMessageV2CanaryMode.SoleApply, fixture.canary.modeFor("server-1", "channel-1"))
    }

    @Test
    fun onlyOneScopeOnAnEnabledServerIsSelected() = runBlocking {
        val fixture = Fixture(enabled = true)
        assertEquals(CanonicalMessageV2CanaryOutcome.Pending, fixture.accept(fixture.input("m1", 1L)))

        val other = fixture.input("m2", 2L, channelId = "channel-2")
        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, fixture.accept(other))
        assertEquals(1, fixture.loaderCalls)
        assertEquals(CanonicalMessageV2CanaryMode.ShadowOnly, fixture.canary.modeFor("server-1", "channel-2"))
    }

    @Test
    fun updateUsesAuthoritativeSnapshotInsteadOfInventingMissingFullV2Fields() = runBlocking {
        val fixture = Fixture(enabled = true)
        assertEquals(CanonicalMessageV2CanaryOutcome.Pending, fixture.accept(fixture.input("m1", 1L)))

        val update = fixture.input("m1", 2L, kind = CanonicalMessageFoldKind.Updated)
        assertEquals(CanonicalMessageV2CanaryOutcome.Pending, fixture.accept(update))
        assertEquals(2, fixture.loaderCalls)
        assertEquals(2, fixture.store.snapshotCalls)
        assertEquals(0, fixture.store.incrementalCalls)
    }

    @Test
    fun unsupportedProducerMetadataCannotEnterIncrementalSoleApply() = runBlocking {
        val fixture = Fixture(enabled = true)
        assertEquals(CanonicalMessageV2CanaryOutcome.Pending, fixture.accept(fixture.input("m1", 1L)))
        fixture.loaderFailure = "snapshot_not_full_v6"
        val unsupported = fixture.input("m2", 2L).copy(
            row = fixture.input("m2", 2L).row.copy(
                actionMetadata = buildJsonObject {
                    put("kind", JsonPrimitive("future-action"))
                    put("payload", JsonPrimitive("opaque"))
                },
            ),
        )

        assertEquals(CanonicalMessageV2CanaryOutcome.Pending, fixture.accept(unsupported))
        assertEquals(0, fixture.store.incrementalCalls)
        assertEquals(CanonicalMessageV2CanaryMode.Broken, fixture.canary.modeFor("server-1", "channel-1"))
    }

    @Test
    fun failedSnapshotWritesNothingAndCanRetry() = runBlocking {
        val fixture = Fixture(enabled = true)
        fixture.loaderFailure = "network_down"

        assertEquals(CanonicalMessageV2CanaryOutcome.Pending, fixture.accept(fixture.input("m1", 1L)))
        assertEquals(CanonicalMessageV2CanaryMode.Broken, fixture.canary.modeFor("server-1", "channel-1"))
        assertEquals(0, fixture.store.snapshotCalls)

        fixture.loaderFailure = null
        assertEquals(CanonicalMessageV2CanaryOutcome.Pending, fixture.accept(fixture.input("m1", 1L)))
        assertEquals(2, fixture.loaderCalls)
        assertEquals(1, fixture.store.snapshotCalls)
    }

    @Test
    fun flagRevokeClearsTheWindowWithoutANetworkBarrierAndReturnsTheScopeToLegacy() = runBlocking {
        val fixture = Fixture(enabled = true)
        assertEquals(CanonicalMessageV2CanaryOutcome.Pending, fixture.accept(fixture.input("m1", 1L)))
        assertEquals(1, fixture.store.restore(SyncScopeKey(partition, "channel-1", "channel")).envelopes.size)

        // Task #168: the revoke never loads (it used to snapshot inline, then clear what it wrote).
        fixture.enabled = false
        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, fixture.accept(fixture.input("m1", 2L)))
        assertEquals(1, fixture.loaderCalls)
        assertEquals(1, fixture.store.snapshotCalls)
        assertEquals(emptyList(), fixture.store.restore(SyncScopeKey(partition, "channel-1", "channel")).envelopes)
        assertEquals(CanonicalMessageV2CanaryMode.Off, fixture.canary.modeFor("server-1", "channel-1"))
    }

    @Test
    fun threadScopeCannotBecomeTheTopLevelCanary() = runBlocking {
        val fixture = Fixture(enabled = true)
        val threadInput = fixture.input("reply-1", 1L).copy(
            row = fixture.input("reply-1", 1L).row.copy(
                conversationContext = CanonicalConversationContextWire(
                    channelType = "thread",
                    parentChannelId = "channel-1",
                    parentChannelType = "channel",
                    parentMessageId = "root-1",
                ),
            ),
        )

        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, fixture.accept(threadInput))
        assertEquals(0, fixture.loaderCalls)
    }

    @Test
    fun reconnectSnapshotsBeforeTheNextFrameInsteadOfAppendingAcrossTheGap() = runBlocking {
        val fixture = Fixture(enabled = true)
        fixture.canary.noteTransportEpoch(partition, 1L)
        fixture.accept(fixture.input("m1", 1L))
        fixture.accept(fixture.input("m2", 2L))
        assertEquals(1, fixture.store.incrementalCalls)

        // m3 is delivered while the socket is down and never reaches the writer; the socket comes back as epoch 2.
        fixture.canary.noteTransportEpoch(partition, 2L)

        assertEquals(CanonicalMessageV2CanaryOutcome.Pending, fixture.accept(fixture.input("m4", 4L)))
        assertEquals(2, fixture.loaderCalls, "the first frame after a reconnect must re-snapshot")
        assertEquals(2, fixture.store.snapshotCalls)
        assertEquals(1, fixture.store.incrementalCalls, "m4 must not be appended after m2")

        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, fixture.accept(fixture.input("m5", 5L)))
        assertEquals(2, fixture.loaderCalls)
        assertEquals(2, fixture.store.incrementalCalls, "after the snapshot the window is continuous again")
    }

    @Test
    fun withoutAReconnectTheNextFrameStaysIncremental() = runBlocking {
        val fixture = Fixture(enabled = true)
        fixture.canary.noteTransportEpoch(partition, 1L)
        fixture.accept(fixture.input("m1", 1L))
        fixture.accept(fixture.input("m2", 2L))
        // A repeated or late signal for the same or an older epoch is not a reconnect.
        fixture.canary.noteTransportEpoch(partition, 1L)

        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, fixture.accept(fixture.input("m4", 4L)))
        assertEquals(1, fixture.loaderCalls)
        assertEquals(2, fixture.store.incrementalCalls)
    }

    @Test
    fun aRestoredWindowSnapshotsBeforeItsFirstFrameAfterProcessRestart() = runBlocking {
        val store = FakeStore()
        val beforeRestart = Fixture(enabled = true, store = store)
        beforeRestart.accept(beforeRestart.input("m1", 1L))
        beforeRestart.accept(beforeRestart.input("m2", 2L))

        // New process: same durable store, fresh canary and engine. Frames may have been missed meanwhile.
        val afterRestart = Fixture(enabled = true, store = store)
        assertEquals(CanonicalMessageV2CanaryOutcome.Pending, afterRestart.accept(afterRestart.input("m4", 4L)))
        assertEquals(1, afterRestart.loaderCalls, "restore() must not append straight onto the old window")
        assertEquals(1, store.incrementalCalls)
    }

    @Test
    fun aFailedSnapshotAfterReconnectKeepsTheMarkAndNeverFallsBackToAppending() = runBlocking {
        val fixture = Fixture(enabled = true)
        fixture.canary.noteTransportEpoch(partition, 1L)
        fixture.accept(fixture.input("m1", 1L))
        fixture.accept(fixture.input("m2", 2L))
        fixture.canary.noteTransportEpoch(partition, 2L)

        fixture.loaderFailure = "offline"
        assertEquals(CanonicalMessageV2CanaryOutcome.Pending, fixture.accept(fixture.input("m4", 4L)))
        assertEquals(CanonicalMessageV2CanaryMode.Broken, fixture.canary.modeFor("server-1", "channel-1"))
        assertEquals(1, fixture.store.incrementalCalls, "a failed snapshot must not degrade into an append")

        fixture.loaderFailure = null
        assertEquals(CanonicalMessageV2CanaryOutcome.Pending, fixture.accept(fixture.input("m5", 5L)))
        assertEquals(3, fixture.loaderCalls, "the next frame retries the snapshot")
        assertEquals(1, fixture.store.incrementalCalls)
    }

    @Test
    fun aReconnectOnAnotherServerOrAccountDoesNotTouchThisWindow() = runBlocking {
        val fixture = Fixture(enabled = true)
        fixture.canary.noteTransportEpoch(partition, 1L)
        fixture.accept(fixture.input("m1", 1L))
        fixture.accept(fixture.input("m2", 2L))

        fixture.canary.noteTransportEpoch(partition.copy(serverId = "server-2"), 2L)
        fixture.canary.noteTransportEpoch(partition.copy(principalId = "principal-2"), 2L)

        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, fixture.accept(fixture.input("m4", 4L)))
        assertEquals(1, fixture.loaderCalls)
        assertEquals(2, fixture.store.incrementalCalls)
    }

    @Test
    fun aRestartedTransportCountingFromOneIsStillAReconnect() = runBlocking {
        val fixture = Fixture(enabled = true)
        fixture.canary.noteTransportEpoch(partition, 5L)
        fixture.accept(fixture.input("m1", 1L))
        fixture.accept(fixture.input("m2", 2L))

        // The native transport object was rebuilt (server switch, re-login) and numbers from 1 again.
        fixture.canary.noteTransportEpoch(partition, 1L)

        assertEquals(CanonicalMessageV2CanaryOutcome.Pending, fixture.accept(fixture.input("m4", 4L)))
        assertEquals(2, fixture.loaderCalls, "a smaller epoch is a different connection too")
        assertEquals(1, fixture.store.incrementalCalls)
    }

    @Test
    fun aReconnectWhileTheSnapshotLoadsForcesAnotherSnapshot() = runBlocking {
        val fixture = Fixture(enabled = true)
        fixture.canary.noteTransportEpoch(partition, 1L)
        // The socket reconnects while the baseline request is in flight: that snapshot predates epoch 2.
        fixture.duringLoad = {
            fixture.duringLoad = null
            fixture.canary.noteTransportEpoch(partition, 2L)
        }
        assertEquals(CanonicalMessageV2CanaryOutcome.Pending, fixture.accept(fixture.input("m1", 1L)))

        assertEquals(CanonicalMessageV2CanaryOutcome.Pending, fixture.accept(fixture.input("m2", 2L)))
        assertEquals(2, fixture.loaderCalls, "the next frame must not append to a snapshot taken before the reconnect")
        assertEquals(0, fixture.store.incrementalCalls)
    }

    @Test
    fun aFrameFromTheNewConnectionSnapshotsEvenWhenItBeatsTheReconnectSignal() = runBlocking {
        val fixture = Fixture(enabled = true)
        fixture.canary.noteTransportEpoch(partition, 1L)
        fixture.accept(fixture.input("m1", 1L), transportEpoch = 1L)
        fixture.accept(fixture.input("m2", 2L), transportEpoch = 1L)
        assertEquals(1, fixture.store.incrementalCalls)

        // The socket came back as epoch 2, but its first frame is processed before the epoch-2 signal.
        assertEquals(
            CanonicalMessageV2CanaryOutcome.Pending,
            fixture.accept(fixture.input("m4", 4L), transportEpoch = 2L),
        )
        assertEquals(2, fixture.loaderCalls, "a frame of a newer connection must re-snapshot without waiting for the signal")
        assertEquals(1, fixture.store.incrementalCalls, "m4 must not be appended after m2")

        // The late signal and the next frame of the same connection: the snapshot already belongs to epoch 2.
        fixture.canary.noteTransportEpoch(partition, 2L)
        assertEquals(
            CanonicalMessageV2CanaryOutcome.Committed,
            fixture.accept(fixture.input("m5", 5L), transportEpoch = 2L),
        )
        assertEquals(2, fixture.loaderCalls)
        assertEquals(2, fixture.store.incrementalCalls)
    }

    @Test
    fun aLateFrameOfAnOlderConnectionIsShownButNeverWrittenToTheNewWindow() = runBlocking {
        val fixture = Fixture(enabled = true)
        fixture.canary.noteTransportEpoch(partition, 1L)
        fixture.accept(fixture.input("m1", 1L), transportEpoch = 1L)
        fixture.canary.noteTransportEpoch(partition, 2L)
        fixture.accept(fixture.input("m104", 104L), transportEpoch = 2L)
        assertEquals(2, fixture.loaderCalls)
        assertEquals(0, fixture.store.incrementalCalls)

        // m110 left the old socket before it closed (105-109 were lost with it); its coroutine only now
        // reaches the writer. Appending it after m104 would leave a hole.
        assertEquals(
            CanonicalMessageV2CanaryOutcome.NotSelected,
            fixture.accept(fixture.input("m110", 110L), transportEpoch = 1L),
            "an older connection's frame goes to the legacy display path only",
        )
        assertEquals(2, fixture.loaderCalls)
        assertEquals(0, fixture.store.incrementalCalls)
        assertEquals(2, fixture.store.snapshotCalls)
    }

    @Test
    fun theSameFrameAppendsUnderTheCurrentConnection() = runBlocking {
        val fixture = Fixture(enabled = true)
        fixture.canary.noteTransportEpoch(partition, 1L)
        fixture.accept(fixture.input("m1", 1L), transportEpoch = 1L)
        fixture.canary.noteTransportEpoch(partition, 2L)
        fixture.accept(fixture.input("m104", 104L), transportEpoch = 2L)

        assertEquals(
            CanonicalMessageV2CanaryOutcome.Committed,
            fixture.accept(fixture.input("m110", 110L), transportEpoch = 2L),
        )
        assertEquals(2, fixture.loaderCalls)
        assertEquals(1, fixture.store.incrementalCalls)
    }

    @Test
    fun framesWithoutATransportEpochStillFollowTheReconnectSignal() = runBlocking {
        val fixture = Fixture(enabled = true)
        fixture.canary.noteTransportEpoch(partition, 1L)
        fixture.accept(fixture.input("m1", 1L), transportEpoch = 1L)
        fixture.accept(fixture.input("m2", 2L))
        assertEquals(1, fixture.store.incrementalCalls)

        fixture.canary.noteTransportEpoch(partition, 2L)
        fixture.accept(fixture.input("m4", 4L))
        assertEquals(2, fixture.loaderCalls)
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
        var loaderCalls = 0
        var loaderFailure: String? = null
        var duringLoad: (suspend () -> Unit)? = null

        /**
         * Returns what [CanonicalMessageV2ProductionCanary.accept] returned, after any background snapshot it
         * started has finished (task #168: accept itself never waits for one).
         */
        suspend fun accept(
            input: CanonicalMessageFoldInput,
            transportEpoch: Long? = null,
        ): CanonicalMessageV2CanaryOutcome =
            acceptWithoutWaiting(input, transportEpoch).also { canary.awaitSnapshotJobs() }

        suspend fun acceptWithoutWaiting(
            input: CanonicalMessageFoldInput,
            transportEpoch: Long?,
        ): CanonicalMessageV2CanaryOutcome =
            canary.accept(
                partition = partition,
                foldInput = input,
                frameTransportEpoch = transportEpoch,
                snapshotLoader = CanonicalMessageV2SnapshotLoader { request ->
                    loaderCalls += 1
                    duringLoad?.invoke()
                    loaderFailure?.let { return@CanonicalMessageV2SnapshotLoader CanonicalMessageV2SnapshotLoadResult.Unavailable(it) }
                    val context = CanonicalConversationContextWire(channelType = request.scope.scopeKind)
                    val envelope = input.toCanonicalMessageV2Envelope(
                        partition = partition,
                        replyCount = 0,
                        producerContextOverride = context,
                    ) ?: error("fixture input must normalize")
                    CanonicalMessageV2SnapshotLoadResult.Loaded(
                        CanonicalMessageV2SnapshotFrame(
                            scope = request.scope,
                            envelopes = listOf(envelope),
                            cursor = cursor(envelope.fact.seq),
                        ),
                    )
                },
            )

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

        override suspend fun restore(scope: SyncScopeKey): RestoredCanonicalMessageV2Scope =
            restored[scope] ?: RestoredCanonicalMessageV2Scope(emptyList(), null)

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
