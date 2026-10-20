package build.raft.app.components

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * raft-ui Card (`variant="default"`): container radius 10dp, `--layer-card` fill, 1dp
 * `--line-hairline` edge, no shadow (content surfaces never lift). The legacy name is
 * kept for the call sites; the brutal offset-box shadow is gone, so [shadowOffset] is
 * ignored. Prefer `build.raft.designsystem.components.RaftCard` in new code.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun RaftBrutalCard(
    modifier: Modifier = Modifier,
    shadowOffset: Dp? = null,
    contentPadding: Dp = 14.dp,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(8.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .raftCardSurface()
            .padding(contentPadding),
        verticalArrangement = verticalArrangement,
        content = content
    )
}

/**
 * A width-bounded floating panel (server switcher and friends): the raft overlay surface
 * (container radius, `--layer-popover`, 1dp seam ring, the overlay float shadow). The
 * legacy brutal offset shadow is gone, so [shadowOffset] is ignored.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun RaftBrutalPanel(
    minWidth: Dp,
    maxWidth: Dp,
    modifier: Modifier = Modifier,
    shadowOffset: Dp? = null,
    contentModifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .widthIn(min = minWidth, max = maxWidth)
            .raftLookOverlaySurface()
            .then(contentModifier)
            .fillMaxWidth(),
        content = content
    )
}
