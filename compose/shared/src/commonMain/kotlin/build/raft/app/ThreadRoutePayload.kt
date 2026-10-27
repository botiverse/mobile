package build.raft.app

import build.raft.app.data.AuthPendingOpenRequest
import build.raft.app.data.ThreadRouteId
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberKind
import build.raft.app.model.MessageId
import build.raft.app.model.RaftTimeFormat24Hour
import build.raft.app.compat.json.JSONObject
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.TimeSource

internal const val THREAD_PAGE = "compose_thread"

private val threadPageJson = Json { ignoreUnknownKeys = true }

private object ThreadFocusedMessageRequestNonce {
    private var counter: Int = 0

    fun next(): Int {
        counter += 1
        if (counter <= 0) counter = 1
        return counter
    }
}

internal fun nextThreadFocusedMessageRequestNonce(): Int =
    ThreadFocusedMessageRequestNonce.next()

@Serializable
internal data class WireThreadLaunchPayload(
    val instanceId: String = "",
    val surfaceKind: String = RaftThreadSurfaceKind.Channel,
    val channelKind: String? = null,
    val threadId: String? = null,
    val sourceThreadId: String? = null,
    val streamChannelId: String? = null,
    val parentChannelId: String? = null,
    val parentMessageId: String? = null,
    val parentSurfaceKind: String? = null,
    val parentChannelKind: String? = null,
    val parentNavigationHint: String? = null,
    val dmPeerIdentity: MemberDetailIdentity? = null,
    val originParentMessageId: String? = null,
    val focusedMessageId: String? = null,
    val selectedTab: String = "chat",
    val navigationLabel: String = "Back",
    val alignOwnMessagesRight: Boolean = false,
    val compactConsecutiveMessages: Boolean = false,
    val showReactionActions: Boolean = true,
    val localOffsetMinutes: Int = 0,
    val todayEpochDay: Long? = null,
    val nowEpochMillis: Long? = null,
    val timeFormat: String = RaftTimeFormat24Hour,
    val composerSeedText: String = "",
    val composerSyncNonce: Int = 0,
    val composerFocusRequestNonce: Int = 0,
    val focusedMessageRequestNonce: Int = 0,
    val originFallbackParentChannelId: String? = null,
    val originFallbackParentMessageId: String? = null,
    val originFallbackPreview: String? = null,
    val originFallbackSenderId: String? = null,
    val originFallbackSenderKind: MemberKind? = null,
    val originFallbackExpected: Boolean = true
) {
    fun hasRouteIdentity(): Boolean =
        listOf(threadId, sourceThreadId, streamChannelId, parentChannelId, parentMessageId)
            .any { !it.isNullOrBlank() }

    fun toInitialPayload(fallbackInstanceId: String): RaftThreadScreenPayload {
        return toInitialPayloadOrNull(fallbackInstanceId)
            ?: error("Invalid thread launch route kind")
    }

    fun toInitialPayloadOrNull(fallbackInstanceId: String): RaftThreadScreenPayload? {
        val routeKinds = toThreadLaunchRouteKindsOrNull() ?: return null
        val isReplyThread = routeKinds.isReplyThread
        val resolvedParentSurfaceKind = routeKinds.parentSurfaceKind
        val resolvedParentChannelKind = routeKinds.parentChannelKind
        // Joint channels have task surfaces like any channel: the server maps each
        // participant's local joint channel id to the canonical task surface.
        // Task v2: a task's conversation is a sub-conversation (like a thread): no channel tabs,
        // members/settings or "as task" sends; its write authority is the task's channel.
        val isTaskConversation = routeKinds.kind == ConversationKind.Task
        val supportsChannelTasks = !isTaskConversation
        val sourceStreamCandidate =
            sourceThreadId.nonBlank()?.takeUnless { isReplyThread && it == parentChannelId.nonBlank() }
        val resolvedStreamId =
            streamChannelId.nonBlank()
                ?: sourceStreamCandidate
                ?: if (isReplyThread) null else threadId.nonBlank()
        val resolvedSourceId = sourceStreamCandidate ?: resolvedStreamId
        val resolvedInstanceId =
            instanceId.nonBlank()
                ?: fallbackInstanceId.nonBlank()
                ?: resolvedSourceId?.let { "thread-$it" }
                ?: "thread-launch"
        val resolvedTitle =
            SharedThreadHeaderContextStore
                .launchContext(
                    surfaceKind = routeKinds.surfaceKind,
                    parentMessageId = originParentMessageId.nonBlank() ?: parentMessageId.nonBlank()
                )
                .title
        val resolvedComposer =
            RaftThreadComposerPayload(
                surfaceKind = routeKinds.surfaceKind,
                seedText = composerSeedText,
                syncNonce = composerSyncNonce,
                focusRequestNonce = composerFocusRequestNonce,
                enabled = true,
                showAsTaskToggle = supportsChannelTasks,
                mentionChannelId = parentChannelId.nonBlank(),
                parentMessageId = parentMessageId.nonBlank(),
                streamChannelId = resolvedStreamId
            )
        val initialPayload = RaftThreadScreenPayload(
            instanceId = resolvedInstanceId,
            title = resolvedTitle,
            subtitle = null,
            status = "loading",
            surfaceKind = routeKinds.surfaceKind,
            channelKind =
                if (routeKinds.surfaceKind == RaftThreadSurfaceKind.Channel) {
                    routeKinds.channelKind ?: RaftThreadSurfaceKind.Channel
                } else {
                    RaftThreadSurfaceKind.Channel
                },
            sourceThreadId = resolvedSourceId,
            streamChannelId = resolvedStreamId,
            parentChannelId = parentChannelId.nonBlank(),
            parentMessageId = parentMessageId.nonBlank(),
            parentSurfaceKind = resolvedParentSurfaceKind,
            parentChannelKind = resolvedParentChannelKind,
            parentNavigationHint = parentNavigationHint.threadParentNavigationHintOrNull(),
            dmPeerIdentity = dmPeerIdentity.takeIf { routeKinds.surfaceKind == RaftThreadSurfaceKind.DirectMessage },
            memberScopedMentions = routeKinds.surfaceKind == RaftThreadSurfaceKind.DirectMessage,
            originParentMessageId =
                if (isReplyThread) {
                    originParentMessageId.nonBlank() ?: parentMessageId.nonBlank()
                } else {
                    null
                },
            focusedMessageId = focusedMessageId.nonBlank(),
            focusedMessageRequestNonce = focusedMessageRequestNonce,
            selectedTab = selectedTab,
            navigationLabel = navigationLabel,
            showModeTabs = !isReplyThread && !isTaskConversation,
            alignOwnMessagesRight = alignOwnMessagesRight,
            compactConsecutiveMessages = compactConsecutiveMessages,
            showReactionActions = showReactionActions,
            localOffsetMinutes = localOffsetMinutes,
            todayEpochDay = todayEpochDay,
            nowEpochMillis = nowEpochMillis,
            timeFormat = timeFormat,
            composer = resolvedComposer,
            showMembersAction =
                !isReplyThread && !isTaskConversation && routeKinds.surfaceKind != RaftThreadSurfaceKind.DirectMessage,
            showSettingsAction =
                !isReplyThread && !isTaskConversation && routeKinds.surfaceKind != RaftThreadSurfaceKind.DirectMessage,
            showOpenInChannelAction = isReplyThread && parentMessageId.nonBlank() != null && resolvedParentSurfaceKind != null,
            showTaskOpenActions = supportsChannelTasks,
            showTaskStatusActions = supportsChannelTasks,
            emptyTitle = "Loading...",
            emptyDetail = ""
        )
        val originFallback = toThreadLaunchOriginFallback()
        return if (originFallback == null) {
            initialPayload.withLaunchHeaderContext()
        } else {
            initialPayload.withHeaderContext(
                SharedThreadHeaderContextStore.launchOriginContext(
                    surfaceKind = initialPayload.surfaceKind,
                    routeIdentity = initialPayload.resolveThreadRouteIdentity(),
                    originMessage = null,
                    parentMessageId = initialPayload.originParentMessageId ?: initialPayload.parentMessageId,
                    originFallback = originFallback
                )
            )
        }
    }
}

