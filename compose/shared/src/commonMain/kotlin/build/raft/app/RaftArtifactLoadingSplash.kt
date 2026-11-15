package build.raft.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
 * The cover (web ArtifactLoadingSplash): an opaque canvas, the icon in the middle and [label] under
 * it, counting from [openedAt] (the opening: the 1-second wait and the cycle's phase both count from
 * there, so a cover drawn again for the same opening picks the cycle up where it was). [loadedAt]:
 * the content loaded (play the ending, then [onGone]). [scale]: the page's zoom (Desktop Mode lays
 * the page out wider and fits it, so its splash is drawn smaller).
 */
@Composable
internal fun RaftArtifactLoadingSplash(
    kind: ArtifactKind?,
    label: String,
    openedAt: TimeMark,
    modifier: Modifier = Modifier,
    loadedAt: TimeMark? = null,
    scale: Float = 1f,
    onGone: () -> Unit = {},
) {
    val icon = raftArtifactSplashIcon(kind)
    val reducedMotion = rememberRaftReducedMotion()
    var elapsedMs by remember { mutableLongStateOf(openedAt.elapsedNow().inWholeMilliseconds) }
    val loadedAfterMs = loadedAt?.let { (openedAt.elapsedNow() - it.elapsedNow()).inWholeMilliseconds }
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
    RaftArtifactLoadingSplashFrameContent(icon, frame, label, modifier, scale)
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
                modifier = Modifier.padding(16.dp),
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
    /** The page drew itself: its toolbar is up (`hostToolbar=1`), so its own splash or content is there. */
    val pageDrewItself: Boolean,
    /** A kept document already showing this Artifact is shown again (nothing loads). */
    val keptShowsTarget: Boolean,
    /** The load watchdog shows its error screen (with Retry). */
    val loadError: Boolean,
    /** How long the document has been loaded (null: not yet); past [RAFT_ARTIFACT_VIEWER_SPLASH_FALLBACK_MS] the page is shown. */
    val sinceDocumentReadyMs: Long?,
    /** The viewer is between WebViews (a fresh reload of a wandered-off kept page). */
    val reloading: Boolean = false,
)

/**
 * After the document loaded, the page is shown at the latest this much later even if it never said
 * it drew itself (a web build without `hostToolbar`, a page of its own like Unavailable): never a
 * cover over a page that is up.
 */
internal const val RAFT_ARTIFACT_VIEWER_SPLASH_FALLBACK_MS = 4_000L

/**
 * One loader at a time (#301): the App's splash until the page has drawn itself, then the page's
 * alone. Before, the App showed its generic "Loading" (a small spinner and a line) until the WebView
 * committed a document, and the page then its own pixel splash: two different loaders in a row on a
 * cold open, one on a warm one. Now the App's splash is the page's ([RaftArtifactLoadingSplash]), on
 * the same clock, and hands over when the page's is there.
 */
internal fun raftArtifactViewerLoadingIndicator(state: RaftArtifactViewerOpenState): RaftArtifactViewerLoadingIndicator =
    when {
        state.reloading || !state.sessionRead -> RaftArtifactViewerLoadingIndicator.AppSplash
        state.loadError -> RaftArtifactViewerLoadingIndicator.None
        state.documentShown && (state.keptShowsTarget || state.pageDrewItself) -> RaftArtifactViewerLoadingIndicator.None
        state.sinceDocumentReadyMs != null && state.sinceDocumentReadyMs >= RAFT_ARTIFACT_VIEWER_SPLASH_FALLBACK_MS ->
            RaftArtifactViewerLoadingIndicator.None
        else -> RaftArtifactViewerLoadingIndicator.AppSplash
    }

/** Desktop Mode fits a page laid out [RaftFilePreviewDesktopViewportWidth] wide into [widthDp]: its splash is that much smaller. */
internal fun raftArtifactViewerSplashScale(desktopMode: Boolean, widthDp: Float): Float =
    if (!desktopMode || widthDp <= 0f) 1f else (widthDp / RaftFilePreviewDesktopViewportWidth.toFloat()).coerceIn(0.1f, 1f)
