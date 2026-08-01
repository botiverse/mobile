package build.raft.app

import build.raft.app.components.SlockLinkButton
import build.raft.app.components.SlockBrutalIconButton
import build.raft.app.components.SlockBrutalInputField
import build.raft.app.components.SlockDisabledActionBorder
import build.raft.app.components.SlockDisabledActionContent
import build.raft.app.components.SlockDisabledActionShadow
import build.raft.app.components.slockDisabledActionBackgroundFor
import build.raft.app.resources.MR
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
import com.tencent.kuikly.compose.foundation.layout.PaddingValues
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
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.foundation.text.KeyboardActions
import com.tencent.kuikly.compose.foundation.text.KeyboardOptions
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.focus.FocusRequester
import com.tencent.kuikly.compose.ui.focus.focusProperties
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.graphics.lerp
import com.tencent.kuikly.compose.ui.layout.ContentScale
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.semantics.Role
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextAlign
import com.tencent.kuikly.compose.ui.text.style.TextDecoration
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.text.input.PasswordVisualTransformation
import com.tencent.kuikly.compose.ui.text.input.VisualTransformation
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.tmm.kmmresource.compose.stringResource
import com.tencent.kuikly.compose.ui.unit.sp

internal enum class AuthProvider {
    Google,
    GitHub,
    Apple
}

internal enum class AuthButtonTone {
    Pink,
    Yellow,
    Cyan,
    White,
    Black,
    Disabled
}

internal enum class AuthActionButtonVariant {
    Default,
    Large
}

private val AuthInputTextStyle =
    SlockType.Input.copy(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        color = SlockColor.TextBlack
    ).withSlockFontWeight(FontWeight.Normal)

private val AuthInputPlaceholderColor = SlockColor.TextBlack.copy(alpha = 0.4f)
private val AuthTopBarHeight = 62.dp
private val AuthTopBarBrandWidth = 81.dp
private val AuthTopBarBrandHeight = 20.dp
private const val RaftLogoAssetSource = "assets://lucide/raft-logo.svg"
private const val SlockIconAssetSource = "assets://lucide/slock-icon.svg"

private fun Modifier.authStaticTextFocus(): Modifier =
    focusProperties { canFocus = false }

@Composable
internal fun AuthScaffold(
    modifier: Modifier = Modifier,
    onDiagnosticsShortcut: (() -> Unit)? = null,
    contentTopPadding: Dp = 40.dp,
    contentScrollable: Boolean = false,
    topBarHorizontalPadding: Dp = 16.dp,
    topBarBrandImagePadding: Dp = 0.dp,
    topLeadingContent: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val statusBarHeight = LocalActivity.current.pageData.statusBarHeight
    var diagnosticsTapCount by remember { mutableStateOf(0) }
    val onBrandClick =
        onDiagnosticsShortcut?.let { callback ->
            {
                diagnosticsTapCount += 1
                if (diagnosticsTapCount >= 5) {
                    diagnosticsTapCount = 0
                    callback()
                }
            }
        }
    SlockInputFocusDismissHost { focusDismissModifier ->
        Column(
            modifier = modifier
                .then(focusDismissModifier)
                .fillMaxSize()
                .background(SlockColor.SurfaceWhite)
        ) {
            AuthTopBar(statusBarHeight, onBrandClick, topBarHorizontalPadding, topBarBrandImagePadding, topLeadingContent)
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 36.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                if (contentScrollable) {
                    LazyColumn(
                        modifier = Modifier
                            .widthIn(max = 314.dp)
                            .fillMaxSize(),
                        contentPadding = PaddingValues(top = contentTopPadding, bottom = 40.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        item {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                content = content
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 314.dp)
                            .padding(top = contentTopPadding),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        content = content
                    )
                }
            }
        }
    }
}

@Composable
private fun AuthTopBar(
    statusBarHeight: Float,
    onBrandClick: (() -> Unit)?,
    horizontalPadding: Dp,
    brandImagePadding: Dp,
    leadingContent: (@Composable () -> Unit)?
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(statusBarHeight.dp + AuthTopBarHeight)
            .background(SlockColor.BrandYellow)
    ) {
        Box(Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack).align(Alignment.BottomCenter))
        Row(
            modifier = Modifier
                .padding(top = statusBarHeight.dp)
                .fillMaxWidth()
                .height(AuthTopBarHeight)
                .padding(horizontal = horizontalPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leadingContent != null) {
                leadingContent()
            } else {
                AuthSmallBrand(
                    modifier =
                        if (onBrandClick != null) {
                            Modifier.clickable { onBrandClick() }
                        } else {
                            Modifier
                        },
                    imagePadding = brandImagePadding
                )
            }
        }
    }
}

