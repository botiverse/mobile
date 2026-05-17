package ai.slock.android.core.data.realtime

import ai.slock.android.core.data.auth.AuthServer
import ai.slock.android.core.data.auth.AuthSession
import ai.slock.android.core.data.auth.AuthSessionStore
import ai.slock.android.core.data.auth.AuthUser
import ai.slock.android.core.data.auth.AuthTokenRefresher
import ai.slock.android.core.data.auth.AuthApi
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import ai.slock.compose.data.DataResult
import ai.slock.android.core.data.repository.RealtimeSubscription
import ai.slock.android.core.model.AgentStatus
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.Heartbeat
import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.MemberPresenceUpdated
import ai.slock.android.core.model.MessageNew
import ai.slock.android.core.model.MessageType
import ai.slock.android.core.model.Presence
import ai.slock.android.core.model.RealtimeEvent
import ai.slock.android.core.model.ServerId
import ai.slock.android.core.model.SyncResumeResponse
import ai.slock.android.core.model.TaskStatus
import ai.slock.android.core.model.TaskUpdated
import ai.slock.android.core.model.ThreadUpdated
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ai.slock.android.core.data.network.ApiResponse

class RealtimeRepositoryTest {
    @Test
    fun repositoryInjectsSessionTokenAndSelectedServerIntoRealtimeClient() {
        val transport = FakeRealtimeSocketTransport()
        val repository = SessionRealtimeRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            client = SocketIoRealtimeClient(transport)
        )

        val result = runSuspend { repository.connect(ServerId("server-1"), lastSeq = 42) }

        assertTrue(result is DataResult.Content)
        assertEquals("sample-access", transport.lastAuth["token"])
        assertEquals("server-1", transport.lastAuth["serverId"])

        transport.receive(RealtimeSocketEvents.RoomsJoined)

