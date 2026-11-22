package build.raft.app.network.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.canonical.message.CanonicalConversationContextWire
import build.raft.app.data.RaftSqlDatabase
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CanonicalMessageV2DurableOwnerTest {
    @Test
    fun identicalServerPrincipalAndScopeDoNotCrossEnvironments() = withFreshDatabase { database ->
        val production = partition("https://api.raft.build/api")
        val staging = partition("https://api-staging.raft.build/api")
        val productionScope = scope(production, "shared-channel")
        val stagingScope = scope(staging, "shared-channel")
        val store = SqlDelightCanonicalMessageV2Store(database, now = { 100L })

        runBlocking {
            store.applySnapshot(snapshot(productionScope, envelope(production, "shared-channel", "prod-message", 1)))
            store.applySnapshot(snapshot(stagingScope, envelope(staging, "shared-channel", "stage-message", 1)))

            assertEquals(listOf("prod-message"), store.restore(productionScope).envelopes.map { it.fact.id })
            assertEquals(listOf("stage-message"), store.restore(stagingScope).envelopes.map { it.fact.id })
        }
    }

    @Test
    fun clearScopeAndPartitionRemoveFactsCursorAndViewerOverlayOnlyForExactOwner() = withFreshDatabase { database ->
        val production = partition("https://api.raft.build/api")
        val staging = partition("https://api-staging.raft.build/api")
        val channel = scope(production, "channel-a")
        val sibling = scope(production, "channel-b")
        val stagingTwin = scope(staging, "channel-a")
        val store = SqlDelightCanonicalMessageV2Store(database, now = { 100L })

        runBlocking {
            store.applySnapshot(snapshot(channel, envelope(production, "channel-a", "message-a", 1)))
            store.applySnapshot(snapshot(sibling, envelope(production, "channel-b", "message-b", 1)))
            store.applySnapshot(snapshot(stagingTwin, envelope(staging, "channel-a", "stage-message", 1)))
            database.insertOverlayRow(channel, "message-a")
            database.insertOverlayRow(sibling, "message-b")

            store.clearScope(channel)
            assertEquals(RestoredCanonicalMessageV2Scope(emptyList(), null), store.restore(channel))
            assertFalse(database.hasOverlayRow(channel, "message-a"))
            assertEquals(listOf("message-b"), store.restore(sibling).envelopes.map { it.fact.id })
            assertTrue(database.hasOverlayRow(sibling, "message-b"))

            store.clearPartition(production)
            assertEquals(RestoredCanonicalMessageV2Scope(emptyList(), null), store.restore(sibling))
            assertFalse(database.hasOverlayRow(sibling, "message-b"))
            assertEquals(listOf("stage-message"), store.restore(stagingTwin).envelopes.map { it.fact.id })
        }
    }

    @Test
    fun retentionPinsOutOfWindowRootsReferencedByRetainedReplyCursors() = withFreshDatabase { database ->
        val owner = partition("https://api.raft.build/api")
        val parent = scope(owner, "channel-a")
        val reply = SyncScopeKey(owner, "reply-stream", "thread")
        val store = SqlDelightCanonicalMessageV2Store(
            database = database,
            now = { 100L },
            retention = CanonicalMessageV2RetentionPolicy(
                maxFactsPerScope = 2,
                maxScopesPerPartition = 4,
                maxFactsPerPartition = 20,
                maxBytesPerPartition = 1_000_000.0,
                scopeTtlMs = 1_000,
            ),
        )

        runBlocking {
            store.applySnapshot(
                snapshot(
                    parent,
                    envelope(owner, "channel-a", "root-message", 1),
                    envelope(owner, "channel-a", "message-2", 2),
                ),
            )
            store.applySnapshot(
                CanonicalMessageV2SnapshotFrame(
                    scope = reply,
                    envelopes = listOf(threadEnvelope(owner, "reply-stream", "reply-message", 3)),
                    cursor = PersistedCursor(
                        latestSeq = 3,
                        oldestLoadedSeq = 3,
                        newestLoadedSeq = 3,
                        epoch = "reply-epoch",
                        parentScopeKind = "channel",
                        parentScopeId = "channel-a",
                        rootMessageId = "root-message",
                    ),
                ),
            )
            store.applySnapshot(
                CanonicalMessageV2SnapshotFrame(
                    scope = parent,
                    envelopes = listOf(
                        envelope(owner, "channel-a", "message-3", 3),
                        envelope(owner, "channel-a", "message-4", 4),
                    ),
                    cursor = PersistedCursor(4, 3, 4, epoch = "parent-epoch"),
                ),
            )

            assertEquals(
                listOf("root-message", "message-3", "message-4"),
                store.restore(parent).envelopes.map { it.fact.id },
            )

            assertEquals(setOf(reply), store.clearScope(reply))
            assertEquals(
                listOf("root-message", "message-3", "message-4"),
                store.restore(parent).envelopes.map { it.fact.id },
            )
            store.applySnapshot(
                CanonicalMessageV2SnapshotFrame(
                    scope = parent,
                    envelopes = listOf(
                        envelope(owner, "channel-a", "message-5", 5),
                        envelope(owner, "channel-a", "message-6", 6),
                    ),
                    cursor = PersistedCursor(6, 5, 6, epoch = "parent-epoch"),
                ),
            )
            assertEquals(
                listOf("message-5", "message-6"),
                store.restore(parent).envelopes.map { it.fact.id },
            )
        }
    }

    @Test
    fun directParentClearCascadesReplyAndInvalidatesBothEngineScopes() =
        withFreshDatabase { database ->
            val owner = partition("https://api.raft.build/api")
            val parent = scope(owner, "channel-a")
            val reply = SyncScopeKey(owner, "reply-stream", "thread")
            val invalidations = mutableListOf<Set<SyncScopeKey>>()
            val engine = CanonicalMessageV2GenerationEngine(
                coordinator = CanonicalMessageV2BaselineCoordinator(),
                store = SqlDelightCanonicalMessageV2Store(database, now = { 100L }),
                snapshotCommitObserver = CanonicalMessageV2SnapshotCommitObserver {},
            ).also { generationEngine ->
                generationEngine.addScopeInvalidationObserver { scopes -> invalidations += scopes }
            }

            runBlocking {
                engine.applySnapshot(
                    snapshot(parent, envelope(owner, "channel-a", "root-message", 1)),
                )
                engine.applySnapshot(
                    CanonicalMessageV2SnapshotFrame(
                        scope = reply,
                        envelopes = listOf(threadEnvelope(owner, "reply-stream", "reply-message", 2)),
                        cursor = PersistedCursor(
                            latestSeq = 2,
                            oldestLoadedSeq = 2,
                            newestLoadedSeq = 2,
                            epoch = "reply-epoch",
                            parentScopeKind = "channel",
                            parentScopeId = "channel-a",
                            rootMessageId = "root-message",
                        ),
                    ),
                )

                engine.clearScope(parent)

                assertEquals(setOf(parent, reply), invalidations.single())
                assertNull(engine.currentCursor(parent))
                assertNull(engine.currentCursor(reply))
                assertEquals(RestoredCanonicalMessageV2Scope(emptyList(), null), engine.restore(parent))
                assertEquals(RestoredCanonicalMessageV2Scope(emptyList(), null), engine.restore(reply))
            }
        }

    private fun snapshot(
        scope: SyncScopeKey,
        vararg envelopes: CanonicalMessageEnvelopeV2,
    ) = CanonicalMessageV2SnapshotFrame(
        scope = scope,
        envelopes = envelopes.toList(),
        cursor = PersistedCursor(
            latestSeq = envelopes.maxOfOrNull { it.fact.seq },
            oldestLoadedSeq = envelopes.minOfOrNull { it.fact.seq },
            newestLoadedSeq = envelopes.maxOfOrNull { it.fact.seq },
            hasLoadedOldest = true,
            hasLoadedNewest = true,
            epoch = "epoch-${scope.scopeId}",
        ),
    )

    private fun envelope(
        partition: DurablePartitionKey,
        channelId: String,
        messageId: String,
        seq: Long,
    ): CanonicalMessageEnvelopeV2 =
        (normalizeCanonicalMessageV2(
            CanonicalMessageV2NormalizationInput(
                partition = partition,
                scopeId = channelId,
                fact = CanonicalMessageNodeV2(
                    id = messageId,
                    seq = seq,
                    senderType = "human",
                    senderId = "human-a",
                    content = messageId,
                    messageType = "chat",
                    createdAt = "2026-07-23T00:00:00.000Z",
                    senderName = "Human",
                ),
                producerContext = CanonicalConversationContextWire("channel"),
            ),
        ) as CanonicalMessageV2NormalizationResult.Normalized).envelope

    private fun threadEnvelope(
        partition: DurablePartitionKey,
        channelId: String,
        messageId: String,
        seq: Long,
    ): CanonicalMessageEnvelopeV2 =
        (normalizeCanonicalMessageV2(
            CanonicalMessageV2NormalizationInput(
                partition = partition,
                scopeId = channelId,
                fact = CanonicalMessageNodeV2(
                    id = messageId,
                    seq = seq,
                    senderType = "human",
                    senderId = "human-a",
                    content = messageId,
                    messageType = "chat",
                    createdAt = "2026-07-23T00:00:00.000Z",
                    senderName = "Human",
                ),
                producerContext = CanonicalConversationContextWire(
                    channelType = "thread",
                    parentChannelId = "channel-a",
                    parentChannelType = "channel",
                    parentMessageId = "root-message",
                ),
            ),
        ) as CanonicalMessageV2NormalizationResult.Normalized).envelope

    private fun partition(environmentId: String) = DurablePartitionKey(
        environmentId = environmentId,
        serverId = "server-a",
        principalId = "principal-a",
        consumerId = SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID,
    )

    private fun scope(partition: DurablePartitionKey, id: String) =
        SyncScopeKey(partition, id, "channel")

    /** A receiver-private overlay row as an earlier build may have left it; the V2 clear paths must drop it. */
    private fun RaftSqlDatabase.insertOverlayRow(scope: SyncScopeKey, messageId: String) =
        queries.insertMessageViewerOverlay(
            environmentId = scope.environmentId,
            serverId = scope.serverId,
            principalId = scope.principalId,
            consumerId = scope.consumerId,
            scopeKind = scope.scopeKind,
            scopeId = scope.scopeId,
            subjectKind = "message",
            subjectId = messageId,
            overlayKind = "reactions",
            overlayVersion = 1,
            completeness = "complete",
            payloadJson = "{\"kind\":\"reactions\",\"reactedEmojis\":[\"heart\"]}",
        )

    private fun RaftSqlDatabase.hasOverlayRow(scope: SyncScopeKey, messageId: String): Boolean =
        queries.selectMessageViewerOverlay(
            environmentId = scope.environmentId,
            serverId = scope.serverId,
            principalId = scope.principalId,
            consumerId = scope.consumerId,
            scopeKind = scope.scopeKind,
            scopeId = scope.scopeId,
            subjectKind = "message",
            subjectId = messageId,
            overlayKind = "reactions",
        ).executeAsOneOrNull() != null

    private fun withFreshDatabase(block: (RaftSqlDatabase) -> Unit) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            RaftSqlDatabase.Schema.create(driver).value
            block(RaftSqlDatabase(driver))
        } finally {
            driver.close()
        }
    }
}
