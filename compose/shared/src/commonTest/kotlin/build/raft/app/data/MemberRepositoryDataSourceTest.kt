package build.raft.app.data

import build.raft.app.RaftFilePreviewPayload
import build.raft.app.englishErrorPresentationStringsForTest
import build.raft.app.withAgentWorkspaceLoadError
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

class MemberRepositoryDataSourceTest {
    @Test
    fun aProfileOpenedFromAChannelSendsItAndKeepsTheServersAnswer() = runBlocking {
        // botiverse/slock@44ce160f1 routes/servers.ts GET /:id/members/:memberId/profile?channelId=
        // (resolveProfileChannelServers): another server's person, with their servers in that channel.
        val network =
            FakeMemberNetworkClient().apply {
                memberProfileResponse =
                    HomeNetworkResponse(
                        200,
                        """
                        {"userId":"user-remote","serverId":"server-peer","serverName":"Peer Labs","serverSlug":"peer-labs",
                         "name":"ada","displayName":"Ada","description":null,"avatarUrl":null,"email":null,"gravatarHash":"",
                         "role":null,"joinedAt":null,"membershipStatus":"active","createdAgents":[],"profileProjection":"channel_summary",
                         "servers":[{"id":"server-peer","name":"Peer Labs","slug":"peer-labs"},{"id":"server-1","name":"Slock","slug":"slock"}]}
                        """.trimIndent()
                    )
            }
        val repository = RealMemberRepository(sampleStore(), network)

        val profile =
            assertIs<DataResult.Content<build.raft.app.model.MemberProfile>>(
                repository.getMemberProfileInChannel(DataRequestPriority.Visible, MemberId("user-remote"), ServerId("server-1"), ChannelId("channel-1"))
            ).data
        val request = network.requests.single()
        assertEquals("servers/server-1/members/user-remote/profile", request.path)
        assertEquals(mapOf("channelId" to "channel-1"), request.query)
        assertEquals(listOf("server-peer", "server-1"), profile.servers?.map { it.id })
        assertEquals(listOf("Peer Labs", "Slock"), profile.servers?.map { it.name })

        // Without a channel: no query, and no `servers` (the single server fields stand).
        network.memberProfileResponse =
            HomeNetworkResponse(200, """{"userId":"user-remote","serverId":"server-1","name":"ada","membershipStatus":"active"}""")
        val plain =
            assertIs<DataResult.Content<build.raft.app.model.MemberProfile>>(
                repository.getMemberProfile(DataRequestPriority.Visible, MemberId("user-remote"), ServerId("server-1"))
            ).data
        assertEquals(emptyMap(), network.requests.last().query)
        assertEquals(null, plain.servers)
    }

    @Test
    fun emptyChannelRosterPreservesCanonicalAddPermission() = runBlocking {
        val network =
            FakeMemberNetworkClient().apply {
                channelMembersResponse = HomeNetworkResponse(200, """{"humans":[],"agents":[]}""")
                channelDetailResponse = HomeNetworkResponse(200, """{"id":"channel-empty","name":"design","type":"channel","channelCapabilities":{"addChannelMembers":true,"removeChannelMembers":false}}""")
            }
        val repository = RealMemberRepository(sampleStore(), network)

        val result =
            assertIs<DataResult.Content<ChannelMemberSnapshot>>(
                repository.getChannelMembers(DataRequestPriority.Visible, ChannelId("channel-empty"))
            )

        assertEquals(emptyList(), result.data.members)
        assertTrue(result.data.canAddMembers)
    }

    @Test
    fun rosterDoesNotGrantMutationsWithoutMatchingChannelCapabilities() = runBlocking {
        // botiverse/slock 1443178ec2c72422256ea05f7d6fd5e480429e0a:
        // routes/channels.ts attaches channelCapabilities to GET /:id, not /:id/members.
        val cases = listOf(
            HomeNetworkResponse(503, "{}"),
            HomeNetworkResponse(200, """{"id":"channel-empty","name":"design","type":"channel"}"""),
            HomeNetworkResponse(200, """{"id":"another","name":"design","type":"channel","channelCapabilities":{"addChannelMembers":true,"removeChannelMembers":true}}"""),
            HomeNetworkResponse(200, """{"id":"channel-empty","name":"all","type":"channel","channelCapabilities":{"addChannelMembers":true,"removeChannelMembers":true}}"""),
            HomeNetworkResponse(200, """{"id":"channel-empty","name":"design","type":"channel","archivedAt":"2026-09-30T00:00:00Z","channelCapabilities":{"addChannelMembers":true,"removeChannelMembers":true}}"""),
        )
        for (response in cases) {
            val network = FakeMemberNetworkClient().apply {
                channelMembersResponse = HomeNetworkResponse(200, """{"humans":[{"id":"user-1","name":"Alex","effectiveChannelRole":"admin"}],"agents":[]}""")
                channelDetailResponse = response
            }
            val result = assertIs<DataResult.Content<ChannelMemberSnapshot>>(
                RealMemberRepository(sampleStore(), network).getChannelMembers(DataRequestPriority.Visible, ChannelId("channel-empty")))
            assertEquals("admin", result.data.members.single().effectiveChannelRole)
            assertFalse(result.data.canAddMembers)
            assertFalse(result.data.canRemoveMembers)
        }
    }

