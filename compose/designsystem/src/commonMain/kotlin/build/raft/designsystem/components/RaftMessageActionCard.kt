package build.raft.designsystem.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.tokens.RaftUiPalette

// raft-ui MessageActionCard (packages/ui/src/components/message-action-card): the card an
// agent attaches to its message with an action already filled in, for a person to confirm;
// afterwards it records who did it and when. Mirrors the raft-ui parts:
// - MessageActionCard + Header + HeaderIcon/Title/Description + Status + CollapseTrigger
//                                      -> RaftMessageActionCard (its parameters)
// - MessageActionCardHeaderDescription -> RaftMessageActionCardHeaderDescription
// - MessageActionCardNotice            -> RaftMessageActionCardNotice
// - MessageActionCardProperties / Property / Label / Value
//                                      -> RaftMessageActionCardProperties, RaftMessageActionCardProperty
// - MessageActionCardHintLabel + Hint  -> RaftMessageActionCardHint
// - MessageActionCardScopeList / Label / Viewport / Scope / Name / Description
//                                      -> RaftMessageActionCardScopeList, RaftMessageActionCardScope
// - MessageActionCardActions + ActionsNote -> RaftMessageActionCardActions
// The parts carry no business logic: the title, values, words and buttons come from the app.

/** raft-ui MessageActionCard metrics. */
object RaftMessageActionCardDefaults {
    /** The card is never wider than this (raft-ui `max-w-[440px]`). */
    val MaxWidth: Dp = 440.dp

    /** The header's icon tile, the line the title, status and toggle centre on (27px). */
    val HeaderIconSize: Dp = 27.dp

    /** The icon inside the tile (`[&>svg]:size-3.5`). */
    val HeaderGlyphSize: Dp = 14.dp

    /** A long permission list scrolls past this height; the cap cuts through the third row. */
    val ScopeListMaxHeight: Dp = 164.dp

    /**
     * The properties' own width from which they sit in two columns: 24rem of the card's
     * content (raft-ui 0.23 `@container/action-card`, raft-design #721), not the window's.
     * A card at its 440dp cap has 416dp of content, so it keeps two columns; a phone card
     * stacks.
     */
    val TwoColumnMinWidth: Dp = 384.dp

    /** The fold's height animation (`duration-200`). */
    internal const val FoldMillis = 200
}

/**
 * raft-ui MessageActionCardStatus tones: the colour of the status pixel before the word. A
 * Badge `default` / `muted` status is [Neutral].
 */
enum class RaftMessageActionCardStatusTone(internal val color: Color) {
    /** `bg-inactive`: nothing to do (View only, Declined, Paused). */
    Neutral(RaftUiPalette.inactive),
    /** Raft yellow in every theme, as [RaftStatusVariant.Primary]: a status light ignores the theme. */
    Primary(RaftUiPalette.selected),
    Information(RaftUiPalette.info),

    /** `bg-attention`, the pink pixel: the card waits on the reader (Confirm again). */
    Accent(RaftUiPalette.attention),
    Success(RaftUiPalette.success),
    Warning(RaftUiPalette.warning),
    Danger(RaftUiPalette.danger),
}

/**
 * raft-ui `MessageActionCard`: a card inside the body of the agent's message, at most 440dp
 * wide, on `layer-card` with a `line-hairline` edge and the container radius.
 *
 * The header (top-aligned, so a wrapping title keeps the icon, status and toggle on its first
 * line): the [icon] in a 27dp `fill-muted` tile ([RaftGlyph.Zap] when null), the [title] in
 * `text-subhead` bold, under it the [description] ([RaftMessageActionCardHeaderDescription]:
 * what happened, or why the reader can't act; it stays visible while folded), then the
 * [status] word led by its [statusTone] pixel, and the fold toggle.
 *
 * The [content] (12dp sides and bottom, 8dp between parts) folds away under the header when
 * [expanded] is false; pass [onExpandedChange] to offer the toggle (named [toggleLabel], "Show
 * or hide details"). Without it the card stays open, as raft-ui's card without a
 * CollapseTrigger. Fold a done or view-only card by default.
 */
