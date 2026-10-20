package build.raft.app.network.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.canonical.message.CanonicalConversationContextWire
import build.raft.app.data.RaftSqlDatabase
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
        val overlays = SqlDelightViewerOverlayStore(database)

        runBlocking {
            store.applySnapshot(snapshot(channel, envelope(production, "channel-a", "message-a", 1)))
            store.applySnapshot(snapshot(sibling, envelope(production, "channel-b", "message-b", 1)))
            store.applySnapshot(snapshot(stagingTwin, envelope(staging, "channel-a", "stage-message", 1)))
            val channelOverlay = overlayKey(channel, "message-a")
            val siblingOverlay = overlayKey(sibling, "message-b")
            overlays.compareAndWrite(channelOverlay, overlay())
            overlays.compareAndWrite(siblingOverlay, overlay())

            store.clearScope(channel)
            assertEquals(RestoredCanonicalMessageV2Scope(emptyList(), null), store.restore(channel))
            assertNull(overlays.read(channelOverlay))
            assertEquals(listOf("message-b"), store.restore(sibling).envelopes.map { it.fact.id })
            assertEquals(overlay(), overlays.read(siblingOverlay))

            store.clearPartition(production)
            assertEquals(RestoredCanonicalMessageV2Scope(emptyList(), null), store.restore(sibling))
            assertNull(overlays.read(siblingOverlay))
            assertEquals(listOf("stage-message"), store.restore(stagingTwin).envelopes.map { it.fact.id })
        }
    }

    @Test
    fun retentionBoundsFactsPerScopeAndEvictsLeastRecentlyUpdatedScopes() = withFreshDatabase { database ->
        var now = 1L
        val owner = partition("https://api.raft.build/api")
        val store = SqlDelightCanonicalMessageV2Store(
            database = database,
            now = { now },
            retention = CanonicalMessageV2RetentionPolicy(
                maxFactsPerScope = 2,
                maxScopesPerPartition = 2,
                maxFactsPerPartition = 4,
                maxBytesPerPartition = 1_000_000.0,
                scopeTtlMs = 1_000,
            ),
        )
        val first = scope(owner, "channel-a")
        val second = scope(owner, "channel-b")
        val third = scope(owner, "channel-c")

        runBlocking {
            store.applySnapshot(
                snapshot(
                    first,
                    envelope(owner, "channel-a", "a-1", 1),
                    envelope(owner, "channel-a", "a-2", 2),
                    envelope(owner, "channel-a", "a-3", 3),
                ),
            )
            assertEquals(listOf("a-2", "a-3"), store.restore(first).envelopes.map { it.fact.id })

            now = 2L
            store.applySnapshot(snapshot(second, envelope(owner, "channel-b", "b-1", 1)))
            now = 3L
            store.applySnapshot(snapshot(third, envelope(owner, "channel-c", "c-1", 1)))

            assertEquals(RestoredCanonicalMessageV2Scope(emptyList(), null), store.restore(first))
            assertEquals(listOf("b-1"), store.restore(second).envelopes.map { it.fact.id })
            assertEquals(listOf("c-1"), store.restore(third).envelopes.map { it.fact.id })
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

    @Test
    fun partitionRetentionEvictsReplyScopesWhenTheirParentProofScopeIsEvicted() =
        withFreshDatabase { database ->
            val owner = partition("https://api.raft.build/api")
            val parent = scope(owner, "channel-a")
            val reply = SyncScopeKey(owner, "reply-stream", "thread")
            val other = scope(owner, "channel-b")
            var now = 1L
            val store = SqlDelightCanonicalMessageV2Store(
                database = database,
                now = { now },
                retention = CanonicalMessageV2RetentionPolicy(
                    maxFactsPerScope = 2,
                    // Channel / DM scopes only (task #168 step 3: thread scopes have their own cap), so
                    // channel-b alone pushes channel-a out, and its reply scope goes with it.
                    maxScopesPerPartition = 1,
                    maxFactsPerPartition = 20,
                    maxBytesPerPartition = 1_000_000.0,
                    scopeTtlMs = 1_000,
                ),
            )

            runBlocking {
                store.applySnapshot(
                    snapshot(parent, envelope(owner, "channel-a", "root-message", 1)),
                )
                now = 2L
                store.applySnapshot(
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
                now = 3L
                val result = store.applySnapshot(
                    snapshot(other, envelope(owner, "channel-b", "other-message", 1)),
                )

                assertEquals(setOf(parent, reply), result.evictedScopes)
                assertEquals(RestoredCanonicalMessageV2Scope(emptyList(), null), store.restore(parent))
                assertEquals(RestoredCanonicalMessageV2Scope(emptyList(), null), store.restore(reply))
                assertEquals(listOf("other-message"), store.restore(other).envelopes.map { it.fact.id })
            }
        }

    @Test
    fun currentScopeBytesEvictionIsReturnedToTheGenerationAuthority() = withFreshDatabase { database ->
        val owner = partition("https://api.raft.build/api")
        val channel = scope(owner, "channel-a")
        val store = SqlDelightCanonicalMessageV2Store(
            database = database,
            now = { 100L },
            retention = CanonicalMessageV2RetentionPolicy(
                maxFactsPerScope = 2,
                maxScopesPerPartition = 2,
                maxFactsPerPartition = 4,
                maxBytesPerPartition = 1.0,
                scopeTtlMs = 1_000,
            ),
        )

        runBlocking {
            val result = store.applySnapshot(
                snapshot(channel, envelope(owner, "channel-a", "message-a", 1)),
            )

            assertEquals(setOf(channel), result.evictedScopes)
            assertEquals(RestoredCanonicalMessageV2Scope(emptyList(), null), store.restore(channel))
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

    private fun overlayKey(scope: SyncScopeKey, messageId: String) = ViewerOverlayKey(
        partition = scope.partition,
        scopeKind = scope.scopeKind,
        scopeId = scope.scopeId,
        subjectKind = "message",
        subjectId = messageId,
        overlayKind = "reactions",
    )

    private fun overlay() = VersionedViewerOverlayV1(
        version = 1,
        completeness = OverlayCompleteness.Complete,
        payload = ViewerOverlayPayloadV1.Reactions(listOf("heart")),
    )

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
