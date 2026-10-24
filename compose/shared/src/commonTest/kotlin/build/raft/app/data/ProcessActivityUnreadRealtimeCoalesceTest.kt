package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.model.ServerId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Hands ticket 1b6dd022 (iOS 1.15.5): every realtime frame reaches the process domain owner twice —
 * once from [ProcessRealtimeIngressCoordinator] (`process_normal_realtime`) and once from the AppShell
 * listener (`process_realtime`). Hint events such as [RealtimeRefreshRequired] carry no upstream dedup
 * key, so both deliveries invalidated the Activity unread summary and each event cost two
 * `channels/inbox` fetches (start + rerun). The store now coalesces per event marker and fetch round.
 */
class ProcessActivityUnreadRealtimeCoalesceTest {
    @Test
    fun oneEventFromBothSubscribersFetchesOncePerRound() = runTest {
        val harness = harness(storeScope = this)
        val event = refreshHint()

        harness.deliverFromBothSubscribers(event)
        advanceUntilIdle()
        assertEquals(1, harness.fetches, "one event in one round must fetch inbox once")
        assertEquals(ActivityUnreadSummaryFreshness.Fresh, harness.freshness())

        // The server repeats the identical hint after the round finished: it must not be swallowed
        // (task #136 — the reason refresh hints carry no upstream dedup key).
        harness.deliverFromBothSubscribers(event)
        advanceUntilIdle()
        assertEquals(2, harness.fetches, "a second event in a separate round must fetch again")
        assertEquals(ActivityUnreadSummaryFreshness.Fresh, harness.freshness())
    }

    @Test
    fun distinctEventDuringInFlightRoundStillRerunsOnce() = runTest {
        val harness = harness(storeScope = this)

        harness.deliverFromBothSubscribers(refreshHint(channelId = "channel-1"))
        runCurrent()
        assertEquals(1, harness.fetches, "the first event starts exactly one fetch")

        // A different event lands while that fetch is in flight: the running fetch may predate it,
        // so it earns one follow-up fetch — and its duplicate delivery does not add a third.
        advanceTimeBy(FetchMillis / 2)
        harness.deliverFromBothSubscribers(refreshHint(channelId = "channel-2"))
        advanceUntilIdle()

        assertEquals(2, harness.fetches, "fetches=${harness.fetches} freshness=${harness.freshness()}")
        assertEquals(ActivityUnreadSummaryFreshness.Fresh, harness.freshness())
    }

    private fun harness(storeScope: CoroutineScope): Harness {
        val owner =
            ProcessActivityUnreadOwner(
                environmentId = "https://api.example.test",
                accountId = "account-1",
                serverId = "server-1"
            )
        val harness = Harness()
        val store =
            ProcessActivityUnreadStore(
                fetchSummary = { _, _ ->
                    harness.fetches += 1
                    delay(FetchMillis)
                    DataResult.Content(ActivityUnreadSummarySnapshot(totalUnreadCount = 3, totalCount = 5))
                },
                ownerResolver = { serverId -> owner.takeIf { it.serverId == serverId } },
                scope = storeScope
            )
        val context =
            ProcessPresenceAuthContext(
                environmentId = owner.environmentId,
                accountId = owner.accountId,
                authorityGeneration = 1L,
                writeGeneration = 1L
            )
        harness.store = store
        harness.domainOwner =
            SharedRealtimeDomainEventOwner(runIfCurrentGrant = { _, block -> block(); true }).also { domainOwner ->
                domainOwner.installActivityUnreadSink(
                    processActivityUnreadRealtimeSink(currentAuthContext = { context }, store = store)
                )
            }
        return harness
    }

    private class Harness {
        var fetches = 0
        lateinit var store: ProcessActivityUnreadStore
        lateinit var domainOwner: SharedRealtimeDomainEventOwner
        private val grant =
            RealtimeGrantRecord(
                slotKey = RealtimeSlotKey("process-realtime-host", "server-1"),
                principalId = "account-1",
                environmentId = "https://api.example.test",
                authGeneration = 1L,
                connectionFamilyId = "family-1",
                connectionEpochId = "epoch-1",
            )

        /** The two production subscribers, each decoding the same wire frame to an equal event. */
        suspend fun deliverFromBothSubscribers(event: RealtimeEvent) {
            domainOwner.acceptProcessMutation(grant, event, source = "process_normal_realtime")
            domainOwner.acceptProcessMutation(grant, event, source = "process_realtime")
        }

        fun freshness(): ActivityUnreadSummaryFreshness = store.currentProjection(ServerId("server-1")).freshness
    }

    private fun refreshHint(channelId: String = "channel-1"): RealtimeRefreshRequired =
        RealtimeRefreshRequired(
            scope = RealtimeRefreshScope.Home,
            reason = "channel:updated",
            serverId = ServerId("server-1"),
            channelId = ChannelId(channelId)
        )

    private companion object {
        const val FetchMillis = 700L
    }
}
