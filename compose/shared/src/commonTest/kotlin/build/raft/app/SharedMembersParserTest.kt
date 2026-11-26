package build.raft.app

import build.raft.app.resources.*
import build.raft.app.data.AgentActivityEntry
import build.raft.app.data.AgentDetailSnapshot
import build.raft.app.data.AgentDetailSummary
import build.raft.app.data.DataResult
import build.raft.app.data.EmptyReason
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.ScreenError
import build.raft.app.data.SharedAgentDetailLoadStatus
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedMembersParserTest {
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
    fun agentDetailInitialTabUsesExplicitLaunchIntentOrPayloadOrder() {
        assertEquals(
            MembersAgentDetailTab.Activity,
            membersInitialAgentDetailTabForRender(
                expectedKind = MemberKind.Agent,
                explicitInitialTab = MembersAgentDetailTab.Activity,
                payloadAgentPanelTabOrder = emptyList(),
                orderKnown = false
            )
        )
        assertEquals(
            MembersAgentDetailTab.Activity,
            membersInitialAgentDetailTabForRender(
                expectedKind = MemberKind.Agent,
                explicitInitialTab = null,
                payloadAgentPanelTabOrder = listOf("activity", "profile"),
                orderKnown = false
            )
        )
        assertEquals(
            null,
            membersInitialAgentDetailTabForRender(
                expectedKind = MemberKind.Agent,
                explicitInitialTab = null,
                payloadAgentPanelTabOrder = emptyList(),
                orderKnown = true
            )
        )
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
    fun runtimeModelPresentationUsesCatalogThenKnownStaticLabelsAndFallsBackToRawId() {
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
        // task #284: an agent genuinely configured with a model id not present in the
        // catalog/static map must still show *something* — hide-as-missing regressed to
        // "no model chip" on real profiles.
        assertEquals("unknown-model", membersAgentRuntimeModelDisplayLabel("claude", "unknown-model", null))
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
        assertNull(membersAgentRuntimeModelDisplayLabel("claude", "", null))
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
                MembersAgentDetailTab.Usage,
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
            listOf(Res.string.member_action_stop_agent.desc(), Res.string.member_label_restart_reset.desc()),
            onlineItems.map { it.label }
        )
        assertEquals(RaftLucideIconName.Square, onlineItems[0].icon)
        assertEquals(RaftLucideIconName.RotateCcw, onlineItems[1].icon)

        val offlineItems =
            buildMembersManagementMenuItems(
                primaryAction = MembersAgentLifecycleAction.Start,
                canManage = true,
                deleted = false,
                inProgress = false
            )

        assertEquals(listOf("start", "restart"), offlineItems.map { it.id })
        assertEquals(
            listOf(Res.string.member_action_start_agent.desc(), Res.string.member_label_restart_reset.desc()),
            offlineItems.map { it.label }
        )
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
                    raftAgentStatusIndicatorState(
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
        assertEquals("read_file", entries[1].title)
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
                            avatarUrl = member.avatar?.wireValue
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
