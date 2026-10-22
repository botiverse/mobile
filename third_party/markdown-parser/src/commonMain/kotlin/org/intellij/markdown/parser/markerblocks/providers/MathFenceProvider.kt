package org.intellij.markdown.parser.markerblocks.providers

import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.LookaheadText
import org.intellij.markdown.parser.MarkerProcessor
import org.intellij.markdown.parser.ProductionHolder
import org.intellij.markdown.parser.constraints.MarkdownConstraints
import org.intellij.markdown.parser.markerblocks.MarkerBlock
import org.intellij.markdown.parser.markerblocks.MarkerBlockProvider
import org.intellij.markdown.parser.markerblocks.impl.MathFenceMarkerBlock
import org.intellij.markdown.parser.sequentialparsers.SequentialParser

/**
 * Block math: a fence of two or more `$` on its own line, the formula, and a
 * closing fence of at least as many `$`, as micromark-extension-math's
 * `mathFlow` reads it (Raft's web, botiverse/slock `raftMath()`).
 *
 * The opening fence may be indented up to three spaces and followed by text
 * (micromark's "meta", ignored here) that contains no `$`; so `$$x$$` alone on
 * a line is not a fence and stays inline math in a paragraph. A fence can
 * interrupt a paragraph, and an unclosed one runs to the end of its container,
 * like a code fence.
 */
class MathFenceProvider : MarkerBlockProvider<MarkerProcessor.StateInfo> {
    override fun createMarkerBlocks(
        pos: LookaheadText.Position,
        productionHolder: ProductionHolder,
        stateInfo: MarkerProcessor.StateInfo
    ): List<MarkerBlock> {
        val opening = openingFence(pos, stateInfo.currentConstraints) ?: return emptyList()
        productionHolder.addProduction(
            listOf(SequentialParser.Node(pos.offset..pos.nextLineOrEofOffset, GFMTokenTypes.MATH_FENCE_START))
        )
        return listOf(
            MathFenceMarkerBlock(stateInfo.currentConstraints, productionHolder, opening.size, opening.indent)
        )
    }

    override fun interruptsParagraph(pos: LookaheadText.Position, constraints: MarkdownConstraints): Boolean =
        openingFence(pos, constraints) != null

    private class OpeningFence(val size: Int, val indent: Int)

    private fun openingFence(pos: LookaheadText.Position, constraints: MarkdownConstraints): OpeningFence? {
        if (!MarkerBlockProvider.isStartOfLineWithConstraints(pos, constraints)) return null
        val line = pos.currentLineFromPosition
        var index = 0
        while (index < line.length && line[index] == ' ') index++
        val indent = index
        if (indent > 3) return null
        while (index < line.length && line[index] == '$') index++
        val size = index - indent
        if (size < 2) return null
        // The rest of the line is micromark's meta: anything but a `$`.
        for (rest in index until line.length) {
            if (line[rest] == '$') return null
        }
        return OpeningFence(size, indent)
    }
}
