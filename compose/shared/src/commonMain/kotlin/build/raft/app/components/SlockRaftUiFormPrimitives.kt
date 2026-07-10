package build.raft.app.components

import build.raft.app.SlockColor
import build.raft.app.SlockShadow
import build.raft.app.SlockStroke
import build.raft.app.SlockType
import build.raft.app.TextField
import build.raft.app.withSlockFontWeight
import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
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

internal enum class SlockFormFieldLabelStyle {
    Uppercase,
    Plain
}

internal enum class SlockFormFieldSize {
    Default,
    Compact
}

internal val SlockSlugInputPrefixBackground = SlockColor.BrandYellow.copy(alpha = 0.30f)
// react's disabled look is the WHOLE control at opacity-60, so the tile's
// effective alpha is 0.30 x 0.60 = 0.18 (#FFF7DD over white). Color.copy(alpha=)
// REPLACES alpha - the old 0.55 made the disabled tile MORE saturated than enabled.
internal val SlockSlugInputDisabledPrefixBackground = SlockColor.BrandYellow.copy(alpha = 0.18f)

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
            text = if (uppercase) label.uppercase() else label,
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
                text = "(optional)",
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
            textStyle = SlockType.Input.copy(fontSize = 14.sp, lineHeight = 18.sp, color = SlockColor.TextBlack)
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
            textStyle = SlockType.Input.copy(fontSize = 14.sp, lineHeight = 18.sp, color = SlockColor.TextBlack)
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
    fieldHeight: Dp = 42.dp
) {
    val shadowOffset = SlockShadow.BrutalSmall
    // Same react uniform opacity-60 rule as the text below: every part of the
    // disabled control dims by x0.60 (the old 0.40/0.45 were pre-rule guesses).
    val shadowColor = if (enabled) SlockColor.ShadowBlack else SlockColor.ShadowBlack.copy(alpha = 0.60f)
    val borderColor = if (enabled) SlockColor.BorderBlack else SlockColor.BorderBlack.copy(alpha = 0.60f)
    // react text-black @ fixture opacity-60 over white = #666666, i.e. black @ 0.60 (visual-fix pilot, AI analysis 2026-07-09)
    val contentColor = if (enabled) SlockColor.TextBlack else SlockColor.TextBlack.copy(alpha = 0.60f)
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
                        .width(32.dp)
                        .height(fieldHeight)
                        .background(if (enabled) SlockSlugInputPrefixBackground else SlockSlugInputDisabledPrefixBackground)
                        .border(SlockStroke.Thin, borderColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "/",
                    style = SlockType.ButtonBold.copy(fontSize = 16.sp, lineHeight = 18.sp),
                    color = contentColor.copy(alpha = 0.70f),
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
