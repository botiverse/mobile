package build.raft.app

import build.raft.app.model.Presence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlockAgentStatusIndicatorTest {
    @Test
    fun lowercaseWorkingFallbackUsesCanonicalWorkingIndicator() {
        val state =
            slockAgentStatusIndicatorState(
                statusValue = "working",
                fallbackPresence = Presence.Offline
            )

        assertEquals("Working", state.status?.label)
        assertEquals(SlockColor.BrandYellow, state.color)
        assertTrue(state.pulsing)
    }

    @Test
    fun activePresenceWithoutStatusUsesOnlineColorButDoesNotPulse() {
        val state =
            slockAgentStatusIndicatorState(
                statusValue = null,
                fallbackPresence = Presence.Active
            )

        assertEquals(null, state.status)
        assertEquals(SlockColor.AccentLime, state.color)
        assertFalse(state.pulsing)
    }
}
