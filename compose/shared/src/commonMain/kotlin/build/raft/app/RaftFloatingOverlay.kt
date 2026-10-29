package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.zIndex

internal expect fun Modifier.raftNativeDispatchCapture(): Modifier

internal data class RaftFloatingOverlayLayout(
    val origin: Offset = Offset.Zero,
    val size: Size = Size.Zero
) {
    val isReady: Boolean = size.width > 0f && size.height > 0f
}

internal enum class RaftFloatingVerticalPlacement {
    Below,
    Above,
    Auto,
    /** Above the anchor when it fits, otherwise below (a bar that must not cover what was tapped). */
    AbovePreferred,
    /** Web `placeFloatingOverlay` axis: see [raftContextMenuAxisPosition]. */
    ContextMenu
}

internal enum class RaftFloatingHorizontalPlacement {
    Anchor,
    Center,
    /** Web `placeFloatingOverlay` axis: see [raftContextMenuAxisPosition]. */
    ContextMenu
}

/**
 * One axis of web `placeFloatingOverlay` (context menus): the overlay's leading edge sits
 * on the anchor when it fits; otherwise it flips to end at the anchor when that fits;
 * otherwise the anchor is clamped [viewportGap] inside the viewport.
 */
internal fun raftContextMenuAxisPosition(
    anchor: Float,
    contentSize: Float,
    viewportSize: Float,
    viewportGap: Float
): Float {
    val min = viewportGap
    val max = (viewportSize - contentSize - viewportGap).coerceAtLeast(min)
    return when {
        anchor + contentSize <= viewportSize - viewportGap -> anchor.coerceIn(min, max)
        anchor - contentSize >= min -> anchor - contentSize
        else -> anchor.coerceIn(min, max)
    }
}

internal enum class RaftFloatingOverlayOutsidePointerMode {
    /** Consume an outside gesture and dismiss without activating the covered control. */
    Dismiss,

    /** Leave outside coordinates unhittable in this overlay so lower siblings receive them. */
    PassThrough
}

internal val RaftFloatingOverlayOutsidePointerMode.installsOutsideDismissBarrier: Boolean
    get() = this == RaftFloatingOverlayOutsidePointerMode.Dismiss

@Composable
internal fun RaftModalAnchoredPopover(
    anchor: RaftPopoverAnchor,
    contentSize: Size,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    viewportGap: Float = RaftFloatingDefaultViewportGap,
    horizontalOffset: Float = 0f,
    verticalOffset: Float = RaftFloatingDefaultVerticalOffset,
    verticalOffsetAbove: Float = verticalOffset,
    verticalPlacement: RaftFloatingVerticalPlacement = RaftFloatingVerticalPlacement.Below,
    horizontalPlacement: RaftFloatingHorizontalPlacement = RaftFloatingHorizontalPlacement.Anchor,
    horizontalAutoFlip: Boolean = false,
    dismissActivationDelayMillis: Long = RaftFloatingOverlayDismissActivationDelayMillis,
    content: @Composable () -> Unit
) {
    Modal(onWillDismiss = { onDismiss() }) {
        RaftAnchoredPopover(
            anchor = anchor,
            contentSize = contentSize,
            onDismiss = onDismiss,
            modifier = modifier,
            viewportGap = viewportGap,
            horizontalOffset = horizontalOffset,
            verticalOffset = verticalOffset,
            verticalOffsetAbove = verticalOffsetAbove,
            verticalPlacement = verticalPlacement,
            horizontalPlacement = horizontalPlacement,
            horizontalAutoFlip = horizontalAutoFlip,
            dismissActivationDelayMillis = dismissActivationDelayMillis,
            content = content
        )
    }
}

