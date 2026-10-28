package build.raft.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.tokens.RaftUiPalette
import build.raft.designsystem.tokens.RaftUiThemeColors

/** raft-ui Badge `appearance`. */
enum class RaftBadgeAppearance {
    /** A sticker: a count or a mark that waits for you, with an ink edge. */
    Solid,

    /** A soft fill: a highlight, with no edge. */
    Soft,

    /** A hairline label in machine type. */
    Outline,
}

/** raft-ui Badge `variant`: the colour role; each appearance colours it differently. */
enum class RaftBadgeVariant {
    Default,

    /** `brand`: follows the theme (Boring Gray grays it). Not for a status; use [Busy]. */
    Primary,

    /**
     * raft-ui 0.39 `busy`: the yellow of a working agent, a status, so it stays yellow in every
     * theme. Soft, the Working chip's `presence-busy-soft` tint; solid, a `presence-busy`
     * sticker. An outline Badge has no `busy` look (it reads as `default`).
     */
    Busy,
    Information,
    Muted,
    Accent,
    Success,
    Warning,
    Danger,
}

/** Resolved look of one appearance x variant x enabled cell (raft-ui badge.tsx). */
internal data class RaftBadgeLook(
    val height: Dp,
    val minWidth: Dp,
    val radius: Dp,
    val fill: Color,
    val ink: Color,
    /** `inset-ring`: drawn inside the box, over the fill. */
    val edge: Color,
    val edgeWidth: Dp,
    /** `ring-[1.5px] ring-sticker-edge`: outside the box (solid only; transparent in light). */
    val outerRing: Color,
    val mono: Boolean,
    val weight: FontWeight,
    val iconSize: Dp,
)

internal fun raftBadgeLook(
    appearance: RaftBadgeAppearance,
    variant: RaftBadgeVariant,
    enabled: Boolean,
    colors: RaftUiThemeColors,
): RaftBadgeLook {
    val look =
        when (appearance) {
            RaftBadgeAppearance.Solid ->
                RaftBadgeLook(
                    height = 20.dp,
                    minWidth = 20.dp,
                    radius = RaftRadius.Sticker,
                    fill =
                        when (variant) {
                            RaftBadgeVariant.Default -> RaftUiPalette.stickerPaper
                            RaftBadgeVariant.Muted -> RaftUiPalette.inactive
                            RaftBadgeVariant.Primary -> colors.brand // follows the theme (Boring Gray grays it)
                            RaftBadgeVariant.Busy -> RaftUiPalette.presenceBusy
                            RaftBadgeVariant.Information -> RaftUiPalette.info
                            RaftBadgeVariant.Accent -> RaftUiPalette.attention
                            RaftBadgeVariant.Success -> RaftUiPalette.success
                            RaftBadgeVariant.Warning -> RaftUiPalette.warning
                            RaftBadgeVariant.Danger -> RaftUiPalette.danger
                        },
                    ink = RaftUiPalette.stickerInk,
                    edge = RaftUiPalette.stickerInk,
                    edgeWidth = RaftStroke.Control,
                    outerRing = colors.stickerEdge,
                    // `accent` is the unread count: `font-mono text-mono-meta`, still bold.
                    mono = variant == RaftBadgeVariant.Accent,
                    weight = FontWeight.Bold,
                    iconSize = 13.dp,
                )
            RaftBadgeAppearance.Soft ->
                RaftBadgeLook(
                    height = 22.dp,
                    minWidth = 0.dp,
                    radius = RaftRadius.Chip,
                    fill =
                        when (variant) {
                            RaftBadgeVariant.Default -> colors.fillMuted
                            RaftBadgeVariant.Muted -> colors.inactiveSoft
                            RaftBadgeVariant.Primary -> colors.brandSoft
                            RaftBadgeVariant.Busy -> colors.presenceBusySoft
                            RaftBadgeVariant.Information -> colors.infoSoft
                            RaftBadgeVariant.Accent -> colors.attentionSoft
                            RaftBadgeVariant.Success -> colors.successSoft
                            RaftBadgeVariant.Warning -> colors.warningSoft
                            RaftBadgeVariant.Danger -> colors.dangerSoft
                        },
                    ink = colors.foreground,
                    edge = Color.Transparent,
                    edgeWidth = 0.dp,
                    outerRing = Color.Transparent,
                    mono = false,
                    weight = FontWeight.SemiBold,
                    iconSize = 14.dp,
                )
            RaftBadgeAppearance.Outline ->
                RaftBadgeLook(
                    height = 22.dp,
                    minWidth = 0.dp,
                    radius = RaftRadius.Chip,
                    fill = Color.Transparent,
                    ink =
                        when (variant) {
                            RaftBadgeVariant.Default,
                            RaftBadgeVariant.Busy,
                            RaftBadgeVariant.Muted -> colors.foregroundMuted
                            RaftBadgeVariant.Primary -> colors.brandStrong
                            RaftBadgeVariant.Information -> colors.infoStrong
                            RaftBadgeVariant.Accent -> colors.attentionStrong
                            RaftBadgeVariant.Success -> colors.successStrong
                            RaftBadgeVariant.Warning -> colors.warningStrong
                            RaftBadgeVariant.Danger -> colors.dangerStrong
                        },
                    edge = colors.lineHairline,
                    edgeWidth = RaftStroke.Hairline,
                    outerRing = Color.Transparent,
                    mono = true,
                    weight = FontWeight.Normal,
                    iconSize = 14.dp,
                )
        }
    // `disabled:bg-fill-muted disabled:text-foreground-disabled disabled:inset-ring-0`.
    return if (enabled) look else look.copy(fill = colors.fillMuted, ink = colors.foregroundDisabled, edgeWidth = 0.dp)
}

