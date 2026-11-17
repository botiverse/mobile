package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The row's read seq ([ActivityRowState.readSeq], slock `activityRowPosition.ts`) outside what the
 * contract cases (`answer_before_read_lands*`) pin: a late older read ack, Mark as Unread, an answer
 * with no position, and the ledger keeping it apart from the row's position state.
 */
class ActivityRowReadSeqTest {
    private fun answer(unreadCount: Int, position: String?) = ActivityRowAnswer(unreadCount, hasMention = false, position = position, mentionOnly = false)

    private fun ActivityRowState.then(event: ActivityRowEvent) = reduceActivityRow(this, event).row

    @Test
    fun aReadRaisesTheReadSeqAndAnOlderReadAckDoesNotLowerIt() {
        val row =
            activityRowFromAnswer(answer(2, "101"))
                .then(ActivityRowEvent.Read("101"))
                .then(ActivityRowEvent.Read("99"))
        assertEquals("101", row.readSeq)
        assertEquals(0, row.then(ActivityRowEvent.Answer(answer(2, "101"))).unreadCount)
    }

    @Test
    fun markUnreadClearsTheReadSeqSoTheNextAnswerCounts() {
        val row =
            activityRowFromAnswer(answer(1, "101"))
                .then(ActivityRowEvent.Read("101"))
                .then(ActivityRowEvent.MarkUnread)
        assertNull(row.readSeq)
        assertEquals(1, row.then(ActivityRowEvent.Answer(answer(1, "101"))).unreadCount)
    }

    @Test
    fun afterMarkUnreadAReadAtOrBelowTheCeilingDoesNotRaiseTheReadSeqAndOneAboveLiftsIt() {
        var row =
            activityRowFromAnswer(answer(1, "101"))
                .then(ActivityRowEvent.Read("101"))
                .then(ActivityRowEvent.MarkUnread)
        assertEquals(null to "101", row.readSeq to row.readSeqCeiling)
        row = row.then(ActivityRowEvent.Read("100"))
        assertNull(row.readSeq)
        row = row.then(ActivityRowEvent.Read("102"))
        assertEquals("102" to null, row.readSeq to row.readSeqCeiling)
        assertEquals(0, row.then(ActivityRowEvent.Answer(answer(1, "102"))).unreadCount)
        // A second mark keeps the higher ceiling.
        val twice = row.then(ActivityRowEvent.MarkUnread).then(ActivityRowEvent.MarkUnread)
        assertEquals("102", twice.readSeqCeiling)
    }

    @Test
    fun theReadSeqSurvivesAnAnswerWithNoPosition() {
        val row = activityRowFromAnswer(answer(1, "101")).then(ActivityRowEvent.Read("101")).then(ActivityRowEvent.Answer(answer(1, null)))
        assertEquals("101" to 1, row.readSeq to row.unreadCount)
    }

    @Test
    fun theLedgerKeepsTheReadSeqApartFromThePositionState() {
        val ledger = ActivityRowPositionLedger()
        val row = activityItem("channel-1", unreadCount = 1).copy(latestActivitySeq = "10")
        ledger.keep("channel-1", ledger.stateOf(row).then(ActivityRowEvent.Read("10")))
        assertEquals("10", ledger.readSeqForTest("channel-1"))
        // Another path moved the row: the position state starts again, the read seq stays.
        assertEquals("10", ledger.stateOf(row.copy(latestActivitySeq = "12")).readSeq)
        // A row first listed by an answer does not drop it; a lower ack does not lower it.
        ledger.start("channel-1", "12")
        ledger.noteRead("channel-1", "9")
        assertEquals("10", ledger.readSeqForTest("channel-1"))
        ledger.noteRead("channel-1", "12")
        assertEquals("12", ledger.readSeqForTest("channel-1"))
        // A newer read state sets the cursor, lower too (Mark as Unread on another device).
        ledger.setReadCursor("channel-1", "11")
        assertEquals("11", ledger.readSeqForTest("channel-1"))
        // Mark as Unread here: the read seq goes and becomes the ceiling a late ack must pass.
        ledger.forgetRead("channel-1")
        assertNull(ledger.stateOf(row).readSeq)
        assertEquals("11", ledger.readSeqCeilingForTest("channel-1"))
        ledger.noteRead("channel-1", "11")
        assertNull(ledger.readSeqForTest("channel-1"), "a late ack at the ceiling does not raise it")
        ledger.noteRead("channel-1", "13")
        assertEquals("13" to null, ledger.readSeqForTest("channel-1") to ledger.readSeqCeilingForTest("channel-1"))
        // A newer read state sets the cursor and drops a ceiling.
        ledger.forgetRead("channel-1")
        ledger.setReadCursor("channel-1", "12")
        assertEquals("12" to null, ledger.readSeqForTest("channel-1") to ledger.readSeqCeilingForTest("channel-1"))
    }
}
