package build.raft.designsystem.vcase

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftComposerDropOverlay
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import dev.vcase.VCase

/**
 * raft-ui Composer > `ComposerDropOverlay` (web MessageInput while files are dragged over it),
 * over a composer-sized box with text under it, which the overlay must hide.
 */
@VCase("ds.composer-drop-overlay.default")
@Composable
fun ComposerDropOverlayCase() {
    Box(Modifier.width(360.dp).height(104.dp).background(RaftTheme.colors.layerCanvas)) {
        Column(Modifier.padding(12.dp)) {
            RaftText("Message #launch-week", color = RaftTheme.colors.foregroundHint)
        }
        RaftComposerDropOverlay(label = "Drop files to attach", modifier = Modifier.matchParentSize())
    }
}
