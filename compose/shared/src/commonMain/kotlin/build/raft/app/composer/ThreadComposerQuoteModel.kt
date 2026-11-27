package build.raft.app

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import build.raft.designsystem.components.toRaftTextFieldValue
import build.raft.app.data.InlineQuoteComposeSegment
import build.raft.app.data.MAX_INLINE_QUOTES_PER_MESSAGE
import build.raft.app.data.MessageQuoteSegment
import build.raft.app.data.buildWholeMessageQuoteExcerpt
import build.raft.app.data.hasSameConversationQuoteRef
import build.raft.app.data.normalizeQuoteExcerptKey
import build.raft.app.data.parseMessageQuoteSegments
import build.raft.app.data.serializeInlineQuoteBlocks
import build.raft.app.model.ResolvedInlineQuote
import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.key
import build.raft.app.thread.quoteReplyAuthorType
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.Serializable

/*
 * Multi-quote interleaved composer model (botiverse/slock #16027 / #16059 / #16210):
 * port of `packages/web/src/features/composer/composerQuoteModel.ts`, `composerQuoteStore.ts`, and
 * `useComposerQuoteController.ts`.
 *
 * The persisted draft (`seedText`) stays the canonical Markdown wire format (`> <excerpt>\n> msg=<shortId>`
 * separated by `\n\n`), isomorphic with web and Agent CLI. Structured composer blocks hydrate from
 * that draft and serialize back on every edit.
 */

@Serializable
internal data class PendingComposerQuote(
    val messageId: String,
    val authorName: String,
    val authorType: String = "user",
    val excerpt: String,
    val isPartial: Boolean = true,
    val createdAt: String = "",
)

internal data class CachedComposerQuoteMeta(
    val messageId: String,
    val authorName: String,
    val authorType: String,
    val createdAt: String,
    val isPartial: Boolean? = null,
)

/**
 * Request dispatched from message actions ("Quote", Select Text > Quote, in-place selection > Quote)
 * into the conversation composer. When [quote] is present, inserts a structured quote chip;
 * otherwise appends [fallbackMarkdown] as a plain Markdown blockquote.
 */
internal data class ThreadComposerQuoteInsert(
    val quote: PendingComposerQuote?,
    val fallbackMarkdown: String,
)

/** Monotonic request handled by the mounted composer's multi-quote controller. */
internal data class ThreadComposerInsertQuoteRequest(
    val nonce: Int,
    val quote: PendingComposerQuote,
)

/** User feedback from attempting to add a quote into the composer. */
internal sealed interface ThreadComposerQuoteFeedback {
    data object Duplicate : ThreadComposerQuoteFeedback

    data class LimitReached(val max: Int) : ThreadComposerQuoteFeedback
}

internal sealed interface ComposerBlock {
    val id: String

    data class Text(
        override val id: String,
        val text: String,
        /** True when the user explicitly opened an empty text gap above/between quotes. */
        val explicitGap: Boolean = false,
    ) : ComposerBlock

    data class Quote(
        override val id: String,
        val quote: PendingComposerQuote,
    ) : ComposerBlock
}

internal data class ComposerBlockCursor(
    val blockId: String? = null,
    val offset: Int? = null,
)

internal sealed interface InsertQuoteResult {
    val blocks: List<ComposerBlock>

    data class Inserted(
        override val blocks: List<ComposerBlock>,
        val quoteBlockId: String,
        val focusBlockId: String,
        val focusOffset: Int,
    ) : InsertQuoteResult

    data class Duplicate(
        override val blocks: List<ComposerBlock>,
        val duplicateBlockId: String,
    ) : InsertQuoteResult

    data class Limit(
        override val blocks: List<ComposerBlock>,
    ) : InsertQuoteResult
}

internal data class ComposerBlockFocusMutation(
    val blocks: List<ComposerBlock>,
    val focusBlockId: String,
    val focusOffset: Int = 0,
)

private val composerBlockSeq = atomic(1L)

internal fun createComposerBlockId(prefix: String): String =
    "$prefix-${composerBlockSeq.incrementAndGet()}"

internal fun countComposerQuotes(blocks: List<ComposerBlock>): Int =
    blocks.count { it is ComposerBlock.Quote }

internal fun hasComposerReplyText(blocks: List<ComposerBlock>): Boolean =
    blocks.any { it is ComposerBlock.Text && it.text.trim().isNotEmpty() }

internal fun extractComposerReplyText(blocks: List<ComposerBlock>): String =
    blocks.mapNotNull { (it as? ComposerBlock.Text)?.text?.trim()?.takeIf(String::isNotEmpty) }
        .joinToString("\n\n")

/**
 * Enforce the 3 Scheme B structural invariants (web `normalizeComposerBlocks`):
 *  1. Merge adjacent `Text` blocks losslessly (joining non-empty texts with `\n`).
 *  2. Drop unrequested empty `Text` blocks directly before a `Quote` block so back-to-back quotes
 *     stack cleanly (keeping `explicitGap = true` gaps).
 *  3. Always end with a trailing `Text` block as a focusable default reply slot.
 */
