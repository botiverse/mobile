package build.raft.app

import build.raft.app.data.SharedSettingsDestination
import build.raft.app.data.SharedSettingsPageContentOwner
import build.raft.app.data.SlockShellStoreScope
import build.raft.app.compat.json.JSONObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsNativeDetailPageTest {
    private val storeScope =
        SlockShellStoreScope.create(
            shellInstanceId = "shell-settings-detail",
            initialServerId = "server-settings-detail",
        )

    @Test
    fun existingAccountAndStoragePagesReceiveOnlyShellScope() {
        listOf(SETTINGS_ACCOUNT_PAGE, SETTINGS_STORAGE_PAGE).forEach { pageName ->
            val pageData = settingsStandalonePageData(pageName, storeScope)
            assertEquals(pageName, pageData.optString("pageName"))
            assertEquals("shell-settings-detail", pageData.optString(SLOCK_SHELL_INSTANCE_ID))
            assertEquals("server-settings-detail", pageData.optString(SLOCK_SHELL_SERVER_ID))
            assertFalse(pageData.has("payload"))
            assertFalse(pageData.has("session"))
            assertFalse(pageData.has("state"))
        }
    }

    @Test
    fun everyRemainingNativeDetailHasAnAuthenticatedStoreRoute() {
        val expected = SettingsNativeDetailPage.entries.associateWith { page -> page.storeDestination }

        assertEquals(expected.keys.mapTo(linkedSetOf()) { page -> page.key }, settingsNativeDetailPageKeys)
        expected.forEach { (page, storeDestination) ->
            val pageData =
                assertNotNull(
                    settingsNativeDetailPageData(
                        page = page,
                        itemId = "flag_id".takeIf {
                            page == SettingsNativeDetailPage.LocalFeatureOverride
                        },
                        storeScope = storeScope,
                    )
                )
            val route = assertNotNull(settingsNativeDetailRoute(pageData))
            assertEquals(page, route.page)
            assertEquals(storeDestination, route.page.storeDestination)
        }
    }

    @Test
    fun pageDataCarriesOnlyDestinationIdentityIntentAndShellScope() {
        val pageData =
            assertNotNull(
                settingsNativeDetailPageData(
                    page = SettingsNativeDetailPage.Diagnostics,
                    storeScope = storeScope,
                    launchIntent =
                        SettingsNativeDetailLaunchIntent(
                            autoCloseMs = 2_500L,
                        ),
                )
            )

        assertEquals(SETTINGS_NATIVE_DETAIL_PAGE, pageData.optString("pageName"))
        assertEquals("diagnostics", pageData.optString(SETTINGS_NATIVE_DETAIL_PAGE_KEY))
        assertEquals("shell-settings-detail", pageData.optString(SLOCK_SHELL_INSTANCE_ID))
        assertEquals("server-settings-detail", pageData.optString(SLOCK_SHELL_SERVER_ID))
        assertEquals(2_500L, pageData.optLong(SETTINGS_NATIVE_DETAIL_AUTO_CLOSE_MS, 0L))
        listOf("session", "account", "profile", "state", "snapshot", "payload", "callback")
            .forEach { forbidden -> assertFalse(pageData.has(forbidden), forbidden) }
    }

    @Test
    fun localFeatureOverrideRequiresAndPreservesOnlyFlagIdentity() {
        assertNull(
            settingsNativeDetailPageData(
                page = SettingsNativeDetailPage.LocalFeatureOverride,
                storeScope = storeScope,
            )
        )
        val route =
            settingsNativeDetailRoute(
                assertNotNull(
                    settingsNativeDetailPageData(
                        page = SettingsNativeDetailPage.LocalFeatureOverride,
                        itemId = "mobile_new_design_settings",
                        storeScope = storeScope,
                    )
                )
            )

        assertNotNull(route)
        assertEquals(SettingsNativeDetailPage.LocalFeatureOverride, route.page)
        assertEquals("mobile_new_design_settings", route.itemId)
        assertEquals(SharedSettingsDestination.Diagnostics, route.page.storeDestination)
    }

    @Test
    fun nonOverrideRoutesDropUnrelatedItemIdentity() {
        val pageData =
            assertNotNull(
                settingsNativeDetailPageData(
                    page = SettingsNativeDetailPage.Mobile,
                    itemId = "must-not-cross-page-boundary",
                    storeScope = storeScope,
                )
            )

        assertFalse(pageData.has(SETTINGS_NATIVE_DETAIL_ITEM_ID))
        assertNull(settingsNativeDetailRoute(pageData)?.itemId)
    }

    @Test
    fun unsupportedOrIncompleteRoutesFailClosed() {
        assertNull(SettingsNativeDetailPage.fromKey("home"))
        assertNull(SettingsNativeDetailPage.fromKey("account"))
        assertNull(settingsNativeDetailRoute(JSONObject().put(SETTINGS_NATIVE_DETAIL_PAGE_KEY, "unknown")))
        assertNull(
            settingsNativeDetailRoute(
                JSONObject().put(SETTINGS_NATIVE_DETAIL_PAGE_KEY, "local-feature-override")
            )
        )
        assertNull(
            settingsNativeDetailPageData(
                page = SettingsNativeDetailPage.LocalFeatureOverride,
                itemId = "../../token",
                storeScope = storeScope,
            )
        )
    }

    @Test
    fun nonDiagnosticsRouteDropsDiagnosticsLaunchIntent() {
        val pageData =
            assertNotNull(
                settingsNativeDetailPageData(
                    page = SettingsNativeDetailPage.Mobile,
                    storeScope = storeScope,
                    launchIntent =
                        SettingsNativeDetailLaunchIntent(
                            autoCloseMs = 2_500L,
                        ),
                )
            )

        assertFalse(pageData.has(SETTINGS_NATIVE_DETAIL_AUTO_CLOSE_MS))
    }

    @Test
    fun oneViewModelCannotBeReboundToReplaceItsPagerPage() {
        val owner = SharedSettingsPageContentOwner()

        assertTrue(owner.admits(SharedSettingsDestination.Mobile))
        assertTrue(owner.admits(SharedSettingsDestination.Mobile))
        assertFalse(owner.admits(SharedSettingsDestination.Diagnostics))
    }

    @Test
    fun diagnosticsAutoCloseWaitsForLoad() = runBlocking {
        val route =
            assertNotNull(
                SettingsNativeDetailRoute.create(
                    page = SettingsNativeDetailPage.Diagnostics,
                    launchIntent =
                        SettingsNativeDetailLaunchIntent(
                            autoCloseMs = 2_500L,
                        ),
                )
            )
        val loadStarted = CompletableDeferred<Unit>()
        val releaseLoad = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()

        val job =
            launch {
                runSettingsNativeDetailLaunchSequence(
                    route = route,
                    loadPageContent = {
                        events += "load-start"
                        loadStarted.complete(Unit)
                        releaseLoad.await()
                        events += "load-complete"
                    },
                    waitForAutoClose = { events += "wait:$it" },
                    closePage = { events += "close" },
                )
            }

        loadStarted.await()
        assertEquals(listOf("load-start"), events)
        releaseLoad.complete(Unit)
        job.join()

        assertEquals(
            listOf("load-start", "load-complete", "wait:2500", "close"),
            events,
        )
    }
}
