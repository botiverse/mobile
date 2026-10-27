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
import build.raft.app.network.sync.CanonicalMessageFoldKind
import build.raft.app.network.sync.DurablePartitionKey
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID
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
    fun a3AuthRotationWaitsThroughTheExactGrantMessageBlock() = runBlocking {
        val fixture = fixture("rotation")
        try {
            fixture.auth.replace(session(), AuthSessionEvent.Authenticated(generation = 1))
            val first = assertNotNull(fixture.provider.authorityFlow("shell-a").value)
            val grant = assertNotNull(first.grant("server-a"))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var blockRuns = 0

            val applyJob = launch {
                assertTrue(
                    fixture.provider.runWithCurrentMessageGrant(grant) {
                        blockRuns += 1
                        entered.complete(Unit)
                        release.await()
                    },
                )
            }
            assertNotNull(
                withTimeoutOrNull(500L) { entered.await() },
                "guarded current message must synchronously enter the exact-grant block",
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
            assertFalse(rotationFinished.isCompleted, "rotation must wait for the exact-grant block")

            release.complete(Unit)
            applyJob.join()
            rotationJob.join()
            assertEquals(1, blockRuns)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun a4TwoCurrentLeasesShareOnePartitionAndSerializeTheirBlocks() = runBlocking {
        val fixture = fixture("multi-lease", scopes = listOf("shell-a", "shell-b"))
        try {
            fixture.auth.replace(session(), AuthSessionEvent.Authenticated(generation = 1))
            val a = assertNotNull(fixture.provider.authorityFlow("shell-a").value)
            val b = assertNotNull(fixture.provider.authorityFlow("shell-b").value)
            val grantA = assertNotNull(a.grant("server-a"))
            val grantB = assertNotNull(b.grant("server-a"))
            val firstEntered = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val partitions = mutableListOf<DurablePartitionKey>()

            val first = launch {
                fixture.provider.runWithCurrentMessageGrant(grantA) { context ->
                    partitions += context.partition
                    firstEntered.complete(Unit)
                    releaseFirst.await()
                }
            }
            assertNotNull(
                withTimeoutOrNull(500L) { firstEntered.await() },
                "first lease must enter its exact-grant block",
            )
            val secondFinished = CompletableDeferred<Unit>()
            val second = launch {
                fixture.provider.runWithCurrentMessageGrant(grantB) { context ->
                    partitions += context.partition
                }
                secondFinished.complete(Unit)
            }
            yield()
            assertFalse(secondFinished.isCompleted, "second lease waits on the registry boundary")
            assertEquals(1, partitions.size)

            releaseFirst.complete(Unit)
            first.join()
            second.join()
            assertEquals(2, partitions.size)
            assertEquals(
                setOf(DurablePartitionKey("server-a", "user-1", SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID, "env-test")),
                partitions.toSet(),
            )
        } finally {
            fixture.close()
        }
    }

    /**
     * Phase 0a: the exact-grant slot no longer runs a V1 shadow commit before display. A guarded canonical
     * frame still reaches the legacy sinks exactly once and advances the applied cursor once.
     */
    @Test
    fun guardedCanonicalFrameRunsLegacyExactlyOnceInTheExactGrantSlot() = runBlocking {
        val fixture = fixture("slot-legacy")
        try {
            fixture.auth.replace(session(), AuthSessionEvent.Authenticated(generation = 1))
            val grant = assertNotNull(fixture.provider.authorityFlow("shell-a").value?.grant("server-a"))
            val cursorStore = RealtimeAppliedSeqCursorStore(InMemoryRaftKeyValueStore())
            val owner = fixture.owner(cursorStore)
            var selected = 0
            var unread = 0
            owner.registerGuardedSinks(
                RealtimeGuardedDomainEventSinks(
                    applyHomeShell = { _, _ -> selected += 1 },
                    applyHomeUnreadProjection = { _, _ -> unread += 1 },
                ),
            )

            assertTrue(owner.accept(grant, canonicalEvent(CanonicalMessageFoldKind.New), source = "selected"))
            assertTrue(owner.accept(grant, canonicalEvent(CanonicalMessageFoldKind.New), source = "selected-replay"))
            assertTrue(
                owner.acceptHomeUnreadProjection(
                    grant,
                    canonicalEvent(CanonicalMessageFoldKind.Updated),
                    source = "background",
                ),
            )
            assertEquals(1, selected, "legacy dedup still consumes the replay")
            assertEquals(1, unread)
            assertEquals(1L, cursorStore.readAppliedLastSeq(ServerId("server-a")))
        } finally {
            fixture.close()
        }
    }

    private data class Fixture(
        val auth: FakeTypedAuthSessionStore,
        val provider: RealtimeAuthorityProvider,
        val scope: CoroutineScope,
    ) {
        fun close() = scope.cancel()

        fun owner(cursorStore: RealtimeAppliedSeqCursorStore): SharedRealtimeDomainEventOwner =
            SharedRealtimeDomainEventOwner(
                cursorStore = cursorStore,
                runIfCurrentGrant = provider::runIfCurrentGrant,
                runWithCurrentMessageGrant = provider::runWithCurrentMessageGrant,
            )
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
}
