package build.raft.app

import build.raft.app.data.ChannelJoinResult
import build.raft.app.data.DataResult
import build.raft.app.data.ScreenError
import build.raft.app.data.TaskConversationChannelRef
import build.raft.app.model.ChannelId
import build.raft.app.model.TaskId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The composer's Join on a conversation page: which channel it joins, and the page after. */
class ThreadJoinChannelTest {
    /** A task conversation the reader may read but not write: its task channel is not joined. */
    private fun taskConversationPage() =
        RaftThreadScreenPayload(
            sourceThreadId = "task-conversation-1",
            streamChannelId = "task-conversation-1",
            surfaceKind = RaftThreadSurfaceKind.Channel,
            channelKind = RaftTaskConversationChannelKind,
            joined = false,
            canSend = false,
            composer = RaftThreadComposerPayload(enabled = false),
        )

    private fun channelPage() =
        RaftThreadScreenPayload(
            sourceThreadId = "channel-1",
            surfaceKind = RaftThreadSurfaceKind.Channel,
            channelKind = "channel",
            joined = false,
            canSend = false,
            composer = RaftThreadComposerPayload(enabled = false),
        )

    private class Calls {
        val refs = mutableListOf<ChannelId>()
        val joins = mutableListOf<ChannelId>()
    }

    private fun refOf(conversation: String, parent: String) =
        TaskConversationChannelRef(ChannelId(conversation), ChannelId(parent), TaskId("task-7"))

    @Test
    fun aTaskConversationJoinsItsTaskChannelAndThePageShowsTheComposer() = runTest {
        val calls = Calls()
        val page = taskConversationPage()
        assertTrue(threadFooterShowsJoin(page.joined, null))

        val outcome =
            page.joinThreadChannel(
                loadTaskConversationRef = { id ->
                    calls.refs += id
                    DataResult.Content(refOf("task-conversation-1", "channel-general"))
                },
                joinChannel = { id ->
                    calls.joins += id
                    DataResult.Content(ChannelJoinResult(channelId = id))
                },
            )

        // POST /channels/channel-general/join: never the conversation itself (the server answers
        // a `task` sub-conversation with 400 thread_membership).
        assertEquals(listOf(ChannelId("task-conversation-1")), calls.refs)
        assertEquals(listOf(ChannelId("channel-general")), calls.joins)
        val joined = assertIs<ThreadJoinChannelOutcome.Joined>(outcome).channel
        assertEquals(ChannelId("channel-general"), joined.joinedChannelId)
        assertEquals(ChannelId("task-conversation-1"), joined.pageChannelId)

        // What the store applies (SharedThreadIntent.ApplyChannelJoined(pageChannelId)): the composer.
        val after = page.withChannelJoined(joined.pageChannelId.value)
        assertTrue(after.joined)
        assertTrue(after.canSend)
        assertTrue(after.composer.enabled)
        assertFalse(threadFooterShowsJoin(after.joined, null))
    }

    @Test
    fun aChannelPageJoinsItselfWithoutReadingATaskRef() = runTest {
        val calls = Calls()

        val outcome =
            channelPage().joinThreadChannel(
                loadTaskConversationRef = { id ->
                    calls.refs += id
                    error("a channel page has no task ref")
                },
                joinChannel = { id ->
                    calls.joins += id
                    DataResult.Content(ChannelJoinResult(channelId = id))
                },
            )

        assertEquals(emptyList<ChannelId>(), calls.refs)
        assertEquals(listOf(ChannelId("channel-1")), calls.joins)
        val joined = assertIs<ThreadJoinChannelOutcome.Joined>(outcome).channel
        assertEquals(ChannelId("channel-1"), joined.joinedChannelId)
        assertEquals(ChannelId("channel-1"), joined.pageChannelId)
    }

    @Test
    fun anUnreadableTaskRefSendsNoJoinAndKeepsItsError() = runTest {
        val calls = Calls()
        val error = ScreenError(code = "task_conversation_ref_network", traceId = "0123456789abcdef0123456789abcdef")

        val outcome =
            taskConversationPage().joinThreadChannel(
                loadTaskConversationRef = { DataResult.Error(error) },
                joinChannel = { id ->
                    calls.joins += id
                    DataResult.Content(ChannelJoinResult(channelId = id))
                },
            )

        assertEquals(emptyList<ChannelId>(), calls.joins)
        assertEquals(DataResult.Error(error), assertIs<ThreadJoinChannelOutcome.Failed>(outcome).result)
    }

    @Test
    fun aRefOfAnotherConversationSendsNoJoin() = runTest {
        val calls = Calls()

        val outcome =
            taskConversationPage().joinThreadChannel(
                loadTaskConversationRef = { DataResult.Content(refOf("task-conversation-2", "channel-general")) },
                joinChannel = { id ->
                    calls.joins += id
                    DataResult.Content(ChannelJoinResult(channelId = id))
                },
            )

        assertEquals(emptyList<ChannelId>(), calls.joins)
        assertEquals(ThreadJoinChannelOutcome.Unresolved, outcome)
    }

    @Test
    fun aJoinOfAnotherChannelLeavesThePageAsItWas() {
        val page = taskConversationPage()

        assertEquals(page, page.withChannelJoined("channel-general"))
    }
}
