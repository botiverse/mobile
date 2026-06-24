package ai.slock.compose

import ai.slock.compose.data.MviPersistSlice
import ai.slock.compose.data.MviStoreRuntime
import ai.slock.compose.data.SharedPreferenceStore
import com.tencent.kuikly.lifecycle.ViewModel
import com.tencent.kuikly.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.EmptyCoroutineContext

internal data class SharedThreadComposerDraftState(
    val instanceId: String = "",
    val draftKey: String? = null,
    val seedText: String = "",
    val seedAsTask: Boolean = false,
    val syncNonce: Int = 0,
    val focusRequestNonce: Int = 0,
    val sending: Boolean = false,
    val failureLabel: String? = null,
    val removedAttachmentIds: Set<String> = emptySet(),
    val uploadedAttachments: List<SharedThreadComposerAttachment> = emptyList(),
    val pendingMentionActions: List<KuiklyPendingMentionActionItem> = emptyList(),
    val mentionActionNotice: String? = null,
    val executingMentionActionIds: Set<String> = emptySet()
)

internal data class SharedThreadComposerPersistedDraft(
    val text: String = "",
    val asTask: Boolean = false
)

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
}

internal data class SharedThreadComposerDraftProjection(
    private val rootConversationIds: Set<String> = emptySet()
) {
    fun hasRootDraft(conversationId: String): Boolean =
        conversationId in rootConversationIds

    companion object {
        val Empty = SharedThreadComposerDraftProjection()
    }
}

internal fun Set<SharedThreadComposerDraftRecord>.toSharedThreadComposerDraftProjection(): SharedThreadComposerDraftProjection =
    SharedThreadComposerDraftProjection(
        rootConversationIds = mapNotNull { record ->
            record.sendChannelId.takeIf { record.isRootComposer }
        }.toSet()
    )

internal class SharedThreadComposerDraftPersistence(
    private val store: SharedPreferenceStore,
    private val diagnosticsLogger: ((String) -> Unit)? = null
) {
    fun read(key: String): SharedThreadComposerPersistedDraft? {
        val text = store.getString(textKey(key)) ?: return null
        return SharedThreadComposerPersistedDraft(
            text = text,
            asTask = store.getString(asTaskKey(key)) == "true"
        )
    }

    fun save(key: String, text: String, asTask: Boolean) {
        diagnosticsLogger?.invoke(
            "thread_composer_draft_persist_save key=$key textLen=${text.length} " +
                "textBlank=${text.isBlank()} asTask=$asTask activeBefore=${activeKeys().sorted().joinToString(separator = ",")}"
        )
        if (text.isBlank() && !asTask) {
            clear(key)
            return
        }
        store.putString(textKey(key), text)
        store.putString(asTaskKey(key), asTask.toString())
        addActiveKey(key)
        diagnosticsLogger?.invoke(
            "thread_composer_draft_persist_saved key=$key activeAfter=${activeKeys().sorted().joinToString(separator = ",")}"
        )
    }

    fun clear(key: String) {
        diagnosticsLogger?.invoke(
            "thread_composer_draft_persist_clear key=$key activeBefore=${activeKeys().sorted().joinToString(separator = ",")}"
        )
        store.putString(textKey(key), null)
        store.putString(asTaskKey(key), null)
        removeActiveKey(key)
        diagnosticsLogger?.invoke(
            "thread_composer_draft_persist_cleared key=$key activeAfter=${activeKeys().sorted().joinToString(separator = ",")}"
        )
    }

    fun activeDraftRecords(): Set<SharedThreadComposerDraftRecord> =
        activeKeys()
            .mapNotNull { key -> key.toSharedThreadComposerDraftRecord() }
            .toSet()

    private fun textKey(key: String): String = "threadComposerDraft:v1:$key:text"
    private fun asTaskKey(key: String): String = "threadComposerDraft:v1:$key:asTask"

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
    }
}

