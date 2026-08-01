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
    fun systemMessageTimestampUsesRenderedLabelWidthInsteadOfMetadataGutter() {
        val layout = threadSystemMessageTimestampLayoutSpec()

        assertFalse(layout.reserveFullMetadataGutter)
        assertEquals(ThreadRelativeTimestampSlotWidthDp, layout.maxWidthDp)
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

    @Test
    fun oneTimestampToggleChangesTheWindowModeForEveryParseableTimestamp() {
        val context =
            SlockTimeContext(
                localOffsetMinutes = 8 * 60,
                todayEpochDay = slockEpochDayForDate(2026, 7, 30),
                nowEpochMillis = 1_785_383_220_000L
            )
        val firstTimestamp = "2026-07-30T03:06:00Z"
        val secondTimestamp = "2026-07-30T04:07:00Z"
        val state = SlockTimestampDisplayState()

        assertEquals(
            SlockTimestampDisplayMode.Relative,
            state.displayMode(SlockTimestampDisplayGroup.Aggregate)
        )
        assertEquals(
            SlockTimestampDisplayMode.Absolute,
            state.toggle(SlockTimestampDisplayGroup.Aggregate, firstTimestamp, context)
        )
        assertEquals(
            SlockTimestampDisplayMode.Absolute,
            state.displayMode(SlockTimestampDisplayGroup.Aggregate)
        )
        assertEquals(
            SlockTimestampDisplayMode.Absolute,
            resolvedSlockTimestampDisplayMode(
                state.displayMode(SlockTimestampDisplayGroup.Aggregate),
                secondTimestamp,
                context
            )
        )

        assertEquals(
            SlockTimestampDisplayMode.Relative,
            state.toggle(SlockTimestampDisplayGroup.Aggregate, secondTimestamp, context)
        )
        assertEquals(
            SlockTimestampDisplayMode.Relative,
            state.displayMode(SlockTimestampDisplayGroup.Aggregate)
        )
        assertEquals(
            SlockTimestampDisplayMode.Relative,
            resolvedSlockTimestampDisplayMode(
                state.displayMode(SlockTimestampDisplayGroup.Aggregate),
                firstTimestamp,
                context
            )
        )
    }

    @Test
    fun nonInteractiveTimestampCannotResetTheWindowMode() {
        val context =
            SlockTimeContext(
                localOffsetMinutes = 8 * 60,
                todayEpochDay = slockEpochDayForDate(2026, 7, 30),
                nowEpochMillis = 1_785_383_220_000L
            )
        val parseableTimestamp = "2026-07-30T03:06:00Z"
        val state =
            SlockTimestampDisplayState(
                initialAggregateMode = SlockTimestampDisplayMode.Absolute
            )

        assertEquals(
            SlockTimestampDisplayMode.Relative,
            state.toggle(SlockTimestampDisplayGroup.Aggregate, "Now", context)
        )
        assertEquals(
            SlockTimestampDisplayMode.Absolute,
            state.displayMode(SlockTimestampDisplayGroup.Aggregate)
        )
        assertEquals(
            SlockTimestampDisplayMode.Relative,
            resolvedSlockTimestampDisplayMode(
                state.displayMode(SlockTimestampDisplayGroup.Aggregate),
                "Now",
                context
            )
        )
        assertEquals(
            SlockTimestampDisplayMode.Absolute,
            resolvedSlockTimestampDisplayMode(
                state.displayMode(SlockTimestampDisplayGroup.Aggregate),
                parseableTimestamp,
                context
            )
        )
    }

    @Test
    fun newWindowRestoresSurfaceDefaultsAndGroupsToggleIndependently() {
        val context =
            SlockTimeContext(
                localOffsetMinutes = 8 * 60,
                todayEpochDay = slockEpochDayForDate(2026, 7, 30),
                nowEpochMillis = 1_785_383_220_000L
            )
        val timestamp = "2026-07-30T03:06:00Z"
        val firstWindow = SlockTimestampDisplayState()

        assertEquals(
            SlockTimestampDisplayMode.Relative,
            firstWindow.displayMode(SlockTimestampDisplayGroup.Aggregate)
        )
        assertEquals(
            SlockTimestampDisplayMode.Absolute,
            firstWindow.displayMode(SlockTimestampDisplayGroup.Conversation)
        )
        firstWindow.toggle(SlockTimestampDisplayGroup.Aggregate, timestamp, context)
        firstWindow.toggle(SlockTimestampDisplayGroup.Conversation, timestamp, context)
        assertEquals(
            SlockTimestampDisplayMode.Absolute,
            firstWindow.displayMode(SlockTimestampDisplayGroup.Aggregate)
        )
        assertEquals(
            SlockTimestampDisplayMode.Relative,
            firstWindow.displayMode(SlockTimestampDisplayGroup.Conversation)
        )

        val nextWindow = SlockTimestampDisplayState()
        assertEquals(
            SlockTimestampDisplayMode.Relative,
            nextWindow.displayMode(SlockTimestampDisplayGroup.Aggregate)
        )
        assertEquals(
            SlockTimestampDisplayMode.Absolute,
            nextWindow.displayMode(SlockTimestampDisplayGroup.Conversation)
        )
    }

    @Test
    fun retainedWindowOwnerKeepsModesWhileNewWindowOwnerRestoresDefaults() {
        val context = SlockTimeContext(nowEpochMillis = 1_700_000_000_000L, localOffsetMinutes = 0)
        val retainedOwner = SlockTimestampDisplayViewModel()

        retainedOwner.state.toggle(
            SlockTimestampDisplayGroup.Aggregate,
            "2023-11-14T22:12:20Z",
            context
        )
        retainedOwner.state.toggle(
            SlockTimestampDisplayGroup.Conversation,
            "2023-11-14T22:12:20Z",
            context
        )

        assertEquals(
            SlockTimestampDisplayMode.Absolute,
            retainedOwner.state.displayMode(SlockTimestampDisplayGroup.Aggregate)
        )
        assertEquals(
            SlockTimestampDisplayMode.Relative,
            retainedOwner.state.displayMode(SlockTimestampDisplayGroup.Conversation)
        )

        val newWindowOwner = SlockTimestampDisplayViewModel()
        assertEquals(
            SlockTimestampDisplayMode.Relative,
            newWindowOwner.state.displayMode(SlockTimestampDisplayGroup.Aggregate)
        )
        assertEquals(
            SlockTimestampDisplayMode.Absolute,
            newWindowOwner.state.displayMode(SlockTimestampDisplayGroup.Conversation)
        )
    }

    @Test
    fun retainedTimestampOwnerKeyIsPagerScoped() {
        assertEquals("timestamp-display-window-a", slockTimestampDisplayViewModelKey("window-a"))
        assertEquals("timestamp-display-window-b", slockTimestampDisplayViewModelKey("window-b"))
        assertEquals("timestamp-display-default", slockTimestampDisplayViewModelKey(""))
    }
}
