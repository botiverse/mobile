package build.raft.app

import build.raft.app.data.ServerPushMode
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.compat.json.JSONObject
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.model.ServerId
import build.raft.app.model.ServerNotificationPreferencesChanged
import build.raft.app.settings.SETTINGS_NATIVE_DETAIL_PAGE_KEY
import build.raft.app.settings.SettingsNativeDetailPage
import build.raft.app.settings.settingsNativeDetailPageData
import build.raft.app.settings.settingsNativeDetailPageKeys
import build.raft.app.settings.settingsNativeDetailRoute
import build.raft.app.settings.settingsServerNotificationRealtimeEventApplies
import build.raft.app.thread.key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsNativeDetailPageTest {
    private val storeScope =
        RaftShellStoreScope.create(
            shellInstanceId = "shell-settings-detail",
            initialServerId = "server-settings-detail",
        )

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
    fun notificationsPageTakesOnlyItsServersNotificationPreferenceChange() {
        val changed =
            ServerNotificationPreferencesChanged(ServerId("server-1"), ServerPushMode.None.wireName, prefsVersion = 2)

        assertTrue(settingsServerNotificationRealtimeEventApplies("server-1", changed, "server-1"))
        // Another server's change, a frame accepted for another socket, or any other event: not routed.
        assertFalse(settingsServerNotificationRealtimeEventApplies("server-2", changed, "server-1"))
        assertFalse(
            settingsServerNotificationRealtimeEventApplies(
                "server-1",
                changed.copy(serverId = ServerId("server-2")),
                "server-1",
            )
        )
        assertFalse(
            settingsServerNotificationRealtimeEventApplies(
                "server-1",
                RealtimeRefreshRequired(
                    scope = RealtimeRefreshScope.Machines,
                    reason = "machine:status",
                    serverId = ServerId("server-1"),
                ),
                "server-1",
            )
        )
    }
}
