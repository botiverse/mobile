package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

// The publish check's screenshot on an Artifact card (botiverse/slock #13046
// `packages/web/src/apps/artifacts/artifactScreenshot.ts`, contract Remy 11-05): card `preview`
// `{ kind: "screenshot", desktop, phone: ObjectFileId | null }`, the bytes from
// `GET /api/objects/artifact/{card.id}/files/{fileId}` with the viewer's credentials
// (`private, max-age=60`; 300 a minute per user, then 429 RATE_LIMITED; 404 when not visible or
// the server's publish check is off). A screenshot that can't be read reads as none: the card shows
// without it, no spinner, no error.

/** web `MAX_KEPT`: the newest screenshots kept in memory. */
const val ARTIFACT_SCREENSHOT_MAX_KEPT: Int = 64

/** web `MAX_KEPT_BYTES`: at most this many bytes kept together. */
const val ARTIFACT_SCREENSHOT_MAX_KEPT_BYTES: Long = 32L * 1024 * 1024

/** web `FAILED_MS`: a failed read is remembered this long, so cards meanwhile don't ask again. */
const val ARTIFACT_SCREENSHOT_FAILED_MILLIS: Long = 30_000L

/** One screenshot is read only up to this many bytes (a screenshot is ~300 KiB). */
const val ARTIFACT_SCREENSHOT_MAX_FILE_BYTES: Int = 8 * 1024 * 1024

/** web `SCREENSHOT_TYPES`: the image types core keeps an object file as; any other answer is a failure. */
internal val ARTIFACT_SCREENSHOT_TYPES: Set<String> = setOf("image/png", "image/jpeg", "image/webp", "image/gif")

/**
 * web `artifactScreenshotFile`: which screenshot a card shows: in a phone-width layout ([phone]) its
 * phone shot when there is one, else the desktop one; null without a screenshot.
 */
internal fun artifactScreenshotFile(screenshot: ArtifactScreenshot?, phone: Boolean): String? {
    val shot = screenshot ?: return null
    if (shot.desktop.isBlank()) return null
    return shot.phone?.takeIf { phone && it.isNotBlank() } ?: shot.desktop
}

/** webObjectsContract `objectWebPaths.file("artifact", id, fileId)`, under the API base (no `/api`). */
internal fun artifactScreenshotPath(cardId: String, fileId: String): String =
    "objects/artifact/${cardId.appLinkPathSegment()}/files/${fileId.appLinkPathSegment()}"

/** One screenshot, by whose read it is (web `artifactReaderScope`: the Server) and its file route. */
data class ArtifactScreenshotKey(
    val serverId: String,
    val cardId: String,
    val fileId: String,
)

/** Outcome of fetching one screenshot so disk caches can evict on 403/404 and retain on transient failures. */
internal sealed interface ArtifactScreenshotFetchResult {
    class Ok(val bytes: ByteArray) : ArtifactScreenshotFetchResult

    /** 403 / 404: the screenshot is no longer readable -> evict any cached disk copy. */
    data object Gone : ArtifactScreenshotFetchResult

    /** Offline, 429, 5xx, or non-image payload -> keep any cached disk copy during cooldown. */
    data object TransientFailure : ArtifactScreenshotFetchResult
}

/** Reads one screenshot's bytes: null when it can't be read (any HTTP failure, or not an image type). */
internal fun interface ArtifactScreenshotFetcher {
    suspend fun fetch(key: ArtifactScreenshotKey): ByteArray?

    suspend fun fetchOutcome(key: ArtifactScreenshotKey): ArtifactScreenshotFetchResult =
        fetch(key)?.takeIf { it.isNotEmpty() }?.let { ArtifactScreenshotFetchResult.Ok(it) }
            ?: ArtifactScreenshotFetchResult.TransientFailure
}

