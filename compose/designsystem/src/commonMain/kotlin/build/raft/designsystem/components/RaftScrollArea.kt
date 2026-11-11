package build.raft.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import build.raft.designsystem.theme.RaftPointerInput
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.delay

/**
 * raft-ui 0.60 ScrollArea's `trigger` (raft-design #952): when the overlay scrollbar shows.
 * - [Hover] (raft-ui's default): while the area scrolls, and with a mouse or trackpad while the
 *   pointer is over it. On a touch screen that is only while it scrolls.
 * - [Scroll]: only while it scrolls (raft-ui's sidebar, message list and attachments row).
 * - [None]: always, the thumb at rest.
 */
enum class RaftScrollbarTrigger { Hover, Scroll, None }

/** raft-ui ScrollArea `size`: the thumb's thickness, `medium` 7dp or `small` 5dp (a short strip). */
enum class RaftScrollbarSize(internal val thickness: Dp, internal val hoverThickness: Dp) {
    Medium(7.dp, 10.dp),
    Small(5.dp, 8.dp),
}

/** raft-ui `scroll-area.css` (0.60.1) values. */
object RaftScrollbarDefaults {
    /** `--r-scroll-area-rail-gap`: the thumb to the area's edge, and to the rail's ends. */
    val RailGap: Dp = 3.dp

    /** `--r-scroll-area-thumb-min-length`. */
    val ThumbMinLength: Dp = 24.dp

    /** `--r-scroll-area-thumb-color`: `foreground` at 42%. */
    const val ThumbAlpha: Float = 0.42f

    /** `--r-scroll-area-duration-fade-in`. */
    const val FadeInMillis: Int = 120

    /** `--r-scroll-area-duration-fade-out`. */
    const val FadeOutMillis: Int = 400

    /** `hideDelay`'s default: how long the bar stays after the last scroll. */
    const val HideDelayMillis: Long = 600L
}

/** A thumb along its rail: [offset] from the area's start edge and its [length], in px. */
data class RaftScrollbarThumb(val offset: Float, val length: Float)

/**
 * Where the thumb sits (raft-ui `scroll-geometry.ts`): the rail runs the [viewport]'s length
 * less [railStart] / [railEnd] (a sticky header's or footer's height) and [gap] at each end;
 * the thumb's length is the viewport's share of the [content], at least [minLength] and at most
 * the rail; its offset follows [scroll] over the scroll range. Null when nothing overflows.
 */
fun raftScrollbarThumb(
    viewport: Float,
    content: Float,
    scroll: Float,
    minLength: Float,
    gap: Float = 0f,
    railStart: Float = 0f,
    railEnd: Float = 0f,
): RaftScrollbarThumb? {
    val range = content - viewport
    if (viewport <= 0f || range < 1f) return null
    val rail = viewport - railStart - railEnd - 2 * gap
    if (rail <= 0f) return null
    val length = (rail * viewport / content).coerceAtLeast(minLength).coerceAtMost(rail)
    val progress = (scroll / range).coerceIn(0f, 1f)
    return RaftScrollbarThumb(offset = railStart + gap + (rail - length) * progress, length = length)
}

/** A lazy list's content length and scroll offset, estimated from the rows it has laid out. */
data class RaftLazyScrollExtent(val content: Float, val scroll: Float)

/**
 * A lazy list knows only the rows it laid out, so its length is estimated as raft-ui does over a
 * VirtualList: the visible rows' mean size (spacing included) times [totalItems], plus the
 * content padding; the scroll is [firstIndex] mean rows plus [firstOffset] into the first.
 */
fun raftLazyScrollExtent(
    totalItems: Int,
    firstIndex: Int,
    firstOffset: Int,
    visibleSizes: List<Int>,
    spacing: Int,
    paddingBefore: Int,
    paddingAfter: Int,
): RaftLazyScrollExtent? {
    if (totalItems <= 0 || visibleSizes.isEmpty()) return null
    val mean = visibleSizes.sum().toFloat() / visibleSizes.size + spacing
    val content = mean * totalItems - spacing + paddingBefore + paddingAfter
    return RaftLazyScrollExtent(content = content, scroll = firstIndex * mean + firstOffset)
}

