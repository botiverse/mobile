package build.raft.designsystem.components

import build.raft.designsystem.gesture.raftKeepsBackSwipe
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.LocalRaftStrings
import build.raft.designsystem.theme.RaftStrings
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.tokens.RaftUiThemeColors

/** raft-ui Input / InputGroup / Textarea box metrics. */
object RaftTextFieldDefaults {
    /** `h-11`: an input (and an input group) is 44dp tall. */
    val Height: Dp = 44.dp

    /** `min-h-24`: a textarea is never shorter than 96dp. */
    val TextareaMinHeight: Dp = 96.dp

    /** An icon in an input-group addon (`size-4.5`). */
    val AddonIconSize: Dp = 18.dp
}

/** Resolved colours of a field box (input, input group, textarea) in one state. */
internal data class RaftFieldBoxLook(
    val fill: Color,
    /** The 1dp `line-field` border, always drawn (Input: "Always draw the ink border"). */
    val border: Color,
    val text: Color,
    val placeholder: Color,
    /** Addon icons and text (`--ig-addon-fg` / `--ig-text-fg`). */
    val addon: Color,
)

/**
 * input.recipe.ts / input-group.tsx / textarea.tsx: `layer-card` with a 1dp `line-field`
 * border; invalid turns the border `danger-strong` and changes nothing else; disabled is
 * `fill-muted`, a `line-hairline` border and `foreground-disabled` type. Disabled wins
 * over invalid: nobody can fix a disabled field. [readOnly] (page hand-off item 5, the
 * read-only field) is `fill-muted` with a `line-hairline` border and `foreground-muted`
 * type: readable, plainly not editable.
 */
internal fun raftFieldBoxLook(enabled: Boolean, invalid: Boolean, colors: RaftUiThemeColors, readOnly: Boolean = false): RaftFieldBoxLook =
    if (enabled && readOnly) {
        RaftFieldBoxLook(
            fill = colors.fillMuted,
            border = colors.lineHairline,
            text = colors.foregroundMuted,
            placeholder = colors.foregroundHint,
            addon = colors.foregroundHint,
        )
    } else if (!enabled) {
        RaftFieldBoxLook(
            fill = colors.fillMuted,
            border = colors.lineHairline,
            text = colors.foregroundDisabled,
            placeholder = colors.foregroundDisabled,
            addon = colors.foregroundDisabled,
        )
    } else {
        RaftFieldBoxLook(
            fill = colors.layerCard,
            border = if (invalid) colors.dangerStrong else colors.lineStrong,
            text = colors.foreground,
            placeholder = colors.foregroundHint,
            addon = colors.foregroundHint,
        )
    }

/**
 * raft-ui 0.8.0 Input, and InputGroup when it has addons: one line of text in a 44dp box,
 * a 1dp `line-field` border on `layer-card`, control radius, 12dp side padding and
 * the field type (`text-field`, 16/24 regular).
 *
 * Controlled: show [value] and update it from [onValueChange]. The editor itself edits a
 * [TextFieldState] kept in step with [value] ([rememberRaftTextFieldState]), so the IME is never
 * handed a stale value (iOS marked text survives, task #69).
 *
 * - [placeholder]: an example value ("Wug") in `foreground-hint`, never the label. Put
 *   the field in a [RaftField] for its label and help text.
 * - Focus: the focus ring (2dp gap, 2dp `line-strong`) outside the border, on any focus,
 *   a tap included (`focus:shadow-focus`, not `focus-visible`). The border stays.
 * - [invalid]: a `danger-strong` border (`data-invalid`), no ring of its own. Inside a
 *   [RaftField] with an error it defaults to true.
 * - [enabled] = false: `fill-muted`, `line-hairline` border, `foreground-disabled` text
 *   and placeholder. Disabled wins over invalid.
 * - [readOnly]: `fill-muted`, `line-hairline` border, `foreground-muted` text; still
 *   focusable and selectable.
 * - Inside a [RaftField], screen readers read it by the field's label, and its error.
 *
 * InputGroup: the addons sit inside the border and the focus ring goes round the whole
 * group. [leading] / [trailing] take an 18dp icon in `foreground-hint`
 * ([LocalRaftContentColor]), 12dp from the edge and 8dp from the text. [prefix] /
 * [suffix] take fixed text (a `/`, a domain, a unit) in Space Mono `text-mono-code`,
 * `foreground-hint`, 2dp from the text.
 *
 * [trailingAction] is InputGroup's button addon at the very end: a [RaftTextFieldAction]
 * (show the password, copy) or [RaftTextFieldClearAction] (show it once there is text). It
 * keeps its own 28dp size, 6dp from the border (its icon lines up with a [trailing] icon's),
 * after any [suffix] or [trailing] icon.
 */
