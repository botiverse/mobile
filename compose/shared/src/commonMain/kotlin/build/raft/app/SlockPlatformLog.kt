package build.raft.app

import build.raft.app.data.defaultNowMillis
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

internal enum class SlockLogLevel(
    val wireValue: String
) {
    Debug("debug"),
    Info("info"),
    Warn("warn"),
    Error("error")
}

enum class SlockPlatformLogHint {
    None,
    AuthCritical
}

data class SlockPlatformLogRecord(
    val level: String,
    val tag: String,
    val message: String,
    val timestampMillis: Long,
    val hint: SlockPlatformLogHint
)

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
    timestampMillis: Long,
    hint: SlockPlatformLogHint
)

/** Waits for platform-specific diagnostics work accepted by [slockPlatformLog]. */
internal expect suspend fun flushSlockPlatformDiagnosticsDownstream(): Boolean

private object SlockPlatformLogSinkRegistry {
    @kotlin.concurrent.Volatile
    var sink: ((SlockPlatformLogRecord) -> Unit)? = null
}

private sealed interface SlockPlatformLogQueueItem {
    val sequence: Long

    data class Entry(
        override val sequence: Long,
        val level: SlockLogLevel,
        val tag: String,
        val message: String,
        val timestampMillis: Long,
        val hint: SlockPlatformLogHint
    ) : SlockPlatformLogQueueItem

    data class Flush(
        override val sequence: Long,
        val timestampMillis: Long,
        val completion: CompletableDeferred<Unit>
    ) : SlockPlatformLogQueueItem
}

private object SlockPlatformLogQueue {
    private const val NormalCapacity = 496
    private const val AuthCriticalCapacity = 16
    private const val ControlCapacity = 1
    private const val QueueTag = "SlockPlatformLog"

    private val normalQueue = Channel<SlockPlatformLogQueueItem.Entry>(capacity = NormalCapacity)
    private val authCriticalQueue = Channel<SlockPlatformLogQueueItem.Entry>(capacity = AuthCriticalCapacity)
    private val controlQueue = Channel<SlockPlatformLogQueueItem.Flush>(capacity = ControlCapacity)
    private val wakeups = Channel<Unit>(capacity = Channel.CONFLATED)
    private val admissionBusy = atomic(false)
    private var nextAcceptedSequence = 1L
    private val normalOverflowDropped = atomic(0L)
    private val normalAdmissionDropped = atomic(0L)
    private val authCriticalOverflowDropped = atomic(0L)
    private val authCriticalAdmissionDropped = atomic(0L)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            var normalHead: SlockPlatformLogQueueItem.Entry? = null
            var authCriticalHead: SlockPlatformLogQueueItem.Entry? = null
            var controlHead: SlockPlatformLogQueueItem.Flush? = null
            while (true) {
                if (normalHead == null) normalHead = normalQueue.tryReceive().getOrNull()
                if (authCriticalHead == null) authCriticalHead = authCriticalQueue.tryReceive().getOrNull()
                if (controlHead == null) controlHead = controlQueue.tryReceive().getOrNull()
                val item = earliest(normalHead, authCriticalHead, controlHead)
                if (item == null) {
                    wakeups.receive()
                    continue
                }
                when (item) {
                    is SlockPlatformLogQueueItem.Entry -> {
                        dispatchPendingDropMarkers(item.timestampMillis)
                        dispatch(item.level, item.tag, item.message, item.timestampMillis, item.hint)
                        if (item === normalHead) {
                            normalHead = null
                        } else {
                            authCriticalHead = null
                        }
                    }
                    is SlockPlatformLogQueueItem.Flush -> {
                        dispatchPendingDropMarkers(item.timestampMillis)
                        controlHead = null
                        item.completion.complete(Unit)
                    }
                }
            }
        }
    }

    fun enqueue(
        level: SlockLogLevel,
        tag: String,
        message: String,
        hint: SlockPlatformLogHint
    ): Boolean {
        if (!admissionBusy.compareAndSet(expect = false, update = true)) {
            recordAdmissionDrop(hint)
            return false
        }
        val accepted =
            try {
                val entry =
                    SlockPlatformLogQueueItem.Entry(
                        sequence = nextAcceptedSequence,
                        level = level,
                        tag = tag.take(SlockLogTagMaxChars * 2),
                        message = message.take(SlockLogMessageMaxChars * 2),
                        timestampMillis = defaultNowMillis(),
                        hint = hint
                    )
                val result = queueFor(hint).trySend(entry)
                if (result.isSuccess) {
                    nextAcceptedSequence += 1L
                    true
                } else {
                    recordOverflowDrop(hint)
                    false
                }
            } finally {
                admissionBusy.value = false
            }
        if (accepted) {
            wakeups.trySend(Unit)
        }
        return accepted
    }

    suspend fun flush() {
        val completion = CompletableDeferred<Unit>()
        while (true) {
            if (!admissionBusy.compareAndSet(expect = false, update = true)) {
                yield()
                continue
            }
            val accepted =
                try {
                    val marker =
                        SlockPlatformLogQueueItem.Flush(
                            sequence = nextAcceptedSequence,
                            timestampMillis = defaultNowMillis(),
                            completion = completion
                        )
                    if (controlQueue.trySend(marker).isSuccess) {
                        nextAcceptedSequence += 1L
                        true
                    } else {
                        false
                    }
                } finally {
                    admissionBusy.value = false
                }
            if (accepted) {
                wakeups.trySend(Unit)
                break
            }
            yield()
        }
        completion.await()
    }

    private fun queueFor(hint: SlockPlatformLogHint): Channel<SlockPlatformLogQueueItem.Entry> =
        when (hint) {
            SlockPlatformLogHint.None -> normalQueue
            SlockPlatformLogHint.AuthCritical -> authCriticalQueue
        }

    private fun recordOverflowDrop(hint: SlockPlatformLogHint) {
        when (hint) {
            SlockPlatformLogHint.None -> normalOverflowDropped.incrementAndGet()
            SlockPlatformLogHint.AuthCritical -> authCriticalOverflowDropped.incrementAndGet()
        }
    }

    private fun recordAdmissionDrop(hint: SlockPlatformLogHint) {
        when (hint) {
            SlockPlatformLogHint.None -> normalAdmissionDropped.incrementAndGet()
            SlockPlatformLogHint.AuthCritical -> authCriticalAdmissionDropped.incrementAndGet()
        }
    }

    private fun earliest(
        normal: SlockPlatformLogQueueItem.Entry?,
        authCritical: SlockPlatformLogQueueItem.Entry?,
        control: SlockPlatformLogQueueItem.Flush?
    ): SlockPlatformLogQueueItem? {
        var earliest: SlockPlatformLogQueueItem? = normal
        if (authCritical != null && (earliest == null || authCritical.sequence < earliest.sequence)) {
            earliest = authCritical
        }
        if (control != null && (earliest == null || control.sequence < earliest.sequence)) {
            earliest = control
        }
        return earliest
    }

    private fun dispatchPendingDropMarkers(timestampMillis: Long) {
        dispatchDropMarker(
            lane = "normal",
            capacity = NormalCapacity,
            overflow = normalOverflowDropped.getAndSet(0L),
            admission = normalAdmissionDropped.getAndSet(0L),
            timestampMillis = timestampMillis,
            hint = SlockPlatformLogHint.None
        )
        dispatchDropMarker(
            lane = "auth_critical",
            capacity = AuthCriticalCapacity,
            overflow = authCriticalOverflowDropped.getAndSet(0L),
            admission = authCriticalAdmissionDropped.getAndSet(0L),
            timestampMillis = timestampMillis,
            hint = SlockPlatformLogHint.AuthCritical
        )
    }

    private fun dispatchDropMarker(
        lane: String,
        capacity: Int,
        overflow: Long,
        admission: Long,
        timestampMillis: Long,
        hint: SlockPlatformLogHint
    ) {
        val dropped = overflow + admission
        if (dropped <= 0L) return
        dispatch(
            level = SlockLogLevel.Warn,
            tag = QueueTag,
            message =
                "slock_platform_log_queue_dropped lane=$lane count=$dropped " +
                    "overflow=$overflow admissionBusy=$admission capacity=$capacity",
            timestampMillis = timestampMillis,
            hint = hint
        )
    }

    private fun dispatch(
        level: SlockLogLevel,
        tag: String,
        message: String,
        timestampMillis: Long,
        hint: SlockPlatformLogHint
    ) {
        val safeTag = tag.trim().ifBlank { "SlockShared" }.take(SlockLogTagMaxChars)
        val safeMessage = message.sanitizeSlockLogMessage().take(SlockLogMessageMaxChars)
        val sink = SlockPlatformLogSinkRegistry.sink
        if (sink != null) {
            val record =
                SlockPlatformLogRecord(
                    level = level.wireValue,
                    tag = safeTag,
                    message = safeMessage,
                    timestampMillis = timestampMillis,
                    hint = hint
                )
            if (runCatching { sink(record) }.isSuccess) return
        }
        runCatching { slockPlatformLog(level, safeTag, safeMessage, timestampMillis, hint) }
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
    SlockPlatformLogSinkRegistry.sink =
        sink?.let { legacySink ->
            { record -> legacySink(record.level, record.tag, record.message) }
        }
}