private fun LazyListState.extent(): Pair<Float, Float>? {
    val info = layoutInfo
    val e =
        raftLazyScrollExtent(
            totalItems = info.totalItemsCount,
            firstIndex = firstVisibleItemIndex,
            firstOffset = firstVisibleItemScrollOffset,
            visibleSizes = info.visibleItemsInfo.map { it.size },
            spacing = info.mainAxisItemSpacing,
            paddingBefore = info.beforeContentPadding,
            paddingAfter = info.afterContentPadding,
        ) ?: return null
    return e.content to e.scroll
}

/**
 * raft-ui 0.60 ScrollArea's overlay scrollbar over a [verticalScroll][androidx.compose.foundation.verticalScroll]
 * of [state]: put it before `verticalScroll(state)` so it draws over the viewport. It takes no
 * room: a rounded thumb in `foreground` at 42%, [size] thick, 3dp from the end edge (the start
 * edge in RTL), at least 24dp long, with no track. It fades in over 120ms while the area scrolls
 * (or, with [RaftScrollbarTrigger.Hover], while a mouse is over it) and out over 400ms
 * [hideDelayMillis] after; hidden when nothing overflows. [railTop] / [railBottom] keep a
 * sticky header's or footer's height clear (`railInset`).
 */
fun Modifier.raftScrollbar(
    state: ScrollState,
    trigger: RaftScrollbarTrigger = RaftScrollbarTrigger.Hover,
    size: RaftScrollbarSize = RaftScrollbarSize.Medium,
    railTop: Dp = 0.dp,
    railBottom: Dp = 0.dp,
    hideDelayMillis: Long = RaftScrollbarDefaults.HideDelayMillis,
): Modifier =
    raftScrollbarImpl(
        scrolling = { state.isScrollInProgress },
        extent = { viewport -> if (state.maxValue <= 0 || state.maxValue == Int.MAX_VALUE) null else (viewport + state.maxValue) to state.value.toFloat() },
        trigger = trigger,
        barSize = size,
        railTop = railTop,
        railBottom = railBottom,
        hideDelayMillis = hideDelayMillis,
    )

/** [raftScrollbar] over a `LazyColumn` of [state] (its modifier); the length is estimated ([raftLazyScrollExtent]). */
fun Modifier.raftScrollbar(
    state: LazyListState,
    trigger: RaftScrollbarTrigger = RaftScrollbarTrigger.Hover,
    size: RaftScrollbarSize = RaftScrollbarSize.Medium,
    railTop: Dp = 0.dp,
    railBottom: Dp = 0.dp,
    hideDelayMillis: Long = RaftScrollbarDefaults.HideDelayMillis,
): Modifier =
    raftScrollbarImpl(
        scrolling = { state.isScrollInProgress },
        extent = { _ ->
            if (!state.canScrollForward && !state.canScrollBackward) null else state.extent()
        },
        trigger = trigger,
        barSize = size,
        railTop = railTop,
        railBottom = railBottom,
        hideDelayMillis = hideDelayMillis,
    )

