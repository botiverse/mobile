package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberKind
import build.raft.app.model.ServerId
import build.raft.app.model.avatarWireValueForRenderedAvatar
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeParserTest {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Test
    fun preservesReadStateV1HydrateCarrierForChannelListDmAndDetailWithoutTouchingLegacyCursor() {
        val result =
            parseRaftHome(
                payload =
                    homePayload(
                        channels =
                            listOf(
                                HomeChannelWireDto(
                                    id = "channel-1",
                                    name = "native",
                                    type = "channel",
                                    maxReadSeq = 41,
                                    readStateVersion = 7,
                                )
                            ),
                        directMessages =
                            listOf(
                                HomeDirectMessageWireDto(
                                    id = "dm-1",
                                    name = "peer",
                                    type = "dm",
                                    peerType = "agent",
                                    peerId = "agent-1",
                                    peerName = "peer",
                                    maxReadSeq = 42,
                                    readStateVersion = 8,
                                )
                            ),
                    ),
                session = sampleSession(),
            )

        assertTrue(result is DataResult.Content)
        val channel = result.data.channels.single()
        val dm = result.data.directMessages.single()
        assertEquals(ReadStateV1HydrateCarrier(7, 41), channel.readStateV1Hydrate)
        assertEquals(ReadStateV1HydrateCarrier(8, 42), dm.readStateV1Hydrate)
        assertNull(channel.readStateMaxReadSeq)
        assertNull(dm.readStateMaxReadSeq)

        val detail =
            parseRaftChannelDetail(
                """{"id":"channel-1","name":"native","type":"channel","maxReadSeq":43,"readStateVersion":9}"""
            )
        requireNotNull(detail)
        assertEquals(ReadStateV1HydrateCarrier(9, 43), detail.readStateV1Hydrate)
        assertNull(detail.readStateMaxReadSeq)
    }

    @Test
    fun detailDirectMessagePreservesLastMessagePreviewAndSenderName() {
        val detail =
            parseRaftChannelDetail(
                """{"id":"dm-1","name":"alice","type":"dm","peerType":"user","peerId":"u-1","peerName":"alice","lastMessagePreview":"Hello world","lastMessageSenderName":"Alice"}"""
            )
        requireNotNull(detail)
        assertEquals("Hello world", detail.lastMessagePreview)
        assertEquals("Alice", detail.lastMessageSenderName)
    }

    @Test
    fun detailTypeAlonePreservesCanonicalJointKind() {
        val detail =
            parseRaftChannelDetail(
                """{"id":"joint-1","name":"raft-mobile","type":"joint"}"""
            )

        requireNotNull(detail)
        assertEquals("joint", detail.channelType)
        assertEquals(ConversationKind.Joint, detail.kind)
    }

    @Test
    fun preservesIncompleteHydrateMembersWithoutFillingFromLegacyCursor() {
        val result =
            parseRaftHome(
                payload =
                    homePayload(
                        channels =
                            listOf(
                                HomeChannelWireDto(
                                    id = "missing-version",
                                    name = "missing-version",
                                    type = "channel",
                                    maxReadSeq = 41,
                                ),
                                HomeChannelWireDto(
                                    id = "missing-watermark",
                                    name = "missing-watermark",
                                    type = "channel",
                                    readStateVersion = 7,
                                ),
                            ),
                    ),
                session = sampleSession(),
            )

        assertTrue(result is DataResult.Content)
        assertEquals(ReadStateV1HydrateCarrier(null, 41), result.data.channels[0].readStateV1Hydrate)
        assertEquals(ReadStateV1HydrateCarrier(7, null), result.data.channels[1].readStateV1Hydrate)
        assertTrue(result.data.channels.all { it.readStateMaxReadSeq == null })
    }

    @Test
    fun parsesTypedChannelsDmsAndUnreadCounts() {
        val payload =
            homePayload(
                channels =
                    listOf(
                        HomeChannelWireDto(
                            id = "channel-1",
                            name = "native",
                            type = "channel",
                            description = "Native client work",
                            joined = true,
                            archivedAt = null,
                            lastMessageAt = "2024-09-01T00:00:00.000Z"
                        )
                    ),
                directMessages =
                    listOf(
                        HomeDirectMessageWireDto(
                            id = "dm-1",
                            name = "agent-one",
                            type = "dm",
                            description = null,
                            peerType = "agent",
                            peerId = "agent-1",
                            peerName = "agent-one",
                            peerDisplayName = "Agent One",
                            peerAvatarUrl = null
                        )
                    ),
                unreadCounts = mapOf(ChannelId("channel-1") to 4, ChannelId("dm-1") to 2)
            )
        val result =
            parseRaftHome(
                payload = payload,
                session = sampleSession()
            )

        assertTrue(result is DataResult.Content)
        val snapshot = result.data
        assertEquals("Slock Community", snapshot.server.name)
        val channel = snapshot.channels.single()
        assertEquals("native", channel.name)
        assertEquals("Native client work", channel.subtitle)
        assertEquals(4, channel.unreadCount)
        assertTrue(channel.unreadCountKnown)
        assertEquals(false, channel.archived)
        val dm = snapshot.directMessages.single()
        assertEquals(ConversationKind.DirectMessage, dm.kind)
        assertEquals("Agent One", dm.name)
        assertEquals("agent", dm.directMessagePeerType)
        assertEquals("agent-1", dm.presenceMemberId?.value)
        assertEquals(2, dm.unreadCount)
        val peerProfile = payload.directMessagePeerProfiles(ServerId("server-1")).single()
        assertEquals("agent-1", peerProfile.id.value)
        assertNull(peerProfile.avatar?.wireValue)
        assertEquals("pixel:robot", peerProfile.avatarWireValueForRenderedAvatar())
        assertEquals(6, snapshot.unreadTotal)
    }

    @Test
    fun directMessagePeerProfilesDropUnknownKindWithoutPoisoningValidPeers() {
        val logs = mutableListOf<String>()
        val payload =
            homePayload(
                directMessages =
                    listOf(
                        HomeDirectMessageWireDto(
                            id = "dm-invalid",
                            name = "invalid",
                            type = "dm",
                            peerType = "future-kind",
                            peerId = "secret-peer-id",
                            peerName = "Secret Person",
                        ),
                        HomeDirectMessageWireDto(
                            id = "dm-human",
                            name = "human",
                            type = "dm",
                            peerType = "human",
                            peerId = "user-human",
                            peerName = "Human Alias",
                        ),
                        HomeDirectMessageWireDto(
                            id = "dm-system",
                            name = "system",
                            type = "dm",
                            peerType = "system",
                            peerId = "system",
                            peerName = "System",
                        ),
                        HomeDirectMessageWireDto(
                            id = "dm-agent",
                            name = "agent",
                            type = "dm",
                            peerType = "agent",
                            peerId = "agent-1",
                            peerName = "agent-one",
                            peerDisplayName = "Agent One",
                        ),
                    ),
            )

        val profiles = payload.directMessagePeerProfiles(ServerId("server-1"), logs::add)

        assertEquals(listOf("agent-1"), profiles.map { it.id.value })
        assertEquals(MemberKind.Agent, profiles.single().kind)
        assertEquals(
            listOf(
                "projection_value_rejected source=home_dm_peer field=member_kind value=future-kind",
                "projection_value_rejected source=home_dm_peer field=member_kind value=human",
                "projection_value_rejected source=home_dm_peer field=member_kind value=system",
            ),
            logs,
        )
        assertTrue(logs.none { "secret-peer-id" in it || "Secret Person" in it })
    }

    @Test
    fun usesSelectedServerUnreadSummaryForInboxTotal() {
        val result =
            parseRaftHome(
                payload =
                    homePayload(
                        channels =
                            listOf(
                                HomeChannelWireDto(
                                    id = "channel-1",
                                    name = "native",
                                    type = "channel",
                                    joined = true
                                )
                            ),
                        unreadCounts = mapOf(ChannelId("channel-1") to 4),
                        serverUnreadSummary =
                            listOf(
                                ServerUnreadSummaryDto(
                                    serverId = "server-1",
                                    unreadCount = 9,
                                    serverPushMuted = false,
                                    activityUnreadCount = 0,
                                ),
                                ServerUnreadSummaryDto(
                                    serverId = "server-2",
                                    unreadCount = 5,
                                    serverPushMuted = true,
                                    activityUnreadCount = 3,
                                )
                            )
                    ),
                session = sampleSession()
            )

        assertTrue(result is DataResult.Content)
        assertEquals(4, result.data.channels.single().unreadCount)
        assertEquals(9, result.data.unreadTotal)
        assertEquals(9, result.data.server.unreadCount)
        assertEquals(mapOf("server-1" to 9, "server-2" to 5), result.data.serverUnreadCounts)
        assertEquals(mapOf("server-1" to 0L, "server-2" to 3L), result.data.serverActivityUnreadCounts)
        assertEquals(mapOf("server-1" to false, "server-2" to true), result.data.serverPushMutedByServer)
    }

    @Test
    fun preservesPrivateAndJointChannelMetadata() {
        val result =
            parseRaftHome(
                payload =
                    homePayload(
                        channels =
                            listOf(
                                HomeChannelWireDto(
                                    id = "private-1",
                                    name = "ops",
                                    type = "private",
                                    joined = true
                                ),
                                HomeChannelWireDto(
                                    id = "joint-1",
                                    name = "partner",
                                    type = "joint",
                                    joined = true,
                                    jointChannelId = "joint-root-1",
                                    jointRole = "host",
                                    jointPeerServerId = "server-2",
                                    jointPeerServerName = "Partner",
                                    jointPeerServerSlug = "partner",
                                    jointPeerStatus = "pending"
                                )
                            )
                    ),
                session = sampleSession()
            )

        assertTrue(result is DataResult.Content)
        val private = result.data.channels.first { it.channelId?.value == "private-1" }
        val joint = result.data.channels.first { it.channelId?.value == "joint-1" }
        assertEquals("private", private.channelType)
        assertEquals("joint", joint.channelType)
        assertEquals("joint-root-1", joint.jointChannelId)
        assertEquals("host", joint.jointRole)
        assertEquals("server-2", joint.jointPeerServerId?.value)
        assertEquals("Partner", joint.jointPeerServerName)
        assertEquals("partner", joint.jointPeerServerSlug)
        assertEquals("pending", joint.jointPeerStatus)
    }

    @Test
    fun decodesServerUnreadSummaryUnreadCountFromServerContract() {
        val serverUnreadSummary =
            json.decodeFromString(
                ListSerializer(ServerUnreadSummaryDto.serializer()),
                """[{"serverId":"server-1","unreadCount":12}]"""
            )

        val result =
            parseRaftHome(
                payload =
                    homePayload(
                        channels =
                            listOf(
                                HomeChannelWireDto(
                                    id = "channel-1",
                                    name = "native",
                                    type = "channel",
                                    joined = true
                                )
                            ),
                        unreadCounts = mapOf(ChannelId("channel-1") to 4),
                        serverUnreadSummary = serverUnreadSummary
                    ),
                session = sampleSession()
            )

        assertTrue(result is DataResult.Content)
        assertEquals(4, result.data.channels.single().unreadCount)
        assertEquals(12, result.data.unreadTotal)
        assertEquals(12, result.data.server.unreadCount)
    }

    @Test
    fun optionalActivityFieldKeepsAbsentAndInvalidValuesUnknown() {
        val serverUnreadSummary =
            json.decodeFromString(
                ListSerializer(ServerUnreadSummaryDto.serializer()),
                """[
                    {"serverId":"known-zero","unreadCount":7,"serverPushMuted":true,"activityUnreadCount":0},
                    {"serverId":"absent","unreadCount":5,"serverPushMuted":false},
                    {"serverId":"invalid","unreadCount":4,"serverPushMuted":false,"activityUnreadCount":-1}
                ]""".trimIndent()
            )

        assertEquals(0L, serverUnreadSummary[0].knownActivityUnreadCount)
        assertNull(serverUnreadSummary[1].knownActivityUnreadCount)
        assertNull(serverUnreadSummary[2].knownActivityUnreadCount)
    }

    @Test
    fun dropsArchivedChannels() {
        val result =
            parseRaftHome(
                payload =
                    homePayload(
                        channels =
                            listOf(
                                HomeChannelWireDto(
                                    id = "c1",
                                    name = "all",
                                    type = "channel",
                                    joined = true,
                                    archivedAt = null
                                ),
                                HomeChannelWireDto(
                                    id = "c2",
                                    name = "old",
                                    type = "channel",
                                    joined = true,
                                    archivedAt = "2024-01-01T00:00:00.000Z"
                                )
                            )
                    ),
                session = sampleSession()
            )

        assertTrue(result is DataResult.Content)
        assertEquals(listOf("all"), result.data.channels.map { it.name })
    }

    @Test
    fun directMessagePrefersPeerAvatarUrlOverGravatarHash() {
        val payload =
            homePayload(
                directMessages =
                    listOf(
                        HomeDirectMessageWireDto(
                            id = "dm-1",
                            name = "agent-one",
                            type = "dm",
                            peerType = "agent",
                            peerId = "agent-1",
                            peerName = "agent-one",
                            peerDisplayName = "Agent One",
                            peerAvatarUrl = "https://cdn.slock.ai/a.png"
                        )
                    )
            )
        val result =
            parseRaftHome(
                payload = payload,
                session = sampleSession()
            )

        assertTrue(result is DataResult.Content)
        val directMessage = result.data.directMessages.single()
        assertEquals("https://cdn.slock.ai/a.png", directMessage.directMessagePeerAvatarWireValue)
        assertEquals(
            "https://cdn.slock.ai/a.png",
            directMessage
                .toSharedConversationDirectoryProjection(ServerId("server-1"))
                ?.avatarWireValue,
        )
        val profile = payload.directMessagePeerProfiles(ServerId("server-1")).single()
        assertEquals("agent-1", profile.id.value)
        assertEquals("https://cdn.slock.ai/a.png", profile.avatar?.wireValue)
    }

    @Test
    fun directMessageNameFallsBackFromDisplayNameToPeerNameToId() {
        val result =
            parseRaftHome(
                payload =
                    homePayload(
                        directMessages =
                            listOf(
                                HomeDirectMessageWireDto(
                                    id = "dm-a",
                                    name = "chan-a",
                                    type = "dm",
                                    peerType = "agent",
                                    peerId = "a",
                                    peerName = "alpha",
                                    peerDisplayName = "Alpha"
                                ),
                                HomeDirectMessageWireDto(
                                    id = "dm-b",
                                    name = "chan-b",
                                    type = "dm",
                                    peerType = "user",
                                    peerId = "b",
                                    peerName = "bravo",
                                    peerDisplayName = null
                                ),
                                HomeDirectMessageWireDto(
                                    id = "dm-c",
                                    name = "chan-c",
                                    type = "dm",
                                    peerType = "user",
                                    peerId = "c",
                                    peerName = "",
                                    peerDisplayName = null
                                )
                            )
                    ),
                session = sampleSession()
            )

        assertTrue(result is DataResult.Content)
        assertEquals(listOf("Alpha", "bravo", "dm-c"), result.data.directMessages.map { it.name })
    }

    @Test
    fun logsDirectMessageAvatarMissingWhenBothUrlAndGravatarAreMissing() {
        val warnings = mutableListOf<String>()
        val payload =
            homePayload(
                directMessages =
                    listOf(
                        HomeDirectMessageWireDto(
                            id = "dm-1",
                            name = "agent-one",
                            type = "dm",
                            peerType = "agent",
                            peerId = "agent-1",
                            peerName = "agent-one"
                        )
                    )
            )
        val result =
            parseRaftHome(
                payload = payload,
                session = sampleSession(),
                warningLogger = warnings::add
            )

        assertTrue(result is DataResult.Content)
        assertNull(payload.directMessagePeerProfiles(ServerId("server-1")).single().avatar?.wireValue)
        assertTrue(warnings.any { it.contains("dm_avatar_missing") && it.contains("peerId=agent-1") })
    }

    @Test
    fun missingSessionServerReturnsEmpty() {
        val result =
            parseRaftHome(
                payload = homePayload(serverId = ServerId("unknown-server")),
                session = sampleSession()
            )

        assertTrue(result is DataResult.Empty)
        assertEquals(EmptyReason.NoChannels, result.reason)
    }

    private fun homePayload(
        serverId: ServerId = ServerId("server-1"),
        channels: List<HomeChannelWireDto> = emptyList(),
        directMessages: List<HomeDirectMessageWireDto> = emptyList(),
        unreadCounts: Map<ChannelId, Int> = emptyMap(),
        serverUnreadSummary: List<ServerUnreadSummaryDto> = emptyList()
    ): HomeNetworkPayload =
        HomeNetworkPayload(
            serverId = serverId,
            channels = channels,
            directMessages = directMessages,
            unreadCounts = unreadCounts,
            serverUnreadSummary = serverUnreadSummary
        )

    private fun sampleSession(): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "artin@slock.ai", displayName = "Artin"),
            accessToken = "token-a",
            refreshToken = "token-r",
            selectedServerId = "server-1",
            servers = listOf(StoredAuthServer(id = "server-1", name = "Slock Community"))
        )
}
