package build.raft.app.data

import build.raft.app.RaftThreadScreenPayload
import build.raft.app.RaftThreadSurfaceKind
import build.raft.app.SharedThreadFreshnessLedger
import build.raft.app.SharedThreadPayloadStore
import build.raft.app.SharedThreadPrewarmJoinOutcome
import build.raft.app.model.ChannelId
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Hands 7e198cab & Hands 3ebf97ba: Activity freshness ledger invalidation for threads, channels,
 * joint channels, and DMs.
 */
class SharedActivityStoreThreadFreshnessTest {
    @Test
    fun onlyAHomeRefreshSupersedesThreadFetchesInFlight() = runTest {
        val cases =
            listOf(
                RealtimeRefreshScope.Home to true,
                RealtimeRefreshScope.Agents to false,
                RealtimeRefreshScope.Machines to false,
                RealtimeRefreshScope.ServerMembers to false,
                RealtimeRefreshScope.ChannelMembers to false,
                RealtimeRefreshScope.Settings to false,
            )
        for ((refreshScope, supersedes) in cases) {
            val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
            val ledger = SharedThreadFreshnessLedger()
            val store = activityStore(scope = scope, replyThreadFreshnessLedger = ledger)
            store.dispatch(SharedActivityIntent.LoadInitial)
            val inFlight = ledger.capture("server-1", "task-conv-1", "owner-1")

            store.dispatch(
                SharedActivityIntent.ApplyRealtimeEvent(RealtimeRefreshRequired(scope = refreshScope, reason = "test"))
            )

            assertEquals(!supersedes, ledger.isCurrent(inFlight), "scope=$refreshScope")
            if (supersedes) {
                assertEquals(
                    "activity_refresh_required",
                    ledger.capture("server-1", "task-conv-1", "owner-1").cause,
                    "the new generation names what moved it",
                )
            }
            scope.cancel()
        }
    }

