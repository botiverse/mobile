package ai.slock.compose

import ai.slock.compose.components.SlockBrutalActionButton
import ai.slock.compose.components.SlockBrutalActionSheetRow
import ai.slock.compose.components.SlockBrutalIconButton
import ai.slock.compose.components.SlockBrutalListSectionHeader
import ai.slock.compose.components.SlockBrutalListShell
import ai.slock.compose.components.SlockBrutalModalHeader
import ai.slock.compose.components.SlockBrutalModalLayer
import ai.slock.compose.components.SlockActionSheetRowModel
import ai.slock.compose.components.SlockModalFieldLabelComponent
import ai.slock.compose.components.SlockModalShellModel
import ai.slock.compose.components.SlockModalTextFieldComponent
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
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.heightIn
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.widthIn
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.foundation.lazy.LazyListScope
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp

@Composable
internal fun SlockModalLayer(
    title: String,
    subtitle: String = "",
    onDismiss: () -> Unit,
    overlayAlpha: Float = 0.58f,
    maxWidth: Dp = 384.dp,
    content: @Composable () -> Unit
) {
    SlockBrutalModalLayer(
        model =
            SlockModalShellModel(
                title = title,
                subtitle = subtitle,
                overlayAlpha = overlayAlpha,
                maxWidth = maxWidth
            ),
        onDismiss = onDismiss,
        content = content
    )
}

@Composable
internal fun SlockUnifiedDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissEnabled: Boolean = true,
    overlayAlpha: Float = 0.36f,
    minWidth: Dp = 312.dp,
    maxWidth: Dp = 408.dp,
    outerPadding: Dp = SlockSpacing.Md,
    contentPadding: Dp = 20.dp,
    titleFontSize: Int = 22,
    titleLineHeight: Int = 26,
    contentSpacing: Dp = 16.dp,
    content: @Composable () -> Unit
) {
    Modal(onWillDismiss = { if (dismissEnabled) onDismiss() }) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(SlockColor.ShadowBlack.copy(alpha = overlayAlpha))
                    .clickable(enabled = dismissEnabled, onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier =
                    modifier
                        .padding(outerPadding)
                        .widthIn(min = minWidth, max = maxWidth)
                        .clickable(enabled = false, onClick = {})
            ) {
                Box(
                    modifier =
                        Modifier
                            .matchParentSize()
                            .offset(x = 4.dp, y = 4.dp)
                            .background(SlockColor.ShadowBlack)
                )
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(SlockColor.SurfaceWhite)
                            .border(SlockStroke.Default, SlockColor.BorderBlack)
                            .padding(contentPadding),
                    verticalArrangement = Arrangement.spacedBy(contentSpacing)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = title,
                            style =
                                SlockType.PageTitle.copy(
                                    fontSize = titleFontSize.sp,
                                    lineHeight = titleLineHeight.sp,
                                    color = SlockColor.TextBlack
                                ),
                            color = SlockColor.TextBlack,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        SlockBrutalIconButton(
                            enabled = dismissEnabled,
                            iconName = "x",
                            onClick = onDismiss,
                            size = 34.dp,
                            shadowOffset = 3.dp
                        )
                    }
                    content()
                }
            }
        }
    }
}

@Composable
private fun SlockModalHeader(
    title: String,
    subtitle: String,
    onDismiss: () -> Unit
) {
    SlockBrutalModalHeader(
        model = SlockModalShellModel(title = title, subtitle = subtitle),
        onDismiss = onDismiss
    )
}

@Composable
internal fun SlockModalIconButton(
    icon: String,
    onClick: () -> Unit
) {
    SlockBrutalIconButton(iconName = icon, onClick = onClick, size = 34.dp, shadowOffset = 0.dp)
}

@Composable
internal fun SlockActionSheetRow(
    title: String,
    subtitle: String = "",
    icon: String,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    SlockBrutalActionSheetRow(
        model =
            SlockActionSheetRowModel(
                title = title,
                subtitle = subtitle,
                icon = icon,
                destructive = destructive
            ),
        onClick = onClick
    )
}

@Composable
internal fun SlockModalFieldLabel(label: String, required: Boolean = false) {
    SlockModalFieldLabelComponent(label = label, required = required)
}

