package build.raft.designsystem.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import build.raft.designsystem.resources.Res
import build.raft.designsystem.resources.space_grotesk_bold
import build.raft.designsystem.resources.space_grotesk_light
import build.raft.designsystem.resources.space_grotesk_medium
import build.raft.designsystem.resources.space_grotesk_regular
import build.raft.designsystem.resources.space_grotesk_semibold
import build.raft.designsystem.resources.space_mono_bold
import build.raft.designsystem.resources.space_mono_regular
import org.jetbrains.compose.resources.Font

/**
 * raft-ui fonts.css: Space Grotesk is what people say (`--sans-font` / `--heading-font`),
 * Space Mono is what machines write (`--mono-font`). Bundled as compose-resources, so
 * Android, iOS and desktop render from the same TTFs.
 */
@Immutable
data class RaftFonts(
    val sans: FontFamily,
    val mono: FontFamily,
)

/** Loads the bundled faces. Space Mono ships only 400 and 700. */
@Composable
fun rememberRaftFonts(): RaftFonts {
    val light = Font(Res.font.space_grotesk_light, FontWeight.Light)
    val regular = Font(Res.font.space_grotesk_regular, FontWeight.Normal)
    val medium = Font(Res.font.space_grotesk_medium, FontWeight.Medium)
    val semibold = Font(Res.font.space_grotesk_semibold, FontWeight.SemiBold)
    val bold = Font(Res.font.space_grotesk_bold, FontWeight.Bold)
    val monoRegular = Font(Res.font.space_mono_regular, FontWeight.Normal)
    val monoBold = Font(Res.font.space_mono_bold, FontWeight.Bold)
    return remember(light, regular, medium, semibold, bold, monoRegular, monoBold) {
        RaftFonts(
            sans = FontFamily(light, regular, medium, semibold, bold),
            mono = FontFamily(monoRegular, monoBold),
        )
    }
}

/**
 * The raft-ui type scale, 1:1 with styles.css `--text-*` (size / line-height / weight /
 * tracking) and the Typography page of raft-design 0.8.0. Each style carries the token's
 * default weight (the bold styles set 700, `text-caption` 500, `text-label` 600, the rest
 * 400); pick another by purpose with `.copy(fontWeight = …)` per the page's "Weights by
 * use": 500 menu items and description-list terms, 600 names in list rows, card titles,
 * field labels, tabs and badges, 700 titles, senders, buttons, group headings and the
 * selected tab, row or menu item.
 *
 * Every style centres the line box the way CSS half-leading does
 * ([LineHeightStyle.Alignment.Center], no trim), so a 24sp line box puts the glyphs
 * where the web puts them.
 */
@Immutable
data class RaftTypography(
    /** `text-hero` 40/44 700, -0.03em: the service name on Login with Raft. */
    val hero: TextStyle,
    /** `text-display` 34/40 700, -0.02em: the create-server title, large page titles. */
    val display: TextStyle,
    /** `text-title` 22/28 700, -0.01em: sign-in and setup-step titles. */
    val title: TextStyle,
    /** `text-headline` 17/22 700: top bar, panel, dialog and sheet titles. */
    val headline: TextStyle,
    /** `text-body` 16/24: message text; list and task titles at 600. */
    val body: TextStyle,
    /** `text-subhead` 15/20: buttons (700), names, radio labels, dialog body text. */
    val subhead: TextStyle,
    /** `text-callout` 14/20: previews, menu items (500), banners, excerpts, list values. */
    val callout: TextStyle,
    /** `text-footnote` 13/18: group headings (700), field labels (600), 32dp buttons. */
    val footnote: TextStyle,
    /** `text-caption` 12/16 500: subtitles, status chips, help text, badges. */
    val caption: TextStyle,
    /** `text-label` 11/13 600: tab bar labels, labeled rail. */
    val label: TextStyle,
    /** `text-mono-meta` 12/16 Space Mono: IDs, times, counts, shortcuts. */
    val monoMeta: TextStyle,
    /** `text-mono-code` 13/20 Space Mono: code and commands. */
    val monoCode: TextStyle,
    /** `--field-*` 16/24 400: Input, Textarea, a field Select trigger and its options. */
    val field: TextStyle,
    /** `--card-title-*` 16/24 600: CardTitle. */
    val cardTitle: TextStyle,
) {
    companion object {
        /** CSS half-leading: extra line height split above and below, nothing trimmed. */
        val CssLineHeightStyle =
            LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.None,
            )

        /**
         * The scale in [fonts]. [localeList] goes on every style (null: none, the platform
         * uses the device locale): it picks the CJK fallback glyphs, so a Japanese,
         * Korean or Traditional Chinese UI passes its language and Han characters take
         * that language's forms.
         */
        fun from(fonts: RaftFonts, localeList: LocaleList? = null): RaftTypography {
            fun sans(size: Int, lineHeight: Int, weight: FontWeight, tracking: TextUnit = 0.sp) =
                style(fonts.sans, size, lineHeight, weight, tracking, localeList)

            fun mono(size: Int, lineHeight: Int) = style(fonts.mono, size, lineHeight, FontWeight.Normal, 0.sp, localeList)

            return RaftTypography(
                hero = sans(40, 44, FontWeight.Bold, (-0.03).em),
                display = sans(34, 40, FontWeight.Bold, (-0.02).em),
                title = sans(22, 28, FontWeight.Bold, (-0.01).em),
                headline = sans(17, 22, FontWeight.Bold),
                body = sans(16, 24, FontWeight.Normal),
                subhead = sans(15, 20, FontWeight.Normal),
                callout = sans(14, 20, FontWeight.Normal),
                footnote = sans(13, 18, FontWeight.Normal),
                caption = sans(12, 16, FontWeight.Medium),
                label = sans(11, 13, FontWeight.SemiBold),
                monoMeta = mono(12, 16),
                monoCode = mono(13, 20),
                field = sans(16, 24, FontWeight.Normal),
                cardTitle = sans(16, 24, FontWeight.SemiBold),
            )
        }

        private fun style(
            family: FontFamily,
            size: Int,
            lineHeight: Int,
            weight: FontWeight,
            tracking: TextUnit,
            localeList: LocaleList?,
        ) = TextStyle(
            fontFamily = family,
            fontWeight = weight,
            fontSize = size.sp,
            lineHeight = lineHeight.sp,
            letterSpacing = tracking,
            localeList = localeList,
            lineHeightStyle = CssLineHeightStyle,
        )
    }
}
