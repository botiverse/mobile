package build.raft.app.data

import build.raft.app.data.realtime.PerReceiverStateVersionStore
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.PerReceiverReadStateChanged
import build.raft.app.model.PerReceiverReadStateScope
import build.raft.app.model.ServerId
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Persistence audit addendum 2 item 4: an accepted realtime read fact writes its version to
 * [PerReceiverStateVersionStore]; the Activity rows it cleared must reach disk in the same
 * reduction, or a cold start shows the old unread next to the newer version (and a replay of that
 * version is then "Current" and never clears it).
 */
class SharedActivityReadStatePersistTest {
    @Test
    fun aRealtimeReadFactPersistsTheClearedRowWithItsVersion() = runTest {
        val keyValues = InMemoryRaftKeyValueStore()
        val persistStorage = InMemoryPersistStorage()
        val unread = activityItem("channel-1", unreadCount = 3).copy(readStateLatestActivitySeq = "10")
        val first =
            activityStore(
                scope = backgroundScope,
                // The reconcile the read schedules never answers: the app dies first.
                inboxRepository =
                    FakeInboxRepository(listOf(snapshot(listOf(unread)))) { call -> if (call > 1) awaitCancellation() },
                persistSlices = listOf(createSharedActivityPersistSlice(persistStorage)),
                perReceiverStateVersionStore = PerReceiverStateVersionStore(keyValues),
            )
        first.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        assertEquals(3, first.state.value.totalUnreadCount)

        first.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                PerReceiverReadStateChanged(
                    ServerId("server-1"),
                    listOf(PerReceiverReadStateScope(ChannelId("channel-1"), maxReadSeq = 10, readStateVersion = 4))
                )
            )
        )
        runCurrent()
        assertEquals(0, first.state.value.totalUnreadCount, "the live row is read")

        val reopened =
            activityStore(
                scope = backgroundScope,
                inboxRepository = FakeInboxRepository(listOf(snapshot(emptyList()))) { awaitCancellation() },
                persistSlices = listOf(createSharedActivityPersistSlice(persistStorage)),
                perReceiverStateVersionStore = PerReceiverStateVersionStore(keyValues),
            )
        reopened.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()

        assertEquals(
            4L,
            PerReceiverStateVersionStore(keyValues)
                .readReadStateCheckpoint(ServerId("server-1"), MemberId("user-1"), ChannelId("channel-1"))
                .version
        )
        assertEquals(0, reopened.state.value.totalUnreadCount, "the cold start shows the read under that version")
        assertEquals(0, reopened.state.value.items.sumOf(InboxItem::unreadCount))
    }
}
