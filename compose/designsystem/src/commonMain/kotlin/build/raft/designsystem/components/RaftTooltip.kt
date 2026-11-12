package build.raft.designsystem.components

import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.Surface
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TooltipState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/** raft-ui tooltip metrics (tooltip.tsx). */
internal object RaftTooltipMetrics {
    /** The arrow's triangle (`M1 0H11L6 5.25Z` in its 12 x 6 svg). */
    val CaretSize = DpSize(10.dp, 5.25.dp)

    /** `sideOffset` 8: anchor edge to bubble edge; the caret hangs into this gap. */
    val AnchorSpacing = 8.dp

    /**
     * `px-2.5`. The bubble already pads [PaddingX] each side (and [PaddingY] top and
     * bottom, the `py-1`); this is the remaining 2dp.
     */
    val ExtraPaddingX = 2.dp

    /** Material's PlainTooltip: its padding, minimum size and maximum width. */
    val PaddingX = 8.dp
    val PaddingY = 4.dp
    val MinWidth = 40.dp
    val MinHeight = 24.dp
    val MaxWidth = 200.dp

    /**
     * How far past the bubble its fade layer reaches: the caret (5.25dp) and the float shadow
     * (offset + blur + spread: 44dp at most, below it in dark) draw there.
     */
    val Bleed = 48.dp

    /** How long a long press keeps it open (Material's `BasicTooltipDefaults.TooltipDuration`). */
    const val TouchDuration = 1500L

    /** A shortcut's `Kbd` after the label (tooltip body `[&_[data-slot=kbd]]:ml-1.5`). */
    val KbdGap = 6.dp

    /** The `Kbd` inside a tooltip: `h-4 min-w-4 px-1 py-0`, 3dp radius (kbd `rounded-[3px]`). */
    val KbdHeight = 16.dp
    val KbdMinWidth = 16.dp
    val KbdPaddingX = 4.dp
    val KbdRadius = 3.dp

    /** kbd `in-data-[slot=tooltip-content]`: `foreground-on-hud` at 70% for the text and the ring. */
    const val KbdInkAlpha = 0.7f
}

/** Which side of its anchor a [RaftTooltip] opens on. */
enum class RaftTooltipPlacement {
    /** Above, or below when there is no room above (raft-ui's default `side="top"`). */
    Above,

    /**
     * Below, or above when there is no room below: the top bar's items (raft-ui), where above
     * would run into the window's edge and its title-bar controls.
     */
    Below,
}

/**
 * The placement [RaftTooltip]s in the subtree take by default: [RaftTopBar] provides
 * [RaftTooltipPlacement.Below] for its items.
 */
val LocalRaftTooltipPlacement = staticCompositionLocalOf { RaftTooltipPlacement.Above }

/**
 * Opens every [RaftTooltip] in the subtree, persistent (screenshot fixtures only: an
 * offscreen frame can neither hover nor hold a long press).
 */
internal val LocalRaftTooltipPreview = staticCompositionLocalOf { false }

/**
 * The physical side of its anchor a [RaftTooltip] opens on, beside it and centred on it, with
 * the caret on the bubble's edge facing the anchor. It flips to the other side when the window
 * has no room on this one. Not public API: [RaftSideActionColumn] and [RaftAppRailItem] set it so
 * their tooltips open toward the content, never off the screen edge the column stands on.
 */
internal enum class RaftTooltipSide { Left, Right }

/** See [RaftTooltipSide]; null (the default) opens above the anchor, below without room. */
internal val LocalRaftTooltipSide = staticCompositionLocalOf<RaftTooltipSide?> { null }

/**
 * Waits out a long-press tooltip's [RaftTooltipMetrics.TouchDuration] before it closes
 * (`delay`). Tests swap in a gate they release, as the scene's effect dispatcher takes its
 * delays from the wall clock, not `mainClock`. A mouse-opened tooltip never calls it.
 */
internal val LocalRaftTooltipTouchTimer = staticCompositionLocalOf<suspend (durationMillis: Long) -> Unit> { { delay(it) } }

