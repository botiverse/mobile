package build.raft.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import build.raft.designsystem.motion.RaftMotion
import build.raft.designsystem.motion.rememberRaftReducedMotion
import build.raft.designsystem.theme.RaftTheme
import kotlin.math.roundToInt

/** How far up a swipe its content does not scroll must travel to close a [RaftPullDownPanel] (web 40px). */
val RaftPullDownSwipeClose: Dp = 40.dp

/** The most of the window's height a [RaftPullDownPanel] takes (web `max-h-[70dvh]`). */
const val RaftPullDownMaxHeightFraction: Float = 0.7f

/**
 * A panel pulled down from a bar over the content under it (the web task dialog's
 * `TaskHeadPullDown`, botiverse/slock #11722). It is not a Popover (it is not anchored to a point)
 * nor a Drawer or Sheet: it has no scrim and is not modal, so what is under it stays live.
 *
 * It hangs from the bar's whole width on `layer-canvas`, with `shadow-float` under it (an overlay,
 * raft-ui Elevation: a floating layer shows its level by its shadow, not by a line), at most
 * [maxHeightFraction] of the window tall; its content scrolls inside it past that. It slides down
 * from under the bar and back up with `ease-slide` over 220ms (raft-ui Motion: slides); with
 * [reducedMotion] it appears and goes at once.
 *
 * Put it at the top of a Box that covers the content it hangs over, right under the bar. A swipe up
 * that its content does not take (the content is at its end) past [RaftPullDownSwipeClose], and
 * Escape while focus is inside it, call [onDismiss]. The caller closes it on everything else: a
 * second tap on the bar, a tap outside it, Back.
 */
@Composable
fun RaftPullDownPanel(
    open: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** The region's name for screen readers. */
    paneTitle: String? = null,
    maxHeightFraction: Float = RaftPullDownMaxHeightFraction,
    reducedMotion: Boolean = rememberRaftReducedMotion(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val reveal = remember { Animatable(if (open) 1f else 0f) }
    LaunchedEffect(open, reducedMotion) {
        val target = if (open) 1f else 0f
        if (reducedMotion) reveal.snapTo(target) else reveal.animateTo(target, tween(RaftMotion.SlideMillis, easing = RaftMotion.EaseSlide))
    }
    val shown by remember { derivedStateOf { reveal.value > 0f } }
    if (!open && !shown) return

    val colors = RaftTheme.colors
    val dark = RaftTheme.isDark
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val thresholdPx = with(LocalDensity.current) { RaftPullDownSwipeClose.toPx() }
    val swipe = remember { RaftPullDownSwipe() }
    val swipeConnection =
        remember(swipe, thresholdPx) {
            object : NestedScrollConnection {
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (source == NestedScrollSource.UserInput && swipe.onUnconsumed(available.y, thresholdPx)) {
                        swipe.reset()
                        currentOnDismiss()
                    }
                    return Offset.Zero
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    swipe.reset()
                    return Velocity.Zero
                }
            }
        }
    val windowHeightPx = LocalWindowInfo.current.containerSize.height
    Layout(
        content = {
            Column(Modifier.fillMaxWidth().nestedScroll(swipeConnection).verticalScroll(rememberScrollState()), content = content)
        },
        modifier =
            modifier
                .fillMaxWidth()
                .raftFloatShadow(dark, 0.dp)
                .background(colors.layerCanvas)
                .clipToBounds()
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
                        currentOnDismiss()
                        true
                    } else {
                        false
                    }
                }
                .semantics { paneTitle?.let { this.paneTitle = it } },
    ) { measurables, constraints ->
        val cap = if (windowHeightPx > 0) (windowHeightPx * maxHeightFraction).roundToInt() else constraints.maxHeight
        val placeable = measurables.single().measure(constraints.copy(minHeight = 0, maxHeight = minOf(constraints.maxHeight, cap)))
        // Revealed from the top: the panel's bottom edge comes down with its content on it.
        val height = (placeable.height * reveal.value).roundToInt()
        layout(placeable.width, height) { placeable.place(0, height - placeable.height) }
    }
}

/**
 * The swipe up that closes a [RaftPullDownPanel]: how far a drag has gone up past the end of the
 * panel's content (the part its own scroll did not take). A drag back down starts it over.
 */
class RaftPullDownSwipe {
    var travelledPx: Float = 0f
        private set

    /** [unconsumedY]: what the content left of a drag step (negative: up). True once up past [thresholdPx]. */
    fun onUnconsumed(unconsumedY: Float, thresholdPx: Float): Boolean {
        if (unconsumedY > 0f) travelledPx = 0f else travelledPx -= unconsumedY
        return travelledPx >= thresholdPx
    }

    /** The drag ended (or closed the panel). */
    fun reset() {
        travelledPx = 0f
    }
}
