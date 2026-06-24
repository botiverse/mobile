package ai.slock.compose

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
}
