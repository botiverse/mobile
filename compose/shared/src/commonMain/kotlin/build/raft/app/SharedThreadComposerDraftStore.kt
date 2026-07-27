package build.raft.app

import build.raft.app.data.InboxItem
import build.raft.app.data.InboxSnapshot
import build.raft.app.data.MviPersistSlice
import build.raft.app.data.MviStoreRuntime
import build.raft.app.data.SharedPreferenceStore
import build.raft.app.model.InboxItemKind
import build.raft.app.model.TaskSummary
import com.tencent.kuikly.lifecycle.ViewModel
import com.tencent.kuikly.lifecycle.viewModelScope
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.EmptyCoroutineContext

internal data class SharedThreadComposerDraftState(
    val instanceId: String = "",
    val draftKey: String? = null,
    val seedText: String = "",
    val seedAsTask: Boolean = false,
    val syncNonce: Int = 0,
    val persistBoundaryNonce: Int = 0,
    val focusRequestNonce: Int = 0,
    val sending: Boolean = false,
    val failureLabel: String? = null,
    val removedAttachmentIds: Set<String> = emptySet(),
    val uploadedAttachments: List<SharedThreadComposerAttachment> = emptyList(),
    val externalShareSources: List<SharedThreadComposerExternalShareSource> = emptyList(),
    val internalForwardSource: SharedThreadComposerInternalForwardSource? = null,
    val pendingMentionActions: List<KuiklyPendingMentionActionItem> = emptyList(),
    val mentionActionNotice: String? = null,
    val mentionActionStates: Map<String, String> = emptyMap(),
    val removingMentionActionIds: Set<String> = emptySet(),
    val executingMentionActionIds: Set<String> = emptySet(),
    val executingMentionActionStates: Map<String, String> = emptyMap()
)

internal data class SharedThreadComposerPersistedDraft(
    val text: String,
    val asTask: Boolean,
    val uploadedAttachments: List<SharedThreadComposerAttachment>,
    val externalShareSources: List<SharedThreadComposerExternalShareSource>,
    val internalForwardSource: SharedThreadComposerInternalForwardSource?
)

/**
 * Exhaustive inventory of every dimension persisted by [SharedThreadComposerDraftPersistence].
 * Adding persisted draft state requires adding a dimension here and classifying it in the
 * no-else [internalForwardDraftMergePolicy] when. That turns an unreviewed merge decision into a
 * compile failure instead of silently letting a new draft dimension be overwritten.
 */
internal enum class SharedThreadComposerPersistedDraftDimension {
    Text,
    AsTask,
    UploadedAttachments,
    ExternalShareSources,
    InternalForwardSource
}

internal enum class InternalForwardDraftMergePolicy {
    PreserveAsUserNote,
    RejectDestinationDraft
}

internal fun internalForwardDraftMergePolicy(
    dimension: SharedThreadComposerPersistedDraftDimension
): InternalForwardDraftMergePolicy =
    when (dimension) {
        SharedThreadComposerPersistedDraftDimension.Text -> InternalForwardDraftMergePolicy.PreserveAsUserNote
        SharedThreadComposerPersistedDraftDimension.AsTask -> InternalForwardDraftMergePolicy.RejectDestinationDraft
        SharedThreadComposerPersistedDraftDimension.UploadedAttachments -> InternalForwardDraftMergePolicy.RejectDestinationDraft
        SharedThreadComposerPersistedDraftDimension.ExternalShareSources -> InternalForwardDraftMergePolicy.RejectDestinationDraft
        SharedThreadComposerPersistedDraftDimension.InternalForwardSource -> InternalForwardDraftMergePolicy.RejectDestinationDraft
    }

internal fun SharedThreadComposerPersistedDraft.activeDimensions(): Set<SharedThreadComposerPersistedDraftDimension> =
    buildSet {
        if (text.isNotBlank()) add(SharedThreadComposerPersistedDraftDimension.Text)
        if (asTask) add(SharedThreadComposerPersistedDraftDimension.AsTask)
        if (uploadedAttachments.isNotEmpty()) add(SharedThreadComposerPersistedDraftDimension.UploadedAttachments)
        if (externalShareSources.isNotEmpty()) add(SharedThreadComposerPersistedDraftDimension.ExternalShareSources)
        if (internalForwardSource != null) add(SharedThreadComposerPersistedDraftDimension.InternalForwardSource)
    }

@Serializable
internal data class SharedThreadComposerExternalShareSource(
    val token: String,
    val accountId: String,
    val serverId: String
)

@Serializable
internal data class SharedThreadComposerInternalForwardSource(
    val operationId: String,
    val accountId: String,
    val serverId: String,
    val sourceChannelId: String,
    val destinationChannelId: String,
    val destinationKind: String,
    val destinationJointChannelId: String? = null,
    val destinationResolvedMemberId: String? = null,
    val messageIds: List<String>,
    val mode: String,
    val sourceLabel: String,
    val destinationLabel: String = "",
    val attachmentCount: Int = 0
)

internal sealed interface InternalForwardComposerDraftStageResult {
    data object Staged : InternalForwardComposerDraftStageResult
    data class Rejected(val code: String) : InternalForwardComposerDraftStageResult
}

internal data class SharedThreadComposerDraftRecord(
    val key: String,
    val sendChannelId: String,
    val parentMessageId: String? = null,
    val mentionChannelId: String? = null
) {
    val isRootComposer: Boolean
        get() = parentMessageId.isNullOrBlank()

    fun matchesRootConversation(conversationId: String): Boolean =
        isRootComposer && sendChannelId == conversationId

    fun matchesReplyThread(parentMessageId: String): Boolean =
        !isRootComposer && this.parentMessageId == parentMessageId

    fun matchesReplyThreadIdentity(other: SharedThreadComposerDraftRecord): Boolean =
        if (isRootComposer || other.isRootComposer) {
            false
        } else {
            val parent = parentMessageId?.takeIf { it.isNotBlank() }
            val otherParent = other.parentMessageId?.takeIf { it.isNotBlank() }
            val mention = mentionChannelId?.takeIf { it.isNotBlank() }
            val otherMention = other.mentionChannelId?.takeIf { it.isNotBlank() }
            parent != null && parent == otherParent && mention != null && mention == otherMention
        }
}

internal sealed interface ExternalShareComposerDraftStageResult {
    data object Staged : ExternalShareComposerDraftStageResult
    data class Rejected(val code: String) : ExternalShareComposerDraftStageResult
}

internal data class SharedThreadComposerDraftProjection(
    private val rootConversationIds: Set<String> = emptySet(),
    private val replyParentMessageIds: Set<String> = emptySet()
) {
    fun hasRootDraft(conversationId: String): Boolean =
        conversationId in rootConversationIds

    fun hasReplyDraft(parentMessageId: String?): Boolean =
        parentMessageId?.takeIf { it.isNotBlank() } in replyParentMessageIds

    fun hasDraftForInboxItem(item: InboxItem): Boolean =
        when (item.kind) {
            InboxItemKind.Thread -> hasReplyDraft(item.parentMessageId?.value)
            InboxItemKind.Channel,
            InboxItemKind.DirectMessage,
            InboxItemKind.MentionAction,
            InboxItemKind.System -> hasRootDraft(item.channelId.value)
        }

    fun hasDraftForTask(task: TaskSummary): Boolean =
        hasReplyDraft(task.parentMessageId?.value)
            || hasReplyDraft(task.messageId?.value)

    companion object {
        val Empty = SharedThreadComposerDraftProjection()
    }
}

