package build.raft.app.components

import build.raft.app.RaftColor
import build.raft.app.RaftMonoFamily
import build.raft.app.RaftAppRadius
import build.raft.app.RaftAppStroke
import build.raft.app.RaftType
import build.raft.app.TextField
import build.raft.app.withRaftFontWeight
import build.raft.app.resources.MR
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tencent.tmm.kmmresource.compose.stringResource

internal enum class RaftFormFieldLabelStyle {
    Uppercase,
    Plain
}

internal enum class RaftFormFieldSize {
    Default,
    Compact
}

internal val RaftSlugInputPrefixBackground =
    RaftColor.BrandYellow.copy(alpha = 0.30f) // react-parity: intentional bg-palette-yellow/30 wash
// react disabled tile flattened over white: BrandYellow @ (0.30 tile x 0.60
// control opacity) = opaque #FFF7DD. Opaque on purpose - see the disabled
// color block in RaftSlugInput for why per-part alpha can't match CSS group
// opacity. (The old value, BrandYellow @ 0.55, was a Color.copy(alpha=)
// REPLACE bug: disabled rendered MORE saturated than enabled's 0.30.)
internal val RaftSlugInputDisabledPrefixBackground = Color(0xFFFFF7DD)

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun RaftFormField(
    label: String,
    modifier: Modifier = Modifier,
    required: Boolean = false,
    optional: Boolean = false,
    hint: String? = null,
    error: String? = null,
    showLabel: Boolean = true,
    labelStyle: RaftFormFieldLabelStyle = RaftFormFieldLabelStyle.Uppercase,
    size: RaftFormFieldSize = RaftFormFieldSize.Default,
    labelAccessory: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    // raft-ui Field: label -> control -> help on a 6dp rhythm; sentence-case label
    // (13/18 semibold muted, 12/16 compact), help 12/16 hint ink, error 12/16 semibold
    // `--danger-strong`. [labelStyle] is ignored: raft never sets labels in ALL CAPS.
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (showLabel) {
            RaftLookFieldLabel(
                label = label,
                required = required,
                optional = optional,
                compact = size == RaftFormFieldSize.Compact,
                accessory = labelAccessory
            )
        }
        content()
        RaftFieldMessage(error = error, hint = hint)
    }
}

/**
 * raft-ui `FieldLabel`: 13/18 (`md`) or 12/16 (`sm`) semibold `--foreground-muted`,
 * sentence case; required `*` and the optional suffix follow in hint ink.
 */
@Composable
internal fun RaftLookFieldLabel(
    label: String,
    required: Boolean,
    optional: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
    disabled: Boolean = false,
    accessory: (@Composable () -> Unit)? = null
) {
    val fontSize = if (compact) 12.sp else 13.sp
    val lineHeight = if (compact) 16.sp else 18.sp
    val labelInk = if (disabled) RaftColor.ForegroundDisabled else RaftColor.TextMuted
    val subInk = if (disabled) RaftColor.ForegroundDisabled else RaftColor.ForegroundHint
    Row(
        modifier = if (accessory != null) modifier.fillMaxWidth() else modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style =
                RaftType.Body
                    .copy(fontSize = fontSize, lineHeight = lineHeight, letterSpacing = 0.sp)
                    .withRaftFontWeight(FontWeight.SemiBold),
            color = labelInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (required) {
            Text(
                text = "*",
                style = RaftType.Body.copy(fontSize = fontSize, lineHeight = lineHeight).withRaftFontWeight(FontWeight.SemiBold),
                color = subInk,
                maxLines = 1
            )
        }
        if (optional) {
            Text(
                text = stringResource(MR.strings.common_optional_suffix),
                style = RaftType.Body.copy(fontSize = fontSize, lineHeight = lineHeight),
                color = subInk,
                maxLines = 1
            )
        }
        if (accessory != null) {
            Spacer(modifier = Modifier.weight(1f))
            accessory()
        }
    }
}

