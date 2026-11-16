package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.MessageSystemProvenance
import build.raft.app.model.MessageType
import build.raft.app.model.PerReceiverReadStateChanged
import build.raft.app.model.PerReceiverReadStateScope
import build.raft.app.model.ServerId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `inbox_row_position_v1` (#triage:#244, inbox redesign step 2) at the store: what the contract cases
 * (ActivityRowContractCasesTest) do not pin. The read marker follows a channel row's live patch, a row
 * the rule cannot judge is re-read alone (`?ids=`, its reason and trigger), rows asked for together go
 * in one read, a row the answer leaves out re-reads the list, and a late older answer is kept out by
 * the row's position however long after it lands (no timed hold). Flag off, today's paths.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedActivityStoreRowPositionTest {
    private data class Read(val reason: InboxFetchReason?, val trigger: InboxRefreshTriggerCount?, val rowIds: List<String>?)

    /** List reads answer from [lists] (the last one repeats); row reads wait for [rowAnswer]. */
    private class RecordingInbox(private val lists: List<InboxSnapshot>) : InboxRepository by FakeInboxRepository(lists) {
        val reads = mutableListOf<Read>()
        private var listReads = 0
        var rowAnswer: CompletableDeferred<DataResult<InboxSnapshot>>? = null

        override suspend fun loadInitial(serverId: ServerId, filter: InboxFilter, limit: Int): DataResult<InboxSnapshot> {
            val context = currentCoroutineContext()[InboxFetchReasonContext]
            reads += Read(context?.reason, context?.trigger, rowIds = null)
            return DataResult.Content(lists.getOrElse(listReads++) { lists.last() })
        }

        override suspend fun loadRows(serverId: ServerId, rowIds: List<String>): DataResult<InboxSnapshot> {
            val context = currentCoroutineContext()[InboxFetchReasonContext]
            reads += Read(context?.reason, context?.trigger, rowIds)
            val answer = CompletableDeferred<DataResult<InboxSnapshot>>()
            rowAnswer = answer
            return answer.await()
        }

        val rowReads: List<Read> get() = reads.filter { it.rowIds != null }
        val listReadCount: Int get() = reads.count { it.rowIds == null }
    }

    private class Fixture(val store: SharedActivityStore, val inbox: RecordingInbox) {
        fun row(key: String): InboxItem = store.state.value.items.first { it.key == key }
    }

    private fun TestScope.loadedStore(lists: List<InboxSnapshot>, rowPosition: Boolean = true): Fixture {
        val inbox = RecordingInbox(lists)
        val store =
            activityStore(
                scope = backgroundScope,
                inboxRepository = inbox,
                realtimeBackgroundRefreshIntervalMillis = ActivityRealtimeBackgroundRefreshIntervalMillis,
                nowMillis = { testScheduler.currentTime },
                inboxInPlaceEnabled = { true },
                inboxRowPositionEnabled = { rowPosition }
            )
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        return Fixture(store, inbox)
    }

    private val channelRow =
        activityItem("channel-1").copy(latestActivitySeq = "10", readStateLatestActivitySeq = "10")
    private val otherRow =
        activityItem("channel-2").copy(latestActivitySeq = "5", readStateLatestActivitySeq = "5")

    private fun live(id: String, channelId: String, seq: Long) =
        SharedActivityIntent.ApplyRealtimeEvent(MessageNew(message(id, channelId = channelId, seq = seq)))

    @Test
    fun aChannelRowsLivePatchMovesItsReadMarkerWithTheFlagOn() = runTest {
        val on = loadedStore(listOf(snapshot(listOf(channelRow))))
        on.store.dispatch(live("m-11", "channel-1", seq = 11L))
        runCurrent()
        assertEquals("11" to "11", on.row(channelRow.key).let { it.latestActivitySeq to it.readStateLatestActivitySeq })

        // Flag off: the marker stays on the served row (as before).
        val off = loadedStore(listOf(snapshot(listOf(channelRow))), rowPosition = false)
        off.store.dispatch(live("m-11", "channel-1", seq = 11L))
        runCurrent()
        assertEquals("11" to "10", off.row(channelRow.key).let { it.latestActivitySeq to it.readStateLatestActivitySeq })
    }

    @Test
    fun aReadOfThePatchedRowCoversItsMessageAndNotANewerOne() = runTest {
        // #15049: the view still serves 11 unread after the read of the patched row (the read is not
        // in it yet): the read hold, at the patched marker, keeps the row read.
        val lagging =
            channelRow.copy(unreadCount = 1, lastMessageId = MessageId("m-11"), latestActivitySeq = "11", readStateLatestActivitySeq = "11")
        val newer = channelRow.copy(unreadCount = 1, latestActivitySeq = "12", readStateLatestActivitySeq = "12")
        fun TestScope.readThenAnswer(rowPosition: Boolean, vararg answers: InboxSnapshot): Fixture {
            val f = loadedStore(listOf(snapshot(listOf(channelRow))) + answers, rowPosition)
            f.store.dispatch(live("m-11", "channel-1", seq = 11L))
            runCurrent()
            f.store.dispatch(readState("channel-1", maxReadSeq = 11L))
            runCurrent()
            assertEquals(0, f.row(channelRow.key).unreadCount)
            answers.forEach { _ ->
                f.store.dispatch(SharedActivityIntent.Refresh)
                runCurrent()
            }
            return f
        }

        assertEquals(0, readThenAnswer(rowPosition = true, snapshot(listOf(lagging))).row(channelRow.key).unreadCount)
        // An answer at 12 has a message the read does not cover: its unread stands.
        val past = readThenAnswer(rowPosition = true, snapshot(listOf(lagging)), snapshot(listOf(newer)))
        assertEquals(1 to "12", past.row(channelRow.key).let { it.unreadCount to it.latestActivitySeq })
        // Flag off: the hold sits at the served marker (10), so the lagging 11 brings the unread back (as before).
        assertEquals(1, readThenAnswer(rowPosition = false, snapshot(listOf(lagging))).row(channelRow.key).unreadCount)
    }

    @Test
    fun anAnswerAtTheRowsOwnPositionStillRefreshesTheRow() = runTest {
        // Only an answer older than the row is left out; one at its position is taken (a rename here).
        val renamed = otherRow.copy(channelName = "renamed")
        val f = loadedStore(listOf(snapshot(listOf(otherRow)), snapshot(listOf(renamed))))
        f.store.dispatch(SharedActivityIntent.Refresh)
        runCurrent()
        assertEquals("renamed", f.row(otherRow.key).channelName)
    }

    @Test
    fun theReadersOwnSendReadsTheRow() = runTest {
        // Web receiveLiveByPosition: my own send reads the conversation on the server, and no read
        // state comes to say so. Flag off, the unread stays until the next answer (as before).
        fun TestScope.afterOwnSend(rowPosition: Boolean): InboxItem {
            val f = loadedStore(listOf(snapshot(listOf(channelRow))), rowPosition)
            f.store.dispatch(live("m-11", "channel-1", seq = 11L))
            f.store.dispatch(
                SharedActivityIntent.ApplyRealtimeEvent(MessageNew(message("m-12", channelId = "channel-1", authorId = "user-1", seq = 12L)))
            )
            runCurrent()
            return f.row(channelRow.key)
        }
        assertEquals(0 to "12", afterOwnSend(rowPosition = true).let { it.unreadCount to it.latestActivitySeq })
        assertEquals(1 to "12", afterOwnSend(rowPosition = false).let { it.unreadCount to it.latestActivitySeq })
    }

    @Test
    fun anAmbiguousLiveSeqRereadsOnlyItsRowWithItsReasonAndTrigger() = runTest {
        val f = loadedStore(listOf(snapshot(listOf(otherRow, channelRow))))
        // At or below the row's delivered position, never applied here: it may have committed late.
        f.store.dispatch(live("m-9", "channel-1", seq = 9L))
        runCurrent()
        assertEquals(emptyList(), f.inbox.rowReads, "a row re-read waits ActivityRowRereadDelayMillis first")

        advanceTimeBy(ActivityRowRereadDelayMillis + 1L)
        runCurrent()
        assertEquals(
            listOf(Read(InboxFetchReason.PositionReread, InboxRefreshTriggerCount(InboxRefreshTrigger.AmbiguousSeq), listOf("channel-1"))),
            f.inbox.rowReads
        )
        assertEquals(1, f.inbox.listReadCount, "the list is not re-read")

        f.inbox.rowAnswer!!.complete(
            DataResult.Content(snapshot(listOf(channelRow.copy(unreadCount = 2, firstUnreadMessageId = null))))
        )
        runCurrent()
        assertEquals(2, f.row(channelRow.key).unreadCount)
        assertEquals(2, f.store.state.value.totalUnreadCount)
        assertEquals(1, f.inbox.listReadCount)
    }

    @Test
    fun rowsAskedForTogetherGoInOneRead() = runTest {
        val f = loadedStore(listOf(snapshot(listOf(otherRow, channelRow))))
        f.store.dispatch(live("m-9", "channel-1", seq = 9L))
        f.store.dispatch(live("m-4", "channel-2", seq = 4L))
        // The same row asked twice is one row standing for two asks.
        f.store.dispatch(live("m-8", "channel-1", seq = 8L))
        advanceTimeBy(ActivityRowRereadDelayMillis + 1L)
        runCurrent()

        assertEquals(
            listOf(
                Read(
                    InboxFetchReason.PositionReread,
                    InboxRefreshTriggerCount(InboxRefreshTrigger.AmbiguousSeq, coalesced = 3),
                    listOf("channel-1", "channel-2")
                )
            ),
            f.inbox.rowReads
        )
    }

    @Test
    fun aRowTheAnswerLeavesOutRereadsTheList() = runTest {
        val f = loadedStore(listOf(snapshot(listOf(otherRow, channelRow)), snapshot(listOf(otherRow))))
        f.store.dispatch(live("m-9", "channel-1", seq = 9L))
        advanceTimeBy(ActivityRowRereadDelayMillis + 1L)
        runCurrent()
        // Done, archived or gone for this reader: the route leaves it out.
        f.inbox.rowAnswer!!.complete(DataResult.Content(snapshot(emptyList())))
        advanceTimeBy(ActivityRealtimeBackgroundRefreshIntervalMillis + 1L)
        runCurrent()

        val listRead = f.inbox.reads.last()
        assertEquals(InboxFetchReason.RealtimeFallback to InboxRefreshTrigger.RowMissing, listRead.reason to listRead.trigger?.trigger)
        assertEquals(listOf(otherRow.key), f.store.state.value.items.map { it.key })
    }

    @Test
    fun aFailedRowReadFallsBackToTheListRead() = runTest {
        val f = loadedStore(listOf(snapshot(listOf(channelRow))))
        f.store.dispatch(live("m-9", "channel-1", seq = 9L))
        advanceTimeBy(ActivityRowRereadDelayMillis + 1L)
        runCurrent()
        f.inbox.rowAnswer!!.complete(DataResult.Error(ScreenError(code = "network")))
        advanceTimeBy(ActivityRealtimeBackgroundRefreshIntervalMillis + 1L)
        runCurrent()

        assertEquals(2, f.inbox.listReadCount)
        assertEquals(InboxRefreshTrigger.AmbiguousSeq, f.inbox.reads.last().trigger?.trigger)
    }

    @Test
    fun aLateOlderAnswerIsKeptOutAfterTheTimedHoldToo() = runTest {
        val lagging = channelRow.copy(unreadCount = 0)
        val on = loadedStore(listOf(snapshot(listOf(channelRow)), snapshot(listOf(lagging))))
        on.store.dispatch(live("m-11", "channel-1", seq = 11L))
        runCurrent()
        // Past the 90 s realtime hold, a list answer still at 10 lands.
        advanceTimeBy(ActivityLocalMutationHoldMillis + 30_000L)
        on.store.dispatch(SharedActivityIntent.Refresh)
        runCurrent()
        assertEquals(1 to "11", on.row(channelRow.key).let { it.unreadCount to it.latestActivitySeq })
        assertEquals("message m-11", on.row(channelRow.key).lastMessagePreview)

        // Flag off: the hold lapsed and the lagging answer wins (as before).
        val off = loadedStore(listOf(snapshot(listOf(channelRow)), snapshot(listOf(lagging))), rowPosition = false)
        off.store.dispatch(live("m-11", "channel-1", seq = 11L))
        runCurrent()
        advanceTimeBy(ActivityLocalMutationHoldMillis + 30_000L)
        off.store.dispatch(SharedActivityIntent.Refresh)
        runCurrent()
        assertEquals(0 to "10", off.row(channelRow.key).let { it.unreadCount to it.latestActivitySeq })
    }

    @Test
    fun aServerSwitchDropsTheRowState() = runTest {
        val f = loadedStore(listOf(snapshot(listOf(channelRow))))
        f.store.dispatch(live("m-11", "channel-1", seq = 11L))
        runCurrent()
        assertEquals(listOf("11"), f.store.rowPositionStateForTest("channel-1")?.applied?.map { it.seq })

        f.store.dispatch(SharedActivityIntent.ServerSelected("server-2"))
        runCurrent()
        // The other server's answer starts its row afresh: nothing applied here carries over.
        assertEquals(
            ActivityRowClientState(position = "10", applied = emptyList(), inversionObserved = false),
            f.store.rowPositionStateForTest("channel-1")
        )
    }

    @Test
    fun aHiddenListCountsLiveMessagesByTheSharedRule() = runTest {
        // B2: the hidden list's badge +1 (no row patch) follows the same rule as a shown row.
        fun TestScope.badgeAfter(rowPosition: Boolean, intent: SharedActivityIntent.ApplyRealtimeSummaryEvent): Int {
            val f = loadedStore(listOf(snapshot(listOf(channelRow))), rowPosition)
            f.store.dispatch(intent)
            runCurrent()
            return f.store.state.value.totalUnreadCount
        }
        val plain = SharedActivityIntent.ApplyRealtimeSummaryEvent(MessageNew(message("m-11", "channel-1", seq = 11L)), null)
        val muted = plain.copy(activityMuted = true)
        val ownCausedSystem =
            SharedActivityIntent.ApplyRealtimeSummaryEvent(
                MessageNew(
                    message("m-12", "channel-1", authorId = "system", seq = 12L).copy(messageType = MessageType.System),
                    systemProvenance = MessageSystemProvenance("channel.member_joined", causalActorType = "user", causalActorId = "user-1")
                ),
                null
            )

        assertEquals(1, badgeAfter(rowPosition = true, plain))
        assertEquals(0, badgeAfter(rowPosition = true, muted), "past the Activity mute: no count")
        assertEquals(0, badgeAfter(rowPosition = true, ownCausedSystem), "a system line the reader caused")
        // Flag off: today's author-only check counts both.
        assertEquals(1, badgeAfter(rowPosition = false, muted))
        assertEquals(1, badgeAfter(rowPosition = false, ownCausedSystem))
    }

    @Test
    fun theRowReadRequestNamesOnlyTheRows() = runTest {
        val network = RecordingInboxRowsClient()
        val repository = RealInboxRepository(InMemoryRaftAuthSessionStore(sampleSession()), network)

        val result =
            withInboxFetchReason(InboxFetchReason.PositionReread, InboxRefreshTriggerCount(InboxRefreshTrigger.RowUnjudged, 2)) {
                repository.loadRows(ServerId("server-1"), listOf("c-1", "t-2", "c-1"))
            }

        assertEquals(listOf(listOf("ids" to "c-1,t-2")), network.queries)
        assertEquals(
            listOf(
                mapOf(
                    "X-Raft-Fetch-Reason" to "position_reread",
                    "X-Raft-Fetch-Trigger" to "row_unjudged",
                    "X-Raft-Fetch-Coalesced" to "2",
                )
            ),
            network.headers
        )
        val rows = (result as DataResult.Content).data
        assertEquals(listOf("c-1"), rows.items.map { it.channelId.value })
        assertEquals(false, rows.hasMore)
    }

    private fun readState(channelId: String, maxReadSeq: Long) =
        SharedActivityIntent.ApplyRealtimeEvent(
            PerReceiverReadStateChanged(
                serverId = ServerId("server-1"),
                scopes = listOf(PerReceiverReadStateScope(ChannelId(channelId), maxReadSeq = maxReadSeq, readStateVersion = 2L))
            )
        )

    private class RecordingInboxRowsClient : RaftNetworkClient {
        val headers = mutableListOf<Map<String, String>>()
        val queries = mutableListOf<List<Pair<String, String>>>()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?
        ): HomeNetworkResponse = requestWithHeaders(priority, path, emptyMap(), method, query, serverIdOverride, bodyText)

        override suspend fun requestWithHeaders(
            priority: DataRequestPriority,
            path: String,
            headers: Map<String, String>,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?
        ): HomeNetworkResponse {
            check(path == "channels/inbox" && method == "GET") { "unexpected $method $path" }
            this.headers += headers
            queries += query
            // `{ items }` only (routes/channels.ts: an ids read carries no totals or paging).
            return HomeNetworkResponse(
                200,
                """{"items":[{"kind":"channel","channelId":"c-1","channelName":"general","channelType":"channel",""" +
                    """"unreadCount":1,"hasMention":false,"lastMessageId":"m-1","lastMessageAt":"2026-11-16T00:00:00.000Z",""" +
                    """"lastMessagePreview":"hi","latestActivitySeq":"12"}]}"""
            )
        }
    }
}
