package build.raft.app

import com.tencent.kuikly.core.log.KLog
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.tmm.knoi.annotation.ServiceConsumer
import com.tencent.tmm.knoi.service.KNOI
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SlockOhosSharedLogSinkService = "slockOhosSharedLogSink"
private const val SlockOhosLogTagMaxChars = 48
private const val SlockOhosLogMessageMaxChars = 2_048
private const val SlockOhosLogEventMaxChars = 96

@ServiceConsumer(name = SlockOhosSharedLogSinkService)
interface SlockOhosSharedLogSink {
    fun record(level: String, tag: String, message: String)

    fun recordBatch(payload: String): Boolean

    fun producerState(pending: Boolean, healthy: Boolean)
}

internal actual fun slockPlatformLog(
    level: SlockLogLevel,
    tag: String,
    message: String,
    timestampMillis: Long,
    hint: SlockPlatformLogHint
) {
    SlockOhosAsyncPlatformLogSink.enqueue(level, tag, message, timestampMillis, hint)
}

internal actual suspend fun flushSlockPlatformDiagnosticsDownstream(): Boolean =
    SlockOhosAsyncPlatformLogSink.flush()

internal data class SlockOhosPendingLog(
    val level: SlockLogLevel,
    val tag: String,
    val message: String,
    val timestampMillis: Long,
    val sourceSeq: Long,
    val hint: SlockPlatformLogHint,
    val dropped: Int = 0
)

internal enum class SlockOhosProducerLane {
    Normal,
    Critical
}

private const val SlockOhosNonStreamSourceSeq = 0L

internal fun slockOhosProducerLane(hint: SlockPlatformLogHint): SlockOhosProducerLane =
    if (hint == SlockPlatformLogHint.AuthCritical) {
        SlockOhosProducerLane.Critical
    } else {
        SlockOhosProducerLane.Normal
    }

internal fun slockOhosDropBreadcrumb(
    lane: SlockOhosProducerLane,
    count: Int,
    capacity: Int,
    timestampMillis: Long
): SlockOhosPendingLog =
    SlockOhosPendingLog(
        level =
            if (lane == SlockOhosProducerLane.Critical) {
                SlockLogLevel.Error
            } else {
                SlockLogLevel.Warn
            },
        tag = "SlockOhosAsyncLog",
        message =
            "slock_ohos_log_queue_dropped lane=${lane.name.lowercase()} count=$count capacity=$capacity",
        timestampMillis = timestampMillis,
        sourceSeq = SlockOhosNonStreamSourceSeq,
        hint =
            if (lane == SlockOhosProducerLane.Critical) {
                SlockPlatformLogHint.AuthCritical
            } else {
                SlockPlatformLogHint.None
            },
        dropped = count
    )

internal class SlockOhosProducerQueue(
    normalCapacity: Int,
    criticalCapacity: Int
) {
    private val normal = Channel<SlockOhosPendingLog>(capacity = normalCapacity)
    private val critical = Channel<SlockOhosPendingLog>(capacity = criticalCapacity)
    private val wakeup = Channel<Unit>(capacity = Channel.CONFLATED)
    private var normalHead: SlockOhosPendingLog? = null
    private var criticalHead: SlockOhosPendingLog? = null

    fun tryEnqueue(entry: SlockOhosPendingLog, lane: SlockOhosProducerLane): Boolean {
        val accepted =
            when (lane) {
                SlockOhosProducerLane.Normal -> normal.trySend(entry).isSuccess
                SlockOhosProducerLane.Critical -> critical.trySend(entry).isSuccess
            }
        if (accepted) wakeup.trySend(Unit)
        return accepted
    }

    suspend fun awaitWork() {
        wakeup.receive()
    }

    fun tryPollMerged(): SlockOhosPendingLog? {
        if (normalHead == null) normalHead = normal.tryReceive().getOrNull()
        if (criticalHead == null) criticalHead = critical.tryReceive().getOrNull()
        val normalEntry = normalHead
        val criticalEntry = criticalHead
        if (normalEntry == null) {
            criticalHead = null
            return criticalEntry
        }
        if (criticalEntry == null) {
            normalHead = null
            return normalEntry
        }
        return if (normalEntry.sourceSeq <= criticalEntry.sourceSeq) {
            normalHead = null
            normalEntry
        } else {
            criticalHead = null
            criticalEntry
        }
    }

    fun requestNextDrain() {
        wakeup.trySend(Unit)
    }

    fun hasPending(): Boolean {
        if (normalHead == null) normalHead = normal.tryReceive().getOrNull()
        if (criticalHead == null) criticalHead = critical.tryReceive().getOrNull()
        return normalHead != null || criticalHead != null
    }
}

internal data class SlockOhosPreparedLog(
    val level: String,
    val tag: String,
    val event: String,
    val message: String,
    val timestampMillis: Long,
    val truncated: Boolean,
    val sourceSeq: Long,
    val dropped: Int,
    val thread: String,
    val critical: Boolean
)

internal class SlockOhosProducerFlushBarrier {
    private val waiters = mutableListOf<CompletableDeferred<Boolean>>()
    private var deliveryHealthy: Boolean = true

