package build.raft.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLayoutDirection
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
import build.raft.designsystem.theme.LocalRaftSurfaceColor
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
 * a 1dp `line-seam` ring and `shadow-float`, as wide as its widest row but at least
 * [minWidth] (240dp for a menu), or exactly [width] (a select's list: its trigger's
 * width), 6dp padding round the [content] rows. [header] sits flush above the padded
 * rows, edge to edge (a [RaftMenuSearchField]). Provides `layer-popover` as the surface.
 *
 * Without a [width], the surface sizes itself by `IntrinsicSize.Max`, which asks every
 * row for its intrinsic width. Content that can't answer that crashes at measure time:
 * anything built on subcomposition (`SubcomposeLayout`, `BoxWithConstraints`, a
 * [RaftDescriptionList]) or a lazy list (`LazyColumn`). Put only plain rows in it
 * (menu items, labels, separators, text), or pass a fixed [width].
 */
@Composable
fun RaftMenuSurface(
    modifier: Modifier = Modifier,
    minWidth: Dp = 240.dp,
    width: Dp? = null,
    header: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = RaftTheme.colors
    CompositionLocalProvider(LocalRaftContentColor provides colors.foreground, LocalRaftSurfaceColor provides colors.layerPopover) {
        Column(
            modifier
                // Material DropdownMenu does the same: the popup hugs its rows instead of
                // taking the window's width (rows fill the column, so they measure by intrinsics).
                .then(if (width != null) Modifier.width(width) else Modifier.widthIn(min = minWidth).width(IntrinsicSize.Max))
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
 *
 * In a [RaftDropdownMenu] the rows take part in its keyboard navigation (arrows, Home /
 * End, the first letter of a row).
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
    val nav = LocalRaftMenuNav.current
    val entry = remember(nav) { RaftMenuNav.Entry() }
    entry.text = text
    entry.enabled = enabled
    if (nav != null) {
        DisposableEffect(nav, entry) {
            nav.entries += entry
            onDispose { nav.entries -= entry }
        }
    }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .then(
                if (nav != null) {
                    Modifier
                        .focusRequester(entry.requester)
                        .onFocusChanged { entry.focused = it.isFocused }
                        .onGloballyPositioned { entry.top = it.positionInRoot().y }
                } else {
                    Modifier
                },
            )
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
    val color = RaftTheme.colors.lineHairline
    Box(
        modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .height(RaftStroke.Hairline)
            // `-mx-1.5`: drawn through the surface's 6dp padding to both edges. Drawn, not
            // measured, so the rule never asks for width (the popup hugs its rows).
            .drawBehind {
                val bleed = 6.dp.toPx()
                drawRect(color, topLeft = Offset(-bleed, 0f), size = Size(size.width + bleed * 2, size.height))
            },
    )
}

/**
 * The search row at the top of a selection popover ([RaftMenuSurface] `header`): 40dp,
 * 12dp in, a 16dp `foreground-hint` search glyph, the field type (16/24, so iOS doesn't
 * zoom into it) with a
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
    val style = RaftTheme.typography.bodyLarge.copy(color = colors.foreground)
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
                        .heightIn(min = 40.dp)
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
 * the safe area of the window (clear of the status bar, the home indicator and the
 * keyboard). Tapping outside or Back calls [onDismissRequest]. Fill it with
 * [RaftMenuItem]s, [RaftMenuLabel]s and [RaftMenuSeparator]s; a row's `onClick` should
 * also close the menu.
 *
 * Keyboard: opened from the keyboard, the first row takes focus; the arrows move through
 * the rows (wrapping), Home / End go to the first / last, a letter to the next row
 * starting with it, Enter picks, Escape closes. On closing from the keyboard, focus goes
 * back to [returnFocusTo] (the trigger's `Modifier.focusRequester`).
 *
 * Acting on one thing goes in a menu; picking a value is a [RaftSelect].
 *
 * The rows go in a scrolling column inside a [RaftMenuSurface]: never a lazy list (the
 * rows already scroll), and without a [width] nothing subcomposed either (see
 * [RaftMenuSurface]).
 */
@Composable
fun RaftDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    minWidth: Dp = 240.dp,
    width: Dp? = null,
    offset: Dp = 6.dp,
    returnFocusTo: FocusRequester? = null,
    header: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val inputModes = LocalInputModeManager.current
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        if (!expanded && wasOpen && inputModes.inputMode == InputMode.Keyboard) {
            returnFocusTo?.let { runCatching { it.requestFocus() } }
        }
        wasOpen = expanded
    }
    if (!expanded) return
    val density = LocalDensity.current
    val safe = WindowInsets.safeDrawing
    val layoutDirection = LocalLayoutDirection.current
    val area =
        RaftSafeArea(
            left = safe.getLeft(density, layoutDirection),
            top = safe.getTop(density),
            right = safe.getRight(density, layoutDirection),
            bottom = safe.getBottom(density),
        )
    val provider =
        remember(density, offset, area) {
            with(density) {
                RaftMenuPositionProvider(
                    gap = offset.roundToPx(),
                    windowMargin = 8.dp.roundToPx(),
                    insets = RaftPopupMargins(RaftPopupInsets.Horizontal.roundToPx(), RaftPopupInsets.Top.roundToPx(), RaftPopupInsets.Bottom.roundToPx()),
                    safeArea = area,
                )
            }
        }
    val dismiss by rememberUpdatedState(onDismissRequest)
    val nav = remember { RaftMenuNav() }
    Popup(popupPositionProvider = provider, onDismissRequest = { dismiss() }, properties = PopupProperties(focusable = true)) {
        LaunchedEffect(Unit) {
            if (inputModes.inputMode == InputMode.Keyboard) {
                // The rows attach their focus targets in the first frame.
                withFrameNanos { }
                nav.focus(nav.ordered().firstOrNull())
            }
        }
        // The shadow's room is part of the popup window; a tap there is outside the menu.
        Box(
            Modifier
                .pointerInput(Unit) { detectTapGestures { dismiss() } }
                .padding(
                    start = RaftPopupInsets.Horizontal,
                    end = RaftPopupInsets.Horizontal,
                    top = RaftPopupInsets.Top,
                    bottom = RaftPopupInsets.Bottom,
                ),
        ) {
            CompositionLocalProvider(LocalRaftMenuNav provides nav) {
                RaftMenuSurface(
                    // Swallows taps on the surface's own padding.
                    modifier
                        .heightIn(max = 480.dp)
                        .pointerInput(Unit) { detectTapGestures { } }
                        .onPreviewKeyEvent { nav.onKey(it) },
                    minWidth = minWidth,
                    width = width,
                    header = header,
                ) {
                    Column(Modifier.verticalScroll(rememberScrollState()), content = content)
                }
            }
        }
    }
}

