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
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
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

    /** `SidebarItemAside` `gap-1` between its parts. */
    val AsideGap = 4.dp

    /**
     * `my-px`: in the web's block flow the 1px margins above and below collapse, so rows
     * stand 1dp apart. Compose margins add up, so each row keeps half of it above and below:
     * 1dp between rows, and about 1dp at either end of a run (before a group header).
     */
    val MarginVertical = 0.5.dp

    /** `SidebarItemIcon` / `SidebarItemChannelIcon`: a 19dp box with a 16dp icon. */
    val IconBox = 19.dp
    val Icon = 16.dp
}

/** The icon size [RaftSidebarItemIcon] draws at: the enclosing row's [RaftSidebarItemVariant]. */
private val LocalRaftSidebarItemIconSize = staticCompositionLocalOf { RaftSidebarItemMetrics.Icon }

/** Which kind of sidebar row: it sets the leading slot, the gap after it and the aside's gap. */
enum class RaftSidebarItemVariant(
    internal val leadingBox: Dp,
    internal val icon: Dp,
    internal val leadingGap: Dp,
    internal val asideGap: Dp,
) {
    /**
     * A place in a group (a channel, a pinned row, a custom group's row):
     * `SidebarItemIcon` / `SidebarItemChannelIcon`, a 19dp slot with a 16dp icon or a 19dp
     * avatar, `gap-2` (8dp) to the name; the aside's parts `gap-1` (4dp) apart.
     */
    Default(RaftSidebarItemMetrics.IconBox, RaftSidebarItemMetrics.Icon, RaftSidebarItemMetrics.Gap, RaftSidebarItemMetrics.AsideGap),

    /**
     * An entry row at the top of the phone Home (Search, Activity, Saved; web
     * `SIDEBAR_ENTRY_ROW_CLASS` / `SIDEBAR_ENTRY_ICON_CLASS`, raft-ui ListRow `entry` +
     * `ListRowIcon`): a 20dp icon, `gap-3` (12dp) to the name. Never selected (Home rules).
     */
    Entry(20.dp, 20.dp, 12.dp, RaftSidebarItemMetrics.AsideGap),

    /**
     * A direct message (web `SidebarPeerRowBody`): the 19dp avatar 8dp from the name, and
     * the aside's parts (the draft mark, the agent's activity or runtime, the count)
     * `gap-1.5` (6dp) apart (web `SIDEBAR_ROW_TRAILING_CLASS`).
     */
    Peer(RaftSidebarItemMetrics.IconBox, RaftSidebarItemMetrics.Icon, RaftSidebarItemMetrics.Gap, 6.dp),
}

