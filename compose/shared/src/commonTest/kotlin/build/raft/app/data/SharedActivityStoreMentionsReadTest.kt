package build.raft.app.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Reading a mention keeps the row in Mentions, like the server and web
 * (botiverse/slock 1bb70cbeb: channelService.ts:13136-13168 keeps read rows in
 * the `mentions` filter; inboxStore.ts:1337-1353 / 1384-1415 drop rows only for
 * `isUnreadInboxFilter`). Only the Unread filter removes a read row.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedActivityStoreMentionsReadTest {

    @Test
    fun singleReadKeepsTheRowInMentionsAndAllButDropsItFromUnread() = runTest {
        val both = listOf("channel:channel-1", "channel:channel-2")
        // Unread keeps the row just read (shown read) until the user moves on; the pull-to-refresh
        // below is moving on, so only then does it leave (SharedActivityStoreRetainedReadRowTest).
        listOf(
            Triple(InboxFilter.All, both, both),
            Triple(InboxFilter.Mentions, both, both),
            Triple(InboxFilter.Unread, both, listOf("channel:channel-2"))
        ).forEach { (filter, keysAfterRead, expectedKeys) ->
            val read = activityItem("channel-1", unreadCount = 2).copy(hasMention = true, readStateLatestActivitySeq = "10")
            val other = activityItem("channel-2", unreadCount = 1).copy(hasMention = true, readStateLatestActivitySeq = "7")
            val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(read, other))))
            val store = activityStore(backgroundScope, inbox, nowMillis = { testScheduler.currentTime })
            store.dispatch(enter(filter))
            runCurrent()

            store.dispatch(SharedActivityIntent.MarkRead(store.state.value.items.first()))
            runCurrent()
            assertEquals(keysAfterRead, store.state.value.items.map { it.key }, "filter=$filter")
            assertEquals(1, store.state.value.totalUnreadCount, "filter=$filter")

            // A lagging copy that still carries the pre-read state stays read and
            // stays listed (the read hold), rather than vanishing from Mentions.
            store.dispatch(SharedActivityIntent.PullRefresh)
            runCurrent()
            assertEquals(expectedKeys, store.state.value.items.map { it.key }, "refresh filter=$filter")
            store.state.value.items.firstOrNull { it.key == read.key }?.let { kept ->
                assertEquals(false to 0, kept.hasMention to kept.unreadCount, "filter=$filter")
            }
        }
    }

    @Test
    fun markAllReadKeepsMentionsRowsListedAndOnlyEmptiesUnread() = runTest {
        listOf(InboxFilter.Mentions to 2, InboxFilter.Unread to 0).forEach { (filter, expectedRows) ->
            val rows =
                listOf(
                    activityItem("channel-1", unreadCount = 2).copy(hasMention = true, readStateLatestActivitySeq = "10"),
                    threadItem("thread-1", unreadCount = 3).copy(hasMention = true, readStateLatestActivitySeq = "4")
                )
            val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(rows)))
            val read = FakeReadReceiptRepository()
            val store =
                activityStore(backgroundScope, inbox, readReceiptRepository = read, nowMillis = { testScheduler.currentTime })
            store.dispatch(enter(filter))
            runCurrent()

            store.dispatch(SharedActivityIntent.MarkAllRead)
            runCurrent()

            assertEquals(1, read.inboxReadAllServerIds.size, "filter=$filter")
            assertEquals(2, inbox.initialLoadCount, "a confirmed read-all refreshes once, like web; filter=$filter")
            assertEquals(expectedRows, store.state.value.items.size, "filter=$filter")
            assertEquals(expectedRows, store.state.value.totalCount, "filter=$filter")
            assertEquals(0, store.state.value.totalUnreadCount, "filter=$filter")
            assertEquals(List(expectedRows) { false to 0 }, store.state.value.items.map { it.hasMention to it.unreadCount })
        }
    }

    private fun enter(filter: InboxFilter): SharedActivityIntent =
        if (filter == InboxFilter.All) SharedActivityIntent.LoadInitial else SharedActivityIntent.SetFilter(filter)
}
