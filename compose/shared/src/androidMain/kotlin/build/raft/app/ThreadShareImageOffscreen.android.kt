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
            return rasteriseOnGpu()
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

    /**
     * The GPU fallback: one hardware bitmap the size of the image, copied to a software one. The
     * GPU draws nothing past its texture limit (and says nothing), so a larger image fails here
     * with its reason in the log rather than coming back blank.
     */
    private fun rasteriseOnGpu(): ImageBitmap {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) error("share image GPU fallback needs API 28")
        if (!threadShareImageGpuFallbackFits(width, height)) {
            val reason = "share image ${width}x$height needs the GPU (hardware bitmap) and is past its ${ThreadShareImageGpuMaxSidePx}px texture limit"
            raftLogWarn(ThreadShareImageLogTag, reason)
            error(reason)
        }
        raftLogInfo(ThreadShareImageLogTag, "share image ${width}x$height rasterised on the GPU (hardware bitmap in its rows)")
        val hardware = Bitmap.createBitmap(picture, width, height, Bitmap.Config.HARDWARE)
        try {
            return checkNotNull(hardware.copy(Bitmap.Config.ARGB_8888, false)) { "share image GPU copy failed" }.asImageBitmap()
        } finally {
            hardware.recycle()
        }
    }

    // A Picture holds no handle that needs closing; the GC frees it.
    override fun release() = Unit
}

/** The largest side the GPU fallback draws: 4096 px, the smallest max texture size the app meets. */
internal const val ThreadShareImageGpuMaxSidePx = 4096

/** Whether a [width] x [height] share image may use the GPU fallback (both sides within its texture limit). */
internal fun threadShareImageGpuFallbackFits(width: Int, height: Int): Boolean =
    width in 1..ThreadShareImageGpuMaxSidePx && height in 1..ThreadShareImageGpuMaxSidePx

private const val ThreadShareImageLogTag = "RaftShareImage"
