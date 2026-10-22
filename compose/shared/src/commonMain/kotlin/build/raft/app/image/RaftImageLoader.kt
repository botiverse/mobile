package build.raft.app.image

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.intercept.Interceptor
import coil3.memory.MemoryCache
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.svg.SvgDecoder
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val RAFT_IMAGE_DISK_CACHE_BYTES = 128L * 1024 * 1024
private const val RAFT_IMAGE_MEMORY_CACHE_PERCENT = 0.2

private val raftImageLoaderInstalled = atomic(false)

/** The loader [raftBuildImageLoader] built last (the singleton), for [raftClearImageCaches]. */
private val raftBuiltImageLoader = atomic<ImageLoader?>(null)

/** The installed Raft loader, or null before the first page host built it. */
internal fun raftCurrentImageLoader(): ImageLoader? = raftBuiltImageLoader.value

/**
 * Drops every decoded and on-disk image of the previous account: called when the session is
 * cleared (logout, forced sign-out) or replaced by a different account. Image URLs can be
 * account-scoped (attachments, avatars), so they must not survive into the next session.
 */
suspend fun raftClearImageCaches() {
    RaftSentAttachmentLocalImages.registry.clear()
    val loader = raftBuiltImageLoader.value ?: return
    withContext(Dispatchers.Default) {
        runCatching { loader.memoryCache?.clear() }
        runCatching { loader.diskCache?.clear() }
    }
}

/**
 * Installs the Raft singleton Coil [ImageLoader] (idempotent). Must run before the first image
 * request: [build.raft.app.compat.pager.RaftPagerHost] calls it when a page host is created, and
 * hosts may call it earlier at startup.
 */
fun raftInstallImageLoader() {
    if (!raftImageLoaderInstalled.compareAndSet(expect = false, update = true)) return
    SingletonImageLoader.setSafe { context -> raftBuildImageLoader(context) }
}

/**
 * The Raft image loader: Coil 3 core + SVG decoding, with Raft's own fetchers for http(s)
 * (NetworkKMM), `assets://`, inline SVG and managed avatar models (see [RaftImageSource]).
 * Memory cache keys are the task #403 content fingerprints plus Coil's render params (size).
 */
fun raftBuildImageLoader(context: PlatformContext): ImageLoader {
    val memoryCache = MemoryCache.Builder().maxSizePercent(context, RAFT_IMAGE_MEMORY_CACHE_PERCENT).build()
    val diskDirectory = runCatching { raftImageDiskCacheDirectory(context) }.getOrNull()
    val loader = ImageLoader.Builder(context)
        .memoryCache { memoryCache }
        .apply {
            if (diskDirectory != null) {
                diskCache { DiskCache.Builder().directory(diskDirectory).maxSizeBytes(RAFT_IMAGE_DISK_CACHE_BYTES).build() }
            }
        }
        .raftPlatformImageLoaderDefaults()
        .components {
            add(RaftManagedImageCommitInterceptor(memoryCache))
            add(RaftImageSourceMapper)
            add(RaftImageSourceKeyer)
            add(RaftImageFetcherFactory())
            add(SvgDecoder.Factory())
        }
        .build()
    raftBuiltImageLoader.value = loader
    return loader
}

/**
 * Platform defaults for the Raft loader. Android: never decode into `Bitmap.Config.HARDWARE`.
 * Every Raft image is drawn through Compose (`drawImage` of a raw [androidx.compose.ui.graphics.ImageBitmap]
 * or a Coil painter), and a hardware bitmap throws `IllegalArgumentException: Software rendering
 * doesn't support hardware bitmaps` as soon as the view is drawn into a software `Canvas`
 * (view-to-bitmap snapshots, share/screenshot paths, some OEM accessibility services, the visual
 * capture harness's `root.draw(Canvas(bitmap))`). Hardware bitmaps also cannot be read back
 * (`readPixels`), which raw-ImageBitmap callers may do.
 */
internal expect fun ImageLoader.Builder.raftPlatformImageLoaderDefaults(): ImageLoader.Builder

/**
 * Managed avatar bookkeeping around a load:
 *  - task #403 commit validation: the decoded entry is keyed by content, so when a descriptor of a
 *    newer commit generation arrives for the same content the older decoded entries are dropped
 *    (same-generation / older-generation descriptors keep hitting);
 *  - hands the fetcher a holder for its render read and, after decode, reports the decoded size
 *    to the host (`registerDecoded`) and closes the lease.
 */
internal class RaftManagedImageCommitInterceptor(
    private val memoryCache: MemoryCache,
) : Interceptor {
    private val lock = SynchronizedObject()
    private val latestGenerationByKey = mutableMapOf<String, Long>()

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val model = chain.request.data as? String ?: return chain.proceed()
        val source = raftImageSourceOf(model) as? RaftImageSource.ManagedAvatar ?: return chain.proceed()
        val key = source.cacheKey
        val generation = source.descriptor.commitIdentity.commitGeneration
        val stale =
            synchronized(lock) {
                val known = latestGenerationByKey[key]
                if (known == null || generation > known) {
                    if (latestGenerationByKey.size > 4096) latestGenerationByKey.clear()
                    latestGenerationByKey[key] = generation
                }
                known != null && generation > known
            }
        if (stale) {
            memoryCache.keys.filter { it.key == key }.forEach(memoryCache::remove)
        }
        val holder = RaftManagedReadHolder()
        val request = chain.request.newBuilder().apply { extras[RaftManagedReadHolderKey] = holder }.build()
        try {
            val result = chain.withRequest(request).proceed()
            val read = holder.read
            if (read != null && result is SuccessResult) {
                val image = result.image
                val registered =
                    read.onDecoded(
                        image.width.coerceAtLeast(0),
                        image.height.coerceAtLeast(0),
                        chain.size.toString(),
                    )
                if (!registered) {
                    memoryCache.keys.filter { it.key == key }.forEach(memoryCache::remove)
                    return ErrorResult(null, result.request, IllegalStateException("Failed to register decoded avatar"))
                }
            }
            return result
        } finally {
            holder.read?.close?.invoke()
            holder.read = null
        }
    }
}