@Composable
private fun AuthSmallBrand(modifier: Modifier = Modifier, imagePadding: Dp = 8.dp) {
    Box(
        modifier = modifier.width(AuthTopBarBrandWidth).height(AuthTopBarBrandHeight),
        contentAlignment = Alignment.Center
    ) {
        SlockCachedImage(
            source = RaftLogoAssetSource,
            contentDescription = null,
            modifier = Modifier.fillMaxSize().padding(horizontal = imagePadding),
            contentScale = ContentScale.Fit
        )
    }
}

@Composable
internal fun AuthBrandBlock(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.Start
    ) {
        AuthLogoBadge("RAFT")
        Text(
            text = stringResource(MR.strings.auth_restore_tagline),
            style = SlockType.SectionLabel.copy(
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 1.4.sp,
                color = SlockColor.TextBlack.copy(alpha = 0.52f)
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.authStaticTextFocus()
        )
    }
}

@Composable
internal fun AuthCenterMark(modifier: Modifier = Modifier) {
    SlockCachedImage(
        source = SlockIconAssetSource,
        contentDescription = null,
        modifier = modifier.size(36.dp),
        contentScale = ContentScale.Fit
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
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.authStaticTextFocus()
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
    focusRequester: FocusRequester? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    password: Boolean = false
) {
    var passwordVisible by remember { mutableStateOf(false) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        AuthFieldLabel(label = label, required = required, optional = optional)
        val borderColor = if (isError) SlockColor.AccentRed else SlockColor.BorderBlack
        SlockBrutalInputField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            enabled = enabled,
            autoFocus = false,
            maxLines = 1,
            textStyle = AuthInputTextStyle,
            fieldMinHeight = 44.dp,
            horizontalPadding = 8.dp,
            verticalPadding = 8.dp,
            forceFocused = forceFocused,
            borderColor = borderColor,
            enabledBackgroundColor = SlockColor.SurfaceWhite,
            disabledBackgroundColor = SlockColor.EmptyStateGray,
            placeholderColor = AuthInputPlaceholderColor,
            visualTransformation = if (password && !passwordVisible) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            focusRequester = focusRequester ?: remember { FocusRequester() },
            trailingContent =
                if (password) {
                    {
                        SlockBrutalIconButton(
                            iconName = if (passwordVisible) SlockLucideIconName.EyeOff else SlockLucideIconName.Eye,
                            onClick = { passwordVisible = !passwordVisible },
                            enabled = enabled,
                            size = 28.dp,
                            shadowOffset = 0.dp,
                            iconSize = 15.dp
                        )
                    }
                } else {
                    null
                }
        )
        if (isError && !errorText.isNullOrBlank()) {
            Text(
                text = errorText,
                style = SlockType.Caption.copy(color = SlockColor.AccentRed),
                color = SlockColor.AccentRed,
                modifier = Modifier.authStaticTextFocus()
            )
        }
    }
}

