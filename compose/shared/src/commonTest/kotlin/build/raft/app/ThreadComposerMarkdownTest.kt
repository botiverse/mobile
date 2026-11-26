package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure-Kotlin mirror of web `packages/web/src/features/composer/tests/composerMarkdown.test.ts`
 * (botiverse/slock #16767 / #triage:#629): what each expanded-composer formatting tool writes.
 */
class ThreadComposerMarkdownTest {
    private val bold = ThreadComposerMarkdownTool.Wrap(before = "**", after = "**")
    private val italic = ThreadComposerMarkdownTool.Wrap(before = "_", after = "_")
    private val strikethrough = ThreadComposerMarkdownTool.Wrap(before = "~~", after = "~~")
    private val inlineCode = ThreadComposerMarkdownTool.Wrap(before = "`", after = "`")
    private val link = ThreadComposerMarkdownTool.Wrap(before = "[", after = "](https://)")
    private val numbered = ThreadComposerMarkdownTool.Line(prefix = "1. ")
    private val bullet = ThreadComposerMarkdownTool.Line(prefix = "- ")
    private val quote = ThreadComposerMarkdownTool.Line(prefix = "> ")
    private val fence = ThreadComposerMarkdownTool.Wrap(before = "```\n", after = "\n```", ownLines = true)

    @Test
    fun wrapSurroundsSelectionAndKeepsInnerSelectedOrPlacesCaretBetweenMarks() {
        val wrapped = threadComposerMarkdownEdit("a word", 2, 6, bold)
        assertEquals("a **word**", wrapped.nextValue)
        assertEquals(4, wrapped.selectionStart)
        assertEquals(8, wrapped.selectionEnd)

        val empty = threadComposerMarkdownEdit("ab", 1, 1, bold)
        assertEquals("a****b", empty.nextValue)
        assertEquals(3, empty.selectionStart)
        assertEquals(3, empty.selectionEnd)

        val reversed = threadComposerMarkdownEdit("a word", 6, 2, italic)
        assertEquals("a _word_", reversed.nextValue)
        assertEquals(3, reversed.selectionStart)
        assertEquals(7, reversed.selectionEnd)

        val struck = threadComposerMarkdownEdit("old", 0, 3, strikethrough)
        assertEquals("~~old~~", struck.nextValue)
        assertEquals(2, struck.selectionStart)
        assertEquals(5, struck.selectionEnd)

        val code = threadComposerMarkdownEdit("x", 0, 0, inlineCode)
        assertEquals("``x", code.nextValue)
        assertEquals(1, code.selectionStart)
        assertEquals(1, code.selectionEnd)

        val linked = threadComposerMarkdownEdit("docs", 0, 4, link)
        assertEquals("[docs](https://)", linked.nextValue)
        assertEquals(1, linked.selectionStart)
        assertEquals(5, linked.selectionEnd)
    }

    @Test
    fun lineToolPrefixesEveryTouchedLineAndIgnoresLineAfterTrailingNewline() {
        val multi = threadComposerMarkdownEdit("one\ntwo\nthree", 1, 5, bullet)
        assertEquals("- one\n- two\nthree", multi.nextValue)
        assertEquals(0, multi.selectionStart)
        assertEquals(9, multi.selectionEnd)

        val trailingNewline = threadComposerMarkdownEdit("a\nb", 0, 2, bullet)
        assertEquals("- a\nb", trailingNewline.nextValue)

        val caretOnSecondLine = threadComposerMarkdownEdit("a\nb", 3, 3, bullet)
        assertEquals("a\n- b", caretOnSecondLine.nextValue)
        assertEquals(5, caretOnSecondLine.selectionStart)
        assertEquals(5, caretOnSecondLine.selectionEnd)

        val emptyDoc = threadComposerMarkdownEdit("", 0, 0, quote)
        assertEquals("> ", emptyDoc.nextValue)
        assertEquals(2, emptyDoc.selectionStart)
        assertEquals(2, emptyDoc.selectionEnd)

        val numberedTwoLines = threadComposerMarkdownEdit("first\nsecond", 0, 12, numbered)
        assertEquals("1. first\n1. second", numberedTwoLines.nextValue)
    }

    @Test
    fun codeFenceSitsOnLinesOfItsOwnIncludingMidLine() {
        val midLine = threadComposerMarkdownEdit("hello world", 6, 6, fence)
        assertEquals("hello \n```\n\n```\nworld", midLine.nextValue)
        assertEquals(11, midLine.selectionStart)
        assertEquals(11, midLine.selectionEnd)

        val wholeDoc = threadComposerMarkdownEdit("x", 0, 1, fence)
        assertEquals("```\nx\n```", wholeDoc.nextValue)
        assertEquals(4, wholeDoc.selectionStart)
        assertEquals(5, wholeDoc.selectionEnd)

        val betweenNewlines = threadComposerMarkdownEdit("a\nb\n", 2, 3, fence)
        assertEquals("a\n```\nb\n```\n", betweenNewlines.nextValue)
        assertEquals(6, betweenNewlines.selectionStart)
        assertEquals(7, betweenNewlines.selectionEnd)
    }

    @Test
    fun formatToolGroupsMatchWebOrder() {
        assertEquals(
            listOf(
                listOf("bold", "italic", "strikethrough"),
                listOf("code", "link"),
            ),
            ThreadComposerInlineFormatGroups.map { group -> group.map { it.id } },
        )
        assertEquals(
            listOf("numbered-list", "bulleted-list", "quote", "code-block"),
            ThreadComposerBlockFormatActions.map { it.id },
        )
    }
}
