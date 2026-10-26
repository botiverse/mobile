package build.raft.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.Measured
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.LocalRaftStrings
import build.raft.designsystem.theme.LocalRaftSurfaceColor
import build.raft.designsystem.theme.RaftStrings
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius

/** raft-ui Dialog `size`: the narrowest one the content fits. */
enum class RaftDialogSize(internal val maxWidth: Dp) {
    /** `max-w-sm`: a decision. */
    Sm(384.dp),

    /** `max-w-md` (default): 2 or 3 fields. */
    Md(448.dp),

    /** `max-w-lg`. */
    Lg(512.dp),
}

/**
 * raft-ui 0.8.0 Dialog surface: the centred card of a dialog without the window behind it
 * (the doc's DialogSurface), for [RaftDialog] and for fixtures.
 *
 * - Surface: `layer-popover`, dialog radius (16dp), a 1dp `line-seam` ring and
 *   `shadow-float`; full width up to [size]'s maximum.
 * - Header: [title] in `text-headline` bold `foreground` (named by the words of the
 *   button that opened it, Title Case: "Create Channel"), and, with [onClose], the ghost
 *   × icon button named [closeLabel] at its end. 20dp padding, no bottom padding.
 * - Body: [description] (`text-subhead` `foreground-muted`) then [content], 20dp at the
 *   sides and bottom, 6dp under the header, 16dp between its children. It scrolls when the
 *   dialog reaches the window, so [content] goes in as it is: no `Modifier.weight` and no
 *   `verticalScroll` or lazy list of its own. Inside the scroll the body's height is
 *   unbounded, and a weighted child gets none of it: it is measured 0dp tall and the body
 *   shows empty (Create Joint Channel, Android 1.15.3).
 * - [footer]: at most two buttons at the end, 8dp apart: a ghost Cancel that says what
 *   stays, then the confirm button (`Lg`). They stay in one row at every width and stack
 *   full width, the confirm on top, only when they don't fit (raft-ui 0.22 #673); stacked, a
 *   ghost Cancel takes the ink frame of `default` (0.23 #727, [LocalRaftButtonsStacked]).
 */
@Composable
fun RaftDialogSurface(
    title: String,
    modifier: Modifier = Modifier,
    size: RaftDialogSize = RaftDialogSize.Md,
    description: String? = null,
    onClose: (() -> Unit)? = null,
    closeLabel: String = LocalRaftStrings.current.close,
    footer: (@Composable RowScope.() -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    CompositionLocalProvider(LocalRaftContentColor provides colors.foregroundMuted, LocalRaftSurfaceColor provides colors.layerPopover) {
        Column(
            modifier
                .widthIn(max = size.maxWidth)
                .fillMaxWidth()
                .raftOverlaySurface(colors.layerPopover, colors.lineSeam, RaftTheme.isDark, RaftRadius.Dialog),
        ) {
            RaftDialogHeader(title = title, onClose = onClose, closeLabel = closeLabel)
            val hasBody = description != null || content != null
            if (hasBody) {
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (description != null) RaftText(description, style = typography.subhead, color = colors.foregroundMuted)
                    content?.invoke(this)
                }
            }
            if (footer != null) {
                RaftDialogFooter(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp, top = if (hasBody) 0.dp else 20.dp),
                    footer,
                )
            } else if (!hasBody) {
                Box(Modifier.padding(bottom = 20.dp))
            }
        }
    }
}

/**
 * raft-ui DialogHeader (with DialogTitle, DialogDescription and DialogClose) on its own, for
 * a dialog-like surface whose body the caller lays out: a modal over a lazy list, a panel
 * drawn on its own `raftOverlaySurface`. [RaftDialogSurface] draws the same title row.
 *
 * - `px-5 pt-5`, no bottom padding: follow it with the body at 20dp sides (DialogBody
 *   `pt-1.5 pb-5`: 6dp under the header).
 * - [title]: `text-headline` bold `foreground`, a heading for screen readers; it wraps.
 * - [description]: `text-subhead` `foreground-muted`, 6dp under the title row.
 * - [onClose]: the ghost × `icon-md` named [closeLabel] ([RaftStrings.close] by default) at
 *   the end, pulled 6dp into the corner (`-my-1.5 -mr-1.5`) so it doesn't make the row taller.
 */
