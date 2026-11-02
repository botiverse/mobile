package build.raft.designsystem.screenshot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftScale
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test

/**
 * The visual-testing typography specimen (components.ui.typography.specimen) drawn by the desktop
 * renderer (Skia, as the Mac app): every raft-ui text style and the body face at 400 to 700, one
 * line each with Latin, digits and Han, so its line pitch can be measured against the web's (artin
 * 2026-11-02: the app's type reads heavier than the web's). Compact and wide (desktop) scales.
 */
class TypographySpecimenScreenshotTest {
    private fun capture(name: String, scale: RaftScale) =
        RaftScreenshot.capture(name, RaftAppearance.Light, scale = scale) {
            val type = RaftTheme.typography
            Column(Modifier.width(310.dp)) {
                listOf(
                    "Headline" to type.headline,
                    "Body 400" to type.body,
                    "Body 500" to type.body.copy(fontWeight = FontWeight.Medium),
                    "Body 600" to type.body.copy(fontWeight = FontWeight.SemiBold),
                    "Body 700" to type.body.copy(fontWeight = FontWeight.Bold),
                    "Subhead" to type.subhead,
                    "Callout" to type.callout,
                    "Footnote" to type.footnote,
                    "Caption 500" to type.caption,
                    "Label 600" to type.label,
                    "Mono meta" to type.monoMeta,
                    "Mono code" to type.monoCode,
                ).forEach { (label, style) ->
                    RaftText("$label 排版 Aa 0123", style = style, color = RaftTheme.colors.foreground, maxLines = 1)
                }
            }
        }

    @Test
    fun typographySpecimen() = capture("typography_specimen", RaftScale.Compact)

    @Test
    fun typographySpecimenWide() = capture("typography_specimen_wide", RaftScale.Wide)
}
