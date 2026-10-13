package build.raft.app.network.sync

import build.raft.app.canonical.message.CanonicalConversationContextWire
import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.canonical.message.MessageAggregateField
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

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

        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, fixture.accept(fixture.input("m1", 1L)))
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
        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, fixture.accept(fixture.input("m1", 1L)))

        val other = fixture.input("m2", 2L, channelId = "channel-2")
        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, fixture.accept(other))
        assertEquals(1, fixture.loaderCalls)
        assertEquals(CanonicalMessageV2CanaryMode.ShadowOnly, fixture.canary.modeFor("server-1", "channel-2"))
    }

    @Test
    fun updateUsesAuthoritativeSnapshotInsteadOfInventingMissingFullV2Fields() = runBlocking {
        val fixture = Fixture(enabled = true)
        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, fixture.accept(fixture.input("m1", 1L)))

        val update = fixture.input("m1", 2L, kind = CanonicalMessageFoldKind.Updated)
        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, fixture.accept(update))
        assertEquals(2, fixture.loaderCalls)
        assertEquals(2, fixture.store.snapshotCalls)
        assertEquals(0, fixture.store.incrementalCalls)
    }

    @Test
    fun unsupportedProducerMetadataCannotEnterIncrementalSoleApply() = runBlocking {
        val fixture = Fixture(enabled = true)
        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, fixture.accept(fixture.input("m1", 1L)))
        fixture.loaderFailure = "snapshot_not_full_v6"
        val unsupported = fixture.input("m2", 2L).copy(
            row = fixture.input("m2", 2L).row.copy(
                actionMetadata = buildJsonObject {
                    put("kind", JsonPrimitive("future-action"))
                    put("payload", JsonPrimitive("opaque"))
                },
            ),
        )

        assertIs<CanonicalMessageV2CanaryOutcome.Held>(fixture.accept(unsupported))
        assertEquals(0, fixture.store.incrementalCalls)
    }

    @Test
    fun failedSnapshotKeepsSelectedScopeDarkAndCanRetry() = runBlocking {
        val fixture = Fixture(enabled = true)
        fixture.loaderFailure = "network_down"

        val held = fixture.accept(fixture.input("m1", 1L))
        assertIs<CanonicalMessageV2CanaryOutcome.Held>(held)
        assertEquals(CanonicalMessageV2CanaryMode.Broken, fixture.canary.modeFor("server-1", "channel-1"))
        assertEquals(0, fixture.store.snapshotCalls)

        fixture.loaderFailure = null
        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, fixture.accept(fixture.input("m1", 1L)))
        assertEquals(2, fixture.loaderCalls)
        assertEquals(1, fixture.store.snapshotCalls)
    }

    @Test
    fun flagRevokeRebaselinesBeforeReturningScopeToLegacy() = runBlocking {
        val fixture = Fixture(enabled = true)
        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, fixture.accept(fixture.input("m1", 1L)))

        fixture.enabled = false
        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, fixture.accept(fixture.input("m1", 2L)))
        assertEquals(2, fixture.loaderCalls)
        assertEquals(2, fixture.store.snapshotCalls)
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
        fixture.accept(fixture.input("m1", 1L))
        fixture.accept(fixture.input("m2", 2L))
        assertEquals(1, fixture.store.incrementalCalls)

        // m3 is delivered while the socket is down and never reaches the writer.
        fixture.canary.markRealtimeDiscontinuity(partition)

        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, fixture.accept(fixture.input("m4", 4L)))
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
        fixture.accept(fixture.input("m1", 1L))
        fixture.accept(fixture.input("m2", 2L))

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
        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, afterRestart.accept(afterRestart.input("m4", 4L)))
        assertEquals(1, afterRestart.loaderCalls, "restore() must not append straight onto the old window")
        assertEquals(1, store.incrementalCalls)
    }

    @Test
    fun aFailedSnapshotAfterReconnectKeepsTheMarkAndNeverFallsBackToAppending() = runBlocking {
        val fixture = Fixture(enabled = true)
        fixture.accept(fixture.input("m1", 1L))
        fixture.accept(fixture.input("m2", 2L))
        fixture.canary.markRealtimeDiscontinuity(partition)

        fixture.loaderFailure = "offline"
        assertIs<CanonicalMessageV2CanaryOutcome.Held>(fixture.accept(fixture.input("m4", 4L)))
        assertEquals(1, fixture.store.incrementalCalls, "a failed snapshot must not degrade into an append")

        fixture.loaderFailure = null
        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, fixture.accept(fixture.input("m5", 5L)))
        assertEquals(3, fixture.loaderCalls, "the next frame retries the snapshot")
        assertEquals(1, fixture.store.incrementalCalls)
    }

    @Test
    fun aReconnectOnAnotherServerOrAccountDoesNotTouchThisWindow() = runBlocking {
        val fixture = Fixture(enabled = true)
        fixture.accept(fixture.input("m1", 1L))
        fixture.accept(fixture.input("m2", 2L))

        fixture.canary.markRealtimeDiscontinuity(partition.copy(serverId = "server-2"))
        fixture.canary.markRealtimeDiscontinuity(partition.copy(principalId = "principal-2"))

        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, fixture.accept(fixture.input("m4", 4L)))
        assertEquals(1, fixture.loaderCalls)
        assertEquals(2, fixture.store.incrementalCalls)
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

        suspend fun accept(input: CanonicalMessageFoldInput): CanonicalMessageV2CanaryOutcome =
            canary.accept(
                partition = partition,
                foldInput = input,
                snapshotLoader = CanonicalMessageV2SnapshotLoader { request ->
                    loaderCalls += 1
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