internal fun Set<SharedThreadComposerDraftRecord>.toSharedThreadComposerDraftProjection(): SharedThreadComposerDraftProjection =
    SharedThreadComposerDraftProjection(
        rootConversationIds = mapNotNull { record ->
            record.sendChannelId.takeIf { record.isRootComposer }
        }.toSet(),
        replyParentMessageIds = mapNotNull { record ->
            record.parentMessageId?.takeIf { !record.isRootComposer && it.isNotBlank() }
        }.toSet()
    )

internal fun InboxItem.withComposerDraftProjection(
    draftProjection: SharedThreadComposerDraftProjection
): InboxItem =
    copy(hasDraft = draftProjection.hasDraftForInboxItem(this))

internal fun InboxSnapshot.withComposerDraftProjection(
    draftProjection: SharedThreadComposerDraftProjection
): InboxSnapshot =
    copy(items = items.map { item -> item.withComposerDraftProjection(draftProjection) })

internal class SharedThreadComposerDraftPersistence(
    private val store: SharedPreferenceStore,
    private val diagnosticsLogger: ((String) -> Unit)? = null
) {
    private val lock = SynchronizedObject()

    fun read(key: String): SharedThreadComposerPersistedDraft? = synchronized(lock) {
        val text = store.getString(textKey(key)) ?: return null
        SharedThreadComposerPersistedDraft(
            text = text,
            asTask = store.getString(asTaskKey(key)) == "true",
            uploadedAttachments = readUploadedAttachments(key),
            externalShareSources = readExternalShareSources(key),
            internalForwardSource = readInternalForwardSource(key)
        )
    }

    fun readWithReplyFallback(key: String): Pair<String, SharedThreadComposerPersistedDraft>? = synchronized(lock) {
        readUnlocked(key)?.let { draft -> return key to draft }
        val currentRecord = key.toSharedThreadComposerDraftRecord()?.takeUnless { it.isRootComposer } ?: return null
        activeDraftRecordsUnlocked()
            .filter { record -> record.key != key && record.matchesReplyThreadIdentity(currentRecord) }
            .sortedBy { record -> record.key }
            .mapNotNull { record -> readUnlocked(record.key)?.let { draft -> record.key to draft } }
            .firstOrNull()
    }

    fun save(
        key: String,
        text: String,
        asTask: Boolean,
        uploadedAttachments: List<SharedThreadComposerAttachment> = emptyList(),
        externalShareSources: List<SharedThreadComposerExternalShareSource> = emptyList(),
        internalForwardSource: SharedThreadComposerInternalForwardSource? = null
    ) {
        synchronized(lock) {
            val persistedAttachments = uploadedAttachments.persistableThreadComposerDraftAttachments()
            diagnosticsLogger?.invoke(
                "thread_composer_draft_persist_save key=$key textLen=${text.length} " +
                    "textBlank=${text.isBlank()} asTask=$asTask attachments=${persistedAttachments.size} " +
                    "activeBefore=${activeKeys().sorted().joinToString(separator = ",")}"
            )
            val persistedSources = externalShareSources.validExternalShareComposerSources()
            val persistedForward = internalForwardSource?.takeIf(SharedThreadComposerInternalForwardSource::isValid)
            if (text.isBlank() && !asTask && persistedAttachments.isEmpty() && persistedSources.isEmpty() && persistedForward == null) {
                clearUnlocked(key)
                return@synchronized
            }
            store.putString(textKey(key), text)
            store.putString(asTaskKey(key), asTask.toString())
            store.putString(
                attachmentsKey(key),
                persistedAttachments
                    .takeIf { it.isNotEmpty() }
                    ?.let { attachments -> ComposerDraftJson.encodeToString(attachments) }
            )
            store.putString(
                sourcesKey(key),
                persistedSources.takeIf { it.isNotEmpty() }
                    ?.let { sources -> ComposerDraftJson.encodeToString(sources) }
            )
            store.putString(
                internalForwardKey(key),
                persistedForward?.let { ComposerDraftJson.encodeToString(it) }
            )
            addActiveKey(key)
            diagnosticsLogger?.invoke(
                "thread_composer_draft_persist_saved key=$key activeAfter=${activeKeys().sorted().joinToString(separator = ",")}"
            )
        }
    }

    /**
     * Atomically merges an External Share handoff into the canonical conversation draft. The
     * route contains only conversation identity; text and private file references enter through
     * this store-owned persistence boundary before navigation.
     */
    fun stageExternalShare(
        key: String,
        text: String,
        attachments: List<SharedThreadComposerAttachment>,
        source: SharedThreadComposerExternalShareSource
    ): ExternalShareComposerDraftStageResult = synchronized(lock) {
        val record = key.toSharedThreadComposerDraftRecord()
            ?: return@synchronized ExternalShareComposerDraftStageResult.Rejected(
                "external_share_composer_key_invalid"
            )
        if (!record.isRootComposer) {
            return@synchronized ExternalShareComposerDraftStageResult.Rejected(
                "external_share_reply_composer_not_supported"
            )
        }
        val normalizedAttachments = attachments.persistableThreadComposerDraftAttachments()
        if (listOf(source).validExternalShareComposerSources().size != 1) {
            return@synchronized ExternalShareComposerDraftStageResult.Rejected(
                "external_share_source_invalid"
            )
        }
        if (
            normalizedAttachments.size != attachments.size ||
            normalizedAttachments.any { attachment -> !attachment.isValidExternalShareQueuedAttachment() }
        ) {
            return@synchronized ExternalShareComposerDraftStageResult.Rejected(
                "external_share_attachment_invalid"
            )
        }
        val current = readUnlocked(key)
        val nextText =
            listOf(current?.text.orEmpty(), text.trim())
                .filter(String::isNotBlank)
                .distinct()
                .joinToString("\n\n")
        val nextAttachments =
            (current?.uploadedAttachments.orEmpty() + normalizedAttachments)
                .distinctBy { attachment -> attachment.id }
                .persistableThreadComposerDraftAttachments()
        val nextSources =
            (current?.externalShareSources.orEmpty() + source)
                .validExternalShareComposerSources()
        if (nextText.isBlank() && nextAttachments.isEmpty()) {
            return@synchronized ExternalShareComposerDraftStageResult.Rejected(
                "external_share_composer_draft_empty"
            )
        }
        store.putString(textKey(key), nextText)
        store.putString(asTaskKey(key), (current?.asTask == true).toString())
        store.putString(
            attachmentsKey(key),
            nextAttachments.takeIf { it.isNotEmpty() }?.let { ComposerDraftJson.encodeToString(nextAttachments) }
        )
        store.putString(
            sourcesKey(key),
            ComposerDraftJson.encodeToString(nextSources)
        )
        addActiveKey(key)
        diagnosticsLogger?.invoke(
            "thread_composer_external_share_staged key=$key textLen=${nextText.length} " +
                "attachments=${normalizedAttachments.size}"
        )
        ExternalShareComposerDraftStageResult.Staged
    }

    fun stageInternalForward(
        key: String,
        source: SharedThreadComposerInternalForwardSource
    ): InternalForwardComposerDraftStageResult = synchronized(lock) {
        val record = key.toSharedThreadComposerDraftRecord()
            ?: return@synchronized InternalForwardComposerDraftStageResult.Rejected("internal_forward_composer_key_invalid")
        if (!record.isRootComposer || !source.isValid() || record.sendChannelId != source.destinationChannelId) {
            return@synchronized InternalForwardComposerDraftStageResult.Rejected("internal_forward_source_invalid")
        }
        val current = readUnlocked(key)
        val rejectedDimension =
            current?.activeDimensions()?.firstOrNull { dimension ->
                internalForwardDraftMergePolicy(dimension) == InternalForwardDraftMergePolicy.RejectDestinationDraft
            }
        if (rejectedDimension != null) {
            return@synchronized InternalForwardComposerDraftStageResult.Rejected(
                "internal_forward_destination_draft_conflict"
            )
        }
        store.putString(textKey(key), current?.text.orEmpty())
        store.putString(asTaskKey(key), (current?.asTask == true).toString())
        store.putString(
            attachmentsKey(key),
            current?.uploadedAttachments.orEmpty().takeIf { it.isNotEmpty() }
                ?.let { ComposerDraftJson.encodeToString(it) }
        )
        store.putString(
            sourcesKey(key),
            current?.externalShareSources.orEmpty().takeIf { it.isNotEmpty() }
                ?.let { ComposerDraftJson.encodeToString(it) }
        )
        store.putString(internalForwardKey(key), ComposerDraftJson.encodeToString(source))
        addActiveKey(key)
        diagnosticsLogger?.invoke(
            "thread_composer_internal_forward_staged key=$key messages=${source.messageIds.size} attachments=${source.attachmentCount}"
        )
        InternalForwardComposerDraftStageResult.Staged
    }

    fun clear(key: String) = synchronized(lock) {
        clearUnlocked(key)
    }

    fun activeDraftRecords(): Set<SharedThreadComposerDraftRecord> = synchronized(lock) {
        activeDraftRecordsUnlocked()
    }

    internal fun recordForKey(key: String): SharedThreadComposerDraftRecord? =
        key.toSharedThreadComposerDraftRecord()

    private fun readUnlocked(key: String): SharedThreadComposerPersistedDraft? {
        val text = store.getString(textKey(key)) ?: return null
        return SharedThreadComposerPersistedDraft(
            text = text,
            asTask = store.getString(asTaskKey(key)) == "true",
            uploadedAttachments = readUploadedAttachments(key),
            externalShareSources = readExternalShareSources(key),
            internalForwardSource = readInternalForwardSource(key)
        )
    }

    private fun clearUnlocked(key: String) {
        diagnosticsLogger?.invoke(
            "thread_composer_draft_persist_clear key=$key activeBefore=${activeKeys().sorted().joinToString(separator = ",")}"
        )
        store.putString(textKey(key), null)
        store.putString(asTaskKey(key), null)
        store.putString(attachmentsKey(key), null)
        store.putString(sourcesKey(key), null)
        store.putString(internalForwardKey(key), null)
        removeActiveKey(key)
        diagnosticsLogger?.invoke(
            "thread_composer_draft_persist_cleared key=$key activeAfter=${activeKeys().sorted().joinToString(separator = ",")}"
        )
    }

    private fun activeDraftRecordsUnlocked(): Set<SharedThreadComposerDraftRecord> =
        activeKeys()
            .mapNotNull { key -> key.toSharedThreadComposerDraftRecord() }
            .toSet()

    private fun textKey(key: String): String = "threadComposerDraft:v1:$key:text"
    private fun asTaskKey(key: String): String = "threadComposerDraft:v1:$key:asTask"
    private fun attachmentsKey(key: String): String = "threadComposerDraft:v1:$key:attachments"
    private fun sourcesKey(key: String): String = "threadComposerDraft:v1:$key:externalShareSources"
    private fun internalForwardKey(key: String): String = "threadComposerDraft:v1:$key:internalForwardSource"

    private fun readInternalForwardSource(key: String): SharedThreadComposerInternalForwardSource? =
        store.getString(internalForwardKey(key))
            ?.let { encoded ->
                runCatching { ComposerDraftJson.decodeFromString<SharedThreadComposerInternalForwardSource>(encoded) }.getOrNull()
            }
            ?.takeIf(SharedThreadComposerInternalForwardSource::isValid)

    private fun readExternalShareSources(key: String): List<SharedThreadComposerExternalShareSource> =
        store.getString(sourcesKey(key))
            ?.let { encoded ->
                runCatching {
                    ComposerDraftJson.decodeFromString<List<SharedThreadComposerExternalShareSource>>(encoded)
                }.getOrNull()
            }
            .orEmpty()
            .validExternalShareComposerSources()

    private fun readUploadedAttachments(key: String): List<SharedThreadComposerAttachment> =
        store.getString(attachmentsKey(key))
            ?.let { encoded ->
                runCatching {
                    ComposerDraftJson.decodeFromString<List<SharedThreadComposerAttachment>>(encoded)
                }.getOrNull()
            }
            .orEmpty()
            .persistableThreadComposerDraftAttachments()
            .map { attachment ->
                if (attachment.source == "external_share" && attachment.status == "uploading") {
                    attachment.copy(
                        status = "queued",
                        errorCode = null,
                        uploadBytesTransferred = 0L
                    )
                } else {
                    attachment
                }
            }

    private fun activeKeys(): Set<String> =
        readActiveKeysFromStore()

    private fun readActiveKeysFromStore(): Set<String> =
        store.getString(ActiveIndexKey)
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .orEmpty()

    private fun addActiveKey(key: String) {
        writeActiveKeys(activeKeys() + key)
    }

    private fun removeActiveKey(key: String) {
        writeActiveKeys(activeKeys() - key)
    }

    private fun writeActiveKeys(keys: Set<String>) {
        store.putString(ActiveIndexKey, keys.sorted().joinToString(separator = "\n").takeIf { it.isNotBlank() })
    }

    private fun String.toSharedThreadComposerDraftRecord(): SharedThreadComposerDraftRecord? {
        val values =
            split('|')
                .mapNotNull { part ->
                    val separator = part.indexOf('=')
                    if (separator <= 0) return@mapNotNull null
                    part.substring(0, separator) to part.substring(separator + 1).threadComposerDraftKeyDecodePart()
                }
                .toMap()
        val sendChannelId = values["send"]?.takeIf { it.isNotBlank() } ?: return null
        return SharedThreadComposerDraftRecord(
            key = this,
            sendChannelId = sendChannelId,
            parentMessageId = values["parent"]?.takeIf { it.isNotBlank() && it != "_" },
            mentionChannelId = values["mention"]?.takeIf { it.isNotBlank() && it != "_" }
        )
    }

    private fun String.threadComposerDraftKeyDecodePart(): String =
        replace("%3D", "=")
            .replace("%7C", "|")
            .replace("%25", "%")

    private companion object {
        const val ActiveIndexKey = "threadComposerDraft:v1:activeKeys"
        val ComposerDraftJson = Json { ignoreUnknownKeys = true }
    }
}

