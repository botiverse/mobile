package build.raft.app.image

import androidx.compose.runtime.Composable
import build.raft.app.data.RaftDesktopPaths
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.decode.Decoder
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

internal actual fun ImageLoader.Builder.raftPlatformImageLoaderDefaults(): ImageLoader.Builder = this

// TODO(desktop): animated GIF / WebP play as their first frame (iOS has a Skia codec player).
internal actual fun raftPlatformAnimatedImageDecoderFactory(): Decoder.Factory? = null

@Composable
internal actual fun RaftImageAnimationEffect(image: coil3.Image?, running: Boolean) = Unit
