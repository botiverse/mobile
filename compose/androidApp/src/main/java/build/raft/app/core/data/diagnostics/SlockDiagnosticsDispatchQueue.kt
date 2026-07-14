package build.raft.app.core.data.diagnostics

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal data class PendingDiagnosticLog(
    val level: String,
    val tag: String,
    val message: String,
    val timestampMillis: Long,
    val threadName: String,
    val sequence: Long,
    val includeNonCritical: Boolean,
)

internal data class SanitizedDiagnosticLog(
    val level: String,
    val tag: String,
    val message: String,
    val timestampMillis: Long,
    val threadName: String,
    val sequence: Long,
    val droppedBeforeDispatch: Long,
)

/** Bounded single-thread handoff. Rejection never runs diagnostics work on the caller. */
internal class SlockDiagnosticsDispatchQueue(
    capacity: Int = 2_048,
    threadFactory: ThreadFactory =
        ThreadFactory { runnable ->
            Thread(runnable, "SlockDiagnosticsDispatch").apply { isDaemon = true }
        },
    private val processor: (PendingDiagnosticLog, droppedBeforeDispatch: Long) -> Unit,
) {
    private val droppedEntries = AtomicLong(0L)
    private val emergencyEntry = AtomicReference<PendingDiagnosticLog?>(null)
    private val executor =
        ThreadPoolExecutor(
            1,
            1,
            30L,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(capacity),
            threadFactory,
            { runnable, _ ->
                val entry = (runnable as? DiagnosticTask)?.entry
                val preserved =
                    entry != null &&
                        entry.shouldPreserveOnSaturation() &&
                        emergencyEntry.compareAndSet(null, entry)
                if (!preserved) droppedEntries.incrementAndGet()
            },
        )

    init {
        executor.prestartCoreThread()
    }

    fun enqueue(entry: PendingDiagnosticLog) {
        executor.execute(DiagnosticTask(entry))
    }

    fun flush(timeoutSeconds: Long = 2L) {
        val marker = FutureTask<Unit> { drainEmergency() }
        val queued =
            runCatching {
                executor.queue.offer(marker, timeoutSeconds, TimeUnit.SECONDS)
            }.getOrDefault(false)
        if (!queued) return
        runCatching { marker.get(timeoutSeconds, TimeUnit.SECONDS) }
    }

    private fun process(entry: PendingDiagnosticLog) {
        processor(entry, droppedEntries.get())
    }

    private fun drainEmergencyWhenIdle() {
        if (executor.queue.isNotEmpty()) return
        drainEmergency()
    }

    private fun drainEmergency() {
        while (true) {
            val entry = emergencyEntry.getAndSet(null) ?: return
            process(entry)
        }
    }

    private inner class DiagnosticTask(val entry: PendingDiagnosticLog) : Runnable {
        override fun run() {
            try {
                process(entry)
            } finally {
                drainEmergencyWhenIdle()
            }
        }
    }

    private fun PendingDiagnosticLog.shouldPreserveOnSaturation(): Boolean =
        !includeNonCritical ||
            level.equals("warn", ignoreCase = true) ||
            level.equals("warning", ignoreCase = true) ||
            level.equals("error", ignoreCase = true) ||
            level.equals("fatal", ignoreCase = true)
}
