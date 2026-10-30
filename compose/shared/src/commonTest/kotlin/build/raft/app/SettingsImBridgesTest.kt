package build.raft.app

import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftFeatureFlagsApi
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.model.ServerId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Settings > IM Bridges: the web section `/settings/im-bridges` (botiverse/slock staging 82ec87f2
 * IMBridgesSettingsSection.tsx), shown only while the server's `slack_bridge_v0` is on.
 */
class SettingsImBridgesTest {
    private class FakeApi(private val body: String) : RaftFeatureFlagsApi {
        override suspend fun evaluate(serverId: ServerId, keys: List<String>): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = body)
    }

    private fun flags(enabled: Boolean) =
        SharedServerFeatureFlags(
            FakeApi("""{"evaluations":[{"key":"slack_bridge_v0","enabled":$enabled,"reason":"server_rule"}]}""")
        )

    @Test
    fun rowIsHiddenWhileTheFlagIsOff() {
        val rows = settingsHomeRowKeys(hasThemeServer = true)
        assertFalse(SettingsDestinationImBridges in rows)
        assertFalse(SettingsDestinationImBridges in settingsHomeRowKeys(hasThemeServer = true, imBridges = false))
    }

    @Test
    fun rowFollowsAdministrationLikeTheWebNavWhenTheFlagIsOn() {
        val rows = settingsHomeRowKeys(hasThemeServer = true, imBridges = true)
        val at = rows.indexOf(SettingsDestinationImBridges)
        assertTrue(at > 0)
        assertEquals(SettingsDestinationAdministration, rows[at - 1])
        assertEquals(SettingsDestinationConnectedApps, rows[at + 1])
        // Nothing else moves.
        assertEquals(settingsHomeRowKeys(hasThemeServer = true), rows - SettingsDestinationImBridges)
    }

    @Test
    fun gateIsTheServersSlackBridgeFlagResolvedOn() = runBlocking {
        val on = flags(enabled = true)
        // Unresolved: off (web `gate.resolved && gate.enabled`).
        assertFalse(settingsImBridgesEnabled(on, "s1"))
        on.prefetch(ServerId("s1"))
        assertTrue(settingsImBridgesEnabled(on, "s1"))
        // Per server, and never without one.
        assertFalse(settingsImBridgesEnabled(on, "s2"))
        assertFalse(settingsImBridgesEnabled(on, null))
        assertFalse(settingsImBridgesEnabled(on, " "))

        val off = flags(enabled = false)
        off.prefetch(ServerId("s1"))
        assertFalse(settingsImBridgesEnabled(off, "s1"))
    }

    @Test
    fun opensTheWebSectionInTheHostShellEmbed() {
        val session =
            StoredAuthSession(
                user = StoredAuthUser(id = "user-1", email = "user@example.test", displayName = "User"),
                accessToken = "access",
                refreshToken = "refresh",
                selectedServerId = "server-1",
                selectedServerSlug = "workspace",
                servers = listOf(StoredAuthServer(id = "server-1", name = "Workspace", slug = "workspace")),
            )
        assertEquals(
            "https://app.raft.build/s/workspace/settings/im-bridges?embed=raft-settings-v1&shell=host&lang=zh-Hans",
            settingsImBridgesWebViewUrl("https://app.raft.build", session, "zh-Hans"),
        )
        assertEquals(
            "https://app.raft.build/settings/im-bridges?embed=raft-settings-v1&shell=host&lang=system",
            settingsImBridgesWebViewUrl("https://app.raft.build", null, "system"),
        )
        assertEquals(SettingsWebViewSurface.ImBridges, settingsImBridgesWebViewSpec.surface)
        assertTrue(settingsImBridgesWebViewSpec.includeLocaleQuery)
    }

    @Test
    fun phoneTapPushesTheSettingsWebPageForTheSection() {
        val scope = build.raft.app.data.RaftShellStoreScope.create("shell-im-bridges")
        val data = settingsWebViewPageData(SettingsWebViewSurface.ImBridges, scope)
        assertEquals(SETTINGS_WEB_VIEW_PAGE, data.optString("pageName"))
        assertEquals(SettingsWebViewSurface.ImBridges, settingsWebViewSurfaceFromPageData(data))
    }

    @Test
    fun wideLayoutOpensItInTheDetailPane() {
        val rows = settingsHomeRowKeys(hasThemeServer = true, imBridges = true)
        assertEquals(SettingsWidePick.Detail(SettingsDestinationImBridges), settingsWidePick(SettingsDestinationImBridges))
        assertEquals(SettingsDestinationImBridges, settingsWideDetailKey(SettingsDestinationImBridges, rows))
        val page = settingsWideDetailPage(SettingsDestinationImBridges, build.raft.app.data.RaftShellStoreScope.create("shell-wide"))
        assertEquals(SETTINGS_WEB_VIEW_PAGE, page?.first)
        assertEquals(SettingsWebViewSurface.ImBridges, page?.second?.let(::settingsWebViewSurfaceFromPageData))
        // Flag off: no row, so a stale pick falls back to the default section.
        assertEquals(
            SettingsDestinationAccount,
            settingsWideDetailKey(SettingsDestinationImBridges, settingsHomeRowKeys(hasThemeServer = true)),
        )
    }
}