internal fun interface SharedThreadComposerDraftPersistenceCancellation {
    fun cancel()
}

internal fun interface SharedThreadComposerDraftPersistenceScheduler {
    fun schedule(delayMillis: Long, block: () -> Unit): SharedThreadComposerDraftPersistenceCancellation
}

internal class CoroutineThreadComposerDraftPersistenceScheduler(
    private val scope: CoroutineScope
) : SharedThreadComposerDraftPersistenceScheduler {
    override fun schedule(
        delayMillis: Long,
        block: () -> Unit
    ): SharedThreadComposerDraftPersistenceCancellation {
        val job: Job = scope.launch {
            delay(delayMillis)
            block()
        }
        return SharedThreadComposerDraftPersistenceCancellation(job::cancel)
    }
}

private object ImmediateThreadComposerDraftPersistenceScheduler : SharedThreadComposerDraftPersistenceScheduler {
    override fun schedule(
        delayMillis: Long,
        block: () -> Unit
    ): SharedThreadComposerDraftPersistenceCancellation {
        block()
        return SharedThreadComposerDraftPersistenceCancellation {}
    }
}

private data class PendingThreadComposerDraftWrite(
    val generation: Long,
    val state: SharedThreadComposerDraftState,
    var cancellation: SharedThreadComposerDraftPersistenceCancellation? = null
)