private fun Modifier.raftScrollbarImpl(
    scrolling: () -> Boolean,
    extent: (viewport: Float) -> Pair<Float, Float>?,
    trigger: RaftScrollbarTrigger,
    barSize: RaftScrollbarSize,
    railTop: Dp,
    railBottom: Dp,
    hideDelayMillis: Long,
): Modifier =
    composed {
        val hoverSource = remember { MutableInteractionSource() }
        val hovered = raftIsHovered(hoverSource)
        val pointerFine = RaftTheme.pointerInput == RaftPointerInput.Fine
        val visible =
            when (trigger) {
                RaftScrollbarTrigger.None -> true
                RaftScrollbarTrigger.Scroll -> scrolling()
                RaftScrollbarTrigger.Hover -> scrolling() || (pointerFine && hovered)
            }
        val alpha = remember { Animatable(if (trigger == RaftScrollbarTrigger.None) 1f else 0f) }
        LaunchedEffect(visible) {
            if (visible) {
                alpha.animateTo(1f, tween(RaftScrollbarDefaults.FadeInMillis))
            } else {
                delay(hideDelayMillis)
                alpha.animateTo(0f, tween(RaftScrollbarDefaults.FadeOutMillis))
            }
        }
        val ink = RaftTheme.colors.foreground
        this
            .then(if (trigger == RaftScrollbarTrigger.Hover) Modifier.raftPointerHover(hoverSource) else Modifier)
            .drawWithContent {
                drawContent()
                val a = alpha.value
                if (a <= 0f) return@drawWithContent
                val (content, scroll) = extent(size.height) ?: return@drawWithContent
                drawRaftThumb(
                    content = content,
                    scroll = scroll,
                    thickness = barSize.thickness.toPx(),
                    radius = barSize.hoverThickness.toPx() / 2f,
                    railTop = railTop.toPx(),
                    railBottom = railBottom.toPx(),
                    color = ink.copy(alpha = ink.alpha * RaftScrollbarDefaults.ThumbAlpha * a),
                )
            }
    }

private fun DrawScope.drawRaftThumb(
    content: Float,
    scroll: Float,
    thickness: Float,
    radius: Float,
    railTop: Float,
    railBottom: Float,
    color: androidx.compose.ui.graphics.Color,
) {
    val gap = RaftScrollbarDefaults.RailGap.toPx()
    val thumb =
        raftScrollbarThumb(
            viewport = size.height,
            content = content,
            scroll = scroll,
            minLength = RaftScrollbarDefaults.ThumbMinLength.toPx(),
            gap = gap,
            railStart = railTop,
            railEnd = railBottom,
        ) ?: return
    val x = if (layoutDirection == LayoutDirection.Rtl) gap else size.width - gap - thickness
    val r = radius.coerceAtMost(thickness / 2f)
    drawRoundRect(color, topLeft = Offset(x, thumb.offset), size = Size(thickness, thumb.length), cornerRadius = CornerRadius(r, r))
}

/** raft-ui `mask-fade-x`: how far in from the edge the content fades out. */
val RaftMaskFadeLength: Dp = 24.dp

/**
 * raft-ui `mask-fade-x` (with `data-overflow`): a sideways scroller's content fades out over
 * [length] on each side that has more content beyond it, so a cut item reads as "more this way"
 * rather than as clipped. [start] and [end] are the layout's start and end (RTL follows); with
 * neither, nothing is drawn differently. Put it before the scroll modifier, on the scroller's box.
 */
fun Modifier.raftMaskFadeX(start: Boolean, end: Boolean, length: Dp = RaftMaskFadeLength): Modifier =
    if (!start && !end) {
        this
    } else {
        // Offscreen, so DstIn masks only this content, not what is under it.
        graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val fade = length.toPx().coerceAtMost(size.width / 2f)
                if (fade <= 0f) return@drawWithContent
                val rtl = layoutDirection == LayoutDirection.Rtl
                val left = if (rtl) end else start
                val right = if (rtl) start else end
                if (left) {
                    drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = fade), blendMode = BlendMode.DstIn)
                }
                if (right) {
                    drawRect(
                        Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - fade, endX = size.width),
                        blendMode = BlendMode.DstIn,
                    )
                }
            }
    }

/** [raftMaskFadeX] for a [ScrollState] scrolled sideways: a fade on each side that has more. */
fun Modifier.raftMaskFadeX(state: ScrollState, length: Dp = RaftMaskFadeLength): Modifier =
    raftMaskFadeX(start = state.value > 0, end = state.value < state.maxValue, length = length)
