package build.raft.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftUiPalette

/** raft-ui `InboxItemLeading` / `InboxItemLeadingBadge` metrics (`inbox-item.recipe.ts`). */
object RaftInboxItemLeadingDefaults {
    /** `size-9`: the leading slot; the avatar or tile inside is the 35dp `md` step. */
    val Slot: Dp = 36.dp

    /** `size-3.5`. */
    val BadgeSize: Dp = 14.dp

    /** `-right-0.5 -bottom-0.5`: 2dp past the slot, 2.5dp past a 35dp avatar or tile, as a presence light. */
    val BadgeOverhang: Dp = 2.dp

    /** `rounded-[3px]`. */
    val BadgeRadius: Dp = 3.dp

    /** `border border-sticker-ink`. */
    val BadgeBorder: Dp = 1.dp

    /** `outline-2 outline-layer-canvas`: the badge reads as cut out of the row. */
    val BadgeCutout: Dp = 2.dp

    /** `[&_svg]:size-2.5`. */
    val BadgeIcon: Dp = 10.dp
}

/**
 * raft-ui `InboxItemLeading`: the 36dp leading visual of an inbox row, holding a 35dp
 * avatar or IconTile ([content]). [badge] (a [RaftInboxItemLeadingBadge]) sits in its
 * bottom-right corner, the presence light's corner, so a tile and an avatar carry the same
 * mark; leave the avatar's own presence light out when there is a badge (one mark per corner).
 */
@Composable
fun RaftInboxItemLeading(
    modifier: Modifier = Modifier,
    badge: (@Composable () -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.size(RaftInboxItemLeadingDefaults.Slot), contentAlignment = Alignment.Center) {
        content()
        if (badge != null) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = RaftInboxItemLeadingDefaults.BadgeOverhang, y = RaftInboxItemLeadingDefaults.BadgeOverhang),
            ) { badge() }
        }
    }
}

/**
 * raft-ui `InboxItemLeadingBadge` (`@botiverse/raft-ui/wip`): the thread badge on a thread
 * row's leading tile or avatar. 14dp, 3dp radius, a 1dp `sticker-ink` edge, `info-soft` over
 * the canvas, ringed 2dp with [cutoutColor] (`layer-canvas`). [icon] is drawn in `foreground`
 * through [LocalRaftContentColor]: raft-ui puts `ThreadIcon` there ([RaftConversationKindIcon]
 * `Thread`), sized to [RaftInboxItemLeadingDefaults.BadgeIcon].
 * Hidden from accessibility: the row's name says it is a thread.
 */
@Composable
fun RaftInboxItemLeadingBadge(
    modifier: Modifier = Modifier,
    cutoutColor: Color = RaftTheme.colors.layerCanvas,
    icon: @Composable () -> Unit,
) {
    val colors = RaftTheme.colors
    val radius = RaftInboxItemLeadingDefaults.BadgeRadius
    val shape = RoundedCornerShape(radius)
    Box(
        modifier
            .size(RaftInboxItemLeadingDefaults.BadgeSize)
            .clearAndSetSemantics { }
            .raftOuterRing(cutoutColor, RaftInboxItemLeadingDefaults.BadgeCutout, radius)
            .background(colors.infoSoft.compositeOver(colors.layerCanvas), shape)
            .border(RaftInboxItemLeadingDefaults.BadgeBorder, RaftUiPalette.stickerInk, shape),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalRaftContentColor provides colors.foreground, content = icon)
    }
}