/**
 * raft-ui 0.8.0 Tooltip around [content]: a short label in the dark HUD bubble with a
 * small caret pointing at [content], above it (below when there is no room above), or below it
 * with [placement] [RaftTooltipPlacement.Below] (the default inside [RaftTopBar]).
 *
 * - Look (tooltip.tsx): `layer-hud` fill, `foreground-on-hud` type, `radius-chip` (6dp),
 *   10 x 4dp padding, `text-footnote` 13/18 in medium weight, `shadow-float`, a 10 x 5.25dp
 *   caret, 8dp from the anchor.
 * - A mouse / trackpad pointer entering [content] opens it, and it stays open until the
 *   pointer leaves (raft-ui: open while hovered). A touch or stylus long press opens it
 *   for [RaftTooltipMetrics.TouchDuration] (1.5s, Material's plain tooltip); that long
 *   press does not also click. A click or tap elsewhere closes it.
 * - Not focusable and adds no semantics node: it never takes focus or swallows the next
 *   tap, and screen readers get one stop, [content] itself, which must carry the label
 *   (see [RaftIconButton], which passes it as the content description).
 *
 * Material3's tooltip position providers place it; the popup, the bubble (Material's
 * PlainTooltip's metrics), its entrance and the gestures are ours: Material's gestures add an
 * unlabeled merging semantics node round [content] and close a hovered tooltip after 1.5s, and
 * its TooltipBox's fade cut off the caret and the shadow until it ended (see
 * [RaftTooltipBubble]). The bubble's width is capped at Material's 200dp (raft-ui allows
 * 320dp): keep labels short.
 *
 * [openOnLongPress] = false leaves the long press to [content] (an icon button with its own
 * long-press action): the tooltip then opens only under a mouse or trackpad pointer.
 *
 * [openOnTap]: a touch or stylus tap opens it too, for the same [RaftTooltipMetrics.TouchDuration];
 * the tap still reaches [content]. For an info button whose whole job is its tooltip (web
 * `<Tooltip><Button><Info /></Button></Tooltip>` beside a heading): a phone has no hover, so a
 * long press alone would leave the tap doing nothing.
 *
 * [shortcut] adds its keyboard shortcut after [text] ("⌘\"), as raft-ui's tooltip with a `Kbd`
 * (web LeftRail's "Collapse sidebar ⌘\"): 6dp after the label, a 16dp-high chip with a 1dp ring,
 * mono 12/16 regular, both in `foreground-on-hud` at 70%. Screen readers don't get it from here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RaftTooltip(
    text: String,
    modifier: Modifier = Modifier,
    openOnLongPress: Boolean = true,
    openOnTap: Boolean = false,
    placement: RaftTooltipPlacement = LocalRaftTooltipPlacement.current,
    shortcut: String? = null,
    content: @Composable () -> Unit,
) {
    val preview = LocalRaftTooltipPreview.current
    val side = LocalRaftTooltipSide.current
    val touchTimer = LocalRaftTooltipTouchTimer.current
    val colors = RaftTheme.colors
    // Persistent: open until dismissed. The touch path bounds it with a timeout.
    val state = rememberTooltipState(initialIsVisible = preview, isPersistent = true)
    val scope = rememberCoroutineScope()
    // The caller's modifier goes on this outer box, which keeps parent-data modifiers (`align`,
    // `weight`) working.
    Box(modifier.raftTooltipGestures(state, scope, touchTimer, openOnLongPress, openOnTap), propagateMinConstraints = true) {
        val label: @Composable () -> Unit = {
            if (shortcut == null) {
                RaftText(
                    text = text,
                    modifier = Modifier.padding(horizontal = RaftTooltipMetrics.ExtraPaddingX),
                    style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.Medium),
                    color = colors.foregroundOnHud,
                )
            } else {
                Row(
                    Modifier.padding(horizontal = RaftTooltipMetrics.ExtraPaddingX),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RaftText(
                        text = text,
                        modifier = Modifier.weight(1f, fill = false),
                        style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.Medium),
                        color = colors.foregroundOnHud,
                    )
                    Spacer(Modifier.width(RaftTooltipMetrics.KbdGap))
                    RaftTooltipKbd(shortcut, colors.foregroundOnHud.let { it.copy(alpha = it.alpha * RaftTooltipMetrics.KbdInkAlpha) })
                }
            }
        }
        val density = LocalDensity.current
        val spacing = with(density) { RaftTooltipMetrics.AnchorSpacing.roundToPx() }
        val frame = LocalRaftFrameInsets.current.toPx(density)
        val sideProvider =
            if (side == null) {
                null
            } else {
                remember(side, spacing, frame) { RaftTooltipSidePositionProvider(side, spacing, frame) }
            }
        val placed =
            when (placement) {
                RaftTooltipPlacement.Above -> TooltipDefaults.rememberPlainTooltipPositionProvider(RaftTooltipMetrics.AnchorSpacing)
                // Material's own Below provider (above without room): our caret follows the side it picks.
                RaftTooltipPlacement.Below ->
                    TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below, RaftTooltipMetrics.AnchorSpacing)
            }
        // Material's placement, then moved out of the host's frame insets (none: as it is). It also
        // keeps where it put the bubble, for our caret.
        val framed = remember(placed, spacing, frame) { RaftTooltipFrameProvider(placed, spacing, frame) }
        val caret =
            if (sideProvider == null) {
                Modifier.raftFramedCaret(framed, colors.layerHud)
            } else {
                // Material's caret only points up or down: ours is drawn on the edge facing the
                // anchor, on whichever side it opened.
                Modifier.raftSideCaret(sideProvider, colors.layerHud)
            }
        // Material's TooltipBox, less its gestures (ours, above) and with our entrance (see
        // [RaftTooltipBubble]); the popup is anchored to this box, round [content].
        val transition = rememberTransition(state.transition, label = "RaftTooltip")
        Box {
            if (state.isVisible) {
                // A page pushed over this one takes the bubble down first.
                RaftDismissOnPageCover { state.dismiss() }
                Popup(
                    popupPositionProvider = sideProvider ?: framed,
                    onDismissRequest = { if (state.isVisible) scope.launch { state.dismiss() } },
                    properties = PopupProperties(focusable = false, clippingEnabled = false),
                ) {
                    RaftTooltipBubble(transition, caret, content = label)
                }
            }
            Box { content() }
        }
    }
    DisposableEffect(state) { onDispose { state.onDispose() } }
}

/**
 * The tooltip's bubble: Material's PlainTooltip (its 40 x 24dp minimum, [RaftTooltipMetrics.MaxWidth],
 * 8 x 4dp padding), with the float shadow and [caret], fading and growing in and out as Material's
 * TooltipBox does.
 *
 * The fade draws the tooltip through an offscreen layer while it is translucent, and that layer
 * clips to its node: Material's own, round the bubble alone, cut the caret and the shadow (both
 * outside the bubble's bounds) until the fade ended, so they appeared after the bubble. Ours spans
 * [RaftTooltipMetrics.Bleed] round the bubble, which still measures and places as the bubble alone.
 */
