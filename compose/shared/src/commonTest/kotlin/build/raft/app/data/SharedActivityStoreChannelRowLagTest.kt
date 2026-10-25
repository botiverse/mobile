package build.raft.app.data

import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * #triage 84d44d75: `GET /channels/inbox` is served from a warehouse view that lags the write by
 * ~1-2 s, so the read a channel `message:new` triggers is often one message behind. The event
 * patches the existing channel row (preview / sender / time, seq-guarded), the store re-reads at
 * most twice (1.5 s, then 3 s) until the inbox reaches the event's seq, and an overloaded inbox
 * is left to Retry-After.
 *
 * Real [RealInboxRepository] under virtual time; the throttle window is the production one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedActivityStoreChannelRowLagTest {

    @Test
    fun eventArrivesFirstReReadIsStaleRowStillShowsTheNewMessage() = runTest {
        val network =
            ScriptedInboxNetwork(
                this,
                listOf(
                    inbox(row(seq = 41, preview = "hi", unread = 3)),
                    inbox(row(seq = 41, preview = "hi", unread = 3)),
                    inbox(row(seq = 41, preview = "hi", unread = 3)),
                    inbox(row(seq = 42, preview = "new (server)", unread = 4))
                )
            )
        val store = loadedStore(network)
        val eventAt = testScheduler.currentTime

        store.dispatch(newMessage(seq = 42))
        runCurrent()

        assertEquals(listOf(eventAt), network.requestTimes.drop(1), "the event's re-read went out at once")
        val afterStaleRead = store.state.value.items.single()
        assertEquals("new", afterStaleRead.lastMessagePreview, "the stale answer does not regress the patched row")
        assertEquals(MessageId("m-42"), afterStaleRead.lastMessageId)
        assertEquals("42", afterStaleRead.latestActivitySeq)
        assertEquals(3, afterStaleRead.unreadCount, "counts stay the server's")

        advanceTimeBy(1_500)
        runCurrent()
        assertEquals("new", store.state.value.items.single().lastMessagePreview)
        advanceTimeBy(3_000)
        runCurrent()

        assertEquals(listOf(eventAt, eventAt + 1_500, eventAt + 4_500), network.requestTimes.drop(1))
        val caughtUp = store.state.value.items.single()
        assertEquals("new (server)", caughtUp.lastMessagePreview, "once the server reaches the seq, its row wins")
        assertEquals(4, caughtUp.unreadCount)
        assertEquals(4, store.state.value.totalUnreadCount)
    }

    @Test
    fun reReadsBehindTheEventStopAfterTwoRetries() = runTest {
        val network = ScriptedInboxNetwork(this, listOf(inbox(row(seq = 41, preview = "hi", unread = 3))))
        val store = loadedStore(network)
        val eventAt = testScheduler.currentTime

        store.dispatch(newMessage(seq = 42))
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(
            listOf(eventAt, eventAt + 1_500, eventAt + 4_500),
            network.requestTimes.drop(1),
            "the event's read plus at most two retries"
        )
        assertEquals("new", store.state.value.items.single().lastMessagePreview, "the patched row is kept")
    }

    @Test
    fun manyEventsCoalesceIntoOnePendingReReadTargetingTheHighestSeq() = runTest {
        val network =
            ScriptedInboxNetwork(
                this,
                listOf(
                    inbox(row(seq = 41, preview = "hi", unread = 3)),
                    inbox(row(seq = 45, preview = "m45", unread = 3)),
                    inbox(row(seq = 46, preview = "m46", unread = 3))
                )
            )
        val store = loadedStore(network)
        val eventAt = testScheduler.currentTime

        (42L..46L).forEach { seq -> store.dispatch(newMessage(seq = seq)) }
        runCurrent()
        assertEquals("m-46", store.state.value.items.single().lastMessageId?.value)
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(
            listOf(eventAt, eventAt + 1_500),
            network.requestTimes.drop(1),
            "five events make one read; one retry because 45 is behind 46, none once 46 answers"
        )
        assertEquals("m46", store.state.value.items.single().lastMessagePreview)
    }

    @Test
    fun anOverloadedInboxFollowsRetryAfterInsteadOfTheCatchUpRetries() = runTest {
        val recoveryRetryAfters = mutableListOf<Long?>()
        val network =
            ScriptedInboxNetwork(
                this,
                listOf(
                    inbox(row(seq = 41, preview = "hi", unread = 3)),
                    rwOverloadedInboxResponse(),
                    rwOverloadedInboxResponse(),
                    inbox(row(seq = 42, preview = "new (server)", unread = 4))
                )
            )
        val store =
            loadedStore(network) { _, retryAfter ->
                recoveryRetryAfters += retryAfter
                retryAfter ?: 15_000L
            }
        val eventAt = testScheduler.currentTime

        store.dispatch(newMessage(seq = 42))
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        // The inbox's own 503 retry after `Retry-After: 2`, then the recovery read after Retry-After.
        assertEquals(listOf(eventAt, eventAt + 2_000, eventAt + 4_000), network.requestTimes.drop(1))
        assertEquals(listOf<Long?>(2_000L), recoveryRetryAfters)
        val row = store.state.value.items.single()
        assertEquals("new (server)", row.lastMessagePreview)
        assertFalse(store.state.value.stale)
    }

    @Test
    fun patchMovesOnlyMessageFieldsOnANewerSeqAndNeverCreatesARow() = runTest {
        val network = ScriptedInboxNetwork(this, listOf(inbox(row(seq = 41, preview = "hi", unread = 3))))
        val store = loadedStore(network)

        store.dispatch(newMessage(seq = 40, channelId = "general", id = "m-old"))
        assertEquals("hi", store.state.value.items.single().lastMessagePreview, "an older seq never patches")

        store.dispatch(newMessage(seq = 50, channelId = "unknown-channel", id = "m-unknown"))
        assertEquals(listOf("channel:general"), store.state.value.items.map(InboxItem::key), "no row for an unknown channel")

        store.dispatch(newMessage(seq = 42))
        val patched = store.state.value.items.single()
        assertEquals("new", patched.lastMessagePreview)
        assertEquals("User user-2", patched.lastMessageSenderName)
        assertEquals("2026-10-25T10:00:00Z", patched.lastMessageAt)
        assertEquals(3, patched.unreadCount, "the patch never moves unread")
        assertEquals(MessageId("first-general"), patched.firstUnreadMessageId)
        assertFalse(patched.hasMention)
        assertEquals(3, store.state.value.totalUnreadCount)
    }

    private fun TestScope.loadedStore(
        network: ScriptedInboxNetwork,
        staleRecoveryDelayMillis: (attempt: Int, retryAfterMillis: Long?) -> Long = { _, _ -> 15_000L }
    ): SharedActivityStore {
        val session = InMemoryRaftAuthSessionStore(sampleSession())
        val store =
            activityStore(
                scope = backgroundScope,
                inboxRepository = RealInboxRepository(session, network),
                authStore = session,
                realtimeBackgroundRefreshIntervalMillis = ActivityRealtimeBackgroundRefreshIntervalMillis,
                staleRecoveryDelayMillis = staleRecoveryDelayMillis
            )
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        check(store.state.value.items.map(InboxItem::key) == listOf("channel:general")) {
            "initial load did not settle, state=${store.state.value}"
        }
        // Past the realtime throttle window, so the event's read goes out at once.
        advanceTimeBy(10_000)
        runCurrent()
        return store
    }

    private fun newMessage(seq: Long, channelId: String = "general", id: String = "m-$seq") =
        SharedActivityIntent.ApplyRealtimeEvent(
            MessageNew(
                message(
                    id = id,
                    channelId = channelId,
                    body = if (id == "m-42") "new" else "m$seq",
                    timestamp = "2026-10-25T10:00:00Z",
                    seq = seq
                )
            )
        )

    private fun row(seq: Long, preview: String, unread: Int): String =
        """
        {"kind":"channel","channelId":"general","channelName":"general","channelType":"channel",
         "lastMessageId":"m-$seq","latestActivitySeq":"$seq","doneFrontierSeq":"$seq",
         "firstUnreadMessageId":"first-general","firstMentionMessageId":null,
         "lastMessageAt":"2026-10-25T09:50:00Z","lastMessagePreview":"$preview",
         "lastMessageSenderType":"user","lastMessageSenderId":"u-9","lastMessageSenderName":"Server",
         "unreadCount":$unread,"hasMention":false}
        """.trimIndent()

    private fun inbox(row: String): HomeNetworkResponse {
        val unread = Regex("\"unreadCount\":(\\d+)").find(row)!!.groupValues[1]
        return HomeNetworkResponse(
            200,
            """{"items":[$row],"hasMore":false,"totalCount":1,"totalUnreadCount":$unread}"""
        )
    }
}
