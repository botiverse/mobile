package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Hands afae9ebb: an agent created while the Search tab was kept alive could not be found in it.
 * The create force-refreshed the member directory, but Search's From list and its person / agent
 * hits were a copy taken when Search opened. They are the same directory and must always agree.
 */
class SharedHomeSearchSenderFiltersFollowDirectoryTest {
    @Test
    fun anAgentAddedToTheDirectoryAfterSearchOpenedIsSearchableWithoutReopeningSearch() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = MutableMemberRepository(listOf(member("user-a", "Artin", MemberKind.Human)))
        val members = SharedServerMembersStore(repository)
        val store =
            SharedHomeSearchSavedStore(
                authStore = InMemoryRaftAuthSessionStore(session()),
                savedRepository = EmptySavedRepository,
                searchRepository = EmptySearchRepository,
                serverMembersStore = members,
                scope = scope,
            )

        store.dispatch(SharedHomeSearchSavedIntent.OpenSearch)
        awaitSenderFilterIds(store, listOf("user-a"))

        // What Members' create does: the server now lists the agent, and the directory is
        // force-refreshed. Search is not reopened (the phone tab stays composed underneath).
        repository.members = repository.members + member("agent-new", "qa-new", MemberKind.Agent)
        members.refreshMembers(ServerId(ServerIdValue), DataRequestPriority.Background, force = true)

        awaitSenderFilterIds(store, listOf("user-a", "agent-new"))
        scope.cancel()
    }

    @Test
    fun aDirectoryOfAnotherServerDoesNotLeakIntoSearch() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = MutableMemberRepository(listOf(member("user-a", "Artin", MemberKind.Human)))
        val members = SharedServerMembersStore(repository)
        val store =
            SharedHomeSearchSavedStore(
                authStore = InMemoryRaftAuthSessionStore(session()),
                savedRepository = EmptySavedRepository,
                searchRepository = EmptySearchRepository,
                serverMembersStore = members,
                scope = scope,
            )
        store.dispatch(SharedHomeSearchSavedIntent.OpenSearch)
        awaitSenderFilterIds(store, listOf("user-a"))

        repository.members = listOf(member("other-agent", "Elsewhere", MemberKind.Agent))
        members.refreshMembers(ServerId("server-other"), DataRequestPriority.Background, force = true)

        assertEquals(listOf("user-a"), store.state.value.search.senderFilters.map { it.id })
        scope.cancel()
    }

    private suspend fun awaitSenderFilterIds(store: SharedHomeSearchSavedStore, expected: List<String>) {
        try {
            withTimeout(5_000) {
                while (store.state.value.search.senderFilters.map { it.id }.sorted() != expected.sorted()) delay(10)
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            assertEquals(expected.sorted(), store.state.value.search.senderFilters.map { it.id }.sorted())
        }
    }

    private fun session(): StoredAuthSession =
        authSessionFixture(
            userId = "user-a",
            servers = listOf(StoredAuthServer(id = ServerIdValue, name = ServerIdValue)),
            selectedServerId = ServerIdValue,
        )

    private fun member(id: String, displayName: String, kind: MemberKind): MemberProfile =
        MemberProfile(id = MemberId(id), displayName = displayName, handle = id, kind = kind)

    private class MutableMemberRepository(var members: List<MemberProfile>) : MemberRepository {
        override suspend fun getMembers(
            priority: DataRequestPriority,
            serverId: ServerId,
        ): DataResult<MemberSnapshot> =
            DataResult.Content(MemberSnapshot(server = ServerSummary(id = serverId, name = serverId.value), members = members))

        override suspend fun getMemberProfile(priority: DataRequestPriority, memberId: MemberId, serverId: ServerId?): DataResult<MemberProfile> = error("unused")
        override suspend fun updateAgentProfile(priority: DataRequestPriority, request: AgentProfileUpdateRequest): DataResult<MemberProfile> = error("unused")
        override suspend fun uploadAgentAvatar(priority: DataRequestPriority, request: AgentAvatarUploadRequest): DataResult<MemberProfile> = error("unused")
        override suspend fun getAgentDetail(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentDetailSnapshot> = error("unused")
        override suspend fun getAgentDirectMessages(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentDmSnapshot> = error("unused")
        override suspend fun getAgentReminders(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentReminderSnapshot> = error("unused")
        override suspend fun getAgentActivity(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentActivitySnapshot> = error("unused")
        override suspend fun getAgentWorkspaces(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentWorkspaceSnapshot> = error("unused")
        override suspend fun getAgentWorkspaceTree(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentWorkspaceTreeSnapshot> = error("unused")
        override suspend fun getAgentWorkspaceChildren(priority: DataRequestPriority, agentId: MemberId, dirPath: String): DataResult<AgentWorkspaceChildrenSnapshot> = error("unused")
        override suspend fun readAgentWorkspaceFile(priority: DataRequestPriority, agentId: MemberId, path: String): DataResult<AgentWorkspaceFilePreviewSnapshot> = error("unused")
        override suspend fun getAgentSkills(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentSkillsSnapshot> = error("unused")
        override suspend fun startAgent(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentLifecycleActionResult> = error("unused")
        override suspend fun stopAgent(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentLifecycleActionResult> = error("unused")
        override suspend fun resetAgent(priority: DataRequestPriority, request: AgentResetRequest): DataResult<AgentLifecycleActionResult> = error("unused")
        override suspend fun deleteAgent(priority: DataRequestPriority, agentId: MemberId, workspaceChoice: AgentArchiveWorkspaceChoice): DataResult<AgentLifecycleActionResult> = error("unused")
        override suspend fun assignAgentMachine(priority: DataRequestPriority, request: AgentMachineAssignmentRequest): DataResult<AgentLifecycleActionResult> = error("unused")
        override suspend fun getChannelMembers(priority: DataRequestPriority, channelId: ChannelId): DataResult<ChannelMemberSnapshot> = error("unused")
        override suspend fun addChannelMember(priority: DataRequestPriority, request: AddChannelMemberRequest): DataResult<ChannelMemberActionResult> = error("unused")
        override suspend fun removeChannelMember(priority: DataRequestPriority, request: RemoveChannelMemberRequest): DataResult<ChannelMemberActionResult> = error("unused")
    }

    private object EmptySearchRepository : SearchRepository {
        override suspend fun search(serverId: ServerId, query: String, offset: Int, limit: Int, options: SearchQueryOptions, cursor: String?): DataResult<SearchSnapshot> = DataResult.Empty(EmptyReason.NoInboxItems)
    }

    private companion object {
        const val ServerIdValue = "server-a"
    }
}
