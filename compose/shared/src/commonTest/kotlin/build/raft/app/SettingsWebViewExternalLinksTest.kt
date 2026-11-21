package build.raft.app

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

    @Test
    fun hostOpenExternalDropsNonHttpSchemesMalformedEscapesAndRawDelimiters() {
        val rejectedPayloads =
            listOf(
                "",
                "javascript%3Aalert(1)",
                "mailto%3Auser%40example.test",
                "tel%3A%2B14155550100",
                "data%3Atext%2Fhtml%2C%3Cscript%3Ealert(1)%3C%2Fscript%3E",
                "file%3A%2F%2F%2Fetc%2Fpasswd",
                "blob%3Ahttps%3A%2F%2Fapp.raft.build%2F123",
                "about%3Ablank",
                "intent%3A%2F%2Fopen",
                "raft%3A%2F%2Fopen",
                "%2Fs%2Fworkspace%2Fchannel%2F1",
                "%2F%2Fevil.test",
                "https%253A%252F%252Fstamp.build",
                "https%3A%2F%2Fuser%40evil.test",
                "https%3A%2F%2Fstamp.build%ZZ",
                "https%3A%2F%2Fstamp.build%-1",
                "https%3A%2F%2Fstamp.build%FF",
                "%20https%3A%2F%2Fstamp.build%20",
                "https%3A%2F%2Fstamp.build/raw-slash",
                "https%3A%2F%2Fstamp.build?raw=query",
                "https%3A%2F%2Fen.wikipedia.org%2Fwiki%2FC++",
                "https%3A%2F%2Fstamp.build&extra=1",
            )
        rejectedPayloads.forEach { encoded ->
            val pageUrl = "$webOrigin#host=open-external&to=$encoded"
            assertTrue(
                settingsWebViewAsksHostOpenExternal(pageUrl, webOrigin),
                "expected request marker to be recognized so hash is cleared for: $encoded",
            )
            assertNull(
                settingsWebViewHostOpenExternalUrl(pageUrl, webOrigin),
                "expected non-http(s)/malformed target to be dropped for: $encoded",
            )
        }

        // Bare #host=open-external without &to= is recognized for cleanup and dropped.
        assertTrue(settingsWebViewAsksHostOpenExternal("$webOrigin#host=open-external", webOrigin))
        assertNull(settingsWebViewHostOpenExternalUrl("$webOrigin#host=open-external", webOrigin))
    }

    @Test
    fun hostOpenExternalTakenScriptIsTopFrameOriginGuardedAndRestoresSafePreviousHash() {
        val emptyHashScript = settingsWebViewHostOpenExternalTakenScript(webOrigin)
        assertContains(emptyHashScript, "if (window.top !== window || window.location.origin !== \"https://app.raft.build\") return;")
        assertContains(emptyHashScript, "/^#host=open-external(?:&|$)/")
        assertContains(emptyHashScript, "url.hash = \"\";")

        val restoredHashScript = settingsWebViewHostOpenExternalTakenScript(webOrigin, previousHash = "section-apps")
        assertContains(restoredHashScript, "url.hash = \"section-apps\";")

        // Never restore another #host= command fragment.
        val sanitizedHostHashScript = settingsWebViewHostOpenExternalTakenScript(webOrigin, previousHash = "host=open-external&to=x")
        assertContains(sanitizedHostHashScript, "url.hash = \"\";")

        // Fail closed when webOrigin has no valid http(s) origin.
        assertEquals("", settingsWebViewHostOpenExternalTakenScript("about:blank"))
        assertEquals("", settingsWebViewHostOpenExternalTakenScript(""))
    }

    @Test
    fun hostOpenExternalDeduperStaysSuppressedUntilNonRequestUrlObservedThenAllowsRepeat() {
        val deduper = SettingsWebViewHostOpenExternalDeduper(repeatWindowMs = 500L)
        val stampRequest = "$webOrigin#host=open-external&to=https%3A%2F%2Fstamp.build"
        val docsRequest = "$webOrigin#host=open-external&to=https%3A%2F%2Fdocs.raft.build"

        // Observe prior non-host fragment on the page.
        assertNull(deduper.observe("$webOrigin#installed", webOrigin, nowMs = 100L))
        assertFalse(deduper.awaitingHashClear)

        // First tap fires onUrlChange -> emits decision carrying previousHash.
        val first = deduper.observe(stampRequest, webOrigin, nowMs = 200L)
        assertNotNull(first)
        assertEquals("https://stamp.build", first.targetUrl)
        assertEquals("installed", first.previousHash)
        assertTrue(deduper.awaitingHashClear)

        // Immediate duplicate callback AND even a late poll (> repeatWindowMs) while hash is still stuck
        // MUST stay suppressed so returning from Safari/InAppBrowser never spontaneously re-opens the link.
        assertNull(deduper.observe(stampRequest, webOrigin, nowMs = 210L))
        assertNull(deduper.observe(stampRequest, webOrigin, nowMs = 2_000L))
        assertTrue(deduper.awaitingHashClear)

        // Once replaceState clears the hash and the WebView observes the cleared URL, awaitingHashClear flips false.
        assertNull(deduper.observe("$webOrigin#installed", webOrigin, nowMs = 2_050L))
        assertFalse(deduper.awaitingHashClear)

        // A stale repeat within repeatWindowMs of handling is still suppressed (e.g. if cleared fast at t=300ms).
        val tap2 = deduper.observe(stampRequest, webOrigin, nowMs = 2_100L)
        assertNotNull(tap2)
        assertNull(deduper.observe("$webOrigin#installed", webOrigin, nowMs = 2_150L))
        assertNull(deduper.observe(stampRequest, webOrigin, nowMs = 2_300L))

        // After clearing AND repeatWindowMs, a deliberate repeat tap opens again.
        val tap3 = deduper.observe(stampRequest, webOrigin, nowMs = 2_650L)
        assertNotNull(tap3)
        assertEquals("https://stamp.build", tap3.targetUrl)

        // Tapping a different link opens immediately without waiting for repeatWindowMs.
        val other = deduper.observe(docsRequest, webOrigin, nowMs = 2_680L)
        assertNotNull(other)
        assertEquals("https://docs.raft.build", other.targetUrl)

        // An invalid non-http(s) request emits a null targetUrl (so caller clears hash & logs drop) and deduplicates.
        val badRequest = "$webOrigin#host=open-external&to=javascript%3Aalert(1)"
        val dropped = deduper.observe(badRequest, webOrigin, nowMs = 2_700L)
        assertNotNull(dropped)
        assertNull(dropped.targetUrl)
        assertNull(deduper.observe(badRequest, webOrigin, nowMs = 2_710L))
    }
}
