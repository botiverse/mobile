package build.raft.app.data

import build.raft.app.model.AgentStatus
import build.raft.app.model.avatarWireValueForAvatarSlot
import build.raft.app.model.MemberId
import build.raft.app.model.ChannelId
import build.raft.app.model.Presence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import build.raft.app.model.toMemberProfile

class MemberAgentParserTest {
    @Test
    fun humanProfilePreservesServerGravatarHash() {
        val hash = "a".repeat(64)
        val profile = assertNotNull(
            parseSharedMemberProfile(
                """
                {
                  "userId":"user-cindy",
                  "name":"cindyz",
                  "displayName":"cindyz",
                  "email":"cindyz@example.com",
                  "gravatarHash":"$hash",
                  "avatarUrl":null,
                  "role":"member",
                  "membershipStatus":"active"
                }
                """.trimIndent()
            )
        )

        assertEquals(hash, profile.gravatarHash)
        assertEquals(
            "gravatar:$hash",
            profile.avatarWireValueForAvatarSlot()
        )
    }

    @Test
    fun agentChannelsPreserveMembershipMuteState() {
        val channels = assertNotNull(
            parseSharedAgentChannels(
                """
                [
                  {"id":"channel-1","name":"general","description":"Team updates","type":"channel","createdAt":"2026-06-01T00:00:00Z","archivedAt":null,"activityMuted":false,"muteFromSeq":null},
                  {"id":"channel-2","name":"private-ops","description":null,"type":"private","createdAt":"2026-06-02T00:00:00Z","archivedAt":null,"activityMuted":true,"muteFromSeq":7}
                ]
                """.trimIndent(),
                MemberId("agent-1")
            )
        )

        assertEquals("agent-1", channels.agentId.value)
        assertEquals(2, channels.channels.size)
        assertEquals("general", channels.channels.first().name)
        assertEquals(false, channels.channels.first().activityMuted)
        assertEquals("private", channels.channels.last().type)
        assertEquals(true, channels.channels.last().activityMuted)
        assertEquals(7L, channels.channels.last().muteFromSeq)
    }

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
    fun agentsListFallsBackInactiveLifecycleStatusToOfflineLikeWeb() {
        val members = parseSharedSettingsAgentsAsMembers(
            """
            [
              {
                "id": "agent-1",
                "name": "builder",
                "displayName": "Builder",
                "status": "inactive"
              }
            ]
            """.trimIndent()
        )

        val profile = assertNotNull(members).single()
        assertEquals(AgentStatus.Offline, profile.agentStatus)
        assertEquals(Presence.Offline, profile.presence)
    }

    @Test
    fun agentsListNeverDerivesLivenessFromActiveLifecycleStatus() {
        // task #135 regression lock: "active" is the ACCOUNT lifecycle (not
        // archived/stopped) and stays "active" while the agent's machine is
        // offline. Without a runtime activity fact liveness is UNKNOWN — the
        // old fallback ("active" → Working, mirrored from web) rendered every
        // agent green on the Members page when the machine was down.
        val members = parseSharedSettingsAgentsAsMembers(
            """
            [
              {
                "id": "agent-1",
                "name": "builder",
                "displayName": "Builder",
                "status": "active"
              }
            ]
            """.trimIndent()
        )

        val profile = assertNotNull(members).single()
        assertNull(profile.agentStatus)
        assertEquals(Presence.Offline, profile.presence)
    }

    @Test
    fun agentsListUsesActivityStatusBeforeInactiveLifecycleStatus() {
        val members = parseSharedSettingsAgentsAsMembers(
            """
            [
              {
                "id": "agent-1",
                "name": "builder",
                "displayName": "Builder",
                "status": "inactive",
                "activity": "working"
              }
            ]
            """.trimIndent()
        )

        val profile = assertNotNull(members).single()
        assertEquals(AgentStatus.Working, profile.agentStatus)
        assertEquals(Presence.Active, profile.presence)
    }

