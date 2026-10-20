package build.raft.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftStrings
import build.raft.designsystem.theme.RaftStrings
import build.raft.designsystem.theme.RaftTheme

/** raft-ui `FieldLabel` `size`. */
enum class RaftFieldLabelSize {
    /** `text-caption` semibold: dense layouts. */
    Sm,

    /** `text-footnote` semibold: form fields (default). */
    Md,
}

/**
 * State a [RaftField] passes to the control inside it, as Base UI Field does: the
 * controls in this module ([RaftTextField], [RaftTextarea], [RaftSelectTrigger],
 * [RaftSelect], [RaftCheckbox], [RaftRadio], [RaftSwitch]) default their `invalid` /
 * `enabled` to it. The text fields, the select trigger and the switch are also named by
 * [label] for screen readers and carry [error] as their error (Base UI wires the label
 * and the error to the control with `aria-labelledby` / `aria-describedby`); an
 * interactive checkbox or radio is named by [label] too ([RaftFieldItem] sets it).
 */
@Immutable
data class RaftFieldState(
    val invalid: Boolean = false,
    val enabled: Boolean = true,
    val label: String? = null,
    val error: String? = null,
    /** Inside a [RaftField]: a select trigger takes the field type to line up with inputs. */
    val inField: Boolean = false,
)

/** The enclosing [RaftField]'s state; outside a field: valid and enabled. */
val LocalRaftFieldState = compositionLocalOf { RaftFieldState() }

/**
 * raft-ui 0.8.0 Field (the dialog form field of the page hand-off): a column (6dp gaps) of
 * a label, the control and one line of help text or the error.
 *
 * - [label] (`FieldLabel`): sentence case, `foreground-muted` semibold, `text-footnote`
 *   ([RaftFieldLabelSize.Md]) or `text-caption` ([RaftFieldLabelSize.Sm]). [optional]
 *   appends "(optional)" in `foreground-hint`; [required] an asterisk. Mark only the
 *   optional fields.
 * - [description] (`FieldDescription`): `text-caption` (12/16 medium), `foreground-hint`.
 * - [error] (`FieldError`): shown in place of the description, `text-caption` semibold
 *   `danger-strong`, announced politely when it appears. A non-null [error] makes the
 *   field invalid, so the control inside gets the `danger-strong` border and the error in
 *   its semantics through [LocalRaftFieldState]. Say how to fix it ("Enter a name"), not
 *   what went wrong.
 * - Screen readers read the control by [label] (its content description).
 * - [enabled] = false turns label, description and error `foreground-disabled` and
 *   disables the control. Disabled wins over invalid.
 * - [labelTrailing]: a small control at the end of the label's row, centred on it, 8dp
 *   from the label: a [RaftSwitch] that turns the field on, or a [RaftTextLink] ("Clear",
 *   "Reset"). The row takes the field's full width; the label ellipsizes, never the slot.
 *   It is not part of the label: give it its own name.
 *
 * ```
 * RaftField(label = "Agent name", description = "Shown in chat and on tasks.") {
 *     RaftTextField(value = name, onValueChange = { name = it }, placeholder = "Wug")
 * }
 * ```
 */
@Composable
fun RaftField(
    label: String?,
    modifier: Modifier = Modifier,
    description: String? = null,
    error: String? = null,
    enabled: Boolean = true,
    optional: Boolean = false,
    required: Boolean = false,
    labelSize: RaftFieldLabelSize = RaftFieldLabelSize.Md,
    labelTrailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val invalid = error != null
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (labelTrailing != null) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (label != null) {
                        RaftFieldLabel(label, size = labelSize, enabled = enabled, optional = optional, required = required, maxLines = 1)
                    }
                }
                labelTrailing()
            }
        } else if (label != null) {
            RaftFieldLabel(label, size = labelSize, enabled = enabled, optional = optional, required = required)
        }
        CompositionLocalProvider(
            LocalRaftFieldState provides RaftFieldState(invalid = invalid, enabled = enabled, label = label, error = error, inField = true),
        ) {
            content()
        }
        if (error != null) {
            RaftFieldError(error, enabled = enabled)
        } else if (description != null) {
            RaftFieldDescription(description, enabled = enabled)
        }
    }
}