internal fun WireThreadLaunchPayload.toAuthPendingOpenRequest(
    serverId: String,
    accountPrincipalId: String,
): AuthPendingOpenRequest.Thread? {
    val resolvedServerId = serverId.trim().takeIf { it.isNotBlank() } ?: return null
    val resolvedAccountPrincipalId =
        accountPrincipalId.trim().takeIf { it.isNotBlank() } ?: return null
    if (!hasRouteIdentity()) return null
    return AuthPendingOpenRequest.Thread(
        serverId = resolvedServerId,
        accountPrincipalId = resolvedAccountPrincipalId,
        surfaceKind = surfaceKind,
        channelKind = channelKind,
        threadId = threadId,
        sourceThreadId = sourceThreadId,
        streamChannelId = streamChannelId,
        parentChannelId = parentChannelId,
        parentMessageId = parentMessageId,
        parentSurfaceKind = parentSurfaceKind,
        parentChannelKind = parentChannelKind,
        parentNavigationHint = parentNavigationHint,
        originParentMessageId = originParentMessageId,
        focusedMessageId = focusedMessageId,
        selectedTab = selectedTab
    )
}

internal fun AuthPendingOpenRequest.Thread.toWireThreadLaunchPayload(): WireThreadLaunchPayload =
    WireThreadLaunchPayload(
        instanceId = "auth-resume-${sourceThreadId ?: streamChannelId ?: threadId ?: parentMessageId.orEmpty()}",
        surfaceKind = surfaceKind,
        channelKind = channelKind,
        threadId = threadId,
        sourceThreadId = sourceThreadId,
        streamChannelId = streamChannelId,
        parentChannelId = parentChannelId,
        parentMessageId = parentMessageId,
        parentSurfaceKind = parentSurfaceKind,
        parentChannelKind = parentChannelKind,
        parentNavigationHint = parentNavigationHint,
        originParentMessageId = originParentMessageId,
        focusedMessageId = focusedMessageId,
        selectedTab = selectedTab,
        focusedMessageRequestNonce = if (focusedMessageId.isNullOrBlank()) 0 else nextThreadFocusedMessageRequestNonce()
    )

