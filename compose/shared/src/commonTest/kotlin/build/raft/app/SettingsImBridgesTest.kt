package build.raft.app

import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftFeatureFlagsApi
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.model.ServerId
import build.raft.app.settings.SettingsDestinationAdministration
import build.raft.app.settings.SettingsDestinationComputers
import build.raft.app.settings.SettingsDestinationImBridges
import build.raft.app.settings.settingsHomeRowKeys
import build.raft.app.settings.settingsImBridgesEnabled
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
    fun rowFollowsAdministrationLikeTheWebNavWhenTheFlagIsOn() {
        val rows = settingsHomeRowKeys(hasThemeServer = true, imBridges = true)
        val at = rows.indexOf(SettingsDestinationImBridges)
        assertTrue(at > 0)
        assertEquals(SettingsDestinationAdministration, rows[at - 1])
        assertEquals(SettingsDestinationComputers, rows[at + 1])
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
}