@Composable
internal fun RaftTooltipBubble(
    transition: Transition<Boolean>,
    caret: Modifier,
    maxWidth: Dp = RaftTooltipMetrics.MaxWidth,
    content: @Composable () -> Unit,
) {
    val colors = RaftTheme.colors
    // Material's motion scheme: fast spatial for the scale, fast effects for the opacity.
    val scale by transition.animateFloat(
        transitionSpec = { spring(dampingRatio = 0.9f, stiffness = 1400f) },
        label = "RaftTooltip scale",
    ) { if (it) 1f else 0.8f }
    val alpha by transition.animateFloat(
        transitionSpec = { spring(dampingRatio = 1f, stiffness = 3800f) },
        label = "RaftTooltip alpha",
    ) { if (it) 1f else 0f }
    Box(
        Modifier
            .raftTooltipBleed(RaftTooltipMetrics.Bleed)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .padding(RaftTooltipMetrics.Bleed),
    ) {
        Surface(
            modifier = Modifier.raftFloatShadow(RaftTheme.isDark, RaftRadius.Chip).then(caret),
            shape = RoundedCornerShape(RaftRadius.Chip),
            color = colors.layerHud,
            contentColor = colors.foregroundOnHud,
        ) {
            Box(
                Modifier
                    .sizeIn(
                        minWidth = RaftTooltipMetrics.MinWidth,
                        maxWidth = maxWidth,
                        minHeight = RaftTooltipMetrics.MinHeight,
                    )
                    .padding(horizontal = RaftTooltipMetrics.PaddingX, vertical = RaftTooltipMetrics.PaddingY),
            ) { content() }
        }
    }
}