private data class ThreadLaunchRouteKinds(
    val kind: ConversationKind,
    val surfaceKind: String,
    val channelKind: String?,
    val parentSurfaceKind: String?,
    val parentChannelKind: String?
) {
    val isReplyThread: Boolean = kind == ConversationKind.Thread
}

private fun WireThreadLaunchPayload.toThreadLaunchRouteKindsOrNull(): ThreadLaunchRouteKinds? {
    val explicitSurfaceKind = surfaceKind.nonBlank() ?: return null
    val surfaceConversationKind = parseConversationKindOrNull(explicitSurfaceKind) ?: return null
    val channelConversationKind =
        channelKind.nonBlank()?.let { rawKind ->
            parseConversationKindOrNull(rawKind) ?: return null
        }
    val routeKind =
        if (surfaceConversationKind == ConversationKind.Channel && channelConversationKind != null) {
            channelConversationKind
        } else {
            surfaceConversationKind
        }
    val normalizedSurfaceKind = routeKind.toRaftSurfaceKind()
    val normalizedChannelKind =
        if (normalizedSurfaceKind == RaftThreadSurfaceKind.Channel) {
            routeKind.toLaunchChannelKind(channelKind)
        } else {
            null
        }
    parentSurfaceKind.nonBlank()?.let { rawKind ->
        parseConversationKindOrNull(rawKind) ?: return null
    }
    parentChannelKind.nonBlank()?.let { rawKind ->
        parseConversationKindOrNull(rawKind) ?: return null
    }
    val parentKind =
        typedReplyParentConversationKindOrNull(
            parentSurfaceKind = parentSurfaceKind,
            parentChannelKind = parentChannelKind
        )
    if (
        routeKind == ConversationKind.Thread &&
        (
            parentChannelId.nonBlank() == null ||
                parentMessageId.nonBlank() == null ||
                parentKind == null
            )
    ) {
        return null
    }
    val parentSurface = parentKind?.toLaunchParentSurfaceKindOrNull()
    if (parentKind != null && parentSurface == null) return null
    val parentChannel =
        when (parentKind) {
            ConversationKind.DirectMessage -> RaftThreadSurfaceKind.DirectMessage
            ConversationKind.Joint -> "joint"
            ConversationKind.Channel ->
                parentKind.toLaunchChannelKind(parentChannelKind)
                    ?: normalizedChannelKind.takeIf { routeKind == ConversationKind.Thread }
            null -> null
            else -> return null
        }
    return ThreadLaunchRouteKinds(
        kind = routeKind,
        surfaceKind = normalizedSurfaceKind,
        channelKind = normalizedChannelKind,
        parentSurfaceKind = parentSurface,
        parentChannelKind = parentChannel
    )
}

