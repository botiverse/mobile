package build.raft.app

import build.raft.app.data.defaultNowMillis
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

@RaftCoreInternalApi enum class RaftLogLevel(
    val wireValue: String
) {
    Debug("debug"),
    Info("info"),
    Warn("warn"),
    Error("error")
}

enum class RaftPlatformLogHint {
    None,
    AuthCritical
}

data class RaftPlatformLogRecord(
    val level: String,
    val tag: String,
    val message: String,
    val timestampMillis: Long,
    val hint: RaftPlatformLogHint,
    /**
     * False: [RaftDiagnosticsVolumePolicy] keeps this line out of the persistent diagnostics log
     * (a gated verbose category, or counted into a per-minute summary). Console/logcat only.
     */
    val persist: Boolean = true,
    /**
     * True while not [persist]: summarized out of the log, but still a line for the platform's
     * in-memory ring of the last minutes at full detail (`recent-memory.jsonl`). False for
     * verbose-gated categories.
     */
    val recent: Boolean = persist
)

/**
 * Shared logging bridge for commonMain diagnostics.
 *
 * commonMain must not call platform logging APIs such as android.util.Log
 * directly. Add a helper here, then implement the platform-specific behavior in
 * each target source set.
 */
@RaftCoreInternalApi expect fun raftPlatformLog(
    level: RaftLogLevel,
    tag: String,
    message: String,
    timestampMillis: Long,
    hint: RaftPlatformLogHint
)

/** Waits for platform-specific diagnostics work accepted by [raftPlatformLog]. */
@RaftCoreInternalApi expect suspend fun flushRaftPlatformDiagnosticsDownstream(): Boolean

private object RaftPlatformLogSinkRegistry {
    @kotlin.concurrent.Volatile
    var sink: ((RaftPlatformLogRecord) -> Unit)? = null
}

private sealed interface RaftPlatformLogQueueItem {
    data class Entry(
        val level: RaftLogLevel,
        val tag: String,
        val message: String,
        val timestampMillis: Long,
        val hint: RaftPlatformLogHint
    ) : RaftPlatformLogQueueItem

    data class Flush(
        val timestampMillis: Long,
        val completion: CompletableDeferred<Unit>
    ) : RaftPlatformLogQueueItem
}