@Composable
fun RaftTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = LocalRaftFieldState.current.enabled,
    readOnly: Boolean = false,
    invalid: Boolean = LocalRaftFieldState.current.invalid,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    prefix: String? = null,
    suffix: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    interactionSource: MutableInteractionSource? = null,
    trailingAction: (@Composable () -> Unit)? = null,
) {
    RaftTextField(
        state = rememberRaftTextFieldState(value, onValueChange, traceName = "RaftTextField"),
        modifier = modifier,
        placeholder = placeholder,
        enabled = enabled,
        readOnly = readOnly,
        invalid = invalid,
        leading = leading,
        trailing = trailing,
        prefix = prefix,
        suffix = suffix,
        keyboardOptions = keyboardOptions,
        onKeyboardAction = keyboardActions.toRaftKeyboardActionHandler(keyboardOptions.imeAction),
        outputTransformation = visualTransformation.toRaftOutputTransformation(),
        interactionSource = interactionSource,
        trailingAction = trailingAction,
    )
}

/** [RaftTextField] on a [TextFieldValue], for callers that own the selection and composition. */
@Composable
fun RaftTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = LocalRaftFieldState.current.enabled,
    readOnly: Boolean = false,
    invalid: Boolean = LocalRaftFieldState.current.invalid,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    prefix: String? = null,
    suffix: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    interactionSource: MutableInteractionSource? = null,
    trailingAction: (@Composable () -> Unit)? = null,
) {
    RaftTextField(
        state = rememberRaftTextFieldState(value, onValueChange, traceName = "RaftTextField"),
        modifier = modifier,
        placeholder = placeholder,
        enabled = enabled,
        readOnly = readOnly,
        invalid = invalid,
        leading = leading,
        trailing = trailing,
        prefix = prefix,
        suffix = suffix,
        keyboardOptions = keyboardOptions,
        onKeyboardAction = keyboardActions.toRaftKeyboardActionHandler(keyboardOptions.imeAction),
        outputTransformation = visualTransformation.toRaftOutputTransformation(),
        interactionSource = interactionSource,
        trailingAction = trailingAction,
    )
}

/**
 * [RaftTextField] on a [TextFieldState] the caller holds: the state-based editor without a
 * controlled-value bridge. [outputTransformation] changes what is shown, not the state (a
 * password mask).
 */
@Composable
fun RaftTextField(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = LocalRaftFieldState.current.enabled,
    readOnly: Boolean = false,
    invalid: Boolean = LocalRaftFieldState.current.invalid,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    prefix: String? = null,
    suffix: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onKeyboardAction: KeyboardActionHandler? = null,
    outputTransformation: OutputTransformation? = null,
    interactionSource: MutableInteractionSource? = null,
    trailingAction: (@Composable () -> Unit)? = null,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val look = raftFieldBoxLook(enabled, invalid, RaftTheme.colors, readOnly)
    val isFocused = raftIsFocused(source)
    val focused = enabled && isFocused
    val field = LocalRaftFieldState.current
    BasicTextField(
        state = state,
        // Focused: cursor and selection drags (and single-line scrolling) keep the drag.
        modifier = modifier.raftKeepsBackSwipe(focused).raftFieldSemantics(field.label, invalid, enabled, field.error),
        enabled = enabled,
        readOnly = readOnly,
        inputTransformation = rememberRaftTextInputTraceTransformation("RaftTextField"),
        textStyle = raftFieldTextStyle(look.text),
        keyboardOptions = keyboardOptions,
        onKeyboardAction = onKeyboardAction,
        lineLimits = TextFieldLineLimits.SingleLine,
        interactionSource = source,
        cursorBrush = SolidColor(RaftTheme.colors.foreground),
        outputTransformation = outputTransformation,
        decorator = { inner ->
            RaftInputBox(look, focused, state.text.isEmpty(), placeholder, leading, trailing, prefix, suffix, trailingAction, inner)
        },
    )
}

