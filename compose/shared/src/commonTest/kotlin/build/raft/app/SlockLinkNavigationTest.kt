package build.raft.app

import build.raft.app.data.LinkOpenMode
import build.raft.app.compat.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SlockLinkNavigationTest {
    @Test
    fun ordinaryHttpLinksFollowTheStoredMode() {
        val url = "https://example.test/docs?q=1#install"

        assertEquals(
            SlockLinkOpenDecision(SlockLinkOpenTarget.ExternalBrowser, url),
            resolveSlockLinkOpenDecision(url, LinkOpenMode.SystemBrowser),
        )
        assertEquals(
            SlockLinkOpenDecision(SlockLinkOpenTarget.InAppBrowser, url),
            resolveSlockLinkOpenDecision(url, LinkOpenMode.InAppBrowser),
        )
    }

    @Test
    fun forceExternalOverridesInAppModeForHttpLinks() {
        val url = "https://accounts.example.test/oauth"

        assertEquals(
            SlockLinkOpenDecision(SlockLinkOpenTarget.ExternalBrowser, url),
            resolveSlockLinkOpenDecision(
                rawUrl = url,
                mode = LinkOpenMode.InAppBrowser,
                intent = SlockLinkOpenIntent.ForceExternal,
            ),
        )
    }

    @Test
    fun mailAndTelephoneLinksAlwaysUseTheExternalHandler() {
        assertEquals(
            SlockLinkOpenTarget.ExternalBrowser,
            resolveSlockLinkOpenDecision("mailto:team@example.test", LinkOpenMode.InAppBrowser).target,
        )
        assertEquals(
            SlockLinkOpenTarget.ExternalBrowser,
            resolveSlockLinkOpenDecision("tel:+8613800138000", LinkOpenMode.InAppBrowser).target,
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
                SlockLinkOpenTarget.Rejected,
                resolveSlockLinkOpenDecision(url, LinkOpenMode.InAppBrowser).target,
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
                SlockLinkOpenTarget.Rejected,
                resolveSlockLinkOpenDecision(url, LinkOpenMode.InAppBrowser).target,
                url,
            )
        }
    }

    @Test
    fun browserPageDataCarriesOnlyTheValidatedNavigationIntent() {
        val pageData = requireNotNull(slockInAppBrowserPageData(" https://example.test/docs?q=1#install "))

        assertEquals(SLOCK_IN_APP_BROWSER_PAGE, pageData.optString("pageName"))
        assertEquals("https://example.test/docs?q=1#install", pageData.optString(SLOCK_IN_APP_BROWSER_URL_KEY))
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
                .put(SLOCK_IN_APP_BROWSER_URL_KEY, "https://example.test/docs")
                .put("title", "Stale title")
                .put("html", "<h1>stale</h1>")
                .slockInAppBrowserUrlOrNull(),
        )
        assertNull(
            JSONObject()
                .put(SLOCK_IN_APP_BROWSER_URL_KEY, "javascript:alert(1)")
                .slockInAppBrowserUrlOrNull()
        )
    }

    @Test
    fun browserSubtitleRedactsQueryAndFragment() {
        assertEquals(
            "https://example.test/docs",
            slockInAppBrowserDisplayUrl("https://example.test/docs?token=secret#section"),
        )
    }
}
