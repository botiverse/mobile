package build.raft.designsystem.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.tokens.RaftUiPalette

// raft-ui Composer > attachments (raft-design packages/ui/src/components/composer/
// composer-attachments.tsx and composer-attachment.recipe.ts `composerAttachment`): the tiles
// above the composer's text for the files about to be sent, their uploading and failed
// overlays and the remove sticker on the corner.

/** A file tile (`attachmentFile`: `h-16 w-44`). */
val RaftComposerAttachmentFileWidth = 176.dp

/** Every tile's height, and an image tile's side (`h-16`, `size-16`). */
val RaftComposerAttachmentTileSize = 64.dp

/**
 * How far the remove sticker overhangs a tile's top and end (`-top-1.5 -right-1.5`). The
 * tray keeps this much room above and after its tiles (`pt-1.5 pe-1.5`), so the sticker is
 * never clipped and its 44dp touch target stays free.
 */
val RaftComposerAttachmentRemoveOverhang = 6.dp

/** Between two tiles, across and down (`gap-2`). */
val RaftComposerAttachmentGap = 8.dp

private val RemoveSize = 20.dp
private val RemoveGlyph = 12.dp
private val ProgressInset = 6.dp
private val ProgressHeight = 4.dp
private val ProgressRadius = 2.dp
private val OverlayInset = 4.dp
private val LabelBarGap = 2.dp

/** How far the remove sticker reaches into a tile from its top edge. */
private val RemoveClearance = RemoveSize - RaftComposerAttachmentRemoveOverhang
private val FailedBottomInset = 2.dp
private const val IndeterminateSegment = 0.4f
private const val MaxLabelLines = 2
private val MinLabelFontSize = 9.sp

/**
 * raft-ui `ComposerAttachment`: one tile in the tray. Put a [RaftComposerAttachmentFile] or
 * [RaftComposerAttachmentImage] in it, then any [RaftComposerAttachmentOverlay], then the
 * [RaftComposerAttachmentRemove] sticker.
 */
@Composable
fun RaftComposerAttachment(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier, content = content)
}

/**
 * Where a tile's transfer stands, for [RaftComposerAttachmentOverlay] (web MessageInput
 * `uploadStatus`): [Checking] the file before it is sent ("validating"), [Uploading] it,
 * [Finishing] (the bytes are sent, the server is confirming) or [Failed].
 */
enum class RaftComposerAttachmentPhase { Checking, Uploading, Finishing, Failed }

/**
 * raft-ui `ComposerAttachmentFile`: a 176 x 64dp `fill-muted` tile with a hairline edge and
 * control radius, 8dp of padding; the [icon] (16dp, `foreground-muted`) 10dp before the
 * [title] (footnote 13/18 semibold, one line) over the [meta] (Space Mono meta,
 * `foreground-hint`, one line), 2dp apart. [onClick] opens a preview. While it uploads or after
 * it fails, a [RaftComposerAttachmentOverlay] goes over the whole tile.
 */
@Composable
fun RaftComposerAttachmentFile(
    title: String,
    meta: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    icon: @Composable () -> Unit,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val shape = RoundedCornerShape(RaftRadius.Control)
    Row(
        modifier =
            modifier
                .width(RaftComposerAttachmentFileWidth)
                .height(RaftComposerAttachmentTileSize)
                .clip(shape)
                .background(colors.fillMuted)
                .border(RaftStroke.Hairline, colors.lineHairline, shape)
                .tileClick(onClick, onClickLabel)
                .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalRaftContentColor provides colors.foregroundMuted, content = icon)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
            RaftText(
                text = title,
                style = typography.footnote.copy(fontWeight = FontWeight.SemiBold),
                color = colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!meta.isNullOrBlank()) {
                RaftText(
                    text = meta,
                    style = typography.monoMeta,
                    color = colors.foregroundHint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** raft-ui `ComposerAttachmentUploadProgressBar`: 4dp, inset 6dp from the sides and bottom. */
@Composable
private fun BoxScope.RaftComposerAttachmentProgressBar(progress: Int) {
    val colors = RaftTheme.colors
    val fraction = progress.coerceIn(4, 100) / 100f
    val shape = RoundedCornerShape(ProgressRadius)
    // Sized by the tile (matchParentSize), so the bar never widens the tile it sits on.
    Box(Modifier.matchParentSize().padding(start = ProgressInset, end = ProgressInset, bottom = ProgressInset)) {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(ProgressHeight)
                .clip(shape)
                .background(colors.layerCard),
        ) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(RaftUiPalette.info, shape))
        }
    }
}

