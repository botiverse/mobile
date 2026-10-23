package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.PerReceiverReadStateChanged
import build.raft.app.model.PerReceiverReadStateScope
import build.raft.app.model.ServerId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * "Being read" and "staying in the list" are separate (stdrc 10-23, via Remy; Gmail / Apple Mail
 * unread view): in Unread, every row the user reads during this visit (opened, marked read, or
 * listed at "Mark all read") is read at once (counts drop) but stays listed, shown read, until the
 * user moves on: switches filter, leaves Activity, pulls to refresh, or switches server. Opening
 * another row does not drop earlier ones. Returning from the conversation (phone Back, closing the
 * wide detail pane: no store intent but the return refresh), foreground catch-up, realtime and
 * background reconciles keep them; Done removes a row at once; a new message makes a retained row
 * unread again and it moves as usual. Web changes the same way.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedActivityStoreRetainedReadRowTest {
    private val rowA = activityItem("channel-a", unreadCount = 2).copy(readStateLatestActivitySeq = "10")
    private val rowB = activityItem("channel-b", unreadCount = 1).copy(readStateLatestActivitySeq = "7")
    private val rowC = activityItem("channel-c", unreadCount = 1)
    private val rowD = activityItem("channel-d", unreadCount = 1)
    private val owner = ReadReceiptOwner("production", "user-1", "server-1")

    /** How the opened row gets read: the Activity open itself, or the conversation's read receipt. */
    private enum class ReadPath { OpenMarksRead, ConversationReadReceipt }

    @Test
    fun everyRowReadThisVisitStaysReadInPlace() = runTest {
        ReadPath.entries.forEach { path ->
            // The server stops listing each row once it is read.
            val inbox =
                FakeInboxRepository(
                    initialSnapshots = listOf(snapshot(listOf(rowA, rowB, rowC)), snapshot(listOf(rowB, rowC)), snapshot(listOf(rowC)))
                )
            val store = unreadStore(inbox)

            store.open(rowA, path, revision = 1L)
            runCurrent()
            assertUnreadList(store, listOf(rowA.key to 0, rowB.key to 1, rowC.key to 1), "opened A, $path")
            assertEquals(setOf(rowA.key), store.state.value.retainedReadRowKeys, "$path")
            assertEquals(2 to 2, store.state.value.totalUnreadCount to store.state.value.totalCount, "counts drop at once, $path")

            // Back to the list on the phone: the page reappears and refreshes; A is still there.
            store.dispatch(SharedActivityIntent.Refresh)
            runCurrent()
            assertUnreadList(store, listOf(rowA.key to 0, rowB.key to 1, rowC.key to 1), "after return, $path")

            // Opening B keeps A: both stay, read, where they stood, across the next return refresh.
            store.open(store.state.value.items.first { it.key == rowB.key }, path, revision = 2L)
            runCurrent()
            assertUnreadList(store, listOf(rowA.key to 0, rowB.key to 0, rowC.key to 1), "opened B, $path")
            assertEquals(setOf(rowA.key, rowB.key), store.state.value.retainedReadRowKeys, "$path")
            assertEquals(1 to 1, store.state.value.totalUnreadCount to store.state.value.totalCount, "$path")
            store.dispatch(SharedActivityIntent.Refresh)
            runCurrent()
            assertEquals(3, inbox.initialLoadCount, "$path")
            assertUnreadList(store, listOf(rowA.key to 0, rowB.key to 0, rowC.key to 1), "after second return, $path")

            // Reopening a row already read this visit is a no-op: the same state, no emission.
            val before = store.state.value
            store.dispatch(SharedActivityIntent.RowOpened(before.items.first()))
            assertSame(before, store.state.value, "$path")
        }
    }

    @Test
    fun movingOnDropsEveryRowReadThisVisit() = runTest {
        listOf<Pair<String, suspend TestScope.(SharedActivityStore) -> Unit>>(
            "switch filter" to { store ->
                store.dispatch(SharedActivityIntent.SetFilter(InboxFilter.All))
                runCurrent()
                assertEquals(
                    listOf(rowC.key),
                    store.state.value.filterSnapshots[InboxFilter.Unread]?.items?.map { it.key },
                    "the Unread page cached behind All no longer lists A or B"
                )
                store.dispatch(SharedActivityIntent.SetFilter(InboxFilter.Unread))
            },
            "pull to refresh" to { store -> store.dispatch(SharedActivityIntent.PullRefresh) },
            "leave the Activity tab or route" to { store -> store.dispatch(SharedActivityIntent.SurfaceHidden) },
        ).forEach { (name, moveOn) ->
            val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(rowA, rowB, rowC)), snapshot(listOf(rowC))))
            val store = unreadStore(inbox)
            store.open(rowA, ReadPath.OpenMarksRead, revision = 1L)
            store.open(rowB, ReadPath.OpenMarksRead, revision = 2L)
            runCurrent()
            assertEquals(setOf(rowA.key, rowB.key), store.state.value.retainedReadRowKeys, name)

            moveOn(store)
            runCurrent()
            assertUnreadList(store, listOf(rowC.key to 1), name)
            assertEquals(emptySet(), store.state.value.retainedReadRowKeys, name)

            // A later reload does not bring them back.
            store.dispatch(SharedActivityIntent.Refresh)
            runCurrent()
            assertUnreadList(store, listOf(rowC.key to 1), "after reload, $name")
        }
    }

    @Test
    fun markAllReadKeepsEveryListedRowReadUntilTheUserMovesOn() = runTest {
        // After the read-all the server lists only D, a row that arrived since.
        val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(rowA, rowB, rowC)), snapshot(listOf(rowD))))
        val pendingReadAll = CompletableDeferred<DataResult<ReadReceiptResult>>()
        val read = FakeReadReceiptRepository(pendingInboxReadAllResult = pendingReadAll)
        val store =
            activityStore(backgroundScope, inbox, readReceiptRepository = read, nowMillis = { testScheduler.currentTime })
        store.dispatch(SharedActivityIntent.SetFilter(InboxFilter.Unread))
        runCurrent()
        store.open(rowA, ReadPath.OpenMarksRead, revision = 1L)
        runCurrent()

        store.dispatch(SharedActivityIntent.MarkAllRead)
        runCurrent()
        assertUnreadList(store, listOf(rowA.key to 0, rowB.key to 0, rowC.key to 0), "the list does not change")
        assertEquals(0 to 0, store.state.value.totalUnreadCount to store.state.value.totalCount)
        assertEquals(setOf(rowA.key, rowB.key, rowC.key), store.state.value.retainedReadRowKeys)

        pendingReadAll.complete(DataResult.Content(ReadReceiptResult()))
        runCurrent()
        assertEquals(1, read.inboxReadAllServerIds.size)
        assertEquals(2, inbox.initialLoadCount, "the confirmed read-all refreshes in the background, like web")
        assertUnreadList(
            store,
            listOf(rowD.key to 1, rowA.key to 0, rowB.key to 0, rowC.key to 0),
            "a new unread row arrives as normal; the read rows stay"
        )

        store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()
        assertUnreadList(store, listOf(rowD.key to 1), "moving on drops them")
    }

    @Test
    fun doneRemovesARetainedRowAtOnceAndKeepsTheOthers() = runTest {
        val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(rowA, rowB, rowC)), snapshot(listOf(rowC))))
        val store = unreadStore(inbox)
        store.open(rowA, ReadPath.OpenMarksRead, revision = 1L)
        store.open(rowB, ReadPath.OpenMarksRead, revision = 2L)
        runCurrent()

        store.dispatch(SharedActivityIntent.MarkDone(store.state.value.items.first { it.key == rowA.key }))
        runCurrent()

        assertEquals(listOf(rowA.channelId), inbox.doneChannelIds)
        assertUnreadList(store, listOf(rowB.key to 0, rowC.key to 1), "Done leaves at once")
        assertEquals(setOf(rowB.key), store.state.value.retainedReadRowKeys)
    }

    @Test
    fun aRetainedRowWithANewMessageIsUnreadAgainAndStaysAfterMovingOn() = runTest {
        val unreadAgain =
            rowA.copy(unreadCount = 1, readStateLatestActivitySeq = "11", latestActivityMessageId = MessageId("new-a"))
        // The canonical page after the new message on A lists A first, unread again; B stays read.
        val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(rowC, rowA, rowB)), snapshot(listOf(unreadAgain, rowC))))
        val store = unreadStore(inbox, refreshIntervalMillis = 2_500L)
        store.open(rowA, ReadPath.OpenMarksRead, revision = 1L)
        store.open(rowB, ReadPath.OpenMarksRead, revision = 2L)
        runCurrent()
        assertUnreadList(store, listOf(rowC.key to 1, rowA.key to 0, rowB.key to 0), "A and B read")

        // Unread takes the new message through the background reconcile, like any other row.
        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(MessageNew(message("new-a", channelId = "channel-a", seq = 11L))))
        advanceTimeBy(2_501L)
        runCurrent()
        assertUnreadList(store, listOf(rowA.key to 1, rowC.key to 1, rowB.key to 0), "A is unread again and moves up as usual")

        store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()
        assertUnreadList(store, listOf(unreadAgain.key to 1, rowC.key to 1), "only the read rows leave")
    }

    @Test
    fun aServerSwitchDropsTheRetainedRows() = runTest {
        val auth = InMemoryRaftAuthSessionStore(twoServerSession(selected = "server-1"))
        val inbox =
            FakeInboxRepository(
                initialSnapshots = listOf(snapshot(listOf(rowA, rowB)), snapshot(listOf(rowA.copy(unreadCount = 0), rowB), serverId = "server-2"))
            )
        val store = unreadStore(inbox, authStore = auth)
        store.open(rowA, ReadPath.OpenMarksRead, revision = 1L)
        store.open(rowB, ReadPath.OpenMarksRead, revision = 2L)
        runCurrent()
        assertEquals(setOf(rowA.key, rowB.key), store.state.value.retainedReadRowKeys)

        auth.saveSession(twoServerSession(selected = "server-2"))
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()

        assertEquals("server-2" to InboxFilter.All, store.state.value.ownerServerId to store.state.value.selectedFilter)
        assertEquals(emptySet(), store.state.value.retainedReadRowKeys)
    }

    @Test
    fun realtimeAndBackgroundReconcileKeepEveryRetainedRow() = runTest {
        val remotelyRead = rowC.copy(readStateLatestActivitySeq = "7")
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(listOf(rowA, rowB, remotelyRead, rowD)),
                        // The reconcile no longer lists A and B (read here) nor C (read elsewhere).
                        snapshot(listOf(rowD))
                    )
            )
        val store = unreadStore(inbox, refreshIntervalMillis = 2_500L)
        store.open(rowA, ReadPath.OpenMarksRead, revision = 1L)
        store.open(rowB, ReadPath.OpenMarksRead, revision = 2L)
        runCurrent()

        // C is read on another device: it leaves Unread, the retained A and B stay.
        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(readState("channel-c", version = 2, maxReadSeq = 7)))
        runCurrent()
        assertUnreadList(store, listOf(rowA.key to 0, rowB.key to 0, rowD.key to 1), "remote read of C")

        // A read that may not cover D asks the server; that background reconcile keeps A and B in place.
        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(readState("channel-d", version = 3, maxReadSeq = 1)))
        advanceTimeBy(2_501L)
        runCurrent()
        assertEquals(2, inbox.initialLoadCount, "one background reconcile")
        assertUnreadList(store, listOf(rowA.key to 0, rowB.key to 0, rowD.key to 1), "after reconcile")
        assertEquals(setOf(rowA.key, rowB.key), store.state.value.retainedReadRowKeys)
    }

    private fun TestScope.unreadStore(
        inbox: InboxRepository,
        authStore: RaftAuthSessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
        refreshIntervalMillis: Long = 60_000L
    ): SharedActivityStore {
        val store =
            activityStore(
                backgroundScope,
                inbox,
                authStore = authStore,
                realtimeBackgroundRefreshIntervalMillis = refreshIntervalMillis,
                nowMillis = { testScheduler.currentTime }
            )
        store.dispatch(SharedActivityIntent.SetFilter(InboxFilter.Unread))
        runCurrent()
        return store
    }

    /** Dispatches what the Activity row open does (SharedInboxPanel openRow) for [path]. */
    private fun SharedActivityStore.open(item: InboxItem, path: ReadPath, revision: Long) {
        when (path) {
            ReadPath.OpenMarksRead -> {
                dispatch(SharedActivityIntent.MarkRead(item))
                dispatch(SharedActivityIntent.RowOpened(item))
            }
            ReadPath.ConversationReadReceipt -> {
                dispatch(SharedActivityIntent.RowOpened(item))
                dispatch(
                    SharedActivityIntent.ApplyReadReceiptPrediction(
                        ReadReceiptPrediction(owner, item.channelId, revision, ReadReceiptTarget.All)
                    )
                )
            }
        }
    }

    private fun assertUnreadList(store: SharedActivityStore, expected: List<Pair<String, Int>>, message: String) {
        assertEquals(InboxFilter.Unread, store.state.value.selectedFilter, message)
        assertEquals(expected, store.state.value.items.map { it.key to it.unreadCount }, message)
    }

    private fun readState(scopeId: String, version: Long, maxReadSeq: Long): PerReceiverReadStateChanged =
        PerReceiverReadStateChanged(
            serverId = ServerId("server-1"),
            scopes = listOf(PerReceiverReadStateScope(ChannelId(scopeId), maxReadSeq = maxReadSeq, readStateVersion = version))
        )

    private fun twoServerSession(selected: String): StoredAuthSession =
        sampleSession(serverId = selected).copy(
            servers = listOf(StoredAuthServer(id = "server-1", name = "One"), StoredAuthServer(id = "server-2", name = "Two"))
        )
}
