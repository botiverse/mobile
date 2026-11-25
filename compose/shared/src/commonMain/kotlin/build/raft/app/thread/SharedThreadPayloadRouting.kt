package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.RaftAgentLounges
import build.raft.app.data.agentLoungeConversationRow
import build.raft.app.data.toConversationSummary
import build.raft.app.data.ThreadActionResult
import build.raft.app.data.ThreadSnapshot
import build.raft.app.data.map
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.markdown.formatRaftDirectMessagePeerRef
import build.raft.app.markdown.parseRaftDirectMessagePeerRef
import build.raft.app.markdown.RaftLoungeRefAgent
import build.raft.app.markdown.raftLoungeConversationAgentName
import build.raft.app.markdown.resolveRaftLoungeRefAgent
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MessageId
import build.raft.app.model.ThreadId
import build.raft.app.model.messageStreamChannelId
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

internal data class MarkdownThreadRefTarget(
    val parentMessageId: String,
    val focusedMessageId: String? = null,
    val threadChannelId: String? = null
)

internal fun RaftThreadScreenPayload.asRefreshFailed(
    code: String,
    traceId: String? = null,
): RaftThreadScreenPayload =
    // artin 10-26: a failed load brings no full message, so a cut Activity seed stops loading.
    withActivityPreviewsSettled().asRefreshFailedSettled(code, traceId)

private fun RaftThreadScreenPayload.asRefreshFailedSettled(
    code: String,
    traceId: String? = null,
): RaftThreadScreenPayload {
    val cleanTraceId = traceId?.takeIf { it.isNotBlank() }
    return if (hasRenderableThreadContent()) {
        copy(status = "content", backgroundRefreshing = false, errorCode = code, errorServerMessage = null, errorTraceId = cleanTraceId)
    } else if (isDurableEmptyStandIn()) {
        // Task #1137: an empty last-known channel survives a transient failure like a non-empty one does.
        copy(backgroundRefreshing = false, errorCode = code, errorServerMessage = null, errorTraceId = cleanTraceId)
    } else {
        copy(status = "error", backgroundRefreshing = false, errorCode = code, errorServerMessage = null, errorTraceId = cleanTraceId)
    }
}

internal fun RaftThreadScreenPayload.asRefreshNoPermission(
    base: RaftThreadScreenPayload,
    code: String,
    serverMessage: String? = null,
    traceId: String? = null,
): RaftThreadScreenPayload =
    withRouteContext(base)
        .withoutThreadTimelineContent()
        .copy(
            status = "no_permission",
            backgroundRefreshing = false,
            errorCode = code,
            errorServerMessage = serverMessage,
            errorTraceId = traceId?.takeIf { it.isNotBlank() },
            focusedMessageId = base.focusedMessageId,
            focusedMessageRequestNonce = base.focusedMessageRequestNonce
        )

internal fun RaftThreadScreenPayload.asRefreshEmpty(base: RaftThreadScreenPayload): RaftThreadScreenPayload =
    withRouteContext(base).copy(
        status = "empty",
        backgroundRefreshing = false,
        errorCode = null,
        errorServerMessage = null,
        errorTraceId = null,
        messages = emptyList(),
        canLoadMoreBefore = false,
        canLoadMoreAfter = false,
        olderMessagesStatus = "idle",
        newerMessagesStatus = "idle",
        emptyTitle = ThreadEmptyConversationTitle,
        emptyDetail = ThreadEmptyConversationDetail
    )

internal fun RaftThreadScreenPayload.asLoadedEmptyReplyThreadPayload(): RaftThreadScreenPayload =
    if (isLoadedEmptyReplyThread()) {
        copy(
            status = "empty",
            messages = emptyList(),
            canLoadMoreBefore = false,
            canLoadMoreAfter = false,
            olderMessagesStatus = "idle",
            newerMessagesStatus = "idle",
            errorCode = null,
            errorTraceId = null,
            emptyTitle = ThreadReplyEmptyTitle,
            emptyDetail = ThreadReplyEmptyDetail
        )
    } else {
        this
    }

internal fun RaftThreadScreenPayload.isLoadedEmptyReplyThread(): Boolean =
    surfaceKind == RaftThreadSurfaceKind.ReplyThread &&
        messages.isEmpty() &&
        originStatus != "none" &&
        originStatus != "loading"

internal fun DataResult<*>.threadDiagnosticsResult(): String =
    when (this) {
        is DataResult.Content -> "content"
        is DataResult.Empty -> "empty:${reason.name}"
        is DataResult.Error -> "error:${error.code}"
        is DataResult.NoPermission -> "no_permission:${blocker.code}"
    }

internal fun SharedThreadOriginResolution.focusedOriginDiagnosticsResult(): String =
    when (this) {
        is SharedThreadOriginResolution.Loaded -> "loaded"
        SharedThreadOriginResolution.Missing -> "missing"
        is SharedThreadOriginResolution.Error -> "error:$code"
    }

internal fun RaftThreadScreenPayload.acceptsFocusedOriginHydrationCompletion(
    parentChannelId: String,
    parentMessageId: String,
    originStatusAtStart: String,
    originMessageAtStart: RaftThreadMessageItem?
): Boolean =
    this.parentChannelId == parentChannelId &&
        (this.parentMessageId?.takeIf { it.isNotBlank() }
            ?: originParentMessageId.orEmpty()) == parentMessageId &&
        originStatus == originStatusAtStart &&
        originMessage == originMessageAtStart

