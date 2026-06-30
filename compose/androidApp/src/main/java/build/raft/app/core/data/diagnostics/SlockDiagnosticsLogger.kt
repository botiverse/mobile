package build.raft.app.core.data.diagnostics

import build.raft.app.BuildConfig
import java.util.concurrent.atomic.AtomicBoolean

object SlockDiagnosticsLogger {
    private val verboseLoggingEnabled = AtomicBoolean(BuildConfig.DEBUG)

    fun setVerboseLoggingEnabled(enabled: Boolean) {
        verboseLoggingEnabled.set(BuildConfig.DEBUG || enabled)
    }

    fun d(tag: String, message: String) {
        if (!verboseLoggingEnabled.get()) return
        SlockAndroidBuglyDiagnostics.recordLog("debug", tag, message)
    }

    fun i(tag: String, message: String) {
        if (!verboseLoggingEnabled.get()) return
        SlockAndroidBuglyDiagnostics.recordLog("info", tag, message)
    }

    fun w(tag: String, message: String) {
        SlockAndroidBuglyDiagnostics.recordLog("warn", tag, message)
    }

    fun e(tag: String, message: String) {
        SlockAndroidBuglyDiagnostics.recordLog("error", tag, message)
    }

    fun record(tag: String, message: String) {
        d(tag, message)
    }
}
