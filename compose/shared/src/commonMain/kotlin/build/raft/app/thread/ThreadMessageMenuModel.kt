package build.raft.app

import build.raft.app.resources.*
import build.raft.app.components.RaftMetrics
import build.raft.app.model.TaskStatus
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.channels.Channel

internal enum class ThreadMessageMenuVariant {
    Classic,
    Compact
}

internal fun threadMessageMenuVariant(
    newMessageMenuEnabled: Boolean,
    hasLocalSendState: Boolean
): ThreadMessageMenuVariant =
    if (newMessageMenuEnabled && !hasLocalSendState) {
        ThreadMessageMenuVariant.Compact
    } else {
        ThreadMessageMenuVariant.Classic
    }

internal enum class ThreadNewMessageMenuTool {
    Copy,
    Reply,
    Forward,
    Save,
    Translate,
    Select,
    AddReaction,
    CopyLink,
    FollowThread,
    TaskStatus,
    ConvertToTask,
    /** Opens the Select Text sheet (third row, first slot; Kirby, web #11467). */
    SelectText,
    /** botiverse/slock #11687: a followed reply thread's messages mark the whole thread unread. */
    MarkThreadUnread,
    /** In-place text selection context only: select the whole message body. */
    SelectAll,
    /**
     * A right-click menu opened while part of the message was selected: copy that text ("Copy",
     * leading the second row). Never in the long-press menu or the selection menu.
     */
    CopySelection
}

internal val ThreadNewMessageMenuToolOrder =
    listOf(
        ThreadNewMessageMenuTool.Copy,
        ThreadNewMessageMenuTool.Reply,
        ThreadNewMessageMenuTool.Forward,
        ThreadNewMessageMenuTool.Save,
        ThreadNewMessageMenuTool.Translate,
        ThreadNewMessageMenuTool.Select
    )

/**
 * The Compact menu for a partial in-place text selection: Select All leads, Copy ("Copy
 * selection") copies the selection; only the enabled tools (Select All, Copy, Reply) are shown.
 */
internal val ThreadNewMessageMenuSelectionToolOrder =
    listOf(
        ThreadNewMessageMenuTool.SelectAll,
        ThreadNewMessageMenuTool.Copy,
        ThreadNewMessageMenuTool.Reply,
        ThreadNewMessageMenuTool.Forward,
        ThreadNewMessageMenuTool.Save,
        ThreadNewMessageMenuTool.Translate
    )

/**
 * The Compact menu's second row of tools (its third row under the reactions): Select Text, always
 * shown (Kirby, web #11467) and first unless a right-click menu leads with Copy for the selected
 * text, then the remaining Classic actions, each shown only when it applies (the primary row keeps
 * its six slots and greys unavailable ones out instead).
 */
internal val ThreadNewMessageMenuSecondaryToolOrder =
    listOf(
        ThreadNewMessageMenuTool.CopySelection,
        ThreadNewMessageMenuTool.SelectText,
        ThreadNewMessageMenuTool.CopyLink,
        ThreadNewMessageMenuTool.FollowThread,
        ThreadNewMessageMenuTool.MarkThreadUnread,
        ThreadNewMessageMenuTool.TaskStatus,
        ThreadNewMessageMenuTool.ConvertToTask
    )

/** One message's Compact menu actions: reactions plus the tool callbacks. */
internal class ThreadNewMessageMenuMessageActions(
    val onReact: (String) -> Unit,
    val tools: ThreadNewMessageMenuToolCallbacks,
)

internal data class ThreadNewMessageMenuToolCallbacks(
    val onCopy: () -> Unit,
    val onReply: () -> Unit,
    val onForward: () -> Unit,
    val onSave: () -> Unit,
    val onTranslate: () -> Unit,
    val onSelect: () -> Unit,
    val onAddReaction: () -> Unit = {},
    val onCopyLink: () -> Unit = {},
    val onFollowThread: () -> Unit = {},
    val onTaskStatus: () -> Unit = {},
    val onConvertToTask: () -> Unit = {},
    val onSelectText: () -> Unit = {},
    val onSelectAll: () -> Unit = {},
    val onMarkThreadUnread: () -> Unit = {},
    val onCopySelection: () -> Unit = {}
)

