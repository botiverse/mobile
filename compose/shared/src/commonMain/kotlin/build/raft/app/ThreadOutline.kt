package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.data.RaftThreadOutlineApi
import build.raft.app.data.ThreadOutlineItem
import build.raft.app.markdown.markdownToPreviewText
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftPopoverSurface
import build.raft.designsystem.components.RaftTooltip
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftPointerInput
import build.raft.designsystem.theme.RaftScale
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

// The thread timeline (消息时间线, web ConversationOutline, botiverse/slock #13534): one tick per
// message a person wrote in a reply thread, and one for the message the thread started from,
// in a short rail at the list's right edge. Over it a card lists them, a line each, with a
// tooltip for the rest of the text, its author and time; a row jumps the list there.
//
// Mobile scope (artin 2026-11-08): wide windows only (the app's wide breakpoint, a tablet or
// the desktop app), where the web's desktop rail lives too. A mouse or trackpad opens the card
// on hover, as the web does; a finger on a tablet taps the rail. Phones show nothing.

/** Fewer messages from people than this in a thread (its first message aside), and it has no outline. */
internal const val ThreadOutlineMinItems = 4

/** web MIN_LIST_WIDTH: narrower than a thread beside the conversation, where it mostly lives. */
internal val ThreadOutlineMinListWidth: Dp = 360.dp

/** web LIVE_PREVIEW_CHARS / LIVE_SOURCE_CHARS: a live row's preview is made as the server makes it. */
private const val ThreadOutlineLivePreviewChars = 400
private const val ThreadOutlineLiveSourceChars = 1_000

/** web MAX_PAGES_BACK: how far one catch-up with the list pages the outline back. */
private const val ThreadOutlineMaxPagesBack = 10

private const val ThreadOutlineOpenDelayMs = 80L
private const val ThreadOutlineCloseDelayMs = 120L
private val ThreadOutlineTickRow = 10.dp
/** web RAIL_MAX_HEIGHT_PX: a short strip in the middle of the list; past it the rail scrolls. */
private val ThreadOutlineRailMaxHeight = 240.dp
private val ThreadOutlineRailWidth = 28.dp
private val ThreadOutlineCardRow = 30.dp
private const val ThreadOutlineCardMaxRows = 10
private val ThreadOutlineCardWidth = 300.dp
/** Rows from the card's oldest end at which the next older page loads (web LOAD_OLDER_WITHIN_PX). */
private const val ThreadOutlineLoadOlderWithinRows = 5

internal const val ThreadOutlineRailTestTag = "thread-outline-rail"
internal const val ThreadOutlineCardTestTag = "thread-outline-card"
internal fun threadOutlineRowTestTag(messageId: String): String = "thread-outline-row:$messageId"

/** The outline's reads; null where the page has no network (fixtures, screenshots): no outline. */
internal val LocalThreadOutlineApi = compositionLocalOf<RaftThreadOutlineApi?> { null }

// ---- Rules (web messageOutlineStore / outlineListStore / useOutlinePosition) ----

/**
 * What the server lists (listHumanOutlineMessages), for a message the page holds: a person's own
 * chat message, not one an assistant sent for them, not retracted, and sent (it has a seq).
 */
internal fun RaftThreadMessageItem.isThreadOutlineHumanMessage(): Boolean =
    authorKind.equals("human", ignoreCase = true) &&
        !isSystemMessage() &&
        delegatedBy == null &&
        !isRetracted &&
        (seq ?: 0L) > 0L &&
        !hasLocalSendState()

/** web `livePreview`: the Markdown's plain text on one line, cut at 400 characters. */
internal fun threadOutlinePreview(content: String): String {
    val text = markdownToPreviewText(content.take(ThreadOutlineLiveSourceChars)).replace(Regex("\\s+"), " ").trim()
    return if (text.length <= ThreadOutlineLivePreviewChars) text else text.take(ThreadOutlineLivePreviewChars)
}

internal fun RaftThreadMessageItem.toThreadOutlineItem(): ThreadOutlineItem =
    ThreadOutlineItem(
        id = id,
        seq = seq ?: 0L,
        createdAt = timestampLabel,
        senderId = authorId,
        senderName = authorName,
        preview = threadOutlinePreview(body),
        attachmentCount = attachments.size,
    )

/**
 * The outline's rows, oldest first: the thread's first message ([leadingMessageId]) first where
 * the server's pages reached it (its seq is its own conversation's, so it never sorts with the
 * replies), then the server's rows and the people's messages the page holds that are newer than
 * them (live arrivals, web `noteOutlineMessage`), by seq, each once. A message the page holds as
 * retracted leaves (web `noteOutlineMessageUpdated`). Nothing until the server's first page.
 */
