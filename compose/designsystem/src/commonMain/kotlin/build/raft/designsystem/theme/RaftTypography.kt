package build.raft.designsystem.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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

/** Mobile content size (compose/shared `SlockTextSize`): body copy, messages, composer. */
object RaftTextSize {
    val Content = 14.sp
    val ContentLineHeight = 20.sp

    /** Single-line list rows keep the tighter line height buttons use. */
    val ListItemLineHeight = 18.sp
}

/**
 * The raft-ui type scale (styles.css `--text-*`: size / line-height / weight / tracking)
 * plus the mobile roles from compose/shared `SlockType`.
 *
 * Mobile difference: [body] is the 14/20 content size (`SlockTextSize.Content`, raft
 * `text-callout`), not raft-ui's 16/24 `text-body`, which is [bodyLarge] here.
 *
 * Every style centres the line box the way CSS half-leading does
 * ([LineHeightStyle.Alignment.Center], no trim), so a 16sp line box puts the glyphs
 * where the web puts them.
 */
@Immutable
data class RaftTypography(
    val hero: TextStyle,
    val display: TextStyle,
    val title: TextStyle,
    val headline: TextStyle,
    val bodyLarge: TextStyle,
    val subhead: TextStyle,
    val body: TextStyle,
    val footnote: TextStyle,
    val caption: TextStyle,
    val label: TextStyle,
    val monoMeta: TextStyle,
    val monoCode: TextStyle,
    // Mobile roles (SlockType).
    val pageTitle: TextStyle,
    val sectionLabel: TextStyle,
    val button: TextStyle,
    val listItem: TextStyle,
    val tabLabel: TextStyle,
) {
    companion object {
        /** CSS half-leading: extra line height split above and below, nothing trimmed. */
        val CssLineHeightStyle =
            LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.None,
            )

        fun from(fonts: RaftFonts): RaftTypography {
            fun sans(size: Int, lineHeight: Int, weight: FontWeight, tracking: TextUnit = 0.sp) =
                style(fonts.sans, size, lineHeight, weight, tracking)

            fun mono(size: Int, lineHeight: Int) = style(fonts.mono, size, lineHeight, FontWeight.Normal, 0.sp)

            return RaftTypography(
                hero = sans(40, 44, FontWeight.Bold, (-0.03).em),
                display = sans(34, 40, FontWeight.Bold, (-0.02).em),
                title = sans(22, 28, FontWeight.Bold, (-0.01).em),
                headline = sans(17, 22, FontWeight.Bold),
                bodyLarge = sans(16, 24, FontWeight.Normal),
                subhead = sans(15, 20, FontWeight.Normal),
                body = sans(14, 20, FontWeight.Normal),
                footnote = sans(13, 18, FontWeight.Normal),
                caption = sans(12, 16, FontWeight.Medium),
                label = sans(11, 13, FontWeight.SemiBold),
                monoMeta = mono(12, 16),
                monoCode = mono(13, 20),
                pageTitle = sans(20, 24, FontWeight.SemiBold),
                sectionLabel = sans(12, 16, FontWeight.SemiBold),
                button = sans(14, 18, FontWeight.Medium),
                listItem = sans(14, 18, FontWeight.Medium),
                tabLabel = sans(10, 12, FontWeight.Bold),
            )
        }

        private fun style(
            family: FontFamily,
            size: Int,
            lineHeight: Int,
            weight: FontWeight,
            tracking: TextUnit,
        ) = TextStyle(
            fontFamily = family,
            fontWeight = weight,
            fontSize = size.sp,
            lineHeight = lineHeight.sp,
            letterSpacing = tracking,
            lineHeightStyle = CssLineHeightStyle,
        )
    }
}
