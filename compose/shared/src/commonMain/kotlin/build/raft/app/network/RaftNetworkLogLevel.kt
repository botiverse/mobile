package build.raft.app.network

import build.raft.app.RaftLogLevel

private val RoutineRequestEvent = Regex("""^slock_http_(?:execute|multipart|binary)_(start|finish) """)
private val ResponseCode = Regex("""(?:^|\s)code=(\d+)(?:\s|$)""")
private val DurationMillis = Regex("""(?:^|\s)durationMs=(\d+)(?:\s|$)""")
private const val SlowRequestMillis = 3_000L

/**
 * Level for a shared network diagnostics line. Every request logged a `_start` and a `_finish` at
 * warn (Hands fb-52cc0499: 6,300 warn lines in 30 minutes for successful requests). A start, and
 * a finish with a 2xx/3xx response under [SlowRequestMillis], are routine: info. Failures, retries,
 * cancellations (`code=0`), error statuses, slow requests and every other line stay warn.
 */
internal fun raftNetworkLogLevel(message: String): RaftLogLevel {
    val event = RoutineRequestEvent.find(message) ?: return RaftLogLevel.Warn
    if (event.groupValues[1] == "start") return RaftLogLevel.Info
    val code = ResponseCode.find(message)?.groupValues?.get(1)?.toIntOrNull() ?: return RaftLogLevel.Warn
    if (code !in 200..399) return RaftLogLevel.Warn
    val durationMillis = DurationMillis.find(message)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    return if (durationMillis >= SlowRequestMillis) RaftLogLevel.Warn else RaftLogLevel.Info
}
