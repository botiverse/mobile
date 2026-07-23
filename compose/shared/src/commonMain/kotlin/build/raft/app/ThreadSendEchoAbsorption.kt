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
internal fun List<KuiklyThreadMessageItem>.absorbingCanonicalSendEchoes(
    canonicalMessages: List<KuiklyThreadMessageItem>,
    matchPolicy: ThreadSendEchoMatchPolicy = ThreadSendEchoMatchPolicy.LegacyCompatible
): List<KuiklyThreadMessageItem> {
    if (canonicalMessages.isEmpty()) return this
    val retained =
        filterNot { localMessage ->
            localMessage.isTransientLocalSendingMessage() &&
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

internal fun KuiklyThreadMessageItem.isTransientLocalSendingMessage(): Boolean =
    id.startsWith(ThreadTransientLocalSendIdPrefix) &&
        deliveryState.equals(ThreadMessageDeliverySending, ignoreCase = true)

private fun KuiklyThreadMessageItem.matchesCanonicalSendEcho(
    canonicalMessage: KuiklyThreadMessageItem,
    matchPolicy: ThreadSendEchoMatchPolicy
): Boolean {
    val context = pendingSendContext ?: return false
    if (!isTransientLocalSendingMessage()) return false
    if (canonicalMessage.id == id || canonicalMessage.isTransientLocalSendingMessage()) return false
    if (canonicalMessage.pendingSendContext != null) return false
    if (!canonicalMessage.isOwn) return false
    val localRandomId = context.randomId?.takeIf { it.isNotBlank() }
    val canonicalRandomId = canonicalMessage.randomId?.takeIf { it.isNotBlank() }
    if (canonicalRandomId != null) return localRandomId == canonicalRandomId
    if (matchPolicy == ThreadSendEchoMatchPolicy.ExactRandomId) return false
    if (authorId.isNotBlank() && canonicalMessage.authorId.isNotBlank() && authorId != canonicalMessage.authorId) {
        return false
    }
    if (context.body != canonicalMessage.body) return false
    if (context.asTask != (canonicalMessage.taskId != null)) return false
    val pendingAttachmentIds = context.attachmentIds.toSet()
    val canonicalAttachmentIds = canonicalMessage.attachments.map { attachment -> attachment.id }.toSet()
    return pendingAttachmentIds == canonicalAttachmentIds
}