@Composable
fun RaftMessageActionCard(
    title: String,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    description: (@Composable () -> Unit)? = null,
    status: String? = null,
    statusTone: RaftMessageActionCardStatusTone = RaftMessageActionCardStatusTone.Neutral,
    expanded: Boolean = true,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    toggleLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = RaftTheme.colors
    val shape = RoundedCornerShape(RaftRadius.Container)
    val open = expanded || onExpandedChange == null
    Column(
        modifier
            .fillMaxWidth()
            .widthIn(max = RaftMessageActionCardDefaults.MaxWidth)
            .clip(shape)
            .background(colors.layerCard, shape)
            .border(RaftStroke.Hairline, colors.lineHairline, shape),
    ) {
        RaftMessageActionCardHeader(
            title = title,
            icon = icon,
            description = description,
            status = status,
            statusTone = statusTone,
            open = open,
            onExpandedChange = onExpandedChange,
            toggleLabel = toggleLabel,
        )
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(tween(RaftMessageActionCardDefaults.FoldMillis), expandFrom = Alignment.Top),
            exit = shrinkVertically(tween(RaftMessageActionCardDefaults.FoldMillis), shrinkTowards = Alignment.Top),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun RaftMessageActionCardHeader(
    title: String,
    icon: (@Composable () -> Unit)?,
    description: (@Composable () -> Unit)?,
    status: String?,
    statusTone: RaftMessageActionCardStatusTone,
    open: Boolean,
    onExpandedChange: ((Boolean) -> Unit)?,
    toggleLabel: String?,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val line = RaftMessageActionCardDefaults.HeaderIconSize
    val titleStyle = typography.subhead.copy(fontWeight = FontWeight.Bold)
    val statusStyle = typography.caption.copy(fontWeight = FontWeight.SemiBold)
    // raft-ui `pb-2 group-data-closed:pb-3`: the folded card closes 12dp under its header.
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = if (open) 8.dp else 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .size(line)
                .background(colors.fillMuted, RoundedCornerShape(RaftRadius.Sticker))
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(LocalRaftContentColor provides colors.foregroundMuted) {
                if (icon != null) icon() else RaftGlyphIcon(RaftGlyph.Zap, size = RaftMessageActionCardDefaults.HeaderGlyphSize)
            }
        }
        Column(Modifier.weight(1f).padding(top = (line - lineHeightOf(titleStyle)) / 2)) {
            RaftText(title, Modifier.semantics { heading() }, style = titleStyle, color = colors.foreground)
            if (description != null) {
                Box(Modifier.padding(top = 2.dp)) { description() }
            }
        }
        if (status != null) {
            Row(
                Modifier.padding(top = (line - lineHeightOf(statusStyle)) / 2),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RaftStatusDot(size = RaftStatusSize.Md, color = statusTone.color)
                RaftText(status, style = statusStyle, color = colors.foregroundMuted, maxLines = 1)
            }
        }
        if (onExpandedChange != null) {
            RaftMessageActionCardCollapseTrigger(open, onExpandedChange, toggleLabel)
        }
    }
}

