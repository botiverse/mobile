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

    private class RecordingReadReceiptNetworkClient : RaftNetworkClient {
        val calls = mutableListOf<ReadReceiptNetworkCall>()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?
        ): HomeNetworkResponse {
            calls += ReadReceiptNetworkCall(method, path, serverIdOverride, bodyText)
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
