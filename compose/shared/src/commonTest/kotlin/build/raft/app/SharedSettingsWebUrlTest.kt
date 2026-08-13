package build.raft.app

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
    fun hostShellWebUrlRootsPathAtConfiguredCanonicalOrigin() {
        assertEquals(
            "https://raft-app-staging.botiverse.dev/s/workspace/computers?embed=raft-settings-v1&shell=host",
            sharedSettingsHostShellWebUrl(
                "https://raft-app-staging.botiverse.dev/s/botiverse/",
                "/s/workspace/computers",
            ),
        )
    }

    @Test
    fun hostShellWebUrlFailsClosedForInvalidConfiguredAuthority() {
        assertEquals("", sharedSettingsHostShellWebUrl("https://user@example.test/s/workspace", "/computers"))
    }
}