@Composable
internal fun RaftAnchoredPopover(
    anchor: RaftPopoverAnchor,
    contentSize: Size,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    viewportGap: Float = RaftFloatingDefaultViewportGap,
    horizontalOffset: Float = 0f,
    verticalOffset: Float = RaftFloatingDefaultVerticalOffset,
    verticalOffsetAbove: Float = verticalOffset,
    verticalPlacement: RaftFloatingVerticalPlacement = RaftFloatingVerticalPlacement.Below,
    horizontalPlacement: RaftFloatingHorizontalPlacement = RaftFloatingHorizontalPlacement.Anchor,
    // #223: when true, the popover flips to the left of the anchor if
    // there is no room on the right. Used by RaftBaseMenuOverlay so
    // long-press menus don't run off the screen's right edge.
    horizontalAutoFlip: Boolean = false,
    dismissActivationDelayMillis: Long = RaftFloatingOverlayDismissActivationDelayMillis,
    outsidePointerMode: RaftFloatingOverlayOutsidePointerMode =
        RaftFloatingOverlayOutsidePointerMode.Dismiss,
    content: @Composable () -> Unit
) {
    anchor.renderInOverlay(
        contentSize = contentSize,
        onDismiss = onDismiss,
        modifier = modifier,
        viewportGap = viewportGap,
        horizontalOffset = horizontalOffset,
        verticalOffset = verticalOffset,
        verticalOffsetAbove = verticalOffsetAbove,
        verticalPlacement = verticalPlacement,
        horizontalPlacement = horizontalPlacement,
        horizontalAutoFlip = horizontalAutoFlip,
        dismissActivationDelayMillis = dismissActivationDelayMillis,
        outsidePointerMode = outsidePointerMode,
        content = content
    )
}

@Composable
internal fun RaftFloatingOverlayHost(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    // task #436 follow-up: the activation delay exists for LONG-PRESS-opened menus,
    // where the press release lands after the host has composed and would instantly
    // dismiss. CLICK-opened popovers complete their opening gesture before the host
    // mounts, so they pass 0 — with a nonzero delay, rapid consecutive taps either
    // land on the trigger pre-mount (toggling) or inside the window (eaten), which
    // reads as "the popover won't close".
    dismissActivationDelayMillis: Long = RaftFloatingOverlayDismissActivationDelayMillis,
    // Suggestion/autocomplete surfaces remain part of the focused editor experience. They use a
    // pass-through host so the editor toolbar stays interactive, while modal menus keep the
    // default full-screen dismiss barrier.
    outsidePointerMode: RaftFloatingOverlayOutsidePointerMode =
        RaftFloatingOverlayOutsidePointerMode.Dismiss,
    content: @Composable (RaftFloatingOverlayLayout) -> Unit
) {
    // #223 fix: keep `origin` and `size` in PIXELS (the same unit as the
    // anchor passed in from onGloballyPositioned). The previous
    // toSlockDpOffset/toSlockDpSize conversion mixed pixels (anchor) with
    // dp (origin / size) and the resulting localAnchor was wrong — for a
    // row whose y in pixels was 1442 and overlay size 411x923 dp, the
    // localAnchor.y came out as 1442 (treated as dp), and the menu
    // landed at y=877 dp ≈ 2303 px (screen bottom) instead of near
    // the row. Keep everything in pixels to avoid the unit mismatch.
    // Contract: docs/kuikly-overlay-coordinate-contract.md.
    var overlayLayout by remember { mutableStateOf(RaftFloatingOverlayLayout()) }
    // Defer the host's dismiss-clickable so the long-press release that
    // opens the menu does not immediately propagate to the host's
    // onDismiss and close the menu before the user sees anything. The
    // 150ms window covers the standard Android long-press threshold
    // (~500ms) minus the time to compose + render the menu. The menu's
    // own content Column already has a no-op clickable
    // (raftFloatingContentClickTarget) so clicks on the menu do not
    // bubble up; the host only needs to dismiss on clicks outside the
    // menu.
    var dismissClickableActive by remember { mutableStateOf(dismissActivationDelayMillis <= 0) }
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    RaftBackHandler(enabled = true, label = "floating_overlay", onBack = currentOnDismiss)
    // #223: defer the dismiss clickable so the long-press release does not
    // immediately propagate to onDismiss. 150ms covers the standard Android
    // long-press threshold (~500ms) minus the time to compose + render the menu.
    if (dismissActivationDelayMillis > 0) {
        LaunchedEffect(Unit) {
            delay(dismissActivationDelayMillis)
            dismissClickableActive = true
        }
    }
    Box(
        modifier = modifier
            .zIndex(RaftFloatingOverlayHostZIndex)
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                overlayLayout = RaftFloatingOverlayLayout(
                    origin = coordinates.positionInRoot(),
                    size = Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())
                )
            },
        contentAlignment = Alignment.TopStart
    ) {
        if (outsidePointerMode.installsOutsideDismissBarrier) {
            RaftFloatingOverlayDismissBarrier(
                dismissClickableActive = dismissClickableActive,
                onDismiss = onDismiss
            )
        }
        content(overlayLayout)
    }
}

