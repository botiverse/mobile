package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SharedServerChannelMembersOwnerTest {
    @Test
    fun sameServerAndChannelIdsDoNotReuseAnotherAccountOrEnvironmentCache() = runBlocking {
        val repository = OwnerTestMemberRepository()
        val ownerState = MutableStateFlow<ProcessServerMembersOwner?>(owner(environmentId = "env-a", accountId = "account-a"))
        var writeGeneration = 0L
        repository.channelMembers = listOf(member("member-a"))
        val store = store(repository, ownerState) { writeGeneration }

        val first = store.loadChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID))
        ownerState.value = owner(environmentId = "env-a", accountId = "account-b")
        writeGeneration += 1
        repository.channelMembers = listOf(member("member-b"))
        val second = store.loadChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID))
        ownerState.value = owner(environmentId = "env-b/", accountId = "account-b")
        writeGeneration += 1
        repository.channelMembers = listOf(member("member-c"))
        val third = store.loadChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID))

        assertIs<DataResult.Content<ChannelMemberSnapshot>>(first)
        assertIs<DataResult.Content<ChannelMemberSnapshot>>(second)
        assertIs<DataResult.Content<ChannelMemberSnapshot>>(third)
        assertEquals(3, repository.getChannelMembersCalls)
        assertEquals(listOf("member-c"), third.data.members.map { it.id.value })
        assertEquals(
            listOf("member-c"),
            store.currentChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID)).map { it.id.value }
        )
        val visibleSlice = store.channelMemberSliceFlow(ServerId(SERVER_ID), ChannelId(CHANNEL_ID)).first()
        assertEquals("env-b", visibleSlice.environmentId)
        assertEquals("account-b", visibleSlice.accountId)
        assertEquals(SERVER_ID, visibleSlice.serverId)
        assertEquals(CHANNEL_ID, visibleSlice.channelId)
        assertEquals(null, store.currentMemberProfile(ServerId(SERVER_ID), "member-a"))
        assertEquals(null, store.currentMemberProfile(ServerId(SERVER_ID), "member-b"))
    }

    @Test
    fun sameOwnerUsesCachedChannelSlice() = runBlocking {
        val repository = OwnerTestMemberRepository().apply {
            channelMembers = listOf(member("member-a"))
        }
        val ownerState = MutableStateFlow<ProcessServerMembersOwner?>(owner("env-a", "account-a"))
        val store = store(repository, ownerState) { 7L }

        val first = store.loadChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID))
        repository.channelMembers = listOf(member("should-not-load"))
        val cached = store.loadChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID))

        assertIs<DataResult.Content<ChannelMemberSnapshot>>(first)
        assertIs<DataResult.Content<ChannelMemberSnapshot>>(cached)
        assertEquals(1, repository.getChannelMembersCalls)
        assertEquals(listOf("member-a"), cached.data.members.map { it.id.value })
    }

    @Test
    fun lateOldOwnerResponseCannotPublish() = runBlocking {
        val repository = OwnerTestMemberRepository()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        repository.channelMembersLoader = {
            started.complete(Unit)
            release.await()
            listOf(member("member-a"))
        }
        val ownerState = MutableStateFlow<ProcessServerMembersOwner?>(owner("env-a", "account-a"))
        var writeGeneration = 0L
        val store = store(repository, ownerState) { writeGeneration }

        val oldLoad = async {
            store.loadChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID))
        }
        started.await()
        ownerState.value = owner("env-a", "account-b")
        writeGeneration += 1

        assertEquals(emptyList(), store.currentChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID)))
        assertEquals(
            emptyList(),
            store.channelMemberSliceFlow(ServerId(SERVER_ID), ChannelId(CHANNEL_ID)).first().members
        )
        release.complete(Unit)
        val result = oldLoad.await()

        assertIs<DataResult.Error>(result)
        assertEquals("channel_members_owner_stale", result.error.code)
        assertEquals(emptyList(), store.currentChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID)))
        assertEquals(null, store.currentMemberProfile(ServerId(SERVER_ID), "member-a"))
    }

    @Test
    fun clearAndSameAccountReloginRetiresPreviousGeneration() = runBlocking {
        val repository = OwnerTestMemberRepository().apply {
            channelMembers = listOf(member("before-clear"))
        }
        val sameOwner = owner("env-a", "account-a")
        val ownerState = MutableStateFlow<ProcessServerMembersOwner?>(sameOwner)
        var writeGeneration = 3L
        val store = store(repository, ownerState) { writeGeneration }

        store.loadChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID))
        ownerState.value = null

        assertEquals(emptyList(), store.currentChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID)))
        assertEquals(
            emptyList(),
            store.channelMemberSliceFlow(ServerId(SERVER_ID), ChannelId(CHANNEL_ID)).first().members
        )

        writeGeneration += 1
        ownerState.value = sameOwner
        repository.channelMembers = listOf(member("after-relogin"))
        val reloaded = store.loadChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID))

        assertIs<DataResult.Content<ChannelMemberSnapshot>>(reloaded)
        assertEquals(2, repository.getChannelMembersCalls)
        assertEquals(listOf("after-relogin"), reloaded.data.members.map { it.id.value })
    }

    @Test
    fun routeOwnedLateResponseCannotPublishAfterSameOwnerGenerationRetires() = runBlocking {
        val repository = OwnerTestMemberRepository()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        repository.channelMembersLoader = {
            started.complete(Unit)
            release.await()
            listOf(member("stale-member"))
        }
        val processOwner = owner("env-a", "account-a")
        val ownerState = MutableStateFlow<ProcessServerMembersOwner?>(processOwner)
        val registry = ProcessMemberPresenceRegistry()
        val store = store(repository, ownerState, registry = registry) { registry.writeGeneration }
        val routeOwner =
            AuthenticatedRouteOwner(
                environmentId = processOwner.environmentId,
                accountId = processOwner.accountId,
                serverId = processOwner.serverId
            )

        val oldLoad = async {
            store.loadChannelMembers(
                owner = routeOwner,
                channelId = ChannelId(CHANNEL_ID),
                force = true
            )
        }
        started.await()
        registry.clearAll(reason = "test_route_same_owner_relogin")
        release.complete(Unit)
        val result = oldLoad.await()

        assertIs<DataResult.Error>(result)
        assertEquals("channel_members_owner_stale", result.error.code)
        assertEquals(
            emptyList(),
            store.currentChannelMemberSlice(routeOwner, ChannelId(CHANNEL_ID)).members
        )
    }

    @Test
    fun mountedFlowObservesGenerationRetirementWhenOwnerTupleIsUnchanged() = runBlocking {
        val repository = OwnerTestMemberRepository().apply {
            channelMembers = listOf(member("before-clear"))
        }
        val ownerState = MutableStateFlow<ProcessServerMembersOwner?>(owner("env-a", "account-a"))
        val registry = ProcessMemberPresenceRegistry()
        val store = store(repository, ownerState, registry = registry) { registry.writeGeneration }

        store.loadChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID))
        val observed = mutableListOf<SharedServerChannelMembersSlice>()
        val initialObserved = CompletableDeferred<Unit>()
        val collection = launch {
            store.channelMemberSliceFlow(ServerId(SERVER_ID), ChannelId(CHANNEL_ID))
                .take(2)
                .collect { slice ->
                    observed += slice
                    if (observed.size == 1) initialObserved.complete(Unit)
                }
        }

        initialObserved.await()
        registry.clearAll(reason = "test_same_owner_relogin")
        collection.join()

        assertEquals(listOf("before-clear"), observed[0].members.map { it.id.value })
        assertEquals(emptyList(), observed[1].members)
        assertEquals(registry.writeGeneration, observed[1].ownerWriteGeneration)

        repository.channelMembers = listOf(member("after-relogin"))
        val reloaded = store.loadChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID))
        assertIs<DataResult.Content<ChannelMemberSnapshot>>(reloaded)
        assertEquals(2, repository.getChannelMembersCalls)
        assertEquals(listOf("after-relogin"), reloaded.data.members.map { it.id.value })
    }

    @Test
    fun addingAPeerServerHumanMakesTheirJointRosterRowLocal() = runBlocking {
        // Joint channel: the roster holds this human only through the peer server.
        val repository = OwnerTestMemberRepository().apply {
            channelMembers =
                listOf(member("peer-human").copy(serverId = ServerId("server-peer"), serverName = "Peer", serverSlug = "peer"))
        }
        val ownerState = MutableStateFlow<ProcessServerMembersOwner?>(owner("env-a", "account-a"))
        val store = store(repository, ownerState) { 1L }
        store.loadChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID))

        val result =
            store.addChannelMember(
                ServerId(SERVER_ID),
                AddChannelMemberRequest(ChannelId(CHANNEL_ID), MemberId("peer-human"), MemberKind.Human)
            )

        assertIs<DataResult.Content<ChannelMemberActionResult>>(result)
        val row = store.currentChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID)).single()
        assertEquals(ServerId(SERVER_ID), row.serverId)
        assertEquals(null, row.serverName)
    }

    private fun store(
        repository: OwnerTestMemberRepository,
        ownerState: MutableStateFlow<ProcessServerMembersOwner?>,
        registry: ProcessMemberPresenceRegistry = ProcessMemberPresenceRegistry(),
        writeGeneration: () -> Long
    ): SharedServerMembersStore =
        SharedServerMembersStore(
            memberRepository = repository,
            presenceRegistry = registry,
            presenceOwner = { ownerState.value },
            presenceOwnerFlow = { ownerState },
            presenceWrite = {
                ownerState.value?.let { owner -> ProcessPresenceWrite(owner, writeGeneration()) }
            }
        )

    private class OwnerTestMemberRepository : UnsupportedMemberRepository() {
        var channelMembers: List<MemberProfile> = emptyList()
        var channelMembersLoader: (suspend (ChannelId) -> List<MemberProfile>)? = null
        var getChannelMembersCalls: Int = 0

        override suspend fun getChannelMembers(
            priority: DataRequestPriority,
            channelId: ChannelId
        ): DataResult<ChannelMemberSnapshot> {
            getChannelMembersCalls += 1
            return DataResult.Content(
                ChannelMemberSnapshot(
                    channelId = channelId,
                    members = channelMembersLoader?.invoke(channelId) ?: channelMembers
                )
            )
        }
    }

    private companion object {
        const val SERVER_ID = "server-shared"
        const val CHANNEL_ID = "channel-shared"

        fun owner(environmentId: String, accountId: String): ProcessServerMembersOwner =
            ProcessServerMembersOwner(
                environmentId = environmentId,
                accountId = accountId,
                serverId = SERVER_ID
            )

        fun member(id: String): MemberProfile =
            MemberProfile(
                id = MemberId(id),
                displayName = id,
                handle = id,
                kind = MemberKind.Human,
                serverId = ServerId(SERVER_ID)
            )
    }
}

