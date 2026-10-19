package build.raft.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke

// Menus, and the list of a Select (dropdown-menu.tsx, select.tsx). States > Selected:
// the highlighted row (pressed, or reached with the keyboard) is `brand-soft` with no
// border; the current value is bold with a check, no fill. A row that touches the popup's
// edge (the search row) takes the inset focus outline instead of the ring.

/** Room around a popup for its `shadow-float`, so a platform popup window doesn't clip it. */
internal object RaftPopupInsets {
    val Horizontal = 16.dp
    val Top = 8.dp
    val Bottom = 24.dp
}

/**
 * raft-ui 0.8.0 DropdownMenu / Select popup surface: `layer-popover`, container radius,
 * a 1dp `line-seam` ring and `shadow-float`, at least [minWidth] wide (240dp for a menu,
 * 220dp for a select's list), 6dp padding round the [content] rows. [header] sits flush
 * above the padded rows, edge to edge (a [RaftMenuSearchField]).
 */
@Composable
fun RaftMenuSurface(
    modifier: Modifier = Modifier,
    minWidth: Dp = 240.dp,
    header: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = RaftTheme.colors
    CompositionLocalProvider(LocalRaftContentColor provides colors.foreground) {
        Column(
            modifier
                .widthIn(min = minWidth)
                .raftOverlaySurface(colors.layerPopover, colors.lineSeam, RaftTheme.isDark, RaftRadius.Container),
        ) {
            header?.invoke()
            Column(Modifier.padding(6.dp), content = content)
        }
    }
}

/**
 * One row of a menu or a select list: 36dp minimum, control radius, 10dp padding, 10dp
 * gap, `text-callout` medium `foreground` (Title Case: "Mark as Read").
 *
 * - Highlight (pressed, or keyboard focus): `brand-soft`, no border.
 * - [checked], the current value: bold, with a 16dp check at the end. No fill.
 * - [leadingIcon]: 16dp in `foreground-muted`. [shortcut]: Space Mono `text-mono-meta`
 *   `foreground-hint` at the end ("⌘K").
 * - Disabled: `foreground-disabled`, no highlight.
 */
@Composable
fun RaftMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    checked: Boolean = false,
    leadingIcon: (@Composable () -> Unit)? = null,
    shortcut: String? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    val colors = RaftTheme.colors
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val highlighted = enabled && (raftIsPressed(source) || raftIsFocused(source))
    val ink = if (enabled) colors.foreground else colors.foregroundDisabled
    val iconInk = if (enabled) colors.foregroundMuted else colors.foregroundDisabled
    val shape = RoundedCornerShape(RaftRadius.Control)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .background(if (highlighted) colors.brandSoft else Color.Transparent, shape)
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { if (checked) selected = true }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            CompositionLocalProvider(LocalRaftContentColor provides iconInk) {
                Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { leadingIcon() }
            }
        }
        RaftText(
            text,
            Modifier.weight(1f),
            style = RaftTheme.typography.body.copy(fontWeight = if (checked) FontWeight.Bold else FontWeight.Medium),
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (shortcut != null) {
            RaftText(shortcut, style = RaftTheme.typography.monoMeta, color = if (enabled) colors.foregroundHint else colors.foregroundDisabled, maxLines = 1)
        }
        if (checked) RaftGlyphIcon(RaftGlyph.Check, size = 16.dp, color = ink)
    }
}

/** A group label in a menu: `text-caption` bold `foreground-hint`, 10dp in, 8dp above, 4dp below. */
@Composable
fun RaftMenuLabel(text: String, modifier: Modifier = Modifier) {
    RaftText(
        text,
        modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 4.dp),
        style = RaftTheme.typography.caption.copy(fontWeight = FontWeight.Bold),
        color = RaftTheme.colors.foregroundHint,
    )
}

/** A `line-hairline` rule between groups of rows, 6dp above and below, across the whole popup. */
@Composable
fun RaftMenuSeparator(modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(vertical = 6.dp)
            // `-mx-1.5`: through the surface's 6dp padding to both edges.
            .layout { measurable, constraints ->
                val bleed = 6.dp.roundToPx()
                val width = constraints.maxWidth + bleed * 2
                val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                layout(constraints.maxWidth, placeable.height) { placeable.place(-bleed, 0) }
            }
            .height(RaftStroke.Hairline)
            .background(RaftTheme.colors.lineHairline),
    )
}

/**
 * The search row at the top of a selection popover ([RaftMenuSurface] `header`): 40dp,
 * 12dp in, a 16dp `foreground-hint` search glyph, `text-callout` input with a
 * `foreground-hint` [placeholder], and a `line-hairline` under it. It touches the
 * popup's edges, so focus (any focus: it is an input) draws the inset 2dp ink outline,
 * not the outer ring, which the popup would clip.
 */
