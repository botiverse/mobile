package build.raft.app.components

import build.raft.app.slockDisplayUppercase
import build.raft.app.Button as SlockButton
import build.raft.app.SlockAvatar
import build.raft.app.SlockColor
import build.raft.app.SlockMonoFamily
import build.raft.app.SlockLucideIcon
import build.raft.app.SlockLucideIconName
import build.raft.app.SlockStroke
import build.raft.app.SlockType
import build.raft.app.slockSquareCorners
import build.raft.app.withSlockFontWeight
import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.ExperimentalLayoutApi
import com.tencent.kuikly.compose.foundation.layout.FlowRow
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.RowScope
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.heightIn
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.widthIn
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.graphics.RectangleShape
import com.tencent.kuikly.compose.ui.semantics.Role
import com.tencent.kuikly.compose.ui.text.font.FontFamily
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.TextUnit
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp

internal enum class SlockBrutalTone(val color: Color) {
    White(SlockColor.SurfaceWhite),
    Black(SlockColor.TextBlack),
    Yellow(SlockColor.BrandYellow),
    Pink(SlockColor.AccentPink),
    Cyan(SlockColor.AccentCyan),
    Lavender(SlockColor.AccentLavender),
    Orange(SlockColor.AccentOrange),
    Lime(SlockColor.AccentLime),
    Red(SlockColor.AccentRed),
    // react raft-ui badge variant "muted": bg --secondary-400 -> brutal stone
    // #C0B9B1 (== SlockColor.AccentStone), not the cream surface color.
    Stone(SlockColor.AccentStone),
    // react raft-ui badge variant "warning": bg --state-warning-base #FF6900,
    // NOT the softer AccentOrange (#F8A16F) — drift issue #773.
    Warning(SlockColor.StateWarningBase)
}

private val SlockSelectedSurfaceCyan = Color(0xFFDFF7FD)

@Composable
internal fun SlockBrutalBadge(
    label: String,
    modifier: Modifier = Modifier,
    tone: SlockBrutalTone = SlockBrutalTone.White,
    uppercase: Boolean = true,
    leadingIcon: SlockLucideIconName? = null,
    contentColor: Color? = null
) {
    SlockBrutalBadgeChrome(
        label = label,
        modifier = modifier,
        tone = tone,
        uppercase = uppercase,
        leadingIcon = leadingIcon,
        contentColor = contentColor
    )
}

@Composable
internal fun SlockBrutalBadgeButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: SlockBrutalTone = SlockBrutalTone.White,
    uppercase: Boolean = true,
    enabled: Boolean = true,
    leadingIcon: SlockLucideIconName? = null
) {
    SlockButton(
        onClick = onClick,
        enabled = enabled,
        pressFeedback = enabled,
        modifier = modifier
    ) {
        SlockBrutalBadgeChrome(
            label = label,
            tone = tone,
            uppercase = uppercase,
            leadingIcon = leadingIcon
        )
    }
}

@Composable
private fun SlockBrutalBadgeChrome(
    label: String,
    modifier: Modifier = Modifier,
    tone: SlockBrutalTone = SlockBrutalTone.White,
    uppercase: Boolean = true,
    leadingIcon: SlockLucideIconName? = null,
    contentColor: Color? = null
) {
    val foreground = contentColor ?: if (tone == SlockBrutalTone.Black) SlockColor.SurfaceWhite else SlockColor.TextBlack
    Row(
        modifier =
            modifier
                .height(20.dp)
                .widthIn(min = 0.dp)
                .border(SlockStroke.Thin, SlockColor.BorderBlack, RectangleShape)
                .background(tone.color, RectangleShape)
                .slockSquareCorners()
                .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leadingIcon?.let {
            SlockLucideIcon(it, modifier = Modifier.size(10.dp), color = if (tone == SlockBrutalTone.Black) "#FFFFFFFF" else "#FF000000")
        }
        Text(
            text = if (uppercase) label.slockDisplayUppercase() else label,
            style =
                SlockType.Caption
                    .copy(
                        fontSize = 10.sp,
                        // Let every native text renderer use its font metrics. A forced
                        // 10sp line box is shorter than Space Grotesk's ascent + descender
                        // on iOS and clips p/q/y at the badge's bottom edge.
                        lineHeight = TextUnit.Unspecified,
                        letterSpacing = if (uppercase) 0.6.sp else 0.sp
                    )
                    .withSlockFontWeight(FontWeight.Bold),
            color = foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun SlockSectionHeader(
    label: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    action: (@Composable () -> Unit)? = null,
    iconName: SlockLucideIconName? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            iconName?.let {
                SlockLucideIcon(it, modifier = Modifier.size(16.dp), color = SlockColor.TextMutedGrayArgb)
            }
            SlockSectionEyebrow(label = label, modifier = Modifier)
            count?.takeIf { it >= 0 }?.let {
                Text(
                    text = it.toString(),
                    style = SlockType.Caption.copy(fontFamily = SlockMonoFamily, fontSize = 12.sp, lineHeight = 14.sp),
                    color = SlockColor.TextBlack.copy(alpha = 0.4f),
                    maxLines = 1
                )
            }
        }
        action?.invoke()
    }
}

@Composable
internal fun SlockSurfaceListItem(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    interactive: Boolean = true,
    onClick: (() -> Unit)? = null,
    horizontalPadding: Dp = 16.dp,
    verticalPadding: Dp = 12.dp,
    content: @Composable () -> Unit
) {
    val borderColor = if (selected) SlockColor.BorderBlack else SlockColor.BorderBlack.copy(alpha = 0.30f)
    val backgroundColor = if (selected) SlockSelectedSurfaceCyan else SlockColor.SurfaceWhite
    val shadowOffset = if (selected) 2.dp else 0.dp
    Box(modifier = modifier.fillMaxWidth().padding(end = shadowOffset, bottom = shadowOffset)) {
        if (shadowOffset > 0.dp) {
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .offset(x = shadowOffset, y = shadowOffset)
                        .background(SlockColor.ShadowBlack)
            )
        }
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(backgroundColor, RectangleShape)
                    .border(SlockStroke.Default, borderColor, RectangleShape)
                    .slockSquareCorners()
                    .let { base ->
                        if (onClick != null) {
                            base.clickable(enabled = true, role = Role.Button, onClick = onClick)
                        } else {
                            base
                        }
                    }
                    .padding(horizontal = horizontalPadding, vertical = verticalPadding)
        ) {
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SlockAvatarListRow(
    avatarWireValue: String?,
    avatarLabel: String,
    name: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    rightContent: (@Composable RowScope.() -> Unit)? = null,
    actionContent: (@Composable RowScope.() -> Unit)? = null,
    avatarSize: Dp = 32.dp
) {
    SlockSurfaceListItem(
        modifier = modifier,
        selected = selected,
        interactive = onClick != null,
        onClick = onClick,
        horizontalPadding = 12.dp,
        verticalPadding = 12.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SlockAvatar(
                avatarWireValue = avatarWireValue,
                label = avatarLabel,
                avatarSize = avatarSize,
                borderWidth = SlockStroke.Thin
            )
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Text(
                    text = name,
                    style = SlockType.ButtonBold.copy(fontSize = 14.sp, lineHeight = 18.sp),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = SlockType.Caption.copy(fontFamily = SlockMonoFamily, fontSize = 12.sp, lineHeight = 14.sp),
                        color = SlockColor.TextBlack.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            rightContent?.invoke(this)
            actionContent?.invoke(this)
        }
    }
}
