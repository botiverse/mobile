package build.raft.app.transfer

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Where one transfer (an upload, a download, the app update) is in its life. */
enum class RaftTransferPhase {
    /** Started, no byte moved yet (request, response headers, server session). */
    Queued,

    /** Bytes are moving. */
    Running,

    /** Stopped by the user; bytes so far are kept. */
    Paused,

    /**
     * Every byte was handed off, the transfer is not complete yet: an upload waits for the server
     * to acknowledge it, a download for the file to be committed (atomic publish, MediaStore).
     */
    Finishing,

    /** Acknowledged by the server / committed to storage. The only phase that shows 100%. */
    Done,
    Failed,
    Cancelled;

    val isTerminal: Boolean get() = this == Done || this == Failed || this == Cancelled
}

/**
 * The progress of one transfer: the only progress type that crosses layers (owner -> host bridge
 * -> stores -> UI and notifications). UI renders [percent] and [bytesPerSecond] as they are; it
 * never derives them from the bytes.
 *
 * Only a [RaftTransferMeter] makes one (the constructor is private), so its rules hold for every
 * screen:
 * - [percent] is null while the total is unknown or no byte has moved; otherwise 1..99 until
 *   [RaftTransferPhase.Done], and 100 only then; it never goes backwards within a transfer;
 * - [bytesPerSecond] is a windowed rate that changes at most once per second, null when not
 *   [RaftTransferPhase.Running].
 */