    @Test
    fun perRowCanRemoveFromChannelSurvivesMutableParticipantJointChannelAndIsClearedWhenImmutable() = runBlocking {
        // #proj-mobile:#400: on a participant joint channel, channelCapabilities.removeChannelMembers
        // is false (humans cannot be removed) while the participant's own agents carry
        // canRemoveFromChannel = true. Mutable channels preserve that per-row flag; archived / #all /
        // missing channel detail force it false (web `!isAllChannel && !isArchived`).
        val roster = HomeNetworkResponse(
            200,
            """{"humans":[{"id":"u-1","name":"Alex","canRemoveFromChannel":false}],"agents":[{"id":"a-1","name":"Bot","canRemoveFromChannel":true}]}""",
        )
        val mutableNetwork = FakeMemberNetworkClient().apply {
            channelMembersResponse = roster
            channelDetailResponse = HomeNetworkResponse(
                200,
                """{"id":"channel-empty","name":"shared","type":"joint","jointRole":"participant","channelCapabilities":{"addChannelMembers":true,"removeChannelMembers":false}}""",
            )
        }
        val mutableResult = assertIs<DataResult.Content<ChannelMemberSnapshot>>(
            RealMemberRepository(sampleStore(), mutableNetwork).getChannelMembers(DataRequestPriority.Visible, ChannelId("channel-empty")),
        )
        assertFalse(mutableResult.data.canRemoveMembers)
        assertEquals(true, mutableResult.data.members.single { it.id == MemberId("a-1") }.canRemoveFromChannel)
        assertEquals(false, mutableResult.data.members.single { it.id == MemberId("u-1") }.canRemoveFromChannel)

        val immutableDetails = listOf(
            HomeNetworkResponse(503, "{}"),
            HomeNetworkResponse(200, """{"id":"channel-empty","name":"shared","type":"joint","archivedAt":"2026-10-01T00:00:00Z","channelCapabilities":{"removeChannelMembers":false}}"""),
        )
        for (detail in immutableDetails) {
            val network = FakeMemberNetworkClient().apply {
                channelMembersResponse = roster
                channelDetailResponse = detail
            }
            val result = assertIs<DataResult.Content<ChannelMemberSnapshot>>(
                RealMemberRepository(sampleStore(), network).getChannelMembers(DataRequestPriority.Visible, ChannelId("channel-empty")),
            )
            assertEquals(false, result.data.members.single { it.id == MemberId("a-1") }.canRemoveFromChannel)
        }
    }

    @Test
    fun roleCapabilityComesFromTheChannelAndOnlyOnAChannelWhoseRolesCanChange() = runBlocking {
        // botiverse/slock bb8b13a1a3: GET /channels/:id `channelCapabilities.changeChannelMemberRoles`;
        // web MemberRoleActions offers nothing on an archived channel or #all.
        val roster = HomeNetworkResponse(
            200,
            """{"humans":[{"id":"user-2","name":"ada","role":"member","effectiveChannelRole":"member","canChangeChannelRole":true}],"agents":[]}""",
        )
        val cases = listOf(
            """{"id":"channel-empty","name":"design","type":"channel","channelCapabilities":{"changeChannelMemberRoles":true}}""" to true,
            """{"id":"channel-empty","name":"design","type":"channel","channelCapabilities":{"changeChannelMemberRoles":false}}""" to false,
            """{"id":"channel-empty","name":"design","type":"channel"}""" to false,
            """{"id":"channel-empty","name":"all","type":"channel","channelCapabilities":{"changeChannelMemberRoles":true}}""" to false,
            """{"id":"channel-empty","name":"design","type":"channel","archivedAt":"2026-09-30T00:00:00Z","channelCapabilities":{"changeChannelMemberRoles":true}}""" to false,
        )
        for ((detail, expected) in cases) {
            val network = FakeMemberNetworkClient().apply {
                channelMembersResponse = roster
                channelDetailResponse = HomeNetworkResponse(200, detail)
            }
            val result = assertIs<DataResult.Content<ChannelMemberSnapshot>>(
                RealMemberRepository(sampleStore(), network).getChannelMembers(DataRequestPriority.Visible, ChannelId("channel-empty")))
            assertEquals(expected, result.data.canChangeMemberRoles, detail)
            assertEquals(true, result.data.members.single().canChangeChannelRole)
        }
    }