internal fun RaftThreadScreenPayload.originDiagnostics(): String =
    "surface=$surfaceKind parentChannelId=${parentChannelId.orEmpty()} " +
        "parentMessageId=${parentMessageId.orEmpty()} originParentMessageId=${originParentMessageId.orEmpty()} " +
        "originStatus=$originStatus hasOriginMessage=${originMessage != null} " +
        "hasOriginPreview=${!originPreview.isNullOrBlank()}"

// task #326: the launch placeholder payload seeds emptyTitle with this literal; a resolved
// (non-loading) merge must not carry it forward into an empty/content frame, or the empty
// state shows "Loading..." forever.
internal const val ThreadRouteLoadingPlaceholderTitle = "Loading..."
internal const val ThreadEmptyConversationTitle = "No messages yet"
internal const val ThreadEmptyConversationDetail = "This conversation has no messages yet."

internal fun RaftThreadScreenPayload.mergedEmptyTitleWith(base: RaftThreadScreenPayload): String =
    if (
        base.emptyTitle == ThreadRouteLoadingPlaceholderTitle &&
            status in setOf("empty", "content")
    ) {
        emptyTitle
    } else {
        base.emptyTitle
    }

internal fun RaftThreadScreenPayload.mergedEmptyDetailWith(base: RaftThreadScreenPayload): String =
    if (
        base.emptyTitle == ThreadRouteLoadingPlaceholderTitle &&
            status in setOf("empty", "content")
    ) {
        emptyDetail
    } else {
        base.emptyDetail
    }

/**
 * Web `isFinalNotFound` without `history_limited` (botiverse/slock #16398, bug #555): a focused
 * message's context load returned a final not-found (`404` / `message_not_found`, not a transient
 * network error and not a plan history-window refusal). The thread falls back quietly to its latest
 * messages / replies and clears the stale focus target.
 */
internal fun isFinalFocusedMessageNotFoundError(error: build.raft.app.data.ScreenError): Boolean {
    if (error.historyLimitedTarget != null) return false
    val serverCode = error.serverCode?.trim()?.lowercase()
    if (serverCode == "history_limited") return false
    val code = error.code.trim().lowercase()
    return code == "http_404" ||
        code.endsWith("_http_404") ||
        code == "message_not_found" ||
        code == "message_id_invalid" ||
        code == "channel_id_invalid" ||
        serverCode == "message_not_found" ||
        serverCode == "message_id_invalid" ||
        serverCode == "channel_id_invalid"
}

internal fun RaftThreadScreenPayload.withRouteContext(base: RaftThreadScreenPayload): RaftThreadScreenPayload =
    (if (hasResolvedReplyThreadStreamFrom(base)) {
        copy(
            navigationLabel = base.navigationLabel,
            selectedTab = base.selectedTab,
            showModeTabs = base.showModeTabs,
            focusedMessageId = if (focusedTargetMissing) null else base.focusedMessageId,
            focusedMessageRequestNonce = if (focusedTargetMissing) 0 else base.focusedMessageRequestNonce,
            alignOwnMessagesRight = base.alignOwnMessagesRight,
            compactConsecutiveMessages = base.compactConsecutiveMessages,
            localOffsetMinutes = base.localOffsetMinutes,
            todayEpochDay = base.todayEpochDay ?: todayEpochDay,
            nowEpochMillis = base.nowEpochMillis ?: nowEpochMillis,
            timeFormat = base.timeFormat,
            knownMarkdownChannelNames =
                base.knownMarkdownChannelNames.ifEmpty { knownMarkdownChannelNames },
            knownMarkdownDirectMessagePeerNames =
                base.knownMarkdownDirectMessagePeerNames.ifEmpty { knownMarkdownDirectMessagePeerNames },
            emptyTitle = if (surfaceKind == RaftThreadSurfaceKind.ReplyThread) emptyTitle else mergedEmptyTitleWith(base),
            emptyDetail = if (surfaceKind == RaftThreadSurfaceKind.ReplyThread) emptyDetail else mergedEmptyDetailWith(base)
        )
    } else {
        copy(
            instanceId = base.instanceId.ifBlank { instanceId },
            sourceThreadId = base.sourceThreadId ?: sourceThreadId,
            navigationLabel = base.navigationLabel,
            selectedTab = base.selectedTab,
            showModeTabs = base.showModeTabs,
            focusedMessageId = if (focusedTargetMissing) null else base.focusedMessageId,
            focusedMessageRequestNonce = if (focusedTargetMissing) 0 else base.focusedMessageRequestNonce,
            alignOwnMessagesRight = base.alignOwnMessagesRight,
            compactConsecutiveMessages = base.compactConsecutiveMessages,
            localOffsetMinutes = base.localOffsetMinutes,
            todayEpochDay = base.todayEpochDay ?: todayEpochDay,
            nowEpochMillis = base.nowEpochMillis ?: nowEpochMillis,
            timeFormat = base.timeFormat,
            knownMarkdownChannelNames =
                base.knownMarkdownChannelNames.ifEmpty { knownMarkdownChannelNames },
            knownMarkdownDirectMessagePeerNames =
                base.knownMarkdownDirectMessagePeerNames.ifEmpty { knownMarkdownDirectMessagePeerNames },
            composer = base.composer.takeUnless { it.isDefaultThreadComposer() } ?: composer,
            // artin #111: a payload warmed without a route (Activity prewarm) takes the route's DM peer.
            dmPeerIdentity =
                dmPeerIdentity
                    ?: base.dmPeerIdentity.takeIf { surfaceKind == RaftThreadSurfaceKind.DirectMessage },
            emptyTitle = mergedEmptyTitleWith(base),
            emptyDetail = mergedEmptyDetailWith(base)
        )
    }).withReplyParentRouteIdentityFrom(base)

