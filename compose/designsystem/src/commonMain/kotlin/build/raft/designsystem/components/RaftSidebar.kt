package build.raft.designsystem.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.LocalRaftSurfaceColor
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.tokens.RaftUiPalette

// raft-ui 0.10 Sidebar (sidebar-item.recipe.ts, sidebar-section.recipe.ts) at its phone
// size: below `md` a SidebarItem is at least 44px in `text-body` at 600 (raft-ui 0.8.1+),
// which is how the web draws Home's Search / Activity / Saved rows, the channel and DM
// rows and the group headers on a phone. The rows sit 8dp in from the page edge
// (`SidebarContentInner` `px-2`); the page owns that padding.

/** The hint ink of the enclosing [RaftSidebarItem] (`sticker-ink` when it is selected). */
private val LocalRaftSidebarItemHint = staticCompositionLocalOf<Color?> { null }

/** Phone metrics of a sidebar row (raft-ui `SidebarItem` below `md`). */
internal object RaftSidebarItemMetrics {
    /** `max-md:min-h-11`. */
    val MinHeight = 44.dp

    /** `max-md:py-[8.5px]`, inside the 1.5dp border. */
    val PaddingVertical = 8.5.dp

    /** `pl-2`, inside the border. */
    val PaddingStart = 8.dp

    /** `pr-2.5`, inside the border. */
    val PaddingEnd = 10.dp

    /** `gap-2` between icon, text and aside. */
    val Gap = 8.dp

    /** `my-px`: rows stand 2dp apart. */
    val MarginVertical = 1.dp

    /** `SidebarItemIcon` / `SidebarItemChannelIcon`: a 19dp box with a 16dp icon. */
    val IconBox = 19.dp
    val Icon = 16.dp
}

/**
 * raft-ui SidebarItem at its phone size: one place in the Home list (Search, Activity,
 * Saved, a channel, a DM). The whole row is the target.
 *
 * - Box: at least 44dp tall (it grows with the font scale), 8dp start / 10dp end padding
 *   inside a 1.5dp edge that is always there (transparent at rest), control radius, gap 8,
 *   1dp above and below. The row fills the width it is given.
 * - [leading]: a 19dp slot for a 16dp icon ([RaftSidebarItemIcon]) or a 19dp avatar, drawn
 *   in `foreground-hint` ([LocalRaftContentColor]).
 * - [title]: `text-body` (16/24) at 600, one line with an ellipsis; 700 when
 *   [RaftRowTone.Unread] or [selected]. [RaftRowTone.Muted] writes it in
 *   `foreground-muted`, [RaftRowTone.Away] in `foreground-hint`.
 * - [subtitle]: `text-footnote` `foreground-muted` on the title's baseline, 4dp after it.
 *   It gives up its room before the title does.
 * - [trailing]: the aside (`gap-1`): [RaftSidebarItemMarks], [RaftSidebarItemMeta], a
 *   status pixel.
 *
 * States (States: navigation selection): [selected] is "you are here", the yellow sticker:
 * `brand` fill, 1.5dp `sticker-ink` edge, die-cut edge in dark, `sticker-ink` for every
 * part, and `selected` in semantics. Press (and a mouse hover) lays the press overlay over
 * the fill; keyboard focus draws the focus ring outside the box. [onClick] = null is a
 * display-only row (`interactive={false}`): no press, no focus, not a button.
 *
 * Touch (raft-design ContextMenu): [onLongClick] is the press and hold that opens the row's
 * menu, the same menu as its ⋯ button and a right-click, named [onLongClickLabel] for
 * screen readers ("Channel Options"); a long press does not also click. While that menu is
 * open pass [menuOpen] = true: the row keeps the press overlay (raft-ui
 * `not-data-active:data-popup-open:bg-fill-pressed`, so not on the selected row). Both need
 * [onClick].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RaftSidebarItem(
    title: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
    tone: RaftRowTone = RaftRowTone.Default,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    menuOpen: Boolean = false,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val interactive = onClick != null
    val pressed = interactive && (raftIsPressed(source) || raftIsHovered(source) || (menuOpen && !selected))
    val focused = interactive && raftIsFocused(source)
    val titleInk =
        when (tone) {
            RaftRowTone.Muted -> colors.foregroundMuted
            RaftRowTone.Away -> colors.foregroundHint
            RaftRowTone.Default, RaftRowTone.Unread -> colors.foreground
        }
    val look = raftActiveStickerLook(active = selected, colors = colors, restingInk = titleInk)
    val hintInk = if (selected) RaftUiPalette.stickerInk else colors.foregroundHint
    val subtitleInk = if (selected) RaftUiPalette.stickerInk else colors.foregroundMuted
    val titleWeight = if (selected || tone == RaftRowTone.Unread) FontWeight.Bold else FontWeight.SemiBold
    val surface = if (selected) RaftUiPalette.brand else RaftTheme.surfaceColor
    val edge = RaftStroke.Control
    CompositionLocalProvider(
        LocalRaftContentColor provides hintInk,
        LocalRaftSidebarItemHint provides hintInk,
        LocalRaftSurfaceColor provides surface,
    ) {
        Row(
            modifier
                .padding(vertical = RaftSidebarItemMetrics.MarginVertical)
                .fillMaxWidth()
                .heightIn(min = RaftSidebarItemMetrics.MinHeight)
                .raftSticker(look, RaftRadius.Control, pressed = pressed, focused = focused, colors = colors)
                .then(
                    when {
                        onClick == null -> Modifier
                        onLongClick != null ->
                            Modifier.combinedClickable(
                                interactionSource = source,
                                indication = null,
                                role = Role.Button,
                                onLongClickLabel = onLongClickLabel,
                                onLongClick = onLongClick,
                                onClick = onClick,
                            )
                        else ->
                            Modifier.clickable(
                                interactionSource = source,
                                indication = null,
                                role = Role.Button,
                                onClick = onClick,
                            )
                    },
                )
                .then(if (selected) Modifier.semantics { this.selected = true } else Modifier)
                .padding(
                    start = RaftSidebarItemMetrics.PaddingStart + edge,
                    end = RaftSidebarItemMetrics.PaddingEnd + edge,
                    top = RaftSidebarItemMetrics.PaddingVertical + edge,
                    bottom = RaftSidebarItemMetrics.PaddingVertical + edge,
                ),
            horizontalArrangement = Arrangement.spacedBy(RaftSidebarItemMetrics.Gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                Box(Modifier.size(RaftSidebarItemMetrics.IconBox), contentAlignment = Alignment.Center) { leading() }
            }
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The title is measured first, so it keeps its width while the subtitle
                // (weighted) takes what is left: the subtitle gives way first.
                RaftText(
                    title,
                    modifier = if (subtitle == null) Modifier.weight(1f) else Modifier.alignByBaseline(),
                    style = typography.body.copy(fontWeight = titleWeight),
                    color = look.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    RaftText(
                        subtitle,
                        modifier = Modifier.weight(1f).alignByBaseline(),
                        style = typography.footnote,
                        color = subtitleInk,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = trailing,
                )
            }
        }
    }
}

/**
 * A 16dp [glyph] for a sidebar row's leading slot (`SidebarItemIcon`), in the row's icon
 * ink (`foreground-hint`, `sticker-ink` when selected). Decorative.
 */
