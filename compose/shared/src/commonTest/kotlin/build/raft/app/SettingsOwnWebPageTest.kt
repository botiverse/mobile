package build.raft.app

import build.raft.app.nav.RAFT_REPLACE_CURRENT_PAGE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsOwnWebPageTest {
    private val prodBase = "https://app.raft.build"
    private val stagingBase = "https://raft-app-staging.botiverse.dev"

    @Test
    fun validatorRequiresExactActiveEnvironmentOriginAndAppendsEmbedParams() {
        assertEquals(
            "$prodBase/s/hq/settings/account?embed=raft-settings-v1&shell=host#passkeys",
            raftAuthenticatedOwnWebPageUrlOrNull(
                rawUrl = "$prodBase/s/hq/settings/account#passkeys",
                activeWebBaseUrl = prodBase,
                blockAppStoreBilling = false,
            ),
        )
        assertEquals(
            "$stagingBase/s/hq/settings/billing?upgrade=history_30d&lang=en&embed=raft-settings-v1&shell=host",
            raftAuthenticatedOwnWebPageUrlOrNull(
                rawUrl = "$stagingBase/s/hq/settings/billing?upgrade=history_30d&lang=en",
                activeWebBaseUrl = "$stagingBase/",
                blockAppStoreBilling = false,
            ),
        )
        // Explicit default port matches normalized active origin.
        assertEquals(
            "https://app.raft.build:443/s/hq/tasks?embed=raft-settings-v1&shell=host",
            raftAuthenticatedOwnWebPageUrlOrNull(
                rawUrl = "https://app.raft.build:443/s/hq/tasks",
                activeWebBaseUrl = prodBase,
                blockAppStoreBilling = false,
            ),
        )
        // Existing embed/shell params are replaced so untrusted values cannot defeat host-shell chrome.
        assertEquals(
            "$prodBase/s/hq/apps?tab=all&embed=raft-settings-v1&shell=host",
            raftAuthenticatedOwnWebPageUrlOrNull(
                rawUrl = "$prodBase/s/hq/apps?embed=0&tab=all&shell=web",
                activeWebBaseUrl = prodBase,
                blockAppStoreBilling = false,
            ),
        )
        // #host=... fragments are stripped on entry to prevent command smuggling.
        assertEquals(
            "$prodBase/s/hq/apps?embed=raft-settings-v1&shell=host",
            raftAuthenticatedOwnWebPageUrlOrNull(
                rawUrl = "$prodBase/s/hq/apps#host=open-external&to=https%3A%2F%2Fevil.example",
                activeWebBaseUrl = prodBase,
                blockAppStoreBilling = false,
            ),
        )
        // embedHostShell = false returns the validated clean URL (still stripping #host= fragments).
        assertEquals(
            "$prodBase/s/hq/apps#top",
            raftAuthenticatedOwnWebPageUrlOrNull(
                rawUrl = " $prodBase/s/hq/apps#top ",
                activeWebBaseUrl = prodBase,
                embedHostShell = false,
                blockAppStoreBilling = false,
            ),
        )
        assertEquals(
            "$prodBase/s/hq/apps",
            raftAuthenticatedOwnWebPageUrlOrNull(
                rawUrl = "$prodBase/s/hq/apps#host=close",
                activeWebBaseUrl = prodBase,
                embedHostShell = false,
                blockAppStoreBilling = false,
            ),
        )
    }

    @Test
    fun validatorFailsClosedAcrossEnvironmentsEncodedBypassesAndUnsafeTargets() {
        val rejectedOnStaging =
            listOf(
                "",
                "   ",
                "$prodBase/s/hq/apps",
                "https://open.raft.build/s/hq/apps",
                "https://raft-app-staging.botiverse.dev./s/hq/apps",
                "https://raft-app-staging.botiverse.dev../s/hq/apps",
                "http://raft-app-staging.botiverse.dev/s/hq/apps",
                "https://raft-app-staging.botiverse.dev.evil.test/s/hq/apps",
                "https://user@raft-app-staging.botiverse.dev/s/hq/apps",
                "$stagingBase/_raft/login",
                "$stagingBase/_raft",
                "$stagingBase/s/hq/_raft",
                "$stagingBase/s/hq/_raft/bridge",
                "$stagingBase/%5fraft/login",
                "$stagingBase/a/../_raft",
                "$stagingBase/s/hq/%ZZ",
                "$stagingBase/s/hq/apps?ticket=one-time",
                "$stagingBase/s/hq/apps?a=1&TICKET=secret#frag",
                "$stagingBase/s/hq/apps?%74icket=secret",
                "$stagingBase/s/hq/apps?%ZZ=1",
                "https://a-pt-1.raftusercontent.com/page",
                "https://content.raft.build/file",
                "https://cdn.raft.build/file",
                "javascript:alert(1)",
                "mailto:hi@raft.build",
            )
        for (url in rejectedOnStaging) {
            assertNull(
                raftAuthenticatedOwnWebPageUrlOrNull(url, stagingBase, blockAppStoreBilling = false),
                "expected rejection for $url",
            )
        }
        // Trailing-dot host on active prod origin is also rejected so browser origin never disagrees.
        assertNull(raftAuthenticatedOwnWebPageUrlOrNull("https://app.raft.build./s/hq/tasks", prodBase, blockAppStoreBilling = false))
        // Artifact content and CDN hosts are rejected even if misconfigured as activeWebBaseUrl.
        for (hostUrl in listOf("https://w-1.raftusercontent.com", "https://content.raft.build", "https://cdn.raft.build")) {
            assertNull(
                raftAuthenticatedOwnWebPageUrlOrNull(
                    rawUrl = "$hostUrl/index.html",
                    activeWebBaseUrl = hostUrl,
                    blockAppStoreBilling = false,
                ),
                "expected rejection when misconfigured base=$hostUrl",
            )
        }
    }

    @Test
    fun appStoreBillingRulesBlockRawEncodedAndDotNormalizedBillingPaths() {
        val billingUrls =
            listOf(
                "$prodBase/settings/billing",
                "$prodBase/s/hq/settings/billing?upgrade=history_30d&lang=en",
                "$prodBase/settings/%62illing",
                "$prodBase/s/hq/settings/%62illing",
                "$prodBase/./settings/billing",
                "$prodBase/foo/../settings/billing",
                "$prodBase/s/hq/settings/./billing",
            )
        for (url in billingUrls) {
            assertNull(
                raftAuthenticatedOwnWebPageUrlOrNull(url, prodBase, blockAppStoreBilling = true),
                "iOS billing guard must reject $url",
            )
            assertTrue(
                raftUrlIsOwnBillingPath(url, prodBase),
                "raftUrlIsOwnBillingPath must detect $url",
            )
            assertEquals(
                url,
                raftAuthenticatedOwnWebPageUrlOrNull(url, prodBase, embedHostShell = false, blockAppStoreBilling = false),
                "non-App-Store platforms allow $url",
            )
        }
        // Non-billing account security pages remain allowed on iOS.
        val accountSecurityUrl = "$prodBase/s/hq/settings/account#passkeys"
        assertFalse(raftUrlIsOwnBillingPath(accountSecurityUrl, prodBase))
        assertEquals(
            accountSecurityUrl,
            raftAuthenticatedOwnWebPageUrlOrNull(
                accountSecurityUrl,
                prodBase,
                embedHostShell = false,
                blockAppStoreBilling = true,
            ),
        )
        // Off-origin billing URL is not Raft's own billing path.
        assertFalse(raftUrlIsOwnBillingPath("https://example.com/settings/billing", prodBase))
    }

    @Test
    fun stripEmbedParamsPreservesProductQueryAndNonHostFragments() {
        assertEquals(
            "$prodBase/s/hq/settings/account#passkeys",
            stripRaftOwnWebPageEmbedParams("$prodBase/s/hq/settings/account?embed=raft-settings-v1&shell=host#passkeys"),
        )
        assertEquals(
            "$prodBase/s/hq/settings/billing?upgrade=history_30d&lang=en",
            stripRaftOwnWebPageEmbedParams("$prodBase/s/hq/settings/billing?upgrade=history_30d&lang=en&embed=raft-settings-v1&shell=host"),
        )
        assertEquals(
            "$prodBase/s/hq/tasks",
            stripRaftOwnWebPageEmbedParams("$prodBase/s/hq/tasks?embed=raft-settings-v1&shell=host#host=open-external&to=https%3A%2F%2Fexample.com"),
        )
        assertEquals(
            "$prodBase/s/hq/tasks?embed=other&shell=web",
            stripRaftOwnWebPageEmbedParams("$prodBase/s/hq/tasks?embed=other&shell=web"),
        )
    }

    @Test
    fun artifactFrameDetectionAndPageDataRoundTrip() {
        assertTrue(raftOwnWebPageNeedsArtifactContentFrame("$prodBase/artifacts/pt_123/v/2"))
        assertTrue(raftOwnWebPageNeedsArtifactContentFrame("$prodBase/s/hq/artifacts/pt_123"))
        assertFalse(raftOwnWebPageNeedsArtifactContentFrame("$prodBase/s/hq/tasks"))

        val pageData = settingsOwnWebViewPageData("$prodBase/s/hq/apps")
        assertEquals(SettingsWebViewSurface.WebPage, settingsWebViewSurfaceFromPageData(pageData))
        assertEquals("$prodBase/s/hq/apps", settingsWebViewTargetUrlFromPageData(pageData))
        assertFalse(pageData.optBoolean(RAFT_REPLACE_CURRENT_PAGE, false))

        val replaceData = settingsOwnWebViewPageData("$prodBase/artifacts/pt_123", replaceCurrentPage = true)
        assertTrue(replaceData.optBoolean(RAFT_REPLACE_CURRENT_PAGE, false))
    }
}
