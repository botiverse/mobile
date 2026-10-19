package build.raft.designsystem.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftUiPalette

// The labels built from Badge and Status (raft-design Badge, Status > Agent state chips /
// Agent tag): the unread count, the @ marker, the muted count, the agent tag and the
// agent state chip. They fix the variant so a screen cannot pick the wrong colour.

/** The count as raft-ui writes it: the number alone, `99+` above 99. */
fun raftUnreadCountText(count: Int): String = if (count > 99) "99+" else count.coerceAtLeast(0).toString()

/** What a screen reader says for an unread count: the full number ("120 unread", not "99+"). */
fun raftUnreadCountDescription(count: Int): String = "${count.coerceAtLeast(0)} unread"

/**
 * The unread count: a pink (`attention`) sticker with Space Mono tabular figures, the
 * ink edge and the die-cut edge. Shows nothing for 0. Never on a selected row, never next
 * to an @ marker, never in a muted place (use [RaftMutedCount]).
 *
 * Screen readers read [contentDescription], by default "N unread" with the full count
 * ([raftUnreadCountDescription]); pass your own wording, or null to read the badge's
 * text as drawn.
 */
@Composable
fun RaftUnreadCount(
    count: Int,
    modifier: Modifier = Modifier,
    contentDescription: String? = raftUnreadCountDescription(count),
) {
    if (count <= 0) return
    RaftBadge(
        text = raftUnreadCountText(count),
        // A number, not a picture: no image role.
        modifier = if (contentDescription != null) modifier.clearAndSetSemantics { this.contentDescription = contentDescription } else modifier,
        variant = RaftBadgeVariant.Accent,
    )
}

/**
 * The @ marker: someone mentioned the person. A 20dp `brand` sticker holding a 13dp
 * `at-sign`, with the ink edge and the die-cut edge (raft-ui `SidebarItemIndicator`). It
 * replaces the unread count and never holds a number.
 */
@Composable
fun RaftMentionMarker(
    modifier: Modifier = Modifier,
    contentDescription: String? = "Mentions you",
) {
    val look = raftActiveStickerLook(active = true, colors = RaftTheme.colors)
    Box(
        modifier
            .size(20.dp)
            .raftSticker(look, RaftRadius.Sticker, colors = RaftTheme.colors)
            .imageSemantics(contentDescription),
        contentAlignment = Alignment.Center,
    ) {
        RaftGlyphIcon(RaftGlyph.AtSign, size = 13.dp, color = RaftUiPalette.stickerInk)
    }
}

/**
 * Unread items in a muted place: a gray number in Space Mono (`text-mono-meta`,
 * `foreground-hint`), not a badge.
 */
@Composable
fun RaftMutedCount(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    RaftText(
        text = raftUnreadCountText(count),
        modifier = modifier,
        style = RaftTheme.typography.monoMeta.copy(fontWeight = FontWeight.Normal, fontFeatureSettings = "tnum"),
        color = RaftTheme.colors.foregroundHint,
        maxLines = 1,
    )
}

/** The `agent` tag after an agent's name: an outline badge (Space Mono, hairline). */
@Composable
fun RaftAgentTag(modifier: Modifier = Modifier, text: String = "agent") {
    RaftBadge(text = text, modifier = modifier, appearance = RaftBadgeAppearance.Outline)
}

/** The soft tint of each presence light (Status > Agent state chips). */
internal fun raftPresenceChipVariant(presence: RaftPresence): RaftBadgeVariant =
    when (presence) {
        RaftPresence.Online -> RaftBadgeVariant.Success
        RaftPresence.Busy -> RaftBadgeVariant.Primary
        RaftPresence.Error -> RaftBadgeVariant.Warning
        RaftPresence.Offline -> RaftBadgeVariant.Muted
        RaftPresence.External -> RaftBadgeVariant.Information
    }

/**
 * An agent state chip: a soft badge in the light's tint (`brand-soft` for Working) with
 * the presence pixel and its word ([RaftPresence.word], Title Case).
 */
@Composable
fun RaftAgentStateChip(presence: RaftPresence, modifier: Modifier = Modifier) {
    RaftBadge(
        text = presence.word,
        modifier = modifier,
        appearance = RaftBadgeAppearance.Soft,
        variant = raftPresenceChipVariant(presence),
        icon = { RaftStatusDot(presence = presence, size = RaftStatusSize.Sm) },
    )
}

private fun Modifier.imageSemantics(contentDescription: String?): Modifier =
    clearAndSetSemantics {
        if (contentDescription != null) {
            this.contentDescription = contentDescription
            role = Role.Image
        }
    }
