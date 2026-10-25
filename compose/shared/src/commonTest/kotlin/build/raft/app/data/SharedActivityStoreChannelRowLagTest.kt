package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MessageConversationContext
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
 * #triage 84d44d75 (web parity: slock #11208): `GET /channels/inbox` is served from a warehouse
 * view that lags the write by ~1-2 s, so the read a channel `message:new` triggers is often one
 * message behind. The event patches the existing channel/DM row (preview / sender / time,
 * seq-guarded); while a clean answer still serves that row behind the event, the list is re-read
 * after 1.5 s and then 3 s through the realtime throttle, then the catch-up stops. A stale or
 * overloaded answer ends it and the Retry-After recovery takes over.
 *
 * Real [RealInboxRepository] under virtual time; the throttle window is the production one
 * (2.5 s from the last reset read), so a 1.5 s catch-up read lands on the window's edge.
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

        advanceTimeBy(2_500)
        runCurrent()
        assertEquals("new", store.state.value.items.single().lastMessagePreview)
        advanceTimeBy(3_000)
        runCurrent()

        assertEquals(listOf(eventAt, eventAt + 2_500, eventAt + 5_500), network.requestTimes.drop(1))
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
            listOf(eventAt, eventAt + 2_500, eventAt + 5_500),
            network.requestTimes.drop(1),
            "the event's read plus at most two retries, each through the throttle"
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
            listOf(eventAt, eventAt + 2_500),
            network.requestTimes.drop(1),
            "five events make one read; one retry because 45 is behind 46, none once 46 answers"
        )
        assertEquals("m46", store.state.value.items.single().lastMessagePreview)
    }

    @Test
    fun anOverloadedInboxEndsTheCatchUpAndFollowsRetryAfter() = runTest {
        val recoveryRetryAfters = mutableListOf<Long?>()
        val network =
            ScriptedInboxNetwork(
                this,
                listOf(
                    inbox(row(seq = 41, preview = "hi", unread = 3)),
                    rwOverloadedInboxResponse(),
                    rwOverloadedInboxResponse(),
                    inbox(row(seq = 41, preview = "hi", unread = 3))
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

        // The inbox's own 503 retry after `Retry-After: 2`, then one recovery read after Retry-After.
        // That answer is still behind, but the catch-up already ended: no 1.5 s / 3 s reads.
        assertEquals(listOf(eventAt, eventAt + 2_000, eventAt + 4_000), network.requestTimes.drop(1))
        assertEquals(listOf<Long?>(2_000L), recoveryRetryAfters)
        assertEquals("new", store.state.value.items.single().lastMessagePreview)
        assertFalse(store.state.value.stale)
    }

    @Test
    fun aRowMissingFromTheAnswerIsNotBehind() = runTest {
        val network =
            ScriptedInboxNetwork(
                this,
                listOf(
                    inbox(row(seq = 41, preview = "hi", unread = 3)),
                    inbox(row(seq = 30, preview = "elsewhere", unread = 0, channelId = "random"))
                )
            )
        val store = loadedStore(network)
        val eventAt = testScheduler.currentTime

        store.dispatch(newMessage(seq = 42))
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(listOf(eventAt), network.requestTimes.drop(1), "no catch-up read for a row the answer left out")
        assertEquals(listOf("channel:random"), store.state.value.items.map(InboxItem::key), "visibility is the server's")
    }

    @Test
    fun aPatchedRowStaysOnTopOverAnOlderServedOrder() = runTest {
        val network =
            ScriptedInboxNetwork(
                this,
                listOf(
                    inbox(
                        row(seq = 40, preview = "r", unread = 0, channelId = "random"),
                        row(seq = 39, preview = "hi", unread = 3)
                    )
                )
            )
        val store = loadedStore(network, initialKeys = listOf("channel:random", "channel:general"))

        store.dispatch(newMessage(seq = 42))
        assertEquals(listOf("channel:general", "channel:random"), store.state.value.items.map(InboxItem::key))
        runCurrent()

        assertEquals(2, network.requestTimes.size, "the stale re-read answered")
        assertEquals(
            listOf("channel:general", "channel:random"),
            store.state.value.items.map(InboxItem::key),
            "the older served order does not move the patched row down"
        )
        assertEquals("new", store.state.value.items.first().lastMessagePreview)
    }

    @Test
    fun aMutedChannelMessageNeverPatchesTheRow() = runTest {
        val network = ScriptedInboxNetwork(this, listOf(inbox(row(seq = 41, preview = "hi", unread = 3))))
        val store = loadedStore(network)
        val eventAt = testScheduler.currentTime

        // The shell's verdict for a message at or past the channel's Activity mute, a mention
        // included (SharedHomeShellStore.allowsLocalActivityPatch): the served row stops at the mute.
        store.dispatch(newMessage(seq = 42).copy(allowLocalPatch = false))
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals("hi", store.state.value.items.single().lastMessagePreview)
        assertEquals(listOf(eventAt), network.requestTimes.drop(1), "the reconcile read, and no catch-up for a muted message")
    }

    @Test
    fun patchMovesOnlyMessageFieldsOnANewerSeqForChannelAndDmRowsAndNeverCreatesARow() = runTest {
        val network =
            ScriptedInboxNetwork(
                this,
                listOf(
                    inbox(
                        row(seq = 41, preview = "hi", unread = 3),
                        row(seq = 20, preview = "dm hi", unread = 1, channelId = "dm-1", kind = "dm")
                    )
                )
            )
        val store = loadedStore(network, initialKeys = listOf("channel:general", "dm:dm-1"))

        store.dispatch(newMessage(seq = 40, id = "m-old"))
        assertEquals("hi", store.state.value.items.first { it.key == "channel:general" }.lastMessagePreview, "an older seq never patches")

        store.dispatch(newMessage(seq = 50, channelId = "unknown-channel", id = "m-unknown"))
        assertEquals(listOf("channel:general", "dm:dm-1"), store.state.value.items.map(InboxItem::key), "no row for an unknown channel")

        store.dispatch(newMessage(seq = 42))
        val patched = store.state.value.items.first { it.key == "channel:general" }
        assertEquals("new", patched.lastMessagePreview)
        assertEquals("User user-2", patched.lastMessageSenderName)
        assertEquals("2026-10-25T10:00:00Z", patched.lastMessageAt)
        assertEquals(3, patched.unreadCount, "the patch never moves unread")
        assertEquals(MessageId("first-general"), patched.firstUnreadMessageId)
        assertFalse(patched.hasMention)
        assertEquals("41", patched.doneFrontierSeq, "the Done frontier stays the served one")

        store.dispatch(newMessage(seq = 43, channelId = "dm-1", id = "m-dm"))
        val dm = store.state.value.items.first()
        assertEquals("dm:dm-1", dm.key)
        assertEquals("m43", dm.lastMessagePreview)
        assertEquals(1, dm.unreadCount)
        assertEquals(4, store.state.value.totalUnreadCount)
    }

    /** Review of #3692: a later thread reply must not take the target away from the channel. */
    @Test
    fun aLaterThreadReplyDoesNotEndTheChannelCatchUp() = runTest {
        val network = ScriptedInboxNetwork(this, listOf(inbox(row(seq = 41, preview = "hi", unread = 3))))
        val store = loadedStore(network)
        val eventAt = testScheduler.currentTime

        store.dispatch(newMessage(seq = 42))
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(
                    message(
                        id = "reply-43",
                        channelId = "thread-x",
                        seq = 43,
                        conversationContext =
                            MessageConversationContext(
                                channelKind = ConversationKind.Thread,
                                parentMessageId = MessageId("m-42"),
                                parentChannelId = ChannelId("general"),
                                parentChannelKind = ConversationKind.Channel
                            )
                    )
                )
            )
        )
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(
            listOf(eventAt, eventAt + 2_500, eventAt + 5_500),
            network.requestTimes.drop(1),
            "#general is still re-read while its row is behind 42"
        )
    }

    @Test
    fun thePatchStopsHoldingAfterTheStaleListBound() = runTest {
        val network = ScriptedInboxNetwork(this, listOf(inbox(row(seq = 41, preview = "hi", unread = 3))))
        val store = loadedStore(network)
        val eventAt = testScheduler.currentTime

        store.dispatch(newMessage(seq = 42))
        runCurrent()
        advanceTimeBy(30_000)
        store.dispatch(SharedActivityIntent.Refresh)
        runCurrent()
        assertEquals("new", store.state.value.items.single().lastMessagePreview, "held inside the bound")

        advanceTimeBy(eventAt + ActivityLocalMutationHoldMillis + 1 - testScheduler.currentTime)
        store.dispatch(SharedActivityIntent.Refresh)
        runCurrent()

        val row = store.state.value.items.single()
        assertEquals("hi", row.lastMessagePreview, "past the bound the served row wins")
        assertEquals("41", row.latestActivitySeq)
    }

    @Test
    fun aServerSwitchCancelsThePendingCatchUpRead() = runTest {
        fun twoServers(selected: String) =
            sampleSession(serverId = selected).copy(
                servers = listOf(StoredAuthServer(id = "server-1", name = "One"), StoredAuthServer(id = "server-2", name = "Two"))
            )
        val auth = InMemoryRaftAuthSessionStore(twoServers("server-1"))
        val network = ScriptedInboxNetwork(this, listOf(inbox(row(seq = 41, preview = "hi", unread = 3))))
        val store = loadedStore(network, authStore = auth)
        val eventAt = testScheduler.currentTime

        store.dispatch(newMessage(seq = 42))
        runCurrent()
        assertEquals(listOf(eventAt), network.requestTimes.drop(1), "behind: a catch-up read is now pending")

        auth.saveSession(twoServers("server-2"))
        store.dispatch(SharedActivityIntent.ServerSelected("server-2"))
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals("server-2", store.state.value.ownerServerId)
        assertEquals(listOf(eventAt, eventAt), network.requestTimes.drop(1), "only the new server's load, no catch-up read")
    }

    private fun TestScope.loadedStore(
        network: ScriptedInboxNetwork,
        initialKeys: List<String> = listOf("channel:general"),
        authStore: InMemoryRaftAuthSessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
        staleRecoveryDelayMillis: (attempt: Int, retryAfterMillis: Long?) -> Long = { _, _ -> 15_000L }
    ): SharedActivityStore {
        val session = authStore
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
        check(store.state.value.items.map(InboxItem::key) == initialKeys) {
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

    private fun row(
        seq: Long,
        preview: String,
        unread: Int,
        channelId: String = "general",
        kind: String = "channel"
    ): Pair<String, Int> =
        """
        {"kind":"$kind","channelId":"$channelId","channelName":"$channelId","channelType":"$kind",
         "lastMessageId":"m-$seq","latestActivitySeq":"$seq","doneFrontierSeq":"$seq",
         "firstUnreadMessageId":"first-$channelId","firstMentionMessageId":null,
         "lastMessageAt":"2026-10-25T09:50:00Z","lastMessagePreview":"$preview",
         "lastMessageSenderType":"user","lastMessageSenderId":"u-9","lastMessageSenderName":"Server",
         "unreadCount":$unread,"hasMention":false}
        """.trimIndent() to unread

    private fun inbox(vararg rows: Pair<String, Int>): HomeNetworkResponse =
        HomeNetworkResponse(
            200,
            """{"items":[${rows.joinToString(",") { it.first }}],"hasMore":false,""" +
                """"totalCount":${rows.size},"totalUnreadCount":${rows.sumOf { it.second }}}"""
        )
}
