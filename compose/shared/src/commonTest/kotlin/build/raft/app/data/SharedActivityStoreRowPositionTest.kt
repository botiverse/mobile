package build.raft.app.data

import build.raft.app.data.realtime.PerReceiverStateVersionStore
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MessageId
import build.raft.app.model.MessageMention
import build.raft.app.model.MessageNew
import build.raft.app.model.MessageSystemProvenance
import build.raft.app.model.MessageType
import build.raft.app.model.PerReceiverReadStateChanged
import build.raft.app.model.PerReceiverReadStateScope
import build.raft.app.model.ServerId
import build.raft.app.model.TaskId
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

    private fun TestScope.loadedStore(
        lists: List<InboxSnapshot>,
        rowPosition: Boolean = true,
        peopleActivityRows: Boolean = false,
        versions: PerReceiverStateVersionStore = PerReceiverStateVersionStore(InMemoryRaftKeyValueStore())
    ): Fixture {
        val inbox = RecordingInbox(lists)
        val store =
            activityStore(
                scope = backgroundScope,
                inboxRepository = inbox,
                perReceiverStateVersionStore = versions,
                realtimeBackgroundRefreshIntervalMillis = ActivityRealtimeBackgroundRefreshIntervalMillis,
                nowMillis = { testScheduler.currentTime },
                inboxInPlaceEnabled = { true },
                inboxRowPositionEnabled = { rowPosition },
                peopleActivityRowsEnabled = { peopleActivityRows },
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
    fun anAnswerComputedBeforeTheReadDoesNotBringTheUnreadBackAfterTheTimedHold() = runTest {
        // slock `answer_before_read_lands`: the row keeps the seq read (10). Past the read hold, an
        // answer the server computed before the read (still 1 unread at 10) lands: nothing it counted
        // is unread. A later read state moving the cursor back (Mark as Unread on another device)
        // lets the next answer count again.
        val unread = channelRow.copy(unreadCount = 1)
        val f = loadedStore(listOf(snapshot(listOf(unread)), snapshot(listOf(unread)), snapshot(listOf(unread))))
        f.store.dispatch(readState("channel-1", maxReadSeq = 10L))
        runCurrent()
        assertEquals(0, f.row(channelRow.key).unreadCount)
        advanceTimeBy(ActivityLocalMutationHoldMillis + 30_000L)
        f.store.dispatch(SharedActivityIntent.Refresh)
        runCurrent()
        assertEquals(0, f.row(channelRow.key).unreadCount)

        f.store.dispatch(readState("channel-1", maxReadSeq = 9L, version = 3L))
        runCurrent()
        f.store.dispatch(SharedActivityIntent.Refresh)
        runCurrent()
        assertEquals(1, f.row(channelRow.key).unreadCount)
    }

    @Test
    fun markAsUnreadHereClearsTheReadSeqSoTheNextAnswerCounts() = runTest {
        // slock `mark_unread_then_late_read_ack`: Mark as Unread clears the row's read seq (kept as a
        // ceiling), so an answer at the read position counts again.
        val unread = channelRow.copy(unreadCount = 1)
        val f = loadedStore(listOf(snapshot(listOf(unread)), snapshot(listOf(unread))))
        f.store.dispatch(readState("channel-1", maxReadSeq = 10L))
        runCurrent()
        f.store.dispatch(SharedActivityIntent.MarkUnread(f.row(channelRow.key)))
        runCurrent()
        assertEquals(null to "10", f.store.rowPositionReadSeqForTest("channel-1"))
        f.store.dispatch(SharedActivityIntent.Refresh)
        runCurrent()
        assertEquals(1, f.row(channelRow.key).unreadCount)
    }

    @Test
    fun aCursorMovedBackElsewhereWithoutTheFlagKeepsTheNextAnswerUnreadAlsoWhenHomeTookItFirst() = runTest {
        // slock #15307: Mark as Unread on another device with countable unread only moves the read cursor
        // back (no `markedUnread`). Home shares the read checkpoints and took the fact first, so it reaches
        // this store at the version already held: the row's read seq still goes, and the answer at the old
        // read (1 unread at 10) keeps its unread.
        val versions = PerReceiverStateVersionStore(InMemoryRaftKeyValueStore())
        val unread = channelRow.copy(unreadCount = 1)
        val f = loadedStore(listOf(snapshot(listOf(unread)), snapshot(listOf(unread))), versions = versions)
        f.store.dispatch(readState("channel-1", maxReadSeq = 10L))
        runCurrent()
        assertEquals("10" to null, f.store.rowPositionReadSeqForTest("channel-1"))
        advanceTimeBy(ActivityLocalMutationHoldMillis + 30_000L)

        versions.applyOrObserveReadStateFact(ServerId("server-1"), MemberId("user-1"), ChannelId("channel-1"), 3L, 9L)
        f.store.dispatch(readState("channel-1", maxReadSeq = 9L, version = 3L))
        runCurrent()
        assertEquals(null to "10", f.store.rowPositionReadSeqForTest("channel-1"))
        f.store.dispatch(SharedActivityIntent.Refresh)
        runCurrent()
        assertEquals(1, f.row(channelRow.key).unreadCount)
    }

    @Test
    fun aReadStateBegunAgainAfterAccessWasLostDropsTheReadSeq() = runTest {
        // slock #15307: access lost deletes the cursor row; an answer serves it `absent` and the read state
        // starts again from v1. The read seq of the old cursor goes (the answer's unread at 10 counts) and
        // the new v1 is accepted, not judged stale against v5.
        val versions = PerReceiverStateVersionStore(InMemoryRaftKeyValueStore())
        val absent = channelRow.copy(unreadCount = 1, readStateAbsent = true)
        val f = loadedStore(listOf(snapshot(listOf(channelRow)), snapshot(listOf(absent))), versions = versions)
        f.store.dispatch(readState("channel-1", maxReadSeq = 10L, version = 5L))
        runCurrent()
        assertEquals("10" to null, f.store.rowPositionReadSeqForTest("channel-1"))
        advanceTimeBy(ActivityLocalMutationHoldMillis + 30_000L)

        f.store.dispatch(SharedActivityIntent.Refresh)
        runCurrent()
        assertEquals(1, f.row(channelRow.key).unreadCount)
        assertEquals(null to null, f.store.rowPositionReadSeqForTest("channel-1"))
        assertEquals(0L, versions.readReadStateCheckpoint(ServerId("server-1"), MemberId("user-1"), ChannelId("channel-1")).version)

        f.store.dispatch(readState("channel-1", maxReadSeq = 4L, version = 1L))
        runCurrent()
        assertEquals("4" to null, f.store.rowPositionReadSeqForTest("channel-1"))
    }

    @Test
    fun anAbsentAnswerRequestedBeforeAReadDoesNotUndoIt() = runTest {
        // Web `supersededBySocket`: the list read starts, then the first read of the conversation lands (v1);
        // the answer, computed before it, still says `absent`. The read and its checkpoint stand.
        val versions = PerReceiverStateVersionStore(InMemoryRaftKeyValueStore())
        val absent = channelRow.copy(unreadCount = 1, readStateAbsent = true)
        val f = loadedStore(listOf(snapshot(listOf(channelRow)), snapshot(listOf(absent))), versions = versions)
        f.store.dispatch(readState("channel-1", maxReadSeq = 10L, version = 1L))
        f.store.dispatch(SharedActivityIntent.Refresh)
        runCurrent()
        assertEquals("10" to null, f.store.rowPositionReadSeqForTest("channel-1"))
        assertEquals(1L, versions.readReadStateCheckpoint(ServerId("server-1"), MemberId("user-1"), ChannelId("channel-1")).version)
        assertEquals(0, f.row(channelRow.key).unreadCount)
    }

    @Test
    fun anAbsentAnswerStillClearsWhenOnlyALocalReadLandedMeanwhile() = runTest {
        // Scout (#4924): only a newer read-state version accepted after the read started stops the clear. A
        // local read ack accepts none: the absent answer still drops the old checkpoint (v5), so the next v1,
        // v2 (a Mark as Unread elsewhere) are not dropped as stale.
        val versions = PerReceiverStateVersionStore(InMemoryRaftKeyValueStore())
        val absent = channelRow.copy(unreadCount = 1, readStateAbsent = true)
        val f = loadedStore(listOf(snapshot(listOf(channelRow)), snapshot(listOf(absent))), versions = versions)
        f.store.dispatch(readState("channel-1", maxReadSeq = 10L, version = 5L))
        runCurrent()
        advanceTimeBy(ActivityLocalMutationHoldMillis + 30_000L)
        f.store.dispatch(SharedActivityIntent.Refresh)
        f.store.dispatch(
            SharedActivityIntent.ApplyReadReceiptResult(
                ReadReceiptRequest.All(ChannelId("channel-1"), ReadReceiptOwner("production", "user-1", "server-1"), revision = 1L),
                succeeded = true,
                maxReadSeq = 12L
            )
        )
        assertEquals("12" to null, f.store.rowPositionReadSeqForTest("channel-1"))
        runCurrent()
        assertEquals(0L, versions.readReadStateCheckpoint(ServerId("server-1"), MemberId("user-1"), ChannelId("channel-1")).version)
        f.store.dispatch(readState("channel-1", maxReadSeq = 11L, version = 2L))
        runCurrent()
        assertEquals(2L, versions.readReadStateCheckpoint(ServerId("server-1"), MemberId("user-1"), ChannelId("channel-1")).version)
    }

    @Test
    fun aReadFoldedFromTheLedgerReadsTheAppliedLiveSeqsItCovers() = runTest {
        // Scout (#4924): row at 10, live 11 and 12 applied; Home takes the read of 12 first, so this store
        // only folds it. A late answer at 10 is read by the read seq, and 11 and 12 do not count again.
        val versions = PerReceiverStateVersionStore(InMemoryRaftKeyValueStore())
        val late = channelRow.copy(unreadCount = 1)
        val f = loadedStore(listOf(snapshot(listOf(channelRow)), snapshot(listOf(late))), versions = versions)
        f.store.dispatch(live("m-11", "channel-1", seq = 11L))
        f.store.dispatch(live("m-12", "channel-1", seq = 12L))
        runCurrent()
        assertEquals(2, f.row(channelRow.key).unreadCount)
        versions.applyOrObserveReadStateFact(ServerId("server-1"), MemberId("user-1"), ChannelId("channel-1"), 2L, 12L)
        f.store.dispatch(readState("channel-1", maxReadSeq = 12L))
        runCurrent()
        assertEquals("12" to null, f.store.rowPositionReadSeqForTest("channel-1"))
        assertEquals(listOf(false, false), f.store.rowPositionStateForTest("channel-1")?.applied?.map { it.counts })
        f.store.dispatch(SharedActivityIntent.Refresh)
        runCurrent()
        assertEquals(0, f.row(channelRow.key).unreadCount)
    }

    @Test
    fun aLocalReadClearsAnAtOnAnUncountedMessageAndAMentionOnlyRowKeepsIt() = runTest {
        // Web #14805: a task conversation counts chat replies only, while the server's @ is any mention
        // past the read cursor, so a lifecycle line mentioning the reader lists the row with an @ and no
        // unread. Reading the conversation here takes the @ and its jump target. A mention-only row
        // (no count, its first unread is the @) keeps its @, as the server does.
        val uncounted = channelRow.copy(hasMention = true, firstMentionMessageId = MessageId("line-1"))
        val mentionOnly =
            otherRow.copy(hasMention = true, firstUnreadMessageId = MessageId("m-5"), firstMentionMessageId = MessageId("m-5"))
        val owner = ReadReceiptOwner("production", "user-1", "server-1")
        for (rowPosition in listOf(false, true)) {
            val f = loadedStore(listOf(snapshot(listOf(uncounted, mentionOnly))), rowPosition)
            listOf("channel-1", "channel-2").forEachIndexed { index, channelId ->
                f.store.dispatch(
                    SharedActivityIntent.ApplyReadReceiptPrediction(
                        ReadReceiptPrediction(owner, ChannelId(channelId), index + 1L, ReadReceiptTarget.All)
                    )
                )
            }
            runCurrent()
            val label = "inbox_row_position_v1=$rowPosition"
            assertEquals(
                listOf(Triple(0, false, null), Triple(0, true, MessageId("m-5"))),
                listOf(f.row(uncounted.key), f.row(mentionOnly.key)).map { Triple(it.unreadCount, it.hasMention, it.firstMentionMessageId) },
                label
            )
            if (!rowPosition) continue

            // Flag on: the persisted reads leave the cleared row as the read made it (nothing to
            // re-read); the mention-only row still re-reads, its @ being the server's to answer.
            f.store.dispatch(readState("channel-1", maxReadSeq = 10L))
            f.store.dispatch(readState("channel-2", maxReadSeq = 5L))
            advanceTimeBy(ActivityRowRereadDelayMillis + 1L)
            runCurrent()
            assertEquals(false, f.row(uncounted.key).hasMention, label)
            assertEquals(listOf(listOf("channel-2")), f.inbox.rowReads.map { it.rowIds }, label)
        }
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
    fun aQuietTaskLifecycleLineLeavesAPersonsTaskRowInPlaceWithFlagOnAndOff() = runTest {
        // Step 2c (raft-design #1440, slock #16828/#16837): a task lifecycle line written quiet
        // for people leaves a person's task row untouched (place, preview, seq, read state, no
        // re-read) unless it mentions them; non-quiet lines and chat messages move it as before.
        val taskRow =
            threadItem("task-conv-1", unreadCount = 1).copy(
                taskConversation = InboxTaskConversation(taskId = TaskId("task-1"), taskTitle = "Ship it"),
                latestActivityPreview = "please look",
                latestActivityAt = "2026-11-25T09:00:00.000Z",
                latestActivityMessageId = MessageId("m-100"),
                latestActivitySeq = "100",
                readStateLatestActivitySeq = "100",
                replyCount = 1,
            )

        fun claimLine(
            id: String,
            seq: Long,
            quietForPeople: Boolean,
            mentions: List<MessageMention> = emptyList(),
        ): SharedActivityIntent.ApplyRealtimeEvent =
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(
                    message(id, channelId = "task-conv-1", authorId = "system", body = "Agent took the task", seq = seq).copy(
                        messageType = MessageType.System,
                        mentions = mentions,
                        quietForPeople = quietForPeople,
                    ),
                    systemProvenance =
                        MessageSystemProvenance(
                            systemSubtype = "task.lifecycle_thread",
                            causalActorType = "agent",
                            causalActorId = "agent-1",
                            countsUnread = true,
                            quietForPeople = quietForPeople,
                        ),
                )
            )

        for (rowPosition in listOf(false, true)) {
            val label = "inbox_row_position_v1=$rowPosition"
            val f = loadedStore(listOf(snapshot(listOf(channelRow, taskRow))), rowPosition = rowPosition)

            // Quiet claim line without mention: row stays at index 1 with identical preview/seq/unread and no re-read.
            f.store.dispatch(claimLine("m-101", seq = 101L, quietForPeople = true))
            runCurrent()
            advanceTimeBy(maxOf(ActivityRealtimeBackgroundRefreshIntervalMillis, ActivityRowRereadDelayMillis) + 1L)
            runCurrent()
            assertEquals(1, f.store.state.value.items.indexOfFirst { it.key == taskRow.key }, label)
            assertEquals(
                listOf("100", "please look", 1, false, 1),
                f.row(taskRow.key).let { listOf(it.latestActivitySeq, it.latestActivityPreview, it.unreadCount, it.hasMention, it.replyCount) },
                label,
            )
            assertEquals(emptyList(), f.inbox.rowReads, label)
            assertEquals(1, f.inbox.listReadCount, label)

            // Quiet line mentioning the viewer: moves to index 0, lights `@`, does not add unread.
            f.store.dispatch(
                claimLine(
                    "m-102",
                    seq = 102L,
                    quietForPeople = true,
                    mentions = listOf(MessageMention(type = "user", id = "user-1")),
                )
            )
            runCurrent()
            assertEquals(0, f.store.state.value.items.indexOfFirst { it.key == taskRow.key }, label)
            assertEquals("102", f.row(taskRow.key).latestActivitySeq, label)
            assertEquals(true, f.row(taskRow.key).hasMention, label)
            assertEquals(MessageId("m-102"), f.row(taskRow.key).firstMentionMessageId, label)
            assertEquals(1, f.row(taskRow.key).unreadCount, label)

            // Non-quiet claim line (flag off on server / older event): moves the row as before.
            val nonQuiet = loadedStore(listOf(snapshot(listOf(channelRow, taskRow))), rowPosition = rowPosition)
            nonQuiet.store.dispatch(claimLine("m-101", seq = 101L, quietForPeople = false))
            runCurrent()
            assertEquals(0, nonQuiet.store.state.value.items.indexOfFirst { it.key == taskRow.key }, label)
            assertEquals("101", nonQuiet.row(taskRow.key).latestActivitySeq, label)
            assertEquals(1, nonQuiet.row(taskRow.key).unreadCount, label)

            // Chat reply on the task row: moves to top and bumps unread.
            val chatStore = loadedStore(listOf(snapshot(listOf(channelRow, taskRow))), rowPosition = rowPosition)
            chatStore.store.dispatch(live("m-101", "task-conv-1", seq = 101L))
            runCurrent()
            assertEquals(0, chatStore.store.state.value.items.indexOfFirst { it.key == taskRow.key }, label)
            assertEquals("101", chatStore.row(taskRow.key).latestActivitySeq, label)
            assertEquals(2, chatStore.row(taskRow.key).unreadCount, label)
        }
    }

    @Test
    fun quietLinesLeaveThreadAndChannelRowsInPlaceAndDoNotReopenDoneRows() = runTest {
        val newerRow =
            activityItem("c-top", unreadCount = 0).copy(
                lastMessageId = MessageId("m-200"),
                lastMessageAt = "2026-11-25T10:00:00.000Z",
                latestActivitySeq = "200",
                readStateLatestActivitySeq = "200",
            )
        val quietTargetChannelRow =
            activityItem("c-1", unreadCount = 1).copy(
                lastMessageId = MessageId("m-100"),
                lastMessagePreview = "original channel message",
                lastMessageAt = "2026-11-25T09:00:00.000Z",
                latestActivitySeq = "100",
                readStateLatestActivitySeq = "100",
            )
        val threadRow =
            threadItem("th-1", unreadCount = 1).copy(
                latestActivityMessageId = MessageId("m-50"),
                latestActivityPreview = "original thread reply",
                latestActivityAt = "2026-11-25T09:00:00.000Z",
                latestActivitySeq = "50",
                readStateLatestActivitySeq = "50",
                replyCount = 1,
            )

        fun quietChannelLine(id: String, channelId: String, seq: Long, mentions: List<MessageMention> = emptyList()) =
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(
                    message(id, channelId = channelId, authorId = "system", body = "Converted to task", seq = seq).copy(
                        messageType = MessageType.System,
                        mentions = mentions,
                        quietForPeople = true,
                    ),
                    systemProvenance =
                        MessageSystemProvenance(
                            systemSubtype = "task.converted_summary",
                            causalActorType = "agent",
                            causalActorId = "agent-1",
                            countsUnread = true,
                            quietForPeople = true,
                        ),
                )
            )

        fun quietThreadLine(id: String, seq: Long, mentions: List<MessageMention> = emptyList()) =
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(
                    message(id, channelId = "th-1", authorId = "system", body = "Task created", seq = seq).copy(
                        messageType = MessageType.System,
                        mentions = mentions,
                        quietForPeople = true,
                    ),
                    systemProvenance =
                        MessageSystemProvenance(
                            systemSubtype = "task.created_summary",
                            causalActorType = "agent",
                            causalActorId = "agent-1",
                            countsUnread = true,
                            quietForPeople = true,
                        ),
                )
            )

        for (rowPosition in listOf(false, true)) {
            val label = "inbox_row_position_v1=$rowPosition"
            val f = loadedStore(listOf(snapshot(listOf(newerRow, quietTargetChannelRow, threadRow))), rowPosition = rowPosition)

            // Quiet channel line without mention: row stays at index 1, unchanged, no re-read.
            f.store.dispatch(quietChannelLine("m-101", "c-1", seq = 101L))
            // Quiet thread line without mention: row stays at index 2, unchanged, no re-read.
            f.store.dispatch(quietThreadLine("m-51", seq = 51L))
            // Quiet line on a Done (unlisted) channel: stays unlisted, no re-read.
            f.store.dispatch(quietChannelLine("m-301", "c-done", seq = 301L))
            runCurrent()
            advanceTimeBy(maxOf(ActivityRealtimeBackgroundRefreshIntervalMillis, ActivityRowRereadDelayMillis) + 1L)
            runCurrent()
            assertEquals(1, f.store.state.value.items.indexOfFirst { it.key == quietTargetChannelRow.key }, label)
            assertEquals("100", f.row(quietTargetChannelRow.key).latestActivitySeq, label)
            assertEquals("original channel message", f.row(quietTargetChannelRow.key).lastMessagePreview, label)
            assertEquals(1, f.row(quietTargetChannelRow.key).unreadCount, label)
            assertEquals(2, f.store.state.value.items.indexOfFirst { it.key == threadRow.key }, label)
            assertEquals("50", f.row(threadRow.key).latestActivitySeq, label)
            assertEquals("original thread reply", f.row(threadRow.key).latestActivityPreview, label)
            assertEquals(1, f.row(threadRow.key).replyCount, label)
            assertEquals(emptyList(), f.inbox.rowReads, label)
            assertEquals(1, f.inbox.listReadCount, label)

            // Quiet channel line mentioning viewer: moves to index 0, lights @, does not bump unread.
            f.store.dispatch(
                quietChannelLine("m-102", "c-1", seq = 102L, mentions = listOf(MessageMention("user", "user-1")))
            )
            runCurrent()
            assertEquals(0, f.store.state.value.items.indexOfFirst { it.key == quietTargetChannelRow.key }, label)
            assertEquals("102", f.row(quietTargetChannelRow.key).latestActivitySeq, label)
            assertEquals(true, f.row(quietTargetChannelRow.key).hasMention, label)
            assertEquals(1, f.row(quietTargetChannelRow.key).unreadCount, label)

            // Quiet thread line mentioning viewer: moves to index 0, lights @, does not bump unread.
            f.store.dispatch(
                quietThreadLine("m-52", seq = 52L, mentions = listOf(MessageMention("user", "user-1")))
            )
            runCurrent()
            assertEquals(0, f.store.state.value.items.indexOfFirst { it.key == threadRow.key }, label)
            assertEquals("52", f.row(threadRow.key).latestActivitySeq, label)
            assertEquals(true, f.row(threadRow.key).hasMention, label)
            assertEquals(1, f.row(threadRow.key).unreadCount, label)
        }
    }

    @Test
    fun peopleActivityRowsFlagGatesHistoryOnlyLinesOnChannelAndThreadRowsAcrossBothPaths() = runTest {
        val newerRow =
            activityItem("c-newer").copy(
                latestActivitySeq = "200",
                readStateLatestActivitySeq = "200",
                unreadCount = 0,
            )
        val targetChannelRow =
            activityItem("c-1").copy(
                lastMessagePreview = "original channel message",
                latestActivitySeq = "100",
                readStateLatestActivitySeq = "100",
                unreadCount = 1,
            )
        val threadRow =
            threadItem("th-1", unreadCount = 1).copy(
                latestActivityPreview = "original thread reply",
                latestActivitySeq = "50",
                readStateLatestActivitySeq = "50",
                replyCount = 1,
            )

        fun historyOnly(id: String, channelId: String, seq: Long, mentions: List<MessageMention> = emptyList()) =
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(
                    message(id, channelId = channelId, authorId = "system", body = "History-only notice", seq = seq).copy(
                        messageType = MessageType.System,
                        mentions = mentions,
                        countsUnread = false,
                    ),
                    systemProvenance =
                        MessageSystemProvenance(
                            systemSubtype = "channel.history_notice",
                            causalActorType = "agent",
                            causalActorId = "agent-1",
                            countsUnread = false,
                        ),
                )
            )

        for (rowPosition in listOf(false, true)) {
            // Flag ON: history-only line without mention leaves channel and thread rows untouched, no re-read.
            val on =
                loadedStore(
                    listOf(snapshot(listOf(newerRow, targetChannelRow, threadRow))),
                    rowPosition = rowPosition,
                    peopleActivityRows = true,
                )
            on.store.dispatch(historyOnly("m-101", "c-1", seq = 101L))
            on.store.dispatch(historyOnly("m-51", "th-1", seq = 51L))
            on.store.dispatch(historyOnly("m-301", "c-done", seq = 301L))
            runCurrent()
            advanceTimeBy(maxOf(ActivityRealtimeBackgroundRefreshIntervalMillis, ActivityRowRereadDelayMillis) + 1L)
            runCurrent()
            assertEquals(1, on.store.state.value.items.indexOfFirst { it.key == targetChannelRow.key })
            assertEquals("100", on.row(targetChannelRow.key).latestActivitySeq)
            assertEquals(2, on.store.state.value.items.indexOfFirst { it.key == threadRow.key })
            assertEquals("50", on.row(threadRow.key).latestActivitySeq)
            assertEquals(emptyList(), on.inbox.rowReads)
            assertEquals(1, on.inbox.listReadCount)

            // Flag ON + personal mention: still moves to index 0 and lights @.
            on.store.dispatch(historyOnly("m-102", "c-1", seq = 102L, mentions = listOf(MessageMention("user", "user-1"))))
            runCurrent()
            assertEquals(0, on.store.state.value.items.indexOfFirst { it.key == targetChannelRow.key })
            assertEquals("102", on.row(targetChannelRow.key).latestActivitySeq)
            assertEquals(true, on.row(targetChannelRow.key).hasMention)

            // Flag OFF: history-only line still moves channel row as before (#17075 rule).
            val off =
                loadedStore(
                    listOf(snapshot(listOf(newerRow, targetChannelRow, threadRow))),
                    rowPosition = rowPosition,
                    peopleActivityRows = false,
                )
            off.store.dispatch(historyOnly("m-101", "c-1", seq = 101L))
            runCurrent()
            assertEquals("101", off.row(targetChannelRow.key).latestActivitySeq)
        }
    }

    private fun readState(channelId: String, maxReadSeq: Long, version: Long = 2L) =
        SharedActivityIntent.ApplyRealtimeEvent(
            PerReceiverReadStateChanged(
                serverId = ServerId("server-1"),
                scopes = listOf(PerReceiverReadStateScope(ChannelId(channelId), maxReadSeq = maxReadSeq, readStateVersion = version))
            )
        )
}
