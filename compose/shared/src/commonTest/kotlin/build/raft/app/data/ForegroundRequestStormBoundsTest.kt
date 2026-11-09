package build.raft.app.data

import build.raft.app.model.ActivityChanged
import build.raft.app.model.ChannelId
import build.raft.app.model.PerReceiverReadStateChanged
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.network.MultipartFile
import build.raft.app.network.RaftHttpExecutor
import build.raft.app.network.RaftHttpExecutorResponse
import build.raft.app.network.RaftRequestPriority
import build.raft.app.network.RaftWireTransferProgress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Feedback fb-52cc0499 (the foreground request storm): the bounds each piece of the fix keeps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundRequestStormBoundsTest {
    // --- Identical GETs started together share one request, whatever their priority. ---

    @Test
    fun identicalGetsInFlightShareOneRequestAcrossPriorities() = runTest {
        val executor = HeldExecutor()
        val client = client(executor)

        val visible = async { client.request(DataRequestPriority.Visible, "channels/inbox", query = listOf("limit" to "1")) }
        runCurrent()
        val foreground = async { client.request(DataRequestPriority.Foreground, "channels/inbox", query = listOf("limit" to "1")) }
        val background = async { client.request(DataRequestPriority.Background, "channels/inbox", query = listOf("limit" to "1")) }
        runCurrent()
        assertEquals(1, executor.urls.size, "one request on the wire: ${executor.urls}")

        executor.release.complete(Unit)
        assertEquals(listOf(200, 200, 200), listOf(visible.await(), foreground.await(), background.await()).map { it.code })
        assertEquals(1, executor.urls.size)
    }

    @Test
    fun differentQueriesServersAndWritesAreNeverShared() = runTest {
        val executor = HeldExecutor()
        val client = client(executor)

        val calls =
            listOf(
                async { client.request(DataRequestPriority.Visible, "channels/inbox", query = listOf("limit" to "1")) },
                async { client.request(DataRequestPriority.Visible, "channels/inbox", query = listOf("limit" to "50")) },
                async {
                    client.request(DataRequestPriority.Visible, "channels/inbox", query = listOf("limit" to "1"), serverIdOverride = ServerId("server-2"))
                },
                async { client.request(DataRequestPriority.Visible, "channels/c1/read", method = "POST", bodyText = "{}") },
                async { client.request(DataRequestPriority.Visible, "channels/c1/read", method = "POST", bodyText = "{}") },
            )
        runCurrent()
        assertEquals(5, executor.urls.size, executor.urls.toString())
        executor.release.complete(Unit)
        calls.forEach { it.await() }
    }

    @Test
    fun aGetSentLongerThanTheJoinWindowAgoIsNotJoined() = runTest {
        val executor = HeldExecutor()
        val client = client(executor)

        val first = async { client.request(DataRequestPriority.Visible, "channels") }
        runCurrent()
        advanceTimeBy(RaftInFlightGetJoinWindowMillis + 1L)
        // Asked after something changed: an answer to a request sent before that may predate it.
        val second = async { client.request(DataRequestPriority.Visible, "channels") }
        runCurrent()
        assertEquals(2, executor.urls.size)
        executor.release.complete(Unit)
        first.await()
        second.await()
    }

    @Test
    fun aCancelledSenderLeavesItsWaitersToSendTheRequestThemselves() = runTest {
        val executor = HeldExecutor()
        val client = client(executor)

        val sender = launch { client.request(DataRequestPriority.Visible, "channels") }
        runCurrent()
        val waiter = async { client.request(DataRequestPriority.Foreground, "channels") }
        runCurrent()
        assertEquals(1, executor.urls.size)

        sender.cancel()
        runCurrent()
        assertEquals(2, executor.urls.size, "the waiter sent it again instead of inheriting the cancellation")
        executor.release.complete(Unit)
        assertEquals(200, waiter.await().code)
    }

    @Test
    fun aWriteBetweenTwoIdenticalGetsKeepsTheSecondFromJoiningTheFirst() = runTest {
        val executor = HeldExecutor()
        val client = client(executor)

        // Home's load reads the unread counts; the person's read is sent and acked; a reconcile reads again.
        val first = async { client.request(DataRequestPriority.Visible, "channels/unread", query = listOf("summary" to "1")) }
        runCurrent()
        val write = async { client.request(DataRequestPriority.Visible, "channels/c1/read", method = "POST", bodyText = "{}") }
        runCurrent()
        val second = async { client.request(DataRequestPriority.Visible, "channels/unread", query = listOf("summary" to "1")) }
        runCurrent()

        assertEquals(2, executor.urls.count { it.startsWith("GET") }, "the GET after the write is its own request: ${executor.urls}")
        executor.release.complete(Unit)
        first.await()
        write.await()
        second.await()
    }

    // --- The Activity unread summary (badge) reads at most once per realtime window. ---

    @Test
    fun theBadgeReadsAtMostOncePerRealtimeWindowWhileOnlyRealtimeAsks() = runTest {
        val reads = mutableListOf<Long>()
        val owner = ProcessActivityUnreadOwner("https://api.raft.build/api", "user-1", "server-1")
        val store = badge(owner) { reads += testScheduler.currentTime }

        // One realtime event every 500 ms for a minute (a busy agent channel).
        repeat(120) {
            store.invalidate(owner, reason = "realtime:MessageNew", eventMarker = Any(), realtimeOnly = true)
            advanceTimeBy(500L)
        }
        advanceTimeBy(20_000L)
        runCurrent()

        assertTrue(reads.size <= 7, "reads=$reads")
        reads.zipWithNext().forEach { (a, b) ->
            assertTrue(b - a >= ActivityUnreadRealtimeMinIntervalMillis, "reads=$reads")
        }
        assertTrue(reads.last() >= 59_000L, "the trailing read still comes after the last event: $reads")
    }

    @Test
    fun thePersonsOwnReadIsNotHeldBehindTheRealtimeWindow() = runTest {
        val reads = mutableListOf<Long>()
        val owner = ProcessActivityUnreadOwner("https://api.raft.build/api", "user-1", "server-1")
        val store = badge(owner) { reads += testScheduler.currentTime }

        store.invalidate(owner, reason = "realtime:MessageNew", eventMarker = Any(), realtimeOnly = true)
        runCurrent()
        advanceTimeBy(1_000L)
        store.invalidate(owner, reason = "realtime:MessageNew", eventMarker = Any(), realtimeOnly = true)
        advanceTimeBy(500L)
        store.invalidate(owner, reason = "read_result:7")
        advanceTimeBy(10_000L)
        runCurrent()

        assertEquals(2, reads.size, "reads=$reads")
        assertTrue(reads[1] <= ActivityRealtimeBackgroundRefreshIntervalMillis * 2, "the read came within the short window: $reads")
    }

    @Test
    fun anActivityActionIsReadInTheShortWindowNotTheRealtimeOne() = runTest {
        val reads = mutableListOf<Long>()
        val owner = ProcessActivityUnreadOwner("https://api.raft.build/api", "user-1", "server-1")
        val store = badge(owner) { reads += testScheduler.currentTime }

        store.invalidate(owner, reason = "realtime:MessageNew", eventMarker = Any(), realtimeOnly = true)
        runCurrent()
        advanceTimeBy(500L)
        // Done in Activity (SharedActivityStore.emitSummarySignalIfChanged, filter Unread).
        store.submitInvalidation(ActivityUnreadSummaryInvalidation(accountId = "user-1", serverId = "server-1", source = "activity:mark_done"))
        advanceTimeBy(10_000L)
        runCurrent()

        assertEquals(2, reads.size, "reads=$reads")
        assertTrue(reads[1] <= ActivityRealtimeBackgroundRefreshIntervalMillis, "Done read within 2.5 s: $reads")
    }

    @Test
    fun onlyAmbientActivityIsRealtimeOnly() {
        listOf("activity:mark_done", "activity:mark_all_read", "activity:mark_unread", "activity:mark_read",
            "activity:follow_thread", "activity:unfollow_thread", "activity:undo_object_done", "activity:remote_done",
            "activity:read_failure").forEach { source ->
            assertFalse(source.isRealtimeOnlyActivitySummarySource(), source)
        }
        assertTrue("activity:realtime".isRealtimeOnlyActivitySummarySource())
        assertTrue("activity:realtime_summary".isRealtimeOnlyActivitySummarySource())
        // A reconnect catch-up and a read on another device are read promptly.
        assertFalse(RealtimeRefreshRequired(scope = RealtimeRefreshScope.Home, reason = "reconnect").isAmbientActivityUnreadEvent())
        assertFalse(PerReceiverReadStateChanged(serverId = ServerId("server-1"), scopes = emptyList()).isAmbientActivityUnreadEvent())
        assertTrue(ActivityChanged(ServerId("server-1"), "object").isAmbientActivityUnreadEvent())
    }

    // --- The Activity list re-reads at most every 30 s while hidden; the entry Refresh is the catch-up. ---

    @Test
    fun hiddenActivityReReadsAtMostOncePerHiddenWindowAndTheEntryRefreshAnswersTheRest() = runTest {
        val loads = mutableListOf<Long>()
        val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(activityItem("channel-1", 1)))))
        val store =
            activityStore(
                scope = backgroundScope,
                inboxRepository =
                    object : InboxRepository by inbox {
                        override suspend fun loadInitial(serverId: ServerId, filter: InboxFilter, limit: Int): DataResult<InboxSnapshot> {
                            loads += testScheduler.currentTime
                            delay(1_000L)
                            return inbox.loadInitial(serverId, filter, limit)
                        }
                    },
                realtimeBackgroundRefreshIntervalMillis = ActivityRealtimeBackgroundRefreshIntervalMillis,
                hiddenRealtimeBackgroundRefreshIntervalMillis = ActivityHiddenRealtimeBackgroundRefreshIntervalMillis,
                nowMillis = { testScheduler.currentTime },
            )
        val changed = SharedActivityIntent.ApplyRealtimeEvent(ActivityChanged(ServerId("server-1"), "message_new"))
        store.dispatch(SharedActivityIntent.LoadInitial)
        advanceTimeBy(5_000L)
        store.dispatch(SharedActivityIntent.SurfaceHidden)
        loads.clear()

        // 70 s behind a pushed conversation, one realtime event a second.
        repeat(70) {
            store.dispatch(changed)
            advanceTimeBy(1_000L)
        }
        runCurrent()
        assertTrue(loads.size in 1..3, "hidden: at most one re-read per 30 s window, loads=$loads")
        loads.zipWithNext().forEach { (a, b) ->
            assertTrue(b - a >= ActivityHiddenRealtimeBackgroundRefreshIntervalMillis, "loads=$loads")
        }

        // Back to Activity: the entry Refresh reads once, and the asks made while hidden fold into it.
        val hiddenLoads = loads.size
        store.dispatch(SharedActivityIntent.Refresh)
        advanceTimeBy(40_000L)
        runCurrent()
        assertEquals(hiddenLoads + 1, loads.size, "only the entry read, no trailing re-read: loads=$loads")

        // Visible again: the 2.5 s window applies as before.
        repeat(10) {
            store.dispatch(changed)
            advanceTimeBy(1_000L)
        }
        advanceTimeBy(10_000L)
        runCurrent()
        assertTrue(loads.size - hiddenLoads - 1 in 3..5, "visible window unchanged: loads=$loads")
    }

    // --- Home: Activity's unread-count asks never reload a hidden Home; a visible one folds a burst. ---

    @Test
    fun hiddenHomeFoldsActivityUnreadAsksIntoOneReadWhenItShows() = runTest {
        val reloads = mutableListOf<Long>()
        val home = homeStore { reloads += testScheduler.currentTime }
        home.dispatch(SharedHomeIntent.Start)
        advanceTimeBy(5_000L)
        home.dispatch(SharedHomeIntent.SetSurfaceVisible(false))
        runCurrent()
        reloads.clear()

        repeat(20) {
            home.requestUnreadCountsReconcile(reason = "activity_unread_counts")
            advanceTimeBy(15_000L)
        }
        runCurrent()
        assertEquals(emptyList(), reloads, "no Home bootstrap while Home is hidden")
        assertTrue(home.state.value.homeRealtimeDirty)

        home.dispatch(SharedHomeIntent.SetSurfaceVisible(true))
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(1, reloads.size, "one read when Home shows again")
    }

    @Test
    fun hiddenHomeKeepsTheLauncherBadgeCountsFreshWithTheSummaryAloneAtMostEveryTenSeconds() = runTest {
        val reloads = mutableListOf<Long>()
        val api = DelayedHomeApi { reloads += testScheduler.currentTime }
        val home = homeStore(api) { }
        home.dispatch(SharedHomeIntent.Start)
        advanceTimeBy(5_000L)
        home.dispatch(SharedHomeIntent.SetSurfaceVisible(false))
        runCurrent()
        reloads.clear()
        api.summaryReads = 0
        api.summaryBody = """[{"serverId":"server-1","unreadCount":1,"activityUnreadCount":1},""" +
            """{"serverId":"server-2","unreadCount":7,"activityUnreadCount":7}]"""

        // 20 s of Activity asks, one a second, while Home is hidden.
        repeat(20) {
            home.requestUnreadCountsReconcile(reason = "activity_unread_counts")
            advanceTimeBy(1_000L)
        }
        advanceTimeBy(30_000L)
        runCurrent()

        assertEquals(emptyList(), reloads, "no whole Home bootstrap while hidden")
        assertTrue(api.summaryReads in 2..3, "servers/unread-summary at most once per 10 s: ${api.summaryReads}")
        val owner = ReadReceiptOwner("https://api.raft.build/api", "user-1", "server-1")
        assertEquals(7L, home.state.value.serverActivityUnread.countsForAccountOf(owner)["server-2"], "badge counts refreshed")

        // Shown again: one full reload, and the summary reads stop.
        val summaryReadsBeforeShow = api.summaryReads
        home.dispatch(SharedHomeIntent.SetSurfaceVisible(true))
        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals(1, reloads.size)
        assertEquals(summaryReadsBeforeShow + 1, api.summaryReads, "only the full reload's own summary read")
    }

    @Test
    fun homesOwnReadStateReconcileStaysImmediateWhileHidden() = runTest {
        val reloads = mutableListOf<Long>()
        val home = homeStore { reloads += testScheduler.currentTime }
        home.dispatch(SharedHomeIntent.Start)
        advanceTimeBy(5_000L)
        home.dispatch(SharedHomeIntent.SetSurfaceVisible(false))
        runCurrent()
        reloads.clear()

        // Home's own path (other servers' summary / app badge depend on it): unchanged by the fold.
        home.dispatch(
            SharedHomeIntent.ApplyPerReceiverReadState(
                PerReceiverReadStateUpdate(
                    serverId = ServerId("server-1"),
                    memberId = MemberId("user-1"),
                    channelId = ChannelId("channel-1"),
                    version = 1L,
                    maxReadSeq = 5L
                )
            )
        )
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(1, reloads.size, "Home's own reconcile reloads at once: $reloads")
    }

    @Test
    fun theActivityAskRunsOnTheStoreScopeInOrderWithTheVisibilityChange() = runTest {
        val reloads = mutableListOf<Long>()
        val home = homeStore { reloads += testScheduler.currentTime }
        home.dispatch(SharedHomeIntent.Start)
        advanceTimeBy(5_000L)
        home.dispatch(SharedHomeIntent.SetSurfaceVisible(false))
        runCurrent()
        reloads.clear()

        // Asked from another thread (onProjectionWithEvidence runs on threadSendScope): nothing changes
        // until the store scope runs it, and then in order with the show that follows.
        home.requestUnreadCountsReconcile(reason = "activity_unread_counts")
        assertFalse(home.state.value.homeRealtimeDirty, "no state written off the store scope")
        home.dispatch(SharedHomeIntent.SetSurfaceVisible(true))
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(1, reloads.size, "the ask became the dirty mark the show read: $reloads")
    }

    @Test
    fun visibleHomeFoldsAsksDuringAReconcileIntoOneTrailingReload() = runTest {
        val reloads = mutableListOf<Long>()
        val home = homeStore { reloads += testScheduler.currentTime }
        home.dispatch(SharedHomeIntent.Start)
        home.dispatch(SharedHomeIntent.SetSurfaceVisible(true))
        advanceTimeBy(5_000L)
        runCurrent()
        reloads.clear()

        home.requestUnreadCountsReconcile(reason = "activity_unread_counts")
        runCurrent()
        repeat(5) {
            advanceTimeBy(100L)
            home.requestUnreadCountsReconcile(reason = "activity_unread_counts")
        }
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(2, reloads.size, "the reconcile and ONE trailing reload after it: $reloads")
        assertTrue(reloads[1] >= reloads[0] + HomeNetworkMillis, "the trailing reload starts after the first answered: $reloads")
    }

    private fun TestScope.badge(owner: ProcessActivityUnreadOwner, onRead: () -> Unit): ProcessActivityUnreadStore =
        ProcessActivityUnreadStore(
            fetchSummary = { _, _ ->
                onRead()
                DataResult.Content(ActivityUnreadSummarySnapshot(totalUnreadCount = 1, totalCount = 2))
            },
            ownerResolver = { serverId -> owner.takeIf { it.serverId == serverId } },
            scope = backgroundScope,
            throttleMillis = ActivityRealtimeBackgroundRefreshIntervalMillis,
            realtimeMinIntervalMillis = ActivityUnreadRealtimeMinIntervalMillis,
            nowMillis = { testScheduler.currentTime },
            appForeground = MutableStateFlow(true),
        )

    private fun TestScope.homeStore(
        api: DelayedHomeApi? = null,
        onChannels: () -> Unit
    ): SharedHomeStore {
        val authStore = InMemoryRaftAuthSessionStore(sampleSession())
        return SharedHomeStore(
            authStore = authStore,
            readReceiptOwnerFactory = { serverId, accountId -> ReadReceiptOwner("https://api.raft.build/api", accountId, serverId) },
            dataSource =
                SharedHomeDataSource(
                    sessionStore = authStore,
                    api = api ?: DelayedHomeApi(onChannels),
                    parseHome = { payload, _ ->
                        DataResult.Content(
                            HomeSnapshot(
                                server = ServerSummary(payload.serverId, "Raft"),
                                currentMember =
                                    MemberProfile(MemberId("user-1"), "Artin", "artin", MemberKind.Human),
                                channels = emptyList(),
                                directMessages = emptyList(),
                                unreadTotal = 0,
                            )
                        )
                    },
                    parseDispatcher = Dispatchers.Unconfined,
                ),
            scope = backgroundScope,
            nowMillis = { testScheduler.currentTime },
        )
    }

    private class DelayedHomeApi(private val onChannels: () -> Unit) : RaftHomeApi {
        var summaryReads = 0
        var summaryBody = "[]"

        override suspend fun channels(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse {
            onChannels()
            delay(HomeNetworkMillis)
            return HomeNetworkResponse(code = 200, bodyText = "[]")
        }

        override suspend fun directMessages(serverId: ServerId, priority: DataRequestPriority) =
            HomeNetworkResponse(code = 200, bodyText = "[]")

        override suspend fun channelUnreadCounts(serverId: ServerId, priority: DataRequestPriority) =
            HomeNetworkResponse(code = 200, bodyText = EMPTY_CHANNEL_UNREAD_SUMMARY_BODY)

        override suspend fun serverUnreadSummary(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse {
            summaryReads += 1
            return HomeNetworkResponse(code = 200, bodyText = summaryBody)
        }

        override suspend fun channels(priority: DataRequestPriority): HomeNetworkResponse = error("unused")
        override suspend fun directMessages(priority: DataRequestPriority): HomeNetworkResponse = error("unused")
        override suspend fun channelUnreadCounts(priority: DataRequestPriority): HomeNetworkResponse = error("unused")
        override suspend fun serverUnreadSummary(priority: DataRequestPriority): HomeNetworkResponse = error("unused")
    }

    private fun TestScope.client(executor: RaftHttpExecutor): SharedRaftNetworkClient =
        SharedRaftNetworkClient(
            sessionStore =
                InMemoryRaftAuthSessionStore(
                    StoredAuthSession(
                        user = StoredAuthUser(id = "user-1", email = "user@raft.build"),
                        accessToken = "access-token",
                        refreshToken = "refresh-token",
                        selectedServerId = "server-1",
                        servers = listOf(StoredAuthServer(id = "server-1", name = "Raft"), StoredAuthServer(id = "server-2", name = "Work")),
                    )
                ),
            baseUrlProvider = { SharedApiEnvironmentStore.Production.apiBaseUrl },
            executor = executor,
            inFlightGets = RaftInFlightGetDedupe(nowMillis = { testScheduler.currentTime }),
        )

    /** Holds every request until [release]; records what reached the wire. */
    private class HeldExecutor : RaftHttpExecutor {
        val urls = mutableListOf<String>()
        val release = CompletableDeferred<Unit>()

        override suspend fun execute(
            priority: RaftRequestPriority,
            url: String,
            method: String,
            headers: Map<String, String>,
            bodyText: String?,
            jsonBody: Boolean
        ): RaftHttpExecutorResponse {
            urls += "$method $url ${headers["X-Server-Id"].orEmpty()}"
            release.await()
            return RaftHttpExecutorResponse(code = 200, bodyText = "{}", errorBodyText = null)
        }

        override suspend fun executeMultipart(
            priority: RaftRequestPriority,
            url: String,
            fields: Map<String, String>,
            files: List<MultipartFile>,
            headers: Map<String, String>,
            onUploadProgress: ((RaftWireTransferProgress) -> Unit)?
        ): RaftHttpExecutorResponse = error("unused")
    }

    private companion object {
        const val HomeNetworkMillis = 1_000L
    }
}
