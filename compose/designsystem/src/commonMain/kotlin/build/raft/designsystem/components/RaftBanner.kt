package build.raft.designsystem.components

import androidx.compose.foundation.background
import build.raft.designsystem.theme.LocalRaftStrings
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.LocalRaftSurfaceColor
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.tokens.RaftUiThemeColors

/** raft-ui Banner `status`: the tint, and the one icon of each intent. */
enum class RaftBannerStatus(internal val glyph: RaftGlyph?) {
    /** Information and limits: `info-soft` (cyan, never yellow). */
    Info(RaftGlyph.Info),

    /** Something to watch that can be recovered from, including errors. */
    Warning(RaftGlyph.TriangleAlert),

    /** A consequence that cannot be undone, usually in a delete confirmation. */
    Destructive(RaftGlyph.OctagonAlert),

    /** Something finished or saved. */
    Success(RaftGlyph.CircleCheck),

    /** raft-ui's neutral `fill-muted` banner; prefer one of the four intents. */
    Default(null),
}

/** raft-ui Banner `size`. */
enum class RaftBannerSize {
    /** 12/8 padding, 8 gap, 13/18 text, 16dp icon: under a radio list, in a dense form. */
    Sm,

    /** 14/12 padding, 10 gap, 14/20 text, 20dp icon. */
    Md,

    /** 16/14 padding, otherwise [Md]. */
    Lg,
}

internal fun raftBannerFill(status: RaftBannerStatus, colors: RaftUiThemeColors): Color =
    when (status) {
        RaftBannerStatus.Info -> colors.infoSoft
        RaftBannerStatus.Warning -> colors.warningSoft
        RaftBannerStatus.Destructive -> colors.dangerSoft
        RaftBannerStatus.Success -> colors.successSoft
        RaftBannerStatus.Default -> colors.fillMuted
    }

@Immutable
internal data class RaftBannerMetrics(val paddingX: Dp, val paddingY: Dp, val gap: Dp, val icon: Dp, val iconTop: Dp)

internal fun raftBannerMetrics(size: RaftBannerSize): RaftBannerMetrics =
    when (size) {
        // `[&>svg]:mt-px`: the 16dp icon centred on the 18dp first line.
        RaftBannerSize.Sm -> RaftBannerMetrics(12.dp, 8.dp, 8.dp, 16.dp, 1.dp)
        RaftBannerSize.Md -> RaftBannerMetrics(14.dp, 12.dp, 10.dp, 20.dp, 0.dp)
        RaftBannerSize.Lg -> RaftBannerMetrics(16.dp, 14.dp, 10.dp, 20.dp, 0.dp)
    }

/**
 * raft-ui 0.8.0 Banner: a notice people read before they act, inside a dialog or a form.
 * A soft tint of its [status] with a 1dp inset `line-hairline` ring and the container
 * radius; never an ink border. The status icon (20dp, 16dp at [RaftBannerSize.Sm]) leads,
 * in `foreground`; [title] is a bold first line, [description] the message in
 * `text-callout` (`text-footnote` at `Sm`), never capped in lines. [action] holds at most one
 * 32dp `Default` button (never pink), with a ghost Dismiss before it when the banner can be
 * dismissed: at the end of the last line while the text fits beside it in a wide banner,
 * otherwise on its own line under the text, aligned to the end (raft-ui 0.19). [onDismiss]
 * adds the frameless × of a banner with no [action] (raft-ui `BannerClose`).
 *
 * Destructive banners are announced assertively, the others politely (`role="alert"` /
 * `"status"`). Show at most one banner in one place.
 */