private fun RaftThreadScreenPayload.withReplyParentRouteIdentityFrom(
    routePayload: RaftThreadScreenPayload
): RaftThreadScreenPayload {
    if (
        surfaceKind != RaftThreadSurfaceKind.ReplyThread ||
        routePayload.surfaceKind != RaftThreadSurfaceKind.ReplyThread
    ) {
        return this
    }
    val routeParentChannelId = routePayload.parentChannelId?.takeIf { it.isNotBlank() } ?: return this
    val routeParentMessageId =
        routePayload.parentMessageId?.takeIf { it.isNotBlank() }
            ?: routePayload.originParentMessageId?.takeIf { it.isNotBlank() }
            ?: return this
    val currentParentChannelId = parentChannelId?.takeIf { it.isNotBlank() }
    val currentParentMessageId =
        parentMessageId?.takeIf { it.isNotBlank() }
            ?: originParentMessageId?.takeIf { it.isNotBlank() }
    if (
        (currentParentChannelId != null && currentParentChannelId != routeParentChannelId) ||
        (currentParentMessageId != null && currentParentMessageId != routeParentMessageId)
    ) {
        return this
    }
    return copy(
        parentChannelId = routeParentChannelId,
        parentMessageId = routeParentMessageId,
        originParentMessageId = originParentMessageId?.takeIf { it.isNotBlank() } ?: routeParentMessageId,
        parentSurfaceKind =
            routePayload.parentSurfaceKind?.takeIf { it.isNotBlank() }
                ?: parentSurfaceKind,
        parentChannelKind =
            routePayload.parentChannelKind?.takeIf { it.isNotBlank() }
                ?: parentChannelKind
    )
}

internal fun RaftThreadScreenPayload.withStoreResolvedReplyOriginFrom(
    storePayload: RaftThreadScreenPayload
): RaftThreadScreenPayload {
    if (
        surfaceKind != RaftThreadSurfaceKind.ReplyThread ||
        storePayload.surfaceKind != RaftThreadSurfaceKind.ReplyThread ||
        originMessage != null ||
        storePayload.originStatus != "loaded"
    ) {
        return this
    }
    val storeOrigin = storePayload.originMessage ?: return this
    val currentParentChannelId = parentChannelId?.takeIf { it.isNotBlank() } ?: return this
    val storeParentChannelId = storePayload.parentChannelId?.takeIf { it.isNotBlank() } ?: return this
    val currentParentMessageId =
        parentMessageId?.takeIf { it.isNotBlank() }
            ?: originParentMessageId?.takeIf { it.isNotBlank() }
            ?: return this
    val storeParentMessageId =
        storePayload.parentMessageId?.takeIf { it.isNotBlank() }
            ?: storePayload.originParentMessageId?.takeIf { it.isNotBlank() }
            ?: return this
    if (
        currentParentChannelId != storeParentChannelId ||
        currentParentMessageId != storeParentMessageId ||
        storeOrigin.id != storeParentMessageId
    ) {
        return this
    }
    return copy(
        originMessage = storeOrigin,
        originPreview = storePayload.originPreview,
        originParentMessageId = storeParentMessageId,
        originStatus = "loaded",
        originErrorCode = null
    )
}

internal fun RaftThreadScreenPayload.withRouteIntentFrom(routePayload: RaftThreadScreenPayload): RaftThreadScreenPayload {
    val resolvedIdentityPayload =
        if (routePayload.resolveThreadRouteIdentity().canRenderTimelineCandidates) {
            withResolvedReplyThreadRouteIntentFrom(routePayload)
        } else {
            this
        }
    return resolvedIdentityPayload.copy(
        navigationLabel = routePayload.navigationLabel,
        selectedTab = routePayload.selectedTab,
        showModeTabs = routePayload.showModeTabs,
        focusedMessageId = routePayload.focusedMessageId,
        focusedMessageRequestNonce = routePayload.focusedMessageRequestNonce,
        alignOwnMessagesRight = routePayload.alignOwnMessagesRight,
        compactConsecutiveMessages = routePayload.compactConsecutiveMessages,
        localOffsetMinutes = routePayload.localOffsetMinutes,
        todayEpochDay = routePayload.todayEpochDay ?: todayEpochDay,
        nowEpochMillis = routePayload.nowEpochMillis ?: nowEpochMillis,
        timeFormat = routePayload.timeFormat,
        knownMarkdownChannelNames =
            routePayload.knownMarkdownChannelNames.ifEmpty { knownMarkdownChannelNames },
        knownMarkdownDirectMessagePeerNames =
            routePayload.knownMarkdownDirectMessagePeerNames.ifEmpty { knownMarkdownDirectMessagePeerNames },
        composer =
            composer.copy(
                syncNonce = maxOf(composer.syncNonce, routePayload.composer.syncNonce),
                focusRequestNonce = maxOf(composer.focusRequestNonce, routePayload.composer.focusRequestNonce),
                focusRequestMeta =
                    if (routePayload.composer.focusRequestNonce > composer.focusRequestNonce) {
                        routePayload.composer.focusRequestMeta
                    } else {
                        composer.focusRequestMeta
                    },
            )
    )
}

