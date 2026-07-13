package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForwardMessageTargetRepositoryTest {
    @Test
    fun parsesForwardTargetSearchObjectShape() {
        val targets = parseForwardMessageTargetSearchResults(
            """
                {
                  "items": [
                    {
                      "type": "channel",
                      "id": "channel-1",
                      "title": "#general",
                      "subtitle": "Joined channel",
                      "channelId": "channel-1",
                      "joined": true,
                      "canForwardNow": true
                    },
                    {
                      "type": "channel",
                      "id": "channel-2",
                      "title": "#town-square",
                      "channelId": "channel-2",
                      "joined": false,
                      "requiredAction": "join_channel"
                    },
                    {
                      "type": "human",
                      "id": "user-1",
                      "title": "Ada",
                      "subtitle": "Product",
                      "dmExists": false
                    },
                    {
                      "type": "agent",
                      "id": "agent-1",
                      "title": "Build Bot",
                      "requiredAction": "create_dm"
                    },
                    {
                      "type": "dm",
                      "id": "dm-1",
                      "title": "Cindy",
                      "channelId": "dm-1",
                      "dmExists": true
                    }
                  ]
                }
            """.trimIndent()
        )

        assertEquals(5, targets.size)
        assertEquals(ForwardMessageTargetKind.RecentChannel, targets[0].kind)
        assertTrue(targets[0].canForwardNow)
        assertEquals(ForwardMessageTargetKind.PublicChannelUnjoined, targets[1].kind)
        assertEquals(ForwardMessageTargetRequiredAction.JoinChannel, targets[1].requiredAction)
        assertFalse(targets[1].canForwardNow)
        assertEquals(ForwardMessageTargetKind.HumanDirectMessageCandidate, targets[2].kind)
        assertEquals(ForwardMessageTargetRequiredAction.CreateDirectMessage, targets[2].requiredAction)
        assertEquals(ForwardMessageTargetKind.AgentDirectMessageCandidate, targets[3].kind)
        assertEquals(ForwardMessageTargetRequiredAction.CreateDirectMessage, targets[3].requiredAction)
        assertEquals(ForwardMessageTargetKind.RecentDirectMessage, targets[4].kind)
        assertTrue(targets[4].canForwardNow)
    }

    @Test
    fun parsesForwardTargetSearchArrayShapeAndDedupes() {
        val targets = parseForwardMessageTargetSearchResults(
            """
                [
                  {
                    "type": "human",
                    "id": "user-1",
                    "title": "Ada",
                    "channelId": "dm-user-1",
                    "dmExists": true
                  },
                  {
                    "type": "human",
                    "id": "user-1",
                    "title": "Ada Duplicate",
                    "channelId": "dm-user-1",
                    "dmExists": true
                  }
                ]
            """.trimIndent()
        )

        assertEquals(1, targets.size)
        assertEquals("Ada", targets.single().title)
        assertEquals(ForwardMessageTargetRequiredAction.None, targets.single().requiredAction)
        assertTrue(targets.single().canForwardNow)
    }

    @Test
    fun fallbackSearchUsesExistingJoinedChannelsAndDms() {
        val targets =
            fallbackForwardMessageTargets(
                directMessages =
                    listOf(
                        HomeDirectMessageRow(
                            threadId = ThreadId("dm-ada"),
                            channelId = ChannelId("dm-ada"),
                            name = "Ada",
                            subtitle = "Recent DM",
                            presenceMemberId = MemberId("user-ada")
                        )
                    ),
                channels =
                    listOf(
                        HomeChannelRow(
                            threadId = ThreadId("channel-general"),
                            channelId = ChannelId("channel-general"),
                            name = "general",
                            joined = true
                        ),
                        HomeChannelRow(
                            threadId = ThreadId("channel-design"),
                            channelId = ChannelId("channel-design"),
                            name = "design",
                            joined = false
                        )
                    ),
                query = "d",
                limit = 10
            )

        assertEquals(1, targets.size)
        assertEquals(ForwardMessageTargetKind.RecentDirectMessage, targets.single().kind)
    }

    @Test
    fun fallbackSearchAddsHumanAndAgentCandidatesWithoutDuplicatingExistingDms() {
        val targets =
            fallbackForwardMessageTargets(
                directMessages =
                    listOf(
                        HomeDirectMessageRow(
                            threadId = ThreadId("dm-ada"),
                            channelId = ChannelId("dm-ada"),
                            name = "Ada",
                            presenceMemberId = MemberId("user-ada")
                        )
                    ),
                channels = emptyList(),
                members =
                    listOf(
                        MemberProfile(
                            id = MemberId("user-ada"),
                            displayName = "Ada",
                            handle = "ada",
                            kind = MemberKind.Human
                        ),
                        MemberProfile(
                            id = MemberId("agent-build"),
                            displayName = "Build Agent",
                            handle = "build-agent",
                            kind = MemberKind.Agent
                        ),
                        MemberProfile(
                            id = MemberId("system-1"),
                            displayName = "System",
                            handle = "system",
                            kind = MemberKind.System
                        )
                    ),
                query = "a",
                limit = 10
            )

        assertEquals(listOf("dm-ada", "agent-build"), targets.map { it.id })
        assertEquals(ForwardMessageTargetKind.RecentDirectMessage, targets[0].kind)
        assertEquals(ForwardMessageTargetKind.AgentDirectMessageCandidate, targets[1].kind)
        assertEquals(ForwardMessageTargetRequiredAction.CreateDirectMessage, targets[1].requiredAction)
    }

    @Test
    fun fallbackSearchMatchesMemberHandleWhenDisplayNameDoesNotMatch() {
        val targets =
            fallbackForwardMessageTargets(
                directMessages = emptyList(),
                channels = emptyList(),
                members =
                    listOf(
                        MemberProfile(
                            id = MemberId("agent-build"),
                            displayName = "Build Agent",
                            handle = "release-bot",
                            kind = MemberKind.Agent
                        )
                    ),
                query = "release-bot",
                limit = 10
            )

        assertEquals(listOf("agent-build"), targets.map { it.id })
        assertEquals("@release-bot", targets.single().subtitle)
    }
}
