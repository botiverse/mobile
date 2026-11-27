package build.raft.app

import build.raft.app.model.RaftTimeContext
import build.raft.app.model.RaftTimeFormat24Hour
import build.raft.app.settings.SettingsCompactDate
import build.raft.app.settings.settingsCompactDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/** Web `formatCompactDate`: today the clock, this year the month and day, else the ISO date. */
class SettingsCompactDateTest {
    // 2026-11-18 09:30 in UTC+8.
    private val context =
        RaftTimeContext(
            localOffsetMinutes = 480,
            nowEpochMillis = Instant.parse("2026-11-18T01:30:00Z").toEpochMilliseconds(),
            timeFormat = RaftTimeFormat24Hour,
        )

    @Test
    fun picksTheFormByDayAndYear() {
        assertEquals(SettingsCompactDate.Clock("08:05"), settingsCompactDate("2026-11-18T00:05:00.000Z", context))
        // 23:30 UTC on the 17th is already the 18th at UTC+8.
        assertEquals(SettingsCompactDate.Clock("07:30"), settingsCompactDate("2026-11-17T23:30:00.000Z", context))
        assertEquals(SettingsCompactDate.MonthDay(month = 11, day = 17), settingsCompactDate("2026-11-17T15:00:00.000Z", context))
        assertEquals(SettingsCompactDate.MonthDay(month = 1, day = 2), settingsCompactDate("2026-01-02T08:00:00.000Z", context))
        assertEquals(SettingsCompactDate.IsoDate("2025-12-31"), settingsCompactDate("2025-12-31T08:00:00.000Z", context))
    }

    @Test
    fun fallsBackToLocalIsoDateWithoutTodayAndNullForInvalidOrMissingContext() {
        assertEquals(
            SettingsCompactDate.IsoDate("2026-11-18"),
            settingsCompactDate("2026-11-18T00:05:00.000Z", context.copy(todayEpochDay = null, nowEpochMillis = null)),
        )
        assertNull(settingsCompactDate("yesterday", context))
        assertNull(settingsCompactDate("2026-11-18T00:05:00.000Z", null))
    }
}
