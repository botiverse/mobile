package build.raft.app

import build.raft.app.model.RaftTimeContext
import build.raft.app.model.RaftTimeFormat12Hour
import build.raft.app.model.raftEpochDayForDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThreadMessageTimeRevealTest {
    private val context =
        RaftTimeContext(localOffsetMinutes = 8 * 60, todayEpochDay = raftEpochDayForDate(2026, 10, 22))

    @Test
    fun theRevealedTimeIsTheClockInThePersonsSetting() {
        // An older message: still the clock only (the day is in the date divider).
        assertEquals("20:34", formatThreadTimeRevealLabel("2026-06-27T12:34:00Z", context))
        assertEquals("08:34 PM", formatThreadTimeRevealLabel("2026-06-27T12:34:00Z", context.copy(timeFormat = RaftTimeFormat12Hour)))
        assertEquals("09:05 AM", formatThreadTimeRevealLabel("2026-10-22T01:05:00Z", context.copy(timeFormat = RaftTimeFormat12Hour)))
        // No time context yet: nothing, never the wire timestamp.
        assertEquals("", formatThreadTimeRevealLabel("2026-10-22T01:05:00Z", null))
        assertEquals("", formatThreadTimeRevealLabel("  ", context))
    }

    @Test
    fun rowsFollowTheFingerToTheGutterThenRubberBand() {
        val gutter = 64f
        assertEquals(0f, threadTimeRevealOffsetPx(-10f, gutter))
        assertEquals(30f, threadTimeRevealOffsetPx(30f, gutter))
        assertEquals(gutter, threadTimeRevealOffsetPx(gutter, gutter))
        val over = threadTimeRevealOffsetPx(gutter + 40f, gutter)
        assertTrue(over > gutter && over < gutter + 40f, "resisted: $over")
        val far = threadTimeRevealOffsetPx(gutter + 10_000f, gutter)
        assertTrue(far < gutter * 1.5f, "bounded: $far")
        assertTrue(threadTimeRevealOffsetPx(gutter + 80f, gutter) > over, "still follows")
    }
}
