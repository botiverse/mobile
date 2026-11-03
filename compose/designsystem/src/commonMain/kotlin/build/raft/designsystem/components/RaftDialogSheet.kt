package build.raft.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.motion.RaftMotion
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.LocalRaftStrings
import build.raft.designsystem.theme.LocalRaftSurfaceColor
import build.raft.designsystem.theme.RaftScale
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke

/**
 * raft-ui Dialog "On a phone" (stdrc and Artea, 2026-10-21), the surface only: a dialog with
 * several fields, a long list or steps is a sheet 90% of the screen tall on a phone.
 *
 * - Surface: `layer-popover`, the dialog radius on its top corners, the `line-seam` ring.
 * - Head: a pushed page's head, 56dp with a seam under it: × at the start
 *   ([RaftBackButton] with [RaftGlyph.X], named [closeLabel]), the [title] in
 *   `text-headline`, and the dialog's one commit button at the end ([commitLabel], 32dp,
 *   [commitVariant]: the same colour and words as on a desktop). No footer: × is Cancel.
 *   A null [commitLabel] leaves the head without one, for a view of the dialog that commits
 *   nothing (the web drops its `DialogCommit` there too, as Invite's link tab does).
 *   [commitIcon] leads its label, as the web's commit keeps its icon in the head (Invite's
 *   Mail). [commitLoading] shows the button's spinner while the commit runs (the web's
 *   `loading` Save): it keeps its colour and ignores taps (RaftButton's busy state).
 * - Body: [content] fills the rest of the sheet as given (a lazy list scrolls itself) and
 *   clears the home indicator; keep the phone's 16dp margin inside it.
 */
@Composable
fun RaftDialogSheetSurface(
    title: String,
    commitLabel: String?,
    onCommit: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    commitEnabled: Boolean = true,
    commitLoading: Boolean = false,
    commitVariant: RaftButtonVariant = RaftButtonVariant.Primary,
    commitIcon: (@Composable () -> Unit)? = null,
    closeLabel: String = LocalRaftStrings.current.close,
    closeEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) = RaftDialogSheetSurfaceImpl(title, commitLabel, onCommit, onClose, modifier, commitEnabled, commitLoading, commitVariant, commitIcon, closeLabel, closeEnabled, clearNavigationBar = false, content)

