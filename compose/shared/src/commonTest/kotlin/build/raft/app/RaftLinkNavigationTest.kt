package build.raft.app

import build.raft.app.data.LinkOpenMode
import kotlin.test.Test
import kotlin.test.assertEquals

class RaftLinkNavigationTest {
    @Test
    fun ordinaryHttpLinksFollowTheStoredMode() {
        val url = "https://example.test/docs?q=1#install"

        assertEquals(
            RaftLinkOpenDecision(RaftLinkOpenTarget.ExternalBrowser, url),
            resolveRaftLinkOpenDecision(url, LinkOpenMode.SystemBrowser),
        )
        assertEquals(
            RaftLinkOpenDecision(RaftLinkOpenTarget.InAppBrowser, url),
            resolveRaftLinkOpenDecision(url, LinkOpenMode.InAppBrowser),
        )
    }

    @Test
    fun forceExternalOverridesInAppModeForHttpLinks() {
        val url = "https://accounts.example.test/oauth"

        assertEquals(
            RaftLinkOpenDecision(RaftLinkOpenTarget.ExternalBrowser, url),
            resolveRaftLinkOpenDecision(
                rawUrl = url,
                mode = LinkOpenMode.InAppBrowser,
                intent = RaftLinkOpenIntent.ForceExternal,
            ),
        )
    }

    @Test
    fun appStoreRestrictedPurchaseUrlsNeverOpenWebViewOrInAppSafari() {
        val billingUrl = "https://app.raft.build/s/hq/settings/billing"
        val upgradeUrl = "https://app.raft.build/servers/srv-1/upgrade"
        val stripeUrl = "https://checkout.stripe.com/c/pay/cs_live_123"
        val opened = mutableListOf<String>()
        val navigator =
            RaftLinkNavigator(
                modeProvider = { LinkOpenMode.InAppBrowser },
                openInAppBrowser = { opened += "in_app:$it"; true },
                openExternalBrowser = { opened += "view:$it" },
                onRejected = { opened += "rejected:$it" },
                openRaftWebPageInBrowser = { opened += "browser:$it" },
                isRaftWebPage = { it == billingUrl || it == upgradeUrl },
                openRaftWebPageInApp = { opened += "auth_web:$it"; true },
                isAppStoreRestrictedPurchaseUrl = { url ->
                    raftUrlIsAppStoreRestrictedPurchaseUrl(url, "https://app.raft.build", emptySet())
                },
                openAppStoreRestrictedPurchaseUrl = { url ->
                    if (raftUrlIsOwnBillingPath(url, "https://app.raft.build", emptySet())) {
                        opened += "native_billing_view_only:$url"
                        true
                    } else {
                        false
                    }
                },
            )

        // Own billing path redirects to native view-only Plan & Billing (never SFSafariViewController or WebView).
        assertEquals(
            RaftLinkOpenDecision(RaftLinkOpenTarget.InAppDestination, billingUrl),
            navigator.open(billingUrl, RaftLinkOpenIntent.Ordinary),
        )
        assertEquals(
            RaftLinkOpenDecision(RaftLinkOpenTarget.InAppDestination, billingUrl),
            navigator.open(billingUrl, RaftLinkOpenIntent.RaftWebPage),
        )
        // /servers/*/upgrade and Stripe URLs are blocked across every intent (never open browser/in-app).
        assertEquals(
            RaftLinkOpenDecision(RaftLinkOpenTarget.Rejected, rejectReason = RaftLinkRejectReason.RouteUnavailable),
            navigator.open(upgradeUrl, RaftLinkOpenIntent.Ordinary),
        )
        assertEquals(
            RaftLinkOpenDecision(RaftLinkOpenTarget.Rejected, rejectReason = RaftLinkRejectReason.RouteUnavailable),
            navigator.open(upgradeUrl, RaftLinkOpenIntent.RaftWebPageInBrowser),
        )
        assertEquals(
            RaftLinkOpenDecision(RaftLinkOpenTarget.Rejected, rejectReason = RaftLinkRejectReason.RouteUnavailable),
            navigator.open(stripeUrl, RaftLinkOpenIntent.Ordinary),
        )
        assertEquals(
            RaftLinkOpenDecision(RaftLinkOpenTarget.Rejected, rejectReason = RaftLinkRejectReason.RouteUnavailable),
            navigator.open(stripeUrl, RaftLinkOpenIntent.ForceExternal),
        )
        assertEquals(
            listOf(
                "native_billing_view_only:$billingUrl",
                "native_billing_view_only:$billingUrl",
                "rejected:RouteUnavailable",
                "rejected:RouteUnavailable",
                "rejected:RouteUnavailable",
                "rejected:RouteUnavailable",
            ),
            opened,
        )
    }

