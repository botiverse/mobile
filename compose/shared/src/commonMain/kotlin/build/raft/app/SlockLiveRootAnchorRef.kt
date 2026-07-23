package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.geometry.Size
import com.tencent.kuikly.compose.ui.layout.LayoutCoordinates
import com.tencent.kuikly.compose.ui.layout.onGloballyPositioned
import com.tencent.kuikly.compose.ui.unit.IntOffset
import com.tencent.kuikly.compose.ui.unit.IntSize

internal sealed interface SlockPopoverLocalPoint {
    fun resolve(size: IntSize): Offset

    data class Exact(val offset: Offset) : SlockPopoverLocalPoint {
        override fun resolve(size: IntSize): Offset = offset
    }

    data object BottomStart : SlockPopoverLocalPoint {
        override fun resolve(size: IntSize): Offset = Offset(x = 0f, y = size.height.toFloat())
    }

    data object BottomEnd : SlockPopoverLocalPoint {
        override fun resolve(size: IntSize): Offset =
            Offset(x = size.width.toFloat(), y = size.height.toFloat())
    }

    data class BottomAtX(val x: Float) : SlockPopoverLocalPoint {
        override fun resolve(size: IntSize): Offset = Offset(x = x, y = size.height.toFloat())
    }
}

/**
 * One gesture-bound popover anchor. The root pixels stay opaque to feature producers; only the
 * shared overlay positioning layer may unwrap them.
 */
internal class SlockPopoverAnchor private constructor(
    private val ownerIdentity: Any,
    private val rootOffset: Offset
) {
    fun belongsTo(expectedIdentity: Any): Boolean = ownerIdentity == expectedIdentity

    @Composable
    internal fun renderInOverlay(
        contentSize: Size,
        onDismiss: () -> Unit,
        modifier: Modifier,
        viewportGap: Float,
        horizontalOffset: Float,
        verticalOffset: Float,
        verticalOffsetAbove: Float,
        verticalPlacement: SlockFloatingVerticalPlacement,
        horizontalAutoFlip: Boolean,
        content: @Composable () -> Unit
    ) {
        SlockFloatingOverlayHost(onDismiss = onDismiss, modifier = modifier) { overlayLayout ->
            val popoverOffset =
                overlayLayout.positionContent(
                    anchor = rootOffset,
                    contentSize = contentSize,
                    viewportGap = viewportGap,
                    horizontalOffset = horizontalOffset,
                    verticalOffset = verticalOffset,
                    verticalOffsetAbove = verticalOffsetAbove,
                    verticalPlacement = verticalPlacement,
                    horizontalAutoFlip = horizontalAutoFlip
                ) ?: return@SlockFloatingOverlayHost
            Box(modifier = Modifier.offset { IntOffset(popoverOffset.x.toInt(), popoverOffset.y.toInt()) }) {
                content()
            }
        }
    }

    internal class LiveRef(
        private val identity: Any
    ) {
        private var coordinates: LayoutCoordinates? = null
        private var captureLease: Long = 0L
        private val reuseInvalidatedLeases = mutableSetOf<Long>()

        internal fun captureFromLayoutCoordinates(next: LayoutCoordinates) {
            coordinates
                ?.takeIf { current -> current !== next }
                ?.let { previous -> SlockLiveRootAnchorCaptureRegistry.release(previous, this) }
            SlockLiveRootAnchorCaptureRegistry.claim(next, this)
            coordinates = next
        }

        fun invalidate() {
            coordinates?.let { current -> SlockLiveRootAnchorCaptureRegistry.release(current, this) }
            coordinates = null
            captureLease += 1L
        }

        internal fun invalidateReusedCapture(coordinates: LayoutCoordinates) {
            if (this.coordinates === coordinates) {
                this.coordinates = null
                reuseInvalidatedLeases += captureLease
                captureLease += 1L
            }
        }

        internal fun wasCaptureLeaseInvalidatedByReuse(lease: Long): Boolean = lease in reuseInvalidatedLeases

        fun resolver(localPoint: SlockPopoverLocalPoint): SlockPopoverAnchorResolver =
            SlockPopoverAnchorResolver(
                identity = identity,
                anchorRef = this,
                localPoint = localPoint,
                captureLease = captureLease
            )

        fun resolve(localPoint: SlockPopoverLocalPoint): SlockPopoverAnchor? =
            resolve(localPoint = localPoint, expectedCaptureLease = captureLease)

        internal fun resolve(
            localPoint: SlockPopoverLocalPoint,
            expectedCaptureLease: Long
        ): SlockPopoverAnchor? {
            if (expectedCaptureLease != captureLease) return null
            val current = coordinates?.takeIf { it.isAttached } ?: return null
            val rootOffset = current.localToRoot(localPoint.resolve(current.size))
            return SlockPopoverAnchor(ownerIdentity = identity, rootOffset = rootOffset)
        }
    }
}