internal fun threadOutlineItems(
    serverItems: List<ThreadOutlineItem>,
    serverLoaded: Boolean,
    leadingMessageId: String?,
    origin: RaftThreadMessageItem?,
    loadedMessages: List<RaftThreadMessageItem>,
): List<ThreadOutlineItem> {
    if (!serverLoaded) return emptyList()
    val retracted =
        (listOfNotNull(origin) + loadedMessages).filter { it.isRetracted }.mapTo(mutableSetOf()) { it.id }
    val leading = serverItems.firstOrNull { leadingMessageId != null && it.id == leadingMessageId }
    val rest = serverItems.filter { it.id != leadingMessageId }
    val restIds = rest.mapTo(mutableSetOf()) { it.id }
    val newestSeq = rest.maxOfOrNull { it.seq } ?: 0L
    val live =
        loadedMessages
            .filter { message ->
                message.id != leadingMessageId && message.id !in restIds &&
                    message.isThreadOutlineHumanMessage() && (message.seq ?: 0L) > newestSeq
            }
            .distinctBy { it.id }
            .map { it.toThreadOutlineItem() }
    return (listOfNotNull(leading) + (rest + live).sortedBy { it.seq }).filter { it.id !in retracted }
}

/** web `hasOutline`: at least [ThreadOutlineMinItems] people's messages, the first message not counted. */
internal fun threadOutlineHasOutline(items: List<ThreadOutlineItem>, leadingMessageId: String?): Boolean =
    items.count { it.id != leadingMessageId } >= ThreadOutlineMinItems

/**
 * Whether the rail shows: a wide window (the app's wide breakpoint, [RaftScale.Wide]; never a
 * phone), a list at least [ThreadOutlineMinListWidth] wide, and a thread with an outline.
 */
internal fun threadOutlineRailShown(scale: RaftScale, listWidth: Dp, hasOutline: Boolean): Boolean =
    scale == RaftScale.Wide && listWidth >= ThreadOutlineMinListWidth && hasOutline

/**
 * web `useOutlinePosition`: where the reader is. A jump's target while it is current; else the
 * thread's first message while it is the top row in view; else the last item at or before the
 * top message in view, by seq. -1 for none.
 */
internal fun threadOutlineCurrentIndex(
    items: List<ThreadOutlineItem>,
    leadingMessageId: String?,
    topMessageId: String?,
    topSeq: Long?,
    jumpedId: String?,
): Int {
    if (jumpedId != null) {
        val jumped = items.indexOfFirst { it.id == jumpedId }
        if (jumped >= 0) return jumped
    }
    if (topMessageId != null && topMessageId == leadingMessageId) {
        return if (items.firstOrNull()?.id == leadingMessageId) 0 else -1
    }
    if (topSeq == null) return -1
    var current = -1
    items.forEachIndexed { index, item ->
        if (item.id != leadingMessageId && item.seq <= topSeq) current = index
    }
    // Above the first reply in the outline, the thread's first message is still the place.
    if (current < 0 && items.firstOrNull()?.id == leadingMessageId) current = 0
    return current
}

/** A jump's target, current from the jump until it has been in view and left it (web). */
internal data class ThreadOutlineJump(val messageId: String, val landed: Boolean = false)

internal fun ThreadOutlineJump.afterView(visibleMessageIds: List<String>): ThreadOutlineJump? {
    val inView = messageId in visibleMessageIds
    return when {
        inView && !landed -> copy(landed = true)
        !inView && landed -> null
        else -> this
    }
}

// ---- Data ----

