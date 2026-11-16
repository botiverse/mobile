package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Core's search, `GET /api/search` (botiverse/slock staging 93207e216000f5abb7d5451453812f00e7645eef):
 * `packages/shared/src/webSearchContract.ts` (`WebSearchResponse`, `WebSearchGroup`, `webSearchPath`),
 * `webArtifactsContract.ts` `ArtifactSearchHit`, `workspaceArtifacts.ts` `ArtifactCard`,
 * `agentApiArtifactsContract.ts` `agentApiArtifactSearchMatchSchema`; served by
 * `packages/server/src/routes/search.ts` and `apps/artifacts/webSearch.ts`. The first answer is the
 * one `apps/artifacts/webSearch.api.test.ts` ("titles are found by word ...") asserts for
 * `q=budget team`, with a card as `webCards.ts` writes it.
 */
class WebSearchArtifactsContractTest {
    @Test
    fun readsTheArtifactsGroupAsTheServerAnswersIt() {
        val answer = parseWebSearchAnswer(UpstreamAnswer)!!

        val page = assertIs<WebSearchArtifactsGroup.Page>(answer.artifacts)
        assertNull(page.nextCursor)
        val hit = page.hits.single()
        assertEquals("pt_7d0c3b5e-9a51-4f7e-8b8e-2f5b1c9d0e11", hit.id)
        assertEquals("Budget for the team", hit.row.summary.title)
        assertEquals(ArtifactKind.Markdown, hit.row.summary.kind)
        assertEquals(ArtifactHome.Conversation("team", dm = false, private = true), hit.row.summary.home)
        assertEquals("2026-11-12T10:30:00.000Z", hit.row.summary.updatedAt)
        assertEquals("Kirby", hit.row.author.displayName)
        assertEquals("user:user-1", hit.row.author.filterValue)
        assertEquals(listOf(ArtifactSearchMatch("title", "Budget for the team", listOf(0 until 6, 15 until 19))), hit.matches)
    }

    @Test
    fun aGroupThatTookTooLongOrFailedSaysSo() {
        fun group(error: String) = parseWebSearchAnswer("""{ "groups": [ { "app": "artifacts", "error": "$error" } ] }""")!!.artifacts

        assertEquals(WebSearchArtifactsGroup.Failed(timedOut = true), group("timeout"))
        assertEquals(WebSearchArtifactsGroup.Failed(timedOut = false), group("failed"))
        // web readGroups: anything but "timeout" reads as "failed".
        assertEquals(WebSearchArtifactsGroup.Failed(timedOut = false), group("exploded"))
    }

    @Test
    fun anotherAppsGroupIsLeftOutAndNoGroupIsNoArtifacts() {
        val answer =
            parseWebSearchAnswer(
                """{ "groups": [ { "app": "tasks", "items": [], "nextCursor": null },
                   { "app": "artifacts", "items": [ { "card": ${artifactsHomeCardJson("pt_a", "Plan")}, "matches": [] } ], "nextCursor": "c1" } ] }""",
            )!!
        val page = assertIs<WebSearchArtifactsGroup.Page>(answer.artifacts)
        assertEquals(listOf("pt_a"), page.hits.map { it.id })
        assertEquals("c1", page.nextCursor)
        // Artifacts switched off for the server: no group.
        assertNull(parseWebSearchAnswer("""{ "groups": [] }""")!!.artifacts)
    }

    @Test
    fun unreadableItemsAreLeftOutButAGroupOfNothingReadableIsAFailure() {
        val partly =
            parseWebSearchAnswer(
                """{ "groups": [ { "app": "artifacts", "nextCursor": null, "items": [
                   { "card": ${artifactsHomeCardJson("pt_ok", "Kept")}, "matches": [] },
                   { "card": ${artifactsHomeCardJson("pt_x", "Odd", home = """{ "kind": "galaxy" }""")}, "matches": [] },
                   { "card": { "id": 3 } } ] } ] }""",
            )!!
        assertEquals(listOf("pt_ok"), assertIs<WebSearchArtifactsGroup.Page>(partly.artifacts).hits.map { it.id })

        val none = parseWebSearchAnswer("""{ "groups": [ { "app": "artifacts", "nextCursor": null, "items": [ { "card": null } ] } ] }""")!!
        assertEquals(WebSearchArtifactsGroup.Failed(timedOut = false), none.artifacts)
        assertEquals(
            WebSearchArtifactsGroup.Page(emptyList(), null),
            parseWebSearchAnswer("""{ "groups": [ { "app": "artifacts", "items": [], "nextCursor": null } ] }""")!!.artifacts,
        )
    }

    @Test
    fun anAnswerThatIsNotOneIsNone() {
        assertNull(parseWebSearchAnswer(null))
        assertNull(parseWebSearchAnswer("<html>"))
        assertNull(parseWebSearchAnswer("""{ "error": "q is required", "code": "INVALID_QUERY" }"""))
        // A guessed envelope is not the contract's.
        assertNull(parseWebSearchAnswer("""{ "data": { "groups": [] } }"""))
    }