    @Test
    fun mailAndTelephoneLinksAlwaysUseTheExternalHandler() {
        assertEquals(
            RaftLinkOpenTarget.ExternalBrowser,
            resolveRaftLinkOpenDecision("mailto:team@example.test", LinkOpenMode.InAppBrowser).target,
        )
        assertEquals(
            RaftLinkOpenTarget.ExternalBrowser,
            resolveRaftLinkOpenDecision("tel:+8613800138000", LinkOpenMode.InAppBrowser).target,
        )
    }

    @Test
    fun unsafeRelativeAndUnknownLinksAreRejected() {
        val rejected =
            listOf(
                "",
                "/relative/path",
                "javascript:alert(1)",
                "data:text/html,hello",
                "file:///tmp/private",
                "content://private/item",
                "intent://open",
                "about:blank",
                "custom://target",
            )

        rejected.forEach { url ->
            assertEquals(
                RaftLinkOpenTarget.Rejected,
                resolveRaftLinkOpenDecision(url, LinkOpenMode.InAppBrowser).target,
                url,
            )
        }
    }

    @Test
    fun browserSubtitleRedactsQueryAndFragment() {
        assertEquals(
            "https://example.test/docs",
            raftInAppBrowserDisplayUrl("https://example.test/docs?token=secret#section"),
        )
    }

    @Test
    fun openAppEntryIgnoresSystemBrowserModeAndRegistersSingleUseToken() {
        val opened = mutableListOf<String>()
        val registry = RaftAppEntryRegistry(newToken = { "tok-1" })
        val navigator =
            RaftLinkNavigator(
                modeProvider = { LinkOpenMode.SystemBrowser },
                openInAppBrowser = { opened += "in_app:$it"; true },
                openExternalBrowser = { opened += "external:$it" },
                onRejected = { opened += "rejected:$it" },
                openInAppBrowserWithEntry = { url, token ->
                    opened += "entry:$url|$token"
                    true
                },
                supportsInAppBrowser = { true },
                raftHostsProvider = { setOf("app.raft.build", "api.raft.build") },
                debugBuild = false,
                appEntryRegistry = registry,
            )

        val url = "https://stamp.build/pulls/1"
        val request = RaftAppEntryRequest(scopeOrigin = "https://stamp.build", appName = "Stamp", preferredServerId = "srv-1")
        assertEquals(
            RaftLinkOpenDecision(RaftLinkOpenTarget.InAppBrowser, url),
            navigator.openAppEntry(url, request),
        )
        assertEquals(listOf("entry:$url|tok-1"), opened)
        assertEquals(request, registry.claim("tok-1", url, owner = "pager-1"))
    }

    @Test
    fun openAppEntryFallsBackToOrdinaryOpenOnInvalidScopeOrDesktop() {
        val opened = mutableListOf<String>()
        val registry = RaftAppEntryRegistry(newToken = { "tok-unused" })
        fun makeNavigator(supportsInApp: Boolean, debug: Boolean) =
            RaftLinkNavigator(
                modeProvider = { LinkOpenMode.SystemBrowser },
                openInAppBrowser = { opened += "in_app:$it"; true },
                openExternalBrowser = { opened += "external:$it" },
                onRejected = { opened += "rejected:$it" },
                openInAppBrowserWithEntry = { url, token ->
                    opened += "entry:$url|$token"
                    true
                },
                supportsInAppBrowser = { supportsInApp },
                raftHostsProvider = { setOf("app.raft.build", "api.raft.build") },
                debugBuild = debug,
                appEntryRegistry = registry,
            )

        // Off-origin scope falls back to ordinary open (SystemBrowser).
        makeNavigator(supportsInApp = true, debug = false).openAppEntry(
            "https://other.example/home",
            RaftAppEntryRequest(scopeOrigin = "https://stamp.build"),
        )
        // Raft own host scope falls back to ordinary open.
        makeNavigator(supportsInApp = true, debug = false).openAppEntry(
            "https://app.raft.build/s/hq",
            RaftAppEntryRequest(scopeOrigin = "https://app.raft.build"),
        )
        // Non-debug http loopback falls back to ordinary open.
        makeNavigator(supportsInApp = true, debug = false).openAppEntry(
            "http://10.0.2.2:3000/app",
            RaftAppEntryRequest(scopeOrigin = "http://10.0.2.2:3000"),
        )
        // Debug emulator loopback 10.0.2.2 opens as an App entry (#356 Decision #8).
        makeNavigator(supportsInApp = true, debug = true).openAppEntry(
            "http://10.0.2.2:3000/app",
            RaftAppEntryRequest(scopeOrigin = "http://10.0.2.2:3000", appName = "DevApp"),
        )
        // Desktop (supportsInAppBrowser = false) falls back to ordinary open.
        makeNavigator(supportsInApp = false, debug = false).openAppEntry(
            "https://stamp.build/pulls/2",
            RaftAppEntryRequest(scopeOrigin = "https://stamp.build"),
        )

        assertEquals(
            listOf(
                "external:https://other.example/home",
                "external:https://app.raft.build/s/hq",
                "external:http://10.0.2.2:3000/app",
                "entry:http://10.0.2.2:3000/app|tok-unused",
                "external:https://stamp.build/pulls/2",
            ),
            opened,
        )
    }

