package build.raft.designsystem.components

import androidx.compose.runtime.ReadOnlyComposable
import build.raft.designsystem.focus.LocalRaftAutofocusGuard
import build.raft.designsystem.focus.LocalRaftFocusInteractionObserverInstalled
import build.raft.designsystem.focus.raftObserveFocusInteractions
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import build.raft.designsystem.theme.LocalRaftComponentTokens
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.Stable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.LocalRaftSurfaceColor
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftMenuMetrics
import build.raft.designsystem.tokens.RaftStroke
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
 * raft-ui DropdownMenu / Select popup surface: `layer-popover`, container radius,
 * a 1dp `line-seam` ring and `shadow-float`, as wide as its widest row but at least
 * [minWidth] (240dp for a menu), or exactly [width] (a select's list: its trigger's
 * width), [RaftMenuMetrics.Padding] (4dp, `p-1`) round the [content] rows. [header] sits flush above the padded
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
    minWidth: Dp = RaftMenuMetrics.MinWidth,
    width: Dp? = null,
    header: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    RaftMenuSurfaceFrame(modifier, minWidth, width, header, scroll = null, content = content)
}

/**
 * [RaftMenuSurface] whose rows scroll in [scroll] (raft-ui 0.106.0 `MenuScroll`, a
 * `[data-slot=dropdown-menu-scroll]` ScrollArea): the 4dp padding is on the scrolled content,
 * not round the scroller, so a cut row runs to the surface's edge instead of stopping 4dp short
 * of it, and a separator still meets the edge; while the rows overflow their edges fade
 * ([raftScrollFadeY], up to 24dp) and the overlay scrollbar shows while they scroll. A menu
 * that fits draws as before. [header] stays above the scroller.
 */
@Composable
internal fun RaftMenuSurfaceFrame(
    modifier: Modifier,
    minWidth: Dp,
    width: Dp?,
    header: (@Composable () -> Unit)?,
    scroll: ScrollState?,
    content: @Composable ColumnScope.() -> Unit,
) {
    RaftPopoverChrome(
        modifier
            // Material DropdownMenu does the same: the popup hugs its rows instead of
            // taking the window's width (rows fill the column, so they measure by intrinsics).
            .then(if (width != null) Modifier.width(width) else Modifier.widthIn(min = minWidth).width(IntrinsicSize.Max)),
    ) {
        header?.invoke()
        if (scroll == null) {
            // raft-ui "inside a frame": the highlighted item sits 4dp inside the popup.
            Column(Modifier.padding(RaftMenuMetrics.Padding), content = content)
        } else {
            Column(
                // Measured after the header, so it takes what the surface's cap leaves (`min-h-0`).
                Modifier
                    .raftScrollFadeY(scroll)
                    .raftScrollbar(scroll)
                    .verticalScroll(scroll)
                    // `contentClassName="flex flex-col p-1"`.
                    .padding(RaftMenuMetrics.Padding),
                content = content,
            )
        }
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
            modifier.raftOverlaySurface(colors.layerPopover, colors.lineSeam, RaftTheme.isDark, RaftMenuMetrics.Radius),
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
 * - [trailingIcon]: 16dp at the end, in `foreground-muted` (the leaving mark ↗ of a link item).
 * - [description]: a second line under the label, `text-footnote` regular `foreground-muted`,
 *   wrapping (why an item is disabled: an item can't carry a tooltip).
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
    trailingIcon: (@Composable () -> Unit)? = null,
    description: String? = null,
) {
    val colors = RaftTheme.colors
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val isFocused = raftIsFocused(source)
    val isHovered = raftIsHovered(source)
    // `hover:bg-brand-soft data-highlighted:bg-brand-soft`: pointer, press and keyboard alike.
    val overlay = rememberRaftStateOverlay(source, RaftHoverTier.Highlight, enabled = enabled, highlighted = isFocused)
    val ink = if (enabled) colors.foreground else colors.foregroundDisabled
    val iconInk = if (enabled) colors.foregroundMuted else colors.foregroundDisabled
    // raft-ui 0.20: an item embedded in the popup's frame wears radius-chip (6dp).
    val shape = RoundedCornerShape(RaftMenuMetrics.ItemRadius)
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
    RaftMenuPointerCursor(nav, entry, enabled && isHovered)
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
                        .raftMenuKeepClearOfFade(entry)
                        .onGloballyPositioned { entry.top = it.positionInRoot().y }
                } else {
                    Modifier
                },
            )
            .raftStateOverlay(overlay, shape)
            // Drawn over the highlight, as a CSS border paints over the background.
            .then(if (divider) Modifier.topHairline(colors.lineHairline) else Modifier)
            .raftPointerHover(source)
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { if (checked) selected = true }
            .then(if (divider) Modifier.padding(top = RaftStroke.Hairline) else Modifier)
            .padding(horizontal = RaftMenuMetrics.ItemPaddingHorizontal, vertical = raftOptionInset(labelStyle)),
        horizontalArrangement = Arrangement.spacedBy(RaftMenuMetrics.ItemGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            CompositionLocalProvider(LocalRaftContentColor provides iconInk) {
                Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { leadingIcon() }
            }
        }
        if (description == null) {
            RaftText(
                text,
                Modifier.weight(1f),
                style = labelStyle,
                color = ink,
                // raft-ui 0.13: a long option wraps to two lines, then clamps.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Column(Modifier.weight(1f)) {
                RaftText(text, style = labelStyle, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                RaftText(description, style = RaftTheme.typography.footnote, color = colors.foregroundMuted)
            }
        }
        if (trailingIcon != null) {
            CompositionLocalProvider(LocalRaftContentColor provides iconInk) {
                Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { trailingIcon() }
            }
        }
        if (shortcut != null) {
            RaftText(shortcut, style = RaftTheme.typography.monoMeta, color = if (enabled) colors.foregroundHint else colors.foregroundDisabled, maxLines = 1)
        }
        if (checked) RaftGlyphIcon(RaftGlyph.Check, size = 16.dp, color = ink)
    }
}

