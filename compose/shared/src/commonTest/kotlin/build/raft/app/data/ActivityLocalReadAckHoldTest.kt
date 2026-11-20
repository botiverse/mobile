package build.raft.app.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `inbox_row_position_v1`, web `holdLocalRead` / `noteLocalReadAck` (botiverse/slock #15197 @
 * f5af7a2586, tests/inboxListRowLowered.test.ts): a `local_read` lowering waits 5 s for this
 * client's read ack. An ack that covers the served latest activity makes it
 * `activity:list_row_corrected`; a lower ack, no ack, or a late one leaves it
 * `activity:list_row_lowered`. Plus the app's flush on background / server switch / sign-out.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivityLocalReadAckHoldTest {
    private class Fixture(val hold: ActivityLocalReadAckHold, val sent: MutableList<Pair<String, ActivityListRowLowered>>) {
        val rows: List<ActivityListRowLowered> get() = sent.map { it.second }
    }

    private fun TestScope.fixture(): Fixture {
        val sent = mutableListOf<Pair<String, ActivityListRowLowered>>()
        val hold =
            ActivityLocalReadAckHold(
                scope = backgroundScope,
                nowMillis = { testScheduler.currentTime },
                timerContext = StandardTestDispatcher(testScheduler),
                sink = { serverId, row -> sent += serverId to row }
            )
        return Fixture(hold, sent)
    }

    /** A `local_read` lowering of [scopeId]: the answer served 3 unread with latest activity [latest]. */
    private fun localRead(scopeId: String, latest: String? = "100", timestamp: Long = 1_000L) =
        ActivityListRowLowered(
            step = ActivityListRowStep.LocalRead,
            before = 0,
            served = 3,
            after = 0,
            fetchReason = "visibility",
            fetchTrigger = "none",
            rowPosition = true,
            timestampMillis = timestamp,
            rowKind = "channel",
            scopeId = scopeId,
            rowLatestSeq = latest,
            servedReadVersion = 4L,
            servedReadSeq = "90",
            servedLatestActivitySeq = latest,
            ledgerReadVersion = null,
            ledgerReadSeq = null,
        )

    private fun ActivityListRowLowered.newAttrs(): List<String> {
        val attrs = stateTransitionTraceAttrs()
        return listOf("ack_read_seq", "ack_delay_ms", "held_end").map { attrs["inbox.lowered.$it"].toString().trim('"') }
    }

    @Test
    fun anAckThatCoversTheServedLatestActivityWithinTheWindowIsACorrection() = runTest {
        val f = fixture()
        f.hold.hold("server-1", localRead("channel-1"))
        runCurrent()
        assertEquals(emptyList(), f.sent, "held, not sent")

        advanceTimeBy(1_200L)
        f.hold.noteAck("channel-1", maxReadSeq = 100L)

        val (serverId, row) = f.sent.single()
        assertEquals("server-1", serverId)
        assertEquals("activity:list_row_corrected", row.event)
        assertEquals(listOf("100", "1200", "ack"), row.newAttrs())
        assertEquals("noop", row.stateTransitionTraceAttrs()["key"].toString().substringAfter("\"outcome\":\"").substringBefore('"'))
        assertEquals(1_000L, row.timestampMillis, "the event keeps the lowering time")

        advanceTimeBy(ActivityLocalReadAckWindowMillis * 2)
        runCurrent()
        assertEquals(1, f.sent.size, "the window sends nothing more")
    }

    @Test
    fun anAckPastTheLatestActivityIsACorrection() = runTest {
        val f = fixture()
        f.hold.hold("server-1", localRead("channel-1"))
        f.hold.noteAck("channel-1", maxReadSeq = 250L)
        assertEquals("activity:list_row_corrected" to listOf("250", "0", "ack"), f.rows.single().let { it.event to it.newAttrs() })
    }

    @Test
    fun anAckBelowTheServedLatestActivityIsALowering() = runTest {
        val f = fixture()
        f.hold.hold("server-1", localRead("channel-1"))
        advanceTimeBy(300L)
        f.hold.noteAck("channel-1", maxReadSeq = 99L)
        val row = f.rows.single()
        assertEquals("activity:list_row_lowered", row.event)
        assertEquals(listOf("99", "300", "ack"), row.newAttrs())
    }

    @Test
    fun noAckIsALoweringExactlyAtTheEndOfTheWindowAndALateAckSendsNothing() = runTest {
        val f = fixture()
        f.hold.hold("server-1", localRead("channel-1"))
        f.hold.noteAck("channel-2", maxReadSeq = 500L) // another scope's ack
        advanceTimeBy(ActivityLocalReadAckWindowMillis - 1)
        runCurrent()
        assertEquals(emptyList(), f.sent, "still held 1 ms before the window ends")

        advanceTimeBy(1L)
        runCurrent()
        val row = f.rows.single()
        assertEquals("activity:list_row_lowered", row.event)
        assertEquals(listOf("-", "-", "window"), row.newAttrs())
        assertEquals(1_000L, row.timestampMillis)

        f.hold.noteAck("channel-1", maxReadSeq = 100L)
        assertEquals(1, f.sent.size, "a late ack sends nothing more")
    }

    @Test
    fun anAckWithNoUsableSeqIsIgnoredAndTheWindowDecides() = runTest {
        val f = fixture()
        f.hold.hold("server-1", localRead("channel-1"))
        f.hold.noteAck("channel-1", maxReadSeq = null)
        f.hold.noteAck("channel-1", maxReadSeq = -1L)
        assertEquals(emptyList(), f.sent, "ignored: still held")
        assertEquals(1, f.hold.heldCount)

        advanceTimeBy(ActivityLocalReadAckWindowMillis)
        runCurrent()
        assertEquals("activity:list_row_lowered" to listOf("-", "-", "window"), f.rows.single().let { it.event to it.newAttrs() })
    }

    @Test
    fun aRowWhoseServedLatestActivityIsUnusableIsALoweringEvenWithAnAck() = runTest {
        val f = fixture()
        f.hold.hold("server-1", localRead("channel-1", latest = null))
        f.hold.noteAck("channel-1", maxReadSeq = 100L)
        assertEquals("activity:list_row_lowered" to listOf("100", "0", "ack"), f.rows.single().let { it.event to it.newAttrs() })
    }

    @Test
    fun severalHeldRowsAreSettledEachOnceByTheirOwnScope() = runTest {
        val f = fixture()
        f.hold.hold("server-1", localRead("channel-1", latest = "100"))
        f.hold.hold("server-1", localRead("channel-1", latest = "120", timestamp = 2_000L)) // a second answer, same scope
        advanceTimeBy(1_000L)
        f.hold.hold("server-1", localRead("channel-2"))
        f.hold.hold("server-2", localRead("channel-3"))
        assertEquals(4, f.hold.heldCount)

        advanceTimeBy(500L)
        f.hold.noteAck("channel-1", maxReadSeq = 110L)
        assertEquals(
            listOf(
                Triple("activity:list_row_corrected", 1_000L, listOf("110", "1500", "ack")),
                Triple("activity:list_row_lowered", 2_000L, listOf("110", "1500", "ack")),
            ),
            f.rows.map { Triple(it.event, it.timestampMillis, it.newAttrs()) }
        )

        advanceTimeBy(1_000L)
        f.hold.noteAck("channel-2", maxReadSeq = 100L)
        assertEquals(listOf("100", "1500", "ack"), f.rows.last().newAttrs(), "held at t = 1 000, acked at t = 2 500")
        assertEquals("activity:list_row_corrected", f.rows.last().event)

        // channel-3's window ends 5 s after it was held (t = 1 000): at t = 6 000.
        advanceTimeBy(ActivityLocalReadAckWindowMillis)
        runCurrent()
        assertEquals("server-2" to listOf("-", "-", "window"), f.sent.last().let { it.first to it.second.newAttrs() })
        assertEquals(4, f.sent.size)
        assertEquals(0, f.hold.heldCount)
    }

    @Test
    fun aFlushSendsEveryHeldRowAtOnceAsALoweringWithItsEnd() = runTest {
        ActivityListRowHeldEnd.entries
            .filter { it != ActivityListRowHeldEnd.Ack && it != ActivityListRowHeldEnd.Window }
            .forEach { end ->
                val f = fixture()
                f.hold.hold("server-1", localRead("channel-1"))
                f.hold.hold("server-1", localRead("channel-2"))
                advanceTimeBy(2_000L)
                f.hold.flush(end)
                assertEquals(
                    listOf("activity:list_row_lowered" to listOf("-", "-", end.wireValue), "activity:list_row_lowered" to listOf("-", "-", end.wireValue)),
                    f.rows.map { it.event to it.newAttrs() },
                    end.name
                )
                assertEquals(0, f.hold.heldCount)

                // The cancelled windows and a later ack send nothing more.
                f.hold.noteAck("channel-1", maxReadSeq = 100L)
                advanceTimeBy(ActivityLocalReadAckWindowMillis)
                runCurrent()
                assertEquals(2, f.sent.size, end.name)
            }
        assertEquals(listOf("background", "server_switch", "signout"), listOf(ActivityListRowHeldEnd.Background, ActivityListRowHeldEnd.ServerSwitch, ActivityListRowHeldEnd.Signout).map { it.wireValue })
    }

    @Test
    fun theSeqRuleIsWebCovers() {
        assertTrue(listRowSeqCovers("100", "100"))
        assertTrue(listRowSeqCovers("12345678901234567890", "12345678901234567889"))
        assertTrue(!listRowSeqCovers("99", "100"))
        assertTrue(!listRowSeqCovers("-1", "0"))
        assertTrue(!listRowSeqCovers("1e3", "5"))
        assertTrue(!listRowSeqCovers(null, "5"))
        assertTrue(!listRowSeqCovers("5", null))
        assertEquals(null, parseListRowSeq(-1L))
        assertEquals("0", parseListRowSeq(0L))
    }

    // slock #15560: an ack that landed before the answer registered the hold settles it at once
    // (`ack_before_hold`, negative delay) when it covers the served latest activity; an older one
    // that doesn't leaves the row held.
    @Test
    fun anEarlierAckThatCoversTheLatestActivitySettlesTheHoldAtOnce() = runTest {
        val f = fixture()
        f.hold.noteAck("channel-1", maxReadSeq = 100L)
        advanceTimeBy(2_000L)
        f.hold.hold("server-1", localRead("channel-1"))
        val row = f.rows.single()
        assertEquals("activity:list_row_corrected", row.event)
        assertEquals(listOf("100", "-2000", "ack_before_hold"), row.newAttrs())
        assertEquals(ActivityListRowLoweredClass.Expected, row.loweredClass)

        f.hold.noteAck("channel-2", maxReadSeq = 99L)
        f.hold.hold("server-1", localRead("channel-2"))
        assertEquals(1, f.rows.size, "an ack below the latest activity does not settle it")
        advanceTimeBy(ActivityLocalReadAckWindowMillis + 1)
        runCurrent()
        assertEquals(ActivityListRowHeldEnd.Window, f.rows.last().heldEnd)
        assertEquals(ActivityListRowLoweredClass.AckPending, f.rows.last().loweredClass)
    }

    @Test
    fun anAckOlderThanTheRecentWindowDoesNotSettleALaterHold() = runTest {
        val f = fixture()
        f.hold.noteAck("channel-1", maxReadSeq = 100L)
        advanceTimeBy(ActivityLocalReadRecentAckMillis + 1)
        f.hold.hold("server-1", localRead("channel-1"))
        assertEquals(emptyList(), f.sent, "held")
    }
}
