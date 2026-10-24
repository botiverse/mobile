package build.raft.app.data

import build.raft.app.activityReadToggleIntent
import build.raft.app.model.ChannelId
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.PerReceiverReadStateChanged
import build.raft.app.model.PerReceiverReadStateScope
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.model.ServerDeleted
import build.raft.app.model.ServerMembershipRemoved
import build.raft.app.model.MemberId
import build.raft.app.model.ConversationSummary
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.ServerId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * "Being read" and "staying in the list" are separate (stdrc 10-23, via Remy; Gmail / Apple Mail
 * unread view): in Unread, every row the user reads during this visit (opened, marked read, or
 * listed at "Mark all read") is read at once (counts drop) but stays listed, shown read, until the
 * user moves on: switches filter, leaves Activity, pulls to refresh, or switches server. Opening
 * another row does not drop earlier ones. Returning from the conversation (phone Back, closing the
 * wide detail pane: no store intent but the return refresh), foreground catch-up, realtime and
 * background reconciles keep them; Done removes a row at once; a new message makes a retained row
 * unread again and it moves as usual. Web changes the same way.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedActivityStoreRetainedReadRowTest {
    private val rowA = activityItem("channel-a", unreadCount = 2).copy(readStateLatestActivitySeq = "10")
    private val rowB = activityItem("channel-b", unreadCount = 1).copy(readStateLatestActivitySeq = "7")
    private val rowC = activityItem("channel-c", unreadCount = 1)
    private val rowD = activityItem("channel-d", unreadCount = 1)
    private val owner = ReadReceiptOwner("production", "user-1", "server-1")

    /** How the opened row gets read: the Activity open itself, or the conversation's read receipt. */
    private enum class ReadPath { OpenMarksRead, ConversationReadReceipt }

    @Test
    fun everyRowReadThisVisitStaysReadInPlace() = runTest {
        ReadPath.entries.forEach { path ->
            // The server stops listing each row once it is read.
            val inbox =
                FakeInboxRepository(
                    initialSnapshots = listOf(snapshot(listOf(rowA, rowB, rowC)), snapshot(listOf(rowB, rowC)), snapshot(listOf(rowC)))
                )
            val store = unreadStore(inbox)

            store.open(rowA, path, revision = 1L)
            runCurrent()
            assertUnreadList(store, listOf(rowA.key to 0, rowB.key to 1, rowC.key to 1), "opened A, $path")
            assertEquals(setOf(rowA.key), store.state.value.retainedReadRowKeys, "$path")
            assertEquals(2 to 2, store.state.value.totalUnreadCount to store.state.value.totalCount, "counts drop at once, $path")

            // Back to the list on the phone: the page reappears and refreshes; A is still there.
            store.dispatch(SharedActivityIntent.Refresh)
            runCurrent()
            assertUnreadList(store, listOf(rowA.key to 0, rowB.key to 1, rowC.key to 1), "after return, $path")

            // Opening B keeps A: both stay, read, where they stood, across the next return refresh.
            store.open(store.state.value.items.first { it.key == rowB.key }, path, revision = 2L)
            runCurrent()
            assertUnreadList(store, listOf(rowA.key to 0, rowB.key to 0, rowC.key to 1), "opened B, $path")
            assertEquals(setOf(rowA.key, rowB.key), store.state.value.retainedReadRowKeys, "$path")
            assertEquals(1 to 1, store.state.value.totalUnreadCount to store.state.value.totalCount, "$path")
            store.dispatch(SharedActivityIntent.Refresh)
            runCurrent()
            assertEquals(3, inbox.initialLoadCount, "$path")
            assertUnreadList(store, listOf(rowA.key to 0, rowB.key to 0, rowC.key to 1), "after second return, $path")

            // Reopening a row already read this visit is a no-op: the same state, no emission.
            val before = store.state.value
            store.dispatch(SharedActivityIntent.RowOpened(before.items.first()))
            assertSame(before, store.state.value, "$path")
        }
    }

    @Test
    fun movingOnDropsEveryRowReadThisVisit() = runTest {
        listOf<Pair<String, suspend TestScope.(SharedActivityStore) -> Unit>>(
            "switch filter" to { store ->
                store.dispatch(SharedActivityIntent.SetFilter(InboxFilter.All))
                runCurrent()
                assertEquals(
                    listOf(rowC.key),
                    store.state.value.filterSnapshots[InboxFilter.Unread]?.items?.map { it.key },
                    "the Unread page cached behind All no longer lists A or B"
                )
                store.dispatch(SharedActivityIntent.SetFilter(InboxFilter.Unread))
            },
            "pull to refresh" to { store -> store.dispatch(SharedActivityIntent.PullRefresh) },
            "leave the Activity tab or route" to { store -> store.dispatch(SharedActivityIntent.SurfaceHidden) },
        ).forEach { (name, moveOn) ->
            val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(rowA, rowB, rowC)), snapshot(listOf(rowC))))
            val store = unreadStore(inbox)
            store.open(rowA, ReadPath.OpenMarksRead, revision = 1L)
            store.open(rowB, ReadPath.OpenMarksRead, revision = 2L)
            runCurrent()
            assertEquals(setOf(rowA.key, rowB.key), store.state.value.retainedReadRowKeys, name)

            moveOn(store)
            runCurrent()
            assertUnreadList(store, listOf(rowC.key to 1), name)
            assertEquals(emptySet(), store.state.value.retainedReadRowKeys, name)

            // A later reload does not bring them back.
            store.dispatch(SharedActivityIntent.Refresh)
            runCurrent()
            assertUnreadList(store, listOf(rowC.key to 1), "after reload, $name")
        }
    }

    /**
     * raft-design #793 (Kirby): the right swipe's Read / Unread toggle, which writes what the menu's Mark
     * as read / unread does ([activityReadToggleIntent]), keeps the row in place in Unread both ways, so
     * it can be swiped back; it leaves only when the user moves on. Mentions keeps read rows anyway.
     */
    @Test
    fun theSwipeReadToggleKeepsTheRowInUnreadBothWaysUntilTheUserMovesOn() = runTest {
        // The server stops listing A while it is read, and lists it again once it is unread.
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(snapshot(listOf(rowA, rowB)), snapshot(listOf(rowB)), snapshot(listOf(rowA, rowB)), snapshot(listOf(rowB)))
            )
        val store = unreadStore(inbox)
        fun toggleA() = store.dispatch(activityReadToggleIntent(store.state.value.items.first { it.key == rowA.key }))
        fun listed() = store.state.value.items.map { it.key to (it.unreadCount > 0) }

        toggleA()
        runCurrent()
        assertEquals(listOf(rowA.key to false, rowB.key to true), listed(), "marked read, A stays in place")
        store.dispatch(SharedActivityIntent.Refresh)
        runCurrent()
        assertEquals(listOf(rowA.key to false, rowB.key to true), listed(), "a reload that no longer lists A keeps it")

        toggleA()
        runCurrent()
        assertEquals(listOf(rowA.key to true, rowB.key to true), listed(), "swiped back: unread again, same place")
        store.dispatch(SharedActivityIntent.Refresh)
        runCurrent()
        assertEquals(listOf(rowA.key to true, rowB.key to true), listed(), "after a reload")

        toggleA()
        runCurrent()
        store.dispatch(SharedActivityIntent.Refresh)
        runCurrent()
        assertEquals(listOf(rowA.key to false, rowB.key to true), listed(), "read a second time, still listed")

        // Moving on (another filter and back) drops it.
        store.dispatch(SharedActivityIntent.SetFilter(InboxFilter.All))
        runCurrent()
        store.dispatch(SharedActivityIntent.SetFilter(InboxFilter.Unread))
        runCurrent()
        assertUnreadList(store, listOf(rowB.key to 1), "after switching filters")
    }

    @Test
    fun aRowSwipedBackToUnreadStaysUnreadWhenAReloadRacesTheWrite() = runTest {
        // The reload after the Unread write was answered before the server listed A again.
        val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(rowA, rowB)), snapshot(listOf(rowB))))
        val store = unreadStore(inbox)
        fun toggleA() = store.dispatch(activityReadToggleIntent(store.state.value.items.first { it.key == rowA.key }))
        toggleA()
        runCurrent()
        toggleA()
        runCurrent()
        store.dispatch(SharedActivityIntent.Refresh)
        runCurrent()
        assertEquals(
            listOf(rowA.key to true, rowB.key to true),
            store.state.value.items.map { it.key to (it.unreadCount > 0) },
            "the row the user marked unread keeps its place and stays unread",
        )
    }

    @Test
    fun theSwipeReadToggleKeepsTheRowInMentions() = runTest {
        val mention = rowA.copy(hasMention = true)
        val store =
            activityStore(backgroundScope, FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(mention, rowB)))), nowMillis = { testScheduler.currentTime })
        store.dispatch(SharedActivityIntent.SetFilter(InboxFilter.Mentions))
        runCurrent()
        store.dispatch(activityReadToggleIntent(store.state.value.items.first()))
        runCurrent()
        assertEquals(listOf(rowA.key to 0, rowB.key to 1), store.state.value.items.map { it.key to it.unreadCount })
        store.dispatch(activityReadToggleIntent(store.state.value.items.first()))
        runCurrent()
        assertEquals(listOf(rowA.key, rowB.key), store.state.value.items.map { it.key })
        assertTrue(store.state.value.items.first().unreadCount > 0, "swiped back to unread")
    }

    @Test
    fun markAllReadKeepsEveryListedRowReadUntilTheUserMovesOn() = runTest {
        // After the read-all the server lists only D, a row that arrived since.
        val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(rowA, rowB, rowC)), snapshot(listOf(rowD))))
        val pendingReadAll = CompletableDeferred<DataResult<ReadReceiptResult>>()
        val read = FakeReadReceiptRepository(pendingInboxReadAllResult = pendingReadAll)
        val store =
            activityStore(backgroundScope, inbox, readReceiptRepository = read, nowMillis = { testScheduler.currentTime })
        store.dispatch(SharedActivityIntent.SetFilter(InboxFilter.Unread))
        runCurrent()
        store.open(rowA, ReadPath.OpenMarksRead, revision = 1L)
        runCurrent()

        store.dispatch(SharedActivityIntent.MarkAllRead)
        runCurrent()
        assertUnreadList(store, listOf(rowA.key to 0, rowB.key to 0, rowC.key to 0), "the list does not change")
        assertEquals(0 to 0, store.state.value.totalUnreadCount to store.state.value.totalCount)
        assertEquals(setOf(rowA.key, rowB.key, rowC.key), store.state.value.retainedReadRowKeys)

        pendingReadAll.complete(DataResult.Content(ReadReceiptResult()))
        runCurrent()
        assertEquals(1, read.inboxReadAllServerIds.size)
        assertEquals(2, inbox.initialLoadCount, "the confirmed read-all refreshes in the background, like web")
        assertUnreadList(
            store,
            listOf(rowD.key to 1, rowA.key to 0, rowB.key to 0, rowC.key to 0),
            "a new unread row arrives as normal; the read rows stay"
        )

        store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()
        assertUnreadList(store, listOf(rowD.key to 1), "moving on drops them")
    }

    @Test
    fun doneRemovesARetainedRowAtOnceAndKeepsTheOthers() = runTest {
        val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(rowA, rowB, rowC)), snapshot(listOf(rowC))))
        val store = unreadStore(inbox)
        store.open(rowA, ReadPath.OpenMarksRead, revision = 1L)
        store.open(rowB, ReadPath.OpenMarksRead, revision = 2L)
        runCurrent()

        store.dispatch(SharedActivityIntent.MarkDone(store.state.value.items.first { it.key == rowA.key }))
        runCurrent()

        assertEquals(listOf(rowA.channelId), inbox.doneChannelIds)
        assertUnreadList(store, listOf(rowB.key to 0, rowC.key to 1), "Done leaves at once")
        assertEquals(setOf(rowB.key), store.state.value.retainedReadRowKeys)
    }

    @Test
    fun aRetainedRowWithANewMessageIsUnreadAgainAndStaysAfterMovingOn() = runTest {
        val unreadAgain =
            rowA.copy(unreadCount = 1, readStateLatestActivitySeq = "11", latestActivityMessageId = MessageId("new-a"))
        // The canonical page after the new message on A lists A first, unread again; B stays read.
        val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(rowC, rowA, rowB)), snapshot(listOf(unreadAgain, rowC))))
        val store = unreadStore(inbox, refreshIntervalMillis = 2_500L)
        store.open(rowA, ReadPath.OpenMarksRead, revision = 1L)
        store.open(rowB, ReadPath.OpenMarksRead, revision = 2L)
        runCurrent()
        assertUnreadList(store, listOf(rowC.key to 1, rowA.key to 0, rowB.key to 0), "A and B read")

        // Unread takes the new message through the background reconcile, like any other row.
        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(MessageNew(message("new-a", channelId = "channel-a", seq = 11L))))
        advanceTimeBy(2_501L)
        runCurrent()
        assertUnreadList(store, listOf(rowA.key to 1, rowC.key to 1, rowB.key to 0), "A is unread again and moves up as usual")

        store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()
        assertUnreadList(store, listOf(unreadAgain.key to 1, rowC.key to 1), "only the read rows leave")
    }

    @Test
    fun aServerSwitchDropsTheRetainedRows() = runTest {
        val auth = InMemoryRaftAuthSessionStore(twoServerSession(selected = "server-1"))
        val inbox =
            FakeInboxRepository(
                initialSnapshots = listOf(snapshot(listOf(rowA, rowB)), snapshot(listOf(rowA.copy(unreadCount = 0), rowB), serverId = "server-2"))
            )
        val store = unreadStore(inbox, authStore = auth)
        store.open(rowA, ReadPath.OpenMarksRead, revision = 1L)
        store.open(rowB, ReadPath.OpenMarksRead, revision = 2L)
        runCurrent()
        assertEquals(setOf(rowA.key, rowB.key), store.state.value.retainedReadRowKeys)

        auth.saveSession(twoServerSession(selected = "server-2"))
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()

        assertEquals("server-2" to InboxFilter.All, store.state.value.ownerServerId to store.state.value.selectedFilter)
        assertEquals(emptySet(), store.state.value.retainedReadRowKeys)
    }

    @Test
    fun realtimeAndBackgroundReconcileKeepEveryRetainedRow() = runTest {
        val remotelyRead = rowC.copy(readStateLatestActivitySeq = "7")
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(listOf(rowA, rowB, remotelyRead, rowD)),
                        // The reconcile no longer lists A and B (read here) nor C (read elsewhere).
                        snapshot(listOf(rowD))
                    )
            )
        val store = unreadStore(inbox, refreshIntervalMillis = 2_500L)
        store.open(rowA, ReadPath.OpenMarksRead, revision = 1L)
        store.open(rowB, ReadPath.OpenMarksRead, revision = 2L)
        runCurrent()

        // C is read on another device: it leaves Unread, the retained A and B stay.
        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(readState("channel-c", version = 2, maxReadSeq = 7)))
        runCurrent()
        assertUnreadList(store, listOf(rowA.key to 0, rowB.key to 0, rowD.key to 1), "remote read of C")

        // A read that may not cover D asks the server; that background reconcile keeps A and B in place.
        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(readState("channel-d", version = 3, maxReadSeq = 1)))
        advanceTimeBy(2_501L)
        runCurrent()
        assertEquals(2, inbox.initialLoadCount, "one background reconcile")
        assertUnreadList(store, listOf(rowA.key to 0, rowB.key to 0, rowD.key to 1), "after reconcile")
        assertEquals(setOf(rowA.key, rowB.key), store.state.value.retainedReadRowKeys)
    }

    /** A retained row must leave when the user lost access to it; read-only rows keep their place. */
    private class AccessCase(
        val name: String,
        val intent: SharedActivityIntent,
        val dropped: Set<String>
    )

    @Test
    fun aRetainedRowLeavesWhenTheUserLosesAccessToIt() = runTest {
        val thread =
            activityItem("thread-1", unreadCount = 1).copy(
                key = "thread:thread-1",
                kind = InboxItemKind.Thread,
                threadChannelId = ChannelId("thread-1"),
                parentChannelId = ChannelId("channel-p")
            )
        val all = setOf(rowA.key, rowB.key, thread.key)
        fun channels(vararg ids: String) = SharedActivityIntent.ChannelAccessLost(ids.toSet())
        fun event(e: RealtimeEvent) = SharedActivityIntent.ApplyRealtimeEvent(e)
        fun member(reason: String, memberId: String) =
            event(
                RealtimeRefreshRequired(
                    scope = RealtimeRefreshScope.ServerMembers,
                    reason = reason,
                    serverId = ServerId("server-1"),
                    memberId = MemberId(memberId)
                )
            )
        val cases =
            listOf(
                // The app's own leave / archive / delete of a channel.
                AccessCase("left channel-a", channels("channel-a"), setOf(rowA.key)),
                AccessCase("left the thread's parent channel", channels("channel-p"), setOf(thread.key)),
                AccessCase("left the thread channel", channels("thread-1"), setOf(thread.key)),
                AccessCase("left two channels", channels("channel-a", "channel-b"), setOf(rowA.key, rowB.key)),
                // The user left or was removed from, or lost, the Server.
                AccessCase("server membership removed", event(ServerMembershipRemoved(ServerId("server-1"))), all),
                AccessCase("server deleted", event(ServerDeleted(ServerId("server-1"))), all),
                AccessCase("left the server", member(RealtimeSocketEvents.ServerMemberLeft, "user-1"), all),
                AccessCase("removed from the server", member(RealtimeSocketEvents.ServerMemberRemoved, "user-1"), all),
                // Nothing the user lost: every retained row stays.
                AccessCase("another channel", channels("channel-z"), emptySet()),
                AccessCase("another server removed", event(ServerMembershipRemoved(ServerId("server-2"))), emptySet()),
                AccessCase("another server deleted", event(ServerDeleted(ServerId("server-2"))), emptySet()),
                AccessCase("another member left", member(RealtimeSocketEvents.ServerMemberLeft, "user-2"), emptySet()),
                AccessCase("another member removed", member(RealtimeSocketEvents.ServerMemberRemoved, "user-2"), emptySet())
            )
        cases.forEach { case ->
            val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(rowA, rowB, thread, rowC))))
            val store = unreadStore(inbox)
            store.open(rowA, ReadPath.OpenMarksRead, revision = 1L)
            store.open(rowB, ReadPath.OpenMarksRead, revision = 2L)
            store.open(thread, ReadPath.OpenMarksRead, revision = 3L)
            runCurrent()
            assertEquals(all, store.state.value.retainedReadRowKeys, case.name)

            store.dispatch(case.intent)
            runCurrent()

            val expectedKeys = listOf(rowA.key, rowB.key, thread.key, rowC.key).filterNot { it in case.dropped }
            assertEquals(expectedKeys, store.state.value.items.map { it.key }, case.name)
            assertEquals(all - case.dropped, store.state.value.retainedReadRowKeys, case.name)
            assertEquals(1, store.state.value.totalCount, "${case.name}: read rows already left the counts")
            assertEquals(1, store.state.value.totalUnreadCount, "${case.name}: the unread C stays counted")
        }
    }

    @Test
    fun aDroppedRowStaysGoneWhenTheServerStopsListingIt() = runTest {
        val inbox =
            FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(rowA, rowB, rowC)), snapshot(listOf(rowC))))
        val store = unreadStore(inbox, refreshIntervalMillis = 2_500L)
        store.open(rowA, ReadPath.OpenMarksRead, revision = 1L)
        store.open(rowB, ReadPath.OpenMarksRead, revision = 2L)
        runCurrent()
        store.dispatch(SharedActivityIntent.ChannelAccessLost(setOf("channel-a")))
        runCurrent()

        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(readState("channel-c", version = 2, maxReadSeq = 1)))
        advanceTimeBy(2_501L)
        runCurrent()

        assertUnreadList(store, listOf(rowB.key to 0, rowC.key to 1), "the page no longer lists A or B; only B is retained")
        assertEquals(setOf(rowB.key), store.state.value.retainedReadRowKeys)
    }

    private fun channelEvent(reason: String, channelId: String): SharedActivityIntent =
        SharedActivityIntent.ApplyRealtimeEvent(
            RealtimeRefreshRequired(
                scope =
                    if (reason == RealtimeSocketEvents.ChannelUpdated) RealtimeRefreshScope.Home
                    else RealtimeRefreshScope.ChannelMembers,
                reason = reason,
                serverId = ServerId("server-1"),
                channelId = ChannelId(channelId)
            )
        )

    private val channelEventReasons =
        listOf(RealtimeSocketEvents.ChannelUpdated, RealtimeSocketEvents.ChannelMembersUpdated)

    private fun http404(): DataResult<ConversationSummary> = DataResult.Error(ScreenError("http_404", retryable = false))

    private fun TestScope.storeWithRetainedAB(conversations: FakeConversationRepository): SharedActivityStore {
        val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(rowA, rowB, rowC))))
        val store = unreadStore(inbox, conversations = conversations)
        store.open(rowA, ReadPath.OpenMarksRead, revision = 1L)
        store.open(rowB, ReadPath.OpenMarksRead, revision = 2L)
        runCurrent()
        return store
    }

    @Test
    fun aChannelEventDropsTheChannelsRetainedRowsOnlyWhenTheReReadAnswers404() = runTest {
        channelEventReasons.forEach { reason ->
            val conversations = FakeConversationRepository().apply { detailResponder = { http404() } }
            val store = storeWithRetainedAB(conversations)

            store.dispatch(channelEvent(reason, "channel-a"))
            runCurrent()

            assertEquals(listOf(ChannelId("channel-a") to DataRequestPriority.Background), conversations.detailRequests, reason)
            assertUnreadList(store, listOf(rowB.key to 0, rowC.key to 1), "404 drops A only, $reason")
            assertEquals(setOf(rowB.key), store.state.value.retainedReadRowKeys, reason)
        }
    }

    @Test
    fun aFailedReReadIsNotAccessLoss() = runTest {
        val failures =
            listOf(
                DataResult.Error(ScreenError("server_unavailable", retryable = true)),
                DataResult.Error(ScreenError("network_unavailable", retryable = true)),
                DataResult.Error(ScreenError("http_403", retryable = false)),
                DataResult.Empty(EmptyReason.NoMessages)
            )
        failures.forEach { failure ->
            val conversations = FakeConversationRepository().apply { detailResponder = { failure } }
            val store = storeWithRetainedAB(conversations)

            store.dispatch(channelEvent(RealtimeSocketEvents.ChannelMembersUpdated, "channel-a"))
            runCurrent()

            assertEquals(1, conversations.detailRequests.size, "$failure")
            assertUnreadList(store, listOf(rowA.key to 0, rowB.key to 0, rowC.key to 1), "$failure")
        }
    }

    @Test
    fun aChannelWithoutRetainedRowsIsNeverReRead() = runTest {
        val conversations = FakeConversationRepository().apply { detailResponder = { http404() } }
        val store = storeWithRetainedAB(conversations)

        // C was never read (not retained); Z is not listed at all.
        store.dispatch(channelEvent(RealtimeSocketEvents.ChannelUpdated, "channel-c"))
        store.dispatch(channelEvent(RealtimeSocketEvents.ChannelMembersUpdated, "channel-z"))
        runCurrent()
        assertEquals(emptyList(), conversations.detailRequests)

        // After the visit ends nothing is retained either.
        store.dispatch(SharedActivityIntent.SurfaceHidden)
        store.dispatch(channelEvent(RealtimeSocketEvents.ChannelMembersUpdated, "channel-a"))
        runCurrent()
        assertEquals(emptyList(), conversations.detailRequests)
    }

    @Test
    fun aStale404AfterAccessWasRegainedDoesNotDrop() = runTest {
        val first = CompletableDeferred<DataResult<ConversationSummary>>()
        val conversations = FakeConversationRepository()
        var calls = 0
        conversations.detailResponder = { if (++calls == 1) first.await() else DataResult.Empty(EmptyReason.NoMessages) }
        val store = storeWithRetainedAB(conversations)

        store.dispatch(channelEvent(RealtimeSocketEvents.ChannelMembersUpdated, "channel-a"))
        runCurrent()
        // Single flight: the request is in flight; a later members-updated (the user was added back)
        // does not start a parallel one.
        store.dispatch(channelEvent(RealtimeSocketEvents.ChannelMembersUpdated, "channel-a"))
        runCurrent()
        assertEquals(1, conversations.detailRequests.size)

        first.complete(http404())
        runCurrent()

        // The stale 404 is ignored; the check ran again and the channel is readable.
        assertEquals(2, conversations.detailRequests.size)
        assertUnreadList(store, listOf(rowA.key to 0, rowB.key to 0, rowC.key to 1), "stale 404")
    }

    @Test
    fun a404ForARowThatLeftTheRetainedSetBeforeItArrivedDoesNothing() = runTest {
        val gate = CompletableDeferred<DataResult<ConversationSummary>>()
        val conversations = FakeConversationRepository().apply { detailResponder = { gate.await() } }
        val store = storeWithRetainedAB(conversations)
        store.dispatch(channelEvent(RealtimeSocketEvents.ChannelUpdated, "channel-a"))
        runCurrent()

        store.dispatch(SharedActivityIntent.SurfaceHidden)
        gate.complete(http404())
        runCurrent()

        assertEquals(1, conversations.detailRequests.size)
        assertEquals(emptySet(), store.state.value.retainedReadRowKeys)
    }

    @Test
    fun a404AfterAServerSwitchIsIgnored() = runTest {
        val gate = CompletableDeferred<DataResult<ConversationSummary>>()
        val conversations = FakeConversationRepository().apply { detailResponder = { gate.await() } }
        val store = storeWithRetainedAB(conversations)
        store.dispatch(channelEvent(RealtimeSocketEvents.ChannelUpdated, "channel-a"))
        runCurrent()

        store.clear()
        gate.complete(http404())
        runCurrent()

        assertEquals(1, conversations.detailRequests.size)
    }

    private fun TestScope.unreadStore(
        inbox: InboxRepository,
        conversations: FakeConversationRepository = FakeConversationRepository(),
        authStore: RaftAuthSessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
        refreshIntervalMillis: Long = 60_000L
    ): SharedActivityStore {
        val store =
            activityStore(
                backgroundScope,
                inbox,
                conversationRepository = conversations,
                authStore = authStore,
                realtimeBackgroundRefreshIntervalMillis = refreshIntervalMillis,
                nowMillis = { testScheduler.currentTime }
            )
        store.dispatch(SharedActivityIntent.SetFilter(InboxFilter.Unread))
        runCurrent()
        return store
    }

    /** Dispatches what the Activity row open does (SharedInboxPanel openRow) for [path]. */
    private fun SharedActivityStore.open(item: InboxItem, path: ReadPath, revision: Long) {
        when (path) {
            ReadPath.OpenMarksRead -> {
                dispatch(SharedActivityIntent.MarkRead(item))
                dispatch(SharedActivityIntent.RowOpened(item))
            }
            ReadPath.ConversationReadReceipt -> {
                dispatch(SharedActivityIntent.RowOpened(item))
                dispatch(
                    SharedActivityIntent.ApplyReadReceiptPrediction(
                        ReadReceiptPrediction(owner, item.channelId, revision, ReadReceiptTarget.All)
                    )
                )
            }
        }
    }

    private fun assertUnreadList(store: SharedActivityStore, expected: List<Pair<String, Int>>, message: String) {
        assertEquals(InboxFilter.Unread, store.state.value.selectedFilter, message)
        assertEquals(expected, store.state.value.items.map { it.key to it.unreadCount }, message)
    }

    private fun readState(scopeId: String, version: Long, maxReadSeq: Long): PerReceiverReadStateChanged =
        PerReceiverReadStateChanged(
            serverId = ServerId("server-1"),
            scopes = listOf(PerReceiverReadStateScope(ChannelId(scopeId), maxReadSeq = maxReadSeq, readStateVersion = version))
        )

    private fun twoServerSession(selected: String): StoredAuthSession =
        sampleSession(serverId = selected).copy(
            servers = listOf(StoredAuthServer(id = "server-1", name = "One"), StoredAuthServer(id = "server-2", name = "Two"))
        )
}
