package ai.slock.android.core.data.diagnostics

import ai.slock.android.BuildConfig
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

object SlockDiagnosticsLogger {
    private val verboseLoggingEnabled = AtomicBoolean(BuildConfig.DEBUG)

    fun setVerboseLoggingEnabled(enabled: Boolean) {
        verboseLoggingEnabled.set(BuildConfig.DEBUG || enabled)
    }

    fun d(tag: String, message: String) {
        if (!verboseLoggingEnabled.get()) return
        runCatching { Log.d(tag, message) }
        SanitizedDiagnosticsLogBuffer.record(tag, message)
    }

    fun i(tag: String, message: String) {
        if (!verboseLoggingEnabled.get()) return
        runCatching { Log.i(tag, message) }
        SanitizedDiagnosticsLogBuffer.record(tag, message)
    }

    fun w(tag: String, message: String) {
        runCatching { Log.w(tag, message) }
        SanitizedDiagnosticsLogBuffer.record(tag, message)
    }

    fun e(tag: String, message: String) {
        runCatching { Log.e(tag, message) }
        SanitizedDiagnosticsLogBuffer.record(tag, message)
    }

    fun record(tag: String, message: String) {
        d(tag, message)
    }
}
