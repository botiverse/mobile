package build.raft.ci

import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult

/**
 * Per-test hang watchdog for JVM unit tests (task #32).
 *
 * Incident provenance: `:shared:testDebugUnitTest` produced zero output for 28+
 * minutes on CI until the job hit its 40-minute deadline (run 30905797837-era
 * diagnostics; first repro run 30847767147 attempt 1). A rerun "fixed" it
 * without ever identifying which test hung. This listener makes a hung test
 * loud and named instead of silent:
 *
 * - heartbeat: while a single test runs longer than [heartbeatMs], one line is
 *   printed every heartbeat interval, so CI logs are never silent about which
 *   test is executing;
 * - hang kill: if one test runs longer than [timeoutMs], the listener prints
 *   `HANG DETECTED in <test>` plus a full all-thread stack dump, then exits
 *   the fork with [EXIT_CODE_HANG]. Gradle reports the fork failure bound to
 *   the executing test, so the hang is attributed instead of masked.
 *
 * Configuration (Gradle JVM system properties):
 *   raft.test.watchdog.timeout.ms    default 60000
 *   raft.test.watchdog.heartbeat.ms  default 30000
 *   raft.test.watchdog.enabled       default true (set "false" to disable)
 *
 * The defaults are deliberately far above the normal per-test duration (the
 * shared unit suite is ~40s for thousands of tests, i.e. milliseconds per
 * test): the watchdog only ever fires on a genuinely stuck test.
 */
class HangWatchdogTestListener @JvmOverloads constructor(
    private val timeoutMs: Long = longProp(PROP_TIMEOUT_MS, DEFAULT_TIMEOUT_MS),
    private val heartbeatMs: Long = longProp(PROP_HEARTBEAT_MS, DEFAULT_HEARTBEAT_MS),
    private val enabled: Boolean = System.getProperty(PROP_ENABLED)?.toBoolean() ?: true,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleeper: (Long) -> Unit = { ms -> Thread.sleep(ms) },
    private val exiter: (Int) -> Unit = { code -> System.exit(code) },
    private val out: (String) -> Unit = { line -> println(line) },
    private val spawner: (Runnable) -> Unit = { body ->
        Thread(body, "raft-test-hang-watchdog").apply { isDaemon = true; start() }
    },
) : TestListener {

    companion object {
        const val PROP_TIMEOUT_MS = "raft.test.watchdog.timeout.ms"
        const val PROP_HEARTBEAT_MS = "raft.test.watchdog.heartbeat.ms"
        const val PROP_ENABLED = "raft.test.watchdog.enabled"
        const val DEFAULT_TIMEOUT_MS = 60_000L
        const val DEFAULT_HEARTBEAT_MS = 30_000L
        const val EXIT_CODE_HANG = 73

        private fun longProp(name: String, default: Long): Long =
            System.getProperty(name)?.toLongOrNull()?.takeIf { it > 0 } ?: default
    }

    private class Watched(val descriptor: TestDescriptor, val startMs: Long)

    @Volatile
    private var watched: Watched? = null

    override fun beforeSuite(suite: TestDescriptor) = Unit

    override fun afterSuite(suite: TestDescriptor, result: TestResult) = Unit

    override fun beforeTest(testDescriptor: TestDescriptor) {
        if (!enabled) return
        // Gradle also fires beforeTest for aggregate descriptors (the whole
        // run / class containers). Only real test methods have a className;
        // guard everything else so the watchdog binds to a single test.
        if (testDescriptor.className == null) return
        clearWatched()
        watched = Watched(testDescriptor, clock())
        spawner(Runnable { runWatchdog() })
    }

    override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {
        if (watched?.descriptor == testDescriptor) {
            clearWatched()
        }
    }

    private fun clearWatched() {
        watched = null
    }

    private fun runWatchdog() {
        val me = Thread.currentThread()
        var nextHeartbeatAt = heartbeatMs
        while (!me.isInterrupted) {
            val current = watched ?: return
            val elapsed = clock() - current.startMs
            val remaining = timeoutMs - elapsed
            if (remaining <= 0) {
                reportHang(current, elapsed)
                exiter(EXIT_CODE_HANG)
                return
            }
            val sleepFor = minOf(remaining, maxOf(1L, nextHeartbeatAt - elapsed))
            try {
                sleeper(sleepFor)
            } catch (_: InterruptedException) {
                return
            }
            val after = watched
            if (after == null || after.descriptor !== current.descriptor) return
            val nowElapsed = clock() - current.startMs
            if (nowElapsed >= nextHeartbeatAt) {
                out("raft-test-watchdog: still running ${identify(current.descriptor)} (${nowElapsed / 1000}s elapsed)")
                nextHeartbeatAt += heartbeatMs
            }
        }
    }

    private fun reportHang(current: Watched, elapsedMs: Long) {
        out("raft-test-watchdog: HANG DETECTED in ${identify(current.descriptor)} after ${elapsedMs / 1000}s (timeout ${timeoutMs}ms); dumping all threads before killing this fork with exit code $EXIT_CODE_HANG")
        dumpAllThreads()
    }

    private fun dumpAllThreads() {
        for ((thread, frames) in Thread.getAllStackTraces()) {
            out("raft-test-watchdog: thread \"${thread.name}\" state=${thread.state}")
            for (frame in frames) {
                out("raft-test-watchdog:     at $frame")
            }
        }
    }

    private fun identify(descriptor: TestDescriptor): String =
        "${descriptor.className}.${descriptor.name}"
}
