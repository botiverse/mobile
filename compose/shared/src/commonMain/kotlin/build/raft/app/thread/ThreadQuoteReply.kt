package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import build.raft.app.base.BridgeModule
import build.raft.app.data.MAX_INLINE_QUOTES_PER_MESSAGE
import build.raft.app.data.MessageQuoteSegment
import build.raft.app.data.QUOTE_REPLY_FEATURE_FLAG_KEY
import build.raft.app.data.SharedResolvedQuotesStore
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.buildWholeMessageQuoteExcerpt
import build.raft.app.data.formatPlainBlockquote
import build.raft.app.data.hasSameConversationQuoteRef
import build.raft.app.data.isQuoteReplyMessageId
import build.raft.app.data.messageCardRevisionOf
import build.raft.app.data.parseMessageQuoteSegments
import build.raft.app.data.quoteChipExcerpt
import build.raft.app.data.rememberServerFeatureLatched
import build.raft.app.data.serializeInlineQuoteReply
import build.raft.app.model.ResolvedInlineQuote
import build.raft.app.model.ServerId
import build.raft.app.resources.*

/*
 * Quote replies in a message (web `quote_reply_v0`, botiverse/slock #15536 / #16027 / #16210):
 * - Gated behind `QUOTE_REPLY_FEATURE_FLAG_KEY` (`quote_reply_v0`): off, messages render as plain
 *   Markdown without resolved-quote fetches, message menu hides Quote, selection quotes insert
 *   plain blockquotes, and the composer stays single-textarea.
 * - On: each server-verified quote block renders as a one-line quote chip (avatar, semibold author,
 *   truncated excerpt; tapping jumps to the quoted message); unverified/degraded blocks keep their
 *   excerpt as a plain Markdown blockquote without the `> msg=<id>` line.
 */

/**
 * Where a message that came without its resolved quotes (a live one) reads them: this viewer's
 * store, provided by the thread page. None (fixtures, previews): such quotes stay plain blockquotes.
 */
internal val LocalThreadResolvedQuotesStore = compositionLocalOf<SharedResolvedQuotesStore?> { null }

/**
 * Ambient quote-reply capabilities for a conversation page (feature-flag gate, avatar lookup,
 * composer quote insertion queue, feedback toasts, and jump callback). Kept in one object so
 * `SharedThreadRouteContentContent` stays well below its JVM bytecode ceiling.
 */
@Stable
internal class ThreadQuoteReplyHost(
    enabled: Boolean = false,
    avatarLookup: ThreadQuoteChipAvatarLookup = ThreadQuoteChipAvatarLookup.Empty,
    onJumpToQuotedMessage: ((String) -> Unit)? = null,
    onFallbackPlainQuote: ((String) -> Unit)? = null,
    onQuoteFeedback: ((ThreadComposerQuoteFeedback) -> Unit)? = null,
    lookupMessageById: (String) -> RaftThreadMessageItem? = { null },
    loadedMessagesProvider: () -> List<RaftThreadMessageItem> = { emptyList() },
) {
    var enabled by mutableStateOf(enabled)
        private set
    var avatarLookup by mutableStateOf(avatarLookup)
        private set
    var onJumpToQuotedMessage by mutableStateOf(onJumpToQuotedMessage)
        private set
    var pendingInsertRequest by mutableStateOf<ThreadComposerInsertQuoteRequest?>(null)
        private set

    private var onFallbackPlainQuoteFn: ((String) -> Unit)? = onFallbackPlainQuote
    private var onQuoteFeedbackFn: ((ThreadComposerQuoteFeedback) -> Unit)? = onQuoteFeedback
    private var lookupMessageByIdFn: (String) -> RaftThreadMessageItem? = lookupMessageById
    private var loadedMessagesFn: () -> List<RaftThreadMessageItem> = loadedMessagesProvider
    private var insertNonceCounter: Int = 0

    internal fun update(
        enabled: Boolean,
        avatarLookup: ThreadQuoteChipAvatarLookup,
        onJumpToQuotedMessage: ((String) -> Unit)?,
        onFallbackPlainQuote: ((String) -> Unit)?,
        onQuoteFeedback: ((ThreadComposerQuoteFeedback) -> Unit)?,
        lookupMessageById: (String) -> RaftThreadMessageItem?,
        loadedMessagesProvider: () -> List<RaftThreadMessageItem>,
    ) {
        this.enabled = enabled
        this.avatarLookup = avatarLookup
        this.onJumpToQuotedMessage = onJumpToQuotedMessage
        this.onFallbackPlainQuoteFn = onFallbackPlainQuote
        this.onQuoteFeedbackFn = onQuoteFeedback
        this.lookupMessageByIdFn = lookupMessageById
        this.loadedMessagesFn = loadedMessagesProvider
    }

    fun requestInsertQuote(insert: ThreadComposerQuoteInsert) {
        val quote = insert.quote
        if (enabled && quote != null && quote.excerpt.isNotBlank()) {
            ThreadComposerQuoteMetaCache.remember(quote)
            insertNonceCounter += 1
            pendingInsertRequest = ThreadComposerInsertQuoteRequest(nonce = insertNonceCounter, quote = quote)
        } else if (insert.fallbackMarkdown.isNotBlank()) {
            onFallbackPlainQuoteFn?.invoke(insert.fallbackMarkdown)
        }
    }

    fun consumeInsertRequest(request: ThreadComposerInsertQuoteRequest) {
        if (pendingInsertRequest?.nonce == request.nonce) {
            pendingInsertRequest = null
        }
    }

    fun reportFeedback(feedback: ThreadComposerQuoteFeedback) {
        onQuoteFeedbackFn?.invoke(feedback)
    }

    fun lookupMessage(messageId: String): RaftThreadMessageItem? = lookupMessageByIdFn(messageId)

    fun loadedMessages(): List<RaftThreadMessageItem> = loadedMessagesFn()
}