/**
 * raft-ui 0.106.0 `scroll-py-6`: a menu row that takes the focus (the keyboard's highlight) is
 * scrolled into view [RaftMaskFadeLength] (24dp, the fade's length) clear of the scroller's top
 * and bottom edge, so the fade never covers it. Also records [entry]'s focus.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.raftMenuKeepClearOfFade(entry: RaftMenuNav.Entry): Modifier {
    val requester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val clearance = with(LocalDensity.current) { RaftMaskFadeLength.toPx() }
    // Not state: read only when the focus moves.
    val size = remember { IntArray(2) }
    return this
        .bringIntoViewRequester(requester)
        .onSizeChanged {
            size[0] = it.width
            size[1] = it.height
        }
        .onFocusChanged {
            entry.focused = it.isFocused
            if (it.isFocused && size[1] > 0) {
                scope.launch { requester.bringIntoView(raftMenuFocusRevealRect(size[0], size[1], clearance)) }
            }
        }
}

/** The part of a focused menu row ([width] x [height] px) to keep in view: the row and [clearance] px above and below. */
internal fun raftMenuFocusRevealRect(width: Int, height: Int, clearance: Float): Rect =
    Rect(0f, -clearance, width.toFloat(), height + clearance)

/** A group label in a menu: `text-caption` bold `foreground-hint`, 10dp in, 8dp above, 4dp below. */
@Composable
fun RaftMenuLabel(text: String, modifier: Modifier = Modifier) {
    RaftText(
        text,
        modifier.padding(
            start = RaftMenuMetrics.ItemPaddingHorizontal,
            end = RaftMenuMetrics.ItemPaddingHorizontal,
            top = RaftMenuMetrics.LabelPaddingTop,
            bottom = RaftMenuMetrics.LabelPaddingBottom,
        ),
        style = RaftTheme.typography.caption.copy(fontWeight = FontWeight.Bold),
        color = RaftTheme.colors.foregroundHint,
    )
}

