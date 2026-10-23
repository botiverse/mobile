package build.raft.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftStroke

// The web top bar's workspace strip (packages/web/src/components/topbar/WorkspaceStrip.tsx,
// workspaceStripLayout.ts): one-tap server switching beside the RaftWorkspaceButton. Every
// server's avatar in the switcher's order, the current one marked in place; as many as fit.

/** Metrics and layout rules of [RaftWorkspaceStrip] (web `workspaceStripLayout.ts`). */
object RaftWorkspaceStripDefaults {
    /** One avatar button: a 32dp ghost on-frame icon button. */
    val ItemSize: Dp = 32.dp

    /** Between avatars (`gap-1`). */
    val Gap: Dp = 4.dp

    /** Before the first avatar, after the workspace button (`pl-2`). */
    val Lead: Dp = 8.dp

    /** One slot: a button and the gap after it. */
    val Slot: Dp = ItemSize + Gap

    /** What the hairline before an out-of-range current server adds over a plain gap: one more
     *  gap and the 1dp line. Always reserved, so the count never depends on which is current. */
    val Divider: Dp = 5.dp

    /** The current mark: a 14x3dp bar whose bottom is 6dp under the avatar button. */
    val CurrentMarkWidth: Dp = 14.dp
    val CurrentMarkHeight: Dp = 3.dp
    val CurrentMarkDrop: Dp = 6.dp

    /** A single extra avatar reads as noise, not a switcher. */
    const val MinShown: Int = 2

    /**
     * How many avatars fit in [span] (from the workspace button's end to the region's end), out of
     * [total]: no fixed cap, the space decides; 0 (the strip hides) below [MinShown] or with
     * fewer than two servers.
     */
    fun capacity(span: Dp, total: Int): Int {
        if (total < 2) return 0
        val free = span - Lead + Gap - Divider
        val fit = minOf(total, (free.value / Slot.value).toInt().coerceAtLeast(0))
        return if (fit >= MinShown) fit else 0
    }

    /**
     * Which servers to show, given [shown] slots: the first ones in the switcher's order. If the
     * current one sits beyond them, it takes the last slot (after a hairline) so "where am I"
     * always shows; [RaftWorkspaceStripItems.currentOutside] says so.
     */
    fun <T> items(servers: List<T>, currentIndex: Int, shown: Int): RaftWorkspaceStripItems<T> {
        if (shown <= 0) return RaftWorkspaceStripItems(emptyList(), currentOutside = false)
        if (currentIndex < 0 || currentIndex < shown) return RaftWorkspaceStripItems(servers.take(shown), currentOutside = false)
        return RaftWorkspaceStripItems(servers.take(shown - 1) + servers[currentIndex], currentOutside = true)
    }
}

/** What [RaftWorkspaceStripDefaults.items] shows; with [currentOutside] the last is the current one. */
@Immutable
data class RaftWorkspaceStripItems<T>(val items: List<T>, val currentOutside: Boolean)

/** One server in a [RaftWorkspaceStrip]. */
@Immutable
data class RaftWorkspaceStripEntry(
    val key: String,
    val name: String,
    /** The server has unread activity: the attention dot on its avatar (never on the current one). */
    val unread: Boolean = false,
)

/**
 * The web top bar's workspace strip: [entries] (every server, in the switcher's order) as 32dp
 * avatar buttons 4dp apart, 8dp after the workspace button. As many show as fit the width it is
 * given (at least two, otherwise it shows nothing); if [currentKey]'s server is beyond them it
 * takes the last slot after a hairline. The current server wears the current mark; another
 * server with unread wears the attention dot, and when a server with unread has no avatar in the
 * strip, the current server's avatar carries the dot ("another server has unread").
 *
 * Give it the rest of the row (`Modifier.weight(1f)`). [onShownChange] reports whether it shows,
 * so the workspace button can carry the "another server has unread" dot while it doesn't.
 * [avatar] draws a server's avatar at [RaftAvatarSize.Xs]. Screen readers read each name, plus
 * [unreadDescription] or [otherUnreadDescription] while the dot shows.
 */