@Composable
internal fun SlockModalTextField(
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
    SlockModalTextFieldComponent(
        label = label,
        value = value,
        placeholder = placeholder,
        onValueChange = onValueChange,
        modifier = modifier,
        required = required,
        isError = isError,
        leadingIcon = leadingIcon,
        enabled = enabled,
        fieldHeight = fieldHeight,
        maxLines = maxLines,
        errorText = errorText
    )
}

@Composable
internal fun SlockModalActionButton(
    label: String,
    icon: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = SlockColor.SurfaceWhite,
    enabled: Boolean = true
) {
    SlockBrutalActionButton(
        label = label,
        leadingIcon = icon,
        onClick = onClick,
        modifier = modifier,
        backgroundColor = color,
        enabled = enabled,
        textStyle = SlockType.ButtonBold
    )
}

@Composable
internal fun SlockModalFullWidthActionButton(
    label: String,
    icon: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = SlockColor.SurfaceWhite,
    enabled: Boolean = true
) {
    val shadowOffset = 3.dp
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(40.dp + shadowOffset)
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .offset(x = shadowOffset, y = shadowOffset)
                    .background(SlockColor.ShadowBlack)
        )
        Button(
            enabled = enabled,
            onClick = onClick,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(if (enabled) color else SlockColor.SurfaceCream)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .padding(horizontal = 14.dp, vertical = 9.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SlockLucideIcon(icon, modifier = Modifier.size(13.dp), color = "#FF000000")
                Text(
                    text = label,
                    style = SlockType.Button.copy(fontSize = 14.sp, lineHeight = 18.sp, color = SlockColor.TextBlack),
                    color = if (enabled) SlockColor.TextBlack else SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
internal fun SlockModalSquareActionButton(
    icon: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = SlockColor.SurfaceWhite,
    enabled: Boolean = true,
    size: Dp = 28.dp
) {
    SlockBrutalIconButton(
        iconName = icon,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        backgroundColor = color,
        size = size,
        shadowOffset = 3.dp
    )
}

@Composable
internal fun SlockModalListShell(
    height: Dp,
    content: LazyListScope.() -> Unit
) {
    SlockBrutalListShell(height = height, content = content)
}

@Composable
internal fun SlockModalSectionHeader(label: String) {
    SlockBrutalListSectionHeader(label = label)
}

@Composable
internal fun SlockModalCompactMemberRow(
    title: String,
    subtitle: String,
    avatarWireValue: String?,
    avatarLabel: String,
    statusColor: Color?,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    avatarSize: Dp = 30.dp,
    trailingText: String = "",
    trailingIcon: String? = null,
    onTrailingClick: (() -> Unit)? = null,
    onClick: () -> Unit = {}
) {
    val rowBackground =
        when {
            !enabled -> SlockColor.SurfaceCream
            selected -> SlockColor.BrandYellow
            else -> SlockColor.SurfaceWhite
        }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(rowBackground)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SlockAvatarWithStatus(
            statusColor = statusColor,
            avatarSize = avatarSize,
            statusSize = if (avatarSize >= 40.dp) 10.dp else 8.dp
        ) {
            SlockAvatar(
                avatarWireValue = avatarWireValue,
                label = avatarLabel,
                avatarSize = avatarSize
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = SlockType.Body.withSlockFontWeight(FontWeight.Medium).copy(fontSize = 14.sp, lineHeight = 18.sp),
                color = if (enabled) SlockColor.TextBlack else SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 15.sp),
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        when {
            trailingIcon != null && onTrailingClick != null -> {
                SlockModalSquareActionButton(
                    icon = trailingIcon,
                    onClick = onTrailingClick,
                    color = if (selected) SlockColor.SurfaceWhite else Color(0xFFFFF2F2),
                    enabled = enabled
                )
            }
            trailingIcon != null -> SlockLucideIcon(trailingIcon, modifier = Modifier.size(15.dp), color = "#FF000000")
            trailingText.isNotBlank() -> Text(text = trailingText, style = SlockType.Caption, color = SlockColor.TextMutedGray)
            else -> Spacer(modifier = Modifier.size(0.dp))
        }
    }
}
