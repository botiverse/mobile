package build.raft.app.data

import build.raft.app.model.ChannelId

/**
 * Keeps UI-driven read receipt side effects idempotent across reactive state writes.
 */
class ReadReceiptVisibilityGate {
    private val requestedSeqs = mutableMapOf<ChannelId, Long>()
    private val requestedReadAll = mutableSetOf<ChannelId>()

    fun shouldMarkReadToSeq(channelId: ChannelId, seq: Long): Boolean {
        if (seq <= 0L) return shouldMarkReadAll(channelId)
        val highestRequestedSeq = requestedSeqs[channelId]
        if (highestRequestedSeq != null && seq <= highestRequestedSeq) {
            return false
        }
        requestedSeqs[channelId] = seq
        requestedReadAll.remove(channelId)
        return true
    }

    fun shouldMarkReadAll(channelId: ChannelId): Boolean {
        if (requestedReadAll.contains(channelId) || requestedSeqs.containsKey(channelId)) {
            return false
        }
        requestedReadAll += channelId
        return true
    }

    fun clear(channelId: ChannelId) {
        requestedSeqs.remove(channelId)
        requestedReadAll.remove(channelId)
    }
}