    fun add(completion: CompletableDeferred<Boolean>) {
        waiters += completion
    }

    fun hasWaiters(): Boolean = waiters.isNotEmpty()

    fun recordDelivery(success: Boolean) {
        deliveryHealthy = success && deliveryHealthy
    }

    fun completeIfIdle(hasPending: Boolean) {
        if (hasPending || waiters.isEmpty()) return
        waiters.forEach { completion -> completion.complete(deliveryHealthy) }
        waiters.clear()
    }
}

internal class SlockOhosProducerPendingState(
    private val notify: (Boolean) -> Boolean
) : SynchronizedObject() {
    private var pendingCount: Int = 0
    private var healthy: Boolean = true

    fun accepted() = synchronized(this) {
        if (pendingCount == 0) healthy = notify(true) && healthy
        pendingCount += 1
    }

    fun delivered(count: Int) = synchronized(this) {
        pendingCount = (pendingCount - count).coerceAtLeast(0)
        if (pendingCount == 0) healthy = notify(false) && healthy
    }

    fun isHealthy(): Boolean = synchronized(this) { healthy }

    fun isPending(): Boolean = synchronized(this) { pendingCount > 0 }
}

private object SlockOhosAsyncPlatformLogSink {
    private const val NormalQueueCapacity = 480
    private const val CriticalQueueCapacity = 32
    private const val BatchCapacity = 32
    private const val BatchDelayMillis = 100L

    private val queue = SlockOhosProducerQueue(NormalQueueCapacity, CriticalQueueCapacity)
    private val flushRequests = Channel<CompletableDeferred<Boolean>>(capacity = Channel.UNLIMITED)
    private val normalDroppedCount = atomic(0)
    private val criticalDroppedCount = atomic(0)
    private val nextSourceSeq = atomic(1L)
    private val pendingState =
        SlockOhosProducerPendingState { pending ->
            notifyProducerState(pending = pending, healthy = true)
        }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            val flushBarrier = SlockOhosProducerFlushBarrier()
            while (true) {
                queue.awaitWork()
                drainFlushRequests(flushBarrier)
                if (!flushBarrier.hasWaiters()) delay(BatchDelayMillis)
                val batch = mutableListOf<SlockOhosPendingLog>()
                while (batch.size < BatchCapacity) {
                    val entry = queue.tryPollMerged() ?: break
                    batch.add(entry)
                }
                val normalDropped = normalDroppedCount.getAndSet(0)
                if (normalDropped > 0) {
                    batch += slockOhosDropBreadcrumb(
                        lane = SlockOhosProducerLane.Normal,
                        count = normalDropped,
                        capacity = NormalQueueCapacity,
                        timestampMillis = batch.lastOrNull()?.timestampMillis ?: 0L
                    )
                }
                val criticalDropped = criticalDroppedCount.getAndSet(0)
                if (criticalDropped > 0) {
                    batch += slockOhosDropBreadcrumb(
                        lane = SlockOhosProducerLane.Critical,
                        count = criticalDropped,
                        capacity = CriticalQueueCapacity,
                        timestampMillis = batch.lastOrNull()?.timestampMillis ?: 0L
                    )
                }
                if (batch.isNotEmpty()) {
                    val recorded = recordBatch(batch)
                    pendingState.delivered(batch.count { entry -> entry.sourceSeq > 0L })
                    if (!recorded) {
                        notifyProducerState(pending = pendingState.isPending(), healthy = false)
                    }
                    flushBarrier.recordDelivery(recorded && pendingState.isHealthy())
                }
                drainFlushRequests(flushBarrier)
                val hasPending = queue.hasPending()
                if (hasPending) {
                    queue.requestNextDrain()
                }
                flushBarrier.recordDelivery(pendingState.isHealthy())
                flushBarrier.completeIfIdle(hasPending)
            }
        }
    }

    fun enqueue(
        level: SlockLogLevel,
        tag: String,
        message: String,
        timestampMillis: Long,
        hint: SlockPlatformLogHint
    ) {
        val lane = slockOhosProducerLane(hint)
        pendingState.accepted()
        val accepted =
            queue.tryEnqueue(
                SlockOhosPendingLog(
                    level = level,
                    tag = tag,
                    message = message,
                    timestampMillis = timestampMillis,
                    sourceSeq = nextSourceSeq.getAndIncrement(),
                    hint = hint
                ),
                lane
            )
        if (accepted) return

        pendingState.delivered(1)

        when (lane) {
            SlockOhosProducerLane.Normal -> normalDroppedCount.incrementAndGet()
            SlockOhosProducerLane.Critical -> criticalDroppedCount.incrementAndGet()
        }
    }

    suspend fun flush(): Boolean {
        val completion = CompletableDeferred<Boolean>()
        flushRequests.send(completion)
        queue.requestNextDrain()
        return completion.await()
    }

    private fun drainFlushRequests(target: SlockOhosProducerFlushBarrier) {
        while (true) {
            val completion = flushRequests.tryReceive().getOrNull() ?: return
            target.add(completion)
        }
    }

    private fun notifyProducerState(pending: Boolean, healthy: Boolean): Boolean =
        runCatching {
            KNOI.get<SlockOhosSharedLogSink>(SlockOhosSharedLogSinkService)
                .producerState(pending, healthy)
        }.isSuccess

    private fun recordBatch(entries: List<SlockOhosPendingLog>): Boolean {
        val preparedEntries =
            entries.map { entry ->
                prepareSlockOhosLog(
                    level = entry.level,
                    tag = entry.tag,
                    message = entry.message,
                    timestampMillis = entry.timestampMillis,
                    sourceSeq = entry.sourceSeq,
                    dropped = entry.dropped,
                    hint = entry.hint
                )
            }
        val payload =
            JSONArray().apply {
                preparedEntries.forEach { entry ->
                    put(
                        JSONObject().apply {
                            put("level", entry.level)
                            put("tag", entry.tag)
                            put("event", entry.event)
                            put("message", entry.message)
                            put("timestampMillis", entry.timestampMillis)
                            put("truncated", entry.truncated)
                            put("sourceSeq", entry.sourceSeq)
                            put("dropped", entry.dropped)
                            put("thread", entry.thread)
                            put("critical", entry.critical)
                        }
                    )
                }
            }.toString()
        val recorded =
            runCatching {
                KNOI.get<SlockOhosSharedLogSink>(SlockOhosSharedLogSinkService)
                    .recordBatch(payload)
            }.getOrDefault(false)
        if (recorded) return true
        preparedEntries.forEach { entry ->
            when (entry.level) {
                SlockLogLevel.Debug.wireValue -> KLog.d(entry.tag, entry.message)
                SlockLogLevel.Warn.wireValue -> KLog.i(entry.tag, "WARN: ${entry.message}")
                SlockLogLevel.Error.wireValue -> KLog.e(entry.tag, entry.message)
                else -> KLog.i(entry.tag, entry.message)
            }
        }
        return false
    }
}