    @Test
    fun theQueryIsTheWebsWithLimitAndCursorOnlyWhenGiven() {
        assertEquals(listOf("q" to "budget"), webSearchQueryParams("budget"))
        assertEquals(
            listOf("q" to "plan", "app" to "artifacts", "cursor" to "a+b/c=", "limit" to "5"),
            webSearchQueryParams("plan", app = WEB_SEARCH_APP_ARTIFACTS, cursor = "a+b/c=", limit = 5),
        )
        assertEquals("plan", webSearchQueryOrNull("  plan "))
        assertNull(webSearchQueryOrNull("   "))
        assertEquals(200, webSearchQueryOrNull("词".repeat(200))?.length)
        assertNull(webSearchQueryOrNull("a".repeat(201)))
        // Code points, not UTF-16 units: 200 emoji are 400 units and still fit.
        assertEquals(400, webSearchQueryOrNull("😀".repeat(200))?.length)
    }

    @Test
    fun asksTheArtifactsGroupInTheReadersServer() = runTest {
        val network = RecordingNetworkClient(HomeNetworkResponse(200, UpstreamAnswer))
        val source = NetworkRaftWebSearchApi(network).asSearchArtifactsSource()

        val first = source.search("s-1", "budget team", cursor = null, limit = null, priority = DataRequestPriority.Visible)
        source.search("s-1", "budget team", cursor = "c1", limit = 5, priority = DataRequestPriority.Visible)

        assertEquals(1, assertIs<WebSearchArtifactsLoad.Page>(first).hits.size)
        assertEquals(
            listOf(
                Recorded(DataRequestPriority.Visible, "search", listOf("q" to "budget team", "app" to "artifacts"), ServerId("s-1")),
                Recorded(DataRequestPriority.Visible, "search", listOf("q" to "budget team", "app" to "artifacts", "cursor" to "c1", "limit" to "5"), ServerId("s-1")),
            ),
            network.requests,
        )
    }

    @Test
    fun refusalsAndGroupFailuresCarryTheTraceId() = runTest {
        suspend fun load(response: HomeNetworkResponse) =
            NetworkRaftWebSearchApi(RecordingNetworkClient(response)).asSearchArtifactsSource()
                .search("s", "plan", null, null, DataRequestPriority.Visible)
        val headers = mapOf("X-Trace-Id" to listOf(TraceId))

        val timedOut = assertIs<WebSearchArtifactsLoad.GroupFailed>(load(HomeNetworkResponse(200, """{ "groups": [ { "app": "artifacts", "error": "timeout" } ] }""", responseHeaders = headers)))
        assertTrue(timedOut.timedOut)
        assertEquals(TraceId, timedOut.error.traceId)

        val limited = assertIs<WebSearchArtifactsLoad.Failed>(load(HomeNetworkResponse(429, null, errorBodyText = """{ "error": "slow down", "code": "RATE_LIMITED" }""", responseHeaders = headers)))
        assertEquals("RATE_LIMITED", limited.error.serverCode)
        assertEquals(TraceId, limited.error.traceId)
        assertIs<WebSearchArtifactsLoad.Failed>(load(HomeNetworkResponse(500, null, responseHeaders = headers)))
        assertIs<WebSearchArtifactsLoad.Failed>(load(HomeNetworkResponse(200, "<html>")))
        // 400: a server that reads the words differently (web: no groups, no alarm); 404 APP_NOT_FOUND: switched off.
        assertEquals(WebSearchArtifactsLoad.NoGroup, load(HomeNetworkResponse(400, null, errorBodyText = """{ "error": "too long", "code": "INVALID_QUERY" }""")))
        assertEquals(WebSearchArtifactsLoad.NoGroup, load(HomeNetworkResponse(404, null, errorBodyText = """{ "error": "no app", "code": "APP_NOT_FOUND" }""")))
        assertEquals(WebSearchArtifactsLoad.NoGroup, load(HomeNetworkResponse(200, """{ "groups": [] }""")))
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
        const val TraceId = "0af7651916cd43dd8448eb211c80319c"

        val UpstreamAnswer =
            """
            {
              "groups": [
                {
                  "app": "artifacts",
                  "items": [
                    {
                      "card": {
                        "id": "pt_7d0c3b5e-9a51-4f7e-8b8e-2f5b1c9d0e11",
                        "url": "https://app.raft.build/artifacts/pt_7d0c3b5e-9a51-4f7e-8b8e-2f5b1c9d0e11",
                        "title": "Budget for the team",
                        "description": null,
                        "kind": "markdown",
                        "facts": { "sizeBytes": 10, "fileCount": 1, "lines": 1 },
                        "preview": { "kind": "excerpt", "text": "Budget for the team" },
                        "version": 1,
                        "pinned": false,
                        "latestVersion": 1,
                        "author": { "type": "user", "id": "user-1", "name": "kirby", "displayName": "Kirby", "avatarUrl": null },
                        "createdAt": "2026-11-12T10:30:00.000Z",
                        "updatedAt": "2026-11-12T10:30:00.000Z",
                        "commentCount": 0,
                        "home": { "kind": "conversation", "conversation": { "id": "ch-team", "name": "team", "type": "private" } }
                      },
                      "matches": [
                        { "field": "title", "snippet": "Budget for the team", "highlights": [ { "start": 0, "length": 6 }, { "start": 15, "length": 4 } ] }
                      ]
                    }
                  ],
                  "nextCursor": null
                }
              ]
            }
            """.trimIndent()
    }
}
