package build.raft.designsystem.text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import build.raft.designsystem.theme.RaftTypography

/**
 * Whether the platform's text layout grows a line to fit a taller fallback font. Android's
 * does (Compose always lays out with `fallbackLineSpacing`, no switch), so a line holding Han,
 * kana or Hangul (Noto Sans CJK, ~1.45em) stands taller than a CSS line box of the same line
 * height; skia's (desktop, iOS) keeps the line height, as CSS does.
 */
internal expect val raftTextGrowsForFallbackFonts: Boolean

/**
 * [RaftTypography.CssLineHeightStyle] for a text whose fallback font would grow its lines:
 * trimming the first and last lines is what lets [LineHeightStyle.Mode.Tight] cut them back to
 * the line height. Its one cost: a paragraph whose first line has no CJK at all loses that
 * line's half-leading (the trim applies when the line height is taller than the font, too).
 */
internal val CssCjkLineHeightStyle =
    LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.Both,
        mode = LineHeightStyle.Mode.Tight,
    )

/**
 * Noto Sans CJK's ascent plus descent, in em: a line height at or over this already fits the
 * fallback font, and trimming would cut the half-leading CSS keeps (body 16/24).
 */
private const val CjkFallbackLineHeightEm = 1.45f

/**
 * [style] for [text], with [CssCjkLineHeightStyle] where the platform needs it. The text is scanned
 * once per value ([remember]), not on every recomposition: a long body goes through RaftText too.
 */
@Composable
internal fun TextStyle.forCjkLineBox(text: CharSequence): TextStyle {
    val cjk = remember(text) { raftTextGrowsForFallbackFonts && text.hasCjk() }
    return forCjkLineBox(cjk)
}

/** [forCjkLineBox] once it is known whether the text has CJK. */
internal fun TextStyle.forCjkLineBox(hasCjk: Boolean): TextStyle =
    if (
        raftTextGrowsForFallbackFonts &&
        lineHeightStyle == RaftTypography.CssLineHeightStyle &&
        fontSize.isSp && lineHeight.isSp && lineHeight.value < fontSize.value * CjkFallbackLineHeightEm &&
        hasCjk
    ) {
        copy(lineHeightStyle = CssCjkLineHeightStyle)
    } else {
        this
    }

/** Han, kana, Hangul, CJK punctuation or fullwidth forms: the scripts that fall back to Noto Sans CJK. */
internal fun CharSequence.hasCjk(): Boolean =
    any { c ->
        c in 'ᄀ'..'ᇿ' || c in '⺀'..'鿿' || c in 'ꥠ'..'꥿' ||
            c in '가'..'퟿' || c in '豈'..'﫿' || c in '︰'..'﹏' ||
            c in '＀'..'￯' || c in '\uD840'..'\uD87F' // high surrogates of the supplementary ideographs
    }
