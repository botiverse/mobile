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
import build.raft.app.data.FakeConversationNetworkClient
import build.raft.app.data.RealConversationRepository
import build.raft.app.data.conversationRepositoryTestSessionStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

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

    @Test
    fun firstOpenShowsTheComposerOnceTheParentChannelResolvesAndTheTitleFollows() = runTest {
        // Hands 4cf4c781: on 4G the task list took seconds and the footer waited for it. Write
        // authority is the parent channel's, so the footer mounts with it; the title lands later.
        val taskListStarted = CompletableDeferred<Unit>()
        val releaseTaskList = CompletableDeferred<Unit>()
        val sessionStore = conversationRepositoryTestSessionStore()
        val network =
            FakeConversationNetworkClient(
                responses =
                    mapOf(
                        "GET messages/channel/conv-1" to
                            """{"historyLimited":false,"messages":[{"id":"reply-1","seq":4,"channelId":"conv-1","senderType":"user","senderId":"user-2","senderName":"Mira","messageType":"chat","content":"on it","createdAt":"2026-10-27T10:00:00.000Z"}]}""",
                        "GET channels/conv-1" to
                            """{"id":"conv-1","serverId":"server-1","name":"task-task-1","type":"task","parentChannelId":"channel-1","taskId":"task-1","joined":false}""",
                        "GET channels/channel-1" to """{"id":"channel-1","name":"general","type":"channel","joined":true}""",
                        "GET tasks/channel/channel-1" to """{"tasks":[$TaskRowJson]}""",
                    ),
                requestStarted = mapOf("GET tasks/channel/channel-1" to taskListStarted),
                requestRelease = mapOf("GET tasks/channel/channel-1" to releaseTaskList),
            )
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = sessionStore,
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = RealConversationRepository(sessionStore, network),
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val launch =
            buildStandaloneTaskThreadLaunchPayload(
                task = anchoredTask(),
                serverId = "server-1",
                conversationDirectory = SharedConversationDirectoryState(),
                timeContext = null,
            ) ?: error("task conversation launch rejected")

        store.dispatch(SharedThreadIntent.Start(launch.toInitialPayload(launch.instanceId)))
        taskListStarted.await()
        val beforeTitle =
            store.awaitState("timeline committed while the task list is held") { state ->
                state.payload.messages.isNotEmpty() && state.accessResolution != ThreadAccessResolution.Loading
            }
        assertEquals(listOf("reply-1"), beforeTitle.payload.messages.map { it.id }, "timeline shown")
        assertEquals(ThreadAccessResolution.Resolved, beforeTitle.accessResolution, "task list still in flight")
        assertTrue(beforeTitle.writeActionsEnabled, "task list still in flight")
        assertTrue(beforeTitle.payload.joined && beforeTitle.payload.canSend, "parent membership applied")
        assertTrue(
            beforeTitle.payload.isThreadPrimaryFooterReady(beforeTitle.timelineSlice, beforeTitle.accessResolution),
            "composer footer ready before the task list lands",
        )
        assertNotEquals(TaskTitle, beforeTitle.payload.title, "title still pending")

        releaseTaskList.complete(Unit)
        val afterTitle = store.awaitState("task title applied") { state -> state.payload.title == TaskTitle }
        assertEquals(TaskTitle, afterTitle.payload.title)
        assertEquals(ThreadAccessResolution.Resolved, afterTitle.accessResolution, "title does not flip access")
        assertTrue(afterTitle.writeActionsEnabled, "title does not flip access")
        assertTrue(afterTitle.payload.joined && afterTitle.payload.canSend, "title does not flip access")
        assertEquals(1, network.requests.count { it.path == "tasks/channel/channel-1" })
        scope.cancel()
    }

    /**
     * The real repository parses responses on the shared JSON parse dispatcher (real threads, no
     * injection seam), so virtual time cannot express "the response was parsed". Wait for the
     * state itself, bounded in real time, failing with the state seen.
     */
    private suspend fun SharedThreadStore.awaitState(
        label: String,
        predicate: (SharedThreadState) -> Boolean,
    ): SharedThreadState =
        withContext(Dispatchers.Default) {
            withTimeoutOrNull(5_000) { state.first(predicate) }
        } ?: error(
            "waited for $label, state=${state.value.payload.status}/${state.value.accessResolution}/" +
                "messages=${state.value.payload.messages.size}/title=${state.value.payload.title}"
        )

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

    private companion object {
        const val TaskTitle = "#general:#7 Ship the task conversation"
        val TaskRowJson =
            """{"id":"task-1","messageId":"task-1","hostMessageId":null,"sourceMessageId":null,"conversationReplyCount":1,""" +
                """"conversationChannelId":"conv-1","conversationAnchored":true,"channelId":"channel-1","channelName":"general",""" +
                """"channelType":"channel","taskNumber":7,"title":"Ship the task conversation","description":null,""" +
                """"status":"in_progress","createdByType":"user","createdById":"user-1","createdByName":"Artin",""" +
                """"createdByHandle":"artin","createdByMembershipStatus":"active","claimedByType":null,"claimedById":null,""" +
                """"claimedByName":null,"claimedAt":null,"completedAt":null,"requiresResourceReceipt":false,""" +
                """"resourceReceipt":null,"resourceReceiptRecordedAt":null,"resourceTeardownOwnerAgentId":null,""" +
                """"resourceExpiryFollowupId":null,"createdAt":"2026-10-27T09:00:00.000Z",""" +
                """"updatedAt":"2026-10-27T09:30:00.000Z","revision":2,"isLegacy":false}"""
    }
}
