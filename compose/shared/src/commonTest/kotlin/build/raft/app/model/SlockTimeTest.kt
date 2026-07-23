package build.raft.app.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SlockTimeTest {
    @Test
    fun formatsTodayAsCompactTime() {
        val today = slockEpochDayForDate(2026, 5, 9)

        assertEquals("22:00", formatSlockTimestampLabel("2026-05-09T22:00:00+08:00", 480, today))
        assertEquals("22:00", formatSlockTimestampLabel("2026-05-09 22:00:00+08:00", 480, today))
        assertEquals("22:00", formatSlockTimestampLabel("2026-05-09 22:00:00.000+08:00", 480, today))
        assertEquals("22:00", formatSlockTimestampLabel("2026-05-09T22:00:00.000+0800", 480, today))
        assertEquals("22:00", formatSlockTimestampLabel("2026-05-09 22:00:00+0800", 480, today))
        assertEquals("22:00", formatSlockTimestampLabel("1778335200000", 480, today))
        assertEquals("22:00", formatSlockTimestampLabel("1778335200", 480, today))
    }

    @Test
    fun formatsTodayWithPreferredTwelveHourClock() {
        val today = slockEpochDayForDate(2026, 5, 9)

        assertEquals(
            "10:00 PM",
            formatSlockTimestampLabel("2026-05-09T22:00:00+08:00", 480, today, SlockTimeFormat12Hour)
        )
        assertEquals(
            "12:05 AM",
            formatSlockTimestampLabel("2026-05-09T00:05:00+08:00", 480, today, SlockTimeFormat12Hour)
        )
        assertEquals(
            "12:05 PM",
            formatSlockTimestampLabel("2026-05-09T12:05:00+08:00", 480, today, SlockTimeFormat12Hour)
        )
    }

    @Test
    fun handlesServerMicrosecondUtcHourOffset() {
        val today = slockEpochDayForDate(2026, 5, 10)

        assertEquals(
            "05:53",
            formatSlockTimestampLabel("2026-05-09 21:53:33.024426+00", 480, today)
        )
    }

    @Test
    fun formatsActivityClockWithSecondsWithoutRawServerTimestamp() {
        assertEquals(
            "13:55:27",
            formatSlockTimestampClockWithSeconds("2026-06-10 13:55:27.466929+00", 0)
        )
        assertEquals(
            "21:55:27",
            formatSlockTimestampClockWithSeconds("2026-06-10 13:55:27.466929+00", 480)
        )
        assertEquals("13:55:27", formatSlockTimestampClockWithSeconds("1781099727466", 0))
        assertEquals(
            "01:55:27 PM",
            formatSlockTimestampClockWithSeconds("2026-06-10 13:55:27.466929+00", 0, SlockTimeFormat12Hour)
        )
    }

    @Test
    fun formatsThroughSharedTimeContext() {
        val context =
            SlockTimeContext(
                localOffsetMinutes = 480,
                todayEpochDay = slockEpochDayForDate(2026, 5, 10),
                nowEpochMillis = 1778335200000L,
                timeFormat = SlockTimeFormat12Hour
            )

        assertEquals(
            "05-09 10:00 PM",
            formatSlockTimestampLabel("2026-05-09T22:00:00+08:00", context)
        )
        assertEquals(
            "09:55:27 PM",
            formatSlockTimestampClockWithSeconds("2026-06-10 13:55:27.466929+00", context)
        )
        assertEquals(
            "10:00 PM",
            formatSlockTimestampLabel("2026-05-09T22:00:00+08:00", context.copy(todayEpochDay = null))
        )
        assertEquals(
            "raw value",
            formatSlockTimestampLabel("raw value", context.copy(todayEpochDay = null, nowEpochMillis = null))
        )
    }

    @Test
    fun refreshesTimeContextAcrossLocalMidnight() {
        val beforeMidnight =
            parseSlockTimestampEpochMillisOrNull("2026-07-22T23:59:30+08:00")!!
        val afterMidnight =
            parseSlockTimestampEpochMillisOrNull("2026-07-23T00:00:05+08:00")!!
        val seed =
            SlockTimeContext(
                localOffsetMinutes = 480,
                todayEpochDay = slockEpochDayForDate(2026, 7, 22),
                nowEpochMillis = beforeMidnight,
                timeFormat = SlockTimeFormat12Hour
            )

        val refreshed = seed.refreshedAt(afterMidnight)

        assertEquals(slockEpochDayForDate(2026, 7, 23), refreshed.todayEpochDay)
        assertEquals(afterMidnight, refreshed.nowEpochMillis)
        assertEquals(480, refreshed.localOffsetMinutes)
        assertEquals(SlockTimeFormat12Hour, refreshed.timeFormat)
        assertEquals(30_000L, slockTimeContextRefreshDelayMillis(beforeMidnight))
        assertEquals(55_000L, slockTimeContextRefreshDelayMillis(afterMidnight))
    }

    @Test
    fun includesDateOutsideToday() {
        val today = slockEpochDayForDate(2026, 5, 10)

        assertEquals("22:00", formatSlockTimestampLabel("2026-05-10T22:00:00+08:00", 480, today))
        assertEquals("05-09 22:00", formatSlockTimestampLabel("2026-05-09T22:00:00+08:00", 480, today))
        assertEquals(
            "05-09 10:00 PM",
            formatSlockTimestampLabel("2026-05-09T22:00:00+08:00", 480, today, SlockTimeFormat12Hour)
        )
        assertEquals(
            "2025-12-31 23:58",
            formatSlockTimestampLabel("2025-12-31T23:58:00+08:00", 480, today)
        )
    }

    @Test
    fun parsesEpochForSorting() {
        assertEquals(1778335200000L, parseSlockTimestampEpochMillisOrNull("1778335200000"))
        assertEquals(1778335200000L, parseSlockTimestampEpochMillisOrNull("1778335200"))
        assertEquals(
            1778335200000L,
            parseSlockTimestampEpochMillisOrNull("2026-05-09T22:00:00+08:00")
        )
        assertNull(parseSlockTimestampEpochMillisOrNull("not a timestamp"))
    }
}
