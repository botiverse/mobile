package ai.slock.compose.data

import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.Presence
import ai.slock.compose.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HomeParserTest {
    @Test
    fun parsesHomePayloadIntoSharedSnapshot() {
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
                                    {"data":{"channels":[{"id":"channel-1","name":"native","unreadCount":3,"memberCount":7}]}}
                                    """.trimIndent()
                            ),
                        directMessages =
                            HomeNetworkResponse(
                                code = 200,
                                bodyText =
                                    """
                                    {"directMessages":[{"channelId":"dm-1","peer":{"id":"agent-1","displayName":"Agent","kind":"agent","presence":"active","gravatarHash":"abc123"}}]}
                                    """.trimIndent()
                            ),
                        unreadCounts =
                            HomeNetworkResponse(
                                code = 200,
                                bodyText = """{"counts":{"channel-1":4,"dm-1":2}}"""
                            ),
                        serverUnreadSummary = HomeNetworkResponse(code = 200, bodyText = """{"unreadCount":24}""")
                    ),
                session = sampleSession()
            )

        assertTrue(result is DataResult.Content)
        val snapshot = result.data
        assertEquals("Slock Community", snapshot.server.name)
        assertEquals("native", snapshot.channels.single().name)
        assertEquals(4, snapshot.channels.single().unreadCount)
        assertEquals(7, snapshot.channels.single().memberCount)
        assertEquals(ConversationKind.DirectMessage, snapshot.directMessages.single().kind)
        assertEquals(Presence.Active, snapshot.directMessages.single().presence)
        assertEquals("https://www.gravatar.com/avatar/abc123?s=52&d=404", snapshot.directMessages.single().avatar?.wireValue)
        assertEquals(6, snapshot.unreadTotal)
    }

    @Test
    fun directMessageNamePrefersPeerFieldsOverChannelName() {
        val result =
            parseSlockHome(
                payload =
                    HomeNetworkPayload(
                        serverId = ServerId("server-1"),
                        channels = HomeNetworkResponse(code = 200, bodyText = """{"channels":[]}"""),
                        directMessages =
                            HomeNetworkResponse(
                                code = 200,
                                bodyText =
                                    """
                                    {"directMessages":[
                                      {
                                        "id":"dm-stdrc",
                                        "name":"artin",
                                        "type":"dm",
                                        "peerId":"user-2",
                                        "peerName":"stdrc",
                                        "peerDisplayName":"stdrc",
                                        "peerType":"user",
                                        "peerAvatarUrl":"https://cdn.slock.ai/avatars/users/stdrc.webp"
                                      }
                                    ]}
                                    """.trimIndent()
                            ),
                        unreadCounts = HomeNetworkResponse(code = 200, bodyText = """{"counts":{}}"""),
                        serverUnreadSummary = HomeNetworkResponse(code = 200, bodyText = """{"unreadCount":0}""")
                    ),
                session = sampleSession()
            )

        assertTrue(result is DataResult.Content)
        val directMessage = result.data.directMessages.single()
        assertEquals("stdrc", directMessage.name)
        assertEquals("user-2", directMessage.presenceMemberId?.value)
    }

    @Test
    fun directMessageNameDoesNotFallbackToRootNameWhenPeerNameIsMissing() {
        val result =
            parseSlockHome(
                payload =
                    HomeNetworkPayload(
                        serverId = ServerId("server-1"),
                        channels = HomeNetworkResponse(code = 200, bodyText = """{"channels":[]}"""),
                        directMessages =
                            HomeNetworkResponse(
                                code = 200,
                                bodyText =
                                    """
                                    {"directMessages":[
                                      {
                                        "id":"dm-current-user-root-name",
                                        "name":"artin",
                                        "type":"dm",
                                        "peerId":"user-2",
                                        "peerType":"user"
                                      }
                                    ]}
                                    """.trimIndent()
                            ),
                        unreadCounts = HomeNetworkResponse(code = 200, bodyText = """{"counts":{}}"""),
                        serverUnreadSummary = HomeNetworkResponse(code = 200, bodyText = """{"unreadCount":0}""")
                    ),
                session = sampleSession()
            )

        assertTrue(result is DataResult.Content)
        assertEquals("dm-current-user-root-name", result.data.directMessages.single().name)
    }

    @Test
    fun parsesDirectMessagePixelAvatarWireValues() {
        val result =
            parseSlockHome(
                payload =
                    HomeNetworkPayload(
                        serverId = ServerId("server-1"),
                        channels = HomeNetworkResponse(code = 200, bodyText = """{"channels":[]}"""),
                        directMessages =
                            HomeNetworkResponse(
                                code = 200,
                                bodyText =
                                    """
                                    {"directMessages":[
                                      {"channelId":"dm-1","peer":{"id":"agent-1","displayName":"Agent One","kind":"agent","avatar":"pixel:heart"}},
                                      {"channelId":"dm-2","peer":{"id":"agent-2","displayName":"Agent Two","kind":"agent","avatar":{"type":"pixel","key":"robot"}}}
                                    ]}
                                    """.trimIndent()
                            ),
                        unreadCounts = HomeNetworkResponse(code = 200, bodyText = """{"counts":{}}"""),
                        serverUnreadSummary = HomeNetworkResponse(code = 200, bodyText = """{"unreadCount":0}""")
                    ),
                session = sampleSession()
            )

        assertTrue(result is DataResult.Content)
        val avatars = result.data.directMessages.map { it.avatar?.wireValue }
        assertEquals(listOf("pixel:heart", "pixel:robot"), avatars)
    }

    @Test
    fun parsesDirectMessageGravatarAvatarObjectWireValues() {
        val result =
            parseSlockHome(
                payload =
                    HomeNetworkPayload(
                        serverId = ServerId("server-1"),
                        channels = HomeNetworkResponse(code = 200, bodyText = """{"channels":[]}"""),
                        directMessages =
                            HomeNetworkResponse(
                                code = 200,
                                bodyText =
                                    """
                                    {"directMessages":[
                                      {"channelId":"dm-1","peer":{"id":"agent-1","displayName":"Agent One","kind":"agent","avatar":{"type":"gravatar","hash":"ABC123"}}},
                                      {"channelId":"dm-2","peer":{"id":"agent-2","displayName":"Agent Two","kind":"agent","avatar":"gravatar:DEF456"}}
                                    ]}
                                    """.trimIndent()
                            ),
                        unreadCounts = HomeNetworkResponse(code = 200, bodyText = """{"counts":{}}"""),
                        serverUnreadSummary = HomeNetworkResponse(code = 200, bodyText = """{"unreadCount":0}""")
                    ),
                session = sampleSession()
            )

        assertTrue(result is DataResult.Content)
        val avatars = result.data.directMessages.map { it.avatar?.wireValue }
        assertEquals(
            listOf(
                "https://www.gravatar.com/avatar/abc123?s=52&d=404",
                "https://www.gravatar.com/avatar/def456?s=52&d=404"
            ),
            avatars
        )
    }

    @Test
    fun parsesDirectMessageImageAvatarAliases() {
        val result =
            parseSlockHome(
                payload =
                    HomeNetworkPayload(
                        serverId = ServerId("server-1"),
                        channels = HomeNetworkResponse(code = 200, bodyText = """{"channels":[]}"""),
                        directMessages =
                            HomeNetworkResponse(
                                code = 200,
                                bodyText =
                                    """
                                    {"directMessages":[
                                      {"channelId":"dm-1","peer":{"id":"agent-1","displayName":"Agent One","kind":"agent","profileImageUrl":"https://cdn.slock.ai/a.png"}},
                                      {"channelId":"dm-2","peer":{"id":"agent-2","displayName":"Agent Two","kind":"agent","pictureUrl":"https://cdn.slock.ai/b.png"}},
                                      {"channelId":"dm-3","peerImageUrl":"https://cdn.slock.ai/c.png","peerDisplayName":"Agent Three","kind":"agent"}
                                    ]}
                                    """.trimIndent()
                            ),
                        unreadCounts = HomeNetworkResponse(code = 200, bodyText = """{"counts":{}}"""),
                        serverUnreadSummary = HomeNetworkResponse(code = 200, bodyText = """{"unreadCount":0}""")
                    ),
                session = sampleSession()
            )

        assertTrue(result is DataResult.Content)
        val avatars = result.data.directMessages.map { it.avatar?.wireValue }
        assertEquals(
            listOf(
                "https://cdn.slock.ai/a.png",
                "https://cdn.slock.ai/b.png",
                "https://cdn.slock.ai/c.png"
            ),
            avatars
        )
    }

    @Test
    fun parsesDirectMessageGravatarUrlWithoutRewritingItAsHash() {
        val result =
            parseSlockHome(
                payload =
                    HomeNetworkPayload(
                        serverId = ServerId("server-1"),
                        channels = HomeNetworkResponse(code = 200, bodyText = """{"channels":[]}"""),
                        directMessages =
                            HomeNetworkResponse(
                                code = 200,
                                bodyText =
                                    """
                                    {"directMessages":[
                                      {"channelId":"dm-1","peer":{"id":"agent-1","displayName":"Agent One","kind":"agent","gravatarUrl":"https://www.gravatar.com/avatar/abc123?s=52&d=404"}}
                                    ]}
                                    """.trimIndent()
                            ),
                        unreadCounts = HomeNetworkResponse(code = 200, bodyText = """{"counts":{}}"""),
                        serverUnreadSummary = HomeNetworkResponse(code = 200, bodyText = """{"unreadCount":0}""")
                    ),
                session = sampleSession()
            )

        assertTrue(result is DataResult.Content)
        assertEquals(
            "https://www.gravatar.com/avatar/abc123?s=52&d=404",
            result.data.directMessages.single().avatar?.wireValue
        )
    }

    @Test
    fun logsDirectMessageAvatarFieldKeysWhenAvatarIsMissing() {
        val warnings = mutableListOf<String>()

        val result =
            parseSlockHome(
                payload =
                    HomeNetworkPayload(
                        serverId = ServerId("server-1"),
                        channels = HomeNetworkResponse(code = 200, bodyText = """{"channels":[]}"""),
                        directMessages =
                            HomeNetworkResponse(
                                code = 200,
                                bodyText =
                                    """
                                    {"directMessages":[
                                      {"channelId":"dm-1","peer":{"id":"agent-1","displayName":"Agent One","kind":"agent"}}
                                    ]}
                                    """.trimIndent()
                            ),
                        unreadCounts = HomeNetworkResponse(code = 200, bodyText = """{"counts":{}}"""),
                        serverUnreadSummary = HomeNetworkResponse(code = 200, bodyText = """{"unreadCount":0}""")
                    ),
                session = sampleSession(),
                warningLogger = warnings::add
            )

        assertTrue(result is DataResult.Content)
        assertTrue(warnings.any { it.contains("dm_avatar_missing") && it.contains("peerId=agent-1") })
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
