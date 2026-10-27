package build.raft.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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

/** raft-ui `SidebarItemThreadCount` `label`: the words read after the thread number. */
const val RaftThreadCountLabel: String = "threads with new replies"

/**
 * What a screen reader says for an unread count with a thread segment: "5 unread, 2 threads
 * with new replies", either part left out at 0. Pass a localized [threadLabel].
 */
fun raftUnreadCountDescription(count: Int, threadCount: Int, threadLabel: String = RaftThreadCountLabel): String =
    listOfNotNull(
        raftUnreadCountDescription(count).takeIf { count > 0 },
        "$threadCount $threadLabel".takeIf { threadCount > 0 },
    ).joinToString(", ")

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
 * The unread count with a thread segment (raft-ui `SidebarItemCount` holding
 * `SidebarItemCountSeparator` and `SidebarItemThreadCount`): the same pink sticker as
 * [RaftUnreadCount], reading `5 · [thread] 2`, or `[thread] 2` alone when only followed
 * threads have new replies. The thread is raft-ui's `ThreadIcon` at 12dp with a 1.75
 * stroke, 2dp before its number; the dot is 60% ink with 2dp each side. Shows nothing when
 * both are 0, and is exactly [RaftUnreadCount] when [threadCount] is 0.
 *
 * Screen readers read [contentDescription], by default "5 unread, 2 threads with new
 * replies" ([raftUnreadCountDescription]); pass localized wording.
 */
@Composable
fun RaftUnreadCount(
    count: Int,
    threadCount: Int,
    modifier: Modifier = Modifier,
    contentDescription: String? = raftUnreadCountDescription(count, threadCount),
) {
    if (threadCount <= 0) {
        RaftUnreadCount(count = count, modifier = modifier, contentDescription = contentDescription)
        return
    }
    val look = raftBadgeLook(RaftBadgeAppearance.Solid, RaftBadgeVariant.Accent, enabled = true, colors = RaftTheme.colors)
    val style = RaftTheme.typography.monoMeta.copy(fontWeight = look.weight, fontFeatureSettings = "tnum")
    val shape = RoundedCornerShape(look.radius)
    Row(
        modifier =
            modifier
                .then(if (contentDescription != null) Modifier.clearAndSetSemantics { this.contentDescription = contentDescription } else Modifier)
                .raftDieCutEdge(look.outerRing, look.radius)
                .height(look.height)
                .widthIn(min = look.minWidth)
                .background(look.fill, shape)
                .border(look.edgeWidth, look.edge, shape)
                .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (count > 0) {
            RaftText(raftUnreadCountText(count), style = style, color = look.ink, maxLines = 1)
            // `SidebarItemCountSeparator`: `mx-0.5 opacity-60`, decorative.
            RaftText(
                "\u00B7",
                modifier = Modifier.padding(horizontal = 2.dp).clearAndSetSemantics {},
                style = style,
                color = look.ink.copy(alpha = 0.6f),
                maxLines = 1,
            )
        }
        RaftConversationKindIcon(RaftConversationKindGlyph.Thread, size = 12.dp, color = look.ink, strokeWidth = 1.75f)
        Spacer(Modifier.width(2.dp))
        RaftText(raftUnreadCountText(threadCount), style = style, color = look.ink, maxLines = 1)
    }
}

/**
 * The @ marker: someone mentioned the person. A 20dp `you` sticker holding a 13dp
 * `at-sign`, with the ink edge and the die-cut edge (raft-ui `SidebarItemIndicator`,
 * `bg-you`: addressed to you). It replaces the unread count and never holds a number.
 */
@Composable
fun RaftMentionMarker(
    modifier: Modifier = Modifier,
    contentDescription: String? = "Mentions you",
) {
    val look = raftActiveStickerLook(active = true, colors = RaftTheme.colors).copy(fill = RaftUiPalette.you)
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

/**
 * The runtime chip, after the web AgentDetailPanel `RUNTIME_CHIP_CLASS`: the soft
 * `information` Badge in machine type (`font-mono text-mono-meta font-normal`), so a runtime
 * name ("Claude Code", "Built-in Pi") reads as what a machine runs, not as a word.
 * [showCpuIcon] leads it with Lucide `cpu` (14dp, badge ink), as the profile hero does
 * (`agent-profile-runtime-chip`); the Runtime row of the config section leaves it out.
 * [trailing] is the account-usage light (web `RuntimeAccountUsageChip`: Status `sm`, `ml-1`).
 */
@Composable
fun RaftRuntimeChip(
    text: String,
    modifier: Modifier = Modifier,
    showCpuIcon: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    RaftBadgeFrame(
        text = text,
        // Web `RUNTIME_CHIP_CLASS` on the soft `information` Badge: Space Mono at 400.
        look =
            raftBadgeLook(RaftBadgeAppearance.Soft, RaftBadgeVariant.Information, enabled = true, colors = RaftTheme.colors)
                .copy(mono = true, weight = FontWeight.Normal),
        appearance = RaftBadgeAppearance.Soft,
        modifier = modifier,
        icon = if (showCpuIcon) ({ RaftGlyphIcon(RaftGlyph.Cpu, size = 14.dp) }) else null,
        trailing = trailing,
    )
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
        // raft-ui 0.20.1: a light beside text is 9dp, centred on the first line.
        icon = { RaftStatusDot(presence = presence) },
    )
}

private fun Modifier.imageSemantics(contentDescription: String?): Modifier =
    clearAndSetSemantics {
        if (contentDescription != null) {
            this.contentDescription = contentDescription
            role = Role.Image
        }
    }
