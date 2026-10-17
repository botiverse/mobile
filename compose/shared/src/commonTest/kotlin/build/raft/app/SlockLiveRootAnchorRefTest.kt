package build.raft.app

import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.geometry.Rect
import com.tencent.kuikly.compose.ui.layout.AlignmentLine
import com.tencent.kuikly.compose.ui.layout.LayoutCoordinates
import com.tencent.kuikly.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SlockLiveRootAnchorRefTest {
    @Test
    fun retainedCoordinatesResolveCurrentPlacementWithoutAnotherUpdate() {
        val coordinates = FakeLayoutCoordinates(root = Offset(10f, 20f), scale = 2f)
        val anchorRef = SlockLiveRootAnchorRef(identity = "channel:alpha")

        anchorRef.captureFromLayoutCoordinates(coordinates)
        assertNotNull(anchorRef.resolve(SlockPopoverLocalPoint.Exact(Offset(4f, 6f))))
        assertEquals(Offset(4f, 6f), coordinates.lastLocalToRootInput)

        coordinates.root = Offset(100f, 200f)
        coordinates.scale = 3f

        assertNotNull(anchorRef.resolve(SlockPopoverLocalPoint.Exact(Offset(4f, 6f))))
        assertEquals(Offset(4f, 6f), coordinates.lastLocalToRootInput)
        assertEquals(2, coordinates.localToRootCalls)
    }

    @Test
    fun retainedResolverPublishesChangedGlobalPlacementWhileObserved() {
        val coordinates = FakeLayoutCoordinates(root = Offset(10f, 20f))
        val anchorRef = SlockLiveRootAnchorRef(identity = "composer")
        val resolver = anchorRef.resolver(SlockPopoverLocalPoint.Exact(Offset.Zero))

        assertEquals(0L, resolver.placementRevision)
        anchorRef.captureFromLayoutCoordinates(coordinates)
        assertEquals(0L, resolver.placementRevision)
        assertEquals(0, coordinates.localToRootCalls)

        resolver.beginObservingPlacement()
        anchorRef.captureFromLayoutCoordinates(coordinates)
        assertEquals(1L, resolver.placementRevision)
        assertEquals(4, coordinates.localToRootCalls)

        anchorRef.captureFromLayoutCoordinates(coordinates)
        assertEquals(1L, resolver.placementRevision)
        assertEquals(8, coordinates.localToRootCalls)

        coordinates.root = Offset(100f, 200f)
        anchorRef.captureFromLayoutCoordinates(coordinates)

        assertEquals(2L, resolver.placementRevision)
        assertEquals(12, coordinates.localToRootCalls)
        assertNotNull(resolver.resolveAtPlacementRevision(2L))
        assertEquals(13, coordinates.localToRootCalls)
        resolver.endObservingPlacement()

        coordinates.root = Offset(300f, 400f)
        anchorRef.captureFromLayoutCoordinates(coordinates)

        assertEquals(2L, resolver.placementRevision)
        assertEquals(13, coordinates.localToRootCalls)
    }

    @Test
    fun resolverRejectsAPlacementRevisionSupersededBeforeResolution() {
        val coordinates = FakeLayoutCoordinates(root = Offset(10f, 20f))
        val anchorRef = SlockLiveRootAnchorRef(identity = "composer")
        anchorRef.captureFromLayoutCoordinates(coordinates)
        val resolver = anchorRef.resolver(SlockPopoverLocalPoint.Exact(Offset.Zero))
        resolver.beginObservingPlacement()
        anchorRef.captureFromLayoutCoordinates(coordinates)
        val staleRevision = resolver.placementRevision

        coordinates.root = Offset(100f, 200f)
        anchorRef.captureFromLayoutCoordinates(coordinates)

        assertNull(resolver.resolveAtPlacementRevision(staleRevision))
        assertEquals(8, coordinates.localToRootCalls)
        assertNotNull(resolver.resolveAtPlacementRevision(resolver.placementRevision))
        assertEquals(9, coordinates.localToRootCalls)
        resolver.endObservingPlacement()
    }

    @Test
    fun sizeDerivedAnchorUsesCurrentSizeAndTransform() {
        val coordinates =
            FakeLayoutCoordinates(
                root = Offset(8f, 12f),
                measuredSize = IntSize(width = 30, height = 20),
                scale = 2f
            )
        val anchorRef = SlockLiveRootAnchorRef(identity = "sort")
        anchorRef.captureFromLayoutCoordinates(coordinates)

        coordinates.root = Offset(20f, 40f)
        coordinates.measuredSize = IntSize(width = 50, height = 35)

        assertNotNull(anchorRef.resolve(SlockPopoverLocalPoint.BottomEnd))
        assertEquals(Offset(50f, 35f), coordinates.lastLocalToRootInput)
    }

    @Test
    fun bottomAtXUsesGestureXAndCurrentHeight() {
        val coordinates =
            FakeLayoutCoordinates(
                root = Offset(20f, 40f),
                measuredSize = IntSize(width = 50, height = 35),
                scale = 2f
            )
        val anchorRef = SlockLiveRootAnchorRef(identity = "dm:beta")
        anchorRef.captureFromLayoutCoordinates(coordinates)

        assertNotNull(anchorRef.resolve(SlockPopoverLocalPoint.BottomAtX(12f)))
        assertEquals(Offset(12f, 35f), coordinates.lastLocalToRootInput)
    }

    @Test
    fun typedResolverDefersRootConversionUntilOpenAndKeepsIdentity() {
        val coordinates =
            FakeLayoutCoordinates(
                root = Offset(10f, 20f),
                measuredSize = IntSize(width = 40, height = 30)
            )
        val anchorRef = SlockLiveRootAnchorRef(identity = "search:sender")
        anchorRef.captureFromLayoutCoordinates(coordinates)
        val resolver = anchorRef.resolver(SlockPopoverLocalPoint.BottomStart)

        assertEquals(0, coordinates.localToRootCalls)
        coordinates.root = Offset(100f, 200f)

        assertEquals("search:sender", resolver.identity)
        assertNotNull(resolver.resolve())
        assertEquals(Offset(0f, 30f), coordinates.lastLocalToRootInput)
        assertEquals(1, coordinates.localToRootCalls)
    }

    @Test
    fun resolvedAnchorRejectsLazyReuseIdentityMismatch() {
        val anchorRef = SlockLiveRootAnchorRef(identity = "task:one")
        anchorRef.captureFromLayoutCoordinates(FakeLayoutCoordinates(root = Offset.Zero))
        val anchor = requireNotNull(anchorRef.resolve(SlockPopoverLocalPoint.Exact(Offset.Zero)))

        assertEquals(true, anchor.belongsTo("task:one"))
        assertEquals(false, anchor.belongsTo("task:two"))
    }

    @Test
    fun detachedCoordinatesFailClosedWithoutConversion() {
        val coordinates = FakeLayoutCoordinates(root = Offset(10f, 20f))
        val anchorRef = SlockLiveRootAnchorRef(identity = "detached")
        anchorRef.captureFromLayoutCoordinates(coordinates)
        coordinates.attached = false

        assertNull(anchorRef.resolve(SlockPopoverLocalPoint.Exact(Offset(4f, 6f))))
        assertNull(anchorRef.resolve(SlockPopoverLocalPoint.BottomStart))
        assertEquals(0, coordinates.localToRootCalls)
    }

    @Test
    fun invalidatedCaptureLeaseCannotResolveAfterCoordinatesAreReused() {
        val coordinates = FakeLayoutCoordinates(root = Offset(10f, 20f))
        val anchorRef = SlockLiveRootAnchorRef(identity = "lazy:item-a")
        anchorRef.captureFromLayoutCoordinates(coordinates)
        val oldResolver = anchorRef.resolver(SlockPopoverLocalPoint.BottomStart)

        anchorRef.invalidate()
        coordinates.root = Offset(300f, 400f)
        anchorRef.captureFromLayoutCoordinates(coordinates)
        val newResolver = anchorRef.resolver(SlockPopoverLocalPoint.BottomStart)

        assertNull(oldResolver.resolve())
        assertEquals(false, oldResolver.wasInvalidatedByCaptureReuse())
        assertNotNull(newResolver.resolve())
        assertEquals(Offset(0f, 10f), coordinates.lastLocalToRootInput)
    }

    @Test
    fun coordinateSlotClaimByNewIdentityPermanentlyInvalidatesOldResolver() {
        val reusedCoordinates = FakeLayoutCoordinates(root = Offset(10f, 20f))
        val oldRef = SlockLiveRootAnchorRef(identity = "lazy:A")
        oldRef.captureFromLayoutCoordinates(reusedCoordinates)
        val oldResolver = oldRef.resolver(SlockPopoverLocalPoint.BottomStart)

        val newRef = SlockLiveRootAnchorRef(identity = "lazy:B")
        reusedCoordinates.root = Offset(300f, 400f)
        newRef.captureFromLayoutCoordinates(reusedCoordinates)
        val newResolver = newRef.resolver(SlockPopoverLocalPoint.BottomStart)

        assertNull(oldResolver.resolve())
        assertEquals(true, oldResolver.wasInvalidatedByCaptureReuse())
        assertNotNull(newResolver.resolve())
        assertEquals(Offset(0f, 10f), reusedCoordinates.lastLocalToRootInput)
    }

    @Test
    fun attachedLazyModifierOwnerUpdateRecapturesWithoutAnotherPositionCallback() {
        val reusedCoordinates = FakeLayoutCoordinates(root = Offset(10f, 20f))
        val oldRef = SlockLiveRootAnchorRef(identity = "message:old")
        oldRef.captureFromLayoutCoordinates(reusedCoordinates)
        val newRef = SlockLiveRootAnchorRef(identity = "message:new")

        refreshSlockLiveRootAnchorCaptureOwner(
            currentOwner = oldRef,
            nextOwner = newRef,
            coordinates = reusedCoordinates
        )

        assertNull(oldRef.resolve(SlockPopoverLocalPoint.Exact(Offset.Zero)))
        assertNotNull(newRef.resolve(SlockPopoverLocalPoint.Exact(Offset.Zero)))
    }

    @Test
    fun sameOwnerLazyReattachRecapturesWithoutAnotherPositionCallback() {
        val reusedCoordinates = FakeLayoutCoordinates(root = Offset(10f, 20f))
        val anchorRef = SlockLiveRootAnchorRef(identity = "message:same")
        anchorRef.captureFromLayoutCoordinates(reusedCoordinates)

        // LayoutNode.onDeactivate resets modifier state, which runs the capture node's detach
        // lifecycle even when this exact semantic row is later reused by the same slot.
        anchorRef.invalidate()
        assertNull(anchorRef.resolve(SlockPopoverLocalPoint.Exact(Offset.Zero)))

        recaptureSlockLiveRootAnchorAfterAttach(anchorRef, reusedCoordinates)

        assertNotNull(anchorRef.resolve(SlockPopoverLocalPoint.Exact(Offset.Zero)))
    }

    @Test
    fun sameOwnerDetachedReattachRemainsInvalid() {
        val detachedCoordinates =
            FakeLayoutCoordinates(
                root = Offset(10f, 20f),
                attached = false
            )
        val anchorRef = SlockLiveRootAnchorRef(identity = "message:same")

        recaptureSlockLiveRootAnchorAfterAttach(anchorRef, detachedCoordinates)

        assertNull(anchorRef.resolve(SlockPopoverLocalPoint.Exact(Offset.Zero)))
    }

    @Test
    fun detachedLazyModifierOwnerUpdateInvalidatesWithoutRecapturing() {
        val detachedCoordinates = FakeLayoutCoordinates(root = Offset(10f, 20f), attached = false)
        val oldRef = SlockLiveRootAnchorRef(identity = "message:old")
        val initiallyAttachedCoordinates = FakeLayoutCoordinates(root = Offset(10f, 20f))
        oldRef.captureFromLayoutCoordinates(initiallyAttachedCoordinates)
        val newRef = SlockLiveRootAnchorRef(identity = "message:new")

        refreshSlockLiveRootAnchorCaptureOwner(
            currentOwner = oldRef,
            nextOwner = newRef,
            coordinates = detachedCoordinates
        )

        assertNull(oldRef.resolve(SlockPopoverLocalPoint.Exact(Offset.Zero)))
        assertNull(newRef.resolve(SlockPopoverLocalPoint.Exact(Offset.Zero)))
    }
}

