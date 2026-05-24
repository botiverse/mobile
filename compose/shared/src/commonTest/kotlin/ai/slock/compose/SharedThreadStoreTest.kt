package ai.slock.compose

import ai.slock.compose.data.ComposeLocalWriteThrough
import ai.slock.compose.data.DataResult
import ai.slock.compose.data.HomeNetworkResponse
import ai.slock.compose.data.InMemoryComposeLocalDataStore
import ai.slock.compose.data.InMemorySlockAuthSessionStore
import ai.slock.compose.data.SharedThreadNetworkApi
import ai.slock.compose.data.StoredAuthSession
import ai.slock.compose.data.StoredAuthUser
import ai.slock.compose.data.ThreadSnapshot
import ai.slock.compose.data.buildSharedThreadPayloadFromMessagesResponse
import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.ConversationSummary
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.Message
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.MessageNew
import ai.slock.compose.model.MessageUpdated
import ai.slock.compose.model.Presence
import ai.slock.compose.model.TaskId
import ai.slock.compose.model.TaskStatus
import ai.slock.compose.model.TaskSummary
import ai.slock.compose.model.TaskUpdated
import ai.slock.compose.model.ThreadUpdated
import ai.slock.compose.model.ThreadId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedThreadStoreTest {
    @Test
    fun cachedThreadPayloadSurvivesVisibleFetchFailure() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = FakeThreadApi(failChannelMessages = true)
        val dataSource =
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession())
            )
        dataSource.cachePayload(contentPayload("cached body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload()))

        val payload = store.state.value.payload
        assertEquals("content", payload.status)
        assertEquals("cached body", payload.messages.single().body)
        assertEquals(1, api.channelMessageCalls)
        scope.cancel()
    }

    @Test
    fun successfulFetchWritesPayloadCacheForNextStart() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val dataSource =
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession())
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload()))

        assertEquals("network body", store.state.value.payload.messages.single().body)
        api.failChannelMessages = true
        store.accept(SharedThreadIntent.Start(basePayload()))

        val payload = store.state.value.payload
        assertEquals("content", payload.status)
        assertEquals("network body", payload.messages.single().body)
        assertEquals(2, api.channelMessageCalls)
        scope.cancel()
    }

    @Test
    fun persistentCachedThreadSnapshotRendersBeforeVisibleFetchAndSurvivesFailure() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeThread(
            ThreadSnapshot(
                conversation =
                    ConversationSummary(
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        kind = ConversationKind.Channel,
                        name = "general"
                    ),
                messages =
                    listOf(
                        Message(
                            id = MessageId("cached-1"),
                            threadId = ThreadId("channel-1"),
                            channelId = ChannelId("channel-1"),
                            author = member("u2", "Stdrc"),
                            timestampLabel = "2026-05-19T15:00:00Z",
                            body = "persistent cached body"
                        )
                    )
            )
        )
        val api = FakeThreadApi(failChannelMessages = true)
        val dataSource =
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                localWriteThrough = ComposeLocalWriteThrough(localStore)
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload()))

        val payload = store.state.value.payload
        assertEquals("content", payload.status)
        assertEquals("persistent cached body", payload.messages.single().body)
        assertEquals("thread_network", payload.errorCode)
        assertEquals(1, api.channelMessageCalls)
        scope.cancel()
    }

    @Test
    fun successfulFetchWritesPersistentThreadSnapshotForColdRestart() = runBlocking {
        val localStore = InMemoryComposeLocalDataStore()
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val dataSource =
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                localWriteThrough = ComposeLocalWriteThrough(localStore)
            )

        dataSource.fetchThreadPayload(basePayload())

        val cached = localStore.readThread(ThreadId("channel-1"))
        assertEquals("general", cached?.conversation?.name)
        assertEquals("network body", cached?.messages?.single()?.body)
        assertEquals("Artin", cached?.messages?.single()?.author?.displayName)
    }

    @Test
    fun realtimeMessageNewMergesIntoVisiblePayloadAndPersistentSnapshot() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                localWriteThrough = ComposeLocalWriteThrough(localStore)
            )
        dataSource.cachePayload(contentPayload("cached body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(
                    Message(
                        id = MessageId("m2"),
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        author = member("u2", "Stdrc"),
                        timestampLabel = "2026-05-19T16:01:00Z",
                        body = "realtime body"
                    )
                )
            )
        )

        val payload = store.state.value.payload
        assertEquals(listOf("cached body", "realtime body"), payload.messages.map { it.body })
        assertEquals("realtime body", localStore.readThread(ThreadId("channel-1"))?.messages?.last()?.body)
        scope.cancel()
    }

    @Test
    fun irrelevantRealtimeMessageDoesNotChangeVisibleThread() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession())
            )
        dataSource.cachePayload(contentPayload("cached body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(
                    Message(
                        id = MessageId("other"),
                        threadId = ThreadId("other-channel"),
                        channelId = ChannelId("other-channel"),
                        author = member("u2", "Stdrc"),
                        timestampLabel = "2026-05-19T16:01:00Z",
                        body = "irrelevant"
                    )
                )
            )
        )

        assertEquals(listOf("cached body"), store.state.value.payload.messages.map { it.body })
        scope.cancel()
    }

    @Test
    fun realtimeMessageUpdatedPreservesExistingAuthorMetadata() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession())
            )
        dataSource.cachePayload(contentPayload("cached body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageUpdated(
                    Message(
                        id = MessageId("m1"),
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        author = member("u1", "u1"),
                        timestampLabel = "",
                        body = "edited body"
                    )
                )
            )
        )

        val message = store.state.value.payload.messages.single()
        assertEquals("edited body", message.body)
        assertEquals("Artin", message.authorName)
        scope.cancel()
    }

    @Test
    fun realtimeThreadUpdatedAndTaskUpdatedMergeIntoPayload() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession())
            )
        val initial =
            contentPayload("task body").copy(
                messages =
                    listOf(
                        contentPayload("task body").messages.single().copy(
                            taskId = "task-1",
                            taskNumber = 44,
                            taskStatus = "Todo"
                        )
                    ),
                tasks =
                    listOf(
                        KuiklyThreadTaskItem(
                            id = "task-1",
                            title = "Old task",
                            status = "Todo",
                            taskNumber = 44
                        )
                    )
            )
        dataSource.cachePayload(initial)
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                ThreadUpdated(
                    parentMessageId = MessageId("m1"),
                    threadChannelId = ChannelId("reply-thread"),
                    replyCount = 3,
                    lastReplyAt = "2026-05-19T16:10:00Z",
                    participantIds = emptyList(),
                    unreadCount = 2,
                    firstUnreadMessageId = MessageId("reply-2")
                )
            )
        )
        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                TaskUpdated(
                    TaskSummary(
                        id = TaskId("task-1"),
                        title = "Updated task",
                        status = TaskStatus.InReview,
                        taskNumber = 44,
                        messageId = MessageId("m1"),
                        updatedAtLabel = "2026-05-19T16:11:00Z"
                    )
                )
            )
        )

        val message = store.state.value.payload.messages.single()
        assertEquals(3, message.replyCount)
        assertEquals(2, message.unreadReplyCount)
        assertEquals("reply-2", message.firstUnreadReplyMessageId)
        assertEquals("InReview", message.taskStatus)
        assertEquals("Updated task", store.state.value.payload.tasks.single().title)
        scope.cancel()
    }

    @Test
    fun visibleReadAllMarksChannel() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = FakeThreadApi(failChannelMessages = true).also { it.failReadAll = false }
        val dataSource =
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                localWriteThrough = ComposeLocalWriteThrough(InMemoryComposeLocalDataStore())
            )
        dataSource.cachePayload(contentPayload("cached body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(SharedThreadIntent.MarkVisibleReadAll)

        assertEquals(listOf("channel-1"), api.readAllChannels)
        scope.cancel()
    }

    @Test
    fun sendMessageForwardsAttachmentIds() = runBlocking {
        val api = FakeThreadApi()
        val dataSource =
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession())
            )

        dataSource.sendMessage(
            channelId = "channel-1",
            body = "with files",
            asTask = false,
            attachmentIds = listOf("att-1", "att-2")
        )

        assertEquals(listOf("att-1", "att-2"), api.lastAttachmentIds)
    }

    @Test
    fun saveMessageCallsThreadApi() = runBlocking {
        val api = FakeThreadApi()
        val dataSource =
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession())
            )

        dataSource.saveMessage("m1")

        assertEquals(listOf("m1"), api.savedMessageIds)
    }

    @Test
    fun updateTaskStatusUpdatesCachedPayloadAndPersistentSnapshot() = runBlocking {
        val localStore = InMemoryComposeLocalDataStore()
        val api = FakeThreadApi()
        val dataSource =
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                localWriteThrough = ComposeLocalWriteThrough(localStore)
            )
        val payload =
            contentPayload("task body").copy(
                messages =
                    listOf(
                        contentPayload("task body").messages.single().copy(
                            taskId = "task-1",
                            taskStatus = "Todo"
                        )
                    ),
                tasks =
                    listOf(
                        KuiklyThreadTaskItem(
                            id = "task-1",
                            title = "#44 Task",
                            status = "Todo",
                            taskNumber = 44
                        )
                    )
            )
        dataSource.cachePayload(payload)

        val result = dataSource.updateTaskStatus("task-1", TaskStatus.Done)

        val updated = (result as DataResult.Content).data
        assertEquals("Done", updated.messages.single().taskStatus)
        assertEquals("Done", updated.tasks.single().status)
        assertEquals("Done", localStore.readThread(ThreadId("channel-1"))?.tasks?.single()?.status?.name)
        assertEquals(listOf("task-1" to TaskStatus.Done), api.updatedTaskStatuses)
    }

    @Test
    fun createOrOpenReplyThreadFetchesReplyChannelAndPreservesOrigin() = runBlocking {
        val api = FakeThreadApi(bodyText = messagesResponse("reply body", id = "reply-1", seq = 11))
        val dataSource =
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession())
            )
        val base = contentPayload("parent body")
        val parent = base.messages.single()

        val result = dataSource.createOrOpenReplyThread(base, parent)

        val payload = (result as DataResult.Content).data
        assertEquals("thread-m1", payload.sourceThreadId)
        assertEquals("parent body", payload.originMessage?.body)
        assertEquals("m1", payload.originParentMessageId)
        assertEquals("reply body", payload.messages.single().body)
        assertEquals(listOf("channel-1" to "m1"), api.createdThreads)
        assertEquals(listOf("thread-m1"), api.channelIds.takeLast(1))
    }

    @Test
    fun loadOlderMessagesMergesBeforeVisiblePayloadAndWritesCache() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10),
                olderBodyText = messagesResponse("older body", id = "m5", seq = 5, historyLimited = false)
            )
        val dataSource =
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                localWriteThrough = ComposeLocalWriteThrough(localStore)
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(SharedThreadIntent.LoadOlder)

        val payload = store.state.value.payload
        assertEquals(listOf("older body", "current body"), payload.messages.map { it.body })
        assertEquals(listOf(10L), api.beforeSeqs)
        assertEquals(false, payload.canLoadMoreBefore)
        assertEquals("idle", payload.olderMessagesStatus)
        assertEquals("older body", localStore.readThread(ThreadId("channel-1"))?.messages?.first()?.body)
        scope.cancel()
    }

    @Test
    fun loadNewerMessagesMergesAfterVisiblePayload() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
                newerBodyText = syncResponse("newer body", id = "m12", seq = 12)
            )
        val dataSource =
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession())
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = true)))

        store.accept(SharedThreadIntent.LoadNewer)

        val payload = store.state.value.payload
        assertEquals(listOf("current body", "newer body"), payload.messages.map { it.body })
        assertEquals(listOf(10L), api.syncSinceSeqs)
        assertEquals(false, payload.canLoadMoreAfter)
        assertEquals("idle", payload.newerMessagesStatus)
        scope.cancel()
    }

    private class FakeThreadApi(
        var bodyText: String = messagesResponse("network body"),
        var olderBodyText: String = messagesResponse("older body", id = "older", seq = 1, historyLimited = false),
        var newerBodyText: String = messagesResponse("newer body", id = "newer", seq = 100, historyLimited = false),
        var failChannelMessages: Boolean = false
    ) : SharedThreadNetworkApi {
        var channelMessageCalls: Int = 0
        var failReadAll: Boolean = false
        val readAllChannels: MutableList<String> = mutableListOf()
        val beforeSeqs: MutableList<Long> = mutableListOf()
        val syncSinceSeqs: MutableList<Long> = mutableListOf()
        val channelIds: MutableList<String> = mutableListOf()
        var lastAttachmentIds: List<String> = emptyList()
        val savedMessageIds: MutableList<String> = mutableListOf()
        val updatedTaskStatuses: MutableList<Pair<String, TaskStatus>> = mutableListOf()
        val createdThreads: MutableList<Pair<String, String>> = mutableListOf()

        override suspend fun channelMessages(threadId: String, limit: Int, beforeSeq: Long?): HomeNetworkResponse {
            channelMessageCalls += 1
            channelIds += threadId
            if (failChannelMessages) throw IllegalStateException("offline")
            return if (beforeSeq != null) {
                beforeSeqs += beforeSeq
                HomeNetworkResponse(code = 200, bodyText = olderBodyText)
            } else {
                HomeNetworkResponse(code = 200, bodyText = bodyText)
            }
        }

        override suspend fun messageSync(channelId: String, sinceSeq: Long, limit: Int): HomeNetworkResponse {
            syncSinceSeqs += sinceSeq
            return HomeNetworkResponse(code = 200, bodyText = newerBodyText)
        }

        override suspend fun sendMessage(
            channelId: String,
            body: String,
            asTask: Boolean,
            attachmentIds: List<String>
        ): HomeNetworkResponse {
            lastAttachmentIds = attachmentIds
            return HomeNetworkResponse(code = 200, bodyText = messagesResponse(body))
        }

        override suspend fun attachmentUrl(attachmentId: String): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = """{"url":"https://cdn.example/$attachmentId"}""")

        override suspend fun markChannelReadAll(channelId: String): HomeNetworkResponse {
            readAllChannels += channelId
            return if (failReadAll) {
                HomeNetworkResponse(code = 500, bodyText = null, errorBodyText = "read_failed")
            } else {
                HomeNetworkResponse(code = 200, bodyText = "{}")
            }
        }

        override suspend fun saveMessage(messageId: String): HomeNetworkResponse {
            savedMessageIds += messageId
            return HomeNetworkResponse(code = 200, bodyText = """{"id":"$messageId","saved":true}""")
        }

        override suspend fun updateTaskStatus(taskId: String, status: TaskStatus): HomeNetworkResponse {
            updatedTaskStatuses += taskId to status
            return HomeNetworkResponse(code = 200, bodyText = """{"id":"$taskId","status":"${status.name}"}""")
        }

        override suspend fun createOrOpenThread(channelId: String, parentMessageId: String): HomeNetworkResponse {
            createdThreads += channelId to parentMessageId
            return HomeNetworkResponse(code = 200, bodyText = """{"threadChannelId":"thread-$parentMessageId","parentMessageId":"$parentMessageId","created":true}""")
        }
    }

    private fun basePayload(): KuiklyThreadScreenPayload =
        KuiklyThreadScreenPayload(
            instanceId = "shared-shell-thread-channel-1",
            title = "general",
            sourceThreadId = "channel-1",
            status = "loading",
            canSend = true
        )

    private fun contentPayload(body: String): KuiklyThreadScreenPayload =
        buildSharedThreadPayloadFromMessagesResponse(
            basePayload = basePayload(),
            bodyText = messagesResponse(body),
            session = sampleSession()
        )

    private fun sampleSession(): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "u1", email = "artin@slock.ai", displayName = "Artin"),
            accessToken = "access",
            refreshToken = "refresh"
        )

    private fun member(id: String, name: String): MemberProfile =
        MemberProfile(
            id = MemberId(id),
            displayName = name,
            handle = name.lowercase(),
            kind = MemberKind.Human,
            presence = Presence.Active
        )

    companion object {
        private fun messagesResponse(
            body: String,
            id: String = "m1",
            seq: Long = 10,
            historyLimited: Boolean = true
        ): String =
            """
            {
              "historyLimited": $historyLimited,
              "messages": [
                {
                  "id": "$id",
                  "seq": $seq,
                  "content": "$body",
                  "createdAt": "2026-05-19T16:00:00Z",
                  "senderId": "u1",
                  "senderName": "Artin",
                  "senderType": "user"
                }
              ]
            }
            """.trimIndent()

        private fun syncResponse(
            body: String,
            id: String = "m1",
            seq: Long = 10
        ): String =
            """
            [
              {
                "id": "$id",
                "seq": $seq,
                "content": "$body",
                "createdAt": "2026-05-19T16:00:00Z",
                "senderId": "u1",
                "senderName": "Artin",
                "senderType": "user"
              }
            ]
            """.trimIndent()
    }
}
