package ai.slock.compose.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

internal suspend inline fun <T> parseResponseBodyWithTrace(
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
    val dispatcherLabel = if (parseOnDefault) "Default" else "caller"
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
            withContext(Dispatchers.Default) { parser() }
        } else {
            parser()
        }
    diagnosticsLogger(
        "${markerPrefix}_parse_result source=$source result=${resultLabel(result)} " +
            "durationMs=${parseStartedAt.elapsedNow().inWholeMilliseconds} dispatcher=$dispatcherLabel"
    )
    return result
}
