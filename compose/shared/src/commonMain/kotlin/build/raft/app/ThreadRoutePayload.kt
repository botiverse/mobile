package build.raft.app

import build.raft.app.data.AuthPendingOpenRequest
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberKind
import build.raft.app.model.SlockTimeFormat24Hour
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
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
    val surfaceKind: String = KuiklyThreadSurfaceKind.Channel,
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
    val showInlineSaveAction: Boolean = false,
    val showReactionActions: Boolean = true,
    val localOffsetMinutes: Int = 0,
    val todayEpochDay: Long? = null,
    val nowEpochMillis: Long? = null,
    val timeFormat: String = SlockTimeFormat24Hour,
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

    fun toInitialPayload(fallbackInstanceId: String): KuiklyThreadScreenPayload {
        return toInitialPayloadOrNull(fallbackInstanceId)
            ?: error("Invalid thread launch route kind")
    }

    fun toInitialPayloadOrNull(fallbackInstanceId: String): KuiklyThreadScreenPayload? {
        val routeKinds = toThreadLaunchRouteKindsOrNull() ?: return null
        val isReplyThread = routeKinds.isReplyThread
        val resolvedParentSurfaceKind = routeKinds.parentSurfaceKind
        val resolvedParentChannelKind = routeKinds.parentChannelKind
        val taskChannelKind = if (isReplyThread) resolvedParentChannelKind else routeKinds.channelKind
        val supportsChannelTasks = !taskChannelKind.equals(JointChannelKindWireValue, ignoreCase = true)
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
            KuiklyThreadComposerPayload(
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
        val initialPayload = KuiklyThreadScreenPayload(
            instanceId = resolvedInstanceId,
            title = resolvedTitle,
            subtitle = null,
            status = "loading",
            surfaceKind = routeKinds.surfaceKind,
            channelKind =
                if (routeKinds.surfaceKind == KuiklyThreadSurfaceKind.Channel) {
                    routeKinds.channelKind ?: KuiklyThreadSurfaceKind.Channel
                } else {
                    KuiklyThreadSurfaceKind.Channel
                },
            sourceThreadId = resolvedSourceId,
            streamChannelId = resolvedStreamId,
            parentChannelId = parentChannelId.nonBlank(),
            parentMessageId = parentMessageId.nonBlank(),
            parentSurfaceKind = resolvedParentSurfaceKind,
            parentChannelKind = resolvedParentChannelKind,
            parentNavigationHint = parentNavigationHint.threadParentNavigationHintOrNull(),
            dmPeerIdentity = dmPeerIdentity.takeIf { routeKinds.surfaceKind == KuiklyThreadSurfaceKind.DirectMessage },
            memberScopedMentions = routeKinds.surfaceKind == KuiklyThreadSurfaceKind.DirectMessage,
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
            showModeTabs = !isReplyThread,
            showReplyActions = !isReplyThread,
            alignOwnMessagesRight = alignOwnMessagesRight,
            compactConsecutiveMessages = compactConsecutiveMessages,
            showInlineSaveAction = showInlineSaveAction,
            showReactionActions = showReactionActions,
            localOffsetMinutes = localOffsetMinutes,
            todayEpochDay = todayEpochDay,
            nowEpochMillis = nowEpochMillis,
            timeFormat = timeFormat,
            composer = resolvedComposer,
            showMembersAction = !isReplyThread && routeKinds.surfaceKind != KuiklyThreadSurfaceKind.DirectMessage,
            showSettingsAction = !isReplyThread && routeKinds.surfaceKind != KuiklyThreadSurfaceKind.DirectMessage,
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
    serverId: String
): AuthPendingOpenRequest.Thread? {
    val resolvedServerId = serverId.trim().takeIf { it.isNotBlank() } ?: return null
    if (!hasRouteIdentity()) return null
    return AuthPendingOpenRequest.Thread(
        serverId = resolvedServerId,
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
    val normalizedSurfaceKind = routeKind.toKuiklySurfaceKind()
    val normalizedChannelKind =
        if (normalizedSurfaceKind == KuiklyThreadSurfaceKind.Channel) {
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
            ConversationKind.DirectMessage -> KuiklyThreadSurfaceKind.DirectMessage
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

private fun ConversationKind.toLaunchChannelKind(rawKind: String?): String? =
    when (this) {
        ConversationKind.Channel -> rawKind.normalizedLaunchChannelKindOrNull() ?: KuiklyThreadSurfaceKind.Channel
        ConversationKind.Joint -> "joint"
        ConversationKind.DirectMessage -> KuiklyThreadSurfaceKind.DirectMessage
        else -> null
    }

private fun ConversationKind.toLaunchParentSurfaceKindOrNull(): String? =
    when (this) {
        ConversationKind.DirectMessage -> KuiklyThreadSurfaceKind.DirectMessage
        ConversationKind.Channel,
        ConversationKind.Joint -> KuiklyThreadSurfaceKind.Channel
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

internal fun KuiklyThreadScreenPayload.withSharedShellEventCapabilities(): KuiklyThreadScreenPayload =
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
                    surfaceKind == KuiklyThreadSurfaceKind.ReplyThread &&
                        originParentMessageId?.isNotBlank() == true &&
                        parentSurfaceKind.parsedThreadParentSurfaceKindOrNull() != null
                    ),
        showSettingsAction = showSettingsAction && showModeTabs,
        showMembersAction = showMembersAction && showModeTabs
    )

internal fun KuiklyThreadScreenPayload.toWireThreadLaunchPayload(): WireThreadLaunchPayload =
    WireThreadLaunchPayload(
        instanceId = instanceId,
        surfaceKind = surfaceKind,
        channelKind = channelKind.takeIf { it.isNotBlank() },
        threadId =
            when (surfaceKind) {
                KuiklyThreadSurfaceKind.ReplyThread,
                KuiklyThreadSurfaceKind.DirectMessage -> streamChannelId.nonBlank() ?: sourceThreadId.nonBlank()
                else -> null
            },
        sourceThreadId = sourceThreadId.nonBlank(),
        streamChannelId = streamChannelId.nonBlank(),
        parentChannelId = parentChannelId.nonBlank(),
        parentMessageId = parentMessageId.nonBlank(),
        parentSurfaceKind = parentSurfaceKind.parsedThreadParentSurfaceKindOrNull(),
        parentChannelKind = parentChannelKind.normalizedThreadChannelKind(),
        parentNavigationHint = parentNavigationHint.threadParentNavigationHintOrNull(),
        dmPeerIdentity = dmPeerIdentity.takeIf { surfaceKind == KuiklyThreadSurfaceKind.DirectMessage },
        originParentMessageId = originParentMessageId.nonBlank() ?: parentMessageId.nonBlank(),
        focusedMessageId = focusedMessageId.nonBlank(),
        selectedTab = selectedTab,
        navigationLabel = navigationLabel,
        alignOwnMessagesRight = alignOwnMessagesRight,
        compactConsecutiveMessages = compactConsecutiveMessages,
        showInlineSaveAction = showInlineSaveAction,
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

internal fun KuiklyThreadScreenPayload.toWireThreadLaunchPayloadOrNull(): WireThreadLaunchPayload? {
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
): KuiklyThreadScreenPayload {
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
        parsed ?: KuiklyThreadScreenPayload(
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
    val payload: KuiklyThreadScreenPayload,
    val durationMs: Long,
    val responseBytes: Int,
    val result: String
)

internal fun parseThreadLaunchPayload(source: String, instanceId: String): KuiklyThreadScreenPayload? =
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
