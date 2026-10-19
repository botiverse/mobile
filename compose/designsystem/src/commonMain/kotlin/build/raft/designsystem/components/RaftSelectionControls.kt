package build.raft.designsystem.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isUnspecified
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.tokens.RaftUiPalette
import build.raft.designsystem.tokens.RaftUiThemeColors

// Value selection (States > Selected: value): a checkbox or radio shows the pick in ink,
// never in yellow; a switch is the one control whose "on" is a brand sticker. None of them
// has a press overlay; keyboard focus draws the focus ring round the box.

/** Colours of a checkbox or radio box in one state. */
@Immutable
internal data class RaftCheckLook(
    val fill: Color,
    /** The 1.5dp edge inside the box. */
    val border: Color,
    /** The check / minus knocked out of a checked box, or a radio's dot. Transparent when off. */
    val mark: Color,
)

/**
 * checkbox-indicator.tsx: unchecked is `layer-card` with a 1.5dp `line-strong` edge;
 * checked is an ink block (`foreground` fill and edge) with the mark knocked out in
 * `foreground-inverse`. Disabled: `fill-muted` with a `foreground-disabled` edge; checked
 * and disabled, a `foreground-disabled` block with a `layer-card` mark.
 */
internal fun raftCheckboxLook(checked: Boolean, enabled: Boolean, colors: RaftUiThemeColors): RaftCheckLook =
    when {
        checked && enabled -> RaftCheckLook(colors.foreground, colors.foreground, colors.foregroundInverse)
        checked -> RaftCheckLook(colors.foregroundDisabled, colors.foregroundDisabled, colors.layerCard)
        enabled -> RaftCheckLook(colors.layerCard, colors.lineStrong, Color.Transparent)
        else -> RaftCheckLook(colors.fillMuted, colors.foregroundDisabled, Color.Transparent)
    }

/**
 * radio-group.tsx: a `layer-card` circle with a 1.5dp `line-strong` edge, and an 8dp
 * `foreground` dot when selected. Disabled: `fill-muted`, `foreground-disabled` edge and dot.
 */
internal fun raftRadioLook(selected: Boolean, enabled: Boolean, colors: RaftUiThemeColors): RaftCheckLook {
    val dot = if (enabled) colors.foreground else colors.foregroundDisabled
    return RaftCheckLook(
        fill = if (enabled) colors.layerCard else colors.fillMuted,
        border = if (enabled) colors.lineStrong else colors.foregroundDisabled,
        mark = if (selected) dot else Color.Transparent,
    )
}

/** Colours of a switch in one state. */
@Immutable
internal data class RaftSwitchLook(
    val track: Color,
    val trackEdge: Color,
    /** The die-cut edge outside an "on" track (`sticker-edge`; transparent in light). */
    val dieCut: Color,
    val thumb: Color,
    /** The 1.5dp ring outside the thumb (`shadow-[0_0_0_1.5px_…]`). */
    val thumbRing: Color,
)

/**
 * switch.tsx: off is a `fill-muted` track with a `line-strong` edge and a `layer-card`
 * knob ringed in `line-strong`; on is a brand sticker (`brand`, `sticker-ink` edge, the
 * die-cut edge) with a `sticker-paper` knob ringed in `sticker-ink`. Disabled, on or off,
 * is `fill-muted` with `foreground-disabled` edges: no longer a sticker.
 */
internal fun raftSwitchLook(checked: Boolean, enabled: Boolean, colors: RaftUiThemeColors): RaftSwitchLook =
    when {
        !enabled -> RaftSwitchLook(colors.fillMuted, colors.foregroundDisabled, Color.Transparent, colors.fillMuted, colors.foregroundDisabled)
        checked ->
            RaftSwitchLook(
                RaftUiPalette.brand,
                RaftUiPalette.stickerInk,
                colors.stickerEdge,
                RaftUiPalette.stickerPaper,
                RaftUiPalette.stickerInk,
            )
        else -> RaftSwitchLook(colors.fillMuted, colors.lineStrong, Color.Transparent, colors.layerCard, colors.lineStrong)
    }

/** The 20dp box of a checkbox and a radio (`size-5`). */
private val RaftCheckSize = 20.dp

/** raft `--ease-slide`, the switch knob's slide. */
private val RaftEaseSlide = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

/**
 * raft-ui 0.8.0 Checkbox: a 20dp box, sticker radius, 1.5dp edge. Checked is an ink
 * block with the check knocked out ([indeterminate]: a minus), never yellow: it is a
 * value, not "you are here". Disabled greys it. Keyboard focus rings the box.
 *
 * Pass [onCheckedChange] = null when a parent row or card handles the tap (then it is
 * only the indicator). The box is exactly 20dp: raft-ui widens the hit area to 44 x 36dp
 * with an overhanging `::after`, which Compose foundation can't do without taking layout
 * room, so put a lone checkbox in a [RaftCheckboxRow] (the whole row toggles) or leave
 * room around it.
 */
