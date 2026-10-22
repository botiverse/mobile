package build.raft.app.image

import build.raft.app.data.RaftSharedPlatformFileResolverProvider
import build.raft.app.raftLogInfo
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val LOG_TAG = "RaftSentImageLocal"

/** Image model for an attachment this device just sent: `raft-sent-attachment:<attachmentId>`. */
internal const val RaftSentAttachmentModelPrefix = "raft-sent-attachment:"

internal fun raftSentAttachmentModel(attachmentId: String): String = RaftSentAttachmentModelPrefix + attachmentId

internal fun raftSentAttachmentCacheKey(attachmentId: String): String = "raft-img-v1:sent-attachment:$attachmentId"

/**
 * Byte storage for seeded sent images. Production writes into the Raft Coil disk cache (bounded LRU,
 * cleared with the account in [raftClearImageCaches]); tests use an in-memory store.
 */
internal interface RaftSentAttachmentImageStore {
    suspend fun write(key: String, bytes: ByteArray, mimeType: String?): Boolean
    suspend fun read(key: String): RaftSentAttachmentImageBytes?
    fun remove(key: String)
}

internal class RaftSentAttachmentImageBytes(val bytes: ByteArray, val mimeType: String?)

/** A local image this device uploaded: the server attachment id and the platform file handle. */
internal data class RaftSentAttachmentLocalSource(
    val attachmentId: String,
    val fileRef: String,
    val mimeType: String?,
    val sizeBytes: Long = 0L,
)

/**
 * Session map from server attachment id to the local bytes of an image this device just uploaded,
 * so the canonical message renders the sender's own image from the local copy (as web keeps its
 * `localPreviewUrl` through the optimistic -> persisted handoff) instead of downloading it again.
 *
 * The composer's picker/camera/editor files are retired right after the send succeeds, so the
 * bytes are copied into the image cache (not referenced by path). Registration is synchronous at
 * send admission, before any request, so a canonical echo that arrives first already resolves to
 * the local model; the fetcher waits for the copy to finish.
 *
 * Bounded: at most [maxEntries] ids (oldest evicted with their bytes); the bytes live in the
 * size-bounded disk cache. Cleared on account change with the other image caches.
 */
internal class RaftSentAttachmentLocalImageRegistry(
    private val maxEntries: Int,
    private val maxSeedBytes: Long,
    private val scope: CoroutineScope,
    private val readLocalFile: suspend (fileRef: String) -> ByteArray?,
    private val storeProvider: () -> RaftSentAttachmentImageStore?,
) {
    private val lock = SynchronizedObject()
    private val seeds = LinkedHashMap<String, Deferred<Boolean>>()
    private val registered = MutableStateFlow<Set<String>>(emptySet())

    /** Attachment ids with a local source (seeding or seeded). */
    val registeredAttachmentIds: StateFlow<Set<String>> = registered.asStateFlow()

    fun localModel(attachmentId: String): String? =
        attachmentId.takeIf { it.isNotBlank() && it in registered.value }?.let(::raftSentAttachmentModel)

    /** Per-attachment slice: emits only when this attachment's local model appears or goes away. */
    fun localModelFlow(attachmentId: String): Flow<String?> =
        registered.map { ids -> if (attachmentId.isNotBlank() && attachmentId in ids) raftSentAttachmentModel(attachmentId) else null }
            .distinctUntilChanged()

    /**
     * Registers [sources] and copies their bytes into the store in the background. Non-image and
     * oversized sources are skipped (they keep the remote path). Idempotent per attachment id.
     */
    fun register(sources: List<RaftSentAttachmentLocalSource>) {
        val admitted =
            sources.filter { source ->
                source.attachmentId.isNotBlank() &&
                    source.fileRef.isNotBlank() &&
                    source.mimeType.isRaftSeedableImageMime() &&
                    (source.sizeBytes <= 0L || source.sizeBytes <= maxSeedBytes)
            }
        if (admitted.isEmpty()) return
        val evicted = mutableListOf<String>()
        val started = mutableListOf<Pair<RaftSentAttachmentLocalSource, CompletableDeferred<Boolean>>>()
        synchronized(lock) {
            admitted.forEach { source ->
                if (seeds.containsKey(source.attachmentId)) return@forEach
                val deferred = CompletableDeferred<Boolean>()
                seeds[source.attachmentId] = deferred
                started += source to deferred
            }
            while (seeds.size > maxEntries) {
                val oldest = seeds.keys.first()
                seeds.remove(oldest)
                evicted += oldest
            }
            registered.value = seeds.keys.toSet()
        }
        evicted.forEach { id -> storeProvider()?.remove(raftSentAttachmentCacheKey(id)) }
        started.forEach { (source, deferred) ->
            scope.launch {
                val ok = runCatching { copyToStore(source) }.getOrDefault(false)
                deferred.complete(ok)
                if (!ok) forget(source.attachmentId, deferred)
                raftLogInfo(
                    LOG_TAG,
                    "sent_image_local_seed attachmentId=${source.attachmentId.take(8)} result=${if (ok) "stored" else "skipped"}"
                )
            }
        }
    }

    private suspend fun copyToStore(source: RaftSentAttachmentLocalSource): Boolean {
        val store = storeProvider() ?: return false
        val bytes = readLocalFile(source.fileRef) ?: return false
        if (bytes.isEmpty() || bytes.size.toLong() > maxSeedBytes) return false
        return store.write(raftSentAttachmentCacheKey(source.attachmentId), bytes, source.mimeType)
    }

    /** Waits (bounded) until every registered seed in [attachmentIds] has finished copying. */
    suspend fun awaitSeeded(attachmentIds: Collection<String>, timeoutMillis: Long) {
        val pending = synchronized(lock) { attachmentIds.mapNotNull { seeds[it] } }
        if (pending.isEmpty()) return
        withTimeoutOrNull(timeoutMillis) { pending.forEach { it.await() } }
    }

    /** Local bytes of a registered attachment; null (and the id forgotten) when they are gone. */
    suspend fun read(attachmentId: String): RaftSentAttachmentImageBytes? {
        val deferred = synchronized(lock) { seeds[attachmentId] } ?: return null
        if (!deferred.await()) return null
        val bytes = storeProvider()?.read(raftSentAttachmentCacheKey(attachmentId))
        if (bytes == null) forget(attachmentId, deferred)
        return bytes
    }

    private fun forget(attachmentId: String, expected: Deferred<Boolean>) {
        synchronized(lock) {
            if (seeds[attachmentId] !== expected) return
            seeds.remove(attachmentId)
            registered.value = seeds.keys.toSet()
        }
    }

    fun clear() {
        synchronized(lock) {
            seeds.clear()
            registered.value = emptySet()
        }
    }
}

