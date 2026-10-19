package build.raft.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.theme.RaftTypography
import build.raft.designsystem.tokens.RaftElevation
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.tokens.RaftUiPalette

/** raft-ui Avatar `type`: the fill under the face. Agents and people share the square. */
enum class RaftAvatarType {
    /** `inactive` (stone) until the agent's pixel avatar, which brings its own fill, covers it. */
    Agent,

    /** `palette-lavender`, under a photo or 1–2 initials. */
    Human,
}

/**
 * raft-ui Avatar `size`: multiples of 8 plus two 1.5px edges, so a face's pixels stay
 * even. [Xxs] is raft `2xs`, [Xxxs] `3xs` (14dp, outside the scale).
 */
enum class RaftAvatarSize(
    val edge: Dp,
    internal val initialsSize: TextUnit,
    internal val iconSize: Dp,
    internal val badgeEdge: Dp,
    /** How far the presence light overhangs the right and bottom edges. */
    internal val badgeOverhang: Dp,
    internal val badgeBorder: Dp,
    /** The outline cut out of the surface around the light. */
    internal val badgeCutout: Dp,
) {
    /** 99dp: the profile of an agent or a person; carries the hard sticker shadow. */
    Xl(99.dp, 40.sp, 48.dp, 20.dp, 8.dp, RaftStroke.Strong, 2.dp),

    /** 51dp: your own row in settings, profile cards. */
    Lg(51.dp, 22.sp, 24.dp, 14.dp, 5.dp, RaftStroke.Control, 2.dp),

    /** 35dp: messages and lists. */
    Md(35.dp, 15.sp, 18.dp, 11.dp, 4.dp, RaftStroke.Control, 2.dp),

    /** 27dp: search results, top bars. */
    Sm(27.dp, 13.sp, 14.dp, 9.dp, 3.dp, RaftStroke.Control, 2.dp),

    /** 19dp: the sidebar, chips, avatar stacks. */
    Xs(19.dp, 11.sp, 12.dp, 7.dp, 2.dp, RaftStroke.Hairline, 1.dp),

    /** 19dp (raft `2xs`): sidebar and DM rows. */
    Xxs(19.dp, 11.sp, 12.dp, 7.dp, 2.dp, RaftStroke.Hairline, 1.dp),

    /** 14dp (raft `3xs`): inline preview chips; outside the scale. */
    Xxxs(14.dp, 8.sp, 8.dp, 6.dp, 2.dp, RaftStroke.Hairline, 1.dp),
    ;

    /** raft-ui writes only one initial at `xs`, `2xs` and `sm`, where two don't fit. */
    val maxInitials: Int get() = if (this == Xl || this == Lg || this == Md) 2 else 1
}

/**
 * Initials for a person without a photo: the first letter of the first [RaftAvatarSize.maxInitials]
 * words of [name], upper-cased.
 */
fun raftAvatarInitials(name: String, size: RaftAvatarSize = RaftAvatarSize.Md): String =
    name.trim()
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .take(size.maxInitials)
        .joinToString("") { it.first().uppercase() }

/** Set by [RaftAvatarGroup]: each face in a stack is ringed with the surface. */
private val LocalRaftAvatarGroupRing = staticCompositionLocalOf { Color.Unspecified }

/**
 * raft-ui 0.8.0 Avatar: the square sticker that stands for one agent or one person. A
 * `sticker-radius` square with a 1.5dp `sticker-ink` edge and a 1.5dp `sticker-edge` ring
 * outside (the die-cut edge in dark); [RaftAvatarSize.Xl] adds the 4dp hard sticker shadow.
 *
 * The face is, in order: [image] (the agent's pixel avatar or the person's photo, filling
 * the inside of the edge, 2dp corners) when given; else [initials] (Space Grotesk bold,
 * `sticker-ink`, sized by [size]; see [raftAvatarInitials]); else the placeholder person
 * icon. raft-ui hides a loading or failed image behind the fallback; here the caller
 * passes [image] only once it has loaded.
 *
 * [presence] adds the presence light in the bottom-right corner, overhanging the edge, cut
 * out of [presenceCutoutColor] (the surface the avatar sits on). [contentDescription] names
 * the avatar when no name is next to it.
 */
@Composable
fun RaftAvatar(
    type: RaftAvatarType,
    size: RaftAvatarSize,
    modifier: Modifier = Modifier,
    initials: String? = null,
    presence: RaftPresence? = null,
    presenceCutoutColor: Color = RaftTheme.colors.layerCanvas,
    contentDescription: String? = null,
    image: (@Composable () -> Unit)? = null,
) {
    val fill = if (type == RaftAvatarType.Agent) RaftUiPalette.inactive else RaftUiPalette.paletteLavender
    RaftAvatarFrame(
        size = size,
        fill = fill,
        edge = RaftUiPalette.stickerInk,
        modifier =
            modifier.semantics {
                if (contentDescription != null) {
                    this.contentDescription = contentDescription
                    role = Role.Image
                }
            },
        badge =
            presence?.let { light ->
                { RaftAvatarPresenceLight(light, size, presenceCutoutColor) }
            },
    ) {
        Box(
            Modifier.fillMaxSize().clip(RoundedCornerShape(RaftRadius.Xs)).clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            when {
                image != null -> image()
                !initials.isNullOrBlank() ->
                    RaftText(
                        text = initials,
                        style = raftAvatarInitialsStyle(size, RaftTheme.typography),
                        color = RaftUiPalette.stickerInk,
                        maxLines = 1,
                        softWrap = false,
                    )
                else -> RaftAvatarPlaceholderIcon(Modifier.size(size.iconSize))
            }
        }
    }
}

