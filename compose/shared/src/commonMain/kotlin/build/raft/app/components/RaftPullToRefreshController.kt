package build.raft.app.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * The platform's pull-to-refresh feel. In both, two layers move together: the list content
 * follows the finger with rubber-band resistance and the indicator comes down in the gap above it;
 * released past the threshold both spring to the refreshing position and hold it while the store
 * refreshes, then spring back together (otherwise they spring straight back).
 *
 * - [Material] (Android): Compose Material 3 indicator (arrow arc, then spinner) sliding in over
 *   the top of the gap, threshold 80dp (Flutter `RefreshIndicator` + moving content).
 * - [Cupertino] (iOS): UIRefreshControl feel: UIScrollView rubber band, a bare spinner centred in
 *   the gap behind the content.
 */
internal enum class RaftPullToRefreshStyle {
    Material,
    Cupertino,
}

/**
 * Whether a refresh the user did not pull (the store starts one on its own) still shows the
 * indicator. Material slides its floating indicator in over the content; the iOS spinner lives in
 * the gap, and the content is never pushed down without a finger, so it stays hidden.
 */
internal val RaftPullToRefreshStyle.showsIndicatorForUnpulledRefresh: Boolean
    get() = this == RaftPullToRefreshStyle.Material

internal enum class RaftPullToRefreshPhase {
    /** At rest. */
    Idle,

    /** The finger is pulling the content down. */
    Pulling,

    /** Released past the threshold and `onRefresh` was called; waiting for the store to report it. */
    AwaitingRefresh,

    /** The store reports a refresh in progress. */
    Refreshing,

    /** Springing back to rest. */
    Settling,
}

/**
 * Geometry of a pull, in px. [triggerPx] is the content offset a release must reach to refresh;
 * [indicatorHoldPx] is the gap the content holds open (and the indicator parks in) while refreshing; [coefficient] is the rubber-band slope at rest.
 */
internal data class RaftPullToRefreshGeometry(
    val triggerPx: Float,
    val indicatorHoldPx: Float,
    val coefficient: Float,
)

/**
 * UIScrollView's rubber band: the content offset for a finger travel of [pull] px over a viewport
 * of [dimension] px. Slope [coefficient] at rest, approaching [dimension] asymptotically.
 */
internal fun raftRubberBandOffset(pull: Float, dimension: Float, coefficient: Float): Float {
    if (pull <= 0f || dimension <= 0f) return 0f
    return (1f - 1f / (pull * coefficient / dimension + 1f)) * dimension
}

/** Inverse of [raftRubberBandOffset]: the finger travel that yields [offset]. */
internal fun raftRubberBandPull(offset: Float, dimension: Float, coefficient: Float): Float {
    if (offset <= 0f || dimension <= 0f) return 0f
    val clamped = offset.coerceAtMost(dimension * 0.999f)
    return (1f / (1f - clamped / dimension) - 1f) * dimension / coefficient
}

/** d(offset)/d(pull) of [raftRubberBandOffset]: converts a finger velocity into a content velocity. */
internal fun raftRubberBandSlope(pull: Float, dimension: Float, coefficient: Float): Float {
    if (dimension <= 0f) return coefficient
    val x = pull.coerceAtLeast(0f) * coefficient / dimension + 1f
    return coefficient / (x * x)
}

/** [offset] as a fraction of the [trigger] distance, clamped to 0..1. */
internal fun raftPullProgress(offset: Float, trigger: Float): Float =
    if (trigger > 0f) (offset / trigger).coerceIn(0f, 1f) else 0f

/**
 * How many of an activity indicator's [tickCount] spokes a pull at [progress] (0..1) reveals,
 * clockwise from 12 o'clock: none at rest, one more per 1/[tickCount] of the pull, all of them at
 * the threshold. This is UIRefreshControl's pull-down reveal (its spokes appear one by one as the
 * content comes down, then the full indicator starts spinning when the pull commits).
 */
internal fun raftRefreshRevealedTicks(progress: Float, tickCount: Int = RaftActivityIndicatorTickCount): Int {
    if (progress <= 0f) return 0
    if (progress >= 1f) return tickCount
    return ceil(progress * tickCount).toInt().coerceIn(1, tickCount)
}

/** UIActivityIndicatorView's `.medium` / `.large` styles (iOS 13+) draw 8 spokes. */
internal const val RaftActivityIndicatorTickCount = 8

/**
 * The pull → refresh → settle state machine behind [RaftPullToRefreshBox]. Drags arrive through
 * [nestedScrollConnection] (user input only: programmatic scrolls and flings never move it), the
 * refresh signal arrives from the caller's store through [onStoreRefreshingChanged]; nothing here
 * waits on a timer. Offsets are snapshot state read in the layer/draw phase.
 *
 * The visible offsets live in [contentOffset] (the list's translation) and [indicatorOffset] (how
 * far the indicator has come down). They move together while pulling and spring together on
 * release and when the refresh ends; they only differ for a Material refresh the user did not pull
 * (indicator in, content untouched).
 */
