package build.raft.app

import build.raft.app.data.AgentActivityEntry
import build.raft.app.data.AgentDetailSnapshot
import build.raft.app.data.AgentDetailSummary
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.EmptyReason
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.ScreenError
import build.raft.app.data.SharedAgentDetailLoadStatus
import build.raft.app.data.SharedAgentDetailOwner
import build.raft.app.data.SharedAgentDetailState
import build.raft.app.data.SharedServerMembersEntry
import build.raft.app.data.SharedServerMembersState
import build.raft.app.data.parseSharedAgentActivity
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.model.RuntimeModelConfig
import build.raft.app.model.ServerId
import build.raft.app.model.ServerPermissions
import build.raft.app.model.ServerSummary
import build.raft.app.model.SlockTimeFormat12Hour
import build.raft.app.resources.MR
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.tmm.kmmresource.resource.desc.desc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedMembersParserTest {
    @Test
    fun cachedAgentDetailUsesBackgroundRefreshWithoutVisibleLoading() {
        assertEquals(
            MemberDetailLoadPlan(
                priority = DataRequestPriority.Background,
                showInitialLoading = false,
                keepCachedContentOnFailure = true
            ),
            memberDetailLoadPlan(hasCachedDetail = true)
        )
        assertEquals(
            MemberDetailLoadPlan(
                priority = DataRequestPriority.Visible,
                showInitialLoading = true,
                keepCachedContentOnFailure = false
            ),
            memberDetailLoadPlan(hasCachedDetail = false)
        )
    }

    @Test
    fun cachedAgentDetailFailureOutcomesKeepContentAndNeverBlockRoute() {
        val cachedPlan = memberDetailLoadPlan(hasCachedDetail = true)

        MemberDetailFailureKind.entries.forEach { failure ->
            assertEquals(
                MemberDetailFailureTransition.KeepCached,
                memberDetailFailureResolution(cachedPlan, failure, "ignored").transition,
                "cached $failure must retain the rendered agent detail"
            )
            assertNull(
                memberDetailFailureResolution(cachedPlan, failure, "ignored").routeErrorCode,
                "cached $failure must not return a blocking route error"
            )
        }
    }

    @Test
    fun coldAgentDetailFailureOutcomesStillBlockAndSetTerminalStatus() {
        val coldPlan = memberDetailLoadPlan(hasCachedDetail = false)

        assertEquals(
            MemberDetailFailureResolution(
                MemberDetailFailureTransition.SetDetailEmpty,
                routeErrorCode = "member_not_found"
            ),
            memberDetailFailureResolution(coldPlan, MemberDetailFailureKind.Empty)
        )
        assertEquals(
            MemberDetailFailureResolution(
                MemberDetailFailureTransition.SetDetailError,
                routeErrorCode = "agent_detail_failed"
            ),
            memberDetailFailureResolution(coldPlan, MemberDetailFailureKind.Error, "agent_detail_failed")
        )
        assertEquals(
            MemberDetailFailureResolution(
                MemberDetailFailureTransition.SetDetailNoPermission,
                routeErrorCode = "agent_detail_forbidden"
            ),
            memberDetailFailureResolution(
                coldPlan,
                MemberDetailFailureKind.NoPermission,
                "agent_detail_forbidden"
            )
        )
    }

    @Test
    fun agentsEndpointArrayParsesAsAgentMembers() {
        val agents =
            parseSharedAgents(
                """
                [
                  {
                    "id": "agent-1",
                    "name": "Android Agent",
                    "status": "online",
                    "computerId": "machine-1"
                  }
                ]
                """.trimIndent()
            )

        assertEquals(1, agents.size)
        assertEquals("agent-1", agents.single().id.value)
        assertEquals("Android Agent", agents.single().displayName)
        assertEquals(MemberKind.Agent, agents.single().kind)
    }

    @Test
    fun legacyMembersParserDoesNotTreatActiveLifecycleAsRuntimeOnline() {
        val agent =
            parseSharedAgents(
                """
                [
                  {
                    "id": "agent-1",
                    "name": "Android Agent",
                    "status": "active"
                  }
                ]
                """.trimIndent()
            ).single()

        assertNull(agent.agentStatus)
        assertEquals(Presence.Offline, agent.presence)
    }

    @Test
    fun membersAndAgentsMergeKeepsAgentSectionWhenServerMembersOnlyHasHumans() {
        val humans =
            parseSharedMembers(
                """
                {
                  "members": [
                    {
                      "id": "human-1",
                      "displayName": "Artin",
                      "type": "human"
                    }
                  ]
                }
                """.trimIndent()
            )
        val agents =
            parseSharedAgents(
                """
                {
                  "agents": [
                    {
                      "id": "agent-1",
                      "displayName": "Cindy",
                      "type": "agent"
                    }
                  ]
                }
                """.trimIndent()
            )

        val merged = mergeSharedMembers(humans, agents)

        assertEquals(2, merged.size)
        assertTrue(merged.any { it.id.value == "human-1" && it.kind == MemberKind.Human })
        assertTrue(merged.any { it.id.value == "agent-1" && it.kind == MemberKind.Agent })
    }

    @Test
    fun mergeDoesNotDuplicateAgentsAlreadyPresentInServerMembers() {
        val members =
            parseSharedMembers(
                """
                {
                  "members": [
                    {
                      "id": "agent-1",
                      "displayName": "Cindy",
                      "type": "agent"
                    }
                  ]
                }
                """.trimIndent()
            )
        val agents =
            parseSharedAgents(
                """
                [
                  {
                    "id": "agent-1",
                    "displayName": "Cindy"
                  }
                ]
                """.trimIndent()
            )

        val merged = mergeSharedMembers(members, agents)

        assertEquals(1, merged.size)
        assertEquals(MemberKind.Agent, merged.single().kind)
    }

    @Test
    fun membersPayloadCanCreateAgentsDefaultsToTrueWhenMissing() {
        val payload =
            """
            {
              "instanceId": "server-1",
              "members": []
            }
            """.trimIndent().toKuiklyMembersPayload()

        assertEquals(true, payload.canCreateAgents)
        assertEquals(true, payload.canInviteHumans)
    }

    @Test
    fun membersPayloadIgnoresLaunchMembersContent() {
        val payload =
            """
            {
              "instanceId": "server-1",
              "members": [
                {
                  "id": "agent-1",
                  "displayName": "Launch Agent",
                  "kind": "agent"
                }
              ]
            }
            """.trimIndent().toKuiklyMembersPayload()

        assertEquals("server-1", payload.instanceId)
        assertEquals(emptyList<MemberProfile>(), payload.members)
    }

    @Test
    fun membersPayloadCanCreateAgentsParsesFalseAndTrue() {
        val falsePayload =
            """
            {
              "instanceId": "server-1",
              "canCreateAgents": false
            }
            """.trimIndent().toKuiklyMembersPayload()
        val truePayload =
            """
            {
              "instanceId": "server-1",
              "canCreateAgents": true
            }
            """.trimIndent().toKuiklyMembersPayload()

        assertEquals(false, falsePayload.canCreateAgents)
        assertEquals(true, truePayload.canCreateAgents)
    }

    @Test
    fun membersPayloadCanInviteHumansParsesFalseAndTrue() {
        val falsePayload =
            """
            {
              "instanceId": "server-1",
              "canInviteHumans": false
            }
            """.trimIndent().toKuiklyMembersPayload()
        val truePayload =
            """
            {
              "instanceId": "server-1",
              "canInviteHumans": true
            }
            """.trimIndent().toKuiklyMembersPayload()

        assertEquals(false, falsePayload.canInviteHumans)
        assertEquals(true, truePayload.canInviteHumans)
    }

    @Test
    fun membersDetailPageDataCarriesIdentityKindAndTimeContextOnly() {
        val payload =
            KuiklyMembersPayload(
                instanceId = "members-server-1",
                title = "Members",
                members = listOf(
                    MemberProfile(
                        id = MemberId("human-artin"),
                        displayName = "Artin",
                        handle = "artin",
                        kind = MemberKind.Human
                    )
                ),
                initialAgentTab = "activity",
                agentPanelTabOrder = listOf("activity", "profile"),
                localOffsetMinutes = 480,
                todayEpochDay = 20624,
                nowEpochMillis = 1782240000000,
                timeFormat = SlockTimeFormat12Hour
            )

        val pageData =
            payload.toMemberDetailPageData(
                MemberDetailIdentity(MemberId("human-artin"), MemberKind.Human)
            )!!

        assertEquals(MEMBER_DETAIL_PAGE, pageData.optString("pageName"))
        assertEquals("members-detail-members-server-1-human-artin", pageData.optString("instanceId"))
        assertEquals("human-artin", pageData.optString("memberId"))
        assertEquals("human", pageData.optString("memberKind"))
        assertEquals("", pageData.optString("sourceServerId"))
        assertEquals("activity", pageData.optString("initialAgentTab"))
        assertEquals("480", pageData.optString("localOffsetMinutes"))
        assertEquals("20624", pageData.optString("todayEpochDay"))
        assertEquals("1782240000000", pageData.optString("nowEpochMillis"))
        assertEquals(SlockTimeFormat12Hour, pageData.optString("timeFormat"))
        assertEquals(listOf("activity", "profile"), pageData.toKuiklyMemberDetailPayload().agentPanelTabOrder)
        assertEquals("", pageData.optString("members"))
        assertEquals("", pageData.optString("selectedMemberId"))
    }

    @Test
    fun membersDetailPageDataLaunchesSelfAndOtherHumansWithTypedSourceServer() {
        val payload = KuiklyMembersPayload(instanceId = "members-server-a")

        listOf("human-self", "human-other").forEach { memberId ->
            val pageData =
                payload.toMemberDetailPageData(
                    MemberDetailIdentity(MemberId(memberId), MemberKind.Human, ServerId("server-a"))
                )!!

            assertEquals(memberId, pageData.optString("memberId"))
            assertEquals("human", pageData.optString("memberKind"))
            assertEquals("server-a", pageData.optString("sourceServerId"))
        }
    }

    @Test
    fun agentDetailPageDataDefaultsInitialTabFromServerOrder() {
        val payload =
            KuiklyMembersPayload(
                instanceId = "members-server-1",
                title = "Members",
                members = listOf(
                    MemberProfile(
                        id = MemberId("agent-cindy"),
                        displayName = "Cindy",
                        handle = "cindy",
                        kind = MemberKind.Agent
                    )
                ),
                agentPanelTabOrder = listOf("activity", "profile")
            )

        val pageData =
            payload.toMemberDetailPageData(
                MemberDetailIdentity(MemberId("agent-cindy"), MemberKind.Agent)
            )!!

        assertEquals("agent", pageData.optString("memberKind"))
        assertEquals("activity", pageData.optString("initialAgentTab"))
        assertEquals(listOf("activity", "profile"), pageData.toKuiklyMemberDetailPayload().agentPanelTabOrder)
    }

    @Test
    fun agentDetailUsesProfileWhileServerTabOrderIsPending() {
        assertEquals(
            MembersAgentDetailTab.Profile,
            membersInitialAgentDetailTabForRender(
                expectedKind = MemberKind.Agent,
                explicitInitialTab = null,
                payloadAgentPanelTabOrder = emptyList(),
                storeServerMatches = true,
                storeLoaded = false
            )
        )
    }

    @Test
    fun agentDetailInitialTabUsesExplicitLaunchIntentOrPayloadOrder() {
        assertEquals(
            MembersAgentDetailTab.Activity,
            membersInitialAgentDetailTabForRender(
                expectedKind = MemberKind.Agent,
                explicitInitialTab = MembersAgentDetailTab.Activity,
                payloadAgentPanelTabOrder = emptyList(),
                storeServerMatches = false,
                storeLoaded = false
            )
        )
        assertEquals(
            MembersAgentDetailTab.Activity,
            membersInitialAgentDetailTabForRender(
                expectedKind = MemberKind.Agent,
                explicitInitialTab = null,
                payloadAgentPanelTabOrder = listOf("activity", "profile"),
                storeServerMatches = false,
                storeLoaded = false
            )
        )
        assertEquals(
            null,
            membersInitialAgentDetailTabForRender(
                expectedKind = MemberKind.Agent,
                explicitInitialTab = null,
                payloadAgentPanelTabOrder = emptyList(),
                storeServerMatches = true,
                storeLoaded = true
            )
        )
    }

    @Test
    fun agentDetailUsesPayloadServerForTabOrderWhileRouteIsLoading() {
        assertEquals(
            "server-from-members",
            memberDetailAgentPanelTabOrderServerId(
                routeServerId = null,
                payloadSourceServerId = " server-from-members "
            )
        )
        assertEquals(
            "server-from-route",
            memberDetailAgentPanelTabOrderServerId(
                routeServerId = " server-from-route ",
                payloadSourceServerId = "server-from-members"
            )
        )
        assertEquals(
            null,
            memberDetailAgentPanelTabOrderServerId(
                routeServerId = null,
                payloadSourceServerId = " "
            )
        )
    }

    @Test
    fun humanDetailDoesNotReceiveAnAgentFallbackTab() {
        assertEquals(
            null,
            membersInitialAgentDetailTabForRender(
                expectedKind = MemberKind.Human,
                explicitInitialTab = null,
                payloadAgentPanelTabOrder = emptyList(),
                storeServerMatches = false,
                storeLoaded = false
            )
        )
    }

    @Test
    fun memberDetailInitialRouteRejectsMatchingAgentRosterSeedUntilCanonicalLoad() {
        val member =
            MemberProfile(
                id = MemberId("agent-cindy"),
                displayName = "Cindy",
                handle = "cindy",
                kind = MemberKind.Agent,
                serverId = ServerId("server-source")
            )
        val server = ServerSummary(id = ServerId("server-source"), name = "Source")
        val route =
            memberDetailInitialRouteState(
                identity = MemberDetailIdentity(member.id, member.kind, server.id),
                activeServerId = server.id.value,
                agentDetailState = seededAgentDetailState(member),
                serverMembersState = serverMembersState(server, member)
            )

        assertNull(route)
    }

    @Test
    fun memberDetailInitialRouteRejectsRemoteAgentRosterSeedUntilCanonicalLoad() {
        val member =
            MemberProfile(
                id = MemberId("agent-remote"),
                displayName = "Remote Agent",
                handle = "remote-agent",
                kind = MemberKind.Agent,
                serverId = ServerId("server-remote")
            )
        val server = ServerSummary(id = ServerId("server-remote"), name = "Remote")
        val route =
            memberDetailInitialRouteState(
                identity = MemberDetailIdentity(member.id, member.kind, server.id),
                activeServerId = "server-local",
                agentDetailState = seededAgentDetailState(member),
                serverMembersState = serverMembersState(server, member)
            )

        assertNull(route)
    }

    @Test
    fun memberDetailInitialRouteRejectsMismatchedOrAmbiguousSeeds() {
        val member =
            MemberProfile(
                id = MemberId("agent-cindy"),
                displayName = "Cindy",
                handle = "cindy",
                kind = MemberKind.Agent
            )
        val serverOne = ServerSummary(id = ServerId("server-1"), name = "One")
        val serverTwo = ServerSummary(id = ServerId("server-2"), name = "Two")
        val identity = MemberDetailIdentity(member.id, member.kind)

        assertNull(
            memberDetailInitialRouteState(
                identity = identity,
                activeServerId = serverOne.id.value,
                agentDetailState = seededAgentDetailState(member.copy(id = MemberId("other-agent"))),
                serverMembersState = serverMembersState(serverOne, member)
            )
        )
        assertNull(
            memberDetailInitialRouteState(
                identity = MemberDetailIdentity(member.id, member.kind, serverOne.id),
                activeServerId = serverOne.id.value,
                agentDetailState = seededAgentDetailState(member.copy(serverId = serverTwo.id)),
                serverMembersState = serverMembersState(serverOne, member.copy(serverId = serverOne.id))
            )
        )
        assertNull(
            memberDetailInitialRouteState(
                identity = MemberDetailIdentity(member.id, member.kind, serverOne.id),
                activeServerId = serverOne.id.value,
                agentDetailState = seededAgentDetailState(member),
                serverMembersState =
                    SharedServerMembersState(
                        entriesByServerId =
                            mapOf(
                                serverOne.id.value to
                                    SharedServerMembersEntry(
                                        serverId = serverOne.id.value,
                                        server = serverTwo,
                                        members = listOf(member)
                                    )
                            )
                    )
            )
        )
        assertNull(
            memberDetailInitialRouteState(
                identity = identity,
                activeServerId = null,
                agentDetailState = seededAgentDetailState(member),
                serverMembersState =
                    SharedServerMembersState(
                        entriesByServerId =
                            mapOf(
                                serverOne.id.value to serverMembersEntry(serverOne, member),
                                serverTwo.id.value to serverMembersEntry(serverTwo, member)
                            )
                    )
            )
        )
    }

    @Test
    fun memberDetailInitialRouteUsesHumanRosterSeedWithoutAgentDetail() {
        val member =
            MemberProfile(
                id = MemberId("human-artin"),
                displayName = "Artin",
                handle = "artin",
                kind = MemberKind.Human,
                serverId = ServerId("server-1")
            )
        val server = ServerSummary(id = ServerId("server-1"), name = "One")
        val route =
            memberDetailInitialRouteState(
                identity = MemberDetailIdentity(member.id, member.kind, server.id),
                activeServerId = server.id.value,
                agentDetailState = SharedAgentDetailState(),
                serverMembersState = serverMembersState(server, member)
            )

        assertEquals(member, assertIs<MemberDetailRouteState.Human>(route).member)
    }

    @Test
    fun memberDetailStatusResolverServerFollowsTypedRouteIdentity() {
        val localServer = ServerSummary(id = ServerId("server-local"), name = "Local")
        val remoteServer = ServerSummary(id = ServerId("server-remote"), name = "Remote")
        val remoteHuman =
            MemberProfile(
                id = MemberId("human-remote"),
                displayName = "Remote Human",
                handle = "remote-human",
                kind = MemberKind.Human,
                serverId = remoteServer.id
            )

        assertEquals(
            localServer.id.value,
            memberDetailStatusResolverServerId(
                MemberDetailRouteState.Agent(localServer, currentUserId = "human-local")
            )
        )
        assertEquals(
            localServer.id.value,
            memberDetailStatusResolverServerId(
                MemberDetailRouteState.Human(
                    server = localServer,
                    currentUserId = "human-local",
                    member = remoteHuman,
                    accessMode = MemberDetailAccessMode.RemoteProfileOnly
                )
            )
        )
        assertNull(memberDetailStatusResolverServerId(MemberDetailRouteState.Loading))
        assertNull(memberDetailStatusResolverServerId(MemberDetailRouteState.Error("failed")))
        assertNull(
            memberDetailStatusResolverServerId(
                MemberDetailRouteState.Agent(
                    server = remoteServer,
                    currentUserId = "human-local",
                    accessMode = MemberDetailAccessMode.RemoteProfileOnly
                )
            )
        )
    }

    @Test
    fun agentActivityLoadStatePreservesRowsWhileRefreshingAndStabilizesFailures() {
        assertEquals(
            MembersAgentActivityLoadState.Loading,
            membersAgentActivityLoadStart(hasCachedActivity = false)
        )
        assertEquals(
            MembersAgentActivityLoadState.Refreshing,
            membersAgentActivityLoadStart(hasCachedActivity = true)
        )

        val content: DataResult<List<AgentActivityEntry>> = DataResult.Content(emptyList())
        val empty: DataResult<List<AgentActivityEntry>> = DataResult.Empty(EmptyReason.NoMembers)
        val error: DataResult<List<AgentActivityEntry>> = DataResult.Error(ScreenError("activity_offline"))
        val blocked: DataResult<List<AgentActivityEntry>> =
            DataResult.NoPermission(PermissionBlocker("activity_forbidden"))

        assertEquals(MembersAgentActivityLoadState.Idle, content.toMembersAgentActivityLoadState())
        assertEquals(MembersAgentActivityLoadState.Idle, empty.toMembersAgentActivityLoadState())
        assertEquals(
            MembersAgentActivityLoadState.Error(ScreenError("activity_offline")),
            error.toMembersAgentActivityLoadState()
        )
        assertEquals(
            MembersAgentActivityLoadState.Error(
                ScreenError("activity_forbidden", retryable = false)
            ),
            blocked.toMembersAgentActivityLoadState()
        )
    }

    @Test
    fun agentActivityRequestFenceRejectsOldMemberServerAndGenerationResults() {
        val fence =
            MembersAgentActivityRequestFence(
                memberId = MemberId("agent-1"),
                serverId = ServerId("server-1"),
                generation = 7L
            )
        val identity = MemberDetailIdentity(MemberId("agent-1"), MemberKind.Agent, ServerId("server-1"))

        assertTrue(fence.matches(identity, ServerId("server-1"), currentGeneration = 7L))
        assertFalse(
            fence.matches(
                identity.copy(memberId = MemberId("agent-2")),
                ServerId("server-1"),
                currentGeneration = 7L
            )
        )
        assertFalse(fence.matches(identity, ServerId("server-2"), currentGeneration = 7L))
        assertFalse(fence.matches(identity, ServerId("server-1"), currentGeneration = 8L))
        assertFalse(
            fence.matches(
                identity.copy(sourceServerId = ServerId("server-2")),
                ServerId("server-1"),
                currentGeneration = 7L
            )
        )
        assertFalse(
            fence.matches(
                identity.copy(kind = MemberKind.Human),
                ServerId("server-1"),
                currentGeneration = 7L
            )
        )
    }

    @Test
    fun agentProfileRequestFenceRejectsAgentServerAndRetryGenerationChanges() {
        val identity = MemberDetailIdentity(MemberId("agent-1"), MemberKind.Agent, ServerId("server-1"))
        val fence = MembersAgentProfileRequestFence(identity.memberId, ServerId("server-1"), generation = 3L)

        assertTrue(fence.matches(identity, "server-local", currentGeneration = 3L))
        assertFalse(fence.matches(identity.copy(memberId = MemberId("agent-2")), "server-local", currentGeneration = 3L))
        assertFalse(fence.matches(identity.copy(sourceServerId = ServerId("server-2")), "server-local", currentGeneration = 3L))
        assertFalse(fence.matches(identity, "server-local", currentGeneration = 4L))
        assertFalse(fence.matches(identity.copy(kind = MemberKind.Human), "server-local", currentGeneration = 3L))

        val localIdentity = identity.copy(sourceServerId = null)
        assertTrue(fence.matches(localIdentity, "server-1", currentGeneration = 3L))
        assertFalse(fence.matches(localIdentity, "server-2", currentGeneration = 3L))
    }

    @Test
    fun agentRuntimeOptionsFenceRejectsEveryRequestIdentityDimension() {
        val identity = MemberDetailIdentity(MemberId("agent-1"), MemberKind.Agent, ServerId("server-1"))
        val fence =
            MembersAgentRuntimeOptionsRequestFence(
                memberId = identity.memberId,
                serverId = ServerId("server-1"),
                machineId = "machine-1",
                runtimeId = "codex",
                profileGeneration = 4L,
                generation = 9L
            )

        assertTrue(fence.matches(identity, "server-local", "machine-1", "codex", 4L, 9L))
        assertFalse(fence.matches(identity.copy(memberId = MemberId("agent-2")), "server-local", "machine-1", "codex", 4L, 9L))
        assertFalse(fence.matches(identity.copy(sourceServerId = ServerId("server-2")), "server-local", "machine-1", "codex", 4L, 9L))
        assertFalse(fence.matches(identity, "server-local", "machine-2", "codex", 4L, 9L))
        assertFalse(fence.matches(identity, "server-local", "machine-1", "claude", 4L, 9L))
        assertFalse(fence.matches(identity, "server-local", "machine-1", "codex", 5L, 9L))
        assertFalse(fence.matches(identity, "server-local", "machine-1", "codex", 4L, 10L))
    }

    @Test
    fun staleRuntimeOptionsRetireOnlyTheirOwnGenerationInFlight() {
        val key = "machine-a" to "codex"
        val inFlight = setOf(key)

        assertEquals(
            emptySet(),
            membersAgentRuntimeRequestCompletionInFlight(inFlight, mapOf(key to 2L), key, 2L),
            "A→B→A stale completion must retire its still-owned loading key"
        )
        assertEquals(
            inFlight,
            membersAgentRuntimeRequestCompletionInFlight(inFlight, mapOf(key to 3L), key, 2L),
            "an old retry must not clear the newer retry's in-flight key"
        )
    }

    @Test
    fun agentRuntimeConfigRequiresCanonicalRuntimeAndModelWithoutDefaults() {
        assertEquals(
            MembersAgentRuntimeConfigResolution.Unavailable("agent_runtime_profile_missing"),
            null.toMembersRuntimeConfigResolution()
        )
        assertEquals(
            MembersAgentRuntimeConfigResolution.Unavailable("agent_runtime_missing"),
            AgentDetailSummary(id = "agent-1", name = "one", model = "gpt-5").toMembersRuntimeConfigResolution()
        )
        assertEquals(
            MembersAgentRuntimeConfigResolution.Unavailable("agent_runtime_model_missing"),
            AgentDetailSummary(id = "agent-1", name = "one", runtime = "codex").toMembersRuntimeConfigResolution()
        )

        val content =
            assertIs<MembersAgentRuntimeConfigResolution.Content>(
                AgentDetailSummary(
                    id = "agent-1",
                    name = "one",
                    runtime = " codex ",
                    model = " gpt-5 ",
                    runtimeModelKind = "preset"
                ).toMembersRuntimeConfigResolution()
            )
        assertEquals("codex", content.config.runtime)
        assertEquals(RuntimeModelConfig.Preset("gpt-5"), content.config.model)
    }

    @Test
    fun agentRuntimeSaveRequiresResolvedExactIdOptionsAndRejectsUnavailableState() {
        val duplicateLabels =
            SharedCreateAgentRuntimeModelCatalog(
                models =
                    listOf(
                        SharedCreateAgentRuntimeModelOption(id = "model-a", label = "Same label"),
                        SharedCreateAgentRuntimeModelOption(id = "model-b", label = "Same label")
                    )
            )
        fun enabled(
            runtime: String = "codex",
            model: String = "model-b",
            catalog: SharedCreateAgentRuntimeModelCatalog? = duplicateLabels,
            loading: Boolean = false,
            error: String? = null
        ) = membersAgentRuntimeConfigSaveEnabled(
            runtime = runtime,
            model = model,
            customModelMode = false,
            customModelName = "",
            machineRuntimeIds = listOf("codex"),
            runtimeModelsCatalog = catalog,
            runtimeModelsLoading = loading,
            runtimeModelsError = error
        )

        assertTrue(enabled())
        assertFalse(enabled(model = "Same label"))
        assertFalse(enabled(model = "missing"))
        assertFalse(enabled(runtime = "claude"))
        assertFalse(enabled(catalog = null))
        assertFalse(enabled(loading = true))
        assertFalse(enabled(error = "runtime_models_unavailable"))
    }

    @Test
    fun agentRuntimeSaveAttemptUsesDraftFieldsBeforeCanonicalCatalogValidation() {
        assertTrue(
            membersAgentRuntimeConfigSaveAttemptEnabled(
                runtime = "codex",
                model = "gpt-5.6-sol",
                customModelMode = false,
                customModelName = "",
                machineRuntimeIds = listOf("codex")
            )
        )
        assertTrue(
            membersAgentRuntimeConfigSaveAttemptEnabled(
                runtime = "claude",
                model = "",
                customModelMode = true,
                customModelName = "vendor/private-model",
                machineRuntimeIds = listOf("claude")
            )
        )
        assertFalse(
            membersAgentRuntimeConfigSaveAttemptEnabled(
                runtime = "codex",
                model = "",
                customModelMode = false,
                customModelName = "",
                machineRuntimeIds = listOf("codex")
            )
        )
        assertFalse(
            membersAgentRuntimeConfigSaveAttemptEnabled(
                runtime = "claude",
                model = "",
                customModelMode = true,
                customModelName = "   ",
                machineRuntimeIds = listOf("claude")
            )
        )
        assertFalse(
            membersAgentRuntimeConfigSaveAttemptEnabled(
                runtime = "codex",
                model = "gpt-5.6-sol",
                customModelMode = false,
                customModelName = "",
                machineRuntimeIds = listOf("claude")
            )
        )
    }

    @Test
    fun openedRuntimeEditorFailsClosedAfterIdentityProfileOrCatalogGenerationChanges() {
        val opened =
            MembersAgentProfileEditAuthority(
                memberId = MemberId("agent-1"),
                environmentId = "https://api.raft.build/api",
                accountId = "account-1",
                serverId = ServerId("server-1"),
                profileGeneration = 4L
            )

        assertTrue(membersAgentProfileEditAuthorityMatches(opened, opened, 7L, 7L))
        assertFalse(
            membersAgentProfileEditAuthorityMatches(
                opened,
                opened.copy(memberId = MemberId("agent-2")),
                7L,
                7L
            )
        )
        assertFalse(
            membersAgentProfileEditAuthorityMatches(
                opened,
                opened.copy(environmentId = "https://staging.raft.build/api"),
                7L,
                7L
            )
        )
        assertFalse(
            membersAgentProfileEditAuthorityMatches(
                opened,
                opened.copy(accountId = "account-2"),
                7L,
                7L
            )
        )
        assertFalse(
            membersAgentProfileEditAuthorityMatches(
                opened,
                opened.copy(serverId = ServerId("server-2")),
                7L,
                7L
            )
        )
        assertFalse(
            membersAgentProfileEditAuthorityMatches(
                opened,
                opened.copy(profileGeneration = 5L),
                7L,
                7L
            )
        )
        assertFalse(membersAgentProfileEditAuthorityMatches(opened, opened, 7L, 8L))
        assertFalse(membersAgentProfileEditAuthorityMatches(null, opened, 7L, 7L))

        val matchingStore =
            SharedAgentDetailState(
                agentId = opened.memberId,
                detailOwner = opened.ownerOrNull(),
                detailProfileGeneration = opened.profileGeneration,
                detailServerId = opened.serverId,
                detail = AgentDetailSnapshot(
                    agentId = opened.memberId,
                    summary = AgentDetailSummary(id = opened.memberId.value, name = "one"),
                    directMessages = emptyList(),
                    reminders = emptyList(),
                    activity = emptyList()
                )
            )
        assertTrue(membersAgentProfileStoreMatchesAuthority(matchingStore, opened))
        assertFalse(
            membersAgentProfileStoreMatchesAuthority(
                matchingStore.copy(
                    detailOwner =
                        opened.copy(accountId = "account-2").ownerOrNull()
                ),
                opened
            )
        )
        assertFalse(
            membersAgentProfileStoreMatchesAuthority(
                matchingStore.copy(detailServerId = ServerId("server-2")),
                opened
            )
        )
        assertFalse(
            membersAgentProfileStoreMatchesAuthority(
                matchingStore.copy(agentId = MemberId("agent-2")),
                opened
            )
        )
    }

    @Test
    fun runtimeModelPresentationUsesCatalogThenKnownStaticLabelsThenRawId() {
        val catalog =
            SharedCreateAgentRuntimeModelCatalog(
                models =
                    listOf(
                        SharedCreateAgentRuntimeModelOption(
                            id = "claude-opus-5",
                            label = "Claude Opus 5"
                        )
                    )
            )

        assertEquals(
            "Claude Opus 5",
            membersAgentRuntimeModelDisplayLabel("claude", "claude-opus-5", catalog)
        )
        assertEquals(
            "Claude Sonnet",
            membersAgentRuntimeModelDisplayLabel("claude", "sonnet", null)
        )
        assertEquals("unknown-model", membersAgentRuntimeModelDisplayLabel("claude", "unknown-model", null))
        // Catalog labels that merely echo the id are treated as absent; the
        // raw id itself still becomes the chip label (web parity, task #284).
        assertEquals(
            "raw-only",
            membersAgentRuntimeModelDisplayLabel(
                runtimeId = "claude",
                modelId = "raw-only",
                runtimeModelsCatalog =
                    SharedCreateAgentRuntimeModelCatalog(
                        models = listOf(SharedCreateAgentRuntimeModelOption("raw-only", "raw-only"))
                    )
            )
        )
        // Blank configured model still hides the row entirely.
        assertNull(membersAgentRuntimeModelDisplayLabel("claude", " ", null))
    }

    @Test
    fun remoteAgentDetailOwnerAllowsUnjoinedSourceServerButLocalFullStillRequiresMembership() {
        val remoteOwner =
            membersAgentDetailOwnerForSession(
                environmentId = "https://api.raft.build/api/",
                accountId = "account-1",
                currentServerId = "server-a",
                joinedServerIds = listOf("server-a"),
                expectedServerId = ServerId("server-b"),
                requireCurrentServer = false
            )

        assertEquals(
            SharedAgentDetailOwner(
                environmentId = "https://api.raft.build/api",
                accountId = "account-1",
                serverId = ServerId("server-b")
            ),
            remoteOwner
        )
        assertNull(
            membersAgentDetailOwnerForSession(
                environmentId = "https://api.raft.build/api",
                accountId = "account-1",
                currentServerId = "server-a",
                joinedServerIds = listOf("server-a"),
                expectedServerId = ServerId("server-b"),
                requireCurrentServer = true
            )
        )
        assertNull(
            membersAgentDetailOwnerForSession(
                environmentId = "https://api.raft.build/api",
                accountId = "account-1",
                currentServerId = "server-a",
                joinedServerIds = emptyList(),
                expectedServerId = ServerId("server-a"),
                requireCurrentServer = true
            )
        )
        assertNull(
            membersAgentDetailOwnerForSession(
                environmentId = "https://api.raft.build/api",
                accountId = "account-1",
                currentServerId = null,
                joinedServerIds = listOf("server-a"),
                expectedServerId = ServerId("server-b"),
                requireCurrentServer = false
            )
        )
        assertNotEquals(
            remoteOwner,
            membersAgentDetailOwnerForSession(
                environmentId = "https://staging.raft.build/api",
                accountId = "account-1",
                currentServerId = "server-a",
                joinedServerIds = listOf("server-a"),
                expectedServerId = ServerId("server-b"),
                requireCurrentServer = false
            )
        )
        assertNotEquals(
            remoteOwner,
            membersAgentDetailOwnerForSession(
                environmentId = "https://api.raft.build/api",
                accountId = "account-2",
                currentServerId = "server-a",
                joinedServerIds = listOf("server-a"),
                expectedServerId = ServerId("server-b"),
                requireCurrentServer = false
            )
        )
    }

    @Test
    fun memberDetailPageDataCarriesSourceServerIdentityWithoutProfileContent() {
        val payload =
            KuiklyMembersPayload(
                instanceId = "members-server-1",
                title = "Members",
                members = listOf(
                    MemberProfile(
                        id = MemberId("remote-human"),
                        displayName = "Remote Human",
                        handle = "remote-human",
                        kind = MemberKind.Human,
                        description = "Loaded from channel member projection",
                        serverId = ServerId("server-remote"),
                        serverName = "Remote Workspace"
                    )
                )
            )

        val pageData =
            payload.toMemberDetailPageData(
                MemberDetailIdentity(
                    memberId = MemberId("remote-human"),
                    kind = MemberKind.Human,
                    sourceServerId = ServerId("server-remote")
                )
            )!!

        assertEquals("remote-human", pageData.optString("memberId"))
        assertEquals("human", pageData.optString("memberKind"))
        assertEquals("server-remote", pageData.optString("sourceServerId"))
        assertEquals("", pageData.optString("members"))
        assertEquals("", pageData.optString("profile"))
        assertEquals("", pageData.optString("description"))
    }

    @Test
    fun memberDetailPayloadParsesMemberKind() {
        val payload =
            JSONObject(
                """
                {
                  "instanceId": "detail-human-artin",
                  "memberId": "human-artin",
                  "memberKind": "human"
                }
                """.trimIndent()
            ).toKuiklyMemberDetailPayload()

        assertEquals("detail-human-artin", payload.instanceId)
        assertEquals("human-artin", payload.identity?.memberId?.value)
        assertEquals(MemberKind.Human, payload.identity?.kind)
    }

    @Test
    fun memberDetailPayloadParsesSourceServerIdentity() {
        val payload =
            JSONObject(
                """
                {
                  "instanceId": "detail-human-artin",
                  "memberId": "human-artin",
                  "memberKind": "human",
                  "sourceServerId": "server-remote"
                }
                """.trimIndent()
            ).toKuiklyMemberDetailPayload()

        assertEquals("server-remote", payload.identity?.sourceServerId?.value)
    }

    @Test
    fun hostMembersPayloadMapsCreateAgentsToManageSettingsPermission() {
        val restrictedServer =
            ServerSummary(
                id = ServerId("server-1"),
                name = "Restricted",
                permissions = ServerPermissions(canManageSettings = false)
            )
        val adminServer =
            restrictedServer.copy(
                permissions = ServerPermissions(canManageSettings = true)
            )

        assertEquals(false, buildHostMembersPayload(restrictedServer, null).canCreateAgents)
        assertEquals(true, buildHostMembersPayload(adminServer, null).canCreateAgents)
        assertEquals(emptyList<MemberProfile>(), buildHostMembersPayload(adminServer, null).members)
        assertEquals(
            listOf("activity", "profile"),
            buildHostMembersPayload(adminServer, null, agentPanelTabOrder = listOf("activity", "profile")).agentPanelTabOrder
        )
    }

    @Test
    fun hostMembersPayloadMapsInviteHumansToInviteMemberPermission() {
        val restrictedServer =
            ServerSummary(
                id = ServerId("server-1"),
                name = "Restricted",
                permissions = ServerPermissions(canInviteMember = false)
            )
        val inviteAllowedServer =
            restrictedServer.copy(
                permissions = ServerPermissions(canInviteMember = true)
            )

        assertEquals(false, buildHostMembersPayload(restrictedServer, null).canInviteHumans)
        assertEquals(true, buildHostMembersPayload(inviteAllowedServer, null).canInviteHumans)
    }

    @Test
    fun agentDetailTabsHidePrivateSurfacesWithoutAccessAndDeletedPermissions() {
        assertEquals(
            listOf(MembersAgentDetailTab.Profile),
            membersVisibleAgentTabs(canViewPrivateSurfaces = false, deleted = false)
        )
        assertEquals(
            listOf(
                MembersAgentDetailTab.Profile,
                MembersAgentDetailTab.Activity,
                MembersAgentDetailTab.Chat,
                MembersAgentDetailTab.Reminders,
                MembersAgentDetailTab.Workspace,
                MembersAgentDetailTab.Apps
            ),
            membersVisibleAgentTabs(canViewPrivateSurfaces = true, deleted = false)
        )
        assertEquals(
            listOf(
                MembersAgentDetailTab.Profile,
                MembersAgentDetailTab.Activity,
                MembersAgentDetailTab.Chat,
                MembersAgentDetailTab.Reminders,
                MembersAgentDetailTab.Workspace,
                MembersAgentDetailTab.Apps
            ),
            membersVisibleAgentTabs(canViewPrivateSurfaces = true, deleted = true)
        )
    }

    @Test
    fun remoteAgentAccessModeForcesProfileOnlyEvenWhenCurrentServerCanViewPrivateTabs() {
        val remoteIdentity =
            MemberDetailIdentity(
                memberId = MemberId("agent-remote"),
                kind = MemberKind.Agent,
                sourceServerId = ServerId("server-b")
            )

        assertEquals(
            MemberDetailAccessMode.RemoteProfileOnly,
            remoteIdentity.memberDetailAccessMode(currentServerId = "server-a")
        )
        assertEquals(
            listOf(MembersAgentDetailTab.Profile),
            membersAgentDetailTabsForAccess(
                accessMode = MemberDetailAccessMode.RemoteProfileOnly,
                canViewPrivateSurfaces = true,
                deleted = false
            )
        )
        assertEquals(
            MemberDetailAccessMode.LocalFull,
            remoteIdentity
                .copy(sourceServerId = ServerId("server-a"))
                .memberDetailAccessMode(currentServerId = "server-a")
        )
    }

    @Test
    fun agentProfileLoadNoticeStaysInsideProfileTab() {
        assertTrue(
            membersAgentDetailShowsProfileStateNotice(
                selectedTab = MembersAgentDetailTab.Profile,
                avatarManagementMode = false
            )
        )
        listOf(
            MembersAgentDetailTab.Activity,
            MembersAgentDetailTab.Chat,
            MembersAgentDetailTab.Reminders,
            MembersAgentDetailTab.Workspace,
            MembersAgentDetailTab.Apps
        ).forEach { tab ->
            assertEquals(
                false,
                membersAgentDetailShowsProfileStateNotice(
                    selectedTab = tab,
                    avatarManagementMode = false
                ),
                "profile API notice leaked into $tab"
            )
        }
        assertEquals(
            false,
            membersAgentDetailShowsProfileStateNotice(
                selectedTab = MembersAgentDetailTab.Profile,
                avatarManagementMode = true
            )
        )
    }

    @Test
    fun agentLifecyclePrimaryActionFollowsRuntimeStatus() {
        assertEquals(
            MembersAgentLifecycleAction.Start,
            membersPrimaryLifecycleAction(AgentStatus.Offline, deleted = false)
        )
        assertEquals(
            MembersAgentLifecycleAction.Start,
            membersPrimaryLifecycleAction(AgentStatus.Disconnected, deleted = false)
        )
        assertEquals(
            MembersAgentLifecycleAction.Stop,
            membersPrimaryLifecycleAction(AgentStatus.Online, deleted = false)
        )
        assertEquals(
            MembersAgentLifecycleAction.Stop,
            membersPrimaryLifecycleAction(AgentStatus.Working, deleted = false)
        )
        assertEquals(null, membersPrimaryLifecycleAction(AgentStatus.Online, deleted = true))
    }

    @Test
    fun agentLifecyclePrimaryActionPrefersResolvedPresenceSsotOverDetailFallback() {
        assertEquals(
            MembersAgentLifecycleAction.Start,
            membersResolvedPrimaryLifecycleAction(
                resolvedStatus = AgentStatus.Offline,
                detailFallbackStatus = AgentStatus.Working,
                deleted = false
            )
        )
        assertEquals(
            MembersAgentLifecycleAction.Stop,
            membersResolvedPrimaryLifecycleAction(
                resolvedStatus = AgentStatus.Working,
                detailFallbackStatus = AgentStatus.Offline,
                deleted = false
            )
        )
        assertEquals(
            null,
            membersResolvedPrimaryLifecycleAction(
                resolvedStatus = null,
                detailFallbackStatus = AgentStatus.Working,
                deleted = false,
                hasResolvedPresence = true
            ),
            "an explicit unknown SSOT state must not expose a stale lifecycle action"
        )
        assertEquals(
            MembersAgentLifecycleAction.Stop,
            membersResolvedPrimaryLifecycleAction(
                resolvedStatus = null,
                detailFallbackStatus = AgentStatus.Working,
                deleted = false,
                hasResolvedPresence = false
            )
        )
    }

    @Test
    fun agentManagementMenuMatchesReactHeaderActions() {
        val onlineItems =
            buildMembersManagementMenuItems(
                primaryAction = MembersAgentLifecycleAction.Stop,
                canManage = true,
                deleted = false,
                inProgress = false
            )

        assertEquals(listOf("stop", "restart"), onlineItems.map { it.id })
        assertEquals(
            listOf(MR.strings.member_action_stop_agent.desc(), MR.strings.member_label_restart_reset.desc()),
            onlineItems.map { it.label }
        )
        assertEquals(SlockLucideIconName.Square, onlineItems[0].icon)
        assertEquals(SlockLucideIconName.RotateCcw, onlineItems[1].icon)

        val offlineItems =
            buildMembersManagementMenuItems(
                primaryAction = MembersAgentLifecycleAction.Start,
                canManage = true,
                deleted = false,
                inProgress = false
            )

        assertEquals(listOf("start", "restart"), offlineItems.map { it.id })
        assertEquals(
            listOf(MR.strings.member_action_start_agent.desc(), MR.strings.member_label_restart_reset.desc()),
            offlineItems.map { it.label }
        )
    }

    @Test
    fun agentManagementMenuHidesWhenUnavailable() {
        assertEquals(
            emptyList(),
            buildMembersManagementMenuItems(
                primaryAction = MembersAgentLifecycleAction.Stop,
                canManage = false,
                deleted = false,
                inProgress = false
            )
        )
        assertEquals(
            emptyList(),
            buildMembersManagementMenuItems(
                primaryAction = MembersAgentLifecycleAction.Stop,
                canManage = true,
                deleted = true,
                inProgress = false
            )
        )
    }

    @Test
    fun agentStatusDisplayStateUsesActivityForColorAndPulse() {
        val member =
            MemberProfile(
                id = MemberId("agent-1"),
                displayName = "Cindy",
                handle = "@Cindy",
                kind = MemberKind.Agent,
                agentStatus = AgentStatus.Offline
            )

        val displayState =
            member.membersAgentStatusDisplayState(
                summaryStatus = "offline",
                summaryActivity = "working"
            )

        assertEquals(AgentStatus.Working, displayState.status)
        assertEquals(SlockColor.BrandYellow, displayState.color)
        assertEquals(true, displayState.pulsing)
    }

    @Test
    fun agentStatusLabelUsesDisplayCaseForWireActivity() {
        assertEquals(
            "Working",
            membersAgentStatusLabel(
                status = AgentStatus.Working,
                summaryActivity = "working",
                fallback = "Agent"
            )
        )
        assertEquals(
            "Running extension",
            membersAgentStatusLabel(
                status = null,
                summaryActivity = "running_extension",
                fallback = "Agent"
            )
        )
    }

    @Test
    fun agentStatusDisplayStateUsesOrangeForErrorWithoutPulse() {
        val member =
            MemberProfile(
                id = MemberId("agent-1"),
                displayName = "Cindy",
                handle = "@Cindy",
                kind = MemberKind.Agent,
                agentStatus = AgentStatus.Online
            )

        val displayState =
            member.membersAgentStatusDisplayState(
                summaryStatus = "active",
                summaryActivity = "error"
            )

        assertEquals(AgentStatus.Error, displayState.status)
        assertEquals(SlockColor.AccentOrange, displayState.color)
        assertEquals(false, displayState.pulsing)
    }

    @Test
    fun agentStatusDisplayStateDoesNotTreatActiveLifecycleAsOnline() {
        val member =
            MemberProfile(
                id = MemberId("agent-1"),
                displayName = "Cindy",
                handle = "@Cindy",
                kind = MemberKind.Agent,
                agentStatus = null,
                presence = Presence.Offline
            )

        val displayState =
            member.membersAgentStatusDisplayState(summaryStatus = "active")

        assertEquals(null, displayState.status)
        assertEquals(SlockColor.StatusNeutralGray, displayState.color)
        assertEquals(false, displayState.pulsing)
    }

    @Test
    fun agentActivityErrorDisplayDetailExtractsReactErrorFields() {
        assertEquals(
            "Runtime crashed",
            membersAgentActivityErrorDisplayDetail("""{"message":"Runtime crashed"}""")
        )
        assertEquals(
            "Provider rejected request",
            membersAgentActivityErrorDisplayDetail("""{"error":{"message":"Provider rejected request"}}""")
        )
        assertEquals(
            "Tool failed",
            membersAgentActivityErrorDisplayDetail("""{"errors":[{"message":"Tool failed"}]}""")
        )
        assertEquals(
            "plain runtime error",
            membersAgentActivityErrorDisplayDetail("plain runtime error")
        )
        assertEquals(
            "Error: Runtime crashed",
            membersAgentActivityErrorText("""{"message":"Runtime crashed"}""")
        )
    }

    @Test
    fun agentStatusDisplayStateFallsBackToMemberStatusAndPresence() {
        val agent =
            MemberProfile(
                id = MemberId("agent-1"),
                displayName = "Cindy",
                handle = "@Cindy",
                kind = MemberKind.Agent,
                agentStatus = AgentStatus.Online
            )
        val human =
            MemberProfile(
                id = MemberId("human-1"),
                displayName = "Artin",
                handle = "@artin",
                kind = MemberKind.Human,
                presence = build.raft.app.model.Presence.Away
            )

        val agentDisplayState = agent.membersAgentStatusDisplayState()
        val humanDisplayState = human.membersAgentStatusDisplayState()

        assertEquals(AgentStatus.Online, agentDisplayState.status)
        assertEquals(SlockColor.AccentLime, agentDisplayState.color)
        assertEquals(false, agentDisplayState.pulsing)
        assertEquals(null, humanDisplayState.status)
        assertEquals(SlockColor.BrandYellow, humanDisplayState.color)
        assertEquals(false, humanDisplayState.pulsing)
    }

    @Test
    fun agentDiagnosticsExportUsesSafeSummaryOnly() {
        val diagnostics =
            buildMembersAgentDiagnostics(
                MemberProfile(
                    id = MemberId("agent-1"),
                    displayName = "Cindy",
                    handle = "@Cindy",
                    kind = MemberKind.Agent,
                    agentStatus = AgentStatus.Working,
                    permissionBadges = listOf("computer:MacBook")
                ),
                detail = null,
                statusIndicatorState =
                    slockAgentStatusIndicatorState(
                        status = AgentStatus.Working,
                        presence = Presence.Active,
                        external = false
                    )
            )

        assertTrue(diagnostics.contains("agentId=agent-1"))
        assertTrue(diagnostics.contains("status=Working"))
        assertTrue(diagnostics.contains("machineName=MacBook"))
        assertTrue(!diagnostics.contains("OPENAI_API_KEY"))
        assertTrue(!diagnostics.contains("Authorization"))
        assertTrue(!diagnostics.contains("accessToken"))
        assertTrue(!diagnostics.contains("Bearer"))
    }

    @Test
    fun profileProjectionCannotOverwriteDetailRuntimeStatus() {
        val detail =
            AgentDetailSnapshot(
                agentId = MemberId("agent-1"),
                summary =
                    AgentDetailSummary(
                        id = "agent-1",
                        name = "Cindy",
                        status = "working",
                        activity = "working"
                    ),
                directMessages = emptyList(),
                reminders = emptyList(),
                activity = emptyList()
            )
        val staleProfile =
            MemberProfile(
                id = MemberId("agent-1"),
                displayName = "Cindy Updated",
                handle = "@Cindy",
                kind = MemberKind.Agent,
                agentStatus = AgentStatus.Online,
                presence = Presence.Active
            )

        val projected = detail.withMemberProfileProjection(staleProfile)

        assertEquals("Cindy Updated", projected.summary?.displayName)
        assertEquals("working", projected.summary?.status)
        assertEquals("working", projected.summary?.activity)
    }

    @Test
    fun detailProjectionBuildsStaticMemberProfileOnly() {
        val profile =
            AgentDetailSnapshot(
                agentId = MemberId("agent-1"),
                summary =
                    AgentDetailSummary(
                        id = "agent-1",
                        name = "Cindy",
                        status = "active",
                        activity = "working"
                    ),
                directMessages = emptyList(),
                reminders = emptyList(),
                activity = emptyList()
            ).toStaticMembersProfile(
                ServerSummary(id = ServerId("server-1"), name = "Server One")
            )

        assertNull(profile.agentStatus)
        assertEquals(Presence.Offline, profile.presence)
    }

    @Test
    fun agentActivityLogParsesTrajectoryEntriesAndHidesTransientSendTools() {
        val snapshot =
            parseSharedAgentActivity(
                """
                [
                  {"timestamp":1710000000000,"entry":{"kind":"thinking","text":"Checking plan"}},
                  {"timestamp":1710000001000,"entry":{"kind":"tool_start","toolName":"mcp__slock__send_message","toolInput":"hello"}},
                  {"timestamp":1710000002000,"entry":{"kind":"tool_start","toolName":"mcp__fs__read_file","toolInput":{"path":"/repo/README.md","authorization":"Bearer secret"},"subagent":{"parentToolUseId":"toolu-1","subagentType":"Explore","phase":"active"}}},
                  {"timestamp":1710000003000,"entry":{"kind":"text","text":"free-form #1 output"}},
                  {"timestamp":1710000004000,"entry":{"kind":"slock_action","title":"Claimed task","text":"task #32 in #members专修:fbd27c76"}},
                  {"timestamp":1710000005000,"entry":{"kind":"system","title":"Reminder","text":"Check #all:aa97eb79"}},
                  {"timestamp":1710000006000,"entry":{"kind":"status","activity":"offline","detail":"Machine disconnected"}},
                  {"timestamp":1710000007000,"entry":{"kind":"compaction_started"}},
                  {"timestamp":1710000008000,"entry":{"kind":"subagent.started","message":"Visual checker started"}}
                ]
                """.trimIndent(),
                MemberId("agent-1")
            )

        val entries = snapshot?.activity.orEmpty()

        assertEquals(8, entries.size)
        assertTrue(entries.none { it.toolName == "mcp__slock__send_message" })
        assertEquals("Thinking", entries[0].title)
        assertEquals("Read file", entries[1].title)
        assertEquals("Structured input: path", entries[1].body)
        assertEquals(true, entries[1].isSubagent)
        assertEquals("Explore", entries[1].subagentType)
        assertEquals("Output", entries[2].title)
        assertEquals(false, entries[2].refTextEnabled)
        assertEquals("Claimed task", entries[3].title)
        assertEquals(true, entries[3].refTextEnabled)
        assertEquals("Disconnected", entries[5].title)
        assertEquals("Context Compaction Started", entries[6].title)
        assertEquals("Subagent started", entries[7].title)
    }

    @Test
    fun agentActivityToolSecondaryUsesOnlyRealInputAndPreservesMetadata() {
        val noInput =
            parseSharedAgentActivity(
                """[{"timestamp":1710000000000,"entry":{"kind":"tool_start","toolName":"check_messages"}}]""",
                MemberId("agent-1"),
            )?.activity?.single() ?: error("Expected one parsed tool_start row")
        val withInput =
            AgentActivityEntry(
                id = "tool-2",
                type = "tool_start",
                title = "Search messages",
                body = "assignee=agent-1",
                details = listOf("Tool" to "search_messages", "Input" to "assignee=agent-1"),
                toolName = "search_messages",
                toolInput = "assignee=agent-1",
            )
        val mobileOnlyAction =
            AgentActivityEntry(
                id = "action-1",
                type = "slock_action",
                title = "Claimed 1 task",
                body = "target: dm:@artin",
                refTextEnabled = true,
            )

        assertEquals(null, noInput.membersActivitySecondaryText())
        assertEquals(listOf("Tool" to "check_messages"), noInput.details)
        assertEquals("assignee=agent-1", withInput.membersActivitySecondaryText())
        assertEquals("target: dm:@artin", mobileOnlyAction.membersActivitySecondaryText())
    }

    @Test
    fun agentActivityParsesWebLifecycleStatusKindsWithoutDroppingMobileDetail() {
        val entries =
            parseSharedAgentActivity(
                """
                [
                  {"timestamp":1710000010000,"entry":{"kind":"status","activity":"online","activityKind":"online","detail":"Computer started","detailKind":"computer_started"}},
                  {"timestamp":1710000011000,"entry":{"kind":"status","activity":"working","detail":"Starting agent","detailKind":"starting"}},
                  {"timestamp":1710000012000,"entry":{"kind":"status","activity":"error","detail":"Restart failed","detailKind":"computer_operation_failed"}},
                  {"timestamp":1710000013000,"entry":{"kind":"status","activity":"offline","detail":"Computer shutdown","detailKind":"stopped"}}
                ]
                """.trimIndent(),
                MemberId("agent-1"),
            )?.activity.orEmpty()

        assertEquals(
            listOf("Started", "Starting", "Computer operation failed", "Stopped"),
            entries.map { it.title },
        )
        assertEquals("Computer started", entries[0].body)
        assertEquals(null, entries[1].body)
        assertEquals("Restart failed", entries[2].body)
        assertEquals("Computer shutdown", entries[3].body)
    }

    @Test
    fun agentActivitySubagentBadgeMatchesWebKindsAndTypeOptionality() {
        val entries =
            parseSharedAgentActivity(
                """
                [
                  {"timestamp":1710000020000,"entry":{"kind":"tool_start","toolName":"read_file","subagent":{"parentToolUseId":"toolu-1"}}},
                  {"timestamp":1710000021000,"entry":{"kind":"status","activity":"working","detail":"Running","subagent":{"subagentType":"Plan"}}},
                  {"timestamp":1710000022000,"entry":{"kind":"thinking","text":"Plan","subagent":{"subagentType":"Explore"}}},
                  {"timestamp":1710000023000,"entry":{"kind":"text","text":"Done","subagent":{"subagentType":"Explore"}}}
                ]
                """.trimIndent(),
                MemberId("agent-1"),
            )?.activity.orEmpty()

        assertEquals(true, entries.all { it.isSubagent })
        assertEquals("Subagent", entries[0].membersActivitySubagentBadgeLabel())
        assertEquals("Subagent: Plan", entries[1].membersActivitySubagentBadgeLabel())
        assertEquals(null, entries[2].membersActivitySubagentBadgeLabel())
        assertEquals(null, entries[3].membersActivitySubagentBadgeLabel())
    }

    private fun seededAgentDetailState(member: MemberProfile): SharedAgentDetailState =
        SharedAgentDetailState(
            agentId = member.id,
            detailServerId = member.serverId,
            detail =
                AgentDetailSnapshot(
                    agentId = member.id,
                    summary =
                        AgentDetailSummary(
                            id = member.id.value,
                            name = member.handle,
                            displayName = member.displayName,
                            description = member.description,
                            avatarUrl = member.avatarUrl
                        ),
                    directMessages = emptyList(),
                    reminders = emptyList(),
                    activity = emptyList()
                ),
            detailLoadStatus = SharedAgentDetailLoadStatus.Content
        )

    private fun serverMembersState(
        server: ServerSummary,
        member: MemberProfile
    ): SharedServerMembersState =
        SharedServerMembersState(
            entriesByServerId = mapOf(server.id.value to serverMembersEntry(server, member))
        )

    private fun serverMembersEntry(
        server: ServerSummary,
        member: MemberProfile
    ): SharedServerMembersEntry =
        SharedServerMembersEntry(
            serverId = server.id.value,
            server = server,
            members = listOf(member)
        )
}
