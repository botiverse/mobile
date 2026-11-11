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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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

    /**
     * Task #14c: the host's forwarded `channel:joined` / `channel:join_denied` (the exact envelope Android's
     * AndroidAppScopeRealtimeHostContext and iOS's RaftIosRealtimeHost post) reaches shared with its witness-checked
     * grant, the socket's epoch and the channel id; no channel id, no epoch or a stale witness is dropped.
     */
    @Test
    fun aForwardedChannelJoinAckReachesSharedWithItsEpochAndChannelId() = runBlocking {
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
        val bus = FakeEventBus()
        val acks = mutableListOf<List<Any>>()
        val roomsJoined = mutableListOf<Long>()
        ProcessRealtimeIngressCoordinator(
            events = RaftRealtimeEvents(bus),
            authorityProvider = provider,
            authorityFlow = provider.authorityFlow("process-realtime-host"),
            domainOwner =
                SharedRealtimeDomainEventOwner(
                    cursorStore = RealtimeAppliedSeqCursorStore(InMemoryRaftKeyValueStore()),
                    runIfCurrentGrant = provider::runIfCurrentGrant,
                    runWithCurrentMessageGrant = provider::runWithCurrentMessageGrant,
                ),
            authStore = auth,
            environmentStore = SharedApiEnvironmentStore(InMemoryRaftKeyValueStore()),
            mapper = RealtimeEventMapper(),
            nativeHost = FakeNativeHost(),
            scope = scope,
            onRoomsJoined = { _, epoch -> roomsJoined += epoch },
            onChannelJoinAck = { grant, epoch, channelId, joined ->
                acks += listOf(grant.connectionFamilyId, epoch, channelId, joined)
            },
        ).start()
        yield()
        fun ack(action: String, epoch: Long, channelId: String?): JsonObject {
            var envelope = presenceTransportEnvelope(authority, epoch).withEntry(RaftRealtimeNotifyContract.KeyAction, action)
            if (channelId != null) envelope = envelope.withEntry(RaftRealtimeNotifyContract.KeyChannelId, channelId)
            return envelope
        }
        val family = authority.witness.connectionFamilyId

        bus.post(RaftRealtimeNotifyContract.EventName, ack(RaftRealtimeNotifyContract.ActionRealtimeChannelJoined, 3L, "thread-1"))
        bus.post(RaftRealtimeNotifyContract.EventName, ack(RaftRealtimeNotifyContract.ActionRealtimeChannelJoinDenied, 3L, "thread-2"))
        yield()
        assertEquals(listOf(listOf<Any>(family, 3L, "thread-1", true), listOf<Any>(family, 3L, "thread-2", false)), acks)

        // Dropped: no channel id, a blank / padded one, no epoch, a stale witness.
        bus.post(RaftRealtimeNotifyContract.EventName, ack(RaftRealtimeNotifyContract.ActionRealtimeChannelJoined, 3L, null))
        bus.post(RaftRealtimeNotifyContract.EventName, ack(RaftRealtimeNotifyContract.ActionRealtimeChannelJoined, 3L, " "))
        bus.post(RaftRealtimeNotifyContract.EventName, ack(RaftRealtimeNotifyContract.ActionRealtimeChannelJoined, 3L, " thread-1"))
        bus.post(RaftRealtimeNotifyContract.EventName, ack(RaftRealtimeNotifyContract.ActionRealtimeChannelJoined, 0L, "thread-1"))
        bus.post(
            RaftRealtimeNotifyContract.EventName,
            ack(RaftRealtimeNotifyContract.ActionRealtimeChannelJoined, 4L, "thread-1")
                .withEntry(RaftRealtimeNotifyContract.KeyAuthorityConnectionFamilyId, "retired-family"),
        )
        yield()
        assertEquals(2, acks.size, acks.toString())
        assertTrue(roomsJoined.isEmpty(), "a join ack is never a rooms:joined")
        scope.cancel()
    }

    /**
     * Reconnect jitter (Tenny 10-27, web #11453), wired as the provider wires it: the roster refetch of a
     * presence transport RE-connect waits the client's 0–20 s delay, while the same socket's message
     * catch-up (`sync:resume:response`) is delivered at once. The first connect fetches immediately and
     * the pre-connect roster cache is retired immediately on every connect.
     */
    @Test
    fun reconnectDelaysTheRosterRefetchButNotTheMessageCatchUp() = runTest {
        val auth = FakeAuthStore()
        val registry =
            RealtimeAuthorityRegistry(
                KeyValueRealtimeAuthRevisionStore(InMemoryRaftKeyValueStore()),
                processInstanceId = "process-ingress-test",
            )
        val provider = RealtimeAuthorityProvider(registry, auth, auth, { "env-test" })
        provider.start(backgroundScope)
        provider.registerScope("process-realtime-host", "process-owner")
        auth.replace(session(), AuthSessionEvent.Authenticated(generation = 1))
        runCurrent()
        val authority = assertNotNull(provider.authorityFlow("process-realtime-host").value)
        val domainOwner =
            SharedRealtimeDomainEventOwner(
                cursorStore = RealtimeAppliedSeqCursorStore(InMemoryRaftKeyValueStore()),
                runIfCurrentGrant = provider::runIfCurrentGrant,
                runWithCurrentMessageGrant = provider::runWithCurrentMessageGrant,
            )
        val deliveredSeqs = mutableListOf<Pair<Long, Long>>()
        domainOwner.registerGuardedActiveThreadSink(
            RealtimeGuardedActiveThreadSink { _, event ->
                RealtimeCursorPolicy.appliedSeqForEvent(event).takeIf { it > 0L }?.let { deliveredSeqs += it to currentTime }
            },
        )
        val bus = FakeEventBus()
        val rosterFetches = mutableListOf<Long>()
        val core =
            ProcessServerMembersCore(
                fetchMembers = { _, serverId ->
                    rosterFetches += currentTime
                    DataResult.Content(
                        MemberSnapshot(
                            build.raft.app.model.ServerSummary(id = serverId, name = "A", slug = "a"),
                            emptyList(),
                        )
                    )
                },
                scope = backgroundScope,
            )
        val store = SharedServerMembersStore(memberRepository = PresenceNoopMemberRepository, processCore = core)
        val invalidations = mutableListOf<Long>()
        val jitterSeed = 11
        val rosterRefetch =
            PresenceTransportRosterRefetch(
                jitter = RaftReconnectRefetchJitter(scope = backgroundScope, random = kotlin.random.Random(jitterSeed)),
                invalidate = { owner, reason ->
                    invalidations += currentTime
                    core.invalidate(owner, reason)
                },
                storesForServer = { listOf(store) },
            )
        ProcessRealtimeIngressCoordinator(
            events = RaftRealtimeEvents(bus),
            authorityProvider = provider,
            authorityFlow = provider.authorityFlow("process-realtime-host"),
            domainOwner = domainOwner,
            authStore = auth,
            environmentStore = SharedApiEnvironmentStore(InMemoryRaftKeyValueStore()),
            mapper = RealtimeEventMapper(),
            nativeHost = FakeNativeHost(),
            scope = backgroundScope,
            onPresenceTransportConnected = { grant, epoch ->
                rosterRefetch.onTransportRebaselined(
                    owner = ProcessServerMembersOwner(grant.environmentId, grant.principalId, grant.serverId),
                    transport = ProcessPresenceTransportEpoch(grant.connectionFamilyId, epoch),
                    rebaselineResult = ProcessPresenceTransportRebaselineResult.Applied,
                )
            },
        ).start()
        runCurrent()

        bus.post(RaftRealtimeNotifyContract.EventName, presenceTransportEnvelope(authority, epoch = 3L))
        runCurrent()
        assertEquals(listOf(0L), rosterFetches, "the first connect fetches the roster at once")

        val delayMs = reconnectRefetchJitterMillis(kotlin.random.Random(jitterSeed))
        bus.post(RaftRealtimeNotifyContract.EventName, presenceTransportEnvelope(authority, epoch = 4L))
        bus.post(
            RaftRealtimeNotifyContract.EventName,
            eventEnvelope(
                authority,
                RealtimeSocketEvents.SyncResumeResponse,
                "{\"messages\":[],\"currentSeq\":42,\"hasMore\":false}",
            ),
        )
        runCurrent()
        assertEquals(listOf(42L to 0L), deliveredSeqs, "message catch-up is not delayed")
        assertEquals(listOf(0L, 0L), invalidations, "the pre-connect roster is retired on every connect")
        assertEquals(listOf(0L), rosterFetches, "the reconnect roster refetch waits for the jitter")

        advanceTimeBy(RECONNECT_REFETCH_JITTER_MAX_MILLIS + 1L)
        assertEquals(listOf(0L, delayMs), rosterFetches, "the roster refetches once the jitter delay ends")
    }

    // Phase 3 multi-session realtime: the process reconcile (token rotation included) sends the windows' set.

    @Test
    fun twoWindowClaimsReconcileBothServersAsOneMultiSessionSet() = runTest {
        val fixture = multiSessionFixture(enabled = true)
        fixture.demand.claim("shell-a", "server-a")
        fixture.demand.claim("shell-b", "server-b")
        runCurrent()

        val last = fixture.host.reconciles.last()
        assertEquals(setOf("server-a", "server-b"), last.serverIds.toSet())
        assertTrue(last.multiSession, "the set goes out as a multi-session reconcile")
    }

    @Test
    fun releasingAWindowShrinksTheSet() = runTest {
        val fixture = multiSessionFixture(enabled = true)
        fixture.demand.claim("shell-a", "server-a")
        fixture.demand.claim("shell-b", "server-b")
        runCurrent()

        fixture.demand.release("shell-b")
        runCurrent()

        assertEquals(setOf("server-a"), fixture.host.reconciles.last().serverIds.toSet())
        assertTrue(fixture.host.reconciles.last().multiSession)
    }

    @Test
    fun tokenRotationKeepsEveryWindowServer() = runTest {
        val fixture = multiSessionFixture(enabled = true)
        fixture.demand.claim("shell-a", "server-a")
        fixture.demand.claim("shell-b", "server-b")
        runCurrent()
        val before = fixture.host.reconciles.size

        fixture.auth.replace(
            twoServerSession().copy(accessToken = "access-rotated"),
            AuthSessionEvent.TokensRotated(generation = 2),
        )
        runCurrent()

        assertTrue(fixture.host.reconciles.size > before, "the rotated token reconciles again")
        val last = fixture.host.reconciles.last()
        assertEquals("access-rotated", last.accessToken)
        assertEquals(setOf("server-a", "server-b"), last.serverIds.toSet(), "rotation must not fall back to the selected server")
        assertTrue(last.multiSession)
    }

    @Test
    fun settingOffSendsOnlyTheSelectedServerAndIgnoresClaims() = runTest {
        val fixture = multiSessionFixture(enabled = false)
        val before = fixture.host.reconciles.size
        fixture.demand.claim("shell-a", "server-a")
        fixture.demand.claim("shell-b", "server-b")
        runCurrent()
        assertEquals(before, fixture.host.reconciles.size, "with the setting off a claim never reconciles")

        fixture.auth.replace(
            twoServerSession().copy(accessToken = "access-rotated"),
            AuthSessionEvent.TokensRotated(generation = 2),
        )
        runCurrent()

        val last = fixture.host.reconciles.last()
        assertEquals(setOf("server-a"), last.serverIds.toSet())
        assertFalse(last.multiSession)

        val count = fixture.host.reconciles.size
        fixture.demand.onVisibility("shell-b", visible = false, nowMillis = 1_000L + currentTime)
        advanceTimeBy(RaftRealtimeSessionDemand.HiddenWindowSessionGraceMillis + 1L)
        runCurrent()
        assertEquals(count, fixture.host.reconciles.size, "with the setting off a hidden window's grace never reconciles")
    }

    @Test
    fun turningTheSettingOffRevertsToTheSelectedServerOnce() = runTest {
        val fixture = multiSessionFixture(enabled = true)
        fixture.demand.claim("shell-a", "server-a")
        fixture.demand.claim("shell-b", "server-b")
        runCurrent()

        fixture.enabled = false
        fixture.demand.notifySettingChanged()
        runCurrent()
        val reverted = fixture.host.reconciles.last()
        assertEquals(setOf("server-a"), reverted.serverIds.toSet())
        assertFalse(reverted.multiSession)

        val count = fixture.host.reconciles.size
        fixture.demand.release("shell-b")
        runCurrent()
        assertEquals(count, fixture.host.reconciles.size, "once reverted, claims no longer reconcile")
    }

    @Test
    fun aWindowHiddenPastItsGraceLeavesTheSetWithoutAnotherChange() = runTest {
        val fixture = multiSessionFixture(enabled = true)
        fixture.demand.claim("shell-a", "server-a")
        fixture.demand.claim("shell-b", "server-b")
        fixture.demand.onVisibility("shell-b", visible = false, nowMillis = 1_000L + currentTime)
        runCurrent()
        assertEquals(setOf("server-a", "server-b"), fixture.host.reconciles.last().serverIds.toSet())
        val before = fixture.host.reconciles.size

        advanceTimeBy(RaftRealtimeSessionDemand.HiddenWindowSessionGraceMillis)
        runCurrent()
        assertEquals(before, fixture.host.reconciles.size, "still inside the grace: nothing to reconcile")

        advanceTimeBy(1L)
        runCurrent()
        val last = fixture.host.reconciles.last()
        assertEquals(before + 1, fixture.host.reconciles.size, "the expiry alone re-reconciles")
        assertEquals(listOf("server-a"), last.serverIds)
        assertTrue(last.multiSession)

        advanceTimeBy(RaftRealtimeSessionDemand.HiddenWindowSessionGraceMillis * 2)
        runCurrent()
        assertEquals(before + 1, fixture.host.reconciles.size, "no hidden window left inside a grace: no more wake-ups")
    }

    // Hands a92fbb13: two restored windows, the one behind reconciled last. With multi-session realtime
    // the window in front keeps its own server's session and that server's frames are accepted.
    @Test
    fun twoRestoredWindowsTheFrontWindowServerStaysConnectedAndItsFramesApply() = runTest {
        val fixture = multiSessionFixture(enabled = true)
        // Restore order: shell-b (in front) comes up, then shell-a restores behind it and reconciles last.
        fixture.demand.claim("shell-b", "server-b")
        fixture.demand.onVisibility("shell-b", visible = true, nowMillis = 1_000L)
        fixture.demand.claim("shell-a", "server-a")
        fixture.demand.onVisibility("shell-a", visible = false, nowMillis = 1_000L)
        runCurrent()

        val last = fixture.host.reconciles.last()
        assertEquals("server-b", last.serverIds.first(), "the front window's server comes first")
        assertTrue("server-a" in last.serverIds, "the window behind keeps its session too")

        fixture.bus.post(
            RaftRealtimeNotifyContract.EventName,
            eventEnvelope(
                fixture.authority,
                RealtimeSocketEvents.SyncResumeResponse,
                "{\"messages\":[],\"currentSeq\":7,\"hasMore\":false}",
                serverId = "server-b",
            ),
        )
        runCurrent()
        assertEquals(listOf("server-b" to 7L), fixture.host.advancedCursors)
    }

    private class MultiSessionFixture(
        val auth: FakeAuthStore,
        val host: FakeNativeHost,
        val bus: FakeEventBus,
        val demand: RaftRealtimeSessionDemand,
        val authority: RealtimeScopeAuthority,
    ) {
        var enabled: Boolean = false
    }

    private suspend fun kotlinx.coroutines.test.TestScope.multiSessionFixture(enabled: Boolean): MultiSessionFixture {
        val auth = FakeAuthStore()
        val registry =
            RealtimeAuthorityRegistry(
                KeyValueRealtimeAuthRevisionStore(InMemoryRaftKeyValueStore()),
                processInstanceId = "process-ingress-test",
            )
        val provider = RealtimeAuthorityProvider(registry, auth, auth, { "env-test" })
        provider.start(backgroundScope)
        provider.registerScope("process-realtime-host", "process-owner")
        auth.replace(twoServerSession(), AuthSessionEvent.Authenticated(generation = 1))
        runCurrent()
        val authority = assertNotNull(provider.authorityFlow("process-realtime-host").value)
        val host = FakeNativeHost()
        val bus = FakeEventBus()
        val fixture = MultiSessionFixture(auth, host, bus, RaftRealtimeSessionDemand(), authority)
        fixture.enabled = enabled
        ProcessRealtimeIngressCoordinator(
            events = RaftRealtimeEvents(bus),
            authorityProvider = provider,
            authorityFlow = provider.authorityFlow("process-realtime-host"),
            domainOwner =
                SharedRealtimeDomainEventOwner(
                    cursorStore = RealtimeAppliedSeqCursorStore(InMemoryRaftKeyValueStore()),
                    runIfCurrentGrant = provider::runIfCurrentGrant,
                    runWithCurrentMessageGrant = provider::runWithCurrentMessageGrant,
                ),
            authStore = auth,
            environmentStore = SharedApiEnvironmentStore(InMemoryRaftKeyValueStore()),
            mapper = RealtimeEventMapper(),
            nativeHost = host,
            scope = backgroundScope,
            sessionDemand = fixture.demand,
            multiSessionEnabled = { fixture.enabled },
            // Wall clock = virtual time (+1 s), so a hidden window's grace runs out on the test scheduler.
            nowMillis = { 1_000L + currentTime },
        ).start()
        runCurrent()
        return fixture
    }

    private fun eventEnvelope(
        authority: RealtimeScopeAuthority,
        eventName: String,
        rawPayload: String,
        serverId: String = "server-a",
    ): JsonObject =
        buildJsonObject {
            put(RaftRealtimeNotifyContract.KeyEventName, eventName)
            put(RaftRealtimeNotifyContract.KeyRawPayload, rawPayload)
            put(RaftRealtimeNotifyContract.KeyServerId, serverId)
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

    private data class Reconcile(val serverIds: List<String>, val accessToken: String?, val multiSession: Boolean)

    private class FakeNativeHost : ProcessRealtimeNativeHost {
        val reconciliations = mutableListOf<RealtimeProcessDesiredSession?>()
        val reconciles = mutableListOf<Reconcile>()
        val disconnects = mutableListOf<String>()
        val advancedSeqs = mutableListOf<Long>()
        val advancedCursors = mutableListOf<Pair<String, Long>>()

        override fun reconcile(
            authority: RealtimeScopeAuthority?,
            desired: RealtimeProcessDesiredSession?,
            fallbackSocketUrl: String,
            multiSession: Boolean,
        ) {
            reconciliations += desired
            reconciles += Reconcile(desired?.serverIds.orEmpty().toList(), desired?.accessToken, multiSession)
        }

        override fun advanceCursor(serverId: String, appliedSeq: Long, grant: RealtimeGrantRecord) {
            advancedSeqs += appliedSeq
            advancedCursors += serverId to appliedSeq
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

    private fun twoServerSession() =
        session().copy(
            servers =
                listOf(
                    StoredAuthServer(id = "server-a", name = "A", slug = "a"),
                    StoredAuthServer(id = "server-b", name = "B", slug = "b"),
                ),
        )

    private fun session() =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "user@example.test", displayName = "User"),
            accessToken = "access",
            refreshToken = "refresh",
            selectedServerId = "server-a",
            servers = listOf(StoredAuthServer(id = "server-a", name = "A", slug = "a")),
        )
}
