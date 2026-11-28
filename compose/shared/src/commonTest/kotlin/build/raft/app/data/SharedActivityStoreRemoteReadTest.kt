package build.raft.app.data

import build.raft.app.data.realtime.PerReceiverReadStateFactResult
import build.raft.app.data.realtime.PerReceiverStateVersionStore
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
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

/**
 * A read made on another device/tab reaches Activity like web
 * `applyReadStateUpdate` -> `inboxStore.applyReadStateProjection`
 * (botiverse/slock 1bb70cbeb, applyReadStateUpdate.ts:8-30,
 * inboxStore.ts:1666-1707). The event is `read_state:updated`
 * `{serverId, scopeId, maxReadSeq, readStateVersion, unreadCount?}`
 * (routes/channels.ts:296), mapped to [PerReceiverReadStateChanged].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedActivityStoreRemoteReadTest {

    @Test
    fun aReadCoveringTheRowFrontierClearsItAndOnlyUnreadDropsIt() = runTest {
        listOf(
            InboxFilter.All to listOf("channel:channel-1", "channel:channel-2"),
            InboxFilter.Mentions to listOf("channel:channel-1", "channel:channel-2"),
            InboxFilter.Unread to listOf("channel:channel-2")
        ).forEach { (filter, expectedKeys) ->
            val read = activityItem("channel-1", unreadCount = 3).copy(hasMention = true, readStateLatestActivitySeq = "40")
            val other = activityItem("channel-2", unreadCount = 1).copy(hasMention = true, readStateLatestActivitySeq = "9")
            val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(read, other))))
            val store = remoteReadStore(inbox, filter)

            store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(readState("channel-1", version = 2, maxReadSeq = 40)))
            runCurrent()

            assertEquals(expectedKeys, store.state.value.items.map { it.key }, "filter=$filter")
            store.state.value.items.first { it.key == other.key }.let { assertEquals(1, it.unreadCount) }
            store.state.value.items.firstOrNull { it.key == read.key }?.let { cleared ->
                assertEquals(Triple(0, null, false), Triple(cleared.unreadCount, cleared.firstUnreadMessageId, cleared.hasMention))
            }
            assertEquals(1, store.state.value.totalUnreadCount, "filter=$filter")

            // A lagging refresh that still serves the pre-read row stays read.
            store.dispatch(SharedActivityIntent.PullRefresh)
            runCurrent()
            assertEquals(expectedKeys, store.state.value.items.map { it.key }, "refresh filter=$filter")
            assertEquals(1, store.state.value.totalUnreadCount, "refresh filter=$filter")
        }
    }

    @Test
    fun aReadThatMayNotCoverTheRowSchedulesOneThrottledReconcileInstead() = runTest {
        // Older read frontier than the row's latest activity, and a row with no
        // authority frontier: neither is patched; the server answers instead.
        val behind = activityItem("channel-1", unreadCount = 2).copy(readStateLatestActivitySeq = "41")
        val unknown = threadItem("thread-1", unreadCount = 1)
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(listOf(behind, unknown)),
                        snapshot(listOf(behind.copy(unreadCount = 1), unknown.copy(unreadCount = 0, firstUnreadMessageId = null)))
                    )
            )
        val store = remoteReadStore(inbox, InboxFilter.All, refreshIntervalMillis = 2_500L)

        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(readState("channel-1", version = 2, maxReadSeq = 40)))
        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(readState("thread-1", version = 5, maxReadSeq = 77)))
        runCurrent()
        assertEquals(listOf(2, 1), store.state.value.items.map { it.unreadCount }, "nothing guessed locally")
        assertEquals(1, inbox.initialLoadCount, "throttled: inside the window of the initial load")

        advanceTimeBy(2_501L)
        runCurrent()
        assertEquals(2, inbox.initialLoadCount, "one trailing reconcile for both reads")
        assertEquals(listOf(1, 0), store.state.value.items.map { it.unreadCount })
    }

    @Test
    fun homeFirstThenActivitySecondReadStateClearsActivityUnreadAndEmitsSummaryFact() = runTest {
        for (rowPosition in listOf(false, true)) {
            val versions = PerReceiverStateVersionStore(InMemoryRaftKeyValueStore())
            val read =
                activityItem("channel-1", unreadCount = 3)
                    .copy(hasMention = true, latestActivitySeq = "40", readStateLatestActivitySeq = "40")
            val other =
                activityItem("channel-2", unreadCount = 1)
                    .copy(hasMention = false, latestActivitySeq = "9", readStateLatestActivitySeq = "9")
            val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(read, other))))
            val facts = mutableListOf<ActivityUnreadSummaryFact>()
            var unreadCountsChangedCalls = 0
            val store =
                activityStore(
                    scope = backgroundScope,
                    inboxRepository = inbox,
                    perReceiverStateVersionStore = versions,
                    onSummaryFact = { facts += it },
                    onUnreadCountsChanged = { unreadCountsChangedCalls += 1 },
                    realtimeBackgroundRefreshIntervalMillis = 60_000L,
                    nowMillis = { testScheduler.currentTime },
                    inboxInPlaceEnabled = { rowPosition },
                    inboxRowPositionEnabled = { rowPosition },
                )
            store.dispatch(SharedActivityIntent.LoadInitial)
            runCurrent()
            assertEquals(4, store.state.value.totalUnreadCount, "rowPosition=$rowPosition")
            val factsBefore = facts.size

            // Home shares PerReceiverStateVersionStore and runs before Activity in SharedHomeShellStore,
            // so Home accepts the fact first and Activity observes PerReceiverReadStateFactResult.Current.
            val homeResult =
                versions.applyOrObserveReadStateFact(
                    serverId = ServerId("server-1"),
                    memberId = MemberId("user-1"),
                    scopeId = ChannelId("channel-1"),
                    incomingVersion = 2L,
                    incomingMaxReadSeq = 40L,
                )
            assertEquals(PerReceiverReadStateFactResult.Accepted, homeResult)

            val event = readState("channel-1", version = 2L, maxReadSeq = 40L)
            store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(event))
            runCurrent()

            val cleared = store.state.value.items.first { it.key == read.key }
            assertEquals(0, cleared.unreadCount, "rowPosition=$rowPosition")
            assertEquals(false, cleared.hasMention, "rowPosition=$rowPosition")
            assertEquals(1, store.state.value.totalUnreadCount, "rowPosition=$rowPosition")
            assertEquals(factsBefore + 1, facts.size, "must emit onSummaryFact on Current (rowPosition=$rowPosition)")
            assertEquals(1, facts.last().totalUnreadCount, "rowPosition=$rowPosition")
            assertEquals(0, unreadCountsChangedCalls, "Home already accepted the fact")

            // Idempotency: repeated delivery of the same Current read state must not double-decrement or emit again.
            store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(event))
            runCurrent()
            assertEquals(1, store.state.value.totalUnreadCount, "no double-decrement (rowPosition=$rowPosition)")
            assertEquals(1, store.state.value.items.first { it.key == other.key }.unreadCount)
            assertEquals(factsBefore + 1, facts.size, "no redundant summary fact on repeat delivery")
        }
    }

    @Test
    fun remoteReadOnOffPageChannelWhileHasMoreSchedulesAuthoritativeReconcile() = runTest {
        val visible = activityItem("channel-1", unreadCount = 1).copy(latestActivitySeq = "10", readStateLatestActivitySeq = "10")
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(listOf(visible), hasMore = true, totalCount = 50, totalUnreadCount = 4),
                        snapshot(listOf(visible), hasMore = true, totalCount = 50, totalUnreadCount = 1),
                    )
            )
        val store = remoteReadStore(inbox, InboxFilter.All, refreshIntervalMillis = 2_500L)
        assertEquals(4, store.state.value.totalUnreadCount)

        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(readState("channel-off-page", version = 2L, maxReadSeq = 80L)))
        runCurrent()
        advanceTimeBy(2_501L)
        runCurrent()

        assertEquals(2, inbox.initialLoadCount, "off-page remote read must reconcile totalUnreadCount")
        assertEquals(1, store.state.value.totalUnreadCount)
    }

    private fun TestScope.remoteReadStore(
        inbox: InboxRepository,
        filter: InboxFilter,
        refreshIntervalMillis: Long = 60_000L
    ): SharedActivityStore {
        val store =
            activityStore(
                backgroundScope,
                inbox,
                realtimeBackgroundRefreshIntervalMillis = refreshIntervalMillis,
                nowMillis = { testScheduler.currentTime }
            )
        store.dispatch(
            if (filter == InboxFilter.All) SharedActivityIntent.LoadInitial else SharedActivityIntent.SetFilter(filter)
        )
        runCurrent()
        return store
    }

    private fun readState(scopeId: String, version: Long, maxReadSeq: Long): PerReceiverReadStateChanged =
        PerReceiverReadStateChanged(
            serverId = ServerId("server-1"),
            scopes = listOf(PerReceiverReadStateScope(ChannelId(scopeId), maxReadSeq = maxReadSeq, readStateVersion = version))
        )
}
