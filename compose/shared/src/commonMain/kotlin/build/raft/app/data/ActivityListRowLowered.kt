package build.raft.app.data

import build.raft.app.model.InboxItemKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/*
 * `inbox_row_position_v1` (#triage:#244 diagnostic), web `reportListRowsLowered`
 * (botiverse/slock packages/web/src/store/inboxListRowLowered.ts): a whole-list answer that leaves a
 * row's unread *below* what the answer served for it sends one event (`slock.state.transition`,
 * domain `inbox`) naming the first step of the answer's application that lowered the row, with the
 * counts, why the list was read, and the row's read facts (scope id, the row's latest activity seq,
 * the answer's read state, this client's ledger read state). Sent only when it happens.
 *
 * Two events, as web (slock #15174):
 * - `activity:list_row_corrected` (outcome `noop`): the `ledger` step, where this client holds a
 *   newer server-issued read (higher version) whose read covers the answer's latest activity for
 *   the row. The answer was stale and the page was right; not counted against the bar.
 * - `activity:list_row_lowered` (outcome `conflict`): every other step (and a ledger step whose
 *   facts don't show that cover). This is the defect count.
 *
 * The app applies an answer in this order: `local_read` (a Read here, and reads from elsewhere the
 * app held: the local read overlay), `done` (a local Done), `merge` (the realtime fold and the
 * per-row position merge). The app has no ledger step of its own: [ledgerAdjudicated] is web's
 * (`adjudicateServedUnreadAgainstLedger`) run on the answer against the app's accepted read ledger,
 * placed first so a row it covers is named `ledger` as on web; it changes nothing shown. The web's
 * `read_hold` and `projection` steps have no stage here.
 */

internal const val ActivityListRowLoweredTraceEvent = "activity:list_row_lowered"
internal const val ActivityListRowCorrectedTraceEvent = "activity:list_row_corrected"

/** Web `ListRowStep`. */
enum class ActivityListRowStep(val wireValue: String) {
    Ledger("ledger"),
    LocalRead("local_read"),
    Done("done"),
    ReadHold("read_hold"),
    Merge("merge"),
    Projection("projection"),
}

/** Web `LedgerReadState`: this client's accepted read state for a scope. */
data class ActivityLedgerReadState(val maxReadSeq: Long, val readStateVersion: Long)

/** One `activity:list_row_lowered` / `activity:list_row_corrected`, as web emits it. */
data class ActivityListRowLowered(
    val step: ActivityListRowStep,
    /** The row's unread as shown before the answer; -1 when it was not listed. */
    val before: Int,
    val served: Int,
    val after: Int,
    /** The read's `X-Raft-Fetch-Reason` (`none` when it had none). */
    val fetchReason: String,
    /** The read's `X-Raft-Fetch-Trigger` (`none` when it had none). */
    val fetchTrigger: String,
    val rowPosition: Boolean,
    val timestampMillis: Long,
    /** Web `item.kind`: `channel`, `dm` or `thread`. */
    val rowKind: String,
    val scopeId: String?,
    /** The row's wire `latestActivitySeq`. */
    val rowLatestSeq: String?,
    /** The answer's `readState` for the row (present frontier only). */
    val servedReadVersion: Long?,
    val servedReadSeq: String?,
    val servedLatestActivitySeq: String?,
    /** This client's accepted read state for the scope when the answer was applied. */
    val ledgerReadVersion: Long?,
    val ledgerReadSeq: String?,
) {
    /** Web `isLedgerCorrection`, on the `ledger` step only. */
    val corrected: Boolean
        get() = step == ActivityListRowStep.Ledger && isLedgerCorrection(servedReadVersion, servedLatestActivitySeq, ledgerReadVersion, ledgerReadSeq)

    val event: String
        get() = if (corrected) ActivityListRowCorrectedTraceEvent else ActivityListRowLoweredTraceEvent
}

/** Web `isLedgerCorrection`: a newer server-issued read covers the answer's latest activity. */
private fun isLedgerCorrection(
    servedReadVersion: Long?,
    servedLatestActivitySeq: String?,
    ledgerReadVersion: Long?,
    ledgerReadSeq: String?,
): Boolean {
    if (servedReadVersion == null || ledgerReadVersion == null) return false
    if (ledgerReadVersion <= servedReadVersion) return false
    if (servedLatestActivitySeq == null || ledgerReadSeq == null) return false
    return compareActivitySeq(ledgerReadSeq, servedLatestActivitySeq) >= 0
}

private fun InboxItem.isListRowLoweredRow(): Boolean =
    kind != InboxItemKind.MentionAction && kind != InboxItemKind.Object

/** Web `inboxItemChannelId`: a thread's own channel, else the row's channel. */
private fun InboxItem.listRowScopeId(): String? =
    if (kind == InboxItemKind.Thread) threadChannelId?.value else channelId.value

/** Web `item.kind` on the wire. */
private fun InboxItem.listRowKindWire(): String =
    when (kind) {
        InboxItemKind.DirectMessage -> "dm"
        InboxItemKind.Thread -> "thread"
        else -> "channel"
    }

