package build.raft.app

import build.raft.app.data.InboxItem
import build.raft.app.model.ChannelId
import build.raft.app.model.InboxItemKind
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.RaftTimeFormat12Hour
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.model.raftEpochDayForDate
import build.raft.app.resources.Res
import build.raft.app.resources.activity_list_day_date
import build.raft.app.resources.activity_list_day_date_year
import build.raft.app.resources.activity_list_day_month_december
import build.raft.app.resources.activity_list_day_month_october
import build.raft.app.resources.activity_list_day_today
import build.raft.app.resources.activity_list_day_weekday_tuesday
import build.raft.app.resources.activity_list_day_weekday_wednesday
import build.raft.app.resources.activity_list_day_yesterday
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
    fun todayYesterdayAnOlderDayAndAnEarlierYearNewestFirst() {
        assertEquals(
            listOf(
                ActivityDayLabel.Today to listOf("a", "b"),
                ActivityDayLabel.Yesterday to listOf("c"),
                ActivityDayLabel.Date(day(2026, 10, 20), withYear = false) to listOf("d"),
                ActivityDayLabel.Date(day(2025, 10, 21), withYear = true) to listOf("e"),
            ),
            shape(
                listOf(
                    row("a", "2026-11-05T11:00:00Z"),
                    row("b", "2026-11-05T09:00:00Z"),
                    row("c", "2026-11-04T23:00:00Z"),
                    row("d", "2026-10-20T12:00:00Z"),
                    row("e", "2025-10-21T12:00:00Z"),
                )
            ),
        )
    }

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
    fun acrossTheYearBoundaryYesterdayStaysYesterdayAndOlderDaysNameTheirYear() {
        val newYear = context(nowEpochMillis = epochMillis("2026-01-01T08:00:00Z"))
        assertEquals(
            listOf(
                ActivityDayLabel.Today to listOf("new-year"),
                ActivityDayLabel.Yesterday to listOf("eve"),
                ActivityDayLabel.Date(day(2025, 12, 30), withYear = true) to listOf("before"),
            ),
            shape(
                listOf(
                    row("new-year", "2026-01-01T07:00:00Z"),
                    row("eve", "2025-12-31T22:00:00Z"),
                    row("before", "2025-12-30T22:00:00Z"),
                ),
                newYear,
            ),
        )
        assertEquals(
            ActivityDayLabelSelection(
                Res.string.activity_list_day_date_year,
                weekday = Res.string.activity_list_day_weekday_tuesday,
                month = Res.string.activity_list_day_month_december,
                day = 30,
                year = 2025,
            ),
            activityDayLabel(day(2025, 12, 30), day(2026, 1, 1)).selection(),
        )
        // Two days before New Year's Day 2027 is 2026's: it names its year from 2027.
        assertEquals(
            ActivityDayLabelSelection(
                Res.string.activity_list_day_date_year,
                weekday = Res.string.activity_list_day_weekday_wednesday,
                month = Res.string.activity_list_day_month_december,
                day = 30,
                year = 2026,
            ),
            activityDayLabel(day(2026, 12, 30), day(2027, 1, 1)).selection(),
        )
        // The same day seen from December 31 of its own year: no year.
        assertEquals(ActivityDayLabel.Date(day(2026, 12, 29), withYear = false), activityDayLabel(day(2026, 12, 29), day(2026, 12, 31)))
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
    fun aDayWithNoRowsHasNoHeadingAndAFilterThatEmptiesADayTakesItsHeading() {
        val all = listOf(row("today", "2026-11-05T11:00:00Z"), row("yesterday", "2026-11-04T11:00:00Z"), row("older", "2026-11-02T11:00:00Z"))
        assertEquals(3, shape(all).size)
        assertEquals(
            listOf(ActivityDayLabel.Today, ActivityDayLabel.Date(day(2026, 11, 2), withYear = false)),
            shape(all.filterNot { it.key == "yesterday" }).map { it.first },
        )
        assertEquals(emptyList(), shape(emptyList()))
    }

    @Test
    fun aGroupKeepsItsKeyWhenItsHeadingTurnsAtMidnight() {
        val items = listOf(row("a", "2026-11-05T23:50:00Z"))
        val before = groupActivityRowsByDay(items, context(epochMillis("2026-11-05T23:55:00Z")))
        val after = groupActivityRowsByDay(items, context(epochMillis("2026-11-06T00:05:00Z")))
        assertEquals(ActivityDayLabel.Today, before.single().label)
        assertEquals(ActivityDayLabel.Yesterday, after.single().label)
        assertEquals(before.single().key, after.single().key)
    }

    @Test
    fun aRowALittleAheadOfThisDevicesClockCountsAsToday() {
        assertEquals(
            listOf(ActivityDayLabel.Today to listOf("skew", "a")),
            shape(listOf(row("skew", "2026-11-06T00:00:05Z"), row("a", "2026-11-05T11:00:00Z"))),
        )
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
    fun theListEntriesPutAHeadingOverEachDayAndHairlinesOnlyBetweenRowsOfOneDay() {
        val items = listOf(row("a", "2026-11-05T11:00:00Z"), row("b", "2026-11-05T10:00:00Z"), row("c", "2026-11-04T10:00:00Z"))
        val entries = activityConversationListEntries(items, context())
        assertEquals(
            listOf(activityDayGroupKey(day(2026, 11, 5)), "a", "b", activityDayGroupKey(day(2026, 11, 4)), "c"),
            entries.map { it.key },
        )
        assertEquals(
            listOf(false, true, false),
            entries.filterIsInstance<ActivityConversationListEntry.Row>().map { it.divider },
        )
        assertEquals(
            listOf(ActivityDayLabel.Today, ActivityDayLabel.Yesterday),
            entries.filterIsInstance<ActivityConversationListEntry.DayHeading>().map { it.label },
        )
        assertEquals(items, activityRowsInDisplayOrder(entries))
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
    fun underAnHourIsMinutesEvenAcrossMidnightAndTheClockFollowsThe12HourChoice() {
        val justAfterMidnight = context(nowEpochMillis = epochMillis("2026-11-06T00:10:00Z"))
        assertEquals(ActivityRowTime.Minutes(20), activityRowTime("2026-11-05T23:50:00Z", justAfterMidnight))
        val twelveHour = context(timeFormat = RaftTimeFormat12Hour)
        assertEquals(
            ActivityRowTime.Older(clock = "5:20 PM", day = ActivityRowTimeDay.Yesterday),
            activityRowTime("2026-11-04T17:20:00Z", twelveHour),
        )
        assertEquals(
            ActivityRowTime.Older(clock = "12:05 AM", day = ActivityRowTimeDay.Yesterday),
            activityRowTime("2026-11-04T00:05:00Z", twelveHour),
        )
    }
}
