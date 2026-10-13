package build.raft.app.network.sync

import build.raft.app.canonical.message.CanonicalConversationContextWire
import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.canonical.message.MessageAggregateField
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** Full schema-v6 is a separate apply gate; the V1 persisted envelope remains permanently ineligible. */
const val CANONICAL_MESSAGE_FULL_V2_SOLE_APPLY_ELIGIBLE: Boolean = true

enum class CanonicalMessageV2CanaryMode {
    Off,
    ShadowOnly,
    Activating,
    SoleApply,
    Broken,
}

sealed interface CanonicalMessageV2CanaryOutcome {
    /** This event remains on the existing T3-shadow + legacy projection path. */
    data object NotSelected : CanonicalMessageV2CanaryOutcome

    /** The V2 commit completed and the caller may project the event after a fresh grant check. */
    data object Committed : CanonicalMessageV2CanaryOutcome

    /** The selected scope stays dark; no legacy or final sink may observe this event. */
    data class Held(val diagnostic: String) : CanonicalMessageV2CanaryOutcome
}

data class CanonicalMessageV2SnapshotRequest(
    val scope: SyncScopeKey,
    val targetFactId: String,
)

sealed interface CanonicalMessageV2SnapshotLoadResult {
    data class Loaded(val frame: CanonicalMessageV2SnapshotFrame) : CanonicalMessageV2SnapshotLoadResult
    data class Unavailable(val diagnostic: String) : CanonicalMessageV2SnapshotLoadResult
}

fun interface CanonicalMessageV2SnapshotLoader {
    suspend fun load(request: CanonicalMessageV2SnapshotRequest): CanonicalMessageV2SnapshotLoadResult
}

private data class CanonicalMessageV2CanaryScopeState(
    var initialized: Boolean = false,
    var mode: CanonicalMessageV2CanaryMode = CanonicalMessageV2CanaryMode.Activating,
    var cursor: PersistedCursor? = null,
    /**
     * Task #167: the realtime transport epoch this scope last committed under. Frames delivered while the
     * socket was down never reach the writer, so a frame from a different epoch must not be appended to
     * this window; it re-snapshots first. Null until the first commit.
     */
    var committedTransportEpoch: Long? = null,
    /** A window restored from disk has no known epoch: frames may have been missed while the process was dead. */
    var restoredWithoutEpoch: Boolean = false,
) {
    fun appendsContinuously(currentTransportEpoch: Long?): Boolean =
        !restoredWithoutEpoch && committedTransportEpoch == currentTransportEpoch

    fun committedUnder(currentTransportEpoch: Long?) {
        committedTransportEpoch = currentTransportEpoch
        restoredWithoutEpoch = false
    }
}

/**
 * One-product-flag, one-scope production canary.
 *
 * The first eligible top-level scope on an enabled server owns that server's process-local canary slot.
 * Its first event must establish a fresh server-backed V2 snapshot. Exact later MessageNew frames commit
 * incrementally; MessageUpdated and any input that cannot prove a full V2 node take the safe snapshot path.
 * No failure degrades the selected scope to legacy. Other scopes retain the existing shadow/legacy path.
 */