internal class SharedThreadComposerDraftPersistSlice(
    private val persistence: SharedThreadComposerDraftPersistence,
    private val diagnosticsLogger: ((String) -> Unit)? = null,
    private val scheduler: SharedThreadComposerDraftPersistenceScheduler = ImmediateThreadComposerDraftPersistenceScheduler,
    private val changeDebounceMillis: Long = DefaultChangeDebounceMillis
) : MviPersistSlice<SharedThreadComposerDraftState, SharedThreadComposerDraftIntent> {
    val activeDraftRecordsState: StateFlow<Set<SharedThreadComposerDraftRecord>> =
        SharedActiveDraftRecordsState.asStateFlow()
    private val stagedUploadedAttachmentsByDraftKey =
        mutableMapOf<String, List<SharedThreadComposerAttachment>>()
    private val fallbackDraftSourceKeyByCurrentKey = mutableMapOf<String, String>()
    private val pendingWritesLock = SynchronizedObject()
    private val persistenceWriteLock = SynchronizedObject()
    private val pendingWritesByDraftKey = mutableMapOf<String, PendingThreadComposerDraftWrite>()
    private val writeGenerationByDraftKey = mutableMapOf<String, Long>()

    init {
        refreshActiveDraftRecords(reason = "init")
    }

    override fun reduce(
        previous: SharedThreadComposerDraftState,
        intent: SharedThreadComposerDraftIntent,
        reduced: SharedThreadComposerDraftState
    ): SharedThreadComposerDraftState =
        when (intent) {
            is SharedThreadComposerDraftIntent.Start -> {
                val draftKey = intent.draftKey?.takeIf { it.isNotBlank() }
                previous.draftKey?.takeIf { it != draftKey }?.let(::flushPendingWrite)
                draftKey?.let(::flushPendingWrite)
                val sameDraftKey = draftKey != null && previous.draftKey == draftKey
                val stagedUploadedAttachments = consumeStagedUploadedAttachments(draftKey)
                val sessionAttachments =
                    if (sameDraftKey) {
                        previous.visibleSessionThreadComposerDraftAttachments()
                    } else {
                        emptyList()
                    }
                val removedAttachmentIds =
                    if (sameDraftKey) {
                        previous.removedAttachmentIds
                    } else {
                        emptySet()
                    }
                val restoredDraft =
                    draftKey
                        ?.let(persistence::readWithReplyFallback)
                if (draftKey != null && restoredDraft != null && restoredDraft.first != draftKey) {
                    fallbackDraftSourceKeyByCurrentKey[draftKey] = restoredDraft.first
                }
                val restored =
                    restoredDraft
                        ?.second
                        ?.let { persistedDraft ->
                            reduced.copy(
                                seedText = persistedDraft.text,
                                seedAsTask = persistedDraft.asTask,
                                uploadedAttachments = persistedDraft.uploadedAttachments,
                                externalShareSources = persistedDraft.externalShareSources,
                                internalForwardSource = persistedDraft.internalForwardSource
                            )
                        }
                        ?: reduced
                val withStagedAttachments =
                    if (stagedUploadedAttachments.isEmpty() && sessionAttachments.isEmpty() && removedAttachmentIds.isEmpty()) {
                        restored
                    } else {
                        restored.copy(
                            removedAttachmentIds = removedAttachmentIds,
                            uploadedAttachments =
                                (restored.uploadedAttachments + sessionAttachments + stagedUploadedAttachments)
                                    .distinctBy { attachment -> attachment.id }
                                    .filterNot { attachment -> attachment.id in removedAttachmentIds }
                        )
                    }
                if (withStagedAttachments.requiresComposerHostSync(reduced)) {
                    withStagedAttachments.copy(syncNonce = reduced.syncNonce + 1)
                } else {
                    withStagedAttachments
                }
            }
            else -> reduced
        }

    override fun onTransition(
        previous: SharedThreadComposerDraftState,
        intent: SharedThreadComposerDraftIntent,
        next: SharedThreadComposerDraftState
    ) {
        when (intent) {
            is SharedThreadComposerDraftIntent.Start ->
                next.draftKey?.let { key ->
                    val fallbackSourceKey = fallbackDraftSourceKeyByCurrentKey.remove(key)
                    if (fallbackSourceKey != null && fallbackSourceKey != key) {
                        synchronized(persistenceWriteLock) {
                            persistence.save(
                                key = key,
                                text = next.seedText,
                                asTask = next.seedAsTask,
                                uploadedAttachments = next.visiblePersistableThreadComposerDraftAttachments(),
                                externalShareSources = next.externalShareSources,
                                internalForwardSource = next.internalForwardSource
                            )
                            persistence.clear(fallbackSourceKey)
                            refreshActiveDraftRecords(reason = "StartFallbackRestore")
                        }
                    }
                }
            is SharedThreadComposerDraftIntent.Change ->
                next.draftKey?.let { key ->
                    schedulePendingWrite(key, next)
                }
            is SharedThreadComposerDraftIntent.InsertMention,
            is SharedThreadComposerDraftIntent.RestoreAfterFailure,
            is SharedThreadComposerDraftIntent.AddUploadedAttachments,
            is SharedThreadComposerDraftIntent.MarkAttachmentsFailed,
            is SharedThreadComposerDraftIntent.RemoveAttachment,
            SharedThreadComposerDraftIntent.RemoveInternalForward ->
                next.draftKey?.let { key ->
                    cancelPendingWrite(key)
                    persistState(key, next, intent.toThreadComposerDraftIntentDiagnosticsLabel())
                }
            SharedThreadComposerDraftIntent.Flush ->
                next.draftKey?.let(::flushPendingWrite)
            SharedThreadComposerDraftIntent.BeginSend ->
                previous.draftKey?.let { key ->
                    cancelPendingWrite(key)
                    persistState(key, previous, "BeginSend")
                }
            SharedThreadComposerDraftIntent.BeginInternalForwardSend ->
                previous.draftKey?.let { key ->
                    flushPendingWrite(key)
                    persistState(key, previous, "BeginInternalForwardSend")
                }
            is SharedThreadComposerDraftIntent.CompleteSend ->
                if (previous.sending) {
                    next.draftKey?.let { key ->
                        cancelPendingWrite(key)
                        synchronized(persistenceWriteLock) {
                            persistence.clear(key)
                            refreshActiveDraftRecords(reason = "CompleteSend")
                        }
                    }
                }
            is SharedThreadComposerDraftIntent.FinishInternalForwardSend ->
                next.draftKey?.let { key ->
                    cancelPendingWrite(key)
                    if (intent.succeeded) {
                        synchronized(persistenceWriteLock) {
                            persistence.clear(key)
                            refreshActiveDraftRecords(reason = "FinishInternalForwardSend")
                        }
                    } else {
                        persistState(key, next, "FinishInternalForwardSendFailure")
                    }
                }
            else -> Unit
        }
    }

    private fun schedulePendingWrite(
        key: String,
        state: SharedThreadComposerDraftState
    ) {
        val (pending, previous) = synchronized(pendingWritesLock) {
            val next =
                PendingThreadComposerDraftWrite(
                    generation = advanceWriteGenerationLocked(key),
                    state = state
                )
            next to pendingWritesByDraftKey.put(key, next)
        }
        previous?.cancellation?.cancel()
        refreshActiveDraftRecords(reason = "ChangeProjected")
        pending.cancellation =
            scheduler.schedule(changeDebounceMillis) {
                val claimed = synchronized(pendingWritesLock) {
                    if (pendingWritesByDraftKey[key] === pending) {
                        pendingWritesByDraftKey.remove(key)
                    } else {
                        null
                    }
                }
                if (claimed != null) {
                    persistStateIfGenerationCurrent(
                        key = key,
                        generation = claimed.generation,
                        state = claimed.state
                    )
                }
            }
    }

    private fun flushPendingWrite(key: String) {
        val pending = synchronized(pendingWritesLock) {
            pendingWritesByDraftKey.remove(key).also {
                advanceWriteGenerationLocked(key)
            }
        } ?: return
        pending.cancellation?.cancel()
        persistState(key, pending.state, "ChangeFlush")
    }

    private fun cancelPendingWrite(key: String) {
        synchronized(pendingWritesLock) {
            pendingWritesByDraftKey.remove(key).also {
                advanceWriteGenerationLocked(key)
            }
        }?.cancellation?.cancel()
    }

    private fun advanceWriteGenerationLocked(key: String): Long {
        val next = (writeGenerationByDraftKey[key] ?: 0L) + 1L
        writeGenerationByDraftKey[key] = next
        return next
    }

    private fun persistState(
        key: String,
        state: SharedThreadComposerDraftState,
        reason: String
    ) {
        synchronized(persistenceWriteLock) {
            persistence.save(
                key = key,
                text = state.seedText,
                asTask = state.seedAsTask,
                uploadedAttachments = state.visiblePersistableThreadComposerDraftAttachments(),
                externalShareSources = state.externalShareSources,
                internalForwardSource = state.internalForwardSource
            )
            refreshActiveDraftRecords(reason = reason)
        }
    }

    private fun persistStateIfGenerationCurrent(
        key: String,
        generation: Long,
        state: SharedThreadComposerDraftState
    ) {
        synchronized(persistenceWriteLock) {
            val isCurrent = synchronized(pendingWritesLock) {
                writeGenerationByDraftKey[key] == generation
            }
            if (!isCurrent) return@synchronized
            persistence.save(
                key = key,
                text = state.seedText,
                asTask = state.seedAsTask,
                uploadedAttachments = state.visiblePersistableThreadComposerDraftAttachments(),
                externalShareSources = state.externalShareSources,
                internalForwardSource = state.internalForwardSource
            )
            refreshActiveDraftRecords(reason = "ChangeDebounced")
        }
    }

    private fun refreshActiveDraftRecords(reason: String) {
        val projected = persistence.activeDraftRecords().associateBy { it.key }.toMutableMap()
        val pendingSnapshot = synchronized(pendingWritesLock) {
            pendingWritesByDraftKey.mapValues { (_, pending) -> pending.state }
        }
        pendingSnapshot.forEach { (key, state) ->
            val record = persistence.recordForKey(key)
            if (state.hasPersistableThreadComposerDraft() && record != null) {
                projected[key] = record
            } else {
                projected.remove(key)
            }
        }
        SharedActiveDraftRecordsState.value = projected.values.toSet()
        diagnosticsLogger?.invoke(
            "thread_composer_draft_active_records reason=$reason count=${SharedActiveDraftRecordsState.value.size} " +
                "records=${SharedActiveDraftRecordsState.value.toThreadComposerDraftDiagnosticsSummary()}"
        )
    }

    fun stageUploadedAttachments(
        draftKey: String?,
        attachments: List<SharedThreadComposerAttachment>
    ) {
        val key = draftKey?.takeIf { it.isNotBlank() } ?: return
        if (attachments.isEmpty()) return
        stagedUploadedAttachmentsByDraftKey[key] =
            (stagedUploadedAttachmentsByDraftKey[key].orEmpty() + attachments)
                .distinctBy { attachment -> attachment.id }
    }

    fun stageExternalShareDraft(
        draftKey: String?,
        text: String,
        attachments: List<SharedThreadComposerAttachment>,
        source: SharedThreadComposerExternalShareSource
    ): ExternalShareComposerDraftStageResult {
        val key = draftKey?.takeIf(String::isNotBlank)
            ?: return ExternalShareComposerDraftStageResult.Rejected(
                "external_share_composer_key_missing"
            )
        flushPendingWrite(key)
        val result = persistence.stageExternalShare(key, text, attachments, source)
        if (result is ExternalShareComposerDraftStageResult.Staged) {
            refreshActiveDraftRecords(reason = "external_share_stage")
        }
        return result
    }

    fun stageInternalForwardDraft(
        draftKey: String?,
        source: SharedThreadComposerInternalForwardSource
    ): InternalForwardComposerDraftStageResult {
        val key = draftKey?.takeIf(String::isNotBlank)
            ?: return InternalForwardComposerDraftStageResult.Rejected("internal_forward_composer_key_missing")
        flushPendingWrite(key)
        val result = persistence.stageInternalForward(key, source)
        if (result is InternalForwardComposerDraftStageResult.Staged) {
            refreshActiveDraftRecords(reason = "internal_forward_stage")
        }
        return result
    }

    private fun consumeStagedUploadedAttachments(draftKey: String?): List<SharedThreadComposerAttachment> {
        val key = draftKey?.takeIf { it.isNotBlank() } ?: return emptyList()
        return stagedUploadedAttachmentsByDraftKey.remove(key).orEmpty()
    }

    private companion object {
        const val DefaultChangeDebounceMillis = 250L
        val SharedActiveDraftRecordsState = MutableStateFlow(emptySet<SharedThreadComposerDraftRecord>())
    }
}