@Composable
internal fun AuthFieldLabel(label: String, required: Boolean = false, optional: Boolean = false) {
    Row(
        modifier = Modifier.authStaticTextFocus(),
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
                text = stringResource(MR.strings.common_optional_suffix),
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
    variant: AuthActionButtonVariant = AuthActionButtonVariant.Large,
    leadingContent: (@Composable () -> Unit)? = null
) {
    val background = when (tone) {
        AuthButtonTone.Pink -> SlockColor.AccentPink
        AuthButtonTone.Yellow -> SlockColor.BrandYellow
        AuthButtonTone.Cyan -> SlockColor.AccentCyan
        AuthButtonTone.White -> SlockColor.SurfaceWhite
        AuthButtonTone.Black -> SlockColor.TextBlack
        AuthButtonTone.Disabled -> SlockColor.EmptyStateGray
    }
    val activeContentColor = if (tone == AuthButtonTone.Black) SlockColor.SurfaceWhite else SlockColor.TextBlack
    val disabledAlpha = 0.50f
    // React parity for disabled:opacity-50 during signing. An earlier fix used a
    // group Modifier.alpha, but Kuikly's alpha modifier drops the offset shadow
    // child entirely (shadow vanished on android vs react's dimmed gray, task
    // #372/#382). Instead PRE-COMPOSITE each color 50% onto white — the opaque
    // pale-pink face still occludes the shadow (no bleed-through), and the shadow
    // stays visible as the same gray react's opacity-50 produces. No group alpha.
    val dimWholeButton = !enabled && preserveDisabledVisual
    val dimOntoWhite = { c: Color -> lerp(SlockColor.SurfaceWhite, c, disabledAlpha) }
    val contentColor = when {
        dimWholeButton -> dimOntoWhite(activeContentColor)
        enabled || preserveDisabledVisual -> activeContentColor
        else -> SlockDisabledActionContent
    }
    val buttonBackground = when {
        dimWholeButton -> dimOntoWhite(background)
        enabled || preserveDisabledVisual -> background
        else -> slockDisabledActionBackgroundFor(background)
    }
    val borderColor = when {
        dimWholeButton -> dimOntoWhite(SlockColor.BorderBlack)
        enabled || preserveDisabledVisual -> SlockColor.BorderBlack
        else -> SlockDisabledActionBorder
    }
    val shadowColor = when {
        dimWholeButton -> dimOntoWhite(SlockColor.ShadowBlack)
        enabled || preserveDisabledVisual -> SlockColor.ShadowBlack
        else -> SlockDisabledActionShadow
    }
    val height = if (variant == AuthActionButtonVariant.Large) 44.dp else 36.dp
    val shadowOffset = if (variant == AuthActionButtonVariant.Large) 3.dp else 2.dp
    val horizontalPadding = if (variant == AuthActionButtonVariant.Large) 14.dp else 12.dp
    val verticalPadding = if (variant == AuthActionButtonVariant.Large) 9.dp else 6.dp
    val textStyle = authActionButtonTextStyle(variant, contentColor)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            // No group alpha: dimming is baked into the pre-composited colors above
            // so the offset shadow (which Kuikly's Modifier.alpha would drop) stays
            // drawn as the dimmed gray, matching react's opacity-50 (task #382).
            .padding(end = shadowOffset, bottom = shadowOffset)
    ) {
        Box(Modifier.fillMaxSize().offset(x = shadowOffset, y = shadowOffset).background(shadowColor))
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(buttonBackground)
                .border(SlockStroke.Default, borderColor)
                .clickable(enabled = enabled && !loading, role = Role.Button, onClick = onClick)
                .padding(horizontal = horizontalPadding, vertical = verticalPadding),
            contentAlignment = Alignment.Center
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
                    // Labels like "Signing in…" already carry react's ellipsis
                    // (U+2026); only append dots to loading labels without one
                    // (e.g. "Opening Google" -> "Opening Google...").
                    text = if (loading && !text.endsWith("...") && !text.endsWith("…")) "$text..." else text,
                    style = textStyle,
                    color = contentColor,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

internal fun authActionButtonTextStyle(
    variant: AuthActionButtonVariant,
    contentColor: Color
) =
    if (variant == AuthActionButtonVariant.Large) {
        SlockType.ButtonBold.copy(fontSize = 13.sp, lineHeight = 18.sp, color = contentColor)
    } else {
        SlockType.ButtonBold.copy(fontSize = 12.sp, lineHeight = 16.sp, color = contentColor)
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
            text = stringResource(MR.strings.auth_social_divider_or),
            style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 12.sp, color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray,
            modifier = Modifier.authStaticTextFocus()
        )
        Box(Modifier.weight(1f).height(SlockStroke.Default).background(SlockColor.BorderBlack))
    }
}

@Composable
internal fun AuthTextLink(text: String, onClick: () -> Unit) {
    SlockLinkButton(
        text = text,
        onClick = onClick,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = SlockColor.TextMutedGray,
        disabledColor = SlockColor.TextMutedGray.copy(alpha = 0.55f)
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
            color = SlockColor.TextBlack,
            modifier = Modifier.authStaticTextFocus()
        )
        Spacer(Modifier.width(SlockSpacing.Xs))
        Text(
            text = action,
            style = SlockType.Body.copy(
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = SlockColor.AccentPink,
                textDecoration = TextDecoration.Underline
            ).withSlockFontWeight(FontWeight.SemiBold),
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
            color = SlockColor.AccentRed,
            modifier = Modifier.authStaticTextFocus()
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
            style = SlockType.Button.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.TextBlack),
            color = SlockColor.TextBlack,
            modifier = Modifier.authStaticTextFocus()
        )
        Text(
            text = detail,
            style = SlockType.Body.copy(color = SlockColor.TextBlack),
            color = SlockColor.TextBlack,
            modifier = Modifier.authStaticTextFocus()
        )
    }
}

