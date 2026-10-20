package build.raft.app.components

import build.raft.app.isAmbient
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
import build.raft.app.SlockRadius
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
import build.raft.app.slockArgbHex
import build.raft.app.resources.MR
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.compat.ui.KeyboardParams
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

// Brutal error-field wash (react `bg-red-50`-ish); raft fields keep the card fill on error.
internal val SlockFieldErrorTint = Color(0xFFFFF4F4)

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
    // raft: chips are 6dp-radius tags (`rounded-chip`); a colour block keeps its ink edge.
    val raftChip = slockRaftActive()
    val chipShape = if (raftChip) RoundedCornerShape(SlockRadius.Chip) else RectangleShape
    val chipModifier =
        modifier
            .heightIn(min = minHeight)
            .border(borderWidth, SlockColor.BorderBlack, chipShape)
            .background(color, chipShape)
            .then(if (raftChip) Modifier else Modifier.slockSquareCorners())
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
    if (slockRaftActive()) {
        SlockRaftFieldLabel(
            label = label,
            required = required,
            optional = optional,
            compact = size == SlockFormFieldSize.Compact,
            modifier = modifier
        )
        return
    }
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
    if (slockRaftActive()) {
        // raft DialogHeader: px-5 pt-5, headline 17/22 bold title, subhead 15/20 muted
        // description, ghost icon-md close (32dp, 18dp X) pulled into the corner.
        Row(
            modifier =
                modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 14.dp, top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f).padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = model.title,
                    style = SlockType.PageTitle.copy(fontSize = 17.sp, lineHeight = 22.sp).withSlockFontWeight(FontWeight.Bold),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (model.subtitle.isNotBlank()) {
                    Text(
                        text = model.subtitle,
                        style = SlockType.Body.copy(fontSize = 15.sp, lineHeight = 20.sp),
                        color = SlockColor.TextMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            SlockBrutalIconButton(
                iconName = model.dismissIcon,
                onClick = onDismiss,
                backgroundColor = Color.Transparent,
                size = 32.dp,
                iconSize = 18.dp
            )
        }
        return
    }
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
            if (slockRaftActive()) {
                // raft Dialog content: dialog radius 16, popover layer, seam ring, float shadow.
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .widthIn(max = model.maxWidth)
                            .slockFloatingContentClickTarget()
                            .slockRaftOverlaySurface(radius = SlockRadius.Dialog)
                ) {
                    SlockBrutalModalHeader(model = model, onDismiss = onDismiss)
                    content()
                }
                return@Box
            }
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
    modifier: Modifier = Modifier,
    // raft: the row sits in a menu popup, so it takes the menu item's `px-2.5 py-1.5`.
    menuItemPadding: Boolean = false
) {
    // The bare material3 Button paints the theme's default filled container
    // (purple in this build) over the sheet's surface and disables text via the
    // default content color. Give the row explicit register-aware colors so the
    // parent's selected/unselected surface shows through and ink stays readable.
    val theme = LocalSlockTheme.current
    if (theme.isAmbient) {
        SlockRaftActionSheetRow(model = model, onClick = onClick, modifier = modifier, menuItemPadding = menuItemPadding)
        return
    }
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
 * raft action-sheet row: a menu-item row (control radius, pressed wash, no ink box) with
 * a soft icon tile. Destructive rows switch to the danger soft tile and danger-strong ink.
 */
@Composable
private fun SlockRaftActionSheetRow(
    model: SlockActionSheetRowModel,
    onClick: () -> Unit,
    modifier: Modifier,
    menuItemPadding: Boolean = false
) {
    var pressed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(SlockRadius.Control)
    val titleColor = if (model.destructive) SlockColor.DangerStrong else SlockColor.TextBlack
    SlockAnchorButton(
        onClick = onClick,
        pressOffset = 0.dp,
        onPressedChange = { pressed = it },
        modifier =
            modifier
                .fillMaxWidth()
                .background(if (pressed) SlockColor.FillPressed else Color.Transparent, shape)
                .padding(
                    horizontal = if (menuItemPadding) SlockRaftMetrics.MenuItemPaddingHorizontal else 12.dp,
                    vertical = if (menuItemPadding) 6.dp else 10.dp
                )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier =
                    Modifier
                        .size(32.dp)
                        .background(if (model.destructive) SlockColor.DangerSoft else SlockColor.FillMuted, shape),
                contentAlignment = Alignment.Center
            ) {
                SlockLucideIcon(
                    model.icon,
                    modifier = Modifier.size(18.dp),
                    color = slockArgbHex(if (model.destructive) SlockColor.DangerStrong else SlockColor.TextMuted)
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = model.title,
                    style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp).withSlockFontWeight(FontWeight.SemiBold),
                    color = titleColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (model.subtitle.isNotBlank()) {
                    Text(
                        text = model.subtitle,
                        style = SlockType.Caption.copy(fontSize = 13.sp, lineHeight = 18.sp),
                        color = SlockColor.TextMuted,
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
    labelAccessory: (@Composable () -> Unit)? = null,
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
        size = labelSize,
        labelAccessory = labelAccessory
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
            enabledBackgroundColor = if (hasError) SlockFieldErrorTint else SlockColor.SurfaceWhite,
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
    labelAccessory: (@Composable () -> Unit)? = null,
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
        size = labelSize,
        labelAccessory = labelAccessory
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
            enabledBackgroundColor = if (hasError) SlockFieldErrorTint else SlockColor.SurfaceWhite,
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
 * (`slockSquareCorners`) with a 2px black border on the white/cream surface. Raft register
 * (raft-ui 0.6.0 Input/Textarea): 8dp control radius + 1.5dp `--line-field` edge on the card
 * layer, no offset shadow and no reserved gutter (the borderRadius-prop path does not round at
 * runtime, so the rounded shape is applied straight to `background`/`border`). The brutal error
 * pair (AccentRed edge + pink wash) maps to the `--danger-strong` edge on the card fill; any
 * other explicit [borderColor] passes through.
 */
@Composable
private fun SlockBrutalInputFieldFrame(
    modifier: Modifier,
    enabled: Boolean,
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
    if (theme.isAmbient) {
        // raft-ui Input / Textarea: control radius 8, 1.5dp `--line-strong` (`--line-field`)
        // edge on the card layer, no shadow. Invalid swaps the edge to `--danger-strong`
        // (the fill stays the card); disabled is the opaque `--fill-muted` / `--line-hairline` pair.
        // Read-only fields keep the resting look, like web `<Input readOnly>`.
        val shape = RoundedCornerShape(SlockRadius.Control)
        val raftBorderColor =
            when {
                !enabled -> SlockColor.BorderHairline
                borderColor == SlockColor.BorderBlack -> SlockColor.LineField
                borderColor == SlockColor.AccentRed -> SlockColor.DangerStrong
                else -> borderColor
            }
        val raftSurfaceColor =
            when {
                !enabled ->
                    if (disabledBackgroundColor == SlockColor.SurfaceCream) SlockColor.FillMuted else disabledBackgroundColor
                enabledBackgroundColor == SlockColor.SurfaceWhite || enabledBackgroundColor == SlockFieldErrorTint -> SlockColor.Card
                else -> enabledBackgroundColor
            }
        Box(
            modifier =
                modifier
                    .fillMaxWidth()
                    .height(fieldMinHeight)
                    .background(raftSurfaceColor, shape)
                    .border(SlockStroke.Control, raftBorderColor, shape)
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
    textStyle: TextStyle = SlockBrutalInputDefaultTextStyle,
    fieldMinHeight: Dp = 42.dp,
    containerTapRequestsFocus: Boolean = false,
    horizontalPadding: Dp = SlockBrutalInputLegacyHorizontalPadding,
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
    val raft = theme.isAmbient
    // raft-ui fields: 44dp tall, 12dp side padding, `text-field` 16/24 type, placeholder
    // ink. Only the legacy brutal defaults are remapped; explicit caller metrics stay.
    val resolvedTextStyle =
        when {
            !raft -> textStyle
            textStyle == SlockBrutalInputDefaultTextStyle ->
                textStyle.copy(
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    color = if (enabled) SlockColor.TextBlack else SlockColor.ForegroundDisabled
                )
            else -> textStyle.copy(color = if (enabled) theme.ink else SlockColor.ForegroundDisabled)
        }
    val resolvedHorizontalPadding =
        if (raft && horizontalPadding == SlockBrutalInputLegacyHorizontalPadding) {
            SlockRaftMetrics.FieldPaddingHorizontal
        } else {
            horizontalPadding
        }
    val resolvedPlaceholderColor =
        if (raft && placeholderColor == SlockColor.TextMutedGray) SlockColor.ForegroundHint else placeholderColor
    SlockBrutalInputFieldFrame(
        modifier = modifier,
        enabled = enabled,
        focusPolicy = focusPolicy,
        focused = focused,
        forceFocused = forceFocused,
        showShadow = showShadow,
        fieldMinHeight =
            slockBrutalInputFieldFrameMinHeight(
                fieldMinHeight = slockRaftFieldHeight(fieldMinHeight),
                maxLines = maxLines,
                lineHeight = resolvedTextStyle.slockLineHeightDp(),
                verticalPadding = verticalPadding
            ),
        horizontalPadding = resolvedHorizontalPadding,
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
                placeholderColor = resolvedPlaceholderColor,
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
                    backgroundColor = if (raft) Color.Transparent else SlockColor.SurfaceWhite,
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
    textStyle: TextStyle = SlockBrutalInputDefaultTextStyle,
    fieldMinHeight: Dp = 42.dp,
    containerTapRequestsFocus: Boolean = false,
    horizontalPadding: Dp = SlockBrutalInputLegacyHorizontalPadding,
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
    val raft = theme.isAmbient
    // raft-ui fields: 44dp tall, 12dp side padding, `text-field` 16/24 type, placeholder
    // ink. Only the legacy brutal defaults are remapped; explicit caller metrics stay.
    val resolvedTextStyle =
        when {
            !raft -> textStyle
            textStyle == SlockBrutalInputDefaultTextStyle ->
                textStyle.copy(
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    color = if (enabled) SlockColor.TextBlack else SlockColor.ForegroundDisabled
                )
            else -> textStyle.copy(color = if (enabled) theme.ink else SlockColor.ForegroundDisabled)
        }
    val resolvedHorizontalPadding =
        if (raft && horizontalPadding == SlockBrutalInputLegacyHorizontalPadding) {
            SlockRaftMetrics.FieldPaddingHorizontal
        } else {
            horizontalPadding
        }
    val resolvedPlaceholderColor =
        if (raft && placeholderColor == SlockColor.TextMutedGray) SlockColor.ForegroundHint else placeholderColor
    SlockBrutalInputFieldFrame(
        modifier = modifier,
        enabled = enabled,
        focusPolicy = focusPolicy,
        focused = focused,
        forceFocused = forceFocused,
        showShadow = showShadow,
        fieldMinHeight =
            slockBrutalInputFieldFrameMinHeight(
                fieldMinHeight = slockRaftFieldHeight(fieldMinHeight),
                maxLines = maxLines,
                lineHeight = resolvedTextStyle.slockLineHeightDp(),
                verticalPadding = verticalPadding
            ),
        horizontalPadding = resolvedHorizontalPadding,
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
                placeholderColor = resolvedPlaceholderColor,
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
                    backgroundColor = if (raft) Color.Transparent else SlockColor.SurfaceWhite,
                    size = 18.dp,
                    shadowOffset = 2.dp
                )
            }
            trailingContent?.invoke()
        }
    }
}

/** The brutal field type (14/18); raft swaps a field still on it for `text-field` 16/24. */
internal val SlockBrutalInputDefaultTextStyle: TextStyle
    get() = SlockType.Input.copy(fontSize = 14.sp, lineHeight = 18.sp, color = SlockColor.TextBlack)

internal val SlockBrutalInputLegacyHorizontalPadding = 10.dp

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

// Brutal read-only fill (Tailwind gray-50).
internal val SlockReadOnlyFieldLegacyBackground = Color(0xFFF9FAFB)

@Composable
internal fun SlockBrutalReadOnlyValueField(
    value: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    fieldMinHeight: Dp = 42.dp,
    horizontalPadding: Dp = 12.dp,
    verticalPadding: Dp = 8.dp,
    backgroundColor: Color = SlockReadOnlyFieldLegacyBackground,
    borderColor: Color = SlockColor.BorderBlack.copy(alpha = 0.30f),
    textStyle: TextStyle = SlockType.Input.copy(fontSize = 14.sp, lineHeight = 18.sp, color = SlockColor.TextMutedGray),
    textColor: Color = SlockColor.TextMutedGray
) {
    val raft = slockRaftActive()
    val shape = if (raft) RoundedCornerShape(SlockRadius.Control) else RectangleShape
    // raft: a non-editable value is the disabled field pair (`--fill-muted` fill,
    // 1.5dp `--line-hairline` edge, muted ink) at the 44dp field height.
    val resolvedBackground = if (raft && backgroundColor == SlockReadOnlyFieldLegacyBackground) SlockColor.FillMuted else backgroundColor
    val resolvedBorder = if (raft && borderColor == SlockColor.BorderBlack.copy(alpha = 0.30f)) SlockColor.BorderHairline else borderColor
    val resolvedTextColor = if (raft && textColor == SlockColor.TextMutedGray) SlockColor.TextMuted else textColor
    Box(
        modifier =
            modifier
                .heightIn(min = slockRaftFieldHeight(fieldMinHeight))
                .background(resolvedBackground, shape)
                .border(SlockStroke.Default, resolvedBorder, shape)
                .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = value,
            style = textStyle.copy(color = resolvedTextColor),
            color = resolvedTextColor,
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
    textStyle: TextStyle = SlockBrutalSearchDefaultTextStyle,
    onFocus: () -> Unit = {},
    onBlur: () -> Unit = {}
) {
    var focused by remember { mutableStateOf(false) }
    if (slockRaftActive()) {
        SlockRaftSearchFieldFrame(
            modifier = modifier,
            enabled = enabled,
            leadingIcon = leadingIcon,
            showClear = showClearWhenNotEmpty && value.isNotEmpty(),
            onClear = onClear
        ) {
            TextField(
                value = value,
                onValueChange = { if (enabled) onValueChange(it) },
                placeholder = placeholder,
                placeholderColor = SlockColor.ForegroundHint,
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
                textStyle = slockRaftSearchTextStyle(textStyle, enabled),
                modifier = Modifier.weight(1f)
            )
        }
        return
    }
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
    textStyle: TextStyle = SlockBrutalSearchDefaultTextStyle,
    onFocus: () -> Unit = {},
    onBlur: () -> Unit = {}
) {
    var focused by remember { mutableStateOf(false) }
    if (slockRaftActive()) {
        SlockRaftSearchFieldFrame(
            modifier = modifier,
            enabled = enabled,
            leadingIcon = leadingIcon,
            showClear = showClearWhenNotEmpty && value.text.isNotEmpty(),
            onClear = onClear
        ) {
            TextField(
                value = value,
                onValueChange = { if (enabled) onValueChange(it) },
                placeholder = placeholder,
                placeholderColor = SlockColor.ForegroundHint,
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
                textStyle = slockRaftSearchTextStyle(textStyle, enabled),
                modifier = Modifier.weight(1f)
            )
        }
        return
    }
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

/** The brutal search type (13/16); raft swaps a field still on it for `text-field` 16/24. */
internal val SlockBrutalSearchDefaultTextStyle: TextStyle
    get() = SlockType.Caption.copy(fontSize = 13.sp, lineHeight = 16.sp, color = SlockColor.TextBlack)

private fun slockRaftSearchTextStyle(textStyle: TextStyle, enabled: Boolean): TextStyle {
    val ink = if (enabled) SlockColor.TextBlack else SlockColor.ForegroundDisabled
    return if (textStyle == SlockBrutalSearchDefaultTextStyle) {
        SlockType.Input.copy(fontSize = 16.sp, lineHeight = 24.sp, color = ink)
    } else {
        textStyle.copy(color = ink)
    }
}

/**
 * raft-ui InputGroup with an inline-start search addon: 44dp, control radius, 1.5dp
 * `--line-strong` edge on the card layer, no shadow; the 18dp addon icon and the clear
 * affordance use the hint ink. Disabled is the opaque `--fill-muted` / `--line-hairline` pair.
 */
@Composable
private fun SlockRaftSearchFieldFrame(
    modifier: Modifier,
    enabled: Boolean,
    leadingIcon: SlockLucideIconName,
    showClear: Boolean,
    onClear: () -> Unit,
    field: @Composable RowScope.() -> Unit
) {
    val shape = RoundedCornerShape(SlockRadius.Control)
    val hintArgb = slockArgbHex(if (enabled) SlockColor.ForegroundHint else SlockColor.ForegroundDisabled)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(SlockRaftMetrics.FieldHeight)
                .background(if (enabled) SlockColor.Card else SlockColor.FillMuted, shape)
                .border(SlockStroke.Control, if (enabled) SlockColor.LineField else SlockColor.BorderHairline, shape)
                .padding(horizontal = SlockRaftMetrics.FieldPaddingHorizontal),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SlockLucideIcon(leadingIcon, color = hintArgb, modifier = Modifier.size(18.dp))
        field()
        if (showClear) {
            SlockLucideIcon(
                SlockLucideIconName.X,
                color = hintArgb,
                modifier =
                    Modifier
                        .size(16.dp)
                        .clickable(enabled = enabled, onClick = onClear)
            )
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
    onClick2: ((SlockPopoverAnchor) -> Unit)? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backgroundColor: Color = SlockColor.SurfaceWhite,
    // react bg-palette-stone #C0B9B1 @ .btn-brutal-sm:disabled opacity-40 over white = #E6E3E0 (visual-fix pilot, AI analysis 2026-07-09)
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
    // The register is read from LocalSlockTheme. The raft themes render raft-ui 0.6.0's
    // Button ([SlockRaftActionButton]: control radius, 1.5dp edge, sticker/outline/ghost
    // variants from [backgroundColor], press overlay, no shadow); the disabled*/shadow*
    // parameters only shape the legacy brutal register below (hard offset-box shadow).
    val theme = LocalSlockTheme.current
    val borderColor = if (enabled) theme.line else disabledBorderColor
    val surfaceBackground = if (enabled) backgroundColor else disabledBackgroundColor
    if (theme.isAmbient) {
        SlockRaftActionButton(
            label = label,
            onClick = onClick,
            onClick2 = onClick2,
            modifier = modifier,
            enabled = enabled,
            backgroundColor = backgroundColor,
            minWidth = minWidth,
            minHeight = minHeight,
            leadingIcon = leadingIcon,
            leadingIconSpinning = leadingIconSpinning,
            textStyle = textStyle,
            trailingContent = trailingContent
        )
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
            onClick2 = onClick2,
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

// The brutal button's default min width; raft buttons have none (they hug the label).
private val SlockBrutalActionButtonLegacyMinWidth = 82.dp

/**
 * raft-ui 0.6.0 `Button` (dist/components button recipe):
 *  - sizes: `md` h-8 / px-3 / 13/18 bold / 16dp icons (raft renders `xs`/`sm` as `md`),
 *    `lg` h-11 / px-4 / 15/20, `xl` h-13 / px-5 / 16/24; control radius 8dp;
 *  - variants from the caller's fill ([slockRaftButtonChrome]): pink accent / danger /
 *    warning stickers with a 1.5dp sticker-ink edge and ink type, or the paper outline
 *    button with a 1.5dp `--line-strong` edge, or ghost;
 *  - press lays `--sticker-pressed` / `--fill-pressed` over the fill (no offset, no
 *    shadow: controls never cast one);
 *  - disabled is opaque: `--fill-muted` fill, `--line-hairline` edge, `--foreground-disabled` ink.
 * A [minHeight] below 38dp picks `md`, up to 52dp `lg`, beyond that `xl`.
 */
@Composable
private fun SlockRaftActionButton(
    label: String,
    onClick: () -> Unit,
    onClick2: ((SlockPopoverAnchor) -> Unit)?,
    modifier: Modifier,
    enabled: Boolean,
    backgroundColor: Color,
    minWidth: Dp,
    minHeight: Dp,
    leadingIcon: SlockLucideIconName?,
    leadingIconSpinning: Boolean,
    textStyle: TextStyle,
    trailingContent: (@Composable () -> Unit)?
) {
    // raft-ui dialog footers pair the primary sticker with a ghost Cancel; a
    // paper (white) button inside SlockDialogFooter is that ghost secondary.
    val chrome =
        slockRaftButtonChrome(
            if (LocalSlockRaftDialogFooter.current && backgroundColor == SlockColor.SurfaceWhite) {
                Color.Transparent
            } else {
                backgroundColor
            }
        )
    var pressed by remember { mutableStateOf(false) }
    val height: Dp
    val horizontalPadding: Dp
    val fontSize: TextUnit
    val lineHeight: TextUnit
    val iconSize: Dp
    when {
        minHeight >= SlockRaftMetrics.ControlHeightXl -> {
            height = SlockRaftMetrics.ControlHeightXl
            horizontalPadding = 20.dp
            fontSize = 16.sp
            lineHeight = 24.sp
            iconSize = 18.dp
        }
        minHeight >= 38.dp -> {
            height = SlockRaftMetrics.ControlHeightLg
            horizontalPadding = 16.dp
            fontSize = 15.sp
            lineHeight = 20.sp
            iconSize = 18.dp
        }
        else -> {
            height = SlockRaftMetrics.ControlHeightMd
            horizontalPadding = 12.dp
            fontSize = 13.sp
            lineHeight = 18.sp
            iconSize = 16.dp
        }
    }
    // `has-data-[icon=inline-start]:pl-2.5` (md) / `pl-3.5` (lg): an icon pulls its edge in by 2dp.
    val startPadding = if (leadingIcon != null) horizontalPadding - 2.dp else horizontalPadding
    val fill =
        when {
            !enabled -> SlockColor.FillMuted
            pressed -> slockRaftOverlay(chrome.pressedOverlay, chrome.fill)
            else -> chrome.fill
        }
    val border = if (enabled) chrome.border else SlockColor.BorderHairline
    val content = if (enabled) chrome.content else SlockColor.ForegroundDisabled
    val shape = RoundedCornerShape(SlockRadius.Control)
    SlockAnchorButton(
        onClick = onClick,
        onClick2 = onClick2,
        enabled = enabled,
        pressFeedback = enabled,
        pressOffset = 0.dp,
        onPressedChange = { pressed = it },
        modifier =
            modifier
                .widthIn(min = if (minWidth == SlockBrutalActionButtonLegacyMinWidth) 0.dp else minWidth)
                .heightIn(min = height)
                .background(fill, shape)
                .border(SlockStroke.Control, border, shape)
                .padding(start = startPadding, end = horizontalPadding)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leadingIcon != null) {
                SlockLucideIcon(
                    leadingIcon,
                    modifier = Modifier.size(iconSize),
                    color = slockArgbHex(content),
                    spinning = leadingIconSpinning
                )
            }
            Text(
                text = label,
                style = textStyle.copy(fontSize = fontSize, lineHeight = lineHeight).withSlockFontWeight(FontWeight.Bold),
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            trailingContent?.invoke()
        }
    }
}

@Composable
internal fun SegmentedControl(
    selectedValue: String,
    options: List<SegmentedControlOption>,
    onValueSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    if (slockRaftActive()) {
        // raft-ui SegmentedControl: a `--fill-muted` tray (radius 11, 3dp inset, 2dp gap);
        // the checked item is the yellow sticker with an ink edge.
        Row(
            modifier =
                modifier
                    .background(SlockColor.FillMuted, RoundedCornerShape(11.dp))
                    .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            options.forEach { option ->
                SlockRaftSegmentedItem(
                    label = option.label,
                    iconName = option.iconName,
                    count = option.count,
                    selected = option.value == selectedValue,
                    enabled = enabled,
                    onClick = { onValueSelected(option.value) }
                )
            }
        }
        return
    }
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

/**
 * One raft-ui segmented / tab item (`segmented-control` item, `tabs` default tab): 32dp,
 * control radius, 1.5dp edge that is transparent at rest and sticker ink when checked,
 * checked fill = the yellow `--brand` sticker with bold ink type, pressed = fill wash.
 */
@Composable
internal fun SlockRaftSegmentedItem(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    iconName: SlockLucideIconName? = null,
    count: Int? = null
) {
    var pressed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(SlockRadius.Control)
    val fill =
        when {
            selected -> if (pressed) slockRaftOverlay(SlockColor.StickerPressed, SlockColor.Brand) else SlockColor.Brand
            pressed && enabled -> SlockColor.FillPressed
            else -> Color.Transparent
        }
    val ink =
        when {
            !enabled -> SlockColor.ForegroundDisabled
            selected -> SlockColor.StickerInk
            else -> SlockColor.TextMuted
        }
    SlockAnchorButton(
        onClick = onClick,
        enabled = enabled,
        pressFeedback = enabled,
        pressOffset = 0.dp,
        onPressedChange = { pressed = it },
        modifier =
            Modifier
                .height(SlockRaftMetrics.ControlHeightMd)
                .background(fill, shape)
                .border(SlockStroke.Control, if (selected) SlockColor.StickerInk else Color.Transparent, shape)
                .padding(horizontal = 12.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            iconName?.let {
                SlockLucideIcon(name = it, modifier = Modifier.size(16.dp), color = slockArgbHex(ink))
            }
            Text(
                text = label,
                style =
                    SlockType.Body
                        .copy(fontSize = 14.sp, lineHeight = 20.sp)
                        .withSlockFontWeight(if (selected) FontWeight.Bold else FontWeight.SemiBold),
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            count?.takeIf { it > 0 }?.let {
                Text(
                    text = it.toString(),
                    style = SlockType.Caption.copy(fontFamily = SlockMonoFamily, fontSize = 12.sp, lineHeight = 16.sp),
                    color = ink,
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
    if (slockRaftActive()) {
        SlockRaftPickerChip(
            label = label,
            onClick = onClick,
            modifier = modifier,
            iconName = iconName,
            trailingIconName = trailingIconName,
            active = active,
            enabled = enabled,
            count = count,
            activeBackgroundColor =
                if (activeBackgroundColor == SlockColor.AccentCyan) SlockColor.InfoSoft else activeBackgroundColor
        )
        return
    }
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

/**
 * raft-ui `PickerTriggerButton` (filter / toolbar chip): 32dp, control radius, 1dp
 * `--line-seam` ring at rest, 13/18 semibold muted type in sentence case; a chip with a
 * value is `--info-soft` with ink type and no ring; pressed lays the fill wash; disabled
 * is `--fill-muted` with disabled ink. Counts are mono meta text, not a boxed badge.
 */
@Composable
private fun SlockRaftPickerChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
    iconName: SlockLucideIconName?,
    trailingIconName: SlockLucideIconName?,
    active: Boolean,
    enabled: Boolean,
    count: Int?,
    activeBackgroundColor: Color
) {
    var pressed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(SlockRadius.Control)
    val restingFill = if (active) activeBackgroundColor else Color.Transparent
    val fill =
        when {
            !enabled -> SlockColor.FillMuted
            pressed -> slockRaftOverlay(SlockColor.FillPressed, restingFill)
            else -> restingFill
        }
    val ink =
        when {
            !enabled -> SlockColor.ForegroundDisabled
            active -> SlockColor.TextBlack
            else -> SlockColor.TextMuted
        }
    val ring = if (!enabled || active) Color.Transparent else SlockColor.BorderSeam
    SlockAnchorButton(
        onClick = onClick,
        enabled = enabled,
        pressFeedback = enabled,
        pressOffset = 0.dp,
        onPressedChange = { pressed = it },
        modifier =
            modifier
                .height(SlockRaftMetrics.ControlHeightMd)
                .background(fill, shape)
                .border(SlockStroke.Thin, ring, shape)
                .padding(horizontal = 10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            iconName?.let {
                SlockLucideIcon(it, modifier = Modifier.size(14.dp), color = slockArgbHex(ink), strokeWidth = 2f)
            }
            Text(
                text = label,
                style = SlockType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp).withSlockFontWeight(FontWeight.SemiBold),
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            count?.let {
                Text(
                    text = it.toString(),
                    style = SlockType.Caption.copy(fontFamily = SlockMonoFamily, fontSize = 12.sp, lineHeight = 16.sp),
                    color = if (enabled) SlockColor.ForegroundHint else SlockColor.ForegroundDisabled,
                    maxLines = 1
                )
            }
            trailingIconName?.let {
                SlockLucideIcon(
                    it,
                    modifier = Modifier.size(14.dp),
                    color = slockArgbHex(if (enabled) SlockColor.ForegroundHint else SlockColor.ForegroundDisabled),
                    strokeWidth = 2f
                )
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
    if (slockRaftActive()) {
        SlockRaftIconButton(
            iconName = iconName,
            onClick = onClick,
            onLongClick = onLongClick,
            longClickLabel = longClickLabel,
            contentDescription = contentDescription,
            modifier = modifier,
            clickIdentity = clickIdentity,
            enabled = enabled,
            backgroundColor = backgroundColor,
            iconColor = iconColor,
            size = size,
            iconSize = iconSize,
            iconSpinning = iconSpinning
        )
        return
    }
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

/**
 * raft-ui 0.6.0 icon button (`size="icon-md"` 32dp / `icon-lg` 44dp): the same variant
 * chrome as [SlockRaftActionButton] on a square control-radius box, no shadow and no
 * reserved shadow gutter, press = overlay wash. The default black icon ink follows the
 * variant's ink so it reads in dark mode; an explicit [iconColor] is kept.
 */
@Composable
private fun SlockRaftIconButton(
    iconName: SlockLucideIconName,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    longClickLabel: String?,
    contentDescription: String?,
    modifier: Modifier,
    clickIdentity: Any?,
    enabled: Boolean,
    backgroundColor: Color,
    iconColor: String,
    size: Dp,
    iconSize: Dp,
    iconSpinning: Boolean
) {
    val chrome = slockRaftButtonChrome(backgroundColor)
    var pressed by remember { mutableStateOf(false) }
    val fill =
        when {
            !enabled -> if (chrome.kind == SlockRaftButtonKind.Ghost) Color.Transparent else SlockColor.FillMuted
            pressed -> slockRaftOverlay(chrome.pressedOverlay, chrome.fill)
            else -> chrome.fill
        }
    val border =
        when {
            chrome.kind == SlockRaftButtonKind.Ghost -> Color.Transparent
            enabled -> chrome.border
            else -> SlockColor.BorderHairline
        }
    val resolvedIconColor =
        when {
            !enabled -> slockArgbHex(SlockColor.ForegroundDisabled)
            iconColor == SlockRaftLegacyIconInkArgb -> slockArgbHex(chrome.content)
            else -> iconColor
        }
    val shape = RoundedCornerShape(SlockRadius.Control)
    SlockAnchorButton(
        onClick = onClick,
        onLongClick = onLongClick,
        longClickLabel = longClickLabel,
        enabled = enabled,
        pressFeedback = enabled,
        pressOffset = 0.dp,
        clickIdentity = clickIdentity,
        onPressedChange = { pressed = it },
        modifier =
            modifier
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
                .background(fill, shape)
                .border(SlockStroke.Control, border, shape)
    ) {
        SlockLucideIcon(iconName, color = resolvedIconColor, modifier = Modifier.size(iconSize), spinning = iconSpinning)
    }
}

// The brutal primitives' hard-coded black icon ink; raft swaps it for the themed ink.
internal const val SlockRaftLegacyIconInkArgb = "#FF000000"

@Composable
internal fun SlockBrutalPanelShell(
    modifier: Modifier = Modifier,
    shadowOffset: Dp = SlockShadow.Brutal,
    content: @Composable BoxScope.() -> Unit
) {
    if (slockRaftActive()) {
        // raft Card: container radius, card layer, hairline edge, no shadow.
        Box(
            modifier = modifier.fillMaxWidth().slockRaftCardSurface(),
            content = content
        )
        return
    }
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
    if (slockRaftActive()) {
        // raft Card surface around the list: container radius, hairline, no shadow.
        Box(
            modifier =
                modifier
                    .fillMaxWidth()
                    .height(height)
                    .slockRaftCardSurface()
                    .clipToBounds()
        ) {
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(height)
                        .then(listModifier),
                verticalArrangement = Arrangement.spacedBy(0.dp),
                content = content
            )
        }
        return
    }
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
    val raft = slockRaftActive()
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(if (raft) Color.Transparent else SlockColor.SurfaceWhite.copy(alpha = 0.5f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (raft) {
            // raft section title (`rf-section`): 13/18 bold hint ink, sentence case.
            Text(
                text = label,
                style = SlockType.SectionLabel.copy(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp)
                    .withSlockFontWeight(FontWeight.Bold),
                color = SlockColor.ForegroundHint
            )
        } else {
            Text(
                text = label,
                style = SlockType.SectionLabel.copy(color = SlockColor.TextBlack),
                color = SlockColor.TextBlack
            )
        }
    }
}
