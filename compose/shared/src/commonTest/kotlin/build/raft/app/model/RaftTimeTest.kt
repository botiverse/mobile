package build.raft.app.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertIs

class RaftTimeTest {
    @Test
    fun relativeTimeMatchesWebMinuteProgressionWithInjectedClock() {
        val sentAt = parseRaftTimestampEpochMillisOrNull("2026-07-27T12:00:00Z")!!
        val seed = RaftTimeContext(localOffsetMinutes = 0, nowEpochMillis = sentAt)

        assertEquals(
            RaftRelativeTimeLabel.Relative(0, RaftRelativeTimeUnit.Minute),
            resolveRaftRelativeTimeLabel("2026-07-27T12:00:00Z", seed)
        )
        assertEquals(
            RaftRelativeTimeLabel.Relative(-1, RaftRelativeTimeUnit.Minute),
            resolveRaftRelativeTimeLabel("2026-07-27T12:00:00Z", seed.refreshedAt(sentAt + 60_000L))
        )
        assertEquals(
            RaftRelativeTimeLabel.Relative(-2, RaftRelativeTimeUnit.Minute),
            resolveRaftRelativeTimeLabel("2026-07-27T12:00:00Z", seed.refreshedAt(sentAt + 120_000L))
        )
    }

    @Test
    fun relativeTimeMatchesWebBucketsFutureAndInvalidFallback() {
        val now = parseRaftTimestampEpochMillisOrNull("2026-07-27T12:00:00Z")!!
        val context = RaftTimeContext(localOffsetMinutes = 0, nowEpochMillis = now)

        assertEquals(
            RaftRelativeTimeLabel.Relative(59, RaftRelativeTimeUnit.Minute),
            resolveRaftRelativeTimeLabel("2026-07-27T12:59:29Z", context)
        )
        assertEquals(
            RaftRelativeTimeLabel.Relative(1, RaftRelativeTimeUnit.Hour),
            resolveRaftRelativeTimeLabel("2026-07-27T13:00:00Z", context)
        )
        assertEquals(
            RaftRelativeTimeLabel.Relative(-1, RaftRelativeTimeUnit.Day),
            resolveRaftRelativeTimeLabel("2026-07-26T12:00:00Z", context)
        )
        assertEquals(RaftRelativeTimeLabel.Unknown, resolveRaftRelativeTimeLabel("not-a-date", context))
        assertEquals(RaftRelativeTimeLabel.Literal("10:38"), resolveRaftRelativeTimeLabel("10:38", context))
        assertEquals(
            RaftRelativeTimeLabel.Unknown,
            resolveRaftRelativeTimeLabel("2026-07-27T12:00:00Z", null)
        )
        assertIs<RaftRelativeTimeLabel.Literal>(resolveRaftRelativeTimeLabel("Active", null))
    }

    @Test
    fun relativeTimeUsesJavaScriptNegativeHalfRounding() {
        val now = parseRaftTimestampEpochMillisOrNull("2026-07-27T12:00:30Z")!!
        val context = RaftTimeContext(localOffsetMinutes = 0, nowEpochMillis = now)

        assertEquals(
            RaftRelativeTimeLabel.Relative(0, RaftRelativeTimeUnit.Minute),
            resolveRaftRelativeTimeLabel("2026-07-27T12:00:00Z", context)
        )
        assertEquals(
            RaftRelativeTimeLabel.Relative(-1, RaftRelativeTimeUnit.Minute),
            resolveRaftRelativeTimeLabel("2026-07-27T11:59:00Z", context)
        )
    }

    @Test
    fun formatsTodayAsCompactTime() {
        val today = raftEpochDayForDate(2026, 5, 9)

        assertEquals("22:00", formatRaftTimestampLabel("2026-05-09T22:00:00+08:00", 480, today))
        assertEquals("22:00", formatRaftTimestampLabel("2026-05-09 22:00:00+08:00", 480, today))
        assertEquals("22:00", formatRaftTimestampLabel("2026-05-09 22:00:00.000+08:00", 480, today))
        assertEquals("22:00", formatRaftTimestampLabel("2026-05-09T22:00:00.000+0800", 480, today))
        assertEquals("22:00", formatRaftTimestampLabel("2026-05-09 22:00:00+0800", 480, today))
        assertEquals("22:00", formatRaftTimestampLabel("1778335200000", 480, today))
        assertEquals("22:00", formatRaftTimestampLabel("1778335200", 480, today))
    }

