package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import build.raft.app.data.MAX_INLINE_QUOTES_PER_MESSAGE
import build.raft.app.data.MessageQuoteSegment
import build.raft.app.data.SharedResolvedQuotesStore
import build.raft.app.data.buildWholeMessageQuoteExcerpt
import build.raft.app.data.formatPlainBlockquote
import build.raft.app.data.hasSameConversationQuoteRef
import build.raft.app.data.isQuoteReplyMessageId
import build.raft.app.data.messageCardRevisionOf
import build.raft.app.data.parseMessageQuoteSegments
import build.raft.app.data.serializeInlineQuoteReply
import build.raft.app.model.ResolvedInlineQuote
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftMessageQuotedPreview
import build.raft.designsystem.components.RaftMessageQuotedPreviewDefaults

/*
 * Quote replies in a message (web `quote_reply_v0`: QuoteReplyBlocks.tsx, QuoteReplyPreview.tsx):
 * each quote block the server verified for this viewer is a card naming the quoted message's author
 * and time over its excerpt, and a tap goes to that message in this conversation; any other quote
 * block keeps its words as a plain blockquote without its `msg=` line.
 */

/**
 * Where a message that came without its resolved quotes (a live one) reads them: this viewer's
 * store, provided by the thread page. None (fixtures, previews): such quotes stay plain blockquotes.
 */
internal val LocalThreadResolvedQuotesStore = compositionLocalOf<SharedResolvedQuotesStore?> { null }

/** One piece of a quote reply's body, in order. */
internal sealed interface ThreadQuoteReplyBlock {
    /** Markdown drawn as the message body is: text, and quotes that did not resolve as plain blockquotes. */
    data class Markdown(val markdown: String) : ThreadQuoteReplyBlock

    /** A verified quote: its card. */
    data class Card(val blockIndex: Int, val excerpt: String, val quote: ResolvedInlineQuote) : ThreadQuoteReplyBlock
}

/**
 * web QuoteReplyBlocks: the body's pieces. A quote block with a resolved quote (one of the first
 * [MAX_INLINE_QUOTES_PER_MESSAGE], as the server resolves no more) is a card; every other one is a
 * plain blockquote of its excerpt, without the `msg=` line, joined with the text around it.
 */
internal fun threadQuoteReplyBlocks(
    segments: List<MessageQuoteSegment>,
    resolvedQuotes: Map<String, ResolvedInlineQuote>,
): List<ThreadQuoteReplyBlock> {
    val blocks = mutableListOf<ThreadQuoteReplyBlock>()
    val markdown = mutableListOf<String>()
    fun flushMarkdown() {
        if (markdown.isEmpty()) return
        blocks += ThreadQuoteReplyBlock.Markdown(markdown.joinToString("\n\n"))
        markdown.clear()
    }
    for (segment in segments) {
        when (segment) {
            is MessageQuoteSegment.Text -> if (segment.text.isNotBlank()) markdown += segment.text
            is MessageQuoteSegment.QuoteBlock -> {
                val quote =
                    resolvedQuotes[segment.blockIndex.toString()]
                        ?.takeIf { segment.blockIndex < MAX_INLINE_QUOTES_PER_MESSAGE }
                if (quote == null) {
                    markdown += formatPlainBlockquote(segment.excerpt)
                } else {
                    flushMarkdown()
                    blocks += ThreadQuoteReplyBlock.Card(segment.blockIndex, segment.excerpt, quote)
                }
            }
        }
    }
    flushMarkdown()
    return blocks
}

/** web QuoteReplyPreview `my-1.5`: the space between a quote card and the text around it. */
private val ThreadQuoteReplyBlockGap = 6.dp

internal fun threadQuoteReplyCardTestTag(blockIndex: Int): String = "thread_quote_reply_card_$blockIndex"

/**
 * A message body with its quote replies (web QuoteReplyBlocks). [markdown] draws a piece of Markdown
 * as the message body does; a body without a quote block is drawn by it whole, as before.
 * [resolvedQuotes]: the message's own ([RaftThreadMessageItem.resolvedQuotes]); null reads them
 * from [LocalThreadResolvedQuotesStore]. [onJumpToQuotedMessage] gets the quoted message's id; null
 * leaves the cards still (a share image, a preview).
 */
@Composable
internal fun ThreadQuoteReplyBody(
    messageId: String,
    body: String,
    resolvedQuotes: Map<String, ResolvedInlineQuote>?,
    onJumpToQuotedMessage: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false,
    markdown: @Composable (String) -> Unit,
) {
    val segments =
        remember(body) {
            if (hasSameConversationQuoteRef(body)) parseMessageQuoteSegments(body) else null
        }
    if (segments == null || segments.none { it is MessageQuoteSegment.QuoteBlock }) {
        markdown(body)
        return
    }
    val quotes = rememberThreadResolvedQuotes(messageId, body, resolvedQuotes)
    val blocks = remember(segments, quotes) { threadQuoteReplyBlocks(segments, quotes) }
    val only = blocks.singleOrNull()
    if (only is ThreadQuoteReplyBlock.Markdown) {
        // No quote resolved: the body as one piece of Markdown, its quotes plain.
        markdown(only.markdown)
        return
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(ThreadQuoteReplyBlockGap),
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    ) {
        blocks.forEachIndexed { index, block ->
            when (block) {
                is ThreadQuoteReplyBlock.Markdown -> key("markdown", index) { markdown(block.markdown) }
                is ThreadQuoteReplyBlock.Card ->
                    key("quote", block.blockIndex) { ThreadQuoteReplyCard(block, onJumpToQuotedMessage) }
            }
        }
    }
}

