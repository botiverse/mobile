package ai.slock.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.ColumnScope
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.text.KeyboardActions
import com.tencent.kuikly.compose.foundation.text.KeyboardOptions
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.Brush
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.graphics.SolidColor
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.text.TextStyle
import com.tencent.kuikly.compose.ui.text.font.FontFamily
import com.tencent.kuikly.compose.ui.text.font.FontStyle
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.font.KuiklyFont
import com.tencent.kuikly.compose.ui.text.style.TextAlign
import com.tencent.kuikly.compose.ui.text.style.TextDecoration
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp

internal enum class AuthButtonTone {
    Pink,
    Cyan,
    White,
    Black,
    Disabled
}

@Composable
internal fun AuthScaffold(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val statusBarHeight = LocalActivity.current.pageData.statusBarHeight
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SlockColor.SurfaceCream)
            .padding(start = 16.dp, top = (statusBarHeight + 24).dp, end = 16.dp, bottom = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(end = 4.dp, bottom = 4.dp)) {
            Box(Modifier.fillMaxSize().offset(x = 4.dp, y = 4.dp).background(SlockColor.ShadowBlack))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SlockColor.SurfaceWhite)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .padding(horizontal = 42.dp, vertical = 44.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.Start,
                content = content
            )
        }
    }
}

