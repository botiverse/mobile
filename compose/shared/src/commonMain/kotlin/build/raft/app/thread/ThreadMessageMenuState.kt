package build.raft.app.thread

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import build.raft.app.RaftPopoverAnchor

/**
 * The message list's open menus and popovers: one per thread open, remembered by
 * `ThreadMessagesListContent` under its thread instance key, so a new thread starts with all closed.
 * Each property is its own snapshot state, read and written where it was as a local.
 */
@Stable
internal class ThreadMessageMenuState {
    var actionMessageId by mutableStateOf<String?>(null)
    var actionMenuAnchor by mutableStateOf<RaftPopoverAnchor?>(null)
    // A double tap's quick reaction bar (stdrc, 2026-10-29, #proj-raft-ui:911ccc08).
    var quickReactionBar by mutableStateOf<ThreadQuickReactionBarRequest?>(null)
    // The message row whose hover toolbar shows (web MessageHoverToolbar): drawn over its neighbours.
    var hoverToolbarMessageId by mutableStateOf<String?>(null)
    var classicMenuOverrideMessageId by mutableStateOf<String?>(null)
    // Product analytics: the open menu came from a long press (not the reaction "+" chip), so its
    // chosen items are `message_action_selected` with surface long_press.
    var actionMenuFromLongPress by mutableStateOf(false)
    var authorDescriptionPopover by mutableStateOf<ThreadAuthorDescriptionPopoverRequest?>(null)
    var taskStatusMenuMessageId by mutableStateOf<String?>(null)
    // A move into Backlog picked from a message's task menu: its reason is asked first (web TaskBacklogReasonPopover).
    var taskBacklogReasonTarget by mutableStateOf<ThreadTaskBacklogReasonTarget?>(null)
    // Add Reaction: the message whose full emoji picker is open (web hover-toolbar picker).
    var reactionPickerMessageId by mutableStateOf<String?>(null)
    // Where the message menu was, kept for the picker: on wide screens it opens there, like web's.
    var reactionPickerAnchor by mutableStateOf<RaftPopoverAnchor?>(null)
    var taskStatusMenuAnchor by mutableStateOf<RaftPopoverAnchor?>(null)
}
