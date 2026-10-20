package build.raft.app.components

import build.raft.app.slockDisplayUppercase
import build.raft.app.SlockColor
import build.raft.app.SlockMonoFamily
import build.raft.app.SlockRadius
import build.raft.app.slockSlugSlashCenteringOffsetDp
import build.raft.app.SlockShadow
import build.raft.app.SlockStroke
import build.raft.app.SlockType
import build.raft.app.TextField
import build.raft.app.withSlockFontWeight
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

internal enum class SlockFormFieldLabelStyle {
    Uppercase,
    Plain
}

internal enum class SlockFormFieldSize {
    Default,
    Compact
}

internal val SlockSlugInputPrefixBackground =
    SlockColor.BrandYellow.copy(alpha = 0.30f) // react-parity: intentional bg-palette-yellow/30 wash
// react disabled tile flattened over white: BrandYellow @ (0.30 tile x 0.60
// control opacity) = opaque #FFF7DD. Opaque on purpose - see the disabled
// color block in SlockSlugInput for why per-part alpha can't match CSS group
// opacity. (The old value, BrandYellow @ 0.55, was a Color.copy(alpha=)
// REPLACE bug: disabled rendered MORE saturated than enabled's 0.30.)
internal val SlockSlugInputDisabledPrefixBackground = Color(0xFFFFF7DD)

