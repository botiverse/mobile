package build.raft.app.data

import build.raft.app.model.MemberKind
import build.raft.app.shared.data.local.AvatarCacheMetadataEntity

/**
 * Binds a native response to one exact owner/subject/content request generation. The callback must
 * become false when either the owner binding or that subject's selected content generation changes.
 */
internal class SlockAvatarCacheCommitGuard(
    val key: SlockAvatarCacheKey,
    internal val expectedCommit: SlockAvatarCacheCommitExpectation? = null,
    private val isCurrentGeneration: () -> Boolean
) {
    init {
        require(expectedCommit == null || expectedCommit.key == key)
    }

    fun allows(candidate: SlockAvatarCacheKey): Boolean =
        candidate == key && isCurrentGeneration()
}

internal data class SlockAvatarCacheCommitExpectation(
    val key: SlockAvatarCacheKey,
    val commitIdentity: SlockAvatarCommitIdentity?,
    val modelRevision: Long
) {
    init {
        require(modelRevision >= 0L)
        require((commitIdentity == null) == (modelRevision == 0L))
    }
}

internal enum class SlockAvatarCacheCommitResult {
    Accepted,
    RejectedStaleGeneration
}

internal interface SlockAvatarCacheMetadataStore {
    suspend fun read(key: SlockAvatarCacheKey): SlockAvatarCacheStoredState

    /**
     * task #343: exact committed rows for one owner that are not suppressed by an unexpired
     * failure, newest validation first. Used only to prewarm the in-process mounted session so a
     * cold start can paint durable avatars on the first frame; never an authority for refresh.
     */
    suspend fun readCommittedForOwner(
        owner: SlockAvatarCacheOwner,
        nowMs: Long,
        limit: Long
    ): List<SlockAvatarCacheMetadata>

    suspend fun commitSuccess(
        metadata: SlockAvatarCacheMetadata,
        guard: SlockAvatarCacheCommitGuard
    ): SlockAvatarCacheCommitResult

    suspend fun commitFailure(
        failure: SlockAvatarCacheFailure,
        retireMatchingMetadata: Boolean,
        guard: SlockAvatarCacheCommitGuard
    ): SlockAvatarCacheCommitResult

    /**
     * Compensates the shared half of a staged native mutation that could not be finalized. The
     * restore is admitted only while both the exact request generation and the exact post-commit
     * state still match, so it can never erase a newer owner/subject/content decision.
     */
    suspend fun restoreAfterNativeFinalizeFailure(
        key: SlockAvatarCacheKey,
        expectedCommittedState: SlockAvatarCacheStoredState,
        previousState: SlockAvatarCacheStoredState,
        guard: SlockAvatarCacheCommitGuard
    ): SlockAvatarCacheCommitResult

    suspend fun clearFailure(key: SlockAvatarCacheKey)
    suspend fun clearTemporaryFailures(owner: SlockAvatarCacheOwner)
    suspend fun clearSubject(owner: SlockAvatarCacheOwner, subject: SlockAvatarCacheSubject)
    suspend fun clearOwner(owner: SlockAvatarCacheOwner)
    suspend fun clearAll()
}

private object AvatarCacheCommitRevoked : RuntimeException()

