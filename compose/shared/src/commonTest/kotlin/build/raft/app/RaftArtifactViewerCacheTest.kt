package build.raft.app

import build.raft.app.data.ArtifactKind
import build.raft.app.data.ArtifactLinkTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tenny's cache rules for the in-app Artifact viewer ([RaftArtifactViewerCache]):
 * 1. per account + server (+ web origin, environment), cleared on sign-out or a switch; an unknown
 *    subject reuses nothing;
 * 2. a 403/404 drops the cached copy (header and kept document);
 * 3. never a frame ticket: nothing the app keeps can hold one.
 * And the WebView rules' pure parts: the origin guard, the blocked-navigation hand-off, origins.
 */
class RaftArtifactViewerCacheTest {
    private val web = "https://raft-app-staging.botiverse.dev"
    private val env = "https://api-aws-staging.botiverse.dev/api"
    private val maya = RaftArtifactViewerScope(web, env, "user-maya", "server-northwind")
    private val mayaOtherServer = maya.copy(serverId = "server-push-test")
    private val someoneElse = maya.copy(accountId = "user-other")
    private val target = ArtifactLinkTarget("pt_c82e597f-5434-4741-909b-d1c8a7a531c8")
    private val header = RaftArtifactViewerHeader(target.id, "Q4 Launch Plan", ArtifactKind.Html, 1)
    private val pageUrl = "$web/artifacts/${target.id}"
    private val token = "access-token-1"

    private fun cache(drops: MutableList<Unit> = mutableListOf()) = RaftArtifactViewerCache(onKeptDocumentDropped = { drops += Unit })

    @Test
    fun aHeaderIsReadOnlyInTheScopeItWasSeenIn() {
        val cache = cache()
        cache.remember(maya, header)
        assertEquals(header, cache.header(maya, target.id))
        assertNull(cache.header(mayaOtherServer, target.id))
        assertNull(cache.header(someoneElse, target.id))
    }

    @Test
    fun aSwitchOfAccountOrServerClearsEverythingAndDropsTheKeptDocument() {
        val drops = mutableListOf<Unit>()
        val cache = cache(drops)
        cache.remember(maya, header)
        assertTrue(cache.rememberKeptDocument(maya, target, pageUrl, token))
        drops.clear()

        cache.remember(someoneElse, header.copy(title = "Theirs"))
        assertTrue(drops.isNotEmpty(), "the kept WebView goes with the old subject")
        assertFalse(cache.keptDocumentShows(maya, target))
        assertFalse(cache.keptDocumentShows(someoneElse, target))
        assertNull(cache.header(maya, target.id))

        cache.markKeptWarm(mayaOtherServer, token)
        assertNull(cache.header(someoneElse, target.id))
        assertTrue(cache.keptDocumentUsable(mayaOtherServer, token))
        assertFalse(cache.keptDocumentUsable(someoneElse, token))
    }

    @Test
    fun forbiddenOrNotFoundDropsTheCachedCopyAndTheKeptDocument() {
        val drops = mutableListOf<Unit>()
        val cache = cache(drops)
        cache.remember(maya, header)
        assertTrue(cache.rememberKeptDocument(maya, target, pageUrl, token))
        assertTrue(cache.keptDocumentShows(maya, target))
        drops.clear()

        cache.evict(maya, target.id)
        assertNull(cache.header(maya, target.id))
        assertFalse(cache.keptDocumentShows(maya, target))
        assertEquals(1, drops.size)

        // Another subject's eviction touches nothing of this one.
        cache.remember(maya, header)
        cache.evict(someoneElse, target.id)
        assertEquals(header, cache.header(maya, target.id))
    }

    @Test
    fun aFrameTicketIsNeverKept() {
        val drops = mutableListOf<Unit>()
        val cache = cache(drops)
        val ticketUrls =
            listOf(
                "https://w-c82e597f-5434-4741-909b-d1c8a7a531c8.w-staging.raftusercontent.com/_raft/open?ticket=rct1.k.c.m",
                "$web/_raft/open?ticket=rct1.k.c.m",
                "$web/artifacts/${target.id}?ticket=rct1.k.c.m",
                "https://evil.test/artifacts/${target.id}",
            )
        for (url in ticketUrls) {
            assertFalse(cache.rememberKeptDocument(maya, target, url, token), url)
            assertFalse(cache.keptDocumentShows(maya, target), url)
        }
        assertTrue(cache.rememberKeptDocument(maya, target, pageUrl, token))
        // What the app does send to a kept document carries the path alone, never a ticket.
        val script = raftArtifactViewerNavigateScript(web, "", ArtifactLinkTarget(target.id, 2))
        assertTrue("\"/artifacts/${target.id}/v/2\"" in script)
        assertFalse("ticket" in script)
        assertFalse("raftusercontent" in script)
    }

