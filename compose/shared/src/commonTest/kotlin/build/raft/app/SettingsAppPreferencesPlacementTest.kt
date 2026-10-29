package build.raft.app

import build.raft.app.data.LinkOpenMode
import build.raft.app.resources.Res
import build.raft.app.resources.settings_mobile_composer_key_detail
import build.raft.app.resources.settings_mobile_composer_key_detail_hardware_keyboard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * artin 2026-10-29: the desktop app (a fine pointer) shows the Mobile page's cards under Appearance,
 * as the web does, with no Mobile row; phones keep Mobile. Desktop hides "Open web links" (no
 * embedded web view) and links open in the default browser there.
 */
class SettingsAppPreferencesPlacementTest {
    @Test
    fun desktopHasNoMobileRowAndKeepsAppearance() {
        val desktop = settingsHomeRowKeys(hasThemeServer = true, appPreferencesUnderAppearance = true)
        assertFalse(SettingsNativeDetailPage.Mobile.key in desktop)
        assertTrue(SettingsNativeDetailPage.Appearance.key in desktop)
        assertEquals(
            settingsHomeRowKeys(hasThemeServer = true, appPreferencesUnderAppearance = false) - SettingsNativeDetailPage.Mobile.key,
            desktop,
        )
    }

    @Test
    fun phoneKeepsMobileAfterNotifications() {
        val phone = settingsHomeRowKeys(hasThemeServer = false, appPreferencesUnderAppearance = false)
        assertEquals(
            phone.indexOf(SettingsNativeDetailPage.Notifications.key) + 1,
            phone.indexOf(SettingsNativeDetailPage.Mobile.key),
        )
    }

    @Test
    fun placementFollowsTheFinePointer() {
        assertTrue(settingsAppPreferencesUnderAppearance(finePointer = true))
        assertFalse(settingsAppPreferencesUnderAppearance(finePointer = false))
    }

    @Test
    fun desktopHidesLinkModeAndUsesEnterCopy() {
        val desktop = settingsAppPreferenceVisibility(finePointer = true, supportsInAppBrowser = false, supportsMultiShellHost = false)
        assertFalse(desktop.linkOpenMode)
        assertFalse(desktop.multiShellHost)
        assertTrue(desktop.hardwareKeyboardComposerCopy)
        assertEquals(Res.string.settings_mobile_composer_key_detail_hardware_keyboard, settingsComposerKeyDetail(true))
    }

    @Test
    fun phoneKeepsLinkModeAndActionKeyCopy() {
        val phone = settingsAppPreferenceVisibility(finePointer = false, supportsInAppBrowser = true, supportsMultiShellHost = true)
        assertTrue(phone.linkOpenMode)
        assertTrue(phone.multiShellHost)
        assertFalse(phone.hardwareKeyboardComposerCopy)
        assertEquals(Res.string.settings_mobile_composer_key_detail, settingsComposerKeyDetail(false))
    }

    @Test
    fun withoutAnInAppBrowserLinksOpenInTheDefaultBrowser() {
        assertEquals(LinkOpenMode.SystemBrowser, effectiveLinkOpenMode(LinkOpenMode.InAppBrowser, supportsInAppBrowser = false))
        assertEquals(LinkOpenMode.InAppBrowser, effectiveLinkOpenMode(LinkOpenMode.InAppBrowser, supportsInAppBrowser = true))
        assertEquals(LinkOpenMode.SystemBrowser, effectiveLinkOpenMode(LinkOpenMode.SystemBrowser, supportsInAppBrowser = true))
    }
}
