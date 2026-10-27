package build.raft.app

import build.raft.app.data.ProcessMemberPresenceRegistry
import build.raft.app.data.ProcessServerMembersOwner
import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.AgentStatus
import build.raft.app.model.AgentToolStuck
import build.raft.app.model.AgentWakeCrashLoop
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.Presence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * `agent:activity` while the agent is stuck on one tool call: the daemon attaches the shared
 * `ToolStuckActivityDiagnostic` (`toolStuck`, botiverse/slock #10409) to every frame while that is
 * true, and a frame without it means "not stuck" (web #10446).
 */
class AgentToolStuckTest {
    private val stuck =
        AgentToolStuck(
            toolName = "bash",
            toolDetailKind = "running_command",
            startedAtMs = 1_000L,
            quietSinceMs = 1_000L,
            thresholdMs = 600_000L,
            queuedMessages = 3
        )

    private fun payload(serverSeq: Long, toolStuck: String?): String {
        val carrier = if (toolStuck == null) "" else ""","toolStuck": $toolStuck"""
        return """
        {
          "agentId": "agent-1",
          "activity": "working",
          "activityKind": "working",
          "detail": "Running command…",
          "detailKind": "running_command",
          "timestamp": 1782367200000,
          "serverSeq": $serverSeq$carrier
        }
        """.trimIndent()
    }

    private val stuckJson =
        """{"toolName":"bash","toolDetailKind":"running_command","startedAtMs":1000,"quietSinceMs":1000,""" +
            """"thresholdMs":600000,"queuedMessages":3}"""

    private fun map(payload: String): MemberPresenceUpdated =
        assertIs<MemberPresenceUpdated>(RealtimeEventMapper().map(RealtimeSocketEvents.AgentActivity, payload))

    @Test
    fun activityFrameCarriesToolStuck() {
        val frame = map(payload(serverSeq = 7, toolStuck = stuckJson))
        assertEquals(AgentStatus.Working, frame.agentStatus)
        assertEquals(stuck, frame.toolStuck)
        // Any detail kind may carry it; without a tool name it is not shown.
        assertNull(map(payload(serverSeq = 8, toolStuck = """{"quietSinceMs":1000}""")).toolStuck)
        assertNull(map(payload(serverSeq = 9, toolStuck = null)).toolStuck)
    }

    @Test
    fun latestFrameWinsAndAFrameWithoutItClearsIt() {
        val owner = ProcessServerMembersOwner(environmentId = "env-1", accountId = "acct-1", serverId = "server-a")
        val registry = ProcessMemberPresenceRegistry()
        registry.apply(owner, map(payload(serverSeq = 1, toolStuck = stuckJson)), 0L)
        assertEquals(stuck, registry.factFor(owner, "agent-1")?.toolStuck)

        // A later heartbeat with a grown queue updates the count, though the activity repeats.
        registry.apply(owner, map(payload(serverSeq = 2, toolStuck = stuckJson.replace("\"queuedMessages\":3", "\"queuedMessages\":5"))), 0L)
        assertEquals(5, registry.factFor(owner, "agent-1")?.toolStuck?.queuedMessages)

        registry.apply(owner, map(payload(serverSeq = 3, toolStuck = null)), 0L)
        assertNull(registry.factFor(owner, "agent-1")?.toolStuck)
    }

    @Test
    fun minutesCountFromTheQuietStartAtLeastOneAndNeverBelowTheThreshold() {
        assertEquals(12L, agentToolStuckMinutes(stuck, nowEpochMillis = 1_000L + 12 * 60_000L + 30_000L))
        // A device clock slightly behind the Computer's still reads the threshold.
        assertEquals(10L, agentToolStuckMinutes(stuck, nowEpochMillis = 1_000L + 2 * 60_000L))
        // No threshold reported and a clock behind the Computer's: at least 1 minute.
        assertEquals(1L, agentToolStuckMinutes(stuck.copy(thresholdMs = 0L), nowEpochMillis = 0L))
    }

    @Test
    fun textOmitsTheQueuePartWhenNothingWaits() {
        val line = "Stuck on bash · no output for 12 min"
        assertEquals(line, agentToolStuckText(line, 0, "1 message waiting", "0 messages waiting"))
        assertEquals("$line · 1 message waiting", agentToolStuckText(line, 1, "1 message waiting", "1 messages waiting"))
        assertEquals("$line · 3 messages waiting", agentToolStuckText(line, 3, "1 message waiting", "3 messages waiting"))
    }

    @Test
    fun offlineOrBlockedStatusWinsOverAStoredStuckReport() {
        val working = raftAgentStatusIndicatorState(status = AgentStatus.Working, presence = Presence.Active, external = false)
        assertEquals(stuck, working.copy(toolStuck = stuck).visibleToolStuck())
        assertNull(working.visibleToolStuck())

        val offline = raftAgentStatusIndicatorState(status = AgentStatus.Offline, presence = Presence.Offline, external = false)
        assertNull(offline.copy(toolStuck = stuck).visibleToolStuck())
        val disconnected = raftAgentStatusIndicatorState(status = AgentStatus.Disconnected, presence = Presence.Offline, external = false)
        assertNull(disconnected.copy(toolStuck = stuck).visibleToolStuck())
        val blocked =
            working.copy(
                detailKind = "wake_crash_loop_blocked",
                wakeCrashLoop = AgentWakeCrashLoop(earlyExitCount = 3),
                toolStuck = stuck
            )
        assertNull(blocked.visibleToolStuck())
    }

    @Test
    fun profileUsesTheLoadedReportOnlyUntilALiveStatusIsObserved() {
        val working = raftAgentStatusIndicatorState(status = AgentStatus.Working, presence = Presence.Active, external = false)
        // A live frame without it means "not stuck": the loaded profile's older report is not shown.
        assertNull(membersAgentToolStuck(working, summaryToolStuck = stuck))
        assertEquals(stuck, membersAgentToolStuck(working.copy(toolStuck = stuck), summaryToolStuck = null))
        assertEquals(stuck, membersAgentToolStuck(null, summaryToolStuck = stuck))
        val offline = raftAgentStatusIndicatorState(status = AgentStatus.Offline, presence = Presence.Offline, external = false)
        assertNull(membersAgentToolStuck(offline.copy(toolStuck = stuck), summaryToolStuck = stuck))
    }
}
