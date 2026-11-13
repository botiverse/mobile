package build.raft.app

import build.raft.app.data.SharedApiEnvironmentStore
import build.raft.app.data.SharedSettingsIntent
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Settings > Account > Delete account opens the web deletion page (botiverse/slock #12459,
 * `/s/<slug>/settings/account/delete`).
 */
class SettingsAccountDeletionWebPageTest {
    private fun session(slug: String?) =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "user@example.test", displayName = "User"),
            accessToken = "access",
            refreshToken = "refresh",
            selectedServerId = "server-1",
            selectedServerSlug = slug,
            servers = listOf(StoredAuthServer(id = "server-1", name = "Workspace", slug = slug)),
        )

    @Test
    fun deletionUrlIsTheEmbeddedWebPageOnProductionAndStaging() {
        assertEquals(
            "https://app.raft.build/s/workspace/settings/account/delete?embed=raft-settings-v1&shell=host&lang=en",
            settingsAccountDeletionWebViewUrl(SharedApiEnvironmentStore.Production.webUrl, session("workspace"), "en"),
        )
        assertEquals(
            "https://raft-app-staging.botiverse.dev/s/workspace/settings/account/delete?embed=raft-settings-v1&shell=host&lang=zh-Hans",
            settingsAccountDeletionWebViewUrl(SharedApiEnvironmentStore.Staging.webUrl, session("workspace"), "zh-Hans"),
        )
    }

    @Test
    fun deletionUrlFallsBackWithoutServerSlug() {
        assertEquals(
            "https://app.raft.build/settings/account/delete?embed=raft-settings-v1&shell=host&lang=system",
            settingsAccountDeletionWebViewUrl("https://app.raft.build/", null, "system"),
        )
    }

    @Test
    fun deletionPageReceivesHostEvents() {
        assertTrue(settingsAccountDeletionWebViewSpec.hostEventsEnabled)
        assertTrue(settingsAccountDeletionWebViewSpec.allowNavigation)
        assertEquals(SettingsWebViewSurface.AccountDeletion, settingsAccountDeletionWebViewSpec.surface)
    }

    @Test
    fun sessionLogoutHostEventIsTheSettingsLogoutIntent() {
        // The web emits it through window.RaftHost as {kind, payload: {}}; the shared parser keeps it.
        val decoded = raftDecodeWebViewHostMessage("""{"kind":"session:logout","payload":{}}""")
        assertEquals("session:logout", decoded?.first)
        assertEquals(SharedSettingsIntent.Logout, settingsAccountDeletionHostIntent(decoded!!.first))
    }

    @Test
    fun otherHostEventsAskForNothing() {
        assertNull(settingsAccountDeletionHostIntent("session:server-left"))
        assertNull(settingsAccountDeletionHostIntent("close"))
        // A kind the shared parser does not know never reaches the page.
        assertNull(raftDecodeWebViewHostMessage("""{"kind":"session:token-refresh-failed","payload":{}}"""))
        // The payload must be an object.
        assertNull(raftDecodeWebViewHostMessage("""{"kind":"session:logout","payload":null}"""))
    }
}
