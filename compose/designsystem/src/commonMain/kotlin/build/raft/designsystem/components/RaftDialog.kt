package build.raft.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.motion.RaftMotion
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.LocalRaftMisuse
import build.raft.designsystem.theme.LocalRaftStrings
import build.raft.designsystem.theme.LocalRaftSurfaceColor
import build.raft.designsystem.theme.RaftStrings
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius

/**
 * raft-ui Dialog `size` (dialog.recipe `size` variants): the narrowest one the content fits.
 * Only [Lg] and [Wide] take a footer note.
 */
enum class RaftDialogSize(internal val maxWidth: Dp, internal val height: Dp? = null) {
    /** `max-w-100` (raft-ui `decisionWidth`): a decision, the same 400dp as an AlertDialog. */
    Sm(400.dp),

    /** `max-w-md` (default): 2 or 3 fields. */
    Md(448.dp),

    /** `max-w-160`: a longer form, a table of choices. */
    Lg(640.dp),

    /**
     * `max-w-4xl h-[min(760px,calc(100dvh-2rem))]`: a settings-like dialog. It keeps that
     * height (the window's, less 16dp above and below, when shorter), so its body scrolls and
     * the footer stays at the bottom. raft-ui's `DialogNav` beside the body is not drawn.
     */
    Wide(896.dp, 760.dp),
}

