package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import build.raft.app.data.ProcessSlockDatetimeFoundationOwner
import build.raft.app.data.SlockDatetimeFoundationOwner
import build.raft.app.data.defaultNowMillis
import build.raft.app.model.SlockTimeContext
import build.raft.app.model.slockTimeContextRefreshDelayMillis
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Pager
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal val LocalSlockTimeContextProvider = compositionLocalOf<SlockTimeContextProvider?> { null }

@Stable
internal class SlockTimeContextProvider(initial: SlockTimeContext?) {
    var current by mutableStateOf(initial)
        private set
    var todayEpochDay by mutableStateOf(initial?.todayEpochDay)
        private set

    fun update(value: SlockTimeContext?) {
        current = value
        todayEpochDay = value?.todayEpochDay
    }
}

internal data class SlockTimeCapabilities(
    val localOffsetMinutes: Int,
    val timeFormat: String
) {
    fun staticContext(): SlockTimeContext =
        SlockTimeContext(
            localOffsetMinutes = localOffsetMinutes,
            timeFormat = timeFormat
        )

    fun contextAt(
        nowEpochMillis: Long,
        foundationOwner: SlockDatetimeFoundationOwner,
    ): SlockTimeContext =
        SlockTimeContext(
            localOffsetMinutes =
                foundationOwner.localOffsetMinutesAt(
                    epochMillis = nowEpochMillis,
                    legacyOffsetMinutes = localOffsetMinutes,
                ),
            timeFormat = timeFormat,
        ).refreshedAt(nowEpochMillis)
}

internal fun SlockTimeContext.toSlockTimeCapabilities(): SlockTimeCapabilities =
    SlockTimeCapabilities(
        localOffsetMinutes = localOffsetMinutes,
        timeFormat = timeFormat
    )

internal fun interface SlockTimeClock {
    fun nowEpochMillis(): Long
}

private val SlockSystemTimeClock = SlockTimeClock(::defaultNowMillis)

@Composable
internal fun rememberLiveSlockTimeContext(
    capabilities: SlockTimeCapabilities?,
    pageVisibleTick: Int = 0,
    active: Boolean = true,
    clock: SlockTimeClock = SlockSystemTimeClock,
    foundationOwner: SlockDatetimeFoundationOwner = ProcessSlockDatetimeFoundationOwner,
): SlockTimeContextProvider {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember(pager) { build.raft.app.base.Utils.bridgeModule(pager.pagerId) }
    val composeScope = rememberCoroutineScope()
    val provider = remember(capabilities, clock, foundationOwner) {
        SlockTimeContextProvider(capabilities?.contextAt(clock.nowEpochMillis(), foundationOwner))
    }
    DisposableEffect(pager, capabilities, pageVisibleTick, active, clock, foundationOwner) {
        val timeoutScheduler =
            SlockIndependentTimeoutScheduler(
                timerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                dispatchCallback = { callback -> composeScope.launch { callback() } }
            )
        val controller =
            SlockTimeContextRefreshController(
                capabilities = capabilities,
                clock = clock,
                foundationOwner = foundationOwner,
                scheduleTimeout = timeoutScheduler::schedule,
                onRefresh = { refreshed ->
                    val previousDay = provider.current?.todayEpochDay
                    val previousOffsetMinutes = provider.current?.localOffsetMinutes
                    val refreshedDay = refreshed?.todayEpochDay
                    val refreshedOffsetMinutes = refreshed?.localOffsetMinutes
                    if (previousDay != null && refreshedDay != null && previousDay != refreshedDay) {
                        bridge.log(
                            "live_time_context_day_changed pagerId=${pager.pagerId} " +
                                "previousDay=$previousDay refreshedDay=$refreshedDay " +
                                "nowEpochMillis=${refreshed.nowEpochMillis}"
                        )
                    }
                    if (
                        previousOffsetMinutes != null &&
                        refreshedOffsetMinutes != null &&
                        previousOffsetMinutes != refreshedOffsetMinutes
                    ) {
                        bridge.log(
                            "live_time_context_offset_changed pagerId=${pager.pagerId} " +
                                "foundation=${if (foundationOwner.usesKuiklyBase) "kuiklybase" else "legacy"} " +
                                "previousOffsetMinutes=$previousOffsetMinutes " +
                                "refreshedOffsetMinutes=$refreshedOffsetMinutes " +
                                "nowEpochMillis=${refreshed.nowEpochMillis}"
                        )
                    }
                    provider.update(refreshed)
                },
                onTimeoutScheduled = { generation, delayMillis, armedAt ->
                    bridge.log(
                        "live_time_context_timer_armed pagerId=${pager.pagerId} " +
                            "generation=$generation delayMillis=$delayMillis " +
                            "nowEpochMillis=$armedAt"
                    )
                },
                onTimeoutDelivered = { generation, deliveredAt ->
                    bridge.log(
                        "live_time_context_timer_delivered pagerId=${pager.pagerId} " +
                            "generation=$generation nowEpochMillis=$deliveredAt"
                    )
                }
            )
        if (active) controller.start()
        onDispose {
            controller.stop()
            timeoutScheduler.close()
        }
    }
    return provider
}

