package ai.slock.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
            verticalPlacement = verticalPlacement
        ) ?: return@SlockFloatingOverlayHost
        val position = SlockAnchoredPopoverPosition(
            offset = popoverOffset,
            localAnchor = Offset(anchor.x - overlayLayout.origin.x, anchor.y - overlayLayout.origin.y),
            overlayLayout = overlayLayout
        )
        content(
            Modifier.offset(x = popoverOffset.x, y = popoverOffset.y),
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
    val density = LocalDensity.current
    var overlayLayout by remember { mutableStateOf(SlockFloatingOverlayLayout()) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                overlayLayout = SlockFloatingOverlayLayout(
                    origin = coordinates.positionInRoot().toSlockDpOffset(density),
                    size = coordinates.size.toSlockDpSize(density)
                )
            }
            .clickable(onClick = onDismiss),
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
    verticalPlacement: SlockFloatingVerticalPlacement = SlockFloatingVerticalPlacement.Below
): Offset? {
    if (!isReady || !anchor.isSlockUsableAnchor() || contentSize.width <= 0f || contentSize.height <= 0f) {
        return null
    }
    val localAnchor = Offset(anchor.x - origin.x, anchor.y - origin.y)
    val maxX = (size.width - contentSize.width - viewportGap).coerceAtLeast(viewportGap)
    val x = (localAnchor.x + horizontalOffset).coerceIn(viewportGap, maxX)
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
