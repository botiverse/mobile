package build.raft.app

import build.raft.app.data.DataRequestPriority
import build.raft.app.data.parseSharedAgentActivity
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerPermissions
import build.raft.app.model.ServerSummary
import build.raft.app.model.SlockTimeFormat12Hour
import build.raft.app.resources.MR
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.tmm.kmmresource.resource.desc.desc
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun agentDetailWaitsForServerTabOrderBeforeRenderingWithoutLaunchOrder() {
        assertTrue(
            shouldWaitForAgentPanelTabOrderBeforeRender(
                expectedKind = MemberKind.Agent,
                initialAgentTab = null,
                payloadAgentPanelTabOrder = emptyList(),
                storeServerMatches = true,
                storeLoaded = false
            )
        )
    }

    @Test
    fun agentDetailDoesNotWaitForTabOrderWhenLaunchIntentOrOrderExists() {
        assertEquals(
            false,
            shouldWaitForAgentPanelTabOrderBeforeRender(
                expectedKind = MemberKind.Agent,
                initialAgentTab = MembersAgentDetailTab.Activity,
                payloadAgentPanelTabOrder = emptyList(),
                storeServerMatches = false,
                storeLoaded = false
            )
        )
        assertEquals(
            false,
            shouldWaitForAgentPanelTabOrderBeforeRender(
                expectedKind = MemberKind.Agent,
                initialAgentTab = null,
                payloadAgentPanelTabOrder = listOf("activity", "profile"),
                storeServerMatches = false,
                storeLoaded = false
            )
        )
        assertEquals(
            false,
            shouldWaitForAgentPanelTabOrderBeforeRender(
                expectedKind = MemberKind.Agent,
                initialAgentTab = null,
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
    fun humanDetailDoesNotWaitForAgentTabOrder() {
        assertEquals(
            false,
            shouldWaitForAgentPanelTabOrderBeforeRender(
                expectedKind = MemberKind.Human,
                initialAgentTab = null,
                payloadAgentPanelTabOrder = emptyList(),
                storeServerMatches = false,
                storeLoaded = false
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
    fun agentManagementMenuAnchorUsesTriggerBottomEnd() {
        assertEquals(
            Offset(132f, 80f),
            membersManagementMenuAnchor(
                buttonLeftPx = 100f,
                buttonTopPx = 48f,
                buttonWidthPx = 32f,
                buttonHeightPx = 32f
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
                detail = null
            )

        assertTrue(diagnostics.contains("agentId=agent-1"))
        assertTrue(diagnostics.contains("machineName=MacBook"))
        assertTrue(!diagnostics.contains("OPENAI_API_KEY"))
        assertTrue(!diagnostics.contains("Authorization"))
        assertTrue(!diagnostics.contains("accessToken"))
        assertTrue(!diagnostics.contains("Bearer"))
    }

    @Test
    fun agentActivityLogParsesTrajectoryEntriesAndHidesTransientSendTools() {
        val snapshot =
            parseSharedAgentActivity(
                """
                [
                  {"timestamp":1710000000000,"entry":{"kind":"thinking","text":"Checking plan"}},
                  {"timestamp":1710000001000,"entry":{"kind":"tool_start","toolName":"mcp__slock__send_message","toolInput":"hello"}},
                  {"timestamp":1710000002000,"entry":{"kind":"tool_start","toolName":"mcp__fs__read_file","toolInput":{"path":"/repo/README.md","authorization":"Bearer secret"}}},
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
        assertEquals("Output", entries[2].title)
        assertEquals(false, entries[2].refTextEnabled)
        assertEquals("Claimed task", entries[3].title)
        assertEquals(true, entries[3].refTextEnabled)
        assertEquals("Disconnected", entries[5].title)
        assertEquals("Context Compaction Started", entries[6].title)
        assertEquals("Subagent started", entries[7].title)
    }
}
