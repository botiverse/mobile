package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class WebLoginConsentTest {
    @Test
    fun uncheckedOrBusyLoginNeverInvokesTheLaunchBoundary() {
        for ((accepted, starting) in listOf(false to false, false to true, true to true)) {
            var launches = 0
            startWebLoginWithConsent(accepted, starting) { launches++ }
            assertEquals(0, launches, "accepted=$accepted starting=$starting")
        }
    }

    @Test
    fun explicitConsentAllowsLoginAndUncheckingBlocksTheNextAttempt() {
        var launches = 0
        startWebLoginWithConsent(true, false) { launches++ }
        assertEquals(1, launches)
        startWebLoginWithConsent(false, false) { launches++ }
        assertEquals(1, launches)
    }
}
