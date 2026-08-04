package build.raft.app.data

/**
 * Binds a native response to one exact owner/subject/content request generation. The callback must
 * become false when either the owner binding or that subject's selected content generation changes.
 */
internal class SlockAvatarCacheCommitGuard(
    val key: SlockAvatarCacheKey,
    private val isCurrentGeneration: () -> Boolean
) {
    fun allows(candidate: SlockAvatarCacheKey): Boolean =
        candidate == key && isCurrentGeneration()
}

internal enum class SlockAvatarCacheCommitResult {
    Accepted,
    RejectedStaleGeneration
}

internal interface SlockAvatarCacheMetadataStore {
    suspend fun read(key: SlockAvatarCacheKey): SlockAvatarCacheStoredState

    suspend fun commitSuccess(
        metadata: SlockAvatarCacheMetadata,
        guard: SlockAvatarCacheCommitGuard
    ): SlockAvatarCacheCommitResult

    suspend fun commitFailure(
        failure: SlockAvatarCacheFailure,
        retireMatchingMetadata: Boolean,
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
    override suspend fun read(key: SlockAvatarCacheKey): SlockAvatarCacheStoredState {
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
            ).executeAsOneOrNull()?.let { row ->
                val storedKey =
                    SlockAvatarCacheKey(
                        owner = owner,
                        subject = subject,
                        content =
                            SlockAvatarContentIdentity(
                                kind = SlockAvatarContentKind.fromStorage(row.contentKind),
                                fingerprint = row.contentFingerprint
                            )
                    )
                SlockAvatarCacheMetadata(
                    key = storedKey,
                    eTag = row.eTag,
                    lastModified = row.lastModified,
                    fetchedAtMs = row.fetchedAtMs,
                    validatedAtMs = row.validatedAtMs,
                    freshUntilMs = row.freshUntilMs,
                    staleUntilMs = row.staleUntilMs,
                    sizeBytes = row.sizeBytes,
                    contentType = row.contentType
                )
            }
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
                contentType = metadata.contentType
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
