package build.raft.app

import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftMessageContextPageSink
import build.raft.app.data.RaftNetworkClient
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.isRaftMessageContextHistoryLimited
import build.raft.app.data.parseHttpErrorEnvelope
import build.raft.app.data.parseRaftMessageLinkChannelProbe
import build.raft.app.data.parseRaftPublicServerCard
import build.raft.app.data.parseSharedMessageContext
import build.raft.app.data.parseSharedServerTasksPayload
import build.raft.app.data.raftPublicServerPath
import build.raft.app.data.toTaskConversationOpenResult
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ServerId
import build.raft.app.model.TaskSummary
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
     * A task-conversation link (`?task=<parentId>:task:<taskId>`, slock #16576) whose task has no
     * conversation yet (opening must not create one), or whose task lookup hit a transient network
     * failure: opens [TASK_PENDING_CONVERSATION_PAGE], preserving [focusedMessageId] for hand-off.
     */
    data class OpenPendingTask(
        val serverId: String,
        val taskId: String,
        val channelId: ChannelId,
        val task: TaskSummary? = null,
        val focusedMessageId: String? = null,
        val discussionElsewhere: Boolean = false,
    ) : RaftMessageLinkResolution

    /**
     * {slug} is not one of the account's servers, and its public card lists the link's channel
     * among those a stranger may read, or could not be read now (a transient failure never decides
     * "not found"): the link opens the read-only public preview
     * ([RaftPublicConversationPreviewStore]), which reads it through the anonymous public routes
     * and shows the same not-found state when a stranger may not read it either.
     */
    data class PublicPreview(val link: RaftMessageLink) : RaftMessageLinkResolution

    /**
     * {slug} is not one of the account's servers, and its public card says there is nothing here
     * for a stranger: no such server or not public (400/403/404), or public without the link's
     * channel among those listed, or a DM link (the public routes serve channels only). The shell
     * opens the neutral "Message not found" page ([openRaftMessageLinkNotFoundPage]), as the web
     * shows its not-found page instead of the public page; never the conversation-unavailable page,
     * which is for a conversation on one of the account's servers.
     */
    data class PublicNotFound(val reason: String) : RaftMessageLinkResolution

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
 * API did not confirm are never opened. A {slug} that is not one of the account's servers is
 * asked whether it is public first (`GET /api/public/servers/:slug`, see [resolveOutsideAccount]).
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
                ?: return if (link.isTask) publicNotFound("public_task_not_supported") else resolveOutsideAccount(link)
        if (link.isTask) {
            return resolveTaskConversationLink(link, server)
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

        val channelProbe =
            if (channelResponse.linkOutcome() == RaftLinkProbeOutcome.Found) {
                parseRaftMessageLinkChannelProbe(channelResponse?.bodyText)
            } else {
                null
            }
        var threadRedirect: ThreadChannelRedirect? = null
        val conversationKind =
            when (channelResponse.linkOutcome()) {
                RaftLinkProbeOutcome.Found -> {
                    when {
                        channelProbe == null -> link.routeKind
                        channelProbe.id != link.channelId -> return notFound("channel_identity_mismatch")
                        channelProbe.type == "thread" -> {
                            // slock #15879 (`useTaskConversationRedirect` + `threadConversationPlace`):
                            // `/channel/<threadChannelId>[?msg=…]` redirects to the parent conversation
                            // with the thread open instead of showing "not found" or a bare thread page.
                            // Never allow `/dm/<threadId>`, nested `?thread=` on a thread channel id, or
                            // a thread row missing its parent channel/message ids.
                            if (link.routeKind == ConversationKind.DirectMessage || link.isThread) {
                                return notFound("route_kind_mismatch")
                            }
                            val parentChannelId = channelProbe.parentChannelId ?: return notFound("route_kind_mismatch")
                            val parentMessageId = channelProbe.parentMessageId ?: return notFound("route_kind_mismatch")
                            // Probe the parent channel so `parentConversationKind` is server-confirmed
                            // even when the parent is absent from Home's directory (archived channels,
                            // Agent Lounges, or cold server switches).
                            val parentResponse = request(serverId, path = "channels/$parentChannelId")
                            val parentConversationKind =
                                when (parentResponse.linkOutcome()) {
                                    RaftLinkProbeOutcome.Found -> {
                                        val parentProbe = parseRaftMessageLinkChannelProbe(parentResponse?.bodyText)
                                        when {
                                            parentProbe == null -> null
                                            parentProbe.id != parentChannelId -> return notFound("channel_identity_mismatch")
                                            else -> parentProbe.type.toParentConversationKind() ?: return notFound("route_kind_mismatch")
                                        }
                                    }
                                    RaftLinkProbeOutcome.NotFound -> return notFound("parent_channel_${parentResponse?.code}")
                                    RaftLinkProbeOutcome.Unavailable -> null
                                }
                            threadRedirect =
                                ThreadChannelRedirect(
                                    threadChannelId = channelProbe.id,
                                    parentChannelId = parentChannelId,
                                    parentMessageId = parentMessageId,
                                    parentConversationKind = parentConversationKind,
                                )
                            ConversationKind.Thread
                        }
                        else -> link.routeKind.confirmedBy(channelProbe.type) ?: return notFound("route_kind_mismatch")
                    }
                }
                RaftLinkProbeOutcome.NotFound ->
                    return openThroughOtherServer(link, server, servers) ?: notFound("channel_${channelResponse?.code}")
                RaftLinkProbeOutcome.Unavailable -> link.routeKind
            }
        if (focusMessageId == null) {
            val redirect = threadRedirect
            return if (redirect != null) {
                open(
                    serverId = server.id,
                    link = link.redirectedToThread(redirect, messageId = null),
                    conversationKind = ConversationKind.Thread,
                    parentConversationKind = redirect.parentConversationKind,
                    threadChannelId = redirect.threadChannelId,
                )
            } else {
                open(server.id, link, conversationKind, threadChannelId = null)
            }
        }

        val resolution = when (contextResponse.linkOutcome()) {
            RaftLinkProbeOutcome.Found -> {
                val context = parseSharedMessageContext(contextResponse?.bodyText.orEmpty(), ThreadId(link.channelId))
                val target = context.canonicalTarget?.takeIf { it.kind == "thread" }
                val targetParentId = target?.threadParentMessageId?.value
                // slock #15521: a link may name its message or thread parent by an 8-hex short id;
                // the lookup answers the full one, which is what the conversation is opened with
                // (a short id never reaches the thread or message reads, which want the full id).
                val lookedUp = context.targetMessageId?.value?.takeIf { raftMessageIdMatches(focusMessageId, it) } ?: focusMessageId
                val redirect = threadRedirect
                when {
                    // slock #15879: `/channel/<threadChannelId>?msg=<reply>` opens the thread on its
                    // parent conversation focused at the resolved reply.
                    redirect != null ->
                        if (
                            target != null && (
                                targetParentId == null ||
                                    !raftMessageIdMatches(redirect.parentMessageId, targetParentId) ||
                                    (target.channelId != null && target.channelId?.value != redirect.parentChannelId)
                                )
                        ) {
                            notFound("thread_reply_mismatch")
                        } else {
                            open(
                                serverId = server.id,
                                link = link.redirectedToThread(redirect, messageId = lookedUp),
                                conversationKind = ConversationKind.Thread,
                                parentConversationKind = redirect.parentConversationKind,
                                threadChannelId = target?.threadChannelId?.value?.takeIf(String::isNotBlank) ?: redirect.threadChannelId,
                            )
                        }
                    // A reply link: the reply must sit in the named thread of this channel.
                    link.isThread && link.messageId != null && link.messageId != link.threadParentMessageId ->
                        if (target == null || targetParentId == null || !raftMessageIdMatches(link.threadParentMessageId, targetParentId)) {
                            notFound("thread_reply_mismatch")
                        } else {
                            open(
                                server.id,
                                link.copy(messageId = lookedUp, threadParentMessageId = targetParentId),
                                conversationKind,
                                threadChannelId = target.threadChannelId?.value,
                            )
                        }
                    // The thread parent itself is a top-level message of this channel.
                    link.isThread ->
                        if (target != null) {
                            notFound("thread_parent_is_reply")
                        } else {
                            open(
                                server.id,
                                link.copy(messageId = link.messageId?.let { lookedUp }, threadParentMessageId = lookedUp),
                                conversationKind,
                                threadChannelId = null,
                            )
                        }
                    // A channel link to what is a reply opens its thread, as the web does.
                    target != null && targetParentId != null ->
                        open(
                            server.id,
                            link.copy(messageId = lookedUp, threadParentMessageId = targetParentId),
                            conversationKind,
                            threadChannelId = target.threadChannelId?.value,
                        )
                    else -> open(server.id, link.copy(messageId = lookedUp), conversationKind, threadChannelId = null)
                }
            }
            RaftLinkProbeOutcome.NotFound -> {
                val redirect = threadRedirect
                when {
                    contextResponse?.code == 404 &&
                        isRaftMessageContextHistoryLimited(contextResponse.bodyText ?: contextResponse.errorBodyText) ->
                        // Readable but behind the plan's history window: open on the paywall.
                        if (redirect != null) {
                            open(
                                serverId = server.id,
                                link = link.redirectedToThread(redirect, messageId = focusMessageId),
                                conversationKind = ConversationKind.Thread,
                                parentConversationKind = redirect.parentConversationKind,
                                threadChannelId = redirect.threadChannelId,
                            )
                        } else {
                            open(server.id, link, conversationKind, threadChannelId = null)
                        }
                    redirect != null && raftMessageIdMatches(focusMessageId, redirect.parentMessageId) ->
                        // `?msg=` names the thread's own parent message (which lives in parentChannelId,
                        // so `messages/context/:id?channelId=<threadId>` 404s even though the thread
                        // probe already confirmed this parent message).
                        open(
                            serverId = server.id,
                            link = link.redirectedToThread(redirect, messageId = redirect.parentMessageId),
                            conversationKind = ConversationKind.Thread,
                            parentConversationKind = redirect.parentConversationKind,
                            threadChannelId = redirect.threadChannelId,
                        )
                    else -> notFound("message_${contextResponse?.code}")
                }
            }
            RaftLinkProbeOutcome.Unavailable -> {
                val redirect = threadRedirect
                if (redirect != null) {
                    open(
                        serverId = server.id,
                        link = link.redirectedToThread(redirect, messageId = focusMessageId),
                        conversationKind = ConversationKind.Thread,
                        parentConversationKind = redirect.parentConversationKind,
                        threadChannelId = redirect.threadChannelId,
                    )
                } else {
                    open(server.id, link, conversationKind, threadChannelId = null)
                }
            }
        }
        if (resolution is RaftMessageLinkResolution.Open && contextResponse.linkOutcome() == RaftLinkProbeOutcome.Found) {
            contextResponse?.bodyText?.let { body ->
                // The probe names the link's channel. The page's scope is the response's, so the type only helps
                // when they are the same conversation; the sink checks.
                pendingContextPage?.offer(body, channelId = link.channelId, channelType = channelProbe?.type)
            }
        }
        return resolution
    }

    /**
     * {slug} is not one of the account's servers. The public preview opens only once
     * `GET /api/public/servers/:slug` says the server is public and lists the link's channel among
     * those a stranger may read, as the web reads that card before it shows its public page
     * (botiverse/slock@0ea95d1cd packages/web/src/pages/PublicServerPage.tsx,
     * packages/server/src/routes/publicServer.ts). One request, whatever it answers:
     *
     * - 400/403/404 (no such server, deleted, not public), or 2xx for a DM link (the public routes
     *   serve ordinary channels only) or without the link's channel: [RaftMessageLinkResolution.PublicNotFound];
     * - 2xx listing the channel (a thread link names its parent channel): the preview;
     * - no answer (offline, timeout, DNS), 401, 408, 429, 5xx, or a 2xx body that is not the card:
     *   the preview as well, whose own Retry handles it. A temporary failure never decides "not found".
     */
    private suspend fun resolveOutsideAccount(link: RaftMessageLink): RaftMessageLinkResolution {
        // The public routes are anonymous: no server of the account is named.
        val response = request(serverId = null, path = raftPublicServerPath(link.serverSlug))
        return when (response.linkOutcome()) {
            RaftLinkProbeOutcome.NotFound -> publicNotFound("public_server_${response?.code}")
            RaftLinkProbeOutcome.Unavailable -> publicPreview(link, reason = "public_server_${response?.code ?: "transport"}")
            RaftLinkProbeOutcome.Found -> {
                if (link.routeKind != ConversationKind.Channel) return publicNotFound("public_route_kind_mismatch")
                val card = parseRaftPublicServerCard(response?.bodyText) ?: return publicPreview(link, reason = "public_server_unreadable")
                // Channel ids are UUIDs: a link may spell one in either case.
                if (card.channelIds.none { it.equals(link.channelId, ignoreCase = true) }) {
                    publicNotFound("public_channel_not_listed")
                } else {
                    publicPreview(link, reason = "public_channel_listed")
                }
            }
        }
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
            // A thread-only link looked its parent up: take the full id for a short one (slock #15521).
            val localThreadParentId =
                if (thread != null) {
                    thread.threadParentMessageId?.value
                } else {
                    link.threadParentMessageId?.let { parent ->
                        context.targetMessageId?.value?.takeIf { raftMessageIdMatches(parent, it) } ?: parent
                    }
                }
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

    /**
     * Resolves a task-conversation link (`/s/<slug>/<channel|dm>/<parentId>?[msg=<mid>&]task=<parentId>:task:<taskId>`,
     * botiverse/slock #16576):
     * - validates the parent channel and its route kind (`/dm/` vs `/channel/`);
     * - reads `GET /api/tasks/channel/:parentId` to locate the task without creating a conversation;
     * - for ordinary channels, DMs, and Agent Lounges, uses `task.conversationChannelId` directly;
     * - for joint channels, resolves this server's local conversation projection via idempotent
     *   `POST /api/tasks/:taskId/conversation` (never leaking a canonical joint storage ID);
     * - for tasks with no conversation yet (`conversationChannelId == null`), opens
     *   [RaftMessageLinkResolution.OpenPendingTask] (without POSTing) when no `msg=` is requested;
     * - when `msg=` is present, probes `GET /api/messages/context/:mid?channelId=<localTaskConvId>`
     *   to confirm visibility, expand short IDs, honour history paywalls, and feed [contextPageSink].
     */
    private suspend fun resolveTaskConversationLink(
        link: RaftMessageLink,
        server: StoredAuthServer,
    ): RaftMessageLinkResolution {
        val serverId = ServerId(server.id)
        val taskId = link.taskId ?: return notFound("task_id_missing")
        val (channelResponse, tasksResponse) =
            coroutineScope {
                val channel = async { request(serverId, path = "channels/${link.channelId}") }
                val tasks = async { request(serverId, path = "tasks/channel/${link.channelId}") }
                channel.await() to tasks.await()
            }
        val channelProbe =
            when (channelResponse.linkOutcome()) {
                RaftLinkProbeOutcome.Found -> {
                    val probe = parseRaftMessageLinkChannelProbe(channelResponse?.bodyText)
                    if (probe != null) {
                        if (!probe.id.equals(link.channelId, ignoreCase = true)) {
                            return notFound("channel_identity_mismatch")
                        }
                        val validParent =
                            if (link.routeKind == ConversationKind.DirectMessage) {
                                probe.type == "dm"
                            } else {
                                probe.type !in setOf("dm", "thread", RaftTaskConversationChannelKind)
                            }
                        if (!validParent) {
                            return notFound("route_kind_mismatch")
                        }
                    }
                    probe
                }
                RaftLinkProbeOutcome.NotFound -> return notFound("channel_${channelResponse?.code}")
                RaftLinkProbeOutcome.Unavailable -> null
            }
        val task =
            when (tasksResponse.linkOutcome()) {
                RaftLinkProbeOutcome.Found -> {
                    val payload =
                        parseSharedServerTasksPayload(
                            body = tasksResponse?.bodyText.orEmpty(),
                            diagnostics = diagnosticsLogger,
                        )
                            ?: return openPendingTask(
                                serverId = server.id,
                                taskId = taskId,
                                channelId = ChannelId(link.channelId),
                                task = null,
                                focusedMessageId = link.messageId,
                            )
                    payload.tasks.firstOrNull { it.id.value.equals(taskId, ignoreCase = true) }
                        ?: return notFound("task_not_found")
                }
                RaftLinkProbeOutcome.NotFound -> return notFound("task_channel_${tasksResponse?.code}")
                RaftLinkProbeOutcome.Unavailable ->
                    return openPendingTask(
                        serverId = server.id,
                        taskId = taskId,
                        channelId = ChannelId(link.channelId),
                        task = null,
                        focusedMessageId = link.messageId,
                    )
            }
        if (task.isLegacy) return notFound("task_legacy_no_conversation")
        val isJoint =
            parseConversationKindOrNull(task.channelType) == ConversationKind.Joint ||
                channelProbe?.type == "joint"
        val canonicalConversationId = task.conversationChannelId?.value?.takeIf(String::isNotBlank)
        if (canonicalConversationId == null) {
            if (link.messageId != null) return notFound("task_conversation_missing")
            return openPendingTask(
                serverId = server.id,
                taskId = task.id.value,
                channelId = task.channelId ?: ChannelId(link.channelId),
                task = task,
                focusedMessageId = null,
            )
        }
        val localConversationId =
            if (!isJoint) {
                canonicalConversationId
            } else {
                val openResponse = request(serverId, path = "tasks/${task.id.value}/conversation", method = "POST")
                when (openResponse.linkOutcome()) {
                    RaftLinkProbeOutcome.Found ->
                        when (val opened = openResponse?.toTaskConversationOpenResult(diagnosticsLogger)) {
                            is DataResult.Content -> opened.data.conversationChannelId.value
                            else ->
                                return openPendingTask(
                                    serverId = server.id,
                                    taskId = task.id.value,
                                    channelId = task.channelId ?: ChannelId(link.channelId),
                                    task = task,
                                    focusedMessageId = link.messageId,
                                )
                        }
                    RaftLinkProbeOutcome.NotFound ->
                        if (
                            openResponse?.code == 403 &&
                            openResponse.parseHttpErrorEnvelope()?.serverCode
                                .equals(JointTaskReadOnlyErrorCode, ignoreCase = true)
                        ) {
                            return openPendingTask(
                                serverId = server.id,
                                taskId = task.id.value,
                                channelId = task.channelId ?: ChannelId(link.channelId),
                                task = task,
                                focusedMessageId = null,
                                discussionElsewhere = true,
                            )
                        } else {
                            return notFound("task_conversation_${openResponse?.code}")
                        }
                    RaftLinkProbeOutcome.Unavailable ->
                        if (openResponse?.code == 409) {
                            return notFound("task_conversation_409")
                        } else {
                            return openPendingTask(
                                serverId = server.id,
                                taskId = task.id.value,
                                channelId = task.channelId ?: ChannelId(link.channelId),
                                task = task,
                                focusedMessageId = link.messageId,
                            )
                        }
                }
            }
        val taskConversationLink =
            link.copy(
                channelId = localConversationId,
                threadParentMessageId = null,
                taskId = task.id.value,
            )
        val targetMessageId = link.messageId
            ?: return open(
                serverId = server.id,
                link = taskConversationLink,
                conversationKind = ConversationKind.Task,
                threadChannelId = null,
            )
        val pendingContextPage = contextPageSink?.beginRequest(server.id)
        val contextResponse =
            request(
                serverId = serverId,
                path = "messages/context/$targetMessageId",
                query = listOf("channelId" to localConversationId, "before" to "0", "after" to "0"),
            )
        val resolution =
            when (contextResponse.linkOutcome()) {
                RaftLinkProbeOutcome.Found -> {
                    val context = parseSharedMessageContext(contextResponse?.bodyText.orEmpty(), ThreadId(localConversationId))
                    val lookedUp =
                        context.targetMessageId?.value?.takeIf { raftMessageIdMatches(targetMessageId, it) }
                            ?: targetMessageId
                    open(
                        serverId = server.id,
                        link = taskConversationLink.copy(messageId = lookedUp),
                        conversationKind = ConversationKind.Task,
                        threadChannelId = null,
                    )
                }
                RaftLinkProbeOutcome.NotFound ->
                    if (
                        contextResponse?.code == 404 &&
                        isRaftMessageContextHistoryLimited(contextResponse.bodyText ?: contextResponse.errorBodyText)
                    ) {
                        open(
                            serverId = server.id,
                            link = taskConversationLink,
                            conversationKind = ConversationKind.Task,
                            threadChannelId = null,
                        )
                    } else {
                        notFound("message_${contextResponse?.code}")
                    }
                RaftLinkProbeOutcome.Unavailable ->
                    open(
                        serverId = server.id,
                        link = taskConversationLink,
                        conversationKind = ConversationKind.Task,
                        threadChannelId = null,
                    )
            }
        if (resolution is RaftMessageLinkResolution.Open && contextResponse.linkOutcome() == RaftLinkProbeOutcome.Found) {
            contextResponse?.bodyText?.let { body ->
                pendingContextPage?.offer(body, channelId = localConversationId, channelType = RaftTaskConversationChannelKind)
            }
        }
        return resolution
    }

    /**
     * [serverId] null: no server binding (the anonymous public routes).
     *
     * Warm App Links accepted from another app can launch these idempotent GET probes right as
     * Android transitions the app from background to foreground (`onActivityStarted()`), which
     * retires in-flight Read requests with `network_request_lifecycle_retired` (#proj-mobile:#311).
     * Retry once on that exact visibility-edge retirement so thread/task/short-id probes still run.
     */
    private suspend fun request(
        serverId: ServerId?,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        method: String = "GET",
    ): HomeNetworkResponse? {
        repeat(2) { attempt ->
            val response =
                try {
                    networkClient.request(
                        priority = DataRequestPriority.Foreground,
                        path = path,
                        method = method,
                        query = query,
                        serverIdOverride = serverId,
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    diagnosticsLogger("message_link_probe_failed path=${path.substringBefore('/')} error=${error::class.simpleName.orEmpty()}")
                    return null
                }
            if (attempt == 0 && response.isLifecycleRetired()) {
                diagnosticsLogger("message_link_probe_retry reason=lifecycle_retired path=${path.substringBefore('/')}")
                return@repeat
            }
            return response
        }
        return null
    }

    private fun HomeNetworkResponse.isLifecycleRetired(): Boolean =
        code == 0 && errorBodyText == RAFT_LIFECYCLE_RETIRED_ERROR_BODY

    private fun open(
        serverId: String,
        link: RaftMessageLink,
        conversationKind: ConversationKind,
        threadChannelId: String?,
        parentConversationKind: ConversationKind? = conversationKind,
    ): RaftMessageLinkResolution.Open {
        diagnosticsLogger(
            "message_link_resolved result=open kind=${conversationKind.name} thread=${link.isThread} " +
                "threadChannelKnown=${threadChannelId != null}"
        )
        return RaftMessageLinkResolution.Open(
            serverId = serverId,
            sourceIdentity =
                link.toConversationOpenSourceMessageIdentity(
                    conversationKind = conversationKind,
                    threadChannelId = threadChannelId,
                    parentConversationKind = parentConversationKind,
                ),
        )
    }

    private fun openPendingTask(
        serverId: String,
        taskId: String,
        channelId: ChannelId,
        task: TaskSummary?,
        focusedMessageId: String?,
        discussionElsewhere: Boolean = false,
    ): RaftMessageLinkResolution.OpenPendingTask {
        diagnosticsLogger(
            "message_link_resolved result=open_pending_task seeded=${task != null} " +
                "msg=${focusedMessageId != null} elsewhere=$discussionElsewhere"
        )
        return RaftMessageLinkResolution.OpenPendingTask(
            serverId = serverId,
            taskId = taskId,
            channelId = channelId,
            task = task,
            focusedMessageId = focusedMessageId,
            discussionElsewhere = discussionElsewhere,
        )
    }

    private fun notFound(reason: String): RaftMessageLinkResolution.NotFound {
        diagnosticsLogger("message_link_resolved result=not_found reason=$reason")
        return RaftMessageLinkResolution.NotFound(reason)
    }

    private fun publicNotFound(reason: String): RaftMessageLinkResolution.PublicNotFound {
        diagnosticsLogger("message_link_resolved result=not_found reason=$reason")
        return RaftMessageLinkResolution.PublicNotFound(reason)
    }

    private fun publicPreview(link: RaftMessageLink, reason: String): RaftMessageLinkResolution.PublicPreview {
        diagnosticsLogger("message_link_resolved result=public_preview reason=$reason")
        return RaftMessageLinkResolution.PublicPreview(link)
    }
}

private const val RAFT_LIFECYCLE_RETIRED_ERROR_BODY = "network_request_lifecycle_retired"

private data class ThreadChannelRedirect(
    val threadChannelId: String,
    val parentChannelId: String,
    val parentMessageId: String,
    val parentConversationKind: ConversationKind?,
)

private fun String.toParentConversationKind(): ConversationKind? =
    when (this) {
        "dm" -> ConversationKind.DirectMessage
        "joint" -> ConversationKind.Joint
        "thread", RaftTaskConversationChannelKind -> null
        else -> ConversationKind.Channel
    }

private fun RaftMessageLink.redirectedToThread(
    redirect: ThreadChannelRedirect,
    messageId: String?,
): RaftMessageLink =
    copy(
        channelId = redirect.parentChannelId,
        messageId = messageId,
        threadParentMessageId = redirect.parentMessageId,
    )

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
 * other top-level conversation (a joint channel opens as one; a thread channel id is redirected
 * above via `parentChannelId` / `parentMessageId` per slock #15879). Null when the link names the
 * wrong kind. A task's own conversation (`type = 'task'`; a task message's Copy link, the app's
 * and Saved's) opens as the task, never as a bare channel page with a Join the server refuses
 * (slock #14960 `useTaskConversationRedirect`).
 */
private fun ConversationKind.confirmedBy(type: String): ConversationKind? =
    when (this) {
        ConversationKind.DirectMessage -> ConversationKind.DirectMessage.takeIf { type == "dm" }
        else ->
            when (type) {
                "dm", "thread" -> null
                "joint" -> ConversationKind.Joint
                RaftTaskConversationChannelKind -> ConversationKind.Task
                else -> ConversationKind.Channel
            }
    }

/** The identity-only open source of a resolved link (no titles, previews or names). */
internal fun RaftMessageLink.toConversationOpenSourceMessageIdentity(
    conversationKind: ConversationKind = routeKind,
    threadChannelId: String? = null,
    parentConversationKind: ConversationKind? = conversationKind,
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
            parentConversationKind = parentConversationKind,
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

/** An 8-hex short message id: the first eight hex digits of the message's UUID (slock `threadLinkIds`). */
private val RaftShortMessageId = Regex("^[0-9a-fA-F]{8}$")

/**
 * Whether [linkId], as a link wrote it, names [fullId]: the same id, or its 8-hex short form
 * (slock #15521). Null [linkId] names nothing.
 */
internal fun raftMessageIdMatches(linkId: String?, fullId: String): Boolean =
    linkId != null &&
        (linkId.equals(fullId, ignoreCase = true) ||
            (RaftShortMessageId.matches(linkId) && fullId.length > linkId.length && fullId.startsWith(linkId, ignoreCase = true)))
