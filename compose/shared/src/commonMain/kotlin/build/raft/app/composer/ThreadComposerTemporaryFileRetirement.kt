package build.raft.app

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Structured receipt for one synchronous temporary-file ownership transition.
 *
 * A platform handler may delete its unique tombstones asynchronously, but it
 * must finish every admitted ref's same-directory rename/quarantine before
 * returning this receipt.  A failed ref keeps its original bytes untouched.
 */
public data class ThreadComposerTemporaryFileRetirementResult(
    val sessionId: String,
    val requestedFileRefs: Set<String>,
    val admittedFileRefs: Set<String> = emptySet(),
    val quarantinedFileRefs: Set<String> = emptySet(),
    val rejectedFileRefs: Set<String> = emptySet(),
    val failedFileRefs: Set<String> = emptySet(),
    val tombstoneByFileRef: Map<String, String> = emptyMap(),
    val errorCode: String? = null
) {
    val requestedCount: Int
        get() = requestedFileRefs.size

    val admittedCount: Int
        get() = admittedFileRefs.size

    val quarantinedCount: Int
        get() = quarantinedFileRefs.size

    val rejectedCount: Int
        get() = rejectedFileRefs.size

    val failedCount: Int
        get() = failedFileRefs.size

    val succeeded: Boolean
        get() =
            requestedFileRefs.isNotEmpty() &&
                admittedFileRefs == requestedFileRefs &&
                quarantinedFileRefs == admittedFileRefs &&
                rejectedFileRefs.isEmpty() &&
                failedFileRefs.isEmpty() &&
                errorCode == null

    /** Alias retained for host adapters which use result.success. */
    val success: Boolean
        get() = succeeded

    /** Missing handlers and failed quarantine moves are explicit fail-closed results. */
    val failClosed: Boolean
        get() = !succeeded

    companion object {
        const val MissingHandlerCode = "temporary_file_retirement_handler_missing"
        const val InvalidSessionCode = "temporary_file_retirement_session_invalid"
        const val HandlerFailureCode = "temporary_file_retirement_handler_failed"
        const val HandlerContractCode = "temporary_file_retirement_handler_contract_invalid"

        fun quarantined(
            sessionId: String,
            fileRefs: Set<String>,
            tombstoneByFileRef: Map<String, String> = emptyMap()
        ): ThreadComposerTemporaryFileRetirementResult =
            ThreadComposerTemporaryFileRetirementResult(
                sessionId = sessionId,
                requestedFileRefs = fileRefs,
                admittedFileRefs = fileRefs,
                quarantinedFileRefs = fileRefs,
                tombstoneByFileRef = tombstoneByFileRef
            )

        fun empty(sessionId: String = ""): ThreadComposerTemporaryFileRetirementResult =
            ThreadComposerTemporaryFileRetirementResult(
                sessionId = sessionId,
                requestedFileRefs = emptySet()
            )
    }
}

/**
 * Platform-owned synchronous retirement operation.
 *
 * For each admitted ref the implementation must atomically move the original
 * file to a unique sibling tombstone before returning.  It may enqueue only
 * deletion of that exact tombstone after the return; it must never enqueue an
 * unlink of the original pathname.  A rename failure reports the ref in
 * [ThreadComposerTemporaryFileRetirementResult.failedFileRefs] and preserves
 * the original file.
 */
public fun interface ThreadComposerTemporaryFileRetirementHandler {
    fun retire(
        sessionId: String,
        fileRefs: Set<String>
    ): ThreadComposerTemporaryFileRetirementResult
}

/**
 * Thread-safe app-scope registry for the one platform retirement handler.
 *
 * [retire] invokes the current handler synchronously while holding the registry
 * lock.  The caller additionally holds the shared draft ownership lock, so a
 * sibling draft cannot claim a pathname between the final durable ownership
 * check and the handler's quarantine transition.  The registry deliberately
 * keeps no path blacklist: producers are responsible for unique refs, and a
 * quarantined path is physically vacant when this call returns.
 */