@Composable
fun RaftDialogHeader(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    onClose: (() -> Unit)? = null,
    closeLabel: String = LocalRaftStrings.current.close,
    closeEnabled: Boolean = true,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    Column(
        modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RaftText(
                title,
                Modifier.weight(1f).semantics { heading() },
                style = typography.headline,
                color = colors.foreground,
            )
            if (onClose != null) {
                // `-my-1.5 -mr-1.5`: the 32dp button doesn't make the header taller and
                // its glyph lines up with the body's edge.
                Box(Modifier.raftBleed(end = 6.dp, vertical = 6.dp)) {
                    RaftIconButton(onClick = onClose, tooltip = closeLabel, variant = RaftButtonVariant.Ghost, enabled = closeEnabled) {
                        RaftGlyphIcon(RaftGlyph.X, size = 18.dp)
                    }
                }
            }
        }
        if (description != null) {
            RaftText(description, style = typography.subhead, color = colors.foregroundMuted)
        }
    }
}

/**
 * raft-ui 0.8.0 Dialog: [RaftDialogSurface] centred over the scrim, 16dp inside the
 * window's safe area (system bars, cutout, and the keyboard while it is up, so a form's
 * fields stay above it) and never taller than that area less 32dp. It appears and goes without
 * animation. Back, × and Cancel call [onDismissRequest]; so does a tap on the scrim
 * unless [dismissOnClickOutside] is false (pass false for a long form, so a stray tap
 * doesn't lose it). To confirm an action, use the same surface with a coloured confirm
 * button (AlertDialog).
 *
 * It opens in a [RaftWindowDialog]: the scrim is [RaftTheme.scrim] (raft `layer-backdrop`,
 * 65% black in light, 60% in dark) and the system bars follow the theme.
 */
@Composable
fun RaftDialog(
    onDismissRequest: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    size: RaftDialogSize = RaftDialogSize.Md,
    description: String? = null,
    dismissOnClickOutside: Boolean = true,
    showClose: Boolean = true,
    closeLabel: String = LocalRaftStrings.current.close,
    footer: (@Composable RowScope.() -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val dismiss by rememberUpdatedState(onDismissRequest)
    RaftWindowDialog(onDismissRequest = onDismissRequest) {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(dismissOnClickOutside) { detectTapGestures { if (dismissOnClickOutside) dismiss() } }
                // Clear of the system bars, the cutout and the keyboard, then 16dp.
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            RaftDialogSurface(
                title = title,
                modifier = modifier.pointerInput(Unit) { detectTapGestures { } },
                size = size,
                description = description,
                onClose = if (showClose) onDismissRequest else null,
                closeLabel = closeLabel,
                footer = footer,
                content = content,
            )
        }
    }
}

/**
 * raft-ui 0.8.0 Drawer as a bottom sheet (`swipeDirection="down"`), the surface only: a
 * sentence or two and one button on a phone, or a short list to pick a place to go. Full
 * width up to 448dp, `layer-popover` with the dialog radius on its top corners, the
 * `line-seam` ring and `shadow-float`, at least 256dp tall.
 *
 * - Header: [title] (`text-headline` bold) and [description] (`text-callout`
 *   `foreground-muted`, one or two sentences), 16dp padding, 4dp apart.
 * - [content] under it, 16dp at the sides. It scrolls in the sheet: no `Modifier.weight` and
 *   no `verticalScroll` of its own (a weighted child of the scroll is measured 0dp tall).
 * - [actions]: stacked full width 8dp apart at the bottom (pass the buttons
 *   `Modifier.fillMaxWidth()`, size `Lg`), one action in Title Case ("Got It").
 */
