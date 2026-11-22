package build.raft.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The image editor saves at the photo's resolution: edits made on the screen-sized canvas are
 * re-rendered over the full-size source. Real Android drawing (Robolectric native graphics).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class RaftComposerImageEditorExportRenderTest {
    private val pen = Color(0xFFFF3B30)

    @Test
    fun croppedExportIsTheCropRegionAtSourceResolution() {
        val source = gradientSource(2000, 1500)
        // Right-top quarter (x 1000..2000, y 0..750) fitted into a 500x375 canvas (x2).
        val crop = RaftComposerImageCropRect(0.5f, 0f, 1f, 0.5f)
        val canvas = IntSize(500, 375)
        val content =
            RaftComposerImageEditorContent(cropRect = crop)
                .raftComposerImageAppendStroke(
                    RaftComposerImageStroke(points = listOf(Offset(50f, 200f), Offset(150f, 200f)), color = pen),
                )

        val out = assertNotNull(render(source, content, canvas)).asAndroidBitmap()

        assertEquals(1000 to 750, out.width to out.height)
        // Canvas (100, 200) -> source (1200, 400) -> export (200, 400).
        assertEquals(Offset(1200f, 400f), raftComposerImageCanvasToSource(Offset(100f, 200f), canvas, crop, 2000, 1500))
        assertPen(out.getPixel(200, 400), "stroke in the cropped export")
        assertEquals(source.asAndroidBitmap().getPixel(1300, 100), out.getPixel(300, 100), "photo at source resolution")
    }

    @Test
    fun quarterTurnExportSwapsSidesAndCarriesTheStrokeWithThePhoto() {
        // 2000x1500 photo on a 500x375 canvas, turned clockwise once: the export is 1500x2000.
        val source = gradientSource(2000, 1500)
        val canvas = IntSize(500, 375)
        val stroke =
            RaftComposerImageStroke(
                points = listOf(Offset(100f, 100f), Offset(300f, 100f)),
                color = pen,
                width = RaftComposerImagePenWidth.Thick.px,
            )
        val content =
            RaftComposerImageEditorContent(rotationQuarterTurns = 1)
                .raftComposerImageAppendStroke(stroke)

        val out = assertNotNull(render(source, content, canvas)).asAndroidBitmap()

        assertEquals(1500 to 2000, out.width to out.height)
        // Source (800, 400) turns to (1500 - 400, 800) = (1100, 800).
        val turned = raftComposerImageRotatePoint(Offset(800f, 400f), androidx.compose.ui.geometry.Size(2000f, 1500f), 1)
        assertEquals(Offset(1100f, 800f), turned)
        assertPen(out.getPixel(1100, 800), "turned stroke centre")
        // The thick pen (16 canvas px) is 64 px wide here: 25 px across the line is still ink.
        assertPen(out.getPixel(1100 + 25, 800), "inside the thick stroke")
        // The photo turned with it: source pixel (x, y) is turned pixel (1499 - y, x). (102, 1401)
        // sits inside one 4 px gradient step, so filtering at the pixel edges cannot change it.
        assertEquals(source.asAndroidBitmap().getPixel(102, 1401), out.getPixel(98, 102), "photo pixel follows the turn")
    }

    private fun render(
        source: ImageBitmap,
        content: RaftComposerImageEditorContent,
        canvas: IntSize,
    ): ImageBitmap? = renderRaftComposerImageExport(source, content, canvas, Density(1f), text = null)

    private fun assertPen(pixel: Int, what: String) {
        val r = android.graphics.Color.red(pixel)
        val g = android.graphics.Color.green(pixel)
        assertTrue(r > 230 && g < 90, "$what: expected pen red, got #${pixel.toUInt().toString(16)}")
    }

    /** A source whose pixels differ every few px, so resampling or mosaic shows up. */
    private fun gradientSource(width: Int, height: Int): ImageBitmap {
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            android.graphics.Color.rgb(0, (x / 4) % 200, (y / 4) % 200)
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap.asImageBitmap()
    }
}
