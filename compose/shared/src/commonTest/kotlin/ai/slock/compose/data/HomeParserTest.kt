package ai.slock.compose.data

import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.ServerId
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
    fun parsesTypedChannelsDmsAndUnreadCounts() {
        val result =
            parseSlockHome(
                payload =
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
                                    peerGravatarHash = "abc123",
                                    peerAvatarUrl = null
                                )
                            ),
                        unreadCounts = mapOf(ChannelId("channel-1") to 4, ChannelId("dm-1") to 2)
                    ),
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
        assertEquals("agent-1", dm.presenceMemberId?.value)
        assertEquals("https://www.gravatar.com/avatar/abc123?s=52&d=404", dm.avatarUrl)
        assertEquals(2, dm.unreadCount)
        assertEquals(6, snapshot.unreadTotal)
    }

    @Test
    fun usesSelectedServerUnreadSummaryForInboxTotal() {
        val result =
            parseSlockHome(
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
                                    unreadCount = 9
                                )
                            )
                    ),
                session = sampleSession()
            )

        assertTrue(result is DataResult.Content)
        assertEquals(4, result.data.channels.single().unreadCount)
        assertEquals(9, result.data.unreadTotal)
        assertEquals(9, result.data.server.unreadCount)
    }

    @Test
    fun preservesPrivateAndJointChannelMetadata() {
        val result =
            parseSlockHome(
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
            parseSlockHome(
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
    fun dropsArchivedChannels() {
        val result =
            parseSlockHome(
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
        val result =
            parseSlockHome(
                payload =
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
                                    peerAvatarUrl = "https://cdn.slock.ai/a.png",
                                    peerGravatarHash = "abc"
                                )
                            )
                    ),
                session = sampleSession()
            )

        assertTrue(result is DataResult.Content)
        assertEquals("https://cdn.slock.ai/a.png", result.data.directMessages.single().avatarUrl)
    }

    @Test
    fun directMessageNameFallsBackFromDisplayNameToPeerNameToId() {
        val result =
            parseSlockHome(
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
        val result =
            parseSlockHome(
                payload =
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
                    ),
                session = sampleSession(),
                warningLogger = warnings::add
            )

        assertTrue(result is DataResult.Content)
        assertNull(result.data.directMessages.single().avatarUrl)
        assertTrue(warnings.any { it.contains("dm_avatar_missing") && it.contains("peerId=agent-1") })
    }

    @Test
    fun missingSessionServerReturnsEmpty() {
        val result =
            parseSlockHome(
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