private fun SharedThreadComposerDraftState.hasPersistableThreadComposerDraft(): Boolean =
    seedText.isNotBlank() ||
        seedAsTask ||
        visiblePersistableThreadComposerDraftAttachments().isNotEmpty() ||
        externalShareSources.isNotEmpty() ||
        internalForwardSource != null

private fun SharedThreadComposerDraftState.requiresComposerHostSync(
    previous: SharedThreadComposerDraftState
): Boolean =
    seedText != previous.seedText ||
        seedAsTask != previous.seedAsTask ||
        removedAttachmentIds != previous.removedAttachmentIds ||
        uploadedAttachments != previous.uploadedAttachments ||
        internalForwardSource != previous.internalForwardSource

private fun SharedThreadComposerDraftState.visibleSessionThreadComposerDraftAttachments(): List<SharedThreadComposerAttachment> =
    uploadedAttachments
        .filterNot { attachment -> attachment.id in removedAttachmentIds }
        .distinctBy { attachment -> attachment.id }

private fun SharedThreadComposerDraftIntent.toThreadComposerDraftIntentDiagnosticsLabel(): String =
    when (this) {
        is SharedThreadComposerDraftIntent.Start -> "Start"
        is SharedThreadComposerDraftIntent.Change -> "Change"
        is SharedThreadComposerDraftIntent.InsertMention -> "InsertMention"
        SharedThreadComposerDraftIntent.Flush -> "Flush"
        is SharedThreadComposerDraftIntent.BeginSend -> "BeginSend"
        is SharedThreadComposerDraftIntent.RestoreAfterFailure -> "RestoreAfterFailure"
        is SharedThreadComposerDraftIntent.CompleteInlineSendFailure -> "CompleteInlineSendFailure"
        is SharedThreadComposerDraftIntent.CompleteSend -> "CompleteSend"
        is SharedThreadComposerDraftIntent.ApplyPendingMentionActions -> "ApplyPendingMentionActions"
        is SharedThreadComposerDraftIntent.DismissMentionAction -> "DismissMentionAction"
        is SharedThreadComposerDraftIntent.BeginMentionAction -> "BeginMentionAction"
        is SharedThreadComposerDraftIntent.FinishMentionAction -> "FinishMentionAction"
        SharedThreadComposerDraftIntent.ClearMentionActionNotice -> "ClearMentionActionNotice"
        is SharedThreadComposerDraftIntent.MarkMentionActionRemoving -> "MarkMentionActionRemoving"
        is SharedThreadComposerDraftIntent.RemoveMentionAction -> "RemoveMentionAction"
        is SharedThreadComposerDraftIntent.AddUploadedAttachments -> "AddUploadedAttachments"
        is SharedThreadComposerDraftIntent.AddLocalAttachments -> "AddLocalAttachments"
        is SharedThreadComposerDraftIntent.MarkAttachmentsFailed -> "MarkAttachmentsFailed"
        is SharedThreadComposerDraftIntent.UpdateAttachmentUploadProgress -> "UpdateAttachmentUploadProgress"
        is SharedThreadComposerDraftIntent.RemoveAttachment -> "RemoveAttachment"
        is SharedThreadComposerDraftIntent.RetryAttachment -> "RetryAttachment"
        SharedThreadComposerDraftIntent.RemoveInternalForward -> "RemoveInternalForward"
        SharedThreadComposerDraftIntent.BeginInternalForwardSend -> "BeginInternalForwardSend"
        is SharedThreadComposerDraftIntent.FinishInternalForwardSend -> "FinishInternalForwardSend"
    }

