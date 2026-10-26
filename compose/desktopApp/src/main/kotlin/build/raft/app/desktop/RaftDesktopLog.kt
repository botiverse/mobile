package build.raft.app.desktop

/** Host-side log lines (the Android host's RaftAppLog); debug lines need -Draft.desktop.debugLogs=true. */
object RaftDesktopLog {
    private val debug = System.getProperty("raft.desktop.debugLogs") == "true"

    fun d(tag: String, message: String) {
        if (debug) println("D $tag: $message")
    }

    fun i(tag: String, message: String) = println("I $tag: $message")

    fun w(tag: String, message: String) = System.err.println("W $tag: $message")

    fun e(tag: String, message: String) = System.err.println("E $tag: $message")
}
