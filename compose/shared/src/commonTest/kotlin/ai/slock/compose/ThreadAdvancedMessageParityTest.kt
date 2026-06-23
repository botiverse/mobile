package ai.slock.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ThreadAdvancedMessageParityTest {
    @Test
    fun quoteFormattingPrefixesWholeMessageLines() {
        assertEquals(
            "> first\n>\n> second",
            formatThreadMessageQuote("first\n\nsecond")
        )
        assertEquals("", formatThreadMessageQuote(" \n "))
    }

    @Test
    fun quoteAppendPreservesExistingDraftWithBlankSeparator() {
        assertEquals(
            "existing\n\n> quoted\n\n",
            appendThreadQuoteToComposer("existing\n", "> quoted")
        )
    }

    @Test
    fun webLinkTargetParsesPlainSlockChannelUrls() {
        val permalink =
            "https://app.slock.ai/channel/channel-1?msg=msg-1"
                .toSlockThreadWebLinkTarget()

        assertEquals("channel-1", permalink?.channelId)
        assertEquals("msg-1", permalink?.messageId)
    }

    @Test
    fun webLinkTargetRejectsMarkdownAndCodeUrls() {
        assertNull(
            "[quoted](https://app.slock.ai/channel/channel-1?msg=msg-1)"
                .toSlockThreadWebLinkTarget()
        )
        assertNull(
            "`https://app.slock.ai/channel/channel-1?msg=msg-1`"
                .toSlockThreadWebLinkTarget()
        )
    }

    @Test
    fun webLinkTargetParsesChannelWithoutMessageTarget() {
        val permalink =
            "https://app.slock.ai/channel/thread-1"
                .toSlockThreadWebLinkTarget()

        assertEquals("thread-1", permalink?.channelId)
        assertNull(permalink?.messageId)
    }
}
