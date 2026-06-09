package ai.slock.compose.data

import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.ConversationSummary
import ai.slock.compose.model.Heartbeat
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberPresenceUpdated
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.Message
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.MessageNew
import ai.slock.compose.model.Presence
import ai.slock.compose.model.RealtimeRefreshRequired
import ai.slock.compose.model.RealtimeRefreshScope
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.ServerSummary
import ai.slock.compose.model.ThreadId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SharedHomeStoreTest {
    @Test
    fun startLoadsHomeThroughCommonStateFlow() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = source(api = RecordingHomeApi()),
            scope = scope
        )

        store.accept(SharedHomeIntent.Start)

        val content = store.state.value.content
        assertTrue(content is SharedHomeContentState.Content)
        assertEquals("server-1", content.snapshot.server.id.value)
        assertEquals(false, content.contentRefreshing)
        scope.cancel()
    }

    @Test
    fun selectServerPersistsSelectionAndClearsPendingAfterLoad() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sessionStore = InMemorySlockAuthSessionStore(sampleSession())
        val api = RecordingHomeApi()
        val store = SharedHomeStore(
            authStore = sessionStore,
            dataSource = source(api = api),
            scope = scope
        )

        store.accept(SharedHomeIntent.SelectConversation("channel-1"))
        store.accept(SharedHomeIntent.SelectServer("server-2"))

        val state = store.state.value
        val content = state.content
        assertTrue(content is SharedHomeContentState.Content)
        assertEquals("server-2", content.snapshot.server.id.value)
        assertEquals("server-2", sessionStore.readSession()?.selectedServerId)
        assertEquals(null, state.serverSwitchState.pendingServerId)
        assertEquals(false, state.serverSwitcherExpanded)
        assertEquals(null, state.selectedConversationId)
        assertEquals(null, state.selectedTopAction)
        assertEquals(listOf("channels:server-2", "dms:server-2", "unread:server-2", "summary:server-2"), api.calls)
        scope.cancel()
    }

    @Test
    fun selectServerLoadsUnreadSummaryForSelectedServerOnly() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sessionStore = InMemorySlockAuthSessionStore(sampleSession())
        val api = ServerUnreadHomeApi(
            unreadByServerId =
                mapOf(
                    "server-1" to 3,
                    "server-2" to 11
                )
        )
        val store = SharedHomeStore(
            authStore = sessionStore,
            dataSource =
                SharedHomeDataSource(
                    sessionStore = sessionStore,
                    api = api,
                    parseHome = ::parseSlockHome
                ),
            scope = scope
        )

        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.SelectServer("server-2"))

        val content = store.state.value.content
        assertTrue(content is SharedHomeContentState.Content)
        assertEquals("server-2", content.snapshot.server.id.value)
        assertEquals(11, content.snapshot.unreadTotal)
        assertEquals(11, content.snapshot.server.unreadCount)
        assertEquals(
            listOf(
                "channels:server-1",
                "dms:server-1",
                "unread:server-1",
                "summary:server-1",
                "channels:server-2",
                "dms:server-2",
                "unread:server-2",
                "summary:server-2"
            ),
            api.calls
        )
        scope.cancel()
    }

    @Test
    fun unauthenticatedSessionBecomesErrorState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val logs = mutableListOf<String>()
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(),
            dataSource = source(api = RecordingHomeApi()),
            scope = scope,
            diagnosticsLogger = logs::add
        )

        store.accept(SharedHomeIntent.Start)

        val content = store.state.value.content
        assertTrue(content is SharedHomeContentState.Error)
        assertEquals("unauthenticated", content.code)
        assertContains(logs, "home_ui_error source=shared_home_load code=unauthenticated mapped=home_error_panel")
        scope.cancel()
    }

    @Test
    fun firstHomeNetworkFailureLogsErrorPanelMapping() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val logs = mutableListOf<String>()
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = source(api = ThrowingHomeApi()),
            scope = scope,
            diagnosticsLogger = logs::add
        )

        store.accept(SharedHomeIntent.Start)

        val content = store.state.value.content
        assertTrue(content is SharedHomeContentState.Error)
        assertEquals("home_network", content.code)
        assertContains(logs, "home_ui_error source=shared_home_load code=home_network mapped=home_error_panel")
        scope.cancel()
    }

    @Test
    fun startUsesCachedHomeAndKeepsContentOnVisibleNetworkError() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeHome(sampleHome(ServerId("server-1")))
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource =
                SharedHomeDataSource(
                    sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                    api = ThrowingHomeApi(),
                    localWriteThrough = ComposeLocalWriteThrough(localStore),
                    parseHome = { _, _ -> error("network failure should not parse") }
                ),
            scope = scope
        )

        store.accept(SharedHomeIntent.Start)

        val content = store.state.value.content
        assertTrue(content is SharedHomeContentState.Content)
        assertEquals("general", content.snapshot.channels.single().name)
        assertEquals(false, content.contentRefreshing)
        scope.cancel()
    }

    @Test
    fun realtimeMessageUpdatesVisibleHomeFromLocalSnapshot() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeHome(sampleHome(ServerId("server-1")))
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource =
                SharedHomeDataSource(
                    sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                    api = ThrowingHomeApi(),
                    localWriteThrough = ComposeLocalWriteThrough(localStore),
                    parseHome = { _, _ -> error("network failure should not parse") }
                ),
            scope = scope
        )
        store.accept(SharedHomeIntent.Start)
        val otherMember = sampleMember().copy(
            id = MemberId("member-2"),
            displayName = "Cindy",
            handle = "cindy@slock.ai"
        )

        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = "server-1",
                event =
                    MessageNew(
                        Message(
                            id = MessageId("message-2"),
                            threadId = ThreadId("channel-1"),
                            channelId = ChannelId("channel-1"),
                            seq = 2,
                            author = otherMember,
                            timestampLabel = "12:01",
                            body = "live update"
                        )
                    )
            )
        )

        val content = store.state.value.content
        assertTrue(content is SharedHomeContentState.Content)
        assertEquals("live update", content.snapshot.channels.single().lastMessagePreview)
        assertEquals(1, content.snapshot.channels.single().unreadCount)
        assertEquals(1, content.snapshot.unreadTotal)
        assertEquals("live update", localStore.readHome(ServerId("server-1"))?.channels?.single()?.lastMessagePreview)
        scope.cancel()
    }

    @Test
    fun realtimeMessageUpdateOnlyReplacesMatchingHomeRow() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeHome(
            sampleHome(ServerId("server-1")).copy(
                channels =
                    listOf(
                        channel("channel-1", "general"),
                        channel("channel-2", "random")
                    )
            )
        )
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource =
                SharedHomeDataSource(
                    sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                    api = ThrowingHomeApi(),
                    localWriteThrough = ComposeLocalWriteThrough(localStore),
                    parseHome = { _, _ -> error("network failure should not parse") }
                ),
            scope = scope
        )
        store.accept(SharedHomeIntent.Start)
        val before = (store.state.value.content as SharedHomeContentState.Content).snapshot.channels

        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = "server-1",
                event =
                    MessageNew(
                        Message(
                            id = MessageId("message-2"),
                            threadId = ThreadId("channel-1"),
                            channelId = ChannelId("channel-1"),
                            seq = 2,
                            author = sampleMember(),
                            timestampLabel = "12:01",
                            body = "live update"
                        )
                    )
            )
        )

        val after = (store.state.value.content as SharedHomeContentState.Content).snapshot.channels
        assertTrue(before !== after)
        assertTrue(before[0] !== after[0])
        assertTrue(before[1] === after[1])
        assertEquals("live update", after[0].lastMessagePreview)
        assertEquals("random", after[1].name)
        scope.cancel()
    }

    @Test
    fun heartbeatDoesNotReloadVisibleHomeSnapshot() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val logs = mutableListOf<String>()
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeHome(sampleHome(ServerId("server-1")))
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource =
                SharedHomeDataSource(
                    sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                    api = ThrowingHomeApi(),
                    localWriteThrough = ComposeLocalWriteThrough(localStore, logger = logs::add),
                    parseHome = { _, _ -> error("network failure should not parse") }
                ),
            scope = scope
        )
        store.accept(SharedHomeIntent.Start)
        logs.clear()

        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = "server-1",
                event = Heartbeat(seq = 42)
            )
        )

        assertEquals(listOf("write_realtime_skip type=heartbeat seq=42"), logs)
        scope.cancel()
    }

    @Test
    fun refreshRequiredReloadsHomeWithoutVisibleRefreshAffordance() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = RecordingHomeApi()
        val logs = mutableListOf<String>()
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = source(api = api),
            scope = scope,
            diagnosticsLogger = logs::add
        )
        store.accept(SharedHomeIntent.Start)
        api.calls.clear()

        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = "server-1",
                event =
                    RealtimeRefreshRequired(
                        scope = RealtimeRefreshScope.ServerMembers,
                        reason = "server:member-updated",
                        serverId = ServerId("server-1")
                    )
            )
        )

        assertEquals(listOf("channels:server-1", "dms:server-1", "unread:server-1", "summary:server-1"), api.calls)
        assertContains(logs, "home_realtime_refresh scope=ServerMembers reason=server:member-updated")
        scope.cancel()
    }

    @Test
    fun duplicatePresenceDoesNotReloadVisibleHomeSnapshot() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val logs = mutableListOf<String>()
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeHome(
            sampleHome(ServerId("server-1")).copy(
                directMessages =
                    listOf(
                        ConversationSummary(
                            threadId = ThreadId("dm-1"),
                            channelId = ChannelId("dm-1"),
                            kind = ConversationKind.DirectMessage,
                            name = "Agent",
                            presence = Presence.Active,
                            presenceMemberId = MemberId("agent-1")
                        )
                    )
            )
        )
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource =
                SharedHomeDataSource(
                    sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                    api = ThrowingHomeApi(),
                    localWriteThrough = ComposeLocalWriteThrough(localStore, logger = logs::add),
                    parseHome = { _, _ -> error("network failure should not parse") }
                ),
            scope = scope
        )
        store.accept(SharedHomeIntent.Start)
        logs.clear()

        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = "server-1",
                event =
                    MemberPresenceUpdated(
                        memberId = MemberId("agent-1"),
                        presence = Presence.Active,
                        kind = MemberKind.Agent
                    )
            )
        )

        assertEquals(emptyList(), logs)
        scope.cancel()
    }

    @Test
    fun realtimePresenceUpdateOnlyReplacesMatchingDirectMessageRow() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeHome(
            sampleHome(ServerId("server-1")).copy(
                directMessages =
                    listOf(
                        directMessage("dm-1", "Agent One", "agent-1", Presence.Offline),
                        directMessage("dm-2", "Agent Two", "agent-2", Presence.Active)
                    )
            )
        )
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource =
                SharedHomeDataSource(
                    sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                    api = ThrowingHomeApi(),
                    localWriteThrough = ComposeLocalWriteThrough(localStore),
                    parseHome = { _, _ -> error("network failure should not parse") }
                ),
            scope = scope
        )
        store.accept(SharedHomeIntent.Start)
        val before = (store.state.value.content as SharedHomeContentState.Content).snapshot.directMessages

        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = "server-1",
                event =
                    MemberPresenceUpdated(
                        memberId = MemberId("agent-1"),
                        presence = Presence.Active,
                        kind = MemberKind.Agent
                    )
            )
        )

        val after = (store.state.value.content as SharedHomeContentState.Content).snapshot.directMessages
        assertTrue(before !== after)
        assertTrue(before[0] !== after[0])
        assertTrue(before[1] === after[1])
        assertEquals(Presence.Active, after[0].presence)
        assertEquals(Presence.Active, after[1].presence)
        scope.cancel()
    }

    @Test
    fun selectConversationTracksSelectedRowAndClearsTopAction() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = source(api = RecordingHomeApi()),
            scope = scope
        )

        store.accept(SharedHomeIntent.SelectTopAction(SharedHomeTopAction.Activity))
        store.accept(SharedHomeIntent.SelectConversation("channel-1"))

        assertEquals("channel-1", store.state.value.selectedConversationId)
        assertEquals(null, store.state.value.selectedTopAction)
        scope.cancel()
    }

    @Test
    fun selectTopActionTracksActionAndClearsSelectedConversation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = source(api = RecordingHomeApi()),
            scope = scope
        )

        store.accept(SharedHomeIntent.SelectConversation("dm-1"))
        store.accept(SharedHomeIntent.SelectTopAction(SharedHomeTopAction.Saved))

        assertEquals(null, store.state.value.selectedConversationId)
        assertEquals(SharedHomeTopAction.Saved, store.state.value.selectedTopAction)
        scope.cancel()
    }

    @Test
    fun refreshKeepsSelectedConversationAfterReturningFromThread() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = source(api = RecordingHomeApi()),
            scope = scope
        )

        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.SelectConversation("channel-2"))
        store.accept(SharedHomeIntent.Refresh)

        assertEquals("channel-2", store.state.value.selectedConversationId)
        assertEquals(null, store.state.value.selectedTopAction)
        scope.cancel()
    }

    @Test
    fun startRefreshWithExistingContentStaysSilent() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = BlockingHomeApi()
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = source(api = api),
            scope = scope
        )

        store.accept(SharedHomeIntent.Start)
        val initialContent = store.state.value.content
        assertTrue(initialContent is SharedHomeContentState.Content)
        assertEquals(false, initialContent.contentRefreshing)

        val blockedStart = api.blockNextChannels()
        val startJob = launch { store.accept(SharedHomeIntent.Start) }
        blockedStart.awaitStarted()

        val routeReturnContent = store.state.value.content
        assertTrue(routeReturnContent is SharedHomeContentState.Content)
        assertEquals(false, routeReturnContent.contentRefreshing)

        blockedStart.release()
        startJob.join()

        val blockedRefresh = api.blockNextChannels()
        val refreshJob = launch { store.accept(SharedHomeIntent.Refresh) }
        blockedRefresh.awaitStarted()

        val manualRefreshContent = store.state.value.content
        assertTrue(manualRefreshContent is SharedHomeContentState.Content)
        assertEquals(true, manualRefreshContent.contentRefreshing)

        blockedRefresh.release()
        refreshJob.join()
        scope.cancel()
    }

    @Test
    fun duplicateHomeSnapshotStartDoesNotEmitState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val logs = mutableListOf<String>()
        val localStore = InMemoryComposeLocalDataStore()
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource =
                SharedHomeDataSource(
                    sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                    api = RecordingHomeApi(),
                    localWriteThrough = ComposeLocalWriteThrough(localStore, logger = logs::add),
                    parseHome = { payload, _ -> DataResult.Content(sampleHome(payload.serverId)) }
                ),
            scope = scope
        )
        store.accept(SharedHomeIntent.Start)
        val observedStates = mutableListOf<SharedHomeState>()
        val observer = scope.launch {
            store.state.collect { state -> observedStates += state }
        }
        logs.clear()
        val countBeforeStart = observedStates.size

        store.accept(SharedHomeIntent.Start)

        assertEquals(countBeforeStart, observedStates.size)
        assertEquals(1, logs.count { it.startsWith("write_home_skip source=shared_home_data_source reason=unchanged") })
        observer.cancel()
        scope.cancel()
    }

    @Test
    fun channelCreatedOptimisticallyAddsAndSelectsChannel() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = source(api = RecordingHomeApi()),
            scope = scope
        )
        store.accept(SharedHomeIntent.Start)

        val created =
            ConversationSummary(
                threadId = ThreadId("channel-new"),
                channelId = ChannelId("channel-new"),
                kind = ConversationKind.Channel,
                name = "release-room"
            )
        store.accept(SharedHomeIntent.SelectTopAction(SharedHomeTopAction.Activity))
        store.accept(SharedHomeIntent.ChannelCreated(created))

        val content = store.state.value.content
        assertTrue(content is SharedHomeContentState.Content)
        assertEquals(listOf("general", "release-room"), content.snapshot.channels.map { it.name })
        assertEquals("channel-new", store.state.value.selectedConversationId)
        assertEquals(null, store.state.value.selectedTopAction)
        scope.cancel()
    }

    @Test
    fun startLoadsActiveAnnouncementsIntoHomeNotifications() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val announcementsApi = RecordingAnnouncementsApi()
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = source(api = RecordingHomeApi(), announcementsApi = announcementsApi),
            scope = scope
        )

        store.accept(SharedHomeIntent.Start)

        val notification = store.state.value.notifications.single()
        assertEquals("announcement-1", notification.id)
        assertEquals("Upgrade available", notification.title)
        assertEquals("Install the newest desktop daemon.", notification.body)
        // The server `Announcement` contract has no severity / actionLabel — see
        // packages/shared/src/index.ts. The home store maps every announcement
        // to the neutral `Info` notification kind by design.
        assertEquals(SharedHomeNotificationKind.Info, notification.kind)
        assertEquals(listOf("GET announcements/active"), announcementsApi.calls)
        scope.cancel()
    }

    @Test
    fun dismissNotificationRemovesFromStateAndCallsSharedApi() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val announcementsApi = RecordingAnnouncementsApi()
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = source(api = RecordingHomeApi(), announcementsApi = announcementsApi),
            scope = scope
        )
        store.accept(SharedHomeIntent.Start)

        store.accept(SharedHomeIntent.DismissNotification("announcement-1"))

        assertEquals(emptyList(), store.state.value.notifications)
        assertEquals(
            listOf("GET announcements/active", "POST announcements/announcement-1/dismiss"),
            announcementsApi.calls
        )
        scope.cancel()
    }

    private fun source(
        api: SlockHomeApi,
        announcementsApi: SlockAnnouncementsRemindersApi? = null
    ): SharedHomeDataSource =
        SharedHomeDataSource(
            sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
            api = api,
            announcementsApi = announcementsApi,
            parseHome = { payload, _ -> DataResult.Content(sampleHome(payload.serverId)) }
        )

    private class RecordingHomeApi : SlockHomeApi {
        val calls = mutableListOf<String>()

        override suspend fun channels(priority: DataRequestPriority): HomeNetworkResponse =
            error("server-specific overload should be used")

        override suspend fun directMessages(priority: DataRequestPriority): HomeNetworkResponse =
            error("server-specific overload should be used")

        override suspend fun channelUnreadCounts(priority: DataRequestPriority): HomeNetworkResponse =
            error("server-specific overload should be used")

        override suspend fun serverUnreadSummary(priority: DataRequestPriority): HomeNetworkResponse =
            error("server-specific overload should be used")

        override suspend fun channels(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse =
            ok("channels", serverId)

        override suspend fun directMessages(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse =
            ok("dms", serverId)

        override suspend fun channelUnreadCounts(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse =
            ok("unread", serverId)

        override suspend fun serverUnreadSummary(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse =
            ok("summary", serverId)

        private fun ok(name: String, serverId: ServerId): HomeNetworkResponse {
            calls += "$name:${serverId.value}"
            val body =
                when (name) {
                    "channels", "dms", "summary" -> "[]"
                    else -> "{}"
                }
            return HomeNetworkResponse(code = 200, bodyText = body)
        }
    }

    private class BlockingHomeApi : SlockHomeApi {
        private var channelBlock: BlockingCall? = null

        fun blockNextChannels(): BlockingCall =
            BlockingCall().also { channelBlock = it }

        override suspend fun channels(priority: DataRequestPriority): HomeNetworkResponse =
            error("server-specific overload should be used")

        override suspend fun directMessages(priority: DataRequestPriority): HomeNetworkResponse =
            error("server-specific overload should be used")

        override suspend fun channelUnreadCounts(priority: DataRequestPriority): HomeNetworkResponse =
            error("server-specific overload should be used")

        override suspend fun serverUnreadSummary(priority: DataRequestPriority): HomeNetworkResponse =
            error("server-specific overload should be used")

        override suspend fun channels(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse {
            channelBlock?.let { block ->
                channelBlock = null
                block.started.complete(Unit)
                block.released.await()
            }
            return HomeNetworkResponse(code = 200, bodyText = "[]")
        }

        override suspend fun directMessages(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = "[]")

        override suspend fun channelUnreadCounts(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = "{}")

        override suspend fun serverUnreadSummary(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = "[]")
    }

    private class BlockingCall {
        val started = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()

        suspend fun awaitStarted() {
            started.await()
        }

        fun release() {
            released.complete(Unit)
        }
    }

    private class ServerUnreadHomeApi(
        private val unreadByServerId: Map<String, Int>
    ) : SlockHomeApi {
        val calls = mutableListOf<String>()

        override suspend fun channels(priority: DataRequestPriority): HomeNetworkResponse =
            error("server-specific overload should be used")

        override suspend fun directMessages(priority: DataRequestPriority): HomeNetworkResponse =
            error("server-specific overload should be used")

        override suspend fun channelUnreadCounts(priority: DataRequestPriority): HomeNetworkResponse =
            error("server-specific overload should be used")

        override suspend fun serverUnreadSummary(priority: DataRequestPriority): HomeNetworkResponse =
            error("server-specific overload should be used")

        override suspend fun channels(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse {
            calls += "channels:${serverId.value}"
            return HomeNetworkResponse(
                code = 200,
                bodyText =
                    """
                    [
                      {"id":"${serverId.value}-channel","name":"${serverId.value} channel","type":"channel","joined":true}
                    ]
                    """.trimIndent()
            )
        }

        override suspend fun directMessages(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse {
            calls += "dms:${serverId.value}"
            return HomeNetworkResponse(code = 200, bodyText = "[]")
        }

        override suspend fun channelUnreadCounts(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse {
            calls += "unread:${serverId.value}"
            return HomeNetworkResponse(code = 200, bodyText = """{"${serverId.value}-channel":1}""")
        }

        override suspend fun serverUnreadSummary(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse {
            calls += "summary:${serverId.value}"
            val unread = unreadByServerId[serverId.value] ?: 0
            return HomeNetworkResponse(
                code = 200,
                bodyText =
                    """
                    [
                      {"serverId":"${serverId.value}","totalUnreadCount":$unread}
                    ]
                    """.trimIndent()
            )
        }
    }

    private class ThrowingHomeApi : SlockHomeApi {
        override suspend fun channels(priority: DataRequestPriority): HomeNetworkResponse =
            throw IllegalStateException("offline")

        override suspend fun directMessages(priority: DataRequestPriority): HomeNetworkResponse =
            error("not reached")

        override suspend fun channelUnreadCounts(priority: DataRequestPriority): HomeNetworkResponse =
            error("not reached")

        override suspend fun serverUnreadSummary(priority: DataRequestPriority): HomeNetworkResponse =
            error("not reached")
    }

    private class RecordingAnnouncementsApi : SlockAnnouncementsRemindersApi {
        val calls = mutableListOf<String>()

        override suspend fun activeAnnouncements(): HomeNetworkResponse {
            calls += "GET announcements/active"
            return HomeNetworkResponse(
                code = 200,
                bodyText =
                    """
                    {
                      "announcements": [
                        {
                          "id": "announcement-1",
                          "title": "Upgrade available",
                          "pages": [
                            { "body": "Install the newest desktop daemon." }
                          ]
                        }
                      ]
                    }
                    """.trimIndent()
            )
        }

        override suspend fun dismissAnnouncement(announcementId: String): HomeNetworkResponse {
            calls += "POST announcements/$announcementId/dismiss"
            return HomeNetworkResponse(code = 200, bodyText = """{"ok":true}""")
        }

        override suspend fun reminders(ownerAgentId: MemberId, status: String): HomeNetworkResponse =
            error("not reached")
    }

    private fun sampleSession(): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "artin@slock.ai", displayName = "Artin"),
            accessToken = "token-a",
            refreshToken = "token-r",
            selectedServerId = "server-1",
            servers =
                listOf(
                    StoredAuthServer(id = "server-1", name = "Slock Community", slug = "slock"),
                    StoredAuthServer(id = "server-2", name = "Work", slug = "work")
                )
        )

    private fun sampleHome(serverId: ServerId): HomeSnapshot =
        HomeSnapshot(
            server = ServerSummary(serverId, if (serverId.value == "server-2") "Work" else "Slock Community"),
            currentMember = sampleMember(),
            channels =
                listOf(
                    ConversationSummary(
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        kind = ConversationKind.Channel,
                        name = "general"
                    )
                ),
            directMessages = emptyList(),
            unreadTotal = 0
        )

    private fun channel(id: String, name: String): ConversationSummary =
        ConversationSummary(
            threadId = ThreadId(id),
            channelId = ChannelId(id),
            kind = ConversationKind.Channel,
            name = name
        )

    private fun directMessage(
        id: String,
        name: String,
        memberId: String,
        presence: Presence
    ): ConversationSummary =
        ConversationSummary(
            threadId = ThreadId(id),
            channelId = ChannelId(id),
            kind = ConversationKind.DirectMessage,
            name = name,
            presence = presence,
            presenceMemberId = MemberId(memberId)
        )

    private fun sampleMember(): MemberProfile =
        MemberProfile(
            id = MemberId("user-1"),
            displayName = "Artin",
            handle = "artin@slock.ai",
            kind = MemberKind.Human,
            presence = Presence.Active
        )
}
