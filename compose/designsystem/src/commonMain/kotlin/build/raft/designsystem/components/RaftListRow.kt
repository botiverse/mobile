package build.raft.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.LocalRaftSurfaceColor
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.tokens.RaftUiThemeColors

/** raft-ui ListRow `variant`. */
enum class RaftListRowVariant(
    /** Where the hairline above a row starts (`--list-row-rule-start`); null: no lines. */
    internal val ruleStart: Dp?,
    internal val minHeight: Dp,
) {
    /** A conversation, a person or an agent: a 35dp leading block, at least 64dp tall. */
    Default(63.dp, 64.dp),

    /** A setting: title (and its consequence), then a value and a chevron, or a switch. */
    Plain(16.dp, 0.dp),

    /** A shortcut into another page (Search, Activity): a 20dp icon and a name. No lines. */
    Entry(null, 0.dp),
}

/**
 * The type of a [RaftListRow]'s title and subtitle.
 */
enum class RaftListRowText {
    /** raft-ui ListRow: title `text-body` 16/24 semibold, subtitle Space Mono 12/16 ink-3. */
    Default,

    /**
     * The web members page row (`ChannelMemberRow`: `MEMBER_ROW_TITLE_CLASS` /
     * `MEMBER_ROW_SUB_CLASS`): title `text-subhead` 15/20 semibold, subtitle `text-caption`
     * 12/16 ink-3 in the sans face (a role, a description or an agent's activity, not a
     * machine label).
     */
    Member,
}

/** What the place is to the person (raft-ui `RowTone`). */
enum class RaftRowTone {
    Default,

    /** Something waits: bold title, the preview in `foreground`, a pink count. */
    Unread,

    /** The person muted it: the title in `foreground-muted`, a gray count in the meta. */
    Muted,

    /** The person hasn't joined yet: title and preview in `foreground-hint`. */
    Away,
}

/** The row's three inks (`--list-row-ink*`), decided once on the row and read by its parts. */
@Immutable
data class RaftListRowInks(
    /** Title. */
    val ink: Color,
    /** Description / preview. */
    val ink2: Color,
    /** Meta, subtitle, value, icons, chevron. */
    val ink3: Color,
    val titleWeight: FontWeight,
)

/** A disabled row ignores its tone: everything is `foreground-disabled`. */
internal fun raftListRowInks(tone: RaftRowTone, enabled: Boolean, colors: RaftUiThemeColors): RaftListRowInks {
    if (!enabled) {
        val d = colors.foregroundDisabled
        return RaftListRowInks(d, d, d, FontWeight.SemiBold)
    }
    val base = RaftListRowInks(colors.foreground, colors.foregroundMuted, colors.foregroundHint, FontWeight.SemiBold)
    return when (tone) {
        RaftRowTone.Default -> base
        RaftRowTone.Unread -> base.copy(ink2 = colors.foreground, titleWeight = FontWeight.Bold)
        RaftRowTone.Muted -> base.copy(ink = colors.foregroundMuted)
        RaftRowTone.Away -> base.copy(ink = colors.foregroundHint, ink2 = colors.foregroundHint)
    }
}

/** The inks of the enclosing [RaftListRow], for parts placed in its slots. */
val LocalRaftListRowInks = staticCompositionLocalOf<RaftListRowInks?> { null }

/**
 * raft-ui ListRowGroup, the inset list: rows flush inside one card (`layer-card`, 1dp
 * `line-hairline`, container radius, clipped, no padding of its own), so a selected
 * row's wash follows the corners. Give every row after the first `divider = true`.
 * Provides `layer-card` as [LocalRaftSurfaceColor].
 */
@Composable
fun RaftListRowGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = RaftTheme.colors
    val shape = RoundedCornerShape(RaftRadius.Container)
    CompositionLocalProvider(LocalRaftSurfaceColor provides colors.layerCard) {
        Column(
            modifier
                // An inset card is content: it stays out of the horizontal safe area, and its
                // rows (which consume it here) do not pad it again.
                .raftSafeAreaHorizontalPadding()
                .clip(shape)
                .background(colors.layerCard, shape)
                .border(RaftStroke.Hairline, colors.lineHairline, shape),
            content = content,
        )
    }
}

