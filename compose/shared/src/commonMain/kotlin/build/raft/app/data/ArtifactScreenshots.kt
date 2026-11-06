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

/** Reads one screenshot's bytes: null when it can't be read (any HTTP failure, or not an image type). */
internal fun interface ArtifactScreenshotFetcher {
    suspend fun fetch(key: ArtifactScreenshotKey): ByteArray?
}

/** [ArtifactScreenshotFetcher] over the app's authenticated API client. */
internal class NetworkArtifactScreenshotFetcher(
    private val networkClient: RaftNetworkClient,
) : ArtifactScreenshotFetcher {
    override suspend fun fetch(key: ArtifactScreenshotKey): ByteArray? {
        val response =
            networkClient.requestBytes(
                // An on-screen card's own picture, as App link previews and the summaries.
                priority = DataRequestPriority.Visible,
                path = artifactScreenshotPath(key.cardId, key.fileId),
                serverIdOverride = ServerId(key.serverId),
                maxBytes = ARTIFACT_SCREENSHOT_MAX_FILE_BYTES,
            )
        if (!response.isSuccessful || response.contentType !in ARTIFACT_SCREENSHOT_TYPES) return null
        return response.bytes?.takeIf { it.isNotEmpty() }
    }
}

/** What one Server's cards read screenshots through (the cards don't know the Server). */
internal interface ArtifactScreenshotReader {
    /** The screenshot kept already (it is the newest again), else null. */
    fun kept(cardId: String, fileId: String): ByteArray?

    /** Reads it once however many cards ask at a time; null when it can't be read. */
    suspend fun read(cardId: String, fileId: String): ByteArray?
}

/**
 * web `artifactScreenshot.ts`'s cache, in memory for this viewer: one read per screenshot however
 * many cards ask at a time (the read outlives the card that started it: another may be waiting on
 * it), the newest [maxKept] kept (at most [maxKeptBytes] together, the oldest go first), and a failed
 * read remembered [failedMillis], then read again by the next card that asks. A card holds the bytes
 * it shows, so dropping one never breaks a card on screen.
 */
class SharedArtifactScreenshotStore internal constructor(
    private val fetcher: ArtifactScreenshotFetcher,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long,
    private val maxKept: Int = ARTIFACT_SCREENSHOT_MAX_KEPT,
    private val maxKeptBytes: Long = ARTIFACT_SCREENSHOT_MAX_KEPT_BYTES,
    private val failedMillis: Long = ARTIFACT_SCREENSHOT_FAILED_MILLIS,
) {
    private val lock = SynchronizedObject()

    /** Kept, the newest last. */
    private val kept = LinkedHashMap<ArtifactScreenshotKey, ByteArray>()
    private var keptBytes = 0L
    private val failedAt = HashMap<ArtifactScreenshotKey, Long>()
    private val reading = HashMap<ArtifactScreenshotKey, Deferred<ByteArray?>>()

    /** Bumped by [clearAll]: a read begun before it is not kept after it. */
    private var generation = 0L

    /** The screenshot kept already (it is the newest again), else null. */
    fun kept(key: ArtifactScreenshotKey): ByteArray? = synchronized(lock) { keptLocked(key) }

    /** Whether a read of it failed less than [failedMillis] ago. */
    fun failedLately(key: ArtifactScreenshotKey): Boolean = synchronized(lock) { failedLatelyLocked(key) }

    /** Reads [key] (a kept one answers at once, one that failed lately answers null). */
    suspend fun read(key: ArtifactScreenshotKey): ByteArray? {
        var have: ByteArray? = null
        val pending =
            synchronized(lock) {
                have = keptLocked(key)
                when {
                    have != null || failedLatelyLocked(key) -> null
                    else -> reading[key] ?: startReadLocked(key)
                }
            }
        return pending?.await() ?: have
    }

    /** Nothing kept, read or failed (sign-out, another account): the next card reads afresh. */
    fun clearAll() {
        synchronized(lock) {
            generation++
            kept.clear()
            keptBytes = 0
            failedAt.clear()
            reading.clear()
        }
    }

    /** The reader of one Server's cards. */
    internal fun reader(serverId: String): ArtifactScreenshotReader =
        object : ArtifactScreenshotReader {
            override fun kept(cardId: String, fileId: String): ByteArray? = kept(ArtifactScreenshotKey(serverId, cardId, fileId))

            override suspend fun read(cardId: String, fileId: String): ByteArray? = read(ArtifactScreenshotKey(serverId, cardId, fileId))
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

    private fun startReadLocked(key: ArtifactScreenshotKey): Deferred<ByteArray?> {
        val startedIn = generation
        val deferred =
            // Started once it is registered: a read that answers at once still finds itself there.
            scope.async(start = CoroutineStart.LAZY) {
                val bytes =
                    try {
                        fetcher.fetch(key)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        null
                    }
                synchronized(lock) { finishReadLocked(key, startedIn, bytes) }
                bytes
            }
        reading[key] = deferred
        deferred.start()
        return deferred
    }

    private fun finishReadLocked(key: ArtifactScreenshotKey, startedIn: Long, bytes: ByteArray?) {
        if (generation != startedIn) return
        reading.remove(key)
        if (bytes != null) {
            keepLocked(key, bytes)
        } else {
            failedAt[key] = nowMillis()
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
