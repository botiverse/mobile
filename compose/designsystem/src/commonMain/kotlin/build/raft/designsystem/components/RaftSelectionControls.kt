package build.raft.designsystem.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isUnspecified
import build.raft.designsystem.motion.RaftMotion
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.tokens.RaftUiPalette
import build.raft.designsystem.tokens.RaftUiThemeColors
import kotlin.math.roundToInt

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
 * knob ringed in `line-strong`; on is the selected sticker (`selected`, `sticker-ink` edge, the
 * die-cut edge) with a `sticker-paper` knob ringed in `sticker-ink`. Disabled, on or off,
 * is `fill-muted` with `foreground-disabled` edges: no longer a sticker.
 */
internal fun raftSwitchLook(checked: Boolean, enabled: Boolean, colors: RaftUiThemeColors): RaftSwitchLook =
    when {
        !enabled -> RaftSwitchLook(colors.fillMuted, colors.foregroundDisabled, Color.Transparent, colors.fillMuted, colors.foregroundDisabled)
        checked ->
            RaftSwitchLook(
                RaftUiPalette.selected,
                RaftUiPalette.stickerInk,
                colors.stickerEdge,
                RaftUiPalette.stickerPaper,
                RaftUiPalette.stickerInk,
            )
        else -> RaftSwitchLook(colors.fillMuted, colors.lineStrong, Color.Transparent, colors.layerCard, colors.lineStrong)
    }

/** The 20dp box of a checkbox and a radio (`size-5`). */
private val RaftCheckSize = 20.dp


/**
 * raft-ui 0.8.0 Checkbox: a 20dp box, sticker radius, 1.5dp edge. Checked is an ink
 * block with the check knocked out ([indeterminate]: a minus), never yellow: it is a
 * value, not "you are here". Disabled greys it. Keyboard focus rings the box.
 *
 * Pass [onCheckedChange] = null when a parent row or card handles the tap (then it is
 * only the indicator). The box is exactly 20dp; on its own its touch target reaches 44dp
 * around it without taking layout room (raft-ui 0.21), so leave that room free. In a
 * [RaftCheckboxRow] or a field item the whole row toggles instead.
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
    val label = LocalRaftFieldState.current.label
    RaftRegisterFieldItemTap(if (onCheckedChange != null && enabled) ({ onCheckedChange(!checked) }) else null)
    val standalone = LocalRaftFieldItemTarget.current == null
    val toggle =
        if (onCheckedChange != null) {
            Modifier
                .then(if (standalone) Modifier.raftTouchTarget(enabled, source, { onCheckedChange(!checked) }) else Modifier)
                .triStateToggleable(
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
    val isFocused = raftIsFocused(source)
    RaftCheckboxBox(
        checked = checked || indeterminate,
        indeterminate = indeterminate,
        enabled = enabled,
        focused = interactive && enabled && isFocused,
        modifier = modifier.then(toggle).then(if (interactive && label != null) Modifier.semantics { contentDescription = label } else Modifier),
    )
}

internal fun raftToggleableState(checked: Boolean, indeterminate: Boolean): ToggleableState =
    when {
        indeterminate -> ToggleableState.Indeterminate
        checked -> ToggleableState.On
        else -> ToggleableState.Off
    }

/** raft-ui CheckboxIndicator `shape`. */
enum class RaftCheckboxShape {
    /** The checkbox: sticker radius. */
    Square,

    /**
     * A round box with the same look (an ink disc with the check knocked out when checked).
     * raft-ui keeps it for picker rows that already draw circles; Checkbox > Rules: don't
     * use it for a checkbox, round is for switches and radios.
     */
    Circle,
}

/**
 * raft-ui 0.8.0 CheckboxIndicator (the check marker): the 20dp checkbox box drawn on its
 * own, with no input, role or focus, for a row or menu item that carries the checkbox
 * semantics itself. Same looks as [RaftCheckbox]: unchecked is an empty box with a 1.5dp
 * `line-strong` edge; checked an ink block with the check knocked out ([indeterminate]:
 * a minus); disabled greys it. [shape] [RaftCheckboxShape.Circle] draws the same looks
 * in a circle: checked, a black disc with the check (not a radio's dot).
 */
@Composable
fun RaftCheckboxIndicator(
    checked: Boolean,
    modifier: Modifier = Modifier,
    shape: RaftCheckboxShape = RaftCheckboxShape.Square,
    indeterminate: Boolean = false,
    enabled: Boolean = true,
) {
    RaftCheckboxBox(
        checked = checked || indeterminate,
        indeterminate = indeterminate,
        enabled = enabled,
        focused = false,
        modifier = modifier,
        shape = shape,
    )
}

