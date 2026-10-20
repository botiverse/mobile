package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

@RaftCoreInternalApi
suspend inline fun <T> parseResponseBodyWithTrace(
    source: String,
    markerPrefix: String,
    body: String,
    code: Int? = null,
    hasBody: Boolean = true,
    parseOnDefault: Boolean = true,
    diagnosticsLogger: (String) -> Unit = {},
    crossinline resultLabel: (T) -> String = { "content" },
    crossinline parser: () -> T
): T {
    val responseBytes = body.encodeToByteArray().size
    val dispatcherLabel = if (parseOnDefault) RAFT_JSON_PARSE_DISPATCHER_LABEL else "caller"
    diagnosticsLogger(
        "${markerPrefix}_network_body_received source=$source " +
            "code=${code ?: "unknown"} responseBytes=$responseBytes hasBody=$hasBody"
    )
    val parseStartedAt = TimeSource.Monotonic.markNow()
    diagnosticsLogger(
        "${markerPrefix}_parse_start source=$source responseBytes=$responseBytes " +
            "dispatcher=$dispatcherLabel"
    )
    val result =
        if (parseOnDefault) {
            withContext(RaftJsonParseDispatcher) { parser() }
        } else {
            parser()
        }
    diagnosticsLogger(
        "${markerPrefix}_parse_result source=$source result=${resultLabel(result)} " +
            "durationMs=${parseStartedAt.elapsedNow().inWholeMilliseconds} dispatcher=$dispatcherLabel"
    )
    return result
}
