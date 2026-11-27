package build.raft.app.data

import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.ActivityChanged
import build.raft.app.model.ActivityChangedObjectRef
import build.raft.app.model.ChannelId
import build.raft.app.model.InboxItemKind
import build.raft.app.model.ServerId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for `#wg-mobile-sync:#346` (`botiverse/slock` `#17040` / `#17064`):
 * `activity:changed` extended fields (`cause`, `object`, `requestId`), `X-Raft-Client-Request-Id`
 * wire header on object writes and inbox read-all, and own-write echo skipping on both visible
 * and hidden badge-owning `SharedActivityStore` instances.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivityOwnWriteEchoSkipTest {
    private val serverId = ServerId("server-1")

    private fun objectItem(
        id: String = "pt_1",
        unreadCount: Int = 2,
    ): InboxItem =
        InboxItem(
            key = "object:artifact:$id",
            kind = InboxItemKind.Object,
            channelId = ChannelId("ch-1"),
            channelName = "general",
            unreadCount = unreadCount,
            latestActivityAt = "2026-11-27T10:00:00.000Z",
            activityObject =
                ActivityObjectRow(
                    objectKind = "artifact",
                    objectId = id,
                    title = "Q3 report",
                    eventKind = "comment",
                    reason = "following",
                    doneFrontierSeq = "1842",
                ),
        )

    private class HeaderRecordingNetworkClient(
        private val responseBody: String = """{"ok":true}""",
    ) : RaftNetworkClient {
        data class Call(
            val path: String,
            val method: String,
            val serverId: ServerId?,
            val headers: Map<String, String>,
        )

        val calls = mutableListOf<Call>()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?,
        ): HomeNetworkResponse {
            calls += Call(path = path, method = method, serverId = serverIdOverride, headers = emptyMap())
            return HomeNetworkResponse(code = 200, bodyText = responseBody)
        }

        override suspend fun requestWithHeaders(
            priority: DataRequestPriority,
            path: String,
            headers: Map<String, String>,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?,
        ): HomeNetworkResponse {
            calls += Call(path = path, method = method, serverId = serverIdOverride, headers = headers)
            return HomeNetworkResponse(code = 200, bodyText = responseBody)
        }
    }

    private class RecordingObjectsRepository(
        var result: DataResult<Unit> = DataResult.Content(Unit),
        var onAct: suspend (action: ActivityObjectAction, clientRequestId: String?) -> Unit = { _, _ -> },
    ) : ActivityObjectsRepository {
        val calls = mutableListOf<Triple<String, ActivityObjectAction, String?>>()

        override suspend fun act(serverId: String, action: ActivityObjectAction, row: ActivityObjectRow): DataResult<Unit> =
            act(serverId, action, row, clientRequestId = null)

        override suspend fun act(
            serverId: String,
            action: ActivityObjectAction,
            row: ActivityObjectRow,
            clientRequestId: String?,
        ): DataResult<Unit> {
            calls += Triple(serverId, action, clientRequestId)
            onAct(action, clientRequestId)
            return result
        }

        override suspend fun unfollow(serverId: String, row: ActivityObjectRow): DataResult<Unit> =
            DataResult.Content(Unit)
    }

    @Test
    fun realtimeMapperParsesFullLegacyUnknownAndMalformedActivityChangedFailOpen() {
        val mapper = RealtimeEventMapper()
        val full =
            mapper.map(
                RealtimeSocketEvents.ActivityChanged,
                """{"serverId":"srv-1","source":"object","cause":"done","object":{"objectKind":"artifact","objectId":"pt_9"},"requestId":"req-1"}""",
            ) as? ActivityChanged
        assertEquals(
            ActivityChanged(
                serverId = ServerId("srv-1"),
                source = "object",
                cause = "done",
                objectRef = ActivityChangedObjectRef(objectKind = "artifact", objectId = "pt_9"),
                requestId = "req-1",
            ),
            full,
        )

        val legacy =
            mapper.map(
                RealtimeSocketEvents.ActivityChanged,
                """{"serverId":"srv-1","source":"object"}""",
            ) as? ActivityChanged
        assertEquals(ActivityChanged(serverId = ServerId("srv-1"), source = "object"), legacy)

        val unknownCause =
            mapper.map(
                RealtimeSocketEvents.ActivityChanged,
                """{"serverId":"srv-1","source":"object","cause":"future_cause","object":"bad","requestId":123}""",
            ) as? ActivityChanged
        assertEquals(
            ActivityChanged(
                serverId = ServerId("srv-1"),
                source = "object",
                cause = "future_cause",
                objectRef = null,
                requestId = null,
            ),
            unknownCause,
        )

        val missingServerId =
            mapper.map(
                RealtimeSocketEvents.ActivityChanged,
                """{"source":"object","requestId":"req-1"}""",
            )
        assertNull(missingServerId)
    }

    @Test
    fun repositoriesSendValidClientRequestIdHeaderOnObjectWritesAndInboxReadAll() = runTest {
        val client = HeaderRecordingNetworkClient()
        val objectsRepo = NetworkActivityObjectsRepository(client)
        val row = requireNotNull(objectItem().activityObject)
        objectsRepo.act("server-1", ActivityObjectAction.Done, row, "req-done_1")
        objectsRepo.act("server-1", ActivityObjectAction.Undone, row, "req-undone-2")
        objectsRepo.act("server-1", ActivityObjectAction.Read, row, "req-read-3")

        val readReceipts = RealReadReceiptRepository(InMemoryRaftAuthSessionStore(sampleSession()), client)
        readReceipts.markInboxReadAll(serverId, "req-read-all-4")

        assertEquals(
            listOf(
                "objects/activity/done" to "req-done_1",
                "objects/activity/undone" to "req-undone-2",
                "objects/activity/read" to "req-read-3",
                "channels/inbox/read-all" to "req-read-all-4",
            ),
            client.calls.map { it.path to it.headers[ActivityClientRequestIdHeader] },
        )
        assertTrue(isValidActivityClientRequestId(randomActivityClientRequestId()))
        assertFalse(isValidActivityClientRequestId("bad id with spaces"))
        assertFalse(isValidActivityClientRequestId("a".repeat(ActivityClientRequestIdMaxChars + 1)))
    }

    @Test
    fun ownWriteTrackerEnforcesExactMatchSingleUseAndTtl() {
        var now = 1_000L
        var seq = 0
        val tracker = OwnActivityRequestTracker(nowMillis = { now }, keptMillis = 60_000L, mintId = { "req-${++seq}" })

        val id1 = tracker.mint()
        assertFalse(tracker.take(null))
        assertFalse(tracker.take("other-device"))
        assertTrue(tracker.take(id1))
        assertFalse(tracker.take(id1), "single-use: second frame with same id must not skip")

        val id2 = tracker.mint()
        now += 60_001L
        assertFalse(tracker.take(id2), "expired past 60s TTL must not skip")
    }

    @Test
    fun objectReadAndDoneSkipOwnEchoEvenWhenEchoArrivesMidFlight() = runTest {
        val row = objectItem("pt_1", unreadCount = 2)
        val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(row))))
        var storeRef: SharedActivityStore? = null
        val gate = CompletableDeferred<Unit>()
        var nextId = 0
        val objectsRepo =
            RecordingObjectsRepository(
                onAct = { action, reqId ->
                    // Server announces activity:changed BEFORE HTTP response returns.
                    storeRef?.dispatch(
                        SharedActivityIntent.ApplyRealtimeEvent(
                            ActivityChanged(
                                serverId = serverId,
                                source = "object",
                                cause = if (action == ActivityObjectAction.Read) "read" else "done",
                                requestId = reqId,
                            )
                        )
                    )
                    if (action == ActivityObjectAction.Done) gate.await()
                },
            )
        val store =
            activityStore(
                scope = backgroundScope,
                inboxRepository = inbox,
                activityObjectsRepository = objectsRepo,
                realtimeBackgroundRefreshIntervalMillis = 0L,
                activityClientRequestIdGenerator = { "own-${++nextId}" },
            )
        storeRef = store
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        assertEquals(1, inbox.initialLoadCount)

        store.dispatch(SharedActivityIntent.MarkRead(row))
        runCurrent()
        assertEquals(1, inbox.initialLoadCount, "object read echo must trigger zero list re-reads")

        store.dispatch(SharedActivityIntent.MarkDone(store.state.value.items.single()))
        runCurrent()
        assertEquals(1, inbox.initialLoadCount, "mid-flight object done echo must trigger zero list re-reads")
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, inbox.initialLoadCount)
        assertEquals(listOf("own-1", "own-2"), objectsRepo.calls.map { it.third })
    }

    @Test
    fun markAllReadAndUndoObjectDoneSkipRedundantEchoWhileNonMatchingFramesRefresh() = runTest {
        val row = objectItem("pt_2", unreadCount = 1)
        val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(row))))
        var storeRef: SharedActivityStore? = null
        var nextId = 0
        val readReceipts =
            FakeReadReceiptRepository(
                onInboxReadAll = { sid, reqId ->
                    storeRef?.dispatch(
                        SharedActivityIntent.ApplyRealtimeEvent(
                            ActivityChanged(serverId = sid, source = "object", cause = "read_all", requestId = reqId)
                        )
                    )
                },
            )
        val objectsRepo =
            RecordingObjectsRepository(
                onAct = { _, reqId ->
                    storeRef?.dispatch(
                        SharedActivityIntent.ApplyRealtimeEvent(
                            ActivityChanged(serverId = serverId, source = "object", cause = "undone", requestId = reqId)
                        )
                    )
                },
            )
        val store =
            activityStore(
                scope = backgroundScope,
                inboxRepository = inbox,
                readReceiptRepository = readReceipts,
                activityObjectsRepository = objectsRepo,
                realtimeBackgroundRefreshIntervalMillis = 0L,
                activityClientRequestIdGenerator = { "req-${++nextId}" },
            )
        storeRef = store
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        assertEquals(1, inbox.initialLoadCount)

        // MarkAllRead does 1 explicit Manual refresh; its echoed activity:changed must NOT add a 2nd read.
        store.dispatch(SharedActivityIntent.MarkAllRead)
        runCurrent()
        assertEquals(2, inbox.initialLoadCount)
        assertEquals("req-1", readReceipts.inboxReadAllRequestIds.single().second)

        // UndoObjectDone does 1 explicit Manual refresh; its echoed activity:changed must NOT add a 2nd read.
        store.dispatch(SharedActivityIntent.UndoObjectDone(row))
        runCurrent()
        assertEquals(3, inbox.initialLoadCount)
        assertEquals("req-2", objectsRepo.calls.single().third)

        // Duplicate of already-consumed requestId, null requestId, and other-device requestId all refresh.
        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(ActivityChanged(serverId, "object", "undone", requestId = "req-2")))
        runCurrent()
        assertEquals(4, inbox.initialLoadCount)

        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(ActivityChanged(serverId, "object", "event", requestId = null)))
        runCurrent()
        assertEquals(5, inbox.initialLoadCount)

        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(ActivityChanged(serverId, "object", "done", requestId = "other-tab")))
        runCurrent()
        assertEquals(6, inbox.initialLoadCount)
    }

    @Test
    fun hiddenBadgeOwningStoreSkipsOwnEchoAndRefreshesOnOtherDeviceFrame() = runTest {
        val row = objectItem("pt_3", unreadCount = 1)
        val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(row))))
        var storeRef: SharedActivityStore? = null
        val objectsRepo =
            RecordingObjectsRepository(
                onAct = { _, reqId ->
                    storeRef?.dispatch(
                        SharedActivityIntent.ApplyRealtimeSummaryEvent(
                            event = ActivityChanged(serverId = serverId, source = "object", cause = "done", requestId = reqId),
                            primarySurfaceVisible = false,
                        )
                    )
                },
            )
        val store =
            activityStore(
                scope = backgroundScope,
                inboxRepository = inbox,
                activityObjectsRepository = objectsRepo,
                inboxInPlaceEnabled = { true },
                inboxRowPositionEnabled = { true },
                realtimeBackgroundRefreshIntervalMillis = 0L,
                hiddenRealtimeBackgroundRefreshIntervalMillis = 0L,
                activityClientRequestIdGenerator = { "hidden-own-1" },
            )
        storeRef = store
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        store.dispatch(SharedActivityIntent.SurfaceActive(false))
        assertEquals(1, inbox.initialLoadCount)

        store.dispatch(SharedActivityIntent.MarkDone(row))
        runCurrent()
        assertEquals(1, inbox.initialLoadCount, "hidden badge-owning store must skip own activity:changed echo")

        store.dispatch(
            SharedActivityIntent.ApplyRealtimeSummaryEvent(
                event = ActivityChanged(serverId = serverId, source = "object", cause = "event", requestId = "remote-1"),
                primarySurfaceVisible = false,
            )
        )
        runCurrent()
        assertEquals(2, inbox.initialLoadCount, "other-device activity:changed must still refresh hidden store")
    }

    @Test
    fun failedObjectWriteForgetsRequestIdAndRefreshes() = runTest {
        val row = objectItem("pt_4", unreadCount = 1)
        val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(row))))
        val objectsRepo = RecordingObjectsRepository(result = DataResult.Error(ScreenError("server_unavailable")))
        val store =
            activityStore(
                scope = backgroundScope,
                inboxRepository = inbox,
                activityObjectsRepository = objectsRepo,
                realtimeBackgroundRefreshIntervalMillis = 0L,
                activityClientRequestIdGenerator = { "fail-req-1" },
            )
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        assertEquals(1, inbox.initialLoadCount)

        store.dispatch(SharedActivityIntent.MarkDone(row))
        runCurrent()
        assertEquals(2, inbox.initialLoadCount, "failed write refreshes after failure")

        // The forgotten requestId must no longer suppress an incoming activity:changed frame.
        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(ActivityChanged(serverId, "object", "done", requestId = "fail-req-1")))
        runCurrent()
        assertEquals(3, inbox.initialLoadCount)
        assertNotNull(objectsRepo.calls.single().third)
    }

    @Test
    fun expiredOwnRequestIdPast60sStillRefreshes() = runTest {
        val row = objectItem("pt_5", unreadCount = 1)
        val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(row))))
        val objectsRepo = RecordingObjectsRepository()
        val store =
            activityStore(
                scope = backgroundScope,
                inboxRepository = inbox,
                activityObjectsRepository = objectsRepo,
                realtimeBackgroundRefreshIntervalMillis = 0L,
                activityClientRequestIdGenerator = { "ttl-req-1" },
            )
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        store.dispatch(SharedActivityIntent.MarkDone(row))
        runCurrent()
        assertEquals(1, inbox.initialLoadCount)

        advanceTimeBy(OwnActivityRequestKeptMillis + 1L)
        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(ActivityChanged(serverId, "object", "done", requestId = "ttl-req-1")))
        runCurrent()
        assertEquals(2, inbox.initialLoadCount)
    }
}
