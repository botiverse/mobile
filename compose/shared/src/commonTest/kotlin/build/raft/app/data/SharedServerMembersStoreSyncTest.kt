package build.raft.app.data

import build.raft.app.SharedThreadStoreTestFakes.FakeMemberRepository
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Incremental sync `server_member` / `agent` rows patch the loaded roster (no full reload); with sync
 * on, a reconnect / foreground return reads only `/agents` (presence), and a roster read older than
 * an applied change never publishes over it.
 */
class SharedServerMembersStoreSyncTest {
    private val serverId = ServerId("server-1")

    private fun profile(id: String, name: String, kind: MemberKind) =
        MemberProfile(id = MemberId(id), displayName = name, handle = name.lowercase(), kind = kind)

    @Test
    fun `upserts replace by kind and id or join and deletes leave`() = runTest {
        val repository =
            FakeMemberRepository(
                serverMembers =
                    listOf(
                        profile("u-1", "Ann", MemberKind.Human),
                        profile("a-1", "Bot", MemberKind.Agent),
                        profile("a-2", "Old", MemberKind.Agent),
                    )
            )
        val store = SharedServerMembersStore(repository)
        assertIs<DataResult.Content<Unit>>(store.refreshMembers(serverId, force = true))

        val changed =
            store.applySyncedRosterRows(
                serverId = serverId,
                upserts = listOf(profile("u-1", "Ann B", MemberKind.Human), profile("a-3", "New", MemberKind.Agent)),
                deletes = listOf(MemberId("a-2") to MemberKind.Agent),
            )

        assertTrue(changed)
        assertEquals(
            listOf("u-1" to "Ann B", "a-1" to "Bot", "a-3" to "New"),
            store.currentServerMembers(serverId).map { it.id.value to it.displayName },
        )
        assertEquals(SharedServerMembersLoadStatus.Loaded, store.currentServerMembersEntry(serverId).status)
        // One network read: the load. Applying rows never fetched.
        assertEquals(1, repository.serverMemberRequests.size)
    }

    @Test
    fun `the same rows again change nothing`() = runTest {
        val store = SharedServerMembersStore(FakeMemberRepository(serverMembers = listOf(profile("u-1", "Ann", MemberKind.Human))))
        store.refreshMembers(serverId, force = true)
        val before = store.state.value

        assertFalse(store.applySyncedRosterRows(serverId, listOf(profile("u-1", "Ann", MemberKind.Human)), emptyList()))
        assertEquals(before, store.state.value)
    }

    @Test
    fun `a roster that is not loaded is left for its load`() = runTest {
        val store = SharedServerMembersStore(FakeMemberRepository())

        assertFalse(store.applySyncedRosterRows(serverId, listOf(profile("u-1", "Ann", MemberKind.Human)), emptyList()))
        assertNull(store.state.value.entryFor(serverId))
    }

    @Test
    fun `an agents refresh reads only agents and keeps the humans`() = runTest {
        val repository =
            SyncRosterRepository(
                members = listOf(human("u-1", "Ann"), agent("a-1", "Bot"), agent("a-2", "Old")),
                agents = listOf(agent("a-1", "Bot 2"), agent("a-3", "New")),
            )
        val store = syncStore(repository)
        store.refreshMembers(serverId, force = true)

        assertIs<DataResult.Content<Unit>>(store.refreshAgents(serverId, reason = "test"))

        assertEquals(
            listOf("u-1" to "Ann", "a-1" to "Bot 2", "a-3" to "New"),
            store.currentServerMembers(serverId).map { it.id.value to it.displayName },
        )
        assertEquals(1, repository.memberCalls)
        assertEquals(1, repository.agentCalls)
    }

    @Test
    fun `an agents refresh of a roster not loaded takes the full load`() = runTest {
        val repository = SyncRosterRepository(members = listOf(human("u-1", "Ann"), agent("a-1", "Bot")))
        val store = syncStore(repository)

        store.refreshAgents(serverId, reason = "test")

        assertEquals(1, repository.memberCalls)
        assertEquals(0, repository.agentCalls)
        assertEquals(SharedServerMembersLoadStatus.Loaded, store.currentServerMembersEntry(serverId).status)
    }