/** One thread's outline as read from the server (web messageOutlineStore), oldest first. */
@Stable
internal class ThreadOutlineState(
    private val api: RaftThreadOutlineApi,
    val channelId: String,
) {
    var serverItems by mutableStateOf<List<ThreadOutlineItem>>(emptyList())
        private set
    var hasMore by mutableStateOf(false)
        private set
    var loaded by mutableStateOf(false)
        private set
    private var newestRead = 0
    private var olderRead: Job? = null

    /** The newest page: on opening the thread and on coming back to it. A failure keeps what was there. */
    suspend fun loadNewest() {
        val read = ++newestRead
        val page = api.page(channelId) ?: return
        if (read != newestRead) return
        serverItems = page.items.reversed()
        hasMore = page.hasMore
        loaded = true
    }

    /** The next older page, unless one is on its way or there is none. */
    suspend fun loadOlder() {
        if (!hasMore || olderRead?.isActive == true) return
        val oldestSeq = serverItems.firstOrNull()?.seq ?: return
        val read = newestRead
        val page = api.page(channelId, beforeSeq = oldestSeq) ?: return
        // Not if the newest page was read again meanwhile: it starts over.
        if (read != newestRead || serverItems.firstOrNull()?.seq != oldestSeq) return
        serverItems = page.items.reversed() + serverItems
        hasMore = page.hasMore
    }

    /** web `pageOutlineBackTo`: pages back until the outline reaches [seq], at most 10 pages. */
    suspend fun pageBackTo(seq: Long) {
        repeat(ThreadOutlineMaxPagesBack) {
            val oldest = serverItems.firstOrNull()?.seq ?: return
            if (!hasMore || oldest <= seq) return
            loadOlder()
            if (serverItems.firstOrNull()?.seq == oldest) return
        }
    }
}

// ---- UI ----

/** What the list tells the outline about itself: the messages in view, top first. */
internal data class ThreadOutlineViewport(val visibleMessageIds: List<String>)

/**
 * The thread timeline beside a reply thread's message list. [channelId] is the thread's channel
 * (null: no outline). It reads [LocalThreadOutlineApi]; [viewport] is where the list is;
 * [jumpTargetId]/[jumpNonce] are the page's focus requests (a link, a search hit, this rail);
 * [onJump] focuses a message the way a search hit does. [listWidth] is the list's.
 */
@Composable
internal fun ThreadOutlineHost(
    channelId: String?,
    active: Boolean,
    origin: RaftThreadMessageItem?,
    messages: List<RaftThreadMessageItem>,
    viewport: () -> ThreadOutlineViewport,
    jumpTargetId: String?,
    jumpNonce: Int,
    listWidth: Dp,
    formatTime: (String) -> String,
    onJump: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val api = LocalThreadOutlineApi.current ?: return
    val threadChannelId = channelId?.takeIf { it.isNotBlank() } ?: return
    // Phones: nothing, not even the read (artin: wide layouts only).
    if (RaftTheme.scale != RaftScale.Wide) return
    val state = remember(api, threadChannelId) { ThreadOutlineState(api, threadChannelId) }
    LaunchedEffect(state, active) {
        if (active) state.loadNewest()
    }
    val leadingMessageId = origin?.id
    val items =
        remember(state.serverItems, state.loaded, leadingMessageId, origin?.deletedAt, messages) {
            threadOutlineItems(state.serverItems, state.loaded, leadingMessageId, origin, messages)
        }
    val shown = threadOutlineRailShown(RaftTheme.scale, listWidth, threadOutlineHasOutline(items, leadingMessageId))
    if (!shown) return
    val ownSenderIds =
        remember(origin, messages) { (listOfNotNull(origin) + messages).filter { it.isOwn }.mapTo(mutableSetOf()) { it.authorId } }
    ThreadOutlineRail(
        state = state,
        ownSenderIds = ownSenderIds,
        items = items,
        leadingMessageId = leadingMessageId,
        messages = messages,
        viewport = viewport,
        jumpTargetId = jumpTargetId,
        jumpNonce = jumpNonce,
        formatTime = formatTime,
        onJump = onJump,
        modifier = modifier,
    )
}