internal fun Set<SharedThreadComposerDraftRecord>.toThreadComposerDraftDiagnosticsSummary(): String =
    sortedWith(
        compareBy<SharedThreadComposerDraftRecord> { it.sendChannelId }
            .thenBy { it.parentMessageId.orEmpty() }
            .thenBy { it.mentionChannelId.orEmpty() }
            .thenBy { it.key }
    ).joinToString(separator = ",") { record ->
        "send=${record.sendChannelId};parent=${record.parentMessageId ?: "_"};mention=${record.mentionChannelId ?: "_"}"
    }

internal sealed interface SharedThreadComposerDraftIntent {
    data class Start(
        val composer: KuiklyThreadComposerPayload,
        val draftKey: String? = null
    ) : SharedThreadComposerDraftIntent
    data class Change(val seedText: String? = null, val seedAsTask: Boolean? = null) : SharedThreadComposerDraftIntent
    data class InsertMention(val handle: String) : SharedThreadComposerDraftIntent
    data object Flush : SharedThreadComposerDraftIntent
    data object BeginSend : SharedThreadComposerDraftIntent
    data class RestoreAfterFailure(
        val seedText: String,
        val seedAsTask: Boolean,
        val failureLabel: String
    ) : SharedThreadComposerDraftIntent
    data object CompleteInlineSendFailure : SharedThreadComposerDraftIntent
    data class CompleteSend(
        val pendingMentionActions: List<KuiklyPendingMentionActionItem> = emptyList()
    ) : SharedThreadComposerDraftIntent
    data class ApplyPendingMentionActions(
        val pendingMentionActions: List<KuiklyPendingMentionActionItem> = emptyList()
    ) : SharedThreadComposerDraftIntent
    data class DismissMentionAction(val resolutionId: String) : SharedThreadComposerDraftIntent
    data class BeginMentionAction(
        val resolutionId: String,
        val state: String
    ) : SharedThreadComposerDraftIntent
    data class FinishMentionAction(
        val resolutionId: String,
        val notice: String,
        val delivered: Boolean,
        val state: String
    ) : SharedThreadComposerDraftIntent
    data object ClearMentionActionNotice : SharedThreadComposerDraftIntent
    data class MarkMentionActionRemoving(val resolutionId: String) : SharedThreadComposerDraftIntent
    data class RemoveMentionAction(val resolutionId: String) : SharedThreadComposerDraftIntent
    data class AddUploadedAttachments(
        val attachments: List<SharedThreadComposerAttachment>,
        val replacedAttachmentIds: Set<String> = emptySet(),
        val replacedAttachmentIdsByUploadedId: Map<String, String> = emptyMap()
    ) : SharedThreadComposerDraftIntent
    data class AddLocalAttachments(
        val attachments: List<SharedThreadComposerAttachment>
    ) : SharedThreadComposerDraftIntent
    data class MarkAttachmentsFailed(
        val attachmentIds: Set<String>,
        val errorCode: String
    ) : SharedThreadComposerDraftIntent
    data class UpdateAttachmentUploadProgress(
        val attachmentId: String,
        val bytesTransferred: Long,
        val bytesTotal: Long?
    ) : SharedThreadComposerDraftIntent
    data class RemoveAttachment(val id: String) : SharedThreadComposerDraftIntent
    data class RetryAttachment(val id: String) : SharedThreadComposerDraftIntent
    data object RemoveInternalForward : SharedThreadComposerDraftIntent
    data object BeginInternalForwardSend : SharedThreadComposerDraftIntent
    data class FinishInternalForwardSend(
        val succeeded: Boolean,
        val failureLabel: String? = null
    ) : SharedThreadComposerDraftIntent
}

