package build.raft.app.data

import build.raft.app.model.ChannelId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Coalesces visible read receipts by channel while preserving Web's single-flight behavior.
 */
class ReadReceiptCoordinator(
    private val scope: CoroutineScope,
    private val repository: ReadReceiptRepository,
    private val requestRunner:
        suspend (ReadReceiptRequest, suspend () -> DataResult<ReadReceiptResult>) ->
            DataResult<ReadReceiptResult> =
        { _, block -> block() },
    private val onOptimisticRead: (ChannelId) -> Unit = {},
    private val onResult: (ReadReceiptRequest, DataResult<ReadReceiptResult>) -> Unit = { _, _ -> },
    private val onFailure: (ReadReceiptRequest, DataResult<ReadReceiptResult>) -> Unit = { _, _ -> }
) {
    private val channels = mutableMapOf<ChannelId, ChannelQueue>()
    private val acknowledgedSeqs = mutableMapOf<ChannelId, Long>()

    fun markReadToSeq(channelId: ChannelId, seq: Long) {
        if (seq <= 0L) {
            markReadAll(channelId)
            return
        }
        val queue = channels.getOrPut(channelId) { ChannelQueue() }
        val highestCoveredSeq =
            listOfNotNull(
                acknowledgedSeqs[channelId],
                queue.inFlightSeq,
                queue.pendingSeq
            )
                .maxOrNull()
        if (highestCoveredSeq != null && seq <= highestCoveredSeq) {
            return
        }
        queue.pendingSeq = maxOf(queue.pendingSeq ?: seq, seq)
        onOptimisticRead(channelId)
        if (queue.inFlight == null) {
            queue.inFlight = scope.launch { drain(channelId, queue) }
        }
    }

    fun markReadAll(channelId: ChannelId) {
        val queue = channels.getOrPut(channelId) { ChannelQueue() }
        if (queue.pendingReadAll || queue.inFlightReadAll) {
            return
        }
        queue.pendingReadAll = true
        onOptimisticRead(channelId)
        if (queue.inFlight == null) {
            queue.inFlight = scope.launch { drain(channelId, queue) }
        }
    }

    private suspend fun drain(channelId: ChannelId, queue: ChannelQueue) {
        try {
            while (true) {
                val request =
                    when {
                        queue.pendingSeq != null -> {
                            val seq = queue.pendingSeq ?: break
                            queue.pendingSeq = null
                            queue.inFlightSeq = seq
                            ReadReceiptRequest.ToSeq(channelId, seq)
                        }
                        queue.pendingReadAll -> {
                            queue.pendingReadAll = false
                            queue.inFlightReadAll = true
                            ReadReceiptRequest.All(channelId)
                        }
                        else -> break
                    }
                val result =
                    requestRunner(request) {
                        when (request) {
                            is ReadReceiptRequest.ToSeq ->
                                repository.markChannelReadToSeq(channelId, request.seq)
                            is ReadReceiptRequest.All -> repository.markChannelReadAll(channelId)
                        }
                    }
                onResult(request, result)
                if (result is DataResult.Content && request is ReadReceiptRequest.ToSeq) {
                    acknowledgedSeqs[channelId] =
                        maxOf(acknowledgedSeqs[channelId] ?: request.seq, request.seq)
                }
                if (result !is DataResult.Content) {
                    onFailure(request, result)
                }
                when (request) {
                    is ReadReceiptRequest.ToSeq -> queue.inFlightSeq = null
                    is ReadReceiptRequest.All -> queue.inFlightReadAll = false
                }
            }
        } finally {
            queue.inFlightSeq = null
            queue.inFlightReadAll = false
            queue.inFlight = null
            if (queue.hasPending()) {
                queue.inFlight = scope.launch { drain(channelId, queue) }
            } else {
                channels.remove(channelId)
            }
        }
    }

    private data class ChannelQueue(
        var pendingSeq: Long? = null,
        var pendingReadAll: Boolean = false,
        var inFlightSeq: Long? = null,
        var inFlightReadAll: Boolean = false,
        var inFlight: Job? = null
    ) {
        fun hasPending(): Boolean = pendingSeq != null || pendingReadAll
    }
}

sealed interface ReadReceiptRequest {
    val channelId: ChannelId

    data class ToSeq(
        override val channelId: ChannelId,
        val seq: Long
    ) : ReadReceiptRequest

    data class All(
        override val channelId: ChannelId
    ) : ReadReceiptRequest
}
