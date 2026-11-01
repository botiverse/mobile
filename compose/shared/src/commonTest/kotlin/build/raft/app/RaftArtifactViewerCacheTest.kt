package build.raft.app

import build.raft.app.data.ArtifactKind
import build.raft.app.data.ArtifactLinkTarget
import build.raft.app.data.AuthenticatedRouteOwner
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
    fun anUnknownSubjectReadsAndKeepsNothing() {
        val cache = cache()
        cache.remember(null, header)
        assertNull(cache.header(null, target.id))
        assertFalse(cache.rememberKeptDocument(null, target, pageUrl))
        assertFalse(cache.keptDocumentUsable(null))
        assertFalse(cache.keptDocumentShows(null, target))

        cache.remember(maya, header)
        assertNull(cache.header(null, target.id))
        assertNull(RaftArtifactViewerScope.of(web, null))
        assertNull(RaftArtifactViewerScope.of(web, AuthenticatedRouteOwner(env, "", "server")))
        assertNull(RaftArtifactViewerScope.of(web, AuthenticatedRouteOwner(env, "user", " ")))
        assertNull(RaftArtifactViewerScope.of("not a url", AuthenticatedRouteOwner(env, "user", "server")))
    }

    @Test
    fun aSwitchOfAccountOrServerClearsEverythingAndDropsTheKeptDocument() {
        val drops = mutableListOf<Unit>()
        val cache = cache(drops)
        cache.remember(maya, header)
        assertTrue(cache.rememberKeptDocument(maya, target, pageUrl))
        drops.clear()

        cache.remember(someoneElse, header.copy(title = "Theirs"))
        assertTrue(drops.isNotEmpty(), "the kept WebView goes with the old subject")
        assertFalse(cache.keptDocumentShows(maya, target))
        assertFalse(cache.keptDocumentShows(someoneElse, target))
        assertNull(cache.header(maya, target.id))

        cache.markKeptWarm(mayaOtherServer)
        assertNull(cache.header(someoneElse, target.id))
        assertTrue(cache.keptDocumentUsable(mayaOtherServer))
        assertFalse(cache.keptDocumentUsable(someoneElse))
    }

    @Test
    fun signOutClearsEverything() {
        val drops = mutableListOf<Unit>()
        val cache = cache(drops)
        cache.remember(maya, header)
        cache.rememberKeptDocument(maya, target, pageUrl)
        drops.clear()
        cache.clear()
        assertNull(cache.header(maya, target.id))
        assertFalse(cache.keptDocumentUsable(maya))
        assertEquals(1, drops.size)
    }

    @Test
    fun forbiddenOrNotFoundDropsTheCachedCopyAndTheKeptDocument() {
        val drops = mutableListOf<Unit>()
        val cache = cache(drops)
        cache.remember(maya, header)
        assertTrue(cache.rememberKeptDocument(maya, target, pageUrl))
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
            assertFalse(cache.rememberKeptDocument(maya, target, url), url)
            assertFalse(cache.keptDocumentShows(maya, target), url)
        }
        assertTrue(cache.rememberKeptDocument(maya, target, pageUrl))
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
    fun blockedTopLevelNavigationGoesToTheBrowserOnlyForWebPages() {
        assertEquals(RaftArtifactViewerBlockedTarget.SystemBrowser, raftArtifactViewerBlockedNavigationTarget("https://example.com/notes", web))
        assertEquals(
            RaftArtifactViewerBlockedTarget.SystemBrowser,
            raftArtifactViewerBlockedNavigationTarget("https://w-x.w-staging.raftusercontent.com/page", web),
        )
        assertEquals(RaftArtifactViewerBlockedTarget.Ignore, raftArtifactViewerBlockedNavigationTarget("", web))
        assertEquals(RaftArtifactViewerBlockedTarget.Ignore, raftArtifactViewerBlockedNavigationTarget("javascript:alert(1)", web))
        assertEquals(RaftArtifactViewerBlockedTarget.Ignore, raftArtifactViewerBlockedNavigationTarget("intent://x#Intent;end", web))
    }

    @Test
    fun webOrigins() {
        assertEquals(web, raftWebOriginOf("$web/artifacts/pt_x?a=1#b"))
        assertEquals(web, raftWebOriginOf("HTTPS://Raft-App-Staging.Botiverse.dev:443/"))
        assertEquals("https://h.test:8443", raftWebOriginOf("https://h.test:8443/x"))
        assertNull(raftWebOriginOf("https://user@h.test/"))
        assertNull(raftWebOriginOf("javascript:alert(1)"))
        assertNull(raftWebOriginOf("https://h.test:99999/"))
        assertTrue(raftArtifactViewerDocumentUrlIsKeepable(web, pageUrl))
        assertFalse(raftArtifactViewerDocumentUrlIsKeepable(web, "https://w.raftusercontent.com/x"))
    }

    @Test
    fun viewerAddresses() {
        assertEquals("$web/artifacts/${target.id}", raftArtifactViewerPageUrl(web, target))
        assertEquals("$web/artifacts/${target.id}/v/3", raftArtifactViewerPageUrl(web, ArtifactLinkTarget(target.id, 3)))
        assertEquals("$web/artifacts/$RAFT_ARTIFACT_VIEWER_WARM_SEGMENT?embed=raft-settings-v1&shell=host&chrome=none", raftArtifactViewerWarmUrl("$web/"))
        // Route data is identity only: the Artifact and its pinned version, never its title or kind.
        val pageData = raftArtifactViewerPageData(ArtifactLinkTarget(target.id, 3))
        assertEquals(ArtifactLinkTarget(target.id, 3), pageData.raftArtifactViewerTargetOrNull())
        assertFalse(pageData.toString().contains(header.title))
        assertNull(build.raft.app.compat.json.JSONObject().put("artifactId", "https://evil.test/").raftArtifactViewerTargetOrNull())
        // A short id is route identity as written (slock staging 380482116 artifactShortId.ts).
        val short = ArtifactLinkTarget("2QthvfPeGfDnqwr7LG6itx", 2)
        assertEquals(short, raftArtifactViewerPageData(short).raftArtifactViewerTargetOrNull())
        assertNull(build.raft.app.compat.json.JSONObject().put("artifactId", "YcVfxkQb6JRzqk5kF2tNLw").raftArtifactViewerTargetOrNull())
    }
}
