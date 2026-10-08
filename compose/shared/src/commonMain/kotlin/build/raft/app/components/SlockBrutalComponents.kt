package build.raft.app.components

import build.raft.app.slockDisplayUppercase
import build.raft.app.Button as SlockAnchorButton
import build.raft.app.SlockColor
import build.raft.app.SlockLucideIcon
import build.raft.app.SlockMonoFamily
import build.raft.app.SlockLucideIconName
import build.raft.app.SlockLiveRootAnchorRef
import build.raft.app.SlockPopoverAnchor
import build.raft.app.SlockPopoverAnchorResolver
import build.raft.app.SlockPopoverLocalPoint
import build.raft.app.SlockModalDismissBarrier
import build.raft.app.SlockShadow
import build.raft.app.SlockSpacing
import build.raft.app.SlockStroke
import build.raft.app.SlockType
import build.raft.app.TextField
import build.raft.app.slockFloatingContentClickTarget
import build.raft.app.slockCaptureLiveRootAnchor
import build.raft.app.slockSquareCorners
import build.raft.app.slockThemedCorners
import build.raft.app.LocalSlockTheme
import build.raft.app.SlockThemeFamily
import build.raft.app.withSlockFontWeight
import build.raft.app.resources.MR
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.text.KeyboardActions
import com.tencent.kuikly.compose.foundation.text.KeyboardOptions
import com.tencent.kuikly.compose.ui.text.input.KeyboardType
import com.tencent.kuikly.compose.ui.text.input.PasswordVisualTransformation
import com.tencent.kuikly.compose.ui.text.input.TextFieldValue
import com.tencent.kuikly.compose.ui.text.input.VisualTransformation
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.BoxScope
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.fillMaxHeight
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.heightIn
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.layout.widthIn
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.foundation.lazy.LazyListScope
import com.tencent.kuikly.compose.material3.Button
import com.tencent.kuikly.compose.material3.ButtonDefaults
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.focus.FocusRequester
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.graphics.RectangleShape
import com.tencent.kuikly.compose.foundation.shape.RoundedCornerShape
import com.tencent.kuikly.compose.ui.draw.shadow
import com.tencent.kuikly.compose.ui.draw.alpha
import com.tencent.kuikly.compose.ui.platform.LocalDensity
import com.tencent.kuikly.compose.ui.text.TextStyle
import com.tencent.kuikly.compose.ui.text.font.FontFamily
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.layout.onGloballyPositioned
import com.tencent.kuikly.compose.ui.semantics.contentDescription
import com.tencent.kuikly.compose.ui.semantics.semantics
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.TextUnit
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.views.KeyboardParams
import com.tencent.tmm.kmmresource.compose.stringResource

internal data class SlockModalShellModel(
    val title: String,
    val subtitle: String = "",
    val overlayAlpha: Float = 0.60f,
    val maxWidth: Dp = 384.dp,
    val dismissIcon: SlockLucideIconName = SlockLucideIconName.X
)

internal data class SlockActionSheetRowModel(
    val title: String,
    val subtitle: String = "",
    val icon: SlockLucideIconName,
    val destructive: Boolean = false
)

internal data class SegmentedControlOption(
    val value: String,
    val label: String,
    val iconName: SlockLucideIconName? = null,
    val count: Int? = null
)

internal val SlockDisabledActionBackground = Color(0xFFFFD8E5)
internal val SlockDisabledDestructiveActionBackground = Color(0xFFFFE5E5)
internal val SlockDisabledActionBorder = Color(0xFFB3B3B3)
internal val SlockDisabledActionShadow = Color(0xFFB8B8B8)
internal val SlockDisabledActionContent = SlockColor.SurfaceWhite
internal const val SlockDisabledActionContentArgb = SlockColor.SurfaceWhiteArgb

internal fun slockDisabledActionBackgroundFor(activeColor: Color): Color =
    when (activeColor) {
        SlockColor.AccentRed -> SlockDisabledDestructiveActionBackground
        else -> SlockDisabledActionBackground
    }

internal enum class SlockAnchorPlacement {
    Tap,
    BottomStart,
    BottomEnd
}

internal enum class SlockMetaChipClickDispatch {
    Disabled,
    Plain,
    Anchored
}

internal fun resolveSlockMetaChipClickDispatch(
    hasPlainClick: Boolean,
    hasAnchoredClick: Boolean
): SlockMetaChipClickDispatch =
    when {
        hasPlainClick -> SlockMetaChipClickDispatch.Plain
        hasAnchoredClick -> SlockMetaChipClickDispatch.Anchored
        else -> SlockMetaChipClickDispatch.Disabled
    }

@Composable
internal fun SlockChipLoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 12.dp,
    color: String = SlockColor.TextMutedGrayArgb
) {
    SlockLucideIcon(
        name = SlockLucideIconName.LoaderCircle,
        modifier = modifier.size(size),
        color = color,
        spinning = true
    )
}

@Composable
internal fun SlockAnchoredMetaChip(
    label: String,
    iconName: SlockLucideIconName?,
    color: Color,
    onClick: ((SlockPopoverAnchor) -> Unit)?,
    onClickWithoutAnchor: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    iconSize: Dp = 10.dp,
    contentColor: Color = SlockColor.TextBlack,
    iconColorArgb: String = SlockColor.TextBlackArgb,
    iconFillArgb: String? = null,
    trailingIconName: SlockLucideIconName? = null,
    trailingIconSize: Dp = iconSize,
    borderWidth: Dp = SlockStroke.Thin,
    minHeight: Dp = 20.dp,
    horizontalPadding: Dp = 6.dp,
    verticalPadding: Dp = 2.dp,
    labelFontSize: TextUnit = 10.sp,
    labelLineHeight: TextUnit = 12.sp,
    pressFeedback: Boolean = onClick != null || onClickWithoutAnchor != null,
    anchorPlacement: SlockAnchorPlacement = SlockAnchorPlacement.Tap,
    clickIdentity: Any? = null,
    anchorOwnerIdentity: Any? = null
) {
    val clickDispatch =
        resolveSlockMetaChipClickDispatch(
            hasPlainClick = onClickWithoutAnchor != null,
            hasAnchoredClick = onClick != null
        )
    val resolvedAnchorOwnerIdentity = anchorOwnerIdentity ?: clickIdentity ?: "anchored_meta_chip:$label"
    val geometryAnchor =
        remember(resolvedAnchorOwnerIdentity) { SlockLiveRootAnchorRef(identity = resolvedAnchorOwnerIdentity) }
    val chipModifier =
        modifier
            .heightIn(min = minHeight)
            .border(borderWidth, SlockColor.BorderBlack, RectangleShape)
            .background(color, RectangleShape)
            .slockSquareCorners()
            .padding(horizontal = horizontalPadding, vertical = verticalPadding)
            .let { base ->
                if (
                    clickDispatch != SlockMetaChipClickDispatch.Anchored ||
                    anchorPlacement == SlockAnchorPlacement.Tap
                ) {
                    base
                } else {
                    base.slockCaptureLiveRootAnchor(geometryAnchor)
                }
            }
    SlockAnchorButton(
        onClick = {
            if (clickDispatch == SlockMetaChipClickDispatch.Plain) {
                onClickWithoutAnchor?.invoke()
            }
        },
        onClick2 =
            if (clickDispatch == SlockMetaChipClickDispatch.Anchored) {
                { tapAnchor ->
                    val anchor =
                        when (anchorPlacement) {
                            SlockAnchorPlacement.Tap -> tapAnchor
                            SlockAnchorPlacement.BottomStart ->
                                geometryAnchor.resolve(SlockPopoverLocalPoint.BottomStart)
                            SlockAnchorPlacement.BottomEnd ->
                                geometryAnchor.resolve(SlockPopoverLocalPoint.BottomEnd)
                        }
                    anchor?.let { onClick?.invoke(it) }
                }
            } else {
                null
            },
        enabled = clickDispatch != SlockMetaChipClickDispatch.Disabled,
        pressFeedback = pressFeedback,
        clickIdentity = clickIdentity,
        anchorOwnerIdentity = resolvedAnchorOwnerIdentity,
        modifier = chipModifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
        ) {
            iconName?.let {
                SlockLucideIcon(
                    it,
                    modifier = Modifier.size(iconSize),
                    color = iconColorArgb,
                    fill = iconFillArgb
                )
            }
            Text(
                text = label,
                style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = labelFontSize, lineHeight = labelLineHeight),
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            trailingIconName?.let {
                SlockLucideIcon(it, modifier = Modifier.size(trailingIconSize), color = SlockColor.TextMutedGrayArgb)
            }
        }
    }
}

