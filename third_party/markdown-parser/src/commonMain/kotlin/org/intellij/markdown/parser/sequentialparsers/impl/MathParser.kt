package org.intellij.markdown.parser.sequentialparsers.impl

import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.sequentialparsers.RangesListBuilder
import org.intellij.markdown.parser.sequentialparsers.SequentialParser
import org.intellij.markdown.parser.sequentialparsers.TokensCache

/**
 * Inline math with Obsidian's (Pandoc's) dollar rules, as Raft's web reads it
 * (botiverse/slock `packages/shared/src/markdownMath.ts`, `raftMath()`).
 *
 * A run of dollars opens math and a run of the same length closes it, so
 * `$$…$$` within a line is inline math. A single pair `$…$` is math only when
 * it reads as math rather than as money:
 *  - the opening `$` is not followed by whitespace or the end of the text;
 *  - the closing `$` is not preceded by whitespace and not followed by a digit;
 *  - it stays on one line;
 *  - its content does not contain CJK text without a LaTeX command (a backslash
 *    followed by an ASCII letter; botiverse/slock #16343).
 * So `$x^2$` and `$\text{速度}=v$` are math, while `$5 到 $10`, `$5,$10` and
 * `「US $10」，只写「$」` stay text. A dollar run that does not close is part of
 * the math, and the search goes on. `\$` lexes as escaped text, never as a
 * dollar run. Block math (`$$` on its own lines) is a block, see
 * `MathFenceProvider`.
 *
 * Every node is always [GFMElementTypes.INLINE_MATH]; the stock parser made
 * `$$…$$` a BLOCK_MATH inside the paragraph.
 */
class MathParser : SequentialParser {
    override fun parse(tokens: TokensCache, rangesToGlue: List<IntRange>): SequentialParser.ParsingResult {
        val result = SequentialParser.ParsingResultBuilder()
        val delegateIndices = RangesListBuilder()
        var iterator: TokensCache.Iterator = tokens.RangesListIterator(rangesToGlue)

        while (iterator.type != null) {
            if (iterator.type == GFMTokenTypes.DOLLAR) {
                val endIterator = findClosing(tokens, iterator)
                if (endIterator != null) {
                    result.withNode(SequentialParser.Node(iterator.index..endIterator.index + 1, GFMElementTypes.INLINE_MATH))
                    iterator = endIterator.advance()
                    continue
                }
            }
            delegateIndices.put(iterator.index)
            iterator = iterator.advance()
        }

        return result.withFurtherProcessing(delegateIndices.get())
    }

    private fun findClosing(tokens: TokensCache, opening: TokensCache.Iterator): TokensCache.Iterator? {
        val sizeOpen = opening.length
        val single = sizeOpen == 1
        // A single `$` opens only when text follows it at once.
        if (single && isWhitespaceOrEnd(tokens, opening.end)) return null

        // Whether the content so far has CJK text, and whether it has a command (`\` + ASCII letter).
        var sawCjk = false
        var sawCommand = false
        var lastWasBackslash = false
        var scanned = opening.end

        var iterator = opening.advance()
        while (iterator.type != null) {
            // Single-dollar math stays on one line.
            if (single && containsLineEnding(tokens, iterator.start, iterator.end)) return null
            if (single) {
                while (scanned < iterator.start) {
                    val char = tokens.getRawCharAt(scanned++)
                    if (isCjk(char)) sawCjk = true
                    if (lastWasBackslash && isAsciiLetter(char)) sawCommand = true
                    lastWasBackslash = char == '\\'
                }
            }
            if (iterator.type == GFMTokenTypes.DOLLAR && iterator.length == sizeOpen) {
                // A single closing `$` must hug the math and not start a number, and
                // CJK prose without a command between the dollars is not math.
                if (!single ||
                    (!isWhitespaceOrEnd(tokens, iterator.start - 1) &&
                        !isAsciiDigit(tokens.getRawCharAt(iterator.end)) &&
                        (!sawCjk || sawCommand))
                ) {
                    return iterator
                }
            }
            if (single) {
                while (scanned < iterator.end) {
                    val char = tokens.getRawCharAt(scanned++)
                    if (isCjk(char)) sawCjk = true
                    if (lastWasBackslash && isAsciiLetter(char)) sawCommand = true
                    lastWasBackslash = char == '\\'
                }
            }
            iterator = iterator.advance()
        }
        return null
    }

    /** Space, tab or line ending at [index], or no character there (start or end of the text). */
    private fun isWhitespaceOrEnd(tokens: TokensCache, index: Int): Boolean {
        if (index < tokens.originalTextRange.first || index > tokens.originalTextRange.last) return true
        return when (tokens.originalText[index]) {
            ' ', '\t', '\n', '\r' -> true
            else -> false
        }
    }

    private fun isAsciiDigit(char: Char): Boolean = char in '0'..'9'

    private fun isAsciiLetter(char: Char): Boolean = char in 'A'..'Z' || char in 'a'..'z'

    /**
     * CJK ideographs, kana, Hangul and their punctuation, plus full-width forms (`，`, `（`),
     * matching `isCjk` in web `packages/shared/src/markdownMath.ts` (botiverse/slock #16343).
     */
    private fun isCjk(char: Char): Boolean {
        val code = char.code
        return code in 0x2E80..0x9FFF ||
            code in 0xAC00..0xD7AF ||
            code in 0xF900..0xFAFF ||
            code in 0xFE30..0xFE4F ||
            code in 0xFF00..0xFFEF ||
            // UTF-16 high surrogates of U+20000–U+3134F (the CJK extension planes).
            code in 0xD840..0xD884
    }

    private fun containsLineEnding(tokens: TokensCache, start: Int, end: Int): Boolean {
        for (index in start until end) {
            val char = tokens.getRawCharAt(index)
            if (char == '\n' || char == '\r') return true
        }
        return false
    }
}