    @Test
    fun federateCapabilityComesFromChannelDetailIncludingParticipantJointCopy() = runBlocking {
        // #wg-rbac:#133: GET /channels/:id `channelCapabilities.federateChannels` is preserved on
        // the members snapshot (including on a participant joint copy), forced false on archived or
        // #all channels, and null when omitted by legacy servers or when detail fetch fails.
        val roster = HomeNetworkResponse(200, """{"humans":[],"agents":[]}""")
        val cases =
            listOf(
                HomeNetworkResponse(
                    200,
                    """{"id":"channel-empty","name":"shared","type":"joint","jointRole":"participant","channelCapabilities":{"federateChannels":true}}""",
                ) to true,
                HomeNetworkResponse(
                    200,
                    """{"id":"channel-empty","name":"shared","type":"joint","jointRole":"participant","channelCapabilities":{"federateChannels":false}}""",
                ) to false,
                HomeNetworkResponse(
                    200,
                    """{"id":"channel-empty","name":"shared","type":"joint","jointRole":"host","channelCapabilities":{"federateChannels":true}}""",
                ) to true,
                HomeNetworkResponse(
                    200,
                    """{"id":"channel-empty","name":"shared","type":"joint","jointRole":"participant","archivedAt":"2026-10-01T00:00:00Z","channelCapabilities":{"federateChannels":true}}""",
                ) to false,
                HomeNetworkResponse(
                    200,
                    """{"id":"channel-empty","name":"all","type":"channel","channelCapabilities":{"federateChannels":true}}""",
                ) to false,
                HomeNetworkResponse(
                    200,
                    """{"id":"channel-empty","name":"shared","type":"joint","jointRole":"host"}""",
                ) to null,
                HomeNetworkResponse(503, "{}") to null,
            )
        for ((detail, expected) in cases) {
            val network =
                FakeMemberNetworkClient().apply {
                    channelMembersResponse = roster
                    channelDetailResponse = detail
                }
            val result =
                assertIs<DataResult.Content<ChannelMemberSnapshot>>(
                    RealMemberRepository(sampleStore(), network).getChannelMembers(DataRequestPriority.Visible, ChannelId("channel-empty")),
                )
            assertEquals(expected, result.data.federateChannels, "detail=${detail.code}:${detail.bodyText}")
        }
    }

    @Test
    fun roleChangePatchesTheMembersRolePathWithTheRoleBody() = runBlocking {
        // botiverse/slock bb8b13a1a3 routes/channels.ts PATCH /:id/members/:targetType/:memberId/role
        // (`role` member|admin; targetType user|agent) and web useChannelMembers.changeMemberRole.
        val network = FakeMemberNetworkClient().apply {
            channelRoleResponse = HomeNetworkResponse(
                200,
                """{"changed":true,"channelId":"channel-empty","targetType":"user","targetId":"user-2","channelRole":"admin","authorityRevision":2,"eventId":"evt-1"}""",
            )
        }
        val repository = RealMemberRepository(sampleStore(), network)

        val promoted = assertIs<DataResult.Content<ChannelMemberRoleChangeResult>>(
            repository.changeChannelMemberRole(
                DataRequestPriority.Foreground,
                ChangeChannelMemberRoleRequest(ChannelId("channel-empty"), MemberId("user-2"), build.raft.app.model.MemberKind.Human, ChannelMemberRole.Admin),
            )
        ).data
        val promote = network.requests.last()
        assertEquals("PATCH", promote.method)
        assertEquals("channels/channel-empty/members/user/user-2/role", promote.path)
        assertEquals("""{"role":"admin"}""", promote.bodyText)
        assertTrue(promoted.changed)
        assertEquals("admin", promoted.channelRole)

        network.channelRoleResponse = HomeNetworkResponse(
            200,
            """{"changed":false,"channelId":"channel-empty","targetType":"agent","targetId":"agent-1","channelRole":"member","authorityRevision":1,"eventId":null}""",
        )
        val demoted = assertIs<DataResult.Content<ChannelMemberRoleChangeResult>>(
            repository.changeChannelMemberRole(
                DataRequestPriority.Foreground,
                ChangeChannelMemberRoleRequest(ChannelId("channel-empty"), MemberId("agent-1"), build.raft.app.model.MemberKind.Agent, ChannelMemberRole.Member),
            )
        ).data
        val demote = network.requests.last()
        assertEquals("channels/channel-empty/members/agent/agent-1/role", demote.path)
        assertEquals("""{"role":"member"}""", demote.bodyText)
        assertFalse(demoted.changed)
    }

    @Test
    fun aRefusedRoleChangeIsAnErrorNotContent() = runBlocking {
        val network = FakeMemberNetworkClient().apply {
            channelRoleResponse = HomeNetworkResponse(
                403,
                """{"error":"Server owners and admins cannot be changed from channel role management","code":"protected_server_role"}""",
            )
        }
        val result = RealMemberRepository(sampleStore(), network).changeChannelMemberRole(
            DataRequestPriority.Foreground,
            ChangeChannelMemberRoleRequest(ChannelId("channel-empty"), MemberId("user-2"), build.raft.app.model.MemberKind.Human, ChannelMemberRole.Member),
        )
        assertFalse(result is DataResult.Content)
    }