@Composable
internal fun SlockBrutalSectionLabel(
    label: String,
    required: Boolean = false,
    optional: Boolean = false,
    size: SlockFormFieldSize = SlockFormFieldSize.Default,
    modifier: Modifier = Modifier
) {
    val compact = size == SlockFormFieldSize.Compact
    val labelFontSize = if (compact) 11.sp else 14.sp
    val labelLineHeight = if (compact) 14.sp else 18.sp
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label.slockDisplayUppercase(),
            style = SlockType.SectionLabel.copy(fontSize = labelFontSize, lineHeight = labelLineHeight, letterSpacing = 1.1.sp),
            color = SlockColor.TextBlack,
            maxLines = 1
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
internal fun SlockBrutalModalHeader(
    model: SlockModalShellModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .border(SlockStroke.Thin, SlockColor.BorderBlack)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = model.title,
                style = SlockType.PageTitle.copy(fontSize = 17.sp, lineHeight = 20.sp),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (model.subtitle.isNotBlank()) {
                Text(
                    text = model.subtitle,
                    style = SlockType.Caption,
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        SlockBrutalIconButton(
            iconName = model.dismissIcon,
            onClick = onDismiss,
            size = 34.dp,
            shadowOffset = 0.dp
        )
    }
}

@Composable
internal fun SlockBrutalModalLayer(
    model: SlockModalShellModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier =
            modifier
                .fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        SlockModalDismissBarrier(
            overlayAlpha = model.overlayAlpha,
            onDismiss = onDismiss
        )
        Box(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .widthIn(max = model.maxWidth)
                        .padding(end = 4.dp, bottom = 4.dp)
                        .background(SlockColor.ShadowBlack)
                        .slockFloatingContentClickTarget()
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(end = 4.dp, bottom = 4.dp)
                            .background(SlockColor.SurfaceWhite)
                            .border(SlockStroke.Default, SlockColor.BorderBlack)
                ) {
                    SlockBrutalModalHeader(model = model, onDismiss = onDismiss)
                    content()
                }
            }
        }
    }
}

