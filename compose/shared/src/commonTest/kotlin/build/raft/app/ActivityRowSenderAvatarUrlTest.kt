package build.raft.app

import build.raft.app.data.parseSharedInboxSnapshot
import build.raft.app.data.InboxItem
import build.raft.app.model.MemberKind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An Activity row's `senderAvatarUrl` (pending server PR, botiverse/slock: the avatar of the sender
 * the row shows — channel/dm: the last message's sender, thread: the latest replier). A joint
 * channel's sender from another server is not in this server's member directory, so the row's face
 * falls back to it, as ThreadPage does with a reply's `senderAvatarUrl`. Row shapes follow
 * channelService.ts `InboxItem` (botiverse/slock @ 696782067).
 */
class ActivityRowSenderAvatarUrlTest {

    @Test
    fun pendingServerPrChannelRowParsesSenderAvatarUrlOntoTheLastMessageSender() {
        val item = parseSingle(channelRow(senderAvatarUrl = "\"https://api.example/avatars/users/0a1b2c3d.webp\""))

        assertEquals("u-ext", item.lastMessageSenderId)
        assertEquals("https://api.example/avatars/users/0a1b2c3d.webp", item.lastMessageSenderAvatarWireValue)
        assertEquals(null, item.latestActivitySenderAvatarWireValue)
    }

    @Test
    fun pendingServerPrThreadRowParsesSenderAvatarUrlOntoTheLatestReplier() {
        val item = parseSingle(threadRow(senderAvatarUrl = "\"/avatars/users/abc123.webp\""))

        assertEquals("u-ext", item.latestActivitySenderId)
        assertEquals("/avatars/users/abc123.webp", item.latestActivitySenderAvatarWireValue)
        assertEquals(null, item.lastMessageSenderAvatarWireValue)
    }

    @Test
    fun pendingServerPrAbsentNullOrBlankSenderAvatarUrlLeavesTheRowWithoutOne() {
        listOf(null, "null", "\"  \"").forEach { raw ->
            val channel = parseSingle(channelRow(senderAvatarUrl = raw))
            assertEquals(null, channel.lastMessageSenderAvatarWireValue, "channel raw=$raw")
            val thread = parseSingle(threadRow(senderAvatarUrl = raw))
            assertEquals(null, thread.latestActivitySenderAvatarWireValue, "thread raw=$raw")
        }
    }

    @Test
    fun pendingServerPrRowFaceUsesTheMemberAvatarThenSenderAvatarUrlThenThePlaceholder() {
        val external = parseSingle(channelRow(senderAvatarUrl = "\"https://api.example/avatars/users/0a1b2c3d.webp\""))
        val identity = external.activitySenderAvatarIdentity()
        assertEquals(MemberKind.Human, identity.senderKind)

        // A person's avatar is a stored Raft upload (`/avatars/users/<hex>.webp`, slock avatarService
        // isStoredUserAvatarUrl), the only shape the human avatar slot accepts.
        // A member of this server: the directory's avatar wins.
        assertEquals("/avatars/users/ffee0011.webp", activityFaceAvatarWireValue("/avatars/users/ffee0011.webp", identity))
        // Another server's member (not in the directory): the row's senderAvatarUrl.
        assertEquals("https://api.example/avatars/users/0a1b2c3d.webp", activityFaceAvatarWireValue(null, identity))
        // Neither: the placeholder person, as before.
        val bare = parseSingle(channelRow(senderAvatarUrl = null)).activitySenderAvatarIdentity()
        assertEquals(null, activityFaceAvatarWireValue(null, bare))
    }

    @Test
    fun pendingServerPrExternalAgentThreadReplierFallsBackToSenderAvatarUrlBeforeThePixelRobot() {
        val thread =
            parseSingle(
                threadRow(senderAvatarUrl = "\"https://cdn.example/agent-ext.png\"")
                    .replace("\"latestActivitySenderType\":\"user\"", "\"latestActivitySenderType\":\"agent\"")
            )
        val identity = thread.activitySenderAvatarIdentity()
        assertEquals(MemberKind.Agent, identity.senderKind)
        assertEquals("https://cdn.example/agent-ext.png", activityFaceAvatarWireValue(null, identity))
    }

    private fun parseSingle(row: String): InboxItem =
        parseSharedInboxSnapshot(
            """{"items":[$row],"hasMore":false,"totalCount":1,"totalUnreadCount":0}"""
        )?.items?.single() ?: error("inbox payload did not parse")

    private fun senderAvatarField(raw: String?): String = raw?.let { ""","senderAvatarUrl":$it""" }.orEmpty()

    private fun channelRow(senderAvatarUrl: String?): String =
        """
        {"kind":"channel","channelId":"c-joint","channelName":"shared","channelType":"joint",
         "lastMessageId":"m-1","latestActivitySeq":"41","doneFrontierSeq":"41","firstUnreadMessageId":null,
         "firstMentionMessageId":null,"lastMessageAt":"2026-10-20T00:00:00Z","lastMessagePreview":"hi",
         "lastMessageSenderType":"user","lastMessageSenderId":"u-ext","lastMessageSenderName":"Ext",
         "unreadCount":1,"hasMention":false${senderAvatarField(senderAvatarUrl)}}
        """.trimIndent()

    private fun threadRow(senderAvatarUrl: String?): String =
        """
        {"kind":"thread","threadChannelId":"t-1","parentMessageId":"p-1","parentChannelId":"c-joint",
         "parentChannelName":"shared","parentChannelType":"joint","parentMessagePreview":"p",
         "parentMessageSenderType":"user","parentMessageSenderId":"u-local","latestActivityPreview":"r",
         "latestActivitySenderType":"user","latestActivitySenderId":"u-ext","latestActivitySenderName":"Ext",
         "latestActivityMessageId":"r-1","latestActivitySeq":"7","doneFrontierSeq":"7",
         "firstUnreadMessageId":null,"firstMentionMessageId":null,"lastActivityAt":"2026-10-20T00:00:00Z",
         "lastReplyAt":null,"replyCount":1,"unreadCount":0,"hasMention":false,"taskNumber":null,
         "taskStatus":null,"taskClaimedByName":null${senderAvatarField(senderAvatarUrl)}}
        """.trimIndent()
}
