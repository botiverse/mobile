package build.raft.app.data

import build.raft.app.RaftSidebarSortMode
import build.raft.app.HomeConversationBadgeInput
import build.raft.app.homeConversationBadgeProjection
import build.raft.app.data.realtime.PerReceiverStateVersionStore
import build.raft.app.data.realtime.PerReceiverStateScope
import build.raft.app.model.ChannelActivityNotificationPreference
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
    fun selectedHydrateCommitsBeforeExactFencedFirstHomeStateAndNeverFillsLegacyCheckpoint() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val versionStore = PerReceiverStateVersionStore(InMemoryRaftKeyValueStore())
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
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
            authStore = InMemoryRaftAuthSessionStore(sampleSession()),
            dataSource =
                source(
                    api = RecordingHomeApi(),
                    sessionStore = InMemoryRaftAuthSessionStore(sampleSession())
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
            authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
    fun shellScopedHomeStoresKeepRuntimeServerSelectionsIndependent() = runBlocking {
        val processStore = InMemoryRaftAuthSessionStore(sampleSession())
        val runtimeA = SelectedServerStore(RaftShellStoreScope.create("shell-a", "server-1"))
        val runtimeB = SelectedServerStore(RaftShellStoreScope.create("shell-b", "server-2"))
        val authA = RaftShellAuthSessionStore(processStore, runtimeA)
        val authB = RaftShellAuthSessionStore(processStore, runtimeB)
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
        val sessionStore = InMemoryRaftAuthSessionStore(sampleSession())
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
    fun openingServerDrawerSeedsEveryServerAndExpandsTheSelectedServer() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sessionStore = InMemoryRaftAuthSessionStore(sampleSession())
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
        val sessionStore = InMemoryRaftAuthSessionStore(sampleSession())
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
    fun serverDrawerLoadDropsSectionWhenServerIsRemovedFromSession() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val initialSession = sampleSession()
        val sessionStore = InMemoryRaftAuthSessionStore(initialSession)
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
        val sessionStore = InMemoryRaftAuthSessionStore(sampleSession())
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
    fun coldStartShowsLastKnownUnreadAndPreviewHintsUntilNetworkHomeArrives() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val storage = InMemoryRaftKeyValueStore()
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
            authStore = InMemoryRaftAuthSessionStore(sampleSession()),
            dataSource = sourceWithSnapshot(lastSession),
            scope = scope,
            persistSlices = slices()
        ).accept(SharedHomeIntent.Start)

        val sessionStore = InMemoryRaftAuthSessionStore(sampleSession())
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
        val storage = InMemoryRaftKeyValueStore()
        val home = sampleHome(ServerId("server-1")).copy(channels = listOf(channel("channel-1", "general")))
        SharedHomeStore(
            authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
                    authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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

    private fun InMemoryRaftKeyValueStore.hintKeyFor(userId: String, serverId: String): String =
        "mvi:persist:home:hints:v1:user:$userId:server:$serverId"

    @Test
    fun hydrateCachedHomeRejectsSnapshotWithMismatchedMemberIdentity() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val storage = InMemoryRaftKeyValueStore()
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
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
                dataSource = sourceWithSnapshot(mismatched),
                scope = scope,
                persistSlices = listOf(persistSlice)
            )
        warmStore.accept(SharedHomeIntent.Start)

        val coldStore =
            SharedHomeStore(
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
    fun resetFencesLateNetworkPublicationFromThePriorSession() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = RecordingHomeApi()
        val blocked = api.blockNextChannels()
        val store =
            SharedHomeStore(
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
    fun unauthenticatedSessionBecomesErrorState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val logs = mutableListOf<String>()
        val store = SharedHomeStore(
            authStore = InMemoryRaftAuthSessionStore(),
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
    fun startShowsLoadingUntilNetworkRefreshCompletes() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = BlockingHomeApi()
        val store = SharedHomeStore(
            authStore = InMemoryRaftAuthSessionStore(sampleSession()),
            dataSource =
                SharedHomeDataSource(
                    sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
            authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
            authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
    fun selectedConversationAccruesUnreadWhileItIsNotSeen() = runBlocking {
        // Seen = read: the selected conversation skips unread only while it is in front of the person
        // (shell visible and in use). Left open in a hidden or idle window it counts as the server
        // counts it, and the page's read clears it once the person is back.
        var seen = false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = SharedHomeStore(
            authStore = InMemoryRaftAuthSessionStore(sampleSession()),
            dataSource = sourceWithSnapshot(sampleHome(ServerId("server-1"))),
            scope = scope,
            selectedConversationSeen = { seen }
        )
        store.accept(SharedHomeIntent.Start)
        val otherMember = sampleMember().copy(
            id = MemberId("member-2"),
            displayName = "Cindy",
            handle = "cindy@slock.ai"
        )
        fun liveMessage(id: String, seq: Long) =
            MessageNew(
                Message(
                    id = MessageId(id),
                    threadId = ThreadId("channel-1"),
                    channelId = ChannelId("channel-1"),
                    seq = seq,
                    author = otherMember,
                    timestampLabel = "12:01",
                    body = id
                )
            )

        store.accept(SharedHomeIntent.SelectConversation("channel-1"))
        store.accept(SharedHomeIntent.ApplyRealtimeEvent(serverId = "server-1", event = liveMessage("message-2", 2)))
        var content = store.state.value.content
        assertTrue(content is SharedHomeContentState.Content)
        assertEquals(1, content.snapshot.channels.single().unreadCount)
        assertEquals(1, content.snapshot.unreadTotal)

        seen = true
        store.accept(SharedHomeIntent.ApplyRealtimeEvent(serverId = "server-1", event = liveMessage("message-3", 3)))
        content = store.state.value.content
        assertTrue(content is SharedHomeContentState.Content)
        assertEquals(1, content.snapshot.channels.single().unreadCount)
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
            authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
        val sessionStore = InMemoryRaftAuthSessionStore(sampleSession())
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
    fun refreshRequiredReloadsHomeWithoutVisibleRefreshAffordance() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = RecordingHomeApi()
        val logs = mutableListOf<String>()
        val store = SharedHomeStore(
            authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
    fun syncRefreshReplacesALoadSentBeforeItInsteadOfJoiningIt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = RecordingHomeApi()
        val logs = mutableListOf<String>()
        val store = SharedHomeStore(
            authStore = InMemoryRaftAuthSessionStore(sampleSession()),
            dataSource = source(api = api),
            scope = scope,
            diagnosticsLogger = logs::add
        )
        store.accept(SharedHomeIntent.Start)
        api.calls.clear()
        logs.clear()
        // A normal (socket) refresh is in flight, read before the sync change.
        val blockedRefresh = api.blockNextChannels()
        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = "server-1",
                event = RealtimeRefreshRequired(RealtimeRefreshScope.Home, reason = "socket", serverId = ServerId("server-1"))
            )
        )
        blockedRefresh.awaitStarted()

        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = "server-1",
                event =
                    RealtimeRefreshRequired(
                        RealtimeRefreshScope.Home,
                        reason = "sync_changes",
                        serverId = ServerId("server-1"),
                        supersedesInFlightLoad = true
                    )
            )
        )

        assertContains(logs, "home_realtime_refresh_supersede scope=Home reason=sync_changes active=load")
        // The fresh read answered while the older one is still held (it is cancelled, never published).
        awaitCondition { api.calls.count { it == "channels:server-1" } == 1 }
        blockedRefresh.release()
        scope.cancel()
    }

    /**
     * Sync on, Home reloads reuse the held machine list. A machine change (`machine:status`, a sync
     * `machine` row, the sync machines refresh: all a Machines-scope refresh) arriving while Home is
     * hidden must make the reload on return read the list again, not reuse the old one.
     *
     * Virtual time on one test dispatcher: each step runs to quiescence ([runCurrent]) before the
     * next, so the Home reload has finished (reused the held list) before the machine change.
     */
    @Test
    fun syncOnMachineChangeWhileHomeHiddenMakesTheReturnReloadReadMachines() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val api = RecordingHomeApi()
        val computersApi = RecordingComputersApi()
        val store = SharedHomeStore(
            authStore = InMemoryRaftAuthSessionStore(sampleSession()),
            dataSource =
                source(
                    api = api,
                    computersApi = computersApi,
                    serverMembersStore = SharedServerMembersStore(EmptyMemberRepository, incrementalSyncCoversRoster = { true }),
                ),
            scope = scope,
        )
        store.accept(SharedHomeIntent.Start)
        runCurrent()
        assertEquals(listOf("GET machines:server-1"), computersApi.calls, "Start reads the machine list")
        // A Home reload with nothing changed reuses the held list.
        store.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                serverId = "server-1",
                event = RealtimeRefreshRequired(RealtimeRefreshScope.Home, reason = "sync_changes", serverId = ServerId("server-1"))
            )
        )
        runCurrent()
        assertEquals(2, api.calls.count { it == "channels:server-1" }, "the Home reload ran")
        assertEquals(listOf("GET machines:server-1"), computersApi.calls, "the Home reload reused the held list")

        store.accept(SharedHomeIntent.SetSurfaceVisible(false))
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
        runCurrent()
        assertEquals(1, computersApi.calls.size, "hidden: nothing read yet")
        store.accept(SharedHomeIntent.SetSurfaceVisible(true))
        runCurrent()

        assertEquals(listOf("GET machines:server-1", "GET machines:server-1"), computersApi.calls, "the return reload read it again")
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
            authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
            authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
    fun selectConversationTracksSelectedRowAndClearsTopAction() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = SharedHomeStore(
            authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
    fun refreshKeepsSelectedConversationAfterReturningFromThread() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = SharedHomeStore(
            authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
            authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
    fun cachedHomeStartRefreshesImmediatelyAndManualRefreshStillForcesNetwork() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val logs = mutableListOf<String>()
        var now = 1_000L
        val localStore = InMemoryComposeLocalDataStore()
        val api = RecordingHomeApi()
        val store = SharedHomeStore(
            authStore = InMemoryRaftAuthSessionStore(sampleSession()),
            dataSource =
                SharedHomeDataSource(
                    sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
            authStore = InMemoryRaftAuthSessionStore(sampleSession()),
            dataSource =
                SharedHomeDataSource(
                    sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
    fun channelCreatedOptimisticallyAddsAndSelectsChannel() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = SharedHomeStore(
            authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
            authStore = InMemoryRaftAuthSessionStore(sampleSession()),
            dataSource = source(api = RecordingHomeApi(), announcementsApi = announcementsApi),
            scope = scope
        )

        store.accept(SharedHomeIntent.Start)

        assertEquals(emptyList(), store.state.value.notifications)
        assertEquals(emptyList(), announcementsApi.calls)
        scope.cancel()
    }

    @Test
    fun startDoesNotSurfaceOfflineMachinesInHomeNotifications() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val announcementsApi = RecordingAnnouncementsApi(activeBody = """{"announcements":[]}""")
        val computersApi =
            RecordingComputersApi(
                bodyText =
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
            )
        val store = SharedHomeStore(
            authStore = InMemoryRaftAuthSessionStore(sampleSession()),
            dataSource =
                source(
                    api = RecordingHomeApi(),
                    announcementsApi = announcementsApi,
                    computersApi = computersApi
                ),
            scope = scope
        )

        store.accept(SharedHomeIntent.Start)

        assertEquals(emptyList(), store.state.value.notifications)
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
            val sessionStore = InMemoryRaftAuthSessionStore(sampleSession(role = role))
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
    fun sameServerDemotionClearsOnlyMachineNotificationsBeforeNetworkCompletes() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sessionStore = InMemoryRaftAuthSessionStore(sampleSession(role = "owner"))
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
    fun staleOwnerMachineRequestCannotPublishAfterSwitchOrDemotion() = runBlocking {
        listOf("switch", "demotion").forEach { transition ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val initialSession =
                sampleSession(role = "owner", secondServerRole = "member")
            val sessionStore = InMemoryRaftAuthSessionStore(initialSession)
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
            authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
        assertEquals("Update available for Jiachengs-MacBook-Pro.local", notification.title)
        assertEquals("Stop it and reconnect to update.", notification.body)
        assertTrue(notification.titleDesc != null)
        assertTrue(notification.bodyDesc != null)
        assertTrue(notification.actionLabelDesc != null)
        assertEquals("machine-1", notification.targetId)
        assertEquals(SharedHomeNotificationKind.Warning, notification.kind)
        assertEquals(SharedHomeNotificationIcon.CircleArrowUp, notification.icon)
        scope.cancel()
    }

    @Test
    fun upsertNotificationReplacesAppUpdateActionStateImmediately() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = SharedHomeStore(
            authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
        val sessionStore = InMemoryRaftAuthSessionStore(sampleSession())
        val api = ServerUnreadHomeApi(unreadByServerId = mapOf("server-1" to 7))
        val store = SharedHomeStore(
            authStore = sessionStore,
            dataSource =
                SharedHomeDataSource(
                    sessionStore = sessionStore,
                    api = api,
                    parseHome = ::parseRaftHome
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
        val sessionStore = InMemoryRaftAuthSessionStore(sampleSession())
        val api = MissingServerUnreadHomeApi() // summary = [] for active server
        val store = SharedHomeStore(
            authStore = sessionStore,
            dataSource =
                SharedHomeDataSource(
                    sessionStore = sessionStore,
                    api = api,
                    parseHome = ::parseRaftHome
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
        val sessionStore = InMemoryRaftAuthSessionStore(sampleSession())
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
                    parseHome = ::parseRaftHome
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
        val sessionStore = InMemoryRaftAuthSessionStore(sampleSession())
        val switchableApi = SwitchableHomeApi()
        switchableApi.setServerUnread("server-1" to 9)
        val store = SharedHomeStore(
            authStore = sessionStore,
            dataSource =
                SharedHomeDataSource(
                    sessionStore = sessionStore,
                    api = switchableApi,
                    parseHome = ::parseRaftHome
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
            agentId: MemberId,
            workspaceChoice: AgentArchiveWorkspaceChoice
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
    fun awaitSnapshotForTimesOutWhileLoadStaysInFlight() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val snapshot = sampleHome(ServerId("server-1"))
        val api = RecordingHomeApi()
        val blockedLoad = api.blockNextChannels("server-1")
        val store =
            SharedHomeStore(
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
                dataSource = sourceWithSnapshot(snapshot, api = api),
                scope = scope,
                readReceiptOwnerFactory = { serverId, accountId ->
                    ReadReceiptOwner("production", accountId, serverId)
                },
            )
        store.dispatch(SharedHomeIntent.Start)
        blockedLoad.awaitStarted()

        val awaited = store.awaitSnapshotFor(ServerId("server-1"), timeoutMillis = 20L)

        assertNull(awaited)
        blockedLoad.release()
        scope.cancel()
    }

    private fun source(
        api: RaftHomeApi,
        announcementsApi: RaftAnnouncementsRemindersApi? = null,
        computersApi: RaftComputersApi? = null,
        sessionStore: RaftAuthSessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
        appUpdateStatusProvider: (suspend () -> DataResult<SharedSettingsUpdateCheckSnapshot>)? = null,
        serverMembersStore: SharedServerMembersStore? = null,
    ): SharedHomeDataSource =
        SharedHomeDataSource(
            sessionStore = sessionStore,
            api = api,
            announcementsApi = announcementsApi,
            computersApi = computersApi,
            appUpdateStatusProvider = appUpdateStatusProvider,
            serverMembersStore = serverMembersStore,
            parseHome = { payload, _ -> DataResult.Content(sampleHome(payload.serverId)) },
            // Decode inline, as in sourceWithSnapshot: on the real JSON lane a launched Home load
            // resumes on a parse worker after its channels request, so a test that waits for that
            // request races the rest of the load (CI run 40564229281).
            parseDispatcher = Dispatchers.Unconfined
        )

    private fun sourceWithSnapshot(
        snapshot: HomeSnapshot,
        api: RaftHomeApi = RecordingHomeApi(),
        sessionStore: RaftAuthSessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
        localStore: ComposeLocalDataStore? = null,
        logger: (String) -> Unit = {},
        serverMembersStore: SharedServerMembersStore? = null
    ): SharedHomeDataSource =
        SharedHomeDataSource(
            sessionStore = sessionStore,
            api = api,
            serverMembersStore = serverMembersStore,
            parseHome = { _, _ -> DataResult.Content(snapshot) },
            // Decode inline. On the real JSON lane (Dispatchers.Default) a load launched by
            // dispatch() (e.g. the read-state reconcile) resumes on a parse worker and commits
            // its state concurrently with the test thread's own accept()/assert calls.
            parseDispatcher = Dispatchers.Unconfined
        )

    private class RecordingHomeApi : RaftHomeApi {
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
                    else -> EMPTY_CHANNEL_UNREAD_SUMMARY_BODY
                }
            return HomeNetworkResponse(code = 200, bodyText = body)
        }
    }

    private class BlockingHomeApi : RaftHomeApi {
        private var channelBlock: BlockingCall? = null
        var channelsCalls = 0

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
            channelsCalls += 1
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
            HomeNetworkResponse(code = 200, bodyText = EMPTY_CHANNEL_UNREAD_SUMMARY_BODY)

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
    ) : RaftHomeApi {
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
            return HomeNetworkResponse(code = 200, bodyText = channelUnreadSummaryBody(mapOf("${serverId.value}-channel" to 1)))
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
    private class SwitchableHomeApi : RaftHomeApi {
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
            return responseOrThrow(channelUnreadSummaryBody(mapOf("${serverId.value}-channel" to 1)))
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
     * Variant of [RaftHomeApi] that returns an empty /servers/unread-summary
     * for the active server (the wire-level fallback condition that should
     * drive the badge source to [AppBadgeSource.CHANNEL_DM_FALLBACK]).
     * [ServerUnreadHomeApi] always emits at least one summary entry per
     * call (it does not distinguish "missing" from "0 unread"), so we use
     * this class only for the CHANNEL_DM_FALLBACK test path.
     */
    private class MissingServerUnreadHomeApi : RaftHomeApi {
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
            return HomeNetworkResponse(code = 200, bodyText = channelUnreadSummaryBody(mapOf("${serverId.value}-channel" to 1)))
        }

        override suspend fun serverUnreadSummary(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse {
            calls += "summary:${serverId.value}"
            // Truly empty summary — the active server has NO entry. This
            // is the wire-level condition for the CHANNEL_DM_FALLBACK path.
            return HomeNetworkResponse(code = 200, bodyText = "[]")
        }
    }

    private class ThrowingHomeApi : RaftHomeApi {
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
    ) : RaftAnnouncementsRemindersApi {
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
              "latestDaemonVersion": "0.63.7",
              "machines": [
                {
                  "id": "machine-1",
                  "name": "OranixSlockTest",
                  "status": "online",
                  "statusVersion": 1,
                  "daemonVersion": "0.62.0"
                }
              ]
            }
            """.trimIndent()
    ) : RaftComputersApi {
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

    private class BlockingComputersApi : RaftComputersApi {
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
                    """{"latestDaemonVersion":"0.63.7","machines":[{"id":"machine-1","name":"Old owner machine","status":"online","statusVersion":1,"daemonVersion":"0.62.0"}]}"""
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
            authStore = InMemoryRaftAuthSessionStore(sampleSession()),
            dataSource =
                SharedHomeDataSource(
                    sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
        // Let the Home load finish before the Pin (CI flake, PR #4427). With Unconfined, the load
        // resumes on the coroutine timer thread when the 300ms cap expires and keeps writing state
        // after it publishes (loadNotifications' `_state.value.copy(...)`); a Pin dispatched from
        // this thread at the same moment could be lost to that read-modify-write. Production runs
        // both on the serial store dispatcher. The late first-paint merge is a separate coroutine
        // still waiting on firstPaintGate, so the case under test is unchanged.
        startJob.join()

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
            authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
            authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
            authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
    fun togglePinnedRollsBackWhenPatchFails() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val initial = SettingsServerSidebarOrderSnapshot(
            pinnedChannelIds = listOf("channel-1"),
            pinnedOrder = listOf("channel-1"),
            pinnedSortMode = "manual"
        )
        val store = SharedHomeStore(
            authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
            authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
        store.accept(SharedHomeIntent.SetPinnedSortMode(RaftSidebarSortMode.Az))

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
            authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
    fun sidebarSectionWritesPreserveInterleavedSystemSectionOrder() = runBlocking {
        // Hands a8fcdfaa: a web-authored sectionOrder interleaves system and
        // custom ids; mobile writes must round-trip the system ids in place.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val mobile = SettingsServerSidebarCustomSection(id = "sec-mobile", name = "Mobile", sortMode = "manual")
        val doomed = SettingsServerSidebarCustomSection(id = "sec-doomed", name = "Doomed", sortMode = "manual")
        val interleaved = listOf("system:pinned", "sec-mobile", "system:dms", "sec-doomed", "system:joint", "system:channels")
        val initial =
            SettingsServerSidebarOrderSnapshot(
                customSections = listOf(mobile, doomed),
                sectionOrder = interleaved,
                sectionsVersion = 2
            )
        var current = initial
        val updateCalls = mutableListOf<SettingsServerSidebarOrderUpdateRequest>()
        val store = SharedHomeStore(
            authStore = InMemoryRaftAuthSessionStore(sampleSession()),
            dataSource = sourceWithSidebarOrder(initial = initial),
            scope = scope,
            loadSidebarOrder = { _ -> DataResult.Content(current) },
            updateSidebarOrder = { request ->
                updateCalls += request
                current =
                    current.copy(
                        customSections = request.customSections.orEmpty(),
                        sectionOrder = request.sectionOrder.orEmpty(),
                        sectionPlacements = request.sectionPlacements.orEmpty(),
                        sectionsVersion = current.sectionsVersion + 1
                    )
                DataResult.Content(current)
            }
        )

        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.DeleteSidebarSection("sec-doomed"))
        store.accept(SharedHomeIntent.CreateSidebarSection(name = "New", emoji = null))

        assertEquals(2, updateCalls.size)
        assertEquals(
            listOf("system:pinned", "sec-mobile", "system:dms", "system:joint", "system:channels"),
            updateCalls[0].sectionOrder
        )
        val created = updateCalls[1].customSections.orEmpty().last()
        assertEquals(
            listOf("system:pinned", "sec-mobile", "system:dms", "system:joint", "system:channels", created.id),
            updateCalls[1].sectionOrder
        )
        val after = (store.state.value.content as SharedHomeContentState.Content).snapshot.sidebarOrder
        assertEquals(updateCalls[1].sectionOrder, after?.sectionOrder)
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
            authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
            authStore = InMemoryRaftAuthSessionStore(sampleSession()),
            dataSource = sourceWithSidebarOrder(initial = initial),
            scope = scope,
            loadSidebarOrder = { _ ->
                loadCalls += 1
                // Call 1 is Start's concurrent sidebar-order fetch (task #322) —
                // return the same snapshot the home payload embedded so the merge
                // no-ops. Call 2 is the conflict reload.
                if (loadCalls == 1) DataResult.Content(initial) else DataResult.Content(fresh)
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
        assertEquals(2, loadCalls, "Start's concurrent fetch, then exactly one conflict reload")
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
            authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
    fun moveOrRemoveAgentDirectMessageCleansLegacyChannelAliasPlacement() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sec1 = SettingsServerSidebarCustomSection(id = "sec-1", name = "One", sortMode = "manual")
        val sec2 = SettingsServerSidebarCustomSection(id = "sec-2", name = "Two", sortMode = "manual")
        val initial =
            SettingsServerSidebarOrderSnapshot(
                customSections = listOf(sec1, sec2),
                sectionOrder = SIDEBAR_SYSTEM_SECTION_IDS + listOf("sec-1", "sec-2"),
                sectionPlacements =
                    listOf(
                        SettingsServerSidebarSectionPlacement(kind = "channel", id = "dm-agent-1", sectionId = "sec-1", position = 0),
                        SettingsServerSidebarSectionPlacement(kind = "agent", id = "agent-1", sectionId = "sec-1", position = 1)
                    ),
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
        val moveUpdates = mutableListOf<SettingsServerSidebarOrderUpdateRequest>()
        val moveStore = SharedHomeStore(
            authStore = InMemoryRaftAuthSessionStore(sampleSession()),
            dataSource = sourceWithSnapshot(home),
            scope = scope,
            loadSidebarOrder = { _ -> DataResult.Content(initial) },
            updateSidebarOrder = { request ->
                moveUpdates += request
                DataResult.Content(
                    initial.copy(
                        sectionPlacements = request.sectionPlacements.orEmpty(),
                        sectionsVersion = 3
                    )
                )
            }
        )

        moveStore.accept(SharedHomeIntent.Start)
        // Moving to sec-2 must drop the legacy `channel:dm-agent-1` alias and write only `agent:agent-1`.
        moveStore.accept(SharedHomeIntent.MoveConversationToSection(conversationId = "dm-agent-1", sectionId = "sec-2"))
        assertEquals(1, moveUpdates.size)
        assertEquals(
            listOf(SettingsServerSidebarSectionPlacement(kind = "agent", id = "agent-1", sectionId = "sec-2", position = 0)),
            moveUpdates.single().sectionPlacements
        )

        // Removing back to Home must clear both canonical `agent:` and legacy `channel:` placements.
        val removeUpdates = mutableListOf<SettingsServerSidebarOrderUpdateRequest>()
        val removeStore = SharedHomeStore(
            authStore = InMemoryRaftAuthSessionStore(sampleSession()),
            dataSource = sourceWithSnapshot(home),
            scope = scope,
            loadSidebarOrder = { _ -> DataResult.Content(initial) },
            updateSidebarOrder = { request ->
                removeUpdates += request
                DataResult.Content(
                    initial.copy(
                        sectionPlacements = request.sectionPlacements.orEmpty(),
                        sectionsVersion = 3
                    )
                )
            }
        )
        removeStore.accept(SharedHomeIntent.Start)
        removeStore.accept(SharedHomeIntent.MoveConversationToSection(conversationId = "dm-agent-1", sectionId = null))
        assertEquals(1, removeUpdates.size)
        assertEquals(emptyList(), removeUpdates.single().sectionPlacements)
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
            authStore = InMemoryRaftAuthSessionStore(sampleSession()),
            dataSource =
                SharedHomeDataSource(
                    sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
    fun reorderServersPersistsNormalizedSessionOrder() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session =
            sampleSession().copy(
                servers =
                    sampleSession().servers +
                        StoredAuthServer(id = "server-3", name = "Design", slug = "design")
            )
        val sessionStore = InMemoryRaftAuthSessionStore(session)
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
        val sessionStore = InMemoryRaftAuthSessionStore(sampleSession()) // account user-1, servers [1,2]
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
    fun closeDirectMessagePersistsHiddenDmIds() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val initial = SettingsServerSidebarOrderSnapshot()
        val updateCalls = mutableListOf<SettingsServerSidebarOrderUpdateRequest>()
        val store = SharedHomeStore(
            authStore = InMemoryRaftAuthSessionStore(sampleSession()),
            dataSource =
                SharedHomeDataSource(
                    sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
            authStore = InMemoryRaftAuthSessionStore(sampleSession()),
            dataSource =
                SharedHomeDataSource(
                    sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
                dataSource =
                    SharedHomeDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
        val versionStore = PerReceiverStateVersionStore(InMemoryRaftKeyValueStore())
        val store =
            SharedHomeStore(
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
                dataSource = sourceWithSnapshot(snapshot, api = api),
                scope = scope,
                perReceiverStateVersionStore =
                    PerReceiverStateVersionStore(InMemoryRaftKeyValueStore())
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
    fun perReceiverReadStateMutedConversationDoesNotDriveAppBadgeCount() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val versionStore = PerReceiverStateVersionStore(InMemoryRaftKeyValueStore())
        val store =
            SharedHomeStore(
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
    fun webReadStateSequenceKeepsBackgroundServerCountProjectionAligned() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = RecordingHomeApi()
        val versionStore = PerReceiverStateVersionStore(InMemoryRaftKeyValueStore())
        val store =
            SharedHomeStore(
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
        assertEquals(
            2,
            api.calls.count { it == "summary:server-1" },
            "the accepted background read state must reconcile canonically: ${api.calls}"
        )
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
        // The reconcile load decodes inline (sourceWithSnapshot), so it has committed before the
        // next event; on a parse worker it could land after the channel-c increment below and
        // overwrite it with the canonical 3.
        assertEquals(
            3,
            api.calls.count { it == "summary:server-1" },
            "the accepted rewind must reconcile canonically: ${api.calls}"
        )
        assertEquals(3, store.state.value.serverUnreadCounts["server-2"])
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
        assertEquals(
            4,
            api.calls.count { it == "summary:server-1" },
            "clearing channel-b must reconcile canonically: ${api.calls}"
        )
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
        assertEquals(
            5,
            api.calls.count { it == "summary:server-1" },
            "an accepted channel-d watermark must reconcile canonically: ${api.calls}"
        )
        assertEquals(3, store.state.value.serverUnreadCounts["server-2"])

        // A canonical unread-summary refresh replaces the count-only delta.
        store.accept(SharedHomeIntent.Refresh)
        assertEquals(3, store.state.value.serverUnreadCounts["server-2"])

        // The accepted checkpoint survives a store recreation: an old
        // message replay below maxReadSeq does not recreate unread count.
        val recreatedScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val recreated =
            SharedHomeStore(
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
    fun realtimePinnedUpdatedUsesUserGlobalVersionGateAndFeedsSidebarProjection() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val versionStore = PerReceiverStateVersionStore(InMemoryRaftKeyValueStore())
        val store =
            SharedHomeStore(
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
    fun newerNotificationPrefsEchoClearsMutedStateOnEchoOnlyDevice() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val versionStore = PerReceiverStateVersionStore(InMemoryRaftKeyValueStore())
        val store =
            SharedHomeStore(
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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

    /**
     * AppFather 10-28: a muted conversation whose mute is no longer offered (an App bot DM after the
     * bot was removed: `activityMuteSupported` false) can still be unmuted, so a mute never gets
     * stuck; muting it again stays refused.
     */
    @Test
    fun aMutedConversationWithoutMuteSupportCanStillBeUnmutedButNotMutedAgain() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val failureMessages = mutableListOf<String>()
        val writes = mutableListOf<Pair<String, Boolean>>()
        val store =
            SharedHomeStore(
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
                dataSource =
                    sourceWithSnapshot(
                        sampleHome(ServerId("server-1")).copy(
                            channels =
                                listOf(
                                    channel("channel-1", "general").copy(
                                        activityNotificationPreference =
                                            ChannelActivityNotificationPreference(activityMuted = true, activityMuteSupported = false)
                                    )
                                )
                        )
                    ),
                scope = scope,
                setChannelActivityMuted = { channelId, muted ->
                    writes += channelId.value to muted
                    DataResult.Content(
                        ChannelActivityMuteSettings(
                            channelId = channelId,
                            activityMuted = muted,
                            muteFromSeq = null,
                            activityMuteSupported = false,
                            prefsVersion = 1
                        )
                    )
                },
                pinUpdateFailureCallback = failureMessages::add
            )

        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.SetChannelActivityMuted("channel-1", false))

        assertEquals(listOf("channel-1" to false), writes, "unmute is sent although mute is not supported")
        assertEquals(emptyList(), failureMessages)

        store.accept(SharedHomeIntent.SetChannelActivityMuted("channel-1", true))
        assertEquals(listOf("channel-1" to false), writes, "muting an unsupported conversation is still refused")
        assertEquals(listOf("Unable to update channel notifications"), failureMessages)
        scope.cancel()
    }

    @Test
    fun setChannelActivityMutedRollsBackLatestFailure() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val failureMessages = mutableListOf<String>()
        val store =
            SharedHomeStore(
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
                authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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

    private fun sourceWithSidebarOrder(
        initial: SettingsServerSidebarOrderSnapshot?,
        updateResult: DataResult<SettingsServerSidebarOrderSnapshot> = DataResult.Empty(EmptyReason.NoSettingsAvailable),
        updateCalls: MutableList<SettingsServerSidebarOrderUpdateRequest> = mutableListOf()
    ): SharedHomeDataSource =
        SharedHomeDataSource(
            sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
            authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
            authStore = InMemoryRaftAuthSessionStore(sampleSession()),
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

