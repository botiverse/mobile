package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.data.defaultNowMillis
import build.raft.app.model.SlockTimeContext
import build.raft.app.model.slockTimeContextRefreshDelayMillis
import kotlinx.coroutines.delay

@Composable
internal fun rememberLiveSlockTimeContext(
    seed: SlockTimeContext?,
    pageVisibleTick: Int = 0
): SlockTimeContext? {
    var current by remember(seed) {
        mutableStateOf(seed?.refreshedAt(defaultNowMillis()))
    }
    LaunchedEffect(seed, pageVisibleTick) {
        runSlockTimeContextRefreshLoop(seed) { current = it }
    }
    return current
}

internal suspend fun runSlockTimeContextRefreshLoop(
    seed: SlockTimeContext?,
    nowEpochMillis: () -> Long = ::defaultNowMillis,
    wait: suspend (Long) -> Unit = { delay(it) },
    onRefresh: (SlockTimeContext?) -> Unit
) {
    while (true) {
        val now = nowEpochMillis()
        onRefresh(seed?.refreshedAt(now))
        wait(slockTimeContextRefreshDelayMillis(now))
    }
}
