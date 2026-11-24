package build.raft.app.data

import build.raft.app.data.realtime.PerReceiverReadStateCheckpoint
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
import build.raft.app.model.ThreadUpdated
import build.raft.app.network.sync.ReplyPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `mobile_inbox_inplace_v0` drift telemetry (web `checkInPlaceDrift`, slock
 * packages/web/src/store/inboxInPlaceUnread.ts): each reset answer of the All list is checked
 * against the rows a live message patched in place, and one `activity:inplace_check` per judged
 * row goes to the sink (every conflict, one noop in 20). Flag off, nothing is sent. The web rules
 * (#triage:#244) hold whatever `inbox_row_position_v1` says: `served_ahead`-only is a match, a note
 * nobody judged expires, an answer behind the accepted read or one a read changed under judges
 * nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedActivityStoreInboxInPlaceCheckTest {
    private class Sent(val serverId: String, val check: ActivityInPlaceCheck)

    private class Fixture(val store: SharedActivityStore, val inbox: FakeInboxRepository, val sent: MutableList<Sent>)

    private val readStates = PerReceiverStateVersionStore(InMemoryRaftKeyValueStore())
    private val appForeground = MutableStateFlow(true)

    /** Accepts a read of channel-1 up to seq 9 (version [version]), as a read ack or a `read_state:updated` would. */
    private fun acceptChannelRead(version: Long) {
        readStates.writeReadStateCheckpoint(
            ServerId("server-1"),
            MemberId("user-1"),
            ChannelId("channel-1"),
            PerReceiverReadStateCheckpoint(version = version, maxReadSeq = 9L)
        )
    }

    /** `k=v` fields of an `outcomeDetail` (`trigger=…;existed=…;…`). */
    private fun ActivityInPlaceCheck.fields(): Map<String, String> =
        outcomeDetail.split(';').associate { it.substringBefore('=') to it.substringAfter('=', "") }

    /** [answers]: the first is the initial load, then one per later read; [beforeLoad] runs while read N is on the network. */
    private fun TestScope.loadedStore(
        answers: List<List<InboxItem>>,
        inPlace: Boolean = true,
        beforeLoad: (callNumber: Int) -> Unit = {}
    ): Fixture {
        val inbox = FakeInboxRepository(initialSnapshots = answers.map(::snapshot), beforeInitialLoad = { beforeLoad(it) })
        val sent = mutableListOf<Sent>()
        val store =
            activityStore(
                scope = backgroundScope,
                inboxRepository = inbox,
                perReceiverStateVersionStore = readStates,
                realtimeBackgroundRefreshIntervalMillis = ActivityRealtimeBackgroundRefreshIntervalMillis,
                nowMillis = { testScheduler.currentTime },
                appForeground = appForeground,
                inboxInPlaceEnabled = { inPlace },
                inPlaceCheckSink = { serverId, check -> sent += Sent(serverId, check) }
            )
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        assertEquals(1, inbox.initialLoadCount)
        return Fixture(store, inbox, sent)
    }

    private val channelRow = activityItem("channel-1").copy(latestActivitySeq = "10")
    private val otherRow = activityItem("channel-2").copy(latestActivitySeq = "5")
    private val threadRow = threadItem("thread-1").copy(replyCount = 2, latestActivitySeq = "20")

    private fun live(id: String, channelId: String, seq: Long, mentionsMe: Boolean = false) =
        SharedActivityIntent.ApplyRealtimeEvent(
            MessageNew(
                message(id, channelId = channelId, seq = seq).copy(
                    mentions = if (mentionsMe) listOf(MessageMention(type = "user", id = "user-1")) else emptyList()
                )
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

    /** Lets the in-place reconcile read run (`inplace_reconcile`). */
    private fun TestScope.untilReconcile() {
        advanceTimeBy(ActivityInPlaceReconcileIntervalMillis + 1L)
        runCurrent()
    }

    @Test
    fun anAnswerThatAgreesWithThePatchedRowSendsASampledNoop() = runTest {
        val patched = channelRow.copy(latestActivitySeq = "11", unreadCount = 1, hasMention = true)
        val f = loadedStore(listOf(listOf(otherRow, channelRow), listOf(patched, otherRow)))

        f.store.dispatch(live("m-11", channelId = "channel-1", seq = 11L, mentionsMe = true))
        untilReconcile()

        assertEquals(2, f.inbox.initialLoadCount, "the inplace_reconcile read answered")
        val sent = f.sent.single()
        assertEquals("server-1", sent.serverId)
        with(sent.check) {
            assertEquals("channel:channel-1", entityId)
            assertEquals(InboxRefreshTrigger.MessageNew, trigger)
            assertEquals(true, rowExisted)
            assertEquals("match", result)
            assertEquals(ActivityInPlaceCheckOutcome.Noop, outcome)
            assertEquals(ActivityInPlaceNoopSample, sampleRate)
        }
    }

    @Test
    fun unreadMentionAndOrderDriftAreConflictsSentEveryTime() = runTest {
        // The served channel row has the patched seq but another unread, no mention and a lower place.
        val served = channelRow.copy(latestActivitySeq = "11", unreadCount = 2, hasMention = false)
        val f = loadedStore(listOf(listOf(otherRow, channelRow), listOf(otherRow, served)))

        f.store.dispatch(live("m-11", channelId = "channel-1", seq = 11L, mentionsMe = true))
        untilReconcile()

        val check = f.sent.single().check
        assertEquals(ActivityInPlaceCheckOutcome.Conflict, check.outcome)
        assertEquals("unread+mention+order", check.result)
        assertEquals(1, check.sampleRate)
        val fields = check.fields()
        assertEquals(
            listOf("1/2", "1/0", "0/1", "0"),
            listOf(fields["unread"], fields["mention"], fields["order"], fields["ahead"])
        )
        assertEquals("list", check.scope, "scope is sent with inbox_row_position_v1 off too (web)")
    }

    @Test
    fun aThreadReplyPatchIsCheckedWithTheThreadReplyTrigger() = runTest {
        val served = threadRow.copy(latestActivitySeq = "21", replyCount = 3, unreadCount = 1)
        val f = loadedStore(listOf(listOf(otherRow, threadRow), listOf(served, otherRow)))

        f.store.dispatch(reply("r-21", seq = 21L, replyCount = 3))
        untilReconcile()

        val check = f.sent.single().check
        assertEquals("thread:thread-1", check.entityId)
        assertEquals(InboxRefreshTrigger.ThreadReply, check.trigger)
        assertEquals("match", check.result)
        assertEquals(ActivityInPlaceCheckOutcome.Noop, check.outcome)
    }

    @Test
    fun aNeighbourBumpedAheadOfItsServedCopyDoesNotMoveTheJudgedRow() = runTest {
        // slock #14247 aheadOfServed: the thread row was bumped by a reply the answer does not have
        // yet, so the answer still sorts it last; left out of the order comparison, the channel row
        // the answer has caught up on keeps its place (it was a false `order` conflict before).
        val servedChannel = channelRow.copy(latestActivitySeq = "11", unreadCount = 1)
        val f = loadedStore(listOf(listOf(otherRow, channelRow, threadRow), listOf(servedChannel, otherRow, threadRow)))

        f.store.dispatch(live("m-11", channelId = "channel-1", seq = 11L))
        f.store.dispatch(reply("r-21", seq = 21L, replyCount = 3))
        assertEquals(listOf(threadRow.key, channelRow.key, otherRow.key), f.store.state.value.items.map { it.key })
        untilReconcile()

        assertEquals(2, f.inbox.initialLoadCount, "the inplace_reconcile read answered")
        with(f.sent.single().check) {
            assertEquals("channel:channel-1", entityId)
            assertEquals("match", result)
            assertEquals(ActivityInPlaceCheckOutcome.Noop, outcome)
            assertEquals("0/0" to "1", fields()["order"] to fields()["ahead"])
        }
        // The thread row's answer is still behind its reply: it judges nothing yet.
        assertTrue(f.sent.none { it.check.entityId == threadRow.key })
    }

    @Test
    fun rowsAheadOfTheAnswerAreLeftOutOfTheOrderComparisonOnly() {
        val check = ActivityInPlaceDriftCheck(nowMillis = { 0L })
        val channel = channelRow.copy(latestActivitySeq = "11", unreadCount = 1)
        val bumpedThread = threadRow.copy(latestActivitySeq = "21")
        val local = listOf(bumpedThread, channel, otherRow)

        check.notePatched(channel, InboxRefreshTrigger.MessageNew)
        val lagging = check.check(local = local, served = listOf(channel, otherRow, threadRow)).single()
        assertEquals("match", lagging.result)
        assertEquals("0/0" to "1", lagging.fields()["order"] to lagging.fields()["ahead"])

        // An answer that has the thread's reply and still sorts it below is a real order drift.
        check.notePatched(channel, InboxRefreshTrigger.MessageNew)
        val caughtUp = check.check(local = local, served = listOf(channel, otherRow, bumpedThread)).single()
        assertEquals("order", caughtUp.result)
        assertEquals("1/0" to "0", caughtUp.fields()["order"] to caughtUp.fields()["ahead"])
    }

    @Test
    fun aThreadSystemMessageCountsInPlaceOnlyAsTheServerCountsIt() = runTest {
        val f = loadedStore(listOf(listOf(otherRow, threadRow)))

        fun system(id: String, seq: Long, provenance: MessageSystemProvenance?) =
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(
                    message(id, channelId = "thread-1", authorId = "system", seq = seq).copy(messageType = MessageType.System),
                    systemProvenance = provenance
                )
            )

        fun unread() = f.store.state.value.items.single { it.key == threadRow.key }.unreadCount

        // Caused by someone else, a counted subtype: one more unread, as the server counts it.
        f.store.dispatch(system("s-21", 21L, MessageSystemProvenance("task.lifecycle_thread", "agent", "agent-1")))
        assertEquals(1, unread())
        // Caused by the reader: born read.
        f.store.dispatch(system("s-22", 22L, MessageSystemProvenance("task.lifecycle_thread", "user", "user-1")))
        assertEquals(1, unread())
        // The followed-thread rule's uncounted subtypes.
        f.store.dispatch(system("s-23", 23L, MessageSystemProvenance("channel.self_unfollow_thread", "agent", "agent-1")))
        f.store.dispatch(system("s-24", 24L, MessageSystemProvenance("task.deleted_summary", null, null)))
        assertEquals(1, unread())
        // No provenance on the frame (like a thread:updated reply): it cannot be judged, so it does not count.
        f.store.dispatch(system("s-25", 25L, provenance = null))
        assertEquals(1, unread())
        // A null subtype with no actor still counts.
        f.store.dispatch(system("s-26", 26L, MessageSystemProvenance(null, null, null)))
        assertEquals(2, unread())
        assertEquals("26", f.store.state.value.items.first().latestActivitySeq, "every line still moves the row")

        // An uncounted line that @-mentions the reader adds no unread but still lights the @ (web liveUnreadFields).
        f.store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(
                    message("s-27", channelId = "thread-1", authorId = "system", seq = 27L).copy(
                        messageType = MessageType.System,
                        mentions = listOf(MessageMention(type = "user", id = "user-1"))
                    ),
                    systemProvenance = MessageSystemProvenance("task.deleted_summary", "agent", "agent-1")
                )
            )
        )
        val mentioned = f.store.state.value.items.single { it.key == threadRow.key }
        assertEquals(2, mentioned.unreadCount)
        assertEquals(true to MessageId("s-27"), mentioned.hasMention to mentioned.firstMentionMessageId)

        // slock #16667: history-only notice (countsUnread=false) does not bump unread, while a
        // personal mention still lights `@` on channel and thread rows alike.
        f.store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(
                    message("s-28", channelId = "thread-1", authorId = "system", seq = 28L).copy(
                        messageType = MessageType.System,
                        countsUnread = false,
                    ),
                    systemProvenance = MessageSystemProvenance("channel.agent_membership", "user", "user-2", countsUnread = false)
                )
            )
        )
        assertEquals(2, unread())

        f.store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(
                    message("s-29", channelId = "thread-1", authorId = "system", seq = 29L).copy(
                        messageType = MessageType.System,
                        countsUnread = true,
                    ),
                    systemProvenance = MessageSystemProvenance("channel.agent_membership", "user", "user-2", countsUnread = true)
                )
            )
        )
        assertEquals(3, unread())
    }

    @Test
    fun aChannelHistoryOnlySystemMessageAddsNoUnreadWhileMentionStillLightsAt() = runTest {
        val f = loadedStore(listOf(listOf(otherRow, channelRow)))

        // countsUnread=false without mention: moves row preview/seq, leaves unread at 0 and hasMention false.
        f.store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(
                    message("c-11", channelId = "channel-1", authorId = "system", seq = 11L).copy(
                        messageType = MessageType.System,
                        countsUnread = false,
                    ),
                    systemProvenance = MessageSystemProvenance("channel.agent_membership", "user", "user-2", countsUnread = false)
                )
            )
        )
        val afterNotice = f.store.state.value.items.single { it.key == channelRow.key }
        assertEquals(0, afterNotice.unreadCount)
        assertEquals(false, afterNotice.hasMention)

        // countsUnread=false with a personal mention of user-1: still 0 unread, but lights `@`.
        f.store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(
                    message("c-12", channelId = "channel-1", authorId = "system", seq = 12L).copy(
                        messageType = MessageType.System,
                        countsUnread = false,
                        mentions = listOf(MessageMention(type = "user", id = "user-1")),
                    ),
                    systemProvenance = MessageSystemProvenance("channel.agent_membership", "user", "user-2", countsUnread = false)
                )
            )
        )
        val afterMention = f.store.state.value.items.single { it.key == channelRow.key }
        assertEquals(0, afterMention.unreadCount)
        assertEquals(true, afterMention.hasMention)
        assertEquals(MessageId("c-12"), afterMention.firstMentionMessageId)
    }

    @Test
    fun aThreadRowHeldOverALaggingAnswerGivesWayPastTheStaleListBound() = runTest {
        // Web preserveNewerThreadActivity `until` (slock #14247): not gated by mobile_inbox_inplace_v0.
        val f = loadedStore(listOf(listOf(otherRow, threadRow), listOf(otherRow, threadRow)))

        f.store.dispatch(reply("r-21", seq = 21L, replyCount = 3))
        untilReconcile()
        assertEquals(2, f.inbox.initialLoadCount, "the inplace_reconcile read answered, still behind")
        val held = f.store.state.value.items.single { it.key == threadRow.key }
        // The held row keeps the patched reply (its served seq stays, withRealtimeActivityProjection).
        assertEquals(MessageId("r-21") to 3, held.latestActivityMessageId to held.replyCount, "inside 90 s the patch holds")
        assertTrue(threadRow.key in f.store.realtimeHighWaterKeysForTest())

        advanceTimeBy(ActivityLocalMutationHoldMillis)
        f.store.dispatch(SharedActivityIntent.PullRefresh)
        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(3, f.inbox.initialLoadCount)
        val served = f.store.state.value.items.single { it.key == threadRow.key }
        assertEquals("20" to MessageId("latest-thread"), served.latestActivitySeq to served.latestActivityMessageId, "the served row wins")
        assertEquals(2, served.replyCount)
        assertTrue(threadRow.key !in f.store.realtimeHighWaterKeysForTest(), "the high-water entry is gone")
    }

    @Test
    fun anAnswerStillBehindThePatchJudgesNothingUntilOneReachesIt() = runTest {
        val behind = channelRow.copy(unreadCount = 0)
        val reached = channelRow.copy(latestActivitySeq = "11", unreadCount = 1)
        val f = loadedStore(listOf(listOf(otherRow, channelRow), listOf(behind, otherRow), listOf(reached, otherRow)))

        f.store.dispatch(live("m-11", channelId = "channel-1", seq = 11L))
        untilReconcile()
        assertEquals(2, f.inbox.initialLoadCount)
        assertTrue(f.sent.isEmpty(), "the lagging answer judges nothing")

        f.store.dispatch(SharedActivityIntent.PullRefresh)
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(3, f.inbox.initialLoadCount)
        assertEquals(listOf("match"), f.sent.map { it.check.result })
    }

    @Test
    fun aRowTheListLackedIsCheckedWhenTheRealtimeFallbackReadBringsIt() = runTest {
        val arrived = activityItem("channel-9", unreadCount = 1).copy(latestActivitySeq = "1")
        val f = loadedStore(listOf(listOf(channelRow), listOf(arrived, channelRow)))

        f.store.dispatch(live("m-1", channelId = "channel-9", seq = 1L))
        advanceTimeBy(ActivityRealtimeBackgroundRefreshIntervalMillis + 1L)
        runCurrent()

        assertEquals(2, f.inbox.initialLoadCount, "the realtime_fallback read answered")
        with(f.sent.single().check) {
            assertEquals("channel:channel-9", entityId)
            assertEquals(false, rowExisted)
            assertEquals("appeared", result)
            assertEquals(ActivityInPlaceCheckOutcome.Noop, outcome)
            assertEquals("1", fields()["served_unread"])
            assertEquals("realtime_fallback" to "0", fields()["fetch_reason"] to fields()["resumed"])
        }
    }

    @Test
    fun flagOffSendsNothing() = runTest {
        val served = channelRow.copy(latestActivitySeq = "11", unreadCount = 3)
        val f = loadedStore(listOf(listOf(otherRow, channelRow), listOf(otherRow, served)), inPlace = false)

        f.store.dispatch(live("m-11", channelId = "channel-1", seq = 11L, mentionsMe = true))
        f.store.dispatch(live("m-1", channelId = "channel-9", seq = 1L))
        advanceTimeBy(2 * ActivityInPlaceReconcileIntervalMillis)
        runCurrent()

        assertEquals(2, f.inbox.initialLoadCount, "the realtime_fallback read answered")
        assertTrue(f.sent.isEmpty())
    }

    @Test
    fun oneAgreementInTwentyIsSentAndEveryConflict() {
        var now = 0L
        val check = ActivityInPlaceDriftCheck(nowMillis = { now })
        val row = channelRow.copy(latestActivitySeq = "11", unreadCount = 1)
        val sent = mutableListOf<ActivityInPlaceCheck>()
        repeat(21) {
            check.notePatched(row, InboxRefreshTrigger.MessageNew)
            sent += check.check(local = listOf(row), served = listOf(row))
        }
        check.notePatched(row, InboxRefreshTrigger.MessageNew)
        sent += check.check(local = listOf(row), served = listOf(row.copy(unreadCount = 4)))
        assertEquals(listOf("match", "match", "unread"), sent.map { it.result }, "the 1st and 21st agreement, then the conflict")
        assertEquals(listOf(20, 20, 1), sent.map { it.sampleRate })

        // A note past the stale-list bound is not judged: it is sent as expired (web emitExpired), whatever the flag.
        check.notePatched(row, InboxRefreshTrigger.MessageNew)
        now += ActivityInPlaceCheckTtlMillis + 1L
        val expired = check.check(local = listOf(row), served = listOf(row.copy(unreadCount = 4))).single()
        assertEquals("expired", expired.result)
        assertEquals(ActivityInPlaceCheckOutcome.Noop to 1, expired.outcome to expired.sampleRate)
        assertEquals("${ActivityInPlaceCheckTtlMillis + 1L}" to "0", expired.fields()["age_ms"] to expired.fields()["read_behind"])
        assertTrue(check.isEmpty)
    }

    @Test
    fun theTraceAttributesAreTheWebStateTransitionShape() {
        val attrs =
            ActivityInPlaceCheck(
                entityId = "thread:thread-1",
                trigger = InboxRefreshTrigger.ThreadReply,
                rowExisted = true,
                result = "unread",
                outcome = ActivityInPlaceCheckOutcome.Conflict,
                sampleRate = 1,
                outcomeDetail = "trigger=thread_reply;existed=1;result=unread",
                timestampMillis = 1_700L
            ).stateTransitionTraceAttrs()

        val key = attrs.getValue("key").jsonObject
        assertEquals("inbox", key.getValue("domain").jsonPrimitive.content)
        assertEquals("activity:inplace_check", key.getValue("event").jsonPrimitive.content)
        assertEquals("conflict", key.getValue("outcome").jsonPrimitive.content)
        assertEquals("thread:thread-1", key.getValue("entityId").jsonPrimitive.content)
        val meta = attrs.getValue("meta").jsonObject
        assertEquals("trigger=thread_reply;existed=1;result=unread", meta.getValue("outcomeDetail").jsonPrimitive.content)
        assertEquals(1L, meta.getValue("touched").jsonPrimitive.long)
        assertEquals(1_700L, meta.getValue("timestamp").jsonPrimitive.long)
        assertEquals("thread_reply", attrs.getValue("inbox.inplace.trigger").jsonPrimitive.content)
        assertEquals("true", attrs.getValue("inbox.inplace.row_existed").jsonPrimitive.content)
        assertEquals("unread", attrs.getValue("inbox.inplace.result").jsonPrimitive.content)
        assertEquals("1", attrs.getValue("inbox.inplace.sample_rate").jsonPrimitive.content)
        assertNull(attrs["inbox.inplace.fetch_reason"], "not sent when not set (row checks)")
        assertNull(attrs["inbox.inplace.resumed"])
    }

    @Test
    fun servedReadBehindScopesAreTheRowsReadOlderAndLowerThanTheAcceptedRead() {
        // Served: read v2 up to 8, activity up to 11.
        val served = channelRow.copy(readStateVersion = 2L, readStateMaxReadSeq = "8", readStateLatestActivitySeq = "11")
        // ledger -> behind?
        val cases =
            listOf(
                ActivityLedgerReadState(maxReadSeq = 9L, readStateVersion = 3L) to true,
                null to false,
                ActivityLedgerReadState(maxReadSeq = 9L, readStateVersion = 2L) to false,
                ActivityLedgerReadState(maxReadSeq = 8L, readStateVersion = 3L) to false,
                ActivityLedgerReadState(maxReadSeq = 11L, readStateVersion = 3L) to false,
            )
        cases.forEach { (ledger, behind) ->
            val expected = if (behind) setOf("channel-1") else emptySet()
            assertEquals(expected, servedReadBehindScopes(listOf(served)) { ledger }, "ledger=$ledger")
        }
        val noFrontier = channelRow.copy(readStateVersion = null)
        assertTrue(servedReadBehindScopes(listOf(noFrontier)) { cases.first().first }.isEmpty(), "a row served without its read frontier")
    }

    private enum class SecondAnswer { Reached, Ahead, ReadChangedUnderIt, BehindTheRead }

    @Test
    fun theSecondAnswerJudgesOrWaitsAsWebDoesWithRowPositionOff() = runTest {
        val reached = channelRow.copy(latestActivitySeq = "11", unreadCount = 1)
        SecondAnswer.entries.forEach { case ->
            val second =
                when (case) {
                    SecondAnswer.Reached, SecondAnswer.ReadChangedUnderIt -> reached
                    SecondAnswer.Ahead -> reached.copy(latestActivitySeq = "12")
                    // Its count is not what is judged here (the skip is): the same as the patched row's.
                    SecondAnswer.BehindTheRead ->
                        reached.copy(readStateVersion = 2L, readStateMaxReadSeq = "8", readStateLatestActivitySeq = "11")
                }
            val third = reached.copy(readStateVersion = 3L, readStateMaxReadSeq = "9", readStateLatestActivitySeq = "11")
            if (case == SecondAnswer.BehindTheRead) acceptChannelRead(version = 3L)
            val f =
                loadedStore(listOf(listOf(otherRow, channelRow), listOf(second, otherRow), listOf(third, otherRow))) { call ->
                    if (call == 2 && case == SecondAnswer.ReadChangedUnderIt) acceptChannelRead(version = 5L)
                }

            val patchedAt = testScheduler.currentTime
            f.store.dispatch(live("m-11", channelId = "channel-1", seq = 11L))
            untilReconcile()
            assertEquals(2, f.inbox.initialLoadCount, "$case: the inplace_reconcile read answered")
            val judgedAtOnce = case == SecondAnswer.Reached || case == SecondAnswer.Ahead
            assertEquals(if (judgedAtOnce) 1 else 0, f.sent.size, "$case: judged by the second answer")
            if (!judgedAtOnce) {
                f.store.dispatch(SharedActivityIntent.PullRefresh)
                advanceTimeBy(1_000L)
                runCurrent()
                assertEquals(3, f.inbox.initialLoadCount, "$case: the pull answered")
            }
            val check = f.sent.single().check
            val fields = check.fields()
            assertEquals("match", check.result, "$case")
            assertEquals(if (case == SecondAnswer.Ahead) "1" else null, fields["served_ahead"], "$case")
            assertEquals(if (case == SecondAnswer.BehindTheRead) "1" else null, fields["read_behind"], "$case")
            val reason = if (judgedAtOnce) "inplace_reconcile" else "manual"
            assertEquals(reason to "0", fields["fetch_reason"] to fields["resumed"], "$case")
            assertEquals(reason to false, check.fetchReason to check.resumed, "$case: attributes")
            assertTrue(check.timestampMillis > patchedAt, "$case")
            assertEquals((check.timestampMillis - patchedAt).toString(), fields["age_ms"], "$case: since the row was patched")
        }
    }

    @Test
    fun theFirstReadAfterAReturnToTheForegroundSaysResumed() = runTest {
        val reached = channelRow.copy(latestActivitySeq = "11", unreadCount = 1)
        listOf(false, true).forEach { backgrounded ->
            // The third answer has the second message with another count: a conflict, always sent.
            val conflicting = reached.copy(latestActivitySeq = "12", unreadCount = 5)
            val f = loadedStore(listOf(listOf(otherRow, channelRow), listOf(reached, otherRow), listOf(conflicting, otherRow)))

            f.store.dispatch(live("m-11", channelId = "channel-1", seq = 11L))
            if (backgrounded) {
                appForeground.value = false
                runCurrent()
                appForeground.value = true
                runCurrent()
            }
            untilReconcile()
            assertEquals(2, f.inbox.initialLoadCount, "backgrounded=$backgrounded: one read answered")
            val first = f.sent.single().check
            assertEquals(backgrounded, first.resumed, "backgrounded=$backgrounded")
            assertEquals(if (backgrounded) "1" else "0", first.fields()["resumed"])

            // The next read is not the return's.
            f.store.dispatch(live("m-12", channelId = "channel-1", seq = 12L, mentionsMe = true))
            f.store.dispatch(SharedActivityIntent.PullRefresh)
            advanceTimeBy(1_000L)
            runCurrent()
            assertEquals(3, f.inbox.initialLoadCount)
            assertEquals(listOf(backgrounded, false), f.sent.map { it.check.resumed }, "backgrounded=$backgrounded: the second read is not")
        }
    }

    // --- #triage:#245: ~30s convergence follow-up after selected=1 unread=0/N conflicts ---

    private data class RecordedRowRead(
        val serverId: ServerId,
        val rowIds: List<String>,
        val reason: InboxFetchReason?,
        val trigger: InboxRefreshTriggerCount?,
    )

    private class RowCapableInbox(
        listAnswers: List<List<InboxItem>>,
        private val rowAnswers: List<DataResult<InboxSnapshot>> = emptyList(),
    ) : InboxRepository by FakeInboxRepository(initialSnapshots = listAnswers.map(::snapshot)) {
        val rowReads = mutableListOf<RecordedRowRead>()
        private var rowAnswerIndex = 0

        override suspend fun loadRows(serverId: ServerId, rowIds: List<String>): DataResult<InboxSnapshot> {
            val context = currentCoroutineContext()[InboxFetchReasonContext]
            rowReads += RecordedRowRead(serverId, rowIds, context?.reason, context?.trigger)
            return if (rowAnswers.isEmpty()) {
                DataResult.Empty(EmptyReason.NoInboxItems)
            } else {
                rowAnswers.getOrElse(rowAnswerIndex++) { rowAnswers.last() }
            }
        }
    }

    private class SelectedFollowupFixture(
        val store: SharedActivityStore,
        val inbox: RowCapableInbox,
        val sent: MutableList<Sent>,
    )

    private fun TestScope.loadedStoreWithRows(
        listAnswers: List<List<InboxItem>>,
        rowAnswers: List<DataResult<InboxSnapshot>>,
        readingOnSight: () -> Set<String>?,
        rowPosition: Boolean = false,
        foreground: MutableStateFlow<Boolean> = MutableStateFlow(true),
    ): SelectedFollowupFixture {
        val inbox = RowCapableInbox(listAnswers = listAnswers, rowAnswers = rowAnswers)
        val sent = mutableListOf<Sent>()
        val store =
            activityStore(
                scope = backgroundScope,
                inboxRepository = inbox,
                perReceiverStateVersionStore = PerReceiverStateVersionStore(InMemoryRaftKeyValueStore()),
                realtimeBackgroundRefreshIntervalMillis = ActivityRealtimeBackgroundRefreshIntervalMillis,
                nowMillis = { testScheduler.currentTime },
                appForeground = foreground,
                inboxInPlaceEnabled = { true },
                inboxRowPositionEnabled = { rowPosition },
                conversationsReadOnSight = readingOnSight,
                inPlaceCheckSink = { serverId, check -> sent += Sent(serverId, check) },
            )
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        return SelectedFollowupFixture(store, inbox, sent)
    }

    private fun liveSelected(id: String, channelId: String, seq: Long, activeConversationId: String? = channelId) =
        SharedActivityIntent.ApplyRealtimeEvent(
            event = MessageNew(message(id, channelId = channelId, seq = seq)),
            activeConversationId = activeConversationId,
        )

    @Test
    fun selectedUnreadZeroConflictSchedulesThirtySecondFollowupAndConfirmsServerConvergence() = runTest {
        val initialOther = otherRow
        val matchingOther = otherRow.copy(latestActivitySeq = "6", unreadCount = 1)
        val servedUnread = channelRow.copy(latestActivitySeq = "11", unreadCount = 1)
        val converged = channelRow.copy(latestActivitySeq = "11", unreadCount = 0)
        val f =
            loadedStoreWithRows(
                listAnswers = listOf(
                    listOf(initialOther, channelRow),
                    listOf(matchingOther, channelRow),
                    listOf(servedUnread, matchingOther),
                ),
                rowAnswers = listOf(DataResult.Content(snapshot(listOf(converged)).copy(inboxBackend = "risingwave"))),
                readingOnSight = { setOf("channel-1") },
            )

        // Consume the 1st sampled noop on channel-2 so the next ordinary noop would be sampled out (1-in-20),
        // proving the selected follow-up always emits with sampleRate = 1.
        f.store.dispatch(live("m-6", channelId = "channel-2", seq = 6L))
        f.store.dispatch(SharedActivityIntent.Refresh)
        runCurrent()
        assertEquals(listOf("match"), f.sent.map { it.check.result })

        // Live message arrives in selected channel-1 (read on sight -> local unread=0), server still reports unread=1.
        f.store.dispatch(liveSelected("m-11", channelId = "channel-1", seq = 11L))
        f.store.dispatch(SharedActivityIntent.Refresh)
        runCurrent()
        assertEquals(2, f.sent.size)
        val initialConflict = f.sent.last().check
        assertEquals(ActivityInPlaceCheckOutcome.Conflict, initialConflict.outcome)
        assertEquals("unread", initialConflict.result)
        assertEquals(true, initialConflict.selected)
        assertEquals("0/1", initialConflict.fields()["unread"])

        // Read state event arrives while the 30s follow-up timer is waiting.
        advanceTimeBy(5_000L)
        f.store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                PerReceiverReadStateChanged(
                    serverId = ServerId("server-1"),
                    scopes = listOf(PerReceiverReadStateScope(ChannelId("channel-1"), maxReadSeq = 11L, readStateVersion = 2L)),
                )
            )
        )
        runCurrent()

        // Not yet due just before 30s.
        advanceTimeBy(ActivitySelectedReadFollowupDelayMillis - 5_001L)
        runCurrent()
        assertTrue(f.inbox.rowReads.isEmpty(), "must wait full 30s before re-checking")

        // At +30s, a single-row read runs and confirms server convergence (unread=0/0).
        advanceTimeBy(1L)
        runCurrent()
        val rowRead = f.inbox.rowReads.single()
        assertEquals(ServerId("server-1"), rowRead.serverId)
        assertEquals(listOf("channel-1"), rowRead.rowIds)
        assertEquals(InboxFetchReason.PositionReread, rowRead.reason)
        assertEquals(InboxRefreshTriggerCount(InboxRefreshTrigger.MessageNew), rowRead.trigger)

        assertEquals(3, f.sent.size)
        val followup = f.sent.last().check
        assertEquals("channel:channel-1", followup.entityId)
        assertEquals(InboxRefreshTrigger.MessageNew, followup.trigger)
        assertEquals("match", followup.result)
        assertEquals(ActivityInPlaceCheckOutcome.Noop, followup.outcome)
        assertEquals(1, followup.sampleRate, "selected follow-ups are never sampled out")
        assertEquals("row", followup.scope)
        assertEquals("risingwave", followup.backend)
        assertEquals(true, followup.selected)
        assertNull(followup.notJudgedCause)
        val fields = followup.fields()
        assertEquals("0/0", fields["unread"])
        assertEquals("0/0", fields["mention"])
        assertEquals("11/11", fields["position"])
        assertEquals("list", fields["initial_scope"])
        assertEquals("1", fields["initial_served_unread"])
        assertEquals("1", fields["selected_followup"])
        assertEquals(ActivitySelectedReadFollowupReason, fields["followup_reason"])
        assertEquals(ActivitySelectedReadFollowupDelayMillis.toString(), fields["age_ms"])
        assertEquals("1", fields["read_state"])

        // Privacy: no conversation id in outcomeDetail or flat inbox.inplace.* attributes.
        assertFalse(followup.outcomeDetail.contains("channel-1"), followup.outcomeDetail)
        val attrs = followup.stateTransitionTraceAttrs()
        attrs.entries
            .filter { (k, _) -> k.startsWith("inbox.inplace.") }
            .forEach { (k, v) -> assertFalse(v.toString().contains("channel-1"), "$k=$v") }
    }

    @Test
    fun selectedUnreadZeroFollowupStillUnreadEmitsConflictAndDoesNotLoopInfinitely() = runTest {
        val servedUnread = channelRow.copy(latestActivitySeq = "11", unreadCount = 1)
        val f =
            loadedStoreWithRows(
                listAnswers = listOf(listOf(otherRow, channelRow), listOf(servedUnread, otherRow)),
                rowAnswers = listOf(DataResult.Content(snapshot(listOf(servedUnread)))),
                readingOnSight = { setOf("channel-1") },
            )

        f.store.dispatch(liveSelected("m-11", channelId = "channel-1", seq = 11L))
        f.store.dispatch(SharedActivityIntent.Refresh)
        runCurrent()
        assertEquals(1, f.sent.size)
        // Even if the client holds a local read overlay on the row (90s hold), the follow-up must check
        // the raw server unread count rather than masking a lingering server unread=1 down to 0.
        f.store.dispatch(SharedActivityIntent.MarkRead(f.store.state.value.items.first { it.key == channelRow.key }))
        runCurrent()

        advanceTimeBy(ActivitySelectedReadFollowupDelayMillis)
        runCurrent()
        assertEquals(1, f.inbox.rowReads.size)
        assertEquals(2, f.sent.size)
        val followup = f.sent.last().check
        assertEquals(ActivityInPlaceCheckOutcome.Conflict, followup.outcome)
        assertEquals("unread", followup.result)
        assertEquals(1, followup.sampleRate)
        assertEquals(true, followup.selected)
        val fields = followup.fields()
        assertEquals("0/1", fields["unread"])
        assertEquals("1", fields["selected_followup"])
        assertEquals(ActivitySelectedReadFollowupReason, fields["followup_reason"])
        assertEquals("0", fields["read_state"])

        // Must never schedule a follow-up of a follow-up.
        advanceTimeBy(10 * ActivitySelectedReadFollowupDelayMillis)
        runCurrent()
        assertEquals(1, f.inbox.rowReads.size, "no follow-up loop")
        assertEquals(2, f.sent.size, "no extra check emitted")
    }

    @Test
    fun unselectedOrNonZeroLocalUnreadConflictDoesNotScheduleSelectedFollowup() = runTest {
        // Case 1: selected=0 + unread=0/1 (user left conversation before the list check ran).
        var reading = setOf("channel-1")
        val servedUnread = channelRow.copy(latestActivitySeq = "11", unreadCount = 1)
        val unselected =
            loadedStoreWithRows(
                listAnswers = listOf(listOf(otherRow, channelRow), listOf(servedUnread, otherRow)),
                rowAnswers = listOf(DataResult.Content(snapshot(listOf(channelRow.copy(latestActivitySeq = "11", unreadCount = 0))))),
                readingOnSight = { reading },
            )
        unselected.store.dispatch(liveSelected("m-11", channelId = "channel-1", seq = 11L))
        reading = emptySet()
        unselected.store.dispatch(SharedActivityIntent.Refresh)
        runCurrent()
        assertEquals(1, unselected.sent.size)
        assertEquals(false, unselected.sent.single().check.selected)
        assertEquals("0/1", unselected.sent.single().check.fields()["unread"])

        advanceTimeBy(2 * ActivitySelectedReadFollowupDelayMillis)
        runCurrent()
        assertTrue(unselected.inbox.rowReads.isEmpty(), "selected=0 must not schedule a selected follow-up")
        assertEquals(1, unselected.sent.size)

        // Case 2: selected=1 + unread=1/2 (local unread > 0).
        var readingTwo = emptySet<String>()
        val servedTwo = channelRow.copy(latestActivitySeq = "11", unreadCount = 2)
        val nonZeroLocal =
            loadedStoreWithRows(
                listAnswers = listOf(listOf(otherRow, channelRow), listOf(servedTwo, otherRow)),
                rowAnswers = listOf(DataResult.Content(snapshot(listOf(channelRow.copy(latestActivitySeq = "11", unreadCount = 0))))),
                readingOnSight = { readingTwo },
            )
        nonZeroLocal.store.dispatch(live("m-11", channelId = "channel-1", seq = 11L))
        readingTwo = setOf("channel-1")
        nonZeroLocal.store.dispatch(SharedActivityIntent.Refresh)
        runCurrent()
        assertEquals(1, nonZeroLocal.sent.size)
        assertEquals(true, nonZeroLocal.sent.single().check.selected)
        assertEquals("1/2", nonZeroLocal.sent.single().check.fields()["unread"])

        advanceTimeBy(2 * ActivitySelectedReadFollowupDelayMillis)
        runCurrent()
        assertTrue(nonZeroLocal.inbox.rowReads.isEmpty(), "local unread > 0 must not schedule a selected follow-up")
        assertEquals(1, nonZeroLocal.sent.size)
    }

    @Test
    fun serverSwitchClearAndBackgroundCancelOrSkipPendingSelectedFollowup() = runTest {
        val servedUnread = channelRow.copy(latestActivitySeq = "11", unreadCount = 1)
        val converged = channelRow.copy(latestActivitySeq = "11", unreadCount = 0)

        // Server switch cancels pending follow-up.
        val switched =
            loadedStoreWithRows(
                listAnswers = listOf(listOf(otherRow, channelRow), listOf(servedUnread, otherRow), listOf(otherRow)),
                rowAnswers = listOf(DataResult.Content(snapshot(listOf(converged)))),
                readingOnSight = { setOf("channel-1") },
            )
        switched.store.dispatch(liveSelected("m-11", channelId = "channel-1", seq = 11L))
        switched.store.dispatch(SharedActivityIntent.Refresh)
        runCurrent()
        assertEquals(1, switched.sent.size)
        advanceTimeBy(10_000L)
        switched.store.dispatch(SharedActivityIntent.ServerSelected("server-2"))
        advanceTimeBy(2 * ActivitySelectedReadFollowupDelayMillis)
        runCurrent()
        assertTrue(switched.inbox.rowReads.isEmpty(), "server switch must cancel pending selected follow-up")
        assertEquals(1, switched.sent.size)

        // Store clear() cancels pending follow-up.
        val cleared =
            loadedStoreWithRows(
                listAnswers = listOf(listOf(otherRow, channelRow), listOf(servedUnread, otherRow)),
                rowAnswers = listOf(DataResult.Content(snapshot(listOf(converged)))),
                readingOnSight = { setOf("channel-1") },
            )
        cleared.store.dispatch(liveSelected("m-11", channelId = "channel-1", seq = 11L))
        cleared.store.dispatch(SharedActivityIntent.Refresh)
        runCurrent()
        advanceTimeBy(10_000L)
        cleared.store.clear()
        advanceTimeBy(2 * ActivitySelectedReadFollowupDelayMillis)
        runCurrent()
        assertTrue(cleared.inbox.rowReads.isEmpty(), "clear() must cancel pending selected follow-up")
        assertEquals(1, cleared.sent.size)

        // Backgrounding skips the follow-up without firing a network request.
        val foreground = MutableStateFlow(true)
        val backgrounded =
            loadedStoreWithRows(
                listAnswers = listOf(listOf(otherRow, channelRow), listOf(servedUnread, otherRow)),
                rowAnswers = listOf(DataResult.Content(snapshot(listOf(converged)))),
                readingOnSight = { setOf("channel-1") },
                foreground = foreground,
            )
        backgrounded.store.dispatch(liveSelected("m-11", channelId = "channel-1", seq = 11L))
        backgrounded.store.dispatch(SharedActivityIntent.Refresh)
        runCurrent()
        advanceTimeBy(10_000L)
        foreground.value = false
        runCurrent()
        advanceTimeBy(2 * ActivitySelectedReadFollowupDelayMillis)
        runCurrent()
        assertTrue(backgrounded.inbox.rowReads.isEmpty(), "backgrounded store must skip selected follow-up")
        assertEquals(1, backgrounded.sent.size)
    }

    @Test
    fun selectedFollowupPreservesPositionAuditAccountingDeduplicatesAndWorksOnSyncRow() = runTest {
        // 1. Position audit conflict with selected=1 unread=0/1 schedules a 30s follow-up whose trigger is NOT
        //    position_audit, preserving `audits scheduled == checks with trigger position_audit`.
        val baseRow = channelRow.copy(readStateLatestActivitySeq = "10")
        val auditServedUnread = baseRow.copy(latestActivitySeq = "30", readStateLatestActivitySeq = "30", unreadCount = 1)
        val convergedAt30 = baseRow.copy(latestActivitySeq = "30", readStateLatestActivitySeq = "30", unreadCount = 0)
        val auditFixture =
            loadedStoreWithRows(
                listAnswers = listOf(listOf(baseRow)),
                rowAnswers = listOf(
                    DataResult.Content(snapshot(listOf(auditServedUnread))),
                    DataResult.Content(snapshot(listOf(convergedAt30))),
                ),
                readingOnSight = { setOf("channel-1") },
                rowPosition = true,
            )
        repeat(20) { idx ->
            auditFixture.store.dispatch(liveSelected("m-${11 + idx}", channelId = "channel-1", seq = 11L + idx))
        }
        advanceTimeBy(ActivityRowRereadDelayMillis + 1L)
        runCurrent()
        assertEquals(1, auditFixture.sent.size)
        assertEquals(InboxRefreshTrigger.PositionAudit, auditFixture.sent.single().check.trigger)
        assertEquals("0/1", auditFixture.sent.single().check.fields()["unread"])

        advanceTimeBy(ActivitySelectedReadFollowupDelayMillis)
        runCurrent()
        assertEquals(2, auditFixture.sent.size)
        assertEquals(
            1,
            auditFixture.sent.count { it.check.trigger == InboxRefreshTrigger.PositionAudit },
            "selected follow-up must not inflate position_audit count"
        )
        val auditFollowup = auditFixture.sent.last().check
        assertEquals(InboxRefreshTrigger.Other, auditFollowup.trigger)
        assertEquals("match", auditFollowup.result)
        assertEquals("0/0", auditFollowup.fields()["unread"])
        assertEquals("1", auditFollowup.fields()["selected_followup"])

        // 2. Deduplication per scope: two selected=1 unread=0/1 conflicts before 30s issue only one follow-up read.
        val served11 =
            baseRow.copy(
                latestActivitySeq = "11",
                readStateLatestActivitySeq = "11",
                lastMessageId = MessageId("m-11"),
                latestActivityMessageId = MessageId("m-11"),
                unreadCount = 1,
            )
        val served12 =
            baseRow.copy(
                latestActivitySeq = "12",
                readStateLatestActivitySeq = "12",
                lastMessageId = MessageId("m-12"),
                latestActivityMessageId = MessageId("m-12"),
                unreadCount = 1,
            )
        val converged12 = served12.copy(unreadCount = 0)
        val dedupe =
            loadedStoreWithRows(
                listAnswers = listOf(
                    listOf(otherRow, baseRow),
                    listOf(served11, otherRow),
                    listOf(served12, otherRow),
                ),
                rowAnswers = listOf(DataResult.Content(snapshot(listOf(converged12)))),
                readingOnSight = { setOf("channel-1") },
            )
        dedupe.store.dispatch(liveSelected("m-11", channelId = "channel-1", seq = 11L))
        dedupe.store.dispatch(SharedActivityIntent.Refresh)
        runCurrent()
        assertEquals(1, dedupe.sent.size)
        advanceTimeBy(5_000L)
        // Clear local unread back to 0 via read state, then land another live message + conflict while first follow-up waits.
        dedupe.store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                PerReceiverReadStateChanged(
                    serverId = ServerId("server-1"),
                    scopes = listOf(PerReceiverReadStateScope(ChannelId("channel-1"), maxReadSeq = 11L, readStateVersion = 2L)),
                )
            )
        )
        runCurrent()
        dedupe.store.dispatch(liveSelected("m-12", channelId = "channel-1", seq = 12L))
        dedupe.store.dispatch(SharedActivityIntent.Refresh)
        runCurrent()
        assertEquals(2, dedupe.sent.size)

        advanceTimeBy(ActivitySelectedReadFollowupDelayMillis + 10_000L)
        runCurrent()
        assertEquals(1, dedupe.inbox.rowReads.size, "pending follow-up for same scope is deduplicated")
        assertEquals(3, dedupe.sent.size)
        assertEquals("match", dedupe.sent.last().check.result)

        // 3. sync_inbox_rows conflict (scope=sync_row) also triggers a 30s follow-up.
        val syncFixture =
            loadedStoreWithRows(
                listAnswers = listOf(listOf(baseRow)),
                rowAnswers = listOf(DataResult.Content(snapshot(listOf(baseRow.copy(latestActivitySeq = "11", unreadCount = 0))))),
                readingOnSight = { setOf("channel-1") },
                rowPosition = true,
            )
        syncFixture.store.dispatch(liveSelected("m-11", channelId = "channel-1", seq = 11L))
        runCurrent()
        val syncChanges =
            parseSyncChangesPage(
                syncChangesBody("c1.1.1", changes = listOf(syncInboxRowUpsert(1, "channel-1", syncInboxChannelRow("channel-1", "11", unread = 1))))
            )!!.changes.filterIsInstance<RaftSyncInboxRowChange>()
        syncFixture.store.applySyncInboxRows("server-1", syncChanges)
        runCurrent()
        assertEquals(1, syncFixture.sent.size)
        assertEquals(ActivityInPlaceScopeSyncRow, syncFixture.sent.single().check.scope)
        assertEquals("0/1", syncFixture.sent.single().check.fields()["unread"])

        advanceTimeBy(ActivitySelectedReadFollowupDelayMillis)
        runCurrent()
        assertEquals(1, syncFixture.inbox.rowReads.size)
        val syncFollowup = syncFixture.sent.last().check
        assertEquals("match", syncFollowup.result)
        assertEquals("0/0", syncFollowup.fields()["unread"])
        assertEquals(ActivityInPlaceScopeSyncRow, syncFollowup.fields()["initial_scope"])
        assertEquals("1", syncFollowup.fields()["selected_followup"])
    }

    @Test
    fun selectedReadFollowupHelpersEnforceNonChainingAndServedAheadConvergence() {
        val auditFollowupConflict =
            ActivityInPlaceCheck(
                entityId = "channel:channel-1",
                trigger = InboxRefreshTrigger.PositionAudit,
                rowExisted = true,
                result = "unread",
                outcome = ActivityInPlaceCheckOutcome.Conflict,
                sampleRate = 1,
                outcomeDetail = "trigger=position_audit;existed=1;result=unread;unread=0/1;mention=0/0;position=11/11;followup=1;reread=position;age_ms=0;read_state=0",
                timestampMillis = 1_000L,
                scope = ActivityInPlaceScopeRow,
                selected = true,
            )
        assertTrue(auditFollowupConflict.isFollowupCheck())
        assertNull(auditFollowupConflict.selectedUnreadZeroServedCountOrNull(), "audit follow-up must not chain a selected follow-up")

        // User left conversation after initial seq 11 was read on server (maxReadSeq=11), and newer seq 12 arrived unread.
        val newerAfterRead =
            channelRow.copy(
                latestActivitySeq = "12",
                unreadCount = 1,
                readStateMaxReadSeq = "11",
            )
        val convergedBeforeNewMessage =
            buildSelectedReadFollowupCheck(
                entityId = "channel:channel-1",
                trigger = InboxRefreshTrigger.MessageNew,
                reread = null,
                initialScope = ActivityInPlaceScopeList,
                initialPosition = "11",
                initialServedUnread = 1,
                served = newerAfterRead,
                localUnreadNow = 1,
                selectedNow = false,
                backend = "postgres",
                readStateArrived = true,
                ageMillis = 30_000L,
                nowMillis = 40_000L,
            )
        assertEquals(ActivityInPlaceCheckOutcome.Noop, convergedBeforeNewMessage.outcome)
        assertEquals("match", convergedBeforeNewMessage.result)
        assertEquals("1", convergedBeforeNewMessage.fields()["read_converged"])
        assertEquals("1", convergedBeforeNewMessage.fields()["served_ahead"])
        assertTrue(convergedBeforeNewMessage.isFollowupCheck())
        assertNull(convergedBeforeNewMessage.copy(outcome = ActivityInPlaceCheckOutcome.Conflict, selected = true).selectedUnreadZeroServedCountOrNull())

        // Still selected and unread on a newer message -> still a conflict.
        val stillUnreadWhileSelected =
            buildSelectedReadFollowupCheck(
                entityId = "channel:channel-1",
                trigger = InboxRefreshTrigger.ThreadReply,
                reread = ActivityInPlaceRereadPosition,
                initialScope = ActivityInPlaceScopeRow,
                initialPosition = "11",
                initialServedUnread = 1,
                served = newerAfterRead.copy(readStateMaxReadSeq = "10"),
                localUnreadNow = 0,
                selectedNow = true,
                backend = "risingwave",
                readStateArrived = false,
                ageMillis = 30_000L,
                nowMillis = 40_000L,
            )
        assertEquals(ActivityInPlaceCheckOutcome.Conflict, stillUnreadWhileSelected.outcome)
        assertEquals("served_ahead+unread", stillUnreadWhileSelected.result)
        assertEquals("0/1", stillUnreadWhileSelected.fields()["unread"])
        assertNull(stillUnreadWhileSelected.copy(selected = true).selectedUnreadZeroServedCountOrNull())
    }
}
