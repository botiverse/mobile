package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Core's channels and people groups of `GET /api/search` (botiverse/slock staging
 * df474620dfa395176661f8d98705ee898e71ff17 + #15387 head 0c6271f8): `packages/shared/src/webSearchContract.ts`
 * (`ChannelSearchHit`, `PersonSearchHit`, `WebSearchCoreGroup`), `docs/contracts/web-search.md`. The
 * items are the ones `packages/server/src/routes/searchCoreGroups.api.test.ts` asserts for its
 * world (`w-alpha` joined, `w-beta` not joined, `w-gamma` archived, `w-joint` a joint), ids made
 * concrete.
 */
class WebSearchCoreGroupsContractTest {
    @Test
    fun readsTheChannelsGroupAsTheServerAnswersIt() {
        val page = assertIs<WebSearchCoreGroupLoad.Page<SearchChannelHit>>(channels(UpstreamChannels))

        assertEquals(listOf("ch-alpha", "ch-beta", "ch-gamma", "ch-joint"), page.hits.map { it.id })
        assertEquals(7, page.total)
        assertEquals("channels.4.tag", page.nextCursor)
        assertEquals(
            SearchChannelHit(
                id = "ch-alpha",
                name = "zeta01-alpha",
                description = null,
                type = "channel",
                archived = false,
                joined = true,
                external = false,
                guest = false,
                canPost = true,
                requiredAction = null,
            ),
            page.hits[0],
        )
        val beta = page.hits[1]
        assertEquals(listOf(false, false, SearchRequiredAction.JoinChannel), listOf(beta.joined, beta.canPost, beta.requiredAction))
        assertEquals("Where zeta01 keeps notes", beta.description)
        val gamma = page.hits[2]
        assertEquals(listOf(true, true, false), listOf(gamma.archived, gamma.joined, gamma.canPost))
        assertNull(gamma.requiredAction)
        val joint = page.hits[3]
        assertEquals("joint", joint.type)
        assertEquals(true to true, joint.external to joint.guest)
    }

    @Test
    fun readsThePeopleGroupAsTheServerAnswersIt() {
        val page = assertIs<WebSearchCoreGroupLoad.Page<SearchPersonHit>>(people(UpstreamPeople))

        assertEquals(listOf("human:u-guest", "agent:a-bot"), page.hits.map { it.key })
        assertEquals(2, page.total)
        assertNull(page.nextCursor)
        assertEquals(
            SearchPersonHit(
                agent = false,
                id = "u-guest",
                name = "gwen",
                displayName = "ZETA01 Guest",
                avatarUrl = null,
                role = "guest",
                guest = true,
                dmChannelId = null,
                canPost = false,
                requiredAction = SearchRequiredAction.CreateDm,
            ),
            page.hits[0],
        )
        val bot = page.hits[1]
        assertEquals(listOf<Any?>(true, "dm-1", true, null), listOf(bot.agent, bot.dmChannelId, bot.canPost, bot.requiredAction))
        assertEquals("https://cdn.example/a-bot.png", bot.avatarUrl)
    }

    @Test
    fun aGroupErrorAnUnreadablePageAndUnknownValues() {
        assertEquals("web_search_group_timeout", assertIs<WebSearchCoreGroupLoad.Failed>(channels("""{ "groups": [ { "app": "channels", "error": "timeout" } ] }""")).error.code)
        assertEquals("web_search_group_failed", assertIs<WebSearchCoreGroupLoad.Failed>(channels("""{ "groups": [ { "app": "channels", "error": "exploded" } ] }""")).error.code)
        // Nothing for these words: an empty page, not a failure.
        assertEquals(WebSearchCoreGroupLoad.Page<SearchChannelHit>(emptyList(), null, 0), channels("""{ "groups": [ { "app": "channels", "items": [], "nextCursor": null, "total": 0 } ] }"""))
        // A type this client doesn't know is a generic channel; a person of an unknown kind is left out.
        val odd =
            assertIs<WebSearchCoreGroupLoad.Page<SearchChannelHit>>(
                channels("""{ "groups": [ { "app": "channels", "nextCursor": null, "total": 1, "items": [ ${channelJson("c1", type = "galaxy")} ] } ] }"""),
            )
        assertEquals("channel", odd.hits.single().type)
        val partly =
            assertIs<WebSearchCoreGroupLoad.Page<SearchPersonHit>>(
                people(
                    """{ "groups": [ { "app": "people", "nextCursor": null, "total": 2, "items": [
                       ${personJson("u1")}, ${personJson("x1", kind = "robot")} ] } ] }""",
                ),
            )
        assertEquals(listOf("human:u1"), partly.hits.map { it.key })
        // Rows sent and none readable: a failure, not "nothing found".
        assertIs<WebSearchCoreGroupLoad.Failed>(channels("""{ "groups": [ { "app": "channels", "nextCursor": null, "total": 1, "items": [ { "id": 3 } ] } ] }"""))
        // Not an answer, or another group's: none.
        assertNull(parseWebSearchCoreGroup("<html>", SearchCoreGroup.Channels, ::readChannelSearchHit))
        assertNull(parseWebSearchCoreGroup("""{ "data": { "groups": [] } }""", SearchCoreGroup.Channels, ::readChannelSearchHit))
        assertNull(parseWebSearchCoreGroup(UpstreamPeople, SearchCoreGroup.Channels, ::readChannelSearchHit))
    }

