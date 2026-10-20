package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadUpdated
import build.raft.app.network.sync.ReplyPreview
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Local Done/read overlay over the lagging Activity view (task #10; mirrors
 * botiverse/slock web PR #10363, 96916b7f9). All timing runs on virtual time:
 * the store's clock and its coroutines share the test scheduler.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedActivityStoreDoneOverlayTest {

    @Test
    fun staleRefreshesKeepConfirmedDoneRowHiddenWithConsistentCounts() = runTest {
        val inbox = ScriptedActivityInbox(snapshot(listOf(row("channel-1", seq = "10", unread = 2), row("channel-2", seq = "7"))))
        val facts = mutableListOf<ActivityUnreadSummaryFact>()
        val store = overlayStore(inbox, onSummaryFact = { facts += it })
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()

        store.dispatch(SharedActivityIntent.MarkDone(store.state.value.items.first()))
        runCurrent()
        assertEquals(listOf(ChannelId("channel-1")), inbox.doneChannelIds)

        // Three lagging copies within ~1s, the shape observed in production. The
        // second one already carries the done write's read side (unread cleared,
        // first-unread gone) but the same activity frontier: still the same row.
        val staleCopies =
            listOf(
                row("channel-1", seq = "10", unread = 2),
                row("channel-1", seq = "10", unread = 0),
                row("channel-1", seq = "9", unread = 2)
            )
        staleCopies.forEach { stale ->
            advanceTimeBy(300L)
            inbox.serving = snapshot(listOf(stale, row("channel-2", seq = "7")))
            store.dispatch(SharedActivityIntent.PullRefresh)
            runCurrent()

            assertEquals(listOf("channel:channel-2"), store.state.value.items.map { it.key }, "stale=$stale")
            assertEquals(1, store.state.value.totalCount)
            assertEquals(0, store.state.value.totalUnreadCount)
            val fact = facts.last()
            assertEquals(0 to 1, fact.totalUnreadCount to fact.totalCount, "badge must not bounce for stale=$stale")
        }
    }

    @Test
    fun newerActivityShowsDoneRowImmediatelyAndRetiresTheHold() = runTest {
        val inbox = ScriptedActivityInbox(snapshot(listOf(row("channel-1", seq = "10", unread = 1))))
        val store = overlayStore(inbox)
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        store.dispatch(SharedActivityIntent.MarkDone(store.state.value.items.single()))
        runCurrent()

        inbox.serving = snapshot(listOf(row("channel-1", seq = "11", unread = 1, latest = "latest-new")))
        store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()
        assertEquals(MessageId("latest-new"), store.state.value.items.single().latestActivityMessageId)
        assertEquals(1, store.state.value.totalUnreadCount)

        // The entry is gone: even an older copy is no longer hidden.
        inbox.serving = snapshot(listOf(row("channel-1", seq = "10", unread = 1)))
        store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()
        assertEquals(listOf("channel:channel-1"), store.state.value.items.map { it.key })
    }

    @Test
    fun doneOnARealtimePatchedThreadCoversThePatchedActivity() = runTest {
        // With and without a socket seq: the click-time marker must cover the
        // locally patched reply, so the lagging pre-patch row stays hidden.
        listOf(11L, null).forEach { patchSeq ->
            val stale = threadItem("thread-channel-1").copy(readStateLatestActivitySeq = "10", replyCount = 2)
            val inbox = ScriptedActivityInbox(snapshot(listOf(stale)))
            val store = overlayStore(inbox)
            store.dispatch(SharedActivityIntent.LoadInitial)
            runCurrent()
            store.dispatch(
                SharedActivityIntent.ApplyRealtimeEvent(
                    MessageNew(message("reply-3", channelId = "thread-channel-1", seq = patchSeq))
                )
            )
            assertEquals(3, store.state.value.items.single().replyCount)
            store.dispatch(SharedActivityIntent.MarkDone(store.state.value.items.single()))
            runCurrent()

            repeat(2) { attempt ->
                advanceTimeBy(500L)
                store.dispatch(SharedActivityIntent.PullRefresh)
                runCurrent()
                assertEquals(emptyList(), store.state.value.items, "patchSeq=$patchSeq refresh #$attempt")
            }
            inbox.serving =
                snapshot(
                    listOf(
                        stale.copy(
                            readStateLatestActivitySeq = "12",
                            replyCount = 4,
                            latestActivityMessageId = MessageId("reply-4")
                        )
                    )
                )
            store.dispatch(SharedActivityIntent.PullRefresh)
            runCurrent()
            assertEquals(MessageId("reply-4"), store.state.value.items.single().latestActivityMessageId)
        }
    }

    @Test
    fun serverCatchUpAbsenceStillGuardsAgainstALaterStaleCopy() = runTest {
        val inbox = ScriptedActivityInbox(snapshot(listOf(row("channel-1", seq = "10"))))
        val store = overlayStore(inbox)
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        store.dispatch(SharedActivityIntent.MarkDone(store.state.value.items.single()))
        runCurrent()

        inbox.serving = snapshot(emptyList())
        store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()
        assertEquals(emptyList(), store.state.value.items)

        // A degraded-cache answer after the view caught up must not resurrect it.
        advanceTimeBy(2_000L)
        inbox.serving = snapshot(listOf(row("channel-1", seq = "10")))
        store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()
        assertEquals(emptyList(), store.state.value.items)
        assertEquals(0, store.state.value.totalCount)
    }

    @Test
    fun holdRunsPerRowFromServerConfirmationAndLaterDonesNeverExtendIt() = runTest {
        val inbox =
            ScriptedActivityInbox(snapshot(listOf(row("channel-a", seq = "10"), row("channel-b", seq = "20"))))
        val gateA = inbox.gateDone(ChannelId("channel-a"))
        val store = overlayStore(inbox)
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        val (a, b) = store.state.value.items

        store.dispatch(SharedActivityIntent.MarkDone(a))
        runCurrent()
        advanceTimeBy(5_000L) // Done POST for A is slow; the hold has not started.
        gateA.complete(DataResult.Content(InboxDoneResult(ChannelId("channel-a"))))
        runCurrent() // confirmed at t=5_000 -> A held until t=95_000
        advanceTimeBy(45_000L)
        store.dispatch(SharedActivityIntent.MarkDone(b))
        runCurrent() // confirmed at t=50_000 -> B held until t=140_000

        inbox.serving = snapshot(listOf(row("channel-a", seq = "10"), row("channel-b", seq = "20")))
        advanceTimeBy(44_999L) // t=94_999
        store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()
        assertEquals(emptyList(), store.state.value.items.map { it.key }, "both rows still inside their holds")

        advanceTimeBy(1_002L) // t=96_001: A's own hold lapsed; B's is untouched
        store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()
        assertEquals(listOf("channel:channel-a"), store.state.value.items.map { it.key })
        assertEquals(1, store.state.value.totalCount)

        advanceTimeBy(44_000L) // t=140_001
        store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()
        assertEquals(listOf("channel:channel-a", "channel:channel-b"), store.state.value.items.map { it.key })
    }

    @Test
    fun failedDoneWriteRestoresRowAndDropsTheHold() = runTest {
        val inbox = ScriptedActivityInbox(snapshot(listOf(row("channel-1", seq = "10", unread = 3))))
        inbox.failDone = true
        val store = overlayStore(inbox)
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()

        store.dispatch(SharedActivityIntent.MarkDone(store.state.value.items.single()))
        assertEquals(emptyList(), store.state.value.items, "optimistic removal")
        runCurrent()

        assertEquals(listOf("channel:channel-1"), store.state.value.items.map { it.key })
        assertEquals(3, store.state.value.totalUnreadCount)
        assertEquals(2, inbox.loadTimes.size, "failure triggers one canonical reload")
        store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()
        assertEquals(listOf("channel:channel-1"), store.state.value.items.map { it.key })
    }

    @Test
    fun readHoldSurvivesStaleRefreshesAndMarkUnreadUndoesItImmediately() = runTest {
        val inbox = ScriptedActivityInbox(snapshot(listOf(row("channel-1", seq = "10", unread = 2))))
        val read = FakeReadReceiptRepository()
        val store = overlayStore(inbox, readReceiptRepository = read)
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()

        store.dispatch(SharedActivityIntent.MarkRead(store.state.value.items.single()))
        runCurrent()
        assertEquals(listOf(ChannelId("channel-1")), read.readAllChannelIds)
        repeat(3) {
            advanceTimeBy(400L)
            // The view already shows the row read once, then a stale copy returns.
            inbox.serving = snapshot(listOf(row("channel-1", seq = "10", unread = if (it == 1) 0 else 2)))
            store.dispatch(SharedActivityIntent.PullRefresh)
            runCurrent()
            assertEquals(0, store.state.value.items.single().unreadCount, "refresh #$it")
            assertEquals(0, store.state.value.totalUnreadCount)
        }

        store.dispatch(SharedActivityIntent.MarkUnread(store.state.value.items.single()))
        runCurrent()
        inbox.serving = snapshot(listOf(row("channel-1", seq = "10", unread = 2)))
        store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()
        assertEquals(2, store.state.value.items.single().unreadCount)
        assertEquals(2, store.state.value.totalUnreadCount)
    }

    @Test
    fun markAllReadUsesTheSameMarkerBoundHold() = runTest {
        val inbox =
            ScriptedActivityInbox(
                snapshot(listOf(row("channel-1", seq = "10", unread = 2), row("channel-2", seq = "5", unread = 1)))
            )
        val store = overlayStore(inbox)
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        store.dispatch(SharedActivityIntent.MarkAllRead)
        runCurrent()

        store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()
        assertEquals(listOf(0, 0), store.state.value.items.map { it.unreadCount })
        assertEquals(0, store.state.value.totalUnreadCount)

        inbox.serving =
            snapshot(listOf(row("channel-1", seq = "10", unread = 2), row("channel-2", seq = "6", unread = 2, latest = "latest-2b")))
        store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()
        assertEquals(listOf(0, 2), store.state.value.items.map { it.unreadCount })
        assertEquals(2, store.state.value.totalUnreadCount)

        advanceTimeBy(ActivityLocalMutationHoldMillis + 1L)
        store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()
        assertEquals(listOf(2, 2), store.state.value.items.map { it.unreadCount }, "hold lapsed; server truth shows")
    }

    @Test
    fun realtimeRefetchesThrottleLeadingAndTrailingWhileUserRefreshIsNeverDelayed() = runTest {
        val inbox = ScriptedActivityInbox(snapshot(listOf(row("channel-1", seq = "10"))))
        val store = overlayStore(inbox, refreshIntervalMillis = 2_500L)
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        advanceTimeBy(3_000L) // quiet window

        realtime(store, "m-1")
        runCurrent()
        assertEquals(listOf(0L, 3_000L), inbox.loadTimes, "leading edge fetches immediately")

        listOf(100L, 400L, 500L).forEach { gap ->
            advanceTimeBy(gap)
            realtime(store, "m-$gap")
            runCurrent()
        }
        assertEquals(2, inbox.loadTimes.size, "burst inside the window coalesces")
        advanceTimeBy(1_500L) // t=5_500
        runCurrent()
        assertEquals(listOf(0L, 3_000L, 5_500L), inbox.loadTimes, "one trailing fetch at the window end")
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(3, inbox.loadTimes.size, "no further fetch without new activity")

        // User refresh inside a window is immediate and restarts the window.
        realtime(store, "m-late")
        runCurrent()
        assertEquals(15_500L, inbox.loadTimes.last())
        advanceTimeBy(200L)
        realtime(store, "m-later")
        advanceTimeBy(300L)
        store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()
        assertEquals(16_000L, inbox.loadTimes.last(), "pull-to-refresh is not throttled")
        advanceTimeBy(2_499L)
        runCurrent()
        assertEquals(5, inbox.loadTimes.size)
        advanceTimeBy(2L)
        runCurrent()
        assertEquals(listOf(0L, 3_000L, 5_500L, 15_500L, 16_000L, 18_500L), inbox.loadTimes)
    }

    @Test
    fun overlayIsScopedPerServerAndSurvivesASwitchBack() = runTest {
        val session = twoServerSession(selected = "server-1")
        val auth = InMemoryRaftAuthSessionStore(session)
        val inbox = ScriptedActivityInbox(snapshot(listOf(row("channel-1", seq = "10"))))
        val store = overlayStore(inbox, authStore = auth)
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        store.dispatch(SharedActivityIntent.MarkDone(store.state.value.items.single()))
        runCurrent()

        auth.saveSession(twoServerSession(selected = "server-2"))
        inbox.serving = snapshot(listOf(row("channel-1", seq = "10")), serverId = "server-2")
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        assertEquals("server-2", store.state.value.ownerServerId)
        assertEquals(listOf("channel:channel-1"), store.state.value.items.map { it.key }, "no leak into server-2")

        auth.saveSession(twoServerSession(selected = "server-1"))
        inbox.serving = snapshot(listOf(row("channel-1", seq = "10")))
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        assertEquals("server-1", store.state.value.ownerServerId)
        assertEquals(emptyList(), store.state.value.items, "server-1 hold still applies")
    }

    @Test
    fun activitySeqComparisonIsExactDecimal() {
        // (a, b, expected sign of compareActivitySeq(a, b))
        val cases =
            listOf(
                Triple("9007199254740993", "9007199254740992", 1), // exact beyond 2^53
                Triple("10", "9", 1),
                Triple("9", "10", -1),
                Triple("10", "10", 0),
                Triple(null, "1", -1),
                Triple("1", null, 1),
                Triple(null, null, 0),
                Triple("007", "1", -1) // non-canonical loses to any canonical value
            )
        cases.forEach { (a, b, expected) ->
            assertEquals(expected, compareActivitySeq(a, b).coerceIn(-1, 1), "compare($a, $b)")
        }
    }

    @Test
    fun canonicalActivitySeqRejectsNonDecimalShapes() {
        val cases = listOf("0" to true, "41" to true, "007" to false, "" to false, "-1" to false, "1.5" to false, "1e3" to false)
        cases.forEach { (value, expected) ->
            assertEquals(expected, isCanonicalActivitySeq(value), "isCanonical($value)")
        }
    }

    @Test
    fun servedMarkerIsNewerOnlyForStrictlyNewerActivity() {
        val channel = ActivityOverlayMarker(seq = "10", anchor = MessageId("a-1"))
        val thread = ActivityOverlayMarker(seq = "10", anchor = MessageId("a-1"), replyCount = 3, isThread = true)
        val cases =
            listOf(
                MarkerCase("channel newer seq", channel, channel.copy(seq = "11"), true),
                MarkerCase("channel same seq", channel, channel.copy(anchor = MessageId("a-2")), false),
                MarkerCase("channel older seq", channel, channel.copy(seq = "9", anchor = MessageId("a-0")), false),
                MarkerCase("held seq, served none, same anchor", channel, channel.copy(seq = null), false),
                MarkerCase("held seq, served none, new anchor", channel, channel.copy(seq = null, anchor = MessageId("a-2")), true),
                MarkerCase("thread newer seq and reply", thread, thread.copy(seq = "11", replyCount = 4), true),
                MarkerCase("thread newer seq, same reply count", thread, thread.copy(seq = "11"), false),
                MarkerCase("thread fallback, same reply count", thread.copy(seq = null), thread.copy(seq = null, anchor = MessageId("a-2")), false),
                MarkerCase("thread fallback, more replies", thread.copy(seq = null), thread.copy(seq = null, anchor = MessageId("a-2"), replyCount = 4), true)
            )
        cases.forEach { (name, held, served, expected) ->
            assertEquals(expected, served.isNewerThan(held), name)
        }
    }

    @Test
    fun heldFrontierFallsBackToAnchorWhenTheServedRowHasNoSeq() = runTest {
        // (served anchor, expect the row shown again)
        listOf("latest-channel-1" to false, "latest-new" to true).forEach { (servedAnchor, shown) ->
            val inbox = ScriptedActivityInbox(snapshot(listOf(row("channel-1", seq = "10"))))
            val store = overlayStore(inbox)
            store.dispatch(SharedActivityIntent.LoadInitial)
            runCurrent()
            store.dispatch(SharedActivityIntent.MarkDone(store.state.value.items.single()))
            runCurrent()

            inbox.serving = snapshot(listOf(row("channel-1", seq = null, latest = servedAnchor)))
            store.dispatch(SharedActivityIntent.PullRefresh)
            runCurrent()
            assertEquals(if (shown) 1 else 0, store.state.value.items.size, "servedAnchor=$servedAnchor")
        }
    }

    @Test
    fun rowWithoutFrontierOrAnchorRegistersNoHold() = runTest {
        val bare =
            activityItem("channel-1", unreadCount = 1)
                .copy(latestActivityMessageId = null, lastMessageId = null, parentMessageId = null)
        val inbox = ScriptedActivityInbox(snapshot(listOf(bare)))
        val store = overlayStore(inbox)
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        store.dispatch(SharedActivityIntent.MarkDone(store.state.value.items.single()))
        runCurrent()
        assertEquals(listOf(ChannelId("channel-1")), inbox.doneChannelIds)

        store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()
        assertEquals(listOf("channel:channel-1"), store.state.value.items.map { it.key }, "nothing to hold against")
    }

    @Test
    fun locallyResolvedMentionActionStaysHiddenPastTheHold() = runTest {
        val inbox = ScriptedActivityInbox(snapshot(listOf(mentionActionItem("resolution-1"), row("channel-2", seq = "7"))))
        val store = overlayStore(inbox)
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        store.dispatch(SharedActivityIntent.MarkDone(store.state.value.items.first()))
        runCurrent()
        assertEquals(emptyList(), inbox.doneChannelIds, "mention actions resolve locally")

        advanceTimeBy(ActivityLocalMutationHoldMillis + ActivityLocalMutationRequestTimeoutMillis + 1L)
        store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()
        assertEquals(listOf("channel:channel-2"), store.state.value.items.map { it.key })
        assertEquals(1, store.state.value.totalCount)
    }

    @Test
    fun realtimeSeqOnAnOldServerRowKeepsTheAnchorFallback() = runTest {
        // Old server: no readState frontier. A socket seq must not turn the row
        // into a seq marker that later seq-less copies can never beat.
        val stale = threadItem("thread-channel-1").copy(replyCount = 2)
        val inbox = ScriptedActivityInbox(snapshot(listOf(stale)))
        val store = overlayStore(inbox)
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(MessageNew(message("reply-3", channelId = "thread-channel-1", seq = 11L)))
        )
        assertEquals(null, store.state.value.items.single().readStateLatestActivitySeq)
        store.dispatch(SharedActivityIntent.MarkDone(store.state.value.items.single()))
        runCurrent()

        store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()
        assertEquals(emptyList(), store.state.value.items, "pre-patch copy is the handled row")

        inbox.serving = snapshot(listOf(stale.copy(replyCount = 4, latestActivityMessageId = MessageId("reply-4"))))
        store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()
        assertEquals(MessageId("reply-4"), store.state.value.items.single().latestActivityMessageId)
    }

    @Test
    fun threadUpdatedPatchedDoneSurvivesTheServedCatchUpOfThatReply() = runTest {
        // With and without the reply seq on the event: the view catching up to the
        // patched reply (newer seq, same reply count) is still the handled row.
        listOf(11L, null).forEach { replySeq ->
            val stale = threadItem("thread-channel-1").copy(readStateLatestActivitySeq = "10", replyCount = 2)
            val inbox = ScriptedActivityInbox(snapshot(listOf(stale)))
            val store = overlayStore(inbox)
            store.dispatch(SharedActivityIntent.LoadInitial)
            runCurrent()
            store.dispatch(
                SharedActivityIntent.ApplyRealtimeEvent(
                    ThreadUpdated(
                        parentMessageId = MessageId("parent-message"),
                        threadChannelId = ChannelId("thread-channel-1"),
                        replyCount = 3,
                        lastReplyAt = "2026-10-20T00:00:03Z",
                        participantIds = emptyList(),
                        latestReply = replySeq?.let(::replyPreview)
                    )
                )
            )
            assertEquals(3, store.state.value.items.single().replyCount)
            store.dispatch(SharedActivityIntent.MarkDone(store.state.value.items.single()))
            runCurrent()

            inbox.serving =
                snapshot(
                    listOf(
                        stale.copy(
                            readStateLatestActivitySeq = "11",
                            replyCount = 3,
                            latestActivityMessageId = MessageId("reply-3")
                        )
                    )
                )
            store.dispatch(SharedActivityIntent.PullRefresh)
            runCurrent()
            assertEquals(emptyList(), store.state.value.items, "replySeq=$replySeq catch-up copy")

            inbox.serving =
                snapshot(
                    listOf(
                        stale.copy(
                            readStateLatestActivitySeq = "12",
                            replyCount = 4,
                            latestActivityMessageId = MessageId("reply-4")
                        )
                    )
                )
            store.dispatch(SharedActivityIntent.PullRefresh)
            runCurrent()
            assertEquals(MessageId("reply-4"), store.state.value.items.single().latestActivityMessageId, "replySeq=$replySeq")
        }
    }

    @Test
    fun pendingDoneThatNeverConfirmsLapsesAtTheBackstop() = runTest {
        val inbox = ScriptedActivityInbox(snapshot(listOf(row("channel-1", seq = "10"))))
        inbox.gateDone(ChannelId("channel-1")) // never completed
        val store = overlayStore(inbox)
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        store.dispatch(SharedActivityIntent.MarkDone(store.state.value.items.single()))
        runCurrent()

        advanceTimeBy(ActivityLocalMutationHoldMillis + 1L)
        store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()
        assertEquals(emptyList(), store.state.value.items, "pending: request may still land")

        advanceTimeBy(ActivityLocalMutationRequestTimeoutMillis)
        store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()
        assertEquals(listOf("channel:channel-1"), store.state.value.items.map { it.key }, "backstop lapsed")
    }

    @Test
    fun cancelledDoneWriteDropsThePendingHold() = runTest {
        val inbox = ScriptedActivityInbox(snapshot(listOf(row("channel-1", seq = "10"))))
        val gate = inbox.gateDone(ChannelId("channel-1"))
        val store = overlayStore(inbox)
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        store.dispatch(SharedActivityIntent.MarkDone(store.state.value.items.single()))
        runCurrent()

        gate.cancel()
        runCurrent()
        store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()
        assertEquals(listOf("channel:channel-1"), store.state.value.items.map { it.key })
    }

    @Test
    fun summaryFactMatchesTheSnapshotCountsUnderAReadHold() = runTest {
        val inbox =
            ScriptedActivityInbox(snapshot(listOf(row("channel-1", seq = "10", unread = 2), row("channel-2", seq = "7", unread = 1))))
        val facts = mutableListOf<ActivityUnreadSummaryFact>()
        val store = overlayStore(inbox, onSummaryFact = { facts += it })
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        store.dispatch(SharedActivityIntent.MarkRead(store.state.value.items.first()))
        runCurrent()

        store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()
        val fact = facts.last()
        assertEquals(1 to 2, store.state.value.totalUnreadCount to store.state.value.totalCount)
        assertEquals(1 to 2, fact.totalUnreadCount to fact.totalCount)
    }

    @Test
    fun readPredictionHoldStartsAtServerConfirmation() = runTest {
        val inbox = ScriptedActivityInbox(snapshot(listOf(row("channel-1", seq = "10", unread = 2))))
        val owner = ReadReceiptOwner("production", "user-1", "server-1")
        val store = overlayStore(inbox, readReceiptOwnerFactory = { serverId, accountId -> owner.copy(accountId = accountId, serverId = serverId) })
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        store.applyReadReceiptPrediction(
            ReadReceiptPrediction(owner, ChannelId("channel-1"), revision = 1L, target = ReadReceiptTarget.All)
        )
        assertEquals(0, store.state.value.items.single().unreadCount)

        advanceTimeBy(20_000L) // write confirmed at t=20s -> held until t=110s
        store.dispatch(
            SharedActivityIntent.ApplyReadReceiptResult(ReadReceiptRequest.All(ChannelId("channel-1"), owner, revision = 1L), succeeded = true)
        )
        advanceTimeBy(80_000L) // t=100s: past a prediction-time hold, inside the confirmed one
        store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()
        assertEquals(0, store.state.value.items.single().unreadCount)

        advanceTimeBy(10_001L) // t=110_001
        store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()
        assertEquals(2, store.state.value.items.single().unreadCount)
    }

    private data class MarkerCase(
        val name: String,
        val held: ActivityOverlayMarker,
        val served: ActivityOverlayMarker,
        val newer: Boolean
    )

    private fun TestScope.overlayStore(
        inbox: ScriptedActivityInbox,
        readReceiptRepository: FakeReadReceiptRepository = FakeReadReceiptRepository(),
        authStore: RaftAuthSessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
        refreshIntervalMillis: Long = 60_000L,
        readReceiptOwnerFactory: ((String, String) -> ReadReceiptOwner)? = null,
        onSummaryFact: (ActivityUnreadSummaryFact) -> Unit = {}
    ): SharedActivityStore {
        inbox.clock = { testScheduler.currentTime }
        return activityStore(
            scope = backgroundScope,
            inboxRepository = inbox,
            readReceiptRepository = readReceiptRepository,
            authStore = authStore,
            onSummaryFact = onSummaryFact,
            realtimeBackgroundRefreshIntervalMillis = refreshIntervalMillis,
            readReceiptOwnerFactory = readReceiptOwnerFactory
        )
    }

    private fun realtime(store: SharedActivityStore, messageId: String) {
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(MessageNew(message(messageId, channelId = "channel-unseen")))
        )
    }

    private fun row(
        channelId: String,
        seq: String?,
        unread: Int = 0,
        latest: String = "latest-$channelId"
    ): InboxItem =
        activityItem(channelId, unreadCount = unread)
            .copy(readStateLatestActivitySeq = seq, latestActivityMessageId = MessageId(latest))

    private fun replyPreview(seq: Long): ReplyPreview =
        ReplyPreview(
            messageId = "reply-3",
            seq = seq,
            content = "reply",
            senderId = "user-2",
            senderType = "user",
            senderName = "User 2",
            createdAt = "2026-10-20T00:00:03Z"
        )

    private fun twoServerSession(selected: String): StoredAuthSession =
        sampleSession(serverId = selected).copy(
            servers = listOf(StoredAuthServer(id = "server-1", name = "One"), StoredAuthServer(id = "server-2", name = "Two"))
        )

    /** Serves [serving] on every list load and records the virtual time of each load. */
    private class ScriptedActivityInbox(var serving: InboxSnapshot) : InboxRepository {
        var clock: () -> Long = { 0L }
        var failDone = false
        val loadTimes = mutableListOf<Long>()
        val doneChannelIds = mutableListOf<ChannelId>()
        private val doneGates = mutableMapOf<ChannelId, CompletableDeferred<DataResult<InboxDoneResult>>>()

        fun gateDone(channelId: ChannelId): CompletableDeferred<DataResult<InboxDoneResult>> =
            CompletableDeferred<DataResult<InboxDoneResult>>().also { doneGates[channelId] = it }

        override suspend fun loadInitial(serverId: ServerId, filter: InboxFilter, limit: Int): DataResult<InboxSnapshot> {
            loadTimes += clock()
            return DataResult.Content(serving.copy(server = serving.server.copy(id = serverId)))
        }

        override suspend fun loadMore(serverId: ServerId, filter: InboxFilter, offset: Int, limit: Int): DataResult<InboxSnapshot> =
            DataResult.Empty(EmptyReason.NoInboxItems)

        override suspend fun markDone(channelId: ChannelId, frontier: InboxDoneFrontier): DataResult<InboxDoneResult> {
            doneChannelIds += channelId
            doneGates[channelId]?.let { return it.await() }
            return if (failDone) DataResult.Error(ScreenError("done_failed")) else DataResult.Content(InboxDoneResult(channelId))
        }
    }
}
