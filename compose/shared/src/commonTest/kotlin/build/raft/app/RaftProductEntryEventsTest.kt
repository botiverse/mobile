package build.raft.app

import build.raft.app.data.RaftActivityItemKind
import build.raft.app.data.RaftMessageAction
import build.raft.app.model.InboxItemKind
import build.raft.app.thread.ThreadNewMessageMenuTool
import build.raft.app.thread.productActivityItemKind
import build.raft.app.thread.productMessageAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Each app entry point -> the registry value its call site reports (null: nothing is sent). */
class RaftProductEntryEventsTest {
    private fun ThreadNewMessageMenuTool.action(saved: Boolean = false, followed: Boolean = false, done: Boolean = false) =
        productMessageAction(isSaved = saved, isThreadFollowed = followed, isTaskDone = done)

    @Test
    fun compactMenuToolsMapToRegistryActions() {
        val cases =
            mapOf(
                ThreadNewMessageMenuTool.Copy to RaftMessageAction.CopyMarkdown,
                ThreadNewMessageMenuTool.Reply to RaftMessageAction.ReplyInThread,
                ThreadNewMessageMenuTool.Forward to RaftMessageAction.Forward,
                ThreadNewMessageMenuTool.Save to RaftMessageAction.Save,
                ThreadNewMessageMenuTool.Translate to RaftMessageAction.Translate,
                ThreadNewMessageMenuTool.Select to RaftMessageAction.Select,
                ThreadNewMessageMenuTool.AddReaction to RaftMessageAction.OpenReactionPicker,
                ThreadNewMessageMenuTool.CopyLink to RaftMessageAction.CopyLink,
                ThreadNewMessageMenuTool.FollowThread to RaftMessageAction.FollowThread,
                ThreadNewMessageMenuTool.TaskStatus to RaftMessageAction.TaskDone,
                // Task v2's Create Task from Message: web sends `create_task` for it.
                ThreadNewMessageMenuTool.ConvertToTask to RaftMessageAction.CreateTask,
                ThreadNewMessageMenuTool.SelectText to RaftMessageAction.SelectText,
            )
        cases.forEach { (tool, action) -> assertEquals(action, tool.action(), tool.name) }
        assertEquals(
            ThreadNewMessageMenuTool.entries.toSet() - ThreadNewMessageMenuTool.SelectAll -
                ThreadNewMessageMenuTool.MarkThreadUnread - ThreadNewMessageMenuTool.CopySelection,
            cases.keys,
        )
        assertNull(ThreadNewMessageMenuTool.SelectAll.action(), "in-place selection is not the long-press menu")
        // Marks the thread, not the message; web sends no message_action_selected for it (slock #11687).
        assertNull(ThreadNewMessageMenuTool.MarkThreadUnread.action(), "mark thread unread is not a message action")
        assertNull(ThreadNewMessageMenuTool.CopySelection.action(), "only a right-click menu has it; it is not the long-press menu")
    }

    @Test
    fun activityRowKindsMapAndSystemRowsSendNothing() {
        val expected =
            mapOf(
                InboxItemKind.Channel to RaftActivityItemKind.Channel,
                InboxItemKind.DirectMessage to RaftActivityItemKind.Dm,
                InboxItemKind.Thread to RaftActivityItemKind.Thread,
                InboxItemKind.MentionAction to RaftActivityItemKind.MentionAction,
                InboxItemKind.Object to RaftActivityItemKind.Artifact,
            )
        expected.forEach { (kind, item) -> assertEquals(item, kind.productActivityItemKind(), kind.name) }
        val silent = setOf(InboxItemKind.System)
        silent.forEach { kind -> assertNull(kind.productActivityItemKind(), kind.name) }
        assertEquals(InboxItemKind.entries.toSet() - silent, expected.keys)
    }
}
