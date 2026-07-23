package com.tencent.kuiklybase.markdown.model

import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.lexer._GFMLexer
import org.intellij.markdown.lexer.GeneratedLexer
import org.intellij.markdown.lexer.MarkdownLexer

/**
 * GFM with code-span precedence restored at the lexer boundary.
 *
 * JetBrains Markdown's GFM lexer recognizes a CJK-adjacent URL before its
 * sequential backtick parser runs. For a source such as
 * `` `http://internal/`、后续文本 `` it can emit one GFM_AUTOLINK that consumes
 * the closing backtick and following prose, so no CODE_SPAN reaches the AST.
 *
 * Replace code payload characters with neutral same-length text in the
 * lexer's shadow buffer while preserving delimiters and whitespace.
 * [MarkdownLexer] retains the untouched original source, so AST offsets and
 * rendered text remain exact; the shadow exists only to let backtick parsing
 * claim code ranges before URL, email, HTML, or emphasis tokenization.
 */
class SlockGFMFlavourDescriptor : GFMFlavourDescriptor() {
    override fun createInlinesLexer(): MarkdownLexer =
        MarkdownLexer(SlockCodeSpanShieldingLexer())
}

private class SlockCodeSpanShieldingLexer : GeneratedLexer {
    private val delegate = _GFMLexer()

    override val tokenStart: Int
        get() = delegate.tokenStart

    override val tokenEnd: Int
        get() = delegate.tokenEnd

    override val state: Int
        get() = delegate.state

    override fun advance() = delegate.advance()

    override fun reset(
        buffer: CharSequence,
        start: Int,
        end: Int,
        initialState: Int,
    ) {
        delegate.reset(buffer.slockCodeSpanLexerShadow(start, end), start, end, initialState)
    }
}

private fun CharSequence.slockCodeSpanLexerShadow(start: Int, end: Int): CharSequence {
    val shadow = StringBuilder(length).append(this)
    var cursor = start
    while (cursor < end) {
        val openingStart = indexOf('`', cursor, end)
        if (openingStart < 0) break
        val markerLength = backtickRunLength(openingStart, end)
        val closingStart = indexOfBacktickRun(openingStart + markerLength, end, markerLength)
        if (closingStart < 0) {
            cursor = openingStart + markerLength
            continue
        }

        for (codeIndex in openingStart + markerLength until closingStart) {
            val char = this[codeIndex]
            if (char != '`' && !char.isWhitespace()) {
                shadow[codeIndex] = SLOCK_LEXER_CODE_PAYLOAD_SHIELD
            }
        }
        cursor = closingStart + markerLength
    }
    return shadow
}

private fun CharSequence.backtickRunLength(start: Int, end: Int): Int {
    var length = 0
    while (start + length < end && this[start + length] == '`') length++
    return length
}

private fun CharSequence.indexOf(char: Char, start: Int, end: Int): Int {
    for (index in start until end) {
        if (this[index] == char) return index
    }
    return -1
}

private fun CharSequence.indexOfBacktickRun(start: Int, end: Int, length: Int): Int {
    var cursor = start
    while (cursor < end) {
        if (this[cursor] != '`') {
            cursor++
            continue
        }
        val runLength = backtickRunLength(cursor, end)
        if (runLength == length) {
            return cursor
        }
        cursor += runLength
    }
    return -1
}

private const val SLOCK_LEXER_CODE_PAYLOAD_SHIELD: Char = 'x'
