package build.raft.app.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.WindowExceptionHandler
import build.raft.app.RaftDesktopFileLog
import build.raft.app.raftDesktopThrowableLines
import java.awt.GraphicsEnvironment
import java.io.File
import java.lang.management.ManagementFactory
import java.lang.management.MemoryUsage
import javax.management.NotificationEmitter
import javax.management.openmbean.CompositeData

/**
 * What the desktop app records about crashes and memory, in the log file ([RaftDesktopFileLog]).
 * A Java process that dies leaves no macOS crash report (.ips), and the packaged app's stderr goes
 * nowhere, so without this a crash left no trace (artin 11-03: froze on opening a reply, then quit).
 *
 * - Startup facts: version, OS, JVM, heap limit.
 * - An uncaught throwable on any thread (the UI thread included: Compose rethrows a composition
 *   failure there after showing its error dialog) is logged with its stack trace before the
 *   previous handler runs; an OutOfMemoryError also logs the heap.
 * - A session that ended without the JVM shutting down (killed, a native crash, the OS) is
 *   reported at the next start ([UNCLEAN_EXIT_MARKER]).
 * - Garbage collections that pause long or run a full collection, and a heap still over 85 % after
 *   a collection, are logged (the freeze's sample showed full collections).
 */
object RaftDesktopCrashReporting {
    const val TAG = "RaftDesktopCrash"
    internal const val UNCLEAN_EXIT_MARKER = "session.running"
    private const val SLOW_GC_MILLIS = 200L
    private const val HEAP_PRESSURE_RATIO = 0.85

    @Volatile private var lastLogged: Throwable? = null

    /** Call first thing in main (after the second-instance hand-off, which exits). */
    fun install(logDir: File? = null) {
        if (logDir != null) RaftDesktopFileLog.install(logDir) else RaftDesktopFileLog.install()
        val dir = RaftDesktopFileLog.directory
        logStartup()
        if (dir != null) checkPreviousSession(dir)
        installUncaughtHandler()
        installGcMonitor()
        warmUpAwtFonts()
    }

    /** Logs [throwable] as fatal for [source], once per throwable, and flushes the file. */
    fun logFatal(source: String, thread: Thread, throwable: Throwable) {
        if (lastLogged === throwable) return
        lastLogged = throwable
        val header =
            buildList {
                add("crash source=$source thread=${thread.name} type=${throwable.javaClass.name}")
                if (throwable is OutOfMemoryError) add("out_of_memory ${heapSummary()}")
            }
        RaftDesktopFileLog.writeNow("F", TAG, header + raftDesktopThrowableLines(throwable))
    }

    /**
     * Wraps a Compose window's exception handler (the default shows an error dialog, then closes
     * the window, which quits the app): log first, then the same behaviour.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    fun wrapWindowExceptionHandler(previous: WindowExceptionHandler?): WindowExceptionHandler =
        WindowExceptionHandler { throwable ->
            if (isRecoverableDisposedSceneError(throwable)) {
                // An event or frame reached a popup / hover card / layer whose Compose scene was
                // already torn down (CMP desktop, "RootNodeOwner is already disposed"). The owner
                // is gone, so there is nothing left to update: log it and keep the window open
                // instead of the default error dialog that quits the app (artin 11-03).
                logRecovered("compose_window", Thread.currentThread(), throwable)
                return@WindowExceptionHandler
            }
            logFatal("compose_window", Thread.currentThread(), throwable)
            if (previous != null) previous.onException(throwable) else throw throwable
        }

    /** A late event on a disposed Compose scene owner; safe to drop (see [wrapWindowExceptionHandler]). */
    internal fun isRecoverableDisposedSceneError(throwable: Throwable): Boolean =
        generateSequence(throwable) { it.cause }.take(5).any { cause ->
            cause is IllegalStateException && cause.message?.contains("RootNodeOwner is already disposed") == true
        }

    private fun logRecovered(source: String, thread: Thread, throwable: Throwable) {
        RaftDesktopFileLog.writeNow(
            "W",
            TAG,
            listOf("recovered_disposed_scene source=$source thread=${thread.name}") + raftDesktopThrowableLines(throwable),
        )
    }

