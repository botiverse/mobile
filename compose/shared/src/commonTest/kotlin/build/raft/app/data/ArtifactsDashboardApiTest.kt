package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Artifacts home routes (botiverse/slock staging aae2b7a89 `packages/shared/src/webArtifactsContract.ts`
 * `dashboardAll` / `dashboardAuthors` / `dashboardConversations`, `ArtifactWebDashboardAll`,
 * `ArtifactDashboardAllRow`, `ArtifactDashboardAllCounts`; `workspaceArtifacts.ts` `ArtifactCard`,
 * `ArtifactActor`): the answers as the server writes them.
 */
class ArtifactsDashboardApiTest {
    @Test
    fun readsAFirstPageWithItsNewsOpenedTimeAndCounts() {
        val body =
            """
            {
              "rows": [
                { "card": ${artifactsHomeCardJson("pt_a", "Launch plan", author = assistantAuthor)}, "openedAt": "2026-11-07T09:00:00.000Z",
                  "news": { "mentions": 1, "versions": 0, "comments": 3 } },
                { "card": ${artifactsHomeCardJson("pt_b", "Metrics", home = publicHome, extra = "\"fromAnotherServer\": true,")}, "openedAt": null,
                  "news": { "mentions": 0, "versions": 0, "comments": 0 } }
              ],
              "nextCursor": "c1",
              "counts": { "total": 24, "filtered": 5, "relevant": 2 }
            }
            """.trimIndent()

        val page = parseArtifactsHomePage(body)!!

        assertEquals(listOf("pt_a", "pt_b"), page.rows.map { it.summary.id })
        assertEquals("c1", page.nextCursor)
        assertEquals(ArtifactsHomeCounts(total = 24, filtered = 5, relevant = 2), page.counts)
        val first = page.rows[0]
        assertEquals("2026-11-07T09:00:00.000Z", first.openedAt)
        assertEquals(ArtifactsHomeNews(mentioned = true, newerVersion = false, newComments = 3), first.news)
        assertEquals("agent:agent-1", first.author.filterValue)
        assertEquals(ArtifactsHomeBehalf("user-9", "Remy"), first.author.onBehalfOf)
        val second = page.rows[1]
        assertNull(second.openedAt)
        assertFalse(second.news.any)
        assertTrue(second.fromAnotherServer)
        assertEquals(ArtifactHome.Public, second.summary.home)
    }

    @Test
    fun anOlderServerSendsNoNewsNoOpenedTimeAndNoCounts() {
        val page = parseArtifactsHomePage("""{ "rows": [ { "card": ${artifactsHomeCardJson("pt_a", "Plan")} } ], "nextCursor": null }""")!!
        assertNull(page.counts)
        assertNull(page.nextCursor)
        assertNull(page.rows.single().openedAt)
        assertEquals(ArtifactsHomeNews(), page.rows.single().news)
        assertNull(parseArtifactsHomePage("""{ "rows": [], "nextCursor": null, "counts": null }""")!!.counts)
    }

    @Test
    fun aCardWithAHomeItCantReadIsLeftOutAndABrokenAnswerIsNone() {
        val body = """{ "rows": [ { "card": ${artifactsHomeCardJson("pt_ok", "Kept")} }, { "card": ${artifactsHomeCardJson("pt_x", "Odd", home = """{ "kind": "galaxy" }""")} } ], "nextCursor": null }"""
        assertEquals(listOf("pt_ok"), parseArtifactsHomePage(body)!!.rows.map { it.summary.id })
        assertNull(parseArtifactsHomePage(null))
        assertNull(parseArtifactsHomePage("<html>"))
        assertNull(parseArtifactsHomePage("""{ "code": "ARTIFACTS_NOT_ENABLED", "error": "off" }"""))
    }

    @Test
    fun readsTheFilterChoices() {
        val authors = parseArtifactsHomeAuthors("""{ "authors": [ ${assistantAuthor.dropLast(1)}, "count": 4 }, { "type": "user", "id": "u-2", "name": "pat", "displayName": "Pat", "avatarUrl": null, "count": 1 } ] }""")!!
        assertEquals(listOf("agent:agent-1", "user:u-2"), authors.map { it.filterValue })

        val conversations =
            parseArtifactsHomeConversations(
                """
                { "conversations": [
                  { "id": "c-1", "name": "proj-workspace", "type": "channel", "count": 3, "updatedAt": "2026-11-07T09:00:00.000Z" },
                  { "id": "c-2", "name": "remy", "type": "dm", "peerDisplayName": "Remy", "count": 1, "updatedAt": "2026-11-06T09:00:00.000Z",
                    "peer": { "type": "user", "id": "u-3", "name": "remy", "displayName": "Remy", "avatarUrl": null } },
                  { "id": "c-3", "name": "Joint", "type": "joint", "count": 1, "updatedAt": "2026-11-05T09:00:00.000Z" }
                ] }
                """.trimIndent()
            )!!
        assertEquals(listOf("#proj-workspace", "Remy", "Joint"), conversations.map { it.label })
        assertEquals("user:u-3", conversations[1].peer?.filterValue)
        assertNull(parseArtifactsHomeAuthors("nope"))
    }

