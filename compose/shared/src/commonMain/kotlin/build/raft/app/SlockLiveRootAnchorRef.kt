package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.ui.ExperimentalComposeUiApi
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.geometry.Size
import com.tencent.kuikly.compose.ui.layout.LayoutCoordinates
import com.tencent.kuikly.compose.ui.node.GlobalPositionAwareModifierNode
import com.tencent.kuikly.compose.ui.node.ModifierNodeElement
import com.tencent.kuikly.compose.ui.node.requireLayoutCoordinates
import com.tencent.kuikly.compose.ui.platform.InspectorInfo
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
        horizontalPlacement: SlockFloatingHorizontalPlacement = SlockFloatingHorizontalPlacement.Anchor,
        horizontalAutoFlip: Boolean,
        dismissActivationDelayMillis: Long,
        outsidePointerMode: SlockFloatingOverlayOutsidePointerMode,
        content: @Composable () -> Unit
    ) {
        SlockFloatingOverlayHost(
            onDismiss = onDismiss,
            modifier = modifier,
            dismissActivationDelayMillis = dismissActivationDelayMillis,
            outsidePointerMode = outsidePointerMode
        ) { overlayLayout ->
            val popoverOffset =
                overlayLayout.positionContent(
                    anchor = rootOffset,
                    contentSize = contentSize,
                    viewportGap = viewportGap,
                    horizontalOffset = horizontalOffset,
                    verticalOffset = verticalOffset,
                    verticalOffsetAbove = verticalOffsetAbove,
                    verticalPlacement = verticalPlacement,
                    horizontalPlacement = horizontalPlacement,
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
        private data class PlacementSnapshot(
            val size: IntSize,
            val topLeft: Offset,
            val topRight: Offset,
            val bottomLeft: Offset,
            val bottomRight: Offset
        )

        private var coordinates: LayoutCoordinates? = null
        /**
         * Task #181 follow-up: the child-page origin this anchor resolves in. Held as the observable
         * state (not an Offset snapshot) so `resolve()` reads the CURRENT value at open time.
         */
        private var childPageOriginState: SlockChildPageOriginState? = null
        private var captureOwner: Any? = null
        private var captureLease: Long = 0L
        private var placementRevisionState by mutableStateOf(0L)
        private var placementObserverCount: Int = 0
        private var placementSnapshot: PlacementSnapshot? = null
        private val reuseInvalidatedLeases = mutableSetOf<Long>()

        internal val placementRevision: Long
            get() = placementRevisionState

        internal fun attachChildPageOrigin(state: SlockChildPageOriginState?) {
            if (childPageOriginState !== state) childPageOriginState = state
        }

        internal fun captureFromLayoutCoordinates(
            next: LayoutCoordinates,
            owner: Any? = null
        ) {
            val previousCoordinates = coordinates
            coordinates
                ?.takeIf { current -> current !== next }
                ?.let { previous -> SlockLiveRootAnchorCaptureRegistry.release(previous, this) }
            SlockLiveRootAnchorCaptureRegistry.claim(next, this)
            coordinates = next
            // A helper-level refresh of the same live coordinates must not erase the production
            // node lease established by onAttach. A different coordinate with no owner remains an
            // explicitly unowned capture, preserving the legacy helper contract.
            if (owner != null || previousCoordinates !== next) captureOwner = owner
            publishPlacementIfObserved(next)
        }

        fun invalidate() {
            coordinates?.let { current -> SlockLiveRootAnchorCaptureRegistry.release(current, this) }
            coordinates = null
            captureOwner = null
            captureLease += 1L
            placementSnapshot = null
            placementRevisionState += 1L
        }

        internal fun invalidateCaptureOwner(owner: Any) {
            if (captureOwner === owner) invalidate()
        }

        internal fun invalidateReusedCapture(coordinates: LayoutCoordinates) {
            if (this.coordinates === coordinates) {
                this.coordinates = null
                captureOwner = null
                reuseInvalidatedLeases += captureLease
                captureLease += 1L
                placementSnapshot = null
                placementRevisionState += 1L
            }
        }

        internal fun beginObservingPlacement() {
            placementObserverCount += 1
            if (placementObserverCount == 1) placementSnapshot = null
        }

        internal fun endObservingPlacement() {
            placementObserverCount = (placementObserverCount - 1).coerceAtLeast(0)
            if (placementObserverCount == 0) placementSnapshot = null
        }

        private fun publishPlacementIfObserved(next: LayoutCoordinates) {
            if (placementObserverCount <= 0 || !next.isAttached) return
            val width = next.size.width.toFloat()
            val height = next.size.height.toFloat()
            val nextSnapshot =
                PlacementSnapshot(
                    size = next.size,
                    topLeft = next.localToRoot(Offset.Zero),
                    topRight = next.localToRoot(Offset(x = width, y = 0f)),
                    bottomLeft = next.localToRoot(Offset(x = 0f, y = height)),
                    bottomRight = next.localToRoot(Offset(x = width, y = height))
                )
            if (placementSnapshot != nextSnapshot) {
                placementSnapshot = nextSnapshot
                placementRevisionState += 1L
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
            expectedCaptureLease: Long,
            expectedPlacementRevision: Long? = null
        ): SlockPopoverAnchor? {
            if (expectedCaptureLease != captureLease) return null
            if (expectedPlacementRevision != null && expectedPlacementRevision != placementRevisionState) return null
            val current = coordinates?.takeIf { it.isAttached } ?: return null
            // `localToRoot` is relative to this page's own root. Inside a Kuikly child page that is
            // NOT the window, so add the host-provided origin. Read here (open time), never cached,
            // so rotation / pane resize / fold are picked up. Zero outside a child page.
            val pageOrigin = childPageOriginState?.windowOrigin ?: Offset.Zero
            val rootOffset = current.localToRoot(localPoint.resolve(current.size)) + pageOrigin
            return SlockPopoverAnchor(ownerIdentity = identity, rootOffset = rootOffset)
        }
    }
}

/**
 * The window-space origin of the Kuikly page the current composition belongs to.
 *
 * Task #181 follow-up (Argus 2026-10-17): a `SlockKuiklyChildPage` is its own Kuikly page, so
 * `positionInRoot()` / `localToRoot()` inside it are relative to THAT page's root, not the window.
 * Every overlay anchored through [SlockPopoverAnchor.LiveRef] therefore landed offset by the host
 * pane's origin -- in the dual-pane detail/side column that meant a menu drawn over the list panel.
 *
 * The host that embeds a child page measures its own window position and provides it here; a
 * single-column or full-screen page provides `Offset.Zero`, which is exactly the old behaviour.
 *
 * Deliberately a [SlockChildPageOriginState] (observable) rather than a plain Offset: it is read
 * inside `resolve()` at the moment an overlay opens, so rotation, a pane resize or a fold is picked
 * up without caching a stale value.
 */
internal class SlockChildPageOriginState(origin: Offset = Offset.Zero) {
    var origin: Offset by mutableStateOf(origin)
        internal set

    /**
     * The enclosing child page's origin, when this page is itself nested. Child pages nest for real
     * -- the AppShell tabs are child pages and the detail/thread columns are child pages INSIDE
     * them -- so the window offset of a nested page is its own `positionInRoot()` relative to the
     * parent page PLUS the parent's origin. Kept as a live reference (not a summed snapshot) so an
     * outer resize still propagates to the inner conversion.
     */
    var parentOrigin: SlockChildPageOriginState? = null
        internal set

    /** Window-space origin of this page: own position within its parent plus every ancestor. */
    val windowOrigin: Offset
        get() = origin + (parentOrigin?.windowOrigin ?: Offset.Zero)

    internal fun updateOrigin(next: Offset) {
        if (next != origin) origin = next
    }

    internal fun linkParentOrigin(next: SlockChildPageOriginState?) {
        if (next !== this && parentOrigin !== next) parentOrigin = next
    }
}

/**
 * Reads the enclosing child page's origin for [anchorRef] and keeps the ref bound to it.
 *
 * Split out because `CompositionLocal.current` is only readable in a @Composable scope, while the
 * modifier factory itself is not composable. Returns the state so the capture element can also
 * carry it through modifier updates.
 */
@Composable
internal fun rememberSlockChildPageOriginBinding(
    anchorRef: SlockLiveRootAnchorRef
): SlockChildPageOriginState {
    val state = LocalSlockChildPageOrigin.current
    SideEffect { anchorRef.attachChildPageOrigin(state) }
    return state
}

internal val LocalSlockChildPageOrigin =
    androidx.compose.runtime.staticCompositionLocalOf { SlockChildPageOriginState() }

internal typealias SlockLiveRootAnchorRef = SlockPopoverAnchor.LiveRef

internal fun refreshSlockLiveRootAnchorCaptureOwner(
    currentOwner: SlockLiveRootAnchorRef,
    nextOwner: SlockLiveRootAnchorRef,
    coordinates: LayoutCoordinates?,
    captureOwner: Any? = null
) {
    if (currentOwner !== nextOwner) {
        if (captureOwner == null) {
            currentOwner.invalidate()
        } else {
            currentOwner.invalidateCaptureOwner(captureOwner)
        }
    }
    coordinates
        ?.takeIf { it.isAttached }
        ?.let { nextOwner.captureFromLayoutCoordinates(it, captureOwner) }
}

internal fun recaptureSlockLiveRootAnchorAfterAttach(
    owner: SlockLiveRootAnchorRef,
    coordinates: LayoutCoordinates,
    captureOwner: Any? = null
) {
    if (coordinates.isAttached) owner.captureFromLayoutCoordinates(coordinates, captureOwner)
}

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
    internal val placementRevision: Long
        get() = anchorRef.placementRevision

    fun resolve(): SlockPopoverAnchor? = anchorRef.resolve(localPoint, captureLease)

    internal fun resolveAtPlacementRevision(expectedPlacementRevision: Long): SlockPopoverAnchor? =
        anchorRef.resolve(
            localPoint = localPoint,
            expectedCaptureLease = captureLease,
            expectedPlacementRevision = expectedPlacementRevision
        )

    internal fun beginObservingPlacement() = anchorRef.beginObservingPlacement()

    internal fun endObservingPlacement() = anchorRef.endObservingPlacement()

    fun wasInvalidatedByCaptureReuse(): Boolean = anchorRef.wasCaptureLeaseInvalidatedByReuse(captureLease)
}

/**
 * Resolves a retained anchor while observing layout publications from its live coordinates.
 *
 * Gesture-opened popovers normally resolve once at the gesture. A portal that remains open across
 * IME or window relayout must call this from composition so the same portal instance re-resolves on
 * the next frame instead of retaining the root offset from its opening frame.
 */
@Composable
internal fun SlockPopoverAnchorResolver.resolveCurrentPlacement(): SlockPopoverAnchor? {
    DisposableEffect(this) {
        beginObservingPlacement()
        onDispose { endObservingPlacement() }
    }
    val observedPlacementRevision = placementRevision
    return resolveAtPlacementRevision(observedPlacementRevision)
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
internal fun Modifier.slockCaptureLiveRootAnchor(
    anchorRef: SlockLiveRootAnchorRef,
    enabled: Boolean = true,
    onCaptured: ((IntSize) -> Unit)? = null,
    /**
     * task #181 follow-up: the enclosing child page's origin. Feature code should not pass this --
     * use the @Composable overload, which fills it from [LocalSlockChildPageOrigin]. It stays an
     * explicit parameter so a build of this modifier outside composition (tests that drive Kuikly's
     * LayoutNode directly) still works.
     */
    childPageOrigin: SlockChildPageOriginState? = null
): Modifier {
    anchorRef.attachChildPageOrigin(childPageOrigin)
    return if (enabled) {
        this then SlockLiveRootAnchorCaptureElement(anchorRef, onCaptured, childPageOrigin)
    } else {
        this
    }
}

/**
 * Popover anchoring for feature code: binds the enclosing child page's origin so `resolve()` can
 * convert this page's root coordinates into window coordinates.
 */
@Composable
internal fun Modifier.slockCaptureLiveRootAnchor(
    anchorRef: SlockLiveRootAnchorRef,
    enabled: Boolean = true,
    onCaptured: ((IntSize) -> Unit)? = null
): Modifier {
    val childPageOrigin = rememberSlockChildPageOriginBinding(anchorRef)
    return slockCaptureLiveRootAnchor(
        anchorRef = anchorRef,
        enabled = enabled,
        onCaptured = onCaptured,
        childPageOrigin = childPageOrigin
    )
}

private data class SlockLiveRootAnchorCaptureElement(
    val anchorRef: SlockLiveRootAnchorRef,
    val onCaptured: ((IntSize) -> Unit)?,
    val childPageOrigin: SlockChildPageOriginState?
) : ModifierNodeElement<SlockLiveRootAnchorCaptureNode>() {
    override fun create(): SlockLiveRootAnchorCaptureNode =
        SlockLiveRootAnchorCaptureNode(anchorRef, onCaptured, childPageOrigin)

    override fun update(node: SlockLiveRootAnchorCaptureNode) {
        node.update(anchorRef, onCaptured, childPageOrigin)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "slockCaptureLiveRootAnchor"
        properties["anchorRef"] = anchorRef
    }
}

private class SlockLiveRootAnchorCaptureNode(
    private var anchorRef: SlockLiveRootAnchorRef,
    private var onCaptured: ((IntSize) -> Unit)?,
    private var childPageOrigin: SlockChildPageOriginState?
) : Modifier.Node(), GlobalPositionAwareModifierNode {
    @OptIn(ExperimentalComposeUiApi::class)
    override fun onAttach() {
        // Lazy reuse detaches and later reattaches the same Modifier.Node. Kuikly does not mark
        // an existing GlobalPositionAware node as inserted/updated during that reactivation, so
        // another positioned callback is not guaranteed when the slot keeps its placement. Wait
        // until modifier updates for this apply pass have settled, then restore the current owner
        // from the node's live coordinator before its reattached pointer detector can resolve it.
        sideEffect {
            if (isAttached) {
                val coordinates = requireLayoutCoordinates()
                recaptureSlockLiveRootAnchorAfterAttach(anchorRef, coordinates, this)
                onCaptured?.invoke(coordinates.size)
            }
        }
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        capture(coordinates)
    }

    fun update(
        nextAnchorRef: SlockLiveRootAnchorRef,
        nextOnCaptured: ((IntSize) -> Unit)?,
        nextChildPageOrigin: SlockChildPageOriginState?
    ) {
        val currentAnchorRef = anchorRef
        val ownerChanged = currentAnchorRef !== nextAnchorRef
        anchorRef = nextAnchorRef
        onCaptured = nextOnCaptured
        childPageOrigin = nextChildPageOrigin
        // task #181 follow-up: keep the ref bound after a reuse/update, otherwise a recycled row
        // would resolve against a stale origin.
        anchorRef.attachChildPageOrigin(childPageOrigin)

        // Kuikly may update a reused lazy-row Modifier.Node without scheduling another global
        // positioning callback when the recycled slot stays at the same coordinates. Capture the
        // node's already-attached coordinator during the update so the new semantic row cannot
        // admit a gesture while its live anchor is still empty.
        if (ownerChanged && isAttached) {
            val coordinates = requireLayoutCoordinates()
            refreshSlockLiveRootAnchorCaptureOwner(currentAnchorRef, nextAnchorRef, coordinates, this)
            onCaptured?.invoke(coordinates.size)
        } else if (ownerChanged) {
            currentAnchorRef.invalidateCaptureOwner(this)
        }
    }

    override fun onDetach() {
        // IME/lazy relayout may attach a replacement node for the same semantic message before
        // this node's delayed detach. Only the node holding the current capture lease may revoke
        // it; an older node must not clear the replacement's live coordinates.
        anchorRef.invalidateCaptureOwner(this)
    }

    private fun capture(coordinates: LayoutCoordinates) {
        anchorRef.captureFromLayoutCoordinates(coordinates, this)
        onCaptured?.invoke(coordinates.size)
    }
}
