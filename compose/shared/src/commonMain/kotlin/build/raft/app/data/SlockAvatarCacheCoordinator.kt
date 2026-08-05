package build.raft.app.data

internal sealed interface SlockAvatarCacheMutationResult {
    data object Accepted : SlockAvatarCacheMutationResult
    data object RejectedStaleGeneration : SlockAvatarCacheMutationResult
    data object MissingCurrentMetadata : SlockAvatarCacheMutationResult
    data class RejectedTooLarge(val sizeBytes: Long, val maxBytes: Long) : SlockAvatarCacheMutationResult
}

internal data class SlockAvatarCacheValidators(
    val eTag: String?,
    val lastModified: String?
)

internal data class SlockAvatarCachePreparedRead(
    val decision: SlockAvatarCacheReadDecision,
    val validators: SlockAvatarCacheValidators?
)

/**
 * Shared metadata/policy coordinator. It never downloads, decodes, stores, or deletes image bytes;
 * platform adapters remain the single owners of those operations and report their observed state.
 */
internal class SlockAvatarCacheCoordinator(
    private val store: SlockAvatarCacheMetadataStore,
    private val nowMillis: () -> Long
) {
    suspend fun prepareRead(
        key: SlockAvatarCacheKey,
        bytesAvailable: Boolean,
        bytesDecodable: Boolean
    ): SlockAvatarCachePreparedRead {
        val stored = store.read(key)
        val metadata = stored.metadata?.takeIf { it.key == key }
        return SlockAvatarCachePreparedRead(
            decision =
                SlockAvatarCachePolicy.decideRead(
                    key = key,
                    stored = stored,
                    bytesAvailable = bytesAvailable,
                    bytesDecodable = bytesDecodable,
                    nowMs = nowMillis()
                ),
            validators =
                metadata?.let {
                    SlockAvatarCacheValidators(
                        eTag = it.eTag,
                        lastModified = it.lastModified
                    )
                }
        )
    }

    suspend fun decideRead(
        key: SlockAvatarCacheKey,
        bytesAvailable: Boolean,
        bytesDecodable: Boolean
    ): SlockAvatarCacheReadDecision =
        prepareRead(key, bytesAvailable, bytesDecodable).decision

    suspend fun storedState(key: SlockAvatarCacheKey): SlockAvatarCacheStoredState =
        store.read(key)

    suspend fun reportSuccessfulBytes(
        key: SlockAvatarCacheKey,
        sizeBytes: Long,
        eTag: String?,
        lastModified: String?,
        contentType: String?,
        guard: SlockAvatarCacheCommitGuard
    ): SlockAvatarCacheMutationResult =
        when (
            val decision =
                SlockAvatarCachePolicy.successfulResponse(
                    key = key,
                    nowMs = nowMillis(),
                    sizeBytes = sizeBytes,
                    eTag = eTag,
                    lastModified = lastModified,
                    contentType = contentType
                )
        ) {
            is SlockAvatarCacheWriteDecision.RejectTooLarge ->
                SlockAvatarCacheMutationResult.RejectedTooLarge(decision.sizeBytes, decision.maxBytes)
            is SlockAvatarCacheWriteDecision.Store ->
                store.commitSuccess(decision.metadata, guard).toMutationResult()
        }

    suspend fun reportNotModified(
        key: SlockAvatarCacheKey,
        eTag: String?,
        lastModified: String?,
        guard: SlockAvatarCacheCommitGuard
    ): SlockAvatarCacheMutationResult {
        val existing = store.read(key).metadata?.takeIf { it.key == key }
            ?: return SlockAvatarCacheMutationResult.MissingCurrentMetadata
        val refreshed =
            SlockAvatarCachePolicy.notModified(
                existing = existing,
                nowMs = nowMillis(),
                eTag = eTag ?: existing.eTag,
                lastModified = lastModified ?: existing.lastModified
            )
        return store.commitSuccess(refreshed, guard).toMutationResult()
    }

    suspend fun reportFailure(
        key: SlockAvatarCacheKey,
        kind: SlockAvatarCacheFailureKind,
        guard: SlockAvatarCacheCommitGuard
    ): SlockAvatarCacheMutationResult {
        val previous = store.read(key).failure
        val failure = SlockAvatarCachePolicy.failure(key, kind, previous, nowMillis())
        return store.commitFailure(
            failure = failure,
            retireMatchingMetadata = kind != SlockAvatarCacheFailureKind.TemporaryTransport,
            guard = guard
        ).toMutationResult()
    }

    suspend fun onNetworkChanged(owner: SlockAvatarCacheOwner) {
        store.clearTemporaryFailures(owner)
    }

    suspend fun restoreAfterNativeFinalizeFailure(
        key: SlockAvatarCacheKey,
        expectedCommittedState: SlockAvatarCacheStoredState,
        previousState: SlockAvatarCacheStoredState,
        guard: SlockAvatarCacheCommitGuard
    ): SlockAvatarCacheMutationResult =
        store.restoreAfterNativeFinalizeFailure(
            key = key,
            expectedCommittedState = expectedCommittedState,
            previousState = previousState,
            guard = guard
        ).toMutationResult()

    suspend fun clearSubject(owner: SlockAvatarCacheOwner, subject: SlockAvatarCacheSubject) {
        store.clearSubject(owner, subject)
    }

    suspend fun clearOwner(owner: SlockAvatarCacheOwner) {
        store.clearOwner(owner)
    }

    suspend fun clearAll() {
        store.clearAll()
    }
}

private fun SlockAvatarCacheCommitResult.toMutationResult(): SlockAvatarCacheMutationResult =
    when (this) {
        SlockAvatarCacheCommitResult.Accepted -> SlockAvatarCacheMutationResult.Accepted
        SlockAvatarCacheCommitResult.RejectedStaleGeneration ->
            SlockAvatarCacheMutationResult.RejectedStaleGeneration
    }