private fun String?.isRaftSeedableImageMime(): Boolean {
    val mime = this?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    return mime.startsWith("image/")
}

/** Coil disk-cache backed store of the Raft image loader. */
private object RaftCoilSentAttachmentImageStore : RaftSentAttachmentImageStore {
    override suspend fun write(key: String, bytes: ByteArray, mimeType: String?): Boolean {
        val diskCache = raftCurrentImageLoader()?.diskCache ?: return false
        val editor = runCatching { diskCache.openEditor(key) }.getOrNull() ?: return false
        return runCatching {
            diskCache.fileSystem.write(editor.data) { write(bytes) }
            diskCache.fileSystem.write(editor.metadata) {
                writeUtf8(RaftImageDiskMetadata(mimeType, raftImageNowEpochMillis()).encode())
            }
            editor.commit()
            true
        }.getOrElse {
            runCatching { editor.abort() }
            false
        }
    }

    override suspend fun read(key: String): RaftSentAttachmentImageBytes? {
        val diskCache = raftCurrentImageLoader()?.diskCache ?: return null
        val snapshot = runCatching { diskCache.openSnapshot(key) }.getOrNull() ?: return null
        return snapshot.use {
            runCatching {
                val metadata =
                    RaftImageDiskMetadata.decode(
                        runCatching { diskCache.fileSystem.read(snapshot.metadata) { readUtf8() } }.getOrNull()
                    )
                RaftSentAttachmentImageBytes(
                    bytes = diskCache.fileSystem.read(snapshot.data) { readByteArray() },
                    mimeType = metadata.mimeType,
                )
            }.getOrNull()
        }
    }

    override fun remove(key: String) {
        runCatching { raftCurrentImageLoader()?.diskCache?.remove(key) }
    }
}

/** Process-wide registry used by the thread composer send path and the thread image renderer. */
internal object RaftSentAttachmentLocalImages {
    private const val MAX_ENTRIES = 64
    private const val MAX_SEED_BYTES = 32L * 1024 * 1024

    val registry =
        RaftSentAttachmentLocalImageRegistry(
            maxEntries = MAX_ENTRIES,
            maxSeedBytes = MAX_SEED_BYTES,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            readLocalFile = { fileRef ->
                RaftSharedPlatformFileResolverProvider.createBinding().resolver
                    ?.readFile(fileRef)
                    ?.takeIf { it.success }
                    ?.bytes
            },
            storeProvider = { RaftCoilSentAttachmentImageStore },
        )
}