/**
 * raft-ui 0.8.0 Textarea: several lines of text in the input's box, at least [minHeight]
 * (96dp) tall, 10dp / 12dp padding. It grows with its content up to [maxHeight], then
 * scrolls inside. States are the input's ([RaftTextField]), focus ring included.
 *
 * [limit] adds the [RaftTextareaCounter] under the box at its end (TextareaGroup, 6dp
 * gap). Typing is never cut off at the limit, so the counter can show the text is over.
 *
 * [modifier] goes on the whole group (box and counter); [inputModifier] on the text field
 * itself, for what must reach the editor: a `focusRequester`, `onFocusChanged`, IME and
 * keyboard handling.
 */
@Composable
fun RaftTextarea(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = LocalRaftFieldState.current.enabled,
    readOnly: Boolean = false,
    invalid: Boolean = LocalRaftFieldState.current.invalid,
    limit: Int? = null,
    minHeight: Dp = RaftTextFieldDefaults.TextareaMinHeight,
    maxHeight: Dp = Dp.Infinity,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    interactionSource: MutableInteractionSource? = null,
    inputModifier: Modifier = Modifier,
) {
    RaftTextarea(
        state = rememberRaftTextFieldState(value, onValueChange, traceName = "RaftTextarea"),
        modifier = modifier,
        placeholder = placeholder,
        enabled = enabled,
        readOnly = readOnly,
        invalid = invalid,
        limit = limit,
        minHeight = minHeight,
        maxHeight = maxHeight,
        keyboardOptions = keyboardOptions,
        onKeyboardAction = keyboardActions.toRaftKeyboardActionHandler(keyboardOptions.imeAction),
        interactionSource = interactionSource,
        inputModifier = inputModifier,
    )
}

/**
 * [RaftTextarea] on a [TextFieldValue], for callers that own the selection and composition
 * (a mention or emoji picker inserting at the caret). The counter counts the text.
 */
@Composable
fun RaftTextarea(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = LocalRaftFieldState.current.enabled,
    readOnly: Boolean = false,
    invalid: Boolean = LocalRaftFieldState.current.invalid,
    limit: Int? = null,
    minHeight: Dp = RaftTextFieldDefaults.TextareaMinHeight,
    maxHeight: Dp = Dp.Infinity,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    interactionSource: MutableInteractionSource? = null,
    inputModifier: Modifier = Modifier,
) {
    RaftTextarea(
        state = rememberRaftTextFieldState(value, onValueChange, traceName = "RaftTextarea"),
        modifier = modifier,
        placeholder = placeholder,
        enabled = enabled,
        readOnly = readOnly,
        invalid = invalid,
        limit = limit,
        minHeight = minHeight,
        maxHeight = maxHeight,
        keyboardOptions = keyboardOptions,
        onKeyboardAction = keyboardActions.toRaftKeyboardActionHandler(keyboardOptions.imeAction),
        interactionSource = interactionSource,
        inputModifier = inputModifier,
    )
}

/** [RaftTextarea] on a [TextFieldState] the caller holds. The counter counts the state's text. */
@Composable
fun RaftTextarea(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = LocalRaftFieldState.current.enabled,
    readOnly: Boolean = false,
    invalid: Boolean = LocalRaftFieldState.current.invalid,
    limit: Int? = null,
    minHeight: Dp = RaftTextFieldDefaults.TextareaMinHeight,
    maxHeight: Dp = Dp.Infinity,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onKeyboardAction: KeyboardActionHandler? = null,
    interactionSource: MutableInteractionSource? = null,
    inputModifier: Modifier = Modifier,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val look = raftFieldBoxLook(enabled, invalid, RaftTheme.colors, readOnly)
    val isFocused = raftIsFocused(source)
    val focused = enabled && isFocused
    val field = LocalRaftFieldState.current
    RaftTextareaGroup(modifier, count = state.text.length, limit = limit, invalid = invalid && enabled) {
        BasicTextField(
            state = state,
            modifier =
                inputModifier
                    .fillMaxWidth()
                    .heightIn(min = minHeight, max = maxHeight)
                    // Focused: cursor and selection drags keep the drag from the host back swipe.
                    .raftKeepsBackSwipe(focused)
                    .raftFieldSemantics(field.label, invalid, enabled, field.error),
            enabled = enabled,
            readOnly = readOnly,
            inputTransformation = rememberRaftTextInputTraceTransformation("RaftTextarea"),
            textStyle = raftFieldTextStyle(look.text),
            keyboardOptions = keyboardOptions,
            onKeyboardAction = onKeyboardAction,
            lineLimits = TextFieldLineLimits.MultiLine(),
            interactionSource = source,
            cursorBrush = SolidColor(RaftTheme.colors.foreground),
            decorator = { inner -> RaftTextareaBox(look, focused, minHeight, state.text.isEmpty(), placeholder, inner) },
        )
    }
}

