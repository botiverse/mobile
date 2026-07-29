package build.raft.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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

/**
 * Production owner for attachment upload attempts and jobs.
 *
 * The production scope must be the shared process-owned Thread send scope, never a page/composable
 * scope: uploads continue while the process is alive even if the picker changes Activity, the app
 * backgrounds, or the originating Thread leaves composition. Tests can drive the same owner with
 * controlled deferred transports; UI code supplies the actual upload block and applies accepted
 * results to the Composer draft reducer.
 */
internal class ThreadAttachmentUploadOrchestrator(
    private val processScope: CoroutineScope
) {
    private var fence = ThreadAttachmentUploadAttemptFence()
    private var jobs: Map<String, Job> = emptyMap()

    val activeAttemptIds: Set<String>
        get() = fence.activeAttemptTokens.keys

    fun isActive(localAttachmentId: String): Boolean = jobs[localAttachmentId]?.isActive == true

    fun launch(
        localAttachmentId: String,
        block: suspend (attemptToken: Long) -> Unit
    ): Long {
        val admission = fence.begin(localAttachmentId)
        fence = admission.fence
        val attemptToken = admission.attemptToken
        val job =
            processScope.launch(start = CoroutineStart.LAZY) {
                try {
                    block(attemptToken)
                } finally {
                    if (fence.activeAttemptTokens[localAttachmentId] == attemptToken) {
                        jobs = jobs - localAttachmentId
                        fence = fence.complete(localAttachmentId, attemptToken)
                    }
                }
            }
        jobs = jobs + (localAttachmentId to job)
        job.start()
        return attemptToken
    }

    fun publishIfAccepted(
        localAttachmentId: String,
        attemptToken: Long,
        publish: () -> Unit
    ): Boolean {
        if (!fence.accepts(localAttachmentId, attemptToken)) return false
        publish()
        return true
    }

    fun failActive(additionalAttachmentIds: Set<String> = emptySet()): Set<String> =
        terminateAndCancel(activeAttemptIds + additionalAttachmentIds)

    fun remove(localAttachmentId: String) {
        terminateAndCancel(setOf(localAttachmentId))
    }

    fun dispose() {
        terminateAndCancel(activeAttemptIds)
    }

    private fun terminateAndCancel(localAttachmentIds: Set<String>): Set<String> {
        if (localAttachmentIds.isEmpty()) return emptySet()
        fence = fence.terminate(localAttachmentIds)
        localAttachmentIds.forEach { localAttachmentId -> jobs[localAttachmentId]?.cancel() }
        jobs = jobs - localAttachmentIds
        return localAttachmentIds
    }
}
