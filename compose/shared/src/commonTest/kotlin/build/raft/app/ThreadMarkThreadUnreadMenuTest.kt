package build.raft.app

import build.raft.app.resources.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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
    fun classicMenuPutsItInTheThreadSectionBeforeTheCopyActions() {
        var marked = 0
        val actions = classicActions(message("r1"), show = true, onMarkThreadUnread = { marked += 1 })
        // web MessageItem: Mark Thread as Unread follows Follow in the thread and task section.
        val index = actions.indexOfFirst { it.label == Res.string.thread_action_mark_thread_unread.desc() }
        val item = actions[index]
        assertTrue(index < actions.indexOfFirst { it.group == "copy" }, "before the take-it-elsewhere section")

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

    // botiverse/slock #14222 (web useMessageMenuMarkUnread): in a channel's or DM's list, a
    // message's menu offers the conversation's Mark as Unread and, when the person follows the
    // message's thread, that thread's.
    @Test
    fun aConversationListOffersItsOwnMarkAsUnreadNamedByItsKind() {
        data class Case(val name: String, val slice: ThreadTimelineSlice, val expected: ThreadMarkConversationUnreadKind?)
        listOf(
            Case("channel", slice(RaftThreadSurfaceKind.Channel), ThreadMarkConversationUnreadKind.Channel),
            Case("dm", slice(RaftThreadSurfaceKind.DirectMessage), ThreadMarkConversationUnreadKind.DirectMessage),
            Case("channel previewed without joining", slice(RaftThreadSurfaceKind.Channel).copy(joined = false), null),
            Case("reply thread", slice(RaftThreadSurfaceKind.ReplyThread), null),
            Case("task conversation", slice(RaftThreadSurfaceKind.Channel).copy(channelKind = RaftTaskConversationChannelKind), null),
        ).forEach { case ->
            assertEquals(case.expected, capabilities(message("m1"), case.slice, null).markConversationUnread, case.name)
        }
        assertNull(
            capabilities(message("m1", deliveryState = ThreadMessageDeliverySending), slice(RaftThreadSurfaceKind.Channel), null)
                .markConversationUnread,
            "a local send has none"
        )
    }

    @Test
    fun aListMessageWhoseThreadIsFollowedMarksThatThread() {
        val channel = slice(RaftThreadSurfaceKind.Channel)
        fun markedThread(slice: ThreadTimelineSlice, metadata: ThreadReplyMetadata): String? =
            threadMessageMenuCapabilities(
                message = message("parent-1"),
                replyMetadata = metadata,
                slice = slice,
                writeActionsEnabled = true,
                taskV2Enabled = true,
            ).markThreadUnreadChannelId
        assertEquals("thread-ch-1", markedThread(channel, followed))
        assertEquals("thread-ch-1", markedThread(slice(RaftThreadSurfaceKind.DirectMessage), followed))
        assertEquals("thread-ch-1", markedThread(channel.copy(joined = false), followed), "a preview still marks a followed thread")
        assertNull(markedThread(channel, followed.copy(isThreadFollowed = false)), "not followed")
        assertNull(markedThread(channel, followed.copy(threadChannelId = " ")), "no thread channel")
        assertNull(
            markedThread(channel.copy(channelKind = RaftTaskConversationChannelKind), followed),
            "a task conversation acts on itself as a thread"
        )
    }

    @Test
    fun classicMenuPutsTheConversationsLastOfTheJustForMeSection() {
        var marked = 0
        val actions =
            classicActions(message("m1"), show = true, conversation = ThreadMarkConversationUnreadKind.DirectMessage, onMarkConversationUnread = { marked += 1 })
        val item = actions.last()
        assertEquals(Res.string.thread_action_mark_dm_unread.desc(), item.label)
        assertEquals(RaftLucideIconName.MessageSquareDot, item.icon)
        assertEquals("personal", item.group)
        assertTrue(actions.indexOfFirst { it.label == Res.string.thread_action_save_message.desc() } < actions.lastIndex, "after Save")
        item.onClick()
        assertEquals(1, marked)
        assertEquals(
            Res.string.thread_action_mark_channel_unread.desc(),
            classicActions(message("m1"), show = false, conversation = ThreadMarkConversationUnreadKind.Channel).last().label
        )
        assertTrue(classicActions(message("m1"), show = false).none { it.group == "personal" && it.icon == RaftLucideIconName.MessageSquareDot })
    }

    @Test
    fun compactMenuShowsBothOnTheSecondRowTheConversationsLast() {
        val metadata = followed
        val availability =
            threadMessageMenuCapabilities(
                message = message("parent-1"),
                replyMetadata = metadata,
                slice = slice(RaftThreadSurfaceKind.Channel),
                writeActionsEnabled = true,
                taskV2Enabled = true,
            ).toThreadNewMessageMenuActionAvailability(message("parent-1"), metadata)
        val tools = availability.secondaryTools()
        assertTrue(ThreadNewMessageMenuTool.MarkThreadUnread in tools)
        assertEquals(ThreadNewMessageMenuTool.MarkConversationUnread, tools.last())
        assertEquals(Res.string.thread_action_mark_channel_unread, ThreadNewMessageMenuTool.MarkConversationUnread.compactLabelResource(availability))
        assertEquals(
            Res.string.thread_action_mark_dm_unread,
            ThreadNewMessageMenuTool.MarkConversationUnread.compactLabelResource(
                availability.copy(markConversationUnread = ThreadMarkConversationUnreadKind.DirectMessage)
            )
        )
        assertEquals(RaftLucideIconName.MessageSquareDot, ThreadNewMessageMenuTool.MarkConversationUnread.compactIcon(availability))
        assertEquals(
            false,
            availability.copy(selectionContext = true).enabled(ThreadNewMessageMenuTool.MarkConversationUnread),
            "a text selection's menu acts on the picked text"
        )
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
        onMarkThreadUnread: () -> Unit = {},
        conversation: ThreadMarkConversationUnreadKind? = null,
        onMarkConversationUnread: () -> Unit = {}
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
            onMarkThreadUnread = onMarkThreadUnread,
            markConversationUnread = conversation,
            onMarkConversationUnread = onMarkConversationUnread
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
