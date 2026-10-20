package build.raft.app.components

import build.raft.app.Button as RaftAnchorButton
import build.raft.app.RaftColor
import build.raft.app.RaftAppThemeState
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.compat.ui.KeyboardParams
import build.raft.app.raftAppEditorBehavior
import build.raft.app.raftResolvedKeyboardOptions
import build.raft.app.resources.MR
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDialogHeader
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftFieldLabel
import build.raft.designsystem.components.RaftFieldLabelSize
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftReadOnlyField
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.components.RaftTextFieldClearAction
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import com.tencent.tmm.kmmresource.compose.stringResource
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay

/*
 * Legacy `RaftBrutal*` API -> compose/designsystem (raft-ui) map. Call counts are call
 * sites in compose/shared at the start of the migration (origin/main 91cadf12e).
 *
 * | Legacy API (calls)                          | Design-system component                                   |
 * | ------------------------------------------- | --------------------------------------------------------- |
 * | RaftBrutalActionButton (143)                 | RaftButton (variant from the fill, size from minHeight)    |
 * | RaftBrutalIconButton (22)                    | RaftIconButton (Md / Lg)                                   |
 * | RaftBrutalTextField (45)                     | RaftField + RaftTextField                                  |
 * | RaftBrutalInputField (20)                    | RaftTextField (single line, default chrome)                |
 * | RaftBrutalSearchField (5)                    | RaftTextField with a leading search addon                  |
 * | RaftBrutalSelectField (16)                   | RaftSelectTrigger + RaftDropdownMenu / RaftMenuItem        |
 * | RaftBrutalSectionLabel (21)                  | removed: RaftFieldLabel at the call sites                  |
 * | RaftBrutalReadOnlyField (5) / ...ValueField (2) | RaftField + RaftReadOnlyField (multi-line); ValueField removed |
 * | RaftBrutalCard (12)                          | RaftCard                                                   |
 * | RaftBrutalListRow (2)                        | RaftListRowGroup + RaftListRow                             |
 * | RaftBrutalBadge (3)                          | RaftBadge (soft); RaftBrutalBadgeButton (0) removed        |
 * | RaftBrutalDropdownMenuSurface (3) / Row (2)  | RaftMenuSurface + RaftMenuItem                             |
 * | RaftBrutalDropdownMenu (2)                   | app popover positioning around RaftMenuSurface             |
 * | RaftBrutalModalLayer (2) / Header (2)        | RaftDialogHeader; Header removed                           |
 * | RaftBrutalTextarea (2)                       | RaftTextarea (no TextFieldValue / focus-hook overload yet) |
 * | RaftBrutalPanel (1)                          | removed: RaftPopoverSurface at the call site               |
 * | RaftBrutalPanelShell (1) / ListShell (1)     | removed: RaftListRowGroup at the call sites                |
 * | RaftBrutalListSectionHeader (1)              | removed: RaftSectionEyebrow at the call site               |
 * | RaftBrutalActionSheetRow (1)                 | RaftListRow + RaftIconTile (no danger tone yet)            |
 * | RaftShadow.Brutal* (48 refs, all 0dp)        | removed: raft-ui controls cast no shadow                   |
 */