/**
 * raft-ui `FieldLabel` on its own (for custom field layouts). See [RaftField]. The
 * required asterisk and the "(optional)" suffix ([RaftStrings.optional], from
 * [LocalRaftStrings]) follow the text after a space (`ml-1`).
 * It takes the full line height of its style (raft-ui 0.8.0 dropped `leading-none`), so
 * a control beside it can centre on that line ([RaftFieldItem]). [maxLines] cuts a long
 * label with an ellipsis (a label beside a [RaftField] `labelTrailing` slot takes one line).
 */
@Composable
fun RaftFieldLabel(
    text: String,
    modifier: Modifier = Modifier,
    size: RaftFieldLabelSize = RaftFieldLabelSize.Md,
    enabled: Boolean = true,
    optional: Boolean = false,
    required: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
) {
    val colors = RaftTheme.colors
    val optionalText = LocalRaftStrings.current.optional
    val ink = if (enabled) colors.foregroundMuted else colors.foregroundDisabled
    val markInk = if (enabled) colors.foregroundHint else colors.foregroundDisabled
    val annotated =
        buildAnnotatedString {
            append(text)
            if (required) {
                withStyle(SpanStyle(color = markInk)) { append(" *") }
            } else if (optional) {
                withStyle(SpanStyle(color = markInk, fontWeight = FontWeight.Normal)) { append(" " + optionalText) }
            }
        }
    RaftText(annotated, modifier, style = raftFieldLabelStyle(size), color = ink, maxLines = maxLines, overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis)
}

/**
 * raft-ui 0.8.0 FieldItem: a checkbox, radio or switch beside its [RaftFieldLabel] (and an
 * optional [description] under the label), 8dp apart. The control is centred on the
 * label's first line, worked out from the line height of [size] (`(1lh - control) / 2`),
 * so a 20dp box and a 30dp switch both land on it, and it stays there when the label wraps
 * or a description follows. Give it the size of its label: [size] sets both.
 *
 * - [RaftFieldLabelSize.Md] (default): `text-footnote` semibold label, 18dp line.
 * - [RaftFieldLabelSize.Sm]: `text-caption` semibold label, 16dp line, for dense layouts.
 *
 * As Base UI `Field.Item` / `Field.Label` do, the label names the control (a [RaftCheckbox],
 * [RaftRadio] or [RaftSwitch] inside reads [label] as its content description) and a tap
 * on the label (or the description) works the control, as a tap on the control itself
 * does. The label text is hidden from screen readers, which read it on the control. The
 * control keeps its own `onCheckedChange` / `onClick`; pass `null` there and nothing toggles.
 * For a choice in a form or dialog where the whole padded row is one target, use
 * [RaftCheckboxRow] / [RaftRadioRow].
 */
@Composable
fun RaftFieldItem(
    label: String,
    modifier: Modifier = Modifier,
    size: RaftFieldLabelSize = RaftFieldLabelSize.Md,
    description: String? = null,
    enabled: Boolean = LocalRaftFieldState.current.enabled,
    control: @Composable () -> Unit,
) {
    val style = raftFieldLabelStyle(size)
    val target = remember { RaftFieldItemTarget() }
    val field = LocalRaftFieldState.current
    CompositionLocalProvider(
        LocalRaftFieldState provides field.copy(label = label, enabled = enabled),
        LocalRaftFieldItemTarget provides target,
    ) {
        RaftFirstLineRow(
            lineHeight = raftLineHeightDp(style.lineHeight, style.fontSize, LocalDensity.current),
            gap = 8.dp,
            modifier = modifier,
            control = control,
        ) {
            Column(
                Modifier
                    // `<label>`: a tap works the control; the control carries the name.
                    .clearAndSetSemantics {}
                    .pointerInput(target) { detectTapGestures { target.onTap?.invoke() } },
            ) {
                RaftFieldLabel(label, size = size, enabled = enabled)
                if (description != null) RaftFieldDescription(description, enabled = enabled)
            }
        }
    }
}

