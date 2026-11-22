package build.raft.app.image

import androidx.compose.ui.graphics.ImageBitmap
import build.raft.app.compat.ui.toRaftImageBitmap
import build.raft.app.raftLogWarn
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.intercept.Interceptor
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import coil3.svg.SvgDecoder
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val RAFT_IMAGE_DISK_CACHE_BYTES = 128L * 1024 * 1024
private const val RAFT_IMAGE_MEMORY_CACHE_PERCENT = 0.2
private const val RAFT_IMAGE_LOADER_LOG_TAG = "RaftImageLoader"
private const val RAFT_AVATAR_PREWARM_DEFAULT_MAX = 64

/**
 * Shared decode bound for http(s) avatars and server logos across every slot size (19dp–51dp) and
 * startup disk-to-memory prewarm (#proj-mobile:#290): large enough for 3x retina 51dp tiles while
 * sharing one [MemoryCache.Key] across Home, Activity, Thread, and the iOS native navigation bar.
 */
internal val RaftAvatarDecodeSize: Size = Size(256, 256)

private val raftImageLoaderInstalled = atomic(false)
private val raftStartupAvatarPrewarmStarted = atomic(false)
private val raftPrewarmLock = SynchronizedObject()
private var raftPrewarmJob: Job? = null
private var raftPrewarmGeneration = 0L
private val raftPrewarmScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** The loader [raftBuildImageLoader] built last (the singleton), for [raftClearImageCaches]. */
private val raftBuiltImageLoader = atomic<ImageLoader?>(null)

/** The installed Raft loader, or null before the first page host built it. */
internal fun raftCurrentImageLoader(): ImageLoader? = raftBuiltImageLoader.value

/**
 * Synchronous frame-1 Coil memory-cache lookup for an http(s) avatar / server logo keyed by
 * [cacheKey] ([raftNetworkImageCacheKey]). Returns the decoded [ImageBitmap] immediately when
 * already in memory (including after startup disk-to-memory prewarm), or null on a miss.
 */
internal fun raftAvatarMemoryCacheBitmap(cacheKey: String): ImageBitmap? {
    val loader = raftBuiltImageLoader.value ?: return null
    val memoryCache = loader.memoryCache ?: return null
    val entry = memoryCache[MemoryCache.Key(cacheKey)] ?: return null
    return runCatching { entry.image.toRaftImageBitmap() }.getOrNull()
}

/**
 * Warms Coil's memory cache from Coil's on-disk cache for [urls] without touching the network
 * (`CachePolicy.DISABLED` network, `CachePolicy.READ_ONLY` disk). Returns how many images were
 * newly promoted from disk into memory.
 */
internal suspend fun raftPrewarmCachedImagesFromDisk(
    context: PlatformContext,
    urls: Collection<String>,
    maxCount: Int = RAFT_AVATAR_PREWARM_DEFAULT_MAX,
    imageLoader: ImageLoader? = null,
): Int {
    if (urls.isEmpty() || maxCount <= 0) return 0
    return withContext(Dispatchers.Default) {
        val loader =
            imageLoader ?: run {
                raftInstallImageLoader()
                SingletonImageLoader.get(context)
            }
        val memoryCache = loader.memoryCache
        val seenKeys = LinkedHashSet<String>()
        var promoted = 0
        for (rawUrl in urls) {
            ensureActive()
            if (seenKeys.size >= maxCount) break
            val url = rawUrl.trim()
            if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) continue
            val cacheKey = raftNetworkImageCacheKey(url)
            if (!seenKeys.add(cacheKey)) continue
            if (memoryCache?.get(MemoryCache.Key(cacheKey)) != null) continue
            val request =
                ImageRequest.Builder(context)
                    .data(url)
                    .size(RaftAvatarDecodeSize)
                    .scale(Scale.FIT)
                    .precision(Precision.INEXACT)
                    .memoryCacheKey(cacheKey)
                    .diskCacheKey(cacheKey)
                    .networkCachePolicy(CachePolicy.DISABLED)
                    .diskCachePolicy(CachePolicy.READ_ONLY)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .build()
            val result =
                try {
                    loader.execute(request)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                }
            if (result is SuccessResult) promoted += 1
        }
        promoted
    }
}

