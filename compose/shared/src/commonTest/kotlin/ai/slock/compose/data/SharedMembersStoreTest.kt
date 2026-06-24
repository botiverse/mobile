package ai.slock.compose.data

import ai.slock.compose.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
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
    fun refreshSuggestionsKeepsLoadedMembersWhenRequestFails() = runBlocking {
        val memberRepository = FakeMemberRepository()
        val channelRepository = FakeChannelRepository()
        val store = store(memberRepository, channelRepository)

        store.ensureSuggestionsForServer("server-1")
        memberRepository.failMembersNextCalls = 1
        store.ensureSuggestionsForServer("server-1", force = true)

        assertEquals(2, memberRepository.getMembersCalls)
        assertEquals(SharedMembersLoadStatus.Error, store.state.value.memberStatus)
        assertEquals("members_failed", store.state.value.memberErrorCode)
        assertEquals(listOf(SharedMembersMemberSuggestion("Cindy", "cindy")), store.state.value.memberSuggestions)
        assertEquals(
            listOf(SharedMembersMemberSuggestion("Cindy", "cindy")),
            store.state.value.selectMemberSuggestionSlice().suggestions
        )
    }

    @Test
    fun refreshScopedSuggestionsKeepsLoadedMembersWhenChannelReturnsEmpty() = runBlocking {
        val memberRepository = FakeMemberRepository()
        memberRepository.channelMembersByChannel["dm-1"] =
            listOf(MemberProfile(MemberId("u2"), "Artin", "@artin", MemberKind.Human))
        val store = store(memberRepository, FakeChannelRepository())

        store.ensureSuggestionsForServer(
            serverId = "server-1",
            mentionChannelId = "dm-1",
            memberScopedMentions = true
        )
        memberRepository.channelMembersByChannel["dm-1"] = emptyList()
        store.ensureSuggestionsForServer(
            serverId = "server-1",
            force = true,
            mentionChannelId = "dm-1",
            memberScopedMentions = true
        )

        assertEquals(2, memberRepository.getChannelMembersCalls)
        assertEquals(SharedMembersLoadStatus.Loaded, store.state.value.memberStatus)
        assertEquals(listOf(SharedMembersMemberSuggestion("Artin", "artin")), store.state.value.memberSuggestions)
        assertEquals(
            listOf(SharedMembersMemberSuggestion("Artin", "artin")),
            store.state.value.selectMemberSuggestionSlice("dm-1", memberScopedMentions = true).suggestions
        )
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
                MemberProfile(
                    id = MemberId("remote-1"),
                    displayName = "Remote User",
                    handle = "@remote",
                    kind = MemberKind.Human,
                    serverId = ServerId("server-2"),
                    serverName = "Remote Lab",
                    serverSlug = "remote-lab"
                )
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
                SharedMembersMemberSuggestion("Cindy", "cindy", inMentionChannel = true),
                SharedMembersMemberSuggestion(
                    "Build Agent",
                    "build",
                    inMentionChannel = false,
                    kind = MemberKind.Agent,
                    avatarWireValue = "pixel:robot",
                    memberId = "u2"
                ),
                SharedMembersMemberSuggestion(
                    "Remote User",
                    "remote",
                    inMentionChannel = true,
                    sourceServerLabel = "Remote Lab"
                )
            ),
            store.state.value.memberSuggestions
        )
    }

    @Test
    fun channelMentionScopeMarksMembersOutsideChannel() = runBlocking {
        val memberRepository =
            FakeMemberRepository(
                members =
                    listOf(
                        MemberProfile(MemberId("u1"), "Cindy", "cindy", MemberKind.Human),
                        MemberProfile(MemberId("u2"), "Outside Agent", "outside", MemberKind.Agent)
                    )
            )
        memberRepository.channelMembersByChannel["channel-1"] =
            listOf(MemberProfile(MemberId("u1"), "Cindy", "@cindy", MemberKind.Human))
        val store = store(memberRepository, FakeChannelRepository())

        store.ensureSuggestionsForServer(serverId = "server-1", mentionChannelId = "channel-1")

        assertEquals(
            listOf(
                SharedMembersMemberSuggestion("Cindy", "cindy", inMentionChannel = true),
                SharedMembersMemberSuggestion(
                    "Outside Agent",
                    "outside",
                    inMentionChannel = false,
                    kind = MemberKind.Agent,
                    avatarWireValue = "pixel:robot",
                    memberId = "u2"
                )
            ),
            store.state.value.memberSuggestions
        )
    }

    @Test
    fun memberSuggestionsPreserveAvatarAndDescriptionMetadata() = runBlocking {
        val memberRepository =
            FakeMemberRepository(
                members =
                    listOf(
                        MemberProfile(
                            id = MemberId("a1"),
                            displayName = "Build Agent",
                            handle = "build",
                            kind = MemberKind.Agent,
                            description = "Builds and validates Android changes",
                            avatar = AvatarReference.Pixel("build-agent"),
                            gravatarHash = "ABC123",
                            avatarLabel = "BA"
                        )
                    )
            )
        memberRepository.channelMembersByChannel["channel-1"] =
            listOf(MemberProfile(MemberId("a1"), "Build Agent", "@build", MemberKind.Agent))
        val store = store(memberRepository, FakeChannelRepository())

        store.ensureSuggestionsForServer(serverId = "server-1", mentionChannelId = "channel-1")

        assertEquals(
            listOf(
                SharedMembersMemberSuggestion(
                    displayName = "Build Agent",
                    handle = "build",
                    inMentionChannel = true,
                    kind = MemberKind.Agent,
                    description = "Builds and validates Android changes",
                    avatarWireValue = "pixel:build-agent",
                    gravatarHash = null,
                    avatarLabel = "BA",
                    memberId = "a1"
                )
            ),
            store.state.value.memberSuggestions
        )
    }

    @Test
    fun memberSuggestionsPreserveAgentStatusProjection() = runBlocking {
        val memberRepository =
            FakeMemberRepository(
                members =
                    listOf(
                        MemberProfile(
                            id = MemberId("a1"),
                            displayName = "Build Agent",
                            handle = "build",
                            kind = MemberKind.Agent,
                            presence = Presence.Active,
                            agentStatus = AgentStatus.Working
                        )
                    )
            )
        val store = store(memberRepository, FakeChannelRepository())

        store.ensureSuggestionsForServer(serverId = "server-1")

        val suggestion = store.state.value.memberSuggestions.single()
        assertEquals("a1", suggestion.memberId)
        assertEquals(MemberKind.Agent, suggestion.kind)
        assertEquals(Presence.Active, suggestion.presence)
        assertEquals(AgentStatus.Working, suggestion.agentStatus)
    }

    @Test
    fun memberSuggestionPresenceEventPatchesLoadedAgentSlices() = runBlocking {
        val memberRepository =
            FakeMemberRepository(
                members =
                    listOf(
                        MemberProfile(
                            id = MemberId("a1"),
                            displayName = "Build Agent",
                            handle = "build",
                            kind = MemberKind.Agent,
                            presence = Presence.Offline,
                            agentStatus = AgentStatus.Offline
                        )
                    )
            )
        val store = store(memberRepository, FakeChannelRepository())

        store.ensureSuggestionsForServer(serverId = "server-1")
        store.dispatch(
            SharedMembersIntent.ApplyPresence(
                MemberPresenceUpdated(
                    memberId = MemberId("a1"),
                    presence = Presence.Active,
                    kind = MemberKind.Agent,
                    agentStatus = AgentStatus.Working
                )
            )
        )

        val suggestion = store.state.value.memberSuggestions.single()
        assertEquals(Presence.Active, suggestion.presence)
        assertEquals(AgentStatus.Working, suggestion.agentStatus)
    }

    @Test
    fun channelMentionScopeUpgradesAgentFallbackAvatarToProfilePixel() = runBlocking {
        val memberRepository =
            FakeMemberRepository(
                members =
                    listOf(
                        MemberProfile(
                            id = MemberId("a1"),
                            displayName = "Build Agent",
                            handle = "build",
                            kind = MemberKind.Agent,
                            description = "Builds and validates Android changes",
                            avatarUrl = "https://www.gravatar.com/avatar/agent-hash?s=52&d=404",
                            gravatarHash = "agent-hash",
                            avatarLabel = "BA"
                        )
                    )
            )
        memberRepository.channelMembersByChannel["channel-1"] =
            listOf(
                MemberProfile(
                    id = MemberId("a1"),
                    displayName = "Build Agent",
                    handle = "@build",
                    kind = MemberKind.Agent,
                    avatar = AvatarReference.Pixel("tree"),
                    avatarLabel = "BA"
                )
            )
        val store = store(memberRepository, FakeChannelRepository())

        store.ensureSuggestionsForServer(serverId = "server-1", mentionChannelId = "channel-1")

        assertEquals(
            listOf(
                SharedMembersMemberSuggestion(
                    displayName = "Build Agent",
                    handle = "build",
                    inMentionChannel = true,
                    kind = MemberKind.Agent,
                    description = "Builds and validates Android changes",
                    avatarWireValue = "pixel:robot",
                    gravatarHash = null,
                    avatarLabel = "BA",
                    memberId = "a1"
                )
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
    fun memberSuggestionSelectorIgnoresUnrelatedScopeChanges() = runBlocking {
        val memberRepository = FakeMemberRepository()
        memberRepository.channelMembersByChannel["thread-channel-1"] =
            listOf(MemberProfile(MemberId("remote-1"), "Remote User", "@remote", MemberKind.Human))
        memberRepository.channelMembersByChannel["dm-1"] =
            listOf(MemberProfile(MemberId("u2"), "Artin", "@artin", MemberKind.Human))
        val channelRepository = FakeChannelRepository()
        val store = store(memberRepository, channelRepository)
        val selectedThreadSlices = mutableListOf<SharedMembersMemberSuggestionSlice>()
        val selectorJob =
            launch(Dispatchers.Unconfined) {
                store.memberSuggestionSliceFlow("thread-channel-1").collect { slice ->
                    selectedThreadSlices += slice
                }
            }

        store.ensureSuggestionsForServer(
            serverId = "server-1",
            mentionChannelId = "thread-channel-1"
        )
        val sizeAfterThreadLoad = selectedThreadSlices.size
        assertEquals(
            listOf(
                SharedMembersMemberSuggestion("Cindy", "cindy", inMentionChannel = false),
                SharedMembersMemberSuggestion("Remote User", "remote", inMentionChannel = true)
            ),
            selectedThreadSlices.last().suggestions
        )

        store.ensureSuggestionsForServer(
            serverId = "server-1",
            mentionChannelId = "dm-1",
            memberScopedMentions = true
        )

        assertEquals(sizeAfterThreadLoad, selectedThreadSlices.size)
        assertEquals(
            listOf(
                SharedMembersMemberSuggestion("Cindy", "cindy", inMentionChannel = false),
                SharedMembersMemberSuggestion("Remote User", "remote", inMentionChannel = true)
            ),
            store.state.value.selectMemberSuggestionSlice("thread-channel-1").suggestions
        )
        assertEquals(
            listOf(SharedMembersMemberSuggestion("Artin", "artin")),
            store.state.value.selectMemberSuggestionSlice("dm-1", memberScopedMentions = true).suggestions
        )
        selectorJob.cancel()
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
                SharedMembersMemberSuggestion(
                    "Build Agent",
                    "build-agent",
                    kind = MemberKind.Agent,
                    avatarWireValue = "pixel:robot",
                    memberId = "a1"
                )
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
        failMembersFirstCalls: Int = 0
    ) : MemberRepository {
        val membersByServer = mutableMapOf("server-1" to members)
        val channelMembersByChannel = mutableMapOf<String, List<MemberProfile>>()
        var failMembersNextCalls = failMembersFirstCalls
        var getMembersCalls = 0
        var getChannelMembersCalls = 0

        override suspend fun getMembers(
            priority: DataRequestPriority,
            serverId: ServerId
        ): DataResult<MemberSnapshot> {
            getMembersCalls += 1
            if (failMembersNextCalls > 0) {
                failMembersNextCalls -= 1
                return DataResult.Error(ScreenError("members_failed"))
            }
            return DataResult.Content(MemberSnapshot(server(serverId.value), membersByServer[serverId.value].orEmpty()))
        }

        override suspend fun getMemberProfile(priority: DataRequestPriority, memberId: MemberId): DataResult<MemberProfile> = error("unused")
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
        override suspend fun updateChannel(channelId: ChannelId, visibility: ChannelVisibility): DataResult<ChannelUpdateResult> = error("unused")
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
