package build.raft.app.downloads

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.time.TimeSource

/**
 * Turns a stream of instantaneous speed samples into a value that changes at most once per
 * [intervalMillis]. Between changes the samples are collected; when the interval has passed the
 * shown value becomes their average. A missing or non-positive sample clears the value at once.
 */
class RaftSpeedSmoother(
    private val intervalMillis: Long = 1000L,
    timeSource: TimeSource = TimeSource.Monotonic
) {
    private val origin = timeSource.markNow()
    private var shown: Long? = null
    private var windowStartMillis = 0L
    private var sum = 0L
    private var count = 0

    /** Feeds one sample and returns the value to display. */
    fun update(bytesPerSecond: Long?): Long? {
        if (bytesPerSecond == null || bytesPerSecond <= 0L) {
            shown = null
            sum = 0L
            count = 0
            return null
        }
        val now = origin.elapsedNow().inWholeMilliseconds
        if (shown == null) {
            shown = bytesPerSecond
            windowStartMillis = now
            sum = 0L
            count = 0
            return shown
        }
        sum += bytesPerSecond
        count += 1
        if (now - windowStartMillis >= intervalMillis) {
            shown = sum / count
            windowStartMillis = now
            sum = 0L
            count = 0
        }
        return shown
    }
}

/** The speed to display: averaged and replaced at most once per [intervalMillis]; null clears at once. */
@Composable
fun rememberThrottledSpeed(bytesPerSecond: Long?, intervalMillis: Long = 1000L): Long? {
    val smoother = remember(intervalMillis) { RaftSpeedSmoother(intervalMillis) }
    var shown by remember(smoother) { mutableStateOf<Long?>(null) }
    LaunchedEffect(smoother, bytesPerSecond) { shown = smoother.update(bytesPerSecond) }
    if (bytesPerSecond == null || bytesPerSecond <= 0L) return null
    return shown ?: bytesPerSecond
}
