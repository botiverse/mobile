package build.raft.app

import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.layout.LayoutCoordinates
import com.tencent.kuikly.compose.ui.layout.onGloballyPositioned
import com.tencent.kuikly.compose.ui.unit.IntSize

/**
 * Retains a live layout handle for controls that open a root-anchored popover.
 *
 * A root [Offset] captured during layout becomes stale when an ancestor is placed again (for
 * example while a LazyColumn scrolls or when content is inserted above the control). Kuikly keeps
 * the same [LayoutCoordinates] backed by its current coordinator, so resolving through it at the
 * actual gesture observes the latest placement and transforms.
 *
 * Keep this reference composition-local. Resolve synchronously from the gesture callback and pass
 * only the resulting [Offset] into modal state. Detached coordinates deliberately fail closed.
 */
internal class SlockLiveRootAnchorRef {
    private var coordinates: LayoutCoordinates? = null

    fun update(next: LayoutCoordinates) {
        coordinates = next
    }

    fun resolve(localOffset: Offset): Offset? {
        val current = coordinates?.takeIf { it.isAttached } ?: return null
        return current.localToRoot(localOffset)
    }

    fun resolve(localOffsetForSize: (IntSize) -> Offset): Offset? {
        val current = coordinates?.takeIf { it.isAttached } ?: return null
        return current.localToRoot(localOffsetForSize(current.size))
    }
}

internal fun Modifier.slockCaptureLiveRootAnchor(
    anchorRef: SlockLiveRootAnchorRef,
    enabled: Boolean = true
): Modifier =
    if (enabled) {
        onGloballyPositioned(anchorRef::update)
    } else {
        this
    }
