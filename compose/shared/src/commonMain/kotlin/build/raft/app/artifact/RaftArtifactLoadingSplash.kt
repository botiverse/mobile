package build.raft.app

import androidx.compose.foundation.Canvas
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.data.ArtifactKind
import build.raft.designsystem.motion.rememberRaftReducedMotion
import kotlin.math.floor
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/*
 * The Artifact loading splash, ported from the web (#proj-workspace:#301): botiverse/slock
 * `packages/web/src/apps/artifacts/artifactSplash.ts` (parameters, icons, cells, clock) and
 * `ArtifactLoadingSplash.tsx` (the cover), the "像素拼图" trial of #proj-workspace:#145 that the
 * App mirrors. Pinned to slock staging 6466423c6143b64d42f19f62f928100a136a2776; the cell vectors in
 * RaftArtifactLoadingSplashTest were computed by that file's own algorithm.
 */

/** Nothing shows before this, from the opening (the Spinner's rule). */
internal const val RAFT_ARTIFACT_SPLASH_APPEAR_AFTER_MS = 1_000L

/** One cycle: assemble (0–40%), hold (40–60%), disperse (60–85%), rest (85–100%). */
internal const val RAFT_ARTIFACT_SPLASH_CYCLE_MS = 2_400L

/** On loaded: the cells still unlit fill in within this. */
internal const val RAFT_ARTIFACT_SPLASH_FILL_MS = 120L

/** On loaded: the complete icon holds this long once filled. */
internal const val RAFT_ARTIFACT_SPLASH_HOLD_MS = 240L

/** Then the cover fades out over this (raft-design `ease-out`). */
internal const val RAFT_ARTIFACT_SPLASH_FADE_MS = 120L

/** An unlit cell's opacity (the Spinner's). */
internal const val RAFT_ARTIFACT_SPLASH_DIM_OPACITY = 0.2f

/** The icon's grid: cells a side. */
internal const val RAFT_ARTIFACT_SPLASH_GRID = 8

/** The gap between cells (web px, here dp). */
internal val RaftArtifactSplashGap: Dp = 1.dp

/** A cell's size: the Artifact's own page (the web's `page` size; the App opens that page). */
internal val RaftArtifactSplashPixel: Dp = 5.dp

/** The gap between the icon and its line (web `gap-3.5`). */
internal val RaftArtifactSplashLineGap: Dp = 14.dp

/** The seed of the cells' order: every instance (and the web) assembles alike. */
internal const val RAFT_ARTIFACT_SPLASH_SEED = 7

internal enum class RaftArtifactSplashIcon { Web, Doc, Img }

/** `#` a cell of the icon, `.` none (the web's ARTIFACT_SPLASH_ICONS). */
internal val RaftArtifactSplashIcons: Map<RaftArtifactSplashIcon, List<String>> =
    mapOf(
        RaftArtifactSplashIcon.Web to listOf("########", "#.#.#..#", "########", "#......#", "#.####.#", "#......#", "#.##...#", "########"),
        RaftArtifactSplashIcon.Doc to listOf("#####...", "#...##..", "#....##.", "#.....#.", "#.###.#.", "#.....#.", "#.###.#.", "#######."),
        RaftArtifactSplashIcon.Img to listOf("........", "########", "#......#", "#....#.#", "#......#", "#..#...#", "#.###.##", "########"),
    )

/** The icon for a kind (web `artifactSplashIcon`): html a web page, image and video a picture, the rest (unknown too) a document. */
internal fun raftArtifactSplashIcon(kind: ArtifactKind?): RaftArtifactSplashIcon =
    when (kind) {
        ArtifactKind.Image, ArtifactKind.Video -> RaftArtifactSplashIcon.Img
        ArtifactKind.Html -> RaftArtifactSplashIcon.Web
        else -> RaftArtifactSplashIcon.Doc
    }

/** The web's seeded LCG (`s = (s * 1664525 + 1013904223) >>> 0; s / 2^32`), bit for bit. */
private fun raftArtifactSplashSeeded(seed: Int): () -> Double {
    var s = seed.toLong() and 0xFFFFFFFFL
    return {
        s = (s * 1664525L + 1013904223L) and 0xFFFFFFFFL
        s.toDouble() / 4294967296.0
    }
}

