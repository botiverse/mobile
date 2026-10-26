package build.raft.app.image

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.allowHardware
import coil3.serviceLoaderEnabled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Path
import okio.Path.Companion.toOkioPath

/** `assets://<path>` resolves to the APK assets root. */
internal actual suspend fun raftReadBundledImageAsset(context: PlatformContext, path: String): ByteArray? =
    withContext(Dispatchers.IO) {
        runCatching { context.assets.open(path).use { input -> input.readBytes() } }.getOrNull()
    }

internal actual fun raftImageDiskCacheDirectory(context: PlatformContext): Path? =
    context.cacheDir?.resolve("raft_image_cache")?.toOkioPath()

/**
 * Software bitmaps only: see the expect declaration (hardware bitmaps crash software canvases).
 *
 * Coil's service loader is off: coil-gif registers its decoder through it, which would animate
 * every GIF (thumbnails, avatars) and keep it out of the memory cache. The Raft loader adds the
 * decoders it wants explicitly (SVG, and coil-gif only behind [RaftAnimatedImageDecoderFactory]'s
 * per-request opt-in); coil-svg's service entry, the only other one, duplicates that SVG decoder.
 */
internal actual fun ImageLoader.Builder.raftPlatformImageLoaderDefaults(): ImageLoader.Builder =
    allowHardware(false).serviceLoaderEnabled(false)
