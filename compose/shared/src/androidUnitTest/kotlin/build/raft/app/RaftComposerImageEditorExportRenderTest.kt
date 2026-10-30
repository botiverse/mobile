package build.raft.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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
    fun uncroppedExportKeepsSourceSizeAndPutsTheStrokeAtTheSourcePixel() {
        // 2000x1500 photo shown on a 500x375 canvas (x4), like a 4000 px photo on a phone.
        val source = gradientSource(2000, 1500)
        val canvas = IntSize(500, 375)
        val stroke = RaftComposerImageStroke(points = listOf(Offset(100f, 100f), Offset(300f, 100f)), color = pen)
        val content =
            RaftComposerImageEditorContent()
                .raftComposerImageAppendStroke(stroke)

        val out = assertNotNull(render(source, content, canvas)).asAndroidBitmap()

        assertEquals(2000 to 1500, out.width to out.height)
        // Canvas (200, 100) -> source (800, 400); the 6 px stroke becomes 24 px wide.
        val hit = raftComposerImageCanvasToSource(Offset(200f, 100f), canvas, RaftComposerImageCropRect.Uncropped, 2000, 1500)
        assertEquals(Offset(800f, 400f), hit)
        assertPen(out.getPixel(800, 400), "stroke centre")
        assertPen(out.getPixel(800, 400 + 10), "inside the scaled stroke width")
        assertEquals(source.asAndroidBitmap().getPixel(800, 400 + 20), out.getPixel(800, 400 + 20), "photo just past the stroke")
    }

    @Test
    fun mosaicBlocksScaleWithTheExport() {
        val source = gradientSource(2000, 1500)
        val canvas = IntSize(500, 375)
        // Tile (10, 10) covers canvas 180..198 -> source 720..792 (an 18 px tile is 72 px here).
        val tile = RaftComposerImageMosaicTile(column = 10, row = 10)
        val content =
            RaftComposerImageEditorContent(
                mosaicTiles = setOf(tile),
                drawOperations = listOf(RaftComposerImageDrawOperation.Mosaic(setOf(tile))),
            )

        val out = assertNotNull(render(source, content, canvas)).asAndroidBitmap()

        val block = out.getPixel(724, 724)
        listOf(724 to 788, 788 to 724, 788 to 788, 756 to 756).forEach { (x, y) ->
            assertEquals(block, out.getPixel(x, y), "mosaic block is one colour at ($x, $y)")
        }
        // The gradient source changes every few pixels, so an untouched block would not be flat.
        assertTrue(source.asAndroidBitmap().getPixel(724, 724) != source.asAndroidBitmap().getPixel(788, 724))
        // Outside the block the photo is untouched.
        assertEquals(source.asAndroidBitmap().getPixel(700, 700), out.getPixel(700, 700))

        // The editor samples block colours from its on-screen decode (what the user saw).
        val screenDecode = solidSource(500, 375, Color(0xFF00FF00))
        val withScreenColours =
            assertNotNull(
                renderRaftComposerImageExport(source, content, canvas, Density(1f), text = null, mosaicSource = screenDecode),
            ).asAndroidBitmap()
        assertEquals(android.graphics.Color.GREEN, withScreenColours.getPixel(756, 756))
        assertEquals(source.asAndroidBitmap().getPixel(700, 700), withScreenColours.getPixel(700, 700))
    }

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
    fun textAnnotationIsDrawnCenteredAndScaled() {
        val source = solidSource(2000, 1500, Color(0xFF0000FF))
        val canvas = IntSize(500, 375)
        val density = Density(2f)
        val content =
            RaftComposerImageEditorContent(
                textAnnotations = listOf(RaftComposerImageTextAnnotation(id = 0, text = "i", position = Offset.Zero)),
            )
        val text =
            RaftComposerImageExportText(
                measurer =
                    TextMeasurer(
                        createFontFamilyResolver(RuntimeEnvironment.getApplication()),
                        density,
                        LayoutDirection.Ltr,
                    ),
                style = TextStyle(fontSize = 20.sp, lineHeight = 24.sp, color = pen),
                borderColor = Color.Black,
            )

        val out =
            assertNotNull(
                renderRaftComposerImageExport(source, content, canvas, density, text),
            ).asAndroidBitmap()

        // The box is at least 48 dp (96 px on canvas) wide around the canvas centre (250, 187.5);
        // 20 dp left of centre is inside its left padding: the translucent white box over blue.
        val inside = out.getPixel(((250 - 40) * 4), (187 * 4))
        assertTrue(android.graphics.Color.red(inside) > 180, "box background, got #${inside.toUInt().toString(16)}")
        // Far from the box the photo is untouched.
        assertEquals(source.asAndroidBitmap().getPixel(40, 40), out.getPixel(40, 40))
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

    private fun solidSource(width: Int, height: Int, color: Color): ImageBitmap {
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.argb(255, (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt()))
        return bitmap.asImageBitmap()
    }
}