/** [ArtifactScreenshotFetcher] over the app's authenticated API client. */
internal class NetworkArtifactScreenshotFetcher(
    private val networkClient: RaftNetworkClient,
) : ArtifactScreenshotFetcher {
    override suspend fun fetch(key: ArtifactScreenshotKey): ByteArray? =
        (fetchOutcome(key) as? ArtifactScreenshotFetchResult.Ok)?.bytes

    override suspend fun fetchOutcome(key: ArtifactScreenshotKey): ArtifactScreenshotFetchResult {
        val response =
            networkClient.requestBytes(
                // An on-screen card's own picture, as App link previews and the summaries.
                priority = DataRequestPriority.Visible,
                path = artifactScreenshotPath(key.cardId, key.fileId),
                serverIdOverride = ServerId(key.serverId),
                maxBytes = ARTIFACT_SCREENSHOT_MAX_FILE_BYTES,
            )
        if (response.code == 403 || response.code == 404) return ArtifactScreenshotFetchResult.Gone
        if (!response.isSuccessful || response.contentType !in ARTIFACT_SCREENSHOT_TYPES) {
            return ArtifactScreenshotFetchResult.TransientFailure
        }
        val bytes = response.bytes?.takeIf { it.isNotEmpty() } ?: return ArtifactScreenshotFetchResult.TransientFailure
        return ArtifactScreenshotFetchResult.Ok(bytes)
    }
}

/** Account + environment owner of persisted Artifact screenshots (#proj-mobile:#294). */
internal data class ArtifactScreenshotDiskOwner(
    val environmentId: String,
    val accountId: String,
) {
    fun normalizedOrNull(): ArtifactScreenshotDiskOwner? {
        val env = environmentId.trim().trimEnd('/').takeIf { it.isNotEmpty() } ?: return null
        val account = accountId.trim().takeIf { it.isNotEmpty() } ?: return null
        return ArtifactScreenshotDiskOwner(env, account)
    }
}

/** Per-(environment, account, server) disk cache for Artifact card screenshots. */
internal interface ArtifactScreenshotDiskCache {
    /** Captures a monotonic token before starting a network fetch so evictions can fence stale writes. */
    fun captureWriteToken(): Long = 0L

    /** Synchronously fences in-flight writes for [serverId] (and optional [accountId]). */
    fun fenceServer(serverId: String, accountId: String? = null) {}

    /** Synchronously fences every in-flight write across all servers (sign-out / account switch). */
    fun fenceAll() {}

    suspend fun read(owner: ArtifactScreenshotDiskOwner, key: ArtifactScreenshotKey): ByteArray?

    suspend fun write(
        owner: ArtifactScreenshotDiskOwner,
        key: ArtifactScreenshotKey,
        bytes: ByteArray,
        writeToken: Long = captureWriteToken(),
    )

    suspend fun remove(
        owner: ArtifactScreenshotDiskOwner,
        key: ArtifactScreenshotKey,
        writeToken: Long = captureWriteToken(),
    )

    suspend fun clearServer(serverId: String, accountId: String? = null, environmentId: String? = null)

    suspend fun clearAll() {}
}

/** What one Server's cards read screenshots through (the cards don't know the Server). */
internal interface ArtifactScreenshotReader {
    /** The screenshot kept already (it is the newest again), else null. */
    fun kept(cardId: String, fileId: String): ByteArray?

    /** Reads it once however many cards ask at a time; null when it can't be read. */
    suspend fun read(cardId: String, fileId: String): ByteArray?

    /**
     * Reads it with stale-while-revalidate disk support: invokes [onCached] immediately if a
     * persisted copy is found on disk, then refreshes over the network and returns the final bytes
     * (keeping the cached copy on transient errors, returning null on 403/404).
     */
    suspend fun read(cardId: String, fileId: String, onCached: (ByteArray) -> Unit): ByteArray? = read(cardId, fileId)
}

/**
 * web `artifactScreenshot.ts`'s cache + per-(env, account, server) disk cache (#proj-mobile:#294):
 * one read per screenshot however many cards ask at a time (the read outlives the card that started
 * it: another may be waiting on it), the newest [maxKept] kept in memory (at most [maxKeptBytes]
 * together, the oldest go first), and a failed read remembered [failedMillis], then read again by
 * the next card that asks. On cold start, any disk-cached copy paints immediately via `onCached`
 * while a background network refresh validates and updates it; 403/404 evicts the disk copy while
 * transient failures (offline/429/5xx) keep showing it.
 */