@ConsistentCopyVisibility
data class RaftTransferProgress private constructor(
    val bytesDone: Long,
    val bytesTotal: Long?,
    val phase: RaftTransferPhase,
    val percent: Int?,
    val bytesPerSecond: Long?
) {
    /** The same progress with the user's pause shown at once (the host confirms later). */
    fun paused(): RaftTransferProgress =
        if (phase.isTerminal) this else copy(phase = RaftTransferPhase.Paused, bytesPerSecond = null)

    /** The same progress, running again after a pause. */
    fun resumed(): RaftTransferProgress = if (phase == RaftTransferPhase.Paused) copy(phase = RaftTransferPhase.Running) else this

    /** The transfer ended without completing: the last percent stays, the speed goes. */
    fun ended(phase: RaftTransferPhase): RaftTransferProgress {
        require(phase == RaftTransferPhase.Failed || phase == RaftTransferPhase.Cancelled) { "use completed() for Done" }
        return if (this.phase == RaftTransferPhase.Done) this else copy(phase = phase, bytesPerSecond = null)
    }

    /**
     * The owner saw the transfer complete (server acknowledged / file committed). [sizeBytes] is
     * the final size when the owner learns it then.
     */
    fun completed(sizeBytes: Long? = null): RaftTransferProgress {
        val total = sizeBytes?.takeIf { it > 0L } ?: bytesTotal
        return RaftTransferProgress(total ?: bytesDone, total, RaftTransferPhase.Done, 100, null)
    }

    /**
     * Keeps the visible progress of one transfer from rewinding when samples arrive out of order
     * across an asynchronous boundary (host callbacks, render scheduling): bytes and percent never
     * go back, a missing total keeps the known one.
     */
    fun followedBy(next: RaftTransferProgress): RaftTransferProgress {
        if (phase == RaftTransferPhase.Done) return this
        if (next.phase != RaftTransferPhase.Done && next.bytesDone < bytesDone) return this
        val currentPercent = percent
        val nextPercent = next.percent
        if (currentPercent != null && nextPercent != null && nextPercent < currentPercent) return this
        return next.copy(
            bytesTotal = next.bytesTotal ?: bytesTotal,
            percent = nextPercent ?: currentPercent?.takeIf { next.bytesDone > 0L }
        )
    }

    companion object {
        /** A transfer that has not started (no meter yet), e.g. a download page before its first sample. */
        fun notStarted(bytesTotal: Long? = null): RaftTransferProgress =
            RaftTransferProgress(0L, bytesTotal?.takeIf { it > 0L }, RaftTransferPhase.Queued, null, null)

        /**
         * Decodes a progress a meter produced on the other side of the host bridge. The values are
         * the meter's own; this only re-checks the invariants so a malformed payload cannot show
         * 100% before Done.
         */
        internal fun decoded(
            bytesDone: Long,
            bytesTotal: Long?,
            phase: RaftTransferPhase,
            percent: Int?,
            bytesPerSecond: Long?
        ): RaftTransferProgress =
            RaftTransferProgress(
                bytesDone = bytesDone.coerceAtLeast(0L),
                bytesTotal = bytesTotal?.takeIf { it > 0L },
                phase = phase,
                percent =
                    when {
                        phase == RaftTransferPhase.Done -> 100
                        bytesDone <= 0L -> null
                        else -> percent?.coerceIn(1, 99)
                    },
                bytesPerSecond = bytesPerSecond?.takeIf { it > 0L && phase == RaftTransferPhase.Running }
            )

        /**
         * One percent for several transfers shown together (the Android download notification):
         * each transfer's own [percent] weighted by its size, so the caps hold for the sum. Null
         * unless every transfer has a known total and a percent.
         */
        fun combinedPercent(transfers: List<RaftTransferProgress>): Int? {
            if (transfers.isEmpty()) return null
            if (transfers.all { it.phase == RaftTransferPhase.Done }) return 100
            var weighted = 0L
            var total = 0L
            for (transfer in transfers) {
                val size = transfer.bytesTotal ?: return null
                val percent = transfer.percent ?: if (transfer.phase == RaftTransferPhase.Queued) 0 else return null
                weighted += percent.toLong() * size
                total += size
            }
            if (total <= 0L) return null
            return (weighted / total).toInt().coerceIn(0, 99)
        }

        /**
         * The meter's step: the next progress after [previous] with the percent rule applied
         * (1..99 from the bytes, 99 while finishing, never below [previous]). Done goes through
         * [completed] only.
         */
        internal fun advance(
            previous: RaftTransferProgress,
            bytesDone: Long,
            bytesTotal: Long?,
            phase: RaftTransferPhase,
            bytesPerSecond: Long?
        ): RaftTransferProgress {
            require(phase != RaftTransferPhase.Done) { "use completed() for Done" }
            val raw =
                when {
                    bytesTotal == null || bytesDone <= 0L -> null
                    phase == RaftTransferPhase.Finishing -> 99
                    else -> (bytesDone.toDouble() / bytesTotal.toDouble() * 100.0).toInt().coerceIn(1, 99)
                }
            val last = previous.percent
            val percent = if (raw != null && last != null) maxOf(raw, last) else raw ?: last?.takeIf { bytesDone > 0L }
            return RaftTransferProgress(
                bytesDone = bytesDone,
                bytesTotal = bytesTotal,
                phase = phase,
                percent = percent,
                bytesPerSecond = bytesPerSecond?.takeIf { it > 0L && phase == RaftTransferPhase.Running }
            )
        }
    }
}

/**
 * Owns the progress of one transfer. The transfer's owner (the attachment download coordinator,
 * the export coordinator, the composer upload, the app-update download) holds one meter per
 * attempt, feeds it raw facts (bytes moved, handed off, acknowledged) and publishes [progress].
 *
 * - Clock: [timeSource], monotonic (virtual in tests).
 * - Rate: bytes over the last [rateWindow] of samples.
 * - Speed label: [RaftTransferProgress.bytesPerSecond] changes at most once per [labelInterval]
 *   (the first rate shows as soon as there is one).
 * - Percent: see [RaftTransferProgress]; 100 only after [done].
 *
 * Not thread-safe: the owner calls it under its own lock or on one thread.
 */
