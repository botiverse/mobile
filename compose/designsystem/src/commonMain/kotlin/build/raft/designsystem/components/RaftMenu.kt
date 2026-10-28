package build.raft.designsystem.components

import build.raft.designsystem.gesture.RaftBlockBackSwipe
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
import androidx.compose.foundation.text.input.TextFieldLineLimits
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
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
    RaftPopoverChrome(
        modifier
            // Material DropdownMenu does the same: the popup hugs its rows instead of
            // taking the window's width (rows fill the column, so they measure by intrinsics).
            .then(if (width != null) Modifier.width(width) else Modifier.widthIn(min = minWidth).width(IntrinsicSize.Max)),
    ) {
        header?.invoke()
        // raft-ui 0.20 "inside a frame": the highlighted item sits 4dp inside the popup.
        Column(Modifier.padding(4.dp), content = content)
    }
}

/**
 * The popover layer every popup surface shares (a menu, a select list, a popover): a
 * column on `layer-popover` with the container radius, a 1dp `line-seam` ring and
 * `shadow-float`, its content clipped; provides `foreground` as the content colour and
 * `layer-popover` as the surface.
 */
@Composable
internal fun RaftPopoverChrome(
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = RaftTheme.colors
    CompositionLocalProvider(LocalRaftContentColor provides colors.foreground, LocalRaftSurfaceColor provides colors.layerPopover) {
        Column(
            modifier.raftOverlaySurface(colors.layerPopover, colors.lineSeam, RaftTheme.isDark, RaftRadius.Container),
            content = content,
        )
    }
}

/** Set by a [RaftSelect] inside a [RaftField]: its options take the field type (16/24). */
internal val LocalRaftMenuFieldType = staticCompositionLocalOf { false }

/**
 * One row of a menu or a select list: 36dp minimum, control radius, 10dp padding, 10dp
 * gap, `text-callout` medium `foreground` (Title Case: "Mark as Read").
 *
 * - Highlight (pressed, or keyboard focus): `brand-soft`, no border.
 * - [checked], the current value: bold, with a 16dp check at the end. No fill.
 * - [leadingIcon]: 16dp in `foreground-muted`. [shortcut]: Space Mono `text-mono-meta`
 *   `foreground-hint` at the end ("⌘K").
 * - Disabled: `foreground-disabled`, no highlight.
 * - [divider]: a 1dp `line-hairline` along the row's top edge, inside its 36dp (CSS
 *   `border-t` on a border-box row, as the web's menu rows that start a section inside a
 *   flush card): the row stays 36dp and its content centres in the 35dp under the line.
 *   Between the groups of a popup use [RaftMenuSeparator] instead.
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
    divider: Boolean = false,
    interactionSource: MutableInteractionSource? = null,
) {
    val colors = RaftTheme.colors
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val isPressed = raftIsPressed(source)
    val isFocused = raftIsFocused(source)
    val highlighted = enabled && (isPressed || isFocused)
    val ink = if (enabled) colors.foreground else colors.foregroundDisabled
    val iconInk = if (enabled) colors.foregroundMuted else colors.foregroundDisabled
    // raft-ui 0.20: an item embedded in the popup's frame wears radius-chip (6dp).
    val shape = RoundedCornerShape(RaftRadius.Chip)
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
    // A field Select's options share the field type (select.tsx `chrome: field`:
    // `text-field font-field` on the item text, so the chosen one stays 400).
    val labelStyle =
        if (LocalRaftMenuFieldType.current) {
            RaftTheme.typography.field
        } else {
            RaftTheme.typography.callout.copy(fontWeight = if (checked) FontWeight.Bold else FontWeight.Medium)
        }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = RaftOptionHeight)
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
            // Drawn over the highlight, as a CSS border paints over the background.
            .then(if (divider) Modifier.topHairline(colors.lineHairline) else Modifier)
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { if (checked) selected = true }
            .then(if (divider) Modifier.padding(top = RaftStroke.Hairline) else Modifier)
            .padding(horizontal = 10.dp, vertical = raftOptionInset(labelStyle)),
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
            style = labelStyle,
            color = ink,
            // raft-ui 0.13: a long option wraps to two lines, then clamps.
            maxLines = 2,
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
            .padding(vertical = 4.dp)
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
) = RaftSearchRow(value, onValueChange, modifier, placeholder, interactionSource)

/**
 * [RaftMenuSearchField] with the room before the glyph and after the text as [inset]
 * (12dp in a popup; 16dp in a sheet, on the page margin) and an optional
 * [focusRequester] on the input itself (a sheet focuses its search row when it opens).
 */
