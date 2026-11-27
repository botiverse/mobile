package build.raft.app

import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.settings.SettingsWebViewSurface
import build.raft.app.settings.settingsAdministrationWebViewUrl
import build.raft.app.settings.standaloneSettingsWebViewInjectedJavaScript
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SettingsAdministrationWebViewTest {
    @Test
    fun administrationUrlCarriesLocaleAndEmbedFlag() {
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
            "https://app.raft.build/s/workspace/settings/administration?embed=raft-settings-v1&shell=host&lang=zh-Hans",
            settingsAdministrationWebViewUrl("https://app.raft.build", session, "zh-Hans")
        )
    }

    @Test
    fun administrationUrlFallsBackWithoutServerSlug() {
        assertEquals(
            "https://app.raft.build/settings/administration?embed=raft-settings-v1&shell=host&lang=system",
            settingsAdministrationWebViewUrl("https://app.raft.build", null, "system")
        )
    }

    @Test
    fun administrationBootstrapClearsRefreshOwnerAndProvidesAccessAndServer() {
        val session = administrationSession()
        val script =
            standaloneSettingsWebViewInjectedJavaScript(
                session,
                null,
                SettingsWebViewSurface.Administration,
                webOrigin = "https://app.raft.build/s/workspace/settings/administration?embed=raft-settings-v1&shell=host",
            )

        val guard = "if (window.top !== window || window.location.origin !== \"https://app.raft.build\") return;"
        assertContains(script, guard)
        assertContains(script, "slock_access_token")
        assertEquals(true, script.indexOf(guard) < script.indexOf("slock_access_token"))
        assertContains(script, "slock_refresh_token")
        assertContains(script, "removeItem(\"slock_refresh_token\")")
        assertFalse(script.contains(session.refreshToken))
        assertContains(script, "slock_last_server_slug")
        assertFalse(script.contains(".h-panel-header"))
        assertFalse(script.contains("MutationObserver"))
        assertFalse(script.contains("createElement(\"style\")"))
        assertFalse(script.contains("hidden-header"))
    }

    private fun administrationSession() =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "user@example.test", displayName = "User"),
            accessToken = "access",
            refreshToken = "native-refresh-secret",
            selectedServerId = "server-1",
            selectedServerSlug = "workspace",
            servers = listOf(StoredAuthServer(id = "server-1", name = "Workspace", slug = "workspace"))
        )
}
