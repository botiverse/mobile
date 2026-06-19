package ai.slock.compose

import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.ConversationSummary

internal object KuiklyThreadSurfaceKind {
    const val Channel = "channel"
    const val DirectMessage = "dm"
    const val ReplyThread = "thread"
}

internal fun ConversationSummary.threadSurfaceKind(): String =
    when (kind) {
        ConversationKind.DirectMessage -> KuiklyThreadSurfaceKind.DirectMessage
        ConversationKind.Thread -> KuiklyThreadSurfaceKind.ReplyThread
        else -> KuiklyThreadSurfaceKind.Channel
    }

internal fun ConversationSummary.usesMemberScopedMentions(): Boolean =
    kind == ConversationKind.DirectMessage ||
        kind == ConversationKind.Joint ||
        channelType.isMemberScopedThreadChannelType()

private fun String?.isMemberScopedThreadChannelType(): Boolean =
    when (this?.trim()?.lowercase()) {
        "dm", "direct", "direct_message", "direct-message", "private", "joint" -> true
        else -> false
    }

internal fun ConversationSummary.threadStreamChannelId(): String =
    thread?.streamChannelId?.value
        ?: channelId?.value
        ?: threadId.value

internal fun ConversationSummary.threadParentChannelId(): String? =
    thread?.parentChannelId?.value

internal fun ConversationSummary.threadParentMessageId(): String? =
    thread?.parentMessageId?.value

internal fun threadSurfaceComposerPlaceholder(
    surfaceKind: String,
    displayName: String,
    fallbackId: String
): String =
    when (surfaceKind) {
        KuiklyThreadSurfaceKind.DirectMessage ->
            "Message @${displayName.removePrefix("@").ifBlank { fallbackId }}"
        KuiklyThreadSurfaceKind.ReplyThread -> "Reply in thread"
        else -> "Message #${displayName.removePrefix("#").ifBlank { fallbackId }}"
    }

internal fun buildThreadSurfaceComposerPayload(
    surfaceKind: String,
    displayName: String,
    fallbackId: String,
    enabled: Boolean,
    showAsTaskToggle: Boolean = true,
    seedText: String = "",
    seedAsTask: Boolean = false,
    syncNonce: Int = 0,
    focusRequestNonce: Int = 0,
    composerMode: String = "idle",
    statusLabel: String? = null,
    mentionChannelId: String? = null,
    parentMessageId: String? = null,
    streamChannelId: String? = null
): KuiklyThreadComposerPayload =
    KuiklyThreadComposerPayload(
        seedText = seedText,
        seedAsTask = seedAsTask,
        syncNonce = syncNonce,
        focusRequestNonce = focusRequestNonce,
        placeholder =
            threadSurfaceComposerPlaceholder(
                surfaceKind = surfaceKind,
                displayName = displayName,
                fallbackId = fallbackId
            ),
        enabled = enabled,
        showAsTaskToggle = showAsTaskToggle,
        composerMode = composerMode,
        statusLabel = statusLabel,
        surfaceKind = surfaceKind,
        mentionChannelId = mentionChannelId,
        parentMessageId = parentMessageId,
        streamChannelId = streamChannelId
    )
