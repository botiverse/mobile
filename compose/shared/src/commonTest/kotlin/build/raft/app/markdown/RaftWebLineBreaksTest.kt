package build.raft.app.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** [withRaftWebTextBreaks] and [withRaftInlineChipGlue]: where the marks go, and that copy drops them. */
class RaftWebLineBreaksTest {
    private val zwsp = "​"
    private val wj = "⁠"

    @Test
    fun aHyphenInsideAWordGetsABreakMark() {
        assertEquals("read-${zwsp}state", "read-state".withRaftWebTextBreaks())
        assertEquals("rolling-${zwsp}share 设计", "rolling-share 设计".withRaftWebTextBreaks())
        // Not before a digit (UAX #14 LB25), not at a word's edge.
        assertEquals("slice-1", "slice-1".withRaftWebTextBreaks())
        assertEquals("-flag and x- y", "-flag and x- y".withRaftWebTextBreaks())
    }

    @Test
    fun aSlashBeforeALetterOrDigitIsGlued() {
        assertEquals("a/${wj}b", "a/b".withRaftWebTextBreaks())
        assertEquals("traversal/${wj}layout/${wj}draw", "traversal/layout/draw".withRaftWebTextBreaks())
        assertEquals("A/${wj}B 设计", "A/B 设计".withRaftWebTextBreaks())
        // Before CJK, a space or the end the native breaker already agrees with the web.
        assertEquals("导航/滚动 a/ b/", "导航/滚动 a/ b/".withRaftWebTextBreaks())
    }

    @Test
    fun textWithoutHyphenOrSlashIsReturnedAsIs() {
        val text = "Captured the Android visual artifact"
        assertSame(text, text.withRaftWebTextBreaks())
    }

    @Test
    fun emojiAndSurrogatePairsAreNeverSplitInText() {
        val text = "👩‍💻-dev ❤️/ok 𠀀-a"
        val marked = text.withRaftWebTextBreaks()
        assertEquals(text, marked.raftCopyText())
        assertTrue(marked.contains("❤️/${wj}ok"))
        assertTrue(marked.contains("👩‍💻-dev"), "no letter before the hyphen: no mark")
    }

    @Test
    fun copyDropsEveryMark() {
        val text = "read-state a/b traversal/layout"
        assertEquals(text, text.withRaftWebTextBreaks().raftCopyText())
    }

    @Test
    fun aPlainLatinChipLabelStaysAsItIs() {
        val label = "#design"
        assertSame(label, label.withRaftInlineChipGlue())
        assertSame("@artin", "@artin".withRaftInlineChipGlue())
    }

    @Test
    fun aChipLabelIsGluedOnlyAtBreakOpportunities() {
        assertEquals("#visual-${wj}testing", "#visual-testing".withRaftInlineChipGlue())
        assertEquals("@Android-${wj}Developer-${wj}4", "@Android-Developer-4".withRaftInlineChipGlue())
        assertEquals("#proj/${wj}x", "#proj/x".withRaftInlineChipGlue())
        // A name with a space: joiners round the space only, never between its letters.
        assertEquals("@Display$wj $wj" + "Name", "@Display Name".withRaftInlineChipGlue())
    }

    @Test
    fun aCjkChipLabelIsGluedBetweenItsCharacters() {
        assertEquals(
            "#${wj}对${wj}话${wj}流${wj}专${wj}修${wj}:661a12bf",
            "#对话流专修:661a12bf".withRaftInlineChipGlue()
        )
        // A CJK character outside the BMP is one code point: no joiner inside its surrogate pair.
        val glued = "#𠀀𠀁".withRaftInlineChipGlue()
        assertEquals("#$wj𠀀$wj𠀁", glued)
    }

    @Test
    fun emojiInAChipLabelStayWhole() {
        // ZWJ sequence, VS16 and a skin tone: joiners only round the space, never inside a cluster.
        assertEquals("@Dev$wj $wj👩‍💻", "@Dev 👩‍💻".withRaftInlineChipGlue())
        assertEquals("@Lily$wj $wj❤️", "@Lily ❤️".withRaftInlineChipGlue())
        assertEquals("@Sam$wj $wj👍🏽", "@Sam 👍🏽".withRaftInlineChipGlue())
        assertEquals("@🦊", "@🦊".withRaftInlineChipGlue())
        listOf("@Dev 👩‍💻", "@Lily ❤️", "#对话流 ❤️ ok-go").forEach { label ->
            val glued = label.withRaftInlineChipGlue()
            assertEquals(label, glued.raftCopyText())
            assertFalse(glued.contains("$wj‍") || glued.contains("‍$wj"), "no joiner beside a ZWJ in $label")
            assertFalse(glued.contains("$wj️"), "no joiner before a variation selector in $label")
        }
    }
}
