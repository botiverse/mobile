package build.raft.app.components

import build.raft.app.Button as SlockAnchorButton
import build.raft.app.SlockColor
import build.raft.app.SlockLucideIcon
import build.raft.app.SlockLucideIconName
import build.raft.app.SlockMutableOffsetRef
import build.raft.app.SlockShadow
import build.raft.app.SlockSpacing
import build.raft.app.SlockStroke
import build.raft.app.SlockType
import build.raft.app.TextField
import build.raft.app.slockSquareCorners
import build.raft.app.withSlockFontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.text.KeyboardActions
import com.tencent.kuikly.compose.foundation.text.KeyboardOptions
import com.tencent.kuikly.compose.ui.text.input.KeyboardType
import com.tencent.kuikly.compose.ui.text.input.PasswordVisualTransformation
import com.tencent.kuikly.compose.ui.text.input.VisualTransformation
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.BoxScope
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
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
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.focus.FocusRequester
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.graphics.RectangleShape
import com.tencent.kuikly.compose.ui.text.TextStyle
import com.tencent.kuikly.compose.ui.text.font.FontFamily
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.layout.onGloballyPositioned
import com.tencent.kuikly.compose.ui.layout.positionInRoot
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp

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

internal enum class SlockAnchorPlacement {
    Tap,
    BottomStart,
    BottomEnd
}

internal fun slockAnchoredChipGeometryAnchor(
    root: Offset,
    width: Float,
    height: Float,
    placement: SlockAnchorPlacement
): Offset =
    when (placement) {
        SlockAnchorPlacement.Tap -> root
        SlockAnchorPlacement.BottomStart -> Offset(root.x, root.y + height)
        SlockAnchorPlacement.BottomEnd -> Offset(root.x + width, root.y + height)
    }

internal fun slockAnchoredTriggerGeometryAnchor(
    root: Offset,
    width: Float,
    height: Float,
    placement: SlockAnchorPlacement
): Offset = slockAnchoredChipGeometryAnchor(root, width, height, placement)

