package build.raft.app.image

import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay

/** Frames this short or shorter play at [RAFT_ANIMATED_DEFAULT_FRAME_MILLIS], like browsers. */
private const val RAFT_ANIMATED_MIN_FRAME_MILLIS = 10

/** The duration given to frames with no usable delay. */
internal const val RAFT_ANIMATED_DEFAULT_FRAME_MILLIS = 100

/** Waits longer than this sleep instead of polling the frame clock every vsync. */
private const val RAFT_ANIMATED_SLEEP_SLACK_MILLIS = 20L

/**
 * Which frame of an animation is on screen at a given time. Pure state, driven by a clock: the
 * frame advances by at most one step per [advance], so a late tick never decodes the frames it
 * skipped, and a schedule that fell behind (a long frame, a pause) restarts from the tick.
 *
 * [repetitionCount] follows Skia / the GIF NETSCAPE block: negative plays forever, `n` plays the
 * sequence `n + 1` times, then holds the last frame.
 */
internal class RaftAnimatedFramePlayer(
    frameDurationsMillis: List<Int>,
    private val repetitionCount: Int,
) {
    private val durations: List<Long> =
        frameDurationsMillis.map { duration ->
            (if (duration <= RAFT_ANIMATED_MIN_FRAME_MILLIS) RAFT_ANIMATED_DEFAULT_FRAME_MILLIS else duration).toLong()
        }

    val frameCount: Int get() = durations.size

    var frameIndex: Int = 0
        private set

    /** True once a finite animation played its last loop; it then holds the last frame. */
    var finished: Boolean = frameCount < 2
        private set

    private var completedLoops = 0

    /** When the current frame ends; null while paused (before [resume]). */
    var deadlineMillis: Long? = null
        private set

    fun frameDurationMillis(index: Int): Long = durations[index]

    /** Starts (or restarts after a pause) the clock: the current frame gets its full duration. */
    fun resume(nowMillis: Long) {
        if (!finished) deadlineMillis = nowMillis + durations[frameIndex]
    }

    fun pause() {
        deadlineMillis = null
    }

    /** Moves to the next frame if the current one has run out; true when the frame changed. */
    fun advance(nowMillis: Long): Boolean {
        val deadline = deadlineMillis ?: return false
        if (finished || nowMillis < deadline) return false
        var next = frameIndex + 1
        if (next == frameCount) {
            completedLoops++
            if (repetitionCount >= 0 && completedLoops > repetitionCount) {
                finished = true
                deadlineMillis = null
                return false
            }
            next = 0
        }
        frameIndex = next
        val scheduled = deadline + durations[next]
        deadlineMillis = if (scheduled > nowMillis) scheduled else nowMillis + durations[next]
        return true
    }
}

/**
 * Runs [this] player on the composition's frame clock until it finishes or the caller is
 * cancelled (page no longer current, app in the background, left composition), calling
 * [showFrame] once per frame change. Long waits sleep instead of ticking every vsync.
 */
internal suspend fun RaftAnimatedFramePlayer.play(showFrame: suspend (Int) -> Unit) {
    if (finished) return
    try {
        resume(withFrameNanos { it } / 1_000_000)
        while (!finished) {
            val now = withFrameNanos { it } / 1_000_000
            if (advance(now)) {
                showFrame(frameIndex)
            } else {
                val wait = (deadlineMillis ?: return) - now
                if (wait > RAFT_ANIMATED_SLEEP_SLACK_MILLIS) delay(wait - RAFT_ANIMATED_SLEEP_SLACK_MILLIS / 2)
            }
        }
    } finally {
        pause()
    }
}
