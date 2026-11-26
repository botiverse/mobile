package build.raft.app

import build.raft.app.data.compareActivitySeq
import build.raft.app.data.isCanonicalActivitySeq
import build.raft.app.data.map
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlin.time.TimeSource

private const val SharedThreadFreshnessMessageFactLimit = 512
private const val SharedThreadReplyCountFactLimit = 2_048
@RaftCoreInternalApi const val SharedThreadFreshnessCauseInitial = "initial"
/** artin #110: past this many live messages since the last other change, a generation is treated as moved by other causes. */
private const val SharedThreadRealtimeFoldMessageIdLimit = 64
class SharedThreadFreshnessGeneration @RaftCoreInternalApi constructor(
    @RaftCoreInternalApi val key: SharedThreadPayloadCacheKey,
    @RaftCoreInternalApi val ownerScopeId: String,
    /**
     * Why this generation replaced the previous one (diagnostics only: a refresh superseded by it
     * logs this as `thread_canonical_superseded cause=`). `initial` for the first capture.
     */
    @RaftCoreInternalApi val cause: String = SharedThreadFreshnessCauseInitial,
    /**
     * artin #110: for a generation moved by a live message event (an edit / delete / reaction /
     * reply), the last generation that was NOT moved that way. Null when this generation itself was
     * moved by something else (an invalidation, a removal, an owner change, a list re-read). See
     * [SharedThreadFreshnessLedger.realtimeMessageIdsSince].
     */
    private val realtimeBase: SharedThreadFreshnessGeneration? = null,
    /** The ids of the live messages that moved the generation since [nonRealtimeBase], in order (append-only). */
    @RaftCoreInternalApi val realtimeMessageIds: List<String> = emptyList(),
) {
    init {
        requireSharedThreadOwnerScopeId(ownerScopeId)
    }

    @RaftCoreInternalApi val nonRealtimeBase: SharedThreadFreshnessGeneration
        get() = realtimeBase ?: this
}

/**
 * Owner-less content-mutation token for one server/thread. Activity prewarm markers compare
 * these by identity to learn "canonical content changed since I looked"; they never select a
 * cached payload, so they carry no owner and must not be confused with
 * [SharedThreadFreshnessGeneration].
 */
class SharedThreadContentGeneration @RaftCoreInternalApi constructor(
    @RaftCoreInternalApi val key: SharedThreadPayloadCacheKey
)

@RaftCoreInternalApi data class SharedThreadSummaryFact(
    val replyCount: Int,
    val latestActivityMessageId: String?,
    val latestActivityPreview: String?,
    val latestActivityAt: String?,
    val lastMessageId: String?,
    val lastMessagePreview: String?,
    val lastMessageAt: String?,
    val latestActivitySeq: String? = null,
)

private data class SharedThreadMessageMutationKey(
    val key: SharedThreadPayloadCacheKey,
    val messageId: MessageId
)

private data class SharedThreadReplyCountKey(
    val key: SharedThreadPayloadCacheKey,
    val environmentId: String,
    val principalId: String,
    val parentMessageId: String
) {
    init {
        // Reply totals are server facts observed by one principal in one environment; an
        // unknown environment or principal must not share a fact bucket (task #1121).
        require(environmentId.isNotBlank()) { "environmentId must not be blank" }
        require(principalId.isNotBlank()) { "principalId must not be blank" }
    }
}

@RaftCoreInternalApi data class SharedThreadReplyCountFact(
    val threadChannelId: String?,
    val replyCount: Int,
    // Monotonic mark captured by the caller when the value was observed. The
    // ledger arbitrates same-message facts by recency so an older projection
    // cannot overwrite a newer realtime observation (e.g. reply deletions).
    val observedAt: TimeSource.Monotonic.ValueTimeMark
)