@Composable
private fun RaftCheckboxBox(
    checked: Boolean,
    indeterminate: Boolean,
    enabled: Boolean,
    focused: Boolean,
    modifier: Modifier,
    shape: RaftCheckboxShape = RaftCheckboxShape.Square,
) {
    val colors = RaftTheme.colors
    val look = raftCheckboxLook(checked, enabled, colors)
    val radius = if (shape == RaftCheckboxShape.Circle) RaftCheckSize / 2 else RaftRadius.Sticker
    val boxShape = RoundedCornerShape(radius)
    Box(
        modifier
            .size(RaftCheckSize)
            .raftFocusRing(focused, radius, colors)
            .background(look.fill, boxShape)
            .border(RaftStroke.Control, look.border, boxShape),
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
 * it only the indicator (inside an option card or a [RaftRadioRow]). On its own its touch
 * target reaches 44dp around the circle, as a [RaftCheckbox]'s does.
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
    val label = LocalRaftFieldState.current.label
    RaftRegisterFieldItemTap(if (onClick != null && enabled) onClick else null)
    val select =
        if (onClick != null) {
            Modifier
                .then(if (LocalRaftFieldItemTarget.current == null) Modifier.raftTouchTarget(enabled, source, onClick) else Modifier)
                .selectable(selected, source, indication = null, enabled = enabled, role = Role.RadioButton, onClick = onClick)
                .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)
        } else {
            Modifier
        }
    val isFocused = raftIsFocused(source)
    RaftRadioBox(selected, enabled, focused = onClick != null && enabled && isFocused, modifier = modifier.then(select))
}

@Composable
internal fun RaftRadioBox(selected: Boolean, enabled: Boolean, focused: Boolean, modifier: Modifier) {
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
    val isFocused = raftIsFocused(source)
    val focused = onCheckedChange != null && enabled && isFocused
    RaftRegisterFieldItemTap(if (onCheckedChange != null && enabled) ({ onCheckedChange(!checked) }) else null)
    val knobOffset by animateDpAsState(if (checked) 20.dp else 0.dp, tween(RaftMotion.SlideMillis, easing = RaftMotion.EaseSlide))
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
 * FieldItem): at least 44dp tall, 12dp vertical padding, the box 8dp before the text and
 * centred on the label's first line (Alignment: a control and its label), whether the
 * label wraps or a description follows. [label] is
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
        val isFocused = raftIsFocused(source)
        RaftCheckboxBox(checked, indeterminate = false, enabled = enabled, focused = enabled && isFocused, modifier = Modifier)
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
        val isFocused = raftIsFocused(source)
        RaftRadioBox(selected, enabled, focused = enabled && isFocused, modifier = Modifier)
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
    RaftFirstLineRow(
        lineHeight = raftLineHeightDp(labelStyle.lineHeight, labelStyle.fontSize, LocalDensity.current),
        gap = 8.dp,
        modifier = modifier.fillMaxWidth().heightIn(min = 44.dp).padding(vertical = 12.dp),
        control = control,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            RaftText(label, style = labelStyle, color = if (enabled) colors.foreground else colors.foregroundDisabled)
            if (description != null) {
                RaftText(description, style = typography.footnote, color = if (enabled) colors.foregroundMuted else colors.foregroundDisabled)
            }
        }
    }
}

/**
 * Where a control and the text beside it go so the control's centre is on the centre of
 * the text's first line, [lineHeight] tall (Alignment: a control and its label; raft-ui
 * FieldItem `mt-[calc((1lh-<control>)/2)]`): `(1lh - control) / 2` down for the control
 * when the line is taller, else that much down for the text, so a 30dp switch beside an
 * 18dp line doesn't hang above the row. [Pair] of (control y, text y), px.
 */
internal fun raftFirstLineOffsets(lineHeight: Int, controlHeight: Int): Pair<Int, Int> {
    val delta = (lineHeight - controlHeight) / 2f
    return if (delta >= 0f) delta.roundToInt() to 0 else 0 to (-delta).roundToInt()
}

/**
 * [control], [gap], then [text] filling the rest of the width, with the control centred on
 * the text's first line ([raftFirstLineOffsets]): not on the top of the text, and not on
 * the whole block, so it stays put when the label wraps or a description follows.
 */
@Composable
internal fun RaftFirstLineRow(
    lineHeight: Dp,
    gap: Dp,
    modifier: Modifier = Modifier,
    control: @Composable () -> Unit,
    text: @Composable () -> Unit,
) {
    Layout(contents = listOf(control, text), modifier = modifier) { (controlMeasurables, textMeasurables), constraints ->
        val gapPx = gap.roundToPx()
        val controlPlaceables = controlMeasurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val controlWidth = controlPlaceables.maxOfOrNull { it.width } ?: 0
        val controlHeight = controlPlaceables.maxOfOrNull { it.height } ?: 0
        val textConstraints =
            if (constraints.hasBoundedWidth) {
                val rest = (constraints.maxWidth - controlWidth - gapPx).coerceAtLeast(0)
                Constraints(minWidth = rest, maxWidth = rest, maxHeight = constraints.maxHeight)
            } else {
                Constraints(maxHeight = constraints.maxHeight)
            }
        val textPlaceables = textMeasurables.map { it.measure(textConstraints) }
        val textWidth = textPlaceables.maxOfOrNull { it.width } ?: 0
        val textHeight = textPlaceables.maxOfOrNull { it.height } ?: 0
        val (controlY, textY) = raftFirstLineOffsets(lineHeight.roundToPx(), controlHeight)
        val width = constraints.constrainWidth(controlWidth + gapPx + textWidth)
        val height = constraints.constrainHeight(maxOf(controlY + controlHeight, textY + textHeight))
        layout(width, height) {
            controlPlaceables.forEach { it.placeRelative(0, controlY) }
            textPlaceables.forEach { it.placeRelative(controlWidth + gapPx, textY) }
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
