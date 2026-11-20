package build.raft.app

import build.raft.app.data.AgentDetailSummary
import build.raft.app.model.AgentStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/** Web `runtimeSignInNeededOf` (slock #15282): the runtime is not signed in on the agent's Computer. */
class AgentRuntimeSignInNeededTest {
    private fun needed(
        status: AgentStatus?,
        live: String? = null,
        kept: String? = null,
        blocked: Boolean = false,
        likelyOnline: Boolean = false,
        external: Boolean = false,
        paused: Boolean = false,
    ) = agentRuntimeSignInNeeded(status, live, kept, blocked, likelyOnline, external, paused)

    @Test
    fun aFailedStartOrTheKeptRecordSaysSoWhileOfflineOrInError() {
        assertEquals(true, needed(AgentStatus.Offline, live = "runtime_login_required"))
        assertEquals(true, needed(AgentStatus.Error, kept = "runtime_login_required"))
        assertEquals(false, needed(AgentStatus.Offline, live = "runtime_not_found"))
        assertEquals(false, needed(AgentStatus.Offline))
        // Running again: no longer.
        assertEquals(false, needed(AgentStatus.Online, kept = "runtime_login_required"))
        assertEquals(false, needed(null, kept = "runtime_login_required"))
    }

    @Test
    fun neverForAHeldPausedExternalOrGuessedOnlineAgent() {
        assertEquals(false, needed(AgentStatus.Offline, live = "runtime_login_required", blocked = true))
        assertEquals(false, needed(AgentStatus.Offline, live = "runtime_login_required", paused = true))
        assertEquals(false, needed(AgentStatus.Offline, live = "runtime_login_required", external = true))
        assertEquals(false, needed(AgentStatus.Offline, live = "runtime_login_required", likelyOnline = true))
    }

    @Test
    fun theProfileReadsTheLoadedStatusAndKeptReasonWithoutALiveIndicator() {
        val summary = AgentDetailSummary(id = "a1", name = "cat", activity = "offline", lastRuntimeErrorSpawnFailureReason = "runtime_login_required")
        assertEquals(true, membersAgentRuntimeSignInNeeded(indicator = null, summary = summary, external = false, blocked = null, liveSpawnFailureReason = null))
        assertEquals(false, membersAgentRuntimeSignInNeeded(indicator = null, summary = null, external = false, blocked = null, liveSpawnFailureReason = null))
    }
}