@Composable
fun RaftCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = LocalRaftFieldState.current.enabled,
    indeterminate: Boolean = false,
    interactionSource: MutableInteractionSource? = null,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val interactive = onCheckedChange != null
    val toggle =
        if (onCheckedChange != null) {
            Modifier.triStateToggleable(
                state = raftToggleableState(checked, indeterminate),
                interactionSource = source,
                indication = null,
                enabled = enabled,
                role = Role.Checkbox,
                onClick = { onCheckedChange(!checked) },
            )
        } else {
            Modifier
        }
    RaftCheckboxBox(
        checked = checked || indeterminate,
        indeterminate = indeterminate,
        enabled = enabled,
        focused = interactive && enabled && raftIsFocused(source),
        modifier = modifier.then(toggle),
    )
}

internal fun raftToggleableState(checked: Boolean, indeterminate: Boolean): ToggleableState =
    when {
        indeterminate -> ToggleableState.Indeterminate
        checked -> ToggleableState.On
        else -> ToggleableState.Off
    }

@Composable
private fun RaftCheckboxBox(checked: Boolean, indeterminate: Boolean, enabled: Boolean, focused: Boolean, modifier: Modifier) {
    val colors = RaftTheme.colors
    val look = raftCheckboxLook(checked, enabled, colors)
    val shape = RoundedCornerShape(RaftRadius.Sticker)
    Box(
        modifier
            .size(RaftCheckSize)
            .raftFocusRing(focused, RaftRadius.Sticker, colors)
            .background(look.fill, shape)
            .border(RaftStroke.Control, look.border, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            RaftGlyphIcon(if (indeterminate) RaftGlyph.Minus else RaftGlyph.Check, size = 14.dp, color = look.mark)
        }
    }
}

/**
 * raft-ui 0.8.0 RadioGroup item: a 20dp circle with a 1.5dp `line-strong` edge and, when
 * [selected], an 8dp ink dot. One choice among a few that are all visible; put the radios
 * of one question in a [RaftRadioGroup]. As with [RaftCheckbox], [onClick] = null makes
 * it only the indicator (inside an option card or a [RaftRadioRow]).
 */
@Composable
fun RaftRadio(
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = LocalRaftFieldState.current.enabled,
    interactionSource: MutableInteractionSource? = null,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val select =
        if (onClick != null) {
            Modifier.selectable(selected, source, indication = null, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        } else {
            Modifier
        }
    RaftRadioBox(selected, enabled, focused = onClick != null && enabled && raftIsFocused(source), modifier = modifier.then(select))
}

@Composable
private fun RaftRadioBox(selected: Boolean, enabled: Boolean, focused: Boolean, modifier: Modifier) {
    val colors = RaftTheme.colors
    val look = raftRadioLook(selected, enabled, colors)
    Box(
        modifier
            .size(RaftCheckSize)
            .raftFocusRing(focused, RaftCheckSize / 2, colors)
            .background(look.fill, CircleShape)
            .border(RaftStroke.Control, look.border, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(8.dp).background(look.mark, CircleShape))
    }
}

/**
 * raft-ui 0.8.0 Switch: a 50 x 30dp pill that turns a setting on or off at once. Off: a
 * `fill-muted` track with a 1.5dp `line-strong` edge and a white knob ringed in
 * `line-strong`. On: a brand sticker (yellow, `sticker-ink` edge, the die-cut edge in
 * dark) with the knob slid 20dp to the end (220ms, `ease-slide`). Disabled is grey in
 * both positions. Keyboard focus rings the track.
 *
 * In a settings list put it in the row's trailing slot ([RaftListRow]) and pass the row's
 * title as [label], which names it for screen readers (inside a [RaftField] the field's
 * label is the default). [onCheckedChange] = null makes it only the indicator.
 */
@Composable
fun RaftSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = LocalRaftFieldState.current.enabled,
    label: String? = LocalRaftFieldState.current.label,
    interactionSource: MutableInteractionSource? = null,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val colors = RaftTheme.colors
    val look = raftSwitchLook(checked, enabled, colors)
    val focused = onCheckedChange != null && enabled && raftIsFocused(source)
    val knobOffset by animateDpAsState(if (checked) 20.dp else 0.dp, tween(220, easing = RaftEaseSlide))
    val toggle =
        if (onCheckedChange != null) {
            Modifier.triStateToggleable(
                state = raftToggleableState(checked, indeterminate = false),
                interactionSource = source,
                indication = null,
                enabled = enabled,
                role = Role.Switch,
                onClick = { onCheckedChange(!checked) },
            )
        } else {
            Modifier
        }
    val radius = 15.dp
    Box(
        modifier
            .then(toggle)
            .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)
            .size(50.dp, 30.dp)
            .raftFocusRing(focused, radius, colors)
            .raftDieCutEdge(look.dieCut, radius)
            .background(look.track, CircleShape)
            .border(RaftStroke.Control, look.trackEdge, CircleShape)
            // `p-[2.5px]` inside the 1.5dp edge.
            .padding(RaftStroke.Control + 2.5.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset(x = knobOffset)
                .size(22.dp)
                .raftOuterRing(look.thumbRing, RaftStroke.Control, 11.dp)
                .background(look.thumb, CircleShape),
        )
    }
}

