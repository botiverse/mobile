package com.tencent.kuiklybase.markdown.compose

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.intellij.markdown.IElementType

/**
 * Slock: the web's vertical margins of one block (CSS `margin-top` / `margin-bottom`), and how much
 * of each the block already paints itself as padding (a code surface's or quote's own inset).
 *
 * Sibling blocks are separated as CSS collapses margins: by the larger of the upper block's bottom
 * and the lower block's top, minus what either already paints. A [barrier] block keeps its legacy
 * spacing: nothing is added next to it.
 */
@Immutable
data class RaftMarkdownBlockMargin(
    val top: Dp = 0.dp,
    val bottom: Dp = 0.dp,
    val paintedTop: Dp = 0.dp,
    val paintedBottom: Dp = 0.dp,
    val barrier: Boolean = false,
) {
    companion object {
        val Barrier = RaftMarkdownBlockMargin(barrier = true)
    }
}

/**
 * Slock: block margins by node type; `null` (the default) keeps the legacy dense stacking. A type
 * mapped to `null` renders nothing of its own (EOL, list markers, quote markers) and is skipped.
 */
val LocalRaftMarkdownBlockMargins =
    staticCompositionLocalOf<((IElementType) -> RaftMarkdownBlockMargin?)?> { null }

/** The collapsed gap between two sibling blocks, net of what they already paint. */
fun raftMarkdownCollapsedBlockGap(upper: RaftMarkdownBlockMargin, lower: RaftMarkdownBlockMargin): Dp {
    if (upper.barrier || lower.barrier) return 0.dp
    val collapsed = maxOf(upper.bottom, lower.top)
    return (collapsed - upper.paintedBottom - lower.paintedTop).coerceAtLeast(0.dp)
}

/**
 * Tracks the previous sibling block while a column of blocks is composed and emits the collapsed
 * gap before each block. Not a Compose state: it lives for one composition of the column.
 */
class RaftMarkdownBlockGapTracker(private val margins: (IElementType) -> RaftMarkdownBlockMargin?) {
    private var previous: RaftMarkdownBlockMargin? = null

    @Composable
    fun Before(type: IElementType) {
        val margin = margins(type) ?: return
        val upper = previous
        previous = margin
        if (upper == null) return
        val gap = raftMarkdownCollapsedBlockGap(upper, margin)
        if (gap > 0.dp) Spacer(Modifier.height(gap))
    }
}