private fun raftAvatarInitialsStyle(size: RaftAvatarSize, typography: RaftTypography): TextStyle =
    typography.body.copy(
        fontSize = size.initialsSize,
        // `leading-none`.
        lineHeight = size.initialsSize,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.01).em,
        textAlign = TextAlign.Center,
    )

/** The sticker frame shared by [RaftAvatar] and [RaftAvatarGroupCount]. */
@Composable
private fun RaftAvatarFrame(
    size: RaftAvatarSize,
    fill: Color,
    edge: Color,
    modifier: Modifier,
    badge: (@Composable () -> Unit)? = null,
    face: @Composable () -> Unit,
) {
    val dark = RaftTheme.isDark
    val shape = RoundedCornerShape(RaftRadius.Sticker)
    val groupRing = LocalRaftAvatarGroupRing.current
    Box(
        modifier
            .size(size.edge)
            // Paint order is bottom-up: the hard shadow, the surface ring of a stack, the die-cut ring.
            .then(if (size == RaftAvatarSize.Xl) Modifier.raftHardShadow(RaftElevation.sticker(dark), RaftRadius.Sticker) else Modifier)
            .then(
                if (groupRing.isSpecified) {
                    Modifier.raftOuterRing(groupRing, RaftStroke.Control * 2, RaftRadius.Sticker)
                } else {
                    Modifier
                },
            )
            .raftDieCutEdge(RaftTheme.colors.stickerEdge, RaftRadius.Sticker),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(fill, shape)
                .border(RaftStroke.Control, edge, shape)
                .padding(RaftStroke.Control),
            contentAlignment = Alignment.Center,
        ) { face() }
        if (badge != null) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = size.badgeOverhang, y = size.badgeOverhang),
            ) { badge() }
        }
    }
}

/** The corner light: a status pixel with a [cutout]-coloured outline around it. */
@Composable
private fun RaftAvatarPresenceLight(presence: RaftPresence, size: RaftAvatarSize, cutout: Color) {
    val shape = RoundedCornerShape(RaftRadius.Xs)
    Box(
        Modifier
            .size(size.badgeEdge)
            .raftOuterRing(cutout, size.badgeCutout, RaftRadius.Xs)
            .background(presence.color, shape)
            .border(size.badgeBorder, RaftUiPalette.stickerInk, shape)
            .semantics {
                contentDescription = presence.word
                role = Role.Image
            },
    )
}

private val PlaceholderHead = PathParser().parsePathString(
    "M9,16.25c1.407,0,2.716-.407,3.827-1.101-.481-1.67-2.003-2.899-3.827-2.899" +
        "s-3.347,1.229-3.827,2.899c1.112,.694,2.42,1.101,3.827,1.101Z",
).toPath()

/** The dots ringing the placeholder person (raft-ui `AvatarFallbackIcon`, 18x18 grid). */
private val PlaceholderDots =
    listOf(
        1.889f to 10.414f, 2.971f to 4.972f, 2.972f to 13.027f, 1.89f to 7.586f,
        4.973f to 2.972f, 7.586f to 1.889f, 15.029f to 13.028f, 16.111f to 7.586f,
        13.028f to 2.971f, 16.11f to 10.414f, 15.028f to 4.973f, 10.414f to 1.89f,
    )

/** raft-ui's placeholder person, drawn from its 18x18 SVG in `sticker-ink`. */
@Composable
private fun RaftAvatarPlaceholderIcon(modifier: Modifier) {
    val ink = RaftUiPalette.stickerInk
    Canvas(modifier) {
        val unit = size.minDimension / 18f
        scale(unit, unit, pivot = Offset.Zero) {
            val stroke = Stroke(width = 1f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawCircle(ink, radius = 2f, center = Offset(9f, 7.75f), style = stroke)
            drawPath(PlaceholderHead, ink, style = stroke)
            PlaceholderDots.forEach { (x, y) -> drawCircle(ink, radius = 0.75f, center = Offset(x, y)) }
        }
    }
}

/**
 * raft-ui AvatarGroup: an overlapping stack of avatars, each overlapping the one before
 * it by 5dp and ringed with [ringColor] (the surface the stack sits on), so a face never
 * shows its neighbour through the overlap. End with [RaftAvatarGroupCount] for the rest.
 * Give the stack an accessible name with the full count ("5 agents working").
 */
@Composable
fun RaftAvatarGroup(
    modifier: Modifier = Modifier,
    ringColor: Color = RaftTheme.colors.layerCanvas,
    content: @Composable RowScope.() -> Unit,
) {
    CompositionLocalProvider(LocalRaftAvatarGroupRing provides ringColor) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy((-5).dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/**
 * raft-ui AvatarGroupCount: the trailing "+N" chip of a stack at the [size] of the avatars
 * beside it: the avatar frame with a transparent edge on `fill-muted`, Space Mono 12 bold
 * tabular in `foreground-muted`.
 */
@Composable
fun RaftAvatarGroupCount(
    text: String,
    size: RaftAvatarSize,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    RaftAvatarFrame(size = size, fill = colors.fillMuted, edge = Color.Transparent, modifier = modifier) {
        RaftText(
            text = text,
            style = RaftTheme.typography.monoMeta.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
            color = colors.foregroundMuted,
            maxLines = 1,
            softWrap = false,
        )
    }
}
