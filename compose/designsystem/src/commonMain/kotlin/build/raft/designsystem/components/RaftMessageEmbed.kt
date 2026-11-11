package build.raft.designsystem.components

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke

// raft-ui MessageEmbed (raft-design 1fc7546f packages/ui/src/components/message-embed/
// message-embed.recipe.ts): the card under a message for something its text links to
// (`rounded-container border border-line-hairline bg-layer-card`), with a header, content and
// footer slot (`px-3 py-2` each; the content slot has no padding).
//
// raft-ui 0.84.0 (cards in messages, stdrc 2026-11-10): every card in a message takes its frame and
// one of two widths from here, and its parts from one recipe (`messageCard`): a 12dp inset, the
// title in `text-subhead` bold (2 lines), at most two buttons. Change one card and the others follow.

object RaftMessageEmbedDefaults {
    /**
     * `size="item"`: a card that opens one thing (an app link, an Artifact, an action card), as
     * wide as the item popover, at most 400dp (on the web the message's width below 768px).
     */
    val ItemMaxWidth: Dp = 400.dp

    /** `size="content"`: a card that shows other messages (a forwarded bundle, replies), at most 560dp. */
    val ContentMaxWidth: Dp = 560.dp

    /** The card's inset (`messageCard` `inset` / `body`: `p-3`). */
    val Inset: Dp = 12.dp

    /** The Artifact card's width: an item card's ([ItemMaxWidth]; slock #14106, was 440dp). */
    val MaxWidth: Dp = ItemMaxWidth

    /** `gap-2.5` between a header's glyph and its text. */
    val HeaderGap: Dp = 10.dp
}

/**
 * raft-ui `MessageEmbed`: `layer-card`, a `line-hairline` edge, the container radius, full width
 * up to [maxWidth]. With [onClick] it is `interactive` (`hover:overlay-subtle
 * active:overlay-medium`): the whole card is the target, as a file card is; without it, a
 * display card (no hover, no press, nothing to focus).
 */
@Composable
fun RaftMessageEmbed(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    maxWidth: Dp = RaftMessageEmbedDefaults.MaxWidth,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = RaftTheme.colors
    val shape = RoundedCornerShape(RaftRadius.Container)
    val source = remember { MutableInteractionSource() }
    val overlay = rememberRaftStateOverlay(source, RaftHoverTier.Subtle, enabled = onClick != null)
    // Read unconditionally: remembered interaction state behind `&&` changes slots when onClick flips.
    val isFocused = raftIsFocused(source)
    val focused = onClick != null && isFocused
    Column(
        modifier
            .widthIn(max = maxWidth)
            .fillMaxWidth()
            .raftFocusRing(focused, RaftRadius.Container, colors)
            .clip(shape)
            .background(colors.layerCard, shape)
            .raftStateOverlay(overlay, shape)
            .then(
                if (onClick != null) {
                    Modifier
                        .raftPointerHover(source)
                        .clickable(source, indication = null, role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .border(RaftStroke.Hairline, colors.lineHairline, shape)
            // CSS `border`: the 1dp edge takes layout room.
            .padding(RaftStroke.Hairline),
        content = content,
    )
}

/** `MessageEmbedHeader` as the Artifact card lays it out: `px-3 py-2`, a top-aligned row, 10dp apart. */
@Composable
fun RaftMessageEmbedHeader(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(RaftMessageEmbedDefaults.HeaderGap),
        verticalAlignment = Alignment.Top,
        content = content,
    )
}

/** `MessageEmbedContent`: no padding of its own (a cover image runs edge to edge). */
@Composable
fun RaftMessageEmbedContent(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth(), content = content)
}

/**
 * `MessageEmbedFooter` as the Artifact card lays it out: `px-3 py-2`, `flex-wrap items-center
 * gap-x-2.5 gap-y-1`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RaftMessageEmbedFooter(
    modifier: Modifier = Modifier,
    content: @Composable FlowRowScope.() -> Unit,
) {
    FlowRow(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