/**
 * Legacy flat-wire adapter for the Thread launch-provenance foundation.
 *
 * Production parsing remains unchanged in this PR. New Store/producer migrations use this
 * adapter and the single typed constructor, so conflicting nullable aliases are rejected
 * instead of being resolved by a nonblank-first guess.
 */
internal fun WireThreadLaunchPayload.toThreadLaunchIdentityConstruction(): ThreadLaunchIdentityConstructionResult {
    val rawSurfaceKind = surfaceKind.nonBlank()
        ?: return rejectedThreadLaunchIdentity(ThreadLaunchIdentityConstructionError.MalformedLegacyKind)
    val surfaceConversationKind = parseConversationKindOrNull(rawSurfaceKind)
        ?: return rejectedThreadLaunchIdentity(ThreadLaunchIdentityConstructionError.MalformedLegacyKind)
    val parsedChannelKind =
        channelKind.nonBlank()?.let { rawKind ->
            parseConversationKindOrNull(rawKind)
                ?: return rejectedThreadLaunchIdentity(ThreadLaunchIdentityConstructionError.MalformedLegacyKind)
        }
    if (
        surfaceConversationKind == ConversationKind.DirectMessage &&
        parsedChannelKind != null &&
        parsedChannelKind != ConversationKind.DirectMessage &&
        // Legacy screen payloads left their default `channel` value on the DM surface.
        parsedChannelKind != ConversationKind.Channel
    ) {
        return rejectedThreadLaunchIdentity(ThreadLaunchIdentityConstructionError.ConflictingLegacyIdentity)
    }
    if (
        surfaceConversationKind == ConversationKind.Channel &&
        parsedChannelKind in setOf(ConversationKind.DirectMessage, ConversationKind.Thread)
    ) {
        return rejectedThreadLaunchIdentity(ThreadLaunchIdentityConstructionError.ConflictingLegacyIdentity)
    }
    if (
        surfaceConversationKind == ConversationKind.Thread &&
        parsedChannelKind != null &&
        // Reply screen payloads likewise retained the render default `channel` value.
        parsedChannelKind != ConversationKind.Channel
    ) {
        return rejectedThreadLaunchIdentity(ThreadLaunchIdentityConstructionError.ConflictingLegacyIdentity)
    }

    val routeKinds = toThreadLaunchRouteKindsOrNull()
        ?: return when {
            surfaceConversationKind == ConversationKind.Thread &&
                (parentChannelId.nonBlank() == null || parentMessageId.nonBlank() == null) ->
                rejectedThreadLaunchIdentity(ThreadLaunchIdentityConstructionError.MissingReplyParentPair)
            else -> rejectedThreadLaunchIdentity(ThreadLaunchIdentityConstructionError.MalformedLegacyKind)
        }

    return if (routeKinds.isReplyThread) {
        if (dmPeerIdentity != null) {
            return rejectedThreadLaunchIdentity(ThreadLaunchIdentityConstructionError.UnexpectedDirectMessagePeer)
        }
        val parentChannel = parentChannelId.nonBlank()?.let(::ChannelId)
            ?: return rejectedThreadLaunchIdentity(ThreadLaunchIdentityConstructionError.MissingReplyParentPair)
        val parentMessage = parentMessageId.nonBlank()?.let(::MessageId)
            ?: return rejectedThreadLaunchIdentity(ThreadLaunchIdentityConstructionError.MissingReplyParentPair)
        if (legacyReplyParentKindsConflict()) {
            return rejectedThreadLaunchIdentity(ThreadLaunchIdentityConstructionError.ConflictingLegacyIdentity)
        }
        val parentKind =
            typedReplyParentConversationKindOrNull(
                parentSurfaceKind = routeKinds.parentSurfaceKind,
                parentChannelKind = routeKinds.parentChannelKind
            ) ?: return rejectedThreadLaunchIdentity(ThreadLaunchIdentityConstructionError.ReplyParentKindMissing)
        val streamCandidates =
            listOf(streamChannelId, sourceThreadId, threadId)
                .mapNotNull { it.nonBlank() }
                .filterNot { it == parentChannel.value }
                .distinct()
        if (streamCandidates.size > 1) {
            return rejectedThreadLaunchIdentity(ThreadLaunchIdentityConstructionError.ConflictingLegacyIdentity)
        }
        val replyStream =
            streamCandidates.singleOrNull()
                ?.let { ThreadLaunchReplyStream.Resolved(ChannelId(it)) }
                ?: ThreadLaunchReplyStream.Unresolved
        val proofStreamChannelId =
            when (replyStream) {
                ThreadLaunchReplyStream.Unresolved -> parentChannel
                is ThreadLaunchReplyStream.Resolved -> replyStream.channelId
            }
        constructThreadLaunchConversationIdentity(
            routeId =
                ThreadRouteId.ReplyThread(
                    parentChannelId = parentChannel,
                    parentMessageId = parentMessage,
                    threadChannelId = proofStreamChannelId
                ),
            kind = ConversationKind.Thread,
            parentConversationKind = parentKind,
            parentChannelType = routeKinds.parentChannelKind,
            replyStream = replyStream
        )
    } else {
        if (
            parentChannelId.nonBlank() != null ||
            parentMessageId.nonBlank() != null ||
            parentSurfaceKind.nonBlank() != null ||
            parentChannelKind.nonBlank() != null
        ) {
            return rejectedThreadLaunchIdentity(ThreadLaunchIdentityConstructionError.UnexpectedReplyMetadata)
        }
        val channelCandidates =
            listOf(streamChannelId, sourceThreadId, threadId)
                .mapNotNull { it.nonBlank() }
                .distinct()
        if (channelCandidates.isEmpty()) {
            return rejectedThreadLaunchIdentity(ThreadLaunchIdentityConstructionError.BlankRouteIdentity)
        }
        if (channelCandidates.size > 1) {
            return rejectedThreadLaunchIdentity(ThreadLaunchIdentityConstructionError.ConflictingLegacyIdentity)
        }
        constructThreadLaunchConversationIdentity(
            routeId = ThreadRouteId.ChannelThread(channelId = ChannelId(channelCandidates.single())),
            kind = routeKinds.kind,
            channelType = routeKinds.channelKind,
            dmPeerIdentity = dmPeerIdentity
        )
    }
}