internal class SharedThreadComposerDraftStore(
    persistSlices: List<MviPersistSlice<SharedThreadComposerDraftState, SharedThreadComposerDraftIntent>> = emptyList(),
    scope: CoroutineScope = CoroutineScope(EmptyCoroutineContext)
) {
    private val runtime =
        MviStoreRuntime(
            initialState = SharedThreadComposerDraftState(),
            scope = scope,
            reduce = ::reduce,
            persistSlices = persistSlices
        )
    val state: StateFlow<SharedThreadComposerDraftState> = runtime.state

    fun dispatch(intent: SharedThreadComposerDraftIntent) {
        runtime.accept(intent)
    }

    private fun reduce(
        current: SharedThreadComposerDraftState,
        intent: SharedThreadComposerDraftIntent
    ): SharedThreadComposerDraftState =
        when (intent) {
            is SharedThreadComposerDraftIntent.Start ->
                SharedThreadComposerDraftState(
                    instanceId = intent.composer.instanceId,
                    draftKey = intent.draftKey,
                    seedText = intent.composer.seedText,
                    seedAsTask = intent.composer.seedAsTask,
                    syncNonce = intent.composer.syncNonce,
                    focusRequestNonce = intent.composer.focusRequestNonce
                )
            is SharedThreadComposerDraftIntent.Change ->
                (intent.seedText ?: current.seedText).let { nextText ->
                    current.copy(
                        seedText = nextText,
                        seedAsTask = intent.seedAsTask ?: current.seedAsTask,
                        sending = false,
                        failureLabel = null,
                        externalShareSources =
                            if (
                                nextText.isBlank() &&
                                current.visibleSessionThreadComposerDraftAttachments().none { attachment ->
                                    attachment.source == "external_share"
                                }
                            ) {
                                emptyList()
                            } else {
                                current.externalShareSources
                            }
                    )
                }
            is SharedThreadComposerDraftIntent.InsertMention ->
                current.copy(
                    seedText = current.seedText.withInsertedThreadMention(intent.handle),
                    syncNonce = current.syncNonce + 1,
                    focusRequestNonce = current.focusRequestNonce + 1,
                    sending = false,
                    failureLabel = null
                )
            SharedThreadComposerDraftIntent.Flush ->
                current.copy(persistBoundaryNonce = current.persistBoundaryNonce + 1)
            SharedThreadComposerDraftIntent.BeginSend ->
                current.copy(
                    seedText = "",
                    seedAsTask = false,
                    syncNonce = current.syncNonce + 1,
                    // Sending clears the current value, but it remains an active composer
                    // interaction. Reassert focus at the same synchronous boundary so a send
                    // click cannot turn the host clear into an editor blur/IME dismissal. Do not
                    // defer this to CompleteSend: that callback may arrive after the user has
                    // deliberately tapped outside the composer.
                    focusRequestNonce = current.focusRequestNonce + 1,
                    sending = true,
                    failureLabel = null,
                    removedAttachmentIds = emptySet(),
                    uploadedAttachments = emptyList(),
                    externalShareSources = emptyList(),
                    internalForwardSource = null,
                    mentionActionNotice = null,
                    mentionActionStates = emptyMap(),
                    removingMentionActionIds = emptySet(),
                    executingMentionActionIds = emptySet(),
                    executingMentionActionStates = emptyMap()
                )
            is SharedThreadComposerDraftIntent.RestoreAfterFailure ->
                current.copy(
                    seedText = intent.seedText,
                    seedAsTask = intent.seedAsTask,
                    syncNonce = current.syncNonce + 1,
                    sending = false,
                    failureLabel = intent.failureLabel
                )
            SharedThreadComposerDraftIntent.CompleteInlineSendFailure -> {
                val shouldClearComposer = current.sending
                current.copy(
                    seedText = if (shouldClearComposer) "" else current.seedText,
                    seedAsTask = if (shouldClearComposer) false else current.seedAsTask,
                    syncNonce = current.syncNonce + 1,
                    sending = false,
                    failureLabel = null,
                    removedAttachmentIds = if (shouldClearComposer) emptySet() else current.removedAttachmentIds,
                    uploadedAttachments = if (shouldClearComposer) emptyList() else current.uploadedAttachments,
                    externalShareSources = if (shouldClearComposer) emptyList() else current.externalShareSources,
                    internalForwardSource = if (shouldClearComposer) null else current.internalForwardSource,
                    mentionActionNotice = null,
                    mentionActionStates = emptyMap(),
                    removingMentionActionIds = emptySet(),
                    executingMentionActionIds = emptySet(),
                    executingMentionActionStates = emptyMap()
                )
            }
            is SharedThreadComposerDraftIntent.CompleteSend -> {
                val shouldClearComposer = current.sending
                current.copy(
                    seedText = if (shouldClearComposer) "" else current.seedText,
                    seedAsTask = if (shouldClearComposer) false else current.seedAsTask,
                    syncNonce = current.syncNonce + 1,
                    sending = false,
                    failureLabel = null,
                    removedAttachmentIds = if (shouldClearComposer) emptySet() else current.removedAttachmentIds,
                    uploadedAttachments = if (shouldClearComposer) emptyList() else current.uploadedAttachments,
                    externalShareSources = if (shouldClearComposer) emptyList() else current.externalShareSources,
                    internalForwardSource = if (shouldClearComposer) null else current.internalForwardSource,
                    pendingMentionActions = intent.pendingMentionActions,
                    mentionActionNotice = null,
                    mentionActionStates = emptyMap(),
                    removingMentionActionIds = emptySet(),
                    executingMentionActionIds = emptySet(),
                    executingMentionActionStates = emptyMap()
                )
            }
            is SharedThreadComposerDraftIntent.ApplyPendingMentionActions ->
                current.copy(
                    syncNonce = current.syncNonce + 1,
                    pendingMentionActions = intent.pendingMentionActions,
                    mentionActionNotice = null,
                    mentionActionStates = emptyMap(),
                    removingMentionActionIds = emptySet(),
                    executingMentionActionIds = emptySet(),
                    executingMentionActionStates = emptyMap()
                )
            is SharedThreadComposerDraftIntent.DismissMentionAction ->
                current.copy(
                    syncNonce = current.syncNonce + 1,
                    pendingMentionActions = current.pendingMentionActions.filterNot { it.resolutionId == intent.resolutionId },
                    mentionActionNotice = null,
                    mentionActionStates = current.mentionActionStates - intent.resolutionId,
                    removingMentionActionIds = current.removingMentionActionIds - intent.resolutionId,
                    executingMentionActionIds = current.executingMentionActionIds - intent.resolutionId,
                    executingMentionActionStates = current.executingMentionActionStates - intent.resolutionId
                )
            is SharedThreadComposerDraftIntent.BeginMentionAction ->
                current.copy(
                    syncNonce = current.syncNonce + 1,
                    mentionActionNotice = null,
                    executingMentionActionIds = current.executingMentionActionIds + intent.resolutionId,
                    executingMentionActionStates = current.executingMentionActionStates + (intent.resolutionId to intent.state)
                )
            is SharedThreadComposerDraftIntent.FinishMentionAction ->
                current.copy(
                    syncNonce = current.syncNonce + 1,
                    pendingMentionActions = current.pendingMentionActions,
                    mentionActionNotice = if (intent.delivered) null else intent.notice,
                    mentionActionStates =
                        if (intent.delivered) {
                            current.mentionActionStates + (intent.resolutionId to intent.state)
                        } else {
                            current.mentionActionStates - intent.resolutionId
                        },
                    removingMentionActionIds = current.removingMentionActionIds - intent.resolutionId,
                    executingMentionActionIds = current.executingMentionActionIds - intent.resolutionId,
                    executingMentionActionStates = current.executingMentionActionStates - intent.resolutionId
                )
            SharedThreadComposerDraftIntent.ClearMentionActionNotice ->
                current.copy(mentionActionNotice = null)
            is SharedThreadComposerDraftIntent.MarkMentionActionRemoving ->
                current.copy(
                    syncNonce = current.syncNonce + 1,
                    removingMentionActionIds = current.removingMentionActionIds + intent.resolutionId
                )
            is SharedThreadComposerDraftIntent.RemoveMentionAction ->
                current.copy(
                    syncNonce = current.syncNonce + 1,
                    pendingMentionActions = current.pendingMentionActions.filterNot { it.resolutionId == intent.resolutionId },
                    mentionActionStates = current.mentionActionStates - intent.resolutionId,
                    removingMentionActionIds = current.removingMentionActionIds - intent.resolutionId,
                    executingMentionActionIds = current.executingMentionActionIds - intent.resolutionId,
                    executingMentionActionStates = current.executingMentionActionStates - intent.resolutionId
                )
            is SharedThreadComposerDraftIntent.AddUploadedAttachments -> {
                val visibleAttachments =
                    intent.attachments
                        .filterNot { attachment ->
                            attachment.id in current.removedAttachmentIds ||
                                intent.replacedAttachmentIdsByUploadedId[attachment.id] in current.removedAttachmentIds
                        }
                val replacedVisibleAttachmentIds =
                    visibleAttachments
                        .mapNotNull { attachment -> intent.replacedAttachmentIdsByUploadedId[attachment.id] }
                        .toSet()
                val nextUploadedAttachments =
                    if (intent.replacedAttachmentIdsByUploadedId.isEmpty()) {
                        current.uploadedAttachments + visibleAttachments
                    } else {
                        val replacementsBySourceId =
                            visibleAttachments
                                .mapNotNull { attachment ->
                                    intent.replacedAttachmentIdsByUploadedId[attachment.id]
                                        ?.let { sourceId -> sourceId to attachment }
                                }
                                .groupBy(
                                    keySelector = { (sourceId, _) -> sourceId },
                                    valueTransform = { (_, attachment) -> attachment }
                                )
                        val unmappedAttachments =
                            visibleAttachments.filter { attachment ->
                                attachment.id !in intent.replacedAttachmentIdsByUploadedId
                            }
                        current.uploadedAttachments.flatMap { attachment ->
                            replacementsBySourceId[attachment.id] ?: listOf(attachment)
                        } + unmappedAttachments
                    }
                current.copy(
                    syncNonce = current.syncNonce + 1,
                    failureLabel = null,
                    removedAttachmentIds =
                        current.removedAttachmentIds +
                            intent.replacedAttachmentIds +
                            replacedVisibleAttachmentIds,
                    uploadedAttachments =
                        nextUploadedAttachments
                            .distinctBy { attachment -> attachment.id }
                )
            }
            is SharedThreadComposerDraftIntent.AddLocalAttachments ->
                intent.attachments
                    .map { attachment -> attachment.id }
                    .toSet()
                    .let { localAttachmentIds ->
                        current.copy(
                            syncNonce = current.syncNonce + 1,
                            sending = false,
                            failureLabel = null,
                            removedAttachmentIds = current.removedAttachmentIds - localAttachmentIds,
                            uploadedAttachments =
                                (current.uploadedAttachments.map { attachment ->
                                    intent.attachments.firstOrNull { replacement -> replacement.id == attachment.id }
                                        ?: attachment
                                } + intent.attachments.filter { attachment ->
                                    current.uploadedAttachments.none { existing -> existing.id == attachment.id }
                                })
                                    .distinctBy { attachment -> attachment.id }
                        )
                    }
            is SharedThreadComposerDraftIntent.MarkAttachmentsFailed ->
                current.copy(
                    syncNonce = current.syncNonce + 1,
                    sending = false,
                    failureLabel = null,
                    uploadedAttachments =
                        current.uploadedAttachments.map { attachment ->
                            if (attachment.id in intent.attachmentIds) {
                                attachment.copy(status = "failed", errorCode = intent.errorCode)
                            } else {
                                attachment
                            }
                        }
                )
            is SharedThreadComposerDraftIntent.UpdateAttachmentUploadProgress ->
                current.copy(
                    syncNonce = current.syncNonce + 1,
                    uploadedAttachments =
                        current.uploadedAttachments.map { attachment ->
                            if (
                                attachment.id == intent.attachmentId &&
                                attachment.status == "uploading"
                            ) {
                                val total = intent.bytesTotal?.takeIf { it > 0L }
                                    ?: attachment.uploadBytesTotal?.takeIf { it > 0L }
                                val transferred =
                                    maxOf(attachment.uploadBytesTransferred, intent.bytesTransferred.coerceAtLeast(0L))
                                        .let { value -> if (total != null) value.coerceAtMost(total) else value }
                                attachment.copy(
                                    uploadBytesTransferred = transferred,
                                    uploadBytesTotal = total
                                )
                            } else {
                                attachment
                            }
                        }
                )
            is SharedThreadComposerDraftIntent.RemoveAttachment ->
                (current.removedAttachmentIds + intent.id).let { removedIds ->
                    current.copy(
                        syncNonce = current.syncNonce + 1,
                        sending = false,
                        removedAttachmentIds = removedIds,
                        externalShareSources =
                            if (
                                current.seedText.isBlank() &&
                                current.uploadedAttachments.none { attachment ->
                                    attachment.source == "external_share" && attachment.id !in removedIds
                                }
                            ) {
                                emptyList()
                            } else {
                                current.externalShareSources
                            }
                    )
                }
            is SharedThreadComposerDraftIntent.RetryAttachment ->
                current.copy(
                    syncNonce = current.syncNonce + 1,
                    sending = false,
                    removedAttachmentIds = current.removedAttachmentIds - intent.id
                )
            SharedThreadComposerDraftIntent.RemoveInternalForward ->
                current.copy(
                    syncNonce = current.syncNonce + 1,
                    sending = false,
                    failureLabel = null,
                    internalForwardSource = null
                )
            SharedThreadComposerDraftIntent.BeginInternalForwardSend ->
                current.copy(
                    sending = true,
                    failureLabel = null
                )
            is SharedThreadComposerDraftIntent.FinishInternalForwardSend ->
                if (intent.succeeded) {
                    current.copy(
                        seedText = "",
                        seedAsTask = false,
                        syncNonce = current.syncNonce + 1,
                        focusRequestNonce = current.focusRequestNonce + 1,
                        sending = false,
                        failureLabel = null,
                        internalForwardSource = null
                    )
                } else {
                    current.copy(
                        sending = false,
                        failureLabel = intent.failureLabel
                    )
                }
        }
}

