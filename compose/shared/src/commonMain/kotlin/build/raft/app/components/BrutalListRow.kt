package build.raft.app.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftListRowVariant

/**
 * A tappable title + subtitle row on its own card: the design-system [RaftListRow]
 * (`plain`) in a [RaftListRowGroup].
 */
@Composable
@Deprecated(
    "Legacy RaftBrutal layer: use build.raft.designsystem.components.RaftListRow in a RaftListRowGroup. Add no new uses; see docs/raftbrutal-migration.md.",
    level = DeprecationLevel.WARNING,
)
internal fun RaftBrutalListRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    RaftListRowGroup(modifier = modifier.fillMaxWidth()) {
        RaftListRow(
            title = title,
            onClick = onClick,
            variant = RaftListRowVariant.Plain,
            enabled = enabled,
            description = subtitle.takeIf { it.isNotBlank() }
        )
    }
}