internal class SharedThreadComposerDraftPersistSlice(
    private val persistence: SharedThreadComposerDraftPersistence,
    private val diagnosticsLogger: ((String) -> Unit)? = null
) : MviPersistSlice<SharedThreadComposerDraftState, SharedThreadComposerDraftIntent> {
    val activeDraftRecordsState: StateFlow<Set<SharedThreadComposerDraftRecord>> =
        SharedActiveDraftRecordsState.asStateFlow()

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
                val persistedDraft =
                    intent.draftKey
                        ?.takeIf { it.isNotBlank() }
                        ?.let(persistence::read)
                if (persistedDraft == null) {
                    reduced
                } else {
                    reduced.copy(
                        seedText = persistedDraft.text,
                        seedAsTask = persistedDraft.asTask
                    )
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
            is SharedThreadComposerDraftIntent.Change,
            is SharedThreadComposerDraftIntent.InsertMention,
            is SharedThreadComposerDraftIntent.RestoreAfterFailure ->
                next.draftKey?.let { key ->
                    persistence.save(key, next.seedText, next.seedAsTask)
                    refreshActiveDraftRecords(reason = intent.toThreadComposerDraftIntentDiagnosticsLabel())
                }
            is SharedThreadComposerDraftIntent.CompleteSend ->
                if (previous.sending) {
                    next.draftKey?.let { key ->
                        persistence.clear(key)
                        refreshActiveDraftRecords(reason = "CompleteSend")
                    }
                }
            else -> Unit
        }
    }

    private fun refreshActiveDraftRecords(reason: String) {
        val records = persistence.activeDraftRecords()
        SharedActiveDraftRecordsState.value = records
        diagnosticsLogger?.invoke(
            "thread_composer_draft_active_records reason=$reason count=${records.size} " +
                "records=${records.toThreadComposerDraftDiagnosticsSummary()}"
        )
    }

    private companion object {
        val SharedActiveDraftRecordsState = MutableStateFlow(emptySet<SharedThreadComposerDraftRecord>())
    }
}

