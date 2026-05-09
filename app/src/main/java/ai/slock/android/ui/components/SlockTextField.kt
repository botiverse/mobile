package ai.slock.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    fieldValue: TextFieldValue? = null,
    onFieldValueChange: ((TextFieldValue) -> Unit)? = null,
    inputModifier: Modifier = Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingContent: (@Composable () -> Unit)? = null,
    bottomContent: (@Composable () -> Unit)? = null,
    minContentHeight: Dp = 48.dp,
    contentPaddingVertical: Dp = 12.dp,
    inputFontWeight: FontWeight = FontWeight.Medium,
    inputFontFamily: FontFamily? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val focusedVisual = (focused || forceFocused) && enabled
    val shadow = if (focusedVisual) SlockShadow.Default else SlockShadow.Small
    val shadowPadding = SlockShadow.Default
    val displayValue = fieldValue?.text ?: value
    val fieldModifier = Modifier
        .fillMaxWidth()
        .padding(end = shadowPadding.offsetX, bottom = shadowPadding.offsetY)
        .slockHardShadow(shadow)
        .background(SlockColor.SurfaceWhite, RoundedCornerShape(SlockRadius.Xs))
        .border(SlockStroke.Default, SlockColor.BorderBlack, RoundedCornerShape(SlockRadius.Xs))
        .alpha(if (enabled) 1f else 0.45f)
        .onFocusChanged { focused = it.isFocused }
        .then(inputModifier)
    val textStyle = SlockType.Body.copy(
        color = SlockColor.TextBlack,
        fontWeight = inputFontWeight,
        fontFamily = inputFontFamily ?: SlockType.Body.fontFamily
    )
    val decorationBox: @Composable (@Composable () -> Unit) -> Unit = { innerTextField ->
        Column(
            modifier = Modifier
                .defaultMinSize(minHeight = minContentHeight)
                .padding(horizontal = SlockSpacing.Md, vertical = contentPaddingVertical),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) {
                    if (displayValue.isEmpty() && placeholder.isNotEmpty()) {
                        Text(
                            text = placeholder,
                            style = SlockType.Body.copy(fontWeight = inputFontWeight, fontFamily = inputFontFamily ?: SlockType.Body.fontFamily),
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
            if (bottomContent != null) {
                bottomContent()
            }
        }
    }

    Column(modifier = modifier) {
        if (label != null) {
            Text(
                text = label,
                style = SlockType.Button.copy(fontWeight = FontWeight.Bold),
                color = SlockColor.TextBlack,
                modifier = Modifier.padding(bottom = SlockSpacing.Xs)
            )
        }
        if (fieldValue != null && onFieldValueChange != null) {
            BasicTextField(
                value = fieldValue,
                onValueChange = onFieldValueChange,
                enabled = enabled,
                singleLine = singleLine,
                minLines = minLines,
                maxLines = maxLines,
                textStyle = textStyle,
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                visualTransformation = visualTransformation,
                cursorBrush = SolidColor(SlockColor.TextBlack),
                interactionSource = remember { MutableInteractionSource() },
                modifier = fieldModifier,
                decorationBox = decorationBox
            )
        } else {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = singleLine,
                minLines = minLines,
                maxLines = maxLines,
                textStyle = textStyle,
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                visualTransformation = visualTransformation,
                cursorBrush = SolidColor(SlockColor.TextBlack),
                interactionSource = remember { MutableInteractionSource() },
                modifier = fieldModifier,
                decorationBox = decorationBox
            )
        }
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
