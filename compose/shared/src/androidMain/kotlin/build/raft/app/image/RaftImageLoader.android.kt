package build.raft.app.image

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.allowHardware
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Path
import okio.Path.Companion.toOkioPath

/** `assets://<path>` resolves to the APK assets root (where kmmresource packs MR assets). */
internal actual suspend fun raftReadBundledImageAsset(context: PlatformContext, path: String): ByteArray? =
    withContext(Dispatchers.IO) {
        runCatching { context.assets.open(path).use { input -> input.readBytes() } }.getOrNull()
    }

internal actual fun raftImageDiskCacheDirectory(context: PlatformContext): Path? =
    context.cacheDir?.resolve("raft_image_cache")?.toOkioPath()

/** Production: Coil defaults (hardware bitmaps allowed); tests may override, see [RaftImageLoaderTestOverrides]. */
internal actual fun ImageLoader.Builder.raftPlatformImageLoaderDefaults(): ImageLoader.Builder {
    val allowHardware = RaftImageLoaderTestOverrides.allowHardware ?: return this
    return allowHardware(allowHardware)
}

/**
 * Instrumentation-only switches for the Raft image loader. Never set in production code.
 *
 * Coil decodes into `Bitmap.Config.HARDWARE` by default, which is right for the app: every on-screen
 * draw is hardware accelerated, and the in-app capture paths (share capture, pixel probes) use
 * PixelCopy or GraphicsLayer recording. Connected tests that instead render the view tree with
 * `root.draw(Canvas(bitmap))` draw into a *software* Canvas, where any hardware bitmap (e.g. the
 * reaction sprite atlas drawn by `ThreadReactionSpriteGlyph`) throws `IllegalArgumentException:
 * Software rendering doesn't support hardware bitmaps` and kills the instrumentation process.
 * Such tests call [forceSoftwareBitmaps] before launching their activity.
 */
object RaftImageLoaderTestOverrides {
    /** null = Coil default. Read whenever a Raft loader is built. */
    @Volatile
    var allowHardware: Boolean? = null
        private set

    /**
     * Makes every Raft image decode as a software bitmap from now on in this process. The loader is a
     * process singleton that an earlier test may already have built (with hardware bitmaps and a
     * memory cache full of them), so it is rebuilt when the setting changes.
     */
    fun forceSoftwareBitmaps() {
        if (allowHardware == false) return
        allowHardware = false
        raftReinstallImageLoader()
    }

    /** Unit tests only: back to Coil defaults (does not rebuild the singleton). */
    internal fun resetForTest() {
        allowHardware = null
    }
}