@Composable
internal fun SlockBrutalActionSheetRow(
    model: SlockActionSheetRowModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // The bare material3 Button paints the theme's default filled container
    // (purple in this build) over the sheet's surface and disables text via the
    // default content color. Give the row explicit register-aware colors so the
    // parent's selected/unselected surface shows through and ink stays readable.
    val theme = LocalSlockTheme.current
    Button(
        onClick = onClick,
        shape = RectangleShape,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = theme.ink,
                disabledContainerColor = Color.Transparent,
                disabledContentColor = theme.inkMuted
            ),
        elevation = null,
        modifier =
            modifier
                .fillMaxWidth()
                .slockSquareCorners()
                .border(SlockStroke.Thin, SlockColor.BorderBlack)
                .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier =
                    Modifier
                        .size(34.dp)
                        .background(if (model.destructive) SlockColor.AccentRed else SlockColor.BrandYellow)
                        .border(SlockStroke.Default, SlockColor.BorderBlack),
                contentAlignment = Alignment.Center
            ) {
                SlockLucideIcon(model.icon, modifier = Modifier.size(17.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = model.title,
                    style = SlockType.ButtonBold,
                    color = theme.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (model.subtitle.isNotBlank()) {
                    Text(
                        text = model.subtitle,
                        style = SlockType.Caption,
                        color = theme.inkMuted,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * String-backed form field for ordinary text.
 *
 * Masked secrets intentionally have no String/password option here. They must use the
 * [TextFieldValue] overload below so the native editing token survives controlled recomposition.
 */
@Composable
internal fun SlockBrutalTextField(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    required: Boolean = false,
    optional: Boolean = false,
    labelSize: SlockFormFieldSize = SlockFormFieldSize.Default,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    hint: String? = null,
    error: String? = null,
    autoFocus: Boolean = false,
    maxLines: Int = 1,
    textStyle: TextStyle = SlockType.Input.copy(fontSize = 14.sp, lineHeight = 18.sp, color = SlockColor.TextBlack),
    fieldMinHeight: Dp = 42.dp,
    containerTapRequestsFocus: Boolean = false,
    onFocusChanged: (Boolean) -> Unit = {}
) {
    val hasError = !error.isNullOrBlank()
    SlockFormField(
        label = label,
        modifier = modifier,
        required = required,
        optional = optional,
        hint = hint,
        error = error,
        size = labelSize
    ) {
        SlockBrutalInputField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            enabled = enabled,
            readOnly = readOnly,
            autoFocus = autoFocus,
            maxLines = maxLines,
            textStyle = textStyle,
            fieldMinHeight = fieldMinHeight,
            containerTapRequestsFocus = containerTapRequestsFocus,
            borderColor = if (hasError) SlockColor.AccentRed else SlockColor.BorderBlack,
            enabledBackgroundColor = if (hasError) Color(0xFFFFF4F4) else SlockColor.SurfaceWhite,
            onFocusChanged = onFocusChanged
        )
    }
}

/**
 * Exact-value form field for editable text.
 *
 * The caller owns the native [TextFieldValue] so selection and composition survive controlled
 * recomposition. Product state and requests still consume [TextFieldValue.text] at their boundary.
 * Secret callers opt into native masking explicitly with [password].
 */
@Composable
internal fun SlockBrutalTextField(
    label: String,
    value: TextFieldValue,
    placeholder: String,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    required: Boolean = false,
    optional: Boolean = false,
    labelSize: SlockFormFieldSize = SlockFormFieldSize.Default,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    hint: String? = null,
    error: String? = null,
    autoFocus: Boolean = false,
    maxLines: Int = 1,
    textStyle: TextStyle = SlockType.Input.copy(fontSize = 14.sp, lineHeight = 18.sp, color = SlockColor.TextBlack),
    fieldMinHeight: Dp = 42.dp,
    containerTapRequestsFocus: Boolean = false,
    password: Boolean = false,
    onFocusChanged: (Boolean) -> Unit = {}
) {
    val hasError = !error.isNullOrBlank()
    SlockFormField(
        label = label,
        modifier = modifier,
        required = required,
        optional = optional,
        hint = hint,
        error = error,
        size = labelSize
    ) {
        SlockBrutalInputField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            enabled = enabled,
            readOnly = readOnly,
            autoFocus = autoFocus,
            maxLines = maxLines,
            textStyle = textStyle,
            fieldMinHeight = fieldMinHeight,
            containerTapRequestsFocus = containerTapRequestsFocus,
            borderColor = if (hasError) SlockColor.AccentRed else SlockColor.BorderBlack,
            enabledBackgroundColor = if (hasError) Color(0xFFFFF4F4) else SlockColor.SurfaceWhite,
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions =
                if (password) {
                    KeyboardOptions(keyboardType = KeyboardType.Password)
                } else {
                    KeyboardOptions.Default
                },
            onFocusChanged = onFocusChanged
        )
    }
}

/**
 * Height the input frame must reserve so [maxLines] lines of [lineHeight] fit inside the
 * vertical padding and the 2dp border. A two-line description field (create-channel,
 * connected-apps, …) used to keep the single-line 42dp: 42 − 2×8 padding − 2×2 border = 22dp of
 * content for two 18sp lines, so the field scrolled its first line out whenever the caret sat at
 * the end (task #536, `components.home.create-channel.dialog`). Web's `<textarea rows={2}>`
 * simply grows. Single-line fields keep [fieldMinHeight] exactly (byte-identical look).
 */
internal fun slockBrutalInputFieldFrameMinHeight(
    fieldMinHeight: Dp,
    maxLines: Int,
    lineHeight: Dp,
    verticalPadding: Dp,
    borderWidth: Dp = SlockStroke.Default
): Dp {
    if (maxLines <= 1) return fieldMinHeight
    val needed = lineHeight * maxLines + verticalPadding * 2 + borderWidth * 2
    return if (needed > fieldMinHeight) needed else fieldMinHeight
}

@Composable
private fun TextStyle.slockLineHeightDp(): Dp =
    with(LocalDensity.current) {
        val lh: TextUnit = lineHeight
        when {
            lh.isSp -> lh.toDp()
            fontSize.isSp -> fontSize.toDp() * 1.3f
            else -> 18.dp
        }
    }

/**
 * Shared frame for the two [SlockBrutalInputField] overloads (String + TextFieldValue). Owns the
 * single register-conditional wrapper so both overloads stay in lockstep and the elegant branch
 * lives in exactly one place (same single-source approach as the elegant button/card in this file).
 *
 * Brutal register (unchanged, byte-identical shipping look): the outer Box reserves a bottom/right
 * shadow gutter, a hard offset `ShadowBlack` box sits behind, and the foreground is square
 * (`slockSquareCorners`) with a 2px black border on the white/cream surface. Elegant register: a
 * flat product-grade field — real 8dp rounded corners + 1px `#CACACE` hairline + surface fill, no
 * offset shadow and no reserved gutter (the borderRadius-prop path does not round at runtime, so
 * the rounded shape is applied straight to `background`/`border`). An explicit non-default
 * [borderColor] (e.g. the AccentRed error border) passes through in both registers.
 */
@Composable
private fun SlockBrutalInputFieldFrame(
    modifier: Modifier,
    focusPolicy: SlockBrutalInputFocusPolicy,
    focused: Boolean,
    forceFocused: Boolean,
    showShadow: Boolean,
    fieldMinHeight: Dp,
    horizontalPadding: Dp,
    verticalPadding: Dp,
    borderColor: Color,
    enabledBackgroundColor: Color,
    disabledBackgroundColor: Color,
    focusRequester: FocusRequester,
    contentAlignment: Alignment,
    content: @Composable () -> Unit
) {
    val theme = LocalSlockTheme.current
    val surfaceColor = if (focusPolicy.acceptsFocus) enabledBackgroundColor else disabledBackgroundColor
    val tapModifier =
        if (focusPolicy.containerTapRequestsFocus) {
            Modifier.clickable { focusRequester.requestFocus() }
        } else {
            Modifier
        }
    if (theme.family == SlockThemeFamily.Elegant) {
        val shape = RoundedCornerShape(theme.cornerRadius)
        val elegantBorderColor = if (borderColor == SlockColor.BorderBlack) theme.line else borderColor
        Box(
            modifier =
                modifier
                    .fillMaxWidth()
                    .height(fieldMinHeight)
                    .background(surfaceColor, shape)
                    .border(theme.lineWidth, elegantBorderColor, shape)
                    .then(tapModifier)
                    .padding(horizontal = horizontalPadding, vertical = verticalPadding),
            contentAlignment = contentAlignment,
            content = { content() }
        )
        return
    }
    val shadowOffset =
        if (showShadow) {
            if ((focused || forceFocused) && focusPolicy.acceptsFocus) SlockShadow.Brutal else SlockShadow.BrutalSmall
        } else {
            0.dp
        }
    val reservedShadowOffset = if (showShadow) SlockShadow.Brutal else 0.dp
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(fieldMinHeight + reservedShadowOffset)
    ) {
        if (showShadow) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(fieldMinHeight)
                        .offset(x = shadowOffset, y = shadowOffset)
                        .slockSquareCorners()
                        .background(SlockColor.ShadowBlack)
            )
        }
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(fieldMinHeight)
                    .slockSquareCorners()
                    .background(surfaceColor)
                    .border(SlockStroke.Default, borderColor)
                    .then(tapModifier)
                    .padding(horizontal = horizontalPadding, vertical = verticalPadding),
            contentAlignment = contentAlignment,
            content = { content() }
        )
    }
}

