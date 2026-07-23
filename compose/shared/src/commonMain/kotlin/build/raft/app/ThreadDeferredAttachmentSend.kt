package build.raft.app

/**
 * Immutable attachment snapshot bound to one composer send click.
 *
 * Upload completions are recorded against the local attachment slot captured at submit time.
 * The final server attachment list is only exposed after every captured slot has resolved, and
 * is flattened in the original selection order rather than upload completion order.
 */
internal data class ThreadDeferredAttachmentSend(
    val localMessageId: String,
    val context: KuiklyThreadPendingSendContext,
    val timestampLabel: String,
    val generation: Long,
    val startedFromComposer: Boolean,
    val pendingLocalAttachmentIds: List<String>,
    val uploadedAttachmentsByLocalId: Map<String, List<KuiklyPendingAttachmentItem>> = emptyMap()
) {
    fun ownsLocalAttachment(localAttachmentId: String): Boolean =
        localAttachmentId in pendingLocalAttachmentIds

    fun recordUploadSuccess(
        localAttachmentId: String,
        attachments: List<KuiklyPendingAttachmentItem>
    ): ThreadDeferredAttachmentSend {
        if (!ownsLocalAttachment(localAttachmentId) || localAttachmentId in uploadedAttachmentsByLocalId) {
            return this
        }
        val readyAttachments = attachments.filter { attachment -> attachment.isReadyThreadAttachmentForSend() }
        if (readyAttachments.isEmpty()) return this
        return copy(
            uploadedAttachmentsByLocalId =
                uploadedAttachmentsByLocalId + (localAttachmentId to readyAttachments)
        )
    }

    fun readyContextOrNull(): KuiklyThreadPendingSendContext? {
        if (pendingLocalAttachmentIds.any { it !in uploadedAttachmentsByLocalId }) return null
        val resolvedAttachments =
            context.attachments.flatMap { attachment ->
                uploadedAttachmentsByLocalId[attachment.id] ?: listOf(attachment)
            }
        return context.copy(
            attachmentIds = resolvedAttachments.readyThreadAttachmentIdsForSend(),
            attachments = resolvedAttachments
        )
    }
}

internal fun newThreadDeferredAttachmentSend(
    localMessageId: String,
    context: KuiklyThreadPendingSendContext,
    timestampLabel: String,
    generation: Long,
    startedFromComposer: Boolean
): ThreadDeferredAttachmentSend =
    ThreadDeferredAttachmentSend(
        localMessageId = localMessageId,
        context = context,
        timestampLabel = timestampLabel,
        generation = generation,
        startedFromComposer = startedFromComposer,
        pendingLocalAttachmentIds =
            context.attachments
                .filterNot { attachment -> attachment.isReadyThreadAttachmentForSend() }
                .map { attachment -> attachment.id }
                .filter { id -> id.isNotBlank() }
                .distinct()
    )

internal data class ThreadAttachmentUploadAttemptFence(
    val nextAttemptToken: Long = 0L,
    val activeAttemptTokens: Map<String, Long> = emptyMap(),
    val discardedAttachmentIds: Set<String> = emptySet()
) {
    fun begin(localAttachmentId: String): ThreadAttachmentUploadAdmission {
        val token = nextAttemptToken + 1L
        return ThreadAttachmentUploadAdmission(
            fence =
                copy(
                    nextAttemptToken = token,
                    activeAttemptTokens = activeAttemptTokens + (localAttachmentId to token),
                    discardedAttachmentIds = discardedAttachmentIds - localAttachmentId
                ),
            attemptToken = token
        )
    }

    fun terminate(localAttachmentIds: Set<String>): ThreadAttachmentUploadAttemptFence =
        copy(
            activeAttemptTokens = activeAttemptTokens - localAttachmentIds,
            discardedAttachmentIds = discardedAttachmentIds + localAttachmentIds
        )

    fun complete(localAttachmentId: String, attemptToken: Long): ThreadAttachmentUploadAttemptFence =
        if (activeAttemptTokens[localAttachmentId] == attemptToken) {
            copy(activeAttemptTokens = activeAttemptTokens - localAttachmentId)
        } else {
            this
        }

    fun accepts(localAttachmentId: String, attemptToken: Long): Boolean =
        localAttachmentId !in discardedAttachmentIds &&
            activeAttemptTokens[localAttachmentId] == attemptToken
}

internal data class ThreadAttachmentUploadAdmission(
    val fence: ThreadAttachmentUploadAttemptFence,
    val attemptToken: Long
)
