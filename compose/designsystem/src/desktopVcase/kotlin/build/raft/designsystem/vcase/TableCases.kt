package build.raft.designsystem.vcase

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftTable
import build.raft.designsystem.components.RaftTableAlign
import build.raft.designsystem.components.RaftTableColumn
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import dev.vcase.VCase

/**
 * [RaftTable] (raft-ui Table, `table/compositions/basic` and `wide`): filling its container with a
 * right-aligned column, one value wrapping, and wider than its container (scrolled to the start).
 */
@VCase("ds.table.gallery")
@Composable
fun TableGallery() {
    val columns =
        listOf(
            RaftTableColumn("Computer"),
            RaftTableColumn("Result"),
            RaftTableColumn("Duration", RaftTableAlign.End),
        )
    val rows =
        listOf(
            Triple("hetzner-ax42-1", "Succeeded", "3m 12s"),
            Triple("a computer whose name is long enough to wrap", "Failed", "9s"),
            Triple("oak-linux", "Succeeded", "41s"),
        )
    Column(Modifier.width(358.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        listOf("Fits its container" to 0.dp, "At least 480dp wide" to 480.dp).forEach { (name, minWidth) ->
            Caption(name)
            CanvasSurface {
                RaftTable(columns, rowCount = rows.size, caption = name, minWidth = minWidth) { row, column ->
                    val (computer, result, duration) = rows[row]
                    when (column) {
                        0 -> RaftText(computer, style = RaftTheme.typography.callout, color = RaftTheme.colors.foreground)
                        1 -> RaftBadge(result, appearance = RaftBadgeAppearance.Soft, variant = if (result == "Failed") RaftBadgeVariant.Warning else RaftBadgeVariant.Success)
                        else -> RaftText(duration, style = RaftTheme.typography.monoCode, color = RaftTheme.colors.foreground)
                    }
                }
            }
        }
    }
}