/** raft-ui `DialogFooterNote`: at most this many characters, one line beside the buttons. */
const val RaftDialogFooterNoteMaxLength: Int = 40

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
 * - [footer]: at most two buttons in one right-aligned row, 8dp apart, in markup order: a
 *   ghost Cancel that says what stays first, the commit button (`Lg`) last (raft-ui
 *   dialog.md, "The footer"). They stay in one row at every width and stack full width, the
 *   commit on top, only when they don't fit (raft-ui 0.22 #673); stacked, a ghost Cancel
 *   takes the ink frame of `default` (0.23 #727, [LocalRaftButtonsStacked]).
 * - [footerNote] (raft-ui `DialogFooterNote`, [RaftDialogSize.Lg] and [RaftDialogSize.Wide]
 *   only): one short line at the start of the footer that says what the commit will do, at
 *   most [RaftDialogFooterNoteMaxLength] characters, in `text-footnote` `foreground-muted`.
 *   One line beside the buttons, 8dp before them; above them, up to 2 lines, when they don't
 *   fit beside it. Ignored (and reported to [LocalRaftMisuse]) in a smaller dialog: put the
 *   sentence in the body there.
 * - [size]: the card's maximum width; a [RaftDialogSize.Wide] card also keeps its height
 *   (760dp, or what the window gives it), its body filling it.
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
    footerNote: String? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val misuse = LocalRaftMisuse.current
    val note = footerNote?.takeIf { it.isNotBlank() }
    val noteAllowed = size == RaftDialogSize.Lg || size == RaftDialogSize.Wide
    if (note != null && !noteAllowed) {
        misuse("RaftDialogSurface: a footer note fits beside the buttons only in a Lg or Wide dialog; put the sentence in the body of this $size dialog")
    }
    if (note != null && note.length > RaftDialogFooterNoteMaxLength) {
        misuse("RaftDialogSurface: a footer note is at most $RaftDialogFooterNoteMaxLength characters; longer copy belongs in the body")
    }
    val fixedHeight = size.height
    CompositionLocalProvider(LocalRaftContentColor provides colors.foregroundMuted, LocalRaftSurfaceColor provides colors.layerPopover) {
        Column(
            modifier
                .widthIn(max = size.maxWidth)
                .fillMaxWidth()
                // `h-[min(760px,calc(100dvh-2rem))]`: the window's room is already less 16dp
                // above and below (RaftDialog's padding).
                .then(if (fixedHeight != null) Modifier.heightIn(max = fixedHeight).fillMaxHeight() else Modifier)
                .raftOverlaySurface(colors.layerPopover, colors.lineSeam, RaftTheme.isDark, RaftRadius.Dialog),
        ) {
            RaftDialogHeader(title = title, onClose = onClose, closeLabel = closeLabel)
            val hasBody = description != null || content != null
            if (hasBody) {
                val bodyScroll = rememberScrollState()
                Column(
                    Modifier
                        // A Wide card keeps its height: the body fills it, the footer at the bottom.
                        .weight(1f, fill = fixedHeight != null)
                        // raft-ui 0.60 ScrollArea: the dialog body's overlay scrollbar.
                        .raftScrollbar(bodyScroll)
                        .verticalScroll(bodyScroll)
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
                    note = note?.takeIf { noteAllowed },
                    footer = footer,
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
    footerNote: String? = null,
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
                footerNote = footerNote,
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
 * - Grab handle ([RaftSheetGrabHandle], raft-ui 0.57.2): every bottom sheet starts with it,
 *   a 36 x 4dp `line-seam` bar in a 16dp strip; the header then starts 8dp under it (`pt-2`).
 *
 * - Header: [title] (`text-headline` bold) and [description] (`text-callout`
 *   `foreground-muted`, one or two sentences), 16dp padding, 4dp apart.
 * - [content] under it, 16dp at the sides. It scrolls in the sheet: no `Modifier.weight` and
 *   no `verticalScroll` of its own (a weighted child of the scroll is measured 0dp tall).
 * - [actions]: stacked full width 8dp apart at the bottom (pass the buttons
 *   `Modifier.fillMaxWidth()`, size `Lg`), one action in Title Case ("Got It").
 * - [rowActions] instead of [actions]: two short actions side by side, 8dp apart (pass each
 *   `Modifier.weight(1f)`, size `Lg`), as the web Select Text sheet's Copy and Quote
 *   (`DrawerActions className="flex-row *:flex-1"`). Not for a ghost action: side by side
 *   it has no frame. Pass [actions] or [rowActions], never both: both throw.
 * - [onClose]: raft-ui DrawerClose, the ghost × named [closeLabel] at the end of the title
 *   row, pulled into the corner so it doesn't make the header taller (as [RaftDialogHeader]).
 */
@Composable
fun RaftSheetSurface(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    actions: (@Composable ColumnScope.() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    closeLabel: String = LocalRaftStrings.current.close,
    rowActions: (@Composable RowScope.() -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) = RaftSheetSurfaceImpl(
    title,
    modifier,
    description,
    clearNavigationBar = false,
    actions,
    content,
    onClose = onClose,
    closeLabel = closeLabel,
    rowActions = rowActions,
)

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
    onClose: (() -> Unit)? = null,
    closeLabel: String = "",
    rowActions: (@Composable RowScope.() -> Unit)? = null,
) {
    require(actions == null || rowActions == null) { "RaftSheet takes actions or rowActions, not both" }
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
            RaftSheetGrabHandle()
            // `pt-2` under the handle (drawer.recipe `side: "down"` header), `pb-2`.
            Column(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (headerAction != null || onClose != null) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RaftText(title, Modifier.weight(1f).semantics { heading() }, style = typography.headline, color = colors.foreground)
                        // A 28dp text button beside a 22dp title line: `-my-[3px] -mr-2`.
                        if (headerAction != null) Box(Modifier.raftBleed(end = 8.dp, vertical = 3.dp)) { headerAction() }
                        if (onClose != null) {
                            // The 32dp ghost × beside the 22dp title line, its glyph on the body's edge.
                            Box(Modifier.raftBleed(end = 6.dp, vertical = 5.dp)) {
                                RaftIconButton(onClick = onClose, tooltip = closeLabel, variant = RaftButtonVariant.Ghost) {
                                    RaftGlyphIcon(RaftGlyph.X, size = 18.dp)
                                }
                            }
                        }
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
                    val bodyScroll = rememberScrollState()
                    Column(
                        Modifier
                            .weight(1f, fill = false)
                            // raft-ui 0.60 ScrollArea: the sheet body's overlay scrollbar.
                            .raftScrollbar(bodyScroll)
                            .verticalScroll(bodyScroll)
                            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                        content = content,
                    )
                }
            }
            // No weighted spacer above the actions: an empty `weight(fill = false)` box measures 0dp
            // tall, so it never pushed them down, and it took half of the height a long body could
            // scroll in (the body showed its first lines only).
            if (rowActions != null) {
                CompositionLocalProvider(LocalRaftButtonsStacked provides false) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        content = rowActions,
                    )
                }
            } else if (actions != null) {
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


/**
 * raft-ui 0.8.0 Drawer, bottom sheet: [RaftSheetSurface] at the bottom of the window over
 * the scrim, at most 82% of the window tall; its content clears the home indicator and
 * the keyboard (the fill runs under them), and it never goes under the status bar. It slides up
 * from the bottom (240ms, `ease-slide`), the one overlay that moves; it closes without
 * the slide. Back, a tap on the scrim and, with [showClose], the header's × call
 * [onDismissRequest]; keep a button in the sheet too, never only the gesture. Swipe to dismiss and snap points are not ported.
 * [actions] and [rowActions] are as on [RaftSheetSurface]: one or the other, never both (throws).
 */
@Composable
fun RaftSheet(
    onDismissRequest: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    actions: (@Composable ColumnScope.() -> Unit)? = null,
    showClose: Boolean = false,
    closeLabel: String = LocalRaftStrings.current.close,
    rowActions: (@Composable RowScope.() -> Unit)? = null,
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
            onClose = if (showClose) onDismissRequest else null,
            closeLabel = closeLabel,
            rowActions = rowActions,
        )
    }
}

/**
 * The window of a [RaftSheet]: a [RaftWindowDialog] with its scrim, the slide up, a tap on
 * the scrim and Back calling [onDismissRequest], and the 82% height cap. [surface] draws
 * the sheet with the modifier it is given (the caller's [modifier] plus the slide, the
 * tap guard and the cap); pass it a [RaftSheetSurfaceImpl] with `clearNavigationBar`.
 *
 * [RaftDrawer] asks for the full drawer motion (raft-ui drawer.recipe.ts, Motion "A scrim moves
 * with its surface") with [slideMotion]: the scrim fades in with the slide (same 240ms,
 * `ease-slide`); the slide starts only after the window's first frame, so content that is heavy to
 * compose (a whole conversation) does not eat the slide's first frames; and [visible] false
 * slides the surface down again with the scrim fading out (180ms), then calls [onHidden], so the
 * caller keeps the window composed until then. [overlay] is drawn over the scrim and the surface,
 * across the whole window (not padded for the status bar). [capHeight] false hands the surface the
 * window's whole height under the status bar (a drawer with snap points sizes itself); [scrimAlpha]
 * fades the scrim with a drag toward dismiss (raft-ui `--drawer-swipe-progress`).
 */
@Composable
internal fun RaftSheetWindow(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    slideMotion: Boolean = false,
    visible: Boolean = true,
    onHidden: () -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit = {},
    capHeight: Boolean = true,
    scrimAlpha: () -> Float = { 1f },
    surface: @Composable (Modifier) -> Unit,
) {
    val dismiss by rememberUpdatedState(onDismissRequest)
    val hidden by rememberUpdatedState(onHidden)
    // A page pushed over this one takes a sliding window down at once, without the slide down
    // (RaftWindowDialog asks the caller to close it): the covered page stops composing.
    if (slideMotion) RaftDismissOnPageCover { hidden() }
    RaftWindowDialog(
        onDismissRequest = onDismissRequest,
        // The drawer slides itself: no platform window transition stacked on the slide (iOS).
        properties = RaftWindowDialogProperties(drawScrim = !slideMotion, platformTransition = !slideMotion),
    ) {
        val slide = remember { Animatable(1f) }
        if (slideMotion) {
            LaunchedEffect(visible) {
                if (visible) {
                    // The first frame composes and measures the content: start after it.
                    withFrameNanos { }
                    slide.animateTo(0f, tween(RaftSheetSlideInMillis, easing = RaftMotion.EaseSlide))
                } else {
                    slide.animateTo(1f, tween(RaftSheetSlideOutMillis, easing = RaftMotion.EaseSlide))
                    hidden()
                }
            }
        } else {
            LaunchedEffect(Unit) { slide.animateTo(0f, tween(RaftSheetSlideInMillis, easing = RaftMotion.EaseSlide)) }
        }
        Box(Modifier.fillMaxSize()) {
            if (slideMotion) {
                val scrim = RaftTheme.scrim
                Box(
                    Modifier
                        .fillMaxSize()
                        .testTag(RaftSheetScrimTestTag)
                        .graphicsLayer { alpha = (1f - slide.value) * scrimAlpha().coerceIn(0f, 1f) }
                        .background(scrim),
                )
            }
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
                            val placeable = measurable.measure(if (capHeight) raftSheetConstraints(constraints) else constraints)
                            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                        },
                )
            }
            overlay()
        }
    }
}

