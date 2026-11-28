package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ChannelActivityNotificationPreference
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.PerReceiverReadStateChanged
import build.raft.app.model.PerReceiverReadStateScope
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.SyncResumeResponse
import build.raft.app.model.ThreadId
import build.raft.app.model.ThreadUpdated
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
                SharedHomeSearchSavedIntent.OpenSearch,
                SharedHomeSearchSavedIntent.RefreshSearch
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
        assertEquals(emptyList<Pair<String?, String>>(), fixture.activitySummaryRefreshes)
        fixture.cancel()
    }

    @Test
    fun homeServerChangeRefreshesHomeBadgeSummaries() {
        val fixture = fixture(homeState = homeStateForServer("server-1"))

        fixture.homeState.value = homeStateForServer("server-2")

        assertEquals(
            listOf<SharedActivityIntent>(SharedActivityIntent.ServerSelected("server-2")),
            fixture.activityIntents
        )
        assertEquals(listOf<Pair<String?, String>>("server-2" to "server_switch"), fixture.activitySummaryRefreshes)
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
    fun homeErrorWithSessionKeepsServerIdentityForRecoveryShell() {
        val fixture = fixture(homeState = errorHomeStateForServer("server-1"))

        fixture.homeState.value = homeStateForServer("server-2")

        assertEquals(
            listOf<SharedActivityIntent>(SharedActivityIntent.ServerSelected("server-2")),
            fixture.activityIntents
        )
        assertEquals(listOf<Pair<String?, String>>("server-2" to "server_switch"), fixture.activitySummaryRefreshes)
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
    fun readStateRealtimeAlwaysForwardsHiddenActivityProjection() {
        val fixture = fixture()
        val store = fixture.store
        val event =
            PerReceiverReadStateChanged(
                serverId = ServerId("server-1"),
                scopes =
                    listOf(
                        PerReceiverReadStateScope(
                            scopeId = ChannelId("channel-1"),
                            maxReadSeq = 42,
                            readStateVersion = 3
                        )
                    )
            )

        store.dispatch(SharedHomeShellIntent.ApplyRealtimeEvent("server-1", event))
        store.dispatch(SharedHomeShellIntent.OpenSaved)
        store.dispatch(SharedHomeShellIntent.ApplyRealtimeEvent("server-1", event))

        assertEquals(
            listOf<SharedHomeIntent>(
                SharedHomeIntent.ApplyRealtimeEvent("server-1", event),
                SharedHomeIntent.ApplyRealtimeEvent("server-1", event)
            ),
            fixture.homeIntents
        )
        assertEquals(
            listOf<SharedActivityIntent>(
                SharedActivityIntent.ApplyRealtimeSummaryEvent(event, activeConversationId = null),
                SharedActivityIntent.ApplyRealtimeSummaryEvent(event, activeConversationId = null)
            ),
            fixture.activityIntents
        )
        fixture.cancel()
    }

    @Test
    fun homeVisibleRealtimeSummaryReceivesActiveConversationId() {
        val fixture = fixture()
        val store = fixture.store
        val event = SyncResumeResponse(messages = emptyList(), currentSeq = 1L, hasMore = false)

        store.dispatch(SharedHomeShellIntent.SetSelectedConversation("channel-1"))
        store.dispatch(SharedHomeShellIntent.SetSurfaceVisible(true))
        store.dispatch(SharedHomeShellIntent.ApplyRealtimeEvent("server-1", event))

        assertEquals(
            listOf<SharedActivityIntent>(
                SharedActivityIntent.ApplyRealtimeSummaryEvent(event, activeConversationId = "channel-1")
            ),
            fixture.activityIntents
        )
        fixture.cancel()
    }

    @Test
    fun selectedConversationCountsInActivityWhileItIsNotSeen() {
        // Seen = read: a conversation left open in a hidden or idle window is not being read, so its
        // live messages count in Activity like the server counts them; once seen again they skip.
        var seen = false
        val fixture = fixture(selectedConversationSeen = { seen })
        val store = fixture.store
        val event = SyncResumeResponse(messages = emptyList(), currentSeq = 1L, hasMore = false)

        store.dispatch(SharedHomeShellIntent.SetSelectedConversation("channel-1"))
        store.dispatch(SharedHomeShellIntent.SetSurfaceVisible(true))
        store.dispatch(SharedHomeShellIntent.ApplyRealtimeEvent("server-1", event))
        seen = true
        store.dispatch(SharedHomeShellIntent.ApplyRealtimeEvent("server-1", event))

        assertEquals(
            listOf<SharedActivityIntent>(
                SharedActivityIntent.ApplyRealtimeSummaryEvent(event, activeConversationId = null),
                SharedActivityIntent.ApplyRealtimeSummaryEvent(event, activeConversationId = "channel-1")
            ),
            fixture.activityIntents
        )
        fixture.cancel()
    }

    @Test
    fun clearingSelectedConversationForwardsClearToHomeStore() {
        // task #299: leaving a conversation must clear the home store's
        // selectedConversationId too, otherwise realtime merges keep
        // suppressing unread for the last opened conversation.
        val fixture = fixture()
        val store = fixture.store

        store.dispatch(SharedHomeShellIntent.SetSelectedConversation("channel-1"))
        store.dispatch(SharedHomeShellIntent.SetSelectedConversation(null))

        assertNull(store.state.value.selectedConversationId)
        assertEquals(
            listOf<SharedHomeIntent>(SharedHomeIntent.ClearSelectedConversation),
            fixture.homeIntents
        )
        fixture.cancel()
    }

    @Test
    fun mutedConversationDisablesLocalActivityMessagePatchButStillForwardsEvent() {
        val fixture = fixture(homeState = homeStateForServer("server-1", activityMuted = true))
        val event =
            build.raft.app.model.MessageNew(
                Message(
                    id = MessageId("message-1"),
                    threadId = ThreadId("channel-1"),
                    channelId = ChannelId("channel-1"),
                    seq = 10,
                    author =
                        MemberProfile(
                            id = MemberId("user-2"),
                            displayName = "User Two",
                            handle = "user-2",
                            kind = MemberKind.Human
                        ),
                    timestampLabel = "2026-07-13T03:00:00Z",
                    body = "Muted ordinary activity"
                )
            )

        fixture.store.dispatch(SharedHomeShellIntent.OpenActivity)
        fixture.store.dispatch(SharedHomeShellIntent.ApplyRealtimeEvent("server-1", event))

        assertEquals(
            SharedActivityIntent.ApplyRealtimeEvent(
                event = event,
                allowLocalPatch = false,
                // `inbox_row_position_v1` reads it (the per-row rule judges a muted message in place).
                activityMuted = true
            ),
            fixture.activityIntents.last()
        )
        fixture.cancel()
    }

    @Test
    fun setSelectedTabActivityMarksVisibleAndRefreshesActivity() {
        val fixture = fixture()
        val store = fixture.store

        assertEquals(false, store.state.value.isActivityVisible)
        assertNull(store.state.value.selectedTab)

        store.dispatch(SharedHomeShellIntent.SetSelectedTab("activity"))

        assertEquals(true, store.state.value.isActivityVisible)
        assertEquals("activity", store.state.value.selectedTab)
        assertEquals(listOf<SharedActivityIntent>(SharedActivityIntent.Refresh), fixture.activityIntents)

        // Redundant set does not re-dispatch refresh
        store.dispatch(SharedHomeShellIntent.SetSelectedTab("activity"))
        assertEquals(listOf<SharedActivityIntent>(SharedActivityIntent.Refresh), fixture.activityIntents)

        // Switching away resets isActivityVisible
        store.dispatch(SharedHomeShellIntent.SetSelectedTab("dms"))
        assertEquals(false, store.state.value.isActivityVisible)
        assertEquals("dms", store.state.value.selectedTab)

        fixture.cancel()
    }

    @Test
    fun slackNavActivityTabReceivesRealtimeEvents() {
        val fixture = fixture(homeState = homeStateForServer("server-1", activityMuted = false))
        val event =
            build.raft.app.model.MessageNew(
                Message(
                    id = MessageId("message-1"),
                    threadId = ThreadId("channel-1"),
                    channelId = ChannelId("channel-1"),
                    seq = 10,
                    author =
                        MemberProfile(
                            id = MemberId("user-2"),
                            displayName = "User Two",
                            handle = "user-2",
                            kind = MemberKind.Human
                        ),
                    timestampLabel = "2026-07-13T03:00:00Z",
                    body = "New activity message"
                )
            )

        fixture.store.dispatch(SharedHomeShellIntent.SetSelectedTab("activity"))
        fixture.store.dispatch(SharedHomeShellIntent.ApplyRealtimeEvent("server-1", event))

        assertEquals(
            SharedActivityIntent.ApplyRealtimeEvent(
                event = event,
                allowLocalPatch = true
            ),
            fixture.activityIntents.last()
        )
        fixture.cancel()
    }

    @Test
    fun destinationPriorityOverSelectedTabOnForegroundRefresh() {
        val fixture = fixture()
        val store = fixture.store

        // User is on Slack Activity tab, then opens Saved overlay
        store.dispatch(SharedHomeShellIntent.SetSelectedTab("activity"))
        store.dispatch(SharedHomeShellIntent.OpenSaved)
        fixture.activityIntents.clear()
        fixture.searchSavedIntents.clear()
        fixture.homeIntents.clear()

        // Returning to foreground must refresh the visible overlay (Saved), not underlying Activity
        store.dispatch(SharedHomeShellIntent.ForegroundRefresh)

        assertEquals(
            listOf<SharedHomeSearchSavedIntent>(SharedHomeSearchSavedIntent.RefreshSaved),
            fixture.searchSavedIntents
        )
        assertEquals(emptyList<SharedActivityIntent>(), fixture.activityIntents)
        assertEquals(emptyList<SharedHomeIntent>(), fixture.homeIntents)

        // Same priority contract for Search overlay
        store.dispatch(SharedHomeShellIntent.OpenSearch)
        fixture.searchSavedIntents.clear()
        store.dispatch(SharedHomeShellIntent.ForegroundRefresh)
        assertEquals(
            listOf<SharedHomeSearchSavedIntent>(SharedHomeSearchSavedIntent.RefreshSearch),
            fixture.searchSavedIntents
        )
        assertEquals(emptyList<SharedActivityIntent>(), fixture.activityIntents)

        // Closing overlay restores Activity tab foreground refresh
        store.dispatch(SharedHomeShellIntent.CloseSecondaryDestination)
        fixture.searchSavedIntents.clear()
        store.dispatch(SharedHomeShellIntent.ForegroundRefresh)
        assertEquals(listOf<SharedActivityIntent>(SharedActivityIntent.Refresh), fixture.activityIntents)
        assertEquals(emptyList<SharedHomeSearchSavedIntent>(), fixture.searchSavedIntents)

        fixture.cancel()
    }

    @Test
    fun foregroundRefreshOnNonHomeDestinationsRefreshesHiddenServerActivityUnread() {
        val fixture = fixture()
        val store = fixture.store

        // Visible Home refreshes Home itself (not the hidden-Home summary hook).
        store.dispatch(SharedHomeShellIntent.ForegroundRefresh)
        assertEquals(0, fixture.hiddenServerActivityUnreadRefreshes)

        // Activity tab, Activity destination, Saved, and Search all trigger the hidden-Home
        // multi-server unread-summary refresh hook on foreground return.
        store.dispatch(SharedHomeShellIntent.SetSelectedTab("activity"))
        store.dispatch(SharedHomeShellIntent.ForegroundRefresh)
        assertEquals(1, fixture.hiddenServerActivityUnreadRefreshes)

        store.dispatch(SharedHomeShellIntent.OpenSaved)
        store.dispatch(SharedHomeShellIntent.ForegroundRefresh)
        assertEquals(2, fixture.hiddenServerActivityUnreadRefreshes)

        store.dispatch(SharedHomeShellIntent.OpenSearch)
        store.dispatch(SharedHomeShellIntent.ForegroundRefresh)
        assertEquals(3, fixture.hiddenServerActivityUnreadRefreshes)

        store.dispatch(SharedHomeShellIntent.OpenActivity)
        store.dispatch(SharedHomeShellIntent.ForegroundRefresh)
        assertEquals(4, fixture.hiddenServerActivityUnreadRefreshes)

        fixture.cancel()
    }

    private fun fixture(
        homeState: SharedHomeState = SharedHomeState(),
        activityState: SharedActivityState = SharedActivityState(),
        searchSavedState: SharedHomeSearchSavedState = SharedHomeSearchSavedState(),
        selectedConversationSeen: () -> Boolean = { true }
    ): ShellFixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val homeFlow = MutableStateFlow(homeState)
        val activityFlow = MutableStateFlow(activityState)
        val searchSavedFlow = MutableStateFlow(searchSavedState)
        val homeIntents = mutableListOf<SharedHomeIntent>()
        val activityIntents = mutableListOf<SharedActivityIntent>()
        val activitySummaryRefreshes = mutableListOf<Pair<String?, String>>()
        val searchSavedIntents = mutableListOf<SharedHomeSearchSavedIntent>()
        var hiddenRefreshes = 0
        val store =
            SharedHomeShellStore(
                homeState = homeFlow,
                activityState = activityFlow,
                searchSavedState = searchSavedFlow,
                dispatchHome = homeIntents::add,
                dispatchActivity = activityIntents::add,
                dispatchSearchSaved = searchSavedIntents::add,
                refreshActivitySummary = { serverId, reason ->
                    activitySummaryRefreshes += serverId to reason
                },
                resetActivity = {},
                scope = scope,
                selectedConversationSeen = selectedConversationSeen,
                refreshHiddenServerActivityUnread = { hiddenRefreshes += 1 },
            )
        return ShellFixture(
            store,
            scope,
            homeFlow,
            homeIntents,
            activityIntents,
            activitySummaryRefreshes,
            searchSavedIntents,
            hiddenServerActivityUnreadRefreshesProvider = { hiddenRefreshes },
        )
    }

    private fun errorHomeStateForServer(serverId: String): SharedHomeState =
        SharedHomeState(
            content = SharedHomeContentState.Error("home_network", sessionForServer(serverId))
        )

    private fun homeStateForServer(
        serverId: String,
        contentRefreshing: Boolean = false,
        activityMuted: Boolean? = null
    ): SharedHomeState {
        val server = ServerSummary(ServerId(serverId), "$serverId server")
        val member =
            MemberProfile(
                id = MemberId("user-1"),
                displayName = "Artin",
                handle = "artin",
                kind = MemberKind.Human
            )
        val session = sessionForServer(serverId, server.name)
        return SharedHomeState(
            content =
                SharedHomeContentState.Content(
                    snapshot =
                        HomeSnapshot(
                            server = server,
                            currentMember = member,
                            channels =
                                activityMuted?.let { muted ->
                                    listOf(
                                        HomeChannelRow(
                                            threadId = ThreadId("channel-1"),
                                            channelId = ChannelId("channel-1"),
                                            name = "general",
                                            activityNotificationPreference =
                                                ChannelActivityNotificationPreference(
                                                    activityMuted = muted,
                                                    muteFromSeq = if (muted) 1L else null,
                                                    activityMuteSupported = true
                                                )
                                        )
                                    )
                                }.orEmpty(),
                            directMessages = emptyList(),
                            unreadTotal = 0
                        ),
                    session = session,
                    contentRefreshing = contentRefreshing
                )
        )
    }

    private fun sessionForServer(
        serverId: String,
        serverName: String = "$serverId server"
    ): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "artin@raft.build"),
            accessToken = "token",
            refreshToken = "refresh",
            selectedServerId = serverId,
            servers = listOf(StoredAuthServer(id = serverId, name = serverName))
        )

    private data class ShellFixture(
        val store: SharedHomeShellStore,
        val scope: CoroutineScope,
        val homeState: MutableStateFlow<SharedHomeState>,
        val homeIntents: MutableList<SharedHomeIntent>,
        val activityIntents: MutableList<SharedActivityIntent>,
        val activitySummaryRefreshes: MutableList<Pair<String?, String>>,
        val searchSavedIntents: MutableList<SharedHomeSearchSavedIntent>,
        private val hiddenServerActivityUnreadRefreshesProvider: () -> Int,
    ) {
        val hiddenServerActivityUnreadRefreshes: Int
            get() = hiddenServerActivityUnreadRefreshesProvider()

        fun cancel() {
            store.clear()
            scope.cancel()
        }
    }
}
