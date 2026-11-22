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
}
