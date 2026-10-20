package build.raft.app

import build.raft.app.model.RaftTimeContext
import build.raft.app.model.RaftTimeFormat12Hour
import build.raft.app.model.raftEpochDayForDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaftRelativeTimeConsumerTest {
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
        assertEquals(35f, beforePress.widthDp)
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
        assertEquals(39f, compactAuthorSlot.heightDp)

        val normalAuthorSlot =
            threadMessageMetadataSlotLayout(
                showAvatar = true,
                compactTimestampGutter = false,
                showTimestamp = false
            )
        assertEquals(35f, normalAuthorSlot.widthDp)
        assertEquals(39f, normalAuthorSlot.heightDp)
    }

    @Test
    fun systemMessageTimestampUsesRenderedLabelWidthInsteadOfMetadataGutter() {
        val layout = threadSystemMessageTimestampLayoutSpec()

        assertFalse(layout.reserveFullMetadataGutter)
        assertEquals(ThreadRelativeTimestampSlotWidthDp, layout.maxWidthDp)
    }

    @Test
    fun headerTimestampUsesRenderedLabelWidthInsteadOfMetadataGutter() {
        // task #1014: a reserved full-width slot starves the sender subtitle
        // and paints a blank band left of the time in the message header row.
        val layout = threadMessageHeaderTimestampLayoutSpec()

        assertFalse(layout.reserveFullMetadataGutter)
        assertEquals(ThreadRelativeTimestampSlotWidthDp, layout.maxWidthDp)
    }

    @Test
    fun parseableRelativeTimestampTogglesToLocalAbsoluteAndBack() {
        val context =
            RaftTimeContext(
                localOffsetMinutes = 8 * 60,
                todayEpochDay = raftEpochDayForDate(2026, 7, 30),
                nowEpochMillis = 1_785_383_220_000L
            )
        val timestamp = "2026-07-30T03:06:00Z"

        assertTrue(raftTimestampToggleAvailable(timestamp, context))
        assertEquals("11:06", raftAbsoluteTimestampLabel(timestamp, context))
        assertEquals(
            "11:06 AM",
            raftAbsoluteTimestampLabel(timestamp, context.copy(timeFormat = RaftTimeFormat12Hour))
        )
        assertEquals(
            "07-30 11:06",
            raftAbsoluteTimestampLabel(
                timestamp,
                context.copy(todayEpochDay = raftEpochDayForDate(2026, 7, 31))
            )
        )
        assertEquals(
            RaftTimestampDisplayMode.Absolute,
            nextRaftTimestampDisplayMode(
                current = RaftTimestampDisplayMode.Relative,
                value = timestamp,
                timeContext = context
            )
        )
        assertEquals(
            RaftTimestampDisplayMode.Relative,
            nextRaftTimestampDisplayMode(
                current = RaftTimestampDisplayMode.Absolute,
                value = timestamp,
                timeContext = context
            )
        )
    }

    @Test
    fun parentOwnedTimestampDoesNotInstallItsOwnToggleTarget() {
        val context =
            RaftTimeContext(
                localOffsetMinutes = 8 * 60,
                todayEpochDay = raftEpochDayForDate(2026, 7, 30),
                nowEpochMillis = 1_785_383_220_000L
            )
        val timestamp = "2026-07-30T03:06:00Z"

        assertTrue(raftTimestampToggleAvailable(timestamp, context))
        assertFalse(
            raftTimestampToggleAvailable(
                value = timestamp,
                timeContext = context,
                toggleEnabled = false,
            )
        )
    }

    @Test
    fun literalUnknownAndContextlessValuesStayNonInteractive() {
        val context =
            RaftTimeContext(
                localOffsetMinutes = 8 * 60,
                todayEpochDay = raftEpochDayForDate(2026, 7, 30),
                nowEpochMillis = 1_785_383_220_000L
            )

        listOf("Now", "10:38", "not-a-date", "").forEach { value ->
            assertFalse(raftTimestampToggleAvailable(value, context))
            assertNull(raftAbsoluteTimestampLabel(value, context))
            assertEquals(
                RaftTimestampDisplayMode.Relative,
                nextRaftTimestampDisplayMode(
                    current = RaftTimestampDisplayMode.Absolute,
                    value = value,
                    timeContext = context
                )
            )
        }

        assertFalse(
            raftTimestampToggleAvailable(
                value = "2026-07-30T03:06:00Z",
                timeContext = context.copy(nowEpochMillis = null)
            )
        )
    }

    @Test
    fun oneTimestampToggleChangesTheWindowModeForEveryParseableTimestamp() {
        val context =
            RaftTimeContext(
                localOffsetMinutes = 8 * 60,
                todayEpochDay = raftEpochDayForDate(2026, 7, 30),
                nowEpochMillis = 1_785_383_220_000L
            )
        val firstTimestamp = "2026-07-30T03:06:00Z"
        val secondTimestamp = "2026-07-30T04:07:00Z"
        val state = RaftTimestampDisplayState()

        assertEquals(
            RaftTimestampDisplayMode.Relative,
            state.displayMode(RaftTimestampDisplayGroup.Aggregate)
        )
        assertEquals(
            RaftTimestampDisplayMode.Absolute,
            state.toggle(RaftTimestampDisplayGroup.Aggregate, firstTimestamp, context)
        )
        assertEquals(
            RaftTimestampDisplayMode.Absolute,
            state.displayMode(RaftTimestampDisplayGroup.Aggregate)
        )
        assertEquals(
            RaftTimestampDisplayMode.Absolute,
            resolvedRaftTimestampDisplayMode(
                state.displayMode(RaftTimestampDisplayGroup.Aggregate),
                secondTimestamp,
                context
            )
        )

        assertEquals(
            RaftTimestampDisplayMode.Relative,
            state.toggle(RaftTimestampDisplayGroup.Aggregate, secondTimestamp, context)
        )
        assertEquals(
            RaftTimestampDisplayMode.Relative,
            state.displayMode(RaftTimestampDisplayGroup.Aggregate)
        )
        assertEquals(
            RaftTimestampDisplayMode.Relative,
            resolvedRaftTimestampDisplayMode(
                state.displayMode(RaftTimestampDisplayGroup.Aggregate),
                firstTimestamp,
                context
            )
        )
    }

    @Test
    fun nonInteractiveTimestampCannotResetTheWindowMode() {
        val context =
            RaftTimeContext(
                localOffsetMinutes = 8 * 60,
                todayEpochDay = raftEpochDayForDate(2026, 7, 30),
                nowEpochMillis = 1_785_383_220_000L
            )
        val parseableTimestamp = "2026-07-30T03:06:00Z"
        val state =
            RaftTimestampDisplayState(
                initialAggregateMode = RaftTimestampDisplayMode.Absolute
            )

        assertEquals(
            RaftTimestampDisplayMode.Relative,
            state.toggle(RaftTimestampDisplayGroup.Aggregate, "Now", context)
        )
        assertEquals(
            RaftTimestampDisplayMode.Absolute,
            state.displayMode(RaftTimestampDisplayGroup.Aggregate)
        )
        assertEquals(
            RaftTimestampDisplayMode.Relative,
            resolvedRaftTimestampDisplayMode(
                state.displayMode(RaftTimestampDisplayGroup.Aggregate),
                "Now",
                context
            )
        )
        assertEquals(
            RaftTimestampDisplayMode.Absolute,
            resolvedRaftTimestampDisplayMode(
                state.displayMode(RaftTimestampDisplayGroup.Aggregate),
                parseableTimestamp,
                context
            )
        )
    }

    @Test
    fun newWindowRestoresSurfaceDefaultsAndGroupsToggleIndependently() {
        val context =
            RaftTimeContext(
                localOffsetMinutes = 8 * 60,
                todayEpochDay = raftEpochDayForDate(2026, 7, 30),
                nowEpochMillis = 1_785_383_220_000L
            )
        val timestamp = "2026-07-30T03:06:00Z"
        val firstWindow = RaftTimestampDisplayState()

        assertEquals(
            RaftTimestampDisplayMode.Relative,
            firstWindow.displayMode(RaftTimestampDisplayGroup.Aggregate)
        )
        assertEquals(
            RaftTimestampDisplayMode.Absolute,
            firstWindow.displayMode(RaftTimestampDisplayGroup.Conversation)
        )
        firstWindow.toggle(RaftTimestampDisplayGroup.Aggregate, timestamp, context)
        firstWindow.toggle(RaftTimestampDisplayGroup.Conversation, timestamp, context)
        assertEquals(
            RaftTimestampDisplayMode.Absolute,
            firstWindow.displayMode(RaftTimestampDisplayGroup.Aggregate)
        )
        assertEquals(
            RaftTimestampDisplayMode.Relative,
            firstWindow.displayMode(RaftTimestampDisplayGroup.Conversation)
        )

        val nextWindow = RaftTimestampDisplayState()
        assertEquals(
            RaftTimestampDisplayMode.Relative,
            nextWindow.displayMode(RaftTimestampDisplayGroup.Aggregate)
        )
        assertEquals(
            RaftTimestampDisplayMode.Absolute,
            nextWindow.displayMode(RaftTimestampDisplayGroup.Conversation)
        )
    }

    @Test
    fun retainedWindowOwnerKeepsModesWhileNewWindowOwnerRestoresDefaults() {
        val context = RaftTimeContext(nowEpochMillis = 1_700_000_000_000L, localOffsetMinutes = 0)
        val retainedOwner = RaftTimestampDisplayViewModel()

        retainedOwner.state.toggle(
            RaftTimestampDisplayGroup.Aggregate,
            "2023-11-14T22:12:20Z",
            context
        )
        retainedOwner.state.toggle(
            RaftTimestampDisplayGroup.Conversation,
            "2023-11-14T22:12:20Z",
            context
        )

        assertEquals(
            RaftTimestampDisplayMode.Absolute,
            retainedOwner.state.displayMode(RaftTimestampDisplayGroup.Aggregate)
        )
        assertEquals(
            RaftTimestampDisplayMode.Relative,
            retainedOwner.state.displayMode(RaftTimestampDisplayGroup.Conversation)
        )

        val newWindowOwner = RaftTimestampDisplayViewModel()
        assertEquals(
            RaftTimestampDisplayMode.Relative,
            newWindowOwner.state.displayMode(RaftTimestampDisplayGroup.Aggregate)
        )
        assertEquals(
            RaftTimestampDisplayMode.Absolute,
            newWindowOwner.state.displayMode(RaftTimestampDisplayGroup.Conversation)
        )
    }

    @Test
    fun retainedTimestampOwnerKeyIsPagerScoped() {
        assertEquals("timestamp-display-window-a", raftTimestampDisplayViewModelKey("window-a"))
        assertEquals("timestamp-display-window-b", raftTimestampDisplayViewModelKey("window-b"))
        assertEquals("timestamp-display-default", raftTimestampDisplayViewModelKey(""))
    }
}
