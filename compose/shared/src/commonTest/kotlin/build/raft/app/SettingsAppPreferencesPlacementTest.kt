package build.raft.app

import build.raft.app.data.LinkOpenMode
import build.raft.app.settings.SettingsAppPreferenceActions
import build.raft.app.settings.SettingsNativeDetailPage
import build.raft.app.settings.raftComposerEnterKeyLabel
import build.raft.app.settings.raftComposerModEnterKeyLabel
import build.raft.app.settings.settingsAppPreferenceVisibility
import build.raft.app.settings.settingsNativeDetailPageKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Settings > Appearance (web parity 1.29.0): there is no "Mobile" Settings page on any platform;
 * app rows share Appearance's single group, and the Keyboard section ("Send messages with": Enter /
 * ⌘Enter or Ctrl+Enter, plus Keyboard shortcuts) shows on Compose desktop, iPad app on Mac, and
 * mobile devices with a hardware keyboard connected — while touch-only mobile without a hardware
 * keyboard hides the Keyboard send-key setting.
 */
class SettingsAppPreferencesPlacementTest {
    @Test
    fun anOldMobileRouteOpensAppearance() {
        assertEquals(SettingsNativeDetailPage.Appearance, SettingsNativeDetailPage.fromKey("mobile"))
        assertFalse("mobile" in settingsNativeDetailPageKeys)
    }

    @Test
    fun desktopHidesLinkModeAndShowsKeyboardSendKeyAndShortcuts() {
        val desktop =
            settingsAppPreferenceVisibility(
                finePointer = true,
                supportsInAppBrowser = false,
                supportsMultiShellHost = false,
                supportsIosNativeNav = false,
                largeScreenPossible = true,
                hardwareKeyboardConnected = true,
                isiOSAppOnMac = false,
            )
        assertFalse(desktop.linkOpenMode)
        assertFalse(desktop.multiShellHost)
        assertTrue(desktop.keyboardSendKey)
        assertTrue(desktop.keyboardShortcuts)
        assertTrue(desktop.keyboardSection)
        assertTrue(desktop.desktopMode)
    }

    @Test
    fun touchOnlyPhoneHidesKeyboardSectionAndSendKey() {
        val phone =
            settingsAppPreferenceVisibility(
                finePointer = false,
                supportsInAppBrowser = true,
                supportsMultiShellHost = true,
                supportsIosNativeNav = false,
                largeScreenPossible = false,
                hardwareKeyboardConnected = false,
                isiOSAppOnMac = false,
            )
        assertFalse(phone.linkOpenMode)
        assertFalse(phone.keyboardSendKey)
        assertFalse(phone.keyboardShortcuts)
        assertFalse(phone.keyboardSection)
    }

    @Test
    fun phoneWithHardwareKeyboardShowsKeyboardSectionAndSendKey() {
        val phoneWithKeyboard =
            settingsAppPreferenceVisibility(
                finePointer = false,
                supportsInAppBrowser = true,
                supportsMultiShellHost = true,
                supportsIosNativeNav = false,
                largeScreenPossible = false,
                hardwareKeyboardConnected = true,
                isiOSAppOnMac = false,
            )
        assertTrue(phoneWithKeyboard.keyboardSendKey)
        assertTrue(phoneWithKeyboard.keyboardShortcuts)
        assertTrue(phoneWithKeyboard.keyboardSection)
    }

    @Test
    fun tabletWithoutHardwareKeyboardHidesSendKeyButShowsKeyboardShortcuts() {
        val tablet =
            settingsAppPreferenceVisibility(
                finePointer = false,
                supportsInAppBrowser = true,
                supportsMultiShellHost = true,
                supportsIosNativeNav = false,
                largeScreenPossible = true,
                hardwareKeyboardConnected = false,
                isiOSAppOnMac = false,
            )
        assertFalse(tablet.keyboardSendKey)
        assertTrue(tablet.keyboardShortcuts)
        assertTrue(tablet.keyboardSection)
    }

    @Test
    fun ipadAppOnMacShowsKeyboardSectionAndSendKey() {
        val ipadOnMac =
            settingsAppPreferenceVisibility(
                finePointer = false,
                supportsInAppBrowser = true,
                supportsMultiShellHost = false,
                supportsIosNativeNav = false,
                largeScreenPossible = true,
                hardwareKeyboardConnected = false,
                isiOSAppOnMac = true,
            )
        assertTrue(ipadOnMac.keyboardSendKey)
        assertTrue(ipadOnMac.keyboardShortcuts)
        assertTrue(ipadOnMac.keyboardSection)
    }

    @Test
    fun keyLabelsMatchWebPlatformConventions() {
        assertEquals("Enter", raftComposerEnterKeyLabel())
        assertEquals("⌘Enter", raftComposerModEnterKeyLabel(apple = true))
        assertEquals("Ctrl+Enter", raftComposerModEnterKeyLabel(apple = false))
    }

    @Test
    fun theRowsReportFromAppearance() {
        assertEquals(build.raft.app.data.RaftAppSettingSurface.SettingsAppearance, SettingsAppPreferenceActions().surface)
    }

    @Test
    fun externalWebLinksAlwaysIgnoreLegacyLinkOpenModePreference() {
        assertEquals(LinkOpenMode.SystemBrowser, effectiveLinkOpenMode(LinkOpenMode.InAppBrowser, supportsInAppBrowser = false))
        assertEquals(LinkOpenMode.SystemBrowser, effectiveLinkOpenMode(LinkOpenMode.InAppBrowser, supportsInAppBrowser = true))
        assertEquals(LinkOpenMode.SystemBrowser, effectiveLinkOpenMode(LinkOpenMode.SystemBrowser, supportsInAppBrowser = true))
    }
}