private fun List<InboxItem>.unreadByKey(): Map<String, Int> =
    asSequence().filter { it.isListRowLoweredRow() }.associate { it.key to it.unreadCount }

/**
 * Web `adjudicateServedUnreadAgainstLedger`: [served] with each unread row read whose scope the
 * ledger holds a newer read for (higher version) that covers the answer's latest activity.
 */
internal fun ledgerAdjudicated(served: List<InboxItem>, ledgerOf: (scopeId: String) -> ActivityLedgerReadState?): List<InboxItem> =
    served.map { item ->
        if (!item.isListRowLoweredRow() || item.unreadCount == 0) return@map item
        val servedVersion = item.readStateVersion ?: return@map item
        val latestSeq = item.readStateLatestActivitySeq ?: return@map item
        val ledger = item.listRowScopeId()?.let(ledgerOf) ?: return@map item
        if (!isLedgerCorrection(servedVersion, latestSeq, ledger.readStateVersion, ledger.maxReadSeq.toString())) return@map item
        item.copy(unreadCount = 0, firstUnreadMessageId = null, firstMentionMessageId = null, hasMention = false)
    }

/** Web `reportListRowsLowered`: the rows of [served] each application [stages] (in order) left lower. */
internal fun listRowsLowered(
    served: List<InboxItem>,
    before: List<InboxItem>,
    stages: List<Pair<ActivityListRowStep, List<InboxItem>>>,
    reason: String?,
    trigger: String?,
    rowPosition: Boolean,
    nowMillis: Long,
    ledgerOf: (scopeId: String) -> ActivityLedgerReadState? = { null },
): List<ActivityListRowLowered> {
    val counted = stages.map { (step, items) -> step to items.unreadByKey() }
    val final = counted.lastOrNull()?.second ?: return emptyList()
    val shown = before.unreadByKey()
    val servedByKey = served.unreadByKey()
    val reported = mutableSetOf<String>()
    return served.mapNotNull { item ->
        if (!item.isListRowLoweredRow()) return@mapNotNull null
        val key = item.key
        // One event per row, judged on the count the stages saw for it (the last served copy).
        if (key in reported || servedByKey[key] != item.unreadCount) return@mapNotNull null
        reported += key
        val servedUnread = item.unreadCount
        val after = final[key] ?: return@mapNotNull null
        if (after >= servedUnread) return@mapNotNull null
        val step = counted.firstOrNull { (_, counts) -> (counts[key] ?: servedUnread) < servedUnread }?.first ?: ActivityListRowStep.Merge
        val scopeId = item.listRowScopeId()
        val ledger = scopeId?.let(ledgerOf)
        ActivityListRowLowered(
            step = step,
            before = shown[key] ?: -1,
            served = servedUnread,
            after = after,
            fetchReason = reason ?: "none",
            fetchTrigger = trigger ?: "none",
            rowPosition = rowPosition,
            timestampMillis = nowMillis,
            rowKind = item.listRowKindWire(),
            scopeId = scopeId,
            rowLatestSeq = item.latestActivitySeq,
            servedReadVersion = item.readStateVersion,
            servedReadSeq = item.readStateMaxReadSeq,
            servedLatestActivitySeq = item.readStateLatestActivitySeq,
            ledgerReadVersion = ledger?.readStateVersion,
            ledgerReadSeq = ledger?.maxReadSeq?.toString(),
        )
    }
}

/** The attributes web `emitStateTransitionTrace` sends for it (key/meta plus the flat `inbox.lowered.*`). */
internal fun ActivityListRowLowered.stateTransitionTraceAttrs(): JsonObject =
    buildJsonObject {
        putJsonObject("key") {
            put("domain", ActivityInPlaceCheckTraceDomain)
            put("event", event)
            put("outcome", (if (corrected) ActivityInPlaceCheckOutcome.Noop else ActivityInPlaceCheckOutcome.Conflict).wireValue)
            put("entityId", "list")
        }
        putJsonObject("meta") {
            put("outcomeDetail", "step=${step.wireValue};before=$before;served=$served;after=$after")
            put("touched", 1)
            put("timestamp", timestampMillis)
        }
        put("inbox.lowered.step", step.wireValue)
        put("inbox.lowered.before", before.toString())
        put("inbox.lowered.served", served.toString())
        put("inbox.lowered.after", after.toString())
        put("inbox.lowered.fetch_reason", fetchReason)
        put("inbox.lowered.fetch_trigger", fetchTrigger)
        put("inbox.lowered.row_position", if (rowPosition) "true" else "false")
        put("inbox.lowered.row_kind", rowKind)
        put("inbox.lowered.scope_id", scopeId ?: "-")
        put("inbox.lowered.row_latest_seq", rowLatestSeq ?: "-")
        put("inbox.lowered.served_read_version", servedReadVersion?.toString() ?: "-")
        put("inbox.lowered.served_read_seq", servedReadSeq ?: "-")
        put("inbox.lowered.served_latest_activity_seq", servedLatestActivitySeq ?: "-")
        put("inbox.lowered.ledger_read_version", ledgerReadVersion?.toString() ?: "-")
        put("inbox.lowered.ledger_read_seq", ledgerReadSeq ?: "-")
    }