internal fun ThreadNewMessageMenuTool.dispatch(callbacks: ThreadNewMessageMenuToolCallbacks) {
    when (this) {
        ThreadNewMessageMenuTool.Copy -> callbacks.onCopy()
        ThreadNewMessageMenuTool.Reply -> callbacks.onReply()
        ThreadNewMessageMenuTool.Forward -> callbacks.onForward()
        ThreadNewMessageMenuTool.Save -> callbacks.onSave()
        ThreadNewMessageMenuTool.Translate -> callbacks.onTranslate()
        ThreadNewMessageMenuTool.Select -> callbacks.onSelect()
        ThreadNewMessageMenuTool.AddReaction -> callbacks.onAddReaction()
        ThreadNewMessageMenuTool.CopyLink -> callbacks.onCopyLink()
        ThreadNewMessageMenuTool.FollowThread -> callbacks.onFollowThread()
        ThreadNewMessageMenuTool.TaskStatus -> callbacks.onTaskStatus()
        ThreadNewMessageMenuTool.ConvertToTask -> callbacks.onConvertToTask()
        ThreadNewMessageMenuTool.SelectText -> callbacks.onSelectText()
        ThreadNewMessageMenuTool.SelectAll -> callbacks.onSelectAll()
        ThreadNewMessageMenuTool.MarkThreadUnread -> callbacks.onMarkThreadUnread()
        ThreadNewMessageMenuTool.CopySelection -> callbacks.onCopySelection()
    }
}

internal data class ThreadNewMessageMenuActionAvailability(
    val reactions: Boolean = true,
    val copy: Boolean = true,
    val reply: Boolean = true,
    val forward: Boolean = true,
    val save: Boolean = true,
    val translate: Boolean = true,
    val select: Boolean = true,
    val more: Boolean = true,
    /** Save toggles to "Remove from Saved" like the Classic menu and web. */
    val saved: Boolean = false,
    /** Translation in flight: the slot reads "Translating…" and is disabled (web parity). */
    val translatePending: Boolean = false,
    /**
     * The reaction row ends with Add Reaction (web `reaction-add-any`, the full emoji picker). Not a
     * tool row entry: [ThreadNewMessageMenuTool.AddReaction] stays out of the tool orders.
     */
    val addReaction: Boolean = false,
    val copyLink: Boolean = false,
    val followThread: Boolean = false,
    val threadFollowed: Boolean = false,
    /** botiverse/slock #11687: "Mark Thread as Unread" (a followed reply thread's messages). */
    val markThreadUnread: Boolean = false,
    val taskStatus: Boolean = false,
    val taskDone: Boolean = false,
    val convertToTask: Boolean = false,
    /** Task v2: the task slot reads "Create Task from Message" (web) instead of "Convert to Task". */
    val createTaskFromMessage: Boolean = false,
    /** Shown for an in-place text selection: Copy copies the selection. */
    val selectionContext: Boolean = false,
    /** The in-place selection covers only part of the message: Copy reads "Copy selection" and Select All appears. */
    val partialSelection: Boolean = false,
    /** A right-click menu opened over selected text in the message: "Copy" copies that text. */
    val copySelection: Boolean = false,
    /** Select Text (the sheet for picking and quoting text); off for a cut Activity preview. */
    val selectText: Boolean = true,
    /**
     * A long press on a link opened the menu, so the link's own "Copy link" shows above: the
     * message's Copy Link reads "Copy Message Link" (artin, 1.25).
     */
    val linkSection: Boolean = false
) {
    fun enabled(tool: ThreadNewMessageMenuTool): Boolean =
        when (tool) {
            ThreadNewMessageMenuTool.Copy -> copy
            ThreadNewMessageMenuTool.Reply -> reply
            ThreadNewMessageMenuTool.Forward -> forward
            ThreadNewMessageMenuTool.Save -> save
            ThreadNewMessageMenuTool.Translate -> translate && !translatePending
            // In-place selection already covers picking text; the Select tool is hidden there.
            ThreadNewMessageMenuTool.Select -> select && !selectionContext
            ThreadNewMessageMenuTool.AddReaction -> addReaction
            ThreadNewMessageMenuTool.CopyLink -> copyLink
            ThreadNewMessageMenuTool.FollowThread -> followThread
            // A message's action on its thread, not on picked text.
            ThreadNewMessageMenuTool.MarkThreadUnread -> markThreadUnread && !selectionContext
            ThreadNewMessageMenuTool.TaskStatus -> taskStatus
            ThreadNewMessageMenuTool.ConvertToTask -> convertToTask
            // The sheet is for picking text; an in-place selection already does that.
            ThreadNewMessageMenuTool.SelectText -> selectText && !selectionContext
            ThreadNewMessageMenuTool.SelectAll -> selectionContext && partialSelection
            ThreadNewMessageMenuTool.CopySelection -> copySelection && !selectionContext
        }

    fun primaryTools(): List<ThreadNewMessageMenuTool> =
        when {
            // Part of the message selected: only the tools that act on the picked text.
            selectionContext && partialSelection -> ThreadNewMessageMenuSelectionToolOrder.filter(::enabled)
            // Whole message selected: the long-press row without the Select tool.
            selectionContext -> ThreadNewMessageMenuToolOrder - ThreadNewMessageMenuTool.Select
            else -> ThreadNewMessageMenuToolOrder
        }

    /** Reactions apply to the whole message, so a partial selection drops the reaction row. */
    fun showsReactionRow(): Boolean = !(selectionContext && partialSelection)

    fun secondaryTools(): List<ThreadNewMessageMenuTool> =
        ThreadNewMessageMenuSecondaryToolOrder.filter(::enabled)
}

