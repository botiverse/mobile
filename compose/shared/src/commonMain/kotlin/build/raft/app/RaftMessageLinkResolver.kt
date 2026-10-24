package build.raft.app

import build.raft.app.data.DataRequestPriority
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftNetworkClient
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.isRaftMessageContextHistoryLimited
import build.raft.app.data.parseRaftMessageLinkChannelProbe
import build.raft.app.data.parseSharedMessageContext
import build.raft.app.model.ConversationKind
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import kotlinx.coroutines.CancellationException

/** What the app shell does with a [RaftMessageLink]. */
internal sealed interface RaftMessageLinkResolution {
    /**
     * Open through the window navigation coordinator, as a notification tap does: a
     * server-qualified, identity-only source.
     */
    data class Open(
        val serverId: String,
        val sourceIdentity: ConversationOpenSourceMessageIdentity,
    ) : RaftMessageLinkResolution

    /**
     * Not a member of the server, or the conversation/message is not there or not for this reader
     * (403/404/bad id/deleted). One answer for all of them: the empty state never says which.
     */
    data class NotFound(val reason: String) : RaftMessageLinkResolution
}

/**
 * Resolves a link for the signed-in account: {slug} against the account's servers, then the
 * conversation (`GET /api/channels/:id`) and the message (`GET /api/messages/context/:id`) on that
 * server. Only what the API returns for this reader becomes identity; ids from the link that the
 * API did not confirm are never opened.
 *
 * A transport failure or 5xx does not decide "not found": the link opens and the conversation
 * page shows its own load/error state, as a notification tap would.
 */
