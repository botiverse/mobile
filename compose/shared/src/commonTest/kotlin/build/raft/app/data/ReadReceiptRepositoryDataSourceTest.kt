package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReadReceiptRepositoryDataSourceTest {
    @Test
    fun ownerAuthViewPinsAccountAndServerAndFailsClosedAfterAccountSwitch() = runBlocking {
        val delegate = InMemoryRaftAuthSessionStore(sampleSession())
        val owner = ReadReceiptOwner("production", "user-1", "server-b")
        val store = ReadReceiptOwnerAuthSessionStore(delegate, owner)

        assertEquals("user-1", store.readSession()?.user?.id)
        assertEquals("server-b", store.readSession()?.currentServerId)

        delegate.replaceAuthenticatedSession(
            sampleSession().copy(
                user = StoredAuthUser(id = "user-2", email = "user-2@slock.local", displayName = "User Two")
            )
        )

        assertEquals(null, store.readSession())
    }

    @Test
    fun ownerScopedReadUsesExplicitServerInsteadOfProcessSelection() = runBlocking {
        val network = RecordingReadReceiptNetworkClient()
        val repository =
            RealReadReceiptRepository(
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                networkClient = network
            )

        val allResult = repository.markChannelReadAll(ServerId("server-b"), ChannelId("channel-1"))
        val seqResult = repository.markChannelReadToSeq(ServerId("server-b"), ChannelId("channel-1"), 42L)

        assertTrue(allResult is DataResult.Content)
        assertTrue(seqResult is DataResult.Content)
        assertEquals(ReadReceiptResult(ChannelId("channel-1"), seq = 44, readStateVersion = 9, maxReadSeq = 44), allResult.data)
        assertEquals(ReadReceiptResult(ChannelId("channel-1"), seq = 42, readStateVersion = 10, maxReadSeq = 42), seqResult.data)
        assertEquals(
            listOf(
                ReadReceiptNetworkCall("POST", "channels/channel-1/read-all", ServerId("server-b"), null),
                ReadReceiptNetworkCall(
                    "POST",
                    "channels/channel-1/read",
                    ServerId("server-b"),
                    """{"seq":42}"""
                )
            ),
            network.calls
        )
    }

    /**
     * Wire vector from botiverse/slock d60367a1: `packages/server/src/routes/channels.ts`
     * `POST /:id/conversation-read-all` answers
     * `{ ok, serverId, markedCount, scopes: [{ scopeId, maxReadSeq, readStateVersion }] }` with no request
     * body (`packages/server/src/routes/readMutations.api.test.ts`, web `messageStore.markConversationRead`).
     */
    @Test
    fun conversationReadAllPostsWithoutBodyAtForegroundAndReadsEveryAckedScope() = runBlocking {
        val network =
            RecordingReadReceiptNetworkClient(
                conversationReadAllBody =
                    """{"ok":true,"serverId":"server-a","markedCount":2,"scopes":[""" +
                        """{"scopeId":"channel-1","maxReadSeq":1,"readStateVersion":3},""" +
                        """{"scopeId":"thread-1","maxReadSeq":2,"readStateVersion":1}]}"""
            )
        val repository =
            RealReadReceiptRepository(
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                networkClient = network
            )

        val result = repository.markConversationReadAll(ChannelId("channel-1"))

        assertTrue(result is DataResult.Content)
        assertEquals(
            listOf(
                ReadStateV1AckScope(ChannelId("channel-1"), readStateVersion = 3, maxReadSeq = 1),
                ReadStateV1AckScope(ChannelId("thread-1"), readStateVersion = 1, maxReadSeq = 2),
            ),
            result.data.scopes
        )
        assertEquals(
            listOf(ReadReceiptNetworkCall("POST", "channels/channel-1/conversation-read-all", ServerId("server-a"), null)),
            network.calls
        )
        assertEquals(listOf(DataRequestPriority.Foreground), network.priorities)
    }

    @Test
    fun conversationReadAllGateOffSurfacesAnErrorNotAnEmptyRead() = runBlocking {
        // The route answers 404 `sidebar_mark_all_read_disabled` while the rollout flag is off.
        val network =
            RecordingReadReceiptNetworkClient(
                conversationReadAllBody =
                    """{"error":"Conversation read-all is not enabled","code":"sidebar_mark_all_read_disabled"}""",
                conversationReadAllCode = 404
            )
        val repository =
            RealReadReceiptRepository(
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                networkClient = network
            )

        val result = repository.markConversationReadAll(ChannelId("channel-1"))

        assertTrue(result is DataResult.Error)
        assertEquals("http_404", result.error.code)
    }

    private class RecordingReadReceiptNetworkClient(
        private val conversationReadAllBody: String = "",
        private val conversationReadAllCode: Int = 200
    ) : RaftNetworkClient {
        val calls = mutableListOf<ReadReceiptNetworkCall>()
        val priorities = mutableListOf<DataRequestPriority>()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?
        ): HomeNetworkResponse {
            calls += ReadReceiptNetworkCall(method, path, serverIdOverride, bodyText)
            priorities += priority
            if (path.endsWith("conversation-read-all")) {
                return HomeNetworkResponse(code = conversationReadAllCode, bodyText = conversationReadAllBody)
            }
            val response =
                if (path.endsWith("read-all")) {
                    """{"ok":true,"seq":44,"readStateVersion":9}"""
                } else {
                    """{"ok":true,"maxReadSeq":42,"readStateVersion":10}"""
                }
            return HomeNetworkResponse(code = 200, bodyText = response)
        }
    }

    private data class ReadReceiptNetworkCall(
        val method: String,
        val path: String,
        val serverId: ServerId?,
        val bodyText: String?
    )

    private fun sampleSession(): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "user-1@slock.local", displayName = "User One"),
            accessToken = "access",
            refreshToken = "refresh",
            selectedServerId = "server-a",
            servers =
                listOf(
                    StoredAuthServer(id = "server-a", name = "Server A"),
                    StoredAuthServer(id = "server-b", name = "Server B")
                )
        )
}
