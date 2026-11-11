package build.raft.app.data

import build.raft.app.model.InboxItemKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * `mobile_inbox_inplace_v0` drift check, web `checkInPlaceDrift` (botiverse/slock
 * packages/web/src/store/inboxInPlaceUnread.ts, #13694): each first-window answer of the All list
 * is checked against the rows a live message patched in place, so the drift the in-place patch
 * leaves on screen is measured the same way on web and mobile.
 *
 * One check per patched row the answer can judge, sent as the web's `slock.state.transition`
 * (domain `inbox`, event `activity:inplace_check`): outcome `noop` when the answer agrees,
 * `conflict` when it does not, with flat attributes `inbox.inplace.trigger` (message_new /
 * thread_reply), `inbox.inplace.row_existed`, `inbox.inplace.result` and
 * `inbox.inplace.sample_rate` (every conflict is sent; one `noop` in [ActivityInPlaceNoopSample]).
 * An answer still behind the patched row judges nothing: the row waits for the next answer, up to
 * [ActivityInPlaceCheckTtlMillis]. Mobile has no persisted-read event yet, so `read_persisted` is
 * never a trigger here.
 */
internal const val ActivityInPlaceCheckTtlMillis = 90_000L
internal const val ActivityInPlaceExpectationsCap = 200
internal const val ActivityInPlaceNoopSample = 20

internal const val ActivityInPlaceCheckTraceDomain = "inbox"
internal const val ActivityInPlaceCheckTraceEvent = "activity:inplace_check"

enum class ActivityInPlaceCheckOutcome(val wireValue: String) {
    Noop("noop"),
    Conflict("conflict"),
}

/** One `activity:inplace_check`, as web emits it. */
data class ActivityInPlaceCheck(
    /** Web `getInboxItemKey` of the row (`channel:<id>`, `dm:<id>`, `thread:<id>`). */
    val entityId: String,
    /** The live event that patched the row (or found it absent). */
    val trigger: InboxRefreshTrigger,
    val rowExisted: Boolean,
    /** `match`, `appeared`, `absent`, or the drifts joined by `+` (`served_ahead`, `unread`, `mention`, `order`). */
    val result: String,
    val outcome: ActivityInPlaceCheckOutcome,
    /** 1 for a conflict, [ActivityInPlaceNoopSample] for a sampled noop. */
    val sampleRate: Int,
    val outcomeDetail: String,
    val timestampMillis: Long,
)

/**
 * The attributes web `emitStateTransitionTrace` sends (shared `buildStateTransitionTraceAttrs`
 * key/meta plus the flat `inbox.inplace.*` attributes).
 */
internal fun ActivityInPlaceCheck.stateTransitionTraceAttrs(): JsonObject =
    buildJsonObject {
        putJsonObject("key") {
            put("domain", ActivityInPlaceCheckTraceDomain)
            put("event", ActivityInPlaceCheckTraceEvent)
            put("outcome", outcome.wireValue)
            put("entityId", entityId)
        }
        putJsonObject("meta") {
            put("outcomeDetail", outcomeDetail)
            put("touched", 1)
            put("timestamp", timestampMillis)
        }
        put("inbox.inplace.trigger", trigger.wireValue)
        put("inbox.inplace.row_existed", if (rowExisted) "true" else "false")
        put("inbox.inplace.result", result)
        put("inbox.inplace.sample_rate", sampleRate.toString())
    }

/** Web `inboxItemChannelId`: the conversation a row is (a thread's own channel); none for other rows. */
internal fun InboxItem.inPlaceScopeId(): String? =
    when (kind) {
        InboxItemKind.Thread -> (threadChannelId ?: channelId).value
        InboxItemKind.Channel, InboxItemKind.DirectMessage -> channelId.value
        else -> null
    }

/** Not thread-safe: the store calls it under its realtime owner lock. */
internal class ActivityInPlaceDriftCheck(private val nowMillis: () -> Long) {
    private class Expectation(
        val trigger: InboxRefreshTrigger,
        val existed: Boolean,
        /** The row's seq once patched; the answer is judged only at this seq. */
        val seq: String?,
        val until: Long,
    )

    private val expectations = LinkedHashMap<String, Expectation>()
    private var noopChecks = 0L

    val isEmpty: Boolean
        get() = expectations.isEmpty()

    fun clear() {
        expectations.clear()
    }

    /** A row patched in place by a live message. */
    fun notePatched(row: InboxItem, trigger: InboxRefreshTrigger) {
        val scopeId = row.inPlaceScopeId() ?: return
        remember(scopeId, Expectation(trigger, existed = true, seq = row.latestActivitySeq, until = 0L))
    }

    /** A live message for a row the list does not show: the re-read brings it; note whether it came. */
    fun noteAbsent(scopeId: String, trigger: InboxRefreshTrigger, seq: String?) {
        if (expectations[scopeId]?.existed == true) return
        remember(scopeId, Expectation(trigger, existed = false, seq = seq, until = 0L))
    }

    private fun remember(scopeId: String, expected: Expectation) {
        val now = nowMillis()
        expectations.remove(scopeId)
        expectations[scopeId] =
            Expectation(expected.trigger, expected.existed, expected.seq, until = now + ActivityInPlaceCheckTtlMillis)
        if (expectations.size <= ActivityInPlaceExpectationsCap) return
        expectations.entries.removeAll { (_, known) -> now > known.until }
        while (expectations.size > ActivityInPlaceExpectationsCap) {
            expectations.remove(expectations.keys.first())
        }
    }

    /**
     * A first-window answer of the All list, newest first: judge each noted row against what the
     * answer puts on screen ([served], after the local read/Done holds) and the list shown now
     * ([local]). Returns the checks to send (all conflicts, sampled noops).
     */
    fun check(local: List<InboxItem>, served: List<InboxItem>): List<ActivityInPlaceCheck> {
        if (expectations.isEmpty()) return emptyList()
        val now = nowMillis()
        val localKeys = local.mapTo(HashSet()) { it.key }
        val servedKeys = served.mapTo(HashSet()) { it.key }
        val checks = mutableListOf<ActivityInPlaceCheck>()
        val iterator = expectations.entries.iterator()
        while (iterator.hasNext()) {
            val (scopeId, expected) = iterator.next()
            if (now > expected.until) {
                iterator.remove()
                continue
            }
            val servedRow = served.firstOrNull { it.inPlaceScopeId() == scopeId }
            val localRow = local.firstOrNull { it.inPlaceScopeId() == scopeId }
            val result: String
            var detail = ""
            if (!expected.existed) {
                // Still absent: maybe the lag; wait. Arrived: the re-read was what showed it.
                if (servedRow == null) continue
                result = "appeared"
                detail = ";served_unread=${servedRow.unreadCount}"
            } else if (localRow == null || localRow.latestActivitySeq != expected.seq) {
                // Done, read away, or moved on since: nothing patched in place to judge.
                iterator.remove()
                continue
            } else if (servedRow == null) {
                result = "absent"
            } else {
                val seqOrder = compareActivitySeq(servedRow.latestActivitySeq, expected.seq)
                if (seqOrder < 0) continue
                val localIndex = sharedIndex(local, servedKeys, localRow.key)
                val servedIndex = sharedIndex(served, localKeys, localRow.key)
                val drift =
                    buildList {
                        if (seqOrder > 0) add("served_ahead")
                        if (servedRow.unreadCount != localRow.unreadCount) add("unread")
                        if (servedRow.hasMention != localRow.hasMention) add("mention")
                        if (localIndex != servedIndex) add("order")
                    }
                result = if (drift.isEmpty()) "match" else drift.joinToString("+")
                detail =
                    ";unread=${localRow.unreadCount}/${servedRow.unreadCount}" +
                        ";mention=${if (localRow.hasMention) 1 else 0}/${if (servedRow.hasMention) 1 else 0}" +
                        ";order=$localIndex/$servedIndex"
            }
            iterator.remove()
            val agrees = result == "match" || result == "appeared"
            // Every conflict; one agreement in ActivityInPlaceNoopSample (counted, so exact over a session).
            if (agrees && noopChecks++ % ActivityInPlaceNoopSample != 0L) continue
            checks +=
                ActivityInPlaceCheck(
                    entityId = localRow?.key ?: servedRow?.key ?: scopeId,
                    trigger = expected.trigger,
                    rowExisted = expected.existed,
                    result = result,
                    outcome = if (agrees) ActivityInPlaceCheckOutcome.Noop else ActivityInPlaceCheckOutcome.Conflict,
                    sampleRate = if (agrees) ActivityInPlaceNoopSample else 1,
                    outcomeDetail =
                        "trigger=${expected.trigger.wireValue};existed=${if (expected.existed) 1 else 0};result=$result$detail",
                    timestampMillis = now,
                )
        }
        return checks
    }

    /** Index of [key] among the rows both lists have, so a row only one side lists does not shift it. */
    private fun sharedIndex(rows: List<InboxItem>, others: Set<String>, key: String): Int =
        rows.asSequence().map { it.key }.filter { it in others }.indexOf(key)
}