class RaftTransferMeter(
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val rateWindow: Duration = 3.seconds,
    private val labelInterval: Duration = 1.seconds,
    bytesTotal: Long? = null
) {
    private class Sample(val at: TimeMark, val bytes: Long)

    private val samples = ArrayDeque<Sample>()
    private var labelShownAt: TimeMark? = null

    var progress: RaftTransferProgress = RaftTransferProgress.notStarted(bytesTotal)
        private set

    /** The total became known (response headers, server session). */
    fun total(bytesTotal: Long?): RaftTransferProgress {
        if (progress.phase.isTerminal) return progress
        val total = bytesTotal?.takeIf { it > 0L } ?: return progress
        return publish(progress.bytesDone, total, progress.phase, progress.bytesPerSecond)
    }

    /** [bytesDone] bytes of the body have moved so far (cumulative, from the wire). */
    fun bytes(bytesDone: Long, bytesTotal: Long? = null): RaftTransferProgress {
        val current = progress
        if (current.phase.isTerminal || current.phase == RaftTransferPhase.Finishing) return current
        val total = bytesTotal?.takeIf { it > 0L } ?: current.bytesTotal
        val done = maxOf(current.bytesDone, bytesDone.coerceAtLeast(0L)).let { if (total != null) it.coerceAtMost(total) else it }
        if (current.phase == RaftTransferPhase.Paused) return publish(done, total, RaftTransferPhase.Paused, null)
        val now = timeSource.markNow()
        samples.addLast(Sample(now, done))
        while (samples.size > 2 && samples.first().at.elapsedNow() > rateWindow) samples.removeFirst()
        val phase = if (done > 0L) RaftTransferPhase.Running else current.phase
        return publish(done, total, phase, shownSpeed(now))
    }

    fun pause(): RaftTransferProgress {
        if (progress.phase.isTerminal || progress.phase == RaftTransferPhase.Finishing) return progress
        resetRate()
        return publish(progress.bytesDone, progress.bytesTotal, RaftTransferPhase.Paused, null)
    }

    fun resume(): RaftTransferProgress {
        if (progress.phase != RaftTransferPhase.Paused) return progress
        val phase = if (progress.bytesDone > 0L) RaftTransferPhase.Running else RaftTransferPhase.Queued
        return publish(progress.bytesDone, progress.bytesTotal, phase, null)
    }

    /** Every byte was handed off; the owner now waits for the server's ack / the commit. */
    fun finishing(): RaftTransferProgress {
        if (progress.phase.isTerminal) return progress
        resetRate()
        val total = progress.bytesTotal
        return publish(total ?: progress.bytesDone, total, RaftTransferPhase.Finishing, null)
    }

    /** The server acknowledged the upload / the file was committed. */
    fun done(sizeBytes: Long? = null): RaftTransferProgress {
        if (progress.phase == RaftTransferPhase.Done) return progress
        resetRate()
        progress = progress.completed(sizeBytes)
        return progress
    }

    fun failed(): RaftTransferProgress = end(RaftTransferPhase.Failed)

    fun cancelled(): RaftTransferProgress = end(RaftTransferPhase.Cancelled)

    private fun end(phase: RaftTransferPhase): RaftTransferProgress {
        if (progress.phase.isTerminal) return progress
        resetRate()
        progress = progress.ended(phase)
        return progress
    }

    private fun resetRate() {
        samples.clear()
        labelShownAt = null
    }

    /** The speed to show: a fresh window rate when the label may change, else the one shown. */
    private fun shownSpeed(now: TimeMark): Long? {
        val shown = progress.bytesPerSecond
        val since = labelShownAt
        if (shown != null && since != null && since.elapsedNow() < labelInterval) return shown
        val rate = windowRate() ?: return shown
        labelShownAt = now
        return rate
    }

    private fun windowRate(): Long? {
        if (samples.size < 2) return null
        val first = samples.first()
        val last = samples.last()
        val millis = (first.at.elapsedNow() - last.at.elapsedNow()).inWholeMilliseconds
        val bytes = last.bytes - first.bytes
        if (millis <= 0L || bytes <= 0L) return null
        return maxOf(1L, (bytes.toDouble() * 1_000.0 / millis.toDouble()).toLong())
    }

    private fun publish(done: Long, total: Long?, phase: RaftTransferPhase, speed: Long?): RaftTransferProgress {
        progress = RaftTransferProgress.advance(progress, done, total, phase, speed)
        return progress
    }
}

/** A [TimeSource] over an owner's injectable millisecond clock (the download engine's `nowMillis`). */
internal fun raftMillisTimeSource(nowMillis: () -> Long): TimeSource =
    object : TimeSource {
        override fun markNow(): TimeMark {
            val at = nowMillis()
            return object : TimeMark {
                override fun elapsedNow(): Duration = (nowMillis() - at).milliseconds
            }
        }
    }