/**
 * raft-ui 0.8.0 ListRow (and the AvatarListRow of the web pages): one row of a list, the
 * whole row the target. `px-4 py-3`, gap 12, items centred ([alignTop] puts the leading
 * and trailing parts on the first line, raft `align=start`). A [RaftListRowVariant.Plain] row
 * with a [description] centres them on the title's first line (raft-ui 0.21).
 *
 * - [leading]: a 35dp avatar or tile, or a 20dp icon (drawn in ink-3).
 * - Text column: [title] 16/24 semibold on one line (bold when [RaftRowTone.Unread]) with
 *   [titleTrailing] right after the name (6dp; a Bot badge; the name truncates first) and
 *   [meta] (the time, Space Mono 12 ink-3) at the end; [subtitle] on its own line in Space
 *   Mono 12 ink-3, 2dp under the title (4dp with [alignTop]); [description] (the preview,
 *   or a setting's consequence with [wrapDescription], up to [descriptionMaxLines]) in 14/20 ink-2, then at most one
 *   [badge] at the end of that line.
 * - [trailing]: a value and chevron ([RaftListRowValue], [RaftListRowChevron]), a switch,
 *   or a 32dp button, centred, 6dp apart.
 * - [divider] draws the hairline above the row, from where the text starts (63dp) in the
 *   default variant and from the edge (16dp) in [RaftListRowVariant.Plain]; entry rows
 *   never have one.
 *
 * [text]: [RaftListRowText.Member] sets the title and subtitle as the web members page does.
 *
 * [titleCopyText] / [subtitleCopyText]: a long press on the title / subtitle copies that text
 * ([raftCopyable]; a member's name, an `@handle`); the row's tap still opens. Null (default):
 * not copyable.
 *
 * States (States, ListRow): [onClick] = null is a display-only row (no press, no focus;
 * its controls are the targets). Press lays `fill-pressed` over the whole row; keyboard
 * focus draws a 2dp inset `foreground` outline (rows touch their group's edge).
 * [selected] is the item open beside the list: an `info-soft` wash, no border, no press
 * overlay, and `selected` in semantics; the wash (over the surface under it) becomes
 * [LocalRaftSurfaceColor] for the row's parts. [menuOpen]: the row's menu (long press, ⋯) is open:
 * `fill-row-open` (raft-ui 0.13), under a selection. [enabled] = false greys every part and drops the tone, and the fill stays.
 * A selected or disabled row never shows its [badge] (no count or @).
 */