    @Test
    fun scriptsAreGuardedToATopLevelDocumentOfTheWebOrigin() {
        val script = raftArtifactViewerGuardedScript(web, "window.localStorage.setItem('k','v')")
        assertTrue("window.top !== window" in script)
        assertTrue("window.location.origin !== \"$web\"" in script)
        // The guard comes before the body.
        assertTrue(script.indexOf("window.top !== window") < script.indexOf("localStorage"))
        val config = raftArtifactViewerWebViewConfig(pageUrl, maya, script)
        assertEquals(web, config.mainFrameOrigin)
        assertFalse(config.hostEventsEnabled)
        assertEquals(maya.keepAliveKey, config.keepAliveKey)
        assertTrue(maya.keepAliveKey != someoneElse.keepAliveKey && maya.keepAliveKey != mayaOtherServer.keepAliveKey)
    }

    @Test
    fun blockedAddressesAndNewWindowsAreRoutedAsLinks() {
        val other = "pt_0b6f1c2e-5d4a-4c3b-9a8e-7f6d5c4b3a21"
        val cases =
            listOf(
                // Off the web origin (Ask AI's links, the content domain): the system browser.
                "https://example.com/notes" to RaftArtifactViewerBlockedTarget.SystemBrowser("https://example.com/notes"),
                "https://w-x.w-staging.raftusercontent.com/page" to
                    RaftArtifactViewerBlockedTarget.SystemBrowser("https://w-x.w-staging.raftusercontent.com/page"),
                // On it (Open in new page, a link in a comment): this Artifact stays, another opens in the viewer,
                // any other safe own-origin page opens in the authenticated in-app WebView.
                "$web/artifacts/${target.id}" to RaftArtifactViewerBlockedTarget.Ignore,
                "$web/artifacts/$other/v/2" to RaftArtifactViewerBlockedTarget.OpenArtifact(ArtifactLinkTarget(other, 2)),
                "$web/s/acme/artifacts/$other" to RaftArtifactViewerBlockedTarget.OpenArtifact(ArtifactLinkTarget(other)),
                "$web/s/acme" to RaftArtifactViewerBlockedTarget.RaftWebPage("$web/s/acme"),
                "$web/_raft/callback" to RaftArtifactViewerBlockedTarget.SystemBrowser("$web/_raft/callback"),
                "$web/s/acme/tasks?ticket=secret" to RaftArtifactViewerBlockedTarget.SystemBrowser("$web/s/acme/tasks?ticket=secret"),
                // A file the page saves (Download, Download all, a Files row): the WebView can't save it.
                "blob:$web/0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0" to RaftArtifactViewerBlockedTarget.Download,
                "blob:https://w-x.w-staging.raftusercontent.com/0f1e2d3c" to RaftArtifactViewerBlockedTarget.Download,
                "$web/api/artifacts/${target.id}/versions/3/zip" to RaftArtifactViewerBlockedTarget.Download,
                // Not a page.
                "" to RaftArtifactViewerBlockedTarget.Ignore,
                "javascript:alert(1)" to RaftArtifactViewerBlockedTarget.Ignore,
                "intent://x#Intent;end" to RaftArtifactViewerBlockedTarget.Ignore,
            )
        for ((url, expected) in cases) {
            assertEquals(
                expected,
                raftArtifactViewerBlockedNavigationTarget(url, web, target.id, blockAppStoreBilling = false),
                url,
            )
        }
        // On iOS (App Store rules), general own-origin pages do not open in the new authenticated WebView.
        assertEquals(
            RaftArtifactViewerBlockedTarget.SystemBrowser("$web/s/acme"),
            raftArtifactViewerBlockedNavigationTarget("$web/s/acme", web, target.id, blockAppStoreBilling = true),
        )
        assertEquals(
            RaftArtifactViewerBlockedTarget.SystemBrowser("$web/servers/srv-1/upgrade"),
            raftArtifactViewerBlockedNavigationTarget("$web/servers/srv-1/upgrade", web, target.id, blockAppStoreBilling = true),
        )
        assertTrue(
            raftArtifactViewerRoutesDocumentAsLink("$web/servers/srv-1/upgrade", web, target.id, emptySet(), blockAppStoreBilling = true),
        )
        assertTrue(
            raftArtifactViewerRoutesDocumentAsLink("https://checkout.stripe.com/c/pay/cs_1", web, target.id, emptySet(), blockAppStoreBilling = true),
        )
    }

