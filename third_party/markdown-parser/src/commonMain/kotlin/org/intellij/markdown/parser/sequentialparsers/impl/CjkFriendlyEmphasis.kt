package org.intellij.markdown.parser.sequentialparsers.impl

import org.intellij.markdown.html.isPunctuation
import org.intellij.markdown.html.isWhitespace

/**
 * Raft: the CJK-friendly emphasis rule (markdown-cjk-friendly, as remark-cjk-friendly applies it
 * on the web): `**` beside a CJK character opens or closes emphasis where strict CommonMark
 * wouldn't, as in `说**“重点”**。`, whose `**` stands between a Han character and a quote.
 *
 * Mirrors micromark-extension-cjk-friendly 2.0.2 `attention` for `*` and `_`. A character
 * outside the Basic Multilingual Plane (a surrogate: emoji, CJK Extension B and beyond) counts
 * as neither CJK nor punctuation, as CommonMark has it; variation selectors aren't looked through.
 */
internal object CjkFriendlyEmphasis {
    /** Whether a run of [marker] between [before] and [after] can open and close emphasis. */
    fun canOpenClose(marker: Char, before: Char, after: Char): Pair<Boolean, Boolean> {
        val beforeNonCjkPunctuation = isNonCjkPunctuation(before)
        val beforeSpaceOrNonCjkPunctuation =
            beforeNonCjkPunctuation || isWhitespace(before) || before == '*' || before == '_'
        val afterNonCjkPunctuation = isNonCjkPunctuation(after)
        val afterSpaceOrNonCjkPunctuation =
            afterNonCjkPunctuation || isWhitespace(after) || after == '*' || after == '_'
        val open =
            !afterSpaceOrNonCjkPunctuation ||
                afterNonCjkPunctuation && (beforeSpaceOrNonCjkPunctuation || isCjk(before))
        val close =
            !beforeSpaceOrNonCjkPunctuation ||
                beforeNonCjkPunctuation && (afterSpaceOrNonCjkPunctuation || isCjk(after))
        if (marker == '*') return open to close
        return (open && (isSpaceOrPunctuation(before) || !close)) to (close && (isSpaceOrPunctuation(after) || !open))
    }

    private fun isSpaceOrPunctuation(char: Char): Boolean = isWhitespace(char) || isPunctuation(char)

    private fun isNonCjkPunctuation(char: Char): Boolean = isPunctuation(char) && !isCjk(char)

    /** East Asian Wide (not emoji), Fullwidth or Halfwidth, or Hangul, from U+1100 (the spec's CJK character). */
    fun isCjk(char: Char): Boolean {
        val code = char.code
        if (code < 0x1100 || char.isSurrogate()) return false
        var low = 0
        var high = CjkRanges.size / 2 - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            when {
                code < CjkRanges[mid * 2] -> high = mid - 1
                code > CjkRanges[mid * 2 + 1] -> low = mid + 1
                else -> return true
            }
        }
        return false
    }

    /**
     * Inclusive [first, last] pairs of the BMP CJK characters, generated from get-east-asian-width
     * with micromark-extension-cjk-friendly-util's `cjkOrIvs` (variation selectors excluded).
     */
    private val CjkRanges =
        intArrayOf(
        0x1100, 0x11FF,
        0x20A9, 0x20A9,
        0x2329, 0x232A,
        0x2630, 0x2637,
        0x268A, 0x268F,
        0x2E80, 0x2E99,
        0x2E9B, 0x2EF3,
        0x2F00, 0x2FD5,
        0x2FF0, 0x303E,
        0x3041, 0x3096,
        0x3099, 0x30FF,
        0x3105, 0x312F,
        0x3131, 0x318E,
        0x3190, 0x31E5,
        0x31EF, 0x321E,
        0x3220, 0x3247,
        0x3250, 0xA48C,
        0xA490, 0xA4C6,
        0xA960, 0xA97C,
        0xAC00, 0xD7A3,
        0xD7B0, 0xD7C6,
        0xD7CB, 0xD7FB,
        0xF900, 0xFAFF,
        0xFE10, 0xFE19,
        0xFE30, 0xFE52,
        0xFE54, 0xFE66,
        0xFE68, 0xFE6B,
        0xFF01, 0xFFBE,
        0xFFC2, 0xFFC7,
        0xFFCA, 0xFFCF,
        0xFFD2, 0xFFD7,
        0xFFDA, 0xFFDC,
        0xFFE0, 0xFFE6,
        0xFFE8, 0xFFEE,
        )
}
