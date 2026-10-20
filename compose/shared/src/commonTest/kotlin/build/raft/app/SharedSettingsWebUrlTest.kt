package build.raft.app

import build.raft.app.data.SharedApiEnvironmentStore
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthUser
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedSettingsWebUrlTest {
    @Test
    fun resolvedWebBaseUrlUsesConfiguredValueWithoutTrailingSlash() {
        assertEquals(
            "https://staging.raft.build",
            sharedSettingsResolvedWebBaseUrl(" https://staging.raft.build/ ")
        )
    }

    @Test
    fun resolvedWebBaseUrlFallsBackToProductionAppUrl() {
        assertEquals("https://app.raft.build", sharedSettingsResolvedWebBaseUrl(" "))
    }

    @Test
    fun webUrlAppendsPathToConfiguredBase() {
        assertEquals(
            "https://staging.raft.build/settings/billing",
            sharedSettingsWebUrl("https://staging.raft.build/", "/settings/billing")
        )
    }

    @Test
    fun stagingEnvironmentProvidesOriginAndSettingsConsumerBuildsSelectedServerRoute() {
        assertEquals(
            "https://raft-app-staging.botiverse.dev",
            SharedApiEnvironmentStore.Staging.webUrl,
        )
        assertEquals(
            "https://raft-app-staging.botiverse.dev/s/workspace/settings/administration?embed=raft-settings-v1&shell=host&lang=en",
            settingsAdministrationWebViewUrl(
                SharedApiEnvironmentStore.Staging.webUrl,
                StoredAuthSession(
                    user = StoredAuthUser(id = "user-1", email = "user@example.test", displayName = "User"),
                    accessToken = "access-token",
                    refreshToken = "refresh-token",
                    selectedServerId = "server-1",
                    selectedServerSlug = "workspace",
                    servers = listOf(StoredAuthServer(id = "server-1", slug = "workspace", name = "Workspace")),
                ),
                "en",
            ),
        )
    }
}