fun installSlockPlatformLogRecordSink(sink: ((SlockPlatformLogRecord) -> Unit)?) {
    SlockPlatformLogSinkRegistry.sink = sink
}

internal fun slockLogger(
    tag: String,
    prefix: String = "",
    level: SlockLogLevel = SlockLogLevel.Info,
    hint: SlockPlatformLogHint = SlockPlatformLogHint.None
): (String) -> Unit =
    { message ->
        slockLog(level, tag, if (prefix.isBlank()) message else "$prefix $message", hint)
    }

internal fun slockLogDebug(
    tag: String,
    message: String,
    hint: SlockPlatformLogHint = SlockPlatformLogHint.None
) {
    slockLog(SlockLogLevel.Debug, tag, message, hint)
}

internal fun slockLogInfo(
    tag: String,
    message: String,
    hint: SlockPlatformLogHint = SlockPlatformLogHint.None
) {
    slockLog(SlockLogLevel.Info, tag, message, hint)
}

internal fun slockLogWarn(
    tag: String,
    message: String,
    hint: SlockPlatformLogHint = SlockPlatformLogHint.None
) {
    slockLog(SlockLogLevel.Warn, tag, message, hint)
}

internal fun slockLogError(
    tag: String,
    message: String,
    hint: SlockPlatformLogHint = SlockPlatformLogHint.None
) {
    slockLog(SlockLogLevel.Error, tag, message, hint)
}

private fun slockLog(
    level: SlockLogLevel,
    tag: String,
    message: String,
    hint: SlockPlatformLogHint
) {
    SlockPlatformLogQueue.enqueue(level, tag, message, hint)
}

/**
 * Ordered shared-to-platform barrier.
 *
 * Completion means the consumer has invoked the installed sink or platform
 * actual for every shared record accepted before this marker. It does not
 * prove downstream persistence: native diagnostics owners must apply their
 * own second barrier. Native-only crash attachment providers are outside this
 * shared queue contract.
 */
internal suspend fun flushSlockPlatformLogs() {
    SlockPlatformLogQueue.flush()
}

internal suspend fun flushSlockPlatformLogsForTest() = flushSlockPlatformLogs()

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
