package com.tencent.kuiklybase.markdown.model

import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownTokenTypes
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
        MarkdownLexer(SlockRaftMarkdownLexer())
}

/** Inline token emitted by the single Raft Markdown parse entry. */
object RaftMarkdownTokenTypes {
    val REFERENCE: IElementType = IElementType("RAFT_REFERENCE")
}

/**
 * Adds Raft reference tokens at the flavour lexer boundary.
 *
 * The base GFM lexer still decides Markdown punctuation and construct
 * boundaries. We only replace candidates composed entirely of ordinary text
 * and colon tokens. URL, email, HTML, and code lexing therefore stay intact,
 * while the resulting AST exposes refs as first-class source nodes. Markdown
 * ancestry remains authoritative: consumers ignore tokens below links,
 * images, code, autolinks, or HTML.
 */
private class SlockRaftMarkdownLexer : GeneratedLexer {
    private val delegate = SlockCodeSpanShieldingLexer()
    private var segments: List<SlockLexerSegment> = emptyList()
    private var segmentIndex = 0
    private var currentStart = 0
    private var currentEnd = 0

    override val tokenStart: Int
        get() = currentStart

    override val tokenEnd: Int
        get() = currentEnd

    override val state: Int
        get() = delegate.state

    override fun reset(
        buffer: CharSequence,
        start: Int,
        end: Int,
        initialState: Int,
    ) {
        delegate.reset(buffer, start, end, initialState)
        val baseSegments = buildList {
            while (true) {
                val type = delegate.advance() ?: break
                add(SlockLexerSegment(delegate.tokenStart, delegate.tokenEnd, type))
            }
        }
        segments = baseSegments.withRaftReferenceTokens(buffer.toString(), start, end)
        segmentIndex = 0
        currentStart = start
        currentEnd = start
    }

    override fun advance(): IElementType? {
        val segment = segments.getOrNull(segmentIndex++) ?: return null
        currentStart = segment.start
        currentEnd = segment.endExclusive
        return segment.type
    }
}

private data class SlockLexerSegment(
    val start: Int,
    val endExclusive: Int,
    val type: IElementType,
)

private fun List<SlockLexerSegment>.withRaftReferenceTokens(
    content: String,
    start: Int,
    end: Int,
): List<SlockLexerSegment> {
    if (isEmpty() || (!content.contains('#') && !content.contains('@'))) return this
    val candidates = content.raftMarkdownSyntaxCandidateRanges(start, end)
    if (candidates.isEmpty()) return this

    val eligible = candidates.filter { candidate ->
        val overlapping = filter { segment ->
            segment.start < candidate.last + 1 && candidate.first < segment.endExclusive
        }
        overlapping.isNotEmpty() && overlapping.all { it.type in RaftReferenceSourceTokenTypes }
    }
    if (eligible.isEmpty()) return this

    return buildList {
        var candidateIndex = 0
        var emittedCandidateEnd = -1
        for (segment in this@withRaftReferenceTokens) {
            var cursor = segment.start.coerceAtLeast(emittedCandidateEnd)
            while (candidateIndex < eligible.size && eligible[candidateIndex].last < cursor) candidateIndex++
            while (cursor < segment.endExclusive) {
                val candidate = eligible.getOrNull(candidateIndex)
                if (candidate == null || candidate.first >= segment.endExclusive) {
                    add(SlockLexerSegment(cursor, segment.endExclusive, segment.type))
                    break
                }
                if (cursor < candidate.first) {
                    add(SlockLexerSegment(cursor, candidate.first, segment.type))
                }
                if (candidate.first >= segment.start) {
                    val candidateEnd = candidate.last + 1
                    add(SlockLexerSegment(candidate.first, candidateEnd, RaftMarkdownTokenTypes.REFERENCE))
                    emittedCandidateEnd = candidateEnd
                }
                cursor = (candidate.last + 1).coerceAtMost(segment.endExclusive)
                if (candidate.last + 1 <= segment.endExclusive) candidateIndex++
            }
        }
    }
}

private val RaftReferenceSourceTokenTypes: Set<IElementType> =
    setOf(MarkdownTokenTypes.TEXT, MarkdownTokenTypes.COLON)

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