@Composable
fun RaftSidebarItemIcon(glyph: RaftGlyph, modifier: Modifier = Modifier) {
    RaftGlyphIcon(glyph, modifier = modifier, size = RaftSidebarItemMetrics.Icon)
}

/**
 * A channel row: [RaftSidebarItem] led by the bare `hash` (`lock` when [isPrivate]) in
 * `foreground-hint` (`SidebarItemChannelIcon`), then the name and its marks
 * ([RaftSidebarItemMarks]). Something unread or a mention turns a [RaftRowTone.Default]
 * row [RaftRowTone.Unread] (a 700 name); a muted or away tone stays. [trailing] goes
 * before the marks (a draft pencil, a status pixel). [onLongClick], [onLongClickLabel] and
 * [menuOpen] are [RaftSidebarItem]'s: press and hold opens the channel's menu.
 */
@Composable
fun RaftSidebarChannelItem(
    name: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
    tone: RaftRowTone = RaftRowTone.Default,
    isPrivate: Boolean = false,
    unreadCount: Int = 0,
    threadCount: Int = 0,
    mentioned: Boolean = false,
    subtitle: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    menuOpen: Boolean = false,
) {
    val waiting = unreadCount > 0 || threadCount > 0 || mentioned
    val resolvedTone = if (tone == RaftRowTone.Default && waiting) RaftRowTone.Unread else tone
    val aside: @Composable RowScope.() -> Unit = {
        trailing?.invoke(this)
        RaftSidebarItemMarks(
            unreadCount = unreadCount,
            threadCount = threadCount,
            mentioned = mentioned,
            muted = tone == RaftRowTone.Muted,
        )
    }
    RaftSidebarItem(
        title = name,
        modifier = modifier,
        onClick = onClick,
        selected = selected,
        tone = resolvedTone,
        subtitle = subtitle,
        leading = { RaftSidebarItemIcon(if (isPrivate) RaftGlyph.Lock else RaftGlyph.Hash) },
        trailing = if (trailing != null || waiting) aside else null,
        interactionSource = interactionSource,
        onLongClick = onLongClick,
        onLongClickLabel = onLongClickLabel,
        menuOpen = menuOpen,
    )
}

