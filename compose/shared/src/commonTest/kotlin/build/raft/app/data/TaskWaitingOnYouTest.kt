package build.raft.app.data

import build.raft.app.formatTaskWaitingDetail
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.model.TaskUpdated
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TaskWaitingOnYouTest {

    private fun taskRow(
        taskId: String = "task-1",
        convId: String = "conv-1",
        seq: String = "10",
        status: TaskStatus = TaskStatus.InProgress,
        waiting: TaskWaitingOnYou? = null,
    ): InboxItem =
        InboxItem(
            key = "thread:$convId",
            kind = InboxItemKind.Thread,
            channelId = ChannelId(convId),
            channelName = "general",
            unreadCount = 0,
            threadChannelId = ChannelId(convId),
            parentChannelId = ChannelId("ch-1"),
            parentChannelName = "general",
            parentChannelKind = ConversationKind.Channel,
            latestActivityMessageId = MessageId("msg-$seq"),
            latestActivitySeq = seq,
            isFollowing = true,
            taskNumber = 7,
            taskStatus = status,
            taskConversation = InboxTaskConversation(taskId = TaskId(taskId), taskTitle = "Ship parity fix"),
            taskWaiting = waiting,
        )

    private fun taskSummary(
        id: String = "task-1",
        status: TaskStatus = TaskStatus.InProgress,
        assigneeKind: MemberKind? = MemberKind.Human,
        assigneeId: String? = "user-1",
        claimedAt: String? = "2026-11-26T10:00:00Z",
        revision: Long = 1L,
    ): TaskSummary =
        TaskSummary(
            id = TaskId(id),
            title = "Ship parity fix",
            status = status,
            assignee =
                if (assigneeKind != null && assigneeId != null) {
                    MemberProfile(MemberId(assigneeId), "User $assigneeId", assigneeId, assigneeKind)
                } else {
                    null
                },
            updatedAtLabel = claimedAt ?: "2026-11-26T10:00:00Z",
            revision = revision,
            claimedAt = claimedAt,
        )

    private class RecordingRowsInboxRepository(
        private val initialSnapshots: List<InboxSnapshot>,
        private val rowResponses: List<List<InboxItem>> = emptyList(),
    ) : InboxRepository {
        val listTriggers = mutableListOf<InboxRefreshTrigger?>()
        val rowRequests = mutableListOf<Pair<List<String>, InboxRefreshTrigger?>>()
        var initialLoadCount = 0
        private var rowCallCount = 0

        override suspend fun loadInitial(
            serverId: ServerId,
            filter: InboxFilter,
            limit: Int,
        ): DataResult<InboxSnapshot> {
            listTriggers += kotlin.coroutines.coroutineContext[InboxFetchReasonContext]?.trigger?.trigger
            val snap = initialSnapshots.getOrElse(initialLoadCount++) { initialSnapshots.last() }
            return DataResult.Content(snap)
        }

        override suspend fun loadMore(
            serverId: ServerId,
            filter: InboxFilter,
            offset: Int,
            limit: Int,
        ): DataResult<InboxSnapshot> = DataResult.Empty(EmptyReason.NoInboxItems)

        override suspend fun loadRows(
            serverId: ServerId,
            rowIds: List<String>,
        ): DataResult<InboxSnapshot> {
            val trigger = kotlin.coroutines.coroutineContext[InboxFetchReasonContext]?.trigger?.trigger
            rowRequests += rowIds to trigger
            val items = rowResponses.getOrElse(rowCallCount++) { rowResponses.lastOrNull().orEmpty() }
            return DataResult.Content(snapshot(items))
        }

        override suspend fun markDone(channelId: ChannelId, frontier: InboxDoneFrontier): DataResult<InboxDoneResult> =
            DataResult.Content(InboxDoneResult(channelId))
    }

    @Test
    fun parseInboxTaskRowWaitingValidAndFailClosedCases() {
        val payload =
            """
            {
              "items": [
                {
                  "kind": "task",
                  "threadChannelId": "conv-assign",
                  "parentChannelId": "ch-1",
                  "parentChannelName": "general",
                  "taskId": "task-assign",
                  "taskNumber": 1,
                  "taskTitle": "Assigned task",
                  "taskStatus": "in_progress",
                  "waiting": {
                    "since": "2026-11-26T10:00:00Z",
                    "via": "assign",
                    "assignedBy": { "type": "agent", "id": "ag-1", "name": "Lagoon" },
                    "reason": "Please review API contract",
                    "reasonTruncated": true
                  }
                },
                {
                  "kind": "task",
                  "threadChannelId": "conv-claim",
                  "parentChannelId": "ch-1",
                  "parentChannelName": "general",
                  "taskId": "task-claim",
                  "taskNumber": 2,
                  "taskTitle": "Claimed task",
                  "taskStatus": "todo",
                  "waiting": {
                    "since": "2026-11-26T10:01:00Z",
                    "via": "claim",
                    "assignedBy": { "type": "user", "id": "u-2", "name": "Ignored" },
                    "reason": "Ignored on claim",
                    "reasonTruncated": true
                  }
                },
                {
                  "kind": "task",
                  "threadChannelId": "conv-bad",
                  "parentChannelId": "ch-1",
                  "parentChannelName": "general",
                  "taskId": "task-bad",
                  "taskNumber": 3,
                  "taskTitle": "Malformed waiting",
                  "taskStatus": "todo",
                  "waiting": {
                    "since": "not-a-timestamp",
                    "via": "assign"
                  }
                },
                {
                  "kind": "thread",
                  "threadChannelId": "th-1",
                  "parentChannelId": "ch-1",
                  "parentChannelName": "general",
                  "parentMessageId": "pm-1",
                  "waiting": {
                    "since": "2026-11-26T10:00:00Z",
                    "via": "system"
                  }
                }
              ],
              "hasMore": false
            }
            """.trimIndent()
        val parsed = assertNotNull(parseSharedInboxSnapshot(payload))
        assertEquals(4, parsed.items.size, "malformed waiting fails closed without dropping the row")

        val assignRow = parsed.items[0]
        assertEquals(
            TaskWaitingOnYou(
                since = "2026-11-26T10:00:00Z",
                via = TaskWaitingVia.Assign,
                assignedBy = TaskWaitingAssignedBy(MemberKind.Agent, "ag-1", "Lagoon"),
                reason = "Please review API contract",
                reasonTruncated = true,
            ),
            assignRow.taskWaiting,
        )

        val claimRow = parsed.items[1]
        assertEquals(
            TaskWaitingOnYou(since = "2026-11-26T10:01:00Z", via = TaskWaitingVia.Claim),
            claimRow.taskWaiting,
            "non-assign ignores assignedBy/reason/reasonTruncated",
        )

        assertNull(parsed.items[2].taskWaiting, "invalid since fails closed to null")
        assertNull(parsed.items[3].taskWaiting, "non-task rows never parse waiting")
    }

    @Test
    fun liveTaskWaitsOnAndShownTaskWaitingRules() {
        val waiting = TaskWaitingOnYou(since = "2026-11-26T10:00:00Z", via = TaskWaitingVia.Claim)
        assertTrue(liveTaskWaitsOn(taskSummary(status = TaskStatus.Todo), "user-1"))
        assertTrue(liveTaskWaitsOn(taskSummary(status = TaskStatus.InProgress), "user-1"))
        assertTrue(liveTaskWaitsOn(taskSummary(status = TaskStatus.InReview), "user-1"))
        assertFalse(liveTaskWaitsOn(taskSummary(status = TaskStatus.Done), "user-1"))
        assertFalse(liveTaskWaitsOn(taskSummary(status = TaskStatus.Closed), "user-1"))
        assertFalse(liveTaskWaitsOn(taskSummary(status = TaskStatus.Backlog), "user-1"))
        assertFalse(liveTaskWaitsOn(taskSummary(assigneeKind = MemberKind.Agent), "user-1"))
        assertFalse(liveTaskWaitsOn(taskSummary(assigneeId = "user-2"), "user-1"))
        assertFalse(liveTaskWaitsOn(taskSummary(assigneeId = null), "user-1"))
        assertFalse(liveTaskWaitsOn(taskSummary(), null))

        assertEquals(waiting, shownTaskWaiting(waiting, liveTask = null, currentUserId = "user-1"))
        assertEquals(waiting, shownTaskWaiting(waiting, liveTask = taskSummary(status = TaskStatus.InReview), currentUserId = "user-1"))
        assertNull(shownTaskWaiting(waiting, liveTask = taskSummary(status = TaskStatus.Done), currentUserId = "user-1"))
        assertNull(shownTaskWaiting(waiting, liveTask = taskSummary(assigneeId = "user-2"), currentUserId = "user-1"))
        assertNull(shownTaskWaiting(null, liveTask = taskSummary(), currentUserId = "user-1"))
    }

    @Test
    fun withServedTaskWaitingUpdatesKeptTaskRowAndIgnoresNonTaskRow() {
        val w1 = TaskWaitingOnYou(since = "2026-11-26T10:00:00Z", via = TaskWaitingVia.Claim)
        val w2 = TaskWaitingOnYou(since = "2026-11-26T10:05:00Z", via = TaskWaitingVia.System)
        val kept = taskRow(waiting = w1)
        assertEquals(w2, kept.withServedTaskWaiting(taskRow(waiting = w2)).taskWaiting)
        assertNull(kept.withServedTaskWaiting(taskRow(waiting = null)).taskWaiting)

        val channel = activityItem("ch-1")
        assertEquals(channel, channel.withServedTaskWaiting(taskRow(waiting = w1)))
    }

    @Test
    fun formatTaskWaitingDetailAppendsEllipsisOnlyWhenTruncatedOnAssign() {
        val assignTruncated =
            TaskWaitingOnYou(
                since = "2026-11-26T10:00:00Z",
                via = TaskWaitingVia.Assign,
                assignedBy = TaskWaitingAssignedBy(MemberKind.Human, "u-2", "Alice"),
                reason = "Cut reason",
                reasonTruncated = true,
            )
        val detail =
            formatTaskWaitingDetail(
                statusLabel = "In progress",
                whoLabel = "Alice assigned it to you · 5m ago",
                waiting = assignTruncated,
                formatWithReason = { s, w, r -> "$s · $w · $r" },
                formatWithoutReason = { s, w -> "$s · $w" },
            )
        assertEquals("In progress · Alice assigned it to you · 5m ago · Cut reason…", detail)

        val claimNoReason =
            TaskWaitingOnYou(
                since = "2026-11-26T10:00:00Z",
                via = TaskWaitingVia.Claim,
                reason = "Stray",
                reasonTruncated = true,
            )
        val claimDetail =
            formatTaskWaitingDetail(
                statusLabel = "To do",
                whoLabel = "You took it · now",
                waiting = claimNoReason,
                formatWithReason = { s, w, r -> "$s · $w · $r" },
                formatWithoutReason = { s, w -> "$s · $w" },
            )
        assertEquals("To do · You took it · now", claimDetail)
    }

    @Test
    fun reconcileTaskWaitingRereadsListedRowWithRowPositionAndPreservesWaitingOnUnchangedSeq() = runTest {
        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        val waiting =
            TaskWaitingOnYou(
                since = "2026-11-26T10:05:00Z",
                via = TaskWaitingVia.Assign,
                assignedBy = TaskWaitingAssignedBy(MemberKind.Human, "u-2", "Alice"),
                reason = "Ready for review",
            )
        val initialRow = taskRow(seq = "10", waiting = null)
        val servedWaitingRow = taskRow(seq = "10", waiting = waiting)
        val servedUpdatedWaitingRow =
            taskRow(
                seq = "10",
                waiting = waiting.copy(since = "2026-11-26T10:10:00Z", reason = "Re-assigned with new note"),
            )
        val repo =
            RecordingRowsInboxRepository(
                initialSnapshots = listOf(snapshot(listOf(initialRow))),
                rowResponses = listOf(listOf(servedWaitingRow), listOf(servedUpdatedWaitingRow)),
            )
        var flagOn = false
        val store =
            activityStore(
                scope = testScope,
                inboxRepository = repo,
                inboxInPlaceEnabled = { true },
                inboxRowPositionEnabled = { true },
                taskWaitingOnYouEnabled = { flagOn },
                rowRereadDelayMillis = 100L,
            )

        store.dispatch(SharedActivityIntent.LoadInitial)
        testScope.runCurrent()
        assertEquals(1, repo.initialLoadCount)
        assertNull(store.state.value.items.single().taskWaiting)

        // Flag OFF -> zero requests.
        store.dispatch(
            SharedActivityIntent.ReconcileTaskWaiting(
                serverId = "server-1",
                task = taskSummary(claimedAt = "2026-11-26T10:05:00Z"),
                previous = taskSummary(assigneeId = "user-2", claimedAt = "2026-11-26T09:00:00Z"),
            )
        )
        testScope.advanceTimeBy(500L)
        testScope.runCurrent()
        assertTrue(repo.rowRequests.isEmpty(), "flag OFF must not re-read")

        // Flag ON + unlisted task -> zero requests.
        flagOn = true
        store.dispatch(
            SharedActivityIntent.ReconcileTaskWaiting(
                serverId = "server-1",
                task = taskSummary(id = "task-unlisted"),
                previous = null,
            )
        )
        testScope.advanceTimeBy(500L)
        testScope.runCurrent()
        assertTrue(repo.rowRequests.isEmpty(), "unlisted task must not re-read")

        // Flag ON + non-waiting task (Done) -> zero requests.
        store.dispatch(
            SharedActivityIntent.ReconcileTaskWaiting(
                serverId = "server-1",
                task = taskSummary(status = TaskStatus.Done),
                previous = taskSummary(status = TaskStatus.InProgress),
            )
        )
        testScope.advanceTimeBy(500L)
        testScope.runCurrent()
        assertTrue(repo.rowRequests.isEmpty(), "non-waiting task must not re-read")

        // Flag ON + listed task newly waiting on user-1 -> single-row reread (?ids=) with TaskWaiting trigger,
        // and mergeServedRowLocked copies `waiting` even though latestActivitySeq stayed "10".
        store.dispatch(
            SharedActivityIntent.ReconcileTaskWaiting(
                serverId = "server-1",
                task = taskSummary(claimedAt = "2026-11-26T10:05:00Z", revision = 2L),
                previous = taskSummary(assigneeId = "user-2", claimedAt = "2026-11-26T09:00:00Z", revision = 1L),
            )
        )
        testScope.advanceTimeBy(200L)
        testScope.runCurrent()
        assertEquals(1, repo.rowRequests.size)
        assertEquals(listOf("conv-1") to InboxRefreshTrigger.TaskWaiting, repo.rowRequests.single())
        assertEquals(waiting, store.state.value.items.single().taskWaiting)

        // Same hold (previous already waited on user-1 with same claimedAt) -> zero extra requests.
        store.dispatch(
            SharedActivityIntent.ReconcileTaskWaiting(
                serverId = "server-1",
                task = taskSummary(status = TaskStatus.InReview, claimedAt = "2026-11-26T10:05:00Z", revision = 3L),
                previous = taskSummary(status = TaskStatus.InProgress, claimedAt = "2026-11-26T10:05:00Z", revision = 2L),
            )
        )
        testScope.advanceTimeBy(500L)
        testScope.runCurrent()
        assertEquals(1, repo.rowRequests.size, "unchanged hold must not re-read again")

        // Re-assigned with a new claimedAt -> re-reads again and updates waiting metadata.
        store.dispatch(
            SharedActivityIntent.ReconcileTaskWaiting(
                serverId = "server-1",
                task = taskSummary(claimedAt = "2026-11-26T10:10:00Z", revision = 4L),
                previous = taskSummary(claimedAt = "2026-11-26T10:05:00Z", revision = 3L),
            )
        )
        testScope.advanceTimeBy(200L)
        testScope.runCurrent()
        assertEquals(2, repo.rowRequests.size)
        assertEquals("Re-assigned with new note", store.state.value.items.single().taskWaiting?.reason)
    }

    @Test
    fun reconcileTaskWaitingFallsBackToListReconcileWhenRowPositionIsOff() = runTest {
        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        val waiting = TaskWaitingOnYou(since = "2026-11-26T10:05:00Z", via = TaskWaitingVia.System)
        val repo =
            RecordingRowsInboxRepository(
                initialSnapshots = listOf(snapshot(listOf(taskRow(waiting = null))), snapshot(listOf(taskRow(waiting = waiting)))),
            )
        val store =
            activityStore(
                scope = testScope,
                inboxRepository = repo,
                inboxInPlaceEnabled = { false },
                inboxRowPositionEnabled = { false },
                taskWaitingOnYouEnabled = { true },
                realtimeBackgroundRefreshIntervalMillis = 0L,
            )

        store.dispatch(SharedActivityIntent.LoadInitial)
        testScope.runCurrent()
        assertEquals(1, repo.initialLoadCount)

        store.dispatch(
            SharedActivityIntent.ReconcileTaskWaiting(
                serverId = "server-1",
                task = taskSummary(),
                previous = null,
            )
        )
        testScope.advanceTimeBy(500L)
        testScope.runCurrent()
        assertEquals(2, repo.initialLoadCount)
        assertEquals(InboxRefreshTrigger.TaskWaiting, repo.listTriggers.last())
        assertEquals(waiting, store.state.value.items.single().taskWaiting)
    }

    @Test
    fun realtimeDomainOwnerCapturesPreviousTaskBeforeUpsertAndSkipsStaleRevisions() = runTest {
        val tasksStore = SharedTasksDataStore()
        val owner = SharedRealtimeDomainEventOwner(tasksDataStore = tasksStore)
        val delivered = mutableListOf<Triple<String, TaskSummary?, TaskSummary>>()
        owner.installTaskUpdatedSink { serverId, previous, task ->
            delivered += Triple(serverId, previous, task)
        }

        val initial = taskSummary(assigneeId = "user-2", claimedAt = "2026-11-26T09:00:00Z", revision = 2L)
        val reassigned = taskSummary(assigneeId = "user-1", claimedAt = "2026-11-26T10:05:00Z", revision = 3L)
        val stale = taskSummary(assigneeId = "user-3", claimedAt = "2026-11-26T08:00:00Z", revision = 1L)

        val sinks = RealtimeGuardedDomainEventSinks()
        owner.deliverGuardedAppShellDomainFinal(sinks, "server-1", TaskUpdated(initial))
        owner.deliverGuardedAppShellDomainFinal(sinks, "server-1", TaskUpdated(reassigned))
        owner.deliverGuardedAppShellDomainFinal(sinks, "server-1", TaskUpdated(stale))

        assertEquals(2, delivered.size, "stale revision must not fire taskUpdatedSink")
        assertNull(delivered[0].second)
        assertEquals(initial.id, delivered[0].third.id)
        assertEquals(initial.claimedAt, delivered[1].second?.claimedAt)
        assertEquals("user-2", delivered[1].second?.assignee?.id?.value)
        assertEquals(reassigned.claimedAt, delivered[1].third.claimedAt)
    }
}
