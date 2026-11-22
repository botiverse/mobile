package build.raft.app.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
