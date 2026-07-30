package build.raft.app

import build.raft.app.model.SlockTimeContext
import build.raft.app.model.SlockTimeFormat12Hour
import build.raft.app.model.slockEpochDayForDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlockRelativeTimeConsumerTest {
    @Test
    fun filesKeepRawCreatedAtForLiveLeafFormatting() {
        val raw = "2026-07-27T12:00:00.123Z"

        assertEquals(raw, threadFileCreatedAtValue(raw))
        assertEquals("", threadFileCreatedAtValue(null))
    }

    @Test
    fun compactMetadataSlotKeepsGeometryStableWhenTimestampAppears() {
        val beforePress =
            threadMessageMetadataSlotLayout(
                showAvatar = false,
                compactTimestampGutter = true,
                showTimestamp = false
            )
        val duringPress =
            threadMessageMetadataSlotLayout(
                showAvatar = false,
                compactTimestampGutter = true,
                showTimestamp = true
            )

        assertEquals(112f, ThreadRelativeTimestampSlotWidthDp)
        assertEquals(36f, beforePress.widthDp)
        assertEquals(18f, beforePress.heightDp)
        assertEquals(false, beforePress.timestampVisible)
        assertEquals(beforePress.widthDp, duringPress.widthDp)
        assertEquals(beforePress.heightDp, duringPress.heightDp)
        assertEquals(true, duringPress.timestampVisible)

        val compactAuthorSlot =
            threadMessageMetadataSlotLayout(
                showAvatar = true,
                compactTimestampGutter = true,
                showTimestamp = false
            )
        assertEquals(beforePress.widthDp, compactAuthorSlot.widthDp)
        assertEquals(40f, compactAuthorSlot.heightDp)

        val normalAuthorSlot =
            threadMessageMetadataSlotLayout(
                showAvatar = true,
                compactTimestampGutter = false,
                showTimestamp = false
            )
        assertEquals(36f, normalAuthorSlot.widthDp)
        assertEquals(40f, normalAuthorSlot.heightDp)
    }

    @Test
    fun parseableRelativeTimestampTogglesToLocalAbsoluteAndBack() {
        val context =
            SlockTimeContext(
                localOffsetMinutes = 8 * 60,
                todayEpochDay = slockEpochDayForDate(2026, 7, 30),
                nowEpochMillis = 1_785_383_220_000L
            )
        val timestamp = "2026-07-30T03:06:00Z"

        assertTrue(slockTimestampToggleAvailable(timestamp, context))
        assertEquals("11:06", slockAbsoluteTimestampLabel(timestamp, context))
        assertEquals(
            "11:06 AM",
            slockAbsoluteTimestampLabel(timestamp, context.copy(timeFormat = SlockTimeFormat12Hour))
        )
        assertEquals(
            "07-30 11:06",
            slockAbsoluteTimestampLabel(
                timestamp,
                context.copy(todayEpochDay = slockEpochDayForDate(2026, 7, 31))
            )
        )
        assertEquals(
            SlockTimestampDisplayMode.Absolute,
            nextSlockTimestampDisplayMode(
                current = SlockTimestampDisplayMode.Relative,
                value = timestamp,
                timeContext = context
            )
        )
        assertEquals(
            SlockTimestampDisplayMode.Relative,
            nextSlockTimestampDisplayMode(
                current = SlockTimestampDisplayMode.Absolute,
                value = timestamp,
                timeContext = context
            )
        )
    }

    @Test
    fun literalUnknownAndContextlessValuesStayNonInteractive() {
        val context =
            SlockTimeContext(
                localOffsetMinutes = 8 * 60,
                todayEpochDay = slockEpochDayForDate(2026, 7, 30),
                nowEpochMillis = 1_785_383_220_000L
            )

        listOf("Now", "10:38", "not-a-date", "").forEach { value ->
            assertFalse(slockTimestampToggleAvailable(value, context))
            assertNull(slockAbsoluteTimestampLabel(value, context))
            assertEquals(
                SlockTimestampDisplayMode.Relative,
                nextSlockTimestampDisplayMode(
                    current = SlockTimestampDisplayMode.Absolute,
                    value = value,
                    timeContext = context
                )
            )
        }

        assertFalse(
            slockTimestampToggleAvailable(
                value = "2026-07-30T03:06:00Z",
                timeContext = context.copy(nowEpochMillis = null)
            )
        )
    }
}
