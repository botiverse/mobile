package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ForwardMessageTargetRepositoryTest {
    private val serverId = ServerId("server-1")

    @Test
    fun parsesForwardTargetSearchObjectShape() {
        val targets = parseForwardMessageTargetSearchResults(
            """
                {
                  "items": [
                    {
                      "type": "channel",
                      "id": "channel-1",
                      "title": "#general",
                      "subtitle": "Joined channel",
                      "channelId": "channel-1",
                      "joined": true,
                      "canForwardNow": true
                    },
                    {
                      "type": "channel",
                      "id": "channel-2",
                      "title": "#town-square",
                      "channelId": "channel-2",
                      "joined": false,
                      "requiredAction": "join_channel"
                    },
                    {
                      "type": "human",
                      "id": "user-1",
                      "title": "Ada",
                      "subtitle": "Product",
                      "dmExists": false
                    },
                    {
                      "type": "agent",
                      "id": "agent-1",
                      "title": "Build Bot",
                      "requiredAction": "create_dm"
                    },
                    {
                      "type": "dm",
                      "id": "dm-1",
                      "title": "Cindy",
                      "channelId": "dm-1",
                      "dmExists": true
                    }
                  ]
                }
            """.trimIndent(),
            fallbackServerId = serverId
        )

        assertEquals(5, targets.size)
        assertEquals(ForwardMessageTargetKind.RecentChannel, targets[0].kind)
        assertTrue(targets[0].canForwardNow)
        assertEquals(ForwardMessageTargetKind.PublicChannelUnjoined, targets[1].kind)
        assertEquals(ForwardMessageTargetRequiredAction.JoinChannel, targets[1].requiredAction)
        assertFalse(targets[1].canForwardNow)
        assertEquals(ForwardMessageTargetKind.HumanDirectMessageCandidate, targets[2].kind)
        assertEquals(ForwardMessageTargetRequiredAction.CreateDirectMessage, targets[2].requiredAction)
        assertEquals(ForwardMessageTargetKind.AgentDirectMessageCandidate, targets[3].kind)
        assertEquals(ForwardMessageTargetRequiredAction.CreateDirectMessage, targets[3].requiredAction)
        assertEquals(ForwardMessageTargetKind.RecentDirectMessage, targets[4].kind)
        assertTrue(targets[4].canForwardNow)
    }

    @Test
    fun parsesForwardTargetSearchArrayShapeAndDedupes() {
        val targets = parseForwardMessageTargetSearchResults(
            """
                [
                  {
                    "type": "human",
                    "id": "user-1",
                    "title": "Ada",
                    "channelId": "dm-user-1",
                    "dmExists": true
                  },
                  {
                    "type": "human",
                    "id": "user-1",
                    "title": "Ada Duplicate",
                    "channelId": "dm-user-1",
                    "dmExists": true
                  }
                ]
            """.trimIndent(),
            fallbackServerId = serverId
        )

        assertEquals(1, targets.size)
        assertEquals("Ada", targets.single().title)
        assertEquals(ForwardMessageTargetRequiredAction.None, targets.single().requiredAction)
        assertTrue(targets.single().canForwardNow)
    }

    @Test
    fun fallbackSearchUsesExistingJoinedChannelsAndDms() {
        val targets =
            fallbackForwardMessageTargets(
                serverId = serverId,
                directMessages =
                    listOf(
                        HomeDirectMessageRow(
                            threadId = ThreadId("dm-ada"),
                            channelId = ChannelId("dm-ada"),
                            name = "Ada",
                            subtitle = "Recent DM",
                            presenceMemberId = MemberId("user-ada")
                        )
                    ),
                channels =
                    listOf(
                        HomeChannelRow(
                            threadId = ThreadId("channel-general"),
                            channelId = ChannelId("channel-general"),
                            name = "general",
                            joined = true
                        ),
                        HomeChannelRow(
                            threadId = ThreadId("channel-design"),
                            channelId = ChannelId("channel-design"),
                            name = "design",
                            joined = false
                        )
                    ),
                query = "d",
                limit = 10
            )

        assertEquals(1, targets.size)
        assertEquals(ForwardMessageTargetKind.RecentDirectMessage, targets.single().kind)
    }

    @Test
    fun fallbackSearchAddsHumanAndAgentCandidatesWithoutDuplicatingExistingDms() {
        val targets =
            fallbackForwardMessageTargets(
                serverId = serverId,
                directMessages =
                    listOf(
                        HomeDirectMessageRow(
                            threadId = ThreadId("dm-ada"),
                            channelId = ChannelId("dm-ada"),
                            name = "Ada",
                            presenceMemberId = MemberId("user-ada")
                        )
                    ),
                channels = emptyList(),
                members =
                    listOf(
                        MemberProfile(
                            id = MemberId("user-ada"),
                            displayName = "Ada",
                            handle = "ada",
                            kind = MemberKind.Human,
                            avatarUrl = "pixel:user",
                            avatarLabel = "AD"
                        ),
                        MemberProfile(
                            id = MemberId("agent-build"),
                            displayName = "Build Agent",
                            handle = "build-agent",
                            kind = MemberKind.Agent
                        ),
                        MemberProfile(
                            id = MemberId("system-1"),
                            displayName = "System",
                            handle = "system",
                            kind = MemberKind.System
                        )
                    ),
                query = "a",
                limit = 10
            )

        assertEquals(listOf("dm-ada", "agent-build"), targets.map { it.id })
        assertEquals(ForwardMessageTargetKind.RecentDirectMessage, targets[0].kind)
        assertEquals("pixel:user", targets[0].avatarWireValue)
        assertEquals("AD", targets[0].avatarLabel)
        assertEquals(ForwardMessageTargetKind.AgentDirectMessageCandidate, targets[1].kind)
        assertEquals(ForwardMessageTargetRequiredAction.CreateDirectMessage, targets[1].requiredAction)
    }

    @Test
    fun fallbackSearchMatchesMemberHandleWhenDisplayNameDoesNotMatch() {
        val targets =
            fallbackForwardMessageTargets(
                serverId = serverId,
                directMessages = emptyList(),
                channels = emptyList(),
                members =
                    listOf(
                        MemberProfile(
                            id = MemberId("agent-build"),
                            displayName = "Build Agent",
                            handle = "release-bot",
                            kind = MemberKind.Agent
                        )
                    ),
                query = "release-bot",
                limit = 10
            )

        assertEquals(listOf("agent-build"), targets.map { it.id })
        assertEquals("@release-bot", targets.single().subtitle)
    }

    @Test
    fun recentJointTargetRetainsTypedServerAndProjectionIdentity() = runBlocking {
        val network = RecordingForwardNetworkClient()
        val repository = realRepository(network)

        val target =
            assertIs<DataResult.Content<List<ForwardMessageTarget>>>(
                repository.recentTargets(ServerId("server-peer"), limit = 10)
            ).data.single()

        assertEquals(ServerId("server-peer"), target.serverId)
        assertEquals(ChannelId("joint-peer-projection"), target.channelId)
        assertEquals(ConversationKind.Joint, target.channelKind)
        assertEquals("joint-root", target.jointChannelId)
    }

    @Test
    fun crossServerJointTargetResolvesSourceProjectionAndBindsForwardRequestToIt() = runBlocking {
        val network = RecordingForwardNetworkClient()
        val repository = realRepository(network)
        val target =
            assertIs<DataResult.Content<List<ForwardMessageTarget>>>(
                repository.recentTargets(ServerId("server-peer"), limit = 10)
            ).data.single()

        val destination =
            assertIs<DataResult.Content<ForwardMessageDestination>>(
                repository.resolveTargetChannel(
                    sourceServerId = ServerId("server-source"),
                    target = target
                )
            ).data
        assertEquals(ServerId("server-source"), destination.serverId)
        assertEquals(ChannelId("joint-source-projection"), destination.channelId)
        assertEquals(ConversationKind.Joint, destination.channelKind)

        val result =
            repository.forwardMessages(
                ForwardMessageExecuteRequest(
                    sourceServerId = ServerId("server-source"),
                    sourceChannelId = ChannelId("source-channel"),
                    destination = destination,
                    messageIds = listOf("11111111-1111-4111-8111-111111111111"),
                    mode = ForwardMessageSendMode.Merged
                )
            )

        assertIs<DataResult.Content<Unit>>(result)
        val forwardCall = network.calls.single { it.path == "messages/forward" }
        assertEquals(ServerId("server-source"), forwardCall.serverId)
        assertTrue(forwardCall.body.orEmpty().contains("joint-source-projection"))
    }

    @Test
    fun ordinaryCrossServerTargetFailsClosedBeforeNetwork() = runBlocking {
        val network = RecordingForwardNetworkClient()
        val repository = realRepository(network)
        val before = network.calls.size
        val result =
            repository.resolveTargetChannel(
                sourceServerId = ServerId("server-source"),
                target =
                    ForwardMessageTarget(
                        serverId = ServerId("server-peer"),
                        kind = ForwardMessageTargetKind.RecentChannel,
                        id = "peer-ordinary",
                        title = "#peer",
                        channelId = ChannelId("peer-ordinary"),
                        channelKind = ConversationKind.Channel
                    )
            )

        assertEquals(
            "forward_cross_server_target_unsupported",
            assertIs<DataResult.Error>(result).error.code
        )
        assertEquals(before, network.calls.size)
    }

    @Test
    fun unauthenticatedTargetServerFailsClosedBeforeNetwork() = runBlocking {
        val network = RecordingForwardNetworkClient()
        val repository = realRepository(network)
        val result =
            repository.resolveTargetChannel(
                sourceServerId = ServerId("server-source"),
                target =
                    ForwardMessageTarget(
                        serverId = ServerId("server-unknown"),
                        kind = ForwardMessageTargetKind.RecentChannel,
                        id = "unknown-channel",
                        title = "#unknown",
                        channelId = ChannelId("unknown-channel")
                    )
            )

        assertEquals(
            "forward_target_server_unauthorized",
            assertIs<DataResult.Error>(result).error.code
        )
        assertTrue(network.calls.isEmpty())
    }

    private fun realRepository(network: RecordingForwardNetworkClient): RealForwardMessageTargetRepository {
        val store =
            InMemorySlockAuthSessionStore(
                StoredAuthSession(
                    user = StoredAuthUser(id = "user-1", email = "user@example.com"),
                    accessToken = "access",
                    refreshToken = "refresh",
                    selectedServerId = "server-source",
                    servers =
                        listOf(
                            StoredAuthServer(id = "server-source", name = "Source"),
                            StoredAuthServer(id = "server-peer", name = "Peer")
                        )
                )
            )
        return RealForwardMessageTargetRepository(
            sessionStore = store,
            networkClient = network,
            channelRepository = RealChannelRepository(store, network),
            memberRepository = RealMemberRepository(store, network)
        )
    }
}

