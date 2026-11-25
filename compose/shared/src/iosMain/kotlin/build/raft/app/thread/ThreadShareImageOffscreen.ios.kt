package build.raft.app

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas as SkiaCanvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Picture
import org.jetbrains.skia.PictureRecorder
import org.jetbrains.skia.Rect

internal actual val threadShareImageRendersOffscreen: Boolean = true

/**
 * A Skia picture: recording it makes each row's GraphicsLayer draw its own picture into this
 * one on the main thread; playing it back into the bitmap is plain Skia work any thread may do.
 */
internal actual fun recordThreadShareImage(width: Int, height: Int, draw: (Canvas) -> Unit): ThreadShareImageRecording {
    val recorder = PictureRecorder()
    try {
        val canvas = recorder.beginRecording(Rect.makeWH(width.toFloat(), height.toFloat()))
        draw(canvas.asComposeCanvas())
        return SkiaThreadShareImageRecording(recorder.finishRecordingAsPicture(), width, height)
    } finally {
        recorder.close()
    }
}

private class SkiaThreadShareImageRecording(
    private val picture: Picture,
    override val width: Int,
    override val height: Int,
) : ThreadShareImageRecording {
    override fun rasterise(checkCancelled: () -> Unit): ImageBitmap {
        val bitmap = Bitmap()
        bitmap.allocPixels(ImageInfo.makeN32(width, height, ColorAlphaType.PREMUL, ColorSpace.sRGB))
        val canvas = SkiaCanvas(bitmap)
        try {
            for (band in threadShareImageRasterBands(height)) {
                checkCancelled()
                val saved = canvas.save()
                canvas.clipRect(Rect.makeLTRB(0f, band.first.toFloat(), width.toFloat(), (band.last + 1).toFloat()))
                canvas.drawPicture(picture)
                canvas.restoreToCount(saved)
            }
        } catch (error: Throwable) {
            bitmap.close()
            throw error
        } finally {
            canvas.close()
        }
        bitmap.setImmutable()
        return bitmap.asComposeImageBitmap()
    }

    override fun release() = picture.close()
}
