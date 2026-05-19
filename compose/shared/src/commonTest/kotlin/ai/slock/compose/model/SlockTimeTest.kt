package ai.slock.compose.model

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
    fun handlesServerMicrosecondUtcHourOffset() {
        val today = slockEpochDayForDate(2026, 5, 10)

        assertEquals(
            "05:53",
            formatSlockTimestampLabel("2026-05-09 21:53:33.024426+00", 480, today)
        )
    }

    @Test
    fun includesDateOutsideToday() {
        val today = slockEpochDayForDate(2026, 5, 10)

        assertEquals("22:00", formatSlockTimestampLabel("2026-05-10T22:00:00+08:00", 480, today))
        assertEquals("05-09 22:00", formatSlockTimestampLabel("2026-05-09T22:00:00+08:00", 480, today))
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