/** A `line-hairline` rule between groups of rows, 4dp above and below (`my-1`), across the whole popup. */
@Composable
fun RaftMenuSeparator(modifier: Modifier = Modifier) {
    val color = RaftTheme.colors.lineHairline
    Box(
        modifier
            .fillMaxWidth()
            .padding(vertical = RaftMenuMetrics.SeparatorInset)
            .height(RaftStroke.Hairline)
            // `-mx-1`: drawn through the surface's padding to both edges. Drawn, not
            // measured, so the rule never asks for width (the popup hugs its rows).
            .drawBehind {
                val bleed = RaftMenuMetrics.Padding.toPx()
                drawRect(color, topLeft = Offset(-bleed, 0f), size = Size(size.width + bleed * 2, size.height))
            },
    )
}

/**
 * The search row at the top of a selection popover ([RaftMenuSurface] `header`): 40dp,
 * 12dp in, a 16dp `foreground-hint` search glyph, the field type (16/24, so iOS doesn't
 * zoom into it) with a
 * `foreground-hint` [placeholder], and a `line-hairline` under it. It touches the
 * popup's edges, so keyboard focus draws the inset 2dp ink outline, not the outer ring,
 * which the popup would clip. A tap that focuses it (the keyboard comes up) draws nothing:
 * raft-ui `has-focus-visible:keyboard:inset-ring-2` (artin 2026-11-14: in a phone's picker
 * sheet the tapped search row wore a square black frame edge to edge).
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
                        // 40 with a finger; the shared 34dp row height under a fine pointer
                        // (sizing v12, raft-ui 0.65 desktop density).
                        .heightIn(min = RaftTheme.controls.rowHeight ?: 40.dp)
                        .raftInsetFocusOutline(raftIsFocusVisible(source), colors.foreground)
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
 * aligned (end aligned with [alignEnd], raft-ui `align="end"`: a trigger at the end of a
 * card, so the menu stays over the card), 6dp below; flipped above when there is more room there, and kept 8dp inside
 * the safe area of the window (clear of the status bar, the home indicator and the
 * keyboard), no wider than that safe area less 8dp each side (raft-ui 0.106.1), and no taller
 * than that safe area less 8dp each side (or 480dp): the rows
 * scroll. Tapping outside or Back calls [onDismissRequest]. Fill it with
 * [RaftMenuItem]s, [RaftMenuLabel]s and [RaftMenuSeparator]s; a row's `onClick` should
 * also close the menu, a `checked` row (picking one value) included: one pick completes the
 * choice and an open menu covers its result (raft-ui 0.51, #883, radio items close on pick).
 *
 * Keyboard: opened from the keyboard, the first row takes focus; the arrows move through
 * the rows (wrapping), Home / End go to the first / last, a letter to the next row
 * starting with it, Enter picks, Escape closes. On closing from the keyboard, focus goes
 * back to [returnFocusTo] (the trigger's `Modifier.focusRequester`). [initialFocus] takes
 * focus instead as soon as the popup can hold it, however it was opened (a search row that
 * is there to type in: a combobox that creates values).
 *
 * A menu whose trigger is scrolled out of view closes, unless people are [typing] in it (its
 * search row has focus, or the keyboard is up for it): the keyboard shrinks the window (a
 * centred dialog) and can clip the trigger away under the very search it came up for
 * ([raftPopupClosesForClippedAnchor]). A tap outside, Back, Escape and a pick still close it.
 *
 * Acting on one thing goes in a menu; picking a value is a [RaftSelect].
 *
 * The rows go in a scrolling column inside a [RaftMenuSurface]: never a lazy list (the
 * rows already scroll), and without a [width] nothing subcomposed either (see
 * [RaftMenuSurface]). The one exception is a combobox popup's list of many rows: a lazy list
 * of a bounded height in a menu of a fixed [width], whose rows keep the keys through
 * [RaftMenuNav.lazy].
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
    alignEnd: Boolean = false,
    initialFocus: FocusRequester? = null,
    typing: Boolean = false,
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
    // Read when the menu leaves the composition: still true there means its anchor went with it.
    val stillExpanded by rememberUpdatedState(expanded)
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
    val gap = with(density) { offset.roundToPx() }
    val minListHeight = with(density) { LocalRaftComponentTokens.current.popupListMinHeight.roundToPx() }
    val room = remember(gap, minListHeight) { RaftListPopupRoom(gap, minListHeight) }
    val scroll = rememberScrollState()
    val provider =
        remember(density, offset, area, alignEnd, room) {
            RaftMenuPositionProvider(
                gap = gap,
                windowMargin = windowMargin,
                insets = insets,
                safeArea = area,
                alignEnd = alignEnd,
                room = room,
            )
        }
    val dismiss by rememberUpdatedState(onDismissRequest)
    val nav = remember { RaftMenuNav() }
    // A right swipe must not pop the page underneath an open menu.
    RaftBlockBackSwipe()
    val autofocusGuard = LocalRaftAutofocusGuard.current
    autofocusGuard.HoldOpenSurface()
    // A page pushed over this one closes the menu first: the covered page stops composing.
    RaftDismissOnPageCover { dismiss() }
    // raft-ui 0.102.6 (`usePopupAnchorDismiss`, elevation.md): a menu whose trigger is scrolled
    // out of its scrolling region, or unmounted, closes the way Back and Escape do, rather than
    // float over unrelated content; one opened with its trigger already out of view closes at once.
    // Not while people type in it: the keyboard of its search may be what clipped the trigger.
    var closedForAnchor by remember { mutableStateOf(false) }
    val currentTyping by rememberUpdatedState(typing)
    Box(
        Modifier.raftPopupAnchorClipped { clipped ->
            if (raftPopupClosesForClippedAnchor(clipped, currentTyping) && !closedForAnchor) {
                closedForAnchor = true
                dismiss()
            }
        },
    )
    DisposableEffect(Unit) { onDispose { if (stillExpanded && !closedForAnchor) dismiss() } }
    // Android: unfocused until the window carries the host's bar icons (RaftPopupWindowPolicy).
    var focusable by remember { mutableStateOf(!RaftPopupDefersFocus) }
    RaftSelectionSafePopup(popupPositionProvider = provider, onDismissRequest = { dismiss() }, properties = PopupProperties(focusable = focusable)) {
        // Its own window on Android: keep the host's bar icons while it holds focus.
        RaftPopupWindowPolicy(onReady = { focusable = true })
        LaunchedEffect(focusable) {
            if (focusable && initialFocus != null) {
                // The row attaches its focus target in the first frame.
                withFrameNanos { }
                runCatching { initialFocus.requestFocus() }
            } else if (focusable && inputModes.inputMode == InputMode.Keyboard) {
                // The rows attach their focus targets in the first frame.
                withFrameNanos { }
                nav.focusFirst()
            }
        }
        // The shadow's room is part of the popup window; a tap there is outside the menu.
        Box(
            Modifier
                .raftMenuViewportCap(windowMargin, insets, area, room, scroll)
                .raftObserveFocusInteractions(
                    coordinator = autofocusGuard.coordinator,
                    observeWindowFocus = false,
                    forceInstall = true,
                )
                .pointerInput(Unit) { detectTapGestures { dismiss() } }
                .padding(
                    start = RaftPopupInsets.Horizontal,
                    end = RaftPopupInsets.Horizontal,
                    top = RaftPopupInsets.Top,
                    bottom = RaftPopupInsets.Bottom,
                ),
        ) {
            CompositionLocalProvider(
                LocalRaftMenuNav provides nav,
                LocalRaftFocusInteractionObserverInstalled provides true,
            ) {
                RaftMenuSurfaceFrame(
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
                    scroll = scroll,
                    content = content,
                )
            }
        }
    }
}

/**
 * raft-ui 0.102.6 `usePopupAnchorDismiss`: watches the anchor of a popup, the layout this
 * zero-size node is placed in (as a Compose Popup is anchored to its parent), and calls
 * [onChange] with whether it is clipped out of view: none of it left inside the bounds of the
 * layouts round it (a scrolling region, the window). A point anchor (no size, a long press's
 * spot) and a window with no size (a test without layout) never count as clipped.
 */
