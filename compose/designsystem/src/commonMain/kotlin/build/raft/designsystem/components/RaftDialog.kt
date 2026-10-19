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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
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
 *   sides and bottom, 6dp under the header. It scrolls when the dialog reaches the window.
 * - [footer]: at most two buttons at the end, 8dp apart: a ghost Cancel that says what
 *   stays, then the confirm button (`Lg`).
 */
@Composable
fun RaftDialogSurface(
    title: String,
    modifier: Modifier = Modifier,
    size: RaftDialogSize = RaftDialogSize.Md,
    description: String? = null,
    onClose: (() -> Unit)? = null,
    closeLabel: String = "Close",
    footer: (@Composable RowScope.() -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    CompositionLocalProvider(LocalRaftContentColor provides colors.foregroundMuted) {
        Column(
            modifier
                .widthIn(max = size.maxWidth)
                .fillMaxWidth()
                .raftOverlaySurface(colors.layerPopover, colors.lineSeam, RaftTheme.isDark, RaftRadius.Dialog),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp),
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
                        RaftIconButton(onClick = onClose, contentDescription = closeLabel, variant = RaftButtonVariant.Ghost) {
                            RaftGlyphIcon(RaftGlyph.X, size = 18.dp)
                        }
                    }
                }
            }
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
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp, top = if (hasBody) 0.dp else 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    content = footer,
                )
            } else if (!hasBody) {
                Box(Modifier.padding(bottom = 20.dp))
            }
        }
    }
}

/**
 * raft-ui 0.8.0 Dialog: [RaftDialogSurface] centred over the scrim, 16dp from the window's
 * sides and never taller than the window less 32dp. It appears and goes without
 * animation. Back, × and Cancel call [onDismissRequest]; so does a tap on the scrim
 * unless [dismissOnClickOutside] is false (pass false for a long form, so a stray tap
 * doesn't lose it). To confirm an action, use the same surface with a coloured confirm
 * button (AlertDialog).
 *
 * The scrim is the platform dialog's (about 60% black on Android and desktop; raft
 * `layer-backdrop` is 65% in light, 60% in dark).
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
    closeLabel: String = "Close",
    footer: (@Composable RowScope.() -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(dismissOnClickOutside) { detectTapGestures { if (dismissOnClickOutside) onDismissRequest() } }
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
 * - [content] under it, 16dp at the sides.
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

@Composable
private fun RaftSheetSurfaceImpl(
    title: String,
    modifier: Modifier,
    description: String?,
    clearNavigationBar: Boolean,
    actions: (@Composable ColumnScope.() -> Unit)?,
    content: (@Composable ColumnScope.() -> Unit)?,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    CompositionLocalProvider(LocalRaftContentColor provides colors.foreground) {
        Column(
            modifier
                .widthIn(max = 448.dp)
                .fillMaxWidth()
                .heightIn(min = 256.dp)
                .raftOverlaySurface(colors.layerPopover, colors.lineSeam, RaftTheme.isDark, RaftRadius.Dialog, 0.dp)
                // `pb-[env(safe-area-inset-bottom)]`: the fill runs under the bar, the content clears it.
                .then(if (clearNavigationBar) Modifier.navigationBarsPadding() else Modifier),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                RaftText(title, Modifier.semantics { heading() }, style = typography.headline, color = colors.foreground)
                if (description != null) RaftText(description, style = typography.body, color = colors.foregroundMuted)
            }
            if (content != null) {
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                    content = content,
                )
            }
            if (actions != null) {
                Box(Modifier.weight(1f, fill = false))
                Column(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = actions,
                )
            }
        }
    }
}

/** raft `--ease-slide`. */
private val RaftSheetEase = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

/**
 * raft-ui 0.8.0 Drawer, bottom sheet: [RaftSheetSurface] at the bottom of the window over
 * the scrim, at most 82% of the window tall, clear of the navigation bar. It slides up
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
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        val slide = remember { Animatable(1f) }
        LaunchedEffect(Unit) { slide.animateTo(0f, tween(240, easing = RaftSheetEase)) }
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { onDismissRequest() } },
            contentAlignment = Alignment.BottomCenter,
        ) {
            RaftSheetSurfaceImpl(
                title = title,
                clearNavigationBar = true,
                modifier =
                    modifier
                        .graphicsLayer { translationY = size.height * slide.value }
                        .pointerInput(Unit) { detectTapGestures { } }
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints.copy(maxHeight = (constraints.maxHeight * 0.82f).toInt()))
                            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                        },
                description = description,
                actions = actions,
                content = content,
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
