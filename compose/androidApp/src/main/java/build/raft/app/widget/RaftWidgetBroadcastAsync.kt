package build.raft.app.widget

import android.content.BroadcastReceiver
import build.raft.app.core.data.diagnostics.RaftAppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bounded background runner for AppWidget [BroadcastReceiver] callbacks (`onUpdate`, `onReceive`,
 * `onDeleted`). Moves MMKV / SharedPreferences reads, cached avatar bitmap decodes, and
 * `AppWidgetManager` IPCs off the main thread while keeping the broadcast alive via
 * [BroadcastReceiver.PendingResult] up to [finishTimeoutMillis] (below Android's 10 s foreground
 * broadcast ANR threshold).
 */
internal class RaftWidgetBroadcastRunner(
    workDispatcher: CoroutineDispatcher,
    coordinatorDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val finishTimeoutMillis: Long = DEFAULT_FINISH_TIMEOUT_MS,
    private val onError: (Throwable) -> Unit = { throwable ->
        RaftAppLog.w(TAG, "widget_broadcast_work_failed error=${throwable.javaClass.simpleName}")
    },
) {
    private val workScope = CoroutineScope(SupervisorJob() + workDispatcher)
    private val coordinatorScope = CoroutineScope(SupervisorJob() + coordinatorDispatcher)

    fun launch(
        finish: (() -> Unit)?,
        block: suspend () -> Unit,
    ): Job {
        val workJob =
            try {
                workScope.launch {
                    try {
                        block()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (throwable: Throwable) {
                        onError(throwable)
                    }
                }
            } catch (throwable: Throwable) {
                runCatching { finish?.invoke() }
                throw throwable
            }
        if (finish == null) return workJob
        try {
            coordinatorScope.launch {
                try {
                    withTimeoutOrNull(finishTimeoutMillis) {
                        workJob.join()
                    }
                } finally {
                    runCatching { finish() }
                }
            }
        } catch (throwable: Throwable) {
            runCatching { finish() }
            throw throwable
        }
        return workJob
    }

    companion object {
        private const val TAG = "RaftWidgetBroadcast"

        // Stay safely below Android's 10 s foreground BroadcastReceiver ANR window even if a
        // network widget refresh stalls; cached views have already been pushed by this point.
        internal const val DEFAULT_FINISH_TIMEOUT_MS: Long = 7_000L
    }
}

internal object RaftWidgetBroadcastAsync {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val defaultRunner =
        RaftWidgetBroadcastRunner(
            workDispatcher = Dispatchers.IO.limitedParallelism(2),
        )

    @Volatile
    internal var runner: RaftWidgetBroadcastRunner = defaultRunner

    fun launch(
        pendingResult: BroadcastReceiver.PendingResult?,
        block: suspend () -> Unit,
    ): Job =
        runner.launch(
            finish = pendingResult?.let { result -> { result.finish() } },
            block = block,
        )

    internal fun resetForTest() {
        runner = defaultRunner
    }
}