public object ThreadComposerTemporaryFileRetirement {
    private val lock = SynchronizedObject()
    private var handler: ThreadComposerTemporaryFileRetirementHandler? = null

    fun install(handler: ThreadComposerTemporaryFileRetirementHandler) {
        synchronized(lock) {
            this.handler = handler
        }
    }

    /** Convenience overload retained only for same-module tests. */
    internal fun install(
        handler: (
            sessionId: String,
            fileRefs: Set<String>
        ) -> ThreadComposerTemporaryFileRetirementResult
    ) {
        install(ThreadComposerTemporaryFileRetirementHandler(handler))
    }

    fun retire(
        sessionId: String,
        fileRefs: Set<String>
    ): ThreadComposerTemporaryFileRetirementResult {
        val normalizedSessionId = sessionId.trim()
        val normalizedRefs = normalizeRefs(fileRefs)
        if (normalizedSessionId.isBlank()) {
            return ThreadComposerTemporaryFileRetirementResult(
                sessionId = normalizedSessionId,
                requestedFileRefs = normalizedRefs,
                rejectedFileRefs = normalizedRefs,
                errorCode = ThreadComposerTemporaryFileRetirementResult.InvalidSessionCode
            )
        }
        if (normalizedRefs.isEmpty()) {
            return ThreadComposerTemporaryFileRetirementResult.empty(normalizedSessionId)
        }
        return synchronized(lock) {
            val current = handler
                ?: return@synchronized ThreadComposerTemporaryFileRetirementResult(
                    sessionId = normalizedSessionId,
                    requestedFileRefs = normalizedRefs,
                    failedFileRefs = normalizedRefs,
                    errorCode = ThreadComposerTemporaryFileRetirementResult.MissingHandlerCode
                )
            val raw =
                runCatching { current.retire(normalizedSessionId, normalizedRefs) }
                    .getOrElse {
                        return@synchronized ThreadComposerTemporaryFileRetirementResult(
                            sessionId = normalizedSessionId,
                            requestedFileRefs = normalizedRefs,
                            failedFileRefs = normalizedRefs,
                            errorCode = ThreadComposerTemporaryFileRetirementResult.HandlerFailureCode
                        )
                    }
            normalizeHandlerResult(normalizedSessionId, normalizedRefs, raw)
        }
    }

    internal fun resetForTests() {
        synchronized(lock) {
            handler = null
        }
    }

    private fun normalizeRefs(fileRefs: Iterable<String>): Set<String> =
        fileRefs.map(String::trim).filter(String::isNotBlank).toCollection(linkedSetOf())

    private fun normalizeHandlerResult(
        sessionId: String,
        requestedRefs: Set<String>,
        result: ThreadComposerTemporaryFileRetirementResult
    ): ThreadComposerTemporaryFileRetirementResult {
        val admitted = result.admittedFileRefs intersect requestedRefs
        val quarantined = result.quarantinedFileRefs intersect admitted
        val rejected = result.rejectedFileRefs intersect requestedRefs
        val explicitFailed = result.failedFileRefs intersect requestedRefs
        val unclassified = requestedRefs - quarantined - rejected - explicitFailed
        val invalidReported =
            (result.admittedFileRefs +
                result.quarantinedFileRefs +
                result.rejectedFileRefs +
                result.failedFileRefs) - requestedRefs
        val failed = explicitFailed + unclassified
        val errorCode =
            when {
                invalidReported.isNotEmpty() ->
                    ThreadComposerTemporaryFileRetirementResult.HandlerContractCode
                result.errorCode != null -> result.errorCode
                failed.isNotEmpty() -> ThreadComposerTemporaryFileRetirementResult.HandlerFailureCode
                else -> null
            }
        return result.copy(
            sessionId = sessionId,
            requestedFileRefs = requestedRefs,
            admittedFileRefs = admitted,
            quarantinedFileRefs = quarantined,
            rejectedFileRefs = rejected,
            failedFileRefs = failed,
            tombstoneByFileRef = result.tombstoneByFileRef.filterKeys { it in quarantined },
            errorCode = errorCode
        )
    }
}
