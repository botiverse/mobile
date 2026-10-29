package build.raft.app

import build.raft.app.resources.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * botiverse/slock #11687 (web ThreadOverflowMenu + MessageItem in a thread panel): a followed reply
 * thread offers Mark as Unread in its header ⋯ and "Mark Thread as Unread" in every message's menu,
 * right after Save, with the message-square-dot icon. A thread the person doesn't follow, a channel
 * or a local send has neither, as the thread's Activity row has no Mark as Unread either.
 */
class ThreadMarkThreadUnreadMenuTest {
    private val followed =
        ThreadReplyMetadata(
            parentMessageId = "parent-1",
            threadChannelId = "thread-ch-1",
            replyCount = 3,
            unreadReplyCount = 0,
            firstUnreadReplyMessageId = null,
            isThreadFollowed = true
        )

    @Test
    fun onlyAFollowedReplyThreadWithItsChannelMarksUnread() {
        // (surface is a reply thread, origin reply facts) -> the thread channel marked, or none
        data class Case(val name: String, val replyThread: Boolean, val origin: ThreadReplyMetadata?, val expected: String?)
        listOf(
            Case("followed reply thread", true, followed, "thread-ch-1"),
            Case("not followed", true, followed.copy(isThreadFollowed = false), null),
            Case("no thread channel yet", true, followed.copy(threadChannelId = null), null),
            Case("blank thread channel", true, followed.copy(threadChannelId = "  "), null),
            Case("no origin", true, null, null),
            Case("a channel", false, followed, null),
        ).forEach { case ->
            assertEquals(case.expected, threadPanelMarkUnreadChannelId(case.replyThread, case.origin), case.name)
        }
    }

    @Test
    fun everyMessageOfAFollowedReplyThreadOffersIt() {
        val slice = slice(RaftThreadSurfaceKind.ReplyThread)
        assertEquals("thread-ch-1", capabilities(message("r1"), slice, followed).markThreadUnreadChannelId)
        assertEquals("thread-ch-1", capabilities(message("parent-1"), slice, followed).markThreadUnreadChannelId)
        assertNull(capabilities(message("r1"), slice, followed.copy(isThreadFollowed = false)).markThreadUnreadChannelId)
        assertNull(capabilities(message("r1"), slice(RaftThreadSurfaceKind.Channel), followed).markThreadUnreadChannelId)
        assertNull(
            capabilities(message("r1", deliveryState = ThreadMessageDeliverySending), slice, followed).markThreadUnreadChannelId,
            "a local send has no thread menu actions"
        )
    }

    @Test
    fun classicMenuPutsItRightAfterSave() {
        var marked = 0
        val actions = classicActions(message("r1"), show = true, onMarkThreadUnread = { marked += 1 })
        val saveIndex = actions.indexOfFirst { it.label == Res.string.thread_action_save_message.desc() }
        val item = actions[saveIndex + 1]

        assertEquals(Res.string.thread_action_mark_thread_unread.desc(), item.label)
        assertEquals(RaftLucideIconName.MessageSquareDot, item.icon)
        assertEquals("message", item.group)
        item.onClick()
        assertEquals(1, marked)
        assertEquals(
            actions.size - 1,
            classicActions(message("r1"), show = false).size,
            "hidden when the thread has no Mark as Unread"
        )
    }