/**
 * Task #180 follow-up (LiBai): the earlier origin tests only exercised state arithmetic. These drive
 * the REAL path -- a `LiveRef`, `captureFromLayoutCoordinates`, then `resolve()` -- and assert the
 * returned root offset actually moves when the child-page origin changes. Without this, a fix that
 * silently does nothing (as the first attempt did on device) would still pass the suite.
 */
class SlockLiveRootAnchorRefChildPageOriginTest {
    @Test
    fun resolveAddsTheChildPageOriginToTheResolvedRootOffset() {
        val coordinates = FakeLayoutCoordinates(root = Offset(10f, 20f))
        val anchorRef = SlockLiveRootAnchorRef(identity = "thread:menu")
        val origin = SlockChildPageOriginState()
        anchorRef.attachChildPageOrigin(origin)
        anchorRef.captureFromLayoutCoordinates(coordinates)

        // No origin: page-root coordinates are used as-is (the old, full-screen behaviour).
        assertEquals(Offset(10f, 20f), anchorRef.resolve(SlockPopoverLocalPoint.Exact(Offset.Zero))?.rootOffsetForTest())

        // With the host's pane origin, the same anchor resolves into WINDOW coordinates.
        origin.updateOrigin(Offset(390f, 0f))
        assertEquals(Offset(400f, 20f), anchorRef.resolve(SlockPopoverLocalPoint.Exact(Offset.Zero))?.rootOffsetForTest())
    }