    internal fun installUncaughtHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is RaftUncaughtHandler) return
        Thread.setDefaultUncaughtExceptionHandler(RaftUncaughtHandler(previous))
    }

    internal class RaftUncaughtHandler(private val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            runCatching { logFatal("uncaught", thread, throwable) }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                System.err.print("Exception in thread \"${thread.name}\" ")
                throwable.printStackTrace()
            }
        }
    }

    /**
     * [UNCLEAN_EXIT_MARKER] holds the running session's start; the JVM's shutdown deletes it. Found
     * at start, the previous session never shut down.
     */
    internal fun checkPreviousSession(dir: File, now: Long = System.currentTimeMillis()): String? {
        val marker = File(dir, UNCLEAN_EXIT_MARKER)
        val previous = runCatching { marker.takeIf { it.isFile }?.readText()?.trim() }.getOrNull()
        if (previous != null) {
            RaftDesktopLog.w(TAG, "previous_session_unclean_exit $previous (killed, a native crash, or the system ended it; see the lines before this session)")
        }
        runCatching {
            marker.writeText("startedAtMillis=$now pid=${ProcessHandle.current().pid()}")
            marker.deleteOnExit()
        }
        return previous
    }

    private fun logStartup() {
        val runtime = Runtime.getRuntime()
        val physical =
            runCatching {
                (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)
                    ?.totalMemorySize?.let { it / MB }
            }.getOrNull()
        RaftDesktopLog.i(
            TAG,
            "startup version=${System.getProperty("raft.desktop.version") ?: "dev"} " +
                "build=${System.getProperty("raft.desktop.build") ?: "0"} " +
                "os=${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")} " +
                "java=${System.getProperty("java.runtime.version")} vm=${System.getProperty("java.vm.name")} " +
                "heapMaxMb=${runtime.maxMemory() / MB} cpus=${runtime.availableProcessors()} " +
                "physicalMb=${physical ?: "?"} args=${ManagementFactory.getRuntimeMXBean().inputArguments.filter { it.startsWith("-X") }}",
        )
    }

    private fun installGcMonitor() {
        runCatching {
            ManagementFactory.getGarbageCollectorMXBeans().forEach { bean ->
                (bean as? NotificationEmitter)?.addNotificationListener(
                    { notification, _ ->
                        if (notification.type == "com.sun.management.gc.notification") {
                            runCatching { onGc(notification.userData as CompositeData) }
                        }
                    },
                    null,
                    null,
                )
            }
        }.onFailure { RaftDesktopLog.w(TAG, "gc_monitor unavailable ${it.javaClass.simpleName}") }
    }

    private fun onGc(data: CompositeData) {
        val info = com.sun.management.GarbageCollectionNotificationInfo.from(data)
        val gc = info.gcInfo
        val duration = gc.duration
        val after = gc.memoryUsageAfterGc.values.sumOf { it.used }
        val max = Runtime.getRuntime().maxMemory()
        val full = info.gcAction.contains("major", ignoreCase = true) || info.gcName.contains("Old", ignoreCase = true)
        val pressure = max > 0 && after > max * HEAP_PRESSURE_RATIO
        if (duration < SLOW_GC_MILLIS && !full && !pressure) return
        val line =
            "gc name=${info.gcName} action=${info.gcAction} cause=${info.gcCause} durationMs=$duration " +
                "heapAfterMb=${after / MB} heapMaxMb=${max / MB}"
        if (pressure) RaftDesktopLog.w(TAG, "heap_pressure $line") else RaftDesktopLog.i(TAG, line)
    }

    private fun heapSummary(): String {
        val heap: MemoryUsage? = runCatching { ManagementFactory.getMemoryMXBean().heapMemoryUsage }.getOrNull()
        return if (heap == null) {
            "heap=?"
        } else {
            "heapUsedMb=${heap.used / MB} heapCommittedMb=${heap.committed / MB} heapMaxMb=${heap.max / MB}"
        }
    }

    /**
     * AWT's font list (CoreText registration on macOS) loads the first time any Swing text is
     * shown, e.g. Compose's error dialog, a file panel, a tooltip window. That took a noticeable part
     * of the UI thread in the freeze's sample; load it once at startup on a background thread.
     */
    private fun warmUpAwtFonts() {
        if (GraphicsEnvironment.isHeadless()) return
        Thread(
            {
                val started = System.nanoTime()
                runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.size }
                    .onSuccess { count ->
                        RaftDesktopLog.i(TAG, "awt_fonts_warm families=$count ms=${(System.nanoTime() - started) / 1_000_000}")
                    }
            },
            "RaftDesktopFontWarmUp",
        ).apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    private const val MB = 1024L * 1024L
}