/** raft-ui `MessageActionCardCollapseTrigger`: a 27dp chevron button; the chevron turns up while open. */
@Composable
private fun RaftMessageActionCardCollapseTrigger(open: Boolean, onExpandedChange: (Boolean) -> Unit, label: String?) {
    val colors = RaftTheme.colors
    val source = remember { MutableInteractionSource() }
    val pressed = raftIsPressed(source)
    val turn by animateFloatAsState(if (open) 180f else 0f, tween(RaftMessageActionCardDefaults.FoldMillis))
    Box(
        Modifier
            // `-mr-1`: the toggle's tile reaches 4dp into the header's padding.
            .offset(x = 4.dp)
            .size(RaftMessageActionCardDefaults.HeaderIconSize)
            .background(if (pressed) colors.fillMedium else Color.Transparent, RoundedCornerShape(RaftRadius.Chip))
            .clickable(source, indication = null, role = Role.Button) { onExpandedChange(!open) }
            .semantics {
                if (label != null) contentDescription = label
                if (open) collapse { onExpandedChange(false); true } else expand { onExpandedChange(true); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        RaftGlyphIcon(
            RaftGlyph.ChevronDown,
            Modifier.rotate(turn),
            size = 16.dp,
            color = if (pressed) colors.foreground else colors.foregroundMuted,
        )
    }
}

/**
 * raft-ui `MessageActionCardHeaderDescription`: the line under the title, `text-footnote`
 * `foreground-muted`. On a done card it is the record, led by an ink check ([check]): "Created
 * by Ada · 10:51 → #release-review"; on a view-only card, why the reader can't act. [trailing]
 * follows the text on the same line (the result, as a link).
 */
@Composable
fun RaftMessageActionCardHeaderDescription(
    text: String,
    modifier: Modifier = Modifier,
    check: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = RaftTheme.colors
    val style = RaftTheme.typography.footnote
    Row(modifier, verticalAlignment = Alignment.Top) {
        if (check) {
            Box(Modifier.padding(top = (lineHeightOf(style) - 16.dp) / 2, end = 6.dp)) {
                RaftGlyphIcon(RaftGlyph.Check, size = 16.dp, color = colors.foreground)
            }
        }
        Row(Modifier.weight(1f, fill = false), verticalAlignment = Alignment.Top) {
            RaftText(text, Modifier.weight(1f, fill = false), style = style, color = colors.foregroundMuted)
            if (trailing != null) {
                Box(Modifier.padding(start = 4.dp)) { trailing() }
            }
        }
    }
}

/**
 * raft-ui `MessageActionCardNotice`: a standing note from Raft about consequences or
 * permissions, at most one, before the properties. A `fill-muted` block with an optional 14dp
 * [icon], `text-footnote` `foreground-muted`. Neutral, never a warning colour.
 */
@Composable
fun RaftMessageActionCardNotice(
    text: String,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
) {
    val colors = RaftTheme.colors
    val style = RaftTheme.typography.footnote
    Row(
        modifier
            .fillMaxWidth()
            .background(colors.fillMuted, RoundedCornerShape(RaftRadius.Control))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (icon != null) {
            Box(
                Modifier
                    .padding(top = (lineHeightOf(style) - 14.dp) / 2)
                    .size(14.dp)
                    .clearAndSetSemantics { },
                contentAlignment = Alignment.Center,
            ) {
                CompositionLocalProvider(LocalRaftContentColor provides colors.foregroundMuted) { icon() }
            }
        }
        RaftText(text, Modifier.weight(1f), style = style, color = colors.foregroundMuted)
    }
}

/**
 * raft-ui `MessageActionCardProperties`: the values the reader checks, a description list of
 * [RaftMessageActionCardProperty] rows. It switches on its own width (the card's content),
 * not the window's: below [RaftMessageActionCardDefaults.TwoColumnMinWidth] each value sits
 * 2dp under its label, with no minimum height or padding of its own, and 10dp above the next
 * label; from it the labels form a column (at least 88dp, at most 160dp) 16dp before the
 * values, each value at least 32dp tall with 6dp above and below. [twoColumns] overrides the
 * width rule.
 */
@Composable
fun RaftMessageActionCardProperties(
    modifier: Modifier = Modifier,
    twoColumns: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val typography = RaftTheme.typography
    // Wide, the label sits on the value's first baseline: the value's 6dp top padding plus
    // half the difference of the two line heights.
    val labelTop = 6.dp + (lineHeightOf(typography.callout) - lineHeightOf(typography.footnote)) / 2
    Layout(content, modifier.fillMaxWidth()) { measurables, constraints ->
        val wide = twoColumns ?: (!constraints.hasBoundedWidth || constraints.maxWidth >= RaftMessageActionCardDefaults.TwoColumnMinWidth.roundToPx())
        val labels = measurables.filterIndexed { index, _ -> index % 2 == 0 }
        val values = measurables.filterIndexed { index, _ -> index % 2 == 1 }
        val labelCap = 160.dp.roundToPx()
        val rowGap = 2.dp.roundToPx()
        if (!wide) {
            // `@max-sm/action-card`: one column; the 2dp row gap plus `mt-2` on each following label.
            val pairGap = rowGap + 8.dp.roundToPx()
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val rows =
                labels.indices.map { index ->
                    labels[index].measure(loose.copy(maxWidth = minOf(labelCap, constraints.maxWidth))) to values.getOrNull(index)?.measure(loose)
                }
            val height = rows.sumOf { (label, value) -> label.height + (value?.let { rowGap + it.height } ?: 0) } + pairGap * (rows.size - 1).coerceAtLeast(0)
            return@Layout layout(constraints.maxWidth, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
                var y = 0
                rows.forEach { (label, value) ->
                    label.placeRelative(0, y)
                    y += label.height
                    if (value != null) {
                        value.placeRelative(0, y + rowGap)
                        y += rowGap + value.height
                    }
                    y += pairGap
                }
            }
        }
        // Two columns: label column = the widest label, clamped to 88..160dp, then 16dp, then
        // the values (`min-h-8 py-1.5`).
        val gap = 16.dp.roundToPx()
        val valuePad = 6.dp.roundToPx()
        val valueMin = 32.dp.roundToPx()
        val top = labelTop.roundToPx()
        val labelWidth =
            labels.maxOfOrNull { it.maxIntrinsicWidth(constraints.maxHeight) }
                ?.coerceIn(88.dp.roundToPx(), labelCap)
                ?: 88.dp.roundToPx()
        val valueWidth = (constraints.maxWidth - labelWidth - gap).coerceAtLeast(0)
        val rows =
            labels.indices.map { index ->
                val label = labels[index].measure(constraints.copy(minWidth = 0, maxWidth = labelWidth, minHeight = 0))
                val value = values.getOrNull(index)?.measure(constraints.copy(minWidth = 0, maxWidth = valueWidth, minHeight = 0))
                label to value
            }
        val rowHeights = rows.map { (label, value) -> maxOf(top + label.height, value?.let { maxOf(valueMin, it.height + valuePad * 2) } ?: 0) }
        val height = rowHeights.sum() + rowGap * (rows.size - 1).coerceAtLeast(0)
        layout(constraints.maxWidth, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            var y = 0
            rows.forEachIndexed { index, (label, value) ->
                label.placeRelative(0, y + top)
                value?.placeRelative(labelWidth + gap, y + valuePad)
                y += rowHeights[index] + rowGap
            }
        }
    }
}

/**
 * One row of [RaftMessageActionCardProperties]: the [label] (`text-footnote` medium
 * `foreground-hint`, at most 160dp) and its [value] (`text-callout` `foreground`; the list
 * sets its height and padding). [mono] sets the value in the mono face (an id, a path);
 * [valueColor] greys a hidden value; [valueMaxLines] cuts a long value at that many lines with
 * an ellipsis (raft-ui `line-clamp-*` on the value).
 */
@Composable
fun RaftMessageActionCardProperty(
    label: String,
    value: String,
    mono: Boolean = false,
    valueColor: Color = Color.Unspecified,
    valueMaxLines: Int = Int.MAX_VALUE,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val labelStyle = typography.footnote.copy(fontWeight = FontWeight.Medium)
    val valueStyle = if (mono) typography.callout.copy(fontFamily = typography.monoMeta.fontFamily ?: FontFamily.Monospace) else typography.callout
    RaftText(label, style = labelStyle, color = colors.foregroundHint)
    RaftText(
        value,
        style = valueStyle,
        color = if (valueColor == Color.Unspecified) colors.foreground else valueColor,
        maxLines = valueMaxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * raft-ui `MessageActionCardHintLabel` + `MessageActionCardHint`: the agent's own note (its
 * reason, or what it filled in) under a [label] ("Reason"), as a quote: a 2dp `line-seam` rule,
 * `text-footnote` `foreground-muted`, 4dp under the label.
 */
@Composable
fun RaftMessageActionCardHint(
    text: String,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label != null) {
            RaftText(label, style = typography.footnote.copy(fontWeight = FontWeight.Medium), color = colors.foregroundHint)
        }
        val rule = colors.lineSeam
        RaftText(
            text,
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    val w = 2.dp.toPx()
                    drawRect(rule, topLeft = Offset.Zero, size = androidx.compose.ui.geometry.Size(w, size.height))
                }
                .padding(start = 2.dp + 10.dp),
            style = typography.footnote,
            color = colors.foregroundMuted,
        )
    }
}

