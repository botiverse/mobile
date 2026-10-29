package build.raft.designsystem.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import build.raft.designsystem.motion.rememberRaftReducedMotion
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftUiPalette
import build.raft.designsystem.tokens.RaftUiThemeColors

// Hover and press as one overlay (raft-ui design/foundations/states.md § Hover and press,
// color.md § Hover overlays, lib/list-cell.ts). Hover lays a translucent overlay over the
// resting fill and never changes the fill; press lays the medium tier on everything. Hover is
// for a pointer that can hover without pressing (a mouse, a trackpad, a hovering pen: CSS
// `@media (hover: hover)`): a finger never hovers, so nothing sticks on the last thing tapped.

/**
 * What a hover lays over a component (raft-ui states.md § Hover and press). Press is
 * always the medium tier of the [RaftPressSurface] (`fill-cell-press` for [Cell]).
 */
enum class RaftHoverTier {
    /** No hover: fields, display-only parts. Press still shows. */
    None,

    /** `fill-subtle`: large things people read (rows, messages, readable cards). */
    Subtle,

    /**
     * The surface's medium tier (`fill-medium`, `sticker-medium`, `fill-frame-medium`,
     * `sticker-ink-medium`), the same overlay as press: buttons, chips, tabs, segments, nav items.
     */
    Medium,

    /**
     * `brand-soft`: the current item of a popup (menu items, select and combobox options).
     * Press and the keyboard highlight take the same fill.
     */
    Highlight,

    /**
     * A list cell (sidebar rows, inbox items, search results): `fill-cell-hover`, press
     * `fill-cell-press`, faded over 150ms with ease-out. A selected cell takes neither.
     */
    Cell,

    /** Media (an image in a gallery): `ink-8` (`hover:bg-ink-8`), faded over 75ms. */
    Media,
}

/** The hover and press overlays of a [RaftHoverTier] on a [RaftPressSurface]. */
@Immutable
data class RaftStateOverlayColors(val hover: Color, val press: Color)

/**
 * The surface's medium tier (Color > Hover overlays): `fill-medium` on paper and frameless
 * controls, `sticker-medium` on a colour sticker, `fill-frame-medium` on the frame,
 * `sticker-ink-medium` on an ink sticker (the server tile).
 */
internal fun raftMediumOverlayColor(surface: RaftPressSurface, colors: RaftUiThemeColors): Color =
    when (surface) {
        RaftPressSurface.Paper -> colors.fillMedium
        RaftPressSurface.Sticker -> RaftUiPalette.stickerMedium
        RaftPressSurface.Frame -> colors.fillFrameMedium
        RaftPressSurface.InkSticker -> RaftUiPalette.stickerInkMedium
    }

/** The overlays [tier] lays on [surface] in [colors]. Pure: the tiers in one place. */
internal fun raftStateOverlayColors(
    tier: RaftHoverTier,
    surface: RaftPressSurface,
    colors: RaftUiThemeColors,
): RaftStateOverlayColors {
    val medium = raftMediumOverlayColor(surface, colors)
    return when (tier) {
        RaftHoverTier.None -> RaftStateOverlayColors(hover = Color.Transparent, press = medium)
        RaftHoverTier.Subtle -> RaftStateOverlayColors(hover = colors.fillSubtle, press = medium)
        RaftHoverTier.Medium -> RaftStateOverlayColors(hover = medium, press = medium)
        RaftHoverTier.Highlight -> RaftStateOverlayColors(hover = colors.brandSoft, press = colors.brandSoft)
        RaftHoverTier.Cell -> RaftStateOverlayColors(hover = colors.fillCellHover, press = colors.fillCellPress)
        RaftHoverTier.Media -> RaftStateOverlayColors(hover = colors.ink8, press = colors.ink8)
    }
}

/**
 * The overlay to show now (pure). [enabled] = false shows nothing (disabled answers neither
 * hover nor press). [selected] drops hover, and for a [RaftHoverTier.Cell] press too (a
 * selected sticker still presses with its own overlay: a selected segment while pressed).
 * [highlighted] shows the hover look without a pointer: its menu is open
 * (`data-popup-open`) or the keyboard cursor is on it (`data-highlighted`). Press wins.
 */
internal fun raftStateOverlayTarget(
    overlay: RaftStateOverlayColors,
    tier: RaftHoverTier,
    pressed: Boolean,
    hovered: Boolean,
    enabled: Boolean = true,
    selected: Boolean = false,
    highlighted: Boolean = false,
): Color =
    when {
        !enabled -> Color.Transparent
        selected && tier == RaftHoverTier.Cell -> Color.Transparent
        pressed -> overlay.press
        selected -> Color.Transparent
        hovered || highlighted -> overlay.hover
        else -> Color.Transparent
    }

