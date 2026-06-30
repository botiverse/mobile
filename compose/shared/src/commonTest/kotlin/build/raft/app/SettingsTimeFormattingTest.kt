package build.raft.app

import build.raft.app.model.SlockTimeContext
import build.raft.app.model.SlockTimeFormat12Hour
import build.raft.app.model.slockEpochDayForDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsTimeFormattingTest {
    @Test
    fun formatsSettingsTimestampsThroughSharedTimeContext() {
        val context =
            SlockTimeContext(
                localOffsetMinutes = 480,
                todayEpochDay = slockEpochDayForDate(2026, 6, 1)
            )

        assertEquals("09:00", formatSettingsTimestampLabel("2026-06-01T01:00:00Z", context))
        assertEquals("05-31 09:00", formatSettingsTimestampLabel("2026-05-31T01:00:00Z", context))
        assertEquals("09:00", formatSettingsTimestampLabel("1780275600000", context))
    }

    @Test
    fun honorsTwelveHourPreferenceAndPreservesNonTimestampLabels() {
        val context =
            SlockTimeContext(
                localOffsetMinutes = 480,
                todayEpochDay = slockEpochDayForDate(2026, 6, 1),
                timeFormat = SlockTimeFormat12Hour
            )

        assertEquals("09:00 AM", formatSettingsTimestampLabel("2026-06-01T01:00:00Z", context))
        assertEquals("Active", formatSettingsTimestampLabel("Active", context))
        assertNull(formatSettingsTimestampLabel("  ", context))
        assertEquals("2026-06-01T01:00:00Z", formatSettingsTimestampLabel("2026-06-01T01:00:00Z", null))
    }
}
