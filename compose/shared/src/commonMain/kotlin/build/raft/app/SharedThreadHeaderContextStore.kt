package build.raft.app

import build.raft.app.model.ConversationSummary
import build.raft.app.model.safeDisplayNameOrNull

internal data class SharedThreadHeaderContext(
    val title: String,
    val subtitle: String?,
    val originMessage: KuiklyThreadMessageItem?,
    val originPreview: String?,
    val originParentMessageId: String?,
    val originStatus: String,
    val originErrorCode: String? = null
)

internal object SharedThreadHeaderContextStore {
    fun launchContext(
        surfaceKind: String,
        parentMessageId: String?,
        dmPeerName: String? = null
    ): SharedThreadHeaderContext =
        SharedThreadHeaderContext(
            title = launchTitleFor(surfaceKind, dmPeerName),
            subtitle = null,
            originMessage = null,
            originPreview = null,
            originParentMessageId =
                if (surfaceKind == KuiklyThreadSurfaceKind.ReplyThread) {
                    parentMessageId?.takeIf { it.isNotBlank() }
                } else {
                    null
                },
            originStatus =
                if (surfaceKind == KuiklyThreadSurfaceKind.ReplyThread && !parentMessageId.isNullOrBlank()) {
                    "loading"
                } else {
                    "none"
                }
        )

    fun conversationContext(
        surfaceKind: String,
        conversation: ConversationSummary,
        originMessage: KuiklyThreadMessageItem?,
        parentMessageId: String?,
        originFallback: ThreadLaunchOriginFallback? = null,
        dmPeerName: String? = null,
        originPending: Boolean = false
    ): SharedThreadHeaderContext {
        val isReplyThread = surfaceKind == KuiklyThreadSurfaceKind.ReplyThread
        val routeIdentity = conversation.resolveThreadRouteIdentity()
        val resolvedOriginParentMessageId =
            if (isReplyThread) {
                originMessage?.id
                    ?: parentMessageId?.takeIf { it.isNotBlank() }
                    ?: conversation.thread?.parentMessageId?.value
            } else {
                null
            }
        val validatedOriginFallback =
            originFallback?.takeIf { fallback ->
                isReplyThread &&
                    resolvedOriginParentMessageId != null &&
                    fallback.matchesOriginParent(
                        routeIdentity = routeIdentity,
                        parentMessageId = resolvedOriginParentMessageId
                    )
            }
        val resolvedOriginPreview =
            if (isReplyThread && originMessage == null) {
                validatedOriginFallback?.preview?.takeIf { it.isNotBlank() }
            } else {
                null
            }
        return SharedThreadHeaderContext(
            title =
                when (surfaceKind) {
                    KuiklyThreadSurfaceKind.ReplyThread -> ""
                    KuiklyThreadSurfaceKind.DirectMessage ->
                        launchTitleFor(surfaceKind, dmPeerName ?: safeConversationTitle(conversation))
                    else -> safeConversationTitle(conversation)
                },
            subtitle =
                when (surfaceKind) {
                    KuiklyThreadSurfaceKind.DirectMessage -> null
                    KuiklyThreadSurfaceKind.ReplyThread ->
                        existingThreadChannelHeaderSubtitle(conversation.subtitle)
                            ?: threadChannelHeaderSubtitle(conversation.name)
                    else -> conversation.subtitle?.takeIf { it.isNotBlank() }
                },
            originMessage = if (isReplyThread) originMessage else null,
            originPreview = resolvedOriginPreview,
            originParentMessageId = resolvedOriginParentMessageId,
            originStatus =
                when {
                    !isReplyThread -> "none"
                    originMessage != null -> "loaded"
                    resolvedOriginPreview != null -> "missing"
                    originPending && resolvedOriginParentMessageId != null -> "loading"
                    resolvedOriginParentMessageId != null || validatedOriginFallback?.expected == true -> "missing"
                    else -> "none"
                }
        )
    }

    fun launchOriginContext(
        surfaceKind: String,
        routeIdentity: ThreadRouteIdentity,
        originMessage: KuiklyThreadMessageItem?,
        parentMessageId: String?,
        originFallback: ThreadLaunchOriginFallback? = null,
        dmPeerName: String? = null
    ): SharedThreadHeaderContext {
        val isReplyThread = surfaceKind == KuiklyThreadSurfaceKind.ReplyThread
        val resolvedOriginParentMessageId =
            if (isReplyThread) {
                originMessage?.id
                    ?: parentMessageId?.takeIf { it.isNotBlank() }
            } else {
                null
            }
        val validatedOriginFallback =
            originFallback?.takeIf { fallback ->
                isReplyThread &&
                    resolvedOriginParentMessageId != null &&
                    fallback.matchesOriginParent(
                        routeIdentity = routeIdentity,
                        parentMessageId = resolvedOriginParentMessageId
                    )
            }
        val resolvedOriginPreview =
            if (isReplyThread && originMessage == null) {
                validatedOriginFallback?.preview?.takeIf { it.isNotBlank() }
            } else {
                null
            }
        return SharedThreadHeaderContext(
            title =
                when (surfaceKind) {
                    KuiklyThreadSurfaceKind.DirectMessage -> launchTitleFor(surfaceKind, dmPeerName)
                    else -> ""
                },
            subtitle = null,
            originMessage = if (isReplyThread) originMessage else null,
            originPreview = resolvedOriginPreview,
            originParentMessageId = resolvedOriginParentMessageId,
            originStatus =
                when {
                    !isReplyThread -> "none"
                    originMessage != null -> "loaded"
                    resolvedOriginPreview != null -> "missing"
                    resolvedOriginParentMessageId != null || validatedOriginFallback?.expected == true -> "missing"
                    else -> "none"
                }
        )
    }

    private fun launchTitleFor(surfaceKind: String, dmPeerName: String?): String =
        if (surfaceKind == KuiklyThreadSurfaceKind.DirectMessage) {
            threadHeaderSafeTitleOrNull(dmPeerName) ?: ""
        } else {
            ""
        }

    private fun safeConversationTitle(
        conversation: ConversationSummary
    ): String =
        conversation.safeDisplayNameOrNull().orEmpty()
}

internal fun ThreadLaunchOriginFallback.matchesOriginParent(
    routeIdentity: ThreadRouteIdentity,
    parentMessageId: String?
): Boolean {
    val normalizedParentMessageId = parentMessageId?.trim()?.takeIf { it.isNotBlank() } ?: return false
    if (this.parentMessageId.trim() != normalizedParentMessageId) return false
    val routeParentKey = routeIdentity.parentKey?.takeIf { it.isNotBlank() } ?: return true
    val fallbackParentChannelId = parentChannelId?.trim()?.takeIf { it.isNotBlank() } ?: return true
    return "$fallbackParentChannelId:$normalizedParentMessageId" == routeParentKey
}

internal fun KuiklyThreadScreenPayload.withHeaderContext(
    context: SharedThreadHeaderContext
): KuiklyThreadScreenPayload =
    copy(
        title = context.title,
        subtitle = context.subtitle,
        originMessage = context.originMessage,
        originPreview = context.originPreview,
        originParentMessageId = context.originParentMessageId,
        originStatus = context.originStatus,
        originErrorCode = context.originErrorCode
    )

internal fun KuiklyThreadScreenPayload.withLaunchHeaderContext(): KuiklyThreadScreenPayload =
    withHeaderContext(
        SharedThreadHeaderContextStore.launchContext(
            surfaceKind = surfaceKind,
            parentMessageId = originParentMessageId ?: parentMessageId,
            dmPeerName = dmPeerName
        )
    )
