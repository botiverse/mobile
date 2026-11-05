package build.raft.app

import build.raft.app.components.RaftLinkButton
import build.raft.app.components.raftButtonChrome
import build.raft.app.components.raftCardSurface
import build.raft.app.resources.MR
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.Constraints
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
import com.tencent.tmm.kmmresource.compose.stringResource
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
    Small,
    Default,
    Large,
    XLarge
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
// raft-ui auth top bar: `h-panel-header` (56dp) including its 1dp bottom seam.
private val AuthTopBarHeight = 56.dp
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
    // Web AuthBrandShell content: `px-5`, `max-w-md`, the stack spacing of the caller.
    contentHorizontalPadding: Dp = 36.dp,
    contentMaxWidth: Dp = 314.dp,
    contentSpacing: Dp = 16.dp,
    contentBottomPadding: Dp = 40.dp,
    // Pinned under the scrolling content (bottom-centred), outside the centred stack.
    footer: (@Composable () -> Unit)? = null,
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
    var authViewportHeight by remember { mutableStateOf(0.dp) }
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
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = contentHorizontalPadding),
                    contentAlignment = Alignment.TopCenter
                ) {
                    if (contentScrollable) {
                        LazyColumn(
                            state = authListState,
                            modifier = Modifier
                                .widthIn(max = contentMaxWidth)
                                .fillMaxSize()
                                .onGloballyPositioned {
                                    authViewportBounds = it.boundsInRoot()
                                    authViewportHeight = with(density) { it.size.height.toDp() }
                                },
                            contentPadding =
                                PaddingValues(
                                    top = contentTopPadding,
                                    // adjustNothing leaves the list's measured viewport at full
                                    // height; reserve the IME region as extra scrollable content
                                    // so the focused field can move fully above the keyboard.
                                    bottom =
                                        contentBottomPadding +
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
                                if (footer == null) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(contentSpacing),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        content = content
                                    )
                                } else {
                                    AuthCenteredContentWithFooter(
                                        minHeight = authViewportHeight - contentTopPadding - contentBottomPadding,
                                        contentSpacing = contentSpacing,
                                        footer = footer,
                                        content = content
                                    )
                                }
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = contentMaxWidth)
                                .padding(top = contentTopPadding),
                            verticalArrangement = Arrangement.spacedBy(contentSpacing),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            content = content
                        )
                    }
                }
            }
        }
    }
}

/**
 * The centred auth stack plus a footer pinned to the bottom of the viewport. Web centres the
 * card in the space under the top bar; the footer (a mobile-only entry) must not move that
 * centre, so it sits at the bottom of a viewport-tall item and only pushes the item taller
 * when the stack and footer no longer fit.
 */