internal typealias SlockLiveRootAnchorRef = SlockPopoverAnchor.LiveRef

/**
 * Focus dismissal also needs live local-to-root conversion, but it must not reuse or expose the
 * typed popover-anchor capability. This small focus-only handle keeps that conversion centralized
 * with the live-coordinate implementation while returning no persistent popup anchor.
 */
internal class SlockInputFocusRootAnchor {
    private var coordinates: LayoutCoordinates? = null

    fun update(next: LayoutCoordinates) {
        coordinates = next
    }

    fun resolve(localPosition: Offset): Offset? =
        coordinates
            ?.takeIf { it.isAttached }
            ?.localToRoot(localPosition)
}

private object SlockLiveRootAnchorCaptureRegistry {
    private data class Entry(
        val coordinates: LayoutCoordinates,
        val owner: SlockLiveRootAnchorRef
    )

    // Capture and disposal callbacks are confined to the Kuikly composition thread. The registry
    // is referential on purpose: recycled lazy slots can reuse one LayoutCoordinates instance for
    // a different semantic owner before the previous composition reaches disposal.
    private val entries = mutableListOf<Entry>()

    fun claim(coordinates: LayoutCoordinates, owner: SlockLiveRootAnchorRef) {
        val existingIndex = entries.indexOfFirst { entry -> entry.coordinates === coordinates }
        if (existingIndex >= 0) {
            val previous = entries[existingIndex].owner
            if (previous !== owner) previous.invalidateReusedCapture(coordinates)
            entries[existingIndex] = Entry(coordinates, owner)
        } else {
            entries += Entry(coordinates, owner)
        }
    }

    fun release(coordinates: LayoutCoordinates, owner: SlockLiveRootAnchorRef) {
        entries.removeAll { entry -> entry.coordinates === coordinates && entry.owner === owner }
    }
}

internal class SlockPopoverAnchorResolver internal constructor(
    val identity: Any,
    private val anchorRef: SlockLiveRootAnchorRef,
    private val localPoint: SlockPopoverLocalPoint,
    private val captureLease: Long
) {
    fun resolve(): SlockPopoverAnchor? = anchorRef.resolve(localPoint, captureLease)

    fun wasInvalidatedByCaptureReuse(): Boolean = anchorRef.wasCaptureLeaseInvalidatedByReuse(captureLease)
}

/**
 * Retains a live layout handle for controls that open a root-anchored popover.
 *
 * A root [Offset] captured during layout becomes stale when an ancestor is placed again (for
 * example while a LazyColumn scrolls or when content is inserted above the control). Kuikly keeps
 * the same [LayoutCoordinates] backed by its current coordinator, so resolving through it at the
 * actual gesture observes the latest placement and transforms.
 *
 * Keep this reference composition-local. Resolve synchronously from the gesture callback and pass
 * only the resulting [SlockPopoverAnchor] into modal state. Programmatic opens may retain a typed
 * [SlockPopoverAnchorResolver], which still defers root conversion until open. Detached coordinates
 * deliberately fail closed.
 */
@Composable
internal fun Modifier.slockCaptureLiveRootAnchor(
    anchorRef: SlockLiveRootAnchorRef,
    enabled: Boolean = true,
    onCaptured: ((IntSize) -> Unit)? = null
): Modifier {
    val currentOnCaptured by rememberUpdatedState(onCaptured)
    DisposableEffect(anchorRef, enabled) {
        onDispose { anchorRef.invalidate() }
    }
    return if (enabled) {
        onGloballyPositioned { coordinates ->
            anchorRef.captureFromLayoutCoordinates(coordinates)
            currentOnCaptured?.invoke(coordinates.size)
        }
    } else {
        this
    }
}