    @Test
    fun compactMenuShowsItOnTheSecondRowBeforeFollowNotForASelection() {
        val availability =
            capabilities(message("r1"), slice(RaftThreadSurfaceKind.ReplyThread), followed)
                .toThreadNewMessageMenuActionAvailability(message("r1"), message("r1").toThreadReplyMetadata())
        assertEquals(
            listOf(ThreadNewMessageMenuTool.SelectText, ThreadNewMessageMenuTool.CopyLink, ThreadNewMessageMenuTool.MarkThreadUnread),
            availability.secondaryTools()
        )
        assertEquals(RaftLucideIconName.MessageSquareDot, ThreadNewMessageMenuTool.MarkThreadUnread.compactIcon(availability))
        assertEquals(
            Res.string.thread_action_mark_thread_unread,
            ThreadNewMessageMenuTool.MarkThreadUnread.compactLabelResource(availability)
        )
        assertEquals(
            false,
            availability.copy(selectionContext = true).enabled(ThreadNewMessageMenuTool.MarkThreadUnread),
            "a text selection's menu acts on the picked text"
        )
        var marked = 0
        ThreadNewMessageMenuTool.MarkThreadUnread.dispatch(
            ThreadNewMessageMenuToolCallbacks(
                onCopy = {}, onReply = {}, onForward = {}, onSave = {}, onTranslate = {}, onSelect = {},
                onMarkThreadUnread = { marked += 1 }
            )
        )
        assertEquals(1, marked)
        val notFollowed =
            capabilities(message("r1"), slice(RaftThreadSurfaceKind.ReplyThread), followed.copy(isThreadFollowed = false))
                .toThreadNewMessageMenuActionAvailability(message("r1"), message("r1").toThreadReplyMetadata())
        assertEquals(listOf(ThreadNewMessageMenuTool.SelectText, ThreadNewMessageMenuTool.CopyLink), notFollowed.secondaryTools())
    }

    private fun capabilities(
        message: RaftThreadMessageItem,
        slice: ThreadTimelineSlice,
        origin: ThreadReplyMetadata?
    ): ThreadMessageMenuCapabilities =
        threadMessageMenuCapabilities(
            message = message,
            replyMetadata = message.toThreadReplyMetadata(),
            slice = slice,
            writeActionsEnabled = true,
            taskV2Enabled = true,
            originReplyMetadata = origin
        )

    private fun classicActions(
        message: RaftThreadMessageItem,
        show: Boolean,
        onMarkThreadUnread: () -> Unit = {}
    ): List<ThreadMessageMenuAction> =
        threadMessageMenuActions(
            message = message,
            showReplyAction = false,
            showSaveAction = true,
            showFollowAction = false,
            showTaskStatusAction = false,
            showConvertToTaskAction = false,
            showCopyLinkAction = true,
            showShareMessagesAction = true,
            showCancelAction = false,
            showDeleteAction = false,
            showForwardAction = true,
            showMarkThreadUnreadAction = show,
            onReply = {},
            onCopyLink = {},
            onCopy = {},
            onSelectText = {},
            onShareMessages = {},
            onSave = {},
            onFollowThread = {},
            onUnfollowThread = {},
            onTaskStatusChange = {},
            onConvertToTask = {},
            onCancel = {},
            onDelete = {},
            onMarkThreadUnread = onMarkThreadUnread
        )

    private fun message(id: String, deliveryState: String = ThreadMessageDeliverySent): RaftThreadMessageItem =
        RaftThreadMessageItem(
            id = id,
            authorId = "member-1",
            authorName = "Member",
            messageType = "Chat",
            body = "Hello",
            deliveryState = deliveryState
        )

    private fun slice(surfaceKind: String): ThreadTimelineSlice =
        ThreadTimelineSlice(
            status = "ready",
            originStatus = "ready",
            messages = emptyList(),
            originMessage = message("parent-1"),
            focusedMessageId = null,
            focusedMessageRequestNonce = 0,
            canLoadMoreBefore = false,
            canLoadMoreAfter = false,
            olderMessagesStatus = "idle",
            newerMessagesStatus = "idle",
            emptyTitle = "",
            emptyDetail = null,
            sourceThreadId = "thread-ch-1",
            alignOwnMessagesRight = false,
            compactConsecutiveMessages = false,
            surfaceKind = surfaceKind,
            showMessageSaveAction = true,
            showReactionActions = true,
            showTaskOpenActions = true,
            showTaskStatusActions = true,
            canConvertMessagesToTasks = false,
            beginningReplyCountLabel = null,
            showForwardAction = true
        )
}
