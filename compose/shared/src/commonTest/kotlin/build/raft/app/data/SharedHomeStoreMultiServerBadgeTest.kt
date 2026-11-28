package build.raft.app.data

import build.raft.app.model.ActivityDoneRowKind
import build.raft.app.model.ActivityDoneUpdated
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.NotificationPushReceived
import build.raft.app.model.NotificationRetracted
import build.raft.app.model.PerReceiverReadStateChanged
import build.raft.app.model.PerReceiverReadStateScope
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SharedHomeStoreMultiServerBadgeTest {
    private class ScriptedMultiServerHomeApi : RaftHomeApi {
        var channelsReads = 0
        var summaryReads = 0
        var summaryBody =
            """[{"serverId":"server-1","unreadCount":0,"activityUnreadCount":0},""" +
                """{"serverId":"server-2","unreadCount":4,"activityUnreadCount":4}]"""

        override suspend fun channels(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse {
            channelsReads += 1
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

    private val owner = ReadReceiptOwner("https://api.raft.build/api", "user-1", "server-1")

    private fun multiServerSession(serverIds: List<String> = listOf("server-1", "server-2")): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "user-1@raft.build", displayName = "Artin"),
            accessToken = "access",
            refreshToken = "refresh",
            selectedServerId = serverIds.first(),
            servers = serverIds.map { id -> StoredAuthServer(id = id, name = id) },
        )

    private fun TestScope.createStore(
        api: ScriptedMultiServerHomeApi,
        session: StoredAuthSession = multiServerSession(),
    ): SharedHomeStore {
        val authStore = InMemoryRaftAuthSessionStore(session)
        return SharedHomeStore(
            authStore = authStore,
            readReceiptOwnerFactory = { serverId, accountId ->
                ReadReceiptOwner("https://api.raft.build/api", accountId, serverId)
            },
            dataSource =
                SharedHomeDataSource(
                    sessionStore = authStore,
                    api = api,
                    parseHome = { payload, _ ->
                        DataResult.Content(
                            HomeSnapshot(
                                server = ServerSummary(payload.serverId, "Raft"),
                                currentMember = MemberProfile(MemberId("user-1"), "Artin", "artin", MemberKind.Human),
                                channels = emptyList(),
                                directMessages = emptyList(),
                                unreadTotal = 0,
                                serverUnreadCounts =
                                    payload.serverUnreadSummary.associate { it.serverId to it.unreadCount.coerceAtLeast(0) },
                                serverActivityUnreadCounts = payload.serverUnreadSummary.serverActivityUnreadCounts(),
                                serverPushMutedByServer = payload.serverUnreadSummary.serverPushMutedByServer(),
                            )
                        )
                    },
                    parseDispatcher = Dispatchers.Unconfined,
                ),
            scope = backgroundScope,
            nowMillis = { testScheduler.currentTime },
        )
    }

    @Test
    fun warmForegroundReturnWhileHomeHiddenRefreshesServerActivityUnreadForMultiServerAccount() = runTest {
        val api = ScriptedMultiServerHomeApi()
        val store = createStore(api)
        val appForeground = MutableStateFlow(true)
        store.followAppLifecycle(appForeground)

        store.accept(SharedHomeIntent.Start)
        runCurrent()
        assertEquals(4L, store.state.value.serverActivityUnread.countsForAccountOf(owner)["server-2"])

        // User moves to Activity tab or pushes a Thread (Home is hidden).
        store.accept(SharedHomeIntent.SetSurfaceVisible(false))
        runCurrent()
        val channelsBefore = api.channelsReads
        val summaryBefore = api.summaryReads

        // User reads server-2 on Web while mobile app is backgrounded, then returns to foreground
        // (both app lifecycle and shell ForegroundRefresh may fire within the same resume tick).
        api.summaryBody =
            """[{"serverId":"server-1","unreadCount":0,"activityUnreadCount":0},""" +
                """{"serverId":"server-2","unreadCount":0,"activityUnreadCount":0}]"""
        appForeground.value = false
        runCurrent()
        appForeground.value = true
        store.requestHiddenServerActivityUnreadRefreshIfMultiServer()
        runCurrent()
        advanceTimeBy(HOME_HIDDEN_SERVER_UNREAD_REFRESH_MIN_INTERVAL_MS + 100L)
        runCurrent()

        assertEquals(channelsBefore, api.channelsReads, "hidden warm return must not run a full Home bootstrap")
        assertEquals(summaryBefore + 1, api.summaryReads, "must refresh servers/unread-summary once without duplicate trailing fetch")
        assertEquals(0L, store.state.value.serverActivityUnread.countsForAccountOf(owner)["server-2"])
        assertEquals(0, store.state.value.serverUnreadCounts["server-2"])

        // Single-server accounts do not issue a hidden summary refresh on warm foreground return.
        val singleApi = ScriptedMultiServerHomeApi()
        val singleStore = createStore(singleApi, session = multiServerSession(listOf("server-1")))
        val singleFg = MutableStateFlow(true)
        singleStore.followAppLifecycle(singleFg)
        singleStore.accept(SharedHomeIntent.Start)
        singleStore.accept(SharedHomeIntent.SetSurfaceVisible(false))
        runCurrent()
        val singleSummaryBefore = singleApi.summaryReads

        singleFg.value = false
        runCurrent()
        singleFg.value = true
        runCurrent()
        assertEquals(singleSummaryBefore, singleApi.summaryReads, "single-server skips hidden summary refresh")
    }

    @Test
    fun backgroundServerRealtimeEventsCoalesceAndRefreshServerActivityUnread() = runTest {
        val api = ScriptedMultiServerHomeApi()
        val store = createStore(api)
        store.accept(SharedHomeIntent.Start)
        runCurrent()
        assertEquals(4L, store.state.value.serverActivityUnread.countsForAccountOf(owner)["server-2"])

        val summaryAfterStart = api.summaryReads
        val channelsAfterStart = api.channelsReads

        // Burst of non-selected server events: retract + push + message:new + activity_done:updated
        // (including cross-server user-room delivery arriving on server-1's socket).
        api.summaryBody =
            """[{"serverId":"server-1","unreadCount":0,"activityUnreadCount":0},""" +
                """{"serverId":"server-2","unreadCount":1,"activityUnreadCount":1}]"""
        store.accept(
            SharedHomeIntent.ApplyBackgroundServerUnreadEvent(
                serverId = "server-2",
                event =
                    NotificationRetracted(
                        serverId = ServerId("server-2"),
                        groupKey = "server-2:channel-b",
                        conversationId = "channel-b",
                        throughSeq = 50L,
                    ),
            )
        )
        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = "server-1",
                event =
                    NotificationPushReceived(
                        serverId = ServerId("server-2"),
                        kind = ConversationKind.Channel,
                        channelId = ChannelId("channel-b"),
                        messageId = MessageId("msg-99"),
                        title = "#general",
                        body = "ping",
                        tag = "msg-99",
                    ),
            )
        )
        store.accept(
            SharedHomeIntent.ApplyBackgroundServerUnreadEvent(
                serverId = "server-2",
                event =
                    MessageNew(
                        Message(
                            id = MessageId("msg-100"),
                            threadId = ThreadId("channel-b"),
                            channelId = ChannelId("channel-b"),
                            seq = 100L,
                            author = MemberProfile(MemberId("user-2"), "Cindy", "cindy", MemberKind.Human),
                            timestampLabel = "2026-11-28T12:00:00Z",
                            body = "hello",
                        )
                    ),
            )
        )
        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = "server-1",
                event =
                    ActivityDoneUpdated(
                        serverId = ServerId("server-2"),
                        rowKind = ActivityDoneRowKind.Channel,
                        rowId = "channel-c",
                        done = true,
                        position = "12",
                    ),
            )
        )

        // Coalesced inside the debounce window: zero immediate summary calls.
        runCurrent()
        assertEquals(summaryAfterStart, api.summaryReads)

        advanceTimeBy(HOME_FOLLOWED_THREAD_REFRESH_DELAY_MS + 1L)
        runCurrent()
        assertEquals(summaryAfterStart + 1, api.summaryReads, "burst coalesces into one servers/unread-summary refresh")
        assertEquals(channelsAfterStart, api.channelsReads, "must not reload selected server channels")
        assertEquals(1L, store.state.value.serverActivityUnread.countsForAccountOf(owner)["server-2"])

        // When Home is hidden, a non-selected server read_state:updated refreshes serverActivityUnread
        // via the lightweight summary endpoint instead of a full Home bootstrap.
        store.accept(SharedHomeIntent.SetSurfaceVisible(false))
        runCurrent()
        api.summaryBody =
            """[{"serverId":"server-1","unreadCount":0,"activityUnreadCount":0},""" +
                """{"serverId":"server-2","unreadCount":0,"activityUnreadCount":0}]"""
        store.accept(
            SharedHomeIntent.ApplyBackgroundServerUnreadEvent(
                serverId = "server-2",
                event =
                    PerReceiverReadStateChanged(
                        serverId = ServerId("server-2"),
                        scopes =
                            listOf(
                                PerReceiverReadStateScope(
                                    scopeId = ChannelId("channel-b"),
                                    maxReadSeq = 100L,
                                    readStateVersion = 2L,
                                )
                            ),
                    ),
            )
        )
        advanceTimeBy(HOME_FOLLOWED_THREAD_REFRESH_DELAY_MS + 1L)
        runCurrent()
        assertEquals(channelsAfterStart, api.channelsReads, "hidden Home must not run full bootstrap")
        assertEquals(summaryAfterStart + 2, api.summaryReads)
        assertEquals(0L, store.state.value.serverActivityUnread.countsForAccountOf(owner)["server-2"])
    }
}
