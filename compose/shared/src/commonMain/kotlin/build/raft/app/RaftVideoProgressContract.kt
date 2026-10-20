package build.raft.app

/**
 * Small, platform-neutral contract for video progress.
 *
 * A player can deliver one or more callbacks after it entered the terminal
 * state. Once terminal is observed those callbacks are pinned to duration;
 * replay creates a new generation and atomically resets to zero first.
 */
data class RaftVideoProgressSnapshot(
    val currentMs: Long,
    val durationMs: Long,
    val generation: Long,
    val terminal: Boolean,
)

/** Keeps terminal callbacks closed until a new playback attempt starts. */
class RaftVideoPlaybackAttemptGate(initialGeneration: Long = 0L) {
    private var activeGeneration = initialGeneration
    private var started = true
    private var terminalEligible = false

    fun begin(generation: Long) {
        activeGeneration = generation
        started = false
        terminalEligible = false
    }

    fun markStarted(generation: Long) {
        if (generation == activeGeneration) started = true
    }

    fun markTerminalEligible(generation: Long) {
        if (generation == activeGeneration && started) terminalEligible = true
    }

    fun acceptsProgress(generation: Long): Boolean = generation == activeGeneration

    fun acceptsTerminal(generation: Long): Boolean =
        started && terminalEligible && generation == activeGeneration
}

class RaftVideoProgressContract {
    private var generation = 0L
    private var currentMs = 0L
    private var durationMs = 0L
    private var terminal = false

    /** Generation used to fence callbacks that belonged to an older attempt. */
    val currentGeneration: Long
        get() = generation

    fun progress(
        rawCurrentMs: Long,
        rawDurationMs: Long,
        callbackGeneration: Long = generation,
    ): RaftVideoProgressSnapshot {
        if (callbackGeneration != generation) return snapshot()
        if (rawDurationMs > 0L) durationMs = rawDurationMs
        if (terminal) return snapshot()
        currentMs = clamp(rawCurrentMs, durationMs)
        return snapshot()
    }

    fun ended(
        rawDurationMs: Long,
        callbackGeneration: Long = generation,
    ): RaftVideoProgressSnapshot {
        if (callbackGeneration != generation) return snapshot()
        if (rawDurationMs > 0L) durationMs = rawDurationMs
        terminal = true
        currentMs = durationMs
        return snapshot()
    }

    fun replay(): RaftVideoProgressSnapshot {
        generation += 1L
        terminal = false
        currentMs = 0L
        return snapshot()
    }

    fun seek(rawTargetMs: Long): RaftVideoProgressSnapshot {
        generation += 1L
        terminal = false
        currentMs = clamp(rawTargetMs, durationMs)
        return snapshot()
    }

    private fun snapshot(): RaftVideoProgressSnapshot =
        RaftVideoProgressSnapshot(currentMs, durationMs, generation, terminal)

    private fun clamp(value: Long, duration: Long): Long =
        if (duration > 0L) value.coerceIn(0L, duration) else value.coerceAtLeast(0L)
}

fun raftVideoProgressForUi(currentMs: Int, durationMs: Int, terminal: Boolean = false): Pair<Int, Int> {
    val duration = durationMs.coerceAtLeast(0)
    val current = if (terminal && duration > 0) duration else if (duration > 0) currentMs.coerceIn(0, duration) else currentMs.coerceAtLeast(0)
    return current to duration
}
