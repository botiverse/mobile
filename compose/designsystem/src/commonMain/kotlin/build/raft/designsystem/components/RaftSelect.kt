package build.raft.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.tokens.RaftUiThemeColors

/** Colours of a select trigger in one state. */
@Immutable
internal data class RaftSelectTriggerLook(
    val fill: Color,
    val border: Color,
    val ink: Color,
    val chevron: Color,
    val pressOverlay: Color,
)

/**
 * select.tsx trigger (an outline Button restyled as a field): `layer-card`, 1dp
 * `line-strong` edge, the value in `foreground` or the placeholder in `foreground-hint`,
 * a `foreground-hint` chevron. Invalid turns the edge `danger-strong`. Disabled takes the
 * button's disabled look (`fill-muted`, `line-hairline`, `foreground-disabled`).
 */
internal fun raftSelectTriggerLook(
    enabled: Boolean,
    invalid: Boolean,
    hasValue: Boolean,
    colors: RaftUiThemeColors,
): RaftSelectTriggerLook =
    if (!enabled) {
        RaftSelectTriggerLook(colors.fillMuted, colors.lineHairline, colors.foregroundDisabled, colors.foregroundDisabled, Color.Transparent)
    } else {
        RaftSelectTriggerLook(
            fill = colors.layerCard,
            border = if (invalid) colors.dangerStrong else colors.lineStrong,
            ink = if (hasValue) colors.foreground else colors.foregroundHint,
            chevron = colors.foregroundHint,
            pressOverlay = raftPressOverlayColor(RaftPressSurface.Paper, colors),
        )
    }

/**
 * raft-ui 0.8.0 Select trigger: the field that opens a Select's list. At least 44dp,
 * control radius, 1dp `line-field` edge on `layer-card`, 14dp before the text and 12dp
 * after the 16dp chevron (inside the edge), 8dp gap. The [value] is `text-subhead`
 * semibold `foreground`; with no value the [placeholder] ("Select a role") is medium
 * `foreground-hint`. Inside a [RaftField] the text takes the field type (16/24) so it
 * lines up with the inputs (`font-field` 400 for value and placeholder; the list's options
 * take the same type). Pressed lays `fill-pressed` over it; keyboard focus rings it;
 * [invalid] turns the edge `danger-strong`; disabled greys it. [leadingIcon] is 16dp.
 *
 * Screen readers hear a drop-down list named by the field's label (or [label]) with the
 * value, or the placeholder when empty, and, when [expanded] is given, whether it is
 * expanded (the expand / collapse actions; [onCollapse] closes it).
 */
