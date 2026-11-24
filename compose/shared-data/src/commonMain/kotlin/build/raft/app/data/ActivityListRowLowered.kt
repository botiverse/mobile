package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

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
 * - `activity:list_row_corrected` too (slock #15197): the `local_read` step, when this client's own
 *   read ack for the row's scope lands within [ActivityLocalReadAckWindowMillis] and its read covers
 *   the answer's latest activity (the answer was read while this client's read was in flight). Such
 *   a row is held until the ack or the window ends, then sent once ([ActivityLocalReadAckHold]);
 *   `ack_read_seq`, `ack_delay_ms` and `held_end` say what decided it.
 * - `activity:list_row_lowered` (outcome `conflict`): every other step (and a ledger or local_read
 *   step whose facts don't show that cover). This is the defect count.
 *
 * Added with web #15560 (event names unchanged, so the existing counts keep their meaning):
 * - `inbox.lowered.class` ([ActivityListRowLowered.loweredClass]): `expected` (a correction, or a
 *   merge lowering of a row whose answer was read under an older read than this client's accepted
 *   one), `ack_pending` (a `local_read` hold that ended with no read ack: the window, or the app's
 *   own flushes, its pagehide), `unexplained` (everything else: the defect count is
 *   `list_row_lowered` with class `unexplained`).
 * - `inbox.lowered.dedupe` / `inbox.lowered.suppressed` ([ActivityListRowLoweredDedupe]): the same
 *   lowering of a row is sent once per [ActivityListRowLoweredReportWindowMillis] (`first`); the
 *   re-reads inside the window send nothing, and when some were left unsent the next one after the
 *   window (within one more window) is sent as `repeat` with how many were not sent.
 * - `held_end=ack_before_hold`: this client's read ack landed before the answer registered the hold
 *   (kept [ActivityLocalReadRecentAckMillis]) and covers the answer's latest activity;
 *   `ack_delay_ms` is then negative (the ack's lead).
 * - `inbox.lowered.proj_*`: always `-`. They describe web's loaded-messages projection step, which
 *   the app does not have.
 *
 * The app applies an answer in this order: `local_read` (a Read here, and reads from elsewhere the
 * app held: the local read overlay), `done` (a local Done), `merge` (the realtime fold and the
 * per-row position merge). The app has no ledger step of its own: [ledgerAdjudicated] is web's
 * (`adjudicateServedUnreadAgainstLedger`) run on the answer against the app's accepted read ledger,
 * placed first so a row it covers is named `ledger` as on web; it changes nothing shown. The web's
 * `read_hold` and `projection` steps have no stage here.
 */

@RaftCoreInternalApi const val ActivityListRowLoweredTraceEvent = "activity:list_row_lowered"
@RaftCoreInternalApi const val ActivityListRowCorrectedTraceEvent = "activity:list_row_corrected"

/**
 * Web `inbox.lowered.held_end`: how a held `local_read` row ended. Web sends `pagehide` for a page
 * that closes; the app flushes on [Background], [ServerSwitch] and [Signout] instead.
 */
enum class ActivityListRowHeldEnd(val wireValue: String) {
    /** This client's read ack for the scope settled it. */
    Ack("ack"),
    /** This client's read ack landed before the hold and covers the answer's latest activity (web #15560). */
    AckBeforeHold("ack_before_hold"),
    /** No usable ack within [ActivityLocalReadAckWindowMillis]. */
    Window("window"),
    /** The app went to the background. */
    Background("background"),
    /** The store switched to another server. */
    ServerSwitch("server_switch"),
    /** The session was cleared or the store's shell scope was released. */
    Signout("signout"),
}

/** Web `LoweredClass` (`inbox.lowered.class`, slock #15560). */
enum class ActivityListRowLoweredClass(val wireValue: String) {
    Expected("expected"),
    AckPending("ack_pending"),
    Unexplained("unexplained"),
}

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
    /** `local_read` only: the `maxReadSeq` of this client's read ack that settled the row; null without one. */
    val ackReadSeq: String? = null,
    /** `local_read` only: how long after the lowering that ack landed; null without one. */
    val ackDelayMs: Long? = null,
    /** `local_read` only: how the hold ended; null for a row that was never held. */
    val heldEnd: ActivityListRowHeldEnd? = null,
    /** Web `inbox.lowered.dedupe`: false for the first report of this lowering, true for a `repeat`. */
    val repeat: Boolean = false,
    /** Web `inbox.lowered.suppressed`: the re-reads of this lowering not sent in the window before (0 on `first`). */
    val suppressed: Int = 0,
    /** Web `getInboxItemKey` of the row (not sent; part of [dedupeKey]). */
    val rowKey: String = "",
) {
    /**
     * Web `isLedgerCorrection` on the `ledger` step; on the `local_read` step, this client's read ack
     * covers the answer's latest activity (web `covers(ack.readSeq, servedLatestActivitySeq)`).
     */
    val corrected: Boolean
        get() =
            when (step) {
                ActivityListRowStep.Ledger -> isLedgerCorrection(servedReadVersion, servedLatestActivitySeq, ledgerReadVersion, ledgerReadSeq)
                ActivityListRowStep.LocalRead -> ackReadSeq != null && listRowSeqCovers(ackReadSeq, servedLatestActivitySeq)
                else -> false
            }

    val event: String
        get() = if (corrected) ActivityListRowCorrectedTraceEvent else ActivityListRowLoweredTraceEvent

    /**
     * Web `classify`: a correction is expected; a `local_read` hold that ended with no ack is
     * `ack_pending` (the app's background / server switch / sign-out flushes are its pagehide); a
     * merge lowering of an answer behind this client's read is expected; anything else unexplained.
     */
    val loweredClass: ActivityListRowLoweredClass
        get() =
            when {
                corrected -> ActivityListRowLoweredClass.Expected
                step == ActivityListRowStep.LocalRead && heldEnd != null &&
                    heldEnd != ActivityListRowHeldEnd.Ack && heldEnd != ActivityListRowHeldEnd.AckBeforeHold ->
                    ActivityListRowLoweredClass.AckPending
                (step == ActivityListRowStep.Merge || step == ActivityListRowStep.Projection) && answerBehindClientRead() ->
                    ActivityListRowLoweredClass.Expected
                else -> ActivityListRowLoweredClass.Unexplained
            }

    /** Web `answerBehindClientRead` (no projection here): a newer accepted read, or one covering the answer's latest activity. */
    private fun answerBehindClientRead(): Boolean {
        if (servedReadVersion != null && ledgerReadVersion != null && ledgerReadVersion > servedReadVersion) return true
        return listRowSeqCovers(ledgerReadSeq, servedLatestActivitySeq)
    }

    /** Web `dedupeKey`: the same lowering of the same row (row, step, served read facts, served, after). */
    @RaftCoreInternalApi val dedupeKey: String
        get() =
            listOf(rowKey, step.wireValue, servedReadVersion?.toString() ?: "-", servedReadSeq ?: "-", servedLatestActivitySeq ?: "-", served, after)
                .joinToString("|")
}

/** Web `LOWERED_REPORT_WINDOW_MS`: the same lowering of a row is sent once per this window. */
@RaftCoreInternalApi const val ActivityListRowLoweredReportWindowMillis = 90_000L

/**
 * Web `reportedLowerings` / `repeatOf` / `expireReported` (slock #15560): one report per lowering
 * per [windowMillis] (a local read's hold re-lowers its row on every re-read). [admit] answers null
 * for a re-read inside the window (counted as suppressed), else the row with `dedupe` and
 * `suppressed` set. Not thread-safe: the store calls it under its lock.
 */
@RaftCoreInternalApi class ActivityListRowLoweredDedupe(private val windowMillis: Long = ActivityListRowLoweredReportWindowMillis) {
    private class Reported(val at: Long, var suppressed: Int)
    private class Repeat(val suppressed: Int, val endedAt: Long)

    private val reported = mutableMapOf<String, Reported>()
    private val repeatOf = mutableMapOf<String, Repeat>()

    fun admit(row: ActivityListRowLowered, nowMillis: Long): ActivityListRowLowered? {
        val key = row.dedupeKey
        expire(nowMillis)
        reported[key]?.let { seen ->
            seen.suppressed += 1
            return null
        }
        val previous = repeatOf.remove(key)?.suppressed
        reported[key] = Reported(nowMillis, 0)
        return row.copy(repeat = previous != null, suppressed = previous ?: 0)
    }

    private fun expire(nowMillis: Long) {
        repeatOf.entries.removeAll { (_, entry) -> nowMillis - entry.endedAt > windowMillis }
        val ended = reported.entries.filter { (_, entry) -> nowMillis - entry.at > windowMillis }
        ended.forEach { (key, entry) ->
            reported.remove(key)
            // The window ended at its end, not when this pass noticed it.
            if (entry.suppressed > 0) repeatOf[key] = Repeat(entry.suppressed, entry.at + windowMillis)
        }
    }

    fun clear() {
        reported.clear()
        repeatOf.clear()
    }
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
    return listRowSeqCovers(ledgerReadSeq, servedLatestActivitySeq)
}

/** Web `parseSeq`: a read or activity seq as a decimal string, or null when it is not a non-negative integer. */
@RaftCoreInternalApi fun parseListRowSeq(value: String?): String? = value?.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }

