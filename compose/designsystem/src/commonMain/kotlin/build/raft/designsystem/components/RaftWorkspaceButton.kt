package build.raft.designsystem.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftStroke

/** Metrics of [RaftWorkspaceButton] (raft-ui `WorkspaceButton`). */
object RaftWorkspaceButtonDefaults {
    /** The server's avatar: raft-ui `Avatar size="sm"`. */
    val AvatarSize: Dp = 27.dp

    /** From the button's edge to the avatar: 10dp padding inside the 1.5dp border. Place the
     *  button this far before the content line, so the avatar sits on it and the press fill
     *  reaches past it (Alignment). */
    val ContentInset: Dp = 10.dp + RaftStroke.Control

    internal val AvatarNameGap: Dp = 8.dp
    internal val NameChevronGap: Dp = 4.dp
    internal val MarkGap: Dp = 4.dp
    /** The name's cap: raft-ui `WorkspaceButtonName`, at most 200dp, then an ellipsis. */
    val NameMaxWidth: Dp = 200.dp

    /** The name's cap while the workspace strip shows beside the button (web AppTopBar `max-w-[120px]`). */
    val NameMaxWidthBesideStrip: Dp = 120.dp
    internal val ChevronSize: Dp = 16.dp
}

/**
 * raft-ui WorkspaceButton: the server switcher on the frame, the same on every surface. The
 * server's [avatar], its [name] and a chevron, as one on-frame `md` button (36dp, 10dp each
 * side, `text-subhead` bold, `fill-frame-pressed` on press) that opens the server list.
 *
 * - [avatar] is the server's avatar at [RaftWorkspaceButtonDefaults.AvatarSize], with any
 *   unread mark on its corner; 8dp before the name.
 * - [name] is at most [nameMaxWidth] (200dp; 120dp beside the top bar's workspace strip), then
 *   an ellipsis. The button shrinks from the name: the avatar
 *   and the chevron keep their size.
 * - [mark] (the public-page globe) sits 4dp after the name.
 * - The 16dp chevron is part of the button and always shows, 4dp after the name.
 * - [open]: the server list is open; the button keeps its press fill until it closes, as
 *   raft-ui's on-frame button does under `data-popup-open`.
 */
@Composable
fun RaftWorkspaceButton(
    name: String,
    onClick: () -> Unit,
    avatar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    mark: (@Composable () -> Unit)? = null,
    open: Boolean = false,
    interactionSource: MutableInteractionSource? = null,
    nameMaxWidth: Dp = RaftWorkspaceButtonDefaults.NameMaxWidth,
) {
    val look = raftButtonLook(RaftButtonVariant.OnFrame, enabled = true, frameless = false, colors = RaftTheme.colors)
    val textStyle = raftButtonTextStyle(RaftButtonSize.Md, RaftButtonVariant.OnFrame, RaftTheme.typography)
    val inset = RaftWorkspaceButtonDefaults.ContentInset
    RaftButtonBox(
        look = look,
        onClick = onClick,
        modifier = modifier.height(36.dp),
        enabled = true,
        loading = false,
        loadingLabel = "",
        interactionSource = interactionSource,
        open = open,
    ) {
        Row(
            modifier = Modifier.padding(start = inset, end = inset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(contentAlignment = Alignment.Center) { avatar() }
            Spacer(Modifier.width(RaftWorkspaceButtonDefaults.AvatarNameGap))
            RaftText(
                text = name,
                style = textStyle,
                color = look.ink,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).widthIn(max = nameMaxWidth),
            )
            if (mark != null) {
                Spacer(Modifier.width(RaftWorkspaceButtonDefaults.MarkGap))
                Box(contentAlignment = Alignment.Center) { mark() }
            }
            Spacer(Modifier.width(RaftWorkspaceButtonDefaults.NameChevronGap))
            RaftGlyphIcon(RaftGlyph.ChevronDown, size = RaftWorkspaceButtonDefaults.ChevronSize, color = look.ink)
        }
    }
}
