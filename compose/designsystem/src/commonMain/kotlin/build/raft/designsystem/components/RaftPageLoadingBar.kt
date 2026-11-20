package build.raft.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import build.raft.designsystem.motion.rememberRaftReducedMotion
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.tokens.RaftUiPalette
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.delay

/** What the page under a [RaftPageLoadingBar] is doing; the caller reports it, the line plays it. */
@Immutable
sealed interface RaftPageLoadState {
    /** No load: no line. Also a load that was stopped, when the caller can't call it finished. */
    data object Idle : RaftPageLoadState

    /**
     * A load is running. [progress] is the fraction the page reports (0..1), or null while it
     * has reported none yet. Null again after a value means a new load began.
     */
    data class Loading(val progress: Float? = null) : RaftPageLoadState

    /** The load ended well: the line runs to the end and fades out. */
    data object Finished : RaftPageLoadState

    /** The load failed: the line goes at once. */
    data object Failed : RaftPageLoadState
}

/** How long a load runs before its line first shows; a load that ends sooner draws no line. */
const val RaftPageLoadingBarAppearDelayMillis: Int = 200

/** How long each change of the line's width takes (`ease-out`), the run to the end included. */
const val RaftPageLoadingBarProgressMillis: Int = 200

/** How long the full line takes to fade out after a finished load. */
const val RaftPageLoadingBarFadeOutMillis: Int = 200

/** The line's width while the page has reported no progress, and its least width while loading. */
const val RaftPageLoadingBarRestFraction: Float = 0.08f

/**
 * The line's greatest width while the load still runs. Engines report 100% some time before
 * the load ends; the full line is kept for [RaftPageLoadState.Finished], so a full line always
 * means done and the run to the end can always be seen.
 */
const val RaftPageLoadingBarLoadingMaxFraction: Float = 0.95f

/** CSS `ease-out`, `cubic-bezier(0, 0, 0.58, 1)`: the line's width changes and its fade. */
private val RaftPageLoadingBarEaseOut: Easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)

/** Where a [RaftPageLoadingBar] is in one load's life. */
internal enum class RaftPageLoadingBarPhase {
    /** No line. */
    Hidden,

    /** A load runs, inside the appear delay: no line yet. */
    Waiting,

    /** The line shows the load. */
    Shown,

    /** The load finished: the line runs to the end and fades out. */
    Leaving,
}

/**
 * The line's state, kept apart from the clock so it can be tested as plain data: [onState]
 * takes what the caller reports, [onAppearDelayPassed] and [onLeft] what the component's
 * timers report.
 *
 * - [fraction] is the width the line is going to (0..1); it never gets smaller within a load.
 * - [reported] is whether this load has reported progress.
 * - [load] counts loads: a change means the line starts again from the beginning.
 */
@Immutable
internal data class RaftPageLoadingBarLine(
    val phase: RaftPageLoadingBarPhase = RaftPageLoadingBarPhase.Hidden,
    val fraction: Float = 0f,
    val reported: Boolean = false,
    val load: Int = 0,
) {
    /** Whether the line is drawn. */
    val visible: Boolean
        get() = phase == RaftPageLoadingBarPhase.Shown || phase == RaftPageLoadingBarPhase.Leaving

    /**
     * The line after the caller reports [state]. Reporting the same state again changes nothing.
     * [appearDelay] = false shows a new load's line at once; [exitMotion] = false (reduced
     * motion) drops a finished load's line at once instead of running it to the end.
     */
    fun onState(
        state: RaftPageLoadState,
        appearDelay: Boolean = true,
        exitMotion: Boolean = true,
    ): RaftPageLoadingBarLine =
        when (state) {
            RaftPageLoadState.Idle, RaftPageLoadState.Failed -> hidden()
            RaftPageLoadState.Finished ->
                when (phase) {
                    RaftPageLoadingBarPhase.Shown ->
                        if (exitMotion) copy(phase = RaftPageLoadingBarPhase.Leaving, fraction = 1f) else hidden()
                    RaftPageLoadingBarPhase.Leaving -> this
                    // Finished inside the appear delay: a fast page draws no line.
                    RaftPageLoadingBarPhase.Waiting, RaftPageLoadingBarPhase.Hidden -> hidden()
                }
            is RaftPageLoadState.Loading -> {
                val progress = state.progress?.takeUnless { it.isNaN() }?.coerceIn(0f, 1f)
                val target = raftPageLoadingBarLoadingFraction(progress)
                when {
                    // A load after none, or after one that finished: from the beginning.
                    phase == RaftPageLoadingBarPhase.Hidden || phase == RaftPageLoadingBarPhase.Leaving ->
                        RaftPageLoadingBarLine(
                            phase = if (appearDelay) RaftPageLoadingBarPhase.Waiting else RaftPageLoadingBarPhase.Shown,
                            fraction = target,
                            reported = progress != null,
                            load = load + 1,
                        )
                    // "No progress yet" after progress: the page started another load.
                    reported && progress == null -> copy(fraction = target, reported = false, load = load + 1)
                    // The same load: forward only.
                    else -> copy(fraction = max(fraction, target), reported = reported || progress != null)
                }
            }
        }

    /** The appear delay of this load passed: its line shows. */
    fun onAppearDelayPassed(): RaftPageLoadingBarLine =
        if (phase == RaftPageLoadingBarPhase.Waiting) copy(phase = RaftPageLoadingBarPhase.Shown) else this

    /** The finished line reached the end and faded out. */
    fun onLeft(): RaftPageLoadingBarLine = if (phase == RaftPageLoadingBarPhase.Leaving) hidden() else this

    private fun hidden(): RaftPageLoadingBarLine =
        copy(phase = RaftPageLoadingBarPhase.Hidden, fraction = 0f, reported = false)
}