internal data class RaftModalShellModel(
    val title: String,
    val subtitle: String = "",
    val overlayAlpha: Float = 0.60f,
    val maxWidth: Dp = 384.dp
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
// Dark takes the raft roles (`danger-soft`; disabled `fill-muted` / `line-hairline` /
// `foreground-disabled`); light keeps the legacy values.
internal val RaftFieldErrorTint: Color
    get() = if (RaftAppThemeState.isDark) RaftColor.DangerSoft else Color(0xFFFFF4F4) // raft-color: light legacy error wash

internal val RaftDisabledActionBackground: Color
    get() = if (RaftAppThemeState.isDark) RaftColor.FillMuted else Color(0xFFFFD8E5) // raft-color: light legacy disabled fill
internal val RaftDisabledDestructiveActionBackground: Color
    get() = if (RaftAppThemeState.isDark) RaftColor.FillMuted else Color(0xFFFFE5E5) // raft-color: light legacy disabled fill
internal val RaftDisabledActionBorder: Color
    get() = if (RaftAppThemeState.isDark) RaftColor.BorderHairline else Color(0xFFB3B3B3) // raft-color: light legacy disabled edge
internal val RaftDisabledActionShadow: Color
    get() = if (RaftAppThemeState.isDark) RaftColor.BorderHairline else Color(0xFFB8B8B8) // raft-color: light legacy disabled shadow
internal val RaftDisabledActionContent: Color
    get() = if (RaftAppThemeState.isDark) RaftColor.ForegroundDisabled else RaftColor.SurfaceWhite
internal val RaftDisabledActionContentArgb: String
    get() = raftArgbHex(RaftDisabledActionContent)

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
    labelFontWeight: FontWeight = FontWeight.Bold,
    trailingIconColorArgb: String = RaftColor.TextMutedGrayArgb,
    leadingContent: (@Composable () -> Unit)? = null,
    contentSpacing: Dp = RaftAppSpacing.Xs
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
            // A zero width means no edge (a soft chip); Compose would draw 0.dp as a hairline.
            .then(if (borderWidth > 0.dp) Modifier.border(borderWidth, raftInkOnFill(color, RaftColor.BorderBlack), chipShape) else Modifier)
            .background(color, chipShape)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding)
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
            horizontalArrangement = Arrangement.spacedBy(contentSpacing)
        ) {
            leadingContent?.invoke()
            iconName?.let {
                RaftLucideIcon(
                    it,
                    modifier = Modifier.size(iconSize),
                    color =
                        if (iconColorArgb == RaftColor.TextBlackArgb) {
                            raftArgbHex(raftInkOnFill(color, RaftColor.TextBlack))
                        } else {
                            iconColorArgb
                        },
                    fill = iconFillArgb
                )
            }
            Text(
                text = label,
                style = RaftType.Caption.withRaftFontWeight(labelFontWeight).copy(fontSize = labelFontSize, lineHeight = labelLineHeight),
                color = if (contentColor == RaftColor.TextBlack) raftInkOnFill(color, contentColor) else contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            trailingIconName?.let {
                RaftLucideIcon(
                    it,
                    modifier = Modifier.size(trailingIconSize),
                    color =
                        if (trailingIconColorArgb == RaftColor.TextMutedGrayArgb) {
                            raftArgbHex(raftInkOnFill(color, RaftColor.TextMutedGray))
                        } else {
                            trailingIconColorArgb
                        }
                )
            }
        }
    }
}

