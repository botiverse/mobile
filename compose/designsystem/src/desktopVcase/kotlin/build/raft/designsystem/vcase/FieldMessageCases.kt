package build.raft.designsystem.vcase

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftTextField
import dev.vcase.VCase

/**
 * [RaftField] `reserveMessage` (web StableField), 4dp apart: nothing to say, a description, an error
 * in its place, a counter beside the description, and a 32dp control on the label row.
 */
@VCase("ds.field.reserved-message")
@Composable
fun FieldReservedMessageGallery() {
    CanvasSurface(padded = true) {
        Column(Modifier.width(326.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            RaftField(label = "Computer", reserveMessage = true) { RaftTextField("Studio", onValueChange = {}, modifier = Modifier.fillMaxWidth()) }
            RaftField(
                label = "Name",
                reserveMessage = true,
                labelTrailing = {
                    RaftIconButton(onClick = {}, tooltip = null, variant = RaftButtonVariant.Ghost) { RaftGlyphIcon(RaftGlyph.ChevronDown, size = 16.dp) }
                },
            ) { RaftTextField("Wug", onValueChange = {}, modifier = Modifier.fillMaxWidth()) }
            RaftField(label = "Model", description = "The provider's default.", reserveMessage = true) {
                RaftTextField("", onValueChange = {}, modifier = Modifier.fillMaxWidth())
            }
            RaftField(label = "Handle", error = "Start with a letter", reserveMessage = true) {
                RaftTextField("9wug", onValueChange = {}, modifier = Modifier.fillMaxWidth())
            }
            RaftField(
                label = "Description",
                description = "A sentence or two on who it is and what it does, over two lines.",
                reserveMessage = true,
                counter = "72/300",
                optional = true,
            ) { RaftTextField("Watches releases", onValueChange = {}, modifier = Modifier.fillMaxWidth()) }
        }
    }
}