    @Test
    fun channelPrewarmInvalidatedWhenRealtimeMessageNewArrivesWithUnchangedAnchor() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val payloadStore = SharedThreadPayloadStore(scope, prewarmStartIntervalMillis = 0L)
        val initialRow =
            InboxItem(
                key = "channel:joint-ch-1",
                kind = InboxItemKind.Channel,
                channelId = ChannelId("joint-ch-1"),
                channelName = "wg-mobile-sync",
                unreadCount = 2,
                firstUnreadMessageId = MessageId("msg-1"),
                lastMessageId = MessageId("msg-2"),
                lastMessageAt = "2026-11-26T10:15:00Z",
                latestActivitySeq = "23655757",
            )
        val store =
            activityStore(
                scope = scope,
                inboxRepository = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(initialRow)))),
                replyThreadFreshnessLedger = payloadStore.freshnessLedger,
            )
        store.dispatch(SharedActivityIntent.LoadInitial)

        val owner = "env-1|user-1|server-1"
        val warmedPayload =
            RaftThreadScreenPayload(
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                sourceThreadId = "joint-ch-1",
                streamChannelId = "joint-ch-1",
                focusedMessageId = "msg-1",
            )
        val beforeLive = payloadStore.freshnessLedger.capture("server-1", "joint-ch-1", owner)
        payloadStore.prewarm("server-1", "joint-ch-1", owner) { DataResult.Content(warmedPayload) }

        // Reproduce Hands 3ebf97ba: a newer message arrives in the channel while the Activity row's
        // firstUnreadMessageId anchor remains msg-1.
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(
                    message(
                        id = "msg-3",
                        channelId = "joint-ch-1",
                        timestamp = "2026-11-26T10:19:24Z",
                        seq = 23656962L,
                    )
                )
            )
        )

        assertFalse(
            payloadStore.freshnessLedger.isCurrent(beforeLive),
            "live MessageNew must advance the channel's freshness generation",
        )
        val join = payloadStore.joinOrConsumePrewarmWithOutcome("server-1", "joint-ch-1", owner)
        assertEquals(SharedThreadPrewarmJoinOutcome.None, join.outcome)
        assertNull(join.result, "first open must not consume a stale prewarm from before msg-3 arrived")
        scope.cancel()
    }

    @Test
    fun channelPrewarmInvalidatedWhenCanonicalInboxRefreshAdvancesChannelSummary() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val payloadStore = SharedThreadPayloadStore(scope, prewarmStartIntervalMillis = 0L)
        val rowBefore =
            InboxItem(
                key = "channel:ch-1",
                kind = InboxItemKind.Channel,
                channelId = ChannelId("ch-1"),
                channelName = "general",
                unreadCount = 2,
                firstUnreadMessageId = MessageId("msg-1"),
                lastMessageId = MessageId("msg-2"),
                lastMessageAt = "2026-11-26T10:15:00Z",
                latestActivitySeq = "100",
            )
        val rowAfter =
            rowBefore.copy(
                unreadCount = 3,
                lastMessageId = MessageId("msg-3"),
                lastMessageAt = "2026-11-26T10:19:24Z",
                latestActivitySeq = "105",
            )
        val store =
            activityStore(
                scope = scope,
                inboxRepository =
                    FakeInboxRepository(
                        initialSnapshots = listOf(snapshot(listOf(rowBefore)), snapshot(listOf(rowAfter)))
                    ),
                replyThreadFreshnessLedger = payloadStore.freshnessLedger,
            )
        store.dispatch(SharedActivityIntent.LoadInitial)

        val owner = "env-1|user-1|server-1"
        val warmedPayload =
            RaftThreadScreenPayload(
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                sourceThreadId = "ch-1",
                streamChannelId = "ch-1",
                focusedMessageId = "msg-1",
            )
        payloadStore.prewarm("server-1", "ch-1", owner) { DataResult.Content(warmedPayload) }

        // Even with a silent socket, a canonical /api/inbox refresh advancing lastMessageId/seq
        // must invalidate the stale channel prewarm.
        store.dispatch(SharedActivityIntent.Refresh)

        val join = payloadStore.joinOrConsumePrewarmWithOutcome("server-1", "ch-1", owner)
        assertEquals(SharedThreadPrewarmJoinOutcome.None, join.outcome)
        assertNull(join.result)
        scope.cancel()
    }

    @Test
    fun laggingCanonicalInboxRefreshDoesNotRegressChannelSummaryOrSupersedeOpenFetch() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val ledger = SharedThreadFreshnessLedger()
        val olderRow =
            InboxItem(
                key = "channel:ch-1",
                kind = InboxItemKind.Channel,
                channelId = ChannelId("ch-1"),
                channelName = "general",
                unreadCount = 2,
                firstUnreadMessageId = MessageId("msg-1"),
                lastMessageId = MessageId("msg-2"),
                lastMessageAt = "2026-11-26T10:15:00Z",
                latestActivitySeq = "100",
            )
        val store =
            activityStore(
                scope = scope,
                inboxRepository =
                    FakeInboxRepository(
                        initialSnapshots = listOf(snapshot(listOf(olderRow)), snapshot(listOf(olderRow)))
                    ),
                replyThreadFreshnessLedger = ledger,
            )
        store.dispatch(SharedActivityIntent.LoadInitial)

        // Live message advances channel summary to seq=105.
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(
                    message(
                        id = "msg-3",
                        channelId = "ch-1",
                        timestamp = "2026-11-26T10:19:24Z",
                        seq = 105L,
                    )
                )
            )
        )
        val openFetchGen = ledger.capture("server-1", "ch-1", "owner-1")

        // A lagging warehouse /api/inbox response returning older seq=100 must not regress
        // summaryFacts or supersede the in-flight open fetch.
        store.dispatch(SharedActivityIntent.Refresh)
        assertTrue(ledger.isCurrent(openFetchGen))
        scope.cancel()
    }

    @Test
    fun echoRealtimeMessageNewAlreadyShownInActivityDoesNotInvalidateFreshPrewarm() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val payloadStore = SharedThreadPayloadStore(scope, prewarmStartIntervalMillis = 0L)
        val row =
            InboxItem(
                key = "channel:ch-1",
                kind = InboxItemKind.Channel,
                channelId = ChannelId("ch-1"),
                channelName = "general",
                unreadCount = 1,
                firstUnreadMessageId = MessageId("msg-2"),
                lastMessageId = MessageId("msg-2"),
                lastMessageAt = "2026-11-26T10:15:00Z",
                latestActivitySeq = "100",
            )
        val store =
            activityStore(
                scope = scope,
                inboxRepository = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(row)))),
                replyThreadFreshnessLedger = payloadStore.freshnessLedger,
            )
        store.dispatch(SharedActivityIntent.LoadInitial)

        val owner = "env-1|user-1|server-1"
        val warmedPayload =
            RaftThreadScreenPayload(
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                sourceThreadId = "ch-1",
                streamChannelId = "ch-1",
                focusedMessageId = "msg-2",
            )
        payloadStore.prewarm("server-1", "ch-1", owner) { DataResult.Content(warmedPayload) }

        // Late socket echo of msg-2 (already shown by Activity before prewarm ran).
        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(
                    message(
                        id = "msg-2",
                        channelId = "ch-1",
                        timestamp = "2026-11-26T10:15:00Z",
                        seq = 100L,
                    )
                )
            )
        )

        val join = payloadStore.joinOrConsumePrewarmWithOutcome("server-1", "ch-1", owner)
        assertEquals(SharedThreadPrewarmJoinOutcome.ConsumedReady, join.outcome)
        scope.cancel()
    }
}
