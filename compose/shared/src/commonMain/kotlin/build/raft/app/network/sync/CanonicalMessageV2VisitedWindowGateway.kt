package build.raft.app.network.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface CanonicalMessageV2VisitedRealtimeResult {
    data object NotVisited : CanonicalMessageV2VisitedRealtimeResult
    data object Committed : CanonicalMessageV2VisitedRealtimeResult
    data class RefreshUnavailable(val diagnostic: String) : CanonicalMessageV2VisitedRealtimeResult
}

/**
 * Durable visited-window sidecar. It serializes authoritative snapshot fetch+commit and later realtime
 * writes per exact scope, but never owns UI fan-out or sole-apply routing.
 */
internal class CanonicalMessageV2VisitedWindowGateway(
    private val generationEngine: CanonicalMessageV2GenerationEngine,
    private val isEnabledForServer: (String) -> Boolean,
    private val logger: (String) -> Unit = {},
) {
    private val registryMutex = Mutex()
    private val visitedScopes = mutableSetOf<SyncScopeKey>()

    init {
        generationEngine.addScopeInvalidationObserver { scopes ->
            registryMutex.withLock { visitedScopes.removeAll(scopes) }
        }
    }

    suspend fun refresh(
        scope: SyncScopeKey,
        loader: CanonicalMessageV2SnapshotLoader,
    ): CanonicalMessageV2SnapshotLoadResult {
        requireV2Scope(scope)
        return generationEngine.withScopeWriter(scope) {
            val partitionGeneration = generationEngine.partitionGeneration(scope.partition)
            if (!isEnabledForServer(scope.serverId)) {
                clearScopeInsideScopeLock(scope)
                return@withScopeWriter loader.load(CanonicalMessageV2SnapshotRequest(scope, targetFactId = ""))
            }
            when (val loaded = loader.load(CanonicalMessageV2SnapshotRequest(scope, targetFactId = ""))) {
                is CanonicalMessageV2SnapshotLoadResult.Unavailable -> {
                    generationEngine.rebaselineFailed(scope)
                    if (loaded.diagnostic.requiresVisitedScopePurge()) {
                        generationEngine.clearScope(scope)
                        registryMutex.withLock { visitedScopes.remove(scope) }
                    }
                    loaded
                }
                is CanonicalMessageV2SnapshotLoadResult.Loaded -> {
                    if (loaded.frame.scope != scope) {
                        generationEngine.rebaselineFailed(scope)
                        return@withScopeWriter CanonicalMessageV2SnapshotLoadResult.Unavailable(
                            "canonical_v2_snapshot_scope_mismatch",
                        )
                    }
                    val committed = generationEngine.applySnapshot(
                        loaded.frame,
                        expectedPartitionGeneration = partitionGeneration,
                    )
                    when (committed) {
                        CanonicalMessageV2CommitResult.Committed ->
                            registryMutex.withLock { visitedScopes.add(scope) }
                        CanonicalMessageV2CommitResult.PartitionRevoked ->
                            return@withScopeWriter CanonicalMessageV2SnapshotLoadResult.Unavailable(
                                "canonical_v2_auth_changed_partition_generation",
                            )
                        CanonicalMessageV2CommitResult.ScopeEvicted ->
                            return@withScopeWriter CanonicalMessageV2SnapshotLoadResult.Unavailable(
                                "canonical_v2_retention_evicted_scope",
                            )
                        is CanonicalMessageV2CommitResult.BaselineRequired ->
                            error("snapshot commit cannot require a baseline")
                    }
                    logger(
                        "canonical_v2_visited baseline server=${scope.serverId} " +
                            "scope=${scope.scopeKind}/${scope.scopeId}",
                    )
                    loaded
                }
            }
        }
    }

    suspend fun refreshPreservingWindow(
        scope: SyncScopeKey,
        loader: CanonicalMessageV2SnapshotLoader,
    ): CanonicalMessageV2SnapshotLoadResult {
        requireV2Scope(scope)
        val partitionGeneration = generationEngine.partitionGeneration(scope.partition)
        return generationEngine.withScopeWriter(scope) {
            if (!isEnabledForServer(scope.serverId)) {
                clearScopeInsideScopeLock(scope)
                return@withScopeWriter loader.load(CanonicalMessageV2SnapshotRequest(scope, targetFactId = ""))
            }
            val restored = generationEngine.restore(scope)
            when (val loaded = loader.load(CanonicalMessageV2SnapshotRequest(scope, targetFactId = ""))) {
                is CanonicalMessageV2SnapshotLoadResult.Unavailable -> {
                    generationEngine.rebaselineFailed(scope)
                    if (loaded.diagnostic.requiresVisitedScopePurge()) {
                        clearScopeInsideScopeLock(scope)
                    }
                    loaded
                }
                is CanonicalMessageV2SnapshotLoadResult.Loaded -> {
                    if (loaded.frame.scope != scope) {
                        generationEngine.rebaselineFailed(scope)
                        return@withScopeWriter CanonicalMessageV2SnapshotLoadResult.Unavailable(
                            "canonical_v2_snapshot_scope_mismatch",
                        )
                    }
                    val replacementIds = loaded.frame.envelopes.mapTo(mutableSetOf()) { it.fact.id }
                    val mergedFrame = if (restored.cursor == null) {
                        loaded.frame
                    } else {
                        loaded.frame.copy(
                            envelopes = restored.envelopes.filterNot { it.fact.id in replacementIds } + loaded.frame.envelopes,
                            cursor = restored.cursor.preservedWithExactFacts(loaded.frame.cursor),
                        )
                    }
                    when (
                        generationEngine.applySnapshot(
                            mergedFrame,
                            expectedPartitionGeneration = partitionGeneration,
                        )
                    ) {
                        CanonicalMessageV2CommitResult.Committed -> {
                            registryMutex.withLock { visitedScopes.add(scope) }
                            loaded
                        }
                        CanonicalMessageV2CommitResult.PartitionRevoked ->
                            CanonicalMessageV2SnapshotLoadResult.Unavailable(
                                "canonical_v2_auth_changed_partition_generation",
                            )
                        CanonicalMessageV2CommitResult.ScopeEvicted ->
                            CanonicalMessageV2SnapshotLoadResult.Unavailable(
                                "canonical_v2_retention_evicted_scope",
                            )
                        is CanonicalMessageV2CommitResult.BaselineRequired ->
                            error("snapshot commit cannot require a baseline")
                    }
                }
            }
        }
    }

    suspend fun acceptRealtime(
        partition: DurablePartitionKey,
        foldInput: CanonicalMessageFoldInput,
        refreshLoader: CanonicalMessageV2SnapshotLoader,
    ): CanonicalMessageV2VisitedRealtimeResult =
        acceptRealtime(
            partition = partition,
            foldInput = foldInput,
            refreshLoader = refreshLoader,
            runIfCurrentCommit = ::runVisitedCommitUnconditionally,
        )

    suspend fun acceptRealtime(
        partition: DurablePartitionKey,
        foldInput: CanonicalMessageFoldInput,
        refreshLoader: CanonicalMessageV2SnapshotLoader,
        runIfCurrentCommit: suspend (suspend () -> Unit) -> Boolean,
    ): CanonicalMessageV2VisitedRealtimeResult {
        require(partition.consumerId == SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
        val partitionGeneration = generationEngine.partitionGeneration(partition)
        val scope = foldInput.visitedV2ScopeOrNull(partition)
            ?: return CanonicalMessageV2VisitedRealtimeResult.NotVisited
        if (!registryMutex.withLock { scope in visitedScopes }) {
            return CanonicalMessageV2VisitedRealtimeResult.NotVisited
        }
        return generationEngine.withScopeWriter(scope) {
            if (!registryMutex.withLock { scope in visitedScopes }) {
                return@withScopeWriter CanonicalMessageV2VisitedRealtimeResult.NotVisited
            }
            if (!isEnabledForServer(scope.serverId)) {
                if (!runIfCurrentCommit { clearScopeInsideScopeLock(scope) }) {
                    return@withScopeWriter staleRealtimeGrant()
                }
                return@withScopeWriter CanonicalMessageV2VisitedRealtimeResult.NotVisited
            }
            if (foldInput.kind == CanonicalMessageFoldKind.New) {
                val envelope = foldInput.toCanonicalMessageV2Envelope(partition, replyCount = 0)
                if (envelope != null) {
                    var result: CanonicalMessageV2CommitResult? = null
                    var hadRestoredCursor = false
                    if (!runIfCurrentCommit {
                            val cursor = generationEngine.restore(scope).cursor
                            if (cursor != null) {
                                hadRestoredCursor = true
                                result = generationEngine.applyIncremental(
                                    CanonicalMessageV2IncrementalFrame(
                                        scope = scope,
                                        envelopes = listOf(envelope),
                                        cursor = cursor.advancedByVisited(envelope.fact.seq),
                                    ),
                                    expectedPartitionGeneration = partitionGeneration,
                                )
                            }
                        }
                    ) {
                        return@withScopeWriter staleRealtimeGrant()
                    }
                    if (hadRestoredCursor) {
                        return@withScopeWriter when (result) {
                            CanonicalMessageV2CommitResult.Committed -> CanonicalMessageV2VisitedRealtimeResult.Committed
                            is CanonicalMessageV2CommitResult.BaselineRequired ->
                                refreshInsideLock(
                                    scope,
                                    refreshLoader,
                                    partitionGeneration,
                                    runIfCurrentCommit,
                                )
                            CanonicalMessageV2CommitResult.PartitionRevoked ->
                                CanonicalMessageV2VisitedRealtimeResult.RefreshUnavailable(
                                    "canonical_v2_auth_changed_partition_generation",
                                )
                            CanonicalMessageV2CommitResult.ScopeEvicted ->
                                CanonicalMessageV2VisitedRealtimeResult.RefreshUnavailable(
                                    "canonical_v2_retention_evicted_scope",
                                )
                            null -> error("current visited commit gate returned without a result")
                        }
                    }
                }
            }
            refreshInsideLock(scope, refreshLoader, partitionGeneration, runIfCurrentCommit)
        }
    }

    suspend fun restore(scope: SyncScopeKey): RestoredCanonicalMessageV2Scope =
        generationEngine.withScopeWriter(scope) {
            if (!isEnabledForServer(scope.serverId)) {
                clearScopeInsideScopeLock(scope)
                return@withScopeWriter RestoredCanonicalMessageV2Scope(emptyList(), null)
            }
            try {
                generationEngine.restore(scope).also { restored ->
                    if (restored.cursor != null) registryMutex.withLock { visitedScopes.add(scope) }
                }
            } catch (poisoned: CanonicalMessageV2ScopePoisoned) {
                clearScopeInsideScopeLock(scope)
                throw poisoned
            }
        }

    suspend fun clearScope(scope: SyncScopeKey) {
        generationEngine.withScopeWriter(scope) {
            clearScopeInsideScopeLock(scope)
        }
    }

    suspend fun clearPartition(partition: DurablePartitionKey) {
        registryMutex.withLock { visitedScopes.removeAll { it.partition == partition } }
        generationEngine.clearPartition(partition)
    }

    suspend fun reconcileAuthorizedPartitions(
        environmentId: String,
        principalId: String?,
        serverIds: Set<String>,
    ) {
        val normalizedEnvironment = environmentId.trim().trimEnd('/')
        val normalizedPrincipal = principalId?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedServers = serverIds.mapTo(mutableSetOf()) { it.trim() }.filterTo(mutableSetOf()) { it.isNotEmpty() }
        generationEngine.knownPartitions().forEach { partition ->
            val authorized =
                normalizedPrincipal != null &&
                    partition.environmentId == normalizedEnvironment &&
                    partition.principalId == normalizedPrincipal &&
                    partition.serverId in normalizedServers
            if (!authorized) clearPartition(partition)
        }
    }

    private suspend fun refreshInsideLock(
        scope: SyncScopeKey,
        loader: CanonicalMessageV2SnapshotLoader,
        partitionGeneration: Long,
        runIfCurrentCommit: suspend (suspend () -> Unit) -> Boolean,
    ): CanonicalMessageV2VisitedRealtimeResult {
        return when (val loaded = loader.load(CanonicalMessageV2SnapshotRequest(scope, targetFactId = ""))) {
            is CanonicalMessageV2SnapshotLoadResult.Unavailable -> {
                if (!runIfCurrentCommit {
                        generationEngine.rebaselineFailed(scope)
                        if (loaded.diagnostic.requiresVisitedScopePurge()) {
                            clearScopeInsideScopeLock(scope)
                        }
                    }
                ) {
                    return staleRealtimeGrant()
                }
                CanonicalMessageV2VisitedRealtimeResult.RefreshUnavailable(loaded.diagnostic)
            }
            is CanonicalMessageV2SnapshotLoadResult.Loaded -> {
                if (loaded.frame.scope != scope) {
                    if (!runIfCurrentCommit { generationEngine.rebaselineFailed(scope) }) {
                        return staleRealtimeGrant()
                    }
                    CanonicalMessageV2VisitedRealtimeResult.RefreshUnavailable(
                        "canonical_v2_snapshot_scope_mismatch",
                    )
                } else {
                    var result: CanonicalMessageV2CommitResult? = null
                    if (!runIfCurrentCommit {
                            result = generationEngine.applySnapshot(
                                loaded.frame,
                                expectedPartitionGeneration = partitionGeneration,
                            )
                        }
                    ) {
                        return staleRealtimeGrant()
                    }
                    when (result) {
                        CanonicalMessageV2CommitResult.Committed -> {
                            registryMutex.withLock { visitedScopes.add(scope) }
                            CanonicalMessageV2VisitedRealtimeResult.Committed
                        }
                        CanonicalMessageV2CommitResult.PartitionRevoked ->
                            CanonicalMessageV2VisitedRealtimeResult.RefreshUnavailable(
                                "canonical_v2_auth_changed_partition_generation",
                            )
                        CanonicalMessageV2CommitResult.ScopeEvicted ->
                            CanonicalMessageV2VisitedRealtimeResult.RefreshUnavailable(
                                "canonical_v2_retention_evicted_scope",
                            )
                        is CanonicalMessageV2CommitResult.BaselineRequired ->
                            error("snapshot commit cannot require a baseline")
                        null -> error("current visited snapshot gate returned without a result")
                    }
                }
            }
        }
    }

    private fun staleRealtimeGrant(): CanonicalMessageV2VisitedRealtimeResult.RefreshUnavailable =
        CanonicalMessageV2VisitedRealtimeResult.RefreshUnavailable("canonical_v2_stale_realtime_grant")

    private suspend fun clearScopeInsideScopeLock(scope: SyncScopeKey) {
        generationEngine.clearScope(scope)
        registryMutex.withLock { visitedScopes.remove(scope) }
    }
}

internal fun CanonicalMessageFoldInput.visitedV2ScopeOrNull(
    partition: DurablePartitionKey,
): SyncScopeKey? {
    val context = row.conversationContext ?: return null
    val kind = context.channelType.trim()
    return when (kind) {
        "channel", "private", "dm", "joint" -> {
            if (
                context.parentChannelId != null ||
                context.parentChannelType != null ||
                context.parentMessageId != null
            ) return null
            SyncScopeKey(partition, row.channelId, kind)
        }
        "thread" -> {
            val parentChannelId = context.parentChannelId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val parentMessageId = context.parentMessageId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val parentKind = context.parentChannelType?.trim()
                ?.takeIf { it in setOf("channel", "private", "dm", "joint") }
                ?: return null
            if (parentChannelId == row.channelId || parentMessageId == row.id && row.channelId.isBlank()) return null
            if (parentKind.isBlank()) return null
            SyncScopeKey(partition, row.channelId, "thread")
        }
        else -> null
    }
}

private fun PersistedCursor.advancedByVisited(seq: Long): PersistedCursor =
    copy(
        latestSeq = maxOf(latestSeq ?: seq, seq),
        oldestLoadedSeq = minOf(oldestLoadedSeq ?: seq, seq),
        newestLoadedSeq = maxOf(newestLoadedSeq ?: seq, seq),
        hasLoadedNewest = true,
    )

private fun PersistedCursor.preservedWithExactFacts(exact: PersistedCursor): PersistedCursor =
    copy(
        latestSeq = maxOf(latestSeq ?: Long.MIN_VALUE, exact.latestSeq ?: Long.MIN_VALUE)
            .takeUnless { it == Long.MIN_VALUE },
        newestLoadedSeq = maxOf(newestLoadedSeq ?: Long.MIN_VALUE, exact.newestLoadedSeq ?: Long.MIN_VALUE)
            .takeUnless { it == Long.MIN_VALUE },
    )

private fun String.requiresVisitedScopePurge(): Boolean =
    startsWith("canonical_v2_snapshot_corrupt") ||
        startsWith("canonical_v2_access_revoked") ||
        startsWith("canonical_v2_auth_changed")

private suspend fun runVisitedCommitUnconditionally(block: suspend () -> Unit): Boolean {
    block()
    return true
}
