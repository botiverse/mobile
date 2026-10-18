package build.raft.app

import build.raft.app.components.slockRaftActive
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.gestures.awaitEachGesture
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.geometry.Size
import com.tencent.kuikly.compose.ui.input.pointer.pointerInput
import com.tencent.kuikly.compose.ui.input.pointer.positionChanged
import com.tencent.kuikly.compose.ui.layout.onGloballyPositioned
import com.tencent.kuikly.compose.ui.layout.positionInRoot
import com.tencent.kuikly.compose.ui.zIndex

internal expect fun Modifier.slockNativeDispatchCapture(): Modifier

internal data class SlockFloatingOverlayLayout(
    val origin: Offset = Offset.Zero,
    val size: Size = Size.Zero
) {
    val isReady: Boolean = size.width > 0f && size.height > 0f
}

internal enum class SlockFloatingVerticalPlacement {
    Below,
    Above,
    Auto
}

internal enum class SlockFloatingHorizontalPlacement {
    Anchor,
    Center
}

internal enum class SlockFloatingOverlayOutsidePointerMode {
    /** Consume an outside gesture and dismiss without activating the covered control. */
    Dismiss,

    /** Leave outside coordinates unhittable in this overlay so lower siblings receive them. */
    PassThrough
}

internal val SlockFloatingOverlayOutsidePointerMode.installsOutsideDismissBarrier: Boolean
    get() = this == SlockFloatingOverlayOutsidePointerMode.Dismiss

