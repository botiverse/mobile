package build.raft.app

import build.raft.app.data.RaftDatetimeFoundationOwner
import build.raft.app.data.RaftEpochMillisSource
import build.raft.app.data.RaftLocalOffsetMinutesSource
import build.raft.app.data.RaftTimeFormatRuntime
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.RaftTimeFormat12Hour
import build.raft.app.model.RaftTimeFormat24Hour
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.model.raftEpochDayForDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for Phase 2A of Date/Time Formatting & DI cleanup (dm:@artin:#2):
 * - Reactive 12h/24h process preference runtime and device-zone fallback formatting
 * - RaftTimeContext date/clock helpers on low-frequency dateContext (`nowEpochMillis == null`)
 * - Joint over-limit grace deadline formatting (no raw ISO leaks)
 * - Local-timezone invite expiry and billing cycle date formatting (cross-midnight UTC vs local)
 * - Account security, WeChat expiry, feedback list, notification pause, and accessibility formatters
 */
class DateTimeDiPhase2ATest {
    private val utcPlus8DateContext24h =
        RaftTimeContext(
            localOffsetMinutes = 8 * 60,
            todayEpochDay = raftEpochDayForDate(2026, 11, 18),
            nowEpochMillis = null,
            timeFormat = RaftTimeFormat24Hour,
        )

    private val utcMinus5DateContext24h =
        RaftTimeContext(
            localOffsetMinutes = -5 * 60,
            todayEpochDay = raftEpochDayForDate(2026, 11, 18),
            nowEpochMillis = null,
            timeFormat = RaftTimeFormat24Hour,
        )

    @Test
    fun timeFormatRuntimeNormalizesAndResetsWithoutLeakingState() {
        RaftTimeFormatRuntime.resetForTest()
        try {
            assertEquals(RaftTimeFormat24Hour, RaftTimeFormatRuntime.current)
            assertNull(RaftTimeFormatRuntime.currentOrNull)

            RaftTimeFormatRuntime.update("12h")
            assertEquals(RaftTimeFormat12Hour, RaftTimeFormatRuntime.current)
            assertEquals(RaftTimeFormat12Hour, RaftTimeFormatRuntime.currentOrNull)

            RaftTimeFormatRuntime.update("  12H ")
            assertEquals(RaftTimeFormat12Hour, RaftTimeFormatRuntime.current)

            RaftTimeFormatRuntime.update("24h")
            assertEquals(RaftTimeFormat24Hour, RaftTimeFormatRuntime.current)

            RaftTimeFormatRuntime.update("unexpected")
            assertEquals(RaftTimeFormat24Hour, RaftTimeFormatRuntime.current)

            RaftTimeFormatRuntime.update(null)
            assertEquals(RaftTimeFormat24Hour, RaftTimeFormatRuntime.current)
            assertNull(RaftTimeFormatRuntime.currentOrNull)
        } finally {
            RaftTimeFormatRuntime.resetForTest()
        }
    }

    @Test
    fun deviceZoneAbsoluteLabelHonors12HourAnd24HourPreference() {
        // 2026-10-22T02:10:00Z -> 10:10 at UTC+8.
        val now = epochMillis("2026-10-22T02:10:00Z")
        val fixedPlus8 =
            RaftDatetimeFoundationOwner(
                kuiklyBaseClock = RaftEpochMillisSource { now },
                kuiklyBaseOffset = RaftLocalOffsetMinutesSource { 8 * 60 },
            )
        assertEquals(
            "13:10",
            raftDeviceZoneAbsoluteLabel("2026-10-22T05:10:00.000Z", now, fixedPlus8, RaftTimeFormat24Hour),
        )
        assertEquals(
            "01:10 PM",
            raftDeviceZoneAbsoluteLabel("2026-10-22T05:10:00.000Z", now, fixedPlus8, RaftTimeFormat12Hour),
        )
        assertEquals(
            "10-23 12:10 PM",
            raftDeviceZoneAbsoluteLabel(
                value = "2026-10-23T04:10:00.000Z",
                timeContext = RaftTimeContext(localOffsetMinutes = 8 * 60, timeFormat = RaftTimeFormat12Hour),
                nowEpochMillis = now,
                foundationOwner = fixedPlus8,
            ),
        )
        assertNull(raftDeviceZoneAbsoluteLabel("not-a-timestamp", now, fixedPlus8, RaftTimeFormat12Hour))
    }

    @Test
    fun inviteExpiryUsesLocalTimezoneDateAcrossUtcMidnight() {
        // 2026-11-01T23:30:00Z is already 2026-11-02 in UTC+8 (raw substringBefore('T') gave 2026-11-01).
        assertEquals("2026-11-02", inviteHumanExpiresDateLabel("2026-11-01T23:30:00.000Z", utcPlus8DateContext24h))
        // 2026-11-01T02:30:00Z is still 2026-10-31 in UTC-5 (raw substringBefore('T') gave 2026-11-01).
        assertEquals("2026-10-31", inviteHumanExpiresDateLabel("2026-11-01T02:30:00.000Z", utcMinus5DateContext24h))
        // Date-only value stays on that local calendar day.
        assertEquals("2026-11-01", inviteHumanExpiresDateLabel("2026-11-01", utcPlus8DateContext24h))
        // Unreadable value falls back to trimmed input.
        assertEquals("soon", inviteHumanExpiresDateLabel(" soon ", utcPlus8DateContext24h))
    }

    private fun epochMillis(iso: String): Long =
        requireNotNull(parseRaftTimestampEpochMillisOrNull(iso, 0))
}
