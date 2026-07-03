package build.raft.app.components

import build.raft.app.SlockColor
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

@Composable
internal fun SlockFormField(
    label: String,
    modifier: Modifier = Modifier,
    required: Boolean = false,
    optional: Boolean = false,
    hint: String? = null,
    error: String? = null,
    labelStyle: SlockFormFieldLabelStyle = SlockFormFieldLabelStyle.Uppercase,
    size: SlockFormFieldSize = SlockFormFieldSize.Default,
    content: @Composable () -> Unit
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SlockFormFieldLabel(
            label = label,
            required = required,
            optional = optional,
            labelStyle = labelStyle,
            size = size
        )
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
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (uppercase) label.uppercase() else label,
            style =
                SlockType.SectionLabel
                    .copy(
                        fontSize = if (compact) 12.sp else 14.sp,
                        lineHeight = if (compact) 14.sp else 18.sp,
                        letterSpacing = if (uppercase) 1.1.sp else 0.sp
                    )
                    .withSlockFontWeight(FontWeight.Bold),
            color = if (compact) SlockColor.TextBlack.copy(alpha = 0.60f) else SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (required) {
            Text(
                text = "*",
                style = SlockType.SectionLabel.copy(fontSize = if (compact) 12.sp else 14.sp, lineHeight = if (compact) 14.sp else 18.sp),
                color = SlockColor.AccentPink,
                maxLines = 1
            )
        }
        if (optional) {
            Text(
                text = "(optional)",
                style =
                    SlockType.Caption
                        .copy(fontSize = if (compact) 11.sp else 12.sp, lineHeight = if (compact) 13.sp else 14.sp)
                        .withSlockFontWeight(FontWeight.Normal),
                color = SlockColor.TextBlack.copy(alpha = 0.40f),
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
internal fun SlockSlugInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fieldHeight: Dp = 42.dp
) {
    val shadowOffset = 3.dp
    val shadowColor = if (enabled) SlockColor.ShadowBlack else SlockColor.ShadowBlack.copy(alpha = 0.40f)
    val borderColor = if (enabled) SlockColor.BorderBlack else SlockColor.BorderBlack.copy(alpha = 0.45f)
    val contentColor = if (enabled) SlockColor.TextBlack else SlockColor.TextBlack.copy(alpha = 0.45f)
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
                        .background(Color(0xFFFFF7D6).copy(alpha = if (enabled) 1f else 0.55f))
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
                placeholder = "",
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