    @Test
    fun raftLinksInThePageOpenInTheAppAndTheViewerStays() {
        // The web opens a page's link in a new tab, its viewer still there; here the app opens it over the viewer.
        val cid = "c0c0c0c0-0000-4000-8000-000000000001"
        val mid = "a0a0a0a0-0000-4000-8000-000000000009"
        val tid = "b0b0b0b0-0000-4000-8000-000000000007"
        val inApp =
            listOf(
                // A conversation, a channel alone, a task's conversation (slock #14960 & #16576), a thread.
                "$web/s/acme/channel/$cid?msg=$mid",
                "$web/s/acme/channel/$cid",
                "$web/s/acme/dm/$cid?thread=$cid%3A$mid",
                "$web/s/acme/channel/$cid?msg=$mid&task=$cid%3Atask%3A$tid",
                "$web/s/acme/dm/$cid?msg=$mid&task=$cid%3Atask%3A$tid",
                "$web/s/acme/channel/$cid?task=$cid%3Atask%3A$tid",
                // On another of Raft's own origins (production's hosts; one written with its dot): before, the browser.
                "https://app.raft.build/s/acme/channel/$cid?msg=$mid",
                "https://app.raft.build/s/acme/channel/$cid?msg=$mid&task=$cid%3Atask%3A$tid",
                "https://open.raft.build/s/acme/channel/$cid",
                "https://app.raft.build./s/acme/channel/$cid",
                // Join and invite links: their pages in the app.
                "$web/join/tok-1",
                "https://app.raft.build/external-invite/inv-1",
            )
        for (url in inApp) {
            assertEquals(RaftArtifactViewerBlockedTarget.OpenMessage(url), raftArtifactViewerBlockedNavigationTarget(url, web, target.id, emptySet()), url)
            assertTrue(raftArtifactViewerRoutesDocumentAsLink(url, web, target.id, emptySet()), url)
        }
        // Another Artifact on another Raft origin: the viewer; this one: nothing.
        val other = "pt_0b6f1c2e-5d4a-4c3b-9a8e-7f6d5c4b3a21"
        assertEquals(
            RaftArtifactViewerBlockedTarget.OpenArtifact(ArtifactLinkTarget(other)),
            raftArtifactViewerBlockedNavigationTarget("https://app.raft.build/artifacts/$other", web, target.id, emptySet()),
        )
        assertEquals(
            RaftArtifactViewerBlockedTarget.Ignore,
            raftArtifactViewerBlockedNavigationTarget("https://app.raft.build/artifacts/${target.id}", web, target.id, emptySet()),
        )
        // Same-origin Raft pages without a native screen (including non-`:task:` `?task=` web panels)
        // open in the authenticated in-app WebView on Android/Desktop; cross-origin Raft pages stay
        // in the system browser so tokens never leave the active environment.
        for (url in listOf("$web/s/acme/tasks", "$web/s/acme/settings/theme", "$web/s/acme/channel/$cid?task=1", "$web/s/acme/channel/$cid?task=$cid%3A$mid")) {
            assertEquals(
                RaftArtifactViewerBlockedTarget.RaftWebPage(url),
                raftArtifactViewerBlockedNavigationTarget(url, web, target.id, emptySet(), blockAppStoreBilling = false),
                url,
            )
            assertEquals(
                RaftArtifactViewerBlockedTarget.SystemBrowser(url),
                raftArtifactViewerBlockedNavigationTarget(url, web, target.id, emptySet(), blockAppStoreBilling = true),
                "iOS keeps existing behavior for $url",
            )
            assertTrue(raftArtifactViewerRoutesDocumentAsLink(url, web, target.id, emptySet(), blockAppStoreBilling = false), url)
        }
        val otherOriginWebPage = "https://app.raft.build/s/acme/agent/a1"
        assertEquals(
            RaftArtifactViewerBlockedTarget.SystemBrowser(otherOriginWebPage),
            raftArtifactViewerBlockedNavigationTarget(otherOriginWebPage, web, target.id, emptySet()),
        )
        assertTrue(raftArtifactViewerRoutesDocumentAsLink(otherOriginWebPage, web, target.id, emptySet()))
        // Full-document links to another Artifact (e.g. a bare `<a href="/artifacts/…">` in "Linked from")
        // or an un-embedded reload of this Artifact are routed instead of replacing the embedded WebView
        // document (which would latch `NOT_EMBEDDED` for the document's lifetime).
        for (
            url in listOf(
                "$web/artifacts/$other",
                "$web/artifacts/$other/v/2",
                "$web/s/acme/artifacts/$other",
                "$web/artifacts/$other?${raftArtifactViewerEmbedQuery()}",
                "$web/artifacts/${target.id}",
                "$web/artifacts/${target.id}/v/2",
            )
        ) {
            assertTrue(raftArtifactViewerRoutesDocumentAsLink(url, web, target.id, emptySet()), url)
        }
        // What the viewer's own document loads stays its own: an embedded load/reload of this Artifact,
        // the warm route, the page signing itself out (`/`, `/login`), the server after a Delete, and
        // anything off Raft (handled by the origin lock).
        for (
            url in listOf(
                "$web/artifacts/${target.id}?${raftArtifactViewerEmbedQuery()}",
                raftArtifactViewerWarmUrl(web),
                "$web/",
                "$web/login",
                "$web/s/acme",
                "https://example.com/a",
            )
        ) {
            assertFalse(raftArtifactViewerRoutesDocumentAsLink(url, web, target.id, emptySet()), url)
        }
    }
}