@Composable
internal fun SlockModalAnchoredPopover(
    anchor: SlockPopoverAnchor,
    contentSize: Size,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    viewportGap: Float = SlockFloatingDefaultViewportGap,
    horizontalOffset: Float = 0f,
    verticalOffset: Float = SlockFloatingDefaultVerticalOffset,
    verticalOffsetAbove: Float = verticalOffset,
    verticalPlacement: SlockFloatingVerticalPlacement = SlockFloatingVerticalPlacement.Below,
    horizontalPlacement: SlockFloatingHorizontalPlacement = SlockFloatingHorizontalPlacement.Anchor,
    horizontalAutoFlip: Boolean = false,
    dismissActivationDelayMillis: Long = SlockFloatingOverlayDismissActivationDelayMillis,
    content: @Composable () -> Unit
) {
    Modal(onWillDismiss = { onDismiss() }) {
        SlockAnchoredPopover(
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
internal fun SlockAnchoredPopover(
    anchor: SlockPopoverAnchor,
    contentSize: Size,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    viewportGap: Float = SlockFloatingDefaultViewportGap,
    horizontalOffset: Float = 0f,
    verticalOffset: Float = SlockFloatingDefaultVerticalOffset,
    verticalOffsetAbove: Float = verticalOffset,
    verticalPlacement: SlockFloatingVerticalPlacement = SlockFloatingVerticalPlacement.Below,
    horizontalPlacement: SlockFloatingHorizontalPlacement = SlockFloatingHorizontalPlacement.Anchor,
    // #223: when true, the popover flips to the left of the anchor if
    // there is no room on the right. Used by SlockBaseMenuOverlay so
    // long-press menus don't run off the screen's right edge.
    horizontalAutoFlip: Boolean = false,
    dismissActivationDelayMillis: Long = SlockFloatingOverlayDismissActivationDelayMillis,
    outsidePointerMode: SlockFloatingOverlayOutsidePointerMode =
        SlockFloatingOverlayOutsidePointerMode.Dismiss,
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
internal fun SlockFloatingOverlayHost(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    // task #436 follow-up: the activation delay exists for LONG-PRESS-opened menus,
    // where the press release lands after the host has composed and would instantly
    // dismiss. CLICK-opened popovers complete their opening gesture before the host
    // mounts, so they pass 0 — with a nonzero delay, rapid consecutive taps either
    // land on the trigger pre-mount (toggling) or inside the window (eaten), which
    // reads as "the popover won't close".
    dismissActivationDelayMillis: Long = SlockFloatingOverlayDismissActivationDelayMillis,
    // Suggestion/autocomplete surfaces remain part of the focused editor experience. They use a
    // pass-through host so the editor toolbar stays interactive, while modal menus keep the
    // default full-screen dismiss barrier.
    outsidePointerMode: SlockFloatingOverlayOutsidePointerMode =
        SlockFloatingOverlayOutsidePointerMode.Dismiss,
    content: @Composable (SlockFloatingOverlayLayout) -> Unit
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
    var overlayLayout by remember { mutableStateOf(SlockFloatingOverlayLayout()) }
    // Defer the host's dismiss-clickable so the long-press release that
    // opens the menu does not immediately propagate to the host's
    // onDismiss and close the menu before the user sees anything. The
    // 150ms window covers the standard Android long-press threshold
    // (~500ms) minus the time to compose + render the menu. The menu's
    // own content Column already has a no-op clickable
    // (slockFloatingContentClickTarget) so clicks on the menu do not
    // bubble up; the host only needs to dismiss on clicks outside the
    // menu.
    var dismissClickableActive by remember { mutableStateOf(dismissActivationDelayMillis <= 0) }
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    SlockBackHandler(enabled = true, label = "floating_overlay", onBack = currentOnDismiss)
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
            .zIndex(SlockFloatingOverlayHostZIndex)
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                overlayLayout = SlockFloatingOverlayLayout(
                    origin = coordinates.positionInRoot(),
                    size = Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())
                )
            },
        contentAlignment = Alignment.TopStart
    ) {
        if (outsidePointerMode.installsOutsideDismissBarrier) {
            SlockFloatingOverlayDismissBarrier(
                dismissClickableActive = dismissClickableActive,
                onDismiss = onDismiss
            )
        }
        content(overlayLayout)
    }
}

internal fun Modifier.slockFloatingContentClickTarget(): Modifier =
    slockFloatingContentPointerBarrier()
        .clickable(onClick = {})

private fun Modifier.slockFloatingContentPointerBarrier(): Modifier =
    slockNativeDispatchCapture()

/**
 * Full-screen native dispatch boundary for a modal surface. Keep it as a
 * sibling behind dialog content: applying capture to a parent container
 * prevents native siblings from seeing the gesture, but it also prevents
 * descendant Compose controls from receiving that gesture on Android.
 */
@Composable
internal fun SlockModalDismissBarrier(
    overlayAlpha: Float,
    onDismiss: () -> Unit,
    dismissEnabled: Boolean = true
) {
    val currentOnDismiss = rememberUpdatedState(onDismiss)
    // raft-ui dialogs dim with the `--layer-backdrop` token (black 65% light /
    // 60% dark); lighter, sheet-style veils keep their own alpha.
    val veil =
        if (slockRaftActive() && overlayAlpha >= 0.5f) {
            SlockColor.Backdrop
        } else {
            SlockColor.TextBlack.copy(alpha = overlayAlpha)
        }
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(veil)
                .slockNativeDispatchCapture()
                .slockModalDismissPointerBarrier(dismissEnabled) {
                    currentOnDismiss.value.invoke()
                }
    )
}

@Composable
private fun SlockFloatingOverlayDismissBarrier(
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
            .background(SlockColor.SurfaceWhite.copy(alpha = SlockFloatingOverlayTouchGuardAlpha))
            .slockNativeDispatchCapture()
            .pointerInput(dismissClickableActive) {
                awaitEachGesture {
                    var moved = false
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { change ->
                            moved = moved || change.positionChanged()
                            change.consume()
                        }
                        if (event.changes.all { !it.pressed }) break
                    }
                    if (dismissClickableActive && !moved) {
                        currentOnDismiss()
                    }
                }
            }
    )
}

private const val SlockFloatingOverlayTouchGuardAlpha = 0.01f

internal const val SlockFloatingOverlayDismissActivationDelayMillis = 150L
internal const val SlockFloatingOverlayClickTriggeredDismissDelayMillis = 0L