@Composable
internal fun AuthProviderIcon(provider: AuthProvider) {
    // task #436: on iOS use the MR-bundled SVG asset (rendered via the SVG coder);
    // other platforms fall back to the inline SVG data URI they already render.
    val source =
        when (provider) {
            AuthProvider.Google ->
                providerIconIosSvgSource(ProviderIconKey.GoogleColor) ?: GoogleIconDataUri
            AuthProvider.GitHub ->
                providerIconIosSvgSource(ProviderIconKey.GitHubWhite) ?: GitHubIconWhiteDataUri
            AuthProvider.Apple ->
                providerIconIosSvgSource(ProviderIconKey.AppleWhite) ?: AppleIconWhiteDataUri
        }
    SlockCachedImage(
        source = source,
        contentDescription = null,
        modifier = Modifier.size(18.dp)
    )
}

private const val RaftLogoSvgDataUri =
    "data:image/svg+xml;utf8,%3Csvg%20width%3D%22422%22%20height%3D%22104%22%20viewBox%3D%220%200%20422%20104%22%20" +
        "fill%3D%22none%22%20xmlns%3D%22http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%22%3E%0A%3Cpath%20d%3D%22M112.754%2038.742" +
        "7L108.8%2016.3029C108.228%2013.0949%20106.323%2010.5063%20103.797%208.4577C102.543%207.44132%2098.0803%203.582" +
        "24%2096.2699%202.18472C93.7131%200.183714%2090.3622%20-0.324477%2087.186%200.183714L21.8199%2011.8404C16.8809%" +
        "2012.5709%2012.9424%2013.4761%209.90915%2017.5893C6.39945%2022.3377%207.43172%2026.9273%208.25753%2031.6122L11" +
        ".3384%2049.1289C11.4655%2049.8435%2011.6719%2050.5105%2011.8943%2051.1617C7.57465%2051.9239%204.16024%2053.480" +
        "3%202.07983%2056.5453C-0.175272%2059.8644%20-0.349963%2062.2942%200.396443%2066.5503L3.92202%2086.5603C4.82724" +
        "%2090.7688%208.03519%2092.9286%2012.7042%2097.1212C18.1196%20101.949%2020.2635%20104.649%2025.679%20103.696L96" +
        ".3017%2091.2452C102.892%2090.07%20107.307%2083.7812%20106.148%2077.1906L102.273%2055.1795C102.146%2054.4331%20" +
        "101.939%2053.7185%20101.685%2053.0356L102.972%2052.7974C109.515%2051.6063%20113.898%2045.3016%20112.754%2038.7" +
        "586V38.7427Z%22%20fill%3D%22%23141111%22%2F%3E%0A%3Cpath%20d%3D%22M18.3261%2087.1956L85.1691%2075.4119C87.3607" +
        "%2075.0307%2088.8217%2072.9345%2088.4247%2070.7588L85.2168%2052.5274C84.8356%2050.3358%2082.7393%2048.8748%208" +
        "0.5636%2049.2718C78.3721%2049.6529%2075.9105%2050.0976%2074.6718%2050.3199C72.7184%2050.6693%2069.3993%2049.46" +
        "24%2068.8276%2046.2544L67.7953%2040.4102L38.8761%2071.9181C37.9708%2072.7439%2035.9063%2073.8397%2033.7624%207" +
        "3.3315C31.9043%2072.8868%2030.5544%2071.267%2030.2209%2069.393L28.2993%2058.4828L13.7206%2061.0555C11.529%2061" +
        ".4525%2010.068%2063.5329%2010.4491%2065.7086L13.6571%2083.9241C14.0382%2086.1157%2016.1345%2087.5767%2018.3102" +
        "%2087.1797L18.3261%2087.1956Z%22%20fill%3D%22%23FFFAEF%22%2F%3E%0A%3Cpath%20d%3D%22M20.9147%2045.508C21.3117%2" +
        "047.6996%2023.3921%2049.1607%2025.5678%2048.7795L31.4914%2047.7314C34.2229%2047.2549%2036.8274%2049.0812%2037." +
        "3038%2051.7969L38.3361%2057.6411L67.2554%2026.1332C68.5417%2024.7357%2070.5268%2024.1799%2072.369%2024.7198C74" +
        ".1954%2025.2598%2075.577%2026.7844%2075.9105%2028.6583L77.8321%2039.5368L91.7756%2036.9958C93.9513%2036.5988%2" +
        "095.3965%2034.5184%2095.0153%2032.3427L91.7279%2013.6825C91.3468%2011.491%2089.2505%2010.0299%2087.0589%2010.4" +
        "269L20.867%2022.1948C18.6754%2022.5759%2017.2303%2024.6722%2017.6114%2026.8479L20.8988%2045.4921L20.9147%2045." +
        "508Z%22%20fill%3D%22%23FFFAEF%22%2F%3E%0A%3Cpath%20d%3D%22M190.396%2017.3194C199.115%2017.3194%20207.786%2021." +
        "0673%20205.293%2032.9622C204.578%2036.3607%20203.022%2039.251%20200.592%2041.6649C198.162%2044.063%20194.907%2" +
        "046.1751%20190.825%2048.0014C196.32%2049.9072%20199.083%2054.5285%20198.67%2061.8497C198.226%2069.5837%20197.6" +
        "06%2079.7793%20197.606%2079.7793H175.103C175.643%2072.744%20176.85%2062.6119%20174.833%2059.4516C172.975%2056." +
        "5295%20165.654%2058.1017%20165.654%2058.1017L161.08%2079.7793C161.08%2079.7793%20139.419%2079.8587%20139.323%2" +
        "079.7793L152.457%2017.3353H190.396V17.3194ZM176.119%2029.5001H171.609C171.609%2029.5001%20168.687%2043.0307%20" +
        "168.417%2044.8888C176.755%2044.6982%20183.202%2041.5061%20184.695%2035.9001C186.394%2029.5001%20179.566%2029.5" +
        "001%20176.119%2029.5001Z%22%20fill%3D%22%23141111%22%2F%3E%0A%3Cpath%20d%3D%22M274.756%2079.8745H251.205L251.0" +
        "14%2067.4556L234.927%2067.6144L230.051%2079.8745H205.737L235.197%2017.2717H268.293L274.756%2079.8745ZM251.173%" +
        "2052.6069L251.062%2031.5487H247.79L239.564%2052.591H251.173V52.6069Z%22%20fill%3D%22%23141111%22%2F%3E%0A%3Cpa" +
        "th%20d%3D%22M339.011%2033.0415H315.396L313.283%2043.0942H335.803L332.102%2059.3881H309.853L305.565%2079.7951H2" +
        "83.808L296.926%2017.4781H342.409L339.027%2033.0415H339.011Z%22%20fill%3D%22%23141111%22%2F%3E%0A%3Cpath%20d%3D" +
        "%22M401.232%2034.2643H384.843L375.267%2079.7951L353.669%2079.7157L363.229%2034.2643H348.047L351.493%2017.4781H" +
        "403.837L401.217%2034.2643H401.232Z%22%20fill%3D%22%23141111%22%2F%3E%0A%3Cpath%20d%3D%22M419.051%2017.0017C417" +
        ".764%2015.5089%20413.54%2011.5228%20412.301%2010.2523C410.03%207.91777%20407.108%206.36143%20403.853%206.36143" +
        "H296.91C291.654%206.36143%20287.112%2010.0458%20286.032%2015.1913L285.142%2019.3838C283.888%2016.6523%20281.68" +
        "%2015.1119%20279.568%2012.9838C276.313%209.7282%20273.089%206.1391%20268.293%206.1391H235.197C230.893%206.1391" +
        "%20226.97%208.63241%20225.144%2012.5233L220.014%2023.4176C219.633%2022.8459%20219.268%2022.2901%20218.855%2021" +
        ".8295C217.14%2019.9714%20213.186%2016.16%20212.106%2015.0801C206.897%209.83937%20199.734%206.18674%20190.412%2" +
        "06.18674H152.473C147.216%206.18674%20142.674%209.87113%20141.594%2015.0166L128.461%2077.4606C127.651%2081.272%" +
        "20128.905%2085.2423%20131.764%2087.8944C133.066%2089.1013%20137.195%2093.421%20138.513%2094.6438C141.737%2097." +
        "6453%20144.485%2097.7247%20149.503%2097.7247H167.893C170.513%2097.7247%20172.943%2096.6924%20174.865%2095.1361" +
        "C176.834%2096.7401%20179.312%2097.7247%20181.868%2097.7247H241.279C243.645%2097.7247%20245.519%2096.9624%20247" +
        ".25%2095.1361C249.283%2097.4071%20250.585%2097.7247%20254.969%2097.7247L312.315%2097.6612C317.571%2097.6612%20" +
        "322.113%2093.9768%20323.193%2088.8314L325.639%2077.2541H342.838L342.806%2077.4288C342.123%2080.7003%20342.774%" +
        "2084.2417%20345.045%2086.7033C346.506%2088.2914%20350.731%2092.3569%20351.795%2093.4527C354.113%2095.8666%2035" +
        "7.051%2097.5659%20360.402%2097.5818L382%2097.6612H382.048C387.305%2097.6612%20391.847%2093.9768%20392.927%2088" +
        ".8314L400.645%2052.1304H408.014C413.492%2052.1304%20418.146%2048.1443%20419.003%2042.7289L421.624%2025.9427C42" +
        "2.132%2022.7347%20421.195%2019.4632%20419.083%2017.0017H419.051Z%22%20fill%3D%22%23141111%22%2F%3E%0A%3Cpath%2" +
        "0d%3D%22M190.396%2017.3194C199.115%2017.3194%20207.786%2021.0673%20205.293%2032.9622C204.578%2036.3607%20203.0" +
        "22%2039.251%20200.592%2041.6649C198.162%2044.063%20194.907%2046.1751%20190.825%2048.0014C196.32%2049.9072%2019" +
        "9.083%2054.5285%20198.67%2061.8497C198.226%2069.5837%20197.606%2079.7793%20197.606%2079.7793H175.103C175.643%2" +
        "072.744%20176.85%2062.6119%20174.833%2059.4516C172.975%2056.5295%20165.654%2058.1017%20165.654%2058.1017L161.0" +
        "8%2079.7793C161.08%2079.7793%20139.419%2079.8587%20139.323%2079.7793L152.457%2017.3353H190.396V17.3194ZM176.11" +
        "9%2029.5001H171.609C171.609%2029.5001%20168.687%2043.0307%20168.417%2044.8888C176.755%2044.6982%20183.202%2041" +
        ".5061%20184.695%2035.9001C186.394%2029.5001%20179.566%2029.5001%20176.119%2029.5001Z%22%20fill%3D%22%23FFFAEF%" +
        "22%2F%3E%0A%3Cpath%20d%3D%22M274.756%2079.8745H251.205L251.014%2067.4556L234.927%2067.6144L230.051%2079.8745H2" +
        "05.737L235.197%2017.2717H268.293L274.756%2079.8745ZM251.173%2052.6069L251.062%2031.5487H247.79L239.564%2052.59" +
        "1H251.173V52.6069Z%22%20fill%3D%22%23FFFAEF%22%2F%3E%0A%3Cpath%20d%3D%22M339.011%2033.0415H315.396L313.283%204" +
        "3.0942H335.803L332.102%2059.3881H309.853L305.565%2079.7951H283.808L296.926%2017.4781H342.409L339.027%2033.0415" +
        "H339.011Z%22%20fill%3D%22%23FFFAEF%22%2F%3E%0A%3Cpath%20d%3D%22M401.232%2034.2643H384.843L375.267%2079.7951L35" +
        "3.669%2079.7157L363.229%2034.2643H348.047L351.493%2017.4781H403.837L401.217%2034.2643H401.232Z%22%20fill%3D%22" +
        "%23FFFAEF%22%2F%3E%0A%3C%2Fsvg%3E"

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