@Composable
fun RaftMenuSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    val colors = RaftTheme.colors
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val style = RaftTheme.typography.body.copy(color = colors.foreground)
    Column(modifier.fillMaxWidth()) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(colors.foreground),
            interactionSource = source,
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .raftInsetFocusOutline(raftIsFocused(source), colors.foreground)
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RaftGlyphIcon(RaftGlyph.Search, size = 16.dp, color = colors.foregroundHint)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty() && placeholder != null) {
                            RaftText(placeholder, style = style, color = colors.foregroundHint, maxLines = 1)
                        }
                        inner()
                    }
                }
            },
        )
        Box(Modifier.fillMaxWidth().height(RaftStroke.Hairline).background(colors.lineHairline))
    }
}

/**
 * raft-ui 0.8.0 DropdownMenu: a [RaftMenuSurface] in a popup under its anchor (the
 * composable this is placed next to, usually in the same `Box` as the trigger), start
 * aligned, 6dp below; flipped above when there is more room there, and kept 8dp inside
 * the window. Tapping outside or Back calls [onDismissRequest]. Fill it with
 * [RaftMenuItem]s, [RaftMenuLabel]s and [RaftMenuSeparator]s; a row's `onClick` should
 * also close the menu.
 *
 * Acting on one thing goes in a menu; picking a value is a [RaftSelect].
 */
@Composable
fun RaftDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    minWidth: Dp = 240.dp,
    offset: Dp = 6.dp,
    header: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) return
    val density = LocalDensity.current
    val provider =
        remember(density, offset) {
            with(density) {
                RaftMenuPositionProvider(
                    gap = offset.roundToPx(),
                    windowMargin = 8.dp.roundToPx(),
                    insets = RaftPopupMargins(RaftPopupInsets.Horizontal.roundToPx(), RaftPopupInsets.Top.roundToPx(), RaftPopupInsets.Bottom.roundToPx()),
                )
            }
        }
    Popup(popupPositionProvider = provider, onDismissRequest = onDismissRequest, properties = PopupProperties(focusable = true)) {
        // The shadow's room is part of the popup window; a tap there is outside the menu.
        Box(
            Modifier
                .pointerInput(onDismissRequest) { detectTapGestures { onDismissRequest() } }
                .padding(
                    start = RaftPopupInsets.Horizontal,
                    end = RaftPopupInsets.Horizontal,
                    top = RaftPopupInsets.Top,
                    bottom = RaftPopupInsets.Bottom,
                ),
        ) {
            RaftMenuSurface(
                // Swallows taps on the surface's own padding.
                modifier.heightIn(max = 480.dp).pointerInput(Unit) { detectTapGestures { } },
                minWidth = minWidth,
                header = header,
            ) {
                Column(Modifier.verticalScroll(rememberScrollState()), content = content)
            }
        }
    }
}

/** The transparent room round a popup's surface, in px. */
@Immutable
internal data class RaftPopupMargins(val horizontal: Int, val top: Int, val bottom: Int)

/**
 * Where a menu popup goes: its surface's start edge on the anchor's, [gap] below it, or
 * above when it doesn't fit below and there is more room above; then moved to stay
 * [windowMargin] inside the window. [insets] is the room the popup keeps round its surface.
 */
internal fun raftMenuPosition(
    anchor: IntRect,
    window: IntSize,
    popup: IntSize,
    gap: Int,
    windowMargin: Int,
    insets: RaftPopupMargins,
    rtl: Boolean = false,
): IntOffset {
    val surfaceWidth = popup.width - insets.horizontal * 2
    val surfaceHeight = popup.height - insets.top - insets.bottom
    var left = if (rtl) anchor.right - surfaceWidth else anchor.left
    left = left.coerceAtMost(window.width - windowMargin - surfaceWidth).coerceAtLeast(windowMargin)
    val below = anchor.bottom + gap
    val roomBelow = window.height - windowMargin - below
    val roomAbove = anchor.top - gap - windowMargin
    var top = if (surfaceHeight > roomBelow && roomAbove > roomBelow) anchor.top - gap - surfaceHeight else below
    top = top.coerceAtMost(window.height - windowMargin - surfaceHeight).coerceAtLeast(windowMargin)
    return IntOffset(left - insets.horizontal, top - insets.top)
}

private class RaftMenuPositionProvider(
    private val gap: Int,
    private val windowMargin: Int,
    private val insets: RaftPopupMargins,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = raftMenuPosition(anchorBounds, windowSize, popupContentSize, gap, windowMargin, insets, layoutDirection == LayoutDirection.Rtl)
}