internal fun normalizeComposerBlocks(input: List<ComposerBlock>): List<ComposerBlock> {
    val merged = mutableListOf<ComposerBlock>()
    for (block in input) {
        if (block is ComposerBlock.Quote) {
            merged += block
            continue
        }
        val textBlock = block as ComposerBlock.Text
        val cleaned =
            if (textBlock.text.isNotEmpty()) {
                ComposerBlock.Text(id = textBlock.id, text = textBlock.text, explicitGap = false)
            } else {
                ComposerBlock.Text(id = textBlock.id, text = "", explicitGap = textBlock.explicitGap)
            }
        val prev = merged.lastOrNull()
        if (prev is ComposerBlock.Text) {
            val joined =
                if (prev.text.isNotEmpty() && cleaned.text.isNotEmpty()) {
                    "${prev.text}\n${cleaned.text}"
                } else {
                    "${prev.text}${cleaned.text}"
                }
            val keepGap = joined.isEmpty() && (prev.explicitGap || cleaned.explicitGap)
            merged[merged.lastIndex] =
                ComposerBlock.Text(id = prev.id, text = joined, explicitGap = keepGap)
        } else {
            merged += cleaned
        }
    }

    val pruned = mutableListOf<ComposerBlock>()
    for (i in merged.indices) {
        val block = merged[i]
        val next = merged.getOrNull(i + 1)
        if (
            block is ComposerBlock.Text &&
            block.text.isEmpty() &&
            !block.explicitGap &&
            next is ComposerBlock.Quote
        ) {
            continue
        }
        pruned += block
    }

    if (pruned.isEmpty() || pruned.last() !is ComposerBlock.Text) {
        pruned += ComposerBlock.Text(id = createComposerBlockId("t"), text = "")
    }
    return pruned
}

internal fun createTextOnlyBlocks(text: String): List<ComposerBlock> =
    listOf(ComposerBlock.Text(id = createComposerBlockId("t"), text = text))

private val TrailingHorizontalSpaces = Regex("[ \t]+$")
private val LeadingNewlines = Regex("^\n+")

/**
 * web `insertQuoteAtCursor`: insert a quote block at the active text cursor ("quote-first,
 * reply-below"):
 * - Never dedupes by `messageId` alone; only guards exact `(shortId, normalizedExcerpt)`.
 * - Enforces [MAX_INLINE_QUOTES_PER_MESSAGE] (10).
 * - Splits the target text block at `cursor.offset`, places the new quote chip there, and returns
 *   focus coordinates for the text block directly below the quote chip.
 */
internal fun insertQuoteAtCursor(
    blocks: List<ComposerBlock>,
    quote: PendingComposerQuote,
    cursor: ComposerBlockCursor? = null,
): InsertQuoteResult {
    val normKey = normalizeQuoteExcerptKey(quote.excerpt)
    val targetShortId = quote.messageId.take(8).lowercase()
    val existingDup =
        blocks.firstOrNull { block ->
            block is ComposerBlock.Quote &&
                block.quote.messageId.take(8).lowercase() == targetShortId &&
                normalizeQuoteExcerptKey(block.quote.excerpt) == normKey
        } as? ComposerBlock.Quote
    if (existingDup != null) {
        return InsertQuoteResult.Duplicate(blocks = blocks.toList(), duplicateBlockId = existingDup.id)
    }

    if (countComposerQuotes(blocks) >= MAX_INLINE_QUOTES_PER_MESSAGE) {
        return InsertQuoteResult.Limit(blocks = blocks.toList())
    }

    val normalized = normalizeComposerBlocks(blocks)
    var targetIdx =
        cursor?.blockId?.let { id ->
            normalized.indexOfFirst { it is ComposerBlock.Text && it.id == id }
        } ?: -1
    if (targetIdx < 0) {
        targetIdx = normalized.indexOfLast { it is ComposerBlock.Text }
    }
    val target = normalized[targetIdx] as ComposerBlock.Text
    val rawOffset = cursor?.offset ?: target.text.length
    val offset = rawOffset.coerceIn(0, target.text.length)
    val before = target.text.substring(0, offset).replace(TrailingHorizontalSpaces, "")
    val after = target.text.substring(offset).replace(LeadingNewlines, "")

    val quoteBlock = ComposerBlock.Quote(id = createComposerBlockId("q"), quote = quote)
    val keepAfterExplicitGap = after.isEmpty() && normalized.getOrNull(targetIdx + 1) is ComposerBlock.Quote
    val afterBlock =
        ComposerBlock.Text(
            id = createComposerBlockId("t"),
            text = after,
            explicitGap = keepAfterExplicitGap,
        )
    val replacement =
        if (before.isNotEmpty()) {
            listOf(ComposerBlock.Text(id = target.id, text = before), quoteBlock, afterBlock)
        } else {
            listOf(quoteBlock, afterBlock)
        }

    val next =
        normalizeComposerBlocks(
            normalized.subList(0, targetIdx) +
                replacement +
                normalized.subList(targetIdx + 1, normalized.size)
        )

    val qIdx = next.indexOfFirst { it.id == quoteBlock.id }
    val afterInserted = next.getOrNull(qIdx + 1)
    val focusBlockId =
        if (afterInserted is ComposerBlock.Text) {
            afterInserted.id
        } else {
            next.last().id
        }

    return InsertQuoteResult.Inserted(
        blocks = next,
        quoteBlockId = quoteBlock.id,
        focusBlockId = focusBlockId,
        focusOffset = 0,
    )
}

/**
 * web `insertTextGapAfterQuote`: open an explicit text block directly below [quoteBlockId] so the
 * user can interleave a reply between two stacked quotes. If a text block already follows the
 * quote, focuses that existing block instead of creating a duplicate.
 */
internal fun insertTextGapAfterQuote(
    blocks: List<ComposerBlock>,
    quoteBlockId: String,
): ComposerBlockFocusMutation {
    val normalized = normalizeComposerBlocks(blocks)
    val idx = normalized.indexOfFirst { it is ComposerBlock.Quote && it.id == quoteBlockId }
    if (idx < 0) {
        return ComposerBlockFocusMutation(blocks = normalized, focusBlockId = normalized.last().id)
    }
    val next = normalized.getOrNull(idx + 1)
    if (next is ComposerBlock.Text) {
        return ComposerBlockFocusMutation(blocks = normalized, focusBlockId = next.id)
    }
    val gap = ComposerBlock.Text(id = createComposerBlockId("t"), text = "", explicitGap = true)
    val inserted =
        normalizeComposerBlocks(
            normalized.subList(0, idx + 1) +
                gap +
                normalized.subList(idx + 1, normalized.size)
        )
    return ComposerBlockFocusMutation(blocks = inserted, focusBlockId = gap.id)
}

