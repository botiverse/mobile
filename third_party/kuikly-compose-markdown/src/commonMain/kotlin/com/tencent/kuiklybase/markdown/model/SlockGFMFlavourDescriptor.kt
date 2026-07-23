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
 * Shield URL scheme separators only in balanced backtick ranges in the
 * lexer's same-length shadow buffer. [MarkdownLexer] retains the untouched
 * original source, so AST offsets and rendered text remain exact; the shadow
 * exists only to prevent autolink tokenization from stealing code content.
 */
class SlockGFMFlavourDescriptor : GFMFlavourDescriptor() {
    override fun createInlinesLexer(): MarkdownLexer =
        MarkdownLexer(SlockCodeSpanShieldingLexer())
}

private class SlockCodeSpanShieldingLexer : GeneratedLexer {
    private val delegate = _GFMLexer()

    override fun getTokenStart(): Int = delegate.tokenStart

    override fun getTokenEnd(): Int = delegate.tokenEnd

    override fun getState(): Int = delegate.state

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

        var codeCursor = openingStart + markerLength
        while (codeCursor < closingStart) {
            val colonOffset = urlSchemeColonOffsetAt(codeCursor, closingStart)
            if (colonOffset != null) {
                shadow[codeCursor + colonOffset] = SLOCK_LEXER_URL_SCHEME_SHIELD
                codeCursor += colonOffset + 1
            } else {
                codeCursor++
            }
        }
        cursor = closingStart + markerLength
    }
    return shadow
}

private fun CharSequence.urlSchemeColonOffsetAt(index: Int, limit: Int): Int? =
    SLOCK_LEXER_URL_SCHEMES.firstNotNullOfOrNull { scheme ->
        scheme.indexOf(':').takeIf { colonOffset ->
            index + scheme.length <= limit && regionMatches(index, scheme)
        }
    }

private fun CharSequence.regionMatches(start: Int, value: String): Boolean =
    value.indices.all { offset -> this[start + offset].equals(value[offset], ignoreCase = true) }

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
    while (cursor + length <= end) {
        if ((0 until length).all { offset -> this[cursor + offset] == '`' }) {
            return cursor
        }
        cursor++
    }
    return -1
}

private const val SLOCK_LEXER_URL_SCHEME_SHIELD: Char = '\uFF1A'
private val SLOCK_LEXER_URL_SCHEMES = listOf("http://", "https://")