/**
 * Measures with [bleed] more room on every side and reports the size less it, the content drawn
 * [bleed] out past each edge: what draws there (shadow, caret) is inside this node's layer.
 */
private fun Modifier.raftTooltipBleed(bleed: Dp): Modifier =
    layout { measurable, constraints ->
        val px = bleed.roundToPx()
        val placeable = measurable.measure(constraints.offset(2 * px, 2 * px))
        layout(placeable.width - 2 * px, placeable.height - 2 * px) { placeable.place(-px, -px) }
    }

/** raft-ui `Kbd` inside a tooltip (see [RaftTooltip]'s `shortcut`), in [ink]. */
@Composable
private fun RaftTooltipKbd(text: String, ink: Color) {
    val shape = RoundedCornerShape(RaftTooltipMetrics.KbdRadius)
    Box(
        Modifier
            .height(RaftTooltipMetrics.KbdHeight)
            .widthIn(min = RaftTooltipMetrics.KbdMinWidth)
            .border(RaftStroke.Hairline, ink, shape)
            .padding(horizontal = RaftTooltipMetrics.KbdPaddingX),
        contentAlignment = Alignment.Center,
    ) {
        RaftText(text = text, style = RaftTheme.typography.monoMeta, color = ink, maxLines = 1, softWrap = false)
    }
}

/**
 * Places a tooltip beside its anchor ([RaftTooltipSide]); [resolved] is the side it took (it
 * flips without room), read by the caret's draw.
 */
private class RaftTooltipSidePositionProvider(
    private val preferred: RaftTooltipSide,
    private val spacing: Int,
    private val frame: RaftFramePx,
) : PopupPositionProvider {
    var resolved by mutableStateOf(preferred)
        private set

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val (offset, side) = raftTooltipSidePosition(anchorBounds, windowSize, popupContentSize, spacing, preferred, frame)
        if (resolved != side) resolved = side
        return offset
    }
}

/**
 * Where a [popup] beside [anchor] goes, [spacing] px from it on the [preferred] side, else on the
 * other side when only that one has room; vertically centred on the anchor and kept inside the
 * window. Returns the popup's top-left and the side it took.
 */
internal fun raftTooltipSidePosition(
    anchor: IntRect,
    window: IntSize,
    popup: IntSize,
    spacing: Int,
    preferred: RaftTooltipSide,
    frame: RaftFramePx = RaftFramePx.Zero,
): Pair<IntOffset, RaftTooltipSide> {
    val leftX = anchor.left - spacing - popup.width
    val rightX = anchor.right + spacing
    // Room on a side ends at the host's inset there (raft-ui: the tooltip flips away from it).
    val fitsLeft = leftX >= frame.left
    val fitsRight = rightX + popup.width <= window.width - frame.right
    val side =
        when (preferred) {
            RaftTooltipSide.Left -> if (!fitsLeft && fitsRight) RaftTooltipSide.Right else RaftTooltipSide.Left
            RaftTooltipSide.Right -> if (!fitsRight && fitsLeft) RaftTooltipSide.Left else RaftTooltipSide.Right
        }
    val x = if (side == RaftTooltipSide.Left) leftX else rightX
    val y = (anchor.top + (anchor.height - popup.height) / 2).coerceAtMost(window.height - popup.height).coerceAtLeast(frame.top)
    return IntOffset(x, y) to side
}

