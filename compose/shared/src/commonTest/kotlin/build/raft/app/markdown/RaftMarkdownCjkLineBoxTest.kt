package build.raft.app.markdown

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import build.raft.app.RaftType
import build.raft.app.withResolvedRaftFontFamily
import build.raft.designsystem.theme.RaftTypography
import com.tencent.kuiklybase.markdown.compose.elements.material.merge as vendorMerge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class RaftMarkdownCjkLineBoxTest {
    @Test
    fun unspecifiedProviderMetricsKeepTheStyle() {
        val style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
        assertSame(style, style.withRaftMarkdownLineMetrics(TextUnit.Unspecified, TextUnit.Unspecified))
    }

    @Test
    fun specifiedProviderMetricsAreTheOnesLaidOut() {
        val style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
        val sized = style.withRaftMarkdownLineMetrics(18.sp, TextUnit.Unspecified)
        assertEquals(18.sp, sized.fontSize)
        assertEquals(20.sp, sized.lineHeight)
        val leaded = style.withRaftMarkdownLineMetrics(TextUnit.Unspecified, 18.sp)
        assertEquals(14.sp, leaded.fontSize)
        assertEquals(18.sp, leaded.lineHeight)
    }

    /**
     * The CJK rule keys on the CSS line box. MarkdownBasicText's merge rebuilds the style without
     * its lineHeightStyle; the text provider's withResolvedRaftFontFamily puts the CSS one back, so
     * every markdown style reaches the rule with it.
     */
    @Test
    fun everyMarkdownStyleReachesTheProviderWithTheCssLineBox() {
        val sm = RaftType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp)
        val typography = raftMarkdownTypography(sm)
        listOf(
            typography.h1, typography.h2, typography.h3, typography.h4, typography.h5, typography.h6,
            typography.paragraph, typography.code, typography.quote, typography.ordered, typography.bullet,
            typography.table,
        ).forEach { style ->
            val provided = style.vendorMerge(fontSize = null, lineHeight = null).withResolvedRaftFontFamily()
            assertEquals(RaftTypography.CssLineHeightStyle, provided.lineHeightStyle)
        }
    }
}
