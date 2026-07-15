package build.raft.app.data

import build.raft.app.canonical.message.CanonicalMessageCarry
import build.raft.app.canonical.message.CanonicalMessageRow
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
import build.raft.app.network.sync.PublishedMessageShadowState
import build.raft.app.network.sync.RestoredFoldState
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.StrictMessageRestoreResult
import build.raft.app.network.sync.StrictMessageScopeSnapshotReader
import build.raft.app.network.sync.SyncScopeKey
import kotlinx.coroutines.CompletableDeferred
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
                DurablePartitionKey("server-a", "user-1", SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID),
                partition,
            )
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

    private class BlockingDurableStore {
        val writeEntered = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        var writeInvocations = 0
        var commits = 0
        var reads = 0
        private val snapshots = mutableMapOf<SyncScopeKey, CommittedMessageScopeSnapshot>()

        fun scope(serverId: String, principalId: String): SyncScopeKey =
            SyncScopeKey(
                partition = DurablePartitionKey(serverId, principalId, SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID),
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
    }

    private suspend fun fixture(process: String, scopes: List<String> = listOf("shell-a")): Fixture {
        val auth = FakeTypedAuthSessionStore()
        val registry =
            RealtimeAuthorityRegistry(
                authRevisionStore = KeyValueRealtimeAuthRevisionStore(InMemorySlockKeyValueStore()),
                processInstanceId = process,
            )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val provider = RealtimeAuthorityProvider(registry, auth, auth)
        provider.start(scope)
        scopes.forEach { key -> provider.registerScope(key, registrationId = "mount-$key") }
        return Fixture(auth, provider, scope)
    }

    private class FakeTypedAuthSessionStore : SlockAuthSessionStore, SlockAuthSessionEventSource {
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
