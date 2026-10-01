package build.raft.app.data

import build.raft.app.data.realtime.GeneratedRealtimeContract
import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeAppliedSeqCursorStore
import build.raft.app.data.realtime.RealtimeCursorPolicy
import build.raft.app.data.realtime.RealtimeProcessDesiredSession
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.data.realtime.SlockRealtimeNotifyContract
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProcessRealtimeIngressCoordinatorTest {
    @Test
    fun normalTerminalFrameIsAcceptedWithoutAppShellAndTombstonesOldWitness() = runBlocking {
        val auth = FakeAuthStore()
        val registry =
            RealtimeAuthorityRegistry(
                KeyValueRealtimeAuthRevisionStore(InMemorySlockKeyValueStore()),
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
        val owner =
            SharedRealtimeDomainEventOwner(
                cursorStore = RealtimeAppliedSeqCursorStore(InMemorySlockKeyValueStore()),
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
            events = KuiklyRealtimeEvents(bus),
            authorityProvider = provider,
            authorityFlow = provider.authorityFlow("process-realtime-host"),
            domainOwner = owner,
            authStore = auth,
            environmentStore = SharedApiEnvironmentStore(InMemorySlockKeyValueStore()),
            mapper = RealtimeEventMapper(),
            nativeHost = host,
            scope = scope,
            onPresenceTransportConnected = { grant, epoch ->
                presenceConnections += grant.connectionFamilyId to epoch
            },
        ).start()
        yield()

        bus.post(
            SlockRealtimeNotifyContract.EventName,
            presenceTransportEnvelope(authority, epoch = 3L),
        )
        yield()
        assertEquals(listOf(authority.witness.connectionFamilyId to 3L), presenceConnections)

        bus.post(
            SlockRealtimeNotifyContract.EventName,
            presenceTransportEnvelope(authority, epoch = 4L)
                .put(SlockRealtimeNotifyContract.KeyAuthorityConnectionFamilyId, "retired-family"),
        )
        yield()
        assertEquals(
            listOf(authority.witness.connectionFamilyId to 3L),
            presenceConnections,
            "a stale native witness must not rebaseline presence",
        )

        bus.post(
            SlockRealtimeNotifyContract.EventName,
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
            SlockRealtimeNotifyContract.EventName,
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
            SlockRealtimeNotifyContract.EventName,
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
    ): JSONObject =
        JSONObject()
            .put(SlockRealtimeNotifyContract.KeyEventName, eventName)
            .put(SlockRealtimeNotifyContract.KeyRawPayload, rawPayload)
            .put(SlockRealtimeNotifyContract.KeyServerId, "server-a")
            .put(SlockRealtimeNotifyContract.KeyAuthorityScopeLeaseId, authority.witness.scopeLeaseId)
            .put(SlockRealtimeNotifyContract.KeyAuthorityPrincipalId, authority.witness.principalId)
            .put(SlockRealtimeNotifyContract.KeyAuthorityAuthGeneration, authority.witness.authGeneration)
            .put(SlockRealtimeNotifyContract.KeyAuthorityConnectionFamilyId, authority.witness.connectionFamilyId)

    private fun presenceTransportEnvelope(
        authority: RealtimeScopeAuthority,
        epoch: Long,
    ): JSONObject =
        JSONObject()
            .put(
                SlockRealtimeNotifyContract.KeyAction,
                SlockRealtimeNotifyContract.ActionPresenceTransportConnected,
            )
            .put(SlockRealtimeNotifyContract.KeyServerId, "server-a")
            .put(SlockRealtimeNotifyContract.KeyPresenceTransportEpoch, epoch)
            .put(SlockRealtimeNotifyContract.KeyAuthorityScopeLeaseId, authority.witness.scopeLeaseId)
            .put(SlockRealtimeNotifyContract.KeyAuthorityPrincipalId, authority.witness.principalId)
            .put(SlockRealtimeNotifyContract.KeyAuthorityAuthGeneration, authority.witness.authGeneration)
            .put(SlockRealtimeNotifyContract.KeyAuthorityConnectionFamilyId, authority.witness.connectionFamilyId)

    private class FakeEventBus : SlockEventBus {
        private val listeners = mutableMapOf<String, MutableList<(JSONObject?) -> Unit>>()

        override fun observe(
            eventName: String,
            crossProcess: Boolean,
            listener: (JSONObject?) -> Unit,
        ): RealtimeSubscription {
            listeners.getOrPut(eventName) { mutableListOf() } += listener
            return RealtimeSubscription { listeners[eventName]?.remove(listener) }
        }

        override fun post(eventName: String, eventData: JSONObject, crossProcess: Boolean, source: String) {
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

    private class FakeAuthStore : SlockAuthSessionStore, SlockAuthSessionEventSource {
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
