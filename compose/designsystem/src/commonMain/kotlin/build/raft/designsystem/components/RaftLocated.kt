package build.raft.designsystem.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline

/**
 * raft-ui 0.22.1 `LOCATED_MS` (#719): the located highlight is gone 2s after it starts
 * (design/foundations/states.md#located).
 */
const val RaftLocatedMillis = 2000

/** raft-ui `@keyframes raft-located`: `info-soft` at full strength until 60% (1.2s). */
const val RaftLocatedHoldMillis = 1200

/**
 * Strength (0..1) of the located highlight's `info-soft` [elapsedMillis] after it started:
 * raft-ui `@keyframes raft-located`, full from 0 to 1.2s, then an `ease-out`
 * (`cubic-bezier(0, 0, 0.58, 1)`) fade to transparent, gone at 2s. With [reducedMotion]
 * (raft-ui `raft-located-still`) it is held still for the same 2s, then off with no fade.
 */
fun raftLocatedStrengthAt(elapsedMillis: Long, reducedMotion: Boolean = false): Float {
    if (elapsedMillis < 0 || elapsedMillis >= RaftLocatedMillis) return 0f
    if (reducedMotion || elapsedMillis <= RaftLocatedHoldMillis) return 1f
    val t = (elapsedMillis - RaftLocatedHoldMillis).toFloat() / (RaftLocatedMillis - RaftLocatedHoldMillis)
    return 1f - cubicBezierEase(t, 0f, 0f, 0.58f, 1f)
}

/**
 * raft-ui 0.22.1 located highlight (#719, `useLocated` + `data-located`): "where did that
 * take me?". After a jump, the item someone arrived at shows `info-soft` at once, holds it
 * 1.2s and fades out, gone at [RaftLocatedMillis]; one look for every jump (a link to a
 * message, a search result opened and come back from, Activity's unread jump).
 *
 * Call [locate] once the item is on screen, after the scroll that brings it there; it
 * suspends for the 2s and clears the highlight when it ends or is cancelled. A new [locate]
 * (the same item or another) restarts it. Paint it with [raftLocated] or read [strengthFor].
 * A stronger mark wins: don't paint it on an item that is selected, current, a search match,
 * checked or has its menu open. Only a look: it adds nothing to semantics.
 */
@Stable
class RaftLocatedState(private val reducedMotion: Boolean = false) {
    /** The item the highlight is on, or null. */
    var key: Any? by mutableStateOf(null)
        private set

    private var strength by mutableFloatStateOf(0f)
    private var generation = 0

    /** The highlight's strength (0..1) on [key]; 0 for any other item. */
    fun strengthFor(key: Any): Float = if (this.key == key) strength else 0f

    /** Shows the highlight on [key] for [RaftLocatedMillis], frame by frame. */
    suspend fun locate(key: Any) {
        val run = ++generation
        this.key = key
        strength = 1f
        try {
            val start = withFrameMillis { it }
            while (true) {
                val elapsed = withFrameMillis { it } - start
                if (elapsed >= RaftLocatedMillis) break
                strength = raftLocatedStrengthAt(elapsed, reducedMotion)
            }
        } finally {
            // A newer locate keeps its own item. No suspending call: this also runs on
            // cancellation (the page left, the list was disposed).
            if (generation == run) {
                this.key = null
                strength = 0f
            }
        }
    }
}

/** A [RaftLocatedState] kept across recompositions. */
@Composable
fun rememberRaftLocatedState(reducedMotion: Boolean = false): RaftLocatedState =
    remember(reducedMotion) { RaftLocatedState(reducedMotion) }

/**
 * The located highlight's strength for one item driven by a flag: it starts when [active]
 * turns true and stops (no fade) if [active] turns false first. For an item that lays the
 * highlight in its own fill (a message row, whose fill the delegated face's ring matches):
 * `infoSoft.copy(alpha = infoSoft.alpha * strength)` over the row's own fill.
 */
@Composable
fun raftLocatedStrength(active: Boolean, reducedMotion: Boolean = false): Float {
    val state = rememberRaftLocatedState(reducedMotion)
    LaunchedEffect(state, active) {
        if (active) state.locate(Unit)
    }
    return state.strengthFor(Unit)
}

/**
 * Paints [state]'s located highlight on the item [key] in [fill] (`RaftTheme.colors.infoSoft`),
 * clipped to the item's [shape]. Put it after the item's own background: it lies over the
 * item's fill and under its content, as raft-ui's `background-image` over `background-color`.
 * Read in the draw phase only: the fade never recomposes.
 */
fun Modifier.raftLocated(
    state: RaftLocatedState,
    key: Any,
    fill: Color,
    shape: Shape = RectangleShape,
): Modifier =
    drawBehind {
        val strength = state.strengthFor(key)
        if (strength > 0f) {
            if (shape == RectangleShape) {
                drawRect(fill, alpha = strength)
            } else {
                drawOutline(shape.createOutline(size, layoutDirection, this), fill, alpha = strength)
            }
        }
    }
