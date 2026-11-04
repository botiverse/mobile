package build.raft.app.data.realtime

import build.raft.app.model.AgentSpawnFailure
import build.raft.app.model.MemberPresenceUpdated
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * `agent:activity` for a failed start (botiverse/slock agentOrchestrator: the daemon's
 * `runtime_unavailable` frame, served offline with the shared SpawnFailureActivityDiagnostic).
 */
class RealtimeAgentSpawnFailureTest {
    private fun failedStart(detailKind: String = "runtime_unavailable", lookupReason: String? = "not_on_path"): String {
        val lookup = if (lookupReason == null) "" else ", \"lookupReason\": \"$lookupReason\""
        return """
        {
          "agentId": "agent-1",
          "activity": "offline",
          "activityKind": "offline",
          "detail": "Codex CLI is not installed on this computer.",
          "detailKind": "$detailKind",
          "timestamp": 1782367200000,
          "serverSeq": 52,
          "spawnFailure": { "reason": "runtime_not_found", "autoRestartPaused": true $lookup }
        }
        """.trimIndent()
    }

    private fun map(payload: String): MemberPresenceUpdated =
        assertIs<MemberPresenceUpdated>(RealtimeEventMapper().map(RealtimeSocketEvents.AgentActivity, payload))

    @Test
    fun failedStartCarriesItsReasonAndLookupOnlyWithItsKind() {
        val failed = map(failedStart())
        assertEquals("runtime_unavailable", failed.detailKind)
        assertEquals(AgentSpawnFailure(reason = "runtime_not_found", lookupReason = "not_on_path"), failed.spawnFailure)

        // An older daemon: no lookup verdict, still the failed start.
        assertEquals(AgentSpawnFailure(reason = "runtime_not_found"), map(failedStart(lookupReason = null)).spawnFailure)

        assertNull(map(failedStart(detailKind = "stopped")).spawnFailure)
    }
}
