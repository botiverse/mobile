package ai.slock.compose.data

import ai.slock.compose.model.SyncResumeResponse
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
    fun primarySurfaceVisibilityPreloadsBadgeSummariesThroughShellStore() {
        val fixture = fixture()
        val store = fixture.store

        store.dispatch(SharedHomeShellIntent.SetSurfaceVisible(true))
        store.dispatch(SharedHomeShellIntent.SetSurfaceVisible(false))

        assertEquals(
            listOf<SharedHomeIntent>(
                SharedHomeIntent.SetSurfaceVisible(true),
                SharedHomeIntent.Start,
                SharedHomeIntent.SetSurfaceVisible(false)
            ),
            fixture.homeIntents
        )
        assertEquals(listOf<SharedActivityIntent>(SharedActivityIntent.LoadInitial), fixture.activityIntents)
        assertEquals(listOf<SharedHomeSearchSavedIntent>(SharedHomeSearchSavedIntent.OpenSaved), fixture.searchSavedIntents)
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
    fun foregroundRefreshTargetsVisibleSecondaryDestination() {
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
                SharedHomeSearchSavedIntent.OpenSaved,
                SharedHomeSearchSavedIntent.RefreshSaved,
                SharedHomeSearchSavedIntent.OpenSearch
            ),
            fixture.searchSavedIntents
        )
        assertEquals(
            listOf<SharedActivityIntent>(
                SharedActivityIntent.Refresh,
                SharedActivityIntent.Refresh
            ),
            fixture.activityIntents
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
                SharedActivityIntent.LoadInitial,
                SharedActivityIntent.ApplyRealtimeEvent(event),
                SharedActivityIntent.Refresh,
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
        val homeIntents = mutableListOf<SharedHomeIntent>()
        val activityIntents = mutableListOf<SharedActivityIntent>()
        val searchSavedIntents = mutableListOf<SharedHomeSearchSavedIntent>()
        val store =
            SharedHomeShellStore(
                homeState = MutableStateFlow(homeState),
                activityState = MutableStateFlow(activityState),
                searchSavedState = MutableStateFlow(searchSavedState),
                dispatchHome = homeIntents::add,
                dispatchActivity = activityIntents::add,
                dispatchSearchSaved = searchSavedIntents::add,
                resetActivity = {},
                scope = scope
            )
        return ShellFixture(store, scope, homeIntents, activityIntents, searchSavedIntents)
    }

    private data class ShellFixture(
        val store: SharedHomeShellStore,
        val scope: CoroutineScope,
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
