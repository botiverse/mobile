package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `X-Raft-Fetch-Reason` on `GET /channels/inbox`: the value set is web's `ListFetchReason`
 * (botiverse/slock packages/web/src/store/listFetchReason.ts), sent on first-page reads only.
 */
class InboxFetchReasonHeaderTest {

    @Test
    fun wireValuesAreWebsListFetchReasons() {
        assertEquals(
            listOf(
                "initial", "reconnect", "visibility", "sync_reset",
                "realtime_fallback", "manual", "legacy_no_fields", "sync_unpatchable"
            ),
            InboxFetchReason.entries.map { it.wireValue }
        )
    }

    @Test
    fun firstPageReadSendsTheReasonOfTheCaller() = runTest {
        val network = RecordingInboxClient()
        val repository = RealInboxRepository(InMemoryRaftAuthSessionStore(sampleSession()), network)

        withInboxFetchReason(InboxFetchReason.Visibility) { repository.loadInitial(ServerId("server-1")) }

        assertEquals(listOf(mapOf("X-Raft-Fetch-Reason" to "visibility")), network.headers)
    }

    @Test
    fun readWithoutAReasonAndContinuationPagesSendNoHeader() = runTest {
        val network = RecordingInboxClient()
        val repository = RealInboxRepository(InMemoryRaftAuthSessionStore(sampleSession()), network)

        repository.loadInitial(ServerId("server-1"))
        withInboxFetchReason(InboxFetchReason.Manual) {
            repository.loadMore(ServerId("server-1"), offset = 30)
        }
        withInboxFetchReason(null) { repository.loadInitial(ServerId("server-1")) }

        assertEquals(List(3) { emptyMap<String, String>() }, network.headers)
    }

    @Test
    fun unreadSummaryReadSendsTheReasonOfItsSource() = runTest {
        val network = RecordingInboxClient()
        val repository = ProcessActivityUnreadRepository(InMemoryRaftAuthSessionStore(sampleSession()), network)
        val owner = ProcessActivityUnreadOwner(environmentId = "https://api.example.test", accountId = "user-1", serverId = "server-1")

        withInboxFetchReason(activityUnreadSummaryFetchReason("invalidate:realtime:MessageNew")) {
            repository.load(owner, DataRequestPriority.Foreground)
        }

        assertEquals(listOf(mapOf("X-Raft-Fetch-Reason" to "realtime_fallback")), network.headers)
        assertEquals(listOf(listOf("filter" to "all", "limit" to "1", "offset" to "0")), network.queries)
    }

    @Test
    fun unreadSummarySourcesMapToWebsValues() {
        mapOf(
            "ensure" to InboxFetchReason.Initial,
            "activity_route_attach" to InboxFetchReason.Initial,
            "thread_route_attach" to InboxFetchReason.Initial,
            "app_shell_server_ready" to InboxFetchReason.Initial,
            "app_shell_server_ready:owner_ready" to InboxFetchReason.Initial,
            "server_switch" to InboxFetchReason.Initial,
            "server_ready" to InboxFetchReason.Initial,
            "app_shell_resume" to InboxFetchReason.Visibility,
            "app_shell_resume:missing_epoch" to InboxFetchReason.Visibility,
            "manual" to InboxFetchReason.Manual,
            "manual_home_refresh" to InboxFetchReason.Manual,
            "invalidate:realtime:MessageNew" to InboxFetchReason.RealtimeFallback,
            "fact_reconcile" to InboxFetchReason.RealtimeFallback,
            "invalidate:activity:thread_updated" to InboxFetchReason.RealtimeFallback,
            "invalidate:read_prediction:3" to InboxFetchReason.Manual,
            "invalidate:read_result:3" to InboxFetchReason.Manual,
            "invalidate:read_failure:3" to InboxFetchReason.Manual,
            "invalidate:conversation_read_all" to InboxFetchReason.Manual,
            "sync_changes" to InboxFetchReason.SyncUnpatchable,
            "sync_reset" to InboxFetchReason.SyncReset,
        ).forEach { (source, expected) ->
            assertEquals(expected, activityUnreadSummaryFetchReason(source), source)
        }
        assertNull(activityUnreadSummaryFetchReason("something_new"))
    }

    private class RecordingInboxClient : RaftNetworkClient {
        val headers = mutableListOf<Map<String, String>>()
        val queries = mutableListOf<List<Pair<String, String>>>()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?
        ): HomeNetworkResponse = requestWithHeaders(priority, path, emptyMap(), method, query, serverIdOverride, bodyText)

        override suspend fun requestWithHeaders(
            priority: DataRequestPriority,
            path: String,
            headers: Map<String, String>,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?
        ): HomeNetworkResponse {
            check(path == "channels/inbox" && method == "GET") { "unexpected $method $path" }
            this.headers += headers
            queries += query
            return HomeNetworkResponse(
                200,
                """{"items":[],"hasMore":false,"totalCount":4,"totalUnreadCount":3,"activeUnreadCount":3}"""
            )
        }
    }
}
