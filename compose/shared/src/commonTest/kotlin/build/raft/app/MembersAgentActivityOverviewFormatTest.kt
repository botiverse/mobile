package build.raft.app

import build.raft.app.members.MembersActivityOverviewDurationParts
import build.raft.app.members.MembersActivityOverviewNewTurnAction
import build.raft.app.members.membersActivityOverviewClockRange
import build.raft.app.members.membersActivityOverviewDurationParts
import build.raft.app.members.membersActivityOverviewNewTurnAction
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.RaftTimeFormat12Hour
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
    fun aNewTurnFollowsTheBottomOnlyForAReaderParkedThere() {
        // First placement always lands at the bottom (web: first load scrolls to the newest).
        assertEquals(MembersActivityOverviewNewTurnAction.FollowBottom, membersActivityOverviewNewTurnAction(placed = false, followingBottom = false, scrolling = true))
        assertEquals(MembersActivityOverviewNewTurnAction.FollowBottom, membersActivityOverviewNewTurnAction(placed = true, followingBottom = true, scrolling = false))
        // A drag in progress or a reader who left the tail is never moved: the hint shows instead.
        assertEquals(MembersActivityOverviewNewTurnAction.ShowHint, membersActivityOverviewNewTurnAction(placed = true, followingBottom = true, scrolling = true))
        assertEquals(MembersActivityOverviewNewTurnAction.ShowHint, membersActivityOverviewNewTurnAction(placed = true, followingBottom = false, scrolling = false))
    }
}