internal fun SlockFloatingOverlayLayout.positionContent(
    anchor: Offset,
    contentSize: Size,
    viewportGap: Float = SlockFloatingDefaultViewportGap,
    horizontalOffset: Float = 0f,
    verticalOffset: Float = SlockFloatingDefaultVerticalOffset,
    verticalOffsetAbove: Float = verticalOffset,
    verticalPlacement: SlockFloatingVerticalPlacement = SlockFloatingVerticalPlacement.Below,
    horizontalPlacement: SlockFloatingHorizontalPlacement = SlockFloatingHorizontalPlacement.Anchor,
    // #223: when true, place content to the left of the anchor (anchor
    // is treated as the content's right edge) when there is no room on
    // the right. Mirrors React placeSidebarContextMenu.ts placeMenuAxis
    // behavior so the menu does not run off the right edge.
    // #327: improved to pick the side that shows more visible content
    // when both left and right placements would overflow.
    horizontalAutoFlip: Boolean = false
): Offset? {
    if (!isReady || !anchor.isSlockUsableAnchor() || contentSize.width <= 0f || contentSize.height <= 0f) {
        return null
    }
    val localAnchor = Offset(anchor.x - origin.x, anchor.y - origin.y)
    val x = slockHorizontalPosition(
        localAnchorX = localAnchor.x,
        contentWidth = contentSize.width,
        viewportWidth = size.width,
        horizontalOffset = horizontalOffset,
        viewportGap = viewportGap,
        horizontalPlacement = horizontalPlacement,
        autoFlip = horizontalAutoFlip
    )
    val y = slockVerticalPosition(
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
 * [SlockFloatingOverlayLayout.origin]. When [horizontalPlacement] is [SlockFloatingHorizontalPlacement.Center],
 * the overlay is placed horizontally centered within the viewport bounds plus [horizontalOffset],
 * clamped to [[viewportGap], [maxX]].
 * When [autoFlip] is true and the
 * preferred right-of-anchor placement overflows the viewport, the overlay
 * is placed to the left of the anchor. When both sides overflow the
 * viewport the side that shows more of the content is chosen (#327).
 */
internal fun slockHorizontalPosition(
    localAnchorX: Float,
    contentWidth: Float,
    viewportWidth: Float,
    horizontalOffset: Float = 0f,
    viewportGap: Float = SlockFloatingDefaultViewportGap,
    horizontalPlacement: SlockFloatingHorizontalPlacement = SlockFloatingHorizontalPlacement.Anchor,
    autoFlip: Boolean = false
): Float {
    val maxX = (viewportWidth - contentWidth - viewportGap).coerceAtLeast(viewportGap)
    if (horizontalPlacement == SlockFloatingHorizontalPlacement.Center) {
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
 * [SlockFloatingOverlayLayout.origin].  When [verticalPlacement] is [Auto]
 * and the preferred below-anchor placement overflows the viewport, the
 * overlay is placed above the anchor.  When both above and below overflow
 * the side that shows more of the content is chosen (#327).
 */
internal fun slockVerticalPosition(
    localAnchorY: Float,
    contentHeight: Float,
    viewportHeight: Float,
    verticalOffset: Float = SlockFloatingDefaultVerticalOffset,
    verticalOffsetAbove: Float = verticalOffset,
    viewportGap: Float = SlockFloatingDefaultViewportGap,
    verticalPlacement: SlockFloatingVerticalPlacement = SlockFloatingVerticalPlacement.Below
): Float {
    val maxY = (viewportHeight - contentHeight - viewportGap).coerceAtLeast(viewportGap)
    val aboveY = localAnchorY - contentHeight - verticalOffsetAbove
    val belowY = localAnchorY + verticalOffset
    if (verticalPlacement == SlockFloatingVerticalPlacement.Below) {
        return belowY.coerceIn(viewportGap, maxY)
    }
    if (verticalPlacement == SlockFloatingVerticalPlacement.Above) {
        return aboveY.coerceIn(viewportGap, maxY)
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

internal fun Offset.isSlockUsableAnchor(): Boolean =
    x != 0f || y != 0f

internal const val SlockFloatingDefaultViewportGap = 8f
internal const val SlockFloatingDefaultVerticalOffset = 8f
