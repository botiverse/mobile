package build.raft.app

import build.raft.app.thread.appendThreadQuoteToComposer
import build.raft.app.thread.formatThreadMessageQuote
import build.raft.app.thread.toRaftThreadWebLinkTarget
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
    fun webLinkTargetRejectsMarkdownAndCodeUrls() {
        assertNull(
            "[quoted](https://app.slock.ai/channel/channel-1?msg=msg-1)"
                .toRaftThreadWebLinkTarget()
        )
        assertNull(
            "`https://app.slock.ai/channel/channel-1?msg=msg-1`"
                .toRaftThreadWebLinkTarget()
        )
    }
}
