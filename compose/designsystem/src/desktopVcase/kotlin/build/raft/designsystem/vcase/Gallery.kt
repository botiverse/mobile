package build.raft.designsystem.vcase

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

// Gallery helpers the screenshot tests each kept privately.

@Composable
internal fun Caption(text: String) {
    RaftText(text = text, style = RaftTheme.typography.monoMeta, color = RaftTheme.colors.foregroundHint)
}

@Composable
internal fun CanvasSurface(
    padded: Boolean = false,
    content: @Composable () -> Unit,
) {
    Column(Modifier.background(RaftTheme.colors.layerCanvas).then(if (padded) Modifier.padding(16.dp) else Modifier)) { content() }
}
