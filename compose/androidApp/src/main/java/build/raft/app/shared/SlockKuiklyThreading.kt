package build.raft.app.shared

import build.raft.app.core.data.diagnostics.SlockAppLog
import com.tencent.kuikly.core.render.android.adapter.IKRThreadAdapter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

internal object SlockKuiklyThreadAdapter : IKRThreadAdapter {
    /**
     * Stack size for Kuikly's Android context thread (HRContextQueueHandlerThread).
     *
     * Compose layout placement recurses once per nested layout level, and Kuikly
     * runs that recursion on its context thread, which otherwise gets the 1 MB
     * platform default (IKRThreadAdapter.stackSize() == 0). Deep trees such as the
     * desktop split layout opening a thread pane exhausted that default with a
     * StackOverflowError made entirely of placement frames. 8 MB matches the iOS
     * main-thread budget; it costs virtual address space only on this one thread.
     */
    private const val CONTEXT_THREAD_STACK_SIZE_BYTES: Long = 8L shl 20

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

    override fun stackSize(): Long = CONTEXT_THREAD_STACK_SIZE_BYTES

    override fun executeOnSubThread(task: () -> Unit) {
        execute(label = "kuikly-adapter", task = task)
    }

    fun execute(label: String, task: () -> Unit) {
        executor.execute {
            SlockAppLog.record(
                    "SlockKuiklyThreading",
                    "native_task_start label=$label thread=${Thread.currentThread().name}"
            )
            task()
        }
    }
}
