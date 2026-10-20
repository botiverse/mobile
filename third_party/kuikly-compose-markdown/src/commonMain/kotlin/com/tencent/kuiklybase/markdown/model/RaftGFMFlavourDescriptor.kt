package com.tencent.kuiklybase.markdown.model

import com.tencent.kuiklybase.markdown.utils.CJK_URL_BOUNDARY_PUNCTUATION
import com.tencent.kuiklybase.markdown.utils.isCjkTextOrWhitespace
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.flavours.gfm.GFMConstraints
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMMarkerProcessor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.flavours.gfm.lexer._GFMLexer
import org.intellij.markdown.flavours.gfm.table.GitHubTableMarkerBlock
import org.intellij.markdown.flavours.gfm.table.GitHubTableMarkerProvider
import org.intellij.markdown.lexer.GeneratedLexer
import org.intellij.markdown.lexer.MarkdownLexer
import org.intellij.markdown.parser.LookaheadText
import org.intellij.markdown.parser.MarkerProcessor
import org.intellij.markdown.parser.MarkerProcessorFactory
import org.intellij.markdown.parser.ProductionHolder
import org.intellij.markdown.parser.constraints.MarkdownConstraints
import org.intellij.markdown.parser.constraints.eatItselfFromString
import org.intellij.markdown.parser.constraints.extendsPrev
import org.intellij.markdown.parser.markerblocks.MarkerBlock
import org.intellij.markdown.parser.markerblocks.MarkerBlockProvider

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
class RaftGFMFlavourDescriptor : GFMFlavourDescriptor() {
    override val markerProcessorFactory: MarkerProcessorFactory =
        object : MarkerProcessorFactory {
            override fun createMarkerProcessor(productionHolder: ProductionHolder): MarkerProcessor<*> =
                GFMMarkerProcessor(
                    productionHolder = productionHolder,
                    constraintsBase = GFMConstraints.BASE,
                    tableMarkerProvider = RaftTableContinuationMarkerProvider(),
                    paragraphLazyContinuationPolicy = ::raftBlockQuoteEndsAtUnmarkedLine,
                )
        }

    override fun createInlinesLexer(): MarkdownLexer =
        MarkdownLexer(RaftMarkdownLexer())
}

/**
 * Raft chat dialect: a block quote ends at the first line without a `>`
 * marker. CommonMark lazy continuation would absorb that line into the
 * quote, so `> quoted\nreply` renders the reply as part of the quotation —
 * web already breaks the quote at the newline, and users typing a quote
 * plus their reply mean two blocks. Consulted only for the lazy case (the
 * next line satisfies a strict prefix of the paragraph's constraints):
 * consecutive `>` lines stay one quote, and list lazy continuation is
 * untouched because the check keys on a dropped `>` constraint alone.
 */
private fun raftBlockQuoteEndsAtUnmarkedLine(
    paragraphConstraints: MarkdownConstraints,
    nextLineConstraints: MarkdownConstraints,
): Boolean {
    for (index in nextLineConstraints.types.size until paragraphConstraints.types.size) {
        if (paragraphConstraints.types[index] == '>') return false
    }
    return true
}

/**
 * Composes Raft's tight-table form with the unmodified stock GFM provider.
 */
private class RaftTableContinuationMarkerProvider : MarkerBlockProvider<MarkerProcessor.StateInfo> {
    private val stock = GitHubTableMarkerProvider()

    override fun createMarkerBlocks(
        pos: LookaheadText.Position,
        productionHolder: ProductionHolder,
        stateInfo: MarkerProcessor.StateInfo,
    ): List<MarkerBlock> {
        val constraints = stateInfo.currentConstraints
        if (stateInfo.nextConstraints == constraints) {
            val headerCells = raftTableContinuationColumns(pos, constraints)
            if (headerCells != null) {
                return listOf(GitHubTableMarkerBlock(pos, constraints, productionHolder, headerCells))
            }
        }
        return stock.createMarkerBlocks(pos, productionHolder, stateInfo)
    }

    override fun interruptsParagraph(
        pos: LookaheadText.Position,
        constraints: MarkdownConstraints,
    ): Boolean =
        raftTableContinuationColumns(pos, constraints) != null ||
            stock.interruptsParagraph(pos, constraints)
}

/**
 * Returns the table column count when the current and next lines form Raft's
 * chat-friendly tight-table continuation, otherwise null.
 *
 * The forked parser owns the provider hook; this flavour owns the dialect
 * policy. Parsing reads the untouched source, so AST offsets remain exact.
 */
