package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MessageConversationContext
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.MessageUpdated
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
    fun anEditOfThePatchedMessageInsideTheHoldUpdatesThePreviewAndTheHoldKeepsIt() = runTest {
        val network =
            ScriptedInboxNetwork(
                this,
                listOf(
                    inbox(row(seq = 41, preview = "hi", unread = 3)),
                    inbox(row(seq = 41, preview = "hi", unread = 3)),
                    inbox(row(seq = 41, preview = "hi", unread = 3)),
                    inbox(row(seq = 42, preview = "edited (server)", unread = 4))
                )
            )
        val store = loadedStore(network)
        store.dispatch(newMessage(seq = 42))
        runCurrent()
        val patched = store.state.value.items.single()
        assertEquals("new", patched.lastMessagePreview)

        store.dispatch(updatedMessage(id = "m-42", seq = 42, body = "edited"))
        runCurrent()

        val edited = store.state.value.items.single()
        assertEquals("edited", edited.lastMessagePreview, "the shown row follows the edit")
        assertEquals(patched.copy(lastMessagePreview = "edited"), edited, "only the preview moves")
        assertEquals(3, store.state.value.totalUnreadCount)

        advanceTimeBy(2_500)
        runCurrent()
        assertEquals("edited", store.state.value.items.single().lastMessagePreview, "a stale answer keeps the edit")
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals("edited (server)", store.state.value.items.single().lastMessagePreview)
    }

    @Test
    fun aBlankUpdateOfThePatchedMessageKeepsThePreview() = runTest {
        // Without `deletedAt` (no retraction), a blank body on `message:updated` is a sparse
        // update (thread page merge: `body.ifBlank { body }`).
        val network = ScriptedInboxNetwork(this, listOf(inbox(row(seq = 41, preview = "hi", unread = 3))))
        val store = loadedStore(network)
        store.dispatch(newMessage(seq = 42))
        runCurrent()
        val patched = store.state.value.items.single()

        store.dispatch(updatedMessage(id = "m-42", seq = 42, body = ""))
        runCurrent()

        assertEquals(patched, store.state.value.items.single())
    }

    @Test
    fun aRetractionOfThePatchedMessageReplacesThePreviewWithThePlaceholder() = runTest {
        // RFC 077 PR 2b: a retraction is `message:updated` with `deletedAt` set and `content`
        // replaced by the server placeholder (web retractPreview). It is authoritative, also when
        // the body would be blank: the old text must leave the row.
        val placeholder = "This message was retracted by its sender."
        val network = ScriptedInboxNetwork(this, listOf(inbox(row(seq = 41, preview = "hi", unread = 3))))
        val store = loadedStore(network)
        store.dispatch(newMessage(seq = 42))
        runCurrent()
        val patched = store.state.value.items.single()
        assertEquals("new", patched.lastMessagePreview)

        store.dispatch(updatedMessage(id = "m-42", seq = 42, body = placeholder, deletedAt = "2026-10-25T10:05:00Z"))
        runCurrent()

        assertEquals(patched.copy(lastMessagePreview = placeholder), store.state.value.items.single())
        assertEquals(3, store.state.value.totalUnreadCount)

        store.dispatch(updatedMessage(id = "m-42", seq = 42, body = "", deletedAt = "2026-10-25T10:05:00Z"))
        runCurrent()
        assertEquals("", store.state.value.items.single().lastMessagePreview, "a retraction never keeps old text")
    }

    @Test
    fun aRetractionReplacesAThreadRowsLatestReplyAndParentPreviews() = runTest {
        // RFC 077 PR 2b, web inboxStore.retractInboxItemPreview: thread rows too. The latest reply
        // and the parent are each replaced when they are the retracted message; so is the
        // reply window that seeds the thread page. Another thread's row is left alone.
        val placeholder = "This message was retracted by its sender."
        val network = ScriptedInboxNetwork(this, listOf(inbox(threadRow("t-1", parent = "p-1", latest = "r-1"), threadRow("t-2", parent = "p-2", latest = "r-2"))))
        val store = loadedStore(network, initialKeys = listOf("thread:t-1", "thread:t-2"))
        fun row(key: String) = store.state.value.items.single { it.key == key }
        val other = row("thread:t-2")

        store.dispatch(updatedMessage(id = "r-1", seq = 8, body = placeholder, channelId = "t-1", deletedAt = "2026-10-25T10:05:00Z"))
        runCurrent()
        val reply = row("thread:t-1")
        assertEquals(placeholder, reply.latestActivityPreview)
        assertEquals("parent p-1", reply.parentMessagePreview, "the parent was not retracted")
        assertEquals(listOf(placeholder), reply.latestReplies.map { it.content })

        store.dispatch(updatedMessage(id = "p-1", seq = 3, body = placeholder, deletedAt = "2026-10-25T10:06:00Z"))
        runCurrent()
        val parent = row("thread:t-1")
        assertEquals(placeholder, parent.parentMessagePreview)
        assertEquals(reply.copy(parentMessagePreview = placeholder), parent, "nothing else on the row moves")
        assertEquals(other, row("thread:t-2"))
    }

    @Test
    fun anUpdateOfAnotherMessageLeavesTheRowsAlone() = runTest {
        val network =
            ScriptedInboxNetwork(
                this,
                listOf(
                    inbox(
                        row(seq = 41, preview = "hi", unread = 3),
                        row(seq = 30, preview = "dm", unread = 1, channelId = "dm-1", kind = "dm")
                    )
                )
            )
        val store = loadedStore(network, initialKeys = listOf("channel:general", "dm:dm-1"))
        store.dispatch(newMessage(seq = 42))
        runCurrent()
        val before = store.state.value.items

        store.dispatch(updatedMessage(id = "m-41", seq = 41, body = "older edited"))
        store.dispatch(updatedMessage(id = "m-99", seq = 99, body = "elsewhere", channelId = "dm-1"))
        runCurrent()

        assertEquals(before, store.state.value.items, "no row shows those messages: nothing moves")
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

    private fun updatedMessage(
        id: String,
        seq: Long,
        body: String,
        channelId: String = "general",
        deletedAt: String? = null
    ) =
        SharedActivityIntent.ApplyRealtimeEvent(
            MessageUpdated(
                message(id = id, channelId = channelId, body = body, timestamp = "2026-10-25T10:00:00Z", seq = seq)
                    .copy(deletedAt = deletedAt)
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

    private fun threadRow(threadChannelId: String, parent: String, latest: String): Pair<String, Int> =
        """
        {"kind":"thread","threadChannelId":"$threadChannelId","parentMessageId":"$parent","parentChannelId":"general",
         "parentChannelName":"general","parentChannelType":"channel","parentMessagePreview":"parent $parent",
         "latestActivityMessageId":"$latest","latestActivityPreview":"reply $latest",
         "latestActivitySeq":"7","doneFrontierSeq":"7","firstUnreadMessageId":null,"firstMentionMessageId":null,
         "lastActivityAt":"2026-10-25T09:50:00Z","replyCount":1,"unreadCount":0,"hasMention":false,
         "latestReplies":[{"messageId":"$latest","seq":7,"preview":"reply $latest","senderId":"u-9",
           "senderType":"user","senderName":"Server","createdAt":"2026-10-25T09:50:00Z"}]}
        """.trimIndent() to 0

    private fun inbox(vararg rows: Pair<String, Int>): HomeNetworkResponse =
        HomeNetworkResponse(
            200,
            """{"items":[${rows.joinToString(",") { it.first }}],"hasMore":false,""" +
                """"totalCount":${rows.size},"totalUnreadCount":${rows.sumOf { it.second }}}"""
        )
}