internal const val GoogleIconDataUri =
    "data:image/svg+xml;utf8," +
        "%3Csvg%20xmlns='http://www.w3.org/2000/svg'%20viewBox='0%200%2024%2024'%3E" +
        "%3Cpath%20fill='%23EA4335'%20d='M12%2010.2v3.9h5.4c-.2%201.3-.9%202.4-1.9%203.2l3.1%202.4c1.8-1.7%202.9-4.1%202.9-7%200-.7-.1-1.4-.2-2H12Z'/%3E" +
        "%3Cpath%20fill='%2334A853'%20d='M12%2021.6c2.6%200%204.9-.9%206.5-2.4l-3.1-2.4c-.9.6-2%20.9-3.4.9-2.6%200-4.8-1.8-5.6-4.1l-3.2%202.5c1.6%203.2%204.9%205.5%208.8%205.5Z'/%3E" +
        "%3Cpath%20fill='%234285F4'%20d='M6.4%2013.6c-.2-.6-.3-1.1-.3-1.7s.1-1.2.3-1.7L3.2%207.7C2.4%209.2%202%2010.5%202%2012s.4%202.8%201.2%204.3l3.2-2.7Z'/%3E" +
        "%3Cpath%20fill='%23FBBC05'%20d='M12%206.2c1.4%200%202.7.5%203.7%201.4l2.8-2.8C16.8%203.2%2014.6%202.4%2012%202.4c-3.9%200-7.2%202.2-8.8%205.4l3.2%202.5C7.2%207.9%209.4%206.2%2012%206.2Z'/%3E" +
        "%3C/svg%3E"

