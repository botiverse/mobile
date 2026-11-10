package build.raft.app.assistant

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalInspectionMode
import build.raft.designsystem.components.LocalRaftSpinnerStillStep

// The otter's loops and its waking (web index.css `assistant-otter-breathe`, `assistant-otter-zzz`,
// `assistant-otter-wake`; Artea #proj-assistant:#24): asleep (nothing new) it breathes, its belly
// on the edge, and z's float up from its head; something new pops it out from behind the edge,
// then two small bobs away from it, never a sideways sway. With reduced motion nothing moves:
// it sleeps still with one z, and wakes in place. The loops hold while the app is in the
// background (RaftAssistantOtter).
//
// Each keyframe list is the CSS one, the curve on each segment as CSS keyframes apply it.

/** One breath: web `assistant-otter-breathe 3.4s ease-in-out infinite`. */
internal const val RaftOtterBreatheMillis = 3400

/** One z's rise: web `assistant-otter-zzz 2.7s ease-in infinite`. */
internal const val RaftOtterZzzMillis = 2700

/** The second and third z start this much after the one before (web `animation-delay: 0.9s / 1.8s`). */
internal const val RaftOtterZzzStaggerMillis = 900

/** The waking: web `assistant-otter-wake 1.5s ease-in-out both`. */
internal const val RaftOtterWakeMillis = 1500

/** CSS `ease-in-out`. */
private val CssEaseInOut: Easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

/** CSS `ease-in`. */
private val CssEaseIn: Easing = CubicBezierEasing(0.42f, 0f, 1f, 1f)

/**
 * A test opting in to motion: every otter's loops and waking held this many ms in, so a capture
 * is one deterministic frame showing them. Null: they run, unless the subtree holds still
 * ([raftOtterHeldStill]).
 */
internal val LocalRaftAssistantOtterStillAt = staticCompositionLocalOf<Long?> { null }

/**
 * Whether the otter holds still here, as with reduced motion (no loops, no waking, one still z, and
 * its loop clock schedules no frames): wherever spinners are held still for one deterministic frame
 * (`LocalRaftSpinnerStillStep`, which every Robolectric screenshot capture provides) and in
 * inspection mode (previews, capture fixtures). A test that wants the motion provides
 * [LocalRaftAssistantOtterStillAt].
 */
@Composable
internal fun raftOtterHeldStill(): Boolean =
    LocalRaftSpinnerStillStep.current != null || LocalInspectionMode.current

/** The pose's scale while it breathes, about its bottom middle (web `transform-origin: 50% 100%`). */
internal class RaftOtterBreath(val scaleX: Float, val scaleY: Float)

/** Web: `scale(1, 1)` at 0% and 100%, `scale(1.015, 1.045)` at 50%. */
internal fun raftOtterBreath(millis: Long): RaftOtterBreath {
    val t = loopFraction(millis, RaftOtterBreatheMillis)
    return RaftOtterBreath(
        scaleX = keyframes(t, BreathStops, floatArrayOf(1f, 1.015f, 1f), CssEaseInOut),
        scaleY = keyframes(t, BreathStops, floatArrayOf(1f, 1.045f, 1f), CssEaseInOut),
    )
}

private val BreathStops = floatArrayOf(0f, 0.5f, 1f)

/** One z: its opacity, its move from where it starts (dp at the otter's scale 1) and its scale about its middle. */
internal class RaftOtterZ(val alpha: Float, val dx: Float, val dy: Float, val scale: Float)

/**
 * The [index]th z (0..2) [millis] into the loops. Web: opacity 0 → 0.9 at 20% → 0; the move
 * `translate(0, 0) scale(0.7)` → `translate(12px, -24px) scale(1.15)` over the whole rise.
 * Before its delay it is not there yet.
 */
internal fun raftOtterZ(index: Int, millis: Long): RaftOtterZ {
    val local = millis - index * RaftOtterZzzStaggerMillis
    if (local < 0) return RaftOtterZ(alpha = 0f, dx = 0f, dy = 0f, scale = 0.7f)
    val t = loopFraction(local, RaftOtterZzzMillis)
    val rise = CssEaseIn.transform(t)
    return RaftOtterZ(
        alpha = keyframes(t, ZStops, floatArrayOf(0f, 0.9f, 0f), CssEaseIn),
        dx = 12f * rise,
        dy = -24f * rise,
        scale = 0.7f + (1.15f - 0.7f) * rise,
    )
}

private val ZStops = floatArrayOf(0f, 0.2f, 1f)

/** With reduced motion (or before the loops' first frame): one z, still, at 70% (web `opacity: 0.7`). */
internal val RaftOtterStillZ = RaftOtterZ(alpha = 0.7f, dx = 0f, dy = 0f, scale = 1f)

/** The pose while it wakes: down from its rest (dp at scale 1, positive is into the edge) and its scale about its middle. */
internal class RaftOtterWake(val dy: Float, val scale: Float)

/**
 * [millis] into the waking. On a composer's top edge the edge is below it (web `--otter-ty: 1px`):
 * 16dp behind the edge at 90%, out 6dp past its rest at 106%, back, then two 5dp bobs up.
 * At [RaftOtterWakeMillis] and after: at rest.
 */
internal fun raftOtterWake(millis: Float): RaftOtterWake {
    val t = (millis / RaftOtterWakeMillis).coerceIn(0f, 1f)
    return RaftOtterWake(
        dy = keyframes(t, WakeStops, floatArrayOf(16f, -6f, 0f, -5f, 0f, -5f, 0f), CssEaseInOut),
        scale = keyframes(t, WakeStops, floatArrayOf(0.9f, 1.06f, 1f, 1f, 1f, 1f, 1f), CssEaseInOut),
    )
}

private val WakeStops = floatArrayOf(0f, 0.35f, 0.5f, 0.62f, 0.75f, 0.87f, 1f)

/**
 * How long the otter's loops have run, in ms, while [running]; held where it was otherwise (the
 * app in the background, the otter awake), and picked up from there. Null until their first frame:
 * with no frames (reduced motion, or a test that holds infinite animations) it sleeps still.
 */
@Composable
internal fun rememberRaftOtterLoopClock(running: Boolean): MutableState<Long?> {
    val elapsed = remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        val from = elapsed.value ?: 0L
        val start = withInfiniteAnimationFrameMillis { it } - from
        while (true) {
            withInfiniteAnimationFrameMillis { elapsed.value = it - start }
        }
    }
    return elapsed
}

private fun loopFraction(millis: Long, period: Int): Float = (millis.mod(period.toLong())).toFloat() / period

/** The value at [t] (0..1) through keyframes at [stops], [easing] on each segment. */
private fun keyframes(t: Float, stops: FloatArray, values: FloatArray, easing: Easing): Float {
    for (i in 0 until stops.size - 1) {
        if (t <= stops[i + 1]) {
            val p = (t - stops[i]) / (stops[i + 1] - stops[i])
            return values[i] + (values[i + 1] - values[i]) * easing.transform(p)
        }
    }
    return values.last()
}
