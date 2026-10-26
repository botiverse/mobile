package build.raft.designsystem.components

import build.raft.designsystem.gesture.rememberRaftBackSwipeScroll
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.tokens.RaftUiThemeColors
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

// Navigation selection (States > Selected: navigation): the selected tab, segment or
// pressed toggle is the yellow active sticker (RaftStates.kt) in every one of these.

/** raft-ui Tabs list `variant`. */
enum class RaftTabsVariant {
    /** Each tab a 32dp ghost; the selected one the yellow sticker. No track. */
    Default,

    /** 44dp text tabs 20dp apart; the selected one `foreground` with a 2dp ink line under it. */
    Underline,
}

private val LocalRaftTabsVariant = staticCompositionLocalOf { RaftTabsVariant.Default }

/**
 * raft-ui 0.8.0 Tabs (`placement="top"`): a row of [RaftTab]s that switch the panel below
 * them. [RaftTabsVariant.Default] puts the tabs 2dp apart, [RaftTabsVariant.Underline]
 * 20dp. The row hugs its tabs and does not scroll; for a panel's tab strip use
 * [RaftTabStrip].
 *
 * Not done: long-press drag to reorder (raft-ui SortableTabsList; the agent-detail
 * strip). Vertical placements are not ported.
 */
@Composable
fun RaftTabs(
    modifier: Modifier = Modifier,
    variant: RaftTabsVariant = RaftTabsVariant.Default,
    content: @Composable RowScope.() -> Unit,
) {
    CompositionLocalProvider(LocalRaftTabsVariant provides variant) {
        Row(
            modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(if (variant == RaftTabsVariant.Default) 2.dp else 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/**
 * The tab strip of a panel (agent detail, conversation panel tabs; page hand-off §8):
 * [RaftTabs] on `layer-canvas` with 16 / 8dp padding and a 1dp `line-seam` under it,
 * 49dp tall with default tabs. With more tabs than room it scrolls sideways: the
 * selected tab scrolls itself into view, and an edge with more tabs past it fades out
 * over 24dp. The padding scrolls with the tabs, so the selected sticker's die-cut edge
 * and the focus ring are never clipped.
 *
 * Only the strip's own horizontal scroll moves to show the selected tab: a scrolling
 * page around the strip stays where it is.
 */
@Composable
fun RaftTabStrip(
    modifier: Modifier = Modifier,
    variant: RaftTabsVariant = RaftTabsVariant.Default,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = RaftTheme.colors
    val scroll = rememberScrollState()
    val strip = remember(scroll) { RaftTabStripScroll(scroll) }
    val backSwipe = rememberRaftBackSwipeScroll(scroll)
    SideEffect { strip.composed = true }
    Column(modifier.fillMaxWidth().background(colors.layerCanvas)) {
        Box(
            Modifier
                .fillMaxWidth()
                // Read in the draw phase: reaching an edge redraws, the modifiers stay.
                .raftEdgeFade(colors.layerCanvas, start = { scroll.canScrollBackward }, end = { scroll.canScrollForward })
                .then(backSwipe.modifier)
                .horizontalScroll(scroll, enabled = backSwipe.userScrollEnabled),
        ) {
            CompositionLocalProvider(LocalRaftTabStripScroll provides strip) {
                RaftTabs(
                    Modifier
                        .onPlaced { strip.content = it }
                        .padding(horizontal = 16.dp, vertical = if (variant == RaftTabsVariant.Default) 8.dp else 0.dp),
                    variant = variant,
                    content = content,
                )
            }
        }
        // Flattened onto the canvas so the seam rounds like the web's (a draw-time blend lands a level dark).
        Box(Modifier.fillMaxWidth().height(RaftStroke.Hairline).background(colors.lineSeam.compositeOver(colors.layerCanvas)))
    }
}

/** How far a scrolling tab strip fades at an edge with more tabs past it. */
private val RaftEdgeFadeWidth = 24.dp

/**
 * Fades the content out towards the start / end edge while [start] / [end] say so (CSS
 * `mask-image` gradient), by drawing a [fill] gradient over it: the strip is all [fill],
 * so that looks the same as the mask, and with no offscreen layer nothing clips the
 * focus ring that a tab draws outside the strip (an underline tab's ring runs 4dp above
 * and below it). The draw step is always attached; only the fade's width changes (0 at
 * an edge with nothing past it), read in the draw phase.
 */
private fun Modifier.raftEdgeFade(fill: Color, start: () -> Boolean, end: () -> Boolean): Modifier =
    drawWithContent {
        drawContent()
        val full = RaftEdgeFadeWidth.toPx().coerceAtMost(size.width / 2f)
        val rtl = layoutDirection == LayoutDirection.Rtl
        val startWidth = if (start()) full else 0f
        val endWidth = if (end()) full else 0f
        val left = if (rtl) endWidth else startWidth
        val right = if (rtl) startWidth else endWidth
        if (left > 0f) {
            drawRect(
                Brush.horizontalGradient(listOf(fill, fill.copy(alpha = 0f)), startX = 0f, endX = left),
                size = Size(left, size.height),
            )
        }
        if (right > 0f) {
            drawRect(
                Brush.horizontalGradient(listOf(fill.copy(alpha = 0f), fill), startX = size.width - right, endX = size.width),
                topLeft = Offset(size.width - right, 0f),
                size = Size(right, size.height),
            )
        }
    }

/** A [RaftTabStrip]'s scroll, for its tabs to scroll themselves into view. */
private class RaftTabStripScroll(val scroll: ScrollState) {
    /** The scrolled row of tabs (with its padding). */
    var content: LayoutCoordinates? = null

    /**
     * False while the strip's first composition runs (set after it applies). A tab
     * remembers whether it was selected then: only that reveal jumps; every later one
     * animates, including the first selection of a tab that was not selected at first,
     * and a strip composed with no tab selected never jumps.
     */
    var composed: Boolean = false
}

/** Per tab: whether its next reveal is the strip's initial one, which jumps. */
private class RaftInitialReveal(var pending: Boolean)

/** Set by [RaftTabStrip]: its tabs scroll themselves into view when selected. */
private val LocalRaftTabStripScroll = staticCompositionLocalOf<RaftTabStripScroll?> { null }

/**
 * One tab of [RaftTabs]: a `text-callout` semibold `foreground-muted` label (Title Case,
 * one or two words) with an optional 16dp [icon] 6dp before it.
 *
 * - Default variant: 32dp tall, 12dp padding, hugging its label. [selected] is the active
 *   sticker: `selected`, 1.5dp `sticker-ink` edge, bold `sticker-ink` label, the die-cut edge
 *   in dark. Pressed lays `fill-pressed` (`sticker-pressed` on the sticker) over it.
 * - Underline variant: 44dp tall, no padding or fill. [selected] turns the label
 *   `foreground` and draws a 2dp `foreground` line along the tab's bottom.
 * - Keyboard focus draws the focus ring outside it. Disabled: `foreground-disabled`.
 * - A default tab draws 32dp but takes touches 44dp tall ([raftTouchTarget]). Heights are
 *   minimums: a large font scale grows the tab instead of clipping its label.
 * - In a [RaftTabStrip], a tab scrolls the strip (only the strip) to show itself, with
 *   room for its ring, when it becomes selected.
 */
@Composable
fun RaftTab(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val variant = LocalRaftTabsVariant.current
    val select = Modifier.selectable(selected, source, indication = null, enabled = enabled, role = Role.Tab, onClick = onClick)
    val target = if (variant == RaftTabsVariant.Default) Modifier.raftTouchTarget(enabled, source, onClick) else Modifier
    val strip = LocalRaftTabStripScroll.current
    val reveal = if (strip != null) Modifier.raftRevealInStripWhen(selected, strip) else Modifier
    when (variant) {
        RaftTabsVariant.Default -> RaftStickerItem(text, selected, enabled, source, modifier.then(reveal).then(target).then(select), icon = icon)
        RaftTabsVariant.Underline -> RaftUnderlineTab(text, selected, enabled, source, modifier.then(reveal).then(select), icon)
    }
}

/**
 * Scrolls [strip] so the element shows, with [room] round it for a focus ring or die-cut
 * edge, when [active] turns true (or [key] changes while it is): at once for the tab selected when the strip is first
 * composed, animated for every other reveal ([RaftTabStripScroll.composed]).
 * It moves the strip's own [ScrollState] and nothing else (a bring-into-view request
 * would also scroll every scrolling parent, so the page would jump), and only when the
 * selection changes, not when the tab's size does.
 */
@Composable
private fun Modifier.raftRevealInStripWhen(active: Boolean, strip: RaftTabStripScroll, room: Dp = 8.dp, key: Any? = null): Modifier {
    val tab = remember { mutableStateOf<LayoutCoordinates?>(null) }
    val initial = remember { RaftInitialReveal(pending = active && !strip.composed) }
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    LaunchedEffect(active, key) {
        if (!active) return@LaunchedEffect
        val coordinates = snapshotFlow { tab.value }.filterNotNull().first()
        // Consumed by the first activation that gets placed, whether or not it scrolls (a
        // run cancelled before that, by a [key] that changes on the first layout, keeps it).
        val jump = initial.pending
        initial.pending = false
        val content = strip.content ?: return@LaunchedEffect
        if (!coordinates.isAttached || !content.isAttached) return@LaunchedEffect
        val left = content.localPositionOf(coordinates, Offset.Zero).x.toInt()
        val right = left + coordinates.size.width
        // The scroll value runs from the start edge: from the right in right-to-left.
        val fromStart = if (layoutDirection == LayoutDirection.Rtl) content.size.width - right else left
        val scroll = strip.scroll
        val target =
            raftRevealScrollTarget(
                start = fromStart,
                end = fromStart + coordinates.size.width,
                room = with(density) { room.roundToPx() },
                viewport = scroll.viewportSize,
                current = scroll.value,
                max = scroll.maxValue,
            )
        if (target != scroll.value) {
            if (jump) scroll.scrollTo(target) else scroll.animateScrollTo(target)
        }
    }
    return onPlaced { tab.value = it }
}

/**
 * The scroll value that shows the span [start]..[end] (px from the content's start) with
 * [room] round it in a [viewport] scrolled to [current]: [current] if it already shows,
 * else the least scroll that shows it (its start edge first if it is wider than the
 * viewport), within 0..[max].
 */
internal fun raftRevealScrollTarget(start: Int, end: Int, room: Int, viewport: Int, current: Int, max: Int): Int {
    val from = start - room
    val to = end + room
    val target =
        when {
            from < current || to - from > viewport -> from
            to > current + viewport -> to - viewport
            else -> current
        }
    return target.coerceIn(0, maxOf(0, max))
}

@Composable
private fun RaftUnderlineTab(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    source: MutableInteractionSource,
    modifier: Modifier,
    icon: (@Composable () -> Unit)?,
) {
    val colors = RaftTheme.colors
    val isFocused = raftIsFocused(source)
    val focused = enabled && isFocused
    val isPressed = raftIsPressed(source)
    val ink =
        when {
            !enabled -> colors.foregroundDisabled
            selected || isPressed -> colors.foreground
            else -> colors.foregroundMuted
        }
    val line = colors.foreground
    Row(
        modifier
            .heightIn(min = 44.dp)
            .raftFocusRing(focused, 0.dp, colors)
            .drawWithContent {
                drawContent()
                // `data-active:shadow-[inset_0_-2px_0_var(--foreground)]`
                if (selected && enabled) {
                    val h = 2.dp.toPx()
                    drawRect(line, topLeft = Offset(0f, size.height - h), size = Size(size.width, h))
                }
            },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalRaftContentColor provides ink) {
            if (icon != null) Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { icon() }
            RaftText(text, style = RaftTheme.typography.callout.copy(fontWeight = FontWeight.SemiBold), color = ink, maxLines = 1, softWrap = false)
        }
    }
}

/**
 * The item of default [RaftTabs], [RaftSegmentedControl] and [RaftToggleGroup] (their
 * recipes are the same): 32dp, control radius, 12dp padding inside a 1.5dp edge that is
 * transparent at rest, 6dp gap, a `text-callout` semibold `foreground-muted` label; when
 * [active], the yellow sticker with a bold `sticker-ink` label. A [count] follows in
 * Space Mono `text-mono-meta`, regular weight.
 */
@Composable
internal fun RaftStickerItem(
    text: String,
    active: Boolean,
    enabled: Boolean,
    source: MutableInteractionSource,
    modifier: Modifier,
    icon: (@Composable () -> Unit)? = null,
    count: String? = null,
    iconsOnly: Boolean = false,
) {
    val colors = RaftTheme.colors
    val look = raftStickerItemLook(active, enabled, colors)
    val isPressed = raftIsPressed(source)
    val pressed = enabled && isPressed
    val isFocused = raftIsFocused(source)
    val focused = enabled && isFocused
    val typography = RaftTheme.typography
    if (iconsOnly && icon != null) {
        // raft-ui `data-icons-only`: a 32dp square with only its icon; the label (and count)
        // stays the accessible name, given by the caller's semantics.
        Box(
            modifier
                .size(32.dp)
                .raftSticker(look, RaftRadius.Control, pressed = pressed, focused = focused, colors = colors),
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(LocalRaftContentColor provides look.ink) {
                Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { icon() }
            }
        }
        return
    }
    Row(
        modifier
            // A minimum: a large font scale grows the item instead of clipping its label.
            .heightIn(min = 32.dp)
            .raftSticker(look, RaftRadius.Control, pressed = pressed, focused = focused, colors = colors)
            .padding(horizontal = 12.dp + RaftStroke.Control, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalRaftContentColor provides look.ink) {
            if (icon != null) Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { icon() }
            RaftText(
                text,
                style = typography.callout.copy(fontWeight = if (active && enabled) FontWeight.Bold else FontWeight.SemiBold),
                color = look.ink,
                maxLines = 1,
                softWrap = false,
            )
            if (count != null) RaftText(count, style = typography.monoMeta, color = look.ink, maxLines = 1, softWrap = false)
        }
    }
}

/**
 * The active sticker when [active], else a ghost in `foreground-muted`. A disabled item
 * is text only (`foreground-disabled`, no fill, no sticker).
 */
internal fun raftStickerItemLook(active: Boolean, enabled: Boolean, colors: RaftUiThemeColors): RaftStickerLook =
    if (enabled) {
        raftActiveStickerLook(active, colors, restingInk = colors.foregroundMuted)
    } else {
        raftActiveStickerLook(false, colors, restingInk = colors.foregroundDisabled)
    }

/**
 * The grey track of [RaftToggleGroup]: `fill-muted`, radius 11, 3dp padding (room for a
 * sticker's die-cut edge), items 3dp apart, hugging them. [RaftSegmentedControl] draws the
 * same track around a scroll.
 */
@Composable
private fun RaftTrack(modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier
            .background(RaftTheme.colors.fillMuted, RoundedCornerShape(11.dp))
            .padding(3.dp),
        // raft-ui 0.20: segments 3dp apart, the same as the track's inset.
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * raft-ui 0.8.0 SegmentedControl: two to five views of the same content (All / Mentions /
 * Unread), one always picked. A `fill-muted` track holding [RaftSegment]s; the picked
 * one is the yellow sticker. It hugs its segments; to fill a width pass
 * `Modifier.fillMaxWidth()` and give each segment `Modifier.weight(1f)`, on its own line
 * (not in a row beside other controls).
 *
 * The segments never shrink, but the track may shrink to its row (raft-ui 0.23
 * `min-w-0 max-w-full`, #721): in a row too narrow for it, after its neighbours have given
 * way, the segments scroll sideways inside the track, with no scrollbar, and the selected
 * one scrolls itself into view, instead of running past the row's end. That is the last
 * resort, not the layout: give it the room first. A full-width control whose weighted
 * segments don't fit scrolls the same way rather than squeezing them.
 */
@Composable
fun RaftSegmentedControl(
    modifier: Modifier = Modifier,
    /**
     * raft-ui `iconsOnlyBelow="auto"`: whenever the labelled segments don't fit the width the
     * control is given, every segment shows only its icon (a 32dp square), its label staying
     * its accessible name and showing in a tooltip; the labels come back once they fit. It is
     * always measured against the labelled layout, so it never flickers at the boundary. In a
     * row, leave the control free to shrink (`Modifier.weight(1f, fill = false)`). Set it only
     * when every [RaftSegment] has an `icon` or `iconsOnlyIcon`: one without keeps its label.
     */
    iconsOnlyWhenLabelsDontFit: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    if (!iconsOnlyWhenLabelsDontFit) {
        RaftSegmentedTrack(modifier, iconsOnly = false, content)
        return
    }
    SubcomposeLayout(modifier) { constraints ->
        // The labelled track, composed only to measure its width: never placed, and with no
        // semantics, so nothing reads or finds it twice.
        val labelledWidth =
            subcompose(RaftSegmentedSlot.Labelled) {
                RaftSegmentedTrack(Modifier.clearAndSetSemantics {}, iconsOnly = false, content)
            }.maxOfOrNull { it.maxIntrinsicWidth(constraints.maxHeight) } ?: 0
        val iconsOnly = raftSegmentIconsOnly(labelledWidth = labelledWidth, availableWidth = constraints.maxWidth, bounded = constraints.hasBoundedWidth)
        val placeables =
            subcompose(if (iconsOnly) RaftSegmentedSlot.IconsOnly else RaftSegmentedSlot.Shown) {
                RaftSegmentedTrack(Modifier, iconsOnly = iconsOnly, content)
            }.map { it.measure(constraints) }
        val width = placeables.maxOfOrNull { it.width } ?: constraints.minWidth
        val height = placeables.maxOfOrNull { it.height } ?: constraints.minHeight
        layout(width, height) { placeables.forEach { it.placeRelative(0, 0) } }
    }
}

private enum class RaftSegmentedSlot { Labelled, Shown, IconsOnly }

/**
 * raft-ui `labelsFit`: the segments show only their icons when the labelled track needs more
 * than the width it is given (half a pixel of rounding aside). Never with an unbounded width.
 */
internal fun raftSegmentIconsOnly(labelledWidth: Int, availableWidth: Int, bounded: Boolean): Boolean =
    bounded && labelledWidth > availableWidth

/** Whether the [RaftSegment]s of this track show only their icons. */
private val LocalRaftSegmentIconsOnly = staticCompositionLocalOf { false }

@Composable
private fun RaftSegmentedTrack(modifier: Modifier, iconsOnly: Boolean, content: @Composable RowScope.() -> Unit) {
    val scroll = rememberScrollState()
    val track = remember(scroll) { RaftTabStripScroll(scroll) }
    SideEffect { track.composed = true }
    val trackShape = RoundedCornerShape(11.dp)
    Row(
        modifier
            .raftBoundedTrack()
            .selectableGroup()
            .background(RaftTheme.colors.fillMuted, trackShape)
            // `data-overflowing:overflow-x-auto`: only a track that scrolls clips its
            // segments to its rounded corners (read in the layer, so no recomposition).
            // The scroll itself always clips sideways, which takes the outer 1dp of a
            // focus ring on the first or last segment.
            .graphicsLayer {
                shape = trackShape
                clip = scroll.maxValue > 0
            }
            .horizontalScroll(scroll)
            .raftWeightsKeepTheirWidth()
            .onPlaced { track.content = it }
            .padding(3.dp),
        // raft-ui 0.20: segments 3dp apart, the same as the track's inset.
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(
            LocalRaftSegmentTrackScroll provides track,
            LocalRaftSegmentIconsOnly provides iconsOnly,
        ) { content() }
    }
}

/** Set by [RaftSegmentedControl]: its selected segment scrolls itself into view. */
private val LocalRaftSegmentTrackScroll = staticCompositionLocalOf<RaftTabStripScroll?> { null }

/**
 * A scrolling track needs a bounded width: with none (in a horizontal scroll, or a row
 * measuring at its content width) the track is as wide as its segments and doesn't scroll.
 */
private fun Modifier.raftBoundedTrack(): Modifier =
    layout { measurable, constraints ->
        val bounded =
            if (constraints.hasBoundedWidth) {
                constraints
            } else {
                constraints.copy(maxWidth = measurable.maxIntrinsicWidth(constraints.maxHeight).coerceAtLeast(constraints.minWidth))
            }
        val placeable = measurable.measure(bounded)
        layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
    }

/**
 * Inside the scroll, a track given a width (`fillMaxWidth`) is at least as wide as its
 * segments at their own widths (a weighted row's max-intrinsic width gives each weighted
 * segment its own width), so weights share the room that's left and never squeeze one.
 */
private fun Modifier.raftWeightsKeepTheirWidth(): Modifier =
    layout { measurable, constraints ->
        val min = if (constraints.minWidth > 0) maxOf(constraints.minWidth, measurable.maxIntrinsicWidth(constraints.maxHeight)) else 0
        val placeable = measurable.measure(constraints.copy(minWidth = min))
        layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
    }

/**
 * One option of a [RaftSegmentedControl]: the sticker item (see [RaftTab]) with an
 * optional [count] in Space Mono after the Title Case label. A radio for accessibility.
 *
 * [iconsOnlyIcon] is the icon the segment shows alone when its control shows icons only
 * (`iconsOnlyWhenLabelsDontFit`), for a segment whose labelled form has no icon (raft-ui
 * `hidden in-data-icons-only:block`); without it, [icon] shows alone. Shown alone, the
 * segment is a 32dp square whose label (and count) stay its accessible name, and the label
 * shows in a tooltip.
 */
@Composable
fun RaftSegment(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: String? = null,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
    iconsOnlyIcon: (@Composable () -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val track = LocalRaftSegmentTrackScroll.current
    // Kept in view with the track's 3dp inset round it, as at rest; again once the track
    // starts to scroll (`[overflowing, value]`).
    val reveal = if (track != null) Modifier.raftRevealInStripWhen(selected, track, room = 3.dp, key = track.scroll.maxValue > 0) else Modifier
    val aloneIcon = iconsOnlyIcon ?: icon
    // Read unconditionally: a composable read inside `&&` gets no group of its own (#3356).
    val trackIconsOnly = LocalRaftSegmentIconsOnly.current
    val select = Modifier.selectable(selected, source, indication = null, enabled = enabled, role = Role.RadioButton, onClick = onClick)
    if (aloneIcon != null && trackIconsOnly) {
        val name = if (count != null) "$text $count" else text
        // The caller's modifier goes on the tooltip's outer box, where parent data (`weight`) holds.
        RaftTooltip(text, modifier) {
            RaftStickerItem(
                text,
                selected,
                enabled,
                source,
                reveal.then(select).semantics { contentDescription = name },
                icon = aloneIcon,
                count = count,
                iconsOnly = true,
            )
        }
        return
    }
    RaftStickerItem(
        text,
        selected,
        enabled,
        source,
        modifier.then(reveal).then(select),
        icon = icon,
        count = count,
    )
}

/**
 * raft-ui 0.8.0 ToggleGroup: buttons that each switch something on or off (any number
 * pressed, or at most one when the caller says so), on the segmented control's track.
 * A pressed [RaftToggleGroupItem] is the yellow sticker.
 */
@Composable
fun RaftToggleGroup(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    RaftTrack(modifier, content)
}

/**
 * One button of a [RaftToggleGroup]; [pressed] is its on state. A toggle button for
 * accessibility (`Role.Button` with an on / off state, like `aria-pressed`), not a switch.
 */
@Composable
fun RaftToggleGroupItem(
    text: String,
    pressed: Boolean,
    onPressedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    RaftStickerItem(
        text,
        pressed,
        enabled,
        source,
        modifier.toggleable(pressed, source, indication = null, enabled = enabled, role = Role.Button, onValueChange = onPressedChange),
        icon = icon,
    )
}
