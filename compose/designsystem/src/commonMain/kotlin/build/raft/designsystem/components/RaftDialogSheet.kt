package build.raft.designsystem.components

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.motion.RaftMotion
import build.raft.designsystem.motion.rememberRaftReducedMotion
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.LocalRaftStrings
import build.raft.designsystem.theme.LocalRaftSurfaceColor
import build.raft.designsystem.theme.RaftScale
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.theme.raftPlatformUnmeasuredWindowWidth
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke

/**
 * raft-ui Dialog "On a phone" (stdrc and Artea, 2026-10-21; its height Kirby, 2026-11-27 in
 * #proj-mobile:c2a6f94a), the surface only: a dialog with several fields, a long list or steps is
 * a sheet on a phone, as tall as its content and at most 90% of the screen.
 *
 * - Surface: `layer-popover`, the dialog radius on its top corners, the `line-seam` ring.
 * - Head: a pushed page's head, 56dp with a seam under it: × at the start
 *   ([RaftCloseButton], named [closeLabel]), the [title] in
 *   `text-headline`, and the dialog's one commit button at the end ([commitLabel], 32dp,
 *   [commitVariant]: the same colour and words as on a desktop). No footer: × is Cancel.
 *   A null [commitLabel] leaves the head without one, for a view of the dialog that commits
 *   nothing (the web drops its `DialogCommit` there too, as Invite's link tab does).
 *   [commitIcon] leads its label, as the web's commit keeps its icon in the head (Invite's
 *   Mail). [commitLoading] shows the button's spinner while the commit runs (the web's
 *   `loading` Save): it keeps its colour and ignores taps (RaftButton's busy state).
 * - Body: [content] under the head, as tall as it is, up to the rest of the sheet's cap; a body
 *   taller than that (a lazy list, a scrolling column) scrolls inside it. Don't fill the height
 *   (`fillMaxSize`, `fillMaxHeight`, a `weight(1f)` spacer): that holds the sheet at its cap. A
 *   change of the body's height (a step, a list that loads) grows or shrinks the sheet over
 *   [RaftMotion.ResizeMillis] (at once with reduced motion). It clears the home indicator; keep
 *   the phone's 16dp margin inside it.
 * - [footerLink]: what the web puts in the dialog's footer as a link (`DialogSheetFooterLink`).
 *   A phone sheet has no footer, so it ends the body as a full-width outline button (32dp, a
 *   finger's 44dp to tap): a quiet link on a phone reads as plain text.
 * - A dialog with steps passes [onBack] from its second step on: the head's start button is then
 *   a back arrow ([RaftGlyph.ArrowLeft], named [backLabel], which the caller localizes) that goes to the previous step instead of
 *   closing (raft-ui `DialogSheetBack`), with no × beside it (raft-ui dialog.md, stdrc 2026-10-22: the
 *   sheet is a stack of steps and Back undoes one, as on a pushed page; people go back to the first
 *   step and close it with ×).
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
    onBack: (() -> Unit)? = null,
    backLabel: String = "",
    footerLink: RaftDialogSheetLink? = null,
    content: @Composable ColumnScope.() -> Unit,
) = RaftDialogSheetSurfaceImpl(title, commitLabel, onCommit, onClose, modifier, commitEnabled, commitLoading, commitVariant, commitIcon, closeLabel, closeEnabled, clearNavigationBar = false, onBack = onBack, backLabel = backLabel, footerLink = footerLink, content = content)

/**
 * A link the web writes in the dialog's footer (`DialogSheetFooterLink`, "Connect your
 * servers"): a full-width outline button at the end of [RaftDialogSheetSurface]'s body on a
 * phone, a muted link at the start of [RaftDialogSheetCenteredSurface]'s footer.
 */
