package build.raft.designsystem.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import build.raft.designsystem.tokens.RaftFade

/**
 * raft-design spacing.md › Edges fade (raft-ui `@utility mask-fade-x`, FitToolbar
 * `useScrollGroup` `data-overflow`): a sideways-scrolling strip fades its content out over
 * [width] (`fade-edge`, 24dp) at each edge with content hidden past it, and only there: an
 * edge it has scrolled away from (start), an edge with more to reveal (end), both, or
 * neither when it all fits. A mask, not a colour gradient: the content itself turns
 * transparent, so whatever is behind it (an applied filter's wash, a card, a hover) shows
 * through without a band.
 *
 * Put it on the clipping scroller, before its `horizontalScroll([scroll])`, so the mask
 * covers the viewport rather than the scrolled content. The fade's sides follow the
 * scroll in the draw phase (no recomposition while scrolling).
 */
fun Modifier.raftScrollEdgeFade(scroll: ScrollState, width: Dp = RaftFade.Edge): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val full = width.toPx().coerceAtMost(size.width / 2f)
            // web: start while scrollLeft > 1, end while scrollLeft < max - 1.
            val startWidth = if (scroll.value > 1) full else 0f
            val endWidth = if (scroll.value < scroll.maxValue - 1) full else 0f
            val rtl = layoutDirection == LayoutDirection.Rtl
            val left = if (rtl) endWidth else startWidth
            val right = if (rtl) startWidth else endWidth
            if (left > 0f) {
                drawRect(
                    Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = left),
                    size = Size(left, size.height),
                    blendMode = BlendMode.DstIn,
                )
            }
            if (right > 0f) {
                drawRect(
                    Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - right, endX = size.width),
                    topLeft = Offset(size.width - right, 0f),
                    size = Size(right, size.height),
                    blendMode = BlendMode.DstIn,
                )
            }
        }

/**
 * An item of a [raftScrollEdgeFade] strip that scrolls itself into view when it turns [active]
 * (web FitToolbar: an applied filter, `data-active`), stopping [padding]
 * (`scroll-padding-inline`, the fade's 24dp) clear of the edge so the fade never covers it.
 * It also reveals when its size changes while active (a filter's label becoming its new
 * value). Not on first show: a strip that opens with several filters applied stays at its
 * start rather than scrolling to whichever item composed last.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.raftScrollIntoViewWhen(active: Boolean, padding: Dp = RaftFade.Edge): Modifier {
    val requester = remember { BringIntoViewRequester() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val seen = remember { RaftRevealSeen(active) }
    val pad = with(LocalDensity.current) { padding.toPx() }
    LaunchedEffect(active, size) {
        val becameActive = active && !seen.active
        val resized = active && seen.size != IntSize.Zero && size != seen.size
        seen.active = active
        if (size != IntSize.Zero) seen.size = size
        if (size != IntSize.Zero && (becameActive || resized)) {
            requester.bringIntoView(Rect(-pad, 0f, size.width + pad, size.height.toFloat()))
        }
    }
    return this.onSizeChanged { size = it }.bringIntoViewRequester(requester)
}

/** What [raftScrollIntoViewWhen] last saw: whether the item was active, and its measured size. */
private class RaftRevealSeen(var active: Boolean, var size: IntSize = IntSize.Zero)
