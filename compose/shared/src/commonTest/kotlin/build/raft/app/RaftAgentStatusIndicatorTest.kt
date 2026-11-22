package build.raft.app

import build.raft.app.model.AgentStatus
import build.raft.app.model.Presence
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RaftAgentStatusIndicatorTest {
    @Test
    fun workingStatusUsesCanonicalWorkingIndicator() {
        val state =
            raftAgentStatusIndicatorState(
                status = AgentStatus.Working,
                presence = Presence.Offline,
                external = false
            )

        assertEquals(AgentStatus.Working, state.status)
        assertEquals(RaftColor.BrandYellow, state.color)
    }

    @Test
    fun missingServerScopeReturnsNeutralResolver() = runBlocking {
        assertEquals(null, raftAgentStatusResolver(null, null)("agent-1").first())
        assertEquals(null, raftScopedAgentStatusResolver(null, "")("agent-1").first())
    }

    @Test
    fun homeDmAndSearchConsumersKeepUnknownAbsentUntilAuthoritativeResolverValueExists() {
        assertEquals(null, projectHomeAgentStatusIndicatorState(true, "agent-1", null))

        val ready = raftAgentStatusIndicatorState(AgentStatus.Offline, Presence.Offline, external = false)
        assertEquals(ready, projectHomeAgentStatusIndicatorState(true, "agent-1", ready))
        assertEquals(null, projectHomeAgentStatusIndicatorState(false, "agent-1", ready))
        assertEquals(null, projectHomeAgentStatusIndicatorState(true, "", ready))
    }

    // task #1119: external agents have no managed liveness. React StatusDot renders
    // `external` unconditionally as the cyan token; the mobile mapping must do the same
    // for every managed status, and color / label must move together.
    @Test
    fun externalAgentRendersExternalTokenForEveryManagedStatus() {
        val inputs: List<AgentStatus?> = AgentStatus.values().toList() + null
        inputs.forEach { status ->
            val state =
                raftAgentStatusIndicatorState(
                    status = status,
                    presence = Presence.Active,
                    external = true
                )
            assertEquals(status, state.status, "status is preserved for $status")
            assertEquals(RaftColor.AccentCyan, state.color, "external color for $status")
            assertEquals(ExternalAgentStatusLabel, state.label, "external label for $status")
            assertTrue(state.external)
        }
    }
}
