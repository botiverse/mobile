package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.messagesResponse
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.SharedThreadStoreTestFixtures.member
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.SharedConversationDirectoryState
import build.raft.app.data.ThreadApiResult
import build.raft.app.model.ChannelId
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.model.ThreadId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Task v2: the task conversation opened from a task reads and replies through the ordinary
 * thread page store: `GET /messages/channel/{conv}`, live `message:new` on the conversation, and
 * `POST /messages {channelId: conv}` (botiverse/slock `routes/messages.ts` @
 * 95703ec22e319f526dedf6c899751929b71062d7).
 */
class SharedThreadStoreTaskConversationTest {
    @Test
    fun taskConversationReadsAndRepliesOnTheConversationChannel() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val api = FakeThreadApi(bodyText = messagesResponse("first reply", id = "reply-1", seq = 3, channelId = "conv-1"))
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val launch =
            buildStandaloneTaskThreadLaunchPayload(
                task = anchoredTask(),
                serverId = "server-1",
                conversationDirectory = SharedConversationDirectoryState(),
                timeContext = null,
            ) ?: error("task conversation launch rejected")

        store.accept(SharedThreadIntent.Start(launch.toInitialPayload(launch.instanceId)))
        store.accept(SharedThreadIntent.Refresh)

        assertEquals(listOf("conv-1"), api.channelIds.distinct())
        assertEquals(listOf("reply-1"), store.state.value.payload.messages.map { it.id })

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(
                    Message(
                        id = MessageId("reply-2"),
                        threadId = ThreadId("conv-1"),
                        channelId = ChannelId("conv-1"),
                        author = member("user-2", "Mira"),
                        timestampLabel = "2026-10-27T10:05:00Z",
                        body = "second reply",
                        seq = 4,
                    )
                )
            )
        )
        assertEquals(listOf("reply-1", "reply-2"), store.state.value.payload.messages.map { it.id })

        val route = store.state.value.payload.resolveThreadRouteIdentity()
        // POST /messages answers the created message (routes/messages.ts).
        api.sendResponseBody =
            """{"id":"reply-3","seq":5,"channelId":"conv-1","content":"my reply","createdAt":"2026-10-27T10:06:00Z","senderId":"u1","senderName":"Artin","senderType":"user","messageType":"chat"}"""
        val sent =
            dataSource.sendMessage(
                channelId = route.timelineChannelId ?: error("task conversation route has no timeline"),
                body = "my reply",
                asTask = false,
            )
        val page = assertIs<ThreadApiResult.Success<build.raft.app.data.ThreadMessagesPage>>(sent).data
        assertEquals(listOf("conv-1"), page.messages.map { it.channelId })
        assertEquals(listOf("conv-1"), api.sentChannelIds)
        scope.cancel()
    }

    private fun anchoredTask(): TaskSummary =
        TaskSummary(
            id = TaskId("task-1"),
            title = "Ship the task conversation",
            status = TaskStatus.InProgress,
            taskNumber = 7,
            channelId = ChannelId("channel-1"),
            channelName = "general",
            updatedAtLabel = "2026-10-27T09:30:00.000Z",
            channelType = "channel",
            conversationChannelId = ChannelId("conv-1"),
            conversationAnchored = true,
        )
}