@RaftCoreInternalApi class RaftPlatformLogQueue(
    scope: CoroutineScope,
    private val recordSink: ((RaftPlatformLogRecord) -> Unit)? = null,
    // Runs in [enqueue] after the line took its place in its lane and before it is published: lets
    // tests log another line at that point, as a second thread can, without timing or threads.
    private val duringAdmission: ((RaftPlatformLogHint) -> Unit)? = null,
    // null: every line persists (no summaries, no verbose gating).
    private val volumePolicy: RaftDiagnosticsVolumePolicy? = RaftDiagnosticsVolumePolicy(),
    private val verboseDiagnostics: () -> Boolean = { RaftVerboseDiagnostics.enabled }
) {
    private companion object {
        const val NormalCapacity = 496
        const val AuthCriticalCapacity = 16
        const val QueueTag = "SlockPlatformLog"
    }

    /**
     * Every admitted line and flush marker, in admission order: the consumer delivers them in that
     * order, and a flush completes once every line admitted before it has been delivered. Unlimited
     * and lock-free, so a line never waits for another thread's admission and is never lost to one.
     * Up to 1.28 a line that found another admission in progress was dropped (`admissionBusy=`):
     * Hands cf580bb9 lost about 2 lines a minute, request finish lines among them. Each lane's bound
     * is its count below.
     */
    private val items = Channel<RaftPlatformLogQueueItem>(capacity = Channel.UNLIMITED)

    // Lines admitted and not yet taken by the consumer, per lane. A full lane drops the new line,
    // counted and then reported in a `slock_platform_log_queue_dropped` line; routine lines never
    // take the auth-critical lane's room.
    private val normalWaiting = atomic(0)
    private val authCriticalWaiting = atomic(0)
    private val normalOverflowDropped = atomic(0L)
    private val authCriticalOverflowDropped = atomic(0L)

    init {
        scope.launch {
            for (item in items) {
                when (item) {
                    is RaftPlatformLogQueueItem.Entry -> {
                        leaveLane(item.hint)
                        dispatchPendingDropMarkers(item.timestampMillis)
                        dispatchAdmitted(item)
                    }
                    is RaftPlatformLogQueueItem.Flush -> {
                        dispatchPendingDropMarkers(item.timestampMillis)
                        // A flush precedes every diagnostics export: write the counts held so far.
                        volumePolicy?.drain(item.timestampMillis)?.forEach { summary ->
                            dispatchSummary(summary, item.timestampMillis)
                        }
                        item.completion.complete(Unit)
                    }
                }
            }
        }
    }

    /** Never blocks or waits for another thread. False only when the line's lane is full. */
    fun enqueue(
        level: RaftLogLevel,
        tag: String,
        message: String,
        hint: RaftPlatformLogHint
    ): Boolean {
        if (!enterLane(hint)) {
            recordOverflowDrop(hint)
            return false
        }
        duringAdmission?.invoke(hint)
        val entry =
            RaftPlatformLogQueueItem.Entry(
                level = level,
                tag = tag.take(RaftLogTagMaxChars * 2),
                message = message.take(RaftLogMessageMaxChars * 2),
                timestampMillis = defaultNowMillis(),
                hint = hint
            )
        if (items.trySend(entry).isSuccess) return true
        // Only a closed channel refuses a line, and nothing closes this one.
        leaveLane(hint)
        return false
    }

    suspend fun flush() {
        val completion = CompletableDeferred<Unit>()
        val marker = RaftPlatformLogQueueItem.Flush(timestampMillis = defaultNowMillis(), completion = completion)
        if (items.trySend(marker).isSuccess) completion.await()
    }

    /**
     * Takes a place in [hint]'s lane unless the lane is full. A failed compare-and-set means another
     * admission or the consumer changed the count meanwhile: read it again and retry, never wait.
     */
    private fun enterLane(hint: RaftPlatformLogHint): Boolean {
        val capacity = capacityOf(hint)
        while (true) {
            val waiting =
                when (hint) {
                    RaftPlatformLogHint.None -> normalWaiting.value
                    RaftPlatformLogHint.AuthCritical -> authCriticalWaiting.value
                }
            if (waiting >= capacity) return false
            val entered =
                when (hint) {
                    RaftPlatformLogHint.None -> normalWaiting.compareAndSet(waiting, waiting + 1)
                    RaftPlatformLogHint.AuthCritical -> authCriticalWaiting.compareAndSet(waiting, waiting + 1)
                }
            if (entered) return true
        }
    }

    private fun leaveLane(hint: RaftPlatformLogHint) {
        when (hint) {
            RaftPlatformLogHint.None -> normalWaiting.decrementAndGet()
            RaftPlatformLogHint.AuthCritical -> authCriticalWaiting.decrementAndGet()
        }
    }

    private fun capacityOf(hint: RaftPlatformLogHint): Int =
        when (hint) {
            RaftPlatformLogHint.None -> NormalCapacity
            RaftPlatformLogHint.AuthCritical -> AuthCriticalCapacity
        }

    private fun recordOverflowDrop(hint: RaftPlatformLogHint) {
        when (hint) {
            RaftPlatformLogHint.None -> normalOverflowDropped.incrementAndGet()
            RaftPlatformLogHint.AuthCritical -> authCriticalOverflowDropped.incrementAndGet()
        }
    }

    private fun dispatchPendingDropMarkers(timestampMillis: Long) {
        dispatchDropMarker(
            lane = "normal",
            capacity = NormalCapacity,
            dropped = normalOverflowDropped.getAndSet(0L),
            timestampMillis = timestampMillis,
            hint = RaftPlatformLogHint.None
        )
        dispatchDropMarker(
            lane = "auth_critical",
            capacity = AuthCriticalCapacity,
            dropped = authCriticalOverflowDropped.getAndSet(0L),
            timestampMillis = timestampMillis,
            hint = RaftPlatformLogHint.AuthCritical
        )
    }

    private fun dispatchDropMarker(
        lane: String,
        capacity: Int,
        dropped: Long,
        timestampMillis: Long,
        hint: RaftPlatformLogHint
    ) {
        if (dropped <= 0L) return
        dispatch(
            level = RaftLogLevel.Warn,
            tag = QueueTag,
            // A full lane is the only way a line is dropped now: `overflow` is all of `count`.
            message =
                "slock_platform_log_queue_dropped lane=$lane count=$dropped " +
                    "overflow=$dropped capacity=$capacity",
            timestampMillis = timestampMillis,
            hint = hint
        )
    }

    private fun dispatchAdmitted(item: RaftPlatformLogQueueItem.Entry) {
        val safeTag = item.tag.safeRaftLogTag()
        val safeMessage = item.message.safeRaftLogMessage()
        val admission =
            volumePolicy?.let { policy ->
                runCatching {
                    policy.admit(item.level.wireValue, safeTag, safeMessage, item.timestampMillis, verboseDiagnostics())
                }.getOrNull()
            }
        admission?.summaries?.forEach { summary -> dispatchSummary(summary, item.timestampMillis) }
        deliver(
            item.level,
            safeTag,
            safeMessage,
            item.timestampMillis,
            item.hint,
            persist = admission?.persist ?: true,
            recent = admission?.recent ?: true
        )
    }

    private fun dispatchSummary(summary: String, timestampMillis: Long) {
        deliver(
            level = RaftLogLevel.Info,
            safeTag = RaftDiagnosticsVolumePolicy.SummaryTag,
            safeMessage = summary.safeRaftLogMessage(),
            timestampMillis = timestampMillis,
            hint = RaftPlatformLogHint.None,
            persist = true
        )
    }

    private fun dispatch(
        level: RaftLogLevel,
        tag: String,
        message: String,
        timestampMillis: Long,
        hint: RaftPlatformLogHint
    ) {
        deliver(level, tag.safeRaftLogTag(), message.safeRaftLogMessage(), timestampMillis, hint, persist = true)
    }

    private fun deliver(
        level: RaftLogLevel,
        safeTag: String,
        safeMessage: String,
        timestampMillis: Long,
        hint: RaftPlatformLogHint,
        persist: Boolean,
        recent: Boolean = persist
    ) {
        val sink = recordSink ?: RaftPlatformLogSinkRegistry.sink
        if (sink != null) {
            val record =
                RaftPlatformLogRecord(
                    level = level.wireValue,
                    tag = safeTag,
                    message = safeMessage,
                    timestampMillis = timestampMillis,
                    hint = hint,
                    persist = persist,
                    recent = recent
                )
            if (runCatching { sink(record) }.isSuccess) return
        }
        // Without a sink the platform actual is the persistent writer (iOS host log, desktop file).
        if (!persist) return
        runCatching { raftPlatformLog(level, safeTag, safeMessage, timestampMillis, hint) }
    }

    private fun String.safeRaftLogTag(): String = trim().ifBlank { "SlockShared" }.take(RaftLogTagMaxChars)

    private fun String.safeRaftLogMessage(): String = sanitizeRaftLogMessage().take(RaftLogMessageMaxChars)
}

