package build.raft.app

import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import kotlin.test.Test
import kotlin.test.assertEquals

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
            "https://app.raft.build/s/workspace/settings/administration?lang=zh-Hans&embed=1",
            settingsAdministrationWebViewUrl("https://app.raft.build", session, "zh-Hans")
        )
    }

    @Test
    fun administrationUrlFallsBackWithoutServerSlug() {
        assertEquals(
            "https://app.raft.build/settings/administration?lang=system&embed=1",
            settingsAdministrationWebViewUrl("https://app.raft.build", null, "system")
        )
    }
}
