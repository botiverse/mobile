package build.raft.app

import build.raft.app.data.RaftConversationHeaderAction
import build.raft.app.data.RaftConversationHeaderKind
import build.raft.app.data.RaftConversationHeaderSurface
import build.raft.app.data.RaftProductEvents
import build.raft.designsystem.components.RaftSideAction

/**
 * Pure event-mapping helpers for `conversation_header_action_selected`
 * (`botiverse/slock` #16056 + #16067, `#proj-mobile:7e5fdb23`).
 *
 * Task conversations are excluded on both web and native apps.
 */
internal fun threadHeaderConversationKind(
    surfaceKind: String,
    taskConversation: Boolean,
): RaftConversationHeaderKind? =
    if (taskConversation) {
        null
    } else {
        when (surfaceKind) {
            RaftThreadSurfaceKind.Channel -> RaftConversationHeaderKind.Channel
            RaftThreadSurfaceKind.DirectMessage -> RaftConversationHeaderKind.Dm
            RaftThreadSurfaceKind.ReplyThread -> RaftConversationHeaderKind.Thread
            else -> null
        }
    }

/**
 * Maps a conversation header own button or folded window action id to its
 * `conversation_header_action_selected` action, or null for entries owned by another event
 * (Tasks / Files side-card toggles stay `channel_tab_selected`).
 */
internal fun threadHeaderOwnActionProductEvent(
    actionId: String,
    settingsOpen: Boolean,
    focusModeActive: Boolean,
): RaftConversationHeaderAction? =
    when (actionId) {
        "thread-search" -> RaftConversationHeaderAction.Search
        "lounge" -> RaftConversationHeaderAction.OpenLounge
        "thread-settings" ->
            if (settingsOpen) RaftConversationHeaderAction.CloseDetails
            else RaftConversationHeaderAction.OpenDetails
        "thread-open-in-channel" -> RaftConversationHeaderAction.ViewInChannel
        FOCUS_MODE_MENU_ITEM_ID ->
            if (focusModeActive) RaftConversationHeaderAction.Restore
            else RaftConversationHeaderAction.Expand
        "thread-pane-close" -> RaftConversationHeaderAction.Close
        else -> null
    }

/**
 * Maps a reply-thread overflow menu item id to its `conversation_header_action_selected` action.
 * Returns null for task conversations, notification level openers, delete-task, and folded actions
 * (folded actions track through [threadHeaderOwnActionProductEvent]).
 */
internal fun threadHeaderOverflowMenuProductEvent(
    actionId: String,
    followed: Boolean?,
    taskConversation: Boolean,
): RaftConversationHeaderAction? =
    if (taskConversation) {
        null
    } else {
        when (actionId) {
            FOLLOW_THREAD_MENU_ITEM_ID ->
                when (followed) {
                    true -> RaftConversationHeaderAction.Unfollow
                    false -> RaftConversationHeaderAction.Follow
                    null -> null
                }
            MARK_THREAD_UNREAD_MENU_ITEM_ID -> RaftConversationHeaderAction.MarkUnread
            SEARCH_IN_TASK_MENU_ITEM_ID -> RaftConversationHeaderAction.Search
            THREAD_TIMELINE_MENU_ITEM_ID -> RaftConversationHeaderAction.OpenTimeline
            VIEW_TASK_IN_CHANNEL_MENU_ITEM_ID -> RaftConversationHeaderAction.ViewInChannel
            else -> null
        }
    }

/**
 * Maps tapping the conversation header title:
 * - reply thread: `scroll_to_start`
 * - channel (not Lounge): `close_details` when details are open beside it, else `open_details`
 */
internal fun threadHeaderTitleClickProductEvent(
    surfaceKind: String,
    taskConversation: Boolean,
    isLounge: Boolean,
    settingsOpen: Boolean,
): RaftConversationHeaderAction? =
    if (taskConversation) {
        null
    } else {
        when (surfaceKind) {
            RaftThreadSurfaceKind.ReplyThread -> RaftConversationHeaderAction.ScrollToStart
            RaftThreadSurfaceKind.Channel ->
                if (isLounge) {
                    null
                } else if (settingsOpen) {
                    RaftConversationHeaderAction.CloseDetails
                } else {
                    RaftConversationHeaderAction.OpenDetails
                }
            else -> null
        }
    }

/**
 * Wide-layout reply-thread Back closes the thread column (`close`); phone Back and nested Back
 * inside a side pane stay unrecorded.
 */
internal fun threadHeaderBackProductEvent(
    surfaceKind: String,
    taskConversation: Boolean,
    wideLayout: Boolean,
    inSidePane: Boolean = false,
): RaftConversationHeaderAction? =
    if (!taskConversation && wideLayout && !inSidePane && surfaceKind == RaftThreadSurfaceKind.ReplyThread) {
        RaftConversationHeaderAction.Close
    } else {
        null
    }

/**
 * Wraps Duo side-column and iOS native navigation bar actions so tracked entries emit
 * `conversation_header_action_selected` with `surface = header` (the system decides what folds
 * into its own `⋯`, per Nova #proj-mobile:7e5fdb23).
 */
internal fun wrapThreadHeaderSideColumnActionsForAnalytics(
    actions: List<RaftSideAction>,
    conversation: RaftConversationHeaderKind?,
    compact: Boolean,
    settingsOpen: Boolean,
    focusModeActive: Boolean,
    threadFollowed: Boolean?,
    taskConversation: Boolean,
): List<RaftSideAction> {
    if (conversation == null) return actions
    return actions.map { action ->
        val eventAction =
            threadHeaderOwnActionProductEvent(
                actionId = action.id,
                settingsOpen = settingsOpen,
                focusModeActive = focusModeActive,
            ) ?: threadHeaderOverflowMenuProductEvent(
                actionId = action.id,
                followed = threadFollowed,
                taskConversation = taskConversation,
            )
        if (eventAction == null) {
            action
        } else {
            val delegate = action.onClick
            action.copy(
                onClick = {
                    RaftProductEvents.conversationHeaderActionSelected(
                        action = eventAction,
                        surface = RaftConversationHeaderSurface.Header,
                        conversation = conversation,
                        compact = compact,
                    )
                    delegate()
                }
            )
        }
    }
}
