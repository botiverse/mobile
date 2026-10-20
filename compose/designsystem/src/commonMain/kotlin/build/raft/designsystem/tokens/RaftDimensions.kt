package build.raft.designsystem.tokens

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Non-colour raft-ui tokens (styles.css, unchanged 0.7.1 -> 0.8.0), ported from compose/shared RaftDesignTokens.kt
// (already aligned to raft-ui 0.6/0.7). CSS px map 1:1 to dp. Colours are generated:
// see RaftUiColorTokens.g.kt and tools/design-tokens.

/** raft-ui `--radius-*` (styles.css): pixels are hard, containers are soft. */
object RaftRadius {
    val None = 0.dp

    /** Spinner pixels (`rounded-[1px]`). */
    val Pixel = 1.dp
    val Xs = 2.dp

    /** Avatars, channel tiles, checkboxes, counts, inline marks. */
    val Sticker = 4.dp

    /** 22px tags and status chips. */
    val Chip = 6.dp

    /** Buttons, fields, rows, menu items. */
    val Control = 8.dp

    /** Cards, menus, composer, banners. */
    val Container = 10.dp

    /** Dialogs and sheet tops. */
    val Dialog = 16.dp
}

/**
 * raft-ui stroke widths (raft-design Shape): 1px for lines (`line-hairline`, `line-seam`;
 * they differ by colour), 1.5px for `line-strong`, the ink edge of controls and stickers.
 * [Strong] is not a line width: it is the 2px edge of the presence light on a 99px avatar
 * and the 2px quote bar.
 */
object RaftStroke {
    val Hairline = 1.dp
    val Control = 1.5.dp
    val Strong = 2.dp
}

object RaftSpacing {
    val Xxs = 2.dp
    val Xs = 4.dp
    val Sm = 8.dp
    val Md = 16.dp
    val Lg = 24.dp
    val Xl = 32.dp
}

object RaftSize {
    val MinTouchTarget = 48.dp
    val IconButton = 44.dp
    val HeaderIcon = 36.dp

    /** raft panel heads are 56px (48px under 600px window height). */
    val PageHeaderHeight = 56.dp
    val PageHeaderCompactHeight = 48.dp
    val BottomBarHeight = 53.dp

    /** Presence / attention pixel. */
    val StatusPixel = 9.dp
}

/** One CSS `box-shadow` layer: `x y blur spread color`. */
@Immutable
data class RaftShadowLayer(
    val offsetX: Dp,
    val offsetY: Dp,
    val blur: Dp,
    val spread: Dp,
    val color: Color,
)

/**
 * raft-ui elevation (foundation.css): controls never cast a shadow; stickers keep one
 * hard 4px offset and only overlays float.
 */
object RaftElevation {
    /** `--elevation-sticker`: `4px 4px 0` in `--ink` (light) / black (dark). */
    fun sticker(dark: Boolean): RaftShadowLayer =
        RaftShadowLayer(
            offsetX = 4.dp,
            offsetY = 4.dp,
            blur = 0.dp,
            spread = 0.dp,
            color = if (dark) RaftUiPalette.black else RaftUiThemeColors.RaftLight.ink,
        )

    /**
     * `--elevation-float`, bottom layer first as CSS paints it.
     * Light: `0 1px 0 ink/0.039, 0 12px 32px -12px ink/0.322`.
     * Dark: `0 0 0 1px ink/0.078, 0 16px 40px -12px black/0.8`.
     */
    fun float(dark: Boolean): List<RaftShadowLayer> =
        if (dark) {
            val ink = RaftUiThemeColors.RaftDark.ink
            listOf(
                RaftShadowLayer(0.dp, 0.dp, 0.dp, 1.dp, ink.copy(alpha = 0.078f)),
                RaftShadowLayer(0.dp, 16.dp, 40.dp, (-12).dp, RaftUiPalette.black.copy(alpha = 0.8f)),
            )
        } else {
            val ink = RaftUiThemeColors.RaftLight.ink
            listOf(
                RaftShadowLayer(0.dp, 1.dp, 0.dp, 0.dp, ink.copy(alpha = 0.039f)),
                RaftShadowLayer(0.dp, 12.dp, 32.dp, (-12).dp, ink.copy(alpha = 0.322f)),
            )
        }
}
