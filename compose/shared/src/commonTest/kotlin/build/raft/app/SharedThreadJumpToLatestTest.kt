package build.raft.app

import build.raft.app.data.DataRequestPriority
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.SharedThreadNetworkApi
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedThreadJumpToLatestTest {
    @Test
    fun focusedWindowLoadsCanonicalTailOnceInsteadOfWalkingNewerPages() = runPortableCoroutineTest {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + Dispatchers.Unconfined)
        val api = TailNetworkApi()
        val window =
            RaftThreadScreenPayload(
                instanceId = "shared-shell-thread-channel-1",
                sourceThreadId = "channel-1",
                streamChannelId = "channel-1",
                status = "content",
                messages = listOf(message(id = "m40", seq = 40L, body = "focused window")),
                focusedMessageId = "m40",
                canLoadMoreBefore = true,
                canLoadMoreAfter = true,
                canSend = true
            )
        val store =
            SharedThreadStore(
                dataSource = dataSource(api),
                scope = scope,
                initialPayload = window
            )

        try {
            val jump = scope.async { store.jumpToLatest() }
            api.tailRequestStarted.await()
            assertTrue(store.state.value.payload.backgroundRefreshing)
            assertNull(store.state.value.payload.focusedMessageId)
            assertEquals(emptyList(), api.afterSeqs)
            api.releaseTailRequest.complete(Unit)

            assertTrue(jump.await())
            assertEquals(1, api.tailRequestCount)
            assertEquals(emptyList(), api.afterSeqs)
            assertEquals("m210", store.state.value.payload.messages.last().id)
            assertFalse(store.state.value.payload.canLoadMoreAfter)
            assertNull(store.state.value.payload.focusedMessageId)
        } finally {
            scopeJob.cancelAndJoin()
        }
    }

    @Test
    fun canonicalTailClearsFocusWithoutAnotherNetworkRequest() = runPortableCoroutineTest {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + Dispatchers.Unconfined)
        val api = TailNetworkApi()
        val store =
            SharedThreadStore(
                dataSource = dataSource(api),
                scope = scope,
                initialPayload =
                    RaftThreadScreenPayload(
                        instanceId = "shared-shell-thread-channel-1",
                        sourceThreadId = "channel-1",
                        streamChannelId = "channel-1",
                        status = "content",
                        messages = listOf(message(id = "m210", seq = 210L, body = "canonical tail")),
                        focusedMessageId = "m210",
                        canLoadMoreBefore = true,
                        canLoadMoreAfter = false,
                        canSend = true
                    )
            )

        try {
            assertTrue(store.jumpToLatest())
            assertEquals(0, api.tailRequestCount)
            assertNull(store.state.value.payload.focusedMessageId)
            assertFalse(store.state.value.payload.canLoadMoreAfter)
        } finally {
            scopeJob.cancelAndJoin()
        }
    }

    private fun dataSource(api: SharedThreadNetworkApi): SharedThreadDataSource =
        testSharedThreadDataSource(
            networkApi = api,
            sessionStore =
                InMemoryRaftAuthSessionStore(
                    StoredAuthSession(
                        user = StoredAuthUser("u1", "artin@example.test", "Artin"),
                        accessToken = "access",
                        refreshToken = "refresh",
                        selectedServerId = "server-1"
                    )
                ),
            cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
        )

    private fun message(id: String, seq: Long, body: String): RaftThreadMessageItem =
        RaftThreadMessageItem(
            id = id,
            authorId = "u1",
            authorName = "Artin",
            seq = seq,
            body = body
        )

    private class TailNetworkApi : SharedThreadNetworkApi {
        val tailRequestStarted = CompletableDeferred<Unit>()
        val releaseTailRequest = CompletableDeferred<Unit>()
        val afterSeqs = mutableListOf<Long>()
        var tailRequestCount = 0

        override suspend fun channelMessages(
            threadId: String,
            limit: Int,
            beforeSeq: Long?,
            afterSeq: Long?
        ): HomeNetworkResponse {
            if (afterSeq != null) {
                afterSeqs += afterSeq
                return HomeNetworkResponse(code = 500, bodyText = "{}")
            }
            check(beforeSeq == null)
            tailRequestCount += 1
            tailRequestStarted.complete(Unit)
            releaseTailRequest.await()
            return HomeNetworkResponse(code = 200, bodyText = tailResponse())
        }

        override suspend fun messageSync(channelId: String, sinceSeq: Long, limit: Int) = unused()
        override suspend fun sendMessage(
            channelId: String,
            body: String,
            asTask: Boolean,
            attachmentIds: List<String>,
            randomId: String?
        ) = unused()
        override suspend fun executePendingMentionActions(action: String, resolutionIds: List<String>) = unused()
        override suspend fun attachmentUrl(priority: DataRequestPriority, attachmentId: String, disposition: String?) = unused()
        override suspend fun attachmentHtmlPreviewUrl(attachmentId: String) = unused()
        override suspend fun attachmentComments(attachmentId: String) = unused()
        override suspend fun sendAttachmentComment(attachmentId: String, body: String) = unused()
        override suspend fun markChannelReadToSeq(channelId: String, seq: Long) = unused()
        override suspend fun markChannelReadAll(channelId: String) = unused()
        override suspend fun saveMessage(messageId: String) = unused()
        override suspend fun unsaveMessage(messageId: String) = unused()
        override suspend fun addReaction(messageId: String, emoji: String) = unused()
        override suspend fun removeReaction(messageId: String, emoji: String) = unused()
        override suspend fun createOrOpenThread(channelId: String, parentMessageId: String) = unused()
        override suspend fun messageContext(messageId: String, channelId: String?) = unused()

        private fun unused(): HomeNetworkResponse = error("unused")

        private fun tailResponse(): String =
            """
            {
              "historyLimited": true,
              "messages": [
                {
                  "id": "m210",
                  "seq": 210,
                  "channelId": "channel-1",
                  "content": "canonical tail",
                  "createdAt": "2026-07-30T03:00:00Z",
                  "senderId": "u1",
                  "senderName": "Artin",
                  "senderType": "user",
                  "messageType": "chat"
                }
              ]
            }
            """.trimIndent()
    }
}