internal fun RaftThreadScreenPayload.withHomeContextProjectionFrom(
    patch: RaftThreadScreenPayload
): RaftThreadScreenPayload {
    val knownNames =
        patch.knownMarkdownChannelNames.takeIf { it.isNotEmpty() && it != knownMarkdownChannelNames }
            ?: knownMarkdownChannelNames
    // Access is resolved exactly once by launchInitialAccessResolution and is
    // emitted atomically with ThreadAccessResolution.Resolved. Background Home
    // enrichment must never flip an already-mounted Composer into Join later.
    return copy(
        knownMarkdownChannelNames = knownNames,
        knownMarkdownDirectMessagePeerNames =
            patch.knownMarkdownDirectMessagePeerNames.ifEmpty { knownMarkdownDirectMessagePeerNames }
    )
}

private fun RaftThreadScreenPayload.withResolvedReplyThreadRouteIntentFrom(
    routePayload: RaftThreadScreenPayload
): RaftThreadScreenPayload =
    if (routePayload.hasResolvedReplyThreadStreamFrom(this)) {
        copy(
            sourceThreadId = routePayload.sourceThreadId,
            streamChannelId = routePayload.streamChannelId,
            parentChannelId = routePayload.parentChannelId,
            parentMessageId = routePayload.parentMessageId,
            composer =
                composer.copy(
                    streamChannelId =
                        routePayload.composer.streamChannelId?.takeIf { it.isNotBlank() }
                            ?: routePayload.streamChannelId?.takeIf { it.isNotBlank() }
                            ?: routePayload.sourceThreadId?.takeIf { it.isNotBlank() }
                )
        )
    } else {
        this
    }

internal fun buildMarkdownChannelLaunchPayload(
    currentPayload: RaftThreadScreenPayload,
    home: HomeSnapshot,
    channelLabel: String,
    requiredKind: ConversationKind? = null,
    knownMemberProfilesById: Map<String, MemberProfile> = emptyMap(),
    anchorMessageId: String? = null
): RaftThreadScreenPayload? {
    val channel =
        resolveMarkdownChannelConversation(
            home = home,
            channelLabel = channelLabel,
            requiredKind = requiredKind,
            knownMemberProfilesById = knownMemberProfilesById,
            currentPayload = currentPayload
        ) ?: return null
    val conversation = channel.toThreadLaunchConversationIdentity()
    val streamChannelId = conversation.threadStreamChannelId() ?: return null
    return buildStandaloneConversationThreadLaunchPayload(
        conversation = conversation,
        timeContext = currentPayload.toRaftTimeContext(),
        anchorMessageId = anchorMessageId,
        alignOwnMessagesRight = currentPayload.alignOwnMessagesRight,
        compactConsecutiveMessages = currentPayload.compactConsecutiveMessages,
        instanceIdOverride = "shared-shell-thread-$streamChannelId"
    )?.toInitialPayloadOrNull("shared-shell-thread-$streamChannelId")
}

internal fun buildMarkdownThreadRefLaunchPayload(
    currentPayload: RaftThreadScreenPayload,
    home: HomeSnapshot,
    channelLabel: String,
    threadShortId: String,
    parentMessageId: String,
    focusedMessageId: String? = null,
    threadChannelId: String? = null,
    requiredParentKind: ConversationKind? = null,
    knownMemberProfilesById: Map<String, MemberProfile> = emptyMap()
): RaftThreadScreenPayload? {
    val channel =
        resolveMarkdownChannelConversation(
            home = home,
            channelLabel = channelLabel,
            requiredKind = requiredParentKind,
            knownMemberProfilesById = knownMemberProfilesById,
            currentPayload = currentPayload,
            threadShortId = threadShortId
        ) ?: return null
    val routeIdentity = channel.resolveThreadRouteIdentity()
    val parentChannelId =
        routeIdentity.timelineChannelId
            ?: channel.messageStreamChannelId().value.takeIf { it.isNotBlank() }
            ?: return null
    val cleanParentMessageId = parentMessageId.takeIf { it.isNotBlank() } ?: return null
    val cleanThreadChannelId = threadChannelId?.takeIf { it.isNotBlank() }
    val instanceId =
        cleanThreadChannelId?.let { "shared-shell-thread-$it" }
            ?: "shared-shell-thread-$parentChannelId-$threadShortId"
    val conversation =
        buildThreadLaunchConversationIdentity(
            kind = ConversationKind.Thread,
            channelId = cleanThreadChannelId?.let(::ChannelId) ?: ChannelId(parentChannelId),
            threadChannelId = cleanThreadChannelId?.let(::ChannelId),
            parentChannelId = ChannelId(parentChannelId),
            parentMessageId = MessageId(cleanParentMessageId),
            parentSurfaceKind = channel.kind.threadParentSurfaceKindOrNull(),
            parentChannelKind = channel.kind.threadChannelKindWireValue()
        )
    return buildStandaloneConversationThreadLaunchPayload(
        conversation = conversation,
        timeContext = currentPayload.toRaftTimeContext(),
        anchorMessageId = focusedMessageId?.takeIf { it.isNotBlank() },
        alignOwnMessagesRight = currentPayload.alignOwnMessagesRight,
        compactConsecutiveMessages = currentPayload.compactConsecutiveMessages,
        instanceIdOverride = instanceId
    )?.toInitialPayloadOrNull(instanceId)
}

/** The channel id tasks of [this] conversation carry (`TaskSummary.channelId`). */
internal fun ConversationSummary.markdownTaskRefChannelId(): ChannelId? =
    resolveThreadRouteIdentity().timelineChannelId?.takeIf { it.isNotBlank() }?.let(::ChannelId)
        ?: messageStreamChannelId().takeIf { it.value.isNotBlank() }