internal const val GitHubIconDataUri =
    "data:image/svg+xml;utf8,%3Csvg%20xmlns%3D%27http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%27%20viewBox%3D%270%200%2024%2024%27%3E%3Cpath%20fill%3D%27%23000000%27%20d%3D%27M12%20.297c-6.63%200-12%205.373-12%2012.297%200%205.303%203.438%209.8%208.205%2011.385.6.113.82-.258.82-.577%200-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422%2018.07%203.633%2017.7%203.633%2017.7c-1.087-.744.084-.729.084-.729%201.205.084%201.838%201.236%201.838%201.236%201.07%201.835%202.809%201.305%203.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93%200-1.31.465-2.38%201.235-3.22-.135-.303-.54-1.523.105-3.176%200%200%201.005-.322%203.3%201.23.96-.267%201.98-.399%203-.405%201.02.006%202.04.138%203%20.405%202.28-1.552%203.285-1.23%203.285-1.23.645%201.653.24%202.873.12%203.176.765.84%201.23%201.91%201.23%203.22%200%204.61-2.805%205.625-5.475%205.92.42.36.81%201.096.81%202.22%200%201.606-.015%202.896-.015%203.286%200%20.315.21.69.825.57C20.565%2022.092%2024%2017.592%2024%2012.297c0-6.627-5.373-12-12-12%27%2F%3E%3C%2Fsvg%3E"

