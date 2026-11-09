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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
// Mobile scope (artin 2026-11-08): the rail in wide windows only (the app's wide breakpoint, a
// tablet or the desktop app). A mouse or trackpad opens the card on hover, as the web does; a
// finger on a tablet taps the rail. Where the rail doesn't show (a phone, a list too narrow for
// it), the reply thread's ⋯ has Timeline, as the web's on touch: a half-height bottom sheet of
// the same rows (web OutlineList).

/** Fewer messages from people than this in a thread (its first message aside), and it has no outline. */
internal const val ThreadOutlineMinItems = 4

/** web RAIL_INSET: the room the rail takes, which message content keeps clear while it shows. */
internal val ThreadOutlineRailInset: Dp = 36.dp

/** The next step of a jump to the thread's first message (web `jumpToParent`). */
internal enum class ThreadOutlineOriginJumpStep { Idle, LoadOlder, Wait, Pin, GiveUp }

/**
 * Where a jump to the thread's first message is: none pending ([request] already [consumed]);
 * older pages still to load before its row is the list's first; one loading; the row there to
 * pin; or the load failed, which drops the jump.
 */
internal fun threadOutlineOriginJumpStep(
    request: Int,
    consumed: Int,
    canLoadMoreBefore: Boolean,
    olderMessagesStatus: String,
    originRowShown: Boolean,
): ThreadOutlineOriginJumpStep =
    when {
        request == 0 || request == consumed -> ThreadOutlineOriginJumpStep.Idle
        !canLoadMoreBefore && originRowShown -> ThreadOutlineOriginJumpStep.Pin
        olderMessagesStatus == "loading" -> ThreadOutlineOriginJumpStep.Wait
        canLoadMoreBefore && olderMessagesStatus == "error" -> ThreadOutlineOriginJumpStep.GiveUp
        canLoadMoreBefore -> ThreadOutlineOriginJumpStep.LoadOlder
        else -> ThreadOutlineOriginJumpStep.Wait
    }

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

/**
 * The reply thread's outline, shared by its header (the ⋯ menu's Timeline) and its message list
 * (the rail and the sheet); null on any other conversation or without [LocalThreadOutlineApi].
 */
internal val LocalThreadOutlineState = compositionLocalOf<ThreadOutlineState?> { null }

/** One [ThreadOutlineState] per thread channel, while [channelId] is a reply thread's (else null). */
@Composable
internal fun rememberThreadOutlineState(channelId: String?): ThreadOutlineState? {
    val api = LocalThreadOutlineApi.current ?: return null
    val id = channelId?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return remember(api, id) { ThreadOutlineState(api, id) }
}

/**
 * Whether the reply thread's ⋯ offers Timeline: the thread has an outline and the rail isn't
 * showing it beside the list (a phone, a narrow list, the desktop below the rail's width).
 */
internal fun threadOutlineMenuOffered(hasOutline: Boolean, railShown: Boolean): Boolean = hasOutline && !railShown

/**
 * The reply thread's ⋯ Timeline for its header: opens the sheet; null when the menu doesn't offer
 * it ([threadOutlineMenuOffered]) for the thread's [origin] and loaded [messages].
 */
internal fun ThreadOutlineState.openTimelineAction(
    leadingMessageId: String?,
    origin: RaftThreadMessageItem?,
    messages: List<RaftThreadMessageItem>,
): (() -> Unit)? {
    val items = threadOutlineItems(serverItems, loaded, leadingMessageId, origin, messages)
    if (!threadOutlineMenuOffered(threadOutlineHasOutline(items, leadingMessageId), railShown)) return null
    return { listOpen = true }
}

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

/**
 * The seq the outline must page back to for the top message in view, or null when it already
 * reaches it: the thread's first message on top needs the thread's start (0).
 */
