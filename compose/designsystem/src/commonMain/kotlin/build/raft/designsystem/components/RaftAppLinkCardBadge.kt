package build.raft.designsystem.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftStroke

// raft-ui 0.95.4 / 0.99.0 AppLinkCardBadge (packages/ui/src/components/app-link-card, lib/fill-contrast.ts): the
// item's state on an app link's card or hover card, a soft Badge of its variant, with the app's own colour for it.

/** The surface an [RaftAppLinkCardBadge] lies on, which its ring is decided against (raft-ui `surface`). */
enum class RaftAppLinkCardBadgeSurface {
    /** The card in a message ([RaftAppLinkCard]): `layer-card`. */
    Card,

    /** The hover card an app link opens ([RaftPreviewCard]): `layer-popover`. */
    Popover,
}

/** raft-ui `FILL_RING_CONTRAST`: below this contrast with the surface, a fill gets a hairline ring. */
const val RAFT_FILL_RING_CONTRAST = 1.5f

/** The type and the ring for an app's fill on a surface (raft-ui `fillOnSurface`). */
@Immutable
data class RaftFillOnSurface(val whiteText: Boolean, val ring: Boolean)

/** `#RRGGBB` (either case) as an opaque colour; anything else (`#RGB`, a name, alpha) is null (raft-ui `parseHexColor`). */
fun raftParseHexColor(value: String?): Color? {
    if (value == null || value.length != 7 || value[0] != '#') return null
    val hex = value.substring(1)
    if (!hex.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) return null
    return Color(0xFF000000.toInt() or hex.toInt(16))
}

/** WCAG 2 contrast ratio of two opaque colours, 1 to 21, in either order. */
fun raftContrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/**
 * raft-ui `fillOnSurface`: black or white type on [fill], whichever has the higher contrast (one of them always
 * reaches 4.58:1), and a ring where [fill]'s contrast with [surface] is below [RAFT_FILL_RING_CONTRAST].
 */
fun raftFillOnSurface(fill: Color, surface: Color): RaftFillOnSurface {
    val luminance = fill.luminance()
    val onBlack = (luminance + 0.05f) / 0.05f
    val onWhite = 1.05f / (luminance + 0.05f)
    return RaftFillOnSurface(whiteText = onWhite > onBlack, ring = raftContrastRatio(fill, surface) < RAFT_FILL_RING_CONTRAST)
}

/**
 * raft-ui `AppLinkCardBadge`: the item's state in the app's words, a soft [RaftBadge] of [variant]. [fill] is the
 * app's colour for it, `#RRGGBB`: only the fill changes; the type is black or white, whichever reads better on it,
 * the fill is the same in both modes, and a hairline ring (`line-hairline`) parts it from [surface] where the two
 * are closer than 1.5:1 (a pale yellow on a white card, a near-black on a dark one). Without a valid [fill] it is
 * the plain soft badge.
 */
@Composable
fun RaftAppLinkCardBadge(
    text: String,
    modifier: Modifier = Modifier,
    variant: RaftBadgeVariant = RaftBadgeVariant.Default,
    fill: String? = null,
    surface: RaftAppLinkCardBadgeSurface = RaftAppLinkCardBadgeSurface.Card,
) {
    val colors = RaftTheme.colors
    val soft = raftBadgeLook(RaftBadgeAppearance.Soft, variant, enabled = true, colors = colors)
    val appFill = raftParseHexColor(fill)
    val look =
        if (appFill == null) {
            soft
        } else {
            val on =
                raftFillOnSurface(
                    appFill,
                    when (surface) {
                        RaftAppLinkCardBadgeSurface.Card -> colors.layerCard
                        RaftAppLinkCardBadgeSurface.Popover -> colors.layerPopover
                    },
                )
            soft.copy(
                fill = appFill,
                ink = if (on.whiteText) Color.White else Color.Black,
                edge = if (on.ring) colors.lineHairline else Color.Transparent,
                edgeWidth = if (on.ring) RaftStroke.Hairline else soft.edgeWidth,
            )
        }
    RaftBadgeFrame(text = text, look = look, appearance = RaftBadgeAppearance.Soft, modifier = modifier)
}