/**
 * raft-ui 0.8.0 Badge. Shared chrome: `inline-flex items-center gap-1 px-1.5`, 22dp
 * tall, chip radius, `text-caption` semibold Space Grotesk, one line, truncated with an ellipsis.
 *
 * - [RaftBadgeAppearance.Solid] (default): a 20dp sticker (min 20dp wide, centred),
 *   sticker radius, bold tabular ink type, a 1.5dp sticker-ink edge and, in dark, a
 *   1.5dp outer `sticker-edge` ring. `Accent` (the unread count) sets Space Mono.
 * - [RaftBadgeAppearance.Soft]: the variant's `-soft` wash, `--foreground` type, no edge.
 * - [RaftBadgeAppearance.Outline]: transparent, a 1dp `line-hairline` edge, Space Mono
 *   `text-mono-meta` in `foreground-muted` or the variant's `-strong` ink.
 *
 * [enabled] = false is the look of a disabled badge button (`fill-muted`,
 * `foreground-disabled`, no inset edge). [icon] is drawn at the badge's icon size
 * (13dp solid, 14dp otherwise) with [LocalRaftContentColor] set to the badge ink.
 * [trailing] follows the label 4dp further out (web `ml-1`), at its own size: a runtime
 * label's account-usage [RaftStatusDot] (`sm`).
 */
@Composable
fun RaftBadge(
    text: String,
    modifier: Modifier = Modifier,
    appearance: RaftBadgeAppearance = RaftBadgeAppearance.Solid,
    variant: RaftBadgeVariant = RaftBadgeVariant.Default,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    RaftBadgeFrame(
        text = text,
        look = raftBadgeLook(appearance, variant, enabled, RaftTheme.colors),
        appearance = appearance,
        modifier = modifier,
        icon = icon,
        trailing = trailing,
    )
}

/**
 * The Badge chrome around a resolved [look]. Internal: a preset that needs a look raft-ui
 * spells as a Badge plus classes (the runtime chip's `font-mono text-mono-meta font-normal`)
 * adjusts [look] here instead of widening [RaftBadge]'s public API.
 */
@Composable
internal fun RaftBadgeFrame(
    text: String,
    look: RaftBadgeLook,
    appearance: RaftBadgeAppearance,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val typography = RaftTheme.typography
    val textStyle: TextStyle =
        (if (look.mono) typography.monoMeta else typography.caption).copy(
            fontWeight = look.weight,
            fontFeatureSettings = if (appearance == RaftBadgeAppearance.Solid) "tnum" else null,
        )
    val shape = RoundedCornerShape(look.radius)
    Row(
        modifier =
            modifier
                .raftDieCutEdge(look.outerRing, look.radius)
                .height(look.height)
                .widthIn(min = look.minWidth)
                .background(look.fill, shape)
                .then(if (look.edgeWidth > 0.dp) Modifier.border(look.edgeWidth, look.edge, shape) else Modifier)
                .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            CompositionLocalProvider(LocalRaftContentColor provides look.ink) {
                Box(Modifier.size(look.iconSize), contentAlignment = Alignment.Center) { icon() }
            }
        }
        if (text.isNotEmpty()) {
            RaftText(
                text = text,
                style = textStyle,
                color = look.ink,
                maxLines = 1,
                // web `truncate`: a badge narrower than its label ends in an ellipsis
                // instead of cutting a glyph in half.
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailing != null) {
            CompositionLocalProvider(LocalRaftContentColor provides look.ink) {
                Box(Modifier.padding(start = 4.dp), contentAlignment = Alignment.Center) { trailing() }
            }
        }
    }
}
