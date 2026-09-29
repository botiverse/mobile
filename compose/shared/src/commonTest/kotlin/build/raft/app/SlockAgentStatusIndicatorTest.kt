package build.raft.app

import build.raft.app.model.AgentStatus
import build.raft.app.model.Presence
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import build.raft.app.data.SharedServerMemberStatus

class SlockAgentStatusIndicatorTest {
    @Test
    fun workingStatusUsesCanonicalWorkingIndicator() {
        val state =
            slockAgentStatusIndicatorState(
                status = AgentStatus.Working,
                presence = Presence.Offline,
                external = false
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
                presence = Presence.Active,
                external = false
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

        val ready = slockAgentStatusIndicatorState(AgentStatus.Offline, Presence.Offline, external = false)
        assertEquals(ready, projectHomeAgentStatusIndicatorState(true, "agent-1", ready))
        assertEquals(null, projectHomeAgentStatusIndicatorState(false, "agent-1", ready))
        assertEquals(null, projectHomeAgentStatusIndicatorState(true, "", ready))
    }

    // task #1119: external agents have no managed liveness. React StatusDot renders
    // `external` unconditionally as the cyan token; the mobile mapping must do the same
    // for every managed status, and color / pulse / label must move together.
    @Test
    fun externalAgentRendersExternalTokenForEveryManagedStatus() {
        val inputs: List<AgentStatus?> = AgentStatus.values().toList() + null
        inputs.forEach { status ->
            val state =
                slockAgentStatusIndicatorState(
                    status = status,
                    presence = Presence.Active,
                    external = true
                )
            assertEquals(status, state.status, "status is preserved for $status")
            assertEquals(SlockColor.AccentCyan, state.color, "external color for $status")
            assertFalse(state.pulsing, "external never pulses ($status)")
            assertEquals(ExternalAgentStatusLabel, state.label, "external label for $status")
            assertTrue(state.external)
        }
    }

    @Test
    fun externalOverridesPresenceFallbackAndFreeTextStatusLabel() {
        // Without the flag this input is the presence-only green path with a custom caption.
        val managed =
            slockAgentStatusIndicatorState(
                status = null,
                presence = Presence.Active,
                statusLabel = "Running tests",
                external = false
            )
        assertEquals(SlockColor.AccentLime, managed.color)
        assertEquals("Running tests", managed.label)
        assertFalse(managed.external)

        val external =
            slockAgentStatusIndicatorState(
                status = null,
                presence = Presence.Active,
                statusLabel = "Running tests",
                external = true
            )
        assertEquals(SlockColor.AccentCyan, external.color)
        assertEquals(ExternalAgentStatusLabel, external.label)
        assertTrue(external.external)
    }

    @Test
    fun resolvedMemberStatusCarriesExternalIntoIndicatorState() {
        val live =
            SharedServerMemberStatus(
                status = AgentStatus.Working,
                presence = Presence.Active,
                statusLabel = "Working",
                external = true
            ).toSlockAgentStatusIndicatorState()
        assertEquals(SlockColor.AccentCyan, live.color)
        assertFalse(live.pulsing)
        assertEquals(ExternalAgentStatusLabel, live.label)

        val managed =
            SharedServerMemberStatus(
                status = AgentStatus.Working,
                presence = Presence.Active,
                statusLabel = "Working",
                external = false
            ).toSlockAgentStatusIndicatorState()
        assertEquals(SlockColor.BrandYellow, managed.color)
        assertTrue(managed.pulsing)
        assertEquals("Working", managed.label)
    }
}
