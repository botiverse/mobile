package build.raft.app

import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import kotlin.test.Test
import kotlin.test.assertEquals

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
            "https://app.raft.build/s/workspace/settings/billing?lang=zh-Hans",
            settingsBillingWebViewUrl("https://app.raft.build", session, "zh-Hans")
        )
        assertEquals(
            "https://app.raft.build/s/workspace/settings/billing?lang=system",
            settingsBillingWebViewUrl("https://app.raft.build", session, "system")
        )
    }
}
