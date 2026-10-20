package build.raft.app.data

import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Realtime reply patches never change unread locally, like web
 * `receiveThreadReply` (botiverse/slock 1bb70cbeb, inboxStore.ts:1712-1777).
 * The server owns unread; the reconcile brings it. ThreadUpdated is covered by
 * SharedActivityStoreTest.threadUpdatedPatchesMonotonicReplyMetaButNeverOverwritesUnread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedActivityStoreRealtimeUnreadTest {

    @Test
    fun replyPatchMovesContentButNotUnreadAndHonoursTheSeqFrontier() = runTest {
        val row = threadItem("thread-1", unreadCount = 1).copy(replyCount = 2, latestActivitySeq = "20")
        val other = activityItem("channel-2")
        val store = realtimeStore(snapshot(listOf(other, row), totalUnreadCount = 1))

        // An out-of-order frame (older seq) and a frame without a seq are ignored.
        listOf(19L, null).forEach { seq ->
            store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(MessageNew(message("old-$seq", channelId = "thread-1", seq = seq))))
            assertEquals(listOf(other.key, row.key), store.state.value.items.map { it.key }, "seq=$seq")
        }

        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(MessageNew(message("reply-3", channelId = "thread-1", seq = 21L))))
        val patched = store.state.value.items.first()
        assertEquals(row.key, patched.key, "a newer reply moves the row to the top")
        assertEquals(MessageId("reply-3"), patched.latestActivityMessageId)
        assertEquals("21", patched.latestActivitySeq)
        assertEquals(3, patched.replyCount)
        assertEquals(1 to MessageId("first-thread-1"), patched.unreadCount to patched.firstUnreadMessageId, "no +1 per reply")
        assertEquals(1, store.state.value.totalUnreadCount)
    }

    @Test
    fun legacyRowsWithoutASeqStillPatchByMessageIdWithoutTouchingUnread() = runTest {
        val read = threadItem("thread-1").copy(replyCount = 1)
        val store = realtimeStore(snapshot(listOf(read)))

        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(MessageNew(message("reply-2", channelId = "thread-1"))))

        val patched = store.state.value.items.single()
        assertEquals(MessageId("reply-2"), patched.latestActivityMessageId)
        assertEquals(0 to null, patched.unreadCount to patched.firstUnreadMessageId)
        assertEquals(0, store.state.value.totalUnreadCount)
    }

    @Test
    fun inboxAggregatesAreTheServersEvenWithUnfollowedRowsOnThePage() {
        // Shapes per channelService.ts InboxItem @ 1bb70cbeb; the server reports
        // unfollowed rows with zero attention and their own aggregates.
        val body =
            """
            {"items":[
              {"kind":"thread","threadChannelId":"t-1","parentMessageId":"p-1","parentChannelId":"c-1",
               "parentChannelName":"general","parentChannelType":"channel","latestActivityMessageId":"r-1",
               "latestActivitySeq":"7","doneFrontierSeq":"7","firstUnreadMessageId":null,"firstMentionMessageId":null,
               "lastActivityAt":"2026-10-20T00:00:00Z","replyCount":1,"unreadCount":0,"hasMention":false,
               "isFollowing":false,"unfollowedAt":"2026-10-19T00:00:00Z"},
              {"kind":"channel","channelId":"c-2","channelName":"random","channelType":"channel","lastMessageId":"m-2",
               "latestActivitySeq":"5","doneFrontierSeq":"5","firstUnreadMessageId":"m-2","firstMentionMessageId":"m-2",
               "lastMessageAt":"2026-10-20T00:00:00Z","lastMessagePreview":"x","lastMessageSenderType":"user",
               "lastMessageSenderId":"u-2","unreadCount":3,"hasMention":true}
            ],"hasMore":true,"totalCount":40,"totalUnreadCount":12,"activeUnreadCount":9}
            """.trimIndent()

        val parsed = parseSharedInboxSnapshot(body) ?: error("inbox payload did not parse")

        assertEquals(Triple(40, 12, 9), Triple(parsed.totalCount, parsed.totalUnreadCount, parsed.activeUnreadCount))
        assertEquals(listOf("2026-10-19T00:00:00Z", null), parsed.items.map { it.unfollowedAt })
        assertEquals(listOf(null, MessageId("m-2")), parsed.items.map { it.firstMentionMessageId })
        val legacy = parseSharedInboxSnapshot("""{"items":[],"hasMore":false}""") ?: error("legacy payload did not parse")
        assertEquals(Triple(0, 0, null), Triple(legacy.totalCount, legacy.totalUnreadCount, legacy.activeUnreadCount))
    }

    private fun kotlinx.coroutines.test.TestScope.realtimeStore(initial: InboxSnapshot): SharedActivityStore {
        val store =
            activityStore(
                backgroundScope,
                FakeInboxRepository(initialSnapshots = listOf(initial)),
                nowMillis = { testScheduler.currentTime }
            )
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        return store
    }
}