@Composable
fun RaftWorkspaceStrip(
    entries: List<RaftWorkspaceStripEntry>,
    currentKey: String?,
    onPick: (RaftWorkspaceStripEntry) -> Unit,
    avatar: @Composable (RaftWorkspaceStripEntry) -> Unit,
    modifier: Modifier = Modifier,
    unreadDescription: String? = null,
    otherUnreadDescription: String? = null,
    onShownChange: ((Boolean) -> Unit)? = null,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.CenterStart) {
        val span = if (constraints.hasBoundedWidth) maxWidth else Dp.Infinity
        val shown =
            if (span == Dp.Infinity) {
                if (entries.size >= RaftWorkspaceStripDefaults.MinShown) entries.size else 0
            } else {
                RaftWorkspaceStripDefaults.capacity(span, entries.size)
            }
        val currentIndex = entries.indexOfFirst { it.key == currentKey }
        val layout = RaftWorkspaceStripDefaults.items(entries, currentIndex, shown)
        val shownKeys = layout.items.mapTo(HashSet()) { it.key }
        val otherUnread = entries.any { it.key != currentKey && it.unread && it.key !in shownKeys }
        val reportShown by rememberUpdatedState(onShownChange)
        val visible = layout.items.isNotEmpty()
        LaunchedEffect(visible) { reportShown?.invoke(visible) }
        if (visible) {
            Row(
                Modifier.padding(start = RaftWorkspaceStripDefaults.Lead),
                horizontalArrangement = Arrangement.spacedBy(RaftWorkspaceStripDefaults.Gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val head = if (layout.currentOutside) layout.items.dropLast(1) else layout.items
                head.forEach { entry ->
                    val current = entry.key == currentKey
                    RaftWorkspaceStripItem(
                        name = entry.name,
                        current = current,
                        onClick = { onPick(entry) },
                        attention = if (current) otherUnread else entry.unread,
                        attentionDescription = if (current) otherUnreadDescription else unreadDescription,
                        avatar = { avatar(entry) },
                    )
                }
                if (layout.currentOutside) {
                    val tail = layout.items.last()
                    Box(Modifier.width(RaftStroke.Hairline).height(16.dp).background(RaftTheme.colors.lineSeam))
                    RaftWorkspaceStripItem(
                        name = tail.name,
                        current = true,
                        onClick = { onPick(tail) },
                        attention = otherUnread,
                        attentionDescription = otherUnreadDescription,
                        avatar = { avatar(tail) },
                    )
                }
            }
        }
    }
}

/**
 * One server in the [RaftWorkspaceStrip]: its [avatar] (a [RaftAvatarSize.Xs] server avatar) in
 * a 32dp ghost button on the frame (`foreground-on-frame`, the frame's pressed wash on press).
 * [current] puts the current mark under it: a 14x3dp `foreground` bar, its bottom 6dp under the
 * button. [attention] puts the attention dot on the avatar's top-right corner. Screen readers
 * read [name] (plus [attentionDescription] while the dot shows) and "selected" when [current].
 */
@Composable
fun RaftWorkspaceStripItem(
    name: String,
    current: Boolean,
    onClick: () -> Unit,
    avatar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    attention: Boolean = false,
    attentionDescription: String? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    val colors = RaftTheme.colors
    val look = raftButtonLook(RaftButtonVariant.OnFrame, enabled = true, frameless = true, colors = colors)
    val description = if (attention && attentionDescription != null) "$name, $attentionDescription" else name
    Box(modifier) {
        RaftButtonBox(
            look = look,
            onClick = onClick,
            modifier =
                Modifier
                    .size(RaftWorkspaceStripDefaults.ItemSize)
                    .clearAndSetSemantics {
                        contentDescription = description
                        role = Role.Button
                        selected = current
                        this.onClick(label = null) { onClick(); true }
                    },
            enabled = true,
            loading = false,
            loadingLabel = "",
            interactionSource = interactionSource,
        ) {
            Box(Modifier.size(RaftAvatarSize.Xs.edge)) {
                avatar()
                if (attention) {
                    val dot = RaftAvatarSize.Xs.badgeEdge
                    Box(Modifier.align(Alignment.TopEnd).offset(x = dot / 2, y = -dot / 2)) {
                        RaftAvatarAttentionLight(RaftAvatarSize.Xs, cutout = colors.layerFrame)
                    }
                }
            }
        }
        if (current) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = RaftWorkspaceStripDefaults.CurrentMarkDrop)
                    .size(RaftWorkspaceStripDefaults.CurrentMarkWidth, RaftWorkspaceStripDefaults.CurrentMarkHeight)
                    .background(colors.foreground, RoundedCornerShape(2.dp)),
            )
        }
    }
}
