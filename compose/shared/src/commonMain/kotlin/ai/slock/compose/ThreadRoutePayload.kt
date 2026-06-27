package ai.slock.compose

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val THREAD_PAGE = "compose_thread"

private val threadPageJson = Json { ignoreUnknownKeys = true }

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
    val originParentMessageId: String? = null,
    val focusedMessageId: String? = null,
    val selectedTab: String = "chat",
    val navigationLabel: String = "Back",
    val alignOwnMessagesRight: Boolean = false,
    val localOffsetMinutes: Int = 0,
    val todayEpochDay: Long? = null,
    val nowEpochMillis: Long? = null,
    val timeFormat: String = SlockTimeFormat24Hour,
    val composerSeedText: String = "",
    val composerSyncNonce: Int = 0,
    val composerFocusRequestNonce: Int = 0
) {
    fun hasRouteIdentity(): Boolean =
        listOf(threadId, sourceThreadId, streamChannelId, parentChannelId, parentMessageId)
            .any { !it.isNullOrBlank() }

    fun toInitialPayload(fallbackInstanceId: String): KuiklyThreadScreenPayload {
        val isReplyThread = surfaceKind == KuiklyThreadSurfaceKind.ReplyThread
        val resolvedParentSurfaceKind = parentSurfaceKind.threadLaunchParentSurfaceKindOrNull()
        val resolvedParentChannelKind =
            parentChannelKind.normalizedThreadChannelKind()
                ?: channelKind.normalizedThreadChannelKind()
                    .takeIf { isReplyThread && resolvedParentSurfaceKind == KuiklyThreadSurfaceKind.Channel }
        val taskChannelKind = if (isReplyThread) resolvedParentChannelKind else channelKind.normalizedThreadChannelKind()
        val supportsChannelTasks = !taskChannelKind.equals("joint", ignoreCase = true)
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
                    surfaceKind = surfaceKind,
                    parentMessageId = originParentMessageId.nonBlank() ?: parentMessageId.nonBlank()
                )
                .title
        val resolvedComposer =
            KuiklyThreadComposerPayload(
                surfaceKind = surfaceKind,
                seedText = composerSeedText,
                syncNonce = composerSyncNonce,
                focusRequestNonce = composerFocusRequestNonce,
                enabled = true,
                showAsTaskToggle = supportsChannelTasks,
                mentionChannelId = parentChannelId.nonBlank(),
                parentMessageId = parentMessageId.nonBlank(),
                streamChannelId = resolvedStreamId
            )
        return KuiklyThreadScreenPayload(
            instanceId = resolvedInstanceId,
            title = resolvedTitle,
            subtitle = null,
            status = "loading",
            surfaceKind = surfaceKind,
            channelKind =
                if (surfaceKind == KuiklyThreadSurfaceKind.Channel) {
                    channelKind?.takeIf { it.isNotBlank() } ?: KuiklyThreadSurfaceKind.Channel
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
            memberScopedMentions = surfaceKind == KuiklyThreadSurfaceKind.DirectMessage,
            originParentMessageId =
                if (isReplyThread) {
                    originParentMessageId.nonBlank() ?: parentMessageId.nonBlank()
                } else {
                    null
                },
            focusedMessageId = focusedMessageId.nonBlank(),
            selectedTab = selectedTab,
            navigationLabel = navigationLabel,
            showModeTabs = !isReplyThread,
            showReplyActions = !isReplyThread,
            alignOwnMessagesRight = alignOwnMessagesRight,
            localOffsetMinutes = localOffsetMinutes,
            todayEpochDay = todayEpochDay,
            nowEpochMillis = nowEpochMillis,
            timeFormat = timeFormat,
            filesPayload = "{}",
            composer = resolvedComposer,
            showMembersAction = !isReplyThread && surfaceKind != KuiklyThreadSurfaceKind.DirectMessage,
            showSettingsAction = !isReplyThread && surfaceKind != KuiklyThreadSurfaceKind.DirectMessage,
            showOpenInChannelAction = isReplyThread && parentMessageId.nonBlank() != null && resolvedParentSurfaceKind != null,
            showTaskOpenActions = supportsChannelTasks,
            showTaskStatusActions = supportsChannelTasks,
            emptyTitle = "Loading...",
            emptyDetail = ""
        ).withLaunchHeaderContext()
    }
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
                        parentSurfaceKind.threadLaunchParentSurfaceKindOrNull() != null
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
        parentSurfaceKind = parentSurfaceKind.threadLaunchParentSurfaceKindOrNull(),
        parentChannelKind = parentChannelKind.normalizedThreadChannelKind(),
        parentNavigationHint = parentNavigationHint.threadParentNavigationHintOrNull(),
        originParentMessageId = originParentMessageId.nonBlank() ?: parentMessageId.nonBlank(),
        focusedMessageId = focusedMessageId.nonBlank(),
        selectedTab = selectedTab,
        navigationLabel = navigationLabel,
        alignOwnMessagesRight = alignOwnMessagesRight,
        localOffsetMinutes = localOffsetMinutes,
        todayEpochDay = todayEpochDay,
        nowEpochMillis = nowEpochMillis,
        timeFormat = timeFormat,
        composerSeedText = composer.seedText,
        composerSyncNonce = composer.syncNonce,
        composerFocusRequestNonce = composer.focusRequestNonce
    )

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
    val source = payload.trim().takeIf { it.isNotEmpty() } ?: "{}"
    parseThreadLaunchPayload(source, instanceId)?.let { return it }
    return KuiklyThreadScreenPayload(
        instanceId = instanceId,
        status = "error",
        errorCode = "payload_parse_failed"
    )
}

internal fun parseThreadLaunchPayload(source: String, instanceId: String): KuiklyThreadScreenPayload? =
    runCatching {
        threadPageJson
            .decodeFromString(WireThreadLaunchPayload.serializer(), source)
            .takeIf { it.hasRouteIdentity() }
            ?.toInitialPayload(instanceId)
    }.getOrNull()

internal fun String?.threadLaunchParentSurfaceKindOrNull(): String? =
    when (this?.trim()?.lowercase()) {
        KuiklyThreadSurfaceKind.DirectMessage,
        "direct_message",
        "direct-message",
        "directmessage" -> KuiklyThreadSurfaceKind.DirectMessage
        KuiklyThreadSurfaceKind.Channel,
        "public",
        "private",
        "joint",
        "group" -> KuiklyThreadSurfaceKind.Channel
        else -> null
    }

internal const val ThreadParentNavigationPrevious = "previous"

internal fun String?.threadParentNavigationHintOrNull(): String? =
    when (this?.trim()?.lowercase()) {
        ThreadParentNavigationPrevious -> ThreadParentNavigationPrevious
        else -> null
    }

internal fun String?.nonBlank(): String? = this?.takeIf { it.isNotBlank() }
