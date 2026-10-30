package build.raft.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Display-canvas -> source-pixel mapping and size budget of the image editor's full-res export. */
class RaftComposerImageEditorExportMathTest {
    @Test
    fun exportSizeIsTheCropRegionAtSourceResolution() {
        assertEquals(IntSize(4000, 3000), raftComposerImageExportSize(4000, 3000))
        assertEquals(
            IntSize(2000, 1500),
            raftComposerImageExportSize(4000, 3000, RaftComposerImageCropRect(0.25f, 0.1f, 0.75f, 0.6f)),
        )
        assertNull(raftComposerImageExportSize(0, 3000))
    }

    @Test
    fun oversizedSourcesAreCappedByLongEdgeThenPixelBudget() {
        // Panorama: long edge cap (8192) wins, aspect ratio kept.
        assertEquals(IntSize(8192, 2048), raftComposerImageExportSize(12000, 3000))
        // 48 MP 4:3: the 40 MP budget wins.
        val capped = raftComposerImageExportSize(8000, 6000)!!
        assertTrue(capped.width.toLong() * capped.height <= RaftComposerImageExportMaxPixels, "capped=$capped")
        assertTrue(capped.width.toLong() * capped.height > 39_000_000L, "capped=$capped")
        assertEquals(4f / 3f, capped.width.toFloat() / capped.height, 0.001f)
    }

    @Test
    fun decodeBoundKeepsTheDecodedSourceWithinBudget() {
        // 4:3 -> sqrt(40M * 4/3) = 7302; square -> 6324; 4:1 panorama -> long-edge cap.
        assertEquals(7302, raftComposerImageExportDecodeEdgePx(1000, 750))
        assertEquals(7302, raftComposerImageExportDecodeEdgePx(750, 1000))
        assertEquals(6324, raftComposerImageExportDecodeEdgePx(800, 800))
        assertEquals(8192, raftComposerImageExportDecodeEdgePx(2400, 600))
        assertEquals(RaftComposerImageExportMaxLongEdgePx, raftComposerImageExportDecodeEdgePx(0, 0))
    }

    @Test
    fun canvasPointsMapToSourcePixelsThroughTheCrop() {
        val source = IntSize(4000, 3000)
        // Uncropped 4000x3000 fitted into a 1000x750 canvas: x4.
        assertEquals(
            Offset(2000f, 1500f),
            raftComposerImageCanvasToSource(Offset(500f, 375f), IntSize(1000, 750), RaftComposerImageCropRect.Uncropped, source.width, source.height),
        )
        // Crop x 1000..3000, y 300..1800 (2000x1500) fitted into 1000x750: x2, offset by the crop origin.
        val crop = RaftComposerImageCropRect(0.25f, 0.1f, 0.75f, 0.6f)
        assertEquals(
            Offset(1000f, 300f),
            raftComposerImageCanvasToSource(Offset.Zero, IntSize(1000, 750), crop, source.width, source.height),
        )
        assertEquals(
            Offset(2000f, 1050f),
            raftComposerImageCanvasToSource(Offset(500f, 375f), IntSize(1000, 750), crop, source.width, source.height),
        )
        assertEquals(
            Offset(3000f, 1800f),
            raftComposerImageCanvasToSource(Offset(1000f, 750f), IntSize(1000, 750), crop, source.width, source.height),
        )
    }

    @Test
    fun canvasPointsMapToExportPixelsRelativeToTheCrop() {
        val crop = RaftComposerImageCropRect(0.25f, 0.1f, 0.75f, 0.6f)
        val export = raftComposerImageExportSize(4000, 3000, crop)!!
        val canvas = IntSize(1000, 750)
        val point = Offset(500f, 375f)
        val inExport = raftComposerImageCanvasToExport(point, canvas, export)
        val inSource = raftComposerImageCanvasToSource(point, canvas, crop, 4000, 3000)
        assertEquals(Offset(1000f, 750f), inExport)
        // Export pixel = source pixel minus the crop origin (no cap at this size).
        assertEquals(inSource - Offset(1000f, 300f), inExport)
    }

    @Test
    fun textBoxesAreCenteredThenOffsetLikeTheOnScreenBox() {
        assertEquals(
            IntOffset(400 + 10, 350 - 20),
            raftComposerImageTextBoxTopLeft(Offset(10.4f, -20f), IntSize(200, 50), IntSize(1000, 750)),
        )
    }
}