internal val LocalThreadQuoteReplyHost = compositionLocalOf { ThreadQuoteReplyHost() }

/**
 * Builds and keeps [ThreadQuoteReplyHost] in sync for a live thread page. Extracted outside
 * `SharedThreadRouteContentContent` to keep its bytecode size well below the JVM budget.
 */
@Composable
internal fun rememberThreadQuoteReplyHost(
    featureFlags: SharedServerFeatureFlags,
    serverId: String?,
    renderedThread: RaftThreadScreenPayload,
    timelineMessages: List<RaftThreadMessageItem>,
    serverMembersStore: SharedServerMembersStore?,
    currentSession: StoredAuthSession?,
    memberAvatarResolver: RaftMemberAvatarResolver,
    composerDraftViewModel: SharedThreadComposerDraftViewModel,
    threadViewModel: SharedThreadViewModel,
    bridge: BridgeModule,
): ThreadQuoteReplyHost {
    val cleanServerId = serverId?.trim()?.takeIf { it.isNotBlank() }?.let(::ServerId)
    val enabled = rememberServerFeatureLatched(featureFlags, cleanServerId, QUOTE_REPLY_FEATURE_FLAG_KEY)
    val duplicateToastLabel = stringResource(Res.string.thread_quote_reply_duplicate)
    val limitToastLabel = stringResource(Res.string.thread_quote_reply_limit_reached, MAX_INLINE_QUOTES_PER_MESSAGE)
    val currentDuplicateLabel by rememberUpdatedState(duplicateToastLabel)
    val currentLimitLabel by rememberUpdatedState(limitToastLabel)

    val loadedMessages =
        remember(timelineMessages, renderedThread.originMessage) {
            val origin = renderedThread.originMessage
            if (origin != null && timelineMessages.none { it.id == origin.id }) {
                listOf(origin) + timelineMessages
            } else {
                timelineMessages
            }
        }
    val serverMembers =
        remember(serverMembersStore, cleanServerId, loadedMessages.size) {
            if (serverMembersStore != null && cleanServerId != null) {
                serverMembersStore.currentServerMembers(cleanServerId)
            } else {
                emptyList()
            }
        }
    val currentUser = currentSession?.user
    val avatarLookup =
        remember(loadedMessages, serverMembers, currentUser, memberAvatarResolver) {
            ThreadQuoteChipAvatarLookup { messageId, authorName, authorType ->
                resolveQuoteAuthorAvatar(
                    messageId = messageId,
                    authorName = authorName,
                    authorType = authorType,
                    loadedMessages = loadedMessages,
                    members = serverMembers,
                    currentUser = currentUser,
                    memberAvatarResolver = memberAvatarResolver,
                )
            }
        }
    val jumpToQuoted =
        remember(threadViewModel) {
            { quotedMessageId: String ->
                threadViewModel.dispatch(
                    SharedThreadIntent.JumpToMessage(
                        messageId = quotedMessageId,
                        switchToTab = RaftThreadTab.Chat.wireValue,
                        unreadThreadPill = false,
                    )
                )
            }
        }
    val fallbackPlainQuote =
        remember(composerDraftViewModel) {
            { markdown: String ->
                composerDraftViewModel.dispatch(SharedThreadComposerDraftIntent.AppendQuote(markdown))
            }
        }
    val reportFeedback =
        remember(bridge) {
            { feedback: ThreadComposerQuoteFeedback ->
                when (feedback) {
                    ThreadComposerQuoteFeedback.Duplicate -> bridge.toast(currentDuplicateLabel)
                    is ThreadComposerQuoteFeedback.LimitReached -> bridge.toast(currentLimitLabel)
                }
            }
        }
    val host = remember { ThreadQuoteReplyHost() }
    host.update(
        enabled = enabled,
        avatarLookup = avatarLookup,
        onJumpToQuotedMessage = jumpToQuoted,
        onFallbackPlainQuote = fallbackPlainQuote,
        onQuoteFeedback = reportFeedback,
        lookupMessageById = { id -> loadedMessages.firstOrNull { it.id == id } },
        loadedMessagesProvider = { loadedMessages },
    )
    return host
}

