package build.raft.app.network.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.canonical.message.CanonicalConversationContextWire
import build.raft.app.data.SlockSqlDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class CanonicalMessageV2GreenStoreTest {
    @Test
    fun strictCodecRoundTripsIndependentGoldenBytes() {
        listOf("canonical-message-v2.minimal.json", "canonical-message-v2.maximal.json").forEach { name ->
            val bytes = CanonicalMessageV2RedOracle.fixture(javaClass, name)
            val accepted = assertIs<CanonicalMessageV2DecodeResult.Accepted>(
                decodeCanonicalMessageV2(bytes, "server-a"),
            )
            assertEquals(bytes, encodeCanonicalMessageV2(accepted.envelope, "server-a"), name)
        }
    }

    @Test
    fun realSqlOverlayStoreUsesOneMonotonicGateAndFullKey() = withFreshDatabase { database ->
        val store = SqlDelightViewerOverlayStore(database)
        val key = overlayKey()
        runBlocking {
            assertEquals(ViewerOverlayWriteResult.Applied(null), store.compareAndWrite(key, overlay(4, "+1")))
            assertEquals(
                ViewerOverlayWriteResult.VersionRegressionNoOp(4),
                store.compareAndWrite(key, overlay(3, "heart")),
            )
            assertEquals(ViewerOverlayWriteResult.Idempotent, store.compareAndWrite(key, overlay(4, "+1")))
            assertFailsWith<ViewerOverlayVersionConflict> { store.compareAndWrite(key, overlay(4, "heart")) }
            assertEquals(ViewerOverlayWriteResult.Applied(4), store.compareAndWrite(key, overlay(5, "heart")))
            assertEquals(overlay(5, "heart"), store.read(key))
            assertNull(store.read(key.copy(partition = DurablePartitionKey("server-a", "principal-b", key.partition.consumerId, "env-test"))))
            assertNull(store.read(key.copy(overlayKind = "future-overlay")))
        }
    }

    @Test
    fun v2SnapshotRestoreIsAtomicStrictAndNeverReadsV1Partition() = withFreshDatabase { database ->
        val minimalBytes = CanonicalMessageV2RedOracle.fixture(javaClass, "canonical-message-v2.minimal.json")
        val minimal = assertIs<CanonicalMessageV2DecodeResult.Accepted>(
            decodeCanonicalMessageV2(minimalBytes, "server-a"),
        ).envelope
        val scope = v2Scope()
        val store = SqlDelightCanonicalMessageV2Store(database = database, now = { 123L })
        val cursor = PersistedCursor(1, 1, 1, hasLoadedOldest = true, epoch = "epoch-v2")

        runBlocking {
            store.applySnapshot(CanonicalMessageV2SnapshotFrame(scope, listOf(minimal), cursor))
            database.queries.insertCanonicalFactIfAbsent(
                environmentId = "env-test",
                serverId = "server-a",
                principalId = "principal-a",
                consumerId = SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID,
                id = "legacy-v1",
                channelId = "channel-a",
                scopeKind = "channel",
                seq = 99,
                createdAt = "legacy",
                canonicalFactJson = "not-v2-and-never-read",
            )
            assertEquals(RestoredCanonicalMessageV2Scope(listOf(minimal), cursor), store.restore(scope))

            database.queries.updateCanonicalFact(
                seq = 1,
                createdAt = minimal.fact.createdAt,
                canonicalFactJson = "malformed",
                id = minimal.fact.id,
                environmentId = scope.environmentId,
                serverId = scope.serverId,
                principalId = scope.principalId,
                consumerId = scope.consumerId,
                scopeKind = scope.scopeKind,
                channelId = scope.scopeId,
            )
            assertFailsWith<CanonicalMessageV2ScopePoisoned> { store.restore(scope) }
        }
    }

    @Test
    fun v2RebaselineCrashBeforeAndAfterRestoresOnlyCompleteStates() = withFreshDatabase { database ->
        val bytes = CanonicalMessageV2RedOracle.fixture(javaClass, "canonical-message-v2.minimal.json")
        val oldEnvelope = assertIs<CanonicalMessageV2DecodeResult.Accepted>(
            decodeCanonicalMessageV2(bytes, "server-a"),
        ).envelope
        val newEnvelope = oldEnvelope.copy(fact = oldEnvelope.fact.copy(content = "new-complete", seq = 2))
        val scope = v2Scope()
        val oldCursor = PersistedCursor(1, 1, 1, epoch = "old")
        val newCursor = PersistedCursor(2, 2, 2, epoch = "new")
        val normal = SqlDelightCanonicalMessageV2Store(database = database, now = { 1L })

        runBlocking {
            normal.applySnapshot(CanonicalMessageV2SnapshotFrame(scope, listOf(oldEnvelope), oldCursor))
            val beforeKill = SqlDelightCanonicalMessageV2Store(
                database = database,
                now = { 2L },
                faultHook = PersistFaultHook { it == PersistFaultPoint.BeforeCommit },
            )
            assertFailsWith<PersistKilled> {
                beforeKill.applySnapshot(CanonicalMessageV2SnapshotFrame(scope, listOf(newEnvelope), newCursor))
            }
            assertEquals(RestoredCanonicalMessageV2Scope(listOf(oldEnvelope), oldCursor), normal.restore(scope))

            val afterKill = SqlDelightCanonicalMessageV2Store(
                database = database,
                now = { 3L },
                faultHook = PersistFaultHook { it == PersistFaultPoint.AfterCommit },
            )
            assertFailsWith<PersistKilled> {
                afterKill.applySnapshot(CanonicalMessageV2SnapshotFrame(scope, listOf(newEnvelope), newCursor))
            }
            assertEquals(RestoredCanonicalMessageV2Scope(listOf(newEnvelope), newCursor), normal.restore(scope))
        }
    }

    @Test
    fun producerFiveStateContextUnionNormalizesLosslessly() {
        listOf("channel", "private", "dm", "joint").forEach { kind ->
            val normalized = assertIs<CanonicalMessageV2NormalizationResult.Normalized>(
                normalizeCanonicalMessageV2(
                    CanonicalMessageV2NormalizationInput(
                        partition = v2Partition(),
                        scopeId = "$kind-a",
                        fact = probeFact(),
                        producerContext = CanonicalConversationContextWire(kind),
                        childReplyScopeId = "child-$kind",
                    ),
                ),
            ).envelope
            val context = assertIs<CanonicalConversationContextV2.TopLevel>(normalized.context)
            assertEquals("$kind-a", context.scope.scopeId)
            assertEquals("child-$kind", normalized.childReplyScope?.scopeId)
            assertNull(normalized.replyMembership)
        }

        val thread = assertIs<CanonicalMessageV2NormalizationResult.Normalized>(
            normalizeCanonicalMessageV2(
                CanonicalMessageV2NormalizationInput(
                    partition = v2Partition(),
                    scopeId = "thread-root",
                    fact = probeFact(),
                    producerContext = CanonicalConversationContextWire(
                        channelType = "thread",
                        parentChannelId = "dm-a",
                        parentChannelType = "dm",
                        parentMessageId = "message-root",
                    ),
                ),
            ),
        ).envelope
        val context = assertIs<CanonicalConversationContextV2.Thread>(thread.context)
        assertEquals("thread-root", context.replyScope.scopeId)
        assertEquals("dm-a", context.parentTopLevelScope.scopeId)
        assertEquals("message-root", context.rootMessageRef.messageId)
        assertEquals(context.replyScope, thread.replyMembership?.parentScope)
        assertEquals(context.rootMessageRef, thread.replyMembership?.root)
    }

    @Test
    fun generationEngineHoldsPrebaselineIncrementalsAndRequestsOneSingleFlightSnapshot() = runBlocking {
        val scope = v2Scope()
        val fake = RecordingV2Store()
        val cache = AttachmentCommentsReadCacheV2()
        val engine =
            CanonicalMessageV2GenerationEngine(
                CanonicalMessageV2BaselineCoordinator(),
                fake,
                cache::invalidateParent,
            )
        val incremental = CanonicalMessageV2IncrementalFrame(scope, emptyList(), PersistedCursor(1, 1, 1))

        assertEquals(CanonicalMessageV2CommitResult.BaselineRequired(true), engine.applyIncremental(incremental))
        assertEquals(CanonicalMessageV2CommitResult.BaselineRequired(false), engine.applyIncremental(incremental))
        assertEquals(0, fake.incrementalWrites)

        assertEquals(
            CanonicalMessageV2CommitResult.Committed,
            engine.applySnapshot(CanonicalMessageV2SnapshotFrame(scope, emptyList(), PersistedCursor(0, 0, 0))),
        )
        assertEquals(CanonicalMessageV2CommitResult.Committed, engine.applyIncremental(incremental))
        assertEquals(1, fake.snapshotWrites)
        assertEquals(1, fake.incrementalWrites)
    }

    @Test
    fun concurrentFirstIncrementalsRequestExactlyOneRebaseline() = runBlocking {
        val coordinator = CanonicalMessageV2BaselineCoordinator()
        val scope = v2Scope()

        val decisions = coroutineScope {
            List(64) {
                async(Dispatchers.Default) { coordinator.onIncremental(scope) }
            }.awaitAll()
        }

        assertEquals(1, decisions.count { (it as CanonicalMessageV2IncrementalDecision.Hold).requestRebaseline })
        assertEquals(63, decisions.count { !(it as CanonicalMessageV2IncrementalDecision.Hold).requestRebaseline })
    }

    @Test
    fun successfulSnapshotInvalidatesOnlyExactParentAttachmentCommentsCache(): Unit = runBlocking {
        val partition = v2Partition()
        val scopeA = SyncScopeKey(partition, "channel-a", "channel")
        val scopeB = SyncScopeKey(partition, "channel-b", "channel")
        val keyA = AttachmentCommentsReadKeyV2(partition, "channel", "channel-a", "message-a", "attachment-a")
        val keyB = AttachmentCommentsReadKeyV2(partition, "channel", "channel-b", "message-b", "attachment-b")
        val cache = AttachmentCommentsReadCacheV2().also {
            it.put(keyA, scopeA, listOf("comment-a"))
            it.put(keyB, scopeB, listOf("comment-b"))
        }
        val engine =
            CanonicalMessageV2GenerationEngine(
                CanonicalMessageV2BaselineCoordinator(),
                RecordingV2Store(),
                cache::invalidateParent,
            )

        engine.applySnapshot(
            CanonicalMessageV2SnapshotFrame(
                scope = scopeA,
                envelopes = emptyList(),
                cursor = PersistedCursor(0, 0, 0, epoch = "snapshot-a"),
            ),
        )

        assertEquals(AttachmentCommentsCacheLookupV2.Unknown, cache.get(keyA))
        assertIs<AttachmentCommentsCacheLookupV2.Loaded>(cache.get(keyB))
        Unit
    }

    private fun withFreshDatabase(block: (SlockSqlDatabase) -> Unit) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            SlockSqlDatabase.Schema.create(driver).value
            block(SlockSqlDatabase(driver))
        } finally {
            driver.close()
        }
    }

    private fun v2Partition() = DurablePartitionKey("server-a", "principal-a", SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID, "env-test")
    private fun v2Scope() = SyncScopeKey(v2Partition(), "channel-a", "channel")
    private fun overlayKey() = ViewerOverlayKey(v2Partition(), "channel", "channel-a", "message", "message-a", "reactions")
    private fun overlay(version: Long, emoji: String) = VersionedViewerOverlayV1(
        version = version,
        completeness = OverlayCompleteness.Complete,
        payload = ViewerOverlayPayloadV1.Reactions(listOf(emoji)),
    )

    private fun probeFact() = CanonicalMessageNodeV2(
        id = "message-probe",
        seq = 1,
        senderType = "human",
        senderId = "human-a",
        content = "probe",
        messageType = "chat",
        createdAt = "2026-07-16T00:00:00.000Z",
        senderName = "Human",
    )

    private class RecordingV2Store : CanonicalMessageV2Store {
        var snapshotWrites: Int = 0
        var incrementalWrites: Int = 0

        override suspend fun applySnapshot(frame: CanonicalMessageV2SnapshotFrame): CanonicalMessageV2StoreWriteResult {
            snapshotWrites += 1
            return CanonicalMessageV2StoreWriteResult()
        }

        override suspend fun applyIncremental(frame: CanonicalMessageV2IncrementalFrame): CanonicalMessageV2StoreWriteResult {
            incrementalWrites += 1
            return CanonicalMessageV2StoreWriteResult()
        }

        override suspend fun restore(scope: SyncScopeKey): RestoredCanonicalMessageV2Scope =
            RestoredCanonicalMessageV2Scope(emptyList(), null)

        override suspend fun clearScope(scope: SyncScopeKey): Set<SyncScopeKey> = setOf(scope)

        override suspend fun clearPartition(partition: DurablePartitionKey) = Unit
    }
}
