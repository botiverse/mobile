package build.raft.app.core.data.diagnostics

import build.raft.app.BuildConfig
import build.raft.app.RaftVerboseDiagnostics
import java.util.concurrent.atomic.AtomicBoolean

/** Caller-facing log facade. Applies verbosity gates before delegating to Android local diagnostics. */
object RaftAppLog {
    private val verboseLoggingEnabled = AtomicBoolean(BuildConfig.DEBUG)

    fun setVerboseLoggingEnabled(enabled: Boolean) {
        verboseLoggingEnabled.set(BuildConfig.DEBUG || enabled)
    }

    /** Debug builds, or Settings > Developer > Verbose diagnostics. */
    private fun verbose(): Boolean = verboseLoggingEnabled.get() || RaftVerboseDiagnostics.enabled

    fun d(tag: String, message: String) {
        if (!verbose()) return
        RaftAndroidLocalDiagnostics.recordLog(
            level = "debug",
            tag = tag,
            message = message,
        )
    }

    fun auth(tag: String, message: String) {
        RaftAndroidLocalDiagnostics.recordAuthLog(
            level = "debug",
            tag = tag,
            message = message,
            includeNonCritical = verbose(),
        )
    }

    /** Auth-critical line: always kept, on the critical lane, and journaled when it carries an auth marker. */
    fun authCritical(tag: String, message: String) {
        RaftAndroidLocalDiagnostics.recordCriticalLog(level = "warn", tag = tag, message = message)
    }

    fun i(tag: String, message: String) {
        if (!verbose()) return
        RaftAndroidLocalDiagnostics.recordLog("info", tag, message)
    }

    fun w(tag: String, message: String) {
        RaftAndroidLocalDiagnostics.recordLog("warn", tag, message)
    }

    fun e(tag: String, message: String) {
        RaftAndroidLocalDiagnostics.recordLog("error", tag, message)
    }

    fun record(tag: String, message: String) {
        d(tag, message)
    }
}
