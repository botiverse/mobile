package build.raft.app.image

import build.raft.app.data.SlockAvatarMountedRenderDescriptor
import build.raft.app.network.SharedBinaryDownloader
import build.raft.app.network.SharedDownloadedBytes
import build.raft.app.network.binaryDownloadUrlDiagnostics
import build.raft.app.slockLogInfo
import build.raft.app.slockLogWarn
import coil3.Extras
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.getExtra
import coil3.request.Options
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import build.raft.kuiklybase.datetime.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import okio.Buffer

private const val LOG_TAG = "RaftImageLoad"
private const val SVG_MIME = "image/svg+xml"

/**
 * Host-provided reader for managed avatar bytes (`slock-avatar-render-v1:` models). Android maps
 * this to `SlockAndroidAvatarCacheProcess.beginRender`, iOS to `SlockIosAvatarCacheHost`.
 * Returning null fails the load (the avatar falls back to its placeholder / url candidate).
 */
fun interface RaftManagedImageReader {
    suspend fun open(descriptor: SlockAvatarMountedRenderDescriptor): RaftManagedImageRead?
}

/**
 * One leased render read. [onDecoded] reports the decoded size back to the host's avatar cache
 * (the Kuikly adapter's `registerDecoded`); return false to fail the load. [close] always runs
 * once the load is over (the delivery lease).
 */
class RaftManagedImageRead(
    val bytes: ByteArray,
    val contentType: String?,
    val onDecoded: (width: Int, height: Int, scaleIdentity: String) -> Boolean = { _, _, _ -> true },
    val close: () -> Unit = {},
)

/** Host hooks of the Raft image loader. */
object RaftImageHost {
    /**
     * TODO(cmp-switch): installed by the platform hosts (androidApp / iosApp, group 6). While null,
     * managed avatar models fail and callers fall back to their url candidate / placeholder.
     */
    var managedImageReader: RaftManagedImageReader? = null
}

/** Platform read of a bundled `assets://` file (Android APK assets; iOS main + MR bundles). */
internal expect suspend fun raftReadBundledImageAsset(context: PlatformContext, path: String): ByteArray?

/** Directory of the Raft image disk cache, or null to run without one. */
internal expect fun raftImageDiskCacheDirectory(context: PlatformContext): okio.Path?

internal fun raftImageMimeTypeForPath(path: String): String? =
    when (path.substringBefore('?').substringAfterLast('.', "").lowercase()) {
        "svg" -> SVG_MIME
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "heic" -> "image/heic"
        else -> null
    }

private fun ByteArray.toImageSource(options: Options): ImageSource =
    ImageSource(source = Buffer().write(this), fileSystem = options.fileSystem)

/** Fetches every [RaftImageSource]. */
internal class RaftImageFetcherFactory : Fetcher.Factory<RaftImageSource> {
    override fun create(data: RaftImageSource, options: Options, imageLoader: ImageLoader): Fetcher =
        when (data) {
            is RaftImageSource.Network -> RaftNetworkImageFetcher(data, options, imageLoader)
            is RaftImageSource.BundledAsset -> RaftBundledAssetFetcher(data, options)
            is RaftImageSource.InlineSvg ->
                object : Fetcher {
                    override suspend fun fetch(): FetchResult =
                        SourceFetchResult(data.text.encodeToByteArray().toImageSource(options), SVG_MIME, DataSource.MEMORY)
                }
            is RaftImageSource.ManagedAvatar -> RaftManagedAvatarFetcher(data, options)
        }
}

/**
 * http(s) images over NetworkKMM with a Coil disk cache. Replaces Coil's ktor/okhttp network
 * fetcher (not available here) and the Kuikly adapters' `SharedBinaryDownloader.fetchBytes` path.
 * Requests carry the same headers as before (none beyond `Accept`: avatar / attachment image URLs
 * are public or pre-signed). Concurrent loads of the same content share one download.
 */