private fun WireThreadLaunchPayload.legacyReplyParentKindsConflict(): Boolean {
    val parsedSurface = parseConversationKindOrNull(parentSurfaceKind)
    val parsedChannel = parseConversationKindOrNull(parentChannelKind)
    if (parsedSurface == null || parsedChannel == null) return false
    val surfaceIsDirect = parsedSurface == ConversationKind.DirectMessage
    val channelIsDirect = parsedChannel == ConversationKind.DirectMessage
    return surfaceIsDirect != channelIsDirect
}

private fun rejectedThreadLaunchIdentity(
    error: ThreadLaunchIdentityConstructionError
): ThreadLaunchIdentityConstructionResult.Rejected =
    ThreadLaunchIdentityConstructionResult.Rejected(error)

/** Normalize only identity fields; intent/host preferences stay on the supplied legacy frame. */
internal fun ThreadLaunchIdentityConstructionResult.Accepted.toNormalizedLegacyWirePayload(
    template: WireThreadLaunchPayload
): WireThreadLaunchPayload =
    when (val route = routeId) {
        is ThreadRouteId.ChannelThread ->
            template.copy(
                surfaceKind = identity.kind.toRaftSurfaceKind(),
                channelKind =
                    when (identity.kind) {
                        ConversationKind.Channel ->
                            identity.channelType.normalizedThreadChannelKind()
                                ?: RaftThreadSurfaceKind.Channel
                        ConversationKind.Joint -> JointChannelKindWireValue
                        else -> null
                    },
                threadId = route.channelId.value,
                sourceThreadId = null,
                streamChannelId = null,
                parentChannelId = null,
                parentMessageId = null,
                parentSurfaceKind = null,
                parentChannelKind = null,
                dmPeerIdentity = identity.dmPeerIdentity.takeIf {
                    identity.kind == ConversationKind.DirectMessage
                }
            )

        is ThreadRouteId.ReplyThread -> {
            val resolvedStream = (replyStream as? ThreadLaunchReplyStream.Resolved)?.channelId?.value
            val parentKind = checkNotNull(parentConversationKind)
            val normalizedParentChannelKind =
                identity.thread?.parentChannelKind.normalizedThreadChannelKind()
                    ?: parentKind.threadChannelKindWireValue()
            template.copy(
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                channelKind = null,
                threadId = null,
                sourceThreadId = null,
                streamChannelId = resolvedStream,
                parentChannelId = route.parentChannelId.value,
                parentMessageId = route.parentMessageId.value,
                parentSurfaceKind = parentKind.threadParentSurfaceKindOrNull(),
                parentChannelKind = normalizedParentChannelKind,
                dmPeerIdentity = null
            )
        }

        is ThreadRouteId.FocusedThread -> error("Focused route is not a launch conversation identity")
    }

