package build.raft.app

import build.raft.app.resources.*
import build.raft.app.components.RaftLinkButton
import build.raft.app.components.RaftBrutalIconButton
import build.raft.app.components.RaftBrutalInputField
import build.raft.app.components.raftButtonChrome
import build.raft.app.components.raftCardSurface
import build.raft.app.components.raftControlSurface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import build.raft.app.compat.pager.LocalActivity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.compat.ui.KeyboardParams
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

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

private val AuthInputTextStyle: TextStyle
    // Getter: the UI-text locale and rendering defaults can change at runtime.
    get() =
        RaftType.Input.copy(
            fontSize = 16.sp,
            lineHeight = 24.sp,
            color = RaftColor.TextBlack
        ).withRaftFontWeight(FontWeight.Normal)

private val AuthInputPlaceholderColor: Color get() = RaftColor.TextBlack.copy(alpha = 0.4f)
private val AuthTopBarHeight = 62.dp
private val AuthTopBarBrandWidth = 81.dp
private val AuthTopBarBrandHeight = 20.dp
private const val RaftLogoAssetSource = "assets://lucide/raft-logo.svg"
private const val RaftIconAssetSource = "assets://lucide/slock-icon.svg"

private fun Modifier.authStaticTextFocus(): Modifier =
    focusProperties { canFocus = false }

private val LocalRaftAuthKeyboardHeightChange =
    compositionLocalOf<(KeyboardParams) -> Unit> { {} }

private val LocalRaftAuthFocusChanged =
    compositionLocalOf<(Boolean) -> Unit> { {} }

/**
 * The Android host keeps the page edge-to-edge and uses adjustNothing, so the keyboard does not
 * resize the Auth LazyColumn. Reduce its root-coordinate viewport explicitly before calculating
 * the focused editor scroll delta. [keyboardHeightDp] is the native Kuikly value in dp; layout
 * bounds are pixels. On Android the host explicitly reports that the native keyboard inset does
 * not include the bottom navigation safe area, so that area is added only for that contract.
 */
internal fun raftAuthKeyboardReducedViewport(
    viewport: Rect,
    keyboardHeightDp: Float,
    density: Float,
    bottomSafeArea: Dp = 0.dp,
    keyboardInsetExcludesBottomSafeArea: Boolean = false
): Rect {
    val keyboardInsetDp =
        raftEffectiveKeyboardInsetDp(
            keyboardHeightDp = keyboardHeightDp,
            bottomSafeArea = bottomSafeArea,
            keyboardInsetExcludesBottomSafeArea = keyboardInsetExcludesBottomSafeArea
        )
    val keyboardHeightPx = keyboardInsetDp.value * density.coerceAtLeast(0f)
    return Rect(
        left = viewport.left,
        top = viewport.top,
        right = viewport.right,
        bottom = (viewport.bottom - keyboardHeightPx).coerceAtLeast(viewport.top)
    )
}