/** A cell of the icon; [on] / [off] are fractions of the cycle, [fillMs] when it fills in on loaded. */
internal data class RaftArtifactSplashCell(val row: Int, val col: Int, val on: Double, val off: Double, val fillMs: Long)

private val raftArtifactSplashCellCache = mutableMapOf<RaftArtifactSplashIcon, List<RaftArtifactSplashCell>>()

/** The icon's cells with their timing (web `artifactSplashCells`): by row a little (top first), mostly at random. */
internal fun raftArtifactSplashCells(icon: RaftArtifactSplashIcon): List<RaftArtifactSplashCell> =
    raftArtifactSplashCellCache.getOrPut(icon) {
        val random = raftArtifactSplashSeeded(RAFT_ARTIFACT_SPLASH_SEED)
        val fillRandom = raftArtifactSplashSeeded(RAFT_ARTIFACT_SPLASH_SEED + 1)
        buildList {
            RaftArtifactSplashIcons.getValue(icon).forEachIndexed { row, line ->
                line.forEachIndexed { col, char ->
                    if (char != '#') return@forEachIndexed
                    val on = (0.45 * (row.toDouble() / RAFT_ARTIFACT_SPLASH_GRID) + 0.55 * random()) * 0.4
                    val off = 0.6 + random() * 0.25
                    // JS Math.round: halves up.
                    add(RaftArtifactSplashCell(row, col, on, off, floor(fillRandom() * RAFT_ARTIFACT_SPLASH_FILL_MS + 0.5).toLong()))
                }
            }
        }
    }

/** Where in its cycle the icon is [elapsedMs] after the opening (web `artifactSplashPhase`): the cycle starts as it appears. */
internal fun raftArtifactSplashPhase(elapsedMs: Long): Long {
    val cycle = RAFT_ARTIFACT_SPLASH_CYCLE_MS
    return (((elapsedMs - RAFT_ARTIFACT_SPLASH_APPEAR_AFTER_MS) % cycle) + cycle) % cycle
}

/** Whether [cell] is lit at [phaseMs] (web `artifactSplashLitAt`). */
internal fun raftArtifactSplashLitAt(cell: RaftArtifactSplashCell, phaseMs: Long): Boolean {
    val at = phaseMs.toDouble() / RAFT_ARTIFACT_SPLASH_CYCLE_MS
    return at >= cell.on && at < cell.off
}

/** Which cycle [elapsedMs] after the opening falls in (0: the first, from when the icon appears). */
internal fun raftArtifactSplashCycleIndex(elapsedMs: Long): Long =
    (elapsedMs - RAFT_ARTIFACT_SPLASH_APPEAR_AFTER_MS).floorDiv(RAFT_ARTIFACT_SPLASH_CYCLE_MS)

/**
 * Whether a change of the splash's kind, made [changedAfterMs] after the opening (null: none since
 * it), is drawn at [elapsedMs] (design, Kirby, #301; web `useArtifactSplashShownIcon` /
 * `artifactSplashUntilNextCycle`, slock #15036): a looping icon never changes mid-cycle. The cycle
 * under way when the kind became known plays out with the icon it began with (unknown: the
 * document), and the new one starts with the next cycle; a change on a cycle's boundary, or before
 * the icon appeared, shows at once. An icon that is not looping takes the new kind at once, as on
 * the web: [reducedMotion] (still), or the ending once the content loaded ([loadedAfterMs]).
 */
internal fun raftArtifactSplashKindSwitched(
    changedAfterMs: Long?,
    elapsedMs: Long,
    loadedAfterMs: Long? = null,
    reducedMotion: Boolean = false,
): Boolean {
    if (changedAfterMs == null || changedAfterMs < RAFT_ARTIFACT_SPLASH_APPEAR_AFTER_MS) return true
    if (reducedMotion || (loadedAfterMs != null && elapsedMs >= loadedAfterMs)) return true
    if (raftArtifactSplashPhase(changedAfterMs) == 0L) return true
    return raftArtifactSplashCycleIndex(elapsedMs) > raftArtifactSplashCycleIndex(changedAfterMs)
}

/**
 * The splash's Artifact kind (null: not known yet, drawn as a document with "Loading Artifact"),
 * and when it last changed, so a kind learned while the icon cycles takes over at the next cycle
 * ([raftArtifactSplashKindSwitched]). One per opening of the viewer: every cover it draws (session
 * read, page load, reload) reads it, so a cover drawn again does not switch mid-cycle either.
 */
