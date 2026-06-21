package ai.slock.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.geometry.Size
import com.tencent.kuikly.compose.ui.layout.onGloballyPositioned
import com.tencent.kuikly.compose.ui.layout.positionInRoot
import com.tencent.kuikly.compose.ui.platform.LocalDensity
import com.tencent.kuikly.compose.ui.unit.Density
import com.tencent.kuikly.compose.ui.unit.IntOffset
import com.tencent.kuikly.compose.ui.unit.IntSize

internal data class SlockFloatingOverlayLayout(
    val origin: Offset = Offset.Zero,
    val size: Size = Size.Zero
) {
    val isReady: Boolean = size.width > 0f && size.height > 0f
}

internal enum class SlockFloatingVerticalPlacement {
    Below,
    Auto
}

internal data class SlockAnchoredPopoverPosition(
    val offset: Offset,
    val localAnchor: Offset,
    val overlayLayout: SlockFloatingOverlayLayout
)

@Composable
internal fun SlockAnchoredPopover(
    anchor: Offset,
    contentSize: Size,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    viewportGap: Float = SlockFloatingDefaultViewportGap,
    horizontalOffset: Float = 0f,
    verticalOffset: Float = SlockFloatingDefaultVerticalOffset,
    verticalPlacement: SlockFloatingVerticalPlacement = SlockFloatingVerticalPlacement.Below,
    // #223: when true, the popover flips to the left of the anchor if
    // there is no room on the right. Used by SlockBaseMenuOverlay so
    // long-press menus don't run off the screen's right edge.
    horizontalAutoFlip: Boolean = false,
    content: @Composable (Modifier, SlockAnchoredPopoverPosition) -> Unit
) {
    SlockFloatingOverlayHost(
        onDismiss = onDismiss,
        modifier = modifier
    ) { overlayLayout ->
        val popoverOffset = overlayLayout.positionContent(
            anchor = anchor,
            contentSize = contentSize,
            viewportGap = viewportGap,
            horizontalOffset = horizontalOffset,
            verticalOffset = verticalOffset,
            verticalPlacement = verticalPlacement,
            horizontalAutoFlip = horizontalAutoFlip
        ) ?: return@SlockFloatingOverlayHost
        val position = SlockAnchoredPopoverPosition(
            offset = popoverOffset,
            localAnchor = Offset(anchor.x - overlayLayout.origin.x, anchor.y - overlayLayout.origin.y),
            overlayLayout = overlayLayout
        )
        // Use IntOffset to keep units in px (Modifier.offset(Dp, Dp) does
        // px→dp→px round trip which loses the integer offset). The host
        // fills its parent and the popoverOffset is already in px.
        content(
            Modifier.offset { IntOffset(popoverOffset.x.toInt(), popoverOffset.y.toInt()) },
            position
        )
    }
}

@Composable
internal fun SlockFloatingOverlayHost(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
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
    var dismissClickableActive by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(150)
        dismissClickableActive = true
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                overlayLayout = SlockFloatingOverlayLayout(
                    origin = coordinates.positionInRoot(),
                    size = Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())
                )
            }
            .then(if (dismissClickableActive) Modifier.clickable(onClick = onDismiss) else Modifier),
        contentAlignment = Alignment.TopStart
    ) {
        content(overlayLayout)
    }
}

internal fun Modifier.slockFloatingContentClickTarget(): Modifier =
    clickable(onClick = {})

internal fun SlockFloatingOverlayLayout.positionContent(
    anchor: Offset,
    contentSize: Size,
    viewportGap: Float = SlockFloatingDefaultViewportGap,
    horizontalOffset: Float = 0f,
    verticalOffset: Float = SlockFloatingDefaultVerticalOffset,
    verticalPlacement: SlockFloatingVerticalPlacement = SlockFloatingVerticalPlacement.Below,
    // #223: when true, place content to the left of the anchor (anchor
    // is treated as the content's right edge) when there is no room on
    // the right. Mirrors React placeSidebarContextMenu.ts placeMenuAxis
    // behavior so the menu does not run off the right edge.
    horizontalAutoFlip: Boolean = false
): Offset? {
    if (!isReady || !anchor.isSlockUsableAnchor() || contentSize.width <= 0f || contentSize.height <= 0f) {
        slockLogDebug("SlockPinMenuDebug", "positionContent: skipped (isReady=" + isReady + " anchor=" + anchor + " contentSize=" + contentSize + ")")
        return null
    }
    val localAnchor = Offset(anchor.x - origin.x, anchor.y - origin.y)
    slockLogDebug("SlockPinMenuDebug", "positionContent: anchor=" + anchor + " origin=" + origin + " localAnchor=" + localAnchor + " size=" + size + " contentSize=" + contentSize + " hOffset=" + horizontalOffset + " vOffset=" + verticalOffset + " vPlacement=" + verticalPlacement + " hAutoFlip=" + horizontalAutoFlip)
    val maxX = (size.width - contentSize.width - viewportGap).coerceAtLeast(viewportGap)
    val x = if (horizontalAutoFlip) {
        val preferredRightX = localAnchor.x + horizontalOffset
        val preferredLeftX = localAnchor.x - contentSize.width + horizontalOffset
        if (preferredRightX + contentSize.width > size.width - viewportGap) {
            if (preferredLeftX >= viewportGap) {
                preferredLeftX.coerceAtMost(maxX)
            } else {
                preferredRightX.coerceIn(viewportGap, maxX)
            }
        } else {
            preferredRightX.coerceIn(viewportGap, maxX)
        }
    } else {
        (localAnchor.x + horizontalOffset).coerceIn(viewportGap, maxX)
    }
    val preferredBelowY = localAnchor.y + verticalOffset
    val maxY = (size.height - contentSize.height - viewportGap).coerceAtLeast(viewportGap)
    val y = when (verticalPlacement) {
        SlockFloatingVerticalPlacement.Below -> preferredBelowY.coerceIn(viewportGap, maxY)
        SlockFloatingVerticalPlacement.Auto -> {
            val preferredAboveY = localAnchor.y - contentSize.height - verticalOffset
            if (preferredBelowY + contentSize.height > size.height - viewportGap) {
                if (preferredAboveY >= viewportGap) preferredAboveY.coerceAtMost(maxY) else maxY
            } else {
                preferredBelowY.coerceIn(viewportGap, maxY)
            }
        }
    }
    slockLogDebug("SlockPinMenuDebug", "positionContent: result offset=(" + x + ", " + y + ")")
    return Offset(x, y)
}

internal fun Offset.isSlockUsableAnchor(): Boolean =
    x != 0f || y != 0f

internal fun Offset.toSlockDpOffset(density: Density): Offset =
    with(density) {
        Offset(x.toDp().value, y.toDp().value)
    }

internal fun IntSize.toSlockDpSize(density: Density): Size =
    with(density) {
        Size(width.toDp().value, height.toDp().value)
    }

internal const val SlockFloatingDefaultViewportGap = 8f
internal const val SlockFloatingDefaultVerticalOffset = 8f
