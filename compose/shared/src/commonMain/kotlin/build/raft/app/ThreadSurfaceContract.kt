package build.raft.app

import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary

internal object RaftThreadSurfaceKind {
    const val Channel = "channel"
    const val DirectMessage = "dm"
    const val ReplyThread = "thread"

    /**
     * Whether "Reply in Thread" (and the thread-opening affordances behind it) is offered on rows of
     * this surface. Threads cannot be nested, so a reply thread never offers it (web hides it with
     * `hideThreadActions` in ThreadPanel). This is the single source: payloads and slices derive
     * from it instead of storing a flag every constructor must remember to set.
     */
    fun allowsReplyInThread(surfaceKind: String): Boolean = surfaceKind != ReplyThread
}

/** Wire value for a joint channel's kind (not a surface — joints render on the channel surface). */
internal const val JointChannelKindWireValue = "joint"

/**
 * Comment-host wire validators (t432 PR-B): the same two-line validation was duplicated
 * byte-for-byte in four wire converters. A comment host's `type` is a channel or reply
 * thread; its `routeKind` routes as a channel or dm. Unknown values reject (null).
 */
internal fun String.commentHostTypeOrNull(): String? =
    takeIf { it == RaftThreadSurfaceKind.Channel || it == RaftThreadSurfaceKind.ReplyThread }

internal fun String.commentHostRouteKindOrNull(): String? =
    takeIf { it == RaftThreadSurfaceKind.Channel || it == RaftThreadSurfaceKind.DirectMessage }

// t5/#419 finale: the ConversationSummary thread-surface family delegates to the canonical
// ThreadLaunchConversationIdentity extensions (the conversion is a plain field copy), so the
// semantics live exactly once. Kind projections route through the #417 contract.

internal fun ConversationSummary.threadSurfaceKind(): String =
    toThreadLaunchConversationIdentity().threadSurfaceKind()

internal fun ConversationSummary.usesMemberScopedMentions(): Boolean =
    toThreadLaunchConversationIdentity().usesMemberScopedMentions()

internal fun String?.isMemberScopedThreadChannelType(): Boolean =
    // Member-scoping heuristic over the row's channelType — intentionally NOT a kind parse
    // (visibility values like "private" scope mentions too); single copy, both families use it.
    when (this?.trim()?.lowercase()) {
        "dm", "direct", "direct_message", "direct-message", "private", "joint" -> true
        else -> false
    }

/**
 * Qualified parent-surface projection: a reply thread's PARENT can only be a dm or channel
 * surface — Thread/System/Inbox (and unknown) are not valid parent shapes and project to
 * null for the caller to treat as unresolved. This is intentionally narrower than
 * [toRaftSurfaceKind] (which maps Thread to "thread").
 */
internal fun ConversationKind?.threadParentSurfaceKindOrNull(): String? =
    when (this) {
        ConversationKind.DirectMessage -> RaftThreadSurfaceKind.DirectMessage
        ConversationKind.Channel,
        ConversationKind.Joint -> RaftThreadSurfaceKind.Channel
        else -> null
    }

/**
 * String form of the qualified parent projection: parse through the #417 contract, then
 * qualify. Exact parity with the legacy per-file vocab tables (incl. "direct" -> dm and
 * "thread"/"system" -> null) — the vocabulary now lives only in the contract.
 */
internal fun String?.parsedThreadParentSurfaceKindOrNull(): String? =
    parseConversationKindOrNull(this).threadParentSurfaceKindOrNull()

internal fun typedReplyParentConversationKindOrNull(
    parentSurfaceKind: String?,
    parentChannelKind: String?
): ConversationKind? {
    val channelKind = parseConversationKindOrNull(parentChannelKind)
        ?.takeIf { it == ConversationKind.Channel || it == ConversationKind.Joint || it == ConversationKind.DirectMessage }
    if (channelKind != null) return channelKind
    return ConversationKind.DirectMessage.takeIf {
        parseConversationKindOrNull(parentSurfaceKind) == ConversationKind.DirectMessage
    }
}

internal fun ConversationSummary.threadStreamChannelId(): String? =
    toThreadLaunchConversationIdentity().threadStreamChannelId()

internal fun ConversationSummary.threadParentChannelId(): String? =
    toThreadLaunchConversationIdentity().threadParentChannelId()

internal fun ConversationSummary.threadParentMessageId(): String? =
    toThreadLaunchConversationIdentity().threadParentMessageId()

internal fun ConversationSummary.threadParentSurfaceKind(): String? =
    toThreadLaunchConversationIdentity().threadParentSurfaceKind()

internal fun ConversationSummary.threadParentChannelKind(): String? =
    toThreadLaunchConversationIdentity().threadParentChannelKind()

internal fun ConversationKind?.threadChannelKindWireValue(): String? =
    // Same table as the #417 bridge projection (parity pinned by ConversationKindContractTest).
    this?.toWireChannelKindOrNull()

internal fun String?.normalizedThreadChannelKind(): String? =
    // Verbatim passthrough by design (contract rule 4): channel-kind strings may carry
    // visibility ("public"/"private") that a kind parse would collapse; normalization only.
    this?.trim()?.lowercase()?.takeIf { it.isNotBlank() }

internal fun threadSurfaceComposerPlaceholder(
    surfaceKind: String,
    displayName: String,
    fallbackId: String
): String =
    when (surfaceKind) {
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
