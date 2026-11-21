package build.raft.app

import build.raft.app.data.AgentActivityTurn
import build.raft.app.data.AgentActivityTurnOutcome
import build.raft.app.model.AgentStatus
import build.raft.app.model.Presence
import build.raft.designsystem.components.RaftStepStatus
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

    @Test
    fun anyOtherStateIsTheStatusItself() {
        val now = t0 + 600_000
        // Working without a running turn (an older server): the headline is the step, said once.
        assertEquals(MembersAgentNowHeadline.Status, membersAgentNowHeadline(AgentStatus.Working, null, last, now))
        // Online before any turn finished.
        assertEquals(MembersAgentNowHeadline.Status, membersAgentNowHeadline(AgentStatus.Online, null, null, now))
        // A crash, a stop, a lost connection, an away presence, and a status not observed yet.
        listOf(AgentStatus.Error, AgentStatus.Offline, AgentStatus.Disconnected, AgentStatus.Idle, null).forEach { status ->
            assertEquals(MembersAgentNowHeadline.Status, membersAgentNowHeadline(status, running, last, now), "$status")
        }
    }

    @Test
    fun theTimeIsWholeSecondsAsShown() {
        // Web `Math.round(ms / 1000) * 1000`: half a second rounds up.
        assertEquals(0L, membersAgentNowElapsedMillis(t0, t0 + 499))
        assertEquals(1_000L, membersAgentNowElapsedMillis(t0, t0 + 500))
        assertEquals(192_000L, membersAgentNowElapsedMillis(t0, t0 + 191_600))
        // Read by web formatTurnDuration: "3m 12s", "2h 5m", and never under its 1s floor, even when
        // the producer's clock started the turn ahead of this device's.
        assertEquals(MembersActivityOverviewDurationParts(0, 3, 12), membersActivityOverviewDurationParts(membersAgentNowElapsedMillis(t0, t0 + 191_600)))
        assertEquals(MembersActivityOverviewDurationParts(2, 5, 0), membersActivityOverviewDurationParts(membersAgentNowElapsedMillis(t0, t0 + 7_500_200)))
        assertEquals(MembersActivityOverviewDurationParts(0, 0, 1), membersActivityOverviewDurationParts(membersAgentNowElapsedMillis(t0, t0 - 5_000)))
    }

    @Test
    fun ottieFollowsTheLiveIndicator() {
        // The live indicator's status, not the label (the dot it replaces read the same SSOT).
        fun ottieWhen(status: AgentStatus, presence: Presence, label: String) =
            MembersAgentActivityNow(label, raftAgentStatusIndicatorState(status, presence, external = false)).otter()
        assertEquals(RaftStepStatus.Process, ottieWhen(AgentStatus.Working, Presence.Active, "Running tests"))
        assertEquals(RaftStepStatus.Process, ottieWhen(AgentStatus.Thinking, Presence.Active, "Thinking"))
        assertEquals(RaftStepStatus.Error, ottieWhen(AgentStatus.Error, Presence.Offline, "Error"))
        assertEquals(RaftStepStatus.Wait, ottieWhen(AgentStatus.Online, Presence.Active, "Idle"))
        assertEquals(RaftStepStatus.Wait, ottieWhen(AgentStatus.Disconnected, Presence.Offline, "Disconnected"))
        // Not observed yet: resting, whatever the label says.
        assertEquals(RaftStepStatus.Wait, MembersAgentActivityNow("Working", indicator = null).otter())
    }
}
