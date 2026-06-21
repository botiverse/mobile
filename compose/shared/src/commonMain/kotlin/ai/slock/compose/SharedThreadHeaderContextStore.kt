package ai.slock.compose

import ai.slock.compose.model.ConversationSummary

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
            title = dmTitleFor(surfaceKind, dmPeerName),
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
        dmPeerName: String? = null
    ): SharedThreadHeaderContext {
        val isReplyThread = surfaceKind == KuiklyThreadSurfaceKind.ReplyThread
        val resolvedOriginParentMessageId =
            if (isReplyThread) {
                originMessage?.id
                    ?: parentMessageId?.takeIf { it.isNotBlank() }
                    ?: conversation.thread?.parentMessageId?.value
            } else {
                null
            }
        val resolvedOriginPreview =
            if (isReplyThread && originMessage == null) {
                conversation.originMessagePreview?.takeIf { it.isNotBlank() }
            } else {
                null
            }
        return SharedThreadHeaderContext(
            title =
                when (surfaceKind) {
                    KuiklyThreadSurfaceKind.ReplyThread -> ThreadReplyPageTitle
                    KuiklyThreadSurfaceKind.DirectMessage -> dmTitleFor(surfaceKind, dmPeerName ?: conversation.name)
                    else -> conversation.name.takeIf { it.isNotBlank() } ?: fallbackTitle(surfaceKind)
                },
            subtitle =
                when (surfaceKind) {
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
                    resolvedOriginParentMessageId != null || conversation.originMessageExpected -> "missing"
                    else -> "none"
                }
        )
    }

    private fun fallbackTitle(surfaceKind: String): String =
        when (surfaceKind) {
            KuiklyThreadSurfaceKind.ReplyThread -> ThreadReplyPageTitle
            KuiklyThreadSurfaceKind.DirectMessage -> "Direct Message"
            else -> "Channel"
        }

    private fun dmTitleFor(surfaceKind: String, dmPeerName: String?): String =
        if (surfaceKind == KuiklyThreadSurfaceKind.DirectMessage) {
            dmPeerName?.takeIf { it.isNotBlank() } ?: fallbackTitle(surfaceKind)
        } else {
            fallbackTitle(surfaceKind)
        }
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
