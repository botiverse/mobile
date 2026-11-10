package build.raft.app

import build.raft.app.components.RaftProGateCopy
import build.raft.app.components.raftProMigrationGateCopy
import build.raft.app.components.raftProThemeGateDescription
import build.raft.app.data.StoredAuthServer
import build.raft.app.resources.*
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsBillingWebViewTest {
    @Test
    fun billingUrlCarriesExplicitAppLocaleToEmbeddedWebApp() {
        val session =
            StoredAuthSession(
                user = StoredAuthUser(id = "user-1", email = "user@example.test", displayName = "User"),
                accessToken = "access",
                refreshToken = "refresh",
                selectedServerId = "server-1",
                selectedServerSlug = "workspace",
                servers = listOf(StoredAuthServer(id = "server-1", name = "Workspace", slug = "workspace"))
            )

        assertEquals(
            "https://app.raft.build/s/workspace/settings/billing?embed=raft-settings-v1&shell=host&lang=zh-Hans",
            settingsBillingWebViewUrl("https://app.raft.build", session, "zh-Hans")
        )
        assertEquals(
            "https://app.raft.build/s/workspace/settings/billing?embed=raft-settings-v1&shell=host&lang=system",
            settingsBillingWebViewUrl("https://app.raft.build", session, "system")
        )
    }

    @Test
    fun billingBootstrapClearsRefreshOwnerAndProvidesAccessAndServer() {
        val session = billingSession()
        val script = standaloneSettingsWebViewInjectedJavaScript(session, null, SettingsWebViewSurface.Billing)

        assertContains(script, "slock_access_token")
        assertContains(script, "slock_refresh_token")
        assertContains(script, "removeItem(\"slock_refresh_token\")")
        assertFalse(script.contains(session.refreshToken))
        assertContains(script, "slock_last_server_slug")
        assertFalse(script.contains(".h-panel-header"))
        assertFalse(script.contains("MutationObserver"))
        assertFalse(script.contains("createElement(\"style\")"))
        assertFalse(script.contains("hidden-header"))
    }

    @Test
    fun iosShowsBillingViewOnlyWithoutUpgradeOrBrowserLink() {
        assertEquals(IosBillingPresentation.ViewOnly, iosBillingPresentation())
        val target = settingsBillingOpenTarget(appStoreBillingRules = true)
        assertEquals(SettingsBillingOpenTarget.ViewOnly, target)
        assertFalse(settingsBillingOffersUpgrade(target))
        assertFalse(settingsBillingLeavesRaft(target))
    }

    @Test
    fun iosExternalPresentationOpensTheBrowserAndKeepsUpgrade() {
        val target = settingsBillingOpenTarget(appStoreBillingRules = true, iosPresentation = IosBillingPresentation.External)
        assertEquals(SettingsBillingOpenTarget.ExternalBrowser, target)
        assertTrue(settingsBillingOffersUpgrade(target))
        assertTrue(settingsBillingLeavesRaft(target))
    }

    @Test
    fun androidAndDesktopKeepTheBillingWebView() {
        IosBillingPresentation.entries.forEach { presentation ->
            assertEquals(
                SettingsBillingOpenTarget.InAppWebView,
                settingsBillingOpenTarget(appStoreBillingRules = false, iosPresentation = presentation)
            )
        }
        assertFalse(settingsBillingLeavesRaft(SettingsBillingOpenTarget.InAppWebView))
        assertTrue(settingsBillingOffersUpgrade(SettingsBillingOpenTarget.InAppWebView))
    }

    @Test
    fun externalBillingUrlIsThePlainProductionWebPage() {
        assertEquals(
            "https://app.raft.build/s/workspace/settings/billing?lang=zh-Hans",
            settingsBillingExternalWebUrl("https://app.raft.build", billingSession(), "zh-Hans")
        )
    }

    @Test
    fun externalBillingUrlFollowsTheStagingWebUrl() {
        assertEquals(
            "https://raft-app-staging.botiverse.dev/s/workspace/settings/billing?lang=ja",
            settingsBillingExternalWebUrl("https://raft-app-staging.botiverse.dev/", billingSession(), "ja")
        )
    }

    @Test
    fun externalBillingUrlWithoutServerOpensTheAccountBillingPath() {
        assertEquals(
            "https://app.raft.build/settings/billing?lang=en",
            settingsBillingExternalWebUrl("https://app.raft.build", session = null, localeTag = "en")
        )
    }

    @Test
    fun proGateCopyIsNeutralWhereTheAppOffersNoUpgrade() {
        assertEquals(
            RaftProGateCopy(Res.string.raft_pro_migration_title_neutral, Res.string.raft_pro_migration_description_neutral),
            raftProMigrationGateCopy(offersUpgrade = false)
        )
        assertEquals(Res.string.settings_server_theme_pro_description_neutral, raftProThemeGateDescription(offersUpgrade = false))
        assertEquals(
            RaftProGateCopy(Res.string.raft_pro_migration_title, Res.string.raft_pro_migration_description),
            raftProMigrationGateCopy(offersUpgrade = true)
        )
        assertEquals(Res.string.settings_server_theme_pro_description, raftProThemeGateDescription(offersUpgrade = true))
    }

    private fun billingSession() =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "user@example.test", displayName = "User"),
            accessToken = "access",
            refreshToken = "native-refresh-secret",
            selectedServerId = "server-1",
            selectedServerSlug = "workspace",
            servers = listOf(StoredAuthServer(id = "server-1", name = "Workspace", slug = "workspace"))
        )
}
