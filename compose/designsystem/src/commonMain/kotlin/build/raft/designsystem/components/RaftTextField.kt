package build.raft.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
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
    /** The 1.5dp border, always drawn (Input: "Always draw the ink border"). */
    val border: Color,
    val text: Color,
    val placeholder: Color,
    /** Addon icons and text (`--ig-addon-fg` / `--ig-text-fg`). */
    val addon: Color,
)

/**
 * input.recipe.ts / input-group.tsx / textarea.tsx: `layer-card` with a 1.5dp `line-strong`
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
 * a 1.5dp `line-strong` border on `layer-card`, control radius, 12dp side padding and
 * the field type (`text-field`, 16/24 regular).
 *
 * Controlled: show [value] and update it from [onValueChange].
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
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val look = raftFieldBoxLook(enabled, invalid, RaftTheme.colors, readOnly)
    val focused = enabled && raftIsFocused(source)
    val field = LocalRaftFieldState.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.raftFieldSemantics(field.label, invalid, enabled, field.error),
        enabled = enabled,
        readOnly = readOnly,
        textStyle = raftFieldTextStyle(look.text),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = true,
        visualTransformation = visualTransformation,
        interactionSource = source,
        cursorBrush = SolidColor(RaftTheme.colors.foreground),
        decorationBox = { inner ->
            RaftInputBox(look, focused, value.isEmpty(), placeholder, leading, trailing, prefix, suffix, inner)
        },
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
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val look = raftFieldBoxLook(enabled, invalid, RaftTheme.colors, readOnly)
    val focused = enabled && raftIsFocused(source)
    val field = LocalRaftFieldState.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.raftFieldSemantics(field.label, invalid, enabled, field.error),
        enabled = enabled,
        readOnly = readOnly,
        textStyle = raftFieldTextStyle(look.text),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = true,
        visualTransformation = visualTransformation,
        interactionSource = source,
        cursorBrush = SolidColor(RaftTheme.colors.foreground),
        decorationBox = { inner ->
            RaftInputBox(look, focused, value.text.isEmpty(), placeholder, leading, trailing, prefix, suffix, inner)
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
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val look = raftFieldBoxLook(enabled, invalid, RaftTheme.colors, readOnly)
    val focused = enabled && raftIsFocused(source)
    val field = LocalRaftFieldState.current
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = minHeight, max = maxHeight)
                    .raftFieldSemantics(field.label, invalid, enabled, field.error),
            enabled = enabled,
            readOnly = readOnly,
            textStyle = raftFieldTextStyle(look.text),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            singleLine = false,
            interactionSource = source,
            cursorBrush = SolidColor(RaftTheme.colors.foreground),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = minHeight)
                        .raftFieldChrome(look, focused)
                        // CSS `py-2.5 px-3` sit inside the 1.5dp border.
                        .padding(horizontal = 12.dp + RaftStroke.Control, vertical = 10.dp + RaftStroke.Control),
                ) {
                    if (value.isEmpty() && placeholder != null) {
                        RaftText(placeholder, style = raftFieldTextStyle(look.placeholder))
                    }
                    inner()
                }
            },
        )
        if (limit != null) {
            RaftTextareaCounter(count = value.length, limit = limit, invalid = invalid && enabled)
        }
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

/** `text-field font-field`: raft-ui `--field-*`, 16/24 regular ([RaftTypography.bodyLarge]). */
@Composable
private fun raftFieldTextStyle(color: Color): TextStyle = RaftTheme.typography.bodyLarge.copy(color = color)

/** The field box: focus ring outside, fill, and the 1.5dp border inside. */
@Composable
private fun Modifier.raftFieldChrome(look: RaftFieldBoxLook, focused: Boolean): Modifier {
    val shape = RoundedCornerShape(RaftRadius.Control)
    return this
        .raftFocusRing(focused, RaftRadius.Control, RaftTheme.colors)
        .background(look.fill, shape)
        .border(RaftStroke.Control, look.border, shape)
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
    inner: @Composable () -> Unit,
) {
    // input-group.tsx: an addon is `px-3`, trimmed to 8dp (icon) / 2dp (text) on the
    // control's side, and the control drops its own padding on that side. All of it sits
    // inside the 1.5dp border.
    val edge = 12.dp + RaftStroke.Control
    val hasStart = leading != null || prefix != null
    val hasEnd = trailing != null || suffix != null
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
                AddonText(prefix, Modifier.padding(start = if (leading != null) 8.dp else edge, end = 2.dp), look.addon)
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
                AddonText(suffix, Modifier.padding(start = 2.dp, end = if (trailing != null) 8.dp else edge), look.addon)
            }
            if (trailing != null) {
                Box(
                    Modifier.padding(start = if (suffix != null) 0.dp else 8.dp, end = edge).size(RaftTextFieldDefaults.AddonIconSize),
                    contentAlignment = Alignment.Center,
                ) { trailing() }
            }
        }
    }
}

@Composable
private fun AddonText(text: String, modifier: Modifier, color: Color) {
    RaftText(text, modifier, style = RaftTheme.typography.monoCode, color = color, maxLines = 1, softWrap = false)
}