@Composable
fun RaftListRow(
    title: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    variant: RaftListRowVariant = RaftListRowVariant.Default,
    tone: RaftRowTone = RaftRowTone.Default,
    selected: Boolean = false,
    enabled: Boolean = true,
    divider: Boolean = false,
    meta: String? = null,
    subtitle: String? = null,
    description: String? = null,
    wrapDescription: Boolean = false,
    descriptionMaxLines: Int = Int.MAX_VALUE,
    alignTop: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    badge: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
    menuOpen: Boolean = false,
    text: RaftListRowText = RaftListRowText.Default,
    titleCopyText: String? = null,
    subtitleCopyText: String? = null,
    titleTrailing: (@Composable () -> Unit)? = null,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val inks = raftListRowInks(tone, enabled, colors)
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val interactive = onClick != null
    val isPressed = raftIsPressed(source)
    val pressed = interactive && enabled && !selected && isPressed
    val isFocused = raftIsFocused(source)
    val focused = interactive && enabled && isFocused
    val ruleStart = variant.ruleStart
    val showBadge = badge != null && enabled && !selected
    // raft-ui 0.21: a plain row with a description centres its ends on the title's first line,
    // not on the title and description together (Alignment: a control and its label).
    val endsOnTitleLine = variant == RaftListRowVariant.Plain && description != null && !alignTop
    val align = if (alignTop || endsOnTitleLine) Alignment.Top else Alignment.CenterVertically
    val titleLine = raftLineHeightDp(typography.body.lineHeight, typography.body.fontSize, LocalDensity.current)
    val endModifier = if (endsOnTitleLine) Modifier.raftCenterOnLine(titleLine) else Modifier
    val surface = if (selected) colors.infoSoft.compositeOver(RaftTheme.surfaceColor) else RaftTheme.surfaceColor
    CompositionLocalProvider(
        LocalRaftListRowInks provides inks,
        LocalRaftContentColor provides inks.ink3,
        LocalRaftSurfaceColor provides surface,
    ) {
        Row(
            modifier
                .fillMaxWidth()
                .heightIn(min = variant.minHeight)
                .then(if (selected) Modifier.background(colors.infoSoft).semantics { this.selected = true } else Modifier)
                .then(if (menuOpen && !selected) Modifier.background(colors.fillRowOpen) else Modifier)
                .raftPressOverlay(pressed, raftPressOverlayColor(RaftPressSurface.Paper, colors), 0.dp)
                .raftInsetFocusOutline(focused, colors.foreground)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(
                            interactionSource = source,
                            indication = null,
                            enabled = enabled,
                            role = Role.Button,
                            onClick = onClick,
                        )
                    } else {
                        Modifier
                    },
                )
                // The washes above run edge to edge; the content (and the divider, which starts
                // where the text starts) stays out of the horizontal safe area.
                .raftSafeAreaHorizontalPadding()
                .then(if (divider && ruleStart != null) Modifier.topHairline(colors.lineHairline, ruleStart) else Modifier)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = align,
        ) {
            if (leading != null) Box(endModifier, contentAlignment = Alignment.Center) { leading() }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (alignTop) 4.dp else 2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val titleStyle = (if (text == RaftListRowText.Member) typography.subhead else typography.body).copy(fontWeight = inks.titleWeight)
                    if (titleTrailing == null) {
                        RaftText(
                            title,
                            modifier = Modifier.weight(1f).raftCopyable(titleCopyText),
                            style = titleStyle,
                            color = inks.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        // The name and what follows it (a Bot badge), 6dp apart: the slot is
                        // measured first, so only the name truncates.
                        Row(
                            Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RaftText(
                                title,
                                modifier = Modifier.weight(1f, fill = false).raftCopyable(titleCopyText),
                                style = titleStyle,
                                color = inks.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            titleTrailing()
                        }
                    }
                    if (meta != null) RaftListRowMeta(meta)
                }
                if (subtitle != null) {
                    RaftText(
                        subtitle,
                        modifier = Modifier.raftCopyable(subtitleCopyText),
                        style = if (text == RaftListRowText.Member) typography.caption else typography.monoMeta,
                        color = inks.ink3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (description != null || showBadge) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (description != null) {
                            RaftText(
                                description,
                                modifier = Modifier.weight(1f),
                                style = typography.callout,
                                color = inks.ink2,
                                maxLines = if (wrapDescription) descriptionMaxLines.coerceAtLeast(1) else 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        if (showBadge) badge?.invoke()
                    }
                }
            }
            if (trailing != null) {
                Row(endModifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, content = trailing)
            }
        }
    }
}

/**
 * Centres the element on a [line] tall line at the top of its row, keeping its own size: a
 * taller one (a 30dp switch on a 24dp line) reaches above the line by half the difference.
 */
private fun Modifier.raftCenterOnLine(line: Dp): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) { placeable.place(0, (line.roundToPx() - placeable.height) / 2) }
    }

/**
 * Machine-written facts in Space Mono 12 tabular, ink-3: the time, a gray count. Kept whole
 * beside the title, which gives way first; with no room left (an email in a narrow row) it
 * truncates with an ellipsis instead of clipping (raft-ui 0.23 `min-w-0 truncate`, #721).
 */
@Composable
fun RaftListRowMeta(text: String, modifier: Modifier = Modifier) {
    RaftText(
        text,
        modifier = modifier,
        style = RaftTheme.typography.monoMeta.copy(fontFeatureSettings = "tnum"),
        color = LocalRaftListRowInks.current?.ink3 ?: RaftTheme.colors.foregroundHint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A setting's current value on the right: 15/20 ink-3, one line. */
@Composable
fun RaftListRowValue(text: String, modifier: Modifier = Modifier, maxWidth: Dp = 160.dp) {
    RaftText(
        text,
        modifier = modifier.widthIn(max = maxWidth),
        style = RaftTheme.typography.subhead,
        color = LocalRaftListRowInks.current?.ink3 ?: RaftTheme.colors.foregroundHint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The ink-3 arrow of a row that opens another page (20dp `chevron-right`). */
@Composable
fun RaftListRowChevron(modifier: Modifier = Modifier) {
    RaftGlyphIcon(
        RaftGlyph.ChevronRight,
        modifier = modifier,
        size = 20.dp,
        color = LocalRaftListRowInks.current?.ink3 ?: RaftTheme.colors.foregroundHint,
    )
}
