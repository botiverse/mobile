package build.raft.app.image

import coil3.ImageLoader
import coil3.PlatformContext
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSBundle
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfFile
import platform.UIKit.UIImage
import platform.UIKit.UIImagePNGRepresentation
import platform.posix.memcpy

/**
 * `assets://<path>`, ported from `KuiklyRenderComponentExpandHandler.slock_bundledImageForAssetURLString`:
 * main bundle file, then the kmmresource MR bundle (`build.raft.app.resources.MR.bundle`, which
 * flattens `/` to `+` and may use a Contents/Resources layout), then the asset catalog.
 */
internal actual suspend fun raftReadBundledImageAsset(context: PlatformContext, path: String): ByteArray? {
    val relativePath = path.trimStart('/')
    if (relativePath.isEmpty()) return null
    val fileManager = NSFileManager.defaultManager
    NSBundle.mainBundle.resourcePath?.let { root ->
        val candidate = "$root/$relativePath"
        if (fileManager.fileExistsAtPath(candidate)) readRaftFile(candidate)?.let { return it }
    }
    raftMrBundleAssetPath(relativePath)?.let { candidate -> readRaftFile(candidate)?.let { return it } }
    val catalogName = relativePath.substringBeforeLast('.').replace('/', '-')
    return UIImage.imageNamed(catalogName)?.let { UIImagePNGRepresentation(it) }?.toRaftByteArray()
}

internal actual fun raftImageDiskCacheDirectory(context: PlatformContext): Path? {
    @Suppress("UNCHECKED_CAST")
    val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true) as List<String>
    return caches.firstOrNull()?.let { "$it/raft_image_cache".toPath() }
}

private val raftMrResourceBundle: NSBundle? by lazy {
    NSBundle.mainBundle.URLForResource("build.raft.app.resources.MR", withExtension = "bundle")
        ?.let { url: NSURL -> NSBundle.bundleWithURL(url) }
}

private fun raftMrBundleAssetPath(relativePath: String): String? {
    val bundle = raftMrResourceBundle ?: return null
    val ext = relativePath.substringAfterLast('.', "").takeIf { it.isNotEmpty() && '/' !in it }
    val stem = if (ext != null) relativePath.removeSuffix(".$ext") else relativePath
    val names = listOf(stem.replace('/', '+'), stem, stem.substringAfterLast('/'))
    val dirs = listOfNotNull("${bundle.bundlePath}/Contents/Resources", bundle.resourcePath, bundle.bundlePath)
    val fileManager = NSFileManager.defaultManager
    for (name in names) {
        bundle.pathForResource(name, ofType = ext)?.let { return it }
        val fileName = if (ext != null) "$name.$ext" else name
        for (dir in dirs) {
            val candidate = "$dir/$fileName"
            if (fileManager.fileExistsAtPath(candidate)) return candidate
        }
    }
    return null
}

@OptIn(BetaInteropApi::class)
private fun readRaftFile(path: String): ByteArray? = NSData.dataWithContentsOfFile(path)?.toRaftByteArray()

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toRaftByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val out = ByteArray(size)
    out.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    return out
}

/** iOS has no hardware-bitmap config: Coil's defaults apply unchanged. */
internal actual fun ImageLoader.Builder.raftPlatformImageLoaderDefaults(): ImageLoader.Builder = this
