package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
 * suspected cause of iOS crashes b692fce7 and 50046806, which followed a window size change in a
 * message list; not reproduced, androidx fd550bed79). The name text carries
 * its own baseline up instead ([build.raft.designsystem.text.RaftCarriedBaseline]). Same pixels:
 *  - the name text sits at the top of its tooltip box, so its baseline is the box's;
 *  - in the cluster a baseline-aligned part is placed at the deepest baseline less its own, so the
 *    name's line in the cluster is the deepest of the cluster's baselines: the name's, as the
 *    name (subhead 15/20) runs deeper than the meta texts (caption 12/16).
 * So [meta] holds only single-line texts smaller than the name: a larger or padded badge would run
 * deeper, push the name down, and the carried baseline would put the time a few pixels off.
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
        modifier = modifier,
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
