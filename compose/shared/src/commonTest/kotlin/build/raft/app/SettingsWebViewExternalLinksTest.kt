package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsWebViewExternalLinksTest {
    private val webOrigin = "https://app.raft.build/s/workspace/settings/integrations?embed=raft-settings-v1&shell=host"

    @Test
    fun blockedNavigationFilterAcceptsNavigableSchemesAndIgnoresPopupPlaceholders() {
        assertEquals("https://stamp.build/repos", settingsWebViewBlockedNavigationUrlOrNull("https://stamp.build/repos"))
        assertEquals("http://localhost:3000/callback", settingsWebViewBlockedNavigationUrlOrNull(" http://localhost:3000/callback "))
        assertEquals("mailto:support@raft.build", settingsWebViewBlockedNavigationUrlOrNull("mailto:support@raft.build"))
        assertEquals("tel:+14155550100", settingsWebViewBlockedNavigationUrlOrNull("tel:+14155550100"))

        listOf(
            "",
            "   ",
            "about:blank",
            "about:srcdoc",
            "javascript:alert(1)",
            "data:text/html,hi",
            "blob:https://app.raft.build/abc",
            "file:///etc/passwd",
            "intent://scan/#Intent;scheme=zxing;end",
            "https://user:pass@evil.test/",
            "not-a-url",
        ).forEach { ignored ->
            assertNull(
                settingsWebViewBlockedNavigationUrlOrNull(ignored),
                "expected blocked navigation to ignore: $ignored",
            )
        }
    }

    @Test
    fun schemeForLogSanitizesMissingColonAndUntrustedTokens() {
        assertEquals("about", settingsWebViewSchemeForLog("about:blank"))
        assertEquals("javascript", settingsWebViewSchemeForLog("  JavaScript:alert(1)"))
        assertEquals("none", settingsWebViewSchemeForLog("/path?token=SECRET"))
        assertEquals("none", settingsWebViewSchemeForLog("not-a-url"))
        assertEquals("none", settingsWebViewSchemeForLog("   "))
    }

    @Test
    fun hostOpenExternalParsesValidHttpAndHttpsUrlsOnExpectedOriginOnly() {
        val stampUrl =
            "$webOrigin#host=open-external&to=https%3A%2F%2Fstamp.build%2Fgithub%2Fbotiverse%2Fmobile%3Ftab%3Dprs%23top"
        assertTrue(settingsWebViewAsksHostOpenExternal(stampUrl, webOrigin))
        assertEquals(
            "https://stamp.build/github/botiverse/mobile?tab=prs#top",
            settingsWebViewHostOpenExternalUrl(stampUrl, webOrigin),
        )

        // Encoded `+` (`%2B`), `%20` inside path, uppercase scheme, and IPv6 literal all decode intact.
        val plusUrl = "$webOrigin#host=open-external&to=https%3A%2F%2Fen.wikipedia.org%2Fwiki%2FC%2B%2B%3Fq%3Da%2Bb"
        assertEquals(
            "https://en.wikipedia.org/wiki/C++?q=a+b",
            settingsWebViewHostOpenExternalUrl(plusUrl, webOrigin),
        )

        val localHttpUrl = "$webOrigin#host=open-external&to=http%3A%2F%2F127.0.0.1%3A8080%2Fdocs%2Fa%2520b"
        assertEquals(
            "http://127.0.0.1:8080/docs/a%20b",
            settingsWebViewHostOpenExternalUrl(localHttpUrl, webOrigin),
        )

        val upperScheme = "$webOrigin#host=open-external&to=HTTPS%3A%2F%2Fstamp.build%2Fapp"
        assertEquals("HTTPS://stamp.build/app", settingsWebViewHostOpenExternalUrl(upperScheme, webOrigin))

        val ipv6 = "$webOrigin#host=open-external&to=http%3A%2F%2F%5B%3A%3A1%5D%3A8080%2Fcallback"
        assertEquals("http://[::1]:8080/callback", settingsWebViewHostOpenExternalUrl(ipv6, webOrigin))

        // Explicit default port on pageUrl still matches normalized webOrigin.
        val defaultPortPage = "https://app.raft.build:443/s/workspace/settings/integrations#host=open-external&to=https%3A%2F%2Fstamp.build"
        assertEquals(
            "https://stamp.build",
            settingsWebViewHostOpenExternalUrl(defaultPortPage, webOrigin),
        )

        // Requests emitted by off-origin pages in unlocked WebViews are completely ignored.
        val offOrigin = "https://checkout.stripe.com/pay#host=open-external&to=https%3A%2F%2Fstamp.build"
        assertFalse(settingsWebViewAsksHostOpenExternal(offOrigin, webOrigin))
        assertNull(settingsWebViewHostOpenExternalUrl(offOrigin, webOrigin))
    }
}
