package build.raft.app.data

import build.raft.app.model.MessageNew
import build.raft.app.model.ServerId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The Activity store's side of sync `inbox_row` changes (#proj-mobile:#167; botiverse/slock #15312
 * `applySyncInboxRowChange`): an upsert merges like a `?ids=` answer, a row the list lacks is added, a
 * delete applies at or past the shown position, and none of it reads the list. A list the position
 * rule does not keep takes nothing ([RaftSyncInboxRowsOutcome.NotApplied]). The rule itself is pinned
 * by the `sync_*` contract cases (ActivityRowContractCasesTest, ActivityRowContractCaseReducerTest).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedActivityStoreSyncInboxRowsTest {
    /** Counts the inbox reads: a sync change applied in place makes none. */
    private class CountingInbox(lists: List<InboxSnapshot>) : InboxRepository by FakeInboxRepository(lists) {
        private val answers = lists
        var listReads = 0
        var rowReads = 0

        override suspend fun loadInitial(serverId: ServerId, filter: InboxFilter, limit: Int): DataResult<InboxSnapshot> {
            return DataResult.Content(answers.getOrElse(listReads++) { answers.last() })
        }

        override suspend fun loadRows(serverId: ServerId, rowIds: List<String>): DataResult<InboxSnapshot> {
            rowReads += 1
            return DataResult.Empty(EmptyReason.NoInboxItems)
        }
    }

    private class Fixture(val store: SharedActivityStore, val inbox: CountingInbox) {
        fun row(key: String): InboxItem? = store.state.value.items.firstOrNull { it.key == key }
    }

    private fun TestScope.loaded(
        rows: List<InboxItem>,
        rowPosition: Boolean = true,
        hasMore: Boolean = false,
        totalCount: Int = rows.size,
        totalUnreadCount: Int = rows.sumOf { it.unreadCount },
        followUpSnapshots: List<InboxSnapshot> = emptyList(),
    ): Fixture {
        val initial = snapshot(rows, hasMore = hasMore, totalCount = totalCount, totalUnreadCount = totalUnreadCount)
        val inbox = CountingInbox(listOf(initial) + followUpSnapshots)
        val store =
            activityStore(
                scope = backgroundScope,
                inboxRepository = inbox,
                realtimeBackgroundRefreshIntervalMillis = ActivityRealtimeBackgroundRefreshIntervalMillis,
                nowMillis = { testScheduler.currentTime },
                inboxInPlaceEnabled = { true },
                inboxRowPositionEnabled = { rowPosition }
            )
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        return Fixture(store, inbox)
    }

    private fun TestScope.settle() {
        runCurrent()
        advanceTimeBy(ActivityRealtimeBackgroundRefreshIntervalMillis + 1L)
        runCurrent()
    }

    private fun row(channelId: String, seq: String, unread: Int = 0, at: String = "2026-11-17T09:00:00.000Z") =
        activityItem(channelId, unread).copy(latestActivitySeq = seq, readStateLatestActivitySeq = seq, lastMessageAt = at)

    private fun changes(vararg changes: String): List<RaftSyncInboxRowChange> =
        parseSyncChangesPage(syncChangesBody("c1.1.1", changes = changes.toList()))!!.changes.filterIsInstance<RaftSyncInboxRowChange>()

    private fun Fixture.sync(vararg changes: String): RaftSyncInboxRowsOutcome = store.applySyncInboxRows("server-1", changes(*changes))

    @Test
    fun anUpsertMergesLikeARowAnswerAndAnOlderOneIsIgnored() = runTest {
        val f = loaded(listOf(row("ch-1", "10", unread = 1), row("ch-2", "5")))
        // (upsert seq, unread) -> what the row shows: newer and equal replace, older is ignored.
        val steps = listOf(Triple("12", 3, "12" to 3), Triple("12", 2, "12" to 2), Triple("11", 7, "12" to 2))
        for ((seq, unread, expected) in steps) {
            val outcome = f.sync(syncInboxRowUpsert(1, "ch-1", syncInboxChannelRow("ch-1", seq, unread = unread, at = "2026-11-17T10:00:00.000Z")))
            assertEquals(setOf("ch-1"), assertIs<RaftSyncInboxRowsOutcome.Applied>(outcome).settledIds)
            val shown = f.row("channel:ch-1")!!
            assertEquals(expected, shown.latestActivitySeq to shown.unreadCount, "upsert $seq/$unread")
        }
        assertEquals(2, f.store.state.value.snapshot!!.totalUnreadCount)
        settle()
        assertEquals(1 to 0, f.inbox.listReads to f.inbox.rowReads, "applied in place: no inbox read")
    }

    @Test
    fun anUpsertWhoseServedReadCoversItHasNothingUnread() = runTest {
        val f = loaded(listOf(row("ch-1", "10", unread = 1)))
        f.sync(syncInboxRowUpsert(1, "ch-1", syncInboxChannelRow("ch-1", "12", unread = 2, maxReadSeq = "12")))
        assertEquals(0, f.row("channel:ch-1")!!.unreadCount)
        assertEquals("12", f.store.rowPositionReadSeqForTest("ch-1").first)
    }

    @Test
    fun anUpsertOfARowTheListLacksAddsItAtItsPlaceByTime() = runTest {
        val f = loaded(listOf(row("ch-1", "10", at = "2026-11-17T08:00:00.000Z")))
        f.sync(syncInboxRowUpsert(1, "ch-new", syncInboxChannelRow("ch-new", "20", unread = 2, at = "2026-11-17T10:00:00.000Z")))
        assertEquals(listOf("channel:ch-new", "channel:ch-1"), f.store.state.value.items.map { it.key })
        val snapshot = f.store.state.value.snapshot!!
        assertEquals(2 to 2, snapshot.totalCount to snapshot.totalUnreadCount)

        // Past the loaded window while more pages wait: the next page brings it.
        val paged = loaded(listOf(row("ch-1", "10", at = "2026-11-17T08:00:00.000Z")), hasMore = true)
        paged.sync(syncInboxRowUpsert(1, "ch-old", syncInboxChannelRow("ch-old", "3", at = "2026-11-16T08:00:00.000Z")))
        assertNull(paged.row("channel:ch-old"))
    }

    @Test
    fun aDeleteAppliesAtOrPastTheShownPositionAndALeftOneAlways() = runTest {
        val f = loaded(listOf(row("ch-1", "104"), row("ch-2", "200", unread = 3), row("ch-3", "7")))
        // A live message moved ch-1 to 105: a Done at 104 is older than it.
        f.store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(MessageNew(message("m-105", channelId = "ch-1", seq = 105L))))
        runCurrent()
        assertEquals("105", f.row("channel:ch-1")!!.latestActivitySeq)
        f.sync(syncInboxRowDelete(1, "ch-1", "104"))
        assertEquals("105", f.row("channel:ch-1")?.latestActivitySeq, "kept: a newer message brought it back")
        f.sync(syncInboxRowDelete(2, "ch-1", "105"), syncInboxRowDelete(3, "ch-2", null, reason = "left"))
        assertEquals(listOf("channel:ch-3"), f.store.state.value.items.map { it.key })
        assertEquals(1, f.store.state.value.snapshot!!.totalCount)
        // A non-canonical position keeps the row (fail closed).
        f.sync(syncInboxRowDelete(4, "ch-3", "7a"))
        assertEquals(listOf("channel:ch-3"), f.store.state.value.items.map { it.key })
        settle()
        assertEquals(0, f.inbox.rowReads)
    }

    @Test
    fun aListThePositionRuleDoesNotKeepTakesNothing() = runTest {
        val f = loaded(listOf(row("ch-1", "10", unread = 1)), rowPosition = false)
        val outcome = f.sync(syncInboxRowUpsert(1, "ch-1", syncInboxChannelRow("ch-1", "12", unread = 3)))
        assertEquals(RaftSyncInboxRowsOutcome.NotApplied, outcome)
        assertEquals(1, f.row("channel:ch-1")!!.unreadCount)
        // Another server's list neither.
        val other = loaded(listOf(row("ch-1", "10", unread = 1)))
        assertEquals(RaftSyncInboxRowsOutcome.NotApplied, other.store.applySyncInboxRows("server-2", changes(syncInboxRowDelete(1, "ch-1", null))))
    }

    @Test
    fun aRowThatDoesNotReadReadsTheListAgain() = runTest {
        val f = loaded(listOf(row("ch-1", "10")))
        val outcome = f.sync(syncInboxRowUpsert(1, "ch-1", """{"kind":"channel"}"""))
        assertEquals(emptySet<String>(), assertIs<RaftSyncInboxRowsOutcome.Applied>(outcome).settledIds)
        settle()
        assertEquals(2, f.inbox.listReads)
    }

    @Test
    fun offPageUpsertOlderThanWindowWhileHasMoreReconcilesTotalUnreadCount() = runTest {
        val page1 = listOf(row("ch-1", "10", unread = 0, at = "2026-11-17T08:00:00.000Z"))
        val f =
            loaded(
                rows = page1,
                hasMore = true,
                totalCount = 40,
                totalUnreadCount = 5,
                followUpSnapshots = listOf(snapshot(page1, hasMore = true, totalCount = 40, totalUnreadCount = 0)),
            )

        // Off-page row read on another device (older than items.last(), unread=0): skipped from page 1
        // but must schedule an authoritative reconcile so totalUnreadCount clears from 5 -> 0.
        f.sync(syncInboxRowUpsert(1, "ch-old", syncInboxChannelRow("ch-old", "3", unread = 0, at = "2026-11-16T08:00:00.000Z")))
        assertNull(f.row("channel:ch-old"))
        settle()
        assertEquals(2, f.inbox.listReads, "off-page upsert while hasMore=true must reconcile totalUnreadCount")
        assertEquals(0, f.store.state.value.snapshot!!.totalUnreadCount)
    }

    @Test
    fun offPageDeleteWhileHasMoreAndUnreadReconcilesTotalUnreadCount() = runTest {
        val page1 = listOf(row("ch-1", "10", unread = 1, at = "2026-11-17T08:00:00.000Z"))
        val f =
            loaded(
                rows = page1,
                hasMore = true,
                totalCount = 40,
                totalUnreadCount = 4,
                followUpSnapshots = listOf(snapshot(page1, hasMore = true, totalCount = 39, totalUnreadCount = 1)),
            )

        f.sync(syncInboxRowDelete(1, "ch-page2", "20", reason = "done"))
        settle()
        assertEquals(2, f.inbox.listReads, "off-page delete while hasMore=true and totalUnreadCount>0 must reconcile")
        assertEquals(1, f.store.state.value.snapshot!!.totalUnreadCount)
    }

    @Test
    fun offPageUpsertInsertingIntoPageOneWhileHasMoreDoesNotDoubleCountAndReconciles() = runTest {
        val ch1 = row("ch-1", "10", unread = 0, at = "2026-11-17T08:00:00.000Z")
        val bumped = row("ch-page2", "25", unread = 6, at = "2026-11-17T10:00:00.000Z")
        val f =
            loaded(
                rows = listOf(ch1),
                hasMore = true,
                totalCount = 40,
                totalUnreadCount = 7,
                followUpSnapshots = listOf(snapshot(listOf(bumped, ch1), hasMore = true, totalCount = 40, totalUnreadCount = 8)),
            )

        // ch-page2 already contributed 5 unreads (plus 2 on ch-page3 = 7 totalUnreadCount) on page 2;
        // a 6th message bumps ch-page2 onto page 1 with unread=6. Must NOT double-count to 7 + 6 = 13
        // before reconciling with the server's authoritative count (8).
        f.sync(syncInboxRowUpsert(1, "ch-page2", syncInboxChannelRow("ch-page2", "25", unread = 6, at = "2026-11-17T10:00:00.000Z")))
        assertEquals(1, f.inbox.listReads, "still inside throttle window before reconcile")
        assertEquals(listOf("channel:ch-page2", "channel:ch-1"), f.store.state.value.items.map { it.key })
        assertEquals(7, f.store.state.value.snapshot!!.totalUnreadCount, "must not double-count off-page unreads")
        assertEquals(40, f.store.state.value.snapshot!!.totalCount, "must not inflate totalCount")

        settle()
        assertEquals(2, f.inbox.listReads, "must reconcile authoritative totalUnreadCount with server")
        assertEquals(8, f.store.state.value.snapshot!!.totalUnreadCount)
    }
}
