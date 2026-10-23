package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.SyncResumeResponse
import build.raft.app.model.ThreadId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Exact catch-up after reconnect (botiverse/slock #10982) can deliver the same message twice: live as
 * `message:new`, then again in a `sync:resume` batch (as a page row or a late row). Each unread counter
 * counts it once.
 */
class RealtimeUnreadDuplicateDeliveryTest {
    private val me =
        MemberProfile(
            id = MemberId("user-1"),
            displayName = "Artin",
            handle = "artin@example.com",
            kind = MemberKind.Human,
            presence = Presence.Active,
        )
    private val cindy = me.copy(id = MemberId("member-2"), displayName = "Cindy", handle = "cindy@example.com")

    private fun message(seq: Long, channelId: String = "channel-1") =
        Message(
            id = MessageId("message-$seq"),
            threadId = ThreadId(channelId),
            channelId = ChannelId(channelId),
            seq = seq,
            author = cindy,
            timestampLabel = "12:$seq",
            body = "body $seq",
        )

    private fun resume(vararg seqs: Long) =
        SyncResumeResponse(messages = seqs.map { message(it) }, currentSeq = seqs.max(), hasMore = false, horizon = "800")

    private fun home(serverUnreadCounts: Map<String, Int> = emptyMap()) =
        HomeSnapshot(
            server = ServerSummary(ServerId("server-1"), "One"),
            currentMember = me,
            channels =
                listOf(
                    HomeChannelRow(
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        name = "general",
                        unreadCountKnown = true,
                        lastMessageId = MessageId("message-10"),
                        lastMessageSeq = 10L,
                        lastMessagePreview = "body 10",
                    )
                ),
            directMessages = emptyList(),
            unreadTotal = 0,
            serverUnreadCounts = serverUnreadCounts,
        )

    private fun HomeSnapshot.apply(messages: List<Message>) =
        withRealtimeMessages(HomeRealtimeMessagePatch(messages, incrementUnread = true), activeConversationId = null)

    @Test
    fun homeRowCountsAMessageOnceAndALateOneNeverRewindsItsPreview() {
        val live = home().apply(listOf(message(11)))
        assertEquals(1, live.channels.single().unreadCount)

        // The resume batch repeats 11 (and 11 again: deduped by id within the batch) and brings 12.
        val caughtUp = live.apply(resume(11, 11, 12).messages)
        val row = caughtUp.channels.single()
        assertEquals(2, row.unreadCount)
        assertEquals(2, caughtUp.unreadTotal)
        assertEquals(12L, row.lastMessageSeq)

        // A late message (seq below the row's latest) is not new for the row and keeps the preview.
        val late = caughtUp.apply(listOf(message(9)))
        assertEquals(2, late.channels.single().unreadCount)
        assertEquals("body 12", late.channels.single().lastMessagePreview)
        assertEquals(12L, late.channels.single().lastMessageSeq)
        // ...so the next repeat of 12 still counts nothing.
        assertEquals(2, late.apply(listOf(message(12))).channels.single().unreadCount)
    }

    @Test
    fun backgroundServerBadgeCountsAMessageOnce() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val session = authSessionFixture()
        val store =
            SharedHomeStore(
                authStore = InMemoryRaftAuthSessionStore(session),
                dataSource =
                    SharedHomeDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(session),
                        api = RootEmptyHomeApi,
                        parseHome = { _, _ -> DataResult.Content(home(mapOf("server-1" to 0, "server-2" to 3))) },
                    ),
                scope = scope,
            )
        store.accept(SharedHomeIntent.Start)
        check(store.state.value.content is SharedHomeContentState.Content) { "home not loaded: ${store.state.value.content}" }

        store.accept(SharedHomeIntent.ApplyBackgroundServerUnreadEvent("server-2", MessageNew(message(101, "channel-b"))))
        assertEquals(4, store.state.value.serverUnreadCounts["server-2"])

        store.accept(
            SharedHomeIntent.ApplyBackgroundServerUnreadEvent(
                "server-2",
                SyncResumeResponse(
                    messages = listOf(message(101, "channel-b"), message(102, "channel-b")),
                    currentSeq = 102L,
                    hasMore = false,
                ),
            )
        )
        assertEquals(5, store.state.value.serverUnreadCounts["server-2"])
        scope.cancel()
    }

    @Test
    fun activitySummaryCountsAMessageOnce() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store =
            activityStore(
                scope,
                FakeInboxRepository(
                    initialSnapshots = listOf(snapshot(listOf(activityItem("channel-1", unreadCount = 1)), totalUnreadCount = 1))
                ),
            )
        store.dispatch(SharedActivityIntent.LoadInitial)

        store.dispatch(SharedActivityIntent.ApplyRealtimeSummaryEvent(MessageNew(message(11)), activeConversationId = null))
        assertEquals(2, store.state.value.totalUnreadCount)

        store.dispatch(SharedActivityIntent.ApplyRealtimeSummaryEvent(resume(11, 12), activeConversationId = null))
        assertEquals(3, store.state.value.totalUnreadCount)
        scope.cancel()
    }
}
