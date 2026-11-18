package build.raft.app

import build.raft.app.data.LinkOpenMode
import build.raft.app.data.appSettingValue
import build.raft.app.resources.Res
import build.raft.app.resources.settings_mobile_composer_key_detail
import build.raft.app.resources.settings_mobile_composer_key_detail_hardware_keyboard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Settings cleanup (1.27.0): there is no "Mobile" Settings page on any platform. Its rows are part
 * of Appearance (Messages, Home, Windows), as the web keeps its device preferences in
 * AppearanceSection. Desktop hides "Open web links" (no embedded web view) and links open in the
 * default browser there; the Windows group shows the desktop layout switch on every device (a large phone held sideways reaches it), multiple windows only where a large screen is possible.
 */
class SettingsAppPreferencesPlacementTest {
    @Test
    fun noPlatformHasAMobileRow() {
        listOf(false, true).forEach { serverPanelPages ->
            val rows = settingsHomeRowKeys(hasThemeServer = true, serverPanelPages = serverPanelPages)
            assertFalse("mobile" in rows, rows.toString())
            assertTrue(SettingsNativeDetailPage.Appearance.key in rows)
            assertEquals(rows.indexOf(SettingsNativeDetailPage.Notifications.key) + 1, rows.indexOf(SettingsDestinationStorage))
        }
    }

    @Test
    fun anOldMobileRouteOpensAppearance() {
        assertEquals(SettingsNativeDetailPage.Appearance, SettingsNativeDetailPage.fromKey("mobile"))
        assertFalse("mobile" in settingsNativeDetailPageKeys)
    }

    @Test
    fun desktopHidesLinkModeAndUsesEnterCopy() {
        val desktop =
            settingsAppPreferenceVisibility(
                finePointer = true,
                supportsInAppBrowser = false,
                supportsMultiShellHost = false,
                supportsIosNativeNav = false,
                largeScreenPossible = true,
            )
        assertFalse(desktop.linkOpenMode)
        assertFalse(desktop.multiShellHost)
        assertTrue(desktop.hardwareKeyboardComposerCopy)
        assertTrue(desktop.desktopMode)
        assertEquals(Res.string.settings_mobile_composer_key_detail_hardware_keyboard, settingsComposerKeyDetail(true))
    }

    @Test
    fun phoneKeepsLinkModeAndActionKeyCopyAndTheDesktopLayoutSwitch() {
        val phone =
            settingsAppPreferenceVisibility(
                finePointer = false,
                supportsInAppBrowser = true,
                supportsMultiShellHost = true,
                supportsIosNativeNav = false,
                largeScreenPossible = false,
            )
        assertTrue(phone.linkOpenMode)
        assertFalse(phone.hardwareKeyboardComposerCopy)
        // A large phone held sideways reaches the desktop layout: the switch shows so it can be turned off.
        assertTrue(phone.desktopMode)
        assertFalse(phone.multiShellHostRow(enabled = false))
        assertTrue(phone.windowsGroup(multiShellHostEnabled = false))
        // Turned on before 1.27.0: it still shows, so it can be turned off.
        assertTrue(phone.multiShellHostRow(enabled = true))
        assertTrue(phone.windowsGroup(multiShellHostEnabled = true))
        assertEquals(Res.string.settings_mobile_composer_key_detail, settingsComposerKeyDetail(false))
    }

    @Test
    fun androidTabletShowsTheWindowsGroup() {
        val tablet =
            settingsAppPreferenceVisibility(
                finePointer = false,
                supportsInAppBrowser = true,
                supportsMultiShellHost = true,
                supportsIosNativeNav = false,
                largeScreenPossible = true,
            )
        assertTrue(tablet.desktopMode)
        assertTrue(tablet.multiShellHostRow(enabled = false))
        assertTrue(tablet.windowsGroup(multiShellHostEnabled = false))
    }

    @Test
    fun iPadShowsDesktopLayoutButNoMultipleWindows() {
        val iPad =
            settingsAppPreferenceVisibility(
                finePointer = false,
                supportsInAppBrowser = true,
                supportsMultiShellHost = false,
                supportsIosNativeNav = false,
                largeScreenPossible = true,
            )
        assertTrue(iPad.desktopMode)
        assertFalse(iPad.multiShellHostRow(enabled = true))
    }

    @Test
    fun theRowsReportFromAppearance() {
        assertEquals(build.raft.app.data.RaftAppSettingSurface.SettingsAppearance, SettingsAppPreferenceActions().surface)
    }

    @Test
    fun removedSettingsAreNoLongerReported() {
        val snapshot =
            build.raft.app.data.SettingsMobileSnapshot(
                pushNotifications = build.raft.app.data.SettingsMobileToggleSnapshot(false, false, "", ""),
                muteServer = build.raft.app.data.SettingsMobileToggleSnapshot(false, false, "", ""),
            )
        assertNull(snapshot.appSettingValue(build.raft.app.data.RaftAppSettingKey.ActivityTaskStatusBadges))
        assertNull(snapshot.appSettingValue(build.raft.app.data.RaftAppSettingKey.HomeServerAvatar))
    }

    @Test
    fun withoutAnInAppBrowserLinksOpenInTheDefaultBrowser() {
        assertEquals(LinkOpenMode.SystemBrowser, effectiveLinkOpenMode(LinkOpenMode.InAppBrowser, supportsInAppBrowser = false))
        assertEquals(LinkOpenMode.InAppBrowser, effectiveLinkOpenMode(LinkOpenMode.InAppBrowser, supportsInAppBrowser = true))
        assertEquals(LinkOpenMode.SystemBrowser, effectiveLinkOpenMode(LinkOpenMode.SystemBrowser, supportsInAppBrowser = true))
    }
}