    @Test
    fun `an agents read older than a synced agent row is dropped`() = runTest {
        val repository =
            SyncRosterRepository(members = listOf(human("u-1", "Ann"), agent("a-1", "Bot")), agents = listOf(agent("a-1", "Stale")))
        val store = syncStore(repository)
        store.refreshMembers(serverId, force = true)
        val gate = CompletableDeferred<Unit>().also { repository.agentsGate = it }
        val refresh = async { store.refreshAgents(serverId, reason = "test") }
        runCurrent()
        assertEquals(1, repository.agentCalls)

        store.applySyncedRosterRows(serverId, listOf(agent("a-1", "Synced")), emptyList())
        gate.complete(Unit)
        refresh.await()

        assertEquals(listOf("Ann", "Synced"), store.currentServerMembers(serverId).map { it.displayName })
    }

    @Test
    fun `a forced roster read older than a synced row does not publish over it`() = runTest {
        val repository = SyncRosterRepository(members = listOf(human("u-1", "Ann"), agent("a-1", "Bot")))
        val store = syncStore(repository)
        store.refreshMembers(serverId, force = true)
        // R0 reads the roster before the change and answers after it.
        val gate = CompletableDeferred<Unit>().also { repository.membersGate = it }
        val olderLoad = async { store.refreshMembers(serverId, force = true) }
        runCurrent()
        repository.members = listOf(human("u-1", "Ann B"), agent("a-1", "Bot"))

        store.applySyncedRosterRows(serverId, listOf(human("u-1", "Ann B")), emptyList())
        gate.complete(Unit)
        olderLoad.await()

        assertEquals(listOf("Ann B", "Bot"), store.currentServerMembers(serverId).map { it.displayName })
        // The older answer was dropped and the load read the server again.
        assertEquals(3, repository.memberCalls)
    }

    @Test
    fun `with sync on a reconnect refetches only agents and a first connect the full roster`() = runTest {
        val repository = SyncRosterRepository(members = listOf(human("u-1", "Ann"), agent("a-1", "Bot")), agents = listOf(agent("a-1", "Bot")))
        val store = syncStore(repository)
        val refetch = rosterRefetch(store, agentsOnly = true)

        connect(refetch, epoch = 1L)
        assertEquals(1 to 0, repository.memberCalls to repository.agentCalls)

        connect(refetch, epoch = 2L)
        advanceTimeBy(RECONNECT_REFETCH_JITTER_MAX_MILLIS + 1L)
        runCurrent()
        assertEquals(1 to 1, repository.memberCalls to repository.agentCalls)
    }

    @Test
    fun `with sync off a reconnect still refetches the full roster`() = runTest {
        val repository = SyncRosterRepository(members = listOf(human("u-1", "Ann"), agent("a-1", "Bot")))
        val store = syncStore(repository)
        val refetch = rosterRefetch(store, agentsOnly = false)

        connect(refetch, epoch = 1L)
        connect(refetch, epoch = 2L)
        advanceTimeBy(RECONNECT_REFETCH_JITTER_MAX_MILLIS + 1L)
        runCurrent()

        assertEquals(2 to 0, repository.memberCalls to repository.agentCalls)
    }

    /**
     * Staging run 10-27 (Tenny): with sync on a reconnect must not retire the roster, so the Home
     * reloads that follow it (catch-up, sync `home=true`) read neither `/members` nor `/machines`
     * (both come with the members read): only the delayed `/agents`.
     */
    @Test
    fun `with sync on a reconnect and an immediate Home reload read only the delayed agents and machines`() = runTest {
        val repository = SyncRosterRepository(members = listOf(human("u-1", "Ann"), agent("a-1", "Bot")), agents = listOf(agent("a-1", "Bot")))
        val core = syncCore(repository)
        val store = SharedServerMembersStore(memberRepository = repository, processCore = core, incrementalSyncCoversRoster = { true })
        var machineReads = 0
        val refetch =
            rosterRefetch(store, agentsOnly = true, invalidate = core::invalidate, refreshMachines = { machineReads += 1 })
        connect(refetch, epoch = 1L)
        assertEquals(1 to 0, repository.memberCalls to repository.agentCalls)
        assertEquals(0, machineReads, "a first connect's full roster read carries the machines")

        connect(refetch, epoch = 2L)
        homeReload(store)
        homeReload(store)
        runCurrent()
        assertEquals(1 to 0, repository.memberCalls to repository.agentCalls, "no roster read outside the jitter")
        assertEquals(0, machineReads, "no machines read outside the jitter")

        advanceTimeBy(RECONNECT_REFETCH_JITTER_MAX_MILLIS + 1L)
        runCurrent()
        homeReload(store)
        assertEquals(1 to 1, repository.memberCalls to repository.agentCalls)
        assertEquals(1, machineReads)
        assertEquals(SharedServerMembersLoadStatus.Loaded, store.currentServerMembersEntry(serverId).status)
    }