    @Test
    fun explicitServerMembersLoadDoesNotRequireSelectedServerToAlreadySwitch() = runBlocking {
        val session =
            sampleSession().copy(
                servers =
                    sampleSession().servers +
                        StoredAuthServer(
                            id = "server-2",
                            name = "Second Server",
                            slug = "second-server",
                            ownerId = "user-1",
                            onboardingAgentId = null,
                            plan = "team",
                            planDowngradedAt = null,
                            role = "member",
                            createdAt = "2026-07-01T00:00:00Z"
                        )
            )
        val network = FakeMemberNetworkClient()
        val repository = RealMemberRepository(sampleStore(session), network)

        val result = repository.getMembers(DataRequestPriority.Visible, ServerId("server-2"))

        assertTrue(result is DataResult.Content)
        assertEquals("server-2", result.data.server.id.value)
        assertEquals("Second Server", result.data.server.name)
        assertTrue(result.data.members.any { it.id.value == "user-2" })
        assertTrue(network.calls.contains("GET servers/server-2/members server=server-2"))
        assertTrue(network.calls.contains("GET agents server=server-2"))
        assertTrue(network.calls.contains("GET servers/server-2/machines server=server-2"))
    }

    @Test
    fun agentDetailIncludesTypedSummaryAndLifecycleActions() = runBlocking {
        val network = FakeMemberNetworkClient()
        val repository = RealMemberRepository(sampleStore(), network)
        val agentId = MemberId("agent-1")

        val detail = repository.getAgentDetail(DataRequestPriority.Visible, agentId)
        val start = repository.startAgent(DataRequestPriority.Foreground, agentId)
        val stop = repository.stopAgent(DataRequestPriority.Foreground, agentId)
        val reset = repository.resetAgent(DataRequestPriority.Foreground, AgentResetRequest(agentId, AgentResetMode.Restart))
        val assign = repository.assignAgentMachine(
            DataRequestPriority.Foreground,
            AgentMachineAssignmentRequest(agentId, "machine-2")
        )
        val delete = repository.deleteAgent(DataRequestPriority.Foreground, agentId, AgentArchiveWorkspaceChoice.Delete)

        assertTrue(detail is DataResult.Content)
        assertEquals("agent-1", detail.data.summary?.id)
        assertEquals("codex", detail.data.summary?.runtime)
        assertEquals("gpt-5", detail.data.summary?.model)
        // No saved `reasoningEffort`: the profile shows Default like web, not the running
        // `runtimeProfile.current` effort ("medium").
        assertEquals(null, detail.data.summary?.reasoning)
        assertEquals("local", detail.data.summary?.executionMode)
        assertEquals("machine-1", detail.data.summary?.machineId)
        assertEquals("Mac Studio", detail.data.summary?.machineName)
        assertEquals("0.57.1", detail.data.summary?.daemonVersion)
        assertEquals("/Users/noel/agents/noel/", detail.data.summary?.workspacePath)
        // What the running process reports (web `runtimeProfile.current`, botiverse/slock #13372).
        assertEquals("codex", detail.data.summary?.runningRuntime)
        assertEquals("gpt-5", detail.data.summary?.runningModel)
        assertEquals("user-1", detail.data.summary?.creatorId)
        assertEquals("human", detail.data.summary?.creatorType)
        assertEquals("Artin", detail.data.summary?.creator?.displayName)
        assertEquals("artin", detail.data.summary?.creator?.name)
        assertEquals("pixel:user", detail.data.summary?.creator?.avatarUrl)
        assertEquals(listOf("ANTHROPIC_API_KEY", "OPENAI_API_KEY"), detail.data.summary?.envVarNames)
        assertEquals("Helper", detail.data.summary?.createdAgents?.single()?.displayName)
        assertEquals("idle", detail.data.summary?.activity)
        assertEquals(1, detail.data.directMessages.size)
        assertEquals(2, detail.data.channels.size)
        assertTrue(detail.data.channels.last().activityMuted)
        assertEquals(7L, detail.data.channels.last().muteFromSeq)
        assertEquals(1, detail.data.reminders.size)
        assertEquals(true, detail.data.skills.groups.isNotEmpty())

        assertTrue(start is DataResult.Content)
        assertEquals("start", start.data.action)
        assertTrue(stop is DataResult.Content)
        assertEquals("stop", stop.data.action)
        assertTrue(reset is DataResult.Content)
        assertEquals(AgentResetMode.Restart, reset.data.resetMode)
        assertTrue(assign is DataResult.Content)
        assertEquals("machine-2", assign.data.machineId)
        assertTrue(delete is DataResult.Content)
        assertEquals("delete", delete.data.action)

        assertTrue(network.requests.any { it.matches("GET", "agents/agent-1") })
        assertTrue(network.requests.any { it.matches("GET", "agents/agent-1/agent-dms") })
        assertTrue(network.requests.any { it.matches("GET", "agents/agent-1/channels") })
        assertTrue(network.requests.any { it.matches("GET", "reminders") })
        assertTrue(network.requests.any { it.matches("GET", "agents/agent-1/activity-log") })
        assertTrue(network.requests.any { it.matches("GET", "agents/agent-1/skills") })
        assertTrue(network.requests.any { it.matches("GET", "integrations/agents/agent-1") })
        assertTrue(network.requests.any { it.matches("POST", "agents/agent-1/start") })
        assertTrue(network.requests.any { it.matches("POST", "agents/agent-1/stop") })
        assertTrue(network.requests.any { it.matches("POST", "agents/agent-1/reset") })
        assertTrue(network.requests.any { it.matches("POST", "agents/agent-1/assign-machine") })
        assertTrue(network.requests.any { it.matches("DELETE", "agents/agent-1") })
        assertTrue(network.calls.any { it == """DELETE agents/agent-1 body={"workspace":"delete"}""" })
    }

