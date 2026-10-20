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
    fun directoryFirstPageReservesEveryAvailableTargetKindAndPaginatesWithoutDuplicates() {
        val targets =
            buildList {
                repeat(4) { index -> add(directoryTarget("dm-$index", ForwardMessageTargetKind.RecentDirectMessage, "dm")) }
                repeat(4) { index -> add(directoryTarget("public-$index", ForwardMessageTargetKind.RecentChannel, "public")) }
                repeat(4) { index -> add(directoryTarget("private-$index", ForwardMessageTargetKind.RecentChannel, "private")) }
                repeat(4) { index ->
                    add(
                        directoryTarget(
                            "joint-$index",
                            ForwardMessageTargetKind.RecentChannel,
                            "joint",
                            ConversationKind.Joint
                        )
                    )
                }
                repeat(4) { index ->
                    add(directoryTarget("member-$index", ForwardMessageTargetKind.HumanDirectMessageCandidate, "dm"))
                }
            }

        val first = targets.toForwardDirectoryPage(pageSize = 10)
        val second = targets.toForwardDirectoryPage(cursor = requireNotNull(first.nextCursor), pageSize = 10)

        assertEquals(10, first.items.size)
        assertEquals(5, first.items.map(::directorySection).distinct().size)
        assertEquals(10, second.items.size)
        assertTrue(first.items.map { it.id }.toSet().intersect(second.items.map { it.id }.toSet()).isEmpty())
        assertEquals(null, second.nextCursor)
    }

    @Test
    fun directorySnapshotDoesNotSkipWhenFreshAuthorityDeletesOrPrependsRows() {
        val authority =
            MutableList(5) { index ->
                directoryTarget("public-$index", ForwardMessageTargetKind.RecentChannel, "public")
            }
        val snapshot = authority.toForwardDirectorySnapshot("snapshot-1", serverId, sessionId = "picker-1")
        val first = snapshot.page(pageSize = 10)

        authority.removeAt(0)
        authority.add(0, directoryTarget("public-new", ForwardMessageTargetKind.RecentChannel, "public"))
        val second = snapshot.page(cursor = requireNotNull(first.nextCursor), pageSize = 10)

        assertEquals(listOf("public-0", "public-1"), first.items.map { it.id })
        assertEquals(listOf("public-2", "public-3"), second.items.map { it.id })
        val fresh = authority.toForwardDirectorySnapshot("snapshot-2", serverId, sessionId = "picker-2").page(pageSize = 10)
        assertEquals(listOf("public-new", "public-1"), fresh.items.map { it.id })
    }

    @Test
    fun directorySnapshotDedupesAndEmptySectionsStillTerminate() {
        val public =
            List(5) { index ->
                directoryTarget("public-$index", ForwardMessageTargetKind.RecentChannel, "public")
            }
        val snapshot =
            (public + public[1]).toForwardDirectorySnapshot(
                "snapshot-empty-sections",
                serverId,
                sessionId = "picker-empty"
            )
        val seen = mutableListOf<String>()
        var cursor = ForwardMessageTargetDirectoryCursor(snapshotId = snapshot.id)
        var pageCount = 0

        do {
            val page = snapshot.page(cursor = cursor, pageSize = 10)
            seen += page.items.map { it.id }
            cursor = page.nextCursor ?: break
            pageCount += 1
        } while (pageCount < 10)

        assertEquals(public.map { it.id }, seen)
        assertEquals(seen.distinct(), seen)
        assertTrue(pageCount < 10, "empty sections must not keep hasMore alive")
    }

    @Test
    fun directoryAuthorityRevisionDriftAtomicallyResetsToFreshFirstPage() = runBlocking {
        val network = RecordingForwardNetworkClient()
        network.peerChannelsBody = jointChannelsBody("joint-0", "joint-1", "joint-2")
        val repository = realRepository(network)
        val first =
            assertIs<DataResult.Content<ForwardMessageTargetDirectoryPage>>(
                repository.directoryTargets(
                    serverId = ServerId("server-peer"),
                    sessionId = "picker-session",
                    pageSize = 5
                )
            ).data

        network.peerChannelsBody = jointChannelsBody("joint-new", "joint-1", "joint-2")
        val reset =
            assertIs<DataResult.Content<ForwardMessageTargetDirectoryPage>>(
                repository.directoryTargets(
                    serverId = ServerId("server-peer"),
                    sessionId = "picker-session",
                    cursor = requireNotNull(first.nextCursor),
                    pageSize = 5
                )
            ).data

        assertEquals(listOf("joint-new"), reset.items.map { it.id })
        assertTrue(reset.reset)
        assertFalse(reset.nextCursor?.authorityRevision == first.nextCursor?.authorityRevision)
    }

    @Test
    fun expiredDirectorySnapshotResetsInsteadOfContinuingOldOffsets() = runBlocking {
        var nowMillis = 0L
        val network = RecordingForwardNetworkClient()
        network.peerChannelsBody = jointChannelsBody("joint-0", "joint-1", "joint-2")
        val repository = realRepository(network, nowMillis = { nowMillis })
        val first =
            assertIs<DataResult.Content<ForwardMessageTargetDirectoryPage>>(
                repository.directoryTargets(ServerId("server-peer"), sessionId = "picker-ttl", pageSize = 5)
            ).data

        nowMillis = ForwardDirectorySnapshotTtlMillis
        val reset =
            assertIs<DataResult.Content<ForwardMessageTargetDirectoryPage>>(
                repository.directoryTargets(
                    ServerId("server-peer"),
                    sessionId = "picker-ttl",
                    cursor = requireNotNull(first.nextCursor),
                    pageSize = 5
                )
            ).data

        assertTrue(reset.reset)
        assertEquals(listOf("joint-0"), reset.items.map { it.id })
    }

    @Test
    fun directorySourceFailureNeverPublishesPartialRevisionReset() = runBlocking {
        val network = RecordingForwardNetworkClient()
        network.peerChannelsBody = jointChannelsBody("joint-0", "joint-1", "joint-2")
        val repository = realRepository(network)
        val first =
            assertIs<DataResult.Content<ForwardMessageTargetDirectoryPage>>(
                repository.directoryTargets(ServerId("server-peer"), sessionId = "picker-failure", pageSize = 5)
            ).data

        network.peerChannelsCode = 500
        network.peerChannelsBody = jointChannelsBody("joint-new", "joint-1", "joint-2")
        assertIs<DataResult.Error>(
            repository.directoryTargets(
                ServerId("server-peer"),
                sessionId = "picker-failure",
                cursor = requireNotNull(first.nextCursor),
                pageSize = 5
            )
        )

        network.peerChannelsCode = 200
        val reset =
            assertIs<DataResult.Content<ForwardMessageTargetDirectoryPage>>(
                repository.directoryTargets(
                    ServerId("server-peer"),
                    sessionId = "picker-failure",
                    cursor = requireNotNull(first.nextCursor),
                    pageSize = 5
                )
            ).data
        assertTrue(reset.reset)
        assertEquals(listOf("joint-new"), reset.items.map { it.id })
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

        assertIs<DataResult.Content<ForwardMessageSendResult>>(result)
        val forwardCall = network.calls.single { it.path == "messages/forward" }
        assertEquals(ServerId("server-source"), forwardCall.serverId)
        assertTrue(forwardCall.body.orEmpty().contains("joint-source-projection"))
    }

    @Test
    fun singleForwardParsesReturnedCanonicalMessage() = runBlocking {
        val network = RecordingForwardNetworkClient()
        network.forwardResponseBody = canonicalMessageBody(channelId = "channel-selected", content = "forward note")
        val repository = realRepository(network)

        val result = repository.forwardMessages(
            ForwardMessageExecuteRequest(
                sourceServerId = serverId,
                sourceChannelId = ChannelId("source"),
                destination = ForwardMessageDestination(
                    serverId = serverId,
                    channelId = ChannelId("channel-selected"),
                    channelKind = ConversationKind.Channel
                ),
                messageIds = listOf("11111111-1111-4111-8111-111111111111"),
                mode = ForwardMessageSendMode.Merged,
                note = "forward note"
            )
        )

        val message = assertIs<DataResult.Content<ForwardMessageSendResult>>(result).data.message
        assertEquals(ChannelId("channel-selected"), message?.channelId)
        assertEquals("forward note", message?.body)
    }

    @Test
    fun batchForwardSendsDedupedDestinationsAndParsesPerDestinationResults() = runBlocking {
        val network = RecordingForwardNetworkClient()
        network.forwardResponseBody = """
            {
              "results": [
                {"destinationChannelId":"channel-a","status":"success","message":${canonicalMessageBody(channelId = "channel-a", content = "batch note")}},
                {"destinationChannelId":"channel-b","status":"failed","code":"destination_unavailable"}
              ]
            }
        """.trimIndent()
        val repository = realRepository(network)
        val destinations = listOf(
            ForwardMessageDestination(serverId, ChannelId("channel-a"), ConversationKind.Channel),
            ForwardMessageDestination(serverId, ChannelId("channel-b"), ConversationKind.Channel),
            ForwardMessageDestination(serverId, ChannelId("channel-a"), ConversationKind.Channel)
        )

        val result = repository.forwardMessagesBatch(
            ForwardMessageBatchExecuteRequest(
                sourceServerId = serverId,
                sourceChannelId = ChannelId("source"),
                destinations = destinations,
                messageIds = listOf("11111111-1111-4111-8111-111111111111"),
                mode = ForwardMessageSendMode.Merged,
                note = " batch note ",
                requestId = "11111111-2222-4333-8444-555555555555"
            )
        )

        val batch = assertIs<DataResult.Content<ForwardMessageBatchResult>>(result).data
        assertEquals(listOf(ChannelId("channel-a")), batch.deliveries.map { it.destination.channelId })
        assertEquals(listOf("destination_unavailable"), batch.failures.map { it.code })
        val call = network.calls.single { it.path == "messages/forward" }
        assertTrue(call.body.orEmpty().contains("destinationChannelIds"))
        assertTrue(call.body.orEmpty().contains("channel-a"))
        assertTrue(call.body.orEmpty().contains("channel-b"))
        assertTrue(call.body.orEmpty().contains("11111111-2222-4333-8444-555555555555"))
        assertTrue(call.body.orEmpty().contains("batch note"))
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

    private fun canonicalMessageBody(channelId: String, content: String): String =
        """
            {
              "id":"message-forwarded",
              "channelId":"$channelId",
              "senderType":"user",
              "senderId":"user-1",
              "senderName":"User",
              "messageType":"chat",
              "content":"$content",
              "createdAt":"2026-09-02T00:00:00.000Z",
              "updatedAt":"2026-09-02T00:00:00.000Z",
              "actionMetadata":{"kind":"forwarded-bundle","version":1,"forwardedItems":[]}
            }
        """.trimIndent()

    private fun realRepository(
        network: RecordingForwardNetworkClient,
        nowMillis: () -> Long = { 0L }
    ): RealForwardMessageTargetRepository {
        val store =
            InMemoryRaftAuthSessionStore(
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
            memberRepository = RealMemberRepository(store, network),
            nowMillis = nowMillis
        )
    }

    private fun directoryTarget(
        id: String,
        kind: ForwardMessageTargetKind,
        channelType: String,
        channelKind: ConversationKind = ConversationKind.Channel
    ): ForwardMessageTarget =
        ForwardMessageTarget(
            serverId = serverId,
            kind = kind,
            id = id,
            title = id,
            channelKind = if (kind == ForwardMessageTargetKind.RecentDirectMessage) ConversationKind.DirectMessage else channelKind,
            channelType = channelType,
            memberId = if (kind == ForwardMessageTargetKind.HumanDirectMessageCandidate) MemberId(id) else null
        )

    private fun directorySection(target: ForwardMessageTarget): String =
        when {
            target.kind == ForwardMessageTargetKind.RecentDirectMessage -> "dm"
            target.kind == ForwardMessageTargetKind.HumanDirectMessageCandidate -> "member"
            target.channelKind == ConversationKind.Joint -> "joint"
            target.channelType == "private" -> "private"
            else -> "public"
        }

    private fun jointChannelsBody(vararg ids: String): String =
        ids.joinToString(prefix = "[", postfix = "]") { id ->
            """{"id":"$id","name":"$id","type":"joint","joined":true,"jointChannelId":"$id-root","jointRole":"participant","jointPeerServerId":"server-source"}"""
        }
}