private fun raftTableContinuationColumns(
    pos: LookaheadText.Position,
    constraints: MarkdownConstraints,
): Int? {
    val headerCells = tableHeaderCellCount(pos.currentLineFromPosition) ?: return null
    val separator = nextLineWithoutConstraints(pos, constraints) ?: return null
    val separatorCells =
        GitHubTableMarkerProvider.countSecondLineCells(separator.withoutTerminalCarriageReturn())
    return headerCells.takeIf { it == separatorCells }
}

private fun tableHeaderCellCount(line: CharSequence): Int? {
    val header = line.withoutTerminalCarriageReturn()
    if (!header.contains('|')) return null
    val split = GitHubTableMarkerBlock.splitByPipes(header)
    // An escaped pipe is cell content, not a table delimiter. Require at least
    // one delimiter that the upstream splitter actually consumed.
    if (split.size < 2) return null
    val count =
        split
            .mapIndexed { index, cell ->
                (index > 0 && index < split.lastIndex) || cell.isNotBlank()
            }
            .count { it }
    return count.takeIf { it > 0 }
}

private fun nextLineWithoutConstraints(
    pos: LookaheadText.Position,
    constraints: MarkdownConstraints,
): CharSequence? {
    val line = pos.nextLine ?: return null
    val nextConstraints = constraints.applyToNextLine(pos.nextLinePosition())
    if (!nextConstraints.extendsPrev(constraints)) return null
    return nextConstraints.eatItselfFromString(line)
}

private fun CharSequence.withoutTerminalCarriageReturn(): CharSequence =
    if (lastOrNull() == '\r') subSequence(0, length - 1) else this

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
private class RaftMarkdownLexer : GeneratedLexer {
    private val delegate = RaftCodeSpanShieldingLexer()
    private var segments: List<RaftLexerSegment> = emptyList()
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
                add(RaftLexerSegment(delegate.tokenStart, delegate.tokenEnd, type))
            }
        }
        segments = baseSegments
            .withResolvedAutolinkBoundaries(buffer.toString(), start, end)
            .withRaftReferenceTokens(buffer.toString(), start, end)
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

private fun List<RaftLexerSegment>.withResolvedAutolinkBoundaries(
    content: String,
    start: Int,
    end: Int,
): List<RaftLexerSegment> {
    if (isEmpty() || none { it.type == GFMTokenTypes.GFM_AUTOLINK }) return this

    val scanner = RaftRecursiveDescentInlineScanner(content, start, end)
    val validAutolinks = scanner.scan()
    if (validAutolinks.isEmpty()) return this

    val validByStart = validAutolinks.associateBy { it.first }

    return buildList {
        for (segment in this@withResolvedAutolinkBoundaries) {
            if (segment.type != GFMTokenTypes.GFM_AUTOLINK) {
                add(segment)
                continue
            }

            val validRange = validByStart[segment.start]
            if (validRange == null || validRange.second >= segment.endExclusive) {
                add(segment)
                continue
            }

            val realEnd = validRange.second
            add(RaftLexerSegment(segment.start, realEnd, GFMTokenTypes.GFM_AUTOLINK))

            val tailSegments = buildList {
                val tailLexer = _GFMLexer()
                tailLexer.reset(content, realEnd, segment.endExclusive, 0)
                while (true) {
                    val tailType = tailLexer.advance() ?: break
                    add(RaftLexerSegment(tailLexer.tokenStart, tailLexer.tokenEnd, tailType))
                }
            }
            addAll(tailSegments.withResolvedAutolinkBoundaries(content, realEnd, segment.endExclusive))
        }
    }
}

private enum class RaftInlineDelimiterKind(val openDelimiter: String, val closeDelimiter: String) {
    StrongStar("**", "**"),
    StrongUnderscore("__", "__"),
    EmphStar("*", "*"),
    EmphUnderscore("_", "_"),
    Strikethrough("~~", "~~"),
    Bracket("[", "]"),
}

private const val MAX_SCANNER_RECURSION_DEPTH: Int = 32

