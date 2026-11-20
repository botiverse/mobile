package build.raft.app.assistant

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Where the Assistant entry lives: the Home row without the wide rail, the rail's tab with it. */
class HomeAssistantRowLayoutTest {
    @Test
    fun homeCarriesTheRowWheneverTheRailIsNotOnScreen() {
        // A phone: no rail, whatever "Desktop mode on large screens" says (it is on by default).
        assertTrue(appShellHomeCarriesAssistantRow(navRailActive = false))
        assertFalse(appShellHomeCarriesAssistantRow(navRailActive = true))
    }
}