/**
 * web `removeEmptyTextBlock`: remove an empty non-trailing text block (intro or interleaved gap)
 * without touching adjacent quote chips, moving focus to the nearest preceding text block (at end)
 * or else the nearest following text block (at offset 0).
 */
internal fun removeEmptyTextBlock(
    blocks: List<ComposerBlock>,
    textBlockId: String,
): ComposerBlockFocusMutation {
    val idx = blocks.indexOfFirst { it is ComposerBlock.Text && it.id == textBlockId }
    if (idx < 0 || idx == blocks.lastIndex) {
        val normalized = normalizeComposerBlocks(blocks)
        val fallback = (normalized.getOrNull(idx) as? ComposerBlock.Text) ?: normalized.last()
        return ComposerBlockFocusMutation(
            blocks = normalized,
            focusBlockId = fallback.id,
            focusOffset = (fallback as? ComposerBlock.Text)?.text?.length ?: 0,
        )
    }
    val target = blocks[idx] as? ComposerBlock.Text
    if (target == null || target.text.isNotEmpty()) {
        return ComposerBlockFocusMutation(blocks = blocks.toList(), focusBlockId = textBlockId, focusOffset = 0)
    }

    val remaining = blocks.subList(0, idx) + blocks.subList(idx + 1, blocks.size)
    val normalized = normalizeComposerBlocks(remaining)

    for (i in minOf(idx - 1, normalized.lastIndex) downTo 0) {
        val cand = normalized[i]
        if (cand is ComposerBlock.Text) {
            return ComposerBlockFocusMutation(
                blocks = normalized,
                focusBlockId = cand.id,
                focusOffset = cand.text.length,
            )
        }
    }
    for (i in maxOf(0, idx) until normalized.size) {
        val cand = normalized[i]
        if (cand is ComposerBlock.Text) {
            return ComposerBlockFocusMutation(
                blocks = normalized,
                focusBlockId = cand.id,
                focusOffset = 0,
            )
        }
    }
    val last = normalized.last()
    return ComposerBlockFocusMutation(
        blocks = normalized,
        focusBlockId = last.id,
        focusOffset = (last as? ComposerBlock.Text)?.text?.length ?: 0,
    )
}

/**
 * web `insertTextGapBeforeIndex`: open an explicit text block directly before `blocks[quoteIndex]`
 * (used when pressing ArrowUp above the first quote to add an intro line seamlessly).
 */
internal fun insertTextGapBeforeIndex(
    blocks: List<ComposerBlock>,
    quoteIndex: Int,
): ComposerBlockFocusMutation {
    val normalized = normalizeComposerBlocks(blocks)
    if (quoteIndex !in normalized.indices) {
        return ComposerBlockFocusMutation(blocks = normalized, focusBlockId = normalized.last().id)
    }
    val prev = normalized.getOrNull(quoteIndex - 1)
    if (prev is ComposerBlock.Text) {
        return ComposerBlockFocusMutation(blocks = normalized, focusBlockId = prev.id)
    }
    val gap = ComposerBlock.Text(id = createComposerBlockId("t"), text = "", explicitGap = true)
    val next =
        normalizeComposerBlocks(
            normalized.subList(0, quoteIndex) +
                gap +
                normalized.subList(quoteIndex, normalized.size)
        )
    return ComposerBlockFocusMutation(blocks = next, focusBlockId = gap.id)
}

/** web `updateTextBlock`: update the text of one text block; clears `explicitGap` once non-empty. */
internal fun updateTextBlock(
    blocks: List<ComposerBlock>,
    blockId: String,
    text: String,
): List<ComposerBlock> {
    var changed = false
    val next =
        blocks.map { block ->
            if (block !is ComposerBlock.Text || block.id != blockId) {
                block
            } else {
                changed = true
                if (text.isNotEmpty()) {
                    ComposerBlock.Text(id = block.id, text = text, explicitGap = false)
                } else {
                    ComposerBlock.Text(id = block.id, text = "", explicitGap = block.explicitGap)
                }
            }
        }
    return if (changed) next else blocks.toList()
}

/**
 * web `collapseEmptyExplicitGap`: drop an empty non-trailing text block before a quote on blur so
 * untouched or emptied gaps collapse cleanly back into stacked quote chips.
 */
internal fun collapseEmptyExplicitGap(
    blocks: List<ComposerBlock>,
    blockId: String,
): List<ComposerBlock> {
    val idx = blocks.indexOfFirst { it is ComposerBlock.Text && it.id == blockId }
    if (idx < 0 || idx == blocks.lastIndex) return blocks.toList()
    val target = blocks[idx] as? ComposerBlock.Text ?: return blocks.toList()
    if (target.text.isNotEmpty()) return blocks.toList()
    return normalizeComposerBlocks(
        blocks.map { block ->
            if (block.id == blockId) ComposerBlock.Text(id = block.id, text = "", explicitGap = false) else block
        }
    )
}

/**
 * web `removeQuoteBlock`: remove a quote block and losslessly merge surrounding text blocks,
 * returning the focus block and caret offset right at the merge seam.
 */
