package build.raft.app

import build.raft.app.data.PlatformFile
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.synchronized
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Normalizes the product draft identity used by an attachment upload fence.
 *
 * A draft key is an ownership boundary, not display text.  In particular, an absent or whitespace
 * key must never be converted into a shared sentinel: doing so would let a callback from an old
 * draft publish into a newly mounted composer.
 */
internal fun normalizeThreadAttachmentUploadDraftKey(draftKey: String?): String? =
    draftKey?.trim()?.takeIf { it.isNotBlank() }

private fun normalizeThreadAttachmentUploadLocalId(localAttachmentId: String): String? =
    localAttachmentId.trim().takeIf { it.isNotBlank() }

/**
 * Resolves the composite key used by page-local platform-file handles.  A local attachment id is
 * only unique inside one composer draft; callers must not index these handles by the id alone.
 */
internal fun threadAttachmentUploadAttemptIdentity(
    localAttachmentId: String,
    draftKey: String?
): ThreadAttachmentUploadAttemptIdentity? {
    val normalizedId = normalizeThreadAttachmentUploadLocalId(localAttachmentId) ?: return null
    val normalizedDraftKey = normalizeThreadAttachmentUploadDraftKey(draftKey) ?: return null
    return ThreadAttachmentUploadAttemptIdentity(normalizedId, normalizedDraftKey)
}

internal fun Map<ThreadAttachmentUploadAttemptIdentity, PlatformFile>.threadAttachmentUploadFile(
    localAttachmentId: String,
    draftKey: String?
): PlatformFile? =
    threadAttachmentUploadAttemptIdentity(localAttachmentId, draftKey)?.let(::get)

internal fun Map<ThreadAttachmentUploadAttemptIdentity, PlatformFile>.withoutThreadAttachmentUploadFile(
    localAttachmentId: String,
    draftKey: String?
): Map<ThreadAttachmentUploadAttemptIdentity, PlatformFile> =
    threadAttachmentUploadAttemptIdentity(localAttachmentId, draftKey)?.let(::minus) ?: this

/** Retires every page-local handle owned by one draft while preserving same-id sibling drafts. */
internal fun Map<ThreadAttachmentUploadAttemptIdentity, PlatformFile>.withoutThreadAttachmentUploadDraft(
    draftKey: String?
): Map<ThreadAttachmentUploadAttemptIdentity, PlatformFile> {
    val normalizedDraftKey = normalizeThreadAttachmentUploadDraftKey(draftKey) ?: return this
    return filterKeys { identity -> identity.draftKey != normalizedDraftKey }
}

/** The complete identity of one local attachment attempt. */
internal data class ThreadAttachmentUploadAttemptIdentity(
    val localAttachmentId: String,
    val draftKey: String
)

/**
 * Per-local-attachment generation fence, scoped by draft identity.
 *
 * The token is still monotonic process-locally, but it is deliberately not the only admission
 * field.  A local id can be reused by a new draft, so every begin/accept/complete/terminate
 * operation must carry the normalized draft key as well.
 */
