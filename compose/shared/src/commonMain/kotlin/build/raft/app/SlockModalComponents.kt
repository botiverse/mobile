package build.raft.app

import build.raft.app.components.SlockBrutalActionButton
import build.raft.app.components.SlockBrutalActionSheetRow
import build.raft.app.components.SlockBrutalIconButton
import build.raft.app.components.SlockBrutalListSectionHeader
import build.raft.app.components.SlockBrutalListShell
import build.raft.app.components.SlockBrutalModalHeader
import build.raft.app.components.SlockBrutalModalLayer
import build.raft.app.components.SlockActionSheetRowModel
import build.raft.app.components.SlockModalShellModel
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

private val SlockUnifiedDialogShadowOffset = 4.dp

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
    titleMaxLines: Int = 1,
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
                        .padding(end = SlockUnifiedDialogShadowOffset, bottom = SlockUnifiedDialogShadowOffset)
                        .widthIn(min = minWidth, max = maxWidth)
                        .clickable(enabled = false, onClick = {})
            ) {
                Box(
                    modifier =
                        Modifier
                            .matchParentSize()
                            .offset(x = SlockUnifiedDialogShadowOffset, y = SlockUnifiedDialogShadowOffset)
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
                            maxLines = titleMaxLines,
                            overflow = TextOverflow.Ellipsis
                        )
                        SlockBrutalIconButton(
                            enabled = dismissEnabled,
                            iconName = SlockLucideIconName.X,
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
    icon: SlockLucideIconName,
    onClick: () -> Unit
) {
    SlockBrutalIconButton(iconName = icon, onClick = onClick, size = 34.dp, shadowOffset = 0.dp)
}

@Composable
internal fun SlockActionSheetRow(
    title: String,
    subtitle: String = "",
    icon: SlockLucideIconName,
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
internal fun SlockModalActionButton(
    label: String,
    icon: SlockLucideIconName,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = SlockColor.SurfaceWhite,
    enabled: Boolean = true,
    iconSpinning: Boolean = icon == SlockLucideIconName.Loader || icon == SlockLucideIconName.LoaderCircle
) {
    SlockBrutalActionButton(
        label = label,
        leadingIcon = icon,
        leadingIconSpinning = iconSpinning,
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
    icon: SlockLucideIconName,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = SlockColor.SurfaceWhite,
    enabled: Boolean = true,
    iconSpinning: Boolean = icon == SlockLucideIconName.Loader || icon == SlockLucideIconName.LoaderCircle
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
                SlockLucideIcon(icon, modifier = Modifier.size(13.dp), color = "#FF000000", spinning = iconSpinning)
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
    icon: SlockLucideIconName,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = SlockColor.SurfaceWhite,
    enabled: Boolean = true,
    size: Dp = 28.dp,
    iconSpinning: Boolean = icon == SlockLucideIconName.Loader || icon == SlockLucideIconName.LoaderCircle
) {
    SlockBrutalIconButton(
        iconName = icon,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        backgroundColor = color,
        size = size,
        shadowOffset = 3.dp,
        iconSpinning = iconSpinning
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
    statusPulsing: Boolean = false,
    sourceBadgeLabel: String? = null,
    trailingText: String = "",
    trailingIcon: SlockLucideIconName? = null,
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
            statusSize = if (avatarSize >= 40.dp) 10.dp else 8.dp,
            statusPulsing = statusPulsing
        ) {
            SlockAvatar(
                avatarWireValue = avatarWireValue,
                label = avatarLabel,
                avatarSize = avatarSize
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = SlockType.Body.withSlockFontWeight(FontWeight.Medium).copy(fontSize = 14.sp, lineHeight = 18.sp),
                    color = if (enabled) SlockColor.TextBlack else SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                val badge = sourceBadgeLabel?.takeIf { it.isNotBlank() }
                if (badge != null) {
                    Box(
                        modifier =
                            Modifier
                                .height(16.dp)
                                .widthIn(max = 88.dp)
                                .background(SlockColor.AccentCyan.copy(alpha = 0.24f))
                                .border(SlockStroke.Thin, SlockColor.BorderBlack)
                                .padding(horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = badge.uppercase(),
                            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 9.sp, lineHeight = 16.sp),
                            color = SlockColor.TextBlack,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp),
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
