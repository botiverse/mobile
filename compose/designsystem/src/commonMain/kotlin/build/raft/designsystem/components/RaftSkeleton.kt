package build.raft.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** raft-ui Skeleton `variant`. */
enum class RaftSkeletonVariant {
    /**
     * A line of text: a 12dp bar; give it a width. Chip radius (6dp) per the mobile DRI's
     * page spec; raft-ui 0.8.0 draws every skeleton with the sticker radius.
     */
    Line,

    /** A thumbnail, card or any other block; size it with the modifier. */
    Block,

    /** An avatar or a tile. Despite raft-ui's name it is square, like the avatar it stands for. */
    Circle,
}

/** Tailwind `animate-pulse`: a 2s cycle, opacity 1 → 0.5 → 1. */
const val RaftSkeletonPulseMillis = 2000

/**
 * Opacity of a pulsing skeleton [elapsedMillis] into the animation: Tailwind's
 * `pulse` keyframes (`50% { opacity: .5 }`) on `cubic-bezier(0.4, 0, 0.6, 1)`.
 */
fun raftSkeletonAlphaAt(elapsedMillis: Long): Float {
    val cycle = ((elapsedMillis % RaftSkeletonPulseMillis) + RaftSkeletonPulseMillis) % RaftSkeletonPulseMillis
    val half = RaftSkeletonPulseMillis / 2f
    // Each half runs the easing from its own keyframe: 1 → 0.5, then 0.5 → 1.
    val t = if (cycle < half) cycle / half else (cycle - half) / half
    val eased = cubicBezierEase(t, 0.4f, 0f, 0.6f, 1f)
    return if (cycle < half) 1f - 0.5f * eased else 0.5f + 0.5f * eased
}

/** CSS `cubic-bezier(x1, y1, x2, y2)` at progress [t], solved for x by bisection. */
private fun cubicBezierEase(t: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
    fun bezier(s: Float, p1: Float, p2: Float): Float {
        val u = 1f - s
        return 3f * u * u * s * p1 + 3f * u * s * s * p2 + s * s * s
    }
    var lo = 0f
    var hi = 1f
    var s = t
    repeat(24) {
        s = (lo + hi) / 2f
        val x = bezier(s, x1, x2)
        if (abs(x - t) < 1e-5f) return bezier(s, y1, y2)
        if (x < t) lo = s else hi = s
    }
    return bezier(s, y1, y2)
}

/**
 * The one frame loop every pulsing [RaftSkeleton] under a [build.raft.designsystem.theme.RaftTheme]
 * reads. It runs only while at least one skeleton is shown ([acquire] / [release]) and
 * publishes the frame time; each skeleton's phase is that time modulo
 * [RaftSkeletonPulseMillis], so skeletons that appear at different moments still pulse
 * together (as CSS animations on one page do), and a screen of skeletons keeps one loop
 * running, not one each. The time is read in the draw phase only: the pulse never
 * recomposes.
 */
@Stable
internal class RaftPulseClock(private val scope: CoroutineScope) {
    private var users = 0
    private var job: Job? = null

    /** The latest frame time in ms; 0 until the first frame. */
    var frameMillis by mutableLongStateOf(0L)
        private set

    /** Whether the frame loop is running (some skeleton is shown). */
    val running: Boolean get() = job != null

    fun acquire() {
        users++
        if (job == null) {
            job = scope.launch { while (true) withFrameMillis { frameMillis = it } }
        }
    }

    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) {
            job?.cancel()
            job = null
        }
    }
}

/** Provided by [build.raft.designsystem.theme.RaftTheme]; null outside one. */
internal val LocalRaftPulseClock = staticCompositionLocalOf<RaftPulseClock?> { null }

/** The enclosing theme's clock, or a new one (one per outermost theme). */
@Composable
internal fun rememberRaftPulseClock(): RaftPulseClock {
    val parent = LocalRaftPulseClock.current
    if (parent != null) return parent
    val scope = rememberCoroutineScope()
    return remember(scope) { RaftPulseClock(scope) }
}

/**
 * raft-ui 0.8.0 Skeleton: a `fill-muted` placeholder in the shape of the content that is
 * loading. [RaftSkeletonVariant.Line] is a 12dp bar with the chip radius; blocks and
 * squares take the sticker radius; size them with [modifier].
 *
 * It pulses (Tailwind `animate-pulse`) unless [animated] is false: pass false for
 * screenshots and reduced motion (raft-ui `motion-reduce:animate-none`), which renders the
 * resting, fully opaque frame. Every skeleton under one theme pulses in phase from one
 * shared frame loop ([RaftPulseClock]), which stops when the last one leaves. Hidden from
 * accessibility; mark the loading region busy.
 */
@Composable
fun RaftSkeleton(
    modifier: Modifier = Modifier,
    variant: RaftSkeletonVariant = RaftSkeletonVariant.Block,
    animated: Boolean = true,
) {
    val clock = rememberRaftPulseClock()
    if (animated) {
        DisposableEffect(clock) {
            clock.acquire()
            onDispose { clock.release() }
        }
    }
    val line = variant == RaftSkeletonVariant.Line
    val sized = if (line) modifier.height(12.dp) else modifier
    Box(
        sized
            .clearAndSetSemantics { }
            // Read in the draw phase only: the pulse never recomposes.
            .graphicsLayer { this.alpha = if (animated) raftSkeletonAlphaAt(clock.frameMillis) else 1f }
            .background(RaftTheme.colors.fillMuted, RoundedCornerShape(if (line) RaftRadius.Chip else RaftRadius.Sticker)),
    )
}