/** One piece of a quote reply's body, in order. */
internal sealed interface ThreadQuoteReplyBlock {
    /** Markdown drawn as the message body is: text, and quotes that did not resolve as plain blockquotes. */
    data class Markdown(val markdown: String) : ThreadQuoteReplyBlock

    /** A verified quote: rendered as a one-line quote chip. */
    data class Card(val blockIndex: Int, val excerpt: String, val quote: ResolvedInlineQuote) : ThreadQuoteReplyBlock
}

/**
 * web QuoteReplyBlocks: the body's pieces. A quote block with a resolved quote (one of the first
 * [MAX_INLINE_QUOTES_PER_MESSAGE], as the server resolves no more) is a chip; every other one is a
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

/** web QuoteReplyPreview `my-1.5`: the space between a quote chip and the text around it. */
private val ThreadQuoteReplyBlockGap = 6.dp

internal fun threadQuoteReplyCardTestTag(blockIndex: Int): String = "thread_quote_reply_card_$blockIndex"

/**
 * A message body with its quote replies (web QuoteReplyBlocks). [markdown] draws a piece of Markdown
 * as the message body does; when `quote_reply_v0` is off ([quoteReplyEnabled] false) or a body has
 * no same-conversation quote block, it is drawn by [markdown] whole with zero extra work.
 * [resolvedQuotes]: the message's own ([RaftThreadMessageItem.resolvedQuotes]); null reads them
 * from [LocalThreadResolvedQuotesStore]. [onJumpToQuotedMessage] gets the quoted message's id; null
 * leaves the chips non-interactive (a share image, a preview).
 */
@Composable
internal fun ThreadQuoteReplyBody(
    messageId: String,
    body: String,
    resolvedQuotes: Map<String, ResolvedInlineQuote>?,
    onJumpToQuotedMessage: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false,
    quoteReplyEnabled: Boolean = LocalThreadQuoteReplyHost.current.enabled,
    avatarLookup: ThreadQuoteChipAvatarLookup = LocalThreadQuoteReplyHost.current.avatarLookup,
    markdown: @Composable (String) -> Unit,
) {
    val segments =
        remember(body, quoteReplyEnabled) {
            if (quoteReplyEnabled && hasSameConversationQuoteRef(body)) parseMessageQuoteSegments(body) else null
        }
    if (segments == null || segments.none { it is MessageQuoteSegment.QuoteBlock }) {
        markdown(body)
        return
    }
    val quotes = rememberThreadResolvedQuotes(messageId, body, resolvedQuotes, quoteReplyEnabled)
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
                    key("quote", block.blockIndex) {
                        ThreadQuoteReplyChip(
                            block = block,
                            avatarLookup = avatarLookup,
                            onJump = onJumpToQuotedMessage,
                        )
                    }
            }
        }
    }
}

/**
 * The quotes of a message: its own when it came with them, else what the viewer's store read for it
 * (`GET /messages/:id/resolved-quotes`), asked once per message and words when `quote_reply_v0` is on.
 */
