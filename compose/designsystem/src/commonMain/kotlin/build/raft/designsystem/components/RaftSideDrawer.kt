package build.raft.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import build.raft.designsystem.motion.RaftMotion
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.LocalRaftStrings
import build.raft.designsystem.theme.LocalRaftSurfaceColor
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftStroke

/**
 * raft-ui Drawer from the side (`swipeDirection="right"`, the x axis), the surface only: a
 * settings panel that slides in over the page (web ChannelSettingsSheet).
 *
 * - Surface: `layer-popover`, square corners, the `line-seam` ring and `shadow-float`; the
 *   whole height it is given, at most [RaftSideDrawerDefaults.MaxWidth] wide (all of a phone).
 * - Head: at least [RaftSideDrawerDefaults.HeadHeight] tall with a seam under it, the phone's
 *   16dp edges: the [title] in `text-headline` on one line, the optional [subtitle] under it
 *   (the caller's line, e.g. "Channel #design" in caption and mono), and the ghost ×
 *   ([closeLabel]) at the end, its glyph on the head's edge (raft-ui PanelAction).
 * - Body: [content] fills the rest as given (a lazy list scrolls itself); keep
 *   [RaftSideDrawerDefaults.BodyPadding] inside it (`px-5 py-4`).
 * - Footer: with [footer], a seam over a row of buttons at the end (`DrawerActions` on the x
 *   axis: `flex-row justify-end`, 8dp apart, `px-5 py-3`): a ghost Cancel, then the commit.
 *
 * [insets] pads everything inside the surface while its fill runs under them (the status bar,
 * the home indicator, the keyboard).
 */
@Composable
fun RaftSideDrawerSurface(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: (@Composable () -> Unit)? = null,
    closeLabel: String = LocalRaftStrings.current.close,
    closeEnabled: Boolean = true,
    insets: PaddingValues = PaddingValues(0.dp),
    footer: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = RaftTheme.colors
    CompositionLocalProvider(LocalRaftContentColor provides colors.foreground, LocalRaftSurfaceColor provides colors.layerPopover) {
        Column(
            modifier
                .widthIn(max = RaftSideDrawerDefaults.MaxWidth)
                .fillMaxWidth()
                .fillMaxHeight()
                .raftOverlaySurface(colors.layerPopover, colors.lineSeam, RaftTheme.isDark, 0.dp)
                .padding(insets),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = RaftSideDrawerDefaults.HeadHeight)
                    .padding(horizontal = RaftSideDrawerDefaults.HeadEdge),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    RaftText(
                        title,
                        Modifier.semantics { heading() },
                        style = RaftTheme.typography.headline,
                        color = colors.foreground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    subtitle?.invoke()
                }
                // The 32dp ghost × pulled out by its icon inset, so its glyph sits on the edge.
                Box(Modifier.raftBleed(end = 7.dp)) {
                    RaftIconButton(onClick = onClose, tooltip = closeLabel, variant = RaftButtonVariant.Ghost, enabled = closeEnabled) {
                        RaftGlyphIcon(RaftGlyph.X, size = 18.dp)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(RaftStroke.Hairline).background(colors.lineSeam))
            Column(Modifier.fillMaxWidth().weight(1f), content = content)
            if (footer != null) {
                Box(Modifier.fillMaxWidth().height(RaftStroke.Hairline).background(colors.lineSeam))
                CompositionLocalProvider(LocalRaftButtonsStacked provides false) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                        content = footer,
                    )
                }
            }
        }
    }
}

/** Metrics of [RaftSideDrawer]. */
object RaftSideDrawerDefaults {
    /** The widest it gets (raft-ui Drawer x axis `max-w-[420px]`); a phone gets all of its width. */
    val MaxWidth = 420.dp

    /** The head: the shell header's 56dp. */
    val HeadHeight = 56.dp

    /** The head's edges on a phone (raft-ui head edges: 16px on a phone). */
    val HeadEdge = 16.dp

    /** The body's padding (raft-ui Drawer x axis body `px-5 py-4`). */
    val BodyPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)

    /** The slide in from the side (raft-ui drawer, `ease-slide`). */
    const val EnterMillis = 240
}

/**
 * raft-ui Drawer from the side: [RaftSideDrawerSurface] at the end of the window over the
 * scrim, the window's full height, sliding in from the end (240ms, `ease-slide`). On a phone it
 * takes the whole window (the scrim is under it); a wider window gets a panel
 * [RaftSideDrawerDefaults.MaxWidth] wide on the end with the page dimmed beside it.
 *
 * × and Back call [onDismissRequest]; so does a tap on the scrim unless [dismissOnScrimTap] is
 * false (a form with unsaved edits: web `disablePointerDismissal`). The surface pads for the
 * system bars, the cutout and the keyboard. Swipe to dismiss is not ported: × is the way out.
 */
@Composable
fun RaftSideDrawer(
    onDismissRequest: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: (@Composable () -> Unit)? = null,
    closeLabel: String = LocalRaftStrings.current.close,
    closeEnabled: Boolean = true,
    dismissOnScrimTap: Boolean = true,
    footer: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dismiss by rememberUpdatedState(onDismissRequest)
    val scrimDismisses by rememberUpdatedState(dismissOnScrimTap)
    RaftWindowDialog(onDismissRequest = onDismissRequest) {
        val slide = remember { Animatable(1f) }
        LaunchedEffect(Unit) { slide.animateTo(0f, tween(RaftSideDrawerDefaults.EnterMillis, easing = RaftMotion.EaseSlide)) }
        val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { if (scrimDismisses) dismiss() } },
            contentAlignment = Alignment.CenterEnd,
        ) {
            RaftSideDrawerSurface(
                title = title,
                onClose = onDismissRequest,
                modifier =
                    modifier
                        .graphicsLayer { translationX = size.width * slide.value * direction }
                        .pointerInput(Unit) { detectTapGestures { } },
                subtitle = subtitle,
                closeLabel = closeLabel,
                closeEnabled = closeEnabled,
                insets = WindowInsets.safeDrawing.asPaddingValues(),
                footer = footer,
                content = content,
            )
        }
    }
}
