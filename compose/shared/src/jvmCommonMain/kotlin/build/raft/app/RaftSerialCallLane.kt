package build.raft.app

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Runs calls that may wait for another thread (a native call that blocks until the platform's
 * main thread runs it) on one dedicated daemon thread, in the order they were made, so the thread
 * making them never waits. Each call's outcome, its value or its failure, reaches the caller
 * through [deliver] (the UI thread's queue on desktop); a failing call never stops the next one.
 *
 * Nothing may wait for a call made here: [call] and [post] return at once. The desktop WebView's
 * main-thread calls go through it (desktopMain `RaftDesktopWryCalls`, #317). Plain JVM, so the
 * Android host tests prove it.
 */
internal class RaftSerialCallLane(
    threadName: String,
    private val deliver: (Runnable) -> Unit,
) {
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { work -> Thread(work, threadName).apply { isDaemon = true } }

    /** Runs [work] on the lane without waiting for it; [onResult] gets its outcome through [deliver]. */
    fun <T> call(
        work: () -> T,
        onResult: (Result<T>) -> Unit,
    ) {
        executor.execute {
            val result = runCatching(work)
            deliver(Runnable { onResult(result) })
        }
    }

    /**
     * Runs [work] on the lane without waiting for it; its outcome, a failure included, is dropped.
     * (Not `run`: the standard library's `run {}` would take a call it did not match and run it here.)
     */
    fun post(work: () -> Unit) {
        executor.execute { runCatching(work) }
    }
}
