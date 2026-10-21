package build.raft.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import build.raft.designsystem.gesture.RaftBlockBackSwipe
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
 * - Body: [content] fills the rest of the sheet as given (a lazy list scrolls itself) and
 *   clears the home indicator; keep the phone's 16dp margin inside it.
 */
@Composable
fun RaftDialogSheetSurface(
    title: String,
    commitLabel: String,
    onCommit: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    commitEnabled: Boolean = true,
    commitVariant: RaftButtonVariant = RaftButtonVariant.Primary,
    closeLabel: String = LocalRaftStrings.current.close,
    content: @Composable ColumnScope.() -> Unit,
) = RaftDialogSheetSurfaceImpl(title, commitLabel, onCommit, onClose, modifier, commitEnabled, commitVariant, closeLabel, clearNavigationBar = false, content)

@Composable
private fun RaftDialogSheetSurfaceImpl(
    title: String,
    commitLabel: String,
    onCommit: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
    commitEnabled: Boolean,
    commitVariant: RaftButtonVariant,
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
                RaftButton(
                    text = commitLabel,
                    onClick = onCommit,
                    variant = commitVariant,
                    size = RaftButtonSize.Md,
                    enabled = commitEnabled,
                )
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
}

/** raft `--ease-slide`. */
private val RaftDialogSheetEase = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

/**
 * raft-ui Dialog on a phone: [RaftDialogSheetSurface] at the bottom of the window over the
 * scrim, 90% of the window tall, sliding up as it opens (240ms, `ease-slide`). × and Back
 * call [onDismissRequest]; a tap on the scrim does not, so a stray tap loses nothing. Use it
 * on a phone for a dialog with several fields, a long list or steps; a decision, a single
 * input or content that is only read stays [RaftDialog].
 */
@Composable
fun RaftDialogSheet(
    onDismissRequest: () -> Unit,
    title: String,
    commitLabel: String,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
    commitEnabled: Boolean = true,
    commitVariant: RaftButtonVariant = RaftButtonVariant.Primary,
    closeLabel: String = LocalRaftStrings.current.close,
    content: @Composable ColumnScope.() -> Unit,
) {
    // A right swipe must not pop the page underneath an open sheet.
    RaftBlockBackSwipe()
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false),
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
                onClose = onDismissRequest,
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
                closeLabel = closeLabel,
                clearNavigationBar = true,
                content = content,
            )
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
