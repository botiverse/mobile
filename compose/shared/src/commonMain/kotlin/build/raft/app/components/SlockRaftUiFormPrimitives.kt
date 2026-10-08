package build.raft.app.components

import build.raft.app.slockDisplayUppercase
import build.raft.app.SlockColor
import build.raft.app.slockSlugSlashCenteringOffsetDp
import build.raft.app.SlockShadow
import build.raft.app.SlockStroke
import build.raft.app.SlockType
import build.raft.app.TextField
import build.raft.app.withSlockFontWeight
import build.raft.app.resources.MR
import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.fillMaxHeight
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.layout.widthIn
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.input.TextFieldValue
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
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
    SlockColor.BrandYellow.copy(alpha = 0.30f) // react-parity: intentional bg-soft-signal/30 wash
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
    content: @Composable () -> Unit
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showLabel) {
            SlockFormFieldLabel(
                label = label,
                required = required,
                optional = optional,
                labelStyle = labelStyle,
                size = size
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

@Composable
private fun SlockFormFieldLabel(
    label: String,
    required: Boolean,
    optional: Boolean,
    labelStyle: SlockFormFieldLabelStyle,
    size: SlockFormFieldSize
) {
    val compact = size == SlockFormFieldSize.Compact
    val uppercase = labelStyle == SlockFormFieldLabelStyle.Uppercase
    val labelFontSize = if (compact) 11.sp else 14.sp
    val labelLineHeight = if (compact) 14.sp else 18.sp
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
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
            enabledBackgroundColor = if (hasError) Color(0xFFFFF4F4) else SlockColor.SurfaceWhite,
            // react .input-brutal textareas inherit the 16px body font with
            // Tailwind's 1.5 line-height (16/24); 14/18 wrapped one word earlier
            // per line and read visibly smaller than the web baseline.
            textStyle = SlockType.Input.copy(fontSize = 16.sp, lineHeight = 24.sp, color = SlockColor.TextBlack)
        )
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
            enabledBackgroundColor = if (hasError) Color(0xFFFFF4F4) else SlockColor.SurfaceWhite,
            // react .input-brutal textareas inherit the 16px body font with
            // Tailwind's 1.5 line-height (16/24); 14/18 wrapped one word earlier
            // per line and read visibly smaller than the web baseline.
            textStyle = SlockType.Input.copy(fontSize = 16.sp, lineHeight = 24.sp, color = SlockColor.TextBlack)
        )
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
        Text(
            text = label,
            style = SlockType.ButtonBold.copy(fontSize = 14.sp, lineHeight = 18.sp),
            color = if (enabled) SlockColor.TextBlack else SlockColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
