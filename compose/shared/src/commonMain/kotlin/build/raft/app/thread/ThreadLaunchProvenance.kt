package build.raft.app

import build.raft.app.data.ThreadRouteId
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ThreadConversation
import build.raft.app.model.threadIdentity
import build.raft.app.model.topLevelThreadId

/**
 * Typed reply-stream input at the launch-construction boundary.
 *
 * The existing [ThreadRouteId.ReplyThread] still carries the legacy parent-channel sentinel
 * while Store migration is staged. Requiring this explicit companion state prevents new
 * construction code from treating that sentinel as a resolved stream. A later Store migration
 * can move this state into Store and remove the sentinel from the runtime route model.
 */
internal sealed interface ThreadLaunchReplyStream {
    data object Unresolved : ThreadLaunchReplyStream

    data class Resolved(val channelId: ChannelId) : ThreadLaunchReplyStream
}

/**
 * Stable, non-raw identity digest for diagnostics only.
 *
 * This unkeyed wire-derived value must never authorize a candidate, side effect, cache restore,
 * or lifecycle transition. Those gates compare the full typed identity plus Store-owned runtime
 * authority; the digest exists only for structured telemetry and test equality.
 */
internal data class ThreadLaunchProvenanceDigest(
    val schemaVersion: Int = 1,
    val value: String
) {
    val diagnosticOnly: Boolean
        get() = true
}

internal sealed class ThreadLaunchIdentityConstructionError(val reason: String) {
    data object BlankRouteIdentity : ThreadLaunchIdentityConstructionError("blank_route_identity")
    data object UnsupportedRouteId : ThreadLaunchIdentityConstructionError("unsupported_route_id")
    data object RouteKindMismatch : ThreadLaunchIdentityConstructionError("route_kind_mismatch")
    data object ReplyParentKindMissing : ThreadLaunchIdentityConstructionError("reply_parent_kind_missing")
    data object ReplyStreamMismatch : ThreadLaunchIdentityConstructionError("reply_stream_mismatch")
    data object UnexpectedReplyMetadata : ThreadLaunchIdentityConstructionError("unexpected_reply_metadata")
    data object UnexpectedDirectMessagePeer : ThreadLaunchIdentityConstructionError("unexpected_dm_peer")
    data object MalformedLegacyKind : ThreadLaunchIdentityConstructionError("malformed_legacy_kind")
    data object MissingReplyParentPair : ThreadLaunchIdentityConstructionError("missing_reply_parent_pair")
    data object ConflictingLegacyIdentity : ThreadLaunchIdentityConstructionError("conflicting_legacy_identity")
}

internal sealed interface ThreadLaunchIdentityConstructionResult {
    data class Accepted(
        val identity: ThreadLaunchConversationIdentity,
        val routeId: ThreadRouteId,
        val parentConversationKind: ConversationKind?,
        val replyStream: ThreadLaunchReplyStream?,
        val digest: ThreadLaunchProvenanceDigest
    ) : ThreadLaunchIdentityConstructionResult

    data class Rejected(
        val error: ThreadLaunchIdentityConstructionError
    ) : ThreadLaunchIdentityConstructionResult
}

/**
 * The single typed construction API for new Thread launch provenance.
 *
 * It deliberately builds the already-established [ThreadLaunchConversationIdentity] and
 * [ThreadRouteId] types instead of introducing another locator hierarchy. Legacy flat wire
 * fields must first pass through their adapter and arrive here as a complete typed route.
 */