@Composable
internal fun RaftSearchRow(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    interactionSource: MutableInteractionSource? = null,
    inset: Dp = 12.dp,
    focusRequester: FocusRequester? = null,
) {
    val colors = RaftTheme.colors
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val style = RaftTheme.typography.field.copy(color = colors.foreground)
    Column(modifier.fillMaxWidth()) {
        val state = rememberRaftTextFieldState(value, onValueChange, traceName = "RaftSearchRow")
        BasicTextField(
            state = state,
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = rememberRaftTextInputTraceTransformation("RaftSearchRow"),
            textStyle = style,
            cursorBrush = SolidColor(colors.foreground),
            interactionSource = source,
            modifier = Modifier.fillMaxWidth().then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
            decorator = { inner ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 40.dp)
                        .raftInsetFocusOutline(raftIsFocused(source), colors.foreground)
                        .padding(horizontal = inset),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RaftGlyphIcon(RaftGlyph.Search, size = 16.dp, color = colors.foregroundHint)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (state.text.isEmpty() && placeholder != null) {
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
 * keyboard), and no taller than that safe area less 8dp each side (or 480dp): the rows
 * scroll. Tapping outside or Back calls [onDismissRequest]. Fill it with
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
    val frame = LocalRaftFrameInsets.current
    val area =
        RaftSafeArea(
            left = safe.getLeft(density, layoutDirection),
            top = safe.getTop(density),
            right = safe.getRight(density, layoutDirection),
            bottom = safe.getBottom(density),
        ).clearOf(frame, density)
    val windowMargin = with(density) { 8.dp.roundToPx() }
    val insets =
        with(density) {
            RaftPopupMargins(RaftPopupInsets.Horizontal.roundToPx(), RaftPopupInsets.Top.roundToPx(), RaftPopupInsets.Bottom.roundToPx())
        }
    val provider =
        remember(density, offset, area) {
            RaftMenuPositionProvider(
                gap = with(density) { offset.roundToPx() },
                windowMargin = windowMargin,
                insets = insets,
                safeArea = area,
            )
        }
    val dismiss by rememberUpdatedState(onDismissRequest)
    val nav = remember { RaftMenuNav() }
    // A right swipe must not pop the page underneath an open menu.
    RaftBlockBackSwipe()
    Popup(popupPositionProvider = provider, onDismissRequest = { dismiss() }, properties = PopupProperties(focusable = true)) {
        // Its own window on Android: keep the host's bar icons while it holds focus.
        RaftPopupWindowPolicy()
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
                .raftMenuHeightCap(windowMargin, insets, area)
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
                        // Escape closes the menu here, not only through the platform popup:
                        // since CMP 1.10 a skiko popup closes on Escape only where the host feeds
                        // the key into its back-navigation input (iOS, web, desktop windows).
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                                dismiss()
                                true
                            } else {
                                nav.onKey(event)
                            }
                        },
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

/**
 * The safe area grown to cover the host's [frame] insets: the menu's 8dp window margin then keeps
 * it [RaftFrameInsetsDefaults.PopupGap] from each inset too (raft-ui `usePopupCollisionPadding`).
 */
internal fun RaftSafeArea.clearOf(frame: RaftFrameInsets, density: Density): RaftSafeArea {
    if (frame.isZero) return this
    return with(density) {
        copy(
            left = maxOf(left, frame.left.coerceAtLeast(0.dp).roundToPx()),
            top = maxOf(top, frame.top.coerceAtLeast(0.dp).roundToPx()),
            right = maxOf(right, frame.right.coerceAtLeast(0.dp).roundToPx()),
        )
    }
}

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
    val (minY, maxY) = raftMenuVerticalBounds(window.height, windowMargin, insets, safeArea)
    var left = if (rtl) anchor.right - surfaceWidth else anchor.left
    left = left.coerceAtMost(maxX - surfaceWidth).coerceAtLeast(minX)
    val below = anchor.bottom + gap
    val roomBelow = maxY - below
    val roomAbove = anchor.top - gap - minY
    var top = if (surfaceHeight > roomBelow && roomAbove > roomBelow) anchor.top - gap - surfaceHeight else below
    top = top.coerceAtMost(maxY - surfaceHeight).coerceAtLeast(minY)
    return IntOffset(left - insets.horizontal, top - insets.top)
}

/**
 * The band a menu's surface may take, top to bottom in px: [windowMargin] inside the
 * [safeArea], and far enough from the window's edges that the shadow room ([insets])
 * stays inside the window.
 */
private fun raftMenuVerticalBounds(windowHeight: Int, windowMargin: Int, insets: RaftPopupMargins, safeArea: RaftSafeArea): Pair<Int, Int> =
    maxOf(safeArea.top + windowMargin, insets.top) to minOf(windowHeight - safeArea.bottom - windowMargin, windowHeight - insets.bottom)

/**
 * The tallest a menu's surface may be in a window [windowHeight] px tall: the band of
 * [raftMenuVerticalBounds] (the safe area less [windowMargin] each side, so never under
 * the keyboard or the bars). The surface's own 480dp cap applies inside that.
 */
internal fun raftMenuMaxSurfaceHeight(windowHeight: Int, windowMargin: Int, insets: RaftPopupMargins, safeArea: RaftSafeArea): Int {
    val (minY, maxY) = raftMenuVerticalBounds(windowHeight, windowMargin, insets, safeArea)
    return (maxY - minY).coerceAtLeast(0)
}

/**
 * Caps a menu popup (its surface plus the shadow room [insets]) so the surface is no
 * taller than [raftMenuMaxSurfaceHeight] of the window it is measured against. The popup
 * is measured against the whole window, keyboard and bars included; capped, a long menu
 * scrolls its rows instead of running under the keyboard.
 *
 * Assumption, unverified on devices: this (and [raftMenuPosition]) takes the popup's
 * constraints / window size to be the whole window and subtracts the `safeDrawing` insets
 * itself. On Android, `PopupLayout` may already measure against the visible display frame
 * (without the status bar and the IME), so the insets could be subtracted twice and a long
 * menu capped too short or placed too low. Check on an Android and an iOS device before
 * changing the math (README, TODO "Device check: popup insets").
 */
internal fun Modifier.raftMenuHeightCap(windowMargin: Int, insets: RaftPopupMargins, safeArea: RaftSafeArea): Modifier =
    layout { measurable, constraints ->
        val capped =
            if (constraints.hasBoundedHeight) {
                val surface = raftMenuMaxSurfaceHeight(constraints.maxHeight, windowMargin, insets, safeArea)
                constraints.copy(maxHeight = (surface + insets.top + insets.bottom).coerceIn(constraints.minHeight, constraints.maxHeight))
            } else {
                constraints
            }
        val placeable = measurable.measure(capped)
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
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


/**
 * raft-ui `py-option-inset`: the space above and below an option's text, what a 36dp row leaves
 * around one line of the option's type ((36 - line height) / 2: 8dp around a 20 line, 6dp around
 * 24). One line is exactly 36dp; a second line adds its own height and nothing else.
 */
@Composable
internal fun raftOptionInset(style: TextStyle): Dp {
    val density = LocalDensity.current
    val line = with(density) { style.lineHeight.toDp() }
    return ((RaftOptionHeight - line) / 2).coerceAtLeast(0.dp)
}

internal val RaftOptionHeight = 36.dp
