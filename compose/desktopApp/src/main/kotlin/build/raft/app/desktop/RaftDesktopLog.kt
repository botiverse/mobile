package build.raft.app.desktop

import build.raft.app.data.RaftDesktopLogFile
import java.awt.Desktop

/**
 * Host-side log lines (the Android host's RaftAppLog); debug lines need -Draft.desktop.debugLogs=true.
 * Printed to stdout / stderr and appended to the log file ([RaftDesktopLogFile], redacted).
 */
object RaftDesktopLog {
    private val debug = System.getProperty("raft.desktop.debugLogs") == "true"

    fun d(tag: String, message: String) {
        if (!debug) return
        println("D $tag: $message")
        RaftDesktopLogFile.append("D", tag, message)
    }

    fun i(tag: String, message: String) {
        println("I $tag: $message")
        RaftDesktopLogFile.append("I", tag, message)
    }

    fun w(tag: String, message: String) {
        System.err.println("W $tag: $message")
        RaftDesktopLogFile.append("W", tag, message)
    }

    fun e(tag: String, message: String) {
        System.err.println("E $tag: $message")
        RaftDesktopLogFile.append("E", tag, message)
    }

    /** Opens the log folder in Finder (the "Show Logs in Finder" menu item). */
    fun showLogFolder() {
        val dir = RaftDesktopLogFile.directory.apply { mkdirs() }
        runCatching { Desktop.getDesktop().open(dir) }
            .onFailure { w("RaftDesktop", "show logs failed: ${it::class.simpleName}") }
    }
}