internal fun Modifier.raftPopupAnchorClipped(onChange: (Boolean) -> Unit): Modifier =
    onGloballyPositioned { coordinates ->
        val anchor = coordinates.parentLayoutCoordinates ?: return@onGloballyPositioned
        onChange(raftAnchorClippedOut(anchor))
    }

/**
 * Whether an open popup closes because its anchor is [clipped] out of view
 * ([raftPopupAnchorClipped]): yes, unless people are [typing] in it (its search row has focus,
 * or the keyboard is up for it). The keyboard a popup's search brings up shrinks the window
 * (a centred dialog) and can clip the field the popup hangs from: closing then would take the
 * popup away the moment its search is tapped.
 */
internal fun raftPopupClosesForClippedAnchor(clipped: Boolean, typing: Boolean): Boolean = clipped && !typing

internal fun raftAnchorClippedOut(anchor: LayoutCoordinates): Boolean {
    if (!anchor.isAttached) return false
    val root = anchor.findRootCoordinates()
    val window = Rect(0f, 0f, root.size.width.toFloat(), root.size.height.toFloat())
    // raft-ui 0.102.8: only where the window has a size (not a test without layout).
    if (window.isEmpty) return false
    if (anchor.size.width <= 0 || anchor.size.height <= 0) return false
    // Laid out past the window's edge (a row wider than a test's window): not a scrolling
    // region's doing, and the window itself does not scroll.
    if (root.localBoundingBoxOf(anchor, clipBounds = false).intersect(window).isEmpty) return false
    // Clipped to the bounds of every layout round the anchor, on the way up to the window.
    return root.localBoundingBoxOf(anchor, clipBounds = true).isEmpty
}

