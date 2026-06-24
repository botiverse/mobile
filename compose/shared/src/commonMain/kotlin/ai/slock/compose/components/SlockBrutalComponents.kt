package ai.slock.compose.components

import ai.slock.compose.Button as SlockAnchorButton
import ai.slock.compose.SlockColor
import ai.slock.compose.SlockLucideIcon
import ai.slock.compose.SlockLucideIconName
import ai.slock.compose.SlockMutableOffsetRef
import ai.slock.compose.SlockSpacing
import ai.slock.compose.SlockStroke
import ai.slock.compose.SlockType
import ai.slock.compose.TextField
import ai.slock.compose.withSlockFontWeight
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
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.text.TextStyle
import com.tencent.kuikly.compose.ui.text.font.FontFamily
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.layout.onGloballyPositioned
import com.tencent.kuikly.compose.ui.layout.positionInRoot
import com.tencent.kuikly.compose.ui.semantics.Role
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp

internal data class SlockModalShellModel(
    val title: String,
    val subtitle: String = "",
    val overlayAlpha: Float = 0.58f,
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
    pressFeedback: Boolean = onClick != null
) {
    SlockAnchorButton(
        onClick2 = { anchor -> onClick?.invoke(anchor) },
        enabled = onClick != null,
        pressFeedback = pressFeedback,
        modifier =
            modifier
                .heightIn(min = 20.dp)
                .border(SlockStroke.Thin, SlockColor.BorderBlack)
                .background(color)
                .padding(horizontal = 6.dp, vertical = 2.dp)
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
        modifier =
            modifier
                .fillMaxWidth()
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
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    showClearWhenNotEmpty: Boolean = false,
    onClear: (() -> Unit)? = null
) {
    var focused by remember { mutableStateOf(false) }
    val shadowOffset = if (showShadow) {
        if (focused && enabled) 4.dp else 3.dp
    } else {
        0.dp
    }
    val reservedShadowOffset = if (showShadow) 4.dp else 0.dp
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
                        .background(SlockColor.ShadowBlack)
            )
        }
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(fieldMinHeight)
                    .background(if (enabled) SlockColor.SurfaceWhite else SlockColor.SurfaceCream)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .padding(horizontal = horizontalPadding, vertical = verticalPadding),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = value,
                    onValueChange = { if (enabled) onValueChange(it) },
                    placeholder = placeholder,
                    placeholderColor = SlockColor.TextMutedGray,
                    autoFocus = autoFocus,
                    onFocus = { focused = true },
                    onBlur = { focused = false },
                    maxLines = maxLines,
                    keyboardOptions = keyboardOptions,
                    keyboardActions = keyboardActions,
                    textStyle = textStyle,
                    visualTransformation = visualTransformation,
                    modifier = Modifier.weight(1f)
                )
                if (showClearWhenNotEmpty && value.isNotEmpty() && enabled) {
                    SlockBrutalIconButton(
                        iconName = SlockLucideIconName.X,
                        onClick = { onClear?.invoke() ?: onValueChange("") },
                        size = 18.dp,
                        shadowOffset = 2.dp
                    )
                }
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
    textStyle: TextStyle = SlockType.Caption.copy(fontSize = 13.sp, lineHeight = 16.sp, color = SlockColor.TextBlack)
) {
    var focused by remember { mutableStateOf(false) }
    val shadowOffset = if (focused && enabled) 4.dp else 3.dp
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
                    .background(SlockColor.ShadowBlack)
        )
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(42.dp)
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
        }
    }
}

@Composable
private fun SlockBrutalActionButtonInnerContent(
    label: String,
    leadingIcon: SlockLucideIconName?,
    textStyle: TextStyle,
    enabled: Boolean,
    trailingContent: (@Composable () -> Unit)? = null
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(if (leadingIcon != null || trailingContent != null) 6.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingIcon != null) {
            SlockLucideIcon(leadingIcon, modifier = Modifier.size(12.dp), color = "#FF000000")
        }
        Text(
            text = label,
            style = textStyle,
            color = if (enabled) SlockColor.TextBlack else SlockColor.TextMutedGray,
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
    shadowOffset: Dp = 3.dp,
    textStyle: TextStyle = SlockType.Button.copy(fontSize = 13.sp, lineHeight = 16.sp),
    trailingContent: (@Composable () -> Unit)? = null
) {
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
                    .background(SlockColor.ShadowBlack)
        )
        Box(
            modifier =
                Modifier
                    .widthIn(min = minWidth)
                    .heightIn(min = minHeight)
                    .background(if (enabled) backgroundColor else disabledBackgroundColor)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            SlockBrutalActionButtonInnerContent(
                label = label,
                leadingIcon = leadingIcon,
                textStyle = textStyle,
                enabled = enabled,
                trailingContent = trailingContent
            )
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
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEach { option ->
            val selected = option.value == selectedValue
            SlockBrutalActionButton(
                label = option.label,
                leadingIcon = option.iconName,
                trailingContent =
                    option.count?.takeIf { it > 0 }?.let { count ->
                        {
                            Text(
                                text = count.toString(),
                                style = SlockType.Caption.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 10.sp),
                                color = SlockColor.TextBlack.copy(alpha = if (selected) 0.6f else 0.4f),
                                maxLines = 1
                            )
                        }
                    },
                enabled = enabled,
                minWidth = 0.dp,
                minHeight = 28.dp,
                shadowOffset = if (selected) 2.dp else 0.dp,
                backgroundColor = if (selected) SlockColor.BrandYellow else SlockColor.SurfaceWhite,
                textStyle = SlockType.ButtonBold.copy(fontSize = 12.sp, lineHeight = 14.sp),
                onClick = { onValueSelected(option.value) }
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
                        .background(activeShadowColor)
            )
        }
        Box(
            modifier =
                Modifier
                    .heightIn(min = 30.dp)
                    .background(if (active) activeBackgroundColor else inactiveBackgroundColor)
                    .border(SlockStroke.Default, resolvedBorderColor)
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
                                .background(SlockColor.SurfaceWhite)
                                .border(SlockStroke.Thin, SlockColor.BorderBlack)
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
    shadowOffset: Dp = 3.dp,
    iconSize: Dp = (size.value * 0.5f).dp
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
            SlockLucideIcon(iconName, color = resolvedIconColor, modifier = Modifier.size(iconSize))
        }
    }
}

@Composable
internal fun SlockBrutalPanelShell(
    modifier: Modifier = Modifier,
    shadowOffset: Dp = 4.dp,
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
    shadowOffset: Dp = 4.dp,
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
