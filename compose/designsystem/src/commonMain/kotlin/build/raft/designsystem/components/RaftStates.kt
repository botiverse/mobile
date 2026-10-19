package build.raft.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.tokens.RaftShadowLayer
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.tokens.RaftUiPalette
import build.raft.designsystem.tokens.RaftUiThemeColors

// Shared state primitives (raft-design Foundations > States, Elevation > Focus ring,
// Dark mode > The die-cut edge, Color > Stickers / Pressed overlays). Every component
// draws hover/press, focus, selection and the die-cut edge through these, so a state
// looks the same everywhere. None of them takes layout room: rings outside the box are
// drawn beyond its bounds, and a sticker's ink edge is always present (transparent at
// rest), so a state change never moves anything.

/** raft-ui `--focus-ring`: a 2dp gap in the surface colour, then 2dp of `line-strong`. */
object RaftFocusRing {
    val Gap = 2.dp
    val Width = 2.dp

    /** `inset-ring-2`: the outline of an element touching its container's edge. */
    val InsetWidth = 2.dp
}

/**
 * Renders every press overlay in the subtree as pressed. Screenshot fixtures only: a
 * real press arrives through the component's `interactionSource`, and an offscreen
 * frame cannot hold a pointer down.
 */
internal val LocalRaftPressedPreview = staticCompositionLocalOf { false }

/** Renders every focus ring in the subtree as focused (screenshot fixtures only). */
internal val LocalRaftFocusedPreview = staticCompositionLocalOf { false }

/** Pressed from [interactionSource], or forced by [LocalRaftPressedPreview]. */
@Composable
internal fun raftIsPressed(interactionSource: InteractionSource): Boolean {
    val pressed = interactionSource.collectIsPressedAsState().value
    return pressed || LocalRaftPressedPreview.current
}

/**
 * Focused from [interactionSource], or forced by [LocalRaftFocusedPreview]. Compose only
 * moves focus to a clickable from a keyboard or D-pad (touch never focuses it), so this
 * is CSS `focus-visible`.
 */
@Composable
internal fun raftIsFocused(interactionSource: InteractionSource): Boolean {
    val focused = interactionSource.collectIsFocusedAsState().value
    return focused || LocalRaftFocusedPreview.current
}

/**
 * The centre line of a stroke band, relative to a box: [outset] past the box edge (negative
 * is inside), with [cornerRadius] and [strokeWidth]. All in px.
 */
@Immutable
internal data class RaftBand(val outset: Float, val cornerRadius: Float, val strokeWidth: Float)

/**
 * A band of [width] starting [offset] outside a box with corner [radius] (CSS
 * `box-shadow: 0 0 0 <offset + width>` over one of `<offset>`). Its corners grow with the
 * distance, as a spread shadow's do, and a square box keeps square corners.
 */
internal fun raftOuterBand(offset: Float, width: Float, radius: Float): RaftBand {
    val outset = offset + width / 2f
    return RaftBand(outset = outset, cornerRadius = if (radius > 0f) radius + outset else 0f, strokeWidth = width)
}

/** A band of [width] inside the edge of a box with corner [radius] (CSS `inset-ring`). */
internal fun raftInsetBand(width: Float, radius: Float): RaftBand =
    RaftBand(outset = -width / 2f, cornerRadius = (radius - width / 2f).coerceAtLeast(0f), strokeWidth = width)

private fun DrawScope.drawBand(band: RaftBand, color: Color) {
    drawRoundRect(
        color = color,
        topLeft = Offset(-band.outset, -band.outset),
        size = Size(size.width + band.outset * 2f, size.height + band.outset * 2f),
        cornerRadius = CornerRadius(band.cornerRadius),
        style = Stroke(width = band.strokeWidth),
    )
}

/**
 * CSS `ring-[w] ring-<color>` / `shadow-[0_0_0_w_color]`: a [width] stroke [offset]
 * outside the box, following its [radius]. Takes no layout space; the parent must not clip.
 */
internal fun Modifier.raftOuterRing(color: Color, width: Dp, radius: Dp, offset: Dp = 0.dp): Modifier =
    if (color.alpha == 0f) {
        this
    } else {
        drawBehind { drawBand(raftOuterBand(offset.toPx(), width.toPx(), radius.toPx()), color) }
    }

/**
 * The dark-mode die-cut edge (`ring-[1.5px] ring-sticker-edge`): 1.5dp of [stickerEdge]
 * outside the sticker's own ink edge. `sticker-edge` is transparent in light, so pass it
 * in both modes. Every sticker takes it: avatars, yellow selection, pink / orange / red
 * buttons, switches, count and @ badges.
 */
internal fun Modifier.raftDieCutEdge(stickerEdge: Color, radius: Dp): Modifier =
    raftOuterRing(stickerEdge, RaftStroke.Control, radius)

/**
 * `shadow-focus` (Elevation > Focus ring): a [RaftFocusRing.Gap] band of [gap] (the
 * surface colour, `layer-canvas`) then [RaftFocusRing.Width] of [ink] (`line-strong`),
 * outside the box and following its [radius]. It goes outside a sticker's own edge and
 * under its die-cut edge, so chain it before [raftDieCutEdge].
 */