internal fun resolveMarkdownChannelConversation(
    home: HomeSnapshot,
    channelLabel: String,
    requiredKind: ConversationKind? = null,
    knownMemberProfilesById: Map<String, MemberProfile> = emptyMap(),
    currentPayload: RaftThreadScreenPayload? = null,
    threadShortId: String? = null
): ConversationSummary? {
    // `lounge:@agent` (slock #16279): the Lounge of the agent currently called so. A Lounge is a
    // channel, never a DM.
    raftLoungeConversationAgentName(channelLabel)?.let { agentName ->
        if (requiredKind != null) return null
        return resolveMarkdownLoungeConversation(home, agentName, knownMemberProfilesById, currentPayload)
    }
    val rawKey = channelLabel.markdownChannelLookupKey()
    if (rawKey.isBlank()) return null
    // DM refs may pin the peer kind (`dm:@skyzh~agent`). A bare name that a
    // human and an agent share opens the first matching DM, like Web
    // `findDmByPeer` (see the end of this function).
    val directMessagePeerRef =
        if (requiredKind == ConversationKind.DirectMessage) {
            parseRaftDirectMessagePeerRef(rawKey) ?: return null
        } else {
            null
        }
    val key = directMessagePeerRef?.peerName ?: rawKey
    val requiredPeerKind = directMessagePeerRef?.peerKind
    fun ConversationSummary.matchesRequiredPeerKind(): Boolean =
        requiredPeerKind == null || directMessagePeerType.parsedMemberKindOrNull() == requiredPeerKind

    if (requiredKind == ConversationKind.DirectMessage &&
        home.currentMember.matchesMarkdownChannelLabel(key) &&
        (requiredPeerKind == null || home.currentMember.kind == requiredPeerKind)
    ) {
        val currentDmChannelId = currentPayload?.let { payload ->
            when {
                payload.parentSurfaceKind == RaftThreadSurfaceKind.DirectMessage ||
                    payload.parentChannelKind == "dm" ||
                    payload.resolveThreadRouteIdentity().parentConversationKind == ConversationKind.DirectMessage -> {
                    payload.parentChannelId?.takeIf { it.isNotBlank() }
                }
                payload.surfaceKind == RaftThreadSurfaceKind.DirectMessage ||
                    payload.channelKind == "dm" ||
                    payload.dmPeerIdentity != null -> {
                    payload.resolveThreadRouteIdentity().timelineChannelId
                        ?: payload.streamChannelId?.takeIf { it.isNotBlank() }
                        ?: payload.sourceThreadId?.takeIf { it.isNotBlank() }
                }
                else -> null
            }
        }

        if (!currentDmChannelId.isNullOrBlank()) {
            val existing =
                home.directMessages
                    .map { it.toConversationSummary() }
                    .firstOrNull {
                        it.messageStreamChannelId().value == currentDmChannelId ||
                            it.channelId?.value == currentDmChannelId ||
                            it.threadId.value == currentDmChannelId
                    }
            if (existing != null) {
                return existing
            }
            return ConversationSummary(
                threadId = ThreadId(currentDmChannelId),
                channelId = ChannelId(currentDmChannelId),
                kind = ConversationKind.DirectMessage,
                name = currentPayload?.dmPeerName ?: currentPayload?.title.orEmpty(),
                presenceMemberId = currentPayload?.dmPeerIdentity?.memberId
            )
        }

        if (currentPayload != null && !threadShortId.isNullOrBlank()) {
            val referencingMessage =
                currentPayload.messages.firstOrNull { it.body.contains(threadShortId) }
                    ?: currentPayload.originMessage?.takeIf { it.body.contains(threadShortId) }
            if (referencingMessage != null) {
                val authorId = referencingMessage.authorId.trim()
                val authorHandle = referencingMessage.authorHandle.trim()
                val authorName = referencingMessage.authorName.trim()
                val matchByAuthor =
                    home.directMessages
                        .map { it.toConversationSummary() }
                        .filter { dm -> dm.matchesRequiredPeerKind() }
                        .firstOrNull { dm ->
                            (authorId.isNotBlank() && dm.presenceMemberId?.value == authorId) ||
                                dm.markdownDirectMessagePeerAliases(knownMemberProfilesById).any { alias ->
                                    val aliasKey = alias.markdownChannelLookupKey().lowercase()
                                    (authorHandle.isNotBlank() && aliasKey == authorHandle.markdownChannelLookupKey().lowercase()) ||
                                        (authorName.isNotBlank() && aliasKey == authorName.markdownChannelLookupKey().lowercase())
                                }
                        }
                if (matchByAuthor != null) {
                    return matchByAuthor
                }
            }
        }
    }

    val matches =
        (if (requiredKind == ConversationKind.DirectMessage) home.directMessages else home.channels)
            .map { it.toConversationSummary() }
            .filter { conversation ->
                (requiredKind == null || conversation.kind == requiredKind) &&
                    if (requiredKind == ConversationKind.DirectMessage) {
                        conversation.matchesRequiredPeerKind() &&
                            conversation.markdownDirectMessagePeerAliases(knownMemberProfilesById)
                                .any { alias -> alias.markdownChannelLookupKey() == key }
                    } else {
                        conversation.name.markdownChannelLookupKey() == key
                    }
            }
            .distinctBy { it.messageStreamChannelId().value }
    matches.singleOrNull()?.let { return it }
    // Web `findDmByPeer` resolves a bare human/agent twin name to the first
    // matching DM in list order. Other alias collisions still fail closed.
    return if (directMessagePeerRef != null &&
        requiredPeerKind == null &&
        key in home.markdownDirectMessageTwinPeerKeys(knownMemberProfilesById)
    ) {
        matches.firstOrNull()
    } else {
        null
    }
}