        assertEquals(RealtimeSocketEvents.SyncResume, transport.emitted.single().first)
        assertEquals(42L, transport.emitted.single().second["lastSeq"])
    }

    @Test
    fun repositoryRejectsMismatchedServerWithoutConnectingTransport() {
        val transport = FakeRealtimeSocketTransport()
        val repository = SessionRealtimeRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            client = SocketIoRealtimeClient(transport)
        )

        val result = runSuspend { repository.connect(ServerId("server-2"), lastSeq = 0) }

        assertTrue(result is DataResult.NoPermission)
        assertTrue(transport.lastAuth.isEmpty())
    }

    @Test
    fun repositoryRefreshesTokenBeforeRealtimeConnectWhenTokenIsNotFresh() {
        val transport = FakeRealtimeSocketTransport()
        val store = InMemoryAuthSessionStore(sampleSession.copy(accessToken = "opaque-access"))
        val repository = SessionRealtimeRepository(
            store = store,
            client = SocketIoRealtimeClient(transport),
            tokenRefresher = AuthTokenRefresher(
                store = store,
                api = FakeAuthApi(),
                logger = {},
                warningLogger = {}
            )
        )

        val result = runSuspend { repository.connect(ServerId("server-1"), lastSeq = 0) }

        assertTrue(result is DataResult.Content)
        assertEquals("socket-new-access", transport.lastAuth["token"])
        assertEquals("socket-new-access", store.session?.accessToken)
    }

    @Test
    fun repositoryDoesNotFallbackToStaleTokenWhenRefreshIsCancelled() {
        val transport = FakeRealtimeSocketTransport()
        val store = InMemoryAuthSessionStore(sampleSession.copy(accessToken = "opaque-access"))
        val repository = SessionRealtimeRepository(
            store = store,
            client = SocketIoRealtimeClient(transport),
            tokenRefresher = AuthTokenRefresher(
                store = store,
                api = FakeAuthApi(refreshFailure = CancellationException("cancelled")),
                logger = {},
                warningLogger = {}
            )
        )
        var cancelled = false

        try {
            runSuspend { repository.connect(ServerId("server-1"), lastSeq = 0) }
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertTrue(transport.lastAuth.isEmpty())
        assertEquals("opaque-access", store.session?.accessToken)
    }

    @Test
    fun clientMapsHeartbeatResumeMessagesAndJoinChannel() {
        val transport = FakeRealtimeSocketTransport()
        val client = SocketIoRealtimeClient(transport)
        val events = mutableListOf<RealtimeEvent>()
        client.observeEvents { events += it }

        client.connect(
            RealtimeConnectRequest(
                auth = RealtimeAuth(token = "sample-access", serverId = ServerId("server-1"))
            )
        )
        client.joinChannel(ChannelId("channel-1"))
        transport.receive(RealtimeSocketEvents.Heartbeat, mapOf("seq" to 12))
        transport.receive(
            RealtimeSocketEvents.MessageNew,
            mapOf(
                "message" to mapOf(
                    "id" to "message-1",
                    "threadId" to "thread-1",
                    "channelId" to "channel-1",
                    "seq" to 13,
                    "body" to "Hello",
                    "author" to mapOf("id" to "user-1", "displayName" to "Artin", "email" to "artin@slock.ai")
                )
            )
        )
        transport.receive(
            RealtimeSocketEvents.SyncResumeResponse,
            mapOf(
                "currentSeq" to 14,
                "hasMore" to false,
                "messages" to listOf(
                    mapOf(
                        "id" to "message-2",
                        "threadId" to "thread-1",
                        "channelId" to "channel-1",
                        "seq" to 14,
                        "content" to "Resumed",
                        "authorId" to "user-2",
                        "authorName" to "Cindy"
                    )
                )
            )
        )

        assertEquals(RealtimeSocketEvents.JoinChannel, transport.emitted.single().first)
        assertEquals("channel-1", transport.emitted.single().second["channelId"])
        assertTrue(events[0] is Heartbeat)
        assertEquals(12L, (events[0] as Heartbeat).seq)
        assertEquals("Hello", (events[1] as MessageNew).message.body)
        assertEquals(14L, (events[2] as SyncResumeResponse).currentSeq)
        assertEquals("Resumed", (events[2] as SyncResumeResponse).messages.single().body)
    }

    @Test
    fun clientReplaysJoinedChannelsAfterConnectButNotRoomsJoinedAck() {
        val transport = FakeRealtimeSocketTransport()
        val client = SocketIoRealtimeClient(transport)

        client.connect(
            RealtimeConnectRequest(
                auth = RealtimeAuth(token = "sample-access", serverId = ServerId("server-1"))
            )
        )
        client.joinChannel(ChannelId("channel-1"))
        client.joinChannel(ChannelId("channel-2"))
        transport.emitted.clear()

        transport.receive(RealtimeSocketEvents.Connect)

        assertEquals(
            listOf("channel-1", "channel-2"),
            transport.emitted.map { it.second["channelId"] }
        )
        transport.emitted.clear()

        transport.receive(RealtimeSocketEvents.RoomsJoined)

        assertTrue(transport.emitted.isEmpty())
    }

    @Test
    fun clientDoesNotEmitDuplicateJoinForAlreadyJoinedChannel() {
        val transport = FakeRealtimeSocketTransport()
        val client = SocketIoRealtimeClient(transport)

        client.connect(
            RealtimeConnectRequest(
                auth = RealtimeAuth(token = "sample-access", serverId = ServerId("server-1"))
            )
        )
        client.joinChannel(ChannelId("channel-1"))
        client.joinChannel(ChannelId("channel-1"))
        client.joinChannel(ChannelId("channel-1"))

        assertEquals(
            listOf("channel-1"),
            transport.emitted.map { it.second["channelId"] }
        )
    }

    @Test
    fun clientReplaysRoomsAndResumeAfterReconnect() {
        val transport = FakeRealtimeSocketTransport()
        val client = SocketIoRealtimeClient(transport)
        val events = mutableListOf<RealtimeEvent>()
        client.observeEvents { events += it }

        client.connect(
            RealtimeConnectRequest(
                auth = RealtimeAuth(token = "sample-access", serverId = ServerId("server-1"))
            )
        )
        client.joinChannel(ChannelId("channel-1"))
        client.joinChannel(ChannelId("thread-1"))
        transport.receive(RealtimeSocketEvents.Heartbeat, mapOf("seq" to 88))
        transport.emitted.clear()

        transport.receive(RealtimeSocketEvents.Reconnect)

        val joinRooms =
            transport.emitted
                .filter { it.first == RealtimeSocketEvents.JoinChannel }
                .map { it.second["channelId"] }
        val resume = transport.emitted.single { it.first == RealtimeSocketEvents.SyncResume }
        assertEquals(listOf("channel-1", "thread-1"), joinRooms)
        assertEquals(88L, resume.second["lastSeq"])
        assertTrue(events.single() is Heartbeat)
    }

    @Test
    fun clientUsesMessageSequenceWhenResumingAfterReconnect() {
        val transport = FakeRealtimeSocketTransport()
        val client = SocketIoRealtimeClient(transport)

        client.connect(
            RealtimeConnectRequest(
                auth = RealtimeAuth(token = "sample-access", serverId = ServerId("server-1"))
            )
        )
        client.joinChannel(ChannelId("channel-1"))
        transport.receive(
            RealtimeSocketEvents.MessageNew,
            mapOf(
                "message" to mapOf(
                    "id" to "message-1",
                    "threadId" to "channel-1",
                    "channelId" to "channel-1",
                    "seq" to 89,
                    "content" to "Realtime body"
                )
            )
        )
        transport.emitted.clear()

        transport.receive(RealtimeSocketEvents.Reconnect)

        val resume = transport.emitted.single { it.first == RealtimeSocketEvents.SyncResume }
        assertEquals(89L, resume.second["lastSeq"])
    }

    @Test
    fun clientClearsJoinedChannelsWhenServerChanges() {
        val transport = FakeRealtimeSocketTransport()
        val client = SocketIoRealtimeClient(transport)

        client.connect(
            RealtimeConnectRequest(
                auth = RealtimeAuth(token = "sample-access", serverId = ServerId("server-1"))
            )
        )
        client.joinChannel(ChannelId("channel-1"))
        transport.emitted.clear()

        client.connect(
            RealtimeConnectRequest(
                auth = RealtimeAuth(token = "sample-access", serverId = ServerId("server-2"))
            )
        )
        transport.receive(RealtimeSocketEvents.Connect)

        assertTrue(transport.emitted.isEmpty())
    }

    @Test
    fun clientDropsEventsWhosePayloadServerDoesNotMatchConnectedServer() {
        val transport = FakeRealtimeSocketTransport()
        val client = SocketIoRealtimeClient(transport)
        val events = mutableListOf<RealtimeEvent>()
        client.observeEvents { events += it }

        client.connect(
            RealtimeConnectRequest(
                auth = RealtimeAuth(token = "sample-access", serverId = ServerId("server-1"))
            )
        )
        transport.receive(
            RealtimeSocketEvents.MessageNew,
            mapOf(
                "serverId" to "server-2",
                "message" to mapOf(
                    "id" to "message-1",
                    "threadId" to "thread-1",
                    "channelId" to "channel-1",
                    "body" to "Wrong server",
                    "author" to mapOf("id" to "user-1", "displayName" to "Artin")
                )
            )
        )

        assertTrue(events.isEmpty())
    }

    @Test
    fun mapperHandlesTaskAndThreadEvents() {
        val mapper = RealtimeEventMapper()

        val thread = mapper.map(
            RealtimeSocketEvents.ThreadUpdated,
            mapOf(
                "message_id" to "message-1",
                "thread_id" to "thread-channel-1",
                "child_count" to 3,
                "participantIds" to listOf("user-1", "agent-1")
            )
        )
        val task = mapper.map(
            RealtimeSocketEvents.TaskUpdated,
            mapOf(
                "task" to mapOf(
                    "id" to "task-1",
                    "title" to "Review Android auth",
                    "status" to "in_review",
                    "taskNumber" to 118
                )
            )
        )

        assertTrue(thread is ThreadUpdated)
        assertEquals("message-1", (thread as ThreadUpdated).parentMessageId.value)
        assertEquals("thread-channel-1", thread.threadChannelId.value)
        assertEquals(3, thread.replyCount)
        assertTrue(task is TaskUpdated)
        assertEquals(118, (task as TaskUpdated).task.taskNumber)
    }

    @Test
    fun mapperPreservesMessageSequenceAliases() {
        val mapper = RealtimeEventMapper()

        val event =
            mapper.map(
                RealtimeSocketEvents.MessageNew,
                mapOf(
                    "message" to
                        mapOf(
                            "id" to "message-1",
                            "channelId" to "channel-1",
                            "threadId" to "channel-1",
                            "logSeq" to 42,
                            "content" to "Hello"
                        )
                )
            )

        assertTrue(event is MessageNew)
        assertEquals(42L, (event as MessageNew).message.seq)
    }

    @Test
    fun mapperHandlesRealServerTaskShapeInRealtimeTaskEvents() {
        val mapper = RealtimeEventMapper()

        val task = mapper.map(
            RealtimeSocketEvents.TaskUpdated,
            mapOf(
                "task" to mapOf(
                    "id" to "message-task-1",
                    "title" to "Open replies from Tasks",
                    "status" to "closed",
                    "channelType" to "channel",
                    "channelId" to "parent-channel-1",
                    "messageId" to "parent-message-1",
                    "threadId" to "reply-thread-1",
                    "claimedById" to "agent-1",
                    "claimedByName" to "Android-App-Integrator",
                    "claimedByType" to "agent"
                )
            )
        )

        assertTrue(task is TaskUpdated)
        val summary = (task as TaskUpdated).task
        assertEquals(TaskStatus.Closed, summary.status)
        assertEquals("parent-channel-1", summary.channelId?.value)
        assertEquals("parent-message-1", summary.messageId?.value)
        assertEquals("parent-message-1", summary.parentMessageId?.value)
        assertEquals("reply-thread-1", summary.threadId?.value)
        assertTrue(summary.isThreadTask)
        assertEquals(MemberKind.Agent, summary.assignee?.kind)
    }

    @Test
    fun mapperUsesNestedRichTaskMessageTextWhenTitleIsOnlyNumber() {
        val mapper = RealtimeEventMapper()

        val task = mapper.map(
            RealtimeSocketEvents.TaskUpdated,
            mapOf(
                "task" to mapOf(
                    "id" to "message-task-2",
                    "title" to "#24",
                    "taskNumber" to 24,
                    "status" to "todo",
                    "sourceMessage" to mapOf(
                        "content" to listOf(
                            mapOf(
                                "type" to "paragraph",
                                "children" to listOf(
                                    mapOf("text" to "Restore realtime task message details")
                                )
                            )
                        )
                    )
                )
            )
        )

        assertTrue(task is TaskUpdated)
        assertEquals("Restore realtime task message details", (task as TaskUpdated).task.title)
    }

    @Test
    fun mapperHandlesMemberPresenceEvents() {
        val mapper = RealtimeEventMapper()

        val agentActivity = mapper.map(
            RealtimeSocketEvents.AgentActivity,
            mapOf(
                "agentId" to "agent-1",
                "activity" to "working",
                "detail" to "Running tests"
            )
        )
        val memberUpdated = mapper.map(
            RealtimeSocketEvents.ServerMemberUpdated,
            mapOf(
                "member" to mapOf(
                    "id" to "user-2",
                    "displayName" to "Cindy",
                    "presence" to "away"
                )
            )
        )

        assertTrue(agentActivity is MemberPresenceUpdated)
        val agent = agentActivity as MemberPresenceUpdated
        assertEquals("agent-1", agent.memberId.value)
        assertEquals(MemberKind.Agent, agent.kind)
        assertEquals(AgentStatus.Working, agent.agentStatus)
        assertEquals(Presence.Active, agent.presence)
        assertEquals("Running tests", agent.statusLabel)
        assertTrue(memberUpdated is MemberPresenceUpdated)
        assertEquals(Presence.Away, (memberUpdated as MemberPresenceUpdated).presence)

        val disconnected = mapper.map(
            RealtimeSocketEvents.AgentActivity,
            mapOf(
                "agentId" to "agent-1",
                "activity" to "disconnected"
            )
        ) as MemberPresenceUpdated
        assertEquals(AgentStatus.Disconnected, disconnected.agentStatus)
        assertEquals(Presence.Offline, disconnected.presence)
    }

    @Test
    fun mapperHandlesSocketIoJsonObjectPayloads() {
        val mapper = RealtimeEventMapper()
        val event = mapper.map(
            RealtimeSocketEvents.MessageNew,
            JSONObject(
                """
                {
                  "message": {
                    "id": "message-json",
                    "threadId": "thread-json",
                    "channelId": "channel-json",
                    "seq": 77,
                    "content": "Socket payload",
                    "createdByUser": {
                      "id": "user-json",
                      "displayName": "Socket User",
                      "email": "socket@slock.ai"
                    }
                  }
                }
                """.trimIndent()
            )
        )

        assertTrue(event is MessageNew)
        val message = (event as MessageNew).message
        assertEquals("message-json", message.id.value)
        assertEquals("thread-json", message.threadId.value)
        assertEquals("channel-json", message.channelId?.value)
        assertEquals(77L, message.seq)
        assertEquals("Socket payload", message.body)
        assertEquals("Socket User", message.author.displayName)
    }

    @Test
    fun mapperPreservesSocketIoMessageAttachments() {
        val mapper = RealtimeEventMapper()
        val event = mapper.map(
            RealtimeSocketEvents.MessageNew,
            JSONObject(
                """
                {
                  "message": {
                    "id": "message-with-attachments",
                    "threadId": "thread-json",
                    "channelId": "channel-json",
                    "content": "Images from socket",
                    "attachments": [
                      {
                        "attachment_id": "image-1",
                        "filename": "preview.png",
                        "mime_type": "image/png",
                        "sizeLabel": "42 KB",
                        "thumbnailUrl": "https://cdn.example/thumbs/image-1.png"
                      },
                      {
                        "file": {
                          "id": "file-1",
                          "name": "report.pdf",
                          "contentType": "application/pdf",
                          "size_bytes": 2048
                        }
                      }
                    ]
                  }
                }
                """.trimIndent()
            )
        )

        assertTrue(event is MessageNew)
        val message = (event as MessageNew).message
        assertEquals(2, message.attachments.size)
        assertEquals("image-1", message.attachments[0].id.value)
        assertEquals("preview.png", message.attachments[0].name)
        assertEquals("image/png", message.attachments[0].mimeType)
        assertEquals("42 KB", message.attachments[0].sizeLabel)
        assertEquals("https://cdn.example/thumbs/image-1.png", message.attachments[0].thumbnailUrl)
        assertEquals("file-1", message.attachments[1].id.value)
        assertEquals("report.pdf", message.attachments[1].name)
        assertEquals("application/pdf", message.attachments[1].mimeType)
        assertEquals("2048 B", message.attachments[1].sizeLabel)
    }

    @Test
    fun mapperPreservesTopLevelReplyThreadFromRawThreadId() {
        val mapper = RealtimeEventMapper()
        val event = mapper.map(
            RealtimeSocketEvents.MessageNew,
            mapOf(
                "id" to "c41039c4-1111-4222-8333-0123456789ab",
                "channelId" to "android-dev-channel",
                "threadId" to "a98ec1f2-1111-4222-8333-fedcba987654",
                "content" to "Android notifications",
                "replyCount" to 7
            )
        )

        assertTrue(event is MessageNew)
        val message = (event as MessageNew).message
        assertEquals("android-dev-channel", message.threadId.value)
        assertEquals("a98ec1f2-1111-4222-8333-fedcba987654", message.threadChannelId?.value)
        assertEquals(7, message.replyCount)
    }

    @Test
    fun mapperHandlesNestedAgentAuthorNames() {
        val mapper = RealtimeEventMapper()
        val event = mapper.map(
            RealtimeSocketEvents.MessageNew,
            JSONObject(
                """
                {
                  "message": {
                    "id": "message-agent",
                    "threadId": "thread-agent",
                    "channelId": "channel-agent",
                    "content": "Agent payload",
                    "agent": {
                      "agentId": "agent-1",
                      "profile": {
                        "displayName": "API Integration",
                        "username": "api-integration",
                        "avatar": {"type": "pixel", "key": "robot"}
                      },
                      "presence": "working"
                    }
                  }
                }
                """.trimIndent()
            )
        )

        assertTrue(event is MessageNew)
        val message = (event as MessageNew).message
        assertEquals("API Integration", message.author.displayName)
        assertEquals("api-integration", message.author.handle)
        assertEquals(MemberKind.Agent, message.author.kind)
        assertEquals("pixel:robot", message.author.avatar?.wireValue)
        assertEquals(Presence.Active, message.author.presence)
    }

    @Test
    fun mapperUsesSenderDescriptionFromMessageContract() {
        val mapper = RealtimeEventMapper()
        val event = mapper.map(
            RealtimeSocketEvents.MessageNew,
            JSONObject(
                """
                {
                  "message": {
                    "id": "message-agent-description",
                    "threadId": "thread-agent",
                    "channelId": "channel-agent",
                    "content": "Agent payload",
                    "senderType": "agent",
                    "senderId": "agent-1",
                    "senderName": "API Integration",
                    "senderDescription": "Data contracts",
                    "roleDescription": "agent"
                  }
                }
                """.trimIndent()
            )
        )

        assertTrue(event is MessageNew)
        val message = (event as MessageNew).message
        assertEquals("API Integration", message.author.displayName)
        assertEquals("Data contracts", message.author.description)
        assertEquals("agent", message.author.roleDescription)
        assertEquals(MemberKind.Agent, message.author.kind)
    }

    @Test
    fun mapperPreservesSocketIoSystemMessageMetadata() {
        val mapper = RealtimeEventMapper()
        val event = mapper.map(
            RealtimeSocketEvents.MessageNew,
            JSONObject(
                """
                {
                  "message": {
                    "id": "system-message-json",
                    "threadId": "thread-json",
                    "channelId": "channel-json",
                    "seq": 78,
                    "messageType": "system",
                    "systemEventType": "task_moved",
                    "content": "Task moved to Review",
                    "metadata": {
                      "taskId": "task-267",
                      "from": "in_progress",
                      "to": "in_review"
                    }
                  }
                }
                """.trimIndent()
            )
        )

        assertTrue(event is MessageNew)
        val message = (event as MessageNew).message
        assertEquals(MessageType.System, message.messageType)
        assertEquals("task_moved", message.systemEventType)
        assertEquals("task-267", message.metadata["taskId"])
        assertEquals("in_review", message.metadata["to"])
    }

    private class FakeRealtimeSocketTransport : RealtimeSocketTransport {
        val handlers = mutableMapOf<String, MutableList<(Any?) -> Unit>>()
        var lastAuth: Map<String, String> = emptyMap()
        val emitted = mutableListOf<Pair<String, Map<String, Any>>>()

        override fun connect(auth: Map<String, String>) {
            lastAuth = auth
        }

        override fun disconnect() = Unit

        override fun emit(eventName: String, payload: Map<String, Any>) {
            emitted += eventName to payload
        }

        override fun on(eventName: String, listener: (Any?) -> Unit): RealtimeSubscription {
            handlers.getOrPut(eventName) { mutableListOf() } += listener
            return RealtimeSubscription { handlers[eventName]?.remove(listener) }
        }

        fun receive(eventName: String, payload: Any? = null) {
            handlers[eventName].orEmpty().forEach { it(payload) }
        }
    }

    private class InMemoryAuthSessionStore(
        var session: AuthSession?
    ) : AuthSessionStore {
        override suspend fun readSession(): AuthSession? = session

        override suspend fun saveSession(session: AuthSession) {
            this.session = session
        }

        override suspend fun updateTokens(accessToken: String, refreshToken: String) {
            session = session?.copy(accessToken = accessToken, refreshToken = refreshToken)
        }

        override suspend fun updateSelectedServer(serverId: String?, serverSlug: String?) {
            session = session?.copy(selectedServerId = serverId, selectedServerSlug = serverSlug)
        }

        override suspend fun clearSession() {
            session = null
        }
    }

    private class FakeAuthApi(
        private val refreshFailure: Throwable? = null
    ) : AuthApi {
        override suspend fun login(body: RequestBody): ApiResponse =
            ApiResponse.error(404, "".toResponseBody(json))

        override suspend fun register(body: RequestBody): ApiResponse =
            ApiResponse.error(404, "".toResponseBody(json))

        override suspend fun forgotPassword(body: RequestBody): ApiResponse =
            ApiResponse.error(404, "".toResponseBody(json))

        override suspend fun currentUser(authorization: String): ApiResponse =
            ApiResponse.error(404, "".toResponseBody(json))

        override suspend fun refresh(body: RequestBody): ApiResponse {
            refreshFailure?.let { throw it }
            return ApiResponse.success(
                """{"accessToken":"socket-new-access","refreshToken":"socket-new-refresh"}""".toResponseBody(json)
            )
        }

        override suspend fun logout(body: RequestBody): ApiResponse =
            ApiResponse.success("""{"ok":true}""".toResponseBody(json))

        override suspend fun servers(authorization: String): ApiResponse =
            ApiResponse.error(404, "".toResponseBody(json))

        override suspend fun createServer(
            authorization: String,
            body: RequestBody
        ): ApiResponse =
            ApiResponse.error(404, "".toResponseBody(json))
    }

    companion object {
        private val json = "application/json; charset=utf-8".toMediaType()

        private val sampleSession = AuthSession(
            user = AuthUser(
                id = "user-1",
                email = "user@slock.ai",
                name = "User",
                displayName = "Slock User",
                description = null,
                avatarUrl = null,
                emailVerified = true
            ),
            accessToken = "sample-access",
            refreshToken = "sample-refresh",
            selectedServerId = "server-1",
            selectedServerSlug = "slock-android",
            servers = listOf(
                AuthServer(
                    id = "server-1",
                    name = "Slock Android",
                    slug = "slock-android",
                    ownerId = "user-1",
                    onboardingAgentId = "agent-1",
                    plan = "team",
                    planDowngradedAt = null,
                    role = "owner",
                    createdAt = "2026-05-06T00:00:00Z"
                )
            )
        )
    }
}

private fun <T> runSuspend(block: suspend () -> T): T {
    var value: T? = null
    var failure: Throwable? = null
    block.startCoroutine(
        object : Continuation<T> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<T>) {
                result.onSuccess { value = it }
                result.onFailure { failure = it }
            }
        }
    )
    failure?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return value as T
}