    @Test
    fun formatsTodayWithPreferredTwelveHourClock() {
        val today = raftEpochDayForDate(2026, 5, 9)

        assertEquals(
            "10:00 PM",
            formatRaftTimestampLabel("2026-05-09T22:00:00+08:00", 480, today, RaftTimeFormat12Hour)
        )
        assertEquals(
            "12:05 AM",
            formatRaftTimestampLabel("2026-05-09T00:05:00+08:00", 480, today, RaftTimeFormat12Hour)
        )
        assertEquals(
            "12:05 PM",
            formatRaftTimestampLabel("2026-05-09T12:05:00+08:00", 480, today, RaftTimeFormat12Hour)
        )
    }

    @Test
    fun handlesServerMicrosecondUtcHourOffset() {
        val today = raftEpochDayForDate(2026, 5, 10)

        assertEquals(
            "05:53",
            formatRaftTimestampLabel("2026-05-09 21:53:33.024426+00", 480, today)
        )
    }

    @Test
    fun formatsActivityClockWithSecondsWithoutRawServerTimestamp() {
        assertEquals(
            "13:55:27",
            formatRaftTimestampClockWithSeconds("2026-06-10 13:55:27.466929+00", 0)
        )
        assertEquals(
            "21:55:27",
            formatRaftTimestampClockWithSeconds("2026-06-10 13:55:27.466929+00", 480)
        )
        assertEquals("13:55:27", formatRaftTimestampClockWithSeconds("1781099727466", 0))
        assertEquals(
            "01:55:27 PM",
            formatRaftTimestampClockWithSeconds("2026-06-10 13:55:27.466929+00", 0, RaftTimeFormat12Hour)
        )
    }

    @Test
    fun formatsThroughSharedTimeContext() {
        val context =
            RaftTimeContext(
                localOffsetMinutes = 480,
                todayEpochDay = raftEpochDayForDate(2026, 5, 10),
                nowEpochMillis = 1778335200000L,
                timeFormat = RaftTimeFormat12Hour
            )

        assertEquals(
            "05-09 10:00 PM",
            formatRaftTimestampLabel("2026-05-09T22:00:00+08:00", context)
        )
        assertEquals(
            "09:55:27 PM",
            formatRaftTimestampClockWithSeconds("2026-06-10 13:55:27.466929+00", context)
        )
        assertEquals(
            "10:00 PM",
            formatRaftTimestampLabel("2026-05-09T22:00:00+08:00", context.copy(todayEpochDay = null))
        )
        assertEquals(
            "raw value",
            formatRaftTimestampLabel("raw value", context.copy(todayEpochDay = null, nowEpochMillis = null))
        )
    }

    @Test
    fun refreshesTimeContextAcrossLocalMidnight() {
        val beforeMidnight =
            parseRaftTimestampEpochMillisOrNull("2026-07-22T23:59:30+08:00")!!
        val afterMidnight =
            parseRaftTimestampEpochMillisOrNull("2026-07-23T00:00:05+08:00")!!
        val seed =
            RaftTimeContext(
                localOffsetMinutes = 480,
                todayEpochDay = raftEpochDayForDate(2026, 7, 22),
                nowEpochMillis = beforeMidnight,
                timeFormat = RaftTimeFormat12Hour
            )

        val refreshed = seed.refreshedAt(afterMidnight)

        assertEquals(raftEpochDayForDate(2026, 7, 23), refreshed.todayEpochDay)
        assertEquals(afterMidnight, refreshed.nowEpochMillis)
        assertEquals(480, refreshed.localOffsetMinutes)
        assertEquals(RaftTimeFormat12Hour, refreshed.timeFormat)
        assertEquals(30_000L, raftTimeContextRefreshDelayMillis(beforeMidnight))
        assertEquals(55_000L, raftTimeContextRefreshDelayMillis(afterMidnight))
    }

    @Test
    fun includesDateOutsideToday() {
        val today = raftEpochDayForDate(2026, 5, 10)

        assertEquals("22:00", formatRaftTimestampLabel("2026-05-10T22:00:00+08:00", 480, today))
        assertEquals("05-09 22:00", formatRaftTimestampLabel("2026-05-09T22:00:00+08:00", 480, today))
        assertEquals(
            "05-09 10:00 PM",
            formatRaftTimestampLabel("2026-05-09T22:00:00+08:00", 480, today, RaftTimeFormat12Hour)
        )
        assertEquals(
            "2025-12-31 23:58",
            formatRaftTimestampLabel("2025-12-31T23:58:00+08:00", 480, today)
        )
    }

    @Test
    fun parsesEpochForSorting() {
        assertEquals(1778335200000L, parseRaftTimestampEpochMillisOrNull("1778335200000"))
        assertEquals(1778335200000L, parseRaftTimestampEpochMillisOrNull("1778335200"))
        assertEquals(
            1778335200000L,
            parseRaftTimestampEpochMillisOrNull("2026-05-09T22:00:00+08:00")
        )
        assertNull(parseRaftTimestampEpochMillisOrNull("not a timestamp"))
    }
}
