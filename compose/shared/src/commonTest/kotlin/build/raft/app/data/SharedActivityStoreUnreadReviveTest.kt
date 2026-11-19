package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.ThreadUpdated
import build.raft.app.network.sync.ReplyPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #triage:#244 (artin; web slock #15568 activityUnreadRevive.ts). Unread keeps a row read this
 * visit in place, read. A new message that makes that conversation unread again brings the row
 * back at once, as All moves its row: unread, on top, with the new preview, once, and back in the
 * filter's count. If the re-read comes back before the server has the message and leaves the row
 * out, the list re-reads (catch-up) and keeps the row shown meanwhile.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedActivityStoreUnreadReviveTest {
    private val other = activityItem("channel-o", unreadCount = 1).copy(latestActivitySeq = "5", readStateLatestActivitySeq = "5")

    /** The kept row's kind, and the live frame(s) that bring the new message. */
    private enum class Kind(val key: String) {
        Channel("channel:channel-r"),
        DirectMessage("dm:channel-r"),
        Thread("thread:thread-r"),
        /** A thread reply delivered twice, as `message:new` then `thread:updated`: it counts once. */
        ThreadPair("thread:thread-r");

        val stream: String get() = if (this == Thread || this == ThreadPair) "thread-r" else "channel-r"

        fun row(unreadCount: Int, seq: String): InboxItem {
            val base =
                when (this) {
                    Channel -> activityItem("channel-r", unreadCount)
                    DirectMessage -> activityItem("channel-r", unreadCount).copy(key = key, kind = InboxItemKind.DirectMessage)
                    Thread, ThreadPair -> threadItem("thread-r", unreadCount = unreadCount).copy(replyCount = 1)
                }
            return base.copy(latestActivitySeq = seq, readStateLatestActivitySeq = seq)
        }
    }

    @Test
    fun aNewMessageBringsTheKeptReadRowBackUnreadOnTopOnce() = runTest {
        Kind.entries.forEach { kind ->
            val inbox = FakeInboxRepository(listOf(snapshot(listOf(other, kind.row(1, "10")))))
            val store = storeWithKeptRead(kind, inbox)
            val before = store.state.value
            assertEquals(listOf(other.key to 1, kind.key to 0), before.rows(), "$kind: read, kept in place")

            liveMessage(kind, seq = 11L).forEach(store::dispatch)
            runCurrent()

            val state = store.state.value
            assertEquals(listOf(kind.key to 1, other.key to 1), state.rows(), "$kind: unread, on top, once")
            assertEquals("new reply", state.items.first().preview(), "$kind: the new preview")
            assertEquals(before.totalCount + 1 to before.totalUnreadCount + 1, state.totalCount to state.totalUnreadCount, "$kind")
            assertEquals(1, inbox.initialLoadCount, "$kind: brought back before any re-read")
        }
    }

    /** What leaves the kept row as it was: the message does not make it unread, or is not newer. */
    private enum class Ignored(val seq: Long, val authorId: String = "user-2", val allowLocalPatch: Boolean = true, val open: Boolean = false) {
        OwnMessage(seq = 11L, authorId = "user-1"),
        OlderMessage(seq = 9L),
        SameSeqAsTheKeptCopy(seq = 10L),
        InTheConversationOpenOnScreen(seq = 11L, open = true),
        PastTheActivityMute(seq = 11L, allowLocalPatch = false),
    }

    @Test
    fun aMessageThatDoesNotMakeItUnreadLeavesTheKeptRowAsItWas() = runTest {
        Ignored.entries.forEach { case ->
            listOf(Kind.Channel, Kind.Thread).forEach { kind ->
                val name = "$case/$kind"
                val store = storeWithKeptRead(kind, FakeInboxRepository(listOf(snapshot(listOf(other, kind.row(1, "10"))))))
                val before = store.state.value

                liveMessage(kind, seq = case.seq, authorId = case.authorId, allowLocalPatch = case.allowLocalPatch, open = case.open)
                    .forEach(store::dispatch)
                runCurrent()

                val state = store.state.value
                assertEquals(listOf(other.key to 1, kind.key to 0), state.rows(), name)
                assertEquals(before.items.last().preview(), state.items.last().preview(), "$name: old preview")
                assertEquals(before.totalCount to before.totalUnreadCount, state.totalCount to state.totalUnreadCount, name)
            }
        }
    }

    @Test
    fun aReReadWithoutTheRowIsRetriedAndTheRowStaysShown() = runTest {
        listOf(Kind.Channel, Kind.Thread).forEach { kind ->
            val caughtUp = kind.row(1, "11").copy(latestActivityMessageId = MessageId("reply-11"))
            val inbox =
                FakeInboxRepository(
                    initialSnapshots =
                        listOf(
                            snapshot(listOf(other, kind.row(1, "10"))),
                            // The server does not have the message yet: the row read here is not listed.
                            snapshot(listOf(other)),
                            snapshot(listOf(caughtUp, other))
                        )
                )
            val store = storeWithKeptRead(kind, inbox)

            liveMessage(kind, seq = 11L).forEach(store::dispatch)
            runCurrent()
            assertEquals(listOf(kind.key to 1, other.key to 1), store.state.value.rows(), "$kind: back at once")

            // The realtime re-read (throttled to the 2.5 s window) comes back without the row.
            advanceTimeBy(2_501L)
            runCurrent()
            assertEquals(2, inbox.initialLoadCount, "$kind: one re-read")
            // Still shown on top with its new preview: a channel row as its kept copy, read (web
            // keepRevivedRowShown); a thread row as the realtime patch left it, as All keeps a
            // patched thread row the answer lags (reconcileRealtimeHighWater missingCurrent).
            val shownUnread = if (kind == Kind.Thread) 1 else 0
            assertEquals(listOf(kind.key to shownUnread, other.key to 1), store.state.value.rows(), "$kind: still shown")
            assertEquals("new reply", store.state.value.items.first().preview(), "$kind: with its new preview")

            // Behind: the catch-up re-reads, and that answer has it.
            advanceTimeBy(ActivityRealtimeCatchUpRetryDelaysMillis.first() + 2_501L)
            runCurrent()
            assertEquals(3, inbox.initialLoadCount, "$kind: the catch-up re-read")
            assertEquals(listOf(kind.key to 1, other.key to 1), store.state.value.rows(), "$kind: unread from the server")

            // Caught up: no more reads.
            advanceTimeBy(20_000L)
            runCurrent()
            assertEquals(3, inbox.initialLoadCount, "$kind: the catch-up ends")
        }
    }

    /** Unread, with [kind]'s row read here (opened): kept in place, read, out of the counts. */
    private fun TestScope.storeWithKeptRead(kind: Kind, inbox: FakeInboxRepository): SharedActivityStore {
        val store =
            activityStore(
                backgroundScope,
                inbox,
                realtimeBackgroundRefreshIntervalMillis = 2_500L,
                nowMillis = { testScheduler.currentTime }
            )
        store.dispatch(SharedActivityIntent.SetFilter(InboxFilter.Unread))
        runCurrent()
        val row = store.state.value.items.first { it.key == kind.key }
        store.dispatch(SharedActivityIntent.MarkRead(row))
        store.dispatch(SharedActivityIntent.RowOpened(row))
        runCurrent()
        assertEquals(setOf(kind.key), store.state.value.retainedReadRowKeys, "$kind kept")
        return store
    }

    private fun liveMessage(
        kind: Kind,
        seq: Long,
        authorId: String = "user-2",
        allowLocalPatch: Boolean = true,
        open: Boolean = false
    ): List<SharedActivityIntent> {
        fun intent(event: build.raft.app.model.RealtimeEvent) =
            SharedActivityIntent.ApplyRealtimeEvent(
                event,
                allowLocalPatch = allowLocalPatch,
                activeConversationId = if (open) kind.stream else null
            )
        val messageNew = intent(MessageNew(message("reply-$seq", channelId = kind.stream, authorId = authorId, body = "new reply", seq = seq)))
        val threadUpdated =
            intent(
                ThreadUpdated(
                    parentMessageId = MessageId("parent-message"),
                    threadChannelId = ChannelId(kind.stream),
                    replyCount = 2,
                    lastReplyAt = "2026-11-19T10:00:00Z",
                    participantIds = emptyList(),
                    latestReply =
                        ReplyPreview(
                            messageId = "reply-$seq",
                            seq = seq,
                            content = "new reply",
                            senderId = authorId,
                            senderType = "user",
                            senderName = authorId,
                            createdAt = "2026-11-19T10:00:00Z"
                        )
                )
            )
        return if (kind == Kind.ThreadPair) listOf(messageNew, threadUpdated) else listOf(messageNew)
    }

    private fun SharedActivityState.rows(): List<Pair<String, Int>> = items.map { it.key to it.unreadCount }

    private fun InboxItem.preview(): String? = if (kind == InboxItemKind.Thread) latestActivityPreview else lastMessagePreview
}
