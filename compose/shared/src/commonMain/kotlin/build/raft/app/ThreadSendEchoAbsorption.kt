package build.raft.app

/**
 * The SINGLE absorption point for canonical send echoes.
 *
 * DEPRECATED (migration-period heuristic — do not extend):
 * this matches a locally-sent pending shadow against a canonical message by
 * content (author + body + task flag + attachment id set). Per
 * `docs/kmp-data-flow-and-sync-contract-binding-2026-07-07.md` §4 (RFC 038
 * §6), the target contract is exact-id absorption via a client-generated
 * `random_id` echoed back by the server. The server does not echo it yet;
 * once it does, this function switches to exact matching and the content
 * heuristic becomes a fallback with a removal milestone.
 *
 * Rules until then:
 * - Every merge path that folds canonical messages into a timeline holding
 *   local pending shadows MUST call this function. Do not inline or fork the
 *   matching logic at call sites.
 * - Do not add new matching dimensions (timestamps are forbidden in decision
 *   paths; see binding doc §3 obligation 5).
 */
internal fun List<KuiklyThreadMessageItem>.absorbingCanonicalSendEchoes(
    canonicalMessages: List<KuiklyThreadMessageItem>
): List<KuiklyThreadMessageItem> {
    if (canonicalMessages.isEmpty()) return this
    val retained =
        filterNot { localMessage ->
            localMessage.isTransientLocalSendingMessage() &&
                canonicalMessages.any { canonicalMessage ->
                    localMessage.matchesCanonicalSendEcho(canonicalMessage)
                }
        }
    return if (retained.size == size) this else retained
}

internal const val ThreadTransientLocalSendIdPrefix = "local-send-"

internal fun KuiklyThreadMessageItem.isTransientLocalSendingMessage(): Boolean =
    id.startsWith(ThreadTransientLocalSendIdPrefix) &&
        deliveryState.equals(ThreadMessageDeliverySending, ignoreCase = true)

private fun KuiklyThreadMessageItem.matchesCanonicalSendEcho(
    canonicalMessage: KuiklyThreadMessageItem
): Boolean {
    val context = pendingSendContext ?: return false
    if (!isTransientLocalSendingMessage()) return false
    if (canonicalMessage.id == id || canonicalMessage.isTransientLocalSendingMessage()) return false
    if (canonicalMessage.pendingSendContext != null) return false
    if (!canonicalMessage.isOwn) return false
    if (authorId.isNotBlank() && canonicalMessage.authorId.isNotBlank() && authorId != canonicalMessage.authorId) {
        return false
    }
    if (context.body != canonicalMessage.body) return false
    if (context.asTask != (canonicalMessage.taskId != null)) return false
    val pendingAttachmentIds = context.attachmentIds.toSet()
    val canonicalAttachmentIds = canonicalMessage.attachments.map { attachment -> attachment.id }.toSet()
    return pendingAttachmentIds == canonicalAttachmentIds
}
