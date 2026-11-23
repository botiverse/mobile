package build.raft.app.data

import build.raft.app.deferWhile
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import kotlinx.serialization.builtins.serializer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HomeScrollPerfDeferralTest {
    @Test
    fun realInboxAndProcessActivityUnreadParseOnJsonParseDispatcherByDefault() = runBlocking {
        val inboxLogs = mutableListOf<String>()
        val unreadLogs = mutableListOf<String>()
        val network =
            object : RaftNetworkClient {
                override suspend fun request(
                    priority: DataRequestPriority,
                    path: String,
                    method: String,
                    query: List<Pair<String, String>>,
                    serverIdOverride: ServerId?,
                    bodyText: String?,
                ): HomeNetworkResponse =
                    HomeNetworkResponse(
                        code = 200,
                        bodyText =
                            """{"items":[{"kind":"channel","channelId":"c-1","channelName":"general","channelType":"channel",""" +
                                """"lastMessageId":"m-1","latestActivitySeq":"10","lastMessageAt":"2026-11-22T10:00:00Z",""" +
                                """"lastMessagePreview":"hi","unreadCount":1,"hasMention":false}],""" +
                                """"hasMore":false,"totalCount":1,"totalUnreadCount":1,"activeUnreadCount":1}"""
                    )
            }
        val session = InMemoryRaftAuthSessionStore(sampleSession())
        val inboxRepo = RealInboxRepository(session, network, logger = { inboxLogs += it })
        val unreadRepo = ProcessActivityUnreadRepository(session, network, logger = { unreadLogs += it })

        assertTrue(inboxRepo.loadInitial(ServerId("server-1")) is DataResult.Content)
        assertTrue(inboxRepo.loadRows(ServerId("server-1"), listOf("c-1")) is DataResult.Content)
        val owner = ProcessActivityUnreadOwner(environmentId = "https://api.raft.build", accountId = "user-1", serverId = "server-1")
        assertTrue(unreadRepo.load(owner, DataRequestPriority.Background) is DataResult.Content)

        assertTrue(
            inboxLogs.any { "inbox_parse_start source=inbox " in it && "dispatcher=JsonParse" in it },
            inboxLogs.joinToString("\n")
        )
        assertTrue(
            inboxLogs.any { "inbox_parse_start source=inbox_rows " in it && "dispatcher=JsonParse" in it },
            inboxLogs.joinToString("\n")
        )
        assertFalse(inboxLogs.any { "dispatcher=caller" in it }, inboxLogs.joinToString("\n"))
        assertTrue(
            unreadLogs.any { "inbox_parse_start source=process_activity_unread " in it && "dispatcher=JsonParse" in it },
            unreadLogs.joinToString("\n")
        )
        assertFalse(unreadLogs.any { "dispatcher=caller" in it }, unreadLogs.joinToString("\n"))
    }

    @Test
    fun activityRowPositionAnswerMergeLogsOneBatchLineInsteadOfPerRow() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val logs = mutableListOf<String>()
        val initialRows = (0 until 20).map { i -> activityItem("c-$i", unreadCount = 1).copy(latestActivitySeq = "${100 + i}") }
        val refreshedRows = (0 until 20).map { i -> activityItem("c-$i", unreadCount = 2).copy(latestActivitySeq = "${200 + i}") }
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(initialRows, totalCount = 20, totalUnreadCount = 20),
                        snapshot(refreshedRows, totalCount = 20, totalUnreadCount = 40),
                    )
            )
        val store =
            activityStore(
                scope = scope,
                inboxRepository = inbox,
                diagnosticsLogger = { logs += it },
                inboxInPlaceEnabled = { true },
                inboxRowPositionEnabled = { true },
            )

        store.dispatch(SharedActivityIntent.LoadInitial)
        logs.clear()
        store.dispatch(SharedActivityIntent.Refresh)

        val perRowAnswerLogs = logs.filter { "activity_row_position event=answer " in it }
        val batchLogs = logs.filter { "activity_row_position event=answer_batch source=list" in it }
        assertEquals(0, perRowAnswerLogs.size, "per-row logs must be collapsed: $perRowAnswerLogs")
        assertEquals(1, batchLogs.size, "expected one batch summary line: ${logs.joinToString("\n")}")
        assertTrue("rows=20" in batchLogs.single(), batchLogs.single())
        scope.cancel()
    }

    @Test
    fun deferWhileCoalescesUpdatesWhilePausedAndHonorsMaxHoldBoundAndEnabledFlip() = runTest {
        val paused = MutableStateFlow(true)
        val enabled = MutableStateFlow(true)
        val upstream = MutableSharedFlow<Int>(extraBufferCapacity = 16)
        val received = mutableListOf<Int>()

        val collector =
            backgroundScope.launch {
                upstream
                    .deferWhile(paused = paused, enabled = enabled, maxHoldMillis = 1_500L, timeSource = testTimeSource)
                    .collect { received += it }
            }
        runCurrent()

        upstream.tryEmit(1)
        upstream.tryEmit(2)
        upstream.tryEmit(3)
        advanceTimeBy(400L)
        runCurrent()
        assertEquals(emptyList(), received, "nothing emitted while paused and enabled")

        paused.value = false
        runCurrent()
        assertEquals(listOf(3), received, "only the latest coalesced value emits on settle")

        upstream.tryEmit(4)
        runCurrent()
        assertEquals(listOf(3, 4), received, "unpaused values pass through immediately")

        // Continuous drag past maxHoldMillis still flushes so updates never starve.
        paused.value = true
        upstream.tryEmit(5)
        advanceTimeBy(1_000L)
        runCurrent()
        upstream.tryEmit(6)
        advanceTimeBy(500L)
        runCurrent()
        assertEquals(listOf(3, 4, 6), received, "bounded wait flushes from the first deferred item")

        // Flipping enabled=false (e.g. hidden tab becomes active mid-scroll) flushes immediately.
        upstream.tryEmit(7)
        advanceTimeBy(100L)
        runCurrent()
        assertEquals(listOf(3, 4, 6), received)
        enabled.value = false
        runCurrent()
        assertEquals(listOf(3, 4, 6, 7), received, "becoming active flushes deferred emission immediately")
        collector.cancel()
    }

    @Test
    fun scrollDeferredPersistSliceCoalescesWritesKeepsImmediateRemoveAndFencesDropServer() = runTest {
        var scrolling = true
        var scrollSettled = CompletableDeferred<Unit>()
        val storage = CountingPersistStorage()
        val middleware =
            MviPersistMiddleware(
                storage = storage,
                spec =
                    object : MviPersistSpec<SharedActivityState, SharedActivityIntent, String> {
                        override val name: String = "test_activity"
                        override val version: Int = 1
                        override val snapshotSerializer = String.serializer()

                        override fun key(
                            previous: SharedActivityState,
                            intent: SharedActivityIntent,
                            reduced: SharedActivityState
                        ): String = "mvi:persist:test:v1:user:${reduced.ownerUserId ?: "u1"}:server:${reduced.ownerServerId ?: "s1"}"

                        override fun partialize(state: SharedActivityState): String? =
                            state.snapshot?.totalUnreadCount?.toString()

                        override fun mergeHydrated(
                            previous: SharedActivityState,
                            intent: SharedActivityIntent,
                            reduced: SharedActivityState,
                            snapshot: String,
                            metadata: MviPersistMetadata,
                        ): SharedActivityState =
                            reduced.copy(
                                snapshot = (reduced.snapshot ?: snapshot(emptyList())).copy(totalUnreadCount = snapshot.toInt())
                            )

                        override fun shouldHydrate(
                            previous: SharedActivityState,
                            intent: SharedActivityIntent,
                            reduced: SharedActivityState,
                        ): Boolean = intent is SharedActivityIntent.LoadInitial

                        override fun shouldPersist(
                            previous: SharedActivityState,
                            intent: SharedActivityIntent,
                            next: SharedActivityState
                        ): Boolean = intent is SharedActivityIntent.Refresh

                        override fun shouldRemove(
                            previous: SharedActivityState,
                            intent: SharedActivityIntent,
                            next: SharedActivityState
                        ): Boolean = next.errorCode == "unauthenticated"
                    }
            )
        val slice =
            ScrollDeferredMviPersistSlice(
                delegate = middleware,
                persistScope = backgroundScope,
                isScrollActive = { scrolling },
                awaitScrollSettled = { scrollSettled.await() },
            )

        fun stateWithUnread(unread: Int, serverId: String = "s1", errorCode: String? = null) =
            SharedActivityState(
                ownerServerId = serverId,
                ownerUserId = "u1",
                loaded = true,
                errorCode = errorCode,
                snapshot = snapshot(emptyList(), totalUnreadCount = unread),
            )

        fun dispatch(previous: SharedActivityState, intent: SharedActivityIntent, next: SharedActivityState): SharedActivityState {
            val reduced = slice.reduce(previous, intent, next)
            slice.onTransition(previous, intent, reduced)
            return reduced
        }

        val keyS1 = "mvi:persist:test:v1:user:u1:server:s1"
        val keyS2 = "mvi:persist:test:v1:user:u1:server:s2"
        val s0 = stateWithUnread(0)
        val s1 = stateWithUnread(1)
        val s2 = stateWithUnread(2)
        val s3 = stateWithUnread(3)

        // Burst of 3 writes + a no-op intent during scroll (full reduce -> onTransition store path) -> 0 disk writes while scrolling.
        dispatch(s0, SharedActivityIntent.Refresh, s1)
        dispatch(s1, SharedActivityIntent.Refresh, s2)
        dispatch(s2, SharedActivityIntent.Refresh, s3)
        dispatch(s3, SharedActivityIntent.SurfaceActive(false), s3)
        runCurrent()
        assertEquals(0, storage.writeCount, "non-hydrating reduce + onTransition must not flush writes while Home is scrolling")

        // Scroll settles -> single coalesced write carrying the latest state (3).
        scrolling = false
        scrollSettled.complete(Unit)
        runCurrent()
        assertEquals(1, storage.writeCount, "burst coalesces into a single write")
        assertTrue(storage.read(keyS1).orEmpty().contains("\"3\""), storage.read(keyS1))

        // Hydrating reduce() during scroll flushes the pending write for that key first so hydration never reads stale data.
        scrolling = true
        scrollSettled = CompletableDeferred()
        dispatch(s3, SharedActivityIntent.Refresh, stateWithUnread(6))
        runCurrent()
        assertEquals(1, storage.writeCount, "still deferred before hydrating reduce")
        val hydrated = slice.reduce(s0, SharedActivityIntent.LoadInitial, stateWithUnread(0))
        assertEquals(6, hydrated.snapshot?.totalUnreadCount, "hydrating reduce flushes pending write before reading")
        assertEquals(2, storage.writeCount)
        scrolling = false
        scrollSettled.complete(Unit)
        runCurrent()
        assertEquals(2, storage.writeCount, "already-flushed pending write is not written twice")

        // Removal while flushPending is genuinely suspended executes immediately and never resurrects.
        scrolling = true
        scrollSettled = CompletableDeferred()
        dispatch(s3, SharedActivityIntent.Refresh, stateWithUnread(4))
        runCurrent()
        dispatch(s3, SharedActivityIntent.Refresh, stateWithUnread(4, errorCode = "unauthenticated"))
        assertNull(storage.read(keyS1), "unauthenticated removal never waits for scroll")
        assertEquals(1, storage.removeCount)
        scrolling = false
        scrollSettled.complete(Unit)
        runCurrent()
        assertNull(storage.read(keyS1), "fenced write must not resurrect after scroll settles")
        assertEquals(2, storage.writeCount)

        // Server access loss during scroll fences only the dropped server while other servers flush normally.
        scrolling = true
        scrollSettled = CompletableDeferred()
        dispatch(s0, SharedActivityIntent.Refresh, stateWithUnread(5, serverId = "s1"))
        dispatch(stateWithUnread(0, serverId = "s2"), SharedActivityIntent.Refresh, stateWithUnread(7, serverId = "s2"))
        runCurrent()
        slice.dropServer(serverId = "s1", userId = "u1")
        scrolling = false
        scrollSettled.complete(Unit)
        runCurrent()
        assertNull(storage.read(keyS1), "dropped server's deferred write must never land on disk")
        assertTrue(storage.read(keyS2).orEmpty().contains("\"7\""), storage.read(keyS2))
        assertEquals(3, storage.writeCount)

        // Sign-out discardPending fences every queued write across all servers.
        scrolling = true
        scrollSettled = CompletableDeferred()
        dispatch(stateWithUnread(7, serverId = "s2"), SharedActivityIntent.Refresh, stateWithUnread(9, serverId = "s2"))
        runCurrent()
        slice.discardPending()
        scrolling = false
        scrollSettled.complete(Unit)
        runCurrent()
        assertTrue(storage.read(keyS2).orEmpty().contains("\"7\""), "discarded write must not overwrite")
        assertEquals(3, storage.writeCount)
    }

    @Test
    fun hiddenActivityTabDefersProgressiveAndFinalProjectionUntilHomeScrollSettlesEvenWithoutRowPositionFlag() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val rows = (0 until 150).map { i -> activityItem("r$i") }
        val page1 = CompletableDeferred<DataResult<InboxSnapshot>>()
        val page2 = CompletableDeferred<DataResult<InboxSnapshot>>()
        var callCount = 0
        val inbox =
            object : InboxRepository {
                override suspend fun loadInitial(serverId: ServerId, filter: InboxFilter, limit: Int): DataResult<InboxSnapshot> {
                    callCount += 1
                    return if (callCount == 1) {
                        DataResult.Content(snapshot(rows.take(100), hasMore = true, nextOffset = 100, totalCount = 150))
                    } else {
                        page1.await()
                    }
                }

                override suspend fun loadMore(serverId: ServerId, filter: InboxFilter, offset: Int, limit: Int): DataResult<InboxSnapshot> {
                    callCount += 1
                    return if (callCount == 2) {
                        DataResult.Content(snapshot(rows.drop(100), hasMore = false, nextOffset = 150, totalCount = 150))
                    } else {
                        page2.await()
                    }
                }

                override suspend fun markDone(channelId: ChannelId, frontier: InboxDoneFrontier): DataResult<InboxDoneResult> =
                    DataResult.Content(InboxDoneResult(channelId))
            }
        val scrolling = MutableStateFlow(false)
        val logs = mutableListOf<String>()
        val store =
            activityStore(
                scope = scope,
                inboxRepository = inbox,
                diagnosticsLogger = { logs += it },
                inboxInPlaceEnabled = { true },
                inboxRowPositionEnabled = { false },
                isBackgroundProjectionDeferred = { scrolling.value },
                awaitBackgroundProjectionWindow = { wakeWhen ->
                    combine(scrolling, wakeWhen) { activeScroll, wake -> !activeScroll || wake }.first { it }
                },
            )

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.dispatch(SharedActivityIntent.LoadMore)
        assertEquals(150, store.state.value.items.size)

        // Hide Activity tab and start scrolling Home.
        store.dispatch(SharedActivityIntent.SurfaceActive(false))
        scrolling.value = true
        logs.clear()

        val updated = activityItem("fresh", unreadCount = 3)
        store.dispatch(SharedActivityIntent.Refresh)
        page1.complete(DataResult.Content(snapshot(listOf(updated) + rows.take(99), hasMore = true, nextOffset = 100, totalCount = 151, totalUnreadCount = 3)))

        // Intermediate page 1 publish skipped while hidden + Home scrolling.
        assertFalse(logs.any { "phase=page_published" in it }, logs.joinToString("\n"))
        assertEquals("channel:r0", store.state.value.items.first().key)

        // Page 2 completes while still scrolling -> final apply waits for scroll to settle.
        page2.complete(DataResult.Content(snapshot(rows.slice(99 until 150), hasMore = false, nextOffset = 151, totalCount = 151, totalUnreadCount = 3)))
        assertEquals("channel:r0", store.state.value.items.first().key, "final hidden projection waits for Home scroll")

        // Scroll settles -> combined snapshot publishes once.
        scrolling.value = false
        assertEquals("channel:fresh", store.state.value.items.first().key)
        assertEquals(3, store.state.value.totalUnreadCount)
        scope.cancel()
    }

    @Test
    fun hiddenActivityTabWakesDeferredProjectionImmediatelyWhenTabBecomesActive() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val initialRow = activityItem("initial", unreadCount = 1)
        val freshRow = activityItem("fresh", unreadCount = 5)
        val refreshDeferred = CompletableDeferred<DataResult<InboxSnapshot>>()
        var callCount = 0
        val inbox =
            object : InboxRepository {
                override suspend fun loadInitial(serverId: ServerId, filter: InboxFilter, limit: Int): DataResult<InboxSnapshot> {
                    callCount += 1
                    return if (callCount == 1) {
                        DataResult.Content(snapshot(listOf(initialRow), hasMore = false, totalCount = 1, totalUnreadCount = 1))
                    } else {
                        refreshDeferred.await()
                    }
                }

                override suspend fun loadMore(serverId: ServerId, filter: InboxFilter, offset: Int, limit: Int): DataResult<InboxSnapshot> =
                    DataResult.Empty(EmptyReason.NoInboxItems)

                override suspend fun markDone(channelId: ChannelId, frontier: InboxDoneFrontier): DataResult<InboxDoneResult> =
                    DataResult.Content(InboxDoneResult(channelId))
            }
        val scrolling = MutableStateFlow(false)
        val store =
            activityStore(
                scope = scope,
                inboxRepository = inbox,
                isBackgroundProjectionDeferred = { scrolling.value },
                awaitBackgroundProjectionWindow = { wakeWhen ->
                    combine(scrolling, wakeWhen) { activeScroll, wake -> !activeScroll || wake }.first { it }
                },
            )

        store.dispatch(SharedActivityIntent.LoadInitial)
        store.dispatch(SharedActivityIntent.SurfaceActive(false))
        scrolling.value = true

        store.dispatch(SharedActivityIntent.Refresh)
        refreshDeferred.complete(DataResult.Content(snapshot(listOf(freshRow), hasMore = false, totalCount = 1, totalUnreadCount = 5)))
        assertEquals("channel:initial", store.state.value.items.first().key, "held while hidden and Home is still scrolling")

        // User switches to Activity while Home scroll flag has not settled yet -> wakes immediately.
        store.dispatch(SharedActivityIntent.SurfaceActive(true))
        assertEquals("channel:fresh", store.state.value.items.first().key)
        assertEquals(5, store.state.value.totalUnreadCount)
        scope.cancel()
    }

    @Test
    fun sharedHomeIdentityPersistSliceSkipsHydrationReadWhenContentAlreadyLoaded() {
        val storage = CountingPersistStorage()
        val slice = createSharedHomeIdentityPersistSlice(storage = storage)
        val session = sampleSession()
        val home =
            HomeSnapshot(
                server = ServerSummary(ServerId("server-1"), "Server One"),
                currentMember =
                    MemberProfile(
                        id = MemberId(session.user.id),
                        displayName = "Viewer",
                        handle = "viewer",
                        kind = MemberKind.Human,
                    ),
                channels = emptyList(),
                directMessages = emptyList(),
                unreadTotal = 0,
            )
        val loaded =
            SharedHomeState(
                content =
                    SharedHomeContentState.Content(
                        snapshot = home,
                        session = session,
                        contentRefreshing = false,
                        baseSnapshot = home,
                    )
            )
        // Persist once so storage has a valid payload.
        slice.onTransition(SharedHomeState(), SharedHomeIntent.Start, loaded)
        assertNotNull(storage.read("mvi:persist:home:identity:v1:user:${session.user.id}:server:server-1"))
        storage.readCount = 0

        // Subsequent Start reduction while Content is already present must not read storage.
        val reduced = slice.reduce(previous = loaded, intent = SharedHomeIntent.Start, reduced = loaded)
        assertEquals(loaded, reduced)
        assertEquals(0, storage.readCount, "shouldHydrate must skip reading storage when Content is already loaded")
    }

    private class CountingPersistStorage : MviPersistStorage {
        private val map = linkedMapOf<String, String>()
        var readCount = 0
        var writeCount = 0
        var removeCount = 0

        override fun read(key: String): String? {
            readCount += 1
            return map[key]
        }

        override fun write(key: String, value: String) {
            writeCount += 1
            map[key] = value
        }

        override fun remove(key: String) {
            removeCount += 1
            map.remove(key)
        }
    }
}
