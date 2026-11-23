package build.raft.app.data

import build.raft.app.model.MemberKind
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

/**
 * Binds a native response to one exact owner/subject/content request generation. The callback must
 * become false when either the owner binding or that subject's selected content generation changes.
 */
internal class RaftAvatarCacheCommitGuard(
    val key: RaftAvatarCacheKey,
    internal val expectedCommit: RaftAvatarCacheCommitExpectation? = null,
    private val isCurrentGeneration: () -> Boolean
) {
    init {
        require(expectedCommit == null || expectedCommit.key == key)
    }

    fun allows(candidate: RaftAvatarCacheKey): Boolean =
        candidate == key && isCurrentGeneration()
}

internal data class RaftAvatarCacheCommitExpectation(
    val key: RaftAvatarCacheKey,
    val commitIdentity: RaftAvatarCommitIdentity?,
    val modelRevision: Long
) {
    init {
        require(modelRevision >= 0L)
        require((commitIdentity == null) == (modelRevision == 0L))
    }
}

internal enum class RaftAvatarCacheCommitResult {
    Accepted,
    RejectedStaleGeneration
}

internal interface RaftAvatarCacheMetadataStore {
    suspend fun read(key: RaftAvatarCacheKey): RaftAvatarCacheStoredState

    /**
     * task #343: exact committed rows for one owner that are not suppressed by an unexpired
     * failure, newest validation first. Used only to prewarm the in-process mounted session so a
     * cold start can paint durable avatars on the first frame; never an authority for refresh.
     */
    suspend fun readCommittedForOwner(
        owner: RaftAvatarCacheOwner,
        nowMs: Long,
        limit: Long
    ): List<RaftAvatarCacheMetadata>

    suspend fun commitSuccess(
        metadata: RaftAvatarCacheMetadata,
        guard: RaftAvatarCacheCommitGuard
    ): RaftAvatarCacheCommitResult

    suspend fun commitFailure(
        failure: RaftAvatarCacheFailure,
        retireMatchingMetadata: Boolean,
        guard: RaftAvatarCacheCommitGuard
    ): RaftAvatarCacheCommitResult

    /**
     * Compensates the shared half of a staged native mutation that could not be finalized. The
     * restore is admitted only while both the exact request generation and the exact post-commit
     * state still match, so it can never erase a newer owner/subject/content decision.
     */
    suspend fun restoreAfterNativeFinalizeFailure(
        key: RaftAvatarCacheKey,
        expectedCommittedState: RaftAvatarCacheStoredState,
        previousState: RaftAvatarCacheStoredState,
        guard: RaftAvatarCacheCommitGuard
    ): RaftAvatarCacheCommitResult

    suspend fun clearFailure(key: RaftAvatarCacheKey)
    suspend fun clearTemporaryFailures(owner: RaftAvatarCacheOwner)
    suspend fun clearSubject(owner: RaftAvatarCacheOwner, subject: RaftAvatarCacheSubject)
    suspend fun clearOwner(owner: RaftAvatarCacheOwner)
    suspend fun clearAll()
}

/**
 * task #323: runs every metadata read/write of [delegate] on [dispatcher] so SQLite I/O never
 * executes on the caller's thread (the Kuikly frame thread when reached from composition).
 * Guards evaluated inside the delegate's transaction ([RaftAvatarCacheCommitGuard.allows]) read
 * session state under the session lock, so they are safe to evaluate off the frame thread.
 */
internal class DispatchingRaftAvatarCacheMetadataStore(
    private val delegate: RaftAvatarCacheMetadataStore,
    private val dispatcher: CoroutineContext
) : RaftAvatarCacheMetadataStore {
    override suspend fun read(key: RaftAvatarCacheKey): RaftAvatarCacheStoredState =
        withContext(dispatcher) { delegate.read(key) }

    override suspend fun commitSuccess(
        metadata: RaftAvatarCacheMetadata,
        guard: RaftAvatarCacheCommitGuard
    ): RaftAvatarCacheCommitResult =
        withContext(dispatcher) { delegate.commitSuccess(metadata, guard) }

    override suspend fun commitFailure(
        failure: RaftAvatarCacheFailure,
        retireMatchingMetadata: Boolean,
        guard: RaftAvatarCacheCommitGuard
    ): RaftAvatarCacheCommitResult =
        withContext(dispatcher) { delegate.commitFailure(failure, retireMatchingMetadata, guard) }

    override suspend fun restoreAfterNativeFinalizeFailure(
        key: RaftAvatarCacheKey,
        expectedCommittedState: RaftAvatarCacheStoredState,
        previousState: RaftAvatarCacheStoredState,
        guard: RaftAvatarCacheCommitGuard
    ): RaftAvatarCacheCommitResult =
        withContext(dispatcher) {
            delegate.restoreAfterNativeFinalizeFailure(key, expectedCommittedState, previousState, guard)
        }

    override suspend fun clearFailure(key: RaftAvatarCacheKey) =
        withContext(dispatcher) { delegate.clearFailure(key) }

    override suspend fun clearTemporaryFailures(owner: RaftAvatarCacheOwner) =
        withContext(dispatcher) { delegate.clearTemporaryFailures(owner) }

    override suspend fun clearSubject(owner: RaftAvatarCacheOwner, subject: RaftAvatarCacheSubject) =
        withContext(dispatcher) { delegate.clearSubject(owner, subject) }

    override suspend fun clearOwner(owner: RaftAvatarCacheOwner) =
        withContext(dispatcher) { delegate.clearOwner(owner) }

    override suspend fun clearAll() =
        withContext(dispatcher) { delegate.clearAll() }

    override suspend fun readCommittedForOwner(
        owner: RaftAvatarCacheOwner,
        nowMs: Long,
        limit: Long
    ): List<RaftAvatarCacheMetadata> =
        withContext(dispatcher) { delegate.readCommittedForOwner(owner, nowMs, limit) }
}