internal val RaftFormFieldSize.raftFieldLabelSize: RaftFieldLabelSize
    get() = if (this == RaftFormFieldSize.Compact) RaftFieldLabelSize.Sm else RaftFieldLabelSize.Md

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
                // raft-ui DialogHeader: the body stays the caller's (lazy lists, own padding).
                RaftDialogHeader(
                    title = model.title,
                    description = model.subtitle.takeIf { it.isNotBlank() },
                    onClose = onDismiss
                )
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
 * a soft icon tile. A destructive row is drawn like any other (raft-ui: no red menu or list
 * rows; the confirm dialog carries the deletion).
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
    val titleColor = RaftColor.TextBlack
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
                        .background(RaftColor.FillMuted, shape),
                contentAlignment = Alignment.Center
            ) {
                RaftLucideIcon(
                    model.icon,
                    modifier = Modifier.size(18.dp),
                    color = raftArgbHex(RaftColor.TextMuted)
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
    RaftBrutalFieldFrame(
        label = label,
        modifier = modifier,
        required = required,
        optional = optional,
        hint = hint,
        error = error,
        labelSize = labelSize,
        enabled = enabled,
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
    RaftBrutalFieldFrame(
        label = label,
        modifier = modifier,
        required = required,
        optional = optional,
        hint = hint,
        error = error,
        labelSize = labelSize,
        enabled = enabled,
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
 * The label / control / help column of a legacy form field: the design-system [RaftField]
 * (label with its required mark or localised optional marker, the [labelAccessory] at the end
 * of the label row, help or error line, field state for the control).
 */
@Composable
internal fun RaftBrutalFieldFrame(
    label: String,
    modifier: Modifier,
    required: Boolean,
    optional: Boolean,
    hint: String?,
    error: String?,
    labelSize: RaftFormFieldSize,
    enabled: Boolean = true,
    labelAccessory: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    RaftField(
        label = label.takeIf { it.isNotBlank() },
        modifier = modifier.fillMaxWidth(),
        description = hint?.takeIf { it.isNotBlank() },
        error = error?.takeIf { it.isNotBlank() },
        enabled = enabled,
        optional = optional && !required,
        required = required,
        labelSize = labelSize.raftFieldLabelSize,
        labelTrailing = labelAccessory,
        content = content
    )
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
            // No indication: a container tap only forwards focus; the default overlay would
            // wash the whole field gray instead of the raft focus edge.
            Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { focusRequester.requestFocus() }
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
    if (
        trailingContent == null &&
        raftBrutalInputUsesDesignSystem(
            maxLines, fieldMinHeight, horizontalPadding, verticalPadding,
            borderColor, enabledBackgroundColor, disabledBackgroundColor
        )
    ) {
        val editor =
            rememberRaftDesignSystemEditor(
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                keyboardHeightChange = keyboardHeightChange,
                focusRequester = focusRequester,
                autoFocus = autoFocus && focusPolicy.acceptsFocus,
                onFocus = { onFocusChanged(true) },
                onBlur = { onFocusChanged(false) }
            )
        RaftTextField(
            value = value,
            onValueChange = { nextValue ->
                raftBrutalInputForwardValueChange(focusPolicy = focusPolicy, nextValue = nextValue, onValueChange = onValueChange)
            },
            modifier = modifier.fillMaxWidth().then(editor.modifier),
            placeholder = placeholder.ifEmpty { null },
            enabled = enabled,
            readOnly = readOnly,
            invalid = borderColor == RaftColor.AccentRed,
            trailingAction =
                if (showClearWhenNotEmpty && value.isNotEmpty() && focusPolicy.acceptsFocus) {
                    { RaftTextFieldClearAction(onClear = { onClear?.invoke() ?: onValueChange("") }) }
                } else {
                    null
                },
            keyboardOptions = editor.keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation
        )
        return
    }
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
                    size = 18.dp
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
    if (
        trailingContent == null &&
        raftBrutalInputUsesDesignSystem(
            maxLines, fieldMinHeight, horizontalPadding, verticalPadding,
            borderColor, enabledBackgroundColor, disabledBackgroundColor
        )
    ) {
        val editor =
            rememberRaftDesignSystemEditor(
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                keyboardHeightChange = keyboardHeightChange,
                focusRequester = focusRequester,
                autoFocus = autoFocus && focusPolicy.acceptsFocus,
                onFocus = { onFocusChanged(true) },
                onBlur = { onFocusChanged(false) }
            )
        RaftTextField(
            value = value,
            onValueChange = { nextValue ->
                raftBrutalInputForwardValueChange(focusPolicy = focusPolicy, nextValue = nextValue, onValueChange = onValueChange)
            },
            modifier = modifier.fillMaxWidth().then(editor.modifier),
            placeholder = placeholder.ifEmpty { null },
            enabled = enabled,
            readOnly = readOnly,
            invalid = borderColor == RaftColor.AccentRed,
            trailingAction =
                if (showClearWhenNotEmpty && value.text.isNotEmpty() && focusPolicy.acceptsFocus) {
                    { RaftTextFieldClearAction(onClear = { onClear?.invoke() ?: onValueChange(TextFieldValue("")) }) }
                } else {
                    null
                },
            keyboardOptions = editor.keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation
        )
        return
    }
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
                    size = 18.dp
                )
            }
            trailingContent?.invoke()
        }
    }
}

