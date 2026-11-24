package build.raft.app

import build.raft.app.model.ConversationKind

@RaftCoreInternalApi object RaftThreadSurfaceKind {
    const val Channel = "channel"
    const val DirectMessage = "dm"
    const val ReplyThread = "thread"

    /**
     * Whether "Reply in Thread" (and the thread-opening affordances behind it) is offered on rows of
     * this surface. Threads cannot be nested, so a reply thread never offers it (web hides it with
     * `hideThreadActions` in ThreadPanel). A task v2 conversation is a sub-conversation rendered by
     * the same ThreadPanel, so it never offers it either: a tap on one of its rows used to try a
     * nested thread that cannot route and toasted "Unable to open thread" (artin feedback 11-03).
     * This is the single source: payloads and slices derive from it instead of storing a flag
     * every constructor must remember to set.
     */
    fun allowsReplyInThread(surfaceKind: String, channelKind: String? = null): Boolean =
        surfaceKind != ReplyThread &&
            channelKind.normalizedThreadChannelKind() != RaftTaskConversationChannelKind
}

/** Wire value for a joint channel's kind (not a surface — joints render on the channel surface). */
@RaftCoreInternalApi const val JointChannelKindWireValue = "joint"

/**
 * Comment-host wire validators (t432 PR-B): the same two-line validation was duplicated
 * byte-for-byte in four wire converters. A comment host's `type` is a channel or reply
 * thread; its `routeKind` routes as a channel or dm. Unknown values reject (null).
 */
@RaftCoreInternalApi fun String.commentHostTypeOrNull(): String? =
    takeIf { it == RaftThreadSurfaceKind.Channel || it == RaftThreadSurfaceKind.ReplyThread }

@RaftCoreInternalApi fun String.commentHostRouteKindOrNull(): String? =
    takeIf { it == RaftThreadSurfaceKind.Channel || it == RaftThreadSurfaceKind.DirectMessage }

@RaftCoreInternalApi fun String?.isMemberScopedThreadChannelType(): Boolean =
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
@RaftCoreInternalApi fun ConversationKind?.threadParentSurfaceKindOrNull(): String? =
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
@RaftCoreInternalApi fun String?.parsedThreadParentSurfaceKindOrNull(): String? =
    parseConversationKindOrNull(this).threadParentSurfaceKindOrNull()

@RaftCoreInternalApi fun typedReplyParentConversationKindOrNull(
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

@RaftCoreInternalApi fun ConversationKind?.threadChannelKindWireValue(): String? =
    // Same table as the #417 bridge projection (parity pinned by ConversationKindContractTest).
    this?.toWireChannelKindOrNull()

@RaftCoreInternalApi fun String?.normalizedThreadChannelKind(): String? =
    // Verbatim passthrough by design (contract rule 4): channel-kind strings may carry
    // visibility ("public"/"private") that a kind parse would collapse; normalization only.
    this?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
