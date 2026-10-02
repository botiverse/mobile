package build.raft.app.data

import build.raft.app.model.ServerId
import build.raft.app.sharedHomeSearchErrorAction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

@OptIn(ExperimentalCoroutinesApi::class)
class SearchTooBroadRecoveryTest {
    @Test
    fun recoveryPreservesFiltersAndStartsARecentFirstPageThroughRealRepository() = runTest {
        val auth = InMemorySlockAuthSessionStore(canonicalV2TestSession())
        val network = RecoveryNetworkClient()
        val store = SharedHomeSearchSavedStore(
            authStore = auth,
            savedRepository = RealSavedRepository(auth, network),
            searchRepository = RealSearchRepository(auth, network),
            scope = this,
            timeRangeBoundsProvider = { SearchIsoBounds("2026-10-01T00:00:00Z", "2026-10-02T00:00:00Z") },
        )
        store.dispatch(SharedHomeSearchSavedIntent.SetSearchChannel("channel-1"))
        store.dispatch(SharedHomeSearchSavedIntent.ToggleSearchSender("user-1"))
        store.dispatch(SharedHomeSearchSavedIntent.ToggleSearchSender("agent-2"))
        store.dispatch(SharedHomeSearchSavedIntent.ToggleSearchScope(SearchScope.Mentioned))
        store.dispatch(SharedHomeSearchSavedIntent.ToggleSearchScope(SearchScope.Humans))
        store.dispatch(SharedHomeSearchSavedIntent.SetSearchTimeRange(SearchTimeRange.Today))
        store.dispatch(SharedHomeSearchSavedIntent.SearchQueryChanged(" release "))
        advanceUntilIdle()
        val before = store.state.value.search
        assertEquals("QUERY_TOO_BROAD", before.errorCode)
        assertEquals(1, network.calls.size)
        val action = assertNotNull(sharedHomeSearchErrorAction(before.errorCode.orEmpty(), before.sort))
        store.dispatch(action)
        advanceUntilIdle()
        val after = store.state.value.search
        assertEquals(SearchSort.Recent, after.sort)
        assertEquals(before.query, after.query)
        assertEquals(before.selectedChannelId, after.selectedChannelId)
        assertEquals(before.selectedSenderIds, after.selectedSenderIds)
        assertEquals(before.selectedScopes, after.selectedScopes)
        assertEquals(before.timeRange, after.timeRange)
        assertNull(after.errorCode)
        assertEquals(emptyList(), after.items)
        assertEquals(false, after.hasMore)
        assertNull(after.nextOffset)
        assertEquals(2, network.calls.size)
        val first = network.calls.first()
        val recent = network.calls.last()
        assertEquals("recent", recent.single { it.first == "sort" }.second)
        assertEquals(first, recent.filterNot { it.first == "sort" })
        assertEquals("0", recent.single { it.first == "offset" }.second)
        assertEquals("release", recent.single { it.first == "q" }.second)
        assertEquals(listOf("user-1", "agent-2"), recent.filter { it.first == "senderId" }.map { it.second })
        assertEquals("channel-1", recent.single { it.first == "channelId" }.second)
        assertEquals("self", recent.single { it.first == "mentionTarget" }.second)
        assertEquals("user", recent.single { it.first == "senderType" }.second)
        assertEquals("2026-10-01T00:00:00Z", recent.single { it.first == "after" }.second)
        assertEquals("2026-10-02T00:00:00Z", recent.single { it.first == "before" }.second)
        store.clear()
    }

