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

    private fun ledgerRead(maxReadSeq: Long, version: Long) = ActivityLedgerReadState(maxReadSeq = maxReadSeq, readStateVersion = version)

    @Test
    fun aLedgerCursorMovedBackWithoutTheFlagIsAMarkAsUnread() {
        // slock #15307: a counted Mark as Unread on another device only moves the cursor back (no flag).
        val ledger = ActivityRowPositionLedger()
        ledger.foldLedgerRead("channel-1", ledgerRead(10L, 2L), markedUnread = false)
        assertEquals("10" to null, ledger.readSeqForTest("channel-1") to ledger.readSeqCeilingForTest("channel-1"))
        // The same version again folds nothing.
        ledger.foldLedgerRead("channel-1", ledgerRead(10L, 2L), markedUnread = false)
        assertEquals("10", ledger.readSeqForTest("channel-1"))
        ledger.foldLedgerRead("channel-1", ledgerRead(9L, 3L), markedUnread = false)
        assertEquals(null to "10", ledger.readSeqForTest("channel-1") to ledger.readSeqCeilingForTest("channel-1"))
        // A late ack of the read before the mark does not raise the read seq again.
        ledger.noteRead("channel-1", "10")
        assertNull(ledger.readSeqForTest("channel-1"))
        // A newer read past the old one reads it again.
        ledger.foldLedgerRead("channel-1", ledgerRead(12L, 4L), markedUnread = false)
        assertEquals("12" to null, ledger.readSeqForTest("channel-1") to ledger.readSeqCeilingForTest("channel-1"))
    }

    @Test
    fun aMarkedLedgerStateIsAMarkAsUnreadWithNoRead() {
        val ledger = ActivityRowPositionLedger()
        ledger.foldLedgerRead("channel-1", ledgerRead(10L, 2L), markedUnread = false)
        ledger.foldLedgerRead("channel-1", ledgerRead(10L, 3L), markedUnread = true)
        assertEquals(null to "10", ledger.readSeqForTest("channel-1") to ledger.readSeqCeilingForTest("channel-1"))
    }

    @Test
    fun aLedgerStateGoneOrBegunAgainFromV1DropsTheReadSeq() {
        // slock #15307: access lost deletes the cursor row; the read state starts again from v1.
        val ledger = ActivityRowPositionLedger()
        ledger.foldLedgerRead("channel-1", ledgerRead(10L, 5L), markedUnread = false)
        ledger.foldLedgerRead("channel-1", null, markedUnread = false)
        assertEquals(null to null, ledger.readSeqForTest("channel-1") to ledger.readSeqCeilingForTest("channel-1"))
        // The new v1 is not judged against v5.
        ledger.foldLedgerRead("channel-1", ledgerRead(3L, 1L), markedUnread = false)
        assertEquals("3", ledger.readSeqForTest("channel-1"))

        // An older version than the one folded, with no absent state seen between: the same.
        ledger.foldLedgerRead("channel-2", ledgerRead(10L, 5L), markedUnread = false)
        ledger.forgetRead("channel-2")
        ledger.foldLedgerRead("channel-2", ledgerRead(3L, 1L), markedUnread = false)
        assertEquals(null to null, ledger.readSeqForTest("channel-2") to ledger.readSeqCeilingForTest("channel-2"))
        ledger.foldLedgerRead("channel-2", ledgerRead(3L, 1L), markedUnread = false)
        assertEquals("3", ledger.readSeqForTest("channel-2"))
        // No state ever folded and none held: nothing to forget.
        ledger.noteRead("channel-3", "4")
        ledger.foldLedgerRead("channel-3", null, markedUnread = false)
        assertEquals("4", ledger.readSeqForTest("channel-3"))
    }

    @Test
    fun aFoldedReadReadsTheAppliedSeqsAtOrBelowItsCursorOnly() {
        val ledger = ActivityRowPositionLedger()
        val row = activityItem("channel-1").copy(latestActivitySeq = "10")
        var state = ledger.stateOf(row)
        for (seq in listOf("11", "12", "13")) state = state.then(ActivityRowEvent.Live(seq, counts = true, mentionsViewer = false, moves = true))
        ledger.keep("channel-1", state)
        ledger.foldLedgerRead("channel-1", ledgerRead(12L, 2L), markedUnread = false)
        assertEquals(listOf(false, false, true), ledger.keptForTest("channel-1")?.applied?.map { it.counts })
        // A rewind reads only what is at or below the new cursor (here nothing more).
        ledger.foldLedgerRead("channel-1", ledgerRead(11L, 3L), markedUnread = false)
        assertEquals(listOf(false, false, true), ledger.keptForTest("channel-1")?.applied?.map { it.counts })
        // A marked state reads nothing.
        ledger.foldLedgerRead("channel-1", ledgerRead(13L, 4L), markedUnread = true)
        assertEquals(true, ledger.keptForTest("channel-1")?.applied?.last()?.counts)
    }
}
