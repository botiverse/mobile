package build.raft.app

import build.raft.app.data.InboxItem
import build.raft.app.model.ChannelId
import build.raft.app.model.InboxItemKind
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.model.raftEpochDayForDate
import build.raft.app.resources.Res
import build.raft.app.resources.activity_list_day_date
import build.raft.app.resources.activity_list_day_date_year
import build.raft.app.resources.activity_list_day_month_october
import build.raft.app.resources.activity_list_day_today
import build.raft.app.resources.activity_list_day_weekday_tuesday
import build.raft.app.resources.activity_list_day_yesterday
import build.raft.app.thread.key
import build.raft.designsystem.theme.RaftPointerInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Activity's rows by day (web botiverse/slock #13030, `activityDayGroups.ts`): Today / Yesterday /
 * weekday and date headings, the year only for an earlier year, and the row's time on its first line
 * in the short form a day-grouped list uses (`formatRowTime`). Fixed clock and fixed zone offsets.
 */
class ActivityDayGroupsTest {
    /** 2026-11-05T12:00:00Z, a Thursday. */
    private val now = epochMillis("2026-11-05T12:00:00Z")

    private fun epochMillis(value: String): Long = parseRaftTimestampEpochMillisOrNull(value)!!

    private fun context(nowEpochMillis: Long = now, offsetMinutes: Int = 0, timeFormat: String = "24h") =
        RaftTimeContext(localOffsetMinutes = offsetMinutes, timeFormat = timeFormat).refreshedAt(nowEpochMillis)

    private fun row(key: String, at: String?) =
        InboxItem(key = key, kind = InboxItemKind.Channel, channelId = ChannelId(key), channelName = key, lastMessageAt = at)

    private fun shape(items: List<InboxItem>, timeContext: RaftTimeContext? = context()) =
        groupActivityRowsByDay(items, timeContext).map { group -> group.label to group.rows.map(InboxItem::key) }

    private fun day(year: Int, month: Int, day: Int) = raftEpochDayForDate(year, month, day)

    @Test
    fun theHeadingsAreTodayYesterdayAndTheWeekdayAndDateWithTheYearOnlyForAnEarlierYear() {
        val today = day(2026, 11, 5)
        assertEquals(ActivityDayLabelSelection(Res.string.activity_list_day_today), activityDayLabel(today, today).selection())
        assertEquals(ActivityDayLabelSelection(Res.string.activity_list_day_yesterday), activityDayLabel(today - 1, today).selection())
        assertEquals(
            ActivityDayLabelSelection(
                Res.string.activity_list_day_date,
                weekday = Res.string.activity_list_day_weekday_tuesday,
                month = Res.string.activity_list_day_month_october,
                day = 20,
            ),
            activityDayLabel(day(2026, 10, 20), today).selection(),
        )
        assertEquals(
            ActivityDayLabelSelection(
                Res.string.activity_list_day_date_year,
                weekday = Res.string.activity_list_day_weekday_tuesday,
                month = Res.string.activity_list_day_month_october,
                day = 21,
                year = 2025,
            ),
            activityDayLabel(day(2025, 10, 21), today).selection(),
        )
    }

    @Test
    fun daysAreCountedInThePersonsOwnZone() {
        val items = listOf(row("a", "2026-11-05T02:00:00Z"))
        val utcNow = epochMillis("2026-11-05T12:00:00Z")
        assertEquals(listOf(ActivityDayLabel.Today to listOf("a")), shape(items, context(utcNow, offsetMinutes = 0)))
        assertEquals(listOf(ActivityDayLabel.Yesterday to listOf("a")), shape(items, context(utcNow, offsetMinutes = -5 * 60)))
        assertEquals(listOf(ActivityDayLabel.Today to listOf("a")), shape(items, context(utcNow, offsetMinutes = 8 * 60)))
    }