internal fun threadOutlinePageBackTarget(hasMore: Boolean, oldestLoadedSeq: Long?, topIsLeading: Boolean, topSeq: Long?): Long? {
    if (!hasMore || oldestLoadedSeq == null) return null
    if (topIsLeading) return 0L
    return topSeq?.takeIf { it < oldestLoadedSeq }
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

    /** The rail shows beside the list (written by [ThreadOutlineHost]); the ⋯ then has no Timeline. */
    var railShown by mutableStateOf(false)
        internal set

    /** The sheet from the ⋯ menu's Timeline is open. */
    var listOpen by mutableStateOf(false)
    private var newestRead = 0
    /** One older read at a time (web `olderReads`): a second caller waits for it, then reads on from there. */
    private val olderReads = Mutex()

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
        if (!hasMore) return
        if (olderReads.isLocked) {
            // The read on its way answers this call too (web: settles with the read under way).
            olderReads.withLock { }
            return
        }
        olderReads.withLock {
            if (!hasMore) return
            val oldestSeq = serverItems.firstOrNull()?.seq ?: return
            val read = newestRead
            val page = api.page(channelId, beforeSeq = oldestSeq) ?: return
            // Not if the newest page was read again meanwhile: it starts over.
            if (read != newestRead || serverItems.firstOrNull()?.seq != oldestSeq) return
            serverItems = page.items.reversed() + serverItems
            hasMore = page.hasMore
        }
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
 * The thread timeline beside a reply thread's message list: the rail in a wide window, and the
 * sheet the ⋯ menu's Timeline opens anywhere else. [state] is the thread's
 * ([LocalThreadOutlineState]; null: no outline); [viewport] is where the list is;
 * [jumpTargetId]/[jumpNonce] are the page's focus requests (a link, a search hit, this outline);
 * [onJump] focuses a message the way a search hit does. [listWidth] is the list's.
 */
@Composable
internal fun ThreadOutlineHost(
    state: ThreadOutlineState?,
    /** The thread's parent message: the first item, known from the route before [origin] loads. */
    leadingMessageId: String?,
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
    state ?: return
    val items =
        remember(state.serverItems, state.loaded, leadingMessageId, origin?.deletedAt, messages) {
            threadOutlineItems(state.serverItems, state.loaded, leadingMessageId, origin, messages)
        }
    val hasOutline = threadOutlineHasOutline(items, leadingMessageId)
    val railShown = threadOutlineRailShown(RaftTheme.scale, listWidth, hasOutline)
    SideEffect { state.railShown = railShown }
    DisposableEffect(state) { onDispose { state.railShown = false } }
    // The sheet closes with the thread, and once the rail takes over (the window grew).
    if (state.listOpen && (!hasOutline || railShown)) SideEffect { state.listOpen = false }
    if (!railShown && !state.listOpen) return
    // The reader's own ticks are darker: by their user id, as the web compares with `currentUserId`.
    val currentUserId = LocalThreadActionCardSpecHost.current.viewerUserId
    val ownSenderIds = remember(currentUserId) { setOfNotNull(currentUserId?.takeIf { it.isNotBlank() }) }
    val position = rememberThreadOutlinePosition(state, items, leadingMessageId, messages, viewport, jumpTargetId, jumpNonce)
    if (railShown) {
        ThreadOutlineRail(
            state = state,
            ownSenderIds = ownSenderIds,
            items = items,
            position = position,
            formatTime = formatTime,
            onJump = onJump,
            modifier = modifier,
        )
    } else if (hasOutline) {
        ThreadOutlineSheet(
            state = state,
            ownSenderIds = ownSenderIds,
            items = items,
            currentId = items.getOrNull(position.currentIndex)?.id,
            formatTime = formatTime,
            onJump = { messageId ->
                position.noteJump(messageId)
                state.listOpen = false
                onJump(messageId)
            },
        )
    }
}

/** Where the reader is in [items] (web `useOutlinePosition`), shared by the rail and the sheet. */
@Stable
internal class ThreadOutlinePosition(
    val currentIndex: Int,
    val jumpedId: String?,
    val noteJump: (String) -> Unit,
)

@Composable
private fun rememberThreadOutlinePosition(
    state: ThreadOutlineState,
    items: List<ThreadOutlineItem>,
    leadingMessageId: String?,
    messages: List<RaftThreadMessageItem>,
    viewport: () -> ThreadOutlineViewport,
    jumpTargetId: String?,
    jumpNonce: Int,
): ThreadOutlinePosition {
    val visibleIds by remember(viewport) { derivedStateOf { viewport().visibleMessageIds } }
    val topId = visibleIds.firstOrNull()
    val topSeq = remember(messages, topId) { messages.firstOrNull { it.id == topId }?.seq }
    // A timeline older than the outline pages the outline back to it. Keyed on whether it is, not
    // on the top message itself: scrolling doesn't cancel and restart the read under way.
    val topIsLeading = topId != null && topId == leadingMessageId
    val pageBackTarget = threadOutlinePageBackTarget(state.hasMore, state.serverItems.firstOrNull()?.seq, topIsLeading, topSeq)
    val latestPageBackTarget by rememberUpdatedState(pageBackTarget)
    LaunchedEffect(state, pageBackTarget != null) {
        latestPageBackTarget?.let { state.pageBackTo(it) }
    }
    var jump by remember(state) { mutableStateOf<ThreadOutlineJump?>(null) }
    // Every jump, wherever it came from (a link, a search hit, this outline).
    LaunchedEffect(jumpTargetId, jumpNonce) {
        jumpTargetId?.takeIf { it.isNotBlank() }?.let { jump = ThreadOutlineJump(it) }
    }
    LaunchedEffect(visibleIds) { jump = jump?.afterView(visibleIds) }
    val currentIndex = threadOutlineCurrentIndex(items, leadingMessageId, topId, topSeq, jump?.messageId)
    return ThreadOutlinePosition(currentIndex, jump?.messageId) { messageId -> jump = ThreadOutlineJump(messageId) }
}

@Composable
internal fun ThreadOutlineRail(
    state: ThreadOutlineState,
    /** The reader's own sender ids: their ticks are darker (web `own`). */
    ownSenderIds: Set<String>,
    items: List<ThreadOutlineItem>,
    position: ThreadOutlinePosition,
    formatTime: (String) -> String,
    onJump: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentIndex = position.currentIndex
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
    // Shares the pointer with the list under it: a drag over the rail still scrolls the list (and
    // reveals times), and only a tap opens the card on a touch screen.
    val hover =
        Modifier.threadOutlinePointer(
            onHover = { inside ->
                pointerInside = inside
                schedule(inside)
            },
            onTap = if (finePointer) null else ({ open = true }),
            // Only the closed rail lets the list under it have the pointer too; the open card is a
            // popover of its own, over the layer that closes it on a press outside.
            shareWithSiblings = !open,
        )
    val jumpTo: (String) -> Unit = { messageId ->
        position.noteJump(messageId)
        // A finger's card closes on the jump; a mouse's stays open while the pointer is on it (web).
        if (!finePointer || !pointerInside) open = false
        onJump(messageId)
    }
    // A finger's card is a transient overlay: Back closes it first.
    RaftBackHandler(enabled = open && !finePointer, label = "thread_outline_card", onBack = { open = false })

    val label = stringResource(Res.string.thread_outline_label)
    Box(modifier.fillMaxSize()) {
        if (open && !finePointer) {
            // A press outside closes it, as a popover's does (not a stylus hovering or a mouse moving).
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            if (awaitPointerEvent(PointerEventPass.Main).type == PointerEventType.Press) open = false
                        }
                    }
                }
            )
        }
        val openLabel = stringResource(Res.string.thread_outline_list_title)
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 8.dp)
                .then(hover)
                // One stop for assistive tech and the keyboard: the rail is a button that opens the
                // card (web: the rail is the one Tab stop; its focus opens the card).
                .onFocusChanged { focus ->
                    if (finePointer && focus.hasFocus) open = true
                    else if (finePointer && !focus.hasFocus && !pointerInside) schedule(false)
                },
            contentAlignment = Alignment.CenterEnd,
        ) {
            if (open) {
                ThreadOutlineCard(
                    state = state,
                    ownSenderIds = ownSenderIds,
                    items = items,
                    currentId = currentId,
                    jumpedId = position.jumpedId,
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
                    onOpen = { open = true },
                    openLabel = openLabel,
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
    onOpen: () -> Unit,
    openLabel: String,
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
                // One button for assistive tech ("Thread timeline", opens the list); the ticks are
                // drawn only. A finger's tap reaches it through the rail's shared pointer input.
                .clearAndSetSemantics {
                    contentDescription = label
                    role = Role.Button
                    onClick(label = openLabel) {
                        onOpen()
                        true
                    }
                }
                .then(if (finePointer) Modifier.focusable() else Modifier),
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

internal const val ThreadOutlineSheetTestTag = "thread-outline-sheet"
internal fun threadOutlineSheetRowTestTag(messageId: String): String = "thread-outline-sheet-row:$messageId"

/** web SHEET_SNAP_POINTS[0]: the sheet opens at half the window, leaving the thread in sight. */
private const val ThreadOutlineSheetHeightFraction = 0.5f
/** The sheet's grab handle, title row and body padding above the rows. */
private val ThreadOutlineSheetChrome = 72.dp
private val ThreadOutlineSheetMinListHeight = 200.dp

/**
 * web OutlineList on a phone: a bottom sheet titled Timeline, half the window tall, one row per
 * item: its text in up to two lines, then who and when (a touch screen has no tooltip for the
 * rest), and the tick. It opens with the row before the current one at its top. A row jumps there
 * and closes the sheet.
 */
@Composable
private fun ThreadOutlineSheet(
    state: ThreadOutlineState,
    ownSenderIds: Set<String>,
    items: List<ThreadOutlineItem>,
    currentId: String?,
    formatTime: (String) -> String,
    onJump: (String) -> Unit,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val windowHeight = with(density) { androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height.toDp() }
    val listHeight = maxOf(windowHeight * ThreadOutlineSheetHeightFraction - ThreadOutlineSheetChrome, ThreadOutlineSheetMinListHeight)
    val listState =
        rememberLazyListState(
            initialFirstVisibleItemIndex =
                ((items.indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: items.lastIndex) - 1).coerceAtLeast(0)
        )
    val latestState by rememberUpdatedState(state)
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex < ThreadOutlineLoadOlderWithinRows }
            .distinctUntilChanged()
            .collect { nearOldest -> if (nearOldest) latestState.loadOlder() }
    }
    build.raft.designsystem.components.RaftSheet(
        onDismissRequest = { state.listOpen = false },
        title = stringResource(Res.string.thread_outline_list_title),
        modifier = Modifier.testTag(ThreadOutlineSheetTestTag),
        showClose = true,
    ) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().height(listHeight)) {
            itemsIndexed(items, key = { _, item -> item.id }) { _, item ->
                ThreadOutlineSheetRow(
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
private fun ThreadOutlineSheetRow(
    item: ThreadOutlineItem,
    text: String,
    time: String,
    own: Boolean,
    current: Boolean,
    onClick: () -> Unit,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val rowLabel = stringResource(Res.string.thread_outline_row_label, item.senderName, time, text)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .background(if (pressed) colors.brandSoft else colors.layerPopover, RoundedCornerShape(RaftRadius.Control))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .testTag(threadOutlineSheetRowTestTag(item.id))
            .semantics(mergeDescendants = true) {
                contentDescription = rowLabel
                selected = current
            }
            .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            RaftText(
                text = text,
                style = typography.callout.let { if (current) it.copy(fontWeight = FontWeight.SemiBold) else it },
                color = if (current || pressed) colors.foreground else colors.foregroundMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                Modifier.padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                RaftText(
                    text = item.senderName,
                    modifier = Modifier.weight(1f, fill = false),
                    style = typography.footnote,
                    color = colors.foregroundHint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                RaftText(text = time, style = typography.monoMeta, color = colors.foregroundHint, maxLines = 1)
            }
        }
        ThreadOutlineTick(own = own, current = current)
    }
}


/**
 * The rail's pointer input: a mouse entering and leaving ([onHover]) and, on a touch screen, a tap
 * ([onTap]: a press and release that didn't move past the touch slop). It consumes nothing and,
 * with [shareWithSiblings], shares the pointer with the siblings under it, so a drag that starts
 * on the rail still scrolls the message list (and drags its times in) instead of stopping there.
 */
private fun Modifier.threadOutlinePointer(
    onHover: (Boolean) -> Unit,
    onTap: (() -> Unit)?,
    shareWithSiblings: Boolean,
): Modifier = this then ThreadOutlinePointerElement(onHover, onTap, shareWithSiblings)

private data class ThreadOutlinePointerElement(
    val onHover: (Boolean) -> Unit,
    val onTap: (() -> Unit)?,
    val shareWithSiblings: Boolean,
) : ModifierNodeElement<ThreadOutlinePointerNode>() {
    override fun create() = ThreadOutlinePointerNode(onHover, onTap, shareWithSiblings)

    override fun update(node: ThreadOutlinePointerNode) {
        node.onHover = onHover
        node.onTap = onTap
        node.shareWithSiblings = shareWithSiblings
    }
}

private class ThreadOutlinePointerNode(
    var onHover: (Boolean) -> Unit,
    var onTap: (() -> Unit)?,
    var shareWithSiblings: Boolean,
) : Modifier.Node(), PointerInputModifierNode, CompositionLocalConsumerModifierNode {
    private var down: PointerId? = null
    private var downAt = Offset.Zero

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass != PointerEventPass.Main) return
        val change = pointerEvent.changes.firstOrNull() ?: return
        if (change.type == PointerType.Mouse) {
            when (pointerEvent.type) {
                PointerEventType.Enter -> onHover(true)
                PointerEventType.Exit -> onHover(false)
            }
            return
        }
        val tap = onTap ?: return
        when (pointerEvent.type) {
            PointerEventType.Press -> {
                down = change.id
                downAt = change.position
            }
            PointerEventType.Move ->
                if (down == change.id &&
                    (change.position - downAt).getDistance() > currentValueOf(LocalViewConfiguration).touchSlop
                ) {
                    down = null
                }
            PointerEventType.Release ->
                if (down == change.id) {
                    down = null
                    if (!change.isConsumed) tap()
                }
        }
    }

    override fun onCancelPointerInput() {
        down = null
    }

    override fun sharePointerInputWithSiblings(): Boolean = shareWithSiblings
}
