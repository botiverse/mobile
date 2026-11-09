package build.raft.app.image

import androidx.compose.runtime.Composable
import build.raft.app.data.RaftDesktopPaths
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.decode.Decoder
import coil3.request.maxBitmapSize
import coil3.size.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Path
import okio.Path.Companion.toOkioPath

/** `assets://<path>`: the bundled assets are on the classpath root (src/commonMain/assets). */
internal actual suspend fun raftReadBundledImageAsset(context: PlatformContext, path: String): ByteArray? =
    withContext(Dispatchers.IO) {
        Thread.currentThread().contextClassLoader?.getResourceAsStream(path.trimStart('/'))?.use { it.readBytes() }
    }

internal actual fun raftImageDiskCacheDirectory(context: PlatformContext): Path? =
    RaftDesktopPaths.cacheDir.resolve("raft_image_cache").toOkioPath()

/**
 * Decoded images at most [RAFT_DESKTOP_MAX_BITMAP_EDGE_PX] on the long edge (Coil's default is
 * 4096). Raft's painters ask Coil for the original size (`rememberAsyncImagePainter` without a
 * size resolver), so a 12 MP photo or a large avatar upload was decoded to ~48 MB of Skia pixels
 * per image and kept in the memory cache. 2048 px still covers a message image (at most 360 dp,
 * 720 px on Retina) and the full-window viewer on a typical display.
 */
internal actual fun ImageLoader.Builder.raftPlatformImageLoaderDefaults(): ImageLoader.Builder =
    maxBitmapSize(Size(RAFT_DESKTOP_MAX_BITMAP_EDGE_PX, RAFT_DESKTOP_MAX_BITMAP_EDGE_PX))

internal const val RAFT_DESKTOP_MAX_BITMAP_EDGE_PX = 2048

// TODO(desktop): animated GIF / WebP play as their first frame (iOS has a Skia codec player).
internal actual fun raftPlatformAnimatedImageDecoderFactory(): Decoder.Factory? = null

@Composable
internal actual fun RaftImageAnimationEffect(image: coil3.Image?, running: Boolean) = Unit
