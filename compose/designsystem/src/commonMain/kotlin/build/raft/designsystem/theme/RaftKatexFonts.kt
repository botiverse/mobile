package build.raft.designsystem.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import build.raft.designsystem.resources.Res
import build.raft.designsystem.resources.katex_ams_regular
import build.raft.designsystem.resources.katex_caligraphic_bold
import build.raft.designsystem.resources.katex_caligraphic_regular
import build.raft.designsystem.resources.katex_fraktur_bold
import build.raft.designsystem.resources.katex_fraktur_regular
import build.raft.designsystem.resources.katex_main_bold
import build.raft.designsystem.resources.katex_main_bolditalic
import build.raft.designsystem.resources.katex_main_italic
import build.raft.designsystem.resources.katex_main_regular
import build.raft.designsystem.resources.katex_math_bolditalic
import build.raft.designsystem.resources.katex_math_italic
import build.raft.designsystem.resources.katex_sansserif_bold
import build.raft.designsystem.resources.katex_sansserif_italic
import build.raft.designsystem.resources.katex_sansserif_regular
import build.raft.designsystem.resources.katex_script_regular
import build.raft.designsystem.resources.katex_size1_regular
import build.raft.designsystem.resources.katex_size2_regular
import build.raft.designsystem.resources.katex_size3_regular
import build.raft.designsystem.resources.katex_size4_regular
import build.raft.designsystem.resources.katex_typewriter_regular
import org.jetbrains.compose.resources.Font

/**
 * KaTeX's math faces, the TTFs of the `katex` 0.19.0 package the web renders formulas with
 * (MIT). Message math (task #775) lays out with these, so a formula has the web's glyphs.
 */
@Immutable
data class RaftKatexFonts(
    val main: FontFamily,
    val math: FontFamily,
    val ams: FontFamily,
    val sansSerif: FontFamily,
    val typewriter: FontFamily,
    val caligraphic: FontFamily,
    val fraktur: FontFamily,
    val script: FontFamily,
    val size1: FontFamily,
    val size2: FontFamily,
    val size3: FontFamily,
    val size4: FontFamily,
)

/** Loads the KaTeX faces; only composed where a formula is shown. */
@Composable
fun rememberRaftKatexFonts(): RaftKatexFonts {
    val mainRegular = Font(Res.font.katex_main_regular, FontWeight.Normal)
    val mainBold = Font(Res.font.katex_main_bold, FontWeight.Bold)
    val mainItalic = Font(Res.font.katex_main_italic, FontWeight.Normal, FontStyle.Italic)
    val mainBoldItalic = Font(Res.font.katex_main_bolditalic, FontWeight.Bold, FontStyle.Italic)
    val mathItalic = Font(Res.font.katex_math_italic, FontWeight.Normal, FontStyle.Italic)
    val mathBoldItalic = Font(Res.font.katex_math_bolditalic, FontWeight.Bold, FontStyle.Italic)
    val ams = Font(Res.font.katex_ams_regular)
    val sansRegular = Font(Res.font.katex_sansserif_regular)
    val sansBold = Font(Res.font.katex_sansserif_bold, FontWeight.Bold)
    val sansItalic = Font(Res.font.katex_sansserif_italic, FontWeight.Normal, FontStyle.Italic)
    val typewriter = Font(Res.font.katex_typewriter_regular)
    val caligraphicRegular = Font(Res.font.katex_caligraphic_regular)
    val caligraphicBold = Font(Res.font.katex_caligraphic_bold, FontWeight.Bold)
    val frakturRegular = Font(Res.font.katex_fraktur_regular)
    val frakturBold = Font(Res.font.katex_fraktur_bold, FontWeight.Bold)
    val script = Font(Res.font.katex_script_regular)
    val size1 = Font(Res.font.katex_size1_regular)
    val size2 = Font(Res.font.katex_size2_regular)
    val size3 = Font(Res.font.katex_size3_regular)
    val size4 = Font(Res.font.katex_size4_regular)
    val fonts =
        listOf(
            mainRegular, mainBold, mainItalic, mainBoldItalic, mathItalic, mathBoldItalic, ams,
            sansRegular, sansBold, sansItalic, typewriter, caligraphicRegular, caligraphicBold,
            frakturRegular, frakturBold, script, size1, size2, size3, size4,
        )
    return remember(fonts) {
        RaftKatexFonts(
            main = FontFamily(mainRegular, mainBold, mainItalic, mainBoldItalic),
            math = FontFamily(mathItalic, mathBoldItalic),
            ams = FontFamily(ams),
            sansSerif = FontFamily(sansRegular, sansBold, sansItalic),
            typewriter = FontFamily(typewriter),
            caligraphic = FontFamily(caligraphicRegular, caligraphicBold),
            fraktur = FontFamily(frakturRegular, frakturBold),
            script = FontFamily(script),
            size1 = FontFamily(size1),
            size2 = FontFamily(size2),
            size3 = FontFamily(size3),
            size4 = FontFamily(size4),
        )
    }
}
