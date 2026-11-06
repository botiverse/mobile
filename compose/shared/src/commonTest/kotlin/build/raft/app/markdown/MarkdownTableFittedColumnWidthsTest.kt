package build.raft.app.markdown

import com.tencent.kuiklybase.markdown.compose.elements.markdownTableColumnWidths
import com.tencent.kuiklybase.markdown.compose.elements.markdownTableFittedColumnWidths
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * A table in the share image fits the image (web share capture: `table-layout: fixed; width:
 * 100%`, cells wrap): the conversation's widths while its words fit, equal columns past that.
 */
class MarkdownTableFittedColumnWidthsTest {

    @Test
    fun aTableWhoseWordsFitKeepsTheConversationWidths() {
        val min = intArrayOf(60, 110)
        val max = intArrayOf(60, 640)
        assertContentEquals(markdownTableColumnWidths(min, max, 360), markdownTableFittedColumnWidths(min, max, 360))
        assertContentEquals(intArrayOf(60, 640), markdownTableFittedColumnWidths(min, max, 1400))
    }

    @Test
    fun aTableTooWideForItsWordsSharesTheWidthEquallyAndNeverOverflows() {
        val widths = markdownTableFittedColumnWidths(intArrayOf(120, 200, 90), intArrayOf(300, 900, 90), availableWidth = 250)
        assertEquals(250, widths.sum())
        assertContentEquals(intArrayOf(84, 83, 83), widths)
    }

    @Test
    fun noColumnsNoWidths() {
        assertEquals(0, markdownTableFittedColumnWidths(IntArray(0), IntArray(0), 300).size)
    }
}