/**
 * True when a legacy input is the plain raft-ui field the design-system [RaftTextField]
 * draws: one line, the legacy height / padding defaults and the default (or error) chrome.
 * Multi-line inputs, a trailing control and custom chrome (a field inside another frame)
 * keep [RaftBrutalInputFieldFrame]: the design system has no multi-line TextFieldValue
 * field or trailing-action slot yet.
 */
internal fun raftBrutalInputUsesDesignSystem(
    maxLines: Int,
    fieldMinHeight: Dp,
    horizontalPadding: Dp,
    verticalPadding: Dp,
    borderColor: Color,
    enabledBackgroundColor: Color,
    disabledBackgroundColor: Color
): Boolean =
    maxLines <= 1 &&
        (fieldMinHeight == RaftMetrics.LegacyFieldHeight || fieldMinHeight == RaftMetrics.FieldHeight) &&
        horizontalPadding == RaftBrutalInputLegacyHorizontalPadding &&
        verticalPadding == 8.dp &&
        (borderColor == RaftColor.BorderBlack || borderColor == RaftColor.AccentRed) &&
        (enabledBackgroundColor == RaftColor.SurfaceWhite || enabledBackgroundColor == RaftFieldErrorTint) &&
        disabledBackgroundColor == RaftColor.SurfaceCream

internal class RaftDesignSystemEditor(val modifier: Modifier, val keyboardOptions: KeyboardOptions)

/**
 * The app editor behaviour ([raftAppEditorBehavior]: IME enter action, keyboard-height
 * events, focus callbacks, tap-outside focus dismiss) for a single-line design-system
 * [RaftTextField], plus the delayed [autoFocus] request of the app editor.
 */
@Composable
internal fun rememberRaftDesignSystemEditor(
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions,
    keyboardHeightChange: (KeyboardParams) -> Unit,
    focusRequester: FocusRequester,
    autoFocus: Boolean,
    onFocus: () -> Unit,
    onBlur: () -> Unit
): RaftDesignSystemEditor {
    val resolvedKeyboardOptions = raftResolvedKeyboardOptions(keyboardOptions, singleLine = true, imeActionRequested = true)
    val modifier =
        Modifier.raftAppEditorBehavior(
            singleLine = true,
            resolvedKeyboardOptions = resolvedKeyboardOptions,
            keyboardActions = keyboardActions,
            keyboardHeightChange = keyboardHeightChange,
            focusRequester = focusRequester,
            onFocus = onFocus,
            onBlur = onBlur
        )
    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            delay(100)
            focusRequester.requestFocus()
        }
    }
    return RaftDesignSystemEditor(modifier, resolvedKeyboardOptions)
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

/**
 * Legacy-named read-only field: the design-system [RaftField] (`sm` label) round a
 * [RaftReadOnlyField] that wraps up to [maxLines] lines. The type / colour / padding
 * parameters of the retired box are ignored.
 */
@Suppress("UNUSED_PARAMETER")
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
    // raft-ui: the disabled-field value under a `sm` label (CopyableCode / read-only Input).
    RaftField(label = label.takeIf { it.isNotBlank() }, modifier = modifier.fillMaxWidth(), labelSize = RaftFieldLabelSize.Sm) {
        RaftReadOnlyField(text = value, maxLines = maxLines)
    }
}

/**
 * Legacy-named search field; renders the design-system [RaftTextField] input group with a
 * leading search addon and the clear button addon ([RaftTextFieldClearAction]), with the app editor behaviour
 * ([rememberRaftDesignSystemEditor]). [textStyle] is ignored: fields use the `field` type.
 */
