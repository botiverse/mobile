package build.raft.app

import build.raft.app.data.RaftActivityItemKind
import build.raft.app.data.RaftChannelTab
import build.raft.app.data.RaftMessageAction
import build.raft.app.model.InboxItemKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
                ThreadNewMessageMenuTool.ConvertToTask to RaftMessageAction.ConvertToTask,
                ThreadNewMessageMenuTool.SelectText to RaftMessageAction.SelectText,
            )
        cases.forEach { (tool, action) -> assertEquals(action, tool.action(), tool.name) }
        assertEquals(ThreadNewMessageMenuTool.entries.toSet() - ThreadNewMessageMenuTool.SelectAll, cases.keys)
        assertNull(ThreadNewMessageMenuTool.SelectAll.action(), "in-place selection is not the long-press menu")
    }

    @Test
    fun toggleToolsFollowTheMessageStateBeforeTheTap() {
        assertEquals(RaftMessageAction.Unsave, ThreadNewMessageMenuTool.Save.action(saved = true))
        assertEquals(RaftMessageAction.UnfollowThread, ThreadNewMessageMenuTool.FollowThread.action(followed = true))
        assertEquals(RaftMessageAction.TaskReopen, ThreadNewMessageMenuTool.TaskStatus.action(done = true))
    }

    @Test
    fun classicTaskItemMapsItsEventName() {
        assertEquals(RaftMessageAction.TaskDone, threadTaskStatusEventProductAction("markTaskDone"))
        assertEquals(RaftMessageAction.TaskReopen, threadTaskStatusEventProductAction("reopenTask"))
        assertNull(threadTaskStatusEventProductAction("setTaskStatus"))
    }

    @Test
    fun inThreadFollowsTheMessageNotThePage() {
        assertTrue(threadMessageIsReplyInThread(replyThreadSurface = true, originMessageId = "origin", messageId = "reply"))
        assertTrue(threadMessageIsReplyInThread(replyThreadSurface = true, originMessageId = null, messageId = "reply"))
        assertFalse(threadMessageIsReplyInThread(replyThreadSurface = true, originMessageId = "origin", messageId = "origin"))
        assertFalse(threadMessageIsReplyInThread(replyThreadSurface = false, originMessageId = null, messageId = "m1"))
    }

    @Test
    fun timelineSliceMarksOnlyReplyThreadPages() {
        val reply = RaftThreadScreenPayload(surfaceKind = RaftThreadSurfaceKind.ReplyThread).toThreadTimelineSlice()
        assertTrue(reply.replyThreadSurface)
        listOf(RaftThreadSurfaceKind.Channel, RaftThreadSurfaceKind.DirectMessage).forEach { kind ->
            assertFalse(RaftThreadScreenPayload(surfaceKind = kind).toThreadTimelineSlice().replyThreadSurface, kind)
        }
    }

    @Test
    fun activityRowKindsMapAndSystemRowsSendNothing() {
        val expected =
            mapOf(
                InboxItemKind.Channel to RaftActivityItemKind.Channel,
                InboxItemKind.DirectMessage to RaftActivityItemKind.Dm,
                InboxItemKind.Thread to RaftActivityItemKind.Thread,
                InboxItemKind.MentionAction to RaftActivityItemKind.MentionAction,
            )
        expected.forEach { (kind, item) -> assertEquals(item, kind.productActivityItemKind(), kind.name) }
        // System rows and Artifact object rows (no `artifact` kind in the registry yet) send nothing.
        val silent = setOf(InboxItemKind.System, InboxItemKind.Object)
        silent.forEach { kind -> assertNull(kind.productActivityItemKind(), kind.name) }
        assertEquals(InboxItemKind.entries.toSet() - silent, expected.keys)
    }

    @Test
    fun threadTabsMapToChannelTabs() {
        assertEquals(RaftChannelTab.Chat, RaftThreadTab.Chat.productChannelTab())
        assertEquals(RaftChannelTab.Tasks, RaftThreadTab.Tasks.productChannelTab())
        assertEquals(RaftChannelTab.Files, RaftThreadTab.Files.productChannelTab())
        assertEquals(RaftThreadTab.entries.map { it.wireValue }, RaftThreadTab.entries.map { it.productChannelTab().wire })
    }
}
