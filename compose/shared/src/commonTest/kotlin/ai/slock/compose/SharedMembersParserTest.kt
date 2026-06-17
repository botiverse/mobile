package ai.slock.compose

import ai.slock.compose.data.parseSharedAgentActivity
import ai.slock.compose.model.AgentStatus
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.ServerPermissions
import ai.slock.compose.model.ServerSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SharedMembersParserTest {
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

        assertEquals(false, buildHostMembersPayload(restrictedServer, emptyList(), null).canCreateAgents)
        assertEquals(true, buildHostMembersPayload(adminServer, emptyList(), null).canCreateAgents)
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
                MembersAgentDetailTab.Permissions,
                MembersAgentDetailTab.Dms,
                MembersAgentDetailTab.Reminders,
                MembersAgentDetailTab.Workspace,
                MembersAgentDetailTab.Apps,
                MembersAgentDetailTab.Activity
            ),
            membersVisibleAgentTabs(canViewPrivateSurfaces = true, deleted = false)
        )
        assertEquals(
            listOf(
                MembersAgentDetailTab.Profile,
                MembersAgentDetailTab.Dms,
                MembersAgentDetailTab.Reminders,
                MembersAgentDetailTab.Workspace,
                MembersAgentDetailTab.Apps,
                MembersAgentDetailTab.Activity
            ),
            membersVisibleAgentTabs(canViewPrivateSurfaces = true, deleted = true)
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
                  {"timestamp":1710000007000,"entry":{"kind":"compaction_started"}}
                ]
                """.trimIndent(),
                MemberId("agent-1")
            )

        val entries = snapshot?.activity.orEmpty()

        assertEquals(7, entries.size)
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
    }
}
