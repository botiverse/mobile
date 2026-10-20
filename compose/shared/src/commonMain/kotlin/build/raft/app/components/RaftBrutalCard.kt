package build.raft.app.components

import androidx.compose.runtime.Composable
import build.raft.designsystem.components.RaftCard
import build.raft.designsystem.components.RaftListRowGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Legacy-named card; renders the design-system [RaftCard] (`default`: container radius,
 * card layer, hairline edge, 16dp body padding, no shadow) with [content] stacked by
 * [verticalArrangement]. A card with no [contentPadding] (a list on a card) is the bare
 * card surface ([RaftListRowGroup]). Prefer
 * `build.raft.designsystem.components.RaftCard` in new code.
 */
@Composable
internal fun RaftBrutalCard(
    modifier: Modifier = Modifier,
    contentPadding: Dp = 14.dp,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(8.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    if (contentPadding == 0.dp) {
        RaftListRowGroup(modifier = modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = verticalArrangement, content = content)
        }
    } else {
        RaftCard(modifier = modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = verticalArrangement, content = content)
        }
    }
}
