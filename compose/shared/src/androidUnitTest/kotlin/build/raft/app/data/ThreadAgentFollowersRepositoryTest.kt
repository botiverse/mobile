package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import build.raft.app.network.MultipartFile
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ThreadAgentFollowersRepositoryTest {
    @Test fun getRemoveAndRestoreUseBoundAuthorityExactRoutesAndTypedBody() = runTest {
        val auth = KeyValueAuthSessionStore(InMemorySlockKeyValueStore())
        auth.saveSession(authSessionFixture())
        val network = RecordingFollowerNetwork()
        val repository = repository(network, auth)
        network.response = HomeNetworkResponse(200, """{"threads":[{"threadChannelId":"thread","canManage":true,"agents":[]}]}""")
        assertIs<DataResult.Content<ThreadAgentFollowerRoster>>(repository.load())
        val get = network.requests.single()
        assertEquals("channels/threads/followers", get.path)
        assertEquals(listOf("threadChannelIds" to "thread"), get.query)
        assertEquals(DataRequestPriority.Visible, get.priority)
        assertEquals("server-1", get.authority.serverId)
        assertEquals("user-1", get.authority.accountId)
        assertEquals("https://api.example.test/api", get.authority.environmentId)
        network.response = HomeNetworkResponse(200, """{"ok":true,"removed":true,"undoToken":"token"}""")
        assertEquals("token", assertIs<DataResult.Content<ThreadAgentFollowerRemoval>>(repository.remove("agent")).data.undoToken)
        assertEquals("DELETE", network.requests.last().method)
        assertEquals("channels/threads/thread/followers/agents/agent", network.requests.last().path)
        assertEquals(DataRequestPriority.Foreground, network.requests.last().priority)
        network.response = HomeNetworkResponse(200, """{"ok":true,"restored":false}""")
        assertEquals(false, assertIs<DataResult.Content<Boolean>>(repository.restore("agent", "token")).data)
        val post = network.requests.last()
        assertEquals("POST", post.method)
        assertEquals("channels/threads/thread/followers/agents/agent/restore", post.path)
        assertEquals("token", Json.parseToJsonElement(post.body!!).jsonObject["undoToken"]!!.jsonPrimitive.content)
    }

    @Test fun every404IsAnErrorRatherThanAnEmptyOrDisabledRoster() = runTest {
        val auth = KeyValueAuthSessionStore(InMemorySlockKeyValueStore())
        auth.saveSession(authSessionFixture())
        val network = RecordingFollowerNetwork()
        val repository = repository(network, auth)
        for (body in listOf("""{"code":"thread_follower_management_disabled"}""", """{"error":"Not found"}""")) {
            network.response = HomeNetworkResponse(404, null, body)
            assertIs<DataResult.Error>(repository.load())
        }
        network.response = HomeNetworkResponse(401, null)
        assertIs<DataResult.NoPermission>(repository.load())
    }

    @Test fun accountServerAndEnvironmentChangesRejectWritesAndStaleReadback() = runTest {
        val auth = KeyValueAuthSessionStore(InMemorySlockKeyValueStore())
        auth.saveSession(authSessionFixture(selectedServerId = "server-2"))
        val network = RecordingFollowerNetwork()
        assertIs<DataResult.Error>(repository(network, auth).remove("agent"))
        assertTrue(network.requests.isEmpty())
        auth.saveSession(authSessionFixture(userId = "someone-else"))
        assertIs<DataResult.Error>(repository(network, auth).remove("agent"))
        assertTrue(network.requests.isEmpty())
        auth.saveSession(authSessionFixture())
        var environment = "https://api.example.test/api"
        val repository = repository(network, auth) { environment }
        network.response = HomeNetworkResponse(200, """{"threads":[{"threadChannelId":"thread","canManage":true,"agents":[]}]}""")
        network.onRequest = { environment = "https://different.example.test/api" }
        assertIs<DataResult.Error>(repository.load())
        assertEquals(1, network.requests.size)
        assertIs<DataResult.Error>(repository.restore("agent", "token"))
        assertEquals(1, network.requests.size)
    }

    private fun repository(
        network: RecordingFollowerNetwork, auth: SlockAuthSessionStore,
        environment: () -> String = { "https://api.example.test/api" },
    ) = RealThreadAgentFollowersRepository(
        network, auth, "user-1", "server-1", "thread", "https://api.example.test/api", environment,
    )
}

private data class FollowerRequest(
    val priority: DataRequestPriority, val path: String, val method: String,
    val query: List<Pair<String, String>>, val body: String?, val authority: SlockExpectedAuthBinding,
)
private class RecordingFollowerNetwork : SlockNetworkClient, AuthorityBoundSlockNetworkClient {
    val requests = mutableListOf<FollowerRequest>()
    var response = HomeNetworkResponse(500, null)
    var onRequest: () -> Unit = {}
    override suspend fun request(
        priority: DataRequestPriority, path: String, method: String,
        query: List<Pair<String, String>>, serverIdOverride: ServerId?, bodyText: String?,
    ): HomeNetworkResponse = error("An unbound request must never be used")
    override suspend fun requestBound(
        priority: DataRequestPriority, path: String, method: String,
        query: List<Pair<String, String>>, serverIdOverride: ServerId?, bodyText: String?,
        expectedAuthority: SlockExpectedAuthBinding,
    ): HomeNetworkResponse {
        assertEquals(expectedAuthority.serverId, serverIdOverride?.value)
        requests += FollowerRequest(priority, path, method, query, bodyText, expectedAuthority)
        onRequest()
        return response
    }
    override suspend fun uploadAttachmentsBound(
        priority: DataRequestPriority, channelId: ChannelId, files: List<MultipartFile>,
        expectedAuthority: SlockExpectedAuthBinding,
    ): HomeNetworkResponse = error("Not an upload")
}