@Composable
private fun rememberThreadResolvedQuotes(
    messageId: String,
    body: String,
    resolvedQuotes: Map<String, ResolvedInlineQuote>?,
    quoteReplyEnabled: Boolean,
): Map<String, ResolvedInlineQuote> {
    if (!quoteReplyEnabled) return emptyMap()
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
 * web QuoteReplyPreview (botiverse/slock #16210): a server-verified quote in a message body, as
 * the same one-line quote chip the composer shows (avatar, semibold author, truncated excerpt,
 * without ×). Pressing jumps to the quoted message; read-only when [onJump] is null.
 */
@Composable
private fun ThreadQuoteReplyChip(
    block: ThreadQuoteReplyBlock.Card,
    avatarLookup: ThreadQuoteChipAvatarLookup,
    onJump: ((String) -> Unit)?,
) {
    val quote = block.quote
    val authorName = quote.authorName.ifBlank { stringResource(Res.string.thread_comments_unknown_author) }
    val chipExcerpt =
        quoteChipExcerpt(block.excerpt, quote.isPartial)
            .ifBlank { stringResource(Res.string.thread_quote_reply_empty_excerpt) }
    val groupLabel = stringResource(Res.string.thread_quote_reply_chip_group, authorName, chipExcerpt.take(120))
    val jumpLabel =
        if (onJump != null) {
            stringResource(Res.string.thread_quote_reply_chip_jump, authorName, chipExcerpt.take(120))
        } else {
            null
        }
    val avatar =
        remember(avatarLookup, quote.messageId, authorName, quote.authorType) {
            avatarLookup.resolve(quote.messageId, authorName, quote.authorType)
        }
    ThreadQuoteChipFrame(
        authorName = authorName,
        excerpt = chipExcerpt,
        avatar = avatar,
        chipLabel = groupLabel,
        modifier = Modifier.testTag(threadQuoteReplyCardTestTag(block.blockIndex)),
        mainModifier = Modifier.testTag(threadQuoteReplyChipTestTag(block.blockIndex)),
        onJump = if (onJump != null) ({ onJump(quote.messageId) }) else null,
        jumpLabel = jumpLabel,
    )
}

/** Wire authorType (`"user" | "agent" | "external_projection" | "system"`) for [PendingComposerQuote]. */
internal fun RaftThreadMessageItem.quoteReplyAuthorType(): String =
    when {
        authorKind.equals("agent", ignoreCase = true) -> "agent"
        externalAuthor != null -> "external_projection"
        authorKind.equals("human", ignoreCase = true) || authorKind.equals("user", ignoreCase = true) -> "user"
        else -> "system"
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

/** Structured whole-message quote for the multi-quote composer; null when the message can't be quoted. */
internal fun threadMessageMenuPendingQuote(
    message: RaftThreadMessageItem,
    authorName: String = message.authorName,
): PendingComposerQuote? {
    if (!message.canQuoteReply()) return null
    val excerpt = buildWholeMessageQuoteExcerpt(message.body).excerpt
    if (excerpt.isBlank()) return null
    return PendingComposerQuote(
        messageId = message.id,
        authorName = authorName.ifBlank { message.authorName },
        authorType = message.quoteReplyAuthorType(),
        excerpt = excerpt,
        isPartial = false,
        createdAt = message.timestampLabel,
    )
}

/** Structured quote insert for a text selection (or whole message when [selectedText] is blank). */
internal fun threadSelectionQuoteInsert(
    selectedText: String,
    markdown: String,
    message: RaftThreadMessageItem?,
    authorName: String? = null,
    quoteReplyEnabled: Boolean,
    isPartialOverride: Boolean? = null,
): ThreadComposerQuoteInsert {
    val cleanSelection = selectedText.trim()
    val fallbackSource = cleanSelection.ifBlank { threadSelectTextSheetText(markdown) }
    val fallbackMarkdown = formatThreadMessageQuote(fallbackSource)
    if (!quoteReplyEnabled || message == null || !message.canQuoteReply()) {
        return ThreadComposerQuoteInsert(quote = null, fallbackMarkdown = fallbackMarkdown)
    }
    val isPartial = isPartialOverride ?: cleanSelection.isNotEmpty()
    val excerpt =
        if (isPartial) {
            cleanSelection
        } else {
            buildWholeMessageQuoteExcerpt(markdown).excerpt.ifBlank { cleanSelection }
        }
    if (excerpt.isBlank()) {
        return ThreadComposerQuoteInsert(quote = null, fallbackMarkdown = fallbackMarkdown)
    }
    return ThreadComposerQuoteInsert(
        quote =
            PendingComposerQuote(
                messageId = message.id,
                authorName = authorName?.takeIf(String::isNotBlank) ?: message.authorName,
                authorType = message.quoteReplyAuthorType(),
                excerpt = excerpt,
                isPartial = isPartial,
                createdAt = message.timestampLabel,
            ),
        fallbackMarkdown = fallbackMarkdown,
    )
}

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