/**
 * How long a hover takes to fade in and out (raft-ui motion.md: press and hover 90ms to
 * 120ms): `duration-100` on controls, `duration-150` on list cells, `duration-75` on media.
 * Press shows at once (`active:transition-none`).
 */
internal fun raftHoverFadeMillis(tier: RaftHoverTier): Int =
    when (tier) {
        RaftHoverTier.Cell -> 150
        RaftHoverTier.Media -> 75
        else -> 100
    }

/** Tailwind's default `transition-colors` curve, `cubic-bezier(0.4, 0, 0.2, 1)`. */
private val RaftHoverEase: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

/** Tailwind `ease-out`, `cubic-bezier(0, 0, 0.2, 1)`: list cells. */
private val RaftHoverEaseOut: Easing = CubicBezierEasing(0f, 0f, 0.2f, 1f)

/**
 * A component's hover and press overlay, from [interactionSource]: draw it with
 * [raftStateOverlay] over the fill and under the content and border, and attach
 * [raftPointerHover] (or a `clickable` plus [raftPointerHover]) on the same source.
 *
 * Press shows at once and for at least [RaftPressMinimumMillis]; hover fades in and out over
 * [fadeMillis] ([raftHoverFadeMillis] for the tier; at once with reduced motion). [enabled], [selected] and
 * [highlighted] as [raftStateOverlayTarget]. Screenshot fixtures force press and hover
 * through the same preview locals as [raftIsPressed] / [raftIsHovered].
 */
@Composable
fun rememberRaftStateOverlay(
    interactionSource: InteractionSource,
    tier: RaftHoverTier,
    surface: RaftPressSurface = RaftPressSurface.Paper,
    enabled: Boolean = true,
    selected: Boolean = false,
    highlighted: Boolean = false,
    fadeMillis: Int = raftHoverFadeMillis(tier),
): State<Color> {
    // Read unconditionally, so the slot table stays put as enabled / selected flip (#3356).
    val pressed = raftIsPressed(interactionSource)
    val hovered = raftIsHovered(interactionSource)
    val reducedMotion = rememberRaftReducedMotion()
    val colors = RaftTheme.colors
    val overlay = raftStateOverlayColors(tier, surface, colors)
    val target =
        raftStateOverlayTarget(
            overlay = overlay,
            tier = tier,
            pressed = pressed,
            hovered = hovered,
            enabled = enabled,
            selected = selected,
            highlighted = highlighted,
        )
    // Fade to the same colour at 0 alpha, not to transparent black: a dark-mode paper
    // overlay would turn gray on its way out.
    val last = remember { RaftLastOverlay() }
    val shown =
        if (target.alpha > 0f) {
            last.color = target
            target
        } else {
            val hue =
                when {
                    last.color.alpha > 0f -> last.color
                    overlay.hover.alpha > 0f -> overlay.hover
                    else -> overlay.press
                }
            hue.copy(alpha = 0f)
        }
    val instant = reducedMotion || (enabled && pressed)
    val spec: AnimationSpec<Color> =
        if (instant) {
            snap()
        } else {
            tween(
                durationMillis = fadeMillis,
                easing = if (tier == RaftHoverTier.Cell) RaftHoverEaseOut else RaftHoverEase,
            )
        }
    return animateColorAsState(shown, spec, label = "raftStateOverlay")
}

/** The last visible overlay colour, so a fade out keeps its hue. Not state: read in composition only. */
private class RaftLastOverlay {
    var color: Color = Color.Transparent
}

/**
 * Draws [overlay] (from [rememberRaftStateOverlay]) in [shape] behind the content. Chain it
 * after the fill and before the border. Reads the colour at draw time, so a fade redraws
 * without recomposing.
 */
fun Modifier.raftStateOverlay(overlay: State<Color>, shape: Shape): Modifier =
    drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        onDrawBehind {
            val color = overlay.value
            if (color.alpha > 0f) drawOutline(outline, color)
        }
    }

/** [raftStateOverlay] in a rounded rect of [radius]. */
fun Modifier.raftStateOverlay(overlay: State<Color>, radius: Dp): Modifier =
    raftStateOverlay(overlay, RoundedCornerShape(radius))

/**
 * A hover from a pointer that hovers without pressing (see [raftPointerHover]). Its own
 * interaction, not `HoverInteraction`: `clickable` emits that one for any pointer the
 * platform reports as entering, and Raft's hover reads only these.
 */
internal sealed interface RaftPointerHoverInteraction : Interaction {
    class Enter : RaftPointerHoverInteraction

