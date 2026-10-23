package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.PerReceiverReadStateChanged
import build.raft.app.model.PerReceiverReadStateScope
import build.raft.app.model.ServerId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * "Being read" and "staying in the list" are separate (Tenny, via stdrc; Gmail's unread view): in
 * Unread, the row the user opens is read at once (counts drop) but stays listed, shown read, until
 * the user moves on: opens another row, switches filter, leaves Activity, pulls to refresh, or
 * switches server. Returning from the conversation, foreground catch-up, realtime and background
 * reconciles keep it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedActivityStoreRetainedReadRowTest {
    private val rowA = activityItem("channel-a", unreadCount = 2).copy(readStateLatestActivitySeq = "10")
    private val rowB = activityItem("channel-b", unreadCount = 1).copy(readStateLatestActivitySeq = "7")
    private val rowC = activityItem("channel-c", unreadCount = 1)
    private val owner = ReadReceiptOwner("production", "user-1", "server-1")

    /** How the opened row gets read: the Activity open itself, or the conversation's read receipt. */
    private enum class ReadPath { OpenMarksRead, ConversationReadReceipt }

    @Test
    fun theOpenedRowStaysReadUntilAnotherRowIsOpened() = runTest {
        ReadPath.entries.forEach { path ->
            // The server stops listing A once it is read.
            val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(rowA, rowB, rowC)), snapshot(listOf(rowB, rowC))))
            val store = unreadStore(inbox)

            store.open(rowA, path, revision = 1L)
            runCurrent()
            assertUnreadList(store, listOf(rowA.key to 0, rowB.key to 1, rowC.key to 1), "opened A, $path")
            assertEquals(rowA.key, store.state.value.retainedReadRowKey, "$path")
            assertEquals(2 to 2, store.state.value.totalUnreadCount to store.state.value.totalCount, "counts drop at once, $path")

            // Back to the list on the phone: the page reappears and refreshes; A is still there.
            store.dispatch(SharedActivityIntent.Refresh)
            runCurrent()
            assertEquals(2, inbox.initialLoadCount, "$path")
            assertUnreadList(store, listOf(rowA.key to 0, rowB.key to 1, rowC.key to 1), "after return, $path")

            store.open(store.state.value.items.first { it.key == rowB.key }, path, revision = 2L)
            runCurrent()
            assertUnreadList(store, listOf(rowB.key to 0, rowC.key to 1), "opened B, $path")
            assertEquals(rowB.key, store.state.value.retainedReadRowKey, "$path")
            assertEquals(1, store.state.value.totalUnreadCount, "$path")
        }
    }

    @Test
    fun movingOnDropsTheRetainedRow() = runTest {
        val bAndC = listOf(rowB.key to 1, rowC.key to 1)
        listOf<Triple<String, suspend TestScope.(SharedActivityStore) -> Unit, List<Pair<String, Int>>>>(
            Triple("switch filter", { store ->
                store.dispatch(SharedActivityIntent.SetFilter(InboxFilter.All))
                runCurrent()
                assertEquals(
                    listOf(rowB.key, rowC.key),
                    store.state.value.filterSnapshots[InboxFilter.Unread]?.items?.map { it.key },
                    "the Unread page cached behind All no longer lists A"
                )
                store.dispatch(SharedActivityIntent.SetFilter(InboxFilter.Unread))
            }, bAndC),
            Triple("pull to refresh", { store -> store.dispatch(SharedActivityIntent.PullRefresh) }, bAndC),
            Triple("leave the Activity tab or route", { store -> store.dispatch(SharedActivityIntent.SurfaceHidden) }, bAndC),
            Triple("mark all read", { store -> store.dispatch(SharedActivityIntent.MarkAllRead) }, emptyList()),
        ).forEach { (name, moveOn, expected) ->
            val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(rowA, rowB, rowC)), snapshot(listOf(rowB, rowC))))
            val store = unreadStore(inbox)
            store.open(rowA, ReadPath.OpenMarksRead, revision = 1L)
            runCurrent()

            moveOn(store)
            runCurrent()
            assertUnreadList(store, expected, name)
            assertNull(store.state.value.retainedReadRowKey, name)

            // A later reload does not bring it back.
            store.dispatch(SharedActivityIntent.Refresh)
            runCurrent()
            assertEquals(false, store.state.value.items.any { it.key == rowA.key }, "after reload, $name")
        }
    }

    @Test
    fun aServerSwitchDropsTheRetainedRow() = runTest {
        val auth = InMemoryRaftAuthSessionStore(twoServerSession(selected = "server-1"))
        val inbox =
            FakeInboxRepository(
                initialSnapshots = listOf(snapshot(listOf(rowA, rowB)), snapshot(listOf(rowA.copy(unreadCount = 0), rowB), serverId = "server-2"))
            )
        val store = unreadStore(inbox, authStore = auth)
        store.open(rowA, ReadPath.OpenMarksRead, revision = 1L)
        runCurrent()
        assertEquals(rowA.key, store.state.value.retainedReadRowKey)

        auth.saveSession(twoServerSession(selected = "server-2"))
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()

        assertEquals("server-2" to InboxFilter.All, store.state.value.ownerServerId to store.state.value.selectedFilter)
        assertNull(store.state.value.retainedReadRowKey)
    }

    @Test
    fun realtimeAndBackgroundReconcileKeepTheRetainedRow() = runTest {
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(listOf(rowA, rowB, rowC)),
                        // The reconcile no longer lists A (read) nor B (read elsewhere).
                        snapshot(listOf(rowC))
                    )
            )
        val store = unreadStore(inbox, refreshIntervalMillis = 2_500L)
        store.open(rowA, ReadPath.OpenMarksRead, revision = 1L)
        runCurrent()

        // B is read on another device: it leaves Unread, the retained A stays.
        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(readState("channel-b", version = 2, maxReadSeq = 7)))
        runCurrent()
        assertUnreadList(store, listOf(rowA.key to 0, rowC.key to 1), "remote read of B")

        // A read that may not cover C asks the server; that background reconcile keeps A in place.
        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(readState("channel-c", version = 3, maxReadSeq = 1)))
        advanceTimeBy(2_501L)
        runCurrent()
        assertEquals(2, inbox.initialLoadCount, "one background reconcile")
        assertUnreadList(store, listOf(rowA.key to 0, rowC.key to 1), "after reconcile")
        assertEquals(rowA.key, store.state.value.retainedReadRowKey)
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