@Composable
fun RaftBanner(
    description: String,
    status: RaftBannerStatus,
    modifier: Modifier = Modifier,
    title: String? = null,
    size: RaftBannerSize = RaftBannerSize.Md,
    showIcon: Boolean = status.glyph != null,
    action: (@Composable RowScope.() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) = RaftBanner(AnnotatedString(description), status, modifier, title, size, showIcon, action, onDismiss)

/**
 * [RaftBanner] whose [description] carries a text action inside the sentence: a strong
 * TextLink appended with [appendRaftTextLink] ("Remove an inactive machine before adding
 * another. Manage Machines"), not a button and not the [action] row.
 */
@Composable
fun RaftBanner(
    description: AnnotatedString,
    status: RaftBannerStatus,
    modifier: Modifier = Modifier,
    title: String? = null,
    size: RaftBannerSize = RaftBannerSize.Md,
    showIcon: Boolean = status.glyph != null,
    action: (@Composable RowScope.() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val metrics = raftBannerMetrics(size)
    val text = if (size == RaftBannerSize.Sm) typography.footnote else typography.callout
    val shape = RoundedCornerShape(RaftRadius.Container)
    val glyph = status.glyph.takeIf { showIcon }
    Row(
        modifier
            .fillMaxWidth()
            .background(raftBannerFill(status, colors), shape)
            .border(RaftStroke.Hairline, colors.lineHairline, shape)
            .padding(horizontal = metrics.paddingX, vertical = metrics.paddingY)
            .semantics {
                liveRegion = if (status == RaftBannerStatus.Destructive) LiveRegionMode.Assertive else LiveRegionMode.Polite
            },
        horizontalArrangement = Arrangement.spacedBy(metrics.gap),
        verticalAlignment = Alignment.Top,
    ) {
        if (glyph != null) {
            RaftGlyphIcon(glyph, Modifier.padding(top = metrics.iconTop), size = metrics.icon, color = colors.foreground)
        }
        val actionContent: (@Composable () -> Unit)? =
            action?.let { content ->
                {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CompositionLocalProvider(
                            LocalRaftContentColor provides colors.foreground,
                            LocalRaftSurfaceColor provides raftBannerFill(status, colors).compositeOver(RaftTheme.surfaceColor),
                        ) { content() }
                    }
                }
            }
        RaftBannerBody(
            title = title?.let { { RaftText(it, style = text.copy(fontWeight = FontWeight.Bold), color = colors.foreground) } },
            description = description,
            textStyle = text,
            action = actionContent,
            modifier = Modifier.weight(1f),
        )
        // raft-ui 0.19 BannerClose: a banner with no button may close with a frameless × at the
        // end of the first line (an action and a close never share a banner).
        if (onDismiss != null && action == null) {
            RaftIconButton(
                onClick = onDismiss,
                tooltip = LocalRaftStrings.current.dismiss,
                variant = RaftButtonVariant.Ghost,
                size = RaftIconButtonSize.Md,
                // banner.tsx `close`: `-my-[calc((--spacing(8)-1lh)/2)] -me-icon-inset`. Centred on
                // the first line and taking only that line's height, so a one-line banner stays as
                // tall as without it; its 16dp icon ends on the banner's padding, the 32dp box
                // overhanging it by the 8dp around the icon.
                modifier =
                    Modifier.layout { measurable, constraints ->
                        val line = text.lineHeight.roundToPx()
                        val inset = ((RaftIconButtonSize.Md.box - 16.dp) / 2).roundToPx()
                        val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                        layout(placeable.width - inset, line) { placeable.place(0, (line - placeable.height) / 2) }
                    },
            ) {
                RaftGlyphIcon(RaftGlyph.X, size = 16.dp)
            }
        }
    }
}

/**
 * raft-ui 0.19 BannerAction placement: the buttons sit at the end of the last line of text,
 * centred on it, while the text fits beside them on one line in a banner at least
 * [RaftBannerInlineActionMinWidth] wide; otherwise they take a line of their own under the
 * text, aligned to the end. (A phone banner is narrower than that, so its buttons always sit
 * on their own line at the end.)
 */
@Composable
private fun RaftBannerBody(
    title: (@Composable () -> Unit)?,
    description: AnnotatedString,
    textStyle: TextStyle,
    action: (@Composable () -> Unit)?,
    modifier: Modifier,
) {
    val colors = RaftTheme.colors
    SubcomposeLayout(modifier) { constraints ->
        val width = constraints.maxWidth
        val loose = Constraints(maxWidth = width)
        val titlePlaceable = title?.let { subcompose("title", it).first().measure(loose) }
        val actionPlaceable = action?.let { subcompose("action", it).first().measure(Constraints()) }
        val gap = 8.dp.roundToPx()
        var lastLineCenter = 0f
        var lines = 1
        val inline =
            actionPlaceable != null &&
                width >= RaftBannerInlineActionMinWidth.roundToPx() &&
                run {
                    // Measured at the width left of the buttons: does the text fit there on one line?
                    subcompose("probe") {
                        RaftText(
                            description,
                            style = textStyle,
                            color = colors.foreground,
                            onTextLayout = { layout ->
                                lines = layout.lineCount
                                lastLineCenter = (layout.getLineTop(layout.lineCount - 1) + layout.getLineBottom(layout.lineCount - 1)) / 2f
                            },
                        )
                    }.first().measure(Constraints(maxWidth = (width - actionPlaceable.width - gap).coerceAtLeast(0)))
                    lines == 1
                }
        val descriptionPlaceable =
            subcompose("description") { RaftText(description, style = textStyle, color = colors.foreground) }
                .first()
                .measure(if (inline) Constraints(maxWidth = (width - actionPlaceable!!.width - gap).coerceAtLeast(0)) else loose)
        val titleHeight = titlePlaceable?.height ?: 0
        val textHeight = titleHeight + descriptionPlaceable.height
        val actionTop = 8.dp.roundToPx()
        val height =
            when {
                actionPlaceable == null -> textHeight
                inline -> maxOf(textHeight, titleHeight + lastLineCenter.toInt() + actionPlaceable.height / 2)
                else -> textHeight + actionTop + actionPlaceable.height
            }
        layout(width, height) {
            titlePlaceable?.place(0, 0)
            descriptionPlaceable.place(0, titleHeight)
            if (actionPlaceable != null) {
                if (inline) {
                    actionPlaceable.place(width - actionPlaceable.width, titleHeight + lastLineCenter.toInt() - actionPlaceable.height / 2)
                } else {
                    actionPlaceable.place(width - actionPlaceable.width, textHeight + actionTop)
                }
            }
        }
    }
}

/** raft-ui: inline actions from 452dp of banner content (a 480dp banner at `md`). */
private val RaftBannerInlineActionMinWidth = 452.dp

/**
 * raft-ui PanelNotice: something in one panel failed. A full-width strip directly under
 * the panel header, no radius, no hairline of its own: the panel header's seam is above
 * it and a `line-seam` below. A status pixel leads the one message (semibold
 * `text-callout`, at most [maxLines] lines), 16dp from the sides on a phone; [actions] sit on the right, centred on the first line.
 * With [stackActions] the actions move to their own row under the message (aligned with the
 * text), so a long message such as a runtime error keeps the full width.
 * [status] is [RaftBannerStatus.Warning] for something to fix, `Destructive` only for
 * what cannot be undone.
 */
@Composable
fun RaftPanelNotice(
    message: String,
    modifier: Modifier = Modifier,
    status: RaftBannerStatus = RaftBannerStatus.Warning,
    maxLines: Int = 2,
    stackActions: Boolean = false,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = RaftTheme.colors
    val seam = colors.lineSeam
    val pixel =
        when (status) {
            RaftBannerStatus.Destructive -> RaftStatusVariant.Danger
            RaftBannerStatus.Info -> RaftStatusVariant.Information
            RaftBannerStatus.Success -> RaftStatusVariant.Success
            RaftBannerStatus.Default -> RaftStatusVariant.Default
            RaftBannerStatus.Warning -> RaftStatusVariant.Warning
        }
    Row(
        modifier
            .fillMaxWidth()
            .background(raftBannerFill(status, colors))
            .drawBehind {
                val w = RaftStroke.Hairline.toPx()
                drawLine(seam, Offset(0f, size.height - w / 2f), Offset(size.width, size.height - w / 2f), strokeWidth = w)
            }
            .heightIn(min = 40.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // The 9dp pixel centred on the 20dp first line.
        Box(Modifier.width(10.dp).height(20.dp), contentAlignment = Alignment.Center) {
            RaftStatusDot(variant = pixel)
        }
        if (stackActions && actions != null) {
            Column(Modifier.weight(1f)) {
                RaftText(
                    message,
                    style = RaftTheme.typography.callout.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.foreground,
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis,
                )
                CompositionLocalProvider(LocalRaftSurfaceColor provides raftBannerFill(status, colors).compositeOver(RaftTheme.surfaceColor)) {
                    Row(
                        Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) { actions() }
                }
            }
            return@Row
        }
        RaftText(
            message,
            modifier = Modifier.weight(1f),
            style = RaftTheme.typography.callout.copy(fontWeight = FontWeight.SemiBold),
            color = colors.foreground,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
        if (actions != null) {
            // `-my-1.5`: a 32dp button doesn't make the strip taller; it centres on the first line.
            Box(Modifier.height(20.dp), contentAlignment = Alignment.Center) {
                CompositionLocalProvider(LocalRaftSurfaceColor provides raftBannerFill(status, colors).compositeOver(RaftTheme.surfaceColor)) {
                    Row(
                        Modifier.wrapContentHeight(unbounded = true),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) { actions() }
                }
            }
        }
    }
}
