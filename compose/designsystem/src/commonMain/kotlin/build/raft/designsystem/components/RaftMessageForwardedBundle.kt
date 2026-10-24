package build.raft.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke

// raft-ui MessageForwardedBundle (raft-design packages/ui/src/components/message-forwarded-bundle/
// message-forwarded-bundle.recipe.ts, .tsx, .md) as the web composes it (slock packages/web/src/
// components/message/ForwardedBundleCard.tsx): a card under a message with the messages someone
// forwarded, built on MessageEmbed (`layer-card`, hairline, `rounded-container`).

object RaftMessageForwardedBundleDefaults {
    /** `max-w-[min(34rem,100%)]`. */
    val MaxWidth = 544.dp

    /** The fold: the items' text past `foldLineLimit` (8) lines folds the list at 8 lines. */
    const val FoldLineLimit = 8

    /** `max-h-40`: the fold height when the line height is not known. */
    val FallbackFoldHeight = 160.dp

    /** `after:h-10`: the fade at the bottom of a folded list; `h-5` under a 60dp fold. */
    val FadeHeight = 40.dp
    val TightFadeHeight = 20.dp
    val TightFoldBelow = 60.dp

    /** An item's time: Space Mono `mono-meta`, tabular figures, `foreground-hint`. */
    val TimeStyle: TextStyle
        @Composable get() = RaftTheme.typography.monoMeta.copy(fontFeatureSettings = "tnum")

    val TimeInk: Color
        @Composable get() = RaftTheme.colors.foregroundHint

    /** `gap-2` between gallery rows and tiles. */
    val GalleryGap = 8.dp

    /** The gallery's own width from which a row of three is three across and rows grow. */
    val GalleryWideFrom = 360.dp
}

/** When the footer shows. */
enum class RaftMessageForwardedBundleFooterMode {
    /** raft-ui: only while the list is folded. */
    WhenFolded,

    /** Always, as Raft Mobile's open-the-whole-bundle entry. */
    Always,
}

/**
 * raft-ui `MessageForwardedBundle`: the card (`layer-card`, `line-hairline`, `rounded-container`,
 * at most 544dp wide unless [fullWidth]).
 *
 * The header (12 x 8dp, wraps, items 8dp apart): a 14dp `forward` and [label] ("Forwarded",
 * `footnote` bold, `foreground-muted`, 4dp apart), [countLabel] ("3 messages", Space Mono
 * `mono-meta`, `foreground-hint`) and, at the end, [source] ("from #launch-week",
 * `caption`, `foreground-hint`; semibold and a control when [onOpenSource] is set).
 *
 * [items] ([RaftMessageForwardedBundleItem]s) fold on their own: when their text alone (not the
 * attachments) runs past [foldHeight] (8 lines of the item text,
 * [RaftMessageForwardedBundleDefaults.FallbackFoldHeight] by default) the list is cut at that height and fades out into
 * `layer-card` over its last 40dp. [footerLabel]
 * ("View all 6 messages", `footnote` semibold with a 12dp `chevron-right`, 12 x 8dp) shows per
 * [footerMode] and calls [onFooterClick].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RaftMessageForwardedBundle(
    label: String,
    countLabel: String,
    modifier: Modifier = Modifier,
    source: String? = null,
    onOpenSource: (() -> Unit)? = null,
    footerLabel: String? = null,
    onFooterClick: (() -> Unit)? = null,
    footerMode: RaftMessageForwardedBundleFooterMode = RaftMessageForwardedBundleFooterMode.WhenFolded,
    foldHeight: Dp = RaftMessageForwardedBundleDefaults.FallbackFoldHeight,
    fullWidth: Boolean = false,
    items: @Composable ColumnScope.() -> Unit,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val shape = RoundedCornerShape(RaftRadius.Container)
    Column(
        modifier
            .then(if (fullWidth) Modifier else Modifier.widthIn(max = RaftMessageForwardedBundleDefaults.MaxWidth))
            .fillMaxWidth()
            .clip(shape)
            .background(colors.layerCard, shape)
            .border(RaftStroke.Hairline, colors.lineHairline, shape),
    ) {
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.align(Alignment.CenterVertically),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RaftGlyphIcon(RaftGlyph.Forward, size = 14.dp, color = colors.foregroundMuted)
                    RaftText(
                        label,
                        style = typography.footnote.copy(fontWeight = FontWeight.Bold),
                        color = colors.foregroundMuted,
                        maxLines = 1,
                    )
                }
                RaftText(countLabel, style = typography.monoMeta, color = colors.foregroundHint, maxLines = 1)
            }
            if (source != null) {
                Box(Modifier.weight(1f).align(Alignment.CenterVertically), contentAlignment = Alignment.CenterEnd) {
                    ForwardedSource(source, onOpenSource)
                }
            }
        }
        val folded = remember { mutableStateOf(false) }
        val meter = remember { ForwardedFoldMeter() }
        CompositionLocalProvider(LocalForwardedFoldMeter provides meter) {
            ForwardedFold(foldHeight = foldHeight, folded = folded, meter = meter) {
                Column(Modifier.fillMaxWidth(), content = items)
            }
        }
        val showFooter =
            footerLabel != null &&
                (footerMode == RaftMessageForwardedBundleFooterMode.Always || folded.value)
        if (showFooter && footerLabel != null) {
            ForwardedFooterAction(footerLabel, onFooterClick)
        }
    }
}

@Composable
private fun ForwardedSource(source: String, onOpenSource: (() -> Unit)?) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    if (onOpenSource == null) {
        RaftText(source, style = typography.caption, color = colors.foregroundHint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed = raftIsPressed(interaction)
    RaftText(
        source,
        modifier = Modifier.clickable(interaction, indication = null, role = Role.Button, onClick = onOpenSource),
        style =
            typography.caption.copy(
                fontWeight = FontWeight.SemiBold,
                textDecoration = if (pressed) TextDecoration.Underline else null,
            ),
        color = if (pressed) colors.foreground else colors.foregroundHint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ForwardedFooterAction(label: String, onClick: (() -> Unit)?) {
    val colors = RaftTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val isPressed = raftIsPressed(interaction)
    val pressed = onClick != null && isPressed
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            Modifier.then(
                if (onClick != null) {
                    Modifier.clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            ),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RaftGlyphIcon(RaftGlyph.ChevronRight, size = 12.dp, color = colors.foreground)
            RaftText(
                label,
                style =
                    RaftTheme.typography.footnote.copy(
                        fontWeight = FontWeight.SemiBold,
                        textDecoration = if (pressed) TextDecoration.Underline else null,
                    ),
                color = colors.foreground,
                maxLines = 1,
            )
        }
    }
}

/**
 * The rendered heights of the items' text (`MessageForwardedBundleItemContent`): the fold measures
 * only these, so attachments, galleries and padding never fold a short bundle.
 */