internal fun removeQuoteBlock(
    blocks: List<ComposerBlock>,
    quoteBlockId: String,
): ComposerBlockFocusMutation {
    val idx = blocks.indexOfFirst { it is ComposerBlock.Quote && it.id == quoteBlockId }
    if (idx < 0) {
        val last = blocks.lastOrNull()
        return ComposerBlockFocusMutation(
            blocks = blocks.toList(),
            focusBlockId = last?.id.orEmpty(),
            focusOffset = (last as? ComposerBlock.Text)?.text?.length ?: 0,
        )
    }
    val prev = blocks.getOrNull(idx - 1)
    val next = blocks.getOrNull(idx + 1)
    val seamOffset = (prev as? ComposerBlock.Text)?.text?.length ?: 0
    val preferredFocusId =
        when {
            prev is ComposerBlock.Text -> prev.id
            next is ComposerBlock.Text -> next.id
            else -> null
        }

    val remaining = blocks.subList(0, idx) + blocks.subList(idx + 1, blocks.size)
    val normalized = normalizeComposerBlocks(remaining)
    val focusBlock =
        preferredFocusId?.let { id -> normalized.firstOrNull { it.id == id } }
            ?: normalized.firstOrNull { it is ComposerBlock.Text }
            ?: normalized.last()
    val maxOffset = (focusBlock as? ComposerBlock.Text)?.text?.length ?: 0

    return ComposerBlockFocusMutation(
        blocks = normalized,
        focusBlockId = focusBlock.id,
        focusOffset = minOf(seamOffset, maxOffset),
    )
}

/** web `serializeComposerBlocks`: serialize blocks to the unified Markdown wire/draft format. */
internal fun serializeComposerBlocks(blocks: List<ComposerBlock>): String =
    serializeInlineQuoteBlocks(
        blocks.map { block ->
            when (block) {
                is ComposerBlock.Quote ->
                    InlineQuoteComposeSegment.Quote(
                        excerpt = block.quote.excerpt,
                        targetMessageId = block.quote.messageId,
                    )
                is ComposerBlock.Text ->
                    InlineQuoteComposeSegment.Text(text = block.text)
            }
        }
    )

/**
 * web `parseDraftToComposerBlocks`: hydrate a serialized Markdown draft back into structured
 * blocks. Fast-paths straight to a single text block when there are no `> msg=<id>` refs.
 */
internal fun parseDraftToComposerBlocks(
    draft: String,
    resolveQuoteMeta: (refId: String, excerpt: String) -> PendingComposerQuote,
): List<ComposerBlock> {
    if (draft.isEmpty() || !hasSameConversationQuoteRef(draft)) {
        return createTextOnlyBlocks(draft)
    }
    val segments = parseMessageQuoteSegments(draft)
    val blocks =
        segments.map { seg ->
            when (seg) {
                is MessageQuoteSegment.QuoteBlock ->
                    ComposerBlock.Quote(
                        id = createComposerBlockId("q"),
                        quote = resolveQuoteMeta(seg.refId, seg.excerpt),
                    )
                is MessageQuoteSegment.Text ->
                    ComposerBlock.Text(
                        id = createComposerBlockId("t"),
                        text = seg.text,
                    )
            }
        }
    return normalizeComposerBlocks(blocks)
}

/**
 * web `buildOptimisticResolvedQuotesFromBlocks`: build optimistic `resolvedQuotes` for EVERY quote
 * block (`"0"` .. `"N-1"`, capped at [MAX_INLINE_QUOTES_PER_MESSAGE]) so the sender sees zero
 * flicker while the server verifies and echoes the message.
 */
internal fun buildOptimisticResolvedQuotesFromBlocks(
    blocks: List<ComposerBlock>,
): Map<String, ResolvedInlineQuote>? {
    val quotes = blocks.filterIsInstance<ComposerBlock.Quote>()
    if (quotes.isEmpty()) return null
    val limit = minOf(quotes.size, MAX_INLINE_QUOTES_PER_MESSAGE)
    return buildMap(limit) {
        for (i in 0 until limit) {
            val q = quotes[i].quote
            put(
                i.toString(),
                ResolvedInlineQuote(
                    messageId = q.messageId,
                    shortId = q.messageId.take(8).lowercase(),
                    authorName = q.authorName,
                    authorType = q.authorType,
                    createdAt = q.createdAt,
                    isPartial = q.isPartial,
                )
            )
        }
    }
}

/**
 * Thread-safe process-local cache of composer quote metadata keyed by lowercased 8-char `shortId`
 * (mirror of web `useComposerQuoteStore.metaByShortId`), so persisted drafts hydrate with full
 * UUID, author name/kind, timestamp, and `isPartial`.
 */
internal object ThreadComposerQuoteMetaCache {
    private val lock = SynchronizedObject()
    private val byShortId = linkedMapOf<String, CachedComposerQuoteMeta>()
    private const val MaxEntries = 250

    fun remember(quote: PendingComposerQuote) {
        val shortId = quote.messageId.trim().take(8).lowercase()
        if (shortId.isEmpty()) return
        synchronized(lock) {
            byShortId.remove(shortId)
            byShortId[shortId] =
                CachedComposerQuoteMeta(
                    messageId = quote.messageId,
                    authorName = quote.authorName,
                    authorType = quote.authorType,
                    createdAt = quote.createdAt,
                    isPartial = quote.isPartial,
                )
            while (byShortId.size > MaxEntries) {
                val oldest = byShortId.keys.firstOrNull() ?: break
                byShortId.remove(oldest)
            }
        }
    }

    fun lookup(shortId: String): CachedComposerQuoteMeta? {
        val key = shortId.trim().take(8).lowercase()
        if (key.isEmpty()) return null
        return synchronized(lock) { byShortId[key] }
    }

    fun clear() {
        synchronized(lock) { byShortId.clear() }
    }
}

/**
 * Hydrate a `> msg=<refId>` quote block from a persisted draft (web `resolveQuoteMetaForDraft`):
 * 1. Check in-memory [ThreadComposerQuoteMetaCache] (exact metadata from the quote action).
 * 2. Scan loaded thread messages (recovering real author, kind, and full UUID for click-to-jump).
 * 3. Graceful fallback using `refId` so cold reloads never lose the draft.
 */