internal fun Modifier.raftFloatingContentClickTarget(): Modifier =
    raftFloatingContentPointerBarrier()
        .clickable(onClick = {})

private fun Modifier.raftFloatingContentPointerBarrier(): Modifier =
    raftNativeDispatchCapture()

/**
 * Full-screen native dispatch boundary for a modal surface. Keep it as a
 * sibling behind dialog content: applying capture to a parent container
 * prevents native siblings from seeing the gesture, but it also prevents
 * descendant Compose controls from receiving that gesture on Android.
 */
@Composable
internal fun RaftModalDismissBarrier(
    overlayAlpha: Float,
    onDismiss: () -> Unit,
    dismissEnabled: Boolean = true
) {
    val currentOnDismiss = rememberUpdatedState(onDismiss)
    // raft-ui dialogs dim with the `--layer-backdrop` token (black 65% light /
    // 60% dark); lighter, sheet-style veils keep their own alpha.
    val veil =
        if (overlayAlpha >= 0.5f) {
            RaftColor.Backdrop
        } else {
            // Fixed ink: a dimming veil stays dark in dark mode.
            RaftColor.StickerInk.copy(alpha = overlayAlpha)
        }
    Box(
        modifier =
            Modifier
                // The veil covers the safe-area strips the enclosing shell padded.
                .raftBleedHorizontalSafeArea(LocalRaftHorizontalSafeAreaBleed.current)
                .fillMaxSize()
                .background(veil)
                .raftNativeDispatchCapture()
                .raftModalDismissPointerBarrier(dismissEnabled) {
                    currentOnDismiss.value.invoke()
                }
    )
}

@Composable
private fun RaftFloatingOverlayDismissBarrier(
    dismissClickableActive: Boolean,
    onDismiss: () -> Unit
) {
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            // The barrier needs a non-transparent background so Kuikly creates a
            // touch-capturing native view, but react draws no scrim behind menus at
            // all: a 1% BLACK wash turned every pixel under the host into a light
            // gray (white became 252,252,252 in visual captures). Wash with white
            // instead — same hit-testing, invisible over the app's white surfaces
            // (task #370).
            .background(RaftColor.SurfaceWhite.copy(alpha = RaftFloatingOverlayTouchGuardAlpha))
            .raftNativeDispatchCapture()
            .pointerInput(dismissClickableActive) {
                awaitEachGesture {
                    val tap = RaftOutsideTapTracker()
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                        val dismiss =
                            tap.onEvent(
                                anyPressed = event.changes.any { it.pressed },
                                moved = event.changes.any { it.positionChanged() },
                            ) ?: continue
                        if (dismiss && dismissClickableActive) currentOnDismiss()
                        break
                    }
                }
            }
    )
}

/**
 * One outside gesture on the dismiss barrier: a press and its release without movement is a tap
 * that dismisses. Only a press starts a gesture. A mouse also sends hover events (enter, move,
 * exit) with no button down: the barrier appearing under the pointer sends an enter at once, and
 * counting that "release" as a tap closed a click-opened popover the moment it opened (desktop,
 * the thread followers popover). Touch input always starts with the press, so it is unchanged.
 */
internal class RaftOutsideTapTracker {
    private var down = false
    private var moved = false

    /** Feeds one pointer event; null while the gesture goes on, else whether it was a dismissing tap. */
    fun onEvent(anyPressed: Boolean, moved: Boolean): Boolean? {
        if (!down) {
            if (!anyPressed) return false
            down = true
            return null
        }
        this.moved = this.moved || moved
        return if (anyPressed) null else !this.moved
    }
}

