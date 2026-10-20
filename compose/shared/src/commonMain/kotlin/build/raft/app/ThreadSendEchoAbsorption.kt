package build.raft.app

/**
 * The SINGLE absorption point for canonical send echoes.
 *
 * Primary matching is exact `random_id` (wire field `randomId`, artin ruling
 * 2026-07-07 / task #83) echoed by the server. The old content heuristic is a
 * migration fallback only when the canonical row lacks `randomId`; remove it
 * after the randomId-capable server is fully deployed.
 *
 * Rules until then:
 * - Every merge path that folds canonical messages into a timeline holding
 *   local pending shadows MUST call this function. Do not inline or fork the
 *   matching logic at call sites.
 * - Do not add new heuristic matching dimensions (timestamps are forbidden in
 *   decision paths; see binding doc §3 obligation 5).
 */
internal fun List<RaftThreadMessageItem>.absorbingCanonicalSendEchoes(
    canonicalMessages: List<RaftThreadMessageItem>,
    matchPolicy: ThreadSendEchoMatchPolicy = ThreadSendEchoMatchPolicy.LegacyCompatible
): List<RaftThreadMessageItem> {
    if (canonicalMessages.isEmpty()) return this
    val retained =
        filterNot { localMessage ->
            localMessage.isPendingLocalSendShadow() &&
                canonicalMessages.any { canonicalMessage ->
                    localMessage.matchesCanonicalSendEcho(canonicalMessage, matchPolicy)
                }
        }
    return if (retained.size == size) this else retained
}

internal const val ThreadTransientLocalSendIdPrefix = "local-send-"

internal enum class ThreadSendEchoMatchPolicy {
    ExactRandomId,
    LegacyCompatible
}

internal fun RaftThreadMessageItem.isTransientLocalSendingMessage(): Boolean =
    id.startsWith(ThreadTransientLocalSendIdPrefix) &&
        deliveryState.equals(ThreadMessageDeliverySending, ignoreCase = true)

/**
 * A canonical random-id echo also retires a locally failed shadow.  The
 * failed state is still retryable while no canonical proof exists, but once
 * the server echoes the same idempotency key it is no longer an unresolved
 * failure and must not remain beside the canonical bubble.
 */
private fun RaftThreadMessageItem.isPendingLocalSendShadow(): Boolean =
    id.startsWith(ThreadTransientLocalSendIdPrefix) &&
        pendingSendContext != null &&
        (
            deliveryState.equals(ThreadMessageDeliverySending, ignoreCase = true) ||
                deliveryState.equals(ThreadMessageDeliveryFailed, ignoreCase = true)
        )

private fun RaftThreadMessageItem.matchesCanonicalSendEcho(
    canonicalMessage: RaftThreadMessageItem,
    matchPolicy: ThreadSendEchoMatchPolicy
): Boolean {
    val context = pendingSendContext ?: return false
    if (!isPendingLocalSendShadow()) return false
    if (canonicalMessage.id == id || canonicalMessage.isTransientLocalSendingMessage()) return false
    if (canonicalMessage.pendingSendContext != null) return false
    if (!canonicalMessage.isOwn) return false
    val localRandomId = context.randomId?.takeIf { it.isNotBlank() }
    val canonicalRandomId = canonicalMessage.randomId?.takeIf { it.isNotBlank() }
    if (canonicalRandomId != null) return localRandomId == canonicalRandomId
    // A failed shadow may only be retired by exact random-id proof.  The
    // legacy content fallback predates durable failure state and could turn a
    // retryable error into a false success.
    if (matchPolicy == ThreadSendEchoMatchPolicy.ExactRandomId ||
        deliveryState.equals(ThreadMessageDeliveryFailed, ignoreCase = true)
    ) return false
    if (authorId.isNotBlank() && canonicalMessage.authorId.isNotBlank() && authorId != canonicalMessage.authorId) {
        return false
    }
    if (context.body != canonicalMessage.body) return false
    if (context.asTask != (canonicalMessage.taskId != null)) return false
    val pendingAttachmentIds = context.attachmentIds.toSet()
    val canonicalAttachmentIds = canonicalMessage.attachments.map { attachment -> attachment.id }.toSet()
    return pendingAttachmentIds == canonicalAttachmentIds
}