    @Test
    fun asksEachGroupAloneInTheReadersServerAndPagesWithItsCursor() = runTest {
        val network = RecordingNetworkClient(HomeNetworkResponse(200, UpstreamChannels))
        val source = NetworkRaftWebSearchApi(network).asSearchCoreGroupsSource()

        source.channels("s-1", "#zeta", cursor = null, limit = null, priority = DataRequestPriority.Visible)
        source.channels("s-1", "#zeta", cursor = "channels.4.tag", limit = WEB_SEARCH_MAX_LIMIT, priority = DataRequestPriority.Visible)
        RecordingNetworkClient(HomeNetworkResponse(200, UpstreamPeople)).let { peopleNetwork ->
            NetworkRaftWebSearchApi(peopleNetwork).asSearchCoreGroupsSource().people("s-1", "zeta", null, null, DataRequestPriority.Visible)
            assertEquals(listOf("q" to "zeta", "app" to "people"), peopleNetwork.requests.single().query)
        }

        assertEquals(
            listOf(
                Recorded(DataRequestPriority.Visible, "search", listOf("q" to "#zeta", "app" to "channels"), ServerId("s-1")),
                Recorded(DataRequestPriority.Visible, "search", listOf("q" to "#zeta", "app" to "channels", "cursor" to "channels.4.tag", "limit" to "50"), ServerId("s-1")),
            ),
            network.requests,
        )
    }

    @Test
    fun failuresCarryTheTraceIdAndAnOlderServerHasNoGroup() = runTest {
        suspend fun load(response: HomeNetworkResponse) =
            NetworkRaftWebSearchApi(RecordingNetworkClient(response)).asSearchCoreGroupsSource()
                .channels("s", "plan", null, null, DataRequestPriority.Visible)
        val headers = mapOf("X-Trace-Id" to listOf(TraceId))

        val timedOut = assertIs<WebSearchCoreGroupLoad.Failed>(load(HomeNetworkResponse(200, """{ "groups": [ { "app": "channels", "error": "timeout" } ] }""", responseHeaders = headers)))
        assertEquals(TraceId, timedOut.error.traceId)
        val limited = assertIs<WebSearchCoreGroupLoad.Failed>(load(HomeNetworkResponse(429, null, errorBodyText = """{ "error": "slow down", "code": "RATE_LIMITED" }""", responseHeaders = headers)))
        assertEquals("RATE_LIMITED", limited.error.serverCode)
        assertEquals(TraceId, limited.error.traceId)
        assertIs<WebSearchCoreGroupLoad.Failed>(load(HomeNetworkResponse(500, null)))
        assertIs<WebSearchCoreGroupLoad.Failed>(load(HomeNetworkResponse(200, "<html>")))
        // A server before core's groups: `app=channels` is no app there.
        assertEquals(WebSearchCoreGroupLoad.Unsupported, load(HomeNetworkResponse(404, null, errorBodyText = """{ "error": "no app", "code": "APP_NOT_FOUND" }""")))
    }

    private fun channels(body: String) = parseWebSearchCoreGroup(body, SearchCoreGroup.Channels, ::readChannelSearchHit)

    private fun people(body: String) = parseWebSearchCoreGroup(body, SearchCoreGroup.People, ::readPersonSearchHit)

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

    internal companion object {
        const val TraceId = "0af7651916cd43dd8448eb211c80319c"

        fun channelJson(id: String, type: String = "channel") =
            """{ "id": "$id", "name": "$id", "description": null, "type": "$type", "archived": false, "joined": true, "external": false, "guest": false, "canPost": true, "requiredAction": null }"""

        fun personJson(id: String, kind: String = "human") =
            """{ "kind": "$kind", "id": "$id", "name": "$id", "displayName": null, "avatarUrl": null, "role": "member", "guest": false, "dmChannelId": null, "canPost": false, "requiredAction": "create_dm" }"""

        val UpstreamChannels =
            """
            {
              "groups": [
                {
                  "app": "channels",
                  "items": [
                    { "id": "ch-alpha", "name": "zeta01-alpha", "description": null, "type": "channel", "archived": false, "joined": true, "external": false, "guest": false, "canPost": true, "requiredAction": null },
                    { "id": "ch-beta", "name": "zeta01-beta", "description": "Where zeta01 keeps notes", "type": "channel", "archived": false, "joined": false, "external": false, "guest": false, "canPost": false, "requiredAction": "join_channel" },
                    { "id": "ch-gamma", "name": "zeta01-gamma", "description": null, "type": "channel", "archived": true, "joined": true, "external": false, "guest": false, "canPost": false, "requiredAction": null },
                    { "id": "ch-joint", "name": "zeta01-joint", "description": null, "type": "joint", "archived": false, "joined": true, "external": true, "guest": true, "canPost": true, "requiredAction": null }
                  ],
                  "nextCursor": "channels.4.tag",
                  "total": 7
                }
              ]
            }
            """.trimIndent()

        val UpstreamPeople =
            """
            {
              "groups": [
                {
                  "app": "people",
                  "items": [
                    { "kind": "human", "id": "u-guest", "name": "gwen", "displayName": "ZETA01 Guest", "avatarUrl": null, "role": "guest", "guest": true, "dmChannelId": null, "canPost": false, "requiredAction": "create_dm" },
                    { "kind": "agent", "id": "a-bot", "name": "zeta01-bot", "displayName": null, "avatarUrl": "https://cdn.example/a-bot.png", "role": "member", "guest": false, "dmChannelId": "dm-1", "canPost": true, "requiredAction": null }
                  ],
                  "nextCursor": null,
                  "total": 2
                }
              ]
            }
            """.trimIndent()
    }
}