private const val RaftFloatingOverlayTouchGuardAlpha = 0.01f

internal const val RaftFloatingOverlayDismissActivationDelayMillis = 150L
internal const val RaftFloatingOverlayClickTriggeredDismissDelayMillis = 0L

internal fun RaftFloatingOverlayLayout.positionContent(
    anchor: Offset,
    contentSize: Size,
    viewportGap: Float = RaftFloatingDefaultViewportGap,
    horizontalOffset: Float = 0f,
    verticalOffset: Float = RaftFloatingDefaultVerticalOffset,
    verticalOffsetAbove: Float = verticalOffset,
    verticalPlacement: RaftFloatingVerticalPlacement = RaftFloatingVerticalPlacement.Below,
    horizontalPlacement: RaftFloatingHorizontalPlacement = RaftFloatingHorizontalPlacement.Anchor,
    // #223: when true, place content to the left of the anchor (anchor
    // is treated as the content's right edge) when there is no room on
    // the right. Mirrors React placeSidebarContextMenu.ts placeMenuAxis
    // behavior so the menu does not run off the right edge.
    // #327: improved to pick the side that shows more visible content
    // when both left and right placements would overflow.
    horizontalAutoFlip: Boolean = false
): Offset? {
    if (!isReady || !anchor.isRaftUsableAnchor() || contentSize.width <= 0f || contentSize.height <= 0f) {
        return null
    }
    val localAnchor = Offset(anchor.x - origin.x, anchor.y - origin.y)
    val x = raftHorizontalPosition(
        localAnchorX = localAnchor.x,
        contentWidth = contentSize.width,
        viewportWidth = size.width,
        horizontalOffset = horizontalOffset,
        viewportGap = viewportGap,
        horizontalPlacement = horizontalPlacement,
        autoFlip = horizontalAutoFlip
    )
    val y = raftVerticalPosition(
        localAnchorY = localAnchor.y,
        contentHeight = contentSize.height,
        viewportHeight = size.height,
        verticalOffset = verticalOffset,
        verticalOffsetAbove = verticalOffsetAbove,
        viewportGap = viewportGap,
        verticalPlacement = verticalPlacement
    )
    return Offset(x, y)
}

/**
 * Compute the horizontal pixel offset for a floating overlay relative to
 * [RaftFloatingOverlayLayout.origin]. When [horizontalPlacement] is [RaftFloatingHorizontalPlacement.Center],
 * the overlay is placed horizontally centered within the viewport bounds plus [horizontalOffset],
 * clamped to [[viewportGap], [maxX]].
 * When [autoFlip] is true and the
 * preferred right-of-anchor placement overflows the viewport, the overlay
 * is placed to the left of the anchor. When both sides overflow the
 * viewport the side that shows more of the content is chosen (#327).
 */
internal fun raftHorizontalPosition(
    localAnchorX: Float,
    contentWidth: Float,
    viewportWidth: Float,
    horizontalOffset: Float = 0f,
    viewportGap: Float = RaftFloatingDefaultViewportGap,
    horizontalPlacement: RaftFloatingHorizontalPlacement = RaftFloatingHorizontalPlacement.Anchor,
    autoFlip: Boolean = false
): Float {
    val maxX = (viewportWidth - contentWidth - viewportGap).coerceAtLeast(viewportGap)
    if (horizontalPlacement == RaftFloatingHorizontalPlacement.ContextMenu) {
        return raftContextMenuAxisPosition(localAnchorX + horizontalOffset, contentWidth, viewportWidth, viewportGap)
    }
    if (horizontalPlacement == RaftFloatingHorizontalPlacement.Center) {
        val centerX = ((viewportWidth - contentWidth) / 2f) + horizontalOffset
        return centerX.coerceIn(viewportGap, maxX)
    }
    if (!autoFlip) {
        return (localAnchorX + horizontalOffset).coerceIn(viewportGap, maxX)
    }
    val rightX = localAnchorX + horizontalOffset
    val leftX = localAnchorX - contentWidth + horizontalOffset
    val rightFits = rightX + contentWidth <= viewportWidth - viewportGap
    val leftFits = leftX >= viewportGap
    return when {
        rightFits -> rightX.coerceIn(viewportGap, maxX)
        leftFits -> leftX.coerceAtMost(maxX)
        else -> {
            // Both sides overflow — pick the side that shows more content.
            val rightEdge = (rightX + contentWidth).coerceAtMost(viewportWidth - viewportGap)
            val rightVisible = (rightEdge - rightX.coerceAtLeast(viewportGap)).coerceAtLeast(0f)
            val leftEdge = (leftX + contentWidth).coerceAtMost(viewportWidth - viewportGap)
            val leftVisible = (leftEdge - leftX.coerceAtLeast(viewportGap)).coerceAtLeast(0f)
            if (rightVisible >= leftVisible) {
                rightX.coerceIn(viewportGap, maxX)
            } else {
                leftX.coerceIn(viewportGap, maxX)
            }
        }
    }
}

