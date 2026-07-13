package build.raft.app.data

import build.raft.app.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

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
                    )
                )
        )

        val entry = store.state.value.entryFor(ServerId("server-1"))
        assertEquals(SharedServerMembersLoadStatus.Idle, entry?.status)
        val member = entry?.members?.single()
        assertEquals("agent-1", member?.id?.value)
        assertEquals("https://cdn.slock.ai/agent.png", member?.avatarUrl)
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
    fun applyPresenceStoresOfflineFactWithoutAgentStatus() {
        val repository = FakeMemberRepository()
        val store = SharedServerMembersStore(repository)
        store.seed(
            MemberSnapshot(
                server("server-1"),
                listOf(
                    member("agent-1", "Agent").copy(
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
                presence = Presence.Offline,
                kind = MemberKind.Agent,
                agentStatus = null
            )
        )

        assertEquals(Presence.Offline, store.state.value.presenceFor("agent-1")?.presence)
        assertEquals(
            SharedServerMemberStatus(status = AgentStatus.Online, presence = Presence.Offline),
            store.state.value.memberStatus(ServerId("server-1"), "agent-1")
        )
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
    fun applyAgentActivitySnapshotBecomesCanonicalStatusFact() {
        val repository = FakeMemberRepository()
        val store = SharedServerMembersStore(repository)
        store.seed(
            MemberSnapshot(
                server("server-1"),
                listOf(
                    member("agent-1", "Agent").copy(
                        kind = MemberKind.Agent,
                        agentStatus = AgentStatus.Offline,
                        presence = Presence.Offline
                    )
                )
            )
        )

        store.applyAgentActivitySnapshot(
            MemberId("agent-1"),
            listOf(
                AgentActivityEntry(
                    id = "activity-1",
                    type = "status",
                    title = "Working",
                    statusActivity = "working",
                    statusDetail = "Building"
                )
            )
        )

        assertEquals(
            SharedServerMemberStatus(
                status = AgentStatus.Working,
                presence = Presence.Active,
                statusLabel = "Building"
            ),
            store.state.value.memberStatus(ServerId("server-1"), "agent-1")
        )
        assertEquals(AgentStatus.Offline, store.state.value.entryFor(ServerId("server-1"))?.members?.single()?.agentStatus)
    }

    @Test
    fun applyAgentActivitySnapshotDoesNotOverwriteSequencedRealtimeFact() {
        val repository = FakeMemberRepository()
        val store = SharedServerMembersStore(repository)

        store.applyPresence(
            MemberPresenceUpdated(
                memberId = MemberId("agent-1"),
                presence = Presence.Active,
                kind = MemberKind.Agent,
                agentStatus = AgentStatus.Working,
                statusLabel = "Running command...",
                serverSeq = 10L
            )
        )
        store.applyAgentActivitySnapshot(
            MemberId("agent-1"),
            listOf(
                AgentActivityEntry(
                    id = "activity-1",
                    type = "status",
                    title = "Offline",
                    statusActivity = "offline",
                    statusDetail = "Stopped"
                )
            )
        )

        assertEquals(AgentStatus.Working, store.state.value.presenceFor("agent-1")?.agentStatus)
        assertEquals("Running command...", store.state.value.presenceFor("agent-1")?.statusLabel)
        assertEquals(10L, store.state.value.presenceFor("agent-1")?.serverSeq)
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
    fun removeMemberEvictsServerChannelSuggestionAndPresenceSlices() = runBlocking {
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
        repository.channelMembersByChannel["channel-1"] =
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
        store.refreshMembers(ServerId("server-1"))
        store.loadChannelMembers(ServerId("server-1"), ChannelId("channel-1"))
        store.ensureMemberSuggestionsForServer(
            serverId = ServerId("server-1"),
            mentionChannelId = "channel-1"
        )
        store.applyPresence(
            MemberPresenceUpdated(
                memberId = MemberId("agent-1"),
                presence = Presence.Active,
                kind = MemberKind.Agent,
                agentStatus = AgentStatus.Working
            )
        )

        val removed =
            store.removeMember(
                serverId = ServerId("server-1"),
                memberId = MemberId("agent-1"),
                memberKind = MemberKind.Agent
            )

        assertEquals(true, removed)
        assertEquals(listOf("u1"), store.currentServerMembers(ServerId("server-1")).map { it.id.value })
        assertEquals(listOf("u1"), store.currentChannelMembers(ServerId("server-1"), ChannelId("channel-1")).map { it.id.value })
        val suggestions =
            store.state.value.memberSuggestionSlice(
                serverId = ServerId("server-1"),
                mentionChannelId = "channel-1"
            ).suggestions
        assertEquals(listOf("u1"), suggestions.map { it.memberId })
        assertNull(store.state.value.presenceFor("agent-1"))
        assertNull(store.state.value.memberStatus(ServerId("server-1"), "agent-1"))
        assertNull(store.state.value.memberProfile(ServerId("server-1"), "agent-1"))
    }

    @Test
    fun deleteAgentCallsRepositoryAndEvictsMemberThroughStore() = runBlocking {
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
        store.refreshMembers(ServerId("server-1"))

        val result =
            store.deleteAgent(
                serverId = ServerId("server-1"),
                agentId = MemberId("agent-1")
            )

        assertIs<DataResult.Content<AgentLifecycleActionResult>>(result)
        assertEquals(1, repository.deleteAgentCalls)
        assertEquals(listOf("u1"), store.currentServerMembers(ServerId("server-1")).map { it.id.value })
    }

    @Test
    fun deleteAgentEvictsLocallyWithoutImmediateRefreshAndKeepsStaleRosterHidden() = runBlocking {
        val repository = FakeMemberRepository()
        repository.retainDeletedAgentsInRoster = true
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
        repository.channelMembersByChannel["channel-1"] =
            listOf(
                member("agent-1", "Build Agent").copy(
                    handle = "build-agent",
                    kind = MemberKind.Agent,
                    agentStatus = AgentStatus.Working,
                    presence = Presence.Active
                )
            )
        val store = SharedServerMembersStore(repository)
        store.refreshMembers(ServerId("server-1"))
        store.loadChannelMembers(ServerId("server-1"), ChannelId("channel-1"))

        val result =
            store.deleteAgent(
                serverId = ServerId("server-1"),
                agentId = MemberId("agent-1")
            )

        assertIs<DataResult.Content<AgentLifecycleActionResult>>(result)
        assertEquals(1, repository.getMembersCalls)
        assertEquals(listOf("u1"), store.currentServerMembers(ServerId("server-1")).map { it.id.value })
        assertEquals(emptyList(), store.currentChannelMembers(ServerId("server-1"), ChannelId("channel-1")).map { it.id.value })
        assertNull(store.state.value.memberProfile(ServerId("server-1"), "agent-1"))

        store.refreshMembers(ServerId("server-1"), force = true)

        assertEquals(2, repository.getMembersCalls)
        assertEquals(listOf("u1"), store.currentServerMembers(ServerId("server-1")).map { it.id.value })
        assertNull(store.state.value.memberProfile(ServerId("server-1"), "agent-1"))
    }

    @Test
    fun addChannelMemberUpdatesChannelSliceAndSuggestionStateThroughStore() = runBlocking {
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
        repository.channelMembersByChannel["channel-1"] = listOf(member("u1", "Cindy"))
        val store = SharedServerMembersStore(repository)
        store.refreshMembers(ServerId("server-1"))
        store.ensureMemberSuggestionsForServer(
            serverId = ServerId("server-1"),
            mentionChannelId = "channel-1"
        )

        val result =
            store.addChannelMember(
                serverId = ServerId("server-1"),
                request =
                    AddChannelMemberRequest(
                        channelId = ChannelId("channel-1"),
                        memberId = MemberId("agent-1"),
                        kind = MemberKind.Agent
                    )
        )

        assertIs<DataResult.Content<ChannelMemberActionResult>>(result)
        assertEquals(1, repository.getChannelMembersCalls)
        assertEquals(listOf("u1", "agent-1"), store.currentChannelMembers(ServerId("server-1"), ChannelId("channel-1")).map { it.id.value })
        val suggestions =
            store.state.value.memberSuggestionSlice(
                serverId = ServerId("server-1"),
                mentionChannelId = "channel-1"
            ).suggestions
        assertEquals(true, suggestions.single { it.memberId == "agent-1" }.inMentionChannel)
        assertEquals(1, repository.addChannelMemberCalls)
    }

    @Test
    fun removeChannelMemberUpdatesChannelSliceAndSuggestionStateThroughStore() = runBlocking {
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
        repository.channelMembersByChannel["channel-1"] =
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
        store.refreshMembers(ServerId("server-1"))
        store.ensureMemberSuggestionsForServer(
            serverId = ServerId("server-1"),
            mentionChannelId = "channel-1"
        )

        val result =
            store.removeChannelMember(
                serverId = ServerId("server-1"),
                request =
                    RemoveChannelMemberRequest(
                        channelId = ChannelId("channel-1"),
                        memberId = MemberId("agent-1"),
                        kind = MemberKind.Agent
                    )
            )

        assertIs<DataResult.Content<ChannelMemberActionResult>>(result)
        assertEquals(1, repository.getChannelMembersCalls)
        assertEquals(listOf("u1"), store.currentChannelMembers(ServerId("server-1"), ChannelId("channel-1")).map { it.id.value })
        val suggestions =
            store.state.value.memberSuggestionSlice(
                serverId = ServerId("server-1"),
                mentionChannelId = "channel-1"
            ).suggestions
        assertEquals(false, suggestions.single { it.memberId == "agent-1" }.inMentionChannel)
        assertEquals(1, repository.removeChannelMemberCalls)
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
        val firstEmission = CompletableDeferred<Unit>()
        val collectJob =
            launch {
                store.memberStatusFlow(ServerId("server-1"), "agent-1")
                    .onEach { firstEmission.complete(Unit) }
                    .take(2)
                    .toList(emissions)
            }
        firstEmission.await()

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
        val firstEmission = CompletableDeferred<Unit>()
        val collectJob =
            launch {
                store.memberProfileFlow(ServerId("server-1"), "u1")
                    .onEach { firstEmission.complete(Unit) }
                    .take(2)
                    .toList(emissions)
            }
        firstEmission.await()

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
    fun scopedMemberFlowsIgnoreUnrelatedPresenceFacts() = runBlocking {
        val repository = FakeMemberRepository()
        val store = SharedServerMembersStore(repository)
        store.seed(
            MemberSnapshot(
                server("server-1"),
                listOf(
                    member("agent-1", "Agent One").copy(
                        kind = MemberKind.Agent,
                        avatar = AvatarReference.fromWireValue("pixel:robot"),
                        agentStatus = AgentStatus.Online,
                        presence = Presence.Active
                    ),
                    member("agent-2", "Agent Two").copy(
                        kind = MemberKind.Agent,
                        avatar = AvatarReference.fromWireValue("pixel:paint"),
                        agentStatus = AgentStatus.Idle,
                        presence = Presence.Away
                    )
                )
            )
        )
        val avatarEmissions = mutableListOf<MemberProfile?>()
        val statusEmissions = mutableListOf<SharedServerMemberStatus?>()
        val avatarFirstEmission = CompletableDeferred<Unit>()
        val statusFirstEmission = CompletableDeferred<Unit>()
        val avatarJob =
            launch {
                store.memberProfileFlow(ServerId("server-1"), "agent-1")
                    .onEach { avatarFirstEmission.complete(Unit) }
                    .take(2)
                    .toList(avatarEmissions)
            }
        val statusJob =
            launch {
                store.memberStatusFlow(ServerId("server-1"), "agent-1")
                    .onEach { statusFirstEmission.complete(Unit) }
                    .take(2)
                    .toList(statusEmissions)
            }
        avatarFirstEmission.await()
        statusFirstEmission.await()

        store.applyPresence(
            MemberPresenceUpdated(
                memberId = MemberId("agent-2"),
                presence = Presence.Active,
                kind = MemberKind.Agent,
                agentStatus = AgentStatus.Working
            )
        )
        yield()

        assertEquals(1, avatarEmissions.size)
        assertEquals(1, statusEmissions.size)

        store.applyPresence(
            MemberPresenceUpdated(
                memberId = MemberId("agent-1"),
                presence = Presence.Active,
                kind = MemberKind.Agent,
                agentStatus = AgentStatus.Working
            )
        )
        store.seed(
            MemberSnapshot(
                server("server-1"),
                listOf(
                    member("agent-1", "Agent One").copy(
                        kind = MemberKind.Agent,
                        avatar = AvatarReference.fromWireValue("pixel:spark"),
                        agentStatus = AgentStatus.Working,
                        presence = Presence.Active
                    ),
                    member("agent-2", "Agent Two").copy(
                        kind = MemberKind.Agent,
                        avatar = AvatarReference.fromWireValue("pixel:paint"),
                        agentStatus = AgentStatus.Working,
                        presence = Presence.Active
                    )
                )
            )
        )
        avatarJob.join()
        statusJob.join()

        assertEquals(listOf("pixel:robot", "pixel:spark"), avatarEmissions.map { it?.avatar?.wireValue })
        assertEquals(
            listOf(AgentStatus.Online, AgentStatus.Working),
            statusEmissions.map { it?.status }
        )
    }

    @Test
    fun serverMembersEntryFlowIgnoresUnrelatedPresenceFacts() = runBlocking {
        val store = SharedServerMembersStore(FakeMemberRepository())
        store.seed(
            MemberSnapshot(
                server("server-1"),
                listOf(
                    member("agent-1", "Agent One").copy(kind = MemberKind.Agent),
                    member("agent-2", "Agent Two").copy(kind = MemberKind.Agent)
                )
            )
        )
        val entries = mutableListOf<SharedServerMembersEntry>()
        val firstEmission = CompletableDeferred<Unit>()
        val collectJob =
            launch {
                store.serverMembersEntryFlow(ServerId("server-1"))
                    .onEach { firstEmission.complete(Unit) }
                    .take(2)
                    .toList(entries)
            }
        firstEmission.await()

        store.applyPresence(
            MemberPresenceUpdated(
                memberId = MemberId("agent-2"),
                kind = MemberKind.Agent,
                presence = Presence.Active,
                agentStatus = AgentStatus.Working
            )
        )
        yield()
        assertEquals(1, entries.size)

        store.seed(
            MemberSnapshot(
                server("server-1"),
                listOf(
                    member("agent-1", "Agent One Updated").copy(kind = MemberKind.Agent),
                    member("agent-2", "Agent Two").copy(kind = MemberKind.Agent)
                )
            )
        )
        collectJob.join()

        assertEquals(
            listOf("Agent One", "Agent One Updated"),
            entries.map { it.members.first { member -> member.id.value == "agent-1" }.displayName }
        )
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

    @Test
    fun memberSuggestionPatchMarksRemovedChannelMemberWithoutReloading() = runBlocking {
        val repository = FakeMemberRepository()
        repository.membersByServer["server-1"] = listOf(member("u1", "Cindy"), member("u2", "Artin"))
        repository.channelMembersByChannel["channel-1"] = listOf(member("u1", "Cindy"), member("u2", "Artin"))
        val store = SharedServerMembersStore(repository)

        store.ensureMemberSuggestionsForServer(
            serverId = ServerId("server-1"),
            mentionChannelId = "channel-1"
        )
        val patched =
            store.removeMentionChannelMemberSuggestion(
                serverId = ServerId("server-1"),
                mentionChannelId = "channel-1",
                memberScopedMentions = false,
                memberId = "u2",
                memberKind = MemberKind.Human
            )

        assertEquals(true, patched)
        assertEquals(1, repository.getMembersCalls)
        assertEquals(1, repository.getChannelMembersCalls)
        val suggestions =
            store.state.value.memberSuggestionSlice(
                serverId = ServerId("server-1"),
                mentionChannelId = "channel-1"
            ).suggestions
        assertEquals(false, suggestions.single { it.handle == "artin" }.inMentionChannel)
        assertEquals(listOf("u1"), store.currentChannelMembers(ServerId("server-1"), ChannelId("channel-1")).map { it.id.value })
    }

    @Test
    fun scopedMemberSuggestionPatchRemovesMemberWithoutReloading() = runBlocking {
        val repository = FakeMemberRepository()
        repository.channelMembersByChannel["channel-1"] = listOf(member("u1", "Cindy"), member("u2", "Artin"))
        val store = SharedServerMembersStore(repository)

        store.ensureMemberSuggestionsForServer(
            serverId = ServerId("server-1"),
            mentionChannelId = "channel-1",
            memberScopedMentions = true
        )
        val patched =
            store.removeMentionChannelMemberSuggestion(
                serverId = ServerId("server-1"),
                mentionChannelId = "channel-1",
                memberScopedMentions = true,
                memberId = "u2",
                memberKind = MemberKind.Human
            )

        assertEquals(true, patched)
        assertEquals(0, repository.getMembersCalls)
        assertEquals(1, repository.getChannelMembersCalls)
        val suggestions =
            store.state.value.memberSuggestionSlice(
                serverId = ServerId("server-1"),
                mentionChannelId = "channel-1",
                memberScopedMentions = true
            ).suggestions
        assertEquals(listOf("cindy"), suggestions.map { it.handle })
    }

    @Test
    fun memberSuggestionPatchAddsChannelMemberWithoutReloading() = runBlocking {
        val repository = FakeMemberRepository()
        repository.membersByServer["server-1"] = listOf(member("u1", "Cindy"), member("u2", "Artin"))
        repository.channelMembersByChannel["channel-1"] = listOf(member("u1", "Cindy"))
        val store = SharedServerMembersStore(repository)

        store.ensureMemberSuggestionsForServer(
            serverId = ServerId("server-1"),
            mentionChannelId = "channel-1"
        )
        val patched =
            store.patchMentionChannelMemberSuggestion(
                serverId = ServerId("server-1"),
                mentionChannelId = "channel-1",
                memberScopedMentions = false,
                member = member("u2", "Artin"),
                inMentionChannel = true
            )

        assertEquals(true, patched)
        assertEquals(1, repository.getMembersCalls)
        assertEquals(1, repository.getChannelMembersCalls)
        val suggestions =
            store.state.value.memberSuggestionSlice(
                serverId = ServerId("server-1"),
                mentionChannelId = "channel-1"
            ).suggestions
        assertEquals(true, suggestions.single { it.handle == "artin" }.inMentionChannel)
        assertEquals(
            listOf("u1", "u2"),
            store.currentChannelMembers(ServerId("server-1"), ChannelId("channel-1")).map { it.id.value }
        )
    }

    private class FakeMemberRepository : MemberRepository {
        val membersByServer = mutableMapOf("server-1" to listOf(member("u1", "Cindy")))
        val channelMembersByChannel = mutableMapOf<String, List<MemberProfile>>()
        var getMembersCalls = 0
        var getChannelMembersCalls = 0
        var addChannelMemberCalls = 0
        var removeChannelMemberCalls = 0
        var deleteAgentCalls = 0
        var retainDeletedAgentsInRoster = false

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
        override suspend fun deleteAgent(
            priority: DataRequestPriority,
            agentId: MemberId
        ): DataResult<AgentLifecycleActionResult> {
            deleteAgentCalls += 1
            if (!retainDeletedAgentsInRoster) {
                for (key in membersByServer.keys.toList()) {
                    membersByServer[key] =
                        membersByServer.getValue(key).filterNot { it.id == agentId && it.kind == MemberKind.Agent }
                }
            }
            return DataResult.Content(
                AgentLifecycleActionResult(
                    agentId = agentId,
                    success = true,
                    action = "delete"
                )
            )
        }
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
        override suspend fun addChannelMember(
            priority: DataRequestPriority,
            request: AddChannelMemberRequest
        ): DataResult<ChannelMemberActionResult> {
            addChannelMemberCalls += 1
            val member =
                membersByServer.values
                    .asSequence()
                    .flatMap { it.asSequence() }
                    .firstOrNull { it.id == request.memberId && it.kind == request.kind }
                    ?: member(request.memberId.value, request.memberId.value).copy(kind = request.kind)
            val current = channelMembersByChannel[request.channelId.value].orEmpty()
            channelMembersByChannel[request.channelId.value] = current.upsertFakeMember(member)
            return DataResult.Content(
                ChannelMemberActionResult(
                    channelId = request.channelId,
                    memberId = request.memberId,
                    member = member,
                    added = true
                )
            )
        }

        override suspend fun removeChannelMember(
            priority: DataRequestPriority,
            request: RemoveChannelMemberRequest
        ): DataResult<ChannelMemberActionResult> {
            removeChannelMemberCalls += 1
            channelMembersByChannel[request.channelId.value] =
                channelMembersByChannel[request.channelId.value]
                    .orEmpty()
                    .filterNot { it.id == request.memberId && it.kind == request.kind }
            return DataResult.Content(
                ChannelMemberActionResult(
                    channelId = request.channelId,
                    memberId = request.memberId,
                    member = null,
                    added = false
                )
            )
        }
    }
}

private fun server(id: String): ServerSummary =
    ServerSummary(id = ServerId(id), name = "Workspace", slug = "workspace")

private fun member(id: String, name: String): MemberProfile =
    MemberProfile(id = MemberId(id), displayName = name, handle = name.lowercase(), kind = MemberKind.Human)

private fun List<MemberProfile>.upsertFakeMember(member: MemberProfile): List<MemberProfile> {
    val current = filterNot { it.id == member.id && it.kind == member.kind }
    return current + member
}
