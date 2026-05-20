package ai.slock.compose

import kotlin.test.Test
import kotlin.test.assertEquals

class SlockHomeComponentsTest {
    @Test
    fun homeDmStatusFallsBackToOfflineWhenPresenceIsMissing() {
        assertEquals(SlockPresence.Offline, homeDmStatusPresence(null))
    }

    @Test
    fun homeDmStatusKeepsKnownPresence() {
        assertEquals(SlockPresence.Online, homeDmStatusPresence(SlockPresence.Online))
        assertEquals(SlockPresence.Unknown, homeDmStatusPresence(SlockPresence.Unknown))
        assertEquals(SlockPresence.Offline, homeDmStatusPresence(SlockPresence.Offline))
    }
}