/** raft-ui `FieldError` (12/16 semibold `--danger-strong`) or `FieldDescription` (12/16 hint). */
@Composable
internal fun RaftFieldMessage(
    error: String?,
    hint: String?,
    modifier: Modifier = Modifier,
    maxLines: Int = 2
) {
    val errorText = error?.takeIf { it.isNotBlank() }
    val message = errorText ?: hint?.takeIf { it.isNotBlank() } ?: return
    Text(
        text = message,
        modifier = modifier,
        style =
            RaftType.Caption
                .copy(fontSize = 12.sp, lineHeight = 16.sp)
                .withRaftFontWeight(if (errorText != null) FontWeight.SemiBold else FontWeight.Normal),
        color = if (errorText != null) RaftColor.DangerStrong else RaftColor.ForegroundHint,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
internal fun RaftBrutalTextarea(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    hint: String? = null,
    error: String? = null,
    showCounter: Boolean = false,
    limit: Int? = null,
    enabled: Boolean = true,
    fieldHeight: Dp = 76.dp,
    maxLines: Int = 3
) {
    val hasError = !error.isNullOrBlank()
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RaftBrutalInputField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            enabled = enabled,
            autoFocus = false,
            maxLines = maxLines,
            fieldMinHeight = fieldHeight,
            borderColor = if (hasError) RaftColor.AccentRed else RaftColor.BorderBlack,
            enabledBackgroundColor = if (hasError) RaftFieldErrorTint else RaftColor.SurfaceWhite,
            // react .input-brutal textareas inherit the 16px body font with
            // Tailwind's 1.5 line-height (16/24); 14/18 wrapped one word earlier
            // per line and read visibly smaller than the web baseline.
            textStyle = RaftType.Input.copy(fontSize = 16.sp, lineHeight = 24.sp, color = RaftColor.TextBlack)
        )
        RaftTextareaFooter(
            error = error,
            hint = hint,
            count = if (showCounter && limit != null) value.length else null,
            limit = limit
        )
    }
}

@Composable
internal fun RaftBrutalTextarea(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    hint: String? = null,
    error: String? = null,
    showCounter: Boolean = false,
    limit: Int? = null,
    enabled: Boolean = true,
    fieldHeight: Dp = 76.dp,
    maxLines: Int = 3
) {
    val hasError = !error.isNullOrBlank()
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RaftBrutalInputField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            enabled = enabled,
            autoFocus = false,
            maxLines = maxLines,
            fieldMinHeight = fieldHeight,
            borderColor = if (hasError) RaftColor.AccentRed else RaftColor.BorderBlack,
            enabledBackgroundColor = if (hasError) RaftFieldErrorTint else RaftColor.SurfaceWhite,
            // react .input-brutal textareas inherit the 16px body font with
            // Tailwind's 1.5 line-height (16/24); 14/18 wrapped one word earlier
            // per line and read visibly smaller than the web baseline.
            textStyle = RaftType.Input.copy(fontSize = 16.sp, lineHeight = 24.sp, color = RaftColor.TextBlack)
        )
        RaftTextareaFooter(
            error = error,
            hint = hint,
            count = if (showCounter && limit != null) value.text.length else null,
            limit = limit
        )
    }
}

/**
 * raft-ui textarea footer: help / error on the left (see [RaftFieldMessage]) and the
 * `TextareaCounter` on the right (mono 12/16 hint ink, `--danger-strong` once over).
 */
@Composable
private fun RaftTextareaFooter(
    error: String?,
    hint: String?,
    count: Int?,
    limit: Int?
) {
    val hasMessage = !error.isNullOrBlank() || !hint.isNullOrBlank()
    if (!hasMessage && count == null) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(modifier = Modifier.weight(1f)) {
            RaftFieldMessage(error = error, hint = hint, maxLines = 1)
        }
        if (count != null && limit != null) {
            Text(
                text = "$count/$limit",
                style = RaftType.Caption.copy(fontFamily = RaftMonoFamily, fontSize = 12.sp, lineHeight = 16.sp),
                color = if (count > limit) RaftColor.DangerStrong else RaftColor.ForegroundHint,
                maxLines = 1
            )
        }
    }
}

