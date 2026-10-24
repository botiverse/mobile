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

@Deprecated(
    "Legacy RaftBrutal layer: use build.raft.designsystem.components.RaftBadge's variant. Add no new uses; see docs/raftbrutal-migration.md.",
    level = DeprecationLevel.WARNING,
)
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
 * Legacy-named badge; renders the design-system soft [RaftBadge] in the closest raft-ui
 * variant ([raftBadgeVariant]). [uppercase] is the retired brutal register's caps: raft-ui
 * badges keep the source case.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
@Deprecated(
    "Legacy RaftBrutal layer: use build.raft.designsystem.components.RaftBadge. Add no new uses; see docs/raftbrutal-migration.md.",
    level = DeprecationLevel.WARNING,
)
internal fun RaftBrutalBadge(
    label: String,
    modifier: Modifier = Modifier,
    tone: RaftBrutalTone = RaftBrutalTone.White,
    uppercase: Boolean = true,
    leadingIcon: RaftLucideIconName? = null
) {
    RaftBadge(
        text = label,
        modifier = modifier,
        appearance = RaftBadgeAppearance.Soft,
        variant = tone.raftBadgeVariant,
        icon = leadingIcon?.let { icon -> { RaftSlotIcon(icon) } }
    )
}

/**
 * The raft-ui badge variant of a legacy tone. raft-ui has no inverse and no lavender badge:
 * black (inverse) is the neutral `default`, lavender (the activity footer's In review task
 * badge) the cool `information`.
 */
internal val RaftBrutalTone.raftBadgeVariant: RaftBadgeVariant
    get() =
        when (this) {
            RaftBrutalTone.White,
            RaftBrutalTone.Black -> RaftBadgeVariant.Default
            RaftBrutalTone.Yellow -> RaftBadgeVariant.Primary
            RaftBrutalTone.Pink -> RaftBadgeVariant.Accent
            RaftBrutalTone.Cyan,
            RaftBrutalTone.Lavender -> RaftBadgeVariant.Information
            RaftBrutalTone.Lime -> RaftBadgeVariant.Success
            RaftBrutalTone.Orange,
            RaftBrutalTone.Warning -> RaftBadgeVariant.Warning
            RaftBrutalTone.Red -> RaftBadgeVariant.Danger
            RaftBrutalTone.Stone -> RaftBadgeVariant.Muted
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