/**
 * The control of a [RaftFieldItem] registers its tap here, so a tap on the item's label
 * works it (`<label for>`). Null when the control is disabled or only an indicator.
 */
internal class RaftFieldItemTarget {
    var onTap: (() -> Unit)? = null
}

/** The enclosing [RaftFieldItem]'s label target, for the control inside it. */
internal val LocalRaftFieldItemTarget = staticCompositionLocalOf<RaftFieldItemTarget?> { null }

/** Registers [onTap] (null: nothing to do) with the enclosing [RaftFieldItem]'s label. */
@Composable
internal fun RaftRegisterFieldItemTap(onTap: (() -> Unit)?) {
    val target = LocalRaftFieldItemTarget.current ?: return
    SideEffect { target.onTap = onTap }
}

/** `FieldLabel`'s type: semibold `text-caption` (sm) or `text-footnote` (md), at the style's own line height. */
@Composable
internal fun raftFieldLabelStyle(size: RaftFieldLabelSize): TextStyle {
    val base = if (size == RaftFieldLabelSize.Sm) RaftTheme.typography.caption else RaftTheme.typography.footnote
    return base.copy(fontWeight = FontWeight.SemiBold)
}

/** The default error a control reports when it is invalid but its field gave no message. */
const val RaftInvalidValueMessage = "Invalid value"

/**
 * A field control's semantics: named by [label], and [error] (or [RaftInvalidValueMessage])
 * when [invalid]. Disabled controls report no error: nobody can fix them.
 */
internal fun Modifier.raftFieldSemantics(label: String?, invalid: Boolean, enabled: Boolean, error: String?): Modifier =
    if (label == null && !(invalid && enabled)) {
        this
    } else {
        semantics {
            if (label != null) contentDescription = label
            if (invalid && enabled) error(error ?: RaftInvalidValueMessage)
        }
    }

/** raft-ui `FieldDescription`: one line of help text under the control. */
@Composable
fun RaftFieldDescription(text: String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = RaftTheme.colors
    RaftText(
        text,
        modifier,
        style = RaftTheme.typography.caption,
        color = if (enabled) colors.foregroundHint else colors.foregroundDisabled,
    )
}

/** raft-ui `FieldError`: says how to fix the value; announced (politely) as an error when it appears. */
@Composable
fun RaftFieldError(text: String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = RaftTheme.colors
    RaftText(
        text,
        modifier.semantics {
            error(text)
            liveRegion = LiveRegionMode.Polite
        },
        style = RaftTheme.typography.caption.copy(fontWeight = FontWeight.SemiBold),
        color = if (enabled) colors.dangerStrong else colors.foregroundDisabled,
    )
}

/**
 * raft-ui 0.8.0 FormSection: one group of fields in a wide dialog (or a long form). A
 * column with 12dp gaps and 16dp vertical padding; [first] drops the top padding, and
 * every later section starts with a `line-hairline` rule (CSS `[section+section]:border-t`).
 *
 * The head: [title] in `text-footnote` bold `foreground-muted` (sentence case), an
 * optional [status] as a soft muted [RaftBadge] after it, and [description] in
 * `text-footnote` `foreground-hint` under it.
 */
@Composable
fun RaftFormSection(
    title: String,
    modifier: Modifier = Modifier,
    first: Boolean = false,
    description: String? = null,
    status: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    Column(modifier.fillMaxWidth()) {
        if (!first) RaftSeparator()
        Column(
            Modifier.fillMaxWidth().padding(top = if (first) 0.dp else 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RaftText(
                        title,
                        Modifier.weight(1f, fill = false),
                        style = typography.footnote.copy(fontWeight = FontWeight.Bold),
                        color = colors.foregroundMuted,
                    )
                    if (status != null) {
                        RaftBadge(status, appearance = RaftBadgeAppearance.Soft, variant = RaftBadgeVariant.Muted)
                    }
                }
                if (description != null) {
                    RaftText(description, style = typography.footnote, color = colors.foregroundHint)
                }
            }
            content()
        }
    }
}