@RaftCoreInternalApi class SharedThreadFreshnessLedger(
    private val messageFactLimit: Int = SharedThreadFreshnessMessageFactLimit
) {
    private val lock = SynchronizedObject()
    // Payload/prewarm generations are owner-scoped. Activity facts do not carry the auth/selection
    // owner token, so a canonical content mutation conservatively advances every already-captured
    // owner generation for that server/thread without making their cached payloads interchangeable.
    private val generations = mutableMapOf<SharedThreadOwnedPayloadCacheKey, SharedThreadFreshnessGeneration>()
    // Owner-less content tokens for prewarm markers; advanced together with every owner generation.
    private val contentGenerations = mutableMapOf<SharedThreadPayloadCacheKey, SharedThreadContentGeneration>()
    private val messageFacts = LinkedHashMap<SharedThreadMessageMutationKey, Message>()
    private val summaryFacts = mutableMapOf<SharedThreadPayloadCacheKey, SharedThreadSummaryFact>()
    // Summary-authoritative reply totals remain useful when a timeline mutation supersedes the full payload generation.
    private val replyCountFacts = LinkedHashMap<SharedThreadReplyCountKey, SharedThreadReplyCountFact>()

    fun capture(
        serverId: String,
        threadId: String,
        ownerScopeId: String
    ): SharedThreadFreshnessGeneration =
        synchronized(lock) {
            val key = sharedThreadOwnedPayloadCacheKey(ownerScopeId, serverId, threadId)
            generations.getOrPut(key) {
                SharedThreadFreshnessGeneration(key.payloadKey, key.ownerScopeId)
            }
        }

    fun captureContent(
        serverId: String,
        threadId: String
    ): SharedThreadContentGeneration =
        synchronized(lock) {
            val key = SharedThreadPayloadCacheKey(serverId, threadId)
            contentGenerations.getOrPut(key) { SharedThreadContentGeneration(key) }
        }

    fun advance(
        serverId: String,
        threadId: String,
        ownerScopeId: String,
        cause: String = "advanced",
    ): SharedThreadFreshnessGeneration =
        synchronized(lock) {
            val key = sharedThreadOwnedPayloadCacheKey(ownerScopeId, serverId, threadId)
            SharedThreadFreshnessGeneration(key.payloadKey, key.ownerScopeId, cause).also { generations[key] = it }
        }

    /** Canonical content changed for every owner of this server/thread (no owner is selected). */
    fun advanceAllOwners(
        serverId: String,
        threadId: String
    ): SharedThreadContentGeneration =
        synchronized(lock) {
            val key = SharedThreadPayloadCacheKey(serverId, threadId)
            advanceAllOwnersLocked(key, cause = "content_changed")
            requireNotNull(contentGenerations[key])
        }

    fun advanceAndRun(
        serverId: String,
        threadId: String,
        ownerScopeId: String,
        cause: String = "cache_removed",
        block: (previous: SharedThreadFreshnessGeneration?, current: SharedThreadFreshnessGeneration) -> Unit
    ): SharedThreadFreshnessGeneration =
        synchronized(lock) {
            val key = sharedThreadOwnedPayloadCacheKey(ownerScopeId, serverId, threadId)
            val previous = generations[key]
            val current = SharedThreadFreshnessGeneration(key.payloadKey, key.ownerScopeId, cause)
            generations[key] = current
            block(previous, current)
            current
        }

    fun acceptMessageMutation(serverId: String, threadId: String, message: Message): Boolean =
        synchronized(lock) {
            val key = SharedThreadPayloadCacheKey(serverId, threadId)
            val mutationKey = SharedThreadMessageMutationKey(key, message.id)
            if (messageFacts[mutationKey] == message) return@synchronized false
            messageFacts.remove(mutationKey)
            messageFacts[mutationKey] = message
            while (messageFacts.size > messageFactLimit.coerceAtLeast(1)) {
                messageFacts.remove(messageFacts.keys.firstOrNull() ?: break)
            }
            advanceAllOwnersLocked(key, cause = "message_mutation", realtimeMessageId = message.id.value)
            true
        }

    /**
     * Hands 3ebf97ba: advances any captured owner / prewarm generation when a live realtime
     * `message:new` arrives for a channel, DM, joint channel, or reply thread — even when
     * Activity does not currently list or in-place patch that row — so a ready prewarm captured
     * before the message arrived can never be consumed as the final answer on open.
     */
    fun acceptRealtimeMessage(serverId: String, threadId: String, message: Message): Boolean =
        synchronized(lock) {
            val key = SharedThreadPayloadCacheKey(serverId, threadId)
            val mutationKey = SharedThreadMessageMutationKey(key, message.id)
            if (messageFacts[mutationKey] == message) return@synchronized false
            messageFacts.remove(mutationKey)
            messageFacts[mutationKey] = message
            while (messageFacts.size > messageFactLimit.coerceAtLeast(1)) {
                messageFacts.remove(messageFacts.keys.firstOrNull() ?: break)
            }
            if (generations.keys.none { it.payloadKey == key } && key !in contentGenerations) {
                return@synchronized false
            }
            advanceAllOwnersLocked(key, cause = "message_new", realtimeMessageId = message.id.value)
            true
        }

    fun acceptSummary(
        serverId: String,
        threadId: String,
        fact: SharedThreadSummaryFact,
        /** The live message (a reply or channel message) this fact comes from; null for a list re-read. */
        realtimeMessageId: String? = null,
    ): Boolean =
        synchronized(lock) {
            val key = SharedThreadPayloadCacheKey(serverId, threadId)
            val previous = summaryFacts[key]
            if (previous == null) {
                summaryFacts[key] = fact
                if (
                    !realtimeMessageId.isNullOrBlank() &&
                    (generations.keys.any { it.payloadKey == key } || key in contentGenerations)
                ) {
                    advanceAllOwnersLocked(key, cause = "activity_summary", realtimeMessageId = realtimeMessageId)
                    return@synchronized true
                }
                return@synchronized false
            }
            if (previous == fact) return@synchronized false
            if (fact.replyCount < previous.replyCount) return@synchronized false
            val factSeq = fact.latestActivitySeq
            val prevSeq = previous.latestActivitySeq
            val seqCompare =
                if (
                    factSeq != null &&
                    prevSeq != null &&
                    isCanonicalActivitySeq(factSeq) &&
                    isCanonicalActivitySeq(prevSeq)
                ) {
                    compareActivitySeq(factSeq, prevSeq)
                } else {
                    null
                }
            if (seqCompare != null && seqCompare < 0) return@synchronized false
            if (seqCompare == null || seqCompare == 0) {
                if (
                    fact.replyCount == previous.replyCount &&
                    fact.latestActivityAt != null &&
                    previous.latestActivityAt != null &&
                    fact.latestActivityAt < previous.latestActivityAt
                ) {
                    return@synchronized false
                }
                if (
                    fact.replyCount == previous.replyCount &&
                    fact.latestActivityAt == null &&
                    previous.latestActivityAt == null &&
                    fact.lastMessageAt != null &&
                    previous.lastMessageAt != null &&
                    fact.lastMessageAt < previous.lastMessageAt
                ) {
                    return@synchronized false
                }
            }
            summaryFacts[key] = fact
            advanceAllOwnersLocked(key, cause = "activity_summary", realtimeMessageId = realtimeMessageId)
            true
        }

    fun recordReplyCountFacts(
        serverId: String,
        threadId: String,
        environmentId: String,
        principalId: String,
        facts: List<Pair<String, SharedThreadReplyCountFact>>
    ) {
        if (environmentId.isBlank() || principalId.isBlank()) return
        synchronized(lock) {
            val key = SharedThreadPayloadCacheKey(serverId, threadId)
            facts.forEach { (parentMessageId, fact) ->
                val factKey = SharedThreadReplyCountKey(key, environmentId, principalId, parentMessageId)
                val existing = replyCountFacts[factKey]
                if (existing != null && existing.observedAt > fact.observedAt) {
                    // Older observation losing to a newer fact: reject without
                    // refreshing the key's recency position.
                    return@forEach
                }
                replyCountFacts.remove(factKey)
                replyCountFacts[factKey] = fact
            }
            while (replyCountFacts.size > SharedThreadReplyCountFactLimit) {
                replyCountFacts.remove(replyCountFacts.keys.firstOrNull() ?: break)
            }
        }
    }

    /**
     * Runs [block] under the ledger lock with the accepted reply-count fact lookup of [key] for this
     * environment and principal (by message id). The payload side is `applyReplyCountFacts` in thread/.
     */
    @RaftCoreInternalApi fun <R> withReplyCountFacts(
        key: SharedThreadPayloadCacheKey,
        environmentId: String,
        principalId: String,
        block: (acceptedFact: (messageId: String) -> SharedThreadReplyCountFact?) -> R
    ): R =
        synchronized(lock) {
            block { messageId -> replyCountFacts[SharedThreadReplyCountKey(key, environmentId, principalId, messageId)] }
        }

    fun invalidateServer(serverId: String, cause: String = "server_invalidated") {
        synchronized(lock) {
            generations.keys
                .filter { key -> key.payloadKey.serverId == serverId }
                .forEach { key ->
                    generations[key] = SharedThreadFreshnessGeneration(key.payloadKey, key.ownerScopeId, cause)
                }
            contentGenerations.keys
                .filter { key -> key.serverId == serverId }
                .forEach { key -> contentGenerations[key] = SharedThreadContentGeneration(key) }
            messageFacts.keys
                .filter { mutation -> mutation.key.serverId == serverId }
                .forEach(messageFacts::remove)
            summaryFacts.keys
                .filter { key -> key.serverId == serverId }
                .forEach(summaryFacts::remove)
            replyCountFacts.keys
                .filter { fact -> fact.key.serverId == serverId }
                .forEach(replyCountFacts::remove)
        }
    }

    fun retireOwner(
        ownerScopeId: String
    ): Map<SharedThreadOwnedPayloadCacheKey, Pair<SharedThreadFreshnessGeneration, SharedThreadFreshnessGeneration>> {
        requireSharedThreadOwnerScopeId(ownerScopeId)
        return synchronized(lock) {
            generations.keys
                .filter { key -> key.ownerScopeId == ownerScopeId }
                .associateWith { key ->
                    val previous = requireNotNull(generations[key])
                    val current = SharedThreadFreshnessGeneration(key.payloadKey, key.ownerScopeId, "owner_retired")
                    generations[key] = current
                    previous to current
                }
        }
    }

    fun retireOwnersMatching(
        serverId: String? = null,
        ownerPredicate: (String) -> Boolean
    ): Map<SharedThreadOwnedPayloadCacheKey, Pair<SharedThreadFreshnessGeneration, SharedThreadFreshnessGeneration>> =
        synchronized(lock) {
            generations.keys
                .filter { key ->
                    (serverId == null || key.payloadKey.serverId == serverId) && ownerPredicate(key.ownerScopeId)
                }
                .associateWith { key ->
                    val previous = requireNotNull(generations[key])
                    val current = SharedThreadFreshnessGeneration(key.payloadKey, key.ownerScopeId, "owner_retired")
                    generations[key] = current
                    previous to current
                }
        }

    fun isCurrent(generation: SharedThreadFreshnessGeneration): Boolean =
        synchronized(lock) {
            generations[
                sharedThreadOwnedPayloadCacheKey(
                    generation.ownerScopeId,
                    generation.key.serverId,
                    generation.key.threadId
                )
            ] === generation
        }

    /**
     * artin #110: when [captured] is no longer current and only live message events (edits /
     * deletes / reactions / replies) moved it since, the ids of those messages; null when anything
     * else moved it (or it is still current). A request that captured it may keep its answer and
     * fold those events onto it, if it holds every one of them, instead of fetching again.
     */
    fun realtimeMessageIdsSince(captured: SharedThreadFreshnessGeneration): Set<String>? =
        synchronized(lock) {
            val current =
                generations[
                    sharedThreadOwnedPayloadCacheKey(
                        captured.ownerScopeId,
                        captured.key.serverId,
                        captured.key.threadId
                    )
                ] ?: return@synchronized null
            if (current === captured || current.nonRealtimeBase !== captured.nonRealtimeBase) {
                return@synchronized null
            }
            // Within one base the ids only grow at the end, so what moved it since is the tail.
            current.realtimeMessageIds.drop(captured.realtimeMessageIds.size).toSet()
        }

    fun runIfCurrent(
        generation: SharedThreadFreshnessGeneration,
        block: () -> Unit
    ): Boolean =
        synchronized(lock) {
            val key =
                sharedThreadOwnedPayloadCacheKey(
                    generation.ownerScopeId,
                    generation.key.serverId,
                    generation.key.threadId
                )
            if (generations[key] !== generation) return@synchronized false
            block()
            true
        }

    fun advanceIfCurrent(
        generation: SharedThreadFreshnessGeneration,
        block: (current: SharedThreadFreshnessGeneration) -> Unit
    ): Boolean =
        synchronized(lock) {
            val key =
                sharedThreadOwnedPayloadCacheKey(
                    generation.ownerScopeId,
                    generation.key.serverId,
                    generation.key.threadId
                )
            if (generations[key] !== generation) return@synchronized false
            val current = SharedThreadFreshnessGeneration(generation.key, key.ownerScopeId, "cache_removed")
            generations[key] = current
            block(current)
            true
        }

    private fun advanceAllOwnersLocked(
        payloadKey: SharedThreadPayloadCacheKey,
        cause: String,
        realtimeMessageId: String? = null,
    ) {
        generations.keys
            .filter { key -> key.payloadKey == payloadKey }
            .forEach { key ->
                val previous = generations[key]
                val realtimeIds =
                    realtimeMessageId
                        ?.takeIf { it.isNotBlank() }
                        ?.let { id ->
                            val existingIds = previous?.realtimeMessageIds.orEmpty()
                            if (existingIds.lastOrNull() == id) existingIds else existingIds + id
                        }
                        ?.takeIf { ids -> ids.size <= SharedThreadRealtimeFoldMessageIdLimit }
                generations[key] =
                    if (previous != null && realtimeIds != null) {
                        SharedThreadFreshnessGeneration(
                            payloadKey,
                            key.ownerScopeId,
                            cause,
                            realtimeBase = previous.nonRealtimeBase,
                            realtimeMessageIds = realtimeIds,
                        )
                    } else {
                        SharedThreadFreshnessGeneration(payloadKey, key.ownerScopeId, cause)
                    }
            }
        contentGenerations[payloadKey] = SharedThreadContentGeneration(payloadKey)
    }

    @RaftCoreInternalApi fun clearForTest() {
        synchronized(lock) {
            generations.clear()
            contentGenerations.clear()
            messageFacts.clear()
            summaryFacts.clear()
            replyCountFacts.clear()
        }
    }
}