@Composable
fun RaftSelectTrigger(
    value: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = LocalRaftFieldState.current.enabled,
    invalid: Boolean = LocalRaftFieldState.current.invalid,
    label: String? = LocalRaftFieldState.current.label,
    expanded: Boolean? = null,
    onCollapse: (() -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    val colors = RaftTheme.colors
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val look = raftSelectTriggerLook(enabled, invalid, value != null, colors)
    val isPressed = raftIsPressed(source)
    val pressed = enabled && isPressed
    val isFocused = raftIsFocused(source)
    val focused = enabled && isFocused
    val shape = RoundedCornerShape(RaftRadius.Control)
    val field = LocalRaftFieldState.current
    val base = if (field.inField) RaftTheme.typography.field else RaftTheme.typography.subhead
    val shown = value ?: placeholder
    Row(
        modifier
            .fillMaxWidth()
            // A minimum, so a large font scale grows the trigger instead of clipping its text.
            .heightIn(min = 44.dp)
            .raftFocusRing(focused, RaftRadius.Control, colors)
            .background(look.fill, shape)
            .raftPressOverlay(pressed, look.pressOverlay, RaftRadius.Control)
            .border(RaftStroke.Field, look.border, shape)
            .clickable(source, indication = null, enabled = enabled, role = Role.DropdownList, onClick = onClick)
            .semantics {
                if (label != null) contentDescription = if (shown.isEmpty()) label else "$label, $shown"
                when (expanded) {
                    true -> collapse { onCollapse?.invoke(); onCollapse != null }
                    false -> expand { onClick(); true }
                    null -> {}
                }
                if (invalid && enabled) error(field.error ?: RaftInvalidValueMessage)
            }
            .padding(start = 14.dp + RaftStroke.Field, end = 12.dp + RaftStroke.Field, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            CompositionLocalProvider(LocalRaftContentColor provides look.ink) {
                Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { leadingIcon() }
            }
        }
        RaftText(
            shown,
            Modifier.weight(1f),
            // In a field the value and placeholder keep `font-field` (400), like an input's.
            style = if (field.inField) base else base.copy(fontWeight = if (value != null) FontWeight.SemiBold else FontWeight.Medium),
            color = look.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        RaftGlyphIcon(RaftGlyph.ChevronDown, size = 16.dp, color = look.chevron)
    }
}

/**
 * raft-ui 0.8.0 Select: pick one value from a list. A [RaftSelectTrigger] that opens a
 * [RaftDropdownMenu] exactly as wide as the trigger (at least 220dp) listing [options] by
 * [optionLabel]; the current [value] is bold with a check, never a fill. Picking calls
 * [onValueChange] and closes the list; from the keyboard, focus goes back to the trigger.
 * [optionEnabled] = false greys an option out and keeps it from being picked (raft-ui
 * `SelectItem disabled`).
 */
@Composable
fun <T> RaftSelect(
    value: T?,
    options: List<T>,
    onValueChange: (T) -> Unit,
    optionLabel: (T) -> String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = LocalRaftFieldState.current.enabled,
    invalid: Boolean = LocalRaftFieldState.current.invalid,
    label: String? = LocalRaftFieldState.current.label,
    optionEnabled: (T) -> Boolean = { true },
) {
    var expanded by remember { mutableStateOf(false) }
    var triggerWidth by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val trigger = remember { FocusRequester() }
    val inField = LocalRaftFieldState.current.inField
    Box(modifier) {
        RaftSelectTrigger(
            value = value?.let(optionLabel),
            onClick = { expanded = true },
            modifier = Modifier.focusRequester(trigger).onSizeChanged { triggerWidth = it.width },
            placeholder = placeholder,
            enabled = enabled,
            invalid = invalid,
            label = label,
            expanded = expanded,
            onCollapse = { expanded = false },
        )
        RaftDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            width = maxOf(220.dp, with(density) { triggerWidth.toDp() }),
            offset = 4.dp,
            returnFocusTo = trigger,
        ) {
            CompositionLocalProvider(LocalRaftMenuFieldType provides inField) {
                options.forEach { option ->
                    RaftMenuItem(
                        text = optionLabel(option),
                        enabled = optionEnabled(option),
                        checked = option == value,
                        onClick = {
                            expanded = false
                            onValueChange(option)
                        },
                    )
                }
            }
        }
    }
}

/** Colours of a picker trigger button in one state. */
@Immutable
internal data class RaftPickerTriggerLook(
    val fill: Color,
    /** The 1dp ring inside the edge (`inset-ring`); transparent for none. */
    val ring: Color,
    val ink: Color,
    val indicator: Color,
    val pressOverlay: Color,
)

/**
 * picker-trigger-button.tsx: at rest a ghost with a 1dp inset `line-seam` ring and
 * `foreground-muted` type; an applied filter ([hasValue]) is `info-soft` with
 * `foreground` type and no ring (States: content selection and applied filters have no
 * border); [invalid] rings it in `danger-strong`; disabled is `fill-muted` with
 * `foreground-disabled` type and no ring.
 */
internal fun raftPickerTriggerLook(
    hasValue: Boolean,
    invalid: Boolean,
    enabled: Boolean,
    colors: RaftUiThemeColors,
): RaftPickerTriggerLook =
    when {
        !enabled -> RaftPickerTriggerLook(colors.fillMuted, Color.Transparent, colors.foregroundDisabled, colors.foregroundDisabled, Color.Transparent)
        else ->
            RaftPickerTriggerLook(
                fill = if (hasValue) colors.infoSoft else Color.Transparent,
                ring =
                    when {
                        invalid -> colors.dangerStrong
                        hasValue -> Color.Transparent
                        else -> colors.lineSeam
                    },
                ink = if (hasValue) colors.foreground else colors.foregroundMuted,
                indicator = colors.foregroundHint,
                pressOverlay = colors.fillPressed,
            )
    }

/**
 * raft-ui 0.8.0 PickerTriggerButton: the small trigger of a filter or picker in a toolbar
 * ("Status", "Assignee: Wug"). 32dp, control radius, 10dp padding, 6dp gap, `text-footnote`
 * semibold. At rest a 1dp `line-seam` inset ring and `foreground-muted`; with
 * [hasValue] (the filter is applied) an `info-soft` fill with `foreground` text and no
 * ring or border. A 14dp chevron in `foreground-hint` ends it unless [showIndicator] is
 * false; [leadingIcon] is 14dp. Pressed or hovered by a mouse / trackpad, it lays
 * `fill-pressed` over the fill; keyboard focus
 * rings it; [invalid] draws a `danger-strong` inset ring; disabled is `fill-muted`. It
 * draws 32dp but takes touches 44dp tall ([raftTouchTarget]); keep 6dp free above and below.
 * Given less width than its label needs, the label ends in an ellipsis (web `truncate`).
 *
 * [size] [RaftPickerTriggerSize.Chip] is the same trigger at the editable chip's metrics
 * ([RaftTaskStatusBadge] `Md`: 22dp, chip radius, 7dp ends, 6dp gap, 12/16 semibold, 12dp
 * chevron and icon), for a value picker that sits beside a status badge (web
 * InlineBadgeEditor: task Status and Assignee are both 22px chips).
 */
@Composable
fun RaftPickerTriggerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hasValue: Boolean = false,
    invalid: Boolean = false,
    enabled: Boolean = true,
    showIndicator: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    size: RaftPickerTriggerSize = RaftPickerTriggerSize.Default,
    interactionSource: MutableInteractionSource? = null,
) {
    val colors = RaftTheme.colors
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val look = raftPickerTriggerLook(hasValue, invalid, enabled, colors)
    // `hover:bg-fill-medium` (`overlay-medium` over an applied filter's `info-soft`), and the
    // same on press: a button, not a field.
    val overlay = rememberRaftStateOverlay(source, RaftHoverTier.Medium, enabled = enabled)
    val isFocused = raftIsFocused(source)
    val focused = enabled && isFocused
    val radius = size.radius
    val shape = RoundedCornerShape(radius)
    Row(
        modifier
            // At least the size's height: large text grows the chip instead of clipping its label.
            .heightIn(min = size.height)
            .raftTouchTarget(enabled, source, onClick)
            .raftFocusRing(focused, radius, colors)
            .background(look.fill, shape)
            .raftStateOverlay(overlay, shape)
            .border(RaftStroke.Hairline, look.ring, shape)
            .raftPointerHover(source)
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = size.horizontalPadding, vertical = size.verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RaftPickerTriggerContent(
            leading =
                leadingIcon?.let { icon ->
                    {
                        CompositionLocalProvider(LocalRaftContentColor provides look.ink) {
                            Box(Modifier.size(size.iconSize), contentAlignment = Alignment.Center) { icon() }
                        }
                    }
                },
            label = {
                RaftText(
                    text,
                    style =
                        if (size == RaftPickerTriggerSize.Chip) {
                            RaftTheme.typography.caption.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
                        } else {
                            RaftTheme.typography.footnote.copy(fontWeight = FontWeight.SemiBold)
                        },
                    color = look.ink,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailing =
                if (showIndicator) {
                    { RaftGlyphIcon(RaftGlyph.ChevronDown, size = size.iconSize, color = look.indicator) }
                } else {
                    null
                },
        )
    }
}

/**
 * A picker trigger's icon, label and chevron, 6dp apart. The icon and chevron are measured
 * first and always kept; the label takes the rest of a bounded width and ends in an ellipsis
 * when it doesn't fit (web `truncate` under `max-w-full`). Given an unbounded width (a
 * horizontally scrolling filter row, a `LazyRow` item) the label is measured whole. A
 * `Modifier.weight` label would get no width at all there, leaving an icon-only chip.
 */
@Composable
private fun RaftPickerTriggerContent(
    leading: (@Composable () -> Unit)?,
    label: @Composable () -> Unit,
    trailing: (@Composable () -> Unit)?,
) {
    Layout(
        content = {
            Box(Modifier.layoutId(PickerTriggerLabelId)) { label() }
            if (leading != null) Box(Modifier.layoutId(PickerTriggerLeadingId)) { leading() }
            if (trailing != null) Box(Modifier.layoutId(PickerTriggerTrailingId)) { trailing() }
        },
    ) { measurables, constraints ->
        val gap = 6.dp.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val leadingPlaceable = measurables.firstOrNull { it.layoutId == PickerTriggerLeadingId }?.measure(loose)
        val trailingPlaceable = measurables.firstOrNull { it.layoutId == PickerTriggerTrailingId }?.measure(loose)
        val fixed =
            (leadingPlaceable?.let { it.width + gap } ?: 0) +
                (trailingPlaceable?.let { it.width + gap } ?: 0)
        val labelMax =
            if (constraints.hasBoundedWidth) (constraints.maxWidth - fixed).coerceAtLeast(0) else Constraints.Infinity
        val labelPlaceable = measurables.first { it.layoutId == PickerTriggerLabelId }.measure(loose.copy(maxWidth = labelMax))
        val width = (fixed + labelPlaceable.width).coerceIn(constraints.minWidth, constraints.maxWidth)
        val height =
            maxOf(labelPlaceable.height, leadingPlaceable?.height ?: 0, trailingPlaceable?.height ?: 0)
                .coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            var x = 0
            leadingPlaceable?.let {
                it.placeRelative(x, (height - it.height) / 2)
                x += it.width + gap
            }
            labelPlaceable.placeRelative(x, (height - labelPlaceable.height) / 2)
            x += labelPlaceable.width
            trailingPlaceable?.placeRelative(x + gap, (height - trailingPlaceable.height) / 2)
        }
    }
}

private const val PickerTriggerLabelId = "picker-trigger-label"
private const val PickerTriggerLeadingId = "picker-trigger-leading"
private const val PickerTriggerTrailingId = "picker-trigger-trailing"

/**
 * [RaftPickerTriggerButton]'s metrics: [Default] is raft-ui PickerTriggerButton (32dp
 * minimum, control radius, 10dp ends, footnote, 14dp marks); [Chip] the editable chip's
 * (22dp, chip radius, 7dp ends, 12/16, 12dp marks), the same box as [RaftTaskStatusBadge]
 * [RaftTaskStatusBadgeSize.Md] so the two read as one kind of control in a row.
 */
enum class RaftPickerTriggerSize(
    internal val height: Dp,
    internal val radius: Dp,
    internal val horizontalPadding: Dp,
    internal val verticalPadding: Dp,
    internal val iconSize: Dp,
) {
    Default(height = 32.dp, radius = RaftRadius.Control, horizontalPadding = 10.dp, verticalPadding = 4.dp, iconSize = 14.dp),
    Chip(
        height = RaftTaskStatusBadgeSize.Md.height,
        radius = RaftRadius.Chip,
        horizontalPadding = RaftTaskStatusBadgeSize.Md.horizontalPadding,
        verticalPadding = 0.dp,
        iconSize = 12.dp,
    ),
}

/**
 * The filter in effect beside a filter's [RaftPickerTriggerButton], with an × that clears
 * it (slock ThreadsInbox `ActiveFilterChip`, raft-design mockup s1-option-a). raft-ui has
 * no standalone chip (its chips are Combobox parts), so this is the applied filter's look
 * (PickerTriggerButton with a value: `info-soft`, no ring, 32dp, control radius,
 * `foreground` footnote semibold) with the combobox chip's remove button at 24dp: 10dp
 * in at the start, 4dp at the end, 6dp gaps, a 14dp [leadingIcon]. The label truncates
 * before the ×. The × is [removeLabel] to assistive tech and takes touches 44dp square.
 */
@Composable
fun RaftFilterChip(
    text: String,
    onRemove: () -> Unit,
    removeLabel: String,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val colors = RaftTheme.colors
    Row(
        modifier
            .heightIn(min = 32.dp)
            .background(colors.infoSoft, RoundedCornerShape(RaftRadius.Control))
            .padding(start = 10.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            CompositionLocalProvider(LocalRaftContentColor provides colors.foreground) {
                Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) { leadingIcon() }
            }
        }
        RaftText(
            text,
            Modifier.weight(1f, fill = false),
            style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.SemiBold),
            color = colors.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        RaftComboboxChipRemove(removeLabel, onRemove, size = 24.dp, widenTouchTarget = true)
    }
}