@Composable
internal fun ThreadOutlineRail(
    state: ThreadOutlineState,
    /** The reader's own sender ids: their ticks are darker (web `own`). */
    ownSenderIds: Set<String>,
    items: List<ThreadOutlineItem>,
    leadingMessageId: String?,
    messages: List<RaftThreadMessageItem>,
    viewport: () -> ThreadOutlineViewport,
    jumpTargetId: String?,
    jumpNonce: Int,
    formatTime: (String) -> String,
    onJump: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visibleIds by remember(viewport) { derivedStateOf { viewport().visibleMessageIds } }
    val topId = visibleIds.firstOrNull()
    val topSeq = remember(messages, topId) { messages.firstOrNull { it.id == topId }?.seq }
    // A timeline older than the outline pages the outline back to it.
    LaunchedEffect(state, topId == leadingMessageId, topSeq) {
        if (topId != null && topId == leadingMessageId) state.pageBackTo(0) else topSeq?.let { state.pageBackTo(it) }
    }
    var jump by remember(state) { mutableStateOf<ThreadOutlineJump?>(null) }
    // Every jump, wherever it came from (a link, a search hit, this rail).
    LaunchedEffect(jumpTargetId, jumpNonce) {
        jumpTargetId?.takeIf { it.isNotBlank() }?.let { jump = ThreadOutlineJump(it) }
    }
    LaunchedEffect(visibleIds) { jump = jump?.afterView(visibleIds) }
    val currentIndex = threadOutlineCurrentIndex(items, leadingMessageId, topId, topSeq, jump?.messageId)
    val currentId = items.getOrNull(currentIndex)?.id

    val finePointer = RaftTheme.pointerInput == RaftPointerInput.Fine
    var open by remember(state) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var timer by remember { mutableStateOf<Job?>(null) }
    var pointerInside by remember { mutableStateOf(false) }
    fun schedule(next: Boolean) {
        timer?.cancel()
        timer = scope.launch {
            delay(if (next) ThreadOutlineOpenDelayMs else ThreadOutlineCloseDelayMs)
            open = next
        }
    }
    val hover =
        Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull() ?: continue
                    if (change.type != PointerType.Mouse) continue
                    when (event.type) {
                        PointerEventType.Enter -> { pointerInside = true; schedule(true) }
                        PointerEventType.Exit -> { pointerInside = false; schedule(false) }
                    }
                }
            }
        }
    val jumpTo: (String) -> Unit = { messageId ->
        jump = ThreadOutlineJump(messageId)
        // A finger's card closes on the jump; a mouse's stays open while the pointer is on it (web).
        if (!finePointer || !pointerInside) open = false
        onJump(messageId)
    }
    // A finger's card is a transient overlay: Back closes it first.
    RaftBackHandler(enabled = open && !finePointer, label = "thread_outline_card", onBack = { open = false })

    val label = stringResource(Res.string.thread_outline_label)
    Box(modifier.fillMaxSize()) {
        if (open && !finePointer) {
            // Tap outside closes it, as a popover's does.
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitPointerEventScope {
                        awaitPointerEvent(PointerEventPass.Main)
                        open = false
                    }
                }
            )
        }
        Box(
            Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).then(hover),
            contentAlignment = Alignment.CenterEnd,
        ) {
            if (open) {
                ThreadOutlineCard(
                    state = state,
                    ownSenderIds = ownSenderIds,
                    items = items,
                    currentId = currentId,
                    jumpedId = jump?.messageId,
                    formatTime = formatTime,
                    onJump = jumpTo,
                )
            } else {
                ThreadOutlineTicks(
                    items = items,
                    ownSenderIds = ownSenderIds,
                    currentIndex = currentIndex,
                    finePointer = finePointer,
                    label = label,
                    onTickClick = { messageId -> if (finePointer) jumpTo(messageId) else open = true },
                    onRailTap = { open = true },
                )
            }
        }
    }
}

@Composable
private fun ThreadOutlineTicks(
    items: List<ThreadOutlineItem>,
    ownSenderIds: Set<String>,
    currentIndex: Int,
    finePointer: Boolean,
    label: String,
    onTickClick: (String) -> Unit,
    onRailTap: () -> Unit,
) {
    val railHeight = minOf(ThreadOutlineTickRow * items.size, ThreadOutlineRailMaxHeight)
    val railState = rememberLazyListState()
    val visibleTicks = (railHeight / ThreadOutlineTickRow).toInt().coerceAtLeast(1)
    // The rail follows the list: where the reader is stays in its middle (web).
    LaunchedEffect(currentIndex, items.size, visibleTicks) {
        if (currentIndex >= 0) railState.scrollToItem((currentIndex - visibleTicks / 2).coerceAtLeast(0))
    }
    LazyColumn(
        state = railState,
        modifier =
            Modifier
                .width(ThreadOutlineRailWidth)
                .height(railHeight)
                .testTag(ThreadOutlineRailTestTag)
                .semantics { contentDescription = label }
                // A finger: the whole rail is one target that opens the card (the ticks are 10dp).
                .then(if (finePointer) Modifier else Modifier.clickable(onClick = onRailTap)),
        userScrollEnabled = finePointer,
    ) {
        itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(ThreadOutlineTickRow)
                    .then(
                        if (finePointer) {
                            Modifier.hoverable(interaction).clickable(interactionSource = interaction, indication = null) {
                                onTickClick(item.id)
                            }
                        } else {
                            Modifier
                        }
                    ),
                contentAlignment = Alignment.CenterEnd,
            ) {
                ThreadOutlineTick(own = item.senderId in ownSenderIds, current = index == currentIndex, hovered = hovered)
            }
        }
    }
}

