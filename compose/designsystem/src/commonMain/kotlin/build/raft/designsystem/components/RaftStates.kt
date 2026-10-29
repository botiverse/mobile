package build.raft.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.TouchBoundsExpansion
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import build.raft.designsystem.tokens.RaftElevation
import build.raft.designsystem.tokens.RaftShadowLayer
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.tokens.RaftUiPalette
import build.raft.designsystem.tokens.RaftUiThemeColors
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.time.TimeSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

/**
 * Renders every hover fill in the subtree as hovered (screenshot fixtures only): a real
 * hover is a mouse or trackpad pointer over the component.
 */
internal val LocalRaftHoveredPreview = staticCompositionLocalOf { false }

/**
 * The shortest time a press shows (artin 2026-10-21: tapping a Home channel showed no press,
 * where the web does). Inside a scrolling list Compose holds a press back until it knows the
 * finger isn't scrolling, so a quick tap emits its press and release together and the press
 * look never reaches the screen before the next page opens. Keeping it at least this long
 * makes every tap visible; a press held longer ends when the finger lifts.
 */
internal const val RaftPressMinimumMillis = 120L

/**
 * Pressed from [interactionSource] (shown for at least [RaftPressMinimumMillis] once a press
 * starts; a press cancelled by a scroll ends at once), or forced by [LocalRaftPressedPreview].
 */
@Composable
internal fun raftIsPressed(interactionSource: InteractionSource): Boolean {
    val shown = remember(interactionSource) { mutableStateOf(false) }
    LaunchedEffect(interactionSource) {
        coroutineScope {
            val presses = mutableListOf<PressInteraction.Press>()
            var since = TimeSource.Monotonic.markNow()
            var ending: Job? = null
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        presses += interaction
                        ending?.cancel()
                        if (!shown.value) {
                            shown.value = true
                            since = TimeSource.Monotonic.markNow()
                        }
                    }
                    is PressInteraction.Release -> {
                        presses -= interaction.press
                        if (presses.isEmpty()) {
                            val left = RaftPressMinimumMillis - since.elapsedNow().inWholeMilliseconds
                            ending = launch {
                                if (left > 0) delay(left)
                                shown.value = false
                            }
                        }
                    }
                    is PressInteraction.Cancel -> {
                        presses -= interaction.press
                        if (presses.isEmpty()) {
                            ending?.cancel()
                            shown.value = false
                        }
                    }
                }
            }
        }
    }
    // Read unconditionally: a composable read after `||` is skipped when pressed (#3356).
    val preview = LocalRaftPressedPreview.current
    return shown.value || preview
}

/**
 * Hovered from [interactionSource] by a mouse, trackpad or pen, or forced by
 * [LocalRaftHoveredPreview]. Reads only [RaftPointerHoverInteraction]s, which
 * [raftPointerHover] emits: attach it on the same source. The `HoverInteraction` that
 * `clickable` emits is ignored, so a platform that reports a finger as entering never shows
 * hover. Touch never hovers.
 */
@Composable
internal fun raftIsHovered(interactionSource: InteractionSource): Boolean {
    val hovered = remember(interactionSource) { mutableStateOf(false) }
    LaunchedEffect(interactionSource) {
        val enters = mutableListOf<RaftPointerHoverInteraction.Enter>()
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is RaftPointerHoverInteraction.Enter -> enters += interaction
                is RaftPointerHoverInteraction.Exit -> enters -= interaction.enter
            }
            hovered.value = enters.isNotEmpty()
        }
    }
    val preview = LocalRaftHoveredPreview.current
    return hovered.value || preview
}

/**
 * Focused from [interactionSource], or forced by [LocalRaftFocusedPreview]. Compose only
 * moves focus to a clickable from a keyboard or D-pad (touch never focuses it), so this
 * is CSS `focus-visible`.
 */
