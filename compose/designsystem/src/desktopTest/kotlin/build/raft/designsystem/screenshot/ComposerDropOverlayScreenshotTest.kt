package build.raft.designsystem.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftComposerDropOverlay
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test

/**
 * raft-ui Composer > `ComposerDropOverlay` (web MessageInput while files are dragged over it),
 * over a composer-sized box with text under it, which the overlay must hide.
 */
class ComposerDropOverlayScreenshotTest {
    @Test
    fun composerDropOverlay() =
        listOf(RaftAppearance.Light, RaftAppearance.Dark).forEach { appearance ->
            RaftScreenshot.capture("composer_drop_overlay_${appearance.name.lowercase()}", appearance) {
                Box(Modifier.width(360.dp).height(104.dp).background(RaftTheme.colors.layerCanvas)) {
                    Column(Modifier.padding(12.dp)) {
                        RaftText("Message #launch-week", color = RaftTheme.colors.foregroundHint)
                    }
                    RaftComposerDropOverlay(label = "Drop files to attach", modifier = Modifier.matchParentSize())
                }
            }
        }
}
