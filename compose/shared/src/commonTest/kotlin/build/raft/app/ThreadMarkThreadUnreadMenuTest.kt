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

    // botiverse/slock #16225 (menu order v4): Mark channel/DM as unread is gone from the message
    // menu (it lives on the Home/sidebar row menu); a list message whose thread is followed still
    // offers Mark thread as unread.
    @Test
    fun aListMessageWhoseThreadIsFollowedMarksThatThread() {
        val channel = slice(RaftThreadSurfaceKind.Channel)
        fun markedThread(slice: ThreadTimelineSlice, metadata: ThreadReplyMetadata): String? =
            threadMessageMenuCapabilities(
                message = message("parent-1"),
                replyMetadata = metadata,
                slice = slice,
                writeActionsEnabled = true,
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
            originReplyMetadata = origin
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
