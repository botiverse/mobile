package build.raft.app

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #301: the App's Artifact loading splash is the web's (botiverse/slock
 * `packages/web/src/apps/artifacts/artifactSplash.ts` at staging 6466423c6143b64d42f19f62f928100a136a2776).
 * The cell vectors below were computed by that file's own `artifactSplashCells` (node), so the App's
 * icon assembles cell for cell as the page's does.
 */
class RaftArtifactLoadingSplashTest {
    @Test
    fun oneLoaderAtATime() {
        fun state(
            sessionRead: Boolean = true,
            documentShown: Boolean = false,
            reusesKeptPage: Boolean = false,
            loadError: Boolean = false,
            sinceDocumentReadyMs: Long? = null,
            reloading: Boolean = false,
            pageLoaded: Boolean = false,
            splashEnded: Boolean = false,
        ) = raftArtifactViewerLoadingIndicator(
            RaftArtifactViewerOpenState(sessionRead, documentShown, reusesKeptPage, loadError, sinceDocumentReadyMs, reloading, pageLoaded, splashEnded)
        )
        val app = RaftArtifactViewerLoadingIndicator.AppSplash
        val none = RaftArtifactViewerLoadingIndicator.None
        // Cold open: the App's splash through the session read, the WebView's load and the page's boot...
        assertEquals(app, state(sessionRead = false))
        assertEquals(app, state())
        assertEquals(app, state(documentShown = true))
        assertEquals(app, state(documentShown = true, sinceDocumentReadyMs = 1_000))
        // ...until the page says its content loaded (`hostLoaded=1`): the App's splash plays its ending, then the page alone.
        assertEquals(app, state(documentShown = true, sinceDocumentReadyMs = 100, pageLoaded = true))
        assertEquals(none, state(documentShown = true, sinceDocumentReadyMs = 100, pageLoaded = true, splashEnded = true))
        // The ending is not cut by the fallback.
        assertEquals(app, state(documentShown = true, sinceDocumentReadyMs = RAFT_ARTIFACT_VIEWER_SPLASH_FALLBACK_MS, pageLoaded = true))
        // An older web never says so: shown at the fallback (it drew its bar by then), not before.
        assertEquals(app, state(documentShown = true, sinceDocumentReadyMs = RAFT_ARTIFACT_VIEWER_SPLASH_FALLBACK_MS - 1))
        assertEquals(none, state(documentShown = true, sinceDocumentReadyMs = RAFT_ARTIFACT_VIEWER_SPLASH_FALLBACK_MS))
        // Warm: a kept page reused that showed an Artifact before (it drew its bar, `hostToolbar=1`): nothing of
        // the App's, it sends no `hostLoaded` then (slock #15036), and its own splash shows under its own bar.
        assertEquals(none, state(documentShown = true, reusesKeptPage = true))
        assertEquals(none, state(documentShown = false, reusesKeptPage = true))
        // A kept page only booted (prewarm) is covered as a fresh load: its loading row never shows under the App's bar.
        assertEquals(app, state(documentShown = true, reusesKeptPage = false))
        // A fresh reload or a session recovery, between WebViews: the App's splash.
        assertEquals(app, state(reloading = true))
        assertEquals(app, state(reloading = true, documentShown = true, pageLoaded = true, splashEnded = true))
        // The error screen replaces it.
        assertEquals(none, state(documentShown = true, loadError = true))
        // Every state yields exactly one owner of the loader: the App's splash covers the page, or the page shows.
        for (shown in listOf(false, true)) for (loaded in listOf(false, true)) for (kept in listOf(false, true)) for (error in listOf(false, true)) {
            val result = state(documentShown = shown, pageLoaded = loaded, reusesKeptPage = kept, loadError = error)
            assertTrue(result == app || result == none)
            if (!shown && !error && !kept) assertEquals(app, result, "a fresh load with nothing shown yet: the App's splash, never a blank page")
        }
    }

    @Test
    fun theCoverGoesOnceThePageDrawsItsBar() {
        // Android 1.26: the page's ⋯ opened under the App's cover, which stayed under the page's bar until `hostLoaded=1`
        // (or the 4 s fallback): only the menu's top edge showed. The page's bar hands the body over to the page.
        fun state(pageDrawsBar: Boolean, pageLoaded: Boolean = false, sinceDocumentReadyMs: Long? = 500, reloading: Boolean = false) =
            raftArtifactViewerLoadingIndicator(
                RaftArtifactViewerOpenState(
                    sessionRead = true,
                    documentShown = true,
                    reusesKeptPage = false,
                    loadError = false,
                    sinceDocumentReadyMs = sinceDocumentReadyMs,
                    reloading = reloading,
                    pageLoaded = pageLoaded,
                    pageDrawsBar = pageDrawsBar,
                )
            )
        // Under the App's bar the cover stays (the page's loading row, Back alone, never shows under it; no white page).
        assertEquals(RaftArtifactViewerLoadingIndicator.AppSplash, state(pageDrawsBar = false))
        assertEquals(RaftArtifactViewerLoadingIndicator.AppSplash, state(pageDrawsBar = false, sinceDocumentReadyMs = null))
        // The page draws the only bar: its own splash and menus, at once, not at `hostLoaded=1` or the fallback.
        assertEquals(RaftArtifactViewerLoadingIndicator.None, state(pageDrawsBar = true))
        assertEquals(RaftArtifactViewerLoadingIndicator.None, state(pageDrawsBar = true, sinceDocumentReadyMs = null))
        assertEquals(RaftArtifactViewerLoadingIndicator.None, state(pageDrawsBar = true, pageLoaded = true))
        // Between WebViews the App's splash stays.
        assertEquals(RaftArtifactViewerLoadingIndicator.AppSplash, state(pageDrawsBar = true, reloading = true))
    }

    @Test
    fun onlyAKeptPageThatDrewItsBarIsReusedUncovered() {
        // #301, the double bar: the prewarmed document on the warm route has drawn no bar and shown nothing.
        assertFalse(raftArtifactViewerKeptPageDrewBar(null))
        assertFalse(
            raftArtifactViewerKeptPageDrewBar(
                "https://raft.build/artifacts/raft-app-warm?embed=raft-settings-v1&shell=host&back=host&hostMenu=desktop-mode,open-in-browser,copy-link,share-link",
            )
        )
        // One that showed an Artifact keeps `hostToolbar=1` in its address (the web's keeper keeps it).
        assertTrue(
            raftArtifactViewerKeptPageDrewBar(
                "https://raft.build/artifacts/pt_a1?embed=raft-settings-v1&shell=host&back=host&hostMenu=desktop-mode,open-in-browser,copy-link,share-link&hostToolbar=1#panel=none",
            )
        )
    }

    @Test
    fun theCoverHidesTheWholeBodyUnderTheAppsBarAndLeavesThePagesBarOnceItDrawsIt() {
        // Under the App's bar: the page's own loading toolbar row is covered too (no second bar).
        assertEquals(0.dp, raftArtifactViewerSplashCoverTop(pageDrawsBar = false, barHeight = 56.dp))
        // The page draws the only bar: its bar shows, where the App's was (the splash does not move).
        assertEquals(56.dp, raftArtifactViewerSplashCoverTop(pageDrawsBar = true, barHeight = 56.dp))
        // Desktop Mode: the page is fitted, its bar too.
        assertEquals(28.dp, raftArtifactViewerSplashCoverTop(pageDrawsBar = true, barHeight = 56.dp, scale = 0.5f))
    }
}