/**
 * One-shot startup prewarm on [Dispatchers.Default]: promotes recently used avatars and server
 * logos from Coil's disk cache into its memory cache so cold-start frame 1 hits synchronously.
 */
internal fun raftScheduleStartupAvatarPrewarm(
    context: PlatformContext,
    urlsProvider: () -> Collection<String>,
) {
    val jobToStart =
        synchronized(raftPrewarmLock) {
            if (!raftStartupAvatarPrewarmStarted.compareAndSet(expect = false, update = true)) return
            val generation = raftPrewarmGeneration
            raftPrewarmScope.launch(start = CoroutineStart.LAZY) {
                val urls =
                    try {
                        urlsProvider()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        emptyList()
                    }
                if (urls.isEmpty()) {
                    synchronized(raftPrewarmLock) {
                        if (raftPrewarmGeneration == generation) {
                            raftStartupAvatarPrewarmStarted.value = false
                        }
                    }
                    return@launch
                }
                try {
                    raftPrewarmCachedImagesFromDisk(context, urls)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    Unit
                }
            }.also { raftPrewarmJob = it }
        }
    jobToStart.start()
}

/**
 * Drops every decoded and on-disk image of the previous account: called when the session is
 * cleared (logout, forced sign-out) or replaced by a different account. Image URLs can be
 * account-scoped (attachments, avatars), so they must not survive into the next session.
 */
suspend fun raftClearImageCaches() {
    RaftSentAttachmentLocalImages.registry.clear()
    build.raft.app.raftFilePreviewAttachmentUrlCache().clear()
    val runningPrewarm =
        synchronized(raftPrewarmLock) {
            raftPrewarmGeneration += 1L
            raftStartupAvatarPrewarmStarted.value = false
            raftPrewarmJob.also { raftPrewarmJob = null }
        }
    runCatching { runningPrewarm?.cancelAndJoin() }
    val loader = raftBuiltImageLoader.value ?: return
    withContext(Dispatchers.Default) {
        runCatching { loader.memoryCache?.clear() }
        runCatching { loader.diskCache?.clear() }
    }
}

/**
 * Installs the Raft singleton Coil [ImageLoader] (idempotent, never throws). Must run before the
 * first image request: [build.raft.app.compat.pager.RaftPagerHost] calls it when a page host is
 * created, and hosts may call it earlier at startup.
 *
 * If something already created Coil's default singleton (a `SingletonImageLoader.get` before this
 * call, e.g. a Robolectric test sharing the JVM), Coil's `setSafe` refuses with an
 * [IllegalStateException]; the install is then skipped with one log line instead of crashing.
 * Coil 3 has no public "is the default loader created" check, so the exception is the signal.
 */
fun raftInstallImageLoader() {
    if (!raftImageLoaderInstalled.compareAndSet(expect = false, update = true)) return
    try {
        SingletonImageLoader.setSafe { context -> raftBuildImageLoader(context) }
    } catch (_: IllegalStateException) {
        raftLogWarn(RAFT_IMAGE_LOADER_LOG_TAG, "image_loader_install_skipped reason=singleton_already_created")
    }
}

/**
 * Test-only: forgets that the Raft loader was installed so a test can start from (and restore) a
 * clean process state. Pair with Coil's `SingletonImageLoader.reset()`. Never call from app code.
 */
internal fun resetRaftImageLoaderForTest() {
    synchronized(raftPrewarmLock) {
        raftPrewarmGeneration += 1L
        raftPrewarmJob?.cancel()
        raftPrewarmJob = null
        raftStartupAvatarPrewarmStarted.value = false
    }
    raftImageLoaderInstalled.value = false
    raftBuiltImageLoader.value = null
}

/**
 * The Raft image loader: Coil 3 core + SVG decoding (+ opt-in animated GIF/WebP, see
 * [raftAnimatedImage]), with Raft's own fetchers for http(s)
 * (Ktor), `assets://`, inline SVG and managed avatar models (see [RaftImageSource]).
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
            // Ahead of the static decoders, but only for requests that opted in (the image viewer).
            raftPlatformAnimatedImageDecoderFactory()?.let { add(RaftAnimatedImageDecoderFactory(it)) }
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
