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
