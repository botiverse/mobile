package build.raft.designsystem.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.LocalRaftFocusedPreview
import build.raft.designsystem.components.LocalRaftHoveredPreview
import build.raft.designsystem.components.LocalRaftPressedPreview
import build.raft.designsystem.components.RaftModeBar
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftStroke
import kotlin.test.Test

/**
 * [RaftModeBar] (raft-ui 0.51 ModeBar, `mode-bar/compositions/preview` and `narrow`), light
 * and dark: under a panel header in a 560dp panel (one line, Done at the end), in a 358dp
 * phone column (the words wrap, Done keeps its place on the first line), title only, the
 * action hovered (mouse), pressed and keyboard-focused, and right-to-left.
 */
class ModeBarScreenshotTest {
    private val appearances = listOf(RaftAppearance.Light, RaftAppearance.Dark)

    @Test
    fun modeBar() =
        appearances.forEach { appearance ->
            RaftScreenshot.capture("mode_bar_${appearance.name.lowercase()}", appearance) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Caption("Under a panel header, 560dp: one line")
                    Panel(560) { Commenting() }
                    Caption("358dp phone: the words wrap, Done stays on the first line")
                    Panel(358) { Commenting() }
                    Caption("Title only")
                    Panel(358) { RaftModeBar(title = "Reordering.", actionLabel = "Done", onAction = {}) }
                    Caption("Action: hovered (mouse), pressed, focused")
                    CompositionLocalProvider(LocalRaftHoveredPreview provides true) { Panel(358, header = false) { Commenting() } }
                    CompositionLocalProvider(LocalRaftPressedPreview provides true) { Panel(358, header = false) { Commenting() } }
                    CompositionLocalProvider(LocalRaftFocusedPreview provides true) { Panel(358, header = false) { Commenting() } }
                    Caption("Right-to-left")
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { Panel(358, header = false) { Commenting() } }
                }
            }
        }

    @Composable
    private fun Commenting() {
        RaftModeBar(
            title = "Commenting · 1 draft.",
            description = "Select text to comment on it.",
            actionLabel = "Done",
            onAction = {},
        )
    }

    /** A panel: its 56dp header with the seam under it, the strip, then the panel's content. */
    @Composable
    private fun Panel(width: Int, header: Boolean = true, strip: @Composable () -> Unit) {
        val colors = RaftTheme.colors
        Column(Modifier.width(width.dp).background(colors.layerCanvas)) {
            if (header) {
                Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp, vertical = 17.dp)) {
                    RaftText("hero-copy.md", style = RaftTheme.typography.headline, color = colors.foreground)
                }
                Box(Modifier.fillMaxWidth().height(RaftStroke.Hairline).background(colors.lineSeam.compositeOver(colors.layerCanvas)))
            }
            strip()
            RaftText(
                "Ship the hero copy for the phone landing page.",
                Modifier.padding(16.dp),
                style = RaftTheme.typography.body,
                color = colors.foreground,
            )
        }
    }

    @Composable
    private fun Caption(text: String) {
        RaftText(text = text, style = RaftTheme.typography.monoMeta, color = RaftTheme.colors.foregroundHint)
    }
}
