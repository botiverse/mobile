package build.raft.app

import build.raft.app.compat.json.JSONObject
import build.raft.app.settings.raftAuthenticatedOwnWebPageUrlOrNull
import build.raft.app.settings.raftUrlIsAppStoreRestrictedPurchaseUrl
import build.raft.app.settings.raftUrlIsOwnBillingPath
import build.raft.app.settings.settingsOwnWebExternalOpenIntent
import build.raft.app.shell.RaftLinkOpenIntent
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
        // Explicit default port and uppercase host match normalized active origin.
        assertEquals(
            "https://app.raft.build:443/s/hq/tasks?embed=raft-settings-v1&shell=host",
            raftAuthenticatedOwnWebPageUrlOrNull(
                rawUrl = "https://app.raft.build:443/s/hq/tasks",
                activeWebBaseUrl = prodBase,
                blockAppStoreBilling = false,
            ),
        )
        assertEquals(
            "HTTPS://APP.RAFT.BUILD/s/hq/tasks?embed=raft-settings-v1&shell=host",
            raftAuthenticatedOwnWebPageUrlOrNull(
                rawUrl = "HTTPS://APP.RAFT.BUILD/s/hq/tasks",
                activeWebBaseUrl = prodBase,
                blockAppStoreBilling = false,
            ),
        )
        // Reserved host/embed params (embed, shell, back, chrome, host*) are stripped on entry so
        // untrusted links cannot smuggle dead back buttons or host toolbar contracts.
        assertEquals(
            "$prodBase/s/hq/apps?tab=all&embed=raft-settings-v1&shell=host",
            raftAuthenticatedOwnWebPageUrlOrNull(
                rawUrl =
                    "$prodBase/s/hq/apps?embed=0&tab=all&shell=web&back=host&chrome=none" +
                        "&hostMenu=copy-link&hostOpen=message&hostConversation=c1&hostToolbar=1" +
                        "&hostDesktop=on&hostLoaded=1&HOSTCUSTOM=1&%62ack=host",
                activeWebBaseUrl = prodBase,
                blockAppStoreBilling = false,
            ),
        )
        // Reserved embed/host params are stripped even when embedHostShell = false (pageData entry).
        assertEquals(
            "$prodBase/s/hq/apps?tab=all#top",
            raftAuthenticatedOwnWebPageUrlOrNull(
                rawUrl = " $prodBase/s/hq/apps?tab=all&back=host&chrome=none&hostMenu=copy-link#top ",
                activeWebBaseUrl = prodBase,
                embedHostShell = false,
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
        assertEquals(
            "$prodBase/s/hq/apps",
            raftAuthenticatedOwnWebPageUrlOrNull(
                rawUrl = "$prodBase/s/hq/apps#host=close",
                activeWebBaseUrl = prodBase,
                embedHostShell = false,
                blockAppStoreBilling = false,
            ),
        )
        assertEquals(
            "$prodBase/s/hq/apps",
            raftAuthenticatedOwnWebPageUrlOrNull(
                rawUrl = "$prodBase/s/hq/apps#HOST=close",
                activeWebBaseUrl = prodBase,
                embedHostShell = false,
                blockAppStoreBilling = false,
            ),
        )
    }

    @Test
    fun validatorFailsClosedAcrossEnvironmentsEncodedBypassesBackslashesAndUnsafeTargets() {
        val rejectedOnStaging =
            listOf(
                "",
                "   ",
                "$prodBase/s/hq/apps",
                "HTTPS://APP.RAFT.BUILD/s/hq/apps",
                "https://open.raft.build/s/hq/apps",
                "https://raft-app-staging.botiverse.dev./s/hq/apps",
                "https://raft-app-staging.botiverse.dev../s/hq/apps",
                "http://raft-app-staging.botiverse.dev/s/hq/apps",
                "https://raft-app-staging.botiverse.dev.evil.test/s/hq/apps",
                "https://user@raft-app-staging.botiverse.dev/s/hq/apps",
                "https://raft-app-staging.botiverse.dev\\@evil.test/s/hq/apps",
                "https://raft-app-staging.botiverse.dev\\evil.test/s/hq/apps",
                "$stagingBase/s/hq\\apps",
                "$stagingBase/s/hq/%5capps",
                "$stagingBase/s/hq/%5C_raft",
                "$stagingBase/%2f_raft/login",
                "$stagingBase/s/hq%2Fsettings/billing",
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
                "https://W-1.RAFTUSERCONTENT.COM/page",
                "https://content.raft.build/file",
                "https://CDN.RAFT.BUILD/file",
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
        assertNull(raftAuthenticatedOwnWebPageUrlOrNull("https://APP.RAFT.BUILD.:443/s/hq/tasks", prodBase, blockAppStoreBilling = false))
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
    fun appStoreRestrictedPurchaseDetectorCoversBillingUpgradeRoutesStripeUppercaseBackslashAndPercent2F() {
        val ownBillingUrls =
            listOf(
                "$prodBase/settings/billing",
                "http://app.raft.build/settings/billing",
                "HTTPS://APP.RAFT.BUILD/settings/billing",
                "https://x@app.raft.build/settings/billing",
                "$prodBase/s/hq/settings/billing?upgrade=history_30d&lang=en",
                "$prodBase/settings/%62illing",
                "$prodBase/s/hq/settings/%62illing",
                "$prodBase/./settings/billing",
                "$prodBase/foo/../settings/billing",
                "$prodBase/s/hq/settings/./billing",
            )
        for (url in ownBillingUrls) {
            assertTrue(raftUrlIsOwnBillingPath(url, prodBase), "raftUrlIsOwnBillingPath must match $url")
            assertTrue(raftUrlIsAppStoreRestrictedPurchaseUrl(url, prodBase), "restricted detector must match $url")
            assertTrue(raftInAppBrowserShouldBlockUrl(url, prodBase, appStoreBillingRules = true), "in-app browser must block $url on iOS")
            assertFalse(raftInAppBrowserShouldBlockUrl(url, prodBase, appStoreBillingRules = false), "Android in-app browser allows $url")
        }

        val upgradeAndStripeUrls =
            listOf(
                "$prodBase/servers/srv-123/upgrade",
                "http://app.raft.build/servers/srv-123/upgrade",
                "https://x@app.raft.build/servers/srv-123/upgrade",
                "HTTPS://APP.RAFT.BUILD/servers/srv-123/upgrade",
                "$prodBase/servers/srv-123/UPGRADE",
                "$prodBase/servers/srv-123/%75pgrade",
                "$prodBase/foo/../servers/srv-123/upgrade",
                "$prodBase/s/hq/servers/srv-123/upgrade",
                "$prodBase/s/hq/tasks?upgrade=history_30d",
                "http://app.raft.build/s/hq/tasks?upgrade=history_30d",
                "$prodBase/s/hq/tasks?%75pgrade=general",
                "$prodBase/s/hq/tasks?upgradeServer=srv-123",
                "$prodBase/settings%2Fbilling",
                "$prodBase/s/hq/settings%2fbilling",
                "$prodBase/servers/srv-1%2Fupgrade",
                "$prodBase/settings\\billing",
                "https://app.raft.build\\settings/billing",
                "$prodBase/settings/%5cbilling",
                "$prodBase/servers/srv-1%5Cupgrade",
                "https://checkout.stripe.com/c/pay/cs_live_123",
                "https://checkout.stripe.com\\c/pay/cs_live_123",
                "https://CHECKOUT.STRIPE.COM./c/pay/cs_live_123",
                "https://buy.stripe.com/test_abc123",
                "https://billing.stripe.com/p/session/bps_123",
                "https://invoice.stripe.com/i/acct_1/inv_1",
                "https://pay.stripe.com/p/session/123",
            )
        for (url in upgradeAndStripeUrls) {
            assertTrue(
                raftUrlIsAppStoreRestrictedPurchaseUrl(url, prodBase),
                "restricted detector must catch $url",
            )
            assertFalse(
                raftUrlIsOwnBillingPath(url, prodBase),
                "non-canonical-billing URL must not open native billing screen: $url",
            )
            assertTrue(
                raftInAppBrowserShouldBlockUrl(url, prodBase, appStoreBillingRules = true),
                "in-app browser must block $url on iOS",
            )
        }

        // Safe non-purchase URLs (including non-billing Stripe docs/status and ordinary own-host %2F links) are not flagged.
        for (safeUrl in listOf(
            "$prodBase/artifacts/pt_123",
            "$prodBase/s/hq/tasks",
            "$prodBase/s/hq/docs/a%2Fb",
            "https://docs.stripe.com/api",
            "https://status.stripe.com/",
            "https://stripe.com/docs",
            "https://example.com/settings/billing",
            "https://example.com/servers/1/upgrade",
            "https://notstripe.com/checkout",
        )) {
            assertFalse(raftUrlIsAppStoreRestrictedPurchaseUrl(safeUrl, prodBase), "should not flag $safeUrl")
            assertFalse(raftInAppBrowserShouldBlockUrl(safeUrl, prodBase, appStoreBillingRules = true), "should not block $safeUrl")
        }
    }

    @Test
    fun ownWebLogRedactionKeepsOnlyOriginAndFirstPathSegment() {
        assertEquals(
            "https://app.raft.build/integration-invites",
            raftOwnWebUrlForLog("https://app.raft.build/integration-invites/secret-token-xyz?next=/s/hq#frag"),
        )
        assertEquals(
            "https://app.raft.build/s",
            raftOwnWebUrlForLog("https://app.raft.build/s/hq/settings/account?embed=raft-settings-v1#passkeys"),
        )
        assertEquals(
            "https://app.raft.build/artifacts",
            raftOwnWebUrlForLog("https://app.raft.build/artifacts/pt_secret_123/v/2"),
        )
        assertEquals(
            "https://app.raft.build",
            raftOwnWebUrlForLog("https://app.raft.build/?token=secret"),
        )
        assertEquals(
            "https://app.raft.build/join",
            raftOwnWebUrlForLog("https://user:pass@APP.RAFT.BUILD/join/secret-code"),
        )
        assertEquals(
            "https://app.raft.build/integration-invites",
            raftOwnWebUrlForLog("https://app.raft.build/integration-invites%2Fsecret-token"),
        )
        assertEquals(
            "https://app.raft.build/integration-invites",
            raftOwnWebUrlForLog("https://app.raft.build/integration-invites\\secret-token"),
        )
    }

    @Test
    fun externalOpenIntentUsesNoBounceOnlyForOwnRaftUrlsAndOrdinaryForThirdParty() {
        // Own active origin and other Raft origins need RaftWebPageInBrowser so App Links don't bounce back.
        assertEquals(
            RaftLinkOpenIntent.RaftWebPageInBrowser,
            settingsOwnWebExternalOpenIntent("$prodBase/s/hq/apps", prodBase, prodBase, emptySet()),
        )
        assertEquals(
            RaftLinkOpenIntent.RaftWebPageInBrowser,
            settingsOwnWebExternalOpenIntent("https://open.raft.build/s/hq/apps", prodBase, prodBase, emptySet()),
        )
        // Third-party URLs from #host=open-external follow the user's LinkOpenMode (Ordinary).
        assertEquals(
            RaftLinkOpenIntent.Ordinary,
            settingsOwnWebExternalOpenIntent("https://docs.example.com/guide", prodBase, prodBase, emptySet()),
        )
    }
}
