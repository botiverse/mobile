package build.raft.designsystem.text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.layout

/**
 * A text's first baseline carried up to the `Row` that lines it up with its neighbours, so that
 * `Row` never reads the baseline of a group (a `Box` or `Row` round the text) while it measures.
 *
 * Why: a parent that reads a child group's alignment line during measure makes Compose lay out
 * (and mark placed) that group's children before the parent itself is placed. For a reused
 * `LazyColumn` item, Compose 1.12 then throws "LayoutNode … not found in RectList" from
 * RectManager (iOS crashes c41fd092, b692fce7, 50046806; fixed upstream in Compose 1.13,
 * androidx fd550bed79). A single text node has no children, so reading its own baseline is safe.
 *
 * Use: [raftCarryFirstBaseline] on the text itself; in the `Row`, `Modifier.alignBy { it.px }`
 * (with the carried baseline) on the group that holds the text, at the group's top. The text is
 * measured as part of the group before the `Row` asks for the line, so the value is the current
 * one in the same pass (no frame aligned by an older value); a later change lays the `Row` out
 * again.
 */
@Stable
class RaftCarriedBaseline {
    private val baseline = mutableIntStateOf(AlignmentLine.Unspecified)

    /** The text's first baseline from its top in px; [AlignmentLine.Unspecified] before it measured. */
    val px: Int
        get() = baseline.intValue

    internal fun update(value: Int) {
        if (baseline.intValue != value) baseline.intValue = value
    }
}

@Composable
fun rememberRaftCarriedBaseline(): RaftCarriedBaseline = remember { RaftCarriedBaseline() }

/**
 * On a single text node (no child layouts): keeps its first baseline in [baseline] as it is
 * measured. The modifiers before this one are outside what it measures, so put it where the
 * text's box starts (after any outer padding the group adds, there is none in its users).
 */
fun Modifier.raftCarryFirstBaseline(baseline: RaftCarriedBaseline): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        // This node's own text: reading it lays out no children.
        baseline.update(placeable[FirstBaseline])
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
