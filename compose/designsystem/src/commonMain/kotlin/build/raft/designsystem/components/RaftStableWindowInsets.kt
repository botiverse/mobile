package build.raft.designsystem.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection

/*
 * Why the insets that reach `windowInsetsPadding` / `consumeWindowInsets` must keep one identity.
 *
 * Compose's insets modifiers re-run `update()` on their node whenever the element's insets (or
 * padding values) compare unequal, and `update()` recomputes what the node consumes by walking its
 * descendants (`InsetsConsumingModifierNode.invalidateChildConsumedInsets`). That walk throws
 * "visitSubtreeIf called on an unattached node" when the node is detached, which it is while
 * movable content recomposed with new insets is put back into the tree in the same frame
 * (`LayoutNode.attach` applies the pending modifier before its nodes are attached). On iOS that
 * aborted the app (Hands crash 8da5d67f: a rotation during a shell relayout).
 *
 * Two things made the element unequal: a safe-area value built into a new `WindowInsets` /
 * `PaddingValues` whenever it changes (a rotation), and, on iOS, `WindowInsets.safeDrawing`, which
 * is a new object with identity equality on every read, so every recomposition.
 *
 * These hand out ONE object per call site that reads its current value from snapshot state when it
 * is measured: the element stays equal and `update()` never runs; a new value only re-measures the
 * nodes that read it (the padding node and whatever below consumed through it). The values, and so
 * the layout, are exactly those of the wrapped insets.
 */

/** [insets] behind one stable object; see the note at the top of this file. */
@Composable
fun rememberRaftStableWindowInsets(insets: WindowInsets): WindowInsets {
    val stable = remember { RaftStateWindowInsets(insets) }
    stable.current = insets
    return stable
}

/** [padding] behind one stable object, for `consumeWindowInsets(PaddingValues)`; see the note above. */
@Composable
fun rememberRaftStablePaddingValues(padding: PaddingValues): PaddingValues {
    val stable = remember { RaftStatePaddingValues(padding) }
    stable.current = padding
    return stable
}

@Stable
private class RaftStateWindowInsets(initial: WindowInsets) : WindowInsets {
    var current: WindowInsets by mutableStateOf(initial)

    override fun getLeft(density: Density, layoutDirection: LayoutDirection): Int = current.getLeft(density, layoutDirection)

    override fun getTop(density: Density): Int = current.getTop(density)

    override fun getRight(density: Density, layoutDirection: LayoutDirection): Int = current.getRight(density, layoutDirection)

    override fun getBottom(density: Density): Int = current.getBottom(density)

    override fun toString(): String = "RaftStableWindowInsets($current)"
}

@Stable
private class RaftStatePaddingValues(initial: PaddingValues) : PaddingValues {
    var current: PaddingValues by mutableStateOf(initial)

    override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp = current.calculateLeftPadding(layoutDirection)

    override fun calculateTopPadding(): Dp = current.calculateTopPadding()

    override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp = current.calculateRightPadding(layoutDirection)

    override fun calculateBottomPadding(): Dp = current.calculateBottomPadding()

    override fun toString(): String = "RaftStablePaddingValues($current)"
}
