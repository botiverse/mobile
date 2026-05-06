package ai.slock.android.core.data.realtime

import ai.slock.android.core.data.auth.AuthServer
import ai.slock.android.core.data.auth.AuthSession
import ai.slock.android.core.data.auth.AuthSessionStore
import ai.slock.android.core.data.auth.AuthUser
import ai.slock.android.core.data.repository.DataResult
import ai.slock.android.core.data.repository.RealtimeSubscription
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.RealtimeEvent
import ai.slock.android.core.model.ServerId
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertTrue(events[0] is RealtimeEvent.Heartbeat)
        assertEquals(12L, (events[0] as RealtimeEvent.Heartbeat).seq)
        assertEquals("Hello", (events[1] as RealtimeEvent.MessageNew).message.body)
        assertEquals(14L, (events[2] as RealtimeEvent.SyncResumeResponse).currentSeq)
        assertEquals("Resumed", (events[2] as RealtimeEvent.SyncResumeResponse).messages.single().body)
    }

    @Test
    fun mapperHandlesTaskAndThreadEvents() {
        val mapper = RealtimeEventMapper()

        val thread = mapper.map(
            RealtimeSocketEvents.ThreadUpdated,
            mapOf(
                "parentMessageId" to "message-1",
                "threadChannelId" to "thread-channel-1",
                "replyCount" to 3,
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

        assertTrue(thread is RealtimeEvent.ThreadUpdated)
        assertEquals(3, (thread as RealtimeEvent.ThreadUpdated).replyCount)
        assertTrue(task is RealtimeEvent.TaskUpdated)
        assertEquals(118, (task as RealtimeEvent.TaskUpdated).task.taskNumber)
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
        private var session: AuthSession?
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

    companion object {
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