/** [RaftFrameInsets] in px. */
@Immutable
internal data class RaftFramePx(val top: Int = 0, val left: Int = 0, val right: Int = 0) {
    val isZero: Boolean get() = top <= 0 && left <= 0 && right <= 0

    companion object {
        val Zero = RaftFramePx()
    }
}

internal fun RaftFrameInsets.toPx(density: Density): RaftFramePx =
    if (isZero) {
        RaftFramePx.Zero
    } else {
        with(density) {
            RaftFramePx(top.coerceAtLeast(0.dp).roundToPx(), left.coerceAtLeast(0.dp).roundToPx(), right.coerceAtLeast(0.dp).roundToPx())
        }
    }

/**
 * Material's placement ([inner]), then moved out of the host's [frame] insets
 * ([raftTooltipClearOfFrame]). Keeps the side and the anchor's centre it ended on for
 * [raftFramedCaret]: written while the popup is laid out, so read by the bubble's first draw.
 */
internal class RaftTooltipFrameProvider(
    private val inner: PopupPositionProvider,
    private val spacing: Int,
    private val frame: RaftFramePx,
) : PopupPositionProvider {
    /** The bubble ended below its anchor (its caret on its top edge, pointing up). */
    var below by mutableStateOf(false)
        private set

    /** The anchor's centre, in px from the bubble's left edge: where the caret goes. */
    var anchorCenterX by mutableStateOf(0)
        private set

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val at =
            raftTooltipClearOfFrame(
                inner.calculatePosition(anchorBounds, windowSize, layoutDirection, popupContentSize),
                anchorBounds,
                windowSize,
                popupContentSize,
                spacing,
                frame,
            )
        val isBelow = at.y >= anchorBounds.bottom
        if (below != isBelow) below = isBelow
        val centre = anchorBounds.center.x - at.x
        if (anchorCenterX != centre) anchorCenterX = centre
        return at
    }
}

/**
 * The caret of a tooltip above or below its anchor ([RaftTooltipFrameProvider]): Material's
 * triangle, on the edge facing the anchor and under its centre, kept on the bubble's straight edge.
 */
internal fun Modifier.raftFramedCaret(provider: RaftTooltipFrameProvider, color: Color): Modifier =
    drawBehind {
        val width = RaftTooltipMetrics.CaretSize.width.toPx()
        val depth = RaftTooltipMetrics.CaretSize.height.toPx()
        val corner = RaftRadius.Chip.toPx()
        val low = corner + width / 2f
        val high = size.width - corner - width / 2f
        val cx = if (low <= high) provider.anchorCenterX.toFloat().coerceIn(low, high) else size.width / 2f
        val base = if (provider.below) 0f else size.height
        val tip = if (provider.below) base - depth else base + depth
        val path =
            Path().apply {
                moveTo(cx - width / 2f, base)
                lineTo(cx, tip)
                lineTo(cx + width / 2f, base)
                close()
            }
        drawPath(path, color)
    }

/**
 * A tooltip at [placed] moved out of the host's [frame] insets (raft-ui #860, up to the inset, no
 * gap): one that would open up into the top band opens [spacing] below [anchor] instead, and one
 * reaching into a side inset slides along toward the middle, as far as the window allows.
 */
internal fun raftTooltipClearOfFrame(
    placed: IntOffset,
    anchor: IntRect,
    window: IntSize,
    popup: IntSize,
    spacing: Int,
    frame: RaftFramePx,
): IntOffset {
    if (frame.isZero) return placed
    val y = if (placed.y < frame.top && placed.y < anchor.top) anchor.bottom + spacing else placed.y
    val minX = frame.left
    val maxX = window.width - frame.right - popup.width
    val x = if (minX <= maxX) placed.x.coerceIn(minX, maxX) else placed.x
    return IntOffset(x, y)
}

/**
 * The tooltip's caret ([RaftTooltipMetrics.CaretSize], the same triangle turned on its side) in
 * [color], hanging off the bubble's edge toward the anchor and centred on it, outside the
 * bubble's bounds (as Material's own caret and the float shadow are).
 */
