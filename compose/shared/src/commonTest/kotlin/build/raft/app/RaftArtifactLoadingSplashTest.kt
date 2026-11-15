package build.raft.app

import build.raft.app.data.ArtifactKind
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * #301: the App's Artifact loading splash is the web's (botiverse/slock
 * `packages/web/src/apps/artifacts/artifactSplash.ts` at staging 6466423c6143b64d42f19f62f928100a136a2776).
 * The cell vectors below were computed by that file's own `artifactSplashCells` (node), so the App's
 * icon assembles cell for cell as the page's does.
 */
class RaftArtifactLoadingSplashTest {
    private fun close(expected: Double, actual: Double) = assertTrue(abs(expected - actual) < 1e-12, "expected $expected, got $actual")

    @Test
    fun cellsMatchTheWebsAlgorithm() {
        data class Vector(val icon: RaftArtifactSplashIcon, val count: Int, val first: RaftArtifactSplashCell, val last: RaftArtifactSplashCell, val fillSum: Long)
        val vectors =
            listOf(
                Vector(
                    RaftArtifactSplashIcon.Web, 42,
                    RaftArtifactSplashCell(0, 0, 0.052531784763559713, 0.8283733161748387, 29),
                    RaftArtifactSplashCell(7, 7, 0.22214606406167148, 0.8064968270133249, 24),
                    2384,
                ),
                Vector(
                    RaftArtifactSplashIcon.Doc, 32,
                    RaftArtifactSplashCell(0, 0, 0.052531784763559713, 0.8283733161748387, 29),
                    RaftArtifactSplashCell(7, 6, 0.18933063488453628, 0.7357569735846482, 32),
                    2040,
                ),
                Vector(
                    RaftArtifactSplashIcon.Img, 32,
                    RaftArtifactSplashCell(1, 0, 0.0750317847635597, 0.8283733161748387, 29),
                    RaftArtifactSplashCell(7, 7, 0.18933063488453628, 0.7357569735846482, 32),
                    2040,
                ),
            )
        for (v in vectors) {
            val cells = raftArtifactSplashCells(v.icon)
            assertEquals(v.count, cells.size, "${v.icon}")
            for ((expected, actual) in listOf(v.first to cells.first(), v.last to cells.last())) {
                assertEquals(expected.row to expected.col, actual.row to actual.col, "${v.icon}")
                close(expected.on, actual.on)
                close(expected.off, actual.off)
                assertEquals(expected.fillMs, actual.fillMs, "${v.icon}")
            }
            assertEquals(v.fillSum, cells.sumOf { it.fillMs }, "${v.icon}")
        }
        // The second cell of the web icon, too (the generator's second draw).
        val second = raftArtifactSplashCells(RaftArtifactSplashIcon.Web)[1]
        close(0.13474816659465436, second.on)
        close(0.8317453647847287, second.off)
        assertEquals(1L, second.fillMs)
    }

    @Test
    fun theIconFollowsTheKindAsOnTheWeb() {
        assertEquals(RaftArtifactSplashIcon.Web, raftArtifactSplashIcon(ArtifactKind.Html))
        assertEquals(RaftArtifactSplashIcon.Img, raftArtifactSplashIcon(ArtifactKind.Image))
        assertEquals(RaftArtifactSplashIcon.Img, raftArtifactSplashIcon(ArtifactKind.Video))
        for (kind in listOf(ArtifactKind.Markdown, ArtifactKind.Text, ArtifactKind.Csv, ArtifactKind.Pdf, ArtifactKind.Other, null)) {
            assertEquals(RaftArtifactSplashIcon.Doc, raftArtifactSplashIcon(kind), "$kind")
        }
    }

    @Test
    fun theCycleStartsAsItAppears() {
        assertEquals(0L, raftArtifactSplashPhase(RAFT_ARTIFACT_SPLASH_APPEAR_AFTER_MS))
        assertEquals(1_200L, raftArtifactSplashPhase(RAFT_ARTIFACT_SPLASH_APPEAR_AFTER_MS + 1_200))
        assertEquals(100L, raftArtifactSplashPhase(RAFT_ARTIFACT_SPLASH_APPEAR_AFTER_MS + RAFT_ARTIFACT_SPLASH_CYCLE_MS + 100))
        // Before it appears (a negative elapsed) the phase still wraps into the cycle.
        assertEquals(RAFT_ARTIFACT_SPLASH_CYCLE_MS - 500, raftArtifactSplashPhase(500))
    }