private data class ForwardNetworkCall(
    val path: String,
    val method: String,
    val serverId: ServerId?,
    val body: String?
)

private class RecordingForwardNetworkClient : SlockNetworkClient {
    val calls = mutableListOf<ForwardNetworkCall>()

    override suspend fun request(
        priority: DataRequestPriority,
        path: String,
        method: String,
        query: List<Pair<String, String>>,
        serverIdOverride: ServerId?,
        bodyText: String?
    ): HomeNetworkResponse {
        calls += ForwardNetworkCall(path, method, serverIdOverride, bodyText)
        return when {
            path == "channels" && serverIdOverride?.value == "server-source" ->
                HomeNetworkResponse(
                    code = 200,
                    bodyText =
                        """[{"id":"joint-source-projection","name":"joint-room","type":"joint","joined":true,"jointChannelId":"joint-root","jointRole":"host","jointPeerServerId":"server-peer"}]"""
                )
            path == "channels" && serverIdOverride?.value == "server-peer" ->
                HomeNetworkResponse(
                    code = 200,
                    bodyText =
                        """[{"id":"joint-peer-projection","name":"joint-room","type":"joint","joined":true,"jointChannelId":"joint-root","jointRole":"participant","jointPeerServerId":"server-source"}]"""
                )
            path == "channels/dm" -> HomeNetworkResponse(code = 200, bodyText = "[]")
            path == "messages/forward" -> HomeNetworkResponse(code = 200, bodyText = "{}")
            else -> HomeNetworkResponse(code = 404, bodyText = null, errorBodyText = "not found")
        }
    }
}
