package build.raft.app

import build.raft.app.model.RaftTimeFormat12Hour
import build.raft.app.model.raftEpochDayForDate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Device test B1 (Android, staging 2026-10-26): a done card read "Committed by X ·
 * 2026-10-26T13:14:00.392Z". The thread payload has no "today", so the label must take the page's
 * live day, and format like web `CommittedByLine` (`formatMessageTime`): the clock today, else the
 * date and the clock.
 */
class ThreadActionCardExecutedTimeLabelTest {
    private val executedAt = "2026-10-26T13:14:00.392Z"
    private val today = raftEpochDayForDate(2026, 10, 26)

    @Test
    fun theDoneLineTimeUsesTheLiveDayAndNeverShowsTheWireTimestamp() {
        // The thread payload as loaded: an offset and a format, but no today/now.
        val utc = RaftThreadScreenPayload(localOffsetMinutes = 0).toThreadTimelineRenderConfig()
        val shanghai12h = RaftThreadScreenPayload(localOffsetMinutes = 480, timeFormat = RaftTimeFormat12Hour).toThreadTimelineRenderConfig()
        val cases =
            listOf(
                Triple("today", utc.formatActionCardExecutedTimeLabel(executedAt, today), "13:14"),
                Triple("an earlier day", utc.formatActionCardExecutedTimeLabel(executedAt, today + 1), "10-26 13:14"),
                Triple("an earlier year", utc.formatActionCardExecutedTimeLabel(executedAt, raftEpochDayForDate(2027, 1, 2)), "2026-10-26 13:14"),
                Triple("local offset, 12h", shanghai12h.formatActionCardExecutedTimeLabel(executedAt, today), "09:14 PM"),
                Triple("no live day yet", utc.formatActionCardExecutedTimeLabel(executedAt, null), null),
                Triple("not executed", utc.formatActionCardExecutedTimeLabel(" ", today), null),
            )
        for ((case, actual, expected) in cases) assertEquals(expected, actual, case)
    }
}