    @Test
    fun agentDetailSummaryRemainsTheOnlyRequiredSection() = runBlocking {
        val network =
            FakeMemberNetworkClient().apply {
                agentSummaryResponse = HomeNetworkResponse(code = 503, bodyText = """{"error":"unavailable"}""")
            }
        val repository = RealMemberRepository(sampleStore(), network)

        val result = repository.getAgentDetail(DataRequestPriority.Visible, MemberId("agent-1"))

        assertEquals("server_unavailable", assertIs<DataResult.Error>(result).error.code)
        assertTrue(network.requests.any { it.matches("GET", "agents/agent-1/agent-dms") })
        assertTrue(network.requests.any { it.matches("GET", "integrations/agents/agent-1") })
    }

    @Test
    fun agentActivityDoesNotReusePreviousRepositoryResults() = runBlocking {
        val network = FakeMemberNetworkClient()
        val repository = RealMemberRepository(sampleStore(), network)
        val agentId = MemberId("agent-1")

        val first = repository.getAgentActivity(DataRequestPriority.Visible, agentId)
        assertTrue(first is DataResult.Content)
        assertTrue(first.data.activity.any { it.body == "Running task" })

        network.activityLogBody =
            """
            [{"timestamp":1710000999,"entry":{"kind":"agent_status","activity":"idle","detail":"After reset"}}]
            """.trimIndent()
        val merged = repository.getAgentActivity(DataRequestPriority.Visible, agentId)
        assertTrue(merged is DataResult.Content)
        assertTrue(!merged.data.activity.any { it.body == "Running task" })
        assertTrue(merged.data.activity.any { it.body == "After reset" })
    }

    @Test
    fun agentDetailRetainsCompleteIntegrationErrorForVisibleCopy() = runBlocking {
        val network =
            FakeMemberNetworkClient().apply {
                agentIntegrationsResponse =
                    HomeNetworkResponse(
                        code = 500,
                        bodyText = null,
                        errorBodyText = """{"error":"Failed to load agent integrations"}"""
                    )
            }
        val repository = RealMemberRepository(sampleStore(), network)

        val detail = assertIs<DataResult.Content<AgentDetailSnapshot>>(
            repository.getAgentDetail(DataRequestPriority.Visible, MemberId("agent-1"))
        ).data

        assertEquals(AgentAppLoginLoadState.Error, detail.appLogins.loadState)
        assertEquals("server_unavailable", detail.appLogins.errorCode)
        assertEquals("server_unavailable", detail.appLogins.screenError?.code)
        assertEquals(
            "Failed to load agent integrations",
            detail.appLogins.screenError?.serverMessage
        )
    }

    @Test
    fun agentLifecycleDuplicateSubmitIsGuardedInRepository() = runBlocking {
        val network = FakeMemberNetworkClient()
        val repository = RealMemberRepository(sampleStore(), network)
        val agentId = MemberId("agent-1")
        val blocker = CompletableDeferred<Unit>()
        network.startBlocker = blocker

        val first = async { repository.startAgent(DataRequestPriority.Foreground, agentId) }
        withTimeout(1_000) {
            while (!network.calls.contains("POST agents/agent-1/start")) {
                yield()
            }
        }

        val duplicate = repository.stopAgent(DataRequestPriority.Foreground, agentId)
        assertTrue(duplicate is DataResult.Error)
        assertEquals("agent_stop_in_flight", duplicate.error.code)

        blocker.complete(Unit)
        assertTrue(first.await() is DataResult.Content)
    }

    @Test
    fun workspaceStructuredServerErrorReachesFilePreviewVisibleCopy() = runBlocking {
        val network =
            FakeMemberNetworkClient().apply {
                workspaceFileResponse =
                    HomeNetworkResponse(
                        code = 400,
                        bodyText = null,
                        errorBodyText = """{"error":"path query parameter is required"}"""
                    )
            }
        val repository = RealMemberRepository(sampleStore(), network)

        val error =
            assertIs<DataResult.Error>(
                repository.readAgentWorkspaceFile(
                    DataRequestPriority.Visible,
                    MemberId("agent-1"),
                    "/repo/README.md"
                )
            ).error
        val payload =
            RaftFilePreviewPayload().withAgentWorkspaceLoadError(
                error,
                englishErrorPresentationStringsForTest()
            )

        assertEquals("path query parameter is required", payload.message)
        assertEquals("http_400", payload.error)
    }

