package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `X-Raft-Fetch-Reason` on `GET /channels/inbox`: first-page reads send the caller's
 * `ListFetchReason` (botiverse/slock packages/web/src/store/listFetchReason.ts), and every
 * `offset > 0` page sends `page` (slock #16583 / #triage:#618).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InboxFetchReasonHeaderTest {

    @Test
    fun wireValuesAreWebsListFetchReasons() {
        assertEquals(
            listOf(
                "initial", "reconnect", "visibility", "sync_reset",
                "realtime_fallback", "manual", "legacy_no_fields", "sync_unpatchable",
                // Mobile-only until the server lists it (`mobile_inbox_inplace_v0` reconcile).
                "inplace_reconcile",
                // Pagination / continuation pages (`offset > 0`, slock #16583 / #triage:#618).
                "page",
                // `inbox_row_position_v1`: not a list read, the rows' own (`?ids=`, server ACTIVITY_ROW_FETCH_REASONS).
                "position_reread",
                // `inbox_row_position_v1`: the sampled audit read of one row (web `position_audit`).
                "position_audit"
            ),
            InboxFetchReason.entries.map { it.wireValue }
        )
    }

    @Test
    fun firstPageReadSendsTheReasonOfTheCaller() = runTest {
        val network = RecordingInboxClient()
        val repository =
            RealInboxRepository(
                InMemoryRaftAuthSessionStore(sampleSession()),
                network,
                parseDispatcher = UnconfinedTestDispatcher(testScheduler),
            )

        withInboxFetchReason(InboxFetchReason.Visibility) { repository.loadInitial(ServerId("server-1")) }

        assertEquals(listOf(mapOf("X-Raft-Fetch-Reason" to "visibility")), network.headers)
    }

    @Test
    fun readWithoutAReasonSendsNoHeaderAndOffsetPagesSendPage() = runTest {
        val network = RecordingInboxClient()
        val repository =
            RealInboxRepository(
                InMemoryRaftAuthSessionStore(sampleSession()),
                network,
                parseDispatcher = UnconfinedTestDispatcher(testScheduler),
            )

        repository.loadInitial(ServerId("server-1"))
        repository.loadMore(ServerId("server-1"), offset = 30)
        withInboxFetchReason(
            InboxFetchReason.RealtimeFallback,
            InboxRefreshTriggerCount(InboxRefreshTrigger.MessageNew, coalesced = 3)
        ) {
            repository.loadMore(ServerId("server-1"), offset = 100)
        }
        withInboxFetchReason(null) { repository.loadInitial(ServerId("server-1")) }

        assertEquals(
            listOf(
                emptyMap(),
                mapOf("X-Raft-Fetch-Reason" to "page"),
                mapOf("X-Raft-Fetch-Reason" to "page"),
                emptyMap(),
            ),
            network.headers
        )
    }

    @Test
    fun unreadSummaryReadSendsTheReasonOfItsSource() = runTest {
        val network = RecordingInboxClient()
        val repository =
            ProcessActivityUnreadRepository(
                InMemoryRaftAuthSessionStore(sampleSession()),
                network,
                parseDispatcher = UnconfinedTestDispatcher(testScheduler),
            )
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

    @Test
    fun followedThreadsRequestsSendCallerReasonAndOmitHeaderWhenUnlabelled() = runTest {
        val network = RecordingFollowedThreadsClient()
        val repository = RealConversationRepository(InMemoryRaftAuthSessionStore(sampleSession()), network)

        repository.getFollowedThreads(ServerId("server-1"))
        withInboxFetchReason(InboxFetchReason.Initial) {
            repository.getFollowedThreads(ServerId("server-1"))
        }
        withInboxFetchReason(InboxFetchReason.Visibility) {
            repository.getUnreadFollowedThreads(ServerId("server-1"))
        }
        withInboxFetchReason(
            InboxFetchReason.RealtimeFallback,
            InboxRefreshTriggerCount(InboxRefreshTrigger.ThreadReply, coalesced = 2)
        ) {
            repository.getFollowedThreads(ServerId("server-1"))
        }
        withInboxFetchReason(InboxFetchReason.Manual) {
            repository.getThreadReplySummaries(
                threadId = build.raft.app.model.ThreadId("channel-1"),
                parentMessageIds = listOf(build.raft.app.model.MessageId("msg-1"))
            )
        }

        assertEquals(
            listOf(
                emptyMap(),
                mapOf("X-Raft-Fetch-Reason" to "initial"),
                mapOf("X-Raft-Fetch-Reason" to "visibility"),
                mapOf(
                    "X-Raft-Fetch-Reason" to "realtime_fallback",
                    "X-Raft-Fetch-Trigger" to "thread_reply",
                    "X-Raft-Fetch-Coalesced" to "2"
                ),
                mapOf("X-Raft-Fetch-Reason" to "manual"),
            ),
            network.followedHeaders
        )
    }

    private class RecordingFollowedThreadsClient : RaftNetworkClient {
        val followedHeaders = mutableListOf<Map<String, String>>()

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
            if (path == "channels/threads/followed") {
                followedHeaders += headers
                return HomeNetworkResponse(200, """{"threads":[]}""")
            }
            return HomeNetworkResponse(200, """{"threads":[]}""")
        }
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