private class RaftRecursiveDescentInlineScanner(
    private val content: String,
    private val start: Int,
    private val end: Int,
) {
    private var pos = start
    private val autolinks = mutableListOf<Pair<Int, Int>>()

    fun scan(): List<Pair<Int, Int>> {
        parseInlines(openScopes = emptyList(), depth = 0)
        return autolinks
    }

    private fun parseInlines(openScopes: List<RaftInlineDelimiterKind>, depth: Int) {
        while (pos < end) {
            val closer = openScopes.firstOrNull { scope -> isClosingDelimiterAt(pos, scope) }
            if (closer != null) {
                return
            }

            if (content[pos] == '`') {
                val runLen = content.backtickRunLength(pos, end)
                val closePos = content.indexOfBacktickRun(pos + runLen, end, runLen)
                if (closePos >= 0) {
                    pos = closePos + runLen
                } else {
                    pos += runLen
                }
                continue
            }

            if (isOpeningDelimiterAt(pos, RaftInlineDelimiterKind.StrongStar)) {
                if (depth < MAX_SCANNER_RECURSION_DEPTH) {
                    pos += 2
                    parseInlines(listOf(RaftInlineDelimiterKind.StrongStar) + openScopes, depth + 1)
                    if (pos + 2 <= end && content.startsWith("**", pos)) {
                        pos += 2
                    }
                } else {
                    pos += 2
                }
                continue
            }

            if (isOpeningDelimiterAt(pos, RaftInlineDelimiterKind.Strikethrough)) {
                if (depth < MAX_SCANNER_RECURSION_DEPTH) {
                    pos += 2
                    parseInlines(listOf(RaftInlineDelimiterKind.Strikethrough) + openScopes, depth + 1)
                    if (pos + 2 <= end && content.startsWith("~~", pos)) {
                        pos += 2
                    }
                } else {
                    pos += 2
                }
                continue
            }

            if (isOpeningDelimiterAt(pos, RaftInlineDelimiterKind.StrongUnderscore)) {
                if (depth < MAX_SCANNER_RECURSION_DEPTH) {
                    pos += 2
                    parseInlines(listOf(RaftInlineDelimiterKind.StrongUnderscore) + openScopes, depth + 1)
                    if (pos + 2 <= end && content.startsWith("__", pos)) {
                        pos += 2
                    }
                } else {
                    pos += 2
                }
                continue
            }

            if (isOpeningDelimiterAt(pos, RaftInlineDelimiterKind.EmphStar)) {
                if (depth < MAX_SCANNER_RECURSION_DEPTH) {
                    pos += 1
                    parseInlines(listOf(RaftInlineDelimiterKind.EmphStar) + openScopes, depth + 1)
                    if (pos < end && content[pos] == '*' && !content.startsWith("**", pos)) {
                        pos += 1
                    }
                } else {
                    pos += 1
                }
                continue
            }

            if (isOpeningDelimiterAt(pos, RaftInlineDelimiterKind.EmphUnderscore)) {
                if (depth < MAX_SCANNER_RECURSION_DEPTH) {
                    pos += 1
                    parseInlines(listOf(RaftInlineDelimiterKind.EmphUnderscore) + openScopes, depth + 1)
                    if (pos < end && content[pos] == '_' && !content.startsWith("__", pos)) {
                        pos += 1
                    }
                } else {
                    pos += 1
                }
                continue
            }

            if (content[pos] == '[') {
                if (depth < MAX_SCANNER_RECURSION_DEPTH) {
                    pos += 1
                    parseInlines(listOf(RaftInlineDelimiterKind.Bracket) + openScopes, depth + 1)
                    if (pos < end && content[pos] == ']') {
                        pos += 1
                    }
                } else {
                    pos += 1
                }
                continue
            }

            val schemeLen = content.matchUrlPrefix(pos, end)
            if (schemeLen > 0) {
                val urlStart = pos
                val urlEnd = scanAutolinkEnd(urlStart + schemeLen, openScopes)
                if (urlEnd > urlStart) {
                    autolinks.add(urlStart to urlEnd)
                }
                pos = urlEnd
                continue
            }

            pos++
        }
    }

    private fun scanAutolinkEnd(startPath: Int, openScopes: List<RaftInlineDelimiterKind>): Int {
        var cursor = startPath
        var roundDepth = 0
        while (cursor < end) {
            val c = content[cursor]
            if (c.isWhitespace() || c == '<' || c == '>' || c in CJK_URL_BOUNDARY_PUNCTUATION) break

            if (c == ',' && cursor + 1 < end && content[cursor + 1].isCjkTextOrWhitespace()) break

            if (c == '(') {
                roundDepth++
                cursor++
                continue
            }
            if (c == ')') {
                if (roundDepth > 0) {
                    roundDepth--
                    cursor++
                    continue
                } else {
                    break
                }
            }

            if (roundDepth == 0) {
                val closer = openScopes.firstOrNull { scope -> isClosingDelimiterAt(cursor, scope) }
                if (closer != null) {
                    break
                }
            }
            cursor++
        }

        while (cursor > startPath && (content[cursor - 1] in ".,:;!?\"'*_~]" || content[cursor - 1] in CJK_URL_BOUNDARY_PUNCTUATION)) {
            cursor--
        }
        return cursor
    }

    private fun isOpeningDelimiterAt(index: Int, kind: RaftInlineDelimiterKind): Boolean {
        if (!content.startsWith(kind.openDelimiter, index)) return false
        val len = kind.openDelimiter.length
        if (len == 1) {
            val char = kind.openDelimiter[0]
            if (index + 1 < end && content[index + 1] == char) return false
            if (index > start && content[index - 1] == char) return false
        }
        val prevChar = if (index > start) content[index - 1] else ' '
        val nextChar = if (index + len < end) content[index + len] else ' '
        // CommonMark intra-word underscore cannot open emphasis
        if ((kind == RaftInlineDelimiterKind.EmphUnderscore || kind == RaftInlineDelimiterKind.StrongUnderscore) &&
            prevChar.isLetterOrDigit()
        ) {
            return false
        }
        return isLeftFlanking(prevChar, nextChar)
    }

    private fun isClosingDelimiterAt(index: Int, kind: RaftInlineDelimiterKind): Boolean {
        if (kind == RaftInlineDelimiterKind.Bracket) {
            return content.startsWith("]", index)
        }
        if (!content.startsWith(kind.closeDelimiter, index)) return false
        val len = kind.closeDelimiter.length
        if (len == 1) {
            val char = kind.closeDelimiter[0]
            if (index + 1 < end && content[index + 1] == char) return false
            if (index > start && content[index - 1] == char) return false
        }
        val prevChar = if (index > start) content[index - 1] else ' '
        val nextChar = if (index + len < end) content[index + len] else ' '
        // CommonMark intra-word underscore cannot close emphasis
        if ((kind == RaftInlineDelimiterKind.EmphUnderscore || kind == RaftInlineDelimiterKind.StrongUnderscore) &&
            nextChar.isLetterOrDigit()
        ) {
            return false
        }
        return isRightFlanking(prevChar, nextChar)
    }

    private fun isLeftFlanking(prevChar: Char, nextChar: Char): Boolean {
        if (nextChar.isWhitespace()) return false
        if (!isPunctuation(nextChar)) return true
        return prevChar.isWhitespace() || isPunctuation(prevChar)
    }

    private fun isRightFlanking(prevChar: Char, nextChar: Char): Boolean {
        if (prevChar.isWhitespace()) return false
        if (!isPunctuation(prevChar)) return true
        return nextChar.isWhitespace() || isPunctuation(nextChar)
    }

    private fun isPunctuation(c: Char): Boolean =
        c in "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~" || c in CJK_URL_BOUNDARY_PUNCTUATION
}