internal const val GitHubIconWhiteDataUri =
    "data:image/svg+xml;utf8,%3Csvg%20xmlns%3D%27http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%27%20viewBox%3D%270%200%2024%2024%27%3E%3Cpath%20fill%3D%27%23FFFFFF%27%20d%3D%27M12%20.297c-6.63%200-12%205.373-12%2012.297%200%205.303%203.438%209.8%208.205%2011.385.6.113.82-.258.82-.577%200-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422%2018.07%203.633%2017.7%203.633%2017.7c-1.087-.744.084-.729.084-.729%201.205.084%201.838%201.236%201.838%201.236%201.07%201.835%202.809%201.305%203.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93%200-1.31.465-2.38%201.235-3.22-.135-.303-.54-1.523.105-3.176%200%200%201.005-.322%203.3%201.23.96-.267%201.98-.399%203-.405%201.02.006%202.04.138%203%20.405%202.28-1.552%203.285-1.23%203.285-1.23.645%201.653.24%202.873.12%203.176.765.84%201.23%201.91%201.23%203.22%200%204.61-2.805%205.625-5.475%205.92.42.36.81%201.096.81%202.22%200%201.606-.015%202.896-.015%203.286%200%20.315.21.69.825.57C20.565%2022.092%2024%2017.592%2024%2012.297c0-6.627-5.373-12-12-12%27%2F%3E%3C%2Fsvg%3E"

