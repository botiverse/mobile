package ai.slock.compose.data

import ai.slock.compose.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedMembersStoreTest {
    @Test
    fun ensureSuggestionsLoadsMembersAndChannelsOncePerServer() = runBlocking {
        val memberRepository = FakeMemberRepository()
        val channelRepository = FakeChannelRepository()
        val store = store(memberRepository, channelRepository)

        store.ensureSuggestionsForServer("server-1")
        store.ensureSuggestionsForServer("server-1")

        assertEquals(1, memberRepository.getMembersCalls)
        assertEquals(1, channelRepository.getChannelsCalls)
        assertEquals(SharedMembersLoadStatus.Loaded, store.state.value.memberStatus)
        assertEquals(SharedMembersLoadStatus.Loaded, store.state.value.channelStatus)
        assertEquals(listOf(SharedMembersMemberSuggestion("Cindy", "cindy")), store.state.value.memberSuggestions)
        assertEquals(listOf(SharedMembersChannelSuggestion("general", "team")), store.state.value.channelSuggestions)
    }

    @Test
    fun failedMemberLoadRetriesWithoutRefetchingLoadedChannels() = runBlocking {
        val memberRepository = FakeMemberRepository(failMembersFirstCalls = 1)
        val channelRepository = FakeChannelRepository()
        val store = store(memberRepository, channelRepository)

        store.ensureSuggestionsForServer("server-1")
        assertEquals(SharedMembersLoadStatus.Error, store.state.value.memberStatus)
        assertEquals(SharedMembersLoadStatus.Loaded, store.state.value.channelStatus)

        store.ensureSuggestionsForServer("server-1")

        assertEquals(2, memberRepository.getMembersCalls)
        assertEquals(1, channelRepository.getChannelsCalls)
        assertEquals(SharedMembersLoadStatus.Loaded, store.state.value.memberStatus)
        assertEquals(listOf(SharedMembersMemberSuggestion("Cindy", "cindy")), store.state.value.memberSuggestions)
    }

    @Test
    fun memberScopedMentionScopeLoadsChannelMembersWithoutServerMembers() = runBlocking {
        val memberRepository = FakeMemberRepository()
        memberRepository.channelMembersByChannel["dm-1"] =
            listOf(MemberProfile(MemberId("u2"), "Artin", "@artin", MemberKind.Human))
        val channelRepository = FakeChannelRepository()
        val store = store(memberRepository, channelRepository)

        store.ensureSuggestionsForServer(
            serverId = "server-1",
            mentionChannelId = "dm-1",
            memberScopedMentions = true
        )

        assertEquals(0, memberRepository.getMembersCalls)
        assertEquals(1, memberRepository.getChannelMembersCalls)
        assertEquals(1, channelRepository.getChannelsCalls)
        assertEquals("dm-1", store.state.value.mentionChannelId)
        assertEquals(true, store.state.value.memberScopedMentions)
        assertEquals(listOf(SharedMembersMemberSuggestion("Artin", "artin")), store.state.value.memberSuggestions)
        assertEquals(listOf(SharedMembersChannelSuggestion("general", "team")), store.state.value.channelSuggestions)
    }

    @Test
    fun channelMentionScopeMergesServerMembersWithChannelMembers() = runBlocking {
        val memberRepository =
            FakeMemberRepository(
                members =
                    listOf(
                        MemberProfile(MemberId("u1"), "Cindy", "cindy", MemberKind.Human),
                        MemberProfile(MemberId("u2"), "Build Agent", "build", MemberKind.Agent)
                    )
            )
        memberRepository.channelMembersByChannel["thread-channel-1"] =
            listOf(
                MemberProfile(MemberId("u1"), "Cindy Duplicate", "@cindy", MemberKind.Human),
                MemberProfile(MemberId("remote-1"), "Remote User", "@remote", MemberKind.Human)
            )
        val channelRepository = FakeChannelRepository()
        val store = store(memberRepository, channelRepository)

        store.ensureSuggestionsForServer(
            serverId = "server-1",
            mentionChannelId = "thread-channel-1"
        )

        assertEquals(1, memberRepository.getMembersCalls)
        assertEquals(1, memberRepository.getChannelMembersCalls)
        assertEquals(
            listOf(
                SharedMembersMemberSuggestion("Cindy", "cindy"),
                SharedMembersMemberSuggestion("Build Agent", "build"),
                SharedMembersMemberSuggestion("Remote User", "remote")
            ),
            store.state.value.memberSuggestions
        )
    }

    @Test
    fun switchingMentionScopeReloadsMembersWithoutRefetchingLoadedChannels() = runBlocking {
        val memberRepository = FakeMemberRepository()
        memberRepository.channelMembersByChannel["dm-1"] =
            listOf(MemberProfile(MemberId("u2"), "Artin", "@artin", MemberKind.Human))
        val channelRepository = FakeChannelRepository()
        val store = store(memberRepository, channelRepository)

        store.ensureSuggestionsForServer("server-1")
        store.ensureSuggestionsForServer(
            serverId = "server-1",
            mentionChannelId = "dm-1",
            memberScopedMentions = true
        )

        assertEquals(1, memberRepository.getMembersCalls)
        assertEquals(1, memberRepository.getChannelMembersCalls)
        assertEquals(1, channelRepository.getChannelsCalls)
        assertEquals(SharedMembersLoadStatus.Loaded, store.state.value.memberStatus)
        assertEquals(listOf(SharedMembersMemberSuggestion("Artin", "artin")), store.state.value.memberSuggestions)
        assertEquals(listOf(SharedMembersChannelSuggestion("general", "team")), store.state.value.channelSuggestions)
    }

    @Test
    fun switchingServerResetsLoadedSuggestionsAndReloads() = runBlocking {
        val memberRepository = FakeMemberRepository()
        val channelRepository = FakeChannelRepository()
        val store = store(memberRepository, channelRepository)

        store.ensureSuggestionsForServer("server-1")
        memberRepository.membersByServer["server-2"] =
            listOf(MemberProfile(MemberId("u2"), "Artin", "@artin", MemberKind.Human))
        channelRepository.channelsByServer["server-2"] =
            listOf(channel("channel-2", "#kmp", "shared"))

        store.ensureSuggestionsForServer("server-2")

        assertEquals("server-2", store.state.value.serverId)
        assertEquals(2, memberRepository.getMembersCalls)
        assertEquals(2, channelRepository.getChannelsCalls)
        assertEquals(listOf(SharedMembersMemberSuggestion("Artin", "artin")), store.state.value.memberSuggestions)
        assertEquals(listOf(SharedMembersChannelSuggestion("kmp", "shared")), store.state.value.channelSuggestions)
    }

    @Test
    fun suggestionsNormalizeTokensAndSkipArchivedChannels() = runBlocking {
        val memberRepository =
            FakeMemberRepository(
                members =
                    listOf(
                        MemberProfile(MemberId("u1"), "Cindy", "@cindy", MemberKind.Human),
                        MemberProfile(MemberId("u2"), "Cindy Duplicate", "cindy", MemberKind.Human),
                        MemberProfile(MemberId("a1"), "Build Agent", "", MemberKind.Agent)
                    )
            )
        val channelRepository =
            FakeChannelRepository(
                channels =
                    listOf(
                        channel("channel-1", "#general", "team"),
                        channel("channel-2", "general", "duplicate"),
                        channel("channel-3", "#old", "archived", archived = true)
                    )
            )
        val store = store(memberRepository, channelRepository)

        store.ensureSuggestionsForServer("server-1")

        assertEquals(
            listOf(
                SharedMembersMemberSuggestion("Cindy", "cindy"),
                SharedMembersMemberSuggestion("Build Agent", "build-agent")
            ),
            store.state.value.memberSuggestions
        )
        assertEquals(listOf(SharedMembersChannelSuggestion("general", "team")), store.state.value.channelSuggestions)
    }

    private fun store(
        memberRepository: FakeMemberRepository,
        channelRepository: FakeChannelRepository
    ): SharedMembersStore =
        SharedMembersStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            memberRepository = memberRepository,
            channelRepository = channelRepository,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )

    private class FakeMemberRepository(
        members: List<MemberProfile> = listOf(MemberProfile(MemberId("u1"), "Cindy", "cindy", MemberKind.Human)),
        private var failMembersFirstCalls: Int = 0
    ) : MemberRepository {
        val membersByServer = mutableMapOf("server-1" to members)
        val channelMembersByChannel = mutableMapOf<String, List<MemberProfile>>()
        var getMembersCalls = 0
        var getChannelMembersCalls = 0

        override suspend fun getMembers(
            priority: DataRequestPriority,
            serverId: ServerId
        ): DataResult<MemberSnapshot> {
            getMembersCalls += 1
            if (failMembersFirstCalls > 0) {
                failMembersFirstCalls -= 1
                return DataResult.Error(ScreenError("members_failed"))
            }
            return DataResult.Content(MemberSnapshot(server(serverId.value), membersByServer[serverId.value].orEmpty()))
        }

        override suspend fun getMemberProfile(priority: DataRequestPriority, memberId: MemberId): DataResult<MemberProfile> = error("unused")
        override suspend fun updateAgentProfile(priority: DataRequestPriority, request: AgentProfileUpdateRequest): DataResult<MemberProfile> = error("unused")
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
        override suspend fun deleteAgent(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentLifecycleActionResult> = error("unused")
        override suspend fun assignAgentMachine(priority: DataRequestPriority, request: AgentMachineAssignmentRequest): DataResult<AgentLifecycleActionResult> = error("unused")
        override suspend fun getChannelMembers(
            priority: DataRequestPriority,
            channelId: ChannelId
        ): DataResult<ChannelMemberSnapshot> {
            getChannelMembersCalls += 1
            return DataResult.Content(
                ChannelMemberSnapshot(
                    channelId = channelId,
                    members = channelMembersByChannel[channelId.value].orEmpty()
                )
            )
        }
        override suspend fun addChannelMember(priority: DataRequestPriority, request: AddChannelMemberRequest): DataResult<ChannelMemberActionResult> = error("unused")
        override suspend fun removeChannelMember(priority: DataRequestPriority, request: RemoveChannelMemberRequest): DataResult<ChannelMemberActionResult> = error("unused")
    }

    private class FakeChannelRepository(
        channels: List<ConversationSummary> = listOf(channel("channel-1", "general", "team"))
    ) : ChannelRepository {
        val channelsByServer = mutableMapOf("server-1" to channels)
        var getChannelsCalls = 0

        override suspend fun getChannels(serverId: ServerId): DataResult<ChannelSnapshot> {
            getChannelsCalls += 1
            return DataResult.Content(ChannelSnapshot(server(serverId.value), channelsByServer[serverId.value].orEmpty()))
        }

        override suspend fun getHome(serverId: ServerId): DataResult<HomeSnapshot> = error("unused")
        override suspend fun getDirectMessages(serverId: ServerId): DataResult<DirectMessageSnapshot> = error("unused")
        override suspend fun openAgentDirectMessage(agentId: MemberId): DataResult<OpenDirectMessageResult> = error("unused")
        override suspend fun openUserDirectMessage(userId: MemberId): DataResult<OpenDirectMessageResult> = error("unused")
        override suspend fun createChannel(request: CreateChannelRequest): DataResult<CreateChannelResult> = error("unused")
        override suspend fun joinChannel(channelId: ChannelId): DataResult<ChannelJoinResult> = error("unused")
        override suspend fun leaveChannel(channelId: ChannelId): DataResult<ChannelLeaveResult> = error("unused")
        override suspend fun archiveChannel(channelId: ChannelId): DataResult<ChannelArchiveResult> = error("unused")
        override suspend fun deleteChannel(channelId: ChannelId): DataResult<ChannelDeleteResult> = error("unused")
    }
}

private fun sampleSession(): StoredAuthSession =
    StoredAuthSession(
        user = StoredAuthUser(id = "user-1", email = "artin@slock.ai"),
        accessToken = "access",
        refreshToken = "refresh",
        selectedServerId = "server-1",
        servers = listOf(StoredAuthServer(id = "server-1", name = "Slock"))
    )

private fun server(id: String): ServerSummary = ServerSummary(ServerId(id), "Slock")

private fun channel(
    id: String,
    name: String,
    subtitle: String? = null,
    archived: Boolean = false
): ConversationSummary =
    ConversationSummary(
        threadId = ai.slock.compose.model.ThreadId(id),
        channelId = ChannelId(id),
        kind = ConversationKind.Channel,
        name = name,
        subtitle = subtitle,
        archived = archived
    )