private val AUTOLINK_PREFIXES = listOf("https://", "http://", "ftp://", "file://", "www.")

private fun CharSequence.matchUrlPrefix(pos: Int, end: Int): Int {
    for (prefix in AUTOLINK_PREFIXES) {
        if (pos + prefix.length <= end && startsWith(prefix, pos)) {
            return prefix.length
        }
    }
    return 0
}

private data class RaftLexerSegment(
    val start: Int,
    val endExclusive: Int,
    val type: IElementType,
)

private fun List<RaftLexerSegment>.withRaftReferenceTokens(
    content: String,
    start: Int,
    end: Int,
): List<RaftLexerSegment> {
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
                    add(RaftLexerSegment(cursor, segment.endExclusive, segment.type))
                    break
                }
                if (cursor < candidate.first) {
                    add(RaftLexerSegment(cursor, candidate.first, segment.type))
                }
                if (candidate.first >= segment.start) {
                    val candidateEnd = candidate.last + 1
                    add(RaftLexerSegment(candidate.first, candidateEnd, RaftMarkdownTokenTypes.REFERENCE))
                    emittedCandidateEnd = candidateEnd
                }
                cursor = (candidate.last + 1).coerceAtMost(segment.endExclusive)
                if (candidate.last + 1 <= segment.endExclusive) candidateIndex++
            }
        }
    }
}

private val RaftReferenceSourceTokenTypes: Set<IElementType> =
    setOf(
        MarkdownTokenTypes.TEXT,
        MarkdownTokenTypes.COLON,
        // `_` is legal inside Raft channel/member names. GFM lexes it as an
        // emphasis delimiter, but within a complete ref it belongs to the ref
        // token and must not reach the emphasis parser independently.
        MarkdownTokenTypes.EMPH,
        // `~` separates a DM peer from its kind (`dm:@name~agent:short`). GFM
        // lexes it as a strikethrough delimiter; inside a complete ref it is
        // part of the ref token, never an independent strikethrough run.
        GFMTokenTypes.TILDE,
    )

private class RaftCodeSpanShieldingLexer : GeneratedLexer {
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
        delegate.reset(buffer.raftCodeSpanLexerShadow(start, end), start, end, initialState)
    }
}

private fun CharSequence.raftCodeSpanLexerShadow(start: Int, end: Int): CharSequence {
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
                shadow[codeIndex] = RAFT_LEXER_CODE_PAYLOAD_SHIELD
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

private const val RAFT_LEXER_CODE_PAYLOAD_SHIELD: Char = 'x'
