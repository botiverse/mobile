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
                                    {"directMessages":[{"channelId":"dm-1","peer":{"id":"agent-1","displayName":"Agent","kind":"agent","presence":"active"}}]}
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
        assertEquals(6, snapshot.unreadTotal)
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