    class Exit(val enter: Enter) : RaftPointerHoverInteraction
}

/**
 * Whether a pointer of [type] hovers (CSS `@media (hover: hover)`): a mouse or trackpad, or a
 * pen held over the screen. A finger never does.
 */
internal fun raftPointerTypeHovers(type: PointerType): Boolean =
    type == PointerType.Mouse || type == PointerType.Stylus || type == PointerType.Eraser

/**
 * Emits Raft's hover into [interactionSource] while a mouse, trackpad or pen is over the
 * element, and never for touch, whatever the platform reports. [raftIsHovered] and
 * [rememberRaftStateOverlay] read only this, so every component that hovers attaches it
 * (next to its `clickable`, on the same source). Consumes nothing.
 */
fun Modifier.raftPointerHover(interactionSource: MutableInteractionSource, enabled: Boolean = true): Modifier =
    this then RaftPointerHoverElement(interactionSource, enabled)

private data class RaftPointerHoverElement(
    val interactionSource: MutableInteractionSource,
    val enabled: Boolean,
) : ModifierNodeElement<RaftPointerHoverNode>() {
    override fun create() = RaftPointerHoverNode(interactionSource, enabled)

    override fun update(node: RaftPointerHoverNode) = node.update(interactionSource, enabled)
}

private class RaftPointerHoverNode(
    private var interactionSource: MutableInteractionSource,
    private var enabled: Boolean,
) : Modifier.Node(), PointerInputModifierNode {
    private var enter: RaftPointerHoverInteraction.Enter? = null

    fun update(interactionSource: MutableInteractionSource, enabled: Boolean) {
        if (interactionSource != this.interactionSource || enabled != this.enabled) {
            exit()
            this.interactionSource = interactionSource
            this.enabled = enabled
        }
    }

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass != PointerEventPass.Main) return
        when (pointerEvent.type) {
            PointerEventType.Enter ->
                if (enabled && enter == null && pointerEvent.changes.any { raftPointerTypeHovers(it.type) }) {
                    val interaction = RaftPointerHoverInteraction.Enter()
                    enter = interaction
                    interactionSource.tryEmit(interaction)
                }
            PointerEventType.Exit -> exit()
        }
    }

    override fun onCancelPointerInput() = exit()

    override fun onDetach() = exit()

    private fun exit() {
        val current = enter ?: return
        enter = null
        interactionSource.tryEmit(RaftPointerHoverInteraction.Exit(current))
    }
}

/**
 * Reports where a mouse, trackpad or pen hovers over the element: [onHover] gets the pointer's
 * position (local px) as it enters and moves, then null when it leaves, is cancelled or the
 * element goes. A finger never reports, whatever the platform sends ([raftPointerTypeHovers]).
 * For parts of one element that hover one at a time, such as the links in a paragraph; a whole
 * component hovers with [raftPointerHover]. Consumes nothing. Disabling it reports null.
 */
fun Modifier.raftPointerHoverPosition(enabled: Boolean = true, onHover: (Offset?) -> Unit): Modifier =
    this then RaftPointerHoverPositionElement(enabled, onHover)

private data class RaftPointerHoverPositionElement(
    val enabled: Boolean,
    val onHover: (Offset?) -> Unit,
) : ModifierNodeElement<RaftPointerHoverPositionNode>() {
    override fun create() = RaftPointerHoverPositionNode(enabled, onHover)

    override fun update(node: RaftPointerHoverPositionNode) = node.update(enabled, onHover)
}

private class RaftPointerHoverPositionNode(
    private var enabled: Boolean,
    private var onHover: (Offset?) -> Unit,
) : Modifier.Node(), PointerInputModifierNode {
    private var hovering = false

    fun update(enabled: Boolean, onHover: (Offset?) -> Unit) {
        if (!enabled) leave()
        this.enabled = enabled
        this.onHover = onHover
    }

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass != PointerEventPass.Main) return
        when (pointerEvent.type) {
            PointerEventType.Enter, PointerEventType.Move -> {
                val change = pointerEvent.changes.firstOrNull { raftPointerTypeHovers(it.type) } ?: return
                if (!enabled) return
                val inside =
                    change.position.x >= 0f && change.position.y >= 0f &&
                        change.position.x < bounds.width && change.position.y < bounds.height
                if (inside) {
                    hovering = true
                    onHover(change.position)
                } else {
                    leave()
                }
            }
            PointerEventType.Exit -> leave()
        }
    }

    override fun onCancelPointerInput() = leave()

    override fun onDetach() = leave()

    private fun leave() {
        if (!hovering) return
        hovering = false
        onHover(null)
    }
}
