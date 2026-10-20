package build.raft.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RaftLiveRootAnchorRefTest {
    @Test
    fun retainedCoordinatesResolveCurrentPlacementWithoutAnotherUpdate() {
        val coordinates = FakeLayoutCoordinates(root = Offset(10f, 20f), scale = 2f)
        val anchorRef = RaftLiveRootAnchorRef(identity = "channel:alpha")

        anchorRef.captureFromLayoutCoordinates(coordinates)
        assertNotNull(anchorRef.resolve(RaftPopoverLocalPoint.Exact(Offset(4f, 6f))))
        assertEquals(Offset(4f, 6f), coordinates.lastLocalToRootInput)

        coordinates.root = Offset(100f, 200f)
        coordinates.scale = 3f

        assertNotNull(anchorRef.resolve(RaftPopoverLocalPoint.Exact(Offset(4f, 6f))))
        assertEquals(Offset(4f, 6f), coordinates.lastLocalToRootInput)
        assertEquals(2, coordinates.localToRootCalls)
    }

    @Test
    fun retainedResolverPublishesChangedGlobalPlacementWhileObserved() {
        val coordinates = FakeLayoutCoordinates(root = Offset(10f, 20f))
        val anchorRef = RaftLiveRootAnchorRef(identity = "composer")
        val resolver = anchorRef.resolver(RaftPopoverLocalPoint.Exact(Offset.Zero))

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
        val anchorRef = RaftLiveRootAnchorRef(identity = "composer")
        anchorRef.captureFromLayoutCoordinates(coordinates)
        val resolver = anchorRef.resolver(RaftPopoverLocalPoint.Exact(Offset.Zero))
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
        val anchorRef = RaftLiveRootAnchorRef(identity = "sort")
        anchorRef.captureFromLayoutCoordinates(coordinates)

        coordinates.root = Offset(20f, 40f)
        coordinates.measuredSize = IntSize(width = 50, height = 35)

        assertNotNull(anchorRef.resolve(RaftPopoverLocalPoint.BottomEnd))
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
        val anchorRef = RaftLiveRootAnchorRef(identity = "dm:beta")
        anchorRef.captureFromLayoutCoordinates(coordinates)

        assertNotNull(anchorRef.resolve(RaftPopoverLocalPoint.BottomAtX(12f)))
        assertEquals(Offset(12f, 35f), coordinates.lastLocalToRootInput)
    }

    @Test
    fun typedResolverDefersRootConversionUntilOpenAndKeepsIdentity() {
        val coordinates =
            FakeLayoutCoordinates(
                root = Offset(10f, 20f),
                measuredSize = IntSize(width = 40, height = 30)
            )
        val anchorRef = RaftLiveRootAnchorRef(identity = "search:sender")
        anchorRef.captureFromLayoutCoordinates(coordinates)
        val resolver = anchorRef.resolver(RaftPopoverLocalPoint.BottomStart)

        assertEquals(0, coordinates.localToRootCalls)
        coordinates.root = Offset(100f, 200f)

        assertEquals("search:sender", resolver.identity)
        assertNotNull(resolver.resolve())
        assertEquals(Offset(0f, 30f), coordinates.lastLocalToRootInput)
        assertEquals(1, coordinates.localToRootCalls)
    }

    @Test
    fun resolvedAnchorRejectsLazyReuseIdentityMismatch() {
        val anchorRef = RaftLiveRootAnchorRef(identity = "task:one")
        anchorRef.captureFromLayoutCoordinates(FakeLayoutCoordinates(root = Offset.Zero))
        val anchor = requireNotNull(anchorRef.resolve(RaftPopoverLocalPoint.Exact(Offset.Zero)))

        assertEquals(true, anchor.belongsTo("task:one"))
        assertEquals(false, anchor.belongsTo("task:two"))
    }

    @Test
    fun detachedCoordinatesFailClosedWithoutConversion() {
        val coordinates = FakeLayoutCoordinates(root = Offset(10f, 20f))
        val anchorRef = RaftLiveRootAnchorRef(identity = "detached")
        anchorRef.captureFromLayoutCoordinates(coordinates)
        coordinates.attached = false

        assertNull(anchorRef.resolve(RaftPopoverLocalPoint.Exact(Offset(4f, 6f))))
        assertNull(anchorRef.resolve(RaftPopoverLocalPoint.BottomStart))
        assertEquals(0, coordinates.localToRootCalls)
    }

    @Test
    fun invalidatedCaptureLeaseCannotResolveAfterCoordinatesAreReused() {
        val coordinates = FakeLayoutCoordinates(root = Offset(10f, 20f))
        val anchorRef = RaftLiveRootAnchorRef(identity = "lazy:item-a")
        anchorRef.captureFromLayoutCoordinates(coordinates)
        val oldResolver = anchorRef.resolver(RaftPopoverLocalPoint.BottomStart)

        anchorRef.invalidate()
        coordinates.root = Offset(300f, 400f)
        anchorRef.captureFromLayoutCoordinates(coordinates)
        val newResolver = anchorRef.resolver(RaftPopoverLocalPoint.BottomStart)

        assertNull(oldResolver.resolve())
        assertEquals(false, oldResolver.wasInvalidatedByCaptureReuse())
        assertNotNull(newResolver.resolve())
        assertEquals(Offset(0f, 10f), coordinates.lastLocalToRootInput)
    }

    @Test
    fun coordinateSlotClaimByNewIdentityPermanentlyInvalidatesOldResolver() {
        val reusedCoordinates = FakeLayoutCoordinates(root = Offset(10f, 20f))
        val oldRef = RaftLiveRootAnchorRef(identity = "lazy:A")
        oldRef.captureFromLayoutCoordinates(reusedCoordinates)
        val oldResolver = oldRef.resolver(RaftPopoverLocalPoint.BottomStart)

        val newRef = RaftLiveRootAnchorRef(identity = "lazy:B")
        reusedCoordinates.root = Offset(300f, 400f)
        newRef.captureFromLayoutCoordinates(reusedCoordinates)
        val newResolver = newRef.resolver(RaftPopoverLocalPoint.BottomStart)

        assertNull(oldResolver.resolve())
        assertEquals(true, oldResolver.wasInvalidatedByCaptureReuse())
        assertNotNull(newResolver.resolve())
        assertEquals(Offset(0f, 10f), reusedCoordinates.lastLocalToRootInput)
    }

    @Test
    fun attachedLazyModifierOwnerUpdateRecapturesWithoutAnotherPositionCallback() {
        val reusedCoordinates = FakeLayoutCoordinates(root = Offset(10f, 20f))
        val oldRef = RaftLiveRootAnchorRef(identity = "message:old")
        oldRef.captureFromLayoutCoordinates(reusedCoordinates)
        val newRef = RaftLiveRootAnchorRef(identity = "message:new")

        refreshRaftLiveRootAnchorCaptureOwner(
            currentOwner = oldRef,
            nextOwner = newRef,
            coordinates = reusedCoordinates
        )

        assertNull(oldRef.resolve(RaftPopoverLocalPoint.Exact(Offset.Zero)))
        assertNotNull(newRef.resolve(RaftPopoverLocalPoint.Exact(Offset.Zero)))
    }

    @Test
    fun sameOwnerLazyReattachRecapturesWithoutAnotherPositionCallback() {
        val reusedCoordinates = FakeLayoutCoordinates(root = Offset(10f, 20f))
        val anchorRef = RaftLiveRootAnchorRef(identity = "message:same")
        anchorRef.captureFromLayoutCoordinates(reusedCoordinates)

        // LayoutNode.onDeactivate resets modifier state, which runs the capture node's detach
        // lifecycle even when this exact semantic row is later reused by the same slot.
        anchorRef.invalidate()
        assertNull(anchorRef.resolve(RaftPopoverLocalPoint.Exact(Offset.Zero)))

        recaptureRaftLiveRootAnchorAfterAttach(anchorRef, reusedCoordinates)

        assertNotNull(anchorRef.resolve(RaftPopoverLocalPoint.Exact(Offset.Zero)))
    }

    @Test
    fun sameOwnerDetachedReattachRemainsInvalid() {
        val detachedCoordinates =
            FakeLayoutCoordinates(
                root = Offset(10f, 20f),
                attached = false
            )
        val anchorRef = RaftLiveRootAnchorRef(identity = "message:same")

        recaptureRaftLiveRootAnchorAfterAttach(anchorRef, detachedCoordinates)

        assertNull(anchorRef.resolve(RaftPopoverLocalPoint.Exact(Offset.Zero)))
    }

    @Test
    fun detachedLazyModifierOwnerUpdateInvalidatesWithoutRecapturing() {
        val detachedCoordinates = FakeLayoutCoordinates(root = Offset(10f, 20f), attached = false)
        val oldRef = RaftLiveRootAnchorRef(identity = "message:old")
        val initiallyAttachedCoordinates = FakeLayoutCoordinates(root = Offset(10f, 20f))
        oldRef.captureFromLayoutCoordinates(initiallyAttachedCoordinates)
        val newRef = RaftLiveRootAnchorRef(identity = "message:new")

        refreshRaftLiveRootAnchorCaptureOwner(
            currentOwner = oldRef,
            nextOwner = newRef,
            coordinates = detachedCoordinates
        )

        assertNull(oldRef.resolve(RaftPopoverLocalPoint.Exact(Offset.Zero)))
        assertNull(newRef.resolve(RaftPopoverLocalPoint.Exact(Offset.Zero)))
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
    ): Offset = error("not needed by RaftLiveRootAnchorRefTest")

    override fun localBoundingBoxOf(
        sourceCoordinates: LayoutCoordinates,
        clipBounds: Boolean
    ): Rect = Rect.Zero

    override fun get(alignmentLine: AlignmentLine): Int = AlignmentLine.Unspecified
}