/**
 * One cursor for pointer and keys (Base UI's highlight): a pointer over a row while the
 * keyboard has another row of the same menu moves the focus onto it, so only one row is
 * `brand-soft`. Leaves the focus alone while the search field has it, so typing goes on.
 */
@Composable
internal fun RaftMenuPointerCursor(nav: RaftMenuNav?, entry: RaftMenuNav.Entry, hovered: Boolean) {
    LaunchedEffect(nav, entry, hovered) {
        if (hovered && nav != null && nav.entries.any { it.focused && it !== entry }) nav.focus(entry)
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

    /**
     * The menu's rows are a lazy list (a combobox popup's): the keys go over every row of it,
     * composed or not, instead of over [entries].
     */
    var lazy: RaftMenuLazyRows? = null

    /** The rows that can take focus, top to bottom. */
    fun ordered(): List<Entry> = entries.filter { it.enabled }.sortedBy { it.top }

    fun focus(entry: Entry?): Boolean {
        if (entry == null) return false
        return runCatching { entry.requester.requestFocus() }.isSuccess
    }

    /** Focuses the first row that can take focus (a menu opened from the keyboard). */
    fun focusFirst() {
        val rows = lazy
        if (rows != null) rows.focusRow(0) else focus(ordered().firstOrNull())
    }

    fun onKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        lazy?.let { return it.onKey(event) }
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

/** A row of a [RaftMenuLazyRows] list that can take focus: its [item] index in the list, and its label (type-ahead). */
internal data class RaftMenuLazyRow(val item: Int, val text: String)

/**
 * The keyboard navigation of a menu whose rows are a lazy list ([RaftMenuNav.lazy], a combobox
 * popup's): [RaftMenuNav]'s keys over every row that can take focus ([rows], top to bottom),
 * composed or not. A target out of view is scrolled to first ([listState]), then focused through
 * its item's [requester], which the list puts on each row it composes; [focusedItem] is the item
 * whose row has focus (-1: none, the search field may have it), which each row keeps.
 */
internal class RaftMenuLazyRows(private val listState: LazyListState, private val scope: CoroutineScope) {
    var rows: List<RaftMenuLazyRow> = emptyList()
    var focusedItem = -1
    private val requesters = HashMap<Int, FocusRequester>()

    /** The focus requester of [item]'s row: the list attaches it to the row while it is composed. */
    fun requester(item: Int): FocusRequester = requesters.getOrPut(item) { FocusRequester() }

    fun onKey(event: KeyEvent): Boolean {
        if (rows.isEmpty()) return false
        val at = rows.indexOfFirst { it.item == focusedItem }
        val target = raftMenuKeyTarget(rows.map { it.text }, at, event.key, event.utf16CodePoint) ?: return false
        focusRow(target)
        return true
    }

    /** Focuses the [at]th row that can take focus; a row out of view is scrolled to (and so composed) first. */
    fun focusRow(at: Int) {
        val item = rows.getOrNull(at)?.item ?: return
        scope.launch {
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == item }) {
                listState.scrollToItem(item)
                // The row attaches its focus target once it is composed.
                withFrameNanos { }
            }
            // A row in view: focusing it brings the rest of it into view.
            runCatching { requester(item).requestFocus() }
        }
    }

    /** Focuses [item]'s row, composed (a pointer over it while the keyboard has another row). */
    fun focusItem(item: Int) {
        runCatching { requester(item).requestFocus() }
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
 * Where a menu popup goes: its surface's start edge on the anchor's (its end edge with
 * [alignEnd], Base UI `align="end"`), [gap] below it, or above when it doesn't fit below and
 * there is more room above; then moved to stay [windowMargin] inside the window's [safeArea]
 * (clear of the bars and the keyboard). [insets] is the room the popup keeps round its surface.
 *
 * A list popup (a menu, a select or combobox list) passes the [side] [raftListPopupPlacement]
 * picked for it instead (raft-ui 0.106.2): below or above as picked, its surface already shrunk
 * to the room there, or [RaftListPopupSide.Beside] its anchor, at the anchor's end side (start
 * side without room there) with its top level with the anchor's. Beside where it fits on neither
 * side across, it takes the side with more room and is moved into the window, over its anchor,
 * as before. Without a [side] (the preview card, raft-ui leaves it unchanged) the simple flip.
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
    alignEnd: Boolean = false,
    side: RaftListPopupSide? = null,
): IntOffset {
    val surfaceWidth = popup.width - insets.horizontal * 2
    val surfaceHeight = popup.height - insets.top - insets.bottom
    val minX = safeArea.left + windowMargin
    val maxX = window.width - safeArea.right - windowMargin
    val (minY, maxY) = raftMenuVerticalBounds(window.height, windowMargin, insets, safeArea)
    val below = anchor.bottom + gap
    val roomBelow = maxY - below
    val roomAbove = anchor.top - gap - minY
    if (side == RaftListPopupSide.Beside) {
        // Base UI `side="inline-end"`, flipped to the start side, `align="start"`.
        val endSide = if (rtl) anchor.left - gap - surfaceWidth else anchor.right + gap
        val startSide = if (rtl) anchor.right + gap else anchor.left - gap - surfaceWidth
        val fits = { x: Int -> x >= minX && x + surfaceWidth <= maxX }
        val x = if (fits(endSide)) endSide else if (fits(startSide)) startSide else null
        if (x != null) {
            val top = anchor.top.coerceAtMost(maxY - surfaceHeight).coerceAtLeast(minY)
            return IntOffset(x - insets.horizontal, top - insets.top)
        }
    }
    // The end edge is the right one left to right, the left one right to left.
    var left = if (rtl != alignEnd) anchor.right - surfaceWidth else anchor.left
    left = left.coerceAtMost(maxX - surfaceWidth).coerceAtLeast(minX)
    val above =
        when (side) {
            RaftListPopupSide.Below -> false
            RaftListPopupSide.Above -> true
            else -> surfaceHeight > roomBelow && roomAbove > roomBelow
        }
    var top = if (above) anchor.top - gap - surfaceHeight else below
    top = top.coerceAtMost(maxY - surfaceHeight).coerceAtLeast(minY)
    return IntOffset(left - insets.horizontal, top - insets.top)
}

