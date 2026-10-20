package build.raft.app.data

import build.raft.app.data.realtime.GeneratedRealtimeContract
import build.raft.app.data.realtime.RealtimeAppliedSeqCursorStore
import build.raft.app.data.realtime.RealtimeCursorPolicy
import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeProcessDesiredSession
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.data.realtime.RaftRealtimeNotifyContract
import build.raft.app.withEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ProcessRealtimeIngressCoordinatorTest {
    @Test
    fun normalTerminalFrameIsAcceptedWithoutAppShellAndTombstonesOldWitness() = runBlocking {
        val auth = FakeAuthStore()
        val registry =
            RealtimeAuthorityRegistry(
                KeyValueRealtimeAuthRevisionStore(InMemoryRaftKeyValueStore()),
                processInstanceId = "process-ingress-test",
            )
        val provider = RealtimeAuthorityProvider(registry, auth, auth, { "env-test" })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        provider.start(scope)
        provider.registerScope("process-realtime-host", "process-owner")
        auth.replace(session(), AuthSessionEvent.Authenticated(generation = 1))
        val authority = assertNotNull(provider.authorityFlow("process-realtime-host").value)
        val oldGrant = assertNotNull(authority.grant("server-a"))
        val bus = FakeEventBus()
        val host = FakeNativeHost()
        val presenceConnections = mutableListOf<Pair<String, Long>>()
        val roomsJoined = mutableListOf<Pair<String, Long>>()
        val owner =
            SharedRealtimeDomainEventOwner(
                cursorStore = RealtimeAppliedSeqCursorStore(InMemoryRaftKeyValueStore()),
                runIfCurrentGrant = provider::runIfCurrentGrant,
                runWithCurrentMessageGrant = provider::runWithCurrentMessageGrant,
            )
        val deliveredSeqs = mutableListOf<Long>()
        owner.registerGuardedActiveThreadSink(
            RealtimeGuardedActiveThreadSink { _, event ->
                RealtimeCursorPolicy.appliedSeqForEvent(event).takeIf { it > 0L }?.let(deliveredSeqs::add)
            },
        )
        ProcessRealtimeIngressCoordinator(
            events = RaftRealtimeEvents(bus),
            authorityProvider = provider,
            authorityFlow = provider.authorityFlow("process-realtime-host"),
            domainOwner = owner,
            authStore = auth,
            environmentStore = SharedApiEnvironmentStore(InMemoryRaftKeyValueStore()),
            mapper = RealtimeEventMapper(),
            nativeHost = host,
            scope = scope,
            onPresenceTransportConnected = { grant, epoch ->
                presenceConnections += grant.connectionFamilyId to epoch
            },
            onRoomsJoined = { grant, epoch -> roomsJoined += grant.connectionFamilyId to epoch },
        ).start()
        yield()

        bus.post(
            RaftRealtimeNotifyContract.EventName,
            presenceTransportEnvelope(authority, epoch = 3L),
        )
        yield()
        assertEquals(listOf(authority.witness.connectionFamilyId to 3L), presenceConnections)

        bus.post(
            RaftRealtimeNotifyContract.EventName,
            presenceTransportEnvelope(authority, epoch = 4L)
                .withEntry(RaftRealtimeNotifyContract.KeyAuthorityConnectionFamilyId, "retired-family"),
        )
        yield()
        assertEquals(
            listOf(authority.witness.connectionFamilyId to 3L),
            presenceConnections,
            "a stale native witness must not rebaseline presence",
        )

        bus.post(
            RaftRealtimeNotifyContract.EventName,
            presenceTransportEnvelope(authority, epoch = 3L)
                .withEntry(RaftRealtimeNotifyContract.KeyAction, RaftRealtimeNotifyContract.ActionRealtimeRoomsJoined),
        )
        bus.post(
            RaftRealtimeNotifyContract.EventName,
            presenceTransportEnvelope(authority, epoch = 0L)
                .withEntry(RaftRealtimeNotifyContract.KeyAction, RaftRealtimeNotifyContract.ActionRealtimeRoomsJoined),
        )
        bus.post(
            RaftRealtimeNotifyContract.EventName,
            presenceTransportEnvelope(authority, epoch = 5L)
                .withEntry(RaftRealtimeNotifyContract.KeyAction, RaftRealtimeNotifyContract.ActionRealtimeRoomsJoined)
                .withEntry(RaftRealtimeNotifyContract.KeyAuthorityConnectionFamilyId, "retired-family"),
        )
        yield()
        assertEquals(
            listOf(authority.witness.connectionFamilyId to 3L),
            roomsJoined,
            "rooms joined reaches shared with its epoch; no epoch or a stale witness is dropped",
        )
        assertEquals(listOf(authority.witness.connectionFamilyId to 3L), presenceConnections)

        bus.post(
            RaftRealtimeNotifyContract.EventName,
            eventEnvelope(
                authority,
                RealtimeSocketEvents.SyncResumeResponse,
                "{\"messages\":[],\"currentSeq\":42,\"hasMore\":false}",
            ),
        )
        yield()
        assertEquals(listOf(42L), deliveredSeqs)
        assertEquals(listOf(42L), host.advancedSeqs)

        bus.post(
            RaftRealtimeNotifyContract.EventName,
            eventEnvelope(
                authority,
                GeneratedRealtimeContract.SocketEvent.ServerMembershipRemoved,
                "{\"serverId\":\"server-b\"}",
            ),
        )
        yield()
        assertTrue(host.disconnects.isEmpty(), "mismatched envelope and terminal payload must fail closed")
        assertEquals("server-a", auth.readSession()?.selectedServerId)
        assertTrue(provider.runIfCurrentGrant(oldGrant) {})

        bus.post(
            RaftRealtimeNotifyContract.EventName,
            eventEnvelope(
                authority,
                GeneratedRealtimeContract.SocketEvent.ServerMembershipRemoved,
                "{\"serverId\":\"server-a\"}",
            ),
        )
        yield()

        assertEquals(listOf("server-a"), host.disconnects)
        assertNull(auth.readSession()?.selectedServerId)
        assertFalse(provider.runIfCurrentGrant(oldGrant) {})
        assertNull(provider.resolve(authority.witness, "server-a"))
        assertTrue(host.reconciliations.isNotEmpty())
        scope.cancel()
    }

    private fun eventEnvelope(
        authority: RealtimeScopeAuthority,
        eventName: String,
        rawPayload: String,
    ): JsonObject =
        buildJsonObject {
            put(RaftRealtimeNotifyContract.KeyEventName, eventName)
            put(RaftRealtimeNotifyContract.KeyRawPayload, rawPayload)
            put(RaftRealtimeNotifyContract.KeyServerId, "server-a")
            put(RaftRealtimeNotifyContract.KeyAuthorityScopeLeaseId, authority.witness.scopeLeaseId)
            put(RaftRealtimeNotifyContract.KeyAuthorityPrincipalId, authority.witness.principalId)
            put(RaftRealtimeNotifyContract.KeyAuthorityAuthGeneration, authority.witness.authGeneration)
            put(RaftRealtimeNotifyContract.KeyAuthorityConnectionFamilyId, authority.witness.connectionFamilyId)
        }

    private fun presenceTransportEnvelope(
        authority: RealtimeScopeAuthority,
        epoch: Long,
    ): JsonObject =
        buildJsonObject {
            put(
                RaftRealtimeNotifyContract.KeyAction,
                RaftRealtimeNotifyContract.ActionPresenceTransportConnected,
            )
            put(RaftRealtimeNotifyContract.KeyServerId, "server-a")
            put(RaftRealtimeNotifyContract.KeyPresenceTransportEpoch, epoch)
            put(RaftRealtimeNotifyContract.KeyAuthorityScopeLeaseId, authority.witness.scopeLeaseId)
            put(RaftRealtimeNotifyContract.KeyAuthorityPrincipalId, authority.witness.principalId)
            put(RaftRealtimeNotifyContract.KeyAuthorityAuthGeneration, authority.witness.authGeneration)
            put(RaftRealtimeNotifyContract.KeyAuthorityConnectionFamilyId, authority.witness.connectionFamilyId)
        }

    private class FakeEventBus : RaftEventBus {
        private val listeners = mutableMapOf<String, MutableList<(JsonObject?) -> Unit>>()

        override fun observe(
            eventName: String,
            crossProcess: Boolean,
            listener: (JsonObject?) -> Unit,
        ): RealtimeSubscription {
            listeners.getOrPut(eventName) { mutableListOf() } += listener
            return RealtimeSubscription { listeners[eventName]?.remove(listener) }
        }

        override fun post(eventName: String, eventData: JsonObject, crossProcess: Boolean, source: String) {
            listeners[eventName].orEmpty().toList().forEach { it(eventData) }
        }
    }

    private class FakeNativeHost : ProcessRealtimeNativeHost {
        val reconciliations = mutableListOf<RealtimeProcessDesiredSession?>()
        val disconnects = mutableListOf<String>()
        val advancedSeqs = mutableListOf<Long>()

        override fun reconcile(
            authority: RealtimeScopeAuthority?,
            desired: RealtimeProcessDesiredSession?,
            fallbackSocketUrl: String,
        ) {
            reconciliations += desired
        }

        override fun advanceCursor(serverId: String, appliedSeq: Long, grant: RealtimeGrantRecord) {
            advancedSeqs += appliedSeq
        }

        override fun disconnect(serverId: String, reason: String) {
            disconnects += serverId
        }
    }

    private class FakeAuthStore : RaftAuthSessionStore, RaftAuthSessionEventSource {
        private var session: StoredAuthSession? = null
        private val events = MutableSharedFlow<AuthSessionEvent>(extraBufferCapacity = 8)
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

        suspend fun replace(next: StoredAuthSession, event: AuthSessionEvent) {
            session = next
            events.emit(event)
        }
    }

    private fun session() =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "user@example.test", displayName = "User"),
            accessToken = "access",
            refreshToken = "refresh",
            selectedServerId = "server-a",
            servers = listOf(StoredAuthServer(id = "server-a", name = "A", slug = "a")),
        )
}
