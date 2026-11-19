package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.PerReceiverReadStateChanged
import build.raft.app.model.PerReceiverReadStateScope
import build.raft.app.network.sync.ReplyPreview
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadUpdated
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `inbox_row_position_v1` (#triage:#244, B3): web's sampled `position_audit` row check
 * (slock packages/web/src/store/inboxRowPosition.ts `auditLiveApplied` / `flushRowRereads` /
 * `applyRowAnswer`, inboxInPlaceUnread.ts `checkRowAnswerDrift` / `recordAuditNotJudged` /
 * `emitExpired`, inboxListRowLowered.ts) replaces the once-a-minute in-place reconcile. Flag off:
 * the minute reconcile, as before.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedActivityStorePositionAuditTest {
    private data class Read(val reason: InboxFetchReason?, val trigger: InboxRefreshTriggerCount?, val rowIds: List<String>?)

    private class RecordingInbox(private val lists: List<InboxSnapshot>) : InboxRepository by FakeInboxRepository(lists) {
        val reads = mutableListOf<Read>()
        private var listAnswered = 0
        var rowAnswer: CompletableDeferred<DataResult<InboxSnapshot>>? = null

        override suspend fun loadInitial(serverId: ServerId, filter: InboxFilter, limit: Int): DataResult<InboxSnapshot> {
            val context = currentCoroutineContext()[InboxFetchReasonContext]
            reads += Read(context?.reason, context?.trigger, rowIds = null)
            return DataResult.Content(lists.getOrElse(listAnswered++) { lists.last() })
        }

        override suspend fun loadRows(serverId: ServerId, rowIds: List<String>): DataResult<InboxSnapshot> {
            val context = currentCoroutineContext()[InboxFetchReasonContext]
            reads += Read(context?.reason, context?.trigger, rowIds)
            val answer = CompletableDeferred<DataResult<InboxSnapshot>>()
            rowAnswer = answer
            return answer.await()
        }

        val rowReads: List<Read> get() = reads.filter { it.rowIds != null }
        val listReads: List<Read> get() = reads.filter { it.rowIds == null }
    }

    private class Fixture(
        val store: SharedActivityStore,
        val inbox: RecordingInbox,
        val checks: MutableList<Pair<String, ActivityInPlaceCheck>>,
        val lowered: MutableList<ActivityListRowLowered>,
        val foreground: MutableStateFlow<Boolean>
    ) {
        fun row(key: String): InboxItem = store.state.value.items.first { it.key == key }
        val audits: List<ActivityInPlaceCheck> get() = checks.map { it.second }.filter { it.trigger == InboxRefreshTrigger.PositionAudit }
    }

    private fun TestScope.loadedStore(lists: List<InboxSnapshot>, rowPosition: Boolean = true): Fixture {
        val inbox = RecordingInbox(lists)
        val checks = mutableListOf<Pair<String, ActivityInPlaceCheck>>()
        val lowered = mutableListOf<ActivityListRowLowered>()
        val foreground = MutableStateFlow(true)
        val store =
            activityStore(
                scope = backgroundScope,
                inboxRepository = inbox,
                realtimeBackgroundRefreshIntervalMillis = ActivityRealtimeBackgroundRefreshIntervalMillis,
                nowMillis = { testScheduler.currentTime },
                appForeground = foreground,
                inboxInPlaceEnabled = { true },
                inboxRowPositionEnabled = { rowPosition },
                inPlaceCheckSink = { serverId, check -> checks += serverId to check },
                listRowLoweredSink = { _, row -> lowered += row }
            )
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        return Fixture(store, inbox, checks, lowered, foreground)
    }

    private val channelRow =
        activityItem("channel-1").copy(latestActivitySeq = "10", readStateLatestActivitySeq = "10")

    private fun live(id: String, channelId: String, seq: Long) =
        SharedActivityIntent.ApplyRealtimeEvent(MessageNew(message(id, channelId = channelId, seq = seq)))

    /** [count] live messages on channel-1 after seq 10 (each applied in place: 11, 12, ...). */
    private fun TestScope.applyLive(f: Fixture, count: Int, from: Long = 11L) {
        repeat(count) { index -> f.store.dispatch(live("m-${from + index}", "channel-1", seq = from + index)) }
        runCurrent()
    }

    private fun TestScope.untilRowRead() {
        advanceTimeBy(ActivityRowRereadDelayMillis + 1L)
        runCurrent()
    }

    /** The row as the server would serve it after the 20 live messages (11..30). */
    private val servedAt30 = channelRow.copy(unreadCount = 20, latestActivitySeq = "30", readStateLatestActivitySeq = "30")

    @Test
    fun theTwentiethAppliedLiveMessageAuditsItsRowOnce() = runTest {
        val f = loadedStore(listOf(snapshot(listOf(channelRow))))
        applyLive(f, 19)
        untilRowRead()
        assertEquals(emptyList(), f.inbox.rowReads, "1 in 20: the 19 before read nothing")

        applyLive(f, 1, from = 30L)
        untilRowRead()
        assertEquals(
            listOf(Read(InboxFetchReason.PositionAudit, InboxRefreshTriggerCount(InboxRefreshTrigger.PositionAudit), listOf("channel-1"))),
            f.inbox.rowReads
        )
        f.inbox.rowAnswer!!.complete(DataResult.Content(snapshot(listOf(servedAt30)).copy(inboxBackend = "risingwave")))
        runCurrent()

        val (serverId, check) = f.checks.single()
        assertEquals("server-1", serverId)
        assertEquals(
            ActivityInPlaceCheck(
                entityId = "channel:channel-1",
                trigger = InboxRefreshTrigger.PositionAudit,
                rowExisted = true,
                result = "match",
                outcome = ActivityInPlaceCheckOutcome.Noop,
                sampleRate = 1,
                outcomeDetail =
                    "trigger=position_audit;existed=1;result=match;unread=20/20;mention=0/0;position=30/30" +
                        ";reread=position;age_ms=0;read_state=0",
                timestampMillis = check.timestampMillis,
                scope = "row",
                reread = "position",
                backend = "risingwave"
            ),
            check
        )

        // The 40th audits again; the 21st..39th do not.
        applyLive(f, 19, from = 31L)
        untilRowRead()
        assertEquals(1, f.inbox.rowReads.size)
        applyLive(f, 1, from = 50L)
        untilRowRead()
        assertEquals(2, f.inbox.rowReads.size)
    }

    @Test
    fun theFlagOffKeepsTheMinuteReconcileAndTheFlagOnDropsIt() = runTest {
        val off = loadedStore(listOf(snapshot(listOf(channelRow))), rowPosition = false)
        applyLive(off, 20)
        advanceTimeBy(ActivityInPlaceReconcileIntervalMillis + 1L)
        runCurrent()
        assertEquals(emptyList(), off.inbox.rowReads, "flag off: no audit")
        assertEquals(InboxFetchReason.InPlaceReconcile, off.inbox.listReads.last().reason, "flag off: the minute reconcile reads")
        assertEquals(emptyList(), off.checks)

        val on = loadedStore(listOf(snapshot(listOf(channelRow))))
        applyLive(on, 20)
        advanceTimeBy(ActivityInPlaceReconcileIntervalMillis + 1L)
        runCurrent()
        assertEquals(listOf(InboxFetchReason.Initial), on.inbox.listReads.map { it.reason }, "flag on: no minute reconcile")
        assertEquals(listOf(InboxFetchReason.PositionAudit), on.inbox.rowReads.map { it.reason })
    }

    @Test
    fun anAuditThatDisagreesIsAConflict() = runTest {
        val f = loadedStore(listOf(snapshot(listOf(channelRow))))
        applyLive(f, 20)
        untilRowRead()
        f.inbox.rowAnswer!!.complete(DataResult.Content(snapshot(listOf(servedAt30.copy(unreadCount = 19)))))
        runCurrent()

        val check = f.audits.single()
        assertEquals(ActivityInPlaceCheckOutcome.Conflict to "unread", check.outcome to check.result)
        assertEquals(1, check.sampleRate)
        assertEquals(null, check.backend, "no X-Raft-Inbox-Backend: not sent")
        assertEquals(19, f.row(channelRow.key).unreadCount, "the answer is taken")
    }

    @Test
    fun anAuditedRowThatMovesBeforeItsAnswerIsNotJudged() = runTest {
        val f = loadedStore(listOf(snapshot(listOf(channelRow))))
        applyLive(f, 20)
        untilRowRead()
        applyLive(f, 1, from = 31L)
        f.inbox.rowAnswer!!.complete(DataResult.Content(snapshot(listOf(servedAt30))))
        runCurrent()

        val check = f.audits.single()
        assertEquals("not_judged" to ActivityAuditNotJudgedCause.Moved, check.result to check.notJudgedCause)
        assertEquals(
            "trigger=position_audit;existed=1;result=not_judged;cause=moved;unread=21/20;mention=0/0;position=31/30" +
                ";reread=position;age_ms=0;read_state=0",
            check.outcomeDetail
        )
    }

    @Test
    fun aRereadAskedWhileTheAuditWaitsSupersedesIt() = runTest {
        val f = loadedStore(listOf(snapshot(listOf(channelRow))))
        applyLive(f, 20)
        // At or below the row's position, never applied: ambiguous, the row is re-read.
        f.store.dispatch(live("m-5", "channel-1", seq = 5L))
        runCurrent()
        assertEquals(ActivityAuditNotJudgedCause.SupersededByReread, f.audits.single().notJudgedCause)

        untilRowRead()
        assertEquals(
            listOf(Read(InboxFetchReason.PositionReread, InboxRefreshTriggerCount(InboxRefreshTrigger.AmbiguousSeq), listOf("channel-1"))),
            f.inbox.rowReads
        )
    }

    @Test
    fun aFailedAuditReadIsNotJudgedAndReadsNoList() = runTest {
        val f = loadedStore(listOf(snapshot(listOf(channelRow))))
        applyLive(f, 20)
        untilRowRead()
        f.inbox.rowAnswer!!.complete(DataResult.Error(ScreenError(code = "network")))
        advanceTimeBy(ActivityRealtimeBackgroundRefreshIntervalMillis + 1L)
        runCurrent()

        assertEquals(ActivityAuditNotJudgedCause.RequestFailed, f.audits.single().notJudgedCause)
        assertEquals(1, f.inbox.listReads.size, "an audit's failure is not a re-read's")
    }

    @Test
    fun anAuditedRowTheAnswerLeavesOutIsNotJudgedAndTheListIsRead() = runTest {
        val f = loadedStore(listOf(snapshot(listOf(channelRow))))
        applyLive(f, 20)
        untilRowRead()
        f.inbox.rowAnswer!!.complete(DataResult.Content(snapshot(emptyList())))
        advanceTimeBy(ActivityRealtimeBackgroundRefreshIntervalMillis + 1L)
        runCurrent()

        assertEquals(ActivityAuditNotJudgedCause.RowMissing, f.audits.single().notJudgedCause)
        assertEquals(InboxRefreshTrigger.RowMissing, f.inbox.listReads.last().trigger?.trigger)
    }

    @Test
    fun anAuditDueInTheBackgroundIsNotJudged() = runTest {
        val f = loadedStore(listOf(snapshot(listOf(channelRow))))
        applyLive(f, 20)
        f.foreground.value = false
        untilRowRead()

        assertEquals(ActivityAuditNotJudgedCause.PageHidden, f.audits.single().notJudgedCause)
        assertEquals(emptyList(), f.inbox.rowReads)
    }

    @Test
    fun aReadOfTheRowWhileTheAuditIsOutIsNotJudged() = runTest {
        val f = loadedStore(listOf(snapshot(listOf(channelRow))))
        applyLive(f, 20)
        untilRowRead()
        f.store.dispatch(readState("channel-1", maxReadSeq = 30L))
        runCurrent()
        f.inbox.rowAnswer!!.complete(DataResult.Content(snapshot(listOf(servedAt30))))
        runCurrent()

        val check = f.audits.single()
        assertEquals(ActivityAuditNotJudgedCause.ReadChanged, check.notJudgedCause)
    }

    @Test
    fun aServerSwitchEndsAWaitingAuditNotJudged() = runTest {
        val f = loadedStore(listOf(snapshot(listOf(channelRow))))
        applyLive(f, 20)
        f.store.dispatch(SharedActivityIntent.ServerSelected("server-2"))
        runCurrent()

        val (serverId, check) = f.checks.single()
        assertEquals("server-1" to ActivityAuditNotJudgedCause.ServerSwitched, serverId to check.notJudgedCause)
    }

    @Test
    fun anAuditAnswerBehindTheRowJudgesUpToItAndReadsTheRowOnceMore() = runTest {
        val f = loadedStore(listOf(snapshot(listOf(channelRow))))
        applyLive(f, 20)
        untilRowRead()
        // The view a moment behind: the answer is at 29 with 19 unread.
        f.inbox.rowAnswer!!.complete(
            DataResult.Content(snapshot(listOf(servedAt30.copy(unreadCount = 19, latestActivitySeq = "29", readStateLatestActivitySeq = "29"))))
        )
        runCurrent()
        val first = f.audits.single()
        assertEquals("match_up_to_answer", first.result)
        assertTrue(first.outcomeDetail.contains(";position=30/29;answer_behind=1;merged=20/30;kept_state="), first.outcomeDetail)

        advanceTimeBy(ActivityAuditFollowupDelayMillis + 1L)
        untilRowRead()
        assertEquals(listOf(InboxFetchReason.PositionAudit, InboxFetchReason.PositionAudit), f.inbox.rowReads.map { it.reason })
        f.inbox.rowAnswer!!.complete(DataResult.Content(snapshot(listOf(servedAt30))))
        runCurrent()
        val followup = f.audits.last()
        assertEquals("match", followup.result)
        assertTrue(followup.outcomeDetail.contains(";position=30/30;followup=1;"), followup.outcomeDetail)
    }

    @Test
    fun aReplyGapRereadsTheThreadRowOnly() = runTest {
        val threadRow = threadItem("thread-1").copy(replyCount = 2, latestActivitySeq = "20", readStateLatestActivitySeq = "20")
        val f = loadedStore(listOf(snapshot(listOf(threadRow))))
        f.store.dispatch(reply("r-23", seq = 23L, replyCount = 5))
        untilRowRead()

        assertEquals(
            listOf(Read(InboxFetchReason.PositionReread, InboxRefreshTriggerCount(InboxRefreshTrigger.RowReplyGap), listOf("thread-1"))),
            f.inbox.rowReads
        )
        assertEquals(1, f.inbox.listReads.size, "not the list")
    }

    @Test
    fun aPatchedRowNoAnswerJudgedInTimeIsSentExpired() = runTest {
        fun TestScope.expiredAfter(rowPosition: Boolean): List<ActivityInPlaceCheck> {
            val f = loadedStore(listOf(snapshot(listOf(channelRow))), rowPosition)
            f.store.dispatch(live("m-11", "channel-1", seq = 11L))
            runCurrent()
            advanceTimeBy(ActivityInPlaceCheckTtlMillis + 1_000L)
            runCurrent()
            f.store.dispatch(SharedActivityIntent.Refresh)
            runCurrent()
            return f.checks.map { it.second }
        }
        // Web emitExpired (#triage:#244): sent with the flag off too; only the position rule's `reread` and cause differ.
        listOf(true, false).forEach { rowPosition ->
            val check = expiredAfter(rowPosition).single { it.result == "expired" }
            assertEquals(ActivityInPlaceCheckOutcome.Noop, check.outcome, "rowPosition=$rowPosition")
            assertEquals("list" to (if (rowPosition) "position" else null), check.scope to check.reread, "rowPosition=$rowPosition")
            assertEquals(1, check.sampleRate)
            val prefix =
                if (rowPosition) {
                    "trigger=message_new;existed=1;result=expired;reread=position;own=0;sender_type=user;message_type=chat;age_ms="
                } else {
                    "trigger=message_new;existed=1;result=expired;age_ms="
                }
            assertTrue(check.outcomeDetail.startsWith(prefix), check.outcomeDetail)
            assertTrue(check.outcomeDetail.contains(";read_behind=0;"), check.outcomeDetail)
            assertEquals("visibility" to false, check.fetchReason to check.resumed, "rowPosition=$rowPosition: the Refresh that found it")
        }
    }

    @Test
    fun aListAnswerThatLowersARowSaysWhichStep() = runTest {
        // The answer serves 3 unread; the person read the row here (local read overlay): local_read lowers it.
        val served = channelRow.copy(unreadCount = 3)
        val f = loadedStore(listOf(snapshot(listOf(channelRow.copy(unreadCount = 1))), snapshot(listOf(served))))
        f.store.dispatch(SharedActivityIntent.MarkRead(f.row(channelRow.key)))
        runCurrent()
        f.store.dispatch(SharedActivityIntent.Refresh)
        runCurrent()
        // slock #15197: a local_read lowering waits 5 s for this client's read ack (the read's own
        // answer came before the list answer: it settles nothing), then goes as a lowering.
        assertEquals(emptyList(), f.lowered)
        advanceTimeBy(ActivityLocalReadAckWindowMillis)
        runCurrent()

        val lowered = f.lowered.single()
        assertEquals(ActivityListRowStep.LocalRead to Triple(0, 3, 0), lowered.step to Triple(lowered.before, lowered.served, lowered.after))
        assertEquals("visibility" to "none", lowered.fetchReason to lowered.fetchTrigger)
        assertEquals(
            Json.parseToJsonElement(
                """{"key":{"domain":"inbox","event":"activity:list_row_lowered","outcome":"conflict","entityId":"list"},""" +
                    """"meta":{"outcomeDetail":"step=local_read;before=0;served=3;after=0","touched":1,"timestamp":${lowered.timestampMillis}},""" +
                    """"inbox.lowered.step":"local_read","inbox.lowered.before":"0","inbox.lowered.served":"3",""" +
                    """"inbox.lowered.after":"0","inbox.lowered.fetch_reason":"visibility","inbox.lowered.fetch_trigger":"none",""" +
                    """"inbox.lowered.row_position":"true","inbox.lowered.row_kind":"channel","inbox.lowered.scope_id":"channel-1",""" +
                    """"inbox.lowered.row_latest_seq":"10","inbox.lowered.served_read_version":"-","inbox.lowered.served_read_seq":"-",""" +
                    """"inbox.lowered.served_latest_activity_seq":"10","inbox.lowered.ledger_read_version":"-",""" +
                    """"inbox.lowered.ledger_read_seq":"-","inbox.lowered.ack_read_seq":"-","inbox.lowered.ack_delay_ms":"-",""" +
                    """"inbox.lowered.held_end":"window"}"""
            ),
            lowered.stateTransitionTraceAttrs()
        )
    }

    @Test
    fun aListAnswerOlderThanAReadAcceptedHereIsACorrection() = runTest {
        // A read from elsewhere (version 2, read through 10) clears the row; the next list answer
        // still serves 3 unread. Served at version 1 it is stale (the ledger step, corrected); served
        // at version 2 the ledger holds nothing newer, so the local read hold lowers it (lowered).
        listOf(1L to ("activity:list_row_corrected" to ActivityListRowStep.Ledger), 2L to ("activity:list_row_lowered" to ActivityListRowStep.LocalRead))
            .forEach { (servedVersion, expected) ->
                val served = channelRow.copy(unreadCount = 3, readStateVersion = servedVersion, readStateMaxReadSeq = "5")
                val f = loadedStore(listOf(snapshot(listOf(channelRow.copy(unreadCount = 1))), snapshot(listOf(served))))
                f.store.dispatch(readState("channel-1", maxReadSeq = 10L))
                runCurrent()
                f.store.dispatch(SharedActivityIntent.Refresh)
                runCurrent()
                advanceTimeBy(ActivityLocalReadAckWindowMillis) // a held local_read row's window
                runCurrent()

                val lowered = f.lowered.single()
                assertEquals(expected, lowered.event to lowered.step, "served version $servedVersion")
                assertEquals(
                    listOf(servedVersion, "5", "10", 2L, "10"),
                    listOf(lowered.servedReadVersion, lowered.servedReadSeq, lowered.servedLatestActivitySeq, lowered.ledgerReadVersion, lowered.ledgerReadSeq),
                    "served version $servedVersion"
                )
            }
    }

    private val owner = ReadReceiptOwner("production", "user-1", "server-1")

    private fun readAck(channelId: String, maxReadSeq: Long?) =
        SharedActivityIntent.ApplyReadReceiptResult(ReadReceiptRequest.All(ChannelId(channelId), owner, revision = 1L), succeeded = true, maxReadSeq = maxReadSeq)

    /** The list answer serves channel-1 at 3 unread (latest 10) while this client's read of it is still in flight. */
    private fun TestScope.heldLocalRead(): Fixture {
        val f = loadedStore(listOf(snapshot(listOf(channelRow.copy(unreadCount = 1))), snapshot(listOf(channelRow.copy(unreadCount = 3)))))
        f.store.dispatch(SharedActivityIntent.MarkRead(f.row(channelRow.key)))
        runCurrent()
        f.store.dispatch(SharedActivityIntent.Refresh)
        runCurrent()
        assertEquals(emptyList(), f.lowered, "the local_read lowering is held")
        return f
    }

    private fun ActivityListRowLowered.ackAttrs(): List<String> =
        stateTransitionTraceAttrs().let { attrs ->
            listOf("ack_read_seq", "ack_delay_ms", "held_end").map { attrs["inbox.lowered.$it"].toString().trim('"') }
        }

    @Test
    fun aLocalReadLoweringItsOwnReadAckCoversWithinFiveSecondsIsACorrection() = runTest {
        val f = heldLocalRead()
        advanceTimeBy(39L)
        f.store.dispatch(readAck("channel-1", maxReadSeq = 10L))
        runCurrent()
        val lowered = f.lowered.single()
        assertEquals("activity:list_row_corrected" to ActivityListRowStep.LocalRead, lowered.event to lowered.step)
        assertEquals(listOf("10", "39", "ack"), lowered.ackAttrs())
        advanceTimeBy(ActivityLocalReadAckWindowMillis)
        runCurrent()
        assertEquals(1, f.lowered.size, "one event per row")
    }

    @Test
    fun aLocalReadLoweringIsALoweringWhenItsAckIsLowerUnusableLateOrFromAnotherAccount() = runTest {
        // Lower.
        heldLocalRead().let { f ->
            f.store.dispatch(readAck("channel-1", maxReadSeq = 9L))
            runCurrent()
            assertEquals("activity:list_row_lowered" to listOf("9", "0", "ack"), f.lowered.single().let { it.event to it.ackAttrs() })
        }
        // No usable seq, another account's ack, then late: the window decides, the late ack adds nothing.
        heldLocalRead().let { f ->
            f.store.dispatch(readAck("channel-1", maxReadSeq = null))
            f.store.dispatch(
                SharedActivityIntent.ApplyReadReceiptResult(
                    ReadReceiptRequest.All(ChannelId("channel-1"), owner.copy(accountId = "user-2"), revision = 1L),
                    succeeded = true,
                    maxReadSeq = 10L
                )
            )
            runCurrent()
            assertEquals(emptyList(), f.lowered)
            advanceTimeBy(ActivityLocalReadAckWindowMillis)
            runCurrent()
            f.store.dispatch(readAck("channel-1", maxReadSeq = 10L))
            runCurrent()
            assertEquals("activity:list_row_lowered" to listOf("-", "-", "window"), f.lowered.single().let { it.event to it.ackAttrs() })
        }
    }

    @Test
    fun heldLocalReadLoweringsAreFlushedWhenTheAppGoesToTheBackground() = runTest {
        val f = heldLocalRead()
        advanceTimeBy(1_000L)
        f.foreground.value = false
        runCurrent()
        assertEquals("activity:list_row_lowered" to listOf("-", "-", "background"), f.lowered.single().let { it.event to it.ackAttrs() })
        f.store.dispatch(readAck("channel-1", maxReadSeq = 10L))
        advanceTimeBy(ActivityLocalReadAckWindowMillis)
        runCurrent()
        assertEquals(1, f.lowered.size, "flushed once; neither the ack nor the window sends it again")
    }

    @Test
    fun heldLocalReadLoweringsAreFlushedWhenTheStoreOrItsScopeIsCleared() = runTest {
        heldLocalRead().let { f ->
            f.store.dispatch(SharedActivityIntent.ServerSelected("server-2"))
            runCurrent()
            assertEquals(listOf("-", "-", "server_switch"), f.lowered.single().ackAttrs())
        }
        heldLocalRead().let { f ->
            f.store.flushHeldListRowsOnSignout()
            assertEquals(listOf("-", "-", "signout"), f.lowered.single().ackAttrs())
        }
        heldLocalRead().let { f ->
            f.store.clear()
            assertEquals("activity:list_row_lowered" to listOf("-", "-", "signout"), f.lowered.single().let { it.event to it.ackAttrs() })
            advanceTimeBy(ActivityLocalReadAckWindowMillis)
            runCurrent()
            assertEquals(1, f.lowered.size)
        }
    }

    @Test
    fun aNotJudgedAuditIsTheWebStateTransitionShape() {
        val check =
            ActivityInPlaceDriftCheck { 1_000L }.notJudged(
                channelRow,
                ActivityAuditNotJudgedCause.RowUnlisted,
                backend = "postgres",
                detail = ";followup=1"
            )
        assertEquals(
            Json.parseToJsonElement(
                """{"key":{"domain":"inbox","event":"activity:inplace_check","outcome":"noop","entityId":"channel:channel-1"},""" +
                    """"meta":{"outcomeDetail":"trigger=position_audit;existed=1;result=not_judged;cause=row_unlisted;followup=1;reread=position;age_ms=0;read_state=0",""" +
                    """"touched":1,"timestamp":1000},""" +
                    """"inbox.inplace.trigger":"position_audit","inbox.inplace.row_existed":"true","inbox.inplace.result":"not_judged",""" +
                    """"inbox.inplace.sample_rate":"1","inbox.inplace.scope":"row","inbox.inplace.reread":"position",""" +
                    """"inbox.inplace.backend":"postgres","inbox.inplace.not_judged_cause":"row_unlisted"}"""
            ) as JsonObject,
            check.stateTransitionTraceAttrs()
        )
    }

    private fun readState(channelId: String, maxReadSeq: Long) =
        SharedActivityIntent.ApplyRealtimeEvent(
            PerReceiverReadStateChanged(
                serverId = ServerId("server-1"),
                scopes = listOf(PerReceiverReadStateScope(ChannelId(channelId), maxReadSeq = maxReadSeq, readStateVersion = 2L))
            )
        )

    private fun reply(id: String, seq: Long, replyCount: Int) =
        SharedActivityIntent.ApplyRealtimeEvent(
            ThreadUpdated(
                parentMessageId = MessageId("parent-message"),
                threadChannelId = ChannelId("thread-1"),
                replyCount = replyCount,
                lastReplyAt = "2026-11-11T10:00:00Z",
                participantIds = emptyList(),
                latestReply =
                    ReplyPreview(
                        messageId = id,
                        seq = seq,
                        content = "reply $id",
                        senderId = "user-2",
                        senderType = "user",
                        senderName = "user-2",
                        createdAt = "2026-11-11T10:00:00Z"
                    )
            )
        )
}
