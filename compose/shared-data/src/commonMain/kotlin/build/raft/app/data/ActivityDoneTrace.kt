package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import build.raft.app.model.InboxItemKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Mobile parity with `botiverse/slock` `packages/web/src/utils/activityDoneTrace.ts` (#16943,
 * `#proj-o11y:#53`): traces what happens to an Activity conversation row after the person marks
 * it Done, using identical event names and attributes so web and mobile can be queried together
 * under `otel.logs` (differentiated by `clientPlatform`).
 *
 * Closed sets, booleans and numbers only; no ids, names or seqs.
 */
@RaftCoreInternalApi const val ActivityItemReappearedTraceName = "slock.activity.item_reappeared"
@RaftCoreInternalApi const val ActivityDoneCommittedTraceName = "slock.activity.done_committed"

@RaftCoreInternalApi enum class ActivityReappearCause(val wireValue: String) {
    SuppressionMarkerNewer("suppression_marker_newer"),
    MarkerNull("marker_null"),
    DoneFailed("done_failed"),
    Undo("undo"),
}

@RaftCoreInternalApi enum class ActivityDoneCommitTrigger(val wireValue: String) {
    ToastClosed("toast_closed"),
    NextRow("next_row"),
    PageHidden("page_hidden"),
    NoToast("no_toast"),
    FrontierCatchUp("frontier_catch_up"),
}

@RaftCoreInternalApi enum class ActivitySeqChange(val wireValue: String) {
    Latest("latest"),
    Frontier("frontier"),
    Both("both"),
    None("none"),
}

@RaftCoreInternalApi enum class ActivityDoneTraceRowKind(val wireValue: String) {
    Channel("channel"),
    Dm("dm"),
    Thread("thread"),
}

@RaftCoreInternalApi fun InboxItem.activityDoneTraceRowKind(): ActivityDoneTraceRowKind? =
    when (kind) {
        InboxItemKind.Channel -> ActivityDoneTraceRowKind.Channel
        InboxItemKind.DirectMessage -> ActivityDoneTraceRowKind.Dm
        InboxItemKind.Thread -> ActivityDoneTraceRowKind.Thread
        InboxItemKind.MentionAction,
        InboxItemKind.Object -> null
    }

@RaftCoreInternalApi sealed interface ActivityDoneTraceEvent {
    val traceName: String
    fun toTraceAttrs(): JsonObject

    @RaftCoreInternalApi data class ItemReappeared(
        val cause: ActivityReappearCause,
        val rowKind: ActivityDoneTraceRowKind,
        val wasUnreadAtClick: Boolean,
        val msSinceClick: Long,
        val seqChanged: ActivitySeqChange,
    ) : ActivityDoneTraceEvent {
        override val traceName: String = ActivityItemReappearedTraceName

        override fun toTraceAttrs(): JsonObject =
            buildJsonObject {
                put("cause", cause.wireValue)
                put("row_kind", rowKind.wireValue)
                put("was_unread_at_click", wasUnreadAtClick)
                put("ms_since_click", msSinceClick.coerceAtLeast(0L))
                put("seq_changed", seqChanged.wireValue)
            }
    }

    @RaftCoreInternalApi data class DoneCommitted(
        val trigger: ActivityDoneCommitTrigger,
        val rowKind: ActivityDoneTraceRowKind,
        val wasUnreadAtClick: Boolean,
        val waitMs: Long,
        val frontierCaughtUp: Boolean,
    ) : ActivityDoneTraceEvent {
        override val traceName: String = ActivityDoneCommittedTraceName

        override fun toTraceAttrs(): JsonObject =
            buildJsonObject {
                put("trigger", trigger.wireValue)
                put("row_kind", rowKind.wireValue)
                put("was_unread_at_click", wasUnreadAtClick)
                put("wait_ms", waitMs.coerceAtLeast(0L))
                put("frontier_caught_up", frontierCaughtUp)
            }
    }
}
