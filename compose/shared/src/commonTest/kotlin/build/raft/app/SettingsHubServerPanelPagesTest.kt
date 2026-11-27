package build.raft.app

import build.raft.app.settings.SettingsDestinationAppAuthorizations
import build.raft.app.settings.SettingsDestinationComputers
import build.raft.app.settings.SettingsDestinationConnectedApps
import build.raft.app.settings.SettingsDestinationStorage
import build.raft.app.settings.settingsHomeRowKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * artin 2026-11-05 (dm task #17) + botiverse/slock #16572: the phone Settings lists the web
 * phone's rows. Computers and Apps are pages on the server panel, and a guest's own link previews
 * live in Settings › App authorizations ("On {server}" group, visible to every member including
 * guests). The wide list keeps Computers and Apps for non-guests beside the rail.
 */
class SettingsHubServerPanelPagesTest {
    @Test
    fun phoneHasNoComputersOrApplicationsRowAndAppAuthorizationsForEveryone() {
        val member = settingsHomeRowKeys(hasThemeServer = true, serverPanelPages = true)
        assertFalse(SettingsDestinationComputers in member)
        assertFalse(SettingsDestinationConnectedApps in member)
        assertTrue(SettingsDestinationAppAuthorizations in member)
        // The app's own row stays: Storage (the former Mobile page is part of Appearance).
        assertTrue(SettingsDestinationStorage in member)

        val guest =
            settingsHomeRowKeys(hasThemeServer = true, serverPanelPages = true, guest = true)
        assertFalse(SettingsDestinationComputers in guest)
        assertFalse(SettingsDestinationConnectedApps in guest)
        assertTrue(SettingsDestinationAppAuthorizations in guest)
        assertEquals(member, guest)
    }

    @Test
    fun thePhoneServerPanelOpensComputersAndApps() {
        val phone = raftServerMenuEntriesFor(RaftServerMenuEntry.entries, wide = false, guest = false)
        assertTrue(RaftServerMenuEntry.Computers in phone)
        assertTrue(RaftServerMenuEntry.Apps in phone)
        assertEquals(RaftServerMenuEntry.Apps, RaftServerMenuEntry.fromKey(RaftAppDestination.Apps.id))
    }
}
