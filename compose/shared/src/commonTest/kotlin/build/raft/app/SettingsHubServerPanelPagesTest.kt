package build.raft.app

import build.raft.app.settings.SettingsDestinationAppAuthorizations
import build.raft.app.settings.SettingsDestinationComputers
import build.raft.app.settings.SettingsDestinationConnectedApps
import build.raft.app.settings.SettingsDestinationStorage
import build.raft.app.settings.settingsHomeRowKeys
import build.raft.app.shell.RaftAppDestination
import build.raft.app.shell.RaftServerMenuEntry
import build.raft.app.shell.raftServerMenuEntriesFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * artin 2026-11-05 (dm task #17) + botiverse/slock #16572 + #17260: Settings › Applications is
 * removed on both phone and wide (Apps lives on its own tab/rail destination `/s/<slug>/apps`),
 * and a member's connected accounts & link previews live in Settings › App authorizations ("On
 * {server}" group, visible to every member including guests). Computers is on the server panel on
 * phone and stays in Workspace settings on wide.
 */
class SettingsHubServerPanelPagesTest {
    @Test
    fun phoneAndWideHaveNoApplicationsRowAndKeepAppAuthorizationsForEveryone() {
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

        val wideMember = settingsHomeRowKeys(hasThemeServer = true, serverPanelPages = false)
        assertTrue(SettingsDestinationComputers in wideMember)
        assertFalse(SettingsDestinationConnectedApps in wideMember)
        assertTrue(SettingsDestinationAppAuthorizations in wideMember)

        val wideGuest = settingsHomeRowKeys(hasThemeServer = true, serverPanelPages = false, guest = true)
        assertFalse(SettingsDestinationConnectedApps in wideGuest)
        assertTrue(SettingsDestinationAppAuthorizations in wideGuest)
    }

    @Test
    fun thePhoneServerPanelOpensComputersAndApps() {
        val phone = raftServerMenuEntriesFor(RaftServerMenuEntry.entries, wide = false, guest = false)
        assertTrue(RaftServerMenuEntry.Computers in phone)
        assertTrue(RaftServerMenuEntry.Apps in phone)
        assertEquals(RaftServerMenuEntry.Apps, RaftServerMenuEntry.fromKey(RaftAppDestination.Apps.id))
    }
}