private class RaftNetworkImageFetcher(
    private val data: RaftImageSource.Network,
    private val options: Options,
    private val imageLoader: ImageLoader,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val diskKey = options.diskCacheKey ?: data.cacheKey
        val diskCache = imageLoader.diskCache
        val expires = raftNetworkImageExpires(data.url)
        var stale: Pair<DiskCache.Snapshot, RaftImageDiskMetadata>? = null
        if (diskCache != null && options.diskCachePolicy.readEnabled) {
            val snapshot = runCatching { diskCache.openSnapshot(diskKey) }.getOrNull()
            if (snapshot != null) {
                val metadata =
                    RaftImageDiskMetadata.decode(
                        runCatching { diskCache.fileSystem.read(snapshot.metadata) { readUtf8() } }.getOrNull()
                    )
                if (raftImageDiskEntryFresh(metadata, expires, raftImageNowEpochMillis())) {
                    return SourceFetchResult(
                        source = ImageSource(snapshot.data, diskCache.fileSystem, diskKey, snapshot),
                        mimeType = metadata.mimeType,
                        dataSource = DataSource.DISK,
                    )
                }
                // Expired external entry: fetch again, keep it only as the offline fallback.
                stale = snapshot to metadata
            }
        }
        val downloaded =
            try {
                if (!options.networkCachePolicy.readEnabled) {
                    error("Network reads disabled for this request")
                }
                RaftImageByteFlights.fetch(data.cacheKey, data.url)
            } catch (failure: Throwable) {
                val fallback = stale
                if (failure is CancellationException || fallback == null || diskCache == null) {
                    stale?.first?.close()
                    throw failure
                }
                return SourceFetchResult(
                    source = ImageSource(fallback.first.data, diskCache.fileSystem, diskKey, fallback.first),
                    mimeType = fallback.second.mimeType,
                    dataSource = DataSource.DISK,
                )
            }
        stale?.first?.close()
        val sniffedMimeType = raftSniffImageMimeType(downloaded.bytes)
        val mimeType =
            downloaded.contentType?.takeIf { it.startsWith("image/", ignoreCase = true) }
                ?: sniffedMimeType
                ?: raftImageMimeTypeForPath(data.url)
        if (sniffedMimeType == null) {
            // A 2xx body that is not an image (captive portal HTML, proxy error page): never
            // commit it under the image's key; the decoders still get a chance at the bytes.
            slockLogWarn(
                LOG_TAG,
                "source=network status=non_image_body bytes=${downloaded.bytes.size} ${binaryDownloadUrlDiagnostics(data.url)}"
            )
        } else if (diskCache != null && options.diskCachePolicy.writeEnabled) {
            val editor = runCatching { diskCache.openEditor(diskKey) }.getOrNull()
            if (editor != null) {
                runCatching {
                    diskCache.fileSystem.write(editor.data) { write(downloaded.bytes) }
                    diskCache.fileSystem.write(editor.metadata) {
                        writeUtf8(RaftImageDiskMetadata(mimeType, raftImageNowEpochMillis()).encode())
                    }
                    editor.commit()
                }.onFailure {
                    runCatching { editor.abort() }
                }
            }
        }
        return SourceFetchResult(
            source = downloaded.bytes.toImageSource(options),
            mimeType = mimeType,
            dataSource = DataSource.NETWORK,
        )
    }
}

internal fun raftImageNowEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()

/** Single flight per content key for network bytes (the Kuikly adapter's `networkByteFlights`). */
private object RaftImageByteFlights {
    private val lock = SynchronizedObject()
    private val inFlight = mutableMapOf<String, Deferred<SharedDownloadedBytes>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun fetch(key: String, url: String): SharedDownloadedBytes {
        val deferred =
            synchronized(lock) {
                inFlight[key] ?: scope.async { SharedBinaryDownloader.fetchBytes(url) }.also { started ->
                    inFlight[key] = started
                    started.invokeOnCompletion {
                        synchronized(lock) {
                            if (inFlight[key] === started) inFlight.remove(key)
                        }
                    }
                }
            }
        return try {
            deferred.await()
        } catch (failure: Throwable) {
            slockLogWarn(
                LOG_TAG,
                "source=network status=failed type=${failure::class.simpleName} ${binaryDownloadUrlDiagnostics(url)}"
            )
            throw failure
        }
    }
}

private class RaftBundledAssetFetcher(
    private val data: RaftImageSource.BundledAsset,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val bytes = raftReadBundledImageAsset(options.context, data.path)
        if (bytes == null) {
            // Bundled asset paths carry no user data, so log the path itself: a missing packaged
            // asset is otherwise invisible (task #335).
            slockLogWarn(LOG_TAG, "source=assets status=failed path=${data.path}")
            error("Bundled image asset not found: ${data.path}")
        }
        return SourceFetchResult(
            source = bytes.toImageSource(options),
            mimeType = raftImageMimeTypeForPath(data.path),
            dataSource = DataSource.DISK,
        )
    }
}

/** Carries the leased managed read from the fetcher back to [RaftManagedImageCommitInterceptor]. */
internal class RaftManagedReadHolder {
    var read: RaftManagedImageRead? = null
}

internal val RaftManagedReadHolderKey = Extras.Key<RaftManagedReadHolder?>(default = null)

private class RaftManagedAvatarFetcher(
    private val data: RaftImageSource.ManagedAvatar,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val reader = RaftImageHost.managedImageReader
        val read = reader?.open(data.descriptor)
        if (read == null) {
            slockLogInfo(
                LOG_TAG,
                "source=managed_avatar status=render_read_unavailable reader=${if (reader == null) "none" else "present"} " +
                    "managedModelHash=${data.model.hashCode().toString(16)}"
            )
            error("Avatar render read failed")
        }
        val holder = options.getExtra(RaftManagedReadHolderKey)
        if (holder != null) holder.read = read else read.close()
        return SourceFetchResult(
            source = read.bytes.toImageSource(options),
            mimeType = read.contentType?.takeIf { it.isNotBlank() },
            dataSource = DataSource.DISK,
        )
    }
}
