package build.raft.app

import build.raft.app.data.LinkOpenMode
import build.raft.app.compat.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

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
    fun raftWebPagesFollowTheModeButReachARealBrowser() {
        // dm:@artin #97: an announcement's link to a Raft page the app has no screen for.
        val url = "https://app.raft.build/s/hq/apps"
        val opened = mutableListOf<String>()
        fun navigator(mode: LinkOpenMode) =
            RaftLinkNavigator(
                modeProvider = { mode },
                openInAppBrowser = { opened += "in_app:$it"; true },
                openExternalBrowser = { opened += "view:$it" },
                onRejected = { opened += "rejected:$it" },
                openRaftWebPageInBrowser = { opened += "browser:$it" },
            )

        navigator(LinkOpenMode.SystemBrowser).open(url, RaftLinkOpenIntent.RaftWebPage)
        navigator(LinkOpenMode.InAppBrowser).open(url, RaftLinkOpenIntent.RaftWebPage)
        navigator(LinkOpenMode.SystemBrowser).open("https://example.test/blog")
        // A page confirmed with a passkey ("Verify it's you" Open on the web): a real browser in either mode.
        val passkeyPage = "https://app.raft.build/s/hq/computers"
        navigator(LinkOpenMode.InAppBrowser).open(passkeyPage, RaftLinkOpenIntent.RaftWebPageInBrowser)
        navigator(LinkOpenMode.SystemBrowser).open(passkeyPage, RaftLinkOpenIntent.RaftWebPageInBrowser)

        assertEquals(
            listOf(
                "browser:$url",
                "in_app:$url",
                "view:https://example.test/blog",
                "browser:$passkeyPage",
                "browser:$passkeyPage",
            ),
            opened,
        )
    }

    @Test
    fun raftWebPagesPreferAuthenticatedInAppWebViewUnlessForcedExternal() {
        val ownPage = "https://app.raft.build/s/hq/apps"
        val otherEnvPage = "https://staging.raft.build/s/hq/apps"
        val opened = mutableListOf<String>()
        fun navigator(mode: LinkOpenMode) =
            RaftLinkNavigator(
                modeProvider = { mode },
                openInAppBrowser = { opened += "in_app:$it"; true },
                openExternalBrowser = { opened += "view:$it" },
                onRejected = { opened += "rejected:$it" },
                openRaftWebPageInBrowser = { opened += "browser:$it" },
                openRaftWebPageInApp = { url ->
                    if (url == ownPage) {
                        opened += "auth_web:$url"
                        true
                    } else {
                        false
                    }
                },
            )

        assertEquals(
            RaftLinkOpenDecision(RaftLinkOpenTarget.InAppDestination, ownPage),
            navigator(LinkOpenMode.SystemBrowser).open(ownPage, RaftLinkOpenIntent.RaftWebPage),
        )
        assertEquals(
            RaftLinkOpenDecision(RaftLinkOpenTarget.InAppDestination, ownPage),
            navigator(LinkOpenMode.InAppBrowser).open(ownPage, RaftLinkOpenIntent.RaftWebPage),
        )
        // Unmatched origin falls back cleanly to browser/in-app browser.
        navigator(LinkOpenMode.SystemBrowser).open(otherEnvPage, RaftLinkOpenIntent.RaftWebPage)
        // Explicit external intents always bypass authenticated in-app WebView.
        navigator(LinkOpenMode.InAppBrowser).open(ownPage, RaftLinkOpenIntent.ForceExternal)
        navigator(LinkOpenMode.InAppBrowser).open(ownPage, RaftLinkOpenIntent.RaftWebPageInBrowser)

        assertEquals(
            listOf(
                "auth_web:$ownPage",
                "auth_web:$ownPage",
                "browser:$otherEnvPage",
                "view:$ownPage",
                "browser:$ownPage",
            ),
            opened,
        )
    }

    @Test
    fun appStoreBlockedBillingPagesAlwaysUseRealSystemBrowserEvenInInAppMode() {
        val billingUrl = "https://app.raft.build/s/hq/settings/billing"
        val opened = mutableListOf<String>()
        val navigator =
            RaftLinkNavigator(
                modeProvider = { LinkOpenMode.InAppBrowser },
                openInAppBrowser = { opened += "in_app:$it"; true },
                openExternalBrowser = { opened += "view:$it" },
                onRejected = { opened += "rejected:$it" },
                openRaftWebPageInBrowser = { opened += "browser:$it" },
                isRaftWebPage = { it == billingUrl },
                openRaftWebPageInApp = { opened += "auth_web:$it"; true },
                isAppStoreBlockedBillingPage = { it == billingUrl },
            )

        assertEquals(
            RaftLinkOpenDecision(RaftLinkOpenTarget.ExternalBrowser, billingUrl),
            navigator.open(billingUrl, RaftLinkOpenIntent.Ordinary),
        )
        assertEquals(
            RaftLinkOpenDecision(RaftLinkOpenTarget.ExternalBrowser, billingUrl),
            navigator.open(billingUrl, RaftLinkOpenIntent.RaftWebPage),
        )
        assertEquals(listOf("browser:$billingUrl", "browser:$billingUrl"), opened)
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
    fun malformedHttpAuthoritiesAreRejected() {
        listOf(
            "https://",
            "https://:443/path",
            "https://user@example.test/path",
            "https://example.test:0/path",
            "https://example.test:70000/path",
            "https://example.test:\u0001/path",
            "https://example.test:443:444/path",
            "https://example.test\\other/path",
            "https://[example.test]/path",
            "https://[::1/path",
        ).forEach { url ->
            assertEquals(
                RaftLinkOpenTarget.Rejected,
                resolveRaftLinkOpenDecision(url, LinkOpenMode.InAppBrowser).target,
                url,
            )
        }
    }

    @Test
    fun browserPageDataCarriesOnlyTheValidatedNavigationIntent() {
        val pageData = requireNotNull(raftInAppBrowserPageData(" https://example.test/docs?q=1#install "))

        assertEquals(RAFT_IN_APP_BROWSER_PAGE, pageData.optString("pageName"))
        assertEquals("https://example.test/docs?q=1#install", pageData.optString(RAFT_IN_APP_BROWSER_URL_KEY))
        assertFalse(pageData.has("title"))
        assertFalse(pageData.has("html"))
        assertFalse(pageData.has("headers"))
        assertFalse(pageData.has("cookies"))
        assertFalse(pageData.has("history"))
        assertFalse(pageData.has("currentUrl"))
        assertFalse(pageData.has("loading"))
        assertFalse(pageData.has("canGoBack"))
    }

    @Test
    fun browserRouteParserRejectsInjectedOrNonHttpState() {
        assertEquals(
            "https://example.test/docs",
            JSONObject()
                .put(RAFT_IN_APP_BROWSER_URL_KEY, "https://example.test/docs")
                .put("title", "Stale title")
                .put("html", "<h1>stale</h1>")
                .raftInAppBrowserUrlOrNull(),
        )
        assertNull(
            JSONObject()
                .put(RAFT_IN_APP_BROWSER_URL_KEY, "javascript:alert(1)")
                .raftInAppBrowserUrlOrNull()
        )
    }

    @Test
    fun browserSubtitleRedactsQueryAndFragment() {
        assertEquals(
            "https://example.test/docs",
            raftInAppBrowserDisplayUrl("https://example.test/docs?token=secret#section"),
        )
    }
}
