package ai.slock.compose

import com.tencent.kuikly.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class SharedThreadComposerDraftState(
    val instanceId: String = "",
    val seedText: String = "",
    val seedAsTask: Boolean = false,
    val syncNonce: Int = 0,
    val sending: Boolean = false,
    val failureLabel: String? = null,
    val removedAttachmentIds: Set<String> = emptySet(),
    val uploadedAttachments: List<SharedThreadComposerAttachment> = emptyList(),
    val pendingMentionActions: List<KuiklyPendingMentionActionItem> = emptyList(),
    val mentionActionNotice: String? = null,
    val executingMentionActionIds: Set<String> = emptySet()
)

internal sealed interface SharedThreadComposerDraftIntent {
    data class Start(val composer: KuiklyThreadComposerPayload) : SharedThreadComposerDraftIntent
    data class Change(val seedText: String? = null, val seedAsTask: Boolean? = null) : SharedThreadComposerDraftIntent
    data object BeginSend : SharedThreadComposerDraftIntent
    data class RestoreAfterFailure(
        val seedText: String,
        val seedAsTask: Boolean,
        val failureLabel: String
    ) : SharedThreadComposerDraftIntent
    data class CompleteSend(
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
        val attachments: List<SharedThreadComposerAttachment>
    ) : SharedThreadComposerDraftIntent
    data class RemoveAttachment(val id: String) : SharedThreadComposerDraftIntent
    data class RetryAttachment(val id: String) : SharedThreadComposerDraftIntent
}

internal class SharedThreadComposerDraftStore {
    private val _state = MutableStateFlow(SharedThreadComposerDraftState())
    val state: StateFlow<SharedThreadComposerDraftState> = _state.asStateFlow()

    fun dispatch(intent: SharedThreadComposerDraftIntent) {
        val current = _state.value
        _state.value =
            when (intent) {
                is SharedThreadComposerDraftIntent.Start ->
                    SharedThreadComposerDraftState(
                        instanceId = intent.composer.instanceId,
                        seedText = intent.composer.seedText,
                        seedAsTask = intent.composer.seedAsTask,
                        syncNonce = intent.composer.syncNonce
                    )
                is SharedThreadComposerDraftIntent.Change -> {
                    if (current.sending) {
                        current
                    } else {
                        current.copy(
                            seedText = intent.seedText ?: current.seedText,
                            seedAsTask = intent.seedAsTask ?: current.seedAsTask,
                            failureLabel = null
                        )
                    }
                }
                SharedThreadComposerDraftIntent.BeginSend ->
                    current.copy(
                        seedText = "",
                        seedAsTask = false,
                        syncNonce = current.syncNonce + 1,
                        sending = true,
                        failureLabel = null,
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
                is SharedThreadComposerDraftIntent.CompleteSend ->
                    current.copy(
                        seedText = "",
                        seedAsTask = false,
                        syncNonce = current.syncNonce + 1,
                        sending = false,
                        failureLabel = null,
                        removedAttachmentIds = emptySet(),
                        uploadedAttachments = emptyList(),
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
                        removedAttachmentIds = current.removedAttachmentIds - uploadedIds,
                        uploadedAttachments =
                            (current.uploadedAttachments + intent.attachments)
                                .distinctBy { attachment -> attachment.id }
                    )
                }
                is SharedThreadComposerDraftIntent.RemoveAttachment ->
                    current.copy(
                        syncNonce = current.syncNonce + 1,
                        removedAttachmentIds = current.removedAttachmentIds + intent.id
                    )
                is SharedThreadComposerDraftIntent.RetryAttachment ->
                    current.copy(
                        syncNonce = current.syncNonce + 1,
                        removedAttachmentIds = current.removedAttachmentIds - intent.id
                    )
            }
    }
}

internal class SharedThreadComposerDraftViewModel : ViewModel() {
    private val store = SharedThreadComposerDraftStore()
    val state: StateFlow<SharedThreadComposerDraftState> = store.state

    fun dispatch(intent: SharedThreadComposerDraftIntent) {
        store.dispatch(intent)
    }
}
