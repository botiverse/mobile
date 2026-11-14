package build.raft.app

import build.raft.app.data.DataRequestPriority
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.CHANNEL_THREAD_SORT_ACTIVITY
import build.raft.app.data.RaftMessageContextPageSink
import build.raft.app.data.RaftNetworkClient
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.isRaftMessageContextHistoryLimited
import build.raft.app.data.parseRaftMessageLinkChannelProbe
import build.raft.app.data.parseSharedMessageContext
import build.raft.app.model.ConversationKind
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

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
     * {slug} is not one of the account's servers: the link opens the read-only public preview
     * ([RaftPublicConversationPreviewStore]), which reads it through the anonymous public routes
     * and shows the same not-found state when a stranger may not read it either.
     */
    data class PublicPreview(val link: RaftMessageLink) : RaftMessageLinkResolution

    /**
     * The conversation/message is not there or not for this reader (400/403/404: no access,
     * deleted, a well-formed id that names nothing). One answer for all of them: the empty state
     * never says which, and all of them made the same requests.
     */
    data class NotFound(val reason: String) : RaftMessageLinkResolution
}

/**
 * Resolves a link for the signed-in account: {slug} against the account's servers, then the
 * conversation (`GET /api/channels/:id`) and the message (`GET /api/messages/context/:id`) on that
 * server. Only what the API returns for this reader becomes identity; ids from the link that the
 * API did not confirm are never opened.
 *
 * A transient failure (5xx, 408/429, timeout, offline, DNS) never decides "not found": the
 * link opens and the conversation page shows its own load/retry/offline state, as a
 * notification tap would (slock #11126 review rule 1).
 *
 * Both probes are always sent together, whatever the first answers, so a deleted message, a
 * conversation without access and an id that names nothing make identical requests.
 */