@Composable
private fun RaftDialogSheetSurfaceImpl(
    title: String,
    commitLabel: String?,
    onCommit: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
    commitEnabled: Boolean,
    commitLoading: Boolean,
    commitVariant: RaftButtonVariant,
    commitIcon: (@Composable () -> Unit)?,
    closeLabel: String,
    closeEnabled: Boolean,
    clearNavigationBar: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = RaftTheme.colors
    CompositionLocalProvider(LocalRaftContentColor provides colors.foreground, LocalRaftSurfaceColor provides colors.layerPopover) {
        Column(
            modifier
                .fillMaxWidth()
                .raftOverlaySurface(colors.layerPopover, colors.lineSeam, RaftTheme.isDark, RaftRadius.Dialog, 0.dp)
                .then(if (clearNavigationBar) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) else Modifier),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(RaftDialogSheetDefaults.HeadHeight)
                    .padding(start = RaftBackButtonDefaults.EdgeInset, end = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RaftBackButton(onClick = onClose, label = closeLabel, glyph = RaftGlyph.X, enabled = closeEnabled)
                RaftText(
                    title,
                    Modifier.weight(1f).semantics { heading() },
                    style = RaftTheme.typography.headline,
                    color = colors.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (commitLabel != null) {
                    RaftButton(
                        text = commitLabel,
                        onClick = onCommit,
                        variant = commitVariant,
                        size = RaftButtonSize.Md,
                        enabled = commitEnabled,
                        loading = commitLoading,
                        leadingIcon = commitIcon,
                        // The 32dp commit takes a finger's 44dp (Lagoon on #4161; the old footer was 44).
                        widenTouchTarget = true,
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(RaftStroke.Hairline).background(colors.lineSeam))
            Column(Modifier.fillMaxWidth().weight(1f), content = content)
        }
    }
}

/** Metrics of [RaftDialogSheet]. */
object RaftDialogSheetDefaults {
    /** The head: a pushed page's 56dp top bar. */
    val HeadHeight = 56.dp

    /** The sheet's height: 90% of the window. */
    const val HeightFraction = 0.9f

    /**
     * The narrowest window that shows the dialog centred instead of as a sheet. raft-ui Dialog
     * "On a phone": "On a desktop and a tablet every dialog stays centered". The web switches
     * at its `md` breakpoint (768px), the same width where its layout goes wide; the app goes
     * wide at [RaftScale.WideMinWidth] (744, the iPad mini upright), so a dialog switches with
     * the layout and the type: a window with the rail and two panes gets the centred dialog, a
     * phone or a split-screen half gets the sheet. Every form dialog reads this one constant
     * (this sheet and the app's `RaftUnifiedDialog`).
     */
    val CenteredMinWindowWidth = RaftScale.WideMinWidth

    /** The centred dialog's width: raft-ui Dialog `md`, a small task. */
    val CenteredWidth = RaftDialogSize.Md.maxWidth

    /**
     * The centred dialog's tallest height; a shorter window keeps it 16dp from each edge. Its
     * body is a list or a form that fills what it is given, so the dialog stays one height.
     */
    val CenteredMaxHeight = 640.dp
}

/** True when a window [windowWidth] wide shows [RaftDialogSheet] as a centred dialog. */
fun raftDialogSheetCentered(windowWidth: Dp): Boolean = windowWidth >= RaftDialogSheetDefaults.CenteredMinWindowWidth


/**
 * raft-ui Dialog with several fields, a long list or steps.
 *
 * On a phone (a window under [RaftDialogSheetDefaults.CenteredMinWindowWidth]):
 * [RaftDialogSheetSurface] at the bottom of the window over the scrim, 90% of the window tall,
 * sliding up as it opens (240ms, `ease-slide`).
 *
 * On a tablet or a large screen: a centred dialog, as on a desktop ([RaftDialogSheetCenteredSurface]):
 * the title with × at the end, the body, and a footer with a ghost Cancel ([cancelLabel]) and the
 * commit button. It appears without animation.
 *
 * Either way × (and Cancel) and Back call [onDismissRequest]; a tap on the scrim does not, so a
 * stray tap loses nothing. [closeEnabled] false disables × and Cancel while the commit is in
 * flight (web `closeDisabled`); the caller's [onDismissRequest] should ignore Back then too. Use it for a dialog with several fields, a long list or steps; a
 * decision, a single input or content that is only read stays [RaftDialog].
 */
@Composable
fun RaftDialogSheet(
    onDismissRequest: () -> Unit,
    title: String,
    commitLabel: String?,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
    commitEnabled: Boolean = true,
    commitLoading: Boolean = false,
    commitVariant: RaftButtonVariant = RaftButtonVariant.Primary,
    commitIcon: (@Composable () -> Unit)? = null,
    closeLabel: String = LocalRaftStrings.current.close,
    cancelLabel: String = LocalRaftStrings.current.cancel,
    closeEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    RaftWindowDialog(onDismissRequest = onDismissRequest) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (raftDialogSheetCentered(maxWidth)) {
                Box(
                    Modifier
                        .fillMaxSize()
                        // Clear of the system bars, the cutout and the keyboard, then 16dp.
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    RaftDialogSheetCenteredSurface(
                        title = title,
                        commitLabel = commitLabel,
                        onCommit = onCommit,
                        onClose = onDismissRequest,
                        modifier = modifier.pointerInput(Unit) { detectTapGestures { } },
                        commitEnabled = commitEnabled,
                        commitLoading = commitLoading,
                        commitVariant = commitVariant,
                        commitIcon = commitIcon,
                        closeLabel = closeLabel,
                        cancelLabel = cancelLabel,
                        closeEnabled = closeEnabled,
                        content = content,
                    )
                }
            } else {
                RaftDialogSheetBottom(
                    title = title,
                    commitLabel = commitLabel,
                    onCommit = onCommit,
                    onClose = onDismissRequest,
                    modifier = modifier,
                    commitEnabled = commitEnabled,
                    commitLoading = commitLoading,
                    commitVariant = commitVariant,
                    commitIcon = commitIcon,
                    closeLabel = closeLabel,
                    closeEnabled = closeEnabled,
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun RaftDialogSheetBottom(
    title: String,
    commitLabel: String?,
    onCommit: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
    commitEnabled: Boolean,
    commitLoading: Boolean,
    commitVariant: RaftButtonVariant,
    commitIcon: (@Composable () -> Unit)?,
    closeLabel: String,
    closeEnabled: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val slide = remember { Animatable(1f) }
    LaunchedEffect(Unit) { slide.animateTo(0f, tween(240, easing = RaftMotion.EaseSlide)) }
    // 90% of the whole window, as raft-ui's `h-[90dvh]` and RaftDialog's sheet; the status bar
    // or a top cutout only caps it (it never reaches under them). Padding the top first made the
    // sheet 90% of what is left below the status bar, so it sat ~4dp per 40dp of bar lower.
    val topInsetPx = WindowInsets.safeDrawing.getTop(LocalDensity.current)
    Box(
        Modifier
            .fillMaxSize()
            // Never under a side cutout; the bottom is the sheet's own.
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        RaftDialogSheetSurfaceImpl(
            title = title,
            commitLabel = commitLabel,
            onCommit = onCommit,
            onClose = onClose,
            modifier =
                modifier
                    .graphicsLayer { translationY = size.height * slide.value }
                    .pointerInput(Unit) { detectTapGestures { } }
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(raftDialogSheetConstraints(constraints, topInsetPx))
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    },
            commitEnabled = commitEnabled,
            commitLoading = commitLoading,
            commitVariant = commitVariant,
            commitIcon = commitIcon,
            closeLabel = closeLabel,
            closeEnabled = closeEnabled,
            clearNavigationBar = true,
            content = content,
        )
    }
}

/**
 * [RaftDialogSheet] on a tablet or a large screen, the surface only: raft-ui's centred Dialog
 * (`md`, 448dp) for a dialog with several fields or a long list.
 *
 * - Surface: `layer-popover`, the dialog radius, the `line-seam` ring and `shadow-float`; at
 *   most [RaftDialogSheetDefaults.CenteredMaxHeight] tall.
 * - Header: [RaftDialogHeader], the [title] with the ghost × ([closeLabel]) at its end.
 * - Body: [content] under the header, 6dp below it, filling the height it is given (a lazy
 *   list scrolls itself); keep a 16dp margin inside it, as on the phone sheet.
 * - Footer: a ghost Cancel ([cancelLabel], calls [onClose]) and the commit button
 *   ([commitLabel], [commitVariant], [commitIcon]) at the end, `Lg`, 8dp apart, 20dp from the edges.
 */
@Composable
fun RaftDialogSheetCenteredSurface(
    title: String,
    commitLabel: String?,
    onCommit: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    commitEnabled: Boolean = true,
    commitLoading: Boolean = false,
    commitVariant: RaftButtonVariant = RaftButtonVariant.Primary,
    commitIcon: (@Composable () -> Unit)? = null,
    closeLabel: String = LocalRaftStrings.current.close,
    cancelLabel: String = LocalRaftStrings.current.cancel,
    closeEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = RaftTheme.colors
    CompositionLocalProvider(LocalRaftContentColor provides colors.foreground, LocalRaftSurfaceColor provides colors.layerPopover) {
        Column(
            modifier
                .widthIn(max = RaftDialogSheetDefaults.CenteredWidth)
                .fillMaxWidth()
                .heightIn(max = RaftDialogSheetDefaults.CenteredMaxHeight)
                .raftOverlaySurface(colors.layerPopover, colors.lineSeam, RaftTheme.isDark, RaftRadius.Dialog),
        ) {
            RaftDialogHeader(title = title, onClose = onClose, closeLabel = closeLabel, closeEnabled = closeEnabled)
            Column(
                Modifier.fillMaxWidth().weight(1f, fill = false).padding(top = 6.dp),
                content = content,
            )
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RaftButton(text = cancelLabel, onClick = onClose, variant = RaftButtonVariant.Ghost, size = RaftButtonSize.Lg, enabled = closeEnabled)
                if (commitLabel != null) {
                    RaftButton(
                        text = commitLabel,
                        onClick = onCommit,
                        variant = commitVariant,
                        size = RaftButtonSize.Lg,
                        enabled = commitEnabled,
                        loading = commitLoading,
                        leadingIcon = commitIcon,
                    )
                }
            }
        }
    }
}

/**
 * A fixed 90% of the window's height, never taller than the window below [topInsetPx] (the status
 * bar or a top cutout); unchanged when the height is unbounded.
 */
internal fun raftDialogSheetConstraints(constraints: Constraints, topInsetPx: Int = 0): Constraints =
    if (!constraints.hasBoundedHeight) {
        constraints
    } else {
        val ninety = (constraints.maxHeight * RaftDialogSheetDefaults.HeightFraction).toInt()
        val height =
            minOf(ninety, constraints.maxHeight - topInsetPx.coerceAtLeast(0)).coerceAtLeast(constraints.minHeight)
        constraints.copy(minHeight = height, maxHeight = height)
    }