internal fun interface SlockTimeoutHandle {
    fun cancel()
}

internal class SlockIndependentTimeoutScheduler(
    private val timerScope: CoroutineScope,
    private val dispatchCallback: (() -> Unit) -> Unit,
    private val wait: suspend (Long) -> Unit = { delay(it) }
) {
    private val closed = atomic(false)

    fun schedule(delayMillis: Int, callback: () -> Unit): SlockTimeoutHandle {
        val active = atomic(true)
        val timerJob: Job =
            timerScope.launch {
                wait(delayMillis.toLong())
                if (!active.value || closed.value) return@launch
                dispatchCallback {
                    if (active.compareAndSet(expect = true, update = false) && !closed.value) {
                        callback()
                    }
                }
            }
        return SlockTimeoutHandle {
            active.value = false
            timerJob.cancel()
        }
    }

    fun close() {
        closed.value = true
        timerScope.cancel()
    }
}

internal class SlockTimeContextRefreshController(
    private val capabilities: SlockTimeCapabilities?,
    private val clock: SlockTimeClock,
    private val foundationOwner: SlockDatetimeFoundationOwner,
    private val scheduleTimeout: (Int, () -> Unit) -> SlockTimeoutHandle,
    onRefresh: (SlockTimeContext?) -> Unit,
    private val onTimeoutScheduled: (Long, Int, Long) -> Unit = { _, _, _ -> },
    private val onTimeoutDelivered: (Long, Long) -> Unit = { _, _ -> }
) {
    private val onRefresh = onRefresh
    private var running = false
    private var timeoutHandle: SlockTimeoutHandle? = null
    private var timeoutGeneration = 0L

    fun start() {
        if (running) return
        running = true
        refreshAndSchedule()
    }

    fun stop() {
        running = false
        timeoutHandle?.cancel()
        timeoutHandle = null
    }

    private fun refreshAndSchedule() {
        if (!running) return
        val now = clock.nowEpochMillis()
        onRefresh(capabilities?.contextAt(now, foundationOwner))
        val delayMillis =
            slockTimeContextRefreshDelayMillis(now)
                .coerceIn(1L, Int.MAX_VALUE.toLong())
                .toInt()
        timeoutGeneration += 1L
        val generation = timeoutGeneration
        onTimeoutScheduled(generation, delayMillis, now)
        timeoutHandle =
            scheduleTimeout(delayMillis) {
                timeoutHandle = null
                if (!running) return@scheduleTimeout
                onTimeoutDelivered(generation, clock.nowEpochMillis())
                refreshAndSchedule()
            }
    }
}
