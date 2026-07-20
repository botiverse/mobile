package build.raft.app

import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.geometry.Rect
import com.tencent.kuikly.compose.ui.layout.AlignmentLine
import com.tencent.kuikly.compose.ui.layout.LayoutCoordinates
import com.tencent.kuikly.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SlockLiveRootAnchorRefTest {
    @Test
    fun retainedCoordinatesResolveCurrentPlacementWithoutAnotherUpdate() {
        val coordinates = FakeLayoutCoordinates(root = Offset(10f, 20f), scale = 2f)
        val anchorRef = SlockLiveRootAnchorRef()

        anchorRef.update(coordinates)
        assertEquals(Offset(18f, 32f), anchorRef.resolve(Offset(4f, 6f)))

        coordinates.root = Offset(100f, 200f)
        coordinates.scale = 3f

        assertEquals(Offset(112f, 218f), anchorRef.resolve(Offset(4f, 6f)))
        assertEquals(2, coordinates.localToRootCalls)
    }

    @Test
    fun sizeDerivedAnchorUsesCurrentSizeAndTransform() {
        val coordinates =
            FakeLayoutCoordinates(
                root = Offset(8f, 12f),
                measuredSize = IntSize(width = 30, height = 20),
                scale = 2f
            )
        val anchorRef = SlockLiveRootAnchorRef()
        anchorRef.update(coordinates)

        coordinates.root = Offset(20f, 40f)
        coordinates.measuredSize = IntSize(width = 50, height = 35)

        assertEquals(
            Offset(120f, 110f),
            anchorRef.resolve { size ->
                Offset(size.width.toFloat(), size.height.toFloat())
            }
        )
    }

    @Test
    fun detachedCoordinatesFailClosedWithoutConversion() {
        val coordinates = FakeLayoutCoordinates(root = Offset(10f, 20f))
        val anchorRef = SlockLiveRootAnchorRef()
        anchorRef.update(coordinates)
        coordinates.attached = false

        assertNull(anchorRef.resolve(Offset(4f, 6f)))
        assertNull(anchorRef.resolve { size -> Offset(0f, size.height.toFloat()) })
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