/**
 * Compute the vertical pixel offset for a floating overlay relative to
 * [RaftFloatingOverlayLayout.origin].  When [verticalPlacement] is [Auto]
 * and the preferred below-anchor placement overflows the viewport, the
 * overlay is placed above the anchor.  When both above and below overflow
 * the side that shows more of the content is chosen (#327).
 */
internal fun raftVerticalPosition(
    localAnchorY: Float,
    contentHeight: Float,
    viewportHeight: Float,
    verticalOffset: Float = RaftFloatingDefaultVerticalOffset,
    verticalOffsetAbove: Float = verticalOffset,
    viewportGap: Float = RaftFloatingDefaultViewportGap,
    verticalPlacement: RaftFloatingVerticalPlacement = RaftFloatingVerticalPlacement.Below
): Float {
    val maxY = (viewportHeight - contentHeight - viewportGap).coerceAtLeast(viewportGap)
    val aboveY = localAnchorY - contentHeight - verticalOffsetAbove
    val belowY = localAnchorY + verticalOffset
    if (verticalPlacement == RaftFloatingVerticalPlacement.ContextMenu) {
        return raftContextMenuAxisPosition(belowY, contentHeight, viewportHeight, viewportGap)
    }
    if (verticalPlacement == RaftFloatingVerticalPlacement.Below) {
        return belowY.coerceIn(viewportGap, maxY)
    }
    if (verticalPlacement == RaftFloatingVerticalPlacement.Above) {
        return aboveY.coerceIn(viewportGap, maxY)
    }
    if (verticalPlacement == RaftFloatingVerticalPlacement.AbovePreferred) {
        return (if (aboveY >= viewportGap) aboveY else belowY).coerceIn(viewportGap, maxY)
    }
    val belowFits = belowY + contentHeight <= viewportHeight - viewportGap
    val aboveFits = aboveY >= viewportGap
    return when {
        belowFits -> belowY.coerceIn(viewportGap, maxY)
        aboveFits -> aboveY.coerceAtMost(maxY)
        else -> {
            // Both sides overflow — pick the side that shows more content.
            val belowEdge = (belowY + contentHeight).coerceAtMost(viewportHeight - viewportGap)
            val belowVisible = (belowEdge - belowY.coerceAtLeast(viewportGap)).coerceAtLeast(0f)
            val aboveEdge = (aboveY + contentHeight).coerceAtMost(viewportHeight - viewportGap)
            val aboveVisible = (aboveEdge - aboveY.coerceAtLeast(viewportGap)).coerceAtLeast(0f)
            if (belowVisible >= aboveVisible) {
                belowY.coerceIn(viewportGap, maxY)
            } else {
                aboveY.coerceIn(viewportGap, maxY)
            }
        }
    }
}

internal fun Offset.isRaftUsableAnchor(): Boolean =
    x != 0f || y != 0f

internal const val RaftFloatingDefaultViewportGap = 8f
internal const val RaftFloatingDefaultVerticalOffset = 8f
