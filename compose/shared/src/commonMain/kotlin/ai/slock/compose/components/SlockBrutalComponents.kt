package ai.slock.compose.components

import ai.slock.compose.Button as SlockAnchorButton
import ai.slock.compose.SlockColor
import ai.slock.compose.SlockLucideIcon
import ai.slock.compose.SlockSpacing
import ai.slock.compose.SlockStroke
import ai.slock.compose.SlockType
import ai.slock.compose.TextField
import ai.slock.compose.toSlockDpOffset
import ai.slock.compose.withSlockFontWeight
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
import com.tencent.kuikly.compose.ui.draw.alpha
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.text.TextStyle
import com.tencent.kuikly.compose.ui.text.font.FontFamily
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.layout.onGloballyPositioned
import com.tencent.kuikly.compose.ui.layout.positionInRoot
import com.tencent.kuikly.compose.ui.platform.LocalDensity
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp

internal data class SlockModalShellModel(
    val title: String,
    val subtitle: String = "",
    val overlayAlpha: Float = 0.58f,
    val maxWidth: Dp = 384.dp,
    val dismissIcon: String = "x"
)

internal data class SlockActionSheetRowModel(
    val title: String,
    val subtitle: String = "",
    val icon: String,
    val destructive: Boolean = false
)

internal data class SegmentedControlOption(
    val value: String,
    val label: String,
    val iconName: String? = null,
    val count: Int? = null
)

@Composable
internal fun SlockAnchoredMetaChip(
    label: String,
    iconName: String?,
    color: Color,
    onClick: ((com.tencent.kuikly.compose.ui.geometry.Offset) -> Unit)?,
    modifier: Modifier = Modifier,
    iconSize: Dp = 10.dp
) {
    SlockAnchorButton(
        onClick2 = { anchor -> onClick?.invoke(anchor) },
        enabled = onClick != null,
        pressFeedback = onClick != null,
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
            iconName?.takeIf { it.isNotBlank() }?.let {
                SlockLucideIcon(it, modifier = Modifier.size(iconSize), color = "#FF000000")
            }
            Text(
                text = label,
                style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 10.sp, lineHeight = 12.sp),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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
internal fun SlockModalFieldLabelComponent(
    label: String,
    required: Boolean = false,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = SlockType.ButtonBold.copy(fontSize = 13.sp, lineHeight = 16.sp),
            color = SlockColor.TextBlack
        )
        if (required) {
            Text(
                text = "*",
                style = SlockType.ButtonBold.copy(fontSize = 13.sp, lineHeight = 16.sp),
                color = SlockColor.AccentRed
            )
        }
    }
}

@Composable
internal fun SlockModalTextFieldComponent(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    required: Boolean = false,
    isError: Boolean = required && value.isBlank(),
    leadingIcon: String? = null,
    enabled: Boolean = true,
    fieldHeight: Dp = 42.dp,
    maxLines: Int = 1,
    errorText: String? = null
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor =
        when {
            isError -> SlockColor.AccentRed
            focused -> SlockColor.TextBlack
            enabled -> SlockColor.BorderBlack
            else -> SlockColor.TextMutedGray
        }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SlockModalFieldLabelComponent(label = label, required = required)
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(end = if (focused) 3.dp else 0.dp, bottom = if (focused) 3.dp else 0.dp)
                    .background(if (focused) SlockColor.ShadowBlack else Color.Transparent)
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(fieldHeight)
                        .padding(end = if (focused) 3.dp else 0.dp, bottom = if (focused) 3.dp else 0.dp)
                        .background(if (enabled) SlockColor.SurfaceWhite else SlockColor.SurfaceCream)
                        .border(SlockStroke.Default, borderColor)
                        .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (leadingIcon != null) {
                    SlockLucideIcon(leadingIcon, modifier = Modifier.size(14.dp), color = "#66000000")
                }
                TextField(
                    modifier = Modifier.weight(1f).height((fieldHeight.value - 8f).dp),
                    value = value,
                    placeholder = placeholder,
                    autoFocus = false,
                    onValueChange = onValueChange,
                    onFocus = { focused = true },
                    onBlur = { focused = false },
                    textStyle = SlockType.Input.copy(fontSize = 14.sp, lineHeight = 18.sp, color = SlockColor.TextBlack),
                    placeholderColor = SlockColor.TextMutedGray,
                    maxLines = maxLines
                )
            }
        }
        val resolvedError = errorText ?: if (isError) "$label is required." else null
        if (resolvedError != null) {
            Text(
                text = resolvedError,
                style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.AccentRed),
                color = SlockColor.AccentRed
            )
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
    fieldMinHeight: Dp = 42.dp
) {
    var focused by remember { mutableStateOf(false) }
    val shadowOffset = if (focused && enabled) 4.dp else 3.dp
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SlockBrutalSectionLabel(label = label, required = required, optional = optional)
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(fieldMinHeight + shadowOffset)
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(fieldMinHeight)
                        .offset(x = shadowOffset, y = shadowOffset)
                        .background(SlockColor.ShadowBlack)
            )
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(fieldMinHeight)
                        .background(if (enabled) SlockColor.SurfaceWhite else SlockColor.SurfaceCream)
                        .border(SlockStroke.Default, SlockColor.BorderBlack)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                contentAlignment = Alignment.CenterStart
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
                    textStyle = textStyle,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
