package build.raft.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftSpacing
import build.raft.designsystem.tokens.RaftStroke

/**
 * raft-ui `ComposerDropOverlay` + `ComposerDropOverlayLabel` (raft-design packages/ui/src/
 * components/composer/composer-drop-overlay.recipe.ts): what the composer shows while files are
 * dragged over it. It covers the composer ([modifier] is usually `matchParentSize()`): a
 * `rounded-container` box with a 1.5dp dashed `line-seam` edge (4dp dashes, 3dp gaps, as
 * [RaftAvatarEmpty]'s CSS-dashed edge), `layer-card` with `info-soft`
 * over it, and [label] centred in `text-callout` semibold `foreground` (web MessageInput:
 * "Drop files to attach"). Hidden from accessibility like the web's `aria-hidden` overlay.
 */
@Composable
fun RaftComposerDropOverlay(label: String, modifier: Modifier = Modifier) {
    val colors = RaftTheme.colors
    val radius = RaftRadius.Container
    val stroke = RaftStroke.Control
    val shape = RaftTheme.shapes.container
    Box(
        modifier
            .clearAndSetSemantics {}
            .background(colors.layerCard, shape)
            .background(colors.infoSoft, shape)
            .drawBehind {
                val inset = stroke.toPx() / 2f
                drawRoundRect(
                    color = colors.lineSeam,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - inset * 2f, size.height - inset * 2f),
                    cornerRadius = CornerRadius((radius.toPx() - inset).coerceAtLeast(0f)),
                    style = Stroke(width = stroke.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                )
            }
            .padding(RaftSpacing.Sm),
        contentAlignment = Alignment.Center,
    ) {
        RaftText(
            text = label,
            style = RaftTheme.typography.callout.copy(fontWeight = FontWeight.SemiBold),
            color = colors.foreground,
            textAlign = TextAlign.Center,
        )
    }
}
