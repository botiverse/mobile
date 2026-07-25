package org.intellij.markdown.flavours.gfm.table

import org.intellij.markdown.parser.LookaheadText
import org.intellij.markdown.parser.MarkerProcessor
import org.intellij.markdown.parser.ProductionHolder
import org.intellij.markdown.parser.constraints.MarkdownConstraints
import org.intellij.markdown.parser.constraints.eatItselfFromString
import org.intellij.markdown.parser.constraints.extendsPrev
import org.intellij.markdown.parser.markerblocks.MarkerBlock
import org.intellij.markdown.parser.markerblocks.MarkerBlockProvider

class GitHubTableMarkerProvider(
    private val tableContinuationColumns: ((LookaheadText.Position, MarkdownConstraints) -> Int?)? = null,
) : MarkerBlockProvider<MarkerProcessor.StateInfo> {
    override fun createMarkerBlocks(pos: LookaheadText.Position, productionHolder: ProductionHolder, stateInfo: MarkerProcessor.StateInfo): List<MarkerBlock> {
        val currentConstraints = stateInfo.currentConstraints
        if (stateInfo.nextConstraints != currentConstraints) {
            return emptyList()
        }

        val continuationColumns = tableContinuationColumns?.invoke(pos, currentConstraints)
        val numberOfHeaderCells = continuationColumns ?: stockHeaderCellCount(pos.currentLineFromPosition)
        if (numberOfHeaderCells == 0) {
            return emptyList()
        }
        if (continuationColumns != null) {
            return listOf(GitHubTableMarkerBlock(pos, currentConstraints, productionHolder, numberOfHeaderCells))
        }
        val nextLine = getNextLineFromConstraints(pos, currentConstraints) ?: return emptyList()
        if (countSecondLineCells(nextLine) == numberOfHeaderCells) {
            return listOf(GitHubTableMarkerBlock(pos, currentConstraints, productionHolder, numberOfHeaderCells))
        }
        return emptyList()
    }

    override fun interruptsParagraph(pos: LookaheadText.Position, constraints: MarkdownConstraints): Boolean {
        return tableContinuationColumns?.invoke(pos, constraints) != null
    }

    private fun stockHeaderCellCount(line: CharSequence): Int {
        if (!line.contains('|')) return 0
        val split = GitHubTableMarkerBlock.splitByPipes(line)
        return split
                .mapIndexed { i, s -> (i > 0 && i < split.lastIndex) || s.isNotBlank() }
                .count { it }
    }

    private fun getNextLineFromConstraints(pos: LookaheadText.Position, constraints: MarkdownConstraints): CharSequence? {
        val line = pos.nextLine ?: return null
        val nextLineConstraints = constraints.applyToNextLine(pos.nextLinePosition())
        if (nextLineConstraints.extendsPrev(constraints)) {
            return nextLineConstraints.eatItselfFromString(line)
        } else {
            return null
        }
    }

    companion object {
        /**
         * @return number of cells in the separator line
         */
        fun countSecondLineCells(line: CharSequence): Int {
            var offset = passWhiteSpaces(line, 0)
            if (offset < line.length && line[offset] == '|') {
                offset++
            }

            var result = 0
            while (offset < line.length) {
                offset = passWhiteSpaces(line, offset)
                if (offset < line.length && line[offset] == ':') {
                    offset++
                    offset = passWhiteSpaces(line, offset)
                }

                var dashes = 0
                while (offset < line.length && line[offset] == '-') {
                    offset++
                    dashes++
                }

                if (dashes < 1) {
                    return 0
                }
                result++

                offset = passWhiteSpaces(line, offset)
                if (offset < line.length && line[offset] == ':') {
                    offset++
                    offset = passWhiteSpaces(line, offset)
                }

                if (offset < line.length && line[offset] == '|') {
                    offset++
                    offset = passWhiteSpaces(line, offset)
                } else {
                    break
                }
            }

            if (offset == line.length) {
                return result
            } else {
                return 0
            }
        }

        fun passWhiteSpaces(line: CharSequence, offset: Int): Int {
            var curOffset = offset
            while (curOffset < line.length) {
                if (line[curOffset] != ' ' && line[curOffset] != '\t') {
                    break
                }
                curOffset++
            }
            return curOffset
        }
    }
}