    @Test
    fun currentActivityEntryDoesNotTreatActiveLifecycleAsOnline() {
        assertNull(
            currentAgentActivityEntry(
                agentId = MemberId("agent-1"),
                activity = null,
                status = "active",
                activityDetail = null
            )
        )
        assertEquals(
            "working",
            currentAgentActivityEntry(
                agentId = MemberId("agent-1"),
                activity = "working",
                status = "active",
                activityDetail = "Building"
            )?.statusActivity
        )
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
    fun serverMembersExcludeExplicitRemoteJointParticipants() {
        val snapshot = parseSharedServerMemberSnapshot(
            bodyText =
                """
                {
                  "members": [
                    {
                      "userId": "local-user",
                      "serverId": "server-1",
                      "name": "local",
                      "displayName": "Local Member"
                    },
                    {
                      "userId": "legacy-user",
                      "name": "legacy",
                      "displayName": "Legacy Member"
                    },
                    {
                      "userId": "remote-user",
                      "serverId": "server-remote",
                      "serverName": "Remote Lab",
                      "serverSlug": "remote-lab",
                      "name": "remote",
                      "displayName": "Remote Joint Participant"
                    }
                  ]
                }
                """.trimIndent(),
            server = build.raft.app.model.ServerSummary(
                id = build.raft.app.model.ServerId("server-1"),
                name = "Slock"
            )
        )

        val members = assertNotNull(snapshot).members
        assertEquals(listOf("local-user", "legacy-user"), members.map { it.id.value })
        assertEquals(listOf("server-1", "server-1"), members.map { it.serverId?.value })
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
                  "status": "inactive",
                  "activity": "working"
                }
              ]
            }
            """.trimIndent()
        )

        assertNotNull(profile)
        assertEquals("2026-06-01T00:00:00.000Z", profile.joinedAt)
        assertEquals("active", profile.membershipStatus)
        assertEquals("Owner", profile.roleDescription)
        assertEquals("owner", profile.serverRole)
        val agent = profile.createdAgents.single()
        assertEquals("agent-1", agent.id.value)
        assertEquals("Builder", agent.displayName)
        assertEquals("claude", agent.runtime)
        assertEquals(AgentStatus.Working, agent.agentStatus)
        assertEquals("Working", agent.statusLabel)
    }

    @Test
    fun agentProfilePreservesServerRole() {
        val profile = parseSharedAgentProfile(
            """
            {
              "id": "agent-1",
              "name": "builder",
              "displayName": "Builder",
              "runtime": "codex",
              "serverRole": "Admin"
            }
            """.trimIndent()
        )

        assertNotNull(profile)
        assertEquals("admin", profile.serverRole)
        assertEquals("codex", profile.roleDescription)
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

    // task #1119: web keeps `agent.external === true || runtime === "external"` as
    // parallel sources (agentStore.ts); either alone must mark the agent external so a
    // payload missing one key cannot silently demote it to a managed (green) agent.
    @Test
    fun agentsListMarksExternalFromProjectedBoolean() {
        val profile =
            assertNotNull(
                parseSharedSettingsAgentsAsMembers(
                    """
                    [{"id":"agent-x","name":"bridge","displayName":"Bridge","status":"active","runtime":"external","external":true}]
                    """.trimIndent()
                )
            ).single()
        assertTrue(profile.isExternal)
    }

    @Test
    fun agentsListMarksExternalFromRuntimeSentinelWhenBooleanMissing() {
        val profile =
            assertNotNull(
                parseSharedSettingsAgentsAsMembers(
                    """
                    [{"id":"agent-x","name":"bridge","displayName":"Bridge","status":"active","runtime":"external"}]
                    """.trimIndent()
                )
            ).single()
        assertTrue(profile.isExternal)
    }

    @Test
    fun agentsListMarksExternalFromBooleanWhenRuntimeIsAbsent() {
        val profile =
            assertNotNull(
                parseSharedSettingsAgentsAsMembers(
                    """
                    [{"id":"agent-x","name":"bridge","displayName":"Bridge","status":"active","external":true}]
                    """.trimIndent()
                )
            ).single()
        assertTrue(profile.isExternal)
    }

    @Test
    fun agentsListKeepsManagedAgentsNonExternal() {
        val profiles =
            assertNotNull(
                parseSharedSettingsAgentsAsMembers(
                    """
                    [
                      {"id":"agent-a","name":"claude","displayName":"Claude","status":"active","runtime":"claude","external":false},
                      {"id":"agent-b","name":"codex","displayName":"Codex","status":"active","runtime":"codex"},
                      {"id":"agent-c","name":"odd","displayName":"Odd","status":"active","runtime":"External"}
                    ]
                    """.trimIndent()
                )
            )
        assertEquals(3, profiles.size)
        // Exact sentinel match only (shared isExternalAgentRuntime parity): "External" is not it.
        profiles.forEach { assertFalse(it.isExternal, "${it.handle} must stay managed") }
    }

    @Test
    fun agentProfileAndCreatedAgentsCarryExternal() {
        val agent =
            assertNotNull(
                parseSharedAgentProfile(
                    """
                    {"id":"agent-x","name":"bridge","displayName":"Bridge","status":"active","runtime":"external","external":true}
                    """.trimIndent()
                )
            )
        assertTrue(agent.isExternal)

        val human =
            assertNotNull(
                parseSharedMemberProfile(
                    """
                    {
                      "userId":"user-1","name":"owner","displayName":"Owner","role":"member",
                      "createdAgents":[
                        {"id":"agent-x","name":"bridge","displayName":"Bridge","status":"active","runtime":"external"},
                        {"id":"agent-y","name":"claude","displayName":"Claude","status":"active","runtime":"claude"}
                      ]
                    }
                    """.trimIndent()
                )
            )
        assertFalse(human.isExternal)
        assertEquals(listOf(true, false), human.createdAgents.map { it.isExternal })
    }

    @Test
    fun agentProfileToMemberProfilePreservesExternal() {
        val external =
            assertNotNull(
                parseSharedAgentProfile(
                    """
                    {"id":"agent-x","name":"bridge","displayName":"Bridge","status":"active","runtime":"external"}
                    """.trimIndent()
                )
            )
        val managed =
            assertNotNull(
                parseSharedAgentProfile(
                    """
                    {"id":"agent-y","name":"claude","displayName":"Claude","status":"active","runtime":"claude"}
                    """.trimIndent()
                )
            )
        // parseSharedAgentProfile already yields a MemberProfile; round-trip through the
        // human profile's createdAgents (AgentProfile) and back to MemberProfile.
        val human =
            assertNotNull(
                parseSharedMemberProfile(
                    """
                    {"userId":"user-1","name":"owner","displayName":"Owner","role":"member",
                     "createdAgents":[
                       {"id":"agent-x","name":"bridge","displayName":"Bridge","status":"active","runtime":"external"},
                       {"id":"agent-y","name":"claude","displayName":"Claude","status":"active","runtime":"claude"}
                     ]}
                    """.trimIndent()
                )
            )
        assertEquals(
            listOf(true, false),
            human.createdAgents.map { it.toMemberProfile().isExternal }
        )
        assertTrue(external.isExternal)
        assertFalse(managed.isExternal)
    }
}