/**
 * raft-ui SidebarItem at its phone size: one place in the Home list (Search, Activity,
 * Saved, a channel, a DM). The whole row is the target.
 *
 * - Box: at least 44dp tall (it grows with the font scale), 8dp start / 10dp end padding
 *   inside a 1.5dp edge that is always there (transparent at rest), control radius, gap 8,
 *   0.5dp above and below, so rows stand 1dp apart (the web's collapsed `my-px`). The row fills the width it is given.
 * - [leading]: a 19dp slot for a 16dp icon ([RaftSidebarItemIcon]) or a 19dp avatar, drawn
 *   in `foreground-hint` ([LocalRaftContentColor]), 8dp from the name. [variant]
 *   [RaftSidebarItemVariant.Entry] (Home's Search / Activity / Saved) makes it a 20dp icon
 *   12dp from the name.
 * - [title]: `text-body` (16/24) at 600, one line with an ellipsis; 700 when
 *   [RaftRowTone.Unread] or [selected]. [RaftRowTone.Muted] writes it in
 *   `foreground-muted`, [RaftRowTone.Away] in `foreground-hint`.
 * - [titleTrailing]: what goes right after the name, 4dp from it (`SidebarItemMetaIcon` in
 *   `SidebarItemContent`: an activity-muted bell, a bridge badge), centred on it in the
 *   icon ink. The name truncates before it; the slot never does.
 * - [subtitle]: `text-footnote` `foreground-muted` on the title's baseline, 4dp after it.
 *   It gives up its room before the title does.
 * - [trailing]: the aside (`gap-1`; `gap-1.5` in a [RaftSidebarItemVariant.Peer] row):
 *   [RaftSidebarItemMarks], [RaftSidebarItemMeta], a status pixel.
 *
 * States (States: navigation selection): [selected] is "you are here", the yellow sticker:
 * `brand` fill, 1.5dp `sticker-ink` edge, die-cut edge in dark, `sticker-ink` for every
 * part, and `selected` in semantics; it takes no hover or press overlay. An unselected row
 * is a list cell (raft-ui lib/list-cell.ts): a mouse or trackpad hover lays
 * `fill-cell-hover` on its rounded shape (faded over 150ms) and press `fill-cell-press`;
 * keyboard focus draws the focus ring outside the box. [onClick] = null is a
 * display-only row (`interactive={false}`): no press, no focus, not a button.
 *
 * Touch (raft-design ContextMenu): [onLongClick] is the press and hold that opens the row's
 * menu, the same menu as its ⋯ button and a right-click, named [onLongClickLabel] for
 * screen readers ("Channel Options"); a long press does not also click. While that menu is
 * open pass [menuOpen] = true: the row keeps the hover fill, `fill-cell-hover` (raft-ui
 * `not-data-active:data-popup-open:before:bg-fill-cell-hover`, so not on the selected row).
 * Hover and press need [onClick].
 */
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
    titleTrailing: (@Composable () -> Unit)? = null,
    variant: RaftSidebarItemVariant = RaftSidebarItemVariant.Default,
) {
    RaftSidebarItemLayout(
        titleText = { textModifier, style, ink ->
            RaftText(title, modifier = textModifier, style = style, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        modifier = modifier,
        onClick = onClick,
        selected = selected,
        tone = tone,
        subtitle = subtitle,
        leading = leading,
        trailing = trailing,
        interactionSource = interactionSource,
        onLongClick = onLongClick,
        onLongClickLabel = onLongClickLabel,
        menuOpen = menuOpen,
        titleTrailing = titleTrailing,
        variant = variant,
    )
}

/**
 * [RaftSidebarItem] with a styled [title]: its spans and their `localeList`
 * (`SpanStyle(localeList = LocaleList("ja"))`) are kept, so a CJK name keeps the glyph forms
 * of its language. The row's type, weight and ink sit under the spans.
 */
@Composable
fun RaftSidebarItem(
    title: AnnotatedString,
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
    titleTrailing: (@Composable () -> Unit)? = null,
    variant: RaftSidebarItemVariant = RaftSidebarItemVariant.Default,
) {
    RaftSidebarItemLayout(
        titleText = { textModifier, style, ink ->
            RaftText(title, modifier = textModifier, style = style, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        modifier = modifier,
        onClick = onClick,
        selected = selected,
        tone = tone,
        subtitle = subtitle,
        leading = leading,
        trailing = trailing,
        interactionSource = interactionSource,
        onLongClick = onLongClick,
        onLongClickLabel = onLongClickLabel,
        menuOpen = menuOpen,
        titleTrailing = titleTrailing,
        variant = variant,
    )
}

/** [RaftSidebarItem]'s layout; [titleText] draws the one-line name in the given style and ink. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RaftSidebarItemLayout(
    titleText: @Composable (modifier: Modifier, style: TextStyle, ink: Color) -> Unit,
    modifier: Modifier,
    onClick: (() -> Unit)?,
    selected: Boolean,
    tone: RaftRowTone,
    subtitle: String?,
    leading: (@Composable () -> Unit)?,
    trailing: (@Composable RowScope.() -> Unit)?,
    interactionSource: MutableInteractionSource?,
    onLongClick: (() -> Unit)?,
    onLongClickLabel: String?,
    menuOpen: Boolean,
    titleTrailing: (@Composable () -> Unit)?,
    variant: RaftSidebarItemVariant,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val interactive = onClick != null
    // A list cell (raft-ui lib/list-cell.ts): hover lays `fill-cell-hover` on the row's
    // shape, faded over 150ms; press `fill-cell-press` at once; an open menu keeps the hover
    // fill. A selected (sticker) row answers neither.
    val overlay =
        rememberRaftStateOverlay(
            source,
            RaftHoverTier.Cell,
            enabled = interactive,
            selected = selected,
            highlighted = menuOpen,
        )
    val isFocused = raftIsFocused(source)
    val focused = interactive && isFocused
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
    val surface = if (selected) RaftUiPalette.selected else RaftTheme.surfaceColor
    val edge = RaftStroke.Control
    CompositionLocalProvider(
        LocalRaftContentColor provides hintInk,
        LocalRaftSidebarItemHint provides hintInk,
        LocalRaftSurfaceColor provides surface,
        LocalRaftSidebarItemIconSize provides variant.icon,
    ) {
        Row(
            modifier
                // The row is a rounded sticker (an inset pill), not a full-width wash: it stays
                // out of the horizontal safe area as a whole.
                .raftSafeAreaHorizontalPadding()
                .padding(vertical = RaftSidebarItemMetrics.MarginVertical)
                .fillMaxWidth()
                .heightIn(min = RaftSidebarItemMetrics.MinHeight)
                .raftSticker(look, RaftRadius.Control, focused = focused, colors = colors)
                // Over the sticker's fill (transparent unless selected, and a selected row
                // lays nothing), under its edge.
                .raftStateOverlay(overlay, RaftRadius.Control)
                .then(if (interactive) Modifier.raftPointerHover(source) else Modifier)
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
                Box(
                    Modifier
                        .padding(end = variant.leadingGap - RaftSidebarItemMetrics.Gap)
                        .size(variant.leadingBox),
                    contentAlignment = Alignment.Center,
                ) { leading() }
            }
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The title is measured first, so it keeps its width while the subtitle
                // (weighted) takes what is left: the subtitle gives way first.
                val titleModifier = if (subtitle == null) Modifier.weight(1f) else Modifier.alignByBaseline()
                val titleStyle = typography.body.copy(fontWeight = titleWeight)
                if (titleTrailing == null) {
                    titleText(titleModifier, titleStyle, look.ink)
                } else {
                    // The name and its slot as one piece (it keeps the name's baseline for the
                    // subtitle): the slot is measured first, so only the name truncates.
                    Row(
                        titleModifier,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        titleText(Modifier.weight(1f, fill = false), titleStyle, look.ink)
                        Box(contentAlignment = Alignment.Center) { titleTrailing() }
                    }
                }
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
                    horizontalArrangement = Arrangement.spacedBy(variant.asideGap),
                    verticalAlignment = Alignment.CenterVertically,
                    content = trailing,
                )
            }
        }
    }
}

/**
 * A [glyph] for a sidebar row's leading slot (`SidebarItemIcon`), in the row's icon ink
 * (`foreground-hint`, `sticker-ink` when selected), at the row's icon size: 16dp, 20dp in
 * a [RaftSidebarItemVariant.Entry] row. Decorative. An app icon in that slot reads the
 * size the same way: [raftSidebarItemIconSize].
 */
@Composable
fun RaftSidebarItemIcon(glyph: RaftGlyph, modifier: Modifier = Modifier) {
    RaftGlyphIcon(glyph, modifier = modifier, size = raftSidebarItemIconSize())
}

/** The icon size of the enclosing sidebar row's leading slot (16dp; 20dp in an entry row). */
@Composable
@ReadOnlyComposable
fun raftSidebarItemIconSize(): Dp = LocalRaftSidebarItemIconSize.current

/**
 * A channel row: [RaftSidebarItem] led by the bare `hash` (`lock` when [isPrivate]) in
 * `foreground-hint` (`SidebarItemChannelIcon`), then the name and its marks
 * ([RaftSidebarItemMarks]). Something unread or a mention turns a [RaftRowTone.Default]
 * row [RaftRowTone.Unread] (a 700 name); a muted or away tone stays. [trailing] goes
 * before the marks (a draft pencil, a status pixel); [titleTrailing] right after the name
 * (the activity-muted bell, a bridge badge). [onLongClick], [onLongClickLabel] and
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
    titleTrailing: (@Composable () -> Unit)? = null,
) {
    RaftSidebarChannelItemLayout(
        titleText = { textModifier, style, ink ->
            RaftText(name, modifier = textModifier, style = style, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        modifier = modifier,
        onClick = onClick,
        selected = selected,
        tone = tone,
        isPrivate = isPrivate,
        unreadCount = unreadCount,
        threadCount = threadCount,
        mentioned = mentioned,
        subtitle = subtitle,
        trailing = trailing,
        interactionSource = interactionSource,
        onLongClick = onLongClick,
        onLongClickLabel = onLongClickLabel,
        menuOpen = menuOpen,
        titleTrailing = titleTrailing,
    )
}

/** [RaftSidebarChannelItem] with a styled [name] (spans and their `localeList` kept). */
@Composable
fun RaftSidebarChannelItem(
    name: AnnotatedString,
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
    titleTrailing: (@Composable () -> Unit)? = null,
) {
    RaftSidebarChannelItemLayout(
        titleText = { textModifier, style, ink ->
            RaftText(name, modifier = textModifier, style = style, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        modifier = modifier,
        onClick = onClick,
        selected = selected,
        tone = tone,
        isPrivate = isPrivate,
        unreadCount = unreadCount,
        threadCount = threadCount,
        mentioned = mentioned,
        subtitle = subtitle,
        trailing = trailing,
        interactionSource = interactionSource,
        onLongClick = onLongClick,
        onLongClickLabel = onLongClickLabel,
        menuOpen = menuOpen,
        titleTrailing = titleTrailing,
    )
}

@Composable
private fun RaftSidebarChannelItemLayout(
    titleText: @Composable (modifier: Modifier, style: TextStyle, ink: Color) -> Unit,
    modifier: Modifier,
    onClick: (() -> Unit)?,
    selected: Boolean,
    tone: RaftRowTone,
    isPrivate: Boolean,
    unreadCount: Int,
    threadCount: Int,
    mentioned: Boolean,
    subtitle: String?,
    trailing: (@Composable RowScope.() -> Unit)?,
    interactionSource: MutableInteractionSource?,
    onLongClick: (() -> Unit)?,
    onLongClickLabel: String?,
    menuOpen: Boolean,
    titleTrailing: (@Composable () -> Unit)?,
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
    RaftSidebarItemLayout(
        titleText = titleText,
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
        titleTrailing = titleTrailing,
        variant = RaftSidebarItemVariant.Default,
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
 * Unread with no number (web `QuietUnreadDot`, botiverse/slock #11795): a conversation marked
 * unread with nothing countable, at the end of a sidebar row or among an Activity row's marks.
 * A square pixel, never a round dot: 6dp (`size-1.5`) with 2dp corners (`rounded-[2px]`) in
 * `foreground-hint` (`sticker-ink` on a selected sidebar row), and no ink edge (unlike
 * [RaftStatusDot]). An image to assistive technology, named by [contentDescription].
 */
@Composable
fun RaftQuietUnreadDot(contentDescription: String?, modifier: Modifier = Modifier) {
    val color = LocalRaftSidebarItemHint.current ?: RaftTheme.colors.foregroundHint
    Box(
        modifier
            .size(RaftQuietUnreadDotMetrics.Edge)
            .background(color, RoundedCornerShape(RaftQuietUnreadDotMetrics.Radius))
            .clearAndSetSemantics {
                if (contentDescription != null) {
                    this.contentDescription = contentDescription
                    role = Role.Image
                }
            },
    )
}

/** [RaftQuietUnreadDot]'s pixel: `size-1.5 rounded-[2px]`. */
internal object RaftQuietUnreadDotMetrics {
    val Edge = 6.dp
    val Radius = 2.dp
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
 * raft-ui SidebarGroupLabel: a plain group heading that doesn't collapse (the web settings
 * hub's Personal / Workspace / Resources, `SettingsSidebarList`). `px-2 pt-4 pb-1.5`, the
 * [title] in `text-footnote` 700 `foreground-hint`, sentence case as written; a heading in
 * semantics. It is [RaftSectionEyebrow] with the sidebar's padding.
 */
@Composable
fun RaftSidebarGroupLabel(title: String, modifier: Modifier = Modifier) {
    RaftSectionEyebrow(title, modifier.fillMaxWidth().raftSafeAreaHorizontalPadding().padding(start = 8.dp, end = 8.dp, top = 16.dp, bottom = 6.dp))
}

/**
 * raft-ui SidebarSectionHeader with its SidebarSectionDisclosure: the heading of a
 * collapsible group of rows (Pinned, Channels, a custom group).
 *
 * - `px-2 pt-4 pb-1.5`, gap 8: 40dp tall at the default font scale.
 * - The disclosure: a 14dp `chevron-right` (turned down when [expanded]; `size-3.5`),
 *   an optional [icon] (a custom group's emoji), the [title] in `text-footnote` 700 and
 *   [count] in Space Mono 12 tabular, all `foreground-hint`, 6dp apart. It is one button
 *   spanning the header's height (so a finger can hit it), with the expand / collapse
 *   action and state in semantics, and the focus ring round its words.
 * - [countSeparator]: printed inside the count, straight after the title with no gap
 *   (web members page: `<SidebarSectionCount>{" · "}{count}</SidebarSectionCount>`, so
 *   "Humans · 11"). Null (the sidebar): the count stands 6dp after the title.
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
    countSeparator: String? = null,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val interactive = onExpandedChange != null
    val isFocused = raftIsFocused(source)
    val focused = interactive && isFocused
    val isPressed = raftIsPressed(source)
    val pressed = interactive && (isPressed || menuOpen)
    val showAttention = attention && !expanded && !menuOpen
    val hint = colors.foregroundHint
    val lineHeight = 18.dp
    Row(
        modifier.fillMaxWidth().raftSafeAreaHorizontalPadding().padding(horizontal = 8.dp),
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
                    Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
                        RaftGlyphIcon(
                            RaftGlyph.ChevronRight,
                            modifier = Modifier.rotate(if (expanded) 90f else 0f),
                            size = 14.dp,
                        )
                    }
                    if (icon != null) Box(contentAlignment = Alignment.Center) { icon() }
                }
                val countText = count?.let { (countSeparator ?: "") + it }
                val countStyle = typography.monoMeta.copy(fontWeight = FontWeight.Normal, fontFeatureSettings = "tnum")
                val titleText: @Composable (Modifier) -> Unit = { titleModifier ->
                    RaftText(
                        title,
                        modifier = titleModifier,
                        style = typography.footnote.copy(fontWeight = FontWeight.Bold),
                        color = hint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (countSeparator != null) {
                    Row(Modifier.weight(1f, fill = false), verticalAlignment = Alignment.CenterVertically) {
                        titleText(Modifier.weight(1f, fill = false))
                        if (countText != null) RaftText(countText, style = countStyle, color = hint, maxLines = 1)
                    }
                } else {
                    titleText(Modifier.weight(1f, fill = false))
                    if (countText != null) RaftText(countText, style = countStyle, color = hint, maxLines = 1)
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
 * A group header's control (`SidebarSectionControl`: ghost, `h-7 min-w-7`): a 28dp
 * (touch target widened to 44dp) [RaftIconButton]
 * [RaftButtonVariant.Ghost] with a 16dp [glyph] in `foreground`, named
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
    RaftSizedIconButton(
        onClick = onClick,
        tooltip = tooltip,
        box = 28.dp,
        glyph = RaftSidebarSectionActionIconSize,
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
 * 28dp control sits on an 18dp line without growing the row.
 */
private fun Modifier.overhang(height: Dp): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        val reported = height.roundToPx()
        layout(placeable.width, reported) {
            placeable.place(0, (reported - placeable.height) / 2)
        }
    }