internal const val AppleIconDataUri =
    "data:image/svg+xml;utf8,%3Csvg%20xmlns%3D%27http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%27%20viewBox%3D%270%200%2016%2016%27%3E%3Cpath%20fill%3D%27%23000000%27%20d%3D%27M8.08803%204.3535C8.74395%204.3535%209.56615%203.91006%2010.0558%203.31881C10.4992%202.78299%2010.8226%202.03469%2010.8226%201.28639C10.8226%201.18477%2010.8133%201.08314%2010.7948%201C10.065%201.02771%209.18738%201.48963%208.6608%202.10859C8.24508%202.57975%207.86631%203.31881%207.86631%204.07635C7.86631%204.18721%207.88479%204.29807%207.89402%204.33502C7.94021%204.34426%208.01412%204.3535%208.08803%204.3535ZM5.77846%2015.5318C6.67457%2015.5318%207.07182%2014.9313%208.18965%2014.9313C9.32596%2014.9313%209.57539%2015.5133%2010.5731%2015.5133C11.5524%2015.5133%2012.2083%2014.608%2012.8273%2013.7211C13.5201%2012.7049%2013.8065%2011.7072%2013.825%2011.661C13.7603%2011.6425%2011.885%2010.8757%2011.885%208.7232C11.885%206.85707%2013.3631%206.01639%2013.4462%205.95172C12.467%204.5475%2010.9796%204.51055%2010.5731%204.51055C9.47377%204.51055%208.57766%205.1757%208.01412%205.1757C7.4044%205.1757%206.60066%204.5475%205.64912%204.5475C3.83842%204.5475%202%206.0441%202%208.87101C2%2010.6263%202.68363%2012.4832%203.52432%2013.6842C4.2449%2014.7004%204.87311%2015.5318%205.77846%2015.5318Z%27%2F%3E%3C%2Fsvg%3E"

internal const val AppleIconWhiteDataUri =
    "data:image/svg+xml;utf8,%3Csvg%20xmlns%3D%27http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%27%20viewBox%3D%270%200%2016%2016%27%3E%3Cpath%20fill%3D%27%23FFFFFF%27%20d%3D%27M8.08803%204.3535C8.74395%204.3535%209.56615%203.91006%2010.0558%203.31881C10.4992%202.78299%2010.8226%202.03469%2010.8226%201.28639C10.8226%201.18477%2010.8133%201.08314%2010.7948%201C10.065%201.02771%209.18738%201.48963%208.6608%202.10859C8.24508%202.57975%207.86631%203.31881%207.86631%204.07635C7.86631%204.18721%207.88479%204.29807%207.89402%204.33502C7.94021%204.34426%208.01412%204.3535%208.08803%204.3535ZM5.77846%2015.5318C6.67457%2015.5318%207.07182%2014.9313%208.18965%2014.9313C9.32596%2014.9313%209.57539%2015.5133%2010.5731%2015.5133C11.5524%2015.5133%2012.2083%2014.608%2012.8273%2013.7211C13.5201%2012.7049%2013.8065%2011.7072%2013.825%2011.661C13.7603%2011.6425%2011.885%2010.8757%2011.885%208.7232C11.885%206.85707%2013.3631%206.01639%2013.4462%205.95172C12.467%204.5475%2010.9796%204.51055%2010.5731%204.51055C9.47377%204.51055%208.57766%205.1757%208.01412%205.1757C7.4044%205.1757%206.60066%204.5475%205.64912%204.5475C3.83842%204.5475%202%206.0441%202%208.87101C2%2010.6263%202.68363%2012.4832%203.52432%2013.6842C4.2449%2014.7004%204.87311%2015.5318%205.77846%2015.5318Z%27%2F%3E%3C%2Fsvg%3E"

@Composable
private fun AuthComponentPreview() {
    AuthScaffold {
        AuthBrandBlock()
        Text(
            text = "Sign In",
            style = SlockType.PageTitle.copy(fontSize = 32.sp, lineHeight = 38.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack
        )
        AuthTextField(value = "", onValueChange = {}, label = stringResource(MR.strings.auth_label_email), required = true, forceFocused = true)
        AuthTextField(value = "not-an-email", onValueChange = {}, label = stringResource(MR.strings.auth_label_email), required = true, isError = true, errorText = "Enter a valid email.")
        AuthActionButton(text = "Sign In", onClick = {}, tone = AuthButtonTone.Pink)
        AuthActionButton(text = "Continue with GitHub", onClick = {}, tone = AuthButtonTone.Black, preserveDisabledVisual = true)
        AuthSuccessNotice("Reset link sent", "Check artin@raft.build for your secure link.")
    }
}
