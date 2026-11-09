package build.raft.app

import build.raft.app.model.AgentSpawnFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Web classifyRuntimeError.ts `runtimeLookupDisplayForReason` and AgentDetailPanel `startLookupDisplay`. */
class AgentRuntimeLookupErrorTest {
    private val notInstalled = "Codex CLI is not installed on this computer."

    @Test
    fun lookupReasonsMapLikeTheWeb() {
        mapOf(
            "not_installed" to AgentRuntimeLookupDisplay.ConfirmedMissing,
            "not_on_path" to AgentRuntimeLookupDisplay.ConfirmedMissing,
            "probe_timeout" to AgentRuntimeLookupDisplay.Timeout,
            "lookup_timed_out" to AgentRuntimeLookupDisplay.Timeout,
            "permission_denied" to AgentRuntimeLookupDisplay.PermissionDenied,
            "lookup_permission_denied" to AgentRuntimeLookupDisplay.PermissionDenied,
            "probe_failed" to AgentRuntimeLookupDisplay.Unconfirmed,
            "lookup_failed" to AgentRuntimeLookupDisplay.Unconfirmed,
            "some_future_reason" to AgentRuntimeLookupDisplay.Unconfirmed,
        ).forEach { (reason, expected) -> assertEquals(expected, agentRuntimeLookupDisplayForReason(reason), reason) }
        assertEquals(AgentRuntimeLookupDisplay.Unconfirmed, agentRuntimeLookupDisplayForReason(null))
    }

    @Test
    fun onlyAConfirmedMissNamesTheMissingRuntime() {
        assertEquals(
            AgentRuntimeLookupDisplay.ConfirmedMissing,
            agentStartLookupDisplay(notInstalled, AgentSpawnFailure("runtime_not_found", "not_on_path"))
        )
        assertEquals(
            AgentRuntimeLookupDisplay.Timeout,
            agentStartLookupDisplay(notInstalled, AgentSpawnFailure("runtime_not_found", "lookup_timed_out"))
        )
        // No verdict (older daemon), no start failure, or another start failure: unresolved, never "install it".
        assertEquals(AgentRuntimeLookupDisplay.Unconfirmed, agentStartLookupDisplay(notInstalled, AgentSpawnFailure("runtime_not_found")))
        assertEquals(AgentRuntimeLookupDisplay.Unconfirmed, agentStartLookupDisplay(notInstalled, null))
        assertEquals(
            AgentRuntimeLookupDisplay.Unconfirmed,
            agentStartLookupDisplay(notInstalled, AgentSpawnFailure("model_not_found", "not_on_path"))
        )
    }

    @Test
    fun otherErrorsKeepTheirOwnText() {
        val confirmed = AgentSpawnFailure("runtime_not_found", "not_on_path")
        assertNull(agentStartLookupDisplay("Claude Code is not logged in on this machine. Please log in locally.", confirmed))
        assertNull(agentStartLookupDisplay("The daemon lost its connection to the workspace broker.", confirmed))
        assertNull(agentStartLookupDisplay("  ", confirmed))
        assertNull(agentStartLookupDisplay(null, confirmed))
    }
}
