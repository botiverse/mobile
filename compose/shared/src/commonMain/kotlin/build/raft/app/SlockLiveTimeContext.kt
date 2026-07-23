package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import build.raft.app.data.defaultNowMillis
import build.raft.app.model.SlockTimeContext
import build.raft.app.model.slockTimeContextRefreshDelayMillis
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.core.pager.Pager
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun rememberLiveSlockTimeContext(
    seed: SlockTimeContext?,
    pageVisibleTick: Int = 0
): SlockTimeContext? {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember(pager) { build.raft.app.base.Utils.bridgeModule(pager.pagerId) }
    val composeScope = rememberCoroutineScope()
    var current by remember(seed) {
        mutableStateOf(seed?.refreshedAt(defaultNowMillis()))
    }
    DisposableEffect(pager, seed, pageVisibleTick) {
        val timeoutScheduler =
            SlockIndependentTimeoutScheduler(
                timerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                dispatchCallback = { callback -> composeScope.launch { callback() } }
            )
        val controller =
            SlockTimeContextRefreshController(
                seed = seed,
                nowEpochMillis = ::defaultNowMillis,
                scheduleTimeout = timeoutScheduler::schedule,
                onRefresh = { refreshed ->
                    val previousDay = current?.todayEpochDay
                    val refreshedDay = refreshed?.todayEpochDay
                    if (previousDay != null && refreshedDay != null && previousDay != refreshedDay) {
                        bridge.log(
                            "live_time_context_day_changed pagerId=${pager.pagerId} " +
                                "previousDay=$previousDay refreshedDay=$refreshedDay " +
                                "nowEpochMillis=${refreshed.nowEpochMillis}"
                        )
                    }
                    current = refreshed
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
        controller.start()
        onDispose {
            controller.stop()
            timeoutScheduler.close()
        }
    }
    return current
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
    private val seed: SlockTimeContext?,
    private val nowEpochMillis: () -> Long,
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
        val now = nowEpochMillis()
        onRefresh(seed?.refreshedAt(now))
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
                onTimeoutDelivered(generation, nowEpochMillis())
                refreshAndSchedule()
            }
    }
}
