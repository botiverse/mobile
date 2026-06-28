package ai.slock.compose.data

import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.ServerSummary
import ai.slock.compose.model.SyncResumeResponse
import ai.slock.compose.model.ThreadUpdated
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SharedHomeShellStoreTest {
    @Test
    fun openActivityMovesDestinationIntoShellStateAndRefreshesActivity() {
        val fixture = fixture()
        val store = fixture.store

        store.dispatch(SharedHomeShellIntent.SetSelectedConversation("thread-1"))
        store.dispatch(SharedHomeShellIntent.OpenActivity)

        assertEquals(SharedHomeShellDestinationActivity, store.state.value.destination)
        assertNull(store.state.value.selectedConversationId)
        assertEquals(listOf<SharedActivityIntent>(SharedActivityIntent.Refresh), fixture.activityIntents)
        fixture.cancel()
    }

    @Test
    fun primarySurfaceVisibilityOnlyForwardsVisibilityThroughShellStore() {
        val fixture = fixture()
        val store = fixture.store

        store.dispatch(SharedHomeShellIntent.SetSurfaceVisible(true))
        store.dispatch(SharedHomeShellIntent.SetSurfaceVisible(false))

        assertEquals(
            listOf<SharedHomeIntent>(
                SharedHomeIntent.SetSurfaceVisible(true),
                SharedHomeIntent.SetSurfaceVisible(false)
            ),
            fixture.homeIntents
        )
        assertEquals(emptyList<SharedActivityIntent>(), fixture.activityIntents)
        assertEquals(emptyList<SharedHomeSearchSavedIntent>(), fixture.searchSavedIntents)
        assertEquals(false, store.state.value.primarySurfaceVisible)
        fixture.cancel()
    }

    @Test
    fun notificationsAreProjectedFromHomeStateThroughShellState() {
        val notification =
            SharedHomeNotification(
                id = "announcement-1",
                title = "Upgrade available",
                body = "Install the newest daemon."
            )
        val fixture = fixture(homeState = SharedHomeState(notifications = listOf(notification)))

        assertEquals(listOf(notification), fixture.store.state.value.notifications)
        fixture.cancel()
    }

    @Test
    fun dismissNotificationRoutesThroughHomeStore() {
        val fixture = fixture()

        fixture.store.dispatch(SharedHomeShellIntent.DismissNotification("announcement-1"))

        assertEquals(
            listOf<SharedHomeIntent>(SharedHomeIntent.DismissNotification("announcement-1")),
            fixture.homeIntents
        )
        fixture.cancel()
    }

    @Test
    fun foregroundRefreshTargetsVisibleDestinationAndHomeBadgeSummaries() {
        val fixture = fixture()
        val store = fixture.store

        store.dispatch(SharedHomeShellIntent.ForegroundRefresh)
        store.dispatch(SharedHomeShellIntent.OpenSaved)
        store.dispatch(SharedHomeShellIntent.ForegroundRefresh)
        store.dispatch(SharedHomeShellIntent.OpenActivity)
        store.dispatch(SharedHomeShellIntent.ForegroundRefresh)
        store.dispatch(SharedHomeShellIntent.OpenSearch)
        store.dispatch(SharedHomeShellIntent.ForegroundRefresh)

        assertEquals(listOf<SharedHomeIntent>(SharedHomeIntent.ForegroundRefresh), fixture.homeIntents)
        assertEquals(
            listOf<SharedHomeSearchSavedIntent>(
                SharedHomeSearchSavedIntent.RefreshSaved,
                SharedHomeSearchSavedIntent.OpenSaved,
                SharedHomeSearchSavedIntent.RefreshSaved,
                SharedHomeSearchSavedIntent.OpenSearch
            ),
            fixture.searchSavedIntents
        )
        assertEquals(
            listOf<SharedActivityIntent>(
                SharedActivityIntent.Refresh,
                SharedActivityIntent.Refresh,
                SharedActivityIntent.Refresh
            ),
            fixture.activityIntents
        )
        fixture.cancel()
    }

    @Test
    fun explicitHomeRefreshAlsoRefreshesHomeBadgeSummaries() {
        val fixture = fixture()
        val store = fixture.store

        store.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.Refresh))

        assertEquals(listOf<SharedHomeIntent>(SharedHomeIntent.Refresh), fixture.homeIntents)
        assertEquals(listOf<SharedActivityIntent>(SharedActivityIntent.Refresh), fixture.activityIntents)
        assertEquals(
            listOf<SharedHomeSearchSavedIntent>(SharedHomeSearchSavedIntent.RefreshSaved),
            fixture.searchSavedIntents
        )
        fixture.cancel()
    }

    @Test
    fun homeServerChangeRefreshesHomeBadgeSummaries() {
        val fixture = fixture(homeState = homeStateForServer("server-1"))

        fixture.homeState.value = homeStateForServer("server-2")

        assertEquals(
            listOf<SharedActivityIntent>(
                SharedActivityIntent.ClearSummary,
                SharedActivityIntent.Refresh
            ),
            fixture.activityIntents
        )
        assertEquals(
            listOf<SharedHomeSearchSavedIntent>(
                SharedHomeSearchSavedIntent.ClearSavedSummary,
                SharedHomeSearchSavedIntent.RefreshSaved
            ),
            fixture.searchSavedIntents
        )
        fixture.cancel()
    }

    @Test
    fun homeFirstServerLoadRefreshesHomeBadgeSummariesWithoutClearing() {
        val fixture = fixture()

        fixture.homeState.value = homeStateForServer("server-1")

        assertEquals(listOf<SharedActivityIntent>(SharedActivityIntent.Refresh), fixture.activityIntents)
        assertEquals(
            listOf<SharedHomeSearchSavedIntent>(SharedHomeSearchSavedIntent.RefreshSaved),
            fixture.searchSavedIntents
        )
        fixture.cancel()
    }

    @Test
    fun realtimeForwardsActivityOnlyWhenPrimaryOrActivityVisible() {
        val fixture = fixture()
        val store = fixture.store
        val event = SyncResumeResponse(messages = emptyList(), currentSeq = 1L, hasMore = false)

        store.dispatch(SharedHomeShellIntent.ApplyRealtimeEvent("server-1", event))
        store.dispatch(SharedHomeShellIntent.SetSurfaceVisible(true))
        store.dispatch(SharedHomeShellIntent.ApplyRealtimeEvent("server-1", event))
        store.dispatch(SharedHomeShellIntent.SetSurfaceVisible(false))
        store.dispatch(SharedHomeShellIntent.OpenSaved)
        store.dispatch(SharedHomeShellIntent.ApplyRealtimeEvent("server-1", event))
        store.dispatch(SharedHomeShellIntent.OpenActivity)
        store.dispatch(SharedHomeShellIntent.ApplyRealtimeEvent("server-1", event))

        assertEquals(
            listOf<SharedHomeIntent>(
                SharedHomeIntent.ApplyRealtimeEvent("server-1", event),
                SharedHomeIntent.SetSurfaceVisible(true),
                SharedHomeIntent.ApplyRealtimeEvent("server-1", event),
                SharedHomeIntent.SetSurfaceVisible(false),
                SharedHomeIntent.ApplyRealtimeEvent("server-1", event),
                SharedHomeIntent.ApplyRealtimeEvent("server-1", event)
            ),
            fixture.homeIntents
        )
        assertEquals(
            listOf<SharedActivityIntent>(
                SharedActivityIntent.ApplyRealtimeEvent(event),
                SharedActivityIntent.Refresh,
                SharedActivityIntent.ApplyRealtimeEvent(event)
            ),
            fixture.activityIntents
        )
        fixture.cancel()
    }

    @Test
    fun threadUpdatedRealtimeStillRefreshesActivityWhenHomeIsVisible() {
        val fixture = fixture()
        val store = fixture.store
        val event =
            ThreadUpdated(
                parentMessageId = MessageId("parent-1"),
                threadChannelId = ChannelId("thread-parent-1"),
                replyCount = 3,
                lastReplyAt = "12:00",
                participantIds = emptyList(),
                unreadCount = 1
            )

        store.dispatch(SharedHomeShellIntent.SetSurfaceVisible(true))
        store.dispatch(SharedHomeShellIntent.ApplyRealtimeEvent("server-1", event))

        assertEquals(
            listOf<SharedHomeIntent>(
                SharedHomeIntent.SetSurfaceVisible(true),
                SharedHomeIntent.ApplyRealtimeEvent("server-1", event)
            ),
            fixture.homeIntents
        )
        assertEquals(
            listOf<SharedActivityIntent>(
                SharedActivityIntent.ApplyRealtimeEvent(event)
            ),
            fixture.activityIntents
        )
        fixture.cancel()
    }

    private fun fixture(
        homeState: SharedHomeState = SharedHomeState(),
        activityState: SharedActivityState = SharedActivityState(),
        searchSavedState: SharedHomeSearchSavedState = SharedHomeSearchSavedState()
    ): ShellFixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val homeFlow = MutableStateFlow(homeState)
        val activityFlow = MutableStateFlow(activityState)
        val searchSavedFlow = MutableStateFlow(searchSavedState)
        val homeIntents = mutableListOf<SharedHomeIntent>()
        val activityIntents = mutableListOf<SharedActivityIntent>()
        val searchSavedIntents = mutableListOf<SharedHomeSearchSavedIntent>()
        val store =
            SharedHomeShellStore(
                homeState = homeFlow,
                activityState = activityFlow,
                searchSavedState = searchSavedFlow,
                dispatchHome = homeIntents::add,
                dispatchActivity = activityIntents::add,
                dispatchSearchSaved = searchSavedIntents::add,
                resetActivity = {},
                scope = scope
            )
        return ShellFixture(store, scope, homeFlow, homeIntents, activityIntents, searchSavedIntents)
    }

    private fun homeStateForServer(serverId: String): SharedHomeState {
        val server = ServerSummary(ServerId(serverId), "$serverId server")
        val member =
            MemberProfile(
                id = MemberId("user-1"),
                displayName = "Artin",
                handle = "artin",
                kind = MemberKind.Human
            )
        val session =
            StoredAuthSession(
                user = StoredAuthUser(id = "user-1", email = "artin@raft.build"),
                accessToken = "token",
                refreshToken = "refresh",
                selectedServerId = serverId,
                servers = listOf(StoredAuthServer(id = serverId, name = server.name))
            )
        return SharedHomeState(
            content =
                SharedHomeContentState.Content(
                    snapshot =
                        HomeSnapshot(
                            server = server,
                            currentMember = member,
                            channels = emptyList(),
                            directMessages = emptyList(),
                            unreadTotal = 0
                        ),
                    session = session
                )
        )
    }

    private data class ShellFixture(
        val store: SharedHomeShellStore,
        val scope: CoroutineScope,
        val homeState: MutableStateFlow<SharedHomeState>,
        val homeIntents: MutableList<SharedHomeIntent>,
        val activityIntents: MutableList<SharedActivityIntent>,
        val searchSavedIntents: MutableList<SharedHomeSearchSavedIntent>
    ) {
        fun cancel() {
            store.clear()
            scope.cancel()
        }
    }
}
