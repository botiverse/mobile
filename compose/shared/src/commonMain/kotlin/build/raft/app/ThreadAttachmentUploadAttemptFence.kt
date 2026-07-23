package build.raft.app

/**
 * Per-local-attachment generation fence.
 *
 * Removal, failure, and route disposal terminate the active token before cancelling the job, so
 * a transport that completes after cancellation cannot publish into the current composer.
 */
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