/**
 * The same 4dp bar while the upload finishes (the bytes are sent, the server is confirming):
 * no percent to show, so a [IndeterminateSegment] wide `info` segment sweeps across the track
 * every [RaftProgressBarIndeterminateMillis] (raft-ui Progress's indeterminate sweep). [still]
 * holds it centred (screenshots).
 */
@Composable
private fun BoxScope.RaftComposerAttachmentIndeterminateBar(still: Boolean) {
    val track = RaftTheme.colors.layerCard
    val sweep =
        if (still) {
            RaftProgressBarStillSweep
        } else {
            rememberInfiniteTransition(label = "composer-attachment-finishing").animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec =
                    infiniteRepeatable(
                        animation = tween(RaftProgressBarIndeterminateMillis, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart,
                    ),
                label = "composer-attachment-finishing-sweep",
            ).value
        }
    Box(Modifier.matchParentSize().padding(start = ProgressInset, end = ProgressInset, bottom = ProgressInset)) {
        Canvas(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(ProgressHeight)) {
            val radius = CornerRadius(ProgressRadius.toPx())
            drawRoundRect(color = track, cornerRadius = radius)
            val segment = size.width * IndeterminateSegment
            val start = -segment + (size.width + segment) * sweep
            clipRect { drawRoundRect(color = RaftUiPalette.info, topLeft = Offset(start, 0f), size = Size(segment, size.height), cornerRadius = radius) }
        }
    }
}

/**
 * raft-ui `ComposerAttachmentImage`: a 64dp `fill-muted` square with a hairline edge and
 * control radius, clipping its [content] (the thumbnail, cropped to fill; or a placeholder
 * glyph while there is none). [onClick] opens a preview.
 */
@Composable
fun RaftComposerAttachmentImage(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = RaftTheme.colors
    val shape = RoundedCornerShape(RaftRadius.Control)
    Box(
        modifier =
            modifier
                .size(RaftComposerAttachmentTileSize)
                .clip(shape)
                .background(colors.fillMuted)
                .tileClick(onClick, onClickLabel)
                .border(RaftStroke.Hairline, colors.lineHairline, shape),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalRaftContentColor provides colors.foregroundMuted) { content() }
    }
}

/**
 * The web's upload overlays on any tile, file or image (web MessageInput `SortableAttachment`
 * on raft-ui `ComposerAttachmentUploadingOverlay`, `ComposerAttachmentUploadProgressBar` and
 * `ComposerAttachmentFailedOverlay`). The tile keeps its own layout and size underneath; the
 * overlay never draws outside it.
 *
 * - [Checking][RaftComposerAttachmentPhase.Checking] and [Uploading][RaftComposerAttachmentPhase.Uploading]:
 *   `layer-card` at 80% over the whole tile, then centred a small spinner and 4dp under it
 *   [label] ("Checking file…", "Uploading", "42%") in Space Mono meta bold `foreground`. A
 *   [percent] (the bar's value; null for no bar) runs the 4dp bar inset 6dp from the sides
 *   and bottom (2dp radius, `layer-card` track, `info` fill, at least 4% so it is visible from
 *   the start), and the spinner and label centre above it.
 * - [Finishing][RaftComposerAttachmentPhase.Finishing]: the same, "Finishing upload…", and the
 *   bar sweeps (indeterminate) whatever [percent] says: the bytes are sent and the server has
 *   not confirmed yet. The web holds its bar at 99%.
 * - [Failed][RaftComposerAttachmentPhase.Failed]: `layer-card` at 90% inside a 1dp
 *   `danger-strong` edge, [label] ("Upload failed") centred in caption 12/16 semibold
 *   `danger-strong`. Mobile adds the underlined [action] ("Retry") under it; tapping the tile
 *   does [onAction].
 *
 * Every label stays inside the tile (artin 11-11, a 64dp image tile cut "正在完成上传…" at the
 * screen edge): it wraps over at most two lines, between words or between CJK characters,
 * fewer when the tile has no room for two, and ends in "…" when it is longer. A word wider
 * than the tile ("Uploading", "Finishing" on an image tile) shrinks the label until it fits
 * (not below 9sp) instead of being broken or spilling past the tile's edges.
 *
 * [spinnerStillStep] freezes the spinner and the finishing sweep (screenshots); so does
 * [LocalRaftSpinnerStillStep].
 */
