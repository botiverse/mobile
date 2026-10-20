package build.raft.app.data

internal sealed interface RaftAvatarCacheMutationResult {
    data object Accepted : RaftAvatarCacheMutationResult
    data object RejectedStaleGeneration : RaftAvatarCacheMutationResult
    data object MissingCurrentMetadata : RaftAvatarCacheMutationResult
    data class RejectedTooLarge(val sizeBytes: Long, val maxBytes: Long) : RaftAvatarCacheMutationResult
}

internal data class RaftAvatarCacheValidators(
    val eTag: String?,
    val lastModified: String?
)

internal data class RaftAvatarCachePreparedRead(
    val decision: RaftAvatarCacheReadDecision,
    val validators: RaftAvatarCacheValidators?,
    val commitIdentity: RaftAvatarCommitIdentity?,
    val modelRevision: Long
)

/**
 * Shared metadata/policy coordinator. It never downloads, decodes, stores, or deletes image bytes;
 * platform adapters remain the single owners of those operations and report their observed state.
 */
internal class RaftAvatarCacheCoordinator(
    private val store: RaftAvatarCacheMetadataStore,
    private val nowMillis: () -> Long
) {
    suspend fun prepareRead(
        key: RaftAvatarCacheKey,
        bytesAvailable: Boolean,
        bytesDecodable: Boolean
    ): RaftAvatarCachePreparedRead {
        val stored = store.read(key)
        val metadata =
            stored.metadata?.takeIf { it.key == key && it.hasExactCommitIdentity }
        return RaftAvatarCachePreparedRead(
            decision =
                RaftAvatarCachePolicy.decideRead(
                    key = key,
                    stored = stored,
                    bytesAvailable = bytesAvailable,
                    bytesDecodable = bytesDecodable,
                    nowMs = nowMillis()
                ),
            validators =
                metadata
                    // task #289: only offer revalidation validators when the local bytes can
                    // actually back a 304. After a cache purge the metadata row can outlive the
                    // file; sending If-None-Match then earns a 304 that re-points the renderer
                    // at a descriptor whose bytes are gone, and the avatar can never recover.
                    ?.takeIf { bytesAvailable && bytesDecodable }
                    ?.let {
                        RaftAvatarCacheValidators(
                            eTag = it.eTag,
                            lastModified = it.lastModified
                        )
                    }
                    ?.takeIf { it.eTag != null || it.lastModified != null },
            commitIdentity = metadata?.commitIdentity,
            modelRevision = metadata?.modelRevision ?: 0L
        )
    }

    suspend fun decideRead(
        key: RaftAvatarCacheKey,
        bytesAvailable: Boolean,
        bytesDecodable: Boolean
    ): RaftAvatarCacheReadDecision =
        prepareRead(key, bytesAvailable, bytesDecodable).decision

    suspend fun storedState(key: RaftAvatarCacheKey): RaftAvatarCacheStoredState =
        store.read(key)

    /** task #343: committed, unsuppressed rows for [owner], newest first (prewarm only). */
    suspend fun committedForOwner(
        owner: RaftAvatarCacheOwner,
        limit: Long
    ): List<RaftAvatarCacheMetadata> =
        store.readCommittedForOwner(owner, nowMillis(), limit)

    suspend fun reportSuccessfulBytes(
        key: RaftAvatarCacheKey,
        sizeBytes: Long,
        expectedCommitIdentity: RaftAvatarCommitIdentity?,
        expectedModelRevision: Long,
        commitIdentity: RaftAvatarCommitIdentity,
        modelRevision: Long,
        eTag: String?,
        lastModified: String?,
        contentType: String?,
        guard: RaftAvatarCacheCommitGuard
    ): RaftAvatarCacheMutationResult =
        if (
            guard.expectedCommit !=
                RaftAvatarCacheCommitExpectation(key, expectedCommitIdentity, expectedModelRevision)
        ) {
            RaftAvatarCacheMutationResult.RejectedStaleGeneration
        } else {
            when (
                val decision =
                    RaftAvatarCachePolicy.successfulResponse(
                        key = key,
                        nowMs = nowMillis(),
                        sizeBytes = sizeBytes,
                        commitIdentity = commitIdentity,
                        modelRevision = modelRevision,
                        eTag = eTag,
                        lastModified = lastModified,
                        contentType = contentType
                    )
            ) {
                is RaftAvatarCacheWriteDecision.RejectTooLarge ->
                    RaftAvatarCacheMutationResult.RejectedTooLarge(decision.sizeBytes, decision.maxBytes)
                is RaftAvatarCacheWriteDecision.Store ->
                    store.commitSuccess(decision.metadata, guard).toMutationResult()
            }
        }

    suspend fun reportNotModified(
        key: RaftAvatarCacheKey,
        expectedCommitIdentity: RaftAvatarCommitIdentity?,
        expectedModelRevision: Long,
        eTag: String?,
        lastModified: String?,
        guard: RaftAvatarCacheCommitGuard
    ): RaftAvatarCacheMutationResult {
        if (
            guard.expectedCommit !=
                RaftAvatarCacheCommitExpectation(key, expectedCommitIdentity, expectedModelRevision)
        ) {
            return RaftAvatarCacheMutationResult.RejectedStaleGeneration
        }
        val existing = store.read(key).metadata?.takeIf {
            it.key == key &&
                expectedCommitIdentity != null &&
                it.commitIdentity == expectedCommitIdentity &&
                it.modelRevision == expectedModelRevision
        }
            ?: return RaftAvatarCacheMutationResult.MissingCurrentMetadata
        val refreshed =
            RaftAvatarCachePolicy.notModified(
                existing = existing,
                nowMs = nowMillis(),
                eTag = eTag ?: existing.eTag,
                lastModified = lastModified ?: existing.lastModified
            )
        return store.commitSuccess(refreshed, guard).toMutationResult()
    }

    /**
     * Rehydrates only exact durable identity into a process-local mounted session. Legacy or
     * malformed rows remain a full-body miss and never carry their validators across restart.
     */
    suspend fun seedMountedSession(
        session: RaftAvatarMountedSession,
        token: RaftAvatarMountToken
    ): Boolean {
        val metadata = store.read(token.key).metadata
            ?.takeIf { it.key == token.key && it.hasExactCommitIdentity }
            ?: return false
        return session.seedDurable(token, metadata)
    }

    suspend fun reportFailure(
        key: RaftAvatarCacheKey,
        kind: RaftAvatarCacheFailureKind,
        guard: RaftAvatarCacheCommitGuard
    ): RaftAvatarCacheMutationResult {
        val previous = store.read(key).failure
        val failure = RaftAvatarCachePolicy.failure(key, kind, previous, nowMillis())
        return store.commitFailure(
            failure = failure,
            retireMatchingMetadata = kind != RaftAvatarCacheFailureKind.TemporaryTransport,
            guard = guard
        ).toMutationResult()
    }

    suspend fun onNetworkChanged(owner: RaftAvatarCacheOwner) {
        store.clearTemporaryFailures(owner)
    }

    suspend fun restoreAfterNativeFinalizeFailure(
        key: RaftAvatarCacheKey,
        expectedCommittedState: RaftAvatarCacheStoredState,
        previousState: RaftAvatarCacheStoredState,
        guard: RaftAvatarCacheCommitGuard
    ): RaftAvatarCacheMutationResult =
        store.restoreAfterNativeFinalizeFailure(
            key = key,
            expectedCommittedState = expectedCommittedState,
            previousState = previousState,
            guard = guard
        ).toMutationResult()

    suspend fun clearSubject(owner: RaftAvatarCacheOwner, subject: RaftAvatarCacheSubject) {
        store.clearSubject(owner, subject)
    }

    suspend fun clearOwner(owner: RaftAvatarCacheOwner) {
        store.clearOwner(owner)
    }

    suspend fun clearAll() {
        store.clearAll()
    }
}

private fun RaftAvatarCacheCommitResult.toMutationResult(): RaftAvatarCacheMutationResult =
    when (this) {
        RaftAvatarCacheCommitResult.Accepted -> RaftAvatarCacheMutationResult.Accepted
        RaftAvatarCacheCommitResult.RejectedStaleGeneration ->
            RaftAvatarCacheMutationResult.RejectedStaleGeneration
    }
