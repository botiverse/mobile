package build.raft.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.tokens.RaftUiPalette

/**
 * The load line on the top edge of a page that is loading (an in-app web page), as a browser
 * draws it: a flat [RaftStroke.Strong] (2dp) line in the Progress `info` fill, with square
 * ends, reaching both edges of its parent. It has no track, no radius and no ink edge, so it
 * must sit flush on the edge: put it at the top of the page, full width, with no padding.
 * For progress inside a page (a download, a quota) use [RaftProgressBar].
 *
 * - [value] is the fraction loaded, 0..1.
 * - [value] = null is the indeterminate line: a half-width segment sweeping across, with the
 *   timing of [RaftProgressBar]'s. [animated] = false freezes it (screenshots, reduced motion).
 *
 * Semantics: a progress bar with its value (indeterminate without one), named by
 * [contentDescription].
 */
@Composable
fun RaftPageLoadingBar(
    value: Float?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    animated: Boolean = true,
) {
    val fraction = value?.coerceIn(0f, 1f)
    val fill = RaftUiPalette.info
    val sweep = if (fraction == null) raftProgressBarSweep(animated) else 0f
    Canvas(
        modifier.fillMaxWidth().height(RaftStroke.Strong).semantics {
            progressBarRangeInfo =
                if (fraction == null) ProgressBarRangeInfo.Indeterminate else ProgressBarRangeInfo(fraction, 0f..1f)
            contentDescription?.let { this.contentDescription = it }
        },
    ) {
        if (fraction == null) {
            // The half-width segment enters from the start edge and leaves past the end.
            val segment = size.width / 2f
            val start = -segment + (size.width + segment) * sweep
            clipRect { drawRect(color = fill, topLeft = Offset(start, 0f), size = Size(segment, size.height)) }
        } else if (fraction > 0f) {
            drawRect(color = fill, size = Size(size.width * fraction, size.height))
        }
    }
}