private data class ForwardNetworkCall(
    val path: String,
    val method: String,
    val serverId: ServerId?,
    val body: String?
)

private class RecordingForwardNetworkClient : RaftNetworkClient {
    val calls = mutableListOf<ForwardNetworkCall>()
    var peerChannelsBody: String =
        """[{"id":"joint-peer-projection","name":"joint-room","type":"joint","joined":true,"jointChannelId":"joint-root","jointRole":"participant","jointPeerServerId":"server-source"}]"""
    var peerChannelsCode: Int = 200
    var forwardResponseBody: String =
        """
            {
              "id":"message-forwarded",
              "channelId":"joint-source-projection",
              "senderType":"user",
              "senderId":"user-1",
              "senderName":"User",
              "messageType":"chat",
              "content":"Forwarded 1 message",
              "createdAt":"2026-09-02T00:00:00.000Z",
              "updatedAt":"2026-09-02T00:00:00.000Z",
              "actionMetadata":{"kind":"forwarded-bundle","version":1,"forwardedItems":[]}
            }
        """.trimIndent()

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
                    code = peerChannelsCode,
                    bodyText = peerChannelsBody
                )
            path == "channels/dm" -> HomeNetworkResponse(code = 200, bodyText = "[]")
            path == "servers/${serverIdOverride?.value}/members" ->
                HomeNetworkResponse(code = 200, bodyText = """{"members":[]}""")
            path == "agents" -> HomeNetworkResponse(code = 200, bodyText = "[]")
            path == "servers/${serverIdOverride?.value}/machines" ->
                HomeNetworkResponse(code = 200, bodyText = "[]")
            path == "messages/forward" -> HomeNetworkResponse(code = 200, bodyText = forwardResponseBody)
            else -> HomeNetworkResponse(code = 404, bodyText = null, errorBodyText = "not found")
        }
    }
}
