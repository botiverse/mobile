package build.raft.designsystem.components

import androidx.compose.foundation.background
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
 * `text-callout` (`text-footnote` at `Sm`), never capped in lines. [action] is a row under
 * the text for at most one 32dp `Default` button (never pink).
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
) = RaftBanner(AnnotatedString(description), status, modifier, title, size, showIcon, action)

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
        Column(Modifier.weight(1f)) {
            if (title != null) RaftText(title, style = text.copy(fontWeight = FontWeight.Bold), color = colors.foreground)
            RaftText(description, style = text, color = colors.foreground)
            if (action != null) {
                Row(
                    Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CompositionLocalProvider(
                        LocalRaftContentColor provides colors.foreground,
                        LocalRaftSurfaceColor provides raftBannerFill(status, colors).compositeOver(RaftTheme.surfaceColor),
                    ) { action() }
                }
            }
        }
    }
}

/**
 * raft-ui PanelNotice: something in one panel failed. A full-width strip directly under
 * the panel header, no radius, no hairline of its own: the panel header's seam is above
 * it and a `line-seam` below. A status pixel leads the one message (semibold
 * `text-callout`, at most 2 lines), 16dp from the sides on a phone; [actions] sit on the right, centred on the first line.
 * [status] is [RaftBannerStatus.Warning] for something to fix, `Destructive` only for
 * what cannot be undone.
 */
@Composable
fun RaftPanelNotice(
    message: String,
    modifier: Modifier = Modifier,
    status: RaftBannerStatus = RaftBannerStatus.Warning,
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
        Box(Modifier.width(9.dp).height(20.dp), contentAlignment = Alignment.Center) {
            RaftStatusDot(variant = pixel)
        }
        RaftText(
            message,
            modifier = Modifier.weight(1f),
            style = RaftTheme.typography.callout.copy(fontWeight = FontWeight.SemiBold),
            color = colors.foreground,
            maxLines = 2,
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
