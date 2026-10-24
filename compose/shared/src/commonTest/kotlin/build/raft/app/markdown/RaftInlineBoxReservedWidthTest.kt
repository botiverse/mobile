package build.raft.app.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaftInlineBoxReservedWidthTest {
    private val chip =
        RaftInlineBoxStyle(
            backgroundColor = Color.Yellow,
            paddingStart = 4.dp,
            paddingEnd = 4.dp,
            marginStart = 2.dp,
            marginEnd = 2.dp,
        )

    @Test
    fun boxReservesPaddingPlusMarginOutsideTheBoxedRange() {
        val text =
            buildAnnotatedString {
                append("a")
                withRaftInlineChipGaps(chip) { withRaftInlineBox(chip) { append("@x") } }
                withRaftInlineChipGaps(chip) { withRaftInlineBox(chip) { append("@y") } }
                append("b")
            }
        // gap + word joiner glue each side; two adjacent chips are separated by both gaps.
        assertEquals("a￼⁠@x⁠￼￼⁠@y⁠￼b", text.text)
        assertEquals(setOf(raftInlineGapInlineId(6.dp)), text.raftMarkdownInlineContentIds())
        assertEquals("a@x@yb", text.raftTextWithoutInlineGaps())
        val boxes = text.raftInlineBoxRanges()
        assertEquals(listOf("@x", "@y"), boxes.map { text.text.substring(it.start, it.end) })
        assertEquals(6.dp, chip.raftInlineLeadingReservedWidth())
        assertEquals(6.dp, chip.raftInlineTrailingReservedWidth())
    }

    @Test
    fun boxWithoutHorizontalInsetReservesNothing() {
        val text = buildAnnotatedString { withRaftInlineChipGaps(RaftInlineBoxStyle(paddingTop = 2.dp)) { append("x") } }
        assertEquals("x", text.text)
        assertTrue(text.raftMarkdownInlineContentIds().isEmpty())
    }

    @Test
    fun gapPlaceholdersResolveToTheirReservedWidth() {
        val content = raftInlineGapInlineContent(listOf(raftInlineGapInlineId(6.dp), raftInlineGapInlineId(2.dp), "other"))
        assertEquals(setOf(raftInlineGapInlineId(6.dp), raftInlineGapInlineId(2.dp)), content.keys)
        assertEquals(6.sp, content.getValue(raftInlineGapInlineId(6.dp)).placeholder.width)
        assertEquals(2.sp, content.getValue(raftInlineGapInlineId(2.dp)).placeholder.width)
        assertNull(raftInlineGapWidth("other"))
    }

    @Test
    fun referenceAndPermalinkChipsReserveTheirInsets() {
        val reference = raftMarkdownRichTextTagInlineBoxStyle(RaftRichTextChromeKind.SelfMention)!!
        // raft-ui inline chip `px-1.5` plus mobile's 2dp outer gap.
        assertEquals(8.dp, reference.raftInlineLeadingReservedWidth())
        assertEquals(8.dp, reference.raftInlineTrailingReservedWidth())
        // A chip with a leading icon takes 4.5px at each end.
        assertEquals(6.5.dp, raftMessagePermalinkChipBoxStyle().raftInlineLeadingReservedWidth())
    }
}
