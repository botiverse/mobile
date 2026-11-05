package build.raft.app.data

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.withContext

/** `X-Raft-Fetch-Reason`, the header web sends on whole-list Activity reads (packages/web listFetchReason.ts). */
internal const val RaftFetchReasonHeader = "X-Raft-Fetch-Reason"

/**
 * Why the app read the Activity list (`GET /api/channels/inbox`, first page). The wire values are
 * exactly web's `ListFetchReason` (botiverse/slock packages/web/src/store/listFetchReason.ts); the
 * server records these and files anything else under `other`. Only the header is new: which reads
 * happen, and when, is unchanged.
 */
enum class InboxFetchReason(val wireValue: String) {
    /** First load: app start, opening Activity, switching server. */
    Initial("initial"),
    /** After the socket reconnected, without a sync cursor. */
    Reconnect("reconnect"),
    /** The app or the Activity tab became visible again. */
    Visibility("visibility"),
    /** The sync stream asked for a full snapshot (reset, new cursor). */
    SyncReset("sync_reset"),
    /** A realtime event could not be applied in place (re-read). */
    RealtimeFallback("realtime_fallback"),
    /** The person asked for it: an action, a filter, a pull. */
    Manual("manual"),
    /** A sync change without the row's state (older server). */
    LegacyNoFields("legacy_no_fields"),
    /** A sync change the app did not patch in place. */
    SyncUnpatchable("sync_unpatchable"),
}

/**
 * Carries the reason of the read being made down to the repository, which turns it into the header.
 * A context element, so the repository interfaces and their fakes keep their signatures.
 */
internal class InboxFetchReasonContext(val reason: InboxFetchReason) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<InboxFetchReasonContext>
}

/** Runs [block] so the inbox reads it makes are labelled [reason]; a null [reason] leaves them unlabelled. */
internal suspend fun <T> withInboxFetchReason(reason: InboxFetchReason?, block: suspend () -> T): T =
    if (reason == null) block() else withContext(InboxFetchReasonContext(reason)) { block() }

internal suspend fun currentInboxFetchReasonHeaders(): Map<String, String> =
    coroutineContext[InboxFetchReasonContext]?.let { mapOf(RaftFetchReasonHeader to it.reason.wireValue) }.orEmpty()

/**
 * The reason for a read the process unread summary store ([ProcessActivityUnreadStore]) makes, from
 * the `source` it logs for the request. Null for a source this map does not know (the header is then
 * left off and the server counts the read as `unknown`).
 */
internal fun activityUnreadSummaryFetchReason(source: String): InboxFetchReason? {
    // Strip the store's own suffixes ("<reason>:owner_ready", "<reason>:missing_epoch").
    val base = source.removeSuffix(":owner_ready").removeSuffix(":missing_epoch")
    return when {
        base == "ensure" ||
            base == "activity_route_attach" ||
            base == "thread_route_attach" ||
            base == "app_shell_server_ready" ||
            base == "server_switch" ||
            base == "server_ready" -> InboxFetchReason.Initial
        // onForeground("app_shell_<lifecycle event>")
        base.startsWith("app_shell_") || base == "app_foreground_return" -> InboxFetchReason.Visibility
        base == "manual" || base == "manual_home_refresh" -> InboxFetchReason.Manual
        // "fact_reconcile": a realtime fact landed while a read was in flight, so the store reads again.
        base == "fact_reconcile" ||
            base.startsWith("invalidate:realtime:") || base.startsWith("invalidate:activity:") ->
            InboxFetchReason.RealtimeFallback
        // The person's own read actions (and their receipt outcome) change the count.
        base.startsWith("invalidate:read_prediction:") ||
            base.startsWith("invalidate:read_result:") ||
            base.startsWith("invalidate:read_failure:") ||
            base == "invalidate:conversation_read_all" -> InboxFetchReason.Manual
        // refreshActivity from the sync stream: "sync_changes" = rows changed, not patched in place;
        // "sync_<why>" = the stream asked for a snapshot.
        base == "sync_changes" -> InboxFetchReason.SyncUnpatchable
        base.startsWith("sync_") -> InboxFetchReason.SyncReset
        else -> null
    }
}
