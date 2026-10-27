package build.raft.app.network.sync

import build.raft.app.canonical.message.CanonicalConversationContextWire

const val CANONICAL_MESSAGE_V2_INVALID_CONTEXT: String = "invalid_canonical_message_context"

data class CanonicalMessageV2NormalizationInput(
    val partition: DurablePartitionKey,
    val scopeId: String,
    val fact: CanonicalMessageNodeV2,
    val producerContext: CanonicalConversationContextWire,
    /** The current producer's raw child-thread anchor, kept orthogonal to top-level container kind. */
    val childReplyScopeId: String? = null,
)

sealed interface CanonicalMessageV2NormalizationResult {
    data class Normalized(val envelope: CanonicalMessageEnvelopeV2) : CanonicalMessageV2NormalizationResult
    data class Rejected(val diagnostic: String) : CanonicalMessageV2NormalizationResult
}

/**
 * Pure full-v6 context/relation normalizer. The host still owns wire extraction; this function is the
 * production boundary that removes raw conversation/thread fields before authoritative bytes exist.
 */
fun normalizeCanonicalMessageV2(
    input: CanonicalMessageV2NormalizationInput,
): CanonicalMessageV2NormalizationResult {
    if (
        input.partition.consumerId != SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID ||
        input.scopeId.isBlank() ||
        input.scopeId != input.scopeId.trim() ||
        input.childReplyScopeId?.let { it.isBlank() || it != it.trim() } == true
    ) return CanonicalMessageV2NormalizationResult.Rejected(CANONICAL_MESSAGE_V2_INVALID_CONTEXT)

    val serverId = input.partition.serverId
    val kind = input.producerContext.channelType
    val envelope = when (kind) {
        "channel", "private", "dm", "joint" -> {
            if (
                input.producerContext.parentChannelId != null ||
                input.producerContext.parentChannelType != null ||
                input.producerContext.parentMessageId != null
            ) return CanonicalMessageV2NormalizationResult.Rejected(CANONICAL_MESSAGE_V2_INVALID_CONTEXT)
            CanonicalMessageEnvelopeV2(
                fact = input.fact,
                context = CanonicalConversationContextV2.TopLevel(
                    scope = topLevelScope(kind, serverId, input.scopeId)
                        ?: return CanonicalMessageV2NormalizationResult.Rejected(CANONICAL_MESSAGE_V2_INVALID_CONTEXT),
                ),
                childReplyScope = input.childReplyScopeId?.let { ReplyScopeRefV2(serverId, it) },
                replyMembership = null,
            )
        }
        CANONICAL_MESSAGE_V2_TASK_SCOPE_KIND -> {
            // Task v2 (slock `messageService.ts` buildFrontendConversationContext): a task
            // conversation names its parent channel (+ type) and no parent message, and its messages
            // never anchor a reply thread (sub-conversations do not nest).
            if (input.childReplyScopeId != null || input.producerContext.parentMessageId != null) {
                return CanonicalMessageV2NormalizationResult.Rejected(CANONICAL_MESSAGE_V2_INVALID_CONTEXT)
            }
            val parentScopeId = input.producerContext.parentChannelId.canonicalOrNull()
                ?: return CanonicalMessageV2NormalizationResult.Rejected(CANONICAL_MESSAGE_V2_INVALID_CONTEXT)
            val parentKind = input.producerContext.parentChannelType
            if (
                parentScopeId == input.scopeId ||
                (parentKind != null && parentKind !in CANONICAL_MESSAGE_V2_REPLY_PARENT_SCOPE_KINDS)
            ) return CanonicalMessageV2NormalizationResult.Rejected(CANONICAL_MESSAGE_V2_INVALID_CONTEXT)
            CanonicalMessageEnvelopeV2(
                fact = input.fact,
                context = CanonicalConversationContextV2.TopLevel(
                    scope = TopLevelScopeRefV2.Task(
                        serverId = serverId,
                        scopeId = input.scopeId,
                        parentScopeId = parentScopeId,
                    ),
                ),
                childReplyScope = null,
                replyMembership = null,
            )
        }
        "thread" -> {
            if (input.childReplyScopeId != null) {
                return CanonicalMessageV2NormalizationResult.Rejected(CANONICAL_MESSAGE_V2_INVALID_CONTEXT)
            }
            val parentScopeId = input.producerContext.parentChannelId.canonicalOrNull()
                ?: return CanonicalMessageV2NormalizationResult.Rejected(CANONICAL_MESSAGE_V2_INVALID_CONTEXT)
            val parentKind = input.producerContext.parentChannelType.canonicalOrNull()
                ?: return CanonicalMessageV2NormalizationResult.Rejected(CANONICAL_MESSAGE_V2_INVALID_CONTEXT)
            val rootMessageId = input.producerContext.parentMessageId.canonicalOrNull()
                ?: return CanonicalMessageV2NormalizationResult.Rejected(CANONICAL_MESSAGE_V2_INVALID_CONTEXT)
            val replyScope = ReplyScopeRefV2(serverId, input.scopeId)
            val root = MessageRefV2(serverId, rootMessageId)
            CanonicalMessageEnvelopeV2(
                fact = input.fact,
                context = CanonicalConversationContextV2.Thread(
                    replyScope = replyScope,
                    parentTopLevelScope = topLevelScope(parentKind, serverId, parentScopeId)
                        ?: return CanonicalMessageV2NormalizationResult.Rejected(CANONICAL_MESSAGE_V2_INVALID_CONTEXT),
                    rootMessageRef = root,
                ),
                childReplyScope = null,
                replyMembership = ReplyMembershipV2(root = root, parentScope = replyScope),
            )
        }
        else -> return CanonicalMessageV2NormalizationResult.Rejected(CANONICAL_MESSAGE_V2_INVALID_CONTEXT)
    }

    val diagnostic = validateCanonicalMessageV2(envelope, serverId)
    return if (diagnostic == null) {
        CanonicalMessageV2NormalizationResult.Normalized(envelope)
    } else {
        CanonicalMessageV2NormalizationResult.Rejected(diagnostic)
    }
}

private fun topLevelScope(kind: String, serverId: String, scopeId: String): TopLevelScopeRefV2? =
    when (kind) {
        "channel" -> TopLevelScopeRefV2.Channel(serverId, scopeId)
        "private" -> TopLevelScopeRefV2.Private(serverId, scopeId)
        "dm" -> TopLevelScopeRefV2.DirectMessage(serverId, scopeId)
        "joint" -> TopLevelScopeRefV2.Joint(serverId, scopeId)
        else -> null
    }

private fun String?.canonicalOrNull(): String? = this?.takeIf { it.isNotBlank() && it == it.trim() }
