package build.raft.app

import build.raft.app.model.AgentStatus
import build.raft.app.model.Presence
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlockAgentStatusIndicatorTest {
    @Test
    fun workingStatusUsesCanonicalWorkingIndicator() {
        val state =
            slockAgentStatusIndicatorState(
                status = AgentStatus.Working,
                presence = Presence.Offline
            )

        assertEquals(AgentStatus.Working, state.status)
        assertEquals(SlockColor.BrandYellow, state.color)
        assertTrue(state.pulsing)
    }

    @Test
    fun activePresenceWithoutStatusUsesOnlineColorButDoesNotPulse() {
        val state =
            slockAgentStatusIndicatorState(
                status = null,
                presence = Presence.Active
            )

        assertEquals(null, state.status)
        assertEquals(SlockColor.AccentLime, state.color)
        assertFalse(state.pulsing)
    }

    @Test
    fun missingServerScopeReturnsNeutralResolver() = runBlocking {
        assertEquals(null, slockAgentStatusResolver(null, null)("agent-1").first())
        assertEquals(null, slockScopedAgentStatusResolver(null, "")("agent-1").first())
    }

    @Test
    fun homeDmAndSearchConsumersKeepUnknownAbsentUntilAuthoritativeResolverValueExists() {
        assertEquals(null, projectHomeAgentStatusIndicatorState(true, "agent-1", null))

        val ready = slockAgentStatusIndicatorState(AgentStatus.Offline, Presence.Offline)
        assertEquals(ready, projectHomeAgentStatusIndicatorState(true, "agent-1", ready))
        assertEquals(null, projectHomeAgentStatusIndicatorState(false, "agent-1", ready))
        assertEquals(null, projectHomeAgentStatusIndicatorState(true, "", ready))
    }
}
