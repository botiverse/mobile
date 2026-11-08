package build.raft.app.data

import build.raft.app.model.ActivityChanged
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.PerReceiverReadStateChanged
import build.raft.app.model.PerReceiverReadStateScope
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Feedback fb-52cc0499 (artin, HUAWEI, 1.21.0): 3 191 requests in 28.5 minutes with the app open.
 * This replays the logged store inputs ([ForegroundRequestStormEvents]: realtime asks, the person's
 * reads, Activity / Home visibility) through the real [ProcessActivityUnreadStore],
 * [SharedActivityStore] and [SharedHomeStore] over a fake network on virtual time, twice:
 *
 * - "before": the 1.21 wiring (no realtime-only badge window, no hidden Activity window, and
 *   Activity's unread-count callback dispatching `SharedHomeIntent.ForegroundRefresh` directly);
 * - "after": the process host's wiring now.
 *
 * Every fake request takes ~1 s, like the log's median. The table goes to stdout.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundRequestStormReplayTest {
    private data class Wiring(
        val name: String,
        val badgeRealtimeMinIntervalMillis: Long?,
        val activityHiddenIntervalMillis: Long?,
        val homeReconcileHonorsVisibility: Boolean,
    )

    private val before = Wiring("before", null, null, homeReconcileHonorsVisibility = false)
    private val after =
        Wiring(
            "after",
            badgeRealtimeMinIntervalMillis = ActivityUnreadRealtimeMinIntervalMillis,
            activityHiddenIntervalMillis = ActivityHiddenRealtimeBackgroundRefreshIntervalMillis,
            homeReconcileHonorsVisibility = true,
        )

    private class Requests {
        val counts = linkedMapOf<String, Int>().apply { Endpoints.forEach { put(it, 0) } }

        fun hit(endpoint: String) {
            counts[endpoint] = (counts[endpoint] ?: 0) + 1
        }
    }

    @Test
    fun theLoggedStormReplaysWithFarFewerRequests() = runTest {
        val beforeCounts = replay(before)
        val afterCounts = replay(after)

        val table =
            buildString {
                appendLine("fb-52cc0499 replay (${ForegroundRequestStormEvents.size} inputs, 28.3 min)")
                appendLine("| endpoint | before | after |")
                appendLine("|---|---:|---:|")
                Endpoints.forEach { endpoint ->
                    appendLine("| $endpoint | ${beforeCounts[endpoint]} | ${afterCounts[endpoint]} |")
                }
                appendLine("| total | ${beforeCounts.values.sum()} | ${afterCounts.values.sum()} |")
            }
        println(table)

        // The replay reproduces the logged storm before the change (log: 75 Activity-driven Home
        // bootstraps, 453 badge reads, 279 Activity list reads).
        assertTrue(beforeCounts.getValue(HomeChannels) >= 75, table)
        assertTrue(beforeCounts.getValue(BadgeInbox) >= 400, table)
        assertTrue(beforeCounts.getValue(ActivityInbox) >= 200, table)
        // After: Home reloads only on its own start and when it shows again dirty (4 shows).
        assertTrue(afterCounts.getValue(HomeChannels) <= 6, table)
        assertEquals(afterCounts.getValue(HomeChannels), afterCounts.getValue(HomeUnread), table)
        // Badge: at most one realtime-only read per 10 s per server, plus the person's own reads.
        assertTrue(afterCounts.getValue(BadgeInbox) * 10 <= beforeCounts.getValue(BadgeInbox) * 7, table)
        // Activity list: hidden re-reads at most every 30 s; entries and visible re-reads stay.
        assertTrue(afterCounts.getValue(ActivityInbox) * 2 <= beforeCounts.getValue(ActivityInbox), table)
    }

    private suspend fun TestScope.replay(wiring: Wiring): Map<String, Int> {
        val requests = Requests()
        val replayJob = Job(backgroundScope.coroutineContext[Job])
        val scope = CoroutineScope(backgroundScope.coroutineContext + replayJob)
        val clock = { testScheduler.currentTime }

        val owners = mapOf("1" to owner("server-1"), "2" to owner("server-2"))
        val badge =
            ProcessActivityUnreadStore(
                fetchSummary = { _, _ ->
                    requests.hit(BadgeInbox)
                    delay(NetworkMillis)
                    DataResult.Content(ActivityUnreadSummarySnapshot(totalUnreadCount = 3, totalCount = 9))
                },
                ownerResolver = { serverId -> owners.values.firstOrNull { it.serverId == serverId } },
                scope = scope,
                throttleMillis = ActivityRealtimeBackgroundRefreshIntervalMillis,
                realtimeMinIntervalMillis = wiring.badgeRealtimeMinIntervalMillis,
                nowMillis = clock,
                appForeground = MutableStateFlow(true),
            )

        val authStore = InMemoryRaftAuthSessionStore(sampleSession())
        val home =
            SharedHomeStore(
                authStore = authStore,
                dataSource =
                    SharedHomeDataSource(
                        sessionStore = authStore,
                        api = CountingHomeApi(requests),
                        parseHome = { payload, _ -> DataResult.Content(homeSnapshot(payload.serverId)) },
                        parseDispatcher = Dispatchers.Unconfined,
                    ),
                scope = scope,
                readReceiptOwnerFactory = { serverId, accountId -> ReadReceiptOwner("https://api.raft.build/api", accountId, serverId) },
                loadSidebarOrder = {
                    requests.hit(HomeSidebarOrder)
                    delay(NetworkMillis)
                    DataResult.Empty(EmptyReason.NoSettingsAvailable)
                },
                nowMillis = clock,
            )

        val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(activityItem("channel-1", 2)))))
        val activity =
            activityStore(
                scope = scope,
                inboxRepository =
                    object : InboxRepository by inbox {
                        override suspend fun loadInitial(serverId: ServerId, filter: InboxFilter, limit: Int): DataResult<InboxSnapshot> {
                            requests.hit(ActivityInbox)
                            delay(NetworkMillis)
                            return inbox.loadInitial(serverId, filter, limit)
                        }
                    },
                authStore = authStore,
                realtimeBackgroundRefreshIntervalMillis = ActivityRealtimeBackgroundRefreshIntervalMillis,
                hiddenRealtimeBackgroundRefreshIntervalMillis = wiring.activityHiddenIntervalMillis,
                nowMillis = clock,
                onUnreadCountsChanged =
                    if (wiring.homeReconcileHonorsVisibility) {
                        { home.requestUnreadCountsReconcile(reason = "activity_unread_counts") }
                    } else {
                        { home.dispatch(SharedHomeIntent.ForegroundRefresh) }
                    },
            )

        // The log opens on the Activity tab with Home loaded and hidden; those first loads are not counted.
        home.dispatch(SharedHomeIntent.Start)
        activity.dispatch(SharedActivityIntent.LoadInitial)
        advanceTimeBy(5_000L)
        home.dispatch(SharedHomeIntent.SetSurfaceVisible(false))
        runCurrent()
        requests.counts.keys.toList().forEach { requests.counts[it] = 0 }

        val start = testScheduler.currentTime
        var readVersion = 0L
        ForegroundRequestStormEvents.forEach { (at, kind) ->
            val wait = start + at - testScheduler.currentTime
            if (wait > 0L) advanceTimeBy(wait)
            runCurrent()
            when (kind) {
                "R1", "R2" -> {
                    val serverKey = kind.substring(1)
                    badge.invalidate(owners.getValue(serverKey), reason = "realtime:MessageNew", eventMarker = Any(), realtimeOnly = true)
                    if (serverKey == "1") {
                        activity.dispatch(
                            SharedActivityIntent.ApplyRealtimeEvent(ActivityChanged(ServerId("server-1"), "message_new"))
                        )
                    }
                }
                "U1", "U2" -> badge.invalidate(owners.getValue(kind.substring(1)), reason = "read_result:${++readVersion}")
                "T1", "T2" -> badge.ensure(ServerId(owners.getValue(kind.substring(1)).serverId), reason = "thread_route_attach")
                "A" -> {
                    readVersion += 1
                    activity.dispatch(
                        SharedActivityIntent.ApplyRealtimeEvent(
                            PerReceiverReadStateChanged(
                                serverId = ServerId("server-1"),
                                scopes =
                                    listOf(
                                        PerReceiverReadStateScope(
                                            scopeId = ChannelId("thread-$readVersion"),
                                            maxReadSeq = readVersion,
                                            readStateVersion = readVersion,
                                        )
                                    ),
                            )
                        )
                    )
                }
                "AV" -> activity.dispatch(SharedActivityIntent.Refresh)
                "AH" -> activity.dispatch(SharedActivityIntent.SurfaceHidden)
                "HV" -> home.dispatch(SharedHomeIntent.SetSurfaceVisible(true))
                "HH" -> home.dispatch(SharedHomeIntent.SetSurfaceVisible(false))
                else -> error("unknown replay input $kind")
            }
        }
        advanceTimeBy(60_000L)
        runCurrent()
        scope.cancel()
        return requests.counts.toMap()
    }

    private class CountingHomeApi(private val requests: Requests) : RaftHomeApi {
        override suspend fun channels(serverId: ServerId, priority: DataRequestPriority) =
            answer(HomeChannels, "[]")

        override suspend fun directMessages(serverId: ServerId, priority: DataRequestPriority) =
            answer(HomeDirectMessages, "[]")

        override suspend fun channelUnreadCounts(serverId: ServerId, priority: DataRequestPriority) =
            answer(HomeUnread, EMPTY_CHANNEL_UNREAD_SUMMARY_BODY)

        override suspend fun serverUnreadSummary(serverId: ServerId, priority: DataRequestPriority) =
            answer(HomeUnreadSummary, "[]")

        private suspend fun answer(endpoint: String, body: String): HomeNetworkResponse {
            requests.hit(endpoint)
            delay(NetworkMillis)
            return HomeNetworkResponse(code = 200, bodyText = body)
        }

        override suspend fun channels(priority: DataRequestPriority): HomeNetworkResponse = error("unused")
        override suspend fun directMessages(priority: DataRequestPriority): HomeNetworkResponse = error("unused")
        override suspend fun channelUnreadCounts(priority: DataRequestPriority): HomeNetworkResponse = error("unused")
        override suspend fun serverUnreadSummary(priority: DataRequestPriority): HomeNetworkResponse = error("unused")
    }

    private fun owner(serverId: String) =
        ProcessActivityUnreadOwner(environmentId = "https://api.raft.build/api", accountId = "user-1", serverId = serverId)

    private fun homeSnapshot(serverId: ServerId) =
        HomeSnapshot(
            server = ServerSummary(serverId, "Raft"),
            currentMember =
                MemberProfile(id = MemberId("user-1"), displayName = "Artin", handle = "artin", kind = MemberKind.Human),
            channels = emptyList(),
            directMessages = emptyList(),
            unreadTotal = 0,
        )

    private companion object {
        const val NetworkMillis = 1_000L
        const val BadgeInbox = "GET channels/inbox?filter=all&limit=1 (Activity badge)"
        const val ActivityInbox = "GET channels/inbox (Activity list first page)"
        const val HomeChannels = "GET channels"
        const val HomeDirectMessages = "GET channels/dm"
        const val HomeUnread = "GET channels/unread"
        const val HomeUnreadSummary = "GET servers/unread-summary"
        const val HomeSidebarOrder = "GET servers/:id/sidebar-order"
        val Endpoints =
            listOf(BadgeInbox, ActivityInbox, HomeChannels, HomeDirectMessages, HomeUnread, HomeUnreadSummary, HomeSidebarOrder)
    }
}
