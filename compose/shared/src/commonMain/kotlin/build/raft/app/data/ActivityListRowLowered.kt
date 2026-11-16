package build.raft.app.data

import build.raft.app.model.InboxItemKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/*
 * `inbox_row_position_v1` (#triage:#244 diagnostic), web `reportListRowsLowered`
 * (botiverse/slock packages/web/src/store/inboxListRowLowered.ts): a whole-list answer that leaves a
 * row's unread *below* what the answer served for it sends one `activity:list_row_lowered`
 * (`slock.state.transition`, domain `inbox`, outcome `conflict`) naming the first step of the
 * answer's application that lowered the row, with the counts and why the list was read. Counts and
 * step names only: no row, channel or title. Sent only when it happens, so its count is the
 * defect's rate.
 *
 * The app applies an answer in this order: `local_read` (a Read here: the local read overlay),
 * `done` (a local Done), `merge` (the realtime fold and the per-row position merge). The web's
 * `ledger`, `read_hold` and `projection` steps have no separate stage here (the app's read ledger
 * and read holds are its local read overlay, and it has no loaded-messages projection step).
 */

internal const val ActivityListRowLoweredTraceEvent = "activity:list_row_lowered"

/** Web `ListRowStep`. */
enum class ActivityListRowStep(val wireValue: String) {
    Ledger("ledger"),
    LocalRead("local_read"),
    Done("done"),
    ReadHold("read_hold"),
    Merge("merge"),
    Projection("projection"),
}

/** One `activity:list_row_lowered`, as web emits it. */
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
)

private fun InboxItem.isListRowLoweredRow(): Boolean =
    kind != InboxItemKind.MentionAction && kind != InboxItemKind.Object

private fun List<InboxItem>.unreadByKey(): Map<String, Int> =
    asSequence().filter { it.isListRowLoweredRow() }.associate { it.key to it.unreadCount }

/** Web `reportListRowsLowered`: the rows of [served] each application [stages] (in order) left lower. */
internal fun listRowsLowered(
    served: List<InboxItem>,
    before: List<InboxItem>,
    stages: List<Pair<ActivityListRowStep, List<InboxItem>>>,
    reason: String?,
    trigger: String?,
    rowPosition: Boolean,
    nowMillis: Long,
): List<ActivityListRowLowered> {
    val counted = stages.map { (step, items) -> step to items.unreadByKey() }
    val final = counted.lastOrNull()?.second ?: return emptyList()
    val shown = before.unreadByKey()
    return served.unreadByKey().mapNotNull { (key, servedUnread) ->
        val after = final[key] ?: return@mapNotNull null
        if (after >= servedUnread) return@mapNotNull null
        val step = counted.firstOrNull { (_, counts) -> (counts[key] ?: servedUnread) < servedUnread }?.first ?: ActivityListRowStep.Merge
        ActivityListRowLowered(
            step = step,
            before = shown[key] ?: -1,
            served = servedUnread,
            after = after,
            fetchReason = reason ?: "none",
            fetchTrigger = trigger ?: "none",
            rowPosition = rowPosition,
            timestampMillis = nowMillis,
        )
    }
}

/** The attributes web `emitStateTransitionTrace` sends for it (key/meta plus the flat `inbox.lowered.*`). */
internal fun ActivityListRowLowered.stateTransitionTraceAttrs(): JsonObject =
    buildJsonObject {
        putJsonObject("key") {
            put("domain", ActivityInPlaceCheckTraceDomain)
            put("event", ActivityListRowLoweredTraceEvent)
            put("outcome", ActivityInPlaceCheckOutcome.Conflict.wireValue)
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
    }
