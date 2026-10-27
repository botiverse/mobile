package build.raft.app

import build.raft.app.data.RaftActivityItemKind
import build.raft.app.data.RaftChannelTab
import build.raft.app.data.RaftMessageAction
import build.raft.app.model.InboxItemKind

/*
 * The app's entry points mapped to the registry's entry-level events (`message_action_selected`,
 * `activity_item_open`, `channel_tab_selected`). A null result means the registry has no value for
 * that item and nothing is sent. Only identity enums leave this file: no ids and no text.
 */

/**
 * The long-press menu's Compact tool as `message_action_selected.action`. [isSaved], [isThreadFollowed]
 * and [isTaskDone] are the message's state before the tap (the tool toggles). Select All belongs to the
 * in-place text selection menu, which is not the long-press menu, so it has no value.
 */
internal fun ThreadNewMessageMenuTool.productMessageAction(
    isSaved: Boolean,
    isThreadFollowed: Boolean,
    isTaskDone: Boolean,
): RaftMessageAction? =
    when (this) {
        // "copyMessage" copies the message body as written (Markdown source), not the rendered text.
        ThreadNewMessageMenuTool.Copy -> RaftMessageAction.CopyMarkdown
        ThreadNewMessageMenuTool.Reply -> RaftMessageAction.ReplyInThread
        ThreadNewMessageMenuTool.Forward -> RaftMessageAction.Forward
        ThreadNewMessageMenuTool.Save -> if (isSaved) RaftMessageAction.Unsave else RaftMessageAction.Save
        ThreadNewMessageMenuTool.Translate -> RaftMessageAction.Translate
        ThreadNewMessageMenuTool.Select -> RaftMessageAction.Select
        ThreadNewMessageMenuTool.AddReaction -> RaftMessageAction.OpenReactionPicker
        ThreadNewMessageMenuTool.CopyLink -> RaftMessageAction.CopyLink
        ThreadNewMessageMenuTool.FollowThread -> if (isThreadFollowed) RaftMessageAction.UnfollowThread else RaftMessageAction.FollowThread
        ThreadNewMessageMenuTool.TaskStatus -> if (isTaskDone) RaftMessageAction.TaskReopen else RaftMessageAction.TaskDone
        ThreadNewMessageMenuTool.ConvertToTask -> RaftMessageAction.ConvertToTask
        ThreadNewMessageMenuTool.SelectAll -> null
    }

/** The Classic menu's task item sends "reopenTask" or "markTaskDone". */
internal fun threadTaskStatusEventProductAction(action: String): RaftMessageAction? =
    when (action) {
        "reopenTask" -> RaftMessageAction.TaskReopen
        "markTaskDone" -> RaftMessageAction.TaskDone
        else -> null
    }

/**
 * `in_thread`: the message is a reply inside a thread. On a reply-thread page ([replyThreadSurface]) every
 * row but the origin message (the top-level parent shown first) is a reply; a channel or DM page has none.
 */
internal fun threadMessageIsReplyInThread(replyThreadSurface: Boolean, originMessageId: String?, messageId: String): Boolean =
    replyThreadSurface && messageId != originMessageId

/** `activity_item_open.item_kind`; a System row has no registry value. */
internal fun InboxItemKind.productActivityItemKind(): RaftActivityItemKind? =
    when (this) {
        InboxItemKind.Channel -> RaftActivityItemKind.Channel
        InboxItemKind.DirectMessage -> RaftActivityItemKind.Dm
        InboxItemKind.Thread -> RaftActivityItemKind.Thread
        InboxItemKind.MentionAction -> RaftActivityItemKind.MentionAction
        InboxItemKind.System -> null
        // Workspace Artifacts rows open on the web; this registry has no `artifact` kind yet.
        InboxItemKind.Object -> null
    }

internal fun RaftThreadTab.productChannelTab(): RaftChannelTab =
    when (this) {
        RaftThreadTab.Chat -> RaftChannelTab.Chat
        RaftThreadTab.Tasks -> RaftChannelTab.Tasks
        RaftThreadTab.Files -> RaftChannelTab.Files
    }