    @Test
    fun theQueryAsksWhatTheWebAsks() {
        val conversation = ArtifactsHomeConversation("c-1", "proj", "channel")
        // The default sort, recently opened, is asked for; recently updated is the server's own (no `sort`).
        assertEquals(listOf("sort" to "opened"), ArtifactsHomeQuery().params())
        assertEquals(emptyList(), ArtifactsHomeQuery(sort = ArtifactsHomeSort.Updated).params())
        assertEquals(
            listOf("conversation" to "c-1", "author" to "user:u-1", "q" to "plan", "relevant" to "1", "cursor" to "c9"),
            ArtifactsHomeQuery(ArtifactsHomePlace.Conversation(conversation), "user:u-1", " plan ", ArtifactsHomeSort.Opened, relevant = true).params("c9"),
        )
        assertEquals(listOf("home" to "public", "sort" to "opened"), ArtifactsHomeQuery(ArtifactsHomePlace.Home(ArtifactsHomeScope.Public)).params())
        assertFalse(ArtifactsHomeQuery(sort = ArtifactsHomeSort.Updated).narrowed)
        assertTrue(ArtifactsHomeQuery(relevant = true).narrowed)
    }

    @Test
    fun asksTheRoutesInTheReadersServer() = runTest {
        val network = RecordingNetworkClient(HomeNetworkResponse(200, """{ "rows": [], "nextCursor": null }"""))
        val api = NetworkRaftArtifactsDashboardApi(network)

        api.loadArtifactsHomePage("s-1", ArtifactsHomeQuery(q = "x"), cursor = null, priority = DataRequestPriority.Foreground)
        api.loadArtifactsHomeAuthors("s-1", " pa ")
        api.loadArtifactsHomeConversations("s-1", "")

        assertEquals(
            listOf(
                Recorded(DataRequestPriority.Foreground, "artifacts/dashboard/all", listOf("q" to "x", "sort" to "opened"), ServerId("s-1")),
                Recorded(DataRequestPriority.Visible, "artifacts/dashboard/authors", listOf("q" to "pa"), ServerId("s-1")),
                Recorded(DataRequestPriority.Visible, "artifacts/dashboard/conversations", emptyList(), ServerId("s-1")),
            ),
            network.requests,
        )
    }

    @Test
    fun refusalsReadAsOffMissingOrFailed() = runTest {
        suspend fun load(response: HomeNetworkResponse) =
            NetworkRaftArtifactsDashboardApi(RecordingNetworkClient(response)).loadArtifactsHomePage("s", ArtifactsHomeQuery(), null, DataRequestPriority.Visible)

        assertEquals(ArtifactsHomeLoad.Off, load(HomeNetworkResponse(403, null, errorBodyText = """{ "error": "off", "code": "ARTIFACTS_NOT_ENABLED" }""")))
        val forbidden = assertIs<ArtifactsHomeLoad.Failed>(load(HomeNetworkResponse(403, null, errorBodyText = """{ "error": "no", "code": "FORBIDDEN" }""")))
        assertEquals("FORBIDDEN", forbidden.error.serverCode)
        assertIs<ArtifactsHomeLoad.Missing>(load(HomeNetworkResponse(404, null)))
        assertIs<ArtifactsHomeLoad.Failed>(load(HomeNetworkResponse(429, null, errorBodyText = """{ "code": "RATE_LIMITED" }""")))
        assertIs<ArtifactsHomeLoad.Failed>(load(HomeNetworkResponse(200, "<html>")))
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

    private companion object {
        val assistantAuthor =
            """{ "type": "agent", "id": "agent-1", "name": "ottie", "displayName": "Ottie", "avatarUrl": null, "onBehalfOf": { "id": "user-9", "name": "remy", "displayName": "Remy" } }"""
        const val publicHome = """{ "kind": "public" }"""
    }
}

/** An `ArtifactCard` as the dashboard sends it (shared by the Artifacts home tests). */
internal fun artifactsHomeCardJson(
    id: String,
    title: String,
    home: String = """{ "kind": "conversation", "conversation": { "id": "ch-1", "name": "proj-workspace", "type": "channel" } }""",
    author: String = """{ "type": "user", "id": "user-1", "name": "kirby", "displayName": "Kirby", "avatarUrl": null }""",
    extra: String = "",
): String =
    """
    {
      "id": "$id",
      "url": "https://app.raft.build/artifacts/$id",
      "title": "$title",
      "description": null,
      "kind": "html",
      "facts": { "sizeBytes": 2048, "fileCount": 1 },
      "preview": { "kind": "none" },
      "version": 2,
      "pinned": false,
      "latestVersion": 2,
      "author": $author,
      "createdAt": "2026-10-20T08:00:00.000Z",
      "updatedAt": "2026-11-07T08:00:00.000Z",
      $extra
      "commentCount": 4,
      "home": $home
    }
    """.trimIndent()
