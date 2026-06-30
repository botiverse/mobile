package build.raft.app.data

import build.raft.app.model.MemberId
import build.raft.app.model.ChannelId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MemberAgentParserTest {
    @Test
    fun agentsListUsesRuntimeProfileComputerName() {
        val members = parseSharedSettingsAgentsAsMembers(
            """
            [
              {
                "id": "agent-1",
                "name": "builder",
                "displayName": "Builder",
                "status": "active",
                "runtime": "claude",
                "runtimeProfile": {
                  "current": {
                    "machineId": "machine-1",
                    "machineName": "Mac Studio",
                    "daemonVersion": "0.43.0"
                  },
                  "migrationStatus": "stable"
                }
              }
            ]
            """.trimIndent()
        )

        val profile = assertNotNull(members).single()
        assertEquals(
            listOf("claude", "computer:Mac Studio", "computer-id:machine-1"),
            profile.permissionBadges
        )
    }

    @Test
    fun agentsListUsesProfileComputerFieldsWhenPresent() {
        val members = parseSharedSettingsAgentsAsMembers(
            """
            [
              {
                "id": "agent-1",
                "name": "builder",
                "displayName": "Builder",
                "status": "active",
                "runtime": "claude",
                "computerId": "computer-1",
                "computerName": "Office Mini",
                "computerHostname": "office-mini.local"
              }
            ]
            """.trimIndent()
        )

        val profile = assertNotNull(members).single()
        assertEquals(
            listOf("claude", "computer:Office Mini", "computer-id:computer-1"),
            profile.permissionBadges
        )
    }

    @Test
    fun agentsListAcceptsWrappedAgentsPayload() {
        val members = parseSharedSettingsAgentsAsMembers(
            """
            {
              "data": {
                "agents": [
                  {
                    "id": "agent-1",
                    "name": "builder",
                    "displayName": "Builder",
                    "status": "online"
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val profile = assertNotNull(members).single()
        assertEquals("agent-1", profile.id.value)
        assertEquals("Builder", profile.displayName)
    }

    @Test
    fun agentsListFiltersDeletedAgentsLikeReactMembersTab() {
        val members = parseSharedSettingsAgentsAsMembers(
            """
            [
              {
                "id": "agent-active",
                "name": "active",
                "displayName": "Active Agent",
                "status": "online",
                "gravatarHash": "agenthash"
              },
              {
                "id": "agent-deleted",
                "name": "deleted",
                "displayName": "Deleted Agent",
                "status": "offline",
                "deletedAt": "2026-06-06T00:00:00.000Z"
              }
            ]
            """.trimIndent()
        )

        val profiles = assertNotNull(members)
        assertEquals(listOf("agent-active"), profiles.map { it.id.value })
        assertEquals("agenthash", profiles.single().gravatarHash)
    }

    @Test
    fun agentProfilePreservesDeletedStateForHistorySenders() {
        val profile = parseSharedAgentProfile(
            """
            {
              "id": "agent-deleted",
              "name": "deleted",
              "displayName": "Deleted Agent",
              "status": "offline",
              "deletedAt": "2026-06-06T00:00:00.000Z"
            }
            """.trimIndent()
        )

        assertNotNull(profile)
        assertEquals("deleted", profile.membershipStatus)
        assertEquals("2026-06-06T00:00:00.000Z", profile.agentDeletedAt)
    }

    @Test
    fun serverMembersAcceptsWrappedMembersPayload() {
        val snapshot = parseSharedServerMemberSnapshot(
            bodyText =
                """
                {
                  "members": [
                    {
                      "userId": "user-1",
                      "name": "artin",
                      "displayName": "Artin"
                    }
                  ]
                }
                """.trimIndent(),
            server = build.raft.app.model.ServerSummary(
                id = build.raft.app.model.ServerId("server-1"),
                name = "Slock"
            )
        )

        val profile = assertNotNull(snapshot).members.single()
        assertEquals("user-1", profile.id.value)
        assertEquals("Artin", profile.displayName)
    }

    @Test
    fun channelMembersPreserveSourceServerMetadata() {
        val snapshot = parseSharedChannelMemberSnapshot(
            bodyText =
                """
                {
                  "humans": [
                    {
                      "id": "remote-user",
                      "serverId": "server-remote",
                      "serverName": "Remote Lab",
                      "serverSlug": "remote-lab",
                      "name": "remote",
                      "displayName": "Remote User"
                    }
                  ],
                  "agents": [
                    {
                      "id": "remote-agent",
                      "serverId": "server-remote",
                      "serverName": "Remote Lab",
                      "serverSlug": "remote-lab",
                      "name": "builder",
                      "displayName": "Remote Builder",
                      "status": "online"
                    }
                  ]
                }
                """.trimIndent(),
            channelId = ChannelId("joint-1")
        )

        val members = assertNotNull(snapshot).members
        assertEquals(listOf("server-remote", "server-remote"), members.map { it.serverId?.value })
        assertEquals(listOf("Remote Lab", "Remote Lab"), members.map { it.serverName })
        assertEquals(listOf("remote-lab", "remote-lab"), members.map { it.serverSlug })
    }

    @Test
    fun memberProfilePreservesMembershipAndCreatedAgents() {
        val profile = parseSharedMemberProfile(
            """
            {
              "userId": "user-1",
              "name": "artin",
              "displayName": "Artin",
              "role": "owner",
              "joinedAt": "2026-06-01T00:00:00.000Z",
              "membershipStatus": "active",
              "createdAgents": [
                {
                  "id": "agent-1",
                  "name": "builder",
                  "displayName": "Builder",
                  "runtime": "claude",
                  "status": "online"
                }
              ]
            }
            """.trimIndent()
        )

        assertNotNull(profile)
        assertEquals("2026-06-01T00:00:00.000Z", profile.joinedAt)
        assertEquals("active", profile.membershipStatus)
        assertEquals("Owner", profile.roleDescription)
        val agent = profile.createdAgents.single()
        assertEquals("agent-1", agent.id.value)
        assertEquals("Builder", agent.displayName)
        assertEquals("claude", agent.runtime)
    }

    @Test
    fun workspaceLookupReadsRuntimeProfileSummaryCurrent() {
        val lookup = parseSharedAgentWorkspaceLookup(
            """
            [
              {
                "id": "agent-1",
                "name": "builder",
                "status": "active",
                "runtimeProfile": {
                  "current": {
                    "machineId": "machine-1",
                    "machineName": "Mac Studio",
                    "workspaceRef": {
                      "label": "Slock",
                      "path": "/Users/artin/AndroidStudioProjects/Slock",
                      "machineId": "machine-1",
                      "runtime": "claude",
                      "reachable": true
                    }
                  },
                  "migrationStatus": "stable"
                }
              }
            ]
            """.trimIndent(),
            MemberId("agent-1")
        )

        val snapshot = assertNotNull(lookup)
        assertEquals("machine-1", snapshot.computerId)
        assertEquals("Mac Studio", snapshot.computerName)
        assertEquals("Slock", snapshot.directWorkspaces.single().name)
        assertEquals("/Users/artin/AndroidStudioProjects/Slock", snapshot.directWorkspaces.single().path)
    }
}