internal class ForwardedFoldMeter {
    val textHeights = mutableStateMapOf<Any, Int>()
}

internal val LocalForwardedFoldMeter = compositionLocalOf<ForwardedFoldMeter?> { null }

/**
 * raft-ui's fold: when the items' text together ([meter]) runs past [foldHeight] (1px of
 * slack), the list is cut at that height and fades into `layer-card` at the bottom
 * (`from-layer-card via-layer-card/80 to-layer-card/0`); the footer then opens the rest.
 */
@Composable
private fun ForwardedFold(
    foldHeight: Dp,
    folded: MutableState<Boolean>,
    meter: ForwardedFoldMeter,
    content: @Composable () -> Unit,
) {
    val surface = RaftTheme.colors.layerCard
    val fade =
        if (foldHeight < RaftMessageForwardedBundleDefaults.TightFoldBelow) {
            RaftMessageForwardedBundleDefaults.TightFadeHeight
        } else {
            RaftMessageForwardedBundleDefaults.FadeHeight
        }
    Layout(
        content = content,
        modifier =
            Modifier
                .fillMaxWidth()
                .clipToBounds()
                .drawWithContent {
                    drawContent()
                    if (folded.value) {
                        val h = fade.toPx().coerceAtMost(size.height)
                        drawRect(
                            Brush.verticalGradient(
                                0f to surface.copy(alpha = 0f),
                                0.5f to surface.copy(alpha = 0.8f),
                                1f to surface,
                                startY = size.height - h,
                                endY = size.height,
                            ),
                            topLeft = Offset(0f, size.height - h),
                            size = Size(size.width, h),
                        )
                    }
                },
    ) { measurables, constraints ->
        val placeable = measurables.first().measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        val limit = foldHeight.roundToPx()
        val folds = meter.textHeights.values.sum() > limit + 1 && placeable.height > limit
        if (folded.value != folds) folded.value = folds
        val height = if (folds) limit else placeable.height
        layout(placeable.width, height) { placeable.place(0, 0) }
    }
}

