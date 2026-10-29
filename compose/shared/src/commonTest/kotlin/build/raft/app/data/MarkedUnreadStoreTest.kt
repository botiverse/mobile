package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.PerReceiverReadStateChanged
import build.raft.app.model.PerReceiverReadStateScope
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Home and Activity follow the server's "marked unread" flag (botiverse/slock #11795 at 1442d689c7):
 * Mark as Unread's answer, the version-ordered `read_state:updated`, and a local read that clears
 * the dot at once. The `/unread` answers are the server test's (`channels.markedUnread.api.test.ts`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MarkedUnreadStoreTest {

    private val markedAnswer = ReadReceiptResult(unreadCount = 0, maxReadSeq = 12, readStateVersion = 9, markedUnread = true)
    private val refusedAnswer = ReadReceiptResult(unreadCount = 0, maxReadSeq = 0, readStateVersion = 0, markedUnread = false)
    private val olderServerAnswer = ReadReceiptResult(unreadCount = 0, maxReadSeq = 0, readStateVersion = 0)
    private val pendingAnswer = ReadReceiptResult(markUnreadPending = true)

    @Test
    fun homeMarkUnreadShowsTheMarkWithNoCountAndAReadClearsIt() = runTest {
        val readCalls = mutableListOf<ChannelId>()
        val store = homeStore(unreadAnswer = markedAnswer, readCalls = readCalls)
        val notices = collectNotices(store.readToggleNotices)

        store.accept(SharedHomeIntent.MarkUnread("dm-1"))
        runCurrent()

        assertEquals(setOf("dm-1"), store.snapshot().markedUnreadIds)
        assertEquals(0, store.snapshot().directMessages.single().unreadCount, "the optimistic count goes back")
        assertEquals(0, store.snapshot().unreadTotal, "a mark never adds to a count")
        assertEquals(emptyList(), notices)

        store.accept(SharedHomeIntent.MarkRead("dm-1"))
        runCurrent()

        assertEquals(listOf(ChannelId("dm-1")), readCalls, "a marked row with no count still sends its read")
        assertEquals(emptySet(), store.snapshot().markedUnreadIds, "the local read clears the dot at once")
    }

    @Test
    fun homeNothingToMarkKeepsTheRowReadAndSaysWhy() = runTest {
        listOf("refused" to refusedAnswer, "older server" to olderServerAnswer).forEach { (name, answer) ->
            val store = homeStore(unreadAnswer = answer)
            val notices = collectNotices(store.readToggleNotices)

            store.accept(SharedHomeIntent.MarkUnread("dm-1"))
            runCurrent()

            assertEquals(0, store.snapshot().directMessages.single().unreadCount, name)
            assertEquals(emptySet(), store.snapshot().markedUnreadIds, name)
            assertEquals(listOf(RaftReadToggleNotice.MarkUnreadNothing), notices, name)
        }
    }

    // #proj-mobile:#70 (contract cases R1-202-pending, R2-optimistic-badge): a 202 keeps the optimistic
    // state with no toast; the read-state event that follows corrects it.
    @Test
    fun homePendingKeepsTheOptimisticCountUntilTheReadStateEvent() = runTest {
        val store = homeStore(unreadAnswer = pendingAnswer)
        val notices = collectNotices(store.readToggleNotices)

        store.accept(SharedHomeIntent.MarkUnread("dm-1"))
        runCurrent()

        assertEquals(1, store.snapshot().directMessages.single().unreadCount, "the optimistic count stays")
        assertEquals(emptySet(), store.snapshot().markedUnreadIds)
        assertEquals(emptyList(), notices, "no toast for a pending answer")

        // Asserted before the summary reconcile the event schedules (this fake's summary has no mark).
        store.accept(SharedHomeIntent.ApplyRealtimeEvent("server-1", readState("dm-1", version = 9, marked = true)))

        assertEquals(setOf("dm-1"), store.snapshot().markedUnreadIds, "the event brings the mark")
        assertEquals(emptyList(), notices)
    }

    @Test
    fun activityPendingKeepsTheOptimisticCountWithNoToast() = runTest {
        val read = FakeReadReceiptRepository(unreadResult = { pendingAnswer.copy(channelId = it) })
        val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(activityItem("channel-1")))))
        val store = activityStore(backgroundScope, inbox, readReceiptRepository = read)
        val notices = collectNotices(store.readToggleNotices)
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()

        store.dispatch(SharedActivityIntent.MarkUnread(store.state.value.items.single()))
        runCurrent()

        val row = store.state.value.items.single()
        assertEquals(1 to false, row.unreadCount to row.markedUnread, "the optimistic count stays")
        assertEquals(emptyList(), notices)

        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(readState("channel-1", version = 9, marked = true)))
        runCurrent()

        assertTrue(store.state.value.items.single().markedUnread, "the event brings the mark")
        assertEquals(emptyList(), notices)
    }

    @Test
    fun homeFollowsTheMarkFromReadStateEventsByVersion() = runTest {
        val store = homeStore(unreadAnswer = markedAnswer)

        store.accept(SharedHomeIntent.ApplyRealtimeEvent("server-1", readState("dm-1", version = 9, marked = true)))
        assertEquals(setOf("dm-1"), store.snapshot().markedUnreadIds, "another device marked it")

        store.accept(SharedHomeIntent.ApplyRealtimeEvent("server-1", readState("dm-1", version = 8, marked = false)))
        assertEquals(setOf("dm-1"), store.snapshot().markedUnreadIds, "an older read never clears a newer mark")

        store.accept(SharedHomeIntent.ApplyRealtimeEvent("server-1", readState("dm-1", version = 10, marked = false)))
        assertEquals(emptySet(), store.snapshot().markedUnreadIds, "a newer read without the mark clears it")
    }

    @Test
    fun homeSummaryCarriesTheMarkThroughALoad() = runTest {
        val store = homeStore(unreadAnswer = markedAnswer, summaryMarked = true)
        assertEquals(setOf("dm-1"), store.snapshot().markedUnreadIds)
        assertEquals(0, store.snapshot().unreadTotal)
    }

    @Test
    fun activityMarkUnreadShowsTheDotAndAReadClearsIt() = runTest {
        val read = FakeReadReceiptRepository(unreadResult = { markedAnswer.copy(channelId = it) })
        val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(activityItem("channel-1")))))
        val store = activityStore(backgroundScope, inbox, readReceiptRepository = read)
        val notices = collectNotices(store.readToggleNotices)
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()

        store.dispatch(SharedActivityIntent.MarkUnread(store.state.value.items.single()))
        runCurrent()

        val marked = store.state.value.items.single()
        assertEquals(0, marked.unreadCount, "the optimistic count goes back")
        assertTrue(marked.markedUnread)
        assertEquals(0, store.state.value.totalUnreadCount)
        assertEquals(emptyList(), notices)

        store.dispatch(SharedActivityIntent.MarkRead(marked))
        runCurrent()

        assertEquals(listOf(ChannelId("channel-1")), read.readAllChannelIds, "a marked row with no count still sends its read")
        assertFalse(store.state.value.items.single().markedUnread, "the local read clears the dot at once")
    }

    @Test
    fun activityNothingToMarkKeepsTheRowReadAndSaysWhy() = runTest {
        listOf("refused" to refusedAnswer, "older server" to olderServerAnswer).forEach { (name, answer) ->
            val read = FakeReadReceiptRepository(unreadResult = { answer.copy(channelId = it) })
            val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(activityItem("channel-1")))))
            val store = activityStore(backgroundScope, inbox, readReceiptRepository = read)
            val notices = collectNotices(store.readToggleNotices)
            store.dispatch(SharedActivityIntent.LoadInitial)
            runCurrent()

            store.dispatch(SharedActivityIntent.MarkUnread(store.state.value.items.single()))
            runCurrent()

            val row = store.state.value.items.single()
            assertEquals(0 to false, row.unreadCount to row.markedUnread, name)
            assertEquals(0, store.state.value.totalUnreadCount, name)
            assertEquals(listOf(RaftReadToggleNotice.MarkUnreadNothing), notices, name)
        }
    }

    @Test
    fun activityUnreadKeepsAMarkedRowAndDropsItOnceAReadClearsTheMark() = runTest {
        // The server's Unread filter lists a marked row with no count (+1 row, +0 unread).
        val markedRow = threadItem("thread-1").copy(markedUnread = true)
        val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(markedRow), totalUnreadCount = 0)))
        val store = activityStore(backgroundScope, inbox)
        store.dispatch(SharedActivityIntent.SetFilter(InboxFilter.Unread))
        runCurrent()
        assertEquals(listOf("thread:thread-1"), store.state.value.items.map { it.key })

        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(readState("thread-1", version = 10, marked = false)))
        runCurrent()

        assertEquals(emptyList(), store.state.value.items, "read elsewhere: nothing unread left")
        assertEquals(0, store.state.value.totalCount)
    }

    @Test
    fun activityAllShowsAMarkFromAnotherDevice() = runTest {
        val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(threadItem("thread-1")))))
        val store = activityStore(backgroundScope, inbox)
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()

        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(readState("thread-1", version = 9, marked = true)))
        runCurrent()

        assertTrue(store.state.value.items.single().markedUnread)
        assertEquals(0, store.state.value.totalUnreadCount)
    }

    private fun TestScope.collectNotices(flow: kotlinx.coroutines.flow.Flow<RaftReadToggleNotice>): List<RaftReadToggleNotice> {
        val notices = mutableListOf<RaftReadToggleNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.toList(notices) }
        return notices
    }

    private fun readState(scopeId: String, version: Long, marked: Boolean) =
        PerReceiverReadStateChanged(
            serverId = ServerId("server-1"),
            scopes =
                listOf(
                    PerReceiverReadStateScope(
                        ChannelId(scopeId),
                        maxReadSeq = 12,
                        readStateVersion = version,
                        unreadCount = 0,
                        markedUnread = marked
                    )
                )
        )

    private suspend fun TestScope.homeStore(
        unreadAnswer: ReadReceiptResult,
        readCalls: MutableList<ChannelId> = mutableListOf(),
        summaryMarked: Boolean = false,
    ): SharedHomeStore {
        val session = sampleSession()
        val store =
            SharedHomeStore(
                authStore = InMemoryRaftAuthSessionStore(session),
                dataSource =
                    SharedHomeDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(session),
                        api = SummaryHomeApi(summaryMarked),
                        parseHome = { payload, _ ->
                            DataResult.Content(sampleHome(payload.serverId).copy(markedUnreadIds = payload.markedUnreadIds))
                        },
                        parseDispatcher = Dispatchers.Unconfined,
                    ),
                scope = backgroundScope,
                markChannelReadAll = { channelId ->
                    readCalls += channelId
                    DataResult.Content(ReadReceiptResult(channelId = channelId))
                },
                markChannelUnread = { channelId -> DataResult.Content(unreadAnswer.copy(channelId = channelId)) },
            )
        store.accept(SharedHomeIntent.Start)
        runCurrent()
        return store
    }

    private fun SharedHomeStore.snapshot(): HomeSnapshot =
        (state.value.content as SharedHomeContentState.Content).snapshot

    private fun sampleHome(serverId: ServerId) =
        HomeSnapshot(
            server = ServerSummary(serverId, "Slock"),
            currentMember =
                MemberProfile(
                    id = MemberId("user-1"),
                    displayName = "User One",
                    handle = "user-1",
                    kind = MemberKind.Human,
                    presence = Presence.Active,
                ),
            channels =
                listOf(HomeChannelRow(threadId = ThreadId("channel-1"), channelId = ChannelId("channel-1"), name = "general")),
            directMessages =
                listOf(
                    HomeDirectMessageRow(
                        threadId = ThreadId("dm-1"),
                        channelId = ChannelId("dm-1"),
                        name = "Cindy",
                        unreadCountKnown = true
                    )
                ),
            unreadTotal = 0,
        )

    /** Home's reads; the sidebar summary is the server's, with `dm-1` marked when [marked]. */
    private class SummaryHomeApi(private val marked: Boolean) : RaftHomeApi {
        override suspend fun channels(priority: DataRequestPriority) = HomeNetworkResponse(code = 200, bodyText = "[]")
        override suspend fun directMessages(priority: DataRequestPriority) = HomeNetworkResponse(code = 200, bodyText = "[]")
        override suspend fun channelUnreadCounts(priority: DataRequestPriority) =
            HomeNetworkResponse(
                code = 200,
                bodyText =
                    if (marked) {
                        """
                        {"channels":{"dm-1":{"unreadCount":0,"hasMention":false,"hasAnyMention":false,
                          "readState":{"kind":"present","readStateVersion":9,"maxReadSeq":"12","latestActivity":null,"markedUnread":true}}},
                         "threadUnread":{}}
                        """.trimIndent()
                    } else {
                        EMPTY_CHANNEL_UNREAD_SUMMARY_BODY
                    }
            )
        override suspend fun serverUnreadSummary(priority: DataRequestPriority) = HomeNetworkResponse(code = 200, bodyText = "[]")
    }
}
