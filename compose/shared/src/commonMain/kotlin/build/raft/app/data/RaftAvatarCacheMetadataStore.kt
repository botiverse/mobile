package build.raft.app.data

import build.raft.app.model.MemberKind
import build.raft.app.shared.data.local.AvatarCacheMetadataEntity
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

private object AvatarCacheCommitRevoked : RuntimeException()

internal class SqlDelightRaftAvatarCacheMetadataStore(
    private val database: RaftSqlDatabase
) : RaftAvatarCacheMetadataStore {
    override suspend fun read(key: RaftAvatarCacheKey): RaftAvatarCacheStoredState =
        readStoredState(key)

    override suspend fun readCommittedForOwner(
        owner: RaftAvatarCacheOwner,
        nowMs: Long,
        limit: Long
    ): List<RaftAvatarCacheMetadata> =
        database.queries.selectCommittedAvatarCacheMetadataForOwner(
            environmentId = owner.environmentId,
            accountId = owner.accountId,
            serverId = owner.serverId,
            nowMs = nowMs,
            limit = limit,
            // The NOT EXISTS subquery makes SqlDelight generate a query-specific row type; map the
            // identical column list back onto the table entity so both reads share one mapper.
            mapper = ::AvatarCacheMetadataEntity
        ).executeAsList().mapNotNull { row ->
            // A row whose subject/content no longer parses is skipped, never fatal: prewarm is an
            // optimization and the exact mount path remains the authority.
            val subject = raftAvatarCacheSubjectFromStorage(row.subjectKind, row.subjectId, row.memberKind)
                ?: return@mapNotNull null
            runCatching { row.toCacheMetadata(owner, subject) }.getOrNull()
                ?.takeIf { it.hasExactCommitIdentity }
        }

    private fun AvatarCacheMetadataEntity.toCacheMetadata(
        owner: RaftAvatarCacheOwner,
        subject: RaftAvatarCacheSubject
    ): RaftAvatarCacheMetadata {
        val storedKey =
            RaftAvatarCacheKey(
                owner = owner,
                subject = subject,
                content =
                    RaftAvatarContentIdentity(
                        kind = RaftAvatarContentKind.fromStorage(contentKind),
                        fingerprint = contentFingerprint
                    )
            )
        val durableCommit =
            raftAvatarDurableCommitOrNull(
                commitGeneration = commitGeneration,
                commitNonce = commitNonce,
                modelRevision = modelRevision
            )
        return RaftAvatarCacheMetadata(
            key = storedKey,
            eTag = eTag,
            lastModified = lastModified,
            fetchedAtMs = fetchedAtMs,
            validatedAtMs = validatedAtMs,
            freshUntilMs = freshUntilMs,
            staleUntilMs = staleUntilMs,
            sizeBytes = sizeBytes,
            contentType = contentType,
            commitIdentity = durableCommit?.first,
            modelRevision = durableCommit?.second ?: 0L
        )
    }

    private fun readStoredState(key: RaftAvatarCacheKey): RaftAvatarCacheStoredState {
        val owner = key.owner
        val subject = key.subject
        val metadata =
            database.queries.selectAvatarCacheMetadata(
                environmentId = owner.environmentId,
                accountId = owner.accountId,
                serverId = owner.serverId,
                subjectKind = subject.subjectKind,
                subjectId = subject.subjectId,
                memberKind = subject.memberKind
            ).executeAsOneOrNull()?.toCacheMetadata(owner, subject)
        val failure =
            database.queries.selectAvatarCacheFailure(
                environmentId = owner.environmentId,
                accountId = owner.accountId,
                serverId = owner.serverId,
                subjectKind = subject.subjectKind,
                subjectId = subject.subjectId,
                memberKind = subject.memberKind,
                contentKind = key.content.kind.storageValue,
                contentFingerprint = key.content.fingerprint
            ).executeAsOneOrNull()?.let { row ->
                RaftAvatarCacheFailure(
                    key = key,
                    kind = RaftAvatarCacheFailureKind.fromStorage(row.failureKind),
                    consecutiveCount = row.consecutiveCount.toInt(),
                    failedAtMs = row.failedAtMs,
                    retryAtMs = row.retryAtMs
                )
            }
        return RaftAvatarCacheStoredState(metadata = metadata, failure = failure)
    }

    override suspend fun commitSuccess(
        metadata: RaftAvatarCacheMetadata,
        guard: RaftAvatarCacheCommitGuard
    ): RaftAvatarCacheCommitResult =
        commitGuarded(metadata.key, guard) {
            val key = metadata.key
            guard.expectedCommit?.let { expected ->
                val current = readStoredState(key).metadata
                if (
                    current?.key?.let { it != expected.key } == true ||
                    current?.commitIdentity != expected.commitIdentity ||
                    (current?.modelRevision ?: 0L) != expected.modelRevision
                ) {
                    throw AvatarCacheCommitRevoked
                }
            }
            val owner = key.owner
            val subject = key.subject
            queries.upsertAvatarCacheMetadata(
                environmentId = owner.environmentId,
                accountId = owner.accountId,
                serverId = owner.serverId,
                subjectKind = subject.subjectKind,
                subjectId = subject.subjectId,
                memberKind = subject.memberKind,
                contentKind = key.content.kind.storageValue,
                contentFingerprint = key.content.fingerprint,
                eTag = metadata.eTag,
                lastModified = metadata.lastModified,
                fetchedAtMs = metadata.fetchedAtMs,
                validatedAtMs = metadata.validatedAtMs,
                freshUntilMs = metadata.freshUntilMs,
                staleUntilMs = metadata.staleUntilMs,
                sizeBytes = metadata.sizeBytes,
                contentType = metadata.contentType,
                commitGeneration = metadata.commitIdentity?.commitGeneration,
                commitNonce = metadata.commitIdentity?.nonce,
                modelRevision = metadata.modelRevision.takeIf { metadata.commitIdentity != null }
            )
            queries.deleteAvatarCacheFailuresForSubject(
                environmentId = owner.environmentId,
                accountId = owner.accountId,
                serverId = owner.serverId,
                subjectKind = subject.subjectKind,
                subjectId = subject.subjectId,
                memberKind = subject.memberKind
            )
        }

    override suspend fun commitFailure(
        failure: RaftAvatarCacheFailure,
        retireMatchingMetadata: Boolean,
        guard: RaftAvatarCacheCommitGuard
    ): RaftAvatarCacheCommitResult =
        commitGuarded(failure.key, guard) {
            val key = failure.key
            val owner = key.owner
            val subject = key.subject
            if (retireMatchingMetadata) {
                queries.deleteAvatarCacheMetadataForContent(
                    environmentId = owner.environmentId,
                    accountId = owner.accountId,
                    serverId = owner.serverId,
                    subjectKind = subject.subjectKind,
                    subjectId = subject.subjectId,
                    memberKind = subject.memberKind,
                    contentKind = key.content.kind.storageValue,
                    contentFingerprint = key.content.fingerprint
                )
            }
            queries.upsertAvatarCacheFailure(
                environmentId = owner.environmentId,
                accountId = owner.accountId,
                serverId = owner.serverId,
                subjectKind = subject.subjectKind,
                subjectId = subject.subjectId,
                memberKind = subject.memberKind,
                contentKind = key.content.kind.storageValue,
                contentFingerprint = key.content.fingerprint,
                failureKind = failure.kind.storageValue,
                consecutiveCount = failure.consecutiveCount.toLong(),
                failedAtMs = failure.failedAtMs,
                retryAtMs = failure.retryAtMs
            )
        }

    override suspend fun restoreAfterNativeFinalizeFailure(
        key: RaftAvatarCacheKey,
        expectedCommittedState: RaftAvatarCacheStoredState,
        previousState: RaftAvatarCacheStoredState,
        guard: RaftAvatarCacheCommitGuard
    ): RaftAvatarCacheCommitResult =
        commitGuarded(key, guard) {
            if (readStoredState(key) != expectedCommittedState) throw AvatarCacheCommitRevoked
            val owner = key.owner
            val subject = key.subject
            val previousMetadata = previousState.metadata
            if (previousMetadata == null) {
                queries.deleteAvatarCacheMetadata(
                    environmentId = owner.environmentId,
                    accountId = owner.accountId,
                    serverId = owner.serverId,
                    subjectKind = subject.subjectKind,
                    subjectId = subject.subjectId,
                    memberKind = subject.memberKind
                )
            } else {
                require(previousMetadata.key.owner == owner && previousMetadata.key.subject == subject)
                val previousKey = previousMetadata.key
                queries.upsertAvatarCacheMetadata(
                    environmentId = owner.environmentId,
                    accountId = owner.accountId,
                    serverId = owner.serverId,
                    subjectKind = subject.subjectKind,
                    subjectId = subject.subjectId,
                    memberKind = subject.memberKind,
                    contentKind = previousKey.content.kind.storageValue,
                    contentFingerprint = previousKey.content.fingerprint,
                    eTag = previousMetadata.eTag,
                    lastModified = previousMetadata.lastModified,
                    fetchedAtMs = previousMetadata.fetchedAtMs,
                    validatedAtMs = previousMetadata.validatedAtMs,
                    freshUntilMs = previousMetadata.freshUntilMs,
                    staleUntilMs = previousMetadata.staleUntilMs,
                    sizeBytes = previousMetadata.sizeBytes,
                    contentType = previousMetadata.contentType,
                    commitGeneration = previousMetadata.commitIdentity?.commitGeneration,
                    commitNonce = previousMetadata.commitIdentity?.nonce,
                    modelRevision = previousMetadata.modelRevision.takeIf {
                        previousMetadata.commitIdentity != null
                    }
                )
            }

            queries.deleteAvatarCacheFailure(
                environmentId = owner.environmentId,
                accountId = owner.accountId,
                serverId = owner.serverId,
                subjectKind = subject.subjectKind,
                subjectId = subject.subjectId,
                memberKind = subject.memberKind,
                contentKind = key.content.kind.storageValue,
                contentFingerprint = key.content.fingerprint
            )
            previousState.failure?.let { previousFailure ->
                require(previousFailure.key == key)
                queries.upsertAvatarCacheFailure(
                    environmentId = owner.environmentId,
                    accountId = owner.accountId,
                    serverId = owner.serverId,
                    subjectKind = subject.subjectKind,
                    subjectId = subject.subjectId,
                    memberKind = subject.memberKind,
                    contentKind = key.content.kind.storageValue,
                    contentFingerprint = key.content.fingerprint,
                    failureKind = previousFailure.kind.storageValue,
                    consecutiveCount = previousFailure.consecutiveCount.toLong(),
                    failedAtMs = previousFailure.failedAtMs,
                    retryAtMs = previousFailure.retryAtMs
                )
            }
        }

    override suspend fun clearFailure(key: RaftAvatarCacheKey) {
        val owner = key.owner
        val subject = key.subject
        database.queries.deleteAvatarCacheFailure(
            environmentId = owner.environmentId,
            accountId = owner.accountId,
            serverId = owner.serverId,
            subjectKind = subject.subjectKind,
            subjectId = subject.subjectId,
            memberKind = subject.memberKind,
            contentKind = key.content.kind.storageValue,
            contentFingerprint = key.content.fingerprint
        )
    }

    override suspend fun clearTemporaryFailures(owner: RaftAvatarCacheOwner) {
        database.queries.clearTemporaryAvatarCacheFailuresForOwner(
            environmentId = owner.environmentId,
            accountId = owner.accountId,
            serverId = owner.serverId,
            failureKind = RaftAvatarCacheFailureKind.TemporaryTransport.storageValue
        )
    }

    override suspend fun clearSubject(owner: RaftAvatarCacheOwner, subject: RaftAvatarCacheSubject) {
        database.transaction {
            queries.deleteAvatarCacheMetadata(
                environmentId = owner.environmentId,
                accountId = owner.accountId,
                serverId = owner.serverId,
                subjectKind = subject.subjectKind,
                subjectId = subject.subjectId,
                memberKind = subject.memberKind
            )
            queries.deleteAvatarCacheFailuresForSubject(
                environmentId = owner.environmentId,
                accountId = owner.accountId,
                serverId = owner.serverId,
                subjectKind = subject.subjectKind,
                subjectId = subject.subjectId,
                memberKind = subject.memberKind
            )
        }
    }

    override suspend fun clearOwner(owner: RaftAvatarCacheOwner) {
        database.transaction {
            queries.clearAvatarCacheMetadataForOwner(
                environmentId = owner.environmentId,
                accountId = owner.accountId,
                serverId = owner.serverId
            )
            queries.clearAvatarCacheFailuresForOwner(
                environmentId = owner.environmentId,
                accountId = owner.accountId,
                serverId = owner.serverId
            )
        }
    }

    override suspend fun clearAll() {
        database.transaction {
            queries.clearAllAvatarCacheMetadata()
            queries.clearAllAvatarCacheFailures()
        }
    }

    private suspend fun commitGuarded(
        key: RaftAvatarCacheKey,
        guard: RaftAvatarCacheCommitGuard,
        write: RaftSqlDatabase.() -> Unit
    ): RaftAvatarCacheCommitResult =
        try {
            database.transaction {
                if (!guard.allows(key)) throw AvatarCacheCommitRevoked
                write()
                if (!guard.allows(key)) throw AvatarCacheCommitRevoked
            }
            RaftAvatarCacheCommitResult.Accepted
        } catch (_: AvatarCacheCommitRevoked) {
            RaftAvatarCacheCommitResult.RejectedStaleGeneration
        }
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
