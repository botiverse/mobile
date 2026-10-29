package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `GET /api/artifacts/conversations/:channelId` (slock #11590 `webConversation.ts`,
 * `ArtifactWebConversationArtifacts`): the answer as the server writes it, each card exactly the
 * summaries route's (`webConversation.api.test.ts` checks the two are equal).
 */
class ConversationArtifactsApiTest {
    private fun card(id: String, title: String, kind: String, version: Int, updatedAt: String, home: String) =
        """
        {
          "id": "$id",
          "url": "https://app.raft.build/artifacts/$id",
          "title": "$title",
          "description": null,
          "kind": "$kind",
          "facts": { "sizeBytes": 2048, "fileCount": 1, "pages": 3 },
          "preview": { "kind": "excerpt", "text": "First lines" },
          "version": $version,
          "pinned": false,
          "latestVersion": $version,
          "author": { "type": "agent", "id": "agent-1", "name": "kirby", "displayName": "Kirby", "avatarUrl": null },
          "createdAt": "2026-10-20T08:00:00.000Z",
          "updatedAt": "$updatedAt",
          "commentCount": 2,
          "home": $home
        }
        """.trimIndent()

    private val channelHome = """{ "kind": "conversation", "conversation": { "id": "ch-1", "name": "proj-workspace", "type": "channel" } }"""

    @Test
    fun readsAPageOfCardsNewestFirstWithItsCursor() {
        val body =
            """
            {
              "rows": [
                { "card": ${card("pt_new", "Launch plan", "html", 3, "2026-10-28T09:30:00.000Z", channelHome)} },
                { "card": ${card("pt_old", "Metrics", "csv", 1, "2026-10-27T18:00:00.000Z", channelHome)}, "reason": "later" }
              ],
              "nextCursor": "eyJ1IjoxfQ"
            }
            """.trimIndent()

        val page = parseConversationArtifactsPage(body)!!

        assertEquals(listOf("pt_new", "pt_old"), page.artifacts.map { it.summary.id })
        assertEquals("eyJ1IjoxfQ", page.nextCursor)
        val first = page.artifacts.first()
        assertEquals("Launch plan", first.summary.title)
        assertEquals(ArtifactKind.Html, first.summary.kind)
        assertEquals(3, first.summary.latestVersion)
        assertEquals("2026-10-28T09:30:00.000Z", first.updatedAt)
        assertEquals(ArtifactHome.Conversation("proj-workspace", dm = false), first.summary.home)
        assertEquals(ArtifactKind.Csv, page.artifacts[1].summary.kind)
    }

    @Test
    fun aLastPageAnEmptyPageAndAnAuthorWithoutIdRead() {
        val outsiderAuthor =
            card("pt_dm", "Notes", "markdown", 2, "2026-10-28T09:30:00.000Z", """{ "kind": "conversation", "conversation": { "id": "dm-1", "name": "", "type": "dm", "peerDisplayName": "Artin" } }""")
                .replace(""""id": "agent-1", """, "")
        val last = parseConversationArtifactsPage("""{ "rows": [ { "card": $outsiderAuthor } ], "nextCursor": null }""")!!
        assertEquals(listOf("pt_dm"), last.artifacts.map { it.summary.id })
        assertNull(last.nextCursor)

        val empty = parseConversationArtifactsPage("""{ "rows": [], "nextCursor": null }""")!!
        assertEquals(emptyList(), empty.artifacts)
        assertNull(empty.nextCursor)
    }

    @Test
    fun aCardWithAHomeItCantReadIsLeftOutAndABrokenAnswerIsNone() {
        val body =
            """
            {
              "rows": [
                { "card": ${card("pt_ok", "Kept", "pdf", 1, "2026-10-28T09:30:00.000Z", channelHome)} },
                { "card": ${card("pt_odd", "Odd home", "html", 1, "2026-10-28T09:00:00.000Z", """{ "kind": "galaxy" }""")} }
              ],
              "nextCursor": null
            }
            """.trimIndent()
        assertEquals(listOf("pt_ok"), parseConversationArtifactsPage(body)!!.artifacts.map { it.summary.id })

        assertNull(parseConversationArtifactsPage(null))
        assertNull(parseConversationArtifactsPage("""{ "error": "no such conversation", "code": "TARGET_NOT_FOUND" }"""))
        assertNull(parseConversationArtifactsPage("not json"))
    }

    @Test
    fun asksTheSharedRouteInTheReadersServerAtVisiblePriority() = runTest {
        val network = RecordingNetworkClient(HomeNetworkResponse(200, """{ "rows": [], "nextCursor": null }"""))
        val api = NetworkRaftConversationArtifactsApi(network)

        api.loadConversationArtifacts("server-1", "ch-1", cursor = null)
        api.loadConversationArtifacts("server-1", "ch 1/x", cursor = "eyJ1IjoxfQ")

        assertEquals(
            listOf(
                Recorded(DataRequestPriority.Visible, "artifacts/conversations/ch-1", emptyList(), ServerId("server-1")),
                Recorded(DataRequestPriority.Visible, "artifacts/conversations/ch%201%2Fx", listOf("cursor" to "eyJ1IjoxfQ"), ServerId("server-1")),
            ),
            network.requests,
        )
    }

    @Test
    fun everyRefusalIsNone() = runTest {
        listOf(
            HomeNetworkResponse(403, null, errorBodyText = """{ "error": "artifacts not enabled", "code": "ARTIFACTS_NOT_ENABLED" }"""),
            HomeNetworkResponse(404, null, errorBodyText = """{ "error": "no such conversation", "code": "TARGET_NOT_FOUND" }"""),
            HomeNetworkResponse(429, null, errorBodyText = """{ "error": "rate limited", "code": "RATE_LIMITED" }"""),
            HomeNetworkResponse(200, "<html>"),
        ).forEach { response ->
            assertNull(NetworkRaftConversationArtifactsApi(RecordingNetworkClient(response)).loadConversationArtifacts("s", "c", null), "${response.code}")
        }
    }

    private data class Recorded(val priority: DataRequestPriority, val path: String, val query: List<Pair<String, String>>, val serverId: ServerId?)

    private class RecordingNetworkClient(private val response: HomeNetworkResponse) : RaftNetworkClient {
        val requests = mutableListOf<Recorded>()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?,
        ): HomeNetworkResponse {
            assertEquals("GET", method)
            requests += Recorded(priority, path, query, serverIdOverride)
            return response
        }
    }
}