    @Test
    fun recentRecoveryReplacesRetainedPagesInsteadOfAppending() = runTest {
        val auth = InMemorySlockAuthSessionStore(canonicalV2TestSession())
        val network = RecoveryNetworkClient(respond = { query ->
            when {
                query.any { it == ("sort" to "recent") } -> recoveryPage("newest", hasMore = false)
                query.any { it == ("offset" to "0") } -> recoveryPage("old", hasMore = true)
                else -> HomeNetworkResponse(422, null, """{"code":"QUERY_TOO_BROAD"}""")
            }
        })
        val store = SharedHomeSearchSavedStore(
            authStore = auth, savedRepository = RealSavedRepository(auth, network),
            searchRepository = RealSearchRepository(auth, network), scope = this,
        )
        store.dispatch(SharedHomeSearchSavedIntent.SearchQueryChanged("release"))
        store.state.first { it.search.items.isNotEmpty() }
        val oldItems = store.state.value.search.items
        store.dispatch(SharedHomeSearchSavedIntent.LoadMoreSearch)
        store.state.first { it.search.errorCode != null }
        assertEquals("1", network.calls.last().single { it.first == "offset" }.second)
        val failed = store.state.value.search
        store.dispatch(assertNotNull(sharedHomeSearchErrorAction(failed.errorCode.orEmpty(), failed.sort)))
        store.state.first { it.search.sort == SearchSort.Recent && it.search.items != oldItems }
        val result = store.state.value.search
        assertEquals(1, result.items.size)
        assertEquals(false, result.items.any { it in oldItems })
        assertEquals(false, result.hasMore)
        assertNull(result.errorCode)
        assertEquals("0", network.calls.last().single { it.first == "offset" }.second)
        store.clear()
    }

    @Test
    fun alreadyRecentHasNoActionAndOtherErrorsKeepOrdinaryRetry() {
        for (sort in SearchSort.entries) {
            for (code in listOf("http_422", "network_parse_failed", "SEARCH_TIMEOUT", "server_forbidden")) {
                assertEquals(SharedHomeSearchSavedIntent.RefreshSearch, sharedHomeSearchErrorAction(code, sort))
            }
        }
        assertNull(sharedHomeSearchErrorAction("QUERY_TOO_BROAD", SearchSort.Recent))
    }

    @Test
    fun onlyThePublicTyped422ActivatesRecovery() = runTest {
        val auth = InMemorySlockAuthSessionStore(canonicalV2TestSession())
        val vectors = listOf(
            Triple(422, """{"error":"too broad","code":"QUERY_TOO_BROAD"}""", "QUERY_TOO_BROAD"),
            Triple(422, """{"error":"QUERY_TOO_BROAD"}""", "http_422"),
            Triple(422, """{"errorCode":"QUERY_TOO_BROAD"}""", "http_422"),
            Triple(422, """{"code":"UNKNOWN"}""", "http_422"),
            Triple(422, "broken json", "http_422"),
            Triple(503, """{"code":"QUERY_TOO_BROAD"}""", "server_unavailable"),
            Triple(403, """{"code":"QUERY_TOO_BROAD"}""", "server_forbidden"),
        )
        for ((status, body, expected) in vectors) {
            val network = RecoveryNetworkClient(HomeNetworkResponse(status, null, body))
            val result = RealSearchRepository(auth, network).search(ServerId("server-1"), "release")
            val code = when (result) {
                is DataResult.Error -> result.error.code
                is DataResult.NoPermission -> result.blocker.code
                else -> error("Expected typed failure, got $result")
            }
            assertEquals(expected, code)
        }
    }
}

private class RecoveryNetworkClient(
    val response: HomeNetworkResponse? = null,
    val respond: ((List<Pair<String, String>>) -> HomeNetworkResponse)? = null,
) : SlockNetworkClient {
    val calls = mutableListOf<List<Pair<String, String>>>()
    override suspend fun request(
        priority: DataRequestPriority, path: String, method: String,
        query: List<Pair<String, String>>, serverIdOverride: ServerId?, bodyText: String?
    ): HomeNetworkResponse {
        assertEquals("messages/search", path)
        assertEquals(ServerId("server-1"), serverIdOverride)
        calls += query
        return respond?.invoke(query) ?: response ?: if (query.any { it == "sort" to "recent" }) {
            HomeNetworkResponse(200, "")
        } else {
            HomeNetworkResponse(422, null, """{"error":"Search query is too broad.","code":"QUERY_TOO_BROAD"}""")
        }
    }
}

private fun recoveryPage(id: String, hasMore: Boolean) = HomeNetworkResponse(
    200,
    """{"results":[{"id":"$id","seq":1,"channelId":"channel-1","channelName":"general","channelType":"channel","parentChannelId":"channel-1","parentChannelName":"general","parentChannelType":"channel","senderId":"user-1","senderType":"user","senderName":"Alice","content":"release","snippet":"release","createdAt":"2026-10-01T00:00:00Z"}],"hasMore":$hasMore}""",
)
