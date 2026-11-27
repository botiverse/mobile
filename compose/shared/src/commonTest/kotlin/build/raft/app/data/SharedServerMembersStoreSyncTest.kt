package build.raft.app.data

import build.raft.app.model.AvatarReference
import build.raft.app.SharedThreadStoreTestFakes.FakeMemberRepository
import build.raft.app.members.currentMemberAvatarWireValue
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
    fun `an agents refresh brings an avatar changed on the web into the roster`() = runTest {
        // Edgar (10-29): set to the Desk `pencil` on the web; agent:updated -> refreshAgents.
        val before = agent("a-1", "Edgar").copy(avatar = AvatarReference.fromWireValue("pixel:antenna"))
        val repository =
            SyncRosterRepository(
                members = listOf(human("u-1", "Ann"), before),
                agents = listOf(before.copy(avatar = AvatarReference.fromWireValue("pixel:pencil"))),
            )
        val store = syncStore(repository)
        store.refreshMembers(serverId, force = true)
        assertEquals("pixel:antenna", store.state.value.currentMemberAvatarWireValue(serverId, "a-1"))

        store.refreshAgents(serverId, reason = "realtime_agent:updated")

        assertEquals("pixel:pencil", store.state.value.currentMemberAvatarWireValue(serverId, "a-1"))
        assertEquals(1, repository.memberCalls)
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
