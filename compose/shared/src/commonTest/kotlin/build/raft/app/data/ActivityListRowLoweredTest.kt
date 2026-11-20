package build.raft.app.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `inbox_row_position_v1` (#triage:#244): web `reportListRowsLowered`'s split (botiverse/slock
 * packages/web/src/store/inboxListRowLowered.ts, tests/inboxListRowLowered.test.ts, slock #15174 @
 * c240fe1a4d): a `ledger` step whose newer read covers the answer's latest activity is
 * `activity:list_row_corrected` (`noop`); every other lowering stays `activity:list_row_lowered`.
 */
class ActivityListRowLoweredTest {
    /** A channel row served at [unread] with the answer's read state: version 4, read 90, latest 100. */
    private fun served(channelId: String, unread: Int = 3, version: Long? = 4L) =
        activityItem(channelId, unreadCount = unread).copy(
            latestActivitySeq = "100",
            readStateVersion = version,
            readStateMaxReadSeq = "90",
            readStateLatestActivitySeq = "100"
        )

    private fun lowered(
        served: List<InboxItem>,
        stages: List<Pair<ActivityListRowStep, List<InboxItem>>>,
        ledger: ActivityLedgerReadState?
    ): List<ActivityListRowLowered> =
        listRowsLowered(
            served = served,
            before = emptyList(),
            stages = stages,
            reason = "reconnect",
            trigger = null,
            rowPosition = true,
            nowMillis = 1L,
            ledgerOf = { ledger }
        )

    @Test
    fun onlyALedgerStepWithANewerReadCoveringTheLatestActivityIsACorrection() {
        val read = activityItem("a", unreadCount = 0)
        val ledgerStep = listOf(ActivityListRowStep.Ledger to listOf(read), ActivityListRowStep.Merge to listOf(read))
        val readHoldStep =
            listOf(
                ActivityListRowStep.Ledger to listOf(served("a")),
                ActivityListRowStep.ReadHold to listOf(read),
                ActivityListRowStep.Merge to listOf(read)
            )
        // (case, served row, stages, ledger, expected event)
        val cases =
            listOf(
                Triple("newer version, read covers latest", served("a") to ledgerStep, ActivityLedgerReadState(maxReadSeq = 100L, readStateVersion = 5L) to "activity:list_row_corrected"),
                Triple("newer version, read past latest", served("a") to ledgerStep, ActivityLedgerReadState(maxReadSeq = 101L, readStateVersion = 5L) to "activity:list_row_corrected"),
                Triple("same version", served("a") to ledgerStep, ActivityLedgerReadState(maxReadSeq = 100L, readStateVersion = 4L) to "activity:list_row_lowered"),
                Triple("older version", served("a") to ledgerStep, ActivityLedgerReadState(maxReadSeq = 100L, readStateVersion = 3L) to "activity:list_row_lowered"),
                Triple("read below latest", served("a") to ledgerStep, ActivityLedgerReadState(maxReadSeq = 99L, readStateVersion = 5L) to "activity:list_row_lowered"),
                Triple("no served read state", served("a", version = null) to ledgerStep, ActivityLedgerReadState(maxReadSeq = 100L, readStateVersion = 5L) to "activity:list_row_lowered"),
                Triple("no ledger read", served("a") to ledgerStep, null to "activity:list_row_lowered"),
                Triple("another step, covering ledger", served("a") to readHoldStep, ActivityLedgerReadState(maxReadSeq = 100L, readStateVersion = 5L) to "activity:list_row_lowered"),
            )
        cases.forEach { (name, input, expectation) ->
            val (row, stages) = input
            val (ledger, event) = expectation
            val sent = lowered(listOf(row), stages, ledger).single()
            assertEquals(event, sent.event, name)
            val key = sent.stateTransitionTraceAttrs()["key"]!!.jsonObject
            assertEquals(event, key["event"]!!.jsonPrimitive.content, name)
            assertEquals(if (sent.corrected) "noop" else "conflict", key["outcome"]!!.jsonPrimitive.content, name)
        }
    }

    @Test
    fun bothEventsCarryTheRowsReadFacts() {
        val read = activityItem("a", unreadCount = 0)
        val corrected =
            lowered(
                listOf(served("a")),
                listOf(ActivityListRowStep.Ledger to listOf(read), ActivityListRowStep.Merge to listOf(read)),
                ActivityLedgerReadState(maxReadSeq = 100L, readStateVersion = 5L)
            ).single()
        assertEquals(
            mapOf(
                "step" to "ledger",
                "before" to "-1",
                "served" to "3",
                "after" to "0",
                "fetch_reason" to "reconnect",
                "fetch_trigger" to "none",
                "row_position" to "true",
                "row_kind" to "channel",
                "scope_id" to "a",
                "row_latest_seq" to "100",
                "served_read_version" to "4",
                "served_read_seq" to "90",
                "served_latest_activity_seq" to "100",
                "ledger_read_version" to "5",
                "ledger_read_seq" to "100",
                // Never held (slock #15197): no ack, no hold end.
                "ack_read_seq" to "-",
                "ack_delay_ms" to "-",
                "held_end" to "-",
                // slock #15560: a correction is expected; the first report of it; no projection here.
                "class" to "expected",
                "dedupe" to "first",
                "suppressed" to "0",
                "proj_unread" to "-",
                "proj_read_seq" to "-",
                "proj_through_seq" to "-",
                "proj_loaded_above_read" to "-",
                "proj_loaded_count" to "-",
                "proj_loaded_min_seq" to "-",
                "proj_coverage" to "-",
            ),
            corrected.stateTransitionTraceAttrs().loweredAttrs()
        )

        // A thread row is named by its own channel; facts it lacks are "-".
        val thread = threadItem("parent-1", threadChannelId = "thread-1", unreadCount = 2)
        val unknown =
            lowered(
                listOf(thread),
                listOf(ActivityListRowStep.LocalRead to listOf(thread.copy(unreadCount = 0))),
                ledger = null
            ).single()
        assertEquals(
            listOf("thread", "thread-1", "-", "-", "-", "-", "-", "-"),
            listOf("row_kind", "scope_id", "row_latest_seq", "served_read_version", "served_read_seq", "served_latest_activity_seq", "ledger_read_version", "ledger_read_seq")
                .map { unknown.stateTransitionTraceAttrs().loweredAttrs()[it] }
        )
    }

