package build.raft.app.components

import build.raft.app.slockDisplayUppercase
import build.raft.app.Button as SlockButton
import build.raft.app.SlockAvatar
import build.raft.app.SlockColor
import build.raft.app.SlockMonoFamily
import build.raft.app.SlockLucideIcon
import build.raft.app.SlockLucideIconName
import build.raft.app.SlockRadius
import build.raft.app.SlockStroke
import build.raft.app.slockArgbHex
import build.raft.app.SlockType
import build.raft.app.slockSquareCorners
import build.raft.app.withSlockFontWeight
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

/**
 * raft-ui 0.6.0 solid Badge fill for a brutal tone: `default` sticker paper, `primary`
 * yellow, `accent` pink, `information` cyan, `success` lime, `warning` orange, `danger`
 * red, `muted` stone (`--inactive`). Lavender has no raft variant and keeps its hue.
 */
internal val SlockBrutalTone.raftFill: Color
    get() =
        when (this) {
            SlockBrutalTone.White -> SlockColor.StickerPaper
            SlockBrutalTone.Black -> SlockColor.TextBlack
            SlockBrutalTone.Yellow -> SlockColor.Brand
            SlockBrutalTone.Pink -> SlockColor.Attention
            SlockBrutalTone.Cyan -> SlockColor.Info
            SlockBrutalTone.Lavender -> SlockColor.AccentLavender
            SlockBrutalTone.Orange -> SlockColor.Warning
            SlockBrutalTone.Lime -> SlockColor.Success
            SlockBrutalTone.Red -> SlockColor.Danger
            SlockBrutalTone.Stone -> SlockColor.Inactive
            SlockBrutalTone.Warning -> SlockColor.Warning
        }

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
        // raft stickers do not shift when pressed; brutal keeps its 2dp press offset.
        pressOffset = if (slockRaftActive()) 0.dp else 2.dp,
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
    if (slockRaftActive()) {
        SlockRaftBadgeChrome(
            label = label,
            modifier = modifier,
            fill = tone.raftFill,
            ink = contentColor ?: if (tone == SlockBrutalTone.Black) SlockColor.ForegroundInverse else SlockColor.StickerInk,
            leadingIcon = leadingIcon
        )
        return
    }
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

/**
 * raft-ui 0.6.0 solid Badge: 20dp tall (min 20dp wide, centred), sticker radius 4dp,
 * 6dp side padding, 1.5dp sticker-ink edge, 12sp bold ink type in the source case
 * (`normal-case tracking-normal`: badges never shout), 13dp icons.
 */
@Composable
internal fun SlockRaftBadgeChrome(
    label: String,
    modifier: Modifier = Modifier,
    fill: Color = SlockColor.StickerPaper,
    ink: Color = SlockColor.StickerInk,
    leadingIcon: SlockLucideIconName? = null
) {
    val shape = RoundedCornerShape(SlockRadius.Sticker)
    Box(
        modifier =
            modifier
                .height(20.dp)
                .widthIn(min = 20.dp)
                .background(fill, shape)
                .border(SlockStroke.Control, SlockColor.StickerInk, shape)
                .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            leadingIcon?.let {
                SlockLucideIcon(it, modifier = Modifier.size(13.dp), color = slockArgbHex(ink))
            }
            Text(
                text = label,
                style =
                    SlockType.Caption
                        .copy(
                            fontSize = 12.sp,
                            // Native font metrics (see the brutal chrome): a forced line box
                            // clips descenders on iOS.
                            lineHeight = TextUnit.Unspecified,
                            letterSpacing = 0.sp
                        )
                        .withSlockFontWeight(FontWeight.Bold),
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
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
            val raft = slockRaftActive()
            iconName?.let {
                SlockLucideIcon(
                    it,
                    modifier = Modifier.size(16.dp),
                    color = if (raft) slockArgbHex(SlockColor.ForegroundHint) else SlockColor.TextMutedGrayArgb
                )
            }
            SlockSectionEyebrow(label = label, modifier = Modifier)
            count?.takeIf { it >= 0 }?.let {
                // raft section count: Space Mono 12/16, hint ink.
                Text(
                    text = it.toString(),
                    style = SlockType.Caption.copy(fontFamily = SlockMonoFamily, fontSize = 12.sp, lineHeight = if (raft) 16.sp else 14.sp),
                    color = if (raft) SlockColor.ForegroundHint else SlockColor.TextBlack.copy(alpha = 0.4f),
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
    if (slockRaftActive()) {
        // raft-ui ListRow on its own Card: container radius, card layer (selected =
        // `--info-soft`), 1dp hairline, no ink line and no lift.
        Box(
            modifier =
                modifier
                    .fillMaxWidth()
                    .slockRaftCardSurface(fill = if (selected) SlockColor.InfoSoft else SlockColor.Card)
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
                        color = if (slockRaftActive()) SlockColor.ForegroundHint else SlockColor.TextBlack.copy(alpha = 0.5f),
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