    @Test
    fun agentProfileUpdateBodyKeepsAvatarChangesExplicit() {
        assertEquals(
            """{"name":"Noel Bot","displayName":"Noel Bot","description":null}""",
            buildSharedAgentProfileUpdateBody(
                AgentProfileUpdateRequest(
                    agentId = MemberId("agent-1"),
                    displayName = " Noel Bot ",
                    description = " "
                )
            )
        )
        assertEquals(
            """{"name":"Noel Bot","displayName":"Noel Bot","description":"Runs tests","avatarUrl":"pixel:robot"}""",
            buildSharedAgentProfileUpdateBody(
                AgentProfileUpdateRequest(
                    agentId = MemberId("agent-1"),
                    displayName = "Noel Bot",
                    description = " Runs tests ",
                    avatarUrl = " pixel:robot ",
                    includeAvatarUrl = true
                )
            )
        )
        assertEquals(
            """{"name":"Noel Bot","displayName":"Noel Bot","description":null,"avatarUrl":null}""",
            buildSharedAgentProfileUpdateBody(
                AgentProfileUpdateRequest(
                    agentId = MemberId("agent-1"),
                    displayName = "Noel Bot",
                    description = null,
                    avatarUrl = null,
                    includeAvatarUrl = true
                )
            )
        )
        assertEquals(
            """{"name":"Noel Bot","displayName":"Noel Bot","description":null,"serverRole":"admin"}""",
            buildSharedAgentProfileUpdateBody(
                AgentProfileUpdateRequest(
                    agentId = MemberId("agent-1"),
                    displayName = "Noel Bot",
                    description = null,
                    serverRole = " Admin ",
                    includeServerRole = true
                )
            )
        )
        assertEquals(
            """{"name":"Noel Bot","displayName":"Noel Bot","description":null,"serverRole":null}""",
            buildSharedAgentProfileUpdateBody(
                AgentProfileUpdateRequest(
                    agentId = MemberId("agent-1"),
                    displayName = "Noel Bot",
                    description = null,
                    serverRole = " ",
                    includeServerRole = true
                )
            )
        )
    }

    private data class RecordedRequest(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val serverId: String?,
        val bodyText: String?
    ) {
        fun matches(expectedMethod: String, expectedPath: String): Boolean =
            method == expectedMethod && path == expectedPath
    }

    private class FakeMemberNetworkClient : RaftNetworkClient {
        companion object {
            val expectedDetailRequestStarts =
                setOf(
                    "GET agents/agent-1",
                    "GET agents/agent-1/agent-dms",
                    "GET agents/agent-1/channels",
                    "GET reminders",
                    "GET agents/agent-1/activity-log",
                    "GET agents/agent-1/skills",
                    "GET integrations/agents/agent-1"
                )
        }

