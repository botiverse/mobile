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
import com.tencent.kuikly.compose.foundation.layout.widthIn
import com.tencent.kuikly.compose.foundation.text.KeyboardActions
import com.tencent.kuikly.compose.foundation.text.KeyboardOptions
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.SolidColor
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextAlign
import com.tencent.kuikly.compose.ui.text.style.TextDecoration
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp

internal enum class AuthProvider {
    Google,
    GitHub
}

internal enum class AuthButtonTone {
    Pink,
    Cyan,
    White,
    Black,
    Disabled
}

private val AuthInputTextStyle =
    SlockType.Input.copy(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        color = SlockColor.TextBlack
    )

private val AuthInputPlaceholderColor = SlockColor.TextBlack.copy(alpha = 0.4f)

@Composable
internal fun AuthScaffold(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val statusBarHeight = LocalActivity.current.pageData.statusBarHeight
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SlockColor.SurfaceWhite)
    ) {
        AuthTopBar(statusBarHeight)
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 36.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 314.dp)
                    .padding(top = 136.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = content
            )
        }
    }
}

@Composable
private fun AuthTopBar(statusBarHeight: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height((statusBarHeight + 52).dp)
            .background(SlockColor.BrandYellow)
    ) {
        Box(Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack).align(Alignment.BottomCenter))
        Row(
            modifier = Modifier
                .padding(top = statusBarHeight.dp)
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AuthSmallBrand()
        }
    }
}