/** The rows of an open [RaftDropdownMenu], for its keyboard navigation. */
internal class RaftMenuNav {
    class Entry {
        val requester = FocusRequester()
        var text = ""
        var enabled = true
        var focused = false
        var top = 0f
    }

    val entries = mutableListOf<Entry>()

    /** The rows that can take focus, top to bottom. */
    fun ordered(): List<Entry> = entries.filter { it.enabled }.sortedBy { it.top }

    fun focus(entry: Entry?): Boolean {
        if (entry == null) return false
        return runCatching { entry.requester.requestFocus() }.isSuccess
    }

    fun onKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val rows = ordered()
        if (rows.isEmpty()) return false
        val at = rows.indexOfFirst { it.focused }
        val target = raftMenuKeyTarget(rows.map { it.text }, at, event.key, event.utf16CodePoint) ?: return false
        return focus(rows[target])
    }
}

/**
 * Where a key moves the focus in a menu of [rows] (labels) with row [at] focused (-1: none,
 * the search field may have it): the index to focus, or null to let the key through.
 * Down / Up wrap; Home / End and type-ahead only act from a row, so they still edit the
 * search field.
 */
internal fun raftMenuKeyTarget(rows: List<String>, at: Int, key: Key, codePoint: Int): Int? {
    if (rows.isEmpty()) return null
    val last = rows.size - 1
    return when (key) {
        Key.DirectionDown -> if (at < 0 || at == last) 0 else at + 1
        Key.DirectionUp -> if (at <= 0) last else at - 1
        Key.MoveHome -> if (at < 0) null else 0
        Key.MoveEnd -> if (at < 0) null else last
        else -> {
            if (at < 0 || codePoint <= 0) return null
            val typed = codePoint.toChar()
            if (!typed.isLetterOrDigit()) return null
            // The next row after the focused one starting with the letter, wrapping.
            (1..rows.size).map { (at + it) % rows.size }.firstOrNull { rows[it].startsWith(typed, ignoreCase = true) }
        }
    }
}

internal val LocalRaftMenuNav = staticCompositionLocalOf<RaftMenuNav?> { null }

/** The window's safe area (system bars, cutouts, the keyboard), in px from each edge. */
@Immutable
internal data class RaftSafeArea(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0)

/** The transparent room round a popup's surface, in px. */
@Immutable
internal data class RaftPopupMargins(val horizontal: Int, val top: Int, val bottom: Int)

/**
 * Where a menu popup goes: its surface's start edge on the anchor's, [gap] below it, or
 * above when it doesn't fit below and there is more room above; then moved to stay
 * [windowMargin] inside the window's [safeArea] (clear of the bars and the keyboard).
 * [insets] is the room the popup keeps round its surface.
 */
internal fun raftMenuPosition(
    anchor: IntRect,
    window: IntSize,
    popup: IntSize,
    gap: Int,
    windowMargin: Int,
    insets: RaftPopupMargins,
    rtl: Boolean = false,
    safeArea: RaftSafeArea = RaftSafeArea(),
): IntOffset {
    val surfaceWidth = popup.width - insets.horizontal * 2
    val surfaceHeight = popup.height - insets.top - insets.bottom
    val minX = safeArea.left + windowMargin
    val maxX = window.width - safeArea.right - windowMargin
    val minY = safeArea.top + windowMargin
    val maxY = window.height - safeArea.bottom - windowMargin
    var left = if (rtl) anchor.right - surfaceWidth else anchor.left
    left = left.coerceAtMost(maxX - surfaceWidth).coerceAtLeast(minX)
    val below = anchor.bottom + gap
    val roomBelow = maxY - below
    val roomAbove = anchor.top - gap - minY
    var top = if (surfaceHeight > roomBelow && roomAbove > roomBelow) anchor.top - gap - surfaceHeight else below
    top = top.coerceAtMost(maxY - surfaceHeight).coerceAtLeast(minY)
    return IntOffset(left - insets.horizontal, top - insets.top)
}

private class RaftMenuPositionProvider(
    private val gap: Int,
    private val windowMargin: Int,
    private val insets: RaftPopupMargins,
    private val safeArea: RaftSafeArea,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = raftMenuPosition(anchorBounds, windowSize, popupContentSize, gap, windowMargin, insets, layoutDirection == LayoutDirection.Rtl, safeArea)
}