@Stable
internal class RaftArtifactSplashKindState(
    initial: ArtifactKind?,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    /** The kind now known. */
    var kind: ArtifactKind? by mutableStateOf(initial)
        private set

    /** The kind drawn before [kind] took over. */
    var previousKind: ArtifactKind? by mutableStateOf(initial)
        private set

    /** When [kind] changed (null: never since this state began). */
    var changedAt: TimeMark? by mutableStateOf(null)
        private set

    /** The kind drawn [elapsedMs] after [openedAt] (its content loaded [loadedAfterMs] after it, or null). */
    fun shownKind(openedAt: TimeMark, elapsedMs: Long, loadedAfterMs: Long? = null, reducedMotion: Boolean = false): ArtifactKind? {
        val changedAfterMs = changedAt?.let { (openedAt.elapsedNow() - it.elapsedNow()).inWholeMilliseconds }
        return if (raftArtifactSplashKindSwitched(changedAfterMs, elapsedMs, loadedAfterMs, reducedMotion)) kind else previousKind
    }

    /** The kind is now [kind] (the card's, read or refreshed); [openedAt]: the splash's opening. */
    fun update(kind: ArtifactKind?, openedAt: TimeMark) {
        if (kind == this.kind) return
        previousKind = shownKind(openedAt, openedAt.elapsedNow().inWholeMilliseconds)
        this.kind = kind
        changedAt = timeSource.markNow()
    }
}

/** What the cover draws [elapsedMs] after the opening; [loadedAfterMs]: when its content loaded (null: not yet). */
internal sealed interface RaftArtifactSplashFrame {
    /** The first second: an empty cover (a fast open goes from nothing to the page). */
    data object Waiting : RaftArtifactSplashFrame

    /** The icon, each cell lit or dim; [opacity] of the whole cover (the ending's fade). */
    data class Icon(val lit: List<Boolean>, val opacity: Float = 1f) : RaftArtifactSplashFrame

    /** Gone: nothing is drawn. */
    data object Gone : RaftArtifactSplashFrame
}

/**
 * The cover's frame (web ArtifactLoadingSplash): nothing for the first second; then the icon
 * assembling and dispersing (or, [reducedMotion], complete and still). Loaded before it appeared,
 * or with reduced motion: gone at once. Loaded after: the unlit cells fill in within
 * [RAFT_ARTIFACT_SPLASH_FILL_MS], the icon holds [RAFT_ARTIFACT_SPLASH_HOLD_MS], then the cover fades
 * over [RAFT_ARTIFACT_SPLASH_FADE_MS] (ease-out) and is gone.
 */
internal fun raftArtifactSplashFrame(
    icon: RaftArtifactSplashIcon,
    elapsedMs: Long,
    loadedAfterMs: Long? = null,
    reducedMotion: Boolean = false,
): RaftArtifactSplashFrame {
    val cells = raftArtifactSplashCells(icon)
    val appearedBeforeLoad = loadedAfterMs == null || loadedAfterMs >= RAFT_ARTIFACT_SPLASH_APPEAR_AFTER_MS
    if (loadedAfterMs != null && (!appearedBeforeLoad || reducedMotion)) return RaftArtifactSplashFrame.Gone
    if (elapsedMs < RAFT_ARTIFACT_SPLASH_APPEAR_AFTER_MS) return RaftArtifactSplashFrame.Waiting
    if (reducedMotion) return RaftArtifactSplashFrame.Icon(cells.map { true })
    if (loadedAfterMs == null) {
        val phase = raftArtifactSplashPhase(elapsedMs)
        return RaftArtifactSplashFrame.Icon(cells.map { raftArtifactSplashLitAt(it, phase) })
    }
    val sinceLoaded = elapsedMs - loadedAfterMs
    val phaseAtLoad = raftArtifactSplashPhase(loadedAfterMs)
    val lit = cells.map { raftArtifactSplashLitAt(it, phaseAtLoad) || sinceLoaded >= it.fillMs }
    val fadeAt = RAFT_ARTIFACT_SPLASH_FILL_MS + RAFT_ARTIFACT_SPLASH_HOLD_MS
    return when {
        sinceLoaded < fadeAt -> RaftArtifactSplashFrame.Icon(lit)
        sinceLoaded < fadeAt + RAFT_ARTIFACT_SPLASH_FADE_MS -> {
            val t = (sinceLoaded - fadeAt).toFloat() / RAFT_ARTIFACT_SPLASH_FADE_MS
            RaftArtifactSplashFrame.Icon(lit, opacity = 1f - raftArtifactSplashEaseOut(t))
        }
        else -> RaftArtifactSplashFrame.Gone
    }
}

