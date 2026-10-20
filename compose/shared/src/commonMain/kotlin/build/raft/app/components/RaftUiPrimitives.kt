package build.raft.app.components

import build.raft.app.raftDisplayUppercase
import build.raft.app.Button as RaftAppButton
import build.raft.app.RaftAppAvatar
import build.raft.app.RaftColor
import build.raft.app.RaftMonoFamily
import build.raft.app.RaftLucideIcon
import build.raft.app.RaftLucideIconName
import build.raft.app.RaftAppRadius
import build.raft.app.RaftAppStroke
import build.raft.app.raftArgbHex
import build.raft.app.RaftType
import build.raft.app.raftSquareCorners
import build.raft.app.withRaftFontWeight
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class RaftBrutalTone(val color: Color) {
    White(RaftColor.SurfaceWhite),
    Black(RaftColor.TextBlack),
    Yellow(RaftColor.BrandYellow),
    Pink(RaftColor.AccentPink),
    Cyan(RaftColor.AccentCyan),
    Lavender(RaftColor.AccentLavender),
    Orange(RaftColor.AccentOrange),
    Lime(RaftColor.AccentLime),
    Red(RaftColor.AccentRed),
    // react raft-ui badge variant "muted": bg --secondary-400 -> brutal stone
    // #C0B9B1 (== RaftColor.AccentStone), not the cream surface color.
    Stone(RaftColor.AccentStone),
    // react raft-ui badge variant "warning": bg --state-warning-base #FF6900,
    // NOT the softer AccentOrange (#F8A16F) — drift issue #773.
    Warning(RaftColor.StateWarningBase)
}

private val RaftSelectedSurfaceCyan = Color(0xFFDFF7FD)

/**
 * raft-ui 0.6.0 solid Badge fill for a brutal tone: `default` sticker paper, `primary`
 * yellow, `accent` pink, `information` cyan, `success` lime, `warning` orange, `danger`
 * red, `muted` stone (`--inactive`). Lavender has no raft variant and keeps its hue.
 */
internal val RaftBrutalTone.raftFill: Color
    get() =
        when (this) {
            RaftBrutalTone.White -> RaftColor.StickerPaper
            RaftBrutalTone.Black -> RaftColor.TextBlack
            RaftBrutalTone.Yellow -> RaftColor.Brand
            RaftBrutalTone.Pink -> RaftColor.Attention
            RaftBrutalTone.Cyan -> RaftColor.Info
            RaftBrutalTone.Lavender -> RaftColor.AccentLavender
            RaftBrutalTone.Orange -> RaftColor.Warning
            RaftBrutalTone.Lime -> RaftColor.Success
            RaftBrutalTone.Red -> RaftColor.Danger
            RaftBrutalTone.Stone -> RaftColor.Inactive
            RaftBrutalTone.Warning -> RaftColor.Warning
        }

@Composable
internal fun RaftBrutalBadge(
    label: String,
    modifier: Modifier = Modifier,
    tone: RaftBrutalTone = RaftBrutalTone.White,
    uppercase: Boolean = true,
    leadingIcon: RaftLucideIconName? = null,
    contentColor: Color? = null
) {
    RaftBrutalBadgeChrome(
        label = label,
        modifier = modifier,
        tone = tone,
        uppercase = uppercase,
        leadingIcon = leadingIcon,
        contentColor = contentColor
    )
}

@Composable
internal fun RaftBrutalBadgeButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: RaftBrutalTone = RaftBrutalTone.White,
    uppercase: Boolean = true,
    enabled: Boolean = true,
    leadingIcon: RaftLucideIconName? = null
) {
    RaftAppButton(
        onClick = onClick,
        enabled = enabled,
        pressFeedback = enabled,
        // raft stickers do not shift when pressed; brutal keeps its 2dp press offset.
        pressOffset = if (raftActive()) 0.dp else 2.dp,
        modifier = modifier
    ) {
        RaftBrutalBadgeChrome(
            label = label,
            tone = tone,
            uppercase = uppercase,
            leadingIcon = leadingIcon
        )
    }
}