private val raftPlatformLogQueue by lazy {
    RaftPlatformLogQueue(CoroutineScope(SupervisorJob() + Dispatchers.Default))
}

/**
 * Installs an app-owned diagnostics sink for shared logs.
 *
 * This is intentionally bridge-free: callers may invoke [raftLogInfo],
 * [raftLogWarn], etc. from shared stores, app-scope event callbacks, or
 * native callback paths without entering Kuikly `callNative`.
 */
fun installRaftPlatformLogSink(sink: ((level: String, tag: String, message: String) -> Unit)?) {
    RaftPlatformLogSinkRegistry.sink =
        sink?.let { legacySink ->
            { record -> legacySink(record.level, record.tag, record.message) }
        }
}

fun installRaftPlatformLogRecordSink(sink: ((RaftPlatformLogRecord) -> Unit)?) {
    RaftPlatformLogSinkRegistry.sink = sink
}

@RaftCoreInternalApi fun raftLogger(
    tag: String,
    prefix: String = "",
    level: RaftLogLevel = RaftLogLevel.Info,
    hint: RaftPlatformLogHint = RaftPlatformLogHint.None
): (String) -> Unit =
    { message ->
        raftLog(level, tag, if (prefix.isBlank()) message else "$prefix $message", hint)
    }

@RaftCoreInternalApi fun raftLogDebug(
    tag: String,
    message: String,
    hint: RaftPlatformLogHint = RaftPlatformLogHint.None
) {
    raftLog(RaftLogLevel.Debug, tag, message, hint)
}