internal class SqlDelightSlockAvatarCacheMetadataStore(
    private val database: SlockSqlDatabase
) : SlockAvatarCacheMetadataStore {
    override suspend fun read(key: SlockAvatarCacheKey): SlockAvatarCacheStoredState =
        readStoredState(key)

    override suspend fun readCommittedForOwner(
        owner: SlockAvatarCacheOwner,
        nowMs: Long,
        limit: Long
    ): List<SlockAvatarCacheMetadata> =
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
            val subject = slockAvatarCacheSubjectFromStorage(row.subjectKind, row.subjectId, row.memberKind)
                ?: return@mapNotNull null
            runCatching { row.toCacheMetadata(owner, subject) }.getOrNull()
                ?.takeIf { it.hasExactCommitIdentity }
        }

    private fun AvatarCacheMetadataEntity.toCacheMetadata(
        owner: SlockAvatarCacheOwner,
        subject: SlockAvatarCacheSubject
    ): SlockAvatarCacheMetadata {
        val storedKey =
            SlockAvatarCacheKey(
                owner = owner,
                subject = subject,
                content =
                    SlockAvatarContentIdentity(
                        kind = SlockAvatarContentKind.fromStorage(contentKind),
                        fingerprint = contentFingerprint
                    )
            )
        val durableCommit =
            slockAvatarDurableCommitOrNull(
                commitGeneration = commitGeneration,
                commitNonce = commitNonce,
                modelRevision = modelRevision
            )
        return SlockAvatarCacheMetadata(
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

    private fun readStoredState(key: SlockAvatarCacheKey): SlockAvatarCacheStoredState {
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
                SlockAvatarCacheFailure(
                    key = key,
                    kind = SlockAvatarCacheFailureKind.fromStorage(row.failureKind),
                    consecutiveCount = row.consecutiveCount.toInt(),
                    failedAtMs = row.failedAtMs,
                    retryAtMs = row.retryAtMs
                )
            }
        return SlockAvatarCacheStoredState(metadata = metadata, failure = failure)
    }

    override suspend fun commitSuccess(
        metadata: SlockAvatarCacheMetadata,
        guard: SlockAvatarCacheCommitGuard
    ): SlockAvatarCacheCommitResult =
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
        failure: SlockAvatarCacheFailure,
        retireMatchingMetadata: Boolean,
        guard: SlockAvatarCacheCommitGuard
    ): SlockAvatarCacheCommitResult =
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
        key: SlockAvatarCacheKey,
        expectedCommittedState: SlockAvatarCacheStoredState,
        previousState: SlockAvatarCacheStoredState,
        guard: SlockAvatarCacheCommitGuard
    ): SlockAvatarCacheCommitResult =
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

    override suspend fun clearFailure(key: SlockAvatarCacheKey) {
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

    override suspend fun clearTemporaryFailures(owner: SlockAvatarCacheOwner) {
        database.queries.clearTemporaryAvatarCacheFailuresForOwner(
            environmentId = owner.environmentId,
            accountId = owner.accountId,
            serverId = owner.serverId,
            failureKind = SlockAvatarCacheFailureKind.TemporaryTransport.storageValue
        )
    }

    override suspend fun clearSubject(owner: SlockAvatarCacheOwner, subject: SlockAvatarCacheSubject) {
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

    override suspend fun clearOwner(owner: SlockAvatarCacheOwner) {
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
        key: SlockAvatarCacheKey,
        guard: SlockAvatarCacheCommitGuard,
        write: SlockSqlDatabase.() -> Unit
    ): SlockAvatarCacheCommitResult =
        try {
            database.transaction {
                if (!guard.allows(key)) throw AvatarCacheCommitRevoked
                write()
                if (!guard.allows(key)) throw AvatarCacheCommitRevoked
            }
            SlockAvatarCacheCommitResult.Accepted
        } catch (_: AvatarCacheCommitRevoked) {
            SlockAvatarCacheCommitResult.RejectedStaleGeneration
        }
}

internal fun slockAvatarDurableCommitOrNull(
    commitGeneration: Long?,
    commitNonce: String?,
    modelRevision: Long?
): Pair<SlockAvatarCommitIdentity, Long>? {
    if (commitGeneration == null || commitNonce == null || modelRevision == null) return null
    if (modelRevision <= 0L) return null
    val identity =
        runCatching { SlockAvatarCommitIdentity(commitGeneration, commitNonce) }
            .getOrNull()
            ?: return null
    return identity to modelRevision
}

/** task #343: inverse of [SlockAvatarCacheSubject] storage columns; null when not representable. */
internal fun slockAvatarCacheSubjectFromStorage(
    subjectKind: String,
    subjectId: String,
    memberKind: String
): SlockAvatarCacheSubject? =
    runCatching {
        when (subjectKind) {
            "member" ->
                when (memberKind) {
                    "human" -> SlockAvatarCacheSubject.Member(MemberKind.Human, subjectId)
                    "agent" -> SlockAvatarCacheSubject.Member(MemberKind.Agent, subjectId)
                    else -> null
                }
            "server" -> SlockAvatarCacheSubject.Server(subjectId)
            else -> null
        }
    }.getOrNull()