@Composable
private fun AuthCenteredContentWithFooter(
    minHeight: Dp,
    contentSpacing: Dp,
    footer: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val gap = 24.dp
    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(contentSpacing),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = content
            )
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { footer() }
        }
    ) { measurables, constraints ->
        val loose = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        val stack = measurables[0].measure(loose)
        val foot = measurables[1].measure(loose)
        val minPx = minHeight.roundToPx().coerceAtLeast(0)
        val gapPx = gap.roundToPx()
        val height = maxOf(minPx, stack.height + gapPx + foot.height)
        val stackTop = ((minPx - stack.height) / 2).coerceIn(0, (height - foot.height - gapPx - stack.height).coerceAtLeast(0))
        layout(constraints.maxWidth, height) {
            stack.place(0, stackTop)
            foot.place(0, height - foot.height)
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
        Box(Modifier.fillMaxWidth().height(RaftAppStroke.Thin).background(RaftColor.BorderSeam.authOpaqueOn(RaftColor.Frame)).align(Alignment.BottomCenter))
        Row(
            modifier = Modifier
                .padding(top = statusBarHeight.dp)
                .fillMaxWidth()
                .height(AuthTopBarHeight - RaftAppStroke.Thin)
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
            text = stringResource(MR.strings.auth_restore_tagline),
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
    RaftCachedImage(
        source = RaftIconAssetSource,
        contentDescription = null,
        modifier = modifier.size(size),
        contentScale = ContentScale.Fit
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

/**
 * Web paints alpha ink straight onto an opaque layer and rounds the blend; Skia's blend rounds
 * differently by one step on some channels. Auth chrome sits on known opaque layers, so it
 * composites the ink first and draws the opaque result.
 */
internal fun Color.authOpaqueOn(layer: Color): Color = if (alpha >= 1f) this else compositeOver(layer)

/** Auth type at an exact web size (`text-*` token): Space Grotesk, no tracking unless given. */
internal fun authTextStyle(
    fontSize: Int,
    lineHeight: Int,
    weight: FontWeight,
    color: Color,
    letterSpacing: TextUnit = 0.sp
): TextStyle =
    RaftType.Body.copy(
        fontSize = fontSize.sp,
        lineHeight = lineHeight.sp,
        letterSpacing = letterSpacing,
        color = color,
        // CSS wraps greedily; Compose's default line break balances lines.
        lineBreak = LineBreak.Simple
    ).withRaftFontWeight(weight)

internal val AuthMutedColor: Color get() = RaftColor.TextMuted.authOpaqueOn(RaftColor.Canvas)
internal val AuthHintColor: Color get() = RaftColor.ForegroundHint.authOpaqueOn(RaftColor.Canvas)
private val AuthHairlineColor: Color get() = RaftColor.BorderHairline.authOpaqueOn(RaftColor.Canvas)

/** Web `AuthBrandIntro`: the 36dp Raft mark, 16dp, the `text-title` heading, then 20dp. */
@Composable
internal fun AuthPageIntro(title: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AuthCenterMark()
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            style = authTextStyle(22, 28, FontWeight.Bold, RaftColor.TextBlack, letterSpacing = (-0.22).sp),
            color = RaftColor.TextBlack,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().authStaticTextFocus()
        )
    }
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
    password: Boolean = false,
    // Web FormField `labelAction`: a trailing control on the label row (Forgot Password?).
    labelAction: (@Composable () -> Unit)? = null,
    // Web PrefixedInput: a fixed muted cell before the editor (`@` for a username).
    prefix: String? = null,
    // Web FormField `description`: helper copy under the control.
    description: String? = null,
) {
    val resolvedKeyboardHeightChange = keyboardHeightChange ?: LocalRaftAuthKeyboardHeightChange.current
    val authFocusChanged = LocalRaftAuthFocusChanged.current
    var focused by remember { mutableStateOf(false) }
    val resolvedFocusRequester = focusRequester ?: remember { FocusRequester() }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { AuthFieldLabel(label = label, required = required, optional = optional) }
            labelAction?.invoke()
        }
        Spacer(Modifier.height(6.dp))
        AuthInputFrame(
            enabled = enabled,
            isError = isError,
            focused = (focused || forceFocused) && enabled,
            onTap = { resolvedFocusRequester.requestFocus() }
        ) {
            if (prefix != null) {
                AuthInputPrefix(prefix)
            }
            TextField(
                value = value,
                onValueChange = { nextValue -> authTextFieldForwardValueChange(nextValue, onValueChange) },
                placeholder = placeholder,
                placeholderColor = RaftColor.ForegroundHint.authOpaqueOn(RaftColor.Card),
                autoFocus = false,
                onFocus = {
                    focused = true
                    authFocusChanged(true)
                },
                onBlur = {
                    focused = false
                    authFocusChanged(false)
                },
                maxLines = 1,
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                keyboardHeightChange = resolvedKeyboardHeightChange,
                enabled = enabled,
                readOnly = !enabled,
                textStyle =
                    authTextStyle(
                        16,
                        24,
                        FontWeight.Normal,
                        if (enabled) RaftColor.TextBlack else RaftColor.ForegroundDisabled.authOpaqueOn(RaftColor.FillMuted)
                    ),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                focusRequester = resolvedFocusRequester
            )
        }
        if (!description.isNullOrBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = description,
                style = authTextStyle(12, 16, FontWeight.Medium, AuthHintColor),
                color = AuthHintColor,
                modifier = Modifier.authStaticTextFocus()
            )
        }
        if (isError && !errorText.isNullOrBlank()) {
            Spacer(Modifier.height(if (description.isNullOrBlank()) 12.dp else 6.dp))
            Text(
                text = errorText,
                style = authTextStyle(12, 16, FontWeight.SemiBold, RaftColor.DangerStrong),
                color = RaftColor.DangerStrong,
                modifier = Modifier.authStaticTextFocus()
            )
        }
    }
}