/** TextareaGroup: the textarea, then its counter at the end 6dp under it when there is a [limit]. */
@Composable
private fun RaftTextareaGroup(
    modifier: Modifier,
    count: Int,
    limit: Int?,
    invalid: Boolean,
    textarea: @Composable () -> Unit,
) {
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        textarea()
        if (limit != null) {
            RaftTextareaCounter(count = count, limit = limit, invalid = invalid)
        }
    }
}

/** The textarea's box round the editor: field chrome, `py-2.5 px-3`, the placeholder. */
@Composable
private fun RaftTextareaBox(
    look: RaftFieldBoxLook,
    focused: Boolean,
    minHeight: Dp,
    showPlaceholder: Boolean,
    placeholder: String?,
    inner: @Composable () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .raftFieldChrome(look, focused)
            // CSS `py-2.5 px-3` sit inside the 1dp border.
            .padding(horizontal = 12.dp + RaftStroke.Field, vertical = 10.dp + RaftStroke.Field),
    ) {
        if (showPlaceholder && placeholder != null) {
            RaftText(placeholder, style = raftFieldTextStyle(look.placeholder))
        }
        inner()
    }
}

/**
 * raft-ui `TextareaCounter`: "[count]/[limit]" in Space Mono `text-mono-meta`, tabular,
 * `foreground-hint`; bold `danger-strong` once over the limit or when the field is [invalid].
 */
@Composable
fun RaftTextareaCounter(count: Int, limit: Int, modifier: Modifier = Modifier, invalid: Boolean = false) {
    val alarm = raftCounterAlarm(count, limit, invalid)
    val colors = RaftTheme.colors
    RaftText(
        text = "$count/$limit",
        modifier = modifier,
        style =
            RaftTheme.typography.monoMeta.copy(
                fontWeight = if (alarm) FontWeight.Bold else FontWeight.Normal,
                fontFeatureSettings = "tnum",
            ),
        color = if (alarm) colors.dangerStrong else colors.foregroundHint,
        maxLines = 1,
    )
}

/** The counter turns red past the limit (not at it) or inside an invalid field. */
internal fun raftCounterAlarm(count: Int, limit: Int, invalid: Boolean): Boolean = count > limit || invalid

/** `text-field font-field`: raft-ui `--field-*`, 16/24 regular ([RaftTypography.field]). */
@Composable
private fun raftFieldTextStyle(color: Color): TextStyle = RaftTheme.typography.field.copy(color = color)

/**
 * The field box: focus ring outside, fill, and the 1dp `line-field` border inside. A tap
 * focuses a text field but draws no ring (the caret marks it); the ring shows after keyboard
 * navigation only (raft-ui 0.9.6 input modality, raft-design States > Focus).
 */
@Composable
private fun Modifier.raftFieldChrome(look: RaftFieldBoxLook, focused: Boolean): Modifier {
    val shape = RoundedCornerShape(RaftRadius.Control)
    val keyboardMode = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val focusedPreview = LocalRaftFocusedPreview.current
    val ring = focused && (keyboardMode || focusedPreview)
    return this
        .raftFocusRing(ring, RaftRadius.Control, RaftTheme.colors)
        .background(look.fill, shape)
        .border(RaftStroke.Field, look.border, shape)
}