/** The side of its anchor a list popup opens on ([raftListPopupPlacement]). */
internal enum class RaftListPopupSide { Below, Above, Beside }

/** Where a list popup opens ([side]) and the tallest its surface may be there, in px. */
internal data class RaftListPopupPlacement(val side: RaftListPopupSide, val maxSurfaceHeight: Int)

/**
 * raft-ui 0.106.2 list popup placement (`LIST_POPUP_COLLISION_AVOIDANCE` + `max-h-popup-list`;
 * a menu, a select or combobox list): it stays below its anchor and shrinks to the room there,
 * scrolling its rows, as long as that room holds [minListHeight] (`--popup-list-min-height`, five
 * rows and the 4dp inset: 188dp by touch, 178dp with a mouse) or the whole [contentHeight] (the
 * surface's natural height, px); otherwise it flips above under the same rule; only when neither
 * side holds it does it open beside the anchor, as tall as the window band [minY]..[maxY].
 * [anchorTop] / [anchorBottom] are the anchor's edges and [gap] the popup's distance from it.
 */
internal fun raftListPopupPlacement(
    anchorTop: Int,
    anchorBottom: Int,
    gap: Int,
    minY: Int,
    maxY: Int,
    contentHeight: Int,
    minListHeight: Int,
): RaftListPopupPlacement {
    val roomBelow = (maxY - (anchorBottom + gap)).coerceAtLeast(0)
    val roomAbove = (anchorTop - gap - minY).coerceAtLeast(0)
    // flip() measures the popup capped at five rows on the pass that picks the side.
    val needs = minOf(contentHeight, minListHeight)
    return when {
        roomBelow >= needs -> RaftListPopupPlacement(RaftListPopupSide.Below, roomBelow)
        roomAbove >= needs -> RaftListPopupPlacement(RaftListPopupSide.Above, roomAbove)
        else -> RaftListPopupPlacement(RaftListPopupSide.Beside, (maxY - minY).coerceAtLeast(0))
    }
}