/**
 * The Lounge `lounge:@<agentName>` names, as a conversation this client can open: the agent is the one
 * the page's directory names so ([toMarkdownLoungeRefAgents], else the server directory's agents),
 * by the server's rule ([resolveRaftLoungeRefAgent]); its Lounge is the one this client knows (opened,
 * loaded, or carried on the agent's DM row). Null when either is unknown: a ref's open makes the
 * Lounge known first (`prepareMarkdownRefLounge`).
 */
internal fun resolveMarkdownLoungeConversation(
    home: HomeSnapshot,
    agentName: String,
    knownMemberProfilesById: Map<String, MemberProfile> = emptyMap(),
    currentPayload: RaftThreadScreenPayload? = null
): ConversationSummary? {
    val serverId = home.server.id.value
    val agents =
        currentPayload?.toMarkdownLoungeRefAgents()?.takeIf { it.isNotEmpty() }
            ?: knownMemberProfilesById.values.markdownLoungeRefAgents(serverId)
    val agent = resolveRaftLoungeRefAgent(agentName, agents) ?: return null
    val channelId =
        RaftAgentLounges.loungeOfAgent(serverId, agent.id)?.channelId?.value
            ?: home.directMessages.firstOrNull { it.presenceMemberId?.value == agent.id }?.lounge?.channelId?.value
            ?: return null
    return home.agentLoungeConversationRow(channelId)?.toConversationSummary()
}

/** This server's live agents among [this] directory profiles, as a `lounge:@agent` ref may name them. */
private fun Iterable<MemberProfile>.markdownLoungeRefAgents(serverId: String): List<RaftLoungeRefAgent> =
    filter { profile ->
        profile.kind == MemberKind.Agent &&
            profile.agentDeletedAt.isNullOrBlank() &&
            (profile.inViewerServer ?: (profile.serverId?.value?.let { it == serverId } ?: true))
    }.mapNotNull { profile ->
        val name = profile.handle.trim().removePrefix("@").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        RaftLoungeRefAgent(id = profile.id.value, name = name, displayName = profile.displayName.trim().takeIf { it.isNotEmpty() })
    }.distinctBy { it.id }

internal fun MemberProfile.matchesMarkdownChannelLabel(label: String): Boolean {
    val key = label.markdownChannelLookupKey().lowercase()
    if (key.isBlank()) return false
    return listOfNotNull(handle, displayName, id.value).any { candidate ->
        candidate.markdownChannelLookupKey().lowercase() == key
    }
}

private fun ConversationSummary.markdownDirectMessagePeerAliases(
    knownMemberProfilesById: Map<String, MemberProfile>
): List<String> {
    val profile =
        presenceMemberId
            ?.value
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(knownMemberProfilesById::get)
    return listOfNotNull(name, profile?.handle, profile?.displayName)
        .mapNotNull { alias -> alias.trim().removePrefix("@").takeIf { it.isNotBlank() } }
        .distinctBy { it.lowercase() }
}

/**
 * DM peer names a markdown `dm:@peer[:short]` ref may resolve to. Each DM
 * contributes its bare aliases plus kind-pinned `alias~agent` / `alias~human`
 * forms. A bare name that a human and an agent share stays linkable and opens
 * the first matching DM (Web `findDmByPeer`); any other bare alias that maps
 * to more than one DM is left out so the ref stays plain text.
 */
/** The channel names a body's `#name` refs become chips for (web: a channel the reader cannot resolve stays text). */
internal fun HomeSnapshot.knownMarkdownChannelNames(): List<String> =
    channels
        .mapNotNull { conversation -> conversation.name.trim().takeIf { it.isNotBlank() } }
        .distinctBy { it.lowercase() }

internal fun HomeSnapshot.knownMarkdownDirectMessagePeerNames(
    knownMemberProfilesById: Map<String, MemberProfile>
): List<String> {
    val twinKeys = markdownDirectMessageTwinPeerKeys(knownMemberProfilesById)
    val aliasesByLookupKey = linkedMapOf<String, MutableList<Pair<String, String>>>()
    directMessages.forEach { row ->
        val conversation = row.toConversationSummary()
        val conversationId = conversation.messageStreamChannelId().value.trim()
        if (conversationId.isBlank()) return@forEach
        val peerKind = conversation.directMessagePeerType.parsedMemberKindOrNull()
        conversation.markdownDirectMessagePeerAliases(knownMemberProfilesById).forEach { alias ->
            val key = alias.markdownChannelLookupKey()
            if (key.isBlank()) return@forEach
            aliasesByLookupKey.getOrPut(key) { mutableListOf() } += conversationId to alias
            if (peerKind == MemberKind.Agent || peerKind == MemberKind.Human) {
                val kindedAlias = formatRaftDirectMessagePeerRef(alias, peerKind)
                aliasesByLookupKey.getOrPut(kindedAlias.lowercase()) { mutableListOf() } += conversationId to kindedAlias
            }
        }
    }
    return aliasesByLookupKey.mapNotNull { (key, matches) ->
        matches.firstOrNull()?.second?.takeIf {
            key in twinKeys || matches.map { match -> match.first }.distinct().size == 1
        }
    }
}

/**
 * Lower-cased names held by both a human and an agent in this server. A bare
 * `dm:@name` ref to one of them opens the first matching DM (Web parity);
 * `~agent` / `~human` pins the side.
 */