@Composable
internal fun SlockBrutalInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    autoFocus: Boolean = false,
    maxLines: Int = 1,
    textStyle: TextStyle = SlockType.Input.copy(fontSize = 14.sp, lineHeight = 18.sp, color = SlockColor.TextBlack),
    fieldMinHeight: Dp = 42.dp,
    containerTapRequestsFocus: Boolean = false,
    horizontalPadding: Dp = 10.dp,
    verticalPadding: Dp = 8.dp,
    showShadow: Boolean = true,
    forceFocused: Boolean = false,
    borderColor: Color = SlockColor.BorderBlack,
    enabledBackgroundColor: Color = SlockColor.SurfaceWhite,
    disabledBackgroundColor: Color = SlockColor.SurfaceCream,
    placeholderColor: Color = SlockColor.TextMutedGray,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    keyboardHeightChange: (KeyboardParams) -> Unit = {},
    onFocusChanged: (Boolean) -> Unit = {},
    focusRequester: FocusRequester = remember { FocusRequester() },
    showClearWhenNotEmpty: Boolean = false,
    onClear: (() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null
) {
    var focused by remember { mutableStateOf(false) }
    val focusPolicy = slockBrutalInputFocusPolicy(maxLines, enabled, readOnly, containerTapRequestsFocus)
    val isMultiline = focusPolicy.fillEditorHeight
    val fieldContentAlignment = if (isMultiline) Alignment.TopStart else Alignment.CenterStart
    val rowVerticalAlignment = if (isMultiline) Alignment.Top else Alignment.CenterVertically
    val theme = LocalSlockTheme.current
    val resolvedTextStyle = if (theme.family == SlockThemeFamily.Elegant) textStyle.copy(color = theme.ink) else textStyle
    SlockBrutalInputFieldFrame(
        modifier = modifier,
        focusPolicy = focusPolicy,
        focused = focused,
        forceFocused = forceFocused,
        showShadow = showShadow,
        fieldMinHeight =
            slockBrutalInputFieldFrameMinHeight(
                fieldMinHeight = fieldMinHeight,
                maxLines = maxLines,
                lineHeight = resolvedTextStyle.slockLineHeightDp(),
                verticalPadding = verticalPadding
            ),
        horizontalPadding = horizontalPadding,
        verticalPadding = verticalPadding,
        borderColor = borderColor,
        enabledBackgroundColor = enabledBackgroundColor,
        disabledBackgroundColor = disabledBackgroundColor,
        focusRequester = focusRequester,
        contentAlignment = fieldContentAlignment
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(if (focusPolicy.fillEditorHeight) Modifier.fillMaxHeight() else Modifier),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = rowVerticalAlignment
        ) {
            TextField(
                value = value,
                onValueChange = { nextValue ->
                    slockBrutalInputForwardValueChange(
                        focusPolicy = focusPolicy,
                        nextValue = nextValue,
                        onValueChange = onValueChange
                    )
                },
                placeholder = placeholder,
                placeholderColor = placeholderColor,
                autoFocus = autoFocus && focusPolicy.acceptsFocus,
                onFocus = {
                    focused = true
                    onFocusChanged(true)
                },
                onBlur = {
                    focused = false
                    onFocusChanged(false)
                },
                maxLines = maxLines,
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                keyboardHeightChange = keyboardHeightChange,
                enabled = focusPolicy.acceptsFocus,
                readOnly = !focusPolicy.acceptsFocus,
                textStyle = resolvedTextStyle,
                visualTransformation = visualTransformation,
                systemNewlineAction = isMultiline,
                contentAlignment = fieldContentAlignment,
                modifier =
                    Modifier
                        .weight(1f)
                        .then(if (focusPolicy.fillEditorHeight) Modifier.fillMaxHeight() else Modifier),
                focusRequester = focusRequester
            )
            if (showClearWhenNotEmpty && value.isNotEmpty() && focusPolicy.acceptsFocus) {
                SlockBrutalIconButton(
                    iconName = SlockLucideIconName.X,
                    onClick = { onClear?.invoke() ?: onValueChange("") },
                    size = 18.dp,
                    shadowOffset = 2.dp
                )
            }
            trailingContent?.invoke()
        }
    }
}

@Composable
internal fun SlockBrutalInputField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    autoFocus: Boolean = false,
    maxLines: Int = 1,
    textStyle: TextStyle = SlockType.Input.copy(fontSize = 14.sp, lineHeight = 18.sp, color = SlockColor.TextBlack),
    fieldMinHeight: Dp = 42.dp,
    containerTapRequestsFocus: Boolean = false,
    horizontalPadding: Dp = 10.dp,
    verticalPadding: Dp = 8.dp,
    showShadow: Boolean = true,
    forceFocused: Boolean = false,
    borderColor: Color = SlockColor.BorderBlack,
    enabledBackgroundColor: Color = SlockColor.SurfaceWhite,
    disabledBackgroundColor: Color = SlockColor.SurfaceCream,
    placeholderColor: Color = SlockColor.TextMutedGray,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    keyboardHeightChange: (KeyboardParams) -> Unit = {},
    onFocusChanged: (Boolean) -> Unit = {},
    focusRequester: FocusRequester = remember { FocusRequester() },
    showClearWhenNotEmpty: Boolean = false,
    onClear: (() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null
) {
    var focused by remember { mutableStateOf(false) }
    val focusPolicy = slockBrutalInputFocusPolicy(maxLines, enabled, readOnly, containerTapRequestsFocus)
    val isMultiline = focusPolicy.fillEditorHeight
    val fieldContentAlignment = if (isMultiline) Alignment.TopStart else Alignment.CenterStart
    val rowVerticalAlignment = if (isMultiline) Alignment.Top else Alignment.CenterVertically
    val theme = LocalSlockTheme.current
    val resolvedTextStyle = if (theme.family == SlockThemeFamily.Elegant) textStyle.copy(color = theme.ink) else textStyle
    SlockBrutalInputFieldFrame(
        modifier = modifier,
        focusPolicy = focusPolicy,
        focused = focused,
        forceFocused = forceFocused,
        showShadow = showShadow,
        fieldMinHeight =
            slockBrutalInputFieldFrameMinHeight(
                fieldMinHeight = fieldMinHeight,
                maxLines = maxLines,
                lineHeight = resolvedTextStyle.slockLineHeightDp(),
                verticalPadding = verticalPadding
            ),
        horizontalPadding = horizontalPadding,
        verticalPadding = verticalPadding,
        borderColor = borderColor,
        enabledBackgroundColor = enabledBackgroundColor,
        disabledBackgroundColor = disabledBackgroundColor,
        focusRequester = focusRequester,
        contentAlignment = fieldContentAlignment
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(if (focusPolicy.fillEditorHeight) Modifier.fillMaxHeight() else Modifier),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = rowVerticalAlignment
        ) {
            TextField(
                value = value,
                onValueChange = { nextValue ->
                    slockBrutalInputForwardValueChange(
                        focusPolicy = focusPolicy,
                        nextValue = nextValue,
                        onValueChange = onValueChange
                    )
                },
                placeholder = placeholder,
                placeholderColor = placeholderColor,
                autoFocus = autoFocus && focusPolicy.acceptsFocus,
                onFocus = {
                    focused = true
                    onFocusChanged(true)
                },
                onBlur = {
                    focused = false
                    onFocusChanged(false)
                },
                maxLines = maxLines,
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                keyboardHeightChange = keyboardHeightChange,
                enabled = focusPolicy.acceptsFocus,
                readOnly = !focusPolicy.acceptsFocus,
                textStyle = resolvedTextStyle,
                visualTransformation = visualTransformation,
                systemNewlineAction = isMultiline,
                contentAlignment = fieldContentAlignment,
                modifier =
                    Modifier
                        .weight(1f)
                        .then(if (focusPolicy.fillEditorHeight) Modifier.fillMaxHeight() else Modifier),
                focusRequester = focusRequester
            )
            if (showClearWhenNotEmpty && value.text.isNotEmpty() && focusPolicy.acceptsFocus) {
                SlockBrutalIconButton(
                    iconName = SlockLucideIconName.X,
                    onClick = { onClear?.invoke() ?: onValueChange(TextFieldValue("")) },
                    size = 18.dp,
                    shadowOffset = 2.dp
                )
            }
            trailingContent?.invoke()
        }
    }
}

