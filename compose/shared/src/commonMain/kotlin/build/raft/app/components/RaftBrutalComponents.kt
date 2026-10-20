package build.raft.app.components

import build.raft.app.Button as RaftAnchorButton
import build.raft.app.RaftColor
import build.raft.app.RaftLucideIcon
import build.raft.app.RaftMonoFamily
import build.raft.app.RaftLucideIconName
import build.raft.app.RaftLiveRootAnchorRef
import build.raft.app.RaftPopoverAnchor
import build.raft.app.RaftPopoverAnchorResolver
import build.raft.app.RaftPopoverLocalPoint
import build.raft.app.RaftModalDismissBarrier
import build.raft.app.RaftAppRadius
import build.raft.app.RaftShadow
import build.raft.app.RaftAppSpacing
import build.raft.app.RaftAppStroke
import build.raft.app.RaftType
import build.raft.app.TextField
import build.raft.app.raftFloatingContentClickTarget
import build.raft.app.raftCaptureLiveRootAnchor
import build.raft.app.LocalRaftTheme
import build.raft.app.withRaftFontWeight
import build.raft.app.raftArgbHex
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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

internal data class RaftModalShellModel(
    val title: String,
    val subtitle: String = "",
    val overlayAlpha: Float = 0.60f,
    val maxWidth: Dp = 384.dp,
    val dismissIcon: RaftLucideIconName = RaftLucideIconName.X
)

internal data class RaftActionSheetRowModel(
    val title: String,
    val subtitle: String = "",
    val icon: RaftLucideIconName,
    val destructive: Boolean = false
)

internal data class SegmentedControlOption(
    val value: String,
    val label: String,
    val iconName: RaftLucideIconName? = null,
    val count: Int? = null
)

// Brutal error-field wash (react `bg-red-50`-ish); raft fields keep the card fill on error.
internal val RaftFieldErrorTint = Color(0xFFFFF4F4)

internal val RaftDisabledActionBackground = Color(0xFFFFD8E5)
internal val RaftDisabledDestructiveActionBackground = Color(0xFFFFE5E5)
internal val RaftDisabledActionBorder = Color(0xFFB3B3B3)
internal val RaftDisabledActionShadow = Color(0xFFB8B8B8)
internal val RaftDisabledActionContent = RaftColor.SurfaceWhite
internal const val RaftDisabledActionContentArgb = RaftColor.SurfaceWhiteArgb

internal fun raftDisabledActionBackgroundFor(activeColor: Color): Color =
    when (activeColor) {
        RaftColor.AccentRed -> RaftDisabledDestructiveActionBackground
        else -> RaftDisabledActionBackground
    }

internal enum class RaftAnchorPlacement {
    Tap,
    BottomStart,
    BottomEnd
}

internal enum class RaftMetaChipClickDispatch {
    Disabled,
    Plain,
    Anchored
}

internal fun resolveRaftMetaChipClickDispatch(
    hasPlainClick: Boolean,
    hasAnchoredClick: Boolean
): RaftMetaChipClickDispatch =
    when {
        hasPlainClick -> RaftMetaChipClickDispatch.Plain
        hasAnchoredClick -> RaftMetaChipClickDispatch.Anchored
        else -> RaftMetaChipClickDispatch.Disabled
    }

@Composable
internal fun RaftChipLoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 12.dp,
    color: String = RaftColor.TextMutedGrayArgb
) {
    RaftLucideIcon(
        name = RaftLucideIconName.LoaderCircle,
        modifier = modifier.size(size),
        color = color,
        spinning = true
    )
}

@Composable
internal fun RaftAnchoredMetaChip(
    label: String,
    iconName: RaftLucideIconName?,
    color: Color,
    onClick: ((RaftPopoverAnchor) -> Unit)?,
    onClickWithoutAnchor: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    iconSize: Dp = 10.dp,
    contentColor: Color = RaftColor.TextBlack,
    iconColorArgb: String = RaftColor.TextBlackArgb,
    iconFillArgb: String? = null,
    trailingIconName: RaftLucideIconName? = null,
    trailingIconSize: Dp = iconSize,
    borderWidth: Dp = RaftAppStroke.Thin,
    minHeight: Dp = 20.dp,
    horizontalPadding: Dp = 6.dp,
    verticalPadding: Dp = 2.dp,
    labelFontSize: TextUnit = 10.sp,
    labelLineHeight: TextUnit = 12.sp,
    pressFeedback: Boolean = onClick != null || onClickWithoutAnchor != null,
    anchorPlacement: RaftAnchorPlacement = RaftAnchorPlacement.Tap,
    clickIdentity: Any? = null,
    anchorOwnerIdentity: Any? = null,
    // Drawn before the label instead of [iconName] (e.g. raft-ui's task status pixel).
    leadingContent: (@Composable () -> Unit)? = null,
    labelFontWeight: FontWeight = FontWeight.Bold,
    contentGap: Dp = RaftAppSpacing.Xs,
    startPadding: Dp = horizontalPadding
) {
    val clickDispatch =
        resolveRaftMetaChipClickDispatch(
            hasPlainClick = onClickWithoutAnchor != null,
            hasAnchoredClick = onClick != null
        )
    val resolvedAnchorOwnerIdentity = anchorOwnerIdentity ?: clickIdentity ?: "anchored_meta_chip:$label"
    val geometryAnchor =
        remember(resolvedAnchorOwnerIdentity) { RaftLiveRootAnchorRef(identity = resolvedAnchorOwnerIdentity) }
    // raft: chips are 6dp-radius tags (`rounded-chip`); a colour block keeps its ink edge.
    val chipShape = RoundedCornerShape(RaftAppRadius.Chip)
    val chipModifier =
        modifier
            .heightIn(min = minHeight)
            .border(borderWidth, RaftColor.BorderBlack, chipShape)
            .background(color, chipShape)
            .padding(start = startPadding, end = horizontalPadding, top = verticalPadding, bottom = verticalPadding)
            .let { base ->
                if (
                    clickDispatch != RaftMetaChipClickDispatch.Anchored ||
                    anchorPlacement == RaftAnchorPlacement.Tap
                ) {
                    base
                } else {
                    base.raftCaptureLiveRootAnchor(geometryAnchor)
                }
            }
    RaftAnchorButton(
        onClick = {
            if (clickDispatch == RaftMetaChipClickDispatch.Plain) {
                onClickWithoutAnchor?.invoke()
            }
        },
        onClick2 =
            if (clickDispatch == RaftMetaChipClickDispatch.Anchored) {
                { tapAnchor ->
                    val anchor =
                        when (anchorPlacement) {
                            RaftAnchorPlacement.Tap -> tapAnchor
                            RaftAnchorPlacement.BottomStart ->
                                geometryAnchor.resolve(RaftPopoverLocalPoint.BottomStart)
                            RaftAnchorPlacement.BottomEnd ->
                                geometryAnchor.resolve(RaftPopoverLocalPoint.BottomEnd)
                        }
                    anchor?.let { onClick?.invoke(it) }
                }
            } else {
                null
            },
        enabled = clickDispatch != RaftMetaChipClickDispatch.Disabled,
        pressFeedback = pressFeedback,
        clickIdentity = clickIdentity,
        anchorOwnerIdentity = resolvedAnchorOwnerIdentity,
        modifier = chipModifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(contentGap)
        ) {
            if (leadingContent != null) {
                leadingContent()
            } else {
                iconName?.let {
                    RaftLucideIcon(
                        it,
                        modifier = Modifier.size(iconSize),
                        color = iconColorArgb,
                        fill = iconFillArgb
                    )
                }
            }
            Text(
                text = label,
                style = RaftType.Caption.withRaftFontWeight(labelFontWeight).copy(fontSize = labelFontSize, lineHeight = labelLineHeight),
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            trailingIconName?.let {
                RaftLucideIcon(it, modifier = Modifier.size(trailingIconSize), color = RaftColor.TextMutedGrayArgb)
            }
        }
    }
}

