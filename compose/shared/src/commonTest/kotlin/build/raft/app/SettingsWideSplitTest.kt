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
    fun webBackedRowsOpenInThePaneLikeNativeOnes() {
        listOf(
            SettingsDestinationBilling,
            SettingsDestinationAdministration,
            SettingsDestinationImBridges,
            SettingsDestinationConnectedApps,
            SettingsDestinationReleaseNotes,
        ).forEach { key ->
            val rows = settingsHomeRowKeys(hasThemeServer = true, imBridges = true)
            assertEquals(SettingsWidePick.Detail(key), settingsWidePick(key), key)
            assertEquals(key, settingsWideDetailKey(key, rows), key)
            assertTrue(settingsWideBackReturnsToDefault(key, rows), key)
        }
    }

    @Test
    fun feedbackOpensInThePaneOnlyAsTheWebPage() {
        assertEquals(SettingsWidePick.Push, settingsWidePick(SettingsDestinationHandsFeedback, feedbackWeb = false))
        assertEquals(SettingsDestinationAccount, settingsWideDetailKey(SettingsDestinationHandsFeedback, rows, feedbackWeb = false))
        assertEquals(
            SettingsWidePick.Detail(SettingsDestinationHandsFeedback),
            settingsWidePick(SettingsDestinationHandsFeedback, feedbackWeb = true),
        )
        assertEquals(SettingsDestinationHandsFeedback, settingsWideDetailKey(SettingsDestinationHandsFeedback, rows, feedbackWeb = true))
    }

    @Test
    fun webSectionsInThePaneAreTheSettingsWebPage() {
        val scope = RaftShellStoreScope.create("shell-wide-settings")
        mapOf(
            SettingsDestinationBilling to SettingsWebViewSurface.Billing,
            SettingsDestinationAdministration to SettingsWebViewSurface.Administration,
            SettingsDestinationImBridges to SettingsWebViewSurface.ImBridges,
            SettingsDestinationConnectedApps to SettingsWebViewSurface.Marketplace,
            SettingsDestinationReleaseNotes to SettingsWebViewSurface.ReleaseNotes,
        ).forEach { (key, surface) ->
            val page = settingsWideDetailPage(key, scope)
            assertEquals(SETTINGS_WEB_VIEW_PAGE, page?.first, key)
            assertEquals(surface, page?.second?.let(::settingsWebViewSurfaceFromPageData), key)
        }
        assertNull(settingsWideDetailPage(SettingsDestinationHandsFeedback, scope, feedbackWeb = false))
        assertEquals(
            SettingsWebViewSurface.Feedback,
            settingsWideDetailPage(SettingsDestinationHandsFeedback, scope, feedbackWeb = true)?.second?.let(::settingsWebViewSurfaceFromPageData),
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
    fun everySettingsWebSurfaceAsksTheWebForTheHostShell() {
        // The native shell (phone header or wide pane) owns the title bar and navigation; the web
        // must lay out one section, never its own Settings nav (slock packages/web/src/embed.ts).
        listOf(
            settingsBillingWebViewUrl("https://raft.example", session = null),
            settingsAdministrationWebViewUrl("https://raft.example", session = null),
            settingsMarketplaceWebViewUrl("https://raft.example", session = null),
            settingsImBridgesWebViewUrl("https://raft.example", session = null),
        ).forEach { url ->
            assertTrue("embed=$SharedSettingsEmbedContract" in url, url)
            assertTrue("shell=$SharedSettingsEmbedHostShell" in url, url)
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

    @Test
    fun phoneHeadKeepsBackWideDetailHasNone() {
        val back: () -> Unit = {}
        assertSame(back, settingsDetailHeaderBack(back, wideDetail = false))
        assertNull(settingsDetailHeaderBack(back, wideDetail = true))
    }
}