@Composable
internal fun AuthBrandBlock(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.Start
    ) {
        AuthLogoBadge("SLOCK")
        Text(
            text = "WHERE HUMANS AND AI AGENTS\nCOLLABORATE",
            style = SlockType.SectionLabel.copy(
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 1.4.sp,
                color = SlockColor.TextBlack.copy(alpha = 0.52f)
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun AuthLogoBadge(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.width(166.dp).height(64.dp).padding(end = 4.dp, bottom = 4.dp)) {
        Box(Modifier.fillMaxSize().offset(x = 4.dp, y = 4.dp).background(SlockColor.ShadowBlack))
        Box(
            modifier = Modifier.fillMaxSize().background(SlockColor.TextBlack),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                style = SlockType.PageTitle.copy(
                    fontSize = 32.sp,
                    lineHeight = 38.sp,
                    color = SlockColor.BrandYellow
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun AuthFormColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.Start,
        content = content
    )
}

@Composable
internal fun AuthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    required: Boolean = false,
    optional: Boolean = false,
    enabled: Boolean = true,
    isError: Boolean = false,
    errorText: String? = null,
    forceFocused: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default
) {
    var isFocused by remember { mutableStateOf(false) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        AuthFieldLabel(label = label, required = required, optional = optional)
        val focused = enabled && (isFocused || forceFocused)
        val shadowOffset = if (focused) 4.dp else 2.dp
        val borderColor = if (isError) SlockColor.AccentRed else SlockColor.BorderBlack
        Box(modifier = Modifier.fillMaxWidth().height(56.dp).padding(end = shadowOffset, bottom = shadowOffset)) {
            Box(Modifier.fillMaxSize().offset(x = shadowOffset, y = shadowOffset).background(SlockColor.ShadowBlack))
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (enabled) SlockColor.SurfaceWhite else SlockColor.EmptyStateGray)
                    .border(SlockStroke.Default, borderColor)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (value.isEmpty() && placeholder.isNotBlank()) {
                    Text(
                        text = placeholder,
                        style = SlockType.Input.copy(color = SlockColor.TextMutedGray),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextField(
                    value = value,
                    placeholder = "",
                    autoFocus = false,
                    onFocus = { isFocused = true },
                    onBlur = { isFocused = false },
                    onValueChange = onValueChange,
                    textStyle = SlockType.Input.copy(color = SlockColor.TextBlack),
                    cursorBrush = SolidColor(SlockColor.TextBlack),
                    maxLines = 1,
                    keyboardOptions = keyboardOptions,
                    keyboardActions = keyboardActions,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        if (isError && !errorText.isNullOrBlank()) {
            Text(
                text = errorText,
                style = SlockType.Caption.copy(color = SlockColor.AccentRed),
                color = SlockColor.AccentRed
            )
        }
    }
}

@Composable
internal fun AuthFieldLabel(label: String, required: Boolean = false, optional: Boolean = false) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = SlockType.Button.copy(fontSize = 16.sp, lineHeight = 20.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack
        )
        if (required) {
            Text(
                text = "*",
                style = SlockType.Button.copy(fontSize = 16.sp, lineHeight = 20.sp, color = SlockColor.AccentPink),
                color = SlockColor.AccentPink
            )
        } else if (optional) {
            Text(
                text = "optional",
                style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
                color = SlockColor.TextMutedGray
            )
        }
    }
}

@Composable
internal fun AuthActionButton(
    text: String,
    onClick: () -> Unit,
    tone: AuthButtonTone,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    preserveDisabledVisual: Boolean = false,
    leadingContent: (@Composable () -> Unit)? = null
) {
    val background = when (tone) {
        AuthButtonTone.Pink -> SlockColor.AccentPink
        AuthButtonTone.Cyan -> SlockColor.AccentCyan
        AuthButtonTone.White -> SlockColor.SurfaceWhite
        AuthButtonTone.Black -> SlockColor.TextBlack
        AuthButtonTone.Disabled -> SlockColor.EmptyStateGray
    }
    val contentColor = if (tone == AuthButtonTone.Black) SlockColor.SurfaceWhite else SlockColor.TextBlack
    val buttonBackground = if (enabled || preserveDisabledVisual) background else SlockColor.EmptyStateGray
    Box(modifier = modifier.fillMaxWidth().height(56.dp).padding(end = 2.dp, bottom = 2.dp)) {
        Box(Modifier.fillMaxSize().offset(x = 2.dp, y = 2.dp).background(SlockColor.ShadowBlack))
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(buttonBackground)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .clickable(enabled = enabled && !loading, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (leadingContent != null) {
                    Box(Modifier.padding(end = SlockSpacing.Md), contentAlignment = Alignment.Center) {
                        leadingContent()
                    }
                }
                Text(
                    text = if (loading && !text.endsWith("...")) "$text..." else text,
                    style = SlockType.Button.copy(fontSize = 16.sp, lineHeight = 20.sp, color = contentColor),
                    color = contentColor,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
internal fun AuthDivider() {
    Box(Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
}

@Composable
internal fun AuthTextLink(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = SlockType.Body.copy(
            fontSize = 18.sp,
            lineHeight = 24.sp,
            color = SlockColor.TextMutedGray,
            textDecoration = TextDecoration.Underline
        ),
        color = SlockColor.TextMutedGray,
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
internal fun AuthInlineAction(leading: String, action: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = leading,
            style = SlockType.Body.copy(fontSize = 18.sp, lineHeight = 24.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack
        )
        Spacer(Modifier.width(SlockSpacing.Xs))
        Text(
            text = action,
            style = SlockType.Body.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                lineHeight = 24.sp,
                color = SlockColor.AccentPink,
                textDecoration = TextDecoration.Underline
            ),
            color = SlockColor.AccentPink,
            modifier = Modifier.clickable(onClick = onClick)
        )
    }
}

@Composable
internal fun AuthError(message: String?) {
    if (!message.isNullOrBlank()) {
        Text(
            text = message,
            style = SlockType.Body.copy(fontSize = 16.sp, lineHeight = 22.sp, color = SlockColor.AccentRed),
            color = SlockColor.AccentRed
        )
    }
}

@Composable
internal fun AuthSuccessNotice(title: String, detail: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SlockColor.AccentCyan)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .padding(SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        Text(
            text = title,
            style = SlockType.Button.copy(fontWeight = FontWeight.Bold, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack
        )
        Text(
            text = detail,
            style = SlockType.Body.copy(color = SlockColor.TextBlack),
            color = SlockColor.TextBlack
        )
    }
}

@Composable
internal fun ProviderGlyph(text: String, color: Color) {
    Text(
        text = text,
        style = SlockType.Button.copy(fontSize = 18.sp, lineHeight = 20.sp, color = color),
        color = color
    )
}

@Composable
private fun AuthComponentPreview() {
    AuthScaffold {
        AuthBrandBlock()
        Text(
            text = "Sign in",
            style = SlockType.PageTitle.copy(fontSize = 32.sp, lineHeight = 38.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack
        )
        AuthTextField(value = "", onValueChange = {}, label = "Email", required = true, forceFocused = true)
        AuthTextField(value = "not-an-email", onValueChange = {}, label = "Email", required = true, isError = true, errorText = "Enter a valid email.")
        AuthActionButton(text = "Sign in", onClick = {}, tone = AuthButtonTone.Pink)
        AuthActionButton(text = "Continue with GitHub", onClick = {}, tone = AuthButtonTone.Black, preserveDisabledVisual = true)
        AuthSuccessNotice("Reset link sent", "Check artin@slock.ai for your secure link.")
    }
}
