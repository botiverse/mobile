package build.raft.app.components

import build.raft.app.RaftColor
import build.raft.designsystem.components.RaftSpinner
import build.raft.designsystem.components.RaftSpinnerSize
import build.raft.app.RaftLucideIcon
import build.raft.app.RaftMonoFamily
import build.raft.app.RaftLucideIconName
import build.raft.app.RaftAppRadius
import build.raft.app.RaftStatusIndicator
import build.raft.app.RaftStatusIndicatorShape
import build.raft.app.raftArgbHex
import build.raft.app.RaftAppStroke
import build.raft.app.RaftType
import androidx.compose.ui.text.font.FontWeight
import build.raft.app.withRaftFontWeight
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class RaftCheckMarkerShape {
    Square,
    Circle
}

internal enum class RaftCheckMarkerTone(val background: Color, val foregroundArgb: String) {
    BlackFill(RaftColor.TextBlack, "#FFFFFFFF"),
    YellowFill(RaftColor.BrandYellow, "#FF000000")
}

internal enum class RaftAppSkeletonVariant {
    Line,
    Block,
    Circle
}

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun RaftCheckMarker(
    checked: Boolean,
    modifier: Modifier = Modifier,
    shape: RaftCheckMarkerShape = RaftCheckMarkerShape.Square,
    size: Dp = 14.dp,
    tone: RaftCheckMarkerTone = RaftCheckMarkerTone.BlackFill,
    disabled: Boolean = false
) {
    RaftLookCheckMarker(checked = checked, modifier = modifier, shape = shape, size = size, disabled = disabled)
}

/**
 * raft-ui 0.6.0 checkbox / radio indicator at the caller's size. Square = checkbox
 * (sticker radius, `--foreground` fill + inverse check when checked); circle = radio
 * (card fill, 1.5dp `--line-strong` ring, 8dp `--foreground` dot when checked; raft
 * radios never take the yellow fill). Disabled = `--foreground-disabled` on `--fill-muted`.
 */
@Composable
private fun RaftLookCheckMarker(
    checked: Boolean,
    modifier: Modifier,
    shape: RaftCheckMarkerShape,
    size: Dp,
    disabled: Boolean
) {
    val circle = shape == RaftCheckMarkerShape.Circle
    val boxShape = RoundedCornerShape(if (circle) size / 2 else RaftAppRadius.Sticker)
    val fill =
        when {
            !circle && checked && !disabled -> RaftColor.TextBlack
            !circle && checked -> RaftColor.ForegroundDisabled
            disabled -> RaftColor.FillMuted
            else -> RaftColor.Card
        }
    val edge =
        when {
            disabled -> RaftColor.ForegroundDisabled
            checked && !circle -> RaftColor.TextBlack
            else -> RaftColor.BorderBlack
        }
    Box(
        modifier =
            modifier
                .size(size)
                .background(fill, boxShape)
                .border(RaftAppStroke.Control, edge, boxShape),
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            if (circle) {
                val dot = (size.value * 0.4f).dp
                Box(
                    modifier =
                        Modifier
                            .size(dot)
                            .background(
                                if (disabled) RaftColor.ForegroundDisabled else RaftColor.TextBlack,
                                RoundedCornerShape(dot / 2)
                            )
                )
            } else {
                RaftLucideIcon(
                    RaftLucideIconName.Check,
                    modifier = Modifier.size((size.value * 0.7f).dp),
                    color = raftArgbHex(if (disabled) RaftColor.Card else RaftColor.ForegroundInverse),
                    strokeWidth = 2f
                )
            }
        }
    }
}

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun RaftAppSkeleton(
    modifier: Modifier = Modifier,
    variant: RaftAppSkeletonVariant = RaftAppSkeletonVariant.Block
) {
    // raft-ui Skeleton: `--fill-muted` blocks with the sticker radius; the "circle"
    // variant is a rounded square too (raft avatars are square).
    Box(modifier = modifier.background(RaftColor.FillMuted, RoundedCornerShape(RaftAppRadius.Sticker)))
}

@Composable
internal fun RaftSkeletonRow(
    modifier: Modifier = Modifier,
    avatar: Boolean = false,
    avatarSize: Dp = 18.dp,
    lineWidths: List<Dp> = listOf(156.dp, 96.dp)
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (avatar) {
            RaftAppSkeleton(modifier = Modifier.size(avatarSize), variant = RaftAppSkeletonVariant.Circle)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            lineWidths.forEach { width ->
                RaftAppSkeleton(modifier = Modifier.width(width).height(12.dp), variant = RaftAppSkeletonVariant.Line)
            }
        }
    }
}
