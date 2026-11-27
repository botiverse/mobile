package build.raft.app

import build.raft.app.data.StoredAuthServer
import build.raft.app.thread.key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Plan & Billing and Administration show only for who may open them, as on the web
 * (settingsNavigation.ts `canOpenSettingsTab`: `viewBilling`; `changeMemberRoles ||
 * changeChannelVisibility || inviteMembers || editServerSettings`, all owner/admin).
 */
class SettingsWorkspaceRowsAccessTest {
    private fun rows(role: String?) =
        settingsHomeRowKeys(
            hasThemeServer = true,
            serverPanelPages = true,
            guest = role == "guest",
            billing = settingsHomeBillingVisible(role),
            administration = settingsHomeAdministrationVisible(role),
        )

    @Test
    fun ownersAndAdminsSeeBillingAndAdministration() {
        for (role in listOf("owner", "admin", "Owner")) {
            val keys = rows(role)
            assertTrue(SettingsDestinationBilling in keys, role)
            assertTrue(SettingsDestinationAdministration in keys, role)
            // Still in the web's order: Server Profile, Plan & Billing, Administration.
            val server = keys.indexOf(SettingsNativeDetailPage.Server.key)
            assertEquals(server + 1, keys.indexOf(SettingsDestinationBilling))
            assertEquals(server + 2, keys.indexOf(SettingsDestinationAdministration))
        }
    }

    @Test
    fun membersGuestsAndNoServerSeeNeither() {
        for (role in listOf("member", "guest", null, "")) {
            val keys = rows(role)
            assertFalse(SettingsDestinationBilling in keys, "$role")
            assertFalse(SettingsDestinationAdministration in keys, "$role")
            // Server Profile stays for everyone.
            assertTrue(SettingsNativeDetailPage.Server.key in keys, "$role")
        }
    }

    private val servers =
        listOf(
            StoredAuthServer(id = "srv-hq", name = "HQ", slug = "hq", role = "member"),
            StoredAuthServer(id = "srv-lab", name = "Lab", slug = "lab", role = "owner"),
        )

    private fun target(url: String, currentServerId: String = "srv-hq") =
        raftSettingsLinkTarget(
            assertNotNullLink(url),
            currentServerId = currentServerId,
            servers = servers,
        )

    private fun assertNotNullLink(url: String) =
        assertNotNull(parseRaftSettingsLink(url, appHosts = emptySet()))

    @Test
    fun aLinkToATabTheRoleCannotOpenOpensAccountAsOnTheWeb() {
        // Web SettingsPanel: `!canOpenSettingsTab(tab)` falls back to "account".
        assertEquals(SettingsDestinationAccount, target("https://app.raft.build/settings/billing")?.destinationKey)
        assertEquals(SettingsDestinationAccount, target("https://app.raft.build/s/hq/settings/administration")?.destinationKey)
        assertEquals(SettingsDestinationAccount, target("https://app.raft.build/s/hq/settings/moderation")?.destinationKey)
        // Other tabs are untouched.
        assertEquals(SettingsNativeDetailPage.Theme.key, target("https://app.raft.build/settings/theme")?.destinationKey)
    }

    @Test
    fun aLinkOpensTheTabForWhoMayOpenItOnTheServerItNames() {
        // The owner's server, named by the link: switch to it and open Plan & Billing there.
        val lab = target("https://app.raft.build/s/lab/settings/billing")
        assertEquals(RaftSettingsLinkTarget("srv-lab", SettingsDestinationBilling), lab)
        // The current server is the owner's one.
        assertEquals(
            SettingsDestinationAdministration,
            target("https://app.raft.build/settings/administration", currentServerId = "srv-lab")?.destinationKey,
        )
    }
}