/**
 * The radios of one question: a column with selectable-group semantics. Fill it with
 * [RaftRadioRow]s, or with option cards ([RaftCard] `Option` with a [RaftRadio] leading).
 */
@Composable
fun RaftRadioGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.selectableGroup(), content = content)
}

/**
 * A checkbox and its label as one tappable row (the page hand-off's choice row, raft-ui
 * FieldItem): at least 44dp tall, 12dp vertical padding, the box 12dp before the text and
 * centred on the label's first line (Alignment: a control and its label). [label] is
 * `text-subhead` semibold (sentence case), [description] `text-footnote`
 * `foreground-muted` under it. The whole row toggles; keyboard focus rings the box.
 */
@Composable
fun RaftCheckboxRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = LocalRaftFieldState.current.enabled,
    interactionSource: MutableInteractionSource? = null,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    RaftChoiceRow(
        label = label,
        description = description,
        enabled = enabled,
        modifier =
            modifier.triStateToggleable(
                state = raftToggleableState(checked, indeterminate = false),
                interactionSource = source,
                indication = null,
                enabled = enabled,
                role = Role.Checkbox,
                onClick = { onCheckedChange(!checked) },
            ),
    ) {
        RaftCheckboxBox(checked, indeterminate = false, enabled = enabled, focused = enabled && raftIsFocused(source), modifier = Modifier)
    }
}

/**
 * A radio and its label as one tappable row, for a [RaftRadioGroup] (ResetAgentDialog,
 * the archive choice): the [RaftCheckboxRow] layout with a [RaftRadio]. Rows have no
 * border; a warning about the choice goes in a small [RaftBanner] under the group.
 */
@Composable
fun RaftRadioRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = LocalRaftFieldState.current.enabled,
    interactionSource: MutableInteractionSource? = null,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    RaftChoiceRow(
        label = label,
        description = description,
        enabled = enabled,
        modifier = modifier.selectable(selected, source, indication = null, enabled = enabled, role = Role.RadioButton, onClick = onClick),
    ) {
        RaftRadioBox(selected, enabled, focused = enabled && raftIsFocused(source), modifier = Modifier)
    }
}

@Composable
private fun RaftChoiceRow(
    label: String,
    description: String?,
    enabled: Boolean,
    modifier: Modifier,
    control: @Composable () -> Unit,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val labelStyle = typography.subhead.copy(fontWeight = FontWeight.SemiBold)
    // Centre the 20dp control on the label's first line: (1lh - 20) / 2, which is 0 at the
    // normal size and grows with the font scale (the line height is in sp).
    val controlTop: Dp = ((raftLineHeightDp(labelStyle.lineHeight, labelStyle.fontSize, LocalDensity.current) - RaftCheckSize) / 2).coerceAtLeast(0.dp)
    Row(
        modifier.fillMaxWidth().heightIn(min = 44.dp).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = controlTop)) { control() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            RaftText(label, style = labelStyle, color = if (enabled) colors.foreground else colors.foregroundDisabled)
            if (description != null) {
                RaftText(description, style = typography.footnote, color = if (enabled) colors.foregroundMuted else colors.foregroundDisabled)
            }
        }
    }
}

/**
 * A text style's line height in dp: [lineHeight] in sp, or in em of [fontSize]; with no
 * line height, the font size. Any unit it can't resolve (unspecified, or em of an em font
 * size) falls back to [RaftCheckSize], which centres nothing (the control sits at the top).
 */
internal fun raftLineHeightDp(lineHeight: TextUnit, fontSize: TextUnit, density: Density): Dp =
    with(density) {
        when {
            lineHeight.isSp -> lineHeight.toDp()
            lineHeight.isEm && fontSize.isSp -> fontSize.toDp() * lineHeight.value
            lineHeight.isUnspecified && fontSize.isSp -> fontSize.toDp()
            else -> RaftCheckSize
        }
    }