@Immutable
data class RaftDialogSheetLink(
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    /** A test tag on the button or the link. */
    val testTag: String? = null,
)

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
    onBack: (() -> Unit)?,
    backLabel: String,
    footerLink: RaftDialogSheetLink?,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = RaftTheme.colors
    val reducedMotion = rememberRaftReducedMotion()
    val navigationBarInsets = rememberRaftStableWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
    CompositionLocalProvider(LocalRaftContentColor provides colors.foreground, LocalRaftSurfaceColor provides colors.layerPopover) {
        Column(
            modifier
                .fillMaxWidth()
                .raftOverlaySurface(colors.layerPopover, colors.lineSeam, RaftTheme.isDark, RaftRadius.Dialog, 0.dp)
                .then(if (clearNavigationBar) Modifier.windowInsetsPadding(navigationBarInsets) else Modifier),
        ) {
            // The head's buttons are bar buttons (28dp under a fine pointer).
            CompositionLocalProvider(LocalRaftBarButtons provides true) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(RaftDialogSheetDefaults.HeadHeight)
                        .padding(start = RaftBackButtonDefaults.EdgeInset, end = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onBack != null) {
                        RaftBackButton(onClick = onBack, label = backLabel, enabled = closeEnabled)
                    } else {
                        RaftCloseButton(onClick = onClose, label = closeLabel, enabled = closeEnabled)
                    }
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
            }
            Box(Modifier.fillMaxWidth().height(RaftStroke.Hairline).background(colors.lineSeam))
            // As tall as the body, up to what the cap leaves under the head (a longer body scrolls
            // in it); its height changes slide instead of jumping. The bottom inset (the home
            // indicator, the keyboard) is the surface's padding, so the keyboard is not animated here.
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .then(
                        if (reducedMotion) {
                            Modifier
                        } else {
                            Modifier.animateContentSize(tween(RaftMotion.ResizeMillis, easing = RaftMotion.EaseSlide))
                        },
                    ),
            ) {
                Column(Modifier.fillMaxWidth().weight(1f, fill = false), content = content)
                if (footerLink != null) {
                    RaftButton(
                        text = footerLink.label,
                        onClick = footerLink.onClick,
                        modifier =
                            Modifier
                                // 8dp above and 16dp below keep the 44dp reach clear of the body and the edge.
                                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)
                                .fillMaxWidth()
                                .then(footerLink.testTag?.let { Modifier.testTag(it) } ?: Modifier),
                        variant = RaftButtonVariant.Default,
                        size = RaftButtonSize.Md,
                        enabled = footerLink.enabled,
                        widenTouchTarget = true,
                    )
                }
            }
        }
    }
}

/** Metrics of [RaftDialogSheet]. */
object RaftDialogSheetDefaults {
    /**
     * The head: a pushed page's top bar, 56dp with a finger, 44 under a fine pointer (sizing v12,
     * raft-ui 0.65 desktop density, [build.raft.designsystem.theme.RaftControlMetrics.headHeight]).
     */
    val HeadHeight: Dp
        @Composable @ReadOnlyComposable
        get() = RaftTheme.controls.headHeight