@Composable
internal fun AuthScaffold(
    modifier: Modifier = Modifier,
    onDiagnosticsShortcut: (() -> Unit)? = null,
    contentTopPadding: Dp = 40.dp,
    contentScrollable: Boolean = false,
    scrollContentVerticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(12.dp),
    topBarHorizontalPadding: Dp = 16.dp,
    topBarBrandImagePadding: Dp = 0.dp,
    topLeadingContent: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val statusBarHeight = LocalActivity.current.pageData.statusBarHeight
    val authListState = rememberLazyListState()
    val authScrollScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val authBottomSafeArea = raftBottomSafeAreaPadding()
    val authKeyboardInsetExcludesBottomSafeArea = raftKeyboardInsetExcludesBottomSafeArea()
    var authKeyboardHeightDp by remember { mutableStateOf(0f) }
    var authViewportBounds by remember { mutableStateOf<Rect?>(null) }
    val authKeyboardScrollCoordinator = remember { RaftFocusedEditorScrollRequestCoordinator() }
    DisposableEffect(authKeyboardScrollCoordinator) {
        onDispose { authKeyboardScrollCoordinator.invalidate() }
    }
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
    RaftInputFocusDismissHost { focusDismissModifier ->
        val focusedEditorBounds = rememberRaftFocusedInputBoundsResolver()
        fun keepFocusedAuthEditorVisible(keyboardHeightDp: Float) {
            if (!contentScrollable) return
            val requestGeneration = authKeyboardScrollCoordinator.begin()
            val job = authScrollScope.launch {
                raftUnifiedDialogKeepFocusedEditorVisible(
                        keyboardHeight = keyboardHeightDp,
                        awaitRelayoutFrame = { withFrameNanos { } },
                        focusedBounds = focusedEditorBounds,
                        viewportBounds = {
                            authViewportBounds?.let {
                                raftAuthKeyboardReducedViewport(
                                    viewport = it,
                                    keyboardHeightDp = keyboardHeightDp,
                                    density = density.density,
                                    bottomSafeArea = authBottomSafeArea,
                                    keyboardInsetExcludesBottomSafeArea =
                                        authKeyboardInsetExcludesBottomSafeArea
                                )
                            }
                        },
                        currentScrollPosition = {
                            RaftUnifiedDialogListScrollPosition(
                                index = authListState.firstVisibleItemIndex,
                                scrollOffset = authListState.firstVisibleItemScrollOffset
                            )
                        },
                        scrollToItem = { index, scrollOffset ->
                            authListState.scrollToItem(index, scrollOffset)
                        },
                        isScrollInProgress = { authListState.isScrollInProgress },
                        isRequestCurrent = {
                            authKeyboardScrollCoordinator.isCurrent(requestGeneration)
                        }
                )
            }
            authKeyboardScrollCoordinator.attach(job)
        }
        val onAuthKeyboardHeightChange: (KeyboardParams) -> Unit = { params ->
            authKeyboardHeightDp = params.height.coerceAtLeast(0f)
            keepFocusedAuthEditorVisible(authKeyboardHeightDp)
        }
        val onAuthFocusChanged: (Boolean) -> Unit = { focused ->
            if (focused) {
                keepFocusedAuthEditorVisible(authKeyboardHeightDp)
            }
        }
        CompositionLocalProvider(
            LocalRaftAuthKeyboardHeightChange provides onAuthKeyboardHeightChange,
            LocalRaftAuthFocusChanged provides onAuthFocusChanged
        ) {
            Column(
                modifier = modifier
                    .then(focusDismissModifier)
                    .fillMaxSize()
                    // raft-ui auth shell: the canvas layer.
                    .background(RaftColor.Canvas)
            ) {
                AuthTopBar(statusBarHeight, onBrandClick, topBarHorizontalPadding, topBarBrandImagePadding, topLeadingContent)
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 36.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    if (contentScrollable) {
                        LazyColumn(
                            state = authListState,
                            modifier = Modifier
                                .widthIn(max = 314.dp)
                                .fillMaxSize()
                                .onGloballyPositioned { authViewportBounds = it.boundsInRoot() },
                            contentPadding =
                                PaddingValues(
                                    top = contentTopPadding,
                                    // adjustNothing leaves the list's measured viewport at full
                                    // height; reserve the IME region as extra scrollable content
                                    // so the focused field can move fully above the keyboard.
                                    bottom =
                                        40.dp +
                                            raftEffectiveKeyboardInsetDp(
                                                keyboardHeightDp = authKeyboardHeightDp,
                                                bottomSafeArea = authBottomSafeArea,
                                                keyboardInsetExcludesBottomSafeArea =
                                                    authKeyboardInsetExcludesBottomSafeArea
                                            )
                                ),
                            verticalArrangement = scrollContentVerticalArrangement,
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
            // raft-ui auth top bar: the frame layer with a 1dp seam.
            .background(RaftColor.Frame)
    ) {
        Box(Modifier.fillMaxWidth().height(RaftAppStroke.Thin).background(RaftColor.BorderSeam).align(Alignment.BottomCenter))
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
        RaftCachedImage(
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
            text = stringResource(Res.string.auth_restore_tagline),
            style = RaftType.SectionLabel.copy(
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 1.4.sp,
                color = RaftColor.TextBlack.copy(alpha = 0.52f)
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.authStaticTextFocus()
        )
    }
}

@Composable
internal fun AuthCenterMark(modifier: Modifier = Modifier, size: Dp = 36.dp) {
    // The mark is a single black path (cut-outs are transparent), so on the dark canvas it is
    // drawn in `foreground` ink; light keeps the asset's own black untouched.
    val dark = RaftAppThemeState.isDark
    val ink = RaftColor.TextBlack
    RaftCachedImage(
        source = RaftIconAssetSource,
        contentDescription = null,
        modifier = modifier.size(size),
        contentScale = ContentScale.Fit,
        colorFilter = if (dark) remember(ink) { ColorFilter.tint(ink) } else null
    )
}

@Composable
internal fun AuthLogoBadge(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.width(166.dp).height(64.dp).padding(end = 4.dp, bottom = 4.dp)) {
        // Brand mark: an ink sticker (sticker radius), no offset shadow.
        Box(
            modifier = Modifier.fillMaxSize().background(RaftColor.StickerInk, RoundedCornerShape(RaftAppRadius.Sticker)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                style = RaftType.PageTitle.copy(
                    fontSize = 32.sp,
                    lineHeight = 38.sp,
                    color = RaftColor.BrandYellow
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
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
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
    keyboardHeightChange: ((KeyboardParams) -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    password: Boolean = false
) {
    var passwordVisible by remember { mutableStateOf(false) }
    val resolvedKeyboardHeightChange = keyboardHeightChange ?: LocalRaftAuthKeyboardHeightChange.current
    val authFocusChanged = LocalRaftAuthFocusChanged.current
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Xs)
    ) {
        AuthFieldLabel(label = label, required = required, optional = optional)
        val borderColor = if (isError) RaftColor.AccentRed else RaftColor.BorderBlack
        RaftBrutalInputField(
            value = value,
            onValueChange = { nextValue ->
                authTextFieldForwardValueChange(nextValue, onValueChange)
            },
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
            enabledBackgroundColor = RaftColor.Card,
            disabledBackgroundColor = RaftColor.EmptyStateGray,
            placeholderColor = AuthInputPlaceholderColor,
            visualTransformation = if (password && !passwordVisible) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            keyboardHeightChange = resolvedKeyboardHeightChange,
            onFocusChanged = authFocusChanged,
            focusRequester = focusRequester ?: remember { FocusRequester() },
            trailingContent =
                if (password) {
                    {
                        RaftBrutalIconButton(
                            iconName = if (passwordVisible) RaftLucideIconName.EyeOff else RaftLucideIconName.Eye,
                            onClick = { passwordVisible = !passwordVisible },
                            contentDescription =
                                stringResource(
                                    if (passwordVisible) Res.string.common_hide_password else Res.string.common_show_password
                                ),
                            enabled = enabled,
                            size = 28.dp,
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
                style = RaftType.Caption.copy(color = RaftColor.AccentRed),
                color = RaftColor.AccentRed,
                modifier = Modifier.authStaticTextFocus()
            )
        }
    }
}

/**
 * Keeps the native editing snapshot intact across the Auth wrapper boundary.
 *
 * Auth screens synchronously install this exact value in their caller-local owner, then project
 * [TextFieldValue.text] to reducers/request state. Reconstructing from String here would discard
 * cursor, selection, and active IME composition.
 */
internal fun authTextFieldForwardValueChange(
    nextValue: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
) {
    onValueChange(nextValue)
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
            style = RaftType.Button.copy(fontSize = 12.sp, lineHeight = 16.sp, color = RaftColor.TextBlack),
            color = RaftColor.TextBlack
        )
        if (required) {
            Text(
                text = "*",
                style = RaftType.Button.copy(fontSize = 12.sp, lineHeight = 16.sp, color = RaftColor.AccentPink),
                color = RaftColor.AccentPink
            )
        } else if (optional) {
            Text(
                text = stringResource(Res.string.common_optional_suffix),
                style = RaftType.Caption.copy(color = RaftColor.TextMutedGray),
                color = RaftColor.TextMutedGray
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
        AuthButtonTone.Pink -> RaftColor.AccentPink
        AuthButtonTone.Yellow -> RaftColor.BrandYellow
        AuthButtonTone.Cyan -> RaftColor.AccentCyan
        AuthButtonTone.White -> RaftColor.SurfaceWhite
        AuthButtonTone.Black -> RaftColor.TextBlack
        AuthButtonTone.Disabled -> RaftColor.EmptyStateGray
    }
    // raft-ui 0.6.0 Button: pink/yellow tones are the accent sticker (sticker-ink edge and
    // type), the paper tones the outline button (1.5dp line-strong edge); Black stays an
    // ink fill with inverse type. Control radius, no offset shadow.
    val raftChrome = raftButtonChrome(background)
    val activeFill = if (tone == AuthButtonTone.Black) RaftColor.TextBlack else raftChrome.fill
    val activeBorder = if (tone == AuthButtonTone.Black) RaftColor.TextBlack else raftChrome.border
    val activeContentColor = if (tone == AuthButtonTone.Black) RaftColor.ForegroundInverse else raftChrome.content
    val disabledAlpha = 0.50f
    // React parity for disabled:opacity-50 during signing: pre-composite each colour 50%
    // onto the card layer instead of a group alpha (Kuikly's alpha modifier is unreliable
    // on this render path, task #372/#382).
    val dimWholeButton = !enabled && preserveDisabledVisual
    val dimOntoCard = { c: Color -> lerp(RaftColor.Card, c, disabledAlpha) }
    // Dark `foreground-disabled` (#636360) on `fill-muted` (#32322F) is ~2:1 and the label all but
    // vanishes (task #58); dark disabled auth buttons step one rung up the ink ladder to
    // `foreground-hint` and a `line-seam` edge so they still read as disabled but stay legible.
    // Light keeps the raft-ui disabled look exactly.
    val darkTheme = RaftAppThemeState.isDark
    val contentColor = when {
        dimWholeButton -> dimOntoCard(activeContentColor)
        enabled || preserveDisabledVisual -> activeContentColor
        darkTheme -> RaftColor.ForegroundHint
        else -> RaftColor.ForegroundDisabled
    }
    // raft disabled controls are opaque: fill-muted, line-muted edge, foreground-disabled type.
    val buttonBackground = when {
        dimWholeButton -> dimOntoCard(activeFill)
        enabled || preserveDisabledVisual -> activeFill
        else -> RaftColor.FillMuted
    }
    val borderColor = when {
        dimWholeButton -> dimOntoCard(activeBorder)
        enabled || preserveDisabledVisual -> activeBorder
        darkTheme -> RaftColor.BorderSeam
        else -> RaftColor.BorderHairline
    }
    val height = if (variant == AuthActionButtonVariant.Large) 44.dp else 36.dp
    val horizontalPadding = if (variant == AuthActionButtonVariant.Large) 14.dp else 12.dp
    val verticalPadding = if (variant == AuthActionButtonVariant.Large) 9.dp else 6.dp
    val textStyle = authActionButtonTextStyle(variant, contentColor)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .raftControlSurface(fill = buttonBackground, border = borderColor)
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
        RaftType.ButtonBold.copy(fontSize = 13.sp, lineHeight = 18.sp, color = contentColor)
    } else {
        RaftType.ButtonBold.copy(fontSize = 12.sp, lineHeight = 16.sp, color = contentColor)
    }

@Composable
internal fun AuthDivider() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.weight(1f).height(RaftAppStroke.Thin).background(RaftColor.BorderHairline))
        Text(
            text = stringResource(Res.string.auth_social_divider_or),
            style = RaftType.Caption.copy(fontSize = 10.sp, lineHeight = 12.sp, color = RaftColor.TextMutedGray),
            color = RaftColor.TextMutedGray,
            modifier = Modifier.authStaticTextFocus()
        )
        Box(Modifier.weight(1f).height(RaftAppStroke.Thin).background(RaftColor.BorderHairline))
    }
}

@Composable
internal fun AuthTextLink(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    RaftLinkButton(
        text = text,
        onClick = onClick,
        enabled = enabled,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = RaftColor.TextMutedGray,
        disabledColor = RaftColor.TextMutedGray.copy(alpha = 0.55f)
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
            style = RaftType.Body.copy(fontSize = 12.sp, lineHeight = 16.sp, color = RaftColor.TextBlack),
            color = RaftColor.TextBlack,
            modifier = Modifier.authStaticTextFocus()
        )
        Spacer(Modifier.width(RaftAppSpacing.Xs))
        Text(
            text = action,
            style = RaftType.Body.copy(
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = RaftColor.AccentPink,
                textDecoration = TextDecoration.Underline
            ).withRaftFontWeight(FontWeight.SemiBold),
            color = RaftColor.AccentPink,
            modifier = Modifier.clickable(onClick = onClick)
        )
    }
}

@Composable
internal fun AuthError(message: String?) {
    if (!message.isNullOrBlank()) {
        Text(
            text = message,
            style = RaftType.Body.copy(fontSize = 16.sp, lineHeight = 22.sp, color = RaftColor.AccentRed),
            color = RaftColor.AccentRed,
            modifier = Modifier.authStaticTextFocus()
        )
    }
}

@Composable
internal fun AuthSuccessNotice(title: String, detail: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // raft-ui info Banner chrome: info-soft fill, hairline, container radius.
            .raftCardSurface(fill = RaftColor.InfoSoft)
            .padding(RaftAppSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Xs)
    ) {
        Text(
            text = title,
            style = RaftType.Button.withRaftFontWeight(FontWeight.Bold).copy(color = RaftColor.TextBlack),
            color = RaftColor.TextBlack,
            modifier = Modifier.authStaticTextFocus()
        )
        Text(
            text = detail,
            style = RaftType.Body.copy(color = RaftColor.TextBlack),
            color = RaftColor.TextBlack,
            modifier = Modifier.authStaticTextFocus()
        )
    }
}

@Composable
internal fun AuthProviderIcon(provider: AuthProvider) {
    // task #60: every platform loads the bundled SVG file (see ProviderIconAsset.kt).
    val source =
        when (provider) {
            AuthProvider.Google -> providerIconAssetSource(ProviderIconKey.GoogleColor)
            AuthProvider.GitHub -> providerIconAssetSource(ProviderIconKey.GitHubWhite)
            AuthProvider.Apple -> providerIconAssetSource(ProviderIconKey.AppleWhite)
        }
    RaftCachedImage(
        source = source,
        contentDescription = null,
        modifier = Modifier.size(18.dp)
    )
}

@Composable
private fun AuthComponentPreview() {
    AuthScaffold {
        AuthBrandBlock()
        Text(
            text = "Sign In",
            style = RaftType.PageTitle.copy(fontSize = 32.sp, lineHeight = 38.sp, color = RaftColor.TextBlack),
            color = RaftColor.TextBlack
        )
        AuthTextField(value = TextFieldValue(""), onValueChange = {}, label = stringResource(Res.string.auth_label_email), required = true, forceFocused = true)
        AuthTextField(value = TextFieldValue("not-an-email"), onValueChange = {}, label = stringResource(Res.string.auth_label_email), required = true, isError = true, errorText = "Enter a valid email.")
        AuthActionButton(text = "Sign In", onClick = {}, tone = AuthButtonTone.Pink)
        AuthActionButton(text = "Continue with GitHub", onClick = {}, tone = AuthButtonTone.Black, preserveDisabledVisual = true)
        AuthSuccessNotice("Reset link sent", "Check artin@raft.build for your secure link.")
    }
}
