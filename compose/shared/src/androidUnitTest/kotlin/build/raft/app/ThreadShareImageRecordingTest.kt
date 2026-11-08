package build.raft.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.IntSize
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The share image is recorded on the main thread (an `android.graphics.Picture`) and rasterised
 * off it ([ThreadShareImageRecording.rasterise] on Dispatchers.Default), band by band so a new
 * width stops a stale one; the preview gets a downscaled copy.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ThreadShareImageRecordingTest {
    private val red = Color(0xFFFF0000) // raft-color: test fixture
    private val blue = Color(0xFF0000FF) // raft-color: test fixture

    private fun record(width: Int, height: Int): ThreadShareImageRecording =
        recordThreadShareImage(width, height) { canvas ->
            canvas.drawRect(0f, 0f, width.toFloat(), height / 2f, Paint().apply { color = red })
            canvas.drawRect(0f, height / 2f, width.toFloat(), height.toFloat(), Paint().apply { color = blue })
        }

    @Test
    fun aRecordingRasterisesOnAnotherThreadBandByBand() {
        val height = ThreadShareImageRasterBandPx * 2 + 100
        val recording = record(40, height)
        val executor = Executors.newSingleThreadExecutor()
        var checks = 0
        val image =
            try {
                executor.submit<ImageBitmap> { recording.rasterise(checkCancelled = { checks++ }) }.get(30, TimeUnit.SECONDS)
            } finally {
                executor.shutdown()
                recording.release()
            }
        assertEquals(40, image.width)
        assertEquals(height, image.height)
        assertEquals(3, checks, "one check per band")
        val pixels = image.toPixelMap()
        assertEquals(red.toArgb(), pixels[20, 10].toArgb())
        assertEquals(red.toArgb(), pixels[20, height / 2 - 1].toArgb())
        assertEquals(blue.toArgb(), pixels[20, height / 2 + 1].toArgb(), "the band past the first is drawn too")
        assertEquals(blue.toArgb(), pixels[20, height - 1].toArgb())
    }

    @Test
    fun aCancelledRasteriseStopsAtTheNextBand() {
        val recording = record(10, ThreadShareImageRasterBandPx * 4)
        var checks = 0
        assertFailsWith<IllegalStateException> {
            recording.rasterise(checkCancelled = { if (++checks == 2) error("cancelled") })
        }
        assertEquals(2, checks)
    }

    @Test
    fun theRowsAreRecordedIntoTheCard() {
        val recording =
            recordThreadShareImageRows(
                rowSizes = listOf(IntSize(30, 20), IntSize(30, 10)),
                density = androidx.compose.ui.unit.Density(1f),
                measurer = androidx.compose.ui.text.TextMeasurer(
                    androidx.compose.ui.text.font.createFontFamilyResolver(org.robolectric.RuntimeEnvironment.getApplication()),
                    androidx.compose.ui.unit.Density(1f),
                    androidx.compose.ui.unit.LayoutDirection.Ltr,
                ),
                background = Color.White,
            ) { index -> drawRect(if (index == 0) red else blue, topLeft = Offset.Zero, size = Size(30f, if (index == 0) 20f else 10f)) }
        requireNotNull(recording)
        val image = recording.rasterise()
        recording.release()
        assertEquals(threadShareImageWidthPx(30, 1f), image.width)
        assertEquals(threadShareImageHeightPx(listOf(20, 10), 1f), image.height)
        val origins = threadShareImageRowOrigins(listOf(20, 10), 1f)
        val pixels = image.toPixelMap()
        assertEquals(red.toArgb(), pixels[origins[0].x + 5, origins[0].y + 5].toArgb())
        assertEquals(blue.toArgb(), pixels[origins[1].x + 5, origins[1].y + 5].toArgb())
    }

    @Test
    fun thePreviewCopyIsDrawnAtItsSize() {
        val recording = record(1000, 3000)
        val source = recording.rasterise()
        recording.release()
        val size = threadShareImagePreviewSize(source.width, source.height, maxWidthPx = 300)
        val preview = downscaleThreadShareImage(source, size)
        assertEquals(IntSize(300, 900), IntSize(preview.width, preview.height))
        val pixels = preview.toPixelMap()
        assertEquals(red.toArgb(), pixels[150, 100].toArgb())
        assertEquals(blue.toArgb(), pixels[150, 800].toArgb())
        assertSame(source, downscaleThreadShareImage(source, IntSize(1000, 3000)), "no copy when nothing shrinks")
    }

    @Test
    fun theGpuFallbackStaysWithinTheTextureLimit() {
        kotlin.test.assertTrue(threadShareImageGpuFallbackFits(1170, 4096))
        kotlin.test.assertTrue(threadShareImageGpuFallbackFits(4096, 4096))
        kotlin.test.assertFalse(threadShareImageGpuFallbackFits(1170, 4097), "a tall image would come back blank from the GPU")
        kotlin.test.assertFalse(threadShareImageGpuFallbackFits(4097, 100))
        kotlin.test.assertFalse(threadShareImageGpuFallbackFits(0, 100))
    }
}
