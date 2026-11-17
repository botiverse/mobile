package build.raft.app.contract

import build.raft.app.data.ActivityRowAnswer
import build.raft.app.data.ActivityRowEvent
import build.raft.app.data.ActivityRowState
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
        "position" -> runPositionOnReducer(case, expect)
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

private fun ActivityRowServed.toAnswer() = ActivityRowAnswer(unreadCount, hasMention, position, mentionOnly)

private fun ActivityRowStep.toEvent(): ActivityRowEvent =
    when (this) {
        is ActivityRowStep.LiveMessage -> ActivityRowEvent.Live(seq, counts, mentionsViewer, moves)
        is ActivityRowStep.Answer -> ActivityRowEvent.Answer(row.toAnswer())
        is ActivityRowStep.Read -> ActivityRowEvent.Read(seq)
        ActivityRowStep.MarkUnread -> ActivityRowEvent.MarkUnread
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

private fun runPositionOnReducer(case: ActivityRowContractCase, expect: ActivityRowExpectation): List<String> {
    val given = case.given.row ?: return listOf("no given.row")
    val expectedReread = expect.reread ?: return listOf("no expected reread")
    val failures = mutableListOf<String>()
    var row = activityRowFromAnswer(given.toAnswer())
    var reread = false

    fun check(where: String, expected: JsonObject?, expectedRereadHere: Boolean?) {
        expected?.forEach { (key, value) ->
            val actual = row.shown(key)
            if (actual != value) failures += "$where: $key: expected $value, got $actual"
        }
        if (expectedRereadHere != null && expectedRereadHere != reread) {
            failures += "$where: reread: expected $expectedRereadHere, got $reread"
        }
    }

    case.checkpoints.filter { it.afterStep !in 1..case.steps.size }.forEach {
        failures += "checkpoint after step ${it.afterStep} is out of range (${case.steps.size} steps)"
    }
    case.steps.forEachIndexed { index, step ->
        val result = reduceActivityRow(row, step.toEvent())
        row = result.row
        reread = reread || result.reread
        case.checkpoints.filter { it.afterStep == index + 1 }.forEach { checkpoint ->
            check("after step ${index + 1}", checkpoint.row, checkpoint.reread)
        }
    }
    check("at the end", expect.row, expectedReread)
    return failures
}
