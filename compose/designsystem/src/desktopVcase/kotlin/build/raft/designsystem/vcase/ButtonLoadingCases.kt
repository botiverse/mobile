package build.raft.designsystem.vcase

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import dev.vcase.VCase

/**
 * Loading buttons as callers write them (raft-ui Button, raft-design #1389): many disable the
 * button while the request runs (`enabled = !submitting`) as well as setting `loading`. Both
 * rows must look the same: the spinner over the button's own colours, never the grey disabled
 * look. The last row is a disabled button that is not loading, for contrast.
 */
@VCase("ds.button.loading")
@Composable
fun ButtonLoadingGallery() {
    Column(Modifier.width(358.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Caption("loading")
        LoadingRow(enabled = true, loading = true)
        Caption("loading and disabled (enabled = !submitting)")
        LoadingRow(enabled = false, loading = true)
        Caption("disabled, not loading")
        LoadingRow(enabled = false, loading = false)
    }
}

@Composable
private fun LoadingRow(enabled: Boolean, loading: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RaftButton("Confirm", onClick = {}, variant = RaftButtonVariant.Primary, enabled = enabled, loading = loading)
        RaftButton("Save", onClick = {}, enabled = enabled, loading = loading)
        RaftButton("Delete", onClick = {}, variant = RaftButtonVariant.Danger, enabled = enabled, loading = loading)
        RaftIconButton(onClick = {}, tooltip = "Search", enabled = enabled, loading = loading) {
            RaftGlyphIcon(RaftGlyph.Search)
        }
    }
}
