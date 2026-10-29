package build.raft.app

import build.raft.app.data.RaftShellStoreScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** artin 2026-10-29: the wide Settings list + detail (SettingsWideSplit.kt). */
class SettingsWideSplitTest {
    private val rows = settingsHomeRowKeys(hasThemeServer = true)

    @Test
    fun nothingPickedShowsAccountLikeTheWebDefault() {
        assertEquals(SettingsDestinationAccount, settingsWideDetailKey(null, rows))
    }

    @Test
    fun aPickedPaneSectionShowsAndIsTheHighlightedRow() {
        val appearance = SettingsNativeDetailPage.Appearance.key
        assertEquals(SettingsWidePick.Detail(appearance), settingsWidePick(appearance))
        assertEquals(appearance, settingsWideDetailKey(appearance, rows))
        assertEquals(SettingsDestinationComputers, settingsWideDetailKey(SettingsDestinationComputers, rows))
    }

    @Test
    fun webBackedRowsStillOpenAsAPageAndKeepTheSelection() {
        listOf(
            SettingsDestinationBilling,
            SettingsDestinationAdministration,
            SettingsDestinationConnectedApps,
            SettingsDestinationHandsFeedback,
            SettingsDestinationReleaseNotes,
        ).forEach { key ->
            assertEquals(SettingsWidePick.Push, settingsWidePick(key), key)
            // Never a selection: the pane falls back to the default if one leaked in.
            assertEquals(SettingsDestinationAccount, settingsWideDetailKey(key, rows), key)
        }
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
        // A pushed (web-backed) row never became the selection: Back stays the shell's.
        assertFalse(settingsWideBackReturnsToDefault(SettingsDestinationBilling, rows))
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
        assertNull(settingsWideDetailPage(SettingsDestinationBilling, scope))
    }

    @Test
    fun phoneHeadKeepsBackWideDetailHasNone() {
        val back: () -> Unit = {}
        assertSame(back, settingsDetailHeaderBack(back, wideDetail = false))
        assertNull(settingsDetailHeaderBack(back, wideDetail = true))
    }
}