internal fun constructThreadLaunchConversationIdentity(
    routeId: ThreadRouteId,
    kind: ConversationKind,
    parentConversationKind: ConversationKind? = null,
    parentChannelType: String? = null,
    replyStream: ThreadLaunchReplyStream? = null,
    channelType: String? = null,
    dmPeerIdentity: MemberDetailIdentity? = null
): ThreadLaunchIdentityConstructionResult {
    val identity =
        when (routeId) {
            is ThreadRouteId.ChannelThread -> {
                if (routeId.channelId.value.isBlank()) {
                    return ThreadLaunchIdentityConstructionResult.Rejected(
                        ThreadLaunchIdentityConstructionError.BlankRouteIdentity
                    )
                }
                if (kind !in setOf(ConversationKind.Channel, ConversationKind.Joint, ConversationKind.DirectMessage)) {
                    return ThreadLaunchIdentityConstructionResult.Rejected(
                        ThreadLaunchIdentityConstructionError.RouteKindMismatch
                    )
                }
                if (parentConversationKind != null || parentChannelType != null || replyStream != null) {
                    return ThreadLaunchIdentityConstructionResult.Rejected(
                        ThreadLaunchIdentityConstructionError.UnexpectedReplyMetadata
                    )
                }
                if (dmPeerIdentity != null && kind != ConversationKind.DirectMessage) {
                    return ThreadLaunchIdentityConstructionResult.Rejected(
                        ThreadLaunchIdentityConstructionError.UnexpectedDirectMessagePeer
                    )
                }
                val normalizedChannelType = channelType.normalizedThreadChannelKind()
                val parsedChannelType = parseConversationKindOrNull(normalizedChannelType)
                val channelTypeMatchesKind =
                    when (kind) {
                        ConversationKind.Channel ->
                            normalizedChannelType == null || parsedChannelType == ConversationKind.Channel
                        ConversationKind.Joint ->
                            normalizedChannelType == null || parsedChannelType == ConversationKind.Joint
                        ConversationKind.DirectMessage ->
                            normalizedChannelType == null || parsedChannelType == ConversationKind.DirectMessage
                        else -> false
                    }
                if (!channelTypeMatchesKind) {
                    return ThreadLaunchIdentityConstructionResult.Rejected(
                        ThreadLaunchIdentityConstructionError.RouteKindMismatch
                    )
                }
                ThreadLaunchConversationIdentity(
                    threadId = topLevelThreadId(routeId.channelId),
                    channelId = routeId.channelId,
                    channelType =
                        when (kind) {
                            ConversationKind.Joint -> JointChannelKindWireValue
                            ConversationKind.DirectMessage -> RaftThreadSurfaceKind.DirectMessage
                            else -> normalizedChannelType ?: RaftThreadSurfaceKind.Channel
                        },
                    kind = kind,
                    dmPeerIdentity = dmPeerIdentity.takeIf { kind == ConversationKind.DirectMessage }
                )
            }

            is ThreadRouteId.ReplyThread -> {
                if (
                    routeId.parentChannelId.value.isBlank() ||
                    routeId.parentMessageId.value.isBlank()
                ) {
                    return ThreadLaunchIdentityConstructionResult.Rejected(
                        ThreadLaunchIdentityConstructionError.BlankRouteIdentity
                    )
                }
                if (kind != ConversationKind.Thread) {
                    return ThreadLaunchIdentityConstructionResult.Rejected(
                        ThreadLaunchIdentityConstructionError.RouteKindMismatch
                    )
                }
                if (channelType != null) {
                    return ThreadLaunchIdentityConstructionResult.Rejected(
                        ThreadLaunchIdentityConstructionError.UnexpectedReplyMetadata
                    )
                }
                val parentKind =
                    parentConversationKind?.takeIf {
                        it == ConversationKind.Channel ||
                            it == ConversationKind.Joint ||
                            it == ConversationKind.DirectMessage
                    } ?: return ThreadLaunchIdentityConstructionResult.Rejected(
                        ThreadLaunchIdentityConstructionError.ReplyParentKindMissing
                    )
                val normalizedParentChannelType = parentChannelType.normalizedThreadChannelKind()
                val parsedParentChannelType = parseConversationKindOrNull(normalizedParentChannelType)
                val parentChannelTypeMatchesKind =
                    when (parentKind) {
                        ConversationKind.Channel ->
                            normalizedParentChannelType == null ||
                                parsedParentChannelType == ConversationKind.Channel
                        ConversationKind.Joint ->
                            normalizedParentChannelType == null || parsedParentChannelType == ConversationKind.Joint
                        ConversationKind.DirectMessage ->
                            normalizedParentChannelType == null ||
                                parsedParentChannelType == ConversationKind.DirectMessage
                        else -> false
                    }
                if (!parentChannelTypeMatchesKind) {
                    return ThreadLaunchIdentityConstructionResult.Rejected(
                        ThreadLaunchIdentityConstructionError.RouteKindMismatch
                    )
                }
                val resolvedStream =
                    when (replyStream) {
                        ThreadLaunchReplyStream.Unresolved -> {
                            if (routeId.threadChannelId != routeId.parentChannelId) {
                                return ThreadLaunchIdentityConstructionResult.Rejected(
                                    ThreadLaunchIdentityConstructionError.ReplyStreamMismatch
                                )
                            }
                            null
                        }

                        is ThreadLaunchReplyStream.Resolved -> {
                            if (
                                replyStream.channelId.value.isBlank() ||
                                replyStream.channelId == routeId.parentChannelId ||
                                replyStream.channelId != routeId.threadChannelId
                            ) {
                                return ThreadLaunchIdentityConstructionResult.Rejected(
                                    ThreadLaunchIdentityConstructionError.ReplyStreamMismatch
                                )
                            }
                            replyStream.channelId
                        }

                        null -> return ThreadLaunchIdentityConstructionResult.Rejected(
                            ThreadLaunchIdentityConstructionError.ReplyStreamMismatch
                        )
                    }
                if (dmPeerIdentity != null) {
                    return ThreadLaunchIdentityConstructionResult.Rejected(
                        ThreadLaunchIdentityConstructionError.UnexpectedDirectMessagePeer
                    )
                }
                ThreadLaunchConversationIdentity(
                    threadId = threadIdentity(routeId.parentChannelId, routeId.parentMessageId),
                    channelId = routeId.parentChannelId,
                    thread =
                        ThreadConversation(
                            parentChannelId = routeId.parentChannelId,
                            parentMessageId = routeId.parentMessageId,
                            streamChannelId = resolvedStream,
                            parentSurfaceKind = parentKind.threadParentSurfaceKindOrNull(),
                            parentChannelKind =
                                when (parentKind) {
                                    ConversationKind.Channel ->
                                        normalizedParentChannelType ?: RaftThreadSurfaceKind.Channel
                                    ConversationKind.Joint -> JointChannelKindWireValue
                                    ConversationKind.DirectMessage -> RaftThreadSurfaceKind.DirectMessage
                                    else -> null
                                }
                        ),
                    kind = ConversationKind.Thread
                )
            }

            is ThreadRouteId.FocusedThread -> return ThreadLaunchIdentityConstructionResult.Rejected(
                ThreadLaunchIdentityConstructionError.UnsupportedRouteId
            )
        }
    val digest =
        threadLaunchProvenanceDigest(
            identity = identity,
            routeId = routeId,
            kind = kind,
            parentConversationKind = parentConversationKind,
            replyStream = replyStream,
            dmPeerIdentity = dmPeerIdentity
        )
    return ThreadLaunchIdentityConstructionResult.Accepted(
        identity = identity,
        routeId = routeId,
        parentConversationKind = parentConversationKind,
        replyStream = replyStream,
        digest = digest
    )
}