    @Test
    fun mutatingTheOriginChangesTheNextResolveWithoutRecapturing() {
        val coordinates = FakeLayoutCoordinates(root = Offset(0f, 0f))
        val anchorRef = SlockLiveRootAnchorRef(identity = "thread:menu")
        val origin = SlockChildPageOriginState()
        anchorRef.attachChildPageOrigin(origin)
        anchorRef.captureFromLayoutCoordinates(coordinates)

        origin.updateOrigin(Offset(100f, 0f))
        assertEquals(Offset(100f, 0f), anchorRef.resolve(SlockPopoverLocalPoint.Exact(Offset.Zero))?.rootOffsetForTest())

        // Pane resized / rotated: the origin moves and the NEXT open must follow it.
        origin.updateOrigin(Offset(250f, 12f))
        assertEquals(
            Offset(250f, 12f),
            anchorRef.resolve(SlockPopoverLocalPoint.Exact(Offset.Zero))?.rootOffsetForTest(),
            "resolve must read the origin live rather than a cached snapshot"
        )
    }

    @Test
    fun aNestedOriginReachesTheResolvedOffset() {
        val parent = SlockChildPageOriginState()
        val child = SlockChildPageOriginState()
        child.linkParentOrigin(parent)
        parent.updateOrigin(Offset(390f, 0f))
        child.updateOrigin(Offset(60f, 0f))

        val coordinates = FakeLayoutCoordinates(root = Offset(5f, 5f))
        val anchorRef = SlockLiveRootAnchorRef(identity = "nested:menu")
        anchorRef.attachChildPageOrigin(child)
        anchorRef.captureFromLayoutCoordinates(coordinates)

        assertEquals(
            Offset(455f, 5f),
            anchorRef.resolve(SlockPopoverLocalPoint.Exact(Offset.Zero))?.rootOffsetForTest(),
            "nested page must contribute parent + own origin"
        )
    }