@Composable
internal fun SlockFormField(
    label: String,
    modifier: Modifier = Modifier,
    required: Boolean = false,
    optional: Boolean = false,
    hint: String? = null,
    error: String? = null,
    showLabel: Boolean = true,
    labelStyle: SlockFormFieldLabelStyle = SlockFormFieldLabelStyle.Uppercase,
    size: SlockFormFieldSize = SlockFormFieldSize.Default,
    labelAccessory: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    if (slockRaftActive()) {
        // raft-ui Field: label -> control -> help on a 6dp rhythm; sentence-case label
        // (13/18 semibold muted, 12/16 compact), help 12/16 hint ink, error 12/16 semibold
        // `--danger-strong`. [labelStyle] is ignored: raft never sets labels in ALL CAPS.
        Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (showLabel) {
                SlockRaftFieldLabel(
                    label = label,
                    required = required,
                    optional = optional,
                    compact = size == SlockFormFieldSize.Compact,
                    accessory = labelAccessory
                )
            }
            content()
            SlockRaftFieldMessage(error = error, hint = hint)
        }
        return
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showLabel) {
            SlockFormFieldLabel(
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
                    SlockType.Caption
                        .copy(fontSize = 12.sp, lineHeight = 14.sp)
                        .withSlockFontWeight(if (error != null) FontWeight.Bold else FontWeight.Normal),
                color = if (error != null) SlockColor.AccentRed else SlockColor.TextBlack.copy(alpha = 0.50f),
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
internal fun SlockRaftFieldLabel(
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
    val labelInk = if (disabled) SlockColor.ForegroundDisabled else SlockColor.TextMuted
    val subInk = if (disabled) SlockColor.ForegroundDisabled else SlockColor.ForegroundHint
    Row(
        modifier = if (accessory != null) modifier.fillMaxWidth() else modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style =
                SlockType.Body
                    .copy(fontSize = fontSize, lineHeight = lineHeight, letterSpacing = 0.sp)
                    .withSlockFontWeight(FontWeight.SemiBold),
            color = labelInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (required) {
            Text(
                text = "*",
                style = SlockType.Body.copy(fontSize = fontSize, lineHeight = lineHeight).withSlockFontWeight(FontWeight.SemiBold),
                color = subInk,
                maxLines = 1
            )
        }
        if (optional) {
            Text(
                text = stringResource(MR.strings.common_optional_suffix),
                style = SlockType.Body.copy(fontSize = fontSize, lineHeight = lineHeight),
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
internal fun SlockRaftFieldMessage(
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
            SlockType.Caption
                .copy(fontSize = 12.sp, lineHeight = 16.sp)
                .withSlockFontWeight(if (errorText != null) FontWeight.SemiBold else FontWeight.Normal),
        color = if (errorText != null) SlockColor.DangerStrong else SlockColor.ForegroundHint,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun SlockFormFieldLabel(
    label: String,
    required: Boolean,
    optional: Boolean,
    labelStyle: SlockFormFieldLabelStyle,
    size: SlockFormFieldSize,
    accessory: (@Composable () -> Unit)? = null
) {
    val compact = size == SlockFormFieldSize.Compact
    val uppercase = labelStyle == SlockFormFieldLabelStyle.Uppercase
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
            text = if (uppercase) label.slockDisplayUppercase() else label,
            style =
                SlockType.SectionLabel
                    .copy(
                        fontSize = labelFontSize,
                        lineHeight = labelLineHeight,
                        letterSpacing = if (uppercase) 1.1.sp else 0.sp
                    )
                    .withSlockFontWeight(FontWeight.Bold),
            // react FormField.tsx: compact labels are text-black/60 (= #666666 over
            // white); default labels stay solid text-black. Color is per-size, not
            // per-state.
            color = if (compact) SlockColor.TextBlack.copy(alpha = 0.60f) else SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (required) {
            Text(
                text = "*",
                style = SlockType.SectionLabel.copy(fontSize = labelFontSize, lineHeight = labelLineHeight),
                color = SlockColor.AccentPink,
                maxLines = 1
            )
        }
        if (optional) {
            Text(
                text = stringResource(MR.strings.common_optional_suffix),
                style =
                    SlockType.Caption
                        .copy(fontSize = if (compact) 11.sp else 12.sp, lineHeight = 14.sp)
                        .withSlockFontWeight(if (compact) FontWeight.Bold else FontWeight.Normal),
                color = if (compact) SlockColor.TextMutedGray else SlockColor.TextBlack.copy(alpha = 0.40f),
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
internal fun SlockBrutalTextarea(
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
        SlockBrutalInputField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            enabled = enabled,
            autoFocus = false,
            maxLines = maxLines,
            fieldMinHeight = fieldHeight,
            borderColor = if (hasError) SlockColor.AccentRed else SlockColor.BorderBlack,
            enabledBackgroundColor = if (hasError) SlockFieldErrorTint else SlockColor.SurfaceWhite,
            // react .input-brutal textareas inherit the 16px body font with
            // Tailwind's 1.5 line-height (16/24); 14/18 wrapped one word earlier
            // per line and read visibly smaller than the web baseline.
            textStyle = SlockType.Input.copy(fontSize = 16.sp, lineHeight = 24.sp, color = SlockColor.TextBlack)
        )
        if (slockRaftActive()) {
            SlockRaftTextareaFooter(
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
                    SlockType.Caption
                        .copy(fontSize = 12.sp, lineHeight = 14.sp)
                        .withSlockFontWeight(if (hasError) FontWeight.Bold else FontWeight.Normal),
                color = if (hasError) SlockColor.AccentRed else SlockColor.TextBlack.copy(alpha = 0.60f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (showCounter && limit != null) {
                val overLimit = value.length > limit
                Text(
                    text = "${value.length}/$limit",
                    style =
                        SlockType.Caption
                            .copy(fontSize = 12.sp, lineHeight = 14.sp)
                            .withSlockFontWeight(if (overLimit || hasError) FontWeight.Bold else FontWeight.Normal),
                    color = if (overLimit || hasError) SlockColor.AccentRed else SlockColor.TextBlack.copy(alpha = 0.50f),
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
internal fun SlockBrutalTextarea(
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
        SlockBrutalInputField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            enabled = enabled,
            autoFocus = false,
            maxLines = maxLines,
            fieldMinHeight = fieldHeight,
            borderColor = if (hasError) SlockColor.AccentRed else SlockColor.BorderBlack,
            enabledBackgroundColor = if (hasError) SlockFieldErrorTint else SlockColor.SurfaceWhite,
            // react .input-brutal textareas inherit the 16px body font with
            // Tailwind's 1.5 line-height (16/24); 14/18 wrapped one word earlier
            // per line and read visibly smaller than the web baseline.
            textStyle = SlockType.Input.copy(fontSize = 16.sp, lineHeight = 24.sp, color = SlockColor.TextBlack)
        )
        if (slockRaftActive()) {
            SlockRaftTextareaFooter(
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
                    SlockType.Caption
                        .copy(fontSize = 12.sp, lineHeight = 14.sp)
                        .withSlockFontWeight(if (hasError) FontWeight.Bold else FontWeight.Normal),
                color = if (hasError) SlockColor.AccentRed else SlockColor.TextBlack.copy(alpha = 0.60f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (showCounter && limit != null) {
                val overLimit = value.text.length > limit
                Text(
                    text = "${value.text.length}/$limit",
                    style =
                        SlockType.Caption
                            .copy(fontSize = 12.sp, lineHeight = 14.sp)
                            .withSlockFontWeight(if (overLimit) FontWeight.Bold else FontWeight.Normal),
                    color = if (overLimit) SlockColor.AccentRed else SlockColor.TextBlack.copy(alpha = 0.45f),
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * raft-ui textarea footer: help / error on the left (see [SlockRaftFieldMessage]) and the
 * `TextareaCounter` on the right (mono 12/16 hint ink, `--danger-strong` once over).
 */
@Composable
private fun SlockRaftTextareaFooter(
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
            SlockRaftFieldMessage(error = error, hint = hint, maxLines = 1)
        }
        if (count != null && limit != null) {
            Text(
                text = "$count/$limit",
                style = SlockType.Caption.copy(fontFamily = SlockMonoFamily, fontSize = 12.sp, lineHeight = 16.sp),
                color = if (count > limit) SlockColor.DangerStrong else SlockColor.ForegroundHint,
                maxLines = 1
            )
        }
    }
}

@Composable
internal fun SlockSlugInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    placeholderColor: Color = SlockColor.TextMutedGray,
    enabled: Boolean = true,
    // react .input-brutal outer height: 24px line + 16px py-2 + 4px border-2 =
    // 44px. Kuikly draws the Row border as a FOREGROUND over children, so with
    // 44dp outer the visible tile inside the 2dp border = 40dp = react's
    // items-stretch span (120px @3x).
    fieldHeight: Dp = 44.dp
) {
    if (slockRaftActive()) {
        SlockRaftPrefixedInputFrame(prefix = "/", enabled = enabled, fieldHeight = fieldHeight, modifier = modifier) {
            TextField(
                value = value,
                onValueChange = { if (enabled) onValueChange(it) },
                placeholder = placeholder,
                placeholderColor = if (placeholderColor == SlockColor.TextMutedGray) SlockColor.ForegroundHint else placeholderColor,
                autoFocus = false,
                maxLines = 1,
                textStyle = SlockType.Input.copy(
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    color = if (enabled) SlockColor.TextBlack else SlockColor.ForegroundDisabled
                ),
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart
            )
        }
        return
    }
    val shadowOffset = SlockShadow.BrutalSmall
    // react's disabled look is CSS group opacity-60: each part flattens against
    // what's beneath it INSIDE the group first, then the whole result dims over
    // the page. Per-part alpha only equals that over the page backdrop, so parts
    // stacked on the tile (border, slash) drift warm. Precomputed opaque react
    // composites instead (white backdrop, matching the disabled-button precedent):
    // shadow #141111 x0.6 = #727070; border/text black x0.6 = #666666.
    val shadowColor = if (enabled) SlockColor.ShadowBlack else Color(0xFF727070)
    val borderColor = if (enabled) SlockColor.BorderBlack else Color(0xFF666666)
    val contentColor = if (enabled) SlockColor.TextBlack else Color(0xFF666666)
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
                    .background(SlockColor.SurfaceWhite)
                    .border(SlockStroke.Default, borderColor),
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
                        .background(if (enabled) SlockSlugInputPrefixBackground else SlockSlugInputDisabledPrefixBackground)
                        // Only the right divider is visible (the other 3 sides sit
                        // under the Row's foreground border); react divider is
                        // border-r-2 = 2px, so Default, not Thin.
                        .border(SlockStroke.Default, borderColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "/",
                    // Empirically measured on the pinned render build (run
                    // 29142637461): react's slash ink is dead-centered in the
                    // tile; android's sits 1.5dp low under the even-split span.
                    // Platform-gated correction - see
                    // slockSlugSlashCenteringOffsetDp() for the measurement
                    // provenance and the do-not-copy-blind note for iOS/OHOS.
                    modifier = Modifier.offset(y = slockSlugSlashCenteringOffsetDp().dp),
                    style = SlockType.ButtonBold.copy(fontSize = 16.sp, lineHeight = 18.sp),
                    // react slash = text-black/70. Disabled must match react's GROUP
                    // flatten (glyph over tile first, then x0.6 over white) = opaque
                    // #94928A; a plain alpha over the dim tile lets ~2x the yellow
                    // through (round-2 adversarial review, Delta(0,3,10)/px).
                    color = if (enabled) SlockColor.TextBlack.copy(alpha = 0.70f) else Color(0xFF94928A),
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
                textStyle = SlockType.Input.copy(fontSize = 14.sp, lineHeight = 18.sp, color = contentColor),
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart
            )
        }
    }
}

/**
 * Value-aware [SlockSlugInput] overload (#130): callers hold a caller-local
 * [TextFieldValue] so CJK composition and cursor/selection survive typing, while
 * the durable slug stays a `.text` String projection at the caller. Mirrors the
 * String overload above exactly; the String overload is retained and is removed
 * later by task #131.
 */
@Composable
internal fun SlockSlugInput(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    placeholderColor: Color = SlockColor.TextMutedGray,
    enabled: Boolean = true,
    // react .input-brutal outer height: 24px line + 16px py-2 + 4px border-2 =
    // 44px. Kuikly draws the Row border as a FOREGROUND over children, so with
    // 44dp outer the visible tile inside the 2dp border = 40dp = react's
    // items-stretch span (120px @3x).
    fieldHeight: Dp = 44.dp
) {
    if (slockRaftActive()) {
        SlockRaftPrefixedInputFrame(prefix = "/", enabled = enabled, fieldHeight = fieldHeight, modifier = modifier) {
            TextField(
                value = value,
                onValueChange = { if (enabled) onValueChange(it) },
                placeholder = placeholder,
                placeholderColor = if (placeholderColor == SlockColor.TextMutedGray) SlockColor.ForegroundHint else placeholderColor,
                autoFocus = false,
                maxLines = 1,
                textStyle = SlockType.Input.copy(
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    color = if (enabled) SlockColor.TextBlack else SlockColor.ForegroundDisabled
                ),
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart
            )
        }
        return
    }
    val shadowOffset = SlockShadow.BrutalSmall
    // react's disabled look is CSS group opacity-60: each part flattens against
    // what's beneath it INSIDE the group first, then the whole result dims over
    // the page. Per-part alpha only equals that over the page backdrop, so parts
    // stacked on the tile (border, slash) drift warm. Precomputed opaque react
    // composites instead (white backdrop, matching the disabled-button precedent):
    // shadow #141111 x0.6 = #727070; border/text black x0.6 = #666666.
    val shadowColor = if (enabled) SlockColor.ShadowBlack else Color(0xFF727070)
    val borderColor = if (enabled) SlockColor.BorderBlack else Color(0xFF666666)
    val contentColor = if (enabled) SlockColor.TextBlack else Color(0xFF666666)
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
                    .background(SlockColor.SurfaceWhite)
                    .border(SlockStroke.Default, borderColor),
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
                        .background(if (enabled) SlockSlugInputPrefixBackground else SlockSlugInputDisabledPrefixBackground)
                        // Only the right divider is visible (the other 3 sides sit
                        // under the Row's foreground border); react divider is
                        // border-r-2 = 2px, so Default, not Thin.
                        .border(SlockStroke.Default, borderColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "/",
                    // Empirically measured on the pinned render build (run
                    // 29142637461): react's slash ink is dead-centered in the
                    // tile; android's sits 1.5dp low under the even-split span.
                    // Platform-gated correction - see
                    // slockSlugSlashCenteringOffsetDp() for the measurement
                    // provenance and the do-not-copy-blind note for iOS/OHOS.
                    modifier = Modifier.offset(y = slockSlugSlashCenteringOffsetDp().dp),
                    style = SlockType.ButtonBold.copy(fontSize = 16.sp, lineHeight = 18.sp),
                    // react slash = text-black/70. Disabled must match react's GROUP
                    // flatten (glyph over tile first, then x0.6 over white) = opaque
                    // #94928A; a plain alpha over the dim tile lets ~2x the yellow
                    // through (round-2 adversarial review, Delta(0,3,10)/px).
                    color = if (enabled) SlockColor.TextBlack.copy(alpha = 0.70f) else Color(0xFF94928A),
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
                textStyle = SlockType.Input.copy(fontSize = 14.sp, lineHeight = 18.sp, color = contentColor),
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
internal fun SlockRaftPrefixedInputFrame(
    prefix: String,
    enabled: Boolean,
    fieldHeight: Dp,
    modifier: Modifier = Modifier,
    field: @Composable RowScope.() -> Unit
) {
    val shape = RoundedCornerShape(SlockRadius.Control)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(fieldHeight)
                .clip(shape)
                .background(if (enabled) SlockColor.Card else SlockColor.FillMuted, shape)
                .border(SlockStroke.Control, if (enabled) SlockColor.LineField else SlockColor.BorderHairline, shape),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .background(SlockColor.FillMuted)
                    .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = prefix,
                style = SlockType.Caption.copy(fontFamily = SlockMonoFamily, fontSize = 13.sp, lineHeight = 20.sp),
                color = if (enabled) SlockColor.ForegroundHint else SlockColor.ForegroundDisabled,
                maxLines = 1
            )
        }
        Box(modifier = Modifier.width(SlockStroke.Thin).fillMaxHeight().background(SlockColor.BorderHairline))
        field()
    }
}

@Composable
internal fun SlockCheckboxLabelRow(
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
        SlockCheckbox(checked = checked, onCheckedChange = {}, enabled = enabled, size = size)
        val raft = slockRaftActive()
        Text(
            text = label,
            style = SlockType.ButtonBold.copy(fontSize = 14.sp, lineHeight = if (raft) 20.sp else 18.sp),
            color =
                when {
                    enabled -> SlockColor.TextBlack
                    raft -> SlockColor.ForegroundDisabled
                    else -> SlockColor.TextMutedGray
                },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
