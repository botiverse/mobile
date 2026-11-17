package build.raft.designsystem.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.tokens.RaftUiPalette

/**
 * A round sticker face: [image] fills a [size] disc, the avatar's 1.5dp `sticker-ink` edge is
 * drawn over its outer 1.5dp, and the 1.5dp `sticker-edge` die-cut ring goes outside it (dark
 * mode; transparent in light). Full colour: it does not take the content colour of the row it
 * sits in. Decorative, hidden from accessibility (the row it leads carries the name).
 *
 * Web `OttieRailAvatar` (botiverse/slock `packages/web/src/assistant/OttieRailAvatar.tsx`): the
 * personal assistant's round face on the rail (28dp) and on the phone Home's Assistant row
 * (24dp), `rounded-full border-[1.5px] border-sticker-ink shadow-[0_0_0_1.5px_var(--sticker-edge)]`
 * with the image laid on the whole box. Round on purpose, not the avatar's sticker square: a
 * character, not a person in a list.
 */
@Composable
fun RaftStickerDisc(
    size: Dp,
    modifier: Modifier = Modifier,
    image: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .size(size)
            .clearAndSetSemantics { }
            .raftDieCutEdge(RaftTheme.colors.stickerEdge, size / 2)
            .clip(CircleShape)
            // Drawn after the image, over its outer 1.5dp (web: the image covers the whole box).
            .border(RaftStroke.Control, RaftUiPalette.stickerInk, CircleShape),
        contentAlignment = Alignment.Center,
        content = image,
    )
}
