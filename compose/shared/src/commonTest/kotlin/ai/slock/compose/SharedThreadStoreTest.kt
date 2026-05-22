package ai.slock.compose

import ai.slock.compose.data.HomeNetworkResponse
import ai.slock.compose.data.ComposeLocalWriteThrough
import ai.slock.compose.data.InMemoryComposeLocalDataStore
import ai.slock.compose.data.InMemorySlockAuthSessionStore
import ai.slock.compose.data.SharedThreadNetworkApi
import ai.slock.compose.data.StoredAuthSession
import ai.slock.compose.data.StoredAuthUser
import ai.slock.compose.data.ThreadSnapshot
import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.ConversationSummary
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.Message
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.Presence
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
                api = api,
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
                api = api,
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
                api = api,
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
                api = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                localWriteThrough = ComposeLocalWriteThrough(localStore)
            )

        dataSource.fetchThreadPayload(basePayload())

        val cached = localStore.readThread(ThreadId("channel-1"))
        assertEquals("general", cached?.conversation?.name)
        assertEquals("network body", cached?.messages?.single()?.body)
        assertEquals("Artin", cached?.messages?.single()?.author?.displayName)
    }

    private class FakeThreadApi(
        var bodyText: String = messagesResponse("network body"),
        var failChannelMessages: Boolean = false
    ) : SharedThreadNetworkApi {
        var channelMessageCalls: Int = 0

        override suspend fun channelMessages(threadId: String, limit: Int): HomeNetworkResponse {
            channelMessageCalls += 1
            if (failChannelMessages) throw IllegalStateException("offline")
            return HomeNetworkResponse(code = 200, bodyText = bodyText)
        }

        override suspend fun sendMessage(channelId: String, body: String, asTask: Boolean): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = messagesResponse(body))

        override suspend fun attachmentUrl(attachmentId: String): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = """{"url":"https://cdn.example/$attachmentId"}""")
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
        private fun messagesResponse(body: String): String =
            """
            {
              "data": {
                "messages": [
                  {
                    "id": "m1",
                    "body": "$body",
                    "createdAt": "2026-05-19T16:00:00Z",
                    "author": {"id": "u1", "displayName": "Artin"}
                  }
                ]
              }
            }
            """.trimIndent()
    }
}