/** Web `parseSeq` for a numeric seq: null when negative. */
@RaftCoreInternalApi fun parseListRowSeq(value: Long?): String? = value?.takeIf { it >= 0 }?.toString()

/** Web `covers`: both seqs usable and [readSeq] >= [latestSeq]. */
@RaftCoreInternalApi fun listRowSeqCovers(readSeq: String?, latestSeq: String?): Boolean {
    val read = parseListRowSeq(readSeq)?.trimStart('0')?.ifEmpty { "0" } ?: return false
    val latest = parseListRowSeq(latestSeq)?.trimStart('0')?.ifEmpty { "0" } ?: return false
    return compareActivitySeq(read, latest) >= 0
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
@RaftCoreInternalApi fun ledgerAdjudicated(served: List<InboxItem>, ledgerOf: (scopeId: String) -> ActivityLedgerReadState?): List<InboxItem> =
    served.map { item ->
        if (!item.isListRowLoweredRow() || item.unreadCount == 0) return@map item
        val servedVersion = item.readStateVersion ?: return@map item
        val latestSeq = item.readStateLatestActivitySeq ?: return@map item
        val ledger = item.listRowScopeId()?.let(ledgerOf) ?: return@map item
        if (!isLedgerCorrection(servedVersion, latestSeq, ledger.readStateVersion, ledger.maxReadSeq.toString())) return@map item
        item.copy(unreadCount = 0, firstUnreadMessageId = null, firstMentionMessageId = null, hasMention = false)
    }

/** Web `reportListRowsLowered`: the rows of [served] each application [stages] (in order) left lower. */
@RaftCoreInternalApi fun listRowsLowered(
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
            rowKey = key,
        )
    }
}

