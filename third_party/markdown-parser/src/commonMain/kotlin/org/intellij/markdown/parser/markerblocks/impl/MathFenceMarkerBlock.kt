package org.intellij.markdown.parser.markerblocks.impl

import org.intellij.markdown.IElementType
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.LookaheadText
import org.intellij.markdown.parser.ProductionHolder
import org.intellij.markdown.parser.constraints.MarkdownConstraints
import org.intellij.markdown.parser.constraints.applyToNextLineAndAddModifiers
import org.intellij.markdown.parser.constraints.eatItselfFromString
import org.intellij.markdown.parser.constraints.extendsPrev
import org.intellij.markdown.parser.constraints.getCharsEaten
import org.intellij.markdown.parser.markerblocks.MarkerBlock
import org.intellij.markdown.parser.markerblocks.MarkerBlockImpl
import org.intellij.markdown.parser.sequentialparsers.SequentialParser
import kotlin.math.min

/**
 * The lines of a `$$` math block (see `MathFenceProvider`), modelled on
 * [CodeFenceMarkerBlock]. Each content line becomes a
 * [GFMTokenTypes.MATH_FENCE_CONTENT] token with up to the opening fence's
 * indent removed, and the closing fence a [GFMTokenTypes.MATH_FENCE_END]. The
 * content is never parsed as Markdown.
 */
class MathFenceMarkerBlock(
    myConstraints: MarkdownConstraints,
    private val productionHolder: ProductionHolder,
    private val fenceSize: Int,
    private val fenceIndent: Int,
) : MarkerBlockImpl(myConstraints, productionHolder.mark()) {
    override fun allowsSubBlocks(): Boolean = false

    override fun isInterestingOffset(pos: LookaheadText.Position): Boolean = true

    private var realInterestingOffset = -1

    override fun calcNextInterestingOffset(pos: LookaheadText.Position): Int = pos.nextLineOrEofOffset

    override fun getDefaultAction(): MarkerBlock.ClosingAction = MarkerBlock.ClosingAction.DONE

    override fun doProcessToken(
        pos: LookaheadText.Position,
        currentConstraints: MarkdownConstraints
    ): MarkerBlock.ProcessingResult {
        if (pos.offset < realInterestingOffset) {
            return MarkerBlock.ProcessingResult.CANCEL
        }

        // Eat everything while on a formula line.
        if (pos.offsetInCurrentLine != -1) {
            return MarkerBlock.ProcessingResult.CANCEL
        }

        val nextLineConstraints = constraints.applyToNextLineAndAddModifiers(pos)
        // A lazy line (a container marker missing) ends the math, as in micromark.
        if (!nextLineConstraints.extendsPrev(constraints)) {
            return MarkerBlock.ProcessingResult.DEFAULT
        }

        val nextLineOffset = pos.nextLineOrEofOffset
        realInterestingOffset = nextLineOffset

        val currentLine = nextLineConstraints.eatItselfFromString(pos.currentLine)
        if (closesThisFence(currentLine)) {
            productionHolder.addProduction(
                listOf(SequentialParser.Node(pos.offset + 1..pos.nextLineOrEofOffset, GFMTokenTypes.MATH_FENCE_END))
            )
            scheduleProcessingResult(nextLineOffset, MarkerBlock.ProcessingResult.DEFAULT)
        } else {
            val lineStart = min(pos.offset + 1 + constraints.getCharsEaten(pos.currentLine), nextLineOffset)
            var contentStart = lineStart
            while (contentStart < nextLineOffset &&
                contentStart - lineStart < fenceIndent &&
                pos.originalText[contentStart].let { it == ' ' || it == '\t' }
            ) {
                contentStart++
            }
            if (contentStart < nextLineOffset) {
                productionHolder.addProduction(
                    listOf(SequentialParser.Node(contentStart..nextLineOffset, GFMTokenTypes.MATH_FENCE_CONTENT))
                )
            }
        }

        return MarkerBlock.ProcessingResult.CANCEL
    }

    /** Up to three spaces, at least [fenceSize] `$`, then only spaces or tabs. */
    private fun closesThisFence(line: CharSequence): Boolean {
        var index = 0
        while (index < line.length && line[index] == ' ') index++
        if (index > 3) return false
        val sequenceStart = index
        while (index < line.length && line[index] == '$') index++
        if (index - sequenceStart < fenceSize) return false
        while (index < line.length) {
            when (line[index]) {
                ' ', '\t', '\r' -> index++
                else -> return false
            }
        }
        return true
    }

    override fun getDefaultNodeType(): IElementType = GFMElementTypes.BLOCK_MATH
}