internal data class SlockBrutalInputFocusPolicy(
    val acceptsFocus: Boolean,
    val fillEditorHeight: Boolean,
    val containerTapRequestsFocus: Boolean
)

/**
 * Applies the shared editability gate without rebuilding the editor's value object.
 *
 * In particular, [TextFieldValue] callers rely on the exact native snapshot reaching their
 * hoisted state so its selection, composition and direct-echo identity stay intact.
 */
internal fun <T> slockBrutalInputForwardValueChange(
    focusPolicy: SlockBrutalInputFocusPolicy,
    nextValue: T,
    onValueChange: (T) -> Unit
) {
    if (focusPolicy.acceptsFocus) onValueChange(nextValue)
}

/**
 * Multiline input owns its complete visible editing surface by default.
 *
 * Filling the editor height lets the native TextArea receive blank-area taps and place the cursor
 * from the real touch position. The container fallback covers border/padding gaps with the same
 * [FocusRequester]. Single-line fields keep their historical opt-in, and disabled/read-only fields
 * never gain focus through either path.
 */
internal fun slockBrutalInputFocusPolicy(
    maxLines: Int,
    enabled: Boolean,
    readOnly: Boolean,
    explicitContainerTapRequestsFocus: Boolean = false
): SlockBrutalInputFocusPolicy {
    val acceptsFocus = enabled && !readOnly
    val multiline = maxLines > 1
    return SlockBrutalInputFocusPolicy(
        acceptsFocus = acceptsFocus,
        fillEditorHeight = multiline,
        containerTapRequestsFocus = acceptsFocus && (multiline || explicitContainerTapRequestsFocus)
    )
}

@Composable
internal fun SlockBrutalReadOnlyField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    fieldMinHeight: Dp = 42.dp,
    horizontalPadding: Dp = 12.dp,
    verticalPadding: Dp = 8.dp,
    textStyle: TextStyle = SlockType.Input.copy(fontSize = 14.sp, lineHeight = 18.sp, color = SlockColor.TextMutedGray),
    textColor: Color = SlockColor.TextMutedGray
) {
    SlockFormField(label = label, modifier = modifier, size = SlockFormFieldSize.Compact) {
        SlockBrutalReadOnlyValueField(
            value = value,
            modifier = Modifier.fillMaxWidth(),
            maxLines = maxLines,
            fieldMinHeight = fieldMinHeight,
            horizontalPadding = horizontalPadding,
            verticalPadding = verticalPadding,
            textStyle = textStyle,
            textColor = textColor
        )
    }
}

@Composable
internal fun SlockBrutalReadOnlyValueField(
    value: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    fieldMinHeight: Dp = 42.dp,
    horizontalPadding: Dp = 12.dp,
    verticalPadding: Dp = 8.dp,
    backgroundColor: Color = Color(0xFFF9FAFB),
    borderColor: Color = SlockColor.BorderBlack.copy(alpha = 0.30f),
    textStyle: TextStyle = SlockType.Input.copy(fontSize = 14.sp, lineHeight = 18.sp, color = SlockColor.TextMutedGray),
    textColor: Color = SlockColor.TextMutedGray
) {
    Box(
        modifier =
            modifier
                .heightIn(min = fieldMinHeight)
                .background(backgroundColor)
                .border(SlockStroke.Default, borderColor)
                .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = value,
            style = textStyle.copy(color = textColor),
            color = textColor,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
internal fun SlockBrutalSearchField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: SlockLucideIconName = SlockLucideIconName.Search,
    showClearWhenNotEmpty: Boolean = false,
    onClear: () -> Unit = { onValueChange("") },
    textStyle: TextStyle = SlockType.Caption.copy(fontSize = 13.sp, lineHeight = 16.sp, color = SlockColor.TextBlack),
    onFocus: () -> Unit = {},
    onBlur: () -> Unit = {}
) {
    var focused by remember { mutableStateOf(false) }
    val shadowOffset = if (focused && enabled) SlockShadow.Brutal else SlockShadow.BrutalSmall
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(42.dp + shadowOffset)
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .offset(x = shadowOffset, y = shadowOffset)
                    .slockSquareCorners()
                    .background(SlockColor.ShadowBlack)
        )
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .slockSquareCorners()
                    .background(if (enabled) SlockColor.SurfaceWhite else SlockColor.SurfaceCream)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SlockLucideIcon(leadingIcon, color = "#FF000000", modifier = Modifier.size(14.dp))
            TextField(
                value = value,
                onValueChange = { if (enabled) onValueChange(it) },
                placeholder = placeholder,
                placeholderColor = SlockColor.TextMutedGray,
                autoFocus = false,
                onFocus = {
                    focused = true
                    onFocus()
                },
                onBlur = {
                    focused = false
                    onBlur()
                },
                maxLines = 1,
                textStyle = textStyle,
                modifier = Modifier.weight(1f)
            )
            if (showClearWhenNotEmpty && value.isNotEmpty()) {
                SlockLucideIcon(
                    SlockLucideIconName.X,
                    color = "#FF000000",
                    modifier =
                        Modifier
                            .size(16.dp)
                            .clickable(enabled = enabled, onClick = onClear)
                )
            }
        }
    }
}

@Composable
internal fun SlockBrutalSearchField(
    value: TextFieldValue,
    placeholder: String,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: SlockLucideIconName = SlockLucideIconName.Search,
    showClearWhenNotEmpty: Boolean = false,
    onClear: () -> Unit = { onValueChange(TextFieldValue("")) },
    textStyle: TextStyle = SlockType.Caption.copy(fontSize = 13.sp, lineHeight = 16.sp, color = SlockColor.TextBlack),
    onFocus: () -> Unit = {},
    onBlur: () -> Unit = {}
) {
    var focused by remember { mutableStateOf(false) }
    val shadowOffset = if (focused && enabled) SlockShadow.Brutal else SlockShadow.BrutalSmall
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(42.dp + shadowOffset)
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .offset(x = shadowOffset, y = shadowOffset)
                    .slockSquareCorners()
                    .background(SlockColor.ShadowBlack)
        )
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .slockSquareCorners()
                    .background(if (enabled) SlockColor.SurfaceWhite else SlockColor.SurfaceCream)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SlockLucideIcon(leadingIcon, color = "#FF000000", modifier = Modifier.size(14.dp))
            TextField(
                value = value,
                onValueChange = { if (enabled) onValueChange(it) },
                placeholder = placeholder,
                placeholderColor = SlockColor.TextMutedGray,
                autoFocus = false,
                onFocus = {
                    focused = true
                    onFocus()
                },
                onBlur = {
                    focused = false
                    onBlur()
                },
                maxLines = 1,
                textStyle = textStyle,
                modifier = Modifier.weight(1f)
            )
            if (showClearWhenNotEmpty && value.text.isNotEmpty()) {
                SlockLucideIcon(
                    SlockLucideIconName.X,
                    color = "#FF000000",
                    modifier =
                        Modifier
                            .size(16.dp)
                            .clickable(enabled = enabled, onClick = onClear)
                )
            }
        }
    }
}

