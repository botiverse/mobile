package ai.slock.compose.data

import ai.slock.compose.model.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SharedServerMembersStoreTest {
    @Test
    fun refreshMembersCachesPerServer() = runBlocking {
        val repository = FakeMemberRepository()
        val store = SharedServerMembersStore(repository)

        val first = store.refreshMembers(ServerId("server-1"))
        val second = store.refreshMembers(ServerId("server-1"))

        assertIs<DataResult.Content<Unit>>(first)
        assertIs<DataResult.Content<Unit>>(second)
        assertEquals(1, repository.getMembersCalls)
        assertEquals(listOf("u1"), store.currentServerMembers(ServerId("server-1")).map { it.id.value })
        assertEquals(SharedServerMembersLoadStatus.Loaded, store.state.value.entryFor(ServerId("server-1"))?.status)
    }

    @Test
    fun forceReloadBypassesLoadedCache() = runBlocking {
        val repository = FakeMemberRepository()
        val store = SharedServerMembersStore(repository)

        store.refreshMembers(ServerId("server-1"))
        repository.membersByServer["server-1"] = listOf(member("u2", "Mina"))
        val result = store.refreshMembers(ServerId("server-1"), force = true)

        assertIs<DataResult.Content<Unit>>(result)
        assertEquals(2, repository.getMembersCalls)
        assertEquals(listOf("u2"), store.currentServerMembers(ServerId("server-1")).map { it.id.value })
    }

    @Test
    fun seedWarmsCacheWithoutRepositoryCall() = runBlocking {
        val repository = FakeMemberRepository()
        val store = SharedServerMembersStore(repository)

        store.seed(MemberSnapshot(server("server-1"), listOf(member("u3", "Nina"))))
        val result = store.refreshMembers(ServerId("server-1"))

        assertIs<DataResult.Content<Unit>>(result)
        assertEquals(0, repository.getMembersCalls)
        assertEquals(listOf("u3"), store.currentServerMembers(ServerId("server-1")).map { it.id.value })
    }

    @Test
    fun seedProfilesCachesHomeDirectMessageAvatarsWithoutMarkingRosterLoaded() {
        val repository = FakeMemberRepository()
        val store = SharedServerMembersStore(repository)

        store.seedProfiles(
            serverId = ServerId("server-1"),
            server = server("server-1"),
            profiles =
                listOf(
                    member("agent-1", "Build Agent").copy(
                        kind = MemberKind.Agent,
                        avatarUrl = "https://cdn.slock.ai/agent.png",
                        gravatarHash = "agent-hash"
                    )
                )
        )

        val entry = store.state.value.entryFor(ServerId("server-1"))
        assertEquals(SharedServerMembersLoadStatus.Idle, entry?.status)
        val member = entry?.members?.single()
        assertEquals("agent-1", member?.id?.value)
        assertEquals("https://cdn.slock.ai/agent.png", member?.avatarUrl)
        assertEquals("agent-hash", member?.gravatarHash)
        assertEquals(0, repository.getMembersCalls)
    }

    @Test
    fun applyPresenceUpdatesStatusResolverWithoutRewritingCachedRoster() {
        val repository = FakeMemberRepository()
        val store = SharedServerMembersStore(repository)
        store.seed(
            MemberSnapshot(
                server("server-1"),
                listOf(
                    member("u1", "Cindy"),
                    member("agent-1", "Agent").copy(kind = MemberKind.Agent, agentStatus = AgentStatus.Online)
                )
            )
        )

        store.applyPresence(
            MemberPresenceUpdated(
                memberId = MemberId("agent-1"),
                presence = AgentStatus.Thinking.presence,
                kind = MemberKind.Agent,
                agentStatus = AgentStatus.Thinking
            )
        )

        val members = store.state.value.entryFor(ServerId("server-1"))?.members.orEmpty()
        assertEquals(AgentStatus.Online, members.single { it.id.value == "agent-1" }.agentStatus)
        assertEquals(Presence.Offline, members.single { it.id.value == "agent-1" }.presence)
        assertEquals(
            SharedServerMemberStatus(status = AgentStatus.Thinking, presence = AgentStatus.Thinking.presence),
            store.state.value.memberStatus(ServerId("server-1"), "agent-1")
        )
        assertEquals(MemberKind.Human, members.single { it.id.value == "u1" }.kind)
    }

    @Test
    fun applyPresenceIgnoresSameVisibleStatusWithNewerSeq() {
        val repository = FakeMemberRepository()
        val store = SharedServerMembersStore(repository)

        store.applyPresence(
            MemberPresenceUpdated(
                memberId = MemberId("agent-1"),
                presence = Presence.Active,
                kind = MemberKind.Agent,
                agentStatus = AgentStatus.Working,
                statusLabel = "working",
                serverSeq = 1L
            )
        )
        store.applyPresence(
            MemberPresenceUpdated(
                memberId = MemberId("agent-1"),
                presence = Presence.Active,
                kind = MemberKind.Agent,
                agentStatus = AgentStatus.Working,
                statusLabel = "working",
                serverSeq = 2L
            )
        )

        assertEquals(1L, store.state.value.presenceFor("agent-1")?.serverSeq)
    }

    @Test
    fun applyPresenceRetainsFactBeforeRosterLoads() = runBlocking {
        val repository = FakeMemberRepository()
        val store = SharedServerMembersStore(repository)

        store.applyPresence(
            MemberPresenceUpdated(
                memberId = MemberId("agent-1"),
                presence = AgentStatus.Working.presence,
                kind = MemberKind.Agent,
                agentStatus = AgentStatus.Working
            )
        )

        assertEquals(AgentStatus.Working, store.state.value.presenceFor("agent-1")?.agentStatus)
        repository.membersByServer["server-1"] =
            listOf(member("agent-1", "Agent").copy(kind = MemberKind.Agent, agentStatus = AgentStatus.Online))

        val result = store.refreshMembers(ServerId("server-1"))

        assertIs<DataResult.Content<Unit>>(result)
        val agent = store.currentServerMembers(ServerId("server-1")).single { it.id.value == "agent-1" }
        assertEquals(AgentStatus.Working, agent.agentStatus)
        assertEquals(AgentStatus.Working.presence, agent.presence)
    }

    @Test
    fun loadChannelMembersCachesPerChannelAndSeedsProfiles() = runBlocking {
        val repository = FakeMemberRepository()
        repository.channelMembersByChannel["channel-1"] =
            listOf(
                member("agent-1", "Build Agent").copy(
                    handle = "build-agent",
                    kind = MemberKind.Agent,
                    agentStatus = AgentStatus.Working,
                    presence = Presence.Active,
                    avatar = AvatarReference.fromWireValue("pixel:build")
                )
            )
        val store = SharedServerMembersStore(repository)

        val first = store.loadChannelMembers(ServerId("server-1"), ChannelId("channel-1"))
        val second = store.loadChannelMembers(ServerId("server-1"), ChannelId("channel-1"))

        assertIs<DataResult.Content<ChannelMemberSnapshot>>(first)
        assertIs<DataResult.Content<ChannelMemberSnapshot>>(second)
        assertEquals(1, repository.getChannelMembersCalls)
        assertEquals(listOf("agent-1"), second.data.members.map { it.id.value })
        assertEquals(
            SharedServerMembersLoadStatus.Loaded,
            store.state.value.channelMemberSlice(ServerId("server-1"), ChannelId("channel-1")).status
        )
        assertEquals(
            SharedServerMemberStatus(status = AgentStatus.Working, presence = Presence.Active),
            store.state.value.memberStatus(ServerId("server-1"), "agent-1")
        )
        assertEquals(
            "pixel:build",
            store.state.value.memberProfile(ServerId("server-1"), "agent-1")?.avatar?.wireValue
        )
    }

    @Test
    fun applyPresenceUpdatesStatusResolverWithoutRewritingChannelMemberSlice() = runBlocking {
        val repository = FakeMemberRepository()
        repository.channelMembersByChannel["channel-1"] =
            listOf(
                member("agent-1", "Build Agent").copy(
                    kind = MemberKind.Agent,
                    agentStatus = AgentStatus.Online,
                    presence = Presence.Active
                )
            )
        val store = SharedServerMembersStore(repository)
        store.loadChannelMembers(ServerId("server-1"), ChannelId("channel-1"))

        store.applyPresence(
            MemberPresenceUpdated(
                memberId = MemberId("agent-1"),
                presence = AgentStatus.Thinking.presence,
                kind = MemberKind.Agent,
                agentStatus = AgentStatus.Thinking
            )
        )

        val member =
            store.state.value
                .channelMemberSlice(ServerId("server-1"), ChannelId("channel-1"))
                .members
                .single()
        assertEquals(AgentStatus.Online, member.agentStatus)
        assertEquals(Presence.Active, member.presence)
        assertEquals(
            SharedServerMemberStatus(status = AgentStatus.Thinking, presence = AgentStatus.Thinking.presence),
            store.state.value.memberStatus(ServerId("server-1"), "agent-1")
        )
    }

    @Test
    fun memberStatusFlowEmitsOnlyForTargetMemberStatusChanges() = runBlocking {
        val repository = FakeMemberRepository()
        val store = SharedServerMembersStore(repository)
        store.seed(
            MemberSnapshot(
                server("server-1"),
                listOf(
                    member("agent-1", "Agent One").copy(
                        kind = MemberKind.Agent,
                        agentStatus = AgentStatus.Online,
                        presence = Presence.Active
                    ),
                    member("agent-2", "Agent Two").copy(
                        kind = MemberKind.Agent,
                        agentStatus = AgentStatus.Idle,
                        presence = Presence.Away
                    )
                )
            )
        )
        val emissions = mutableListOf<SharedServerMemberStatus?>()
        val collectJob =
            launch {
                store.memberStatusFlow(ServerId("server-1"), "agent-1")
                    .take(2)
                    .toList(emissions)
            }
        yield()

        store.applyPresence(
            MemberPresenceUpdated(
                memberId = MemberId("agent-2"),
                presence = Presence.Active,
                kind = MemberKind.Agent,
                agentStatus = AgentStatus.Working
            )
        )
        yield()
        assertEquals(1, emissions.size)

        store.applyPresence(
            MemberPresenceUpdated(
                memberId = MemberId("agent-1"),
                presence = Presence.Active,
                kind = MemberKind.Agent,
                agentStatus = AgentStatus.Working
            )
        )
        collectJob.join()

        val expected: List<SharedServerMemberStatus?> =
            listOf(
                SharedServerMemberStatus(status = AgentStatus.Online, presence = Presence.Active),
                SharedServerMemberStatus(status = AgentStatus.Working, presence = Presence.Active)
            )
        assertEquals(expected, emissions)
    }

    @Test
    fun seedProfilesCannotOverrideRealtimePresenceFactStatus() {
        val repository = FakeMemberRepository()
        val store = SharedServerMembersStore(repository)
        store.seed(
            MemberSnapshot(
                server("server-1"),
                listOf(
                    member("agent-1", "Agent One").copy(
                        kind = MemberKind.Agent,
                        agentStatus = AgentStatus.Online,
                        presence = Presence.Active
                    )
                )
            )
        )
        store.applyPresence(
            MemberPresenceUpdated(
                memberId = MemberId("agent-1"),
                presence = Presence.Active,
                kind = MemberKind.Agent,
                agentStatus = AgentStatus.Working
            )
        )

        store.seedProfiles(
            serverId = ServerId("server-1"),
            server = server("server-1"),
            profiles =
                listOf(
                    member("agent-1", "Agent One").copy(
                        kind = MemberKind.Agent,
                        agentStatus = AgentStatus.Idle,
                        presence = Presence.Away
                    )
                )
        )

        assertEquals(
            SharedServerMemberStatus(status = AgentStatus.Working, presence = Presence.Active),
            store.state.value.memberStatus(ServerId("server-1"), "agent-1")
        )
        assertEquals(
            AgentStatus.Working,
            store.state.value.entryFor(ServerId("server-1"))?.members?.single()?.agentStatus
        )
    }

    @Test
    fun memberProfileFlowEmitsOnlyForTargetProfileChanges() = runBlocking {
        val repository = FakeMemberRepository()
        val store = SharedServerMembersStore(repository)
        store.seed(
            MemberSnapshot(
                server("server-1"),
                listOf(
                    member("u1", "Cindy").copy(avatar = AvatarReference.fromWireValue("pixel:robot")),
                    member("u2", "Artin").copy(avatar = AvatarReference.fromWireValue("pixel:paint"))
                )
            )
        )
        val emissions = mutableListOf<MemberProfile?>()
        val collectJob =
            launch {
                store.memberProfileFlow(ServerId("server-1"), "u1")
                    .take(2)
                    .toList(emissions)
            }
        yield()

        store.seed(
            MemberSnapshot(
                server("server-1"),
                listOf(
                    member("u1", "Cindy").copy(avatar = AvatarReference.fromWireValue("pixel:robot")),
                    member("u2", "Artin").copy(avatar = AvatarReference.fromWireValue("pixel:terminal"))
                )
            )
        )
        yield()
        assertEquals(1, emissions.size)

        store.seed(
            MemberSnapshot(
                server("server-1"),
                listOf(
                    member("u1", "Cindy").copy(avatar = AvatarReference.fromWireValue("pixel:spark")),
                    member("u2", "Artin").copy(avatar = AvatarReference.fromWireValue("pixel:terminal"))
                )
            )
        )
        collectJob.join()

        assertEquals(listOf("pixel:robot", "pixel:spark"), emissions.map { it?.avatar?.wireValue })
    }

    @Test
    fun memberSuggestionsAreOwnedByServerMembersStore() = runBlocking {
        val repository = FakeMemberRepository()
        repository.membersByServer["server-1"] =
            listOf(
                member("u1", "Cindy"),
                member("agent-1", "Build Agent").copy(
                    handle = "build-agent",
                    kind = MemberKind.Agent,
                    agentStatus = AgentStatus.Working,
                    presence = Presence.Active
                )
            )
        val store = SharedServerMembersStore(repository)

        store.ensureMemberSuggestionsForServer(ServerId("server-1"))

        val suggestions =
            store.state.value.memberSuggestionSlice(ServerId("server-1")).suggestions
        assertEquals(listOf("cindy", "build-agent"), suggestions.map { it.handle })
        assertEquals("u1", suggestions.single { it.handle == "cindy" }.memberId)
        assertEquals("agent-1", suggestions.single { it.handle == "build-agent" }.memberId)
        assertEquals(1, repository.getMembersCalls)
    }

    @Test
    fun memberSuggestionsRefreshScopedChannelMembership() = runBlocking {
        val repository = FakeMemberRepository()
        repository.membersByServer["server-1"] = listOf(member("u1", "Cindy"), member("u2", "Artin"))
        repository.channelMembersByChannel["channel-1"] = listOf(member("u2", "Artin"))
        val store = SharedServerMembersStore(repository)

        store.ensureMemberSuggestionsForServer(
            serverId = ServerId("server-1"),
            mentionChannelId = "channel-1"
        )

        val suggestions =
            store.state.value.memberSuggestionSlice(
                serverId = ServerId("server-1"),
                mentionChannelId = "channel-1"
            ).suggestions
        assertEquals(false, suggestions.single { it.handle == "cindy" }.inMentionChannel)
        assertEquals(true, suggestions.single { it.handle == "artin" }.inMentionChannel)
        assertEquals(1, repository.getChannelMembersCalls)
    }

    @Test
    fun scopedMemberSuggestionsReuseCachedChannelMembers() = runBlocking {
        val repository = FakeMemberRepository()
        repository.membersByServer["server-1"] = listOf(member("u1", "Cindy"), member("u2", "Artin"))
        repository.channelMembersByChannel["channel-1"] = listOf(member("u2", "Artin"))
        val store = SharedServerMembersStore(repository)

        store.ensureMemberSuggestionsForServer(
            serverId = ServerId("server-1"),
            mentionChannelId = "channel-1"
        )
        store.ensureMemberSuggestionsForServer(
            serverId = ServerId("server-1"),
            mentionChannelId = "channel-1"
        )

        assertEquals(1, repository.getChannelMembersCalls)
    }

    private class FakeMemberRepository : MemberRepository {
        val membersByServer = mutableMapOf("server-1" to listOf(member("u1", "Cindy")))
        val channelMembersByChannel = mutableMapOf<String, List<MemberProfile>>()
        var getMembersCalls = 0
        var getChannelMembersCalls = 0

        override suspend fun getMembers(
            priority: DataRequestPriority,
            serverId: ServerId
        ): DataResult<MemberSnapshot> {
            getMembersCalls += 1
            return DataResult.Content(MemberSnapshot(server(serverId.value), membersByServer[serverId.value].orEmpty()))
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
}

private fun server(id: String): ServerSummary =
    ServerSummary(id = ServerId(id), name = "Workspace", slug = "workspace")

private fun member(id: String, name: String): MemberProfile =
    MemberProfile(id = MemberId(id), displayName = name, handle = name.lowercase(), kind = MemberKind.Human)
