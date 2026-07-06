package build.raft.app.data

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
}
