package com.tencent.kuiklybase.markdown.model

import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.flavours.commonmark.CommonMarkMarkerProcessor
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMConstraints
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
import org.intellij.markdown.parser.constraints.CommonMarkdownConstraints
import org.intellij.markdown.parser.constraints.MarkdownConstraints
import org.intellij.markdown.parser.constraints.eatItselfFromString
import org.intellij.markdown.parser.constraints.extendsPrev
import org.intellij.markdown.parser.constraints.getCharsEaten
import org.intellij.markdown.parser.markerblocks.MarkerBlock
import org.intellij.markdown.parser.markerblocks.MarkerBlockProvider
import org.intellij.markdown.parser.sequentialparsers.SequentialParser
import kotlin.math.min

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
    override val markerProcessorFactory: MarkerProcessorFactory = SlockGFMMarkerProcessor.Factory

    override fun createInlinesLexer(): MarkdownLexer =
        MarkdownLexer(SlockRaftMarkdownLexer())
}

/**
 * GFM block parsing with Raft's chat-friendly table continuation rule.
 *
 * Upstream GFM recognizes a table only when its header starts a fresh block.
 * Chat messages commonly put the header directly after prose, so the table
 * provider is allowed to interrupt a paragraph when the current and next
 * lines form a valid GFM header/separator pair. The parser still reads the
 * untouched source, which keeps every AST offset bound to the original body.
 */
private class SlockGFMMarkerProcessor(
    productionHolder: ProductionHolder,
    constraintsBase: CommonMarkdownConstraints,
) : CommonMarkMarkerProcessor(productionHolder, constraintsBase) {
    private val markerBlockProviders =
        slockGfmLeadingMarkerProviders() +
            super.getMarkerBlockProviders() +
            SlockTableContinuationMarkerProvider()

    override fun getMarkerBlockProviders(): List<MarkerBlockProvider<StateInfo>> =
        markerBlockProviders

    // Preserve the pinned JetBrains Markdown 0.7.5 GFM checkbox tokenization
    // while replacing its final table provider with the paragraph-interrupting
    // Raft variant. Re-check this parity when upgrading the parser dependency.
    override fun populateConstraintsTokens(
        pos: LookaheadText.Position,
        constraints: MarkdownConstraints,
        productionHolder: ProductionHolder,
    ) {
        if (constraints !is GFMConstraints || !constraints.hasCheckbox()) {
            super.populateConstraintsTokens(pos, constraints, productionHolder)
            return
        }

        val line = pos.currentLine
        var offset = pos.offsetInCurrentLine
        while (offset < line.length && line[offset] != '[') offset++
        if (offset == line.length) {
            super.populateConstraintsTokens(pos, constraints, productionHolder)
            return
        }

        val type =
            when (constraints.types.lastOrNull()) {
                '>' -> MarkdownTokenTypes.BLOCK_QUOTE
                '.', ')' -> MarkdownTokenTypes.LIST_NUMBER
                else -> MarkdownTokenTypes.LIST_BULLET
            }
        val middleOffset = pos.offset - pos.offsetInCurrentLine + offset
        val endOffset =
            min(
                pos.offset - pos.offsetInCurrentLine + constraints.getCharsEaten(pos.currentLine),
                pos.nextLineOrEofOffset,
            )
        productionHolder.addProduction(
            listOf(
                SequentialParser.Node(pos.offset..middleOffset, type),
                SequentialParser.Node(middleOffset..endOffset, GFMTokenTypes.CHECK_BOX),
            )
        )
    }

    object Factory : MarkerProcessorFactory {
        override fun createMarkerProcessor(productionHolder: ProductionHolder): MarkerProcessor<*> =
            SlockGFMMarkerProcessor(productionHolder, GFMConstraints.BASE)
    }
}

/**
 * Preserves the stock leading GFM providers available on each parser build.
 * Android/iOS use JetBrains Markdown 0.7.5 (GitHub alerts); the OHOS toolchain
 * currently links 0.7.4-ohos, which predates that provider.
 */
internal expect fun slockGfmLeadingMarkerProviders(): List<MarkerBlockProvider<MarkerProcessor.StateInfo>>

private class SlockTableContinuationMarkerProvider : MarkerBlockProvider<MarkerProcessor.StateInfo> {
    override fun createMarkerBlocks(
        pos: LookaheadText.Position,
        productionHolder: ProductionHolder,
        stateInfo: MarkerProcessor.StateInfo,
    ): List<MarkerBlock> {
        val constraints = stateInfo.currentConstraints
        if (stateInfo.nextConstraints != constraints) return emptyList()
        val headerCells = tableColumnCount(pos, constraints) ?: return emptyList()
        return listOf(GitHubTableMarkerBlock(pos, constraints, productionHolder, headerCells))
    }

    override fun interruptsParagraph(
        pos: LookaheadText.Position,
        constraints: MarkdownConstraints,
    ): Boolean = tableColumnCount(pos, constraints) != null

    private fun tableColumnCount(
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
        // An escaped pipe is cell content, not a table delimiter. Require at
        // least one delimiter that the upstream splitter actually consumed.
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
    setOf(
        MarkdownTokenTypes.TEXT,
        MarkdownTokenTypes.COLON,
        // `_` is legal inside Raft channel/member names. GFM lexes it as an
        // emphasis delimiter, but within a complete ref it belongs to the ref
        // token and must not reach the emphasis parser independently.
        MarkdownTokenTypes.EMPH,
    )

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