@Composable
private fun SlockBrutalActionButtonInnerContent(
    label: String,
    leadingIcon: SlockLucideIconName?,
    leadingIconSpinning: Boolean,
    leadingIconSize: Dp,
    contentSpacing: Dp,
    textStyle: TextStyle,
    enabled: Boolean,
    disabledContentColor: Color,
    disabledIconColor: String,
    trailingContent: (@Composable () -> Unit)? = null
) {
    // Enabled ink follows the register (brutalTheme.ink == TextBlack, unchanged).
    // The leading icon keeps its ARGB-string ink for now — elegant ink (#0A0A0A) vs
    // brutal (#000000) is sub-perceptual, and Color→ARGB-string plumbing is a
    // separate follow-up from this switch proof.
    val inkColor = if (enabled) LocalSlockTheme.current.ink else disabledContentColor
    Row(
        horizontalArrangement = Arrangement.spacedBy(if (leadingIcon != null || trailingContent != null) contentSpacing else 0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingIcon != null) {
            SlockLucideIcon(
                leadingIcon,
                modifier = Modifier.size(leadingIconSize),
                color = if (enabled) SlockColor.TextBlackArgb else disabledIconColor,
                spinning = leadingIconSpinning
            )
        }
        Text(
            text = label,
            style = textStyle,
            color = inkColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        trailingContent?.invoke()
    }
}

@Composable
internal fun SlockBrutalActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backgroundColor: Color = SlockColor.SurfaceWhite,
    // react bg-brutal-stone #C0B9B1 @ .btn-brutal-sm:disabled opacity-40 over white = #E6E3E0 (visual-fix pilot, AI analysis 2026-07-09)
    disabledBackgroundColor: Color = Color(0xFFE6E3E0),
    // Same react rule as the fill above: .btn-brutal-sm:disabled is the WHOLE
    // element at opacity-40, so every part composites over white - border black
    // -> #999999, shadow ink #141111 -> #A1A0A0, text black -> #999999. Opaque
    // values on purpose: alpha on the shadow Box is unreliable in this render
    // path (the 0.40f below still drew black) and opaque matches the #508 fill
    // precedent.
    disabledBorderColor: Color = Color(0xFF999999),
    disabledShadowColor: Color = Color(0xFFA1A0A0),
    disabledContentColor: Color = Color(0xFF999999),
    disabledIconColor: String = "#FF999999",
    minWidth: Dp = 82.dp,
    minHeight: Dp = 40.dp,
    leadingIcon: SlockLucideIconName? = null,
    leadingIconSpinning: Boolean = leadingIcon == SlockLucideIconName.Loader || leadingIcon == SlockLucideIconName.LoaderCircle,
    leadingIconSize: Dp = 12.dp,
    contentSpacing: Dp = 6.dp,
    // Null defers to the register's structural offset (brutal 4dp == SlockShadow.Brutal,
    // so the default path is unchanged). Callers passing an explicit offset keep it.
    shadowOffset: Dp? = null,
    textStyle: TextStyle = SlockBrutalActionButtonDefaultTextStyle,
    trailingContent: (@Composable () -> Unit)? = null
) {
    // The enabled register (border, corner radius, shadow, ink) is read from
    // LocalSlockTheme. brutalTheme reproduces every value this button hard-coded, so the
    // default path renders byte-identically (hard structural offset-box shadow + square).
    // elegantTheme renders the *ambient* register: rounded corners + a soft elevation
    // shadow via Kuikly Modifier.shadow (which draws a blurred shadow AND clips to the
    // rounded shape — the reliable rounding path, since the borderRadius-prop
    // slockThemedCorners did not actually round at runtime) + a thin gray hairline +
    // near-black ink. Verified against the real capture, not just source (#5916 audit).
    val theme = LocalSlockTheme.current
    val borderColor = if (enabled) theme.line else disabledBorderColor
    val surfaceBackground = if (enabled) backgroundColor else disabledBackgroundColor
    if (theme.family == SlockThemeFamily.Elegant) {
        // Ambient register: no offset-box, no reserved offset padding. Rounding is
        // enforced by Modifier.shadow(clip = true) so it holds even if Kuikly ignores the
        // background/border Shape. Shadow is TUNED to React's subtle 0 1px 8px rgba(0,0,0,
        // .15): Kuikly couples elevation -> Y offset (~e/2) + blur (~e), so a low 3dp
        // elevation keeps the halo short, and ambient/spot = theme.shadow.color pins the
        // 15% darkness (vs Kuikly's ~19% default) — the #5916/#1894 captures measured the
        // 8dp default as 2.62x too dark and 40px vs 26px too tall.
        // Disabled mirrors React's `.btn:disabled { opacity: .4 }`: the SAME elegant chrome
        // (theme.line hairline, tone fill, soft shadow, 8dp round) faded by alpha(0.4) — not
        // the brutal opaque #999/#E6E3E0/2dp disabled constants (which measured too dark).
        val shape = RoundedCornerShape(theme.cornerRadius)
        SlockAnchorButton(
            onClick = onClick,
            enabled = enabled,
            pressFeedback = enabled,
            modifier =
                modifier
                    .widthIn(min = minWidth)
                    .heightIn(min = minHeight)
                    // Always apply alpha and vary its value: a removed modifier leaves the
                    // stale native alpha behind on Kuikly, so a re-enabled button stayed dim.
                    .alpha(if (enabled) 1f else 0.4f)
                    .shadow(
                        elevation = 3.dp,
                        shape = shape,
                        clip = true,
                        ambientColor = theme.shadow.color,
                        spotColor = theme.shadow.color,
                    )
                    .background(backgroundColor, shape)
                    .border(theme.lineWidth, theme.line, shape)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            SlockBrutalActionButtonInnerContent(
                label = label,
                leadingIcon = leadingIcon,
                leadingIconSpinning = leadingIconSpinning,
                leadingIconSize = leadingIconSize,
                contentSpacing = contentSpacing,
                textStyle = textStyle,
                // Elegant disabled fades the whole button via alpha(0.4) above, so the
                // content keeps the enabled near-black ink (faded), matching React's
                // opacity approach rather than switching to the #999 disabled ink.
                enabled = true,
                disabledContentColor = disabledContentColor,
                disabledIconColor = disabledIconColor,
                trailingContent = trailingContent
            )
        }
        return
    }
    // Brutal register (unchanged, byte-identical): a hard structural offset-box shadow
    // behind a square surface.
    val resolvedShadowOffset = shadowOffset ?: theme.shadow.offset
    val shadowColor = if (enabled) theme.shadow.color else disabledShadowColor
    Box(
        modifier =
            modifier
                .padding(end = resolvedShadowOffset, bottom = resolvedShadowOffset),
        propagateMinConstraints = true
    ) {
        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .offset(x = resolvedShadowOffset, y = resolvedShadowOffset)
                    .background(shadowColor, RectangleShape)
                    .slockThemedCorners(theme.cornerRadius)
        )
        SlockAnchorButton(
            onClick = onClick,
            enabled = enabled,
            pressFeedback = enabled,
            modifier =
                Modifier
                    .widthIn(min = minWidth)
                    .heightIn(min = minHeight)
                    .background(surfaceBackground, RectangleShape)
                    .border(theme.lineWidth, borderColor, RectangleShape)
                    .slockThemedCorners(theme.cornerRadius)
                    // react buttons use 12px/6px padding: with 6dp vertical padding a
                    // 12sp/14sp-line label yields the same 28px box react renders for
                    // minHeight-28 buttons (8dp made them 30px tall) — task #370.
                    .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            SlockBrutalActionButtonInnerContent(
                label = label,
                leadingIcon = leadingIcon,
                leadingIconSpinning = leadingIconSpinning,
                leadingIconSize = leadingIconSize,
                contentSpacing = contentSpacing,
                textStyle = textStyle,
                enabled = enabled,
                disabledContentColor = disabledContentColor,
                disabledIconColor = disabledIconColor,
                trailingContent = trailingContent
            )
        }
    }
}

