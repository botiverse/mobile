package build.raft.app.data

import build.raft.app.SlockSidebarSortMode
import build.raft.app.HomeConversationBadgeInput
import build.raft.app.homeLazyItemCount
import build.raft.app.homeConversationBadgeProjection
import build.raft.app.data.realtime.PerReceiverStateVersionStore
import build.raft.app.data.realtime.PerReceiverStateScope
import build.raft.app.model.ChannelActivityNotificationPreference
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.Heartbeat
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.PerReceiverNotificationPrefsChanged
import build.raft.app.model.PerReceiverPinnedChanged
import build.raft.app.model.PerReceiverReadStateChanged
import build.raft.app.model.PerReceiverReadStateScope
import build.raft.app.model.Presence
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import build.raft.app.network.sync.ReadStateV1Fact
import build.raft.app.network.sync.ReadStateV1ProductionCanary
import build.raft.app.network.sync.ReadStateV1ProductionOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.concurrent.Volatile

class SharedHomeStoreTest {
    @Test
    fun flagOffHomeFinalStateIsByteEquivalentToLegacyPath() = runBlocking {
        val legacyScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val canaryScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val legacyVersionStore = PerReceiverStateVersionStore(InMemorySlockKeyValueStore())
        val canaryVersionStore = PerReceiverStateVersionStore(InMemorySlockKeyValueStore())
        listOf(legacyVersionStore, canaryVersionStore).forEach { versionStore ->
            versionStore.writeReadStateCheckpoint(
                ServerId("server-1"),
                MemberId("user-1"),
                ChannelId("channel-1"),
                build.raft.app.data.realtime.PerReceiverReadStateCheckpoint(3, 7),
            )
        }
        val base = sampleHome(ServerId("server-1"))
        val carrier = base.copy(
            channels = base.channels.map { row ->
                if (row.channelId == ChannelId("channel-1")) {
                    row.copy(readStateV1Hydrate = ReadStateV1HydrateCarrier(9, 44))
                } else {
                    row
                }
            }
        )
        val legacy =
            SharedHomeStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                dataSource = sourceWithSnapshot(base),
                scope = legacyScope,
                perReceiverStateVersionStore = legacyVersionStore,
                readReceiptOwnerFactory = { serverId, accountId -> ReadReceiptOwner("production", accountId, serverId) },
                nowMillis = { 42L },
            )
        val flagOff =
            SharedHomeStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                dataSource = sourceWithSnapshot(carrier),
                scope = canaryScope,
                perReceiverStateVersionStore = canaryVersionStore,
                readReceiptOwnerFactory = { serverId, accountId -> ReadReceiptOwner("production", accountId, serverId) },
                readStateV1Canary =
                    ReadStateV1ProductionCanary(
                        isEnabledForServer = { false },
                        store = TestReadStateV1Store(),
                        onProjection = { _, _, _ -> },
                        onRebaseline = { _, _, _ -> },
                    ),
                nowMillis = { 42L },
            )

        legacy.accept(SharedHomeIntent.Start)
        flagOff.accept(SharedHomeIntent.Start)

        assertEquals(legacy.state.value, flagOff.state.value)
        legacyScope.cancel()
        canaryScope.cancel()
    }

    @Test
    fun flagOffHydrateCarrierNeverReachesFinalStateOrPersistedBytes() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val snapshot =
            sampleHome(ServerId("server-1")).copy(
                channels =
                    listOf(
                        channel("channel-1", "general").copy(
                            readStateV1Hydrate = ReadStateV1HydrateCarrier(7, 44),
                        )
                    )
            )
        val store =
            SharedHomeStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                dataSource = sourceWithSnapshot(snapshot),
                scope = scope,
                readReceiptOwnerFactory = { serverId, accountId -> ReadReceiptOwner("production", accountId, serverId) },
                readStateV1Canary =
                    ReadStateV1ProductionCanary(
                        isEnabledForServer = { false },
                        store = TestReadStateV1Store(),
                        onProjection = { _, _, _ -> },
                        onRebaseline = { _, _, _ -> },
                    ),
            )

        store.accept(SharedHomeIntent.Start)

        val finalSnapshot = (store.state.value.content as SharedHomeContentState.Content).snapshot
        assertEquals(null, finalSnapshot.channels.single().readStateV1Hydrate)
        assertFalse(Json.encodeToString(HomeSnapshot.serializer(), finalSnapshot).contains("readStateV1Hydrate"))
        scope.cancel()
    }

    @Test
    fun selectedHydrateCommitsBeforeExactFencedFirstHomeStateAndNeverFillsLegacyCheckpoint() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val versionStore = PerReceiverStateVersionStore(InMemorySlockKeyValueStore())
        versionStore.writeReadStateCheckpoint(
            ServerId("server-1"),
            MemberId("user-1"),
            ChannelId("channel-1"),
            build.raft.app.data.realtime.PerReceiverReadStateCheckpoint(99, 99),
        )
        val durable = TestReadStateV1Store()
        lateinit var store: SharedHomeStore
        var projectedAfterFreshContent = false
        val canary =
            ReadStateV1ProductionCanary(
                isEnabledForServer = { true },
                store = durable,
                onProjection = { projectedOwner, _, fact ->
                    projectedAfterFreshContent =
                        (store.state.value.content as? SharedHomeContentState.Content)
                            ?.snapshot
                            ?.channels
                            ?.single()
                            ?.readStateMaxReadSeq == fact.maxReadSeq
                    store.applyReadStateV1Projection(projectedOwner, fact)
                },
                onRebaseline = { _, _, _ -> },
            )
        val snapshot =
            sampleHome(ServerId("server-1")).copy(
                channels =
                    listOf(
                        channel("channel-1", "general").copy(
                            readStateV1Hydrate = ReadStateV1HydrateCarrier(1, 10),
                        )
                    )
            )
        store =
            SharedHomeStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                dataSource = sourceWithSnapshot(snapshot),
                scope = scope,
                perReceiverStateVersionStore = versionStore,
                readReceiptOwnerFactory = { serverId, accountId -> ReadReceiptOwner("production", accountId, serverId) },
                readStateV1Canary = canary,
            )

        store.accept(SharedHomeIntent.Start)

        val row = (store.state.value.content as SharedHomeContentState.Content).snapshot.channels.single()
        assertEquals(10, row.readStateMaxReadSeq)
        assertEquals(null, row.readStateV1Hydrate)
        assertTrue(projectedAfterFreshContent)
        assertEquals(ReadStateV1Fact("channel-1", 1, 10), durable.facts.values.single())
        assertEquals(
            build.raft.app.data.realtime.PerReceiverReadStateCheckpoint(99, 99),
            versionStore.readReadStateCheckpoint(ServerId("server-1"), MemberId("user-1"), ChannelId("channel-1")),
        )
        scope.cancel()
    }

    @Test
    fun selectedHydrateCommitFailureKeepsFirstHomeStateDark() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val versionStore = PerReceiverStateVersionStore(InMemorySlockKeyValueStore())
        versionStore.writeReadStateCheckpoint(
            ServerId("server-1"),
            MemberId("user-1"),
            ChannelId("channel-1"),
            build.raft.app.data.realtime.PerReceiverReadStateCheckpoint(99, 99),
        )
        val canary =
            ReadStateV1ProductionCanary(
                isEnabledForServer = { true },
                store = TestReadStateV1Store(failCommit = true),
                onProjection = { _, _, _ -> },
                onRebaseline = { _, _, _ -> },
            )
        val snapshot =
            sampleHome(ServerId("server-1")).copy(
                channels =
                    listOf(
                        channel("channel-1", "general").copy(
                            readStateV1Hydrate = ReadStateV1HydrateCarrier(1, 10),
                        )
                    )
            )
        val store =
            SharedHomeStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                dataSource = sourceWithSnapshot(snapshot),
                scope = scope,
                perReceiverStateVersionStore = versionStore,
                readReceiptOwnerFactory = { serverId, accountId -> ReadReceiptOwner("production", accountId, serverId) },
                readStateV1Canary = canary,
            )

        store.accept(SharedHomeIntent.Start)

        assertEquals(
            null,
            (store.state.value.content as SharedHomeContentState.Content).snapshot.channels.single().readStateMaxReadSeq,
        )
        scope.cancel()
    }

    @Test
    fun staleSelectedRefreshCannotRepublishStateIdentitySidebarOrBackgroundCacheAfterRevoke() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val owner = ReadStateV1ProductionOwner("production", "user-1", "server-1")
        val api = RecordingHomeApi()
        var networkSnapshot =
            sampleHome(ServerId("server-1")).copy(
                channels =
                    listOf(
                        channel("channel-1", "good-name").copy(
                            readStateV1Hydrate = ReadStateV1HydrateCarrier(3, 9),
                        )
                    ),
                serverUnreadCounts = mapOf("server-1" to 0, "server-2" to 3),
                sidebarOrder = SettingsServerSidebarOrderSnapshot(channelOrder = listOf("good-order")),
            )
        val dataSource =
            SharedHomeDataSource(
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                api = api,
                parseHome = { _, _ -> DataResult.Content(networkSnapshot) },
            )
        val canary =
            ReadStateV1ProductionCanary(
                isEnabledForServer = { true },
                store = TestReadStateV1Store(),
                onProjection = { _, _, _ -> },
                onRebaseline = { _, _, _ -> },
            )
        var revokeBeforePublication = false
        val store =
            SharedHomeStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                dataSource = dataSource,
                scope = scope,
                readReceiptOwnerFactory = { serverId, accountId ->
                    ReadReceiptOwner("production", accountId, serverId)
                },
                readStateV1Canary = canary,
                nowMillis = {
                    if (revokeBeforePublication) {
                        revokeBeforePublication = false
                        canary.revoke(owner)
                    }
                    42L
                },
            )

        store.accept(SharedHomeIntent.Start)
        val acceptedSnapshot = (store.state.value.content as SharedHomeContentState.Content).snapshot
        val acceptedIdentity = store.resolveProjection(ServerId("server-1"), "channel-1")
        val backgroundMessage =
            MessageNew(
                Message(
                    id = MessageId("background-101"),
                    threadId = ThreadId("channel-b"),
                    channelId = ChannelId("channel-b"),
                    seq = 101,
                    author = sampleMember().copy(id = MemberId("member-2"), displayName = "Cindy"),
                    timestampLabel = "12:01",
                    body = "background update",
                )
            )
        store.accept(SharedHomeIntent.ApplyBackgroundServerUnreadEvent("server-2", backgroundMessage))
        assertEquals(4, store.state.value.serverUnreadCounts["server-2"])

        networkSnapshot =
            networkSnapshot.copy(
                channels =
                    listOf(
                        channel("channel-1", "stale-name").copy(
                            readStateV1Hydrate = ReadStateV1HydrateCarrier(2, 99),
                        )
                    ),
                sidebarOrder = SettingsServerSidebarOrderSnapshot(channelOrder = listOf("stale-order")),
            )
        revokeBeforePublication = true
        store.accept(SharedHomeIntent.ForegroundRefresh)

        assertEquals(acceptedSnapshot, (store.state.value.content as SharedHomeContentState.Content).snapshot)
        assertEquals(acceptedIdentity, store.resolveProjection(ServerId("server-1"), "channel-1"))
        assertEquals(4, store.state.value.serverUnreadCounts["server-2"])

        networkSnapshot =
            networkSnapshot.copy(
                channels =
                    listOf(
                        channel("channel-1", "good-name").copy(
                            readStateV1Hydrate = ReadStateV1HydrateCarrier(3, 9),
                        )
                    ),
                sidebarOrder = null,
            )
        store.accept(SharedHomeIntent.ForegroundRefresh)

        val recovered = (store.state.value.content as SharedHomeContentState.Content).snapshot
        assertEquals(listOf("good-order"), recovered.sidebarOrder?.channelOrder)
        assertEquals("good-name", store.resolveProjection(ServerId("server-1"), "channel-1")?.title)
        scope.cancel()
    }

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
    fun cachedStoreRefreshesRuntimeScopeBeforeDispatch() = runBlocking {
        val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        // task #1131: the store keeps logging from its Unconfined coroutines on whatever
        // thread resumed them, so a plain MutableList appended by the logger races the
        // assertions below (ConcurrentModificationException while iterating). Record
        // through an unbounded Channel -- trySend is thread-safe and non-suspending on
        // every target -- and drain a snapshot on the test thread once the awaited state
        // has been observed. Both asserted entries are emitted before that state
        // (`home_runtime_integrations_updated` synchronously, `home_dispatch_launch_load`
        // before the load runs), so the verdict is unchanged.
        val originalLogs = Channel<String>(Channel.UNLIMITED)
        val refreshedLogs = Channel<String>(Channel.UNLIMITED)
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = sourceWithSnapshot(sampleHome(ServerId("server-old"))),
            scope = storeScope,
            diagnosticsLogger = { originalLogs.trySend(it) }
        )

        store.updateRuntimeIntegrations(
            dataSource = sourceWithSnapshot(sampleHome(ServerId("server-new"))),
            diagnosticsLogger = { refreshedLogs.trySend(it) }
        )
        store.dispatch(SharedHomeIntent.Start)
        // #471: await the launched load deterministically instead of busy-spinning
        // with yield() against a fixed 1s budget. dispatch() fires the load on a
        // separate Unconfined scope; the old `while (…) yield()` poll raced that
        // cross-scope completion and timed out under CI load (rerun-to-green flake).
        // StateFlow.first { … } suspends until the *server-new* Content is emitted —
        // waiting on the specific expected state (not merely any Content) is immune to
        // any transient session/cached Content the store may surface before the network
        // load resolves. The timeout is only a hang safety-net, not the polling mechanism.
        withTimeout(2_000) {
            store.state.first {
                val content = it.content
                content is SharedHomeContentState.Content &&
                    content.snapshot.server.id.value == "server-new"
            }
        }

        val content = store.state.value.content
        assertTrue(content is SharedHomeContentState.Content)
        assertEquals("server-new", content.snapshot.server.id.value)
        // Stop the store before draining so late diagnostics cannot make the snapshot
        // drift with timing; everything asserted below was recorded before the awaited state.
        storeScope.cancel()
        val refreshed = refreshedLogs.drainRecorded()
        val original = originalLogs.drainRecorded()
        assertContains(refreshed, "home_runtime_integrations_updated")
        assertTrue(refreshed.any { it.startsWith("home_dispatch_launch_load intent=hydrate:start") })
        assertEquals(emptyList(), original)
    }

    /** Snapshot every entry recorded so far without suspending; see task #1131. */
    private fun Channel<String>.drainRecorded(): List<String> =
        buildList {
            while (true) {
                val next = tryReceive().getOrNull() ?: break
                add(next)
            }
        }

    @Test
    fun startHydratesConversationIdentityProviderFromHomeSnapshot() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource =
                source(
                    api = RecordingHomeApi(),
                    sessionStore = InMemorySlockAuthSessionStore(sampleSession())
                ),
            scope = scope
        )

        store.accept(SharedHomeIntent.Start)

        val projections =
            store.projectionFlow(
                ServerId("server-1"),
                listOf("channel-1")
            ).first()
        assertEquals("general", projections["channel-1"]?.title)
        scope.cancel()
    }

    @Test
    fun channelProfileUpdatePatchesHomeAndConversationDirectoryWithoutDroppingRowState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = source(api = RecordingHomeApi()),
            scope = scope
        )
        store.accept(SharedHomeIntent.Start)
        val before = store.snapshotFor(ServerId("server-1"))!!.channels.single { it.channelId == ChannelId("channel-1") }

        store.accept(
            SharedHomeIntent.ChannelProfileUpdated(
                channelId = ChannelId("channel-1"),
                name = "renamed",
                description = "Updated description"
            )
        )

        val after = store.snapshotFor(ServerId("server-1"))!!.channels.single { it.channelId == ChannelId("channel-1") }
        assertEquals("renamed", after.name)
        assertEquals("Updated description", after.subtitle)
        assertEquals(before.unreadCount, after.unreadCount)
        assertEquals(before.lastMessageId, after.lastMessageId)
        assertEquals("renamed", store.resolveProjection(ServerId("server-1"), "channel-1")?.title)
        assertEquals("Updated description", store.resolveProjection(ServerId("server-1"), "channel-1")?.subtitle)
        scope.cancel()
    }

    @Test
    fun activitySnapshotSupplementsMissingParentIdentityWithoutOverwritingHome() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = source(api = RecordingHomeApi()),
            scope = scope
        )
        store.accept(SharedHomeIntent.Start)

        store.supplementConversationIdentity(
            InboxSnapshot(
                server = ServerSummary(ServerId("server-1"), "Raft"),
                items =
                    listOf(
                        InboxItem(
                            key = "thread:reply-stream",
                            kind = InboxItemKind.Thread,
                            channelId = ChannelId("reply-stream"),
                            channelName = "Thread",
                            threadChannelId = ChannelId("reply-stream"),
                            parentChannelId = ChannelId("activity-parent"),
                            parentChannelName = "activity-parent-name",
                            parentChannelKind = ConversationKind.Channel,
                            parentMessageId = MessageId("parent-message")
                        ),
                        InboxItem(
                            key = "thread:home-parent-reply",
                            kind = InboxItemKind.Thread,
                            channelId = ChannelId("home-parent-reply"),
                            channelName = "Thread",
                            threadChannelId = ChannelId("home-parent-reply"),
                            parentChannelId = ChannelId("channel-1"),
                            parentChannelName = "stale-general",
                            parentChannelKind = ConversationKind.Channel,
                            parentMessageId = MessageId("home-parent-message")
                        )
                    )
            )
        )

        assertEquals(
            "activity-parent-name",
            store.resolveProjection(ServerId("server-1"), "activity-parent")?.title
        )
        assertEquals(
            "general",
            store.resolveProjection(ServerId("server-1"), "channel-1")?.title,
            "Home remains authoritative when both Home and Activity project the same parent"
        )
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
    fun shellScopedHomeStoresKeepRuntimeServerSelectionsIndependent() = runBlocking {
        val processStore = InMemorySlockAuthSessionStore(sampleSession())
        val runtimeA = SelectedServerStore(SlockShellStoreScope.create("shell-a", "server-1"))
        val runtimeB = SelectedServerStore(SlockShellStoreScope.create("shell-b", "server-2"))
        val authA = SlockShellAuthSessionStore(processStore, runtimeA)
        val authB = SlockShellAuthSessionStore(processStore, runtimeB)
        val scopeA = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val scopeB = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val storeA =
            SharedHomeStore(
                authStore = authA,
                dataSource = source(api = RecordingHomeApi(), sessionStore = authA),
                scope = scopeA
            )
        val storeB =
            SharedHomeStore(
                authStore = authB,
                dataSource = source(api = RecordingHomeApi(), sessionStore = authB),
                scope = scopeB
            )

        storeA.accept(SharedHomeIntent.Start)
        storeB.accept(SharedHomeIntent.Start)

        assertEquals("server-1", (storeA.state.value.content as SharedHomeContentState.Content).snapshot.server.id.value)
        assertEquals("server-2", (storeB.state.value.content as SharedHomeContentState.Content).snapshot.server.id.value)

        storeA.accept(SharedHomeIntent.SelectServer("server-2"))
        storeB.accept(SharedHomeIntent.SelectServer("server-1"))

        assertEquals("server-2", (storeA.state.value.content as SharedHomeContentState.Content).snapshot.server.id.value)
        assertEquals("server-1", (storeB.state.value.content as SharedHomeContentState.Content).snapshot.server.id.value)
        assertEquals("server-2", runtimeA.currentServerId.value)
        assertEquals("server-1", runtimeB.currentServerId.value)
        assertEquals("server-1", processStore.readSession()?.currentServerId)
        scopeA.cancel()
        scopeB.cancel()
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
    fun selectServerRestoresCachedServerContentWhileRefreshIsPending() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sessionStore = InMemorySlockAuthSessionStore(sampleSession())
        val api = BlockingHomeApi()
        val store = SharedHomeStore(
            authStore = sessionStore,
            dataSource = source(api = api, sessionStore = sessionStore),
            scope = scope
        )
        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.SelectServer("server-2"))
        val server2Content = store.state.value.content
        assertTrue(server2Content is SharedHomeContentState.Content)
        assertEquals("server-2", server2Content.snapshot.server.id.value)

        val blockedSwitchBack = api.blockNextChannels()
        val switchBackJob = launch { store.accept(SharedHomeIntent.SelectServer("server-1")) }
        blockedSwitchBack.awaitStarted()

        val switchingContent = store.state.value.content
        assertTrue(switchingContent is SharedHomeContentState.Content)
        assertEquals("server-1", switchingContent.snapshot.server.id.value)
        assertEquals("general", switchingContent.snapshot.channels.single().name)
        assertEquals(true, switchingContent.contentRefreshing)
        assertEquals("server-1", store.state.value.serverSwitchState.pendingServerId)

        blockedSwitchBack.release()
        switchBackJob.join()

        val finalContent = store.state.value.content
        assertTrue(finalContent is SharedHomeContentState.Content)
        assertEquals("server-1", finalContent.snapshot.server.id.value)
        assertEquals(false, finalContent.contentRefreshing)
        assertEquals(null, store.state.value.serverSwitchState.pendingServerId)
        scope.cancel()
    }

    @Test
    fun openingServerDrawerSeedsEveryServerAndExpandsTheSelectedServer() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sessionStore = InMemorySlockAuthSessionStore(sampleSession())
        val store =
            SharedHomeStore(
                authStore = sessionStore,
                dataSource = source(api = RecordingHomeApi(), sessionStore = sessionStore),
                scope = scope
            )

        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.ToggleServerSwitcher)

        val state = store.state.value
        assertEquals(true, state.serverDrawer.expanded)
        assertEquals(setOf(ServerId("server-1")), state.serverDrawer.expandedServerIds)
        assertEquals(setOf(ServerId("server-1"), ServerId("server-2")), state.serverDrawer.sections.keys)
        assertEquals(
            HomeServerDrawerLoadStatus.Loaded,
            state.serverDrawer.sections.getValue(ServerId("server-1")).status
        )
        assertEquals(
            "server-1",
            state.serverDrawer.sections.getValue(ServerId("server-1")).content?.snapshot?.server?.id?.value
        )
        assertEquals(
            HomeServerDrawerLoadStatus.Unloaded,
            state.serverDrawer.sections.getValue(ServerId("server-2")).status
        )
        assertEquals("server-1", (state.content as SharedHomeContentState.Content).snapshot.server.id.value)
        scope.cancel()
    }

    @Test
    fun expandingNonSelectedServerLoadsItsSectionWithoutChangingSelectedHome() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sessionStore = InMemorySlockAuthSessionStore(sampleSession())
        val api = RecordingHomeApi()
        val sidebarRequests = mutableListOf<String>()
        val store =
            SharedHomeStore(
                authStore = sessionStore,
                dataSource = source(api = api, sessionStore = sessionStore),
                scope = scope,
                loadSidebarOrder = { serverId ->
                    sidebarRequests += serverId.value
                    DataResult.Content(SettingsServerSidebarOrderSnapshot(channelOrder = listOf("channel-${serverId.value}")))
                }
            )

        store.accept(SharedHomeIntent.Start)
        sidebarRequests.clear()
        store.accept(SharedHomeIntent.ToggleServerSwitcher)
        val blockedServerLoad = api.blockNextChannels()
        store.accept(SharedHomeIntent.ToggleServerDrawerSection(ServerId("server-2")))
        blockedServerLoad.awaitStarted()

        val loading = store.state.value
        assertEquals("server-1", (loading.content as SharedHomeContentState.Content).snapshot.server.id.value)
        assertEquals("server-1", sessionStore.readSession()?.currentServerId)
        assertEquals(
            HomeServerDrawerLoadStatus.Loading,
            loading.serverDrawer.sections.getValue(ServerId("server-2")).status
        )
        assertEquals(setOf(ServerId("server-2")), loading.serverDrawer.expandedServerIds)

        blockedServerLoad.release()
        awaitCondition {
            store.state.value.serverDrawer.sections[ServerId("server-2")]?.status ==
                HomeServerDrawerLoadStatus.Loaded
        }

        val loaded = store.state.value
        val server2Section = loaded.serverDrawer.sections.getValue(ServerId("server-2"))
        assertEquals("server-2", server2Section.content?.snapshot?.server?.id?.value)
        assertEquals(listOf("channel-server-2"), server2Section.content?.snapshot?.sidebarOrder?.channelOrder)
        assertEquals(listOf("server-2"), sidebarRequests)
        assertEquals("server-1", (loaded.content as SharedHomeContentState.Content).snapshot.server.id.value)
        assertEquals("server-1", sessionStore.readSession()?.currentServerId)
        scope.cancel()
    }

    @Test
    fun foregroundServerSelectionSupersedesOlderDrawerPublication() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sessionStore = InMemorySlockAuthSessionStore(sampleSession())
        val api = RecordingHomeApi()
        var server2SidebarLoads = 0
        val store =
            SharedHomeStore(
                authStore = sessionStore,
                dataSource = source(api = api, sessionStore = sessionStore),
                scope = scope,
                loadSidebarOrder = { serverId ->
                    val marker =
                        if (serverId == ServerId("server-2")) {
                            server2SidebarLoads += 1
                            if (server2SidebarLoads == 1) "foreground-fresh" else "drawer-stale"
                        } else {
                            "server-1"
                        }
                    DataResult.Content(SettingsServerSidebarOrderSnapshot(channelOrder = listOf(marker)))
                }
            )

        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.ToggleServerSwitcher)
        val olderDrawerLoad = api.blockNextChannels("server-2")
        store.accept(SharedHomeIntent.ToggleServerDrawerSection(ServerId("server-2")))
        olderDrawerLoad.awaitStarted()

        store.accept(SharedHomeIntent.SelectServer("server-2"))
        val foreground = store.state.value.content as SharedHomeContentState.Content
        assertEquals("server-2", foreground.snapshot.server.id.value)
        assertEquals(listOf("foreground-fresh"), foreground.snapshot.sidebarOrder?.channelOrder)

        olderDrawerLoad.release()
        awaitCondition { server2SidebarLoads == 2 }

        store.accept(SharedHomeIntent.SelectServer("server-1"))
        val reopenRefresh = api.blockNextChannels("server-2")
        store.accept(SharedHomeIntent.ToggleServerSwitcher)
        store.accept(SharedHomeIntent.ToggleServerDrawerSection(ServerId("server-2")))
        withTimeout(1_000L) { reopenRefresh.awaitStarted() }

        val cachedServer2 = store.state.value.serverDrawer.sections.getValue(ServerId("server-2"))
        assertEquals(HomeServerDrawerLoadStatus.Refreshing, cachedServer2.status)
        assertEquals(
            listOf("foreground-fresh"),
            cachedServer2.content?.snapshot?.sidebarOrder?.channelOrder
        )
        reopenRefresh.release()
        scope.cancel()
    }

    @Test
    fun reopeningDrawerExpandsOnlySelectedServerAndKeepsOtherCache() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sessionStore = InMemorySlockAuthSessionStore(sampleSession())
        val api = RecordingHomeApi()
        val store =
            SharedHomeStore(
                authStore = sessionStore,
                dataSource = source(api = api, sessionStore = sessionStore),
                scope = scope
            )

        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.ToggleServerSwitcher)
        store.accept(SharedHomeIntent.ToggleServerDrawerSection(ServerId("server-2")))
        awaitCondition {
            store.state.value.serverDrawer.sections[ServerId("server-2")]?.status ==
                HomeServerDrawerLoadStatus.Loaded
        }
        val cachedBeforeClose =
            store.state.value.serverDrawer.sections.getValue(ServerId("server-2")).content
        assertTrue(cachedBeforeClose != null)

        store.accept(SharedHomeIntent.ToggleServerSwitcher)
        store.accept(SharedHomeIntent.ToggleServerSwitcher)

        val reopened = store.state.value.serverDrawer
        val cached = reopened.sections.getValue(ServerId("server-2"))
        assertEquals(setOf(ServerId("server-1")), reopened.expandedServerIds)
        assertEquals(HomeServerDrawerLoadStatus.Cached, cached.status)
        assertEquals(cachedBeforeClose, cached.content)
        scope.cancel()
    }

    @Test
    fun switchingExpandedServerKeepsOnlyOneExpandedWhileLoadsRemainGenerationSafe() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val baseSession = sampleSession()
        val session =
            baseSession.copy(
                servers =
                    baseSession.servers +
                        StoredAuthServer(id = "server-3", name = "Third", slug = "third")
            )
        val sessionStore = InMemorySlockAuthSessionStore(session)
        val api = RecordingHomeApi()
        val store =
            SharedHomeStore(
                authStore = sessionStore,
                dataSource = source(api = api, sessionStore = sessionStore),
                scope = scope
            )

        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.ToggleServerSwitcher)
        val server2Block = api.blockNextChannels("server-2")
        val server3Block = api.blockNextChannels("server-3")
        store.accept(SharedHomeIntent.ToggleServerDrawerSection(ServerId("server-2")))
        store.accept(SharedHomeIntent.ToggleServerDrawerSection(ServerId("server-3")))
        server2Block.awaitStarted()
        server3Block.awaitStarted()

        server3Block.release()
        awaitCondition {
            store.state.value.serverDrawer.sections[ServerId("server-3")]?.status ==
                HomeServerDrawerLoadStatus.Loaded
        }
        assertEquals(
            HomeServerDrawerLoadStatus.Loading,
            store.state.value.serverDrawer.sections.getValue(ServerId("server-2")).status
        )
        assertEquals(
            "server-1",
            (store.state.value.content as SharedHomeContentState.Content).snapshot.server.id.value
        )

        server2Block.release()
        awaitCondition {
            store.state.value.serverDrawer.sections[ServerId("server-2")]?.status ==
                HomeServerDrawerLoadStatus.Loaded
        }
        assertEquals(
            setOf(ServerId("server-3")),
            store.state.value.serverDrawer.expandedServerIds
        )
        assertEquals(
            "server-2",
            store.state.value.serverDrawer.sections.getValue(ServerId("server-2")).content?.snapshot?.server?.id?.value
        )
        assertEquals(
            "server-3",
            store.state.value.serverDrawer.sections.getValue(ServerId("server-3")).content?.snapshot?.server?.id?.value
        )
        scope.cancel()
    }

    @Test
    fun retryingServerDrawerSectionRotatesGenerationWithoutChangingSelection() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sessionStore = InMemorySlockAuthSessionStore(sampleSession())
        val api = RecordingHomeApi()
        val store =
            SharedHomeStore(
                authStore = sessionStore,
                dataSource = source(api = api, sessionStore = sessionStore),
                scope = scope
            )

        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.ToggleServerSwitcher)
        val firstLoad = api.blockNextChannels("server-2")
        store.accept(SharedHomeIntent.ToggleServerDrawerSection(ServerId("server-2")))
        firstLoad.awaitStarted()
        val firstGeneration =
            store.state.value.serverDrawer.sections.getValue(ServerId("server-2")).requestGeneration

        store.accept(SharedHomeIntent.RetryServerDrawerSection(ServerId("server-2")))
        awaitCondition {
            store.state.value.serverDrawer.sections[ServerId("server-2")]?.status ==
                HomeServerDrawerLoadStatus.Loaded
        }

        val section = store.state.value.serverDrawer.sections.getValue(ServerId("server-2"))
        assertEquals(firstGeneration + 1L, section.requestGeneration)
        assertEquals("server-2", section.content?.snapshot?.server?.id?.value)
        assertEquals(
            "server-1",
            (store.state.value.content as SharedHomeContentState.Content).snapshot.server.id.value
        )
        assertEquals("server-1", sessionStore.readSession()?.currentServerId)
        firstLoad.release()
        scope.cancel()
    }

    @Test
    fun serverDrawerLoadDropsSectionWhenServerIsRemovedFromSession() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val initialSession = sampleSession()
        val sessionStore = InMemorySlockAuthSessionStore(initialSession)
        val api = RecordingHomeApi()
        val store =
            SharedHomeStore(
                authStore = sessionStore,
                dataSource = source(api = api, sessionStore = sessionStore),
                scope = scope
            )

        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.ToggleServerSwitcher)
        val blockedLoad = api.blockNextChannels("server-2")
        store.accept(SharedHomeIntent.ToggleServerDrawerSection(ServerId("server-2")))
        blockedLoad.awaitStarted()

        sessionStore.saveSession(
            initialSession.copy(servers = initialSession.servers.filter { it.id == "server-1" })
        )
        blockedLoad.release()
        awaitCondition {
            ServerId("server-2") !in store.state.value.serverDrawer.sections
        }

        assertEquals(setOf(ServerId("server-1")), store.state.value.serverDrawer.expandedServerIds)
        assertEquals(
            "server-1",
            (store.state.value.content as SharedHomeContentState.Content).snapshot.server.id.value
        )
        assertEquals("server-1", sessionStore.readSession()?.currentServerId)
        scope.cancel()
    }

    @Test
    fun failedServerDrawerSectionCanRetryWithoutChangingSelection() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sessionStore = InMemorySlockAuthSessionStore(sampleSession())
        val api = SwitchableHomeApi().apply { setServerUnread("server-1" to 0) }
        val store =
            SharedHomeStore(
                authStore = sessionStore,
                dataSource = source(api = api, sessionStore = sessionStore),
                scope = scope
            )

        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.ToggleServerSwitcher)
        api.setFailing(true)
        store.accept(SharedHomeIntent.ToggleServerDrawerSection(ServerId("server-2")))
        awaitCondition {
            store.state.value.serverDrawer.sections[ServerId("server-2")]?.status ==
                HomeServerDrawerLoadStatus.ErrorEmpty
        }

        assertEquals(
            "server-1",
            (store.state.value.content as SharedHomeContentState.Content).snapshot.server.id.value
        )
        api.setServerUnread("server-2" to 0)
        store.accept(SharedHomeIntent.RetryServerDrawerSection(ServerId("server-2")))
        awaitCondition {
            store.state.value.serverDrawer.sections[ServerId("server-2")]?.status ==
                HomeServerDrawerLoadStatus.Loaded
        }

        assertEquals(
            "server-2",
            store.state.value.serverDrawer.sections.getValue(ServerId("server-2")).content?.snapshot?.server?.id?.value
        )
        assertEquals("server-1", sessionStore.readSession()?.currentServerId)
        scope.cancel()
    }

    @Test
    fun noPermissionServerDrawerSectionBecomesUnavailableAndDoesNotRetry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sessionStore = InMemorySlockAuthSessionStore(sampleSession())
        val api = SwitchableHomeApi().apply { setServerUnread("server-1" to 0) }
        val store =
            SharedHomeStore(
                authStore = sessionStore,
                dataSource = source(api = api, sessionStore = sessionStore),
                scope = scope
            )

        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.ToggleServerSwitcher)
        api.setPermissionDenied(true)
        store.accept(SharedHomeIntent.ToggleServerDrawerSection(ServerId("server-2")))
        awaitCondition {
            store.state.value.serverDrawer.sections[ServerId("server-2")]?.status ==
                HomeServerDrawerLoadStatus.Unavailable
        }
        val callsAtTerminal = api.callCount

        store.accept(SharedHomeIntent.RetryServerDrawerSection(ServerId("server-2")))

        assertEquals(callsAtTerminal, api.callCount)
        assertEquals(
            HomeServerDrawerLoadStatus.Unavailable,
            store.state.value.serverDrawer.sections.getValue(ServerId("server-2")).status
        )
        assertEquals("server-1", sessionStore.readSession()?.currentServerId)
        scope.cancel()
    }

    @Test
    fun startHydratesPrunedHomeIdentitySnapshotWhileNetworkLoadIsPending() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val storage = InMemorySlockKeyValueStore()
        val staleSnapshot =
            sampleHome(ServerId("server-1")).copy(
                channels =
                    listOf(
                        channel("channel-1", "cached-general").copy(
                            unreadCount = 9,
                            unreadCountKnown = true,
                            mentionCount = 2,
                            mentionCountKnown = true,
                            lastMessageId = MessageId("message-1"),
                            firstUnreadMessageId = MessageId("message-1"),
                            lastMessagePreview = "cached preview",
                            lastMessageAt = "2026-07-01T00:00:00Z",
                            lastMessageSenderName = "Cached",
                            hasDraft = true,
                            hasDraftKnown = true,
                            activityNotificationPreference =
                                ChannelActivityNotificationPreference(
                                    activityMuted = true,
                                    muteFromSeq = 9_223_372_036_854_775_000L,
                                    activityMuteSupported = true
                                )
                        )
                    ),
                directMessages =
                    listOf(
                        directMessage("dm-1", "Cached DM", "member-1", Presence.Active).copy(
                            activityNotificationPreference =
                                ChannelActivityNotificationPreference(
                                    activityMuted = true,
                                    muteFromSeq = 9_223_372_036_854_775_001L,
                                    activityMuteSupported = true
                                )
                        )
                    ),
                sidebarOrder =
                    SettingsServerSidebarOrderSnapshot(
                        pinnedChannelIds = listOf("channel-1"),
                        pinnedOrder = listOf("channel-1"),
                        pinnedSortMode = "manual"
                    ),
                unreadTotal = 9,
                unreadSource = AppBadgeSource.SERVER_UNREAD_SUMMARY,
                serverUnreadCounts = mapOf("server-1" to 9)
            )
        val persistSlice =
            createSharedHomeIdentityPersistSlice(
                storage = SharedPreferencePersistStorage(storage)
            )
        val warmStore =
            SharedHomeStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                dataSource = sourceWithSnapshot(staleSnapshot),
                scope = scope,
                persistSlices = listOf(persistSlice)
            )
        warmStore.accept(SharedHomeIntent.Start)

        val sessionStore = InMemorySlockAuthSessionStore(sampleSession())
        val api = BlockingHomeApi()
        val coldStore =
            SharedHomeStore(
                authStore = sessionStore,
                dataSource = source(api = api, sessionStore = sessionStore),
                scope = scope,
                persistSlices =
                    listOf(
                        createSharedHomeIdentityPersistSlice(
                            storage = SharedPreferencePersistStorage(storage)
                        )
                    )
            )
        val blockedLoad = api.blockNextChannels()
        val loadJob = launch { coldStore.accept(SharedHomeIntent.Start) }
        blockedLoad.awaitStarted()

        val hydratedContent = coldStore.state.value.content
        assertTrue(hydratedContent is SharedHomeContentState.Content)
        assertEquals("cached-general", hydratedContent.snapshot.channels.single().name)
        assertEquals(true, hydratedContent.contentRefreshing)
        assertEquals(0, hydratedContent.snapshot.unreadTotal)
        assertEquals(0, hydratedContent.snapshot.channels.single().unreadCount)
        assertEquals(false, hydratedContent.snapshot.channels.single().hasDraft)
        assertEquals(null, hydratedContent.snapshot.channels.single().lastMessagePreview)
        assertEquals(
            ChannelActivityNotificationPreference.Unsupported,
            hydratedContent.snapshot.channels.single().activityNotificationPreference
        )
        assertEquals(
            ChannelActivityNotificationPreference.Unsupported,
            hydratedContent.snapshot.directMessages.single().activityNotificationPreference
        )
        assertEquals(emptyMap(), hydratedContent.snapshot.serverUnreadCounts)
        assertEquals(listOf("channel-1"), hydratedContent.snapshot.sidebarOrder?.pinnedChannelIds)
        assertEquals(listOf("channel-1"), hydratedContent.snapshot.sidebarOrder?.pinnedOrder)

        blockedLoad.release()
        loadJob.join()

        val freshContent = coldStore.state.value.content
        assertTrue(freshContent is SharedHomeContentState.Content)
        assertEquals("general", freshContent.snapshot.channels.single().name)
        assertEquals(false, freshContent.contentRefreshing)
        assertEquals(
            listOf("channel-1"),
            freshContent.snapshot.sidebarOrder?.pinnedChannelIds,
            "network home payload without sidebarOrder should retain the hydrated sidebar-order cache"
        )
        scope.cancel()
    }

    @Test
    fun coldStartShowsLastKnownUnreadAndPreviewHintsUntilNetworkHomeArrives() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val storage = InMemorySlockKeyValueStore()
        val lastSession =
            sampleHome(ServerId("server-1")).copy(
                channels =
                    listOf(
                        channel("channel-1", "general").copy(
                            unreadCount = 9,
                            unreadCountKnown = true,
                            lastMessagePreview = "ship it",
                            lastMessageAt = "2026-10-12T08:00:00Z",
                            lastMessageSenderName = "Ada"
                        ),
                        channel("channel-2", "random").copy(unreadCountKnown = true)
                    ),
                unreadTotal = 9,
                unreadSource = AppBadgeSource.SERVER_UNREAD_SUMMARY
            )
        fun slices() =
            listOf(
                createSharedHomeIdentityPersistSlice(storage = SharedPreferencePersistStorage(storage)),
                createSharedHomeRowHintPersistSlice(storage = HomeRowHintPersistStorage(storage))
            )
        SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = sourceWithSnapshot(lastSession),
            scope = scope,
            persistSlices = slices()
        ).accept(SharedHomeIntent.Start)

        val sessionStore = InMemorySlockAuthSessionStore(sampleSession())
        val api = BlockingHomeApi()
        val coldStore =
            SharedHomeStore(
                authStore = sessionStore,
                dataSource = source(api = api, sessionStore = sessionStore),
                scope = scope,
                persistSlices = slices()
            )
        val blockedLoad = api.blockNextChannels()
        val loadJob = launch { coldStore.accept(SharedHomeIntent.Start) }
        blockedLoad.awaitStarted()

        val cached = coldStore.state.value.content
        assertTrue(cached is SharedHomeContentState.Content)
        assertEquals(
            mapOf(
                "channel-1" to
                    HomeRowRestoredHint(
                        unreadCount = 9,
                        lastMessagePreview = "ship it",
                        lastMessageAt = "2026-10-12T08:00:00Z",
                        lastMessageSenderName = "Ada"
                    )
            ),
            cached.displayRowHints(),
            "only rows with something to show are restored"
        )
        // The rows themselves stay unknown, so the badge sum, realtime increments and sidebar
        // mark-read cannot act on the restored number.
        assertEquals(0, cached.snapshot.channels.first().unreadCount)
        assertEquals(false, cached.snapshot.channels.first().unreadCountKnown)
        assertEquals(0, coldStore.state.value.appBadgeCount.count)

        blockedLoad.release()
        loadJob.join()

        val fresh = coldStore.state.value.content
        assertTrue(fresh is SharedHomeContentState.Content)
        assertEquals(emptyMap(), fresh.displayRowHints(), "network Home replaces the restored hints")
        scope.cancel()
    }

    @Test
    fun rowHintsAreRestoredOnlyForTheAccountThatWroteThem() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val storage = InMemorySlockKeyValueStore()
        val home = sampleHome(ServerId("server-1")).copy(channels = listOf(channel("channel-1", "general")))
        SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = sourceWithSnapshot(home),
            scope = scope,
            persistSlices =
                listOf(createSharedHomeIdentityPersistSlice(storage = SharedPreferencePersistStorage(storage)))
        ).accept(SharedHomeIntent.Start)

        suspend fun restoredHintsWrittenBy(memberId: String): Map<String, HomeRowRestoredHint> {
            storage.putString(
                storage.hintKeyFor(sampleSession().user.id, "server-1"),
                """{"version":1,"snapshot":{"serverId":"server-1","memberId":"$memberId",""" +
                    """"hints":{"channel-1":{"unreadCount":4,"lastMessagePreview":"hello"}}}}"""
            )
            val coldStore =
                SharedHomeStore(
                    authStore = InMemorySlockAuthSessionStore(sampleSession()),
                    dataSource = sourceWithSnapshot(home),
                    scope = scope,
                    persistSlices =
                        listOf(
                            createSharedHomeIdentityPersistSlice(storage = SharedPreferencePersistStorage(storage)),
                            createSharedHomeRowHintPersistSlice(storage = HomeRowHintPersistStorage(storage))
                        )
                )
            assertTrue(coldStore.hydrateCachedHome(ServerId("server-1")))
            val cached = coldStore.state.value.content
            assertTrue(cached is SharedHomeContentState.Content)
            return cached.displayRowHints()
        }

        assertEquals(
            mapOf("channel-1" to HomeRowRestoredHint(unreadCount = 4, lastMessagePreview = "hello")),
            restoredHintsWrittenBy(home.currentMember.id.value)
        )
        assertEquals(emptyMap(), restoredHintsWrittenBy("someone-else"))
        scope.cancel()
    }

    private fun InMemorySlockKeyValueStore.hintKeyFor(userId: String, serverId: String): String =
        "mvi:persist:home:hints:v1:user:$userId:server:$serverId"

    @Test
    fun hydrateCachedHomeDoesNotStartNetworkLoad() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val storage = InMemorySlockKeyValueStore()
        val persistSlice =
            createSharedHomeIdentityPersistSlice(
                storage = SharedPreferencePersistStorage(storage)
            )
        val warmStore =
            SharedHomeStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                dataSource = sourceWithSnapshot(sampleHome(ServerId("server-1"))),
                scope = scope,
                persistSlices = listOf(persistSlice)
            )
        warmStore.accept(SharedHomeIntent.Start)

        val api = RecordingHomeApi()
        val coldStore =
            SharedHomeStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                dataSource = source(api = api),
                scope = scope,
                persistSlices =
                    listOf(
                        createSharedHomeIdentityPersistSlice(
                            storage = SharedPreferencePersistStorage(storage)
                        )
                    )
            )

        assertTrue(coldStore.hydrateCachedHome(ServerId("server-1")))
        val content = coldStore.state.value.content
        assertTrue(content is SharedHomeContentState.Content)
        assertTrue(content.isDurableIdentityCache)
        assertTrue(content.needsNetworkRefresh)
        assertEquals(emptyList(), api.calls)
        coldStore.accept(SharedHomeIntent.Start)
        assertFalse((coldStore.state.value.content as SharedHomeContentState.Content).needsNetworkRefresh)
        scope.cancel()
    }

    @Test
    fun hydrateCachedHomeRejectsSnapshotWithMismatchedMemberIdentity() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val storage = InMemorySlockKeyValueStore()
        val persistSlice =
            createSharedHomeIdentityPersistSlice(
                storage = SharedPreferencePersistStorage(storage)
            )
        val mismatched =
            sampleHome(ServerId("server-1")).copy(
                currentMember = sampleMember().copy(id = MemberId("different-user"))
            )
        val warmStore =
            SharedHomeStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                dataSource = sourceWithSnapshot(mismatched),
                scope = scope,
                persistSlices = listOf(persistSlice)
            )
        warmStore.accept(SharedHomeIntent.Start)

        val coldStore =
            SharedHomeStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                dataSource = source(api = RecordingHomeApi()),
                scope = scope,
                persistSlices =
                    listOf(
                        createSharedHomeIdentityPersistSlice(
                            storage = SharedPreferencePersistStorage(storage)
                        )
                    )
            )

        assertFalse(coldStore.hydrateCachedHome(ServerId("server-1")))
        assertFalse(coldStore.state.value.content is SharedHomeContentState.Content)
        scope.cancel()
    }

    @Test
    fun resetRetiresPublishedHomeProjectionButKeepsDurableIdentitySnapshot() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val storage = InMemorySlockKeyValueStore()
        val persistSlice =
            createSharedHomeIdentityPersistSlice(
                storage = SharedPreferencePersistStorage(storage)
            )
        val session = InMemorySlockAuthSessionStore(sampleSession())
        val store =
            SharedHomeStore(
                authStore = session,
                dataSource = sourceWithSnapshot(sampleHome(ServerId("server-1"))),
                scope = scope,
                persistSlices = listOf(persistSlice)
            )

        store.accept(SharedHomeIntent.Start)
        assertTrue(store.state.value.content is SharedHomeContentState.Content)

        store.reset()

        assertEquals(SharedHomeState(), store.state.value)
        val restored =
            SharedHomeStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                dataSource = source(api = RecordingHomeApi()),
                scope = scope,
                persistSlices = listOf(createSharedHomeIdentityPersistSlice(SharedPreferencePersistStorage(storage)))
            )
        assertTrue(restored.hydrateCachedHome(ServerId("server-1")))
        assertTrue(restored.state.value.content is SharedHomeContentState.Content)
        scope.cancel()
    }

    @Test
    fun resetFencesLateNetworkPublicationFromThePriorSession() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = RecordingHomeApi()
        val blocked = api.blockNextChannels()
        val store =
            SharedHomeStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                dataSource = sourceWithSnapshot(sampleHome(ServerId("server-1")), api = api),
                scope = scope
            )
        val load = launch { store.accept(SharedHomeIntent.Start) }
        blocked.awaitStarted()

        store.reset()
        blocked.release()
        load.join()

        assertEquals(SharedHomeState(), store.state.value)
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
            mapOf(
                "server-1" to 3,
                "server-2" to 11
            ),
            store.state.value.serverUnreadCounts
        )
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
        assertEquals("server-1", content.session?.currentServerId)
        assertContains(logs, "home_ui_error source=shared_home_load code=home_network mapped=home_error_panel")
        scope.cancel()
    }

    @Test
    fun startIgnoresRemovedHomeBlobOnVisibleNetworkError() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeHome(sampleHome(ServerId("server-1")))
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource =
                SharedHomeDataSource(
                    sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                    api = ThrowingHomeApi(),
                    parseHome = { _, _ -> error("network failure should not parse") }
                ),
            scope = scope
        )

        store.accept(SharedHomeIntent.Start)

        val content = store.state.value.content
        assertTrue(content is SharedHomeContentState.Error)
        assertEquals("home_network", content.code)
        assertEquals("server-1", content.session?.currentServerId)
        assertEquals("general", localStore.readHome(ServerId("server-1"))?.channels?.single()?.name)
        scope.cancel()
    }

    @Test
    fun startShowsLoadingUntilNetworkRefreshCompletes() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = BlockingHomeApi()
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource =
                SharedHomeDataSource(
                    sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                    api = api,
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

        val startJob = launch { store.accept(SharedHomeIntent.Start) }
        blockedRefresh.awaitStarted()

        assertTrue(store.state.value.content is SharedHomeContentState.Loading)

        blockedRefresh.release()
        startJob.join()
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
    fun realtimeMessageUpdatesVisibleHomeWithoutWritingLocalCache() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = sourceWithSnapshot(sampleHome(ServerId("server-1")), localStore = localStore),
            scope = scope
        )
        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.ToggleServerSwitcher)
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
        assertEquals("live update", content.baseSnapshot.channels.single().lastMessagePreview)
        assertEquals(1, content.snapshot.channels.single().unreadCount)
        assertEquals(1, content.snapshot.unreadTotal)
        assertEquals(
            "live update",
            store.state.value.serverDrawer.sections
                .getValue(ServerId("server-1"))
                .content
                ?.snapshot
                ?.channels
                ?.single()
                ?.lastMessagePreview
        )
        assertEquals(null, localStore.readHome(ServerId("server-1")))
        scope.cancel()
    }

    @Test
    fun realtimeMessageIncrementsUnreadAfterLeavingSelectedConversation() = runBlocking {
        // task #299: after opening a conversation and leaving it, a new
        // realtime message must accrue unread again — the clear path has to
        // reach the home store's selectedConversationId.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = sourceWithSnapshot(sampleHome(ServerId("server-1"))),
            scope = scope
        )
        store.accept(SharedHomeIntent.Start)
        val otherMember = sampleMember().copy(
            id = MemberId("member-2"),
            displayName = "Cindy",
            handle = "cindy@slock.ai"
        )
        fun liveMessage(id: String, seq: Long, body: String) =
            MessageNew(
                Message(
                    id = MessageId(id),
                    threadId = ThreadId("channel-1"),
                    channelId = ChannelId("channel-1"),
                    seq = seq,
                    author = otherMember,
                    timestampLabel = "12:01",
                    body = body
                )
            )

        // While the conversation is selected, realtime messages only update
        // the preview — no unread increment.
        store.accept(SharedHomeIntent.SelectConversation("channel-1"))
        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = "server-1",
                event = liveMessage("message-2", 2, "while open")
            )
        )
        var content = store.state.value.content
        assertTrue(content is SharedHomeContentState.Content)
        assertEquals(0, content.snapshot.channels.single().unreadCount)

        // After leaving the conversation the same stream must accrue unread.
        store.accept(SharedHomeIntent.ClearSelectedConversation)
        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = "server-1",
                event = liveMessage("message-3", 3, "after leave")
            )
        )
        content = store.state.value.content
        assertTrue(content is SharedHomeContentState.Content)
        assertEquals(1, content.snapshot.channels.single().unreadCount)
        assertEquals(1, content.snapshot.unreadTotal)
        scope.cancel()
    }

    @Test
    fun realtimeMessageUpdateOnlyReplacesMatchingHomeRow() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        val home =
            sampleHome(ServerId("server-1")).copy(
                channels =
                    listOf(
                        channel("channel-1", "general"),
                        channel("channel-2", "random")
                    )
            )
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = sourceWithSnapshot(home, localStore = localStore),
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
    fun directMessageRealtimePreviewPreservedAcrossNetworkRefresh() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val home =
            sampleHome(ServerId("server-1")).copy(
                directMessages =
                    listOf(
                        directMessage("dm-1", "Alice", "member-2", Presence.Active)
                    )
            )
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = sourceWithSnapshot(home),
            scope = scope
        )
        store.accept(SharedHomeIntent.Start)
        val initialDm = (store.state.value.content as SharedHomeContentState.Content).snapshot.directMessages.single()
        assertNull(initialDm.lastMessagePreview)

        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = "server-1",
                event =
                    MessageNew(
                        Message(
                            id = MessageId("msg-dm-1"),
                            threadId = ThreadId("dm-1"),
                            channelId = ChannelId("dm-1"),
                            seq = 1,
                            author = sampleMember().copy(id = MemberId("member-2"), displayName = "Alice"),
                            timestampLabel = "12:05",
                            body = "Hello via DM!"
                        )
                    )
            )
        )

        val afterRealtime = (store.state.value.content as SharedHomeContentState.Content).snapshot.directMessages.single()
        assertEquals("Hello via DM!", afterRealtime.lastMessagePreview)

        // Trigger a network refresh where the server returns the original snapshot without preview
        store.accept(SharedHomeIntent.Refresh)

        val afterRefresh = (store.state.value.content as SharedHomeContentState.Content).snapshot.directMessages.single()
        assertEquals("Hello via DM!", afterRefresh.lastMessagePreview)
        assertEquals("Alice", afterRefresh.lastMessageSenderName)
        assertEquals(MessageId("msg-dm-1"), afterRefresh.lastMessageId)
        scope.cancel()
    }

    @Test
    fun serverSwitchDoesNotLeakDirectMessagePreviewAcrossServersWithIdenticalRowKey() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sessionStore = InMemorySlockAuthSessionStore(sampleSession())
        val api = RecordingHomeApi()
        val server1Home =
            sampleHome(ServerId("server-1")).copy(
                directMessages = listOf(
                    directMessage("shared-dm", "Alice", "member-2", Presence.Active).copy(
                        lastMessagePreview = "Secret preview on server 1"
                    )
                )
            )
        val server2Home =
            sampleHome(ServerId("server-2")).copy(
                directMessages = listOf(
                    directMessage("shared-dm", "Alice", "member-2", Presence.Active).copy(
                        lastMessagePreview = null
                    )
                )
            )
        val dataSource = SharedHomeDataSource(
            sessionStore = sessionStore,
            api = api,
            parseHome = { payload, _ ->
                val snapshot = if (payload.serverId.value == "server-2") server2Home else server1Home
                DataResult.Content(snapshot)
            }
        )
        val store = SharedHomeStore(
            authStore = sessionStore,
            dataSource = dataSource,
            scope = scope
        )
        store.accept(SharedHomeIntent.Start)
        val initialDm = (store.state.value.content as SharedHomeContentState.Content).snapshot.directMessages.single()
        assertEquals("Secret preview on server 1", initialDm.lastMessagePreview)

        store.accept(SharedHomeIntent.SelectServer("server-2"))

        val content = store.state.value.content as SharedHomeContentState.Content
        assertEquals("server-2", content.snapshot.server.id.value)
        val server2Dm = content.snapshot.directMessages.single()
        assertNull(server2Dm.lastMessagePreview)
        scope.cancel()
    }

    @Test
    fun heartbeatDoesNotReloadOrWriteVisibleHomeSnapshot() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val logs = mutableListOf<String>()
        val localStore = InMemoryComposeLocalDataStore()
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = sourceWithSnapshot(sampleHome(ServerId("server-1")), localStore = localStore, logger = logs::add),
            scope = scope,
            presenceFlushDelayMillis = 0L
        )
        store.accept(SharedHomeIntent.Start)
        val beforeContent = store.state.value.content
        logs.clear()

        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = "server-1",
                event = Heartbeat(seq = 42)
            )
        )

        assertTrue(store.state.value.content === beforeContent)
        assertEquals(null, localStore.readHome(ServerId("server-1"))?.channels?.single()?.lastMessagePreview)
        assertEquals(emptyList(), logs)
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
    fun clusteredRefreshRequiredEventsCoalesceHomeReload() = runBlocking {
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
        logs.clear()

        val blockedRefresh = api.blockNextChannels()
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
        blockedRefresh.awaitStarted()
        val callsBeforeCluster = api.calls.toList()

        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = "server-1",
                event =
                    RealtimeRefreshRequired(
                        scope = RealtimeRefreshScope.Settings,
                        reason = "settings:updated",
                        serverId = ServerId("server-1")
                    )
            )
        )
        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = "server-1",
                event =
                    RealtimeRefreshRequired(
                        scope = RealtimeRefreshScope.Reminders,
                        reason = "reminder:updated",
                        serverId = ServerId("server-1")
                    )
            )
        )

        assertEquals(callsBeforeCluster, api.calls)
        assertContains(logs, "home_realtime_refresh scope=ServerMembers reason=server:member-updated")
        assertContains(logs, "home_realtime_refresh_coalesce scope=Settings reason=settings:updated active=load")
        assertContains(logs, "home_realtime_refresh_coalesce scope=Reminders reason=reminder:updated active=load")

        blockedRefresh.release()
        awaitCondition { api.calls.size == 4 }
        assertEquals(
            setOf("channels:server-1", "dms:server-1", "unread:server-1", "summary:server-1"),
            api.calls.toSet()
        )
        scope.cancel()
    }

    @Test
    fun agentSessionRefreshDoesNotReloadVisibleHomeSnapshot() = runBlocking {
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
        logs.clear()

        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = "server-1",
                event =
                    RealtimeRefreshRequired(
                        scope = RealtimeRefreshScope.Agents,
                        reason = "agent:session",
                        serverId = ServerId("server-1"),
                        subjectId = "agent-1"
                    )
            )
        )

        assertEquals(emptyList(), api.calls)
        assertContains(logs, "home_realtime_refresh_lightweight scope=Agents reason=agent:session")
        scope.cancel()
    }

    @Test
    fun machineRefreshUpdatesNotificationsWithoutReloadingVisibleHomeSnapshot() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = RecordingHomeApi()
        val announcementsApi = RecordingAnnouncementsApi(activeBody = """{"announcements":[]}""")
        val computersApi = RecordingComputersApi()
        val logs = mutableListOf<String>()
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource =
                source(
                    api = api,
                    announcementsApi = announcementsApi,
                    computersApi = computersApi
                ),
            scope = scope,
            diagnosticsLogger = logs::add
        )
        store.accept(SharedHomeIntent.Start)
        api.calls.clear()
        announcementsApi.calls.clear()
        computersApi.calls.clear()
        logs.clear()

        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = "server-1",
                event =
                    RealtimeRefreshRequired(
                        scope = RealtimeRefreshScope.Machines,
                        reason = "machine:status",
                        serverId = ServerId("server-1"),
                        subjectId = "machine-1"
                    )
            )
        )

        assertEquals(emptyList(), api.calls)
        assertEquals(emptyList(), announcementsApi.calls)
        assertEquals(listOf("GET machines:server-1"), computersApi.calls)
        assertContains(logs, "home_realtime_refresh_lightweight scope=Machines reason=machine:status")
        scope.cancel()
    }

    @Test
    fun duplicatePresenceDoesNotReloadVisibleHomeSnapshot() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val logs = mutableListOf<String>()
        val localStore = InMemoryComposeLocalDataStore()
        val home =
            sampleHome(ServerId("server-1")).copy(
                directMessages =
                    listOf(
                        directMessage("dm-1", "Agent", "agent-1", Presence.Offline)
                    )
            )
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = sourceWithSnapshot(home, localStore = localStore, logger = logs::add),
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
        val home =
            sampleHome(ServerId("server-1")).copy(
                directMessages =
                    listOf(
                        directMessage("dm-1", "Agent One", "agent-1", Presence.Offline),
                        directMessage("dm-2", "Agent Two", "agent-2", Presence.Active)
                    )
            )
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = sourceWithSnapshot(home, localStore = localStore),
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
        assertTrue(before === after)
        scope.cancel()
    }

    @Test
    fun realtimePresencePatchesVisibleHomeWithoutWritingCache() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val logs = mutableListOf<String>()
        val localStore = InMemoryComposeLocalDataStore()
        val home =
            sampleHome(ServerId("server-1")).copy(
                directMessages =
                    listOf(
                        directMessage("dm-1", "Agent One", "agent-1", Presence.Offline),
                        directMessage("dm-2", "Agent Two", "agent-2", Presence.Offline)
                    )
            )
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = sourceWithSnapshot(home, localStore = localStore, logger = logs::add),
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
        assertTrue(before === after)
        assertTrue(contentAfter.snapshot === contentAfter.baseSnapshot)
        assertEquals(null, localStore.readHome(ServerId("server-1")))
        assertEquals(emptyList(), logs)
        scope.cancel()
    }

    @Test
    fun agentPresenceUsesMemberStoreProjectionWithoutPatchingHomeRows() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val logs = mutableListOf<String>()
        val localStore = InMemoryComposeLocalDataStore()
        val agentDm =
            directMessage("dm-agent-1", "Agent One", "agent-1", Presence.Offline)
                .copy(directMessagePeerType = "agent")
        val home = sampleHome(ServerId("server-1")).copy(directMessages = listOf(agentDm))
        val sessionStore = InMemorySlockAuthSessionStore(sampleSession())
        val store = SharedHomeStore(
            authStore = sessionStore,
            dataSource =
                sourceWithSnapshot(
                    home,
                    sessionStore = sessionStore,
                    localStore = localStore,
                    logger = logs::add,
                    serverMembersStore = SharedServerMembersStore(EmptyMemberRepository)
                ),
            scope = scope,
            diagnosticsLogger = logs::add,
            presenceFlushDelayMillis = 0L
        )
        store.accept(SharedHomeIntent.Start)
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
        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = "server-1",
                event =
                    RealtimeRefreshRequired(
                        scope = RealtimeRefreshScope.Agents,
                        reason = "agent:activity",
                        serverId = ServerId("server-1"),
                        subjectId = "agent-1"
                    )
            )
        )

        val afterContent = store.state.value.content as SharedHomeContentState.Content
        assertTrue(afterContent === beforeContent)
        assertEquals(MemberId("agent-1"), afterContent.snapshot.directMessages.single().presenceMemberId)
        assertEquals(
            listOf(
                "home_realtime_refresh_lightweight scope=Agents reason=agent:activity"
            ),
            logs.filter {
                it.startsWith("home_realtime_refresh_lightweight ") ||
                    it.startsWith("home_agent_status_refresh_skip ")
            }
        )
        assertEquals(emptyList(), logs.filter { it.startsWith("home_presence_realtime_project ") })
        scope.cancel()
    }

    @Test
    fun homeIdentityProjectionRematerializesAfterNetworkRefresh() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        var networkHome =
            sampleHome(ServerId("server-1")).copy(
                channels = listOf(channel("channel-1", "general")),
                directMessages =
                    listOf(
                        directMessage("dm-1", "Agent One", "agent-1", Presence.Offline)
                    )
            )
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource =
                SharedHomeDataSource(
                    sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                    api = RecordingHomeApi(),
                    parseHome = { _, _ -> DataResult.Content(networkHome) }
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
        assertEquals(MemberId("agent-1"), beforeRefresh.snapshot.directMessages.single().presenceMemberId)

        networkHome =
            sampleHome(ServerId("server-1")).copy(
                channels = listOf(channel("channel-2", "updates")),
                directMessages =
                    listOf(
                        directMessage("dm-1", "Agent One", "agent-1", Presence.Offline)
                    )
            )
        store.accept(SharedHomeIntent.Refresh)

        val afterRefresh = store.state.value.content as SharedHomeContentState.Content
        assertEquals("channel-2", afterRefresh.baseSnapshot.channels.single().threadId.value)
        assertEquals(MemberId("agent-1"), afterRefresh.baseSnapshot.directMessages.single().presenceMemberId)
        assertEquals(MemberId("agent-1"), afterRefresh.snapshot.directMessages.single().presenceMemberId)
        assertEquals(null, localStore.readHome(ServerId("server-1")))
        scope.cancel()
    }

    @Test
    fun realtimePresenceBatchUsesLatestEventAndSkipsNoOpState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        val home =
            sampleHome(ServerId("server-1")).copy(
                directMessages =
                    listOf(
                        directMessage("dm-1", "Agent One", "agent-1", Presence.Offline),
                        directMessage("dm-2", "Agent Two", "agent-2", Presence.Active)
                    )
            )
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = sourceWithSnapshot(home, localStore = localStore),
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
        assertTrue(before === after)
        assertEquals(MemberId("agent-1"), contentAfter.baseSnapshot.directMessages[0].presenceMemberId)
        assertEquals(null, localStore.readHome(ServerId("server-1")))
        scope.cancel()
    }

    @Test
    fun realtimePresenceBatchKeepsHighestServerSeqForMember() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        val home =
            sampleHome(ServerId("server-1")).copy(
                directMessages =
                    listOf(
                        directMessage("dm-1", "Agent One", "agent-1", Presence.Offline)
                    )
            )
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = sourceWithSnapshot(home, localStore = localStore),
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
        assertEquals(MemberId("agent-1"), contentAfter.snapshot.directMessages.single().presenceMemberId)
        assertEquals(MemberId("agent-1"), contentAfter.baseSnapshot.directMessages.single().presenceMemberId)
        assertEquals(null, localStore.readHome(ServerId("server-1")))
        scope.cancel()
    }

    @Test
    fun hiddenRealtimeMessageMarksDirtyWithoutWritingLocalCacheOrProjectingHomeState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val logs = mutableListOf<String>()
        val localStore = InMemoryComposeLocalDataStore()
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = sourceWithSnapshot(sampleHome(ServerId("server-1")), localStore = localStore, logger = logs::add),
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
        assertEquals(null, localStore.readHome(ServerId("server-1")))
        assertEquals(emptyList(), logs.filter { it.startsWith("read_home ") })
        assertContains(logs, "home_realtime_dirty serverId=server-1 reason=message_new")
        scope.cancel()
    }

    @Test
    fun hiddenRealtimePresenceMarksDirtyWithoutPatchingRowsOrCache() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val logs = mutableListOf<String>()
        val localStore = InMemoryComposeLocalDataStore()
        val home =
            sampleHome(ServerId("server-1")).copy(
                directMessages =
                    listOf(
                        directMessage("dm-1", "Agent One", "agent-1", Presence.Offline)
                    )
            )
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = sourceWithSnapshot(home, localStore = localStore, logger = logs::add),
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
        assertEquals(false, afterState.homeRealtimeDirty)
        assertEquals(MemberId("agent-1"), beforeContent.snapshot.directMessages.single().presenceMemberId)
        assertEquals(MemberId("agent-1"), beforeContent.baseSnapshot.directMessages.single().presenceMemberId)
        assertEquals(null, localStore.readHome(ServerId("server-1")))
        assertEquals(emptyList(), logs.filter { it.startsWith("home_realtime_dirty ") })

        store.accept(SharedHomeIntent.SetSurfaceVisible(true))
        val visibleContent = store.state.value.content as SharedHomeContentState.Content
        assertEquals(MemberId("agent-1"), visibleContent.snapshot.directMessages.single().presenceMemberId)
        assertEquals(MemberId("agent-1"), visibleContent.baseSnapshot.directMessages.single().presenceMemberId)
        assertEquals(false, store.state.value.homeRealtimeDirty)
        assertEquals(null, localStore.readHome(ServerId("server-1")))
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
    fun duplicateHomeSnapshotStartRevalidatesWithoutFreshnessSkip() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val logs = mutableListOf<String>()
        val localStore = InMemoryComposeLocalDataStore()
        val api = RecordingHomeApi()
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource =
                SharedHomeDataSource(
                    sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                    api = api,
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

        withTimeout(1_000) {
            while (api.calls.size < 8) yield()
        }
        assertEquals(countBeforeStart, observedStates.size)
        assertEquals(8, api.calls.size)
        assertEquals(0, logs.count { it.contains("home_refresh_skip") && it.contains("reason=fresh_snapshot") })
        observer.cancel()
        scope.cancel()
    }

    @Test
    fun cachedHomeStartRefreshesImmediatelyAndManualRefreshStillForcesNetwork() = runBlocking {
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

        awaitCondition { api.calls.size >= 8 }
        assertEquals(8, api.calls.size)
        assertEquals(0, logs.count { it.contains("home_refresh_skip") && it.contains("reason=fresh_snapshot") })

        store.accept(SharedHomeIntent.Refresh)

        awaitCondition { api.calls.size >= 12 }
        assertEquals(12, api.calls.size)
        scope.cancel()
    }

    @Test
    fun foregroundRefreshForcesNetworkWithoutVisibleRefreshAffordance() = runBlocking {
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
                    parseHome = { payload, _ -> DataResult.Content(sampleHome(payload.serverId)) }
                ),
            scope = scope,
            nowMillis = { now }
        )

        store.accept(SharedHomeIntent.Start)
        assertEquals(4, api.calls.size)
        now += 1_000L

        val blockedRefresh = api.blockNextChannels()
        val refreshJob = launch { store.accept(SharedHomeIntent.ForegroundRefresh) }
        blockedRefresh.awaitStarted()

        val refreshingContent = store.state.value.content
        assertTrue(refreshingContent is SharedHomeContentState.Content)
        assertEquals(false, refreshingContent.contentRefreshing)

        blockedRefresh.release()
        refreshJob.join()
        awaitCondition { api.calls.size >= 8 }
        assertEquals(8, api.calls.size)
        scope.cancel()
    }

    @Test
    fun cachedHomeStartRefreshesWithoutWaitingForFreshnessWindow() = runBlocking {
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
                    parseHome = { payload, _ -> DataResult.Content(sampleHome(payload.serverId)) }
                ),
            scope = scope,
            nowMillis = { now }
        )

        store.accept(SharedHomeIntent.Start)
        assertEquals(4, api.calls.size)

        now += 1_000L
        store.accept(SharedHomeIntent.Start)

        awaitCondition { api.calls.size >= 8 }
        assertEquals(8, api.calls.size)
        scope.cancel()
    }

    @Test
    fun dispatchStartDebouncesInitialNetworkLoadWithoutCachedHome() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val logs = mutableListOf<String>()
        val api = RecordingHomeApi()
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = source(api = api),
            scope = scope,
            diagnosticsLogger = logs::add
        )
        val blockedLoad = api.blockNextChannels()

        store.dispatch(SharedHomeIntent.Start)
        blockedLoad.awaitStarted()
        logs.clear()

        store.dispatch(SharedHomeIntent.Start)
        yield()

        assertEquals(3, api.calls.size)
        assertEquals(setOf("dms:server-1", "unread:server-1", "summary:server-1"), api.calls.toSet())
        assertEquals(
            1,
            logs.count {
                it.contains("home_load_debounce") &&
                    it.contains("intent=hydrate:start") &&
                    it.contains("reason=in_flight_load")
            }
        )

        blockedLoad.release()
        awaitCondition { api.calls.size >= 4 }
        assertEquals(4, api.calls.size)
        scope.cancel()
    }

    @Test
    fun visibleDirtyReconcileCoalescesIntoInFlightStartLoad() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val logs = mutableListOf<String>()
        val api = RecordingHomeApi()
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = sourceWithSnapshot(sampleHome(ServerId("server-1")), api = api),
            scope = scope,
            diagnosticsLogger = logs::add
        )
        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.SetSurfaceVisible(false))
        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = "server-1",
                event =
                    MessageNew(
                        Message(
                            id = MessageId("message-hidden"),
                            threadId = ThreadId("channel-1"),
                            channelId = ChannelId("channel-1"),
                            seq = 2,
                            author = sampleMember().copy(id = MemberId("member-2")),
                            timestampLabel = "12:01",
                            body = "hidden update"
                        )
                    )
            )
        )
        assertEquals(true, store.state.value.homeRealtimeDirty)
        api.calls.clear()
        logs.clear()
        val blockedLoad = api.blockNextChannels()

        store.dispatch(SharedHomeIntent.Start)
        blockedLoad.awaitStarted()
        store.dispatch(SharedHomeIntent.SetSurfaceVisible(true))
        yield()

        assertEquals(3, api.calls.size)
        assertEquals(
            setOf("dms:server-1", "unread:server-1", "summary:server-1"),
            api.calls.toSet()
        )
        assertEquals(
            1,
            logs.count {
                it == "home_load_debounce intent=visible_dirty_reconcile reason=in_flight_load"
            }
        )

        blockedLoad.release()
        awaitCondition {
            api.calls.size >= 4 &&
                (store.state.value.content as? SharedHomeContentState.Content)?.contentRefreshing == false
        }
        assertEquals(4, api.calls.size)
        assertEquals(false, store.state.value.homeRealtimeDirty)
        assertEquals("server-1", (store.state.value.content as SharedHomeContentState.Content).snapshot.server.id.value)
        scope.cancel()
    }

    @Test
    fun dispatchStartDebouncesInFlightHomeLoad() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val logs = mutableListOf<String>()
        val api = BlockingHomeApi()
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource =
                SharedHomeDataSource(
                    sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                    api = api,
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

        assertEquals(
            1,
            logs.count {
                it.contains("home_load_debounce") &&
                    it.contains("intent=hydrate:start") &&
                    it.contains("reason=in_flight_load")
            }
        )
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
    fun dispatchForegroundRefreshReplacesInFlightHomeBackgroundRefresh() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val logs = mutableListOf<String>()
        val api = BlockingHomeApi()
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource =
                SharedHomeDataSource(
                    sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                    api = api,
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
        val blockedBackgroundRefresh = api.blockNextChannels()

        store.dispatch(SharedHomeIntent.Start)
        blockedBackgroundRefresh.awaitStarted()
        logs.clear()

        val blockedForegroundRefresh = api.blockNextChannels()
        store.dispatch(SharedHomeIntent.ForegroundRefresh)
        blockedForegroundRefresh.awaitStarted()

        assertEquals(
            0,
            logs.count {
                it.contains("home_load_debounce") &&
                    it.contains("intent=hydrate:foreground")
            }
        )

        blockedForegroundRefresh.release()
        awaitCondition {
            val refreshedContent = store.state.value.content
            refreshedContent is SharedHomeContentState.Content &&
                refreshedContent.snapshot.channels.single().name == "network"
        }

        blockedBackgroundRefresh.release()
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
            channel("channel-new", "release-room")
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
    fun startDoesNotSurfaceActiveAnnouncementsInHomeNotifications() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val announcementsApi = RecordingAnnouncementsApi()
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = source(api = RecordingHomeApi(), announcementsApi = announcementsApi),
            scope = scope
        )

        store.accept(SharedHomeIntent.Start)

        assertEquals(emptyList(), store.state.value.notifications)
        assertEquals(emptyList(), announcementsApi.calls)
        scope.cancel()
    }

    @Test
    fun startDoesNotRequestBlankActiveAnnouncementsForHomeNotifications() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val announcementsApi =
            RecordingAnnouncementsApi(
                activeBody =
                    """
                    {
                      "announcements": [
                        {
                          "id": "announcement-blank",
                          "title": "   ",
                          "pages": [
                            { "body": "   " }
                          ]
                        }
                      ]
                    }
                    """.trimIndent()
            )
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = source(api = RecordingHomeApi(), announcementsApi = announcementsApi),
            scope = scope
        )

        store.accept(SharedHomeIntent.Start)

        assertEquals(emptyList(), store.state.value.notifications)
        assertEquals(emptyList(), announcementsApi.calls)
        scope.cancel()
    }

    @Test
    fun startLoadsOfflineMachinesIntoHomeNotifications() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val announcementsApi = RecordingAnnouncementsApi(activeBody = """{"announcements":[]}""")
        val computersApi = RecordingComputersApi()
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource =
                source(
                    api = RecordingHomeApi(),
                    announcementsApi = announcementsApi,
                    computersApi = computersApi
                ),
            scope = scope
        )

        store.accept(SharedHomeIntent.Start)

        val notification = store.state.value.notifications.single()
        assertEquals("machine-offline:machine-1", notification.id)
        assertEquals("OranixSlockTest is offline", notification.title)
        assertEquals(
            "No agents are active on this computer right now - reconnect when you need it next.",
            notification.body
        )
        assertEquals("View", notification.actionLabel)
        assertTrue(notification.titleDesc != null)
        assertTrue(notification.bodyDesc != null)
        assertTrue(notification.actionLabelDesc != null)
        assertEquals("machine-1", notification.targetId)
        assertEquals(SharedHomeNotificationKind.Warning, notification.kind)
        assertEquals(SharedHomeNotificationIcon.WifiOff, notification.icon)
        assertEquals(emptyList(), announcementsApi.calls)
        assertEquals(listOf("GET machines:server-1"), computersApi.calls)
        scope.cancel()
    }

    @Test
    fun machineNotificationsFollowStoreRoleAuthority() = runBlocking {
        listOf(
            "owner" to true,
            "admin" to true,
            "member" to false,
            "unknown" to false,
            null to false
        ).forEach { (role, expectedMachineNotification) ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val sessionStore = InMemorySlockAuthSessionStore(sampleSession(role = role))
            val computersApi = RecordingComputersApi()
            val store =
                SharedHomeStore(
                    authStore = sessionStore,
                    dataSource =
                        source(
                            api = RecordingHomeApi(),
                            computersApi = computersApi,
                            sessionStore = sessionStore
                        ),
                    scope = scope
                )

            store.accept(SharedHomeIntent.Start)

            assertEquals(
                expectedMachineNotification,
                store.state.value.notifications.any { it.id.isMachineHomeNotificationId() },
                "role=$role"
            )
            assertEquals(
                if (expectedMachineNotification) listOf("GET machines:server-1") else emptyList(),
                computersApi.calls,
                "role=$role"
            )
            scope.cancel()
        }
    }

    @Test
    fun missingSelectedServerDoesNotCallMachineSource() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sessionStore =
            InMemorySlockAuthSessionStore(
                sampleSession(role = "owner").copy(selectedServerId = "missing-server")
            )
        val computersApi = RecordingComputersApi()
        val store =
            SharedHomeStore(
                authStore = sessionStore,
                dataSource =
                    source(
                        api = RecordingHomeApi(),
                        computersApi = computersApi,
                        sessionStore = sessionStore
                    ),
                scope = scope
            )

        store.accept(SharedHomeIntent.Start)

        assertFalse(store.state.value.notifications.any { it.id.isMachineHomeNotificationId() })
        assertEquals(emptyList(), computersApi.calls)
        scope.cancel()
    }

    @Test
    fun sameServerDemotionClearsOnlyMachineNotificationsBeforeNetworkCompletes() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sessionStore = InMemorySlockAuthSessionStore(sampleSession(role = "owner"))
        val homeApi = RecordingHomeApi()
        val store =
            SharedHomeStore(
                authStore = sessionStore,
                dataSource =
                    source(
                        api = homeApi,
                        computersApi = RecordingComputersApi(),
                        sessionStore = sessionStore
                    ),
                scope = scope
            )
        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.UpsertNotification(appUpdateNotification()))
        store.accept(SharedHomeIntent.UpsertNotification(otherNotification()))
        sessionStore.saveSession(sampleSession(role = "member"))
        val blockedLoad = homeApi.blockNextChannels("server-1")

        val demotion = launch { store.accept(SharedHomeIntent.Refresh) }
        blockedLoad.awaitStarted()

        assertEquals(
            setOf(APP_UPDATE_HOME_NOTIFICATION_ID, "announcement:other"),
            store.state.value.notifications.mapTo(mutableSetOf()) { it.id }
        )
        blockedLoad.release()
        demotion.join()
        scope.cancel()
    }

    @Test
    fun serverSwitchClearsOnlyMachineNotificationsBeforeNetworkCompletes() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sessionStore =
            InMemorySlockAuthSessionStore(
                sampleSession(role = "owner", secondServerRole = "member")
            )
        val homeApi = RecordingHomeApi()
        val store =
            SharedHomeStore(
                authStore = sessionStore,
                dataSource =
                    source(
                        api = homeApi,
                        computersApi = RecordingComputersApi(),
                        sessionStore = sessionStore
                    ),
                scope = scope
            )
        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.UpsertNotification(appUpdateNotification()))
        store.accept(SharedHomeIntent.UpsertNotification(otherNotification()))
        val blockedLoad = homeApi.blockNextChannels("server-2")

        val switch = launch { store.accept(SharedHomeIntent.SelectServer("server-2")) }
        blockedLoad.awaitStarted()

        assertEquals(
            setOf(APP_UPDATE_HOME_NOTIFICATION_ID, "announcement:other"),
            store.state.value.notifications.mapTo(mutableSetOf()) { it.id }
        )
        blockedLoad.release()
        switch.join()
        scope.cancel()
    }

    @Test
    fun staleOwnerMachineRequestCannotPublishAfterSwitchOrDemotion() = runBlocking {
        listOf("switch", "demotion").forEach { transition ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val initialSession =
                sampleSession(role = "owner", secondServerRole = "member")
            val sessionStore = InMemorySlockAuthSessionStore(initialSession)
            val computersApi = BlockingComputersApi()
            val store =
                SharedHomeStore(
                    authStore = sessionStore,
                    dataSource =
                        source(
                            api = RecordingHomeApi(),
                            computersApi = computersApi,
                            sessionStore = sessionStore,
                            appUpdateStatusProvider = { DataResult.Content(updateAvailableSnapshot()) }
                        ),
                    scope = scope
                )

            val oldOwnerLoad = launch { store.accept(SharedHomeIntent.Start) }
            computersApi.awaitStarted()
            when (transition) {
                "switch" -> store.accept(SharedHomeIntent.SelectServer("server-2"))
                else -> {
                    sessionStore.saveSession(sampleSession(role = "member", secondServerRole = "member"))
                    store.accept(SharedHomeIntent.Refresh)
                }
            }
            assertEquals(listOf(APP_UPDATE_HOME_NOTIFICATION_ID), store.state.value.notifications.map { it.id })

            computersApi.release()
            oldOwnerLoad.join()

            assertEquals(
                listOf(APP_UPDATE_HOME_NOTIFICATION_ID),
                store.state.value.notifications.map { it.id },
                "transition=$transition"
            )
            assertEquals(1, computersApi.calls, "transition=$transition")
            scope.cancel()
        }
    }

    @Test
    fun ownerRequestThatPassedNetworkCannotPublishAfterCommitTimeServerSwitch() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sessionStore =
            InMemorySlockAuthSessionStore(
                sampleSession(role = "owner", secondServerRole = "member")
            )
        val homeApi = RecordingHomeApi()
        val publicationBarrier = NotificationPublicationBarrier()
        val store =
            SharedHomeStore(
                authStore = sessionStore,
                dataSource =
                    source(
                        api = homeApi,
                        computersApi = RecordingComputersApi(),
                        sessionStore = sessionStore,
                        appUpdateStatusProvider = { DataResult.Content(updateAvailableSnapshot()) }
                    ),
                scope = scope,
                beforeNotificationPublicationCommit = publicationBarrier::intercept
            )
        store.accept(SharedHomeIntent.Start)
        assertTrue(store.state.value.notifications.any { it.id.isMachineHomeNotificationId() })
        publicationBarrier.arm()

        val oldOwnerRefresh = launch { store.accept(SharedHomeIntent.RefreshNotifications) }
        publicationBarrier.awaitStarted()
        val blockedSwitch = homeApi.blockNextChannels("server-2")
        val switch = launch { store.accept(SharedHomeIntent.SelectServer("server-2")) }
        blockedSwitch.awaitStarted()
        assertEquals(listOf(APP_UPDATE_HOME_NOTIFICATION_ID), store.state.value.notifications.map { it.id })

        publicationBarrier.release()
        oldOwnerRefresh.join()

        assertEquals(listOf(APP_UPDATE_HOME_NOTIFICATION_ID), store.state.value.notifications.map { it.id })
        blockedSwitch.release()
        switch.join()
        scope.cancel()
    }

    @Test
    fun startLoadsOutdatedMachinesIntoHomeNotifications() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val computersApi =
            RecordingComputersApi(
                bodyText =
                    """
                    {
                      "latestDaemonVersion": "0.63.7",
                      "machines": [
                        {
                          "id": "machine-1",
                          "name": "Jiachengs-MacBook-Pro.local",
                          "status": "online",
                          "statusVersion": 1,
                          "daemonVersion": "0.62.0"
                        }
                      ]
                    }
                    """.trimIndent()
            )
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource =
                source(
                    api = RecordingHomeApi(),
                    announcementsApi = RecordingAnnouncementsApi(activeBody = """{"announcements":[]}"""),
                    computersApi = computersApi
                ),
            scope = scope
        )

        store.accept(SharedHomeIntent.Start)

        val notification = store.state.value.notifications.single()
        assertEquals("machine-outdated:0.63.7:machine-1", notification.id)
        assertEquals("Jiachengs-MacBook-Pro.local is running an outdated daemon", notification.title)
        assertEquals("Stop the daemon and reconnect to update.", notification.body)
        assertTrue(notification.titleDesc != null)
        assertTrue(notification.bodyDesc != null)
        assertTrue(notification.actionLabelDesc != null)
        assertEquals("machine-1", notification.targetId)
        assertEquals(SharedHomeNotificationKind.Warning, notification.kind)
        assertEquals(SharedHomeNotificationIcon.CircleArrowUp, notification.icon)
        scope.cancel()
    }

    @Test
    fun homeStartDoesNotLoadActiveAnnouncementsIntoHomeNotifications() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val announcementsApi = RecordingAnnouncementsApi()
        val sessionStore = InMemorySlockAuthSessionStore(sampleSession())
        val store = SharedHomeStore(
            authStore = sessionStore,
            dataSource = source(api = RecordingHomeApi(), announcementsApi = announcementsApi, sessionStore = sessionStore),
            scope = scope
        )

        store.accept(SharedHomeIntent.Start)

        assertEquals(emptyList(), store.state.value.notifications)
        assertEquals(emptyList(), announcementsApi.calls)
        scope.cancel()
    }

    @Test
    fun realtimeAnnouncementDoesNotRefreshHomeNotifications() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val announcementsApi = RecordingAnnouncementsApi()
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = source(api = RecordingHomeApi(), announcementsApi = announcementsApi),
            scope = scope
        )
        store.accept(SharedHomeIntent.Start)
        announcementsApi.calls.clear()
        announcementsApi.activeBody =
            """
            {
              "announcements": [
                {
                  "id": "announcement-2",
                  "title": "Realtime notice",
                  "pages": [
                    { "body": "Home should consume the live announcement event." }
                  ]
                }
              ]
            }
            """.trimIndent()

        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = "server-1",
                event =
                    RealtimeRefreshRequired(
                        scope = RealtimeRefreshScope.Announcements,
                        reason = "announcement:new"
                    )
            )
        )

        assertEquals(emptyList(), store.state.value.notifications)
        assertEquals(emptyList(), announcementsApi.calls)
        scope.cancel()
    }

    @Test
    fun dismissLocalMachineNotificationDoesNotCallAnnouncementsApi() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val announcementsApi = RecordingAnnouncementsApi(activeBody = """{"announcements":[]}""")
        val computersApi = RecordingComputersApi()
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource =
                source(
                    api = RecordingHomeApi(),
                    announcementsApi = announcementsApi,
                    computersApi = computersApi
                ),
            scope = scope
        )
        store.accept(SharedHomeIntent.Start)

        store.accept(SharedHomeIntent.DismissNotification("machine-offline:machine-1"))

        assertEquals(emptyList(), store.state.value.notifications)
        assertEquals(emptyList(), announcementsApi.calls)
        scope.cancel()
    }

    @Test
    fun upsertNotificationReplacesAppUpdateActionStateImmediately() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = source(api = RecordingHomeApi()),
            scope = scope
        )
        val downloading =
            SharedHomeNotification(
                id = APP_UPDATE_HOME_NOTIFICATION_ID,
                title = "App update available",
                body = "Current version 1.0.0. Latest version 1.0.1 (1000100).",
                actionLabel = "Downloading",
                targetId = APP_UPDATE_HOME_NOTIFICATION_ID,
                kind = SharedHomeNotificationKind.Info,
                icon = SharedHomeNotificationIcon.CircleArrowUp
            )
        val install = downloading.copy(actionLabel = "Install")

        store.accept(SharedHomeIntent.UpsertNotification(downloading))
        assertEquals("Downloading", store.state.value.notifications.single().actionLabel)

        store.accept(SharedHomeIntent.UpsertNotification(install))
        assertEquals("Install", store.state.value.notifications.single().actionLabel)
        assertEquals(listOf(APP_UPDATE_HOME_NOTIFICATION_ID), store.state.value.notifications.map { it.id })
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
    fun unavailableUnreadKeepsPriorBadgesThenRecoversOnNextGeneration() = runBlocking {
        // task #831: when channels/DMs are fresh but the unread enrichment was
        // unavailable (error/timeout/hung/contract-mismatch, flagged by
        // unreadEnrichmentAvailable = false), the Home must keep the prior badge
        // + drawer counts (only flip isStale) instead of zeroing them. A later
        // generation with available unread recovers the fresh value.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sessionStore = InMemorySlockAuthSessionStore(sampleSession())
        val serverId = ServerId("server-1")
        val availableWithBadges =
            sampleHome(serverId).copy(
                unreadTotal = 7,
                unreadSource = AppBadgeSource.SERVER_UNREAD_SUMMARY,
                serverUnreadCounts = mapOf("server-1" to 7, "server-2" to 3),
                unreadEnrichmentAvailable = true
            )
        val unavailableHonestEmpty =
            sampleHome(serverId).copy(
                unreadTotal = 0,
                unreadSource = AppBadgeSource.CHANNEL_DM_FALLBACK,
                serverUnreadCounts = emptyMap(),
                unreadEnrichmentAvailable = false
            )
        val recovered =
            sampleHome(serverId).copy(
                unreadTotal = 2,
                unreadSource = AppBadgeSource.SERVER_UNREAD_SUMMARY,
                serverUnreadCounts = mapOf("server-1" to 2, "server-2" to 5),
                unreadEnrichmentAvailable = true
            )
        val snapshots = listOf(availableWithBadges, unavailableHonestEmpty, recovered)
        var index = 0
        val dataSource =
            SharedHomeDataSource(
                sessionStore = sessionStore,
                api = RecordingHomeApi(),
                parseHome = { _, _ -> DataResult.Content(snapshots[minOf(index++, snapshots.lastIndex)]) }
            )
        val store = SharedHomeStore(authStore = sessionStore, dataSource = dataSource, scope = scope)

        store.accept(SharedHomeIntent.Start)
        assertEquals(7, store.state.value.appBadgeCount.count)
        assertEquals(false, store.state.value.appBadgeCount.isStale)
        assertEquals(3, store.state.value.serverUnreadCounts["server-2"])

        store.accept(SharedHomeIntent.Refresh)
        // Prior badge kept, only marked stale — NOT zeroed.
        assertEquals(7, store.state.value.appBadgeCount.count)
        assertEquals(true, store.state.value.appBadgeCount.isStale)
        assertEquals(3, store.state.value.serverUnreadCounts["server-2"])
        // task #831 blocker: the COMMITTED snapshot / baseSnapshot (the realtime
        // unread delta baseline) must also freeze to prior unread, or the next
        // realtime message would rebuild from zero and clobber 7 down to 1.
        val committed = store.state.value.content as SharedHomeContentState.Content
        assertEquals(7, committed.baseSnapshot.unreadTotal)
        assertEquals(mapOf("server-1" to 7, "server-2" to 3), committed.baseSnapshot.serverUnreadCounts)

        // End-to-end: a realtime unread-increment for a non-current server after
        // the unavailable refresh must increment from the FROZEN baseline (3),
        // not from a clobbered zero. applyBackgroundUnreadMessages adds to
        // current.serverUnreadCounts[serverId]; with the delayed-clobber bug that
        // baseline was 0, so this would have been 1 instead of 4.
        val backgroundMessage =
            MessageNew(
                Message(
                    id = MessageId("bg-unread-201"),
                    threadId = ThreadId("channel-b"),
                    channelId = ChannelId("channel-b"),
                    seq = 201,
                    author = sampleMember().copy(id = MemberId("member-2"), displayName = "Peer"),
                    timestampLabel = "12:02",
                    body = "background update after unavailable refresh",
                )
            )
        store.accept(SharedHomeIntent.ApplyBackgroundServerUnreadEvent("server-2", backgroundMessage))
        assertEquals(4, store.state.value.serverUnreadCounts["server-2"])

        store.accept(SharedHomeIntent.Refresh)
        // Fresh available unread recovers the value.
        assertEquals(2, store.state.value.appBadgeCount.count)
        assertEquals(false, store.state.value.appBadgeCount.isStale)
        assertEquals(5, store.state.value.serverUnreadCounts["server-2"])
        scope.cancel()
    }

    @Test
    fun unavailableUnreadKeepsCurrentServerRealtimeBaselineRowAggregateAndBadge() = runBlocking {
        // task #831 blocker (current-server write boundary): the delayed clobber
        // lived in applyRealtimeHomeEventLocally, which pushes the new message
        // through content.baseSnapshot.withRealtimeMessages(...) then writes the
        // row/unreadTotal/server-count/badge back to top-level. If the frozen
        // baseSnapshot had been zeroed, a current-server message would increment
        // from 0 (→1). With unread frozen to prior 7, it must increment to 8.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sessionStore = InMemorySlockAuthSessionStore(sampleSession())
        val serverId = ServerId("server-1")
        val availableWithRowUnread =
            sampleHome(serverId).let { base ->
                base.copy(
                    server = base.server.copy(unreadCount = 7),
                    channels = listOf(channel("channel-1", "general").copy(unreadCount = 7, unreadCountKnown = true)),
                    unreadTotal = 7,
                    unreadSource = AppBadgeSource.SERVER_UNREAD_SUMMARY,
                    serverUnreadCounts = mapOf("server-1" to 7),
                    unreadEnrichmentAvailable = true
                )
            }
        val unavailable =
            sampleHome(serverId).copy(
                channels = listOf(channel("channel-1", "general")),
                unreadTotal = 0,
                unreadSource = AppBadgeSource.CHANNEL_DM_FALLBACK,
                serverUnreadCounts = emptyMap(),
                unreadEnrichmentAvailable = false
            )
        val snapshots = listOf(availableWithRowUnread, unavailable)
        var index = 0
        val dataSource =
            SharedHomeDataSource(
                sessionStore = sessionStore,
                api = RecordingHomeApi(),
                parseHome = { _, _ -> DataResult.Content(snapshots[minOf(index++, snapshots.lastIndex)]) }
            )
        val store = SharedHomeStore(authStore = sessionStore, dataSource = dataSource, scope = scope)

        store.accept(SharedHomeIntent.Start)
        assertEquals(7, (store.state.value.content as SharedHomeContentState.Content).snapshot.channels.single().unreadCount)
        assertEquals(7, store.state.value.appBadgeCount.count)

        store.accept(SharedHomeIntent.Refresh)
        val frozen = store.state.value.content as SharedHomeContentState.Content
        assertEquals(7, frozen.baseSnapshot.channels.single().unreadCount)
        assertEquals(7, frozen.baseSnapshot.unreadTotal)
        assertEquals(7, store.state.value.appBadgeCount.count)

        // Current-server realtime message: increments from the frozen 7, not 0.
        val peer = sampleMember().copy(id = MemberId("member-2"), displayName = "Peer")
        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = "server-1",
                event =
                    MessageNew(
                        Message(
                            id = MessageId("rt-unread-8"),
                            threadId = ThreadId("channel-1"),
                            channelId = ChannelId("channel-1"),
                            seq = 8,
                            author = peer,
                            timestampLabel = "12:03",
                            body = "live current-server update",
                        )
                    )
            )
        )
        val after = store.state.value.content as SharedHomeContentState.Content
        assertEquals(8, after.snapshot.channels.single().unreadCount)
        assertEquals(8, after.snapshot.unreadTotal)
        assertEquals(8, after.baseSnapshot.channels.single().unreadCount)
        assertEquals(8, after.baseSnapshot.unreadTotal)
        // withRealtimeMessagesForStream sets serverUnreadCounts[server.id] in the
        // same copy, and applyRealtimeHomeEventLocally writes it back to top-level
        // — the last unread write face must also increment from the frozen 7.
        assertEquals(8, after.baseSnapshot.serverUnreadCounts["server-1"])
        assertEquals(8, store.state.value.serverUnreadCounts["server-1"])
        assertEquals(8, store.state.value.appBadgeCount.count)
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

    private object EmptyMemberRepository : MemberRepository {
        private fun <T> noMembers(): DataResult<T> =
            DataResult.Empty(EmptyReason.NoMembers)

        override suspend fun getMembers(
            priority: DataRequestPriority,
            serverId: ServerId
        ): DataResult<MemberSnapshot> = noMembers()

        override suspend fun getMemberProfile(
            priority: DataRequestPriority,
            memberId: MemberId,
            serverId: ServerId?
        ): DataResult<MemberProfile> = noMembers()

        override suspend fun updateAgentProfile(
            priority: DataRequestPriority,
            request: AgentProfileUpdateRequest
        ): DataResult<MemberProfile> = noMembers()

        override suspend fun uploadAgentAvatar(
            priority: DataRequestPriority,
            request: AgentAvatarUploadRequest
        ): DataResult<MemberProfile> = noMembers()

        override suspend fun getAgentDetail(
            priority: DataRequestPriority,
            agentId: MemberId
        ): DataResult<AgentDetailSnapshot> = noMembers()

        override suspend fun getAgentDirectMessages(
            priority: DataRequestPriority,
            agentId: MemberId
        ): DataResult<AgentDmSnapshot> = noMembers()

        override suspend fun getAgentReminders(
            priority: DataRequestPriority,
            agentId: MemberId
        ): DataResult<AgentReminderSnapshot> = noMembers()

        override suspend fun getAgentActivity(
            priority: DataRequestPriority,
            agentId: MemberId
        ): DataResult<AgentActivitySnapshot> = noMembers()

        override suspend fun getAgentWorkspaces(
            priority: DataRequestPriority,
            agentId: MemberId
        ): DataResult<AgentWorkspaceSnapshot> = noMembers()

        override suspend fun getAgentWorkspaceTree(
            priority: DataRequestPriority,
            agentId: MemberId
        ): DataResult<AgentWorkspaceTreeSnapshot> = noMembers()

        override suspend fun getAgentWorkspaceChildren(
            priority: DataRequestPriority,
            agentId: MemberId,
            dirPath: String
        ): DataResult<AgentWorkspaceChildrenSnapshot> = noMembers()

        override suspend fun readAgentWorkspaceFile(
            priority: DataRequestPriority,
            agentId: MemberId,
            path: String
        ): DataResult<AgentWorkspaceFilePreviewSnapshot> = noMembers()

        override suspend fun getAgentSkills(
            priority: DataRequestPriority,
            agentId: MemberId
        ): DataResult<AgentSkillsSnapshot> = noMembers()

        override suspend fun startAgent(
            priority: DataRequestPriority,
            agentId: MemberId
        ): DataResult<AgentLifecycleActionResult> = noMembers()

        override suspend fun stopAgent(
            priority: DataRequestPriority,
            agentId: MemberId
        ): DataResult<AgentLifecycleActionResult> = noMembers()

        override suspend fun resetAgent(
            priority: DataRequestPriority,
            request: AgentResetRequest
        ): DataResult<AgentLifecycleActionResult> = noMembers()

        override suspend fun deleteAgent(
            priority: DataRequestPriority,
            agentId: MemberId
        ): DataResult<AgentLifecycleActionResult> = noMembers()

        override suspend fun assignAgentMachine(
            priority: DataRequestPriority,
            request: AgentMachineAssignmentRequest
        ): DataResult<AgentLifecycleActionResult> = noMembers()

        override suspend fun getChannelMembers(
            priority: DataRequestPriority,
            channelId: ChannelId
        ): DataResult<ChannelMemberSnapshot> = noMembers()

        override suspend fun addChannelMember(
            priority: DataRequestPriority,
            request: AddChannelMemberRequest
        ): DataResult<ChannelMemberActionResult> = noMembers()

        override suspend fun removeChannelMember(
            priority: DataRequestPriority,
            request: RemoveChannelMemberRequest
        ): DataResult<ChannelMemberActionResult> = noMembers()
    }

    @Test
    fun serverActivityBatchPublishesThroughTheHomeStoreWithEnvironmentAccountAndServerFences() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sessionStore = InMemorySlockAuthSessionStore(sampleSession())
        val requestOwner = ReadReceiptOwner("https://raft.example", "user-1", "server-1")
        val snapshot =
            sampleHome(ServerId("server-1")).copy(
                serverUnreadCounts = mapOf("server-1" to 21, "server-2" to 6),
                serverActivityUnreadCounts = mapOf("server-1" to 0, "server-2" to 3),
                serverPushMutedByServer = mapOf("server-1" to false, "server-2" to true),
            )
        val store =
            SharedHomeStore(
                authStore = sessionStore,
                dataSource = sourceWithSnapshot(snapshot, sessionStore = sessionStore),
                scope = scope,
                readReceiptOwnerFactory = { serverId, accountId ->
                    ReadReceiptOwner("https://raft.example", accountId, serverId)
                },
            )

        store.accept(SharedHomeIntent.Start)

        val projection = store.state.value.serverActivityUnread
        assertEquals(requestOwner, projection.requestOwner)
        assertEquals(0L, projection.countFor(requestOwner))
        assertEquals(3L, projection.countFor(requestOwner.copy(serverId = "server-2")))
        assertTrue(projection.serverPushMutedFor(requestOwner.copy(serverId = "server-2")))
        assertNull(projection.countFor(requestOwner.copy(accountId = "other")))
        scope.cancel()
    }

    @Test
    fun unavailableSummaryClearsActivityKnowledgeButKeepsLegacyUnreadFallback() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sessionStore = InMemorySlockAuthSessionStore(sampleSession())
        val owner = ReadReceiptOwner("https://raft.example", "user-1", "server-1")
        var snapshot =
            sampleHome(ServerId("server-1")).copy(
                serverUnreadCounts = mapOf("server-1" to 21, "server-2" to 6),
                serverActivityUnreadCounts = mapOf("server-1" to 0, "server-2" to 3),
            )
        val source =
            SharedHomeDataSource(
                sessionStore = sessionStore,
                api = RecordingHomeApi(),
                parseHome = { _, _ -> DataResult.Content(snapshot) },
            )
        val store =
            SharedHomeStore(
                authStore = sessionStore,
                dataSource = source,
                scope = scope,
                readReceiptOwnerFactory = { serverId, accountId ->
                    ReadReceiptOwner("https://raft.example", accountId, serverId)
                },
            )

        store.accept(SharedHomeIntent.Start)
        assertEquals(3L, store.state.value.serverActivityUnread.countFor(owner.copy(serverId = "server-2")))

        snapshot =
            snapshot.copy(
                serverActivityUnreadCounts = emptyMap(),
                serverPushMutedByServer = emptyMap(),
                serverUnreadSummaryAvailable = false,
                unreadEnrichmentAvailable = false,
            )
        store.accept(SharedHomeIntent.ForegroundRefresh)

        assertNull(store.state.value.serverActivityUnread.countFor(owner.copy(serverId = "server-2")))
        assertEquals(6, store.state.value.serverUnreadCounts["server-2"])
        scope.cancel()
    }

    private fun source(
        api: SlockHomeApi,
        announcementsApi: SlockAnnouncementsRemindersApi? = null,
        computersApi: SlockComputersApi? = null,
        sessionStore: SlockAuthSessionStore = InMemorySlockAuthSessionStore(sampleSession()),
        appUpdateStatusProvider: (suspend () -> DataResult<SharedSettingsUpdateCheckSnapshot>)? = null,
        offlineComputerAlertsEnabled: Boolean = true,
    ): SharedHomeDataSource =
        SharedHomeDataSource(
            sessionStore = sessionStore,
            api = api,
            announcementsApi = announcementsApi,
            computersApi = computersApi,
            appUpdateStatusProvider = appUpdateStatusProvider,
            notificationPreferenceRepository =
                object : NotificationPreferenceRepository {
                    override suspend fun getNotificationPreference(): NotificationPreferenceSnapshot =
                        NotificationPreferenceSnapshot(
                            enabled = true,
                            offlineComputerAlertsEnabled = offlineComputerAlertsEnabled,
                        )

                    override suspend fun setNotificationEnabled(enabled: Boolean): NotificationPreferenceSnapshot =
                        error("unused")

                    override suspend fun setForegroundKeepAliveEnabled(enabled: Boolean): NotificationPreferenceSnapshot =
                        error("unused")

                    override suspend fun setOfflineComputerAlertsEnabled(enabled: Boolean): NotificationPreferenceSnapshot =
                        error("unused")
                },
            parseHome = { payload, _ -> DataResult.Content(sampleHome(payload.serverId)) }
        )

    private fun sourceWithSnapshot(
        snapshot: HomeSnapshot,
        api: SlockHomeApi = RecordingHomeApi(),
        sessionStore: SlockAuthSessionStore = InMemorySlockAuthSessionStore(sampleSession()),
        localStore: ComposeLocalDataStore? = null,
        logger: (String) -> Unit = {},
        serverMembersStore: SharedServerMembersStore? = null
    ): SharedHomeDataSource =
        SharedHomeDataSource(
            sessionStore = sessionStore,
            api = api,
            serverMembersStore = serverMembersStore,
            parseHome = { _, _ -> DataResult.Content(snapshot) }
        )

    private class RecordingHomeApi : SlockHomeApi {
        val calls = mutableListOf<String>()
        private var channelBlock: BlockingCall? = null
        private val channelBlocksByServer = mutableMapOf<String, BlockingCall>()

        fun blockNextChannels(): BlockingCall =
            BlockingCall().also { channelBlock = it }

        fun blockNextChannels(serverId: String): BlockingCall =
            BlockingCall().also { channelBlocksByServer[serverId] = it }

        override suspend fun channels(priority: DataRequestPriority): HomeNetworkResponse =
            error("server-specific overload should be used")

        override suspend fun directMessages(priority: DataRequestPriority): HomeNetworkResponse =
            error("server-specific overload should be used")

        override suspend fun channelUnreadCounts(priority: DataRequestPriority): HomeNetworkResponse =
            error("server-specific overload should be used")

        override suspend fun serverUnreadSummary(priority: DataRequestPriority): HomeNetworkResponse =
            error("server-specific overload should be used")

        override suspend fun channels(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse {
            channelBlocksByServer.remove(serverId.value)?.let { block ->
                block.started.complete(Unit)
                block.released.await()
            }
            channelBlock?.let { block ->
                channelBlock = null
                block.started.complete(Unit)
                block.released.await()
            }
            return ok("channels", serverId)
        }

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
            return HomeNetworkResponse(
                code = 200,
                bodyText =
                    unreadByServerId.entries.joinToString(
                        prefix = "[",
                        postfix = "]"
                    ) { (id, unread) ->
                        """{"serverId":"$id","unreadCount":$unread}"""
                    }
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
        @Volatile private var permissionDenied: Boolean = false

        fun setServerUnread(vararg pairs: Pair<String, Int>) {
            unreadByServerId.clear()
            unreadByServerId.putAll(pairs)
            failing = false
            permissionDenied = false
        }

        fun setFailing(value: Boolean) {
            failing = value
        }

        fun setPermissionDenied(value: Boolean) {
            permissionDenied = value
        }

        private fun responseOrThrow(bodyText: String): HomeNetworkResponse {
            if (failing) throw IllegalStateException("offline")
            return if (permissionDenied) {
                HomeNetworkResponse(code = 403, bodyText = "{}")
            } else {
                HomeNetworkResponse(code = 200, bodyText = bodyText)
            }
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
            return responseOrThrow(
                    """
                    [
                      {"id":"${serverId.value}-channel","name":"${serverId.value} channel","type":"channel","joined":true}
                    ]
                    """.trimIndent()
            )
        }

        override suspend fun directMessages(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse {
            return responseOrThrow("[]")
        }

        override suspend fun channelUnreadCounts(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse {
            return responseOrThrow("""{"${serverId.value}-channel":1}""")
        }

        override suspend fun serverUnreadSummary(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse {
            callCount++
            val unread = unreadByServerId[serverId.value] ?: 0
            return responseOrThrow(
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

    private class RecordingAnnouncementsApi(
        var activeBody: String =
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
    ) : SlockAnnouncementsRemindersApi {
        val calls = mutableListOf<String>()

        override suspend fun activeAnnouncements(): HomeNetworkResponse {
            calls += "GET announcements/active"
            return HomeNetworkResponse(
                code = 200,
                bodyText = activeBody
            )
        }

        override suspend fun dismissAnnouncement(announcementId: String): HomeNetworkResponse {
            calls += "POST announcements/$announcementId/dismiss"
            return HomeNetworkResponse(code = 200, bodyText = """{"ok":true}""")
        }

        override suspend fun reminders(ownerAgentId: MemberId, status: String): HomeNetworkResponse =
            error("not reached")
    }

    private class RecordingComputersApi(
        private val bodyText: String =
            """
            {
              "machines": [
                {
                  "id": "machine-1",
                  "name": "OranixSlockTest",
                  "status": "offline",
                  "statusVersion": 1
                }
              ]
            }
            """.trimIndent()
    ) : SlockComputersApi {
        val calls = mutableListOf<String>()

        override suspend fun machines(serverId: ServerId): HomeNetworkResponse {
            calls += "GET machines:${serverId.value}"
            return HomeNetworkResponse(
                code = 200,
                bodyText = bodyText
            )
        }

        override suspend fun registerMachine(serverId: ServerId, body: String): HomeNetworkResponse =
            error("not reached")

        override suspend fun renameMachine(serverId: ServerId, machineId: String, body: String): HomeNetworkResponse =
            error("not reached")

        override suspend fun deleteMachine(serverId: ServerId, machineId: String): HomeNetworkResponse =
            error("not reached")

        override suspend fun rotateMachineKey(serverId: ServerId, machineId: String): HomeNetworkResponse =
            error("not reached")

        override suspend fun machineWorkspaces(serverId: ServerId, machineId: String): HomeNetworkResponse =
            error("not reached")

        override suspend fun runtimeModels(serverId: ServerId, machineId: String, runtime: String): HomeNetworkResponse =
            error("not reached")

        override suspend fun deleteMachineWorkspace(
            serverId: ServerId,
            machineId: String,
            directoryName: String
        ): HomeNetworkResponse =
            error("not reached")
    }

    private class BlockingComputersApi : SlockComputersApi {
        private val started = CompletableDeferred<Unit>()
        private val released = CompletableDeferred<Unit>()
        var calls: Int = 0
            private set

        suspend fun awaitStarted() = started.await()

        fun release() {
            released.complete(Unit)
        }

        override suspend fun machines(serverId: ServerId): HomeNetworkResponse {
            calls += 1
            started.complete(Unit)
            released.await()
            return HomeNetworkResponse(
                code = 200,
                bodyText =
                    """{"machines":[{"id":"machine-1","name":"Old owner machine","status":"offline","statusVersion":1}]}"""
            )
        }

        override suspend fun registerMachine(serverId: ServerId, body: String): HomeNetworkResponse = error("not reached")
        override suspend fun renameMachine(serverId: ServerId, machineId: String, body: String): HomeNetworkResponse = error("not reached")
        override suspend fun deleteMachine(serverId: ServerId, machineId: String): HomeNetworkResponse = error("not reached")
        override suspend fun rotateMachineKey(serverId: ServerId, machineId: String): HomeNetworkResponse = error("not reached")
        override suspend fun machineWorkspaces(serverId: ServerId, machineId: String): HomeNetworkResponse = error("not reached")
        override suspend fun runtimeModels(serverId: ServerId, machineId: String, runtime: String): HomeNetworkResponse = error("not reached")
        override suspend fun deleteMachineWorkspace(
            serverId: ServerId,
            machineId: String,
            directoryName: String
        ): HomeNetworkResponse = error("not reached")
    }

    private class NotificationPublicationBarrier {
        private var armed = false
        private var started = CompletableDeferred<Unit>()
        private var released = CompletableDeferred<Unit>()

        fun arm() {
            armed = true
            started = CompletableDeferred()
            released = CompletableDeferred()
        }

        suspend fun intercept() {
            if (!armed) return
            armed = false
            started.complete(Unit)
            released.await()
        }

        suspend fun awaitStarted() = started.await()

        fun release() {
            released.complete(Unit)
        }
    }

    private fun sampleSession(
        role: String? = "owner",
        secondServerRole: String? = role
    ): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "artin@slock.ai", displayName = "Artin"),
            accessToken = "token-a",
            refreshToken = "token-r",
            selectedServerId = "server-1",
            servers =
                listOf(
                    StoredAuthServer(id = "server-1", name = "Slock Community", slug = "slock", role = role),
                    StoredAuthServer(id = "server-2", name = "Work", slug = "work", role = secondServerRole)
                )
        )

    private fun updateAvailableSnapshot(): SharedSettingsUpdateCheckSnapshot =
        SharedSettingsUpdateCheckSnapshot(
            supported = true,
            updateAvailable = true,
            currentVersionCode = 1L,
            latestVersionCode = 2L
        )

    private fun appUpdateNotification(): SharedHomeNotification =
        updateAvailableSnapshot().toSharedHomeAppUpdateNotification()!!

    private fun otherNotification(): SharedHomeNotification =
        SharedHomeNotification(
            id = "announcement:other",
            title = "Other notification",
            body = "Must survive machine authority changes.",
            actionLabel = "View",
            targetId = "other"
        )

    private fun sampleHome(serverId: ServerId): HomeSnapshot =
        HomeSnapshot(
            server = ServerSummary(serverId, if (serverId.value == "server-2") "Work" else "Slock Community"),
            currentMember = sampleMember(),
            channels = listOf(channel("channel-1", "general")),
            directMessages = emptyList(),
            unreadTotal = 0
        )

    private fun channel(id: String, name: String): HomeChannelRow =
        HomeChannelRow(
            threadId = ThreadId(id),
            channelId = ChannelId(id),
            name = name
        )

    private fun supportedActivityPreference(): ChannelActivityNotificationPreference =
        ChannelActivityNotificationPreference(activityMuteSupported = true)

    private fun directMessage(
        id: String,
        name: String,
        memberId: String,
        presence: Presence
    ): HomeDirectMessageRow =
        HomeDirectMessageRow(
            threadId = ThreadId(id),
            channelId = ChannelId(id),
            name = name,
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
            loadSidebarOrder = { _ ->
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
    fun firstHomePaintCarriesConcurrentSidebarOrderWhenFetchIsFast() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sidebarOrder = SettingsServerSidebarOrderSnapshot(channelOrder = listOf("channel-pinned"))
        val store =
            SharedHomeStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                dataSource = source(api = RecordingHomeApi()),
                scope = scope,
                loadSidebarOrder = { _ -> DataResult.Content(sidebarOrder) }
            )

        store.accept(SharedHomeIntent.Start)
        val content = store.state.value.content as SharedHomeContentState.Content
        assertEquals(
            listOf("channel-pinned"),
            content.snapshot.sidebarOrder?.channelOrder,
            "a fast sidebar-order fetch must land in the first home paint, not append after it"
        )
        scope.cancel()
    }

    @Test
    fun sidebarOrderFetchLateStillMergesFromSameRequestWithoutRefetch() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var loadCalls = 0
        val gate = CompletableDeferred<DataResult<SettingsServerSidebarOrderSnapshot>>()
        val store =
            SharedHomeStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                dataSource = source(api = RecordingHomeApi()),
                scope = scope,
                loadSidebarOrder = { _ ->
                    loadCalls += 1
                    gate.await()
                }
            )

        val startJob = scope.launch { store.accept(SharedHomeIntent.Start) }
        // The 300ms first-paint cap elapses: home publishes channels alone.
        awaitCondition {
            (store.state.value.content as? SharedHomeContentState.Content)?.snapshot != null
        }
        assertNull((store.state.value.content as SharedHomeContentState.Content).snapshot.sidebarOrder)

        // When the in-flight request resolves, the deferred result merges under the
        // sidebarOrderMutex — same request, no second fetch.
        gate.complete(DataResult.Content(SettingsServerSidebarOrderSnapshot(channelOrder = listOf("late-pinned"))))
        startJob.join()
        awaitCondition {
            (store.state.value.content as? SharedHomeContentState.Content)
                ?.snapshot?.sidebarOrder?.channelOrder == listOf("late-pinned")
        }
        assertEquals(1, loadCalls, "the late-arriving sidebar must reuse the in-flight request, not refetch")
        scope.cancel()
    }

    @Test
    fun pinDuringConcurrentSidebarOrderFetchDiscardsTheLateStaleResult() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var loadCalls = 0
        // First call = the concurrent first-paint fetch; it stays blocked past the
        // 300ms cap so home publishes channels alone. Later calls (the Pin's own
        // ensureSidebarOrderLoaded fetch while the deferred is still in flight)
        // return an empty base synchronously — no suspension inside sidebarOrderMutex.
        val firstPaintGate = CompletableDeferred<DataResult<SettingsServerSidebarOrderSnapshot>>()
        val pinBase = SettingsServerSidebarOrderSnapshot(pinnedChannelIds = emptyList())
        val pinnedSnapshot = pinBase.copy(pinnedChannelIds = listOf("channel-1"), pinnedOrder = listOf("channel-1"))
        val store =
            SharedHomeStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                dataSource = source(api = RecordingHomeApi()),
                scope = scope,
                loadSidebarOrder = { _ ->
                    loadCalls += 1
                    if (loadCalls == 1) firstPaintGate.await() else DataResult.Content(pinBase)
                },
                updateSidebarOrder = { _ -> DataResult.Content(pinnedSnapshot) }
            )

        val startJob = scope.launch { store.accept(SharedHomeIntent.Start) }
        // The 300ms first-paint cap elapses: home publishes channels alone.
        awaitCondition {
            (store.state.value.content as? SharedHomeContentState.Content)?.snapshot != null &&
                (store.state.value.content as? SharedHomeContentState.Content)
                    ?.snapshot?.sidebarOrder == null
        }
        assertNull(
            (store.state.value.content as SharedHomeContentState.Content).snapshot.sidebarOrder
        )

        // The Pin lands while the deferred is still in flight: ensureSidebarOrderLoaded
        // finds no cached order, fetches the empty base under sidebarOrderMutex, then
        // the optimistic apply bumps sidebarPinChangeVersion and writes the pin.
        store.accept(SharedHomeIntent.TogglePinned("channel-1"))
        awaitCondition {
            (store.state.value.content as? SharedHomeContentState.Content)
                ?.snapshot?.sidebarOrder?.pinnedChannelIds == listOf("channel-1")
        }

        // The late-arriving first-paint result does NOT contain the pin (it was
        // fetched before the mutation). It must be dropped inside sidebarOrderMutex
        // because sidebarPinChangeVersion changed — not clobber the pin.
        firstPaintGate.complete(DataResult.Content(SettingsServerSidebarOrderSnapshot(pinnedChannelIds = emptyList())))
        startJob.join()
        awaitCondition { loadCalls >= 2 }
        // Give the spawned late-merge coroutine a beat to run under the mutex, then
        // assert the pin survived and the stale result was discarded.
        delay(50)
        val after =
            (store.state.value.content as SharedHomeContentState.Content).snapshot.sidebarOrder
        assertEquals(
            listOf("channel-1"),
            after?.pinnedChannelIds,
            "a pin dispatched while the deferred was in flight must survive the late stale merge"
        )
        assertEquals(
            listOf("channel-1"),
            after?.pinnedOrder,
            "the pin must not be clobbered by the pre-mutation sidebar-order result"
        )
        scope.cancel()
    }

    @Test
    fun persistedHomeSnapshotKeepsSidebarOrderForColdStartPaint() {
        // task #322: artin's "下次启动一次出齐" requirement — the first-paint snapshot that
        // carries a concurrently-fetched sidebarOrder must keep it through the durable
        // persist slice, so a cold start reads the pinned section back out with channels.
        val snapshot =
            sampleHome(ServerId("server-1")).copy(
                sidebarOrder =
                    SettingsServerSidebarOrderSnapshot(
                        channelOrder = listOf("channel-pinned"),
                        pinnedChannelIds = listOf("channel-pinned")
                    )
            )
        val pruned = snapshot.pruneForHomeIdentityPersist()
        assertEquals(listOf("channel-pinned"), pruned.sidebarOrder?.channelOrder)
        assertEquals(listOf("channel-pinned"), pruned.sidebarOrder?.pinnedChannelIds)
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
            loadSidebarOrder = { _ -> DataResult.Content(initial) },
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
        assertEquals(listOf(SettingsServerSidebarPinnedItem(kind = "channel", id = "channel-1")), updateCalls.first().pinned)
        assertEquals(null, updateCalls.first().pinnedChannelIds)
        assertEquals(null, updateCalls.first().pinnedAgentIds)
        assertEquals(null, updateCalls.first().pinnedOrder)
        scope.cancel()
    }

    @Test
    fun togglePinnedAgentDirectMessagePatchesPinnedAgentIds() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val initial = SettingsServerSidebarOrderSnapshot(
            pinnedChannelIds = emptyList(),
            pinnedAgentIds = emptyList(),
            pinnedOrder = emptyList(),
            pinnedSortMode = "manual"
        )
        val updateCalls = mutableListOf<SettingsServerSidebarOrderUpdateRequest>()
        val updatedSnapshot = initial.copy(
            pinnedAgentIds = listOf("agent-1"),
            pinnedOrder = listOf("agent-1")
        )
        val home =
            sampleHome(ServerId("server-1")).copy(
                directMessages =
                    listOf(
                        directMessage("dm-agent-1", "Agent One", "agent-1", Presence.Active)
                            .copy(directMessagePeerType = "agent")
                    ),
                sidebarOrder = initial
            )
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = sourceWithSnapshot(home),
            scope = scope,
            loadSidebarOrder = { _ -> DataResult.Content(initial) },
            updateSidebarOrder = { request ->
                updateCalls += request
                DataResult.Content(updatedSnapshot)
            }
        )

        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.TogglePinned("dm-agent-1"))

        val after = (store.state.value.content as SharedHomeContentState.Content).snapshot.sidebarOrder
        assertEquals(emptyList(), after?.pinnedChannelIds)
        assertEquals(listOf("agent-1"), after?.pinnedAgentIds)
        assertEquals(listOf("agent-1"), after?.pinnedOrder)
        assertEquals(1, updateCalls.size)
        assertEquals(listOf(SettingsServerSidebarPinnedItem(kind = "agent", id = "agent-1")), updateCalls.first().pinned)
        assertEquals(null, updateCalls.first().pinnedChannelIds)
        assertEquals(null, updateCalls.first().pinnedAgentIds)
        assertEquals(null, updateCalls.first().pinnedOrder)
        scope.cancel()
    }

    @Test
    fun togglePinnedAgentDirectMessageUnpinsLegacyMixedFamilies() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val initial = SettingsServerSidebarOrderSnapshot(
            pinnedChannelIds = listOf("dm-agent-1"),
            pinnedAgentIds = listOf("agent-1"),
            pinnedOrder = listOf("dm-agent-1", "agent-1"),
            pinnedSortMode = "manual"
        )
        val updateCalls = mutableListOf<SettingsServerSidebarOrderUpdateRequest>()
        val updatedSnapshot = initial.copy(
            pinnedChannelIds = emptyList(),
            pinnedAgentIds = emptyList(),
            pinnedOrder = emptyList()
        )
        val home =
            sampleHome(ServerId("server-1")).copy(
                directMessages =
                    listOf(
                        directMessage("dm-agent-1", "Agent One", "agent-1", Presence.Active)
                            .copy(directMessagePeerType = "agent")
                    ),
                sidebarOrder = initial
            )
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = sourceWithSnapshot(home),
            scope = scope,
            loadSidebarOrder = { _ -> DataResult.Content(initial) },
            updateSidebarOrder = { request ->
                updateCalls += request
                DataResult.Content(updatedSnapshot)
            }
        )

        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.TogglePinned("dm-agent-1"))

        val after = (store.state.value.content as SharedHomeContentState.Content).snapshot.sidebarOrder
        assertEquals(emptyList(), after?.pinnedChannelIds)
        assertEquals(emptyList(), after?.pinnedAgentIds)
        assertEquals(emptyList(), after?.pinnedOrder)
        assertEquals(1, updateCalls.size)
        assertEquals(emptyList(), updateCalls.first().pinned)
        assertEquals(null, updateCalls.first().pinnedChannelIds)
        assertEquals(null, updateCalls.first().pinnedAgentIds)
        assertEquals(null, updateCalls.first().pinnedOrder)
        scope.cancel()
    }

    @Test
    fun togglePinnedHumanDirectMessagePatchesTypedHumanPinnedItem() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val initial = SettingsServerSidebarOrderSnapshot(
            pinnedChannelIds = emptyList(),
            pinnedAgentIds = emptyList(),
            pinnedOrder = emptyList(),
            pinnedSortMode = "manual"
        )
        val updateCalls = mutableListOf<SettingsServerSidebarOrderUpdateRequest>()
        val updatedSnapshot = initial.copy(
            pinned = listOf(SettingsServerSidebarPinnedItem(kind = "human", id = "user-1")),
            pinnedOrder = listOf("user-1")
        )
        val home =
            sampleHome(ServerId("server-1")).copy(
                directMessages =
                    listOf(
                        directMessage("dm-user-1", "Artin", "user-1", Presence.Active)
                            .copy(directMessagePeerType = "user")
                    ),
                sidebarOrder = initial
            )
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = sourceWithSnapshot(home),
            scope = scope,
            loadSidebarOrder = { _ -> DataResult.Content(initial) },
            updateSidebarOrder = { request ->
                updateCalls += request
                DataResult.Content(updatedSnapshot)
            }
        )

        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.TogglePinned("dm-user-1"))

        val after = (store.state.value.content as SharedHomeContentState.Content).snapshot.sidebarOrder
        assertEquals(emptyList(), after?.pinnedChannelIds)
        assertEquals(emptyList(), after?.pinnedAgentIds)
        assertEquals(listOf("user-1"), after?.pinnedOrder)
        assertEquals(1, updateCalls.size)
        assertEquals(listOf(SettingsServerSidebarPinnedItem(kind = "human", id = "user-1")), updateCalls.first().pinned)
        assertEquals(null, updateCalls.first().pinnedChannelIds)
        assertEquals(null, updateCalls.first().pinnedAgentIds)
        assertEquals(null, updateCalls.first().pinnedOrder)
        scope.cancel()
    }

    @Test
    fun togglePinnedSearchMemberWithoutDirectMessageUsesExplicitTypedIdentity() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val initial = SettingsServerSidebarOrderSnapshot(pinnedSortMode = "manual")
        val updateCalls = mutableListOf<SettingsServerSidebarOrderUpdateRequest>()
        val expectedPinned = listOf(SettingsServerSidebarPinnedItem(kind = "agent", id = "agent-no-dm"))
        val updatedSnapshot = initial.copy(pinned = expectedPinned, pinnedOrder = listOf("agent-no-dm"))
        val home = sampleHome(ServerId("server-1")).copy(directMessages = emptyList(), sidebarOrder = initial)
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = sourceWithSnapshot(home),
            scope = scope,
            loadSidebarOrder = { _ -> DataResult.Content(initial) },
            updateSidebarOrder = { request ->
                updateCalls += request
                DataResult.Content(updatedSnapshot)
            }
        )

        store.accept(SharedHomeIntent.Start)
        store.accept(
            SharedHomeIntent.TogglePinned(
                conversationId = "agent-no-dm",
                pinnedKind = "agent",
                pinnedId = "agent-no-dm"
            )
        )

        assertEquals(expectedPinned, updateCalls.single().pinned)
        assertEquals(expectedPinned, (store.state.value.content as SharedHomeContentState.Content).snapshot.sidebarOrder?.pinned)
        scope.cancel()
    }

    @Test
    fun togglePinnedWritesSidebarOrderToHomeIdentityPersist() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val storage = InMemorySlockKeyValueStore()
        val persistSlice =
            createSharedHomeIdentityPersistSlice(
                storage = SharedPreferencePersistStorage(storage)
            )
        val initial = SettingsServerSidebarOrderSnapshot(
            pinnedChannelIds = emptyList(),
            pinnedOrder = emptyList(),
            pinnedSortMode = "manual"
        )
        val updatedSnapshot = initial.copy(
            pinnedChannelIds = listOf("channel-1"),
            pinnedOrder = listOf("channel-1")
        )
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = sourceWithSidebarOrder(initial = initial),
            scope = scope,
            loadSidebarOrder = { _ -> DataResult.Content(initial) },
            updateSidebarOrder = { DataResult.Content(updatedSnapshot) },
            persistSlices = listOf(persistSlice)
        )

        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.TogglePinned("channel-1"))

        val sessionStore = InMemorySlockAuthSessionStore(sampleSession())
        val api = BlockingHomeApi()
        val coldStore =
            SharedHomeStore(
                authStore = sessionStore,
                dataSource = source(api = api, sessionStore = sessionStore),
                scope = scope,
                persistSlices =
                    listOf(
                        createSharedHomeIdentityPersistSlice(
                            storage = SharedPreferencePersistStorage(storage)
                        )
                    )
            )
        val blockedLoad = api.blockNextChannels()
        val loadJob = launch { coldStore.accept(SharedHomeIntent.Start) }
        blockedLoad.awaitStarted()

        val hydratedContent = coldStore.state.value.content
        assertTrue(hydratedContent is SharedHomeContentState.Content)
        assertEquals(listOf("channel-1"), hydratedContent.snapshot.sidebarOrder?.pinnedChannelIds)
        assertEquals(listOf("channel-1"), hydratedContent.snapshot.sidebarOrder?.pinnedOrder)

        blockedLoad.release()
        loadJob.join()
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
            loadSidebarOrder = { _ -> DataResult.Content(existingSnapshot) },
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
        assertEquals(
            listOf(
                SettingsServerSidebarPinnedItem(kind = "channel", id = "channel-2"),
                SettingsServerSidebarPinnedItem(kind = "channel", id = "channel-1")
            ),
            updateCalls.first().pinned
        )
        assertEquals(null, updateCalls.first().pinnedChannelIds)
        assertEquals(null, updateCalls.first().pinnedAgentIds)
        assertEquals(null, updateCalls.first().pinnedOrder)
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
            loadSidebarOrder = { _ -> DataResult.Content(initial) },
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
            loadSidebarOrder = { _ -> DataResult.Error(ScreenError(code = "sidebar_order_load_failed", retryable = true)) },
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
            loadSidebarOrder = { _ -> DataResult.Content(initial) },
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
            loadSidebarOrder = { _ -> DataResult.Content(initial) },
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

    // task #512: custom sections (web channel-sections port) -----------------

    @Test
    fun createSidebarSectionPatchesCustomSectionsWithOccVersion() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val initial = SettingsServerSidebarOrderSnapshot(sectionsVersion = 3)
        val updateCalls = mutableListOf<SettingsServerSidebarOrderUpdateRequest>()
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = sourceWithSidebarOrder(initial = initial),
            scope = scope,
            loadSidebarOrder = { _ -> DataResult.Content(initial) },
            updateSidebarOrder = { request ->
                updateCalls += request
                DataResult.Content(
                    initial.copy(
                        customSections = request.customSections.orEmpty(),
                        sectionOrder = request.sectionOrder.orEmpty(),
                        sectionsVersion = 4
                    )
                )
            }
        )

        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.CreateSidebarSection(name = "My Project", emoji = "🚀"))

        assertEquals(1, updateCalls.size)
        val request = updateCalls.first()
        assertEquals(3L, request.sectionsVersion)
        val created = request.customSections.orEmpty().single()
        assertTrue(created.id.isNotBlank())
        assertEquals("My Project", created.name)
        assertEquals("🚀", created.emoji)
        assertEquals("manual", created.sortMode)
        assertEquals(SIDEBAR_SYSTEM_SECTION_IDS + created.id, request.sectionOrder)
        val after = (store.state.value.content as SharedHomeContentState.Content).snapshot.sidebarOrder
        assertEquals(4L, after?.sectionsVersion)
        assertEquals(listOf(created), after?.customSections)
        scope.cancel()
    }

    @Test
    fun renameSidebarSectionPatchesCustomSections() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val section = SettingsServerSidebarCustomSection(id = "sec-1", name = "Old", emoji = null, sortMode = "manual")
        val initial =
            SettingsServerSidebarOrderSnapshot(
                customSections = listOf(section),
                sectionOrder = SIDEBAR_SYSTEM_SECTION_IDS + "sec-1",
                sectionsVersion = 2
            )
        val updateCalls = mutableListOf<SettingsServerSidebarOrderUpdateRequest>()
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = sourceWithSidebarOrder(initial = initial),
            scope = scope,
            loadSidebarOrder = { _ -> DataResult.Content(initial) },
            updateSidebarOrder = { request ->
                updateCalls += request
                DataResult.Content(initial.copy(customSections = request.customSections.orEmpty(), sectionsVersion = 3))
            }
        )

        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.RenameSidebarSection(sectionId = "sec-1", name = "New Name", emoji = "📌"))

        assertEquals(1, updateCalls.size)
        assertEquals(2L, updateCalls.first().sectionsVersion)
        assertEquals(
            listOf(section.copy(name = "New Name", emoji = "📌")),
            updateCalls.first().customSections
        )
        val after = (store.state.value.content as SharedHomeContentState.Content).snapshot.sidebarOrder
        assertEquals("New Name", after?.customSections?.single()?.name)
        assertEquals(3L, after?.sectionsVersion)
        scope.cancel()
    }

    @Test
    fun deleteSidebarSectionDropsOrderAndPlacements() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val section = SettingsServerSidebarCustomSection(id = "sec-1", name = "Doomed", sortMode = "manual")
        val keep = SettingsServerSidebarCustomSection(id = "sec-2", name = "Keep", sortMode = "az")
        val placement =
            SettingsServerSidebarSectionPlacement(kind = "channel", id = "channel-1", sectionId = "sec-1", position = 0)
        val initial =
            SettingsServerSidebarOrderSnapshot(
                customSections = listOf(section, keep),
                sectionOrder = SIDEBAR_SYSTEM_SECTION_IDS + listOf("sec-1", "sec-2"),
                sectionPlacements = listOf(placement),
                sectionsVersion = 6
            )
        val updateCalls = mutableListOf<SettingsServerSidebarOrderUpdateRequest>()
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = sourceWithSidebarOrder(initial = initial),
            scope = scope,
            loadSidebarOrder = { _ -> DataResult.Content(initial) },
            updateSidebarOrder = { request ->
                updateCalls += request
                DataResult.Content(
                    initial.copy(
                        customSections = request.customSections.orEmpty(),
                        sectionOrder = request.sectionOrder.orEmpty(),
                        sectionPlacements = request.sectionPlacements.orEmpty(),
                        sectionsVersion = 7
                    )
                )
            }
        )

        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.DeleteSidebarSection("sec-1"))

        assertEquals(1, updateCalls.size)
        val request = updateCalls.first()
        assertEquals(6L, request.sectionsVersion)
        assertEquals(listOf(keep), request.customSections)
        assertEquals(SIDEBAR_SYSTEM_SECTION_IDS + "sec-2", request.sectionOrder)
        assertEquals(emptyList(), request.sectionPlacements)
        val after = (store.state.value.content as SharedHomeContentState.Content).snapshot.sidebarOrder
        assertEquals(listOf(keep), after?.customSections)
        assertEquals(emptyList(), after?.sectionPlacements)
        scope.cancel()
    }

    @Test
    fun setSidebarSectionSortModePatchesSortMode() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val section = SettingsServerSidebarCustomSection(id = "sec-1", name = "Project", sortMode = "manual")
        val initial =
            SettingsServerSidebarOrderSnapshot(
                customSections = listOf(section),
                sectionOrder = SIDEBAR_SYSTEM_SECTION_IDS + "sec-1",
                sectionsVersion = 1
            )
        val updateCalls = mutableListOf<SettingsServerSidebarOrderUpdateRequest>()
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = sourceWithSidebarOrder(initial = initial),
            scope = scope,
            loadSidebarOrder = { _ -> DataResult.Content(initial) },
            updateSidebarOrder = { request ->
                updateCalls += request
                DataResult.Content(initial.copy(customSections = request.customSections.orEmpty(), sectionsVersion = 2))
            }
        )

        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.SetSidebarSectionSortMode("sec-1", SlockSidebarSortMode.Az))

        assertEquals(1, updateCalls.size)
        assertEquals(1L, updateCalls.first().sectionsVersion)
        assertEquals(listOf(section.copy(sortMode = "az")), updateCalls.first().customSections)
        val after = (store.state.value.content as SharedHomeContentState.Content).snapshot.sidebarOrder
        assertEquals("az", after?.customSections?.single()?.sortMode)
        scope.cancel()
    }

    @Test
    fun moveConversationToSectionMovesPlacementAndUnpins() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val section = SettingsServerSidebarCustomSection(id = "sec-1", name = "Project", sortMode = "manual")
        val initial =
            SettingsServerSidebarOrderSnapshot(
                pinned = listOf(SettingsServerSidebarPinnedItem(kind = "channel", id = "channel-1")),
                pinnedChannelIds = listOf("channel-1"),
                pinnedOrder = listOf("channel-1"),
                customSections = listOf(section),
                sectionOrder = SIDEBAR_SYSTEM_SECTION_IDS + "sec-1",
                sectionsVersion = 2
            )
        val updateCalls = mutableListOf<SettingsServerSidebarOrderUpdateRequest>()
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = sourceWithSidebarOrder(initial = initial),
            scope = scope,
            loadSidebarOrder = { _ -> DataResult.Content(initial) },
            updateSidebarOrder = { request ->
                updateCalls += request
                DataResult.Content(
                    initial.copy(
                        pinned = request.pinned.orEmpty(),
                        sectionPlacements = request.sectionPlacements.orEmpty(),
                        sectionsVersion = 3
                    )
                )
            }
        )

        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.MoveConversationToSection(conversationId = "channel-1", sectionId = "sec-1"))

        assertEquals(1, updateCalls.size)
        val request = updateCalls.first()
        assertEquals(2L, request.sectionsVersion)
        // Web parity (Sidebar.tsx moveItemToCustomSection): moving into a custom
        // section unpins the conversation in the same PATCH.
        assertEquals(emptyList(), request.pinned)
        assertEquals(
            listOf(SettingsServerSidebarSectionPlacement(kind = "channel", id = "channel-1", sectionId = "sec-1", position = 0)),
            request.sectionPlacements
        )
        val after = (store.state.value.content as SharedHomeContentState.Content).snapshot.sidebarOrder
        assertEquals(emptyList(), after?.pinned)
        assertEquals(3L, after?.sectionsVersion)
        scope.cancel()
    }

    @Test
    fun moveConversationToDefaultSectionRemovesPlacement() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val section = SettingsServerSidebarCustomSection(id = "sec-1", name = "Project", sortMode = "manual")
        val placement =
            SettingsServerSidebarSectionPlacement(kind = "channel", id = "channel-1", sectionId = "sec-1", position = 0)
        val initial =
            SettingsServerSidebarOrderSnapshot(
                customSections = listOf(section),
                sectionOrder = SIDEBAR_SYSTEM_SECTION_IDS + "sec-1",
                sectionPlacements = listOf(placement),
                sectionsVersion = 4
            )
        val updateCalls = mutableListOf<SettingsServerSidebarOrderUpdateRequest>()
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = sourceWithSidebarOrder(initial = initial),
            scope = scope,
            loadSidebarOrder = { _ -> DataResult.Content(initial) },
            updateSidebarOrder = { request ->
                updateCalls += request
                DataResult.Content(initial.copy(sectionPlacements = request.sectionPlacements.orEmpty(), sectionsVersion = 5))
            }
        )

        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.MoveConversationToSection(conversationId = "channel-1", sectionId = null))

        assertEquals(1, updateCalls.size)
        assertEquals(4L, updateCalls.first().sectionsVersion)
        assertEquals(emptyList(), updateCalls.first().sectionPlacements)
        assertEquals(null, updateCalls.first().pinned)
        scope.cancel()
    }

    @Test
    fun togglePinnedRemovesCustomSectionPlacement() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val section = SettingsServerSidebarCustomSection(id = "sec-1", name = "Project", sortMode = "manual")
        val placement =
            SettingsServerSidebarSectionPlacement(kind = "channel", id = "channel-1", sectionId = "sec-1", position = 0)
        val initial =
            SettingsServerSidebarOrderSnapshot(
                customSections = listOf(section),
                sectionOrder = SIDEBAR_SYSTEM_SECTION_IDS + "sec-1",
                sectionPlacements = listOf(placement),
                sectionsVersion = 8
            )
        val updateCalls = mutableListOf<SettingsServerSidebarOrderUpdateRequest>()
        val updatedSnapshot =
            initial.copy(
                pinned = listOf(SettingsServerSidebarPinnedItem(kind = "channel", id = "channel-1")),
                sectionPlacements = emptyList(),
                sectionsVersion = 9
            )
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = sourceWithSidebarOrder(initial = initial),
            scope = scope,
            loadSidebarOrder = { _ -> DataResult.Content(initial) },
            updateSidebarOrder = { request ->
                updateCalls += request
                DataResult.Content(updatedSnapshot)
            }
        )

        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.TogglePinned("channel-1"))

        assertEquals(1, updateCalls.size)
        val request = updateCalls.first()
        assertEquals(listOf(SettingsServerSidebarPinnedItem(kind = "channel", id = "channel-1")), request.pinned)
        // Web parity (togglePinnedRef): pinning strips the custom-section
        // placement, and the PATCH carries the OCC sectionsVersion with it.
        assertEquals(emptyList(), request.sectionPlacements)
        assertEquals(8L, request.sectionsVersion)
        scope.cancel()
    }

    @Test
    fun sidebarSectionMutationConflictReloadsSidebarOrder() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val initial = SettingsServerSidebarOrderSnapshot(sectionsVersion = 1)
        val fresh =
            SettingsServerSidebarOrderSnapshot(
                customSections = listOf(SettingsServerSidebarCustomSection(id = "sec-9", name = "From Web", sortMode = "recent")),
                sectionOrder = SIDEBAR_SYSTEM_SECTION_IDS + "sec-9",
                sectionsVersion = 9
            )
        val failureMessages = mutableListOf<String>()
        var loadCalls = 0
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = sourceWithSidebarOrder(initial = initial),
            scope = scope,
            loadSidebarOrder = { _ ->
                loadCalls += 1
                DataResult.Content(fresh)
            },
            updateSidebarOrder = { DataResult.Error(ScreenError(code = "http_409", retryable = true)) },
            pinUpdateFailureCallback = { message -> failureMessages += message }
        )

        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.CreateSidebarSection(name = "Local", emoji = null))

        // Web parity (serverStore.updateSidebarOrder): a 409
        // SIDEBAR_SECTIONS_VERSION_CONFLICT reloads the sidebar order instead of
        // rolling back to the stale snapshot, and surfaces no error toast.
        val after = (store.state.value.content as SharedHomeContentState.Content).snapshot.sidebarOrder
        assertEquals(9L, after?.sectionsVersion)
        assertEquals(listOf("sec-9"), after?.customSections?.map { it.id })
        assertEquals(1, loadCalls, "the conflict path reloads the sidebar order exactly once")
        assertEquals(emptyList(), failureMessages)
        scope.cancel()
    }

    @Test
    fun moveAgentDirectMessageToSectionKeysPlacementByMemberId() = runBlocking {
        // LiBai review: agent DMs must place as kind=agent id=<memberId> —
        // the same key the UI resolves and web writes for agent items —
        // not kind=channel id=<conversationId>.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val section = SettingsServerSidebarCustomSection(id = "sec-1", name = "Agents", sortMode = "manual")
        val initial =
            SettingsServerSidebarOrderSnapshot(
                customSections = listOf(section),
                sectionOrder = SIDEBAR_SYSTEM_SECTION_IDS + "sec-1",
                sectionsVersion = 2
            )
        val home =
            sampleHome(ServerId("server-1")).copy(
                directMessages =
                    listOf(
                        directMessage("dm-agent-1", "Agent One", "agent-1", Presence.Active)
                            .copy(directMessagePeerType = "agent")
                    ),
                sidebarOrder = initial
            )
        val updateCalls = mutableListOf<SettingsServerSidebarOrderUpdateRequest>()
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = sourceWithSnapshot(home),
            scope = scope,
            loadSidebarOrder = { _ -> DataResult.Content(initial) },
            updateSidebarOrder = { request ->
                updateCalls += request
                DataResult.Content(initial.copy(sectionPlacements = request.sectionPlacements.orEmpty(), sectionsVersion = 3))
            }
        )

        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.MoveConversationToSection(conversationId = "dm-agent-1", sectionId = "sec-1"))

        assertEquals(1, updateCalls.size)
        assertEquals(
            listOf(SettingsServerSidebarSectionPlacement(kind = "agent", id = "agent-1", sectionId = "sec-1", position = 0)),
            updateCalls.first().sectionPlacements
        )
        scope.cancel()
    }

    @Test
    fun removeAgentDirectMessageFromSectionDropsTheAgentKeyedPlacement() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val section = SettingsServerSidebarCustomSection(id = "sec-1", name = "Agents", sortMode = "manual")
        val agentPlacement =
            SettingsServerSidebarSectionPlacement(kind = "agent", id = "agent-1", sectionId = "sec-1", position = 0)
        val initial =
            SettingsServerSidebarOrderSnapshot(
                customSections = listOf(section),
                sectionOrder = SIDEBAR_SYSTEM_SECTION_IDS + "sec-1",
                sectionPlacements = listOf(agentPlacement),
                sectionsVersion = 5
            )
        val home =
            sampleHome(ServerId("server-1")).copy(
                directMessages =
                    listOf(
                        directMessage("dm-agent-1", "Agent One", "agent-1", Presence.Active)
                            .copy(directMessagePeerType = "agent")
                    ),
                sidebarOrder = initial
            )
        val updateCalls = mutableListOf<SettingsServerSidebarOrderUpdateRequest>()
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = sourceWithSnapshot(home),
            scope = scope,
            loadSidebarOrder = { _ -> DataResult.Content(initial) },
            updateSidebarOrder = { request ->
                updateCalls += request
                DataResult.Content(initial.copy(sectionPlacements = request.sectionPlacements.orEmpty(), sectionsVersion = 6))
            }
        )

        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.MoveConversationToSection(conversationId = "dm-agent-1", sectionId = null))

        assertEquals(1, updateCalls.size)
        assertEquals(emptyList(), updateCalls.first().sectionPlacements)
        assertEquals(5L, updateCalls.first().sectionsVersion)
        scope.cancel()
    }

    @Test
    fun reorderChannelsPersistsManualChannelOrderAndPreservesJointIds() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val home =
            sampleHome(ServerId("server-1")).copy(
                channels =
                    listOf(
                        channel("channel-1", "general"),
                        channel("channel-2", "random"),
                        channel("joint-1", "partner").copy(kind = ConversationKind.Joint)
                    )
            )
        val initial = SettingsServerSidebarOrderSnapshot(
            channelOrder = listOf("channel-1", "joint-1", "channel-2"),
            channelSortMode = "recent"
        )
        val updateCalls = mutableListOf<SettingsServerSidebarOrderUpdateRequest>()
        val updatedSnapshot = initial.copy(
            channelOrder = listOf("channel-2", "channel-1", "joint-1"),
            channelSortMode = "manual"
        )
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource =
                SharedHomeDataSource(
                    sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                    api = RecordingHomeApi(),
                    parseHome = { _, _ -> DataResult.Content(home.copy(sidebarOrder = initial)) }
                ),
            scope = scope,
            loadSidebarOrder = { _ -> DataResult.Content(initial) },
            updateSidebarOrder = { request ->
                updateCalls += request
                DataResult.Content(updatedSnapshot)
            }
        )

        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.ReorderSidebar(channelOrder = listOf("channel-2", "channel-1")))

        val after = (store.state.value.content as SharedHomeContentState.Content).snapshot.sidebarOrder
        assertEquals(listOf("channel-2", "channel-1", "joint-1"), after?.channelOrder)
        assertEquals("manual", after?.channelSortMode)
        assertEquals(1, updateCalls.size)
        assertEquals(listOf("channel-2", "channel-1", "joint-1"), updateCalls.first().channelOrder)
        assertEquals("manual", updateCalls.first().channelSortMode)
        scope.cancel()
    }

    @Test
    fun reorderSidebarPersistsManualPinnedOrderAndKeepsUnknownIdsOut() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val home =
            sampleHome(ServerId("server-1")).copy(
                channels =
                    listOf(
                        channel("channel-1", "general"),
                        channel("channel-2", "random")
                    )
            )
        val initial = SettingsServerSidebarOrderSnapshot(
            pinnedChannelIds = listOf("channel-1", "channel-2"),
            pinnedAgentIds = listOf("agent-1"),
            pinnedOrder = listOf("channel-1", "agent-1", "channel-2"),
            pinnedSortMode = "recent"
        )
        val updateCalls = mutableListOf<SettingsServerSidebarOrderUpdateRequest>()
        val updatedSnapshot = initial.copy(
            pinnedOrder = listOf("channel-2", "channel-1", "agent-1"),
            pinnedSortMode = "manual"
        )
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource =
                SharedHomeDataSource(
                    sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                    api = RecordingHomeApi(),
                    parseHome = { _, _ -> DataResult.Content(home.copy(sidebarOrder = initial)) }
                ),
            scope = scope,
            loadSidebarOrder = { _ -> DataResult.Content(initial) },
            updateSidebarOrder = { request ->
                updateCalls += request
                DataResult.Content(updatedSnapshot)
            }
        )

        store.accept(SharedHomeIntent.Start)
        store.accept(
            SharedHomeIntent.ReorderSidebar(
                pinnedOrder = listOf("channel-2", "channel-1", "agent-1", "not-pinned")
            )
        )

        val after = (store.state.value.content as SharedHomeContentState.Content).snapshot.sidebarOrder
        assertEquals(listOf("channel-2", "channel-1", "agent-1"), after?.pinnedOrder)
        assertEquals("manual", after?.pinnedSortMode)
        assertEquals(1, updateCalls.size)
        assertEquals(
            listOf(
                SettingsServerSidebarPinnedItem(kind = "channel", id = "channel-2"),
                SettingsServerSidebarPinnedItem(kind = "channel", id = "channel-1"),
                SettingsServerSidebarPinnedItem(kind = "agent", id = "agent-1")
            ),
            updateCalls.first().pinned
        )
        assertEquals(null, updateCalls.first().pinnedOrder)
        assertEquals("manual", updateCalls.first().pinnedSortMode)
        assertEquals(null, updateCalls.first().channelOrder)
        scope.cancel()
    }

    @Test
    fun reorderServersPersistsNormalizedSessionOrder() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session =
            sampleSession().copy(
                servers =
                    sampleSession().servers +
                        StoredAuthServer(id = "server-3", name = "Design", slug = "design")
            )
        val sessionStore = InMemorySlockAuthSessionStore(session)
        val updateCalls = mutableListOf<List<String>>()
        val store = SharedHomeStore(
            authStore = sessionStore,
            dataSource = sourceWithSnapshot(sampleHome(ServerId("server-1")), sessionStore = sessionStore),
            scope = scope,
            updateServerOrder = { request ->
                updateCalls += request.serverOrder
                DataResult.Content(SettingsServerOrderSnapshot(serverOrder = listOf("server-2", "server-1")))
            }
        )

        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.ReorderServers(listOf("server-3", "server-2", "server-2", "unknown")))

        assertEquals(listOf(listOf("server-3", "server-2", "server-1")), updateCalls)
        assertEquals(listOf("server-2", "server-1", "server-3"), sessionStore.readSession()?.servers?.map { it.id })
        val after = store.state.value.content as SharedHomeContentState.Content
        assertEquals(listOf("server-2", "server-1", "server-3"), after.session.servers.map { it.id })
        scope.cancel()
    }

    @Test
    fun reorderDoesNotReviveStaleSessionWhenAuthStoreSwitchedAccounts() = runBlocking {
        // #466 (AD2 blocker 1): if the auth store switches account (or clears) between the home store
        // capturing account A and applyHomeSessionLocally's in-lock write, updateMetadata no-ops
        // (account-generation guard). The home projection must NOT re-project the captured account-A
        // reorder — that would revive in the UI a session the persistence layer just rejected.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sessionStore = InMemorySlockAuthSessionStore(sampleSession()) // account user-1, servers [1,2]
        val store = SharedHomeStore(
            authStore = sessionStore,
            dataSource = sourceWithSnapshot(sampleHome(ServerId("server-1")), sessionStore = sessionStore),
            scope = scope,
            updateServerOrder = { request ->
                DataResult.Content(SettingsServerOrderSnapshot(serverOrder = request.serverOrder))
            }
        )

        store.accept(SharedHomeIntent.Start)
        val before = store.state.value.content as SharedHomeContentState.Content
        assertEquals(listOf("server-1", "server-2"), before.session.servers.map { it.id })

        // account switch to user-2 BEFORE the reorder's metadata write lands
        sessionStore.replaceAuthenticatedSession(
            sampleSession().copy(
                user = StoredAuthUser(id = "user-2", email = "b@slock.ai", displayName = "B"),
                accessToken = "b-access",
                refreshToken = "b-refresh"
            )
        )

        // reorder captured against account A → updateMetadata(expectedAccountId=user-1) no-ops on user-2
        store.accept(SharedHomeIntent.ReorderServers(listOf("server-2", "server-1")))

        // store still holds account B untouched (account-A metadata rejected)...
        assertEquals("user-2", sessionStore.readSession()?.user?.id)
        // ...and home did NOT project the rejected reorder: server order stays [1,2], not [2,1].
        val after = store.state.value.content as SharedHomeContentState.Content
        assertEquals(listOf("server-1", "server-2"), after.session.servers.map { it.id })
        scope.cancel()
    }

    @Test
    fun openingServerSwitcherRefreshesServerOrderFromServer() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session =
            sampleSession().copy(
                servers =
                    sampleSession().servers +
                        StoredAuthServer(id = "server-3", name = "Design", slug = "design")
            )
        val sessionStore = InMemorySlockAuthSessionStore(session)
        var loadCount = 0
        val blockedLoad = BlockingCall()
        val store = SharedHomeStore(
            authStore = sessionStore,
            dataSource = sourceWithSnapshot(sampleHome(ServerId("server-1")), sessionStore = sessionStore),
            scope = scope,
            loadServerOrder = {
                loadCount += 1
                blockedLoad.started.complete(Unit)
                blockedLoad.released.await()
                DataResult.Content(SettingsServerOrderSnapshot(serverOrder = listOf("server-3", "server-2", "server-1")))
            }
        )

        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.ToggleServerSwitcher)
        blockedLoad.awaitStarted()

        assertEquals(1, loadCount)
        assertEquals(true, store.state.value.serverSwitcherExpanded)
        assertEquals(1L, store.state.value.serverDrawerCommandIntent.generation)
        assertEquals(true, store.state.value.serverDrawerCommandIntent.desiredOpen)
        assertEquals(listOf("server-1", "server-2", "server-3"), sessionStore.readSession()?.servers?.map { it.id })
        store.accept(SharedHomeIntent.DismissServerSwitcher)
        assertEquals(false, store.state.value.serverSwitcherExpanded)
        assertEquals(2L, store.state.value.serverDrawerCommandIntent.generation)
        assertEquals(false, store.state.value.serverDrawerCommandIntent.desiredOpen)
        blockedLoad.release()
        awaitCondition {
            (store.state.value.content as? SharedHomeContentState.Content)
                ?.session
                ?.servers
                ?.map { it.id } == listOf("server-3", "server-2", "server-1")
        }
        assertEquals(listOf("server-3", "server-2", "server-1"), sessionStore.readSession()?.servers?.map { it.id })
        val after = store.state.value.content as SharedHomeContentState.Content
        assertEquals(listOf("server-3", "server-2", "server-1"), after.session.servers.map { it.id })
        scope.cancel()
    }

    @Test
    fun closeDirectMessagePersistsHiddenDmIds() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val initial = SettingsServerSidebarOrderSnapshot()
        val updateCalls = mutableListOf<SettingsServerSidebarOrderUpdateRequest>()
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource =
                SharedHomeDataSource(
                    sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                    api = RecordingHomeApi(),
                    parseHome = { payload, _ ->
                        DataResult.Content(
                            sampleHome(payload.serverId).copy(
                                directMessages = listOf(directMessage("dm-1", "Artin", "user-1", Presence.Active)),
                                sidebarOrder = initial
                            )
                        )
                    }
                ),
            scope = scope,
            loadSidebarOrder = { _ -> DataResult.Content(initial) },
            updateSidebarOrder = { request ->
                updateCalls += request
                DataResult.Content(initial.copy(hiddenDmIds = request.hiddenDmIds.orEmpty()))
            }
        )

        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.CloseDirectMessage("dm-1"))

        val after = (store.state.value.content as SharedHomeContentState.Content).snapshot.sidebarOrder
        assertEquals(listOf("dm-1"), after?.hiddenDmIds)
        assertEquals(listOf("dm-1"), updateCalls.single().hiddenDmIds)
        scope.cancel()
    }

    @Test
    fun markReadAndUnreadUpdateHomeCountsAndCallReadReceiptRoutes() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val readCalls = mutableListOf<ChannelId>()
        val unreadCalls = mutableListOf<ChannelId>()
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource =
                SharedHomeDataSource(
                    sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                    api = RecordingHomeApi(),
                    parseHome = { payload, _ ->
                        DataResult.Content(
                            sampleHome(payload.serverId).copy(
                                channels = listOf(channel("channel-1", "general").copy(unreadCount = 3, unreadCountKnown = true)),
                                unreadTotal = 3,
                                server = ServerSummary(payload.serverId, "Slock Community", unreadCount = 3)
                            )
                        )
                    }
                ),
            scope = scope,
            markChannelReadAll = { channelId ->
                readCalls += channelId
                DataResult.Content(ReadReceiptResult(channelId = channelId, unreadCount = 0))
            },
            markChannelUnread = { channelId ->
                unreadCalls += channelId
                DataResult.Content(ReadReceiptResult(channelId = channelId, unreadCount = 1))
            }
        )

        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.MarkRead("channel-1"))

        val readSnapshot = (store.state.value.content as SharedHomeContentState.Content).snapshot
        assertEquals(0, readSnapshot.channels.single().unreadCount)
        assertEquals(0, readSnapshot.unreadTotal)
        assertEquals(listOf(ChannelId("channel-1")), readCalls)

        store.accept(SharedHomeIntent.MarkUnread("channel-1"))

        val unreadSnapshot = (store.state.value.content as SharedHomeContentState.Content).snapshot
        assertEquals(1, unreadSnapshot.channels.single().unreadCount)
        assertEquals(1, unreadSnapshot.unreadTotal)
        assertEquals(listOf(ChannelId("channel-1")), unreadCalls)
        scope.cancel()
    }

    @Test
    fun processReadPredictionClearsHomeAndOlderFailureCannotRestoreIt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = RecordingHomeApi()
        val store =
            SharedHomeStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                dataSource =
                    SharedHomeDataSource(
                        sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                        api = api,
                        parseHome = { payload, _ ->
                            DataResult.Content(
                                sampleHome(payload.serverId).copy(
                                    channels =
                                        listOf(
                                            channel("channel-1", "general").copy(
                                                unreadCount = 3,
                                                unreadCountKnown = true
                                            )
                                        ),
                                    unreadTotal = 3,
                                    server = ServerSummary(payload.serverId, "Slock Community", unreadCount = 3)
                                )
                            )
                        }
                    ),
                scope = scope,
                readReceiptOwnerFactory = { serverId, accountId ->
                    ReadReceiptOwner("production", accountId, serverId)
                }
            )
        store.accept(SharedHomeIntent.Start)
        val callsAfterLoad = api.calls.toList()
        val owner = ReadReceiptOwner("production", "user-1", "server-1")

        store.applyReadReceiptPrediction(
            ReadReceiptPrediction(
                owner.copy(environmentId = "staging"),
                ChannelId("channel-1"),
                revision = 1L,
                target = ReadReceiptTarget.All
            )
        )
        assertEquals(
            3,
            (store.state.value.content as SharedHomeContentState.Content).snapshot.channels.single().unreadCount
        )
        store.applyReadReceiptPrediction(
            ReadReceiptPrediction(owner, ChannelId("channel-1"), revision = 2L, target = ReadReceiptTarget.All)
        )
        store.handleReadReceiptFailure(
            ReadReceiptRequest.All(ChannelId("channel-1"), owner = owner, revision = 1L)
        )

        val snapshot = (store.state.value.content as SharedHomeContentState.Content).snapshot
        assertEquals(0, snapshot.channels.single().unreadCount)
        assertEquals(0, snapshot.unreadTotal)
        assertEquals(callsAfterLoad, api.calls)
        scope.cancel()
    }

    @Test
    fun processReadPredictionSerializesWithRealtimePatchWithoutLosingEitherTransition() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val realtimeCommitHeld = CompletableDeferred<Unit>()
        val releaseRealtimeCommit = CompletableDeferred<Unit>()
        val predictionEnteredOwner = CompletableDeferred<Unit>()
        val home =
            sampleHome(ServerId("server-1")).copy(
                channels =
                    listOf(
                        channel("channel-1", "general").copy(
                            unreadCount = 3,
                            unreadCountKnown = true
                        )
                    ),
                unreadTotal = 3,
                server = ServerSummary(ServerId("server-1"), "Slock Community", unreadCount = 3)
            )
        val store =
            SharedHomeStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                dataSource = sourceWithSnapshot(home),
                scope = scope,
                readReceiptOwnerFactory = { serverId, accountId ->
                    ReadReceiptOwner("production", accountId, serverId)
                },
                beforeRealtimeHomeStateCommit = {
                    realtimeCommitHeld.complete(Unit)
                    runBlocking { releaseRealtimeCommit.await() }
                },
                onReadReceiptPredictionOwnerEntered = {
                    predictionEnteredOwner.complete(Unit)
                }
            )
        store.accept(SharedHomeIntent.Start)
        val owner = ReadReceiptOwner("production", "user-1", "server-1")
        val otherMember =
            sampleMember().copy(
                id = MemberId("member-2"),
                displayName = "Cindy",
                handle = "cindy@slock.ai"
            )

        val realtimeJob =
            scope.launch {
                store.accept(
                    SharedHomeIntent.ApplyRealtimeEvent(
                        serverId = "server-1",
                        event =
                            MessageNew(
                                Message(
                                    id = MessageId("message-concurrent"),
                                    threadId = ThreadId("channel-1"),
                                    channelId = ChannelId("channel-1"),
                                    seq = 4,
                                    author = otherMember,
                                    timestampLabel = "12:04",
                                    body = "concurrent realtime"
                                )
                            )
                    )
                )
            }
        withTimeout(2_000L) { realtimeCommitHeld.await() }

        val predictionJob =
            scope.launch {
                store.dispatch(
                    SharedHomeIntent.ApplyReadReceiptPrediction(
                        ReadReceiptPrediction(
                            owner = owner,
                            channelId = ChannelId("channel-1"),
                            revision = 1L,
                            target = ReadReceiptTarget.All
                        )
                    )
                )
            }
        val enteredWhileRealtimeHeld =
            withTimeoutOrNull(200L) {
                predictionEnteredOwner.await()
            }
        assertEquals(null, enteredWhileRealtimeHeld)

        releaseRealtimeCommit.complete(Unit)
        realtimeJob.join()
        predictionJob.join()

        val content = store.state.value.content as SharedHomeContentState.Content
        assertEquals("concurrent realtime", content.snapshot.channels.single().lastMessagePreview)
        assertEquals("concurrent realtime", content.baseSnapshot.channels.single().lastMessagePreview)
        assertEquals(0, content.snapshot.channels.single().unreadCount)
        assertEquals(0, content.snapshot.unreadTotal)
        assertTrue(predictionEnteredOwner.isCompleted)
        scope.cancel()
    }

    @Test
    fun perReceiverReadStateUsesVersionAuthorityAndAcceptsCanonicalCursorRewind() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val versionStore = PerReceiverStateVersionStore(InMemorySlockKeyValueStore())
        val store =
            SharedHomeStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                dataSource =
                    sourceWithSnapshot(
                        sampleHome(ServerId("server-1")).copy(
                            channels =
                                listOf(
                                    channel("channel-1", "general").copy(
                                        unreadCount = 3,
                                        unreadCountKnown = true,
                                        mentionCount = 2,
                                        mentionCountKnown = true,
                                        lastMessageSeq = 31,
                                        firstUnreadMessageId = MessageId("message-31")
                                    )
                                ),
                            unreadTotal = 3,
                            server = ServerSummary(ServerId("server-1"), "Slock Community", unreadCount = 3),
                            serverUnreadCounts = mapOf("server-1" to 3)
                        )
                    ),
                scope = scope,
                perReceiverStateVersionStore = versionStore,
                nowMillis = { 42L }
            )

        store.accept(SharedHomeIntent.Start)
        store.accept(
            SharedHomeIntent.ApplyPerReceiverReadState(
                PerReceiverReadStateUpdate(
                    serverId = ServerId("server-1"),
                    memberId = MemberId("user-1"),
                    channelId = ChannelId("channel-1"),
                    version = 3,
                    maxReadSeq = 31
                )
            )
        )

        val readSnapshot = (store.state.value.content as SharedHomeContentState.Content).snapshot
        assertEquals(3, readSnapshot.channels.single().unreadCount)
        assertEquals(2, readSnapshot.channels.single().mentionCount)
        assertEquals(MessageId("message-31"), readSnapshot.channels.single().firstUnreadMessageId)
        assertEquals(3, readSnapshot.unreadTotal)
        assertEquals(3, store.state.value.appBadgeCount.count)

        store.accept(
            SharedHomeIntent.ApplyPerReceiverReadState(
                PerReceiverReadStateUpdate(
                    serverId = ServerId("server-1"),
                    memberId = MemberId("user-1"),
                    channelId = ChannelId("channel-1"),
                    version = 2,
                    maxReadSeq = 40
                )
            )
        )

        val staleSnapshot = (store.state.value.content as SharedHomeContentState.Content).snapshot
        assertEquals(3, staleSnapshot.channels.single().unreadCount)
        assertEquals(3, staleSnapshot.unreadTotal)

        store.accept(
            SharedHomeIntent.ApplyPerReceiverReadState(
                PerReceiverReadStateUpdate(
                    serverId = ServerId("server-1"),
                    memberId = MemberId("user-1"),
                    channelId = ChannelId("channel-1"),
                    version = 4,
                    maxReadSeq = 12
                )
            )
        )

        store.awaitRealtimeSettled()
        val rewoundSnapshot = (store.state.value.content as SharedHomeContentState.Content).snapshot
        assertEquals(3, rewoundSnapshot.channels.single().unreadCount)
        assertEquals(2, rewoundSnapshot.channels.single().mentionCount)
        assertEquals(MessageId("message-31"), rewoundSnapshot.channels.single().firstUnreadMessageId)
        assertEquals(12, rewoundSnapshot.channels.single().readStateMaxReadSeq)
        assertEquals(3, rewoundSnapshot.unreadTotal)
        assertEquals(3, store.state.value.appBadgeCount.count)
        scope.cancel()
    }

    @Test
    fun canonicalSummaryAcceptedFactBeforeRequestAppliesServerSummary() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = RecordingHomeApi()
        val versionStore = PerReceiverStateVersionStore(InMemorySlockKeyValueStore())
        val snapshot =
            sampleHome(ServerId("server-1")).copy(
                channels =
                    listOf(
                        channel("channel-1", "general").copy(
                            unreadCount = 3,
                            unreadCountKnown = true,
                            mentionCount = 2,
                            mentionCountKnown = true,
                            lastMessageSeq = 31,
                            firstUnreadMessageId = MessageId("message-29")
                        )
                    ),
                unreadTotal = 3,
                server = ServerSummary(ServerId("server-1"), "Slock Community", unreadCount = 3),
                serverUnreadCounts = mapOf("server-1" to 3)
            )
        val store =
            SharedHomeStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                dataSource = sourceWithSnapshot(snapshot, api = api),
                scope = scope,
                perReceiverStateVersionStore = versionStore
            )

        store.accept(SharedHomeIntent.Start)
        store.accept(
            SharedHomeIntent.ApplyPerReceiverReadState(
                PerReceiverReadStateUpdate(
                    serverId = ServerId("server-1"),
                    memberId = MemberId("user-1"),
                    channelId = ChannelId("channel-1"),
                    version = 1,
                    maxReadSeq = 20
                )
            )
        )

        val reconciled = (store.state.value.content as SharedHomeContentState.Content).snapshot
        assertEquals(3, reconciled.channels.single().unreadCount)
        assertEquals(2, reconciled.channels.single().mentionCount)
        assertEquals(MessageId("message-29"), reconciled.channels.single().firstUnreadMessageId)
        assertEquals(20, reconciled.channels.single().readStateMaxReadSeq)
        assertEquals(3, reconciled.unreadTotal)
        assertEquals(2, api.calls.count { it == "channels:server-1" })
        scope.cancel()
    }

    @Test
    fun canonicalInflightSummaryCompleteCacheKeepsNewerFactByRestartingHomeLoad() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = RecordingHomeApi()
        val snapshot =
            sampleHome(ServerId("server-1")).copy(
                channels =
                    listOf(
                        channel("channel-1", "general").copy(
                            unreadCount = 3,
                            unreadCountKnown = true,
                            lastMessageSeq = 31,
                            firstUnreadMessageId = MessageId("message-31")
                        )
                    ),
                unreadTotal = 3,
                server = ServerSummary(ServerId("server-1"), "Slock Community", unreadCount = 3)
            )
        val store =
            SharedHomeStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                dataSource = sourceWithSnapshot(snapshot, api = api),
                scope = scope,
                perReceiverStateVersionStore =
                    PerReceiverStateVersionStore(InMemorySlockKeyValueStore())
            )

        store.accept(SharedHomeIntent.Start)
        val blockedRefresh = api.blockNextChannels()
        store.dispatch(SharedHomeIntent.Refresh)
        blockedRefresh.awaitStarted()

        store.dispatch(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = "server-1",
                event =
                    PerReceiverReadStateChanged(
                        serverId = ServerId("server-1"),
                        scopes =
                            listOf(
                                PerReceiverReadStateScope(
                                    scopeId = ChannelId("channel-1"),
                                    maxReadSeq = 31,
                                    readStateVersion = 1
                                )
                            )
                    )
            )
        )

        awaitCondition {
            api.calls.count { it == "channels:server-1" } == 2 &&
                (store.state.value.content as? SharedHomeContentState.Content)
                    ?.snapshot
                    ?.channels
                    ?.single()
                    ?.readStateMaxReadSeq == 31L
        }
        val current = (store.state.value.content as SharedHomeContentState.Content).snapshot
        assertEquals(31, current.channels.single().readStateMaxReadSeq)
        blockedRefresh.release()
        scope.cancel()
    }

    @Test
    fun canonicalInflightSummaryIncompleteCachePreservesCurrentUnreadAndRestartsHomeLoad() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = RecordingHomeApi()
        val snapshot =
            sampleHome(ServerId("server-1")).copy(
                channels =
                    listOf(
                        channel("channel-1", "general").copy(
                            unreadCount = 3,
                            unreadCountKnown = true,
                            mentionCount = 2,
                            mentionCountKnown = true,
                            lastMessageSeq = 31,
                            firstUnreadMessageId = MessageId("message-29")
                        )
                    ),
                unreadTotal = 3,
                server = ServerSummary(ServerId("server-1"), "Slock Community", unreadCount = 3)
            )
        val store =
            SharedHomeStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                dataSource = sourceWithSnapshot(snapshot, api = api),
                scope = scope,
                perReceiverStateVersionStore =
                    PerReceiverStateVersionStore(InMemorySlockKeyValueStore())
            )

        store.accept(SharedHomeIntent.Start)
        val blockedRefresh = api.blockNextChannels()
        store.dispatch(SharedHomeIntent.Refresh)
        blockedRefresh.awaitStarted()

        store.dispatch(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = "server-1",
                event =
                    PerReceiverReadStateChanged(
                        serverId = ServerId("server-1"),
                        scopes =
                            listOf(
                                PerReceiverReadStateScope(
                                    scopeId = ChannelId("channel-1"),
                                    maxReadSeq = 20,
                                    readStateVersion = 1
                                )
                            )
                    )
            )
        )

        awaitCondition {
            api.calls.count { it == "channels:server-1" } == 2 &&
                (store.state.value.content as? SharedHomeContentState.Content)
                    ?.snapshot
                    ?.channels
                    ?.single()
                    ?.readStateMaxReadSeq == 20L
        }
        val current = (store.state.value.content as SharedHomeContentState.Content).snapshot
        assertEquals(3, current.channels.single().unreadCount)
        assertEquals(2, current.channels.single().mentionCount)
        assertEquals(MessageId("message-29"), current.channels.single().firstUnreadMessageId)
        assertEquals(20, current.channels.single().readStateMaxReadSeq)
        blockedRefresh.release()
        scope.cancel()
    }

    @Test
    fun perReceiverReadStateMutedConversationDoesNotDriveAppBadgeCount() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val versionStore = PerReceiverStateVersionStore(InMemorySlockKeyValueStore())
        val store =
            SharedHomeStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                dataSource =
                    sourceWithSnapshot(
                        sampleHome(ServerId("server-1")).copy(
                            channels =
                                listOf(
                                    channel("channel-1", "general").copy(
                                        unreadCount = 4,
                                        unreadCountKnown = true,
                                        lastMessageSeq = 20,
                                        firstUnreadMessageId = MessageId("message-20"),
                                        activityNotificationPreference =
                                            ChannelActivityNotificationPreference(
                                                activityMuted = true,
                                                muteFromSeq = 7,
                                                activityMuteSupported = true
                                            )
                                    )
                                ),
                            unreadTotal = 4,
                            server = ServerSummary(ServerId("server-1"), "Slock Community", unreadCount = 4)
                        )
                    ),
                scope = scope,
                perReceiverStateVersionStore = versionStore
            )

        store.accept(SharedHomeIntent.Start)
        assertEquals(0, store.state.value.appBadgeCount.count)

        store.accept(
            SharedHomeIntent.ApplyPerReceiverReadState(
                PerReceiverReadStateUpdate(
                    serverId = ServerId("server-1"),
                    memberId = MemberId("user-1"),
                    channelId = ChannelId("channel-1"),
                    version = 3,
                    maxReadSeq = 20
                )
            )
        )

        val readSnapshot = (store.state.value.content as SharedHomeContentState.Content).snapshot
        assertEquals(4, readSnapshot.channels.single().unreadCount)
        assertEquals(4, readSnapshot.unreadTotal)
        assertEquals(0, store.state.value.appBadgeCount.count)

        store.accept(
            SharedHomeIntent.ApplyPerReceiverReadState(
                PerReceiverReadStateUpdate(
                    serverId = ServerId("server-1"),
                    memberId = MemberId("user-1"),
                    channelId = ChannelId("channel-1"),
                    version = 4,
                    maxReadSeq = 10
                )
            )
        )

        val regressedSnapshot = (store.state.value.content as SharedHomeContentState.Content).snapshot
        assertEquals(4, regressedSnapshot.channels.single().unreadCount)
        assertEquals(4, regressedSnapshot.unreadTotal)
        assertEquals(0, store.state.value.appBadgeCount.count)
        scope.cancel()
    }

    @Test
    fun perReceiverNotificationPrefsAreVersionGatedAndFeedMutedBadgeProjection() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val versionStore = PerReceiverStateVersionStore(InMemorySlockKeyValueStore())
        val store =
            SharedHomeStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                dataSource =
                    sourceWithSnapshot(
                        sampleHome(ServerId("server-1")).copy(
                            channels =
                                listOf(
                                    channel("channel-1", "general").copy(
                                        unreadCount = 4,
                                        unreadCountKnown = true,
                                        activityNotificationPreference = supportedActivityPreference()
                                    )
                                ),
                            unreadTotal = 4,
                            server = ServerSummary(ServerId("server-1"), "Slock Community", unreadCount = 4)
                        )
                    ),
                scope = scope,
                perReceiverStateVersionStore = versionStore
            )

        store.accept(SharedHomeIntent.Start)
        store.accept(
            SharedHomeIntent.ApplyPerReceiverNotificationPrefs(
                PerReceiverNotificationPrefsUpdate(
                    serverId = ServerId("server-1"),
                    memberId = MemberId("user-1"),
                    channelId = ChannelId("channel-1"),
                    version = 8,
                    preference =
                        ChannelActivityNotificationPreference(
                            activityMuted = true,
                            muteFromSeq = 99,
                            activityMuteSupported = true
                        )
                )
            )
        )

        val muted = (store.state.value.content as SharedHomeContentState.Content).snapshot.channels.single()
        assertEquals(true, muted.activityMuted)
        assertEquals(99, muted.muteFromSeq)
        assertFalse(
            homeConversationBadgeProjection(
                HomeConversationBadgeInput(
                    unreadCount = muted.unreadCount,
                    muted = muted.activityMuted
                )
            ).hasAttention
        )

        store.accept(
            SharedHomeIntent.ApplyPerReceiverNotificationPrefs(
                PerReceiverNotificationPrefsUpdate(
                    serverId = ServerId("server-1"),
                    memberId = MemberId("user-1"),
                    channelId = ChannelId("channel-1"),
                    version = 7,
                    preference =
                        ChannelActivityNotificationPreference(
                            activityMuted = false,
                            muteFromSeq = null,
                            activityMuteSupported = true
                        )
                )
            )
        )

        val stale = (store.state.value.content as SharedHomeContentState.Content).snapshot.channels.single()
        assertEquals(true, stale.activityMuted)
        assertEquals(99, stale.muteFromSeq)
        scope.cancel()
    }

    @Test
    fun realtimePerReceiverReadStateEventGatesEachScopeIndependently() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val versionStore = PerReceiverStateVersionStore(InMemorySlockKeyValueStore())
        val store =
            SharedHomeStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                dataSource =
                    sourceWithSnapshot(
                        sampleHome(ServerId("server-1")).copy(
                            channels =
                                listOf(
                                    channel("channel-1", "general").copy(
                                        unreadCount = 3,
                                        unreadCountKnown = true,
                                        lastMessageSeq = 31,
                                        firstUnreadMessageId = MessageId("message-31")
                                    ),
                                    channel("channel-2", "dev").copy(
                                        unreadCount = 2,
                                        unreadCountKnown = true,
                                        lastMessageSeq = 50,
                                        firstUnreadMessageId = MessageId("message-50")
                                    )
                                ),
                            unreadTotal = 5,
                            server = ServerSummary(ServerId("server-1"), "Slock Community", unreadCount = 5),
                            serverUnreadCounts = mapOf("server-1" to 5)
                        )
                    ),
                scope = scope,
                perReceiverStateVersionStore = versionStore
            )

        store.accept(SharedHomeIntent.Start)
        // Wire-shaped event straight off the socket: the receiving member is
        // implied by the user room (enriched from the session), not carried
        // in the payload.
        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = "server-1",
                event =
                    PerReceiverReadStateChanged(
                        serverId = ServerId("server-1"),
                        scopes =
                            listOf(
                                PerReceiverReadStateScope(
                                    scopeId = ChannelId("channel-1"),
                                    maxReadSeq = 31,
                                    readStateVersion = 9
                                )
                            )
                    )
            )
        )

        val preserved = (store.state.value.content as SharedHomeContentState.Content).snapshot
        assertEquals(3, preserved.channels.first { it.channelId.value == "channel-1" }.unreadCount)
        assertEquals(2, preserved.channels.first { it.channelId.value == "channel-2" }.unreadCount)
        assertEquals(5, preserved.unreadTotal)

        // Bulk frame: channel-1 arrives stale (8 < 9) and must be skipped,
        // but channel-2 has its own counter (041 §2.2 per-scope) so its
        // version 1 still applies — channel-1's high water must not swallow it.
        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = "server-1",
                event =
                    PerReceiverReadStateChanged(
                        serverId = ServerId("server-1"),
                        scopes =
                            listOf(
                                PerReceiverReadStateScope(
                                    scopeId = ChannelId("channel-1"),
                                    maxReadSeq = 12,
                                    readStateVersion = 8
                                ),
                                PerReceiverReadStateScope(
                                    scopeId = ChannelId("channel-2"),
                                    maxReadSeq = 50,
                                    readStateVersion = 1
                                )
                            )
                    )
            )
        )

        val next = (store.state.value.content as SharedHomeContentState.Content).snapshot
        assertEquals(3, next.channels.first { it.channelId.value == "channel-1" }.unreadCount)
        assertEquals(2, next.channels.first { it.channelId.value == "channel-2" }.unreadCount)
        assertEquals(5, next.unreadTotal)
        assertEquals(5, store.state.value.appBadgeCount.count)
        scope.cancel()
    }

    @Test
    fun realtimeReadStatePreservesPartialSummaryAndReconcilesAcceptedRewind() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = RecordingHomeApi()
        val versionStore = PerReceiverStateVersionStore(InMemorySlockKeyValueStore())
        val store =
            SharedHomeStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                dataSource =
                    sourceWithSnapshot(
                        sampleHome(ServerId("server-1")).copy(
                            channels =
                                listOf(
                                    channel("channel-1", "general").copy(
                                        unreadCount = 4,
                                        unreadCountKnown = true,
                                        lastMessageSeq = 50,
                                        firstUnreadMessageId = MessageId("message-47")
                                    )
                                ),
                            unreadTotal = 4,
                            server = ServerSummary(ServerId("server-1"), "Slock Community", unreadCount = 4),
                            serverUnreadCounts = mapOf("server-1" to 4)
                        ),
                        api = api
                    ),
                scope = scope,
                perReceiverStateVersionStore = versionStore
            )

        store.accept(SharedHomeIntent.Start)
        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = "server-1",
                event =
                    PerReceiverReadStateChanged(
                        serverId = ServerId("server-1"),
                        scopes =
                            listOf(
                                PerReceiverReadStateScope(
                                    scopeId = ChannelId("channel-1"),
                                    maxReadSeq = 20,
                                    readStateVersion = 2
                                )
                            )
                    )
            )
        )
        awaitCondition { api.calls.count { it == "channels:server-1" } == 2 }

        val partial = (store.state.value.content as SharedHomeContentState.Content).snapshot
        assertEquals(4, partial.channels.single().unreadCount)
        assertEquals(20, partial.channels.single().readStateMaxReadSeq)
        assertEquals(4, partial.unreadTotal)

        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = "server-1",
                event =
                    PerReceiverReadStateChanged(
                        serverId = ServerId("server-1"),
                        scopes =
                            listOf(
                                PerReceiverReadStateScope(
                                    scopeId = ChannelId("channel-1"),
                                    maxReadSeq = 5,
                                    readStateVersion = 3
                                )
                            )
                    )
            )
        )
        awaitCondition {
            api.calls.count { it == "channels:server-1" } == 3 &&
                (store.state.value.content as? SharedHomeContentState.Content)
                    ?.snapshot
                    ?.channels
                    ?.single()
                    ?.readStateMaxReadSeq == 5L
        }

        val rewound = (store.state.value.content as SharedHomeContentState.Content).snapshot
        assertEquals(4, rewound.channels.single().unreadCount)
        assertEquals(5, rewound.channels.single().readStateMaxReadSeq)
        assertEquals(4, rewound.unreadTotal)
        assertEquals(
            3,
            versionStore.readVersion(
                serverId = ServerId("server-1"),
                memberId = MemberId("user-1"),
                scope = PerReceiverStateScope.ReadState,
                scopeId = ChannelId("channel-1")
            )
        )
        scope.cancel()
    }

    @Test
    fun webReadStateSequenceKeepsBackgroundServerCountProjectionAligned() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = RecordingHomeApi()
        val versionStore = PerReceiverStateVersionStore(InMemorySlockKeyValueStore())
        val store =
            SharedHomeStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                dataSource =
                    sourceWithSnapshot(
                        sampleHome(ServerId("server-1")).copy(
                            serverUnreadCounts = mapOf("server-1" to 0, "server-2" to 3)
                        ),
                        api = api
                    ),
                scope = scope,
                perReceiverStateVersionStore = versionStore
            )
        store.accept(SharedHomeIntent.Start)
        val visibleBefore = (store.state.value.content as SharedHomeContentState.Content).snapshot
        val otherMember = sampleMember().copy(id = MemberId("member-2"), displayName = "Cindy")

        store.accept(
            SharedHomeIntent.ApplyBackgroundServerUnreadEvent(
                serverId = "server-2",
                event =
                    MessageNew(
                        Message(
                            id = MessageId("background-101"),
                            threadId = ThreadId("channel-b"),
                            channelId = ChannelId("channel-b"),
                            seq = 101,
                            author = otherMember,
                            timestampLabel = "12:01",
                            body = "background update"
                        )
                    )
            )
        )
        store.accept(
            SharedHomeIntent.ApplyBackgroundServerUnreadEvent(
                serverId = "server-2",
                event =
                    MessageNew(
                        Message(
                            id = MessageId("background-102"),
                            threadId = ThreadId("channel-b"),
                            channelId = ChannelId("channel-b"),
                            seq = 102,
                            author = otherMember,
                            timestampLabel = "12:02",
                            body = "later background update"
                        )
                    )
            )
        )

        // Cross-client count vector, aligned with Web
        // packages/web/src/store/readStateSync.ts::consumeReadStateUpdate:
        // hydrated 3 + seq 101 + seq 102 = 5.
        assertEquals(5, store.state.value.serverUnreadCounts["server-2"])
        assertEquals(visibleBefore, (store.state.value.content as SharedHomeContentState.Content).snapshot)

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
                                    maxReadSeq = 101,
                                    readStateVersion = 5
                                )
                            )
                    )
            )
        )
        awaitCondition {
            api.calls.count { it == "summary:server-1" } == 2 &&
                store.state.value.serverUnreadCounts["server-2"] == 3
        }
        // The accepted fact removes only the provably covered optimistic
        // bucket, then canonical unread-summary reconciliation replaces the
        // count-only background aggregate.
        assertEquals(3, store.state.value.serverUnreadCounts["server-2"])

        // Higher version with a lower watermark is a legal accepted rewind.
        // Background aggregates cannot reconstruct the resulting baseline,
        // so they reconcile canonically. A later lower version is stale.
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
                                    maxReadSeq = 80,
                                    readStateVersion = 7
                                )
                            )
                    )
            )
        )
        awaitCondition { api.calls.count { it == "summary:server-1" } == 3 }
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
                                    maxReadSeq = 200,
                                    readStateVersion = 6
                                )
                            )
                    )
            )
        )
        assertEquals(3, store.state.value.serverUnreadCounts["server-2"])
        assertEquals(7, versionStore.readVersion(ServerId("server-2"), MemberId("user-1"), PerReceiverStateScope.ReadState, ChannelId("channel-b")))

        // A second scope is independent. Clearing channel-b at the next
        // accepted version leaves channel-c's optimistic unread intact.
        store.accept(
            SharedHomeIntent.ApplyBackgroundServerUnreadEvent(
                serverId = "server-2",
                event =
                    MessageNew(
                        Message(
                            id = MessageId("background-c-1"),
                            threadId = ThreadId("channel-c"),
                            channelId = ChannelId("channel-c"),
                            seq = 1,
                            author = otherMember,
                            timestampLabel = "12:03",
                            body = "independent scope"
                        )
                    )
            )
        )
        assertEquals(4, store.state.value.serverUnreadCounts["server-2"])
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
                                    maxReadSeq = 102,
                                    readStateVersion = 8
                                )
                            )
                    )
            )
        )
        awaitCondition {
            api.calls.count { it == "summary:server-1" } == 4 &&
                store.state.value.serverUnreadCounts["server-2"] == 3
        }
        assertEquals(3, store.state.value.serverUnreadCounts["server-2"])

        // seq=null is not provably covered by any watermark. Keep it until
        // canonical refresh instead of silently undercounting.
        store.accept(
            SharedHomeIntent.ApplyBackgroundServerUnreadEvent(
                serverId = "server-2",
                event =
                    MessageNew(
                        Message(
                            id = MessageId("background-unknown-seq"),
                            threadId = ThreadId("channel-d"),
                            channelId = ChannelId("channel-d"),
                            seq = null,
                            author = otherMember,
                            timestampLabel = "12:04",
                            body = "unknown seq"
                        )
                    )
            )
        )
        store.accept(
            SharedHomeIntent.ApplyBackgroundServerUnreadEvent(
                serverId = "server-2",
                event =
                    PerReceiverReadStateChanged(
                        serverId = ServerId("server-2"),
                        scopes =
                            listOf(
                                PerReceiverReadStateScope(
                                    scopeId = ChannelId("channel-d"),
                                    maxReadSeq = 999,
                                    readStateVersion = 1
                                )
                            )
                    )
            )
        )
        awaitCondition {
            api.calls.count { it == "summary:server-1" } == 5 &&
                store.state.value.serverUnreadCounts["server-2"] == 3
        }
        assertEquals(3, store.state.value.serverUnreadCounts["server-2"])

        // A canonical unread-summary refresh replaces the count-only delta.
        store.accept(SharedHomeIntent.Refresh)
        assertEquals(3, store.state.value.serverUnreadCounts["server-2"])

        // The accepted checkpoint survives a store recreation: an old
        // message replay below maxReadSeq does not recreate unread count.
        val recreatedScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val recreated =
            SharedHomeStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                dataSource =
                    sourceWithSnapshot(
                        sampleHome(ServerId("server-1")).copy(
                            serverUnreadCounts = mapOf("server-1" to 0, "server-2" to 3)
                        )
                    ),
                scope = recreatedScope,
                perReceiverStateVersionStore = versionStore
            )
        recreated.accept(SharedHomeIntent.Start)
        recreated.accept(
            SharedHomeIntent.ApplyBackgroundServerUnreadEvent(
                serverId = "server-2",
                event =
                    MessageNew(
                        Message(
                            id = MessageId("background-replayed-90"),
                            threadId = ThreadId("channel-b"),
                            channelId = ChannelId("channel-b"),
                            seq = 90,
                            author = otherMember,
                            timestampLabel = "11:59",
                            body = "old replay"
                        )
                    )
            )
        )
        assertEquals(3, recreated.state.value.serverUnreadCounts["server-2"])
        scope.cancel()
        recreatedScope.cancel()
    }

    @Test
    fun realtimeNotificationPrefsEventAppliesMuteThroughVersionGate() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val versionStore = PerReceiverStateVersionStore(InMemorySlockKeyValueStore())
        val store =
            SharedHomeStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                dataSource =
                    sourceWithSnapshot(
                        sampleHome(ServerId("server-1")).copy(
                            channels =
                                listOf(
                                    channel("channel-1", "general").copy(
                                        unreadCount = 4,
                                        unreadCountKnown = true,
                                        activityNotificationPreference = supportedActivityPreference()
                                    )
                                ),
                            unreadTotal = 4,
                            server = ServerSummary(ServerId("server-1"), "Slock Community", unreadCount = 4)
                        )
                    ),
                scope = scope,
                perReceiverStateVersionStore = versionStore
            )

        store.accept(SharedHomeIntent.Start)
        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = "server-1",
                event =
                    PerReceiverNotificationPrefsChanged(
                        serverId = ServerId("server-1"),
                        scopeId = ChannelId("channel-1"),
                        preference =
                            ChannelActivityNotificationPreference(
                                activityMuted = true,
                                muteFromSeq = 99,
                                activityMuteSupported = true
                            ),
                        prefsVersion = 8
                    )
            )
        )

        val muted = (store.state.value.content as SharedHomeContentState.Content).snapshot.channels.single()
        assertEquals(true, muted.activityMuted)
        assertEquals(99, muted.muteFromSeq)
        scope.cancel()
    }

    @Test
    fun perReceiverServerOrderUsesIndependentVersionGate() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val versionStore = PerReceiverStateVersionStore(InMemorySlockKeyValueStore())
        val store =
            SharedHomeStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                dataSource =
                    sourceWithSnapshot(
                        sampleHome(ServerId("server-1")).copy(
                            sidebarOrder =
                                SettingsServerSidebarOrderSnapshot(
                                    channelOrder = listOf("channel-1"),
                                    channelSortMode = "manual"
                                )
                        )
                    ),
                scope = scope,
                perReceiverStateVersionStore = versionStore
            )

        store.accept(SharedHomeIntent.Start)
        store.accept(
            SharedHomeIntent.ApplyPerReceiverServerOrder(
                PerReceiverServerOrderUpdate(
                    serverId = ServerId("server-1"),
                    memberId = MemberId("user-1"),
                    version = 11,
                    sidebarOrder =
                        SettingsServerSidebarOrderSnapshot(
                            channelOrder = listOf("channel-2", "channel-1"),
                            channelSortMode = "manual"
                        )
                )
            )
        )

        val updated = (store.state.value.content as SharedHomeContentState.Content).snapshot.sidebarOrder
        assertEquals(listOf("channel-2", "channel-1"), updated?.channelOrder)

        store.accept(
            SharedHomeIntent.ApplyPerReceiverServerOrder(
                PerReceiverServerOrderUpdate(
                    serverId = ServerId("server-1"),
                    memberId = MemberId("user-1"),
                    version = 10,
                    sidebarOrder =
                        SettingsServerSidebarOrderSnapshot(
                            channelOrder = listOf("channel-1"),
                            channelSortMode = "manual"
                        )
                )
            )
        )

        val stale = (store.state.value.content as SharedHomeContentState.Content).snapshot.sidebarOrder
        assertEquals(listOf("channel-2", "channel-1"), stale?.channelOrder)
        scope.cancel()
    }

    @Test
    fun realtimePinnedUpdatedUsesUserGlobalVersionGateAndFeedsSidebarProjection() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val versionStore = PerReceiverStateVersionStore(InMemorySlockKeyValueStore())
        val store =
            SharedHomeStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                dataSource =
                    sourceWithSnapshot(
                        sampleHome(ServerId("server-1")).copy(
                            sidebarOrder =
                                SettingsServerSidebarOrderSnapshot(
                                    pinnedChannelIds = listOf("channel-1"),
                                    pinnedOrder = listOf("channel-1"),
                                    pinned = listOf(SettingsServerSidebarPinnedItem(kind = "channel", id = "channel-1"))
                                )
                        )
                    ),
                scope = scope,
                perReceiverStateVersionStore = versionStore
            )

        store.accept(SharedHomeIntent.Start)
        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = "server-1",
                event =
                    PerReceiverPinnedChanged(
                        serverId = ServerId("server-1"),
                        pinned =
                            listOf(
                                SettingsServerSidebarPinnedItem(kind = "agent", id = "agent-1"),
                                SettingsServerSidebarPinnedItem(kind = "human", id = "user-2")
                            ),
                        pinnedVersion = 6
                    )
            )
        )

        val updated = (store.state.value.content as SharedHomeContentState.Content).snapshot.sidebarOrder
        assertEquals(
            listOf(
                SettingsServerSidebarPinnedItem(kind = "agent", id = "agent-1"),
                SettingsServerSidebarPinnedItem(kind = "human", id = "user-2")
            ),
            updated?.pinned
        )
        assertEquals(emptyList(), updated?.pinnedChannelIds)
        assertEquals(listOf("agent-1"), updated?.pinnedAgentIds)
        assertEquals(listOf("agent-1", "user-2"), updated?.pinnedOrder)

        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = "server-1",
                event =
                    PerReceiverPinnedChanged(
                        serverId = ServerId("server-1"),
                        pinned = listOf(SettingsServerSidebarPinnedItem(kind = "channel", id = "channel-2")),
                        pinnedVersion = 5
                    )
            )
        )

        val stale = (store.state.value.content as SharedHomeContentState.Content).snapshot.sidebarOrder
        assertEquals(
            listOf(
                SettingsServerSidebarPinnedItem(kind = "agent", id = "agent-1"),
                SettingsServerSidebarPinnedItem(kind = "human", id = "user-2")
            ),
            stale?.pinned
        )

        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = "server-1",
                event =
                    PerReceiverPinnedChanged(
                        serverId = ServerId("server-1"),
                        pinned = emptyList(),
                        pinnedVersion = 7
                    )
            )
        )

        val emptyPinned = (store.state.value.content as SharedHomeContentState.Content).snapshot.sidebarOrder
        assertEquals(emptyList(), emptyPinned?.pinned)
        assertEquals(emptyList(), emptyPinned?.pinnedChannelIds)
        assertEquals(emptyList(), emptyPinned?.pinnedAgentIds)
        assertEquals(emptyList(), emptyPinned?.pinnedOrder)
        scope.cancel()
    }

    @Test
    fun setChannelActivityMutedAppliesAuthoritativeResponse() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val calls = mutableListOf<Pair<ChannelId, Boolean>>()
        val store =
            SharedHomeStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                dataSource =
                    sourceWithSnapshot(
                        sampleHome(ServerId("server-1")).copy(
                            channels = listOf(channel("channel-1", "general").copy(activityNotificationPreference = supportedActivityPreference()))
                        )
                    ),
                scope = scope,
                setChannelActivityMuted = { channelId, muted ->
                    calls += channelId to muted
                    DataResult.Content(
                        ChannelActivityMuteSettings(
                            channelId = channelId,
                            activityMuted = muted,
                            muteFromSeq = 88,
                            activityMuteSupported = true,
                            prefsVersion = 1
                        )
                    )
                }
            )

        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.SetChannelActivityMuted("channel-1", true))

        val channel = (store.state.value.content as SharedHomeContentState.Content).snapshot.channels.single()
        assertEquals(listOf(ChannelId("channel-1") to true), calls)
        assertEquals(true, channel.activityMuted)
        assertEquals(88, channel.muteFromSeq)
        assertEquals(true, channel.activityMuteSupported)
        scope.cancel()
    }

    @Test
    fun notificationPrefsResponseThenSameVersionEchoFoldsOnlyOnce() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val versionStore = PerReceiverStateVersionStore(InMemorySlockKeyValueStore())
        val diagnostics = mutableListOf<String>()
        val store =
            SharedHomeStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                dataSource =
                    sourceWithSnapshot(
                        sampleHome(ServerId("server-1")).copy(
                            channels = listOf(channel("channel-1", "general").copy(activityNotificationPreference = supportedActivityPreference()))
                        )
                    ),
                scope = scope,
                perReceiverStateVersionStore = versionStore,
                diagnosticsLogger = diagnostics::add,
                setChannelActivityMuted = { channelId, muted ->
                    DataResult.Content(
                        ChannelActivityMuteSettings(
                            channelId = channelId,
                            activityMuted = muted,
                            muteFromSeq = 88,
                            activityMuteSupported = true,
                            prefsVersion = 8
                        )
                    )
                }
            )

        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.SetChannelActivityMuted("channel-1", true))
        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = "server-1",
                event =
                    PerReceiverNotificationPrefsChanged(
                        serverId = ServerId("server-1"),
                        scopeId = ChannelId("channel-1"),
                        preference =
                            ChannelActivityNotificationPreference(
                                activityMuted = false,
                                muteFromSeq = null,
                                activityMuteSupported = true
                            ),
                        prefsVersion = 8
                    )
            )
        )

        val channel = (store.state.value.content as SharedHomeContentState.Content).snapshot.channels.single()
        assertEquals(true, channel.activityMuted)
        assertEquals(88, channel.muteFromSeq)
        assertEquals(
            8,
            versionStore.readVersion(
                serverId = ServerId("server-1"),
                memberId = MemberId("user-1"),
                scope = PerReceiverStateScope.NotificationPrefs,
                scopeId = ChannelId("channel-1")
            )
        )
        assertTrue(diagnostics.any { it.contains("per_receiver_notification_prefs_response_apply") && it.endsWith("result=applied") })
        assertTrue(diagnostics.any { it.contains("per_receiver_notification_prefs_apply") && it.endsWith("result=skipped") })
        scope.cancel()
    }

    @Test
    fun notificationPrefsEchoThenSameVersionResponseFoldsOnlyOnce() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val versionStore = PerReceiverStateVersionStore(InMemorySlockKeyValueStore())
        val requestStarted = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()
        val responseReturned = CompletableDeferred<Unit>()
        val diagnostics = mutableListOf<String>()
        val store =
            SharedHomeStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                dataSource =
                    sourceWithSnapshot(
                        sampleHome(ServerId("server-1")).copy(
                            channels = listOf(channel("channel-1", "general").copy(activityNotificationPreference = supportedActivityPreference()))
                        )
                    ),
                scope = scope,
                perReceiverStateVersionStore = versionStore,
                diagnosticsLogger = diagnostics::add,
                setChannelActivityMuted = { channelId, muted ->
                    requestStarted.complete(Unit)
                    releaseResponse.await()
                    responseReturned.complete(Unit)
                    DataResult.Content(
                        ChannelActivityMuteSettings(
                            channelId = channelId,
                            activityMuted = muted,
                            muteFromSeq = 88,
                            activityMuteSupported = true,
                            prefsVersion = 8
                        )
                    )
                }
            )

        store.accept(SharedHomeIntent.Start)
        store.dispatch(SharedHomeIntent.SetChannelActivityMuted("channel-1", true))
        withTimeout(1_000) { requestStarted.await() }
        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = "server-1",
                event =
                    PerReceiverNotificationPrefsChanged(
                        serverId = ServerId("server-1"),
                        scopeId = ChannelId("channel-1"),
                        preference =
                            ChannelActivityNotificationPreference(
                                activityMuted = true,
                                muteFromSeq = 99,
                                activityMuteSupported = true
                            ),
                        prefsVersion = 8
                    )
            )
        )
        releaseResponse.complete(Unit)
        withTimeout(1_000) { responseReturned.await() }
        yield()

        val channel = (store.state.value.content as SharedHomeContentState.Content).snapshot.channels.single()
        assertEquals(true, channel.activityMuted)
        assertEquals(99, channel.muteFromSeq)
        assertEquals(
            8,
            versionStore.readVersion(
                serverId = ServerId("server-1"),
                memberId = MemberId("user-1"),
                scope = PerReceiverStateScope.NotificationPrefs,
                scopeId = ChannelId("channel-1")
            )
        )
        assertTrue(diagnostics.any { it.contains("per_receiver_notification_prefs_apply") && it.endsWith("result=applied") })
        assertTrue(diagnostics.any { it.startsWith("per_receiver_notification_prefs_response_superseded") })
        scope.cancel()
    }

    @Test
    fun newerNotificationPrefsEchoClearsMutedStateOnEchoOnlyDevice() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val versionStore = PerReceiverStateVersionStore(InMemorySlockKeyValueStore())
        val store =
            SharedHomeStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                dataSource =
                    sourceWithSnapshot(
                        sampleHome(ServerId("server-1")).copy(
                            channels =
                                listOf(
                                    channel("channel-1", "general").copy(
                                        activityNotificationPreference =
                                            ChannelActivityNotificationPreference(
                                                activityMuted = true,
                                                muteFromSeq = 90,
                                                activityMuteSupported = true
                                            )
                                    )
                                )
                        )
                    ),
                scope = scope,
                perReceiverStateVersionStore = versionStore
            )

        store.accept(SharedHomeIntent.Start)
        store.accept(
            SharedHomeIntent.ApplyPerReceiverNotificationPrefs(
                PerReceiverNotificationPrefsUpdate(
                    serverId = ServerId("server-1"),
                    memberId = MemberId("user-1"),
                    channelId = ChannelId("channel-1"),
                    version = 10,
                    preference =
                        ChannelActivityNotificationPreference(
                            activityMuted = true,
                            muteFromSeq = 90,
                            activityMuteSupported = true
                        )
                )
            )
        )
        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = "server-1",
                event =
                    PerReceiverNotificationPrefsChanged(
                        serverId = ServerId("server-1"),
                        scopeId = ChannelId("channel-1"),
                        preference =
                            ChannelActivityNotificationPreference(
                                activityMuted = false,
                                muteFromSeq = null,
                                activityMuteSupported = true
                            ),
                        prefsVersion = 11
                    )
            )
        )

        val channel = (store.state.value.content as SharedHomeContentState.Content).snapshot.channels.single()
        assertFalse(channel.activityMuted)
        assertEquals(null, channel.muteFromSeq)
        assertEquals(
            11,
            versionStore.readVersion(
                serverId = ServerId("server-1"),
                memberId = MemberId("user-1"),
                scope = PerReceiverStateScope.NotificationPrefs,
                scopeId = ChannelId("channel-1")
            )
        )
        scope.cancel()
    }

    @Test
    fun setChannelActivityMutedFailsClosedForUnversionedSuccess() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val versionStore = PerReceiverStateVersionStore(InMemorySlockKeyValueStore())
        val failureMessages = mutableListOf<String>()
        val store =
            SharedHomeStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                dataSource =
                    sourceWithSnapshot(
                        sampleHome(ServerId("server-1")).copy(
                            channels = listOf(channel("channel-1", "general").copy(activityNotificationPreference = supportedActivityPreference()))
                        )
                    ),
                scope = scope,
                perReceiverStateVersionStore = versionStore,
                setChannelActivityMuted = { channelId, _ ->
                    DataResult.Content(
                        ChannelActivityMuteSettings(
                            channelId = channelId,
                            activityMuted = true,
                            muteFromSeq = 88,
                            activityMuteSupported = true
                        )
                    )
                },
                pinUpdateFailureCallback = failureMessages::add
            )

        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.SetChannelActivityMuted("channel-1", true))

        val channel = (store.state.value.content as SharedHomeContentState.Content).snapshot.channels.single()
        assertFalse(channel.activityMuted)
        assertEquals(
            0,
            versionStore.readVersion(
                serverId = ServerId("server-1"),
                memberId = MemberId("user-1"),
                scope = PerReceiverStateScope.NotificationPrefs,
                scopeId = ChannelId("channel-1")
            )
        )
        assertEquals(listOf("Unable to update channel notifications"), failureMessages)
        scope.cancel()
    }

    @Test
    fun setChannelActivityMutedRollsBackLatestFailure() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val failureMessages = mutableListOf<String>()
        val store =
            SharedHomeStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                dataSource =
                    sourceWithSnapshot(
                        sampleHome(ServerId("server-1")).copy(
                            channels = listOf(channel("channel-1", "general").copy(activityNotificationPreference = supportedActivityPreference()))
                        )
                    ),
                scope = scope,
                setChannelActivityMuted = { _, _ -> DataResult.Error(ScreenError("server_unavailable", retryable = true)) },
                pinUpdateFailureCallback = { message -> failureMessages += message }
            )

        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.SetChannelActivityMuted("channel-1", true))

        val channel = (store.state.value.content as SharedHomeContentState.Content).snapshot.channels.single()
        assertEquals(false, channel.activityMuted)
        assertEquals(listOf("Unable to update channel notifications"), failureMessages)
        scope.cancel()
    }

    @Test
    fun setChannelActivityMutedFailureOnlyRollsBackTargetChannel() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val calls = mutableListOf<Pair<ChannelId, Boolean>>()
        val store =
            SharedHomeStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                dataSource =
                    sourceWithSnapshot(
                        sampleHome(ServerId("server-1")).copy(
                            channels =
                                listOf(
                                    channel("channel-a", "alpha").copy(activityNotificationPreference = supportedActivityPreference()),
                                    channel("channel-b", "bravo").copy(activityNotificationPreference = supportedActivityPreference())
                                )
                        )
                    ),
                scope = scope,
                setChannelActivityMuted = { channelId, muted ->
                    calls += channelId to muted
                    if (channelId == ChannelId("channel-a")) {
                        firstStarted.complete(Unit)
                        releaseFirst.await()
                        DataResult.Error(ScreenError("server_unavailable", retryable = true))
                    } else {
                        DataResult.Content(
                            ChannelActivityMuteSettings(
                                channelId = channelId,
                                activityMuted = muted,
                                muteFromSeq = 202,
                                activityMuteSupported = true,
                                prefsVersion = 1
                            )
                        )
                    }
                }
            )

        store.accept(SharedHomeIntent.Start)
        store.dispatch(SharedHomeIntent.SetChannelActivityMuted("channel-a", true))
        withTimeout(1_000) { firstStarted.await() }
        store.dispatch(SharedHomeIntent.SetChannelActivityMuted("channel-b", true))
        withTimeout(1_000) {
            while (calls.size < 2) {
                yield()
            }
        }

        releaseFirst.complete(Unit)
        yield()

        val channels = (store.state.value.content as SharedHomeContentState.Content).snapshot.channels.associateBy { it.threadId.value }
        assertEquals(listOf(ChannelId("channel-a") to true, ChannelId("channel-b") to true), calls)
        assertEquals(false, channels.getValue("channel-a").activityMuted)
        assertEquals(null, channels.getValue("channel-a").muteFromSeq)
        assertEquals(true, channels.getValue("channel-b").activityMuted)
        assertEquals(202, channels.getValue("channel-b").muteFromSeq)
        scope.cancel()
    }

    @Test
    fun setChannelActivityMutedIgnoresStaleOlderResponse() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val firstResponseReturned = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val secondResponseReturned = CompletableDeferred<Unit>()
        val calls = mutableListOf<Pair<ChannelId, Boolean>>()
        val diagnostics = mutableListOf<String>()
        val store =
            SharedHomeStore(
                authStore = InMemorySlockAuthSessionStore(sampleSession()),
                dataSource =
                    sourceWithSnapshot(
                        sampleHome(ServerId("server-1")).copy(
                            channels = listOf(channel("channel-1", "general").copy(activityNotificationPreference = supportedActivityPreference()))
                        )
                    ),
                scope = scope,
                diagnosticsLogger = diagnostics::add,
                setChannelActivityMuted = { channelId, muted ->
                    calls += channelId to muted
                    if (muted) {
                        firstStarted.complete(Unit)
                        releaseFirst.await()
                        firstResponseReturned.complete(Unit)
                        DataResult.Content(
                            ChannelActivityMuteSettings(
                                channelId = channelId,
                                activityMuted = true,
                                muteFromSeq = 101,
                                activityMuteSupported = true,
                                prefsVersion = 1
                            )
                        )
                    } else {
                        secondStarted.complete(Unit)
                        releaseSecond.await()
                        secondResponseReturned.complete(Unit)
                        DataResult.Content(
                            ChannelActivityMuteSettings(
                                channelId = channelId,
                                activityMuted = false,
                                muteFromSeq = null,
                                activityMuteSupported = true,
                                prefsVersion = 2
                            )
                        )
                    }
                }
            )

        store.accept(SharedHomeIntent.Start)
        store.dispatch(SharedHomeIntent.SetChannelActivityMuted("channel-1", true))
        withTimeout(1_000) { firstStarted.await() }
        assertEquals(true, (store.state.value.content as SharedHomeContentState.Content).snapshot.channels.single().activityMuted)

        store.dispatch(SharedHomeIntent.SetChannelActivityMuted("channel-1", false))
        withTimeout(1_000) { secondStarted.await() }
        assertEquals(false, (store.state.value.content as SharedHomeContentState.Content).snapshot.channels.single().activityMuted)

        releaseFirst.complete(Unit)
        withTimeout(1_000) { firstResponseReturned.await() }
        withTimeout(1_000) {
            while (diagnostics.none { it.startsWith("per_receiver_notification_prefs_response_superseded") }) {
                yield()
            }
        }
        // The v1 mute response is superseded by the in-flight v2 unmute and
        // must not visibly overwrite its newer optimistic prediction.
        assertEquals(false, (store.state.value.content as SharedHomeContentState.Content).snapshot.channels.single().activityMuted)

        releaseSecond.complete(Unit)
        withTimeout(1_000) { secondResponseReturned.await() }
        yield()
        val channel = (store.state.value.content as SharedHomeContentState.Content).snapshot.channels.single()
        assertEquals(listOf(ChannelId("channel-1") to true, ChannelId("channel-1") to false), calls)
        assertEquals(false, channel.activityMuted)
        assertEquals(null, channel.muteFromSeq)
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
            pinnedExpanded = false
        )
        // 10 fixed (including pull refresh) + 0 joint section + 2 pinned section items = 12
        assertEquals(12, collapsed)
        val expanded = homeLazyItemCount(
            channelsCount = 3,
            jointCount = 0,
            directMessagesCount = 2,
            pinnedCount = 2,
            channelsExpanded = false,
            jointChannelsExpanded = false,
            directMessagesExpanded = false,
            pinnedExpanded = true
        )
        assertEquals(14, expanded)
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

    @Test
    fun hydrateDirectMessagePreviewUpdatesEmptyPreviewWithoutModifyingUnreadOrExistingPreview() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val serverId = ServerId("server-1")
        val dm1 = HomeDirectMessageRow(
            threadId = ThreadId("dm-1"),
            channelId = ChannelId("dm-1"),
            name = "DM 1",
            unreadCount = 5,
            lastMessagePreview = null,
            lastMessageSenderName = null,
            lastMessageAt = null,
        )
        val dm2 = HomeDirectMessageRow(
            threadId = ThreadId("dm-2"),
            channelId = ChannelId("dm-2"),
            name = "DM 2",
            unreadCount = 2,
            lastMessagePreview = "Authoritative preview",
            lastMessageSenderName = "Alice",
            lastMessageAt = "2026-03-29T09:00:00Z",
        )
        val snapshot = sampleHome(serverId).copy(
            directMessages = listOf(dm1, dm2)
        )
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = sourceWithSnapshot(snapshot),
            scope = scope,
            readReceiptOwnerFactory = { sId, accountId -> ReadReceiptOwner("production", accountId, sId) },
        )
        store.accept(SharedHomeIntent.Start)

        // 1. Hydrate dm1 with empty preview -> should update preview and senderName, keeping unread unchanged
        store.hydrateDirectMessagePreview(
            SharedHomeIntent.HydrateDirectMessagePreview(
                serverId = serverId,
                channelId = ChannelId("dm-1"),
                preview = "New preview text",
                senderName = "Bob",
                lastMessageAt = "2026-03-29T10:00:00Z",
                lastMessageId = MessageId("msg-1"),
                accountId = "user-1",
            )
        )

        val stateAfter1 = (store.state.value.content as SharedHomeContentState.Content).snapshot
        val updatedDm1 = stateAfter1.directMessages.first { it.channelId == ChannelId("dm-1") }
        assertEquals("New preview text", updatedDm1.lastMessagePreview)
        assertEquals("Bob", updatedDm1.lastMessageSenderName)
        assertEquals("2026-03-29T10:00:00Z", updatedDm1.lastMessageAt)
        assertEquals(5, updatedDm1.unreadCount)

        // 2. Hydrate dm2 which already has a preview -> must NOT clobber
        store.hydrateDirectMessagePreview(
            SharedHomeIntent.HydrateDirectMessagePreview(
                serverId = serverId,
                channelId = ChannelId("dm-2"),
                preview = "Attempted clobber preview",
                senderName = "Mallory",
                lastMessageAt = "2026-03-29T11:00:00Z",
                lastMessageId = MessageId("msg-2"),
                accountId = "user-1",
            )
        )

        val stateAfter2 = (store.state.value.content as SharedHomeContentState.Content).snapshot
        val dm2After = stateAfter2.directMessages.first { it.channelId == ChannelId("dm-2") }
        assertEquals("Authoritative preview", dm2After.lastMessagePreview)
        assertEquals("Alice", dm2After.lastMessageSenderName)
        assertEquals("2026-03-29T09:00:00Z", dm2After.lastMessageAt)
        assertEquals(2, dm2After.unreadCount)

        // 3. Hydrate with mismatched accountId -> must NOT hydrate
        val dm3 = HomeDirectMessageRow(
            threadId = ThreadId("dm-3"),
            channelId = ChannelId("dm-3"),
            name = "DM 3",
            lastMessagePreview = null,
            lastMessageSenderName = null,
            unreadCount = 1,
        )
        val snapshotWithDm3 = stateAfter2.copy(directMessages = stateAfter2.directMessages + dm3)
        val storeWithDm3 = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = sourceWithSnapshot(snapshotWithDm3),
            scope = scope,
            readReceiptOwnerFactory = { sId, accountId -> ReadReceiptOwner("production", accountId, sId) },
        )
        storeWithDm3.accept(SharedHomeIntent.Start)
        storeWithDm3.hydrateDirectMessagePreview(
            SharedHomeIntent.HydrateDirectMessagePreview(
                serverId = serverId,
                channelId = ChannelId("dm-3"),
                preview = "Foreign account preview",
                senderName = "Eve",
                accountId = "other-account",
            )
        )
        val stateAfter3 = (storeWithDm3.state.value.content as SharedHomeContentState.Content).snapshot
        val dm3After = stateAfter3.directMessages.first { it.channelId == ChannelId("dm-3") }
        assertNull(dm3After.lastMessagePreview)
        scope.cancel()
    }
}

