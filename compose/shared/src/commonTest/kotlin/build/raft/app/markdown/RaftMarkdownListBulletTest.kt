package build.raft.app.markdown

import androidx.compose.ui.unit.dp
import com.tencent.kuiklybase.markdown.compose.elements.MarkdownBulletShape
import com.tencent.kuiklybase.markdown.compose.elements.markdownBulletMetrics
import com.tencent.kuiklybase.markdown.compose.elements.markdownBulletShape
import kotlin.test.Test
import kotlin.test.assertEquals

/** Web parity for unordered list markers: disc → circle → square, sized and placed in em. */
class RaftMarkdownListBulletTest {
    @Test
    fun markerShapeFollowsBrowserListStylePerLevel() {
        assertEquals(MarkdownBulletShape.Disc, markdownBulletShape(0))
        assertEquals(MarkdownBulletShape.Circle, markdownBulletShape(1))
        assertEquals(MarkdownBulletShape.Square, markdownBulletShape(2))
        assertEquals(MarkdownBulletShape.Square, markdownBulletShape(5))
    }

    @Test
    fun markerIsAThirdEmAndTextIndentsOneAndAHalfEm() {
        val metrics = markdownBulletMetrics(fontSize = 15.dp, lineHeight = 22.dp, listIndent = 4.dp)
        assertEquals(5f, metrics.markerSize.value, 0.01f)
        assertEquals(1f, metrics.strokeWidth.value, 0.01f)
        assertEquals(7.5f, metrics.gap.value, 0.01f)
        // listIndent + slot = 1.5em, the same at every level.
        assertEquals(22.5f, (metrics.slotWidth + 4.dp).value, 0.01f)
        assertEquals(22f, metrics.lineHeight.value, 0.01f)
    }

    @Test
    fun markerScalesWithMessageFontSize() {
        val small = markdownBulletMetrics(fontSize = 14.dp, lineHeight = 22.dp, listIndent = 4.dp)
        val large = markdownBulletMetrics(fontSize = 28.dp, lineHeight = 44.dp, listIndent = 4.dp)
        assertEquals(small.markerSize.value * 2f, large.markerSize.value, 0.01f)
        assertEquals(small.gap.value * 2f, large.gap.value, 0.01f)
        assertEquals(42f, (large.slotWidth + 4.dp).value, 0.01f)
        // circle stroke never goes below a hairline-visible 1dp, and thickens for big text.
        assertEquals(1f, small.strokeWidth.value, 0.01f)
        assertEquals(28f / 3f * 0.2f, large.strokeWidth.value, 0.01f)
    }

    @Test
    fun slotNeverClipsMarkerWhenListIndentIsLarge() {
        val metrics = markdownBulletMetrics(fontSize = 14.dp, lineHeight = 20.dp, listIndent = 40.dp)
        assertEquals((metrics.markerSize + metrics.gap).value, metrics.slotWidth.value, 0.01f)
    }
}
