package build.raft.app

import build.raft.app.model.SlockTimeContext
import build.raft.app.model.SlockRelativeTimeLabel
import build.raft.app.model.SlockRelativeTimeUnit
import build.raft.app.model.parseSlockTimestampEpochMillisOrNull
import build.raft.app.model.resolveSlockRelativeTimeLabel
import build.raft.app.model.slockEpochDayForDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class SlockLiveTimeContextTest {
    @Test
    fun liveCapabilitiesDiscardHostSnapshotTime() {
        val staleHostNow = epochMillis("2026-07-26T08:00:00Z")
        val liveNow = epochMillis("2026-07-27T12:00:00Z")
        val capabilities =
            SlockTimeContext(
                localOffsetMinutes = 480,
                todayEpochDay = slockEpochDayForDate(2026, 7, 26),
                nowEpochMillis = staleHostNow,
                timeFormat = "12h"
            ).toSlockTimeCapabilities()

        val context = capabilities.contextAt(liveNow)

        assertEquals(liveNow, context.nowEpochMillis)
        assertEquals(slockEpochDayForDate(2026, 7, 27), context.todayEpochDay)
        assertEquals(480, context.localOffsetMinutes)
        assertEquals("12h", context.timeFormat)
    }

    @Test
    fun sharedMinuteTickerAdvancesRelativeLabelWithoutConversationReload() {
        val sentAt = epochMillis("2026-07-27T12:00:00Z")
        var now = sentAt
        val scheduled = mutableListOf<() -> Unit>()
        val labels = mutableListOf<SlockRelativeTimeLabel>()
        val capabilities = SlockTimeCapabilities(localOffsetMinutes = 0, timeFormat = "24h")
        val controller =
            SlockTimeContextRefreshController(
                capabilities = capabilities,
                clock = SlockTimeClock { now },
                scheduleTimeout = { _, callback ->
                    scheduled += callback
                    SlockTimeoutHandle {}
                },
                onRefresh = { context ->
                    labels += resolveSlockRelativeTimeLabel("2026-07-27T12:00:00Z", context)
                }
            )

        controller.start()
        now += 60_000L
        scheduled.removeAt(0).invoke()
        now += 60_000L
        scheduled.removeAt(0).invoke()

        assertEquals(
            listOf<SlockRelativeTimeLabel>(
                SlockRelativeTimeLabel.Relative(0, SlockRelativeTimeUnit.Minute),
                SlockRelativeTimeLabel.Relative(-1, SlockRelativeTimeUnit.Minute),
                SlockRelativeTimeLabel.Relative(-2, SlockRelativeTimeUnit.Minute)
            ),
            labels
        )
        controller.stop()
    }

    @Test
    fun nativeTimerControllerCrossesMidnightAndResumeRestartRecalculatesDelay() {
        val beforeMidnight = epochMillis("2026-07-22T23:59:30+08:00")
        val afterMidnight = epochMillis("2026-07-23T00:00:05+08:00")
        val capabilities = SlockTimeCapabilities(localOffsetMinutes = 480, timeFormat = "24h")
        val activeRefreshes = mutableListOf<SlockTimeContext?>()
        val activeDelays = mutableListOf<Long>()
        var activeClockIndex = 0
        var activeCallback: (() -> Unit)? = null
        val canceledTimeouts = mutableListOf<String>()
        val activeArmedGenerations = mutableListOf<Long>()
        val activeDeliveredGenerations = mutableListOf<Long>()
        val activeController =
            SlockTimeContextRefreshController(
                capabilities = capabilities,
                clock = SlockTimeClock { listOf(beforeMidnight, afterMidnight)[activeClockIndex] },
                scheduleTimeout = { delayMillis, callback ->
                    activeDelays += delayMillis.toLong()
                    activeCallback = callback
                    val timeoutId = "active-${activeDelays.size}"
                    SlockTimeoutHandle { canceledTimeouts += timeoutId }
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
                slockEpochDayForDate(2026, 7, 22),
                slockEpochDayForDate(2026, 7, 23)
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

        val resumedRefreshes = mutableListOf<SlockTimeContext?>()
        val resumedDelays = mutableListOf<Long>()
        val resumedArmedGenerations = mutableListOf<Long>()
        val resumedController =
            SlockTimeContextRefreshController(
                capabilities = capabilities,
                clock = SlockTimeClock { afterMidnight },
                scheduleTimeout = { delayMillis, _ ->
                    resumedDelays += delayMillis.toLong()
                    SlockTimeoutHandle {}
                },
                onRefresh = resumedRefreshes::add,
                onTimeoutScheduled = { generation, _, _ -> resumedArmedGenerations += generation }
            )
        resumedController.start()

        assertEquals(slockEpochDayForDate(2026, 7, 23), resumedRefreshes.single()?.todayEpochDay)
        assertEquals(listOf(55_000L), resumedDelays)
        assertEquals(listOf(1L), resumedArmedGenerations)
    }

    @Test
    fun independentTimeoutSchedulerFencesCancellationBeforeAndAfterWake() = runBlocking {
        val queuedDeliveries = mutableListOf<() -> Unit>()
        val pendingWakes = mutableListOf<CompletableDeferred<Unit>>()
        val scheduler =
            SlockIndependentTimeoutScheduler(
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
            SlockIndependentTimeoutScheduler(
                timerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                dispatchCallback = { callback -> callback() }
            )

        scheduler.schedule(1) { delivered.complete(Unit) }
        withTimeout(5_000) { delivered.await() }
        scheduler.close()
    }

    private fun epochMillis(value: String): Long =
        parseSlockTimestampEpochMillisOrNull(value)!!
}
