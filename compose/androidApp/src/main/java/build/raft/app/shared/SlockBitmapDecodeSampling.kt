package build.raft.app.shared

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.InputStream
import kotlin.math.max

/** Longest decoded bitmap edge for in-app image rendering. */
internal const val SLOCK_MAX_BITMAP_DECODE_EDGE_PX = 4096

/**
 * Decoded pixel budget (4096 x 4096, about 64 MB as ARGB_8888).  A 12 MP camera photo still
 * decodes at full resolution; a 20 MP+ image is halved instead of exhausting a 256 MB heap.
 */
internal const val SLOCK_MAX_BITMAP_DECODE_PIXELS = 4096L * 4096L

/**
 * Power-of-two `inSampleSize` for a source of [sourceWidth] x [sourceHeight].
 *
 * The first loop keeps the decode at least as large as [targetEdgePx] (the requested view
 * edge).  The second loop enforces the hard ceilings even when that leaves the decode smaller
 * than the request: the request is a quality hint, the ceilings protect the process.
 */
internal fun slockBitmapDecodeSampleSize(
    sourceWidth: Int,
    sourceHeight: Int,
    targetEdgePx: Int,
    maxEdgePx: Int = SLOCK_MAX_BITMAP_DECODE_EDGE_PX,
    maxPixels: Long = SLOCK_MAX_BITMAP_DECODE_PIXELS,
): Int {
    if (sourceWidth <= 0 || sourceHeight <= 0) return 1
    var sampleSize = 1
    var sampledWidth = sourceWidth
    var sampledHeight = sourceHeight
    while (max(sampledWidth, sampledHeight) / 2 >= targetEdgePx) {
        sampleSize *= 2
        sampledWidth /= 2
        sampledHeight /= 2
    }
    while (
        max(sampledWidth, sampledHeight) > maxEdgePx ||
            sampledWidth.toLong() * sampledHeight.toLong() > maxPixels
    ) {
        sampleSize *= 2
        sampledWidth /= 2
        sampledHeight /= 2
    }
    return sampleSize.coerceAtLeast(1)
}

/**
 * Decodes an image from [openInput] without ever holding the encoded file in memory: one pass
 * reads the bounds, a second pass decodes with [slockBitmapDecodeSampleSize].  [openInput] is
 * called once per pass and must return a fresh stream (or null when the source is gone).
 */
internal fun slockDecodeSampledBitmap(
    openInput: () -> InputStream?,
    targetEdgePx: Int,
): Bitmap? {
    val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    // With inJustDecodeBounds the decoder always returns null; only a missing stream aborts.
    val boundsInput = openInput() ?: return null
    boundsInput.use { input -> BitmapFactory.decodeStream(input, null, boundsOptions) }
    val decodeOptions =
        BitmapFactory.Options().apply {
            if (boundsOptions.outWidth > 0 && boundsOptions.outHeight > 0) {
                inSampleSize =
                    slockBitmapDecodeSampleSize(boundsOptions.outWidth, boundsOptions.outHeight, targetEdgePx)
            }
        }
    return openInput()?.use { input -> BitmapFactory.decodeStream(input, null, decodeOptions) }
}
