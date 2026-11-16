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
        val check = expiredAfter(rowPosition = true).single()
        assertEquals("expired" to ActivityInPlaceCheckOutcome.Noop, check.result to check.outcome)
        assertEquals("list" to "position", check.scope to check.reread)
        assertEquals(1, check.sampleRate)
        assertTrue(check.outcomeDetail.startsWith("trigger=message_new;existed=1;result=expired;reread=position;age_ms="), check.outcomeDetail)
        assertTrue(check.outcomeDetail.endsWith(";read_behind=0"), check.outcomeDetail)
        // Flag off: the note lapses silently, as before (the minute reconcile judged it first anyway).
        assertTrue(expiredAfter(rowPosition = false).none { it.result == "expired" })
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

        val lowered = f.lowered.single()
        assertEquals(ActivityListRowStep.LocalRead to Triple(0, 3, 0), lowered.step to Triple(lowered.before, lowered.served, lowered.after))
        assertEquals("visibility" to "none", lowered.fetchReason to lowered.fetchTrigger)
        assertEquals(
            Json.parseToJsonElement(
                """{"key":{"domain":"inbox","event":"activity:list_row_lowered","outcome":"conflict","entityId":"list"},""" +
                    """"meta":{"outcomeDetail":"step=local_read;before=0;served=3;after=0","touched":1,"timestamp":${lowered.timestampMillis}},""" +
                    """"inbox.lowered.step":"local_read","inbox.lowered.before":"0","inbox.lowered.served":"3",""" +
                    """"inbox.lowered.after":"0","inbox.lowered.fetch_reason":"visibility","inbox.lowered.fetch_trigger":"none",""" +
                    """"inbox.lowered.row_position":"true"}"""
            ),
            lowered.stateTransitionTraceAttrs()
        )
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