/**
 * The line's width for a running load that reports [progress] (0..1, null: none yet): at least
 * [RaftPageLoadingBarRestFraction], at most [RaftPageLoadingBarLoadingMaxFraction].
 */
internal fun raftPageLoadingBarLoadingFraction(progress: Float?): Float =
    max(RaftPageLoadingBarRestFraction, min(progress ?: 0f, RaftPageLoadingBarLoadingMaxFraction))

/**
 * The load line on the top edge of a page that is loading (an in-app web page), as a browser
 * draws it: a flat [RaftStroke.Strong] (2dp) line in the Progress `info` fill, with square
 * ends, starting at the start edge of its parent. It has no track, no radius and no ink edge,
 * so it must sit flush on the edge: put it at the top of the page, full width, with no padding.
 * For progress inside a page (a download, a quota) use [RaftProgressBar].
 *
 * Keep it in the composition for as long as the page is and report the load through [state];
 * the line owns its own life:
 * - It first shows [appearDelayMillis] ([RaftPageLoadingBarAppearDelayMillis]) into a load, so
 *   a page that finishes sooner draws no line. `0` shows it at once (screenshots).
 * - With no progress reported it rests, still, at [RaftPageLoadingBarRestFraction] of the width.
 * - With progress it follows the page, forward only, each change over
 *   [RaftPageLoadingBarProgressMillis] with `ease-out`, up to
 *   [RaftPageLoadingBarLoadingMaxFraction]. A new load starts again from the beginning.
 * - [RaftPageLoadState.Finished]: it runs to the end, then fades out over
 *   [RaftPageLoadingBarFadeOutMillis]. [RaftPageLoadState.Failed] and [RaftPageLoadState.Idle]:
 *   it goes at once.
 * - With [reducedMotion] the width jumps to each value and a finished line goes at once.
 *
 * It takes no room and has no semantics while no line is drawn. Semantics while it is: a
 * progress bar with its value (indeterminate before the page reports any), named by
 * [contentDescription].
 */
@Composable
fun RaftPageLoadingBar(
    state: RaftPageLoadState,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    reducedMotion: Boolean = rememberRaftReducedMotion(),
    appearDelayMillis: Int = RaftPageLoadingBarAppearDelayMillis,
) {
    val appearDelay = appearDelayMillis > 0
    var line by remember { mutableStateOf(RaftPageLoadingBarLine().onState(state, appearDelay, !reducedMotion)) }
    // The line is derived from its last value and the reported state; onState is idempotent,
    // so this settles in the same composition.
    val reportedLine = line.onState(state, appearDelay, !reducedMotion)
    if (reportedLine != line) line = reportedLine
    val current = line

    val width = remember { Animatable(current.fraction) }
    val alpha = remember { Animatable(1f) }

    val waiting = current.phase == RaftPageLoadingBarPhase.Waiting
    LaunchedEffect(current.load, waiting, appearDelayMillis) {
        if (!waiting) return@LaunchedEffect
        delay(appearDelayMillis.toLong())
        line = line.onAppearDelayPassed()
    }

    LaunchedEffect(current, reducedMotion) {
        when (current.phase) {
            RaftPageLoadingBarPhase.Hidden, RaftPageLoadingBarPhase.Waiting -> {
                width.snapTo(current.fraction)
                alpha.snapTo(1f)
            }
            RaftPageLoadingBarPhase.Shown -> {
                alpha.snapTo(1f)
                // Smaller than what is drawn only when a new load began: no travel backwards.
                if (reducedMotion || current.fraction <= width.value) {
                    width.snapTo(current.fraction)
                } else {
                    width.animateTo(
                        current.fraction,
                        tween(RaftPageLoadingBarProgressMillis, easing = RaftPageLoadingBarEaseOut),
                    )
                }
            }
            RaftPageLoadingBarPhase.Leaving -> {
                width.animateTo(1f, tween(RaftPageLoadingBarProgressMillis, easing = RaftPageLoadingBarEaseOut))
                alpha.animateTo(0f, tween(RaftPageLoadingBarFadeOutMillis, easing = RaftPageLoadingBarEaseOut))
                line = line.onLeft()
            }
        }
    }

    if (!current.visible) return
    val fill = RaftUiPalette.info
    val indeterminate = current.phase == RaftPageLoadingBarPhase.Shown && !current.reported
    val semanticFraction = current.fraction
    Canvas(
        modifier.fillMaxWidth().height(RaftStroke.Strong).semantics {
            progressBarRangeInfo =
                if (indeterminate) ProgressBarRangeInfo.Indeterminate else ProgressBarRangeInfo(semanticFraction, 0f..1f)
            contentDescription?.let { this.contentDescription = it }
        },
    ) {
        // Read in the draw phase only: the width and the fade never recompose.
        val drawn = size.width * width.value.coerceIn(0f, 1f)
        if (drawn > 0f) {
            // From the start edge: the right one in a right-to-left layout.
            val left = if (layoutDirection == LayoutDirection.Rtl) size.width - drawn else 0f
            drawRect(
                color = fill,
                topLeft = Offset(left, 0f),
                size = Size(drawn, size.height),
                alpha = alpha.value.coerceIn(0f, 1f),
            )
        }
    }
}
