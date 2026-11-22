package build.raft.app.markdown

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
    fun aTableTooWideForItsWordsSharesTheWidthEquallyAndNeverOverflows() {
        val widths = markdownTableFittedColumnWidths(intArrayOf(120, 200, 90), intArrayOf(300, 900, 90), availableWidth = 250)
        assertEquals(250, widths.sum())
        assertContentEquals(intArrayOf(84, 83, 83), widths)
    }
}
