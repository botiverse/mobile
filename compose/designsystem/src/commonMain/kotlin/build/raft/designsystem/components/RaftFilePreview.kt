package build.raft.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke

// raft-ui FilePreview (raft-design 1fc7546f packages/ui/src/components/file-preview/
// file-preview.recipe.ts, file-preview.tsx): a 40dp file glyph that names a file's type, as a
// card's leading mark. The page (`h-10 w-8 rounded-md rounded-tr-[15%] ring-1 ring-line-hairline`)
// is a document (`bg-layer-card` with four rows of `ink-10` text lines), code (`<tag>` rows) or
// media (`bg-fill-muted`, blank: the picture itself is shown elsewhere); [badge] tags its corner.

/** Which page [RaftFilePreview] draws. */
enum class RaftFilePreviewKind {
    /** `FilePreviewDocument`: text lines. */
    Document,

    /** `FilePreviewCode`: markup rows. */
    Code,

    /** `FilePreviewMedia`: a blank muted page. */
    Media,
}

/**
 * raft-ui `FilePreview` with one page ([kind]) and, when [badge] is set, a `FilePreviewBadge` on
 * its corner (`absolute -right-1.5 bottom-0.5`: Space Mono 10 bold `foreground-muted` on
 * `fill-muted`, the sticker radius, a `line-hairline` ring). Decorative: hidden from
 * accessibility (the card's title names the file).
 */
@Composable
fun RaftFilePreview(
    kind: RaftFilePreviewKind,
    modifier: Modifier = Modifier,
    badge: String? = null,
) {
    Box(modifier.size(40.dp).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        when (kind) {
            RaftFilePreviewKind.Document -> FilePreviewDocument()
            RaftFilePreviewKind.Code -> FilePreviewCode()
            RaftFilePreviewKind.Media -> FilePreviewPage(RaftTheme.colors.fillMuted) {}
        }
        if (badge != null) {
            FilePreviewBadge(
                badge,
                // `-right-1.5 bottom-0.5`: past the root's end by 6dp, 2dp above its bottom.
                Modifier.align(Alignment.BottomEnd).offset(x = 6.dp, y = (-2).dp),
            )
        }
    }
}

/** `rounded-md rounded-tr-[15%]`: 6dp, the top-end corner 15% of the page's width. */
private object FilePreviewPageShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val base = CornerSize(with(density) { 6.dp.toPx() })
        val corner = CornerSize(size.width * 0.15f)
        return RoundedCornerShape(topStart = base, topEnd = corner, bottomEnd = base, bottomStart = base)
            .createOutline(size, layoutDirection, density)
    }
}

/** The page: 32 x 40dp, [fill], with its `ring-1 ring-line-hairline` drawn outside it. */
@Composable
private fun FilePreviewPage(fill: Color, content: @Composable BoxScope.() -> Unit) {
    val ring = RaftTheme.colors.lineHairline
    Box(
        Modifier
            .size(width = 32.dp, height = 40.dp)
            .drawBehind {
                // `ring-1` is a box-shadow: a 1dp band just outside the page.
                val stroke = RaftStroke.Hairline.toPx()
                val outline = FilePreviewPageShape.createOutline(Size(size.width + stroke, size.height + stroke), layoutDirection, this)
                translate(-stroke / 2, -stroke / 2) { drawOutline(outline, ring, style = Stroke(stroke)) }
            }
            .clip(FilePreviewPageShape)
            .background(fill),
    ) {
        content()
    }
}

/** `FilePreviewDocument`: `p-1.5 stack-y-1.5`, a full line then three split ones, 2dp tall, `ink-10`. */
@Composable
private fun FilePreviewDocument() {
    val line = RaftTheme.colors.ink10
    FilePreviewPage(RaftTheme.colors.layerCard) {
        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FilePreviewLine(line, Modifier.fillMaxWidth())
            FilePreviewSplitLine(line, 1f / 3f)
            FilePreviewSplitLine(line, 1f / 2f)
            FilePreviewSplitLine(line, 1f / 3f)
        }
    }
}

@Composable
private fun FilePreviewSplitLine(color: Color, first: Float) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        FilePreviewLine(color, Modifier.weight(first))
        FilePreviewLine(color, Modifier.weight(1f - first))
    }
}

@Composable
private fun FilePreviewLine(color: Color, modifier: Modifier) {
    Box(modifier.height(2.dp).background(color, RoundedCornerShape(50)))
}

/**
 * `FilePreviewCode`: six rows (`stack-y-px`, lifted 2dp) of `<` bar `>` in 4sp Space Mono at
 * `foreground/30`, the bars `h-0.5` at `foreground/40` (tags), `/25` (attributes) and `/10` (text).
 */
@Composable
private fun FilePreviewCode() {
    val ink = RaftTheme.colors.foreground
    FilePreviewPage(RaftTheme.colors.layerCard) {
        Column(
            Modifier.align(Alignment.Center).offset(y = (-2).dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            CodeRow(indent = false) { CodeGlyph("<"); CodeBar(12.dp, ink.copy(alpha = 0.4f)); CodeGlyph(">") }
            CodeRow(indent = true) { CodeGlyph("<"); CodeBar(10.dp, ink.copy(alpha = 0.25f)); CodeGlyph(">") }
            Row(Modifier.padding(start = 12.dp)) { CodeBar(24.dp, ink.copy(alpha = 0.1f)) }
            CodeRow(indent = true) { CodeGlyph("</"); CodeBar(10.dp, ink.copy(alpha = 0.25f)); CodeGlyph(">") }
            CodeRow(indent = true) { CodeGlyph("<"); CodeBar(8.dp, ink.copy(alpha = 0.4f)); CodeGlyph("/>") }
            CodeRow(indent = false) { CodeGlyph("</"); CodeBar(12.dp, ink.copy(alpha = 0.4f)); CodeGlyph(">") }
        }
    }
}

@Composable
private fun CodeRow(indent: Boolean, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.padding(start = if (indent) 6.dp else 0.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun CodeGlyph(text: String) {
    RaftText(
        text,
        style = RaftTheme.typography.monoMeta.copy(fontSize = 4.sp, lineHeight = 4.sp),
        color = RaftTheme.colors.foreground.copy(alpha = 0.3f),
        maxLines = 1,
        softWrap = false,
    )
}

@Composable
private fun CodeBar(width: Dp, color: Color) {
    Box(Modifier.size(width = width, height = 2.dp).background(color, RoundedCornerShape(50)))
}

/** `FilePreviewBadge` (corner): `px-1 py-0.5`, Space Mono 10 bold, `leading-none`. */
@Composable
private fun FilePreviewBadge(text: String, modifier: Modifier) {
    val colors = RaftTheme.colors
    val shape = RoundedCornerShape(RaftRadius.Sticker)
    val ring = colors.lineHairline
    Box(
        modifier
            .drawBehind {
                // `ring-1`: outside the chip, taking no layout room.
                val stroke = RaftStroke.Hairline.toPx()
                drawRoundRect(
                    color = ring,
                    topLeft = Offset(-stroke, -stroke),
                    size = Size(size.width + stroke * 2, size.height + stroke * 2),
                    cornerRadius = CornerRadius(RaftRadius.Sticker.toPx() + stroke),
                )
            }
            .background(colors.fillMuted, shape)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        RaftText(
            text,
            style = RaftTheme.typography.monoMeta.copy(fontSize = 10.sp, lineHeight = 10.sp, fontWeight = FontWeight.Bold),
            color = colors.foregroundMuted,
            maxLines = 1,
            softWrap = false,
        )
    }
}