@Composable
fun BoxScope.RaftComposerAttachmentOverlay(
    phase: RaftComposerAttachmentPhase,
    percent: Int?,
    label: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    spinnerStillStep: Int? = null,
) {
    val colors = RaftTheme.colors
    val shape = RoundedCornerShape(RaftRadius.Control)
    if (phase == RaftComposerAttachmentPhase.Failed) {
        val style = RaftTheme.typography.caption.copy(fontWeight = FontWeight.SemiBold)
        val hasAction = !action.isNullOrBlank()
        Column(
            modifier =
                modifier
                    .matchParentSize()
                    .clip(shape)
                    .background(colors.layerVeil)
                    .border(RaftStroke.Hairline, colors.dangerStrong, shape)
                    .tileClick(onAction, action)
                    // Below the remove sticker's corner, so "Upload failed" never runs under the ✕.
                    .padding(start = OverlayInset, end = OverlayInset, top = RemoveClearance, bottom = FailedBottomInset),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            RaftComposerAttachmentOverlayLabel(label, style, colors.dangerStrong, Modifier.weight(1f, fill = false))
            if (hasAction) {
                RaftComposerAttachmentOverlayLabel(
                    action.orEmpty(),
                    style.copy(textDecoration = TextDecoration.Underline),
                    colors.dangerStrong,
                    Modifier,
                    maxLines = 1,
                )
            }
        }
        return
    }
    val finishing = phase == RaftComposerAttachmentPhase.Finishing
    val hasBar = finishing || percent != null
    val stillStep = spinnerStillStep ?: LocalRaftSpinnerStillStep.current
    Column(
        modifier =
            modifier
                .matchParentSize()
                .clip(shape)
                .background(colors.layerVeil)
                // Centred above the bar, so a two-line label never runs into it.
                .padding(
                    start = OverlayInset,
                    end = OverlayInset,
                    top = OverlayInset,
                    bottom = if (hasBar) ProgressInset + ProgressHeight + LabelBarGap else OverlayInset,
                ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        RaftSpinner(size = RaftSpinnerSize.Sm, color = colors.foreground, stillStep = stillStep)
        RaftComposerAttachmentOverlayLabel(
            label,
            RaftTheme.typography.monoMeta.copy(fontWeight = FontWeight.Bold),
            colors.foreground,
            Modifier.weight(1f, fill = false).padding(top = 4.dp),
        )
    }
    when {
        finishing -> RaftComposerAttachmentIndeterminateBar(still = stillStep != null)
        percent != null -> RaftComposerAttachmentProgressBar(percent)
    }
}

/**
 * An overlay's [label], centred and held inside the room the tile leaves it: at most
 * [maxLines] lines (fewer when the height holds fewer), "…" past the last, and shrunk
 * (down to [MinLabelFontSize]) when one unbreakable run, a word or a CJK character, is wider
 * than the tile.
 */
@Composable
private fun RaftComposerAttachmentOverlayLabel(
    label: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier,
    maxLines: Int = MaxLabelLines,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val maxWidth = constraints.maxWidth
        val maxHeight = constraints.maxHeight
        val fitted =
            remember(label, style, maxWidth, maxHeight, maxLines, measurer, density) {
                val widest =
                    raftUnbreakableRuns(label).maxOfOrNull { run ->
                        measurer.measure(run, style, softWrap = false, maxLines = 1, density = density).size.width
                    } ?: 0
                val fitStyle =
                    if (widest <= maxWidth || widest == 0) {
                        style
                    } else {
                        val size = maxOf(MinLabelFontSize.value, floorToHalf(style.fontSize.value * maxWidth / widest))
                        val ratio = size / style.fontSize.value
                        style.copy(fontSize = size.sp, lineHeight = (style.lineHeight.value * ratio).sp)
                    }
                val lineHeight = with(density) { fitStyle.lineHeight.toPx() }
                val lines =
                    if (maxHeight == Constraints.Infinity || lineHeight <= 0f) maxLines
                    else (maxHeight / lineHeight).toInt().coerceIn(1, maxLines)
                fitStyle to lines
            }
        RaftText(
            text = label,
            style = fitted.first,
            color = color,
            textAlign = TextAlign.Center,
            maxLines = fitted.second,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun floorToHalf(value: Float): Float = kotlin.math.floor(value * 2f) / 2f

/**
 * The runs of [text] a line never breaks inside: words between spaces, and each CJK
 * character (Han, kana, Hangul, full-width forms) on its own, as the line breaker may break
 * before and after any of them.
 */
internal fun raftUnbreakableRuns(text: String): List<String> {
    val runs = mutableListOf<String>()
    val current = StringBuilder()
    fun flush() {
        if (current.isNotEmpty()) runs += current.toString()
        current.clear()
    }
    text.forEach { char ->
        when {
            char.isWhitespace() -> flush()
            char.isCjkBreakable() -> {
                flush()
                runs += char.toString()
            }
            else -> current.append(char)
        }
    }
    flush()
    return runs
}

private fun Char.isCjkBreakable(): Boolean =
    this in '\u1100'..'\u11FF' || this in '\u2E80'..'\u9FFF' || this in '\uAC00'..'\uD7AF' ||
        this in '\uF900'..'\uFAFF' || this in '\uFF00'..'\uFFEF'

/**
 * raft-ui `ComposerAttachmentRemove`: a 20dp sticker-radius square on the tile's top-end
 * corner, overhanging it by [RaftComposerAttachmentRemoveOverhang]; `layer-card` with a 1.5dp
 * `line-strong` edge and a 12dp ✕ in `foreground`; pressed `fill-pressed`, the focus ring,
 * disabled (`fill-muted`, hairline edge, `foreground-disabled`). Its touch target reaches
 * 44dp. Always shown: a phone has no hover.
 */
@Composable
fun BoxScope.RaftComposerAttachmentRemove(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
) {
    val colors = RaftTheme.colors
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val isPressed = raftIsPressed(source)
    val isHovered = raftIsHovered(source)
    val isFocused = raftIsFocused(source)
    val pressed = enabled && (isPressed || isHovered)
    val focused = enabled && isFocused
    val shape = RoundedCornerShape(RaftRadius.Sticker)
    Box(
        modifier =
            modifier
                .align(Alignment.TopEnd)
                .offset(x = RaftComposerAttachmentRemoveOverhang, y = -RaftComposerAttachmentRemoveOverhang)
                .size(RemoveSize)
                .raftFocusRing(focused, RaftRadius.Sticker, colors)
                .background(if (enabled) colors.layerCard else colors.fillMuted, shape)
                .raftPressOverlay(pressed, raftPressOverlayColor(RaftPressSurface.Paper, colors), RaftRadius.Sticker)
                .border(RaftStroke.Control, if (enabled) colors.lineStrong else colors.lineHairline, shape)
                .semantics { this.contentDescription = contentDescription }
                .raftTouchTarget(enabled, source, onClick)
                .raftPointerHover(source)
                .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        RaftGlyphIcon(RaftGlyph.X, size = RemoveGlyph, color = if (enabled) colors.foreground else colors.foregroundDisabled)
    }
}

private fun Modifier.tileClick(onClick: (() -> Unit)?, onClickLabel: String?): Modifier =
    if (onClick == null) this else clickable(onClickLabel = onClickLabel, role = Role.Button, indication = null, interactionSource = null, onClick = onClick)