/**
 * What waits in a place, at the end of a sidebar row (`SidebarItemAside`, `gap-1`):
 *
 * - [mentioned]: the yellow @ sticker ([RaftMentionMarker]) in place of the unread count;
 *   followed threads with new replies still show as a pink `[thread] N` count after it.
 * - Otherwise the pink unread count ([RaftUnreadCount]): `N`, `N · [thread] M`, or
 *   `[thread] M`.
 * - [muted]: no sticker. Something unread in a muted place is the gray status pixel the
 *   web Home draws, named [mutedUnreadDescription] for screen readers (raft-ui's quiet
 *   number is [RaftSidebarItemMeta]).
 *
 * Draws nothing when nothing waits. Pass localized [contentDescription]s from the app.
 */
@Composable
fun RaftSidebarItemMarks(
    unreadCount: Int,
    modifier: Modifier = Modifier,
    threadCount: Int = 0,
    mentioned: Boolean = false,
    muted: Boolean = false,
    mentionDescription: String? = "Mentions you",
    countDescription: String? =
        raftUnreadCountDescription(if (mentioned) 0 else unreadCount, threadCount),
    mutedUnreadDescription: String? = "Unread",
) {
    val hasUnread = unreadCount > 0 || threadCount > 0
    if (!hasUnread && !mentioned) return
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            muted && !mentioned ->
                RaftStatusDot(
                    variant = RaftStatusVariant.Default,
                    size = RaftStatusSize.Sm,
                    contentDescription = mutedUnreadDescription,
                )
            mentioned -> {
                RaftMentionMarker(contentDescription = mentionDescription)
                if (threadCount > 0) RaftUnreadCount(count = 0, threadCount = threadCount, contentDescription = countDescription)
            }
            else -> RaftUnreadCount(count = unreadCount, threadCount = threadCount, contentDescription = countDescription)
        }
    }
}

/**
 * Quiet text at the end of a sidebar row (`SidebarItemMeta` / `SidebarMenuCount`): Space
 * Mono 12 tabular in `foreground-hint` (`sticker-ink` on a selected row). Saved's count,
 * the unread number of a muted place.
 */
@Composable
fun RaftSidebarItemMeta(text: String, modifier: Modifier = Modifier) {
    RaftText(
        text,
        modifier = modifier,
        style = RaftTheme.typography.monoMeta.copy(fontWeight = FontWeight.Normal, fontFeatureSettings = "tnum"),
        color = LocalRaftSidebarItemHint.current ?: RaftTheme.colors.foregroundHint,
        maxLines = 1,
    )
}

