package build.raft.app

import build.raft.app.compat.json.JSONObject
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.settings.AppShellNotificationAppTarget
import build.raft.app.settings.RaftSearchableSetting
import build.raft.app.settings.RaftSettingsSearchContext
import build.raft.app.settings.SETTINGS_WEB_VIEW_PAGE
import build.raft.app.settings.SettingsDestinationAppAuthorizations
import build.raft.app.settings.SettingsNativeDetailPage
import build.raft.app.settings.SettingsWebViewSurface
import build.raft.app.settings.SettingsWidePick
import build.raft.app.settings.SettingsWideWebDetailKeys
import build.raft.app.settings.appShellNotificationAppTarget
import build.raft.app.settings.raftSettingsSearchPlacements
import build.raft.app.settings.searchSettings
import build.raft.app.settings.settingsAppAuthorizationsWebViewSpec
import build.raft.app.settings.settingsAppAuthorizationsWebViewUrl
import build.raft.app.settings.settingsHomeRowKeys
import build.raft.app.settings.settingsSearchAliases
import build.raft.app.settings.settingsWebViewSurfaceFromPageData
import build.raft.app.settings.settingsWideDetailPage
import build.raft.app.settings.settingsWidePick
import build.raft.app.thread.key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Settings › App authorizations (botiverse/slock #16572 050f7d16a, #proj-mobile:#344) and
 * App permission update notification launch parsing (#proj-mobile:#350).
 */
class SettingsAppAuthorizationsTest {
    private fun session(slug: String? = "workspace") =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "user@example.test", displayName = "User"),
            accessToken = "access",
            refreshToken = "refresh",
            selectedServerId = "server-1",
            selectedServerSlug = slug,
            servers = listOf(StoredAuthServer(id = "server-1", name = "Workspace", slug = slug, role = "member")),
        )

    @Test
    fun webViewSpecLocksToWebOriginAllowsNavigationAndBuildsCanonicalUrl() {
        assertTrue(settingsAppAuthorizationsWebViewSpec.lockToWebOrigin)
        assertTrue(settingsAppAuthorizationsWebViewSpec.allowNavigation)
        assertEquals(SettingsWebViewSurface.AppAuthorizations, settingsAppAuthorizationsWebViewSpec.surface)
        assertEquals(
            "https://app.raft.build/s/workspace/settings/app-authorizations?embed=raft-settings-v1&shell=host&lang=zh-CN",
            settingsAppAuthorizationsWebViewUrl("https://app.raft.build/", session("workspace"), localeTag = "zh-CN"),
        )
        assertEquals(
            "https://app.raft.build/settings/app-authorizations?embed=raft-settings-v1&shell=host&lang=en",
            settingsAppAuthorizationsWebViewUrl("https://app.raft.build/", session(null), localeTag = "en"),
        )
    }

    @Test
    fun rowLivesInOnServerGroupForEveryMemberIncludingGuests() {
        for (serverPanelPages in listOf(false, true)) {
            for (guest in listOf(false, true)) {
                val rows = settingsHomeRowKeys(hasThemeServer = true, serverPanelPages = serverPanelPages, guest = guest)
                val serverNotificationsIndex = rows.indexOf(SettingsNativeDetailPage.ServerNotifications.key)
                val appAuthIndex = rows.indexOf(SettingsDestinationAppAuthorizations)
                val serverProfileIndex = rows.indexOf(SettingsNativeDetailPage.Server.key)
                assertTrue(appAuthIndex > 0, "serverPanelPages=$serverPanelPages guest=$guest")
                assertEquals(serverNotificationsIndex + 1, appAuthIndex)
                assertEquals(appAuthIndex + 1, serverProfileIndex)
            }
        }
        assertFalse(SettingsDestinationAppAuthorizations in settingsHomeRowKeys(hasThemeServer = false))
    }

    @Test
    fun wideSplitOpensAppAuthorizationsInTheDetailPane() {
        assertTrue(SettingsDestinationAppAuthorizations in SettingsWideWebDetailKeys)
        assertEquals(SettingsWidePick.Detail(SettingsDestinationAppAuthorizations), settingsWidePick(SettingsDestinationAppAuthorizations))
        val page = settingsWideDetailPage(SettingsDestinationAppAuthorizations, RaftShellStoreScope.create("shell-authz"))
        assertEquals(SETTINGS_WEB_VIEW_PAGE, page?.first)
        assertEquals(SettingsWebViewSurface.AppAuthorizations, page?.second?.let(::settingsWebViewSurfaceFromPageData))
    }

    @Test
    fun settingsSearchFindsAppAuthorizationsForMembersAndGuests() {
        for (role in listOf("member", "guest", "admin", "owner")) {
            val rows = settingsHomeRowKeys(hasThemeServer = true, serverPanelPages = true, guest = role == "guest")
            val context = RaftSettingsSearchContext(rowKeys = rows, role = role)
            val placements = raftSettingsSearchPlacements(context)
            val entry = placements.firstOrNull { it.entry.id == "app-authorizations" }
            assertEquals(SettingsDestinationAppAuthorizations, entry?.rowKey, role)
            val searchable =
                listOf(
                    RaftSearchableSetting(
                        id = entry!!.entry.id,
                        rowKey = entry.rowKey,
                        webPage = entry.entry.webPage,
                        name = "App authorizations",
                        description = "Connected accounts and your link previews for Apps on this server",
                        pageLabel = "App authorizations",
                        aliases = settingsSearchAliases(entry.entry.aliases, "en"),
                    ),
                )
            assertEquals(1, searchSettings(searchable, "authorized accounts").size, role)
            assertEquals(1, searchSettings(searchable, "link previews").size, role)
        }
    }

    @Test
    fun appNotificationTargetParsesServerAndAppId() {
        val valid =
            JSONObject()
                .put("slockNotificationTarget", "app")
                .put("slockNotificationServerId", "srv-1")
                .put("slockNotificationAppId", "app-42")
        assertEquals(AppShellNotificationAppTarget("srv-1", "app-42"), valid.appShellNotificationAppTarget())

        // Missing appId, missing serverId, or non-app target: rejected cleanly.
        assertNull(
            JSONObject()
                .put("slockNotificationTarget", "app")
                .put("slockNotificationServerId", "srv-1")
                .appShellNotificationAppTarget(),
        )
        assertNull(
            JSONObject()
                .put("slockNotificationTarget", "activity")
                .put("slockNotificationServerId", "srv-1")
                .put("slockNotificationAppId", "app-42")
                .appShellNotificationAppTarget(),
        )
    }
}