    @Test
    fun `with sync off a Home reload after a reconnect reads the roster at once as before`() = runTest {
        val repository = SyncRosterRepository(members = listOf(human("u-1", "Ann"), agent("a-1", "Bot")))
        val core = syncCore(repository)
        val store = SharedServerMembersStore(memberRepository = repository, processCore = core)
        val refetch = rosterRefetch(store, agentsOnly = false, invalidate = core::invalidate)
        connect(refetch, epoch = 1L)

        connect(refetch, epoch = 2L)
        homeReload(store)
        assertEquals(2 to 0, repository.memberCalls to repository.agentCalls)

        advanceTimeBy(RECONNECT_REFETCH_JITTER_MAX_MILLIS + 1L)
        runCurrent()
        assertEquals(3 to 0, repository.memberCalls to repository.agentCalls)
    }

    @Test
    fun `with sync on a Home reload with no roster held still loads it`() = runTest {
        val repository = SyncRosterRepository(members = listOf(human("u-1", "Ann")))
        val store = SharedServerMembersStore(memberRepository = repository, processCore = syncCore(repository), incrementalSyncCoversRoster = { true })

        homeReload(store)

        assertEquals(1, repository.memberCalls)
        assertEquals(SharedServerMembersLoadStatus.Loaded, store.currentServerMembersEntry(serverId).status)
    }

    @Test
    fun `a normal roster load in flight is dropped when a sync reload is sent after it`() = runTest {
        val repository = SyncRosterRepository(members = listOf(human("u-1", "Ann"), agent("a-1", "Bot")))
        val core = syncCore(repository)
        val store = SharedServerMembersStore(memberRepository = repository, processCore = core)
        store.refreshMembers(serverId, force = true)
        // A normal (non-sync) load is sent first and answers last, with the list before the change.
        val gate = CompletableDeferred<Unit>().also { repository.membersGate = it }
        val normalLoad = async { store.refreshMembers(serverId, force = true, reason = "members_page") }
        runCurrent()
        repository.members = listOf(human("u-1", "Ann B"), agent("a-1", "Bot"))

        // The provider's sync reload: retire what is in flight, then one fresh forced read (no join).
        core.invalidate(ProcessServerMembersOwner("local", "local", serverId.value), reason = "sync_resnapshot")
        store.refreshMembers(serverId, force = true, reason = "sync_resnapshot")
        assertEquals(listOf("Ann B", "Bot"), store.currentServerMembers(serverId).map { it.displayName })
        assertEquals(3, repository.memberCalls)

        gate.complete(Unit)
        normalLoad.await()
        assertEquals(listOf("Ann B", "Bot"), store.currentServerMembers(serverId).map { it.displayName })
    }

    @Test
    fun `a first roster load in flight when member rows arrive reads the server again`() = runTest {
        val repository = SyncRosterRepository(members = listOf(human("u-1", "Ann")))
        val core = syncCore(repository)
        val store = SharedServerMembersStore(memberRepository = repository, processCore = core)
        val gate = CompletableDeferred<Unit>().also { repository.membersGate = it }
        val firstLoad = async { store.refreshMembers(serverId) }
        runCurrent()
        repository.members = listOf(human("u-1", "Ann B"))

        // The router's supersedeRosterLoad: no roster to patch, so the load in flight is retired.
        core.invalidate(ProcessServerMembersOwner("local", "local", serverId.value), reason = "sync_changes")
        gate.complete(Unit)
        firstLoad.await()

        assertEquals(listOf("Ann B"), store.currentServerMembers(serverId).map { it.displayName })
        assertEquals(2, repository.memberCalls)
    }

    @Test
    fun `a failed reconnect pull falls back to the full roster refetch after the jitter`() = runTest {
        val repository = SyncRosterRepository(members = listOf(human("u-1", "Ann"), agent("a-1", "Bot")), agents = listOf(agent("a-1", "Bot")))
        val store = syncStore(repository)
        val refetch = rosterRefetch(store, agentsOnly = true)
        connect(refetch, epoch = 1L)
        connect(refetch, epoch = 2L)

        // The pull of the reconnect failed before the agents-only refetch ran: the full refetch takes its place.
        refetch.onSyncPullFailed(
            owner = ProcessServerMembersOwner("local", "local", serverId.value),
            transport = ProcessPresenceTransportEpoch(connectionFamilyId = "family-1", epoch = 2L),
        )
        runCurrent()
        assertEquals(1 to 0, repository.memberCalls to repository.agentCalls)
        advanceTimeBy(RECONNECT_REFETCH_JITTER_MAX_MILLIS + 1L)
        runCurrent()
        assertEquals(2 to 0, repository.memberCalls to repository.agentCalls)
    }

