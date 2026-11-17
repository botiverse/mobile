package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.ScreenError
import build.raft.app.data.StoredAuthSession
import build.raft.app.network.sync.DurablePartitionKey

internal data class ReplyThreadResolveKey(
    val namespaceId: String,
    val parentChannelId: String,
    val parentMessageId: String
)

internal data class ReplyCacheProvenance(
    val environmentId: String,
    val principalId: String,
    val serverId: String,
    val generation: Long,
    val authGeneration: Long = 0L,
    val selectionGeneration: Long = 0L,
    /** Stable process-auth binding; unchanged by transparent token rotation. */
    val bindingGeneration: Long = 0L,
) {
    val namespaceId: String?
        get() =
            takeIf { environmentId.isNotBlank() && principalId.isNotBlank() && serverId.isNotBlank() }
                ?.let { identity ->
                    listOf(identity.environmentId, identity.principalId, identity.serverId).joinToString("\u001f")
                }

    val messageStoreScopeId: String?
        get() = namespaceId?.let { namespace -> "$namespace\u001f$generation" }
}

/**
 * A send captured under [expected] may finish after a transparent token rotation. The process
 * auth binding generation is deliberately separate from [ReplyCacheProvenance.authGeneration]:
 * the latter advances for every credential revision, while the former changes only when the
 * environment/account binding is replaced. Selection remains exact because changing servers is
 * a route/data boundary even when the principal is unchanged.
 */
internal fun ReplyCacheProvenance.isCurrentOrSuccessorForLocalSend(
    expected: ReplyCacheProvenance
): Boolean {
    if (this == expected) return true
    return environmentId.isNotBlank() &&
        principalId.isNotBlank() &&
        serverId.isNotBlank() &&
        expected.environmentId.isNotBlank() &&
        expected.principalId.isNotBlank() &&
        expected.serverId.isNotBlank() &&
        threadPendingSendOwnerScopeId != null &&
        expected.threadPendingSendOwnerScopeId != null &&
        bindingGeneration > 0L &&
        expected.bindingGeneration > 0L &&
        bindingGeneration == expected.bindingGeneration &&
        environmentId == expected.environmentId &&
        principalId == expected.principalId &&
        serverId == expected.serverId &&
        authGeneration >= expected.authGeneration &&
        selectionGeneration == expected.selectionGeneration
}

internal fun ReplyCacheProvenance?.currentRevisionForReplyCacheOwner(
    owner: ReplyCacheProvenance?
): ReplyCacheProvenance? {
    val ownerNamespaceId = owner?.namespaceId ?: return null
    return this?.takeIf { current -> current.namespaceId == ownerNamespaceId }
}

/**
 * One owner key for every reply cache (in-memory payload cache and durable pending-send
 * journal). There is no legacy namespace: an absent environment, principal, or server cannot
 * prove which realm owns the cached rows, so the owner is `null` and every cache fails closed
 * instead of collapsing unknown identities into one shared bucket (task #1121).
 *
 * `authGeneration` is the auth epoch of `RealtimeAuthorityRegistry`, whose `Empty` revision is
 * generation `0` with no session and whose every advance starts at `1`; `0` is therefore the
 * "no authenticated session" sentinel and is rejected here exactly as the realtime witness
 * rejects it. `selectionGeneration` has no sentinel: `SelectedServerStore` writes its first
 * durable selection at epoch `0`, and an unselected server is already excluded by the blank
 * `serverId`.
 */
internal val ReplyCacheProvenance.replyCacheOwnerScopeId: String?
    get() =
        takeIf { provenance ->
            provenance.environmentId.isNotBlank() &&
                provenance.principalId.isNotBlank() &&
                provenance.serverId.isNotBlank() &&
                provenance.authGeneration > 0L
        }
            ?.let { provenance ->
                listOf(
                    provenance.environmentId,
                    provenance.principalId,
                    provenance.serverId,
                    provenance.authGeneration.toString(),
                    provenance.selectionGeneration.toString()
                ).joinToString("\u001f")
            }

/**
 * Timeline (payload) cache owner: environment + account + server only (data-layer plan step 2).
 *
 * Auth and server-selection generations are deliberately NOT part of this key, so switching
 * servers A -> B -> A or a same-account token rotation reaches the same cached timelines again.
 * Admission is still [replyCacheOwnerScopeId]'s: a blank segment or the auth "no session"
 * sentinel owns no timeline cache. The generations survive as a write fence instead
 * ([isSameTimelineCacheAccount]): a late response is dropped only when the ACCOUNT or the
 * ENVIRONMENT changed since its request, never because the user switched servers or the token
 * rotated (a late server-A response is still server-A data and is written to A's key).
 *
 * The pending-send journal keeps the generation-exact [threadPendingSendOwnerScopeId].
 */