private fun ConversationKind.toLaunchChannelKind(rawKind: String?): String? =
    when (this) {
        ConversationKind.Channel -> rawKind.normalizedLaunchChannelKindOrNull() ?: RaftThreadSurfaceKind.Channel
        ConversationKind.Joint -> "joint"
        ConversationKind.DirectMessage -> RaftThreadSurfaceKind.DirectMessage
        ConversationKind.Task -> RaftTaskConversationChannelKind
        else -> null
    }

private fun ConversationKind.toLaunchParentSurfaceKindOrNull(): String? =
    when (this) {
        ConversationKind.DirectMessage -> RaftThreadSurfaceKind.DirectMessage
        ConversationKind.Channel,
        ConversationKind.Joint -> RaftThreadSurfaceKind.Channel
        else -> null
    }

private fun String?.normalizedLaunchChannelKindOrNull(): String? =
    nonBlank()
        ?.trim()
        ?.lowercase()
        ?.takeIf { kind -> parseConversationKindOrNull(kind).let { it == ConversationKind.Channel || it == ConversationKind.Joint } }

private fun WireThreadLaunchPayload.toThreadLaunchOriginFallback(): ThreadLaunchOriginFallback? {
    val parentMessageId = originFallbackParentMessageId?.takeIf { it.isNotBlank() } ?: return null
    return ThreadLaunchOriginFallback(
        parentChannelId = originFallbackParentChannelId?.takeIf { it.isNotBlank() },
        parentMessageId = parentMessageId,
        preview = originFallbackPreview?.takeIf { it.isNotBlank() },
        senderId = originFallbackSenderId?.takeIf { it.isNotBlank() },
        senderKind = originFallbackSenderKind,
        expected = originFallbackExpected
    )
}

internal fun RaftThreadScreenPayload.withSharedShellEventCapabilities(): RaftThreadScreenPayload =
    copy(
        subtitle =
            if (showModeTabs) {
                subtitle
            } else {
                existingThreadChannelHeaderSubtitle(subtitle)
            },
        showOpenInChannelAction =
            showOpenInChannelAction ||
                (
                    surfaceKind == RaftThreadSurfaceKind.ReplyThread &&
                        originParentMessageId?.isNotBlank() == true &&
                        parentSurfaceKind.parsedThreadParentSurfaceKindOrNull() != null
                    ),
        showSettingsAction = showSettingsAction && showModeTabs,
        showMembersAction = showMembersAction && showModeTabs
    )

internal fun RaftThreadScreenPayload.toWireThreadLaunchPayload(): WireThreadLaunchPayload =
    WireThreadLaunchPayload(
        instanceId = instanceId,
        surfaceKind = surfaceKind,
        channelKind = channelKind.takeIf { it.isNotBlank() },
        threadId =
            when (surfaceKind) {
                RaftThreadSurfaceKind.ReplyThread,
                RaftThreadSurfaceKind.DirectMessage -> streamChannelId.nonBlank() ?: sourceThreadId.nonBlank()
                else -> null
            },
        sourceThreadId = sourceThreadId.nonBlank(),
        streamChannelId = streamChannelId.nonBlank(),
        parentChannelId = parentChannelId.nonBlank(),
        parentMessageId = parentMessageId.nonBlank(),
        parentSurfaceKind = parentSurfaceKind.parsedThreadParentSurfaceKindOrNull(),
        parentChannelKind = parentChannelKind.normalizedThreadChannelKind(),
        parentNavigationHint = parentNavigationHint.threadParentNavigationHintOrNull(),
        dmPeerIdentity = dmPeerIdentity.takeIf { surfaceKind == RaftThreadSurfaceKind.DirectMessage },
        originParentMessageId = originParentMessageId.nonBlank() ?: parentMessageId.nonBlank(),
        focusedMessageId = focusedMessageId.nonBlank(),
        selectedTab = selectedTab,
        navigationLabel = navigationLabel,
        alignOwnMessagesRight = alignOwnMessagesRight,
        compactConsecutiveMessages = compactConsecutiveMessages,
        showReactionActions = showReactionActions,
        localOffsetMinutes = localOffsetMinutes,
        todayEpochDay = todayEpochDay,
        nowEpochMillis = nowEpochMillis,
        timeFormat = timeFormat,
        composerSeedText = composer.seedText,
        composerSyncNonce = composer.syncNonce,
        composerFocusRequestNonce = composer.focusRequestNonce,
        focusedMessageRequestNonce = focusedMessageRequestNonce
    )

