package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

/** Select Text sheet (web #11467): what Copy and Quote take, the quote's shape and its composer. */
class ThreadMessageSelectTextSheetTest {
    private val markdown = "Ship the **sheet** today.\n\n- Copy\n- Quote"

    @Test
    fun copyTakesTheSelectionElseTheWholeMarkdown() {
        assertEquals("**sheet**", threadSelectTextSheetSource(selectedText = "**sheet**", markdown = markdown))
        // Nothing selected, or only whitespace (a caret, a stray space): the whole message.
        listOf("", " ", "\n\t").forEach { selected ->
            assertEquals(markdown, threadSelectTextSheetSource(selectedText = selected, markdown = markdown))
        }
    }

    @Test
    fun quotePrefixesEveryLineOfTheSelectionOrTheWholeMessage() {
        assertEquals("> **sheet** today.", threadSelectTextSheetQuote("**sheet** today.", markdown))
        // A blank line stays a bare `>` so the blockquote is one block (web formatSelectedTextQuote).
        assertEquals(
            "> Ship the **sheet** today.\n>\n> - Copy\n> - Quote",
            threadSelectTextSheetQuote(selectedText = "", markdown = markdown)
        )
        assertEquals("> a\n> b", threadSelectTextSheetQuote("a\r\nb\n\n", markdown))
    }

    @Test
    fun aQuoteReplyIsShownAndCopiedWithoutItsRefLine() {
        // web MessageSelectTextDrawer.tsx:124,141,219 (botiverse/slock #16158): the sheet shows and copies
        // the message through `stripQuoteReplyRefsForPreview`, its quote kept.
        val reply = "> Status page banner goes live at 9:00 PT\n> msg=c54e1234\n\nCan we move it to 8:30?"
        val shown = "> Status page banner goes live at 9:00 PT\n\nCan we move it to 8:30?"
        assertEquals(shown, threadSelectTextSheetText(reply))
        assertEquals(shown, threadSelectTextSheetSource(selectedText = "", markdown = reply))
        // Quote with nothing selected still reads the Markdown as written: the reply's own words
        // (:151, `buildWholeMessageQuoteExcerpt(markdown)`).
        assertEquals(
            "> Can we move it to 8:30?\n> msg=91c2b8aa",
            threadSelectTextSheetQuote(selectedText = "", markdown = reply, quoteMessageId = "91c2b8aa-2222-4222-8222-222222222222"),
        )
    }

    @Test
    fun quoteLandsAfterTheDraftWithTheCursorOnTheLineBelow() {
        val quote = threadSelectTextSheetQuote("picked", markdown)
        assertEquals("> picked\n\n", appendThreadQuoteToComposer("", quote))
        assertEquals("draft\n\n> picked\n\n", appendThreadQuoteToComposer("draft  \n", quote))
    }

    @Test
    fun quoteGoesToTheComposerOfTheSurfaceTheMessageIsIn() {
        assertEquals(
            ThreadSelectTextQuoteTarget.ChannelComposer,
            threadSelectTextQuoteTarget(RaftThreadSurfaceKind.Channel, composerAvailable = true)
        )
        assertEquals(
            ThreadSelectTextQuoteTarget.ChannelComposer,
            threadSelectTextQuoteTarget(RaftThreadSurfaceKind.DirectMessage, composerAvailable = true)
        )
        assertEquals(
            ThreadSelectTextQuoteTarget.ThreadComposer,
            threadSelectTextQuoteTarget(RaftThreadSurfaceKind.ReplyThread, composerAvailable = true)
        )
        // No composer on the page (not joined, read-only): no Quote at all.
        listOf(RaftThreadSurfaceKind.Channel, RaftThreadSurfaceKind.ReplyThread).forEach { kind ->
            assertEquals(ThreadSelectTextQuoteTarget.None, threadSelectTextQuoteTarget(kind, composerAvailable = false))
        }
    }

    @Test
    fun aButtonUsesTheSelectionSeenWhenItWasPressed() {
        val press = ThreadSelectTextPressSelection()
        var live = "picked"
        press.onPress(live)
        // The tap collapsed the selection before the click arrived.
        live = ""
        assertEquals("picked", press.take { live })
        // Taken once: a later click with no press reads the live selection.
        assertEquals("", press.take { live })
    }

    @Test
    fun nativeSelectionSliceIsOrderedAndClamped() {
        val text = "hello world"
        assertEquals("world", raftNativeSelectedSlice(text, 6, 11))
        assertEquals("world", raftNativeSelectedSlice(text, 11, 6))
        assertEquals("", raftNativeSelectedSlice(text, 3, 3))
        assertEquals("", raftNativeSelectedSlice(text, -1, -1))
        assertEquals("world", raftNativeSelectedSlice(text, 6, 99))
    }
}
