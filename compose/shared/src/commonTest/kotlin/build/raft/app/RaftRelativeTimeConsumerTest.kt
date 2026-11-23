package build.raft.app

import build.raft.app.model.RaftTimeContext
import build.raft.app.model.RaftTimeFormat12Hour
import build.raft.app.model.raftEpochDayForDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RaftRelativeTimeConsumerTest {
    @Test
    fun filesKeepRawCreatedAtForLiveLeafFormatting() {
        val raw = "2026-07-27T12:00:00.123Z"

        assertEquals(raw, threadFileCreatedAtValue(raw))
        assertEquals("", threadFileCreatedAtValue(null))
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
        // The absolute label is the message row's time (web formatMessageTime; per language in RaftMessageTimeFormatTest).
        assertEquals("11:06", raftMessageTimeLabel(timestamp, context, RaftEnglishMessageTime))
        assertEquals(
            "11:06 AM",
            raftMessageTimeLabel(timestamp, context.copy(timeFormat = RaftTimeFormat12Hour), RaftEnglishMessageTime)
        )
        assertEquals(
            "07/30 11:06",
            raftMessageTimeLabel(
                timestamp,
                context.copy(todayEpochDay = raftEpochDayForDate(2026, 8, 1)),
                RaftEnglishMessageTime
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
}
