package build.raft.app

import androidx.compose.runtime.snapshots.ObserverHandle
import androidx.compose.runtime.snapshots.Snapshot
import build.raft.app.data.defaultNowMillis

/**
 * DIAGNOSTIC ONLY (branch ios-flush-reentry-diag, not for main): finds what re-enters CMP 1.12.2's
 * `FlushCoroutineDispatcher.flush()` on iOS (the Create agent name crash, upstream CMP-10674).
 *
 * The dispatcher is internal to compose-ui, so the app cannot hook `flush()` itself. Two probes:
 *
 * - **Before the throw.** A global snapshot apply observer. Every IME edit is a global write, so the
 *   apply pump is the first task the nested `flush()` runs; its stack then holds two flush frames
 *   (outer + nested) plus the UIKit chain between them (touch/key/frame driver, the text-input
 *   collector, `selectionWillChange` / `unmarkText` / `replaceRange`, `TextInputConnection.edit`,
 *   `updateView`, `performFrame`). Detected by counting flush frames in the current stack.
 * - **At the crash.** [onUnhandledException], called from the unhandled-exception hook, prints the
 *   fatal throwable's full stack under the same tag when it involves the dispatcher.
 *
 * Every report goes to stdout (`println`, one line per stack frame so the console does not
 * truncate it) and to the app diagnostics log (`raftPlatformLog`, synchronous
 * critical path so it survives the termination that follows). grep `RAFT_FLUSH_REENTRY`.
 */
internal object RaftFlushReentryDiagnostics {
    const val Tag = "RAFT_FLUSH_REENTRY"
    private const val MaxReports = 20

    private var handle: ObserverHandle? = null
    private var reports = 0
    private var inProbe = false

    /** Main thread, once, at app start. */
    fun install() {
        if (handle != null) return
        handle = Snapshot.registerApplyObserver { changed, _ ->
            if (changed.isNotEmpty()) probe("apply_observer")
        }
        emit("probe_installed (apply observer + unhandled-exception hook)", stack = null)
    }

    private fun probe(site: String) {
        if (inProbe || reports >= MaxReports) return
        inProbe = true
        try {
            val stack = Throwable("$Tag probe").stackTraceToString()
            val depth = flushDepth(stack)
            if (depth < 2) return
            reports++
            emit(
                "reentry_detected site=$site flushDepth=$depth report=$reports/$MaxReports " +
                    "imeEdit=${stack.contains("TextInputConnection")} " +
                    "pointer=${stack.contains("sendPointerEvent")} key=${stack.contains("sendKeyEvent")} " +
                    "setContent=${stack.contains("setContent")} updateView=${stack.contains("ComposeSceneMediator")}",
                stack,
            )
        } catch (_: Throwable) {
            // Best effort: a diagnostic must never add a crash of its own.
        } finally {
            inProbe = false
        }
    }

    /** From the unhandled-exception hook, before the runtime terminates. Never throws. */
    fun onUnhandledException(throwable: Throwable) {
        try {
            val stack = throwable.stackTraceToString()
            val related =
                stack.contains("FlushCoroutineDispatcher") ||
                    stack.contains("ConcurrentModificationException") ||
                    stack.contains("CoroutinesInternalError")
            if (!related) return
            emit("fatal flushDepth=${flushDepth(stack)} ${throwable::class.qualifiedName}: ${throwable.message}", stack)
        } catch (_: Throwable) {
        }
    }

    /**
     * Nesting depth of `FlushCoroutineDispatcher.flush()` in a Kotlin/Native stack. Release stacks
     * may show only the `flush` lambda (`FlushCoroutineDispatcher$flush$1.invoke`) or only
     * `#flush()`, so take the larger count.
     */
    internal fun flushDepth(stack: String): Int {
        var named = 0
        var lambda = 0
        for (line in stack.lineSequence()) {
            if (!line.contains("FlushCoroutineDispatcher")) continue
            if (line.contains("#flush(")) named++
            if (line.contains("\$flush\$")) lambda++
        }
        return maxOf(named, lambda)
    }

    private fun emit(summary: String, stack: String?) {
        val id = defaultNowMillis()
        val head = "[$Tag] id=$id $summary"
        println(head)
        val lines = stack?.lines()?.filter { it.isNotBlank() }.orEmpty()
        lines.forEachIndexed { index, line ->
            val out = "[$Tag] id=$id ${index + 1}/${lines.size} ${line.trim()}"
            println(out)
        }
        runCatching {
            raftPlatformLog(
                level = RaftLogLevel.Error,
                tag = Tag,
                message = if (stack == null) "id=$id $summary" else "id=$id $summary\n$stack",
                timestampMillis = id,
                // The critical path records synchronously: the process usually dies right after.
                hint = RaftPlatformLogHint.AuthCritical,
            )
        }
    }
}