internal val ReplyCacheProvenance.threadPayloadOwnerScopeId: String?
    get() =
        replyCacheOwnerScopeId?.let {
            sharedThreadTimelineOwnerScopeId(environmentId, principalId, serverId)
        }

internal fun sharedThreadTimelineOwnerScopeId(
    environmentId: String,
    principalId: String,
    serverId: String
): String = listOf(environmentId, principalId, serverId).joinToString("\u001f")

/** Prefix shared by every timeline owner of one environment + account (all its servers). */
internal fun sharedThreadTimelineAccountScopePrefix(
    environmentId: String,
    principalId: String
): String = listOf(environmentId, principalId, "").joinToString("\u001f")

/**
 * Tenny rule 1 write fence for the timeline cache: same environment and same account. The
 * process-auth binding is compared when both sides know it, because a replaced binding (logout
 * then login) is an account boundary even for the same principal id. Server selection and the
 * auth (token) generation are intentionally ignored.
 */
internal fun ReplyCacheProvenance.isSameTimelineCacheAccount(other: ReplyCacheProvenance): Boolean =
    environmentId.isNotBlank() &&
        principalId.isNotBlank() &&
        environmentId == other.environmentId &&
        principalId == other.principalId &&
        (bindingGeneration <= 0L || other.bindingGeneration <= 0L || bindingGeneration == other.bindingGeneration)

/**
 * The durable message partition this provenance may read (task #1123). It is the same identity
 * the realtime writer persists under (`RealtimeGrantRecord.durablePartition`: normalized
 * environment, principal, server) — generations are deliberately not part of the on-disk key.
 * Admission reuses [replyCacheOwnerScopeId]: a blank segment or the auth "no session" sentinel
 * owns no partition, and a segment that is not already canonical (surrounding whitespace) is
 * refused rather than normalized here, because the writer never produces such a key.
 */
internal fun ReplyCacheProvenance.durableMessagePartitionOrNull(consumerId: String): DurablePartitionKey? {
    replyCacheOwnerScopeId ?: return null
    return runCatching {
        DurablePartitionKey(
            serverId = serverId,
            principalId = principalId,
            consumerId = consumerId,
            environmentId = environmentId,
        )
    }.getOrNull()
}

internal val ReplyCacheProvenance.threadPendingSendOwnerScopeId: String?
    get() = replyCacheOwnerScopeId

internal data class ReplyResolutionScope(
    val session: StoredAuthSession,
    val provenance: ReplyCacheProvenance,
    val sharedMessageStore: Boolean
) {
    val namespaceId: String? get() = provenance.namespaceId
    val messageStoreScopeId: String?
        get() =
            provenance.messageStoreScopeId
                ?: provenance.serverId.takeIf { !sharedMessageStore && it.isNotBlank() }
}

internal data class ReplyCacheProducerContext(
    val session: StoredAuthSession,
    val provenance: ReplyCacheProvenance
)

internal data class SharedThreadPayloadCacheKey(
    val serverId: String,
    val threadId: String
)

/**
 * Owner-scoped payload cache key. `ownerScopeId` is REQUIRED and must already be a
 * [replyCacheOwnerScopeId]; a blank owner is rejected instead of being mapped onto a shared
 * legacy bucket, because an unknown owner can never be proven equal to another unknown owner
 * (task #1121, same rule as `DurablePartitionKey`).
 */
internal data class SharedThreadOwnedPayloadCacheKey(
    val ownerScopeId: String,
    val payloadKey: SharedThreadPayloadCacheKey
) {
    init {
        requireSharedThreadOwnerScopeId(ownerScopeId)
    }
}

internal fun requireSharedThreadOwnerScopeId(ownerScopeId: String) {
    require(ownerScopeId.isNotBlank()) { "ownerScopeId must not be blank" }
}

internal fun sharedThreadOwnedPayloadCacheKey(
    ownerScopeId: String,
    serverId: String,
    threadId: String
): SharedThreadOwnedPayloadCacheKey =
    SharedThreadOwnedPayloadCacheKey(
        ownerScopeId = ownerScopeId,
        payloadKey = SharedThreadPayloadCacheKey(serverId, threadId)
    )

/**
 * Tenny rule 3 caps for the in-memory timeline cache ([SharedThreadPayloadStore]), LRU by last
 * read or write. 20 per server covers the conversations a user moves between in one session (the
 * durable V2 partition keeps 64 channel/DM + 24 reply-thread scopes per server on disk); 60 in
 * total bounds memory to roughly three busy servers' worth of one-page payloads.
 */
internal const val SharedThreadTimelineCacheMaxConversationsPerServer = 20
internal const val SharedThreadTimelineCacheMaxConversations = 60
internal const val ReplyCacheProvenanceStaleCode = "thread_reply_cache_provenance_stale"

internal fun <T> staleReplyCacheProvenanceResult(): DataResult<T> =
    DataResult.Error(ScreenError(code = ReplyCacheProvenanceStaleCode, retryable = false))