private fun threadLaunchProvenanceDigest(
    identity: ThreadLaunchConversationIdentity,
    routeId: ThreadRouteId,
    kind: ConversationKind,
    parentConversationKind: ConversationKind?,
    replyStream: ThreadLaunchReplyStream?,
    dmPeerIdentity: MemberDetailIdentity?
): ThreadLaunchProvenanceDigest {
    val canonical = buildString {
        appendDigestPart("thread-launch-v1")
        appendDigestPart(kind.name)
        appendDigestPart(parentConversationKind?.name.orEmpty())
        appendDigestPart(identity.channelType.normalizedThreadChannelKind().orEmpty())
        appendDigestPart(identity.thread?.parentChannelKind.normalizedThreadChannelKind().orEmpty())
        appendDigestPart(routeId.serverId?.value.orEmpty())
        when (routeId) {
            is ThreadRouteId.ChannelThread -> {
                appendDigestPart("channel")
                appendDigestPart(routeId.channelId.value)
            }

            is ThreadRouteId.ReplyThread -> {
                appendDigestPart("reply")
                appendDigestPart(routeId.parentChannelId.value)
                appendDigestPart(routeId.parentMessageId.value)
                when (replyStream) {
                    ThreadLaunchReplyStream.Unresolved -> appendDigestPart("unresolved")
                    is ThreadLaunchReplyStream.Resolved -> {
                        appendDigestPart("resolved")
                        appendDigestPart(replyStream.channelId.value)
                    }
                    null -> appendDigestPart("missing")
                }
            }

            is ThreadRouteId.FocusedThread -> {
                appendDigestPart("focused")
                appendDigestPart(routeId.channelId.value)
                appendDigestPart(routeId.focusedMessageId.value)
            }
        }
        appendDigestPart(dmPeerIdentity?.memberId?.value.orEmpty())
        appendDigestPart(dmPeerIdentity?.kind?.name.orEmpty())
        appendDigestPart(dmPeerIdentity?.sourceServerId?.value.orEmpty())
    }
    return ThreadLaunchProvenanceDigest(value = canonical.fnv1a64Hex())
}

private fun StringBuilder.appendDigestPart(value: String) {
    append(value.length)
    append(':')
    append(value)
    append('|')
}

private fun String.fnv1a64Hex(): String {
    var hash = 0xcbf29ce484222325uL
    for (byte in encodeToByteArray()) {
        hash = hash xor byte.toUByte().toULong()
        hash *= 0x100000001b3uL
    }
    return hash.toString(16).padStart(16, '0')
}
