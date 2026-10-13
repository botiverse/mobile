package build.raft.app

import build.raft.app.model.PerReceiverReadStateChanged
import build.raft.app.data.DataResult
import build.raft.app.data.EmptyReason
import build.raft.app.data.HomeConversationRow
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.InboxItem
import build.raft.app.data.toConversationSummary
import build.raft.app.data.ThreadActionResult
import build.raft.app.data.PlatformFile
import build.raft.app.data.AttachmentUploadRequest
import build.raft.app.data.AttachmentUploadResult
import build.raft.app.data.ActivityReplyThreadPrewarmMarker
import build.raft.app.data.CreateAgentBody
import build.raft.app.data.CreateChannelRequest
import build.raft.app.data.appendSharedThreadSentMessageFromPage
import build.raft.app.data.buildSharedThreadPayloadFromMessagesPage
import build.raft.app.data.buildCreateAgentBody
import build.raft.app.data.buildExecuteActionBody
import build.raft.app.data.buildMarkAgentActionExecutedBody
import build.raft.app.data.buildMarkChannelActionExecutedBody
import build.raft.app.data.buildMarkChannelMembersActionExecutedBody
import build.raft.app.data.ChannelFilesSnapshot
import build.raft.app.data.ChannelMemberActionResult
import build.raft.app.data.ConversationRepository
import build.raft.app.data.RealConversationRepository
import build.raft.app.data.ProgressiveConversationAttachmentUploader
import build.raft.app.data.DirectAttachmentUploadOperation
import build.raft.app.data.SlockExpectedAuthBinding
import build.raft.app.data.matchesExpectedAuthority
import build.raft.app.data.normalizedEnvironmentId
import build.raft.app.data.AddChannelMemberRequest
import build.raft.app.data.AddChannelMembersBatchRequest
import build.raft.app.data.ChannelRepository
import build.raft.app.data.ChannelProfileUpdateRequest
import build.raft.app.data.ChannelProfileUpdateResult
import build.raft.app.data.ReadReceiptResult
import build.raft.app.data.ReadReceiptCoordinator
import build.raft.app.data.ReadReceiptOwner
import build.raft.app.data.RemoveChannelMemberRequest
import build.raft.app.data.ScreenError
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.MemberRepository
import build.raft.app.data.MessageMutationSummary
import build.raft.app.data.MessageReactionSummary
import build.raft.app.data.MviStaleToken
import build.raft.app.data.PendingMentionActionExecuteResult
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.SlockAgentsApiClient
import build.raft.app.data.SlockAuthSessionStore
import build.raft.app.data.SlockIntegrationsActionsPushApiClient
import build.raft.app.data.SavedStatusRepository
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.SharedCreateAgentFormStore
import build.raft.app.data.ServerFeatureFlagSnapshot
import build.raft.app.data.MOBILE_THREAD_PAGE_SIZE_FEATURE_FLAG_KEY
import build.raft.app.data.SelectedServerStore
import build.raft.app.data.SlockThreadApi
import build.raft.app.data.SlockThreadApiClient
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.SharedThreadNetworkApi
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.isGenericInlineCommitAction
import build.raft.app.data.ThreadAttachmentCommentsPage
import build.raft.app.data.ThreadCandidateProofGate
import build.raft.app.data.ThreadIdentityProof
import build.raft.app.data.ThreadRouteId
import build.raft.app.data.ThreadReplySummariesSnapshot
import build.raft.app.data.ThreadSnapshot
import build.raft.app.data.RawMessagePage
import build.raft.app.data.RawMessagePageWitness
import build.raft.app.data.ThreadApiResult
import build.raft.app.data.ThreadPendingSendDrainer
import build.raft.app.data.ThreadPendingSendJournal
import build.raft.app.data.ThreadPendingSendJournalDrainStore
import build.raft.app.data.toThreadAttachmentCommentsFailureProjection
import build.raft.app.data.ThreadAttachmentOpenTarget
import build.raft.app.data.ThreadMessagesPage
import build.raft.app.data.ThreadReplyCacheIdentitySnapshot
import build.raft.app.data.ThreadReplyCacheIdentityTransitionAnchor
import build.raft.app.data.authSessionRequiredResult
import build.raft.app.data.map
import build.raft.app.data.toSharedConversationDirectoryProjection
import build.raft.app.data.toSharedConversationDirectoryState
import build.raft.app.data.toMessageMetadata
import build.raft.app.data.toThreadRouteId
import build.raft.app.data.withExecutedAgent
import build.raft.app.data.withExecutedChannel
import build.raft.app.data.withExecutedChannelMembers
import build.raft.app.data.realtime.RealtimeVisibleStreamGapPolicy
import build.raft.app.data.realtime.RealtimeVisibleStreamGapRequest
import build.raft.app.model.Attachment
import build.raft.app.model.AttachmentId
import build.raft.app.model.AvatarReference
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationPermissions
import build.raft.app.model.ConversationSummary
import build.raft.app.model.MemberId
import build.raft.app.markdown.formatRaftDirectMessagePeerRef
import build.raft.app.markdown.parseRaftDirectMessagePeerRef
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageCommentHostSource
import build.raft.app.model.MessageCommentRef
import build.raft.app.model.MessageDeliveryState
import build.raft.app.model.MessageId
import build.raft.app.model.MessageReaction
import build.raft.app.model.MessageType
import build.raft.app.model.MessageNew
import build.raft.app.model.MessageUpdated
import build.raft.app.model.Presence
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.ServerId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskSummary
import build.raft.app.model.ThreadId
import build.raft.app.model.ThreadUpdated
import build.raft.app.model.SyncResumeResponse
import build.raft.app.model.avatarWireValueForAvatarSlot
import build.raft.app.network.sync.DurablePartitionKey
import build.raft.app.network.sync.InlineThreadReplyPreviewLimit
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.ThreadDurableLastKnownReader
import build.raft.app.network.sync.ThreadEnteredScopeSnapshotLoader
import build.raft.app.network.sync.ThreadDurablePageDirection
import build.raft.app.network.sync.ThreadDurablePageExtender
import build.raft.app.network.sync.ThreadRepliesAcceptedProjection
import build.raft.app.network.sync.ThreadRepliesAuthoritativeSnapshot
import build.raft.app.network.sync.ThreadRepliesPageIdentity
import build.raft.app.network.sync.ThreadRepliesScopeIdentity
import build.raft.app.network.sync.ThreadRepliesSyncHost
import build.raft.app.network.sync.initialThreadRepliesReadModel
import build.raft.app.network.sync.toThreadMessageItem
import build.raft.app.model.messageStreamChannelId
import build.raft.app.model.providerLabel
import build.raft.app.model.resolveAvatarSlot
import build.raft.app.model.safeDisplayNameOrNull
import build.raft.app.model.threadIdentity
import com.tencent.kuikly.lifecycle.ViewModel
import com.tencent.kuikly.lifecycle.viewModelScope
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private data class ReplyThreadResolveKey(
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

internal val ReplyCacheProvenance.threadPayloadOwnerScopeId: String?
    get() = replyCacheOwnerScopeId

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

private data class ReplyResolutionScope(
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

private fun sharedThreadOwnedPayloadCacheKey(
    ownerScopeId: String,
    serverId: String,
    threadId: String
): SharedThreadOwnedPayloadCacheKey =
    SharedThreadOwnedPayloadCacheKey(
        ownerScopeId = ownerScopeId,
        payloadKey = SharedThreadPayloadCacheKey(serverId, threadId)
    )

private const val SharedThreadPrewarmMaxConcurrency = 2
private const val SharedThreadPrewarmStartIntervalMillis = 100L
private const val SharedThreadZeroReplyOriginHydrationGraceMillis = 300L
private const val SharedThreadFreshnessMessageFactLimit = 512
private const val SharedThreadReplyCountFactLimit = 2_048
private const val SharedThreadPrewarmGenerationSupersededCode = "thread_prewarm_generation_superseded"
private const val ReplyCacheProvenanceStaleCode = "thread_reply_cache_provenance_stale"

private fun <T> staleReplyCacheProvenanceResult(): DataResult<T> =
    DataResult.Error(ScreenError(code = ReplyCacheProvenanceStaleCode, retryable = false))

class SharedThreadFreshnessGeneration internal constructor(
    internal val key: SharedThreadPayloadCacheKey,
    internal val ownerScopeId: String
) {
    init {
        requireSharedThreadOwnerScopeId(ownerScopeId)
    }
}

/**
 * Owner-less content-mutation token for one server/thread. Activity prewarm markers compare
 * these by identity to learn "canonical content changed since I looked"; they never select a
 * cached payload, so they carry no owner and must not be confused with
 * [SharedThreadFreshnessGeneration].
 */
class SharedThreadContentGeneration internal constructor(
    internal val key: SharedThreadPayloadCacheKey
)

internal data class SharedThreadSummaryFact(
    val replyCount: Int,
    val latestActivityMessageId: String?,
    val latestActivityPreview: String?,
    val latestActivityAt: String?,
    val lastMessageId: String?,
    val lastMessagePreview: String?,
    val lastMessageAt: String?
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

internal data class SharedThreadReplyCountFact(
    val threadChannelId: String?,
    val replyCount: Int,
    // Monotonic mark captured by the caller when the value was observed. The
    // ledger arbitrates same-message facts by recency so an older projection
    // cannot overwrite a newer realtime observation (e.g. reply deletions).
    val observedAt: TimeSource.Monotonic.ValueTimeMark
)

internal class SharedThreadFreshnessLedger(
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
        ownerScopeId: String
    ): SharedThreadFreshnessGeneration =
        synchronized(lock) {
            val key = sharedThreadOwnedPayloadCacheKey(ownerScopeId, serverId, threadId)
            SharedThreadFreshnessGeneration(key.payloadKey, key.ownerScopeId).also { generations[key] = it }
        }

    /** Canonical content changed for every owner of this server/thread (no owner is selected). */
    fun advanceAllOwners(
        serverId: String,
        threadId: String
    ): SharedThreadContentGeneration =
        synchronized(lock) {
            val key = SharedThreadPayloadCacheKey(serverId, threadId)
            advanceAllOwnersLocked(key)
            requireNotNull(contentGenerations[key])
        }

    fun advanceAndRun(
        serverId: String,
        threadId: String,
        ownerScopeId: String,
        block: (previous: SharedThreadFreshnessGeneration?, current: SharedThreadFreshnessGeneration) -> Unit
    ): SharedThreadFreshnessGeneration =
        synchronized(lock) {
            val key = sharedThreadOwnedPayloadCacheKey(ownerScopeId, serverId, threadId)
            val previous = generations[key]
            val current = SharedThreadFreshnessGeneration(key.payloadKey, key.ownerScopeId)
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
            advanceAllOwnersLocked(key)
            true
        }

    fun acceptSummary(
        serverId: String,
        threadId: String,
        fact: SharedThreadSummaryFact
    ): Boolean =
        synchronized(lock) {
            val key = SharedThreadPayloadCacheKey(serverId, threadId)
            val previous = summaryFacts[key]
            if (previous == null) {
                summaryFacts[key] = fact
                return@synchronized false
            }
            if (previous == fact) return@synchronized false
            if (fact.replyCount < previous.replyCount) return@synchronized false
            if (
                fact.replyCount == previous.replyCount &&
                fact.latestActivityAt != null &&
                previous.latestActivityAt != null &&
                fact.latestActivityAt < previous.latestActivityAt
            ) {
                return@synchronized false
            }
            summaryFacts[key] = fact
            advanceAllOwnersLocked(key)
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

    fun applyReplyCountFacts(
        serverId: String,
        threadId: String,
        environmentId: String,
        principalId: String,
        payload: KuiklyThreadScreenPayload
    ): KuiklyThreadScreenPayload {
        if (environmentId.isBlank() || principalId.isBlank()) return payload
        return synchronized(lock) {
            applyReplyCountFactsLocked(
                key = SharedThreadPayloadCacheKey(serverId, threadId),
                environmentId = environmentId,
                principalId = principalId,
                payload = payload
            )
        }
    }

    fun invalidateServer(serverId: String) {
        synchronized(lock) {
            generations.keys
                .filter { key -> key.payloadKey.serverId == serverId }
                .forEach { key ->
                    generations[key] = SharedThreadFreshnessGeneration(key.payloadKey, key.ownerScopeId)
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
                    val current = SharedThreadFreshnessGeneration(key.payloadKey, key.ownerScopeId)
                    generations[key] = current
                    previous to current
                }
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
            val current = SharedThreadFreshnessGeneration(generation.key, key.ownerScopeId)
            generations[key] = current
            block(current)
            true
        }

    private fun advanceAllOwnersLocked(payloadKey: SharedThreadPayloadCacheKey) {
        generations.keys
            .filter { key -> key.payloadKey == payloadKey }
            .forEach { key ->
                generations[key] = SharedThreadFreshnessGeneration(payloadKey, key.ownerScopeId)
            }
        contentGenerations[payloadKey] = SharedThreadContentGeneration(payloadKey)
    }

    internal fun clearForTest() {
        synchronized(lock) {
            generations.clear()
            contentGenerations.clear()
            messageFacts.clear()
            summaryFacts.clear()
            replyCountFacts.clear()
        }
    }

    private fun applyReplyCountFactsLocked(
        key: SharedThreadPayloadCacheKey,
        environmentId: String,
        principalId: String,
        payload: KuiklyThreadScreenPayload
    ): KuiklyThreadScreenPayload {
        fun merge(message: KuiklyThreadMessageItem): KuiklyThreadMessageItem {
            val factKey = SharedThreadReplyCountKey(key, environmentId, principalId, message.id)
            val accepted = replyCountFacts[factKey] ?: return message
            val nextThreadChannelId = accepted.threadChannelId ?: message.threadChannelId
            val nextReplyCount = accepted.replyCount
            return if (
                nextThreadChannelId == message.threadChannelId &&
                nextReplyCount == message.replyCount
            ) {
                message
            } else {
                message.copy(
                    threadChannelId = nextThreadChannelId,
                    replyCount = nextReplyCount
                )
            }
        }

        val nextOrigin = payload.originMessage?.let(::merge)
        val nextMessages = payload.messages.map(::merge)
        return if (nextOrigin == payload.originMessage && nextMessages == payload.messages) {
            payload
        } else {
            payload.copy(originMessage = nextOrigin, messages = nextMessages)
        }
    }
}

private data class SharedThreadPayloadEntry(
    val payload: KuiklyThreadScreenPayload,
    val generation: SharedThreadFreshnessGeneration
)

private data class PendingReactionRealtimeGuard(
    val messageId: String,
    val emoji: String,
    val selected: Boolean
)

internal data class SharedThreadMessageKey(
    val scopeId: String,
    val channelId: ChannelId,
    val messageId: MessageId
)

internal sealed interface SharedThreadMessageResolution {
    data class Loaded(val message: Message) : SharedThreadMessageResolution
    data object Missing : SharedThreadMessageResolution
    data class Error(val code: String) : SharedThreadMessageResolution
}

internal sealed interface SharedThreadOriginResolution {
    data class Loaded(val message: KuiklyThreadMessageItem) : SharedThreadOriginResolution
    data object Missing : SharedThreadOriginResolution
    data class Error(val code: String) : SharedThreadOriginResolution
}

internal class SharedThreadMessageStore(
    private val limit: Int = 256,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob())
) {
    private val messageLock = SynchronizedObject()
    private val messages = LinkedHashMap<SharedThreadMessageKey, Message>()
    private val replyThreadStreams = LinkedHashMap<ReplyThreadResolveKey, String>()
    private val inFlight = mutableMapOf<SharedThreadMessageKey, Deferred<SharedThreadMessageResolution>>()
    private val inFlightMutex = Mutex()

    fun get(serverId: ServerId, channelId: ChannelId, messageId: MessageId): Message? =
        getScoped(serverId.value, channelId, messageId)

    fun getScoped(scopeId: String, channelId: ChannelId, messageId: MessageId): Message? =
        synchronized(messageLock) {
            messages[SharedThreadMessageKey(scopeId, channelId, messageId)]
        }

    fun upsert(serverId: ServerId, channelId: ChannelId, message: Message): Message =
        upsertScoped(serverId.value, channelId, message)

    fun upsertScoped(scopeId: String, channelId: ChannelId, message: Message): Message =
        synchronized(messageLock) {
            upsertLocked(scopeId, channelId, message)
        }

    private fun upsertLocked(scopeId: String, channelId: ChannelId, message: Message): Message {
        val key = SharedThreadMessageKey(scopeId, channelId, message.id)
        val existing = messages[key]
        val selected =
            if (existing != null && !existing.needsSharedThreadMessageStoreHydration() && message.needsSharedThreadMessageStoreHydration()) {
                existing
            } else {
                message
            }
        messages.remove(key)
        messages[key] = selected
        trimToLimit()
        return selected
    }

    fun getReplyThreadStream(
        namespaceId: String,
        parentChannelId: String,
        parentMessageId: String
    ): String? =
        synchronized(messageLock) {
            replyThreadStreams[ReplyThreadResolveKey(namespaceId, parentChannelId, parentMessageId)]
        }

    fun upsertReplyThreadStream(
        namespaceId: String,
        parentChannelId: String,
        parentMessageId: String,
        threadChannelId: String
    ) {
        if (
            namespaceId.isBlank() ||
            parentChannelId.isBlank() ||
            parentMessageId.isBlank() ||
            threadChannelId.isBlank()
        ) {
            return
        }
        synchronized(messageLock) {
            val key = ReplyThreadResolveKey(namespaceId, parentChannelId, parentMessageId)
            replyThreadStreams.remove(key)
            replyThreadStreams[key] = threadChannelId
            while (replyThreadStreams.size > limit) {
                val firstKey = replyThreadStreams.keys.firstOrNull() ?: return@synchronized
                replyThreadStreams.remove(firstKey)
            }
        }
    }

    suspend fun resolve(
        serverId: ServerId,
        channelId: ChannelId,
        messageId: MessageId,
        fetch: suspend () -> SharedThreadMessageResolution
    ): SharedThreadMessageResolution =
        resolveScoped(serverId.value, channelId, messageId, fetch)

    suspend fun resolveScoped(
        scopeId: String,
        channelId: ChannelId,
        messageId: MessageId,
        fetch: suspend () -> SharedThreadMessageResolution
    ): SharedThreadMessageResolution {
        getScoped(scopeId, channelId, messageId)
            ?.takeIf { message -> message.matchesSharedThreadMessageStoreKey(channelId, messageId) }
            ?.let { message -> return SharedThreadMessageResolution.Loaded(message) }
        val key = SharedThreadMessageKey(scopeId, channelId, messageId)
        val deferred =
            inFlightMutex.withLock {
                getScoped(scopeId, channelId, messageId)
                    ?.takeIf { message -> message.matchesSharedThreadMessageStoreKey(channelId, messageId) }
                    ?.let { message -> return SharedThreadMessageResolution.Loaded(message) }
                inFlight[key] ?: scope.async {
                    val fetched = fetch()
                    synchronized(messageLock) {
                        val current =
                            messages[SharedThreadMessageKey(scopeId, channelId, messageId)]
                                ?.takeIf { message -> message.matchesSharedThreadMessageStoreKey(channelId, messageId) }
                        if (current != null) {
                            SharedThreadMessageResolution.Loaded(current)
                        } else {
                            when (fetched) {
                                is SharedThreadMessageResolution.Loaded -> {
                                    val message =
                                        fetched.message.takeIf { candidate ->
                                            candidate.matchesSharedThreadMessageStoreKey(channelId, messageId)
                                        }
                                    if (message == null) {
                                        SharedThreadMessageResolution.Error("thread_parent_context_identity_mismatch")
                                    } else {
                                        SharedThreadMessageResolution.Loaded(upsertLocked(scopeId, channelId, message))
                                    }
                                }
                                SharedThreadMessageResolution.Missing -> fetched
                                is SharedThreadMessageResolution.Error -> fetched
                            }
                        }
                    }
                }.also { producer ->
                    inFlight[key] = producer
                    producer.invokeOnCompletion {
                        scope.launch {
                            inFlightMutex.withLock {
                                if (inFlight[key] === producer) {
                                    inFlight.remove(key)
                                }
                            }
                        }
                    }
                }
            }
        return deferred.await()
    }

    internal fun clearForTest() {
        synchronized(messageLock) {
            messages.clear()
            replyThreadStreams.clear()
        }
        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
    }

    private fun trimToLimit() {
        while (messages.size > limit) {
            val firstKey = messages.keys.firstOrNull() ?: return
            messages.remove(firstKey)
        }
    }
}

/**
 * App-scope first-frame cache shared by Activity prewarm and visible Thread stores.
 *
 * Route payloads remain identity/intent-only. Background prewarm writes the same
 * canonical payload candidate that [SharedThreadStore] reads on start, so opening
 * a warmed reply thread does not need a second route-owned content model.
 */
internal class SharedThreadPayloadStore(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
    maxConcurrentPrewarms: Int = SharedThreadPrewarmMaxConcurrency,
    private val prewarmStartIntervalMillis: Long = SharedThreadPrewarmStartIntervalMillis,
    internal val freshnessLedger: SharedThreadFreshnessLedger = SharedThreadFreshnessLedger(),
    private val replyThreadAccessFlights: SharedReplyThreadAccessFlightStore<ThreadSnapshot> =
        SharedReplyThreadAccessFlightStore(scope)
) {
    private val payloads = MutableStateFlow<Map<SharedThreadOwnedPayloadCacheKey, SharedThreadPayloadEntry>>(emptyMap())
    private val prewarmMutex = Mutex()
    private val prewarmPool = Semaphore(maxConcurrentPrewarms.coerceAtLeast(1))
    private val prewarmStartMutex = Mutex()
    private val prewarmInFlight = mutableMapOf<SharedThreadOwnedPayloadCacheKey, Deferred<DataResult<KuiklyThreadScreenPayload>>>()
    private val prewarmInFlightGenerations =
        mutableMapOf<SharedThreadOwnedPayloadCacheKey, SharedThreadFreshnessGeneration>()
    private val prewarmFetchStartedGenerations =
        mutableMapOf<SharedThreadOwnedPayloadCacheKey, SharedThreadFreshnessGeneration>()
    private val prewarmReadyGenerations =
        mutableMapOf<SharedThreadOwnedPayloadCacheKey, SharedThreadFreshnessGeneration>()
    private val prewarmFailureGenerations =
        mutableMapOf<SharedThreadOwnedPayloadCacheKey, SharedThreadFreshnessGeneration>()
    private val prewarmFailures = mutableMapOf<SharedThreadOwnedPayloadCacheKey, DataResult<KuiklyThreadScreenPayload>>()
    private val failedPrewarmTakeovers =
        mutableMapOf<SharedThreadOwnedPayloadCacheKey, Deferred<DataResult<KuiklyThreadScreenPayload>>>()
    private val failedPrewarmTakeoverGenerations =
        mutableMapOf<SharedThreadOwnedPayloadCacheKey, SharedThreadFreshnessGeneration>()
    private val failedPrewarmTakeoverResults =
        mutableMapOf<SharedThreadOwnedPayloadCacheKey, DataResult<KuiklyThreadScreenPayload>>()
    private val failedPrewarmTakeoverResultGenerations =
        mutableMapOf<SharedThreadOwnedPayloadCacheKey, SharedThreadFreshnessGeneration>()
    private var lastPrewarmStartedAt: TimeMark? = null

    suspend fun joinOrStartReplyThreadAccessFlight(
        key: SharedReplyThreadAccessFlightKey,
        replaceFailure: Boolean = false,
        fetch: suspend () -> DataResult<ThreadSnapshot>
    ): DataResult<ThreadSnapshot> =
        replyThreadAccessFlights.joinOrStart(key, replaceFailure, fetch)

    suspend fun replyThreadAccessFailure(key: SharedReplyThreadAccessFlightKey): ScreenError? =
        replyThreadAccessFlights.failure(key)

    suspend fun clearReplyThreadAccessFailure(key: SharedReplyThreadAccessFlightKey) {
        replyThreadAccessFlights.clearFailure(key)
    }

    fun retireOwner(ownerScopeId: String) {
        val retired = freshnessLedger.retireOwner(ownerScopeId)
        if (retired.isEmpty()) return
        // Preserve already-committed local-first candidates while changing their generation token;
        // producers holding the retired token can no longer commit after an owner transition.
        payloads.update { current ->
            current.mapValues { (key, entry) ->
                val transition = retired[key]
                if (transition?.first === entry.generation) {
                    SharedThreadPayloadEntry(entry.payload, transition.second)
                } else {
                    entry
                }
            }
        }
    }

    fun get(
        serverId: String,
        threadId: String,
        environmentId: String,
        principalId: String,
        ownerScopeId: String
    ): KuiklyThreadScreenPayload? {
        val generation = freshnessLedger.capture(serverId, threadId, ownerScopeId)
        return payloads.value[sharedThreadOwnedPayloadCacheKey(ownerScopeId, serverId, threadId)]
            ?.takeIf { entry -> entry.generation === generation }
            ?.payload
            ?.let { payload ->
                freshnessLedger.applyReplyCountFacts(serverId, threadId, environmentId, principalId, payload)
            }
    }

    fun put(
        serverId: String,
        threadId: String,
        payload: KuiklyThreadScreenPayload,
        ownerScopeId: String
    ) {
        val key = sharedThreadOwnedPayloadCacheKey(ownerScopeId, serverId, threadId)
        val generation = freshnessLedger.capture(serverId, threadId, ownerScopeId)
        payloads.update { current -> current + (key to SharedThreadPayloadEntry(payload, generation)) }
    }

    fun putIfCurrent(
        generation: SharedThreadFreshnessGeneration,
        payload: KuiklyThreadScreenPayload,
        ownerScopeId: String
    ): Boolean {
        if (generation.ownerScopeId != ownerScopeId) return false
        return freshnessLedger.runIfCurrent(generation) {
            val key =
                sharedThreadOwnedPayloadCacheKey(
                    ownerScopeId,
                    generation.key.serverId,
                    generation.key.threadId
                )
            payloads.update { current ->
                current + (key to SharedThreadPayloadEntry(payload, generation))
            }
        }
    }

    fun recordReplyCountFacts(
        serverId: String,
        threadId: String,
        environmentId: String,
        principalId: String,
        facts: List<Pair<String, SharedThreadReplyCountFact>>
    ) {
        freshnessLedger.recordReplyCountFacts(serverId, threadId, environmentId, principalId, facts)
    }

    fun applyReplyCountFacts(
        serverId: String,
        threadId: String,
        environmentId: String,
        principalId: String,
        payload: KuiklyThreadScreenPayload
    ): KuiklyThreadScreenPayload =
        freshnessLedger.applyReplyCountFacts(serverId, threadId, environmentId, principalId, payload)

    fun captureGeneration(
        serverId: String,
        threadId: String,
        ownerScopeId: String
    ): SharedThreadFreshnessGeneration =
        freshnessLedger.capture(serverId, threadId, ownerScopeId)

    fun remove(
        serverId: String,
        threadId: String,
        ownerScopeId: String
    ) {
        val ownedKey = sharedThreadOwnedPayloadCacheKey(ownerScopeId, serverId, threadId)
        val ownedLegacyKey = sharedThreadOwnedPayloadCacheKey(ownerScopeId, "", threadId)
        freshnessLedger.advanceAndRun(serverId, threadId, ownerScopeId) { previous, _ ->
            payloads.update { current ->
                current.filterKeys { candidate ->
                    if (candidate == ownedLegacyKey) return@filterKeys false
                    if (candidate != ownedKey) return@filterKeys true
                    val entry = current[candidate]
                    previous != null && entry?.generation !== previous
                }
            }
        }
    }

    fun removeIfCurrent(generation: SharedThreadFreshnessGeneration): Boolean =
        freshnessLedger.advanceIfCurrent(generation) {
            payloads.update { current ->
                current.filterKeys { candidate ->
                    if (
                        candidate.ownerScopeId == generation.ownerScopeId &&
                            candidate.payloadKey == SharedThreadPayloadCacheKey("", generation.key.threadId)
                    ) {
                        return@filterKeys false
                    }
                    candidate.ownerScopeId != generation.ownerScopeId ||
                        candidate.payloadKey != generation.key ||
                        current[candidate]?.generation !== generation
                }
            }
        }

    fun values(
        serverId: String,
        ownerScopeId: String
    ): Collection<KuiklyThreadScreenPayload> {
        requireSharedThreadOwnerScopeId(ownerScopeId)
        return payloads.value
            .filter { (key, entry) ->
                key.ownerScopeId == ownerScopeId &&
                    key.payloadKey.serverId == serverId &&
                    freshnessLedger.isCurrent(entry.generation)
            }
            .values
            .map { entry -> entry.payload }
    }

    suspend fun prewarm(
        serverId: String,
        threadId: String,
        ownerScopeId: String,
        fetch: suspend () -> DataResult<KuiklyThreadScreenPayload>
    ): DataResult<KuiklyThreadScreenPayload> = coroutineScope {
        val key = sharedThreadOwnedPayloadCacheKey(ownerScopeId, serverId, threadId)
        val generation = freshnessLedger.capture(serverId, threadId, ownerScopeId)

        var cached: KuiklyThreadScreenPayload? = null
        var created: Deferred<DataResult<KuiklyThreadScreenPayload>>? = null
        val deferred =
            prewarmMutex.withLock {
                if (prewarmReadyGenerations[key] !== generation) {
                    prewarmReadyGenerations.remove(key)
                }
                if (prewarmFailureGenerations[key] !== generation) {
                    prewarmFailures.remove(key)
                    prewarmFailureGenerations.remove(key)
                }
                if (prewarmInFlightGenerations[key] !== generation) {
                    prewarmInFlight.remove(key)
                    prewarmInFlightGenerations.remove(key)
                    prewarmFetchStartedGenerations.remove(key)
                    failedPrewarmTakeovers.remove(key)
                    failedPrewarmTakeoverGenerations.remove(key)
                    failedPrewarmTakeoverResults.remove(key)
                    failedPrewarmTakeoverResultGenerations.remove(key)
                }
                if (prewarmReadyGenerations[key] === generation) {
                    cached = payloads.value[key]
                        ?.takeIf { entry -> entry.generation === generation }
                        ?.payload
                    if (cached != null) return@withLock null
                    prewarmReadyGenerations.remove(key)
                }
                prewarmInFlight[key]
                    ?.takeIf { prewarmInFlightGenerations[key] === generation }
                    ?: failedPrewarmTakeovers[key]
                        ?.takeIf { failedPrewarmTakeoverGenerations[key] === generation }
                    ?: scope.async(start = CoroutineStart.LAZY) {
                        try {
                            val result =
                                prewarmPool.withPermit {
                                    awaitPrewarmStartRateLimit()
                                    val currentJob = currentCoroutineContext()[Job]
                                    val stillOwned =
                                        prewarmMutex.withLock {
                                            if (
                                                prewarmInFlightGenerations[key] === generation &&
                                                prewarmInFlight[key] === currentJob
                                            ) {
                                                prewarmFetchStartedGenerations[key] = generation
                                                true
                                            } else {
                                                false
                                            }
                                        }
                                    if (!stillOwned) {
                                        throw CancellationException("queued thread prewarm was promoted")
                                    }
                                    fetch()
                                }
                            prewarmMutex.withLock {
                                val currentJob = currentCoroutineContext()[Job]
                                if (
                                    prewarmInFlightGenerations[key] === generation &&
                                    prewarmInFlight[key] === currentJob
                                ) {
                                    freshnessLedger.runIfCurrent(generation) {
                                        if (result is DataResult.Content) {
                                            payloads.update { current ->
                                                current + (key to SharedThreadPayloadEntry(result.data, generation))
                                            }
                                            prewarmReadyGenerations[key] = generation
                                            prewarmFailures.remove(key)
                                            prewarmFailureGenerations.remove(key)
                                            failedPrewarmTakeoverResults.remove(key)
                                            failedPrewarmTakeoverResultGenerations.remove(key)
                                        } else {
                                            prewarmFailures[key] = result
                                            prewarmFailureGenerations[key] = generation
                                        }
                                    }
                                }
                            }
                            result
                        } finally {
                            val currentJob = currentCoroutineContext()[Job]
                            prewarmMutex.withLock {
                                if (prewarmInFlight[key] === currentJob) {
                                    prewarmInFlight.remove(key)
                                    prewarmInFlightGenerations.remove(key)
                                    prewarmFetchStartedGenerations.remove(key)
                                }
                            }
                        }
                    }.also { candidate ->
                        prewarmFailures.remove(key)
                        failedPrewarmTakeoverResults.remove(key)
                        failedPrewarmTakeoverResultGenerations.remove(key)
                        prewarmInFlight[key] = candidate
                        prewarmInFlightGenerations[key] = generation
                        created = candidate
                    }
            }
        cached?.let { return@coroutineScope DataResult.Content(it) }
        val shared = deferred ?: error("prewarm generation lookup missing")
        created?.start()
        shared.await()
    }

    /**
     * A visible Thread must not inherit the queue delay of a background prewarm that has not
     * reached its network fetch yet. Detach and cancel only that queued generation; an already
     * started request remains single-flight and is joined by [joinOrConsumePrewarm].
     */
    suspend fun promoteQueuedPrewarm(
        serverId: String,
        threadId: String,
        ownerScopeId: String
    ): Boolean {
        val key = sharedThreadOwnedPayloadCacheKey(ownerScopeId, serverId, threadId)
        var queued: Deferred<DataResult<KuiklyThreadScreenPayload>>? = null
        prewarmMutex.withLock {
            val generation = freshnessLedger.capture(serverId, threadId, ownerScopeId)
            val candidate =
                prewarmInFlight[key]
                    ?.takeIf { prewarmInFlightGenerations[key] === generation }
                    ?: return@withLock
            if (prewarmFetchStartedGenerations[key] === generation) return@withLock
            prewarmInFlight.remove(key)
            prewarmInFlightGenerations.remove(key)
            prewarmFetchStartedGenerations.remove(key)
            queued = candidate
        }
        queued?.cancel()
        return queued != null
    }

    suspend fun joinOrConsumePrewarm(
        serverId: String,
        threadId: String,
        ownerScopeId: String
    ): DataResult<KuiklyThreadScreenPayload>? {
        val key = sharedThreadOwnedPayloadCacheKey(ownerScopeId, serverId, threadId)
        val lookup =
            prewarmMutex.withLock {
                val generation = freshnessLedger.capture(serverId, threadId, ownerScopeId)
                val cached =
                    if (prewarmReadyGenerations[key] === generation) {
                        prewarmReadyGenerations.remove(key)
                        payloads.value[key]
                            ?.takeIf { entry -> entry.generation === generation }
                            ?.payload
                    } else {
                        null
                    }
                SharedThreadPrewarmLookup(
                    generation = generation,
                    cached = cached,
                    inFlight = prewarmInFlight[key]
                        ?.takeIf { prewarmInFlightGenerations[key] === generation },
                    failure = prewarmFailures[key]
                        ?.takeIf { prewarmFailureGenerations[key] === generation }
                )
            }
        lookup.cached?.let { return DataResult.Content(it) }
        val result = lookup.inFlight?.await() ?: lookup.failure
        if (result == null || freshnessLedger.isCurrent(lookup.generation)) return result
        return DataResult.Error(
            ScreenError(code = SharedThreadPrewarmGenerationSupersededCode, retryable = true)
        )
    }

    suspend fun takeOverFailedPrewarm(
        serverId: String,
        threadId: String,
        ownerScopeId: String,
        fetch: suspend () -> DataResult<KuiklyThreadScreenPayload>
    ): DataResult<KuiklyThreadScreenPayload> = coroutineScope {
        val key = sharedThreadOwnedPayloadCacheKey(ownerScopeId, serverId, threadId)
        while (true) {
            var immediate: DataResult<KuiklyThreadScreenPayload>? = null
            var created: Deferred<DataResult<KuiklyThreadScreenPayload>>? = null
            lateinit var generation: SharedThreadFreshnessGeneration
            val deferred =
                prewarmMutex.withLock {
                    generation = freshnessLedger.capture(serverId, threadId, ownerScopeId)
                    if (prewarmReadyGenerations[key] === generation) {
                        val readyPayload = payloads.value[key]
                            ?.takeIf { entry -> entry.generation === generation }
                            ?.payload
                        if (readyPayload != null) {
                            readyPayload.let { payload ->
                                immediate = DataResult.Content(payload)
                                return@withLock null
                            }
                        } else {
                            prewarmReadyGenerations.remove(key)
                        }
                    }
                    if (failedPrewarmTakeoverResultGenerations[key] !== generation) {
                        failedPrewarmTakeoverResults.remove(key)
                        failedPrewarmTakeoverResultGenerations.remove(key)
                    }
                    if (failedPrewarmTakeoverGenerations[key] !== generation) {
                        failedPrewarmTakeovers.remove(key)
                        failedPrewarmTakeoverGenerations.remove(key)
                    }
                    failedPrewarmTakeoverResults[key]?.let { result ->
                        immediate = result
                        return@withLock null
                    }
                    failedPrewarmTakeovers[key]
                        ?: prewarmInFlight[key]?.takeIf { prewarmInFlightGenerations[key] === generation }
                        ?: prewarmMutexTakeover(key, generation, fetch).also { candidate ->
                            failedPrewarmTakeovers[key] = candidate
                            failedPrewarmTakeoverGenerations[key] = generation
                            prewarmReadyGenerations.remove(key)
                            prewarmInFlight.remove(key)
                            prewarmInFlightGenerations.remove(key)
                            created = candidate
                        }
                }
            val result =
                immediate
                    ?: run {
                        val shared = deferred ?: error("failed prewarm takeover lookup missing")
                        created?.start()
                        shared.await()
                    }
            if (freshnessLedger.isCurrent(generation)) return@coroutineScope result
        }
        error("failed prewarm takeover loop exited unexpectedly")
    }

    suspend fun recordCanonicalSuccess(
        generation: SharedThreadFreshnessGeneration,
        ownerScopeId: String
    ) {
        if (generation.ownerScopeId != ownerScopeId) return
        val key =
            sharedThreadOwnedPayloadCacheKey(
                ownerScopeId,
                generation.key.serverId,
                generation.key.threadId
            )
        prewarmMutex.withLock {
            freshnessLedger.runIfCurrent(generation) {
                prewarmFailures.remove(key)
                prewarmFailureGenerations.remove(key)
                failedPrewarmTakeoverResults.remove(key)
                failedPrewarmTakeoverResultGenerations.remove(key)
                // An explicit canonical retry is the manual escape from a failed
                // prewarm generation. Detach any older app-scope takeover so its
                // later completion cannot make that terminal failure sticky again.
                failedPrewarmTakeovers.remove(key)
                failedPrewarmTakeoverGenerations.remove(key)
            }
        }
    }

    private fun prewarmMutexTakeover(
        key: SharedThreadOwnedPayloadCacheKey,
        generation: SharedThreadFreshnessGeneration,
        fetch: suspend () -> DataResult<KuiklyThreadScreenPayload>
    ): Deferred<DataResult<KuiklyThreadScreenPayload>> =
        failedPrewarmTakeovers[key]
            ?.takeIf { failedPrewarmTakeoverGenerations[key] === generation }
            ?: scope.async(start = CoroutineStart.LAZY) {
                try {
                    val result = fetch()
                    prewarmMutex.withLock {
                        val currentJob = currentCoroutineContext()[Job]
                        if (
                            failedPrewarmTakeovers[key] === currentJob
                        ) {
                            freshnessLedger.runIfCurrent(generation) {
                                if (result is DataResult.Content) {
                                    payloads.update { current ->
                                        current + (key to SharedThreadPayloadEntry(result.data, generation))
                                    }
                                    prewarmReadyGenerations[key] = generation
                                    prewarmFailures.remove(key)
                                    prewarmFailureGenerations.remove(key)
                                    failedPrewarmTakeoverResults.remove(key)
                                    failedPrewarmTakeoverResultGenerations.remove(key)
                                } else {
                                    failedPrewarmTakeoverResults[key] = result
                                    failedPrewarmTakeoverResultGenerations[key] = generation
                                }
                            }
                        }
                    }
                    result
                } finally {
                    val currentJob = currentCoroutineContext()[Job]
                    prewarmMutex.withLock {
                        if (failedPrewarmTakeovers[key] === currentJob) {
                            failedPrewarmTakeovers.remove(key)
                            failedPrewarmTakeoverGenerations.remove(key)
                        }
                    }
                }
            }

    private suspend fun awaitPrewarmStartRateLimit() {
        if (prewarmStartIntervalMillis <= 0L) return
        prewarmStartMutex.withLock {
            val elapsedMillis = lastPrewarmStartedAt?.elapsedNow()?.inWholeMilliseconds
            val remainingMillis =
                elapsedMillis
                    ?.let { elapsed -> (prewarmStartIntervalMillis - elapsed).coerceAtLeast(0L) }
                    ?: 0L
            if (remainingMillis > 0L) {
                delay(remainingMillis)
            }
            lastPrewarmStartedAt = TimeSource.Monotonic.markNow()
        }
    }

    internal fun clearForTest() {
        prewarmInFlight.values.forEach { it.cancel() }
        failedPrewarmTakeovers.values.forEach { it.cancel() }
        prewarmInFlight.clear()
        prewarmInFlightGenerations.clear()
        prewarmFetchStartedGenerations.clear()
        prewarmReadyGenerations.clear()
        prewarmFailures.clear()
        prewarmFailureGenerations.clear()
        failedPrewarmTakeovers.clear()
        failedPrewarmTakeoverGenerations.clear()
        failedPrewarmTakeoverResults.clear()
        failedPrewarmTakeoverResultGenerations.clear()
        lastPrewarmStartedAt = null
        payloads.value = emptyMap()
        freshnessLedger.clearForTest()
    }
}

private data class SharedThreadPrewarmLookup(
    val generation: SharedThreadFreshnessGeneration,
    val cached: KuiklyThreadScreenPayload?,
    val inFlight: Deferred<DataResult<KuiklyThreadScreenPayload>>?,
    val failure: DataResult<KuiklyThreadScreenPayload>?
)

private fun Message.needsSharedThreadMessageStoreHydration(): Boolean =
    body.isBlank() && attachments.isEmpty() && forwardBundle == null

private fun Message.matchesSharedThreadMessageStoreKey(
    channelId: ChannelId,
    messageId: MessageId
): Boolean =
    id == messageId &&
        !needsSharedThreadMessageStoreHydration() &&
        (this.channelId == channelId || threadId == ThreadId(channelId.value))

internal data class MarkdownThreadRefTarget(
    val parentMessageId: String,
    val focusedMessageId: String? = null,
    val threadChannelId: String? = null
)

internal data class SharedThreadPageFetchResult(
    val payload: KuiklyThreadScreenPayload,
    val requestedPageSize: Int,
    /**
     * Task #168: the raw server page plus the identity that fetched it, for the durable window
     * extender. Present only for repository-backed older / newer pages; never read for rendering.
     */
    val durablePage: SharedThreadDurablePage? = null
)

/** A fetched paging page as the durable window extender needs it (task #168). */
internal data class SharedThreadDurablePage(
    val context: ReplyCacheProducerContext,
    val rawPage: RawMessagePage
)

internal class SharedThreadDataSource(
    private val api: SlockThreadApi,
    private val sessionStore: SlockAuthSessionStore,
    private val pendingSendJournal: ThreadPendingSendJournal? = null,
    private val selectedServerStore: SelectedServerStore? = null,
    private val memberRepository: MemberRepository? = null,
    private val serverMembersStore: SharedServerMembersStore? = null,
    private val conversationRepository: ConversationRepository? = null,
    private val channelRepository: ChannelRepository? = null,
    private val homeSnapshotProvider: ((ServerId) -> HomeSnapshot?)? = null,
    private val channelProfileUpdateSink: suspend (ChannelProfileUpdateResult) -> Unit = {},
    private val messageStore: SharedThreadMessageStore? = null,
    // REQUIRED: the cache environment is an identity dimension of every reply cache. There is no
    // blank default; a caller that cannot name its environment owns no cache (task #1121).
    private val cacheEnvironmentIdProvider: () -> String,
    /** Current API environment used to bind direct-session uploads to one route authority. */
    private val apiEnvironmentIdProvider: () -> String = { cacheEnvironmentIdProvider() },
    // REQUIRED (task #1122): reply-cache identity comes only from the provider-owned authority
    // (`ThreadReplyCacheIdentityAuthority`). There is no private fallback resolver any more, so a
    // data source that cannot name its authority cannot be constructed.
    private val replyCacheIdentityAuthority: suspend () -> ThreadReplyCacheIdentitySnapshot?,
    private val replyCacheIdentityTransitionWaiter:
        (suspend (ThreadReplyCacheIdentityTransitionAnchor?) -> Boolean)? = null,
    payloadStore: SharedThreadPayloadStore? = null,
    /**
     * Offline cache read entry (task #1123). Absent means "no durable first frame"; the store then
     * behaves exactly as before this seam existed. Production wires the V2 reader from the provider.
     */
    private val durableLastKnownReader: ThreadDurableLastKnownReader? = null,
    /**
     * Offline cache write entry (task #1124): on entering a channel / DM, its current REST page is
     * committed to the V2 partition so the reader above has something to show next time. Absent
     * means "never write"; production wires the V2 loader from the provider.
     */
    private val enteredScopeSnapshotLoader: ThreadEnteredScopeSnapshotLoader? = null,
    /**
     * Offline cache paging entry (task #168): an older / newer page that is exactly adjacent to the
     * stored V2 window is appended to it. Absent means "never write"; production wires the V2
     * adjacent-page writer from the provider.
     */
    private val durablePageExtender: ThreadDurablePageExtender? = null,
    private val agentsApiClient: SlockAgentsApiClient? = null,
    private val actionCardsApiClient: SlockIntegrationsActionsPushApiClient? = null,
    private val savedStatusRepository: SavedStatusRepository? = null,
    private val readReceiptCoordinator: ReadReceiptCoordinator? = null,
    private val readReceiptOwnerFactory: ((String, String) -> ReadReceiptOwner)? = null,
    private val threadPageSizeProvider: (ServerId?) -> Int? = { null },
    private val replyOriginHydrationGraceMillis: Long = SharedThreadZeroReplyOriginHydrationGraceMillis,
    private val canonicalConversationIdentityPublisher:
        suspend (ReplyCacheProvenance, build.raft.app.model.ConversationSummary) -> Boolean = { _, _ -> false },
    private val diagnosticsLogger: (String) -> Unit = projectionRejectingDiagnosticsLogger,
    /**
     * Task #43: shared create-agent coordinator used by the action-card
     * path. Mirrors the field on `SharedMembersDataSource`; the actual
     * body construction + API call lives in
     * [build.raft.app.data.SharedCreateAgentCoordinator] so the
     * Members page and Thread action card share the same code path.
     */
    private val createAgentCoordinator: build.raft.app.data.SharedCreateAgentCoordinator? = null
) {
    /**
     * Public accessor for the [build.raft.app.data.SharedCreateAgentCoordinator]
     * field. Used by callers (e.g. `SettingsHomePage` which constructs
     * `SharedThreadDataSource` and forwards to `SharedThreadRouteContent`)
     * that need the coordinator to wire up the action-card create-agent
     * dialog. Returns null when the data source was constructed without
     * a coordinator; in that case the create-agent action card falls back
     * to the legacy name/description/machineId-only path.
     */
    val createAgentCoordinatorOrNull: build.raft.app.data.SharedCreateAgentCoordinator?
        get() = createAgentCoordinator

    internal fun seedThreadAuthorProfiles(
        serverId: String?,
        payload: KuiklyThreadScreenPayload,
        source: String
    ) {
        val scopedServerId = serverId?.trim()?.takeIf { it.isNotBlank() }?.let(::ServerId) ?: return
        serverMembersStore?.seedThreadAuthorProfiles(
            serverId = scopedServerId,
            messages = payload.threadAuthorProfileCandidates(),
            source = source,
            diagnosticsLogger = diagnosticsLogger
        )
    }

    internal fun seedRealtimeThreadAuthorProfiles(
        serverId: String?,
        event: RealtimeEvent,
        currentUserId: String?
    ) {
        val scopedServerId = serverId?.trim()?.takeIf { it.isNotBlank() }?.let(::ServerId) ?: return
        val messageItems =
            when (event) {
                is MessageNew -> listOf(event.message)
                is MessageUpdated -> listOf(event.message)
                is SyncResumeResponse -> event.messages
                else -> emptyList()
            }.map { message -> message.toKuiklyThreadMessageItem(currentUserId) }
        serverMembersStore?.seedThreadAuthorProfiles(
            serverId = scopedServerId,
            messages = messageItems,
            source = "realtime",
            diagnosticsLogger = diagnosticsLogger
        )
    }

    internal fun seedAcceptedRealtimeThreadReplyAuthorProfile(
        serverId: String?,
        reply: build.raft.app.network.sync.ReplyPreview
    ) {
        val scopedServerId = serverId?.trim()?.takeIf { it.isNotBlank() }?.let(::ServerId) ?: return
        serverMembersStore?.seedAcceptedThreadReplyAuthorProfile(
            serverId = scopedServerId,
            reply = reply,
            source = "realtime_inline",
            diagnosticsLogger = diagnosticsLogger
        )
    }

    constructor(
        networkApi: SharedThreadNetworkApi,
        sessionStore: SlockAuthSessionStore,
        pendingSendJournal: ThreadPendingSendJournal? = null,
        selectedServerStore: SelectedServerStore? = null,
        memberRepository: MemberRepository? = null,
        serverMembersStore: SharedServerMembersStore? = null,
        conversationRepository: ConversationRepository? = null,
        channelRepository: ChannelRepository? = null,
        homeSnapshotProvider: ((ServerId) -> HomeSnapshot?)? = null,
        channelProfileUpdateSink: suspend (ChannelProfileUpdateResult) -> Unit = {},
        messageStore: SharedThreadMessageStore? = null,
        cacheEnvironmentIdProvider: () -> String,
        apiEnvironmentIdProvider: () -> String = cacheEnvironmentIdProvider,
        replyCacheIdentityAuthority: suspend () -> ThreadReplyCacheIdentitySnapshot?,
        replyCacheIdentityTransitionWaiter:
            (suspend (ThreadReplyCacheIdentityTransitionAnchor?) -> Boolean)? = null,
        payloadStore: SharedThreadPayloadStore? = null,
        durableLastKnownReader: ThreadDurableLastKnownReader? = null,
        enteredScopeSnapshotLoader: ThreadEnteredScopeSnapshotLoader? = null,
        durablePageExtender: ThreadDurablePageExtender? = null,
        agentsApiClient: SlockAgentsApiClient? = null,
        actionCardsApiClient: SlockIntegrationsActionsPushApiClient? = null,
        savedStatusRepository: SavedStatusRepository? = null,
        readReceiptCoordinator: ReadReceiptCoordinator? = null,
        readReceiptOwnerFactory: ((String, String) -> ReadReceiptOwner)? = null,
        threadPageSizeProvider: (ServerId?) -> Int? = { null },
        replyOriginHydrationGraceMillis: Long = SharedThreadZeroReplyOriginHydrationGraceMillis,
        canonicalConversationIdentityPublisher:
            suspend (ReplyCacheProvenance, build.raft.app.model.ConversationSummary) -> Boolean = { _, _ -> false },
        createAgentCoordinator: build.raft.app.data.SharedCreateAgentCoordinator? = null,
        diagnosticsLogger: (String) -> Unit = projectionRejectingDiagnosticsLogger
    ) : this(
        api = SlockThreadApiClient(networkApi, diagnosticsLogger),
        sessionStore = sessionStore,
        pendingSendJournal = pendingSendJournal,
        selectedServerStore = selectedServerStore,
        memberRepository = memberRepository,
        serverMembersStore = serverMembersStore,
        conversationRepository = conversationRepository,
        channelRepository = channelRepository,
        homeSnapshotProvider = homeSnapshotProvider,
        channelProfileUpdateSink = channelProfileUpdateSink,
        messageStore = messageStore,
        cacheEnvironmentIdProvider = cacheEnvironmentIdProvider,
        apiEnvironmentIdProvider = apiEnvironmentIdProvider,
        replyCacheIdentityAuthority = replyCacheIdentityAuthority,
        replyCacheIdentityTransitionWaiter = replyCacheIdentityTransitionWaiter,
        payloadStore = payloadStore,
        durableLastKnownReader = durableLastKnownReader,
        enteredScopeSnapshotLoader = enteredScopeSnapshotLoader,
        durablePageExtender = durablePageExtender,
        agentsApiClient = agentsApiClient,
        actionCardsApiClient = actionCardsApiClient,
        savedStatusRepository = savedStatusRepository,
        readReceiptCoordinator = readReceiptCoordinator,
        readReceiptOwnerFactory = readReceiptOwnerFactory,
        threadPageSizeProvider = threadPageSizeProvider,
        replyOriginHydrationGraceMillis = replyOriginHydrationGraceMillis,
        canonicalConversationIdentityPublisher = canonicalConversationIdentityPublisher,
        diagnosticsLogger = diagnosticsLogger,
        createAgentCoordinator = createAgentCoordinator
    )

    private val payloadCache = payloadStore ?: SharedThreadPayloadStore()
    private val messageResolver = messageStore ?: SharedThreadMessageStore()
    private val replyThreadResolveCache = mutableMapOf<ReplyThreadResolveKey, String>()
    private val cacheEnvironmentId = MutableStateFlow("")
    private val cacheServerId = MutableStateFlow("")
    private val cachePrincipalId = MutableStateFlow("")
    private val cachePayloadOwnerScopeId = MutableStateFlow("")
    private val replyCacheProvenanceLock = SynchronizedObject()
    private val replyCacheCaptureSequence = atomic(0L)
    private val replyCacheProvenance = MutableStateFlow<ReplyCacheProvenance?>(null)
    internal val replyCacheProvenanceState: StateFlow<ReplyCacheProvenance?> =
        replyCacheProvenance.asStateFlow()
    private var latestPublishedReplyCacheCapture = 0L
    internal var onReplyCacheIdentitySourceCapturedForTest: (suspend () -> Unit)? = null
    internal var onMarkdownReplyCacheScopeCapturedForTest: (suspend () -> Unit)? = null
    internal var onReplyThreadAccessValidatedForTest: (suspend () -> Unit)? = null
    internal var onReplyCacheFirstFrameAdmissionForTest: (suspend () -> Unit)? = null

    private fun threadPageSize(serverId: String?): Int =
        SharedThreadPaging.resolve(
            threadPageSizeProvider(serverId?.trim()?.takeIf { it.isNotEmpty() }?.let(::ServerId))
        )

    suspend fun readSession(): StoredAuthSession? = readReplyResolutionScope()?.session

    internal suspend fun captureReplyCacheProducerContext(): ReplyCacheProducerContext? =
        readReplyResolutionScope()?.let { scope ->
            ReplyCacheProducerContext(scope.session, scope.provenance)
        }

    internal fun pendingSendJournalOrNull(): ThreadPendingSendJournal? = pendingSendJournal

    /** Durable admission gate for a local send. Must complete before the caller starts HTTP. */
    internal fun persistPendingLocalSend(
        provenance: ReplyCacheProvenance,
        route: ThreadRouteIdentity,
        message: KuiklyThreadMessageItem,
    ): Boolean = pendingSendJournal?.persistSending(provenance, route, message) ?: true

    internal fun clearPendingLocalSend(
        provenance: ReplyCacheProvenance,
        route: ThreadRouteIdentity,
        localId: String,
    ): ThreadPendingSendJournal.ConsumeResult =
        pendingSendJournal?.consume(provenance, route, localId)
            ?: ThreadPendingSendJournal.ConsumeResult.NotFound

    /**
     * Terminal cleanup for an already-admitted send. The old provenance names the exact journal
     * row to retire; the live provenance is checked first and may be a same-binding token
     * successor. This keeps cleanup from becoming an escape hatch across account/server fences.
     */
    internal fun clearPendingLocalSendForLocalSend(
        expectedProvenance: ReplyCacheProvenance,
        route: ThreadRouteIdentity,
        localId: String,
    ): ThreadPendingSendJournal.ConsumeResult =
        synchronized(replyCacheProvenanceLock) {
            if (replyCacheProvenance.value?.isCurrentOrSuccessorForLocalSend(expectedProvenance) != true) {
                return@synchronized ThreadPendingSendJournal.ConsumeResult.Failed
            }
            pendingSendJournal?.consume(expectedProvenance, route, localId)
                ?: ThreadPendingSendJournal.ConsumeResult.NotFound
        }

    /** True when terminal local-send cleanup has a durable row to fence. */
    internal val hasDurablePendingSendJournal: Boolean
        get() = pendingSendJournal != null

    internal fun markPendingLocalSendFailedForLocalSend(
        expectedProvenance: ReplyCacheProvenance,
        route: ThreadRouteIdentity,
        localId: String,
        errorCode: String?,
        errorMessage: String?,
    ): Boolean =
        synchronized(replyCacheProvenanceLock) {
            if (replyCacheProvenance.value?.isCurrentOrSuccessorForLocalSend(expectedProvenance) != true) {
                return@synchronized false
            }
            pendingSendJournal?.markFailed(
                provenance = expectedProvenance,
                route = route,
                localId = localId,
                errorCode = errorCode,
                errorMessage = errorMessage,
            ) ?: true
        }

    internal fun restorePendingLocalSends(
        provenance: ReplyCacheProvenance,
        route: ThreadRouteIdentity,
        canonicalPayload: KuiklyThreadScreenPayload,
    ): KuiklyThreadScreenPayload {
        val journal = pendingSendJournal ?: return canonicalPayload
        // A cached/launch payload is only a canonical candidate. Pending rows
        // are owned by the scoped journal, so discard any compatibility shadow
        // before merging the journal projection back in.
        val canonicalProjection = canonicalPayload.withoutLocalPendingSendMessages()
        val canonicalRandomIds =
            canonicalProjection.messages
                .asSequence()
                .filter { it.pendingSendContext == null }
                .mapNotNull { it.randomId?.trim()?.takeIf(String::isNotBlank) }
                .toSet()
        val restored = journal.restore(provenance, route)
        val surviving = restored.filterNot { item ->
            val randomId = item.pendingSendContext?.randomId?.trim()?.takeIf(String::isNotBlank)
            if (randomId == null || randomId !in canonicalRandomIds) return@filterNot false
            when (journal.consumeCanonicalRandomId(provenance, route, randomId)) {
                // A canonical row is authoritative even if the local row was already consumed by
                // another merge path.  Keeping that stale shadow would render a duplicate bubble
                // until the next refresh, so both terminal success and an idempotent miss remove
                // it from this projection.
                ThreadPendingSendJournal.ConsumeResult.Deleted,
                ThreadPendingSendJournal.ConsumeResult.NotFound -> true
                // Do not drop the recovered projection merely because the durable delete failed.
                // The row remains recoverable on the next launch and the diagnostic preserves the
                // distinction between a storage failure and an idempotent miss.
                ThreadPendingSendJournal.ConsumeResult.Failed -> {
                    diagnosticsLogger(
                        "thread_pending_send_canonical_cleanup_failed randomId=$randomId " +
                            "serverId=${provenance.serverId} route=${route.instanceKey}"
                    )
                    false
                }
            }
        }
        val existingIds = canonicalProjection.messages.mapTo(mutableSetOf()) { it.id }
        val additions = surviving.filter { it.id !in existingIds }
        return if (additions.isEmpty()) {
            canonicalProjection
        } else {
            canonicalProjection.copy(
                status = "content",
                messages = (canonicalProjection.messages + additions).sortedByThreadTimeline()
            ).withOriginReplyCountAtLeastLoadedReplies()
        }
    }

    private suspend fun readReplyResolutionScope(): ReplyResolutionScope? {
        val captureSequence = replyCacheCaptureSequence.incrementAndGet()
        var transitionAttempts = 0
        while (true) {
            var source: ThreadReplyCacheIdentitySnapshot? = null
            var sampleAttempts = 0
            while (source == null && sampleAttempts < 4) {
                sampleAttempts += 1
                val candidate = readReplyCacheIdentitySource()
                onReplyCacheIdentitySourceCapturedForTest?.invoke()
                val confirmed = readReplyCacheIdentitySource()
                if (candidate == confirmed) {
                    source = confirmed
                    break
                }
            }
            if (source != null) {
                val provenance = publishReplyCacheProvenance(source, captureSequence)
                cacheEnvironmentId.value = provenance.environmentId
                cacheServerId.value = provenance.serverId
                cachePrincipalId.value = provenance.principalId
                cachePayloadOwnerScopeId.value = provenance.threadPayloadOwnerScopeId.orEmpty()
                return ReplyResolutionScope(
                    session = source.session,
                    provenance = provenance,
                    sharedMessageStore = messageStore != null
                )
            }

            // A durable session can become visible before the provider-owned auth-revision collector
            // publishes its matching immutable context or before this shell's selected-server owner
            // converges. Retire the old token before waiting so stale page producers stay zero-write,
            // then pass that retired authority as the only license for an A→B owner transition. Stable
            // hybrids, environment mismatches, and missing authority remain fail-closed.
            val transitionAnchor =
                replyCacheProvenance.value?.let { current ->
                    ThreadReplyCacheIdentityTransitionAnchor(
                        environmentId = current.environmentId,
                        principalId = current.principalId,
                        authGeneration = current.authGeneration,
                        selectionGeneration = current.selectionGeneration,
                    )
                }
            clearReplyCacheProvenance(captureSequence)
            val shouldRetry =
                transitionAttempts < 4 &&
                    replyCacheIdentityTransitionWaiter?.invoke(transitionAnchor) == true
            if (!shouldRetry) return null
            transitionAttempts += 1
        }
    }

    private suspend fun readReplyCacheIdentitySource(): ThreadReplyCacheIdentitySnapshot? =
        replyCacheIdentityAuthority()

    private fun publishReplyCacheProvenance(
        source: ThreadReplyCacheIdentitySnapshot,
        captureSequence: Long
    ): ReplyCacheProvenance =
        synchronized(replyCacheProvenanceLock) {
            val current = replyCacheProvenance.value
            val sameIdentity =
                current != null &&
                current.environmentId == source.environmentId &&
                current.principalId == source.principalId &&
                current.serverId == source.serverId &&
                current.authGeneration == source.authGeneration &&
                current.selectionGeneration == source.selectionGeneration &&
                current.bindingGeneration == source.bindingGeneration
            if (sameIdentity) {
                if (captureSequence > latestPublishedReplyCacheCapture) {
                    latestPublishedReplyCacheCapture = captureSequence
                }
                current
            } else if (captureSequence < latestPublishedReplyCacheCapture) {
                ReplyCacheProvenance(
                    environmentId = source.environmentId,
                    principalId = source.principalId,
                    serverId = source.serverId,
                    generation = captureSequence,
                    authGeneration = source.authGeneration,
                    selectionGeneration = source.selectionGeneration,
                    bindingGeneration = source.bindingGeneration,
                )
            } else {
                latestPublishedReplyCacheCapture = captureSequence
                val next =
                    ReplyCacheProvenance(
                        environmentId = source.environmentId,
                        principalId = source.principalId,
                        serverId = source.serverId,
                        generation = captureSequence,
                        authGeneration = source.authGeneration,
                        selectionGeneration = source.selectionGeneration,
                        bindingGeneration = source.bindingGeneration,
                    )
                current
                    ?.threadPayloadOwnerScopeId
                    ?.takeIf { oldOwnerScopeId ->
                        oldOwnerScopeId != next.threadPayloadOwnerScopeId
                    }
                    ?.let(payloadCache::retireOwner)
                next.also { replyCacheProvenance.value = it }
            }
        }

    private fun clearReplyCacheProvenance(captureSequence: Long) {
        synchronized(replyCacheProvenanceLock) {
            if (captureSequence >= latestPublishedReplyCacheCapture) {
                latestPublishedReplyCacheCapture = captureSequence
                replyCacheProvenance.value
                    ?.threadPayloadOwnerScopeId
                    ?.let(payloadCache::retireOwner)
                replyCacheProvenance.value = null
            }
        }
        cacheEnvironmentId.value = ""
        cacheServerId.value = ""
        cachePrincipalId.value = ""
        cachePayloadOwnerScopeId.value = ""
    }

    internal fun isCurrentReplyCacheProvenance(expected: ReplyCacheProvenance): Boolean =
        synchronized(replyCacheProvenanceLock) {
            replyCacheProvenance.value == expected
        }

    /**
     * Send terminal work may cross a transparent token rotation, but never a new auth binding or
     * selected server. Keep the ordinary cache/read fence above strict; this relation is only for
     * the already-admitted local send state machine.
     */
    internal fun isCurrentReplyCacheProvenanceForLocalSend(expected: ReplyCacheProvenance): Boolean =
        synchronized(replyCacheProvenanceLock) {
            replyCacheProvenance.value?.isCurrentOrSuccessorForLocalSend(expected) == true
        }

    internal fun currentReplyCacheProvenanceForLocalSend(
        expected: ReplyCacheProvenance
    ): ReplyCacheProvenance? =
        synchronized(replyCacheProvenanceLock) {
            replyCacheProvenance.value?.takeIf { current ->
                current.isCurrentOrSuccessorForLocalSend(expected)
            }
        }

    internal fun currentReplyCacheProvenance(): ReplyCacheProvenance? =
        synchronized(replyCacheProvenanceLock) {
            replyCacheProvenance.value
        }

    internal fun <T> withCurrentReplyCacheProvenance(
        expected: ReplyCacheProvenance,
        block: () -> T
    ): T? =
        synchronized(replyCacheProvenanceLock) {
            if (replyCacheProvenance.value != expected) return@synchronized null
            block()
        }

    /**
     * Re-applies the recorded reply-count fact ledger to a projected payload,
     * guarded by the expected reply-cache provenance. Returns `null` when the
     * provenance rotated (caller must drop), otherwise the ledger-projected
     * payload. Used by commit paths whose projections can overwrite a newer
     * realtime fact with a stale summary-snapshot value.
     */
    internal fun applyReplyCountFacts(
        threadId: String,
        expected: ReplyCacheProvenance,
        payload: KuiklyThreadScreenPayload,
    ): KuiklyThreadScreenPayload? =
        synchronized(replyCacheProvenanceLock) {
            if (replyCacheProvenance.value != expected) return@synchronized null
            payloadCache.applyReplyCountFacts(
                serverId = expected.serverId,
                threadId = threadId,
                environmentId = expected.environmentId,
                principalId = expected.principalId,
                payload = payload,
            )
        }

    internal fun <T> withCurrentReplyCacheProvenanceForLocalSend(
        expected: ReplyCacheProvenance,
        block: (ReplyCacheProvenance) -> T
    ): T? =
        synchronized(replyCacheProvenanceLock) {
            val current = replyCacheProvenance.value
            if (current?.isCurrentOrSuccessorForLocalSend(expected) != true) {
                return@synchronized null
            }
            block(current)
        }

    fun cachePayload(
        payload: KuiklyThreadScreenPayload,
        serverId: String = cacheServerId.value,
        expectedGeneration: SharedThreadFreshnessGeneration? = null,
        ownerScopeId: String = cachePayloadOwnerScopeId.value,
        expectedProvenance: ReplyCacheProvenance? = null
    ): Boolean =
        synchronized(replyCacheProvenanceLock) {
            if (expectedProvenance != null && replyCacheProvenance.value != expectedProvenance) {
                return@synchronized false
            }
            if (ownerScopeId.isBlank()) return@synchronized false
            if (replyCacheProvenance.value?.threadPayloadOwnerScopeId != ownerScopeId) {
                return@synchronized false
            }
            val canonicalRoute = payload.resolveThreadRouteIdentity()
            val canonicalProvenance = expectedProvenance ?: replyCacheProvenance.value
            val threadId =
                canonicalRoute.timelineChannelId
                    ?: return@synchronized false
            val cacheablePayload =
                (if (pendingSendJournal == null) {
                    // Fixture/legacy data sources without a journal retain their historical
                    // failed-send cache projection.
                    payload.withoutTransientLocalSendingMessages()
                } else {
                    // Production's durable journal is the sole pending-state authority. Do not
                    // turn the canonical payload cache into a second pending database.
                    payload.withoutLocalPendingSendMessages()
                })
                    .withOriginReplyCountAtLeastLoadedReplies()
                    .asLoadedEmptyReplyThreadPayload()
            if (cacheablePayload.hasRenderableThreadContent()) {
                val candidate =
                    cacheablePayload.copy(
                        status = if (cacheablePayload.isLoadedEmptyReplyThread()) "empty" else "content",
                        backgroundRefreshing = false,
                        errorCode = null
                    )
                if (expectedGeneration == null) {
                    payloadCache.put(serverId, threadId, candidate, ownerScopeId)
                } else if (
                    expectedGeneration.key != SharedThreadPayloadCacheKey(serverId, threadId) ||
                    expectedGeneration.ownerScopeId != ownerScopeId ||
                    !payloadCache.putIfCurrent(expectedGeneration, candidate, ownerScopeId)
                ) {
                    return@synchronized false
                }
            } else {
                if (expectedGeneration == null) {
                    payloadCache.remove(serverId, threadId, ownerScopeId)
                } else if (
                    expectedGeneration.key != SharedThreadPayloadCacheKey(serverId, threadId) ||
                    expectedGeneration.ownerScopeId != ownerScopeId ||
                    !payloadCache.removeIfCurrent(expectedGeneration)
                ) {
                    return@synchronized false
                }
            }
            // The cache write is the commit point for this canonical observation.  Only after it
            // succeeds do we attempt to retire local pending rows; if the delete itself fails,
            // the canonical cache remains valid and a later pull can retry the idempotent cleanup.
            if (canonicalProvenance != null) {
                cacheablePayload.messages
                    .asSequence()
                    .filter { it.pendingSendContext == null }
                    .mapNotNull { it.randomId?.trim()?.takeIf(String::isNotBlank) }
                    .distinct()
                    .forEach { randomId ->
                        when (
                            pendingSendJournal?.consumeCanonicalRandomId(
                                provenance = canonicalProvenance,
                                route = canonicalRoute,
                                randomId = randomId,
                            )
                        ) {
                            ThreadPendingSendJournal.ConsumeResult.Failed ->
                                diagnosticsLogger(
                                    "thread_pending_send_canonical_cleanup_failed randomId=$randomId " +
                                        "serverId=${canonicalProvenance.serverId} route=${canonicalRoute.instanceKey}"
                                )
                            else -> Unit
                        }
                    }
            }
            true
        }

    fun cachedPayload(
        threadId: String,
        ownerScopeId: String = cachePayloadOwnerScopeId.value,
        serverId: String = cacheServerId.value,
        principalId: String = cachePrincipalId.value,
        environmentId: String = cacheEnvironmentId.value
    ): KuiklyThreadScreenPayload? =
        ownerScopeId
            .takeIf { ownerScopeId -> ownerScopeId.isNotBlank() }
            ?.let { ownerScopeId ->
                payloadCache.get(
                    serverId,
                    threadId,
                    environmentId,
                    principalId,
                    ownerScopeId
                )
            }

    internal fun cachedPayloadIfCurrent(
        threadId: String,
        expectedProvenance: ReplyCacheProvenance
    ): KuiklyThreadScreenPayload? =
        synchronized(replyCacheProvenanceLock) {
            if (replyCacheProvenance.value != expectedProvenance) return@synchronized null
            val ownerScopeId = expectedProvenance.threadPayloadOwnerScopeId ?: return@synchronized null
            payloadCache.get(
                expectedProvenance.serverId,
                threadId,
                expectedProvenance.environmentId,
                expectedProvenance.principalId,
                ownerScopeId
            )
        }

    /**
     * Durable last-known first frame for a top-level route (channel / DM), read from the
     * `sync-core.messages.v2` partition of [context]'s identity. Returns `null` for every
     * non-positive case (no reader, reply-thread route, unrenderable route, incomplete identity,
     * identity moved during the disk read, flag off, no baseline, projection produced nothing) so the
     * caller falls through to the ordinary loading shell. The returned payload is a stand-in, not an
     * authority: paging is closed and `durableLastKnown` is set so the first network content slice
     * replaces it wholesale.
     */
    /**
     * Offline cache write entry (task #1124): fetch the entered channel's current page into the
     * current identity's V2 partition. Same admission as [durableLastKnownPayloadIfCurrent]
     * (top-level surface, resolvable channel id, an owning partition, current provenance); the
     * loader re-reads `stillCurrent` after its network round trip. Returns whether a load was
     * requested at all — callers only use this for diagnostics, never for rendering.
     */
    internal suspend fun loadEnteredScopeSnapshotIfCurrent(
        routeIdentity: ThreadRouteIdentity,
        context: ReplyCacheProducerContext,
    ): Boolean {
        val loader = enteredScopeSnapshotLoader ?: return false
        if (!routeIdentity.canRenderTimelineCandidates) return false
        when (routeIdentity.surfaceKind) {
            KuiklyThreadSurfaceKind.Channel, KuiklyThreadSurfaceKind.DirectMessage -> Unit
            else -> return false
        }
        val channelId = routeIdentity.timelineChannelId ?: return false
        val partition =
            context.provenance.durableMessagePartitionOrNull(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
                ?: return false
        if (!isCurrentReplyCacheProvenance(context.provenance)) return false
        loader.loadOnEnter(partition, channelId) { isCurrentReplyCacheProvenance(context.provenance) }
        return true
    }

    /**
     * Offline cache paging entry (task #168): hand a fetched older / newer page to the durable window
     * extender, which appends it only when it is exactly adjacent to the stored window. Same admission
     * as [loadEnteredScopeSnapshotIfCurrent]; `stillCurrent` is re-read by the extender after its own
     * network round trip. Returns whether the extender was asked at all (diagnostics only).
     */
    internal suspend fun extendDurableWindowIfCurrent(
        routeIdentity: ThreadRouteIdentity,
        context: ReplyCacheProducerContext,
        direction: ThreadDurablePageDirection,
        anchorSeq: Long,
        rawPage: RawMessagePage,
    ): Boolean {
        val extender = durablePageExtender ?: return false
        if (!routeIdentity.canRenderTimelineCandidates) return false
        when (routeIdentity.surfaceKind) {
            KuiklyThreadSurfaceKind.Channel, KuiklyThreadSurfaceKind.DirectMessage -> Unit
            else -> return false
        }
        val channelId = routeIdentity.timelineChannelId ?: return false
        val partition =
            context.provenance.durableMessagePartitionOrNull(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
                ?: return false
        if (!isCurrentReplyCacheProvenance(context.provenance)) return false
        extender.extendWithAdjacentPage(
            partition = partition,
            channelId = channelId,
            direction = direction,
            anchorSeq = anchorSeq,
            rawMessages = rawPage.messages,
            requestedLimit = rawPage.requestedLimit,
        ) { isCurrentReplyCacheProvenance(context.provenance) }
        return true
    }

    internal suspend fun durableLastKnownPayloadIfCurrent(
        routePayload: KuiklyThreadScreenPayload,
        routeIdentity: ThreadRouteIdentity,
        context: ReplyCacheProducerContext,
    ): KuiklyThreadScreenPayload? {
        val reader = durableLastKnownReader ?: return null
        if (!routeIdentity.canRenderTimelineCandidates) return null
        val allowedScopeKinds =
            when (routeIdentity.surfaceKind) {
                KuiklyThreadSurfaceKind.Channel -> setOf("channel", "private", "joint")
                KuiklyThreadSurfaceKind.DirectMessage -> setOf("dm")
                else -> return null
            }
        val channelId = routeIdentity.timelineChannelId ?: return null
        val partition =
            context.provenance.durableMessagePartitionOrNull(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
                ?: return null
        if (!isCurrentReplyCacheProvenance(context.provenance)) return null
        val frame = reader.read(partition, channelId) ?: return null
        // The disk read suspended; a sign-out / account switch in between must not let identity A's
        // rows act under identity B's route. Rendering is ALSO refused by the commit gate in start()
        // (`withCurrentReplyCacheProvenance`), but `seedThreadAuthorProfiles` runs upstream of that
        // gate: without this check A's author profiles would be written into the process-wide
        // `serverMembersStore` before the frame is rejected. This line guards the write, not the display.
        if (!isCurrentReplyCacheProvenance(context.provenance)) return null
        if (frame.scope.scopeKind !in allowedScopeKinds) {
            diagnosticsLogger(
                "thread_durable_last_known threadId=$channelId outcome=surface_mismatch " +
                    "surface=${routeIdentity.surfaceKind} scopeKind=${frame.scope.scopeKind}"
            )
            return null
        }
        if (frame.envelopes.isEmpty()) {
            // Task #1137: the channel was empty when last seen. Stand in with the ordinary empty
            // state (flagged as last-known) rather than falling through to "cannot load".
            diagnosticsLogger(
                "thread_durable_last_known threadId=$channelId outcome=empty_frame scopeKind=${frame.scope.scopeKind}"
            )
            return routePayload.asRefreshEmpty(routePayload).copy(durableLastKnown = true)
        }
        // Stand in with the newest page only, the same size the network's first page has: projecting,
        // laying out and drawing the whole stored scope (up to 500 facts) held the first frame behind a
        // loading state for over a second on a cold open (artin, Hands 2d15915b: 147 rows ⇒ ~1.3 s).
        val pageSize = threadPageSize(partition.serverId)
        val messages =
            frame.envelopes
                .sortedByDescending { it.fact.seq }
                .take(pageSize)
                .mapNotNull { envelope ->
                    envelope.toThreadMessageItem(
                        currentUserId = context.provenance.principalId,
                        diagnostics = diagnosticsLogger,
                    )
                }
                .sortedByThreadTimeline()
        if (messages.isEmpty()) return null
        diagnosticsLogger(
            "thread_durable_last_known threadId=$channelId outcome=frame scopeKind=${frame.scope.scopeKind} " +
                "messages=${messages.size} stored=${frame.envelopes.size} latestSeq=${frame.cursor.latestSeq}"
        )
        return routePayload.copy(
            status = "content",
            backgroundRefreshing = false,
            durableLastKnown = true,
            messages = messages,
            canLoadMoreBefore = false,
            canLoadMoreAfter = false,
            olderMessagesStatus = "idle",
            newerMessagesStatus = "idle",
            errorCode = null,
        )
    }

    suspend fun rememberedReplyThreadAccessFailure(
        basePayload: KuiklyThreadScreenPayload
    ): ScreenError? {
        val context = captureReplyCacheProducerContext() ?: return null
        return rememberedReplyThreadAccessFailure(basePayload, context)
    }

    internal suspend fun rememberedReplyThreadAccessFailure(
        basePayload: KuiklyThreadScreenPayload,
        context: ReplyCacheProducerContext
    ): ScreenError? {
        if (!isCurrentReplyCacheProvenance(context.provenance)) return null
        val resolutionScope =
            ReplyResolutionScope(
                session = context.session,
                provenance = context.provenance,
                sharedMessageStore = messageStore != null
            )
        val key = replyThreadAccessFlightKey(resolutionScope, basePayload) ?: return null
        return payloadCache.replyThreadAccessFailure(key)
    }

    internal suspend fun clearRememberedReplyThreadAccessFailure(
        basePayload: KuiklyThreadScreenPayload,
        context: ReplyCacheProducerContext,
    ): Boolean {
        if (!isCurrentReplyCacheProvenance(context.provenance)) return false
        val resolutionScope =
            ReplyResolutionScope(
                session = context.session,
                provenance = context.provenance,
                sharedMessageStore = messageStore != null,
            )
        val key = replyThreadAccessFlightKey(resolutionScope, basePayload) ?: return true
        payloadCache.clearReplyThreadAccessFailure(key)
        return isCurrentReplyCacheProvenance(context.provenance)
    }

    private fun replyThreadAccessFlightKey(
        resolutionScope: ReplyResolutionScope,
        basePayload: KuiklyThreadScreenPayload
    ): SharedReplyThreadAccessFlightKey? {
        if (basePayload.surfaceKind != KuiklyThreadSurfaceKind.ReplyThread) return null
        val parentChannelId = basePayload.parentChannelId?.takeIf { it.isNotBlank() } ?: return null
        val parentMessageId =
            basePayload.parentMessageId?.takeIf { it.isNotBlank() }
                ?: basePayload.originParentMessageId?.takeIf { it.isNotBlank() }
                ?: return null
        val provenance = resolutionScope.provenance
        val authBinding =
            listOf(
                provenance.generation.toString(),
                provenance.authGeneration.toString(),
                provenance.selectionGeneration.toString(),
                resolutionScope.session.accessToken
            ).joinToString("\u001f")
        return sharedReplyThreadAccessFlightKey(
            environmentId = provenance.environmentId,
            principalId = provenance.principalId,
            serverId = provenance.serverId,
            authBinding = authBinding,
            parentChannelId = parentChannelId,
            parentMessageId = parentMessageId
        )
    }

    private suspend fun replyThreadAccessAuthorityStillCurrent(
        expectedKey: SharedReplyThreadAccessFlightKey,
        expectedProvenance: ReplyCacheProvenance,
        basePayload: KuiklyThreadScreenPayload
    ): Boolean {
        val currentScope = readReplyResolutionScope() ?: return false
        return currentScope.provenance == expectedProvenance &&
            replyThreadAccessFlightKey(currentScope, basePayload) == expectedKey
    }

    // Reserved for UNRECOVERABLE scenarios only (task #562 boundary):
    // permission/visibility loss, epoch/server switches, identity proof
    // failures. Ordinary realtime traffic warms the cache with the live
    // store's merged snapshot instead (see applyRealtimeEvent). Narrow by
    // design: removes exactly one thread's candidate; never touches durable
    // stores, the message resolver, or the active screen's in-memory state.
    fun invalidateCachedPayload(payload: KuiklyThreadScreenPayload) {
        val threadId = payload.resolveThreadRouteIdentity().timelineChannelId ?: return
        val ownerScopeId = cachePayloadOwnerScopeId.value.takeIf { it.isNotBlank() } ?: return
        payloadCache.remove(cacheServerId.value, threadId, ownerScopeId)
    }

    internal fun invalidateCachedPayload(
        payload: KuiklyThreadScreenPayload,
        expectedProvenance: ReplyCacheProvenance,
    ): Boolean =
        synchronized(replyCacheProvenanceLock) {
            if (replyCacheProvenance.value != expectedProvenance) return@synchronized false
            val threadId =
                payload.resolveThreadRouteIdentity().timelineChannelId
                    ?: return@synchronized false
            val ownerScopeId =
                expectedProvenance.threadPayloadOwnerScopeId
                    ?: return@synchronized false
            payloadCache.remove(expectedProvenance.serverId, threadId, ownerScopeId)
            true
        }

    suspend fun withCachedReplyParentCandidate(basePayload: KuiklyThreadScreenPayload): KuiklyThreadScreenPayload {
        val context = captureReplyCacheProducerContext() ?: return basePayload
        return withCachedReplyParentCandidate(basePayload, context)
    }

    internal fun withCachedReplyParentCandidate(
        basePayload: KuiklyThreadScreenPayload,
        context: ReplyCacheProducerContext
    ): KuiklyThreadScreenPayload {
        if (basePayload.surfaceKind != KuiklyThreadSurfaceKind.ReplyThread) return basePayload
        if (basePayload.originMessage != null || basePayload.originPreview?.isNotBlank() == true) return basePayload
        val parentChannelId = basePayload.parentChannelId?.takeIf { it.isNotBlank() } ?: return basePayload
        val parentMessageId =
            basePayload.parentMessageId?.takeIf { it.isNotBlank() }
                ?: basePayload.originParentMessageId?.takeIf { it.isNotBlank() }
                ?: return basePayload
        if (!isCurrentReplyCacheProvenance(context.provenance)) return basePayload
        val session = context.session
        val messageStoreScopeId = context.provenance.messageStoreScopeId ?: return basePayload
        val parent =
            getCachedReplyParentMessage(
                messageStoreScopeId = messageStoreScopeId,
                parentChannelId = parentChannelId,
                parentMessageId = parentMessageId
            ) ?: return basePayload
        return basePayload.copy(
            originMessage = parent.toKuiklyThreadMessageItem(session.user.id),
            originParentMessageId = parentMessageId,
            originStatus = "loaded",
            originErrorCode = null
        )
    }

    suspend fun resolveReplyOrigin(
        basePayload: KuiklyThreadScreenPayload,
        expectedProvenance: ReplyCacheProvenance? = null
    ): SharedThreadOriginResolution {
        if (basePayload.surfaceKind != KuiklyThreadSurfaceKind.ReplyThread) {
            return SharedThreadOriginResolution.Missing
        }
        val parentChannelId =
            basePayload.parentChannelId?.takeIf { it.isNotBlank() }
                ?: return SharedThreadOriginResolution.Error("thread_parent_channel_missing")
        val parentMessageId =
            basePayload.parentMessageId?.takeIf { it.isNotBlank() }
                ?: basePayload.originParentMessageId?.takeIf { it.isNotBlank() }
                ?: return SharedThreadOriginResolution.Error("thread_parent_message_missing")
        val replyResolutionScope =
            readReplyResolutionScope()
                ?: return SharedThreadOriginResolution.Error("thread_parent_context_session_missing")
        if (expectedProvenance != null && replyResolutionScope.provenance != expectedProvenance) {
            return SharedThreadOriginResolution.Error("thread_reply_cache_provenance_stale")
        }
        val session = replyResolutionScope.session
        val selected = when (
            val resolution = resolveReplyParentMessageFromStore(
                parentChannelId = parentChannelId,
                parentMessageId = parentMessageId,
                messageStoreScopeId = replyResolutionScope.messageStoreScopeId,
                expectedProvenance = replyResolutionScope.provenance
            )
        ) {
            is SharedThreadMessageResolution.Loaded ->
                SharedThreadOriginResolution.Loaded(
                    resolution.message.toKuiklyThreadMessageItem(session.user.id)
                )
            SharedThreadMessageResolution.Missing -> SharedThreadOriginResolution.Missing
            is SharedThreadMessageResolution.Error -> SharedThreadOriginResolution.Error(resolution.code)
        }
        return if (isCurrentReplyCacheProvenance(replyResolutionScope.provenance)) {
            selected
        } else {
            SharedThreadOriginResolution.Error("thread_reply_cache_provenance_stale")
        }
    }

    fun rememberReplyParentCandidate(
        provenance: ReplyCacheProvenance,
        serverId: String?,
        parentChannelId: String?,
        parentMessage: KuiklyThreadMessageItem,
        threadChannelId: String?
    ): Boolean {
        val channelId = parentChannelId?.takeIf { it.isNotBlank() }?.let(::ChannelId) ?: return false
        if (parentMessage.id.isBlank()) return false
        val exactServerId = serverId?.takeIf { it.isNotBlank() } ?: provenance.serverId.takeIf { it.isNotBlank() }
        if (exactServerId == null || exactServerId != provenance.serverId) return false
        val projectedParent =
            if (parentMessage.needsReplyOriginContextHydration()) {
                null
            } else {
                parentMessage.toMessage(
                    threadId = ThreadId(channelId.value),
                    channelId = channelId,
                    diagnostics = diagnosticsLogger,
                ) ?: return false
            }
        val accepted =
            synchronized(replyCacheProvenanceLock) {
                if (replyCacheProvenance.value != provenance) return@synchronized false
                threadChannelId?.takeIf { it.isNotBlank() }?.let { resolvedThreadChannelId ->
                    cacheReplyThreadResolution(
                        namespaceId = provenance.namespaceId,
                        parentChannelId = channelId.value,
                        parentMessageId = parentMessage.id,
                        threadChannelId = resolvedThreadChannelId
                    )
                }
                projectedParent?.let { parent ->
                    val scopeId =
                        provenance.messageStoreScopeId
                            ?: provenance.serverId.takeIf { messageStore == null && it.isNotBlank() }
                            ?: return@synchronized false
                    messageResolver.upsertScoped(scopeId = scopeId, channelId = channelId, message = parent)
                }
                true
            }
        if (!accepted) return false
        diagnosticsLogger(
            "thread_parent_context_store_seed parentChannelId=${channelId.value} " +
                "parentMessageId=${parentMessage.id} threadChannelId=${threadChannelId.orEmpty()}"
        )
        return true
    }

    suspend fun withConversationAccessFallback(basePayload: KuiklyThreadScreenPayload): KuiklyThreadScreenPayload {
        val session = readSession() ?: return basePayload
        return basePayload.withConversationAccessFallback(resolveConversationAccessFallback(basePayload, session))
    }

    suspend fun withKnownMarkdownChannelNames(payload: KuiklyThreadScreenPayload): KuiklyThreadScreenPayload {
        val session = readSession() ?: return payload
        return payload.withKnownMarkdownChannelNames(session)
    }

    suspend fun homeConversationContextResolution(
        payload: KuiklyThreadScreenPayload
    ): ThreadHomeContextResolution {
        val session = readSession() ?: return ThreadHomeContextResolution(payload = payload)
        val home = currentHomeSnapshot(session) ?: return ThreadHomeContextResolution(payload = payload)
        val accessFallback = resolveConversationAccessFallback(payload, home)
        return ThreadHomeContextResolution(
            payload =
                payload
                    .withConversationAccessFallback(accessFallback)
                    .withKnownMarkdownChannelNames(home),
            accessCandidate = accessFallback
        )
    }

    /**
     * Reply write authority belongs to the exact parent Channel/DM. Resolve it independently from
     * the full Home aggregate so auxiliary unread enrichment cannot consume the initial-access
     * timeout after the canonical conversation list is already available.
     */
    suspend fun initialConversationAccessResolution(
        payload: KuiklyThreadScreenPayload
    ): ThreadHomeContextResolution =
        if (payload.surfaceKind == KuiklyThreadSurfaceKind.ReplyThread) {
            replyParentConversationAccessResolution(payload)
        } else {
            homeConversationContextResolution(payload)
        }

    private suspend fun replyParentConversationAccessResolution(
        payload: KuiklyThreadScreenPayload
    ): ThreadHomeContextResolution {
        val (parentId, parentKind) =
            payload.replyParentAccessIdentityOrNull()
                ?: return ThreadHomeContextResolution(
                    payload = payload,
                    requiresBackgroundHomeContextHydration = true,
                )
        val resolutionScope =
            readReplyResolutionScope()
                ?: return ThreadHomeContextResolution(
                    payload = payload,
                    requiresBackgroundHomeContextHydration = true,
                )
        val serverId =
            resolutionScope.provenance.serverId
                .takeIf { it.isNotBlank() }
                ?.let(::ServerId)
                ?: return ThreadHomeContextResolution(
                    payload = payload,
                    requiresBackgroundHomeContextHydration = true,
                )

        homeSnapshotProvider?.invoke(serverId)
            ?.takeIf { snapshot ->
                snapshot.server.id == serverId &&
                    snapshot.currentMember.id.value == resolutionScope.provenance.principalId
            }
            ?.let { home ->
                val candidate =
                    payload.exactReplyParentAccessCandidate(
                        serverId = serverId,
                        parentId = parentId,
                        parentKind = parentKind,
                        conversations = home.channels + home.directMessages,
                    ) ?: return@let
                val ownerCurrent = replyAccessResolutionOwnerStillCurrent(resolutionScope)
                return ThreadHomeContextResolution(
                    payload = payload.withKnownMarkdownChannelNames(home),
                    accessCandidate = candidate,
                    ownerCurrent = ownerCurrent,
                )
            }

        val repository = channelRepository
        val conversations: List<HomeConversationRow> =
            if (repository == null) {
                emptyList()
            } else {
                try {
                    when (parentKind) {
                        ConversationKind.Channel,
                        ConversationKind.Joint ->
                            (repository.getChannels(serverId) as? DataResult.Content)
                                ?.data
                                ?.takeIf { snapshot -> snapshot.server.id == serverId }
                                ?.channels
                                .orEmpty()
                        ConversationKind.DirectMessage ->
                            (repository.getDirectMessages(serverId) as? DataResult.Content)
                                ?.data
                                ?.takeIf { snapshot -> snapshot.server.id == serverId }
                                ?.directMessages
                                .orEmpty()
                        ConversationKind.Thread,
                        ConversationKind.Inbox,
                        ConversationKind.System -> emptyList()
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    emptyList()
                }
            }
        val ownerCurrent = replyAccessResolutionOwnerStillCurrent(resolutionScope)
        val candidate =
            payload.exactReplyParentAccessCandidate(
                serverId = serverId,
                parentId = parentId,
                parentKind = parentKind,
                conversations = conversations,
            )
        diagnosticsLogger(
            "thread_initial_parent_access source=conversation_list serverId=${serverId.value} " +
                "parentId=$parentId parentKind=${parentKind.name} candidate=${candidate != null} " +
                "ownerCurrent=$ownerCurrent"
        )
        return ThreadHomeContextResolution(
            payload = payload,
            accessCandidate = candidate,
            ownerCurrent = ownerCurrent,
            requiresBackgroundHomeContextHydration = true,
        )
    }

    private suspend fun replyAccessResolutionOwnerStillCurrent(
        expected: ReplyResolutionScope
    ): Boolean {
        val current = readReplyResolutionScope() ?: return false
        // A transparent token rotation (same binding/server/selection) keeps the owner; any
        // binding, principal, or server change still fails closed (task #2606).
        return current.provenance.isCurrentOrSuccessorForLocalSend(expected.provenance) &&
            isCurrentReplyCacheProvenanceForLocalSend(expected.provenance)
    }

    private fun KuiklyThreadScreenPayload.exactReplyParentAccessCandidate(
        serverId: ServerId,
        parentId: String,
        parentKind: ConversationKind,
        conversations: List<HomeConversationRow>,
    ): ConversationAccessCandidate? =
        conversations
            .asSequence()
            .mapNotNull { conversation ->
                conversation.toSharedConversationDirectoryProjection(serverId)
            }
            .firstOrNull { projection ->
                projection.canonicalChannelId == parentId &&
                    projection.conversationKind == parentKind
            }
            ?.toConversationAccessCandidate()
            ?.takeIf(::acceptsInitialConversationAccessCandidate)

    suspend fun withHomeConversationContext(payload: KuiklyThreadScreenPayload): KuiklyThreadScreenPayload =
        homeConversationContextResolution(payload).payload

    private fun cachedReplyThreadStreamId(
        namespaceId: String?,
        parentChannelId: String,
        parentMessageId: String
    ): String? {
        namespaceId ?: return null
        val key = ReplyThreadResolveKey(namespaceId, parentChannelId, parentMessageId)
        return replyThreadResolveCache[key]
            ?: messageStore?.getReplyThreadStream(namespaceId, parentChannelId, parentMessageId)
    }

    private fun KuiklyThreadScreenPayload.matchesCachedReplyThreadStreamResolution(
        namespaceId: String?
    ): Boolean {
        val parentChannel = parentChannelId?.takeIf { it.isNotBlank() } ?: return false
        val parentMessage =
            parentMessageId?.takeIf { it.isNotBlank() }
                ?: originParentMessageId?.takeIf { it.isNotBlank() }
                ?: return false
        val routeStream =
            streamChannelId?.takeIf { it.isNotBlank() }
                ?: sourceThreadId?.takeIf { it.isNotBlank() }
                ?: return false
        return cachedReplyThreadStreamId(namespaceId, parentChannel, parentMessage) == routeStream
    }

    private fun cachedMarkdownThreadRefTarget(
        serverId: String,
        ownerScopeId: String,
        parentChannelId: String,
        threadShortId: String
    ): MarkdownThreadRefTarget? {
        val shortId = threadShortId.trim().lowercase()
        if (parentChannelId.isBlank() || shortId.isBlank()) return null
        val matches =
            payloadCache.values(serverId, ownerScopeId)
                .mapNotNull { payload ->
                    val parentMessageId =
                        payload.parentMessageId?.takeIf { it.isNotBlank() }
                            ?: payload.originParentMessageId?.takeIf { it.isNotBlank() }
                            ?: return@mapNotNull null
                    if (
                        payload.parentChannelId != parentChannelId ||
                        !parentMessageId.lowercase().startsWith(shortId)
                    ) {
                        return@mapNotNull null
                    }
                    MarkdownThreadRefTarget(
                        parentMessageId = parentMessageId,
                        threadChannelId =
                            payload.streamChannelId?.takeIf { it.isNotBlank() }
                                ?: payload.sourceThreadId?.takeIf { it.isNotBlank() }
                    )
                }
                .distinctBy { it.parentMessageId }
        return matches.singleOrNull()
    }

    private fun cacheReplyThreadResolution(
        provenance: ReplyCacheProvenance,
        parentChannelId: String,
        parentMessageId: String,
        threadChannelId: String
    ): Boolean =
        synchronized(replyCacheProvenanceLock) {
            if (replyCacheProvenance.value != provenance) return@synchronized false
            cacheReplyThreadResolution(
                provenance.namespaceId,
                parentChannelId,
                parentMessageId,
                threadChannelId
            )
            true
        }

    private fun cacheReplyThreadResolution(
        provenance: ReplyCacheProvenance,
        payload: KuiklyThreadScreenPayload
    ): Boolean {
        val parentChannelId = payload.parentChannelId?.takeIf { it.isNotBlank() } ?: return true
        val parentMessageId =
            payload.parentMessageId?.takeIf { it.isNotBlank() }
                ?: payload.originParentMessageId?.takeIf { it.isNotBlank() }
                ?: return true
        val threadChannelId =
            payload.streamChannelId?.takeIf { it.isNotBlank() }
                ?: payload.sourceThreadId?.takeIf { it.isNotBlank() }
                ?: return true
        return cacheReplyThreadResolution(
            provenance,
            parentChannelId,
            parentMessageId,
            threadChannelId
        )
    }

    private fun cacheReplyThreadResolution(
        namespaceId: String?,
        parentChannelId: String,
        parentMessageId: String,
        threadChannelId: String
    ) {
        namespaceId ?: return
        if (parentChannelId.isBlank() || parentMessageId.isBlank() || threadChannelId.isBlank()) return
        if (parentChannelId == threadChannelId) return
        replyThreadResolveCache[ReplyThreadResolveKey(namespaceId, parentChannelId, parentMessageId)] = threadChannelId
        messageStore?.upsertReplyThreadStream(namespaceId, parentChannelId, parentMessageId, threadChannelId)
    }

    suspend fun joinChannel(channelId: ChannelId) =
        channelRepository?.joinChannel(channelId)
            ?: DataResult.Error(ScreenError("channel_repository_unavailable", retryable = false))

    suspend fun updateChannelProfile(
        channelId: ChannelId,
        name: String,
        description: String
    ): DataResult<ChannelProfileUpdateResult> =
        channelRepository?.updateChannelProfile(
            ChannelProfileUpdateRequest(
                channelId = channelId,
                name = name,
                description = description
            )
        ) ?: DataResult.Error(ScreenError("channel_repository_unavailable", retryable = false))

    suspend fun publishChannelProfileUpdate(result: ChannelProfileUpdateResult) {
        channelProfileUpdateSink(result)
    }

    suspend fun cacheCanonicalPayload(
        payload: KuiklyThreadScreenPayload,
        expectedOwnerScopeId: String? = null,
        expectedProvenance: ReplyCacheProvenance? = null
    ): KuiklyThreadScreenPayload {
        val context = captureReplyCacheProducerContext() ?: return payload
        val ownerScopeId = context.provenance.threadPayloadOwnerScopeId ?: return payload
        if (expectedOwnerScopeId != null && expectedOwnerScopeId != ownerScopeId) return payload
        if (expectedProvenance != null && expectedProvenance != context.provenance) return payload
        cachePayload(
            payload = payload,
            serverId = context.provenance.serverId,
            ownerScopeId = ownerScopeId,
            expectedProvenance = expectedProvenance
        )
        return payload
    }

    suspend fun cacheLocalSendFailure(
        basePayload: KuiklyThreadScreenPayload,
        localMessage: KuiklyThreadMessageItem,
        errorCode: String?,
        errorMessage: String?,
        expectedOwnerScopeId: String? = null,
        expectedProvenance: ReplyCacheProvenance? = null
    ): KuiklyThreadScreenPayload {
        val failedPayload =
            basePayload
                .withLocalSendStarted(localMessage)
                .withLocalSendFailure(localMessage.id, errorCode, errorMessage)
        cacheCanonicalPayload(failedPayload, expectedOwnerScopeId, expectedProvenance)
        return failedPayload
    }

    suspend fun cacheLocalSendSuccess(
        basePayload: KuiklyThreadScreenPayload,
        localMessage: KuiklyThreadMessageItem,
        sentPage: ThreadMessagesPage,
        expectedOwnerScopeId: String? = null,
        expectedProvenance: ReplyCacheProvenance? = null
    ): KuiklyThreadScreenPayload {
        if (sentPage.messages.isEmpty()) return basePayload
        val context = captureReplyCacheProducerContext() ?: return basePayload
        val ownerScopeId = context.provenance.threadPayloadOwnerScopeId ?: return basePayload
        if (expectedOwnerScopeId != null && expectedOwnerScopeId != ownerScopeId) return basePayload
        if (expectedProvenance != null && expectedProvenance != context.provenance) return basePayload
        val session = context.session
        val threadId = basePayload.resolveThreadRouteIdentity().timelineChannelId
        val latestCachedPayload =
            threadId
                ?.let { cachedThreadId ->
                    cachedPayload(
                        threadId = cachedThreadId,
                        ownerScopeId = ownerScopeId,
                        serverId = context.provenance.serverId,
                        principalId = context.provenance.principalId
                    )
                }
                ?.takeIf { cached -> cached.isUsableCachedPayloadForRoute(basePayload) }
                ?.withRouteContext(basePayload)
        // Concurrent sends complete independently. Always fold the next
        // canonical response into the latest cached timeline instead of the
        // send-start snapshot, otherwise the later cache write can erase a
        // sibling send that completed while this request was in flight.
        val pendingPayload = (latestCachedPayload ?: basePayload).withLocalSendStarted(localMessage)
        val appended =
            appendSharedThreadSentMessageFromPage(
                basePayload = pendingPayload,
                page = sentPage,
                session = session,
                trustedReplyThreadChannelId = localMessage.pendingSendContext?.streamChannelId,
                diagnostics = diagnosticsLogger,
            )
        val replaced =
            appended
                .withoutLocalSendMessage(localMessage.id)
                .preserveStableTimelineFrom(pendingPayload)
        return cacheCanonicalPayload(replaced, ownerScopeId, expectedProvenance)
    }

    suspend fun commitAgentCreateActionCard(
        messageId: String,
        card: ThreadActionCard
    ): DataResult<ThreadActionCard> {
        return commitAgentCreateActionCardInternal(messageId, card)
    }

    /**
     * Task #43: action-card create-agent path that goes through the shared
     * [SharedCreateAgentCoordinator]. The dialog collects a full
     * [SharedCreateAgentDialogSubmit] (name, description, machineId,
     * runtimeConfig, external); the coordinator owns the body construction
     * and the API call. An allowlisted zero-side-effect runtime-form rejection
     * may refresh the canonical form, revalidate the original input, and retry
     * once with the fresh ref. Changed input or a second rejection requires
     * explicit confirmation. The action card is just the metadata that gets
     * marked executed on success.
     *
     * Replaces the legacy `commitAgentCreateActionCard(messageId, card)`
     * which only used name/description/machineId from the action card.
     */
    suspend fun commitAgentCreateActionCardFromDialog(
        messageId: String,
        card: ThreadActionCard,
        submit: SharedCreateAgentDialogSubmit,
        formStore: SharedCreateAgentFormStore?,
        serverId: String?
    ): DataResult<ThreadActionCard> {
        if (!card.type.equals("agent:create", ignoreCase = true)) {
            return DataResult.Error(ScreenError(code = "action_card_unsupported", retryable = false))
        }
        val actionsApi = actionCardsApiClient
            ?: return DataResult.Error(ScreenError(code = "action_card_actions_api_missing", retryable = false))
        val coordinator = createAgentCoordinator
            ?: return DataResult.Error(ScreenError(code = "action_card_coordinator_missing", retryable = false))

        val created =
            coordinator
                .createAgent(submit.toCreateAgentRequest())
                .recoverRejectedCreateAgentFormOrRetry(formStore, serverId, submit) { retrySubmit ->
                    coordinator.createAgent(retrySubmit.toCreateAgentRequest())
                }
        return when (created) {
            is DataResult.Content -> {
                val resultName = created.data.name.takeIf { it.isNotBlank() } ?: submit.name
                val executedCard = card.withExecutedAgent(id = created.data.id, name = resultName)
                markThreadActionCardExecuted(
                    actionsApi = actionsApi,
                    messageId = messageId,
                    body = buildMarkAgentActionExecutedBody(agentId = created.data.id, agentName = resultName),
                    executedCard = executedCard
                )
            }
            is DataResult.Empty ->
                DataResult.Error(ScreenError(code = "agent_create_empty", retryable = true))
            is DataResult.Error -> created
            is DataResult.NoPermission -> created
        }
    }

    suspend fun commitThreadActionCard(
        payload: KuiklyThreadScreenPayload,
        messageId: String,
        card: ThreadActionCard
    ): DataResult<ThreadActionCard> {
        return when {
            card.type.equals("agent:create", ignoreCase = true) ->
                DataResult.Error(ScreenError(code = "action_card_agent_create_dialog_required", retryable = false))
            card.type.equals("channel:create", ignoreCase = true) ->
                commitChannelCreateActionCard(messageId, card)
            card.type.equals("channel:add_member", ignoreCase = true) ->
                commitChannelAddMembersActionCard(payload, messageId, card)
            card.type.equals("integration:approve_agent_login", ignoreCase = true) ->
                executeInlineThreadActionCard(messageId, card)
            card.isGenericInlineCommitAction() ->
                executeInlineThreadActionCard(messageId, card)
            else ->
                DataResult.Error(ScreenError(code = "action_card_unsupported", retryable = false))
        }
    }

    private suspend fun executeInlineThreadActionCard(
        messageId: String,
        card: ThreadActionCard
    ): DataResult<ThreadActionCard> {
        val actionsApi =
            actionCardsApiClient
                ?: return DataResult.Error(ScreenError(code = "action_card_actions_api_missing", retryable = false))
        return when (val result = actionsApi.executeAction(MessageId(messageId), buildExecuteActionBody(expectedState = "prepared"))) {
            is DataResult.Content ->
                DataResult.Content(
                    result.data.actionCard
                        ?: if (card.type.equals("integration:approve_agent_login", ignoreCase = true)) {
                            card.copy(
                                state = "executed",
                                resultKind = "agent-integration-login",
                                resultId = card.agentId,
                                resultName = card.clientName,
                                executedByUserName = card.executedByUserName
                            )
                        } else {
                            // The canonical execute response normally carries
                            // the updated card. Keep a truthful terminal state
                            // if an older compatible server only returns the
                            // message id; never invent a type-specific result.
                            card.copy(state = "executed")
                        }
                )
            is DataResult.Empty ->
                DataResult.Error(ScreenError(code = "action_card_execute_empty", retryable = true))
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    private suspend fun commitAgentCreateActionCardInternal(
        messageId: String,
        card: ThreadActionCard
    ): DataResult<ThreadActionCard> {
        if (!card.type.equals("agent:create", ignoreCase = true)) {
            return DataResult.Error(ScreenError(code = "action_card_unsupported", retryable = false))
        }
        val agentName = card.name.trim()
        if (agentName.isBlank()) {
            return DataResult.Error(ScreenError(code = "action_card_agent_name_missing", retryable = false))
        }
        val agentsApi = agentsApiClient
            ?: return DataResult.Error(ScreenError(code = "action_card_agents_api_missing", retryable = false))
        val actionsApi = actionCardsApiClient
            ?: return DataResult.Error(ScreenError(code = "action_card_actions_api_missing", retryable = false))

        return when (
            val created =
                agentsApi.createAgent(
                    buildCreateAgentBody(
                        CreateAgentBody(
                            name = agentName.removePrefix("@"),
                            description = card.description?.takeIf { it.isNotBlank() },
                            machineId = card.requiredComputer?.takeIf { it.isNotBlank() }
                                ?: card.suggestedComputer?.takeIf { it.isNotBlank() }
                        )
                    )
                )
        ) {
            is DataResult.Content -> {
                val resultName = created.data.name.takeIf { it.isNotBlank() } ?: agentName.removePrefix("@")
                val executedCard = card.withExecutedAgent(id = created.data.id, name = resultName)
                markThreadActionCardExecuted(
                    actionsApi = actionsApi,
                    messageId = messageId,
                    body = buildMarkAgentActionExecutedBody(agentId = created.data.id, agentName = resultName),
                    executedCard = executedCard
                )
            }
            is DataResult.Empty ->
                DataResult.Error(ScreenError(code = "agent_create_empty", retryable = true))
            is DataResult.Error -> created
            is DataResult.NoPermission -> created
        }
    }

    private suspend fun commitChannelCreateActionCard(
        messageId: String,
        card: ThreadActionCard
    ): DataResult<ThreadActionCard> {
        if (!card.type.equals("channel:create", ignoreCase = true)) {
            return DataResult.Error(ScreenError(code = "action_card_unsupported", retryable = false))
        }
        val channelName = card.name.trim()
        if (channelName.isBlank()) {
            return DataResult.Error(ScreenError(code = "action_card_channel_name_missing", retryable = false))
        }
        val repository =
            channelRepository
                ?: return DataResult.Error(ScreenError(code = "action_card_channel_repository_missing", retryable = false))
        val actionsApi =
            actionCardsApiClient
                ?: return DataResult.Error(ScreenError(code = "action_card_actions_api_missing", retryable = false))
        return when (
            val created =
                repository.createChannel(
                    CreateChannelRequest(
                        name = channelName,
                        description = card.description?.trim()?.takeIf { it.isNotBlank() },
                        visibility = card.visibility?.trim()?.takeIf { it.isNotBlank() } ?: "public",
                        agentMemberIds = card.initialAgents.cleanActionCardIds(),
                        userMemberIds = card.initialHumans.cleanActionCardIds()
                    )
                )
        ) {
            is DataResult.Content -> {
                val channel = created.data.channel
                val createdChannelId = channel.channelId?.value ?: channel.threadId.value
                val createdChannelName = channel.name.takeIf { it.isNotBlank() } ?: channelName
                val executedCard = card.withExecutedChannel(id = createdChannelId, name = createdChannelName)
                markThreadActionCardExecuted(
                    actionsApi = actionsApi,
                    messageId = messageId,
                    body =
                        buildMarkChannelActionExecutedBody(
                            channelId = createdChannelId,
                            channelName = createdChannelName
                        ),
                    executedCard = executedCard
                )
            }
            is DataResult.Empty ->
                DataResult.Error(ScreenError(code = "channel_create_empty", retryable = true))
            is DataResult.Error -> created
            is DataResult.NoPermission -> created
        }
    }

    private suspend fun commitChannelAddMembersActionCard(
        payload: KuiklyThreadScreenPayload,
        messageId: String,
        card: ThreadActionCard
    ): DataResult<ThreadActionCard> {
        if (!card.type.equals("channel:add_member", ignoreCase = true)) {
            return DataResult.Error(ScreenError(code = "action_card_unsupported", retryable = false))
        }
        val targetChannelId =
            card.channel?.trim()?.takeIf { it.isNotBlank() }
                ?: payload.sourceThreadId?.takeIf { it.isNotBlank() }
                ?: return DataResult.Error(ScreenError(code = "action_card_channel_missing", retryable = false))
        val humanIds = card.humans.cleanActionCardIds()
        val agentIds = card.agents.cleanActionCardIds()
        if (humanIds.isEmpty() && agentIds.isEmpty()) {
            return DataResult.Error(ScreenError(code = "action_card_members_missing", retryable = false))
        }
        val repository =
            memberRepository
                ?: return DataResult.Error(ScreenError(code = "action_card_member_repository_missing", retryable = false))
        val actionsApi =
            actionCardsApiClient
                ?: return DataResult.Error(ScreenError(code = "action_card_actions_api_missing", retryable = false))
        val channelId = ChannelId(targetChannelId)
        val serverId = readSession()?.currentServerId?.takeIf { it.isNotBlank() }?.let(::ServerId)

        // task #352 (Web #7209): ONE atomic batch instead of per-member loops. The
        // server dedupes and validates the whole batch, rolling back on any failure,
        // so there is no partial success and we never accumulate a half-applied set
        // here. `alreadyMembers`-only is a success (idempotent re-send).
        val batchRequest =
            AddChannelMembersBatchRequest(
                channelId = channelId,
                userIds = humanIds.map(::MemberId),
                agentIds = agentIds.map(::MemberId)
            )
        val batchResult =
            if (serverMembersStore != null && serverId != null) {
                serverMembersStore.addChannelMembersBatch(serverId, batchRequest, DataRequestPriority.Foreground)
            } else {
                repository.addChannelMembersBatch(DataRequestPriority.Foreground, batchRequest)
            }
        val addedHumans: List<String>
        val addedAgents: List<String>
        when (batchResult) {
            is DataResult.Content -> {
                addedHumans = batchResult.data.addedUserIds
                addedAgents = batchResult.data.addedAgentIds
            }
            is DataResult.Empty -> return DataResult.Empty(batchResult.reason)
            is DataResult.Error -> return batchResult
            is DataResult.NoPermission -> return batchResult
        }
        // `added` may be empty when every requested member was ALREADY present: the
        // server contract defines that as an idempotent SUCCESS (added:[] with the
        // ids under alreadyMembers), so we must NOT fail the action card here. The
        // card still advances to executed; only the recorded added-ids stay empty.
        val channelName =
            payload.title.takeIf { targetChannelId == payload.sourceThreadId && it.isNotBlank() }
                ?: card.resultChannelName?.takeIf { it.isNotBlank() }
                ?: targetChannelId
        val executedCard =
            card.withExecutedChannelMembers(
                channelId = targetChannelId,
                channelName = channelName,
                addedHumanIds = addedHumans,
                addedAgentIds = addedAgents
            )
        return markThreadActionCardExecuted(
            actionsApi = actionsApi,
            messageId = messageId,
            body =
                buildMarkChannelMembersActionExecutedBody(
                    channelId = targetChannelId,
                    channelName = channelName,
                    addedHumanIds = addedHumans,
                    addedAgentIds = addedAgents
                ),
            executedCard = executedCard
        )
    }

    private suspend fun markThreadActionCardExecuted(
        actionsApi: SlockIntegrationsActionsPushApiClient,
        messageId: String,
        body: String,
        executedCard: ThreadActionCard
    ): DataResult<ThreadActionCard> =
        when (val result = actionsApi.markActionExecuted(MessageId(messageId), body)) {
            is DataResult.Content -> DataResult.Content(executedCard)
            is DataResult.Empty ->
                DataResult.Error(ScreenError(code = "action_card_mark_executed_empty", retryable = true))
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }

    suspend fun fetchThreadPayload(
        basePayload: KuiklyThreadScreenPayload,
        persistResult: Boolean = true,
        reusePrewarm: Boolean = false,
        replaceReplyAccessFailure: Boolean = false
    ): DataResult<KuiklyThreadScreenPayload> =
        fetchThreadPayloadWithResolutionScope(
            basePayload = basePayload,
            persistResult = persistResult,
            reusePrewarm = reusePrewarm,
            replyResolutionScope = null,
            replaceReplyAccessFailure = replaceReplyAccessFailure
        )

    /**
     * Runs a visible Store refresh under the exact owner captured before the refresh began.
     * Re-sampling here could turn an A-owned Store request into a B-owned network request while
     * the surrounding Store generation still belongs to A.
     */
    internal suspend fun fetchThreadPayloadForContext(
        basePayload: KuiklyThreadScreenPayload,
        expectedContext: ReplyCacheProducerContext,
        persistResult: Boolean = true,
        reusePrewarm: Boolean = false,
        replaceReplyAccessFailure: Boolean = false,
    ): DataResult<KuiklyThreadScreenPayload> {
        if (!isCurrentReplyCacheProvenanceForLocalSend(expectedContext.provenance)) {
            return staleReplyCacheProvenanceResult()
        }
        return fetchThreadPayloadWithResolutionScope(
            basePayload = basePayload,
            persistResult = persistResult,
            reusePrewarm = reusePrewarm,
            replyResolutionScope =
                ReplyResolutionScope(
                    session = expectedContext.session,
                    provenance = expectedContext.provenance,
                    sharedMessageStore = messageStore != null,
                ),
            replaceReplyAccessFailure = replaceReplyAccessFailure,
        )
    }

    private suspend fun fetchThreadPayloadWithResolutionScope(
        basePayload: KuiklyThreadScreenPayload,
        persistResult: Boolean,
        reusePrewarm: Boolean,
        replyResolutionScope: ReplyResolutionScope?,
        replaceReplyAccessFailure: Boolean = false
    ): DataResult<KuiklyThreadScreenPayload> {
        val resolutionScope = replyResolutionScope ?: readReplyResolutionScope() ?: return authSessionRequiredResult()
        val session = resolutionScope.session
        val replyResolutionNamespaceId = resolutionScope.namespaceId
        val payloadOwnerScopeId =
            resolutionScope.provenance.threadPayloadOwnerScopeId
                ?: return staleReplyCacheProvenanceResult()
        val shouldFetchReplyByParent =
            conversationRepository != null &&
                basePayload.shouldFetchReplyThreadByParentPair() &&
                !basePayload.matchesCachedReplyThreadStreamResolution(replyResolutionNamespaceId)
        val resolvedBasePayload =
            if (shouldFetchReplyByParent) {
                basePayload
            } else {
                when (val resolved = resolveReplyThreadStream(basePayload, resolutionScope)) {
                    is DataResult.Content -> resolved.data
                    is DataResult.Empty -> return resolved
                    is DataResult.Error -> return resolved
                    is DataResult.NoPermission -> return resolved
                }
            }
        val threadId =
            resolvedBasePayload.sourceThreadId?.takeIf { it.isNotBlank() }
                ?: resolvedBasePayload.streamChannelId?.takeIf { it.isNotBlank() }
        var failedPrewarm = false
        if (reusePrewarm && threadId != null) {
            val serverId = session.currentServerId.orEmpty()
            if (payloadCache.promoteQueuedPrewarm(serverId, threadId, payloadOwnerScopeId)) {
                diagnosticsLogger?.invoke(
                    "thread_prewarm_promote threadId=$threadId from=background_queue to=foreground"
                )
                failedPrewarm = true
            } else {
                when (
                    val warmed =
                        payloadCache.joinOrConsumePrewarm(
                            serverId,
                            threadId,
                            payloadOwnerScopeId
                        )
                ) {
                    is DataResult.Content -> {
                        if (!cacheReplyThreadResolution(resolutionScope.provenance, warmed.data)) {
                            return staleReplyCacheProvenanceResult()
                        }
                        seedThreadAuthorProfiles(session.currentServerId, warmed.data, source = "prewarm")
                        return warmed
                    }
                    is DataResult.Empty,
                    is DataResult.Error,
                    is DataResult.NoPermission -> failedPrewarm = true
                    null -> Unit
                }
            }
        }
        suspend fun fetchCanonicalPayload(): DataResult<KuiklyThreadScreenPayload> =
            if (conversationRepository != null) {
                if (shouldFetchReplyByParent) {
                    fetchInitialReplyThreadPayloadFromRepository(
                        session = session,
                        basePayload = resolvedBasePayload,
                        replyResolutionScope = resolutionScope,
                        replaceReplyAccessFailure = replaceReplyAccessFailure
                    )
                } else {
                    val resolvedThreadId =
                        threadId
                            ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false))
                    fetchInitialThreadPayloadFromRepository(
                        session = session,
                        basePayload = resolvedBasePayload,
                        threadId = ThreadId(resolvedThreadId),
                        replyResolutionScope = resolutionScope
                    )
                }
            } else {
                val resolvedThreadId =
                    threadId
                        ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false))
                val response =
                    try {
                        val pageSize = threadPageSize(session.currentServerId)
                        diagnosticsLogger(
                            "thread_page_size source=initial_api server=${session.currentServerId.orEmpty()} " +
                                "pageSize=$pageSize"
                        )
                        api.channelMessages(resolvedThreadId, limit = pageSize)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        return DataResult.Error(ScreenError(code = "thread_network", retryable = true))
                    }
                val page =
                    when (response) {
                        is ThreadApiResult.Success -> response.data
                        is ThreadApiResult.Failure ->
                            return response.toThreadDataError("thread_network")
                    }
                DataResult.Content(
                    buildSharedThreadPayloadFromMessagesPage(
                        basePayload = resolvedBasePayload,
                        page = page,
                        session = session,
                        diagnostics = diagnosticsLogger,
                    )
                )
            }
        var canonicalRequestGeneration: SharedThreadFreshnessGeneration? = null
        suspend fun fetchCanonicalPayloadForCurrentGeneration(): DataResult<KuiklyThreadScreenPayload> {
            val resolvedThreadId = threadId ?: return fetchCanonicalPayload()
            while (true) {
                if (!isCurrentReplyCacheProvenanceForLocalSend(resolutionScope.provenance)) {
                    return staleReplyCacheProvenanceResult()
                }
                val generation =
                    payloadCache.captureGeneration(
                        session.currentServerId.orEmpty(),
                        resolvedThreadId,
                        payloadOwnerScopeId
                    )
                val result = fetchCanonicalPayload()
                if (payloadCache.freshnessLedger.isCurrent(generation)) {
                    canonicalRequestGeneration = generation
                    return result
                }
                if (!isCurrentReplyCacheProvenanceForLocalSend(resolutionScope.provenance)) {
                    return staleReplyCacheProvenanceResult()
                }
            }
        }
        val payloadResult =
            if (failedPrewarm && threadId != null) {
                payloadCache.takeOverFailedPrewarm(
                    serverId = session.currentServerId.orEmpty(),
                    threadId = threadId,
                    ownerScopeId = payloadOwnerScopeId,
                    fetch = {
                        if (!isCurrentReplyCacheProvenanceForLocalSend(resolutionScope.provenance)) {
                            staleReplyCacheProvenanceResult()
                        } else {
                            fetchCanonicalPayload()
                        }
                    }
                )
            } else {
                fetchCanonicalPayloadForCurrentGeneration()
            }
        val payload =
            when (payloadResult) {
                is DataResult.Content -> payloadResult.data
                is DataResult.Empty ->
                    if (resolvedBasePayload.surfaceKind == KuiklyThreadSurfaceKind.ReplyThread &&
                        payloadResult.reason == EmptyReason.NoMessages
                    ) {
                        resolvedBasePayload.toEmptyReplyThreadPayload()
                    } else {
                        return payloadResult
                    }
                is DataResult.Error -> return payloadResult
                is DataResult.NoPermission -> return payloadResult
            }
                .let { canonicalPayload ->
                    canonicalPayload.cacheThreadId()?.let { resolvedThreadId ->
                        payloadCache.applyReplyCountFacts(
                            serverId = session.currentServerId.orEmpty(),
                            threadId = resolvedThreadId,
                            environmentId = resolutionScope.provenance.environmentId,
                            principalId = session.user.id,
                            payload = canonicalPayload
                        )
                    } ?: canonicalPayload
                }
        if (!replyAccessResolutionOwnerStillCurrent(resolutionScope)) {
            return staleReplyCacheProvenanceResult()
        }
        val cacheProvenance =
            currentReplyCacheProvenanceForLocalSend(resolutionScope.provenance)
                ?: return staleReplyCacheProvenanceResult()
        if (!cacheReplyThreadResolution(cacheProvenance, payload)) {
            return staleReplyCacheProvenanceResult()
        }
        if (canonicalRequestGeneration != null) {
            payloadCache.recordCanonicalSuccess(canonicalRequestGeneration, payloadOwnerScopeId)
        }
        if (persistResult && canonicalRequestGeneration != null) {
            cachePayload(
                payload = payload,
                serverId = session.currentServerId.orEmpty(),
                expectedGeneration = canonicalRequestGeneration,
                ownerScopeId = payloadOwnerScopeId
            )
        }
        seedThreadAuthorProfiles(session.currentServerId, payload, source = "initial")
        return DataResult.Content(payload)
    }

    suspend fun prewarmReplyThread(
        marker: ActivityReplyThreadPrewarmMarker
    ): DataResult<Unit> {
        val key = marker.key
        if (
            key.serverId.isBlank() ||
            key.parentChannelId.isBlank() ||
            key.parentMessageId.isBlank() ||
            key.threadChannelId.isBlank()
        ) {
            return DataResult.Error(ScreenError(code = "thread_prewarm_identity_missing", retryable = false))
        }
        val replyResolutionScope = readReplyResolutionScope() ?: return authSessionRequiredResult()
        val session = replyResolutionScope.session
        val payloadOwnerScopeId =
            replyResolutionScope.provenance.threadPayloadOwnerScopeId
                ?: return staleReplyCacheProvenanceResult()
        val selection = selectedServerStore
            ?: return DataResult.Error(ScreenError(code = "thread_prewarm_selection_unavailable", retryable = false))
        if (!selection.isSelected(key.serverId)) {
            return DataResult.Error(ScreenError(code = "thread_prewarm_server_changed", retryable = false))
        }
        val basePayload =
            KuiklyThreadScreenPayload(
                status = "loading",
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                channelKind = KuiklyThreadSurfaceKind.ReplyThread,
                sourceThreadId = key.threadChannelId,
                streamChannelId = key.threadChannelId,
                parentChannelId = key.parentChannelId,
                parentMessageId = key.parentMessageId,
                originParentMessageId = key.parentMessageId
            )
        val result =
            payloadCache.prewarm(
                serverId = key.serverId,
                threadId = key.threadChannelId,
                ownerScopeId = payloadOwnerScopeId
            ) {
                val fetched =
                    fetchInitialReplyThreadPayloadFromRepository(
                        session = session,
                        basePayload = basePayload,
                        requestPriority = DataRequestPriority.Background,
                        replyResolutionScope = replyResolutionScope
                    )
                val payload =
                    when (fetched) {
                        is DataResult.Content -> fetched.data
                        is DataResult.Empty ->
                            if (fetched.reason == EmptyReason.NoMessages) {
                                basePayload.toEmptyReplyThreadPayload()
                            } else {
                                return@prewarm fetched
                            }
                        is DataResult.Error -> return@prewarm fetched
                        is DataResult.NoPermission -> return@prewarm fetched
                    }
                DataResult.Content(payload)
            }
        (result as? DataResult.Content)?.data?.let { payload ->
            if (!cacheReplyThreadResolution(replyResolutionScope.provenance, payload)) {
                return DataResult.Error(
                    ScreenError(code = "thread_reply_access_authority_changed", retryable = true)
                )
            }
        }
        return when (result) {
            is DataResult.Content -> DataResult.Content(Unit)
            is DataResult.Empty -> result
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    private suspend fun resolveReplyThreadStream(
        basePayload: KuiklyThreadScreenPayload,
        replyResolutionScope: ReplyResolutionScope
    ): DataResult<KuiklyThreadScreenPayload> {
        val repository = conversationRepository ?: return DataResult.Content(basePayload)
        if (!basePayload.needsReplyThreadStreamResolution()) {
            return DataResult.Content(basePayload)
        }
        return createOrOpenReplyThreadStream(basePayload, repository, replyResolutionScope)
    }

    private suspend fun createOrOpenReplyThreadStream(
        basePayload: KuiklyThreadScreenPayload,
        repository: ConversationRepository? = conversationRepository,
        replyResolutionScope: ReplyResolutionScope
    ): DataResult<KuiklyThreadScreenPayload> {
        val resolvedRepository =
            repository
                ?: return DataResult.Error(ScreenError(code = "thread_reply_network", retryable = true))
        val replyResolutionNamespaceId = replyResolutionScope.namespaceId
        val parentChannelId = basePayload.parentChannelId?.takeIf { it.isNotBlank() }
            ?: return DataResult.Content(basePayload)
        val parentMessageId = basePayload.parentMessageId?.takeIf { it.isNotBlank() }
            ?: basePayload.originParentMessageId?.takeIf { it.isNotBlank() }
            ?: return DataResult.Content(basePayload)
        cachedReplyThreadStreamId(replyResolutionNamespaceId, parentChannelId, parentMessageId)?.let { threadChannelId ->
            return DataResult.Content(
                basePayload.withResolvedReplyThreadAction(
                    ThreadActionResult(
                        threadChannelId = ChannelId(threadChannelId),
                        changed = false,
                        parentMessageId = MessageId(parentMessageId),
                        replyCount = basePayload.originMessage?.replyCount ?: 0
                    )
                )
            )
        }
        val actionResult =
            try {
                resolvedRepository.createOrOpenThread(ChannelId(parentChannelId), MessageId(parentMessageId))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return DataResult.Error(ScreenError(code = "thread_reply_network", retryable = true))
            }
        return when (actionResult) {
            is DataResult.Content -> {
                if (!cacheReplyThreadResolution(
                        replyResolutionScope.provenance,
                        parentChannelId,
                        parentMessageId,
                        actionResult.data.threadChannelId.value
                    )
                ) {
                    return DataResult.Error(
                        ScreenError(code = "thread_reply_access_authority_changed", retryable = true)
                    )
                }
                DataResult.Content(basePayload.withResolvedReplyThreadAction(actionResult.data))
            }
            is DataResult.Empty -> actionResult
            is DataResult.Error -> actionResult
            is DataResult.NoPermission -> actionResult
        }
    }

    suspend fun fetchWebLinkThreadPayload(
        basePayload: KuiklyThreadScreenPayload,
        target: SlockThreadWebLinkTarget
    ): DataResult<KuiklyThreadScreenPayload> {
        val messageId = target.messageId?.takeIf { it.isNotBlank() }
        if (messageId == null || conversationRepository == null) {
            return fetchThreadPayload(basePayload)
        }
        val resolutionScope =
            readReplyResolutionScope()
                ?: return authSessionRequiredResult()
        val session = resolutionScope.session
        val contextResult =
            fetchThreadPayloadFromRepository(
                session = session,
                expectedProvenance = resolutionScope.provenance,
                fallbackCode = "thread_weblink_context",
                call = { repository -> repository.getMessageContext(MessageId(messageId)) },
                transform = { snapshot -> snapshot.toKuiklyThreadPayload(basePayload, session.user.id) }
            )
        return when (contextResult) {
            is DataResult.Content -> contextResult
            is DataResult.Empty,
            is DataResult.Error,
            is DataResult.NoPermission -> fetchThreadPayload(basePayload)
        }
    }

    suspend fun enrichSavedStatus(payload: KuiklyThreadScreenPayload): KuiklyThreadScreenPayload {
        val context = captureReplyCacheProducerContext() ?: return payload
        return when (val result = enrichSavedStatus(payload, context)) {
            is DataResult.Content -> result.data
            is DataResult.Empty,
            is DataResult.Error,
            is DataResult.NoPermission -> payload
        }
    }

    /**
     * Enrich saved state using the producer's already-captured owner. The secondary
     * timeline must never re-sample the process-global session between the summary
     * request and this lookup: doing so can turn an A result into a B projection.
     */
    internal suspend fun enrichSavedStatus(
        payload: KuiklyThreadScreenPayload,
        expectedContext: ReplyCacheProducerContext,
    ): DataResult<KuiklyThreadScreenPayload> {
        if (!isCurrentReplyCacheProvenance(expectedContext.provenance)) {
            return staleReplyCacheProvenanceResult()
        }
        val serverId =
            expectedContext.provenance.serverId.takeIf { it.isNotBlank() }?.let(::ServerId)
                ?: return DataResult.Content(payload)
        val result = payload.withSavedStatus(serverId)
        if (!isCurrentReplyCacheProvenance(expectedContext.provenance)) {
            return staleReplyCacheProvenanceResult()
        }
        return DataResult.Content(result)
    }

    suspend fun fetchFollowedThreadPayload(
        basePayload: KuiklyThreadScreenPayload,
        onSummaries: (ThreadReplySummariesSnapshot, TimeSource.Monotonic.ValueTimeMark) -> Unit = { _, _ -> },
    ): DataResult<KuiklyThreadScreenPayload> {
        return fetchFollowedThreadPayloadForContext(
            basePayload = basePayload,
            expectedContext = null,
            onSummaries = onSummaries,
        )
    }

    internal suspend fun fetchFollowedThreadPayloadForContext(
        basePayload: KuiklyThreadScreenPayload,
        expectedContext: ReplyCacheProducerContext?,
        onSummaries: (ThreadReplySummariesSnapshot, TimeSource.Monotonic.ValueTimeMark) -> Unit = { _, _ -> },
    ): DataResult<KuiklyThreadScreenPayload> {
        if (expectedContext != null && !isCurrentReplyCacheProvenance(expectedContext.provenance)) {
            return staleReplyCacheProvenanceResult()
        }
        if (conversationRepository == null) return DataResult.Content(basePayload)
        if (basePayload.surfaceKind == KuiklyThreadSurfaceKind.ReplyThread) {
            return DataResult.Content(basePayload)
        }
        val context = expectedContext ?: captureReplyCacheProducerContext()
            ?: return authSessionRequiredResult()
        if (!isCurrentReplyCacheProvenance(context.provenance)) {
            return staleReplyCacheProvenanceResult()
        }
        val resolvedThreadId =
            basePayload.sourceThreadId?.takeIf { it.isNotBlank() }
                ?: basePayload.streamChannelId?.takeIf { it.isNotBlank() }
                ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false))
        val parentMessageIds = basePayload.messages.map { MessageId(it.id) }
        // Mark the instant the canonical summaries request is issued, not when
        // the response lands: facts recorded by realtime events that arrive
        // while this request is in flight must stay ahead of its (older)
        // server-side snapshot.
        val summariesRequestedAt = TimeSource.Monotonic.markNow()
        val result = conversationRepository
            .getThreadReplySummaries(
                threadId = ThreadId(resolvedThreadId),
                parentMessageIds = parentMessageIds
            )
        if (!isCurrentReplyCacheProvenance(context.provenance)) {
            return staleReplyCacheProvenanceResult()
        }
        return when (result) {
            is DataResult.Content -> {
                onSummaries(result.data, summariesRequestedAt)
                if (!isCurrentReplyCacheProvenance(context.provenance)) {
                    staleReplyCacheProvenanceResult()
                } else {
                    // `withReplySummaries` unconditionally overwrites each
                    // message's replyCount with the canonical snapshot. Realtime
                    // reply-count facts recorded after the request was issued
                    // (newer observedAt) must still win, so re-apply the fact
                    // ledger before publishing the payload.
                    val projected = basePayload.withReplySummaries(result.data)
                    DataResult.Content(
                        payloadCache.applyReplyCountFacts(
                            serverId = context.provenance.serverId,
                            threadId = resolvedThreadId,
                            environmentId = context.provenance.environmentId,
                            principalId = context.provenance.principalId,
                            payload = projected
                        )
                    )
                }
            }
            is DataResult.Empty -> result
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    /**
     * Linearizes the matching Store publication and reply-count fact write under
     * the same exact owner. `null` means owner rejection; `false` means the Store
     * declined a stale route; `true` means the owner-bound commit was admitted.
     */
    fun cacheReplyCountProjection(
        threadId: String,
        snapshot: ThreadReplySummariesSnapshot,
        observedAt: TimeSource.Monotonic.ValueTimeMark,
        serverId: String,
        principalId: String,
        expectedProvenance: ReplyCacheProvenance,
        commit: () -> Boolean,
    ): Boolean? {
        // The caller supplies the instant the canonical summaries request was
        // issued; the response reflects the server-side snapshot at request
        // time, so facts newer than that mark must not be overwritten.
        val facts =
            snapshot.threads.mapNotNull { summary ->
                val parentMessageId = summary.parentMessageId?.value ?: return@mapNotNull null
                parentMessageId to
                    SharedThreadReplyCountFact(
                        threadChannelId = (summary.threadChannelId ?: summary.channelId).value,
                        replyCount = summary.replyCount,
                        observedAt = observedAt
                    )
            }
        if (serverId != expectedProvenance.serverId || principalId != expectedProvenance.principalId) {
            return null
        }
        return synchronized(replyCacheProvenanceLock) {
            if (replyCacheProvenance.value != expectedProvenance) return@synchronized null
            if (!commit()) return@synchronized false
            if (facts.isNotEmpty()) {
                payloadCache.recordReplyCountFacts(
                    serverId = serverId,
                    threadId = threadId,
                    environmentId = expectedProvenance.environmentId,
                    principalId = principalId,
                    facts = facts
                )
            }
            true
        }
    }

    /**
     * Best-effort reply-count fact write for a realtime [ThreadUpdated] event.
     * Keeps the fact ledger fresh so a later canonical reprojection cannot
     * clobber a newer realtime reply count. Silently no-ops when the
     * provenance is unavailable or rotates mid-call.
     */
    fun recordReplyCountRealtimeFact(
        threadId: String,
        parentMessageId: String,
        fact: SharedThreadReplyCountFact,
    ) {
        val provenance = currentReplyCacheProvenance() ?: return
        synchronized(replyCacheProvenanceLock) {
            if (replyCacheProvenance.value != provenance) return@synchronized
            payloadCache.recordReplyCountFacts(
                serverId = provenance.serverId,
                threadId = threadId,
                environmentId = provenance.environmentId,
                principalId = provenance.principalId,
                facts = listOf(parentMessageId to fact)
            )
        }
    }

    /**
     * Outcome of an attempt to enrich the current thread payload with
     * fresh member / candidate data. The tri-state lets the caller
     * distinguish a real "no change" success from a transient failure
     * (network / repository exception, NoPermission, Error) so that
     * idempotent LoadMembers can decide whether to enter Loaded or
     * Error.
     */
    internal sealed class MembersEnrichOutcome {
        data class Updated(val payload: KuiklyThreadScreenPayload) : MembersEnrichOutcome()
        data object Unchanged : MembersEnrichOutcome()
        data class Failed(val reason: String) : MembersEnrichOutcome()
    }

    suspend fun enrichThreadMembers(payload: KuiklyThreadScreenPayload): MembersEnrichOutcome {
        val threadId = payload.sourceThreadId?.takeIf { it.isNotBlank() }
            ?: return MembersEnrichOutcome.Unchanged
        val serverId = readSession()?.currentServerId?.takeIf { it.isNotBlank() }?.let(::ServerId)
        val store = serverMembersStore
        val repository = memberRepository
        val channelMembers =
            try {
                if (store != null && serverId != null) {
                    store.loadChannelMembers(serverId, ChannelId(threadId), DataRequestPriority.Visible)
                } else {
                    repository?.getChannelMembers(DataRequestPriority.Visible, ChannelId(threadId))
                        ?: return MembersEnrichOutcome.Unchanged
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                return MembersEnrichOutcome.Failed("getChannelMembers threw: ${t::class.simpleName}")
            }
        if (channelMembers !is DataResult.Content) {
            // Reached only for Empty / NoPermission / Error. Treat
            // Empty as success-no-data (Loaded), NoPermission / Error
            // as transient failure so the next modal open can retry.
            return when (val result = channelMembers) {
                is DataResult.Empty -> MembersEnrichOutcome.Unchanged
                is DataResult.NoPermission ->
                    MembersEnrichOutcome.Failed("getChannelMembers: NoPermission")
                is DataResult.Error ->
                    MembersEnrichOutcome.Failed("getChannelMembers: Error(${result.error.code})")
                is DataResult.Content -> error("unreachable: filtered by !is DataResult.Content above")
            }
        }
        val serverMembers = serverId?.let { serverMembersStore?.currentServerMembers(it) }
        val members =
            channelMembers.data.members
                .distinctBy { it.memberKey }
        val memberKeys = members.map { it.memberKey }.toSet()
        val candidates =
            if (serverMembers == null) {
                payload.memberCandidates
            } else {
                serverMembers
                    .filterNot { it.memberKey in memberKeys }
                    .map { it.toKuiklyThreadMemberItem(serverId) }
            }
        return MembersEnrichOutcome.Updated(
            payload.copy(
                membersCount = members.size,
                members = members.map { it.toKuiklyThreadMemberItem(serverId) },
                memberCandidates = candidates,
                canAddMembers = channelMembers.data.canAddMembers
            )
        )
    }

    suspend fun addChannelMember(
        payload: KuiklyThreadScreenPayload,
        memberId: String,
        kind: String?
    ): DataResult<KuiklyThreadScreenPayload> {
        val repository =
            memberRepository
                ?: return DataResult.Error(ScreenError(code = "thread_member_repository_missing", retryable = false))
        val channelId =
            payload.sourceThreadId?.takeIf { it.isNotBlank() }?.let(::ChannelId)
                ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false))
        val memberKind =
            kind.strictMemberKindOrNull(
                source = "thread_add_member",
                contract = MemberKindWireContract.Projected,
            )
                ?: return DataResult.Error(ScreenError(code = "thread_member_kind_missing", retryable = false))
        val cleanMemberId =
            memberId.takeIf { it.isNotBlank() }?.let(::MemberId)
                ?: return DataResult.Error(ScreenError(code = "thread_member_id_missing", retryable = false))
        val serverId = readSession()?.currentServerId?.takeIf { it.isNotBlank() }?.let(::ServerId)
        val request =
            AddChannelMemberRequest(
                channelId = channelId,
                memberId = cleanMemberId,
                kind = memberKind
            )
        return when (
            val result =
                if (serverMembersStore != null && serverId != null) {
                    serverMembersStore.addChannelMember(
                        serverId = serverId,
                        request = request,
                        priority = DataRequestPriority.Foreground
                    )
                } else {
                    repository.addChannelMember(DataRequestPriority.Foreground, request)
                }
        ) {
            is DataResult.Content ->
                DataResult.Content(
                    refreshMembersAfterMutation(
                        payload.copy(addMemberStatus = "idle", addMemberErrorCode = null)
                    )
                )
            is DataResult.Empty -> DataResult.Empty(result.reason)
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    suspend fun markdownChannelLaunchPayload(
        currentPayload: KuiklyThreadScreenPayload,
        channelLabel: String,
        requiredKind: ConversationKind? = null
    ): KuiklyThreadScreenPayload? {
        val session = readSession() ?: return null
        val home = currentHomeSnapshot(session) ?: return null
        return buildMarkdownChannelLaunchPayload(
            currentPayload = currentPayload,
            home = home,
            channelLabel = channelLabel,
            requiredKind = requiredKind,
            knownMemberProfilesById =
                if (requiredKind == ConversationKind.DirectMessage) {
                    currentKnownMarkdownMemberProfilesById(home)
                } else {
                    emptyMap()
                }
        )
    }

    suspend fun markdownThreadRefLaunchPayload(
        provenance: ReplyCacheProvenance,
        currentPayload: KuiklyThreadScreenPayload,
        channelLabel: String,
        threadShortId: String?,
        requiredParentKind: ConversationKind? = null
    ): DataResult<KuiklyThreadScreenPayload> {
        val shortId =
            threadShortId
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return DataResult.Empty(EmptyReason.NoMessages)
        val resolutionScope =
            readReplyResolutionScope()
                ?: return authSessionRequiredResult()
        if (resolutionScope.provenance != provenance || !isCurrentReplyCacheProvenance(provenance)) {
            return staleReplyCacheProvenanceResult()
        }
        onMarkdownReplyCacheScopeCapturedForTest?.invoke()
        val session = resolutionScope.session
        val home =
            currentHomeSnapshot(session)
                ?: return DataResult.Empty(EmptyReason.NoChannels)
        val knownMemberProfilesById = currentKnownMarkdownMemberProfilesById(home)
        val channel =
            resolveMarkdownChannelConversation(
                home = home,
                channelLabel = channelLabel,
                requiredKind = requiredParentKind,
                knownMemberProfilesById = knownMemberProfilesById,
                currentPayload = currentPayload,
                threadShortId = shortId
            )
                ?: return DataResult.Empty(EmptyReason.NoChannels)
        val parentChannelId =
            channel.resolveThreadRouteIdentity().timelineChannelId
                ?: channel.messageStreamChannelId().value.takeIf { it.isNotBlank() }
                ?: return DataResult.Empty(EmptyReason.NoChannels)
        val localTarget =
            currentPayload.markdownThreadRefTarget(parentChannelId, shortId)
                ?: provenance.threadPayloadOwnerScopeId?.let { ownerScopeId ->
                    cachedMarkdownThreadRefTarget(
                        provenance.serverId,
                        ownerScopeId,
                        parentChannelId,
                        shortId
                    )
                }
        val target =
            localTarget
                ?: when (
                    val result =
                        fetchThreadSnapshotFromRepository(
                            fallbackCode = "thread_markdown_ref_context_network",
                            expectedProvenance = provenance,
                            call = { repository ->
                                repository.getMessageContext(MessageId(shortId), ChannelId(parentChannelId))
                            }
                        )
                ) {
                    is DataResult.Content -> result.data.markdownThreadRefTarget(parentChannelId, shortId)
                    is DataResult.Empty -> return result
                    is DataResult.Error -> return result
                    is DataResult.NoPermission -> return result
                }
                ?: return DataResult.Empty(EmptyReason.NoMessages)
        if (!isCurrentReplyCacheProvenance(provenance)) {
            return staleReplyCacheProvenanceResult()
        }
        val payload =
            buildMarkdownThreadRefLaunchPayload(
                currentPayload = currentPayload,
                home = home,
                channelLabel = channelLabel,
                threadShortId = shortId,
                requiredParentKind = requiredParentKind,
                knownMemberProfilesById = knownMemberProfilesById,
                parentMessageId = target.parentMessageId,
                focusedMessageId = target.focusedMessageId,
                threadChannelId = target.threadChannelId
            )
                ?: return DataResult.Empty(EmptyReason.NoMessages)
        target.threadChannelId
            ?.takeIf { it.isNotBlank() }
            ?.let {
                cacheReplyThreadResolution(
                    provenance,
                    parentChannelId,
                    target.parentMessageId,
                    it
                )
            }
        if (!isCurrentReplyCacheProvenance(provenance)) {
            return staleReplyCacheProvenanceResult()
        }
        return DataResult.Content(payload)
    }

    suspend fun removeChannelMember(
        payload: KuiklyThreadScreenPayload,
        memberId: String,
        kind: String?
    ): DataResult<KuiklyThreadScreenPayload> {
        val repository =
            memberRepository
                ?: return DataResult.Error(ScreenError(code = "thread_member_repository_missing", retryable = false))
        val channelId =
            payload.sourceThreadId?.takeIf { it.isNotBlank() }?.let(::ChannelId)
                ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false))
        val memberKind =
            kind.strictMemberKindOrNull(
                source = "thread_remove_member",
                contract = MemberKindWireContract.Projected,
            )
                ?: return DataResult.Error(ScreenError(code = "thread_member_kind_missing", retryable = false))
        val cleanMemberId =
            memberId.takeIf { it.isNotBlank() }?.let(::MemberId)
                ?: return DataResult.Error(ScreenError(code = "thread_member_id_missing", retryable = false))
        val serverId = readSession()?.currentServerId?.takeIf { it.isNotBlank() }?.let(::ServerId)
        val request =
            RemoveChannelMemberRequest(
                channelId = channelId,
                memberId = cleanMemberId,
                kind = memberKind
            )
        return when (
            val result =
                if (serverMembersStore != null && serverId != null) {
                    serverMembersStore.removeChannelMember(
                        serverId = serverId,
                        request = request,
                        priority = DataRequestPriority.Foreground
                    )
                } else {
                    repository.removeChannelMember(DataRequestPriority.Foreground, request)
                }
        ) {
            is DataResult.Content ->
                DataResult.Content(
                    refreshMembersAfterMutation(
                        payload.copy(
                            removeMemberStatus = "idle",
                            removingMemberId = null,
                            removeMemberErrorCode = null
                        )
                    )
                )
            is DataResult.Empty -> DataResult.Empty(result.reason)
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    suspend fun leaveChannel(payload: KuiklyThreadScreenPayload): DataResult<Unit> {
        val repository =
            channelRepository
                ?: return DataResult.Error(ScreenError(code = "thread_channel_repository_missing", retryable = false))
        val channelId =
            payload.sourceThreadId?.takeIf { it.isNotBlank() }?.let(::ChannelId)
                ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false))
        return when (val result = repository.leaveChannel(channelId)) {
            is DataResult.Content -> DataResult.Content(Unit)
            is DataResult.Empty -> DataResult.Empty(result.reason)
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    suspend fun archiveChannel(payload: KuiklyThreadScreenPayload): DataResult<Unit> {
        val repository =
            channelRepository
                ?: return DataResult.Error(ScreenError(code = "thread_channel_repository_missing", retryable = false))
        val channelId =
            payload.sourceThreadId?.takeIf { it.isNotBlank() }?.let(::ChannelId)
                ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false))
        return when (val result = repository.archiveChannel(channelId)) {
            is DataResult.Content -> DataResult.Content(Unit)
            is DataResult.Empty -> DataResult.Empty(result.reason)
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    suspend fun deleteChannel(payload: KuiklyThreadScreenPayload): DataResult<Unit> {
        val repository =
            channelRepository
                ?: return DataResult.Error(ScreenError(code = "thread_channel_repository_missing", retryable = false))
        val channelId =
            payload.sourceThreadId?.takeIf { it.isNotBlank() }?.let(::ChannelId)
                ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false))
        return when (val result = repository.deleteChannel(channelId)) {
            is DataResult.Content -> DataResult.Content(Unit)
            is DataResult.Empty -> DataResult.Empty(result.reason)
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    private suspend fun refreshMembersAfterMutation(payload: KuiklyThreadScreenPayload): KuiklyThreadScreenPayload {
        return when (val outcome = enrichThreadMembers(payload)) {
            is SharedThreadDataSource.MembersEnrichOutcome.Updated -> outcome.payload
            is SharedThreadDataSource.MembersEnrichOutcome.Unchanged,
            is SharedThreadDataSource.MembersEnrichOutcome.Failed -> payload
        }
    }

    suspend fun fetchOlderThreadPayload(
        basePayload: KuiklyThreadScreenPayload,
        beforeSeq: Long
    ): DataResult<SharedThreadPageFetchResult> =
        fetchThreadPage(
            basePayload = basePayload,
            source = "older",
            repositoryCall = { repository, threadId, pageSize ->
                repository.loadOlderMessages(threadId, beforeSeq, pageSize)
            },
            call = { threadId, pageSize ->
                api.channelMessages(threadId, limit = pageSize, beforeSeq = beforeSeq)
            }
        )

    suspend fun fetchNewerThreadPayload(
        basePayload: KuiklyThreadScreenPayload,
        afterSeq: Long
    ): DataResult<SharedThreadPageFetchResult> =
        fetchThreadPage(
            basePayload = basePayload,
            source = "newer",
            repositoryCall = { repository, threadId, pageSize ->
                repository.loadNewerMessages(threadId, afterSeq, pageSize)
            },
            call = { threadId, pageSize ->
                api.channelMessages(threadId, limit = pageSize, afterSeq = afterSeq)
            }
        )

    suspend fun fetchRealtimeGapThreadPayload(
        basePayload: KuiklyThreadScreenPayload,
        sinceSeq: Long
    ): DataResult<SharedThreadPageFetchResult> =
        fetchThreadPage(
            basePayload = basePayload,
            source = "realtime_gap",
            repositoryCall = { repository, threadId, pageSize ->
                repository.syncNewerMessages(threadId, sinceSeq, pageSize)
            },
            call = { threadId, pageSize ->
                api.messageSync(threadId, sinceSeq = sinceSeq, limit = pageSize)
            }
        )

    /**
     * Reply-thread gap recovery tail fallback. `messageSync` is a delta feed that
     * only returns messages with seq greater than `sinceSeq`; when a reply-count
     * gap is driven by replies that were already on the server *below* the
     * visible tail (backgrounded receipt, initial empty load), the delta returns
     * empty forever and the timeline is stranded short of `replyCount`. This
     * refetches the channel tail (`channelMessages`, no seq bound) so those
     * older replies are merged back into the timeline.
     */
    suspend fun fetchReplyThreadTailGapPayload(
        basePayload: KuiklyThreadScreenPayload
    ): DataResult<SharedThreadPageFetchResult> =
        fetchThreadPage(
            basePayload = basePayload,
            source = "reply_count_tail_gap",
            repositoryCall = { repository, threadId, pageSize ->
                repository.getThread(threadId, includeFollowedSummaries = false, limit = pageSize)
            },
            call = { threadId, pageSize ->
                api.channelMessages(threadId, limit = pageSize)
            }
        )

    suspend fun sendMessage(
        channelId: String,
        body: String,
        asTask: Boolean,
        attachmentIds: List<String> = emptyList(),
        randomId: String? = null
    ): ThreadApiResult<ThreadMessagesPage> =
        api.sendMessage(channelId = channelId, body = body, asTask = asTask, attachmentIds = attachmentIds, randomId = randomId)

    suspend fun executePendingMentionActions(
        action: String,
        resolutionIds: List<String>
    ): ThreadApiResult<List<PendingMentionActionExecuteResult>> =
        api.executePendingMentionActions(action = action, resolutionIds = resolutionIds)

    suspend fun uploadAttachments(
        channelId: String,
        files: List<PlatformFile>,
        onProgress: ((build.raft.app.network.SlockTransferProgress) -> Unit)? = null
    ): DataResult<AttachmentUploadResult> {
        val repository =
            conversationRepository
                ?: return DataResult.Error(ScreenError(code = "attachment_upload_unavailable", retryable = false))
        if (channelId.isBlank()) {
            return DataResult.Error(ScreenError(code = "missing_channel_id", retryable = false))
        }
        if (files.isEmpty()) {
            return DataResult.Error(ScreenError(code = "attachment_read_failed", retryable = false))
        }
        val request =
            AttachmentUploadRequest(
                channelId = ChannelId(channelId),
                files = files
            )
        val progressive = repository as? ProgressiveConversationAttachmentUploader
        return if (onProgress != null && progressive != null) {
            progressive.uploadAttachmentsWithProgress(request, onProgress)
        } else {
            repository.uploadAttachments(request)
        }
    }

    /**
     * Capture one complete authority witness and bind it to one direct-session coordinator.  The
     * returned operation must be reused for capability admission, every upload mutation and all
     * later compensation; this data source intentionally exposes no reconstruct-on-cleanup seam.
     */
    suspend fun captureDirectAttachmentUploadOperation(
        diagnosticsLogger: (String) -> Unit = {},
    ): DirectAttachmentUploadOperation? {
        val repository =
            conversationRepository
                ?: return null
        val authority = captureExternalShareSendAuthority() ?: return null
        return (repository as? RealConversationRepository)
            ?.directAttachmentUploadOperation(authority, diagnosticsLogger)
    }

    suspend fun attachmentUploadMaxBytes(
        expectedAuthority: SlockExpectedAuthBinding? = null,
    ): DataResult<Long> {
        val repository =
            conversationRepository
                ?: return DataResult.Error(
                    ScreenError(code = "attachment_upload_capabilities_unavailable", retryable = true)
                )
        val boundRepository =
            expectedAuthority?.let { authority ->
                (repository as? RealConversationRepository)?.boundTo(authority)
                    ?: return DataResult.Error(
                        ScreenError(
                            code = "attachment_upload_authority_binding_unsupported",
                            retryable = false,
                        )
                    )
            } ?: repository
        return boundRepository.getAttachmentUploadCapabilities().map { capabilities -> capabilities.maxBytes }
    }

    suspend fun captureExternalShareSendAuthority(): SlockExpectedAuthBinding? {
        val session = sessionStore.readSession() ?: return null
        val environmentId = apiEnvironmentIdProvider().normalizedEnvironmentId().takeIf { it.isNotBlank() } ?: return null
        val accountId = session.user.id.trim().takeIf { it.isNotBlank() } ?: return null
        val accessToken = session.accessToken.takeIf { it.isNotBlank() } ?: return null
        val refreshToken = session.refreshToken.takeIf { it.isNotBlank() } ?: return null
        val serverId = session.currentServerId?.trim()?.takeIf(String::isNotBlank) ?: return null
        if (session.servers.none { server -> server.id.trim() == serverId }) return null
        return SlockExpectedAuthBinding(
            accountId = accountId,
            accessToken = accessToken,
            refreshToken = refreshToken,
            serverId = serverId,
            environmentId = environmentId,
        )
    }

    suspend fun matchesExternalShareSendAuthority(expected: SlockExpectedAuthBinding): Boolean {
        val session = sessionStore.readSession() ?: return false
        val environmentId = apiEnvironmentIdProvider().normalizedEnvironmentId()
        val expectedEnvironment = expected.environmentId.normalizedEnvironmentId()
        return expectedEnvironment.isNotBlank() &&
            environmentId == expectedEnvironment &&
            session.matchesExpectedAuthority(
                expected = expected,
                effectiveServerId = expected.serverId,
                effectiveEnvironmentId = environmentId,
            ) &&
            session.servers.any { server -> server.id.trim() == expected.serverId.trim() }
    }

    suspend fun attachmentUrl(attachmentId: String, disposition: String? = null): ThreadApiResult<ThreadAttachmentOpenTarget> =
        api.attachmentUrl(attachmentId, disposition)

    suspend fun attachmentHtmlPreviewUrl(attachmentId: String): ThreadApiResult<ThreadAttachmentOpenTarget> =
        api.attachmentHtmlPreviewUrl(attachmentId)

    suspend fun fetchFilesPayload(
        basePayload: KuiklyThreadScreenPayload,
        cursor: String? = null,
        currentPayload: KuiklyThreadFilesPayload? = null
    ): DataResult<KuiklyThreadFilesPayload> {
        val repository =
            conversationRepository
                ?: return DataResult.Error(ScreenError(code = "thread_files_unavailable", retryable = false))
        val threadId =
            basePayload.sourceThreadId?.takeIf { it.isNotBlank() }
                ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false))
        return when (val result = repository.getChannelFiles(ChannelId(threadId), cursor = cursor)) {
            is DataResult.Content ->
                DataResult.Content(
                    result.data.toKuiklyThreadFilesPayload(
                        basePayload = basePayload,
                        currentPayload = currentPayload,
                        append = cursor != null
                    )
                )
            is DataResult.Empty ->
                DataResult.Content(
                    currentPayload
                        ?.takeIf { cursor != null && it.items.isNotEmpty() }
                        ?.copy(status = "content", hasMore = false, loadingMore = false, nextCursor = null, errorCode = null)
                        ?: KuiklyThreadFilesPayload(
                            instanceId = basePayload.instanceId,
                            status = "empty",
                            items = emptyList(),
                            hasMore = false,
                            loadingMore = false,
                            nextCursor = null
                        )
                )
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    suspend fun saveMessage(messageId: String): DataResult<Unit> {
        if (messageId.isBlank()) {
            return DataResult.Error(ScreenError(code = "message_id_missing", retryable = false))
        }
        val response =
            try {
                api.saveMessage(messageId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return DataResult.Error(ScreenError(code = "thread_save_network", retryable = true))
        }
        return response.toUnitDataResult("thread_save_network")
    }

    suspend fun unsaveMessage(messageId: String): DataResult<Unit> {
        if (messageId.isBlank()) {
            return DataResult.Error(ScreenError(code = "message_id_missing", retryable = false))
        }
        val response =
            try {
                api.unsaveMessage(messageId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return DataResult.Error(ScreenError(code = "thread_unsave_network", retryable = true))
            }
        return response.toUnitDataResult("thread_unsave_network")
    }

    suspend fun setMessageReaction(messageId: String, emoji: String, selected: Boolean): DataResult<MessageMutationSummary> {
        val cleanMessageId = messageId.trim()
        val cleanEmoji = emoji.trim()
        if (cleanMessageId.isBlank()) {
            return DataResult.Error(ScreenError(code = "message_id_missing", retryable = false))
        }
        if (cleanEmoji.isBlank()) {
            return DataResult.Error(ScreenError(code = "message_reaction_missing", retryable = false))
        }
        val response =
            try {
                if (selected) {
                    api.addReaction(cleanMessageId, cleanEmoji)
                } else {
                    api.removeReaction(cleanMessageId, cleanEmoji)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return DataResult.Error(ScreenError(code = "thread_reaction_network", retryable = true))
            }
        return when (response) {
            is ThreadApiResult.Success -> DataResult.Content(response.data)
            is ThreadApiResult.Failure -> response.toThreadDataError("thread_reaction_network")
        }
    }

    suspend fun loadAttachmentComments(attachmentId: String): DataResult<ThreadAttachmentCommentsPage> {
        val cleanAttachmentId = attachmentId.trim()
        if (cleanAttachmentId.isBlank()) {
            return DataResult.Error(ScreenError(code = "attachment_id_missing", retryable = false))
        }
        val response =
            try {
                api.attachmentComments(cleanAttachmentId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return DataResult.Error(ScreenError(code = "thread_attachment_comments_network", retryable = true))
            }
        return when (response) {
            is ThreadApiResult.Success -> DataResult.Content(response.data)
            is ThreadApiResult.Failure -> response.toThreadDataError("thread_attachment_comments_network")
        }
    }

    suspend fun sendAttachmentComment(attachmentId: String, body: String): DataResult<Unit> {
        val cleanAttachmentId = attachmentId.trim()
        val cleanBody = body.trim()
        if (cleanAttachmentId.isBlank() || cleanBody.isBlank()) {
            return DataResult.Error(ScreenError(code = "attachment_comment_missing", retryable = false))
        }
        val response =
            try {
                api.sendAttachmentComment(cleanAttachmentId, cleanBody)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return DataResult.Error(ScreenError(code = "thread_attachment_comment_send_network", retryable = true))
            }
        return when (response) {
            is ThreadApiResult.Success -> DataResult.Content(Unit)
            is ThreadApiResult.Failure -> response.toThreadDataError("thread_attachment_comment_send_network")
        }
    }

    suspend fun createOrOpenReplyThread(
        basePayload: KuiklyThreadScreenPayload,
        parentMessage: KuiklyThreadMessageItem
    ): DataResult<KuiklyThreadScreenPayload> {
        val replyResolutionScope = readReplyResolutionScope() ?: return authSessionRequiredResult()
        val replyResolutionNamespaceId = replyResolutionScope.namespaceId
        val parentChannelId =
            basePayload.sourceThreadId?.takeIf { it.isNotBlank() }
                ?: basePayload.parentChannelId?.takeIf { it.isNotBlank() }
                ?: return DataResult.Error(ScreenError(code = "thread_parent_channel_missing", retryable = false))
        cachedReplyThreadStreamId(replyResolutionNamespaceId, parentChannelId, parentMessage.id)?.let { threadChannelId ->
            return loadResolvedReplyThread(
                basePayload = basePayload,
                parentMessage = parentMessage,
                parentChannelId = parentChannelId,
                parentMessageId = parentMessage.id,
                threadChannelId = threadChannelId,
                replyCount = parentMessage.replyCount,
                replyResolutionScope = replyResolutionScope
            )
        }
        val actionResult =
            if (conversationRepository != null) {
                try {
                    conversationRepository.createOrOpenThread(ChannelId(parentChannelId), MessageId(parentMessage.id))
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    return DataResult.Error(ScreenError(code = "thread_reply_network", retryable = true))
                }
            } else {
                val response =
                    try {
                        api.createOrOpenThread(parentChannelId, parentMessage.id)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        return DataResult.Error(ScreenError(code = "thread_reply_network", retryable = true))
                    }
                when (response) {
                    is ThreadApiResult.Success -> DataResult.Content(response.data)
                    is ThreadApiResult.Failure -> return response.toThreadDataError("thread_reply_network")
                }
            }
        val action =
            when (actionResult) {
                is DataResult.Content -> actionResult.data
                is DataResult.Empty -> {
                    return DataResult.Empty(EmptyReason.NoMessages)
                }
                is DataResult.Error -> return actionResult
                is DataResult.NoPermission -> return actionResult
            }
        val threadChannelId = action.threadChannelId.value
        val resolvedParentMessageId = action.parentMessageId?.value ?: parentMessage.id
        if (!cacheReplyThreadResolution(
                replyResolutionScope.provenance,
                parentChannelId,
                resolvedParentMessageId,
                threadChannelId
            )
        ) {
            return DataResult.Error(
                ScreenError(code = "thread_reply_access_authority_changed", retryable = true)
            )
        }
        return loadResolvedReplyThread(
            basePayload = basePayload,
            parentMessage = parentMessage,
            parentChannelId = parentChannelId,
            parentMessageId = resolvedParentMessageId,
            threadChannelId = threadChannelId,
            replyCount = action.replyCount,
            replyResolutionScope = replyResolutionScope
        )
    }

    suspend fun followThread(parentMessageId: MessageId): DataResult<ThreadActionResult> {
        val repository =
            conversationRepository
                ?: return DataResult.Error(ScreenError(code = "thread_follow_network", retryable = true))
        return try {
            repository.followThread(parentMessageId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            DataResult.Error(ScreenError(code = "thread_follow_network", retryable = true))
        }
    }

    suspend fun unfollowThread(threadChannelId: ChannelId): DataResult<ThreadActionResult> {
        val repository =
            conversationRepository
                ?: return DataResult.Error(ScreenError(code = "thread_unfollow_network", retryable = true))
        return try {
            repository.unfollowThread(threadChannelId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            DataResult.Error(ScreenError(code = "thread_unfollow_network", retryable = true))
        }
    }

    private suspend fun loadResolvedReplyThread(
        basePayload: KuiklyThreadScreenPayload,
        parentMessage: KuiklyThreadMessageItem,
        parentChannelId: String,
        parentMessageId: String,
        threadChannelId: String,
        replyCount: Int,
        replyResolutionScope: ReplyResolutionScope
    ): DataResult<KuiklyThreadScreenPayload> {
        val parentSurfaceKind =
            basePayload.parentSurfaceKind?.takeIf { it.isNotBlank() }
                ?: when {
                    basePayload.surfaceKind == KuiklyThreadSurfaceKind.DirectMessage -> KuiklyThreadSurfaceKind.DirectMessage
                    basePayload.channelKind.equals("dm", ignoreCase = true) -> KuiklyThreadSurfaceKind.DirectMessage
                    basePayload.memberScopedMentions && basePayload.parentChannelId.isNullOrBlank() -> KuiklyThreadSurfaceKind.DirectMessage
                    else -> KuiklyThreadSurfaceKind.Channel
                }
        val resolvedOrigin =
            parentMessage.copy(
                threadChannelId = threadChannelId,
                replyCount = maxOf(parentMessage.replyCount, replyCount)
            )
        val replyBase =
            basePayload.copy(
                instanceId = "shared-shell-thread-$threadChannelId",
                title = ThreadReplyPageTitle,
                subtitle = basePayload.replyThreadHeaderSubtitle(),
                status = "loading",
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                sourceThreadId = threadChannelId,
                streamChannelId = threadChannelId,
                parentChannelId = parentChannelId,
                parentMessageId = parentMessageId,
                parentSurfaceKind = parentSurfaceKind,
                parentNavigationHint = basePayload.parentNavigationHint,
                navigationLabel = "Close",
                selectedTab = "chat",
                showModeTabs = false,
                showReplyActions = false,
                showOpenInChannelAction = true,
                focusedMessageId = basePayload.focusedMessageId,
                originMessage = resolvedOrigin,
                originParentMessageId = parentMessageId,
                originStatus = "loaded",
                messages = emptyList(),
                canLoadMoreBefore = false,
                canLoadMoreAfter = false,
                emptyTitle = ThreadReplyEmptyTitle,
                emptyDetail = ThreadReplyEmptyDetail,
                composer =
                    buildThreadSurfaceComposerPayload(
                        surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                        displayName = ThreadReplyPageTitle,
                        fallbackId = parentMessage.id,
                        enabled = basePayload.canSend,
                        mentionChannelId = parentChannelId,
                        parentMessageId = parentMessageId,
                        streamChannelId = threadChannelId
                    )
            )
        return when (
            val loaded =
                fetchThreadPayloadWithResolutionScope(
                    basePayload = replyBase,
                    persistResult = false,
                    reusePrewarm = false,
                    replyResolutionScope = replyResolutionScope
                )
        ) {
            is DataResult.Content -> {
                val payload =
                    loaded.data.copy(
                        surfaceKind = replyBase.surfaceKind,
                        streamChannelId = replyBase.streamChannelId,
                        parentChannelId = replyBase.parentChannelId,
                        parentMessageId = replyBase.parentMessageId,
                        parentSurfaceKind = replyBase.parentSurfaceKind,
                        focusedMessageId = loaded.data.focusedMessageId ?: replyBase.focusedMessageId,
                        focusedMessageRequestNonce = replyBase.focusedMessageRequestNonce,
                        composer = loaded.data.composer.takeUnless { it.isDefaultThreadComposer() } ?: replyBase.composer,
                        originMessage = loaded.data.originMessage ?: resolvedOrigin,
                        originParentMessageId = loaded.data.originParentMessageId ?: parentMessageId,
                        originStatus = if (loaded.data.originMessage != null) loaded.data.originStatus else "loaded"
                    ).asLoadedEmptyReplyThreadPayload()
                val selected =
                    cacheCanonicalPayload(
                        payload,
                        replyResolutionScope.provenance.threadPayloadOwnerScopeId
                    )
                DataResult.Content(selected)
            }
            is DataResult.Empty -> {
                val payload = replyBase.toEmptyReplyThreadPayload()
                val selected =
                    cacheCanonicalPayload(
                        payload,
                        replyResolutionScope.provenance.threadPayloadOwnerScopeId
                    )
                DataResult.Content(selected)
            }
            is DataResult.Error -> loaded
            is DataResult.NoPermission -> loaded
        }
    }

    suspend fun markVisibleChannelRead(
        channelId: String,
        latestSeq: Long?,
        expectedProvenance: ReplyCacheProvenance? = null
    ): DataResult<ReadReceiptResult> {
        if (channelId.isBlank()) {
            return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false))
        }
        if (expectedProvenance != null && !isCurrentReplyCacheProvenance(expectedProvenance)) {
            return staleReplyCacheProvenanceResult()
        }
        val coordinator = readReceiptCoordinator
        val ownerFactory = readReceiptOwnerFactory
        if (coordinator != null && ownerFactory != null) {
            val session = readSession() ?: return authSessionRequiredResult()
            if (expectedProvenance != null && !isCurrentReplyCacheProvenance(expectedProvenance)) {
                return staleReplyCacheProvenanceResult()
            }
            val serverId =
                selectedServerStore?.selectedServerId?.value
                    ?: session.currentServerId
                    ?: return DataResult.Error(ScreenError(code = "server_missing", retryable = false))
            val owner = ownerFactory(serverId, session.user.id)
            return coordinator.awaitReadToSeq(owner, ChannelId(channelId), latestSeq ?: 0L)
        }
        if (expectedProvenance != null && !isCurrentReplyCacheProvenance(expectedProvenance)) {
            return staleReplyCacheProvenanceResult()
        }
        val response =
            try {
                if (latestSeq != null && latestSeq > 0L) {
                    api.markChannelReadToSeq(channelId, latestSeq)
                } else {
                    api.markChannelReadAll(channelId)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return DataResult.Error(ScreenError(code = "thread_read_receipt_network", retryable = true))
            }
        return if (response is ThreadApiResult.Success) {
            DataResult.Content(
                ReadReceiptResult(
                    channelId = ChannelId(channelId),
                    seq = latestSeq?.takeIf { it > 0L },
                    changed = true
                )
            )
        } else {
            (response as ThreadApiResult.Failure).toThreadDataError("thread_read_receipt_network")
        }
    }

    suspend fun openMessageContext(
        messageId: String,
        basePayload: KuiklyThreadScreenPayload
    ): DataResult<KuiklyThreadScreenPayload> {
        val trimmedMessageId = messageId.takeIf { it.isNotBlank() }
            ?: return DataResult.Error(ScreenError(code = "thread_parent_message_missing", retryable = false))
        val resolutionScope =
            readReplyResolutionScope()
                ?: return authSessionRequiredResult()
        val session = resolutionScope.session
        return fetchThreadPayloadFromRepository(
            session = session,
            expectedProvenance = resolutionScope.provenance,
            fallbackCode = "thread_parent_context_network",
            call = { repository -> repository.getMessageContext(MessageId(trimmedMessageId)) },
            transform = { snapshot ->
                snapshot.toKuiklyThreadPayload(basePayload, session.user.id)
                    .copy(showOpenInChannelAction = false)
            }
        )
    }

    suspend fun openCommentHost(
        hostMessageId: String,
        basePayload: KuiklyThreadScreenPayload
    ): DataResult<KuiklyThreadScreenPayload> {
        val streamThreadId = basePayload.sourceThreadId?.takeIf { it.isNotBlank() }?.let(::ThreadId)
        if (basePayload.surfaceKind == KuiklyThreadSurfaceKind.ReplyThread && streamThreadId != null) {
            return fetchThreadPayload(basePayload, persistResult = false)
        }
        return openMessageContext(hostMessageId, basePayload)
    }

    private suspend fun fetchThreadPage(
        basePayload: KuiklyThreadScreenPayload,
        source: String,
        repositoryCall: suspend (ConversationRepository, ThreadId, Int) -> DataResult<ThreadSnapshot>,
        call: suspend (threadId: String, pageSize: Int) -> ThreadApiResult<ThreadMessagesPage>
    ): DataResult<SharedThreadPageFetchResult> {
        val threadId =
            basePayload.sourceThreadId?.takeIf { it.isNotBlank() }
                ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false))
        val resolutionScope =
            readReplyResolutionScope()
                ?: return authSessionRequiredResult()
        val session = resolutionScope.session
        val pageSize = threadPageSize(session.currentServerId)
        diagnosticsLogger(
            "thread_page_size source=$source server=${session.currentServerId.orEmpty()} pageSize=$pageSize"
        )
        var rawPage = RawMessagePageWitness.None
        val result =
            if (conversationRepository != null) {
                fetchThreadPayloadFromRepository(
                    session = session,
                    expectedProvenance = resolutionScope.provenance,
                    fallbackCode = "thread_${source}_network",
                    call = { repository -> repositoryCall(repository, ThreadId(threadId), pageSize) },
                    transform = { snapshot ->
                        rawPage = snapshot.rawMessagePage
                        snapshot.toKuiklyThreadPayload(basePayload, session.user.id)
                    }
                )
            } else {
                val response =
                    try {
                        call(threadId, pageSize)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        return DataResult.Error(ScreenError(code = "thread_${source}_network", retryable = true))
                    }
                val page =
                    when (response) {
                        is ThreadApiResult.Success -> response.data
                        is ThreadApiResult.Failure ->
                            return response.toThreadDataError("thread_${source}_network")
                    }
                DataResult.Content(
                    buildSharedThreadPayloadFromMessagesPage(
                        basePayload = basePayload,
                        page = page,
                        session = session,
                        diagnostics = diagnosticsLogger,
                    )
                )
            }
        return when (result) {
            is DataResult.Content -> {
                seedThreadAuthorProfiles(session.currentServerId, result.data, source = source)
                DataResult.Content(
                    SharedThreadPageFetchResult(
                        payload = result.data,
                        requestedPageSize = pageSize,
                        durablePage =
                            rawPage.page?.let { page ->
                                SharedThreadDurablePage(
                                    context = ReplyCacheProducerContext(session, resolutionScope.provenance),
                                    rawPage = page
                                )
                            }
                    )
                )
            }
            is DataResult.Empty -> result
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    private suspend fun fetchThreadPayloadFromRepository(
        session: StoredAuthSession,
        expectedProvenance: ReplyCacheProvenance,
        fallbackCode: String,
        call: suspend (ConversationRepository) -> DataResult<ThreadSnapshot>,
        transform: (ThreadSnapshot) -> KuiklyThreadScreenPayload
    ): DataResult<KuiklyThreadScreenPayload> {
        val result =
            fetchThreadSnapshotFromRepository(
                fallbackCode = fallbackCode,
                expectedProvenance = expectedProvenance,
                call = call
            )
        return result.map { snapshot ->
            transform(snapshot)
                .asLoadedEmptyReplyThreadPayload()
        }
    }

    private suspend fun fetchInitialThreadPayloadFromRepository(
        session: StoredAuthSession,
        basePayload: KuiklyThreadScreenPayload,
        threadId: ThreadId,
        replyResolutionScope: ReplyResolutionScope,
        includeFollowedSummaries: Boolean = false
    ): DataResult<KuiklyThreadScreenPayload> {
        val focusedContext =
            if (includeFollowedSummaries) {
                null
            } else {
                fetchFocusedInitialThreadPayloadFromRepository(
                    session = session,
                    basePayload = basePayload,
                    routeThreadId = threadId,
                    accessFallback = null,
                    replyResolutionScope = replyResolutionScope
                )
            }
        if (focusedContext != null) {
            return focusedContext
        }
        val initialResult =
            fetchThreadSnapshotFromRepository(
                fallbackCode = "thread_network",
                expectedProvenance = replyResolutionScope.provenance
            ) { repository ->
                val pageSize = threadPageSize(session.currentServerId)
                diagnosticsLogger?.invoke(
                    "thread_page_size source=initial_repository server=${session.currentServerId.orEmpty()} " +
                        "pageSize=$pageSize"
                )
                repository.getThread(
                    threadId = threadId,
                    includeFollowedSummaries = includeFollowedSummaries,
                    limit = pageSize
                )
            }
        val initialData = initialResult as? DataResult.Content
        // If the route's stream hint is untrusted (stream != parent channel) and the
        // thread-channel snapshot's reported parent pair differs from the route,
        // fall back to the resolve path which POSTs createOrOpenThread to
        // reconcile. This is the v0.2 defense-in-depth: the snapshot may not
        // carry parent metadata at all (treated as trust), but if it does and
        // disagrees with the canonical route parent pair, we don't show it.
        return when {
            initialData != null &&
                basePayload.hasUntrustedReplyThreadStreamHint() &&
                !initialData.data.parentPairMatchesRoute(basePayload) ->
                fetchResolvedReplyThreadSnapshot(
                    session = session,
                    basePayload = basePayload,
                    replyResolutionScope = replyResolutionScope
                ).map { (resolvedBase, snapshot) ->
                    snapshot
                        ?.withReplyParentMessageFromContext(resolvedBase, replyResolutionScope = replyResolutionScope)
                        ?.toKuiklyThreadPayload(resolvedBase, session.user.id)
                        ?: resolvedBase.toEmptyReplyThreadPayload()
                }
            initialData != null ->
                DataResult.Content(
                    initialData.data
                        .withReplyParentMessageFromContext(basePayload, replyResolutionScope = replyResolutionScope)
                        .toKuiklyThreadPayload(basePayload, session.user.id)
                )
            else -> initialResult as DataResult<KuiklyThreadScreenPayload>
        }
    }

    private suspend fun fetchFocusedInitialThreadPayloadFromRepository(
        session: StoredAuthSession,
        basePayload: KuiklyThreadScreenPayload,
        routeThreadId: ThreadId,
        accessFallback: ConversationAccessCandidate?,
        replyResolutionScope: ReplyResolutionScope
    ): DataResult<KuiklyThreadScreenPayload>? {
        val focusedMessageId = basePayload.focusedMessageId?.takeIf { it.isNotBlank() } ?: return null
        val focusedContextChannelId = basePayload.focusedMessageContextChannelIdOrNull()
        val contextResult =
            fetchThreadSnapshotFromRepository(
                fallbackCode = "thread_focused_context",
                expectedProvenance = replyResolutionScope.provenance
            ) { repository ->
                repository.getMessageContext(MessageId(focusedMessageId), focusedContextChannelId)
            }
        val context = (contextResult as? DataResult.Content)?.data ?: return null
        if (!context.matchesFocusedThreadRoute(routeThreadId, focusedMessageId)) {
            return null
        }
        return DataResult.Content(
            context
                .withCachedReplyParentMessage(
                    basePayload = basePayload,
                    messageStoreScopeId = replyResolutionScope.messageStoreScopeId
                )
                .withConversationAccessFallback(accessFallback)
                .toKuiklyThreadPayload(basePayload, session.user.id)
        )
    }

    private fun ThreadSnapshot.withCachedReplyParentMessage(
        basePayload: KuiklyThreadScreenPayload,
        messageStoreScopeId: String?
    ): ThreadSnapshot {
        val isReplyThread =
            basePayload.surfaceKind == KuiklyThreadSurfaceKind.ReplyThread ||
                conversation.kind == ConversationKind.Thread
        if (!isReplyThread) return this
        val parentMessageId =
            basePayload.parentMessageId?.takeIf { it.isNotBlank() }
                ?: basePayload.originParentMessageId?.takeIf { it.isNotBlank() }
                ?: conversation.thread?.parentMessageId?.value?.takeIf { it.isNotBlank() }
                ?: return this
        val parentChannelId =
            basePayload.parentChannelId?.takeIf { it.isNotBlank() }
                ?: conversation.thread?.parentChannelId?.value
                ?: parentMessage?.channelId?.value
                ?: return this
        val parent =
            getCachedReplyParentMessage(
                messageStoreScopeId = messageStoreScopeId ?: return this,
                parentChannelId = parentChannelId,
                parentMessageId = parentMessageId
            ) ?: return this
        return copy(parentMessage = parent)
    }

    private suspend fun ThreadSnapshot.withReplyParentMessageFromContext(
        basePayload: KuiklyThreadScreenPayload,
        allowNetworkHydration: Boolean = true,
        replyResolutionScope: ReplyResolutionScope
    ): ThreadSnapshot {
        val isReplyThread =
            basePayload.surfaceKind == KuiklyThreadSurfaceKind.ReplyThread ||
                conversation.kind == ConversationKind.Thread
        if (!isReplyThread) return this
        val parentMessageId =
            basePayload.parentMessageId?.takeIf { it.isNotBlank() }
                ?: basePayload.originParentMessageId?.takeIf { it.isNotBlank() }
                ?: conversation.thread?.parentMessageId?.value?.takeIf { it.isNotBlank() }
                ?: return this
        val existingParentMessage = parentMessage?.takeIf { it.id.value == parentMessageId }
        if (existingParentMessage != null && !existingParentMessage.needsReplyOriginContextHydration()) {
            return this
        }
        if (
            basePayload.originStatus == "loaded" &&
            basePayload.originMessage?.id == parentMessageId &&
            !basePayload.originMessage.needsReplyOriginContextHydration()
        ) {
            val originParentChannelId =
                basePayload.parentChannelId?.takeIf { it.isNotBlank() }
                    ?: conversation.thread?.parentChannelId?.value
                    ?: parentMessage?.channelId?.value
                    ?: return this
            return copy(
                parentMessage =
                    basePayload.originMessage.toMessage(
                        threadId = ThreadId(originParentChannelId),
                        channelId = ChannelId(originParentChannelId),
                        diagnostics = diagnosticsLogger,
                    )
            )
        }
        val messageParentCandidate = messages.firstOrNull { message -> message.id.value == parentMessageId }
        if (messageParentCandidate != null && !messageParentCandidate.needsReplyOriginContextHydration()) {
            return this
        }
        val parentChannelId =
            basePayload.parentChannelId?.takeIf { it.isNotBlank() }
                ?: conversation.thread?.parentChannelId?.value
                ?: parentMessage?.channelId?.value
        if (!allowNetworkHydration) {
            return this
        }
        val resolvedParentChannelId = parentChannelId ?: return this
        val parent =
            getReplyParentMessageFromStore(
                parentChannelId = resolvedParentChannelId,
                parentMessageId = parentMessageId,
                messageStoreScopeId = replyResolutionScope.messageStoreScopeId,
                expectedProvenance = replyResolutionScope.provenance
            )
                ?: return this
        return copy(parentMessage = parent)
    }

    private suspend fun fetchInitialReplyThreadPayloadFromRepository(
        session: StoredAuthSession,
        basePayload: KuiklyThreadScreenPayload,
        includeFollowedSummaries: Boolean = false,
        requestPriority: DataRequestPriority = DataRequestPriority.Foreground,
        replyResolutionScope: ReplyResolutionScope,
        replaceReplyAccessFailure: Boolean = false
    ): DataResult<KuiklyThreadScreenPayload> = coroutineScope {
        val parentChannelId = basePayload.parentChannelId?.takeIf { it.isNotBlank() }
            ?: return@coroutineScope DataResult.Error(ScreenError(code = "thread_parent_channel_missing", retryable = false))
        val parentMessageId = basePayload.parentMessageId?.takeIf { it.isNotBlank() }
            ?: basePayload.originParentMessageId?.takeIf { it.isNotBlank() }
            ?: return@coroutineScope DataResult.Error(ScreenError(code = "thread_parent_message_missing", retryable = false))
        val routeThreadId =
            basePayload.streamChannelId?.takeIf { it.isNotBlank() }?.let(::ThreadId)
                ?: basePayload.sourceThreadId?.takeIf { it.isNotBlank() }?.let(::ThreadId)
        val focusedContext =
            if (includeFollowedSummaries || routeThreadId == null) {
                null
            } else {
                fetchFocusedInitialThreadPayloadFromRepository(
                    session = session,
                    basePayload = basePayload,
                    routeThreadId = routeThreadId,
                    accessFallback = null,
                    replyResolutionScope = replyResolutionScope
                )
            }
        if (focusedContext != null) {
            return@coroutineScope focusedContext
        }
        val parentContextStartedAt = TimeSource.Monotonic.markNow()
        val parentContextDeferred =
            async {
                resolveReplyParentMessageFromStore(
                    parentChannelId = parentChannelId,
                    parentMessageId = parentMessageId,
                    requestPriority = requestPriority,
                    messageStoreScopeId = replyResolutionScope.messageStoreScopeId,
                    expectedProvenance = replyResolutionScope.provenance
                )
            }
        val accessFlightKey = replyThreadAccessFlightKey(replyResolutionScope, basePayload)
        suspend fun fetchInitialSnapshot(): DataResult<ThreadSnapshot> {
            if (accessFlightKey != null &&
                !replyThreadAccessAuthorityStillCurrent(
                    expectedKey = accessFlightKey,
                    expectedProvenance = replyResolutionScope.provenance,
                    basePayload = basePayload
                )
            ) {
                return DataResult.Error(
                    ScreenError(code = "thread_reply_access_authority_changed", retryable = true)
                )
            }
            val result =
                fetchThreadSnapshotFromRepository(
                    fallbackCode = "thread_network_reply",
                    expectedProvenance = replyResolutionScope.provenance
                ) { repository ->
                    val pageSize = threadPageSize(session.currentServerId)
                    diagnosticsLogger(
                        "thread_page_size source=initial_reply server=${session.currentServerId.orEmpty()} " +
                            "pageSize=$pageSize"
                    )
                    repository.getReplyThread(
                        parentChannelId = ChannelId(parentChannelId),
                        parentMessageId = MessageId(parentMessageId),
                        includeFollowedSummaries = includeFollowedSummaries,
                        limit = pageSize,
                        priority = requestPriority
                    )
                }
            if (accessFlightKey != null &&
                !replyThreadAccessAuthorityStillCurrent(
                    expectedKey = accessFlightKey,
                    expectedProvenance = replyResolutionScope.provenance,
                    basePayload = basePayload
                )
            ) {
                return DataResult.Error(
                    ScreenError(code = "thread_reply_access_authority_changed", retryable = true)
                )
            }
            return result
        }
        val initialResult =
            if (accessFlightKey == null) {
                fetchInitialSnapshot()
            } else {
                payloadCache.joinOrStartReplyThreadAccessFlight(
                    key = accessFlightKey,
                    replaceFailure = replaceReplyAccessFailure,
                    fetch = ::fetchInitialSnapshot
                )
            }
        return@coroutineScope when (initialResult) {
            is DataResult.Content -> {
                onReplyThreadAccessValidatedForTest?.invoke()
                val hasReplyContent = initialResult.data.hasRenderableReplyThreadMessages(basePayload)
                val parentContextRemainingGraceMillis =
                    (replyOriginHydrationGraceMillis -
                        parentContextStartedAt.elapsedNow().inWholeMilliseconds)
                        .coerceAtLeast(0L)
                val parentResolution =
                    if (parentContextDeferred.isCompleted) {
                        parentContextDeferred.await()
                    } else {
                        withTimeoutOrNull(parentContextRemainingGraceMillis) {
                            parentContextDeferred.await()
                        }
                    }
                // A grace timeout only means that the canonical parent lookup has not
                // completed yet. It is not evidence that the parent message is absent.
                // The scoped message-store producer may still complete after this
                // caller is cancelled, and the visible route will hydrate that result.
                val parentContextTimedOut = parentResolution == null
                if (parentContextTimedOut) {
                    parentContextDeferred.cancel()
                    diagnosticsLogger(
                        "thread_parent_context_grace_expired parentChannelId=$parentChannelId " +
                            "parentMessageId=$parentMessageId " +
                            "graceMs=$replyOriginHydrationGraceMillis " +
                            "hasReplyContent=$hasReplyContent"
                    )
                }
                val parentMessage =
                    (parentResolution as? SharedThreadMessageResolution.Loaded)?.message
                val resolvedThreadId =
                    initialResult.data.conversation.thread?.streamChannelId?.value?.takeIf { it.isNotBlank() }
                        ?: initialResult.data.conversation.channelId?.value?.takeIf { it.isNotBlank() }
                        ?: initialResult.data.conversation.threadId.value.takeIf { it.isNotBlank() }
                        ?: basePayload.streamChannelId?.takeIf { it.isNotBlank() }
                        ?: basePayload.sourceThreadId?.takeIf { it.isNotBlank() }
                val resolvedBase =
                    if (resolvedThreadId == null) {
                        basePayload
                    } else {
                        basePayload.copy(
                            sourceThreadId = resolvedThreadId,
                            streamChannelId = resolvedThreadId
                        )
                    }
                val originResolvedBase =
                    when (parentResolution) {
                        is SharedThreadMessageResolution.Loaded -> resolvedBase
                        SharedThreadMessageResolution.Missing ->
                            resolvedBase.copy(
                                originStatus = "missing",
                                originErrorCode = null
                            )
                        is SharedThreadMessageResolution.Error ->
                            resolvedBase.copy(
                                originStatus = "error",
                                originErrorCode = parentResolution.code
                            )
                        null ->
                            resolvedBase.copy(
                                originStatus = "loading",
                                originErrorCode = null
                            )
                    }
                val payload =
                    initialResult.data
                        .withReplyParentMessage(parentChannelId, parentMessageId, parentMessage)
                        .rememberReplyParentMessage(
                            parentChannelId,
                            parentMessageId,
                            replyResolutionScope.messageStoreScopeId
                        )
                        .withReplyParentMessageFromContext(
                            basePayload = originResolvedBase,
                            allowNetworkHydration = false,
                            replyResolutionScope = replyResolutionScope
                        )
                        .toKuiklyThreadPayload(originResolvedBase, session.user.id)
                DataResult.Content(
                    if (payload.surfaceKind == KuiklyThreadSurfaceKind.ReplyThread && payload.messages.isEmpty()) {
                        payload.toEmptyReplyThreadPayload()
                    } else {
                        payload
                    }
                )
            }
            is DataResult.Empty -> {
                parentContextDeferred?.cancel()
                initialResult
            }
            is DataResult.Error -> {
                parentContextDeferred?.cancel()
                initialResult
            }
            is DataResult.NoPermission -> {
                parentContextDeferred?.cancel()
                initialResult
            }
        }
    }

    private suspend fun getReplyParentMessageFromStore(
        parentChannelId: String,
        parentMessageId: String,
        requestPriority: DataRequestPriority = DataRequestPriority.Foreground,
        messageStoreScopeId: String?,
        expectedProvenance: ReplyCacheProvenance
    ): Message? =
        when (
            val resolution = resolveReplyParentMessageFromStore(
                parentChannelId = parentChannelId,
                parentMessageId = parentMessageId,
                requestPriority = requestPriority,
                messageStoreScopeId = messageStoreScopeId,
                expectedProvenance = expectedProvenance
            )
        ) {
            is SharedThreadMessageResolution.Loaded -> resolution.message
            SharedThreadMessageResolution.Missing,
            is SharedThreadMessageResolution.Error -> null
        }

    private suspend fun resolveReplyParentMessageFromStore(
        parentChannelId: String,
        parentMessageId: String,
        requestPriority: DataRequestPriority = DataRequestPriority.Foreground,
        messageStoreScopeId: String?,
        expectedProvenance: ReplyCacheProvenance
    ): SharedThreadMessageResolution {
        val parentChannel = ChannelId(parentChannelId)
        val parentMessage = MessageId(parentMessageId)
        val cachedParent = messageStoreScopeId?.let { scopeId ->
            messageResolver.getScoped(scopeId, parentChannel, parentMessage)
        }
            ?.takeIf { message -> message.matchesReplyOriginParent(parentChannel, parentMessage) }
        suspend fun fetchParent(): SharedThreadMessageResolution {
            val startedAt = TimeSource.Monotonic.markNow()
            val contextResult =
                fetchThreadSnapshotFromRepository(
                    fallbackCode = "thread_parent_context",
                    expectedProvenance = expectedProvenance,
                    allowCapturedScopeResultAfterProvenanceChange = true
                ) { repository ->
                    repository.getMessageContext(parentMessage, parentChannel, requestPriority)
                }
            diagnosticsLogger(
                "thread_parent_context_parallel_result parentChannelId=$parentChannelId " +
                    "parentMessageId=$parentMessageId result=${contextResult.threadDiagnosticsResult()} " +
                    "durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
            )
            return when (contextResult) {
                is DataResult.Content -> {
                    val message =
                        contextResult.data.parentMessage?.takeIf { message -> message.id == parentMessage }
                            ?: contextResult.data.messages.firstOrNull { message -> message.id == parentMessage }
                    message
                        ?.let { resolved -> SharedThreadMessageResolution.Loaded(resolved) }
                        ?: SharedThreadMessageResolution.Missing
                }
                is DataResult.Empty -> SharedThreadMessageResolution.Missing
                is DataResult.Error -> SharedThreadMessageResolution.Error(contextResult.error.code)
                is DataResult.NoPermission -> SharedThreadMessageResolution.Error(contextResult.blocker.code)
            }
        }
        val parent =
            if (messageStoreScopeId == null) {
                fetchParent()
            } else {
                messageResolver.resolveScoped(messageStoreScopeId, parentChannel, parentMessage, ::fetchParent)
            }
        if (cachedParent != null && parent is SharedThreadMessageResolution.Loaded && parent.message == cachedParent) {
            diagnosticsLogger(
                "thread_parent_context_store_hit parentChannelId=$parentChannelId parentMessageId=$parentMessageId"
            )
        }
        return when (parent) {
            is SharedThreadMessageResolution.Loaded -> {
                parent.message
                    .takeIf { message -> message.matchesReplyOriginParent(parentChannel, parentMessage) }
                    ?.let { resolved -> SharedThreadMessageResolution.Loaded(resolved) }
                    ?: SharedThreadMessageResolution.Error("thread_parent_context_identity_mismatch")
            }
            SharedThreadMessageResolution.Missing -> parent
            is SharedThreadMessageResolution.Error -> parent
        }
    }

    private fun getCachedReplyParentMessage(
        messageStoreScopeId: String,
        parentChannelId: String,
        parentMessageId: String
    ): Message? {
        val parentChannel = ChannelId(parentChannelId)
        val parentMessage = MessageId(parentMessageId)
        return messageResolver.getScoped(messageStoreScopeId, parentChannel, parentMessage)
            ?.takeIf { message -> message.matchesReplyOriginParent(parentChannel, parentMessage) }
    }

    private fun ThreadSnapshot.withReplyParentMessage(
        parentChannelId: String,
        parentMessageId: String,
        parentMessage: Message?
    ): ThreadSnapshot {
        val parentChannel = ChannelId(parentChannelId)
        val targetMessageId = MessageId(parentMessageId)
        val existingParent =
            this.parentMessage?.takeIf { message -> message.matchesReplyOriginParent(parentChannel, targetMessageId) }
                ?: messages.firstOrNull { message -> message.matchesReplyOriginParent(parentChannel, targetMessageId) }
        if (existingParent != null) {
            return copy(parentMessage = existingParent)
        }
        return parentMessage
            ?.takeIf { message -> message.matchesReplyOriginParent(parentChannel, targetMessageId) }
            ?.let { parent -> copy(parentMessage = parent) }
            ?: this
    }

    private fun ThreadSnapshot.rememberReplyParentMessage(
        parentChannelId: String,
        parentMessageId: String,
        messageStoreScopeId: String?
    ): ThreadSnapshot {
        val parentChannel = ChannelId(parentChannelId)
        val targetMessageId = MessageId(parentMessageId)
        val candidate =
            parentMessage?.takeIf { message -> message.id == targetMessageId }
                ?: messages.firstOrNull { message -> message.id == targetMessageId }
                ?: return this
        if (candidate.matchesReplyOriginParent(parentChannel, targetMessageId)) {
            val scopeId = messageStoreScopeId ?: return this
            messageResolver.upsertScoped(scopeId, parentChannel, candidate)
        }
        return this
    }

    private fun Message.needsReplyOriginContextHydration(): Boolean =
        body.isBlank() && attachments.isEmpty()

    private fun KuiklyThreadMessageItem.needsReplyOriginContextHydration(): Boolean =
        body.isBlank() && attachments.isEmpty()

    private fun Message.matchesReplyOriginParent(
        parentChannelId: ChannelId,
        parentMessageId: MessageId
    ): Boolean =
        id == parentMessageId &&
            !needsReplyOriginContextHydration() &&
            (channelId == parentChannelId || threadId == ThreadId(parentChannelId.value))

    private fun ThreadSnapshot.hasRenderableReplyThreadMessages(
        basePayload: KuiklyThreadScreenPayload
    ): Boolean {
        val parentMessageId =
            basePayload.parentMessageId?.takeIf { it.isNotBlank() }
                ?: basePayload.originParentMessageId?.takeIf { it.isNotBlank() }
                ?: conversation.thread?.parentMessageId?.value?.takeIf { it.isNotBlank() }
        val replyThreadContentChannelId =
            basePayload.copy(
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                sourceThreadId =
                    basePayload.sourceThreadId
                        ?: conversation.thread?.streamChannelId?.value
                        ?: conversation.channelId?.value
                        ?: conversation.threadId.value,
                streamChannelId =
                    basePayload.streamChannelId
                        ?: conversation.thread?.streamChannelId?.value
                        ?: conversation.channelId?.value
                        ?: conversation.threadId.value
            ).replyThreadContentChannelId()
        return messages.any { message ->
            message.id.value != parentMessageId &&
                (replyThreadContentChannelId == null || message.belongsToReplyThreadContent(replyThreadContentChannelId))
        }
    }

    private suspend fun fetchResolvedReplyThreadSnapshot(
        session: StoredAuthSession,
        basePayload: KuiklyThreadScreenPayload,
        replyResolutionScope: ReplyResolutionScope
    ): DataResult<Pair<KuiklyThreadScreenPayload, ThreadSnapshot?>> {
        val resolvedBasePayload =
            when (
                val resolved =
                    createOrOpenReplyThreadStream(
                        basePayload = basePayload,
                        replyResolutionScope = replyResolutionScope
                    )
            ) {
                is DataResult.Content -> resolved.data
                is DataResult.Empty -> return resolved
                is DataResult.Error -> return resolved
                is DataResult.NoPermission -> return resolved
            }
        val resolvedThreadId =
            resolvedBasePayload.sourceThreadId?.takeIf { it.isNotBlank() }
                ?: resolvedBasePayload.streamChannelId?.takeIf { it.isNotBlank() }
                ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false))
        val resolvedResult =
            fetchThreadSnapshotFromRepository(
                fallbackCode = "thread_network",
                expectedProvenance = replyResolutionScope.provenance
            ) { repository ->
                val pageSize = threadPageSize(session.currentServerId)
                diagnosticsLogger(
                    "thread_page_size source=resolved_reply server=${session.currentServerId.orEmpty()} " +
                        "pageSize=$pageSize"
                )
                repository.getThread(
                    threadId = ThreadId(resolvedThreadId),
                    includeFollowedSummaries = false,
                    limit = pageSize
                )
            }
        return when (resolvedResult) {
            is DataResult.Content ->
                DataResult.Content(resolvedBasePayload to resolvedResult.data)
            is DataResult.Empty ->
                if (resolvedResult.reason == EmptyReason.NoMessages) {
                    DataResult.Content(resolvedBasePayload to null)
                } else {
                    resolvedResult
                }
            is DataResult.Error -> resolvedResult
            is DataResult.NoPermission -> resolvedResult
        }
    }

    private fun KuiklyThreadScreenPayload.toEmptyReplyThreadPayload(): KuiklyThreadScreenPayload =
        copy(
            status = "empty",
            messages = emptyList(),
            canLoadMoreBefore = false,
            canLoadMoreAfter = false,
            olderMessagesStatus = "idle",
            newerMessagesStatus = "idle",
            errorCode = null,
            emptyTitle = ThreadReplyEmptyTitle,
            emptyDetail = ThreadReplyEmptyDetail
        )

    private suspend fun fetchThreadSnapshotFromRepository(
        fallbackCode: String,
        expectedProvenance: ReplyCacheProvenance,
        allowCapturedScopeResultAfterProvenanceChange: Boolean = false,
        call: suspend (ConversationRepository) -> DataResult<ThreadSnapshot>
    ): DataResult<ThreadSnapshot> {
        val repository =
            conversationRepository
                ?: return DataResult.Error(ScreenError(code = fallbackCode, retryable = true))
        val result =
            try {
                call(repository)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return DataResult.Error(ScreenError(code = fallbackCode, retryable = true))
            }
        val detail = (result as? DataResult.Content)?.data?.canonicalConversationDetail
        if (detail != null) {
            if (!isCurrentReplyCacheProvenance(expectedProvenance)) {
                diagnosticsLogger(
                    "conversation_directory_canonical_drop reason=stale_producer " +
                        "serverId=${expectedProvenance.serverId} channelId=${detail.channelId?.value.orEmpty()}"
                )
            } else {
                try {
                    val published = canonicalConversationIdentityPublisher(expectedProvenance, detail)
                    diagnosticsLogger(
                        "conversation_directory_canonical_publish " +
                            "serverId=${expectedProvenance.serverId} channelId=${detail.channelId?.value.orEmpty()} " +
                            "accepted=$published"
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    diagnosticsLogger(
                        "conversation_directory_canonical_publish_failed " +
                            "serverId=${expectedProvenance.serverId} error=${throwable::class.simpleName.orEmpty()}"
                    )
                }
            }
        }
        if (
            !allowCapturedScopeResultAfterProvenanceChange &&
            !isCurrentReplyCacheProvenance(expectedProvenance)
        ) {
            return staleReplyCacheProvenanceResult()
        }
        return result
    }

    private suspend fun resolveConversationAccessFallback(
        basePayload: KuiklyThreadScreenPayload,
        session: StoredAuthSession
    ): ConversationAccessCandidate? {
        val home = currentHomeSnapshot(session) ?: return null
        return resolveConversationAccessFallback(basePayload, home)
    }

    private fun resolveConversationAccessFallback(
        basePayload: KuiklyThreadScreenPayload,
        home: HomeSnapshot
    ): ConversationAccessCandidate? {
        val lookupIds = basePayload.conversationAccessFallbackIds()
        if (lookupIds.isEmpty()) return null
        return home.toSharedConversationDirectoryState()
            .let { directory -> lookupIds.firstNotNullOfOrNull(directory::resolve) }
            ?.toConversationAccessCandidate()
            ?.takeIf { candidate -> basePayload.acceptsInitialConversationAccessCandidate(candidate) }
    }

    private suspend fun KuiklyThreadScreenPayload.withKnownMarkdownChannelNames(
        session: StoredAuthSession
    ): KuiklyThreadScreenPayload {
        val home = currentHomeSnapshot(session) ?: return this
        return withKnownMarkdownChannelNames(home)
    }

    private fun KuiklyThreadScreenPayload.withKnownMarkdownChannelNames(
        home: HomeSnapshot
    ): KuiklyThreadScreenPayload {
        val channelNames =
            home.channels
                .mapNotNull { conversation -> conversation.name.trim().takeIf { it.isNotBlank() } }
                .distinctBy { it.lowercase() }
        val knownMemberProfilesById = currentKnownMarkdownMemberProfilesById(home)
        val directMessagePeerNames =
            home.knownMarkdownDirectMessagePeerNames(knownMemberProfilesById)
        return if (
            (channelNames.isEmpty() || channelNames == knownMarkdownChannelNames) &&
            directMessagePeerNames == knownMarkdownDirectMessagePeerNames
        ) {
            this
        } else {
            copy(
                knownMarkdownChannelNames = channelNames.ifEmpty { knownMarkdownChannelNames },
                knownMarkdownDirectMessagePeerNames = directMessagePeerNames
            )
        }
    }

    private fun currentKnownMarkdownMemberProfilesById(home: HomeSnapshot): Map<String, MemberProfile> =
        serverMembersStore?.currentKnownMemberProfilesById(home.server.id).orEmpty()

    private suspend fun currentHomeSnapshot(session: StoredAuthSession): HomeSnapshot? {
        val serverId = session.currentServerId?.takeIf { it.isNotBlank() }?.let(::ServerId) ?: return null
        homeSnapshotProvider?.invoke(serverId)?.let { snapshot ->
            diagnosticsLogger("thread_home_context source=shared_home_store serverId=${serverId.value}")
            return snapshot
        }
        val repository = channelRepository ?: return null
        diagnosticsLogger("thread_home_context source=repository serverId=${serverId.value}")
        return (repository.getHome(serverId) as? DataResult.Content)?.data
    }

    private suspend fun KuiklyThreadScreenPayload.withSavedStatus(
        serverId: ServerId
    ): KuiklyThreadScreenPayload {
        val repository = savedStatusRepository ?: return this
        val messageIds =
            (messages.map { it.id } + listOfNotNull(originMessage?.id))
                .map { it.trim() }
                .filter { it.isNotBlank() }
                // Local sends (sending / failed / journal-restored) have no server row, so they can never be
                // saved, and their ids are not UUIDs: older servers fail the whole batch with a 500 (task #335).
                .filterNot { it.startsWith(ThreadTransientLocalSendIdPrefix) }
                .distinct()
                .map(::MessageId)
        if (messageIds.isEmpty()) return this
        val result =
            try {
                repository.checkSavedMessages(serverId, messageIds)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                diagnosticsLogger(
                    "thread_saved_status_refresh_failed errorType=${throwable.safeThreadDiagnosticsType()}"
                )
                return this
            }
        return when (result) {
            is DataResult.Content -> withSavedMessageIds(result.data.savedIds.map { it.value }.toSet())
            is DataResult.Empty -> this
            is DataResult.Error -> {
                diagnosticsLogger("thread_saved_status_refresh_failed result=error code=${result.error.code}")
                this
            }
            is DataResult.NoPermission -> {
                diagnosticsLogger("thread_saved_status_refresh_failed result=no_permission code=${result.blocker.code}")
                this
            }
        }
    }
}

private fun ThreadApiResult.Failure.toThreadDataError(fallbackCode: String): DataResult<Nothing> =
    toThreadAttachmentCommentsFailureProjection().let { projection ->
        when (code) {
            // Permission loss must surface as NoPermission, not a transient error:
            // downstream treats NoPermission as unrecoverable (e.g. task #562 drops
            // the first-frame cache candidate) while plain errors keep warm state.
            401 ->
                DataResult.NoPermission(
                    PermissionBlocker(
                        code = "auth_required",
                        requiredCapability = "auth_session",
                        serverMessage = projection.serverMessage
                    )
                )
            403 ->
                DataResult.NoPermission(
                    PermissionBlocker(
                        code = contractCode ?: "thread_forbidden",
                        requiredCapability = "thread_access",
                        serverMessage = projection.serverMessage
                    )
                )
            else ->
                DataResult.Error(
                    ScreenError(
                        code =
                            contractCode
                                ?: projection.errorCode.takeUnless { it == "http_$code" }
                                ?: fallbackCode,
                        retryable = contractCode == null,
                        serverMessage = projection.serverMessage
                    )
                )
        }
    }

private fun ThreadApiResult<Unit>.toUnitDataResult(fallbackCode: String): DataResult<Unit> =
    when (this) {
        is ThreadApiResult.Success -> DataResult.Content(Unit)
        is ThreadApiResult.Failure -> toThreadDataError(fallbackCode)
    }

internal object SharedThreadPaging {
    const val DefaultPageSize = 30
    const val MinimumPageSize = 10
    const val MaximumPageSize = 50

    fun resolve(candidate: Int?): Int =
        candidate?.takeIf { it in MinimumPageSize..MaximumPageSize } ?: DefaultPageSize
}

internal fun ServerFeatureFlagSnapshot.mobileThreadPageSize(): Int? =
    if (isEnabled(MOBILE_THREAD_PAGE_SIZE_FEATURE_FLAG_KEY)) {
        variant(MOBILE_THREAD_PAGE_SIZE_FEATURE_FLAG_KEY)?.trim()?.toIntOrNull()
    } else {
        null
    }
private const val SharedThreadFirstFrameDelayMillis = 16L
private const val ThreadVisibleReadCoalesceWindowMillis = 2_000L
private const val ThreadInitialAccessLookupTimeoutMillis = 1_000L

internal data class SharedThreadState(
    val payload: KuiklyThreadScreenPayload = KuiklyThreadScreenPayload(),
    val timelineSlice: ThreadTimelineSlice = payload.toThreadTimelineSlice(),
    val replyMetadataByParentId: Map<String, ThreadReplyMetadata> = payload.toThreadReplyMetadataMap(),
    val highlightedMentions: Set<String> = emptySet(),
    val accessResolution: ThreadAccessResolution = ThreadAccessResolution.Loading,
    val canonicalAccessResolved: Boolean = false,
    val initialHomeAccessLookupComplete: Boolean = false,
    val initialHomeAccessCandidate: ConversationAccessCandidate? = null,
    /**
     * task #326 diagnostics: result kind + EmptyReason of the last canonical
     * refresh, so a stuck "loading" shell can be told apart from a resolved-but-
     * empty thread without a repro bundle.
     */
    val lastRefreshResultReason: String? = null,
    val replyParentAuthority: ReplyParentAuthorityStatus = ReplyParentAuthorityStatus.Resolving,
    val writeActionsEnabled: Boolean = false,
    val channelProfileUpdateStatus: ChannelProfileUpdateStatus = ChannelProfileUpdateStatus.Idle,
    val channelProfileUpdateErrorCode: String? = null,
    val membersLoadStatus: LoadMembersStatus = LoadMembersStatus.Idle,
    /**
     * Key (typically source thread id) the [membersLoadStatus] is bound to.
     * Switching threads resets the status to Idle for the new key so we
     * never reuse a previous thread's "Loaded" verdict.
     */
    val membersLoadKey: String? = null
)

internal enum class ThreadAccessResolution {
    Loading,
    Resolved,
    Error,
    NoPermission
}

/**
 * Task #228 successor authority model for reply-thread parent write access.
 *
 * "The parent conversation cannot currently be proven writable" is a distinct
 * state from "the parent conversation is provably not writable". Collapsing
 * both into `joined=false` hid the composer permanently whenever the parent
 * directory lookup timed out. The composer stays disabled in every non-
 * [Writable] state; the difference is what the UI explains and whether
 * event-driven re-evaluation may still upgrade the state.
 */
internal enum class ReplyParentAuthorityStatus {
    /** Initial lookup still in flight. */
    Resolving,

    /** Parent directory/server attests joined + canSend. */
    Writable,

    /** Provably not writable: terminal NoPermission/Error or an accepted
     *  candidate that denies join/send. Only realtime membership changes to
     *  the parent row may reopen this; it never times out into Writable. */
    Denied,

    /** Lookup completed without denial and without a candidate: canonical
     *  content is readable but parent write authority is undetermined
     *  (legacy server without parentAccess, or a conversation-list miss).
     *  Event-driven re-evaluation upgrades this as soon as the parent row
     *  or a server-provided parentAccess fact arrives. */
    Unknown,

    /** Network-authoritative Home directory attests the parent row is gone:
     *  the channel was deleted. A later [ApplyParentDirectoryHint] with
     *  `present = true` (recreated/restored row) recomputes from here. */
    ParentDeleted,

}

internal enum class ChannelProfileUpdateStatus {
    Idle,
    InFlight,
    Succeeded,
    Failed
}

internal data class ThreadHomeContextResolution(
    val payload: KuiklyThreadScreenPayload,
    val accessCandidate: ConversationAccessCandidate? = null,
    val ownerCurrent: Boolean = true,
    val requiresBackgroundHomeContextHydration: Boolean = false,
)

/**
 * UI-owned projection that keeps access/composer facts and timeline load facts
 * in the same StateFlow emission. Collecting these fields independently can
 * briefly pair a resolved membership payload with a still-loading timeline (or
 * vice versa), which makes the footer and LazyColumn change on separate frames.
 */
internal data class SharedThreadRenderSlice(
    val payload: KuiklyThreadScreenPayload,
    val timelineSlice: ThreadTimelineSlice,
    val replyMetadataByParentId: Map<String, ThreadReplyMetadata>,
    val highlightedMentions: Set<String>,
    val accessResolution: ThreadAccessResolution,
    val canonicalAccessResolved: Boolean,
    val initialHomeAccessLookupComplete: Boolean,
    val lastRefreshResultReason: String?,
    val replyParentAuthority: ReplyParentAuthorityStatus,
    val writeActionsEnabled: Boolean,
    val channelProfileUpdateStatus: ChannelProfileUpdateStatus,
    val channelProfileUpdateErrorCode: String?
)

private fun SharedThreadState.toRenderSlice(): SharedThreadRenderSlice =
    SharedThreadRenderSlice(
        payload = payload.withoutThreadTimelineContent(),
        timelineSlice = timelineSlice,
        replyMetadataByParentId = replyMetadataByParentId,
        highlightedMentions = highlightedMentions,
        accessResolution = accessResolution,
        canonicalAccessResolved = canonicalAccessResolved,
        initialHomeAccessLookupComplete = initialHomeAccessLookupComplete,
        lastRefreshResultReason = lastRefreshResultReason,
        replyParentAuthority = replyParentAuthority,
        writeActionsEnabled = writeActionsEnabled,
        channelProfileUpdateStatus = channelProfileUpdateStatus,
        channelProfileUpdateErrorCode = channelProfileUpdateErrorCode
    )

/**
 * Per-thread members load status. Keyed by [SharedThreadState.membersLoadKey]
 * so that switching to another thread resets the loaded state instead of
 * re-using the previous thread's "Loaded" status.
 */
internal enum class LoadMembersStatus { Idle, InFlight, Loaded, Error }

private fun SharedThreadState.withSynchronizedTimelineSlice(): SharedThreadState {
    val nextTimelineSlice = payload.toThreadTimelineSlice()
    val nextReplyMetadataByParentId = payload.toThreadReplyMetadataMap()
    val timelineChanged = timelineSlice != nextTimelineSlice
    val replyMetadataChanged = replyMetadataByParentId != nextReplyMetadataByParentId
    return if (!timelineChanged && !replyMetadataChanged) {
        this
    } else {
        copy(
            timelineSlice = nextTimelineSlice,
            replyMetadataByParentId = nextReplyMetadataByParentId
        )
    }
}

private data class ThreadVisibleReadMarker(
    val channelId: String,
    val latestMessageId: String,
    val latestSeq: Long?
)

internal data class ThreadLocalSendComposerSeed(
    val sentBody: String,
    val sentAttachmentIds: Set<String>
)

internal sealed interface SharedThreadIntent {
    data class Start(val payload: KuiklyThreadScreenPayload) : SharedThreadIntent
    data class ApplyRouteIntent(val payload: KuiklyThreadScreenPayload) : SharedThreadIntent
    data object Refresh : SharedThreadIntent

    /**
     * Task #228 successor: the foreground Thread page observes the shared Home
     * directory (the same source the initial parent-authority lookup consults)
     * and forwards the row state for this reply thread's exact parent whenever
     * it appears or changes. Pure event-driven re-evaluation - no network, no
     * retry counter.
     */
    data class ApplyParentDirectoryHint(
        val parentChannelId: String,
        val parentMessageId: String,
        val present: Boolean,
        val joined: Boolean,
        val canSend: Boolean,
        /** True only when the hint's snapshot is network-authoritative
         *  (not a durable identity cache and not pending a refresh); a row
         *  absence read from such a snapshot means the channel is deleted. */
        val authoritative: Boolean = false,
    ) : SharedThreadIntent
    data object RetryAccessResolution : SharedThreadIntent
    data class AppendSentPage(val page: ThreadMessagesPage) : SharedThreadIntent
    data class ApplyLocalSendFailure(
        val messageId: String,
        val errorCode: String? = null,
        val errorMessage: String? = null,
        val expectedProvenance: ReplyCacheProvenance,
        val expectedRoute: ThreadRouteIdentity
    ) : SharedThreadIntent
    data class CancelLocalSend(val messageId: String) : SharedThreadIntent
    data class DismissLocalFailedSend(val messageId: String) : SharedThreadIntent
    data class ReplaceLocalSendWithSentPage(
        val messageId: String,
        val page: ThreadMessagesPage,
        val expectedProvenance: ReplyCacheProvenance,
        val expectedRoute: ThreadRouteIdentity
    ) : SharedThreadIntent
    data class ApplyMessageReactionSelection(
        val messageId: String,
        val emoji: String,
        val selected: Boolean
    ) : SharedThreadIntent
    data class ApplyMessageReactions(
        val messageId: String,
        val reactions: List<KuiklyThreadReactionItem>
    ) : SharedThreadIntent
    data class ApplyReplyThreadRead(
        val parentMessageId: String,
        val threadChannelId: String? = null
    ) : SharedThreadIntent
    data class ApplyMessageThreadFollowed(
        val parentMessageId: String,
        val threadChannelId: String? = null,
        val followed: Boolean,
        val replyCount: Int = 0
    ) : SharedThreadIntent
    data class ApplyMessageMutation(val summary: MessageMutationSummary) : SharedThreadIntent
    data class ApplyActionCard(val messageId: String, val card: ThreadActionCard) : SharedThreadIntent
    data class ApplyMessageSaved(val messageId: String, val saved: Boolean) : SharedThreadIntent
    data class ApplyChannelJoined(val channelId: String) : SharedThreadIntent
    data class UpdateChannelProfile(
        val channelId: String,
        val originalName: String,
        val originalDescription: String,
        val name: String,
        val description: String
    ) : SharedThreadIntent
    data object ClearChannelProfileUpdateStatus : SharedThreadIntent
    data class SelectTab(val tab: String) : SharedThreadIntent
    data class FocusMessage(val messageId: String, val switchToTab: String? = null) : SharedThreadIntent
    data class ApplyMembersPatch(
        val membersCount: Int,
        val members: List<KuiklyThreadMemberItem>,
        val memberCandidates: List<KuiklyThreadMemberItem>,
        val canAddMembers: Boolean,
        val addMemberStatus: String = "idle",
        val addMemberErrorCode: String? = null,
        val removeMemberStatus: String = "idle",
        val removingMemberId: String? = null,
        val removeMemberErrorCode: String? = null
    ) : SharedThreadIntent
    data class SetAddMemberStatus(val status: String, val errorCode: String? = null) : SharedThreadIntent
    data class SetRemoveMemberStatus(
        val status: String,
        val removingMemberId: String?,
        val errorCode: String? = null
    ) : SharedThreadIntent
    data class SetPagingAvailability(
        val canLoadMoreBefore: Boolean,
        val canLoadMoreAfter: Boolean
    ) : SharedThreadIntent
    data class ApplyRealtimeEvent(val event: RealtimeEvent, val activeReplyParentMessageId: MessageId? = null) :
        SharedThreadIntent
    data class MarkVisibleRead(val visibleTailIndex: Int? = null) : SharedThreadIntent
    data object FirstContentReady : SharedThreadIntent
    data object LoadMembers : SharedThreadIntent
    data object LoadOlder : SharedThreadIntent
    data object LoadNewer : SharedThreadIntent
    data object RefreshLatestAfterVisibleMaxSeq : SharedThreadIntent
    data class RecoverRealtimeGap(val serverSeq: Long) : SharedThreadIntent
}

internal class SharedThreadStore(
    private val dataSource: SharedThreadDataSource,
    private val scope: CoroutineScope,
    private val diagnosticsLogger: ((String) -> Unit)? = null,
    initialPayload: KuiklyThreadScreenPayload = KuiklyThreadScreenPayload(),
    private val beforeFocusedOriginCommit: (suspend (KuiklyThreadScreenPayload) -> Unit)? = null,
    private val afterRefreshLoadingStateApplied: (suspend (KuiklyThreadScreenPayload) -> Unit)? = null,
    private val threadRepliesSyncHost: ThreadRepliesSyncHost = ThreadRepliesSyncHost(),
    // task #243: the initial-access lookup deadline as an injectable SIGNAL rather than a bare
    // duration. Production keeps the wall clock; a test can hand in a barrier it completes
    // itself, so the timeout branch is reached because the test said so - not because a second
    // of real time happened to elapse on a loaded runner. A shorter duration was deliberately
    // NOT used: that shrinks the flake window instead of removing the nondeterminism.
    // ⚠️ Invoked once per resolution attempt (launchInitialAccessResolution cancels and relaunches),
    // so an injected implementation must be safe to call repeatedly. A one-shot CompletableDeferred
    // parks the first attempt and then completes instantly for every attempt after it.
    private val initialAccessLookupDeadline: suspend () -> Unit = {
        delay(ThreadInitialAccessLookupTimeoutMillis)
    },
) {
    private val readStateProjection = ThreadReadStateProjection()
    private val _state = MutableStateFlow(SharedThreadState(payload = initialPayload))
    val state: StateFlow<SharedThreadState> = _state.asStateFlow()
    fun payloadFlow(): Flow<KuiklyThreadScreenPayload> =
        state
            .map { threadState -> threadState.payload.withoutThreadTimelineContent() }
            .distinctUntilChanged()

    fun renderSliceFlow(): Flow<SharedThreadRenderSlice> =
        state
            .map { threadState -> threadState.toRenderSlice() }
            .distinctUntilChanged()

    fun timelineSliceFlow(): Flow<ThreadTimelineSlice> =
        state
            .map { threadState -> threadState.timelineSlice }
            .distinctUntilChanged()

    fun replyMetadataFlow(): Flow<Map<String, ThreadReplyMetadata>> =
        state
            .map { threadState -> threadState.replyMetadataByParentId }
            .distinctUntilChanged()

    fun highlightedMentionsFlow(): Flow<Set<String>> =
        state
            .map { threadState -> threadState.highlightedMentions }
            .distinctUntilChanged()

    // Event-driven drainer for durably journaled pending sends. Resends reuse the journaled
    // randomId so a request that committed server-side before its response was lost is deduped
    // by the server rather than duplicated; row fencing stays with the journal's exact
    // provenance/route partition.
    private val pendingSendDrainer: ThreadPendingSendDrainer? =
        dataSource.pendingSendJournalOrNull()?.let { journal ->
            ThreadPendingSendDrainer(
                drainStore = ThreadPendingSendJournalDrainStore(journal),
                scope = scope,
                provenanceResolver = { dataSource.currentReplyCacheProvenance() },
                routeResolver = { _state.value.payload.resolveThreadRouteIdentity() },
                send = { context ->
                    dataSource.sendMessage(
                        context.channelId,
                        context.body,
                        context.asTask,
                        context.attachmentIds,
                        context.randomId,
                    )
                },
                onSent = { localId, page, provenance, route ->
                    accept(
                        SharedThreadIntent.ReplaceLocalSendWithSentPage(
                            localId,
                            page,
                            provenance,
                            route,
                        )
                    )
                },
                onRowFailed = { localId, provenance, route, errorCode, errorMessage ->
                    emitLocalSendFailureProjection(
                        messageId = localId,
                        errorCode = errorCode,
                        errorMessage = errorMessage,
                        expectedProvenance = provenance,
                        expectedRoute = route,
                    )
                },
                logger = { message -> diagnosticsLogger?.invoke(message) },
            )
        }

    internal fun triggerPendingSendDrain(reason: String) {
        pendingSendDrainer?.trigger(reason)
    }

    private var loadJob: Job? = null
    private var enteredScopeSnapshotJob: Job? = null
    private var secondaryTimelineRefreshJob: Job? = null
    private var focusedOriginHydrationJob: Job? = null
    private var initialAccessResolutionJob: Job? = null
    private var homeContextHydrationJob: Job? = null
    private var backgroundMembersHydrationJob: Job? = null
    private var readInFlightMarker: ThreadVisibleReadMarker? = null
    private var pendingReadMarker: ThreadVisibleReadMarker? = null
    private var lastCompletedReadMarker: ThreadVisibleReadMarker? = null
    private var lastCompletedReadAt: TimeMark? = null
    private var lastRoutePayload: KuiklyThreadScreenPayload? = null
    private var visiblePayloadOwnerScopeId: String? = null
    private var firstContentReadyRouteKey: String? = null
    private var pendingAuxiliaryHydration: PendingAuxiliaryHydration? = null
    private var pendingSecondaryTimelineApply: PendingSecondaryTimelineApply? = null
    private var routeStaleToken = MviStaleToken.Initial
    private var routeStaleTokenValue = MviStaleToken.Initial.value
    private var nextThreadRepliesRebaselineGeneration: Long = 0L
    private val initialAccessStateMutex = Mutex()
    private val realtimeGapRecoveryMutex = Mutex()
    private var realtimeGapRecoveryState: RealtimeGapRecoveryState? = null
    private var loadNewerState: LoadNewerState? = null
    private var nextRealtimeGapRecoveryId: Long = 0L
    private var nextLoadNewerId: Long = 0L
    private val pendingReactionRealtimeGuards = mutableMapOf<String, PendingReactionRealtimeGuard>()

    private data class RealtimeGapRecoveryState(
        val id: Long,
        val routeIdentity: ThreadRouteIdentity,
        val staleToken: MviStaleToken,
        var targetSeq: Long,
        var targetVersion: Long,
        /**
         * Reply-thread only: the server-authoritative replyCount that triggered
         * this recovery. When the delta `messageSync` cannot reach replies
         * stranded below the visible tail seq, the recovery falls back to a
         * full tail refetch until `loadedReplyTimelineCount()` reaches it.
         */
        var replyCountTarget: Int? = null,
        /**
         * Set by a foreground resume (ad1df6b7): re-read the loaded parents' reply summaries after the
         * recovery even when it merged nothing. While the app was in the background the socket was down,
         * so replies posted meanwhile reached neither `ThreadUpdated` nor the top-level `messageSync`.
         */
        var backfillAfterRecovery: Boolean = false,
        var replyCountTailFallbackUsed: Boolean = false,
    )

    private data class LoadNewerState(
        val id: Long,
        val routeIdentity: ThreadRouteIdentity,
        val staleToken: MviStaleToken,
        var pendingTargetSeq: Long? = null,
    )

    private data class PendingAuxiliaryHydration(
        val routePayload: KuiklyThreadScreenPayload,
        val staleToken: MviStaleToken,
        val launchSecondaryTimelineRefresh: Boolean,
        val launchMembersHydration: Boolean
    )

    private data class PendingSecondaryTimelineApply(
        val routePayload: KuiklyThreadScreenPayload,
        val staleToken: MviStaleToken,
        val apply: suspend () -> Unit,
    )

    private fun KuiklyThreadScreenPayload.realtimeGapRouteIdentity(): ThreadRouteIdentity =
        resolveThreadRouteIdentity()

    private data class RealtimeEventDecision(
        val source: String,
        val emitted: Boolean = false,
        val messageCount: Int = 0,
        val recoveryId: Long? = null,
        val recoveryInitial: KuiklyThreadScreenPayload? = null,
        val recoveryRequest: RealtimeVisibleStreamGapRequest? = null,
        val replyCountGap: ReplyCountRealtimeGap? = null,
        val dropped: Boolean = false,
    )

    fun dispatch(intent: SharedThreadIntent) {
        when (intent) {
            is SharedThreadIntent.Start,
            SharedThreadIntent.Refresh -> {
                loadJob?.cancel()
                if (intent is SharedThreadIntent.Start) {
                    initialAccessResolutionJob?.cancel()
                    initialAccessResolutionJob = null
                }
                secondaryTimelineRefreshJob?.cancel()
                secondaryTimelineRefreshJob = null
                pendingSecondaryTimelineApply = null
                focusedOriginHydrationJob?.cancel()
                focusedOriginHydrationJob = null
                homeContextHydrationJob?.cancel()
                homeContextHydrationJob = null
                backgroundMembersHydrationJob?.cancel()
                backgroundMembersHydrationJob = null
                pendingReactionRealtimeGuards.clear()
                loadJob =
                    scope.launch {
                        accept(intent)
                    }
            }
            SharedThreadIntent.RetryAccessResolution,
            is SharedThreadIntent.ApplyParentDirectoryHint -> {
                scope.launch { accept(intent) }
            }
            is SharedThreadIntent.AppendSentPage,
            is SharedThreadIntent.ApplyRouteIntent,
            is SharedThreadIntent.ApplyLocalSendFailure,
            is SharedThreadIntent.CancelLocalSend,
            is SharedThreadIntent.DismissLocalFailedSend,
            is SharedThreadIntent.ReplaceLocalSendWithSentPage,
            is SharedThreadIntent.ApplyMessageReactionSelection,
            is SharedThreadIntent.ApplyMessageReactions,
            is SharedThreadIntent.ApplyReplyThreadRead,
            is SharedThreadIntent.ApplyMessageThreadFollowed,
            is SharedThreadIntent.ApplyMessageMutation,
            is SharedThreadIntent.ApplyActionCard,
            is SharedThreadIntent.ApplyMessageSaved,
            is SharedThreadIntent.ApplyChannelJoined,
            is SharedThreadIntent.UpdateChannelProfile,
            SharedThreadIntent.ClearChannelProfileUpdateStatus,
            is SharedThreadIntent.SelectTab,
            is SharedThreadIntent.FocusMessage,
            is SharedThreadIntent.ApplyMembersPatch,
            is SharedThreadIntent.SetAddMemberStatus,
            is SharedThreadIntent.SetRemoveMemberStatus,
            is SharedThreadIntent.SetPagingAvailability ->
                scope.launch { accept(intent) }
            is SharedThreadIntent.ApplyRealtimeEvent -> {
                // An accepted realtime frame (even one later dropped as stale) is an
                // authoritative online witness: let the pending-send drainer re-evaluate
                // journaled rows. Throttled inside the drainer.
                pendingSendDrainer?.onAuthoritativeOnline()
                scope.launch {
                    applyRealtimeEvent(
                        event = intent.event,
                        activeReplyParentMessageId = intent.activeReplyParentMessageId
                    )
                }
            }
            is SharedThreadIntent.MarkVisibleRead -> {
                scope.launch { markVisibleRead(intent.visibleTailIndex) }
            }
            SharedThreadIntent.FirstContentReady -> {
                scope.launch { markFirstContentReady() }
            }
            SharedThreadIntent.LoadMembers -> {
                scope.launch { loadMembers() }
            }
            SharedThreadIntent.LoadOlder -> {
                scope.launch { loadOlder() }
            }
            SharedThreadIntent.LoadNewer -> {
                scope.launch { loadNewer() }
            }
            SharedThreadIntent.RefreshLatestAfterVisibleMaxSeq -> {
                scope.launch { refreshLatestAfterVisibleMaxSeq() }
            }
            is SharedThreadIntent.RecoverRealtimeGap -> {
                scope.launch { recoverRealtimeGap(intent.serverSeq) }
            }
        }
    }

    suspend fun accept(intent: SharedThreadIntent) {
        when (intent) {
            is SharedThreadIntent.Start -> start(intent.payload)
            is SharedThreadIntent.ApplyRouteIntent -> applyRouteIntent(intent.payload)
            SharedThreadIntent.Refresh ->
                refresh(
                    lastRoutePayload ?: _state.value.payload,
                    advanceRouteStaleToken(),
                    explicitRetry = true
                )
            SharedThreadIntent.RetryAccessResolution -> retryAccessResolution()
            is SharedThreadIntent.AppendSentPage -> appendSentPage(intent.page)
            is SharedThreadIntent.ApplyLocalSendFailure ->
                applyLocalSendFailure(
                    intent.messageId,
                    intent.errorCode,
                    intent.errorMessage,
                    intent.expectedProvenance,
                    intent.expectedRoute
                )
            is SharedThreadIntent.CancelLocalSend -> cancelLocalSend(intent.messageId)
            is SharedThreadIntent.DismissLocalFailedSend -> dismissLocalFailedSend(intent.messageId)
            is SharedThreadIntent.ReplaceLocalSendWithSentPage ->
                replaceLocalSendWithSentPage(
                    intent.messageId,
                    intent.page,
                    intent.expectedProvenance,
                    intent.expectedRoute
                )
            is SharedThreadIntent.ApplyMessageReactionSelection ->
                applyMessageReactionSelection(intent.messageId, intent.emoji, intent.selected)
            is SharedThreadIntent.ApplyMessageReactions ->
                applyMessageReactions(intent.messageId, intent.reactions)
            is SharedThreadIntent.ApplyReplyThreadRead ->
                applyReplyThreadRead(intent.parentMessageId, intent.threadChannelId)
            is SharedThreadIntent.ApplyMessageThreadFollowed ->
                applyMessageThreadFollowed(intent.parentMessageId, intent.threadChannelId, intent.followed, intent.replyCount)
            is SharedThreadIntent.ApplyMessageMutation -> applyMessageMutation(intent.summary)
            is SharedThreadIntent.ApplyActionCard -> applyActionCard(intent.messageId, intent.card)
            is SharedThreadIntent.ApplyMessageSaved -> applyMessageSaved(intent.messageId, intent.saved)
            is SharedThreadIntent.ApplyChannelJoined -> applyChannelJoined(intent.channelId)
            is SharedThreadIntent.ApplyParentDirectoryHint -> applyParentDirectoryHint(intent)
            is SharedThreadIntent.UpdateChannelProfile -> updateChannelProfile(intent)
            SharedThreadIntent.ClearChannelProfileUpdateStatus -> clearChannelProfileUpdateStatus()
            is SharedThreadIntent.SelectTab -> selectTab(intent.tab)
            is SharedThreadIntent.FocusMessage -> focusMessage(intent.messageId, intent.switchToTab)
            is SharedThreadIntent.ApplyMembersPatch -> applyMembersPatch(intent)
            is SharedThreadIntent.SetAddMemberStatus -> setAddMemberStatus(intent.status, intent.errorCode)
            is SharedThreadIntent.SetRemoveMemberStatus ->
                setRemoveMemberStatus(intent.status, intent.removingMemberId, intent.errorCode)
            is SharedThreadIntent.SetPagingAvailability ->
                setPagingAvailability(intent.canLoadMoreBefore, intent.canLoadMoreAfter)
            is SharedThreadIntent.ApplyRealtimeEvent ->
                applyRealtimeEvent(
                    event = intent.event,
                    activeReplyParentMessageId = intent.activeReplyParentMessageId
                )
            is SharedThreadIntent.MarkVisibleRead -> markVisibleRead(intent.visibleTailIndex)
            SharedThreadIntent.FirstContentReady -> markFirstContentReady()
            SharedThreadIntent.LoadMembers -> loadMembers()
            SharedThreadIntent.LoadOlder -> loadOlder()
            SharedThreadIntent.LoadNewer -> loadNewer()
            SharedThreadIntent.RefreshLatestAfterVisibleMaxSeq -> refreshLatestAfterVisibleMaxSeq()
            is SharedThreadIntent.RecoverRealtimeGap -> recoverRealtimeGap(intent.serverSeq)
        }
    }

    fun clear() {
        loadJob?.cancel()
        loadJob = null
        secondaryTimelineRefreshJob?.cancel()
        secondaryTimelineRefreshJob = null
        focusedOriginHydrationJob?.cancel()
        focusedOriginHydrationJob = null
        initialAccessResolutionJob?.cancel()
        initialAccessResolutionJob = null
        homeContextHydrationJob?.cancel()
        homeContextHydrationJob = null
        backgroundMembersHydrationJob?.cancel()
        backgroundMembersHydrationJob = null
        lastRoutePayload = null
        firstContentReadyRouteKey = null
        pendingAuxiliaryHydration = null
        pendingSecondaryTimelineApply = null
        advanceRouteStaleToken()
        reducePayloadPatch(cachePayload = true) { currentPayload ->
            if (currentPayload.newerMessagesStatus == "loading") {
                currentPayload.copy(newerMessagesStatus = "idle")
            } else {
                currentPayload
            }
        }
        visiblePayloadOwnerScopeId = null
        readInFlightMarker = null
        pendingReadMarker = null
        lastCompletedReadMarker = null
        lastCompletedReadAt = null
    }

    private suspend fun start(initialPayload: KuiklyThreadScreenPayload) {
        focusedOriginHydrationJob?.cancel()
        focusedOriginHydrationJob = null
        val producerContext = dataSource.captureReplyCacheProducerContext()
        val session = producerContext?.session
        val payloadOwnerScopeId = producerContext?.provenance?.threadPayloadOwnerScopeId
        val routeCandidatePayload = initialPayload.withoutRouteCandidateContent()
        val routeContextPayload =
            (producerContext
                ?.let { context ->
                    dataSource.withCachedReplyParentCandidate(routeCandidatePayload, context)
                }
                ?: routeCandidatePayload)
                .withoutTransientLocalSendingMessages()
        val rememberedAccessFailure =
            producerContext?.let { context ->
                dataSource.rememberedReplyThreadAccessFailure(routeContextPayload, context)
            }
        val staleToken = advanceRouteStaleToken()
        lastRoutePayload = routeContextPayload
        firstContentReadyRouteKey = null
        pendingAuxiliaryHydration = null
        pendingSecondaryTimelineApply = null
        val routeIdentity = routeContextPayload.resolveThreadRouteIdentity()
        val threadId = routeIdentity.timelineKey
        dataSource.onReplyCacheFirstFrameAdmissionForTest?.invoke()
        val cached =
            threadId
                ?.let { candidateThreadId ->
                    producerContext?.let { context ->
                        dataSource.cachedPayloadIfCurrent(candidateThreadId, context.provenance)
                    }
                }
                ?.takeIf { routeIdentity.canRenderTimelineCandidates }
                ?.takeIf { it.isUsableCachedPayloadForRoute(routeContextPayload) }
                ?.withRouteContext(routeContextPayload)
                ?.withStoreResolvedReplyOriginFrom(routeContextPayload)
        // A thread payload cache survives independently from the process-local member profile
        // projection. Re-seed an accepted cache hit before publishing the first frame so a server
        // switch or process restore cannot render complete history with placeholder-only avatars.
        cached?.let { accepted ->
            dataSource.seedThreadAuthorProfiles(
                serverId = session?.currentServerId,
                payload = accepted,
                source = "visible_cache"
            )
        }
        val current =
            if (
                routeIdentity.canRenderTimelineCandidates &&
                    payloadOwnerScopeId != null &&
                    visiblePayloadOwnerScopeId == payloadOwnerScopeId
            ) {
                _state.value.payload.takeIf {
                    it.isUsableCachedPayloadForRoute(routeContextPayload) && it.hasRenderableThreadContent()
                }?.withRouteContext(routeContextPayload)
                    ?.withStoreResolvedReplyOriginFrom(routeContextPayload)
            } else {
                null
            }
        // Offline cache read entry (task #1123): only when no in-memory candidate exists does the
        // durable last-known frame stand in for the loading shell. Memory candidates are newer by
        // construction (they were written by a network response in this process).
        val durableLastKnown =
            if (cached == null && current == null && threadId != null) {
                producerContext?.let { context ->
                    dataSource.durableLastKnownPayloadIfCurrent(
                        routePayload = routeContextPayload,
                        routeIdentity = routeIdentity,
                        context = context,
                    )
                }
            } else {
                null
            }
        durableLastKnown?.let { accepted ->
            dataSource.seedThreadAuthorProfiles(
                serverId = session?.currentServerId,
                payload = accepted,
                source = "durable_last_known"
            )
        }
        diagnosticsLogger?.invoke(
            "thread_start threadId=${threadId.orEmpty()} cachedMemory=${cached != null || current != null} " +
                "durableLastKnown=${durableLastKnown != null} " +
                "status=${routeContextPayload.status} messages=${routeContextPayload.messages.size} " +
                routeContextPayload.originDiagnostics()
        )
        val visiblePayload =
            (cached
                ?: current
                ?: durableLastKnown
                ?: if (threadId == null) {
                    routeContextPayload
                } else {
                    routeContextPayload.copy(status = "loading", errorCode = null)
                }).let { candidate ->
                rememberedAccessFailure?.let { failure ->
                    candidate
                        .asRefreshFailed(failure.code)
                        .withTerminalAccessWriteAuthorityClosed(ThreadAccessResolution.Error)
                } ?: candidate
            }.let { candidate ->
                producerContext?.let { context ->
                    dataSource.restorePendingLocalSends(
                        provenance = context.provenance,
                        route = routeIdentity,
                        canonicalPayload = candidate,
                    )
                } ?: candidate
            }
        diagnosticsLogger?.invoke(
            "thread_visible_payload threadId=${threadId.orEmpty()} status=${visiblePayload.status} " +
                "messages=${visiblePayload.messages.size} " + visiblePayload.originDiagnostics()
        )
        initialAccessStateMutex.withLock {
            val admitted =
                producerContext?.let { context ->
                    dataSource.withCurrentReplyCacheProvenance(context.provenance) {
                        visiblePayloadOwnerScopeId = payloadOwnerScopeId
                        reduceState(
                            SharedThreadState(
                                payload = visiblePayload,
                                highlightedMentions = emptySet(),
                                accessResolution =
                                    if (rememberedAccessFailure == null) {
                                        ThreadAccessResolution.Loading
                                    } else {
                                        ThreadAccessResolution.Error
                                    },
                                canonicalAccessResolved = false,
                                initialHomeAccessLookupComplete = false,
                                initialHomeAccessCandidate = null
                            )
                        )
                    }
            }
            if (admitted == null) {
                visiblePayloadOwnerScopeId =
                    dataSource.currentReplyCacheProvenance()?.threadPayloadOwnerScopeId
                reduceState(
                    SharedThreadState(
                        payload =
                            if (threadId == null) {
                                routeContextPayload
                            } else {
                                routeContextPayload.copy(
                                    status = "loading",
                                    messages = emptyList(),
                                    backgroundRefreshing = false,
                                    errorCode = null
                                )
                            },
                        highlightedMentions = emptySet(),
                        accessResolution = ThreadAccessResolution.Loading,
                        canonicalAccessResolved = false,
                        initialHomeAccessLookupComplete = false,
                        initialHomeAccessCandidate = null,
                        writeActionsEnabled = false
                    )
                )
            }
        }
        // Offline cache write entry (task #1124): every entry of a channel / DM refreshes that one
        // channel's durable baseline in the background, independent of what the first frame was
        // (memory cache, durable frame, or loading shell). Fire-and-forget on the store scope: it
        // never touches the visible payload, and closing the screen cancels it.
        producerContext?.let { context ->
            enteredScopeSnapshotJob?.cancel()
            enteredScopeSnapshotJob =
                scope.launch { dataSource.loadEnteredScopeSnapshotIfCurrent(routeIdentity, context) }
        }
        delay(SharedThreadFirstFrameDelayMillis)
        if (
            staleTokenStillCurrent(staleToken, "thread_start", "highlight_mentions", routeContextPayload) &&
            (threadId == null || _state.value.payload.sourceThreadId == threadId) &&
            producerContext?.provenance?.let(dataSource::isCurrentReplyCacheProvenance) == true
        ) {
            reduceState(_state.value.copy(highlightedMentions = session.threadSelfMentionNames()))
        }
        // Process restart / page entry: adopt journaled sends whose owning attempt died and
        // resume automatic retries. Fire-and-forget; the drainer no-ops until provenance and a
        // send-valid route are available.
        pendingSendDrainer?.onStoreStarted()
        if (rememberedAccessFailure != null) {
            diagnosticsLogger?.invoke(
                "thread_start_access_failure_reused route=${routeIdentity.instanceKey} " +
                    "code=${rememberedAccessFailure.code}"
            )
            return
        }
        refresh(routeContextPayload, staleToken, reusePrewarm = true)
    }

    private fun applyRouteIntent(routePayload: KuiklyThreadScreenPayload) {
        val routeCandidatePayload = routePayload.withoutRouteCandidateContent()
        val currentPayload = _state.value.payload
        val currentIdentity = currentPayload.resolveThreadRouteIdentity()
        val incomingIdentity = routeCandidatePayload.resolveThreadRouteIdentity()
        if (!currentIdentity.canRetainThreadPageFor(incomingIdentity)) {
            diagnosticsLogger?.invoke(
                "thread_route_intent_drop reason=route_changed " +
                    "current=${currentIdentity.instanceKey} incoming=${incomingIdentity.instanceKey}"
            )
            return
        }
        // Same-identity intent updates (tab/focus/nonce) do not invalidate the
        // in-flight canonical content/access generation. Content projection
        // deliberately does not own those intent fields, so the latest intent
        // remains intact when the current generation completes.
        lastRoutePayload =
            (lastRoutePayload ?: currentPayload)
                .withRouteIntentFrom(routeCandidatePayload)
                .withoutTransientLocalSendingMessages()
        reducePayloadPatch { currentPayload ->
            currentPayload.withRouteIntentFrom(routeCandidatePayload)
        }
    }

    private suspend fun captureThreadRepliesPageIdentity(
        payload: KuiklyThreadScreenPayload,
    ): ThreadRepliesPageIdentity? {
        val context = dataSource.captureReplyCacheProducerContext() ?: return null
        return captureThreadRepliesPageIdentity(payload, context)
    }

    private fun captureThreadRepliesPageIdentity(
        payload: KuiklyThreadScreenPayload,
        context: ReplyCacheProducerContext,
    ): ThreadRepliesPageIdentity? {
        val parentScopeId =
            if (payload.surfaceKind == KuiklyThreadSurfaceKind.ReplyThread) {
                payload.parentChannelId
            } else {
                payload.sourceThreadId ?: payload.streamChannelId
            }?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val parentScopeKind =
            if (payload.surfaceKind == KuiklyThreadSurfaceKind.ReplyThread) {
                payload.parentChannelKind ?: payload.parentSurfaceKind
            } else {
                payload.channelKind.ifBlank { payload.surfaceKind }
            }.toThreadRepliesParentScopeKind()
        return ThreadRepliesPageIdentity(
            serverId = context.provenance.serverId,
            principalId = context.provenance.principalId,
            parentScopeKind = parentScopeKind,
            parentScopeId = parentScopeId,
        ).takeIf(ThreadRepliesPageIdentity::isValid)
    }

    private fun KuiklyThreadScreenPayload.withAcceptedThreadRepliesRebaseline(
        previous: KuiklyThreadScreenPayload,
        page: ThreadRepliesPageIdentity?,
        generation: Long?,
        expectedProvenance: ReplyCacheProvenance? = null,
    ): KuiklyThreadScreenPayload {
        val authorityAvailable =
            replySummariesAuthority == build.raft.app.data.ThreadReplySummariesAuthority.Embedded ||
                replySummariesAuthority == build.raft.app.data.ThreadReplySummariesAuthority.Targeted
        if (page == null || generation == null || !authorityAvailable) {
            if (page != null && generation != null) {
                threadRepliesSyncHost.abortAuthoritativeRebaseline(page, generation)
            }
            return withAcceptedThreadRepliesProjection(emptyMap(), previous)
                .copy(replySummariesAuthority = previous.replySummariesAuthority)
        }
        val previousById =
            (previous.messages + listOfNotNull(previous.originMessage)).associateBy { it.id }
        val snapshots =
            (messages + listOfNotNull(originMessage)).mapNotNull { message ->
                val previousMessage = previousById[message.id]
                val threadChannelId =
                    message.threadChannelId?.trim()?.takeIf { it.isNotBlank() }
                        ?: previousMessage?.threadChannelId?.trim()?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                val model = message.inlineReplies
                when {
                    model != null ->
                        ThreadRepliesAuthoritativeSnapshot(
                            identity =
                                ThreadRepliesScopeIdentity(
                                    page = page,
                                    parentMessageId = message.id,
                                    threadChannelId = threadChannelId,
                                ),
                            replies = model.replies,
                            replyCount = model.replyCount,
                        )
                    message.replyCount <= 0 ->
                        ThreadRepliesAuthoritativeSnapshot(
                            identity =
                                ThreadRepliesScopeIdentity(
                                    page = page,
                                    parentMessageId = message.id,
                                    threadChannelId = threadChannelId,
                                ),
                            replies = emptyList(),
                            replyCount = 0,
                        )
                    else -> null // malformed/missing summary for a positive-count parent: fail closed per scope
                }
            }
        val accepted =
            threadRepliesSyncHost.ingestAuthoritativeSnapshots(
                page = page,
                generation = generation,
                snapshots = snapshots,
                withCommitAdmission =
                    expectedProvenance?.let { provenance ->
                        { commit ->
                            dataSource.withCurrentReplyCacheProvenanceForLocalSend(provenance) {
                                commit()
                            }
                        }
                    },
            )
                ?: return withAcceptedThreadRepliesProjection(emptyMap(), previous)
                    .copy(replySummariesAuthority = previous.replySummariesAuthority)
        return withAcceptedThreadRepliesProjection(accepted, previous)
            .copy(replySummariesAuthority = previous.replySummariesAuthority)
    }

    private suspend fun refresh(
        basePayload: KuiklyThreadScreenPayload,
        staleToken: MviStaleToken = routeStaleToken,
        reusePrewarm: Boolean = false,
        explicitRetry: Boolean = false
    ) {
        val refreshStartedAt = TimeSource.Monotonic.markNow()
        val refreshProducerContext = dataSource.captureReplyCacheProducerContext() ?: return
        val refreshProvenance = refreshProducerContext.provenance
        if (
            explicitRetry &&
            !dataSource.clearRememberedReplyThreadAccessFailure(basePayload, refreshProducerContext)
        ) {
            return
        }
        var refreshStartState = _state.value
        val resolvingInitialAccessAtStart =
            refreshStartState.accessResolution != ThreadAccessResolution.Resolved
        val retryingReadOnlyParentAccess =
            refreshStartState.accessResolution == ThreadAccessResolution.Resolved &&
                !refreshStartState.writeActionsEnabled
        if (resolvingInitialAccessAtStart) {
            initialAccessStateMutex.withLock {
                val admitted =
                    dataSource.withCurrentReplyCacheProvenanceForLocalSend(refreshProvenance) {
                        val latest = _state.value
                        reduceState(
                            latest.copy(
                                accessResolution = ThreadAccessResolution.Loading,
                                canonicalAccessResolved = false,
                                initialHomeAccessLookupComplete = false,
                                initialHomeAccessCandidate = null
                            )
                        )
                        refreshStartState = _state.value
                    }
                if (admitted == null) {
                    return
                }
            }
            if (!dataSource.isCurrentReplyCacheProvenanceForLocalSend(refreshProvenance)) return
            launchInitialAccessResolution(basePayload, staleToken)
        } else if (retryingReadOnlyParentAccess) {
            // A readable reply timeline must stay terminal while Refresh retries
            // its auxiliary parent capability. Resetting canonical access here
            // would let a transient reply refresh error cover preserved history.
            launchInitialAccessResolution(basePayload, staleToken)
        }
        val existing = refreshStartState.payload
        if (!routeStillCurrent(basePayload, "thread_refresh", "start", existing)) {
            return
        }
        val refreshObservedMessageIds = existing.messages.mapTo(mutableSetOf()) { message -> message.id }
        val repliesPageIdentity = captureThreadRepliesPageIdentity(basePayload, refreshProducerContext)
        val repliesRebaselineGeneration =
            repliesPageIdentity?.let { page ->
                val generation = ++nextThreadRepliesRebaselineGeneration
                generation.takeIf { threadRepliesSyncHost.beginAuthoritativeRebaseline(page, generation) }
            }
        fun abortRepliesRebaseline() {
            if (repliesPageIdentity != null && repliesRebaselineGeneration != null) {
                threadRepliesSyncHost.abortAuthoritativeRebaseline(
                    repliesPageIdentity,
                    repliesRebaselineGeneration,
                )
            }
        }
        fun dropForOwnerChange(stage: String, result: DataResult<KuiklyThreadScreenPayload>? = null) {
            abortRepliesRebaseline()
            diagnosticsLogger?.invoke(
                "thread_refresh_result threadId=${basePayload.sourceThreadId.orEmpty()} " +
                    "result=${result?.threadDiagnosticsResult().orEmpty()} ignored=true " +
                    "reason=owner_changed stage=$stage " +
                    "durationMs=${refreshStartedAt.elapsedNow().inWholeMilliseconds}"
            )
        }
        if (!dataSource.isCurrentReplyCacheProvenanceForLocalSend(refreshProvenance)) {
            dropForOwnerChange("before_loading")
            return
        }
        val hasRenderableContent = existing.hasRenderableThreadContent()
        diagnosticsLogger?.invoke(
            "thread_refresh_start threadId=${basePayload.sourceThreadId.orEmpty()} " +
                "existingStatus=${existing.status} existingMessages=${existing.messages.size} " +
                "hasRenderableContent=$hasRenderableContent"
        )
        val loadingBase =
            if (hasRenderableContent || existing.isDurableEmptyStandIn()) {
                existing.withRouteContext(basePayload).copy(backgroundRefreshing = true)
            } else {
                basePayload.copy(status = "loading", backgroundRefreshing = false, errorCode = null)
            }
        diagnosticsLogger?.invoke(
            "thread_loading_patch threadId=${basePayload.sourceThreadId.orEmpty()} status=${loadingBase.status} " +
                "messages=${loadingBase.messages.size} " + loadingBase.originDiagnostics()
        )
        val loadingAdmitted =
            dataSource.withCurrentReplyCacheProvenanceForLocalSend(refreshProvenance) {
                reducePayloadPatch { currentPayload ->
                    if (!routeStillCurrent(basePayload, "thread_refresh", "loading_apply", currentPayload)) {
                        return@reducePayloadPatch currentPayload
                    }
                    currentPayload.withThreadContentProjectionFrom(loadingBase)
                }
            }
        if (loadingAdmitted == null) {
            dropForOwnerChange("loading_apply")
            return
        }
        afterRefreshLoadingStateApplied?.invoke(_state.value.payload)
        if (!hasRenderableContent) {
            delay(SharedThreadFirstFrameDelayMillis)
        }
        val result =
            dataSource.fetchThreadPayloadForContext(
                basePayload = basePayload,
                expectedContext = refreshProducerContext,
                persistResult = true,
                reusePrewarm = reusePrewarm,
                replaceReplyAccessFailure = explicitRetry
            )
        val latest = _state.value.payload
        if (
            !dataSource.isCurrentReplyCacheProvenanceForLocalSend(refreshProvenance) ||
            (result is DataResult.Error && result.error.code == ReplyCacheProvenanceStaleCode)
        ) {
            dropForOwnerChange("after_fetch", result)
            return
        }
        if (
            !staleTokenStillCurrent(staleToken, "thread_refresh", "after_fetch", basePayload, latest) ||
            !routeStillCurrent(basePayload, "thread_refresh", "after_fetch", latest)
        ) {
            abortRepliesRebaseline()
            diagnosticsLogger?.invoke(
                "thread_refresh_result threadId=${basePayload.sourceThreadId.orEmpty()} " +
                    "result=${result.threadDiagnosticsResult()} ignored=true reason=stale_route " +
                    "durationMs=${refreshStartedAt.elapsedNow().inWholeMilliseconds}"
            )
            return
        }
        val repliesProjectedResult =
            (result as? DataResult.Content)
                ?.data
                ?.withRouteContext(basePayload)
                ?.let { fetched ->
                    fetched.withAcceptedThreadRepliesRebaseline(
                        previous = latest,
                        page = repliesPageIdentity,
                        generation = repliesRebaselineGeneration,
                        expectedProvenance = refreshProvenance,
                    )
                }
        if (!dataSource.isCurrentReplyCacheProvenanceForLocalSend(refreshProvenance)) {
            dropForOwnerChange("after_rebaseline", result)
            return
        }
        val visiblePayload =
            repliesProjectedResult
                ?.preserveStableTimelineFrom(
                    previous = latest,
                    preserveAuxiliaryProjectionFromPrevious = true,
                )
                ?.asLoadedEmptyReplyThreadPayload()
        val nextPayload =
            when (result) {
                is DataResult.Content ->
                    visiblePayload ?: result.data.withRouteContext(basePayload).asLoadedEmptyReplyThreadPayload()
                is DataResult.Empty ->
                    if (result.reason == EmptyReason.NoMessages) {
                        latest.asRefreshEmpty(basePayload)
                    } else {
                        latest.asRefreshFailed("empty_thread")
                    }
                is DataResult.Error ->
                    latest.asRefreshFailed(result.error.code)
                is DataResult.NoPermission -> {
                    // Canonical fetch says we can no longer read this thread:
                    // the cached first-frame candidate is unrecoverable-stale
                    // (permission/visibility change) — drop it. Transient
                    // network errors deliberately keep the warm cache.
                    latest.asRefreshNoPermission(basePayload, result.blocker.code, result.blocker.serverMessage)
                }
            }.copy(
                backgroundRefreshing = false,
                // A durable last-known frame survives only a transient failure; any canonical answer
                // (content, empty, no permission) replaces it and clears the stand-in flag.
                durableLastKnown =
                    latest.durableLastKnown &&
                        when (result) {
                            is DataResult.Error -> true
                            is DataResult.Empty -> result.reason != EmptyReason.NoMessages
                            else -> false
                        },
                mainLoadFailed =
                    when (result) {
                        is DataResult.Error -> true
                        is DataResult.Empty -> result.reason != EmptyReason.NoMessages
                        else -> false
                    },
            )
        var committedPayload: KuiklyThreadScreenPayload? = null
        val committed =
            initialAccessStateMutex.withLock<Boolean?> {
                val identityPayload = _state.value.payload
                val replyIdentities =
                    repliesPageIdentity?.let { page ->
                        nextPayload.threadRepliesScopeIdentities(page, identityPayload)
                    }.orEmpty()
                fun commitWithAcceptedReplies(
                    acceptedReplies: Map<String, ThreadRepliesAcceptedProjection>,
                ): Boolean? =
                    dataSource.withCurrentReplyCacheProvenanceForLocalSend(refreshProvenance) {
                        val currentState = _state.value
                        val currentPayload = currentState.payload
                        if (
                            !staleTokenStillCurrent(
                                staleToken,
                                "thread_refresh",
                                "access_apply",
                                basePayload,
                                currentPayload,
                            ) ||
                            !routeStillCurrent(basePayload, "thread_refresh", "apply", currentPayload)
                        ) {
                            false
                        } else {
                            // A positive Home candidate may resolve the footer before the canonical
                            // fetch lands; the first canonical result still has to record itself.
                            val resolvingInitialAccess =
                                currentState.accessResolution != ThreadAccessResolution.Resolved ||
                                    !currentState.canonicalAccessResolved
                            val refreshStableNextPayload =
                                if (result is DataResult.Content) {
                                    nextPayload.preserveStableTimelineFrom(
                                        previous = currentPayload,
                                        preserveAuxiliaryProjectionFromPrevious = true,
                                        refreshObservedMessageIds = refreshObservedMessageIds,
                                        sendEchoMatchPolicy = ThreadSendEchoMatchPolicy.ExactRandomId,
                                    )
                                } else {
                                    nextPayload
                                }
                            val commitNextPayload =
                                refreshStableNextPayload.withAcceptedThreadRepliesProjection(
                                    acceptedReplies,
                                    currentPayload,
                                )
                            val projectedPayload =
                                currentPayload
                                    .withThreadContentProjectionFrom(commitNextPayload)
                                    .let { payload ->
                                        if (
                                            resolvingInitialAccess &&
                                            result is DataResult.Content &&
                                            payload.surfaceKind != KuiklyThreadSurfaceKind.ReplyThread
                                        ) {
                                            payload.withCanonicalConversationAccessFrom(nextPayload)
                                        } else {
                                            payload
                                        }
                                    }
                                    .withInitialHomeConversationAccessFallbackUnlessCanonical(
                                        canonicalAccessResolved =
                                            currentState.canonicalAccessResolved ||
                                                (resolvingInitialAccess && result is DataResult.Content),
                                        candidate = currentState.initialHomeAccessCandidate,
                                    )
                                    .withReadOnlyReplyAccessWhenHomeCandidateUnavailable(
                                        homeLookupComplete = currentState.initialHomeAccessLookupComplete,
                                        homeAccessCandidate = currentState.initialHomeAccessCandidate,
                                    )
                            val canonicalAccessResolved =
                                currentState.canonicalAccessResolved ||
                                    (resolvingInitialAccess && result.resolvesCanonicalConversationAccess())
                            val projectedAccessResolution =
                                currentState.accessResolution.afterRefreshResult(
                                    result = result,
                                    canonicalAccessResolved = canonicalAccessResolved,
                                    homeLookupComplete = currentState.initialHomeAccessLookupComplete,
                                    homeAccessCandidate = currentState.initialHomeAccessCandidate,
                                    requiresParentAccessCandidate =
                                        projectedPayload.surfaceKind == KuiklyThreadSurfaceKind.ReplyThread,
                                    hasReadableCanonicalReplyContent =
                                        projectedPayload.hasReadableCanonicalReplyContent(),
                                )
                            val authorityProjectedPayload =
                                projectedPayload.withTerminalAccessWriteAuthorityClosed(projectedAccessResolution)
                            val didEmit =
                                reduceState(
                                    currentState.copy(
                                        payload = authorityProjectedPayload,
                                        accessResolution = projectedAccessResolution,
                                        canonicalAccessResolved = canonicalAccessResolved,
                                        lastRefreshResultReason = result.diagnosticsResultReason(),
                                        writeActionsEnabled =
                                            authorityProjectedPayload.hasWriteAuthorityForAccess(
                                                accessResolution = projectedAccessResolution,
                                                homeLookupComplete = currentState.initialHomeAccessLookupComplete,
                                                homeAccessCandidate = currentState.initialHomeAccessCandidate,
                                            ),
                                    )
                                )
                            if (didEmit && visiblePayload != null) {
                                committedPayload = authorityProjectedPayload
                            }
                            true
                        }
                    }
                if (replyIdentities.isEmpty()) {
                    commitWithAcceptedReplies(emptyMap())
                } else {
                    threadRepliesSyncHost.withCurrentAcceptedProjections(replyIdentities) { accepted ->
                        commitWithAcceptedReplies(accepted)
                    }
                }
            }
        if (committed != true) {
            if (committed == null) {
                dropForOwnerChange("commit", result)
            } else {
                abortRepliesRebaseline()
            }
            return
        }
        val currentProvenance =
            dataSource.currentReplyCacheProvenanceForLocalSend(refreshProvenance)
        if (result is DataResult.NoPermission) {
            currentProvenance?.let { dataSource.invalidateCachedPayload(basePayload, it) }
        } else {
            committedPayload?.let { payload ->
                currentProvenance?.let { current ->
                    dataSource.cachePayload(
                        payload = payload,
                        serverId = current.serverId,
                        ownerScopeId = current.threadPayloadOwnerScopeId.orEmpty(),
                        expectedProvenance = current,
                    )
                }
            }
        }
        if (currentProvenance == null) {
            return
        }
        diagnosticsLogger?.invoke(
            "thread_refresh_result threadId=${basePayload.sourceThreadId.orEmpty()} " +
                "result=${result.threadDiagnosticsResult()} messages=${nextPayload.messages.size} " +
                "durationMs=${refreshStartedAt.elapsedNow().inWholeMilliseconds} " +
                nextPayload.originDiagnostics()
        )
        launchReplyOriginHydrationIfNeeded(basePayload, staleToken)
        if (!resolvingInitialAccessAtStart && !retryingReadOnlyParentAccess) {
            launchBackgroundHomeContextHydration(basePayload)
        }
        queueAuxiliaryHydration(
            routePayload = basePayload,
            staleToken = staleToken,
            launchSecondaryTimelineRefresh =
                visiblePayload != null &&
                    nextPayload.hasRenderableThreadContent() &&
                    !basePayload.needsReplyThreadStreamResolution(),
            launchMembersHydration = nextPayload.hasRenderableThreadContent()
        )
    }

    private fun queueAuxiliaryHydration(
        routePayload: KuiklyThreadScreenPayload,
        staleToken: MviStaleToken,
        launchSecondaryTimelineRefresh: Boolean,
        launchMembersHydration: Boolean
    ) {
        if (!launchSecondaryTimelineRefresh && !launchMembersHydration) {
            pendingAuxiliaryHydration = null
            return
        }
        if (launchSecondaryTimelineRefresh) {
            // The caller only sets this flag once the visible payload already has
            // renderable thread content, so the reply-summaries fetch does not
            // depend on FirstContentReady. Launch it immediately instead of
            // parking it behind the UI signal (~400ms) and let it race ahead of
            // the first frame.
            diagnosticsLogger?.invoke(
                "thread_auxiliary_hydration result=launched_immediate " +
                    "threadId=${routePayload.sourceThreadId.orEmpty()} " +
                    "secondary=true members=$launchMembersHydration"
            )
            launchSecondaryTimelineRefresh(routePayload, staleToken)
        }
        if (!launchMembersHydration) {
            pendingAuxiliaryHydration = null
            return
        }
        pendingAuxiliaryHydration =
            PendingAuxiliaryHydration(
                routePayload = routePayload,
                staleToken = staleToken,
                launchSecondaryTimelineRefresh = false,
                launchMembersHydration = launchMembersHydration
            )
        diagnosticsLogger?.invoke(
            "thread_auxiliary_hydration result=queued " +
                "threadId=${routePayload.sourceThreadId.orEmpty()} " +
                "secondary=false members=$launchMembersHydration"
        )
        launchPendingAuxiliaryHydrationIfReady()
    }

    private fun markFirstContentReady() {
        val currentPayload = _state.value.payload
        val routeKey = currentPayload.resolveThreadRouteIdentity().instanceKey
        firstContentReadyRouteKey = routeKey
        diagnosticsLogger?.invoke(
            "thread_first_content_ready threadId=${currentPayload.sourceThreadId.orEmpty()} routeKey=$routeKey"
        )
        launchPendingAuxiliaryHydrationIfReady()
        launchPendingSecondaryTimelineApplyIfReady()
    }

    private fun launchPendingSecondaryTimelineApplyIfReady() {
        val pending = pendingSecondaryTimelineApply ?: return
        val pendingRouteKey = pending.routePayload.resolveThreadRouteIdentity().instanceKey
        if (firstContentReadyRouteKey != pendingRouteKey) {
            // The route changed before first content became ready; drop the
            // stashed apply so an old route's result can never resurface.
            pendingSecondaryTimelineApply = null
            return
        }
        if (!pending.staleToken.isCurrent(routeStaleToken)) {
            pendingSecondaryTimelineApply = null
            diagnosticsLogger?.invoke(
                "thread_secondary_refresh_result threadId=${pending.routePayload.sourceThreadId.orEmpty()} " +
                    "ignored=true reason=stale_route stage=pending_apply"
            )
            return
        }
        pendingSecondaryTimelineApply = null
        scope.launch { pending.apply() }
    }

    private fun launchPendingAuxiliaryHydrationIfReady() {
        val pending = pendingAuxiliaryHydration ?: return
        val pendingRouteKey = pending.routePayload.resolveThreadRouteIdentity().instanceKey
        if (firstContentReadyRouteKey != pendingRouteKey) return
        val currentPayload = _state.value.payload
        if (
            !pending.staleToken.isCurrent(routeStaleToken) ||
            !routeStillCurrent(
                pending.routePayload,
                "thread_auxiliary_hydration",
                "launch",
                currentPayload
            )
        ) {
            pendingAuxiliaryHydration = null
            return
        }
        pendingAuxiliaryHydration = null
        diagnosticsLogger?.invoke(
            "thread_auxiliary_hydration result=launched " +
                "threadId=${pending.routePayload.sourceThreadId.orEmpty()} " +
                "secondary=${pending.launchSecondaryTimelineRefresh} members=${pending.launchMembersHydration}"
        )
        if (pending.launchSecondaryTimelineRefresh) {
            launchSecondaryTimelineRefresh(pending.routePayload, pending.staleToken)
        }
        if (pending.launchMembersHydration) {
            launchBackgroundMembersHydration(pending.routePayload)
        }
    }

    private fun launchReplyOriginHydrationIfNeeded(
        routePayload: KuiklyThreadScreenPayload,
        staleToken: MviStaleToken
    ) {
        val currentPayload = _state.value.payload
        if (
            currentPayload.surfaceKind != KuiklyThreadSurfaceKind.ReplyThread ||
            currentPayload.originStatus != "loading" ||
            currentPayload.parentChannelId.isNullOrBlank() ||
            (currentPayload.parentMessageId.isNullOrBlank() && currentPayload.originParentMessageId.isNullOrBlank())
        ) {
            return
        }
        val parentChannelId = currentPayload.parentChannelId.orEmpty()
        val parentMessageId =
            currentPayload.parentMessageId?.takeIf { it.isNotBlank() }
                ?: currentPayload.originParentMessageId.orEmpty()
        val originStatusAtStart = currentPayload.originStatus
        val originMessageAtStart = currentPayload.originMessage
        focusedOriginHydrationJob?.cancel()
        focusedOriginHydrationJob =
            scope.launch {
                val originContext = dataSource.captureReplyCacheProducerContext() ?: return@launch
                val resolution =
                    dataSource.resolveReplyOrigin(
                        currentPayload,
                        expectedProvenance = originContext.provenance
                    )
                if (
                    resolution is SharedThreadOriginResolution.Error &&
                    resolution.code == "thread_reply_cache_provenance_stale"
                ) {
                    return@launch
                }
                val latest = _state.value.payload
                if (
                    !staleTokenStillCurrent(
                        staleToken,
                        "thread_focused_origin",
                        "after_fetch",
                        routePayload,
                        latest
                    ) ||
                    !routeStillCurrent(routePayload, "thread_focused_origin", "after_fetch", latest) ||
                    !latest.acceptsFocusedOriginHydrationCompletion(
                        parentChannelId = parentChannelId,
                        parentMessageId = parentMessageId,
                        originStatusAtStart = originStatusAtStart,
                        originMessageAtStart = originMessageAtStart
                    )
                ) {
                    return@launch
                }
                val payloadBeforeCommit = _state.value.payload
                beforeFocusedOriginCommit?.invoke(payloadBeforeCommit)
                var committedPayload: KuiklyThreadScreenPayload? = null
                var emitted = false
                dataSource.withCurrentReplyCacheProvenance(originContext.provenance) {
                    _state.update { currentState ->
                        emitted = false
                        committedPayload = null
                        val payload = currentState.payload
                        val nextPayload =
                            if (
                                !staleTokenStillCurrent(
                                    staleToken,
                                    "thread_focused_origin",
                                    "apply",
                                    routePayload,
                                    payload
                                ) ||
                                !routeStillCurrent(routePayload, "thread_focused_origin", "apply", payload) ||
                                !payload.acceptsFocusedOriginHydrationCompletion(
                                    parentChannelId = parentChannelId,
                                    parentMessageId = parentMessageId,
                                    originStatusAtStart = originStatusAtStart,
                                    originMessageAtStart = originMessageAtStart
                                )
                            ) {
                                payload
                            } else {
                                when (resolution) {
                                    is SharedThreadOriginResolution.Loaded ->
                                        payload.copy(
                                            originMessage = resolution.message,
                                            originParentMessageId = parentMessageId,
                                            originStatus = "loaded",
                                            originErrorCode = null
                                        )
                                    SharedThreadOriginResolution.Missing ->
                                        payload.copy(
                                            originMessage = null,
                                            originParentMessageId = parentMessageId,
                                            originStatus = "missing",
                                            originErrorCode = null
                                        )
                                    is SharedThreadOriginResolution.Error ->
                                        payload.copy(
                                            originMessage = null,
                                            originParentMessageId = parentMessageId,
                                            originStatus = "error",
                                            originErrorCode = resolution.code
                                        )
                                }
                            }
                        val nextState = currentState.copy(payload = nextPayload).withSynchronizedTimelineSlice()
                        emitted = nextState != currentState
                        if (emitted) committedPayload = nextPayload
                        nextState
                    }
                }
                if (emitted) {
                    committedPayload?.let { payload ->
                        dataSource.cachePayload(
                            payload = payload,
                            serverId = originContext.provenance.serverId,
                            ownerScopeId = originContext.provenance.threadPayloadOwnerScopeId.orEmpty(),
                            expectedProvenance = originContext.provenance
                        )
                    }
                }
                diagnosticsLogger?.invoke(
                    "thread_focused_origin_result parentChannelId=$parentChannelId " +
                        "parentMessageId=$parentMessageId result=${resolution.focusedOriginDiagnosticsResult()} " +
                        "emitted=$emitted timelineMutation=false"
                )
            }
    }

    /**
     * task #243: race the lookup against an injectable deadline SIGNAL.
     *
     * `withTimeoutOrNull(1_000L)` tied the timeout branch to a real second of wall clock, which is
     * unreachable deterministically from a test - `commonTest` compiles for OHOS arm64, where no
     * `kotlinx-coroutines-test` KLIB exists, so virtual time is not available here. Handing the
     * deadline in as a suspend function lets a test complete it explicitly (the same explicit-barrier
     * shape already used in SharedReplyThreadAccessIntegrationTest) while production keeps `delay`.
     *
     * The block still runs, so callers that count downstream requests keep observing them.
     */
    private suspend fun <T> raceInitialAccessLookupDeadline(block: suspend () -> T?): T? =
        coroutineScope {
            val outcome = CompletableDeferred<T?>()
            val work = launch {
                try {
                    outcome.complete(block())
                } catch (cancellation: CancellationException) {
                    // The body rethrows CancellationException on purpose (see the call site and
                    // initialConversationAccessResolution). A cancelled child does NOT fail its
                    // parent, so without this the exception would be swallowed, nothing would
                    // complete `outcome`, and the resolution would sit here until the deadline
                    // fired - turning "abandon the lookup" into "wait, then take the timeout
                    // branch" and running the whole projection with a null candidate. Hand it to
                    // the awaiter so it keeps escaping exactly as it did under withTimeoutOrNull.
                    outcome.completeExceptionally(cancellation)
                    throw cancellation
                }
            }
            val deadline = launch {
                initialAccessLookupDeadline()
                outcome.complete(null)
            }
            try {
                outcome.await()
            } finally {
                work.cancel()
                deadline.cancel()
            }
        }

    private fun launchInitialAccessResolution(
        routePayload: KuiklyThreadScreenPayload,
        staleToken: MviStaleToken
    ) {
        initialAccessResolutionJob?.cancel()
        initialAccessResolutionJob =
            scope.launch {
                val homeResolution =
                    raceInitialAccessLookupDeadline {
                        try {
                            dataSource.initialConversationAccessResolution(routePayload)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Throwable) {
                            null
                        }
                    }
                if (homeResolution?.ownerCurrent == false) {
                    diagnosticsLogger?.invoke(
                        "thread_access_resolution_stale_drop " +
                            "threadId=${routePayload.sourceThreadId.orEmpty()} reason=owner_changed"
                    )
                    return@launch
                }
                val candidate = homeResolution?.accessCandidate
                val launchBackgroundHomeContext =
                    homeResolution?.requiresBackgroundHomeContextHydration
                        ?: (routePayload.surfaceKind == KuiklyThreadSurfaceKind.ReplyThread)
                initialAccessStateMutex.withLock {
                    val currentState = _state.value
                    val currentPayload = currentState.payload
                    if (
                        !staleTokenStillCurrent(
                            staleToken,
                            "thread_access_resolution",
                            "apply",
                            routePayload,
                            currentPayload
                        ) ||
                        !routeStillCurrent(routePayload, "thread_access_resolution", "apply", currentPayload)
                    ) {
                        return@withLock
                    }
                    val projectedPayload =
                        currentPayload
                            .let { payload ->
                                homeResolution?.payload?.let { homePayload ->
                                    payload.withHomeContextProjectionFrom(homePayload)
                                } ?: payload
                            }
                            .withInitialHomeConversationAccessFallbackUnlessCanonical(
                                canonicalAccessResolved = currentState.canonicalAccessResolved,
                                candidate = candidate,
                            )
                            .withReadOnlyReplyAccessWhenHomeCandidateUnavailable(
                                homeLookupComplete = true,
                                homeAccessCandidate = candidate
                            )
                    val nextResolution =
                        when {
                            currentState.accessResolution == ThreadAccessResolution.NoPermission ->
                                ThreadAccessResolution.NoPermission
                            currentState.accessResolution == ThreadAccessResolution.Error ->
                                ThreadAccessResolution.Error
                            currentState.accessResolution == ThreadAccessResolution.Resolved &&
                                currentState.canonicalAccessResolved ->
                                ThreadAccessResolution.Resolved
                            projectedPayload.surfaceKind == KuiklyThreadSurfaceKind.ReplyThread &&
                                currentState.canonicalAccessResolved &&
                                (candidate != null || projectedPayload.hasReadableCanonicalReplyContent()) ->
                                ThreadAccessResolution.Resolved
                            projectedPayload.surfaceKind == KuiklyThreadSurfaceKind.ReplyThread &&
                                currentState.canonicalAccessResolved ->
                                ThreadAccessResolution.Error
                            currentState.canonicalAccessResolved ->
                                ThreadAccessResolution.Resolved
                            // Task #1141 + reply-thread parity: a POSITIVE access candidate
                            // (joined + canSend) is the same write authority the canonical fetch
                            // would confirm — for Channel/DM it is the conversation's own row, and
                            // for a ReplyThread it is the exact parent row, which is the only write
                            // authority the reply surface ever consults
                            // (hasWriteAuthorityForAccess + withInitialHomeConversationAccessFallback).
                            // Accept it for renderable cached content just as it is accepted for
                            // "empty", so the composer mounts when the directory answers instead of
                            // after the first network refresh. For reply threads the canonical reply
                            // stream never attests parent write authority anyway, so waiting for it
                            // added latency without adding information. A negative candidate (stale
                            // unjoined row) keeps waiting for the canonical fetch, which must still
                            // win; a later canonical NoPermission still closes access.
                            candidate != null &&
                                candidate.joined &&
                                candidate.permissions.canSend &&
                                currentPayload.status == "content" ->
                                ThreadAccessResolution.Resolved
                            candidate != null && currentPayload.status == "empty" ->
                                ThreadAccessResolution.Resolved
                            currentPayload.status == "error" ->
                                ThreadAccessResolution.Error
                            currentPayload.status == "empty" ->
                                ThreadAccessResolution.Error
                            else -> ThreadAccessResolution.Loading
                        }
                    val authorityProjectedPayload =
                        projectedPayload.withTerminalAccessWriteAuthorityClosed(nextResolution)
                    reduceState(
                        currentState.copy(
                            payload = authorityProjectedPayload,
                            accessResolution = nextResolution,
                            initialHomeAccessLookupComplete = true,
                            initialHomeAccessCandidate = candidate,
                            replyParentAuthority =
                                resolveReplyParentAuthorityStatus(
                                    accessResolution = nextResolution,
                                    payload = authorityProjectedPayload,
                                    candidate = candidate,
                                ),
                            writeActionsEnabled =
                                authorityProjectedPayload.hasWriteAuthorityForAccess(
                                    accessResolution = nextResolution,
                                    homeLookupComplete = true,
                                    homeAccessCandidate = candidate
                                )
                        )
                    )
                }
                if (
                    launchBackgroundHomeContext &&
                    staleTokenStillCurrent(
                        staleToken,
                        "thread_access_home_context",
                        "launch",
                        routePayload,
                    ) &&
                    routeStillCurrent(
                        routePayload,
                        "thread_access_home_context",
                        "launch",
                    )
                ) {
                    launchBackgroundHomeContextHydration(routePayload)
                }
            }
    }

    /**
     * Classifies the reply-thread parent write authority after an access
     * resolution pass. Non-reply surfaces never carry parent authority.
     */
    private fun resolveReplyParentAuthorityStatus(
        accessResolution: ThreadAccessResolution,
        payload: KuiklyThreadScreenPayload,
        candidate: ConversationAccessCandidate?,
    ): ReplyParentAuthorityStatus {
        if (payload.surfaceKind != KuiklyThreadSurfaceKind.ReplyThread) {
            return ReplyParentAuthorityStatus.Writable
        }
        return when {
            accessResolution == ThreadAccessResolution.NoPermission ||
                accessResolution == ThreadAccessResolution.Error ->
                ReplyParentAuthorityStatus.Denied
            candidate != null && payload.acceptsInitialConversationAccessCandidate(candidate) ->
                if (payload.joined && payload.canSend) {
                    ReplyParentAuthorityStatus.Writable
                } else {
                    ReplyParentAuthorityStatus.Denied
                }
            else ->
                // This classification only runs from the apply pass, which by
                // definition completes the initial home access lookup.
                if (payload.hasReadableCanonicalReplyContent()) {
                    ReplyParentAuthorityStatus.Unknown
                } else {
                    ReplyParentAuthorityStatus.Resolving
                }
        }
    }

    /**
     * Event-driven re-evaluation of reply-thread parent authority (task #228
     * successor direction A). The Home directory is the same authority source
     * the initial lookup consults; when its row for the exact parent appears
     * or changes while the thread is open, this applies it in place - no
     * retry loop, no network. Only upgrades [ReplyParentAuthorityStatus.Unknown]
     * or recomputes a candidate-derived [ReplyParentAuthorityStatus.Denied];
     * terminal NoPermission/Error and non-reply surfaces are untouched.
     * A network-authoritative hint with `present = false` attests the parent
     * channel was deleted and lands in [ReplyParentAuthorityStatus.ParentDeleted];
     * a later `present = true` hint (recreated/restored row) recomputes from there.
     */
    private suspend fun applyParentDirectoryHint(hint: SharedThreadIntent.ApplyParentDirectoryHint) {
        val state = _state.value
        val payload = state.payload
        if (payload.surfaceKind != KuiklyThreadSurfaceKind.ReplyThread) return
        if (state.accessResolution == ThreadAccessResolution.NoPermission ||
            state.accessResolution == ThreadAccessResolution.Error
        ) {
            return
        }
        if (!state.canonicalAccessResolved || !state.initialHomeAccessLookupComplete) return
        val parentChannelId = payload.parentChannelId?.takeIf { it.isNotBlank() } ?: return
        if (parentChannelId != hint.parentChannelId) return
        val parentMessageId =
            payload.parentMessageId?.takeIf { it.isNotBlank() }
                ?: payload.originParentMessageId?.takeIf { it.isNotBlank() }
                ?: return
        if (parentMessageId != hint.parentMessageId) return
        val routeIdentity = payload.resolveThreadRouteIdentity()
        if (routeIdentity.parentKey != "$parentChannelId:$parentMessageId") return
        if (!hint.present) {
            if (!hint.authoritative) {
                // Row absent in a non-authoritative snapshot: nothing new to
                // learn - the state stays Unknown/Resolving.
                return
            }
            initialAccessStateMutex.withLock {
                val current = _state.value
                // Re-check under the lock: a route/account switch may have
                // landed while the hint was queued on the dispatcher.
                if (current.payload.parentChannelId != parentChannelId ||
                    current.payload.sourceThreadId != payload.sourceThreadId ||
                    current.accessResolution == ThreadAccessResolution.NoPermission ||
                    current.accessResolution == ThreadAccessResolution.Error
                ) {
                    return
                }
                // A network-authoritative directory read that no longer lists
                // the parent means the channel was deleted.
                diagnosticsLogger?.invoke(
                    "thread_parent_authority_hint_applied threadId=${current.payload.sourceThreadId.orEmpty()} " +
                        "authority=${ReplyParentAuthorityStatus.ParentDeleted} present=false authoritative=true"
                )
                reduceState(
                    current.copy(
                        replyParentAuthority = ReplyParentAuthorityStatus.ParentDeleted,
                        writeActionsEnabled = false,
                    )
                )
            }
            return
        }
        if (state.replyParentAuthority == ReplyParentAuthorityStatus.Writable &&
            payload.joined == hint.joined && payload.canSend == hint.canSend
        ) {
            return
        }
        val conversationKind =
            routeIdentity.parentConversationKind ?: ConversationKind.Channel
        val candidate =
            ConversationAccessCandidate(
                ids = setOf(parentChannelId),
                conversationKind = conversationKind,
                surfaceKind = "channel",
                joined = hint.joined,
                archived = false,
                permissions =
                    ConversationPermissions(
                        canRead = true,
                        canSend = hint.canSend,
                        canAttach = hint.canSend,
                        canCreateTask = hint.canSend,
                    ),
            )
        if (!payload.acceptsInitialConversationAccessCandidate(candidate)) return
        initialAccessStateMutex.withLock {
            val current = _state.value
            // Re-check under the lock: a route/account switch may have landed
            // while the hint was queued on the dispatcher.
            if (current.payload.parentChannelId != parentChannelId ||
                current.payload.sourceThreadId != payload.sourceThreadId ||
                current.accessResolution == ThreadAccessResolution.NoPermission ||
                current.accessResolution == ThreadAccessResolution.Error
            ) {
                return
            }
            val projected =
                current.payload
                    .withInitialHomeConversationAccessFallbackUnlessCanonical(
                        canonicalAccessResolved = current.canonicalAccessResolved,
                        candidate = candidate,
                    )
                    .withTerminalAccessWriteAuthorityClosed(current.accessResolution)
            val nextAuthority =
                if (hint.joined && hint.canSend) {
                    ReplyParentAuthorityStatus.Writable
                } else {
                    ReplyParentAuthorityStatus.Denied
                }
            val nextWriteActions =
                projected.hasWriteAuthorityForAccess(
                    accessResolution = current.accessResolution,
                    homeLookupComplete = true,
                    homeAccessCandidate = candidate
                )
            diagnosticsLogger?.invoke(
                "thread_parent_authority_hint_applied threadId=${current.payload.sourceThreadId.orEmpty()} " +
                    "authority=$nextAuthority joined=${hint.joined} canSend=${hint.canSend}"
            )
            reduceState(
                current.copy(
                    payload = projected,
                    initialHomeAccessCandidate = candidate,
                    replyParentAuthority = nextAuthority,
                    writeActionsEnabled = nextWriteActions,
                )
            )
        }
    }

    private suspend fun retryAccessResolution() {
        val routePayload = lastRoutePayload ?: _state.value.payload
        initialAccessStateMutex.withLock {
            val currentState = _state.value
            if (
                currentState.accessResolution != ThreadAccessResolution.Error ||
                !currentState.canonicalAccessResolved
            ) {
                return
            }
            diagnosticsLogger?.invoke(
                "thread_access_retry_start threadId=${routePayload.sourceThreadId.orEmpty()} " +
                    "canonicalAccessResolved=${currentState.canonicalAccessResolved}"
            )
            reduceState(
                currentState.copy(
                    accessResolution = ThreadAccessResolution.Loading,
                    initialHomeAccessLookupComplete = false,
                    initialHomeAccessCandidate = null
                )
            )
        }
        launchInitialAccessResolution(routePayload, routeStaleToken)
    }

    private fun launchBackgroundHomeContextHydration(routePayload: KuiklyThreadScreenPayload) {
        val staleToken = routeStaleToken
        homeContextHydrationJob?.cancel()
        homeContextHydrationJob =
            scope.launch {
                val hydratedPayload =
                    try {
                        dataSource.withHomeConversationContext(routePayload)
                            .withoutTransientLocalSendingMessages()
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        return@launch
                    }
                if (hydratedPayload == routePayload) {
                    return@launch
                }
                if (!staleTokenStillCurrent(staleToken, "thread_home_context", "after_fetch", routePayload)) {
                    return@launch
                }
                val emitted =
                    reducePayloadPatch { currentPayload ->
                        if (
                            !staleTokenStillCurrent(
                                staleToken,
                                "thread_home_context",
                                "apply",
                                routePayload,
                                currentPayload
                            ) ||
                            !routeStillCurrent(routePayload, "thread_home_context", "apply", currentPayload)
                        ) {
                            return@reducePayloadPatch currentPayload
                        }
                        currentPayload.withHomeContextProjectionFrom(hydratedPayload)
                    }
                if (emitted) {
                    diagnosticsLogger?.invoke(
                        "thread_home_context_applied threadId=${routePayload.sourceThreadId.orEmpty()} " +
                            "knownMarkdownChannels=${hydratedPayload.knownMarkdownChannelNames.size} " +
                            "accessMutation=false"
                    )
                }
            }
    }

    private fun launchSecondaryTimelineRefresh(
        routePayload: KuiklyThreadScreenPayload,
        staleToken: MviStaleToken
    ) {
        secondaryTimelineRefreshJob?.cancel()
        secondaryTimelineRefreshJob =
            scope.launch {
                refreshSecondaryTimelineData(routePayload, staleToken)
            }
    }

    private fun launchBackgroundMembersHydration(routePayload: KuiklyThreadScreenPayload) {
        backgroundMembersHydrationJob?.cancel()
        backgroundMembersHydrationJob =
            scope.launch {
                val latest = _state.value.payload
                if (!routeStillCurrent(routePayload, "thread_members_background_load", "start", latest)) {
                    return@launch
                }
                if (!latest.hasRenderableThreadContent()) {
                    return@launch
                }
                loadMembers(force = true)
            }
    }

    private suspend fun refreshSecondaryTimelineData(
        routePayload: KuiklyThreadScreenPayload,
        staleToken: MviStaleToken
    ) {
        val startedAt = TimeSource.Monotonic.markNow()
        val beforeFetch = _state.value.payload
        if (!routeStillCurrent(routePayload, "thread_secondary_refresh", "start", beforeFetch)) {
            return
        }
        if (!beforeFetch.hasRenderableThreadContent()) {
            return
        }
        val producerContext = dataSource.captureReplyCacheProducerContext() ?: return
        val expectedProvenance = producerContext.provenance
        val repliesPageIdentity =
            if (beforeFetch.surfaceKind == KuiklyThreadSurfaceKind.ReplyThread) {
                null
            } else {
                captureThreadRepliesPageIdentity(routePayload, producerContext)
            }
        val repliesRebaselineGeneration =
            repliesPageIdentity?.let { page ->
                val generation = ++nextThreadRepliesRebaselineGeneration
                generation.takeIf { threadRepliesSyncHost.beginAuthoritativeRebaseline(page, generation) }
            }
        fun abortRebaseline() {
            if (repliesPageIdentity != null && repliesRebaselineGeneration != null) {
                threadRepliesSyncHost.abortAuthoritativeRebaseline(
                    repliesPageIdentity,
                    repliesRebaselineGeneration,
                )
            }
        }
        fun dropForOwnerChange(stage: String) {
            abortRebaseline()
            diagnosticsLogger?.invoke(
                "thread_secondary_refresh_result threadId=${routePayload.sourceThreadId.orEmpty()} " +
                    "ignored=true reason=owner_changed stage=$stage " +
                    "durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
            )
        }
        if (!dataSource.isCurrentReplyCacheProvenance(expectedProvenance)) {
            dropForOwnerChange("before_summary")
            return
        }
        var replySummaries: ThreadReplySummariesSnapshot? = null
        var replySummariesObservedAt: TimeSource.Monotonic.ValueTimeMark? = null
        val followedPayload =
            if (beforeFetch.surfaceKind == KuiklyThreadSurfaceKind.ReplyThread) {
                // ReplyThread loads are already scoped to a single canonical stream,
                // and RealConversationRepository.getReplyThread does not attach
                // followed-channel summaries. Re-running it here only duplicates the
                // complete messages/context request after the first result is visible.
                diagnosticsLogger?.invoke(
                    "thread_secondary_followed_result threadId=${routePayload.sourceThreadId.orEmpty()} " +
                        "result=skipped:reply_thread durationMs=0"
                )
                beforeFetch
            } else {
                val followedStartedAt = TimeSource.Monotonic.markNow()
                val followedResult =
                    dataSource.fetchFollowedThreadPayloadForContext(
                        basePayload = beforeFetch,
                        expectedContext = producerContext,
                    ) { snapshot, observedAt ->
                        replySummaries = snapshot
                        replySummariesObservedAt = observedAt
                    }
                diagnosticsLogger?.invoke(
                    "thread_secondary_followed_result threadId=${routePayload.sourceThreadId.orEmpty()} " +
                        "result=${followedResult.threadDiagnosticsResult()} " +
                        "durationMs=${followedStartedAt.elapsedNow().inWholeMilliseconds}"
                )
                when (followedResult) {
                    is DataResult.Content -> followedResult.data
                    is DataResult.Empty,
                    is DataResult.Error,
                    is DataResult.NoPermission -> beforeFetch
                }
            }
        if (!dataSource.isCurrentReplyCacheProvenance(expectedProvenance)) {
            dropForOwnerChange("after_summary")
            return
        }
        val enrichedResult = dataSource.enrichSavedStatus(followedPayload, producerContext)
        if (!dataSource.isCurrentReplyCacheProvenance(expectedProvenance)) {
            dropForOwnerChange("after_saved_status")
            return
        }
        val enriched =
            when (enrichedResult) {
                is DataResult.Content -> enrichedResult.data
                is DataResult.Empty,
                is DataResult.Error,
                is DataResult.NoPermission -> {
                    dropForOwnerChange("saved_status_rejected")
                    return
                }
            }
        val latest = _state.value.payload
        if (
            !staleTokenStillCurrent(staleToken, "thread_secondary_refresh", "after_fetch", routePayload, latest) ||
            !routeStillCurrent(routePayload, "thread_secondary_refresh", "after_fetch", latest)
        ) {
            abortRebaseline()
            diagnosticsLogger?.invoke(
                "thread_secondary_refresh_result threadId=${routePayload.sourceThreadId.orEmpty()} " +
                    "ignored=true reason=stale_route durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
            )
            return
        }
        val replyProjectedEnriched =
            if (replySummaries != null) {
                enriched
                    .withRouteContext(routePayload)
                    .withAcceptedThreadRepliesRebaseline(
                        previous = latest,
                        page = repliesPageIdentity,
                        generation = repliesRebaselineGeneration,
                        expectedProvenance = expectedProvenance,
                    )
            } else {
                abortRebaseline()
                enriched
                    .withRouteContext(routePayload)
                    .withAcceptedThreadRepliesProjection(emptyMap(), latest)
            }
        if (!dataSource.isCurrentReplyCacheProvenance(expectedProvenance)) {
            dropForOwnerChange("after_rebaseline")
            return
        }
        val patch = replyProjectedEnriched.preserveStableTimelineFrom(latest)
        var emitted = false
        var committedPayload: KuiklyThreadScreenPayload? = null
        fun commitState(accepted: Map<String, ThreadRepliesAcceptedProjection>): Boolean {
            val currentPayload = _state.value.payload
            if (
                !staleTokenStillCurrent(
                    staleToken,
                    "thread_secondary_refresh",
                    "apply",
                    routePayload,
                    currentPayload,
                ) ||
                !routeStillCurrent(routePayload, "thread_secondary_refresh", "apply", currentPayload)
            ) {
                return false
            }
            val commitPatch = patch.withAcceptedThreadRepliesProjection(accepted, currentPayload)
            emitted = reducePayloadPatch(cachePayload = false) { applyingPayload ->
                if (
                    !staleTokenStillCurrent(
                        staleToken,
                        "thread_secondary_refresh",
                        "apply",
                        routePayload,
                        applyingPayload
                    ) ||
                    !routeStillCurrent(routePayload, "thread_secondary_refresh", "apply", applyingPayload)
                ) {
                    return@reducePayloadPatch applyingPayload
                }
                applyingPayload.withSecondaryTimelineProjectionFrom(
                    patch = commitPatch,
                    previous = latest,
                ).let { projected ->
                    // The secondary projection merges live/baseline/patch and
                    // can overwrite a newer realtime reply-count fact with the
                    // summary snapshot's stale value. Re-apply the fact ledger
                    // (same pass as the fetch path at :3587) before committing;
                    // a null result means the provenance rotated, so the caller
                    // keeps the unprojected payload and the commit drops stale.
                    routePayload.cacheThreadId()?.let { threadId ->
                        dataSource.applyReplyCountFacts(
                            threadId = threadId,
                            expected = expectedProvenance,
                            payload = projected,
                        )
                    } ?: projected
                }
            }
            if (emitted) committedPayload = _state.value.payload
            return true
        }
        fun commitSecondary(accepted: Map<String, ThreadRepliesAcceptedProjection>): Boolean? {
            val snapshot = replySummaries
            val observedAt = replySummariesObservedAt
            val threadId = routePayload.cacheThreadId()
            return if (snapshot != null && observedAt != null && threadId != null) {
                dataSource.cacheReplyCountProjection(
                    threadId = threadId,
                    snapshot = snapshot,
                    observedAt = observedAt,
                    serverId = expectedProvenance.serverId,
                    principalId = expectedProvenance.principalId,
                    expectedProvenance = expectedProvenance,
                ) {
                    commitState(accepted)
                }
            } else {
                dataSource.withCurrentReplyCacheProvenance(expectedProvenance) {
                    commitState(accepted)
                }
            }
        }
        suspend fun applyPatch() {
            val replyIdentities =
                repliesPageIdentity?.let { page -> patch.threadRepliesScopeIdentities(page, latest) }.orEmpty()
            val committed =
                if (replyIdentities.isEmpty()) {
                    commitSecondary(emptyMap())
                } else {
                    threadRepliesSyncHost.withCurrentAcceptedProjections(replyIdentities) { accepted ->
                        commitSecondary(accepted)
                    }
                }
            if (committed != true) {
                if (committed == null) {
                    dropForOwnerChange("commit")
                } else {
                    abortRebaseline()
                }
                return
            }
            committedPayload?.let { payload ->
                dataSource.cachePayload(
                    payload = payload,
                    ownerScopeId = expectedProvenance.threadPayloadOwnerScopeId.orEmpty(),
                    expectedProvenance = expectedProvenance,
                )
            }
            diagnosticsLogger?.invoke(
                "thread_secondary_refresh_result threadId=${routePayload.sourceThreadId.orEmpty()} " +
                    "emitted=$emitted messages=${patch.messages.size} durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
            )
        }
        if (firstContentReadyRouteKey == routePayload.resolveThreadRouteIdentity().instanceKey) {
            applyPatch()
        } else {
            // First frame is not up yet (or the route moved on): stash the
            // fully-built patch instead of painting the secondary timeline.
            // launchPendingSecondaryTimelineApplyIfReady() flushes or drops it.
            pendingSecondaryTimelineApply =
                PendingSecondaryTimelineApply(
                    routePayload = routePayload,
                    staleToken = staleToken,
                    apply = { applyPatch() },
                )
            diagnosticsLogger?.invoke(
                "thread_secondary_refresh_result threadId=${routePayload.sourceThreadId.orEmpty()} " +
                    "result=pending_first_content durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
            )
        }
    }

    /**
     * Load the current thread's members, with idempotency guards.
     *
     * Behaviour:
     * - No-op when the current source thread is missing.
     * - No-op when the bound status is [LoadMembersStatus.InFlight] for
     *   the current thread key (concurrent taps coalesce to one request).
     * - No-op when the bound status is [LoadMembersStatus.Loaded] for the
     *   current thread key, unless [force] is true.
     * - Resets to [LoadMembersStatus.Idle] when the bound key changes
     *   (switching threads) so a previous thread's "Loaded" verdict
     *   never leaks into the next thread.
     */
    private suspend fun loadMembers(force: Boolean = false) {
        val membersStartedAt = TimeSource.Monotonic.markNow()
        val existing = _state.value.payload
        val basePayload = existing
        val threadKey = existing.sourceThreadId.orEmpty()
        if (threadKey.isBlank()) return
        val currentState = _state.value
        if (currentState.membersLoadKey != threadKey) {
            // Switched threads: reset status so the previous verdict does not leak.
            _state.value = currentState.copy(
                membersLoadKey = threadKey,
                membersLoadStatus = LoadMembersStatus.Idle
            )
        }
        val resolvedStatus = _state.value.membersLoadStatus
        if (!force && resolvedStatus == LoadMembersStatus.InFlight) {
            diagnosticsLogger?.invoke(
                "thread_members_load_skip threadId=$threadKey reason=in_flight"
            )
            return
        }
        if (!force && resolvedStatus == LoadMembersStatus.Loaded) {
            diagnosticsLogger?.invoke(
                "thread_members_load_skip threadId=$threadKey reason=already_loaded"
            )
            return
        }
        diagnosticsLogger?.invoke(
            "thread_members_load_start threadId=$threadKey " +
                "existingMembers=${existing.members.size} existingCandidates=${existing.memberCandidates.size}"
        )
        _state.value = _state.value.copy(membersLoadStatus = LoadMembersStatus.InFlight)
        when (val outcome = dataSource.enrichThreadMembers(existing)) {
            is SharedThreadDataSource.MembersEnrichOutcome.Updated -> {
                if (!routeStillCurrent(basePayload, "thread_members_load", "after_fetch")) {
                    return
                }
                reducePayloadPatch(cachePayload = true) { currentPayload ->
                    if (!routeStillCurrent(basePayload, "thread_members_load", "apply", currentPayload)) {
                        return@reducePayloadPatch currentPayload
                    }
                    currentPayload.withMembersPatchFrom(outcome.payload)
                }
                _state.value = _state.value.copy(membersLoadStatus = LoadMembersStatus.Loaded)
                diagnosticsLogger?.invoke(
                    "thread_members_load_result threadId=$threadKey " +
                        "result=changed members=${outcome.payload.members.size} " +
                        "candidates=${outcome.payload.memberCandidates.size} canAddMembers=${outcome.payload.canAddMembers} " +
                        "durationMs=${membersStartedAt.elapsedNow().inWholeMilliseconds}"
                )
            }
            is SharedThreadDataSource.MembersEnrichOutcome.Unchanged -> {
                if (!routeStillCurrent(basePayload, "thread_members_load", "after_fetch")) {
                    return
                }
                _state.value = _state.value.copy(membersLoadStatus = LoadMembersStatus.Loaded)
                diagnosticsLogger?.invoke(
                    "thread_members_load_result threadId=$threadKey " +
                        "result=unchanged members=${existing.members.size} candidates=${existing.memberCandidates.size} " +
                        "durationMs=${membersStartedAt.elapsedNow().inWholeMilliseconds}"
                )
            }
            is SharedThreadDataSource.MembersEnrichOutcome.Failed -> {
                if (!routeStillCurrent(basePayload, "thread_members_load", "after_fetch")) {
                    return
                }
                // Transient failure: keep status as Error so the next
                // modal open (or Refresh) can retry. Do not reduce payload.
                _state.value = _state.value.copy(membersLoadStatus = LoadMembersStatus.Error)
                diagnosticsLogger?.invoke(
                    "thread_members_load_result threadId=$threadKey " +
                        "result=failed reason=${outcome.reason} " +
                        "members=${existing.members.size} candidates=${existing.memberCandidates.size} " +
                        "durationMs=${membersStartedAt.elapsedNow().inWholeMilliseconds}"
                )
            }
        }
    }

    private suspend fun applyRealtimeEvent(
        event: RealtimeEvent,
        activeReplyParentMessageId: MessageId? = null
    ) {
        val acceptedPayload = _state.value.payload
        val eventStaleToken = routeStaleToken
        val eventRouteIdentity = acceptedPayload.realtimeGapRouteIdentity()
        val currentSession = dataSource.readSession()
        val currentUserId = currentSession?.user?.id
        val readStateOwner = if (event is PerReceiverReadStateChanged) {
            dataSource.currentReplyCacheProvenance()?.threadPayloadOwnerScopeId
        } else null
        val repliesPageIdentity = captureThreadRepliesPageIdentity(acceptedPayload)
        val decision =
            realtimeGapRecoveryMutex.withLock {
                val currentPayload = _state.value.payload
                if (
                    !eventStaleToken.isCurrent(routeStaleToken) ||
                    currentPayload.realtimeGapRouteIdentity() != eventRouteIdentity ||
                    !routeStillCurrent(
                        acceptedPayload,
                        "thread_realtime_event",
                        "linearize",
                        currentPayload,
                    )
                ) {
                    return@withLock RealtimeEventDecision(
                        source = "stale",
                        messageCount = currentPayload.messages.size,
                        dropped = true,
                    )
                }
                if (event is PerReceiverReadStateChanged) {
                    val sessionServerId = currentSession?.currentServerId
                    val owner = visiblePayloadOwnerScopeId
                    if (!sessionServerId.isNullOrBlank() && !currentUserId.isNullOrBlank() &&
                        owner != null && owner == readStateOwner && (event.serverId == null || event.serverId.value == sessionServerId)) {
                        readStateProjection.observe(currentPayload, owner, event.scopes)
                        val emitted = reducePayloadPatch(cachePayload = true) { payload ->
                            readStateProjection.project(payload, owner)
                        }
                        return@withLock RealtimeEventDecision(source = "read_state", emitted = emitted,
                            messageCount = currentPayload.messages.size)
                    }
                    return@withLock RealtimeEventDecision(source = "read_state_identity_mismatch",
                        messageCount = currentPayload.messages.size, dropped = true)
                }
                dataSource.seedRealtimeThreadAuthorProfiles(
                    serverId = currentSession?.currentServerId,
                    event = event,
                    currentUserId = currentUserId
                )
                val acceptedRepliesProjection =
                    if (event is ThreadUpdated && repliesPageIdentity != null) {
                        threadRepliesSyncHost.ingestFrame(repliesPageIdentity, event)
                    } else {
                        null
                    }
                if (event is ThreadUpdated && acceptedRepliesProjection != null) {
                    event.latestReply?.let { reply ->
                        dataSource.seedAcceptedRealtimeThreadReplyAuthorProfile(
                            serverId = currentSession?.currentServerId,
                            reply = reply
                        )
                    }
                }
                if (event is ThreadUpdated) {
                    readStateProjection.contentChanged(event.threadChannelId.value, event.latestReply?.seq)
                    currentPayload.cacheThreadId()?.let { factThreadId ->
                        dataSource.recordReplyCountRealtimeFact(
                            threadId = factThreadId,
                            parentMessageId = event.parentMessageId.value,
                            fact =
                                SharedThreadReplyCountFact(
                                    threadChannelId = event.threadChannelId.value,
                                    replyCount = event.replyCount,
                                    observedAt = TimeSource.Monotonic.markNow()
                                )
                        )
                    }
                }
                val storeEvent =
                    if (event is ThreadUpdated) event.copy(latestReply = null) else event
                val eventIdentity =
                    when (event) {
                        is MessageNew -> {
                            val message = event.message
                            "type=MessageNew messageId=${message.id.value} " +
                                "threadId=${message.threadId.value} channelId=${message.channelId?.value.orEmpty()} " +
                                "threadChannelId=${message.threadChannelId?.value.orEmpty()} " +
                                "matches=${message.matchesSharedThread(currentPayload, activeReplyParentMessageId)}"
                        }
                        is ThreadUpdated -> {
                            val parentId = event.parentMessageId.value
                            "type=ThreadUpdated parentMessageId=$parentId " +
                                "threadMatch=${currentPayload.messages.any { it.id == parentId } || currentPayload.originMessage?.id == parentId}"
                        }
                        else -> "type=${event::class.simpleName.orEmpty()}"
                    }
                diagnosticsLogger?.invoke(
                    "thread_realtime_identity threadId=${currentPayload.sourceThreadId.orEmpty()} " +
                        "cacheThreadId=${currentPayload.cacheThreadId().orEmpty()} " +
                        "originParentMessageId=${currentPayload.originParentMessageId.orEmpty()} " +
                        "messagesBefore=${currentPayload.messages.size} $eventIdentity"
                )
                val realtimeGapServerSeq =
                    currentPayload.realtimeNewerGapServerSeq(
                        event = event,
                        activeReplyParentMessageId = activeReplyParentMessageId,
                    )
                if (realtimeGapServerSeq != null) {
                    val matchingRecovery =
                        realtimeGapRecoveryState?.takeIf { state ->
                            state.staleToken == eventStaleToken && state.routeIdentity == eventRouteIdentity
                        }
                    val matchingPagination =
                        loadNewerState?.takeIf { state ->
                            state.staleToken == eventStaleToken && state.routeIdentity == eventRouteIdentity
                        }
                    if (matchingRecovery != null || matchingPagination != null) {
                        val missingThroughSeq = (realtimeGapServerSeq - 1L).coerceAtLeast(0L)
                        if (matchingRecovery != null && missingThroughSeq > matchingRecovery.targetSeq) {
                            matchingRecovery.targetSeq = missingThroughSeq
                            matchingRecovery.targetVersion += 1L
                        }
                        if (
                            matchingPagination != null &&
                            missingThroughSeq > (matchingPagination.pendingTargetSeq ?: 0L)
                        ) {
                            matchingPagination.pendingTargetSeq = missingThroughSeq
                        }
                        val merged =
                            currentPayload
                                .copy(canLoadMoreAfter = false)
                                .applyRealtimeEvent(
                                    event = storeEvent,
                                    currentUserId = currentUserId,
                                    activeReplyParentMessageId = activeReplyParentMessageId,
                                    shouldPreserveReactionsForRealtime = { message, userId ->
                                        shouldPreserveReactionsForRealtime(message, userId)
                                    },
                                )
                                .withAcceptedThreadRepliesProjection(acceptedRepliesProjection)
                                .preserveStableTimelineFrom(
                                    previous = currentPayload,
                                    preserveSelectedReactions = false,
                                ).copy(canLoadMoreAfter = currentPayload.canLoadMoreAfter)
                        fun commitGapMerged(accepted: Map<String, ThreadRepliesAcceptedProjection>): Boolean {
                            val commitMerged =
                                merged.withAcceptedThreadRepliesProjection(
                                    acceptedRepliesProjection?.let { projection ->
                                        accepted[projection.identity.parentMessageId]
                                    },
                                )
                            return reducePayloadPatch(cachePayload = true) { latestPayload ->
                                latestPayload.withThreadContentProjectionFrom(commitMerged)
                            }
                        }
                        val emitted =
                            if (acceptedRepliesProjection == null) {
                                commitGapMerged(emptyMap())
                            } else {
                                threadRepliesSyncHost.withCurrentAcceptedProjections(
                                    listOf(acceptedRepliesProjection.identity),
                                ) { accepted -> commitGapMerged(accepted) }
                            }
                        return@withLock RealtimeEventDecision(
                            source = "in_flight_gap",
                            emitted = emitted,
                            messageCount = merged.messages.size,
                        )
                    }
                    val streamChannelId =
                        currentPayload.sourceThreadId
                            ?.takeIf { it.isNotBlank() }
                            ?.let(::ChannelId)
                            ?: currentPayload.streamChannelId
                                ?.takeIf { it.isNotBlank() }
                                ?.let(::ChannelId)
                            ?: return@withLock RealtimeEventDecision(
                                source = "none",
                                messageCount = currentPayload.messages.size,
                            )
                    val recoveryId = ++nextRealtimeGapRecoveryId
                    realtimeGapRecoveryState =
                        RealtimeGapRecoveryState(
                            id = recoveryId,
                            routeIdentity = eventRouteIdentity,
                            staleToken = eventStaleToken,
                            targetSeq = realtimeGapServerSeq,
                            targetVersion = 1L,
                        )
                    reducePayloadPatch(cachePayload = true) { latestPayload ->
                        latestPayload.copy(newerMessagesStatus = "loading", errorCode = null)
                    }
                    return@withLock RealtimeEventDecision(
                        source = "gap",
                        messageCount = currentPayload.messages.size,
                        recoveryId = recoveryId,
                        recoveryInitial = currentPayload,
                        recoveryRequest =
                            RealtimeVisibleStreamGapRequest(
                                channelId = streamChannelId,
                                sinceSeq = currentPayload.messages.mapNotNull { it.seq }.maxOrNull() ?: 0L,
                                serverSeq = realtimeGapServerSeq,
                            ),
                    )
                }

                val merged =
                    currentPayload.applyRealtimeEvent(
                        event = storeEvent,
                        currentUserId = currentUserId,
                        activeReplyParentMessageId = activeReplyParentMessageId,
                        shouldPreserveReactionsForRealtime = { message, userId ->
                            shouldPreserveReactionsForRealtime(message, userId)
                        },
                    ).withAcceptedThreadRepliesProjection(acceptedRepliesProjection)
                val replyCountGap =
                    merged.realtimeReplyCountGap(
                        event = event,
                        previous = currentPayload,
                        activeReplyParentMessageId = activeReplyParentMessageId,
                    )
                diagnosticsLogger?.invoke(
                    "thread_realtime_fold threadId=${currentPayload.sourceThreadId.orEmpty()} " +
                        "messagesBefore=${currentPayload.messages.size} messagesAfter=${merged.messages.size} " +
                        "samePayload=${merged === currentPayload} replyCountGap=${replyCountGap != null}"
                )
                if (merged === currentPayload) {
                    return@withLock RealtimeEventDecision(
                        source = "none",
                        messageCount = currentPayload.messages.size,
                        replyCountGap = replyCountGap,
                    )
                }
                val stableSelected =
                    merged.preserveStableTimelineFrom(
                        previous = currentPayload,
                        preserveSelectedReactions = false,
                    )
                // task #562 (agreed with KMP-专家/HanXin, adjusting #231's A'): the
                // first-frame candidate is a read-only SNAPSHOT of the live store's
                // merged state — a single merge algorithm, written through after the
                // reduce. Every open still forces a refresh, so any snapshot drift
                // lives only in the first-frame window and is corrected by the
                // canonical fetch through this same reducer. Raw events are never
                // written; durable local-store never rides this path.
                fun commitStableSelected(accepted: Map<String, ThreadRepliesAcceptedProjection>): Boolean {
                    val commitSelected =
                        stableSelected.withAcceptedThreadRepliesProjection(
                            acceptedRepliesProjection?.let { projection ->
                                accepted[projection.identity.parentMessageId]
                            },
                        )
                    return reducePayloadPatch(cachePayload = true) { latestPayload ->
                        latestPayload.withThreadContentProjectionFrom(commitSelected)
                    }
                }
                val emitted =
                    if (acceptedRepliesProjection == null) {
                        commitStableSelected(emptyMap())
                    } else {
                        threadRepliesSyncHost.withCurrentAcceptedProjections(
                            listOf(acceptedRepliesProjection.identity),
                        ) { accepted -> commitStableSelected(accepted) }
                    }
                RealtimeEventDecision(
                    source = "direct_latest",
                    emitted = emitted,
                    messageCount = stableSelected.messages.size,
                    replyCountGap = replyCountGap,
                )
            }

        if (decision.dropped) return
        val recoveryId = decision.recoveryId
        val recoveryInitial = decision.recoveryInitial
        val recoveryRequest = decision.recoveryRequest
        if (recoveryId != null && recoveryInitial != null && recoveryRequest != null) {
            diagnosticsLogger?.invoke(
                "thread_realtime_gap_detected threadId=${recoveryInitial.sourceThreadId.orEmpty()} " +
                    "visibleMaxSeq=${recoveryRequest.sinceSeq} serverSeq=${recoveryRequest.serverSeq}"
            )
            runRealtimeGapRecovery(
                initial = recoveryInitial,
                initialRequest = recoveryRequest,
                staleToken = eventStaleToken,
                recoveryId = recoveryId,
                tagAsGapRefill = true,
            )
            return
        }
        if (decision.source == "none" && decision.replyCountGap == null) {
            diagnosticsLogger?.invoke(
                "thread_realtime_apply_result threadId=${acceptedPayload.sourceThreadId.orEmpty()} " +
                    "source=none emitted=false messages=${decision.messageCount} reason=no_projection"
            )
            return
        }
        diagnosticsLogger?.invoke(
            "thread_realtime_apply_result threadId=${acceptedPayload.sourceThreadId.orEmpty()} " +
                "source=${decision.source} emitted=${decision.emitted} " +
                "messages=${decision.messageCount} cacheWarmed=${decision.emitted}"
        )
        val replyCountGap = decision.replyCountGap
        if (replyCountGap != null) {
            diagnosticsLogger?.invoke(
                "thread_realtime_reply_count_gap_detected threadId=${_state.value.payload.sourceThreadId.orEmpty()} " +
                    "visibleMaxSeq=${_state.value.payload.messages.mapNotNull { it.seq }.maxOrNull() ?: 0L} " +
                    "loadedReplies=${_state.value.payload.loadedReplyTimelineCount()} " +
                    "replyCount=${replyCountGap.replyCount}"
            )
            recoverRealtimeGapFromVisibleTail(replyCountGap)
        }
    }

    private data class ReplyCountRealtimeGap(
        val replyCount: Int
    )

    private fun KuiklyThreadScreenPayload.realtimeReplyCountGap(
        event: RealtimeEvent,
        previous: KuiklyThreadScreenPayload,
        activeReplyParentMessageId: MessageId? = null
    ): ReplyCountRealtimeGap? {
        if (event !is ThreadUpdated) return null
        if (surfaceKind != KuiklyThreadSurfaceKind.ReplyThread) return null
        if (canLoadMoreAfter || newerMessagesStatus == "loading") return null
        val visibleMaxSeq = messages.mapNotNull { it.seq }.maxOrNull() ?: 0L
        if (visibleMaxSeq <= 0L) return null
        val replyThreadContentChannelId = replyThreadContentChannelId() ?: return null
        val originParentId = originParentMessageId?.takeIf { it.isNotBlank() }?.let(::MessageId)
        val matchesThread =
            event.threadChannelId.value == replyThreadContentChannelId &&
                (
                    originParentId == null ||
                        event.parentMessageId == originParentId ||
                        event.parentMessageId == activeReplyParentMessageId
                    )
        if (!matchesThread) return null
        val loadedReplies = loadedReplyTimelineCount()
        val previousLoadedReplies = previous.loadedReplyTimelineCount()
        val loadedReplyDelta = (loadedReplies - previousLoadedReplies).coerceAtLeast(0)
        val previousReplyCount =
            previous.knownReplyCountFor(event, activeReplyParentMessageId)
                ?: previousLoadedReplies
        val replyDelta = event.replyCount - previousReplyCount
        if (replyDelta <= 0 || loadedReplyDelta >= replyDelta) return null
        return ReplyCountRealtimeGap(replyCount = event.replyCount)
    }

    private fun KuiklyThreadScreenPayload.loadedReplyTimelineCount(): Int =
        if (surfaceKind == KuiklyThreadSurfaceKind.ReplyThread) {
            val parentId = originParentMessageId?.takeIf { it.isNotBlank() }
            messages.count { message -> message.id != parentId }
        } else {
            messages.size
        }

    private fun KuiklyThreadScreenPayload.knownReplyCountFor(
        event: ThreadUpdated,
        activeReplyParentMessageId: MessageId?
    ): Int? {
        val parentIds =
            listOfNotNull(
                originParentMessageId?.takeIf { it.isNotBlank() },
                parentMessageId?.takeIf { it.isNotBlank() },
                activeReplyParentMessageId?.value,
                event.parentMessageId.value
            ).toSet()
        return (listOfNotNull(originMessage) + messages)
            .filter { message -> message.id in parentIds }
            .map { message -> message.replyCount }
            .maxOrNull()
    }

    private suspend fun markVisibleRead(visibleTailIndex: Int? = null) {
        val authorityState = _state.value
        if (
            authorityState.accessResolution != ThreadAccessResolution.Resolved ||
            !authorityState.writeActionsEnabled
        ) {
            diagnosticsLogger?.invoke("thread_read_skip reason=write_authority_closed")
            return
        }
        val expectedProvenance = dataSource.currentReplyCacheProvenance() ?: return
        if (visiblePayloadOwnerScopeId != expectedProvenance.threadPayloadOwnerScopeId) return
        val expectedRoute = authorityState.payload.resolveThreadRouteIdentity()
        fun authorityStillCurrent(): Boolean {
            val currentState = _state.value
            return currentState.accessResolution == ThreadAccessResolution.Resolved &&
                currentState.writeActionsEnabled &&
                currentState.payload.resolveThreadRouteIdentity() == expectedRoute &&
                dataSource.isCurrentReplyCacheProvenance(expectedProvenance)
        }
        if (!authorityStillCurrent()) return
        val marker = authorityState.payload.visibleReadMarker(visibleTailIndex) ?: return
        if (marker.wasRecentlyMarkedRead()) {
            diagnosticsLogger?.invoke(
                "thread_read_skip channelId=${marker.channelId} reason=recently_marked latest=${marker.latestMessageId}"
            )
            return
        }
        if (readInFlightMarker != null) {
            pendingReadMarker = marker
            diagnosticsLogger?.invoke(
                "thread_read_skip channelId=${marker.channelId} reason=in_flight latest=${marker.latestMessageId}"
            )
            return
        }

        var nextMarker: ThreadVisibleReadMarker? = marker
        while (nextMarker != null) {
            if (!authorityStillCurrent()) {
                pendingReadMarker = null
                return
            }
            val current = nextMarker
            pendingReadMarker = null
            readInFlightMarker = current
            val startedAt = TimeSource.Monotonic.markNow()
            diagnosticsLogger?.invoke(
                "thread_read_start channelId=${current.channelId} latest=${current.latestMessageId}"
            )
            try {
                val result =
                    dataSource.markVisibleChannelRead(
                        current.channelId,
                        current.latestSeq,
                        expectedProvenance = expectedProvenance
                    )
                if (
                    authorityStillCurrent() &&
                    (result is DataResult.Content || result is DataResult.Empty)
                ) {
                    lastCompletedReadMarker = current
                    lastCompletedReadAt = TimeSource.Monotonic.markNow()
                }
                diagnosticsLogger?.invoke(
                    "thread_read_result channelId=${current.channelId} result=${result.threadDiagnosticsResult()} " +
                        "mode=${if (current.latestSeq != null && current.latestSeq > 0L) "seq" else "all"} " +
                        "seq=${current.latestSeq ?: -1} latest=${current.latestMessageId} " +
                        "durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
                )
            } finally {
                readInFlightMarker = null
            }
            val pending = pendingReadMarker
            nextMarker =
                if (pending != null && pending != current && pending != lastCompletedReadMarker) {
                    pending
                } else {
                    pendingReadMarker = null
                    null
                }
        }
    }

    private fun ThreadVisibleReadMarker.wasRecentlyMarkedRead(): Boolean {
        val completed = lastCompletedReadMarker ?: return false
        if (completed.channelId != channelId) return false
        if (completed == this) return true
        val completedAt = lastCompletedReadAt ?: return false
        val ageMs = completedAt.elapsedNow().inWholeMilliseconds
        return ageMs in 0L until ThreadVisibleReadCoalesceWindowMillis
    }

    private suspend fun loadOlder() {
        val existing = _state.value.payload
        if (!existing.canLoadMoreBefore || existing.olderMessagesStatus == "loading") return
        val staleToken = routeStaleToken
        val beforeSeq = existing.messages.mapNotNull { it.seq }.minOrNull()
        if (beforeSeq == null) {
            reducePayloadPatch { currentPayload ->
                currentPayload.copy(olderMessagesStatus = "error", errorCode = "missing_before_seq")
            }
            return
        }
        reducePayloadPatch { currentPayload -> currentPayload.copy(olderMessagesStatus = "loading", errorCode = null) }
        val result = dataSource.fetchOlderThreadPayload(existing, beforeSeq)
        val latest = _state.value.payload
        if (!routeStillCurrent(existing, "thread_page", "older_after_fetch", latest)) {
            return
        }
        val nextPayload =
            when (result) {
                is DataResult.Content -> {
                    val merged =
                        latest
                        .mergePagedMessages(
                            pageMessages = result.data.payload.messages,
                            before = true,
                            pageSize = result.data.requestedPageSize
                        )
                        .preserveStableTimelineFrom(latest)
                    dataSource
                        .cacheCanonicalPayload(merged, visiblePayloadOwnerScopeId)
                        .withRouteContext(existing)
                        .preserveStableTimelineFrom(latest)
                }
                is DataResult.Empty ->
                    latest.copy(canLoadMoreBefore = false, olderMessagesStatus = "idle")
                is DataResult.Error ->
                    latest.copy(olderMessagesStatus = "error", errorCode = result.error.code)
                is DataResult.NoPermission ->
                    latest.copy(olderMessagesStatus = "error", errorCode = result.blocker.code)
            }
        reducePayloadPatch { currentPayload ->
            if (!routeStillCurrent(existing, "thread_page", "older_apply", currentPayload)) {
                return@reducePayloadPatch currentPayload
            }
            currentPayload.withThreadContentProjectionFrom(nextPayload)
        }
        val applied = _state.value.payload
        if (
            result is DataResult.Content &&
            staleToken.isCurrent(routeStaleToken) &&
            routeStillCurrent(existing, "thread_page", "older_secondary", applied)
        ) {
            launchSecondaryTimelineRefresh(applied, staleToken)
            launchDurableWindowExtension(existing, result.data, ThreadDurablePageDirection.Older, beforeSeq)
        }
    }

    /**
     * Offline cache paging entry (task #168): offer an applied older / newer page to the durable V2
     * window. Fire-and-forget on the store scope like the enter snapshot: it never touches the
     * visible payload, and a failure is only logged.
     */
    private fun launchDurableWindowExtension(
        routePayload: KuiklyThreadScreenPayload,
        fetched: SharedThreadPageFetchResult,
        direction: ThreadDurablePageDirection,
        anchorSeq: Long,
    ) {
        val durablePage = fetched.durablePage ?: return
        val routeIdentity = routePayload.resolveThreadRouteIdentity()
        scope.launch {
            try {
                dataSource.extendDurableWindowIfCurrent(
                    routeIdentity = routeIdentity,
                    context = durablePage.context,
                    direction = direction,
                    anchorSeq = anchorSeq,
                    rawPage = durablePage.rawPage,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                diagnosticsLogger?.invoke(
                    "thread_durable_page_extend_failed direction=${direction.wireName} " +
                        "reason=${throwable::class.simpleName ?: "unknown"}"
                )
            }
        }
    }

    suspend fun loadNewer(): ThreadNewerPageLoadOutcome {
        val initial = _state.value.payload
        if (!initial.canLoadMoreAfter || initial.newerMessagesStatus == "loading") {
            return ThreadNewerPageLoadOutcome.Ignored
        }
        val afterSeq = initial.messages.mapNotNull { it.seq }.maxOrNull()
        if (afterSeq == null) {
            reducePayloadPatch { currentPayload ->
                currentPayload.copy(newerMessagesStatus = "error", errorCode = "missing_after_seq")
            }
            return ThreadNewerPageLoadOutcome.Failed
        }
        val staleToken = routeStaleToken
        val routeIdentity = initial.realtimeGapRouteIdentity()
        val loadId =
            realtimeGapRecoveryMutex.withLock {
                val currentPayload = _state.value.payload
                val active = loadNewerState
                val activeRecovery = realtimeGapRecoveryState
                if (
                    !staleToken.isCurrent(routeStaleToken) ||
                    currentPayload.realtimeGapRouteIdentity() != routeIdentity ||
                    (
                        active != null &&
                            active.routeIdentity == routeIdentity &&
                            active.staleToken == staleToken
                    ) ||
                    (
                        activeRecovery != null &&
                            activeRecovery.routeIdentity == routeIdentity &&
                            activeRecovery.staleToken == staleToken
                    )
                ) {
                    null
                } else {
                    val id = ++nextLoadNewerId
                    loadNewerState =
                        LoadNewerState(
                            id = id,
                            routeIdentity = routeIdentity,
                            staleToken = staleToken,
                        )
                    reducePayloadPatch(cachePayload = true) { latestPayload ->
                        latestPayload.copy(newerMessagesStatus = "loading", errorCode = null)
                    }
                    id
                }
            } ?: return ThreadNewerPageLoadOutcome.Ignored
        var successorRecovery: Pair<KuiklyThreadScreenPayload, RealtimeVisibleStreamGapRequest>? = null
        var outcome: ThreadNewerPageLoadOutcome = ThreadNewerPageLoadOutcome.Ignored
        try {
            val result = dataSource.fetchNewerThreadPayload(initial, afterSeq)
            realtimeGapRecoveryMutex.withLock {
                val active =
                    loadNewerState
                        ?.takeIf { state -> state.id == loadId }
                        ?: return@withLock
                val currentPayload = _state.value.payload
                if (
                    !staleTokenStillCurrent(
                        staleToken,
                        "thread_page",
                        "newer_after_fetch",
                        initial,
                        currentPayload,
                    ) ||
                    !routeStillCurrent(
                        initial,
                        "thread_page",
                        "newer_after_fetch",
                        currentPayload,
                    )
                ) {
                    loadNewerState = null
                    return@withLock
                }
                val pageMaxSeq =
                    when (result) {
                        is DataResult.Content ->
                            result.data.payload.messages.mapNotNull { message -> message.seq }.maxOrNull() ?: afterSeq
                        else -> afterSeq
                    }
                val nextPayload =
                    when (result) {
                        is DataResult.Content -> {
                            val merged =
                                currentPayload
                                    .mergePagedMessages(
                                        pageMessages = result.data.payload.messages,
                                        before = false,
                                        pageSize = result.data.requestedPageSize
                                    )
                                    .preserveStableTimelineFrom(currentPayload)
                                    .withGapRefillTagFrom(currentPayload)
                            dataSource
                                .cacheCanonicalPayload(merged, visiblePayloadOwnerScopeId)
                                .withRouteContext(initial)
                                .preserveStableTimelineFrom(currentPayload)
                        }
                        is DataResult.Empty ->
                            currentPayload.copy(canLoadMoreAfter = false, newerMessagesStatus = "idle")
                        is DataResult.Error ->
                            currentPayload.copy(newerMessagesStatus = "error", errorCode = result.error.code)
                        is DataResult.NoPermission ->
                            currentPayload.copy(newerMessagesStatus = "error", errorCode = result.blocker.code)
                    }
                var appliedToCurrentRoute = false
                reducePayloadPatch(cachePayload = true) { latestPayload ->
                    if (
                        !staleTokenStillCurrent(
                            staleToken,
                            "thread_page",
                            "newer_apply",
                            initial,
                            latestPayload,
                        ) ||
                        !routeStillCurrent(
                            initial,
                            "thread_page",
                            "newer_apply",
                            latestPayload,
                        )
                    ) {
                        return@reducePayloadPatch latestPayload
                    }
                    appliedToCurrentRoute = true
                    latestPayload.withThreadContentProjectionFrom(nextPayload)
                }
                if (appliedToCurrentRoute && result is DataResult.Content) {
                    launchDurableWindowExtension(initial, result.data, ThreadDurablePageDirection.Newer, afterSeq)
                }
                if (appliedToCurrentRoute) {
                    outcome =
                        when (result) {
                            is DataResult.Content -> {
                                val existingIds = currentPayload.messages.mapTo(mutableSetOf()) { it.id }
                                val appliedIds = _state.value.payload.messages.mapTo(mutableSetOf()) { it.id }
                                val addedMessageIds =
                                    result.data.payload.messages
                                        .map { it.id }
                                        .filter { id -> id.isNotBlank() && id !in existingIds && id in appliedIds }
                                        .distinct()
                                if (addedMessageIds.isEmpty()) {
                                    ThreadNewerPageLoadOutcome.Empty
                                } else {
                                    ThreadNewerPageLoadOutcome.Applied(addedMessageIds)
                                }
                            }
                            is DataResult.Empty -> ThreadNewerPageLoadOutcome.Empty
                            is DataResult.Error,
                            is DataResult.NoPermission -> ThreadNewerPageLoadOutcome.Failed
                        }
                }
                loadNewerState = null
                val pendingTarget = active.pendingTargetSeq
                if (pendingTarget != null && pendingTarget > pageMaxSeq) {
                    val streamChannelId =
                        currentPayload.sourceThreadId
                            ?.takeIf { it.isNotBlank() }
                            ?.let(::ChannelId)
                            ?: currentPayload.streamChannelId
                                ?.takeIf { it.isNotBlank() }
                                ?.let(::ChannelId)
                    if (streamChannelId != null) {
                        successorRecovery =
                            _state.value.payload to
                                RealtimeVisibleStreamGapRequest(
                                    channelId = streamChannelId,
                                    sinceSeq = pageMaxSeq,
                                    serverSeq = pendingTarget,
                                )
                    }
                }
            }
        } finally {
            realtimeGapRecoveryMutex.withLock {
                val active = loadNewerState?.takeIf { state -> state.id == loadId }
                if (active != null) {
                    loadNewerState = null
                    val currentPayload = _state.value.payload
                    if (
                        active.staleToken == routeStaleToken &&
                        active.routeIdentity == currentPayload.realtimeGapRouteIdentity() &&
                        currentPayload.newerMessagesStatus == "loading"
                    ) {
                        reducePayloadPatch(cachePayload = true) { latestPayload ->
                            latestPayload.copy(newerMessagesStatus = "idle")
                        }
                    }
                }
            }
        }
        successorRecovery?.let { (successorInitial, request) ->
            recoverRealtimeGap(successorInitial, request, staleToken, tagAsGapRefill = true)
        }
        return outcome
    }

    /**
     * Replaces a focused/windowed timeline with the canonical tail in one request.
     *
     * Back-to-bottom is an explicit destination intent, not incremental pagination:
     * walking `loadNewer()` one page at a time leaves the viewport at each rendered
     * window boundary and makes progress depend on another edge/drag callback.
     */
    suspend fun jumpToLatest(): Boolean {
        val initial = _state.value.payload
        val tailRoute =
            (lastRoutePayload ?: initial.withoutThreadTimelineContent())
                .withRouteIntentFrom(initial)
                .copy(focusedMessageId = null)
                .withoutTransientLocalSendingMessages()
        lastRoutePayload = tailRoute
        if (!initial.canLoadMoreAfter) {
            reducePayloadPatch { current -> current.copy(focusedMessageId = null) }
            diagnosticsLogger?.invoke(
                "thread_jump_to_latest result=already_at_tail messages=${initial.messages.size}"
            )
            return true
        }

        // Retire the focused-window intent before the canonical tail fetch.
        // Timeline projection deliberately does not own route-intent fields, so
        // leaving the old focus on the visible payload would preserve it when
        // the fetched tail content is committed.
        reducePayloadPatch { current -> current.copy(focusedMessageId = null) }

        // Invalidate an older focused refresh or incremental newer-page request.
        // Their stale-token checks prevent a late page from replacing the tail.
        loadJob?.cancel()
        loadJob = null
        secondaryTimelineRefreshJob?.cancel()
        secondaryTimelineRefreshJob = null
        pendingSecondaryTimelineApply = null
        val staleToken = advanceRouteStaleToken()
        diagnosticsLogger?.invoke(
            "thread_jump_to_latest result=requested visibleMessages=${initial.messages.size} " +
                "visibleMaxSeq=${initial.messages.mapNotNull { it.seq }.maxOrNull() ?: 0L}"
        )
        refresh(basePayload = tailRoute, staleToken = staleToken)

        val latest = _state.value.payload
        val reachedCanonicalTail =
            latest.resolveThreadRouteIdentity().canRetainThreadPageFor(tailRoute.resolveThreadRouteIdentity()) &&
                latest.focusedMessageId == null &&
                !latest.canLoadMoreAfter
        diagnosticsLogger?.invoke(
            "thread_jump_to_latest result=${if (reachedCanonicalTail) "applied" else "failed"} " +
                "messages=${latest.messages.size} canLoadMoreAfter=${latest.canLoadMoreAfter}"
        )
        return reachedCanonicalTail
    }

    private suspend fun recoverRealtimeGap(serverSeq: Long) {
        val initial = _state.value.payload
        val staleToken = routeStaleToken
        val streamChannelId =
            initial.sourceThreadId
                ?.takeIf { it.isNotBlank() }
                ?.let(::ChannelId)
                ?: initial.streamChannelId
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::ChannelId)
        val nextRequest =
            RealtimeVisibleStreamGapPolicy.syncRequest(
                channelId = streamChannelId,
                visibleMaxSeq = initial.messages.mapNotNull { it.seq }.maxOrNull() ?: 0L,
                serverSeq = serverSeq,
                loading = false,
            ) ?: return
        recoverRealtimeGap(initial, nextRequest, staleToken, tagAsGapRefill = true)
    }

    private suspend fun recoverRealtimeGapFromVisibleTail(replyCountGap: ReplyCountRealtimeGap) {
        val initial = _state.value.payload
        val staleToken = routeStaleToken
        val streamChannelId =
            initial.sourceThreadId
                ?.takeIf { it.isNotBlank() }
                ?.let(::ChannelId)
                ?: initial.streamChannelId
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::ChannelId)
                ?: return
        val sinceSeq = initial.messages.mapNotNull { it.seq }.maxOrNull()?.takeIf { it > 0L } ?: return
        if (initial.newerMessagesStatus == "loading") return
        recoverRealtimeGap(
            initial = initial,
            initialRequest =
                RealtimeVisibleStreamGapRequest(
                    channelId = streamChannelId,
                    sinceSeq = sinceSeq,
                    serverSeq = sinceSeq + replyCountGap.replyCount.toLong().coerceAtLeast(1L)
            ),
            staleToken = staleToken,
            tagAsGapRefill = true,
            replyCountTarget = replyCountGap.replyCount,
        )
    }

    private suspend fun refreshLatestAfterVisibleMaxSeq() {
        val initial = _state.value.payload
        val staleToken = routeStaleToken
        val streamChannelId =
            initial.sourceThreadId
                ?.takeIf { it.isNotBlank() }
                ?.let(::ChannelId)
                ?: initial.streamChannelId
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::ChannelId)
                ?: return
        val sinceSeq = initial.messages.mapNotNull { it.seq }.maxOrNull()?.takeIf { it > 0L } ?: return
        if (initial.newerMessagesStatus == "loading") return
        recoverRealtimeGap(
            initial = initial,
            initialRequest =
                RealtimeVisibleStreamGapRequest(
                    channelId = streamChannelId,
                    sinceSeq = sinceSeq,
                    serverSeq = sinceSeq + 1L
                ),
            staleToken = staleToken,
            // Resume/visible refresh arrivals are genuinely new to the user and
            // keep counting toward ↓ x new (frozen task #232 contract); only
            // foreground gap recovery is tagged as refill.
            tagAsGapRefill = false,
            // Replies posted while backgrounded are not top-level messages, so the sync above can come
            // back empty while reply counts are stale (ad1df6b7): always re-read the reply summaries.
            backfillAfterRecovery = true,
        )
    }

    private suspend fun recoverRealtimeGap(
        initial: KuiklyThreadScreenPayload,
        initialRequest: RealtimeVisibleStreamGapRequest,
        staleToken: MviStaleToken,
        tagAsGapRefill: Boolean,
        replyCountTarget: Int? = null,
        backfillAfterRecovery: Boolean = false,
    ) {
        val routeIdentity = initial.realtimeGapRouteIdentity()
        val recoveryId =
            realtimeGapRecoveryMutex.withLock {
                val currentPayload = _state.value.payload
                if (
                    !staleToken.isCurrent(routeStaleToken) ||
                    currentPayload.realtimeGapRouteIdentity() != routeIdentity ||
                    !routeStillCurrent(
                        initial,
                        "thread_realtime_gap",
                        "start",
                        currentPayload,
                    )
                ) {
                    return@withLock null
                }
                val active = realtimeGapRecoveryState
                val pagination = loadNewerState
                if (
                    active != null &&
                    active.routeIdentity == routeIdentity &&
                    active.staleToken == staleToken
                ) {
                    if (initialRequest.serverSeq > active.targetSeq) {
                        active.targetSeq = initialRequest.serverSeq
                        active.targetVersion += 1L
                    }
                    if (backfillAfterRecovery) active.backfillAfterRecovery = true
                    null
                } else if (
                    pagination != null &&
                    pagination.routeIdentity == routeIdentity &&
                    pagination.staleToken == staleToken
                ) {
                    if (initialRequest.serverSeq > (pagination.pendingTargetSeq ?: 0L)) {
                        pagination.pendingTargetSeq = initialRequest.serverSeq
                    }
                    null
                } else {
                    val id = ++nextRealtimeGapRecoveryId
                    realtimeGapRecoveryState =
                        RealtimeGapRecoveryState(
                            id = id,
                            routeIdentity = routeIdentity,
                            staleToken = staleToken,
                            targetSeq = initialRequest.serverSeq,
                            targetVersion = 1L,
                            replyCountTarget = replyCountTarget,
                            backfillAfterRecovery = backfillAfterRecovery,
                        )
                    reducePayloadPatch(cachePayload = true) { currentPayload ->
                        currentPayload.copy(newerMessagesStatus = "loading", errorCode = null)
                    }
                    id
                }
            } ?: return
        runRealtimeGapRecovery(
            initial, initialRequest, staleToken, recoveryId, tagAsGapRefill,
            replyCountTarget = replyCountTarget,
        )
    }

    private suspend fun runRealtimeGapRecovery(
        initial: KuiklyThreadScreenPayload,
        initialRequest: RealtimeVisibleStreamGapRequest,
        staleToken: MviStaleToken,
        recoveryId: Long,
        tagAsGapRefill: Boolean,
        replyCountTarget: Int? = null,
    ) {
        var nextRequest = initialRequest
        // When true, the next fetch uses a full channel tail refetch
        // (`fetchReplyThreadTailGapPayload`) instead of the `messageSync` delta.
        // Armed once a delta pass cannot satisfy the reply-count target.
        var tailFallbackArmed = false
        // Set when any Content page merged genuinely new messages into the
        // timeline. Resume arrivals land here (messageSync 200), but nothing
        // else re-triggers the secondary (thread summary / followed) refresh
        // that the initial route load performs — so reply summaries stay stale
        // until the next full route reload (task #317).
        var mergedNewArrivals = false
        // Copied from this recovery's state while it is live: a resume that coalesced into it may have set it.
        var backfillRequested = false
        try {
            while (true) {
                val requestSnapshot =
                    realtimeGapRecoveryMutex.withLock {
                        val active =
                            realtimeGapRecoveryState
                                ?.takeIf { state -> state.id == recoveryId }
                                ?: return@withLock null
                        val currentPayload = _state.value.payload
                        if (
                            !staleToken.isCurrent(routeStaleToken) ||
                            active.routeIdentity != currentPayload.realtimeGapRouteIdentity() ||
                            !routeStillCurrent(
                                initial,
                                "thread_realtime_gap",
                                "before_fetch",
                                currentPayload,
                            )
                        ) {
                            realtimeGapRecoveryState = null
                            return@withLock null
                        }
                        active.targetVersion to currentPayload
                    } ?: return
                val requestTargetVersion = requestSnapshot.first
                val requestPayload = requestSnapshot.second
                val result =
                    if (tailFallbackArmed) {
                        dataSource.fetchReplyThreadTailGapPayload(requestPayload)
                    } else {
                        dataSource.fetchRealtimeGapThreadPayload(requestPayload, nextRequest.sinceSeq)
                    }
                var shouldContinue = false
                var nextSinceSeq = nextRequest.sinceSeq
                var armTailFallback = false
                realtimeGapRecoveryMutex.withLock {
                    val recovery =
                        realtimeGapRecoveryState
                            ?.takeIf { state -> state.id == recoveryId }
                            ?: return@withLock
                    // Read before any branch below clears the state (an empty or final page ends the
                    // recovery here, not in `finally`); a resume that coalesced mid-fetch set it on this state.
                    if (recovery.backfillAfterRecovery) backfillRequested = true
                    val currentPayload = _state.value.payload
                    if (
                        !staleTokenStillCurrent(
                            staleToken,
                            "thread_realtime_gap",
                            "after_fetch",
                            initial,
                            currentPayload,
                        ) ||
                        !routeStillCurrent(
                            initial,
                            "thread_realtime_gap",
                            "after_fetch",
                            currentPayload,
                        )
                    ) {
                        realtimeGapRecoveryState = null
                        return@withLock
                    }
                    when (result) {
                        is DataResult.Content -> {
                            val pageMessages = result.data.payload.messages
                            nextSinceSeq =
                                pageMessages
                                    .mapNotNull { message -> message.seq }
                                    .maxOrNull()
                                    ?: nextRequest.sinceSeq
                            val madeProgress = nextSinceSeq > nextRequest.sinceSeq
                            val fetchedFullPage =
                                pageMessages.size >= result.data.requestedPageSize
                            val targetAdvancedDuringFetch = recovery.targetVersion > requestTargetVersion
                            val merged =
                                currentPayload
                                    .mergePagedMessages(
                                        pageMessages = pageMessages,
                                        before = false,
                                        pageSize = result.data.requestedPageSize
                                    )
                                    .preserveStableTimelineFrom(currentPayload)
                                    .copy(
                                        canLoadMoreAfter = false,
                                        newerMessagesStatus = "idle",
                                    )
                                    .let { mergedPayload ->
                                        if (tagAsGapRefill) {
                                            mergedPayload.withGapRefillTagFrom(currentPayload)
                                        } else {
                                            mergedPayload
                                        }
                                    }
                            val selected =
                                dataSource
                                    .cacheCanonicalPayload(merged, visiblePayloadOwnerScopeId)
                                    .withRouteContext(initial)
                                    .preserveStableTimelineFrom(currentPayload)
                            reducePayloadPatch { latestPayload ->
                                if (
                                    !staleTokenStillCurrent(
                                        staleToken,
                                        "thread_realtime_gap",
                                        "apply",
                                        initial,
                                        latestPayload,
                                    ) ||
                                    !routeStillCurrent(
                                        initial,
                                        "thread_realtime_gap",
                                        "apply",
                                        latestPayload,
                                    )
                                ) {
                                    return@reducePayloadPatch latestPayload
                                }
                                latestPayload.withThreadContentProjectionFrom(selected)
                            }
                            if (madeProgress) {
                                mergedNewArrivals = true
                            }
                            val replyCountShortfall =
                                replyCountTarget != null &&
                                    merged.loadedReplyTimelineCount() < replyCountTarget
                            if (tailFallbackArmed) {
                                // Tail refetch ran once; regardless of shortfall we stop —
                                // the timeline now reflects the server's authoritative tail.
                                shouldContinue = false
                                realtimeGapRecoveryState = null
                            } else if (replyCountShortfall && !recovery.replyCountTailFallbackUsed) {
                                // The delta can't reach replies stranded below the visible
                                // tail seq — arm the one-shot tail refetch. Keep
                                // newerMessagesStatus=loading so the UI shows a
                                // pending tail and the gap detector stays gated.
                                recovery.replyCountTailFallbackUsed = true
                                armTailFallback = true
                                shouldContinue = true
                                reducePayloadPatch(cachePayload = true) { latestPayload ->
                                    latestPayload.copy(newerMessagesStatus = "loading")
                                }
                            } else {
                                shouldContinue =
                                    madeProgress &&
                                    nextSinceSeq < recovery.targetSeq &&
                                    (fetchedFullPage || targetAdvancedDuringFetch)
                                if (!shouldContinue) {
                                    realtimeGapRecoveryState = null
                                }
                            }
                        }
                        is DataResult.Empty -> {
                            val replyCountShortfall =
                                replyCountTarget != null &&
                                    currentPayload.loadedReplyTimelineCount() < replyCountTarget
                            if (!tailFallbackArmed && replyCountShortfall && !recovery.replyCountTailFallbackUsed) {
                                // Delta returned empty but replies are still owed — the
                                // missing replies live below the visible tail seq, so a
                                // `sinceSeq` sync can never produce them. Arm tail refetch.
                                recovery.replyCountTailFallbackUsed = true
                                armTailFallback = true
                                shouldContinue = true
                                reducePayloadPatch(cachePayload = true) { latestPayload ->
                                    latestPayload.copy(newerMessagesStatus = "loading")
                                }
                            } else {
                                reducePayloadPatch(cachePayload = true) { latestPayload ->
                                    latestPayload.copy(
                                        canLoadMoreAfter = false,
                                        newerMessagesStatus = "idle",
                                    )
                                }
                                realtimeGapRecoveryState = null
                            }
                        }
                        is DataResult.Error -> {
                            reducePayloadPatch(cachePayload = true) { latestPayload ->
                                latestPayload.copy(
                                    newerMessagesStatus = "error",
                                    errorCode = result.error.code,
                                )
                            }
                            realtimeGapRecoveryState = null
                        }
                        is DataResult.NoPermission -> {
                            reducePayloadPatch(cachePayload = true) { latestPayload ->
                                latestPayload.copy(
                                    newerMessagesStatus = "error",
                                    errorCode = result.blocker.code,
                                )
                            }
                            realtimeGapRecoveryState = null
                        }
                    }
                }
                if (armTailFallback) {
                    tailFallbackArmed = true
                    diagnosticsLogger?.invoke(
                        "thread_reply_count_tail_fallback threadId=${initial.sourceThreadId.orEmpty()} " +
                            "loadedReplies=${_state.value.payload.loadedReplyTimelineCount()} " +
                            "replyCount=${replyCountTarget ?: -1}"
                    )
                }
                if (!shouldContinue) break
                nextRequest = nextRequest.copy(sinceSeq = nextSinceSeq)
            }
        } finally {
            realtimeGapRecoveryMutex.withLock {
                val active = realtimeGapRecoveryState?.takeIf { state -> state.id == recoveryId }
                if (active?.backfillAfterRecovery == true) backfillRequested = true
                if (active != null) {
                    realtimeGapRecoveryState = null
                    val currentPayload = _state.value.payload
                    if (
                        active.staleToken == routeStaleToken &&
                        active.routeIdentity == currentPayload.realtimeGapRouteIdentity() &&
                        currentPayload.newerMessagesStatus == "loading"
                    ) {
                        reducePayloadPatch(cachePayload = true) { latestPayload ->
                            latestPayload.copy(newerMessagesStatus = "idle")
                        }
                    }
                }
            }
        }
        if (!mergedNewArrivals && !backfillRequested) return
        val latest = _state.value.payload
        if (
            !staleTokenStillCurrent(
                staleToken,
                "thread_secondary_backfill",
                "after_gap_recovery",
                initial,
                latest,
            ) ||
            !routeStillCurrent(
                initial,
                "thread_secondary_backfill",
                "after_gap_recovery",
                latest,
            )
        ) {
            return
        }
        diagnosticsLogger?.invoke(
            "thread_secondary_backfill threadId=${initial.sourceThreadId.orEmpty()} " +
                "trigger=${if (mergedNewArrivals) "resume_gap" else "resume"}"
        )
        launchSecondaryTimelineRefresh(initial, staleToken)
    }

    private suspend fun appendSentPage(page: ThreadMessagesPage) {
        if (page.messages.isEmpty()) return
        val routePayload = _state.value.payload
        val session = dataSource.readSession()
        reducePayloadPatchAndCache(cachePayload = true) { payload ->
            if (!routeStillCurrent(routePayload, "thread_send", "append_apply", payload)) {
                return@reducePayloadPatchAndCache payload
            }
            val appended =
                appendSharedThreadSentMessageFromPage(
                    basePayload = payload,
                    page = page,
                    session = session,
                    diagnostics = diagnosticsLogger ?: strictProjectionDiagnosticsLogger,
                )
            val appendedCanonicalMessages =
                appended.messages.filter { appendedMessage ->
                    payload.messages.none { currentMessage -> currentMessage.id == appendedMessage.id }
                }
            val selected =
                appended
                    .copy(messages = appended.messages.absorbingCanonicalSendEchoes(appendedCanonicalMessages))
                    .withRouteContext(payload)
                    .preserveStableTimelineFrom(payload)
            payload.withThreadContentProjectionFrom(selected)
        }
    }

    internal fun admitLocalSend(
        message: KuiklyThreadMessageItem,
        expectedProvenance: ReplyCacheProvenance,
        expectedRoute: ThreadRouteIdentity,
        composerSeed: ThreadLocalSendComposerSeed? = null
    ): Boolean =
        dataSource.withCurrentReplyCacheProvenance(expectedProvenance) {
            val current = _state.value
            if (
                !current.writeActionsEnabled ||
                expectedProvenance.threadPayloadOwnerScopeId == null ||
                !current.payload.resolveThreadRouteIdentity().isPendingSendEquivalentTo(expectedRoute)
            ) {
                false
            } else {
                val prepared =
                    current.payload.copy(
                        focusedMessageId = null,
                        canLoadMoreAfter = false,
                        newerMessagesStatus = "idle"
                    )
                val withLocalSend = prepared.withLocalSendStarted(message)
                val nextPayload =
                    composerSeed?.let { seed ->
                        withLocalSend.consumeComposerSeedForSend(
                            sentBody = seed.sentBody,
                            sentAttachmentIds = seed.sentAttachmentIds
                        )
                    } ?: withLocalSend
                val durableMessage = nextPayload.messages.firstOrNull { it.id == message.id } ?: return@withCurrentReplyCacheProvenance false
                if (!dataSource.persistPendingLocalSend(expectedProvenance, expectedRoute, durableMessage)) {
                    diagnosticsLogger?.invoke(
                        "thread_send_admission_rejected reason=pending_journal_write_failed localId=${message.id}"
                    )
                    return@withCurrentReplyCacheProvenance false
                }
                val emitted = reduceState(current.copy(payload = nextPayload))
                if (!emitted) {
                    when (
                        dataSource.clearPendingLocalSend(
                            expectedProvenance,
                            expectedRoute,
                            durableMessage.id
                        )
                    ) {
                        ThreadPendingSendJournal.ConsumeResult.Failed ->
                            diagnosticsLogger?.invoke(
                                "thread_send_admission_rollback_failed localId=${durableMessage.id} " +
                                    "serverId=${expectedProvenance.serverId} route=${expectedRoute.instanceKey}"
                            )
                        ThreadPendingSendJournal.ConsumeResult.Deleted,
                        ThreadPendingSendJournal.ConsumeResult.NotFound -> Unit
                    }
                }
                emitted
            }
        } ?: false

    private suspend fun applyLocalSendFailure(
        messageId: String,
        errorCode: String?,
        errorMessage: String?,
        expectedProvenance: ReplyCacheProvenance,
        expectedRoute: ThreadRouteIdentity
    ) {
        if (!dataSource.isCurrentReplyCacheProvenanceForLocalSend(expectedProvenance)) return
        if (!_state.value.payload.resolveThreadRouteIdentity().isPendingSendEquivalentTo(expectedRoute)) return
        val currentProvenance =
            dataSource.currentReplyCacheProvenanceForLocalSend(expectedProvenance)
                ?: return
        val durableFailure =
            if (currentProvenance == expectedProvenance) {
                dataSource.markPendingLocalSendFailedForLocalSend(
                    expectedProvenance = expectedProvenance,
                    route = expectedRoute,
                    localId = messageId,
                    errorCode = errorCode,
                    errorMessage = errorMessage,
                )
            } else {
                // The auth lifecycle may have retired the pre-refresh journal partition while
                // the request was in flight. Re-admit the still-visible local intent into the
                // verified successor owner before marking it failed, so an explicit retry remains
                // durable after a token rotation.
                val pendingMessage =
                    _state.value.payload.messages.firstOrNull { message -> message.id == messageId }
                val rehomed =
                    pendingMessage != null &&
                    dataSource.persistPendingLocalSend(
                        provenance = currentProvenance,
                        route = expectedRoute,
                        message = pendingMessage,
                    ) &&
                    dataSource.markPendingLocalSendFailedForLocalSend(
                        expectedProvenance = currentProvenance,
                        route = expectedRoute,
                        localId = messageId,
                        errorCode = errorCode,
                        errorMessage = errorMessage,
                    )
                if (rehomed) {
                    when (
                        dataSource.clearPendingLocalSendForLocalSend(
                            expectedProvenance,
                            expectedRoute,
                            messageId,
                        )
                    ) {
                        ThreadPendingSendJournal.ConsumeResult.Failed ->
                            diagnosticsLogger?.invoke(
                                "thread_pending_send_failure_old_owner_cleanup_failed localId=$messageId " +
                                    "serverId=${expectedProvenance.serverId} route=${expectedRoute.instanceKey}"
                            )
                        ThreadPendingSendJournal.ConsumeResult.Deleted,
                        ThreadPendingSendJournal.ConsumeResult.NotFound -> Unit
                    }
                }
                rehomed
            }
        if (!durableFailure) {
            diagnosticsLogger?.invoke(
                "thread_send_failure_projection_blocked reason=pending_journal_write_failed localId=$messageId"
            )
            return
        }
        if (emitLocalSendFailureProjection(
                messageId = messageId,
                errorCode = errorCode,
                errorMessage = errorMessage,
                expectedProvenance = expectedProvenance,
                expectedRoute = expectedRoute,
            )
        ) {
            // The failure just landed in the journal as durable: arm the drainer so the
            // automatic retry path owns it from here (backoff + online/foreground triggers).
            pendingSendDrainer?.onSendFailed()
        }
    }

    /**
     * Hot-updates the open page's payload for a pending-send row that just turned failed, so the
     * failure marker + retry affordance render without requiring a page re-entry. Used by both the
     * live send path ([applyLocalSendFailure]) and the drain path, whose terminal/adoption failures
     * previously only wrote the journal and left the open page stale until re-entry (#1134 cell 3
     * follow-up).
     *
     * @return true when the open payload carries this route and the projection was applied.
     */
    private fun emitLocalSendFailureProjection(
        messageId: String,
        errorCode: String?,
        errorMessage: String?,
        expectedProvenance: ReplyCacheProvenance,
        expectedRoute: ThreadRouteIdentity,
    ): Boolean {
        val currentProvenance =
            dataSource.currentReplyCacheProvenanceForLocalSend(expectedProvenance) ?: return false
        val emitted =
            dataSource.withCurrentReplyCacheProvenanceForLocalSend(expectedProvenance) { _ ->
                val payload = _state.value.payload
                if (!payload.resolveThreadRouteIdentity().isPendingSendEquivalentTo(expectedRoute)) {
                    false
                } else {
                    reducePayloadPatch { current ->
                        current.withLocalSendFailure(messageId, errorCode, errorMessage)
                    }
                }
            } ?: false
        if (emitted) {
            dataSource.cachePayload(
                payload = _state.value.payload,
                ownerScopeId = currentProvenance.threadPayloadOwnerScopeId.orEmpty(),
                expectedProvenance = currentProvenance
            )
        }
        return emitted
    }

    private suspend fun dismissLocalFailedSend(messageId: String) {
        val provenance = dataSource.currentReplyCacheProvenance() ?: return
        val route = _state.value.payload.resolveThreadRouteIdentity()
        val cleanup = dataSource.clearPendingLocalSend(provenance, route, messageId)
        if (dataSource.hasDurablePendingSendJournal &&
            cleanup == ThreadPendingSendJournal.ConsumeResult.Failed
        ) {
            diagnosticsLogger?.invoke(
                "thread_send_dismiss_blocked reason=pending_journal_cleanup_${cleanup.name.lowercase()} " +
                    "localId=$messageId"
            )
            return
        }
        reducePayloadPatchAndCache(cachePayload = true) { payload ->
            payload.withoutLocalFailedSendMessage(messageId)
        }
    }

    private suspend fun cancelLocalSend(messageId: String) {
        val provenance = dataSource.currentReplyCacheProvenance() ?: return
        val route = _state.value.payload.resolveThreadRouteIdentity()
        val cleanup = dataSource.clearPendingLocalSend(provenance, route, messageId)
        if (dataSource.hasDurablePendingSendJournal &&
            cleanup == ThreadPendingSendJournal.ConsumeResult.Failed
        ) {
            diagnosticsLogger?.invoke(
                "thread_send_cancel_blocked reason=pending_journal_cleanup_${cleanup.name.lowercase()} " +
                    "localId=$messageId"
            )
            return
        }
        reducePayloadPatchAndCache(cachePayload = true) { payload ->
            payload.withoutLocalSendMessage(messageId)
        }
    }

    private suspend fun replaceLocalSendWithSentPage(
        messageId: String,
        page: ThreadMessagesPage,
        expectedProvenance: ReplyCacheProvenance,
        expectedRoute: ThreadRouteIdentity
    ) {
        if (page.messages.isEmpty()) return
        val routePayload = _state.value.payload
        val session = dataSource.readSession()
        // Build the replacement from the latest payload inside the reducer.
        // Two send responses and a realtime agent reply may interleave while
        // readSession/network/cache work is suspended; projecting a snapshot
        // captured before that suspension would make the last completion win
        // and temporarily hide the other canonical message until reload.
        fun applyToCurrentPayload(): Boolean =
            reducePayloadPatch { payload ->
                if (!routeStillCurrent(routePayload, "thread_send", "replace_apply", payload)) {
                    return@reducePayloadPatch payload
                }
                if (!payload.resolveThreadRouteIdentity().isPendingSendEquivalentTo(expectedRoute)) {
                    return@reducePayloadPatch payload
                }
                val appended =
                    appendSharedThreadSentMessageFromPage(
                        basePayload = payload,
                        page = page,
                        session = session,
                        trustedReplyThreadChannelId =
                            payload
                                .messages
                                .firstOrNull { it.id == messageId }
                                ?.pendingSendContext
                                ?.streamChannelId,
                        diagnostics = diagnosticsLogger ?: strictProjectionDiagnosticsLogger,
                    )
                val selected =
                    appended
                        .withoutLocalSendMessage(messageId)
                        .withRouteContext(payload)
                        .preserveStableTimelineFrom(payload)
                payload.withThreadContentProjectionFrom(selected)
            }
        val terminalProjection =
            dataSource.withCurrentReplyCacheProvenanceForLocalSend(expectedProvenance) { current ->
                current to applyToCurrentPayload()
            } ?: return
        val currentProvenance = terminalProjection.first
        if (terminalProjection.second) {
            val cached = dataSource.cachePayload(
                payload = _state.value.payload,
                ownerScopeId = currentProvenance.threadPayloadOwnerScopeId.orEmpty(),
                expectedProvenance = currentProvenance
            )
            if (!cached) {
                diagnosticsLogger?.invoke(
                    "thread_pending_send_success_cache_skipped localId=$messageId " +
                    "serverId=${currentProvenance.serverId} route=${expectedRoute.instanceKey}"
                )
            }
        }
        // Realtime may have consumed the row first. The durable send is still terminal after the
        // HTTP success, so journal cleanup is independent of the optional payload-cache commit
        // and of whether this page is still mounted on the original route.
        when (
            dataSource.clearPendingLocalSendForLocalSend(expectedProvenance, expectedRoute, messageId)
        ) {
            ThreadPendingSendJournal.ConsumeResult.Failed ->
                diagnosticsLogger?.invoke(
                    "thread_pending_send_success_cleanup_failed localId=$messageId " +
                        "serverId=${expectedProvenance.serverId} route=${expectedRoute.instanceKey}"
                )
            ThreadPendingSendJournal.ConsumeResult.Deleted,
            ThreadPendingSendJournal.ConsumeResult.NotFound -> Unit
        }
    }

    private fun applyMessageReactionSelection(messageId: String, emoji: String, selected: Boolean) {
        val guard = PendingReactionRealtimeGuard(
            messageId = messageId.trim(),
            emoji = emoji,
            selected = selected
        )
        if (guard.messageId.isNotBlank() && guard.emoji.isNotBlank()) {
            pendingReactionRealtimeGuards[guard.key] = guard
        }
        reducePayloadPatch { payload ->
            payload.withMessageReaction(messageId, emoji, selected)
        }
    }

    private fun applyMessageReactions(messageId: String, reactions: List<KuiklyThreadReactionItem>) {
        clearReactionRealtimeGuards(messageId)
        reducePayloadPatch { payload ->
            payload.withMessageReactions(messageId, reactions)
        }
    }

    private fun applyReplyThreadRead(parentMessageId: String, threadChannelId: String?) {
        reducePayloadPatch(cachePayload = true) { payload ->
            payload.withReplyThreadRead(parentMessageId, threadChannelId)
        }
    }

    private suspend fun applyMessageThreadFollowed(
        parentMessageId: String,
        threadChannelId: String?,
        followed: Boolean,
        replyCount: Int
    ) {
        reducePayloadPatchAndCache(cachePayload = true) { payload ->
            payload.withMessageThreadFollowed(parentMessageId, threadChannelId, followed, replyCount)
        }
    }

    private suspend fun applyMessageMutation(summary: MessageMutationSummary) {
        val session = dataSource.readSession()
        val currentUserId = session?.user?.id
        val currentUserName = session?.user?.bestDisplayName
        confirmReactionRealtimeGuards(summary, currentUserId)
        val currentPayload = _state.value.payload
        val canonicalPayload = currentPayload.withMessageMutationSummary(summary, currentUserId, currentUserName)
        val selected =
            dataSource
                .cacheCanonicalPayload(canonicalPayload, visiblePayloadOwnerScopeId)
                .withRouteContext(currentPayload)
                .preserveStableTimelineFrom(currentPayload, preserveSelectedReactions = false)
        reducePayloadPatch { payload ->
            if (!routeStillCurrent(currentPayload, "thread_reaction", "mutation_apply", payload)) {
                return@reducePayloadPatch payload
            }
            payload.withThreadContentProjectionFrom(selected)
        }
    }

    private fun shouldPreserveReactionsForRealtime(message: Message, currentUserId: String?): Boolean {
        val messageId = message.id.value
        if (message.reactions == null || pendingReactionRealtimeGuards.none { it.value.messageId == messageId }) {
            return false
        }
        val incomingReactions = message.reactions.toRealtimeThreadReactionItems(currentUserId).orEmpty()
        var preserveReactions = false
        pendingReactionRealtimeGuards
            .values
            .filter { guard -> guard.messageId == messageId }
            .forEach { guard ->
                val incomingSelected = incomingReactions.selectedForEmoji(guard.emoji)
                if (incomingSelected == guard.selected) {
                    pendingReactionRealtimeGuards.remove(guard.key)
                } else {
                    preserveReactions = true
                    pendingReactionRealtimeGuards.remove(guard.key)
                    diagnosticsLogger?.invoke(
                        "thread_realtime_reaction_frame_stale messageId=$messageId emoji=${guard.emoji} " +
                            "expectedSelected=${guard.selected} incomingSelected=$incomingSelected"
                    )
                }
            }
        return preserveReactions
    }

    private fun confirmReactionRealtimeGuards(summary: MessageMutationSummary, currentUserId: String?) {
        val messageId = summary.messageId.trim()
        if (messageId.isBlank()) return
        val canonicalReactions = summary.reactions.toThreadReactionItems(currentUserId)
        pendingReactionRealtimeGuards
            .values
            .filter { guard -> guard.messageId == messageId }
            .forEach { guard ->
                if (canonicalReactions.selectedForEmoji(guard.emoji) != guard.selected) {
                    pendingReactionRealtimeGuards.remove(guard.key)
                }
            }
    }

    private fun clearReactionRealtimeGuards(messageId: String) {
        val cleanMessageId = messageId.trim()
        if (cleanMessageId.isBlank()) return
        pendingReactionRealtimeGuards
            .values
            .filter { guard -> guard.messageId == cleanMessageId }
            .forEach { guard -> pendingReactionRealtimeGuards.remove(guard.key) }
    }

    private suspend fun applyActionCard(messageId: String, card: ThreadActionCard) {
        reducePayloadPatchAndCache(cachePayload = true) { payload -> payload.withActionCard(messageId, card) }
    }

    private suspend fun applyMessageSaved(messageId: String, saved: Boolean) {
        reducePayloadPatchAndCache(cachePayload = true) { payload -> payload.withMessageSaved(messageId, saved) }
    }

    private suspend fun applyChannelJoined(channelId: String) {
        reducePayloadPatchAndCache(cachePayload = true) { payload ->
            if (payload.joinChannelTargetId()?.value != channelId) {
                payload
            } else {
                payload.copy(
                    joined = true,
                    canSend = true,
                    composer = payload.composer.copy(enabled = true)
                )
            }
        }
    }

    private suspend fun applyMembersPatch(patch: SharedThreadIntent.ApplyMembersPatch) {
        reducePayloadPatchAndCache(cachePayload = true) { payload ->
            payload.copy(
                membersCount = patch.membersCount,
                members = patch.members,
                memberCandidates = patch.memberCandidates,
                canAddMembers = patch.canAddMembers,
                addMemberStatus = patch.addMemberStatus,
                addMemberErrorCode = patch.addMemberErrorCode,
                removeMemberStatus = patch.removeMemberStatus,
                removingMemberId = patch.removingMemberId,
                removeMemberErrorCode = patch.removeMemberErrorCode
            )
        }
    }

    private fun setAddMemberStatus(status: String, errorCode: String?) {
        reducePayloadPatch {
            it.copy(addMemberStatus = status, addMemberErrorCode = errorCode)
        }
    }

    private fun setRemoveMemberStatus(status: String, removingMemberId: String?, errorCode: String?) {
        reducePayloadPatch {
            it.copy(
                removeMemberStatus = status,
                removingMemberId = removingMemberId,
                removeMemberErrorCode = errorCode
            )
        }
    }

    private fun setPagingAvailability(canLoadMoreBefore: Boolean, canLoadMoreAfter: Boolean) {
        reducePayloadPatch {
            it.copy(
                canLoadMoreBefore = canLoadMoreBefore,
                canLoadMoreAfter = canLoadMoreAfter
            )
        }
    }

    private fun selectTab(tab: String) {
        val normalizedTab = tab.takeIf { it.isNotBlank() } ?: return
        reducePayloadPatch { it.copy(selectedTab = normalizedTab) }
    }

    private fun focusMessage(messageId: String, switchToTab: String?) {
        val normalizedMessageId = messageId.takeIf { it.isNotBlank() } ?: return
        reducePayloadPatch {
            it.copy(
                selectedTab = switchToTab?.takeIf { tab -> tab.isNotBlank() } ?: it.selectedTab,
                focusedMessageId = normalizedMessageId,
                focusedMessageRequestNonce = nextThreadFocusedMessageRequestNonce()
            )
        }
    }

    private suspend fun updateChannelProfile(intent: SharedThreadIntent.UpdateChannelProfile) {
        val current = _state.value
        val routeIdentity = current.payload.resolveThreadRouteIdentity()
        val staleToken = routeStaleToken
        if (
            routeIdentity.surfaceKind != KuiklyThreadSurfaceKind.Channel ||
            routeIdentity.timelineChannelId != intent.channelId ||
            current.channelProfileUpdateStatus == ChannelProfileUpdateStatus.InFlight
        ) {
            return
        }
        val name = intent.name.trim()
        val description = intent.description.trim()
        val originalName = intent.originalName.trim()
        val originalDescription = intent.originalDescription.trim()
        if (name == originalName && description == originalDescription) {
            diagnosticsLogger?.invoke(
                "thread_channel_profile_update_skip channelId=${intent.channelId} reason=unchanged"
            )
            reduceState(
                current.copy(
                    channelProfileUpdateStatus = ChannelProfileUpdateStatus.Succeeded,
                    channelProfileUpdateErrorCode = null
                )
            )
            return
        }
        diagnosticsLogger?.invoke(
            "thread_channel_profile_update_start channelId=${intent.channelId} " +
                "nameChanged=${name != originalName} descriptionChanged=${description != originalDescription}"
        )
        reduceState(
            current.copy(
                channelProfileUpdateStatus = ChannelProfileUpdateStatus.InFlight,
                channelProfileUpdateErrorCode = null
            )
        )
        val result = dataSource.updateChannelProfile(ChannelId(intent.channelId), name, description)
        if (!channelProfileUpdateStillCurrent(staleToken, routeIdentity, intent.channelId, "after_network")) {
            return
        }
        when (result) {
            is DataResult.Content -> {
                val canonical = result.data
                if (canonical.channelId != ChannelId(intent.channelId)) {
                    diagnosticsLogger?.invoke(
                        "thread_channel_profile_update_identity_mismatch requested=${intent.channelId} " +
                            "response=${canonical.channelId.value}"
                    )
                    failChannelProfileUpdate("channel_update_identity_mismatch")
                    return
                }
                reducePayloadPatch(cachePayload = true) { payload ->
                    payload.copy(title = canonical.name, subtitle = canonical.description)
                }
                try {
                    dataSource.publishChannelProfileUpdate(canonical)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (exception: Exception) {
                    diagnosticsLogger?.invoke(
                        "thread_channel_profile_update_projection_failed channelId=${canonical.channelId.value} " +
                            "exception=${exception::class.simpleName}"
                    )
                }
                if (!channelProfileUpdateStillCurrent(staleToken, routeIdentity, intent.channelId, "after_projection")) {
                    return
                }
                diagnosticsLogger?.invoke(
                    "thread_channel_profile_update_success channelId=${canonical.channelId.value}"
                )
                reduceState(
                    _state.value.copy(
                        channelProfileUpdateStatus = ChannelProfileUpdateStatus.Succeeded,
                        channelProfileUpdateErrorCode = null
                    )
                )
            }
            is DataResult.Error -> failChannelProfileUpdate(result.error.code)
            is DataResult.NoPermission -> failChannelProfileUpdate(result.blocker.code)
            is DataResult.Empty -> failChannelProfileUpdate("channel_update_empty")
        }
    }

    private fun channelProfileUpdateStillCurrent(
        staleToken: MviStaleToken,
        routeIdentity: ThreadRouteIdentity,
        channelId: String,
        stage: String
    ): Boolean {
        val currentPayload = _state.value.payload
        if (
            staleToken.isCurrent(routeStaleToken) &&
            currentPayload.resolveThreadRouteIdentity() == routeIdentity
        ) {
            return true
        }
        diagnosticsLogger?.invoke(
            "thread_channel_profile_update_stale_drop channelId=$channelId stage=$stage " +
                "token=${staleToken.value} currentToken=${routeStaleToken.value} " +
                "currentRoute=${currentPayload.resolveThreadRouteIdentity().instanceKey}"
        )
        return false
    }

    private fun failChannelProfileUpdate(errorCode: String) {
        diagnosticsLogger?.invoke("thread_channel_profile_update_failed errorCode=$errorCode")
        reduceState(
            _state.value.copy(
                channelProfileUpdateStatus = ChannelProfileUpdateStatus.Failed,
                channelProfileUpdateErrorCode = errorCode
            )
        )
    }

    private fun clearChannelProfileUpdateStatus() {
        reduceState(
            _state.value.copy(
                channelProfileUpdateStatus = ChannelProfileUpdateStatus.Idle,
                channelProfileUpdateErrorCode = null
            )
        )
    }

    private fun reduceState(nextState: SharedThreadState): Boolean {
        val previousState = _state.value
        val projectedState = if (nextState.payload === previousState.payload) nextState else nextState.copy(
            payload = readStateProjection.project(nextState.payload, visiblePayloadOwnerScopeId)
        )
        val synchronizedState = projectedState.withSynchronizedTimelineSlice()
        val emitted = synchronizedState != previousState
        if (!emitted) return false
        _state.value = synchronizedState
        return true
    }

    private suspend fun reducePayloadPatchAndCache(
        cachePayload: Boolean = false,
        patch: (KuiklyThreadScreenPayload) -> KuiklyThreadScreenPayload
    ): Boolean {
        val nextPayload = patch(_state.value.payload)
        val emitted = reduceState(_state.value.copy(payload = nextPayload))
        if (emitted && cachePayload) {
            dataSource.cachePayload(
                _state.value.payload,
                ownerScopeId = visiblePayloadOwnerScopeId.orEmpty()
            )
        }
        return emitted
    }

    private fun advanceRouteStaleToken(): MviStaleToken {
        routeStaleTokenValue += 1L
        routeStaleToken = MviStaleToken(routeStaleTokenValue)
        return routeStaleToken
    }

    private fun staleTokenStillCurrent(
        staleToken: MviStaleToken,
        source: String,
        stage: String,
        routePayload: KuiklyThreadScreenPayload,
        currentPayload: KuiklyThreadScreenPayload = _state.value.payload
    ): Boolean {
        if (staleToken.isCurrent(routeStaleToken)) {
            return true
        }
        diagnosticsLogger?.invoke(
            "${source}_stale_drop threadId=${routePayload.sourceThreadId.orEmpty()} " +
                "currentThreadId=${currentPayload.sourceThreadId.orEmpty()} stage=$stage " +
                "reason=stale_token token=${staleToken.value} currentToken=${routeStaleToken.value}"
        )
        return false
    }

    private fun routeStillCurrent(
        routePayload: KuiklyThreadScreenPayload,
        source: String,
        stage: String,
        currentPayload: KuiklyThreadScreenPayload = _state.value.payload
    ): Boolean {
        if (currentPayload.isUsableCachedPayloadForRoute(routePayload)) {
            return true
        }
        diagnosticsLogger?.invoke(
            "${source}_stale_drop threadId=${routePayload.sourceThreadId.orEmpty()} " +
                "currentThreadId=${currentPayload.sourceThreadId.orEmpty()} stage=$stage " +
                "routeSurface=${routePayload.surfaceKind} currentSurface=${currentPayload.surfaceKind} " +
                "routeParentMessageId=${routePayload.parentMessageId.orEmpty()} " +
                "currentParentMessageId=${currentPayload.parentMessageId.orEmpty()}"
        )
        return false
    }

    private fun reducePayloadPatch(
        cachePayload: Boolean = false,
        patch: (KuiklyThreadScreenPayload) -> KuiklyThreadScreenPayload
    ): Boolean {
        val nextPayload = patch(_state.value.payload)
        val emitted = reduceState(_state.value.copy(payload = nextPayload))
        if (emitted && cachePayload) {
            dataSource.cachePayload(
                _state.value.payload,
                ownerScopeId = visiblePayloadOwnerScopeId.orEmpty()
            )
        }
        return emitted
    }
}

internal class SharedThreadViewModel(
    private val dataSource: SharedThreadDataSource,
    private val initialPayload: KuiklyThreadScreenPayload = KuiklyThreadScreenPayload(),
    private val diagnosticsLogger: ((String) -> Unit)? = null
) : ViewModel() {
    private val storeDelegate =
        lazy {
            SharedThreadStore(
                dataSource = dataSource,
                scope = viewModelScope,
                diagnosticsLogger = diagnosticsLogger,
                initialPayload = initialPayload
            )
        }
    private val store: SharedThreadStore by storeDelegate
    val state: StateFlow<SharedThreadState>
        get() = store.state
    val currentPayload: KuiklyThreadScreenPayload
        get() = store.state.value.payload.withoutThreadTimelineContent()
    val currentTimelineSlice: ThreadTimelineSlice
        get() = store.state.value.timelineSlice
    val currentReplyMetadataByParentId: Map<String, ThreadReplyMetadata>
        get() = store.state.value.replyMetadataByParentId
    val currentHighlightedMentions: Set<String>
        get() = store.state.value.highlightedMentions
    val currentRenderSlice: SharedThreadRenderSlice
        get() = store.state.value.toRenderSlice()

    fun payloadFlow(): Flow<KuiklyThreadScreenPayload> = store.payloadFlow()

    fun renderSliceFlow(): Flow<SharedThreadRenderSlice> = store.renderSliceFlow()

    fun timelineSliceFlow(): Flow<ThreadTimelineSlice> = store.timelineSliceFlow()

    fun replyMetadataFlow(): Flow<Map<String, ThreadReplyMetadata>> = store.replyMetadataFlow()

    fun highlightedMentionsFlow(): Flow<Set<String>> = store.highlightedMentionsFlow()

    fun dispatch(intent: SharedThreadIntent) {
        store.dispatch(intent)
    }

    internal fun triggerPendingSendDrain(reason: String) {
        store.triggerPendingSendDrain(reason)
    }

    fun admitLocalSend(
        message: KuiklyThreadMessageItem,
        expectedProvenance: ReplyCacheProvenance,
        expectedRoute: ThreadRouteIdentity,
        composerSeed: ThreadLocalSendComposerSeed? = null
    ): Boolean =
        store.admitLocalSend(
            message = message,
            expectedProvenance = expectedProvenance,
            expectedRoute = expectedRoute,
            composerSeed = composerSeed
        )

    suspend fun accept(intent: SharedThreadIntent) {
        store.accept(intent)
    }

    suspend fun loadNewerPage(): ThreadNewerPageLoadOutcome = store.loadNewer()

    suspend fun jumpToLatest(): Boolean = store.jumpToLatest()

    override fun onCleared() {
        if (storeDelegate.isInitialized()) {
            store.clear()
        }
        super.onCleared()
    }
}

private fun KuiklyThreadScreenPayload.asRefreshFailed(code: String): KuiklyThreadScreenPayload =
    if (hasRenderableThreadContent()) {
        copy(status = "content", backgroundRefreshing = false, errorCode = code, errorServerMessage = null)
    } else if (isDurableEmptyStandIn()) {
        // Task #1137: an empty last-known channel survives a transient failure like a non-empty one does.
        copy(backgroundRefreshing = false, errorCode = code, errorServerMessage = null)
    } else {
        copy(status = "error", backgroundRefreshing = false, errorCode = code, errorServerMessage = null)
    }

private fun KuiklyThreadScreenPayload.asRefreshNoPermission(
    base: KuiklyThreadScreenPayload,
    code: String,
    serverMessage: String? = null
): KuiklyThreadScreenPayload =
    withRouteContext(base)
        .withoutThreadTimelineContent()
        .copy(
            status = "no_permission",
            backgroundRefreshing = false,
            errorCode = code,
            errorServerMessage = serverMessage,
            focusedMessageId = base.focusedMessageId,
            focusedMessageRequestNonce = base.focusedMessageRequestNonce
        )

private fun KuiklyThreadScreenPayload.asRefreshEmpty(base: KuiklyThreadScreenPayload): KuiklyThreadScreenPayload =
    withRouteContext(base).copy(
        status = "empty",
        backgroundRefreshing = false,
        errorCode = null,
        errorServerMessage = null,
        messages = emptyList(),
        canLoadMoreBefore = false,
        canLoadMoreAfter = false,
        olderMessagesStatus = "idle",
        newerMessagesStatus = "idle",
        emptyTitle = ThreadEmptyConversationTitle,
        emptyDetail = ThreadEmptyConversationDetail
    )

private fun KuiklyThreadScreenPayload.asLoadedEmptyReplyThreadPayload(): KuiklyThreadScreenPayload =
    if (isLoadedEmptyReplyThread()) {
        copy(
            status = "empty",
            messages = emptyList(),
            canLoadMoreBefore = false,
            canLoadMoreAfter = false,
            olderMessagesStatus = "idle",
            newerMessagesStatus = "idle",
            errorCode = null,
            emptyTitle = ThreadReplyEmptyTitle,
            emptyDetail = ThreadReplyEmptyDetail
        )
    } else {
        this
    }

private fun KuiklyThreadScreenPayload.isLoadedEmptyReplyThread(): Boolean =
    surfaceKind == KuiklyThreadSurfaceKind.ReplyThread &&
        messages.isEmpty() &&
        originStatus != "none" &&
        originStatus != "loading"

private fun DataResult<*>.threadDiagnosticsResult(): String =
    when (this) {
        is DataResult.Content -> "content"
        is DataResult.Empty -> "empty:${reason.name}"
        is DataResult.Error -> "error:${error.code}"
        is DataResult.NoPermission -> "no_permission:${blocker.code}"
    }

private fun SharedThreadOriginResolution.focusedOriginDiagnosticsResult(): String =
    when (this) {
        is SharedThreadOriginResolution.Loaded -> "loaded"
        SharedThreadOriginResolution.Missing -> "missing"
        is SharedThreadOriginResolution.Error -> "error:$code"
    }

internal fun KuiklyThreadScreenPayload.acceptsFocusedOriginHydrationCompletion(
    parentChannelId: String,
    parentMessageId: String,
    originStatusAtStart: String,
    originMessageAtStart: KuiklyThreadMessageItem?
): Boolean =
    this.parentChannelId == parentChannelId &&
        (this.parentMessageId?.takeIf { it.isNotBlank() }
            ?: originParentMessageId.orEmpty()) == parentMessageId &&
        originStatus == originStatusAtStart &&
        originMessage == originMessageAtStart

private fun KuiklyThreadScreenPayload.originDiagnostics(): String =
    "surface=$surfaceKind parentChannelId=${parentChannelId.orEmpty()} " +
        "parentMessageId=${parentMessageId.orEmpty()} originParentMessageId=${originParentMessageId.orEmpty()} " +
        "originStatus=$originStatus hasOriginMessage=${originMessage != null} " +
        "hasOriginPreview=${!originPreview.isNullOrBlank()}"

// task #326: the launch placeholder payload seeds emptyTitle with this literal; a resolved
// (non-loading) merge must not carry it forward into an empty/content frame, or the empty
// state shows "Loading..." forever.
internal const val ThreadRouteLoadingPlaceholderTitle = "Loading..."
internal const val ThreadEmptyConversationTitle = "No messages yet"
internal const val ThreadEmptyConversationDetail = "This conversation has no messages yet."

internal fun KuiklyThreadScreenPayload.mergedEmptyTitleWith(base: KuiklyThreadScreenPayload): String =
    if (
        base.emptyTitle == ThreadRouteLoadingPlaceholderTitle &&
            status in setOf("empty", "content")
    ) {
        emptyTitle
    } else {
        base.emptyTitle
    }

internal fun KuiklyThreadScreenPayload.mergedEmptyDetailWith(base: KuiklyThreadScreenPayload): String =
    if (
        base.emptyTitle == ThreadRouteLoadingPlaceholderTitle &&
            status in setOf("empty", "content")
    ) {
        emptyDetail
    } else {
        base.emptyDetail
    }

internal fun KuiklyThreadScreenPayload.withRouteContext(base: KuiklyThreadScreenPayload): KuiklyThreadScreenPayload =
    (if (hasResolvedReplyThreadStreamFrom(base)) {
        copy(
            navigationLabel = base.navigationLabel,
            selectedTab = base.selectedTab,
            showModeTabs = base.showModeTabs,
            focusedMessageId = base.focusedMessageId,
            focusedMessageRequestNonce = base.focusedMessageRequestNonce,
            alignOwnMessagesRight = base.alignOwnMessagesRight,
            compactConsecutiveMessages = base.compactConsecutiveMessages,
            localOffsetMinutes = base.localOffsetMinutes,
            todayEpochDay = base.todayEpochDay ?: todayEpochDay,
            nowEpochMillis = base.nowEpochMillis ?: nowEpochMillis,
            timeFormat = base.timeFormat,
            knownMarkdownChannelNames =
                base.knownMarkdownChannelNames.ifEmpty { knownMarkdownChannelNames },
            knownMarkdownDirectMessagePeerNames =
                base.knownMarkdownDirectMessagePeerNames.ifEmpty { knownMarkdownDirectMessagePeerNames },
            emptyTitle = if (surfaceKind == KuiklyThreadSurfaceKind.ReplyThread) emptyTitle else mergedEmptyTitleWith(base),
            emptyDetail = if (surfaceKind == KuiklyThreadSurfaceKind.ReplyThread) emptyDetail else mergedEmptyDetailWith(base)
        )
    } else {
        copy(
            instanceId = base.instanceId.ifBlank { instanceId },
            sourceThreadId = base.sourceThreadId ?: sourceThreadId,
            navigationLabel = base.navigationLabel,
            selectedTab = base.selectedTab,
            showModeTabs = base.showModeTabs,
            focusedMessageId = base.focusedMessageId,
            focusedMessageRequestNonce = base.focusedMessageRequestNonce,
            alignOwnMessagesRight = base.alignOwnMessagesRight,
            compactConsecutiveMessages = base.compactConsecutiveMessages,
            localOffsetMinutes = base.localOffsetMinutes,
            todayEpochDay = base.todayEpochDay ?: todayEpochDay,
            nowEpochMillis = base.nowEpochMillis ?: nowEpochMillis,
            timeFormat = base.timeFormat,
            knownMarkdownChannelNames =
                base.knownMarkdownChannelNames.ifEmpty { knownMarkdownChannelNames },
            knownMarkdownDirectMessagePeerNames =
                base.knownMarkdownDirectMessagePeerNames.ifEmpty { knownMarkdownDirectMessagePeerNames },
            composer = base.composer.takeUnless { it.isDefaultThreadComposer() } ?: composer,
            emptyTitle = mergedEmptyTitleWith(base),
            emptyDetail = mergedEmptyDetailWith(base)
        )
    }).withReplyParentRouteIdentityFrom(base)

private fun KuiklyThreadScreenPayload.withReplyParentRouteIdentityFrom(
    routePayload: KuiklyThreadScreenPayload
): KuiklyThreadScreenPayload {
    if (
        surfaceKind != KuiklyThreadSurfaceKind.ReplyThread ||
        routePayload.surfaceKind != KuiklyThreadSurfaceKind.ReplyThread
    ) {
        return this
    }
    val routeParentChannelId = routePayload.parentChannelId?.takeIf { it.isNotBlank() } ?: return this
    val routeParentMessageId =
        routePayload.parentMessageId?.takeIf { it.isNotBlank() }
            ?: routePayload.originParentMessageId?.takeIf { it.isNotBlank() }
            ?: return this
    val currentParentChannelId = parentChannelId?.takeIf { it.isNotBlank() }
    val currentParentMessageId =
        parentMessageId?.takeIf { it.isNotBlank() }
            ?: originParentMessageId?.takeIf { it.isNotBlank() }
    if (
        (currentParentChannelId != null && currentParentChannelId != routeParentChannelId) ||
        (currentParentMessageId != null && currentParentMessageId != routeParentMessageId)
    ) {
        return this
    }
    return copy(
        parentChannelId = routeParentChannelId,
        parentMessageId = routeParentMessageId,
        originParentMessageId = originParentMessageId?.takeIf { it.isNotBlank() } ?: routeParentMessageId,
        parentSurfaceKind =
            routePayload.parentSurfaceKind?.takeIf { it.isNotBlank() }
                ?: parentSurfaceKind,
        parentChannelKind =
            routePayload.parentChannelKind?.takeIf { it.isNotBlank() }
                ?: parentChannelKind
    )
}

private fun KuiklyThreadScreenPayload.withStoreResolvedReplyOriginFrom(
    storePayload: KuiklyThreadScreenPayload
): KuiklyThreadScreenPayload {
    if (
        surfaceKind != KuiklyThreadSurfaceKind.ReplyThread ||
        storePayload.surfaceKind != KuiklyThreadSurfaceKind.ReplyThread ||
        originMessage != null ||
        storePayload.originStatus != "loaded"
    ) {
        return this
    }
    val storeOrigin = storePayload.originMessage ?: return this
    val currentParentChannelId = parentChannelId?.takeIf { it.isNotBlank() } ?: return this
    val storeParentChannelId = storePayload.parentChannelId?.takeIf { it.isNotBlank() } ?: return this
    val currentParentMessageId =
        parentMessageId?.takeIf { it.isNotBlank() }
            ?: originParentMessageId?.takeIf { it.isNotBlank() }
            ?: return this
    val storeParentMessageId =
        storePayload.parentMessageId?.takeIf { it.isNotBlank() }
            ?: storePayload.originParentMessageId?.takeIf { it.isNotBlank() }
            ?: return this
    if (
        currentParentChannelId != storeParentChannelId ||
        currentParentMessageId != storeParentMessageId ||
        storeOrigin.id != storeParentMessageId
    ) {
        return this
    }
    return copy(
        originMessage = storeOrigin,
        originPreview = storePayload.originPreview,
        originParentMessageId = storeParentMessageId,
        originStatus = "loaded",
        originErrorCode = null
    )
}

private fun KuiklyThreadScreenPayload.withRouteIntentFrom(routePayload: KuiklyThreadScreenPayload): KuiklyThreadScreenPayload {
    val resolvedIdentityPayload =
        if (routePayload.resolveThreadRouteIdentity().canRenderTimelineCandidates) {
            withResolvedReplyThreadRouteIntentFrom(routePayload)
        } else {
            this
        }
    return resolvedIdentityPayload.copy(
        navigationLabel = routePayload.navigationLabel,
        selectedTab = routePayload.selectedTab,
        showModeTabs = routePayload.showModeTabs,
        focusedMessageId = routePayload.focusedMessageId,
        focusedMessageRequestNonce = routePayload.focusedMessageRequestNonce,
        alignOwnMessagesRight = routePayload.alignOwnMessagesRight,
        compactConsecutiveMessages = routePayload.compactConsecutiveMessages,
        localOffsetMinutes = routePayload.localOffsetMinutes,
        todayEpochDay = routePayload.todayEpochDay ?: todayEpochDay,
        nowEpochMillis = routePayload.nowEpochMillis ?: nowEpochMillis,
        timeFormat = routePayload.timeFormat,
        knownMarkdownChannelNames =
            routePayload.knownMarkdownChannelNames.ifEmpty { knownMarkdownChannelNames },
        knownMarkdownDirectMessagePeerNames =
            routePayload.knownMarkdownDirectMessagePeerNames.ifEmpty { knownMarkdownDirectMessagePeerNames },
        composer =
            composer.copy(
                syncNonce = maxOf(composer.syncNonce, routePayload.composer.syncNonce),
                focusRequestNonce = maxOf(composer.focusRequestNonce, routePayload.composer.focusRequestNonce)
            )
    )
}

internal fun KuiklyThreadScreenPayload.withHomeContextProjectionFrom(
    patch: KuiklyThreadScreenPayload
): KuiklyThreadScreenPayload {
    val knownNames =
        patch.knownMarkdownChannelNames.takeIf { it.isNotEmpty() && it != knownMarkdownChannelNames }
            ?: knownMarkdownChannelNames
    // Access is resolved exactly once by launchInitialAccessResolution and is
    // emitted atomically with ThreadAccessResolution.Resolved. Background Home
    // enrichment must never flip an already-mounted Composer into Join later.
    return copy(
        knownMarkdownChannelNames = knownNames,
        knownMarkdownDirectMessagePeerNames =
            patch.knownMarkdownDirectMessagePeerNames.ifEmpty { knownMarkdownDirectMessagePeerNames }
    )
}

private fun KuiklyThreadScreenPayload.withResolvedReplyThreadRouteIntentFrom(
    routePayload: KuiklyThreadScreenPayload
): KuiklyThreadScreenPayload =
    if (routePayload.hasResolvedReplyThreadStreamFrom(this)) {
        copy(
            sourceThreadId = routePayload.sourceThreadId,
            streamChannelId = routePayload.streamChannelId,
            parentChannelId = routePayload.parentChannelId,
            parentMessageId = routePayload.parentMessageId,
            composer =
                composer.copy(
                    streamChannelId =
                        routePayload.composer.streamChannelId?.takeIf { it.isNotBlank() }
                            ?: routePayload.streamChannelId?.takeIf { it.isNotBlank() }
                            ?: routePayload.sourceThreadId?.takeIf { it.isNotBlank() }
                )
        )
    } else {
        this
    }

internal fun buildMarkdownChannelLaunchPayload(
    currentPayload: KuiklyThreadScreenPayload,
    home: HomeSnapshot,
    channelLabel: String,
    requiredKind: ConversationKind? = null,
    knownMemberProfilesById: Map<String, MemberProfile> = emptyMap()
): KuiklyThreadScreenPayload? {
    val channel =
        resolveMarkdownChannelConversation(
            home = home,
            channelLabel = channelLabel,
            requiredKind = requiredKind,
            knownMemberProfilesById = knownMemberProfilesById,
            currentPayload = currentPayload
        ) ?: return null
    val conversation = channel.toThreadLaunchConversationIdentity()
    val streamChannelId = conversation.threadStreamChannelId() ?: return null
    return buildStandaloneConversationThreadLaunchPayload(
        conversation = conversation,
        timeContext = currentPayload.toSlockTimeContext(),
        alignOwnMessagesRight = currentPayload.alignOwnMessagesRight,
        compactConsecutiveMessages = currentPayload.compactConsecutiveMessages,
        instanceIdOverride = "shared-shell-thread-$streamChannelId"
    )?.toInitialPayloadOrNull("shared-shell-thread-$streamChannelId")
}

internal fun buildMarkdownThreadRefLaunchPayload(
    currentPayload: KuiklyThreadScreenPayload,
    home: HomeSnapshot,
    channelLabel: String,
    threadShortId: String,
    parentMessageId: String,
    focusedMessageId: String? = null,
    threadChannelId: String? = null,
    requiredParentKind: ConversationKind? = null,
    knownMemberProfilesById: Map<String, MemberProfile> = emptyMap()
): KuiklyThreadScreenPayload? {
    val channel =
        resolveMarkdownChannelConversation(
            home = home,
            channelLabel = channelLabel,
            requiredKind = requiredParentKind,
            knownMemberProfilesById = knownMemberProfilesById,
            currentPayload = currentPayload,
            threadShortId = threadShortId
        ) ?: return null
    val routeIdentity = channel.resolveThreadRouteIdentity()
    val parentChannelId =
        routeIdentity.timelineChannelId
            ?: channel.messageStreamChannelId().value.takeIf { it.isNotBlank() }
            ?: return null
    val cleanParentMessageId = parentMessageId.takeIf { it.isNotBlank() } ?: return null
    val cleanThreadChannelId = threadChannelId?.takeIf { it.isNotBlank() }
    val instanceId =
        cleanThreadChannelId?.let { "shared-shell-thread-$it" }
            ?: "shared-shell-thread-$parentChannelId-$threadShortId"
    val conversation =
        buildThreadLaunchConversationIdentity(
            kind = ConversationKind.Thread,
            channelId = cleanThreadChannelId?.let(::ChannelId) ?: ChannelId(parentChannelId),
            threadChannelId = cleanThreadChannelId?.let(::ChannelId),
            parentChannelId = ChannelId(parentChannelId),
            parentMessageId = MessageId(cleanParentMessageId),
            parentSurfaceKind = channel.kind.threadParentSurfaceKindOrNull(),
            parentChannelKind = channel.kind.threadChannelKindWireValue()
        )
    return buildStandaloneConversationThreadLaunchPayload(
        conversation = conversation,
        timeContext = currentPayload.toSlockTimeContext(),
        anchorMessageId = focusedMessageId?.takeIf { it.isNotBlank() },
        alignOwnMessagesRight = currentPayload.alignOwnMessagesRight,
        compactConsecutiveMessages = currentPayload.compactConsecutiveMessages,
        instanceIdOverride = instanceId
    )?.toInitialPayloadOrNull(instanceId)
}

internal fun resolveMarkdownChannelConversation(
    home: HomeSnapshot,
    channelLabel: String,
    requiredKind: ConversationKind? = null,
    knownMemberProfilesById: Map<String, MemberProfile> = emptyMap(),
    currentPayload: KuiklyThreadScreenPayload? = null,
    threadShortId: String? = null
): ConversationSummary? {
    val rawKey = channelLabel.markdownChannelLookupKey()
    if (rawKey.isBlank()) return null
    // DM refs may pin the peer kind (`dm:@skyzh~agent`). A bare name that a
    // human and an agent share opens the first matching DM, like Web
    // `findDmByPeer` (see the end of this function).
    val directMessagePeerRef =
        if (requiredKind == ConversationKind.DirectMessage) {
            parseRaftDirectMessagePeerRef(rawKey) ?: return null
        } else {
            null
        }
    val key = directMessagePeerRef?.peerName ?: rawKey
    val requiredPeerKind = directMessagePeerRef?.peerKind
    fun ConversationSummary.matchesRequiredPeerKind(): Boolean =
        requiredPeerKind == null || directMessagePeerType.parsedMemberKindOrNull() == requiredPeerKind

    if (requiredKind == ConversationKind.DirectMessage &&
        home.currentMember.matchesMarkdownChannelLabel(key) &&
        (requiredPeerKind == null || home.currentMember.kind == requiredPeerKind)
    ) {
        val currentDmChannelId = currentPayload?.let { payload ->
            when {
                payload.parentSurfaceKind == KuiklyThreadSurfaceKind.DirectMessage ||
                    payload.parentChannelKind == "dm" ||
                    payload.resolveThreadRouteIdentity().parentConversationKind == ConversationKind.DirectMessage -> {
                    payload.parentChannelId?.takeIf { it.isNotBlank() }
                }
                payload.surfaceKind == KuiklyThreadSurfaceKind.DirectMessage ||
                    payload.channelKind == "dm" ||
                    payload.dmPeerIdentity != null -> {
                    payload.resolveThreadRouteIdentity().timelineChannelId
                        ?: payload.streamChannelId?.takeIf { it.isNotBlank() }
                        ?: payload.sourceThreadId?.takeIf { it.isNotBlank() }
                }
                else -> null
            }
        }

        if (!currentDmChannelId.isNullOrBlank()) {
            val existing =
                home.directMessages
                    .map { it.toConversationSummary() }
                    .firstOrNull {
                        it.messageStreamChannelId().value == currentDmChannelId ||
                            it.channelId?.value == currentDmChannelId ||
                            it.threadId.value == currentDmChannelId
                    }
            if (existing != null) {
                return existing
            }
            return ConversationSummary(
                threadId = ThreadId(currentDmChannelId),
                channelId = ChannelId(currentDmChannelId),
                kind = ConversationKind.DirectMessage,
                name = currentPayload?.dmPeerName ?: currentPayload?.title.orEmpty(),
                presenceMemberId = currentPayload?.dmPeerIdentity?.memberId
            )
        }

        if (currentPayload != null && !threadShortId.isNullOrBlank()) {
            val referencingMessage =
                currentPayload.messages.firstOrNull { it.body.contains(threadShortId) }
                    ?: currentPayload.originMessage?.takeIf { it.body.contains(threadShortId) }
            if (referencingMessage != null) {
                val authorId = referencingMessage.authorId.trim()
                val authorHandle = referencingMessage.authorHandle.trim()
                val authorName = referencingMessage.authorName.trim()
                val matchByAuthor =
                    home.directMessages
                        .map { it.toConversationSummary() }
                        .filter { dm -> dm.matchesRequiredPeerKind() }
                        .firstOrNull { dm ->
                            (authorId.isNotBlank() && dm.presenceMemberId?.value == authorId) ||
                                dm.markdownDirectMessagePeerAliases(knownMemberProfilesById).any { alias ->
                                    val aliasKey = alias.markdownChannelLookupKey().lowercase()
                                    (authorHandle.isNotBlank() && aliasKey == authorHandle.markdownChannelLookupKey().lowercase()) ||
                                        (authorName.isNotBlank() && aliasKey == authorName.markdownChannelLookupKey().lowercase())
                                }
                        }
                if (matchByAuthor != null) {
                    return matchByAuthor
                }
            }
        }
    }

    val matches =
        (if (requiredKind == ConversationKind.DirectMessage) home.directMessages else home.channels)
            .map { it.toConversationSummary() }
            .filter { conversation ->
                (requiredKind == null || conversation.kind == requiredKind) &&
                    if (requiredKind == ConversationKind.DirectMessage) {
                        conversation.matchesRequiredPeerKind() &&
                            conversation.markdownDirectMessagePeerAliases(knownMemberProfilesById)
                                .any { alias -> alias.markdownChannelLookupKey() == key }
                    } else {
                        conversation.name.markdownChannelLookupKey() == key
                    }
            }
            .distinctBy { it.messageStreamChannelId().value }
    matches.singleOrNull()?.let { return it }
    // Web `findDmByPeer` resolves a bare human/agent twin name to the first
    // matching DM in list order. Other alias collisions still fail closed.
    return if (directMessagePeerRef != null &&
        requiredPeerKind == null &&
        key in home.markdownDirectMessageTwinPeerKeys(knownMemberProfilesById)
    ) {
        matches.firstOrNull()
    } else {
        null
    }
}

internal fun MemberProfile.matchesMarkdownChannelLabel(label: String): Boolean {
    val key = label.markdownChannelLookupKey().lowercase()
    if (key.isBlank()) return false
    return listOfNotNull(handle, displayName, id.value).any { candidate ->
        candidate.markdownChannelLookupKey().lowercase() == key
    }
}

private fun ConversationSummary.markdownDirectMessagePeerAliases(
    knownMemberProfilesById: Map<String, MemberProfile>
): List<String> {
    val profile =
        presenceMemberId
            ?.value
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(knownMemberProfilesById::get)
    return listOfNotNull(name, profile?.handle, profile?.displayName)
        .mapNotNull { alias -> alias.trim().removePrefix("@").takeIf { it.isNotBlank() } }
        .distinctBy { it.lowercase() }
}

/**
 * DM peer names a markdown `dm:@peer[:short]` ref may resolve to. Each DM
 * contributes its bare aliases plus kind-pinned `alias~agent` / `alias~human`
 * forms. A bare name that a human and an agent share stays linkable and opens
 * the first matching DM (Web `findDmByPeer`); any other bare alias that maps
 * to more than one DM is left out so the ref stays plain text.
 */
internal fun HomeSnapshot.knownMarkdownDirectMessagePeerNames(
    knownMemberProfilesById: Map<String, MemberProfile>
): List<String> {
    val twinKeys = markdownDirectMessageTwinPeerKeys(knownMemberProfilesById)
    val aliasesByLookupKey = linkedMapOf<String, MutableList<Pair<String, String>>>()
    directMessages.forEach { row ->
        val conversation = row.toConversationSummary()
        val conversationId = conversation.messageStreamChannelId().value.trim()
        if (conversationId.isBlank()) return@forEach
        val peerKind = conversation.directMessagePeerType.parsedMemberKindOrNull()
        conversation.markdownDirectMessagePeerAliases(knownMemberProfilesById).forEach { alias ->
            val key = alias.markdownChannelLookupKey()
            if (key.isBlank()) return@forEach
            aliasesByLookupKey.getOrPut(key) { mutableListOf() } += conversationId to alias
            if (peerKind == MemberKind.Agent || peerKind == MemberKind.Human) {
                val kindedAlias = formatRaftDirectMessagePeerRef(alias, peerKind)
                aliasesByLookupKey.getOrPut(kindedAlias.lowercase()) { mutableListOf() } += conversationId to kindedAlias
            }
        }
    }
    return aliasesByLookupKey.mapNotNull { (key, matches) ->
        matches.firstOrNull()?.second?.takeIf {
            key in twinKeys || matches.map { match -> match.first }.distinct().size == 1
        }
    }
}

/**
 * Lower-cased names held by both a human and an agent in this server. A bare
 * `dm:@name` ref to one of them opens the first matching DM (Web parity);
 * `~agent` / `~human` pins the side.
 */
internal fun HomeSnapshot.markdownDirectMessageTwinPeerKeys(
    knownMemberProfilesById: Map<String, MemberProfile>
): Set<String> {
    val kindsByKey = mutableMapOf<String, MutableSet<MemberKind>>()
    fun record(name: String?, kind: MemberKind?) {
        if (kind != MemberKind.Human && kind != MemberKind.Agent) return
        val key = name?.markdownChannelLookupKey().orEmpty()
        if (key.isNotBlank()) kindsByKey.getOrPut(key) { mutableSetOf() } += kind
    }
    knownMemberProfilesById.values.forEach { profile -> record(profile.handle, profile.kind) }
    record(currentMember.handle, currentMember.kind)
    directMessages.forEach { row ->
        val conversation = row.toConversationSummary()
        val peerKind = conversation.directMessagePeerType.parsedMemberKindOrNull()
        val profile = conversation.presenceMemberId?.value?.trim()?.let(knownMemberProfilesById::get)
        record(profile?.handle, peerKind)
        record(conversation.name, peerKind)
    }
    return kindsByKey.filterValues { it.size > 1 }.keys
}

private fun String.markdownChannelLookupKey(): String =
    trim()
        .let { value ->
            when {
                value.startsWith("dm:@", ignoreCase = true) -> value.drop(4)
                value.startsWith("#") -> value.drop(1)
                else -> value
            }
        }
        .substringBefore(":")
        .trim()
        .lowercase()

private fun KuiklyThreadScreenPayload.markdownThreadRefTarget(
    parentChannelId: String,
    threadShortId: String
): MarkdownThreadRefTarget? {
    val shortId = threadShortId.trim().lowercase()
    if (parentChannelId.isBlank() || shortId.isBlank()) return null
    val routeIsParentChannel =
        surfaceKind != KuiklyThreadSurfaceKind.ReplyThread &&
            (sourceThreadId == parentChannelId || streamChannelId == parentChannelId)
    val originIsParentMessage =
        this.parentChannelId == parentChannelId &&
            originMessage != null
    val candidateMessages =
        (if (routeIsParentChannel) messages else emptyList()) +
            if (originIsParentMessage) listOfNotNull(originMessage) else emptyList()
    val candidates =
        candidateMessages
            .filter { message ->
                message.id.lowercase().startsWith(shortId)
            }
            .map { message ->
                MarkdownThreadRefTarget(
                    parentMessageId = message.id,
                    threadChannelId = message.threadChannelId
                )
            }
            .distinctBy { it.parentMessageId }
    return candidates.singleOrNull()
}

private fun ThreadSnapshot.markdownThreadRefTarget(
    parentChannelId: String,
    threadShortId: String
): MarkdownThreadRefTarget? {
    val expectedShortId = threadShortId.trim().lowercase()
    val routeThread = conversation.thread
    if (
        conversation.kind == ConversationKind.Thread &&
        routeThread != null &&
        routeThread.parentChannelId.value == parentChannelId &&
        routeThread.parentMessageId.value.isNotBlank()
    ) {
        return MarkdownThreadRefTarget(
            parentMessageId = routeThread.parentMessageId.value,
            focusedMessageId =
                highlightedMessageId
                    ?.value
                    ?.takeIf { it.isNotBlank() && it != routeThread.parentMessageId.value },
            threadChannelId = routeThread.streamChannelId?.value
        )
    }
    val targetMessageId =
        highlightedMessageId
            ?.value
            ?.takeIf { it.isNotBlank() }
            ?: parentMessage
                ?.id
                ?.value
                ?.takeIf { it.isNotBlank() }
            ?: messages
                .firstOrNull { message ->
                    message.channelId?.value == parentChannelId &&
                        message.id.value.lowercase().startsWith(expectedShortId)
                }
                ?.id
                ?.value
    return targetMessageId?.let { MarkdownThreadRefTarget(parentMessageId = it) }
}

private fun KuiklyThreadScreenPayload.withoutRouteCandidateContent(): KuiklyThreadScreenPayload =
    if (!resolveThreadRouteIdentity().canRenderTimelineCandidates) {
        val originCandidate = validatedUnresolvedReplyOriginCandidate()
        copy(
            status = if (hasRenderableThreadContent() && status != "error") "loading" else status,
            backgroundRefreshing = false,
            messages = emptyList(),
            originMessage = originCandidate,
            originPreview = if (originCandidate != null) originPreview else null,
            originStatus = if (originCandidate != null) originStatus else "none",
            originErrorCode = null,
            tasks = emptyList(),
            olderMessagesStatus = "idle",
            newerMessagesStatus = "idle",
            members = emptyList(),
            memberCandidates = emptyList(),
            settingsActions = emptyList()
        )
    } else {
        copy(
            status = "loading",
            backgroundRefreshing = false,
            messages = emptyList(),
            originMessage = null,
            originPreview = null,
            originStatus = "none",
            originErrorCode = null,
            tasks = emptyList(),
            olderMessagesStatus = "idle",
            newerMessagesStatus = "idle",
            errorCode = null,
            showComposer = true,
            joined = true,
            canSend = true,
            membersCount = 0,
            members = emptyList(),
            memberCandidates = emptyList(),
            settingsActions = emptyList()
        ).withLaunchHeaderContext()
    }

private fun KuiklyThreadScreenPayload.validatedUnresolvedReplyOriginCandidate(): KuiklyThreadMessageItem? {
    val expectedParentMessageId =
        parentMessageId?.takeIf { it.isNotBlank() }
            ?: originParentMessageId?.takeIf { it.isNotBlank() }
            ?: return null
    if (surfaceKind != KuiklyThreadSurfaceKind.ReplyThread) return null
    if (sourceThreadId?.isNotBlank() == true || streamChannelId?.isNotBlank() == true) return null
    return originMessage?.takeIf { message -> message.id == expectedParentMessageId }
}

private fun KuiklyThreadScreenPayload.needsReplyThreadStreamResolution(): Boolean {
    val parentChannel = parentChannelId?.takeIf { it.isNotBlank() } ?: return false
    val parentMessage = parentMessageId?.takeIf { it.isNotBlank() }
        ?: originParentMessageId?.takeIf { it.isNotBlank() }
        ?: return false
    val streamCandidate =
        streamChannelId?.takeIf { it.isNotBlank() }
            ?: sourceThreadId?.takeIf { it.isNotBlank() }
    return surfaceKind == KuiklyThreadSurfaceKind.ReplyThread &&
        parentMessage.isNotBlank() &&
        (streamCandidate == null || streamCandidate == parentChannel)
}

private fun KuiklyThreadScreenPayload.shouldFetchReplyThreadByParentPair(): Boolean =
    surfaceKind == KuiklyThreadSurfaceKind.ReplyThread &&
        parentChannelId?.isNotBlank() == true &&
        (parentMessageId?.isNotBlank() == true || originParentMessageId?.isNotBlank() == true)

private fun KuiklyThreadScreenPayload.shouldResolveReplyThreadByParentPair(): Boolean =
    shouldFetchReplyThreadByParentPair() &&
        needsReplyThreadStreamResolution()

private fun KuiklyThreadScreenPayload.hasUntrustedReplyThreadStreamHint(): Boolean {
    val parentChannel = parentChannelId?.takeIf { it.isNotBlank() } ?: return false
    parentMessageId?.takeIf { it.isNotBlank() }
        ?: originParentMessageId?.takeIf { it.isNotBlank() }
        ?: return false
    val streamCandidate =
        streamChannelId?.takeIf { it.isNotBlank() }
            ?: sourceThreadId?.takeIf { it.isNotBlank() }
            ?: return false
    return surfaceKind == KuiklyThreadSurfaceKind.ReplyThread &&
        streamCandidate != parentChannel
}

private fun ThreadSnapshot.parentPairMatchesRoute(base: KuiklyThreadScreenPayload): Boolean {
    val expectedParentChannelId = base.parentChannelId?.takeIf { it.isNotBlank() } ?: return true
    val expectedParentMessageId =
        base.parentMessageId?.takeIf { it.isNotBlank() }
            ?: base.originParentMessageId?.takeIf { it.isNotBlank() }
            ?: return true
    val thread = conversation.thread
    if (thread != null) {
        return thread.parentChannelId.value == expectedParentChannelId &&
            thread.parentMessageId.value == expectedParentMessageId
    }
    val parent = parentMessage ?: return true
    return parent.channelId?.value == expectedParentChannelId &&
        parent.id.value == expectedParentMessageId
}

private fun KuiklyThreadScreenPayload.hasResolvedReplyThreadStreamFrom(base: KuiklyThreadScreenPayload): Boolean {
    val source = sourceThreadId?.takeIf { it.isNotBlank() } ?: return false
    val baseSource =
        base.streamChannelId?.takeIf { it.isNotBlank() }
            ?: base.sourceThreadId?.takeIf { it.isNotBlank() }
            ?: base.parentChannelId?.takeIf { it.isNotBlank() }
            ?: return false
    return surfaceKind == KuiklyThreadSurfaceKind.ReplyThread &&
        base.surfaceKind == KuiklyThreadSurfaceKind.ReplyThread &&
        source != baseSource
}

private fun KuiklyThreadScreenPayload.withResolvedReplyThreadAction(
    action: ThreadActionResult
): KuiklyThreadScreenPayload {
    val threadChannelId = action.threadChannelId.value.takeIf { it.isNotBlank() } ?: return this
    val resolvedParentMessageId =
        action.parentMessageId?.value?.takeIf { it.isNotBlank() }
            ?: parentMessageId
            ?: originParentMessageId
    return copy(
        instanceId = "shared-shell-thread-$threadChannelId",
        sourceThreadId = threadChannelId,
        streamChannelId = threadChannelId,
        parentMessageId = resolvedParentMessageId,
        originParentMessageId = resolvedParentMessageId ?: originParentMessageId,
        originMessage =
            originMessage?.copy(
                threadChannelId = threadChannelId,
                replyCount = maxOf(originMessage.replyCount, action.replyCount)
            ),
        composer =
            composer.copy(
                parentMessageId = resolvedParentMessageId ?: composer.parentMessageId,
                streamChannelId = threadChannelId
            )
    )
}

internal fun KuiklyThreadScreenPayload.preserveStableTimelineFrom(
    previous: KuiklyThreadScreenPayload,
    preserveSelectedReactions: Boolean = true,
    preserveAuxiliaryProjectionFromPrevious: Boolean = false,
    refreshObservedMessageIds: Set<String>? = null,
    sendEchoMatchPolicy: ThreadSendEchoMatchPolicy = ThreadSendEchoMatchPolicy.LegacyCompatible
): KuiklyThreadScreenPayload {
    val visibleWindowMessages =
        if (
            (canLoadMoreBefore || previous.canLoadMoreBefore) &&
            messages.shouldRetainExpandedTimelineWindowFrom(previous.messages)
        ) {
            messages.mergeIntoExpandedTimelineWindow(previous.messages)
        } else {
            messages
        }
    val visibleAfterPreviousCanonicalEchoes =
        visibleWindowMessages.absorbingCanonicalSendEchoes(
            canonicalMessages = previous.messages,
            matchPolicy = sendEchoMatchPolicy
        )
    val previousAfterCanonicalEchoes =
        previous.messages.absorbingCanonicalSendEchoes(
            canonicalMessages = visibleAfterPreviousCanonicalEchoes,
            matchPolicy = sendEchoMatchPolicy
        )
    val timelineMessages =
        visibleAfterPreviousCanonicalEchoes
            .withRetainedTransientLocalSendingMessagesFrom(previousAfterCanonicalEchoes)
            .withRetainedCanonicalOwnSendsAfterRefreshFrom(
                previousMessages = previousAfterCanonicalEchoes,
                refreshObservedMessageIds = refreshObservedMessageIds
            )
    val stableMessages =
        timelineMessages
            .withRetainedLocalFailedMessagesFrom(previous.messages)
            .withSelectedReactionsFrom(previous.messages, preserveSelectedReactions)
            .let { messages ->
                if (preserveAuxiliaryProjectionFromPrevious) {
                    messages.withAcceptedAuxiliaryProjectionFrom(previous.messages)
                } else {
                    messages
                }
            }
            .preserveStableItemsFrom(previous.messages) { item -> item.id }
    val stableStatus =
        if (stableMessages.any { it.isTransientLocalSendingMessage() } && status == "empty") {
            "content"
        } else {
            status
        }
    val preservePreviousOrigin = shouldPreserveLoadedOriginFrom(previous)
    val stableOrigin =
        when {
            preservePreviousOrigin -> previous.originMessage
            originMessage != null && originMessage == previous.originMessage -> previous.originMessage
            else -> originMessage
        }
    val reactionStableOrigin =
        stableOrigin
            ?.withSelectedReactionsFrom(previous.originMessage, preserveSelectedReactions)
            ?.let { origin ->
                val acceptedOrigin = previous.originMessage
                if (preserveAuxiliaryProjectionFromPrevious && acceptedOrigin?.id == origin.id) {
                    origin.withAcceptedAuxiliaryProjectionFrom(acceptedOrigin)
                } else {
                    origin
                }
            }
    val stableTasks =
        tasks
            .preserveStableItemsFrom(previous.tasks) { item -> item.id }
            .withReferencedThreadTasksFrom(
                previous = previous.tasks,
                messages = stableMessages,
                originMessage = reactionStableOrigin
            )
    val stableOriginParentMessageId =
        if (preservePreviousOrigin) {
            previous.originParentMessageId ?: previous.originMessage?.id ?: originParentMessageId
        } else {
            originParentMessageId
        }
    val stableOriginPreview =
        if (preservePreviousOrigin) previous.originPreview else originPreview
    val stableOriginStatus =
        if (preservePreviousOrigin) previous.originStatus.takeIf { it == "loaded" } ?: "loaded" else originStatus
    val stableOriginErrorCode =
        if (preservePreviousOrigin) previous.originErrorCode else originErrorCode
    if (
        stableMessages === messages &&
        stableTasks === tasks &&
        reactionStableOrigin === originMessage &&
        stableOriginParentMessageId == originParentMessageId &&
        stableOriginPreview == originPreview &&
        stableOriginStatus == originStatus &&
        stableOriginErrorCode == originErrorCode &&
        stableStatus == status
    ) {
        return this
    }
    return copy(
        status = stableStatus,
        messages = stableMessages,
        originMessage = reactionStableOrigin,
        originPreview = stableOriginPreview,
        originParentMessageId = stableOriginParentMessageId,
        originStatus = stableOriginStatus,
        originErrorCode = stableOriginErrorCode,
        tasks = stableTasks
    )
}

/**
 * Saved membership and linked-task facts are auxiliary Store projections, not canonical facts from
 * the messages response. A primary timeline refresh therefore cannot replace an already accepted
 * projection with the response defaults while the Saved/Tasks authorities are still refreshing.
 *
 * This bridge is deliberately used only by the primary refresh commit. The later Saved lookup and
 * SharedTasksDataStore projection remain authoritative and may replace or remove these retained
 * facts. Identity is exact message id; unmatched rows never inherit another row's projection.
 */
private fun List<KuiklyThreadMessageItem>.withAcceptedAuxiliaryProjectionFrom(
    previous: List<KuiklyThreadMessageItem>
): List<KuiklyThreadMessageItem> {
    if (isEmpty() || previous.isEmpty()) return this
    val previousById = previous.associateBy { message -> message.id }
    return map { message ->
        previousById[message.id]
            ?.takeIf { accepted -> accepted.id.isNotBlank() }
            ?.let(message::withAcceptedAuxiliaryProjectionFrom)
            ?: message
    }
}

private fun KuiklyThreadMessageItem.withAcceptedAuxiliaryProjectionFrom(
    previous: KuiklyThreadMessageItem
): KuiklyThreadMessageItem {
    if (id.isBlank() || id != previous.id) return this
    val previousHasTaskProjection =
        previous.taskId != null ||
            previous.taskNumber != null ||
            previous.taskStatus != null ||
            previous.taskAssigneeKind != null ||
            previous.taskAssigneeId != null ||
            previous.taskAssigneeName != null
    return copy(
        isSaved = previous.isSaved,
        taskId = if (previousHasTaskProjection) previous.taskId else taskId,
        taskNumber = if (previousHasTaskProjection) previous.taskNumber else taskNumber,
        taskStatus = if (previousHasTaskProjection) previous.taskStatus else taskStatus,
        taskAssigneeKind = if (previousHasTaskProjection) previous.taskAssigneeKind else taskAssigneeKind,
        taskAssigneeId = if (previousHasTaskProjection) previous.taskAssigneeId else taskAssigneeId,
        taskAssigneeName = if (previousHasTaskProjection) previous.taskAssigneeName else taskAssigneeName
    )
}

private fun String?.toThreadRepliesParentScopeKind(): String =
    when (this?.trim()?.lowercase()) {
        "private" -> "private"
        "joint" -> "joint"
        "dm", "direct", "direct_message" -> "dm"
        else -> "channel"
    }

private fun KuiklyThreadScreenPayload.threadRepliesScopeIdentities(
    page: ThreadRepliesPageIdentity,
    previous: KuiklyThreadScreenPayload,
): List<ThreadRepliesScopeIdentity> {
    val previousById =
        (previous.messages + listOfNotNull(previous.originMessage)).associateBy { it.id }
    return (messages + listOfNotNull(originMessage)).mapNotNull { message ->
        val threadChannelId =
            message.threadChannelId?.trim()?.takeIf { it.isNotBlank() }
                ?: previousById[message.id]?.threadChannelId?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
        ThreadRepliesScopeIdentity(
            page = page,
            parentMessageId = message.id,
            threadChannelId = threadChannelId,
        )
    }
}

private fun KuiklyThreadScreenPayload.withAcceptedThreadRepliesProjection(
    accepted: Map<String, ThreadRepliesAcceptedProjection>,
    previous: KuiklyThreadScreenPayload,
): KuiklyThreadScreenPayload {
    val previousById =
        (previous.messages + listOfNotNull(previous.originMessage)).associateBy { it.id }

    fun KuiklyThreadMessageItem.project(): KuiklyThreadMessageItem {
        val projection = accepted[id]
        if (projection != null) {
            return copy(
                threadChannelId = projection.identity.threadChannelId,
                replyCount = projection.model.replyCount,
                inlineReplies = projection.model,
            )
        }
        val prior = previousById[id]
        val priorThreadChannelId = prior?.threadChannelId?.trim()?.takeIf { it.isNotBlank() }
        val incomingThreadChannelId = threadChannelId?.trim()?.takeIf { it.isNotBlank() }
        val sameScope =
            prior != null &&
                (priorThreadChannelId == null ||
                    incomingThreadChannelId == null ||
                    priorThreadChannelId == incomingThreadChannelId)
        if (!sameScope) return if (inlineReplies == null) this else copy(inlineReplies = null)
        val priorAccepted = prior?.inlineReplies ?: return if (inlineReplies == null) this else copy(inlineReplies = null)
        return copy(
            threadChannelId = incomingThreadChannelId ?: priorThreadChannelId,
            replyCount = priorAccepted.replyCount,
            inlineReplies = priorAccepted,
        )
    }

    val projectedMessages = messages.map { it.project() }
    val projectedOrigin = originMessage?.project()
    return if (projectedMessages == messages && projectedOrigin == originMessage) {
        this
    } else {
        copy(messages = projectedMessages, originMessage = projectedOrigin)
    }
}

/**
 * Applies the Saved/reply-summary result produced by [refreshSecondaryTimelineData] without
 * replacing canonical timeline state that committed while the auxiliary projection was in
 * flight. [previous] is the live payload observed immediately before the projection callback was
 * requested, so each auxiliary field can use a three-way merge: apply [patch] only while the live
 * field still equals that baseline; otherwise retain the newer live value.
 */
private fun KuiklyThreadScreenPayload.withSecondaryTimelineProjectionFrom(
    patch: KuiklyThreadScreenPayload,
    previous: KuiklyThreadScreenPayload,
): KuiklyThreadScreenPayload {
    val patchById =
        (patch.messages + listOfNotNull(patch.originMessage))
            .filter { message -> message.id.isNotBlank() }
            .associateBy { message -> message.id }
    val previousById =
        (previous.messages + listOfNotNull(previous.originMessage))
            .filter { message -> message.id.isNotBlank() }
            .associateBy { message -> message.id }
    fun <T> projectField(live: T, baseline: T, candidate: T): T =
        if (live == baseline) candidate else live

    fun KuiklyThreadMessageItem.project(): KuiklyThreadMessageItem {
        val candidate = patchById[id] ?: return this
        val baseline = previousById[id] ?: return this
        return copy(
            threadChannelId = projectField(threadChannelId, baseline.threadChannelId, candidate.threadChannelId),
            replyCount = projectField(replyCount, baseline.replyCount, candidate.replyCount),
            unreadReplyCount =
                projectField(unreadReplyCount, baseline.unreadReplyCount, candidate.unreadReplyCount),
            firstUnreadReplyMessageId =
                projectField(
                    firstUnreadReplyMessageId,
                    baseline.firstUnreadReplyMessageId,
                    candidate.firstUnreadReplyMessageId,
                ),
            isThreadFollowed =
                projectField(isThreadFollowed, baseline.isThreadFollowed, candidate.isThreadFollowed),
            inlineReplies = projectField(inlineReplies, baseline.inlineReplies, candidate.inlineReplies),
            isSaved = projectField(isSaved, baseline.isSaved, candidate.isSaved),
        )
    }

    val projectedMessages = messages.map { message -> message.project() }
    val projectedOrigin = originMessage?.project()
    val projectedAuthority =
        projectField(replySummariesAuthority, previous.replySummariesAuthority, patch.replySummariesAuthority)
    return if (
        projectedMessages == messages &&
        projectedOrigin == originMessage &&
        projectedAuthority == replySummariesAuthority
    ) {
        this
    } else {
        copy(
            messages = projectedMessages,
            originMessage = projectedOrigin,
            replySummariesAuthority = projectedAuthority,
        ).withOriginReplyCountAtLeastLoadedReplies()
    }
}

private fun KuiklyThreadScreenPayload.withAcceptedThreadRepliesProjection(
    accepted: ThreadRepliesAcceptedProjection?,
): KuiklyThreadScreenPayload {
    accepted ?: return this
    fun KuiklyThreadMessageItem.project(): KuiklyThreadMessageItem =
        if (id == accepted.identity.parentMessageId) {
            copy(
                threadChannelId = accepted.identity.threadChannelId,
                replyCount = accepted.model.replyCount,
                inlineReplies = accepted.model,
            )
        } else {
            this
        }
    val projectedMessages = messages.map { it.project() }
    val projectedOrigin = originMessage?.project()
    return if (projectedMessages == messages && projectedOrigin == originMessage) {
        this
    } else {
        copy(messages = projectedMessages, originMessage = projectedOrigin)
    }
}

private fun List<KuiklyThreadMessageItem>.shouldRetainExpandedTimelineWindowFrom(
    previous: List<KuiklyThreadMessageItem>
): Boolean {
    if (isEmpty() || previous.size <= size) return false
    val previousIds = previous.map { it.id }
    val previousIdSet = previousIds.toSet()
    val existingIncomingIds = map { it.id }.filter { it in previousIdSet }
    if (existingIncomingIds.isEmpty()) return false
    if (
        previous.size >= SharedThreadPaging.DefaultPageSize &&
        existingIncomingIds.size < SharedThreadPaging.DefaultPageSize / 2
    ) {
        return false
    }
    return existingIncomingIds == previousIds.takeLast(existingIncomingIds.size)
}

private fun List<KuiklyThreadMessageItem>.mergeIntoExpandedTimelineWindow(
    previous: List<KuiklyThreadMessageItem>
): List<KuiklyThreadMessageItem> {
    val incomingById = associateBy { it.id }
    val previousIds = previous.mapTo(mutableSetOf()) { it.id }
    return (
        previous.map { message -> incomingById[message.id] ?: message } +
            filterNot { message -> message.id in previousIds }
        ).sortedByThreadTimeline()
}

private fun KuiklyThreadScreenPayload.shouldPreserveLoadedOriginFrom(
    previous: KuiklyThreadScreenPayload
): Boolean {
    val previousOrigin = previous.originMessage ?: return false
    if (originMessage != null) return false
    if (surfaceKind != KuiklyThreadSurfaceKind.ReplyThread || previous.surfaceKind != KuiklyThreadSurfaceKind.ReplyThread) {
        return false
    }
    if (originStatus != "missing" && originStatus != "loading") return false
    val incomingOriginId =
        originParentMessageId?.takeIf { it.isNotBlank() }
            ?: parentMessageId?.takeIf { it.isNotBlank() }
            ?: return false
    val previousOriginId =
        previous.originParentMessageId?.takeIf { it.isNotBlank() }
            ?: previous.parentMessageId?.takeIf { it.isNotBlank() }
            ?: previousOrigin.id.takeIf { it.isNotBlank() }
    return previousOriginId == incomingOriginId && previousOrigin.id == incomingOriginId
}

private inline fun <T, K> List<T>.preserveStableItemsFrom(
    previous: List<T>,
    keySelector: (T) -> K
): List<T> {
    if (isEmpty() || previous.isEmpty()) return this
    val previousByKey = previous.associateBy(keySelector)
    var reusedAnyReference = false
    val stableItems =
        map { incoming ->
            val previousItem = previousByKey[keySelector(incoming)]
            if (previousItem != null && previousItem == incoming) {
                reusedAnyReference = reusedAnyReference || previousItem !== incoming
                previousItem
            } else {
                incoming
            }
        }
    val fullyMatchesPrevious =
        stableItems.size == previous.size &&
            stableItems.indices.all { index -> stableItems[index] === previous[index] }
    return when {
        fullyMatchesPrevious -> previous
        reusedAnyReference -> stableItems
        else -> this
    }
}

private fun List<KuiklyThreadMessageItem>.withSelectedReactionsFrom(
    previous: List<KuiklyThreadMessageItem>,
    enabled: Boolean
): List<KuiklyThreadMessageItem> {
    if (!enabled || isEmpty() || previous.isEmpty()) return this
    val previousById = previous.associateBy { it.id }
    var changed = false
    val merged =
        map { message ->
            val previousMessage = previousById[message.id] ?: return@map message
            val nextMessage = message.withSelectedReactionsFrom(previousMessage, enabled = true)
            changed = changed || nextMessage !== message
            nextMessage
        }
    return if (changed) merged else this
}

private fun KuiklyThreadMessageItem.withSelectedReactionsFrom(
    previous: KuiklyThreadMessageItem?,
    enabled: Boolean
): KuiklyThreadMessageItem {
    if (!enabled || previous == null) return this
    val selectedPrevious = previous.reactions.filter { it.selected && it.count > 0 }
    if (selectedPrevious.isEmpty()) return this
    val nextReactions = reactions.withSelectedReactionsFrom(selectedPrevious)
    val orderedReactions = nextReactions.orderedLike(previous.reactions)
    return if (orderedReactions === reactions) this else copy(reactions = orderedReactions)
}

private fun List<KuiklyThreadReactionItem>.withSelectedReactionsFrom(
    selectedPrevious: List<KuiklyThreadReactionItem>
): List<KuiklyThreadReactionItem> {
    var changed = false
    val byEmoji = associateBy { it.emoji }.toMutableMap()
    selectedPrevious.forEach { previousReaction ->
        val incoming = byEmoji[previousReaction.emoji]
        if (incoming == null || !incoming.selected || incoming.count <= 0) {
            byEmoji[previousReaction.emoji] =
                incoming?.copy(
                    count = maxOf(incoming.count, previousReaction.count),
                    selected = true,
                    reactorIds = (incoming.reactorIds + previousReaction.reactorIds).distinct(),
                    reactorNames = (incoming.reactorNames + previousReaction.reactorNames).distinct()
                )
                    ?: previousReaction
            changed = true
        }
    }
    return if (changed) byEmoji.values.toList() else this
}

private fun List<KuiklyThreadReactionItem>.orderedLike(
    previous: List<KuiklyThreadReactionItem>
): List<KuiklyThreadReactionItem> {
    if (size <= 1 || previous.isEmpty()) return this
    val previousOrder = previous.map { it.emoji }.filter { it.isNotBlank() }.distinct()
    if (previousOrder.isEmpty()) return this
    val currentByEmoji = associateBy { it.emoji }
    val ordered =
        buildList(size) {
            previousOrder.forEach { emoji ->
                currentByEmoji[emoji]?.let(::add)
            }
            this@orderedLike.forEach { reaction ->
                if (reaction.emoji !in previousOrder) add(reaction)
            }
        }
    return if (ordered == this) this else ordered
}

private fun List<KuiklyThreadTaskItem>.withReferencedThreadTasksFrom(
    previous: List<KuiklyThreadTaskItem>,
    messages: List<KuiklyThreadMessageItem>,
    originMessage: KuiklyThreadMessageItem?
): List<KuiklyThreadTaskItem> {
    if (previous.isEmpty()) return this
    val referencedMessages = messages + listOfNotNull(originMessage)
    if (referencedMessages.none { it.hasThreadTaskReference() }) return this
    val existingIds = mapNotNull { item -> item.id.takeIf { it.isNotBlank() } }.toSet()
    val existingNumbers = mapNotNull { item -> item.taskNumber }.toSet()
    val retained =
        previous.filter { item ->
            val taskNumberIsSuperseded = item.taskNumber?.let { it in existingNumbers } == true
            item.matchesAnyThreadTaskReference(referencedMessages) &&
                item.id.takeIf { it.isNotBlank() } !in existingIds &&
                !taskNumberIsSuperseded
        }
    if (retained.isEmpty()) return this
    return this + retained
}

private fun KuiklyThreadMessageItem.hasThreadTaskReference(): Boolean =
    taskId?.isNotBlank() == true || taskNumber != null

private fun KuiklyThreadTaskItem.matchesAnyThreadTaskReference(
    messages: List<KuiklyThreadMessageItem>
): Boolean =
    messages.any { message ->
        val taskIdMatches =
            id.isNotBlank() &&
                message.taskId?.takeIf { it.isNotBlank() } == id
        val taskNumberMatches =
            taskNumber != null &&
                message.taskNumber == taskNumber
        taskIdMatches || taskNumberMatches
    }

private val MemberProfile.memberKey: String
    get() = "${kind.name}:${id.value}"

private fun MemberProfile.toKuiklyThreadMemberItem(localServerId: ServerId?): KuiklyThreadMemberItem =
    KuiklyThreadMemberItem(
        id = id.value,
        displayName = displayName,
        handle = handle,
        roleLabel =
            roleDescription
                ?: when (kind) {
                    MemberKind.Agent -> "Agent"
                    MemberKind.Human -> "Member"
                    MemberKind.System -> "System"
                    MemberKind.External -> "External"
                },
        description = normalizedThreadMemberDescription(description),
        avatarUrl = avatarWireValueForAvatarSlot(),
        avatarLabel = avatarLabel,
        kind = kind.name.lowercase(),
        sourceServerId = serverId?.value,
        sourceServerLabel = remoteSourceServerLabel(localServerId),
        canRemove = isLocalToServer(localServerId)
    )

private fun MemberProfile.isLocalToServer(localServerId: ServerId?): Boolean {
    val sourceId = serverId?.value?.takeIf { it.isNotBlank() } ?: return true
    val localId = localServerId?.value?.takeIf { it.isNotBlank() } ?: return true
    return sourceId == localId
}

private fun MemberProfile.remoteSourceServerLabel(localServerId: ServerId?): String? {
    if (isLocalToServer(localServerId)) return null
    return serverName?.takeIf { it.isNotBlank() } ?: serverSlug?.takeIf { it.isNotBlank() }
}

private fun ChannelFilesSnapshot.toKuiklyThreadFilesPayload(
    basePayload: KuiklyThreadScreenPayload,
    currentPayload: KuiklyThreadFilesPayload?,
    append: Boolean
): KuiklyThreadFilesPayload {
    val incoming = items.map { item ->
        val attachment = item.attachment
        KuiklyThreadFileItem(
            attachmentId = attachment.id.value,
            messageId = item.messageId?.value.orEmpty(),
            channelId = item.channelId?.value.orEmpty(),
            name = attachment.name,
            sizeBytes = attachment.sizeBytes,
            sizeLabel = attachment.sizeLabel,
            createdAtLabel = threadFileCreatedAtValue(item.createdAt),
            sourceLabel = item.filesSourceLabel(),
            sourceType = item.sourceType.orEmpty(),
            sourceChannelId = item.sourceChannelId?.value.orEmpty(),
            sourceParentMessageId = item.sourceParentMessageId?.value.orEmpty(),
            sourceParentMessageShortId = item.sourceParentMessageShortId.orEmpty(),
            mimeType = attachment.mimeType,
            thumbnailUrl = attachment.thumbnailUrl
        )
    }
    val merged =
        if (append) {
            currentPayload.orEmptyFilesPayload(basePayload).items + incoming
        } else {
            incoming
        }.distinctBy { item -> item.attachmentId }
    return KuiklyThreadFilesPayload(
        instanceId = basePayload.instanceId,
        status = if (merged.isEmpty()) "empty" else "content",
        items = merged,
        hasMore = hasMore,
        loadingMore = false,
        nextCursor = nextCursor,
        errorCode = null
    )
}

private fun KuiklyThreadScreenPayload.withReplySummaries(
    snapshot: ThreadReplySummariesSnapshot,
    authority: build.raft.app.data.ThreadReplySummariesAuthority =
        build.raft.app.data.ThreadReplySummariesAuthority.Targeted,
): KuiklyThreadScreenPayload {
    if (snapshot.threads.isEmpty()) {
        return if (replySummariesAuthority == authority) this else copy(replySummariesAuthority = authority)
    }
    val summariesByParentMessageId =
        snapshot.threads
            .mapNotNull { summary -> summary.parentMessageId?.value?.let { it to summary } }
            .toMap()
    val summariesByThreadChannelId =
        snapshot.threads
            .flatMap { summary ->
                listOfNotNull(
                    summary.threadChannelId?.value?.let { it to summary },
                    summary.channelId.value.let { it to summary }
                )
            }
            .toMap()
    val updatedMessages =
        messages.map { message ->
            val summary =
                summariesByParentMessageId[message.id]
                    ?: message.threadChannelId?.let(summariesByThreadChannelId::get)
                    ?: return@map message
            message.withReplySummary(summary, snapshot.followedThreadKeys)
        }
    return if (updatedMessages == messages && replySummariesAuthority == authority) {
        this
    } else {
        copy(messages = updatedMessages, replySummariesAuthority = authority)
    }
}

private fun KuiklyThreadMessageItem.withReplySummary(
    summary: InboxItem,
    followedThreadKeys: Set<String>
): KuiklyThreadMessageItem {
    val summaryThreadChannelId = summary.threadChannelId ?: summary.channelId
    val nextThreadChannelId = threadChannelId ?: summaryThreadChannelId.value
    val nextReplyCount = summary.replyCount.coerceAtLeast(0)
    val nextUnreadReplyCount = maxOf(unreadReplyCount, summary.unreadCount)
    val nextFirstUnreadReplyMessageId = firstUnreadReplyMessageId ?: summary.firstUnreadMessageId?.value
    val nextFollowed = isThreadFollowed || summary.key in followedThreadKeys
    val incomingInlineReplies =
        initialThreadRepliesReadModel(
            replies = summary.latestReplies,
            replyCount = summary.replyCount,
            limit = InlineThreadReplyPreviewLimit,
        )
    val nextInlineReplies = incomingInlineReplies
    if (
        nextThreadChannelId == threadChannelId &&
        nextReplyCount == replyCount &&
        nextUnreadReplyCount == unreadReplyCount &&
        nextFirstUnreadReplyMessageId == firstUnreadReplyMessageId &&
        nextFollowed == isThreadFollowed &&
        nextInlineReplies == inlineReplies
    ) {
        return this
    }
    return copy(
        threadChannelId = nextThreadChannelId,
        replyCount = nextReplyCount,
        unreadReplyCount = nextUnreadReplyCount,
        firstUnreadReplyMessageId = nextFirstUnreadReplyMessageId,
        isThreadFollowed = nextFollowed,
        inlineReplies = nextInlineReplies,
    )
}

private fun KuiklyThreadFilesPayload?.orEmptyFilesPayload(basePayload: KuiklyThreadScreenPayload): KuiklyThreadFilesPayload =
    this ?: KuiklyThreadFilesPayload(instanceId = basePayload.instanceId)

private fun build.raft.app.data.ChannelFileItem.filesSourceLabel(): String =
    sourceParentMessageShortId
        ?.takeIf { it.isNotBlank() }
        ?.let { "Thread #$it" }
        ?: sourceType
            ?.takeIf { it.isNotBlank() }
            ?.replaceFirstChar { char -> char.uppercaseChar() }
            .orEmpty()

private fun KuiklyThreadScreenPayload.hasRenderableThreadContent(): Boolean =
    messages.isNotEmpty() || originMessage != null || tasks.isNotEmpty()

/** Task #1137: the offline stand-in for a channel that was empty when last seen. */
private fun KuiklyThreadScreenPayload.isDurableEmptyStandIn(): Boolean =
    durableLastKnown && status == "empty"

private fun KuiklyThreadScreenPayload.cacheThreadId(): String? =
    resolveThreadRouteIdentity().timelineChannelId

private fun Message.belongsToReplyThreadContent(threadChannelId: String): Boolean =
    matchesResolvedReplyThreadTimeline(
        resolvedThreadChannelId = threadChannelId,
        messageChannelId = channelId?.value,
        messageThreadId = threadId.value
    )

private fun KuiklyThreadScreenPayload.isUsableCachedPayloadForRoute(route: KuiklyThreadScreenPayload): Boolean =
    ThreadCandidateProofGate
        .prove(route = route.toThreadRouteId(), candidate = toThreadRouteId())
        .let { it is ThreadIdentityProof.Accepted }

private fun KuiklyThreadScreenPayload.toThreadRouteId(serverId: ServerId? = null): ThreadRouteId {
    val parentChannel = parentChannelId?.takeIf { it.isNotBlank() }?.let(::ChannelId)
    val parentMessageStr =
        parentMessageId?.takeIf { it.isNotBlank() }
            ?: originParentMessageId?.takeIf { it.isNotBlank() }
    val parentMessage = parentMessageStr?.let(::MessageId)
    val stream = streamChannelId?.takeIf { it.isNotBlank() }?.let(::ChannelId)
    val source = sourceThreadId?.takeIf { it.isNotBlank() }?.let(::ThreadId)
    val isReplyThread =
        surfaceKind == KuiklyThreadSurfaceKind.ReplyThread ||
            (parentChannel != null && parentMessage != null)
    return when {
        isReplyThread && parentChannel != null && parentMessage != null -> {
            // v0.2 contract: ReplyThread requires explicit threadChannelId. If stream is
            // missing, we still construct ReplyThread (callers handle missing stream via
            // #275-style `activity_thread_channel_missing` internal state + refresh).
            val resolvedStream = stream ?: source?.let { ChannelId(it.value) }
            if (resolvedStream != null) {
                ThreadRouteId.ReplyThread(
                    parentChannelId = parentChannel,
                    parentMessageId = parentMessage,
                    threadChannelId = resolvedStream,
                    serverId = serverId
                )
            } else {
                // Fallback: synthesize from source threadId to keep v0.2 proof path alive.
                val synthesized = source?.let { ChannelId(it.value) } ?: parentChannel
                ThreadRouteId.ReplyThread(
                    parentChannelId = parentChannel,
                    parentMessageId = parentMessage,
                    threadChannelId = synthesized,
                    serverId = serverId
                )
            }
        }
        else -> ThreadRouteId.ChannelThread(
            channelId = source?.let { ChannelId(it.value) } ?: parentChannel ?: ChannelId(""),
            serverId = serverId
        )
    }
}

private fun KuiklyThreadScreenPayload.visibleReadMarker(
    visibleTailIndex: Int? = null
): ThreadVisibleReadMarker? {
    val channelId = cacheThreadId() ?: return null
    // task #232: bound the read marker to the rendered visible tail when known,
    // so live appends while off-bottom never advance the shared read frontier.
    val markerCandidates =
        visibleTailIndex?.takeIf { it in messages.indices }?.let { tail ->
            messages.subList(0, tail + 1)
        } ?: messages
    val latest =
        markerCandidates
            .asSequence()
            .filter { message -> message.seq != null }
            .maxWithOrNull(
                compareBy<KuiklyThreadMessageItem> { message -> message.seq ?: Long.MIN_VALUE }
                    .thenBy(KuiklyThreadMessageItem::id)
            )
            ?: messages.lastOrNull()
            ?: return null
    return ThreadVisibleReadMarker(
        channelId = channelId,
        latestMessageId = latest.id,
        latestSeq = latest.seq
    )
}

private fun KuiklyThreadScreenPayload.applyRealtimeEvent(
    event: RealtimeEvent,
    currentUserId: String?,
    activeReplyParentMessageId: MessageId? = null,
    shouldPreserveReactionsForRealtime: (Message, String?) -> Boolean = { _, _ -> false }
): KuiklyThreadScreenPayload =
    when (event) {
        is MessageNew ->
            applyRealtimeMessages(listOf(event.message), currentUserId, activeReplyParentMessageId)
        is MessageUpdated ->
            applyRealtimeMessageUpdate(
                message = event.message,
                currentUserId = currentUserId,
                activeReplyParentMessageId = activeReplyParentMessageId,
                preserveExistingReactions = shouldPreserveReactionsForRealtime(event.message, currentUserId)
            )
        is SyncResumeResponse ->
            applyRealtimeMessages(event.messages, currentUserId, activeReplyParentMessageId)
        is ThreadUpdated ->
            applyThreadUpdated(event)
        is MemberPresenceUpdated ->
            this
        else -> this
    }

private fun KuiklyThreadScreenPayload.realtimeNewerGapServerSeq(
    event: RealtimeEvent,
    activeReplyParentMessageId: MessageId? = null
): Long? {
    val currentMaxSeq = messages.mapNotNull { it.seq }.maxOrNull() ?: 0L
    if (currentMaxSeq <= 0L) return null
    val existingIds = messages.mapTo(mutableSetOf()) { it.id }
    val eventMessages =
        when (event) {
            is MessageNew -> listOf(event.message)
            is SyncResumeResponse -> event.messages
            else -> return null
        }
    return eventMessages
        .asSequence()
        .filter { message -> message.matchesSharedThread(this, activeReplyParentMessageId) }
        .filterNot { message -> message.id.value in existingIds }
        .mapNotNull { message -> message.seq }
        .filter { seq -> seq > currentMaxSeq + 1L }
        .maxOrNull()
}

private fun KuiklyThreadScreenPayload.applyRealtimeMessages(
    messages: List<Message>,
    currentUserId: String?,
    activeReplyParentMessageId: MessageId?
): KuiklyThreadScreenPayload {
    val currentMaxSeq = this.messages.mapNotNull { it.seq }.maxOrNull() ?: 0L
    val existingIds = this.messages.mapTo(mutableSetOf()) { it.id }
    val relevant =
        messages.filter { message ->
            message.matchesSharedThread(this, activeReplyParentMessageId)
        }.filter { message ->
            if (!canLoadMoreAfter) return@filter true
            if (message.id.value in existingIds) return@filter true
            val seq = message.seq ?: return@filter true
            currentMaxSeq <= 0L || seq <= currentMaxSeq + 1L
        }
    if (relevant.isEmpty()) return this
    val incoming = relevant.map { message -> message.toKuiklyThreadMessageItem(currentUserId) }
    val baseMessages = this.messages.absorbingCanonicalSendEchoes(incoming)
    val merged =
        (baseMessages + incoming)
            .distinctBy { item -> item.id }
    return copy(
        status = "content",
        errorCode = null,
        messages = merged.sortedByThreadTimeline()
    ).withOriginReplyCountAtLeastLoadedReplies()
        .asLoadedEmptyReplyThreadPayload()
}

/**
 * Task #232 arrival provenance: records IDs first merged by this payload
 * (relative to [base]) as gap-refill arrivals, which the ↓ x new projection
 * excludes. Live realtime events and resume refreshes never call this.
 */
internal fun KuiklyThreadScreenPayload.withGapRefillTagFrom(
    base: KuiklyThreadScreenPayload
): KuiklyThreadScreenPayload {
    val baseIds = base.messages.mapTo(mutableSetOf()) { it.id }
    val added =
        messages.mapTo(mutableSetOf()) { it.id } - baseIds
    val newlyExcluded = added.filter { it.isNotBlank() }
    if (newlyExcluded.isEmpty()) return this
    return copy(gapRefillMessageIds = gapRefillMessageIds + newlyExcluded)
}

internal fun KuiklyThreadScreenPayload.mergePagedMessages(
    pageMessages: List<KuiklyThreadMessageItem>,
    before: Boolean,
    pageSize: Int
): KuiklyThreadScreenPayload {
    val baseMessages = messages.absorbingCanonicalSendEchoes(pageMessages)
    val incoming = pageMessages.filterNot { pageMessage -> baseMessages.any { it.id == pageMessage.id } }
    val mergedMessages =
        if (before) {
            (incoming + baseMessages).distinctBy { it.id }
        } else {
            (baseMessages + incoming).distinctBy { it.id }
        }.sortedByThreadTimeline()
    val hasMore = incoming.size >= SharedThreadPaging.resolve(pageSize)
    return copy(
        status = "content",
        errorCode = null,
        messages = mergedMessages,
        canLoadMoreBefore = if (before) hasMore else canLoadMoreBefore,
        canLoadMoreAfter = if (before) canLoadMoreAfter else hasMore,
        olderMessagesStatus = if (before) "idle" else olderMessagesStatus,
        newerMessagesStatus = if (before) newerMessagesStatus else "idle"
    ).withOriginReplyCountAtLeastLoadedReplies()
        .asLoadedEmptyReplyThreadPayload()
}

private fun KuiklyThreadScreenPayload.withLocalSendStarted(
    message: KuiklyThreadMessageItem
): KuiklyThreadScreenPayload {
    val sendingMessage =
        message.copy(
            deliveryState = ThreadMessageDeliverySending,
            pendingSendErrorCode = null,
            pendingSendErrorMessage = null
        )
    val nextMessages =
        if (messages.any { it.id == sendingMessage.id }) {
            messages.map { existing -> if (existing.id == sendingMessage.id) sendingMessage else existing }
        } else {
            messages + sendingMessage
        }
    return copy(
        status = "content",
        errorCode = null,
        messages = nextMessages.sortedByThreadTimeline()
    ).withOriginReplyCountAtLeastLoadedReplies()
}

private fun KuiklyThreadScreenPayload.withLocalSendFailure(
    messageId: String,
    errorCode: String?,
    errorMessage: String?
): KuiklyThreadScreenPayload =
    copy(
        messages =
            messages.map { message ->
                if (message.id == messageId && message.pendingSendContext != null) {
                    message.copy(
                        deliveryState = ThreadMessageDeliveryFailed,
                        pendingSendErrorCode = errorCode,
                        pendingSendErrorMessage = errorMessage
                    )
                } else {
                    message
                }
            }
    )

private fun KuiklyThreadScreenPayload.withoutLocalSendMessage(messageId: String): KuiklyThreadScreenPayload =
    copy(messages = messages.filterNot { it.id == messageId && it.pendingSendContext != null })

private fun KuiklyThreadScreenPayload.withoutLocalFailedSendMessage(messageId: String): KuiklyThreadScreenPayload =
    copy(messages = messages.filterNot { it.id == messageId && it.isPersistentLocalFailedMessage() })

private fun KuiklyThreadScreenPayload.withoutTransientLocalSendingMessages(): KuiklyThreadScreenPayload {
    val retainedMessages = messages.filterNot { it.isTransientLocalSendingMessage() }
    return if (retainedMessages.size == messages.size) this else copy(messages = retainedMessages)
}

private fun KuiklyThreadScreenPayload.withoutLocalPendingSendMessages(): KuiklyThreadScreenPayload {
    val retainedMessages = messages.filterNot { it.pendingSendContext != null }
    return if (retainedMessages.size == messages.size) this else copy(messages = retainedMessages)
}

private fun List<KuiklyThreadMessageItem>.withRetainedTransientLocalSendingMessagesFrom(
    previousMessages: List<KuiklyThreadMessageItem>
): List<KuiklyThreadMessageItem> {
    val existingIds = map { it.id }.toSet()
    val retainedSendingMessages =
        previousMessages.filter { message ->
            message.id !in existingIds && message.isTransientLocalSendingMessage()
        }
    return if (retainedSendingMessages.isEmpty()) this else (this + retainedSendingMessages).sortedByThreadTimeline()
}

private fun List<KuiklyThreadMessageItem>.withRetainedCanonicalOwnSendsAfterRefreshFrom(
    previousMessages: List<KuiklyThreadMessageItem>,
    refreshObservedMessageIds: Set<String>?
): List<KuiklyThreadMessageItem> {
    refreshObservedMessageIds ?: return this
    val existingIds = mapTo(mutableSetOf()) { message -> message.id }
    val incomingRandomIds = mapNotNullTo(mutableSetOf()) { message -> message.randomId?.takeIf { it.isNotBlank() } }
    val retainedMessages =
        previousMessages.filter { message ->
            val randomId = message.randomId?.takeIf { it.isNotBlank() } ?: return@filter false
            message.id !in existingIds &&
                message.id !in refreshObservedMessageIds &&
                message.isCanonicalOwnSend() &&
                randomId !in incomingRandomIds
        }
    return if (retainedMessages.isEmpty()) this else (this + retainedMessages).sortedByThreadTimeline()
}

private fun KuiklyThreadMessageItem.isCanonicalOwnSend(): Boolean =
    isOwn &&
        pendingSendContext == null &&
        deliveryState.equals(ThreadMessageDeliverySent, ignoreCase = true) &&
        !randomId.isNullOrBlank()

private fun List<KuiklyThreadMessageItem>.withRetainedLocalFailedMessagesFrom(
    previousMessages: List<KuiklyThreadMessageItem>
): List<KuiklyThreadMessageItem> {
    val existingIds = map { it.id }.toSet()
    val retainedFailedMessages =
        previousMessages.filter { message ->
            message.id !in existingIds && message.isPersistentLocalFailedMessage()
        }
    return if (retainedFailedMessages.isEmpty()) this else (this + retainedFailedMessages).sortedByThreadTimeline()
}

private fun KuiklyThreadMessageItem.isPersistentLocalFailedMessage(): Boolean =
    id.startsWith(ThreadTransientLocalSendIdPrefix) &&
        deliveryState.equals(ThreadMessageDeliveryFailed, ignoreCase = true) &&
        pendingSendContext != null

private fun KuiklyThreadScreenPayload.trimToLatestThreadWindow(limit: Int): KuiklyThreadScreenPayload {
    if (limit <= 0 || messages.isEmpty()) return withOriginReplyCountAtLeastLoadedReplies()
    val sortedMessages = messages.sortedByThreadTimeline()
    if (sortedMessages.size <= limit) {
        return (if (sortedMessages == messages) this else copy(messages = sortedMessages))
            .withOriginReplyCountAtLeastLoadedReplies()
    }
    val projectedOrigin =
        originMessage?.let { origin ->
            if (sortedMessages.size > origin.replyCount) {
                origin.copy(replyCount = sortedMessages.size)
            } else {
                origin
            }
        }
    return copy(
        status = "content",
        errorCode = null,
        messages = sortedMessages.takeLast(limit),
        originMessage = projectedOrigin,
        canLoadMoreBefore = true,
        olderMessagesStatus = "idle"
    ).withOriginReplyCountAtLeastLoadedReplies()
}

internal fun KuiklyThreadScreenPayload.withOriginReplyCountAtLeastLoadedReplies(): KuiklyThreadScreenPayload {
    val origin = originMessage ?: return this
    val loadedReplyCount = messages.size
    if (loadedReplyCount <= origin.replyCount) return this
    return copy(originMessage = origin.copy(replyCount = loadedReplyCount))
}

internal fun List<KuiklyThreadMessageItem>.sortedByThreadTimeline(): List<KuiklyThreadMessageItem> =
    sortedWith(SharedThreadTimelineComparator)

/**
 * A single lexicographic total order for every message pair.
 *
 * Canonical server messages stay sequence-authoritative. A sent message whose
 * sequence has not arrived yet is kept in a separate fallback lane ordered by
 * its numeric creation instant. Optimistic/failed sends remain last and use
 * their creation instant plus a stable local-send ordinal. No branch reads the
 * localized [KuiklyThreadMessageItem.timestampLabel].
 *
 * The old pair-dependent comparator used `seq` only when both operands had it,
 * then compared display strings for mixed pairs. That admitted cycles such as
 * A(seq=1,label=z) < B(seq=2,label=a) < M(seq=null,label=m) < A.
 */
internal val SharedThreadTimelineComparator =
    Comparator<KuiklyThreadMessageItem> { left, right ->
        compareValues(left.threadTimelineSortLane(), right.threadTimelineSortLane())
            .takeIf { it != 0 }
            ?: compareValues(left.threadTimelineSequenceKey(), right.threadTimelineSequenceKey())
                .takeIf { it != 0 }
            ?: compareValues(left.createdAtEpochMillis ?: Long.MAX_VALUE, right.createdAtEpochMillis ?: Long.MAX_VALUE)
                .takeIf { it != 0 }
            ?: compareValues(left.localSendOrder ?: Long.MAX_VALUE, right.localSendOrder ?: Long.MAX_VALUE)
                .takeIf { it != 0 }
            ?: left.id.compareTo(right.id)
    }

private fun KuiklyThreadMessageItem.threadTimelineSortLane(): Int =
    when {
        hasLocalSendState() -> 2
        seq != null -> 0
        else -> 1
    }

private fun KuiklyThreadMessageItem.threadTimelineSequenceKey(): Long =
    if (threadTimelineSortLane() == 0) seq ?: Long.MAX_VALUE else Long.MAX_VALUE

private fun KuiklyThreadScreenPayload.applyRealtimeMessageUpdate(
    message: Message,
    currentUserId: String?,
    activeReplyParentMessageId: MessageId?,
    preserveExistingReactions: Boolean = false
): KuiklyThreadScreenPayload {
    val incoming = message.toKuiklyThreadMessageItem(currentUserId)
    val matchesTimeline = messages.any { it.id == incoming.id }
    val matchesOrigin = originMessage?.id == incoming.id
    val replyThreadContentChannelId = replyThreadContentChannelId()
    val matchesReplyOrigin =
        replyThreadContentChannelId != null &&
            (
                matchesOrigin ||
                    parentMessageId?.takeIf { it.isNotBlank() } == incoming.id ||
                    originParentMessageId?.takeIf { it.isNotBlank() } == incoming.id
                )
    val matchesThreadContent =
        if (matchesReplyOrigin) {
            false
        } else if (replyThreadContentChannelId != null) {
            message.belongsToReplyThreadContent(replyThreadContentChannelId)
        } else {
            message.matchesSharedThread(this, activeReplyParentMessageId)
        }
    val shouldUpdateTimeline = matchesTimeline || matchesThreadContent
    if (!matchesOrigin && !shouldUpdateTimeline) return this
    val reactionUpdate = message.reactions.toRealtimeThreadReactionItems(currentUserId, threadReactorNameById())
    val updatedMessages =
        if (shouldUpdateTimeline) {
            messages.map { item ->
                if (item.id == incoming.id) {
                    item.mergeRealtimeUpdate(
                        update = incoming,
                        reactionUpdate = reactionUpdate,
                        preserveExistingReactions = preserveExistingReactions
                    )
                } else {
                    item
                }
            }
        } else {
            messages
        }
    val mergedMessages =
        if (!shouldUpdateTimeline || updatedMessages.any { it.id == incoming.id }) {
            updatedMessages
        } else {
            updatedMessages + incoming
        }
    val updatedOrigin =
        if (matchesOrigin) {
            originMessage.mergeRealtimeUpdate(
                update = incoming,
                reactionUpdate = reactionUpdate,
                preserveExistingReactions = preserveExistingReactions
            )
        } else {
            originMessage
        }
    return copy(
        status = "content",
        errorCode = null,
        messages = mergedMessages.distinctBy { it.id },
        originMessage = updatedOrigin
    ).withOriginReplyCountAtLeastLoadedReplies()
}

private fun KuiklyThreadScreenPayload.applyThreadUpdated(event: ThreadUpdated): KuiklyThreadScreenPayload {
    fun KuiklyThreadMessageItem.updateIfMatches(): KuiklyThreadMessageItem =
        if (id == event.parentMessageId.value) {
            copy(
                threadChannelId = event.threadChannelId.value,
                replyCount = event.replyCount,
                unreadReplyCount = event.unreadCount ?: unreadReplyCount,
                firstUnreadReplyMessageId =
                    when {
                        event.unreadCount == 0 -> null
                        event.firstUnreadMessageId != null -> event.firstUnreadMessageId.value
                        else -> firstUnreadReplyMessageId
                    },
            )
        } else {
            this
        }
    val updatedMessages = messages.map { it.updateIfMatches() }
    val updatedOrigin = originMessage?.updateIfMatches()
    if (updatedMessages == messages && updatedOrigin == originMessage) return this
    return copy(messages = updatedMessages, originMessage = updatedOrigin)
        .withOriginReplyCountAtLeastLoadedReplies()
}

internal fun KuiklyThreadScreenPayload.withActionCard(messageId: String, card: ThreadActionCard): KuiklyThreadScreenPayload =
    copy(
        messages = messages.map { message ->
            if (message.id == messageId) message.withActionCard(card) else message
        },
        originMessage = originMessage?.let { message ->
            if (message.id == messageId) message.withActionCard(card) else message
        }
    )

private fun KuiklyThreadMessageItem.withActionCard(card: ThreadActionCard): KuiklyThreadMessageItem =
    copy(metadata = metadata.filterKeys { !it.startsWith("actionCard.") } + card.toMessageMetadata())

private fun List<String>.cleanActionCardIds(): List<String> =
    map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

internal fun KuiklyThreadScreenPayload.withContentSliceFrom(patch: KuiklyThreadScreenPayload): KuiklyThreadScreenPayload {
    val acceptsPatchHeader =
        resolveThreadRouteIdentity().canRetainThreadPageFor(patch.resolveThreadRouteIdentity())
    val patchTitle =
        if (acceptsPatchHeader) {
            threadHeaderSafeTitleOrNull(
                patch.title,
                patch.conversationHeaderLookupIds().toSet()
            )?.takeUnless { it.isGenericThreadHeaderFallbackLabel() }
        } else {
            null
        }
    val patchDmPeerName =
        if (acceptsPatchHeader) {
            threadHeaderSafeTitleOrNull(
                patch.dmPeerName,
                patch.conversationHeaderLookupIds().toSet()
            )?.takeUnless { it.isGenericThreadHeaderFallbackLabel() }
        } else {
            null
        }
    return copy(
        // Only a content response for this retained destination may enrich header
        // metadata. Even then, a neutral route fallback (for example "Direct Message")
        // must never erase a valid canonical detail/directory value.
        title = patchTitle ?: title,
        subtitle =
            if (acceptsPatchHeader) {
                patch.subtitle?.takeIf { it.isNotBlank() } ?: subtitle
            } else {
                subtitle
            },
        status = patch.status,
        backgroundRefreshing = patch.backgroundRefreshing,
        // The content slice carries its own provenance: a network patch is never last-known.
        durableLastKnown = patch.durableLastKnown,
        mainLoadFailed = patch.mainLoadFailed,
        messages = patch.messages,
        gapRefillMessageIds = patch.gapRefillMessageIds,
        originMessage = patch.originMessage,
        originPreview = patch.originPreview,
        originParentMessageId = patch.originParentMessageId,
        originStatus = patch.originStatus,
        originErrorCode = patch.originErrorCode,
        knownMarkdownChannelNames =
            patch.knownMarkdownChannelNames.ifEmpty { knownMarkdownChannelNames },
        knownMarkdownDirectMessagePeerNames =
            patch.knownMarkdownDirectMessagePeerNames.ifEmpty { knownMarkdownDirectMessagePeerNames },
        canLoadMoreBefore = patch.canLoadMoreBefore,
        canLoadMoreAfter = patch.canLoadMoreAfter,
        olderMessagesStatus = patch.olderMessagesStatus,
        newerMessagesStatus = patch.newerMessagesStatus,
        errorCode = patch.errorCode,
        errorServerMessage = patch.errorServerMessage,
        emptyTitle = patch.emptyTitle,
        emptyDetail = patch.emptyDetail,
        replySummariesAuthority = patch.replySummariesAuthority,
        dmPeerName = patchDmPeerName ?: dmPeerName,
        dmPeerAvatarUrl =
            if (acceptsPatchHeader) {
                patch.dmPeerAvatarUrl?.takeIf { it.isNotBlank() } ?: dmPeerAvatarUrl
            } else {
                dmPeerAvatarUrl
            },
        dmPeerAvatarLabel =
            if (acceptsPatchHeader) {
                patch.dmPeerAvatarLabel?.takeIf { it.isNotBlank() } ?: dmPeerAvatarLabel
            } else {
                dmPeerAvatarLabel
            }
    ).withOriginReplyCountAtLeastLoadedReplies()
}

internal fun KuiklyThreadScreenPayload.withThreadContentProjectionFrom(
    patch: KuiklyThreadScreenPayload
): KuiklyThreadScreenPayload =
    withResolvedReplyThreadIdentityFrom(patch)
        .withContentSliceFrom(patch)
        .withTaskSliceFrom(patch)

internal fun KuiklyThreadScreenPayload.withCanonicalConversationAccessFrom(
    patch: KuiklyThreadScreenPayload
): KuiklyThreadScreenPayload =
    copy(
        joined = patch.joined,
        canSend = patch.canSend,
        composer = composer.copy(enabled = patch.composer.enabled)
    )

internal fun KuiklyThreadScreenPayload.withInitialHomeConversationAccessFallback(
    candidate: ConversationAccessCandidate?
): KuiklyThreadScreenPayload {
    candidate ?: return this
    if (!acceptsInitialConversationAccessCandidate(candidate)) return this
    if (surfaceKind == KuiklyThreadSurfaceKind.ReplyThread) {
        // Reply capability belongs to the parent Channel/DM, not to the
        // synthetic thread conversation returned with reply content.
        return copy(
            joined = candidate.joined,
            canSend = candidate.permissions.canSend,
            composer =
                composer.copy(
                    enabled = candidate.joined && candidate.permissions.canSend
                )
        )
    }
    if (candidate.joined) return this
    return copy(
        joined = false,
        canSend = candidate.permissions.canSend,
        composer = composer.copy(enabled = false)
    )
}

/**
 * The Home projection is only an initial access candidate. Once a canonical non-reply
 * conversation fetch has resolved access, a stale pre-join Home row must not override it or turn
 * a mounted Composer back into a Join footer. Reply threads still need the Home candidate because
 * their canonical stream response does not attest the parent channel's write authority.
 */
internal fun KuiklyThreadScreenPayload.withInitialHomeConversationAccessFallbackUnlessCanonical(
    canonicalAccessResolved: Boolean,
    candidate: ConversationAccessCandidate?,
): KuiklyThreadScreenPayload =
    if (canonicalAccessResolved && surfaceKind != KuiklyThreadSurfaceKind.ReplyThread) {
        this
    } else {
        withInitialHomeConversationAccessFallback(candidate)
    }

private fun ThreadAccessResolution.afterRefreshResult(
    result: DataResult<*>,
    canonicalAccessResolved: Boolean,
    homeLookupComplete: Boolean,
    homeAccessCandidate: ConversationAccessCandidate?,
    requiresParentAccessCandidate: Boolean,
    hasReadableCanonicalReplyContent: Boolean
): ThreadAccessResolution =
    when (result) {
        is DataResult.Content ->
            when {
                this == ThreadAccessResolution.Resolved -> ThreadAccessResolution.Resolved
                !canonicalAccessResolved || !homeLookupComplete -> ThreadAccessResolution.Loading
                requiresParentAccessCandidate &&
                    homeAccessCandidate == null &&
                    !hasReadableCanonicalReplyContent ->
                    ThreadAccessResolution.Error
                else -> ThreadAccessResolution.Resolved
            }
        is DataResult.Empty ->
            when {
                this == ThreadAccessResolution.Resolved &&
                    (!requiresParentAccessCandidate || homeAccessCandidate != null) ->
                    ThreadAccessResolution.Resolved
                result.reason == EmptyReason.NoMessages &&
                    canonicalAccessResolved &&
                    !requiresParentAccessCandidate ->
                    ThreadAccessResolution.Resolved
                result.reason == EmptyReason.NoMessages &&
                    canonicalAccessResolved &&
                    !homeLookupComplete ->
                    ThreadAccessResolution.Loading
                result.reason == EmptyReason.NoMessages &&
                    canonicalAccessResolved &&
                    homeAccessCandidate == null ->
                    ThreadAccessResolution.Error
                homeAccessCandidate != null -> ThreadAccessResolution.Resolved
                homeLookupComplete -> ThreadAccessResolution.Error
                else -> ThreadAccessResolution.Loading
            }
        is DataResult.NoPermission -> ThreadAccessResolution.NoPermission
        is DataResult.Error -> if (this == ThreadAccessResolution.Resolved) this else ThreadAccessResolution.Error
    }

// Canonical reply content proves read access independently of parent/origin
// enrichment. The complete reply identity keeps stale or unresolved route
// candidates from acquiring access; parent context remains auxiliary and may
// degrade inline without hiding the canonical timeline.
internal fun KuiklyThreadScreenPayload.hasReadableCanonicalReplyContent(): Boolean {
    if (surfaceKind != KuiklyThreadSurfaceKind.ReplyThread || status != "content" || messages.isEmpty()) return false
    val parentChannel = parentChannelId?.takeIf { it.isNotBlank() } ?: return false
    val parentMessage =
        parentMessageId?.takeIf { it.isNotBlank() }
            ?: originParentMessageId?.takeIf { it.isNotBlank() }
            ?: return false
    val routeIdentity = resolveThreadRouteIdentity()
    if (!routeIdentity.hasCompleteReplyIdentity || routeIdentity.parentKey != "$parentChannel:$parentMessage") {
        return false
    }
    return routeIdentity.canRenderTimelineCandidates && routeIdentity.timelineChannelId != null
}

private fun KuiklyThreadScreenPayload.withReadOnlyReplyAccessWhenHomeCandidateUnavailable(
    homeLookupComplete: Boolean,
    homeAccessCandidate: ConversationAccessCandidate?
): KuiklyThreadScreenPayload {
    if (!isReadOnlyReplyAccessFallback(homeLookupComplete, homeAccessCandidate)) return this
    return copy(
        joined = false,
        canSend = false,
        composer = composer.copy(enabled = false)
    )
}

private fun KuiklyThreadScreenPayload.isReadOnlyReplyAccessFallback(
    homeLookupComplete: Boolean,
    homeAccessCandidate: ConversationAccessCandidate?
): Boolean =
    homeLookupComplete &&
        homeAccessCandidate == null &&
        surfaceKind == KuiklyThreadSurfaceKind.ReplyThread

// Write authority is a terminal access-state invariant, not an inference from
// whichever payload fields survived the latest projection. In particular, a
// canonical permission revocation clears readable content, so re-evaluating
// only the read-only reply fallback would otherwise turn writes back on.
private fun KuiklyThreadScreenPayload.hasWriteAuthorityForAccess(
    accessResolution: ThreadAccessResolution,
    homeLookupComplete: Boolean,
    homeAccessCandidate: ConversationAccessCandidate?
): Boolean =
    accessResolution == ThreadAccessResolution.Resolved &&
        (
            surfaceKind != KuiklyThreadSurfaceKind.ReplyThread ||
                (
                    homeLookupComplete &&
                        homeAccessCandidate != null &&
                        acceptsInitialConversationAccessCandidate(homeAccessCandidate)
                )
        ) &&
        !isReadOnlyReplyAccessFallback(homeLookupComplete, homeAccessCandidate)

private fun KuiklyThreadScreenPayload.withTerminalAccessWriteAuthorityClosed(
    accessResolution: ThreadAccessResolution
): KuiklyThreadScreenPayload =
    if (
        accessResolution == ThreadAccessResolution.NoPermission ||
        accessResolution == ThreadAccessResolution.Error
    ) {
        copy(
            joined = false,
            canSend = false,
            composer = composer.copy(enabled = false)
        )
    } else {
        this
    }

private fun DataResult<*>.diagnosticsResultReason(): String =
    when (this) {
        is DataResult.Content -> "content"
        is DataResult.Empty -> "empty:${reason.name}"
        is DataResult.Error -> "error:${error.code}"
        is DataResult.NoPermission -> "no_permission:${blocker.code}"
    }

private fun DataResult<*>.resolvesCanonicalConversationAccess(): Boolean =
    this is DataResult.Content || (this is DataResult.Empty && reason == EmptyReason.NoMessages)

internal fun KuiklyThreadScreenPayload.withTaskSliceFrom(patch: KuiklyThreadScreenPayload): KuiklyThreadScreenPayload =
    copy(tasks = patch.tasks)

internal fun KuiklyThreadScreenPayload.withResolvedReplyThreadIdentityFrom(
    patch: KuiklyThreadScreenPayload
): KuiklyThreadScreenPayload =
    if (patch.hasResolvedReplyThreadStreamFrom(this)) {
        copy(
            instanceId = patch.instanceId,
            title = patch.title,
            subtitle = patch.subtitle,
            sourceThreadId = patch.sourceThreadId,
            streamChannelId = patch.streamChannelId,
            parentChannelId = patch.parentChannelId,
            parentMessageId = patch.parentMessageId,
            composer =
                composer.copy(
                    streamChannelId =
                        patch.composer.streamChannelId?.takeIf { it.isNotBlank() }
                            ?: patch.streamChannelId?.takeIf { it.isNotBlank() }
                            ?: patch.sourceThreadId?.takeIf { it.isNotBlank() }
                )
        )
    } else {
        this
    }

internal fun KuiklyThreadScreenPayload.withMembersPatchFrom(patch: KuiklyThreadScreenPayload): KuiklyThreadScreenPayload =
    copy(
        membersCount = patch.membersCount,
        members = patch.members,
        memberCandidates = patch.memberCandidates,
        canAddMembers = patch.canAddMembers,
        addMemberStatus = patch.addMemberStatus,
        addMemberErrorCode = patch.addMemberErrorCode,
        removeMemberStatus = patch.removeMemberStatus,
        removingMemberId = patch.removingMemberId,
        removeMemberErrorCode = patch.removeMemberErrorCode
    )

internal fun KuiklyThreadScreenPayload.withMessageReaction(
    messageId: String,
    emoji: String,
    selected: Boolean
): KuiklyThreadScreenPayload {
    val cleanMessageId = messageId.trim()
    val cleanEmoji = emoji.trim()
    if (cleanMessageId.isBlank() || cleanEmoji.isBlank()) return this
    return copy(
        messages = messages.map { message ->
            if (message.id == cleanMessageId) message.withReactionSelection(cleanEmoji, selected) else message
        },
        originMessage = originMessage?.let { message ->
            if (message.id == cleanMessageId) message.withReactionSelection(cleanEmoji, selected) else message
        }
    )
}

internal fun KuiklyThreadScreenPayload.withMessageReactions(
    messageId: String,
    reactions: List<KuiklyThreadReactionItem>
): KuiklyThreadScreenPayload {
    val cleanMessageId = messageId.trim()
    if (cleanMessageId.isBlank()) return this
    return copy(
        messages = messages.map { message ->
            if (message.id == cleanMessageId) message.copy(reactions = reactions.orderedLike(message.reactions)) else message
        },
        originMessage = originMessage?.let { message ->
            if (message.id == cleanMessageId) message.copy(reactions = reactions.orderedLike(message.reactions)) else message
        }
    )
}

internal fun KuiklyThreadScreenPayload.withMessageSaved(
    messageId: String,
    saved: Boolean
): KuiklyThreadScreenPayload {
    val cleanMessageId = messageId.trim()
    if (cleanMessageId.isBlank()) return this
    return copy(
        messages = messages.map { message ->
            if (message.id == cleanMessageId) message.copy(isSaved = saved) else message
        },
        originMessage = originMessage?.let { message ->
            if (message.id == cleanMessageId) message.copy(isSaved = saved) else message
        }
    )
}

internal fun KuiklyThreadScreenPayload.withSavedMessageIds(savedIds: Set<String>): KuiklyThreadScreenPayload =
    copy(
        messages = messages.map { message -> message.copy(isSaved = message.id in savedIds) },
        originMessage = originMessage?.let { message -> message.copy(isSaved = message.id in savedIds) }
    )

// task #569 follow-up: id -> display name over the thread's known members
// (roster + mention candidates) so reaction-tooltip name resolution can backfill
// reactor ids the mutation/realtime payload didn't name. The current user is in
// this roster, so their own just-added reaction resolves without a server round.
private fun KuiklyThreadScreenPayload.threadReactorNameById(): Map<String, String> {
    val byId = LinkedHashMap<String, String>()
    (members + memberCandidates).forEach { member ->
        val id = member.id.trim()
        val name = member.displayName.trim()
        if (id.isNotBlank() && name.isNotBlank() && !byId.containsKey(id)) byId[id] = name
    }
    return byId
}

private fun KuiklyThreadScreenPayload.withMessageMutationSummary(
    summary: MessageMutationSummary,
    currentUserId: String?,
    currentUserName: String? = null
): KuiklyThreadScreenPayload {
    val messageId = summary.messageId.trim()
    if (messageId.isBlank()) return this
    val reactions = summary.reactions.toThreadReactionItems(currentUserId, threadReactorNameById(), currentUserName)
    return copy(
        messages = messages.map { message ->
            if (message.id == messageId) message.copy(reactions = reactions.orderedLike(message.reactions)) else message
        },
        originMessage = originMessage?.let { message ->
            if (message.id == messageId) message.copy(reactions = reactions.orderedLike(message.reactions)) else message
        }
    )
}

private fun KuiklyThreadScreenPayload.withReplyThreadRead(
    parentMessageId: String,
    threadChannelId: String?
): KuiklyThreadScreenPayload {
    val cleanParentMessageId = parentMessageId.trim()
    if (cleanParentMessageId.isBlank()) return this
    val cleanThreadChannelId = threadChannelId?.trim()?.takeIf { it.isNotBlank() }
    fun KuiklyThreadMessageItem.clearIfParent(): KuiklyThreadMessageItem {
        if (id != cleanParentMessageId) return this
        return copy(
            threadChannelId = cleanThreadChannelId ?: this.threadChannelId,
            unreadReplyCount = 0,
            firstUnreadReplyMessageId = null
        )
    }
    return copy(
        messages = messages.map { it.clearIfParent() },
        originMessage = originMessage?.clearIfParent()
    )
}

private fun KuiklyThreadScreenPayload.withMessageThreadFollowed(
    parentMessageId: String,
    threadChannelId: String?,
    followed: Boolean,
    replyCount: Int
): KuiklyThreadScreenPayload {
    val cleanParentMessageId = parentMessageId.trim()
    if (cleanParentMessageId.isBlank()) return this
    val cleanThreadChannelId = threadChannelId?.trim()?.takeIf { it.isNotBlank() }
    fun KuiklyThreadMessageItem.updateIfParent(): KuiklyThreadMessageItem {
        if (id != cleanParentMessageId) return this
        return copy(
            threadChannelId = cleanThreadChannelId ?: this.threadChannelId,
            replyCount = maxOf(this.replyCount, replyCount),
            isThreadFollowed = followed
        )
    }
    return copy(
        messages = messages.map { it.updateIfParent() },
        originMessage = originMessage?.updateIfParent()
    )
}

private fun KuiklyThreadMessageItem.withReactionSelection(emoji: String, selected: Boolean): KuiklyThreadMessageItem {
    var matched = false
    val updated =
        reactions.mapNotNull { reaction ->
            if (reaction.emoji != emoji) return@mapNotNull reaction
            matched = true
            val delta =
                when {
                    selected && !reaction.selected -> 1
                    !selected && reaction.selected -> -1
                    else -> 0
                }
            val nextCount = (reaction.count + delta).coerceAtLeast(0)
            if (nextCount > 0) {
                reaction.copy(count = nextCount, selected = selected)
            } else {
                null
            }
        }
    val nextReactions =
        if (!matched && selected) {
            updated + KuiklyThreadReactionItem(emoji = emoji, count = 1, selected = true)
        } else {
            updated
        }
    return copy(reactions = nextReactions)
}

// task #569 follow-up: the who-reacted tooltip reads reactorNames off the
// KuiklyThreadReactionItem. The server's initial GET already carries names
// (messageService enrichWithSenderNames), but the addReaction mutation summary
// and any reduced payload can arrive with reactorNames empty while reactorIds
// is populated — so right after you tap a reaction the tooltip degraded to the
// bare "1 person" count. Resolve display names on-device (no server change):
// keep any server-provided names, then resolve remaining reactor ids: the
// current user's own id from the session identity (authoritative, always known
// regardless of roster completeness) and everyone else from the thread member
// directory. So a just-added self-reaction shows the name immediately, and
// other reactors resolve whenever they're in the roster. Degrades to the honest
// count when neither a name nor a resolvable id is available.
internal fun resolveThreadReactorNames(
    reactorIds: List<String>,
    reactorNames: List<String>,
    memberNameById: Map<String, String>,
    currentUserId: String? = null,
    currentUserName: String? = null
): List<String> {
    val cleanSelfId = currentUserId?.trim()?.takeIf { it.isNotBlank() }
    val cleanSelfName = currentUserName?.trim()?.takeIf { it.isNotBlank() }
    // Identity is the source of truth: dedup by reactor id (two distinct people
    // who happen to share a display name each count), then map each id to a name
    // — self from the session, others from the index-aligned server name or the
    // member directory. Unresolvable ids are dropped; the tooltip label's "+K"
    // remainder (max(count, names)) still reflects the true reactor count.
    val resolved = LinkedHashSet<String>()
    if (reactorIds.isEmpty()) {
        // Older/edge payload with names but no ids to anchor to — dedup by name.
        reactorNames.forEach { name -> name.trim().takeIf { it.isNotBlank() }?.let(resolved::add) }
        return resolved.toList()
    }
    val seenIds = HashSet<String>()
    reactorIds.forEachIndexed { index, rawId ->
        val id = rawId.trim()
        if (id.isBlank() || !seenIds.add(id)) return@forEachIndexed
        val name =
            if (id == cleanSelfId && cleanSelfName != null) {
                cleanSelfName
            } else {
                reactorNames.getOrNull(index)?.trim()?.takeIf { it.isNotBlank() }
                    ?: memberNameById[id]?.trim()?.takeIf { it.isNotBlank() }
            }
        name?.let(resolved::add)
    }
    return resolved.toList()
}

private fun List<MessageReactionSummary>.toThreadReactionItems(
    currentUserId: String?,
    memberNameById: Map<String, String> = emptyMap(),
    currentUserName: String? = null
): List<KuiklyThreadReactionItem> =
    mapNotNull { reaction ->
        val emoji = reaction.emoji.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val count = reaction.count.takeIf { it > 0 } ?: return@mapNotNull null
        KuiklyThreadReactionItem(
            emoji = emoji,
            count = count,
            selected = currentUserId != null && currentUserId in reaction.reactorIds,
            reactorIds = reaction.reactorIds,
            reactorNames =
                if (memberNameById.isEmpty() && currentUserName.isNullOrBlank()) {
                    reaction.reactorNames
                } else {
                    resolveThreadReactorNames(
                        reaction.reactorIds,
                        reaction.reactorNames,
                        memberNameById,
                        currentUserId,
                        currentUserName
                    )
                }
        )
    }

private val PendingReactionRealtimeGuard.key: String
    get() = "$messageId\u0000$emoji"

private fun List<KuiklyThreadReactionItem>.selectedForEmoji(emoji: String): Boolean =
    any { reaction -> reaction.emoji == emoji && reaction.selected && reaction.count > 0 }

private fun List<MessageReaction>?.toRealtimeThreadReactionItems(
    currentUserId: String?,
    memberNameById: Map<String, String> = emptyMap()
): List<KuiklyThreadReactionItem>? =
    this?.mapNotNull { reaction ->
        val emoji = reaction.emoji.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val count = reaction.count.takeIf { it > 0 } ?: return@mapNotNull null
        KuiklyThreadReactionItem(
            emoji = emoji,
            count = count,
            selected = currentUserId != null && currentUserId in reaction.reactorIds,
            reactorIds = reaction.reactorIds,
            reactorNames =
                if (memberNameById.isEmpty()) {
                    reaction.reactorNames
                } else {
                    resolveThreadReactorNames(reaction.reactorIds, reaction.reactorNames, memberNameById)
                }
        )
    }

private fun Message.matchesSharedThread(
    payload: KuiklyThreadScreenPayload,
    activeReplyParentMessageId: MessageId?
): Boolean {
    val replyThreadContentChannelId = payload.replyThreadContentChannelId()
    if (replyThreadContentChannelId != null) {
        return belongsToReplyThreadContent(replyThreadContentChannelId)
    }
    val selectedThreadId = payload.cacheThreadId()
    val originParentMessageId = payload.originParentMessageId?.takeIf { it.isNotBlank() }?.let(::MessageId)
    return selectedThreadId?.let { selectedId ->
        threadId.value == selectedId ||
            channelId?.value == selectedId ||
            threadChannelId?.value == selectedId
    } == true ||
        originParentMessageId?.let { parentMessageId == it } == true ||
        activeReplyParentMessageId?.let { parentMessageId == it } == true
}

private fun KuiklyThreadMessageItem.mergeRealtimeUpdate(
    update: KuiklyThreadMessageItem,
    reactionUpdate: List<KuiklyThreadReactionItem>? = null,
    preserveExistingReactions: Boolean = false
): KuiklyThreadMessageItem {
    val currentAuthorId = authorId.resolvedRealtimeThreadAuthorIdOrNull()
    val incomingAuthorId = update.authorId.resolvedRealtimeThreadAuthorIdOrNull()
    // A message author is immutable. Sparse realtime patches have historically
    // carried an event actor id without matching author presentation fields;
    // accepting that id would leave the old name/avatar clickable as another
    // member. Only hydrate an unresolved author or refresh the same identity.
    val acceptsIncomingAuthor =
        incomingAuthorId != null && (currentAuthorId == null || currentAuthorId == incomingAuthorId)
    return copy(
        authorId = if (acceptsIncomingAuthor) update.authorId else authorId,
        authorName =
            if (acceptsIncomingAuthor) {
                update.authorName.takeUnless { it.isBlank() || it == update.authorId } ?: authorName
            } else {
                authorName
            },
        isOwn = if (acceptsIncomingAuthor) update.isOwn else isOwn,
        avatarLabel = if (acceptsIncomingAuthor) update.avatarLabel.ifBlank { avatarLabel } else avatarLabel,
        avatarWireValue = if (acceptsIncomingAuthor) update.avatarWireValue ?: avatarWireValue else avatarWireValue,
        // Creation time is immutable. Realtime message updates may carry a
        // later updatedAt-derived label, but must never rewrite the visible
        // timeline timestamp of an already loaded message.
        timestampLabel = timestampLabel.ifBlank { update.timestampLabel },
        createdAtEpochMillis = createdAtEpochMillis ?: update.createdAtEpochMillis,
        seq = update.seq ?: seq,
        messageType = update.messageType.ifBlank { messageType },
        systemEventType = update.systemEventType ?: systemEventType,
        metadata = update.metadata.ifEmpty { metadata },
        body = update.body.ifBlank { body },
        threadChannelId = update.threadChannelId ?: threadChannelId,
        replyCount = maxOf(replyCount, update.replyCount),
        unreadReplyCount = maxOf(unreadReplyCount, update.unreadReplyCount),
        firstUnreadReplyMessageId = update.firstUnreadReplyMessageId ?: firstUnreadReplyMessageId,
        isThreadFollowed = isThreadFollowed || update.isThreadFollowed,
        isSaved = isSaved || update.isSaved,
        taskId = update.taskId ?: taskId,
        taskNumber = update.taskNumber ?: taskNumber,
        taskStatus = update.taskStatus ?: taskStatus,
        taskAssigneeKind = update.taskAssigneeKind ?: taskAssigneeKind,
        taskAssigneeId = update.taskAssigneeId ?: taskAssigneeId,
        taskAssigneeName = update.taskAssigneeName ?: taskAssigneeName,
        reactions =
            when {
                preserveExistingReactions -> reactions
                reactionUpdate != null -> reactionUpdate.orderedLike(reactions)
                else -> update.reactions.ifEmpty { reactions }
            },
        attachments = mergeAttachmentCommentMetadata(update.attachments),
        commentRef = update.commentRef ?: commentRef
    )
}

private fun String.resolvedRealtimeThreadAuthorIdOrNull(): String? =
    trim().takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }

private fun KuiklyThreadMessageItem.mergeAttachmentCommentMetadata(
    updates: List<KuiklyThreadAttachmentItem>
): List<KuiklyThreadAttachmentItem> {
    if (updates.isEmpty()) return attachments
    if (attachments.isEmpty()) return updates
    val previousById = attachments.associateBy { it.id }
    return updates.map { update ->
        val previous = previousById[update.id]
        if (previous != null && update.commentCount == 0 && previous.commentCount > 0) {
            update.copy(commentCount = previous.commentCount)
        } else {
            update
        }
    }
}

private fun ThreadSnapshot.toKuiklyThreadPayload(
    base: KuiklyThreadScreenPayload,
    currentUserId: String?
): KuiklyThreadScreenPayload {
    val baseHasReplyThreadIdentity =
        base.surfaceKind == KuiklyThreadSurfaceKind.ReplyThread ||
            (
                base.parentChannelId?.isNotBlank() == true &&
                    (base.parentMessageId?.isNotBlank() == true || base.originParentMessageId?.isNotBlank() == true)
                )
    val baseTargetsParentConversation =
        !baseHasReplyThreadIdentity &&
            base.parentChannelId.isNullOrBlank() &&
            base.focusedMessageId?.isNotBlank() == true &&
            (
                base.surfaceKind == KuiklyThreadSurfaceKind.DirectMessage ||
                    base.surfaceKind == KuiklyThreadSurfaceKind.Channel
                )
    val routeIsReplyThread =
        baseHasReplyThreadIdentity ||
            (
                !baseTargetsParentConversation &&
                    conversation.kind == ConversationKind.Thread
                )
    val routeParentMessageId =
        base.parentMessageId?.takeIf { it.isNotBlank() }
            ?: base.originParentMessageId?.takeIf { it.isNotBlank() }
    val parentId =
        if (routeIsReplyThread) {
            routeParentMessageId
                ?: conversation.thread?.parentMessageId?.value
                ?: parentMessage?.id?.value
        } else {
            null
        }
    val resolvedParentMessage =
        parentMessage
            ?.takeIf { message -> parentId == null || message.id.value == parentId }
            ?.takeUnless { it.isSparseReplyOriginParentCandidate() }
            ?: parentId?.let { id ->
                messages.firstOrNull { message ->
                    message.id.value == id && !message.isSparseReplyOriginParentCandidate()
                }
            }
    val surfaceKind =
        when {
            conversation.kind == ConversationKind.DirectMessage -> KuiklyThreadSurfaceKind.DirectMessage
            routeIsReplyThread &&
                (resolvedParentMessage != null || !base.parentMessageId.isNullOrBlank() || !base.originParentMessageId.isNullOrBlank()) ->
                KuiklyThreadSurfaceKind.ReplyThread
            else -> base.surfaceKind
        }
    val channelKind =
        if (surfaceKind == KuiklyThreadSurfaceKind.Channel) {
            conversation.resolveThreadRouteIdentity().channelKind
                .withRouteChannelKindFallback(base.resolveThreadRouteIdentity().channelKind ?: base.channelKind)
                ?: base.channelKind
        } else {
            base.channelKind
        }
    val streamChannelId =
        base.streamChannelId
            ?: if (surfaceKind == KuiklyThreadSurfaceKind.ReplyThread) {
                conversation.thread?.streamChannelId?.value
            } else {
                conversation.channelId?.value ?: conversation.threadId.value
            }
    val parentChannelId =
        if (surfaceKind == KuiklyThreadSurfaceKind.ReplyThread) {
            base.parentChannelId ?: conversation.thread?.parentChannelId?.value
        } else {
            null
        }
    val parentMessageId =
        if (surfaceKind == KuiklyThreadSurfaceKind.ReplyThread) {
            base.parentMessageId ?: resolvedParentMessage?.id?.value ?: conversation.thread?.parentMessageId?.value
        } else {
            null
        }
    val parentSurfaceKind =
        if (surfaceKind == KuiklyThreadSurfaceKind.ReplyThread) {
            // Parent shape is immutable route intent. Snapshot content may omit it, but it must
            // never erase a valid launch identity before the directory projection renders the
            // header. Preserve malformed non-blank input too so display enrichment still rejects
            // it fail-closed instead of silently guessing Channel.
            base.parentSurfaceKind?.takeIf { it.isNotBlank() }
                ?: base.parentChannelKind.parsedThreadParentSurfaceKindOrNull()
                ?: conversation.threadParentSurfaceKind()
        } else {
            null
        }
    val parentChannelKind =
        if (surfaceKind == KuiklyThreadSurfaceKind.ReplyThread) {
            base.parentChannelKind.normalizedThreadChannelKind()
                ?: conversation.threadParentChannelKind()
        } else {
            null
        }
    val sourceThreadId =
        base.sourceThreadId
            ?: if (surfaceKind == KuiklyThreadSurfaceKind.ReplyThread) {
                conversation.thread?.streamChannelId?.value
            } else {
                conversation.channelId?.value ?: conversation.threadId.value
            }
    val replyThreadContentChannelId =
        base.copy(
            surfaceKind = surfaceKind,
            sourceThreadId = sourceThreadId,
            streamChannelId = streamChannelId
        ).replyThreadContentChannelId()
    val parentConversationMessagePatch =
        if (baseTargetsParentConversation) {
            parentMessage?.toKuiklyThreadMessageItem(currentUserId)
        } else {
            null
        }
    val timelineMessages =
        if (
            parentConversationMessagePatch != null &&
            messages.none { message -> message.id.value == parentConversationMessagePatch.id }
        ) {
            messages + listOfNotNull(parentMessage)
        } else {
            messages
        }
    val cachedMessages =
        timelineMessages
            .filterNot { message -> parentId != null && message.id.value == parentId }
            .filter { message ->
                replyThreadContentChannelId == null || message.belongsToReplyThreadContent(replyThreadContentChannelId)
            }
            .map { message ->
                val item = message.toKuiklyThreadMessageItem(currentUserId)
                if (parentConversationMessagePatch?.id == item.id) {
                    item.mergeRealtimeUpdate(parentConversationMessagePatch)
                } else {
                    item
                }
            }
            .sortedByThreadTimeline()
    val isDirectMessage = surfaceKind == KuiklyThreadSurfaceKind.DirectMessage
    val safeDirectMessagePeerName = conversation.safeDisplayNameOrNull().takeIf { isDirectMessage }
    val headerContext =
        SharedThreadHeaderContextStore.conversationContext(
            surfaceKind = surfaceKind,
            conversation = conversation,
            originMessage = resolvedParentMessage?.toKuiklyThreadMessageItem(currentUserId),
            parentMessageId = parentId,
            originPending =
                surfaceKind == KuiklyThreadSurfaceKind.ReplyThread &&
                    resolvedParentMessage == null &&
                    parentId != null &&
                    base.originStatus == "loading"
        )
    val resolvedComposer =
        if (surfaceKind == KuiklyThreadSurfaceKind.ReplyThread) {
            base.composer.copy(
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                mentionChannelId = parentChannelId,
                parentMessageId = parentMessageId,
                streamChannelId = streamChannelId
            )
        } else {
            base.composer
        }
    // Joint channels have task surfaces like any channel (server task surface mapping).
    val supportsChannelTasks = conversation.permissions.canCreateTask
    val resolvedCapabilityComposer =
        resolvedComposer.copy(
            showAsTaskToggle = resolvedComposer.showAsTaskToggle && supportsChannelTasks
        )
    return base.copy(
        title = headerContext.title,
        subtitle = headerContext.subtitle,
        status =
            if (cachedMessages.isEmpty() && headerContext.originMessage == null && tasks.isEmpty()) {
                "empty"
            } else {
                "content"
            },
        sourceThreadId = sourceThreadId,
        surfaceKind = surfaceKind,
        channelKind = channelKind ?: base.channelKind,
        streamChannelId = streamChannelId,
        parentChannelId = parentChannelId,
        parentMessageId = parentMessageId,
        parentSurfaceKind = parentSurfaceKind,
        parentChannelKind = parentChannelKind,
        memberScopedMentions = base.memberScopedMentions || conversation.usesMemberScopedMentions(),
        messages = cachedMessages,
        originMessage = headerContext.originMessage,
        originPreview = headerContext.originPreview,
        originParentMessageId = headerContext.originParentMessageId,
        focusedMessageId = base.focusedMessageId,
        focusedMessageRequestNonce = base.focusedMessageRequestNonce,
        originStatus = headerContext.originStatus,
        originErrorCode = headerContext.originErrorCode,
        emptyTitle =
            if (surfaceKind == KuiklyThreadSurfaceKind.ReplyThread) {
                ThreadReplyEmptyTitle
            } else if (
                cachedMessages.isEmpty() && headerContext.originMessage == null && tasks.isEmpty() &&
                    base.emptyTitle == ThreadRouteLoadingPlaceholderTitle
            ) {
                // task #326: the route placeholder's "Loading..." must not survive into a
                // resolved empty channel frame, or the empty state shows "加载中" forever.
                ThreadEmptyConversationTitle
            } else {
                base.emptyTitle
            },
        emptyDetail =
            if (surfaceKind == KuiklyThreadSurfaceKind.ReplyThread) {
                ThreadReplyEmptyDetail
            } else if (
                cachedMessages.isEmpty() && headerContext.originMessage == null && tasks.isEmpty() &&
                    base.emptyTitle == ThreadRouteLoadingPlaceholderTitle
            ) {
                ThreadEmptyConversationDetail
            } else {
                base.emptyDetail
            },
        composer = resolvedCapabilityComposer,
        showTaskOpenActions = base.showTaskOpenActions && supportsChannelTasks,
        showTaskStatusActions = base.showTaskStatusActions && supportsChannelTasks,
        tasks = tasks.map { task -> task.toKuiklyThreadTaskItem() },
        canLoadMoreBefore = canLoadMoreBefore,
        canLoadMoreAfter = canLoadMoreAfter,
        joined = conversation.joined,
        canSend = conversation.permissions.canSend,
        membersCount = conversation.memberCount ?: base.membersCount,
        errorCode = null,
        dmPeerName = safeDirectMessagePeerName,
        dmPeerAvatarUrl = conversation.directMessagePeerAvatarWireValue?.takeIf { it.isNotBlank() },
        dmPeerAvatarLabel = safeDirectMessagePeerName?.take(2)?.uppercase(),
        activityMuted = conversation.activityMuted,
        muteFromSeq = conversation.muteFromSeq,
        activityMuteSupported = conversation.activityMuteSupported,
        replySummariesAuthority = replySummariesAuthority,
    )
        .withOriginReplyCountAtLeastLoadedReplies()
        .withReplySummaries(replySummaries, replySummariesAuthority)
}

private fun Message.isSparseReplyOriginParentCandidate(): Boolean =
    body.isBlank() && attachments.isEmpty()

private fun String?.withRouteChannelKindFallback(routeKind: String?): String? {
    val normalized = normalizedThreadChannelKind()
    val route = routeKind.normalizedThreadChannelKind()
    return if (normalized == KuiklyThreadSurfaceKind.Channel && route != null && route != KuiklyThreadSurfaceKind.Channel) {
        route
    } else {
        normalized ?: route
    }
}

private fun ThreadSnapshot.matchesFocusedThreadRoute(
    routeThreadId: ThreadId,
    focusedMessageId: String
): Boolean {
    val routeId = routeThreadId.value.takeIf { it.isNotBlank() } ?: return false
    val focusedMessage =
        messages.firstOrNull { message -> message.id.value == focusedMessageId }
            ?: parentMessage?.takeIf { message -> message.id.value == focusedMessageId }
            ?: return false
    return focusedMessage.channelId?.value == routeId || focusedMessage.threadId.value == routeId
}

private fun KuiklyThreadScreenPayload.focusedMessageContextChannelIdOrNull(): ChannelId? {
    if (surfaceKind != KuiklyThreadSurfaceKind.ReplyThread) return null
    return listOfNotNull(streamChannelId, sourceThreadId)
        .firstNotNullOfOrNull { value -> value.takeIf { it.isNotBlank() }?.let(::ChannelId) }
}

private fun ThreadSnapshot.withConversationAccessFallback(
    fallback: ConversationAccessCandidate?
): ThreadSnapshot {
    fallback ?: return this
    val current = conversation
    if (current.kind == ConversationKind.DirectMessage) return this
    if (fallback.joined || !current.matchesHeaderFallbackId(fallback.ids)) return this
    val nextConversation =
        current.copy(
            joined = false,
            permissions = fallback.permissions
        )
    return if (nextConversation == current) this else copy(conversation = nextConversation)
}

private fun KuiklyThreadScreenPayload.conversationAccessFallbackIds(): List<String> =
    if (surfaceKind == KuiklyThreadSurfaceKind.ReplyThread) {
        listOfNotNull(replyParentAccessIdentityOrNull()?.first)
    } else {
        listOfNotNull(sourceThreadId, streamChannelId, parentChannelId)
            .mapNotNull { id -> id.takeIf { it.isNotBlank() } }
            .distinct()
    }

private fun KuiklyThreadScreenPayload.acceptsInitialConversationAccessCandidate(
    candidate: ConversationAccessCandidate
): Boolean {
    if (surfaceKind != KuiklyThreadSurfaceKind.ReplyThread) {
        return acceptsConversationAccessCandidate(candidate)
    }
    val (parentId, parentKind) = replyParentAccessIdentityOrNull() ?: return false
    if (parentId !in candidate.ids) return false
    return candidate.conversationKind == parentKind
}

private fun KuiklyThreadScreenPayload.replyParentAccessIdentityOrNull(): Pair<String, ConversationKind>? {
    val routeIdentity = resolveThreadRouteIdentity()
    if (
        routeIdentity.surfaceKind != KuiklyThreadSurfaceKind.ReplyThread ||
        !routeIdentity.hasCompleteReplyIdentity
    ) {
        return null
    }
    val parentId = parentChannelId?.takeIf { it.isNotBlank() } ?: return null
    val parentMessage =
        parentMessageId?.takeIf { it.isNotBlank() }
            ?: originParentMessageId?.takeIf { it.isNotBlank() }
            ?: return null
    if (routeIdentity.parentKey != "$parentId:$parentMessage") return null
    return parentId to (routeIdentity.parentConversationKind ?: return null)
}

private fun ConversationSummary.matchesHeaderFallbackId(ids: Set<String>): Boolean =
    headerFallbackIds().any { it in ids }

private fun ConversationSummary.headerFallbackIds(): Set<String> =
    listOfNotNull(
        threadId.value,
        channelId?.value,
        thread?.streamChannelId?.value
    ).filter { it.isNotBlank() }.toSet()

internal fun KuiklyThreadMessageItem.toMessage(
    threadId: ThreadId,
    channelId: ChannelId,
    currentUserId: String? = null,
    currentUserName: String? = null,
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
): Message? {
    val authorMemberKind =
        authorKind.strictMemberKindOrNull(
            source = "thread_item_author",
            diagnostics = diagnostics,
            contract = MemberKindWireContract.ProjectedMessageAuthor,
        ) ?: return null
    if (authorMemberKind == MemberKind.External && externalAuthor == null) {
        diagnostics("projection_value_rejected source=thread_item_author field=externalAuthor value=missing messageId=$id")
        return null
    }
    val resolvedMessageType = messageType.strictMessageTypeOrNull("thread_item_message", diagnostics) ?: return null
    val hasTaskProjection = taskId != null || taskNumber != null || taskStatus != null
    val resolvedTaskStatus =
        if (hasTaskProjection) taskStatus.strictTaskStatusOrNull("thread_item_task", diagnostics) else null
    val taskSummary =
        if (hasTaskProjection && resolvedTaskStatus != null) {
            TaskSummary(
                id = TaskId(taskId ?: "task-${taskNumber ?: id}"),
                title = body.ifBlank { taskNumber?.let { "Task #$it" } ?: "Task" },
                status = resolvedTaskStatus,
                taskNumber = taskNumber,
                messageId = MessageId(id),
                channelId = channelId,
                threadId = threadId,
                assignee = taskAssigneeId?.takeIf { it.isNotBlank() }?.let { id ->
                    val name = taskAssigneeName?.takeIf { it.isDisplaySafeTaskAssigneeName(id) } ?: id
                    taskAssigneeKind.strictMemberKindOrNull(
                        source = "thread_item_task_assignee",
                        diagnostics = diagnostics,
                        contract = MemberKindWireContract.Projected,
                    )
                        ?.let { kind ->
                            MemberProfile(
                                id = MemberId(id),
                                displayName = name,
                                handle = name.removePrefix("@"),
                                kind = kind,
                            )
                        }
                },
                updatedAtLabel = timestampLabel
            )
        } else {
            null
        }
    return Message(
        id = MessageId(id),
        threadId = threadId,
        channelId = channelId,
        threadChannelId = threadChannelId?.let(::ChannelId),
        author = toMemberProfile(authorMemberKind),
        seq = seq,
        timestampLabel = timestampLabel,
        createdAtEpochMillis = createdAtEpochMillis,
        body = body,
        messageType = resolvedMessageType,
        systemEventType = systemEventType,
        metadata = metadata.withLocalSendMetadata(pendingSendContext, pendingSendErrorCode, pendingSendErrorMessage),
        attachments = attachments.map { attachment -> attachment.toAttachment() },
        deliveryState = deliveryState.toMessageDeliveryState(),
        task = taskSummary,
        externalAuthor = if (authorMemberKind == MemberKind.External) externalAuthor else null,
        replyCount = replyCount,
        unreadReplyCount = unreadReplyCount,
        firstUnreadReplyMessageId = firstUnreadReplyMessageId?.let(::MessageId),
        isThreadFollowed = isThreadFollowed,
        isSaved = isSaved,
        reactions = reactions.toMessageReactions(currentUserId, currentUserName),
        commentRef = commentRef?.toMessageCommentRef()
    )
}

private fun List<KuiklyThreadReactionItem>.toMessageReactions(
    currentUserId: String?,
    currentUserName: String?
): List<MessageReaction>? =
    mapNotNull { reaction ->
        val emoji = reaction.emoji.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val count = reaction.count.takeIf { it > 0 } ?: return@mapNotNull null
        val reactorIds =
            reaction.reactorIds
                .plus(if (reaction.selected && currentUserId != null) listOf(currentUserId) else emptyList())
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
        val reactorNames =
            reaction.reactorNames
                .plus(if (reaction.selected && !currentUserName.isNullOrBlank()) listOf(currentUserName) else emptyList())
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
        MessageReaction(
            emoji = emoji,
            count = count,
            reactorIds = reactorIds,
            reactorNames = reactorNames
        )
    }.takeIf { it.isNotEmpty() }

private fun KuiklyThreadMessageItem.toMemberProfile(kind: MemberKind): MemberProfile {
    val cleanAuthorDeletedAt = authorDeletedAt?.takeIf { it.isNotBlank() }
    val cleanMembershipStatus =
        senderMembershipStatus?.takeIf { it.isNotBlank() }
            ?: if (authorKind.equals("agent", ignoreCase = true) && cleanAuthorDeletedAt != null) "deleted" else null
    val effectiveDescription =
        if (kind == MemberKind.External) {
            externalAuthor?.providerLabel() ?: authorDescription
        } else {
            authorDescription
        }
    val effectiveAvatarUrl =
        if (kind == MemberKind.External) {
            externalAuthor?.avatarUrl ?: avatarWireValue?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        } else {
            avatarWireValue?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        }
    return MemberProfile(
        id = MemberId(authorId),
        displayName = authorName,
        handle = authorName.removePrefix("@"),
        kind = kind,
        description = effectiveDescription,
        avatarUrl = effectiveAvatarUrl,
        avatar = AvatarReference.fromWireValue(avatarWireValue),
        avatarLabel = avatarLabel.ifBlank { authorName.take(1).uppercase() },
        presence = Presence.Offline,
        membershipStatus = cleanMembershipStatus,
        agentDeletedAt = cleanAuthorDeletedAt
    )
}

private fun KuiklyThreadAttachmentItem.toAttachment(): Attachment =
    Attachment(
        id = AttachmentId(id),
        name = name,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        sizeLabel = sizeLabel,
        thumbnailUrl = thumbnailUrl,
        commentCount = commentCount
    )

internal fun StoredAuthSession.currentServerSlug(): String? =
    selectedServerSlug?.takeIf { it.isNotBlank() }
        ?: currentServerId
            ?.let { selectedId -> servers.firstOrNull { it.id == selectedId }?.slug }
            ?.takeIf { it.isNotBlank() }
        ?: servers.firstOrNull()?.slug?.takeIf { it.isNotBlank() }

private fun KuiklyThreadMessageCommentRef.toMessageCommentRef(): MessageCommentRef? {
    val cleanAttachmentId = attachmentId.takeIf { it.isNotBlank() } ?: return null
    val cleanFilename = filename.takeIf { it.isNotBlank() } ?: return null
    return MessageCommentRef(
        attachmentId = AttachmentId(cleanAttachmentId),
        filename = cleanFilename,
        hostMessageId = hostMessageId?.takeIf { it.isNotBlank() }?.let(::MessageId),
        hostSource = hostSource?.toMessageCommentHostSource(),
        anchorLabel = anchorLabel?.takeIf { it.isNotBlank() }
    )
}

private fun KuiklyThreadMessageCommentHostSource.toMessageCommentHostSource(): MessageCommentHostSource? {
    val cleanType = type.commentHostTypeOrNull() ?: return null
    val cleanRouteKind = routeKind.commentHostRouteKindOrNull() ?: return null
    val cleanChannelId = channelId.takeIf { it.isNotBlank() } ?: return null
    return MessageCommentHostSource(
        type = cleanType,
        routeKind = cleanRouteKind,
        channelId = ChannelId(cleanChannelId),
        parentMessageId = parentMessageId?.takeIf { it.isNotBlank() }?.let(::MessageId),
        threadChannelId = threadChannelId?.takeIf { it.isNotBlank() }?.let(::ChannelId),
        rootThreadChannelId = rootThreadChannelId?.takeIf { it.isNotBlank() }?.let(::ChannelId)
    )
}

internal fun Message.toKuiklyThreadMessageItem(currentUserId: String?): KuiklyThreadMessageItem {
    val pendingSendContext = metadata.localSendContextFromMetadata()
    val resolvedAvatar =
        resolveAvatarSlot(
            kind = author.kind,
            id = author.id.value,
            avatarWireValue = author.avatar?.wireValue,
            avatarUrl = author.avatarUrl
        )
    return KuiklyThreadMessageItem(
        id = id.value,
        authorId = author.id.value,
        authorName = author.displayName,
        authorHandle = author.handle,
        authorDescription =
            author.description?.takeIf { it.isNotBlank() }
                ?: author.roleDescription?.takeIf { it.isNotBlank() },
        authorKind =
            when (author.kind) {
                MemberKind.Agent -> "agent"
                MemberKind.System -> "system"
                MemberKind.External -> "external"
                else -> "human"
            },
        isOwn = currentUserId != null && author.id.value == currentUserId,
        avatarLabel = author.avatarLabel,
        avatarWireValue = resolvedAvatar.effectiveAvatarWireValue,
        externalAuthor = externalAuthor,
        senderMembershipStatus =
            senderMembershipStatus?.takeIf { it.isNotBlank() }
                ?: author.membershipStatus?.takeIf { it.isNotBlank() },
        authorDeletedAt = author.agentDeletedAt?.takeIf { it.isNotBlank() },
        seq = seq,
        timestampLabel = timestampLabel,
        createdAtEpochMillis = createdAtEpochMillis,
        messageType = if (messageType == MessageType.System) "System" else "Chat",
        systemEventType = systemEventType,
        metadata = metadata.withoutLocalSendMetadata(),
        body = body,
        randomId = randomId?.takeIf { it.isNotBlank() } ?: pendingSendContext?.randomId?.takeIf { it.isNotBlank() },
        threadChannelId = threadChannelId?.value,
        replyCount = replyCount,
        unreadReplyCount = unreadReplyCount,
        firstUnreadReplyMessageId = firstUnreadReplyMessageId?.value,
        isThreadFollowed = isThreadFollowed,
        isSaved = isSaved,
        taskId = task?.id?.value,
        taskNumber = task?.taskNumber,
        taskStatus = task?.status?.toWireValue(),
        taskAssigneeKind = task?.assignee?.kind?.memberKindWireValue(),
        taskAssigneeId = task?.assignee?.id?.value,
        taskAssigneeName = task?.assignee?.displayName,
        deliveryState = deliveryState.toKuiklyThreadDeliveryState(),
        attachments = attachments.map { attachment ->
            KuiklyThreadAttachmentItem(
                id = attachment.id.value,
                name = attachment.name,
                mimeType = attachment.mimeType,
                sizeBytes = attachment.sizeBytes,
                sizeLabel = attachment.sizeLabel,
                thumbnailUrl = attachment.thumbnailUrl,
                commentCount = attachment.commentCount
            )
        },
        reactions = reactions.toRealtimeThreadReactionItems(currentUserId).orEmpty(),
        commentRef = commentRef?.toKuiklyThreadCommentRef(),
        forwardBundle = forwardBundle,
        pendingSendContext = pendingSendContext,
        pendingSendErrorCode = metadata[ThreadLocalSendErrorMetadataKey]?.takeIf { it.isNotBlank() },
        pendingSendErrorMessage = metadata[ThreadLocalSendErrorMessageMetadataKey]?.takeIf { it.isNotBlank() }
    )
}

private const val ThreadLocalSendContextMetadataKey = "localSend.context"
private const val ThreadLocalSendErrorMetadataKey = "localSend.errorCode"
private const val ThreadLocalSendErrorMessageMetadataKey = "localSend.errorMessage"

private val ThreadLocalSendMetadataJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

private fun Map<String, String>.withLocalSendMetadata(
    context: KuiklyThreadPendingSendContext?,
    errorCode: String?,
    errorMessage: String?
): Map<String, String> {
    val metadata = withoutLocalSendMetadata().toMutableMap()
    if (context != null) {
        metadata[ThreadLocalSendContextMetadataKey] = ThreadLocalSendMetadataJson.encodeToString(context)
    }
    if (!errorCode.isNullOrBlank()) {
        metadata[ThreadLocalSendErrorMetadataKey] = errorCode
    }
    if (!errorMessage.isNullOrBlank()) {
        metadata[ThreadLocalSendErrorMessageMetadataKey] = errorMessage
    }
    return metadata
}

private fun Map<String, String>.withoutLocalSendMetadata(): Map<String, String> =
    filterKeys { key ->
        key != ThreadLocalSendContextMetadataKey &&
            key != ThreadLocalSendErrorMetadataKey &&
            key != ThreadLocalSendErrorMessageMetadataKey
    }

private fun Map<String, String>.localSendContextFromMetadata(): KuiklyThreadPendingSendContext? =
    get(ThreadLocalSendContextMetadataKey)
        ?.let { value ->
            runCatching {
                ThreadLocalSendMetadataJson.decodeFromString<KuiklyThreadPendingSendContext>(value)
            }.getOrNull()
        }

private fun String.toMessageDeliveryState(): MessageDeliveryState =
    when (trim().lowercase()) {
        ThreadMessageDeliverySending -> MessageDeliveryState.Sending
        ThreadMessageDeliveryFailed -> MessageDeliveryState.Failed
        else -> MessageDeliveryState.Sent
    }

private fun MessageDeliveryState.toKuiklyThreadDeliveryState(): String =
    when (this) {
        MessageDeliveryState.Sending -> ThreadMessageDeliverySending
        MessageDeliveryState.Failed -> ThreadMessageDeliveryFailed
        MessageDeliveryState.Sent -> ThreadMessageDeliverySent
    }

private fun MessageCommentRef.toKuiklyThreadCommentRef(): KuiklyThreadMessageCommentRef =
    KuiklyThreadMessageCommentRef(
        attachmentId = attachmentId.value,
        filename = filename,
        hostMessageId = hostMessageId?.value,
        hostSource = hostSource?.toKuiklyThreadCommentHostSource(),
        anchorLabel = anchorLabel
    )

private fun MessageCommentHostSource.toKuiklyThreadCommentHostSource(): KuiklyThreadMessageCommentHostSource =
    KuiklyThreadMessageCommentHostSource(
        type = type,
        routeKind = routeKind,
        channelId = channelId.value,
        parentMessageId = parentMessageId?.value,
        threadChannelId = threadChannelId?.value,
        rootThreadChannelId = rootThreadChannelId?.value
    )

private fun TaskSummary.toKuiklyThreadTaskItem(): KuiklyThreadTaskItem =
    KuiklyThreadTaskItem(
        id = id.value,
        title = title,
        status = status.toWireValue(),
        taskNumber = taskNumber,
        messageId = messageId?.value,
        assigneeId = assignee?.id?.value,
        assigneeName = assignee?.displayName,
        updatedAtLabel = updatedAtLabel
    )

private fun Throwable.safeThreadDiagnosticsType(): String =
    this::class.simpleName?.takeIf { it.isNotBlank() } ?: "Throwable"