/**
 * raft-ui `MessageActionCardScopeList`: the permissions an app asks for, under a [label]
 * ("Permissions (4)"). The [content] ([RaftMessageActionCardScope] rows, 6dp apart) scrolls
 * inside the list past 164dp, so the card doesn't grow with it. [viewportDescription] names
 * the list for screen readers ("Permissions").
 */
@Composable
fun RaftMessageActionCardScopeList(
    label: String,
    modifier: Modifier = Modifier,
    viewportDescription: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = RaftTheme.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RaftText(label, style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.Medium), color = colors.foregroundHint)
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = RaftMessageActionCardDefaults.ScopeListMaxHeight)
                .verticalScroll(rememberScrollState())
                .then(if (viewportDescription != null) Modifier.semantics { contentDescription = viewportDescription } else Modifier)
                .padding(end = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

/**
 * One permission of a [RaftMessageActionCardScopeList]: its [name] (`text-mono-meta`) over
 * its [description] (`text-footnote` `foreground-muted`), in a `layer-card` box with a
 * `line-hairline` edge.
 */
@Composable
fun RaftMessageActionCardScope(
    name: String,
    description: String? = null,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    val shape = RoundedCornerShape(RaftRadius.Control)
    Column(
        modifier
            .fillMaxWidth()
            .background(colors.layerCard, shape)
            .border(RaftStroke.Hairline, colors.lineHairline, shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        RaftText(name, style = RaftTheme.typography.monoMeta, color = colors.foreground)
        if (description != null) {
            RaftText(description, style = RaftTheme.typography.footnote, color = colors.foregroundMuted)
        }
    }
}

/**
 * raft-ui `MessageActionCardActions`: the buttons, bottom right (they wrap when the card is
 * narrow), 8dp apart, with an optional [note] taking the room on their left (why they're
 * disabled, whom the card waits on). The confirm is a [RaftButton] at the default variant and
 * size, never pink: the screen's one pink button is the composer's send.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RaftMessageActionCardActions(
    modifier: Modifier = Modifier,
    note: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = RaftTheme.colors
    FlowRow(
        modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        if (note != null) {
            RaftText(
                note,
                Modifier.weight(1f).widthIn(min = 160.dp),
                style = RaftTheme.typography.footnote,
                color = colors.foregroundMuted,
            )
        }
        content()
    }
}

@Composable
private fun lineHeightOf(style: androidx.compose.ui.text.TextStyle): Dp = with(LocalDensity.current) { style.lineHeight.toDp() }