/**
 * The quotes of a message: its own when it came with them, else what the viewer's store read for it
 * (`GET /messages/:id/resolved-quotes`), asked once per message and words.
 */
@Composable
private fun rememberThreadResolvedQuotes(
    messageId: String,
    body: String,
    resolvedQuotes: Map<String, ResolvedInlineQuote>?,
): Map<String, ResolvedInlineQuote> {
    if (resolvedQuotes != null) return resolvedQuotes
    val store = LocalThreadResolvedQuotesStore.current
    val serverId = LocalThreadViewerServerId.current?.takeIf { it.isNotBlank() }
    if (store == null || serverId == null) return emptyMap()
    val quotesFlow = remember(store, serverId, messageId, body) { store.quotesFlow(serverId, messageId, body) }
    val hydrated by quotesFlow.collectAsState(initial = store.quotesFor(serverId, messageId, body))
    LaunchedEffect(store, serverId, messageId, body) { store.request(serverId, messageId, body) }
    return hydrated.orEmpty()
}

/**
 * web QuoteReplyPreview: the quoted message's author and time over the excerpt, in the design
 * system's quoted-message card (as a pasted permalink's quote, [ThreadQuotedPermalinkCard]).
 */
@Composable
private fun ThreadQuoteReplyCard(block: ThreadQuoteReplyBlock.Card, onJump: ((String) -> Unit)?) {
    val quote = block.quote
    val authorName = quote.authorName.ifBlank { stringResource(Res.string.thread_comments_unknown_author) }
    val createdAt = quote.createdAt
    val jumpLabel =
        if (onJump != null) stringResource(Res.string.thread_quote_reply_jump, authorName, block.excerpt.take(120)) else null
    RaftMessageQuotedPreview(
        authorName = authorName,
        text = block.excerpt,
        modifier = Modifier.testTag(threadQuoteReplyCardTestTag(block.blockIndex)),
        time =
            if (createdAt.isNotBlank()) {
                {
                    RaftRelativeTimestampText(
                        value = createdAt,
                        displayGroup = RaftTimestampDisplayGroup.Conversation,
                        style = RaftMessageQuotedPreviewDefaults.TimeStyle,
                        color = RaftMessageQuotedPreviewDefaults.TimeInk,
                        maxLines = 1,
                        toggleEnabled = false,
                    )
                }
            } else {
                null
            },
        onClick = if (onJump != null) { { onJump(quote.messageId) } } else null,
        onClickLabel = jumpLabel,
    )
}

/**
 * web MessageItem `canQuoteThisMessage`: a sent chat message with words of its own, not a system
 * message, an action or message card, a forward, a retracted message or a cut Activity preview.
 */
internal fun RaftThreadMessageItem.canQuoteReply(): Boolean =
    isQuoteReplyMessageId(id) &&
        isForwardableOrdinaryChatMessage() &&
        !hasLocalSendState() &&
        !isRetracted &&
        !hasActivityCutBody() &&
        messageCardRevisionOf(metadata) == null &&
        body.isNotBlank()

/**
 * The message menu's Quote (web `handleQuoteWholeMessage`): the message's opening line as a quote
 * reply of it, for the composer; null when the message can't be quoted.
 */
internal fun threadMessageMenuQuote(message: RaftThreadMessageItem): String? =
    if (message.canQuoteReply()) threadWholeMessageQuote(message.body, message.id) else null

/** A whole message quoted (web `buildWholeMessageQuoteExcerpt`): its first line, cut at 120; null when nothing is left. */
internal fun threadWholeMessageQuote(body: String, messageId: String): String? {
    if (!isQuoteReplyMessageId(messageId)) return null
    val excerpt = buildWholeMessageQuoteExcerpt(body).excerpt
    return if (excerpt.isBlank()) null else serializeInlineQuoteReply(excerpt, messageId, "")
}

/**
 * A picked passage quoted (web selection quote with its source message): a quote reply of
 * [messageId], or the plain `> ` blockquote when there is no server message to point at (a send
 * still on its way).
 */
internal fun threadSelectionQuote(text: String, messageId: String?): String =
    if (messageId != null && isQuoteReplyMessageId(messageId) && text.isNotBlank()) {
        serializeInlineQuoteReply(text, messageId, "")
    } else {
        formatThreadMessageQuote(text)
    }