internal fun ThreadMessageMenuCapabilities.toThreadNewMessageMenuActionAvailability(
    message: RaftThreadMessageItem,
    replyMetadata: ThreadReplyMetadata
): ThreadNewMessageMenuActionAvailability =
    ThreadNewMessageMenuActionAvailability(
        reactions = quickReactions,
        copy = copyText,
        selectText = copyText,
        reply = reply,
        forward = forward,
        save = save,
        translate = translate != ThreadMessageMenuTranslateState.Hidden,
        select = selectMessage,
        more = true,
        saved = message.isSaved,
        translatePending = translate == ThreadMessageMenuTranslateState.Pending,
        addReaction = addReaction,
        copyLink = copyLink,
        followThread = follow,
        threadFollowed = replyMetadata.isThreadFollowed,
        markThreadUnread = markThreadUnreadChannelId != null,
        taskStatus = taskStatus,
        taskDone = message.isThreadTaskDone(),
        convertToTask = convertToTask,
        createTaskFromMessage = createTaskFromMessage
    )

internal data class ThreadMessageMenuAction(
    val label: StringDesc,
    val icon: RaftLucideIconName,
    val group: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

// Web MessageItem context menu (raft-ui ContextMenuPopup + slock MenuItem): `min-w-60`
// popup, 36dp `min-h-9` rows with 16dp icons (MenuItem forces `size-4` in `foreground-muted`
// whatever size a caller passes; raft-design iconography.md: menus are 16px), and raft-ui's
// `MessageReactionQuickRow` (`gap-0.5 px-0.75`) of 36dp (`size-9`) buttons with 22px drawings
// -- with Add Reaction 8 * 36 + 7 * 2 + 2 * 3 = 308dp, which sets the menu's width (web
// `MESSAGE_CONTEXT_MENU_WIDTH`), and so how far left of the screen edge it opens.
private val ThreadMessageMenuMinWidth = 240.dp
private val ThreadMessageMenuRowHeight = 36.dp
internal val ThreadMessageMenuReactionRowHeight = 36.dp
internal val ThreadMessageMenuReactionButtonSize = 36.dp
internal val ThreadMessageMenuReactionGap = 2.dp
internal val ThreadMessageMenuReactionPaddingHorizontal = 3.dp
// Web places a touch context menu's top-left corner at the press point
// (`placeTouchMessageContextMenu`), clamped 8px inside the viewport.
internal val ThreadMessageMenuViewportGap = 8.dp

/**
 * [showAddReactionAction]: the reaction row ends with Add Reaction (web `reaction-add-any`), one
 * more button slot; the double-tap quick reaction bar has none.
 */
internal fun threadMessageMenuWidthDp(showReactionActions: Boolean, showAddReactionAction: Boolean = false): Float {
    if (!showReactionActions) return ThreadMessageMenuMinWidth.value
    val reactionCount = ThreadQuickReactionEmojis.size + if (showAddReactionAction) 1 else 0
    val reactionRowWidth =
        reactionCount * ThreadMessageMenuReactionButtonSize.value +
            (reactionCount - 1).coerceAtLeast(0) * ThreadMessageMenuReactionGap.value +
            ThreadMessageMenuReactionPaddingHorizontal.value * 2
    return maxOf(ThreadMessageMenuMinWidth.value, reactionRowWidth + RaftMetrics.PopupPadding.value * 2)
}

internal fun threadMessageMenuContentHeightDp(
    actionGroups: List<String>,
    showReactionActions: Boolean,
    /** A link section's actions ([ThreadMessageLinkMenuRows]); 0: no link section. */
    linkActionCount: Int = 0
): Float {
    // The popover chooses above/below before drawing its content. Feeding it a
    // generic first-frame estimate and replacing that with onGloballyPositioned
    // later makes an edge-adjacent menu visibly jump between placements. This
    // surface has fixed row/header/divider metrics, so compute the final height
    // up front and keep the first rendered position authoritative.
    // raft-ui menu (RaftUiMenuSurface): `p-1` popup inset on
    // both edges, a hairline separator with a `my-1` inset either side between groups and
    // under the reaction header; the seam ring is drawn over the content and the float
    // shadow takes no layout space.
    val effectiveRowCount = actionGroups.size.coerceAtLeast(1)
    val groupDividerCount =
        actionGroups.zipWithNext().count { (previous, next) -> previous != next }
    val separatorHeight = RaftAppStroke.Thin.value + RaftMetrics.MenuSeparatorInset.value * 2
    val reactionHeaderHeight =
        if (showReactionActions) {
            ThreadMessageMenuReactionRowHeight.value + separatorHeight
        } else {
            0f
        }
    // A link section leads the header: its address line, a row per action, and the separator
    // under it (before the reaction row, or the header's own when there is none).
    val linkSectionHeight =
        if (linkActionCount > 0) {
            ThreadMessageLinkMenuUrlRowHeight.value + linkActionCount * ThreadMessageMenuRowHeight.value + separatorHeight
        } else {
            0f
        }
    return effectiveRowCount * ThreadMessageMenuRowHeight.value +
        groupDividerCount * separatorHeight +
        reactionHeaderHeight +
        linkSectionHeight +
        RaftMetrics.PopupPadding.value * 2
}

internal fun shouldShowThreadFollowAction(
    message: RaftThreadMessageItem,
    replyMetadata: ThreadReplyMetadata
): Boolean =
    !message.hasLocalSendState() &&
        (replyMetadata.threadChannelId?.isNotBlank() == true ||
            replyMetadata.replyCount > 0 ||
            replyMetadata.isThreadFollowed)

/**
 * task #180 (artin): what the thread panel's follow row should be, derived from the thread's origin
 * message -- the same per-message state the long-press menu reads, so the two surfaces cannot
 * disagree (web reads `followedThreads` from its thread store; this is the mobile equivalent).
 *
 * Returns null when there is nothing to follow, in which case the row is ABSENT rather than shown
 * disabled: an empty thread or a message without a thread channel would otherwise offer an action
 * that cannot run.
 */
internal data class ThreadPanelFollowAction(
    val followed: Boolean,
    val threadChannelId: String,
    val action: String,
)

internal fun threadPanelFollowAction(message: RaftThreadMessageItem?): ThreadPanelFollowAction? {
    val threadChannelId = message?.threadChannelId?.takeIf { it.isNotBlank() } ?: return null
    return ThreadPanelFollowAction(
        followed = message.isThreadFollowed,
        threadChannelId = threadChannelId,
        // Reuses the two actions the page already dispatches, so the panel does not invent a
        // second code path for the same operation.
        action = if (message.isThreadFollowed) UNFOLLOW_THREAD_ACTION else FOLLOW_THREAD_ACTION
    )
}

/**
 * botiverse/slock #11687: the thread channel an open reply thread's Mark as Unread marks (its header
 * ⋯ and its messages' menus), or null: not a reply thread, no thread channel yet, or a thread the
 * person doesn't follow (its Activity row has no Mark as Unread either).
 */
internal fun threadPanelMarkUnreadChannelId(
    replyThreadSurface: Boolean,
    originReplyMetadata: ThreadReplyMetadata?,
): String? {
    if (!replyThreadSurface || originReplyMetadata?.isThreadFollowed != true) return null
    return originReplyMetadata.threadChannelId?.trim()?.takeIf { it.isNotBlank() }
}

/**
 * The thread facts of [this] message from the page's reply metadata. The timeline slice keeps them
 * out of its rows ([withoutThreadReplyMetadata]); the header's panel actions read them back here.
 */
internal fun RaftThreadMessageItem.withThreadReplyFacts(metadata: ThreadReplyMetadata): RaftThreadMessageItem =
    copy(
        threadChannelId = metadata.threadChannelId,
        replyCount = metadata.replyCount,
        unreadReplyCount = metadata.unreadReplyCount,
        firstUnreadReplyMessageId = metadata.firstUnreadReplyMessageId,
        isThreadFollowed = metadata.isThreadFollowed
    )

internal const val MARK_THREAD_UNREAD_ACTION = "markThreadUnread"
internal const val FOLLOW_THREAD_ACTION = "followMessageThread"
internal const val UNFOLLOW_THREAD_ACTION = "unfollowMessageThread"

/** raft-ui `FollowThreadIcon` / `UnfollowThreadIcon`, as web MessageItem's menu draws them. */
internal fun threadFollowMenuIcon(isFollowed: Boolean): RaftLucideIconName =
    if (isFollowed) RaftLucideIconName.UnfollowThread else RaftLucideIconName.FollowThread

/**
 * web MessageItem: Mark as Done is Lucide `square-check-big`, Reopen Task raft-ui `ReopenTaskIcon`
 * (raft-design iconography.md: reopen is not restart, so not `refresh-cw`/`rotate-ccw`).
 */
internal fun threadTaskStatusMenuIcon(isDone: Boolean): RaftLucideIconName =
    if (isDone) RaftLucideIconName.ReopenTask else RaftLucideIconName.SquareCheckBig

/**
 * What a message's long-press menu offers, shared by the Classic and Compact menus and aligned
 * with web MessageItem's context menu (botiverse/slock 563672da5):
 *  - quick reactions, and the reaction row's trailing Add Reaction (full picker): `!isSystem && canReact`
 *  - Select Message: not system, not a guest
 *  - Reply in Thread: `!hideThreadActions`; the menu also drops it once the message shows its
 *    thread ([threadMessageMenuShowsReplyInThread]), the hover toolbar keeps it
 *  - Forward: `showForwardAction` (non-guest, source channel) and `getForwardableMessages`
 *  - Follow/Unfollow: `canShowThreadFollowAction && hasThreadConversation`
 *  - Mark as Done/Reopen and Convert to Task: never for external senders
 * Mobile-only: Select Text, and Delete/Cancel for local sends.
 */
internal data class ThreadMessageMenuCapabilities(
    val quickReactions: Boolean,
    val addReaction: Boolean,
    val copyLink: Boolean,
    val selectMessage: Boolean,
    val translate: ThreadMessageMenuTranslateState,
    val reply: Boolean,
    val forward: Boolean,
    val save: Boolean,
    val follow: Boolean,
    val taskStatus: Boolean,
    val convertToTask: Boolean,
    val cancelLocalSend: Boolean,
    val deleteLocalSend: Boolean,
    /** Task v2: [convertToTask] is "Create Task from Message" (web CreateTaskDialog), not the legacy convert. */
    val createTaskFromMessage: Boolean = false,
    /**
     * botiverse/slock #11687 / #16225: "Mark thread as unread" on a followed reply thread's messages
     * or on a list message whose thread is followed; the thread channel it marks, else null.
     */
    val markThreadUnreadChannelId: String? = null,
    /**
     * Copy, Select Text (and its quote) and a selection's Copy: off while the body is only a cut
     * Activity preview ([hasActivityCutBody]), so the cut text is never copied or quoted as the message.
     */
    val copyText: Boolean = true
)

/**
 * botiverse/slock #16225 (web useMessageMenuMarkUnread): a channel's or DM's own message list, not
 * a thread (a reply thread or a task conversation, which web shows in its thread panel, where the
 * menu acts on the thread instead).
 */
private fun ThreadTimelineSlice.isConversationList(): Boolean =
    (surfaceKind == RaftThreadSurfaceKind.Channel || surfaceKind == RaftThreadSurfaceKind.DirectMessage) &&
        channelKind.normalizedThreadChannelKind() != RaftTaskConversationChannelKind

/**
 * The thread a message's "Mark thread as unread" marks: in an open reply thread, that thread
 * ([threadPanelMarkUnreadChannelId], #11687); in a channel's or DM's list, the message's own
 * thread when the person follows it (#14222 / #16225); else none.
 *
 * The conversation's own Mark as unread lives on the Home/sidebar row menu, not in the message menu
 * (menu order v4, botiverse/slock #16225).
 */
internal fun threadMessageMarkThreadUnreadChannelId(
    slice: ThreadTimelineSlice,
    replyMetadata: ThreadReplyMetadata,
    originReplyMetadata: ThreadReplyMetadata?,
): String? {
    threadPanelMarkUnreadChannelId(slice.replyThreadSurface, originReplyMetadata)?.let { return it }
    if (!slice.isConversationList() || !replyMetadata.isThreadFollowed) return null
    return replyMetadata.threadChannelId?.trim()?.takeIf { it.isNotBlank() }
}

internal fun threadMessageMenuCapabilities(
    message: RaftThreadMessageItem,
    replyMetadata: ThreadReplyMetadata,
    slice: ThreadTimelineSlice,
    writeActionsEnabled: Boolean,
    translate: ThreadMessageMenuTranslateState = ThreadMessageMenuTranslateState.Hidden,
    /** The open reply thread's origin reply facts (its thread channel, followed), for Mark Thread as Unread. */
    originReplyMetadata: ThreadReplyMetadata? = null
): ThreadMessageMenuCapabilities {
    val localSend = message.hasLocalSendState()
    val system = message.isSystemMessage()
    val external = message.externalAuthor != null
    // The same gate decides whether a row's double tap shows the quick reaction bar.
    val canReact = threadMessageCanQuickReact(message, slice, writeActionsEnabled)
    // Web hides thread actions on rows inside the thread panel but still offers Follow on the
    // panel's parent (`showThreadFollowAction`); mobile's origin message is that parent.
    val threadFollowSurface =
        slice.showReplyActions || slice.originMessage?.id == message.id
    // A cut Activity preview stands in for a message whose text has not loaded: nothing may copy,
    // quote, translate, forward or make a task of the cut text until the full message replaces it.
    val cutBody = message.hasActivityCutBody()
    return ThreadMessageMenuCapabilities(
        quickReactions = canReact,
        // Web's quick-reaction row ends with a SmilePlus "Add Reaction" (`reaction-add-any`) that
        // opens the full emoji picker; it is the menu's only way there (the "+" beside a message's
        // reactions opens the quick reaction bar). It replaced the old "no Add reaction row" rule
        // (artin, 2026-10-21), which left the picker unreachable (Lagoon smoke, 2026-10-29).
        addReaction = canReact,
        copyLink = !localSend,
        selectMessage = !localSend && !system && !slice.viewerIsGuest && !cutBody,
        translate = if (localSend || cutBody) ThreadMessageMenuTranslateState.Hidden else translate,
        reply = slice.showReplyActions && !localSend,
        forward =
            writeActionsEnabled &&
                slice.showForwardAction &&
                !system &&
                !cutBody &&
                message.isForwardableOrdinaryChatMessage(),
        save = slice.showMessageSaveAction && !localSend,
        follow =
            writeActionsEnabled &&
                !localSend &&
                threadFollowSurface &&
                shouldShowThreadFollowAction(message, replyMetadata),
        // Task v2 (web MessageItem menu): a message's tasks are its list, never a single linked task,
        // so there is no Mark as Done / Reopen here, and "Create Task from Message" replaces "Convert
        // to Task" — offered on a message that already has tasks too.
        taskStatus = false,
        convertToTask =
            slice.canConvertMessagesToTasks &&
                !external &&
                !cutBody &&
                message.canCreateTaskFromMessage(),
        cancelLocalSend = writeActionsEnabled && message.isSendingLocalSend(),
        deleteLocalSend = writeActionsEnabled && message.isFailedLocalSend(),
        createTaskFromMessage = true,
        markThreadUnreadChannelId =
            if (localSend) null else threadMessageMarkThreadUnreadChannelId(slice, replyMetadata, originReplyMetadata),
        copyText = !cutBody
    )
}

/**
 * botiverse/slock #15524 (web MessageItem `showMenuReplyInThread`): the message menu (long press,
 * right click, the hover toolbar's More) offers Reply in Thread only while the message does not
 * show its thread. Its inline replies, or else its replies badge (replies or a reply draft), open
 * that thread already; both are read here as the message's row draws them. Where the menu never
 * offers it ([ThreadMessageMenuCapabilities.reply]: a reply thread, on a phone's thread page or in a
 * wide layout's side panel, or a task conversation), it stays out. The hover toolbar keeps its
 * Reply button ([ThreadMessageHoverToolbarActions.reply]).
 */
internal fun threadMessageMenuShowsReplyInThread(
    capabilities: ThreadMessageMenuCapabilities,
    message: RaftThreadMessageItem,
    replyMetadata: ThreadReplyMetadata,
    slice: ThreadTimelineSlice,
    hasReplyDraft: Boolean,
): Boolean {
    if (!capabilities.reply) return false
    val inlineReplies =
        threadInlineReplyPresentationForMessage(
            model = message.inlineReplies,
            replyMetadata = replyMetadata,
            hasDraft = hasReplyDraft,
            showReplyAction = slice.showReplyActions,
        )
    val repliesBadge =
        threadRepliesBadgeSpec(
            replyMetadata = replyMetadata,
            hasDraft = hasReplyDraft,
            showReplyAction = slice.showReplyActions,
            hasInlineReplies = inlineReplies != null,
        )
    return inlineReplies == null && repliesBadge == null
}

/**
 * The video a long press on [message] can download: its one sent video attachment. A message with
 * several videos (or none) has no menu Download; each video still downloads from its preview.
 */
internal fun threadMessageMenuDownloadVideoAttachmentId(message: RaftThreadMessageItem): String? {
    if (message.hasLocalSendState()) return null
    return message.attachments
        .filter { it.id.isNotBlank() && it.isPreviewableVideoAttachment() }
        .singleOrNull()
        ?.id
}

internal fun RaftThreadMessageItem.isThreadTaskDone(): Boolean =
    taskStatus?.trim()?.lowercase() in setOf("done", "closed")

internal fun threadMessageMenuActions(
    message: RaftThreadMessageItem,
    replyMetadata: ThreadReplyMetadata = message.toThreadReplyMetadata(),
    showReplyAction: Boolean,
    showSaveAction: Boolean,
    showFollowAction: Boolean = true,
    showTaskStatusAction: Boolean,
    showConvertToTaskAction: Boolean,
    showCopyLinkAction: Boolean,
    showShareMessagesAction: Boolean,
    showCancelAction: Boolean,
    showDeleteAction: Boolean,
    showTranslateAction: Boolean = false,
    translatePending: Boolean = false,
    showForwardAction: Boolean = false,
    /** Task v2: the task item is "Create Task from Message". */
    createTaskFromMessage: Boolean = false,
    /** botiverse/slock #11687 / #16225: "Mark thread as unread", last of the thread group. */
    showMarkThreadUnreadAction: Boolean = false,
    /** web MessageItem "Quote" (menu order v4, #16225): first in the take-it-elsewhere group. */
    showQuoteAction: Boolean = false,
    /**
     * A right-click menu opened over selected text in the message: "Copy" copies it, first in the
     * copy group (web: the browser's own Copy). Null otherwise (long press, nothing selected).
     */
    onCopySelection: (() -> Unit)? = null,
    /** A long press on a link opened the menu (its section on top): Copy Link reads "Copy Message Link". */
    linkSection: Boolean = false,
    onReply: () -> Unit,
    onCopyLink: () -> Unit,
    onCopy: () -> Unit,
    onSelectText: () -> Unit,
    onShareMessages: () -> Unit,
    onSave: () -> Unit,
    onFollowThread: () -> Unit,
    onUnfollowThread: (String) -> Unit,
    onTaskStatusChange: (String) -> Unit,
    onConvertToTask: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onTranslate: () -> Unit = {},
    onForward: () -> Unit = {},
    onMarkThreadUnread: () -> Unit = {},
    /** Downloads the message's video ([threadMessageMenuDownloadVideoAttachmentId]); null hides it. */
    onDownloadVideo: (() -> Unit)? = null,
    onQuote: () -> Unit = {}
): List<ThreadMessageMenuAction> =
    // Add Reaction is not a row: it ends the reaction header ([ThreadMessageMenuChrome]).
    // web MessageItem's sections (menu order v4, botiverse/slock #16225, cindyz 2026-11-22):
    // 1. Reactions (header above)
    // 2. Thread: Reply in thread, Follow/Unfollow thread, Mark thread as unread
    // 3. Task (own group): Mark as done / Reopen, or Create task from message
    // 4. Take it elsewhere: Quote, Copy Markdown, Copy link, Forward, Select text, Select message (+ mobile Download)
    // 5. Just for me: Save, Translate (Mark channel/DM as unread dropped — lives on Home/sidebar row menu)
    // 6. Local send destructive actions (mobile-only): Delete, Cancel
    buildList {
        // A cut Activity preview is not the message's text: nothing copies or quotes it.
        val copyText = !message.hasActivityCutBody()
        // A right-click over selected text leads with Copy for it (artin, 2026-10-29), before
        // the sections (web: the browser's own Copy).
        if (onCopySelection != null && copyText) {
            add(ThreadMessageMenuAction(Res.string.common_copy.desc(), RaftLucideIconName.Copy, "copy", onClick = onCopySelection))
        }
        if (showReplyAction) {
            add(ThreadMessageMenuAction(Res.string.thread_action_reply_in_thread.desc(), RaftLucideIconName.Thread, "thread", onClick = onReply))
        }
        val followThreadChannelId = replyMetadata.threadChannelId?.takeIf { it.isNotBlank() }
        if (showFollowAction && shouldShowThreadFollowAction(message, replyMetadata)) {
            add(
                ThreadMessageMenuAction(
                    label =
                        if (replyMetadata.isThreadFollowed) {
                            Res.string.thread_action_unfollow_thread.desc()
                        } else {
                            Res.string.thread_action_follow_thread.desc()
                        },
                    icon = threadFollowMenuIcon(replyMetadata.isThreadFollowed),
                    group = "thread",
                    onClick = {
                        if (replyMetadata.isThreadFollowed && followThreadChannelId != null) {
                            onUnfollowThread(followThreadChannelId)
                        } else {
                            onFollowThread()
                        }
                    }
                )
            )
        }
        if (showMarkThreadUnreadAction) {
            add(
                ThreadMessageMenuAction(
                    label = Res.string.thread_action_mark_thread_unread.desc(),
                    icon = RaftLucideIconName.MessageSquareDot,
                    group = "thread",
                    onClick = onMarkThreadUnread
                )
            )
        }
        message.taskId?.takeIf { showTaskStatusAction && it.isNotBlank() }?.let {
            val isDone = message.isThreadTaskDone()
            add(
                ThreadMessageMenuAction(
                    label =
                        if (isDone) {
                            Res.string.thread_action_reopen_task.desc()
                        } else {
                            Res.string.thread_action_mark_done.desc()
                        },
                    icon = threadTaskStatusMenuIcon(isDone),
                    group = "task",
                    onClick = { onTaskStatusChange(if (isDone) "reopenTask" else "markTaskDone") }
                )
            )
        }
        if (showConvertToTaskAction && (createTaskFromMessage || message.canConvertToTask())) {
            add(
                ThreadMessageMenuAction(
                    label =
                        if (createTaskFromMessage) {
                            Res.string.thread_action_create_task_from_message.desc()
                        } else {
                            Res.string.thread_action_convert_to_task.desc()
                        },
                    icon = RaftLucideIconName.Plus,
                    group = "task",
                    onClick = onConvertToTask
                )
            )
        }
        if (showQuoteAction && copyText) {
            add(ThreadMessageMenuAction(Res.string.thread_action_quote.desc(), RaftLucideIconName.TextQuote, "copy", onClick = onQuote))
        }
        add(ThreadMessageMenuAction(Res.string.thread_action_copy_markdown.desc(), RaftLucideIconName.Copy, "copy", enabled = copyText, onClick = onCopy))
        if (showCopyLinkAction) {
            add(
                ThreadMessageMenuAction(
                    label = threadMessageCopyLinkLabel(linkSection).desc(),
                    icon = RaftLucideIconName.Link,
                    group = "copy",
                    onClick = onCopyLink
                )
            )
        }
        if (showForwardAction) {
            // Web uses the select-mode Forward glyph (lucide forward) for the per-message entry.
            add(ThreadMessageMenuAction(Res.string.thread_action_forward.desc(), RaftLucideIconName.Forward, "copy", onClick = onForward))
        }
        add(ThreadMessageMenuAction(Res.string.thread_action_select_text.desc(), RaftLucideIconName.SquareDashedText, "copy", enabled = copyText, onClick = onSelectText))
        if (showShareMessagesAction) {
            add(
                ThreadMessageMenuAction(
                    label = Res.string.thread_action_select_message.desc(),
                    // web: ListChecks (SquareCheckBig is Mark as Done in the same menu).
                    icon = RaftLucideIconName.ListChecks,
                    group = "copy",
                    onClick = onShareMessages
                )
            )
        }
        if (onDownloadVideo != null) {
            // Telegram / WhatsApp / iMessage: a long press on a video message offers to save it.
            add(
                ThreadMessageMenuAction(
                    label = Res.string.file_preview_action_download.desc(),
                    icon = RaftLucideIconName.Download,
                    group = "copy",
                    onClick = onDownloadVideo
                )
            )
        }
        if (showSaveAction) {
            add(
                ThreadMessageMenuAction(
                    label =
                        if (message.isSaved) {
                            Res.string.thread_action_remove_from_saved.desc()
                        } else {
                            Res.string.thread_action_save_message.desc()
                        },
                    icon = if (message.isSaved) RaftLucideIconName.BookmarkMinus else RaftLucideIconName.Bookmark,
                    group = "personal",
                    onClick = onSave
                )
            )
        }
        if (showTranslateAction) {
            add(
                ThreadMessageMenuAction(
                    label =
                        if (translatePending) {
                            Res.string.translation_state_in_progress.desc()
                        } else {
                            Res.string.thread_action_translate.desc()
                        },
                    icon = RaftLucideIconName.Languages,
                    group = "personal",
                    enabled = !translatePending,
                    onClick = onTranslate
                )
            )
        }
        if (showDeleteAction) {
            add(
                ThreadMessageMenuAction(
                    label = Res.string.common_delete.desc(),
                    icon = RaftLucideIconName.Trash,
                    group = "destructive",
                    onClick = onDelete
                )
            )
        }
        if (showCancelAction) {
            add(
                ThreadMessageMenuAction(
                    label = Res.string.common_cancel.desc(),
                    icon = RaftLucideIconName.X,
                    group = "destructive",
                    onClick = onCancel
                )
            )
        }
    }