@Composable
internal fun SlockAnchoredMetaChip(
    label: String,
    iconName: SlockLucideIconName?,
    color: Color,
    onClick: ((com.tencent.kuikly.compose.ui.geometry.Offset) -> Unit)?,
    modifier: Modifier = Modifier,
    iconSize: Dp = 10.dp,
    trailingIconName: SlockLucideIconName? = null,
    trailingIconSize: Dp = iconSize,
    borderWidth: Dp = SlockStroke.Thin,
    pressFeedback: Boolean = onClick != null,
    anchorPlacement: SlockAnchorPlacement = SlockAnchorPlacement.Tap,
    clickIdentity: Any? = null
) {
    val geometryAnchor = remember { SlockMutableOffsetRef() }
    val chipModifier =
        modifier
            .heightIn(min = 20.dp)
            .border(borderWidth, SlockColor.BorderBlack, RectangleShape)
            .background(color, RectangleShape)
            .slockSquareCorners()
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .let { base ->
                if (anchorPlacement == SlockAnchorPlacement.Tap) {
                    base
                } else {
                    base.onGloballyPositioned { coordinates ->
                        val root = coordinates.positionInRoot()
                        geometryAnchor.update(
                            slockAnchoredChipGeometryAnchor(
                                root = root,
                                width = coordinates.size.width.toFloat(),
                                height = coordinates.size.height.toFloat(),
                                placement = anchorPlacement
                            )
                        )
                    }
                }
            }
    SlockAnchorButton(
        onClick2 = { tapAnchor ->
            val anchor =
                if (anchorPlacement == SlockAnchorPlacement.Tap || !geometryAnchor.hasValue) {
                    tapAnchor
                } else {
                    geometryAnchor.value
                }
            onClick?.invoke(anchor)
        },
        enabled = onClick != null,
        pressFeedback = pressFeedback,
        clickIdentity = clickIdentity,
        modifier = chipModifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
        ) {
            iconName?.let {
                SlockLucideIcon(it, modifier = Modifier.size(iconSize), color = "#FF000000")
            }
            Text(
                text = label,
                style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 10.sp, lineHeight = 12.sp),
                color = SlockColor.TextBlack,
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
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label.uppercase(),
            style = SlockType.SectionLabel.copy(fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.1.sp),
            color = SlockColor.TextBlack,
            maxLines = 1
        )
        if (required) {
            Text(
                text = "*",
                style = SlockType.SectionLabel.copy(fontSize = 11.sp, lineHeight = 14.sp),
                color = SlockColor.AccentPink,
                maxLines = 1
            )
        }
        if (optional) {
            Text(
                text = "(optional)",
                style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 11.sp, lineHeight = 14.sp),
                color = SlockColor.TextMutedGray,
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
                .fillMaxSize()
                .background(SlockColor.TextBlack.copy(alpha = model.overlayAlpha))
                .clickable(onClick = onDismiss)
                .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = model.maxWidth)
                    .padding(end = 4.dp, bottom = 4.dp)
                    .background(SlockColor.ShadowBlack)
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

@Composable
internal fun SlockBrutalActionSheetRow(
    model: SlockActionSheetRowModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        shape = RectangleShape,
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
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (model.subtitle.isNotBlank()) {
                    Text(
                        text = model.subtitle,
                        style = SlockType.Caption,
                        color = SlockColor.TextMutedGray,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
internal fun SlockBrutalTextField(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    required: Boolean = false,
    optional: Boolean = false,
    enabled: Boolean = true,
    autoFocus: Boolean = false,
    maxLines: Int = 1,
    textStyle: TextStyle = SlockType.Input.copy(fontSize = 14.sp, lineHeight = 18.sp, color = SlockColor.TextBlack),
    fieldMinHeight: Dp = 42.dp,
    /**
     * Task #43 QA-4 fix: when `true`, the text field uses a native password
     * visual transformation that masks the value with `•` while keeping
     * the underlying string in `value` intact. Show/hide toggles in the
     * caller are expected to switch this flag, not edit bullet chars
     * into the real value.
     */
    password: Boolean = false
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SlockBrutalSectionLabel(label = label, required = required, optional = optional)
        SlockBrutalInputField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            enabled = enabled,
            autoFocus = autoFocus,
            maxLines = maxLines,
            textStyle = textStyle,
            fieldMinHeight = fieldMinHeight,
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions =
                if (password) {
                    KeyboardOptions(keyboardType = KeyboardType.Password)
                } else {
                    KeyboardOptions.Default
                }
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
    autoFocus: Boolean = false,
    maxLines: Int = 1,
    textStyle: TextStyle = SlockType.Input.copy(fontSize = 14.sp, lineHeight = 18.sp, color = SlockColor.TextBlack),
    fieldMinHeight: Dp = 42.dp,
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
    focusRequester: FocusRequester = remember { FocusRequester() },
    showClearWhenNotEmpty: Boolean = false,
    onClear: (() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null
) {
    var focused by remember { mutableStateOf(false) }
    val isMultiline = maxLines > 1
    val fieldContentAlignment = if (isMultiline) Alignment.TopStart else Alignment.CenterStart
    val rowVerticalAlignment = if (isMultiline) Alignment.Top else Alignment.CenterVertically
    val shadowOffset = if (showShadow) {
        if ((focused || forceFocused) && enabled) SlockShadow.Brutal else SlockShadow.BrutalSmall
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
                    .background(if (enabled) enabledBackgroundColor else disabledBackgroundColor)
                    .border(SlockStroke.Default, borderColor)
                    .padding(horizontal = horizontalPadding, vertical = verticalPadding),
            contentAlignment = fieldContentAlignment
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = rowVerticalAlignment
            ) {
                TextField(
                    value = value,
                    onValueChange = { if (enabled) onValueChange(it) },
                    placeholder = placeholder,
                    placeholderColor = placeholderColor,
                    autoFocus = autoFocus,
                    onFocus = { focused = true },
                    onBlur = { focused = false },
                    maxLines = maxLines,
                    keyboardOptions = keyboardOptions,
                    keyboardActions = keyboardActions,
                    textStyle = textStyle,
                    visualTransformation = visualTransformation,
                    contentAlignment = fieldContentAlignment,
                    modifier = Modifier.weight(1f),
                    focusRequester = focusRequester
                )
                if (showClearWhenNotEmpty && value.isNotEmpty() && enabled) {
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
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SlockBrutalSectionLabel(label = label)
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
        TextField(
            value = value,
            onValueChange = {},
            placeholder = "",
            autoFocus = false,
            maxLines = maxLines,
            textStyle = textStyle.copy(color = textColor),
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
    textStyle: TextStyle = SlockType.Caption.copy(fontSize = 13.sp, lineHeight = 16.sp, color = SlockColor.TextBlack)
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
                onFocus = { focused = true },
                onBlur = { focused = false },
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
private fun SlockBrutalActionButtonInnerContent(
    label: String,
    leadingIcon: SlockLucideIconName?,
    leadingIconSpinning: Boolean,
    textStyle: TextStyle,
    enabled: Boolean,
    trailingContent: (@Composable () -> Unit)? = null
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(if (leadingIcon != null || trailingContent != null) 6.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingIcon != null) {
            SlockLucideIcon(leadingIcon, modifier = Modifier.size(12.dp), color = "#FF000000", spinning = leadingIconSpinning)
        }
        Text(
            text = label,
            style = textStyle,
            color = if (enabled) SlockColor.TextBlack else SlockColor.TextBlack.copy(alpha = 0.40f),
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
    disabledBackgroundColor: Color = SlockColor.SurfaceCream,
    minWidth: Dp = 82.dp,
    minHeight: Dp = 40.dp,
    leadingIcon: SlockLucideIconName? = null,
    leadingIconSpinning: Boolean = leadingIcon == SlockLucideIconName.Loader || leadingIcon == SlockLucideIconName.LoaderCircle,
    shadowOffset: Dp = SlockShadow.Brutal,
    textStyle: TextStyle = SlockBrutalActionButtonDefaultTextStyle,
    trailingContent: (@Composable () -> Unit)? = null
) {
    val chromeAlpha = if (enabled) 1f else 0.40f
    Box(
        modifier =
            modifier
                .padding(end = shadowOffset, bottom = shadowOffset),
        propagateMinConstraints = true
    ) {
        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .offset(x = shadowOffset, y = shadowOffset)
                    .background(SlockColor.ShadowBlack.copy(alpha = chromeAlpha), RectangleShape)
                    .slockSquareCorners()
        )
        SlockAnchorButton(
            onClick = onClick,
            enabled = enabled,
            pressFeedback = enabled,
            modifier =
                Modifier
                    .widthIn(min = minWidth)
                    .heightIn(min = minHeight)
                    .background(if (enabled) backgroundColor else disabledBackgroundColor, RectangleShape)
                    .border(SlockStroke.Default, SlockColor.BorderBlack, RectangleShape)
                    .slockSquareCorners()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            SlockBrutalActionButtonInnerContent(
                label = label,
                leadingIcon = leadingIcon,
                leadingIconSpinning = leadingIconSpinning,
                textStyle = textStyle,
                enabled = enabled,
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
        horizontalArrangement = Arrangement.spacedBy(4.dp),
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
    Row(
        modifier =
            Modifier
                .height(28.dp)
                .background(if (selected) SlockColor.BrandYellow else SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .clickable(enabled = enabled) { onClick() }
                .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
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
            Text(
                text = count.toString(),
                style = SlockType.Caption.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 10.sp),
                color = SlockColor.TextBlack.copy(alpha = if (selected) 0.6f else 0.4f),
                maxLines = 1
            )
        }
    }
}

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
                    text = if (uppercase) label.uppercase() else label,
                    style = SlockType.TabLabel.copy(fontSize = 11.sp, lineHeight = 14.sp),
                    color = if (enabled) SlockColor.TextBlack else SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                count?.let {
                    Text(
                        text = it.toString(),
                        style = SlockType.Caption.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 10.sp),
                        color = SlockColor.TextBlack,
                        modifier =
                            Modifier
                                .background(SlockColor.SurfaceWhite, RectangleShape)
                                .border(SlockStroke.Thin, SlockColor.BorderBlack, RectangleShape)
                                .slockSquareCorners()
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        maxLines = 1
                    )
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
    onClick: (Offset) -> Unit,
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
    onAnchorChanged: ((Offset) -> Unit)? = null
) {
    val anchor = remember { SlockMutableOffsetRef() }
    Box(
        modifier =
            modifier.onGloballyPositioned { coords ->
                val root = coords.positionInRoot()
                val next = Offset(root.x, root.y + coords.size.height.toFloat())
                if (!anchor.hasValue || anchor.value != next) {
                    anchor.update(next)
                    onAnchorChanged?.invoke(next)
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
            onClick = { if (anchor.hasValue) onClick(anchor.value) }
        )
    }
}

@Composable
internal fun SlockBrutalIconButton(
    iconName: SlockLucideIconName,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
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
        Box(
            modifier =
                Modifier
                    .size(size)
                    .background(resolvedBackground)
                    .border(SlockStroke.Default, resolvedBorder)
                    .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            SlockLucideIcon(iconName, color = resolvedIconColor, modifier = Modifier.size(iconSize), spinning = iconSpinning)
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
    content: LazyListScope.() -> Unit
) {
    // Brutal-shadow pattern: outer Box reserves shadowOffset extra space on
    // right + bottom via padding; shadow Box inside is matchParentSize() +
    // offset(shadowOffset) so the dark slab peeks out uniformly. Foreground
    // LazyColumn fills the non-shadow area with bordered SurfaceWhite.
    // Mirrors the proven SlockBrutalCard pattern (SlockBrutalCard.kt) and
    // fixes the earlier broken `padding(end, bottom).background(ShadowBlack)`
    // pattern that did not actually reserve outer space for the shadow to
    // peek out. (Visual diff evidence:
    // `visual-testing-results/diff/react__android/components.channel.members.panel.side-by-side.png`
    // showed 81.18% pixel mismatch with a flat inner list box in Android.)
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
                    .border(SlockStroke.Default, SlockColor.BorderBlack),
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
