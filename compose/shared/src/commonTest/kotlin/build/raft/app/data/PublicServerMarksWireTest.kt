package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Wire contract for the member-facing public-server marks, pinned to botiverse/slock@9df0d1b3f:
 * - `GET /api/servers` rows carry `publicPageLive` (packages/server/src/routes/servers.ts:748-770);
 *   the owner-only `publiclyVisible` is stripped. Row columns: services/serverService.ts:275-289.
 * - `GET /api/channels` rows are full `channels` rows (services/channelService.ts:1964), including
 *   `guestVisible` (db/schema.ts:1801) and `archivedAt`.
 */
class PublicServerMarksWireTest {
    private class ServersTransport(private val body: String) : SlockAuthTransport by AuthDataSourceTest.FakeAuthApi() {
        override suspend fun servers(authorization: String): AuthNetworkResponse = AuthNetworkResponse(200, body)
    }

    @Test
    fun serverListCarriesPublicPageLiveAndDefaultsToFalseWhenAbsent() = runBlocking {
        val body =
            """
            [
              {"id":"server-live","name":"Live","avatarUrl":null,"slug":"live","ownerId":"user-1","onboardingAgentId":null,
               "hideHumansFromMembers":false,"plan":"pro","planDowngradedAt":null,"role":"member","serverPushMuted":false,
               "createdAt":"2026-10-01T00:00:00.000Z","serverOrderVersion":3,"publicPageLive":true},
              {"id":"server-private","name":"Private","avatarUrl":null,"slug":"private","ownerId":"user-2","onboardingAgentId":null,
               "hideHumansFromMembers":false,"plan":"hobby","planDowngradedAt":null,"role":"owner","serverPushMuted":false,
               "createdAt":"2026-10-01T00:00:00.000Z","serverOrderVersion":3,"publicPageLive":false},
              {"id":"server-old","name":"Old server build","slug":"old","role":"admin"}
            ]
            """.trimIndent()

        val servers = assertIs<AuthApiResult.Success<List<StoredAuthServer>>>(
            SlockAuthApiClient(ServersTransport(body)).servers("Bearer access")
        ).data

        assertEquals(
            mapOf("server-live" to true, "server-private" to false, "server-old" to false),
            servers.associate { it.id to it.publicPageLive }
        )
        val summaries = servers.map { it.toServerSummary() }
        assertTrue(summaries.first { it.id == ServerId("server-live") }.publicPageLive)
        assertFalse(summaries.first { it.id == ServerId("server-old") }.publicPageLive)
    }

    @Test
    fun loadServersPersistsPublicPageLiveIntoTheSessionStore() = runBlocking {
        val store = InMemorySlockAuthSessionStore(
            authSessionFixture(servers = listOf(StoredAuthServer(id = "server-1", name = "One", slug = "one")))
        )
        val transport =
            ServersTransport("""[{"id":"server-1","name":"One","slug":"one","role":"owner","publicPageLive":true}]""")

        assertIs<SharedAuthResult.Success<StoredAuthSession>>(SharedAuthDataSource(transport, store).loadServers())

        assertEquals(true, store.readSession()?.servers?.single()?.publicPageLive)
    }

    @Test
    fun channelListCarriesGuestVisibleAndDefaultsToFalseWhenAbsent() {
        val server = authServerFixtures().first().toServerSummary()
        val snapshot = assertNotNull(
            parseSharedChannelSnapshot(
                """
                [
                  {"id":"c-public","serverId":"server-1","name":"announcements","type":"channel","description":"News",
                   "guestVisible":true,"guestJoinable":false,"archivedAt":null,"deletedAt":null,"joined":true},
                  {"id":"c-team","serverId":"server-1","name":"team","type":"channel","guestVisible":false,"joined":true},
                  {"id":"c-legacy","name":"legacy","type":"channel","joined":true}
                ]
                """.trimIndent(),
                server
            )
        )

        assertEquals(
            mapOf("c-public" to true, "c-team" to false, "c-legacy" to false),
            snapshot.channels.associate { it.channelId.value to it.guestVisible }
        )
        // HomeChannelRow -> ConversationSummary -> HomeChannelRow keeps the flag.
        val publicRow = snapshot.channels.first { it.channelId.value == "c-public" }
        assertTrue(publicRow.toConversationSummary().guestVisible)
        assertTrue(publicRow.toConversationSummary().toHomeChannelRow().guestVisible)
    }

    @Test
    fun channelDetailCarriesGuestVisibleIntoTheDirectoryProjection() {
        val row = assertNotNull(
            parseSlockChannelDetail(
                """{"id":"c-public","name":"announcements","type":"channel","guestVisible":true,"archivedAt":null,"joined":true}"""
            )
        )
        assertTrue(row.guestVisible)
        val projection = assertNotNull(row.toSharedConversationDirectoryProjection(ServerId("server-1")))
        assertTrue(projection.guestVisible)
        assertFalse(projection.archived)
    }
}
