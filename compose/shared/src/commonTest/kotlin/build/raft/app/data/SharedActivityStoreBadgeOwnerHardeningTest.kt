package build.raft.app.data

import build.raft.app.model.ActivityChanged
import build.raft.app.model.ChannelId
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.PerReceiverReadStateChanged
import build.raft.app.model.PerReceiverReadStateScope
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadUpdated
import build.raft.app.network.sync.ReplyPreview
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `inbox_row_position_v1` (B4, review of #4863): the list owns the badge only while it can keep it.
 * Its shown state comes from the panel's edges (M1), a failed or stale reset read gives the badge back
 * to the summary (M2), only a window with live realtime sinks can own it (M3), a thrown row read is a
 * failed one (L1), a `thread:updated` adds no thread total on the hidden +1 path (L2), and the owner's
 * account must match (L3). Flag off: unchanged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedActivityStoreBadgeOwnerHardeningTest {
    private val owner =
        ProcessActivityUnreadOwner(environmentId = "https://api.example.test", accountId = "user-1", serverId = "server-1")

    private class Inbox(private val lists: List<DataResult<InboxSnapshot>>) :
        InboxRepository by FakeInboxRepository(listOf(snapshot(emptyList()))) {
        val reasons = mutableListOf<InboxFetchReason?>()
        val rowReads = mutableListOf<List<String>>()
        var rowAnswer: (suspend () -> DataResult<InboxSnapshot>)? = null

        override suspend fun loadInitial(serverId: ServerId, filter: InboxFilter, limit: Int): DataResult<InboxSnapshot> {
            reasons += currentCoroutineContext()[InboxFetchReasonContext]?.reason
            return lists.getOrElse(reasons.size - 1) { lists.last() }
        }

        override suspend fun loadRows(serverId: ServerId, rowIds: List<String>): DataResult<InboxSnapshot> {
            rowReads += rowIds
            return rowAnswer?.invoke() ?: CompletableDeferred<DataResult<InboxSnapshot>>().await()
        }
    }

    private class Fixture(
        val store: SharedActivityStore,
        val inbox: Inbox,
        val foreground: MutableStateFlow<Boolean>,
        val released: MutableList<String>,
        val checks: MutableList<ActivityInPlaceCheck>
    )

    private fun TestScope.fixture(
        lists: List<DataResult<InboxSnapshot>>,
        rowPosition: Boolean = true,
        hiddenWindow: Long? = 30_000L,
        panelActive: Boolean? = null
    ): Fixture {
        val inbox = Inbox(lists)
        val foreground = MutableStateFlow(true)
        val released = mutableListOf<String>()
        val checks = mutableListOf<ActivityInPlaceCheck>()
        val store =
            activityStore(
                scope = backgroundScope,
                inboxRepository = inbox,
                realtimeBackgroundRefreshIntervalMillis = ActivityRealtimeBackgroundRefreshIntervalMillis,
                hiddenRealtimeBackgroundRefreshIntervalMillis = hiddenWindow,
                nowMillis = { testScheduler.currentTime },
                appForeground = foreground,
                onBadgeReleased = { released += it },
                inboxInPlaceEnabled = { true },
                inboxRowPositionEnabled = { rowPosition },
                inPlaceCheckSink = { _, check -> checks += check }
            )
        // As the panel does: its mount-time active state, then its LoadInitial.
        panelActive?.let { store.dispatch(SharedActivityIntent.SurfaceActive(it)) }
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        return Fixture(store, inbox, foreground, released, checks)
    }

    private val row = activityItem("channel-1", unreadCount = 2).copy(latestActivitySeq = "10", readStateLatestActivitySeq = "10")
    private val fresh = DataResult.Content(snapshot(listOf(row)))

    private fun live(id: String, seq: Long) =
        SharedActivityIntent.ApplyRealtimeEvent(MessageNew(message(id, channelId = "channel-1", seq = seq)))

    @Test
    fun aPanelComposedHiddenKeepsTheHiddenWindowThoughItsLoadInitialRan() = runTest {
        val f = fixture(listOf(fresh), panelActive = false)
        f.store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(ActivityChanged(ServerId("server-1"), source = "activity:changed")))
        advanceTimeBy(ActivityRealtimeBackgroundRefreshIntervalMillis + 1_000L)
        runCurrent()
        assertEquals(listOf<InboxFetchReason?>(InboxFetchReason.Initial), f.inbox.reasons, "hidden: not the 2.5 s window")
        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals(2, f.inbox.reasons.size, "the hidden window's read (the list owns the badge, so it reads)")

        // Flag off: the panel's active state is not read; LoadInitial showed the list, as before.
        val off = fixture(listOf(fresh), rowPosition = false, panelActive = false)
        off.store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(ActivityChanged(ServerId("server-1"), source = "activity:changed")))
        advanceTimeBy(ActivityRealtimeBackgroundRefreshIntervalMillis + 1_000L)
        runCurrent()
        assertEquals(2, off.inbox.reasons.size)
    }

    @Test
    fun anAuditDueWhileThePanelIsHiddenIsNotJudged() = runTest {
        val f = fixture(listOf(fresh), panelActive = false)
        repeat(ActivityPositionAuditEvery) { f.store.dispatch(live("m-${11 + it}", seq = 11L + it)) }
        advanceTimeBy(ActivityRowRereadDelayMillis + 1L)
        runCurrent()
        assertEquals(emptyList(), f.inbox.rowReads)
        assertEquals(ActivityAuditNotJudgedCause.PageHidden, f.checks.single().notJudgedCause)
    }

    @Test
    fun aFailedOrStaleReturnReadGivesTheBadgeBack() = runTest {
        listOf(
            DataResult.Error(ScreenError(code = "network")),
            DataResult.Content(snapshot(listOf(row)).copy(staleReason = RaftStaleReason.entries.first()))
        ).forEach { answer ->
            val f = fixture(listOf(fresh, answer))
            assertEquals(true, f.store.ownsBadgeFor(owner))
            f.foreground.value = false
            runCurrent()
            f.foreground.value = true
            advanceTimeBy(30_000L)
            runCurrent()

            // The return read (later ones are the stale / failed answer's own recovery reads).
            assertEquals(listOf<InboxFetchReason?>(InboxFetchReason.Initial, InboxFetchReason.Visibility), f.inbox.reasons.take(2))
            assertEquals(false, f.store.ownsBadgeFor(owner), "answer=$answer")
            assertEquals(listOf("server-1"), f.released, "the summary reads the badge again")
        }
    }

    @Test
    fun theBadgeReleasedReadIsAVisibilityReadTheListDoesNotAnswer() {
        assertEquals(InboxFetchReason.Visibility, activityUnreadSummaryFetchReason(ActivityBadgeReleasedSummaryReason))
        assertEquals(false, ActivityBadgeReleasedSummaryReason.isListDerivedActivitySummaryRead())
    }

    @Test
    fun onlyAWindowWithLiveSinksOwnsTheBadge() = runTest {
        val a = fixture(listOf(fresh)).store
        val b = fixture(listOf(fresh)).store
        val windowA = RaftShellStoreScope.create("window-a")
        val windowB = RaftShellStoreScope.create("window-b")
        val stores = listOf(windowA to a, windowB to b)

        assertEquals(true, activityListOwnsBadge(owner, stores, liveScopes = setOf(windowB)))
        // Window B closed (or its shell never registered its sinks): A gets no live events, it can't own it.
        assertEquals(false, activityListOwnsBadge(owner, stores.take(1), liveScopes = setOf(windowB)))
        assertEquals(false, activityListOwnsBadge(owner, stores, liveScopes = emptySet()))
    }

    @Test
    fun anotherAccountsOwnerIsNotOwned() = runTest {
        val f = fixture(listOf(fresh))
        assertEquals(true, f.store.ownsBadgeFor(owner))
        assertEquals(false, f.store.ownsBadgeFor(owner.copy(accountId = "user-2")))
    }

    @Test
    fun aThrownRowReadIsAFailedOne() = runTest {
        val f = fixture(listOf(fresh))
        f.inbox.rowAnswer = { throw IllegalStateException("boom") }
        repeat(ActivityPositionAuditEvery) { f.store.dispatch(live("m-${11 + it}", seq = 11L + it)) }
        advanceTimeBy(ActivityRowRereadDelayMillis + 1L)
        runCurrent()
        assertEquals(ActivityAuditNotJudgedCause.RequestFailed, f.checks.single().notJudgedCause)

        // A plain re-read that throws falls back to the list read, and the next row re-read still goes.
        f.store.dispatch(live("m-5", seq = 5L))
        advanceTimeBy(ActivityRowRereadDelayMillis + ActivityRealtimeBackgroundRefreshIntervalMillis + 1L)
        runCurrent()
        assertEquals(InboxFetchReason.RealtimeFallback, f.inbox.reasons.last())
        f.store.dispatch(live("m-6", seq = 6L))
        advanceTimeBy(ActivityRowRereadDelayMillis + 1L)
        runCurrent()
        assertEquals(3, f.inbox.rowReads.size, "no row is left in flight")
    }

    @Test
    fun aThreadUpdatedAddsItsReplyOnceNotTheThreadsTotal() = runTest {
        val thread = threadItem("thread-1").copy(replyCount = 2, latestActivitySeq = "20", readStateLatestActivitySeq = "20")
        fun TestScope.totalAfter(rowPosition: Boolean): Int {
            val f = fixture(listOf(DataResult.Content(snapshot(listOf(thread)))), rowPosition = rowPosition)
            f.store.dispatch(
                SharedActivityIntent.ApplyRealtimeSummaryEvent(
                    MessageNew(message("r-21", channelId = "thread-1", seq = 21L)),
                    activeConversationId = null
                )
            )
            f.store.dispatch(
                SharedActivityIntent.ApplyRealtimeSummaryEvent(
                    ThreadUpdated(
                        parentMessageId = MessageId("parent-message"),
                        threadChannelId = ChannelId("thread-1"),
                        replyCount = 3,
                        lastReplyAt = "2026-11-16T10:00:00Z",
                        participantIds = emptyList(),
                        unreadCount = 5,
                        latestReply =
                            ReplyPreview(
                                messageId = "r-21",
                                seq = 21L,
                                content = "reply",
                                senderId = "user-2",
                                senderType = "user",
                                senderName = "user-2",
                                createdAt = "2026-11-16T10:00:00Z"
                            )
                    ),
                    activeConversationId = null
                )
            )
            runCurrent()
            return f.store.state.value.totalUnreadCount
        }
        assertEquals(1, totalAfter(rowPosition = true), "the reply, once")
        // Flag off: today's `unreadCount` add, as before.
        assertEquals(6, totalAfter(rowPosition = false))
    }

    @Test
    fun aListCheckSaysTheCauseAndWhetherAReadStateCame() = runTest {
        val f = fixture(listOf(DataResult.Content(snapshot(listOf(row.copy(unreadCount = 0))))))
        f.store.dispatch(live("m-11", seq = 11L))
        runCurrent()
        f.store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                PerReceiverReadStateChanged(
                    serverId = ServerId("server-1"),
                    scopes = listOf(PerReceiverReadStateScope(ChannelId("channel-1"), maxReadSeq = 11L, readStateVersion = 2L))
                )
            )
        )
        runCurrent()
        advanceTimeBy(ActivityInPlaceCheckTtlMillis + 1_000L)
        f.store.dispatch(SharedActivityIntent.Refresh)
        runCurrent()

        val detail = f.checks.single { it.result == "expired" }.outcomeDetail
        assertTrue(detail.contains(";reread=position;own=0;sender_type=user;message_type=chat;age_ms="), detail)
        assertTrue(detail.endsWith(";read_state=1;read_behind=0"), detail)
    }
}