@Suppress("UNUSED_PARAMETER")
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
    val editor = rememberRaftSearchEditor(onFocus, onBlur)
    RaftTextField(
        value = value,
        onValueChange = { if (enabled) onValueChange(it) },
        modifier = modifier.fillMaxWidth().then(editor.modifier),
        placeholder = placeholder.ifEmpty { null },
        enabled = enabled,
        leading = { RaftSlotIcon(leadingIcon) },
        trailingAction = if (showClearWhenNotEmpty && value.isNotEmpty()) ({ RaftTextFieldClearAction(onClear) }) else null,
        keyboardOptions = editor.keyboardOptions
    )
}

@Suppress("UNUSED_PARAMETER")
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
    val editor = rememberRaftSearchEditor(onFocus, onBlur)
    RaftTextField(
        value = value,
        onValueChange = { if (enabled) onValueChange(it) },
        modifier = modifier.fillMaxWidth().then(editor.modifier),
        placeholder = placeholder.ifEmpty { null },
        enabled = enabled,
        leading = { RaftSlotIcon(leadingIcon) },
        trailingAction = if (showClearWhenNotEmpty && value.text.isNotEmpty()) ({ RaftTextFieldClearAction(onClear) }) else null,
        keyboardOptions = editor.keyboardOptions
    )
}

@Composable
private fun rememberRaftSearchEditor(onFocus: () -> Unit, onBlur: () -> Unit): RaftDesignSystemEditor {
    val focusRequester = remember { FocusRequester() }
    return rememberRaftDesignSystemEditor(
        keyboardOptions = KeyboardOptions.Default,
        keyboardActions = KeyboardActions.Default,
        keyboardHeightChange = {},
        focusRequester = focusRequester,
        autoFocus = false,
        onFocus = onFocus,
        onBlur = onBlur
    )
}

/** The brutal search type (13/16); raft swaps a field still on it for `text-field` 16/24. */
internal val RaftBrutalSearchDefaultTextStyle: TextStyle
    get() = RaftType.Caption.copy(fontSize = 13.sp, lineHeight = 16.sp, color = RaftColor.TextBlack)

