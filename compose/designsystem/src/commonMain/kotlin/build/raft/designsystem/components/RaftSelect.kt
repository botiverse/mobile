package build.raft.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.dp
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
    val pressed = enabled && raftIsPressed(source)
    val focused = enabled && raftIsFocused(source)
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
 * false; [leadingIcon] is 14dp. Pressed lays `fill-pressed` over the fill; keyboard focus
 * rings it; [invalid] draws a `danger-strong` inset ring; disabled is `fill-muted`. It
 * draws 32dp but takes touches 44dp tall ([raftTouchTarget]); keep 6dp free above and below.
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
    interactionSource: MutableInteractionSource? = null,
) {
    val colors = RaftTheme.colors
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val look = raftPickerTriggerLook(hasValue, invalid, enabled, colors)
    val pressed = enabled && raftIsPressed(source)
    val focused = enabled && raftIsFocused(source)
    val shape = RoundedCornerShape(RaftRadius.Control)
    Row(
        modifier
            .heightIn(min = 32.dp)
            .raftTouchTarget(enabled, source, onClick)
            .raftFocusRing(focused, RaftRadius.Control, colors)
            .background(look.fill, shape)
            .raftPressOverlay(pressed, look.pressOverlay, RaftRadius.Control)
            .border(RaftStroke.Hairline, look.ring, shape)
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            CompositionLocalProvider(LocalRaftContentColor provides look.ink) {
                Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) { leadingIcon() }
            }
        }
        RaftText(
            text,
            style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.SemiBold),
            color = look.ink,
            maxLines = 1,
            softWrap = false,
        )
        if (showIndicator) RaftGlyphIcon(RaftGlyph.ChevronDown, size = 14.dp, color = look.indicator)
    }
}
