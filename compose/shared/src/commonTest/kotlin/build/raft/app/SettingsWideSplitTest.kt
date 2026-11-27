package build.raft.app

import build.raft.app.data.RaftShellStoreScope
import build.raft.app.thread.key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** artin 2026-10-29: the wide Settings list + detail (SettingsWideSplit.kt). */
class SettingsWideSplitTest {
    private val rows = settingsHomeRowKeys(hasThemeServer = true)

    @Test
    fun aPickedPaneSectionShowsAndIsTheHighlightedRow() {
        val appearance = SettingsNativeDetailPage.Appearance.key
        assertEquals(SettingsWidePick.Detail(appearance), settingsWidePick(appearance))
        assertEquals(appearance, settingsWideDetailKey(appearance, rows))
        assertEquals(SettingsDestinationComputers, settingsWideDetailKey(SettingsDestinationComputers, rows))
    }

    @Test
    fun feedbackIsAlwaysPushedAsTheNativeTicketList() {
        // Settings › Feedback is the native ticket list on every platform (desktop included): the
        // row never opens in the pane and has no pane page.
        assertEquals(SettingsWidePick.Push, settingsWidePick(SettingsDestinationHandsFeedback))
        assertEquals(SettingsDestinationAccount, settingsWideDetailKey(SettingsDestinationHandsFeedback, rows))
        assertFalse(SettingsDestinationHandsFeedback in SettingsWideDetailKeys)
        assertNull(settingsWideDetailPage(SettingsDestinationHandsFeedback, RaftShellStoreScope.create("shell-wide-feedback")))
    }

    @Test
    fun releaseNotesRowOnlyWhereThePlatformHasAHandsApp() {
        assertTrue(settingsReleaseNotesAvailable("raft-android"))
        assertFalse(settingsReleaseNotesAvailable(""))
        assertFalse(SettingsDestinationReleaseNotes in settingsHomeRowKeys(hasThemeServer = true, releaseNotes = false))
        assertEquals(
            settingsHomeRowKeys(hasThemeServer = true, releaseNotes = true) - SettingsDestinationReleaseNotes,
            settingsHomeRowKeys(hasThemeServer = true, releaseNotes = false),
        )
    }

    @Test
    fun aWebSectionInThePaneHasNoBackOfItsOwnAndClosesToTheDefault() {
        assertTrue(settingsWebViewHandlesBack(wideDetail = false, canGoBack = false))
        assertFalse(settingsWebViewHandlesBack(wideDetail = true, canGoBack = false))
        assertTrue(settingsWebViewHandlesBack(wideDetail = true, canGoBack = true))
        val calls = mutableListOf<String>()
        settingsPageClose(wideDetailClose = { calls += "pane" }) { calls += "router" }
        settingsPageClose(wideDetailClose = null) { calls += "router" }
        assertEquals(listOf("pane", "router"), calls)
    }

    @Test
    fun aSectionThatIsNoLongerARowFallsBackToTheDefault() {
        val noTheme = settingsHomeRowKeys(hasThemeServer = false)
        assertEquals(SettingsDestinationAccount, settingsWideDetailKey(SettingsNativeDetailPage.Theme.key, noTheme))
        assertNull(settingsWideDetailKey(null, emptyList()))
    }

    @Test
    fun backReturnsToTheDefaultSectionOnlyFromAnother() {
        assertFalse(settingsWideBackReturnsToDefault(null, rows))
        assertFalse(settingsWideBackReturnsToDefault(SettingsDestinationAccount, rows))
        assertTrue(settingsWideBackReturnsToDefault(SettingsNativeDetailPage.Notifications.key, rows))
        assertTrue(settingsWideBackReturnsToDefault(SettingsDestinationStorage, rows))
    }

    @Test
    fun everyPaneSectionHasItsChildPage() {
        val scope = RaftShellStoreScope.create("shell-wide-settings")
        assertEquals(SETTINGS_ACCOUNT_PAGE, settingsWideDetailPage(SettingsDestinationAccount, scope)?.first)
        assertEquals(SETTINGS_STORAGE_PAGE, settingsWideDetailPage(SettingsDestinationStorage, scope)?.first)
        assertEquals(SETTINGS_COMPUTERS_PAGE, settingsWideDetailPage(SettingsDestinationComputers, scope)?.first)
        val notifications = settingsWideDetailPage(SettingsNativeDetailPage.Notifications.key, scope)
        assertEquals(SETTINGS_NATIVE_DETAIL_PAGE, notifications?.first)
        assertEquals("notifications", notifications?.second?.optString(SETTINGS_NATIVE_DETAIL_PAGE_KEY))
        SettingsWideDetailKeys.forEach { key -> assertTrue(settingsWideDetailPage(key, scope) != null, key) }
        assertNull(settingsWideDetailPage(SettingsNativeDetailPage.Developer.key, scope))
    }
}