internal class RaftMessageLinkResolver(
    private val networkClient: RaftNetworkClient,
    private val diagnosticsLogger: (String) -> Unit = {},
    /** Message storage Phase 2c: where a resolved link's context page is offered; it never delays or changes the result. */
    private val contextPageSink: RaftMessageContextPageSink? = null,
) {
    suspend fun resolve(link: RaftMessageLink, servers: List<StoredAuthServer>): RaftMessageLinkResolution {
        val server =
            servers.firstOrNull { it.slug?.equals(link.serverSlug, ignoreCase = true) == true }
                ?: run {
                    diagnosticsLogger("message_link_resolved result=public_preview")
                    return RaftMessageLinkResolution.PublicPreview(link)
                }
        val serverId = ServerId(server.id)
        val focusMessageId = link.focusMessageId
        // Before the requests: the page's revision and head epoch must predate its fetch.
        val pendingContextPage = focusMessageId?.let { contextPageSink?.beginRequest(server.id) }
        val (channelResponse, contextResponse) =
            coroutineScope {
                val channel = async { request(serverId, path = "channels/${link.channelId}") }
                val context =
                    focusMessageId?.let { messageId ->
                        async {
                            request(
                                serverId,
                                path = "messages/context/$messageId",
                                query = listOf("channelId" to link.channelId, "before" to "0", "after" to "0"),
                            )
                        }
                    }
                channel.await() to context?.await()
            }

        val conversationKind =
            when (channelResponse.linkOutcome()) {
                RaftLinkProbeOutcome.Found -> {
                    val probe = parseRaftMessageLinkChannelProbe(channelResponse?.bodyText)
                    when {
                        probe == null -> link.routeKind
                        probe.id != link.channelId -> return notFound("channel_identity_mismatch")
                        else -> link.routeKind.confirmedBy(probe.type) ?: return notFound("route_kind_mismatch")
                    }
                }
                RaftLinkProbeOutcome.NotFound ->
                    return openThroughOtherServer(link, server, servers) ?: notFound("channel_${channelResponse?.code}")
                RaftLinkProbeOutcome.Unavailable -> link.routeKind
            }
        if (focusMessageId == null) return open(server.id, link, conversationKind, threadChannelId = null)

        val resolution = when (contextResponse.linkOutcome()) {
            RaftLinkProbeOutcome.Found -> {
                val context = parseSharedMessageContext(contextResponse?.bodyText.orEmpty(), ThreadId(link.channelId))
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
            RaftLinkProbeOutcome.NotFound ->
                if (
                    contextResponse?.code == 404 &&
                    isRaftMessageContextHistoryLimited(contextResponse.bodyText ?: contextResponse.errorBodyText)
                ) {
                    // Readable but behind the plan's history window: open on the paywall.
                    open(server.id, link, conversationKind, threadChannelId = null)
                } else {
                    notFound("message_${contextResponse?.code}")
                }
            RaftLinkProbeOutcome.Unavailable -> open(server.id, link, conversationKind, threadChannelId = null)
        }
        if (resolution is RaftMessageLinkResolution.Open && contextResponse.linkOutcome() == RaftLinkProbeOutcome.Found) {
            val probe = parseRaftMessageLinkChannelProbe(channelResponse?.bodyText)
            // A forum channel never writes segments.
            if (probe?.threadSort != CHANNEL_THREAD_SORT_ACTIVITY) {
                contextResponse?.bodyText?.let { body ->
                    // The probe names the link's channel. The page's scope is the response's, so the type only helps
                    // when they are the same conversation; the sink checks.
                    pendingContextPage?.offer(body, channelId = link.channelId, channelType = probe?.type)
                }
            }
        }
        return resolution
    }

    /**
     * botiverse/slock #12371 (web `useOtherServerMessageLink`): a link to a joint channel names the
     * copy on the server it was copied from; a reader who takes part only through another of their
     * servers can't read it there. Asks each other server of the account, one at a time, whether it
     * can show the message (`GET /messages/context/:id?channelId`, as a link from another server is
     * resolved) and opens the link on the first that can, with that server's copy of the channel
     * and the thread. Null: none can (or the link is a DM, or names no message): not found.
     */
    private suspend fun openThroughOtherServer(
        link: RaftMessageLink,
        linkServer: StoredAuthServer,
        servers: List<StoredAuthServer>,
    ): RaftMessageLinkResolution.Open? {
        if (link.routeKind != ConversationKind.Channel) return null
        // What to look up: the message, or for a thread-only link its parent.
        val lookupId = link.focusMessageId ?: return null
        for (other in servers.filter { it.id != linkServer.id }) {
            val response =
                request(
                    ServerId(other.id),
                    path = "messages/context/$lookupId",
                    query = listOf("channelId" to link.channelId),
                )
            // Not readable from this server either (or it didn't answer): ask the next.
            if (response.linkOutcome() != RaftLinkProbeOutcome.Found) continue
            val context = parseSharedMessageContext(response?.bodyText.orEmpty(), ThreadId(link.channelId))
            val thread = context.canonicalTarget?.takeIf { it.kind == "thread" }
            val localChannelId = (if (thread != null) thread.channelId else context.channelId)?.value ?: continue
            val localThreadParentId = if (thread != null) thread.threadParentMessageId?.value else link.threadParentMessageId
            val moved =
                link.copy(
                    serverSlug = other.slug ?: link.serverSlug,
                    channelId = localChannelId,
                    // A thread-only link moves without gaining a message.
                    messageId = link.messageId?.let { context.targetMessageId?.value ?: it },
                    threadParentMessageId = localThreadParentId,
                )
            diagnosticsLogger("message_link_other_server result=found thread=${moved.isThread}")
            return open(other.id, moved, link.routeKind, threadChannelId = thread?.threadChannelId?.value)
        }
        return null
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

internal enum class RaftLinkProbeOutcome { Found, NotFound, Unavailable }

/**
 * 2xx: readable. 400/403/404 only: not found for this reader (bad id, deleted, no access).
 * Anything else, including no response at all (timeout, offline, DNS), 401 (the auth layer's),
 * 408, 429 and 5xx, is not an answer and must never show the not-found state.
 */
internal fun HomeNetworkResponse?.linkOutcome(): RaftLinkProbeOutcome =
    when (this?.code) {
        null -> RaftLinkProbeOutcome.Unavailable
        in 200..299 -> RaftLinkProbeOutcome.Found
        400, 403, 404 -> RaftLinkProbeOutcome.NotFound
        else -> RaftLinkProbeOutcome.Unavailable
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