internal data class ThreadAttachmentUploadAttemptFence(
    val nextAttemptToken: Long = 0L,
    val activeAttemptTokens: Map<ThreadAttachmentUploadAttemptIdentity, Long> = emptyMap(),
    val discardedAttempts: Set<ThreadAttachmentUploadAttemptIdentity> = emptySet()
) {
    /**
     * Starts an attempt, or returns null when either identity component is unusable.
     *
     * The returned admission contains the normalized key so callers use exactly the value that
     * was fenced, even if the input had surrounding whitespace.
     */
    fun begin(
        localAttachmentId: String,
        draftKey: String?
    ): ThreadAttachmentUploadAdmission? {
        val normalizedId = normalizeThreadAttachmentUploadLocalId(localAttachmentId) ?: return null
        val normalizedDraftKey = normalizeThreadAttachmentUploadDraftKey(draftKey) ?: return null
        val identity =
            ThreadAttachmentUploadAttemptIdentity(
                localAttachmentId = normalizedId,
                draftKey = normalizedDraftKey
            )
        val token = nextAttemptToken + 1L
        return ThreadAttachmentUploadAdmission(
            fence =
                copy(
                    nextAttemptToken = token,
                    activeAttemptTokens = activeAttemptTokens + (identity to token),
                    discardedAttempts = discardedAttempts - identity
                ),
            attemptToken = token,
            identity = identity
        )
    }

    /**
     * Retires only the supplied attachment ids in the supplied draft.  The same local id in a
     * different draft remains active and publishable.
     */
    fun terminate(
        draftKey: String?,
        localAttachmentIds: Set<String>
    ): ThreadAttachmentUploadAttemptFence {
        val normalizedDraftKey = normalizeThreadAttachmentUploadDraftKey(draftKey) ?: return this
        val identities =
            localAttachmentIds
                .mapNotNull { localAttachmentId ->
                    normalizeThreadAttachmentUploadLocalId(localAttachmentId)?.let { normalizedId ->
                        ThreadAttachmentUploadAttemptIdentity(normalizedId, normalizedDraftKey)
                    }
                }
                .toSet()
        if (identities.isEmpty()) return this
        return copy(
            activeAttemptTokens = activeAttemptTokens - identities,
            discardedAttempts = discardedAttempts + identities
        )
    }

    /** Retires every active attempt owned by one draft, without touching another draft. */
    fun retireDraft(draftKey: String?): ThreadAttachmentUploadAttemptFence {
        val normalizedDraftKey = normalizeThreadAttachmentUploadDraftKey(draftKey) ?: return this
        val identities = activeAttemptTokens.keys.filter { it.draftKey == normalizedDraftKey }.toSet()
        if (identities.isEmpty()) return this
        return copy(
            activeAttemptTokens = activeAttemptTokens - identities,
            discardedAttempts = discardedAttempts + identities
        )
    }

    /** Completes only the exact id + draft + token tuple. */
    fun complete(
        localAttachmentId: String,
        draftKey: String?,
        attemptToken: Long
    ): ThreadAttachmentUploadAttemptFence {
        val identity = identity(localAttachmentId, draftKey) ?: return this
        return if (activeAttemptTokens[identity] == attemptToken) {
            copy(activeAttemptTokens = activeAttemptTokens - identity)
        } else {
            this
        }
    }

    /** Accepts a callback only for the exact current id + draft + token tuple. */
    fun accepts(
        localAttachmentId: String,
        draftKey: String?,
        attemptToken: Long
    ): Boolean {
        val identity = identity(localAttachmentId, draftKey) ?: return false
        return identity !in discardedAttempts && activeAttemptTokens[identity] == attemptToken
    }

    /** Returns all active ids; use the draft-scoped overload for a mutation. */
    fun activeAttemptIds(): Set<String> =
        activeAttemptTokens.keys.mapTo(mutableSetOf()) { it.localAttachmentId }

    /** Returns active ids for one normalized draft; an invalid key fails closed. */
    fun activeAttemptIds(draftKey: String?): Set<String> {
        val normalizedDraftKey = normalizeThreadAttachmentUploadDraftKey(draftKey) ?: return emptySet()
        return activeAttemptTokens.keys
            .asSequence()
            .filter { it.draftKey == normalizedDraftKey }
            .map { it.localAttachmentId }
            .toSet()
    }

    private fun identity(
        localAttachmentId: String,
        draftKey: String?
    ): ThreadAttachmentUploadAttemptIdentity? {
        val normalizedId = normalizeThreadAttachmentUploadLocalId(localAttachmentId) ?: return null
        val normalizedDraftKey = normalizeThreadAttachmentUploadDraftKey(draftKey) ?: return null
        return ThreadAttachmentUploadAttemptIdentity(normalizedId, normalizedDraftKey)
    }
}

