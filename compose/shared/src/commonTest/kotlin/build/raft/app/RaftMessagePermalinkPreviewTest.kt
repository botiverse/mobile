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
