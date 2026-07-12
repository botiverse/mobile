package build.raft.app

import build.raft.app.data.defaultNowMillis
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

internal enum class SlockLogLevel(
    val wireValue: String
) {
    Debug("debug"),
    Info("info"),
    Warn("warn"),
    Error("error")
}

/**
 * Shared logging bridge for commonMain diagnostics.
 *
 * commonMain must not call platform logging APIs such as android.util.Log
 * directly. Add a helper here, then implement the platform-specific behavior in
 * each target source set.
 */
internal expect fun slockPlatformLog(
    level: SlockLogLevel,
    tag: String,
    message: String,
    timestampMillis: Long
)

private object SlockPlatformLogSinkRegistry {
    @kotlin.concurrent.Volatile
    var sink: ((level: String, tag: String, message: String) -> Unit)? = null
}

private sealed interface SlockPlatformLogQueueItem {
    data class Entry(
        val level: SlockLogLevel,
        val tag: String,
        val message: String,
        val timestampMillis: Long
    ) : SlockPlatformLogQueueItem

    data class Flush(val completion: CompletableDeferred<Unit>) : SlockPlatformLogQueueItem
}

private object SlockPlatformLogQueue {
    private const val Capacity = 512
    private const val QueueTag = "SlockPlatformLog"

    private val queue = Channel<SlockPlatformLogQueueItem>(capacity = Capacity)
    private val droppedCount = atomic(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            for (item in queue) {
                when (item) {
                    is SlockPlatformLogQueueItem.Entry -> {
                        val dropped = droppedCount.getAndSet(0)
                        if (dropped > 0) {
                            dispatch(
                                level = SlockLogLevel.Warn,
                                tag = QueueTag,
                                message = "slock_platform_log_queue_dropped count=$dropped capacity=$Capacity",
                                timestampMillis = item.timestampMillis
                            )
                        }
                        dispatch(item.level, item.tag, item.message, item.timestampMillis)
                    }
                    is SlockPlatformLogQueueItem.Flush -> item.completion.complete(Unit)
                }
            }
        }
    }

    fun enqueue(level: SlockLogLevel, tag: String, message: String) {
        val result =
            queue.trySend(
                SlockPlatformLogQueueItem.Entry(
                    level = level,
                    tag = tag.take(SlockLogTagMaxChars * 2),
                    message = message.take(SlockLogMessageMaxChars * 2),
                    timestampMillis = defaultNowMillis()
                )
            )
        if (result.isFailure) {
            droppedCount.incrementAndGet()
        }
    }

    suspend fun flush() {
        val completion = CompletableDeferred<Unit>()
        queue.send(SlockPlatformLogQueueItem.Flush(completion))
        completion.await()
    }

    private fun dispatch(level: SlockLogLevel, tag: String, message: String, timestampMillis: Long) {
        val safeTag = tag.trim().ifBlank { "SlockShared" }.take(SlockLogTagMaxChars)
        val safeMessage = message.sanitizeSlockLogMessage().take(SlockLogMessageMaxChars)
        val sink = SlockPlatformLogSinkRegistry.sink
        if (sink != null) {
            runCatching { sink(level.wireValue, safeTag, safeMessage) }
            return
        }
        runCatching { slockPlatformLog(level, safeTag, safeMessage, timestampMillis) }
    }
}

/**
 * Installs an app-owned diagnostics sink for shared logs.
 *
 * This is intentionally bridge-free: callers may invoke [slockLogInfo],
 * [slockLogWarn], etc. from shared stores, app-scope event callbacks, or
 * native callback paths without entering Kuikly `callNative`.
 */
fun installSlockPlatformLogSink(sink: ((level: String, tag: String, message: String) -> Unit)?) {
    SlockPlatformLogSinkRegistry.sink = sink
}

internal fun slockLogger(
    tag: String,
    prefix: String = "",
    level: SlockLogLevel = SlockLogLevel.Info
): (String) -> Unit =
    { message ->
        slockLog(level, tag, if (prefix.isBlank()) message else "$prefix $message")
    }

internal fun slockLogDebug(tag: String, message: String) {
    slockLog(SlockLogLevel.Debug, tag, message)
}

internal fun slockLogInfo(tag: String, message: String) {
    slockLog(SlockLogLevel.Info, tag, message)
}

internal fun slockLogWarn(tag: String, message: String) {
    slockLog(SlockLogLevel.Warn, tag, message)
}

internal fun slockLogError(tag: String, message: String) {
    slockLog(SlockLogLevel.Error, tag, message)
}

private fun slockLog(level: SlockLogLevel, tag: String, message: String) {
    SlockPlatformLogQueue.enqueue(level, tag, message)
}

internal suspend fun flushSlockPlatformLogsForTest() {
    SlockPlatformLogQueue.flush()
}

private fun String.sanitizeSlockLogMessage(): String =
    SlockCredentialLikePatterns.fold(this) { current, pattern ->
        pattern.replace(current) { match ->
            when {
                match.value.startsWith("sk_agent_") -> "sk_agent_<redacted>"
                match.value.startsWith("sk_machine_") -> "sk_machine_<redacted>"
                match.value.startsWith("Bearer", ignoreCase = true) -> "Bearer <redacted>"
                else -> match.groupValues.getOrNull(1)?.let { "$it<redacted>" } ?: "<redacted>"
            }
        }
    }

private const val SlockLogTagMaxChars = 64
private const val SlockLogMessageMaxChars = 2_048

private val SlockCredentialLikePatterns =
    listOf(
        Regex("sk_agent_[A-Za-z0-9_=-]+"),
        Regex("sk_machine_[A-Za-z0-9_=-]+"),
        Regex("Bearer\\s+[A-Za-z0-9._=-]+", RegexOption.IGNORE_CASE),
        Regex("(?i)(access[_-]?token=)[^\\s&]+"),
        Regex("(?i)(client[_-]?key=)[^\\s&]+"),
        Regex("(?i)(api[_-]?key=)[^\\s&]+")
    )