/** The attributes web `emitStateTransitionTrace` sends for it (key/meta plus the flat `inbox.lowered.*`). */
@RaftCoreInternalApi fun ActivityListRowLowered.stateTransitionTraceAttrs(): JsonObject =
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
        put("inbox.lowered.ack_read_seq", ackReadSeq ?: "-")
        put("inbox.lowered.ack_delay_ms", ackDelayMs?.toString() ?: "-")
        put("inbox.lowered.held_end", heldEnd?.wireValue ?: "-")
        put("inbox.lowered.class", loweredClass.wireValue)
        put("inbox.lowered.dedupe", if (repeat) "repeat" else "first")
        put("inbox.lowered.suppressed", suppressed.toString())
        // Web's loaded-messages projection step; the app has none (`-`, as web without a projection).
        ActivityListRowProjectionAttrs.forEach { put(it, "-") }
    }

private val ActivityListRowProjectionAttrs =
    listOf(
        "inbox.lowered.proj_unread",
        "inbox.lowered.proj_read_seq",
        "inbox.lowered.proj_through_seq",
        "inbox.lowered.proj_loaded_above_read",
        "inbox.lowered.proj_loaded_count",
        "inbox.lowered.proj_loaded_min_seq",
        "inbox.lowered.proj_coverage",
    )
