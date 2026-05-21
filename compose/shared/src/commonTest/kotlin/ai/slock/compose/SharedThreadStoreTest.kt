package ai.slock.compose

import ai.slock.compose.data.HomeNetworkResponse
import ai.slock.compose.data.InMemorySlockAuthSessionStore
import ai.slock.compose.data.SharedThreadNetworkApi
import ai.slock.compose.data.StoredAuthSession
import ai.slock.compose.data.StoredAuthUser
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
