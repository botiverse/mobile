package build.raft.app

/** Task v2: a task conversation's composer placeholder (web `message.threadPanel.taskComposerPlaceholder`). */
internal const val ThreadTaskComposerPlaceholder = "Message this task"

internal fun threadSurfaceComposerPlaceholder(
    surfaceKind: String,
    displayName: String,
    fallbackId: String,
    /** The conversation's channel kind: a task conversation (`task`) is not addressed by its name. */
    channelKind: String? = null,
): String =
    if (channelKind.normalizedThreadChannelKind() == RaftTaskConversationChannelKind) {
        ThreadTaskComposerPlaceholder
    } else when (surfaceKind) {
        RaftThreadSurfaceKind.DirectMessage ->
            "Message @${displayName.removePrefix("@").ifBlank { fallbackId }}"
        RaftThreadSurfaceKind.ReplyThread -> "Reply in thread"
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
): RaftThreadComposerPayload =
    RaftThreadComposerPayload(
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
