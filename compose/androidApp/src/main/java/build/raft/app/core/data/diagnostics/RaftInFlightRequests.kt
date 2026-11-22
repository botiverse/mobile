package build.raft.app.core.data.diagnostics

/**
 * Requests that logged a start and no finish yet.
 *
 * Routine `slock_http_*_start` lines are no longer persisted ([RaftPersistentDiagnosticsPolicy]),
 * so a request that hangs would leave no trace in the ring. This keeps the starts in memory,
 * drops them on the matching `_finish`/`_failed` (by `clientRequestId`), and lists what is still
 * open when diagnostics are exported (feedback archive, crash context).
 *
 * The end of a request is seen only through its log line, and a full log queue drops lines
 * (`slock_platform_log_queue_dropped`; up to 1.28 the shared queue also dropped one whenever two
 * threads logged at once, `admissionBusy=1`). No request runs much past its configured timeout
 * (Ktor's request timeout ends it, the request scheduler's timeout sits around it, and every exit
 * logs a finish or failed line), so one still open [GraceMillis] past its timeout lost its end line
 * (Hands cf580bb9: four requests listed as overdue for 5-16 min). [unfinished] counts those once in
 * a `network_request_finish_unobserved` line and forgets them.
 */
internal class RaftInFlightRequests(private val capacity: Int = 64) {
    private data class Started(val startedAtMillis: Long, val timeoutMillis: Long, val path: String, val line: String)

    /**
     * What an export lists: [finishUnobserved], one info line for the requests whose end line was
     * lost (null when there are none), and [open], one warn line per request still open, oldest first.
     */
    class Unfinished(val finishUnobserved: String?, val open: List<String>)

    private val lock = Any()
    private val open = LinkedHashMap<String, Started>()

    fun observe(message: String, nowMillis: Long) {
        if (!message.contains("slock_http_")) return
        val event = RequestEvent.find(message) ?: return
        val id = RequestId.find(message)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() } ?: return
        synchronized(lock) {
            if (event.groupValues[1] == "start") {
                val timeout = TotalTimeout.find(message)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
                val path = RequestPath.find(message)?.groupValues?.get(1) ?: "none"
                open.remove(id)
                // The fields after the event token: the line must not read as a routine start, or
                // the persistence policy would drop it again.
                open[id] = Started(nowMillis, timeout, path, message.substring(event.range.last + 1).take(LineMaxChars))
                if (open.size > capacity) open.remove(open.keys.first())
            } else {
                open.remove(id)
            }
        }
    }

    /**
     * One `network_request_unfinished` line per open request, oldest first; `overdue=true` once past
     * its configured timeout. A request open longer than its timeout plus [GraceMillis] (than
     * [NoTimeoutCeilingMillis] when its start named no timeout) lost its end line: it leaves the
     * table and is counted in [Unfinished.finishUnobserved], so no later export lists it again.
     */
    fun unfinished(nowMillis: Long): Unfinished =
        synchronized(lock) {
            val lost =
                open.filterValues { started ->
                    val maxAge = if (started.timeoutMillis >= 0) started.timeoutMillis + GraceMillis else NoTimeoutCeilingMillis
                    nowMillis - started.startedAtMillis > maxAge
                }
            open.keys.removeAll(lost.keys)
            Unfinished(
                finishUnobserved = if (lost.isEmpty()) null else finishUnobservedLine(lost.values, nowMillis),
                open =
                    open.map { (id, started) ->
                        val age = nowMillis - started.startedAtMillis
                        "network_request_unfinished clientRequestId=$id ageMs=$age timeoutMs=${started.timeoutMillis} " +
                            "overdue=${started.timeoutMillis in 0 until age} request=[${started.line}]"
                    },
            )
        }

    /**
     * `network_request_finish_unobserved count=… oldestAgeMs=… paths=…`: the distinct paths, oldest
     * first, at most [MaxListedPaths], then `+<n>` for the paths not listed.
     */
    private fun finishUnobservedLine(lost: Collection<Started>, nowMillis: Long): String {
        val paths = lost.map { it.path }.distinct()
        val notListed = paths.size - MaxListedPaths
        val listed = paths.take(MaxListedPaths).joinToString(",") + (if (notListed > 0) ",+$notListed" else "")
        return "network_request_finish_unobserved count=${lost.size} " +
            "oldestAgeMs=${nowMillis - lost.minOf { it.startedAtMillis }} paths=$listed"
    }

    private companion object {
        const val LineMaxChars = 400

        /** How long past its timeout a request may still be open before its end line counts as lost. */
        const val GraceMillis = 30_000L

        /**
         * How long after its start a request whose start named no timeout may still be open: the
         * longest any request is given (`RAFT_DIRECT_UPLOAD_TOTAL_TIMEOUT_MILLIS`, a direct upload's
         * 30 min), plus the grace.
         */
        const val NoTimeoutCeilingMillis = 30 * 60_000L + GraceMillis

        /** As many as an export lists open requests (`MaxUnfinishedRequestLines`). */
        const val MaxListedPaths = 8

        val RequestEvent = Regex("""slock_http_(?:execute|multipart|binary)_(start|finish|failed)\s""")
        val RequestId = Regex("""(?:^|\s)clientRequestId=(\S*)""")
        val TotalTimeout = Regex("""(?:^|\s)configTotalTimeoutMillis=(\d+)""")

        /** `path=`; a presigned upload logs `category=` in its place. */
        val RequestPath = Regex("""(?:^|\s)(?:path|category)=(\S+)""")
    }
}
