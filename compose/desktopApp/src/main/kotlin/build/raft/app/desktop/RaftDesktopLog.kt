package build.raft.app.desktop

import build.raft.app.RaftDesktopFileLog

/**
 * Host-side log lines (the Android host's RaftAppLog): the console, and the log file
 * ([RaftDesktopFileLog], `~/Library/Logs/Raft`). Debug lines need -Draft.desktop.debugLogs=true.
 */
object RaftDesktopLog {
    private val debug = System.getProperty("raft.desktop.debugLogs") == "true"

    fun d(tag: String, message: String) {
        if (!debug) return
        println("D $tag: $message")
        RaftDesktopFileLog.write("D", tag, message)
    }

    fun i(tag: String, message: String) {
        println("I $tag: $message")
        RaftDesktopFileLog.write("I", tag, message)
    }

    fun w(tag: String, message: String) {
        System.err.println("W $tag: $message")
        RaftDesktopFileLog.write("W", tag, message)
    }

    fun e(tag: String, message: String) {
        System.err.println("E $tag: $message")
        RaftDesktopFileLog.write("E", tag, message)
    }
}
