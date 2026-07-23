package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.data.defaultNowMillis
import build.raft.app.model.SlockTimeContext
import build.raft.app.model.slockTimeContextRefreshDelayMillis
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.timer.clearTimeout
import com.tencent.kuikly.core.timer.setTimeout

@Composable
internal fun rememberLiveSlockTimeContext(
    seed: SlockTimeContext?,
    pageVisibleTick: Int = 0
): SlockTimeContext? {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember(pager) { build.raft.app.base.Utils.bridgeModule(pager.pagerId) }
    var current by remember(seed) {
        mutableStateOf(seed?.refreshedAt(defaultNowMillis()))
    }
    DisposableEffect(pager, seed, pageVisibleTick) {
        val controller =
            SlockTimeContextRefreshController(
                seed = seed,
                nowEpochMillis = ::defaultNowMillis,
                scheduleTimeout = { delayMillis, callback ->
                    pager.setTimeout(delayMillis, callback)
                },
                clearTimeout = pager::clearTimeout,
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
                }
            )
        controller.start()
        onDispose(controller::stop)
    }
    return current
}

internal class SlockTimeContextRefreshController(
    private val seed: SlockTimeContext?,
    private val nowEpochMillis: () -> Long,
    private val scheduleTimeout: (Int, () -> Unit) -> String,
    private val clearTimeout: (String) -> Unit,
    onRefresh: (SlockTimeContext?) -> Unit
) {
    private val onRefresh = onRefresh
    private var running = false
    private var timeoutRef: String? = null

    fun start() {
        if (running) return
        running = true
        refreshAndSchedule()
    }

    fun stop() {
        running = false
        timeoutRef?.let(clearTimeout)
        timeoutRef = null
    }

    private fun refreshAndSchedule() {
        if (!running) return
        val now = nowEpochMillis()
        onRefresh(seed?.refreshedAt(now))
        val delayMillis =
            slockTimeContextRefreshDelayMillis(now)
                .coerceIn(1L, Int.MAX_VALUE.toLong())
                .toInt()
        timeoutRef =
            scheduleTimeout(delayMillis) {
                timeoutRef = null
                refreshAndSchedule()
            }
    }
}