internal data class ThreadAttachmentUploadAdmission(
    val fence: ThreadAttachmentUploadAttemptFence,
    val attemptToken: Long,
    val identity: ThreadAttachmentUploadAttemptIdentity
) {
    val localAttachmentId: String
        get() = identity.localAttachmentId

    val draftKey: String
        get() = identity.draftKey
}

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
    /**
     * Upload callbacks run on the process scope while UI/reducer code can retire a draft from a
     * different dispatcher.  Keep the immutable fence and the identity-keyed job table under one
     * lock; cancellation itself is always issued after releasing it because Job.cancel may invoke
     * completion handlers synchronously.
     */
    private val stateLock = SynchronizedObject()
    /**
     * Reducer publication must not run while [stateLock] is held: a dispatch can synchronously
     * re-enter removal/failure handling and it can also do persistence work.  A check followed by
     * an unlocked callback is not safe either, because retirement could win between those two
     * steps.  Give every draft one re-entrant publication lane instead.  Publication and
     * retirement for that draft are serialized by this lane, while unrelated drafts and all
     * state inspection remain free to use [stateLock].
     *
     * Locks stay process-local for this orchestrator's lifetime.  They are intentionally not
     * removed when a draft retires: retaining the lock prevents an ABA where a waiter still holds
     * the old lock while a reused draft key publishes through a newly allocated one.
     */
    private val publicationLocks = mutableMapOf<String, ReentrantLock>()
    /**
     * A token which has passed the acceptance check and is currently inside its reducer callback.
     * The set is separate from [fence]: an accepted callback is allowed to retire its own draft,
     * but a recursive callback with the same identity must be rejected instead of publishing the
     * same terminal result twice.  The token (rather than only the local id) also lets a callback
     * retire and synchronously start a new retry without making the old callback block the retry's
     * first publication.
     */
    private val inFlightPublications = mutableSetOf<ThreadAttachmentUploadPublicationIdentity>()
    private var fence = ThreadAttachmentUploadAttemptFence()
    private var jobs: Map<ThreadAttachmentUploadAttemptIdentity, Job> = emptyMap()
    private var disposed = false

    /** IDs active in any draft. Prefer the draft-scoped overload when making a mutation. */
    val activeAttemptIds: Set<String>
        get() = synchronized(stateLock) { fence.activeAttemptIds() }

    fun activeAttemptIds(draftKey: String?): Set<String> =
        synchronized(stateLock) { fence.activeAttemptIds(draftKey) }

    fun isActive(localAttachmentId: String, draftKey: String?): Boolean {
        val normalizedId = normalizeThreadAttachmentUploadLocalId(localAttachmentId) ?: return false
        val normalizedDraftKey = normalizeThreadAttachmentUploadDraftKey(draftKey) ?: return false
        return synchronized(stateLock) {
            jobs[
                    ThreadAttachmentUploadAttemptIdentity(
                        localAttachmentId = normalizedId,
                        draftKey = normalizedDraftKey
                    )
                ]
                ?.isActive == true
        }
    }

    /** Starts a draft-owned attempt; null means the draft identity was not admissible. */
    fun launch(
        localAttachmentId: String,
        draftKey: String?,
        onCompletion: (() -> Unit)? = null,
        block: suspend (attemptToken: Long) -> Unit
    ): Long? {
        val normalizedId = normalizeThreadAttachmentUploadLocalId(localAttachmentId)
        val normalizedDraftKey = normalizeThreadAttachmentUploadDraftKey(draftKey)
        if (normalizedId == null || normalizedDraftKey == null) {
            signalRejectedCompletion(onCompletion)
            return null
        }
        val publicationLock = publicationLockFor(normalizedDraftKey)
        val prepared =
            publicationLock.withLock {
                val admission =
                    synchronized(stateLock) {
                        if (disposed) {
                            null
                        } else {
                            fence.begin(normalizedId, normalizedDraftKey)?.also { candidate ->
                                fence = candidate.fence
                            }
                        }
                    } ?: return@withLock null
                val identity = admission.identity
                val attemptToken = admission.attemptToken
                val job =
                    processScope.launch(start = CoroutineStart.LAZY) {
                        try {
                            block(attemptToken)
                        } finally {
                            completeAttempt(identity, attemptToken)
                        }
                    }
                // Install the job before its completion handler. A LAZY child whose parent is
                // already cancelled can invoke a newly registered handler synchronously; putting
                // the entry in the map first lets that handler retire the admitted token instead
                // of leaving a phantom active attempt behind.
                val admittedForStart =
                    synchronized(stateLock) {
                        if (disposed || fence.activeAttemptTokens[identity] != attemptToken) {
                            false
                        } else {
                            jobs = jobs + (identity to job)
                            true
                        }
                    }
                // A LAZY job can be cancelled after admission but before its body starts. In that
                // case the block's finally is never entered, so the unconditional handler owns
                // exact-token cleanup and wakes an optional batch waiter.
                job.invokeOnCompletion {
                    completeAttempt(identity, attemptToken)
                    onCompletion?.let { completion -> runCatching { completion() } }
                }
                PreparedThreadAttachmentUpload(
                    attemptToken = attemptToken,
                    job = job,
                    admittedForStart = admittedForStart
                )
            }
        if (prepared == null) {
            signalRejectedCompletion(onCompletion)
            return null
        }
        if (prepared.admittedForStart) {
            prepared.job.start()
        } else {
            // Do not start an admission superseded by a newer token.  Registering the completion
            // handler above still guarantees the batch waiter receives its terminal signal.
            prepared.job.cancel()
        }
        return prepared.attemptToken
    }

    fun publishIfAccepted(
        localAttachmentId: String,
        draftKey: String?,
        attemptToken: Long,
        publish: () -> Unit
    ): Boolean {
        val normalizedId = normalizeThreadAttachmentUploadLocalId(localAttachmentId) ?: return false
        val normalizedDraftKey = normalizeThreadAttachmentUploadDraftKey(draftKey) ?: return false
        return publicationLockFor(normalizedDraftKey).withLock {
            val publicationIdentity =
                ThreadAttachmentUploadPublicationIdentity(
                    identity =
                        ThreadAttachmentUploadAttemptIdentity(
                            localAttachmentId = normalizedId,
                            draftKey = normalizedDraftKey
                        ),
                    attemptToken = attemptToken
                )
            val accepted =
                synchronized(stateLock) {
                    if (
                        disposed ||
                            !fence.accepts(normalizedId, normalizedDraftKey, attemptToken) ||
                            !inFlightPublications.add(publicationIdentity)
                    ) {
                        false
                    } else {
                        true
                    }
                }
            if (!accepted) return@withLock false
            // The draft publication lane, rather than stateLock, remains held across dispatch.
            // Retirement therefore either linearizes before this check (and rejects it) or waits
            // until the reducer callback has finished. A callback may safely re-enter retirement
            // because the lane is explicitly re-entrant.
            try {
                publish()
                true
            } finally {
                synchronized(stateLock) {
                    inFlightPublications.remove(publicationIdentity)
                }
            }
        }
    }

    /** Retires/cancels all active attempts for exactly one draft and returns their local ids. */
    fun retireDraft(draftKey: String?): Set<String> {
        val normalizedDraftKey = normalizeThreadAttachmentUploadDraftKey(draftKey) ?: return emptySet()
        val retirement =
            publicationLockFor(normalizedDraftKey).withLock {
                synchronized(stateLock) {
                    val retiredIdentities =
                        (fence.activeAttemptTokens.keys +
                                jobs.keys.filter { identity -> identity.draftKey == normalizedDraftKey })
                            .filter { identity -> identity.draftKey == normalizedDraftKey }
                            .toSet()
                    if (retiredIdentities.isEmpty()) {
                        null
                    } else {
                        fence = fence.retireDraft(normalizedDraftKey)
                        val jobsToCancel = retiredIdentities.mapNotNull { identity -> jobs[identity] }
                        jobs = jobs - retiredIdentities
                        retiredIdentities to jobsToCancel
                    }
                }
            }
                ?: return emptySet()
        val (retiredIdentities, jobsToCancel) = retirement
        jobsToCancel.forEach(Job::cancel)
        return retiredIdentities.mapTo(mutableSetOf()) { it.localAttachmentId }
    }

    /**
     * Terminally fails exactly [attachmentIds] in [draftKey] and returns them.  Siblings keep
     * uploading: each file is admitted or rejected on its own, so one file's failure (for example
     * an oversize original image rejected as UPLOAD_TOO_LARGE) must never cancel the others.
     */
    fun failAttempts(
        draftKey: String?,
        attachmentIds: Set<String>
    ): Set<String> =
        terminateAndCancel(
            draftKey = draftKey,
            localAttachmentIds = attachmentIds
        )

    fun remove(localAttachmentId: String, draftKey: String?) {
        terminateAndCancel(draftKey, setOf(localAttachmentId))
    }

    fun dispose() {
        val draftKeys =
            synchronized(stateLock) {
                disposed = true
                (fence.activeAttemptTokens.keys + jobs.keys)
                    .mapTo(mutableSetOf()) { identity -> identity.draftKey }
            }
        draftKeys.sorted().forEach(::retireDraft)
    }

    private fun terminateAndCancel(
        draftKey: String?,
        localAttachmentIds: Set<String>
    ): Set<String> {
        val normalizedDraftKey = normalizeThreadAttachmentUploadDraftKey(draftKey) ?: return emptySet()
        val requestedIds =
            localAttachmentIds
                .mapNotNull(::normalizeThreadAttachmentUploadLocalId)
                .toSet()
        val retirement =
            publicationLockFor(normalizedDraftKey).withLock {
                synchronized(stateLock) {
                    val normalizedIds = requestedIds
                    if (normalizedIds.isEmpty()) return@synchronized null
                    val identities =
                        normalizedIds.map { id ->
                            ThreadAttachmentUploadAttemptIdentity(id, normalizedDraftKey)
                        }
                    fence = fence.terminate(normalizedDraftKey, normalizedIds)
                    val jobsToCancel = identities.mapNotNull { identity -> jobs[identity] }
                    jobs = jobs - identities
                    normalizedIds to jobsToCancel
                }
            }
                ?: return emptySet()
        val (normalizedIds, jobsToCancel) = retirement
        jobsToCancel.forEach(Job::cancel)
        return normalizedIds
    }

    /**
     * Retires one completed job only when it still owns the exact admitted token.  A transport can
     * finish after cancellation, and a retry may already have claimed the same id; in that case
     * removing the map/fence entry would clobber the newer attempt.
     */
    private fun completeAttempt(
        identity: ThreadAttachmentUploadAttemptIdentity,
        attemptToken: Long
    ) {
        publicationLockFor(identity.draftKey).withLock {
            synchronized(stateLock) {
                if (fence.activeAttemptTokens[identity] != attemptToken) return@synchronized
                jobs = jobs - identity
                fence = fence.complete(identity.localAttachmentId, identity.draftKey, attemptToken)
            }
        }
    }

    private fun publicationLockFor(draftKey: String): ReentrantLock =
        synchronized(stateLock) {
            publicationLocks.getOrPut(draftKey, ::reentrantLock)
        }

    private fun signalRejectedCompletion(onCompletion: (() -> Unit)?) {
        // A caller waiting on an N/N barrier must be woken even when identity admission fails.
        // Treat this like a terminal cancelled attempt; the callback is best-effort and must not
        // turn a rejected upload into an exception on the caller's scope.
        onCompletion?.let { completion -> runCatching { completion() } }
    }

    private data class PreparedThreadAttachmentUpload(
        val attemptToken: Long,
        val job: Job,
        val admittedForStart: Boolean
    )

    private data class ThreadAttachmentUploadPublicationIdentity(
        val identity: ThreadAttachmentUploadAttemptIdentity,
        val attemptToken: Long
    )

    // Transitional source-compatibility overloads. They deliberately fail closed until every
    // caller supplies a draft key; this keeps an old callback from becoming an unowned attempt.
    @Deprecated("Attachment upload attempts require a nonblank draftKey")
    fun launch(
        localAttachmentId: String,
        block: suspend (attemptToken: Long) -> Unit
    ): Long? = null

    @Deprecated("Attachment upload publications require a nonblank draftKey")
    fun publishIfAccepted(
        localAttachmentId: String,
        attemptToken: Long,
        publish: () -> Unit
    ): Boolean = false

    @Deprecated("Attachment failure retirement requires a nonblank draftKey")
    fun failActive(additionalAttachmentIds: Set<String> = emptySet()): Set<String> = emptySet()

    @Deprecated("Attachment removal requires a nonblank draftKey")
    fun remove(localAttachmentId: String) = Unit

    @Deprecated("Attachment acceptance requires a nonblank draftKey")
    fun isActive(localAttachmentId: String): Boolean = false
}

// Named-argument/order conveniences for callers migrating from the old `(id, token, ...)`
// shape. They still enforce the same draft-key identity and do not create an unscoped path.
internal fun ThreadAttachmentUploadAttemptFence.complete(
    localAttachmentId: String,
    attemptToken: Long,
    draftKey: String?
): ThreadAttachmentUploadAttemptFence =
    complete(localAttachmentId = localAttachmentId, draftKey = draftKey, attemptToken = attemptToken)

internal fun ThreadAttachmentUploadAttemptFence.accepts(
    localAttachmentId: String,
    attemptToken: Long,
    draftKey: String?
): Boolean =
    accepts(localAttachmentId = localAttachmentId, draftKey = draftKey, attemptToken = attemptToken)

internal fun ThreadAttachmentUploadAttemptFence.terminate(
    localAttachmentIds: Set<String>,
    draftKey: String?
): ThreadAttachmentUploadAttemptFence =
    terminate(draftKey = draftKey, localAttachmentIds = localAttachmentIds)