/** raft-ui drawer.recipe.ts: the popup's `duration-240` in, `180ms` out. */
internal const val RaftSheetSlideInMillis = 240
internal const val RaftSheetSlideOutMillis = 180

/** The scrim [RaftSheetWindow] draws itself with `slideMotion` (tests read its alpha). */
internal const val RaftSheetScrimTestTag = "raft-sheet-scrim"

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
 * (Cancel, then the commit). The row is measured, never guessed from the window: only when
 * it doesn't fit does the footer stack, each button full width and 8dp apart, the commit (the
 * last one) on top, with [LocalRaftButtonsStacked] set so a ghost Cancel is framed.
 *
 * [note] (`DialogFooterNote`, footer `start` variant): at the start, one line, 8dp before the
 * buttons (`md:me-2`), which stay at the end; when the two don't fit on one line the note goes
 * above the buttons (`flex-wrap`, 8dp apart) and may wrap to 2 lines.
 */
@Composable
private fun RaftDialogFooter(modifier: Modifier, note: String?, footer: @Composable RowScope.() -> Unit) {
    val noteStyle = RaftTheme.typography.footnote
    val noteInk = RaftTheme.colors.foregroundMuted
    SubcomposeLayout(modifier) { constraints ->
        val gap = 8.dp.roundToPx()
        val width = constraints.maxWidth
        val rowItems =
            subcompose("row") {
                CompositionLocalProvider(LocalRaftButtonsStacked provides false) { RaftFooterRowScope.footer() }
            }
        val loose = Constraints(maxHeight = constraints.maxHeight)
        val inRow = rowItems.map { it.measure(loose) }
        val rowWidth = inRow.sumOf { it.width } + gap * (inRow.size - 1).coerceAtLeast(0)
        val noteLine =
            note?.let {
                subcompose("note") { RaftText(it, style = noteStyle, color = noteInk, maxLines = 1, softWrap = false) }
                    .map { m -> m.measure(loose) }
            }
        val noteLineWidth = noteLine?.maxOfOrNull { it.width } ?: 0
        if (noteLine != null && noteLineWidth + gap + rowWidth <= width) {
            // The note and the buttons on one line: the note at the start, the buttons at the end.
            val height = maxOf(inRow.maxOfOrNull { it.height } ?: 0, noteLine.maxOfOrNull { it.height } ?: 0)
            return@SubcomposeLayout layout(width, height) {
                noteLine.forEach { it.placeRelative(0, (height - it.height) / 2) }
                var x = width - rowWidth
                inRow.forEach { placeable ->
                    placeable.placeRelative(x, (height - placeable.height) / 2)
                    x += placeable.width + gap
                }
            }
        }
        // The note (if any) on its own line above the buttons, up to 2 lines.
        val noteAbove =
            note?.let {
                subcompose("note-above") {
                    RaftText(it, style = noteStyle, color = noteInk, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }.map { m -> m.measure(Constraints(maxWidth = width, maxHeight = constraints.maxHeight)) }
            }
        val noteHeight = noteAbove?.maxOfOrNull { it.height } ?: 0
        val top = if (noteAbove != null) noteHeight + gap else 0
        if (rowWidth <= width) {
            val rowHeight = inRow.maxOfOrNull { it.height } ?: 0
            layout(width, top + rowHeight) {
                noteAbove?.forEach { it.placeRelative(0, 0) }
                var x = width - rowWidth
                inRow.forEach { placeable ->
                    placeable.placeRelative(x, top + (rowHeight - placeable.height) / 2)
                    x += placeable.width + gap
                }
            }
        } else {
            val stacked =
                subcompose("stacked") {
                    CompositionLocalProvider(LocalRaftButtonsStacked provides true) { RaftFooterRowScope.footer() }
                }
            val full = Constraints(minWidth = width, maxWidth = width, maxHeight = constraints.maxHeight)
            // The commit, written last, goes on top.
            val column = stacked.map { it.measure(full) }.asReversed()
            val columnHeight = column.sumOf { it.height } + gap * (column.size - 1).coerceAtLeast(0)
            layout(width, top + columnHeight) {
                noteAbove?.forEach { it.placeRelative(0, 0) }
                var y = top
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
