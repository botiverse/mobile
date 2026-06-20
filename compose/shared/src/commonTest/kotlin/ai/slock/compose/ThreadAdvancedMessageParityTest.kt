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
    fun permalinkExtractorLoadsOnlyCurrentServerPlainUrls() {
        val permalink =
            extractFirstQuotedMessagePermalink(
                "see https://app.slock.ai/s/botiverse/channel/channel-1?msg=msg-1",
                currentServerSlug = "botiverse"
            )

        assertEquals("channel-1", permalink?.channelId)
        assertEquals("msg-1", permalink?.messageId)
    }

    @Test
    fun permalinkExtractorRejectsMarkdownAndCodeUrls() {
        assertNull(
            extractFirstQuotedMessagePermalink(
                "[quoted](https://app.slock.ai/s/botiverse/channel/channel-1?msg=msg-1)",
                currentServerSlug = "botiverse"
            )
        )
        assertNull(
            extractFirstQuotedMessagePermalink(
                "`https://app.slock.ai/s/botiverse/channel/channel-1?msg=msg-1`",
                currentServerSlug = "botiverse"
            )
        )
    }

    @Test
    fun permalinkExtractorParsesThreadParentAsOpenTargetWhenMsgMissing() {
        val permalink =
            extractFirstQuotedMessagePermalink(
                "https://app.slock.ai/s/botiverse/channel/thread-1?thread=thread-1:parent-1",
                currentServerSlug = "botiverse"
            )

        assertEquals("parent-1", permalink?.messageId)
        assertEquals("parent-1", permalink?.threadParentMessageId)
    }
}
