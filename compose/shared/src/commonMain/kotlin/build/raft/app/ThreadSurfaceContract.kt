package build.raft.app

import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary

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

internal fun ConversationKind?.threadParentSurfaceKindOrNull(): String? =
    when (this) {
        ConversationKind.DirectMessage -> KuiklyThreadSurfaceKind.DirectMessage
        ConversationKind.Channel,
        ConversationKind.Joint -> KuiklyThreadSurfaceKind.Channel
        else -> null
    }

private fun String?.threadParentSurfaceKindOrNull(): String? =
    when (this?.trim()?.lowercase()) {
        KuiklyThreadSurfaceKind.DirectMessage,
        "direct_message",
        "direct-message",
        "directmessage",
        "direct" -> KuiklyThreadSurfaceKind.DirectMessage
        KuiklyThreadSurfaceKind.Channel,
        "public",
        "private",
        "joint",
        "group" -> KuiklyThreadSurfaceKind.Channel
        else -> null
    }

internal fun ConversationSummary.threadStreamChannelId(): String? =
    thread?.streamChannelId?.value
        ?: if (kind == ConversationKind.Thread) {
            null
        } else {
            channelId?.value ?: threadId.value
        }

internal fun ConversationSummary.threadParentChannelId(): String? =
    thread?.parentChannelId?.value

internal fun ConversationSummary.threadParentMessageId(): String? =
    thread?.parentMessageId?.value

internal fun ConversationSummary.threadParentSurfaceKind(): String? =
    thread?.parentSurfaceKind.threadParentSurfaceKindOrNull()
        ?: if (kind == ConversationKind.Thread) {
            channelType.threadParentSurfaceKindOrNull()
        } else {
            null
        }

internal fun ConversationSummary.threadParentChannelKind(): String? =
    thread?.parentChannelKind.normalizedThreadChannelKind()
        ?: if (kind == ConversationKind.Thread) {
            channelType.normalizedThreadChannelKind()
        } else {
            null
        }

internal fun ConversationKind?.threadChannelKindWireValue(): String? =
    when (this) {
        ConversationKind.Channel -> KuiklyThreadSurfaceKind.Channel
        ConversationKind.Joint -> "joint"
        ConversationKind.DirectMessage -> KuiklyThreadSurfaceKind.DirectMessage
        else -> null
    }

internal fun String?.normalizedThreadChannelKind(): String? =
    this?.trim()?.lowercase()?.takeIf { it.isNotBlank() }

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