@Composable
private fun AuthSmallBrand() {
    SlockCachedImage(
        source = SlockLockupLightPngDataUri,
        contentDescription = null,
        modifier = Modifier.width(143.dp).height(31.dp)
    )
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
internal fun AuthCenterMark(modifier: Modifier = Modifier) {
    SlockCachedImage(
        source = SlockIconPngDataUri,
        contentDescription = null,
        modifier = modifier.width(44.dp).height(41.dp)
    )
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
        verticalArrangement = Arrangement.spacedBy(10.dp),
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
        Box(modifier = Modifier.fillMaxWidth().height(44.dp + shadowOffset).padding(end = shadowOffset, bottom = shadowOffset)) {
            Box(Modifier.fillMaxSize().offset(x = shadowOffset, y = shadowOffset).background(SlockColor.ShadowBlack))
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (enabled) SlockColor.SurfaceWhite else SlockColor.EmptyStateGray)
                    .border(SlockStroke.Default, borderColor)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (value.isEmpty() && placeholder.isNotBlank()) {
                    Text(
                        text = placeholder,
                        style = AuthInputTextStyle.copy(color = AuthInputPlaceholderColor),
                        color = AuthInputPlaceholderColor,
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
                    textStyle = AuthInputTextStyle,
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
            style = SlockType.Button.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack
        )
        if (required) {
            Text(
                text = "*",
                style = SlockType.Button.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.AccentPink),
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
    Box(modifier = modifier.fillMaxWidth().height(36.dp).padding(end = 2.dp, bottom = 2.dp)) {
        Box(Modifier.fillMaxSize().offset(x = 2.dp, y = 2.dp).background(SlockColor.ShadowBlack))
        Button(
            onClick = if (enabled && !loading) onClick else ({}),
            modifier = Modifier
                .fillMaxSize()
                .background(buttonBackground)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (leadingContent != null) {
                    Box(Modifier.padding(end = 8.dp), contentAlignment = Alignment.Center) {
                        leadingContent()
                    }
                }
                Text(
                    text = if (loading && !text.endsWith("...")) "$text..." else text,
                    style = SlockType.Button.copy(fontSize = 12.sp, lineHeight = 16.sp, color = contentColor),
                    color = contentColor,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
internal fun AuthDivider() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.weight(1f).height(SlockStroke.Default).background(SlockColor.BorderBlack))
        Text(
            text = "OR",
            style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 12.sp, color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray
        )
        Box(Modifier.weight(1f).height(SlockStroke.Default).background(SlockColor.BorderBlack))
    }
}

@Composable
internal fun AuthTextLink(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = SlockType.Body.copy(
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = SlockColor.TextMutedGray,
            textDecoration = TextDecoration.Underline
        ),
        color = SlockColor.TextMutedGray,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        textAlign = TextAlign.Center
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
            style = SlockType.Body.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack
        )
        Spacer(Modifier.width(SlockSpacing.Xs))
        Text(
            text = action,
            style = SlockType.Body.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                lineHeight = 16.sp,
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
internal fun AuthProviderIcon(provider: AuthProvider) {
    val source =
        when (provider) {
            AuthProvider.Google -> GoogleIconDataUri
            AuthProvider.GitHub -> GitHubIconDataUri
        }
    SlockCachedImage(
        source = source,
        contentDescription = null,
        modifier = Modifier.size(18.dp)
    )
}

internal const val SlockLockupLightPngDataUri =
    "data:image/png;base64,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"

private const val SlockIconPngDataUri =
    "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACwAAAApCAYAAABOScuyAAAABmJLR0QA/wD/AP+gvaeTAAAEN0lEQVRYhcXYW4hVVRzH8c" +
    "+M40SlQ0oqXYzoNuY0Iypl9NLdopsPlQ9BdKFejOgGRUXQU4UEWfTQSxSVUFDRgw8SCgUS9KDYjCNd1FIIw0Ttps2oYw/r7M4+66y9z2XO6A827Mt//d" +
    "d3r+t//bucGl2AQQxhUeX+Iozja7yE71IFu6YYrBeXYikWYgBXYW6DcmN4FB/GHzoJfG4EthT9mNamvxNYjRcwkb1sB7hP6MJBoTuHKvcz2wRrpA/wkA" +
    "p0GXAXLlMda9l1YYNyzeg3jAjjdKRyjWENbk7Yv4Gni5z14jnsEbplMtcRbMZ7lQpvwpySH1lW4Oc4FkBPVGA6PsftJU6LtFu1tbKW+xHHWvAxXIGLx3" +
    "13hen7GPixJmDHsUNouc0YrQD+3gJYkY5gl7CyxFpEfQs/GT3/iQ3Cn2fXLqGbpkrD0sCD8Ys+teNmrKBgu5qJFbi4gd3L0uP4X1EDz4gM9nQQdh5+UG" +
    "2I60ps7ykAPoHLY+ODamfmGR2A7cOWqOKPSuz7S4BXdkfGo7n7bmHHmoxOwxdYHL2vG4857RB6IaUrYuCR2KB5tjpNw1pcn/i2QFhCU+oTGiulwfjDtu" +
    "h5MsBv4+6Cb71C1+efF+NBrFf8M3U816odM1+2x5qc6RPR81ohGhsW1vZmds7jcUWzI4O9bcCuSlQ0ileahGoJGH6NjM5uAfbeitN4eZyPWzoAvDVV6f" +
    "rIKB8lzcIS6VDyBmFxz5fdL8THcBb+mSTwAyngVxOGI2qjt31qd8El+CMq8zeujnzfiq+wUQglV+GTJmHfikEHhLjzpyYdvF4pd4kQ3+a/jeO2VGsk1I" +
    "XtBXX8Jazjd8SFnsXRJkGzayPOwc7o/QTubxI207sFdWxPGd/VImh+fG5NvH+mRVh4qqCOCSHGqdG3CcOjwlL0MZ7HncLRaHeDn1jdBizhJFLkc1lsHM" +
    "/sNxUHPetKHL+v/bPevBK/j8TGhyKDeSWOXytwuk79YaBV7SvwvSZv1C2sCnmVAcexBnyDlVo7u6UUB16ZauKH7oRhWei3Se32OCqM78Ot0iWUaow6nu" +
    "6EYVmE9gteFCbfZ1iOA+3x1akopJwrSm0tVz8eT5amCy34hNBLRRPvxqxAj9aGxGR0vmoWKUt1LRBi4UbqFzYqPUIIuV81KpsvBCqH2gSbIWzzWWorg5" +
    "vdpj9yy2y2FI2oHmW6hHG8qYGTacKRPQ81JOR5O53G/f+smQIm5A/ywHMSYAM4vcNgKe0Ukjk1WqF+D98gHJH2Kp4MU30dxJWpv+iRjilO1XVAyAvXZY" +
    "nyY+28SosujI2mUMeEDOc21YznNvxcVCCeHLOEdfiaKYA7KMS3WcZzu5ARammXTM3mM/GpcJxpR4crMMOq+eJhnUnHFi4/0/EOHi4pOyF0XQy2U8FxvB" +
    "NqtF7eh8eFvXxPBDYqHDRPqv4DaZkeachPulAAAAAASUVORK5CYII="

private const val GoogleIconDataUri =
    "data:image/svg+xml;utf8," +
        "%3Csvg%20xmlns='http://www.w3.org/2000/svg'%20viewBox='0%200%2024%2024'%3E" +
        "%3Cpath%20fill='%23EA4335'%20d='M12%2010.2v3.9h5.4c-.2%201.3-.9%202.4-1.9%203.2l3.1%202.4c1.8-1.7%202.9-4.1%202.9-7%200-.7-.1-1.4-.2-2H12Z'/%3E" +
        "%3Cpath%20fill='%2334A853'%20d='M12%2021.6c2.6%200%204.9-.9%206.5-2.4l-3.1-2.4c-.9.6-2%20.9-3.4.9-2.6%200-4.8-1.8-5.6-4.1l-3.2%202.5c1.6%203.2%204.9%205.5%208.8%205.5Z'/%3E" +
        "%3Cpath%20fill='%234285F4'%20d='M6.4%2013.6c-.2-.6-.3-1.1-.3-1.7s.1-1.2.3-1.7L3.2%207.7C2.4%209.2%202%2010.5%202%2012s.4%202.8%201.2%204.3l3.2-2.7Z'/%3E" +
        "%3Cpath%20fill='%23FBBC05'%20d='M12%206.2c1.4%200%202.7.5%203.7%201.4l2.8-2.8C16.8%203.2%2014.6%202.4%2012%202.4c-3.9%200-7.2%202.2-8.8%205.4l3.2%202.5C7.2%207.9%209.4%206.2%2012%206.2Z'/%3E" +
        "%3C/svg%3E"

private const val GitHubIconDataUri =
    "data:image/svg+xml;utf8," +
        "%3Csvg%20xmlns='http://www.w3.org/2000/svg'%20viewBox='0%200%2024%2024'%3E" +
        "%3Cpath%20fill='%23FFFFFF'%20d='M12%20.5C5.6.5.5%205.7.5%2012.2c0%205.2%203.4%209.7%208.1%2011.2.6.1.8-.3.8-.6v-2.2c-3.3.7-4-1.4-4-1.4-.5-1.4-1.3-1.8-1.3-1.8-1.1-.8.1-.8.1-.8%201.2.1%201.9%201.3%201.9%201.3%201.1%201.9%202.8%201.3%203.5%201%20.1-.8.4-1.3.7-1.7-2.7-.3-5.5-1.4-5.5-6%200-1.3.5-2.5%201.3-3.3-.1-.3-.6-1.6.1-3.2%200%200%201.1-.4%203.5%201.3a11.8%2011.8%200%200%201%206.4%200c2.4-1.7%203.5-1.3%203.5-1.3.7%201.6.2%202.9.1%203.2.8.9%201.3%202%201.3%203.3%200%204.7-2.8%205.7-5.6%206%20.5.4.8%201.2.8%202.3v3.4c0%20.3.2.8.8.6%204.7-1.5%208.1-5.9%208.1-11.2C23.5%205.7%2018.4.5%2012%20.5Z'/%3E" +
        "%3C/svg%3E"

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