@Composable
fun RaftSheetSurface(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    actions: (@Composable ColumnScope.() -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) = RaftSheetSurfaceImpl(title, modifier, description, clearNavigationBar = false, actions, content)

/**
 * The sheet surface behind [RaftSheetSurface] and [RaftSheet], with the parts the
 * combobox's sheet adds: [headerAction] at the end of the title row (a text button such
 * as Clear, bled into the 16dp margin so it doesn't grow the row), [pinned] edge to edge
 * under the header (a search row that stays put while the list scrolls), and
 * [fillContent]: [content] is laid out as given, without the sheet's scroll or padding
 * (a lazy list that scrolls itself), and fills the sheet to its full height (82% of the
 * window) with the actions under it. A fixture without a window must give the surface a
 * height then.
 */
@Composable
internal fun RaftSheetSurfaceImpl(
    title: String,
    modifier: Modifier,
    description: String?,
    clearNavigationBar: Boolean,
    actions: (@Composable ColumnScope.() -> Unit)?,
    content: (@Composable ColumnScope.() -> Unit)?,
    headerAction: (@Composable () -> Unit)? = null,
    pinned: (@Composable () -> Unit)? = null,
    fillContent: Boolean = false,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    CompositionLocalProvider(LocalRaftContentColor provides colors.foreground, LocalRaftSurfaceColor provides colors.layerPopover) {
        Column(
            modifier
                .widthIn(max = 448.dp)
                .fillMaxWidth()
                .heightIn(min = 256.dp)
                .raftOverlaySurface(colors.layerPopover, colors.lineSeam, RaftTheme.isDark, RaftRadius.Dialog, 0.dp)
                // `pb-[env(safe-area-inset-bottom)]`: the fill runs under the bar (and the
                // keyboard), the content clears the home indicator and the keyboard.
                .then(if (clearNavigationBar) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) else Modifier),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (headerAction != null) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RaftText(title, Modifier.weight(1f).semantics { heading() }, style = typography.headline, color = colors.foreground)
                        // A 28dp text button beside a 22dp title line: `-my-[3px] -mr-2`.
                        Box(Modifier.raftBleed(end = 8.dp, vertical = 3.dp)) { headerAction() }
                    }
                } else {
                    RaftText(title, Modifier.semantics { heading() }, style = typography.headline, color = colors.foreground)
                }
                if (description != null) RaftText(description, style = typography.callout, color = colors.foregroundMuted)
            }
            pinned?.invoke()
            if (content != null) {
                if (fillContent) {
                    Column(Modifier.fillMaxWidth().weight(1f), content = content)
                } else {
                    Column(
                        Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                        content = content,
                    )
                }
            }
            if (actions != null) {
                // Pushes the actions to the bottom of a short sheet; filling content already does.
                if (!fillContent) Box(Modifier.weight(1f, fill = false))
                // raft-ui DrawerActions in a bottom sheet: always stacked, so a ghost is framed (#727).
                CompositionLocalProvider(LocalRaftButtonsStacked provides true) {
                    Column(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        content = actions,
                    )
                }
            }
        }
    }
}

/** raft `--ease-slide`. */
private val RaftSheetEase = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

/**
 * raft-ui 0.8.0 Drawer, bottom sheet: [RaftSheetSurface] at the bottom of the window over
 * the scrim, at most 82% of the window tall; its content clears the home indicator and
 * the keyboard (the fill runs under them), and it never goes under the status bar. It slides up
 * from the bottom (240ms, `ease-slide`), the one overlay that moves; it closes without
 * the slide. Back and a tap on the scrim call [onDismissRequest]; keep a button in the
 * sheet too, never only the gesture. Swipe to dismiss and snap points are not ported.
 */
@Composable
fun RaftSheet(
    onDismissRequest: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    actions: (@Composable ColumnScope.() -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    RaftSheetWindow(onDismissRequest, modifier) { surfaceModifier ->
        RaftSheetSurfaceImpl(
            title = title,
            modifier = surfaceModifier,
            description = description,
            clearNavigationBar = true,
            actions = actions,
            content = content,
        )
    }
}

/**
 * The window of a [RaftSheet]: a [RaftWindowDialog] with its scrim, the slide up, a tap on
 * the scrim and Back calling [onDismissRequest], and the 82% height cap. [surface] draws
 * the sheet with the modifier it is given (the caller's [modifier] plus the slide, the
 * tap guard and the cap); pass it a [RaftSheetSurfaceImpl] with `clearNavigationBar`.
 */
@Composable
internal fun RaftSheetWindow(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    surface: @Composable (Modifier) -> Unit,
) {
    val dismiss by rememberUpdatedState(onDismissRequest)
    RaftWindowDialog(onDismissRequest = onDismissRequest) {
        val slide = remember { Animatable(1f) }
        LaunchedEffect(Unit) { slide.animateTo(0f, tween(240, easing = RaftSheetEase)) }
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { dismiss() } }
                // Never under the status bar or a side cutout; the bottom is the sheet's own.
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            surface(
                modifier
                    .graphicsLayer { translationY = size.height * slide.value }
                    .pointerInput(Unit) { detectTapGestures { } }
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(raftSheetConstraints(constraints))
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    },
            )
        }
    }
}