/**
 * What an open list popup's cap and its position share ([raftMenuViewportCap],
 * `RaftMenuPositionProvider`): the [anchor]'s bounds the positioner last got and the surface's
 * natural height ([contentHeight], its measured height plus what its rows scroll; 0 until
 * measured). Both pick the side with [raftListPopupPlacement] from these, so the popup is
 * capped to the room on the side it is then placed on. The positioner learns the anchor after
 * the first measure: the first frame is capped to the window, the next to the side.
 */
@Stable
internal class RaftListPopupRoom(val gap: Int, val minListHeight: Int) {
    var anchor by mutableStateOf<IntRect?>(null)
    var contentHeight by mutableIntStateOf(0)

    fun placement(windowHeight: Int, windowMargin: Int, insets: RaftPopupMargins, safeArea: RaftSafeArea, fallbackContentHeight: Int): RaftListPopupPlacement? {
        val at = anchor ?: return null
        val (minY, maxY) = raftMenuVerticalBounds(windowHeight, windowMargin, insets, safeArea)
        val content = contentHeight.takeIf { it > 0 } ?: fallbackContentHeight
        return raftListPopupPlacement(at.top, at.bottom, gap, minY, maxY, content, minListHeight)
    }
}

/**
 * The band a menu's surface may take, left to right in px: [windowMargin] inside the
 * [safeArea], and far enough from the window's edges that the shadow room ([insets])
 * stays inside the window.
 */
private fun raftMenuHorizontalBounds(windowWidth: Int, windowMargin: Int, insets: RaftPopupMargins, safeArea: RaftSafeArea): Pair<Int, Int> =
    maxOf(safeArea.left + windowMargin, insets.horizontal) to minOf(windowWidth - safeArea.right - windowMargin, windowWidth - insets.horizontal)

/**
 * The widest a menu's surface may be in a window [windowWidth] px wide: the band of
 * [raftMenuHorizontalBounds] (the safe area less [windowMargin] each side, and keeping
 * the shadow room [insets] inside the window). Mirrors raft-ui 0.106.1 `max-w-(--available-width)`.
 */
