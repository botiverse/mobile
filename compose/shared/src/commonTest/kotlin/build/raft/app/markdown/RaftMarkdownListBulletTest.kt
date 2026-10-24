package build.raft.app.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tencent.kuiklybase.markdown.compose.elements.markdownBulletColor
import com.tencent.kuiklybase.markdown.model.RaftGFMFlavourDescriptor
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.parser.MarkdownParser
import com.tencent.kuiklybase.markdown.compose.elements.MarkdownBulletShape
import com.tencent.kuiklybase.markdown.compose.elements.markdownBulletMetrics
import com.tencent.kuiklybase.markdown.compose.elements.markdownBulletShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun markerIsAThirdEmAndTextIndentsLikeWebPl5() {
        val metrics = markdownBulletMetrics(fontSize = 15.dp, lineHeight = 22.dp, listIndent = 4.dp)
        assertEquals(5f, metrics.markerSize.value, 0.01f)
        assertEquals(1f, metrics.strokeWidth.value, 0.01f)
        assertEquals(7.5f, metrics.gap.value, 0.01f)
        // listIndent + slot = 1.25em (web `pl-5`, 20px at the 16px body), the same at every level.
        assertEquals(18.75f, (metrics.slotWidth + 4.dp).value, 0.01f)
        assertEquals(22f, metrics.lineHeight.value, 0.01f)
    }

    @Test
    fun markerScalesWithMessageFontSize() {
        val small = markdownBulletMetrics(fontSize = 14.dp, lineHeight = 22.dp, listIndent = 4.dp)
        val large = markdownBulletMetrics(fontSize = 28.dp, lineHeight = 44.dp, listIndent = 4.dp)
        assertEquals(small.markerSize.value * 2f, large.markerSize.value, 0.01f)
        assertEquals(small.gap.value * 2f, large.gap.value, 0.01f)
        assertEquals(35f, (large.slotWidth + 4.dp).value, 0.01f)
        // circle stroke never goes below a hairline-visible 1dp, and thickens for big text.
        assertEquals(1f, small.strokeWidth.value, 0.01f)
        assertEquals(28f / 3f * 0.2f, large.strokeWidth.value, 0.01f)
    }

    @Test
    fun slotNeverClipsMarkerWhenListIndentIsLarge() {
        val metrics = markdownBulletMetrics(fontSize = 14.dp, lineHeight = 20.dp, listIndent = 40.dp)
        assertEquals((metrics.markerSize + metrics.gap).value, metrics.slotWidth.value, 0.01f)
    }

    @Test
    fun markerInheritsMarkdownTextColourWhenStyleHasNone() {
        // File preview passes RaftType.Body, which carries no colour: the painted disc must
        // still get the markdown text colour, exactly like the item text beside it.
        val text = Color(0xFF111111)
        assertEquals(text, markdownBulletColor(Color.Unspecified, text))
        assertEquals(Color.Red, markdownBulletColor(Color.Red, text))
        assertEquals(Color.Black, markdownBulletColor(Color.Unspecified, Color.Unspecified))
    }

    @Test
    fun listDirectlyAfterAProseLineIsAListWithMarkers() {
        val source = "🔧 改进\n- 更流畅：滚动\n- 切换服务器\n\n🐛 修复\n1. 修复崩溃\n2. 修复显示"
        val root = MarkdownParser(RaftGFMFlavourDescriptor()).buildMarkdownTreeFromString(source)
        val bulletList = root.children.single { it.type == MarkdownElementTypes.UNORDERED_LIST }
        val orderedList = root.children.single { it.type == MarkdownElementTypes.ORDERED_LIST }
        val bulletItems = bulletList.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }
        val orderedItems = orderedList.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }
        assertEquals(2, bulletItems.size)
        assertEquals(2, orderedItems.size)
        bulletItems.forEach { assertTrue(it.children.any { c -> c.type == MarkdownTokenTypes.LIST_BULLET }) }
        orderedItems.forEach { assertTrue(it.children.any { c -> c.type == MarkdownTokenTypes.LIST_NUMBER }) }
        assertEquals(2, root.children.count { it.type == MarkdownElementTypes.PARAGRAPH })
    }

    @Test
    fun orderedListNumbersStartAtTheFirstItemsNumber() {
        val content = "3. three\n1. four\n7. five"
        val root = MarkdownParser(RaftGFMFlavourDescriptor()).buildMarkdownTreeFromString(content)
        val list = root.children.single { it.type == MarkdownElementTypes.ORDERED_LIST }
        val start = raftMarkdownOrderedListStart(list, content)
        assertEquals(3, start)
        assertEquals(listOf("3.", "4.", "5."), (0..2).map { raftMarkdownOrderedListMarker(start, it) })
        val plain = "- a"
        val bullets = MarkdownParser(RaftGFMFlavourDescriptor()).buildMarkdownTreeFromString(plain).children.first()
        assertEquals(1, raftMarkdownOrderedListStart(bullets, plain))
    }
}
