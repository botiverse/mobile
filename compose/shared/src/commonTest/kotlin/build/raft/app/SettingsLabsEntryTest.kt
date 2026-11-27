package build.raft.app

import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftFeatureFlagsApi
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.model.ServerId
import build.raft.app.settings.SettingsDestinationAdministration
import build.raft.app.settings.SettingsDestinationImBridges
import build.raft.app.settings.SettingsDestinationLabs
import build.raft.app.settings.SettingsNativeDetailPage
import build.raft.app.settings.settingsHomeRowKeys
import build.raft.app.settings.settingsLabsEnabled
import build.raft.app.thread.key
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Settings > Labs entry: the web's `labs` item (botiverse/slock staging aa270b415d44
 * packages/web/src/features/sidebar/hooks/useSidebarRoute.tsx), last in the Server group and only
 * while the server's `server_labs_ui_v0` is on.
 */
class SettingsLabsEntryTest {
    private class FakeApi(private val body: String) : RaftFeatureFlagsApi {
        override suspend fun evaluate(serverId: ServerId, keys: List<String>): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = body)
    }

    private fun flags(enabled: Boolean) =
        SharedServerFeatureFlags(FakeApi("""{"evaluations":[{"key":"server_labs_ui_v0","enabled":$enabled,"reason":"server_rule"}]}"""))

    @Test
    fun flagOnPutsLabsLastInTheWorkspaceGroupLikeTheWeb() {
        for (serverPanelPages in listOf(false, true)) {
            val rows = settingsHomeRowKeys(hasThemeServer = true, imBridges = true, labs = true, serverPanelPages = serverPanelPages)
            val at = rows.indexOf(SettingsDestinationLabs)
            assertTrue(at > rows.indexOf(SettingsDestinationAdministration), "$serverPanelPages")
            assertTrue(at > rows.indexOf(SettingsDestinationImBridges), "$serverPanelPages")
            assertEquals(SettingsNativeDetailPage.About.key, rows[at + 1], "$serverPanelPages")
            // Nothing else moves.
            assertEquals(
                settingsHomeRowKeys(hasThemeServer = true, imBridges = true, serverPanelPages = serverPanelPages),
                rows - SettingsDestinationLabs,
            )
        }
    }

    @Test
    fun gateIsTheServersLabsUiFlagResolvedOn() = runTest {
        val on = flags(enabled = true)
        // Unresolved: off.
        assertFalse(settingsLabsEnabled(on, "s1"))
        on.prefetch(ServerId("s1"))
        assertTrue(settingsLabsEnabled(on, "s1"))
        assertFalse(settingsLabsEnabled(on, "s2"))
        assertFalse(settingsLabsEnabled(on, null))
        assertFalse(settingsLabsEnabled(on, " "))

        val off = flags(enabled = false)
        off.prefetch(ServerId("s1"))
        assertFalse(settingsLabsEnabled(off, "s1"))
    }
}
