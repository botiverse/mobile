package build.raft.app.components

import build.raft.app.raftDisplayUppercase
import build.raft.app.RaftColor
import build.raft.app.RaftMonoFamily
import build.raft.app.RaftAppRadius
import build.raft.app.raftSlugSlashCenteringOffsetDp
import build.raft.app.RaftShadow
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
import androidx.compose.foundation.layout.offset
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
    if (raftActive()) {
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
        return
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showLabel) {
            RaftFormFieldLabel(
                label = label,
                required = required,
                optional = optional,
                labelStyle = labelStyle,
                size = size,
                accessory = labelAccessory
            )
        }
        content()
        val message = error?.takeIf { it.isNotBlank() } ?: hint?.takeIf { it.isNotBlank() }
        message?.let {
            Text(
                text = it,
                style =
                    RaftType.Caption
                        .copy(fontSize = 12.sp, lineHeight = 14.sp)
                        .withRaftFontWeight(if (error != null) FontWeight.Bold else FontWeight.Normal),
                color = if (error != null) RaftColor.AccentRed else RaftColor.TextBlack.copy(alpha = 0.50f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
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
private fun RaftFormFieldLabel(
    label: String,
    required: Boolean,
    optional: Boolean,
    labelStyle: RaftFormFieldLabelStyle,
    size: RaftFormFieldSize,
    accessory: (@Composable () -> Unit)? = null
) {
    val compact = size == RaftFormFieldSize.Compact
    val uppercase = labelStyle == RaftFormFieldLabelStyle.Uppercase
    val labelFontSize = if (compact) 11.sp else 14.sp
    val labelLineHeight = if (compact) 14.sp else 18.sp
    Row(
        // An accessory sits at the trailing edge of the label row (react FormField
        // `labelAccessory` + `ml-auto`); without one the row keeps its wrap-content width.
        modifier = if (accessory != null) Modifier.fillMaxWidth() else Modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (uppercase) label.raftDisplayUppercase() else label,
            style =
                RaftType.SectionLabel
                    .copy(
                        fontSize = labelFontSize,
                        lineHeight = labelLineHeight,
                        letterSpacing = if (uppercase) 1.1.sp else 0.sp
                    )
                    .withRaftFontWeight(FontWeight.Bold),
            // react FormField.tsx: compact labels are text-black/60 (= #666666 over
            // white); default labels stay solid text-black. Color is per-size, not
            // per-state.
            color = if (compact) RaftColor.TextBlack.copy(alpha = 0.60f) else RaftColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (required) {
            Text(
                text = "*",
                style = RaftType.SectionLabel.copy(fontSize = labelFontSize, lineHeight = labelLineHeight),
                color = RaftColor.AccentPink,
                maxLines = 1
            )
        }
        if (optional) {
            Text(
                text = stringResource(MR.strings.common_optional_suffix),
                style =
                    RaftType.Caption
                        .copy(fontSize = if (compact) 11.sp else 12.sp, lineHeight = 14.sp)
                        .withRaftFontWeight(if (compact) FontWeight.Bold else FontWeight.Normal),
                color = if (compact) RaftColor.TextMutedGray else RaftColor.TextBlack.copy(alpha = 0.40f),
                maxLines = 1
            )
        }
        if (accessory != null) {
            Spacer(modifier = Modifier.weight(1f))
            accessory()
        }
    }
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
        if (raftActive()) {
            RaftTextareaFooter(
                error = error,
                hint = hint,
                count = if (showCounter && limit != null) value.length else null,
                limit = limit
            )
            return@Column
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Text(
                text = error?.takeIf { it.isNotBlank() } ?: hint.orEmpty(),
                style =
                    RaftType.Caption
                        .copy(fontSize = 12.sp, lineHeight = 14.sp)
                        .withRaftFontWeight(if (hasError) FontWeight.Bold else FontWeight.Normal),
                color = if (hasError) RaftColor.AccentRed else RaftColor.TextBlack.copy(alpha = 0.60f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (showCounter && limit != null) {
                val overLimit = value.length > limit
                Text(
                    text = "${value.length}/$limit",
                    style =
                        RaftType.Caption
                            .copy(fontSize = 12.sp, lineHeight = 14.sp)
                            .withRaftFontWeight(if (overLimit || hasError) FontWeight.Bold else FontWeight.Normal),
                    color = if (overLimit || hasError) RaftColor.AccentRed else RaftColor.TextBlack.copy(alpha = 0.50f),
                    maxLines = 1
                )
            }
        }
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
        if (raftActive()) {
            RaftTextareaFooter(
                error = error,
                hint = hint,
                count = if (showCounter && limit != null) value.text.length else null,
                limit = limit
            )
            return@Column
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Text(
                text = error?.takeIf { it.isNotBlank() } ?: hint.orEmpty(),
                style =
                    RaftType.Caption
                        .copy(fontSize = 12.sp, lineHeight = 14.sp)
                        .withRaftFontWeight(if (hasError) FontWeight.Bold else FontWeight.Normal),
                color = if (hasError) RaftColor.AccentRed else RaftColor.TextBlack.copy(alpha = 0.60f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (showCounter && limit != null) {
                val overLimit = value.text.length > limit
                Text(
                    text = "${value.text.length}/$limit",
                    style =
                        RaftType.Caption
                            .copy(fontSize = 12.sp, lineHeight = 14.sp)
                            .withRaftFontWeight(if (overLimit) FontWeight.Bold else FontWeight.Normal),
                    color = if (overLimit) RaftColor.AccentRed else RaftColor.TextBlack.copy(alpha = 0.45f),
                    maxLines = 1
                )
            }
        }
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
    if (raftActive()) {
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
        return
    }
    val shadowOffset = RaftShadow.BrutalSmall
    // react's disabled look is CSS group opacity-60: each part flattens against
    // what's beneath it INSIDE the group first, then the whole result dims over
    // the page. Per-part alpha only equals that over the page backdrop, so parts
    // stacked on the tile (border, slash) drift warm. Precomputed opaque react
    // composites instead (white backdrop, matching the disabled-button precedent):
    // shadow #141111 x0.6 = #727070; border/text black x0.6 = #666666.
    val shadowColor = if (enabled) RaftColor.ShadowBlack else Color(0xFF727070)
    val borderColor = if (enabled) RaftColor.BorderBlack else Color(0xFF666666)
    val contentColor = if (enabled) RaftColor.TextBlack else Color(0xFF666666)
    Box(modifier = modifier.fillMaxWidth().height(fieldHeight + shadowOffset)) {
        if (shadowOffset > 0.dp) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(fieldHeight)
                        .offset(x = shadowOffset, y = shadowOffset)
                        .background(shadowColor)
            )
        }
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(fieldHeight)
                    .background(RaftColor.SurfaceWhite)
                    .border(RaftAppStroke.Default, borderColor),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier =
                    Modifier
                        // react's w-8 (32px) span sits INSIDE the outer border-2;
                        // Kuikly draws the Row border as a FOREGROUND over this
                        // child's left 2dp, so 34dp is what leaves react's 30px
                        // visible tile + 2px divider (#1038: the 32dp box shifted
                        // the divider and all downstream text 2dp left).
                        .width(34.dp)
                        // structural mirror of react's items-stretch span
                        .fillMaxHeight()
                        .background(if (enabled) RaftSlugInputPrefixBackground else RaftSlugInputDisabledPrefixBackground)
                        // Only the right divider is visible (the other 3 sides sit
                        // under the Row's foreground border); react divider is
                        // border-r-2 = 2px, so Default, not Thin.
                        .border(RaftAppStroke.Default, borderColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "/",
                    // Empirically measured on the pinned render build (run
                    // 29142637461): react's slash ink is dead-centered in the
                    // tile; android's sits 1.5dp low under the even-split span.
                    // Platform-gated correction - see
                    // raftSlugSlashCenteringOffsetDp() for the measurement
                    // provenance and the do-not-copy-blind note for iOS/OHOS.
                    modifier = Modifier.offset(y = raftSlugSlashCenteringOffsetDp().dp),
                    style = RaftType.ButtonBold.copy(fontSize = 16.sp, lineHeight = 18.sp),
                    // react slash = text-black/70. Disabled must match react's GROUP
                    // flatten (glyph over tile first, then x0.6 over white) = opaque
                    // #94928A; a plain alpha over the dim tile lets ~2x the yellow
                    // through (round-2 adversarial review, Delta(0,3,10)/px).
                    color = if (enabled) RaftColor.TextBlack.copy(alpha = 0.70f) else Color(0xFF94928A),
                    maxLines = 1
                )
            }
            TextField(
                value = value,
                onValueChange = { if (enabled) onValueChange(it) },
                placeholder = placeholder,
                placeholderColor = placeholderColor,
                autoFocus = false,
                maxLines = 1,
                textStyle = RaftType.Input.copy(fontSize = 14.sp, lineHeight = 18.sp, color = contentColor),
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart
            )
        }
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
    if (raftActive()) {
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
        return
    }
    val shadowOffset = RaftShadow.BrutalSmall
    // react's disabled look is CSS group opacity-60: each part flattens against
    // what's beneath it INSIDE the group first, then the whole result dims over
    // the page. Per-part alpha only equals that over the page backdrop, so parts
    // stacked on the tile (border, slash) drift warm. Precomputed opaque react
    // composites instead (white backdrop, matching the disabled-button precedent):
    // shadow #141111 x0.6 = #727070; border/text black x0.6 = #666666.
    val shadowColor = if (enabled) RaftColor.ShadowBlack else Color(0xFF727070)
    val borderColor = if (enabled) RaftColor.BorderBlack else Color(0xFF666666)
    val contentColor = if (enabled) RaftColor.TextBlack else Color(0xFF666666)
    Box(modifier = modifier.fillMaxWidth().height(fieldHeight + shadowOffset)) {
        if (shadowOffset > 0.dp) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(fieldHeight)
                        .offset(x = shadowOffset, y = shadowOffset)
                        .background(shadowColor)
            )
        }
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(fieldHeight)
                    .background(RaftColor.SurfaceWhite)
                    .border(RaftAppStroke.Default, borderColor),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier =
                    Modifier
                        // react's w-8 (32px) span sits INSIDE the outer border-2;
                        // Kuikly draws the Row border as a FOREGROUND over this
                        // child's left 2dp, so 34dp is what leaves react's 30px
                        // visible tile + 2px divider (#1038: the 32dp box shifted
                        // the divider and all downstream text 2dp left).
                        .width(34.dp)
                        // structural mirror of react's items-stretch span
                        .fillMaxHeight()
                        .background(if (enabled) RaftSlugInputPrefixBackground else RaftSlugInputDisabledPrefixBackground)
                        // Only the right divider is visible (the other 3 sides sit
                        // under the Row's foreground border); react divider is
                        // border-r-2 = 2px, so Default, not Thin.
                        .border(RaftAppStroke.Default, borderColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "/",
                    // Empirically measured on the pinned render build (run
                    // 29142637461): react's slash ink is dead-centered in the
                    // tile; android's sits 1.5dp low under the even-split span.
                    // Platform-gated correction - see
                    // raftSlugSlashCenteringOffsetDp() for the measurement
                    // provenance and the do-not-copy-blind note for iOS/OHOS.
                    modifier = Modifier.offset(y = raftSlugSlashCenteringOffsetDp().dp),
                    style = RaftType.ButtonBold.copy(fontSize = 16.sp, lineHeight = 18.sp),
                    // react slash = text-black/70. Disabled must match react's GROUP
                    // flatten (glyph over tile first, then x0.6 over white) = opaque
                    // #94928A; a plain alpha over the dim tile lets ~2x the yellow
                    // through (round-2 adversarial review, Delta(0,3,10)/px).
                    color = if (enabled) RaftColor.TextBlack.copy(alpha = 0.70f) else Color(0xFF94928A),
                    maxLines = 1
                )
            }
            TextField(
                value = value,
                onValueChange = { if (enabled) onValueChange(it) },
                placeholder = placeholder,
                placeholderColor = placeholderColor,
                autoFocus = false,
                maxLines = 1,
                textStyle = RaftType.Input.copy(fontSize = 14.sp, lineHeight = 18.sp, color = contentColor),
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart
            )
        }
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
        val raft = raftActive()
        Text(
            text = label,
            style = RaftType.ButtonBold.copy(fontSize = 14.sp, lineHeight = if (raft) 20.sp else 18.sp),
            color =
                when {
                    enabled -> RaftColor.TextBlack
                    raft -> RaftColor.ForegroundDisabled
                    else -> RaftColor.TextMutedGray
                },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
