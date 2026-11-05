package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * artin 2026-11-05 (dm task #17): the phone Settings lists the web phone's rows. Computers and
 * Apps are pages on the server panel; Applications is a row only for a guest. The wide list keeps
 * both rows, next to the rail's Computers and Apps.
 */
class SettingsHubServerPanelPagesTest {
    @Test
    fun phoneHasNoComputersRowAndApplicationsOnlyForAGuest() {
        val member = settingsHomeRowKeys(hasThemeServer = true, appPreferencesUnderAppearance = false, serverPanelPages = true)
        assertFalse(SettingsDestinationComputers in member)
        assertFalse(SettingsDestinationConnectedApps in member)
        // The app's own rows stay: Mobile and Storage.
        assertTrue(SettingsNativeDetailPage.Mobile.key in member)
        assertTrue(SettingsDestinationStorage in member)

        val guest =
            settingsHomeRowKeys(hasThemeServer = true, appPreferencesUnderAppearance = false, serverPanelPages = true, guest = true)
        assertFalse(SettingsDestinationComputers in guest)
        assertEquals(guest.indexOf(SettingsDestinationAdministration) + 1, guest.indexOf(SettingsDestinationConnectedApps))
        assertEquals(member, guest - SettingsDestinationConnectedApps)
    }

    @Test
    fun wideKeepsComputersAndApplications() {
        listOf(false, true).forEach { guest ->
            val wide = settingsHomeRowKeys(hasThemeServer = true, guest = guest)
            assertTrue(SettingsDestinationComputers in wide)
            assertTrue(SettingsDestinationConnectedApps in wide)
        }
    }

    @Test
    fun thePhoneServerPanelOpensComputersAndApps() {
        val phone = raftServerMenuEntriesFor(RaftServerMenuEntry.entries, wide = false, guest = false)
        assertTrue(RaftServerMenuEntry.Computers in phone)
        assertTrue(RaftServerMenuEntry.Apps in phone)
        assertEquals(RaftServerMenuEntry.Apps, RaftServerMenuEntry.fromKey(RaftAppDestination.Apps.id))
    }
}
