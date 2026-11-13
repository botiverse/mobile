package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MessageId
import build.raft.app.model.MessageMention
import build.raft.app.model.MessageNew
import build.raft.app.model.MessageSystemProvenance
import build.raft.app.model.MessageType
import build.raft.app.model.ThreadUpdated
import build.raft.app.network.sync.ReplyPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `mobile_inbox_inplace_v0` drift telemetry (web `checkInPlaceDrift`, slock
 * packages/web/src/store/inboxInPlaceUnread.ts): each reset answer of the All list is checked
 * against the rows a live message patched in place, and one `activity:inplace_check` per judged
 * row goes to the sink (every conflict, one noop in 20). Flag off, nothing is sent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedActivityStoreInboxInPlaceCheckTest {
    private class Sent(val serverId: String, val check: ActivityInPlaceCheck)

    private class Fixture(val store: SharedActivityStore, val inbox: FakeInboxRepository, val sent: MutableList<Sent>)

    /** [answers]: the first is the initial load, then one per later read. */
    private fun TestScope.loadedStore(answers: List<List<InboxItem>>, inPlace: Boolean = true): Fixture {
        val inbox = FakeInboxRepository(initialSnapshots = answers.map(::snapshot))
        val sent = mutableListOf<Sent>()
        val store =
            activityStore(
                scope = backgroundScope,
                inboxRepository = inbox,
                realtimeBackgroundRefreshIntervalMillis = ActivityRealtimeBackgroundRefreshIntervalMillis,
                nowMillis = { testScheduler.currentTime },
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
        assertEquals("trigger=message_new;existed=1;result=unread+mention+order;unread=1/2;mention=1/0;order=0/1;ahead=0", check.outcomeDetail)
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
            assertEquals("trigger=message_new;existed=1;result=match;unread=1/1;mention=0/0;order=0/0;ahead=1", outcomeDetail)
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
        assertEquals("trigger=message_new;existed=1;result=match;unread=1/1;mention=0/0;order=0/0;ahead=1", lagging.outcomeDetail)

        // An answer that has the thread's reply and still sorts it below is a real order drift.
        check.notePatched(channel, InboxRefreshTrigger.MessageNew)
        val caughtUp = check.check(local = local, served = listOf(channel, otherRow, bumpedThread)).single()
        assertEquals("order", caughtUp.result)
        assertEquals("trigger=message_new;existed=1;result=order;unread=1/1;mention=0/0;order=1/0;ahead=0", caughtUp.outcomeDetail)
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
            assertEquals("trigger=message_new;existed=0;result=appeared;served_unread=1", outcomeDetail)
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

        // A note past the stale-list bound is not judged.
        check.notePatched(row, InboxRefreshTrigger.MessageNew)
        now += ActivityInPlaceCheckTtlMillis + 1L
        assertTrue(check.check(local = listOf(row), served = listOf(row.copy(unreadCount = 4))).isEmpty())
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
    }
}
