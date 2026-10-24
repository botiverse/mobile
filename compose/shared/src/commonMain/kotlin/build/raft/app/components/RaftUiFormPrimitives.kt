package build.raft.app.components

import build.raft.app.RaftColor
import build.raft.app.RaftAppThemeState
import build.raft.app.RaftMonoFamily
import build.raft.app.RaftAppRadius
import build.raft.app.RaftAppStroke
import build.raft.app.RaftType
import build.raft.app.TextField
import build.raft.app.withRaftFontWeight
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import build.raft.app.raftAppEditorBehavior
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftTextarea

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
internal val RaftSlugInputDisabledPrefixBackground: Color
    get() = if (RaftAppThemeState.isDark) RaftColor.BrandSoft else Color(0xFFFFF7DD) // raft-color: light legacy flattened yellow tile

/**
 * Legacy-named textarea: the design-system [RaftField] (help or error under it, field state for
 * the box) round a [RaftTextarea], at least [fieldHeight] tall and growing to [maxLines] lines
 * before it scrolls; [showCounter] with a [limit] adds the `TextareaCounter`. The editor keeps
 * the app editor behaviour ([raftAppEditorBehavior]: keyboard-height events, tap-outside focus
 * dismiss).
 */
@Composable
@Deprecated(
    "Legacy RaftBrutal layer: use build.raft.designsystem.components.RaftTextarea inside a RaftField. Add no new uses; see docs/raftbrutal-migration.md.",
    level = DeprecationLevel.WARNING,
)
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
    RaftTextareaField(modifier = modifier, hint = hint, error = error, enabled = enabled) {
        RaftTextarea(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder.takeIf { it.isNotEmpty() },
            limit = limit.takeIf { showCounter },
            minHeight = fieldHeight,
            maxHeight = raftTextareaMaxHeight(fieldHeight, maxLines),
            inputModifier = rememberRaftTextareaEditorModifier()
        )
    }
}

/**
 * [RaftBrutalTextarea] on a caller-owned [TextFieldValue], so selection and composition survive
 * controlled recomposition; the counter counts the text.
 */
@Composable
@Deprecated(
    "Legacy RaftBrutal layer: use build.raft.designsystem.components.RaftTextarea inside a RaftField. Add no new uses; see docs/raftbrutal-migration.md.",
    level = DeprecationLevel.WARNING,
)
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
    RaftTextareaField(modifier = modifier, hint = hint, error = error, enabled = enabled) {
        RaftTextarea(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder.takeIf { it.isNotEmpty() },
            limit = limit.takeIf { showCounter },
            minHeight = fieldHeight,
            maxHeight = raftTextareaMaxHeight(fieldHeight, maxLines),
            inputModifier = rememberRaftTextareaEditorModifier()
        )
    }
}

@Composable
private fun RaftTextareaField(
    modifier: Modifier,
    hint: String?,
    error: String?,
    enabled: Boolean,
    textarea: @Composable () -> Unit
) {
    RaftField(
        label = null,
        modifier = modifier.fillMaxWidth(),
        description = hint?.takeIf { it.isNotBlank() },
        error = error?.takeIf { it.isNotBlank() },
        enabled = enabled,
        content = textarea
    )
}

/** [maxLines] lines of the field type (24dp line) inside the textarea's 10dp vertical padding. */
private fun raftTextareaMaxHeight(fieldHeight: Dp, maxLines: Int): Dp =
    if (maxLines == Int.MAX_VALUE) Dp.Infinity else maxOf(fieldHeight, RaftTextareaLineHeight * maxLines + 20.dp)

private val RaftTextareaLineHeight = 24.dp

@Composable
private fun rememberRaftTextareaEditorModifier(): Modifier {
    val focusRequester = remember { FocusRequester() }
    return Modifier.raftAppEditorBehavior(
        singleLine = false,
        resolvedKeyboardOptions = KeyboardOptions.Default,
        keyboardActions = KeyboardActions.Default,
        keyboardHeightChange = {},
        focusRequester = focusRequester,
        onFocus = {},
        onBlur = {}
    )
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