internal fun HomeSnapshot.markdownDirectMessageTwinPeerKeys(
    knownMemberProfilesById: Map<String, MemberProfile>
): Set<String> {
    val kindsByKey = mutableMapOf<String, MutableSet<MemberKind>>()
    fun record(name: String?, kind: MemberKind?) {
        if (kind != MemberKind.Human && kind != MemberKind.Agent) return
        val key = name?.markdownChannelLookupKey().orEmpty()
        if (key.isNotBlank()) kindsByKey.getOrPut(key) { mutableSetOf() } += kind
    }
    knownMemberProfilesById.values.forEach { profile -> record(profile.handle, profile.kind) }
    record(currentMember.handle, currentMember.kind)
    directMessages.forEach { row ->
        val conversation = row.toConversationSummary()
        val peerKind = conversation.directMessagePeerType.parsedMemberKindOrNull()
        val profile = conversation.presenceMemberId?.value?.trim()?.let(knownMemberProfilesById::get)
        record(profile?.handle, peerKind)
        record(conversation.name, peerKind)
    }
    return kindsByKey.filterValues { it.size > 1 }.keys
}

private fun String.markdownChannelLookupKey(): String =
    trim()
        .let { value ->
            when {
                value.startsWith("dm:@", ignoreCase = true) -> value.drop(4)
                value.startsWith("#") -> value.drop(1)
                else -> value
            }
        }
        .substringBefore(":")
        .trim()
        .lowercase()

private fun RaftThreadScreenPayload.markdownThreadRefTarget(
    parentChannelId: String,
    threadShortId: String
): MarkdownThreadRefTarget? = markdownThreadRefCandidates(parentChannelId, threadShortId).singleMarkdownThreadRefTarget()

/** Every message of this payload's [parentChannelId] timeline (and its origin) whose id starts with [threadShortId]. */
internal fun RaftThreadScreenPayload.markdownThreadRefCandidates(
    parentChannelId: String,
    threadShortId: String
): List<MarkdownThreadRefTarget> {
    val shortId = threadShortId.trim().lowercase()
    if (parentChannelId.isBlank() || shortId.isBlank()) return emptyList()
    val routeIsParentChannel =
        surfaceKind != RaftThreadSurfaceKind.ReplyThread &&
            (sourceThreadId == parentChannelId || streamChannelId == parentChannelId)
    val originIsParentMessage =
        this.parentChannelId == parentChannelId &&
            originMessage != null
    val candidateMessages =
        (if (routeIsParentChannel) messages else emptyList()) +
            if (originIsParentMessage) listOfNotNull(originMessage) else emptyList()
    return candidateMessages
        .filter { message -> message.id.lowercase().startsWith(shortId) }
        .map { message ->
            MarkdownThreadRefTarget(
                parentMessageId = message.id,
                threadChannelId = message.threadChannelId
            )
        }
}

/**
 * The one target these candidates name, merged across sources (a source that knows the thread
 * fills in one that does not). Null when there is none or when two different messages match.
 */
internal fun List<MarkdownThreadRefTarget>.singleMarkdownThreadRefTarget(): MarkdownThreadRefTarget? {
    val byMessage = groupBy { it.parentMessageId }
    val matches = byMessage.values.singleOrNull() ?: return null
    return MarkdownThreadRefTarget(
        parentMessageId = matches.first().parentMessageId,
        focusedMessageId = matches.firstNotNullOfOrNull { it.focusedMessageId?.takeIf(String::isNotBlank) },
        threadChannelId = matches.firstNotNullOfOrNull { it.threadChannelId?.takeIf(String::isNotBlank) }
    )
}

internal fun ThreadSnapshot.markdownThreadRefTarget(
    parentChannelId: String,
    threadShortId: String
): MarkdownThreadRefTarget? {
    val expectedShortId = threadShortId.trim().lowercase()
    val routeThread = conversation.thread
    if (
        conversation.kind == ConversationKind.Thread &&
        routeThread != null &&
        routeThread.parentChannelId.value == parentChannelId &&
        routeThread.parentMessageId.value.isNotBlank()
    ) {
        return MarkdownThreadRefTarget(
            parentMessageId = routeThread.parentMessageId.value,
            focusedMessageId =
                highlightedMessageId
                    ?.value
                    ?.takeIf { it.isNotBlank() && it != routeThread.parentMessageId.value },
            threadChannelId = routeThread.streamChannelId?.value
        )
    }
    val targetMessageId =
        highlightedMessageId
            ?.value
            ?.takeIf { it.isNotBlank() }
            ?: parentMessage
                ?.id
                ?.value
                ?.takeIf { it.isNotBlank() }
            ?: messages
                .firstOrNull { message ->
                    message.channelId?.value == parentChannelId &&
                        message.id.value.lowercase().startsWith(expectedShortId)
                }
                ?.id
                ?.value
    return targetMessageId?.let { MarkdownThreadRefTarget(parentMessageId = it) }
}

internal fun RaftThreadScreenPayload.withoutRouteCandidateContent(): RaftThreadScreenPayload =
    if (!resolveThreadRouteIdentity().canRenderTimelineCandidates) {
        val originCandidate = validatedUnresolvedReplyOriginCandidate()
        copy(
            status = if (hasRenderableThreadContent() && status != "error") "loading" else status,
            backgroundRefreshing = false,
            messages = emptyList(),
            originMessage = originCandidate,
            originPreview = if (originCandidate != null) originPreview else null,
            originStatus = if (originCandidate != null) originStatus else "none",
            originErrorCode = null,
            tasks = emptyList(),
            olderMessagesStatus = "idle",
            newerMessagesStatus = "idle",
            members = emptyList(),
            memberCandidates = emptyList(),
            settingsActions = emptyList()
        )
    } else {
        copy(
            status = "loading",
            backgroundRefreshing = false,
            messages = emptyList(),
            originMessage = null,
            originPreview = null,
            originStatus = "none",
            originErrorCode = null,
            tasks = emptyList(),
            olderMessagesStatus = "idle",
            newerMessagesStatus = "idle",
            errorCode = null,
            showComposer = true,
            joined = true,
            canSend = true,
            membersCount = 0,
            members = emptyList(),
            memberCandidates = emptyList(),
            settingsActions = emptyList()
        ).withLaunchHeaderContext()
    }

