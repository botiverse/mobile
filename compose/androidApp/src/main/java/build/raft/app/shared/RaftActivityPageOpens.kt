package build.raft.app.shared

import android.os.Handler
import android.os.Looper
import build.raft.app.cmp.host.RaftNativePageOpenGate
import build.raft.app.cmp.host.RaftNativePageOpenOutcome
import build.raft.app.core.data.diagnostics.RaftAppLog
import kotlinx.serialization.json.JsonObject

/**
 * The Activity path's push rules (task #151 follow-up): one [RaftNativePageOpenGate] per task, so
 * an open of the page already on top of the task is a no-op, and while an Activity this task
 * started is still coming in (until its window's enter animation completes) the same page is
 * dropped and a different one queued (latest wins). Every Activity-path push of a page goes
 * through [open]: page navigators, the shell's notification, banner and shortcut opens (which
 * reach the opener outside a page's own open guard, or after its fixed window).
 *
 * [P] is the page host (the Activity in production, plain keys in tests). Hosts report their
 * lifecycle: [created] (on top of its task now), [entered] (enter animation complete), [destroyed].
 * A push whose new page never reports [entered] stops holding opens after [enterTimeoutMillis].
 * Main thread only.
 */
internal class RaftActivityPageOpens<P : Any>(
    private val schedule: (delayMillis: Long, block: () -> Unit) -> Unit,
    private val enterTimeoutMillis: Long = RAFT_ACTIVITY_ENTER_TIMEOUT_MS,
    /** A page on its way out (finishing): not the top page any more, though not yet [destroyed]. */
    private val leaving: (P) -> Boolean = { false },
    private val log: (String) -> Unit = {},
) {
    private class Page<P>(val host: P, val route: Any)

    private inner class Task {
        val pages = mutableListOf<Page<P>>()
        val gate = RaftNativePageOpenGate(topRoute = { pages.lastOrNull { !leaving(it.host) }?.route })
        /** Bumped per push: a timeout of an older push does nothing. */
        var pushSerial = 0
    }

    private val tasks = HashMap<Int, Task>()
    private val identities = RaftNativePageOpenGate(topRoute = { null })

    private fun task(taskId: Int): Task = tasks.getOrPut(taskId) { Task() }

    /** The route identity of a page (what [open] compares with the task's top page). */
    fun routeOf(pageName: String, pageData: JsonObject): Any = identities.routeOf(pageName, pageData)

    /**
     * Pushes [pageName] in [taskId] through [start], or drops / queues it. [pageData] is what the
     * new page will be created with (so it compares with [created]'s); [start] runs later when
     * the open is queued, and must check its opener is still there.
     */
    fun open(taskId: Int, pageName: String, pageData: JsonObject, start: () -> Unit): RaftNativePageOpenOutcome {
        val task = task(taskId)
        return task.gate.open(pageName, pageData) {
            val serial = ++task.pushSerial
            // Before the start: a start that throws must not hold this task's opens for good.
            schedule(enterTimeoutMillis) {
                if (task.pushSerial == serial && task.gate.transitionRunning) {
                    log("activity_open_enter_timeout page=$pageName")
                    task.gate.transitionEnded()
                }
            }
            start()
        }
    }

    /** [host] (showing [pageName] with [pageData]) is the top page of [taskId] now. */
    fun created(host: P, taskId: Int, pageName: String, pageData: JsonObject) {
        val task = task(taskId)
        task.pages.removeAll { it.host == host }
        task.pages += Page(host, routeOf(pageName, pageData))
    }

    /** [host]'s enter animation completed: a push into its task has ended. */
    fun entered(host: P) {
        tasks.values.firstOrNull { task -> task.pages.any { it.host == host } }?.gate?.transitionEnded()
    }

    /** [host] is gone (finished, or destroyed for a recreation that will report [created] again). */
    fun destroyed(host: P) {
        val entry = tasks.entries.firstOrNull { (_, task) -> task.pages.any { it.host == host } } ?: return
        val task = entry.value
        val wasTop = task.pages.lastOrNull()?.host == host
        task.pages.removeAll { it.host == host }
        // The page that was coming in went away (cleared by a new notification Intent, finished at
        // once): no enter animation will end the push.
        if (wasTop) task.gate.transitionEnded()
        if (task.pages.isEmpty() && !task.gate.transitionRunning) tasks.remove(entry.key)
    }

    companion object {
        private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

        /** The process's instance, for [RaftCmpActivity]. */
        val Process: RaftActivityPageOpens<RaftCmpActivity> by lazy {
            RaftActivityPageOpens(
                schedule = { delay, block -> mainHandler.postDelayed(block, delay) },
                leaving = { activity -> activity.isFinishing || activity.isDestroyed },
                log = { line -> RaftAppLog.record("SlockKuiklyActivity", line) },
            )
        }
    }
}

/**
 * How long a push holds opens if its Activity never reports its enter animation: well past the
 * system's activity open animation (~300-450 ms), short enough that a lost callback costs little.
 */
internal const val RAFT_ACTIVITY_ENTER_TIMEOUT_MS = 1_000L
