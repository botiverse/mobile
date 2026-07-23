package build.raft.app.network.sync

import build.raft.app.canonical.message.CanonicalConversationContextWire
import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.canonical.message.MessageAggregateField
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class CanonicalMessageV2VisitedWindowGatewayTest {
    @Test
    fun realtimeArrivingDuringSnapshotCommitsAfterSnapshotWithoutRollback() = runBlocking {
        val store = InMemoryStore()
        val engine = engine(store)
        val gateway = CanonicalMessageV2VisitedWindowGateway(engine, isEnabledForServer = { true })
        val scope = topLevelScope("channel-a")
        gateway.refresh(scope, loader(snapshot(scope, envelope(scope, "message-1", 1))))

        val snapshotStarted = CompletableDeferred<Unit>()
        val releaseSnapshot = CompletableDeferred<Unit>()
        val refresh = async {
            gateway.refresh(
                scope,
                CanonicalMessageV2SnapshotLoader {
                    snapshotStarted.complete(Unit)
                    releaseSnapshot.await()
                    CanonicalMessageV2SnapshotLoadResult.Loaded(
                        snapshot(scope, envelope(scope, "message-2", 2)),
                    )
                },
            )
        }
        snapshotStarted.await()
        val realtime = async {
            gateway.acceptRealtime(
                partition = scope.partition,
                foldInput = foldInput(scope, "message-3", 3),
                refreshLoader = loader(snapshot(scope, envelope(scope, "message-refresh", 4))),
            )
        }
        releaseSnapshot.complete(Unit)

        assertIs<CanonicalMessageV2SnapshotLoadResult.Loaded>(refresh.await())
        assertEquals(CanonicalMessageV2VisitedRealtimeResult.Committed, realtime.await())
        assertEquals(listOf("message-2", "message-3"), store.restore(scope).envelopes.map { it.fact.id })
    }

    @Test
    fun staleGrantAfterPreparationCommitsZeroVisitedFactsWhileSuccessorProceeds() = runBlocking {
        val store = InMemoryStore()
        val coordinator = CanonicalMessageV2BaselineCoordinator()
        var observerCommits = 0
        val engine =
            CanonicalMessageV2GenerationEngine(
                coordinator = coordinator,
                store = store,
                snapshotCommitObserver = CanonicalMessageV2SnapshotCommitObserver { observerCommits += 1 },
            )
        val gateway = CanonicalMessageV2VisitedWindowGateway(engine, isEnabledForServer = { true })
        val scope = topLevelScope("channel-a")
        gateway.refresh(scope, loader(snapshot(scope, envelope(scope, "message-1", 1))))
        val cursorBeforeStaleGrant = engine.currentCursor(scope)
        val observerCommitsBeforeStaleGrant = observerCommits
        coordinator.invalidate(scope)
        assertFalse(coordinator.isBaselined(scope))

        var currentGrant = "g1"
        val g1CommitGateEntered = CompletableDeferred<Unit>()
        val releaseG1CommitGate = CompletableDeferred<Unit>()
        val g1 = async {
            gateway.acceptRealtime(
                partition = scope.partition,
                foldInput = foldInput(scope, "message-g1", 2),
                refreshLoader = loader(snapshot(scope, envelope(scope, "unexpected-refresh", 99))),
                runIfCurrentCommit = { block ->
                    g1CommitGateEntered.complete(Unit)
                    releaseG1CommitGate.await()
                    if (currentGrant != "g1") {
                        false
                    } else {
                        block()
                        true
                    }
                },
            )
        }
        g1CommitGateEntered.await()

        currentGrant = "g2"
        releaseG1CommitGate.complete(Unit)

        val stale = assertIs<CanonicalMessageV2VisitedRealtimeResult.RefreshUnavailable>(g1.await())
        assertEquals("canonical_v2_stale_realtime_grant", stale.diagnostic)
        assertEquals(listOf("message-1"), store.restore(scope).envelopes.map { it.fact.id })
        assertEquals(1L, store.restore(scope).cursor?.latestSeq)
        assertEquals(cursorBeforeStaleGrant, engine.currentCursor(scope))
        assertEquals(observerCommitsBeforeStaleGrant, observerCommits)
        assertFalse(coordinator.isBaselined(scope), "stale g1 must not rebaseline coordinator authority")

        assertEquals(
            CanonicalMessageV2VisitedRealtimeResult.Committed,
            gateway.acceptRealtime(
                partition = scope.partition,
                foldInput = foldInput(scope, "message-g2", 3),
                refreshLoader = loader(snapshot(scope, envelope(scope, "unexpected-refresh", 100))),
                runIfCurrentCommit = { block ->
                    if (currentGrant != "g2") {
                        false
                    } else {
                        block()
                        true
                    }
                },
            ),
        )
        assertEquals(listOf("message-1", "message-g2"), store.restore(scope).envelopes.map { it.fact.id })
        assertEquals(3L, store.restore(scope).cursor?.latestSeq)
        assertEquals(3L, engine.currentCursor(scope)?.latestSeq)
        assertEquals(observerCommitsBeforeStaleGrant + 1, observerCommits)
        assertEquals(true, coordinator.isBaselined(scope))
    }

    @Test
    fun selectedCanaryRealtimeWaitsForVisitedSnapshotWriterAndCannotBeRolledBack() = runBlocking {
        val store = InMemoryStore()
        val engine = engine(store)
        val gateway = CanonicalMessageV2VisitedWindowGateway(engine, isEnabledForServer = { true })
        val canary = CanonicalMessageV2ProductionCanary(
            isEnabledForServer = { true },
            generationEngine = engine,
        )
        val scope = topLevelScope("channel-a")
        gateway.refresh(scope, loader(snapshot(scope, envelope(scope, "message-1", 1))))
        val snapshotStarted = CompletableDeferred<Unit>()
        val releaseSnapshot = CompletableDeferred<Unit>()
        val refresh = async {
            gateway.refresh(scope) {
                snapshotStarted.complete(Unit)
                releaseSnapshot.await()
                CanonicalMessageV2SnapshotLoadResult.Loaded(
                    snapshot(scope, envelope(scope, "message-2", 2)),
                )
            }
        }
        snapshotStarted.await()
        val realtime = async {
            canary.accept(
                partition = scope.partition,
                foldInput = foldInput(scope, "message-3", 3),
                snapshotLoader = loader(snapshot(scope, envelope(scope, "message-refresh", 4))),
            )
        }
        kotlinx.coroutines.yield()
        assertFalse(realtime.isCompleted)

        releaseSnapshot.complete(Unit)

        assertIs<CanonicalMessageV2SnapshotLoadResult.Loaded>(refresh.await())
        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, realtime.await())
        assertEquals(listOf("message-2", "message-3"), store.restore(scope).envelopes.map { it.fact.id })
    }

    @Test
    fun exactParentRefreshPreservesVisitedWindowAndSynchronizesCanaryCursor() = runBlocking {
        val store = InMemoryStore()
        val engine = engine(store)
        val gateway = CanonicalMessageV2VisitedWindowGateway(engine, isEnabledForServer = { true })
        val canary = CanonicalMessageV2ProductionCanary(
            isEnabledForServer = { true },
            generationEngine = engine,
        )
        val scope = topLevelScope("channel-a")
        gateway.refresh(
            scope,
            loader(
                snapshot(
                    scope,
                    envelope(scope, "message-10", 10),
                    envelope(scope, "message-11", 11),
                ),
            ),
        )
        assertEquals(
            CanonicalMessageV2CanaryOutcome.Committed,
            canary.accept(
                scope.partition,
                foldInput(scope, "message-12", 12),
                loader(snapshot(scope, envelope(scope, "unexpected-refresh", 99))),
            ),
        )

        assertIs<CanonicalMessageV2SnapshotLoadResult.Loaded>(
            gateway.refreshPreservingWindow(
                scope,
                loader(snapshot(scope, envelope(scope, "root-message", 5))),
            ),
        )
        assertEquals(
            CanonicalMessageV2CanaryOutcome.Committed,
            canary.accept(
                scope.partition,
                foldInput(scope, "message-13", 13),
                loader(snapshot(scope, envelope(scope, "unexpected-refresh-2", 100))),
            ),
        )

        val restored = store.restore(scope)
        assertEquals(
            listOf("root-message", "message-10", "message-11", "message-12", "message-13"),
            restored.envelopes.map { it.fact.id },
        )
        assertEquals(13L, restored.cursor?.latestSeq)
        assertEquals(10L, restored.cursor?.oldestLoadedSeq)
    }

    @Test
    fun partitionClearRevokesPausedCanarySnapshotAndClearsSelection() = runBlocking {
        val store = InMemoryStore()
        val engine = engine(store)
        val gateway = CanonicalMessageV2VisitedWindowGateway(engine, isEnabledForServer = { true })
        val canary = CanonicalMessageV2ProductionCanary(
            isEnabledForServer = { true },
            generationEngine = engine,
        )
        val scope = topLevelScope("channel-a")
        val snapshotStarted = CompletableDeferred<Unit>()
        val releaseSnapshot = CompletableDeferred<Unit>()
        val accepted = async {
            canary.accept(scope.partition, foldInput(scope, "stale-after-logout", 1)) {
                snapshotStarted.complete(Unit)
                releaseSnapshot.await()
                CanonicalMessageV2SnapshotLoadResult.Loaded(
                    snapshot(scope, envelope(scope, "stale-after-logout", 1)),
                )
            }
        }
        snapshotStarted.await()

        gateway.clearPartition(scope.partition)
        assertEquals(CanonicalMessageV2CanaryMode.Off, canary.modeFor(scope.serverId, scope.scopeId))
        releaseSnapshot.complete(Unit)

        assertEquals(
            CanonicalMessageV2CanaryOutcome.Held("canonical_v2_partition_revoked"),
            accepted.await(),
        )
        assertEquals(RestoredCanonicalMessageV2Scope(emptyList(), null), store.restore(scope))
    }

    @Test
    fun retentionEvictionInvalidatesCanaryAndForcesFreshSnapshotOnReturn() = runBlocking {
        val store = InMemoryStore()
        val engine = engine(store)
        val gateway = CanonicalMessageV2VisitedWindowGateway(engine, isEnabledForServer = { true })
        val canary = CanonicalMessageV2ProductionCanary(
            isEnabledForServer = { true },
            generationEngine = engine,
        )
        val scopeA = topLevelScope("channel-a")
        val scopeB = topLevelScope("channel-b")
        gateway.refresh(scopeA, loader(snapshot(scopeA, envelope(scopeA, "message-a1", 1))))
        assertEquals(
            CanonicalMessageV2CanaryOutcome.Committed,
            canary.accept(
                scopeA.partition,
                foldInput(scopeA, "message-a2", 2),
                loader(snapshot(scopeA, envelope(scopeA, "unexpected", 99))),
            ),
        )

        store.evictionsByWrittenScope[scopeB] = setOf(scopeA)
        gateway.refresh(scopeB, loader(snapshot(scopeB, envelope(scopeB, "message-b1", 1))))
        assertEquals(CanonicalMessageV2CanaryMode.Off, canary.modeFor(scopeA.serverId, scopeA.scopeId))
        assertNull(engine.currentCursor(scopeA))

        var snapshotLoads = 0
        assertEquals(
            CanonicalMessageV2CanaryOutcome.Committed,
            canary.accept(scopeA.partition, foldInput(scopeA, "message-a3", 3)) {
                snapshotLoads += 1
                CanonicalMessageV2SnapshotLoadResult.Loaded(
                    snapshot(scopeA, envelope(scopeA, "message-a3", 3)),
                )
            },
        )
        assertEquals(1, snapshotLoads)
        assertEquals(listOf("message-a3"), store.restore(scopeA).envelopes.map { it.fact.id })
    }

    @Test
    fun currentScopeRetentionEvictionCannotPublishCommittedCursor() = runBlocking {
        val store = InMemoryStore()
        val engine = engine(store)
        val gateway = CanonicalMessageV2VisitedWindowGateway(engine, isEnabledForServer = { true })
        val scope = topLevelScope("channel-a")
        store.evictionsByWrittenScope[scope] = setOf(scope)

        val result = gateway.refresh(scope, loader(snapshot(scope, envelope(scope, "message-1", 1))))

        assertEquals(
            CanonicalMessageV2SnapshotLoadResult.Unavailable("canonical_v2_retention_evicted_scope"),
            result,
        )
        assertNull(engine.currentCursor(scope))
        assertEquals(RestoredCanonicalMessageV2Scope(emptyList(), null), store.restore(scope))
        assertEquals(
            CanonicalMessageV2VisitedRealtimeResult.NotVisited,
            gateway.acceptRealtime(
                scope.partition,
                foldInput(scope, "message-2", 2),
                loader(snapshot(scope, envelope(scope, "message-2", 2))),
            ),
        )
    }

    @Test
    fun flagOffRestoreClearsDurableScopeBeforeReturningAnyCandidate() = runBlocking {
        var enabled = true
        val store = InMemoryStore()
        val gateway = CanonicalMessageV2VisitedWindowGateway(engine(store), isEnabledForServer = { enabled })
        val scope = topLevelScope("channel-a")
        gateway.refresh(scope, loader(snapshot(scope, envelope(scope, "message-1", 1))))

        enabled = false

        assertEquals(RestoredCanonicalMessageV2Scope(emptyList(), null), gateway.restore(scope))
        assertEquals(RestoredCanonicalMessageV2Scope(emptyList(), null), store.restore(scope))
    }

    @Test
    fun parentTopLevelIdentityNeverAdmitsAsReplyThreadScope() {
        val partition = partition()
        val parent = foldInput(topLevelScope("parent-channel"), "parent-message", 1)
        val reply = parent.copy(
            row = parent.row.copy(
                channelId = "reply-stream",
                conversationContext = CanonicalConversationContextWire(
                    channelType = "thread",
                    parentChannelId = "parent-channel",
                    parentChannelType = "channel",
                    parentMessageId = "parent-message",
                ),
            ),
        )

        assertEquals(
            SyncScopeKey(partition, "parent-channel", "channel"),
            parent.visitedV2ScopeOrNull(partition),
        )
        assertEquals(
            SyncScopeKey(partition, "reply-stream", "thread"),
            reply.visitedV2ScopeOrNull(partition),
        )
        assertNull(
            reply.copy(
                row = reply.row.copy(
                    conversationContext = reply.row.conversationContext?.copy(parentMessageId = null),
                ),
            ).visitedV2ScopeOrNull(partition),
        )
    }

    @Test
    fun partitionClearFencesAnAlreadyRunningSnapshotCommit() = runBlocking {
        val store = InMemoryStore()
        val gateway = CanonicalMessageV2VisitedWindowGateway(engine(store), isEnabledForServer = { true })
        val scope = topLevelScope("channel-a")
        gateway.refresh(scope, loader(snapshot(scope, envelope(scope, "message-1", 1))))
        val snapshotStarted = CompletableDeferred<Unit>()
        val releaseSnapshot = CompletableDeferred<Unit>()
        val refresh = async {
            gateway.refresh(scope) {
                snapshotStarted.complete(Unit)
                releaseSnapshot.await()
                CanonicalMessageV2SnapshotLoadResult.Loaded(
                    snapshot(scope, envelope(scope, "stale-after-logout", 2)),
                )
            }
        }
        snapshotStarted.await()

        gateway.clearPartition(scope.partition)
        releaseSnapshot.complete(Unit)

        assertIs<CanonicalMessageV2SnapshotLoadResult.Unavailable>(refresh.await())
        assertEquals(RestoredCanonicalMessageV2Scope(emptyList(), null), store.restore(scope))
    }

    private fun engine(store: CanonicalMessageV2Store) = CanonicalMessageV2GenerationEngine(
        coordinator = CanonicalMessageV2BaselineCoordinator(),
        store = store,
        snapshotCommitObserver = CanonicalMessageV2SnapshotCommitObserver {},
    )

    private fun loader(frame: CanonicalMessageV2SnapshotFrame) = CanonicalMessageV2SnapshotLoader {
        CanonicalMessageV2SnapshotLoadResult.Loaded(frame)
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
            epoch = "epoch",
        ),
    )

    private fun envelope(scope: SyncScopeKey, id: String, seq: Long): CanonicalMessageEnvelopeV2 =
        checkNotNull(foldInput(scope, id, seq).toCanonicalMessageV2Envelope(scope.partition, replyCount = 0))

    private fun foldInput(scope: SyncScopeKey, id: String, seq: Long) = CanonicalMessageFoldInput(
        kind = CanonicalMessageFoldKind.New,
        row = CanonicalMessageRow(
            id = id,
            channelId = scope.scopeId,
            seq = seq,
            senderType = "human",
            senderId = "human-a",
            content = id,
            messageType = "chat",
            createdAt = "2026-07-23T00:00:00.000Z",
            senderName = "Human",
            conversationContext = CanonicalConversationContextWire(scope.scopeKind),
        ),
        presentAggregates = MessageAggregateField.entries.toList(),
    )

    private fun partition() = DurablePartitionKey(
        environmentId = "https://api.raft.build/api",
        serverId = "server-a",
        principalId = "principal-a",
        consumerId = SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID,
    )

    private fun topLevelScope(id: String) = SyncScopeKey(partition(), id, "channel")

    private class InMemoryStore : CanonicalMessageV2Store {
        private val states = mutableMapOf<SyncScopeKey, RestoredCanonicalMessageV2Scope>()
        val evictionsByWrittenScope = mutableMapOf<SyncScopeKey, Set<SyncScopeKey>>()

        override suspend fun applySnapshot(frame: CanonicalMessageV2SnapshotFrame): CanonicalMessageV2StoreWriteResult {
            states[frame.scope] = RestoredCanonicalMessageV2Scope(frame.envelopes, frame.cursor)
            val evicted = evictionsByWrittenScope.remove(frame.scope).orEmpty()
            states.keys.removeAll(evicted)
            return CanonicalMessageV2StoreWriteResult(evicted)
        }

        override suspend fun applyIncremental(frame: CanonicalMessageV2IncrementalFrame): CanonicalMessageV2StoreWriteResult {
            val previous = states[frame.scope]?.envelopes.orEmpty().associateBy { it.fact.id }
            states[frame.scope] = RestoredCanonicalMessageV2Scope(
                envelopes = (previous + frame.envelopes.associateBy { it.fact.id }).values.sortedBy { it.fact.seq },
                cursor = frame.cursor,
            )
            val evicted = evictionsByWrittenScope.remove(frame.scope).orEmpty()
            states.keys.removeAll(evicted)
            return CanonicalMessageV2StoreWriteResult(evicted)
        }

        override suspend fun restore(scope: SyncScopeKey): RestoredCanonicalMessageV2Scope =
            states[scope] ?: RestoredCanonicalMessageV2Scope(emptyList(), null)

        override suspend fun clearScope(scope: SyncScopeKey): Set<SyncScopeKey> {
            states.remove(scope)
            return setOf(scope)
        }

        override suspend fun clearPartition(partition: DurablePartitionKey) {
            states.keys.removeAll { it.partition == partition }
        }
    }
}