private fun RaftThreadScreenPayload.validatedUnresolvedReplyOriginCandidate(): RaftThreadMessageItem? {
    val expectedParentMessageId =
        parentMessageId?.takeIf { it.isNotBlank() }
            ?: originParentMessageId?.takeIf { it.isNotBlank() }
            ?: return null
    if (surfaceKind != RaftThreadSurfaceKind.ReplyThread) return null
    if (sourceThreadId?.isNotBlank() == true || streamChannelId?.isNotBlank() == true) return null
    return originMessage?.takeIf { message -> message.id == expectedParentMessageId }
}

internal fun RaftThreadScreenPayload.needsReplyThreadStreamResolution(): Boolean {
    val parentChannel = parentChannelId?.takeIf { it.isNotBlank() } ?: return false
    val parentMessage = parentMessageId?.takeIf { it.isNotBlank() }
        ?: originParentMessageId?.takeIf { it.isNotBlank() }
        ?: return false
    val streamCandidate =
        streamChannelId?.takeIf { it.isNotBlank() }
            ?: sourceThreadId?.takeIf { it.isNotBlank() }
    return surfaceKind == RaftThreadSurfaceKind.ReplyThread &&
        parentMessage.isNotBlank() &&
        (streamCandidate == null || streamCandidate == parentChannel)
}

internal fun RaftThreadScreenPayload.shouldFetchReplyThreadByParentPair(): Boolean =
    surfaceKind == RaftThreadSurfaceKind.ReplyThread &&
        parentChannelId?.isNotBlank() == true &&
        (parentMessageId?.isNotBlank() == true || originParentMessageId?.isNotBlank() == true)

private fun RaftThreadScreenPayload.shouldResolveReplyThreadByParentPair(): Boolean =
    shouldFetchReplyThreadByParentPair() &&
        needsReplyThreadStreamResolution()

internal fun RaftThreadScreenPayload.hasUntrustedReplyThreadStreamHint(): Boolean {
    val parentChannel = parentChannelId?.takeIf { it.isNotBlank() } ?: return false
    parentMessageId?.takeIf { it.isNotBlank() }
        ?: originParentMessageId?.takeIf { it.isNotBlank() }
        ?: return false
    val streamCandidate =
        streamChannelId?.takeIf { it.isNotBlank() }
            ?: sourceThreadId?.takeIf { it.isNotBlank() }
            ?: return false
    return surfaceKind == RaftThreadSurfaceKind.ReplyThread &&
        streamCandidate != parentChannel
}

internal fun ThreadSnapshot.parentPairMatchesRoute(base: RaftThreadScreenPayload): Boolean {
    val expectedParentChannelId = base.parentChannelId?.takeIf { it.isNotBlank() } ?: return true
    val expectedParentMessageId =
        base.parentMessageId?.takeIf { it.isNotBlank() }
            ?: base.originParentMessageId?.takeIf { it.isNotBlank() }
            ?: return true
    val thread = conversation.thread
    if (thread != null) {
        return thread.parentChannelId.value == expectedParentChannelId &&
            thread.parentMessageId.value == expectedParentMessageId
    }
    val parent = parentMessage ?: return true
    return parent.channelId?.value == expectedParentChannelId &&
        parent.id.value == expectedParentMessageId
}

internal fun RaftThreadScreenPayload.hasResolvedReplyThreadStreamFrom(base: RaftThreadScreenPayload): Boolean {
    val source = sourceThreadId?.takeIf { it.isNotBlank() } ?: return false
    val baseSource =
        base.streamChannelId?.takeIf { it.isNotBlank() }
            ?: base.sourceThreadId?.takeIf { it.isNotBlank() }
            ?: base.parentChannelId?.takeIf { it.isNotBlank() }
            ?: return false
    return surfaceKind == RaftThreadSurfaceKind.ReplyThread &&
        base.surfaceKind == RaftThreadSurfaceKind.ReplyThread &&
        source != baseSource
}

internal fun RaftThreadScreenPayload.withResolvedReplyThreadAction(
    action: ThreadActionResult
): RaftThreadScreenPayload {
    val threadChannelId = action.threadChannelId.value.takeIf { it.isNotBlank() } ?: return this
    val resolvedParentMessageId =
        action.parentMessageId?.value?.takeIf { it.isNotBlank() }
            ?: parentMessageId
            ?: originParentMessageId
    return copy(
        instanceId = "shared-shell-thread-$threadChannelId",
        sourceThreadId = threadChannelId,
        streamChannelId = threadChannelId,
        parentMessageId = resolvedParentMessageId,
        originParentMessageId = resolvedParentMessageId ?: originParentMessageId,
        originMessage =
            originMessage?.copy(
                threadChannelId = threadChannelId,
                replyCount = maxOf(originMessage.replyCount, action.replyCount)
            ),
        composer =
            composer.copy(
                parentMessageId = resolvedParentMessageId ?: composer.parentMessageId,
                streamChannelId = threadChannelId
            )
    )
}