/**
 * raft-ui Input: 44dp, control radius, a 1dp `--line-field` edge on the card (danger-strong
 * when invalid, hairline on fill-muted when disabled). Focus draws `shadow-focus`: a 2dp
 * canvas gap, then a 2dp ink ring, outside the box.
 */
@Composable
private fun AuthInputFrame(
    enabled: Boolean,
    isError: Boolean,
    focused: Boolean,
    onTap: () -> Unit,
    content: @Composable RowScope.() -> Unit
) {
    val shape = RoundedCornerShape(RaftAppRadius.Control)
    val fill = if (enabled) RaftColor.Card else RaftColor.FillMuted
    val edge =
        when {
            !enabled -> RaftColor.BorderHairline.authOpaqueOn(RaftColor.FillMuted)
            isError -> RaftColor.DangerStrong
            else -> RaftColor.LineField
        }
    val ringGap = RaftColor.Canvas
    val ring = RaftColor.LineField
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .then(
                    if (focused) {
                        Modifier.drawBehind {
                            val gap = 2.dp.toPx()
                            val radius = RaftAppRadius.Control.toPx()
                            drawRoundRect(
                                color = ring,
                                topLeft = Offset(-2 * gap, -2 * gap),
                                size = Size(size.width + 4 * gap, size.height + 4 * gap),
                                cornerRadius = CornerRadius(radius + 2 * gap)
                            )
                            drawRoundRect(
                                color = ringGap,
                                topLeft = Offset(-gap, -gap),
                                size = Size(size.width + 2 * gap, size.height + 2 * gap),
                                cornerRadius = CornerRadius(radius + gap)
                            )
                        }
                    } else {
                        Modifier
                    }
                )
                .background(fill, shape)
                .border(RaftAppStroke.Thin, edge, shape)
                .clip(shape)
                .clickable(enabled = enabled, onClick = onTap)
                .padding(RaftAppStroke.Thin),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/** Web PrefixedInput prefix: fill-muted cell, 12dp sides, Space Mono 13/20 hint, hairline edge. */
@Composable
private fun AuthInputPrefix(text: String) {
    Row(modifier = Modifier.fillMaxHeight()) {
        Box(
            modifier = Modifier.fillMaxHeight().background(RaftColor.FillMuted).padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            val color = RaftColor.ForegroundHint.authOpaqueOn(RaftColor.FillMuted)
            Text(
                text = text,
                style = authTextStyle(13, 20, FontWeight.Normal, color).copy(fontFamily = RaftMonoFamily),
                color = color,
                modifier = Modifier.authStaticTextFocus()
            )
        }
        Box(Modifier.fillMaxHeight().width(RaftAppStroke.Thin).background(RaftColor.BorderHairline.authOpaqueOn(RaftColor.FillMuted)))
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

/** Web FormField label: 13/18 semibold `foreground-muted`. */
@Composable
internal fun AuthFieldLabel(label: String, required: Boolean = false, optional: Boolean = false) {
    Row(
        modifier = Modifier.authStaticTextFocus(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = authTextStyle(13, 18, FontWeight.SemiBold, AuthMutedColor),
            color = AuthMutedColor
        )
        if (required) {
            Text(
                text = "*",
                style = authTextStyle(13, 18, FontWeight.SemiBold, RaftColor.DangerStrong),
                color = RaftColor.DangerStrong
            )
        } else if (optional) {
            Text(
                text = stringResource(MR.strings.common_optional_suffix),
                style = authTextStyle(13, 18, FontWeight.Normal, AuthHintColor),
                color = AuthHintColor
            )
        }
    }
}

/** Web TextLink beside a field label (`text-footnote`, medium, muted). */
@Composable
internal fun AuthLabelLink(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = authTextStyle(13, 18, FontWeight.Medium, AuthMutedColor),
        color = AuthMutedColor,
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick)
    )
}

@Composable
internal fun AuthActionButton(
    text: String,
    onClick: () -> Unit,
    tone: AuthButtonTone,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    // Kept for callers: raft disabled buttons are always the opaque disabled look now.
    @Suppress("UNUSED_PARAMETER") preserveDisabledVisual: Boolean = false,
    variant: AuthActionButtonVariant = AuthActionButtonVariant.Large,
    fillWidth: Boolean = true,
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
    // raft-ui Button: accent tones are the sticker (ink edge and type), paper tones the
    // outline button (ink edge); Black stays an ink fill with inverse type. Control radius,
    // 1dp edge, no offset shadow. Disabled is opaque: fill-muted, hairline edge, 30% ink type.
    val raftChrome = raftButtonChrome(background)
    val active = enabled && tone != AuthButtonTone.Disabled
    val fill =
        when {
            !active -> RaftColor.FillMuted
            tone == AuthButtonTone.Black -> RaftColor.TextBlack
            tone == AuthButtonTone.White -> RaftColor.Card
            else -> raftChrome.fill
        }
    val edge =
        when {
            !active -> RaftColor.BorderHairline.authOpaqueOn(RaftColor.FillMuted)
            tone == AuthButtonTone.Black || tone == AuthButtonTone.White -> RaftColor.LineField
            else -> raftChrome.border
        }
    val contentColor =
        when {
            !active -> RaftColor.ForegroundDisabled.authOpaqueOn(RaftColor.FillMuted)
            tone == AuthButtonTone.Black -> RaftColor.ForegroundInverse
            tone == AuthButtonTone.White -> RaftColor.TextBlack
            else -> raftChrome.content
        }
    val height =
        when (variant) {
            AuthActionButtonVariant.XLarge -> 52.dp
            AuthActionButtonVariant.Large -> 44.dp
            AuthActionButtonVariant.Default -> 36.dp
            AuthActionButtonVariant.Small -> 32.dp
        }
    val horizontalPadding =
        when (variant) {
            AuthActionButtonVariant.XLarge -> 20.dp
            AuthActionButtonVariant.Large -> 16.dp
            AuthActionButtonVariant.Default, AuthActionButtonVariant.Small -> 12.dp
        }
    val iconGap = if (variant == AuthActionButtonVariant.Small) 6.dp else 12.dp
    val textStyle = authActionButtonTextStyle(variant, contentColor)
    val shape = RoundedCornerShape(RaftAppRadius.Control)
    Box(
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .height(height)
            .background(fill, shape)
            .border(RaftAppStroke.Thin, edge, shape)
            .clip(shape)
            .clickable(enabled = enabled && !loading, role = Role.Button, onClick = onClick)
            .padding(horizontal = horizontalPadding),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leadingContent != null) {
                Box(Modifier.padding(end = iconGap), contentAlignment = Alignment.Center) {
                    leadingContent()
                }
            }
            Text(
                // Labels like "Signing in…" already carry the ellipsis (U+2026); only append
                // dots to loading labels without one (e.g. "Opening Google" -> "Opening Google...").
                text = if (loading && !text.endsWith("...") && !text.endsWith("…")) "$text..." else text,
                style = textStyle,
                color = contentColor,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}

/** raft-ui Button type per size: sm/md 13/18, lg 15/20, xl 16/24, all bold. */
internal fun authActionButtonTextStyle(
    variant: AuthActionButtonVariant,
    contentColor: Color
) =
    when (variant) {
        AuthActionButtonVariant.XLarge -> authTextStyle(16, 24, FontWeight.Bold, contentColor)
        AuthActionButtonVariant.Large -> authTextStyle(15, 20, FontWeight.Bold, contentColor)
        AuthActionButtonVariant.Default, AuthActionButtonVariant.Small -> authTextStyle(13, 18, FontWeight.Bold, contentColor)
    }

/** raft-ui Separator with a label: hairlines either side of 13/18 semibold hint type. */
@Composable
internal fun AuthDivider() {
    Row(
        modifier = Modifier.fillMaxWidth().height(18.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.weight(1f).padding(top = 9.dp).height(RaftAppStroke.Thin).background(AuthHairlineColor))
        Text(
            text = stringResource(MR.strings.auth_social_divider_or),
            style = authTextStyle(13, 18, FontWeight.SemiBold, AuthHintColor),
            color = AuthHintColor,
            modifier = Modifier.authStaticTextFocus()
        )
        Box(Modifier.weight(1f).padding(top = 9.dp).height(RaftAppStroke.Thin).background(AuthHairlineColor))
    }
}

@Composable
internal fun AuthTextLink(text: String, onClick: () -> Unit) {
    RaftLinkButton(
        text = text,
        onClick = onClick,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = RaftColor.TextMutedGray,
        disabledColor = RaftColor.TextMutedGray.copy(alpha = 0.55f)
    )
}

/** Web `TextSans size="small"` prompt with a strong TextLink: "No account? Create one". */
@Composable
internal fun AuthInlineAction(leading: String, action: String, onClick: () -> Unit) {
    val linkStyle = authTextStyle(14, 20, FontWeight.SemiBold, RaftColor.TextBlack)
    val text =
        buildAnnotatedString {
            append(leading)
            append(" ")
            withLink(
                LinkAnnotation.Clickable(
                    tag = "auth-inline-action",
                    styles = TextLinkStyles(style = linkStyle.toSpanStyle()),
                    linkInteractionListener = LinkInteractionListener { onClick() }
                )
            ) {
                append(action)
            }
        }
    Text(
        text = text,
        style = authTextStyle(14, 20, FontWeight.Normal, AuthMutedColor),
        color = AuthMutedColor,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().authStaticTextFocus()
    )
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
internal fun AuthProviderIcon(provider: AuthProvider, onPaper: Boolean = false) {
    // task #60: every platform loads the MR-bundled SVG asset (see ProviderIconAsset.kt).
    // On the web outline button (paper fill) the marks are ink and 20dp.
    val source =
        when (provider) {
            AuthProvider.Google -> providerIconAssetSource(ProviderIconKey.GoogleColor)
            AuthProvider.GitHub -> providerIconAssetSource(if (onPaper) ProviderIconKey.GitHubBlack else ProviderIconKey.GitHubWhite)
            AuthProvider.Apple -> providerIconAssetSource(if (onPaper) ProviderIconKey.AppleBlack else ProviderIconKey.AppleWhite)
        }
    RaftCachedImage(
        source = source,
        contentDescription = null,
        modifier = Modifier.size(if (onPaper) 20.dp else 18.dp)
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
        AuthTextField(value = TextFieldValue(""), onValueChange = {}, label = stringResource(MR.strings.auth_label_email), required = true, forceFocused = true)
        AuthTextField(value = TextFieldValue("not-an-email"), onValueChange = {}, label = stringResource(MR.strings.auth_label_email), required = true, isError = true, errorText = "Enter a valid email.")
        AuthActionButton(text = "Sign In", onClick = {}, tone = AuthButtonTone.Pink)
        AuthActionButton(text = "Continue with GitHub", onClick = {}, tone = AuthButtonTone.Black, preserveDisabledVisual = true)
        AuthSuccessNotice("Reset link sent", "Check artin@raft.build for your secure link.")
    }
}