@Composable
private fun RaftInputBox(
    look: RaftFieldBoxLook,
    focused: Boolean,
    showPlaceholder: Boolean,
    placeholder: String?,
    leading: (@Composable () -> Unit)?,
    trailing: (@Composable () -> Unit)?,
    prefix: String?,
    suffix: String?,
    trailingAction: (@Composable () -> Unit)?,
    inner: @Composable () -> Unit,
) {
    // input-group.tsx: an addon is `px-3`, trimmed to 8dp (icon) / 2dp (text) on the
    // control's side, and the control drops its own padding on that side. All of it sits
    // inside the 1dp border. A button addon at the end keeps 6dp to the border, so its 16dp
    // icon (centred in 28dp) lines up with an 18dp icon addon's 12dp edge.
    val edge = 12.dp + RaftStroke.Field
    val actionEdge = 6.dp + RaftStroke.Field
    val hasAction = trailingAction != null
    // Before a button addon, the addons on its side end 4dp from it.
    val endEdge = if (hasAction) 4.dp else edge
    val hasStart = leading != null || prefix != null
    val hasEnd = trailing != null || suffix != null || hasAction
    Row(
        Modifier
            .fillMaxWidth()
            // A minimum, so text at a large font scale grows the box instead of clipping.
            .heightIn(min = RaftTextFieldDefaults.Height)
            .raftFieldChrome(look, focused),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalRaftContentColor provides look.addon) {
            if (leading != null) {
                Box(
                    Modifier.padding(start = edge, end = if (prefix != null) 0.dp else 8.dp).size(RaftTextFieldDefaults.AddonIconSize),
                    contentAlignment = Alignment.Center,
                ) { leading() }
            }
            if (prefix != null) {
                // raft-ui 0.13: a text prefix takes at most half the field, then ellipsizes
                // (the input's equal weight keeps the other half).
                AddonText(
                    prefix,
                    Modifier.weight(1f, fill = false).padding(start = if (leading != null) 8.dp else edge, end = 2.dp),
                    look.addon,
                )
            }
        }
        Box(
            Modifier
                .weight(1f)
                .padding(start = if (hasStart) 0.dp else edge, end = if (hasEnd) 0.dp else edge),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (showPlaceholder && placeholder != null) {
                RaftText(
                    placeholder,
                    style = raftFieldTextStyle(look.placeholder),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            inner()
        }
        CompositionLocalProvider(LocalRaftContentColor provides look.addon) {
            if (suffix != null) {
                AddonText(suffix, Modifier.padding(start = 2.dp, end = if (trailing != null) 8.dp else endEdge), look.addon)
            }
            if (trailing != null) {
                Box(
                    Modifier.padding(start = if (suffix != null) 0.dp else 8.dp, end = endEdge).size(RaftTextFieldDefaults.AddonIconSize),
                    contentAlignment = Alignment.Center,
                ) { trailing() }
            }
        }
        if (trailingAction != null) {
            Box(
                Modifier.padding(start = if (suffix != null || trailing != null) 0.dp else 4.dp, end = actionEdge),
                contentAlignment = Alignment.Center,
            ) { trailingAction() }
        }
    }
}

/**
 * raft-ui InputGroup button addon: a 28dp square (control radius) inside a field's box, for
 * the field's own action, at the end of a [RaftTextField] (`trailingAction`). [label] names
 * it in Title Case ("Show Password", "Copy Invite Link"); [icon] is drawn at 16dp in its ink.
 *
 * - Rest: `foreground-muted` (combobox.tsx `clear`); pressed or hovered: `fill-pressed`
 *   under `foreground`. Keyboard focus: the focus ring.
 * - Disabled (by default when its [RaftField] is): `foreground-disabled`, no press.
 * - Its hit area is its 28dp: it is not widened, as it would take taps from the text.
 */
@Composable
fun RaftTextFieldAction(
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = LocalRaftFieldState.current.enabled,
    interactionSource: MutableInteractionSource? = null,
    icon: @Composable () -> Unit,
) {
    val colors = RaftTheme.colors
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val isPressed = raftIsPressed(source)
    val isHovered = raftIsHovered(source)
    val active = enabled && (isPressed || isHovered)
    val isFocused = raftIsFocused(source)
    val focused = enabled && isFocused
    val ink =
        when {
            !enabled -> colors.foregroundDisabled
            active -> colors.foreground
            else -> colors.foregroundMuted
        }
    Box(
        modifier
            .size(28.dp)
            .raftFocusRing(focused, RaftRadius.Control, colors)
            .raftPressOverlay(active, raftPressOverlayColor(RaftPressSurface.Paper, colors), RaftRadius.Control)
            // 28dp drawn, 44dp to the touch (raft-design Spacing: draw small, hit 44).
            .raftTouchTarget(enabled, source, onClick)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalRaftContentColor provides ink) {
            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { icon() }
        }
    }
}

/**
 * The clear (×) [RaftTextFieldAction] of a search or filter field: show it once there is
 * text, and empty the field in [onClear]. Named [label], by default [RaftStrings.clear]
 * from [LocalRaftStrings] (raft-ui asks for "Clear Search" on a search field).
 */
@Composable
fun RaftTextFieldClearAction(
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = LocalRaftStrings.current.clear,
    enabled: Boolean = LocalRaftFieldState.current.enabled,
) {
    RaftTextFieldAction(onClick = onClear, label = label, modifier = modifier, enabled = enabled) {
        RaftGlyphIcon(RaftGlyph.X, size = 16.dp)
    }
}

@Composable
private fun AddonText(text: String, modifier: Modifier, color: Color) {
    RaftText(
        text,
        modifier,
        style = RaftTheme.typography.monoCode,
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
}
