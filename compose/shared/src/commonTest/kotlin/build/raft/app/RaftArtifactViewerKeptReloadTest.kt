package build.raft.app

import build.raft.app.data.ArtifactLinkTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Hands dd109a01: a kept viewer document reused after the session changed while it was parked
 * routed itself to the web root and the viewer closed 0.8 s after it opened. A reused kept page
 * that wanders off reloads once in a fresh WebView; the page's own Back still closes at once; a
 * kept page parked under another session is not reused at all.
 */
class RaftArtifactViewerKeptReloadTest {
    private val web = "https://app.raft.build"
    private val id = "pt_c82e597f-5434-4741-909b-d1c8a7a531c8"
    private val scope = RaftArtifactViewerScope(web, "https://api.raft.build/api", "user-maya", "server-northwind")
    private val target = ArtifactLinkTarget(id)

    private fun action(url: String, reusedKept: Boolean = true, sinceShownMs: Long = 830, reloadAvailable: Boolean = true) =
        raftArtifactViewerLeaveAction(raftArtifactViewerNavigation(url, web, id), url, reusedKept, sinceShownMs, reloadAvailable)

    @Test
    fun aReusedKeptPageThatGoesToTheWebRootReloadsInsteadOfClosing() {
        assertEquals(RaftArtifactViewerNavigation.Close, raftArtifactViewerNavigation("$web/", web, id))
        assertEquals(RaftArtifactViewerLeaveAction.ReloadFresh, action("$web/"))
        assertEquals(RaftArtifactViewerLeaveAction.ReloadFresh, action("$web/s/northwind"))
    }

    @Test
    fun aSecondSelfNavigationCloses() {
        // The one fresh load of this open is used: close as before.
        assertEquals(RaftArtifactViewerLeaveAction.Leave, action("$web/", reloadAvailable = false))
        // The fresh WebView is no kept page: it closes when it leaves.
        assertEquals(RaftArtifactViewerLeaveAction.Leave, action("$web/", reusedKept = false))
        // Long after it was shown it is the user's doing (Delete routes to the server): close.
        assertEquals(
            RaftArtifactViewerLeaveAction.Leave,
            action("$web/", sinceShownMs = RAFT_ARTIFACT_VIEWER_KEPT_SELF_NAVIGATION_WINDOW_MS + 1),
        )
    }

    @Test
    fun thePagesOwnBackClosesImmediately() {
        val back = "$web/artifacts/$id?embed=raft-settings-v1&shell=host&back=host&hostMenu=desktop-mode,open-in-browser$RAFT_ARTIFACT_VIEWER_CLOSE_FRAGMENT"
        assertEquals(RaftArtifactViewerNavigation.Close, raftArtifactViewerNavigation(back, web, id))
        assertEquals(RaftArtifactViewerLeaveAction.Leave, action(back))
        assertEquals(RaftArtifactViewerLeaveAction.Leave, action("$web/$RAFT_ARTIFACT_VIEWER_CLOSE_FRAGMENT", sinceShownMs = 0))
    }

    @Test
    fun aMessageOrAnotherArtifactStillLeaves() {
        val other = "pt_0b6f1c2e-5d4a-4c3b-9a8e-7f6d5c4b3a21"
        assertEquals(RaftArtifactViewerLeaveAction.Leave, action("$web/artifacts/$other"))
    }

    @Test
    fun aTokenChangeMakesTheKeptPageUnusable() {
        var drops = 0
        val cache = RaftArtifactViewerCache(onKeptDocumentDropped = { drops += 1 })
        assertTrue(cache.rememberKeptDocument(scope, target, raftArtifactViewerEmbeddedPageUrl(web, target), "token-a"))
        assertTrue(cache.keptDocumentUsable(scope, "token-a"))
        // Refreshed while parked: not reused.
        assertFalse(cache.keptDocumentUsable(scope, "token-b"))
        // A rotation synced into the shown page: parked with the new one.
        cache.noteKeptSession(scope, "token-b")
        assertTrue(cache.keptDocumentUsable(scope, "token-b"))
        assertFalse(cache.keptDocumentUsable(scope, "token-a"))
        // A prewarmed one carries its session too.
        cache.markKeptWarm(scope, "token-c")
        assertTrue(cache.keptDocumentUsable(scope, "token-c"))
        assertFalse(cache.keptDocumentUsable(scope, "token-b"))
        // Dropped: nothing usable until a document is kept again.
        cache.forgetKeptDocument(scope)
        assertFalse(cache.keptDocumentUsable(scope, "token-c"))
        assertEquals(1, drops)
        // Another subject's note touches nothing.
        cache.noteKeptSession(scope.copy(accountId = "user-other"), "token-c")
        assertFalse(cache.keptDocumentUsable(scope, "token-c"))
    }

    @Test
    fun logDestinationsCarryThePathOnly() {
        assertEquals("/", raftArtifactViewerLogDestination("$web/"))
        assertEquals("/", raftArtifactViewerLogDestination(web))
        assertEquals("/s/northwind", raftArtifactViewerLogDestination("$web/s/northwind?token=secret&code=abc#x=1"))
        assertEquals("/", raftArtifactViewerLogDestination("$web?access_token=secret"))
        assertEquals(RAFT_ARTIFACT_VIEWER_CLOSE_FRAGMENT, raftArtifactViewerLogDestination("$web/artifacts/$id?ticket=t$RAFT_ARTIFACT_VIEWER_CLOSE_FRAGMENT"))
        assertFalse("secret" in raftArtifactViewerLogDestination("$web/s/x?token=secret"))
    }
}
