package ai.slock.android.shared

import ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger
import com.tencent.kuikly.core.render.android.adapter.IKRThreadAdapter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

internal object SlockKuiklyThreadAdapter : IKRThreadAdapter {
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

    override fun executeOnSubThread(task: () -> Unit) {
        execute(label = "kuikly-adapter", task = task)
    }

    fun execute(label: String, task: () -> Unit) {
        executor.execute {
            SlockDiagnosticsLogger.record(
                    "SlockKuiklyThreading",
                    "native_task_start label=$label thread=${Thread.currentThread().name}"
            )
            task()
        }
    }
}