        val calls = mutableListOf<String>()
        val requests = mutableListOf<RecordedRequest>()
        val detailRequestsStarted = linkedSetOf<String>()
        val firstDetailRequestStarted = CompletableDeferred<Unit>()
        var detailRequestGate: CompletableDeferred<Unit>? = null
        var activityLogBody: String =
            """
            [{"timestamp":1710000000,"entry":{"kind":"agent_status","activity":"working","detail":"Running task"}}]
            """.trimIndent()
        var skillsResponse: HomeNetworkResponse? = null
        var workspaceFileResponse: HomeNetworkResponse? = null
        var agentIntegrationsResponse: HomeNetworkResponse? = null
        var agentSummaryResponse: HomeNetworkResponse? = null
        var agentSummaryActivity: String = "idle"
        var agentSummaryActivityDetail: String = "Ready"
        var agentsListActivity: String = "idle"
        var agentsListActivityDetail: String = "Ready"
        var channelMembersResponse: HomeNetworkResponse? = null
        var channelDetailResponse: HomeNetworkResponse = HomeNetworkResponse(403, "{}")
        var memberProfileResponse: HomeNetworkResponse = HomeNetworkResponse(404, """{"error":"User not found"}""")
        var channelRoleResponse: HomeNetworkResponse = HomeNetworkResponse(500, "{}")
        var startBlocker: CompletableDeferred<Unit>? = null
        var patchConfigApply: String? = null
        var patchRefusal: HomeNetworkResponse? = null

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: build.raft.app.model.ServerId?,
            bodyText: String?
        ): HomeNetworkResponse {
            val normalized = path.trim('/')
            requests +=
                RecordedRequest(
                    method = method,
                    path = normalized,
                    query = query.toMap(),
                    serverId = serverIdOverride?.value,
                    bodyText = bodyText
                )
            val requestStart = "$method $normalized"
            if (requestStart in expectedDetailRequestStarts) {
                detailRequestsStarted += requestStart
                firstDetailRequestStarted.complete(Unit)
                detailRequestGate?.await()
            }
            return when {
                normalized == "agents/agent-1" && method == "GET" -> {
                    calls += "GET agents/agent-1"
                    agentSummaryResponse?.let { return it }
                    ok(
                        """
                        {
                          "id":"agent-1",
                          "name":"noel",
                          "displayName":"Noel Bot",
                          "status":"active",
                          "activity":"$agentSummaryActivity",
                          "activityDetail":"$agentSummaryActivityDetail",
                          "creatorId":"user-1",
                          "creatorType":"human",
                          "creator":{
                            "type":"human",
                            "id":"user-1",
                            "name":"artin",
                            "displayName":"Artin",
                            "avatarUrl":"pixel:user",
                            "gravatarHash":"hash-1"
                          },
                          "daemonVersion":"0.57.1",
                          "envVars":{"OPENAI_API_KEY":"configured","ANTHROPIC_API_KEY":"configured"},
                          "createdAgents":[{"id":"agent-child","name":"helper","displayName":"Helper","runtime":"codex","status":"idle"}],
                          "runtimeProfile":{"current":{"machineId":"machine-1","machineName":"Mac Studio","runtime":"codex","model":"gpt-5","reasoningEffort":"medium","executionMode":"local","workspacePathRef":{"label":"Workspace","path":" /Users/noel/agents/noel/ "}}}
                        }
                        """.trimIndent()
                    )
                }
                normalized == "agents/agent-1/agent-dms" && method == "GET" -> {
                    calls += "GET agents/agent-1/agent-dms"
                    ok("""[{"id":"dm-1","peerId":"user-1","peerName":"Artin"}]""")
                }
                normalized == "agents/agent-1/channels" && method == "GET" -> {
                    calls += "GET agents/agent-1/channels"
                    ok(
                        """
                        [
                          {"id":"channel-1","name":"general","description":"Team updates","type":"channel","createdAt":"2026-06-01T00:00:00Z","archivedAt":null,"activityMuted":false,"muteFromSeq":null},
                          {"id":"channel-2","name":"private-ops","description":null,"type":"private","createdAt":"2026-06-02T00:00:00Z","archivedAt":null,"activityMuted":true,"muteFromSeq":7}
                        ]
                        """.trimIndent()
                    )
                }
                normalized == "reminders" && method == "GET" -> {
                    val queryMap = query.toMap()
                    calls += "GET reminders ownerAgentId=${queryMap["ownerAgentId"]} status=${queryMap["status"]}"
                    ok(
                        """
                        {"reminders":[{"reminderId":"reminder-1","ownerAgentId":"agent-1","title":"Ping","fireAt":"2026-05-27T00:00:00Z","status":"scheduled"}]}
                        """.trimIndent()
                    )
                }
                normalized == "agents/agent-1/activity-log" && method == "GET" -> {
                    calls += "GET agents/agent-1/activity-log limit=${query.toMap()["limit"]}"
                    ok(activityLogBody)
                }
                normalized == "agents" && method == "GET" -> {
                    calls += serverIdOverride?.let { "GET agents server=${it.value}" } ?: "GET agents"
                    ok("""[{"id":"agent-1","name":"noel","status":"active","activity":"$agentsListActivity","activityDetail":"$agentsListActivityDetail"}]""")
                }
                normalized == "servers/server-2/members" && method == "GET" -> {
                    calls += "GET servers/server-2/members server=${serverIdOverride?.value}"
                    ok("""{"members":[{"userId":"user-2","name":"second-user","displayName":"Second User"}]}""")
                }
                normalized == "servers/server-2/machines" && method == "GET" -> {
                    calls += "GET servers/server-2/machines server=${serverIdOverride?.value}"
                    ok("[]")
                }
                normalized == "agents/agent-1/skills" && method == "GET" -> {
                    calls += "GET agents/agent-1/skills"
                    skillsResponse?.let { return it }
                    ok(
                        """
                        {"global":[{"name":"docs","displayName":"Docs","description":"Reads docs"}],"workspace":[]}
                        """.trimIndent()
                    )
                }
                normalized == "agents/agent-1/workspace-files/read" && method == "GET" -> {
                    calls += "GET agents/agent-1/workspace-files/read path=${query.toMap()["path"]}"
                    workspaceFileResponse
                        ?: ok(
                            """{"path":"/repo/README.md","content":"# Readme","binary":false,"size":8,"mimeType":"text/markdown","encoding":"utf8"}"""
                        )
                }
                normalized == "integrations/agents/agent-1" && method == "GET" -> {
                    calls += "GET integrations/agents/agent-1"
                    agentIntegrationsResponse ?: ok("[]")
                }
                normalized == "agents/agent-1/start" && method == "POST" -> {
                    calls += "POST agents/agent-1/start"
                    startBlocker?.await()
                    ok("""{"ok":true}""")
                }
                normalized == "agents/agent-1/stop" && method == "POST" -> {
                    calls += "POST agents/agent-1/stop"
                    ok("""{"ok":true}""")
                }
                normalized == "agents/agent-1/reset" && method == "POST" -> {
                    calls += "POST agents/agent-1/reset body=${bodyText.orEmpty()}"
                    ok("""{"ok":true}""")
                }
                normalized == "agents/agent-1/assign-machine" && method == "POST" -> {
                    calls += "POST agents/agent-1/assign-machine body=${bodyText.orEmpty()}"
                    ok("""{"ok":true}""")
                }
                normalized == "agents/agent-1" && method == "PATCH" -> {
                    calls += "PATCH agents/agent-1 body=${bodyText.orEmpty()}"
                    patchRefusal?.let { return it }
                    // slock routes/agents.ts PATCH answer: the agent plus `configApply` (#13372).
                    val apply = patchConfigApply?.let { ""","configApply":"$it"""" }.orEmpty()
                    ok("""{"id":"agent-1","name":"noel","displayName":"Noel Bot","status":"active","runtime":"codex","model":"gpt-5"$apply}""")
                }
                normalized == "agents/agent-1" && method == "DELETE" -> {
                    calls += "DELETE agents/agent-1 body=${bodyText.orEmpty()}"
                    ok("""{"ok":true}""")
                }
                normalized == "channels/channel-empty" && method == "GET" -> channelDetailResponse
                normalized.startsWith("channels/channel-empty/members/") && normalized.endsWith("/role") && method == "PATCH" ->
                    channelRoleResponse
                normalized == "servers/server-1/members/user-remote/profile" && method == "GET" -> memberProfileResponse
                normalized == "channels/channel-empty/members" && method == "GET" ->
                    channelMembersResponse ?: error("Missing channel members response")
                else -> error("Unhandled request $method $normalized query=$query")
            }
        }

        private fun ok(body: String): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = body)
    }

    @Test
    fun agentProfileUpdateCarriesTheServersConfigApply() = runBlocking {
        val network = FakeMemberNetworkClient()
        val repository = RealMemberRepository(sampleStore(), network)
        val request = AgentProfileUpdateRequest(agentId = MemberId("agent-1"), displayName = "Noel Bot", description = null)
        for ((wire, expected) in listOf("applied" to AgentConfigApply.Applied, "when_idle" to AgentConfigApply.WhenIdle, "saved" to AgentConfigApply.Saved, "restarted" to AgentConfigApply.Restarted, null to null)) {
            network.patchConfigApply = wire
            val result = repository.updateAgentProfile(DataRequestPriority.Foreground, request)
            assertTrue(result is DataResult.Content, "wire=$wire")
            assertEquals(expected, result.data.configApply, "wire=$wire")
            assertEquals(null, result.data.withoutConfigApply().configApply)
        }
    }

    @Test
    fun aDescriptionRefusalKeepsTheServersCodeForTheEditor() = runBlocking {
        // slock #15664 routes/agents.ts PATCH /:id: a new or changed description over 300 is a 400
        // `profile_description_invalid`; the editor names it from ScreenError.serverCode.
        val network = FakeMemberNetworkClient()
        network.patchRefusal =
            HomeNetworkResponse(
                code = 400,
                bodyText = null,
                errorBodyText = """{"code":"profile_description_invalid","error":"A new or changed description must be a string of at most 300 characters"}"""
            )
        val repository = RealMemberRepository(sampleStore(), network)
        val request = AgentProfileUpdateRequest(agentId = MemberId("agent-1"), displayName = "Noel Bot", description = "z".repeat(301))
        val result = repository.updateAgentProfile(DataRequestPriority.Foreground, request)
        assertTrue(result is DataResult.Error)
        assertEquals("http_400", result.error.code)
        assertEquals(PROFILE_DESCRIPTION_INVALID_CODE, result.error.serverCode)
        assertEquals("A new or changed description must be a string of at most 300 characters", result.error.serverMessage)
    }

    @Test
    fun runtimeFormV2EditAdvertisesTheClientCapabilitiesAndAPlainProfileEditDoesNot() = runBlocking {
        // slock #13950: the v2 PATCH carries `clientCapabilities` like the edit form it came from.
        val network = FakeMemberNetworkClient()
        val repository = RealMemberRepository(sampleStore(), network)
        val plain = AgentProfileUpdateRequest(agentId = MemberId("agent-1"), displayName = "Noel Bot", description = null)
        val v2 = plain.copy(
            runtimeFormV2 = build.raft.app.data.runtimeform.v2.RuntimeFormV2SubmitBody(
                runtimeId = "pi",
                formValues = kotlinx.serialization.json.JsonObject(emptyMap())
            )
        )
        for ((request, expected) in listOf(v2 to "dependent_select.custom_value_with_options,runtime_form.v3", plain to null)) {
            network.requests.clear()
            assertTrue(repository.updateAgentProfile(DataRequestPriority.Foreground, request) is DataResult.Content)
            val patch = network.requests.single { it.method == "PATCH" && it.path == "agents/agent-1" }
            assertEquals(expected, patch.query["clientCapabilities"], "v2=${request.runtimeFormV2 != null}")
        }
    }

    private fun sampleStore(session: StoredAuthSession = sampleSession()): RaftAuthSessionStore =
        InMemoryRaftAuthSessionStore(session)

    private fun sampleSession(): StoredAuthSession =
        StoredAuthSession(
            user =
                StoredAuthUser(
                    id = "user-1",
                    email = "user@slock.ai",
                    name = "User",
                    displayName = "Slock User",
                    description = null,
                    avatarUrl = null,
                    emailVerified = true
                ),
            accessToken = "sample-access",
            refreshToken = "sample-refresh",
            selectedServerId = "server-1",
            selectedServerSlug = "slock-android",
            servers =
                listOf(
                    StoredAuthServer(
                        id = "server-1",
                        name = "Slock Android",
                        slug = "slock-android",
                        ownerId = "user-1",
                        onboardingAgentId = "agent-1",
                        plan = "team",
                        planDowngradedAt = null,
                        role = "owner",
                        createdAt = "2026-05-06T00:00:00Z"
                    )
                )
        )
}
