package build.raft.app

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** A process-owned sink: producers never wait for the system log writer. */
internal class RaftBoundedNativeLog<L>(
    scope: CoroutineScope,
    capacity: Int,
    private val maxChars: Int,
    private val warningLevel: L,
    private val write: (L, String) -> Unit
) {
    private data class Entry<L>(val level: L, val message: String)
    private val queue = Channel<Entry<L>>(capacity)
    private val dropped = atomic(0L)
    private val failures = atomic(0L)

    init {
        require(capacity > 0)
        require(maxChars > 0)
        scope.launch {
            for (entry in queue) {
                writeSafely(entry.level, entry.message)
                val lost = dropped.getAndSet(0L)
                val failed = failures.getAndSet(0L)
                if (lost != 0L || failed != 0L) {
                    // This breadcrumb also runs on the consumer, never through KNOI.
                    if (!writeSafely(warningLevel, "knoi_async_log_loss dropped=$lost writeFailures=$failed")) {
                        dropped.addAndGet(lost)
                        failures.addAndGet(failed)
                    }
                }
            }
        }
    }

    fun enqueue(level: L, message: String) {
        val bounded = if (message.length <= maxChars) message else
            message.take(maxChars) + " [truncated]"
        if (queue.trySend(Entry(level, bounded)).isFailure) dropped.incrementAndGet()
    }

    private fun writeSafely(level: L, message: String): Boolean {
        try {
            write(level, message)
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failures.incrementAndGet()
            return false
        }
    }
}
