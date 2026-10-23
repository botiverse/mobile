package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Search paging, as on the web (`MessageSearchPage` `loadMoreResults`): the next page continues
 * from the server's walk cursor when the last page gave one, else from the row offset; rows
 * already shown are not repeated.
 */
class SharedHomeSearchPagingTest {
    // Nav spec §9.7 / §10.2: the Search tab keeps its results and scroll. The tab refreshes when
    // the shell reappears (Back from a result, foreground); that refresh reloads every row shown,
    // not just the first page, so a reader scrolled past it keeps their place.
    @Test
    fun refreshSearchReloadsEveryLoadedPageWhileNewSearchStartsFromFirstPage() = runTest {
        val newSearches = listOf(null, SharedHomeSearchSavedIntent.SubmitSearch, SharedHomeSearchSavedIntent.SetSearchSort(SearchSort.Recent))
        for (newSearch in newSearches) {
            val search = PagedSearchRepository(total = 75)
            val store =
                SharedHomeSearchSavedStore(
                    authStore = InMemoryRaftAuthSessionStore(authSessionFixture()),
                    savedRepository = EmptySavedRepository,
                    searchRepository = search,
                    scope = backgroundScope,
                    searchDebounceMillis = 0L
                )
            store.dispatch(SharedHomeSearchSavedIntent.SearchQueryChanged("release"))
            runCurrent()
            store.dispatch(SharedHomeSearchSavedIntent.LoadMoreSearch)
            runCurrent()
            assertEquals(60, store.state.value.search.items.size)
            search.calls.clear()

            store.dispatch(newSearch ?: SharedHomeSearchSavedIntent.RefreshSearch)
            runCurrent()

            val after = store.state.value.search
            if (newSearch == null) {
                assertEquals(listOf(0 to 30, 30 to 30), search.calls)
                assertEquals((0 until 60).map { "message-$it" }, after.items.map { it.key })
                assertEquals(true to 60, after.hasMore to after.nextOffset)
            } else {
                assertEquals(listOf(0 to 30), search.calls, "new search $newSearch")
                assertEquals(30, after.items.size, "new search $newSearch")
            }
            assertEquals(false, after.refreshing)
        }
    }

    @Test
    fun searchDepthLoadKeepsLoadedRowsWhenALaterPageFails() = runTest {
        val search = PagedSearchRepository(total = 100, failAtOffset = 60)
        val result =
            loadSearchSnapshotToDepth(depth = 90, pageSize = 30) { page, limit ->
                search.search(ServerId("server-1"), "release", page.offset, limit, SearchQueryOptions(), page.cursor)
            }
        val snapshot = (result as DataResult.Content).data
        assertEquals(Triple(60, true, 60), Triple(snapshot.items.size, snapshot.hasMore, snapshot.nextOffset))
        val firstPageFailure =
            loadSearchSnapshotToDepth(depth = 90, pageSize = 30) { _, _ -> DataResult.Error(ScreenError(code = "search_network")) }
        assertEquals("search_network", (firstPageFailure as DataResult.Error).error.code)
    }

    @Test
    fun loadMoreContinuesFromTheServerCursorAndDropsRowsAlreadyShown() = runTest {
        val search = CursorSearchRepository()
        val store =
            SharedHomeSearchSavedStore(
                authStore = InMemoryRaftAuthSessionStore(authSessionFixture()),
                savedRepository = EmptySavedRepository,
                searchRepository = search,
                scope = backgroundScope,
                searchDebounceMillis = 0L
            )
        store.dispatch(SharedHomeSearchSavedIntent.SetSearchSort(SearchSort.Recent))
        store.dispatch(SharedHomeSearchSavedIntent.SearchQueryChanged("release"))
        runCurrent()
        assertEquals("cursor-1", store.state.value.search.nextCursor)

        store.dispatch(SharedHomeSearchSavedIntent.LoadMoreSearch)
        runCurrent()

        assertEquals(listOf(SearchPageStart(0, null), SearchPageStart(2, "cursor-1")), search.pages)
        val after = store.state.value.search
        assertEquals(listOf("message-0", "message-1", "message-2"), after.items.map { it.key })
        assertEquals(null to null, after.nextCursor to after.nextOffset)
        assertEquals(false, after.hasMore)
    }

    /** Serves `message-0 until total` by offset/limit and records each (offset, limit) call. */
    private class PagedSearchRepository(
        private val total: Int,
        private val failAtOffset: Int? = null,
    ) : SearchRepository {
        val calls = mutableListOf<Pair<Int, Int>>()

        override suspend fun search(
            serverId: ServerId,
            query: String,
            offset: Int,
            limit: Int,
            options: SearchQueryOptions,
            cursor: String?
        ): DataResult<SearchSnapshot> {
            calls += offset to limit
            if (offset == failAtOffset) return DataResult.Error(ScreenError(code = "search_network"))
            val end = minOf(offset + limit, total)
            return DataResult.Content(
                SearchSnapshot(
                    server = searchTestServer(serverId),
                    query = query,
                    items = (offset until end).map { searchHitRow("message-$it") },
                    hasMore = end < total,
                    nextOffset = end
                )
            )
        }
    }

    /**
     * Recent-sort walk: the first page ends with a cursor; the cursor page repeats the last row
     * already shown (`message-1`) and ends the walk.
     */
    private class CursorSearchRepository : SearchRepository {
        val pages = mutableListOf<SearchPageStart>()

        override suspend fun search(
            serverId: ServerId,
            query: String,
            offset: Int,
            limit: Int,
            options: SearchQueryOptions,
            cursor: String?
        ): DataResult<SearchSnapshot> {
            pages += SearchPageStart(offset, cursor)
            val snapshot =
                if (cursor == null) {
                    SearchSnapshot(
                        server = searchTestServer(serverId),
                        query = query,
                        items = listOf(searchHitRow("message-0"), searchHitRow("message-1")),
                        hasMore = true,
                        nextOffset = 2,
                        nextCursor = "cursor-1"
                    )
                } else {
                    SearchSnapshot(
                        server = searchTestServer(serverId),
                        query = query,
                        items = listOf(searchHitRow("message-1"), searchHitRow("message-2")),
                        hasMore = false
                    )
                }
            return DataResult.Content(snapshot)
        }
    }
}
