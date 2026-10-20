package build.raft.designsystem.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** raft-ui Popover metrics (popover.tsx, Popover > Rules). */
object RaftPopoverDefaults {
    /** "Make a popover 300px wide". */
    val Width: Dp = 300.dp

    /** `min-w-[220px]`: the narrowest a popover that hugs its content gets. */
    val MinWidth: Dp = 220.dp

    /** "with 16px padding". */
    val ContentPadding: PaddingValues = PaddingValues(16.dp)
}

/**
 * raft-ui 0.10.3 Popover panel (`PopoverPopup`, the surface without positioning): a small
 * panel next to what was tapped, for a little input (snooze, rename) or a short list of
 * places (a server switcher). The same popover layer as [RaftMenuSurface]: `layer-popover`,
 * container radius, a 1dp `line-seam` ring, `shadow-float`, content clipped; provides
 * `layer-popover` as the surface and `foreground` as the content colour.
 *
 * - [width]: 300dp by default (Popover rules). Null: as wide as the incoming constraints
 *   allow, at least [RaftPopoverDefaults.MinWidth] (220dp); bound it with the caller's
 *   `Modifier.widthIn(…)`. It never measures its content by intrinsics, so a lazy list or a
 *   subcomposed layout may go inside (unlike [RaftMenuSurface] without a width).
 * - [contentPadding] round [content]: 16dp (Popover rules). Pass `PaddingValues(0.dp)` for
 *   rows that touch the edges (list rows, a [RaftMenuSearchField]).
 * - [header] sits flush above the padded content, edge to edge.
 *
 * Positioning, dismissal and Back are the caller's (a popup or the app's overlay host).
 * Don't put a pink button in it: confirm with a `Default` button, cancel with `Ghost`.
 */
@Composable
fun RaftPopoverSurface(
    modifier: Modifier = Modifier,
    width: Dp? = RaftPopoverDefaults.Width,
    contentPadding: PaddingValues = RaftPopoverDefaults.ContentPadding,
    header: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    RaftPopoverChrome(
        modifier.then(if (width != null) Modifier.width(width) else Modifier.widthIn(min = RaftPopoverDefaults.MinWidth)),
    ) {
        header?.invoke()
        Column(Modifier.fillMaxWidth().padding(contentPadding), content = content)
    }
}