/**
 * raft-ui `MessageForwardedBundleItem`: one forwarded message, 12 x 10dp. The meta line (wraps,
 * items 6dp apart, 2dp above the text): [author] (`footnote` bold, `foreground`), then a dot and
 * the [time] (Space Mono `mono-meta`, tabular, `foreground-hint`: [RaftMessageForwardedBundleItemTime]
 * or the host's own timestamp in [RaftMessageForwardedBundleDefaults.TimeStyle]). [content] is the
 * text (the host's Markdown body); [attachments] sit 6dp under it, 6dp apart (a
 * [RaftMessageForwardedBundleGallery], then a row of [RaftMessageForwardedBundleAttachment]s).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RaftMessageForwardedBundleItem(
    author: String,
    modifier: Modifier = Modifier,
    time: (@Composable () -> Unit)? = null,
    attachments: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        FlowRow(
            Modifier.padding(bottom = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RaftText(
                author,
                modifier = Modifier.align(Alignment.CenterVertically),
                style = typography.footnote.copy(fontWeight = FontWeight.Bold),
                color = colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (time != null) {
                RaftText(
                    "·",
                    modifier = Modifier.align(Alignment.CenterVertically).clearAndSetSemantics {},
                    style = RaftMessageForwardedBundleDefaults.TimeStyle,
                    color = RaftMessageForwardedBundleDefaults.TimeInk,
                    maxLines = 1,
                )
                Box(Modifier.align(Alignment.CenterVertically)) { time() }
            }
        }
        val meter = LocalForwardedFoldMeter.current
        val key = remember { Any() }
        DisposableEffect(meter, key) { onDispose { meter?.textHeights?.remove(key) } }
        Box(
            if (meter != null) {
                Modifier.onSizeChanged { meter.textHeights[key] = it.height }
            } else {
                Modifier
            },
        ) { content() }
        if (attachments != null) {
            Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = attachments)
        }
    }
}

/** An item's time as text, in [RaftMessageForwardedBundleDefaults.TimeStyle] and `foreground-hint`. */
@Composable
fun RaftMessageForwardedBundleItemTime(text: String, modifier: Modifier = Modifier) {
    RaftText(
        text,
        modifier = modifier,
        style = RaftMessageForwardedBundleDefaults.TimeStyle,
        color = RaftMessageForwardedBundleDefaults.TimeInk,
        maxLines = 1,
        softWrap = false,
    )
}

/** A wrapping row of [RaftMessageForwardedBundleAttachment]s, 6dp apart. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RaftMessageForwardedBundleAttachments(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

/**
 * raft-ui `MessageForwardedBundleAttachment`: a 22dp chip, `fill-muted`, `rounded-chip`, no edge,
 * 5.5dp before its 14dp `file` icon and 6.5dp after its text, 4dp apart: the [name] (`caption`
 * semibold, `foreground`, truncates) and [meta] (the type, Space Mono, `foreground-hint`). With
 * [onClick] a press lays `overlay-medium` over it.
 */
@Composable
fun RaftMessageForwardedBundleAttachment(
    name: String,
    modifier: Modifier = Modifier,
    meta: String? = null,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val shape = RoundedCornerShape(RaftRadius.Chip)
    val interaction = remember { MutableInteractionSource() }
    val isPressed = raftIsPressed(interaction)
    val pressed = onClick != null && isPressed
    Row(
        modifier
            .height(22.dp)
            .clip(shape)
            .background(colors.fillMuted, shape)
            .raftPressOverlay(pressed, colors.fillMedium, RaftRadius.Chip)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interaction, indication = null, role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(start = 5.5.dp, end = 6.5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RaftGlyphIcon(RaftGlyph.File, size = 14.dp, color = colors.foreground)
        RaftText(
            name,
            modifier = Modifier.weight(1f, fill = false),
            style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
            color = colors.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (meta != null) {
            RaftText(
                meta,
                style = typography.caption.copy(fontFamily = typography.monoMeta.fontFamily, fontWeight = FontWeight.Normal),
                color = colors.foregroundHint,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/**
 * raft-ui `MessageForwardedBundleGallery`: forwarded images in rows of three (exactly four split
 * two and two), 8dp apart, each tile a [RaftMessageImageFrame] (the message gallery's cell).
 * By the gallery's own width: below 360dp rows of one or two are 128dp tall and a row of three
 * is two across at 112dp (the third wraps under); from 360dp rows are 144dp and a row of three
 * is three across at 128dp. [tile] draws image [index] into its frame (cropped).
 */
@Composable
fun RaftMessageForwardedBundleGallery(
    count: Int,
    modifier: Modifier = Modifier,
    onClick: ((index: Int) -> Unit)? = null,
    onClickLabel: (index: Int) -> String? = { null },
    tile: @Composable BoxScope.(index: Int) -> Unit,
) {
    if (count <= 0) return
    val chunk = if (count == 4) 2 else 3
    val rows = (0 until count).chunked(chunk)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val available = maxWidth
        val wide = available >= RaftMessageForwardedBundleDefaults.GalleryWideFrom
        val gap = RaftMessageForwardedBundleDefaults.GalleryGap
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            rows.forEach { row ->
                val columns = if (row.size == 3 && !wide) 2 else row.size
                val height =
                    when {
                        row.size == 3 && wide -> 128.dp
                        row.size == 3 -> 112.dp
                        wide -> 144.dp
                        else -> 128.dp
                    }
                val cellWidth = (available - gap * (columns - 1)) / columns
                row.chunked(columns).forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        line.forEach { index ->
                            RaftMessageImageFrame(
                                modifier = Modifier.size(cellWidth, height),
                                onClick = onClick?.let { { it(index) } },
                                onClickLabel = onClickLabel(index),
                            ) { tile(index) }
                        }
                    }
                }
            }
        }
    }
}