@RaftCoreInternalApi fun raftLogInfo(
    tag: String,
    message: String,
    hint: RaftPlatformLogHint = RaftPlatformLogHint.None
) {
    raftLog(RaftLogLevel.Info, tag, message, hint)
}

@RaftCoreInternalApi fun raftLogWarn(
    tag: String,
    message: String,
    hint: RaftPlatformLogHint = RaftPlatformLogHint.None
) {
    raftLog(RaftLogLevel.Warn, tag, message, hint)
}

@RaftCoreInternalApi fun raftLogError(
    tag: String,
    message: String,
    hint: RaftPlatformLogHint = RaftPlatformLogHint.None
) {
    raftLog(RaftLogLevel.Error, tag, message, hint)
}

private fun raftLog(
    level: RaftLogLevel,
    tag: String,
    message: String,
    hint: RaftPlatformLogHint
) {
    raftPlatformLogQueue.enqueue(level, tag, message, hint)
}

/**
 * task #180: a trace line that ALWAYS reaches the platform log (logcat), for cross-page
 * diagnostics that a verifier greps on a device.
 *
 * `raftLogInfo` is not usable for this: when a record sink is installed (RaftApplication
 * installs one) `raftLog` hands the record to the sink and returns, so entries land in the
 * diagnostics ring/journal and only reach logcat later, asynchronously, via that sink's queue.
 * A verifier clearing logcat and tapping would see nothing. This calls the platform actual
 * directly, which is a synchronous `android.util.Log.i` on Android.
 */
@RaftCoreInternalApi fun raftTraceToPlatformLog(tag: String, message: String) {
    runCatching {
        // Uses the platform actual, deliberately skipping the queued sink above it. Not suspend.
        raftPlatformLog(
            level = RaftLogLevel.Info,
            tag = tag,
            message = message,
            timestampMillis = 0L,
            hint = RaftPlatformLogHint.None
        )
    }
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
@RaftCoreInternalApi suspend fun flushRaftPlatformLogs() {
    raftPlatformLogQueue.flush()
}

@RaftCoreInternalApi suspend fun flushRaftPlatformLogsForTest() = flushRaftPlatformLogs()

/**
 * The credential redaction every shared log line gets ([RaftPlatformLogQueue]), for platform log
 * writers that also take lines from outside the queue (the desktop log file: host logs, crashes).
 */
@RaftCoreInternalApi fun raftRedactLogMessage(message: String): String = message.sanitizeRaftLogMessage()

private fun String.sanitizeRaftLogMessage(): String =
    RaftCredentialLikePatterns.fold(this) { current, pattern ->
        pattern.replace(current) { match ->
            when {
                match.value.startsWith("sk_agent_") -> "sk_agent_<redacted>"
                match.value.startsWith("sk_machine_") -> "sk_machine_<redacted>"
                match.value.startsWith("Bearer", ignoreCase = true) -> "Bearer <redacted>"
                match.value.startsWith("eyJ") -> "<jwt-redacted>"
                else -> match.groupValues.getOrNull(1)?.let { "$it<redacted>" } ?: "<redacted>"
            }
        }
    }

private const val RaftLogTagMaxChars = 64
private const val RaftLogMessageMaxChars = 2_048

private val RaftCredentialLikePatterns =
    listOf(
        Regex("sk_agent_[A-Za-z0-9_=-]+"),
        Regex("sk_machine_[A-Za-z0-9_=-]+"),
        Regex("Bearer\\s+[A-Za-z0-9._=-]+", RegexOption.IGNORE_CASE),
        Regex("(?i)(access[_-]?token=)[^\\s&]+"),
        Regex("(?i)(client[_-]?key=)[^\\s&]+"),
        Regex("(?i)(api[_-]?key=)[^\\s&]+"),
        Regex("""\beyJ[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]{4,}(?![A-Za-z0-9_-])"""),
        Regex("""(?:"?(?:draft_?(?:before|after)|contact_?hints)"?\s*[:=]\s*(?:\[[^\]]*\]|"(?:\\.|[^"\\])*"|[^\s,;}]+))""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:draft_?(?:before|after)|contact_?hints)\b""", RegexOption.IGNORE_CASE),
    )
