package ai.slock.compose.data

import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeParserTest {
    @Test
    fun parsesCanonicalChannelsDmsAndUnreadCounts() {
        val result =
            parseSlockHome(
                payload =
                    HomeNetworkPayload(
                        serverId = ServerId("server-1"),
                        channels =
                            HomeNetworkResponse(
                                code = 200,
                                bodyText =
                                    """
                                    [
                                      {
                                        "id": "channel-1",
                                        "serverId": "server-1",
                                        "name": "native",
                                        "type": "channel",
                                        "description": "Native client work",
                                        "joined": true,
                                        "archivedAt": null,
                                        "lastMessageAt": "2024-09-01T00:00:00.000Z"
                                      }
                                    ]
                                    """.trimIndent()
                            ),
                        directMessages =
                            HomeNetworkResponse(
                                code = 200,
                                bodyText =
                                    """
                                    [
                                      {
                                        "id": "dm-1",
                                        "name": "agent-one",
                                        "type": "dm",
                                        "description": null,
                                        "peerType": "agent",
                                        "peerId": "agent-1",
                                        "peerName": "agent-one",
                                        "peerDisplayName": "Agent One",
                                        "peerGravatarHash": "abc123",
                                        "peerAvatarUrl": null
                                      }
                                    ]
                                    """.trimIndent()
                            ),
                        unreadCounts =
                            HomeNetworkResponse(
                                code = 200,
                                bodyText = """{"channel-1":4,"dm-1":2}"""
                            ),
                        serverUnreadSummary = HomeNetworkResponse(code = 200, bodyText = "[]")
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
    fun dropsArchivedChannels() {
        val result =
            parseSlockHome(
                payload =
                    HomeNetworkPayload(
                        serverId = ServerId("server-1"),
                        channels =
                            HomeNetworkResponse(
                                code = 200,
                                bodyText =
                                    """
                                    [
                                      {"id":"c1","name":"all","type":"channel","joined":true,"archivedAt":null},
                                      {"id":"c2","name":"old","type":"channel","joined":true,"archivedAt":"2024-01-01T00:00:00.000Z"}
                                    ]
                                    """.trimIndent()
                            ),
                        directMessages = HomeNetworkResponse(code = 200, bodyText = "[]"),
                        unreadCounts = HomeNetworkResponse(code = 200, bodyText = "{}"),
                        serverUnreadSummary = HomeNetworkResponse(code = 200, bodyText = "[]")
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
                    HomeNetworkPayload(
                        serverId = ServerId("server-1"),
                        channels = HomeNetworkResponse(code = 200, bodyText = "[]"),
                        directMessages =
                            HomeNetworkResponse(
                                code = 200,
                                bodyText =
                                    """
                                    [
                                      {
                                        "id":"dm-1",
                                        "name":"agent-one",
                                        "type":"dm",
                                        "peerType":"agent",
                                        "peerId":"agent-1",
                                        "peerName":"agent-one",
                                        "peerDisplayName":"Agent One",
                                        "peerAvatarUrl":"https://cdn.slock.ai/a.png",
                                        "peerGravatarHash":"abc"
                                      }
                                    ]
                                    """.trimIndent()
                            ),
                        unreadCounts = HomeNetworkResponse(code = 200, bodyText = "{}"),
                        serverUnreadSummary = HomeNetworkResponse(code = 200, bodyText = "[]")
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
                    HomeNetworkPayload(
                        serverId = ServerId("server-1"),
                        channels = HomeNetworkResponse(code = 200, bodyText = "[]"),
                        directMessages =
                            HomeNetworkResponse(
                                code = 200,
                                bodyText =
                                    """
                                    [
                                      {"id":"dm-a","name":"chan-a","type":"dm","peerType":"agent","peerId":"a","peerName":"alpha","peerDisplayName":"Alpha"},
                                      {"id":"dm-b","name":"chan-b","type":"dm","peerType":"user","peerId":"b","peerName":"bravo","peerDisplayName":null},
                                      {"id":"dm-c","name":"chan-c","type":"dm","peerType":"user","peerId":"c","peerName":"","peerDisplayName":null}
                                    ]
                                    """.trimIndent()
                            ),
                        unreadCounts = HomeNetworkResponse(code = 200, bodyText = "{}"),
                        serverUnreadSummary = HomeNetworkResponse(code = 200, bodyText = "[]")
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
                    HomeNetworkPayload(
                        serverId = ServerId("server-1"),
                        channels = HomeNetworkResponse(code = 200, bodyText = "[]"),
                        directMessages =
                            HomeNetworkResponse(
                                code = 200,
                                bodyText =
                                    """
                                    [
                                      {"id":"dm-1","name":"agent-one","type":"dm","peerType":"agent","peerId":"agent-1","peerName":"agent-one"}
                                    ]
                                    """.trimIndent()
                            ),
                        unreadCounts = HomeNetworkResponse(code = 200, bodyText = "{}"),
                        serverUnreadSummary = HomeNetworkResponse(code = 200, bodyText = "[]")
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
                payload =
                    HomeNetworkPayload(
                        serverId = ServerId("unknown-server"),
                        channels = HomeNetworkResponse(code = 200, bodyText = "[]"),
                        directMessages = HomeNetworkResponse(code = 200, bodyText = "[]"),
                        unreadCounts = HomeNetworkResponse(code = 200, bodyText = "{}"),
                        serverUnreadSummary = HomeNetworkResponse(code = 200, bodyText = "[]")
                    ),
                session = sampleSession()
            )

        assertTrue(result is DataResult.Empty)
        assertEquals(EmptyReason.NoChannels, result.reason)
    }

    private fun sampleSession(): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "artin@slock.ai", displayName = "Artin"),
            accessToken = "token-a",
            refreshToken = "token-r",
            selectedServerId = "server-1",
            servers = listOf(StoredAuthServer(id = "server-1", name = "Slock Community"))
        )
}