internal fun SlockBrutalSearchField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: String = "search",
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
    leadingIcon: String?,
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
    minWidth: Dp = 82.dp,
    minHeight: Dp = 40.dp,
    leadingIcon: String? = null,
    shadowOffset: Dp = 3.dp,
    textStyle: TextStyle = SlockType.Button.copy(fontSize = 13.sp, lineHeight = 16.sp),
    trailingContent: (@Composable () -> Unit)? = null
) {
    Box(
        modifier =
            modifier
                .padding(end = shadowOffset, bottom = shadowOffset)
    ) {
        Box(
            modifier =
                Modifier
                    .offset(x = shadowOffset, y = shadowOffset)
                    .widthIn(min = minWidth)
                    .heightIn(min = minHeight)
                    .background(SlockColor.ShadowBlack)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            SlockBrutalActionButtonInnerContent(
                label = label,
                leadingIcon = leadingIcon,
                textStyle = textStyle,
                enabled = enabled,
                trailingContent = trailingContent?.let { content -> { Box(modifier = Modifier.alpha(0f)) { content() } } }
            )
        }
        Box(
            modifier =
                Modifier
                    .widthIn(min = minWidth)
                    .heightIn(min = minHeight)
                    .background(if (enabled) backgroundColor else SlockColor.SurfaceCream)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .clickable(enabled = enabled, onClick = onClick)
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
    iconName: String? = null,
    trailingIconName: String? = null,
    active: Boolean = false,
    enabled: Boolean = true,
    uppercase: Boolean = true,
    count: Int? = null,
    activeBackgroundColor: Color = SlockColor.AccentCyan,
    inactiveBackgroundColor: Color = SlockColor.SurfaceWhite
) {
    Box(
        modifier =
            modifier
                .heightIn(min = 30.dp)
                .background(if (active) activeBackgroundColor else inactiveBackgroundColor)
                .border(SlockStroke.Default, if (enabled) SlockColor.BorderBlack else SlockColor.EmptyStateGray)
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

@Composable
internal fun SlockAnchoredToolbarChip(
    label: String,
    onClick: (Offset) -> Unit,
    modifier: Modifier = Modifier,
    iconName: String? = null,
    trailingIconName: String? = "chevron-down",
    active: Boolean = false,
    enabled: Boolean = true,
    uppercase: Boolean = true,
    count: Int? = null,
    activeBackgroundColor: Color = SlockColor.AccentCyan,
    inactiveBackgroundColor: Color = SlockColor.SurfaceWhite
) {
    val density = LocalDensity.current
    var anchor by remember { mutableStateOf<Offset?>(null) }
    Box(
        modifier =
            modifier.onGloballyPositioned { coords ->
                val root = coords.positionInRoot().toSlockDpOffset(density)
                val height = with(density) { coords.size.height.toDp().value }
                anchor = Offset(root.x, root.y + height)
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
            onClick = { anchor?.let(onClick) }
        )
    }
}

@Composable
internal fun SlockBrutalIconButton(
    iconName: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backgroundColor: Color = SlockColor.SurfaceWhite,
    size: Dp = 32.dp,
    shadowOffset: Dp = 3.dp
) {
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
                    .background(SlockColor.ShadowBlack)
        )
        Box(
            modifier =
                Modifier
                    .size(size)
                    .background(if (enabled) backgroundColor else SlockColor.SurfaceCream)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            SlockLucideIcon(iconName, color = "#FF000000", modifier = Modifier.size((size.value * 0.5f).dp))
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
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height)
                .padding(end = shadowOffset, bottom = shadowOffset)
                .background(SlockColor.ShadowBlack)
    ) {
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(height)
                    .padding(end = shadowOffset, bottom = shadowOffset)
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