@Composable
private fun RaftBrutalBadgeChrome(
    label: String,
    modifier: Modifier = Modifier,
    tone: RaftBrutalTone = RaftBrutalTone.White,
    uppercase: Boolean = true,
    leadingIcon: RaftLucideIconName? = null,
    contentColor: Color? = null
) {
    if (raftActive()) {
        RaftBadgeChrome(
            label = label,
            modifier = modifier,
            fill = tone.raftFill,
            ink = contentColor ?: if (tone == RaftBrutalTone.Black) RaftColor.ForegroundInverse else RaftColor.StickerInk,
            leadingIcon = leadingIcon
        )
        return
    }
    val foreground = contentColor ?: if (tone == RaftBrutalTone.Black) RaftColor.SurfaceWhite else RaftColor.TextBlack
    Row(
        modifier =
            modifier
                .height(20.dp)
                .widthIn(min = 0.dp)
                .border(RaftAppStroke.Thin, RaftColor.BorderBlack, RectangleShape)
                .background(tone.color, RectangleShape)
                .raftSquareCorners()
                .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leadingIcon?.let {
            RaftLucideIcon(it, modifier = Modifier.size(10.dp), color = if (tone == RaftBrutalTone.Black) "#FFFFFFFF" else "#FF000000")
        }
        Text(
            text = if (uppercase) label.raftDisplayUppercase() else label,
            style =
                RaftType.Caption
                    .copy(
                        fontSize = 10.sp,
                        // Let every native text renderer use its font metrics. A forced
                        // 10sp line box is shorter than Space Grotesk's ascent + descender
                        // on iOS and clips p/q/y at the badge's bottom edge.
                        lineHeight = TextUnit.Unspecified,
                        letterSpacing = if (uppercase) 0.6.sp else 0.sp
                    )
                    .withRaftFontWeight(FontWeight.Bold),
            color = foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * raft-ui 0.6.0 solid Badge: 20dp tall (min 20dp wide, centred), sticker radius 4dp,
 * 6dp side padding, 1.5dp sticker-ink edge, 12sp bold ink type in the source case
 * (`normal-case tracking-normal`: badges never shout), 13dp icons.
 */
@Composable
internal fun RaftBadgeChrome(
    label: String,
    modifier: Modifier = Modifier,
    fill: Color = RaftColor.StickerPaper,
    ink: Color = RaftColor.StickerInk,
    leadingIcon: RaftLucideIconName? = null
) {
    val shape = RoundedCornerShape(RaftAppRadius.Sticker)
    Box(
        modifier =
            modifier
                .height(20.dp)
                .widthIn(min = 20.dp)
                .background(fill, shape)
                .border(RaftAppStroke.Control, RaftColor.StickerInk, shape)
                .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            leadingIcon?.let {
                RaftLucideIcon(it, modifier = Modifier.size(13.dp), color = raftArgbHex(ink))
            }
            Text(
                text = label,
                style =
                    RaftType.Caption
                        .copy(
                            fontSize = 12.sp,
                            // Native font metrics (see the brutal chrome): a forced line box
                            // clips descenders on iOS.
                            lineHeight = TextUnit.Unspecified,
                            letterSpacing = 0.sp
                        )
                        .withRaftFontWeight(FontWeight.Bold),
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun RaftSectionHeader(
    label: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    action: (@Composable () -> Unit)? = null,
    iconName: RaftLucideIconName? = null
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
            val raft = raftActive()
            iconName?.let {
                RaftLucideIcon(
                    it,
                    modifier = Modifier.size(16.dp),
                    color = if (raft) raftArgbHex(RaftColor.ForegroundHint) else RaftColor.TextMutedGrayArgb
                )
            }
            RaftAppSectionEyebrow(label = label, modifier = Modifier)
            count?.takeIf { it >= 0 }?.let {
                // raft section count: Space Mono 12/16, hint ink.
                Text(
                    text = it.toString(),
                    style = RaftType.Caption.copy(fontFamily = RaftMonoFamily, fontSize = 12.sp, lineHeight = if (raft) 16.sp else 14.sp),
                    color = if (raft) RaftColor.ForegroundHint else RaftColor.TextBlack.copy(alpha = 0.4f),
                    maxLines = 1
                )
            }
        }
        action?.invoke()
    }
}

@Composable
internal fun RaftSurfaceListItem(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    interactive: Boolean = true,
    onClick: (() -> Unit)? = null,
    horizontalPadding: Dp = 16.dp,
    verticalPadding: Dp = 12.dp,
    content: @Composable () -> Unit
) {
    if (raftActive()) {
        // raft-ui ListRow on its own Card: container radius, card layer (selected =
        // `--info-soft`), 1dp hairline, no ink line and no lift.
        Box(
            modifier =
                modifier
                    .fillMaxWidth()
                    .raftCardSurface(fill = if (selected) RaftColor.InfoSoft else RaftColor.Card)
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
        return
    }
    val borderColor = if (selected) RaftColor.BorderBlack else RaftColor.BorderBlack.copy(alpha = 0.30f)
    val backgroundColor = if (selected) RaftSelectedSurfaceCyan else RaftColor.SurfaceWhite
    val shadowOffset = if (selected) 2.dp else 0.dp
    Box(modifier = modifier.fillMaxWidth().padding(end = shadowOffset, bottom = shadowOffset)) {
        if (shadowOffset > 0.dp) {
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .offset(x = shadowOffset, y = shadowOffset)
                        .background(RaftColor.ShadowBlack)
            )
        }
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(backgroundColor, RectangleShape)
                    .border(RaftAppStroke.Default, borderColor, RectangleShape)
                    .raftSquareCorners()
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
internal fun RaftAvatarListRow(
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
    RaftSurfaceListItem(
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
            RaftAppAvatar(
                avatarWireValue = avatarWireValue,
                label = avatarLabel,
                avatarSize = avatarSize,
            )
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Text(
                    text = name,
                    style = RaftType.ButtonBold.copy(fontSize = 14.sp, lineHeight = 18.sp),
                    color = RaftColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = RaftType.Caption.copy(fontFamily = RaftMonoFamily, fontSize = 12.sp, lineHeight = 14.sp),
                        color = if (raftActive()) RaftColor.ForegroundHint else RaftColor.TextBlack.copy(alpha = 0.5f),
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