/**
 * CSS negative margins: the element reports itself [end] narrower and [vertical] shorter
 * on each side than it draws, so it overhangs its slot without growing it.
 */
internal fun Modifier.raftBleed(end: Dp = 0.dp, vertical: Dp = 0.dp): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
        val e = end.roundToPx()
        val v = vertical.roundToPx()
        layout((placeable.width - e).coerceAtLeast(0), (placeable.height - 2 * v).coerceAtLeast(0)) {
            placeable.place(0, -v)
        }
    }

/**
 * A sheet's constraints: at most 82% of the window's height, but never below the
 * incoming minimum (a caller's `heightIn(min = …)` on a short window) and unchanged when
 * the height is unbounded.
 */
internal fun raftSheetConstraints(constraints: Constraints): Constraints =
    if (!constraints.hasBoundedHeight) {
        constraints
    } else {
        constraints.copy(maxHeight = (constraints.maxHeight * 0.82f).toInt().coerceAtLeast(constraints.minHeight))
    }

/**
 * raft-ui DialogFooter (#673): the buttons in one row at the end, 8dp apart, in markup order
 * (Cancel, then the confirm). The row is measured, never guessed from the window: only when
 * it doesn't fit does the footer stack, each button full width and 8dp apart, the confirm (the
 * last one) on top, with [LocalRaftButtonsStacked] set so a ghost Cancel is framed.
 */
@Composable
private fun RaftDialogFooter(modifier: Modifier, footer: @Composable RowScope.() -> Unit) {
    SubcomposeLayout(modifier) { constraints ->
        val gap = 8.dp.roundToPx()
        val rowItems =
            subcompose("row") {
                CompositionLocalProvider(LocalRaftButtonsStacked provides false) { RaftFooterRowScope.footer() }
            }
        val loose = Constraints(maxHeight = constraints.maxHeight)
        val inRow = rowItems.map { it.measure(loose) }
        val rowWidth = inRow.sumOf { it.width } + gap * (inRow.size - 1).coerceAtLeast(0)
        if (rowWidth <= constraints.maxWidth) {
            val height = inRow.maxOfOrNull { it.height } ?: 0
            layout(constraints.maxWidth, height) {
                var x = constraints.maxWidth - rowWidth
                inRow.forEach { placeable ->
                    placeable.placeRelative(x, (height - placeable.height) / 2)
                    x += placeable.width + gap
                }
            }
        } else {
            val stacked =
                subcompose("stacked") {
                    CompositionLocalProvider(LocalRaftButtonsStacked provides true) { RaftFooterRowScope.footer() }
                }
            val full = Constraints(minWidth = constraints.maxWidth, maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight)
            // The confirm, written last, goes on top.
            val column = stacked.map { it.measure(full) }.asReversed()
            val height = column.sumOf { it.height } + gap * (column.size - 1).coerceAtLeast(0)
            layout(constraints.maxWidth, height) {
                var y = 0
                column.forEach { placeable ->
                    placeable.placeRelative(0, y)
                    y += placeable.height + gap
                }
            }
        }
    }
}

/**
 * The [RowScope] the footer's buttons are written in. The footer lays them out itself, so the
 * row modifiers (`weight`, `align`) have nothing to act on.
 */
private object RaftFooterRowScope : RowScope {
    override fun Modifier.weight(weight: Float, fill: Boolean): Modifier = this

    override fun Modifier.align(alignment: Alignment.Vertical): Modifier = this

    override fun Modifier.alignBy(alignmentLine: HorizontalAlignmentLine): Modifier = this

    override fun Modifier.alignByBaseline(): Modifier = this

    override fun Modifier.alignBy(alignmentLineBlock: (Measured) -> Int): Modifier = this
}
