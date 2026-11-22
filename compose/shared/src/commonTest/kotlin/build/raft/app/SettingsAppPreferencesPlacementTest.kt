package build.raft.app

import build.raft.app.data.LinkOpenMode
import build.raft.app.resources.Res
import build.raft.app.resources.settings_mobile_composer_key_detail_hardware_keyboard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Settings cleanup (1.27.0): there is no "Mobile" Settings page on any platform. Its rows are part
 * of Appearance (Messages, Home, Windows), as the web keeps its device preferences in
 * AppearanceSection. Desktop hides "Open web links" (no embedded web view) and links open in the
 * default browser there; the Windows group shows the desktop layout switch on every device (a large phone held sideways reaches it), multiple windows only where a large screen is possible.
 */
class SettingsAppPreferencesPlacementTest {
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
    fun theRowsReportFromAppearance() {
        assertEquals(build.raft.app.data.RaftAppSettingSurface.SettingsAppearance, SettingsAppPreferenceActions().surface)
    }

    @Test
    fun withoutAnInAppBrowserLinksOpenInTheDefaultBrowser() {
        assertEquals(LinkOpenMode.SystemBrowser, effectiveLinkOpenMode(LinkOpenMode.InAppBrowser, supportsInAppBrowser = false))
        assertEquals(LinkOpenMode.InAppBrowser, effectiveLinkOpenMode(LinkOpenMode.InAppBrowser, supportsInAppBrowser = true))
        assertEquals(LinkOpenMode.SystemBrowser, effectiveLinkOpenMode(LinkOpenMode.SystemBrowser, supportsInAppBrowser = true))
    }
}
