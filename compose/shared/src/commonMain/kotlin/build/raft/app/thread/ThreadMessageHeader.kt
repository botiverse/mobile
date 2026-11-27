package build.raft.app.thread

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import build.raft.app.fill
import kotlin.math.roundToInt
import build.raft.designsystem.text.raftCarryFirstBaseline
import build.raft.designsystem.text.rememberRaftCarriedBaseline

/**
 * A message's header line (raft-ui `MessageItemHeader`, `items-baseline gap-1.5`): the sender
 * cluster (web `MessageItemSenderGroup`: name, badges, model, subtitle, "sent by"), then the time
 * outside it, so a long name can never push the time off the row. The time lines up with the
 * name's first baseline, not whichever badge or label in the cluster sits highest.
 *
 * [name] gets two modifiers: `inCluster` for its outermost node in the cluster (the name text, or
 * the tooltip round it), `text` for the name text itself. [meta] follows it in the cluster; its
 * texts line up with `Modifier.alignByBaseline()` (single texts only). [time] (a single text, or
 * nothing) gets the modifier that puts it on the name's baseline.
 *
 * Neither row reads the alignment line of a group while it measures: the name's tooltip is a box
 * and the cluster a row, and reading their baselines during measure made Compose 1.12 lay out
 * their children before a reused message row was placed ("LayoutNode … not found in RectList",
 * suspected cause of iOS crashes b692fce7 and 50046806 in a message list: a window size record
 * precedes each exception log, the SIGABRT has second precision only; not reproduced,
 * androidx fd550bed79). The name text carries
 * its own baseline up instead ([build.raft.designsystem.text.RaftCarriedBaseline]). Same pixels:
 *  - the name text sits at the top of its tooltip box, so its baseline is the box's;
 *  - in the cluster a baseline-aligned part is placed at the deepest baseline less its own, so the
 *    name's line in the cluster is the deepest of the cluster's baselines: the name's, as the
 *    name (subhead 15/20) runs deeper than the meta texts (caption 12/16).
 * So [meta] holds only single-line texts smaller than the name: a larger or padded badge would run
 * deeper, push the name down, and the carried baseline would put the time a few pixels off.
 *
 * In a message column at least 768dp wide the line keeps its last fifth empty (raft-ui 0.102.1,
 * raft-design #1291, [threadMessageHeaderWideEndRoom]). Pass the toolbar's fade in [modifier]:
 * it is drawn on the whole line, the empty fifth included, as raft-ui's mask is.
 */
@Composable
internal fun ThreadMessageHeaderRow(
    alignEnd: Boolean,
    modifier: Modifier = Modifier,
    time: @Composable (Modifier) -> Unit = {},
    meta: @Composable RowScope.() -> Unit = {},
    name: @Composable RowScope.(inCluster: Modifier, text: Modifier) -> Unit,
) {
    val nameBaseline = rememberRaftCarriedBaseline()
    Row(
        // An own message drawn at the end keeps the whole line: its end is the message's edge.
        modifier = if (alignEnd) modifier else modifier.threadMessageHeaderWideEndRoom(),
        verticalAlignment = Alignment.CenterVertically,
        // raft-ui `MessageItemHeader`: gap-1.5.
        horizontalArrangement = if (alignEnd) Arrangement.spacedBy(6.dp, Alignment.End) else Arrangement.spacedBy(6.dp),
    ) {
        // The name has no fixed cap (web `max-w-full`): it truncates only when it alone is wider
        // than the cluster, and the subtitle gives up its room first.
        Row(
            modifier = Modifier.weight(1f, fill = false).alignBy { nameBaseline.px },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            name(Modifier.alignBy { nameBaseline.px }, Modifier.raftCarryFirstBaseline(nameBaseline))
            meta()
        }
        // The time is a single text: reading its own baseline lays out no children.
        time(Modifier.alignByBaseline())
    }
}

/** raft-ui MessageItem `@3xl/message` (48rem): from this column width the header keeps [ThreadMessageHeaderWideEndFraction] empty. */
internal val ThreadMessageHeaderWideColumn: Dp = 768.dp

/** raft-ui MessageItemHeader `pe-[20%]`: the share of the column a wide header leaves empty at its end. */
internal const val ThreadMessageHeaderWideEndFraction = 0.2f

/**
 * raft-ui 0.102.1 (raft-design #1291, slock #14749): in a message column at least 768dp wide, the
 * sender line pads its end by a fifth of the column, so everything on it, a long description
 * included, fits in the first 80% and truncates there; the line reads as a label for the message,
 * not a sentence across the page. Narrower columns (every phone, a thread pane) keep the full width.
 *
 * Measured on the width the line is given (the message's column, as raft-ui's `@container/message`),
 * never the window's. Padding inside the line's own box, as raft-ui's: the toolbar's fade applied
 * before this modifier still runs from the line's real end.
 */
internal fun Modifier.threadMessageHeaderWideEndRoom(): Modifier =
    layout { measurable, constraints ->
        val wide = constraints.hasBoundedWidth && constraints.maxWidth >= ThreadMessageHeaderWideColumn.roundToPx()
        if (!wide) {
            val placeable = measurable.measure(constraints)
            return@layout layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
        }
        val end = (constraints.maxWidth * ThreadMessageHeaderWideEndFraction).roundToInt()
        val placeable = measurable.measure(constraints.offset(horizontal = -end))
        val width = (placeable.width + end).coerceIn(constraints.minWidth, constraints.maxWidth)
        layout(width, placeable.height) { placeable.placeRelative(0, 0) }
    }