internal class CanonicalMessageV2ProductionCanary(
    private val isEnabledForServer: (String) -> Boolean,
    private val generationEngine: CanonicalMessageV2GenerationEngine,
    private val logger: (String) -> Unit = {},
) {
    private data class MutexEntry(
        val mutex: Mutex = Mutex(),
        var users: Int = 0,
    )

    private val selectionMutex = Mutex()
    private val selectedScopeByPartition = mutableMapOf<DurablePartitionKey, SyncScopeKey>()
    private val scopeMutexByKey = mutableMapOf<SyncScopeKey, MutexEntry>()
    private val stateByScope = mutableMapOf<SyncScopeKey, CanonicalMessageV2CanaryScopeState>()
    private val currentTransportEpochByPartition = mutableMapOf<DurablePartitionKey, Long>()

    init {
        generationEngine.addPartitionRevocationObserver(::onPartitionRevoked)
        generationEngine.addScopeInvalidationObserver(::onScopesInvalidated)
    }

    suspend fun accept(
        partition: DurablePartitionKey,
        foldInput: CanonicalMessageFoldInput,
        snapshotLoader: CanonicalMessageV2SnapshotLoader,
    ): CanonicalMessageV2CanaryOutcome {
        require(partition.consumerId == SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
        val partitionGeneration = generationEngine.partitionGeneration(partition)
        val existingSelection = selectionMutex.withLock { selectedScopeByPartition[partition] }
        val scope = when {
            existingSelection?.scopeId == foldInput.row.channelId -> existingSelection
            existingSelection != null -> return CanonicalMessageV2CanaryOutcome.NotSelected
            else -> foldInput.v2ScopeOrNull(partition) ?: return CanonicalMessageV2CanaryOutcome.NotSelected
        }
        val selected = selectionMutex.withLock {
            val current = selectedScopeByPartition[partition]
            when {
                current != null -> current == scope
                !isEnabledForServer(partition.serverId) -> false
                else -> {
                    selectedScopeByPartition[partition] = scope
                    logger("canonical_v2_canary mode=activating server=${partition.serverId} scope=${scope.scopeId}")
                    true
                }
            }
        }
        if (!selected) return CanonicalMessageV2CanaryOutcome.NotSelected

        return withScopeMutex(scope) {
            generationEngine.withScopeWriter(scope) writer@{
                val state = selectionMutex.withLock {
                    stateByScope.getOrPut(scope) { CanonicalMessageV2CanaryScopeState() }
                }
                if (!isEnabledForServer(partition.serverId)) {
                    return@writer revokeAtSnapshotBarrier(
                        scope,
                        foldInput.row.id,
                        state,
                        snapshotLoader,
                        partitionGeneration,
                    )
                }
                if (!CANONICAL_MESSAGE_FULL_V2_SOLE_APPLY_ELIGIBLE) {
                    state.mode = CanonicalMessageV2CanaryMode.ShadowOnly
                    return@writer CanonicalMessageV2CanaryOutcome.NotSelected
                }

                if (!state.initialized) {
                    val restored = try {
                        generationEngine.restore(scope)
                    } catch (throwable: Throwable) {
                        state.mode = CanonicalMessageV2CanaryMode.Broken
                        logger(
                            "canonical_v2_canary mode=broken server=${scope.serverId} scope=${scope.scopeId} " +
                                "reason=restore_${throwable::class.simpleName ?: "unknown"}",
                        )
                        return@writer CanonicalMessageV2CanaryOutcome.Held(CANONICAL_MESSAGE_V2_SCOPE_POISONED)
                    }
                    state.initialized = true
                    state.cursor = restored.cursor
                    // A window that survived a process restart may have missed frames meanwhile.
                    state.restoredWithoutEpoch = restored.cursor != null
                    state.mode =
                        if (restored.cursor == null) CanonicalMessageV2CanaryMode.Activating
                        else CanonicalMessageV2CanaryMode.SoleApply
                }
                state.cursor = generationEngine.currentCursor(scope)
                if (state.cursor == null) state.mode = CanonicalMessageV2CanaryMode.Activating

                // Read the epoch inside this scope's writer, at commit time, not when the frame arrived.
                val transportEpoch = currentTransportEpoch(scope.partition)
                if (
                    foldInput.kind == CanonicalMessageFoldKind.New &&
                    state.cursor != null &&
                    state.appendsContinuously(transportEpoch)
                ) {
                    val envelope = foldInput.toCanonicalMessageV2Envelope(partition, replyCount = 0)
                    if (envelope != null) {
                        val cursor = state.cursor!!.advancedBy(envelope.fact.seq)
                        return@writer when (
                            generationEngine.applyIncremental(
                                CanonicalMessageV2IncrementalFrame(scope, listOf(envelope), cursor),
                                expectedPartitionGeneration = partitionGeneration,
                            )
                        ) {
                            CanonicalMessageV2CommitResult.Committed -> {
                                state.cursor = cursor
                                state.committedUnder(transportEpoch)
                                state.mode = CanonicalMessageV2CanaryMode.SoleApply
                                logger(
                                    "canonical_v2_canary mode=sole_apply server=${scope.serverId} " +
                                        "scope=${scope.scopeId} source=incremental",
                                )
                                CanonicalMessageV2CanaryOutcome.Committed
                            }
                            is CanonicalMessageV2CommitResult.BaselineRequired ->
                                applyAuthoritativeSnapshot(
                                    scope,
                                    foldInput.row.id,
                                    state,
                                    snapshotLoader,
                                    partitionGeneration,
                                )
                            CanonicalMessageV2CommitResult.PartitionRevoked ->
                                heldAfterPartitionRevoke(scope, state)
                            CanonicalMessageV2CommitResult.ScopeEvicted ->
                                heldAfterScopeEviction(scope, state)
                        }
                    }
                }

                applyAuthoritativeSnapshot(
                    scope,
                    foldInput.row.id,
                    state,
                    snapshotLoader,
                    partitionGeneration,
                )
            }
        }
    }

    private suspend fun <T> withScopeMutex(
        scope: SyncScopeKey,
        block: suspend () -> T,
    ): T {
        val entry = selectionMutex.withLock {
            scopeMutexByKey.getOrPut(scope) { MutexEntry() }.also { it.users += 1 }
        }
        return try {
            entry.mutex.withLock { block() }
        } finally {
            selectionMutex.withLock {
                entry.users -= 1
                if (entry.users == 0 && scopeMutexByKey[scope] === entry) {
                    scopeMutexByKey.remove(scope)
                }
            }
        }
    }

    /**
     * Task #167: the realtime transport serving [partition] (re)connected as [transportEpoch]. Posted by
     * the native host before any frame of the new socket. Recorded per partition; every scope compares it
     * with the epoch it last committed under, inside its own writer, at commit time.
     */
    suspend fun noteTransportEpoch(partition: DurablePartitionKey, transportEpoch: Long) {
        if (transportEpoch <= 0L) return
        selectionMutex.withLock {
            val previous = currentTransportEpochByPartition[partition]
            if (previous == null || transportEpoch > previous) {
                currentTransportEpochByPartition[partition] = transportEpoch
            }
        }
    }

    private suspend fun currentTransportEpoch(partition: DurablePartitionKey): Long? =
        selectionMutex.withLock { currentTransportEpochByPartition[partition] }

    /**
     * Read-only view for writers outside the canary (task #1124 enter-snapshot): whether [scope] is
     * the scope this canary currently owns for its partition. Never selects, never widens.
     */
    suspend fun isSelected(scope: SyncScopeKey): Boolean =
        selectionMutex.withLock { selectedScopeByPartition[scope.partition] == scope }

    suspend fun modeFor(serverId: String, scopeId: String): CanonicalMessageV2CanaryMode =
        selectionMutex.withLock {
            val selected = selectedScopeByPartition.values.firstOrNull { it.serverId == serverId }
                ?: return@withLock CanonicalMessageV2CanaryMode.Off
            if (selected.scopeId != scopeId) return@withLock CanonicalMessageV2CanaryMode.ShadowOnly
            stateByScope[selected]?.mode ?: CanonicalMessageV2CanaryMode.Activating
        }

    private suspend fun applyAuthoritativeSnapshot(
        scope: SyncScopeKey,
        targetFactId: String,
        state: CanonicalMessageV2CanaryScopeState,
        snapshotLoader: CanonicalMessageV2SnapshotLoader,
        partitionGeneration: Long,
    ): CanonicalMessageV2CanaryOutcome {
        state.mode = CanonicalMessageV2CanaryMode.Activating
        return when (val loaded = snapshotLoader.load(CanonicalMessageV2SnapshotRequest(scope, targetFactId))) {
            is CanonicalMessageV2SnapshotLoadResult.Unavailable -> {
                generationEngine.rebaselineFailed(scope)
                state.mode = CanonicalMessageV2CanaryMode.Broken
                logger(
                    "canonical_v2_canary mode=broken server=${scope.serverId} scope=${scope.scopeId} " +
                        "reason=${loaded.diagnostic}",
                )
                CanonicalMessageV2CanaryOutcome.Held(loaded.diagnostic)
            }
            is CanonicalMessageV2SnapshotLoadResult.Loaded -> {
                val frame = loaded.frame
                if (frame.scope != scope || frame.envelopes.none { it.fact.id == targetFactId }) {
                    generationEngine.rebaselineFailed(scope)
                    state.mode = CanonicalMessageV2CanaryMode.Broken
                    return CanonicalMessageV2CanaryOutcome.Held("canonical_v2_snapshot_missing_target")
                }
                try {
                    when (
                        generationEngine.applySnapshot(
                            frame,
                            expectedPartitionGeneration = partitionGeneration,
                        )
                    ) {
                        CanonicalMessageV2CommitResult.Committed -> {
                            state.cursor = frame.cursor
                            // The snapshot reads the server's current window, so it is continuous with
                            // whatever epoch is current now (read under this scope's writer).
                            state.committedUnder(currentTransportEpoch(scope.partition))
                            state.mode = CanonicalMessageV2CanaryMode.SoleApply
                            logger(
                                "canonical_v2_canary mode=sole_apply server=${scope.serverId} " +
                                    "scope=${scope.scopeId} source=snapshot",
                            )
                            CanonicalMessageV2CanaryOutcome.Committed
                        }
                        CanonicalMessageV2CommitResult.PartitionRevoked ->
                            heldAfterPartitionRevoke(scope, state)
                        CanonicalMessageV2CommitResult.ScopeEvicted ->
                            heldAfterScopeEviction(scope, state)
                        is CanonicalMessageV2CommitResult.BaselineRequired ->
                            error("snapshot commit cannot require a baseline")
                    }
                } catch (throwable: Throwable) {
                    generationEngine.rebaselineFailed(scope)
                    state.mode = CanonicalMessageV2CanaryMode.Broken
                    CanonicalMessageV2CanaryOutcome.Held(
                        "canonical_v2_snapshot_${throwable::class.simpleName ?: "failed"}",
                    )
                }
            }
        }
    }

    private suspend fun revokeAtSnapshotBarrier(
        scope: SyncScopeKey,
        targetFactId: String,
        state: CanonicalMessageV2CanaryScopeState,
        snapshotLoader: CanonicalMessageV2SnapshotLoader,
        partitionGeneration: Long,
    ): CanonicalMessageV2CanaryOutcome {
        if (state.mode == CanonicalMessageV2CanaryMode.Off) return CanonicalMessageV2CanaryOutcome.NotSelected
        val barrier = applyAuthoritativeSnapshot(
            scope,
            targetFactId,
            state,
            snapshotLoader,
            partitionGeneration,
        )
        if (barrier != CanonicalMessageV2CanaryOutcome.Committed) return barrier
        generationEngine.clearScope(scope)
        selectionMutex.withLock {
            if (selectedScopeByPartition[scope.partition] == scope) {
                selectedScopeByPartition.remove(scope.partition)
            }
            stateByScope.remove(scope)
        }
        logger("canonical_v2_canary mode=off server=${scope.serverId} scope=${scope.scopeId} reason=flag_revoked")
        return CanonicalMessageV2CanaryOutcome.NotSelected
    }

    private suspend fun onPartitionRevoked(partition: DurablePartitionKey) {
        selectionMutex.withLock {
            selectedScopeByPartition.remove(partition)
            currentTransportEpochByPartition.remove(partition)
            stateByScope.keys.removeAll { it.partition == partition }
        }
    }

    private suspend fun onScopesInvalidated(scopes: Set<SyncScopeKey>) {
        selectionMutex.withLock {
            selectedScopeByPartition.entries.removeAll { it.value in scopes }
            stateByScope.keys.removeAll { it in scopes }
        }
    }

    private fun heldAfterPartitionRevoke(
        scope: SyncScopeKey,
        state: CanonicalMessageV2CanaryScopeState,
    ): CanonicalMessageV2CanaryOutcome {
        state.cursor = null
        state.mode = CanonicalMessageV2CanaryMode.Off
        logger(
            "canonical_v2_canary mode=off server=${scope.serverId} " +
                "scope=${scope.scopeId} reason=partition_revoked",
        )
        return CanonicalMessageV2CanaryOutcome.Held("canonical_v2_partition_revoked")
    }

    private fun heldAfterScopeEviction(
        scope: SyncScopeKey,
        state: CanonicalMessageV2CanaryScopeState,
    ): CanonicalMessageV2CanaryOutcome {
        state.cursor = null
        state.mode = CanonicalMessageV2CanaryMode.Off
        logger(
            "canonical_v2_canary mode=off server=${scope.serverId} " +
                "scope=${scope.scopeId} reason=retention_evicted",
        )
        return CanonicalMessageV2CanaryOutcome.Held("canonical_v2_scope_evicted")
    }
}

private fun CanonicalMessageFoldInput.v2ScopeOrNull(partition: DurablePartitionKey): SyncScopeKey? {
    val context = row.conversationContext ?: return null
    val kind = context.channelType
    if (kind !in setOf("channel", "private", "dm", "joint")) return null
    if (
        context.parentChannelId != null ||
        context.parentChannelType != null ||
        context.parentMessageId != null
    ) return null
    return SyncScopeKey(partition = partition, scopeId = row.channelId, scopeKind = kind)
}

internal fun CanonicalMessageFoldInput.toCanonicalMessageV2Envelope(
    partition: DurablePartitionKey,
    replyCount: Int,
    producerContextOverride: CanonicalConversationContextWire? = null,
): CanonicalMessageEnvelopeV2? {
    if (replyCount < 0) return null
    if (!row.hasCanonicalV2Scalars()) return null
    val requiredAggregates = setOf(
        MessageAggregateField.SenderName,
        MessageAggregateField.SenderDescription,
        MessageAggregateField.SenderMembershipStatus,
        MessageAggregateField.Mentions,
        MessageAggregateField.Attachments,
        MessageAggregateField.Reactions,
        MessageAggregateField.ActionMetadata,
    )
    if (!presentAggregates.containsAll(requiredAggregates)) return null
    if (producerContextOverride == null && MessageAggregateField.ConversationContext !in presentAggregates) return null
    val context = producerContextOverride ?: row.conversationContext ?: return null
    val normalizedReactions = normalizeLegacyReactionRoster(
        context = ReactionNormalizationContext(
            serverId = partition.serverId,
            messageId = row.id,
            parentScopeKey = ReactionParentScopeKey(partition.serverId, row.channelId, context.channelType),
            authority = ReactionIngressAuthority.SharedRoom,
        ),
        reactions = row.reactions.map { it.toLegacyReactionRosterDto() },
    ) as? ReactionNormalizationOutcome.Normalized ?: return null
    val actionMetadata = row.actionMetadata.toCanonicalActionMetadataV2OrNull() ?: if (row.actionMetadata != null) return null else null
    val fact = CanonicalMessageNodeV2(
        id = row.id,
        seq = row.seq,
        senderType = row.senderType,
        senderId = row.senderId,
        content = row.content,
        messageType = row.messageType,
        createdAt = row.createdAt,
        randomId = row.randomId,
        senderName = row.senderName,
        senderDescription = row.senderDescription,
        senderMembershipStatus = row.senderMembershipStatus,
        mentions = row.mentions.map { CanonicalMentionV2(it.id, it.name, it.type) },
        attachments = row.attachments.map {
            CanonicalAttachmentV2(
                id = it.id,
                filename = it.filename,
                mimeType = it.mimeType,
                sizeBytes = it.sizeBytes,
                width = it.width,
                height = it.height,
                thumbnailUrl = it.thumbnailUrl,
            )
        },
        reactions = normalizedReactions.value.sharedFact,
        actionMetadata = actionMetadata,
        replyCount = replyCount,
    )
    return (normalizeCanonicalMessageV2(
        CanonicalMessageV2NormalizationInput(
            partition = partition,
            scopeId = row.channelId,
            fact = fact,
            producerContext = context,
            childReplyScopeId = row.threadId,
        ),
    ) as? CanonicalMessageV2NormalizationResult.Normalized)?.envelope
}

private fun PersistedCursor.advancedBy(seq: Long): PersistedCursor =
    copy(
        latestSeq = maxOf(latestSeq ?: seq, seq),
        oldestLoadedSeq = minOf(oldestLoadedSeq ?: seq, seq),
        newestLoadedSeq = maxOf(newestLoadedSeq ?: seq, seq),
        hasLoadedNewest = true,
    )

private val canonicalV2ProducerJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

@Serializable
private data class CanonicalActionMetadataProducer(
    val kind: String? = null,
    val version: Int? = null,
    val action: CanonicalActionCardActionV2? = null,
    val state: String = "prepared",
    val executedAt: String? = null,
    val executedByUserName: String? = null,
    val result: CanonicalActionCardResultV2? = null,
    val forwardedItems: List<CanonicalForwardedBundleProducerItem> = emptyList(),
)

@Serializable
private data class CanonicalForwardedBundleProducerItem(
    val index: Int? = null,
    val sourceAuthorSnapshot: CanonicalForwardedAuthorProducer? = null,
    val sourceCreatedAt: String? = null,
    val sourceTargetSnapshot: CanonicalForwardedTargetProducer? = null,
    val contentSnapshot: String = "",
    val attachmentSnapshots: List<CanonicalForwardedAttachmentSnapshotV2> = emptyList(),
)

@Serializable
private data class CanonicalForwardedAuthorProducer(
    val type: String? = null,
    val name: String? = null,
    val uniqueName: String? = null,
)

@Serializable
private data class CanonicalForwardedTargetProducer(
    val type: String? = null,
    val label: String = "",
    val labelVisibility: String? = null,
)

private fun JsonElement?.toCanonicalActionMetadataV2OrNull(): CanonicalActionMetadataV2? {
    val element = this ?: return null
    if (!element.hasSupportedCanonicalActionProducerShape()) return null
    val producer = runCatching {
        canonicalV2ProducerJson.decodeFromString<CanonicalActionMetadataProducer>(element.toString())
    }.getOrNull() ?: return null
    return when (producer.kind) {
        "action-card" -> {
            val action = producer.action ?: return null
            if (action.type.isBlank()) return null
            CanonicalActionMetadataV2.ActionCard(
                version = producer.version,
                action = action,
                state = producer.state,
                executedAt = producer.executedAt,
                executedByUserName = producer.executedByUserName,
                result = producer.result,
            )
        }
        "forwarded-bundle" -> CanonicalActionMetadataV2.ForwardedBundle(
            version = producer.version,
            items = producer.forwardedItems.mapIndexed { fallbackIndex, item ->
                CanonicalForwardedBundleItemV2(
                    index = item.index ?: fallbackIndex,
                    author = item.sourceAuthorSnapshot?.let {
                        CanonicalForwardedAuthorSnapshotV2(it.type, it.name, it.uniqueName)
                    },
                    createdAt = item.sourceCreatedAt,
                    target = item.sourceTargetSnapshot?.let {
                        CanonicalForwardedTargetSnapshotV2(it.type, it.label, it.labelVisibility)
                    },
                    content = item.contentSnapshot,
                    attachments = item.attachmentSnapshots,
                )
            },
        )
        else -> null
    }
}

private fun CanonicalMessageRow.hasCanonicalV2Scalars(): Boolean =
    seq >= 0L &&
        listOf(id, channelId, senderType, senderId, messageType, createdAt, senderName).all { it.isCanonicalV2Value() } &&
        randomId?.isCanonicalV2Value() != false &&
        threadId?.isCanonicalV2Value() != false &&
        mentions.all { it.id.isCanonicalV2Value() && it.name.isCanonicalV2Value() && it.type.isCanonicalV2Value() } &&
        attachments.all {
            it.id.isCanonicalV2Value() &&
                it.filename.isCanonicalV2Value() &&
                it.mimeType.isCanonicalV2Value() &&
                it.sizeBytes >= 0L &&
                it.width?.let { width -> width >= 0 } != false &&
                it.height?.let { height -> height >= 0 } != false
        }

private fun String.isCanonicalV2Value(): Boolean = isNotBlank() && this == trim()

private fun JsonElement.hasSupportedCanonicalActionProducerShape(): Boolean {
    val root = this as? JsonObject ?: return false
    val kind = (root["kind"] as? kotlinx.serialization.json.JsonPrimitive)?.content
    return when (kind) {
        "action-card" -> {
            if (!root.keys.all(ACTION_CARD_ROOT_KEYS::contains)) return false
            val action = root["action"] as? JsonObject ?: return false
            if (!action.keys.all(ACTION_CARD_ACTION_KEYS::contains)) return false
            val result = root["result"]
            result == null || result is JsonNull ||
                ((result as? JsonObject)?.keys?.all(ACTION_CARD_RESULT_KEYS::contains) == true)
        }
        "forwarded-bundle" -> {
            if (!root.keys.all(FORWARDED_ROOT_KEYS::contains)) return false
            val items = root["forwardedItems"] as? JsonArray ?: return false
            items.all { itemElement ->
                val item = itemElement as? JsonObject ?: return@all false
                if (!item.keys.all(FORWARDED_ITEM_KEYS::contains)) return@all false
                val author = item["sourceAuthorSnapshot"]
                if (author != null && author !is JsonNull &&
                    (author as? JsonObject)?.keys?.all(FORWARDED_AUTHOR_KEYS::contains) != true
                ) return@all false
                val target = item["sourceTargetSnapshot"]
                if (target != null && target !is JsonNull &&
                    (target as? JsonObject)?.keys?.all(FORWARDED_TARGET_KEYS::contains) != true
                ) return@all false
                val attachments = item["attachmentSnapshots"] as? JsonArray ?: return@all false
                attachments.all { attachment ->
                    (attachment as? JsonObject)?.keys?.all(FORWARDED_ATTACHMENT_KEYS::contains) == true
                }
            }
        }
        else -> false
    }
}

private val ACTION_CARD_ROOT_KEYS = setOf(
    "kind", "version", "action", "state", "executedAt", "executedByUserName", "result",
)
private val ACTION_CARD_ACTION_KEYS = setOf(
    "type", "name", "description", "visibility", "channel", "suggestedComputer", "requiredComputer",
    "humans", "agents", "initialHumans", "initialAgents", "draftHint", "requestId", "agentId",
    "agentName", "clientId", "clientKey", "clientName", "scopes",
)
private val ACTION_CARD_RESULT_KEYS = setOf(
    "kind", "id", "name", "channelId", "channelName", "addedHumanIds", "addedAgentIds",
)
private val FORWARDED_ROOT_KEYS = setOf("kind", "version", "forwardedItems")
private val FORWARDED_ITEM_KEYS = setOf(
    "index", "sourceMessageId", "sourceAuthorSnapshot", "sourceCreatedAt", "sourceTargetSnapshot",
    "contentSnapshot", "attachmentSnapshots", "provenanceState",
)
private val FORWARDED_AUTHOR_KEYS = setOf("type", "id", "name", "uniqueName")
private val FORWARDED_TARGET_KEYS = setOf("id", "type", "label", "labelVisibility")
private val FORWARDED_ATTACHMENT_KEYS = setOf("filename", "mimeType")
