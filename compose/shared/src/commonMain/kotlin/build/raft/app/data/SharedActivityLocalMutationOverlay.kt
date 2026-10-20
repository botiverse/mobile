package build.raft.app.data

import build.raft.app.model.MessageId
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.time.TimeSource

/**
 * Activity is served from a RisingWave view that lags the synchronous Postgres
 * Done/read write by seconds (and, under RW slowness, from a short-lived Redis
 * copy). A confirmed Done or read therefore stays applied locally until the
 * served row carries a NEWER activity marker, or until this per-row hold lapses.
 *
 * Mirrors botiverse/slock web `INBOX_LOCAL_MUTATION_HOLD_MS` (PR #10363, 96916b7f9).
 * 90s = the 60s stale-cache bound + RW lag + request time; after it a row the
 * server still serves is shown again, so a server that never applied the
 * mutation stays observable rather than masked. The server no longer serves a
 * cache older than the user's own write (slock #10360), so in practice the
 * overlay only covers the few-second view lag and the cap is a backstop.
 */
internal const val ActivityLocalMutationHoldMillis: Long = 90_000L

/**
 * Realtime-triggered Activity refetches are throttled leading+trailing to one
 * per window, measured from the last reset load of any kind (web
 * `INBOX_BACKGROUND_REFRESH_INTERVAL_MS`). User-initiated loads are never delayed.
 */
internal const val ActivityRealtimeBackgroundRefreshIntervalMillis: Long = 2_500L

/** Monotonic milliseconds since the store was created; injectable for virtual-time tests. */
internal fun activityMonotonicClock(): () -> Long {
    val origin = TimeSource.Monotonic.markNow()
    return { origin.elapsedNow().inWholeMilliseconds }
}

/**
 * The activity marker captured at click time.
 *
 * [seq] is the server's authority frontier for the row's own scope
 * (`readState.latestActivity.seq`, same field web keys on via
 * `inboxItemLatestMarker`). It is deliberately NOT `firstUnreadMessageId`: a
 * Done/read write moves the read cursor, so a lagging row legitimately loses its
 * first-unread id while carrying the same activity, and keying on it made the
 * hidden row reappear. [anchor] (latest-activity message id) and [replyCount]
 * are used only when the row carried no authority frontier at click time.
 */
internal data class ActivityOverlayMarker(
    val seq: String?,
    val anchor: MessageId?,
    val replyCount: Int = 0
)

internal fun InboxItem.activityOverlayMarker(): ActivityOverlayMarker =
    ActivityOverlayMarker(
        seq = readStateLatestActivitySeq?.takeIf(::isCanonicalActivitySeq),
        anchor = latestActivityMessageId ?: lastMessageId ?: parentMessageId,
        replyCount = replyCount
    )

/**
 * True only for activity strictly newer than the held marker. A lagging list can
 * carry an OLDER seq than the one recorded at click; that is still the same,
 * already-handled row.
 */
internal fun ActivityOverlayMarker.isNewerThan(held: ActivityOverlayMarker): Boolean {
    if (held.seq != null) return compareActivitySeq(seq, held.seq) > 0
    // Without a frontier, message ids carry no order. A thread row whose reply
    // count is behind the click-time count (the click saw a realtime-patched
    // row) is an older copy, not new activity.
    return held.anchor != null && anchor != held.anchor && replyCount >= held.replyCount
}

internal fun isCanonicalActivitySeq(value: String): Boolean =
    value.isNotEmpty() &&
        value.all { it in '0'..'9' } &&
        (value.length == 1 || value[0] != '0')

/**
 * Exact decimal comparison (no Long overflow / precision loss). Absent or
 * non-canonical values lose to any canonical value; two absent values are equal.
 */
internal fun compareActivitySeq(a: String?, b: String?): Int {
    val left = a?.takeIf(::isCanonicalActivitySeq)
    val right = b?.takeIf(::isCanonicalActivitySeq)
    if (left == null && right == null) return 0
    if (left == null) return -1
    if (right == null) return 1
    if (left.length != right.length) return left.length.compareTo(right.length)
    return left.compareTo(right).coerceIn(-1, 1)
}

/** Overlay entries never cross server or account boundaries. */
internal data class ActivityOverlayOwner(
    val serverId: String,
    val userId: String
)

/**
 * Per-owner, per-row local mutation overlay (Done or read).
 *
 * An entry is pending (no expiry) until the server confirms the write; the
 * confirmation starts that row's own hold. A later mutation on another row
 * never extends this row's timer. Entries retire when the server serves newer
 * activity for the row, when the hold lapses, or when the user undoes / the
 * write fails. Absence from a refreshed list does NOT retire an entry: a later
 * stale copy may still carry the row.
 */
internal class ActivityLocalMutationOverlay(
    private val nowMillis: () -> Long,
    private val holdMillis: Long = ActivityLocalMutationHoldMillis
) {
    private data class Entry(
        val marker: ActivityOverlayMarker,
        val generation: Long,
        val expiresAtMillis: Long?
    )

    private val lock = SynchronizedObject()
    private var nextGeneration = 0L
    private val entries = mutableMapOf<ActivityOverlayOwner, MutableMap<String, Entry>>()

    /** Records (or re-arms) the row; returns the generation a later confirm/forget must match. */
    fun remember(owner: ActivityOverlayOwner, item: InboxItem, confirmed: Boolean = false): Long =
        synchronized(lock) {
            val generation = ++nextGeneration
            entries.getOrPut(owner) { mutableMapOf() }[item.key] =
                Entry(
                    marker = item.activityOverlayMarker(),
                    generation = generation,
                    expiresAtMillis = if (confirmed) nowMillis() + holdMillis else null
                )
            generation
        }

    /** Server confirmed the write: start this row's hold now. */
    fun confirm(owner: ActivityOverlayOwner, key: String, generation: Long) {
        synchronized(lock) {
            val scoped = entries[owner] ?: return
            val entry = scoped[key] ?: return
            if (entry.generation != generation) return
            scoped[key] = entry.copy(expiresAtMillis = nowMillis() + holdMillis)
        }
    }

    /** Undo or failed write: drop the row immediately ([generation] null = any). */
    fun forget(owner: ActivityOverlayOwner, key: String, generation: Long? = null) {
        synchronized(lock) {
            val scoped = entries[owner] ?: return
            val entry = scoped[key] ?: return
            if (generation != null && entry.generation != generation) return
            scoped.remove(key)
            if (scoped.isEmpty()) entries.remove(owner)
        }
    }

    fun hasEntries(owner: ActivityOverlayOwner): Boolean =
        synchronized(lock) { entries[owner]?.isNotEmpty() == true }

    fun contains(owner: ActivityOverlayOwner, key: String): Boolean =
        synchronized(lock) { entries[owner]?.containsKey(key) == true }

    /**
     * Whether [item], as served now, is still covered by a local mutation.
     * Newer activity or an expired hold retires the entry and returns false.
     */
    fun holds(owner: ActivityOverlayOwner, item: InboxItem): Boolean =
        synchronized(lock) {
            val scoped = entries[owner] ?: return@synchronized false
            val entry = scoped[item.key] ?: return@synchronized false
            val expired = entry.expiresAtMillis?.let { nowMillis() > it } == true
            if (expired || item.activityOverlayMarker().isNewerThan(entry.marker)) {
                scoped.remove(item.key)
                if (scoped.isEmpty()) entries.remove(owner)
                false
            } else {
                true
            }
        }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }
}