internal val SlockBrutalActionButtonDefaultTextStyle =
    SlockType.ButtonBold.copy(fontSize = 13.sp, lineHeight = 16.sp)

@Composable
internal fun SegmentedControl(
    selectedValue: String,
    options: List<SegmentedControlOption>,
    onValueSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(SegmentedControlItemGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEach { option ->
            val selected = option.value == selectedValue
            SegmentedControlItem(
                option = option,
                selected = selected,
                enabled = enabled,
                onClick = { onValueSelected(option.value) }
            )
        }
    }
}

@Composable
private fun SegmentedControlItem(
    option: SegmentedControlOption,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier.height(SegmentedControlItemHeight),
        propagateMinConstraints = true
    ) {
        // No offset shadow on the selected chip: react renders the active chip flat
        // (yellow fill + border only, no shadow-brutal) — task #370.
        Row(
            modifier =
                Modifier
                    .height(SegmentedControlItemHeight)
                    .background(if (selected) SlockColor.BrandYellow else SlockColor.SurfaceWhite)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .clickable(enabled = enabled) { onClick() }
                    .padding(horizontal = SegmentedControlItemHorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(SegmentedControlItemContentGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            option.iconName?.let { iconName ->
                SlockLucideIcon(
                    name = iconName,
                    modifier = Modifier.size(13.dp),
                    color = if (enabled) SlockColor.TextBlackArgb else SlockColor.TextMutedGrayArgb
                )
            }
            Text(
                text = option.label,
                style = SlockType.ButtonBold.copy(fontSize = 12.sp, lineHeight = 14.sp),
                color = if (enabled) SlockColor.TextBlack else SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            option.count?.takeIf { it > 0 }?.let { count ->
                // react (rui segmented-control, brutal theme): count is
                // font-mono text-xs leading-4 font-bold at text-foreground/65,
                // checked -> /70 — bold 12/16 at high alpha, not a muted 10sp.
                Text(
                    text = count.toString(),
                    style =
                        SlockType.Caption
                            .copy(fontFamily = SlockMonoFamily, fontSize = 12.sp, lineHeight = 16.sp)
                            .withSlockFontWeight(FontWeight.Bold),
                    color = SlockColor.TextBlack.copy(alpha = if (selected) 0.70f else 0.65f),
                    maxLines = 1
                )
            }
        }
    }
}

// Chip box is 32px tall with a 12px gap between chips in react
// (components.ui.segmented-control.states: chips y 48..144, gap 12px @3x) — task #370.
private val SegmentedControlItemHeight = 32.dp
private val SegmentedControlItemHorizontalPadding = 9.dp
private val SegmentedControlItemGap = 4.dp
private val SegmentedControlItemContentGap = 5.dp

@Composable
internal fun SlockToolbarChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconName: SlockLucideIconName? = null,
    trailingIconName: SlockLucideIconName? = null,
    active: Boolean = false,
    enabled: Boolean = true,
    uppercase: Boolean = true,
    count: Int? = null,
    activeBackgroundColor: Color = SlockColor.AccentCyan,
    inactiveBackgroundColor: Color = SlockColor.SurfaceWhite,
    borderColor: Color = SlockColor.EmptyStateGray,
    disabledBorderColor: Color = SlockColor.EmptyStateGray,
    activeShadowOffset: Dp = 0.dp,
    reserveActiveShadowSpace: Boolean = false,
    activeShadowColor: Color = SlockColor.ShadowBlack
) {
    val shouldDrawShadow = enabled && active && activeShadowOffset > 0.dp
    val reservedShadowOffset =
        if (shouldDrawShadow || reserveActiveShadowSpace) {
            activeShadowOffset
        } else {
            0.dp
        }
    val resolvedBorderColor =
        when {
            !enabled -> disabledBorderColor
            active -> SlockColor.BorderBlack
            else -> borderColor
        }
    Box(
        modifier =
            modifier
                .padding(end = reservedShadowOffset, bottom = reservedShadowOffset)
    ) {
        if (shouldDrawShadow) {
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .offset(x = activeShadowOffset, y = activeShadowOffset)
                        .background(activeShadowColor, RectangleShape)
                        .slockSquareCorners()
            )
        }
        Box(
            modifier =
                Modifier
                    .heightIn(min = 30.dp)
                    .background(if (active) activeBackgroundColor else inactiveBackgroundColor, RectangleShape)
                    .border(SlockStroke.Default, resolvedBorderColor, RectangleShape)
                    .slockSquareCorners()
                    .clickable(enabled = enabled, onClick = onClick)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                iconName?.let {
                    SlockLucideIcon(it, modifier = Modifier.size(14.dp), color = "#FF000000")
                }
                Text(
                    text = if (uppercase) label.slockDisplayUppercase() else label,
                    style = SlockType.TabLabel.copy(fontSize = 11.sp, lineHeight = 14.sp),
                    color = if (enabled) SlockColor.TextBlack else SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                count?.let {
                    Box(
                        modifier =
                            Modifier
                                .background(SlockColor.SurfaceWhite, RectangleShape)
                                .border(SlockStroke.Thin, SlockColor.BorderBlack, RectangleShape)
                                .slockSquareCorners()
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = it.toString(),
                            style = SlockType.Caption.copy(fontFamily = SlockMonoFamily, fontSize = 10.sp, lineHeight = 10.sp),
                            color = SlockColor.TextBlack,
                            maxLines = 1
                        )
                    }
                }
                trailingIconName?.let {
                    SlockLucideIcon(it, modifier = Modifier.size(12.dp), color = "#FF000000")
                }
            }
        }
    }
}