internal fun resolveQuoteMetaForDraft(
    refId: String,
    excerpt: String,
    loadedMessages: List<RaftThreadMessageItem>,
    fallbackAuthorName: String,
): PendingComposerQuote {
    val shortId = refId.trim().take(8).lowercase()
    ThreadComposerQuoteMetaCache.lookup(shortId)?.let { cached ->
        return PendingComposerQuote(
            messageId = cached.messageId,
            authorName = cached.authorName.ifBlank { fallbackAuthorName },
            authorType = cached.authorType.ifBlank { "user" },
            excerpt = excerpt,
            isPartial = cached.isPartial ?: true,
            createdAt = cached.createdAt,
        )
    }
    val found =
        loadedMessages.firstOrNull { message ->
            message.id.lowercase().startsWith(shortId)
        }
    if (found != null) {
        val wholeExcerpt = buildWholeMessageQuoteExcerpt(found.body).excerpt
        val isPartial = wholeExcerpt.isEmpty() || normalizeQuoteExcerptKey(excerpt) != normalizeQuoteExcerptKey(wholeExcerpt)
        val quote =
            PendingComposerQuote(
                messageId = found.id,
                authorName = found.authorName.ifBlank { fallbackAuthorName },
                authorType = found.quoteReplyAuthorType(),
                excerpt = excerpt,
                isPartial = isPartial,
                createdAt = found.timestampLabel,
            )
        ThreadComposerQuoteMetaCache.remember(quote)
        return quote
    }
    return PendingComposerQuote(
        messageId = refId,
        authorName = fallbackAuthorName,
        authorType = "user",
        excerpt = excerpt,
        isPartial = true,
        createdAt = "",
    )
}

/** Pure key-down decision for an interleaved multi-quote text block (web `useComposerQuoteController`). */
internal sealed interface ThreadComposerQuoteKeyDecision {
    data object PassThrough : ThreadComposerQuoteKeyDecision

    data object DisarmAndPassThrough : ThreadComposerQuoteKeyDecision

    data class CollapseEmptyTextBlock(val textBlockId: String) : ThreadComposerQuoteKeyDecision

    data class ArmQuote(val quoteBlockId: String) : ThreadComposerQuoteKeyDecision

    data class RemoveArmedQuote(val quoteBlockId: String) : ThreadComposerQuoteKeyDecision

    data class FocusTextBlock(val textBlockId: String, val offset: Int) : ThreadComposerQuoteKeyDecision

    data object InsertIntroGapBeforeFirstQuote : ThreadComposerQuoteKeyDecision
}

/**
 * Pure key handler mirroring `useComposerQuoteController.ts` lines 404-478:
 * - Backspace at caret 0 on an empty non-trailing interleaved/intro text block collapses ONLY that
 *   empty row (never arming or deleting the quote above it).
 * - Backspace at caret 0 directly below a quote chip arms on 1st press and removes on 2nd press.
 * - Any other non-Backspace key disarms an armed quote chip and passes through.
 * - ArrowUp at caret 0 jumps to the previous text block (at end), or opens an intro gap above
 *   quote 0 when none exists.
 * - ArrowDown at end jumps to the next text block (at offset 0).
 */
internal fun threadComposerQuoteBlockKeyDecision(
    blocks: List<ComposerBlock>,
    blockIndex: Int,
    key: Key,
    isComposing: Boolean,
    shift: Boolean,
    alt: Boolean,
    ctrl: Boolean,
    meta: Boolean,
    selectionStart: Int,
    selectionEnd: Int,
    textLength: Int,
    armedDeleteQuoteId: String?,
): ThreadComposerQuoteKeyDecision {
    val start = minOf(selectionStart, selectionEnd).coerceIn(0, textLength)
    val end = maxOf(selectionStart, selectionEnd).coerceIn(start, textLength)
    if (
        key == Key.Backspace &&
        !isComposing &&
        !meta &&
        !ctrl &&
        !alt &&
        start == 0 &&
        end == 0
    ) {
        val curBlock = blocks.getOrNull(blockIndex)
        if (curBlock is ComposerBlock.Text && curBlock.text.isEmpty() && blockIndex < blocks.lastIndex) {
            return ThreadComposerQuoteKeyDecision.CollapseEmptyTextBlock(curBlock.id)
        }
        val prevBlock = blocks.getOrNull(blockIndex - 1)
        if (prevBlock is ComposerBlock.Quote) {
            return if (armedDeleteQuoteId == prevBlock.id) {
                ThreadComposerQuoteKeyDecision.RemoveArmedQuote(prevBlock.id)
            } else {
                ThreadComposerQuoteKeyDecision.ArmQuote(prevBlock.id)
            }
        }
    }

    if (armedDeleteQuoteId != null && key != Key.Backspace) {
        return ThreadComposerQuoteKeyDecision.DisarmAndPassThrough
    }

    if (
        key == Key.DirectionUp &&
        !isComposing &&
        !shift &&
        !alt &&
        !meta &&
        !ctrl &&
        start == 0 &&
        end == 0
    ) {
        for (i in (blockIndex - 1) downTo 0) {
            val candidate = blocks[i]
            if (candidate is ComposerBlock.Text) {
                return ThreadComposerQuoteKeyDecision.FocusTextBlock(candidate.id, candidate.text.length)
            }
        }
        if (blockIndex > 0 && blocks.firstOrNull() is ComposerBlock.Quote) {
            return ThreadComposerQuoteKeyDecision.InsertIntroGapBeforeFirstQuote
        }
    }

    if (
        key == Key.DirectionDown &&
        !isComposing &&
        !shift &&
        !alt &&
        !meta &&
        !ctrl &&
        start == textLength &&
        end == textLength
    ) {
        for (i in (blockIndex + 1) until blocks.size) {
            val candidate = blocks[i]
            if (candidate is ComposerBlock.Text) {
                return ThreadComposerQuoteKeyDecision.FocusTextBlock(candidate.id, 0)
            }
        }
    }

    return ThreadComposerQuoteKeyDecision.PassThrough
}