/**
 * #proj-mobile:#294: retired `slock-avatar-render-v1` SQLite tables (`avatarCacheMetadataEntity` /
 * `avatarCacheFailureEntity`); Coil's disk cache is the sole avatar byte/image cache.
 */
internal object NoOpRaftAvatarCacheMetadataStore : RaftAvatarCacheMetadataStore {
    override suspend fun read(key: RaftAvatarCacheKey): RaftAvatarCacheStoredState =
        RaftAvatarCacheStoredState(metadata = null, failure = null)

    override suspend fun readCommittedForOwner(
        owner: RaftAvatarCacheOwner,
        nowMs: Long,
        limit: Long
    ): List<RaftAvatarCacheMetadata> = emptyList()

    override suspend fun commitSuccess(
        metadata: RaftAvatarCacheMetadata,
        guard: RaftAvatarCacheCommitGuard
    ): RaftAvatarCacheCommitResult =
        if (guard.allows(metadata.key)) {
            RaftAvatarCacheCommitResult.Accepted
        } else {
            RaftAvatarCacheCommitResult.RejectedStaleGeneration
        }

    override suspend fun commitFailure(
        failure: RaftAvatarCacheFailure,
        retireMatchingMetadata: Boolean,
        guard: RaftAvatarCacheCommitGuard
    ): RaftAvatarCacheCommitResult =
        if (guard.allows(failure.key)) {
            RaftAvatarCacheCommitResult.Accepted
        } else {
            RaftAvatarCacheCommitResult.RejectedStaleGeneration
        }

    override suspend fun restoreAfterNativeFinalizeFailure(
        key: RaftAvatarCacheKey,
        expectedCommittedState: RaftAvatarCacheStoredState,
        previousState: RaftAvatarCacheStoredState,
        guard: RaftAvatarCacheCommitGuard
    ): RaftAvatarCacheCommitResult =
        if (guard.allows(key)) {
            RaftAvatarCacheCommitResult.Accepted
        } else {
            RaftAvatarCacheCommitResult.RejectedStaleGeneration
        }

    override suspend fun clearFailure(key: RaftAvatarCacheKey) = Unit

    override suspend fun clearTemporaryFailures(owner: RaftAvatarCacheOwner) = Unit

    override suspend fun clearSubject(owner: RaftAvatarCacheOwner, subject: RaftAvatarCacheSubject) = Unit

    override suspend fun clearOwner(owner: RaftAvatarCacheOwner) = Unit

    override suspend fun clearAll() = Unit
}

internal fun raftAvatarDurableCommitOrNull(
    commitGeneration: Long?,
    commitNonce: String?,
    modelRevision: Long?
): Pair<RaftAvatarCommitIdentity, Long>? {
    if (commitGeneration == null || commitNonce == null || modelRevision == null) return null
    if (modelRevision <= 0L) return null
    val identity =
        runCatching { RaftAvatarCommitIdentity(commitGeneration, commitNonce) }
            .getOrNull()
            ?: return null
    return identity to modelRevision
}

/** task #343: inverse of [RaftAvatarCacheSubject] storage columns; null when not representable. */
internal fun raftAvatarCacheSubjectFromStorage(
    subjectKind: String,
    subjectId: String,
    memberKind: String
): RaftAvatarCacheSubject? =
    runCatching {
        when (subjectKind) {
            "member" ->
                when (memberKind) {
                    "human" -> RaftAvatarCacheSubject.Member(MemberKind.Human, subjectId)
                    "agent" -> RaftAvatarCacheSubject.Member(MemberKind.Agent, subjectId)
                    else -> null
                }
            "server" -> RaftAvatarCacheSubject.Server(subjectId)
            else -> null
        }
    }.getOrNull()
