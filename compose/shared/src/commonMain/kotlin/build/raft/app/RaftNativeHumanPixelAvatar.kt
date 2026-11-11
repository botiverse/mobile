package build.raft.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/**
 * A person's built pixel avatar, ready to draw: the composed 112×112 picture as one bitmap
 * ([RaftHumanPixelAvatarRaster]) and its background colour for the frame under it.
 */
@Immutable
internal class RaftHumanPixelAvatarPicture(
    val key: String,
    val background: Color,
    val bitmap: ImageBitmap,
)

private const val PictureCacheCapacity = 64

// One picture per look (keyed by the canonical recipe), kept in a small LRU: a look's pixels
// never change, and a list of messages shows the same few people again and again. Read on the
// composition thread only.
private val pictureCache = LinkedHashMap<String, RaftHumanPixelAvatarPicture>()

/** The picture a stored avatarUrl draws, or null when it is not a built avatar (web `PersonAvatar`). */
internal fun raftHumanPixelAvatarPicture(value: String?): RaftHumanPixelAvatarPicture? {
    val recipe = readRaftHumanPixelAvatarRecipe(value) ?: return null
    val key = serializeRaftHumanPixelAvatarRecipe(recipe)
    pictureCache.remove(key)?.let { cached ->
        pictureCache[key] = cached
        return cached
    }
    val composed = composeRaftHumanPixelAvatar(recipe)
    val picture =
        RaftHumanPixelAvatarPicture(
            key = key,
            background = Color(raftHumanPixelAvatarArgb(composed.background)),
            bitmap = composed.toRaster().toImageBitmap(),
        )
    pictureCache[key] = picture
    if (pictureCache.size > PictureCacheCapacity) pictureCache.remove(pictureCache.keys.first())
    return picture
}

/** One 1×1 rect per run of a colour in a row, on an opaque bitmap of the raster's size. */
private fun RaftHumanPixelAvatarRaster.toImageBitmap(): ImageBitmap {
    val bitmap = ImageBitmap(size, size)
    val canvas = Canvas(bitmap)
    val paint = Paint().apply { isAntiAlias = false }
    for (y in 0 until size) {
        var x = 0
        while (x < size) {
            val color = argb[y * size + x]
            var end = x + 1
            while (end < size && argb[y * size + end] == color) end++
            paint.color = Color(color)
            canvas.drawRect(x.toFloat(), y.toFloat(), end.toFloat(), y + 1f, paint)
            x = end
        }
    }
    return bitmap
}

/**
 * The face of a person's built pixel avatar: the whole picture at every size, filling the
 * avatar's inside (the frame, ink edge and corners are [RaftAppAvatar]'s), as the web's
 * `HumanPixelAvatar` (`image-rendering: pixelated`, no crop for small avatars).
 *
 * Scaled nearest-neighbour ([FilterQuality.None]) onto whole device pixels, never smoothed.
 */
@Composable
internal fun RaftNativeHumanPixelAvatar(
    picture: RaftHumanPixelAvatarPicture,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.fillMaxSize()) {
        drawRect(picture.background)
        drawImage(
            image = picture.bitmap,
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            filterQuality = FilterQuality.None,
        )
    }
}
