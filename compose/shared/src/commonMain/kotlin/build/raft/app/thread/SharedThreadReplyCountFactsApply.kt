package build.raft.app.thread

import build.raft.app.SharedThreadFreshnessLedger
import build.raft.app.SharedThreadPayloadCacheKey
import build.raft.app.SharedThreadReplyCountFact
import build.raft.app.messages

/**
 * The payload side of [SharedThreadFreshnessLedger]'s accepted reply counts: applies them to a
 * thread payload's origin and messages. Runs under the ledger lock, as before.
 */
internal fun SharedThreadFreshnessLedger.applyReplyCountFacts(
    serverId: String,
    threadId: String,
    environmentId: String,
    principalId: String,
    payload: RaftThreadScreenPayload
): RaftThreadScreenPayload {
    if (environmentId.isBlank() || principalId.isBlank()) return payload
    return withReplyCountFacts(
        key = SharedThreadPayloadCacheKey(serverId, threadId),
        environmentId = environmentId,
        principalId = principalId,
    ) { acceptedFact ->
        applyReplyCountFactsLocked(
            acceptedFact = acceptedFact,
            payload = payload
        )
    }
}

private fun applyReplyCountFactsLocked(
    acceptedFact: (messageId: String) -> SharedThreadReplyCountFact?,
    payload: RaftThreadScreenPayload
): RaftThreadScreenPayload {
    fun merge(message: RaftThreadMessageItem): RaftThreadMessageItem {
        val accepted = acceptedFact(message.id) ?: return message
        val nextThreadChannelId = accepted.threadChannelId ?: message.threadChannelId
        val nextReplyCount = accepted.replyCount
        return if (
            nextThreadChannelId == message.threadChannelId &&
            nextReplyCount == message.replyCount
        ) {
            message
        } else {
            message.copy(
                threadChannelId = nextThreadChannelId,
                replyCount = nextReplyCount
            )
        }
    }

    val nextOrigin = payload.originMessage?.let(::merge)
    val nextMessages = payload.messages.map(::merge)
    return if (nextOrigin == payload.originMessage && nextMessages == payload.messages) {
        payload
    } else {
        payload.copy(originMessage = nextOrigin, messages = nextMessages)
    }
}
