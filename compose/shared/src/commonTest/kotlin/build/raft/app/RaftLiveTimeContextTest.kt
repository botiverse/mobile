package build.raft.app

import build.raft.app.model.RaftTimeContext
import build.raft.app.model.RaftRelativeTimeLabel
import build.raft.app.model.RaftRelativeTimeUnit
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.model.resolveRaftRelativeTimeLabel
import build.raft.app.model.raftEpochDayForDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class RaftLiveTimeContextTest {
    @Test
    fun stableProviderKeepsIdentityAcrossMinuteUpdates() {
        val first = epochMillis("2026-07-27T12:00:00Z")
        val second = first + 60_000L
        val provider =
            RaftTimeContextProvider(
                RaftTimeCapabilities(0, "24h").contextAt(first, RaftLegacyTestDatetimeFoundationOwner)
            )

        provider.update(
            RaftTimeCapabilities(0, "24h").contextAt(second, RaftLegacyTestDatetimeFoundationOwner)
        )

        assertEquals(second, provider.current?.nowEpochMillis)
        assertEquals(provider.current?.todayEpochDay, provider.todayEpochDay)
    }

    @Test
    fun liveCapabilitiesDiscardHostSnapshotTime() {
        val staleHostNow = epochMillis("2026-07-26T08:00:00Z")
        val liveNow = epochMillis("2026-07-27T12:00:00Z")
        val capabilities =
            RaftTimeContext(
                localOffsetMinutes = 480,
                todayEpochDay = raftEpochDayForDate(2026, 7, 26),
                nowEpochMillis = staleHostNow,
                timeFormat = "12h"
            ).toRaftTimeCapabilities()

        val context = capabilities.contextAt(liveNow, RaftLegacyTestDatetimeFoundationOwner)

        assertEquals(liveNow, context.nowEpochMillis)
        assertEquals(raftEpochDayForDate(2026, 7, 27), context.todayEpochDay)
        assertEquals(480, context.localOffsetMinutes)
        assertEquals("12h", context.timeFormat)
    }

    @Test
    fun staticCapabilityContextCannotCarryRouteSnapshotTime() {
        val staleHostNow = epochMillis("2026-07-26T08:00:00Z")
        val context =
            RaftTimeContext(
                localOffsetMinutes = 480,
                todayEpochDay = raftEpochDayForDate(2026, 7, 26),
                nowEpochMillis = staleHostNow,
                timeFormat = "12h"
            ).toRaftTimeCapabilities().staticContext()

        assertEquals(null, context.nowEpochMillis)
        assertEquals(null, context.todayEpochDay)
        assertEquals(480, context.localOffsetMinutes)
        assertEquals("12h", context.timeFormat)
    }

    @Test
    fun sharedMinuteTickerAdvancesRelativeLabelWithoutConversationReload() {
        val sentAt = epochMillis("2026-07-27T12:00:00Z")
        var now = sentAt
        val scheduled = mutableListOf<() -> Unit>()
        val labels = mutableListOf<RaftRelativeTimeLabel>()
        val capabilities = RaftTimeCapabilities(localOffsetMinutes = 0, timeFormat = "24h")
        val controller =
            RaftTimeContextRefreshController(
                capabilities = capabilities,
                clock = RaftTimeClock { now },
                foundationOwner = RaftLegacyTestDatetimeFoundationOwner,
                scheduleTimeout = { _, callback ->
                    scheduled += callback
                    RaftTimeoutHandle {}
                },
                onRefresh = { context ->
                    labels += resolveRaftRelativeTimeLabel("2026-07-27T12:00:00Z", context)
                }
            )

        controller.start()
        now += 60_000L
        scheduled.removeAt(0).invoke()
        now += 60_000L
        scheduled.removeAt(0).invoke()

        assertEquals(
            listOf<RaftRelativeTimeLabel>(
                RaftRelativeTimeLabel.Relative(0, RaftRelativeTimeUnit.Minute),
                RaftRelativeTimeLabel.Relative(-1, RaftRelativeTimeUnit.Minute),
                RaftRelativeTimeLabel.Relative(-2, RaftRelativeTimeUnit.Minute)
            ),
            labels
        )
        controller.stop()
    }

    @Test
    fun nativeTimerControllerCrossesMidnightAndResumeRestartRecalculatesDelay() {
        val beforeMidnight = epochMillis("2026-07-22T23:59:30+08:00")
        val afterMidnight = epochMillis("2026-07-23T00:00:05+08:00")
        val capabilities = RaftTimeCapabilities(localOffsetMinutes = 480, timeFormat = "24h")
        val activeRefreshes = mutableListOf<RaftTimeContext?>()
        val activeDelays = mutableListOf<Long>()
        var activeClockIndex = 0
        var activeCallback: (() -> Unit)? = null
        val canceledTimeouts = mutableListOf<String>()
        val activeArmedGenerations = mutableListOf<Long>()
        val activeDeliveredGenerations = mutableListOf<Long>()
        val activeController =
            RaftTimeContextRefreshController(
                capabilities = capabilities,
                clock = RaftTimeClock { listOf(beforeMidnight, afterMidnight)[activeClockIndex] },
                foundationOwner = RaftLegacyTestDatetimeFoundationOwner,
                scheduleTimeout = { delayMillis, callback ->
                    activeDelays += delayMillis.toLong()
                    activeCallback = callback
                    val timeoutId = "active-${activeDelays.size}"
                    RaftTimeoutHandle { canceledTimeouts += timeoutId }
                },
                onRefresh = activeRefreshes::add,
                onTimeoutScheduled = { generation, _, _ -> activeArmedGenerations += generation },
                onTimeoutDelivered = { generation, _ -> activeDeliveredGenerations += generation }
            )

        activeController.start()
        activeController.start()
        assertEquals(1, activeRefreshes.size)
        assertEquals(listOf(30_000L), activeDelays)
        assertEquals(listOf(1L), activeArmedGenerations)
        activeClockIndex = 1
        activeCallback!!.invoke()

        assertEquals(
            listOf(
                raftEpochDayForDate(2026, 7, 22),
                raftEpochDayForDate(2026, 7, 23)
            ),
            activeRefreshes.map { it?.todayEpochDay }
        )
        assertEquals(listOf(30_000L, 55_000L), activeDelays)
        assertEquals(listOf(1L, 2L), activeArmedGenerations)
        assertEquals(listOf(1L), activeDeliveredGenerations)
        activeController.stop()
        assertEquals(listOf("active-2"), canceledTimeouts)
        activeCallback!!.invoke()
        assertEquals(2, activeRefreshes.size)
        assertEquals(listOf(30_000L, 55_000L), activeDelays)

        val resumedRefreshes = mutableListOf<RaftTimeContext?>()
        val resumedDelays = mutableListOf<Long>()
        val resumedArmedGenerations = mutableListOf<Long>()
        val resumedController =
            RaftTimeContextRefreshController(
                capabilities = capabilities,
                clock = RaftTimeClock { afterMidnight },
                foundationOwner = RaftLegacyTestDatetimeFoundationOwner,
                scheduleTimeout = { delayMillis, _ ->
                    resumedDelays += delayMillis.toLong()
                    RaftTimeoutHandle {}
                },
                onRefresh = resumedRefreshes::add,
                onTimeoutScheduled = { generation, _, _ -> resumedArmedGenerations += generation }
            )
        resumedController.start()

        assertEquals(raftEpochDayForDate(2026, 7, 23), resumedRefreshes.single()?.todayEpochDay)
        assertEquals(listOf(55_000L), resumedDelays)
        assertEquals(listOf(1L), resumedArmedGenerations)
    }

    @Test
    fun liveControllerReReadsSystemZoneWithoutReplacingLegacyPayload() {
        val first = epochMillis("2026-07-27T23:59:30Z")
        var now = first
        var systemOffsetMinutes = 0
        val scheduled = mutableListOf<() -> Unit>()
        val contexts = mutableListOf<RaftTimeContext?>()
        val foundationOwner =
            raftTestDatetimeFoundationOwner(
                kuiklyBaseEnabled = true,
                kuiklyBaseNowMillis = { now },
                legacyNowMillis = { now },
                localOffsetMinutesAt = { systemOffsetMinutes },
            )
        val controller =
            RaftTimeContextRefreshController(
                capabilities = RaftTimeCapabilities(localOffsetMinutes = 480, timeFormat = "24h"),
                clock = RaftTimeClock { now },
                foundationOwner = foundationOwner,
                scheduleTimeout = { _, callback ->
                    scheduled += callback
                    RaftTimeoutHandle {}
                },
                onRefresh = contexts::add,
            )

        controller.start()
        systemOffsetMinutes = 60
        now += 60_000L
        scheduled.removeAt(0).invoke()

        assertEquals(listOf(0, 60), contexts.map { it?.localOffsetMinutes })
        assertEquals(
            listOf(
                raftEpochDayForDate(2026, 7, 27),
                raftEpochDayForDate(2026, 7, 28),
            ),
            contexts.map { it?.todayEpochDay },
        )
        controller.stop()
    }

    @Test
    fun independentTimeoutSchedulerFencesCancellationBeforeAndAfterWake() = runBlocking {
        val queuedDeliveries = mutableListOf<() -> Unit>()
        val pendingWakes = mutableListOf<CompletableDeferred<Unit>>()
        val scheduler =
            RaftIndependentTimeoutScheduler(
                timerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                dispatchCallback = queuedDeliveries::add,
                wait = {
                    CompletableDeferred<Unit>()
                        .also(pendingWakes::add)
                        .await()
                }
            )
        var callbackCount = 0

        scheduler.schedule(30_000) { callbackCount += 1 }
        assertEquals(1, pendingWakes.size)
        assertEquals(0, callbackCount)
        pendingWakes.removeAt(0).complete(Unit)
        assertEquals(1, queuedDeliveries.size)
        assertEquals(0, callbackCount)
        queuedDeliveries.removeAt(0).invoke()
        assertEquals(1, callbackCount)

        val canceledBeforeWake = scheduler.schedule(10_000) { callbackCount += 1 }
        assertEquals(1, pendingWakes.size)
        canceledBeforeWake.cancel()
        pendingWakes.removeAt(0).complete(Unit)
        assertEquals(0, queuedDeliveries.size)
        assertEquals(1, callbackCount)

        val canceledAfterWake = scheduler.schedule(10_000) { callbackCount += 1 }
        pendingWakes.removeAt(0).complete(Unit)
        assertEquals(1, queuedDeliveries.size)
        canceledAfterWake.cancel()
        queuedDeliveries.removeAt(0).invoke()
        assertEquals(1, callbackCount)

        scheduler.schedule(10_000) { callbackCount += 1 }
        pendingWakes.removeAt(0).complete(Unit)
        assertEquals(1, queuedDeliveries.size)
        scheduler.close()
        queuedDeliveries.removeAt(0).invoke()
        assertEquals(1, callbackCount)
    }

    @Test
    fun independentTimeoutSchedulerUsesRealDefaultDispatcherWake() = runBlocking {
        val delivered = CompletableDeferred<Unit>()
        val scheduler =
            RaftIndependentTimeoutScheduler(
                timerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                dispatchCallback = { callback -> callback() }
            )

        scheduler.schedule(1) { delivered.complete(Unit) }
        withTimeout(5_000) { delivered.await() }
        scheduler.close()
    }

    private fun epochMillis(value: String): Long =
        parseRaftTimestampEpochMillisOrNull(value)!!
}