private fun SharedThreadComposerDraftIntent.toThreadComposerDraftIntentDiagnosticsLabel(): String =
    when (this) {
        is SharedThreadComposerDraftIntent.Start -> "Start"
        is SharedThreadComposerDraftIntent.Change -> "Change"
        is SharedThreadComposerDraftIntent.InsertMention -> "InsertMention"
        is SharedThreadComposerDraftIntent.BeginSend -> "BeginSend"
        is SharedThreadComposerDraftIntent.RestoreAfterFailure -> "RestoreAfterFailure"
        is SharedThreadComposerDraftIntent.CompleteInlineSendFailure -> "CompleteInlineSendFailure"
        is SharedThreadComposerDraftIntent.CompleteSend -> "CompleteSend"
        is SharedThreadComposerDraftIntent.ApplyPendingMentionActions -> "ApplyPendingMentionActions"
        is SharedThreadComposerDraftIntent.DismissMentionAction -> "DismissMentionAction"
        is SharedThreadComposerDraftIntent.BeginMentionAction -> "BeginMentionAction"
        is SharedThreadComposerDraftIntent.FinishMentionAction -> "FinishMentionAction"
        is SharedThreadComposerDraftIntent.AddUploadedAttachments -> "AddUploadedAttachments"
        is SharedThreadComposerDraftIntent.AddLocalAttachments -> "AddLocalAttachments"
        is SharedThreadComposerDraftIntent.MarkAttachmentsFailed -> "MarkAttachmentsFailed"
        is SharedThreadComposerDraftIntent.RemoveAttachment -> "RemoveAttachment"
        is SharedThreadComposerDraftIntent.RetryAttachment -> "RetryAttachment"
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
    data class BeginMentionAction(val resolutionId: String) : SharedThreadComposerDraftIntent
    data class FinishMentionAction(
        val resolutionId: String,
        val notice: String,
        val delivered: Boolean
    ) : SharedThreadComposerDraftIntent
    data class AddUploadedAttachments(
        val attachments: List<SharedThreadComposerAttachment>,
        val replacedAttachmentIds: Set<String> = emptySet()
    ) : SharedThreadComposerDraftIntent
    data class AddLocalAttachments(
        val attachments: List<SharedThreadComposerAttachment>
    ) : SharedThreadComposerDraftIntent
    data class MarkAttachmentsFailed(
        val attachmentIds: Set<String>,
        val errorCode: String
    ) : SharedThreadComposerDraftIntent
    data class RemoveAttachment(val id: String) : SharedThreadComposerDraftIntent
    data class RetryAttachment(val id: String) : SharedThreadComposerDraftIntent
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
                current.copy(
                    seedText = intent.seedText ?: current.seedText,
                    seedAsTask = intent.seedAsTask ?: current.seedAsTask,
                    sending = false,
                    failureLabel = null
                )
            is SharedThreadComposerDraftIntent.InsertMention ->
                current.copy(
                    seedText = current.seedText.withInsertedThreadMention(intent.handle),
                    syncNonce = current.syncNonce + 1,
                    focusRequestNonce = current.focusRequestNonce + 1,
                    sending = false,
                    failureLabel = null
                )
            SharedThreadComposerDraftIntent.BeginSend ->
                current.copy(
                    seedText = "",
                    seedAsTask = false,
                    syncNonce = current.syncNonce + 1,
                    sending = true,
                    failureLabel = null,
                    removedAttachmentIds = emptySet(),
                    uploadedAttachments = emptyList(),
                    mentionActionNotice = null,
                    executingMentionActionIds = emptySet()
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
                    mentionActionNotice = null,
                    executingMentionActionIds = emptySet()
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
                    pendingMentionActions = intent.pendingMentionActions,
                    mentionActionNotice = null,
                    executingMentionActionIds = emptySet()
                )
            }
            is SharedThreadComposerDraftIntent.ApplyPendingMentionActions ->
                current.copy(
                    syncNonce = current.syncNonce + 1,
                    pendingMentionActions = intent.pendingMentionActions,
                    mentionActionNotice = null,
                    executingMentionActionIds = emptySet()
                )
            is SharedThreadComposerDraftIntent.DismissMentionAction ->
                current.copy(
                    syncNonce = current.syncNonce + 1,
                    pendingMentionActions = current.pendingMentionActions.filterNot { it.resolutionId == intent.resolutionId },
                    mentionActionNotice = null,
                    executingMentionActionIds = current.executingMentionActionIds - intent.resolutionId
                )
            is SharedThreadComposerDraftIntent.BeginMentionAction ->
                current.copy(
                    syncNonce = current.syncNonce + 1,
                    mentionActionNotice = null,
                    executingMentionActionIds = current.executingMentionActionIds + intent.resolutionId
                )
            is SharedThreadComposerDraftIntent.FinishMentionAction ->
                current.copy(
                    syncNonce = current.syncNonce + 1,
                    pendingMentionActions =
                        if (intent.delivered) {
                            current.pendingMentionActions.filterNot { it.resolutionId == intent.resolutionId }
                        } else {
                            current.pendingMentionActions
                        },
                    mentionActionNotice = intent.notice,
                    executingMentionActionIds = current.executingMentionActionIds - intent.resolutionId
                )
            is SharedThreadComposerDraftIntent.AddUploadedAttachments -> {
                val uploadedIds = intent.attachments.map { attachment -> attachment.id }.toSet()
                current.copy(
                    syncNonce = current.syncNonce + 1,
                    failureLabel = null,
                    removedAttachmentIds = (current.removedAttachmentIds + intent.replacedAttachmentIds) - uploadedIds,
                    uploadedAttachments =
                        (current.uploadedAttachments + intent.attachments)
                            .distinctBy { attachment -> attachment.id }
                )
            }
            is SharedThreadComposerDraftIntent.AddLocalAttachments ->
                current.copy(
                    syncNonce = current.syncNonce + 1,
                    sending = false,
                    failureLabel = null,
                    uploadedAttachments =
                        (current.uploadedAttachments + intent.attachments)
                            .distinctBy { attachment -> attachment.id }
                )
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
            is SharedThreadComposerDraftIntent.RemoveAttachment ->
                current.copy(
                    syncNonce = current.syncNonce + 1,
                    sending = false,
                    removedAttachmentIds = current.removedAttachmentIds + intent.id
                )
            is SharedThreadComposerDraftIntent.RetryAttachment ->
                current.copy(
                    syncNonce = current.syncNonce + 1,
                    sending = false,
                    removedAttachmentIds = current.removedAttachmentIds - intent.id
                )
        }
}

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

private fun String.toThreadMentionToken(): String? =
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
