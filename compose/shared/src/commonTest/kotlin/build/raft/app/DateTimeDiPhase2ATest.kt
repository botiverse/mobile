package build.raft.app

import build.raft.app.assistant.weChatExpiryClock
import build.raft.app.data.RaftDatetimeFoundationOwner
import build.raft.app.data.RaftEpochMillisSource
import build.raft.app.data.RaftLocalOffsetMinutesSource
import build.raft.app.data.RaftTimeFormatRuntime
import build.raft.app.data.raftMinuteOfDayLabel
import build.raft.app.data.raftPausedUntilLabel
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.RaftTimeFormat12Hour
import build.raft.app.model.RaftTimeFormat24Hour
import build.raft.app.model.formatRaftClock
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.model.raftEpochDayForDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    private val utcPlus8DateContext12h =
        utcPlus8DateContext24h.copy(timeFormat = RaftTimeFormat12Hour)

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
    fun jointOverLimitGraceDeadlineResolvesLocalDateTimeIn24hAnd12h() {
        val graceIso = "2026-12-01T14:30:00.000Z"
        // UTC+8: 2026-12-01 22:30 (10:30 PM).
        val plus8 = taskHeadDateTime(graceIso, utcPlus8DateContext12h)
        assertEquals(TaskHeadDateTime(2026, 12, 1, 22, 30), plus8)
        assertEquals("10:30 PM", formatRaftClock(plus8!!.hour, plus8.minute, null, RaftTimeFormat12Hour))
        assertEquals("22:30", formatRaftClock(plus8.hour, plus8.minute, null, RaftTimeFormat24Hour))

        // Cross-midnight negative offset: 2026-12-01T02:15:00Z at UTC-5 is 2026-11-30 21:15.
        val minus5 = taskHeadDateTime("2026-12-01T02:15:00.000Z", utcMinus5DateContext24h)
        assertEquals(TaskHeadDateTime(2026, 11, 30, 21, 15), minus5)
        assertNull(taskHeadDateTime("not-an-iso", utcPlus8DateContext24h))
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

    @Test
    fun billingLocalDateUsesLocalTimezoneAcrossUtcMidnight() {
        // 2026-12-01T02:00:00Z is Nov 30, 2026 at UTC-5 (raw take(10).split('-') gave Dec 1).
        assertEquals(
            MembersJoinedDate(2026, 11, 30),
            settingsBillingLocalDate("2026-12-01T02:00:00.000Z", utcMinus5DateContext24h),
        )
        // 2026-12-01T22:00:00Z is Dec 2, 2026 at UTC+8 (raw take(10).split('-') gave Dec 1).
        assertEquals(
            MembersJoinedDate(2026, 12, 2),
            settingsBillingLocalDate("2026-12-01T22:00:00.000Z", utcPlus8DateContext24h),
        )
        // Screenshot fixture value at UTC+8 stays Dec 1, 2026.
        assertEquals(
            MembersJoinedDate(2026, 12, 1),
            settingsBillingLocalDate("2026-12-01T00:00:00.000Z", utcPlus8DateContext24h),
        )
        assertNull(settingsBillingLocalDate("unparseable", utcPlus8DateContext24h))
    }

    @Test
    fun uiClockAndNotificationFormattersHonorTimeContextAnd12hPreference() {
        val instant = epochMillis("2026-11-18T14:30:00Z") // 22:30 at UTC+8.
        assertEquals("22:30", secondFactorStepUpLocalClock(instant, utcPlus8DateContext24h))
        assertEquals("10:30 PM", secondFactorStepUpLocalClock(instant, utcPlus8DateContext12h))

        assertEquals("22:30", weChatExpiryClock("2026-11-18T14:30:00Z", utcPlus8DateContext24h))
        assertEquals("10:30 PM", weChatExpiryClock("2026-11-18T14:30:00Z", utcPlus8DateContext12h))
        assertEquals("not-a-time", weChatExpiryClock("not-a-time", utcPlus8DateContext12h))

        RaftTimeFormatRuntime.resetForTest()
        try {
            RaftTimeFormatRuntime.update(RaftTimeFormat12Hour)
            val stepUp12h = secondFactorStepUpLocalClock(instant)
            val weChat12h = weChatExpiryClock("2026-11-18T14:30:00Z")
            assertTrue(stepUp12h.endsWith(" AM") || stepUp12h.endsWith(" PM"))
            assertTrue(weChat12h.endsWith(" AM") || weChat12h.endsWith(" PM"))

            RaftTimeFormatRuntime.update(RaftTimeFormat24Hour)
            val clock24h = Regex("""^\d{2}:\d{2}$""")
            assertTrue(clock24h.matches(secondFactorStepUpLocalClock(instant)))
            assertTrue(clock24h.matches(weChatExpiryClock("2026-11-18T14:30:00Z")))
        } finally {
            RaftTimeFormatRuntime.resetForTest()
        }

        assertEquals("22:30", raftMinuteOfDayLabel(1350, RaftTimeFormat24Hour))
        assertEquals("10:30 PM", raftMinuteOfDayLabel(1350, RaftTimeFormat12Hour))
        assertEquals("00:00", raftMinuteOfDayLabel(0, RaftTimeFormat24Hour))
        assertEquals("12:00 AM", raftMinuteOfDayLabel(0, RaftTimeFormat12Hour))
        assertEquals("12:00", raftMinuteOfDayLabel(720, RaftTimeFormat24Hour))
        assertEquals("12:00 PM", raftMinuteOfDayLabel(720, RaftTimeFormat12Hour))

        val now = epochMillis("2026-11-18T02:00:00Z") // 10:00 at UTC+8.
        assertEquals("22:30", raftPausedUntilLabel(instant, now, utcPlus8DateContext24h))
        assertEquals("10:30 PM", raftPausedUntilLabel(instant, now, utcPlus8DateContext12h))
        val tomorrow = epochMillis("2026-11-19T01:00:00Z") // 09:00 on Nov 19 at UTC+8.
        assertEquals("11-19 09:00 AM", raftPausedUntilLabel(tomorrow, now, utcPlus8DateContext12h))

        // Explicit nowEpochMillis must take precedence over timeContext.todayEpochDay when they differ.
        val nov3Now = epochMillis("2026-11-03T08:00:00Z")
        val nov3Pause = epochMillis("2026-11-03T10:30:00Z")
        val oct30UtcContext =
            RaftTimeContext(
                localOffsetMinutes = 0,
                todayEpochDay = raftEpochDayForDate(2026, 10, 30),
                nowEpochMillis = null,
                timeFormat = RaftTimeFormat24Hour,
            )
        assertEquals("10:30", raftPausedUntilLabel(nov3Pause, nov3Now, oct30UtcContext))
        assertEquals("18:30", raftPausedUntilLabel(nov3Pause, nov3Now, utcPlus8DateContext24h))
        assertEquals("11-03 18:30", raftPausedUntilLabel(nov3Pause, null, utcPlus8DateContext24h))
    }

    @Test
    fun feedbackListTimeAndAccessibilityLabelWorkWithLowFrequencyDateContext() {
        val todayAt2230 = epochMillis("2026-11-18T14:30:00Z")
        val yesterday = epochMillis("2026-11-17T06:00:00Z")
        val sameYear = epochMillis("2026-09-05T06:00:00Z")
        val priorYear = epochMillis("2025-12-31T06:00:00Z")

        assertEquals(FeedbackListTime.Clock("22:30"), feedbackListTime(todayAt2230, utcPlus8DateContext24h))
        assertEquals(FeedbackListTime.Clock("10:30 PM"), feedbackListTime(todayAt2230, utcPlus8DateContext12h))
        assertEquals(FeedbackListTime.Yesterday, feedbackListTime(yesterday, utcPlus8DateContext24h))
        assertEquals(FeedbackListTime.Date(month = 9, day = 5, year = null), feedbackListTime(sameYear, utcPlus8DateContext24h))
        assertEquals(FeedbackListTime.Date(month = 12, day = 31, year = 2025), feedbackListTime(priorYear, utcPlus8DateContext24h))

        assertEquals(
            "10:30 PM",
            threadRowTimeAccessibilityLabel("2026-11-18T14:30:00Z", "22:30", utcPlus8DateContext12h),
        )
        assertEquals(
            "11-17 02:00 PM",
            threadRowTimeAccessibilityLabel("2026-11-17T06:00:00Z", "14:00", utcPlus8DateContext12h),
        )
        assertEquals(
            "22:30",
            threadRowTimeAccessibilityLabel("2026-11-18T14:30:00Z", "22:30", null),
        )
    }

    private fun epochMillis(iso: String): Long =
        requireNotNull(parseRaftTimestampEpochMillisOrNull(iso, 0))
}