/**
 * Legacy-named button; renders the design-system [RaftButton]. The variant comes from
 * [backgroundColor] ([raftButtonVariantFor]), the size from [minHeight]
 * ([raftButtonSizeFor]). [textStyle], the disabled* colours, [leadingIconSize] and
 * [contentSpacing] only shaped the retired brutal register and are
 * ignored. Prefer `build.raft.designsystem.components.RaftButton` in new code.
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
    disabledBackgroundColor: Color = RaftColor.FillMuted,
    disabledBorderColor: Color = RaftColor.BorderHairline,
    disabledShadowColor: Color = RaftColor.BorderHairline,
    disabledContentColor: Color = RaftColor.ForegroundDisabled,
    disabledIconColor: String = "#FF999999",
    minWidth: Dp = RaftBrutalActionButtonLegacyMinWidth,
    minHeight: Dp = 40.dp,
    leadingIcon: RaftLucideIconName? = null,
    leadingIconSpinning: Boolean = leadingIcon == RaftLucideIconName.Loader || leadingIcon == RaftLucideIconName.LoaderCircle,
    leadingIconSize: Dp = 12.dp,
    contentSpacing: Dp = 6.dp,
    textStyle: TextStyle = RaftBrutalActionButtonDefaultTextStyle,
    trailingContent: (@Composable () -> Unit)? = null
) {
    // An anchored click hands the caller the button's bottom-start corner (menus open under it).
    val anchor = if (onClick2 != null) remember { RaftLiveRootAnchorRef(identity = "action_button:$label") } else null
    val currentOnClick2 by rememberUpdatedState(onClick2)
    RaftButton(
        text = label,
        onClick = {
            onClick()
            val anchored = currentOnClick2
            if (anchored != null) anchor?.resolve(RaftPopoverLocalPoint.BottomStart)?.let(anchored)
        },
        modifier =
            modifier
                .then(if (anchor != null) Modifier.raftCaptureLiveRootAnchor(anchor) else Modifier)
                .then(if (minWidth != RaftBrutalActionButtonLegacyMinWidth && minWidth > 0.dp) Modifier.widthIn(min = minWidth) else Modifier),
        variant = raftButtonVariantFor(backgroundColor, inDialogFooter = LocalRaftDialogFooter.current),
        size = raftButtonSizeFor(minHeight),
        enabled = enabled,
        leadingIcon = leadingIcon?.let { icon -> { RaftSlotIcon(icon, spinning = leadingIconSpinning) } },
        trailingIcon = trailingContent
    )
}

internal val RaftBrutalActionButtonDefaultTextStyle: TextStyle
    // Getter: the UI-text locale and rendering defaults can change at runtime.
    get() =
        RaftType.ButtonBold.copy(fontSize = 13.sp, lineHeight = 16.sp)

// The brutal button's default min width; raft buttons have none (they hug the label).
private val RaftBrutalActionButtonLegacyMinWidth = 82.dp

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
                // CSS `px-3` sits inside the 1.5dp (transparent at rest) border box.
                .padding(horizontal = 12.dp + RaftAppStroke.Control)
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
            active -> raftInkOnFill(restingFill, RaftColor.TextBlack)
            else -> RaftColor.TextMuted
        }
    // Count / trailing chevron: hint ink, or sticker ink on a bright active fill (BrandYellow) in dark.
    val metaInk =
        when {
            !enabled -> RaftColor.ForegroundDisabled
            active -> raftInkOnFill(restingFill, RaftColor.ForegroundHint)
            else -> RaftColor.ForegroundHint
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
                    color = metaInk,
                    maxLines = 1
                )
            }
            trailingIconName?.let {
                RaftLucideIcon(
                    it,
                    modifier = Modifier.size(14.dp),
                    color = raftArgbHex(metaInk),
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
 * Legacy-named icon button; renders the design-system [RaftIconButton] (size from
 * [raftIconButtonSizeFor]: `xs` 24dp, `sm` 28dp, `icon-md` 32dp, `icon-lg` 44dp), its variant
 * from [backgroundColor] ([raftIconButtonVariantFor]: the paper default is the ghost icon
 * button), named and tooltipped by [contentDescription] (required from 24dp up: the design
 * system's icon button needs a name). The design system has no icon button under 24dp, so
 * those callers keep the raft-look box ([RaftLookIconButton]). The border / shadow /
 * disabled-colour parameters of the retired brutal look are ignored.
 * Prefer `build.raft.designsystem.components.RaftIconButton` in new code.
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
    iconSize: Dp = (size.value * 0.5f).dp,
    iconSpinning: Boolean = iconName == RaftLucideIconName.Loader || iconName == RaftLucideIconName.LoaderCircle
) {
    val tooltip = contentDescription?.takeIf { it.isNotBlank() }
    if (size < 24.dp) {
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
        return
    }
    RaftIconButton(
        onClick = onClick,
        tooltip = tooltip,
        modifier = modifier,
        variant = raftIconButtonVariantFor(backgroundColor),
        size = raftIconButtonSizeFor(size),
        enabled = enabled,
        onLongClick = onLongClick,
        longClickLabel = longClickLabel
    ) {
        // An explicit ink stays (media controls on a scrim); the legacy black follows the variant.
        val explicitInk = iconColor.takeIf { enabled && it != RaftLegacyIconInkArgb }
        if (explicitInk != null) {
            RaftLucideIcon(iconName, color = explicitInk, modifier = Modifier.fillMaxSize(), spinning = iconSpinning)
        } else {
            RaftSlotIcon(iconName, spinning = iconSpinning)
        }
    }
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