private fun SharedThreadComposerInternalForwardSource.isValid(): Boolean =
    operationId.isNotBlank() &&
        accountId.isNotBlank() &&
        serverId.isNotBlank() &&
        sourceChannelId.isNotBlank() &&
        destinationChannelId.isNotBlank() &&
        destinationKind.isNotBlank() &&
        messageIds.isNotEmpty() &&
        messageIds.all(String::isNotBlank) &&
        mode in setOf("merged", "individual") &&
        sourceLabel.isNotBlank() &&
        attachmentCount >= 0

private fun SharedThreadComposerDraftState.visiblePersistableThreadComposerDraftAttachments(): List<SharedThreadComposerAttachment> =
    uploadedAttachments
        .filterNot { attachment -> attachment.id in removedAttachmentIds }
        .persistableThreadComposerDraftAttachments()

private fun List<SharedThreadComposerAttachment>.persistableThreadComposerDraftAttachments(): List<SharedThreadComposerAttachment> =
    asSequence()
        .filter { attachment -> attachment.id.isNotBlank() }
        .filter { attachment ->
            attachment.status == "ready" || attachment.isValidExternalShareQueuedAttachment()
        }
        .distinctBy { attachment -> attachment.id }
        .toList()

private fun SharedThreadComposerAttachment.isValidExternalShareQueuedAttachment(): Boolean =
    source == "external_share" &&
        status in setOf("queued", "uploading", "failed") &&
        !fileRef.isNullOrBlank() &&
        !externalShareToken.isNullOrBlank() &&
        !externalShareAccountId.isNullOrBlank() &&
        !externalShareServerId.isNullOrBlank()

private fun List<SharedThreadComposerExternalShareSource>.validExternalShareComposerSources(): List<SharedThreadComposerExternalShareSource> =
    asSequence()
        .map { source ->
            source.copy(
                token = source.token.trim(),
                accountId = source.accountId.trim(),
                serverId = source.serverId.trim()
            )
        }
        .filter { source ->
            source.token.isNotBlank() && source.accountId.isNotBlank() && source.serverId.isNotBlank()
        }
        .distinctBy { source -> source.token }
        .toList()

internal fun String.withInsertedThreadMention(handle: String): String {
    val token = handle.toThreadMentionToken() ?: return this
    val mention = "@$token "
    if (isBlank()) return mention
    return if (last().isWhitespace()) {
        this + mention
    } else {
        "$this $mention"
    }
}

internal fun String.toThreadMentionToken(): String? =
    trim()
        .removePrefix("@")
        .takeIf { it.isNotBlank() }
        ?.let { raw ->
            raw.takeIf { value -> value.none { char -> char.isWhitespace() } }
                ?: raw.lowercase()
                    .replace(Regex("""\s+"""), "-")
                    .filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
        }
        ?.takeIf { it.isNotBlank() }

internal class SharedThreadComposerDraftViewModel(
    persistSlices: List<MviPersistSlice<SharedThreadComposerDraftState, SharedThreadComposerDraftIntent>> = emptyList()
) : ViewModel() {
    private val storeDelegate =
        lazy {
            SharedThreadComposerDraftStore(persistSlices = persistSlices, scope = viewModelScope)
        }
    private val store: SharedThreadComposerDraftStore by storeDelegate
    val state: StateFlow<SharedThreadComposerDraftState>
        get() = store.state

    fun dispatch(intent: SharedThreadComposerDraftIntent) {
        store.dispatch(intent)
    }
}
