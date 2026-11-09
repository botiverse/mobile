package build.raft.designsystem.text

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import build.raft.designsystem.theme.RaftTypography
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RaftCjkLineHeightTest {
    @Test
    fun cjkScriptsAreDetected() {
        listOf("排版", "かな", "カナ", "한글", "，", "Ａ", "𠀋").forEach { assertTrue(it.hasCjk(), it) }
    }

    @Test
    fun latinAndEmojiAreNot() {
        listOf("", "Aa 0123", "café — “quotes”", "😀").forEach { assertFalse(it.hasCjk(), it) }
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

    @Test
    fun everythingElseKeepsItsStyle() {
        val callout = css(14f, 20f)
        // No CJK; a platform that keeps the line height (skia).
        assertSame(callout, callout.forCjkLineBox(hasCjk = false, growsForFallbackFonts = true))
        assertSame(callout, callout.forCjkLineBox(hasCjk = true, growsForFallbackFonts = false))
        // A line height that already fits Noto Sans CJK (body 16/24, a 14/22 markdown floor).
        listOf(css(16f, 24f), css(14f, 22f)).forEach { style ->
            assertSame(style, style.forCjkLineBox(hasCjk = true, growsForFallbackFonts = true))
        }
        // Another line box, or a line height not in sp.
        val proportional = callout.copy(lineHeightStyle = LineHeightStyle.Default)
        assertSame(proportional, proportional.forCjkLineBox(hasCjk = true, growsForFallbackFonts = true))
        val em = callout.copy(lineHeight = 1.2.em)
        assertSame(em, em.forCjkLineBox(hasCjk = true, growsForFallbackFonts = true))
    }
}