class SharedArtifactScreenshotStore internal constructor(
    private val fetcher: ArtifactScreenshotFetcher,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long,
    private val maxKept: Int = ARTIFACT_SCREENSHOT_MAX_KEPT,
    private val maxKeptBytes: Long = ARTIFACT_SCREENSHOT_MAX_KEPT_BYTES,
    private val failedMillis: Long = ARTIFACT_SCREENSHOT_FAILED_MILLIS,
    private val diskCache: ArtifactScreenshotDiskCache? = null,
    private val currentOwner: () -> ArtifactScreenshotDiskOwner? = { null },
) {
    private val lock = SynchronizedObject()

    /** Kept, the newest last. */
    private val kept = LinkedHashMap<ArtifactScreenshotKey, ByteArray>()
    private var keptBytes = 0L
    private val failedAt = HashMap<ArtifactScreenshotKey, Long>()
    private val reading = HashMap<ArtifactScreenshotKey, Deferred<ArtifactScreenshotFetchResult>>()

    /** Bumped by [clearAll]: a read begun before it is not kept after it. */
    private var generation = 0L
    /** Bumped per server by [dropServer]: an in-flight read for a lost server never re-populates cache. */
    private val serverGenerations = HashMap<String, Long>()

    /** The screenshot kept already (it is the newest again), else null. */
    fun kept(key: ArtifactScreenshotKey): ByteArray? = synchronized(lock) { keptLocked(key) }

    /** Whether a read of it failed less than [failedMillis] ago. */
    fun failedLately(key: ArtifactScreenshotKey): Boolean = synchronized(lock) { failedLatelyLocked(key) }

    /** Reads [key] (a kept one answers at once, one that failed lately answers null). */
    suspend fun read(key: ArtifactScreenshotKey): ByteArray? = read(key, onCached = null)

    /**
     * Reads [key] with optional [onCached] callback for immediate disk-cached display before the
     * network refresh settles.
     */
    suspend fun read(key: ArtifactScreenshotKey, onCached: ((ByteArray) -> Unit)?): ByteArray? {
        val memoryHit = synchronized(lock) { keptLocked(key) }
        if (memoryHit != null) return memoryHit

        val (startedGeneration, startedServerGeneration) =
            synchronized(lock) {
                generation to (serverGenerations[key.serverId] ?: 0L)
            }
        val owner = currentOwner()?.normalizedOrNull()
        val diskBytes =
            if (diskCache != null && owner != null) {
                runCatchingPreservingCancellation { diskCache.read(owner, key) }.getOrNull()
            } else {
                null
            }
        val stillSameAfterDisk =
            synchronized(lock) {
                generation == startedGeneration &&
                    (serverGenerations[key.serverId] ?: 0L) == startedServerGeneration &&
                    (owner == null || currentOwner()?.normalizedOrNull() == owner)
            }
        if (!stillSameAfterDisk) return null
        if (diskBytes != null) {
            onCached?.invoke(diskBytes)
        }

        val pending =
            synchronized(lock) {
                val stillValid =
                    generation == startedGeneration &&
                        (serverGenerations[key.serverId] ?: 0L) == startedServerGeneration &&
                        (owner == null || currentOwner()?.normalizedOrNull() == owner)
                if (!stillValid) return null
                val keptNow = keptLocked(key)
                if (keptNow != null) return keptNow
                if (failedLatelyLocked(key)) return diskBytes
                reading[key] ?: startReadLocked(key, owner)
            }
        val outcome = pending.await()
        val stillCurrentAfterFetch =
            synchronized(lock) {
                generation == startedGeneration &&
                    (serverGenerations[key.serverId] ?: 0L) == startedServerGeneration &&
                    (owner == null || currentOwner()?.normalizedOrNull() == owner)
            }
        if (!stillCurrentAfterFetch) return null
        return when (outcome) {
            is ArtifactScreenshotFetchResult.Ok -> outcome.bytes
            ArtifactScreenshotFetchResult.Gone -> null
            ArtifactScreenshotFetchResult.TransientFailure -> diskBytes
        }
    }

    /** Drops one server's screenshots in memory and on disk (server access lost). */
    suspend fun dropServer(serverId: String, accountId: String? = null, environmentId: String? = null) {
        val cleanServer = serverId.trim().takeIf { it.isNotEmpty() } ?: return
        synchronized(lock) {
            serverGenerations[cleanServer] = (serverGenerations[cleanServer] ?: 0L) + 1L
            val victims = kept.keys.filter { it.serverId == cleanServer }
            victims.forEach { key -> kept.remove(key)?.let { keptBytes -= it.size } }
            failedAt.keys.filter { it.serverId == cleanServer }.forEach(failedAt::remove)
            reading.keys.filter { it.serverId == cleanServer }.forEach(reading::remove)
            diskCache?.fenceServer(cleanServer, accountId)
        }
        runCatchingPreservingCancellation { diskCache?.clearServer(cleanServer, accountId, environmentId) }
    }

    /** Nothing kept, read or failed (sign-out, another account): the next card reads afresh. */
    fun clearAll() {
        synchronized(lock) {
            generation++
            kept.clear()
            keptBytes = 0
            failedAt.clear()
            reading.clear()
            diskCache?.fenceAll()
        }
    }

    /** The reader of one Server's cards. */
    internal fun reader(serverId: String): ArtifactScreenshotReader =
        object : ArtifactScreenshotReader {
            override fun kept(cardId: String, fileId: String): ByteArray? = kept(ArtifactScreenshotKey(serverId, cardId, fileId))

            override suspend fun read(cardId: String, fileId: String): ByteArray? = read(ArtifactScreenshotKey(serverId, cardId, fileId))

            override suspend fun read(cardId: String, fileId: String, onCached: (ByteArray) -> Unit): ByteArray? =
                read(ArtifactScreenshotKey(serverId, cardId, fileId), onCached)
        }

    /** For tests: how many are kept, and their bytes together. */
    internal fun keptCount(): Int = synchronized(lock) { kept.size }

    internal fun keptByteCount(): Long = synchronized(lock) { keptBytes }

    private fun keptLocked(key: ArtifactScreenshotKey): ByteArray? {
        val bytes = kept.remove(key) ?: return null
        kept[key] = bytes
        return bytes
    }

    private fun failedLatelyLocked(key: ArtifactScreenshotKey): Boolean {
        val at = failedAt[key] ?: return false
        if (nowMillis() - at < failedMillis) return true
        failedAt.remove(key)
        return false
    }

    private fun startReadLocked(
        key: ArtifactScreenshotKey,
        owner: ArtifactScreenshotDiskOwner?,
    ): Deferred<ArtifactScreenshotFetchResult> {
        val startedIn = generation
        val startedServerIn = serverGenerations[key.serverId] ?: 0L
        val writeToken = diskCache?.captureWriteToken() ?: 0L
        val deferred =
            // Started once it is registered: a read that answers at once still finds itself there.
            scope.async(start = CoroutineStart.LAZY) {
                val outcome =
                    try {
                        fetcher.fetchOutcome(key)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        ArtifactScreenshotFetchResult.TransientFailure
                    }
                val acceptWrite =
                    synchronized(lock) {
                        val sameGeneration =
                            generation == startedIn && (serverGenerations[key.serverId] ?: 0L) == startedServerIn
                        if (sameGeneration) {
                            finishReadLocked(key, outcome)
                        }
                        sameGeneration && (owner == null || currentOwner()?.normalizedOrNull() == owner)
                    }
                if (acceptWrite && diskCache != null && owner != null) {
                    when (outcome) {
                        is ArtifactScreenshotFetchResult.Ok ->
                            runCatchingPreservingCancellation {
                                diskCache.write(owner, key, outcome.bytes, writeToken = writeToken)
                            }
                        ArtifactScreenshotFetchResult.Gone ->
                            runCatchingPreservingCancellation { diskCache.remove(owner, key, writeToken = writeToken) }
                        ArtifactScreenshotFetchResult.TransientFailure -> Unit
                    }
                }
                outcome
            }
        reading[key] = deferred
        deferred.start()
        return deferred
    }

    private fun finishReadLocked(
        key: ArtifactScreenshotKey,
        outcome: ArtifactScreenshotFetchResult,
    ) {
        reading.remove(key)
        when (outcome) {
            is ArtifactScreenshotFetchResult.Ok -> keepLocked(key, outcome.bytes)
            ArtifactScreenshotFetchResult.Gone -> {
                kept.remove(key)?.let { keptBytes -= it.size }
                failedAt[key] = nowMillis()
            }
            ArtifactScreenshotFetchResult.TransientFailure -> failedAt[key] = nowMillis()
        }
    }

    private fun keepLocked(key: ArtifactScreenshotKey, bytes: ByteArray) {
        kept.remove(key)?.let { keptBytes -= it.size }
        kept[key] = bytes
        keptBytes += bytes.size
        // The oldest go first; the one just kept stays.
        while ((kept.size > maxKept || keptBytes > maxKeptBytes) && kept.size > 1) {
            val oldest = kept.keys.first()
            kept.remove(oldest)?.let { keptBytes -= it.size }
        }
    }
}