    @Test
    fun theLedgerStepReadsOnlyRowsANewerCoveringReadReaches() {
        val ledger = mapOf(
            "covered" to ActivityLedgerReadState(maxReadSeq = 100L, readStateVersion = 5L),
            "below" to ActivityLedgerReadState(maxReadSeq = 99L, readStateVersion = 5L),
            "same" to ActivityLedgerReadState(maxReadSeq = 100L, readStateVersion = 4L),
        )
        val adjudicated =
            ledgerAdjudicated(listOf(served("covered"), served("below"), served("same"), served("unknown"))) { ledger[it] }
        assertEquals(listOf(0, 3, 3, 3), adjudicated.map { it.unreadCount })
        assertEquals(false, adjudicated.first().hasMention)
    }

    @Test
    fun aRowServedTwiceInOneAnswerIsReportedOnce() {
        val read = activityItem("a", unreadCount = 0)
        val sent =
            lowered(
                listOf(served("a"), served("a")),
                listOf(ActivityListRowStep.ReadHold to listOf(read), ActivityListRowStep.Merge to listOf(read)),
                ledger = null
            )
        assertEquals(1, sent.size)
    }

    private fun JsonObject.loweredAttrs(): Map<String, String> =
        filterKeys { it.startsWith("inbox.lowered.") }
            .map { (k, v) -> k.removePrefix("inbox.lowered.") to v.jsonPrimitive.content }
            .toMap()

    // slock #15560 `inbox.lowered.class`: a merge lowering of an answer behind this client's read is
    // expected; one with no such fact is the defect (`unexplained`).
    @Test
    fun aMergeLoweringBehindThisClientsReadIsExpectedElseUnexplained() {
        val read = activityItem("a", unreadCount = 0)
        val merge = listOf(ActivityListRowStep.Merge to listOf(read))
        val behind = lowered(listOf(served("a")), merge, ActivityLedgerReadState(maxReadSeq = 100L, readStateVersion = 4L)).single()
        assertEquals(ActivityListRowLoweredClass.Expected, behind.loweredClass)
        val unexplained = lowered(listOf(served("a")), merge, null).single()
        assertEquals(ActivityListRowLoweredClass.Unexplained, unexplained.loweredClass)
        val attrs = unexplained.stateTransitionTraceAttrs()
        assertEquals("unexplained", attrs["inbox.lowered.class"]!!.jsonPrimitive.content)
        assertEquals("first", attrs["inbox.lowered.dedupe"]!!.jsonPrimitive.content)
        assertEquals("0", attrs["inbox.lowered.suppressed"]!!.jsonPrimitive.content)
        assertEquals("-", attrs["inbox.lowered.proj_coverage"]!!.jsonPrimitive.content)
    }

    // slock #15560 dedupe: once per 90 s window; the re-reads inside it are counted and carried
    // to the next report after the window as `repeat`.
    @Test
    fun theSameLoweringIsReportedOncePerWindowThenAsARepeatWithWhatWasSuppressed() {
        val read = activityItem("a", unreadCount = 0)
        val row = lowered(listOf(served("a")), listOf(ActivityListRowStep.Merge to listOf(read)), null).single()
        val dedupe = ActivityListRowLoweredDedupe()
        assertEquals(false, dedupe.admit(row, 0L)!!.repeat)
        assertEquals(null, dedupe.admit(row, 2_500L))
        assertEquals(null, dedupe.admit(row, 5_000L))
        val again = dedupe.admit(row, ActivityListRowLoweredReportWindowMillis + 1)!!
        assertEquals(true, again.repeat)
        assertEquals(2, again.suppressed)
        // Another lowering of the row (a different `after`) is its own report.
        assertEquals(false, dedupe.admit(row.copy(after = 1), 3_000L)!!.repeat)
        // Nothing suppressed in a window: the next one after it is a plain first.
        val quiet = ActivityListRowLoweredDedupe()
        quiet.admit(row, 0L)
        assertEquals(false, quiet.admit(row, ActivityListRowLoweredReportWindowMillis + 1)!!.repeat)
    }
}
