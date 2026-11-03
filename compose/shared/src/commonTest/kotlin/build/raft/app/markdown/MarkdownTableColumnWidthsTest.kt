package build.raft.app.markdown

import com.tencent.kuiklybase.markdown.compose.elements.markdownTableColumnWidths
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * HTML automatic table layout, as the web renders a chat table (`<table>` in `overflow-x-auto`):
 * content width up to the available width, wrapping only beyond it, scrolling only when even the
 * narrowest columns do not fit.
 */
class MarkdownTableColumnWidthsTest {

    @Test
    fun contentThatFitsKeepsEveryColumnOnOneLine() {
        // artin 11-03: short first column, long second one, wide Compose 版 window.
        val widths = markdownTableColumnWidths(
            minWidths = intArrayOf(60, 110),
            maxWidths = intArrayOf(60, 640),
            availableWidth = 1400,
        )
        assertContentEquals(intArrayOf(60, 640), widths, "the table hugs its content and does not stretch")
    }

    @Test
    fun tooWideContentGivesTheSpareWidthByHowMuchEachColumnWantsToGrow() {
        // 360 available, 170 of minimums: 190 spare, split 0 : 530 → all of it to column 2.
        assertContentEquals(
            intArrayOf(60, 300),
            markdownTableColumnWidths(intArrayOf(60, 110), intArrayOf(60, 640), availableWidth = 360),
        )
        // Two growing columns: 100 spare, wanted growth 100 : 300 → 25 : 75.
        assertContentEquals(
            intArrayOf(75, 175),
            markdownTableColumnWidths(intArrayOf(50, 100), intArrayOf(150, 400), availableWidth = 250),
        )
    }

    @Test
    fun roundingStillFillsTheAvailableWidthExactly() {
        val widths = markdownTableColumnWidths(intArrayOf(10, 10, 10), intArrayOf(40, 40, 40), availableWidth = 100)
        assertEquals(100, widths.sum())
        widths.forEach { assertTrue(it in 10..40, "every column stays between its min and max: $it") }
    }

    @Test
    fun columnsThatCannotShrinkEnoughKeepTheirMinimumAndOverflow() {
        assertContentEquals(
            intArrayOf(120, 200),
            markdownTableColumnWidths(intArrayOf(120, 200), intArrayOf(300, 900), availableWidth = 250),
        )
    }

    @Test
    fun maxBelowMinIsTreatedAsMin() {
        assertContentEquals(
            intArrayOf(80, 40),
            markdownTableColumnWidths(intArrayOf(80, 40), intArrayOf(20, 40), availableWidth = 1000),
        )
    }

    @Test
    fun unboundedWidthUsesMaxContent() {
        assertContentEquals(
            intArrayOf(60, 5000),
            markdownTableColumnWidths(intArrayOf(60, 110), intArrayOf(60, 5000), availableWidth = Int.MAX_VALUE),
        )
    }
}
