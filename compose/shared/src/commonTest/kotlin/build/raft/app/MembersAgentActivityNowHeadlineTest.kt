package build.raft.app

import build.raft.app.data.AgentActivityTurn
import build.raft.app.data.AgentActivityTurnOutcome
import build.raft.app.members.MembersAgentNowHeadline
import build.raft.app.members.membersAgentNowHeadline
import build.raft.app.model.AgentStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Activity overview's "now" card (web packages/web/src/components/agent/AgentNowCard.tsx at
 * botiverse/slock 4be606f7fd, from #13108; its overview tests "the now card says working and for
 * how long, counting on, or idle and when the last turn ended" and "working without a running turn
 * says the step once"): which headline it shows, the time it counts, and Ottie.
 */
class MembersAgentActivityNowHeadlineTest {
    // 2026-06-19T12:00:00Z.
    private val t0 = 1_781_870_400_000L

    private fun turn(startedAt: Long, endedAt: Long, running: Boolean = false) =
        AgentActivityTurn(
            id = if (running) "running" else "t$startedAt",
            startedAtEpochMillis = startedAt,
            endedAtEpochMillis = endedAt,
            outcome = if (running) AgentActivityTurnOutcome.Unknown else AgentActivityTurnOutcome.Completed,
            trigger = null,
            actions = emptyList(),
            toolCalls = 0,
            problems = emptyList(),
            problemCount = 0,
            messagesSent = 0,
            lastReplyMessageId = null,
            running = running,
        )

    // The server read the running turn a second after it started; the last turn ended before it.
    private val running = turn(startedAt = t0, endedAt = t0 + 1_000, running = true)
    private val last = turn(startedAt = t0 - 120_000, endedAt = t0 - 20_000)

    @Test
    fun busyWithARunningTurnSaysForHowLongFromItsStart() {
        // From the turn's start, not from when the server read it: 3m 12s, counted to the second.
        listOf(AgentStatus.Working, AgentStatus.Thinking).forEach { status ->
            assertEquals(
                MembersAgentNowHeadline.WorkingFor(192_000),
                membersAgentNowHeadline(status, running, last, nowEpochMillis = t0 + 191_600),
                "$status",
            )
        }
        // A second later the same turn reads a second longer.
        assertEquals(
            MembersAgentNowHeadline.WorkingFor(193_000),
            membersAgentNowHeadline(AgentStatus.Working, running, last, nowEpochMillis = t0 + 192_600),
        )
    }

    @Test
    fun onlineAfterAFinishedTurnSaysWhenItEnded() {
        assertEquals(
            MembersAgentNowHeadline.IdleSince(t0 - 20_000),
            membersAgentNowHeadline(AgentStatus.Online, running = null, lastFinished = last, nowEpochMillis = t0 + 600_000),
        )
        // A running turn the live status does not show as busy changes nothing: the last turn's end.
        assertEquals(
            MembersAgentNowHeadline.IdleSince(t0 - 20_000),
            membersAgentNowHeadline(AgentStatus.Online, running, last, nowEpochMillis = t0 + 600_000),
        )
    }
}
