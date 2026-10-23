package build.raft.app.data.realtime

import build.raft.app.membersAgentActivityWakeCrashLoop
import build.raft.app.model.AgentStatus
import build.raft.app.model.AgentWakeCrashLoop
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.Presence
import build.raft.app.raftAgentStatusIndicatorState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `agent:activity` while the server pauses automatic wakes (agentOrchestrator `broadcastActivity`
 * public payload at botiverse/slock 24873fd5be37: `wakeCrashLoop` rides a
 * `wake_crash_loop_blocked` status; `lastExitReason` from #10860), and which pause the Activity
 * "now" card explains.
 */
class RealtimeAgentWakePauseTest {
    private fun pausePayload(detailKind: String = "wake_crash_loop_blocked", lastExitReason: String? = "memory_limit"): String {
        val reason = if (lastExitReason == null) "" else ", \"lastExitReason\": \"$lastExitReason\""
        return """
        {
          "agentId": "agent-1",
          "activity": "offline",
          "activityKind": "offline",
          "detail": "Automatic wake paused: stopped 3 times in a row at this Computer's per-agent memory limit",
          "detailKind": "$detailKind",
          "timestamp": 1782367200000,
          "serverSeq": 51,
          "wakeCrashLoop": {
            "episode": 2, "earlyExitCount": 3, "threshold": 3, "windowMs": 120000, "blocked": true,
            "blockedAtMs": 1782367200000, "firstExitAtMs": 1782360000000, "lastExitAtMs": 1782367100000,
            "lastExitKind": "agent_process_exited", "lastSignal": "SIGKILL", "lastLaunchId": "launch-1"
            $reason
          }
        }
        """.trimIndent()
    }

    private fun map(payload: String): MemberPresenceUpdated =
        assertIs<MemberPresenceUpdated>(RealtimeEventMapper().map(RealtimeSocketEvents.AgentActivity, payload))

    @Test
    fun pauseCarriesItsCountAndReasonOnlyWithItsKind() {
        val paused = map(pausePayload())
        assertEquals(AgentStatus.Offline, paused.agentStatus)
        assertEquals("wake_crash_loop_blocked", paused.detailKind)
        assertEquals(AgentWakeCrashLoop(earlyExitCount = 3, lastExitReason = "memory_limit"), paused.wakeCrashLoop)
        assertTrue(paused.wakeCrashLoop!!.pausedAtMemoryLimit)

        // An older server names no reason: still a pause, not a memory-limit one.
        val older = map(pausePayload(lastExitReason = null)).wakeCrashLoop
        assertEquals(AgentWakeCrashLoop(earlyExitCount = 3), older)
        assertEquals(false, older!!.pausedAtMemoryLimit)

        assertNull(map(pausePayload(detailKind = "stopped")).wakeCrashLoop)
    }

    @Test
    fun nowCardExplainsTheLivePauseElseTheLoadedProfilesWhileStillOffline() {
        val pause = AgentWakeCrashLoop(earlyExitCount = 3, lastExitReason = "memory_limit")
        val offline = raftAgentStatusIndicatorState(status = AgentStatus.Offline, presence = Presence.Offline, external = false)
        val live = offline.copy(detailKind = "wake_crash_loop_blocked", wakeCrashLoop = pause)
        assertEquals(pause, membersAgentActivityWakeCrashLoop(live, summaryDetailKind = null, summaryWakeCrashLoop = null))
        // The live status moved on (another kind): the profile's older pause is not shown.
        assertNull(membersAgentActivityWakeCrashLoop(offline.copy(detailKind = "stopped"), "wake_crash_loop_blocked", pause))
        // No live kind yet: the profile's pause while the agent is offline or unobserved, not once it runs.
        assertEquals(pause, membersAgentActivityWakeCrashLoop(offline, "wake_crash_loop_blocked", pause))
        assertEquals(pause, membersAgentActivityWakeCrashLoop(null, "wake_crash_loop_blocked", pause))
        val working = raftAgentStatusIndicatorState(status = AgentStatus.Working, presence = Presence.Active, external = false)
        assertNull(membersAgentActivityWakeCrashLoop(working, "wake_crash_loop_blocked", pause))
        assertNull(membersAgentActivityWakeCrashLoop(null, "stopped", pause))
    }
}
