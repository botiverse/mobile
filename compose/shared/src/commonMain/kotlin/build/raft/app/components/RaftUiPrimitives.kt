package build.raft.app.components

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
import build.raft.app.withRaftFontWeight
import androidx.compose.runtime.Composable
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
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
    // raft-ui labels are soft badges; black (inverse), lavender (no raft variant) and a
    // caller-set ink keep the raft-look chrome.
    val variant = tone.raftBadgeVariant
    if (variant == null || contentColor != null) {
        RaftBrutalBadgeChrome(
            label = label,
            modifier = modifier,
            tone = tone,
            uppercase = uppercase,
            leadingIcon = leadingIcon,
            contentColor = contentColor
        )
        return
    }
    RaftBadge(
        text = label,
        modifier = modifier,
        appearance = RaftBadgeAppearance.Soft,
        variant = variant,
        icon = leadingIcon?.let { icon -> { RaftSlotIcon(icon) } }
    )
}

/** The raft-ui badge variant of a legacy tone; null where raft-ui has none (black, lavender). */
internal val RaftBrutalTone.raftBadgeVariant: RaftBadgeVariant?
    get() =
        when (this) {
            RaftBrutalTone.White -> RaftBadgeVariant.Default
            RaftBrutalTone.Yellow -> RaftBadgeVariant.Primary
            RaftBrutalTone.Pink -> RaftBadgeVariant.Accent
            RaftBrutalTone.Cyan -> RaftBadgeVariant.Information
            RaftBrutalTone.Lime -> RaftBadgeVariant.Success
            RaftBrutalTone.Orange,
            RaftBrutalTone.Warning -> RaftBadgeVariant.Warning
            RaftBrutalTone.Red -> RaftBadgeVariant.Danger
            RaftBrutalTone.Stone -> RaftBadgeVariant.Muted
            RaftBrutalTone.Black,
            RaftBrutalTone.Lavender -> null
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
        // raft badges do not shift when pressed.
        pressOffset = 0.dp,
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

// [uppercase] is the retired brutal register's caps; raft-ui badges keep the source case.
@Suppress("UNUSED_PARAMETER")
@Composable
private fun RaftBrutalBadgeChrome(
    label: String,
    modifier: Modifier = Modifier,
    tone: RaftBrutalTone = RaftBrutalTone.White,
    uppercase: Boolean = true,
    leadingIcon: RaftLucideIconName? = null,
    contentColor: Color? = null
) {
    if (tone == RaftBrutalTone.Black) {
        RaftBadgeChrome(
            label = label,
            modifier = modifier,
            fill = tone.raftFill,
            ink = contentColor ?: RaftColor.ForegroundInverse,
            leadingIcon = leadingIcon
        )
    } else {
        RaftSoftBadgeChrome(
            label = label,
            modifier = modifier,
            fill = tone.raftSoftFill,
            ink = contentColor ?: RaftColor.TextBlack,
            leadingIcon = leadingIcon
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
                // min, not fixed: the label grows with the system font size.
                .heightIn(min = 20.dp)
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

/**
 * raft-ui Badge `appearance="soft"` wash for a tone: the label badge (status, kind, tag).
 * Web labels are soft, not stickers: only a count or a waiting mark is a solid sticker.
 */
internal val RaftBrutalTone.raftSoftFill: Color
    get() =
        when (this) {
            RaftBrutalTone.White,
            RaftBrutalTone.Black -> RaftColor.FillMuted
            RaftBrutalTone.Yellow -> RaftColor.BrandSoft
            RaftBrutalTone.Pink -> RaftColor.AttentionSoft
            RaftBrutalTone.Cyan -> RaftColor.InfoSoft
            // No raft role for lavender: its hue at the soft washes' strength.
            RaftBrutalTone.Lavender -> RaftColor.AccentLavender.copy(alpha = 0.28f)
            RaftBrutalTone.Orange,
            RaftBrutalTone.Warning -> RaftColor.WarningSoft
            RaftBrutalTone.Lime -> RaftColor.SuccessSoft
            RaftBrutalTone.Red -> RaftColor.DangerSoft
            RaftBrutalTone.Stone -> RaftColor.InactiveSoft
        }

/**
 * raft-ui soft Badge: 22dp tall, chip radius 6dp, 6dp side padding, the tone's `-soft`
 * wash with no edge, 12sp semibold foreground type in the source case, 14dp icons.
 */
@Composable
internal fun RaftSoftBadgeChrome(
    label: String,
    modifier: Modifier = Modifier,
    fill: Color = RaftColor.FillMuted,
    ink: Color = RaftColor.TextBlack,
    leadingIcon: RaftLucideIconName? = null
) {
    val shape = RoundedCornerShape(RaftAppRadius.Chip)
    Row(
        modifier =
            modifier
                .height(22.dp)
                .background(fill, shape)
                .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leadingIcon?.let {
            RaftLucideIcon(it, modifier = Modifier.size(14.dp), color = raftArgbHex(ink))
        }
        Text(
            text = label,
            style =
                RaftType.Caption
                    .copy(fontSize = 12.sp, lineHeight = TextUnit.Unspecified, letterSpacing = 0.sp)
                    .withRaftFontWeight(FontWeight.SemiBold),
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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
            iconName?.let {
                RaftLucideIcon(
                    it,
                    modifier = Modifier.size(16.dp),
                    color = raftArgbHex(RaftColor.ForegroundHint)
                )
            }
            RaftAppSectionEyebrow(label = label, modifier = Modifier)
            count?.takeIf { it >= 0 }?.let {
                // raft section count: Space Mono 12/16, hint ink.
                Text(
                    text = it.toString(),
                    style = RaftType.Caption.copy(fontFamily = RaftMonoFamily, fontSize = 12.sp, lineHeight = 16.sp),
                    color = RaftColor.ForegroundHint,
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
                        color = RaftColor.ForegroundHint,
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
