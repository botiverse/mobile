package build.raft.app.shared

import build.raft.app.core.data.diagnostics.RaftAppLog
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/** Background executor for native bridge work (formerly also Kuikly's thread adapter). */
internal object RaftThreadAdapter {
    private val threadIds = AtomicInteger(1)
    private val executor: ExecutorService =
            Executors.newFixedThreadPool(
                    4,
                    ThreadFactory { runnable ->
                        Thread(runnable, "slock-kuikly-bg-${threadIds.getAndIncrement()}").apply {
                            isDaemon = false
                        }
                    }
            )

    fun execute(label: String, task: () -> Unit) {
        executor.execute {
            RaftAppLog.record(
                    "SlockKuiklyThreading",
                    "native_task_start label=$label thread=${Thread.currentThread().name}"
            )
            task()
        }
    }
}
