package build.raft.app.shared

import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import build.raft.app.core.data.diagnostics.SlockAndroidLocalDiagnostics
import build.raft.app.core.data.diagnostics.SlockPerformanceTraceRing
import java.util.concurrent.ConcurrentHashMap

/**
 * Names who holds the main thread during a long frame (task #37/#39: a 478 ms `jank_frame` in the
 * same window as a fast-fling blank, with nothing in the trace saying what ran during it).
 *
 * A background thread posts a heartbeat to the main looper every [HeartbeatIntervalMs]. An idle
 * main looper runs it within a frame, so a heartbeat still pending after [StallThresholdMs] means
 * the main thread was busy for that long. While it stays pending, the watchdog takes up to
 * [MaxSamplesPerStall] samples; each sample is ONE `Thread.getAllStackTraces()` call, so the main
 * and Kuikly ([KuiklyThreadName]) stacks in a line belong to the same instant. A main stack whose
 * top frame is a looper wait is reported as `mainWaiting=true` (not working, waiting).
 *
 * Alpha-only (performance trace switch) and budgeted to [MaxStallsPerProcess] stalls per process.
 */
internal object SlockMainThreadStallWatchdog {
    private const val Tag = "thread_stall"
    private const val HeartbeatIntervalMs = 50L
    private const val StallThresholdMs = 150L
    private const val SampleSpacingMs = 150L
    private const val MaxSamplesPerStall = 4
    private const val MaxStallsPerProcess = 15
    private const val MaxFramesPerStack = 14
    private const val KuiklyThreadName = "HRContextQueueHandlerThread"

    @Volatile private var started = false
    @Volatile private var heartbeatPostedAt = 0L
    @Volatile private var heartbeatPending = false
    private val bridgeThreadsReported = ConcurrentHashMap.newKeySet<String>()

    /** Called from the main thread (frame probe); starts the watchdog once per process. */
    fun ensureStarted() {
        if (started || !SlockPerformanceTraceRing.isEnabled()) return
        started = true
        val main = Handler(Looper.getMainLooper())
        val thread = Thread({ loop(main) }, "slock-stall-watchdog")
        thread.isDaemon = true
        thread.priority = Thread.NORM_PRIORITY
        thread.start()
        SlockAndroidLocalDiagnostics.recordLog(
            "info",
            Tag,
            "thread_stall_watchdog_started main=${Looper.getMainLooper().thread.name} " +
                "drawThread=${Thread.currentThread().name} thresholdMs=$StallThresholdMs"
        )
    }

    /** Records, once per bridge method, which thread the Kotlin side calls the native bridge on. */
    fun noteBridgeThread(method: String) {
        if (!SlockPerformanceTraceRing.isEnabled() || !bridgeThreadsReported.add(method)) return
        val current = Thread.currentThread()
        SlockAndroidLocalDiagnostics.recordLog(
            "info",
            Tag,
            "thread_bridge_caller method=$method thread=${current.name} " +
                "isMain=${current === Looper.getMainLooper().thread}"
        )
    }

    private fun loop(main: Handler) {
        var stalls = 0
        var stallId = 0
        var samples = 0
        var nextSampleAt = 0L
        while (stalls < MaxStallsPerProcess || samples > 0) {
            val now = SystemClock.uptimeMillis()
            if (!heartbeatPending) {
                if (samples > 0) {
                    SlockAndroidLocalDiagnostics.recordLog(
                        "info",
                        Tag,
                        "thread_stall_end stall=$stallId t=$now samples=$samples"
                    )
                    samples = 0
                }
                heartbeatPostedAt = now
                heartbeatPending = true
                // Asynchronous, so a pending traversal barrier cannot hold it back past a frame.
                val heartbeat = Message.obtain(main) { heartbeatPending = false }
                heartbeat.isAsynchronous = true
                main.sendMessageAtFrontOfQueue(heartbeat)
            } else {
                val pendingMs = now - heartbeatPostedAt
                if (pendingMs >= StallThresholdMs && samples < MaxSamplesPerStall) {
                    if (samples == 0) {
                        stalls += 1
                        stallId = stalls
                        nextSampleAt = now
                    }
                    if (now >= nextSampleAt) {
                        samples += 1
                        nextSampleAt = now + SampleSpacingMs
                        sample(stallId, samples, now, pendingMs)
                    }
                }
            }
            try {
                Thread.sleep(HeartbeatIntervalMs)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun sample(stallId: Int, sampleIndex: Int, now: Long, pendingMs: Long) {
        val all = runCatching { Thread.getAllStackTraces() }.getOrNull() ?: return
        val mainThread = Looper.getMainLooper().thread
        val mainStack = all[mainThread].orEmpty()
        val kuikly = all.entries.firstOrNull { it.key.name == KuiklyThreadName }
        SlockAndroidLocalDiagnostics.recordLog(
            "info",
            Tag,
            "thread_stall_sample stall=$stallId sample=$sampleIndex t=$now heartbeatPendingMs=$pendingMs " +
                "mainState=${mainThread.state} mainWaiting=${isLooperWait(mainStack)} " +
                "main=${formatStack(mainStack)} " +
                "kuiklyState=${kuikly?.key?.state ?: "absent"} " +
                "kuiklyWaiting=${kuikly?.value?.let(::isLooperWait) ?: false} " +
                "kuikly=${formatStack(kuikly?.value.orEmpty())}"
        )
    }

    private fun isLooperWait(stack: Array<StackTraceElement>): Boolean {
        val top = stack.firstOrNull() ?: return false
        return top.methodName == "nativePollOnce" && top.className == "android.os.MessageQueue"
    }

    private fun formatStack(stack: Array<StackTraceElement>): String =
        if (stack.isEmpty()) {
            "[]"
        } else {
            stack.take(MaxFramesPerStack).joinToString(separator = "<", prefix = "[", postfix = "]") { frame ->
                "${frame.className.substringAfterLast('.')}.${frame.methodName}:${frame.lineNumber}"
            }
        }
}