/** raft-design `ease-out`, `cubic-bezier(0, 0, 0.58, 1)`, at [t] in 0..1. */
internal fun raftArtifactSplashEaseOut(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    // Solve the bezier's x(s) = x for s by bisection, then y(s) (control points y 0 and 1).
    var lo = 0f
    var hi = 1f
    repeat(24) {
        val s = (lo + hi) / 2f
        val bx = 3f * (1f - s) * s * s * 0.58f + s * s * s
        if (bx < x) lo = s else hi = s
    }
    val s = (lo + hi) / 2f
    return 3f * (1f - s) * s * s + s * s * s
}

/**
 * The cover (web ArtifactLoadingSplash): an opaque canvas, the icon of [kinds]' kind in the middle
 * and its line ([labelFor]) under it (a kind learned mid-cycle takes over at the next cycle), counting from [openedAt] (the opening: the 1-second wait and the cycle's phase both count from
 * there, so a cover drawn again for the same opening picks the cycle up where it was). [loadedAt]:
 * the content loaded (play the ending, then [onGone]). [scale]: the page's zoom (Desktop Mode lays
 * the page out wider and fits it, so its splash is drawn smaller).
 */
@Composable
internal fun RaftArtifactLoadingSplash(
    kinds: RaftArtifactSplashKindState,
    labelFor: (ArtifactKind?) -> String,
    openedAt: TimeMark,
    modifier: Modifier = Modifier,
    loadedAt: TimeMark? = null,
    scale: Float = 1f,
    onGone: () -> Unit = {},
) {
    val reducedMotion = rememberRaftReducedMotion()
    var elapsedMs by remember { mutableLongStateOf(openedAt.elapsedNow().inWholeMilliseconds) }
    val loadedAfterMs = loadedAt?.let { (openedAt.elapsedNow() - it.elapsedNow()).inWholeMilliseconds }
    val kind = kinds.shownKind(openedAt, elapsedMs, loadedAfterMs, reducedMotion)
    val icon = raftArtifactSplashIcon(kind)
    val frame = raftArtifactSplashFrame(icon, elapsedMs, loadedAfterMs, reducedMotion)
    var goneReported by remember { mutableStateOf(false) }
    LaunchedEffect(openedAt, loadedAt, reducedMotion) {
        while (true) {
            withFrameMillis { }
            elapsedMs = openedAt.elapsedNow().inWholeMilliseconds
            if (raftArtifactSplashFrame(icon, elapsedMs, loadedAfterMs, reducedMotion) == RaftArtifactSplashFrame.Gone) break
        }
    }
    if (frame == RaftArtifactSplashFrame.Gone) {
        LaunchedEffect(Unit) {
            if (!goneReported) {
                goneReported = true
                onGone()
            }
        }
        return
    }
    RaftArtifactLoadingSplashFrameContent(icon, frame, labelFor(kind), modifier, scale)
}

/**
 * One [frame] of the cover, drawn as it is: the live splash ([RaftArtifactLoadingSplash]) draws each
 * frame through it, and screenshots draw a chosen one.
 */
