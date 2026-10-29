package build.raft.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.ui.layout.layout
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
 * `ComposerAttachmentFailedOverlay`). The tile keeps its own layout underneath.
 *
 * - [Checking][RaftComposerAttachmentPhase.Checking], [Uploading][RaftComposerAttachmentPhase.Uploading]
 *   and [Finishing][RaftComposerAttachmentPhase.Finishing]: `layer-card` at 80% over the whole
 *   tile, then centred a small spinner and 4dp under it [label] ("Checking file…", "Uploading",
 *   "42%", "Finishing upload…") in Space Mono meta bold `foreground`, wrapping between words
 *   (centred above the bar when there is one). A [percent] (the bar's
 *   value; null for no bar) runs the 4dp bar inset 6dp from the sides and bottom (2dp radius,
 *   `layer-card` track, `info` fill, at least 4% so it is visible from the start).
 * - [Failed][RaftComposerAttachmentPhase.Failed]: `layer-card` at 90% inside a 1dp
 *   `danger-strong` edge, [label] ("Upload failed") centred in caption 12/16 semibold
 *   `danger-strong`. Mobile adds the underlined [action] ("Retry") under it; tapping the tile
 *   does [onAction].
 *
 * [spinnerStillStep] freezes the spinner (screenshots).
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
        Column(
            modifier =
                modifier
                    .matchParentSize()
                    .clip(shape)
                    .background(colors.layerVeil)
                    .border(RaftStroke.Hairline, colors.dangerStrong, shape)
                    .tileClick(onAction, action)
                    .padding(horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            RaftText(text = label, style = style, color = colors.dangerStrong, textAlign = TextAlign.Center)
            if (!action.isNullOrBlank()) {
                RaftText(
                    text = action,
                    style = style.copy(textDecoration = TextDecoration.Underline),
                    color = colors.dangerStrong,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
        return
    }
    Column(
        modifier =
            modifier
                .matchParentSize()
                .background(colors.layerVeil, shape)
                // Centred above the bar, so a two-line label on an image tile never runs into it.
                .padding(start = 4.dp, end = 4.dp, bottom = if (percent != null) ProgressInset + ProgressHeight else 0.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        RaftSpinner(size = RaftSpinnerSize.Sm, color = colors.foreground, stillStep = spinnerStillStep)
        // Like the web's span: it wraps between words ("Checking / file…" on a 64dp image
        // tile), and a single word wider than the tile ("Uploading") stays whole, centred.
        val style = RaftTheme.typography.monoMeta.copy(fontWeight = FontWeight.Bold)
        val measurer = rememberTextMeasurer()
        val longestWord =
            remember(label, style, measurer) {
                label.split(' ').filter { it.isNotEmpty() }.maxOfOrNull { word ->
                    measurer.measure(word, style, softWrap = false, maxLines = 1).size.width
                } ?: 0
            }
        RaftText(
            text = label,
            modifier =
                Modifier.padding(top = 4.dp).layout { measurable, constraints ->
                    val placeable =
                        measurable.measure(constraints.copy(minWidth = 0, maxWidth = maxOf(constraints.maxWidth, longestWord + 1)))
                    val width = placeable.width.coerceAtMost(constraints.maxWidth)
                    layout(width, placeable.height) { placeable.place((width - placeable.width) / 2, 0) }
                },
            style = style,
            color = colors.foreground,
            textAlign = TextAlign.Center,
        )
    }
    if (percent != null) RaftComposerAttachmentProgressBar(percent)
}

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
