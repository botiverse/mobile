package build.raft.app

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme

/**
 * A Lucide icon for a `build.raft.designsystem` slot (button, tab, badge icon): drawn in
 * the slot's ink ([LocalRaftContentColor]), `foreground` outside one.
 */
@Composable
internal fun RaftContentLucideIcon(
    name: RaftLucideIconName,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    spinning: Boolean = false
) {
    val ink = LocalRaftContentColor.current.takeIf { it.isSpecified } ?: RaftTheme.colors.foreground
    RaftLucideIcon(name, modifier = modifier.size(size), color = raftArgbHex(ink), spinning = spinning)
}
