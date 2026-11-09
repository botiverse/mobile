package build.raft.app.core.data.diagnostics

/**
 * Requests that logged a start and no finish yet.
 *
 * Routine `slock_http_*_start` lines are no longer persisted ([RaftPersistentDiagnosticsPolicy]),
 * so a request that hangs would leave no trace in the ring. This keeps the starts in memory,
 * drops them on the matching `_finish`/`_failed` (by `clientRequestId`), and lists what is still
 * open when diagnostics are exported (feedback archive, crash context).
 */
internal class RaftInFlightRequests(private val capacity: Int = 64) {
    private data class Started(val startedAtMillis: Long, val timeoutMillis: Long, val line: String)

    private val lock = Any()
    private val open = LinkedHashMap<String, Started>()

    fun observe(message: String, nowMillis: Long) {
        if (!message.contains("slock_http_")) return
        val event = RequestEvent.find(message) ?: return
        val id = RequestId.find(message)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() } ?: return
        synchronized(lock) {
            if (event.groupValues[1] == "start") {
                val timeout = TotalTimeout.find(message)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
                open.remove(id)
                // The fields after the event token: the line must not read as a routine start, or
                // the persistence policy would drop it again.
                open[id] = Started(nowMillis, timeout, message.substring(event.range.last + 1).take(LineMaxChars))
                if (open.size > capacity) open.remove(open.keys.first())
            } else {
                open.remove(id)
            }
        }
    }

    /** One line per open request, oldest first; `overdue=true` once past its configured timeout. */
    fun unfinished(nowMillis: Long): List<String> =
        synchronized(lock) {
            open.map { (id, started) ->
                val age = nowMillis - started.startedAtMillis
                "network_request_unfinished clientRequestId=$id ageMs=$age timeoutMs=${started.timeoutMillis} " +
                    "overdue=${started.timeoutMillis in 0 until age} request=[${started.line}]"
            }
        }

    private companion object {
        const val LineMaxChars = 400
        val RequestEvent = Regex("""slock_http_(?:execute|multipart|binary)_(start|finish|failed)\s""")
        val RequestId = Regex("""(?:^|\s)clientRequestId=(\S*)""")
        val TotalTimeout = Regex("""(?:^|\s)configTotalTimeoutMillis=(\d+)""")
    }
}
