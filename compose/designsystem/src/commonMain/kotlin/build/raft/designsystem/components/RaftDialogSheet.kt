package build.raft.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.LocalRaftStrings
import build.raft.designsystem.theme.LocalRaftSurfaceColor
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
 *   Mail).
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
    commitVariant: RaftButtonVariant = RaftButtonVariant.Primary,
    commitIcon: (@Composable () -> Unit)? = null,
    closeLabel: String = LocalRaftStrings.current.close,
    content: @Composable ColumnScope.() -> Unit,
) = RaftDialogSheetSurfaceImpl(title, commitLabel, onCommit, onClose, modifier, commitEnabled, commitVariant, commitIcon, closeLabel, clearNavigationBar = false, content)

@Composable
private fun RaftDialogSheetSurfaceImpl(
    title: String,
    commitLabel: String?,
    onCommit: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
    commitEnabled: Boolean,
    commitVariant: RaftButtonVariant,
    commitIcon: (@Composable () -> Unit)?,
    closeLabel: String,
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
                RaftBackButton(onClick = onClose, label = closeLabel, glyph = RaftGlyph.X)
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
                        leadingIcon = commitIcon,
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
     * "On a phone": "On a desktop and a tablet every dialog stays centered". 600dp is the
     * narrowest tablet window (Android's compact/medium width class), so a tablet in portrait
     * and the app's 840dp two-pane layout both get the centred dialog, a phone never does.
     */
    val CenteredMinWindowWidth = 600.dp

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

/** raft `--ease-slide`. */
private val RaftDialogSheetEase = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

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
 * stray tap loses nothing. Use it for a dialog with several fields, a long list or steps; a
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
    commitVariant: RaftButtonVariant = RaftButtonVariant.Primary,
    commitIcon: (@Composable () -> Unit)? = null,
    closeLabel: String = LocalRaftStrings.current.close,
    cancelLabel: String = LocalRaftStrings.current.cancel,
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
                        commitVariant = commitVariant,
                        commitIcon = commitIcon,
                        closeLabel = closeLabel,
                        cancelLabel = cancelLabel,
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
                    commitVariant = commitVariant,
                    commitIcon = commitIcon,
                    closeLabel = closeLabel,
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
    commitVariant: RaftButtonVariant,
    commitIcon: (@Composable () -> Unit)?,
    closeLabel: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val slide = remember { Animatable(1f) }
    LaunchedEffect(Unit) { slide.animateTo(0f, tween(240, easing = RaftDialogSheetEase)) }
    Box(
        Modifier
            .fillMaxSize()
            // Never under the status bar or a side cutout; the bottom is the sheet's own.
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
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
                        val placeable = measurable.measure(raftDialogSheetConstraints(constraints))
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    },
            commitEnabled = commitEnabled,
            commitVariant = commitVariant,
            commitIcon = commitIcon,
            closeLabel = closeLabel,
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
    commitVariant: RaftButtonVariant = RaftButtonVariant.Primary,
    commitIcon: (@Composable () -> Unit)? = null,
    closeLabel: String = LocalRaftStrings.current.close,
    cancelLabel: String = LocalRaftStrings.current.cancel,
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
            RaftDialogHeader(title = title, onClose = onClose, closeLabel = closeLabel)
            Column(
                Modifier.fillMaxWidth().weight(1f, fill = false).padding(top = 6.dp),
                content = content,
            )
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RaftButton(text = cancelLabel, onClick = onClose, variant = RaftButtonVariant.Ghost, size = RaftButtonSize.Lg)
                if (commitLabel != null) {
                    RaftButton(
                        text = commitLabel,
                        onClick = onCommit,
                        variant = commitVariant,
                        size = RaftButtonSize.Lg,
                        enabled = commitEnabled,
                        leadingIcon = commitIcon,
                    )
                }
            }
        }
    }
}

/** A fixed 90% of the window's height (unchanged when the height is unbounded). */
internal fun raftDialogSheetConstraints(constraints: Constraints): Constraints =
    if (!constraints.hasBoundedHeight) {
        constraints
    } else {
        val height = (constraints.maxHeight * RaftDialogSheetDefaults.HeightFraction).toInt().coerceAtLeast(constraints.minHeight)
        constraints.copy(minHeight = height, maxHeight = height)
    }
