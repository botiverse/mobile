package build.raft.designsystem.text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.LayoutModifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.unit.Constraints

/**
 * A text's first baseline carried up to the `Row` that lines it up with its neighbours, so that
 * `Row` never reads the baseline of a group (a `Box` or `Row` round the text) while it measures.
 *
 * Why: a parent that reads a child group's alignment line during measure makes Compose lay out
 * (and mark placed) that group's children before the parent itself is placed. For a reused
 * `LazyColumn` item, Compose 1.12 then throws "LayoutNode … not found in RectList" from
 * RectManager (Android crashes c41fd092, 52b93946; iOS crashes b692fce7, 50046806; fixed upstream in Compose 1.13,
 * androidx fd550bed79). A single text node has no children, so reading its own baseline is safe.
 *
 * Use: [raftCarryFirstBaseline] on the text itself; in the `Row`, `Modifier.alignBy { it.px }`
 * (with the carried baseline) on the group that holds the text, at the group's top. The text is
 * measured as part of the group before the `Row` asks for the line, so the value is the current
 * one in the same pass (no frame aligned by an older value); a later change lays the `Row` out
 * again. A text that is not at its group's top (centred beside a taller part) adds its offset,
 * from the group's measured height and [heightPx].
 */
@Stable
class RaftCarriedBaseline {
    private val baseline = mutableIntStateOf(AlignmentLine.Unspecified)
    private val height = mutableIntStateOf(0)

    /** The text's first baseline from its top in px; [AlignmentLine.Unspecified] before it measured. */
    val px: Int
        get() = baseline.intValue

    /** The text's measured height in px; 0 before it measured. */
    val heightPx: Int
        get() = height.intValue

    internal fun update(value: Int, measuredHeight: Int) {
        if (baseline.intValue != value) baseline.intValue = value
        if (height.intValue != measuredHeight) height.intValue = measuredHeight
    }
}

@Composable
fun rememberRaftCarriedBaseline(): RaftCarriedBaseline = remember { RaftCarriedBaseline() }

/**
 * On a single text node (no child layouts): keeps its first baseline in [baseline] as it is
 * measured. The modifiers before this one are outside what it measures, so put it where the
 * text's box starts (after any outer padding the group adds, there is none in its users).
 */
fun Modifier.raftCarryFirstBaseline(baseline: RaftCarriedBaseline): Modifier = this then RaftCarryFirstBaseline(baseline)

/** [raftCarryFirstBaseline]: its intrinsic sizes are the text's own, so asking them writes nothing. */
private data class RaftCarryFirstBaseline(private val baseline: RaftCarriedBaseline) : LayoutModifier {
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        // This node's own text: reading it lays out no children.
        baseline.update(placeable[FirstBaseline], placeable.height)
        return layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurable: IntrinsicMeasurable, height: Int): Int = measurable.minIntrinsicWidth(height)

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurable: IntrinsicMeasurable, height: Int): Int = measurable.maxIntrinsicWidth(height)

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int): Int = measurable.minIntrinsicHeight(width)

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int): Int = measurable.maxIntrinsicHeight(width)
}