internal fun RaftThreadScreenPayload.toWireThreadLaunchPayloadOrNull(): WireThreadLaunchPayload? {
    val launchPayload = toWireThreadLaunchPayload()
    if (!launchPayload.hasRouteIdentity()) return null
    return launchPayload.takeIf { it.toInitialPayloadOrNull(instanceId) != null }
}

internal fun WireThreadLaunchPayload.threadComposerDraftKey(
    fallbackInstanceId: String = instanceId
): String? {
    val initialPayload = toInitialPayload(fallbackInstanceId)
    val resolvedAuxiliaryThreadId = initialPayload.resolvedAuxiliaryThreadId()
    return initialPayload
        .resolveThreadComposer()
        .copy(streamChannelId = resolvedAuxiliaryThreadId)
        .toThreadComposerSurface(
            channelId = resolvedAuxiliaryThreadId.orEmpty(),
            parentChannelId = initialPayload.parentChannelId
        )
        .threadComposerDraftKey()
}

internal fun threadLaunchPageData(launchPayload: WireThreadLaunchPayload): JSONObject =
    JSONObject().apply {
        put("pageName", THREAD_PAGE)
        put("payload", threadPageJson.encodeToString(launchPayload))
        put("instanceId", launchPayload.instanceId)
    }

internal fun WireThreadLaunchPayload.toThreadPageData(): JSONObject =
    threadLaunchPageData(this)

internal fun parseThreadScreenPayload(
    payload: String,
    instanceId: String
): RaftThreadScreenPayload {
    return parseThreadScreenPayloadWithTrace(payload, instanceId).payload
}

internal fun parseThreadScreenPayloadWithTrace(
    payload: String,
    instanceId: String
): ThreadLaunchPayloadParseTrace {
    val startedAt = TimeSource.Monotonic.markNow()
    val source = payload.trim().takeIf { it.isNotEmpty() } ?: "{}"
    val parsed = parseThreadLaunchPayload(source, instanceId)
    val resultPayload =
        parsed ?: RaftThreadScreenPayload(
            instanceId = instanceId,
            status = "error",
            errorCode = "payload_parse_failed"
        )
    return ThreadLaunchPayloadParseTrace(
        payload = resultPayload,
        durationMs = startedAt.elapsedNow().inWholeMilliseconds,
        responseBytes = source.encodeToByteArray().size,
        result = if (parsed != null) "success" else "failure"
    )
}

internal data class ThreadLaunchPayloadParseTrace(
    val payload: RaftThreadScreenPayload,
    val durationMs: Long,
    val responseBytes: Int,
    val result: String
)

internal fun parseThreadLaunchPayload(source: String, instanceId: String): RaftThreadScreenPayload? =
    runCatching {
        threadPageJson
            .decodeFromString(WireThreadLaunchPayload.serializer(), source)
            .takeIf { it.hasRouteIdentity() }
            ?.toInitialPayloadOrNull(instanceId)
    }.getOrNull()

internal const val ThreadParentNavigationPrevious = "previous"

internal fun String?.threadParentNavigationHintOrNull(): String? =
    when (this?.trim()?.lowercase()) {
        ThreadParentNavigationPrevious -> ThreadParentNavigationPrevious
        else -> null
    }

internal fun String?.nonBlank(): String? = this?.takeIf { it.isNotBlank() }
