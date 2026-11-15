package build.raft.app

import build.raft.app.data.ArtifactKind
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

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
        // Warm: a kept page reused (already showing it, or routed to it): nothing of the App's, it sends no
        // `hostLoaded` then (slock #15036), and its own splash shows.
        assertEquals(none, state(documentShown = true, reusesKeptPage = true))
        assertEquals(none, state(documentShown = false, reusesKeptPage = true))
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
    fun aKindLearnedMidCycleTakesOverAtTheNextCycle() {
        val appear = RAFT_ARTIFACT_SPLASH_APPEAR_AFTER_MS
        val cycle = RAFT_ARTIFACT_SPLASH_CYCLE_MS
        // No change since the opening: the kind as it is.
        assertTrue(raftArtifactSplashKindSwitched(changedAfterMs = null, elapsedMs = appear + 500))
        // Known before the icon appeared: drawn from the start.
        assertTrue(raftArtifactSplashKindSwitched(changedAfterMs = appear - 1, elapsedMs = appear))
        // Known 40% into the first cycle: that cycle ends with the document...
        val known = appear + cycle * 4 / 10
        assertEquals(false, raftArtifactSplashKindSwitched(known, elapsedMs = known))
        assertEquals(false, raftArtifactSplashKindSwitched(known, elapsedMs = appear + cycle - 1))
        // ...and the next starts with the new icon.
        assertTrue(raftArtifactSplashKindSwitched(known, elapsedMs = appear + cycle))
        assertTrue(raftArtifactSplashKindSwitched(known, elapsedMs = appear + cycle * 3))
        // Known in a later cycle: the same, from that cycle's end.
        val later = appear + cycle * 2 + 100
        assertEquals(false, raftArtifactSplashKindSwitched(later, elapsedMs = appear + cycle * 3 - 1))
        assertTrue(raftArtifactSplashKindSwitched(later, elapsedMs = appear + cycle * 3))
        // On a cycle's boundary: at once (web `artifactSplashUntilNextCycle` is 0 there).
        assertTrue(raftArtifactSplashKindSwitched(appear + cycle, elapsedMs = appear + cycle))
        // Not looping, as on the web: the ending (loaded) and reduced motion take the new kind at once.
        assertEquals(false, raftArtifactSplashKindSwitched(known, elapsedMs = known + 100, loadedAfterMs = known + 200))
        assertTrue(raftArtifactSplashKindSwitched(known, elapsedMs = known + 300, loadedAfterMs = known + 200))
        assertTrue(raftArtifactSplashKindSwitched(known, elapsedMs = known + 100, reducedMotion = true))
    }

    @Test
    fun theKindStateDrawsTheDocumentUntilTheCycleEnds() {
        val time = TestTimeSource()
        val openedAt = time.markNow()
        val kinds = RaftArtifactSplashKindState(initial = null, timeSource = time)
        fun shownAt(elapsedMs: Long): ArtifactKind? {
            time += (elapsedMs - openedAt.elapsedNow().inWholeMilliseconds).milliseconds
            return kinds.shownKind(openedAt, elapsedMs)
        }
        val appear = RAFT_ARTIFACT_SPLASH_APPEAR_AFTER_MS
        val cycle = RAFT_ARTIFACT_SPLASH_CYCLE_MS
        // Unknown: the document (the web's `artifactSplashIcon`).
        assertEquals(null, shownAt(appear + 300))
        assertEquals(RaftArtifactSplashIcon.Doc, raftArtifactSplashIcon(kinds.shownKind(openedAt, appear + 300)))
        // The card arrives mid-cycle: still the document until this cycle ends.
        kinds.update(ArtifactKind.Html, openedAt)
        assertEquals(ArtifactKind.Html, kinds.kind)
        assertEquals(null, shownAt(appear + 600))
        assertEquals(null, shownAt(appear + cycle - 1))
        assertEquals(ArtifactKind.Html, shownAt(appear + cycle))
        // The same kind again changes nothing.
        kinds.update(ArtifactKind.Html, openedAt)
        assertEquals(ArtifactKind.Html, shownAt(appear + cycle + 10))
        // Known before the icon appears (a new opening): drawn from the start.
        val reopened = time.markNow()
        val fresh = RaftArtifactSplashKindState(initial = null, timeSource = time)
        fresh.update(ArtifactKind.Image, reopened)
        time += (appear + 10).milliseconds
        assertEquals(ArtifactKind.Image, fresh.shownKind(reopened, appear + 10))
    }

    @Test
    fun desktopModeDrawsItAsSmallAsThePageDoes() {
        assertEquals(1f, raftArtifactViewerSplashScale(desktopMode = false, widthDp = 400f))
        assertEquals(400f / RaftFilePreviewDesktopViewportWidth, raftArtifactViewerSplashScale(desktopMode = true, widthDp = 400f))
    }
}
