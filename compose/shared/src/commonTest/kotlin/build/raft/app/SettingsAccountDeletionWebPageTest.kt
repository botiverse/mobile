package build.raft.app

import build.raft.app.data.ACCOUNT_DELETION_FEATURE_FLAG_KEY
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftFeatureFlagsApi
import build.raft.app.data.REGISTERED_SERVER_FEATURE_FLAG_KEYS
import build.raft.app.data.SharedApiEnvironmentStore
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.data.SharedSettingsIntent
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.model.ServerId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Settings > Account > Delete account opens the web deletion page (botiverse/slock #12459,
 * `/s/<slug>/settings/account/delete`) while `account_deletion_v0` is on; off, the Hands ticket.
 */
class SettingsAccountDeletionWebPageTest {
    private class FakeApi(private val body: String) : RaftFeatureFlagsApi {
        override suspend fun evaluate(serverId: ServerId, keys: List<String>): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = body)
    }

    private fun flags(enabled: Boolean) =
        SharedServerFeatureFlags(
            FakeApi("""{"evaluations":[{"key":"account_deletion_v0","enabled":$enabled,"reason":"user_rule"}]}""")
        )

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
    fun flagOffKeepsTheTicketEntryAndHidesItWithoutTicketSupport() {
        assertEquals(SettingsAccountDeletionEntry.Ticket, settingsAccountDeletionEntry(deletionFlagOn = false, ticketSupported = true))
        assertEquals(SettingsAccountDeletionEntry.Hidden, settingsAccountDeletionEntry(deletionFlagOn = false, ticketSupported = false))
    }

    @Test
    fun flagOnOpensTheWebPageOnEveryPlatform() {
        assertEquals(SettingsAccountDeletionEntry.WebPage, settingsAccountDeletionEntry(deletionFlagOn = true, ticketSupported = true))
        // The Compose desktop app (no ticket) shows the row too.
        assertEquals(SettingsAccountDeletionEntry.WebPage, settingsAccountDeletionEntry(deletionFlagOn = true, ticketSupported = false))
    }

    @Test
    fun flagGateIsTheServersAccountDeletionFlagResolvedOn() = runBlocking {
        assertTrue(ACCOUNT_DELETION_FEATURE_FLAG_KEY in REGISTERED_SERVER_FEATURE_FLAG_KEYS)
        val on = flags(enabled = true)
        assertFalse(settingsAccountDeletionFlagOn(on, "server-1"), "unresolved reads off")
        on.prefetch(ServerId("server-1"))
        assertTrue(settingsAccountDeletionFlagOn(on, "server-1"))
        assertFalse(settingsAccountDeletionFlagOn(on, null))
        assertFalse(settingsAccountDeletionFlagOn(on, " "))

        val off = flags(enabled = false)
        off.prefetch(ServerId("server-1"))
        assertFalse(settingsAccountDeletionFlagOn(off, "server-1"))
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