@Stable
internal class RaftPullToRefreshController(
    val style: RaftPullToRefreshStyle,
    private val scope: CoroutineScope,
    geometry: RaftPullToRefreshGeometry,
) {
    var geometry: RaftPullToRefreshGeometry = geometry

    /** Height of the pulled viewport (px), the rubber band's dimension. */
    var viewportPx: Float = 0f

    var onRefresh: () -> Unit = {}

    var phase: RaftPullToRefreshPhase by mutableStateOf(RaftPullToRefreshPhase.Idle)
        private set

    var contentOffset: Float by mutableFloatStateOf(0f)
        private set

    var indicatorOffset: Float by mutableFloatStateOf(0f)
        private set

    /** The indicator's travel relative to its hold position: 0 at rest, 1 at the hold. */
    val indicatorFraction: Float
        get() = if (geometry.indicatorHoldPx > 0f) indicatorOffset / geometry.indicatorHoldPx else 0f

    /** The indicator spins (refresh requested or running), as opposed to following the pull. */
    val showsRefreshing: Boolean
        get() = phase == RaftPullToRefreshPhase.AwaitingRefresh || phase == RaftPullToRefreshPhase.Refreshing

    /**
     * Cupertino only: this pull has reached [RaftPullToRefreshGeometry.triggerPx] and is committed.
     * UIRefreshControl commits the moment the pull crosses its threshold (the spinner starts and
     * the haptic plays while the finger is still down) and does not take it back if the finger
     * then eases off; the release then refreshes regardless of where the content is. Cleared when
     * the pull is released (or grabbed again, or disabled).
     */
    var armed: Boolean by mutableStateOf(false)
        private set

    /** The pull's progress towards the threshold, 0..1 (the indicator's reveal before it arms). */
    val pullProgress: Float
        get() = raftPullProgress(contentOffset, geometry.triggerPx)

    val isAnimating: Boolean
        get() = animationJob?.isActive == true

    private var enabled = true
    private var pull = 0f
    private var storeRefreshing = false
    private var animationJob: Job? = null

    private val dimension: Float
        get() = if (viewportPx > 0f) viewportPx else geometry.triggerPx * RaftPullToRefreshFallbackDimensionFactor

    val nestedScrollConnection: NestedScrollConnection =
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                if (source == NestedScrollSource.UserInput && available.y < 0f) {
                    Offset(0f, onPullUp(available.y))
                } else {
                    Offset.Zero
                }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                if (source == NestedScrollSource.UserInput && available.y > 0f) {
                    Offset(0f, onPullDown(available.y))
                } else {
                    Offset.Zero
                }

            override suspend fun onPreFling(available: Velocity): Velocity =
                Velocity(0f, onRelease(available.y))
        }

    /** An upward drag of [dy] (< 0) px before the list scrolls; returns the part it consumed. */
    fun onPullUp(dy: Float): Float {
        if (!enabled || dy >= 0f) return 0f
        return when {
            phase == RaftPullToRefreshPhase.Pulling -> dragBy(dy)
            phase == RaftPullToRefreshPhase.Settling && contentOffset > 0f -> {
                grab()
                dragBy(dy)
            }
            // UIRefreshControl: scrolling up while refreshing closes the held gap before the list moves.
            phase == RaftPullToRefreshPhase.Refreshing && contentOffset > 0f -> {
                cancelAnimation()
                val next = (contentOffset + dy).coerceAtLeast(0f)
                val consumed = next - contentOffset
                contentOffset = next
                indicatorOffset = next
                consumed
            }
            else -> 0f
        }
    }

    /** A downward drag of [dy] (> 0) px the list could not scroll (it is at the top); returns the consumed part. */
    fun onPullDown(dy: Float): Float {
        if (!enabled || dy <= 0f) return 0f
        return when (phase) {
            RaftPullToRefreshPhase.Idle -> {
                pull = 0f
                phase = RaftPullToRefreshPhase.Pulling
                dragBy(dy)
            }
            RaftPullToRefreshPhase.Pulling -> dragBy(dy)
            RaftPullToRefreshPhase.Settling ->
                // Catch content that is still springing back; an indicator retreating on its own
                // (unpulled Material refresh, content already at rest) is left alone.
                if (contentOffset > 0f) {
                    grab()
                    dragBy(dy)
                } else {
                    0f
                }
            // While a refresh is pending or running the platform overscroll takes extra pulls.
            RaftPullToRefreshPhase.AwaitingRefresh,
            RaftPullToRefreshPhase.Refreshing -> 0f
        }
    }

    /**
     * The finger lifted with [velocityY] px/s. Past the threshold this requests a refresh and
     * springs to the refreshing position; otherwise it springs back. Returns the velocity consumed:
     * a downward fling of a pull is spent on the spring, an upward one goes on to the list.
     */
    fun onRelease(velocityY: Float): Float {
        if (phase != RaftPullToRefreshPhase.Pulling) return 0f
        val offsetVelocity = velocityY * raftRubberBandSlope(pull, dimension, geometry.coefficient)
        pull = 0f
        val wasArmed = armed
        armed = false
        when {
            !enabled -> settle(offsetVelocity)
            storeRefreshing -> {
                phase = RaftPullToRefreshPhase.Refreshing
                springToRefreshing(offsetVelocity)
            }
            wasArmed || contentOffset >= geometry.triggerPx -> {
                phase = RaftPullToRefreshPhase.AwaitingRefresh
                springToRefreshing(offsetVelocity)
                onRefresh()
            }
            else -> settle(offsetVelocity)
        }
        return if (velocityY > 0f) velocityY else 0f
    }

    /** The caller's (store's) refresh signal. */
    fun onStoreRefreshingChanged(refreshing: Boolean) {
        if (storeRefreshing == refreshing) return
        storeRefreshing = refreshing
        if (refreshing) {
            when (phase) {
                RaftPullToRefreshPhase.AwaitingRefresh -> phase = RaftPullToRefreshPhase.Refreshing
                RaftPullToRefreshPhase.Idle,
                RaftPullToRefreshPhase.Settling -> {
                    phase = RaftPullToRefreshPhase.Refreshing
                    // A refresh that was not pulled: never push the content down without a finger.
                    if (style.showsIndicatorForUnpulledRefresh) springToRefreshing(0f, moveContent = false)
                }
                RaftPullToRefreshPhase.Pulling,
                RaftPullToRefreshPhase.Refreshing -> Unit
            }
        } else if (phase == RaftPullToRefreshPhase.Refreshing) {
            settle(0f)
        }
        // AwaitingRefresh + false: the spring to the refreshing position resolves it (see springToRefreshing).
    }

    fun onEnabledChanged(enabled: Boolean) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        if (!enabled && phase != RaftPullToRefreshPhase.Idle) {
            pull = 0f
            armed = false
            settle(0f)
        }
    }

    private fun dragBy(dy: Float): Float {
        val next = (pull + dy).coerceAtLeast(0f)
        val consumed = next - pull
        pull = next
        val offset = raftRubberBandOffset(pull, dimension, geometry.coefficient)
        contentOffset = offset
        indicatorOffset = offset
        if (style == RaftPullToRefreshStyle.Cupertino && offset >= geometry.triggerPx) armed = true
        // An armed pull stays a pull at rest: the release still refreshes, as UIRefreshControl does.
        if (pull == 0f && !armed) phase = RaftPullToRefreshPhase.Idle
        return consumed
    }

    /** Takes over content that is springing back, continuing from where it is (no jump). */
    private fun grab() {
        cancelAnimation()
        pull = raftRubberBandPull(contentOffset, dimension, geometry.coefficient)
        indicatorOffset = contentOffset
        armed = false
        phase = RaftPullToRefreshPhase.Pulling
    }

    private fun springToRefreshing(offsetVelocity: Float, moveContent: Boolean = true) {
        val contentTarget = if (moveContent) geometry.indicatorHoldPx else contentOffset
        launchAnimation {
            coroutineScope {
                launch {
                    animate(contentOffset, contentTarget, offsetVelocity, RaftPullToRefreshSpring) { value, _ ->
                        contentOffset = value
                    }
                }
                launch {
                    animate(indicatorOffset, geometry.indicatorHoldPx, offsetVelocity, RaftPullToRefreshSpring) { value, _ ->
                        indicatorOffset = value
                    }
                }
            }
            // The store had the whole spring to report the refresh. If it did not (the request was
            // dropped, or it finished before we saw it), there is nothing to wait for.
            if (phase == RaftPullToRefreshPhase.AwaitingRefresh && !storeRefreshing) settle(0f)
        }
    }

    private fun settle(offsetVelocity: Float) {
        phase = RaftPullToRefreshPhase.Settling
        launchAnimation {
            coroutineScope {
                launch {
                    animate(contentOffset, 0f, offsetVelocity, RaftPullToRefreshSpring) { value, _ ->
                        contentOffset = value
                    }
                }
                launch {
                    animate(indicatorOffset, 0f, offsetVelocity, RaftPullToRefreshSpring) { value, _ ->
                        indicatorOffset = value
                    }
                }
            }
            if (phase == RaftPullToRefreshPhase.Settling) phase = RaftPullToRefreshPhase.Idle
        }
    }

    private fun launchAnimation(block: suspend CoroutineScope.() -> Unit) {
        animationJob?.cancel()
        animationJob = scope.launch(block = block)
    }

    private fun cancelAnimation() {
        animationJob?.cancel()
        animationJob = null
    }
}

/**
 * Critically damped settle, close to UIScrollView's bounce-back and Android's stretch release.
 * Stiffness 400 (natural frequency 20 rad/s) brings the 60pt refresh gap to within 1pt of rest in
 * about 0.3s with no overshoot, the length of UIKit's default view animation and of the collapse
 * after `UIRefreshControl.endRefreshing()` (observed; Apple documents neither curve). A spring
 * rather than a fixed curve keeps the release velocity when the finger lets go mid-motion.
 */
internal val RaftPullToRefreshSpring =
    spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

/** Rubber-band dimension before the viewport is measured, in thresholds. */
private const val RaftPullToRefreshFallbackDimensionFactor = 8f
