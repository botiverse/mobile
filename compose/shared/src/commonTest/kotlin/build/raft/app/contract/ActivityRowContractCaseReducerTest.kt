package build.raft.app.contract

import build.raft.app.data.ActivityRowAnswer
import build.raft.app.data.ActivityRowEvent
import build.raft.app.data.ActivityRowRereadReason
import build.raft.app.data.activityRowFromAnswer
import build.raft.app.data.compareActivityRowSeq
import build.raft.app.data.reduceActivityRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every vendored Activity row contract case (#triage:#244) through the app's pure port of the shared
 * rule ([runActivityRowCaseOnReducer]): a plain commonTest, so it runs on Android and iOS alike. The
 * cases come from [ActivityRowVendoredCases], generated at build time from the vendored copy in
 * `src/commonTest/resources/contract-cases/activity-row/` (`:shared:generateActivityRowContractCases`).
 *
 * The reducer has no known gaps: every agreed case must hold. Only a pending product decision (no
 * agreed expectation) or a case this loader cannot read (a copy newer than the code, slock README
 * "Versioning") is skipped, and reported. The store driver ([ActivityRowContractCasesTest]) keeps its
 * own gap list.
 */
class ActivityRowContractCaseReducerTest {
    @Test
    fun everyAgreedCaseHoldsForTheReducer() {
        val files = ActivityRowVendoredCases.files
        assertTrue(files.isNotEmpty(), "no vendored activity-row contract cases")
        val failures = mutableListOf<String>()
        var ran = 0
        files.forEach { (fileId, text) ->
            val case =
                when (val decoded = decodeActivityRowContractCase(fileId, text)) {
                    is ActivityRowCaseDecoding.Unreadable -> {
                        report(fileId, "skipped: unreadable: ${decoded.reason}")
                        return@forEach
                    }
                    is ActivityRowCaseDecoding.Decoded -> decoded.case
                }
            if (case.id != fileId) failures += "$fileId: id '${case.id}' must be the file name"
            if (case.status != "agreed") {
                report(fileId, "skipped: ${case.status}: ${case.decision?.question}")
                return@forEach
            }
            ran++
            val mismatches = runActivityRowCaseOnReducer(case)
            report(fileId, if (mismatches.isEmpty()) "ran (reducer)" else "FAILED (reducer)")
            if (mismatches.isNotEmpty()) failures += "${case.id} (${case.title}):\n    " + mismatches.joinToString("\n    ")
        }
        println("ACTIVITY_ROW_REDUCER_CONTRACT ${ActivityRowVendoredCases.SOURCE_SHA}: ran $ran of ${files.size}, ${failures.size} failed")
        if (failures.isNotEmpty()) fail("activity-row cases the reducer does not hold:\n  " + failures.joinToString("\n  "))
        assertTrue(ran > 0, "no agreed case ran")
    }

    @Test
    fun theDriverReportsAMismatchItDoesNotHold() {
        // The shipped case with its expectations flipped must fail, or a case could pass unchecked.
        val text = ActivityRowVendoredCases.files.getValue("null_position_rereads")
        val case = assertIs<ActivityRowCaseDecoding.Decoded>(decodeActivityRowContractCase("null_position_rereads", text)).case
        assertEquals(emptyList(), runActivityRowCaseOnReducer(case))
        val expect = case.expect!!
        val flipped = case.copy(expect = expect.copy(reread = !expect.reread!!))
        assertEquals(listOf("at the end: reread: expected ${!expect.reread}, got ${expect.reread}"), runActivityRowCaseOnReducer(flipped))

        val predicate =
            ActivityRowVendoredCases.files.keys.first { it.startsWith("predicate_") }.let { id ->
                assertIs<ActivityRowCaseDecoding.Decoded>(decodeActivityRowContractCase(id, ActivityRowVendoredCases.files.getValue(id))).case
            }
        val verdict = predicate.expect!!.verdict!!
        val wrong = predicate.copy(expect = predicate.expect.copy(verdict = verdict.copy(mention = !verdict.mention)))
        assertEquals(1, runActivityRowCaseOnReducer(wrong).size, "a wrong verdict is reported")
    }

    @Test
    fun nonCanonicalSeqsFailClosed() {
        // Not in the cases (their schema rejects such seqs); web: activityRowFromAnswer / compareActivityRowSeq.
        assertNull(activityRowFromAnswer(ActivityRowAnswer(1, false, "0101", false)).position, "a non-canonical position is no position")
        assertFailsWith<IllegalArgumentException> { compareActivityRowSeq("1", "-1") }
        val row = activityRowFromAnswer(ActivityRowAnswer(0, false, "99", false))
        val live = reduceActivityRow(row, ActivityRowEvent.Live("1e3", counts = true, mentionsViewer = false))
        assertEquals(ActivityRowRereadReason.Unjudged, live.rereadReason)
        assertEquals(row, live.row, "an unjudged live seq changes nothing")
        val answer = reduceActivityRow(row, ActivityRowEvent.Answer(ActivityRowAnswer(2, false, "x", false)))
        assertNull(answer.row.position, "an answer without a canonical position takes no live patches")
        assertEquals(2, answer.row.unreadCount)
    }

    private fun report(fileId: String, result: String) {
        println("ACTIVITY_ROW_REDUCER_CONTRACT_CASE $fileId: $result")
    }
}
