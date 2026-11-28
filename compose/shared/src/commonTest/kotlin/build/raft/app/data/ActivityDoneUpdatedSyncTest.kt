package build.raft.app.data

import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.ActivityChanged
import build.raft.app.model.ActivityDoneRowKind
import build.raft.app.model.ActivityDoneUpdated
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Live `activity_done:updated` sync (#wg-mobile-sync:#309, botiverse/slock #16816 / #triage:#632):
 * verifies wire parsing, serverId scoping, numeric position comparison, null-position unconditional
 * drop, joint-channel / joint-parent-thread skip, object-row frontier & count invariants,
 * single-row `?ids=` fetch & insert on undone (with `inbox_row_position_v1` both off and on),
 * object undone reload without double-storm, and originating-device no-op behavior.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivityDoneUpdatedSyncTest {

    private data class RecordedRead(
        val reason: InboxFetchReason?,
        val trigger: InboxRefreshTriggerCount?,
        val rowIds: List<String>?,
    )

    private class ScriptedRowInbox(
        private val lists: MutableList<InboxSnapshot>,
        private var servedRows: List<InboxItem> = emptyList(),
        private val rowAnswersQueue: ArrayDeque<List<InboxItem>> = ArrayDeque(),
    ) : InboxRepository by FakeInboxRepository(lists) {
        val reads = mutableListOf<RecordedRead>()
        private var listCursor = 0

        fun serveList(snapshot: InboxSnapshot) {
            lists += snapshot
        }

        fun serveRows(vararg items: InboxItem) {
            servedRows = items.toList()
        }

        fun enqueueRowAnswers(vararg answers: List<InboxItem>) {
            answers.forEach(rowAnswersQueue::addLast)
        }

        override suspend fun loadInitial(serverId: ServerId, filter: InboxFilter, limit: Int): DataResult<InboxSnapshot> {
            val context = currentCoroutineContext()[InboxFetchReasonContext]
            reads += RecordedRead(context?.reason, context?.trigger, rowIds = null)
            val snap = lists.getOrElse(listCursor++) { lists.last() }
            return DataResult.Content(snap)
        }

        override suspend fun loadRows(serverId: ServerId, rowIds: List<String>): DataResult<InboxSnapshot> {
            val context = currentCoroutineContext()[InboxFetchReasonContext]
            reads += RecordedRead(context?.reason, context?.trigger, rowIds = rowIds)
            val source = if (rowAnswersQueue.isNotEmpty()) rowAnswersQueue.removeFirst() else servedRows
            val wanted = rowIds.toSet()
            val matched = source.filter { item -> item.activityRowScopeId() in wanted }
            return DataResult.Content(snapshot(matched, serverId = serverId.value))
        }

        val listReads: List<RecordedRead> get() = reads.filter { it.rowIds == null }
        val rowReads: List<RecordedRead> get() = reads.filter { it.rowIds != null }
    }

    private class Fixture(
        val store: SharedActivityStore,
        val inbox: ScriptedRowInbox,
        val facts: List<ActivityUnreadSummaryFact>,
    )

    private fun TestScope.fixture(
        initial: InboxSnapshot,
        rowPosition: Boolean = false,
        inPlace: Boolean = false,
        settleThrottle: Boolean = true,
    ): Fixture {
        val inbox = ScriptedRowInbox(mutableListOf(initial))
        val facts = mutableListOf<ActivityUnreadSummaryFact>()
        val store =
            activityStore(
                scope = backgroundScope,
                inboxRepository = inbox,
                onSummaryFact = { facts += it },
                realtimeBackgroundRefreshIntervalMillis = ActivityRealtimeBackgroundRefreshIntervalMillis,
                nowMillis = { testScheduler.currentTime },
                appForeground = MutableStateFlow(true),
                inboxInPlaceEnabled = { inPlace },
                inboxRowPositionEnabled = { rowPosition },
            )
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        if (settleThrottle) {
            advanceTimeBy(ActivityRealtimeBackgroundRefreshIntervalMillis + 500L)
            runCurrent()
        }
        return Fixture(store, inbox, facts)
    }

    private fun channelRow(
        id: String,
        latestActivitySeq: String? = "40",
        unreadCount: Int = 0,
        lastMessageAt: String = "2026-11-11T09:00:00.000Z",
        channelType: String = "channel",
        channelKind: ConversationKind = ConversationKind.Channel,
    ): InboxItem =
        activityItem(id, unreadCount = unreadCount).copy(
            channelType = channelType,
            channelKind = channelKind,
            lastMessageAt = lastMessageAt,
            latestActivityAt = lastMessageAt,
            latestActivitySeq = latestActivitySeq,
            readStateLatestActivitySeq = latestActivitySeq,
            doneFrontierSeq = latestActivitySeq,
            doneFrontierSeqPresent = latestActivitySeq != null,
        )

    private fun dmRow(
        id: String,
        latestActivitySeq: String? = "7",
        unreadCount: Int = 1,
        lastMessageAt: String = "2026-11-11T10:00:00.000Z",
    ): InboxItem =
        InboxItem(
            key = "dm:$id",
            kind = InboxItemKind.DirectMessage,
            channelId = ChannelId(id),
            channelName = "bob",
            channelKind = ConversationKind.DirectMessage,
            channelType = "dm",
            unreadCount = unreadCount,
            firstUnreadMessageId = if (unreadCount > 0) MessageId("first-$id") else null,
            lastMessageId = MessageId("last-$id"),
            latestActivityMessageId = MessageId("last-$id"),
            lastMessageAt = lastMessageAt,
            latestActivityAt = lastMessageAt,
            latestActivitySeq = latestActivitySeq,
            readStateLatestActivitySeq = latestActivitySeq,
            doneFrontierSeq = latestActivitySeq,
            doneFrontierSeqPresent = latestActivitySeq != null,
        )

    private fun replyThreadRow(
        id: String,
        latestActivitySeq: String? = "51",
        unreadCount: Int = 1,
        lastActivityAt: String = "2026-11-11T08:00:00.000Z",
        parentChannelType: String = "channel",
        parentChannelKind: ConversationKind = ConversationKind.Channel,
        task: Boolean = false,
    ): InboxItem =
        threadItem(id, unreadCount = unreadCount).copy(
            parentChannelType = parentChannelType,
            parentChannelKind = parentChannelKind,
            latestActivityAt = lastActivityAt,
            lastMessageAt = lastActivityAt,
            latestActivitySeq = latestActivitySeq,
            readStateLatestActivitySeq = latestActivitySeq,
            doneFrontierSeq = latestActivitySeq,
            doneFrontierSeqPresent = latestActivitySeq != null,
            isFollowing = true,
            taskConversation = if (task) InboxTaskConversation(TaskId("task-$id"), "Ship sync") else null,
            taskNumber = if (task) 309 else null,
            taskStatus = if (task) TaskStatus.InProgress else null,
        )

    private fun objectRow(
        objectId: String = "artifact-632",
        objectKind: String = "artifact",
        doneFrontierSeq: String = "12",
        unreadCount: Int = 1,
        hasMention: Boolean = false,
        lastActivityAt: String = "2026-11-11T11:00:00.000Z",
    ): InboxItem =
        InboxItem(
            key = activityObjectRowKey(objectKind, objectId),
            kind = InboxItemKind.Object,
            channelId = ChannelId("object:$objectId"),
            channelName = "Design doc",
            unreadCount = unreadCount,
            hasMention = hasMention,
            lastMessageAt = lastActivityAt,
            latestActivityAt = lastActivityAt,
            isFollowing = true,
            activityObject =
                ActivityObjectRow(
                    objectKind = objectKind,
                    objectId = objectId,
                    title = "Design doc",
                    eventKind = "comment",
                    reason = "following",
                    eventAt = lastActivityAt,
                    isFollowing = true,
                    doneFrontierSeq = doneFrontierSeq,
                ),
        )

    private fun doneEvent(
        serverId: String = "server-1",
        rowKind: ActivityDoneRowKind,
        rowId: String,
        done: Boolean,
        position: String?,
        objectKind: String? = null,
    ): SharedActivityIntent.ApplyRealtimeEvent =
        SharedActivityIntent.ApplyRealtimeEvent(
            ActivityDoneUpdated(
                serverId = ServerId(serverId),
                rowKind = rowKind,
                rowId = rowId,
                objectKind = objectKind,
                done = done,
                position = position,
            )
        )

    // --- 1 & 2: Wire parser tests ---

    @Test
    fun wireParserAcceptsAllFiveRowKindsAndDoneAndUndonePayloads() {
        val mapper = RealtimeEventMapper(diagnosticsLogger = {})
        assertEquals("activity_done:updated", RealtimeSocketEvents.ActivityDoneUpdated)
        assertTrue(RealtimeSocketEvents.ActivityDoneUpdated in RealtimeSocketEvents.ServerWireEvents)

        val channelDone =
            mapper.map(
                RealtimeSocketEvents.ActivityDoneUpdated,
                """{"serverId":"s1","rowKind":"channel","rowId":"c1","done":true,"position":"42"}"""
            )
        assertEquals(
            ActivityDoneUpdated(ServerId("s1"), ActivityDoneRowKind.Channel, "c1", null, true, "42"),
            channelDone,
        )
        assertFalse(channelDone!!.affectsActivityContent())
        assertTrue(channelDone.affectsActivityUnreadSummary())

        val dmDone =
            mapper.map(
                RealtimeSocketEvents.ActivityDoneUpdated,
                """{"serverId":"s1","rowKind":"dm","rowId":"dm1","done":true,"position":"92233720368547758070"}"""
            )
        assertEquals(
            ActivityDoneUpdated(ServerId("s1"), ActivityDoneRowKind.Dm, "dm1", null, true, "92233720368547758070"),
            dmDone,
        )

        val deletedThreadDone =
            mapper.map(
                RealtimeSocketEvents.ActivityDoneUpdated,
                """{"serverId":"s1","rowKind":"thread","rowId":"t1","done":true,"position":null}"""
            )
        assertEquals(
            ActivityDoneUpdated(ServerId("s1"), ActivityDoneRowKind.Thread, "t1", null, true, null),
            deletedThreadDone,
        )

        val taskUndone =
            mapper.map(
                RealtimeSocketEvents.ActivityDoneUpdated,
                """{"serverId":"s1","rowKind":"task","rowId":"task-conv-1","done":false,"position":null}"""
            )
        assertEquals(
            ActivityDoneUpdated(ServerId("s1"), ActivityDoneRowKind.Task, "task-conv-1", null, false, null),
            taskUndone,
        )

        val objectDone =
            mapper.map(
                RealtimeSocketEvents.ActivityDoneUpdated,
                """{"serverId":"s1","rowKind":"object","rowId":"art-1","objectKind":"artifact","done":true,"position":"12"}"""
            )
        assertEquals(
            ActivityDoneUpdated(ServerId("s1"), ActivityDoneRowKind.Object, "art-1", "artifact", true, "12"),
            objectDone,
        )

        val objectUndone =
            mapper.map(
                RealtimeSocketEvents.ActivityDoneUpdated,
                """{"serverId":"s1","rowKind":"object","rowId":"art-1","objectKind":"artifact","done":false,"position":null}"""
            )
        assertEquals(
            ActivityDoneUpdated(ServerId("s1"), ActivityDoneRowKind.Object, "art-1", "artifact", false, null),
            objectUndone,
        )
    }

    @Test
    fun wireParserRejectsMalformedPayloads() {
        val mapper = RealtimeEventMapper(diagnosticsLogger = {})
        val malformed =
            listOf(
                """{}""",
                """{"serverId":"","rowKind":"channel","rowId":"c1","done":true,"position":"1"}""",
                """{"serverId":123,"rowKind":"channel","rowId":"c1","done":true,"position":"1"}""",
                """{"serverId":"s1","rowKind":"mention_action","rowId":"m1","done":true,"position":"1"}""",
                """{"serverId":"s1","rowKind":"channel","rowId":"","done":true,"position":"1"}""",
                """{"serverId":"s1","rowKind":"object","rowId":"o1","done":true,"position":"1"}""",
                """{"serverId":"s1","rowKind":"object","rowId":"o1","objectKind":"","done":true,"position":"1"}""",
                """{"serverId":"s1","rowKind":"object","rowId":"o1","objectKind":42,"done":true,"position":"1"}""",
                """{"serverId":"s1","rowKind":"channel","rowId":"c1","done":"true","position":"1"}""",
                """{"serverId":"s1","rowKind":"channel","rowId":"c1","done":1,"position":"1"}""",
                """{"serverId":"s1","rowKind":"channel","rowId":"c1","done":true,"position":"0"}""",
                """{"serverId":"s1","rowKind":"channel","rowId":"c1","done":true,"position":"01"}""",
                """{"serverId":"s1","rowKind":"channel","rowId":"c1","done":true,"position":"-5"}""",
                """{"serverId":"s1","rowKind":"channel","rowId":"c1","done":true,"position":"5x"}""",
                """{"serverId":"s1","rowKind":"channel","rowId":"c1","done":true,"position":12}""",
                """{"serverId":"s1","rowKind":"channel","rowId":"c1","done":true}""",
                """{"serverId":"s1","rowKind":"channel","rowId":"c1","done":false,"position":"51"}""",
            )
        for (payload in malformed) {
            assertNull(mapper.map(RealtimeSocketEvents.ActivityDoneUpdated, payload), "should reject $payload")
        }
    }

    // --- 3: serverId scoping ---

    @Test
    fun serverIdMismatchIsIgnoredForBothDoneAndUndone() = runTest {
        val dm = dmRow("dm-1", latestActivitySeq = "7", unreadCount = 1)
        val ch = channelRow("ch-1", latestActivitySeq = "40", unreadCount = 0)
        val f = fixture(snapshot(listOf(dm, ch), serverId = "server-1"))

        f.store.dispatch(doneEvent(serverId = "server-other", rowKind = ActivityDoneRowKind.Dm, rowId = "dm-1", done = true, position = "7"))
        runCurrent()
        assertEquals(listOf("dm:dm-1", "channel:ch-1"), f.store.state.value.items.map { it.key })
        assertEquals(1, f.store.state.value.totalUnreadCount)

        // Drop dm-1 on server-1, then send undone for server-other: hold stays & no ?ids= read.
        f.store.dispatch(doneEvent(serverId = "server-1", rowKind = ActivityDoneRowKind.Dm, rowId = "dm-1", done = true, position = "7"))
        runCurrent()
        assertEquals(listOf("channel:ch-1"), f.store.state.value.items.map { it.key })

        f.inbox.serveRows(dm)
        f.store.dispatch(doneEvent(serverId = "server-other", rowKind = ActivityDoneRowKind.Dm, rowId = "dm-1", done = false, position = null))
        advanceTimeBy(ActivityRowRereadDelayMillis * 3)
        runCurrent()
        assertEquals(emptyList(), f.inbox.rowReads)
        assertEquals(1, f.inbox.listReads.size)
        assertEquals(listOf("channel:ch-1"), f.store.state.value.items.map { it.key })
    }

    // --- 4: Numeric comparison including digit-length boundary ("99" vs "100") & >64-bit ---

    @Test
    fun doneNumericPositionDropsCoveredAndKeepsNewerIncludingDigitLengthBoundary() = runTest {
        val coveredPairs =
            listOf(
                "40" to "40",
                "40" to "41",
                "99" to "100",
                "92233720368547758069" to "92233720368547758070",
            )
        for ((rowSeq, position) in coveredPairs) {
            val target = channelRow("ch-done", latestActivitySeq = rowSeq, unreadCount = 2)
            val keeper = channelRow("ch-keep", latestActivitySeq = "7", unreadCount = 1)
            val f = fixture(snapshot(listOf(target, keeper)))
            f.inbox.serveList(snapshot(listOf(target, keeper)))

            f.store.dispatch(doneEvent(rowKind = ActivityDoneRowKind.Channel, rowId = "ch-done", done = true, position = position))
            runCurrent()

            assertEquals(listOf("channel:ch-keep"), f.store.state.value.items.map { it.key }, "$rowSeq <= $position")
            assertEquals(1, f.store.state.value.totalCount)
            assertEquals(1, f.store.state.value.totalUnreadCount)
            assertEquals(1, f.facts.last().totalUnreadCount)
            assertEquals(1, f.inbox.listReads.size, "zero network reads on done")
            assertEquals(emptyList(), f.inbox.rowReads)

            // A lagging refresh still serving the Done row keeps it suppressed via doneOverlay.
            f.store.dispatch(SharedActivityIntent.PullRefresh)
            runCurrent()
            assertEquals(listOf("channel:ch-keep"), f.store.state.value.items.map { it.key }, "held against lagging list ($rowSeq/$position)")
        }

        val newerPairs =
            listOf(
                "41" to "40",
                "100" to "99",
                "92233720368547758070" to "92233720368547758069",
                null to "40",
            )
        for ((rowSeq, position) in newerPairs) {
            val target = channelRow("ch-newer", latestActivitySeq = rowSeq, unreadCount = 3)
            val f = fixture(snapshot(listOf(target)))

            f.store.dispatch(doneEvent(rowKind = ActivityDoneRowKind.Channel, rowId = "ch-newer", done = true, position = position))
            advanceTimeBy(ActivityRowRereadDelayMillis * 3)
            runCurrent()

            assertEquals(listOf("channel:ch-newer"), f.store.state.value.items.map { it.key }, "$rowSeq > $position keeps row")
            assertEquals(3, f.store.state.value.totalUnreadCount)
            assertEquals(1, f.inbox.listReads.size)
            assertEquals(emptyList(), f.inbox.rowReads)
        }
    }

    // --- 5: position == null drops unconditionally ---

    @Test
    fun doneWithNullPositionDropsRowUnconditionallyIncludingDeletedThreads() = runTest {
        val deletedThread = replyThreadRow("thread-del", latestActivitySeq = "999", unreadCount = 2)
        val seqlessThread = replyThreadRow("thread-null-seq", latestActivitySeq = null, unreadCount = 1)
        val jointDeletedThread =
            replyThreadRow(
                "thread-joint-del",
                latestActivitySeq = "500",
                unreadCount = 1,
                parentChannelType = "joint",
                parentChannelKind = ConversationKind.Joint,
            )
        val keeper = channelRow("ch-keep", latestActivitySeq = "10", unreadCount = 0)
        val f = fixture(snapshot(listOf(deletedThread, seqlessThread, jointDeletedThread, keeper)))

        f.store.dispatch(doneEvent(rowKind = ActivityDoneRowKind.Thread, rowId = "thread-del", done = true, position = null))
        f.store.dispatch(doneEvent(rowKind = ActivityDoneRowKind.Thread, rowId = "thread-null-seq", done = true, position = null))
        f.store.dispatch(doneEvent(rowKind = ActivityDoneRowKind.Thread, rowId = "thread-joint-del", done = true, position = null))
        runCurrent()

        assertEquals(listOf("channel:ch-keep"), f.store.state.value.items.map { it.key })
        assertEquals(1, f.store.state.value.totalCount)
        assertEquals(0, f.store.state.value.totalUnreadCount)
        assertEquals(1, f.inbox.listReads.size)
    }

    // --- 6: Joint-channel safety rule (Tenny in #triage:#632) ---

    @Test
    fun doneWithNonNullPositionSkipsJointChannelRowsAndJointParentThreads() = runTest {
        val jointChannel =
            channelRow(
                id = "joint-ch",
                latestActivitySeq = "10",
                unreadCount = 2,
                channelType = "joint",
                channelKind = ConversationKind.Joint,
            )
        val jointThread =
            replyThreadRow(
                id = "joint-thread",
                latestActivitySeq = "10",
                unreadCount = 1,
                parentChannelType = "joint",
                parentChannelKind = ConversationKind.Joint,
            )
        val jointTask =
            replyThreadRow(
                id = "joint-task",
                latestActivitySeq = "10",
                unreadCount = 1,
                parentChannelType = "joint",
                parentChannelKind = ConversationKind.Joint,
                task = true,
            )
        val f = fixture(snapshot(listOf(jointChannel, jointThread, jointTask)))

        f.store.dispatch(doneEvent(rowKind = ActivityDoneRowKind.Channel, rowId = "joint-ch", done = true, position = "99"))
        f.store.dispatch(doneEvent(rowKind = ActivityDoneRowKind.Thread, rowId = "joint-thread", done = true, position = "99"))
        f.store.dispatch(doneEvent(rowKind = ActivityDoneRowKind.Task, rowId = "joint-task", done = true, position = "99"))
        runCurrent()

        assertEquals(
            listOf("channel:joint-ch", "thread:joint-thread", "thread:joint-task"),
            f.store.state.value.items.map { it.key },
            "joint channel and joint-parent thread/task rows must be skipped when position != null",
        )
        assertEquals(3, f.store.state.value.totalCount)
        assertEquals(4, f.store.state.value.totalUnreadCount)
        assertEquals(1, f.inbox.listReads.size)
    }

    // --- 7: Object row doneFrontierSeq compare, counts, and lagging-list hold ---

    @Test
    fun doneOnObjectRowComparesDoneFrontierSeqUpdatesObjectCountsAndHoldsLaggingList() = runTest {
        val artifact = objectRow(objectId = "artifact-632", doneFrontierSeq = "12", unreadCount = 1, hasMention = true)
        val ch = channelRow("ch-1", latestActivitySeq = "40", unreadCount = 2)
        val initial =
            snapshot(listOf(artifact, ch), totalCount = 1, totalUnreadCount = 2)
                .copy(objectCounts = ActivityObjectCounts(objectUnreadCount = 1, objectMentionUnreadCount = 1, objectCount = 1))
        val f = fixture(initial)

        // Newer event than Done position (12 > 11): keep row.
        f.store.dispatch(
            doneEvent(rowKind = ActivityDoneRowKind.Object, rowId = "artifact-632", objectKind = "artifact", done = true, position = "11")
        )
        runCurrent()
        assertEquals(listOf("object:artifact:artifact-632", "channel:ch-1"), f.store.state.value.items.map { it.key })

        // Covered (12 <= 12): drop row, decrement objectCounts only, keep conversation counts intact.
        f.store.dispatch(
            doneEvent(rowKind = ActivityDoneRowKind.Object, rowId = "artifact-632", objectKind = "artifact", done = true, position = "12")
        )
        runCurrent()
        assertEquals(listOf("channel:ch-1"), f.store.state.value.items.map { it.key })
        assertEquals(1, f.store.state.value.snapshot!!.totalCount)
        assertEquals(2, f.store.state.value.snapshot!!.totalUnreadCount)
        assertEquals(ActivityObjectCounts(0, 0, 0), f.store.state.value.snapshot!!.objectCounts)
        assertEquals(2, f.facts.last().totalUnreadCount)

        // Lagging refresh still returning the object row at doneFrontierSeq=12 stays suppressed with accurate objectCounts.
        f.inbox.serveList(initial)
        f.store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()
        assertEquals(listOf("channel:ch-1"), f.store.state.value.items.map { it.key })
        assertEquals(ActivityObjectCounts(0, 0, 0), f.store.state.value.snapshot!!.objectCounts)
        assertEquals(2, f.store.state.value.snapshot!!.totalUnreadCount)

        // Newer event (doneFrontierSeq=13) retires the hold and shows the row again.
        val newerArtifact = objectRow(objectId = "artifact-632", doneFrontierSeq = "13", unreadCount = 1, hasMention = false)
        f.inbox.serveList(
            snapshot(listOf(newerArtifact, ch), totalCount = 1, totalUnreadCount = 2)
                .copy(objectCounts = ActivityObjectCounts(objectUnreadCount = 1, objectMentionUnreadCount = 0, objectCount = 1))
        )
        f.store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()
        assertEquals(listOf("object:artifact:artifact-632", "channel:ch-1"), f.store.state.value.items.map { it.key })
        assertEquals(ActivityObjectCounts(1, 0, 1), f.store.state.value.snapshot!!.objectCounts)
    }

    // --- 8: Undone single-row ?ids= fetch & insert with inbox_row_position_v1 OFF and ON ---

    @Test
    fun undoneForConversationRowsFetchesSingleRowByIdsAndInsertsWithFlagOffAndFlagOn() = runTest {
        for (rowPositionFlag in listOf(false, true)) {
            val dm = dmRow("dm-618", latestActivitySeq = "7", unreadCount = 1, lastMessageAt = "2026-11-11T10:00:00.000Z")
            val ch = channelRow("ch-older", latestActivitySeq = "40", unreadCount = 0, lastMessageAt = "2026-11-11T09:00:00.000Z")
            val thread = replyThreadRow("thread-632", latestActivitySeq = "51", unreadCount = 2, lastActivityAt = "2026-11-11T11:00:00.000Z")
            val task = replyThreadRow("task-632", latestActivitySeq = "60", unreadCount = 1, lastActivityAt = "2026-11-11T08:00:00.000Z", task = true)

            val f = fixture(snapshot(listOf(thread, dm, ch, task)), rowPosition = rowPositionFlag, inPlace = rowPositionFlag)

            // Mark dm, thread, task, and channel Done live.
            f.store.dispatch(doneEvent(rowKind = ActivityDoneRowKind.Dm, rowId = "dm-618", done = true, position = "7"))
            f.store.dispatch(doneEvent(rowKind = ActivityDoneRowKind.Thread, rowId = "thread-632", done = true, position = "51"))
            f.store.dispatch(doneEvent(rowKind = ActivityDoneRowKind.Task, rowId = "task-632", done = true, position = "60"))
            runCurrent()
            assertEquals(listOf("channel:ch-older"), f.store.state.value.items.map { it.key }, "flag=$rowPositionFlag")
            assertEquals(1, f.store.state.value.totalCount)
            assertEquals(0, f.store.state.value.totalUnreadCount)

            // Undo dm-618: releases hold, fetches ?ids=dm-618 (reason=position_reread, trigger=row_undone), inserts without full list reload.
            f.inbox.serveRows(dm, thread, task, ch)
            f.store.dispatch(doneEvent(rowKind = ActivityDoneRowKind.Dm, rowId = "dm-618", done = false, position = null))
            advanceTimeBy(ActivityRowRereadDelayMillis + 10L)
            runCurrent()

            assertEquals(1, f.inbox.listReads.size, "no full Activity reload on dm undone (flag=$rowPositionFlag)")
            assertEquals(
                listOf(RecordedRead(InboxFetchReason.PositionReread, InboxRefreshTriggerCount(InboxRefreshTrigger.RowUndone), listOf("dm-618"))),
                f.inbox.rowReads,
                "flag=$rowPositionFlag",
            )
            assertEquals(listOf("dm:dm-618", "channel:ch-older"), f.store.state.value.items.map { it.key }, "inserted by time")
            assertEquals(2, f.store.state.value.totalCount)
            assertEquals(1, f.store.state.value.totalUnreadCount)

            // Undo thread-632 and task-632: batched into single ?ids= read and inserted at their timestamps.
            f.store.dispatch(doneEvent(rowKind = ActivityDoneRowKind.Thread, rowId = "thread-632", done = false, position = null))
            f.store.dispatch(doneEvent(rowKind = ActivityDoneRowKind.Task, rowId = "task-632", done = false, position = null))
            advanceTimeBy(ActivityRowRereadDelayMillis + 10L)
            runCurrent()

            assertEquals(1, f.inbox.listReads.size, "still zero full Activity reloads (flag=$rowPositionFlag)")
            assertEquals(
                listOf("thread:thread-632", "dm:dm-618", "channel:ch-older", "thread:task-632"),
                f.store.state.value.items.map { it.key },
                "flag=$rowPositionFlag",
            )
            assertEquals(4, f.store.state.value.totalCount)
            assertEquals(4, f.store.state.value.totalUnreadCount)
            assertEquals(4, f.facts.last().totalUnreadCount)
        }
    }

    @Test
    fun undoneRetriesSingleRowFetchWhenViewLagsBehindWrite() = runTest {
        val dm = dmRow("dm-lag", latestActivitySeq = "7", unreadCount = 1, lastMessageAt = "2026-11-11T10:00:00.000Z")
        val ch = channelRow("ch-1", latestActivitySeq = "40", unreadCount = 0, lastMessageAt = "2026-11-11T09:00:00.000Z")
        val f = fixture(snapshot(listOf(ch)), rowPosition = false)
        // First ?ids= answer is empty (view lag), second returns the undone row.
        f.inbox.enqueueRowAnswers(emptyList(), listOf(dm))

        f.store.dispatch(doneEvent(rowKind = ActivityDoneRowKind.Dm, rowId = "dm-lag", done = false, position = null))
        advanceTimeBy(ActivityRowRereadDelayMillis + 10L)
        runCurrent()
        assertEquals(1, f.inbox.rowReads.size)
        assertEquals(listOf("channel:ch-1"), f.store.state.value.items.map { it.key })

        advanceTimeBy(ActivityRowRereadDelayMillis + 10L)
        runCurrent()
        assertEquals(2, f.inbox.rowReads.size)
        assertEquals(1, f.inbox.listReads.size, "still zero full list reloads on retry")
        assertEquals(listOf("dm:dm-lag", "channel:ch-1"), f.store.state.value.items.map { it.key })
        assertEquals(2, f.store.state.value.totalCount)
        assertEquals(1, f.store.state.value.totalUnreadCount)
    }

    // --- 9: Undone on filtered list reads nothing ---

    @Test
    fun undoneWhileFilteredReleasesHoldAndReadsNothing() = runTest {
        val dm = dmRow("dm-1", latestActivitySeq = "7", unreadCount = 1)
        val ch = channelRow("ch-1", latestActivitySeq = "40", unreadCount = 1)
        val f = fixture(snapshot(listOf(dm, ch)))

        f.inbox.serveList(snapshot(listOf(dm, ch)))
        f.store.dispatch(SharedActivityIntent.SetFilter(InboxFilter.Unread))
        runCurrent()
        val listReadsBefore = f.inbox.listReads.size

        f.store.dispatch(doneEvent(rowKind = ActivityDoneRowKind.Dm, rowId = "dm-1", done = true, position = "7"))
        runCurrent()
        assertEquals(listOf("channel:ch-1"), f.store.state.value.items.map { it.key })

        f.inbox.serveRows(dm)
        f.store.dispatch(doneEvent(rowKind = ActivityDoneRowKind.Dm, rowId = "dm-1", done = false, position = null))
        advanceTimeBy(ActivityRowRereadDelayMillis * 3)
        runCurrent()
        assertEquals(emptyList(), f.inbox.rowReads, "filtered list does not fire ?ids= insert")
        assertEquals(listReadsBefore, f.inbox.listReads.size, "filtered list does not fire full reload")

        // Next list refresh brings dm-1 back because the hold was released.
        f.inbox.serveList(snapshot(listOf(dm, ch)))
        f.store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()
        assertEquals(listOf("dm:dm-1", "channel:ch-1"), f.store.state.value.items.map { it.key })
    }

    // --- 10: Object undone reloads Activity without double-storming with activity:changed ---

    @Test
    fun undoneForObjectRowReloadsActivityAndDoesNotDoubleStormWithActivityChanged() = runTest {
        val artifact = objectRow(objectId = "artifact-632", doneFrontierSeq = "12", unreadCount = 1)
        val ch = channelRow("ch-1", latestActivitySeq = "40", unreadCount = 0)
        val withObject =
            snapshot(listOf(artifact, ch), totalCount = 1, totalUnreadCount = 0)
                .copy(objectCounts = ActivityObjectCounts(objectUnreadCount = 1, objectMentionUnreadCount = 0, objectCount = 1))

        // Case A: activity_done:updated (object, undone) alone triggers one reload and restores row.
        val alone = fixture(withObject)
        alone.store.dispatch(
            doneEvent(rowKind = ActivityDoneRowKind.Object, rowId = "artifact-632", objectKind = "artifact", done = true, position = "12")
        )
        runCurrent()
        assertEquals(listOf("channel:ch-1"), alone.store.state.value.items.map { it.key })

        alone.inbox.serveList(withObject)
        alone.store.dispatch(
            doneEvent(rowKind = ActivityDoneRowKind.Object, rowId = "artifact-632", objectKind = "artifact", done = false, position = null)
        )
        advanceTimeBy(ActivityRealtimeBackgroundRefreshIntervalMillis * 2)
        runCurrent()
        assertEquals(2, alone.inbox.listReads.size, "object undone triggers one Activity reload")
        assertEquals(emptyList(), alone.inbox.rowReads)
        assertEquals(listOf("object:artifact:artifact-632", "channel:ch-1"), alone.store.state.value.items.map { it.key })

        // Case B: paired activity:changed + activity_done:updated (server routes/objects.ts order) coalesces to ONE reload.
        val paired = fixture(withObject)
        paired.store.dispatch(
            doneEvent(rowKind = ActivityDoneRowKind.Object, rowId = "artifact-632", objectKind = "artifact", done = true, position = "12")
        )
        runCurrent()
        paired.inbox.serveList(withObject)
        paired.store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(ActivityChanged(ServerId("server-1"), "object")))
        paired.store.dispatch(
            doneEvent(rowKind = ActivityDoneRowKind.Object, rowId = "artifact-632", objectKind = "artifact", done = false, position = null)
        )
        advanceTimeBy(ActivityRealtimeBackgroundRefreshIntervalMillis * 3)
        runCurrent()
        assertEquals(2, paired.inbox.listReads.size, "paired activity:changed + activity_done:updated must not double-storm reloads")
        assertEquals(listOf("object:artifact:artifact-632", "channel:ch-1"), paired.store.state.value.items.map { it.key })
    }

    // --- 11: Originating-device no-op for Done and undone ---

    @Test
    fun originatingDeviceEchoesAreCleanNoOps() = runTest {
        val dm = dmRow("dm-1", latestActivitySeq = "7", unreadCount = 1)
        val ch = channelRow("ch-1", latestActivitySeq = "40", unreadCount = 0)
        val f = fixture(snapshot(listOf(dm, ch)))

        // Local MarkDone removes dm-1; echoed done=true is a no-op (no duplicate drops, no count underflow).
        f.inbox.serveList(snapshot(listOf(ch)))
        f.store.dispatch(SharedActivityIntent.MarkDone(dm))
        runCurrent()
        val listReadsAfterLocalDone = f.inbox.listReads.size
        assertEquals(listOf("channel:ch-1"), f.store.state.value.items.map { it.key })
        assertEquals(1, f.store.state.value.totalCount)

        f.store.dispatch(doneEvent(rowKind = ActivityDoneRowKind.Dm, rowId = "dm-1", done = true, position = "7"))
        f.store.dispatch(doneEvent(rowKind = ActivityDoneRowKind.Dm, rowId = "dm-1", done = true, position = "7"))
        advanceTimeBy(ActivityRowRereadDelayMillis * 3)
        runCurrent()
        assertEquals(listOf("channel:ch-1"), f.store.state.value.items.map { it.key })
        assertEquals(1, f.store.state.value.totalCount)
        assertEquals(listReadsAfterLocalDone, f.inbox.listReads.size)
        assertEquals(emptyList(), f.inbox.rowReads)

        // Row already listed (e.g. restored on originating device with newer state): undone echo reads nothing & does not clobber.
        val newerCh = ch.copy(latestActivitySeq = "45", unreadCount = 3)
        val restored = fixture(snapshot(listOf(newerCh)))
        restored.inbox.serveRows(ch) // older copy on server
        restored.store.dispatch(doneEvent(rowKind = ActivityDoneRowKind.Channel, rowId = "ch-1", done = false, position = null))
        advanceTimeBy(ActivityRowRereadDelayMillis * 3)
        runCurrent()
        assertEquals(1, restored.inbox.listReads.size)
        assertEquals(emptyList(), restored.inbox.rowReads, "already-listed row must not fire redundant ?ids= fetch")
        assertEquals("45", restored.store.state.value.items.single().latestActivitySeq, "must not clobber newer local state")
        assertEquals(3, restored.store.state.value.items.single().unreadCount)
    }

    // --- 12: Hidden Activity surface receives Done via ApplyRealtimeSummaryEvent ---

    @Test
    fun hiddenActivitySurfaceAppliesDoneImmediatelyAndUpdatesBadge() = runTest {
        val dm = dmRow("dm-1", latestActivitySeq = "7", unreadCount = 2)
        val ch = channelRow("ch-1", latestActivitySeq = "40", unreadCount = 1)
        val f = fixture(snapshot(listOf(dm, ch)))
        f.store.dispatch(SharedActivityIntent.SurfaceHidden)
        runCurrent()

        f.store.dispatch(
            SharedActivityIntent.ApplyRealtimeSummaryEvent(
                event = ActivityDoneUpdated(ServerId("server-1"), ActivityDoneRowKind.Dm, "dm-1", null, done = true, position = "7"),
                activeConversationId = null,
                primarySurfaceVisible = false,
            )
        )
        runCurrent()

        assertEquals(listOf("channel:ch-1"), f.store.state.value.items.map { it.key })
        assertEquals(1, f.store.state.value.totalCount)
        assertEquals(1, f.store.state.value.totalUnreadCount)
        assertEquals(1, f.facts.last().totalUnreadCount)
        assertEquals(1, f.inbox.listReads.size)
    }

    // --- 13: Off-page (hasMore=true) Done, read receipt, and row-reread insert count accuracy ---

    @Test
    fun offPageDoneWhileHasMoreAndUnreadSchedulesAuthoritativeReconcile() = runTest {
        val ch1 = channelRow("ch-1", latestActivitySeq = "40", unreadCount = 1)
        val f =
            fixture(
                initial = snapshot(listOf(ch1), hasMore = true, totalCount = 40, totalUnreadCount = 4),
                rowPosition = true,
                inPlace = true,
            )
        f.inbox.serveList(snapshot(listOf(ch1), hasMore = true, totalCount = 39, totalUnreadCount = 1))

        f.store.dispatch(doneEvent(rowKind = ActivityDoneRowKind.Channel, rowId = "ch-page2", done = true, position = "20"))
        advanceTimeBy(ActivityRealtimeBackgroundRefreshIntervalMillis + 100L)
        runCurrent()

        assertEquals(2, f.inbox.listReads.size, "off-page Done while hasMore=true and unread>0 must reconcile")
        assertEquals(1, f.store.state.value.totalUnreadCount)
        assertEquals(1, f.facts.last().totalUnreadCount)
    }

    @Test
    fun offPageReadReceiptResultWhileHasMoreAndUnreadSchedulesAuthoritativeReconcile() = runTest {
        val ch1 = channelRow("ch-1", latestActivitySeq = "40", unreadCount = 1)
        val owner = ReadReceiptOwner("production", "user-1", "server-1")
        val f =
            fixture(
                initial = snapshot(listOf(ch1), hasMore = true, totalCount = 40, totalUnreadCount = 4),
                rowPosition = true,
                inPlace = true,
            )
        f.inbox.serveList(snapshot(listOf(ch1), hasMore = true, totalCount = 40, totalUnreadCount = 1))

        val offPageChannel = ChannelId("ch-page2")
        f.store.dispatch(
            SharedActivityIntent.ApplyReadReceiptPrediction(
                ReadReceiptPrediction(owner, offPageChannel, revision = 1L, target = ReadReceiptTarget.All)
            )
        )
        runCurrent()
        // Prediction alone waits for server ack before reconciling.
        assertEquals(1, f.inbox.listReads.size)

        f.store.dispatch(
            SharedActivityIntent.ApplyReadReceiptResult(
                request = ReadReceiptRequest.All(offPageChannel, owner, revision = 1L),
                succeeded = true,
                maxReadSeq = 50L,
            )
        )
        advanceTimeBy(ActivityRealtimeBackgroundRefreshIntervalMillis + 100L)
        runCurrent()

        assertEquals(2, f.inbox.listReads.size, "acknowledged off-page read must reconcile totalUnreadCount")
        assertEquals(1, f.store.state.value.totalUnreadCount)
        assertEquals(1, f.facts.last().totalUnreadCount)
    }

    @Test
    fun rowRereadInsertIntoPageOneWhileHasMoreDoesNotDoubleCountAndReconciles() = runTest {
        val ch1 = channelRow("ch-1", latestActivitySeq = "40", unreadCount = 0, lastMessageAt = "2026-11-11T09:00:00.000Z")
        val dmPage2 = dmRow("dm-page2", latestActivitySeq = "55", unreadCount = 6, lastMessageAt = "2026-11-11T10:00:00.000Z")
        val f =
            fixture(
                initial = snapshot(listOf(ch1), hasMore = true, totalCount = 40, totalUnreadCount = 5),
                rowPosition = true,
                inPlace = true,
                settleThrottle = false,
            )
        f.inbox.serveRows(dmPage2)
        f.inbox.serveList(snapshot(listOf(dmPage2, ch1), hasMore = true, totalCount = 40, totalUnreadCount = 6))

        f.store.dispatch(doneEvent(rowKind = ActivityDoneRowKind.Dm, rowId = "dm-page2", done = false, position = null))
        advanceTimeBy(ActivityRowRereadDelayMillis + 10L)
        runCurrent()

        assertEquals(1, f.inbox.listReads.size, "still inside throttle window before reconcile")
        assertEquals(listOf("dm:dm-page2", "channel:ch-1"), f.store.state.value.items.map { it.key })
        assertEquals(6, f.store.state.value.totalUnreadCount, "must not double-count 5 + 6 = 11 while hasMore=true")
        assertEquals(40, f.store.state.value.totalCount)

        advanceTimeBy(ActivityRealtimeBackgroundRefreshIntervalMillis + 100L)
        runCurrent()
        assertEquals(2, f.inbox.listReads.size, "must reconcile authoritative server count after off-page insert")
        assertEquals(6, f.store.state.value.totalUnreadCount)
    }
}