    @Test
    fun framesWaitThenLoopThenEnd() {
        val icon = RaftArtifactSplashIcon.Web
        val cells = raftArtifactSplashCells(icon)
        // Nothing for the first second.
        assertEquals(RaftArtifactSplashFrame.Waiting, raftArtifactSplashFrame(icon, 999))
        // The loop: in the hold (40–60%) every cell is lit, in the rest (85–100%) none.
        val hold = raftArtifactSplashFrame(icon, RAFT_ARTIFACT_SPLASH_APPEAR_AFTER_MS + RAFT_ARTIFACT_SPLASH_CYCLE_MS / 2)
        assertIs<RaftArtifactSplashFrame.Icon>(hold)
        assertTrue(hold.lit.all { it }, "every cell lit in the hold")
        val rest = raftArtifactSplashFrame(icon, RAFT_ARTIFACT_SPLASH_APPEAR_AFTER_MS + RAFT_ARTIFACT_SPLASH_CYCLE_MS * 95 / 100)
        assertIs<RaftArtifactSplashFrame.Icon>(rest)
        assertTrue(rest.lit.none { it }, "none lit in the rest")
        assertEquals(cells.size, rest.lit.size)
        // Loaded before it appeared: gone at once, no ending.
        assertEquals(RaftArtifactSplashFrame.Gone, raftArtifactSplashFrame(icon, 800, loadedAfterMs = 700))
        // Loaded after: fill, hold, fade (opacity going down), gone.
        val loaded = RAFT_ARTIFACT_SPLASH_APPEAR_AFTER_MS + 100
        val filled = raftArtifactSplashFrame(icon, loaded + RAFT_ARTIFACT_SPLASH_FILL_MS, loadedAfterMs = loaded)
        assertIs<RaftArtifactSplashFrame.Icon>(filled)
        assertTrue(filled.lit.all { it } && filled.opacity == 1f)
        val fading = raftArtifactSplashFrame(icon, loaded + RAFT_ARTIFACT_SPLASH_FILL_MS + RAFT_ARTIFACT_SPLASH_HOLD_MS + 60, loadedAfterMs = loaded)
        assertIs<RaftArtifactSplashFrame.Icon>(fading)
        assertTrue(fading.opacity in 0f..0.99f, "fading: ${fading.opacity}")
        val end = RAFT_ARTIFACT_SPLASH_FILL_MS + RAFT_ARTIFACT_SPLASH_HOLD_MS + RAFT_ARTIFACT_SPLASH_FADE_MS
        assertEquals(RaftArtifactSplashFrame.Gone, raftArtifactSplashFrame(icon, loaded + end, loadedAfterMs = loaded))
        // Reduced motion: complete and still; loaded removes it at once.
        val still = raftArtifactSplashFrame(icon, 5_000, reducedMotion = true)
        assertIs<RaftArtifactSplashFrame.Icon>(still)
        assertTrue(still.lit.all { it })
        assertEquals(RaftArtifactSplashFrame.Gone, raftArtifactSplashFrame(icon, 5_000, loadedAfterMs = 4_000, reducedMotion = true))
    }

    @Test
    fun easeOutIsRaftDesigns() {
        assertTrue(raftArtifactSplashEaseOut(0f) < 1e-3f)
        assertTrue(abs(1f - raftArtifactSplashEaseOut(1f)) < 1e-3f)
        // ease-out runs ahead of linear.
        assertTrue(raftArtifactSplashEaseOut(0.5f) > 0.5f)
    }

    @Test
    fun oneLoaderAtATime() {
        fun state(
            sessionRead: Boolean = true,
            documentShown: Boolean = false,
            pageDrewItself: Boolean = false,
            keptShowsTarget: Boolean = false,
            loadError: Boolean = false,
            sinceDocumentReadyMs: Long? = null,
            reloading: Boolean = false,
        ) = raftArtifactViewerLoadingIndicator(
            RaftArtifactViewerOpenState(sessionRead, documentShown, pageDrewItself, keptShowsTarget, loadError, sinceDocumentReadyMs, reloading)
        )
        val app = RaftArtifactViewerLoadingIndicator.AppSplash
        val none = RaftArtifactViewerLoadingIndicator.None
        // Cold open: the App's splash through the session read, the WebView's load and the page's boot...
        assertEquals(app, state(sessionRead = false))
        assertEquals(app, state())
        assertEquals(app, state(documentShown = true))
        assertEquals(app, state(documentShown = true, sinceDocumentReadyMs = 1_000))
        // ...until the page drew itself: then the page's own splash (same clock) or its content, alone.
        assertEquals(none, state(documentShown = true, pageDrewItself = true, sinceDocumentReadyMs = 100))
        // Warm: a kept page already showing it: nothing of the App's.
        assertEquals(none, state(documentShown = true, keptShowsTarget = true))
        // A kept page routing to it: the App's splash until the page drew itself.
        assertEquals(app, state(documentShown = true, keptShowsTarget = false))
        // A fresh reload between WebViews: the App's splash.
        assertEquals(app, state(reloading = true))
        // The error screen replaces it; a page that never says it drew itself is shown after the fallback.
        assertEquals(none, state(documentShown = true, loadError = true))
        assertEquals(none, state(documentShown = true, sinceDocumentReadyMs = RAFT_ARTIFACT_VIEWER_SPLASH_FALLBACK_MS))
        // Every state yields exactly one owner of the loader: the App's splash covers the page, or the page shows.
        for (shown in listOf(false, true)) for (drew in listOf(false, true)) for (kept in listOf(false, true)) for (error in listOf(false, true)) {
            val result = state(documentShown = shown, pageDrewItself = drew, keptShowsTarget = kept, loadError = error)
            assertTrue(result == app || result == none)
            if (!shown && !error) assertEquals(app, result, "nothing shown yet: the App's splash, never a blank page")
        }
    }

    @Test
    fun desktopModeDrawsItAsSmallAsThePageDoes() {
        assertEquals(1f, raftArtifactViewerSplashScale(desktopMode = false, widthDp = 400f))
        assertEquals(400f / RaftFilePreviewDesktopViewportWidth, raftArtifactViewerSplashScale(desktopMode = true, widthDp = 400f))
    }
}