    @Test
    fun detachingTheOriginRestoresPageRootBehaviour() {
        val coordinates = FakeLayoutCoordinates(root = Offset(8f, 9f))
        val anchorRef = SlockLiveRootAnchorRef(identity = "plain:menu")
        anchorRef.attachChildPageOrigin(SlockChildPageOriginState())
        anchorRef.captureFromLayoutCoordinates(coordinates)
        anchorRef.attachChildPageOrigin(null)

        assertEquals(Offset(8f, 9f), anchorRef.resolve(SlockPopoverLocalPoint.Exact(Offset.Zero))?.rootOffsetForTest())
    }
}

private class FakeLayoutCoordinates(
    var root: Offset,
    var measuredSize: IntSize = IntSize(width = 10, height = 10),
    var scale: Float = 1f,
    var attached: Boolean = true
) : LayoutCoordinates {
    var localToRootCalls: Int = 0
        private set
    var lastLocalToRootInput: Offset? = null
        private set

    override val size: IntSize
        get() = measuredSize
    override val providedAlignmentLines: Set<AlignmentLine> = emptySet()
    override val parentLayoutCoordinates: LayoutCoordinates? = null
    override val parentCoordinates: LayoutCoordinates? = null
    override val isAttached: Boolean
        get() = attached

    override fun windowToLocal(relativeToWindow: Offset): Offset =
        Offset(
            x = (relativeToWindow.x - root.x) / scale,
            y = (relativeToWindow.y - root.y) / scale
        )

    override fun localToWindow(relativeToLocal: Offset): Offset = localToRoot(relativeToLocal)

    override fun localToRoot(relativeToLocal: Offset): Offset {
        localToRootCalls += 1
        lastLocalToRootInput = relativeToLocal
        return Offset(
            x = root.x + relativeToLocal.x * scale,
            y = root.y + relativeToLocal.y * scale
        )
    }

    override fun localPositionOf(
        sourceCoordinates: LayoutCoordinates,
        relativeToSource: Offset
    ): Offset = error("not needed by SlockLiveRootAnchorRefTest")

    override fun localBoundingBoxOf(
        sourceCoordinates: LayoutCoordinates,
        clipBounds: Boolean
    ): Rect = Rect.Zero

    override fun get(alignmentLine: AlignmentLine): Int = AlignmentLine.Unspecified
}
