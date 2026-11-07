package build.raft.app.data

import build.raft.app.network.sync.DurablePartitionKey
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.ThreadDurablePageExtender
import build.raft.app.network.sync.ThreadDurableReplyParent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Message storage Phase 2c: where a `messages/context` response read outside a conversation screen (message link, agent
 * overview) is offered for durable storage. Such a write is no use of the conversation: it never counts as user-driven
 * for retention, and a page of a never-opened channel stays invisible until Phase 2d.
 */
interface RaftMessageContextPageSink {
    /**
     * Call BEFORE the request: what it captures must predate the fetch. [serverId] `null` = the selected server. `null`
     * result = no identity, nothing to store.
     */
    suspend fun beginRequest(serverId: String?): RaftMessageContextPageRequest?
}

/** One context request in flight. [serverId] is the server it must be sent to and is stored under. */
interface RaftMessageContextPageRequest {
    val serverId: String

    /**
     * Fire and forget: never delays the caller. [channelId] / [channelType]: a channel the caller already read, whose type
     * helps only when it is the response's own channel.
     */
    fun offer(bodyText: String, channelId: String? = null, channelType: String? = null)
}

/** The production sink: the same writer as a conversation's pages, one identity captured per request. */
internal class DurableMessageContextPageSink(
    private val extender: ThreadDurablePageExtender,
    private val scope: CoroutineScope,
    /** (environment, principal, selected server) of the current account; `null` = none. */
    private val currentIdentity: suspend () -> Triple<String, String, String>?,
    /** (environment, principal) of the current account, as a non-suspending recheck before the write. */
    private val currentAccount: () -> Pair<String, String>?,
    private val logger: (String) -> Unit = {},
) : RaftMessageContextPageSink {
    override suspend fun beginRequest(serverId: String?): RaftMessageContextPageRequest? {
        val (environmentId, principalId, selectedServerId) = currentIdentity() ?: return null
        val targetServerId = serverId ?: selectedServerId
        val partition = runCatching {
            DurablePartitionKey(
                serverId = targetServerId,
                principalId = principalId,
                consumerId = SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID,
                environmentId = environmentId,
            )
        }.getOrNull() ?: return null
        val account = currentAccount() ?: return null
        val revAtRequest = extender.currentLocalRev(partition)
        val headEpochAtRequest = extender.headEpoch(partition)
        return object : RaftMessageContextPageRequest {
            override val serverId: String = targetServerId

            override fun offer(bodyText: String, channelId: String?, channelType: String?) {
                scope.launch {
                    try {
                        val page = rawMessagePageWitness(bodyText, requestedLimit = 0).page ?: return@launch
                        val response = page.context ?: return@launch
                        val anchor = response.targetMessageId ?: return@launch
                        val parent = response.threadParentChannelId?.let { parentChannelId ->
                            response.threadRootMessageId?.let { root -> ThreadDurableReplyParent(parentChannelId, root) }
                        }
                        extender.persistContextPage(
                            partition = partition,
                            channelId = response.channelId,
                            anchorMessageId = anchor,
                            rawMessages = page.messages,
                            hasOlder = response.hasOlder,
                            hasNewer = response.hasNewer,
                            replyParent = parent,
                            requestHeadEpoch = headEpochAtRequest,
                            historyLimited = page.historyLimited,
                            revAtRequest = revAtRequest,
                            replyCountByParentId = page.replyCountsByParentId,
                            userDriven = false,
                            channelType = channelType?.takeIf { channelId == response.channelId },
                        ) { currentAccount() == account }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (throwable: Throwable) {
                        logger("canonical_v2_context_page_offer_failed reason=${throwable::class.simpleName ?: "unknown"}")
                    }
                }
            }
        }
    }
}