/** Pending focus transfer into a specific segmented text block. */
internal data class ComposerBlockFocusRequest(
    val nonce: Int,
    val blockId: String,
    val offset: Int,
    val raiseKeyboard: Boolean = false,
)

/**
 * Stateful Compose controller for interleaved multi-quote editing inside [ThreadComposerContentLeaf]
 * (mirror of web `useComposerQuoteController`). On the 0-quote hot path (`isSegmented == false`),
 * [syncFromExternalDraft] short-circuits in O(1) via [hasSameConversationQuoteRef].
 */
@Stable
internal class ThreadComposerQuoteController(
    initialDraft: String,
    resolveQuoteMeta: (refId: String, excerpt: String) -> PendingComposerQuote,
) {
    var lastCommittedSerialized: String = initialDraft
        private set
    var blocks by mutableStateOf(parseDraftToComposerBlocks(initialDraft, resolveQuoteMeta))
        private set
    var activeTextBlockId by mutableStateOf(defaultActiveTextBlockId(blocks))
        private set
    var focusedTextBlockId: String? = null
        private set
    var armedDeleteQuoteId by mutableStateOf<String?>(null)
        private set
    var duplicateFlashBlockId by mutableStateOf<String?>(null)
        private set
    var duplicateFlashNonce by mutableStateOf(0)
        private set
    var pendingFocusRequest by mutableStateOf<ComposerBlockFocusRequest?>(null)
        private set

    private var focusNonceCounter: Int = 0
    private val textFieldStatesByBlockId = linkedMapOf<String, TextFieldState>().apply {
        for (block in blocks) {
            if (block is ComposerBlock.Text) {
                put(block.id, TextFieldState(block.text, initialSelection = TextRange(block.text.length)))
            }
        }
    }

    val quoteCount: Int
        get() = countComposerQuotes(blocks)

    val isSegmented: Boolean
        get() = quoteCount > 0

    val hasReplyText: Boolean
        get() = hasComposerReplyText(blocksWithLiveTextStates())

    fun textFieldStateFor(block: ComposerBlock.Text): TextFieldState =
        textFieldStatesByBlockId.getOrPut(block.id) {
            TextFieldState(block.text, initialSelection = TextRange(block.text.length))
        }

    fun activeTextBlockOrNull(): ComposerBlock.Text? =
        blocks.firstOrNull { it is ComposerBlock.Text && it.id == activeTextBlockId } as? ComposerBlock.Text
            ?: blocks.lastOrNull { it is ComposerBlock.Text } as? ComposerBlock.Text

    fun activeTextFieldState(): TextFieldState? =
        activeTextBlockOrNull()?.let(::textFieldStateFor)

    fun textFieldValueFor(block: ComposerBlock.Text): TextFieldValue =
        textFieldStateFor(block).toRaftTextFieldValue()

    fun activeFieldValue(): TextFieldValue =
        activeTextFieldState()?.toRaftTextFieldValue() ?: TextFieldValue("")

    fun activeTextValue(): String = activeTextFieldState()?.text?.toString().orEmpty()

    /**
     * Synchronize with an external draft change (channel switch, restored draft, Clear after Send,
     * or RestoreAfterFailure). Short-circuits in O(1) on plain text.
     */
    fun syncFromExternalDraft(
        draft: String,
        resolveQuoteMeta: (refId: String, excerpt: String) -> PendingComposerQuote,
    ) {
        val unchanged =
            lastCommittedSerialized == draft ||
                (countComposerQuotes(blocks) == 0 && !hasSameConversationQuoteRef(draft))
        if (unchanged) return
        lastCommittedSerialized = draft
        armedDeleteQuoteId = null
        duplicateFlashBlockId = null
        val parsed = parseDraftToComposerBlocks(draft, resolveQuoteMeta)
        blocks = parsed
        activeTextBlockId = defaultActiveTextBlockId(parsed)
        focusedTextBlockId = null
        textFieldStatesByBlockId.clear()
        for (block in parsed) {
            if (block is ComposerBlock.Text) {
                textFieldStatesByBlockId[block.id] =
                    TextFieldState(block.text, initialSelection = TextRange(block.text.length))
            }
        }
    }

    fun resetBlocks() {
        val empty = createTextOnlyBlocks("")
        val id = empty.first().id
        blocks = empty
        activeTextBlockId = id
        focusedTextBlockId = null
        lastCommittedSerialized = ""
        armedDeleteQuoteId = null
        duplicateFlashBlockId = null
        textFieldStatesByBlockId.clear()
        textFieldStatesByBlockId[id] = TextFieldState("", initialSelection = TextRange.Zero)
    }

    fun disarmQuote() {
        if (armedDeleteQuoteId != null) armedDeleteQuoteId = null
    }

    fun armQuote(quoteBlockId: String) {
        armedDeleteQuoteId = quoteBlockId
    }

    fun clearDuplicateFlash() {
        if (duplicateFlashBlockId != null) duplicateFlashBlockId = null
    }

    fun onTextBlockFocus(blockId: String) {
        activeTextBlockId = blockId
        focusedTextBlockId = blockId
        armedDeleteQuoteId = null
    }

    /** Updates one segmented text block from one-way user observation; returns the newly serialized draft string. */
    fun onTextBlockTextChanged(blockId: String, nextText: String): String {
        activeTextBlockId = blockId
        armedDeleteQuoteId = null
        val next = updateTextBlock(blocks, blockId, nextText)
        return commitBlocks(next, preferredActiveId = blockId, userEditedBlockId = blockId)
    }

    /** Collapses an empty non-trailing explicit gap on blur; returns serialized draft if changed, else null. */
    fun onTextBlockBlur(blockId: String): String? {
        if (focusedTextBlockId == blockId) {
            focusedTextBlockId = null
        }
        armedDeleteQuoteId = null
        val collapsed = collapseEmptyExplicitGap(blocks, blockId)
        if (collapsed.size == blocks.size) return null
        return commitBlocks(collapsed)
    }

    fun textFieldStateOrNull(blockId: String): TextFieldState? {
        val block = blocks.firstOrNull { it is ComposerBlock.Text && it.id == blockId } as? ComposerBlock.Text ?: return null
        return textFieldStateFor(block)
    }

    /** Replaces the active text block's text & selection (e.g. `@` / `#` suggestion, mention insert, or format tool). */
    fun replaceActiveTextBlock(
        nextText: String,
        nextCursor: Int,
        nextSelectionEnd: Int = nextCursor,
    ): String {
        val blockId =
            blocks.firstOrNull { it is ComposerBlock.Text && it.id == activeTextBlockId }?.id
                ?: defaultActiveTextBlockId(blocks)
        return replaceTextBlock(blockId, nextText, nextCursor, nextSelectionEnd) ?: lastCommittedSerialized
    }

    /**
     * Replaces a specific text block's text & selection when [blockId] still exists in [blocks].
     * Returns the newly serialized draft string, or `null` if that block was removed.
     */
    fun replaceTextBlock(
        blockId: String,
        nextText: String,
        nextCursor: Int,
        nextSelectionEnd: Int = nextCursor,
    ): String? {
        if (blocks.none { it is ComposerBlock.Text && it.id == blockId }) return null
        val clampedStart = nextCursor.coerceIn(0, nextText.length)
        val clampedEnd = nextSelectionEnd.coerceIn(clampedStart, nextText.length)
        val targetState =
            textFieldStatesByBlockId.getOrPut(blockId) {
                TextFieldState(nextText, initialSelection = TextRange(clampedStart, clampedEnd))
            }
        targetState.edit {
            if (asCharSequence().toString() != nextText) {
                replace(0, length, nextText)
            }
            selection = TextRange(clampedStart, clampedEnd)
        }
        armedDeleteQuoteId = null
        val next = updateTextBlock(blocks, blockId, nextText)
        return commitBlocks(next, preferredActiveId = blockId, userEditedBlockId = blockId)
    }

    /**
     * Insert a quote at the active caret (or live plain-text caret when transitioning from 0 quotes).
     * Returns `(InsertQuoteResult, serializedDraftOrNull)`.
     */
    fun insertQuote(
        quote: PendingComposerQuote,
        livePlainFieldValue: TextFieldValue,
    ): Pair<InsertQuoteResult, String?> {
        ThreadComposerQuoteMetaCache.remember(quote)
        val alreadySegmented = countComposerQuotes(blocks) > 0
        val baseBlocks =
            if (alreadySegmented) {
                blocksWithLiveTextStates()
            } else {
                createTextOnlyBlocks(livePlainFieldValue.text)
            }
        val targetBlockId =
            if (alreadySegmented) {
                activeTextBlockId
            } else {
                baseBlocks.first().id
            }
        val targetOffset =
            if (alreadySegmented) {
                textFieldStatesByBlockId[targetBlockId]?.selection?.let { minOf(it.start, it.end) }
            } else {
                minOf(livePlainFieldValue.selection.start, livePlainFieldValue.selection.end)
            }

        val result =
            insertQuoteAtCursor(
                blocks = baseBlocks,
                quote = quote,
                cursor = ComposerBlockCursor(blockId = targetBlockId, offset = targetOffset),
            )
        return when (result) {
            is InsertQuoteResult.Duplicate -> {
                duplicateFlashBlockId = result.duplicateBlockId
                duplicateFlashNonce += 1
                result to null
            }
            is InsertQuoteResult.Limit -> result to null
            is InsertQuoteResult.Inserted -> {
                armedDeleteQuoteId = null
                val serialized =
                    commitBlocks(
                        nextBlocks = result.blocks,
                        preferredActiveId = result.focusBlockId,
                        focusOffsetOverride = result.focusOffset,
                        requestFocus = true,
                        raiseKeyboard = true,
                    )
                result to serialized
            }
        }
    }

    /** Removes a quote chip, merges surrounding text, and returns `(serializedDraft, focusOffset)`. */
    fun removeQuote(quoteBlockId: String): Pair<String, Int> {
        armedDeleteQuoteId = null
        val mutation = removeQuoteBlock(blocksWithLiveTextStates(), quoteBlockId)
        val serialized =
            commitBlocks(
                nextBlocks = mutation.blocks,
                preferredActiveId = mutation.focusBlockId,
                focusOffsetOverride = mutation.focusOffset,
                requestFocus = true,
                raiseKeyboard = false,
            )
        return serialized to mutation.focusOffset
    }

    /** Inserts an explicit reply text block below a stacked quote chip and focuses it. */
    fun insertGapAfterQuote(quoteBlockId: String): String {
        armedDeleteQuoteId = null
        val mutation = insertTextGapAfterQuote(blocksWithLiveTextStates(), quoteBlockId)
        return commitBlocks(
            nextBlocks = mutation.blocks,
            preferredActiveId = mutation.focusBlockId,
            focusOffsetOverride = 0,
            requestFocus = true,
            raiseKeyboard = true,
        )
    }

    /** Inserts an explicit intro text block before `blocks[quoteIndex]` and focuses it. */
    fun insertGapBeforeIndex(quoteIndex: Int): String {
        armedDeleteQuoteId = null
        val mutation = insertTextGapBeforeIndex(blocksWithLiveTextStates(), quoteIndex)
        return commitBlocks(
            nextBlocks = mutation.blocks,
            preferredActiveId = mutation.focusBlockId,
            focusOffsetOverride = 0,
            requestFocus = true,
            raiseKeyboard = false,
        )
    }

    /** Collapses an empty non-trailing interleaved/intro text block without touching adjacent quotes. */
    fun removeEmptyTextBlock(textBlockId: String): String {
        armedDeleteQuoteId = null
        val mutation = build.raft.app.removeEmptyTextBlock(blocksWithLiveTextStates(), textBlockId)
        return commitBlocks(
            nextBlocks = mutation.blocks,
            preferredActiveId = mutation.focusBlockId,
            focusOffsetOverride = mutation.focusOffset,
            requestFocus = true,
            raiseKeyboard = false,
        )
    }

    fun requestFocusBlock(
        blockId: String,
        offset: Int,
        raiseKeyboard: Boolean = false,
        selectionEnd: Int = offset,
    ) {
        val textBlock = blocks.firstOrNull { it is ComposerBlock.Text && it.id == blockId } as? ComposerBlock.Text
        val liveState = textBlock?.let { textFieldStateFor(it) }
        val textLength = liveState?.text?.length ?: textBlock?.text?.length ?: 0
        val clampedStart = offset.coerceIn(0, textLength)
        val clampedEnd = selectionEnd.coerceIn(clampedStart, textLength)
        activeTextBlockId = blockId
        liveState?.edit {
            selection = TextRange(clampedStart, clampedEnd)
        }
        focusNonceCounter += 1
        pendingFocusRequest =
            ComposerBlockFocusRequest(
                nonce = focusNonceCounter,
                blockId = blockId,
                offset = clampedStart,
                raiseKeyboard = raiseKeyboard,
            )
    }

    fun consumeFocusRequest(nonce: Int) {
        if (pendingFocusRequest?.nonce == nonce) {
            pendingFocusRequest = null
        }
    }

    /**
     * Merges live [TextFieldState] contents (and optional [activeBlockOverride]) across all text
     * blocks and returns `(serializedWireMarkdown, optimisticResolvedQuotes)`.
     */
    fun buildSendPayload(
        activeBlockOverride: TextFieldValue? = null,
    ): Pair<String, Map<String, ResolvedInlineQuote>?> {
        val liveBlocks = blocksWithLiveTextStates()
        val merged =
            if (activeBlockOverride != null) {
                updateTextBlock(liveBlocks, activeTextBlockId, activeBlockOverride.text)
            } else {
                liveBlocks
            }
        val serialized = serializeComposerBlocks(merged)
        val optimistic = buildOptimisticResolvedQuotesFromBlocks(merged)
        return serialized to optimistic
    }

    private fun blocksWithLiveTextStates(): List<ComposerBlock> =
        blocks.map { block ->
            if (block is ComposerBlock.Text) {
                val live = textFieldStatesByBlockId[block.id]?.text?.toString()
                if (live != null && live != block.text) block.copy(text = live) else block
            } else {
                block
            }
        }

    private fun commitBlocks(
        nextBlocks: List<ComposerBlock>,
        preferredActiveId: String? = null,
        focusOffsetOverride: Int? = null,
        requestFocus: Boolean = false,
        raiseKeyboard: Boolean = false,
        userEditedBlockId: String? = null,
    ): String {
        val nextCount = countComposerQuotes(nextBlocks)
        val serialized =
            if (nextCount > 0) {
                serializeComposerBlocks(nextBlocks)
            } else {
                (nextBlocks.firstOrNull { it is ComposerBlock.Text } as? ComposerBlock.Text)?.text.orEmpty()
            }
        val nextActiveId =
            preferredActiveId?.takeIf { id -> nextBlocks.any { it is ComposerBlock.Text && it.id == id } }
                ?: activeTextBlockId.takeIf { id -> nextBlocks.any { it is ComposerBlock.Text && it.id == id } }
                ?: defaultActiveTextBlockId(nextBlocks)

        val validTextIds = HashSet<String>()
        for (block in nextBlocks) {
            if (block !is ComposerBlock.Text) continue
            validTextIds += block.id
            val existing = textFieldStatesByBlockId[block.id]
            when {
                block.id == userEditedBlockId && existing != null -> Unit
                existing == null -> {
                    val initialCursor =
                        if (block.id == nextActiveId && focusOffsetOverride != null) {
                            focusOffsetOverride.coerceIn(0, block.text.length)
                        } else {
                            block.text.length
                        }
                    textFieldStatesByBlockId[block.id] =
                        TextFieldState(block.text, initialSelection = TextRange(initialCursor))
                }
                block.id == nextActiveId && focusOffsetOverride != null -> {
                    val clamped = focusOffsetOverride.coerceIn(0, block.text.length)
                    existing.edit {
                        if (asCharSequence().toString() != block.text) {
                            replace(0, length, block.text)
                        }
                        selection = TextRange(clamped)
                    }
                }
                existing.text.toString() != block.text -> {
                    existing.edit {
                        replace(0, length, block.text)
                        selection = TextRange(block.text.length)
                    }
                }
            }
        }
        val iterator = textFieldStatesByBlockId.keys.iterator()
        while (iterator.hasNext()) {
            if (iterator.next() !in validTextIds) {
                iterator.remove()
            }
        }
        blocks = nextBlocks
        activeTextBlockId = nextActiveId
        if (focusedTextBlockId != null && focusedTextBlockId !in validTextIds) {
            focusedTextBlockId = null
        }
        lastCommittedSerialized = serialized
        if (requestFocus) {
            val activeText = textFieldStatesByBlockId[nextActiveId]?.text?.toString().orEmpty()
            val clamped = (focusOffsetOverride ?: activeText.length).coerceIn(0, activeText.length)
            focusNonceCounter += 1
            pendingFocusRequest =
                ComposerBlockFocusRequest(
                    nonce = focusNonceCounter,
                    blockId = nextActiveId,
                    offset = clamped,
                    raiseKeyboard = raiseKeyboard,
                )
        }
        return serialized
    }

    private companion object {
        fun defaultActiveTextBlockId(blocks: List<ComposerBlock>): String =
            blocks.lastOrNull { it is ComposerBlock.Text }?.id
                ?: blocks.firstOrNull()?.id.orEmpty()
    }
}