/**
 * web OutlineTick: 12 x 2dp, `foreground-muted` for the reader's own messages and `line-seam`
 * for others'; the current one 22 x 3dp in `foreground`; a hovered one 18dp in `foreground`.
 */
@Composable
private fun ThreadOutlineTick(own: Boolean, current: Boolean, hovered: Boolean = false) {
    val colors = RaftTheme.colors
    val (width, height, color) =
        when {
            current -> Triple(22.dp, 3.dp, colors.foreground)
            hovered -> Triple(18.dp, 2.dp, colors.foreground)
            own -> Triple(12.dp, 2.dp, colors.foregroundMuted)
            else -> Triple(12.dp, 2.dp, colors.lineSeam)
        }
    Box(Modifier.size(width = width, height = height).background(color, CircleShape))
}

@Composable
private fun threadOutlineItemText(item: ThreadOutlineItem): String =
    when {
        item.preview.isNotBlank() -> item.preview
        item.attachmentCount == 1 -> stringResource(Res.string.thread_outline_attachments_one)
        item.attachmentCount > 1 -> stringResource(Res.string.thread_outline_attachments_many, item.attachmentCount)
        else -> stringResource(Res.string.thread_outline_no_text)
    }

/**
 * web OutlineCard: a popover (300dp, 6dp padding) with a 30dp row per item, at most 10 in view,
 * the current one in the middle as it opens. A row is a popup item: the pointer's (or the
 * finger's) row fills `brand-soft`; the reader's place is in semibold `foreground` with its
 * tick long, no fill. Each row's tooltip has the rest of its text, its author and time.
 */
@Composable
private fun ThreadOutlineCard(
    state: ThreadOutlineState,
    ownSenderIds: Set<String>,
    items: List<ThreadOutlineItem>,
    currentId: String?,
    jumpedId: String?,
    formatTime: (String) -> String,
    onJump: (String) -> Unit,
) {
    val listState: LazyListState =
        rememberLazyListState(
            initialFirstVisibleItemIndex =
                ((items.indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: items.lastIndex) - ThreadOutlineCardMaxRows / 2)
                    .coerceAtLeast(0)
        )
    LaunchedEffect(jumpedId) {
        val index = items.indexOfFirst { it.id == jumpedId }
        if (index >= 0) listState.scrollToItem((index - ThreadOutlineCardMaxRows / 2).coerceAtLeast(0))
    }
    val latestState by rememberUpdatedState(state)
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex < ThreadOutlineLoadOlderWithinRows }
            .distinctUntilChanged()
            .collect { nearOldest -> if (nearOldest) latestState.loadOlder() }
    }
    RaftPopoverSurface(
        modifier = Modifier.testTag(ThreadOutlineCardTestTag),
        width = ThreadOutlineCardWidth,
        contentPadding = PaddingValues(6.dp),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().height(ThreadOutlineCardRow * minOf(items.size, ThreadOutlineCardMaxRows)),
        ) {
            itemsIndexed(items, key = { _, item -> item.id }) { _, item ->
                ThreadOutlineCardRow(
                    item = item,
                    text = threadOutlineItemText(item),
                    time = formatTime(item.createdAt),
                    own = item.senderId in ownSenderIds,
                    current = item.id == currentId,
                    onClick = { onJump(item.id) },
                )
            }
        }
    }
}

@Composable
private fun ThreadOutlineCardRow(
    item: ThreadOutlineItem,
    text: String,
    time: String,
    own: Boolean,
    current: Boolean,
    onClick: () -> Unit,
) {
    val colors = RaftTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val rowLabel = stringResource(Res.string.thread_outline_row_label, item.senderName, time, text)
    val tooltip = listOf(text, listOf(item.senderName, time).filter { it.isNotBlank() }.joinToString(" · ")).joinToString("\n")
    RaftTooltip(text = tooltip) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(ThreadOutlineCardRow)
                .padding(end = 6.dp)
                .background(
                    if (hovered || pressed) colors.brandSoft else colors.layerPopover,
                    RoundedCornerShape(RaftRadius.Control)
                )
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .testTag(threadOutlineRowTestTag(item.id))
                .semantics {
                    contentDescription = rowLabel
                    selected = current
                }
                .padding(start = 10.dp, end = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RaftText(
                text = text,
                modifier = Modifier.weight(1f),
                style = RaftTheme.typography.callout.let { if (current) it.copy(fontWeight = FontWeight.SemiBold) else it },
                color = if (current || hovered || pressed) colors.foreground else colors.foregroundMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ThreadOutlineTick(own = own, current = current, hovered = hovered)
        }
    }
}