/**
 * raft-ui SidebarSectionHeader with its SidebarSectionDisclosure: the heading of a
 * collapsible group of rows (Pinned, Channels, a custom group).
 *
 * - `px-2 pt-4 pb-1.5`, gap 8: 40dp tall at the default font scale.
 * - The disclosure: an 18dp box with a 14dp `chevron-right` (turned down when [expanded]),
 *   an optional [icon] (a custom group's emoji), the [title] in `text-footnote` 700 and
 *   [count] in Space Mono 12 tabular, all `foreground-hint`, 6dp apart. It is one button
 *   spanning the header's height (so a finger can hit it), with the expand / collapse
 *   action and state in semantics, and the focus ring round its words.
 * - [attention]: the pink status pixel (`SidebarSectionAttention`) of a collapsed group
 *   holding something unread; hidden while [expanded].
 * - [actions]: the group's controls (`SidebarSectionControls`), always shown on a phone:
 *   [RaftSidebarSectionAction]s, 4dp apart. They overhang the 18dp line instead of
 *   growing the header (`-my-1.5`).
 * - Press: the disclosure lights up its hit area with `fill-pressed` (States: a text
 *   button lights up its hit area when pressed).
 * - Touch: [onLongClick] is the press and hold that opens the group's menu (the web's
 *   right-click menu on the header), named [onLongClickLabel]; it does not also toggle.
 *   While that menu is open pass [menuOpen] = true: the disclosure stays pressed and the
 *   attention pixel hides (raft-ui `group-data-popup-open/section-header:hidden`). Both
 *   need [onExpandedChange].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RaftSidebarSectionHeader(
    title: String,
    expanded: Boolean,
    onExpandedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    count: Int? = null,
    attention: Boolean = false,
    icon: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    menuOpen: Boolean = false,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val interactive = onExpandedChange != null
    val focused = interactive && raftIsFocused(source)
    val pressed = interactive && (raftIsPressed(source) || menuOpen)
    val showAttention = attention && !expanded && !menuOpen
    val hint = colors.foregroundHint
    val lineHeight = 18.dp
    Row(
        modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .weight(1f)
                .raftPressOverlay(pressed, colors.fillPressed, RaftRadius.Control)
                .then(
                    if (onExpandedChange != null) {
                        val toggle = { onExpandedChange(!expanded) }
                        Modifier
                            .then(
                                if (onLongClick != null) {
                                    Modifier.combinedClickable(
                                        interactionSource = source,
                                        indication = null,
                                        role = Role.Button,
                                        onLongClickLabel = onLongClickLabel,
                                        onLongClick = onLongClick,
                                        onClick = toggle,
                                    )
                                } else {
                                    Modifier.clickable(
                                        interactionSource = source,
                                        indication = null,
                                        role = Role.Button,
                                        onClick = toggle,
                                    )
                                },
                            )
                            .semantics {
                                if (expanded) {
                                    collapse { onExpandedChange(false); true }
                                } else {
                                    expand { onExpandedChange(true); true }
                                }
                            }
                    } else {
                        Modifier
                    },
                )
                .padding(top = 16.dp, bottom = 6.dp),
        ) {
            Row(
                Modifier.raftFocusRing(focused, RaftRadius.Sticker, colors),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompositionLocalProvider(LocalRaftContentColor provides hint) {
                    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                        RaftGlyphIcon(
                            RaftGlyph.ChevronRight,
                            modifier = Modifier.rotate(if (expanded) 90f else 0f),
                            size = 14.dp,
                        )
                    }
                    if (icon != null) Box(contentAlignment = Alignment.Center) { icon() }
                }
                RaftText(
                    title,
                    modifier = Modifier.weight(1f, fill = false),
                    style = typography.footnote.copy(fontWeight = FontWeight.Bold),
                    color = hint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (count != null) {
                    RaftText(
                        count.toString(),
                        style = typography.monoMeta.copy(fontWeight = FontWeight.Normal, fontFeatureSettings = "tnum"),
                        color = hint,
                        maxLines = 1,
                    )
                }
            }
        }
        // The trailing box stays while a menu hides the pixel (`min-w-4.5`), so nothing moves.
        if ((attention && !expanded) || actions != null) {
            Row(
                Modifier.padding(top = 16.dp, bottom = 6.dp).widthIn(min = 18.dp).overhang(lineHeight),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showAttention) RaftStatusDot(variant = RaftStatusVariant.Accent, size = RaftStatusSize.Md)
                actions?.invoke(this)
            }
        }
    }
}

/**
 * A group header's control (`SidebarSectionControl`: ghost, icon size): a
 * [RaftIconButton] [RaftButtonVariant.Ghost] with a 16dp [glyph] in `foreground`, named
 * by [tooltip] (Title Case: "Sort Channels", "Create Channel"). Sort is
 * [RaftGlyph.ArrowUpDown] (or [RaftGlyph.ArrowDownUp]), create [RaftGlyph.Plus].
 */
@Composable
fun RaftSidebarSectionAction(
    glyph: RaftGlyph,
    tooltip: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    RaftSidebarSectionAction(tooltip = tooltip, onClick = onClick, modifier = modifier, enabled = enabled) {
        RaftGlyphIcon(glyph, size = RaftSidebarSectionActionIconSize)
    }
}

/**
 * [RaftSidebarSectionAction] with any [icon] (an app icon the module has no [RaftGlyph]
 * for), drawn in a 16dp box in the button's ink ([LocalRaftContentColor]: `foreground`,
 * `foreground-disabled` when not [enabled]). Size the icon 16dp or let it fill the box.
 */
@Composable
fun RaftSidebarSectionAction(
    tooltip: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: @Composable () -> Unit,
) {
    RaftIconButton(
        onClick = onClick,
        tooltip = tooltip,
        modifier = modifier,
        variant = RaftButtonVariant.Ghost,
        enabled = enabled,
    ) {
        Box(Modifier.size(RaftSidebarSectionActionIconSize), contentAlignment = Alignment.Center) { icon() }
    }
}

/** `SidebarSectionControl` `[&>svg]:size-4`. */
private val RaftSidebarSectionActionIconSize = 16.dp

/**
 * Lays the content out at its own height but reports [height] (padding included in the
 * modifiers before it), centring the content on it: CSS negative block margins, so a
 * 32dp control sits on an 18dp line without growing the row.
 */
private fun Modifier.overhang(height: Dp): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        val reported = height.roundToPx()
        layout(placeable.width, reported) {
            placeable.place(0, (reported - placeable.height) / 2)
        }
    }