internal fun prepareSlockOhosLog(
    level: SlockLogLevel,
    tag: String,
    message: String,
    timestampMillis: Long,
    sourceSeq: Long = 0L,
    dropped: Int = 0,
    hint: SlockPlatformLogHint = SlockPlatformLogHint.None
): SlockOhosPreparedLog {
    val safeTag = sanitizeSlockOhosDiagnosticValue(tag).take(SlockOhosLogTagMaxChars)
    val sanitizedMessage = sanitizeSlockOhosDiagnosticValue(message)
    val safeMessage = sanitizedMessage.take(SlockOhosLogMessageMaxChars)
    return SlockOhosPreparedLog(
        level = level.wireValue,
        tag = safeTag,
        event = safeMessage.substringBefore(' ', missingDelimiterValue = safeMessage).take(SlockOhosLogEventMaxChars),
        message = safeMessage,
        timestampMillis = timestampMillis,
        truncated = sanitizedMessage.length > safeMessage.length,
        sourceSeq = sourceSeq,
        dropped = dropped,
        thread = "kmp-worker",
        critical = hint == SlockPlatformLogHint.AuthCritical
    )
}

internal fun sanitizeSlockOhosDiagnosticValue(value: String): String =
    SlockOhosSensitivePatterns.fold(value) { current, pattern ->
        pattern.regex.replace(current, pattern.replacement)
    }

private data class SlockOhosSensitivePattern(
    val regex: Regex,
    val replacement: String
)

private val SlockOhosSensitivePatterns =
    listOf(
        SlockOhosSensitivePattern(
            Regex("""(\bauthorization\s*[:=]\s*bearer\s+)[^\s,;]+""", RegexOption.IGNORE_CASE),
            "\$1[redacted]"
        ),
        SlockOhosSensitivePattern(
            Regex("""(\bbearer\s+)[^\s,;]+""", RegexOption.IGNORE_CASE),
            "\$1[redacted]"
        ),
        SlockOhosSensitivePattern(
            Regex("""(\baccess[_-]?token\s*[:=]\s*)[^\s,;]+""", RegexOption.IGNORE_CASE),
            "\$1[redacted]"
        ),
        SlockOhosSensitivePattern(
            Regex("""(\brefresh[_-]?token\s*[:=]\s*)[^\s,;]+""", RegexOption.IGNORE_CASE),
            "\$1[redacted]"
        ),
        SlockOhosSensitivePattern(
            Regex("""(\bpassword\s*[:=]\s*)[^\s,;]+""", RegexOption.IGNORE_CASE),
            "\$1[redacted]"
        ),
        SlockOhosSensitivePattern(
            Regex("""(\braw\s+(?:response|body)\s*[:=]\s*).+""", RegexOption.IGNORE_CASE),
            "\$1[redacted]"
        ),
        SlockOhosSensitivePattern(
            Regex("""\b((?:eyJ)[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.)[A-Za-z0-9_-]+\b"""),
            "\$1[redacted]"
        ),
        SlockOhosSensitivePattern(
            Regex("""\b([A-Za-z0-9._%+-]{2})[A-Za-z0-9._%+-]*(@[A-Za-z0-9.-]+\.[A-Za-z]{2,})\b"""),
            "\$1[redacted]"
        )
    )
