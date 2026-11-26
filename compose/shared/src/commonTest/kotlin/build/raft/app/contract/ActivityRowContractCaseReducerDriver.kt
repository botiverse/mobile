package build.raft.app.contract

import build.raft.app.data.ActivityRowAnswer
import build.raft.app.data.ActivityRowEvent
import build.raft.app.data.ActivityRowState
import build.raft.app.data.ActivitySyncRowChange
import build.raft.app.data.applySyncInboxRowChange
import build.raft.app.data.ActivityUnreadMessage
import build.raft.app.data.ActivityUnreadRowKind
import build.raft.app.data.ActivityUnreadScope
import build.raft.app.data.ActivityUnreadTarget
import build.raft.app.data.ActivityUnreadViewer
import build.raft.app.data.activityLiveUnread
import build.raft.app.data.activityRowDisplayPosition
import build.raft.app.data.activityRowFromAnswer
import build.raft.app.data.reduceActivityRow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The `reducer` driver: an Activity row contract case through the app's pure port of the shared rule
 * ([reduceActivityRow], [activityLiveUnread]), as slock's own runner drives it
 * (`packages/shared/src/activityRowContractCaseRunner.ts`). Every agreed case runs here, whatever its
 * `knownGaps` say: those name a side's product path (the app's is SharedActivityStore, driven by
 * [ActivityRowCaseDrivers] with its [AppActivityRowKnownGaps]), never the shared rule itself.
 *
 * Returns the mismatches; empty = the case holds.
 */
internal fun runActivityRowCaseOnReducer(case: ActivityRowContractCase): List<String> {
    val expect = case.expect ?: return listOf("an agreed case has no expect")
    return when (case.rule) {
        "predicate" -> runPredicateOnReducer(case, expect)
        "position", "sync_inbox_row" -> runPositionOnReducer(case, expect)
        else -> listOf("unknown rule '${case.rule}'")
    }
}

private fun runPredicateOnReducer(case: ActivityRowContractCase, expect: ActivityRowExpectation): List<String> {
    val viewer = case.given.viewer ?: return listOf("no given.viewer")
    val scope = case.given.scope ?: return listOf("no given.scope")
    val message = case.given.message ?: return listOf("no given.message")
    val expected = expect.verdict ?: return listOf("no expected verdict")
    val verdict =
        activityLiveUnread(
            ActivityUnreadMessage(
                senderType = message.senderType,
                senderId = message.senderId,
                messageType = message.messageType,
                causalActorType = message.causalActorType,
                causalActorId = message.causalActorId,
                systemSubtype = message.systemSubtype,
                carriesSystemProvenance = message.carriesSystemProvenance,
                quietForPeople = message.quietForPeople,
                mentions = message.mentions.map { ActivityUnreadTarget(it.type, it.id) },
            ),
            ActivityUnreadViewer(viewer.type, viewer.id),
            ActivityUnreadScope(
                rowKind =
                    when (scope.rowKind) {
                        ActivityRowKind.Channel -> ActivityUnreadRowKind.Channel
                        ActivityRowKind.Dm -> ActivityUnreadRowKind.Dm
                        ActivityRowKind.Thread -> ActivityUnreadRowKind.Thread
                        ActivityRowKind.Task -> ActivityUnreadRowKind.Task
                    },
                threadFollowed = scope.threadFollowed ?: true,
                activityMuted = scope.activityMuted ?: false,
                readOnArrival = scope.readOnArrival ?: false,
            ),
        )
    val actual = ActivityRowVerdict(counts = verdict.counts, mention = verdict.mention, moves = verdict.moves.takeIf { expected.moves != null })
    return if (actual == expected) emptyList() else listOf("verdict: expected $expected, got $actual")
}

private fun ActivityRowServed.toAnswer() = ActivityRowAnswer(unreadCount, hasMention, position, mentionOnly, readSeq)

private fun ActivityRowStep.toEvent(): ActivityRowEvent =
    when (this) {
        is ActivityRowStep.LiveMessage -> ActivityRowEvent.Live(seq, counts, mentionsViewer, moves)
        is ActivityRowStep.Answer -> ActivityRowEvent.Answer(row.toAnswer())
        is ActivityRowStep.Read -> ActivityRowEvent.Read(seq)
        ActivityRowStep.MarkUnread -> ActivityRowEvent.MarkUnread
        is ActivityRowStep.SyncDelete -> error("syncDelete is not a reducer event (applySyncInboxRowChange)")
    }

/** The row as the case reads it: `position` is the shown one, `mentionOnly` the reducer's own flag. */
private fun ActivityRowState.shown(key: String): JsonElement =
    when (key) {
        "unreadCount" -> JsonPrimitive(unreadCount)
        "hasMention" -> JsonPrimitive(hasMention)
        "position" -> JsonPrimitive(activityRowDisplayPosition(this))
        "mentionOnly" -> JsonPrimitive(mentionOnly)
        else -> error("unknown row key $key")
    }

/**
 * `position` and `sync_inbox_row`: slock's runner drives both the same way; in a `sync_inbox_row` case an
 * `answer` is an `inbox_row` upsert and a `syncDelete` a delete, both through [applySyncInboxRowChange].
 */
private fun runPositionOnReducer(case: ActivityRowContractCase, expect: ActivityRowExpectation): List<String> {
    val given = case.given.row ?: return listOf("no given.row")
    val expectedReread = expect.reread ?: return listOf("no expected reread")
    val sync = case.rule == "sync_inbox_row"
    val failures = mutableListOf<String>()
    var row: ActivityRowState? = activityRowFromAnswer(given.toAnswer())
    var reread = false

    fun check(where: String, expected: JsonObject?, expectedRereadHere: Boolean?, expectedRemoved: Boolean?) {
        if (expectedRemoved != null && expectedRemoved != (row == null)) {
            failures += "$where: removed: expected $expectedRemoved, got ${row == null}"
        }
        expected?.forEach { (key, value) ->
            val held = row
            if (held == null) {
                failures += "$where: the row is not held (expected $key $value)"
            } else {
                val actual = held.shown(key)
                if (actual != value) failures += "$where: $key: expected $value, got $actual"
            }
        }
        if (expectedRereadHere != null && expectedRereadHere != reread) {
            failures += "$where: reread: expected $expectedRereadHere, got $reread"
        }
    }

    case.checkpoints.filter { it.afterStep !in 1..case.steps.size }.forEach {
        failures += "checkpoint after step ${it.afterStep} is out of range (${case.steps.size} steps)"
    }
    for ((index, step) in case.steps.withIndex()) {
        when {
            step is ActivityRowStep.SyncDelete -> {
                if (!sync) return failures + "step ${index + 1}: syncDelete is a sync_inbox_row act"
                row = applySyncInboxRowChange(row, ActivitySyncRowChange.Delete(step.position)).row
            }
            sync && step is ActivityRowStep.Answer -> {
                val result = applySyncInboxRowChange(row, ActivitySyncRowChange.Upsert(step.row.toAnswer()))
                row = result.row
                reread = reread || result.reread
            }
            else -> {
                val held = row ?: return failures + "step ${index + 1}: the row was removed by an earlier step"
                val result = reduceActivityRow(held, step.toEvent())
                row = result.row
                reread = reread || result.reread
            }
        }
        case.checkpoints.filter { it.afterStep == index + 1 }.forEach { checkpoint ->
            check("after step ${index + 1}", checkpoint.row, checkpoint.reread, checkpoint.removed)
        }
    }
    check("at the end", expect.row, expectedReread, expect.removed)
    return failures
}