@Composable
internal fun RaftArtifactLoadingSplashFrameContent(
    icon: RaftArtifactSplashIcon,
    frame: RaftArtifactSplashFrame,
    label: String,
    modifier: Modifier = Modifier,
    scale: Float = 1f,
) {
    if (frame == RaftArtifactSplashFrame.Gone) return
    val shown = frame as? RaftArtifactSplashFrame.Icon
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .alpha(shown?.opacity ?: 1f)
                .testTag(RAFT_ARTIFACT_LOADING_SPLASH_TAG)
                .semantics { if (shown != null) contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        // The opaque canvas (web `bg-layer-canvas`).
        Canvas(Modifier.fillMaxSize()) { drawRect(RaftColor.Canvas) }
        if (shown != null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(RaftArtifactSplashLineGap * scale),
                modifier = Modifier.raftSafeAreaHorizontalPadding().padding(16.dp),
            ) {
                RaftArtifactSplashPixels(icon, shown.lit, scale)
                Text(
                    text = label,
                    style = RaftType.Callout.copy(color = RaftColor.ForegroundMuted),
                    color = RaftColor.ForegroundMuted,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

internal const val RAFT_ARTIFACT_LOADING_SPLASH_TAG = "artifact-loading-splash"

@Composable
private fun RaftArtifactSplashPixels(icon: RaftArtifactSplashIcon, lit: List<Boolean>, scale: Float) {
    val cells = raftArtifactSplashCells(icon)
    val pixel = RaftArtifactSplashPixel * scale
    val gap = RaftArtifactSplashGap * scale
    val box = pixel * RAFT_ARTIFACT_SPLASH_GRID + gap * (RAFT_ARTIFACT_SPLASH_GRID - 1)
    val ink = RaftColor.TextBlack
    Canvas(Modifier.size(box)) {
        val p = pixel.toPx()
        val step = p + gap.toPx()
        val radius = CornerRadius(1.dp.toPx() * scale)
        cells.forEachIndexed { index, cell ->
            drawRoundRect(
                color = ink,
                topLeft = Offset(cell.col * step, cell.row * step),
                size = Size(p, p),
                cornerRadius = radius,
                alpha = if (lit.getOrElse(index) { true }) 1f else RAFT_ARTIFACT_SPLASH_DIM_OPACITY,
            )
        }
    }
}

/** Which loader the App draws over the Artifact viewer's page (#301): its splash, or none (then only the page's own). */
internal enum class RaftArtifactViewerLoadingIndicator(val logToken: String) {
    /** The App's splash ([RaftArtifactLoadingSplash]) covers the page: the page's own is not seen. */
    AppSplash("app_splash"),

    /** Nothing of the App's: the page shows its own splash or its content (or the error screen shows). */
    None("none"),
}

/** Where an Artifact opening stands, as far as the viewer can tell. */
internal data class RaftArtifactViewerOpenState(
    /** The session the page needs is read (before that no WebView is attached). */
    val sessionRead: Boolean,
    /** The WebView shows a document (committed, or a kept one shown again). */
    val documentShown: Boolean,
    /**
     * A kept document is reused that has drawn its own bar before (its address keeps `hostToolbar=1`,
     * [raftArtifactViewerKeptPageDrewBar]): it showed an Artifact already, so it sends no `hostLoaded`
     * now (once per document, slock #15036) and draws the only bar from the start: no native cover,
     * the page's own splash shows. A kept document only booted (prewarm, the warm route) has shown
     * nothing: it says `hostLoaded=1` for its first Artifact and is covered as a fresh load is, so
     * its loading toolbar row (Back alone, `back=host`) never shows under the App's bar (#301).
     */
    val reusesKeptPage: Boolean,
    /** The load watchdog shows its error screen (with Retry). */
    val loadError: Boolean,
    /** How long the document has been loaded (null: not yet); past [RAFT_ARTIFACT_VIEWER_SPLASH_FALLBACK_MS] the page is shown. */
    val sinceDocumentReadyMs: Long?,
    /** The viewer is between WebViews (a fresh reload of a wandered-off kept page, or a session recovery). */
    val reloading: Boolean = false,
    /** The page said its content loaded (`hostLoaded=1`, [raftArtifactViewerPageSaysLoaded]). */
    val pageLoaded: Boolean = false,
    /** After [pageLoaded], the App's splash played its ending (fill, hold, fade). */
    val splashEnded: Boolean = false,
    /**
     * The page draws the only bar (`hostToolbar=1`, shown, no load error): its own splash, on the same
     * clock and in the same place, or its content is under that bar, and its menus open from it.
     */
    val pageDrawsBar: Boolean = false,
)

/**
 * After the document loaded, the page is shown at the latest this much later even if it never said
 * its content loaded (`hostLoaded=1`): a web build from before that signal (it drew its bar,
 * `hostToolbar=1`, by then, and its own splash or content is there), a page of its own like
 * Unavailable, or content still loading (then the page's own splash, on the same clock). Never a
 * cover over a page that is up for longer.
 */
internal const val RAFT_ARTIFACT_VIEWER_SPLASH_FALLBACK_MS = 4_000L

/**
 * One loader at a time (#301): on a fresh load the App's splash covers the page while the App's bar is
 * up (the page's loading row, Back alone, never shows under it), until the page draws the only bar
 * (`hostToolbar=1`, [RaftArtifactViewerOpenState.pageDrawsBar]: its own splash, the same one on the
 * same clock, takes over in place, and its menus open over it) or says its loading is over
 * (`hostLoaded=1`, slock #15036: the moment its own splash would start its ending, or 150 ms after a
 * failure left no splash on screen; the App's splash then plays its ending), then the page alone. A kept page reused that showed an Artifact before is never covered (it sends no signal):
 * its own splash shows, under its own bar. A kept page only booted is covered as a fresh load. A page that never says so (an older web) is shown at
 * [RAFT_ARTIFACT_VIEWER_SPLASH_FALLBACK_MS]. Before, the App showed its generic "Loading" (a small
 * spinner and a line) until the WebView committed a document, and the page then its own pixel
 * splash: two different loaders in a row on a cold open, one on a warm one. Now the App's splash is
 * the page's ([RaftArtifactLoadingSplash]), on the same clock.
 */
internal fun raftArtifactViewerLoadingIndicator(state: RaftArtifactViewerOpenState): RaftArtifactViewerLoadingIndicator =
    when {
        state.reloading || !state.sessionRead -> RaftArtifactViewerLoadingIndicator.AppSplash
        state.loadError -> RaftArtifactViewerLoadingIndicator.None
        // In-page reuse of a document that showed an Artifact gets no hand-over signal: the page's own
        // splash (under its own bar), never the App's over it.
        state.reusesKeptPage -> RaftArtifactViewerLoadingIndicator.None
        // The page draws the only bar: the page takes over at once (its own splash shows where the App's was).
        // Never a cover under its bar: its ⋯ and version ▾ open from that bar over the body, inside the
        // WebView, and a cover there hid them (only the menu's top edge showed under the bar, 1.26).
        state.pageDrawsBar -> RaftArtifactViewerLoadingIndicator.None
        // Loaded: the App's splash plays its ending over the page, then the page alone.
        state.pageLoaded -> if (state.splashEnded) RaftArtifactViewerLoadingIndicator.None else RaftArtifactViewerLoadingIndicator.AppSplash
        state.sinceDocumentReadyMs != null && state.sinceDocumentReadyMs >= RAFT_ARTIFACT_VIEWER_SPLASH_FALLBACK_MS ->
            RaftArtifactViewerLoadingIndicator.None
        else -> RaftArtifactViewerLoadingIndicator.AppSplash
    }

/**
 * Whether a kept document has drawn its own bar before: its address keeps `hostToolbar=1` (the web's
 * keeper keeps it once written, slock embed.ts), so it has shown an Artifact. Null address: nothing kept.
 */
internal fun raftArtifactViewerKeptPageDrewBar(keptUrl: String?): Boolean =
    keptUrl != null && raftArtifactViewerPageDrawsHostBar(keptUrl)

/**
 * The top of the App's splash cover, from the viewer body's top (#301, one bar): while the App's bar is
 * up it covers the whole body, the page's own loading toolbar row too (so no second bar shows under the
 * App's); once the page draws the only bar (`hostToolbar=1`) the App's bar is gone and the body starts
 * under the status bar, so the cover leaves the page's bar uncovered, [barHeight] (the App's head height,
 * `RaftTheme.controls.headHeight`: 56 by touch, 44 with a mouse). In the Artifact column the page's bar
 * is exactly that tall, as the App tells it that height (`hostHead`, [raftArtifactViewerHostHead]): the
 * splash stays where it was and the page's bar takes the App's place. A page of its own (a phone) is
 * told none and keeps its own toolbar, whose height is the page's. In Desktop Mode the page's bar is
 * fitted as its splash is ([scale]).
 */
internal fun raftArtifactViewerSplashCoverTop(pageDrawsBar: Boolean, barHeight: Dp, scale: Float = 1f): Dp =
    if (pageDrawsBar) barHeight * scale else 0.dp

/** Desktop Mode fits a page laid out [RaftFilePreviewDesktopViewportWidth] wide into [widthDp]: its splash is that much smaller. */
internal fun raftArtifactViewerSplashScale(desktopMode: Boolean, widthDp: Float): Float =
    if (!desktopMode || widthDp <= 0f) 1f else (widthDp / RaftFilePreviewDesktopViewportWidth.toFloat()).coerceIn(0.1f, 1f)
