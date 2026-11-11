package build.raft.app.data

import build.raft.app.model.ActivityChanged
import build.raft.app.model.DirectMessageCreated
import build.raft.app.model.MessageNew
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.ThreadUpdated
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.withContext

/** `X-Raft-Fetch-Reason`, the header web sends on whole-list Activity reads (packages/web listFetchReason.ts). */
internal const val RaftFetchReasonHeader = "X-Raft-Fetch-Reason"

/** `X-Raft-Fetch-Trigger` (shared `INBOX_REFRESH_TRIGGER_HEADER`, botiverse/slock #13126). */
internal const val RaftFetchTriggerHeader = "X-Raft-Fetch-Trigger"

/** `X-Raft-Fetch-Coalesced` (shared `INBOX_REFRESH_COALESCED_HEADER`): the requests one read stands for. */
internal const val RaftFetchCoalescedHeader = "X-Raft-Fetch-Coalesced"

/** The server records any larger count as this (server `INBOX_FETCH_COALESCED_CAP`); the app sends no more. */
internal const val RaftFetchCoalescedCap = 1000

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
    /**
     * `mobile_inbox_inplace_v0`: the at-most-once-per-minute re-read of a visible list whose rows
     * live messages patched in place ([ActivityInPlaceReconcileIntervalMillis]). Mobile-only for
     * now: a server without it in `ACTIVITY_LIST_FETCH_REASONS` files it under `other`.
     */
    InPlaceReconcile("inplace_reconcile"),
}

/**
 * Which realtime event made the app re-read the Activity list with reason
 * [InboxFetchReason.RealtimeFallback], sent as `X-Raft-Fetch-Trigger`. The first eight wire values
 * are exactly shared `INBOX_REFRESH_TRIGGERS` (botiverse/slock packages/shared/src/
 * inboxRefreshTrigger.ts, #13126), in its order. [Other] names an app re-read no shared value
 * describes (an edit, a task change, a reconnect catch-up, another device's read or Done, a local
 * fact landing mid-read, a stale-answer recovery); the server files it, like any unlisted value,
 * under `other`, apart from `unknown` (no header). Observability only: which reads happen, and
 * when, is unchanged.
 */
enum class InboxRefreshTrigger(val wireValue: String) {
    /** A new live message (`message:new`) moved a channel, DM or thread row. */
    MessageNew("message_new"),
    /** `message:new` for a message the Activity list already shows (a repeat delivery, my own send). */
    MessageEcho("message_echo"),
    /** A thread reply (`thread:updated`) moved a followed-thread row. */
    ThreadReply("thread_reply"),
    /** `thread:updated` applied after a thread-replies rebaseline snapshot (web only today). */
    ThreadReplyRebaseline("thread_reply_rebaseline"),
    /** `activity:changed`: first-party objects changed for this person. */
    ActivityChanged("activity_changed"),
    /** `dm:new`: a new DM was opened with this person. */
    DmNew("dm_new"),
    /** This device's thread read-all was persisted while Activity is loaded (web only today). */
    ReadPersisted("read_persisted"),
    /** The list answered still behind the newest live message; re-read. */
    CatchUpRetry("catch_up_retry"),
    /** An app re-read none of the shared values describes. */
    Other("other"),
}

/** The trigger a realtime event gives the Activity re-read it asks for. */
internal fun RealtimeEvent.inboxRefreshTrigger(): InboxRefreshTrigger =
    when (this) {
        is MessageNew -> InboxRefreshTrigger.MessageNew
        is ThreadUpdated -> InboxRefreshTrigger.ThreadReply
        // The sync stream hands its Activity refetch over as an `ActivityChanged` with source
        // `sync_<why>` (RaftSharedStoreProvider.refreshActivity): not the `activity:changed` socket event.
        is ActivityChanged ->
            if (source.startsWith("sync_")) InboxRefreshTrigger.Other else InboxRefreshTrigger.ActivityChanged
        is DirectMessageCreated -> InboxRefreshTrigger.DmNew
        else -> InboxRefreshTrigger.Other
    }

/**
 * The trigger of a throttled re-read: the FIRST one folded into it and how many requests it stands
 * for (>= 1, itself included), web `InboxRefreshTriggerCount`.
 */
internal data class InboxRefreshTriggerCount(val trigger: InboxRefreshTrigger, val coalesced: Int = 1)

/** Folds [next] into this: the first trigger stays, the counts add up (web `foldInboxRefreshTrigger`). */
internal fun InboxRefreshTriggerCount?.foldWith(next: InboxRefreshTriggerCount?): InboxRefreshTriggerCount? =
    when {
        this == null -> next
        next == null -> this
        else -> copy(coalesced = (coalesced + next.coalesced).coerceAtMost(RaftFetchCoalescedCap))
    }

/** Folds one more request asked for by [trigger]. */
internal fun InboxRefreshTriggerCount?.foldWith(trigger: InboxRefreshTrigger): InboxRefreshTriggerCount =
    foldWith(InboxRefreshTriggerCount(trigger))!!

/**
 * Carries the reason of the read being made down to the repository, which turns it into the header.
 * A context element, so the repository interfaces and their fakes keep their signatures.
 */
internal class InboxFetchReasonContext(
    val reason: InboxFetchReason,
    val trigger: InboxRefreshTriggerCount? = null
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<InboxFetchReasonContext>
}

/**
 * Runs [block] so the inbox reads it makes are labelled [reason]; a null [reason] leaves them
 * unlabelled. [trigger] is sent only when [reason] is [InboxFetchReason.RealtimeFallback] (web
 * `listFetchReasonHeaders`: a read whose final reason is another carries none).
 */
internal suspend fun <T> withInboxFetchReason(
    reason: InboxFetchReason?,
    trigger: InboxRefreshTriggerCount? = null,
    block: suspend () -> T
): T =
    if (reason == null) block() else withContext(InboxFetchReasonContext(reason, trigger)) { block() }

internal suspend fun currentInboxFetchReasonHeaders(): Map<String, String> =
    coroutineContext[InboxFetchReasonContext]?.let(::inboxFetchReasonHeaders).orEmpty()

internal fun inboxFetchReasonHeaders(context: InboxFetchReasonContext): Map<String, String> {
    val trigger = context.trigger?.takeIf { context.reason == InboxFetchReason.RealtimeFallback }
        ?: return mapOf(RaftFetchReasonHeader to context.reason.wireValue)
    return mapOf(
        RaftFetchReasonHeader to context.reason.wireValue,
        RaftFetchTriggerHeader to trigger.trigger.wireValue,
        RaftFetchCoalescedHeader to trigger.coalesced.coerceIn(1, RaftFetchCoalescedCap).toString(),
    )
}

/**
 * The headers that only describe a read (the trigger and its count) and so do not make it a different
 * request: the in-flight GET single flight ([RaftInFlightGetDedupe]) leaves them out of its key, so
 * labelling a read never changes which reads are shared.
 */
internal val RaftFetchObservabilityHeaders: Set<String> = setOf(RaftFetchTriggerHeader, RaftFetchCoalescedHeader)

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