    @Test
    fun `the pull fallback never runs at once even for a session that never connected`() = runTest {
        val repository = SyncRosterRepository(members = listOf(human("u-1", "Ann")))
        val store = syncStore(repository)
        val refetch = rosterRefetch(store, agentsOnly = true, maxJitterMillis = 5_000L)

        refetch.onSyncPullFailed(
            owner = ProcessServerMembersOwner("local", "local", serverId.value),
            transport = ProcessPresenceTransportEpoch(connectionFamilyId = "family-1", epoch = 1L),
        )
        runCurrent()
        assertEquals(0, repository.memberCalls)
        advanceTimeBy(RECONNECT_REFETCH_JITTER_MAX_MILLIS + 1L)
        runCurrent()
        assertEquals(1, repository.memberCalls)
        assertTrue(testScheduler.currentTime > 0L)
    }

    private fun human(id: String, name: String) = profile(id, name, MemberKind.Human)

    private fun agent(id: String, name: String) = profile(id, name, MemberKind.Agent)

    /** The process core on the test scheduler, so its flights run under runCurrent / advanceTimeBy. */
    private fun TestScope.syncCore(repository: SyncRosterRepository) =
        ProcessServerMembersCore(
            fetchMembers = repository::getMembers,
            fetchAgents = repository::getServerAgents,
            scope = backgroundScope,
        )

    private fun TestScope.syncStore(repository: SyncRosterRepository) =
        SharedServerMembersStore(memberRepository = repository, processCore = syncCore(repository))

    /** [agentsOnly] is the provider's per-connect decision; each [connect] passes it. */
    private fun TestScope.rosterRefetch(
        store: SharedServerMembersStore,
        agentsOnly: Boolean,
        maxJitterMillis: Long = RECONNECT_REFETCH_JITTER_MAX_MILLIS,
        invalidate: suspend (ProcessServerMembersOwner, String) -> Unit = { _, _ -> },
        refreshMachines: suspend (String) -> Unit = {},
    ) = SyncRosterRefetch(
        agentsOnly,
        PresenceTransportRosterRefetch(
            jitter =
                RaftReconnectRefetchJitter(scope = backgroundScope, random = kotlin.random.Random(3), maxMillis = maxJitterMillis),
            invalidate = invalidate,
            storesForServer = { listOf(store) },
            refreshMachines = refreshMachines,
        ),
    )

    private class SyncRosterRefetch(val agentsOnly: Boolean, val refetch: PresenceTransportRosterRefetch) {
        fun onSyncPullFailed(owner: ProcessServerMembersOwner, transport: ProcessPresenceTransportEpoch) =
            refetch.onSyncPullFailed(owner, transport)
    }

    /** What every Home load runs ([SharedHomeDataSource.refreshAgentDirectMessageStatuses]). */
    private suspend fun homeReload(store: SharedServerMembersStore) {
        store.refreshMembersForHomeProjection(serverId, DataRequestPriority.Background, reason = "home_agent_status_projection")
    }

    private suspend fun connect(refetch: SyncRosterRefetch, epoch: Long) {
        refetch.refetch.onTransportRebaselined(
            owner = ProcessServerMembersOwner("local", "local", serverId.value),
            transport = ProcessPresenceTransportEpoch(connectionFamilyId = "family-1", epoch = epoch),
            rebaselineResult = ProcessPresenceTransportRebaselineResult.Applied,
            agentsOnlyOnReconnect = refetch.agentsOnly,
        )
    }

    /** A roster endpoint pair whose answers can be held: [membersGate] / [agentsGate] hold the next call. */
    private class SyncRosterRepository(
        var members: List<MemberProfile>,
        var agents: List<MemberProfile> = emptyList(),
    ) : MemberRepository by FakeMemberRepository() {
        var memberCalls = 0
        var agentCalls = 0
        var membersGate: CompletableDeferred<Unit>? = null
        var agentsGate: CompletableDeferred<Unit>? = null

        override suspend fun getMembers(priority: DataRequestPriority, serverId: ServerId): DataResult<MemberSnapshot> {
            memberCalls += 1
            val answer = members
            membersGate?.also { membersGate = null }?.await()
            return DataResult.Content(MemberSnapshot(ServerSummary(id = serverId, name = "Slock"), answer))
        }

        override suspend fun getServerAgents(priority: DataRequestPriority, serverId: ServerId): DataResult<List<MemberProfile>> {
            agentCalls += 1
            val answer = agents
            agentsGate?.also { agentsGate = null }?.await()
            return DataResult.Content(answer)
        }
    }
}
