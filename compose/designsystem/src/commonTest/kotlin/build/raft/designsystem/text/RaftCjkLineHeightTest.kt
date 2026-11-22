package build.raft.designsystem.text

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import build.raft.designsystem.theme.RaftTypography
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RaftCjkLineHeightTest {
    @Test
    fun cjkScriptsAreDetected() {
        listOf("排版", "かな", "カナ", "한글", "，", "Ａ", "𠀋").forEach { assertTrue(it.hasCjk(), it) }
    }

    private fun css(fontSize: Float, lineHeight: Float) =
        TextStyle(fontSize = fontSize.sp, lineHeight = lineHeight.sp, lineHeightStyle = RaftTypography.CssLineHeightStyle)

    @Test
    fun aTightCssLineWithCjkTrimsWhereFallbackFontsGrowLines() {
        // callout 14/20, footnote 13/18, a markdown heading (leading-tight 1.25).
        listOf(css(14f, 20f), css(13f, 18f), css(20f, 25f)).forEach { style ->
            assertEquals(CssCjkLineHeightStyle, style.forCjkLineBox(hasCjk = true, growsForFallbackFonts = true).lineHeightStyle)
        }
    }
}