internal class RaftMessageLinkResolver(
    private val networkClient: RaftNetworkClient,
    private val diagnosticsLogger: (String) -> Unit = {},
) {
    suspend fun resolve(link: RaftMessageLink, servers: List<StoredAuthServer>): RaftMessageLinkResolution {
        val server =
            servers.firstOrNull { it.slug?.equals(link.serverSlug, ignoreCase = true) == true }
                ?: return notFound("server_not_member")
        val serverId = ServerId(server.id)

        val channelResponse =
            request(serverId, path = "channels/${link.channelId}")
                ?: return open(server.id, link, conversationKind = link.routeKind, threadChannelId = null)
        val conversationKind =
            when (channelResponse.outcome()) {
                ProbeOutcome.Found -> {
                    val probe = parseRaftMessageLinkChannelProbe(channelResponse.bodyText)
                    when {
                        probe == null -> link.routeKind
                        probe.id != link.channelId -> return notFound("channel_identity_mismatch")
                        else -> link.routeKind.confirmedBy(probe.type) ?: return notFound("route_kind_mismatch")
                    }
                }
                ProbeOutcome.NotFound -> return notFound("channel_${channelResponse.code}")
                ProbeOutcome.Unavailable -> link.routeKind
            }

        val focusMessageId = link.focusMessageId
            ?: return open(server.id, link, conversationKind, threadChannelId = null)
        val contextResponse =
            request(
                serverId,
                path = "messages/context/$focusMessageId",
                query = listOf("channelId" to link.channelId, "before" to "0", "after" to "0"),
            ) ?: return open(server.id, link, conversationKind, threadChannelId = null)
        return when (contextResponse.outcome()) {
            ProbeOutcome.Found -> {
                val context = parseSharedMessageContext(contextResponse.bodyText.orEmpty(), ThreadId(link.channelId))
                val target = context.canonicalTarget?.takeIf { it.kind == "thread" }
                val targetParentId = target?.threadParentMessageId?.value
                when {
                    // A reply link: the reply must sit in the named thread of this channel.
                    link.isThread && link.messageId != null && link.messageId != link.threadParentMessageId ->
                        if (target == null || targetParentId != link.threadParentMessageId) {
                            notFound("thread_reply_mismatch")
                        } else {
                            open(server.id, link, conversationKind, threadChannelId = target.threadChannelId?.value)
                        }
                    // The thread parent itself is a top-level message of this channel.
                    link.isThread ->
                        if (target != null) {
                            notFound("thread_parent_is_reply")
                        } else {
                            open(server.id, link, conversationKind, threadChannelId = null)
                        }
                    // A channel link to what is a reply opens its thread, as the web does.
                    target != null && targetParentId != null ->
                        open(
                            server.id,
                            link.copy(threadParentMessageId = targetParentId),
                            conversationKind,
                            threadChannelId = target.threadChannelId?.value,
                        )
                    else -> open(server.id, link, conversationKind, threadChannelId = null)
                }
            }
            ProbeOutcome.NotFound ->
                if (contextResponse.code == 404 && isRaftMessageContextHistoryLimited(contextResponse.bodyText ?: contextResponse.errorBodyText)) {
                    // Readable but behind the plan's history window: open on the paywall.
                    open(server.id, link, conversationKind, threadChannelId = null)
                } else {
                    notFound("message_${contextResponse.code}")
                }
            ProbeOutcome.Unavailable -> open(server.id, link, conversationKind, threadChannelId = null)
        }
    }

    private suspend fun request(
        serverId: ServerId,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
    ): HomeNetworkResponse? =
        try {
            networkClient.request(
                priority = DataRequestPriority.Foreground,
                path = path,
                query = query,
                serverIdOverride = serverId,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            diagnosticsLogger("message_link_probe_failed path=${path.substringBefore('/')} error=${error::class.simpleName.orEmpty()}")
            null
        }

    private fun open(
        serverId: String,
        link: RaftMessageLink,
        conversationKind: ConversationKind,
        threadChannelId: String?,
    ): RaftMessageLinkResolution.Open {
        diagnosticsLogger(
            "message_link_resolved result=open kind=${conversationKind.name} thread=${link.isThread} " +
                "threadChannelKnown=${threadChannelId != null}"
        )
        return RaftMessageLinkResolution.Open(
            serverId = serverId,
            sourceIdentity = link.toConversationOpenSourceMessageIdentity(conversationKind, threadChannelId),
        )
    }

    private fun notFound(reason: String): RaftMessageLinkResolution.NotFound {
        diagnosticsLogger("message_link_resolved result=not_found reason=$reason")
        return RaftMessageLinkResolution.NotFound(reason)
    }
}

private enum class ProbeOutcome { Found, NotFound, Unavailable }

/**
 * 2xx: readable. 400/403/404/410: not found for this reader (bad id, not a member, deleted, no
 * access). Anything else (401 is the auth layer's, 408/429/5xx/transport) is not an answer.
 */
private fun HomeNetworkResponse.outcome(): ProbeOutcome =
    when (code) {
        in 200..299 -> ProbeOutcome.Found
        400, 403, 404, 410 -> ProbeOutcome.NotFound
        else -> ProbeOutcome.Unavailable
    }

/**
 * The route kind the channel's own `type` confirms: `/dm/` only for a DM, `/channel/` for any
 * other conversation (a joint channel opens as one). Null when the link names the wrong kind, or a
 * thread's own channel id.
 */
private fun ConversationKind.confirmedBy(type: String): ConversationKind? =
    when (this) {
        ConversationKind.DirectMessage -> ConversationKind.DirectMessage.takeIf { type == "dm" }
        else ->
            when (type) {
                "dm", "thread" -> null
                "joint" -> ConversationKind.Joint
                else -> ConversationKind.Channel
            }
    }

/** The identity-only open source of a resolved link (no titles, previews or names). */
internal fun RaftMessageLink.toConversationOpenSourceMessageIdentity(
    conversationKind: ConversationKind = routeKind,
    threadChannelId: String? = null,
): ConversationOpenSourceMessageIdentity {
    val parentMessageId = threadParentMessageId
    return if (parentMessageId != null) {
        val streamId = threadChannelId?.takeIf(String::isNotBlank)
        ConversationOpenSourceMessageIdentity(
            source = ConversationOpenSource.MessageLink,
            messageId = focusMessageId,
            channelId = streamId ?: channelId,
            threadChannelId = streamId,
            parentChannelId = channelId,
            parentMessageId = parentMessageId,
            conversationKind = ConversationKind.Thread,
            parentConversationKind = conversationKind,
        )
    } else {
        ConversationOpenSourceMessageIdentity(
            source = ConversationOpenSource.MessageLink,
            messageId = messageId,
            channelId = channelId,
            conversationKind = conversationKind,
        )
    }
}
