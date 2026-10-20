package build.raft.app.data

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
import build.raft.app.model.MessageUpdated
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import build.raft.app.data.realtime.RealtimeAppliedSeqCursorStore
import build.raft.app.network.sync.CanonicalMessageFoldInput
import build.raft.app.network.sync.CanonicalMessageFoldKind
import build.raft.app.network.sync.CommittedMessageScopeSnapshot
import build.raft.app.network.sync.CurrentGrantMessageShadowCommitLane
import build.raft.app.network.sync.DurablePartitionKey
import build.raft.app.network.sync.FoldPersistFrame
import build.raft.app.network.sync.FoldPersistenceAdapter
import build.raft.app.network.sync.LegacyRealtimeDedupKey
import build.raft.app.network.sync.MessageShadowWorkingCell
import build.raft.app.network.sync.MessageShadowWorkingState
import build.raft.app.network.sync.PersistedCursor
import build.raft.app.network.sync.PublishedMessageShadowState
import build.raft.app.network.sync.RestoredFoldState
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.StrictMessageRestoreResult
import build.raft.app.network.sync.StrictMessageScopeSnapshotReader
import build.raft.app.network.sync.SyncScopeKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CurrentMessageCommitAuthorityRedTest {

    @Test
    fun contextExposesOnlyExactPartitionDerivedInsideCurrentGrantGate() = runBlocking {
        val fixture = fixture("context")
        try {
            fixture.auth.replace(session(), AuthSessionEvent.Authenticated(generation = 1))
            val authority = assertNotNull(fixture.provider.authorityFlow("shell-a").value)
            val grant = assertNotNull(authority.grant("server-a"))
            var partition: DurablePartitionKey? = null

            assertTrue(
                fixture.provider.runWithCurrentMessageGrant(grant) { context ->
                    partition = context.partition
                },
            )
            assertEquals(
                DurablePartitionKey("server-a", "user-1", SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID, "env-test"),
                partition,
            )
        } finally {
            fixture.close()
        }
    }

    /**
     * Task #1109 (offline cache ②): the commit partition carries the REAL environment of the selected API
     * server, normalized exactly like the pending-send journal owner key (trim + trailing '/'), so staging
     * and production rows of one principal can never land in the same partition.
     */
    @Test
    fun contextPartitionCarriesTheNormalizedSelectedEnvironment() = runBlocking {
        val fixture = fixture("context-env", currentEnvironmentId = { " https://api.staging.test/ " })
        try {
            fixture.auth.replace(session(), AuthSessionEvent.Authenticated(generation = 1))
            val authority = assertNotNull(fixture.provider.authorityFlow("shell-a").value)
            val grant = assertNotNull(authority.grant("server-a"))
            var partition: DurablePartitionKey? = null

            assertTrue(fixture.provider.runWithCurrentMessageGrant(grant) { context -> partition = context.partition })
            assertEquals(
                DurablePartitionKey(
                    serverId = "server-a",
                    principalId = "user-1",
                    consumerId = SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID,
                    environmentId = "https://api.staging.test",
                ),
                partition,
            )
        } finally {
            fixture.close()
        }
    }

    /** No environment → no session identity → no grant → nothing can commit under a placeholder partition. */
    @Test
    fun blankEnvironmentYieldsNoGrantInsteadOfAPlaceholderPartition() = runBlocking {
        val fixture = fixture("context-blank-env", currentEnvironmentId = { "   " })
        try {
            fixture.auth.replace(session(), AuthSessionEvent.Authenticated(generation = 1))
            val authority = fixture.provider.authorityFlow("shell-a").value
            assertNull(authority?.grant("server-a"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun a2StaleCapturedGrantRunsZeroContextOrLaneWork() = runBlocking {
        val fixture = fixture("stale")
        try {
            fixture.auth.replace(session(), AuthSessionEvent.Authenticated(generation = 1))
            val first = assertNotNull(fixture.provider.authorityFlow("shell-a").value)
            val stale = assertNotNull(first.grant("server-a"))
            fixture.auth.replace(session(), AuthSessionEvent.TokensRotated(generation = 2))
            var contextRuns = 0

            assertFalse(
                fixture.provider.runWithCurrentMessageGrant(stale) {
                    contextRuns += 1
                },
            )
            assertEquals(0, contextRuns)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun a3AuthRotationWaitsThroughWriteCommitReadbackAndAtomicPublish() = runBlocking {
        val fixture = fixture("rotation")
        try {
            fixture.auth.replace(session(), AuthSessionEvent.Authenticated(generation = 1))
            val first = assertNotNull(fixture.provider.authorityFlow("shell-a").value)
            val grant = assertNotNull(first.grant("server-a"))
            val durable = BlockingDurableStore()
            val lane = durable.lane()
            val scope = durable.scope(grant.serverId, grant.principalId)
            durable.seedReady(lane, scope)

            val applyJob = launch {
                assertTrue(
                    fixture.provider.runWithCurrentMessageGrant(grant) { context ->
                        lane.commit(context.partition, key(), input())
                    },
                )
            }
            assertNotNull(
                withTimeoutOrNull(500L) { durable.writeEntered.await() },
                "guarded current message must synchronously enter the write transaction",
            )

            val rotationFinished = CompletableDeferred<Unit>()
            val rotationJob = launch {
                fixture.auth.replace(session(), AuthSessionEvent.TokensRotated(generation = 2))
                fixture.provider.authorityFlow("shell-a").first { next ->
                    next != null && next.witness.connectionFamilyId != first.witness.connectionFamilyId
                }
                rotationFinished.complete(Unit)
            }
            yield()
            assertFalse(rotationFinished.isCompleted, "rotation must wait through COMMIT/readback/publish")
            assertTrue(lane.published.value().snapshotsByScope[scope]?.facts.orEmpty().isEmpty())

            durable.releaseWrite.complete(Unit)
            applyJob.join()
            rotationJob.join()
            assertEquals(1, durable.writeInvocations)
            assertEquals(1, durable.reads)
            assertEquals(listOf("m1"), lane.published.value().snapshotsByScope[scope]?.facts?.map { it.id })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun a4TwoCurrentLeasesShareOnePartitionCommitAndPublication() = runBlocking {
        val fixture = fixture("multi-lease", scopes = listOf("shell-a", "shell-b"))
        try {
            fixture.auth.replace(session(), AuthSessionEvent.Authenticated(generation = 1))
            val a = assertNotNull(fixture.provider.authorityFlow("shell-a").value)
            val b = assertNotNull(fixture.provider.authorityFlow("shell-b").value)
            val grantA = assertNotNull(a.grant("server-a"))
            val grantB = assertNotNull(b.grant("server-a"))
            val durable = BlockingDurableStore()
            val lane = durable.lane()
            val scope = durable.scope("server-a", "user-1")
            durable.seedReady(lane, scope)

            val first = launch {
                fixture.provider.runWithCurrentMessageGrant(grantA) { context ->
                    lane.commit(context.partition, key(), input())
                }
            }
            assertNotNull(
                withTimeoutOrNull(500L) { durable.writeEntered.await() },
                "first lease must enter the one shared write",
            )
            val secondFinished = CompletableDeferred<Unit>()
            val second = launch {
                fixture.provider.runWithCurrentMessageGrant(grantB) { context ->
                    lane.commit(context.partition, key(), input())
                }
                secondFinished.complete(Unit)
            }
            yield()
            assertFalse(secondFinished.isCompleted, "second lease waits on the registry/central lane boundary")

            durable.releaseWrite.complete(Unit)
            first.join()
            second.join()
            assertEquals(1, durable.writeInvocations)
            assertEquals(1, durable.commits)
            assertEquals(listOf("m1"), lane.published.value().snapshotsByScope[scope]?.facts?.map { it.id })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun selectedNewRollbackThenSameRawReplayRepairsWithoutSecondLegacyDelivery() = runBlocking {
        assertOwnerRollbackReplay(OwnerSurface.Selected, CanonicalMessageFoldKind.New)
    }

    @Test
    fun selectedUpdatedRollbackThenSameRawReplayRepairsWithoutSecondLegacyDelivery() = runBlocking {
        assertOwnerRollbackReplay(OwnerSurface.Selected, CanonicalMessageFoldKind.Updated)
    }

    @Test
    fun backgroundNewRollbackThenSameRawReplayRepairsWithoutSecondUnreadDelivery() = runBlocking {
        assertOwnerRollbackReplay(OwnerSurface.BackgroundUnread, CanonicalMessageFoldKind.New)
    }

    @Test
    fun backgroundUpdatedRollbackThenSameRawReplayRepairsWithoutSecondUnreadDelivery() = runBlocking {
        assertOwnerRollbackReplay(OwnerSurface.BackgroundUnread, CanonicalMessageFoldKind.Updated)
    }

    @Test
    fun processIngressCommitsCanonicalMessageOnceWithoutMountedSinks() = runBlocking {
        val fixture = fixture("process-zero-sink")
        try {
            fixture.auth.replace(session(), AuthSessionEvent.Authenticated(generation = 1))
            val grant = assertNotNull(fixture.provider.authorityFlow("shell-a").value?.grant("server-a"))
            val durable = OwnerDurableStore()
            val scope = durable.scope(grant)
            val empty = CommittedMessageScopeSnapshot(scope, emptyList(), null)
            durable.snapshots[scope] = empty
            val lane = durable.lane()
            durable.seedReady(lane, scope, empty)
            val owner = fixture.owner(RealtimeAppliedSeqCursorStore(InMemoryRaftKeyValueStore()), lane)
            val event = canonicalEvent(CanonicalMessageFoldKind.New)

            assertTrue(owner.acceptProcessMutation(grant, event, source = "normal-process"))
            assertTrue(owner.acceptProcessMutation(grant, event, source = "raw-process"))

            assertEquals(1, durable.writeInvocations)
            assertEquals(1, durable.commits)
            assertEquals(listOf("m1"), lane.published.value().snapshotsByScope[scope]?.facts?.map { it.id })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun cancellationBeforeLegacyAcceptedKeyRecoveryAndSinksLeavesRetryAvailable() = runBlocking {
        val fixture = fixture("owner-cancellation")
        try {
            fixture.auth.replace(session(), AuthSessionEvent.Authenticated(generation = 1))
            val grant = assertNotNull(fixture.provider.authorityFlow("shell-a").value?.grant("server-a"))
            val cursorStore = RealtimeAppliedSeqCursorStore(InMemoryRaftKeyValueStore())
            val durable = OwnerDurableStore()
            val scope = durable.scope(grant)
            var cancelBeforeLane = true
            val lane = durable.lane(enabled = {
                if (cancelBeforeLane) throw CancellationException("cancel-before-lane")
                true
            })
            durable.seedReady(lane, scope, durable.snapshot(scope))
            val owner = fixture.owner(cursorStore, lane)
            var selectedSinkCount = 0
            owner.registerGuardedSinks(
                RealtimeGuardedDomainEventSinks(
                    applyHomeShell = { _, _ -> selectedSinkCount += 1 },
                ),
            )
            val event = canonicalEvent(CanonicalMessageFoldKind.New)

            assertFailsWith<CancellationException> { owner.accept(grant, event, source = "selected") }
            assertEquals(0, selectedSinkCount)
            assertEquals(0L, cursorStore.readAppliedLastSeq(ServerId("server-a")))
            assertEquals(0, durable.writeInvocations)

            cancelBeforeLane = false
            assertTrue(owner.accept(grant, event, source = "selected-retry"))
            assertEquals(1, selectedSinkCount, "cancellation must not consume the legacy accepted key")
            assertEquals(1L, cursorStore.readAppliedLastSeq(ServerId("server-a")))
            assertEquals(1, durable.writeInvocations)
            assertEquals(1, durable.commits)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun nullRunnerNeverTouchesInstalledLaneAndKeepsExactLegacyBehavior() = runBlocking {
        val fixture = fixture("null-runner")
        try {
            fixture.auth.replace(session(), AuthSessionEvent.Authenticated(generation = 1))
            val grant = assertNotNull(fixture.provider.authorityFlow("shell-a").value?.grant("server-a"))
            var laneCalls = 0
            val lane = CurrentGrantMessageShadowCommitLane(
                isEnabledForServer = {
                    laneCalls += 1
                    error("null runner must not enter the lane")
                },
            )
            val cursorStore = RealtimeAppliedSeqCursorStore(InMemoryRaftKeyValueStore())
            val owner = SharedRealtimeDomainEventOwner(
                cursorStore = cursorStore,
                runIfCurrentGrant = fixture.provider::runIfCurrentGrant,
            )
            owner.installCurrentGrantMessageShadowCommitLane(lane)
            var sinkCount = 0
            owner.registerGuardedSinks(
                RealtimeGuardedDomainEventSinks(applyHomeShell = { _, _ -> sinkCount += 1 }),
            )

            assertTrue(owner.accept(grant, canonicalEvent(CanonicalMessageFoldKind.New), source = "null-runner"))
            assertEquals(0, laneCalls)
            assertEquals(1, sinkCount)
            assertEquals(1L, cursorStore.readAppliedLastSeq(ServerId("server-a")))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun laneAndDiagnosticNonCancellationFailuresStillRunLegacyExactlyOnce() = runBlocking {
        val fixture = fixture("lane-logger-nonce")
        try {
            fixture.auth.replace(session(), AuthSessionEvent.Authenticated(generation = 1))
            val grant = assertNotNull(fixture.provider.authorityFlow("shell-a").value?.grant("server-a"))
            var laneCalls = 0
            var loggerCalls = 0
            val lane = CurrentGrantMessageShadowCommitLane(
                isEnabledForServer = {
                    laneCalls += 1
                    throw IllegalStateException("lane-failure")
                },
            )
            val owner = fixture.owner(RealtimeAppliedSeqCursorStore(InMemoryRaftKeyValueStore()), lane)
            var sinkCount = 0
            owner.registerGuardedSinks(
                RealtimeGuardedDomainEventSinks(
                    applyHomeShell = { _, _ -> sinkCount += 1 },
                    log = { line ->
                        if (line.startsWith("current_grant_message_shadow_failed")) {
                            loggerCalls += 1
                            throw IllegalArgumentException("logger-failure")
                        }
                    },
                ),
            )

            assertTrue(owner.accept(grant, canonicalEvent(CanonicalMessageFoldKind.New), source = "logger-nonce"))
            assertEquals(1, laneCalls)
            assertEquals(1, loggerCalls)
            assertEquals(1, sinkCount, "logger failure must not block the legacy fail-open continuation")
        } finally {
            fixture.close()
        }
    }

    @Test
    fun diagnosticCancellationStopsLegacyContinuation() = runBlocking {
        val fixture = fixture("logger-cancellation")
        try {
            fixture.auth.replace(session(), AuthSessionEvent.Authenticated(generation = 1))
            val grant = assertNotNull(fixture.provider.authorityFlow("shell-a").value?.grant("server-a"))
            val lane = CurrentGrantMessageShadowCommitLane(
                isEnabledForServer = { throw IllegalStateException("lane-failure") },
            )
            val cursorStore = RealtimeAppliedSeqCursorStore(InMemoryRaftKeyValueStore())
            val owner = fixture.owner(cursorStore, lane)
            var sinkCount = 0
            owner.registerGuardedSinks(
                RealtimeGuardedDomainEventSinks(
                    applyHomeShell = { _, _ -> sinkCount += 1 },
                    log = { line ->
                        if (line.startsWith("current_grant_message_shadow_failed")) {
                            throw CancellationException("logger-cancellation")
                        }
                    },
                ),
            )

            assertFailsWith<CancellationException> {
                owner.accept(grant, canonicalEvent(CanonicalMessageFoldKind.New), source = "logger-ce")
            }
            assertEquals(0, sinkCount)
            assertEquals(0L, cursorStore.readAppliedLastSeq(ServerId("server-a")))
        } finally {
            fixture.close()
        }
    }

    private enum class OwnerSurface { Selected, BackgroundUnread }

    private suspend fun assertOwnerRollbackReplay(
        surface: OwnerSurface,
        kind: CanonicalMessageFoldKind,
    ) {
        val fixture = fixture("owner-${surface.name}-${kind.name}")
        try {
            fixture.auth.replace(session(), AuthSessionEvent.Authenticated(generation = 1))
            val grant = assertNotNull(fixture.provider.authorityFlow("shell-a").value?.grant("server-a"))
            val durable = OwnerDurableStore().also {
                it.beforeCommitFailure = IllegalStateException("rollback")
            }
            val scope = durable.scope(grant)
            val base = if (kind == CanonicalMessageFoldKind.Updated) canonicalRow(content = "original") else null
            val old = CommittedMessageScopeSnapshot(
                scope = scope,
                facts = listOfNotNull(base),
                cursor = base?.let { PersistedCursor(1L, 1L, 1L) },
            )
            durable.snapshots[scope] = old
            val lane = durable.lane()
            durable.seedReady(lane, scope, old)
            val owner = fixture.owner(RealtimeAppliedSeqCursorStore(InMemoryRaftKeyValueStore()), lane)
            var legacySinkCount = 0
            owner.registerGuardedSinks(
                RealtimeGuardedDomainEventSinks(
                    applyHomeShell = { _, _ ->
                        if (surface == OwnerSurface.Selected) legacySinkCount += 1
                    },
                    applyHomeUnreadProjection = { _, _ ->
                        if (surface == OwnerSurface.BackgroundUnread) legacySinkCount += 1
                    },
                ),
            )
            val event = canonicalEvent(kind)

            val first = when (surface) {
                OwnerSurface.Selected -> owner.accept(grant, event, source = "selected-first")
                OwnerSurface.BackgroundUnread -> owner.acceptHomeUnreadProjection(grant, event, source = "background-first")
            }
            assertTrue(first)
            assertEquals(old, lane.published.value().snapshotsByScope[scope], "rollback keeps Pub0")
            assertEquals(1, legacySinkCount)

            val replay = when (surface) {
                OwnerSurface.Selected -> owner.accept(grant, event, source = "selected-replay")
                OwnerSurface.BackgroundUnread -> owner.acceptHomeUnreadProjection(grant, event, source = "background-replay")
            }
            assertTrue(replay)
            assertEquals(2, durable.writeInvocations, "same raw replay must re-enter the lane")
            assertEquals(1, durable.rollbacks)
            assertEquals(1, durable.commits)
            assertEquals(1, legacySinkCount, "legacy dedup consumes only after first fail-open continuation")
            assertEquals("edited", lane.published.value().snapshotsByScope[scope]?.facts?.single()?.content)
        } finally {
            fixture.close()
        }
    }

    private class OwnerDurableStore {
        val snapshots = mutableMapOf<SyncScopeKey, CommittedMessageScopeSnapshot>()
        var writeInvocations = 0
        var commits = 0
        var rollbacks = 0
        var reads = 0
        var beforeCommitFailure: Throwable? = null

        fun scope(grant: RealtimeGrantRecord): SyncScopeKey =
            SyncScopeKey(
                partition = DurablePartitionKey(
                    grant.serverId,
                    grant.principalId,
                    SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID,
                    grant.environmentId,
                ),
                scopeId = "channel-1",
                scopeKind = "channel",
            )

        fun snapshot(scope: SyncScopeKey): CommittedMessageScopeSnapshot =
            snapshots[scope] ?: CommittedMessageScopeSnapshot(scope, emptyList(), null)

        fun seedReady(
            lane: CurrentGrantMessageShadowCommitLane,
            scope: SyncScopeKey,
            snapshot: CommittedMessageScopeSnapshot,
        ) {
            snapshots[scope] = snapshot
            lane.workingByScope[scope] = MessageShadowWorkingCell(MessageShadowWorkingState.Ready(snapshot))
            lane.published.replace(PublishedMessageShadowState(snapshotsByScope = mapOf(scope to snapshot)))
        }

        fun lane(enabled: (String) -> Boolean = { true }): CurrentGrantMessageShadowCommitLane {
            val reader = StrictMessageScopeSnapshotReader { scope ->
                reads += 1
                StrictMessageRestoreResult.Valid(snapshot(scope))
            }
            val adapter = object : FoldPersistenceAdapter {
                override suspend fun applyFrame(frame: FoldPersistFrame) {
                    writeInvocations += 1
                    beforeCommitFailure?.let { failure ->
                        beforeCommitFailure = null
                        rollbacks += 1
                        throw failure
                    }
                    snapshots[frame.scope] = CommittedMessageScopeSnapshot(frame.scope, frame.facts, frame.cursor)
                    commits += 1
                }

                override suspend fun restore(scope: SyncScopeKey): RestoredFoldState =
                    snapshot(scope).let { RestoredFoldState(it.facts, it.cursor) }
            }
            return CurrentGrantMessageShadowCommitLane(
                strictReader = reader,
                persistence = adapter,
                isEnabledForServer = enabled,
            )
        }
    }

    private class BlockingDurableStore {
        val writeEntered = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        var writeInvocations = 0
        var commits = 0
        var reads = 0
        private val snapshots = mutableMapOf<SyncScopeKey, CommittedMessageScopeSnapshot>()

        fun scope(serverId: String, principalId: String): SyncScopeKey =
            SyncScopeKey(
                partition = DurablePartitionKey(serverId, principalId, SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID, "env-test"),
                scopeId = "channel-1",
                scopeKind = "channel",
            )

        fun seedReady(lane: CurrentGrantMessageShadowCommitLane, scope: SyncScopeKey) {
            val empty = CommittedMessageScopeSnapshot(scope, emptyList(), null)
            snapshots[scope] = empty
            lane.workingByScope[scope] = MessageShadowWorkingCell(MessageShadowWorkingState.Ready(empty))
            lane.published.replace(PublishedMessageShadowState(snapshotsByScope = mapOf(scope to empty)))
        }

        fun lane(): CurrentGrantMessageShadowCommitLane {
            val reader = StrictMessageScopeSnapshotReader { scope ->
                reads += 1
                StrictMessageRestoreResult.Valid(
                    snapshots[scope] ?: CommittedMessageScopeSnapshot(scope, emptyList(), null),
                )
            }
            val adapter = object : FoldPersistenceAdapter {
                override suspend fun applyFrame(frame: FoldPersistFrame) {
                    writeInvocations += 1
                    writeEntered.complete(Unit)
                    releaseWrite.await()
                    snapshots[frame.scope] = CommittedMessageScopeSnapshot(frame.scope, frame.facts, frame.cursor)
                    commits += 1
                }

                override suspend fun restore(scope: SyncScopeKey): RestoredFoldState =
                    snapshots[scope].let { snapshot ->
                        RestoredFoldState(snapshot?.facts.orEmpty(), snapshot?.cursor)
                    }
            }
            return CurrentGrantMessageShadowCommitLane(
                strictReader = reader,
                persistence = adapter,
                isEnabledForServer = { true },
            )
        }
    }

    private data class Fixture(
        val auth: FakeTypedAuthSessionStore,
        val provider: RealtimeAuthorityProvider,
        val scope: CoroutineScope,
    ) {
        fun close() = scope.cancel()

        fun owner(
            cursorStore: RealtimeAppliedSeqCursorStore,
            lane: CurrentGrantMessageShadowCommitLane,
        ): SharedRealtimeDomainEventOwner =
            SharedRealtimeDomainEventOwner(
                cursorStore = cursorStore,
                runIfCurrentGrant = provider::runIfCurrentGrant,
                runWithCurrentMessageGrant = provider::runWithCurrentMessageGrant,
            ).also { owner -> owner.installCurrentGrantMessageShadowCommitLane(lane) }
    }

    private suspend fun fixture(
        process: String,
        scopes: List<String> = listOf("shell-a"),
        currentEnvironmentId: () -> String = { "env-test" },
    ): Fixture {
        val auth = FakeTypedAuthSessionStore()
        val registry =
            RealtimeAuthorityRegistry(
                authRevisionStore = KeyValueRealtimeAuthRevisionStore(InMemoryRaftKeyValueStore()),
                processInstanceId = process,
            )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val provider = RealtimeAuthorityProvider(registry, auth, auth, currentEnvironmentId)
        provider.start(scope)
        scopes.forEach { key -> provider.registerScope(key, registrationId = "mount-$key") }
        return Fixture(auth, provider, scope)
    }

    private class FakeTypedAuthSessionStore : RaftAuthSessionStore, RaftAuthSessionEventSource {
        private var session: StoredAuthSession? = null
        private val events = MutableSharedFlow<AuthSessionEvent>(extraBufferCapacity = 16)

        override val authSessionEvents: SharedFlow<AuthSessionEvent> = events.asSharedFlow()

        override suspend fun readSession(): StoredAuthSession? = session
        override suspend fun saveSession(session: StoredAuthSession) { this.session = session }
        override suspend fun updateTokens(accessToken: String, refreshToken: String) {
            session = session?.copy(accessToken = accessToken, refreshToken = refreshToken)
        }
        override suspend fun updateSelectedServer(serverId: String?, serverSlug: String?) {
            session = session?.copy(selectedServerId = serverId, selectedServerSlug = serverSlug)
        }
        override suspend fun clearSession(reason: String, source: String) { session = null }

        suspend fun replace(next: StoredAuthSession?, event: AuthSessionEvent) {
            session = next
            events.emit(event)
        }
    }

    private fun session(): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "user@example.test", displayName = "User"),
            accessToken = "access",
            refreshToken = "refresh",
            selectedServerId = "server-a",
            servers = listOf(StoredAuthServer(id = "server-a", name = "A", slug = "a")),
        )

    private fun key() = LegacyRealtimeDedupKey("message:new:server-a:m1:1")

    private fun canonicalRow(content: String = "edited") =
        CanonicalMessageRow(
            id = "m1",
            channelId = "channel-1",
            seq = 1L,
            senderType = "human",
            senderId = "u1",
            content = content,
            messageType = "chat",
            createdAt = "2026-07-15T00:00:01Z",
            senderName = "Alice",
        )

    private fun canonicalEvent(kind: CanonicalMessageFoldKind): RealtimeEvent {
        val row = canonicalRow(content = "edited")
        val message =
            Message(
                id = MessageId(row.id),
                threadId = ThreadId(row.channelId),
                channelId = ChannelId(row.channelId),
                seq = row.seq,
                author = MemberProfile(MemberId("u1"), "Alice", "alice", MemberKind.Human),
                timestampLabel = "now",
                body = row.content,
            )
        val carry = CanonicalMessageCarry(row = row, presentAggregates = emptySet<MessageAggregateField>())
        return when (kind) {
            CanonicalMessageFoldKind.New -> MessageNew(message = message, decodedCanonical = carry)
            CanonicalMessageFoldKind.Updated -> MessageUpdated(message = message, decodedCanonical = carry)
        }
    }

    private fun input() =
        CanonicalMessageFoldInput.from(
            CanonicalMessageFoldKind.New,
            CanonicalMessageCarry(
                row =
                    CanonicalMessageRow(
                        id = "m1",
                        channelId = "channel-1",
                        seq = 1L,
                        senderType = "human",
                        senderId = "u1",
                        content = "body",
                        messageType = "chat",
                        createdAt = "2026-07-15T00:00:01Z",
                        senderName = "Alice",
                    ),
                presentAggregates = emptySet(),
            ),
        )
}
