package build.raft.app.image

import coil3.PlatformContext
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
