package build.raft.app

import build.raft.app.model.RaftTimeContext
import build.raft.app.model.RaftTimeFormat12Hour
import build.raft.designsystem.components.RaftPresence
import kotlin.test.Test
import kotlin.test.assertEquals

/** Web AgentActivityOverview helpers (formatTurnDuration, formatClockRange, intl list). */
class MembersAgentActivityOverviewFormatTest {
    // 2026-06-19, UTC+8 (the visual fixture day).
    private val context = RaftTimeContext(localOffsetMinutes = 480, todayEpochDay = 20623L)
    private val t1200 = 1781870400000L // 2026-06-19T12:00:00Z = 20:00 local

    @Test
    fun durationRoundsToSecondsWithAOneSecondFloor() {
        assertEquals(MembersActivityOverviewDurationParts(0, 0, 1), membersActivityOverviewDurationParts(0))
        assertEquals(MembersActivityOverviewDurationParts(0, 0, 41), membersActivityOverviewDurationParts(40_600))
        assertEquals(MembersActivityOverviewDurationParts(0, 1, 41), membersActivityOverviewDurationParts(101_000))
        assertEquals(MembersActivityOverviewDurationParts(2, 5, 0), membersActivityOverviewDurationParts(7_500_000))
    }

    @Test
    fun clockRangeWritesASharedMinuteOrDayPeriodOnce() {
        assertEquals("20:00" to null, membersActivityOverviewClockRange(t1200, t1200 + 20_000, context))
        assertEquals("20:00" to "20:01", membersActivityOverviewClockRange(t1200, t1200 + 101_000, context))
        val twelve = context.copy(timeFormat = RaftTimeFormat12Hour)
        assertEquals("08:00" to "08:06 PM", membersActivityOverviewClockRange(t1200, t1200 + 360_000, twelve))
        // A turn on an earlier day carries its date; the end drops it when the turn stayed on that day.
        val dayBefore = t1200 - 86_400_000L
        assertEquals("06-18 20:00" to "20:06", membersActivityOverviewClockRange(dayBefore, dayBefore + 360_000, context))
    }

    @Test
    fun actionListJoinsLikeIntlConjunction() {
        val join = "%1\$s, %2\$s"
        val last = "%1\$s, and %2\$s"
        val pair = "%1\$s and %2\$s"
        assertEquals("a", membersActivityOverviewJoinList(listOf("a"), join, last, pair))
        assertEquals("a and b", membersActivityOverviewJoinList(listOf("a", "b"), join, last, pair))
        assertEquals("a, b, and c", membersActivityOverviewJoinList(listOf("a", "b", "c"), join, last, pair))
    }

    @Test
    fun nowPresenceFollowsTheLiveActivity() {
        assertEquals(
            listOf(RaftPresence.Busy, RaftPresence.Busy, RaftPresence.Error, RaftPresence.Offline, RaftPresence.Online),
            listOf("working", "thinking", "error", null, "online").map(::membersAgentActivityNowPresence)
        )
    }
}