@Composable
internal fun SlockAnchoredToolbarChip(
    label: String,
    onClick: (SlockPopoverAnchor) -> Unit,
    anchorIdentity: Any,
    modifier: Modifier = Modifier,
    iconName: SlockLucideIconName? = null,
    trailingIconName: SlockLucideIconName? = SlockLucideIconName.ChevronDown,
    active: Boolean = false,
    enabled: Boolean = true,
    uppercase: Boolean = true,
    count: Int? = null,
    activeBackgroundColor: Color = SlockColor.AccentCyan,
    inactiveBackgroundColor: Color = SlockColor.SurfaceWhite,
    borderColor: Color = SlockColor.EmptyStateGray,
    disabledBorderColor: Color = SlockColor.EmptyStateGray,
    activeShadowOffset: Dp = 0.dp,
    reserveActiveShadowSpace: Boolean = false,
    activeShadowColor: Color = SlockColor.ShadowBlack,
    onAnchorChanged: ((SlockPopoverAnchorResolver) -> Unit)? = null
) {
    val currentOnAnchorChanged by rememberUpdatedState(onAnchorChanged)
    val anchor = remember(anchorIdentity) { SlockLiveRootAnchorRef(identity = anchorIdentity) }
    val anchorResolver = remember(anchor) { anchor.resolver(SlockPopoverLocalPoint.BottomStart) }
    var resolverNotified by remember(anchorResolver) { mutableStateOf(false) }
    Box(
        modifier =
            modifier.slockCaptureLiveRootAnchor(anchor) {
                if (!resolverNotified) {
                    resolverNotified = true
                    currentOnAnchorChanged?.invoke(anchorResolver)
                }
            }
    ) {
        SlockToolbarChip(
            label = label,
            iconName = iconName,
            trailingIconName = trailingIconName,
            active = active,
            enabled = enabled,
            uppercase = uppercase,
            count = count,
            activeBackgroundColor = activeBackgroundColor,
            inactiveBackgroundColor = inactiveBackgroundColor,
            borderColor = borderColor,
            disabledBorderColor = disabledBorderColor,
            activeShadowOffset = activeShadowOffset,
            reserveActiveShadowSpace = reserveActiveShadowSpace,
            activeShadowColor = activeShadowColor,
            onClick = {
                anchor.resolve(SlockPopoverLocalPoint.BottomStart)?.let(onClick)
            }
        )
    }
}

@Composable
internal fun SlockBrutalIconButton(
    iconName: SlockLucideIconName,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    clickIdentity: Any? = null,
    enabled: Boolean = true,
    backgroundColor: Color = SlockColor.SurfaceWhite,
    disabledBackgroundColor: Color = SlockColor.SurfaceCream,
    disabledAlpha: Float = 1f,
    borderColor: Color = SlockColor.BorderBlack,
    disabledBorderColor: Color = SlockColor.BorderBlack,
    shadowColor: Color = SlockColor.ShadowBlack,
    disabledShadowColor: Color = SlockColor.ShadowBlack,
    iconColor: String = "#FF000000",
    disabledIconColor: String = "#FF000000",
    size: Dp = 32.dp,
    shadowOffset: Dp = SlockShadow.BrutalSmall,
    iconSize: Dp = (size.value * 0.5f).dp,
    iconSpinning: Boolean = iconName == SlockLucideIconName.Loader || iconName == SlockLucideIconName.LoaderCircle
) {
    val resolvedBackground = if (enabled) backgroundColor else disabledBackgroundColor.copy(alpha = disabledAlpha)
    val resolvedBorder = if (enabled) borderColor else disabledBorderColor.copy(alpha = disabledAlpha)
    val resolvedShadow = if (enabled) shadowColor else disabledShadowColor.copy(alpha = disabledAlpha)
    val resolvedIconColor = if (enabled) iconColor else disabledIconColor
    Box(
        modifier =
            modifier
                .size(size + shadowOffset)
    ) {
        Box(
            modifier =
                Modifier
                    .size(size)
                    .offset(x = shadowOffset, y = shadowOffset)
                    .background(resolvedShadow)
        )
        SlockAnchorButton(
            onClick = onClick,
            onLongClick = onLongClick,
            longClickLabel = longClickLabel,
            enabled = enabled,
            pressFeedback = enabled,
            pressOffset = shadowOffset,
            clickIdentity = clickIdentity,
            modifier = Modifier
                .size(size)
                .then(
                    if (contentDescription.isNullOrBlank()) {
                        Modifier
                    } else {
                        Modifier.semantics {
                            this.contentDescription = contentDescription
                        }
                    }
                )
        ) {
            Box(
                modifier =
                    Modifier
                        .size(size)
                        .background(resolvedBackground)
                        .border(SlockStroke.Default, resolvedBorder),
                contentAlignment = Alignment.Center
            ) {
                SlockLucideIcon(iconName, color = resolvedIconColor, modifier = Modifier.size(iconSize), spinning = iconSpinning)
            }
        }
    }
}

@Composable
internal fun SlockBrutalPanelShell(
    modifier: Modifier = Modifier,
    shadowOffset: Dp = SlockShadow.Brutal,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .offset(x = shadowOffset, y = shadowOffset)
                    .background(SlockColor.ShadowBlack)
        )
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(SlockColor.SurfaceWhite)
                    .border(SlockStroke.Default, SlockColor.BorderBlack),
            content = content
        )
    }
}

@Composable
internal fun SlockBrutalListShell(
    height: Dp,
    modifier: Modifier = Modifier,
    shadowOffset: Dp = SlockShadow.Brutal,
    listModifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit
) {
    // Brutal-shadow pattern: outer Box reserves shadowOffset extra space on
    // right + bottom via padding; shadow Box inside is matchParentSize() +
    // offset(shadowOffset) so the dark slab peeks out uniformly. Foreground
    // LazyColumn fills the non-shadow area with bordered SurfaceWhite.
    // Mirrors the proven SlockBrutalCard pattern (SlockBrutalCard.kt) and
    // fixes the earlier broken `padding(end, bottom).background(ShadowBlack)`
    // pattern that did not actually reserve outer space for the shadow to
    // peek out.
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height + shadowOffset)
                .padding(end = shadowOffset, bottom = shadowOffset)
    ) {
        Box(
            Modifier
                .matchParentSize()
                .offset(x = shadowOffset, y = shadowOffset)
                .background(SlockColor.ShadowBlack)
        )
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(height)
                    .background(SlockColor.SurfaceWhite)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .then(listModifier),
            verticalArrangement = Arrangement.spacedBy(0.dp),
            content = content
        )
    }
}

@Composable
internal fun SlockBrutalListSectionHeader(
    label: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite.copy(alpha = 0.5f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = SlockType.SectionLabel.copy(color = SlockColor.TextBlack),
            color = SlockColor.TextBlack
        )
    }
}