    @Test
    fun rowsKeepTheOrderTheListPresentsAndNoHeadingRepeats() {
        // A row the stable order holds in its slot while its activity moved on (in place, revealed
        // later) stays where it is, under the heading it sits under; no day's heading shows twice.
        assertEquals(
            listOf(
                ActivityDayLabel.Today to listOf("a"),
                ActivityDayLabel.Yesterday to listOf("b", "held"),
                ActivityDayLabel.Date(day(2026, 11, 2), withYear = false) to listOf("c"),
            ),
            shape(
                listOf(
                    row("a", "2026-11-05T11:00:00Z"),
                    row("b", "2026-11-04T10:00:00Z"),
                    row("held", "2026-11-05T11:30:00Z"),
                    row("c", "2026-11-02T10:00:00Z"),
                )
            ),
        )
    }

    @Test
    fun rowsWithNoReadableTimeTakeNoHeadingOfTheirOwn() {
        assertEquals(
            listOf(
                null to listOf("lead"),
                ActivityDayLabel.Today to listOf("a", "bad"),
                ActivityDayLabel.Yesterday to listOf("b"),
            ),
            shape(listOf(row("lead", null), row("a", "2026-11-05T10:00:00Z"), row("bad", "not a time"), row("b", "2026-11-04T10:00:00Z"))),
        )
        // No clock to name today by: one group, no headings.
        assertEquals(listOf(null to listOf("a", "b")), shape(listOf(row("a", "2026-11-05T10:00:00Z"), row("b", "2026-11-04T10:00:00Z")), null))
    }

    @Test
    fun aRowsTimeIsShortWhileRecentAndItsClockOnceItIsAnEarlierDay() {
        val ctx = context()
        assertEquals(ActivityRowTime.Now, activityRowTime("2026-11-05T11:59:30Z", ctx))
        // A time a little ahead of this device's clock reads as now.
        assertEquals(ActivityRowTime.Now, activityRowTime("2026-11-05T12:00:20Z", ctx))
        assertEquals(ActivityRowTime.Minutes(3), activityRowTime("2026-11-05T11:56:10Z", ctx))
        // Whole steps, counted down: 1h 59m is 1h.
        assertEquals(ActivityRowTime.Hours(1), activityRowTime("2026-11-05T10:01:00Z", ctx))
        assertEquals(ActivityRowTime.Hours(11), activityRowTime("2026-11-05T00:30:00Z", ctx))
        assertEquals(
            ActivityRowTime.Older(clock = "17:20", day = ActivityRowTimeDay.Yesterday),
            activityRowTime("2026-11-04T17:20:00Z", ctx),
        )
        assertEquals(
            ActivityRowTime.Older(clock = "9:05", day = ActivityRowTimeDay.Weekday(day(2026, 11, 1))),
            activityRowTime("2026-11-01T09:05:00Z", ctx),
        )
        assertEquals(
            ActivityRowTime.Older(clock = "9:05", day = ActivityRowTimeDay.Date(ActivityDayLabel.Date(day(2025, 10, 21), withYear = true))),
            activityRowTime("2025-10-21T09:05:00Z", ctx),
        )
        assertNull(activityRowTime("not a time", ctx))
        assertNull(activityRowTime("2026-11-05T11:00:00Z", null))
    }

    @Test
    fun anEarlierDaysClockShowsAlwaysWithAFingerAndOnlyOnHoverWithAMouse() {
        val older = ActivityRowTime.Older(clock = "23:15", day = ActivityRowTimeDay.Yesterday)
        // A finger cannot hover (Jasper, #proj-raft-ui:#274): the clock is always there.
        assertTrue(older.shown(RaftPointerInput.Touch, pointerHovering = false))
        assertTrue(older.shown(RaftPointerInput.Touch, pointerHovering = true))
        // A mouse or trackpad keeps the hover reveal.
        assertFalse(older.shown(RaftPointerInput.Fine, pointerHovering = false))
        assertTrue(older.shown(RaftPointerInput.Fine, pointerHovering = true))
        // A recent row shows its time with either input, hovered or not.
        for (recent in listOf(ActivityRowTime.Now, ActivityRowTime.Minutes(3), ActivityRowTime.Hours(2))) {
            for (input in RaftPointerInput.entries) {
                assertTrue(recent.shown(input, pointerHovering = false), "$recent with $input")
            }
        }
    }
}