@Composable
internal fun raftIsFocused(interactionSource: InteractionSource): Boolean {
    val focused = interactionSource.collectIsFocusedAsState().value
    val preview = LocalRaftFocusedPreview.current
    return focused || preview
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

private fun DrawScope.drawBand(band: RaftBand, color: Color) = drawBandAround(Rect(Offset.Zero, size), band, color)

private fun DrawScope.drawBandAround(box: Rect, band: RaftBand, color: Color) {
    drawRoundRect(
        color = color,
        topLeft = Offset(box.left - band.outset, box.top - band.outset),
        size = Size(box.width + band.outset * 2f, box.height + band.outset * 2f),
        cornerRadius = CornerRadius(band.cornerRadius),
        style = Stroke(width = band.strokeWidth),
    )
}

/**
 * [raftFocusRing] round [box] (px, in the draw scope's coordinates) rather than the whole
 * element: a link inside a run of text rings only its own words.
 */
internal fun DrawScope.drawRaftFocusRing(box: Rect, radius: Dp, gap: Color, ink: Color) {
    val r = radius.toPx()
    val gapPx = RaftFocusRing.Gap.toPx()
    drawBandAround(box, raftOuterBand(0f, gapPx, r), gap)
    drawBandAround(box, raftOuterBand(gapPx, RaftFocusRing.Width.toPx(), r), ink)
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
 * (12% ink) on a block of colour, `fill-frame-pressed` on the yellow frame,
 * `sticker-ink-medium` on an ink sticker (the server tile), else `fill-pressed` (8% ink) on
 * paper and frameless controls. The hover tiers build on it: [RaftHoverTier].
 */
enum class RaftPressSurface {
    Paper,
    Sticker,
    Frame,
    InkSticker,
}

internal fun raftPressOverlayColor(surface: RaftPressSurface, colors: RaftUiThemeColors): Color =
    when (surface) {
        RaftPressSurface.Paper -> colors.fillPressed
        RaftPressSurface.Sticker -> RaftUiPalette.stickerPressed
        RaftPressSurface.Frame -> colors.fillFramePressed
        RaftPressSurface.InkSticker -> RaftUiPalette.stickerInkMedium
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
 * The active sticker (Color > Stickers, States > Selected: navigation): a `selected` block
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
            // raft-ui 0.33 #791: `bg-selected`, its own value so a theme that grays `brand`
            // keeps where you are yellow.
            fill = RaftUiPalette.selected,
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
 * content by [RaftStroke.Control] or more: the edge is drawn inside the box. [overlay]
 * (from [rememberRaftStateOverlay]) draws hover and press in place of [pressed].
 */
internal fun Modifier.raftSticker(
    look: RaftStickerLook,
    radius: Dp,
    pressed: Boolean = false,
    focused: Boolean = false,
    colors: RaftUiThemeColors,
    overlay: State<Color>? = null,
): Modifier {
    val shape = RoundedCornerShape(radius)
    return this
        .raftFocusRing(focused, radius, colors)
        .raftDieCutEdge(look.dieCut, radius)
        .background(look.fill, shape)
        .then(if (overlay != null) Modifier.raftStateOverlay(overlay, shape) else Modifier.raftPressOverlay(pressed, look.pressOverlay, radius))
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

/**
 * `shadow-float` ([RaftElevation.float]) under an overlay: menus, popovers, dialogs, sheets
 * and toasts, never content. Drawn without a platform blur, so it is the same on every
 * platform and Android API level (RenderEffect blur needs API 31): each blurred layer is
 * a stack of rounded rects whose alphas add up to the Gaussian edge CSS draws
 * (sigma = blur / 2). [topRadius] / [bottomRadius] are the surface's corner radii.
 */
internal fun Modifier.raftFloatShadow(dark: Boolean, topRadius: Dp, bottomRadius: Dp = topRadius): Modifier =
    drawWithCache {
        // Built once per size and density, not on every frame.
        val layers = RaftElevation.float(dark).flatMap { shadowLayerPaths(it, topRadius.toPx(), bottomRadius.toPx()) }
        onDrawBehind { layers.forEach { (path, color) -> drawPath(path, color) } }
    }

/** The rects of one shadow layer and their colours, bottom first. */
private fun CacheDrawScope.shadowLayerPaths(layer: RaftShadowLayer, topRadius: Float, bottomRadius: Float): List<Pair<Path, Color>> {
    val dx = layer.offsetX.toPx()
    val dy = layer.offsetY.toPx()
    val spread = layer.spread.toPx()
    val sigma = layer.blur.toPx() / 2f
    val out = ArrayList<Pair<Path, Color>>()

    // The box grown by [grow] on every side, moved by the layer's x / y.
    fun grown(grow: Float): Path? {
        if (size.width + 2 * grow <= 0f || size.height + 2 * grow <= 0f) return null
        return raftRoundRectPath(
            left = dx - grow,
            top = dy - grow,
            right = size.width + dx + grow,
            bottom = size.height + dy + grow,
            topRadius = max(0f, topRadius + grow),
            bottomRadius = max(0f, bottomRadius + grow),
        )
    }

    if (sigma <= 0f) {
        // A hard layer (a spread-only ring included): one rect.
        grown(spread)?.let { out += it to layer.color }
        return out
    }
    // A staircase of the edge profile alpha * (1 - Phi(d / sigma)), from 3 sigma outside the
    // spread edge inwards. Rect i covers every point inside its edge, so the alpha in band i
    // is 1 - prod(1 - a_j) for j <= i; each a_i makes that equal the profile mid-band.
    val alpha = layer.color.alpha
    val step = max(1.dp.toPx(), sigma / 8f)
    val steps = (6f * sigma / step).toInt().coerceAtLeast(1)
    var covered = 0f
    for (i in 0 until steps) {
        val edge = spread + 3f * sigma - i * step
        val target = alpha * (1f - raftNormalCdf((edge - step / 2f - spread) / sigma))
        if (target <= covered) continue
        val a = 1f - (1f - target) / (1f - covered)
        covered = target
        grown(edge)?.let { out += it to layer.color.copy(alpha = a) }
    }
    return out
}

private fun raftRoundRectPath(left: Float, top: Float, right: Float, bottom: Float, topRadius: Float, bottomRadius: Float): Path =
    Path().apply {
        addRoundRect(
            RoundRect(
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                topLeftCornerRadius = CornerRadius(topRadius),
                topRightCornerRadius = CornerRadius(topRadius),
                bottomRightCornerRadius = CornerRadius(bottomRadius),
                bottomLeftCornerRadius = CornerRadius(bottomRadius),
            ),
        )
    }

/** The standard normal CDF (Abramowitz-Stegun 7.1.26 erf, |error| < 1.5e-7). */
internal fun raftNormalCdf(x: Float): Float {
    val z = abs(x) / sqrt(2f)
    val t = 1f / (1f + 0.3275911f * z)
    val y =
        1f - (((((1.061405429f * t - 1.453152027f) * t) + 1.421413741f) * t - 0.284496736f) * t + 0.254829592f) *
            t * exp(-z * z)
    return 0.5f * (1f + if (x >= 0f) y else -y)
}

/**
 * An overlay's surface (menus, popovers, dialogs, sheets): `shadow-float` under it, a 1dp
 * [ring] outside its edge (`ring-1 ring-line-seam`; pass transparent for none), [fill],
 * and its content clipped to the shape (`overflow-hidden`). [topRadius] / [bottomRadius]
 * let a bottom sheet round only its top. Overlays never take an ink border.
 */
internal fun Modifier.raftOverlaySurface(
    fill: Color,
    ring: Color,
    dark: Boolean,
    topRadius: Dp,
    bottomRadius: Dp = topRadius,
): Modifier {
    val shape = RoundedCornerShape(topStart = topRadius, topEnd = topRadius, bottomEnd = bottomRadius, bottomStart = bottomRadius)
    return this
        .raftFloatShadow(dark, topRadius, bottomRadius)
        .then(
            if (ring.alpha == 0f) {
                Modifier
            } else {
                Modifier.drawWithCache {
                    val w = RaftStroke.Hairline.toPx()
                    val top = topRadius.toPx() + w / 2f
                    val bottom = if (bottomRadius > 0.dp) bottomRadius.toPx() + w / 2f else 0f
                    val path = raftRoundRectPath(-w / 2f, -w / 2f, size.width + w / 2f, size.height + w / 2f, top, bottom)
                    onDrawBehind { drawPath(path, ring, style = Stroke(width = w)) }
                }
            },
        )
        .clip(shape)
        .background(fill, shape)
}

/** raft-ui's minimum touch target (`::after` 44px), and Apple's. */
val RaftTouchTargetSize: Dp = 44.dp

/**
 * Widens a small control's touch target to [minSize] square without changing its layout
 * or drawing (raft-ui's overhanging `::after`): a press up to [minSize] / 2 from its
 * centre, outside its box, presses it through [interactionSource] and taps call
 * [onClick]. Put it before the control's own `clickable` / `selectable`, which still
 * handles touches inside the box, the keyboard and accessibility. The space around the
 * control must be free: a neighbour hit first wins.
 */
internal fun Modifier.raftTouchTarget(
    enabled: Boolean,
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
    minSize: Dp = RaftTouchTargetSize,
    onLongClick: (() -> Unit)? = null,
    expandStart: Boolean = true,
): Modifier = this then RaftTouchTargetElement(minSize, enabled, interactionSource, onClick, onLongClick, expandStart)

private data class RaftTouchTargetElement(
    val minSize: Dp,
    val enabled: Boolean,
    val interactionSource: MutableInteractionSource,
    val onClick: () -> Unit,
    val onLongClick: (() -> Unit)?,
    val expandStart: Boolean,
) : ModifierNodeElement<RaftTouchTargetNode>() {
    override fun create() = RaftTouchTargetNode(minSize, enabled, interactionSource, onClick, onLongClick, expandStart)

    override fun update(node: RaftTouchTargetNode) {
        node.minSize = minSize
        node.enabled = enabled
        node.interactionSource = interactionSource
        node.onClick = onClick
        node.expandStart = expandStart
        node.updateLongClick(onLongClick)
    }
}

/**
 * [expandStart] = false widens the target only past the end (raft-ui's `after:left-0`): a
 * control joined to a neighbour on its start edge (the dismiss of a jump pill) must not
 * take that neighbour's taps.
 */
private class RaftTouchTargetNode(
    var minSize: Dp,
    var enabled: Boolean,
    var interactionSource: MutableInteractionSource,
    var onClick: () -> Unit,
    private var onLongClick: (() -> Unit)?,
    var expandStart: Boolean,
) : DelegatingNode(), PointerInputModifierNode, LayoutAwareModifierNode {
    private var size = IntSize.Zero

    /** The gesture detector is set up once: restart it when a long press appears or goes. */
    fun updateLongClick(value: (() -> Unit)?) {
        val restart = (value == null) != (onLongClick == null)
        onLongClick = value
        if (restart) pointer.resetPointerInputHandler()
    }

    private fun outside(offset: Offset): Boolean =
        offset.x < 0f || offset.y < 0f || offset.x > size.width || offset.y > size.height

    private val pointer =
        delegate(
            SuspendingPointerInputModifierNode {
                // Inside the box the control's own clickable consumes the down first, so this
                // only sees touches in the widened ring.
                detectTapGestures(
                    onPress = { offset ->
                        if (enabled && outside(offset)) {
                            val press = PressInteraction.Press(offset)
                            interactionSource.emit(press)
                            val released = tryAwaitRelease()
                            interactionSource.emit(if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press))
                        }
                    },
                    // With a long press, a long press in the ring is that action (not a tap on
                    // release), as `combinedClickable` does inside the box.
                    onLongPress =
                        if (onLongClick != null) {
                            { offset -> if (enabled && outside(offset)) onLongClick?.invoke() }
                        } else {
                            null
                        },
                    onTap = { offset -> if (enabled && outside(offset)) onClick() },
                )
            },
        )

    override fun onRemeasured(size: IntSize) {
        this.size = size
    }

    override val touchBoundsExpansion: TouchBoundsExpansion
        get() {
            val min = with(requireDensity()) { minSize.roundToPx() }
            val y = ((min - size.height) / 2).coerceAtLeast(0)
            if (!expandStart) return TouchBoundsExpansion(0, y, (min - size.width).coerceAtLeast(0), y)
            val x = ((min - size.width) / 2).coerceAtLeast(0)
            return TouchBoundsExpansion(x, y, x, y)
        }

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) =
        pointer.onPointerEvent(pointerEvent, pass, bounds)

    override fun onCancelPointerInput() = pointer.onCancelPointerInput()
}
