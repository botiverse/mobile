package build.raft.app

/**
 * Small, platform-neutral contract for video progress.
 *
 * A player can deliver one or more callbacks after it entered the terminal
 * state. Once terminal is observed those callbacks are pinned to duration;
 * replay creates a new generation and atomically resets to zero first.
 */
data class SlockVideoProgressSnapshot(
    val currentMs: Long,
    val durationMs: Long,
    val generation: Long,
    val terminal: Boolean,
)

class SlockVideoProgressContract {
    private var generation = 0L
    private var currentMs = 0L
    private var durationMs = 0L
    private var terminal = false

    fun progress(rawCurrentMs: Long, rawDurationMs: Long): SlockVideoProgressSnapshot {
        if (rawDurationMs > 0L) durationMs = rawDurationMs
        if (terminal) return snapshot()
        currentMs = clamp(rawCurrentMs, durationMs)
        return snapshot()
    }

    fun ended(rawDurationMs: Long): SlockVideoProgressSnapshot {
        if (rawDurationMs > 0L) durationMs = rawDurationMs
        terminal = true
        currentMs = durationMs
        return snapshot()
    }

    fun replay(): SlockVideoProgressSnapshot {
        generation += 1L
        terminal = false
        currentMs = 0L
        return snapshot()
    }

    fun seek(rawTargetMs: Long): SlockVideoProgressSnapshot {
        terminal = false
        currentMs = clamp(rawTargetMs, durationMs)
        return snapshot()
    }

    private fun snapshot(): SlockVideoProgressSnapshot =
        SlockVideoProgressSnapshot(currentMs, durationMs, generation, terminal)

    private fun clamp(value: Long, duration: Long): Long =
        if (duration > 0L) value.coerceIn(0L, duration) else value.coerceAtLeast(0L)
}

fun slockVideoProgressForUi(currentMs: Int, durationMs: Int, terminal: Boolean = false): Pair<Int, Int> {
    val duration = durationMs.coerceAtLeast(0)
    val current = if (terminal && duration > 0) duration else if (duration > 0) currentMs.coerceIn(0, duration) else currentMs.coerceAtLeast(0)
    return current to duration
}
