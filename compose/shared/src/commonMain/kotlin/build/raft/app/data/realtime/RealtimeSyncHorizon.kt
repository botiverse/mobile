package build.raft.app.data.realtime

import build.raft.app.model.RealtimeEvent
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Exact message catch-up after a reconnect: the sync horizon of botiverse/slock #10982
 * (`packages/server/src/services/syncHorizon.ts`, `socket/syncResume.ts`).
 *
 * Message `seq` is allocated at INSERT but transactions commit in another order, so `sync:resume`
 * from `lastSeq` alone can skip a smaller-seq message that committed after the client passed it.
 * Every server read also returns a horizon (decimal xid8 text). A resume that sends the previous
 * round's horizon back as `sinceHorizon` also gets the "late" messages (seq <= lastSeq that finished
 * after that horizon), or `horizonReset: true` when more than 500 qualify (none are sent; the client
 * re-fetches its visible scope instead).
 *
 * Client rules, as implemented by the Android socket client and the iOS realtime host:
 * - The horizon is opaque decimal text. It is never parsed into a number; it is stored and sent as a
 *   string (a JSON number is not a horizon: the server ignores it).
 * - `sinceHorizon` goes only on a round's FIRST page. The app never follows a resume's `hasMore` with
 *   `/messages/sync`, so every `sync:resume` is a round's first (and only) page, and the next round
 *   uses the horizon of this round's response.
 * - Starting horizon: `sync:horizon` is asked on every socket connect while no horizon is held. Its
 *   ack is used only if it arrived before ANY baseline history request of that server was sent
 *   ([RealtimeBaselineHistoryRequests]); otherwise it is discarded and the first resume runs without
 *   one, exactly as before. Nothing ever waits for the ack. A `sync:resume` sent without
 *   `sinceHorizon` still returns a horizon, so from the second round on catch-up is exact.
 * - A server that predates the horizon returns none: the client behaves exactly as before.
 * - Per-channel `/messages/sync?channel_id=…` (thread gap catch-up) never sends `since_horizon` and
 *   never reads `X-Sync-Horizon`: it sees one channel only, so its horizon would skip other channels'
 *   late messages.
 */
object RealtimeSyncHorizon {
    /** `RealtimeRefreshRequired(Home)` reason when a resume answers `horizonReset: true`. */
    const val ResetReason = "sync_resume_horizon_reset"

    /** Payload key of `sync:resume` (request) and of `sync:horizon` / `sync:resume:response` (answers). */
    const val SinceHorizonKey = "sinceHorizon"
    const val HorizonKey = "horizon"

    private const val Xid8Max = "18446744073709551615"

    /**
     * A horizon as the server accepts it back (`parseSyncHorizon`): the decimal text of a non-negative
     * integer that fits xid8 (unsigned 64-bit), without signs, spaces or leading zeros. Anything else is
     * null and treated as "no horizon".
     */
    fun parse(value: String?): String? {
        if (value.isNullOrEmpty() || value.length > Xid8Max.length) return null
        if (value.any { it !in '0'..'9' }) return null
        if (value.length > 1 && value[0] == '0') return null
        if (value.length == Xid8Max.length && value > Xid8Max) return null
        return value
    }

    /** Only a JSON string can carry a horizon; a JSON number would already have lost precision. */
    fun parseJson(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::parse)
}

/** The Home/Activity re-fetch a `horizonReset: true` resume asks for (the open thread re-reads its latest page too). */
fun RealtimeEvent.isSyncHorizonReset(): Boolean =
    this is RealtimeRefreshRequired &&
        scope == RealtimeRefreshScope.Home &&
        reason == RealtimeSyncHorizon.ResetReason

/**
 * Process-wide record of which servers have had a baseline history request SENT: the reads whose
 * results the client keeps as the state realtime then patches (Home lists, unread summaries, message
 * pages, Activity). A starting horizon (`sync:horizon` ack) is usable only while none was sent for
 * that server, because only then was the server's snapshot taken before every history read the client
 * holds. The record is never cleared within a process: data loaded earlier stays on screen and in
 * caches, so a later horizon would not predate it.
 *
 * Marked by the shared network client right before the request goes to the HTTP stack (the one
 * request boundary every platform shares).
 */
object RealtimeBaselineHistoryRequests {
    private val lock = SynchronizedObject()
    private val sentServerIds = mutableSetOf<String>()

    /** GET reads under `messages` (not search), `channels` and `servers/unread-summary`. */
    fun isBaselineHistoryRequest(method: String, path: String): Boolean {
        if (!method.equals("GET", ignoreCase = true)) return false
        val normalized = path.trim().trimStart('/')
        return when {
            normalized.startsWith("messages/search") -> false
            normalized == "messages" || normalized.startsWith("messages/") -> true
            normalized == "channels" || normalized.startsWith("channels/") -> true
            normalized.startsWith("servers/unread-summary") -> true
            else -> false
        }
    }

    fun recordSent(serverId: String) {
        val clean = serverId.trim().takeIf { it.isNotEmpty() } ?: return
        synchronized(lock) { sentServerIds += clean }
    }

    fun anySent(serverId: String): Boolean = synchronized(lock) { serverId.trim() in sentServerIds }
}