    /**
     * The phone sheet's tallest height: 90% of the window (it is as tall as its content below
     * that; Kirby, 2026-11-27 in #proj-mobile:c2a6f94a, was a fixed 90%).
     */
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


internal fun raftDialogSheetWindowProperties(centered: Boolean): RaftWindowDialogProperties =
    if (centered) {
        RaftWindowDialogProperties(drawScrim = true, platformTransition = true)
    } else {
        RaftWindowDialogProperties(drawScrim = false, platformTransition = false)
    }

/**
 * raft-ui Dialog with several fields, a long list or steps.
 *
 * On a phone (a window under [RaftDialogSheetDefaults.CenteredMinWindowWidth]):
 * [RaftDialogSheetSurface] at the bottom of the window over the scrim, as tall as its content and
 * at most 90% of the window (a longer body scrolls inside), sliding up as it opens (240ms,
 * `ease-slide`); a change of the content's height slides too. The keyboard pushes it up, as before.
 *
 * On a tablet or a large screen: a centred dialog, as on a desktop ([RaftDialogSheetCenteredSurface]):
 * the title with × at the end, the body, and a footer with a ghost Cancel ([cancelLabel]) and the
 * commit button. It appears without animation. [footerStart] and [cancelVisible] are the centred
 * footer's ([RaftDialogSheetCenteredSurface]); a phone sheet has no footer. [footerLink] is the
 * web's footer link: in the centred footer's start, and on a phone a full-width outline button at
 * the end of the body, so callers pass it here rather than drawing it in the body.
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
    onBack: (() -> Unit)? = null,
    backLabel: String = "",
    footerStart: (@Composable RowScope.() -> Unit)? = null,
    cancelVisible: Boolean = true,
    footerLink: RaftDialogSheetLink? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val hostWindowWidthPx = LocalWindowInfo.current.containerSize.width
    val hostWindowWidthDp =
        if (hostWindowWidthPx > 0) {
            with(LocalDensity.current) { hostWindowWidthPx.toDp() }
        } else {
            raftPlatformUnmeasuredWindowWidth()
        }
    val windowCentered = hostWindowWidthDp?.let(::raftDialogSheetCentered) ?: true
    val dialogProperties = raftDialogSheetWindowProperties(centered = windowCentered)
    RaftWindowDialog(onDismissRequest = onDismissRequest, properties = dialogProperties) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (raftDialogSheetCentered(maxWidth)) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(if (!dialogProperties.drawScrim) Modifier.background(RaftTheme.scrim) else Modifier)
                        // Clear of the system bars, the cutout and the keyboard, then 16dp.
                        .windowInsetsPadding(rememberRaftStableWindowInsets(WindowInsets.safeDrawing))
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
                        onBack = onBack,
                        backLabel = backLabel,
                        footerStart = footerStart,
                        cancelVisible = cancelVisible,
                        footerLink = footerLink,
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
                    onBack = onBack,
                    backLabel = backLabel,
                    drawSyncedScrim = !dialogProperties.drawScrim,
                    footerLink = footerLink,
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
    onBack: (() -> Unit)?,
    backLabel: String,
    drawSyncedScrim: Boolean,
    footerLink: RaftDialogSheetLink?,
    content: @Composable ColumnScope.() -> Unit,
) {
    val reducedMotion = rememberRaftReducedMotion()
    val slide = remember { Animatable(if (reducedMotion) 0f else 1f) }
    LaunchedEffect(reducedMotion) {
        if (reducedMotion) {
            slide.snapTo(0f)
        } else {
            withFrameNanos { }
            slide.animateTo(0f, tween(240, easing = RaftMotion.EaseSlide))
        }
    }
    // At most 90% of the whole window (raft-ui's `90dvh`, now a cap); the status bar
    // or a top cutout only caps it (it never reaches under them). Padding the top first made the
    // sheet 90% of what is left below the status bar, so it sat ~4dp per 40dp of bar lower.
    val topInsetPx = WindowInsets.safeDrawing.getTop(LocalDensity.current)
    Box(Modifier.fillMaxSize()) {
        if (drawSyncedScrim) {
            val scrim = RaftTheme.scrim
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 1f - slide.value }
                    .background(scrim),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                // Never under a side cutout; the bottom is the sheet's own.
                .windowInsetsPadding(rememberRaftStableWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))),
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
                onBack = onBack,
                backLabel = backLabel,
                footerLink = footerLink,
                content = content,
            )
        }
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
 * - Footer: a ghost Cancel ([cancelLabel], calls [onClose]; [backLabel] and [onBack] instead from a step's second page on) and the commit button
 *   ([commitLabel], [commitVariant], [commitIcon]) at the end, `Lg`, 8dp apart, 20dp from the edges.
 *   Cancel shows whether or not there is a commit button: a view with nothing to commit still
 *   has its way out, as the web footer's Cancel does when a question card has no submit.
 * - [footerStart]: what the web writes in `DialogFooter` before the buttons (`mr-auto`), at the
 *   footer's start, the buttons staying at its end: a question card's "Submitted · Undo".
 * - [cancelVisible] false leaves Cancel (or Back) out of the footer, as the web footer does while
 *   a question card's answer waits for Undo; the header's × still closes.
 * - [footerLink]: the web's `DialogSheetFooterLink`, a muted callout link at the footer's start
 *   (after [footerStart] when there are both).
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
    onBack: (() -> Unit)? = null,
    backLabel: String = "",
    footerStart: (@Composable RowScope.() -> Unit)? = null,
    cancelVisible: Boolean = true,
    footerLink: RaftDialogSheetLink? = null,
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
                if (footerStart != null || footerLink != null) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        footerStart?.invoke(this)
                        if (footerLink != null) {
                            RaftTextLink(
                                text = footerLink.label,
                                onClick = footerLink.onClick,
                                modifier = footerLink.testTag?.let { Modifier.testTag(it) } ?: Modifier,
                                variant = RaftTextLinkVariant.Muted,
                                style = RaftTheme.typography.callout,
                                enabled = footerLink.enabled,
                            )
                        }
                    }
                }
                // From a step's second page on, Cancel is Back (the header's × still closes).
                if (cancelVisible) {
                    RaftButton(
                        text = if (onBack != null) backLabel else cancelLabel,
                        onClick = onBack ?: onClose,
                        variant = RaftButtonVariant.Ghost,
                        size = RaftButtonSize.Lg,
                        enabled = closeEnabled,
                    )
                }
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
 * At most 90% of the window's height and never taller than the window below [topInsetPx] (the
 * status bar or a top cutout), as short as the sheet's content; unchanged when the height is
 * unbounded.
 */
internal fun raftDialogSheetConstraints(constraints: Constraints, topInsetPx: Int = 0): Constraints =
    if (!constraints.hasBoundedHeight) {
        constraints
    } else {
        val ninety = (constraints.maxHeight * RaftDialogSheetDefaults.HeightFraction).toInt()
        val cap = minOf(ninety, constraints.maxHeight - topInsetPx.coerceAtLeast(0)).coerceAtLeast(constraints.minHeight)
        constraints.copy(minHeight = constraints.minHeight, maxHeight = cap)
    }
