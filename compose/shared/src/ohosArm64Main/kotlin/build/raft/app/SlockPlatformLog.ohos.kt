package build.raft.app

import com.tencent.kuikly.core.log.KLog
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.tmm.knoi.annotation.ServiceConsumer
import com.tencent.tmm.knoi.service.KNOI
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SlockOhosSharedLogSinkService = "slockOhosSharedLogSink"

@ServiceConsumer(name = SlockOhosSharedLogSinkService)
interface SlockOhosSharedLogSink {
    fun record(level: String, tag: String, message: String)

    fun recordBatch(payload: String)
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

private data class SlockOhosPendingLog(
    val level: SlockLogLevel,
    val tag: String,
    val message: String,
    val timestampMillis: Long,
    val hint: SlockPlatformLogHint
)

private object SlockOhosAsyncPlatformLogSink {
    private const val QueueCapacity = 512
    private const val BatchCapacity = 32
    private const val BatchDelayMillis = 100L
    private const val QueueTag = "SlockOhosAsyncLog"

    private val queue = Channel<SlockOhosPendingLog>(capacity = QueueCapacity)
    private val droppedCount = atomic(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            while (true) {
                val batch = mutableListOf(queue.receive())
                delay(BatchDelayMillis)
                while (batch.size < BatchCapacity) {
                    val entry = queue.tryReceive().getOrNull() ?: break
                    batch += entry
                }
                val dropped = droppedCount.getAndSet(0)
                if (dropped > 0) {
                    batch +=
                        SlockOhosPendingLog(
                            level = SlockLogLevel.Warn,
                            tag = QueueTag,
                            message = "slock_ohos_log_queue_dropped count=$dropped capacity=$QueueCapacity",
                            timestampMillis = batch.last().timestampMillis,
                            hint = SlockPlatformLogHint.AuthCritical
                        )
                }
                recordBatch(batch)
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
        val result = queue.trySend(SlockOhosPendingLog(level, tag, message, timestampMillis, hint))
        if (result.isSuccess) return

        droppedCount.incrementAndGet()
    }

    private fun recordBatch(entries: List<SlockOhosPendingLog>) {
        val payload =
            JSONArray().apply {
                entries.forEach { entry ->
                    put(
                        JSONObject().apply {
                            put("level", entry.level.wireValue)
                            put("tag", entry.tag)
                            put("message", entry.message)
                            put("timestampMillis", entry.timestampMillis)
                            put("critical", entry.hint == SlockPlatformLogHint.AuthCritical)
                        }
                    )
                }
            }.toString()
        val recorded =
            runCatching {
                KNOI.get<SlockOhosSharedLogSink>(SlockOhosSharedLogSinkService)
                    .recordBatch(payload)
            }.isSuccess
        if (recorded) return
        entries.forEach { entry ->
            when (entry.level) {
                SlockLogLevel.Debug -> KLog.d(entry.tag, entry.message)
                SlockLogLevel.Info -> KLog.i(entry.tag, entry.message)
                SlockLogLevel.Warn -> KLog.i(entry.tag, "WARN: ${entry.message}")
                SlockLogLevel.Error -> KLog.e(entry.tag, entry.message)
            }
        }
    }
}