    @Test
    fun inAppBrowserRoutesRaftHostsOutOnAndroidAsWellAsIos() {
        val webBase = "https://app.raft.build"
        val apiBase = "https://api.raft.build"

        assertEquals(
            true,
            raftInAppBrowserShouldRouteNavigation(
                candidateUrl = "https://app.raft.build/s/hq",
                activeWebBaseUrl = webBase,
                appStoreBillingRules = false,
                useEphemeralStore = false,
                activeApiBaseUrl = apiBase,
                appHosts = emptySet(),
            ),
        )
        assertEquals(
            true,
            raftInAppBrowserShouldRouteNavigation(
                candidateUrl = "https://api.raft.build/api/oauth/authorize?client_id=x",
                activeWebBaseUrl = webBase,
                appStoreBillingRules = false,
                useEphemeralStore = false,
                activeApiBaseUrl = apiBase,
                appHosts = emptySet(),
            ),
        )
        assertEquals(
            false,
            raftInAppBrowserShouldRouteNavigation(
                candidateUrl = "https://stamp.build/pulls/1",
                activeWebBaseUrl = webBase,
                appStoreBillingRules = false,
                useEphemeralStore = false,
                activeApiBaseUrl = apiBase,
                appHosts = emptySet(),
            ),
        )
    }

    @Test
    fun thirdPartyInAppLinkFailsClosedToExternalBrowserWhenIsolatedProfileUnsupported() {
        val opened = mutableListOf<String>()
        val registry = RaftAppEntryRegistry(newToken = { "tok-pre119" })
        fun makeNavigator(supportsIsolated: Boolean) =
            RaftLinkNavigator(
                modeProvider = { LinkOpenMode.InAppBrowser },
                openInAppBrowser = { opened += "in_app:$it"; true },
                openExternalBrowser = { opened += "external:$it" },
                onRejected = { opened += "rejected:$it" },
                openInAppBrowserWithEntry = { url, token ->
                    opened += "entry:$url|$token"
                    true
                },
                supportsInAppBrowser = { true },
                supportsIsolatedWebViewProfile = { supportsIsolated },
                raftHostsProvider = { setOf("app.raft.build", "api.raft.build") },
                debugBuild = false,
                appEntryRegistry = registry,
            )

        // Pre-Chromium 119 Android (no MULTI_PROFILE): third-party link fails closed to system browser.
        assertEquals(
            RaftLinkOpenDecision(RaftLinkOpenTarget.ExternalBrowser, "https://docs.example.com/push"),
            makeNavigator(supportsIsolated = false).open("https://docs.example.com/push"),
        )
        // Layer 2 Raft App entry uses Default profile and still opens in-app even without MULTI_PROFILE.
        assertEquals(
            RaftLinkOpenDecision(RaftLinkOpenTarget.InAppBrowser, "https://stamp.build/pulls/9"),
            makeNavigator(supportsIsolated = false).openAppEntry(
                "https://stamp.build/pulls/9",
                RaftAppEntryRequest(scopeOrigin = "https://stamp.build", appName = "Stamp"),
            ),
        )
        // Chromium 119+ Android / iOS: third-party link opens in the mobile web panel.
        assertEquals(
            RaftLinkOpenDecision(RaftLinkOpenTarget.InAppBrowser, "https://docs.example.com/push"),
            makeNavigator(supportsIsolated = true).open("https://docs.example.com/push"),
        )
        assertEquals(
            listOf(
                "external:https://docs.example.com/push",
                "entry:https://stamp.build/pulls/9|tok-pre119",
                "in_app:https://docs.example.com/push",
            ),
            opened,
        )
    }
}