internal fun raftMenuMaxSurfaceWidth(windowWidth: Int, windowMargin: Int, insets: RaftPopupMargins, safeArea: RaftSafeArea): Int {
    val (minX, maxX) = raftMenuHorizontalBounds(windowWidth, windowMargin, insets, safeArea)
    return (maxX - minX).coerceAtLeast(0)
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
 * Caps a menu popup (its surface plus the shadow room [insets]) so the surface is no wider
 * than [raftMenuMaxSurfaceWidth] and no taller than [raftMenuMaxSurfaceHeight] of the window
 * it is measured against. The popup is measured against the whole window, keyboard and bars
 * included; capped, a wide menu grows to fit its longest single-line item without overflowing
 * the window (wrapping only when the screen truly can't fit it, raft-ui 0.106.1), and a long
 * menu scrolls its rows instead of running under the keyboard.
 *
 * Assumption, unverified on devices: this (and [raftMenuPosition]) takes the popup's
 * constraints / window size to be the whole window and subtracts the `safeDrawing` insets
 * itself. On Android, `PopupLayout` may already measure against the visible display frame
 * (without the status bar and the IME), so the insets could be subtracted twice and a long
 * menu capped too short or placed too low. Check on an Android and an iOS device before
 * changing the math (README, TODO "Device check: popup insets").
 */
internal fun Modifier.raftMenuViewportCap(
    windowMargin: Int,
    insets: RaftPopupMargins,
    safeArea: RaftSafeArea,
    room: RaftListPopupRoom? = null,
    scroll: ScrollState? = null,
): Modifier =
    layout { measurable, constraints ->
        val maxWidth =
            if (constraints.hasBoundedWidth) {
                val surface = raftMenuMaxSurfaceWidth(constraints.maxWidth, windowMargin, insets, safeArea)
                (surface + insets.horizontal * 2).coerceIn(constraints.minWidth, constraints.maxWidth)
            } else {
                constraints.maxWidth
            }
        val maxHeight =
            if (constraints.hasBoundedHeight) {
                val band = raftMenuMaxSurfaceHeight(constraints.maxHeight, windowMargin, insets, safeArea)
                // raft-ui 0.106.2: a list popup takes the room on the side it opens on.
                val side = room?.placement(constraints.maxHeight, windowMargin, insets, safeArea, fallbackContentHeight = Int.MAX_VALUE)
                val surface = side?.maxSurfaceHeight?.coerceAtMost(band) ?: band
                (surface + insets.top + insets.bottom).coerceIn(constraints.minHeight, constraints.maxHeight)
            } else {
                constraints.maxHeight
            }
        val placeable = measurable.measure(constraints.copy(maxWidth = maxWidth, maxHeight = maxHeight))
        if (room != null) {
            // The rows' scroller has just measured: what it scrolls is what the cap cut off.
            val scrolled = scroll?.let { Snapshot.withoutReadObservation { it.maxValue } }?.takeIf { it in 0 until Int.MAX_VALUE } ?: 0
            val natural = placeable.height - insets.top - insets.bottom + scrolled
            if (natural > 0 && natural != Snapshot.withoutReadObservation { room.contentHeight }) room.contentHeight = natural
        }
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

private class RaftMenuPositionProvider(
    private val gap: Int,
    private val windowMargin: Int,
    private val insets: RaftPopupMargins,
    private val safeArea: RaftSafeArea,
    private val alignEnd: Boolean = false,
    private val room: RaftListPopupRoom,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        // Read back by the cap on the next measure: the popup shrinks to its side's room.
        if (Snapshot.withoutReadObservation { room.anchor } != anchorBounds) room.anchor = anchorBounds
        val surfaceHeight = popupContentSize.height - insets.top - insets.bottom
        val side = Snapshot.withoutReadObservation { room.placement(windowSize.height, windowMargin, insets, safeArea, fallbackContentHeight = surfaceHeight) }?.side
        return raftMenuPosition(anchorBounds, windowSize, popupContentSize, gap, windowMargin, insets, layoutDirection == LayoutDirection.Rtl, safeArea, alignEnd, side)
    }
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

/**
 * `option.height`: 36 with a finger; the shared 34dp row height on a desktop (sizing v12, raft-ui
 * 0.65 desktop density), so a menu option matches the sidebar row and the field beside it.
 */
internal val RaftOptionHeight: Dp
    @Composable @ReadOnlyComposable
    get() = RaftTheme.sizes.optionHeight
