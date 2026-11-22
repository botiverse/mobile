package build.raft.app.markdown

import build.raft.app.designtokens.RaftUiThemeColors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaftMarkdownCodeHighlightTest {
    @AfterTest
    fun clearCache() = RaftCodeTokenCache.clear()

    // --- language from the fence's info string ---

    @Test
    fun missingOrUnknownLanguageIsPlain() {
        assertNull(raftCodeSyntaxLanguage(null))
        assertNull(raftCodeSyntaxLanguage(""))
        assertNull(raftCodeSyntaxLanguage("   "))
        assertNull(raftCodeSyntaxLanguage("text"))
        assertNull(raftCodeSyntaxLanguage("json"))
        assertNull(raftCodeSyntaxLanguage("yaml"))
        assertNull(raftCodeSyntaxLanguage("mermaid"))
        assertNull(raftCodeSyntaxLanguage("brainfuck"))
    }

    // --- size caps ---

    @Test
    fun largeBlocksFallBackToPlain() {
        assertTrue(raftCodeHighlightEligible("val x = 1"))
        assertFalse(raftCodeHighlightEligible(""))
        assertTrue(raftCodeHighlightEligible("a".repeat(RaftCodeHighlightMaxChars)))
        assertFalse(raftCodeHighlightEligible("a".repeat(RaftCodeHighlightMaxChars + 1)))
        assertTrue(raftCodeHighlightEligible("x\n".repeat(RaftCodeHighlightMaxLines - 1) + "x"))
        assertFalse(raftCodeHighlightEligible("x\n".repeat(RaftCodeHighlightMaxLines)))
    }

    // --- token -> colour ---

    // --- tokenizing ---

    // --- annotated string ---

    @Test
    fun highlightedCodeKeepsTheTextAndPaintsEachRun() {
        val code = "val x = 1"
        val runs =
            listOf(
                RaftCodeTokenRun(RaftCodeTokenKind.Keyword, 0, 3),
                RaftCodeTokenRun(RaftCodeTokenKind.NumberLiteral, 8, 9),
                // Out of range or empty: ignored rather than crashing.
                RaftCodeTokenRun(RaftCodeTokenKind.Comment, 8, 40),
                RaftCodeTokenRun(RaftCodeTokenKind.Comment, 5, 5),
            )
        val colors = RaftUiThemeColors.RaftDark
        val text = raftHighlightedCode(code, runs, colors)
        assertEquals(code, text.text)
        assertEquals(2, text.spanStyles.size)
        assertEquals(colors.dangerStrong, text.spanStyles[0].item.color)
        assertEquals(0, text.spanStyles[0].start)
        assertEquals(3, text.spanStyles[0].end)
        assertEquals(colors.foregroundLink, text.spanStyles[1].item.color)
    }

    // --- memo ---
}
