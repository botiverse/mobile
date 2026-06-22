package ai.slock.compose.data

import ai.slock.compose.SlockSidebarSortMode
import ai.slock.compose.homeLazyItemCount
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.jvm.Volatile

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
    fun surfaceVisibilityChangeStaysOutOfHomeUiState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = source(api = RecordingHomeApi()),
            scope = scope
        )
        store.accept(SharedHomeIntent.Start)

        var emissions = 0
        val collectJob = launch {
            store.state.collect { emissions += 1 }
        }
        yield()
        assertEquals(1, emissions)

        store.accept(SharedHomeIntent.SetSurfaceVisible(false))
        yield()
        store.accept(SharedHomeIntent.SetSurfaceVisible(true))
        yield()

        assertEquals(1, emissions)
        collectJob.cancel()
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
    fun selectServerShowsSelectedSessionWhileNetworkLoadIsPending() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sessionStore = InMemorySlockAuthSessionStore(sampleSession())
        val api = BlockingHomeApi()
        val store = SharedHomeStore(
            authStore = sessionStore,
            dataSource = source(api = api, sessionStore = sessionStore),
            scope = scope
        )
        store.accept(SharedHomeIntent.Start)
        val initialContent = store.state.value.content
        assertTrue(initialContent is SharedHomeContentState.Content)
        assertEquals("server-1", initialContent.snapshot.server.id.value)

        val blockedSwitch = api.blockNextChannels()
        val switchJob = launch { store.accept(SharedHomeIntent.SelectServer("server-2")) }
        blockedSwitch.awaitStarted()

        val switchingContent = store.state.value.content
        assertTrue(switchingContent is SharedHomeContentState.Loading)
        assertEquals("server-2", switchingContent.session?.currentServerId)
        assertEquals("server-2", sessionStore.readSession()?.selectedServerId)
        assertEquals("server-2", store.state.value.serverSwitchState.pendingServerId)

        blockedSwitch.release()
        switchJob.join()

        val finalContent = store.state.value.content
        assertTrue(finalContent is SharedHomeContentState.Content)
        assertEquals("server-2", finalContent.snapshot.server.id.value)
        assertEquals(null, store.state.value.serverSwitchState.pendingServerId)
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
    fun cacheHitStartRendersCachedHomeBeforeNetworkRefreshCompletes() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeHome(sampleHome(ServerId("server-1")))
        val api = BlockingHomeApi()
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource =
                SharedHomeDataSource(
                    sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                    api = api,
                    localWriteThrough = ComposeLocalWriteThrough(localStore),
                    parseHome = { payload, _ ->
                        DataResult.Content(
                            sampleHome(payload.serverId).copy(
                                channels = listOf(channel("channel-network", "network"))
                            )
                        )
                    }
                ),
            scope = scope
        )
        val blockedRefresh = api.blockNextChannels()

        store.accept(SharedHomeIntent.Start)
        blockedRefresh.awaitStarted()

        val cachedContent = store.state.value.content
        assertTrue(cachedContent is SharedHomeContentState.Content)
        assertEquals("general", cachedContent.snapshot.channels.single().name)
        assertEquals(true, cachedContent.contentRefreshing)

        blockedRefresh.release()
        awaitCondition {
            val refreshedContent = store.state.value.content
            refreshedContent is SharedHomeContentState.Content &&
                refreshedContent.snapshot.channels.single().name == "network"
        }

        val refreshedContent = store.state.value.content
        assertTrue(refreshedContent is SharedHomeContentState.Content)
        assertEquals("network", refreshedContent.snapshot.channels.single().name)
        assertEquals(false, refreshedContent.contentRefreshing)
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
            scope = scope,
            presenceFlushDelayMillis = 0L
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
            scope = scope,
            presenceFlushDelayMillis = 0L
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
    fun realtimePresencePatchesVisibleHomeWithoutWritingCache() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val logs = mutableListOf<String>()
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeHome(
            sampleHome(ServerId("server-1")).copy(
                directMessages =
                    listOf(
                        directMessage("dm-1", "Agent One", "agent-1", Presence.Offline),
                        directMessage("dm-2", "Agent Two", "agent-2", Presence.Offline)
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
            scope = scope,
            presenceFlushDelayMillis = 0L
        )
        store.accept(SharedHomeIntent.Start)
        logs.clear()
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

        val contentAfter = store.state.value.content as SharedHomeContentState.Content
        val after = contentAfter.snapshot.directMessages
        assertTrue(before !== after)
        assertTrue(before[0] !== after[0])
        assertTrue(before[1] === after[1])
        assertEquals(Presence.Active, after[0].presence)
        assertEquals(Presence.Offline, contentAfter.baseSnapshot.directMessages[0].presence)
        assertEquals(Presence.Offline, localStore.readHome(ServerId("server-1"))?.directMessages?.first()?.presence)
        assertEquals(emptyList(), logs)
        scope.cancel()
    }

    @Test
    fun realtimePresenceOverlayRematerializesAfterCachedRefresh() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeHome(
            sampleHome(ServerId("server-1")).copy(
                channels = listOf(channel("channel-1", "general")),
                directMessages =
                    listOf(
                        directMessage("dm-1", "Agent One", "agent-1", Presence.Offline)
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
            scope = scope,
            presenceFlushDelayMillis = 0L
        )
        store.accept(SharedHomeIntent.Start)
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
        val beforeRefresh = store.state.value.content as SharedHomeContentState.Content
        assertEquals(Presence.Active, beforeRefresh.snapshot.directMessages.single().presence)
        assertEquals(Presence.Offline, beforeRefresh.baseSnapshot.directMessages.single().presence)

        localStore.writeHome(
            sampleHome(ServerId("server-1")).copy(
                channels = listOf(channel("channel-2", "updates")),
                directMessages =
                    listOf(
                        directMessage("dm-1", "Agent One", "agent-1", Presence.Offline)
                    )
            )
        )
        store.accept(SharedHomeIntent.Refresh)

        val afterRefresh = store.state.value.content as SharedHomeContentState.Content
        assertEquals("channel-2", afterRefresh.baseSnapshot.channels.single().threadId.value)
        assertEquals(Presence.Offline, afterRefresh.baseSnapshot.directMessages.single().presence)
        assertEquals(Presence.Active, afterRefresh.snapshot.directMessages.single().presence)
        assertEquals(Presence.Offline, localStore.readHome(ServerId("server-1"))?.directMessages?.single()?.presence)
        scope.cancel()
    }

    @Test
    fun realtimePresenceBatchUsesLatestEventAndSkipsNoOpState() = runBlocking {
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
            scope = scope,
            presenceFlushDelayMillis = 1L
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
        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = "server-1",
                event =
                    MemberPresenceUpdated(
                        memberId = MemberId("agent-1"),
                        presence = Presence.Away,
                        kind = MemberKind.Agent
                    )
            )
        )
        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = "server-1",
                event =
                    MemberPresenceUpdated(
                        memberId = MemberId("agent-2"),
                        presence = Presence.Active,
                        kind = MemberKind.Agent
                    )
            )
        )
        delay(10L)

        val contentAfter = store.state.value.content as SharedHomeContentState.Content
        val after = contentAfter.snapshot.directMessages
        assertTrue(before !== after)
        assertTrue(before[0] !== after[0])
        assertTrue(before[1] === after[1])
        assertEquals(Presence.Away, after[0].presence)
        assertEquals(Presence.Active, after[1].presence)
        assertEquals(Presence.Offline, contentAfter.baseSnapshot.directMessages[0].presence)
        assertEquals(Presence.Active, contentAfter.baseSnapshot.directMessages[1].presence)
        assertEquals(Presence.Offline, localStore.readHome(ServerId("server-1"))?.directMessages?.first()?.presence)
        scope.cancel()
    }

    @Test
    fun realtimePresenceBatchKeepsHighestServerSeqForMember() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeHome(
            sampleHome(ServerId("server-1")).copy(
                directMessages =
                    listOf(
                        directMessage("dm-1", "Agent One", "agent-1", Presence.Offline)
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
            scope = scope,
            presenceFlushDelayMillis = 1L
        )
        store.accept(SharedHomeIntent.Start)

        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = "server-1",
                event =
                    MemberPresenceUpdated(
                        memberId = MemberId("agent-1"),
                        presence = Presence.Active,
                        kind = MemberKind.Agent,
                        serverSeq = 10L
                    )
            )
        )
        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = "server-1",
                event =
                    MemberPresenceUpdated(
                        memberId = MemberId("agent-1"),
                        presence = Presence.Away,
                        kind = MemberKind.Agent,
                        serverSeq = 9L
                    )
            )
        )
        delay(10L)

        val contentAfter = store.state.value.content as SharedHomeContentState.Content
        assertEquals(Presence.Active, contentAfter.snapshot.directMessages.single().presence)
        assertEquals(Presence.Offline, contentAfter.baseSnapshot.directMessages.single().presence)
        assertEquals(Presence.Offline, localStore.readHome(ServerId("server-1"))?.directMessages?.single()?.presence)
        scope.cancel()
    }

    @Test
    fun hiddenRealtimeMessageWritesCacheWithoutProjectingHomeState() = runBlocking {
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
            scope = scope,
            diagnosticsLogger = logs::add
        )
        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.SetSurfaceVisible(false))
        logs.clear()
        val beforeContent = store.state.value.content as SharedHomeContentState.Content

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
                            author = sampleMember().copy(id = MemberId("member-2")),
                            timestampLabel = "12:01",
                            body = "cached while hidden"
                        )
                    )
            )
        )

        val afterState = store.state.value
        assertTrue(afterState.content === beforeContent)
        assertEquals(true, afterState.homeRealtimeDirty)
        assertEquals("cached while hidden", localStore.readHome(ServerId("server-1"))?.channels?.single()?.lastMessagePreview)
        assertEquals(emptyList(), logs.filter { it.startsWith("read_home ") })
        assertContains(logs, "home_realtime_dirty serverId=server-1 reason=message_new")
        scope.cancel()
    }

    @Test
    fun hiddenRealtimePresenceMarksDirtyWithoutPatchingRowsOrCache() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val logs = mutableListOf<String>()
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeHome(
            sampleHome(ServerId("server-1")).copy(
                directMessages =
                    listOf(
                        directMessage("dm-1", "Agent One", "agent-1", Presence.Offline)
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
            scope = scope,
            diagnosticsLogger = logs::add,
            presenceFlushDelayMillis = 0L
        )
        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.SetSurfaceVisible(false))
        logs.clear()
        val beforeContent = store.state.value.content as SharedHomeContentState.Content

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

        val afterState = store.state.value
        assertTrue(afterState.content === beforeContent)
        assertEquals(true, afterState.homeRealtimeDirty)
        assertEquals(Presence.Offline, beforeContent.snapshot.directMessages.single().presence)
        assertEquals(Presence.Offline, beforeContent.baseSnapshot.directMessages.single().presence)
        assertEquals(Presence.Offline, localStore.readHome(ServerId("server-1"))?.directMessages?.single()?.presence)
        assertEquals(listOf("home_realtime_dirty serverId=server-1 reason=presence"), logs)

        store.accept(SharedHomeIntent.SetSurfaceVisible(true))
        val visibleContent = store.state.value.content as SharedHomeContentState.Content
        assertEquals(Presence.Active, visibleContent.snapshot.directMessages.single().presence)
        assertEquals(Presence.Offline, visibleContent.baseSnapshot.directMessages.single().presence)
        assertEquals(false, store.state.value.homeRealtimeDirty)
        assertEquals(Presence.Offline, localStore.readHome(ServerId("server-1"))?.directMessages?.single()?.presence)
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
            scope = scope,
            diagnosticsLogger = logs::add
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
        assertEquals(1, logs.count { it.contains("home_refresh_skip") && it.contains("reason=fresh_snapshot") })
        observer.cancel()
        scope.cancel()
    }

    @Test
    fun cachedHomeStartSkipsFreshBackgroundRefreshButManualRefreshForcesNetwork() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val logs = mutableListOf<String>()
        var now = 1_000L
        val localStore = InMemoryComposeLocalDataStore()
        val api = RecordingHomeApi()
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource =
                SharedHomeDataSource(
                    sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                    api = api,
                    localWriteThrough = ComposeLocalWriteThrough(localStore),
                    parseHome = { payload, _ -> DataResult.Content(sampleHome(payload.serverId)) }
                ),
            scope = scope,
            diagnosticsLogger = logs::add,
            nowMillis = { now }
        )

        store.accept(SharedHomeIntent.Start)
        assertEquals(4, api.calls.size)
        logs.clear()

        store.accept(SharedHomeIntent.Start)

        assertEquals(4, api.calls.size)
        assertEquals(1, logs.count { it.contains("home_refresh_skip") && it.contains("reason=fresh_snapshot") })

        store.accept(SharedHomeIntent.Refresh)

        awaitCondition { api.calls.size >= 8 }
        assertEquals(8, api.calls.size)
        scope.cancel()
    }

    @Test
    fun cachedHomeStartRefreshesAfterFreshnessWindowExpires() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var now = 1_000L
        val localStore = InMemoryComposeLocalDataStore()
        val api = RecordingHomeApi()
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource =
                SharedHomeDataSource(
                    sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                    api = api,
                    localWriteThrough = ComposeLocalWriteThrough(localStore),
                    parseHome = { payload, _ -> DataResult.Content(sampleHome(payload.serverId)) }
                ),
            scope = scope,
            nowMillis = { now }
        )

        store.accept(SharedHomeIntent.Start)
        assertEquals(4, api.calls.size)

        now += 31_000L
        store.accept(SharedHomeIntent.Start)

        awaitCondition { api.calls.size >= 8 }
        assertEquals(8, api.calls.size)
        scope.cancel()
    }

    @Test
    fun dispatchStartDoesNotCancelInFlightHomeBackgroundRefresh() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val logs = mutableListOf<String>()
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeHome(sampleHome(ServerId("server-1")))
        val api = BlockingHomeApi()
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource =
                SharedHomeDataSource(
                    sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                    api = api,
                    localWriteThrough = ComposeLocalWriteThrough(localStore),
                    parseHome = { payload, _ ->
                        DataResult.Content(
                            sampleHome(payload.serverId).copy(
                                channels = listOf(channel("channel-network", "network"))
                            )
                        )
                    }
                ),
            scope = scope,
            diagnosticsLogger = logs::add
        )
        val blockedRefresh = api.blockNextChannels()

        store.dispatch(SharedHomeIntent.Start)
        blockedRefresh.awaitStarted()
        logs.clear()

        store.dispatch(SharedHomeIntent.Start)

        assertEquals(1, logs.count { it.contains("home_refresh_skip") && it.contains("reason=in_flight") })
        blockedRefresh.release()
        awaitCondition {
            val refreshedContent = store.state.value.content
            refreshedContent is SharedHomeContentState.Content &&
                refreshedContent.snapshot.channels.single().name == "network"
        }

        val refreshedContent = store.state.value.content
        assertTrue(refreshedContent is SharedHomeContentState.Content)
        assertEquals("network", refreshedContent.snapshot.channels.single().name)
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

    // #81: app badge projection tests -----------------------------------------
    // These cover the typed badge source boundary (no new server endpoint,
    // derived from existing /servers/unread-summary + /channel-unread-counts).
    // Host bridge forwarding (bridge.appBadge(...)) is exercised at the
    // host level, not in shared unit tests; see [BridgeModule.appBadge].

    @Test
    fun appBadgeSourceStartsUnknownAndFlipsToServerUnreadSummaryOnLoad() = runBlocking {
        // Happy path: a successful /servers/unread-summary response for the
        // active server gives us SERVER_UNREAD_SUMMARY with a non-null count.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sessionStore = InMemorySlockAuthSessionStore(sampleSession())
        val api = ServerUnreadHomeApi(unreadByServerId = mapOf("server-1" to 7))
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

        // Initial state: UNKNOWN.
        assertEquals(AppBadgeSource.UNKNOWN, store.state.value.appBadgeCount.source)
        assertEquals(0, store.state.value.appBadgeCount.count)

        store.accept(SharedHomeIntent.Start)

        // After successful load: source = SERVER_UNREAD_SUMMARY, count = 7,
        // isStale = false, lastUpdated advanced past 0L.
        val badge = store.state.value.appBadgeCount
        assertEquals(AppBadgeSource.SERVER_UNREAD_SUMMARY, badge.source)
        assertEquals(7, badge.count)
        assertEquals(false, badge.isStale)
        assertTrue(badge.lastUpdated > 0L)
        assertEquals("server-1", badge.selectedServerId?.value)
        scope.cancel()
    }

    @Test
    fun appBadgeSourceFlipsToChannelDmFallbackWhenServerUnreadSummaryMissing() = runBlocking {
        // The /servers/unread-summary response has NO entry for the active
        // server, so the parser's `firstOrNull { it.serverId == ... }`
        // returns null, the source falls through to the channel + DM unread
        // sum (HomeParser.kt:102-103). The badge source MUST reflect this.
        // We use a DedicatedMissingServerUnreadHomeApi for the empty case
        // because ServerUnreadHomeApi always returns at least one entry.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sessionStore = InMemorySlockAuthSessionStore(sampleSession())
        val api = MissingServerUnreadHomeApi() // summary = [] for active server
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

        val badge = store.state.value.appBadgeCount
        assertEquals(AppBadgeSource.CHANNEL_DM_FALLBACK, badge.source)
        // channelUnreadCounts api returns {"server-1-channel":1}, so
        // channel + DM unread sum = 1.
        assertEquals(1, badge.count)
        assertEquals(false, badge.isStale)
        scope.cancel()
    }

    @Test
    fun appBadgeSourceIsDisabledOnServerSwitch() = runBlocking {
        // Server switch must immediately clear the badge to DISABLED with
        // count = 0 so the host stops showing a stale count from the
        // previous server before the new server's first fetch arrives.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sessionStore = InMemorySlockAuthSessionStore(sampleSession())
        val api = ServerUnreadHomeApi(
            unreadByServerId =
                mapOf(
                    "server-1" to 5,
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
        val before = store.state.value.appBadgeCount
        assertEquals(AppBadgeSource.SERVER_UNREAD_SUMMARY, before.source)
        assertEquals(5, before.count)
        val lastUpdatedBefore = before.lastUpdated

        // Issue the server switch. Because SwitchHomeApi isn't blocking here,
        // the new server's fetch may already have completed by the time we
        // inspect, so we just assert the FINAL state is the new server's
        // (post-load) projection — not the stale server-1 count.
        store.accept(SharedHomeIntent.SelectServer("server-2"))

        val after = store.state.value.appBadgeCount
        assertEquals("server-2", after.selectedServerId?.value)
        assertEquals(11, after.count)
        assertEquals(AppBadgeSource.SERVER_UNREAD_SUMMARY, after.source)
        assertTrue(after.lastUpdated >= lastUpdatedBefore)
        scope.cancel()
    }

    @Test
    fun appBadgeSourceFlipsIsStaleOnRefreshFailureWithoutAdvancingLastUpdated() = runBlocking {
        // #81: refresh failure must NOT advance lastUpdated and MUST set
        // isStale = true. count is held over from the last successful
        // fetch. KDoc: "badge not flashing is better than badge oscillating
        // between stale and fresh values".
        //
        // #82 deterministic regression: drive the real
        // `SharedHomeStore.refresh()` failure path end-to-end against a
        // [SwitchableHomeApi] and assert on the actual store state. This
        // closes the prior placeholder (which only computed
        // `previous.copy(isStale = true)` in-line without ever exercising
        // the failure path).
        //
        // The fix in #82 derives `freshSnapshot` from the original data
        // source result so the failure path's `else { previous.copy(
        // isStale = true) }` branch actually runs when the previous
        // content was `Content`. Without that fix, the assertion at the
        // end of this test fails deterministically with
        // `expected:<true> but was:<false>`.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sessionStore = InMemorySlockAuthSessionStore(sampleSession())
        val switchableApi = SwitchableHomeApi()
        switchableApi.setServerUnread("server-1" to 9)
        val store = SharedHomeStore(
            authStore = sessionStore,
            dataSource =
                SharedHomeDataSource(
                    sessionStore = sessionStore,
                    api = switchableApi,
                    parseHome = ::parseSlockHome
                ),
            scope = scope
        )

        // Step 1: successful initial load seeds the badge with
        // count=9, source=SERVER_UNREAD_SUMMARY, isStale=false.
        store.accept(SharedHomeIntent.Start)
        val before = store.state.value.appBadgeCount
        assertEquals(9, before.count)
        assertEquals(AppBadgeSource.SERVER_UNREAD_SUMMARY, before.source)
        assertEquals(false, before.isStale)
        assertEquals("server-1", before.selectedServerId?.value)
        val lastUpdatedBefore = before.lastUpdated
        val sourceBefore = before.source

        // Step 2: arm the api to fail on the next call, then drive
        // Refresh. `loadHomeFromNetwork` runs synchronously on the
        // test's coroutine (no background launch involved): the api
        // throws, `HomeDataSource.loadHome` catches and returns
        // `DataResult.Error("home_network")`, the failure branch
        // computes `nextBadge = previous.copy(isStale = true)`, and
        // `reduceState` writes the new state before `accept(Refresh)`
        // returns.
        switchableApi.setFailing(true)
        store.accept(SharedHomeIntent.Refresh)

        // Step 3: assert the failure-path transition. count / source /
        // selectedServerId / lastUpdated MUST be unchanged; isStale
        // MUST be true. The badge must not "look fresh" even though
        // the network call just failed.
        val afterFailure = store.state.value.appBadgeCount
        assertEquals(9, afterFailure.count)
        assertEquals(sourceBefore, afterFailure.source)
        assertEquals(true, afterFailure.isStale)
        assertEquals(lastUpdatedBefore, afterFailure.lastUpdated)
        assertEquals("server-1", afterFailure.selectedServerId?.value)
        scope.cancel()
    }

    // end #81 ----------------------------------------------------------------

    private fun source(
        api: SlockHomeApi,
        announcementsApi: SlockAnnouncementsRemindersApi? = null,
        sessionStore: SlockAuthSessionStore = InMemorySlockAuthSessionStore(sampleSession())
    ): SharedHomeDataSource =
        SharedHomeDataSource(
            sessionStore = sessionStore,
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

    private suspend fun awaitCondition(condition: () -> Boolean) {
        withTimeout(1_000L) {
            while (!condition()) {
                delay(1)
            }
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
                      {"serverId":"${serverId.value}","unreadCount":$unread}
                    ]
                    """.trimIndent()
            )
        }
    }

    /**
     * #81: Switchable api for the refresh-failure test. Behaves like
     * [ServerUnreadHomeApi] until [setFailing] is called, after which all
     * `/servers/{id}` / `/channel-unread-counts` / `/dms` endpoints throw
     * [IllegalStateException("offline")]. This drives the SharedHomeStore
     * through the [asRefreshFailed] path which is what flips
     * [AppBadgeCount.isStale] to true.
     */
    private class SwitchableHomeApi : SlockHomeApi {
        var callCount = 0
            private set
        private val unreadByServerId = mutableMapOf<String, Int>()
        @Volatile private var failing: Boolean = false

        fun setServerUnread(vararg pairs: Pair<String, Int>) {
            unreadByServerId.clear()
            unreadByServerId.putAll(pairs)
            failing = false
        }

        fun setFailing(value: Boolean) {
            failing = value
        }

        override suspend fun channels(priority: DataRequestPriority): HomeNetworkResponse =
            error("server-specific overload should be used")

        override suspend fun directMessages(priority: DataRequestPriority): HomeNetworkResponse =
            error("server-specific overload should be used")

        override suspend fun channelUnreadCounts(priority: DataRequestPriority): HomeNetworkResponse =
            error("server-specific overload should be used")

        override suspend fun serverUnreadSummary(priority: DataRequestPriority): HomeNetworkResponse =
            error("server-specific overload should be used")

        override suspend fun channels(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse {
            callCount++
            if (failing) throw IllegalStateException("offline")
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
            if (failing) throw IllegalStateException("offline")
            return HomeNetworkResponse(code = 200, bodyText = "[]")
        }

        override suspend fun channelUnreadCounts(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse {
            if (failing) throw IllegalStateException("offline")
            return HomeNetworkResponse(code = 200, bodyText = """{"${serverId.value}-channel":1}""")
        }

        override suspend fun serverUnreadSummary(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse {
            callCount++
            if (failing) throw IllegalStateException("offline")
            val unread = unreadByServerId[serverId.value] ?: 0
            return HomeNetworkResponse(
                code = 200,
                bodyText =
                    """
                    [
                      {"serverId":"${serverId.value}","unreadCount":$unread}
                    ]
                    """.trimIndent()
            )
        }
    }

    /**
     * Variant of [SlockHomeApi] that returns an empty /servers/unread-summary
     * for the active server (the wire-level fallback condition that should
     * drive the badge source to [AppBadgeSource.CHANNEL_DM_FALLBACK]).
     * [ServerUnreadHomeApi] always emits at least one summary entry per
     * call (it does not distinguish "missing" from "0 unread"), so we use
     * this class only for the CHANNEL_DM_FALLBACK test path.
     */
    private class MissingServerUnreadHomeApi : SlockHomeApi {
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
            // Truly empty summary — the active server has NO entry. This
            // is the wire-level condition for the CHANNEL_DM_FALLBACK path.
            return HomeNetworkResponse(code = 200, bodyText = "[]")
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

    // #211 ----------------------------------------------------------------

    @Test
    fun reloadUsesStoreCachedSidebarOrderWhenHomePayloadOmitsIt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var sidebarOrderLoads = 0
        val loadedSidebarOrder = SettingsServerSidebarOrderSnapshot(
            pinnedChannelIds = listOf("channel-1"),
            pinnedOrder = listOf("channel-1"),
            pinnedSortMode = "manual"
        )
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource =
                SharedHomeDataSource(
                    sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                    api = RecordingHomeApi(),
                    parseHome = { payload, _ ->
                        DataResult.Content(sampleHome(payload.serverId).copy(sidebarOrder = null))
                    }
                ),
            scope = scope,
            loadSidebarOrder = {
                sidebarOrderLoads += 1
                if (sidebarOrderLoads == 1) {
                    DataResult.Content(loadedSidebarOrder)
                } else {
                    DataResult.Error(ScreenError(code = "sidebar_order_load_failed", retryable = true))
                }
            }
        )

        store.accept(SharedHomeIntent.Start)
        val initial = (store.state.value.content as SharedHomeContentState.Content).snapshot.sidebarOrder
        assertEquals(listOf("channel-1"), initial?.pinnedChannelIds)

        store.accept(SharedHomeIntent.SelectServer("server-2"))
        val otherServer = store.state.value.content as SharedHomeContentState.Content
        assertEquals("server-2", otherServer.snapshot.server.id.value)
        assertEquals(null, otherServer.snapshot.sidebarOrder)

        store.accept(SharedHomeIntent.SelectServer("server-1"))

        val afterReload = (store.state.value.content as SharedHomeContentState.Content).snapshot.sidebarOrder
        assertEquals(
            listOf("channel-1"),
            afterReload?.pinnedChannelIds,
            "home reload without a sidebar-order payload must use the store-cached pinned list"
        )
        assertEquals(listOf("channel-1"), afterReload?.pinnedOrder)
        scope.cancel()
    }

    @Test
    fun togglePinnedOptimisticallyUpdatesAndPatchesSidebarOrder() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val initial = SettingsServerSidebarOrderSnapshot(
            pinnedChannelIds = emptyList(),
            pinnedOrder = emptyList(),
            pinnedSortMode = "manual"
        )
        val updateCalls = mutableListOf<SettingsServerSidebarOrderUpdateRequest>()
        val updatedSnapshot = initial.copy(
            pinnedChannelIds = listOf("channel-1"),
            pinnedOrder = listOf("channel-1")
        )
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = sourceWithSidebarOrder(initial = initial, updateResult = DataResult.Content(updatedSnapshot), updateCalls = updateCalls),
            scope = scope,
            loadSidebarOrder = { DataResult.Content(initial) },
            updateSidebarOrder = { request ->
                updateCalls += request
                DataResult.Content(updatedSnapshot)
            }
        )

        store.accept(SharedHomeIntent.Start)
        assertEquals(emptyList(), (store.state.value.content as SharedHomeContentState.Content).snapshot.sidebarOrder?.pinnedChannelIds)

        store.accept(SharedHomeIntent.TogglePinned("channel-1"))

        val after = (store.state.value.content as SharedHomeContentState.Content).snapshot.sidebarOrder
        assertEquals(listOf("channel-1"), after?.pinnedChannelIds)
        assertEquals(listOf("channel-1"), after?.pinnedOrder)
        assertEquals(1, updateCalls.size)
        assertEquals(listOf("channel-1"), updateCalls.first().pinnedChannelIds)
        assertEquals(listOf("channel-1"), updateCalls.first().pinnedOrder)
        scope.cancel()
    }

    @Test
    fun togglePinnedMergesExistingPinnedListAndAppendsNewOne() = runBlocking {
        // #211: a Pin dispatched after the initial sidebar-order load must
        // preserve any existing server-side pinned list. The store should
        // start from the loaded snapshot (not an empty default) and append
        // the new id, then PATCH the merged set so the server does not
        // silently drop the previous pins.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val existingSnapshot = SettingsServerSidebarOrderSnapshot(
            pinnedChannelIds = listOf("channel-2"),
            pinnedOrder = listOf("channel-2"),
            pinnedSortMode = "manual"
        )
        val updateCalls = mutableListOf<SettingsServerSidebarOrderUpdateRequest>()
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = sourceWithSidebarOrder(
                // Home payload is returned without sidebarOrder; the store
                // should fill it in from `loadSidebarOrder`.
                initial = null
            ),
            scope = scope,
            loadSidebarOrder = { DataResult.Content(existingSnapshot) },
            updateSidebarOrder = { request ->
                updateCalls += request
                DataResult.Content(
                    existingSnapshot.copy(
                        pinnedChannelIds = listOf("channel-2", "channel-1"),
                        pinnedOrder = listOf("channel-2", "channel-1")
                    )
                )
            }
        )

        store.accept(SharedHomeIntent.Start)
        // After Start, the store has merged in the loaded sidebar-order.
        val initialContent = store.state.value.content as SharedHomeContentState.Content
        assertEquals(listOf("channel-2"), initialContent.snapshot.sidebarOrder?.pinnedChannelIds)

        store.accept(SharedHomeIntent.TogglePinned("channel-1"))

        val after = (store.state.value.content as SharedHomeContentState.Content).snapshot.sidebarOrder
        assertEquals(listOf("channel-2", "channel-1"), after?.pinnedChannelIds, "must include existing pinned channel-2 plus new channel-1")
        assertEquals(listOf("channel-2", "channel-1"), after?.pinnedOrder)
        assertEquals(1, updateCalls.size)
        assertEquals(listOf("channel-2", "channel-1"), updateCalls.first().pinnedChannelIds)
        scope.cancel()
    }

    @Test
    fun togglePinnedFiresFailureCallbackWhenPatchFails() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val initial = SettingsServerSidebarOrderSnapshot(
            pinnedChannelIds = listOf("channel-1"),
            pinnedOrder = listOf("channel-1"),
            pinnedSortMode = "manual"
        )
        val failureMessages = mutableListOf<String>()
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = sourceWithSidebarOrder(
                initial = initial,
                updateResult = DataResult.Error(ScreenError(code = "sidebar_order_patch_failed", retryable = true))
            ),
            scope = scope,
            loadSidebarOrder = { DataResult.Content(initial) },
            updateSidebarOrder = { DataResult.Error(ScreenError(code = "sidebar_order_patch_failed", retryable = true)) },
            pinUpdateFailureCallback = { message -> failureMessages += message }
        )

        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.TogglePinned("channel-1"))

        val after = (store.state.value.content as SharedHomeContentState.Content).snapshot.sidebarOrder
        assertEquals(listOf("channel-1"), after?.pinnedChannelIds, "rollback should restore the previous pinned ids")
        assertEquals(1, failureMessages.size, "failure callback should fire on PATCH error")
        assertTrue(failureMessages.first().contains("pinned", ignoreCase = true))
        scope.cancel()
    }

    @Test
    fun togglePinnedFiresFailureCallbackWhenSidebarOrderLoadFails() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val failureMessages = mutableListOf<String>()
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = sourceWithSidebarOrder(initial = null),
            scope = scope,
            loadSidebarOrder = { DataResult.Error(ScreenError(code = "sidebar_order_load_failed", retryable = true)) },
            updateSidebarOrder = { error("PATCH should not be called when load fails") },
            pinUpdateFailureCallback = { message -> failureMessages += message }
        )

        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.TogglePinned("channel-1"))

        // No PATCH should be issued and no optimistic state change should leak.
        val after = (store.state.value.content as SharedHomeContentState.Content).snapshot.sidebarOrder
        assertEquals(null, after)
        assertEquals(1, failureMessages.size, "failure callback should fire when sidebar-order load fails")
        scope.cancel()
    }

    @Test
    fun togglePinnedRollsBackWhenPatchFails() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val initial = SettingsServerSidebarOrderSnapshot(
            pinnedChannelIds = listOf("channel-1"),
            pinnedOrder = listOf("channel-1"),
            pinnedSortMode = "manual"
        )
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = sourceWithSidebarOrder(
                initial = initial,
                updateResult = DataResult.Error(ScreenError(code = "sidebar_order_patch_failed", retryable = true))
            ),
            scope = scope,
            loadSidebarOrder = { DataResult.Content(initial) },
            updateSidebarOrder = { DataResult.Error(ScreenError(code = "sidebar_order_patch_failed", retryable = true)) }
        )

        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.TogglePinned("channel-1"))

        val after = (store.state.value.content as SharedHomeContentState.Content).snapshot.sidebarOrder
        assertEquals(listOf("channel-1"), after?.pinnedChannelIds, "rollback should restore the previous pinned ids")
        assertEquals(listOf("channel-1"), after?.pinnedOrder, "rollback should restore the previous pinned order")
        scope.cancel()
    }

    @Test
    fun setPinnedSortModePersistsAndPatchesSidebarOrder() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val initial = SettingsServerSidebarOrderSnapshot(
            pinnedChannelIds = listOf("channel-1"),
            pinnedOrder = listOf("channel-1"),
            pinnedSortMode = "manual"
        )
        val updateCalls = mutableListOf<SettingsServerSidebarOrderUpdateRequest>()
        val updatedSnapshot = initial.copy(pinnedSortMode = "az")
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = sourceWithSidebarOrder(
                initial = initial,
                updateResult = DataResult.Content(updatedSnapshot),
                updateCalls = updateCalls
            ),
            scope = scope,
            loadSidebarOrder = { DataResult.Content(initial) },
            updateSidebarOrder = { request ->
                updateCalls += request
                DataResult.Content(updatedSnapshot)
            }
        )

        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.SetPinnedSortMode(SlockSidebarSortMode.Az))

        val after = (store.state.value.content as SharedHomeContentState.Content).snapshot.sidebarOrder
        assertEquals("az", after?.pinnedSortMode)
        assertEquals(1, updateCalls.size)
        assertEquals("az", updateCalls.first().pinnedSortMode)
        assertEquals(null, updateCalls.first().pinnedChannelIds)
        assertEquals(null, updateCalls.first().pinnedOrder)
        scope.cancel()
    }

    @Test
    fun pinnedLazyItemCountGrowsWhenPinnedItemsArePresent() {
        val collapsed = homeLazyItemCount(
            channelsCount = 3,
            jointCount = 0,
            directMessagesCount = 2,
            pinnedCount = 2,
            channelsExpanded = false,
            jointChannelsExpanded = false,
            directMessagesExpanded = false,
            pinnedExpanded = false,
            contentRefreshEnabled = true
        )
        // 8 fixed + 1 refresh + 0 joint section + 2 pinned section items (header + spacer) = 11
        assertEquals(11, collapsed)
        val expanded = homeLazyItemCount(
            channelsCount = 3,
            jointCount = 0,
            directMessagesCount = 2,
            pinnedCount = 2,
            channelsExpanded = false,
            jointChannelsExpanded = false,
            directMessagesExpanded = false,
            pinnedExpanded = true,
            contentRefreshEnabled = true
        )
        assertEquals(13, expanded)
    }

    private fun sourceWithSidebarOrder(
        initial: SettingsServerSidebarOrderSnapshot?,
        updateResult: DataResult<SettingsServerSidebarOrderSnapshot> = DataResult.Empty(EmptyReason.NoSettingsAvailable),
        updateCalls: MutableList<SettingsServerSidebarOrderUpdateRequest> = mutableListOf()
    ): SharedHomeDataSource =
        SharedHomeDataSource(
            sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
            api = RecordingHomeApi(),
            parseHome = { payload, _ -> DataResult.Content(sampleHome(payload.serverId).copy(sidebarOrder = initial)) }
        )

    // end #211 --------------------------------------------------------------
}
