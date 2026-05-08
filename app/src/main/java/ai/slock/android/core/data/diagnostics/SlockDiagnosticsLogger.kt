package ai.slock.android.core.data.diagnostics

import android.util.Log

object SlockDiagnosticsLogger {
    fun d(tag: String, message: String) {
        runCatching { Log.d(tag, message) }
        SanitizedDiagnosticsLogBuffer.record(tag, message)
    }

    fun w(tag: String, message: String) {
        runCatching { Log.w(tag, message) }
        SanitizedDiagnosticsLogBuffer.record(tag, message)
    }

    fun record(tag: String, message: String) {
        d(tag, message)
    }
}
