package ai.slock.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockRadius
import ai.slock.android.ui.theme.SlockShadow
import ai.slock.android.ui.theme.SlockSpacing
import ai.slock.android.ui.theme.SlockStroke
import ai.slock.android.ui.theme.SlockType
import ai.slock.android.ui.theme.slockHardShadow

@Composable
fun SlockTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    enabled: Boolean = true,
    isError: Boolean = false,
    errorText: String? = null,
    reserveErrorSpace: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 4,
    forceFocused: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingContent: (@Composable () -> Unit)? = null
) {
    var focused by remember { mutableStateOf(false) }
    val focusedVisual = (focused || forceFocused) && enabled
    val shadow = if (focusedVisual) SlockShadow.Default else SlockShadow.Small
    val shadowPadding = SlockShadow.Default

    Column(modifier = modifier) {
        if (label != null) {
            Text(
                text = label,
                style = SlockType.Button.copy(fontWeight = FontWeight.Bold),
                color = SlockColor.TextBlack,
                modifier = Modifier.padding(bottom = SlockSpacing.Xs)
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            textStyle = SlockType.Body.copy(
                color = SlockColor.TextBlack,
                fontWeight = FontWeight.Medium
            ),
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
            cursorBrush = SolidColor(SlockColor.TextBlack),
            interactionSource = remember { MutableInteractionSource() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = shadowPadding.offsetX, bottom = shadowPadding.offsetY)
                .slockHardShadow(shadow)
                .background(SlockColor.SurfaceWhite, RoundedCornerShape(SlockRadius.Xs))
                .border(SlockStroke.Default, SlockColor.BorderBlack, RoundedCornerShape(SlockRadius.Xs))
                .alpha(if (enabled) 1f else 0.45f)
                .onFocusChanged { focused = it.isFocused },
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .padding(horizontal = SlockSpacing.Md, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (value.isEmpty() && placeholder.isNotEmpty()) {
                            Text(
                                text = placeholder,
                                style = SlockType.Body,
                                color = SlockColor.TextMutedGray,
                                maxLines = if (singleLine) 1 else maxLines,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        innerTextField()
                    }
                    if (trailingContent != null) {
                        Box(modifier = Modifier.padding(start = SlockSpacing.Sm)) {
                            trailingContent()
                        }
                    }
                }
            }
        )
        if (reserveErrorSpace || (isError && !errorText.isNullOrBlank())) {
            Text(
                text = errorText.takeUnless { it.isNullOrBlank() } ?: " ",
                style = SlockType.Caption,
                color = SlockColor.AccentRed,
                modifier = Modifier
                    .padding(top = SlockSpacing.Xs)
                    .alpha(if (isError && !errorText.isNullOrBlank()) 1f else 0f)
            )
        }
    }
}