internal fun Modifier.raftFocusRing(visible: Boolean, radius: Dp, gap: Color, ink: Color): Modifier =
    if (!visible) {
        this
    } else {
        drawBehind {
            val r = radius.toPx()
            val gapPx = RaftFocusRing.Gap.toPx()
            drawBand(raftOuterBand(0f, gapPx, r), gap)
            drawBand(raftOuterBand(gapPx, RaftFocusRing.Width.toPx(), r), ink)
        }
    }

/** [raftFocusRing] in the theme's colours: a `layer-canvas` gap and a `line-strong` ring. */
internal fun Modifier.raftFocusRing(visible: Boolean, radius: Dp, colors: RaftUiThemeColors): Modifier =
    raftFocusRing(visible, radius, gap = colors.layerCanvas, ink = colors.lineStrong)

/**
 * `focus-visible:inset-ring-2 inset-ring-foreground`: a 2dp [ink] outline inside the box,
 * over the content, for an element that touches its container's edge (a list row, the
 * search row of a menu), where an outer ring would be clipped.
 */
internal fun Modifier.raftInsetFocusOutline(visible: Boolean, ink: Color, radius: Dp = 0.dp): Modifier =
    if (!visible) {
        this
    } else {
        drawWithContent {
            drawContent()
            drawBand(raftInsetBand(RaftFocusRing.InsetWidth.toPx(), radius.toPx()), ink)
        }
    }

/**
 * The press overlay (CSS `press-overlay*`): [overlay] laid over the fill while [pressed],
 * under the content and the border. Chain it after the fill. It never changes the fill
 * and never moves anything (Motion: press doesn't move).
 */
internal fun Modifier.raftPressOverlay(pressed: Boolean, overlay: Color, radius: Dp): Modifier =
    if (!pressed || overlay.alpha == 0f) {
        this
    } else {
        drawBehind { drawRoundRect(overlay, cornerRadius = CornerRadius(radius.toPx())) }
    }

/**
 * Which overlay a pressed surface takes (Color > Pressed overlays): `sticker-pressed`
 * (12% ink) on a block of colour, `fill-frame-pressed` on the yellow frame, else
 * `fill-pressed` (8% ink) on paper and frameless controls.
 */
enum class RaftPressSurface {
    Paper,
    Sticker,
    Frame,
}

internal fun raftPressOverlayColor(surface: RaftPressSurface, colors: RaftUiThemeColors): Color =
    when (surface) {
        RaftPressSurface.Paper -> colors.fillPressed
        RaftPressSurface.Sticker -> RaftUiPalette.stickerPressed
        RaftPressSurface.Frame -> colors.fillFramePressed
    }

/** Colours of a navigation item that can become the active sticker. */
@Immutable
internal data class RaftStickerLook(
    val fill: Color,
    /** The 1.5dp edge inside the box: `sticker-ink` when active, transparent at rest. */
    val edge: Color,
    val ink: Color,
    /** The die-cut edge outside the box (`sticker-edge`; transparent at rest and in light). */
    val dieCut: Color,
    val pressOverlay: Color,
)

/**
 * The active sticker (Color > Stickers, States > Selected: navigation): a `brand` block
 * with a 1.5dp `sticker-ink` edge, `sticker-ink` type and the die-cut edge, in both
 * modes. At rest the item shows [restingFill] and [restingInk] and keeps a transparent
 * edge of the same width, so turning active changes colours only.
 */
internal fun raftActiveStickerLook(
    active: Boolean,
    colors: RaftUiThemeColors,
    restingFill: Color = Color.Transparent,
    restingInk: Color = colors.foreground,
): RaftStickerLook =
    if (active) {
        RaftStickerLook(
            fill = RaftUiPalette.brand,
            edge = RaftUiPalette.stickerInk,
            ink = RaftUiPalette.stickerInk,
            dieCut = colors.stickerEdge,
            pressOverlay = RaftUiPalette.stickerPressed,
        )
    } else {
        RaftStickerLook(
            fill = restingFill,
            edge = Color.Transparent,
            ink = restingInk,
            dieCut = Color.Transparent,
            pressOverlay = colors.fillPressed,
        )
    }

/**
 * Draws a [RaftStickerLook]: the die-cut edge outside, the fill, the press overlay while
 * [pressed], and the 1.5dp edge inside the box, which is always drawn (transparent at
 * rest). [focused] adds the focus ring outside the edge, under the die-cut edge. Pad the
 * content by [RaftStroke.Control] or more: the edge is drawn inside the box.
 */
internal fun Modifier.raftSticker(
    look: RaftStickerLook,
    radius: Dp,
    pressed: Boolean = false,
    focused: Boolean = false,
    colors: RaftUiThemeColors,
): Modifier {
    val shape = RoundedCornerShape(radius)
    return this
        .raftFocusRing(focused, radius, colors)
        .raftDieCutEdge(look.dieCut, radius)
        .background(look.fill, shape)
        .raftPressOverlay(pressed, look.pressOverlay, radius)
        .border(RaftStroke.Control, look.edge, shape)
}

/** One hard (blur 0, spread 0) shadow layer, e.g. `--elevation-sticker`, behind a rounded box. */
internal fun Modifier.raftHardShadow(layer: RaftShadowLayer, radius: Dp): Modifier =
    drawBehind {
        drawRoundRect(
            color = layer.color,
            topLeft = Offset(layer.offsetX.toPx(), layer.offsetY.toPx()),
            size = size,
            cornerRadius = CornerRadius(radius.toPx()),
        )
    }
