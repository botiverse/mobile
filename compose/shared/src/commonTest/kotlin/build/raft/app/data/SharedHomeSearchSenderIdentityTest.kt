package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedHomeSearchSenderIdentityTest {
    @Test
    fun memberProjectionUsesRequestServerAndRejectsContradictoryOrMissingIdentity() {
        val filters =
            listOf(
                member("self", "Current User"),
                member("other", "Other User", explicitServerId = "server-a"),
                member("cross-server", "Wrong Server", explicitServerId = "server-b"),
                member("", "Missing Identity"),
            ).toSearchSenderFilters(
                currentUserId = "self",
                sourceServerId = ServerId("server-a"),
            )

        assertEquals(listOf("self", "other"), filters.map { it.id })
        assertEquals(listOf(ServerId("server-a"), ServerId("server-a")), filters.map { it.sourceServerId })
        assertTrue(filters.first().isSelf)
        assertFalse(filters.last().isSelf)
    }

    @Test
    fun accountAndServerSwitchDiscardsCompletionFromOldOwnerWithoutASecondLoad() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val authStore = InMemoryRaftAuthSessionStore(sessionFixture("user-a", "server-a"))
        val members = DeferredMemberRepository()
        val store = store(scope, authStore, members)

        store.dispatch(SharedHomeSearchSavedIntent.OpenSearch)
        assertEquals(listOf(ServerId("server-a")), members.requestedServers())
        authStore.saveSession(sessionFixture("user-b", "server-b"))

        members.complete("server-a", listOf(member("user-a", "Same Name")))

        assertEquals(emptyList(), store.state.value.search.senderFilters)
        assertFalse(store.state.value.search.senderFiltersLoading)
        scope.cancel()
    }

    @Test
    fun successorLoadWinsWhenSameHandleExistsOnTwoServersAndOldProducerIgnoresCancellation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val authStore = InMemoryRaftAuthSessionStore(sessionFixture("user-a", "server-a"))
        val members = DeferredMemberRepository()
        val store = store(scope, authStore, members)

        store.dispatch(SharedHomeSearchSavedIntent.OpenSearch)
        authStore.saveSession(sessionFixture("user-b", "server-b"))
        store.dispatch(SharedHomeSearchSavedIntent.OpenSearch)
        assertEquals(listOf(ServerId("server-a"), ServerId("server-b")), members.requestedServers())

        members.complete("server-b", listOf(member("user-b", "Same Name", handle = "same")))
        members.complete("server-a", listOf(member("user-a", "Same Name", handle = "same")))

        val filter = store.state.value.search.senderFilters.single()
        assertEquals("user-b", filter.id)
        assertEquals("same", filter.handle)
        assertEquals(ServerId("server-b"), filter.sourceServerId)
        assertTrue(filter.isSelf)
        scope.cancel()
    }

    private fun store(
        scope: CoroutineScope,
        authStore: RaftAuthSessionStore,
        members: MemberRepository,
    ): SharedHomeSearchSavedStore =
        SharedHomeSearchSavedStore(
            authStore = authStore,
            savedRepository = EmptySavedRepository,
            searchRepository = EmptySearchRepository,
            memberRepository = members,
            scope = scope,
        )

    private fun sessionFixture(userId: String, serverId: String): StoredAuthSession =
        authSessionFixture(
            userId = userId,
            servers = listOf(StoredAuthServer(id = serverId, name = serverId)),
            selectedServerId = serverId,
        )

    private fun member(
        id: String,
        displayName: String,
        handle: String = id,
        explicitServerId: String? = null,
    ): MemberProfile =
        MemberProfile(
            id = MemberId(id),
            displayName = displayName,
            handle = handle,
            kind = MemberKind.Human,
            serverId = explicitServerId?.let(::ServerId),
        )

    /**
     * commonTest avoids kotlinx-coroutines-test. The continuation is the explicit
     * interleaving barrier and intentionally ignores cancellation so request-token fencing runs.
     */
    private class DeferredMemberRepository : MemberRepository {
        private data class Request(
            val serverId: ServerId,
            val continuation: Continuation<DataResult<MemberSnapshot>>,
        )

        private val requests = mutableListOf<Request>()

        override suspend fun getMembers(
            priority: DataRequestPriority,
            serverId: ServerId,
        ): DataResult<MemberSnapshot> =
            suspendCoroutine { continuation -> requests += Request(serverId, continuation) }

        fun requestedServers(): List<ServerId> = requests.map { it.serverId }

        fun complete(serverId: String, members: List<MemberProfile>) {
            val request = requests.first { it.serverId == ServerId(serverId) }
            request.continuation.resume(
                DataResult.Content(
                    MemberSnapshot(
                        server = ServerSummary(id = request.serverId, name = request.serverId.value),
                        members = members,
                    )
                )
            )
        }

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

    private object EmptySavedRepository : SavedRepository {
        override suspend fun loadInitial(serverId: ServerId, limit: Int): DataResult<SavedSnapshot> = DataResult.Empty(EmptyReason.NoInboxItems)
        override suspend fun loadMore(serverId: ServerId, offset: Int, limit: Int): DataResult<SavedSnapshot> = DataResult.Empty(EmptyReason.NoInboxItems)
        override suspend fun saveMessage(serverId: ServerId, messageId: MessageId): DataResult<SavedMessageMutationResult> = error("unused")
        override suspend fun unsaveMessage(serverId: ServerId, messageId: MessageId): DataResult<SavedMessageMutationResult> = error("unused")
    }

    private object EmptySearchRepository : SearchRepository {
        override suspend fun search(serverId: ServerId, query: String, offset: Int, limit: Int, options: SearchQueryOptions): DataResult<SearchSnapshot> = DataResult.Empty(EmptyReason.NoInboxItems)
    }
}
