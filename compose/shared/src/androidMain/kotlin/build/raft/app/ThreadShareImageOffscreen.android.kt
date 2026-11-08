package build.raft.app

import android.graphics.Bitmap
import android.graphics.Picture
import android.os.Build
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

internal actual val threadShareImageRendersOffscreen: Boolean = true

/**
 * An [android.graphics.Picture]: recording it on a (software) picture canvas makes each row's
 * GraphicsLayer re-run its drawing there, on the main thread; playing it back into the bitmap is
 * plain Skia work any thread may do.
 */
internal actual fun recordThreadShareImage(width: Int, height: Int, draw: (Canvas) -> Unit): ThreadShareImageRecording {
    val picture = Picture()
    val canvas = picture.beginRecording(width, height)
    try {
        draw(Canvas(canvas))
    } finally {
        picture.endRecording()
    }
    return AndroidThreadShareImageRecording(picture, width, height)
}

private class AndroidThreadShareImageRecording(
    private val picture: Picture,
    override val width: Int,
    override val height: Int,
) : ThreadShareImageRecording {
    override fun rasterise(checkCancelled: () -> Unit): ImageBitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && picture.requiresHardwareAcceleration()) {
            // A hardware bitmap (an avatar Coil decoded to the GPU) cannot play back on a software
            // canvas; the platform renders such a picture through the GPU and copies the pixels back.
            checkCancelled()
            return Bitmap.createBitmap(picture, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        for (band in threadShareImageRasterBands(height)) {
            checkCancelled()
            val saved = canvas.save()
            canvas.clipRect(0, band.first, width, band.last + 1)
            canvas.drawPicture(picture)
            canvas.restoreToCount(saved)
        }
        return bitmap.asImageBitmap()
    }

    // A Picture holds no handle that needs closing; the GC frees it.
    override fun release() = Unit
}
