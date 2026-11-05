package build.raft.app

import build.raft.app.data.parseSharedMessageContext
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RaftMessagePermalinkPreviewTest {

    @Test
    fun extractsFirstSameServerPermalinkFromPlainBody() {
        val body = "看看这个 https://app.raft.build/s/botiverse/channel/c1?msg=m1"
        val target = body.extractFirstQuotedMessagePermalink(currentServerSlug = "botiverse")
        assertNotNull(target)
        assertEquals("botiverse", target.serverSlug)
        assertEquals("c1", target.channelId.value)
        assertEquals("m1", target.messageId.value)
    }

    @Test
    fun extractsThreadReplyPermalinkWithPercentEncodedThreadParam() {
        val channel = "1417c3d4-a7c2-47ca-b247-7e84bc5842be"
        val body =
            "visual aid @Oscar 来补充\n" +
                "https://app.raft.build/s/raft/channel/$channel" +
                "?msg=e1cf9d25-db9a-4626-9406-be8b3aa8672b" +
                "&thread=$channel%3Aa6e03470-d465-4e0a-801d-945efa952684"
        val target = body.extractFirstQuotedMessagePermalink(currentServerSlug = "raft")
        assertNotNull(target)
        assertEquals(channel, target.channelId.value)
        assertEquals("e1cf9d25-db9a-4626-9406-be8b3aa8672b", target.messageId.value)
        assertEquals("a6e03470-d465-4e0a-801d-945efa952684", target.threadParentMessageId?.value)
    }

    @Test
    fun skipsOtherServerPermalinks() {
        val body = "https://app.raft.build/s/other/channel/c1?msg=m1"
        assertNull(body.extractFirstQuotedMessagePermalink(currentServerSlug = "botiverse"))
        assertNull(body.extractFirstQuotedMessagePermalink(currentServerSlug = null))
    }

    @Test
    fun protectsCodeBlocksAndSkipsMarkdownLinkTargets() {
        val codeBody =
            "```\nhttps://app.raft.build/s/botiverse/channel/c1?msg=code\n```\n" +
                "plain https://app.raft.build/s/botiverse/channel/c2?msg=m2"
        val fromCode = codeBody.extractFirstQuotedMessagePermalink("botiverse")
        assertNotNull(fromCode)
        assertEquals("m2", fromCode.messageId.value)

        val markdownLink = "[link](https://app.raft.build/s/botiverse/channel/c1?msg=m1)"
        assertNull(markdownLink.extractFirstQuotedMessagePermalink("botiverse"))
    }

    @Test
    fun trimsTrailingPunctuationAndHandlesInlineCode() {
        val body = "见 https://app.raft.build/s/botiverse/dm/d1?msg=m1。"
        val target = body.extractFirstQuotedMessagePermalink("botiverse")
        assertNotNull(target)
        assertEquals("m1", target.messageId.value)

        val inlineCode = "`https://app.raft.build/s/botiverse/channel/c1?msg=code`"
        assertNull(inlineCode.extractFirstQuotedMessagePermalink("botiverse"))
    }

    @Test
    fun parsesContextPayloadWithTargetMessage() {
        val body =
            """
            {"channelId":"c1","targetMessageId":"m1","historyLimited":false,
             "messages":[{"id":"m1","seq":3,"channelId":"c1","senderType":"user","senderId":"u1","senderName":"Alice","messageType":"Chat","content":"hello"}]}
            """.trimIndent()
        val payload = parseSharedMessageContext(body, ThreadId("c1"))
        assertEquals("m1", payload.targetMessageId?.value)
        assertEquals(1, payload.messages.size)
        assertEquals("Alice", payload.messages.first().author.displayName)
        assertEquals("hello", payload.messages.first().body)
    }
}