@Composable
internal fun RaftBrutalSectionLabel(
    label: String,
    required: Boolean = false,
    optional: Boolean = false,
    size: RaftFormFieldSize = RaftFormFieldSize.Default,
    modifier: Modifier = Modifier
) {
    RaftLookFieldLabel(
        label = label,
        required = required,
        optional = optional,
        compact = size == RaftFormFieldSize.Compact,
        modifier = modifier
    )
}

@Composable
internal fun RaftBrutalModalHeader(
    model: RaftModalShellModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
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
                style = RaftType.PageTitle.copy(fontSize = 17.sp, lineHeight = 22.sp).withRaftFontWeight(FontWeight.Bold),
                color = RaftColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (model.subtitle.isNotBlank()) {
                Text(
                    text = model.subtitle,
                    style = RaftType.Body.copy(fontSize = 15.sp, lineHeight = 20.sp),
                    color = RaftColor.TextMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        RaftBrutalIconButton(
            iconName = model.dismissIcon,
            onClick = onDismiss,
            backgroundColor = Color.Transparent,
            size = 32.dp,
            iconSize = 18.dp
        )
    }
}

@Composable
internal fun RaftBrutalModalLayer(
    model: RaftModalShellModel,
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
        RaftModalDismissBarrier(
            overlayAlpha = model.overlayAlpha,
            onDismiss = onDismiss
        )
        Box(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            // raft Dialog content: dialog radius 16, popover layer, seam ring, float shadow.
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .widthIn(max = model.maxWidth)
                        .raftFloatingContentClickTarget()
                        .raftLookOverlaySurface(radius = RaftAppRadius.Dialog)
            ) {
                RaftBrutalModalHeader(model = model, onDismiss = onDismiss)
                content()
            }
        }
    }
}

@Composable
internal fun RaftBrutalActionSheetRow(
    model: RaftActionSheetRowModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    // raft: the row sits in a menu popup, so it takes the menu item's `px-2.5 py-1.5`.
    menuItemPadding: Boolean = false
) {
    RaftLookActionSheetRow(model = model, onClick = onClick, modifier = modifier, menuItemPadding = menuItemPadding)
}

/**
 * raft action-sheet row: a menu-item row (control radius, pressed wash, no ink box) with
 * a soft icon tile. Destructive rows switch to the danger soft tile and danger-strong ink.
 */