private fun Modifier.raftSideCaret(provider: RaftTooltipSidePositionProvider, color: Color): Modifier =
    drawBehind {
        val width = RaftTooltipMetrics.CaretSize.width.toPx()
        val depth = RaftTooltipMetrics.CaretSize.height.toPx()
        val cy = size.height / 2f
        // The bubble is left of the anchor: the caret is on its right edge, pointing right.
        val pointsRight = provider.resolved == RaftTooltipSide.Left
        val base = if (pointsRight) size.width else 0f
        val tip = if (pointsRight) base + depth else base - depth
        val path =
            Path().apply {
                moveTo(base, cy - width / 2f)
                lineTo(tip, cy)
                lineTo(base, cy + width / 2f)
                close()
            }
        drawPath(path, color)
    }

/** Opens [state] for [RaftTooltipMetrics.TouchDuration] (outside the restricted pointer scope). */
@OptIn(ExperimentalMaterial3Api::class)
private fun CoroutineScope.showForTouch(
    state: TooltipState,
    timer: suspend (Long) -> Unit,
) {
    launch {
        val show = launch { state.show(MutatePriority.UserInput) }
        timer(RaftTooltipMetrics.TouchDuration)
        // Cancelling the show closes it (no-op if a hover or another press took over).
        show.cancel()
    }
}

/**
 * Hover (mouse) opens [state] until the pointer leaves; with [onLongPress], a touch / stylus
 * long press opens it for [RaftTooltipMetrics.TouchDuration] and eats the rest of that
 * gesture so [content] doesn't click; with [onTap], a touch / stylus tap opens it for as long
 * and still clicks. Runs in the Initial pass and consumes nothing else, so
 * [content]'s own clicks, presses and hover are unchanged.
 */
@OptIn(ExperimentalMaterial3Api::class)
private fun Modifier.raftTooltipGestures(
    state: TooltipState,
    scope: CoroutineScope,
    touchTimer: suspend (Long) -> Unit,
    onLongPress: Boolean,
    onTap: Boolean,
): Modifier =
    then(if (onLongPress || onTap) Modifier.raftTooltipTouch(state, scope, touchTimer, onLongPress, onTap) else Modifier)
        .raftTooltipHover(state, scope)

@OptIn(ExperimentalMaterial3Api::class)
private fun Modifier.raftTooltipTouch(
    state: TooltipState,
    scope: CoroutineScope,
    touchTimer: suspend (Long) -> Unit,
    onLongPress: Boolean,
    onTap: Boolean,
): Modifier =
    pointerInput(state, onLongPress, onTap) {
        awaitEachGesture {
            val pass = PointerEventPass.Initial
            val down = awaitFirstDown(requireUnconsumed = false, pass = pass)
            if (down.type != PointerType.Touch && down.type != PointerType.Stylus) return@awaitEachGesture
            try {
                val up = withTimeout(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation(pass) }
                // A tap (lifted, not cancelled): shown, and the tap goes on to [content].
                if (onTap && up != null) scope.showForTouch(state, touchTimer)
            } catch (_: PointerEventTimeoutCancellationException) {
                if (!onLongPress) return@awaitEachGesture
                scope.showForTouch(state, touchTimer)
                // The long press was the tooltip's: swallow it up to the release.
                do {
                    val event = awaitPointerEvent(pass)
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        }
    }

@OptIn(ExperimentalMaterial3Api::class)
private fun Modifier.raftTooltipHover(
    state: TooltipState,
    scope: CoroutineScope,
): Modifier =
    pointerInput(state) {
        var hover: Job? = null
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.firstOrNull()?.type != PointerType.Mouse) continue
                when (event.type) {
                    PointerEventType.Enter -> {
                        hover?.cancel()
                        hover = scope.launch { state.show(MutatePriority.UserInput) }
                    }
                    PointerEventType.Exit -> {
                        hover?.cancel()
                        hover = null
                        state.dismiss()
                    }
                }
            }
        }
    }