private abstract class UnsupportedMemberRepository : MemberRepository {
    override suspend fun getMembers(priority: DataRequestPriority, serverId: ServerId): DataResult<MemberSnapshot> =
        error("unused")

    override suspend fun getMemberProfile(
        priority: DataRequestPriority,
        memberId: MemberId,
        serverId: ServerId?
    ): DataResult<MemberProfile> = error("unused")

    override suspend fun updateAgentProfile(
        priority: DataRequestPriority,
        request: AgentProfileUpdateRequest
    ): DataResult<MemberProfile> = error("unused")

    override suspend fun uploadAgentAvatar(
        priority: DataRequestPriority,
        request: AgentAvatarUploadRequest
    ): DataResult<MemberProfile> = error("unused")

    override suspend fun getAgentDetail(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentDetailSnapshot> =
        error("unused")

    override suspend fun getAgentDirectMessages(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentDmSnapshot> =
        error("unused")

    override suspend fun getAgentReminders(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentReminderSnapshot> =
        error("unused")

    override suspend fun getAgentActivity(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentActivitySnapshot> =
        error("unused")

    override suspend fun getAgentWorkspaces(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentWorkspaceSnapshot> =
        error("unused")

    override suspend fun getAgentWorkspaceTree(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentWorkspaceTreeSnapshot> =
        error("unused")

    override suspend fun getAgentWorkspaceChildren(
        priority: DataRequestPriority,
        agentId: MemberId,
        dirPath: String
    ): DataResult<AgentWorkspaceChildrenSnapshot> = error("unused")

    override suspend fun readAgentWorkspaceFile(
        priority: DataRequestPriority,
        agentId: MemberId,
        path: String
    ): DataResult<AgentWorkspaceFilePreviewSnapshot> = error("unused")

    override suspend fun getAgentSkills(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentSkillsSnapshot> =
        error("unused")

    override suspend fun startAgent(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentLifecycleActionResult> =
        error("unused")

    override suspend fun stopAgent(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentLifecycleActionResult> =
        error("unused")

    override suspend fun resetAgent(priority: DataRequestPriority, request: AgentResetRequest): DataResult<AgentLifecycleActionResult> =
        error("unused")

    override suspend fun deleteAgent(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentLifecycleActionResult> =
        error("unused")

    override suspend fun assignAgentMachine(
        priority: DataRequestPriority,
        request: AgentMachineAssignmentRequest
    ): DataResult<AgentLifecycleActionResult> = error("unused")

    override suspend fun addChannelMember(
        priority: DataRequestPriority,
        request: AddChannelMemberRequest
    ): DataResult<ChannelMemberActionResult> =
        DataResult.Content(ChannelMemberActionResult(channelId = request.channelId, memberId = request.memberId))

    override suspend fun removeChannelMember(
        priority: DataRequestPriority,
        request: RemoveChannelMemberRequest
    ): DataResult<ChannelMemberActionResult> = error("unused")
}
