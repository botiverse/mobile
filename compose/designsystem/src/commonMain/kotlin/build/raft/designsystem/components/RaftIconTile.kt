package build.raft.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftUiPalette

/** raft-ui IconTile `size`: the avatar sizes, so tiles and avatars line up in one column. */
enum class RaftIconTileSize(val edge: Dp, val icon: Dp) {
    Xs(19.dp, 11.dp),
    Sm(27.dp, 14.dp),
    Md(35.dp, 18.dp),
    Lg(51.dp, 22.dp),
}

/** raft-ui IconTile `variant`. */
enum class RaftIconTileVariant {
    /** Everything: channels, files, apps, computers. `fill-muted`, `foreground-muted` icon. */
    Default,

    /** An error or a warning in the notification center: `warning-soft`, ink icon. */
    Warning,

    /** A message in the notification center: `info-soft`, ink icon. */
    Information,
}

/**
 * raft-ui 0.8.0 IconTile: a neutral square (sticker radius, no border) holding an icon,
 * where an avatar would go. [icon] is drawn with [LocalRaftContentColor] set to the
 * tile's ink; size it to [RaftIconTileSize.icon].
 */
@Composable
fun RaftIconTile(
    modifier: Modifier = Modifier,
    variant: RaftIconTileVariant = RaftIconTileVariant.Default,
    size: RaftIconTileSize = RaftIconTileSize.Md,
    icon: @Composable () -> Unit,
) {
    val colors = RaftTheme.colors
    val (fill, ink) =
        when (variant) {
            RaftIconTileVariant.Default -> colors.fillMuted to colors.foregroundMuted
            RaftIconTileVariant.Warning -> colors.warningSoft to colors.foreground
            RaftIconTileVariant.Information -> colors.infoSoft to colors.foreground
        }
    Box(
        modifier.size(size.edge).background(fill, RoundedCornerShape(RaftRadius.Sticker)),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalRaftContentColor provides ink, content = icon)
    }
}

/**
 * The sticker tile of app update, report-success and empty states: an icon on a block of
 * colour ([fill], a palette colour such as `RaftUiPalette.selected`), sticker radius, the
 * 1.5dp `sticker-ink` edge and ink, and the die-cut edge in dark. raft-ui has no part for
 * it; it is the IconTile shape made a sticker.
 */
@Composable
fun RaftStickerTile(
    fill: Color,
    modifier: Modifier = Modifier,
    size: RaftIconTileSize = RaftIconTileSize.Md,
    icon: @Composable () -> Unit,
) {
    val colors = RaftTheme.colors
    val look =
        RaftStickerLook(
            fill = fill,
            edge = RaftUiPalette.stickerInk,
            ink = RaftUiPalette.stickerInk,
            dieCut = colors.stickerEdge,
            pressOverlay = RaftUiPalette.stickerPressed,
        )
    Box(
        modifier.size(size.edge).raftSticker(look, RaftRadius.Sticker, colors = colors),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalRaftContentColor provides look.ink, content = icon)
    }
}
