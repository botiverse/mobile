package build.raft.app.core.data.diagnostics

import build.raft.app.BuildConfig
import java.util.concurrent.atomic.AtomicBoolean

/** Caller-facing log facade. Applies verbosity gates before delegating to Android local diagnostics. */
object SlockAppLog {
    private val verboseLoggingEnabled = AtomicBoolean(BuildConfig.DEBUG)

    fun setVerboseLoggingEnabled(enabled: Boolean) {
        verboseLoggingEnabled.set(BuildConfig.DEBUG || enabled)
    }

    fun d(tag: String, message: String) {
        SlockAndroidLocalDiagnostics.recordLog(
            level = "debug",
            tag = tag,
            message = message,
            includeNonCritical = verboseLoggingEnabled.get(),
        )
    }

    fun i(tag: String, message: String) {
        if (!verboseLoggingEnabled.get()) return
        SlockAndroidLocalDiagnostics.recordLog("info", tag, message)
    }

    fun w(tag: String, message: String) {
        SlockAndroidLocalDiagnostics.recordLog("warn", tag, message)
    }

    fun e(tag: String, message: String) {
        SlockAndroidLocalDiagnostics.recordLog("error", tag, message)
    }

    fun record(tag: String, message: String) {
        d(tag, message)
    }
}
