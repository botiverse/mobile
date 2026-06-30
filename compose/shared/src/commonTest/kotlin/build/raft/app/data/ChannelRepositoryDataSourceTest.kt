package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ChannelRepositoryDataSourceTest {
    @Test
    fun createChannelSendsInitialMembersInCreateBodyWithoutPostCreateMemberCalls() = runBlocking {
        val network =
            FakeChannelNetworkClient(
                responseBody = """{"id":"channel-1","name":"review","type":"private","joined":true}"""
            )
        val repository = RealChannelRepository(sampleStore(), network)

        val result =
            repository.createChannel(
                CreateChannelRequest(
                    name = " review ",
                    description = "  Design review  ",
                    visibility = " private ",
                    agentMemberIds = listOf("agent-1", "agent-1", "", "agent-2"),
                    userMemberIds = listOf("user-1", "user-1", " ")
                )
            )

        val created = assertIs<DataResult.Content<CreateChannelResult>>(result).data
        assertEquals("channel-1", created.channel.channelId?.value)
        assertEquals(listOf("agent-1", "agent-2"), created.addedAgentMemberIds)
        assertEquals(listOf("user-1"), created.addedUserMemberIds)
        assertEquals(
            listOf(RecordedChannelRequest(DataRequestPriority.Foreground, "POST", "channels")),
            network.requests
        )

        val body = network.bodies.single().asJsonObject()
        assertEquals("review", body["name"]?.jsonPrimitive?.content)
        assertEquals("Design review", body["description"]?.jsonPrimitive?.content)
        assertEquals("private", body["visibility"]?.jsonPrimitive?.content)
        assertEquals(listOf("agent-1", "agent-2"), body.stringList("agentIds"))
        assertEquals(listOf("user-1"), body.stringList("userIds"))
    }

    @Test
    fun createJointChannelSendsServerInviteContractFields() = runBlocking {
        val network =
            FakeChannelNetworkClient(
                responseBody = """{"id":"joint-1","name":"partner","type":"joint","joined":true}"""
            )
        val repository = RealChannelRepository(sampleStore(), network)

        repository.createChannel(
            CreateChannelRequest(
                name = "partner",
                visibility = "joint",
                agentMemberIds = listOf("agent-1"),
                userMemberIds = listOf("user-1"),
                targetServerSlug = " partner-server ",
                invitedPeople = listOf("owner@example.com", "owner@example.com", " @admin ")
            )
        )

        val body = network.bodies.single().asJsonObject()
        assertEquals("joint", body["visibility"]?.jsonPrimitive?.content)
        assertEquals(listOf("agent-1"), body.stringList("agentIds"))
        assertEquals(listOf("user-1"), body.stringList("userIds"))
        assertEquals("partner-server", body["targetServerSlug"]?.jsonPrimitive?.content)
        assertEquals(listOf("owner@example.com", "@admin"), body.stringList("invitedPeople"))
        assertEquals(
            listOf(RecordedChannelRequest(DataRequestPriority.Foreground, "POST", "channels")),
            network.requests
        )
    }

    @Test
    fun createChannelPreservesServerConflictCode() = runBlocking {
        val network =
            FakeChannelNetworkClient(
                responseCode = 409,
                responseBody = """{"code":"archived_name_collision","error":"Channel name is held by an archived channel"}"""
            )
        val repository = RealChannelRepository(sampleStore(), network)

        val result = repository.createChannel(CreateChannelRequest(name = "review"))

        val error = assertIs<DataResult.Error>(result).error
        assertEquals("archived_name_collision", error.code)
        assertEquals(false, error.retryable)
    }

    @Test
    fun channelCacheAllowsJoinedStateToBecomeFalse() = runBlocking {
        val network =
            FakeChannelNetworkClient(
                responseBody = """[{"id":"channel-1","name":"general","type":"channel","joined":true}]"""
            )
        val repository = RealChannelRepository(sampleStore(), network)
        val serverId = ServerId("server-1")

        val joined = assertIs<DataResult.Content<ChannelSnapshot>>(repository.getChannels(serverId)).data.channels.single()
        assertEquals(true, joined.joined)
        assertEquals(true, joined.permissions.canSend)

        network.responseBody = """[{"id":"channel-1","name":"general","type":"channel","joined":false}]"""
        val notJoined = assertIs<DataResult.Content<ChannelSnapshot>>(repository.getChannels(serverId)).data.channels.single()

        assertEquals(false, notJoined.joined)
        assertEquals(false, notJoined.permissions.canSend)
    }

    @Test
    fun channelSummaryCacheKeepsServerBucketsAcrossSelectedServerSwitches() = runBlocking {
        val sessionStore = sampleStore()
        val network =
            FakeChannelNetworkClient(
                responseBody = """[{"id":"channel-1","name":"General Team","type":"channel","description":"Team room","joined":true}]"""
            )
        val repository = RealChannelRepository(sessionStore, network)

        val server1Rich = assertIs<DataResult.Content<ChannelSnapshot>>(repository.getChannels(ServerId("server-1"))).data.channels.single()
        assertEquals("General Team", server1Rich.name)
        assertEquals("Team room", server1Rich.subtitle)

        sessionStore.updateSelectedServer("server-2", "work")
        network.responseBody = """[{"id":"channel-2","name":"Work Team","type":"channel","description":"Work room","joined":true}]"""
        val server2 = assertIs<DataResult.Content<ChannelSnapshot>>(repository.getChannels(ServerId("server-2"))).data.channels.single()
        assertEquals("Work Team", server2.name)

        sessionStore.updateSelectedServer("server-1", "android")
        network.responseBody = """[{"id":"channel-1","name":"channel-1","type":"channel","joined":true}]"""
        val server1Generic = assertIs<DataResult.Content<ChannelSnapshot>>(repository.getChannels(ServerId("server-1"))).data.channels.single()

        assertEquals("General Team", server1Generic.name)
        assertEquals("Team room", server1Generic.subtitle)
    }

    @Test
    fun directMessageCacheKeepsServerBucketsAcrossSelectedServerSwitches() = runBlocking {
        val sessionStore = sampleStore()
        val network =
            FakeChannelNetworkClient(
                responseBody = """[{"id":"dm-1","name":"dm-1","type":"dm","peerType":"agent","peerId":"agent-1","peerName":"Product UX"}]"""
            )
        val repository = RealChannelRepository(sessionStore, network)

        val server1Rich = assertIs<DataResult.Content<DirectMessageSnapshot>>(repository.getDirectMessages(ServerId("server-1"))).data.directMessages.single()
        assertEquals("Product UX", server1Rich.name)
        assertEquals("agent", server1Rich.directMessagePeerType)

        sessionStore.updateSelectedServer("server-2", "work")
        network.responseBody = """[{"id":"dm-2","name":"dm-2","type":"dm","peerType":"agent","peerId":"agent-2","peerName":"Android Dev"}]"""
        val server2 = assertIs<DataResult.Content<DirectMessageSnapshot>>(repository.getDirectMessages(ServerId("server-2"))).data.directMessages.single()
        assertEquals("Android Dev", server2.name)

        sessionStore.updateSelectedServer("server-1", "android")
        network.responseBody = """[{"id":"dm-1","name":"dm-1","type":"dm","peerType":"agent","peerId":"agent-1","peerName":"dm-1"}]"""
        val server1Generic = assertIs<DataResult.Content<DirectMessageSnapshot>>(repository.getDirectMessages(ServerId("server-1"))).data.directMessages.single()

        assertEquals("Product UX", server1Generic.name)
        assertEquals("agent", server1Generic.directMessagePeerType)
    }

    @Test
    fun getHomePreservesServerUnreadSummaryAfterCacheMerge() = runBlocking {
        val network =
            FakeChannelNetworkClient(
                pathResponses =
                    mapOf(
                        "channels" to """[{"id":"channel-1","name":"general","type":"channel","joined":true}]""",
                        "channels/dm" to """[]""",
                        "channels/unread" to """{"channel-1":4}""",
                        "servers/unread-summary" to """[{"serverId":"server-1","unreadCount":12}]"""
                    )
            )
        val repository = RealChannelRepository(sampleStore(), network)

        val home = assertIs<DataResult.Content<HomeSnapshot>>(repository.getHome(ServerId("server-1"))).data

        assertEquals(AppBadgeSource.SERVER_UNREAD_SUMMARY, home.unreadSource)
        assertEquals(4, home.channels.single().unreadCount)
        assertEquals(12, home.unreadTotal)
        assertEquals(12, home.server.unreadCount)
        assertEquals(12, home.serverUnreadCounts["server-1"])
    }

    private fun String.asJsonObject() =
        Json.parseToJsonElement(this).jsonObject

    private fun kotlinx.serialization.json.JsonObject.stringList(key: String): List<String> =
        get(key)?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    private fun sampleStore(): SlockAuthSessionStore =
        InMemorySlockAuthSessionStore(
            StoredAuthSession(
                user =
                    StoredAuthUser(
                        id = "user-1",
                        email = "artin@slock.ai",
                        displayName = "Artin"
                    ),
                accessToken = "token-a",
                refreshToken = "token-r",
                selectedServerId = "server-1",
                servers =
                    listOf(
                        StoredAuthServer(
                            id = "server-1",
                            name = "Slock Android",
                            slug = "android"
                        ),
                        StoredAuthServer(
                            id = "server-2",
                            name = "Work",
                            slug = "work"
                        )
                    )
            )
        )

    private class FakeChannelNetworkClient(
        private val responseCode: Int = 200,
        var responseBody: String = """{"id":"channel-1","name":"general","type":"channel","joined":true}""",
        private val pathResponses: Map<String, String> = emptyMap()
    ) : SlockNetworkClient {
        val requests = mutableListOf<RecordedChannelRequest>()
        val bodies = mutableListOf<String>()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?
        ): HomeNetworkResponse {
            val normalizedPath = path.trim('/')
            requests += RecordedChannelRequest(priority, method, normalizedPath)
            bodyText?.let { bodies += it }
            return HomeNetworkResponse(
                code = responseCode,
                bodyText = pathResponses[normalizedPath] ?: responseBody
            )
        }
    }

    private data class RecordedChannelRequest(
        val priority: DataRequestPriority,
        val method: String,
        val path: String
    )
}