@Composable
internal fun RaftSlugInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    placeholderColor: Color = RaftColor.TextMutedGray,
    enabled: Boolean = true,
    // react .input-brutal outer height: 24px line + 16px py-2 + 4px border-2 =
    // 44px. Kuikly draws the Row border as a FOREGROUND over children, so with
    // 44dp outer the visible tile inside the 2dp border = 40dp = react's
    // items-stretch span (120px @3x).
    fieldHeight: Dp = 44.dp
) {
    RaftPrefixedInputFrame(prefix = "/", enabled = enabled, fieldHeight = fieldHeight, modifier = modifier) {
        TextField(
            value = value,
            onValueChange = { if (enabled) onValueChange(it) },
            placeholder = placeholder,
            placeholderColor = if (placeholderColor == RaftColor.TextMutedGray) RaftColor.ForegroundHint else placeholderColor,
            autoFocus = false,
            maxLines = 1,
            textStyle = RaftType.Input.copy(
                fontSize = 16.sp,
                lineHeight = 24.sp,
                color = if (enabled) RaftColor.TextBlack else RaftColor.ForegroundDisabled
            ),
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart
        )
    }
}

/**
 * Value-aware [RaftSlugInput] overload (#130): callers hold a caller-local
 * [TextFieldValue] so CJK composition and cursor/selection survive typing, while
 * the durable slug stays a `.text` String projection at the caller. Mirrors the
 * String overload above exactly; the String overload is retained and is removed
 * later by task #131.
 */
@Composable
internal fun RaftSlugInput(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    placeholderColor: Color = RaftColor.TextMutedGray,
    enabled: Boolean = true,
    // react .input-brutal outer height: 24px line + 16px py-2 + 4px border-2 =
    // 44px. Kuikly draws the Row border as a FOREGROUND over children, so with
    // 44dp outer the visible tile inside the 2dp border = 40dp = react's
    // items-stretch span (120px @3x).
    fieldHeight: Dp = 44.dp
) {
    RaftPrefixedInputFrame(prefix = "/", enabled = enabled, fieldHeight = fieldHeight, modifier = modifier) {
        TextField(
            value = value,
            onValueChange = { if (enabled) onValueChange(it) },
            placeholder = placeholder,
            placeholderColor = if (placeholderColor == RaftColor.TextMutedGray) RaftColor.ForegroundHint else placeholderColor,
            autoFocus = false,
            maxLines = 1,
            textStyle = RaftType.Input.copy(
                fontSize = 16.sp,
                lineHeight = 24.sp,
                color = if (enabled) RaftColor.TextBlack else RaftColor.ForegroundDisabled
            ),
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart
        )
    }
}

/**
 * raft-ui `InputGroup` with a `container` text addon (web `PrefixedInput` / `SlugInput`):
 * 44dp, control radius, 1.5dp `--line-strong` edge on the card layer; the addon is a
 * `--fill-muted` cell behind a 1dp `--line-hairline` divider with mono 13/20 placeholder ink.
 * Disabled is the opaque `--fill-muted` / `--line-hairline` pair. No shadow.
 */
@Composable
internal fun RaftPrefixedInputFrame(
    prefix: String,
    enabled: Boolean,
    fieldHeight: Dp,
    modifier: Modifier = Modifier,
    field: @Composable RowScope.() -> Unit
) {
    val shape = RoundedCornerShape(RaftAppRadius.Control)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(fieldHeight)
                .clip(shape)
                .background(if (enabled) RaftColor.Card else RaftColor.FillMuted, shape)
                .border(RaftAppStroke.Control, if (enabled) RaftColor.LineField else RaftColor.BorderHairline, shape),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .background(RaftColor.FillMuted)
                    .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = prefix,
                style = RaftType.Caption.copy(fontFamily = RaftMonoFamily, fontSize = 13.sp, lineHeight = 20.sp),
                color = if (enabled) RaftColor.ForegroundHint else RaftColor.ForegroundDisabled,
                maxLines = 1
            )
        }
        Box(modifier = Modifier.width(RaftAppStroke.Thin).fillMaxHeight().background(RaftColor.BorderHairline))
        field()
    }
}

@Composable
internal fun RaftCheckboxLabelRow(
    label: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 16.dp
) {
    Row(
        modifier = modifier.widthIn(min = 0.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RaftAppCheckbox(checked = checked, onCheckedChange = {}, enabled = enabled, size = size)
        Text(
            text = label,
            style = RaftType.ButtonBold.copy(fontSize = 14.sp, lineHeight = 20.sp),
            color = if (enabled) RaftColor.TextBlack else RaftColor.ForegroundDisabled,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
