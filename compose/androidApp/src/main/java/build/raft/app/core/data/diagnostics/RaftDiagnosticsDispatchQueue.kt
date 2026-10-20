package build.raft.app.core.data.diagnostics

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal enum class DiagnosticCriticalHint {
    None,
    AuthCritical,
}

internal data class PendingDiagnosticLog(
    val level: String,
    val tag: String,
    val message: String,
    val timestampMillis: Long,
    val threadName: String,
    val sequence: Long,
    val includeNonCritical: Boolean,
    val criticalHint: DiagnosticCriticalHint,
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

/** Bounded two-lane handoff. Both lanes are drained by one sequence-ordered consumer. */
internal class RaftDiagnosticsDispatchQueue(
    capacity: Int = 2_048,
    criticalCapacity: Int = 64,
    initialSequence: Long = System.nanoTime(),
    threadFactory: ThreadFactory =
        ThreadFactory { runnable ->
            Thread(runnable, "SlockDiagnosticsDispatch").apply { isDaemon = true }
        },
    private val processor: (PendingDiagnosticLog, droppedBeforeDispatch: Long) -> Unit,
) {
    private val normalQueue = ArrayBlockingQueue<PendingDiagnosticLog>(capacity)
    private val criticalQueue = ArrayBlockingQueue<PendingDiagnosticLog>(criticalCapacity)
    private val availableEntries = Semaphore(0)
    private val acceptedEntries = AtomicLong(0L)
    private val processedEntries = AtomicLong(0L)
    private val droppedEntries = AtomicLong(0L)
    private val failedEntries = AtomicLong(0L)
    private val nextSequence = AtomicLong(initialSequence)
    private val enqueueLock = Any()
    private val flushMonitor = Object()

    init {
        threadFactory.newThread(::runLoop).apply {
            isDaemon = true
            start()
        }
    }

    fun enqueue(entry: PendingDiagnosticLog): Boolean {
        // Sequence assignment and lane publication share one short critical section, so accepted
        // entries cannot appear later with an older sequence under concurrent producers.
        val accepted = synchronized(enqueueLock) {
            val sequencedEntry = entry.copy(sequence = nextSequence.incrementAndGet())
            val offered = if (sequencedEntry.shouldUseCriticalLane()) {
                criticalQueue.offer(sequencedEntry)
            } else {
                normalQueue.offer(sequencedEntry)
            }
            if (offered) {
                acceptedEntries.incrementAndGet()
            } else {
                droppedEntries.incrementAndGet()
            }
            offered
        }
        if (!accepted) return false
        availableEntries.release()
        return true
    }

    fun flush(timeoutMillis: Long = 2_000L): Boolean {
        val target = acceptedEntries.get()
        if (processedEntries.get() >= target) return failedEntries.get() == 0L
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis.coerceAtLeast(0L))
        synchronized(flushMonitor) {
            while (processedEntries.get() < target) {
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0L) return false
                val waitMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos)
                val waitNanos = (remainingNanos - TimeUnit.MILLISECONDS.toNanos(waitMillis)).toInt()
                try {
                    flushMonitor.wait(waitMillis, waitNanos)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }
        return failedEntries.get() == 0L
    }

    private fun runLoop() {
        while (true) {
            try {
                availableEntries.acquire()
            } catch (_: InterruptedException) {
                continue
            }
            val entry = pollNextBySequence() ?: continue
            runCatching {
                processor(entry, droppedEntries.get())
            }.onFailure {
                droppedEntries.incrementAndGet()
                failedEntries.incrementAndGet()
            }
            processedEntries.incrementAndGet()
            synchronized(flushMonitor) {
                flushMonitor.notifyAll()
            }
        }
    }

    private fun pollNextBySequence(): PendingDiagnosticLog? {
        val normal = normalQueue.peek()
        val critical = criticalQueue.peek()
        return when {
            normal == null -> criticalQueue.poll()
            critical == null -> normalQueue.poll()
            normal.sequence <= critical.sequence -> normalQueue.poll()
            else -> criticalQueue.poll()
        }
    }

    private fun PendingDiagnosticLog.shouldUseCriticalLane(): Boolean =
        criticalHint != DiagnosticCriticalHint.None ||
            level.equals("warn", ignoreCase = true) ||
            level.equals("warning", ignoreCase = true) ||
            level.equals("error", ignoreCase = true) ||
            level.equals("fatal", ignoreCase = true)
}