@Composable
private fun RaftLookActionSheetRow(
    model: RaftActionSheetRowModel,
    onClick: () -> Unit,
    modifier: Modifier,
    menuItemPadding: Boolean = false
) {
    var pressed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(RaftAppRadius.Control)
    val titleColor = if (model.destructive) RaftColor.DangerStrong else RaftColor.TextBlack
    RaftAnchorButton(
        onClick = onClick,
        pressOffset = 0.dp,
        onPressedChange = { pressed = it },
        modifier =
            modifier
                .fillMaxWidth()
                .background(if (pressed) RaftColor.FillPressed else Color.Transparent, shape)
                .padding(
                    horizontal = if (menuItemPadding) RaftMetrics.MenuItemPaddingHorizontal else 12.dp,
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
                        .background(if (model.destructive) RaftColor.DangerSoft else RaftColor.FillMuted, shape),
                contentAlignment = Alignment.Center
            ) {
                RaftLucideIcon(
                    model.icon,
                    modifier = Modifier.size(18.dp),
                    color = raftArgbHex(if (model.destructive) RaftColor.DangerStrong else RaftColor.TextMuted)
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = model.title,
                    style = RaftType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp).withRaftFontWeight(FontWeight.SemiBold),
                    color = titleColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (model.subtitle.isNotBlank()) {
                    Text(
                        text = model.subtitle,
                        style = RaftType.Caption.copy(fontSize = 13.sp, lineHeight = 18.sp),
                        color = RaftColor.TextMuted,
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
internal fun RaftBrutalTextField(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    required: Boolean = false,
    optional: Boolean = false,
    labelSize: RaftFormFieldSize = RaftFormFieldSize.Default,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    hint: String? = null,
    error: String? = null,
    autoFocus: Boolean = false,
    maxLines: Int = 1,
    textStyle: TextStyle = RaftType.Input.copy(fontSize = 14.sp, lineHeight = 18.sp, color = RaftColor.TextBlack),
    fieldMinHeight: Dp = 42.dp,
    containerTapRequestsFocus: Boolean = false,
    labelAccessory: (@Composable () -> Unit)? = null,
    onFocusChanged: (Boolean) -> Unit = {}
) {
    val hasError = !error.isNullOrBlank()
    RaftFormField(
        label = label,
        modifier = modifier,
        required = required,
        optional = optional,
        hint = hint,
        error = error,
        size = labelSize,
        labelAccessory = labelAccessory
    ) {
        RaftBrutalInputField(
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
            borderColor = if (hasError) RaftColor.AccentRed else RaftColor.BorderBlack,
            enabledBackgroundColor = if (hasError) RaftFieldErrorTint else RaftColor.SurfaceWhite,
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
internal fun RaftBrutalTextField(
    label: String,
    value: TextFieldValue,
    placeholder: String,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    required: Boolean = false,
    optional: Boolean = false,
    labelSize: RaftFormFieldSize = RaftFormFieldSize.Default,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    hint: String? = null,
    error: String? = null,
    autoFocus: Boolean = false,
    maxLines: Int = 1,
    textStyle: TextStyle = RaftType.Input.copy(fontSize = 14.sp, lineHeight = 18.sp, color = RaftColor.TextBlack),
    fieldMinHeight: Dp = 42.dp,
    containerTapRequestsFocus: Boolean = false,
    password: Boolean = false,
    labelAccessory: (@Composable () -> Unit)? = null,
    onFocusChanged: (Boolean) -> Unit = {}
) {
    val hasError = !error.isNullOrBlank()
    RaftFormField(
        label = label,
        modifier = modifier,
        required = required,
        optional = optional,
        hint = hint,
        error = error,
        size = labelSize,
        labelAccessory = labelAccessory
    ) {
        RaftBrutalInputField(
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
            borderColor = if (hasError) RaftColor.AccentRed else RaftColor.BorderBlack,
            enabledBackgroundColor = if (hasError) RaftFieldErrorTint else RaftColor.SurfaceWhite,
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
internal fun raftBrutalInputFieldFrameMinHeight(
    fieldMinHeight: Dp,
    maxLines: Int,
    lineHeight: Dp,
    verticalPadding: Dp,
    borderWidth: Dp = RaftAppStroke.Default
): Dp {
    if (maxLines <= 1) return fieldMinHeight
    val needed = lineHeight * maxLines + verticalPadding * 2 + borderWidth * 2
    return if (needed > fieldMinHeight) needed else fieldMinHeight
}

@Composable
private fun TextStyle.raftAppLineHeightDp(): Dp =
    with(LocalDensity.current) {
        val lh: TextUnit = lineHeight
        when {
            lh.isSp -> lh.toDp()
            fontSize.isSp -> fontSize.toDp() * 1.3f
            else -> 18.dp
        }
    }

/**
 * Shared frame for the two [RaftBrutalInputField] overloads (String + TextFieldValue), so both
 * overloads stay in lockstep.
 *
 * raft-ui 0.6.0 Input/Textarea: 8dp control radius + 1.5dp `--line-field` edge on the card
 * layer, no offset shadow and no reserved gutter (the borderRadius-prop path does not round at
 * runtime, so the rounded shape is applied straight to `background`/`border`). The legacy error
 * pair (AccentRed edge + pink wash) maps to the `--danger-strong` edge on the card fill; any
 * other explicit [borderColor] passes through.
 */
@Composable
private fun RaftBrutalInputFieldFrame(
    modifier: Modifier,
    enabled: Boolean,
    focusPolicy: RaftBrutalInputFocusPolicy,
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
    val tapModifier =
        if (focusPolicy.containerTapRequestsFocus) {
            Modifier.clickable { focusRequester.requestFocus() }
        } else {
            Modifier
        }
    // raft-ui Input / Textarea: control radius 8, 1.5dp `--line-strong` (`--line-field`)
    // edge on the card layer, no shadow. Invalid swaps the edge to `--danger-strong`
    // (the fill stays the card); disabled is the opaque `--fill-muted` / `--line-hairline` pair.
    // Read-only fields keep the resting look, like web `<Input readOnly>`.
    val shape = RoundedCornerShape(RaftAppRadius.Control)
    val raftBorderColor =
        when {
            !enabled -> RaftColor.BorderHairline
            borderColor == RaftColor.BorderBlack -> RaftColor.LineField
            borderColor == RaftColor.AccentRed -> RaftColor.DangerStrong
            else -> borderColor
        }
    val raftSurfaceColor =
        when {
            !enabled ->
                if (disabledBackgroundColor == RaftColor.SurfaceCream) RaftColor.FillMuted else disabledBackgroundColor
            enabledBackgroundColor == RaftColor.SurfaceWhite || enabledBackgroundColor == RaftFieldErrorTint -> RaftColor.Card
            else -> enabledBackgroundColor
        }
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(fieldMinHeight)
                .background(raftSurfaceColor, shape)
                .border(RaftAppStroke.Control, raftBorderColor, shape)
                .then(tapModifier)
                .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        contentAlignment = contentAlignment,
        content = { content() }
    )
}

/** [showShadow] and [forceFocused] are ignored: raft fields cast no shadow. */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun RaftBrutalInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    autoFocus: Boolean = false,
    maxLines: Int = 1,
    textStyle: TextStyle = RaftBrutalInputDefaultTextStyle,
    fieldMinHeight: Dp = 42.dp,
    containerTapRequestsFocus: Boolean = false,
    horizontalPadding: Dp = RaftBrutalInputLegacyHorizontalPadding,
    verticalPadding: Dp = 8.dp,
    showShadow: Boolean = true,
    forceFocused: Boolean = false,
    borderColor: Color = RaftColor.BorderBlack,
    enabledBackgroundColor: Color = RaftColor.SurfaceWhite,
    disabledBackgroundColor: Color = RaftColor.SurfaceCream,
    placeholderColor: Color = RaftColor.TextMutedGray,
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
    val focusPolicy = raftBrutalInputFocusPolicy(maxLines, enabled, readOnly, containerTapRequestsFocus)
    val isMultiline = focusPolicy.fillEditorHeight
    val fieldContentAlignment = if (isMultiline) Alignment.TopStart else Alignment.CenterStart
    val rowVerticalAlignment = if (isMultiline) Alignment.Top else Alignment.CenterVertically
    val theme = LocalRaftTheme.current
    // raft-ui fields: 44dp tall, 12dp side padding, `text-field` 16/24 type, placeholder
    // ink. Only the legacy brutal defaults are remapped; explicit caller metrics stay.
    val resolvedTextStyle =
        when {
            textStyle == RaftBrutalInputDefaultTextStyle ->
                textStyle.copy(
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    color = if (enabled) RaftColor.TextBlack else RaftColor.ForegroundDisabled
                )
            else -> textStyle.copy(color = if (enabled) theme.ink else RaftColor.ForegroundDisabled)
        }
    val resolvedHorizontalPadding =
        if (horizontalPadding == RaftBrutalInputLegacyHorizontalPadding) {
            RaftMetrics.FieldPaddingHorizontal
        } else {
            horizontalPadding
        }
    val resolvedPlaceholderColor =
        if (placeholderColor == RaftColor.TextMutedGray) RaftColor.ForegroundHint else placeholderColor
    RaftBrutalInputFieldFrame(
        modifier = modifier,
        enabled = enabled,
        focusPolicy = focusPolicy,
        fieldMinHeight =
            raftBrutalInputFieldFrameMinHeight(
                fieldMinHeight = raftFieldHeight(fieldMinHeight),
                maxLines = maxLines,
                lineHeight = resolvedTextStyle.raftAppLineHeightDp(),
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
                    raftBrutalInputForwardValueChange(
                        focusPolicy = focusPolicy,
                        nextValue = nextValue,
                        onValueChange = onValueChange
                    )
                },
                placeholder = placeholder,
                placeholderColor = resolvedPlaceholderColor,
                autoFocus = autoFocus && focusPolicy.acceptsFocus,
                onFocus = { onFocusChanged(true) },
                onBlur = { onFocusChanged(false) },
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
                RaftBrutalIconButton(
                    iconName = RaftLucideIconName.X,
                    onClick = { onClear?.invoke() ?: onValueChange("") },
                    backgroundColor = Color.Transparent,
                    size = 18.dp,
                    shadowOffset = 2.dp
                )
            }
            trailingContent?.invoke()
        }
    }
}

/** [showShadow] and [forceFocused] are ignored: raft fields cast no shadow. */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun RaftBrutalInputField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    autoFocus: Boolean = false,
    maxLines: Int = 1,
    textStyle: TextStyle = RaftBrutalInputDefaultTextStyle,
    fieldMinHeight: Dp = 42.dp,
    containerTapRequestsFocus: Boolean = false,
    horizontalPadding: Dp = RaftBrutalInputLegacyHorizontalPadding,
    verticalPadding: Dp = 8.dp,
    showShadow: Boolean = true,
    forceFocused: Boolean = false,
    borderColor: Color = RaftColor.BorderBlack,
    enabledBackgroundColor: Color = RaftColor.SurfaceWhite,
    disabledBackgroundColor: Color = RaftColor.SurfaceCream,
    placeholderColor: Color = RaftColor.TextMutedGray,
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
    val focusPolicy = raftBrutalInputFocusPolicy(maxLines, enabled, readOnly, containerTapRequestsFocus)
    val isMultiline = focusPolicy.fillEditorHeight
    val fieldContentAlignment = if (isMultiline) Alignment.TopStart else Alignment.CenterStart
    val rowVerticalAlignment = if (isMultiline) Alignment.Top else Alignment.CenterVertically
    val theme = LocalRaftTheme.current
    // raft-ui fields: 44dp tall, 12dp side padding, `text-field` 16/24 type, placeholder
    // ink. Only the legacy brutal defaults are remapped; explicit caller metrics stay.
    val resolvedTextStyle =
        when {
            textStyle == RaftBrutalInputDefaultTextStyle ->
                textStyle.copy(
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    color = if (enabled) RaftColor.TextBlack else RaftColor.ForegroundDisabled
                )
            else -> textStyle.copy(color = if (enabled) theme.ink else RaftColor.ForegroundDisabled)
        }
    val resolvedHorizontalPadding =
        if (horizontalPadding == RaftBrutalInputLegacyHorizontalPadding) {
            RaftMetrics.FieldPaddingHorizontal
        } else {
            horizontalPadding
        }
    val resolvedPlaceholderColor =
        if (placeholderColor == RaftColor.TextMutedGray) RaftColor.ForegroundHint else placeholderColor
    RaftBrutalInputFieldFrame(
        modifier = modifier,
        enabled = enabled,
        focusPolicy = focusPolicy,
        fieldMinHeight =
            raftBrutalInputFieldFrameMinHeight(
                fieldMinHeight = raftFieldHeight(fieldMinHeight),
                maxLines = maxLines,
                lineHeight = resolvedTextStyle.raftAppLineHeightDp(),
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
                    raftBrutalInputForwardValueChange(
                        focusPolicy = focusPolicy,
                        nextValue = nextValue,
                        onValueChange = onValueChange
                    )
                },
                placeholder = placeholder,
                placeholderColor = resolvedPlaceholderColor,
                autoFocus = autoFocus && focusPolicy.acceptsFocus,
                onFocus = { onFocusChanged(true) },
                onBlur = { onFocusChanged(false) },
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
                RaftBrutalIconButton(
                    iconName = RaftLucideIconName.X,
                    onClick = { onClear?.invoke() ?: onValueChange(TextFieldValue("")) },
                    backgroundColor = Color.Transparent,
                    size = 18.dp,
                    shadowOffset = 2.dp
                )
            }
            trailingContent?.invoke()
        }
    }
}

/** The brutal field type (14/18); raft swaps a field still on it for `text-field` 16/24. */
internal val RaftBrutalInputDefaultTextStyle: TextStyle
    get() = RaftType.Input.copy(fontSize = 14.sp, lineHeight = 18.sp, color = RaftColor.TextBlack)

internal val RaftBrutalInputLegacyHorizontalPadding = 10.dp

internal data class RaftBrutalInputFocusPolicy(
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
internal fun <T> raftBrutalInputForwardValueChange(
    focusPolicy: RaftBrutalInputFocusPolicy,
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
internal fun raftBrutalInputFocusPolicy(
    maxLines: Int,
    enabled: Boolean,
    readOnly: Boolean,
    explicitContainerTapRequestsFocus: Boolean = false
): RaftBrutalInputFocusPolicy {
    val acceptsFocus = enabled && !readOnly
    val multiline = maxLines > 1
    return RaftBrutalInputFocusPolicy(
        acceptsFocus = acceptsFocus,
        fillEditorHeight = multiline,
        containerTapRequestsFocus = acceptsFocus && (multiline || explicitContainerTapRequestsFocus)
    )
}

@Composable
internal fun RaftBrutalReadOnlyField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    fieldMinHeight: Dp = 42.dp,
    horizontalPadding: Dp = 12.dp,
    verticalPadding: Dp = 8.dp,
    textStyle: TextStyle = RaftType.Input.copy(fontSize = 14.sp, lineHeight = 18.sp, color = RaftColor.TextMutedGray),
    textColor: Color = RaftColor.TextMutedGray
) {
    RaftFormField(label = label, modifier = modifier, size = RaftFormFieldSize.Compact) {
        RaftBrutalReadOnlyValueField(
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
internal val RaftReadOnlyFieldLegacyBackground = Color(0xFFF9FAFB)

@Composable
internal fun RaftBrutalReadOnlyValueField(
    value: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    fieldMinHeight: Dp = 42.dp,
    horizontalPadding: Dp = 12.dp,
    verticalPadding: Dp = 8.dp,
    backgroundColor: Color = RaftReadOnlyFieldLegacyBackground,
    borderColor: Color = RaftColor.BorderBlack.copy(alpha = 0.30f),
    textStyle: TextStyle = RaftType.Input.copy(fontSize = 14.sp, lineHeight = 18.sp, color = RaftColor.TextMutedGray),
    textColor: Color = RaftColor.TextMutedGray
) {
    val shape = RoundedCornerShape(RaftAppRadius.Control)
    // raft: a non-editable value is the disabled field pair (`--fill-muted` fill,
    // 1.5dp `--line-hairline` edge, muted ink) at the 44dp field height.
    val resolvedBackground = if (backgroundColor == RaftReadOnlyFieldLegacyBackground) RaftColor.FillMuted else backgroundColor
    val resolvedBorder = if (borderColor == RaftColor.BorderBlack.copy(alpha = 0.30f)) RaftColor.BorderHairline else borderColor
    val resolvedTextColor = if (textColor == RaftColor.TextMutedGray) RaftColor.TextMuted else textColor
    Box(
        modifier =
            modifier
                .heightIn(min = raftFieldHeight(fieldMinHeight))
                .background(resolvedBackground, shape)
                .border(RaftAppStroke.Default, resolvedBorder, shape)
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
internal fun RaftBrutalSearchField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: RaftLucideIconName = RaftLucideIconName.Search,
    showClearWhenNotEmpty: Boolean = false,
    onClear: () -> Unit = { onValueChange("") },
    textStyle: TextStyle = RaftBrutalSearchDefaultTextStyle,
    onFocus: () -> Unit = {},
    onBlur: () -> Unit = {}
) {
    RaftSearchFieldFrame(
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
            placeholderColor = RaftColor.ForegroundHint,
            autoFocus = false,
            onFocus = { onFocus() },
            onBlur = { onBlur() },
            maxLines = 1,
            textStyle = raftSearchTextStyle(textStyle, enabled),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
internal fun RaftBrutalSearchField(
    value: TextFieldValue,
    placeholder: String,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: RaftLucideIconName = RaftLucideIconName.Search,
    showClearWhenNotEmpty: Boolean = false,
    onClear: () -> Unit = { onValueChange(TextFieldValue("")) },
    textStyle: TextStyle = RaftBrutalSearchDefaultTextStyle,
    onFocus: () -> Unit = {},
    onBlur: () -> Unit = {}
) {
    RaftSearchFieldFrame(
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
            placeholderColor = RaftColor.ForegroundHint,
            autoFocus = false,
            onFocus = { onFocus() },
            onBlur = { onBlur() },
            maxLines = 1,
            textStyle = raftSearchTextStyle(textStyle, enabled),
            modifier = Modifier.weight(1f)
        )
    }
}

/** The brutal search type (13/16); raft swaps a field still on it for `text-field` 16/24. */
internal val RaftBrutalSearchDefaultTextStyle: TextStyle
    get() = RaftType.Caption.copy(fontSize = 13.sp, lineHeight = 16.sp, color = RaftColor.TextBlack)

private fun raftSearchTextStyle(textStyle: TextStyle, enabled: Boolean): TextStyle {
    val ink = if (enabled) RaftColor.TextBlack else RaftColor.ForegroundDisabled
    return if (textStyle == RaftBrutalSearchDefaultTextStyle) {
        RaftType.Input.copy(fontSize = 16.sp, lineHeight = 24.sp, color = ink)
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
private fun RaftSearchFieldFrame(
    modifier: Modifier,
    enabled: Boolean,
    leadingIcon: RaftLucideIconName,
    showClear: Boolean,
    onClear: () -> Unit,
    field: @Composable RowScope.() -> Unit
) {
    val shape = RoundedCornerShape(RaftAppRadius.Control)
    val hintArgb = raftArgbHex(if (enabled) RaftColor.ForegroundHint else RaftColor.ForegroundDisabled)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(RaftMetrics.FieldHeight)
                .background(if (enabled) RaftColor.Card else RaftColor.FillMuted, shape)
                .border(RaftAppStroke.Control, if (enabled) RaftColor.LineField else RaftColor.BorderHairline, shape)
                .padding(horizontal = RaftMetrics.FieldPaddingHorizontal),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RaftLucideIcon(leadingIcon, color = hintArgb, modifier = Modifier.size(18.dp))
        field()
        if (showClear) {
            RaftLucideIcon(
                RaftLucideIconName.X,
                color = hintArgb,
                modifier =
                    Modifier
                        .size(16.dp)
                        .clickable(enabled = enabled, onClick = onClear)
            )
        }
    }
}

/**
 * The disabled* colours, [leadingIconSize], [contentSpacing] and [shadowOffset] are ignored:
 * they only shaped the retired brutal register.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun RaftBrutalActionButton(
    label: String,
    onClick: () -> Unit,
    onClick2: ((RaftPopoverAnchor) -> Unit)? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backgroundColor: Color = RaftColor.SurfaceWhite,
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
    leadingIcon: RaftLucideIconName? = null,
    leadingIconSpinning: Boolean = leadingIcon == RaftLucideIconName.Loader || leadingIcon == RaftLucideIconName.LoaderCircle,
    leadingIconSize: Dp = 12.dp,
    contentSpacing: Dp = 6.dp,
    shadowOffset: Dp? = null,
    textStyle: TextStyle = RaftBrutalActionButtonDefaultTextStyle,
    trailingContent: (@Composable () -> Unit)? = null
) {
    // Renders raft-ui 0.6.0's Button ([RaftActionButton]: control radius, 1.5dp edge,
    // sticker/outline/ghost variants from [backgroundColor], press overlay, no shadow).
    RaftActionButton(
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
}

internal val RaftBrutalActionButtonDefaultTextStyle: TextStyle
    // Getter: the UI-text locale and rendering defaults can change at runtime.
    get() =
        RaftType.ButtonBold.copy(fontSize = 13.sp, lineHeight = 16.sp)

// The brutal button's default min width; raft buttons have none (they hug the label).
private val RaftBrutalActionButtonLegacyMinWidth = 82.dp

/**
 * raft-ui 0.6.0 `Button` (dist/components button recipe):
 *  - sizes: `md` h-8 / px-3 / 13/18 bold / 16dp icons (raft renders `xs`/`sm` as `md`),
 *    `lg` h-11 / px-4 / 15/20, `xl` h-13 / px-5 / 16/24; control radius 8dp;
 *  - variants from the caller's fill ([raftButtonChrome]): pink accent / danger /
 *    warning stickers with a 1.5dp sticker-ink edge and ink type, or the paper outline
 *    button with a 1.5dp `--line-strong` edge, or ghost;
 *  - press lays `--sticker-pressed` / `--fill-pressed` over the fill (no offset, no
 *    shadow: controls never cast one);
 *  - disabled is opaque: `--fill-muted` fill, `--line-hairline` edge, `--foreground-disabled` ink.
 * A [minHeight] below 38dp picks `md`, up to 52dp `lg`, beyond that `xl`.
 */
@Composable
private fun RaftActionButton(
    label: String,
    onClick: () -> Unit,
    onClick2: ((RaftPopoverAnchor) -> Unit)?,
    modifier: Modifier,
    enabled: Boolean,
    backgroundColor: Color,
    minWidth: Dp,
    minHeight: Dp,
    leadingIcon: RaftLucideIconName?,
    leadingIconSpinning: Boolean,
    textStyle: TextStyle,
    trailingContent: (@Composable () -> Unit)?
) {
    // raft-ui dialog footers pair the primary sticker with a ghost Cancel; a
    // paper (white) button inside RaftDialogFooter is that ghost secondary.
    val chrome =
        raftButtonChrome(
            if (LocalRaftDialogFooter.current && backgroundColor == RaftColor.SurfaceWhite) {
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
        minHeight >= RaftMetrics.ControlHeightXl -> {
            height = RaftMetrics.ControlHeightXl
            horizontalPadding = 20.dp
            fontSize = 16.sp
            lineHeight = 24.sp
            iconSize = 18.dp
        }
        minHeight >= 38.dp -> {
            height = RaftMetrics.ControlHeightLg
            horizontalPadding = 16.dp
            fontSize = 15.sp
            lineHeight = 20.sp
            iconSize = 18.dp
        }
        else -> {
            height = RaftMetrics.ControlHeightMd
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
            !enabled -> RaftColor.FillMuted
            pressed -> raftOverlay(chrome.pressedOverlay, chrome.fill)
            else -> chrome.fill
        }
    val border = if (enabled) chrome.border else RaftColor.BorderHairline
    val content = if (enabled) chrome.content else RaftColor.ForegroundDisabled
    val shape = RoundedCornerShape(RaftAppRadius.Control)
    RaftAnchorButton(
        onClick = onClick,
        onClick2 = onClick2,
        enabled = enabled,
        pressFeedback = enabled,
        pressOffset = 0.dp,
        onPressedChange = { pressed = it },
        modifier =
            modifier
                .widthIn(min = if (minWidth == RaftBrutalActionButtonLegacyMinWidth) 0.dp else minWidth)
                .heightIn(min = height)
                .background(fill, shape)
                .border(RaftAppStroke.Control, border, shape)
                .padding(start = startPadding, end = horizontalPadding)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leadingIcon != null) {
                RaftLucideIcon(
                    leadingIcon,
                    modifier = Modifier.size(iconSize),
                    color = raftArgbHex(content),
                    spinning = leadingIconSpinning
                )
            }
            Text(
                text = label,
                style = textStyle.copy(fontSize = fontSize, lineHeight = lineHeight).withRaftFontWeight(FontWeight.Bold),
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
    // raft-ui SegmentedControl: a `--fill-muted` tray (radius 11, 3dp inset, 2dp gap);
    // the checked item is the yellow sticker with an ink edge.
    Row(
        modifier =
            modifier
                .background(RaftColor.FillMuted, RoundedCornerShape(11.dp))
                .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEach { option ->
            RaftSegmentedItem(
                label = option.label,
                iconName = option.iconName,
                count = option.count,
                selected = option.value == selectedValue,
                enabled = enabled,
                onClick = { onValueSelected(option.value) }
            )
        }
    }
}

/**
 * One raft-ui segmented / tab item (`segmented-control` item, `tabs` default tab): 32dp,
 * control radius, 1.5dp edge that is transparent at rest and sticker ink when checked,
 * checked fill = the yellow `--brand` sticker with bold ink type, pressed = fill wash.
 */
@Composable
internal fun RaftSegmentedItem(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    iconName: RaftLucideIconName? = null,
    count: Int? = null
) {
    var pressed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(RaftAppRadius.Control)
    val fill =
        when {
            selected -> if (pressed) raftOverlay(RaftColor.StickerPressed, RaftColor.Brand) else RaftColor.Brand
            pressed && enabled -> RaftColor.FillPressed
            else -> Color.Transparent
        }
    val ink =
        when {
            !enabled -> RaftColor.ForegroundDisabled
            selected -> RaftColor.StickerInk
            else -> RaftColor.TextMuted
        }
    RaftAnchorButton(
        onClick = onClick,
        enabled = enabled,
        pressFeedback = enabled,
        pressOffset = 0.dp,
        onPressedChange = { pressed = it },
        modifier =
            Modifier
                .height(RaftMetrics.ControlHeightMd)
                .background(fill, shape)
                .border(RaftAppStroke.Control, if (selected) RaftColor.StickerInk else Color.Transparent, shape)
                .padding(horizontal = 12.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            iconName?.let {
                RaftLucideIcon(name = it, modifier = Modifier.size(16.dp), color = raftArgbHex(ink))
            }
            Text(
                text = label,
                style =
                    RaftType.Body
                        .copy(fontSize = 14.sp, lineHeight = 20.sp)
                        .withRaftFontWeight(if (selected) FontWeight.Bold else FontWeight.SemiBold),
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            count?.takeIf { it > 0 }?.let {
                Text(
                    text = it.toString(),
                    style = RaftType.Caption.copy(fontFamily = RaftMonoFamily, fontSize = 12.sp, lineHeight = 16.sp),
                    color = ink,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * [uppercase], [inactiveBackgroundColor], [borderColor], [disabledBorderColor] and the
 * activeShadow* parameters are ignored: they only shaped the retired brutal register.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun RaftToolbarChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconName: RaftLucideIconName? = null,
    trailingIconName: RaftLucideIconName? = null,
    active: Boolean = false,
    enabled: Boolean = true,
    uppercase: Boolean = true,
    count: Int? = null,
    activeBackgroundColor: Color = RaftColor.AccentCyan,
    inactiveBackgroundColor: Color = RaftColor.SurfaceWhite,
    borderColor: Color = RaftColor.EmptyStateGray,
    disabledBorderColor: Color = RaftColor.EmptyStateGray,
    activeShadowOffset: Dp = 0.dp,
    reserveActiveShadowSpace: Boolean = false,
    activeShadowColor: Color = RaftColor.ShadowBlack
) {
    RaftPickerChip(
        label = label,
        onClick = onClick,
        modifier = modifier,
        iconName = iconName,
        trailingIconName = trailingIconName,
        active = active,
        enabled = enabled,
        count = count,
        activeBackgroundColor =
            if (activeBackgroundColor == RaftColor.AccentCyan) RaftColor.InfoSoft else activeBackgroundColor
    )
}

/**
 * raft-ui `PickerTriggerButton` (filter / toolbar chip): 32dp, control radius, 1dp
 * `--line-seam` ring at rest, 13/18 semibold muted type in sentence case; a chip with a
 * value is `--info-soft` with ink type and no ring; pressed lays the fill wash; disabled
 * is `--fill-muted` with disabled ink. Counts are mono meta text, not a boxed badge.
 */
@Composable
private fun RaftPickerChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
    iconName: RaftLucideIconName?,
    trailingIconName: RaftLucideIconName?,
    active: Boolean,
    enabled: Boolean,
    count: Int?,
    activeBackgroundColor: Color
) {
    var pressed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(RaftAppRadius.Control)
    val restingFill = if (active) activeBackgroundColor else Color.Transparent
    val fill =
        when {
            !enabled -> RaftColor.FillMuted
            pressed -> raftOverlay(RaftColor.FillPressed, restingFill)
            else -> restingFill
        }
    val ink =
        when {
            !enabled -> RaftColor.ForegroundDisabled
            active -> RaftColor.TextBlack
            else -> RaftColor.TextMuted
        }
    val ring = if (!enabled || active) Color.Transparent else RaftColor.BorderSeam
    RaftAnchorButton(
        onClick = onClick,
        enabled = enabled,
        pressFeedback = enabled,
        pressOffset = 0.dp,
        onPressedChange = { pressed = it },
        modifier =
            modifier
                .height(RaftMetrics.ControlHeightMd)
                .background(fill, shape)
                .border(RaftAppStroke.Thin, ring, shape)
                .padding(horizontal = 10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            iconName?.let {
                RaftLucideIcon(it, modifier = Modifier.size(14.dp), color = raftArgbHex(ink), strokeWidth = 2f)
            }
            Text(
                text = label,
                style = RaftType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp).withRaftFontWeight(FontWeight.SemiBold),
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            count?.let {
                Text(
                    text = it.toString(),
                    style = RaftType.Caption.copy(fontFamily = RaftMonoFamily, fontSize = 12.sp, lineHeight = 16.sp),
                    color = if (enabled) RaftColor.ForegroundHint else RaftColor.ForegroundDisabled,
                    maxLines = 1
                )
            }
            trailingIconName?.let {
                RaftLucideIcon(
                    it,
                    modifier = Modifier.size(14.dp),
                    color = raftArgbHex(if (enabled) RaftColor.ForegroundHint else RaftColor.ForegroundDisabled),
                    strokeWidth = 2f
                )
            }
        }
    }
}

@Composable
internal fun RaftAnchoredToolbarChip(
    label: String,
    onClick: (RaftPopoverAnchor) -> Unit,
    anchorIdentity: Any,
    modifier: Modifier = Modifier,
    iconName: RaftLucideIconName? = null,
    trailingIconName: RaftLucideIconName? = RaftLucideIconName.ChevronDown,
    active: Boolean = false,
    enabled: Boolean = true,
    uppercase: Boolean = true,
    count: Int? = null,
    activeBackgroundColor: Color = RaftColor.AccentCyan,
    inactiveBackgroundColor: Color = RaftColor.SurfaceWhite,
    borderColor: Color = RaftColor.EmptyStateGray,
    disabledBorderColor: Color = RaftColor.EmptyStateGray,
    activeShadowOffset: Dp = 0.dp,
    reserveActiveShadowSpace: Boolean = false,
    activeShadowColor: Color = RaftColor.ShadowBlack,
    onAnchorChanged: ((RaftPopoverAnchorResolver) -> Unit)? = null
) {
    val currentOnAnchorChanged by rememberUpdatedState(onAnchorChanged)
    val anchor = remember(anchorIdentity) { RaftLiveRootAnchorRef(identity = anchorIdentity) }
    val anchorResolver = remember(anchor) { anchor.resolver(RaftPopoverLocalPoint.BottomStart) }
    var resolverNotified by remember(anchorResolver) { mutableStateOf(false) }
    Box(
        modifier =
            modifier.raftCaptureLiveRootAnchor(anchor) {
                if (!resolverNotified) {
                    resolverNotified = true
                    currentOnAnchorChanged?.invoke(anchorResolver)
                }
            }
    ) {
        RaftToolbarChip(
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
                anchor.resolve(RaftPopoverLocalPoint.BottomStart)?.let(onClick)
            }
        )
    }
}

/**
 * Legacy-named icon button; renders the raft-ui icon button ([RaftLookIconButton]). The
 * border / shadow / disabled-colour parameters of the retired brutal look are accepted
 * and ignored so call sites keep compiling; prefer
 * `build.raft.designsystem.components.RaftIconButton` in new code.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun RaftBrutalIconButton(
    iconName: RaftLucideIconName,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    clickIdentity: Any? = null,
    enabled: Boolean = true,
    backgroundColor: Color = RaftColor.SurfaceWhite,
    disabledBackgroundColor: Color = RaftColor.SurfaceCream,
    disabledAlpha: Float = 1f,
    borderColor: Color = RaftColor.BorderBlack,
    disabledBorderColor: Color = RaftColor.BorderBlack,
    shadowColor: Color = RaftColor.ShadowBlack,
    disabledShadowColor: Color = RaftColor.ShadowBlack,
    iconColor: String = "#FF000000",
    disabledIconColor: String = "#FF000000",
    size: Dp = 32.dp,
    shadowOffset: Dp = RaftShadow.BrutalSmall,
    iconSize: Dp = (size.value * 0.5f).dp,
    iconSpinning: Boolean = iconName == RaftLucideIconName.Loader || iconName == RaftLucideIconName.LoaderCircle
) {
    RaftLookIconButton(
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
}

/**
 * raft-ui icon button (`size="icon-md"` 32dp / `icon-lg` 44dp) on a square control-radius
 * box, no shadow and no reserved shadow gutter, press = overlay wash. A paper fill (the
 * old brutal default) is the raft-ui ghost icon button: no frame, only the pressed wash,
 * as on web headers and toolbars. A sticker fill (yellow / pink / red / orange) keeps the
 * sticker chrome. The default black icon ink follows the variant's ink so it reads in
 * dark mode; an explicit [iconColor] is kept.
 */
@Composable
private fun RaftLookIconButton(
    iconName: RaftLucideIconName,
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
    val chrome = raftButtonChrome(backgroundColor).let { base ->
        if (base.kind == RaftButtonKind.Outline) raftButtonChrome(Color.Transparent) else base
    }
    var pressed by remember { mutableStateOf(false) }
    val fill =
        when {
            !enabled -> if (chrome.kind == RaftButtonKind.Ghost) Color.Transparent else RaftColor.FillMuted
            pressed -> raftOverlay(chrome.pressedOverlay, chrome.fill)
            else -> chrome.fill
        }
    val border =
        when {
            chrome.kind == RaftButtonKind.Ghost -> Color.Transparent
            enabled -> chrome.border
            else -> RaftColor.BorderHairline
        }
    val resolvedIconColor =
        when {
            !enabled -> raftArgbHex(RaftColor.ForegroundDisabled)
            iconColor == RaftLegacyIconInkArgb -> raftArgbHex(chrome.content)
            else -> iconColor
        }
    val shape = RoundedCornerShape(RaftAppRadius.Control)
    RaftAnchorButton(
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
                .border(RaftAppStroke.Control, border, shape)
    ) {
        RaftLucideIcon(iconName, color = resolvedIconColor, modifier = Modifier.size(iconSize), spinning = iconSpinning)
    }
}

// The brutal primitives' hard-coded black icon ink; raft swaps it for the themed ink.
internal const val RaftLegacyIconInkArgb = "#FF000000"

/** [shadowOffset] is ignored: the raft card casts no shadow. */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun RaftBrutalPanelShell(
    modifier: Modifier = Modifier,
    shadowOffset: Dp = RaftShadow.Brutal,
    content: @Composable BoxScope.() -> Unit
) {
    // raft Card: container radius, card layer, hairline edge, no shadow.
    Box(
        modifier = modifier.fillMaxWidth().raftCardSurface(),
        content = content
    )
}

/** [shadowOffset] is ignored: the raft card casts no shadow. */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun RaftBrutalListShell(
    height: Dp,
    modifier: Modifier = Modifier,
    shadowOffset: Dp = RaftShadow.Brutal,
    listModifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit
) {
    // raft Card surface around the list: container radius, hairline, no shadow.
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height)
                .raftCardSurface()
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
}

@Composable
internal fun RaftBrutalListSectionHeader(
    label: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(Color.Transparent)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // raft section title (`rf-section`): 13/18 bold hint ink, sentence case.
        Text(
            text = label,
            style = RaftType.SectionLabel.copy(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp)
                .withRaftFontWeight(FontWeight.Bold),
            color = RaftColor.ForegroundHint
        )
    }
}
