package build.raft.app.thread

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import build.raft.app.data.ForwardMessageBatchResult
import build.raft.app.data.ForwardMessageSendMode
import build.raft.app.data.ForwardMessageTarget
import build.raft.app.data.ForwardMessageTargetRepository
import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import build.raft.app.raftBottomSafeAreaPadding
import build.raft.app.reduce
import build.raft.app.thread.reduce
import build.raft.app.traceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// The forward picker, its note and its success toast (ThreadPageContentContent), verbatim and at the same place.
@Composable
@NonRestartableComposable
internal fun ThreadForwardOverlays(
    shareState: ThreadShareForwardState,
    activeModalState: MutableState<String?>,
    threadInstanceKey: String,
    payload: RaftThreadScreenPayload,
    writeActionsEnabled: Boolean,
    forwardServerId: ServerId?,
    forwardSourceChannelId: ChannelId?,
    forwardTargetRepository: ForwardMessageTargetRepository?,
    forwardMessageSelection: ThreadForwardMessageSelection,
    forwardTargetItems: List<RaftForwardTargetPickerItem<ForwardMessageTarget>>,
    forwardConversationItems: List<RaftForwardTargetPickerItem<ForwardMessageTarget>>,
    shareExportScope: CoroutineScope,
    clearShareSelection: () -> Unit,
    onThreadEvent: ThreadEventHandler,
    onForwardMessagesDelivered: (ForwardMessageBatchResult) -> Unit,
    onOpenForwardDestination: (ThreadForwardSuccessBanner) -> Unit,
) {
    var activeModal by activeModalState
    val visibleForwardPickerSession = shareState.forwardPickerFlow.sessionKey
    val forwardPickerBottomSafeArea = raftBottomSafeAreaPadding()
    if ((activeModal == "forwardTargets" || activeModal == "forwardNote") && visibleForwardPickerSession != null) {
            RaftForwardTargetPicker(
                messageCount = forwardMessageSelection.forwardableMessages.size,
                skippedCount = forwardMessageSelection.skippedCount,
                nestedForwardCount = forwardMessageSelection.nestedForwardCount,
                // Keep the search field composed while results load: swapping it
                // for the read-only placeholder destroys the focused field and
                // closes the IME on every keystroke (verify feedback #255).
                searchInputInteractive = shareState.forwardPickerFlow.step == RaftForwardPickerStep.Targets,
                bottomSafeArea = forwardPickerBottomSafeArea,
                sourceLabel = payload.threadForwardSourceLabel(),
                singleTapContinues = true,
                state =
                    RaftForwardTargetPickerUiState(
                        sessionKey = visibleForwardPickerSession,
                        query = shareState.forwardPickerFlow.query,
                        items = forwardTargetItems,
                        selectedKey = shareState.forwardPickerFlow.selectedKey,
                        selectedItems = shareState.forwardSelectedReceipts.map { it.target.toRaftForwardTargetPickerItem() },
                        loadState = shareState.forwardPickerFlow.loadState,
                        errorCode = shareState.forwardPickerFlow.errorCode,
                        canLoadMore = shareState.forwardDirectoryNextCursor != null,
                        appending = shareState.forwardPickerFlow.appending,
                        interactive = shareState.forwardPickerFlow.rowsInteractive,
                        dismissEnabled = shareState.forwardPickerFlow.dismissEnabled,
                        requestGeneration = shareState.forwardPickerFlow.requestGeneration,
                        conversationItems = forwardConversationItems
                    ),
                onIntent = { intent ->
                    when (intent) {
                        is RaftForwardTargetPickerIntent.QueryChanged -> {
                            shareState.forwardPickerFlow.sessionKey?.let { session ->
                                forwardTargetRepository?.releaseDirectorySnapshot(session.directorySessionId())
                            }
                            shareState.forwardPickerFlow =
                                shareState.forwardPickerFlow.reduce(
                                    RaftForwardPickerFlowEvent.QueryChanged(intent.query)
                                )
                            shareState.forwardDirectoryNextCursor = null
                        }
                        RaftForwardTargetPickerIntent.Retry -> {
                            shareState.forwardPickerFlow =
                                shareState.forwardPickerFlow.reduce(
                                    RaftForwardPickerFlowEvent.Retry(
                                        appending = shareState.forwardPickerFlow.query.isBlank() && shareState.forwardDirectoryNextCursor != null
                                    )
                                )
                        }
                        RaftForwardTargetPickerIntent.LoadMore -> {
                            if (shareState.forwardDirectoryNextCursor != null && !shareState.forwardPickerFlow.appending) {
                                shareState.forwardPickerFlow =
                                    shareState.forwardPickerFlow.reduce(RaftForwardPickerFlowEvent.LoadMore)
                            }
                        }
                        RaftForwardTargetPickerIntent.Dismiss -> {
                            shareState.forwardPickerFlow.sessionKey?.let { session ->
                                forwardTargetRepository?.releaseDirectorySnapshot(session.directorySessionId())
                            }
                            shareState.forwardPickerFlow = shareState.forwardPickerFlow.reduce(RaftForwardPickerFlowEvent.Dismiss)
                            shareState.forwardMessageIds = emptySet()
                            activeModal = null
                        }
                        RaftForwardTargetPickerIntent.Continue -> {
                            val nextFlow = shareState.forwardPickerFlow.reduce(RaftForwardPickerFlowEvent.Continue)
                            shareState.forwardPickerFlow = nextFlow
                            if (nextFlow.step == RaftForwardPickerStep.Note) {
                                if (shareState.forwardRequestId == null) {
                                    shareState.forwardRequestId = newThreadForwardRequestId()
                                }
                                activeModal = "forwardNote"
                            }
                        }
                        is RaftForwardTargetPickerIntent.RemoveSelection -> {
                            shareState.forwardPickerFlow =
                                shareState.forwardPickerFlow.reduce(
                                    RaftForwardPickerFlowEvent.TargetSelected(
                                        stableKey = intent.stableKey,
                                        renderGeneration = shareState.forwardPickerFlow.requestGeneration
                                    )
                                )
                            shareState.forwardSelectedReceipts = shareState.forwardSelectedReceipts.filterNot {
                                it.target.threadForwardSelectionKey() == intent.stableKey
                            }
                            shareState.forwardRequestId = null
                        }
                        is RaftForwardTargetPickerIntent.TargetSelected -> {
                            val nextFlow =
                                shareState.forwardPickerFlow.reduce(
                                    RaftForwardPickerFlowEvent.TargetSelected(
                                        stableKey = intent.item.stableKey,
                                        renderGeneration = intent.renderGeneration
                                    )
                                )
                            val sourceServerId = shareState.forwardSourceServerId
                            shareState.forwardRequestId = forwardRequestIdAfterSelectionChange(
                                previousKeys = shareState.forwardPickerFlow.selectedKeys,
                                nextKeys = nextFlow.selectedKeys,
                                currentRequestId = shareState.forwardRequestId
                            )
                            if (
                                sourceServerId != null &&
                                nextFlow.selectedKeys.contains(intent.item.stableKey)
                            ) {
                                shareState.forwardPickerFlow = nextFlow
                                shareState.forwardOperationNonce += 1
                                val receipt =
                                    intent.item.payload.toThreadForwardDestinationReceipt(
                                        operationId = "$threadInstanceKey-fwd-${shareState.forwardOperationNonce}",
                                        sourceServerId = sourceServerId,
                                        directoryGeneration = shareState.forwardDirectoryGeneration
                                    )
                                shareState.forwardSelectedReceipts =
                                    (shareState.forwardSelectedReceipts + receipt).distinctBy {
                                        it.target.threadForwardSelectionKey()
                                    }
                            } else {
                                shareState.forwardPickerFlow = nextFlow
                                shareState.forwardSelectedReceipts = shareState.forwardSelectedReceipts.filterNot {
                                    it.target.threadForwardSelectionKey() == intent.item.stableKey
                                }
                            }
                        }
                    }
                }
            )
    }
    if (activeModal == "forwardNote") {
            ThreadForwardNoteDialog(
                messageCount = forwardMessageSelection.forwardableMessages.size,
                selectedTargetLabel = shareState.forwardSelectedReceipts.joinToString(", ") { it.displayedTitle },
                note = shareState.forwardNote,
                sending = shareState.forwardSending,
                onNoteChange = { shareState.forwardNote = it },
                onDismiss = {
                    shareState.forwardPickerFlow = shareState.forwardPickerFlow.reduce(RaftForwardPickerFlowEvent.NoteBack)
                    activeModal = "forwardTargets"
                },
                onSend = {
                    if (!writeActionsEnabled) return@ThreadForwardNoteDialog
                    val repository = forwardTargetRepository
                    // task #803: the gate needs the LIVE server authority at
                    // confirm time — forwardServerId is the live projection;
                    // forwardSourceServerId is the frozen picker-open copy
                    // already inside the receipt.
                    val liveServerId = forwardServerId
                    val sourceChannelId = forwardSourceChannelId
                    val selectedReceipts = shareState.forwardSelectedReceipts
                    when {
                        repository == null ->
                            onThreadEvent("forwardMessagesFailed", ThreadForwardFailureCodeUnavailable, null, null)
                        sourceChannelId == null ->
                            onThreadEvent("forwardMessagesFailed", ThreadForwardFailureCodeSourceUnavailable, null, null)
                        selectedReceipts.isEmpty() ->
                            onThreadEvent("forwardMessagesFailed", ThreadForwardFailureCodeTargetUnavailable, null, null)
                        !shareState.forwardSending -> {
                            shareState.forwardSending = true
                            shareExportScope.launch {
                                try {
                                    val outcome =
                                        executeThreadForwardBatch(
                                            repository = repository,
                                            liveSourceServerId = liveServerId,
                                            sourceChannelId = sourceChannelId,
                                            receipts = selectedReceipts,
                                            currentDirectoryGeneration = shareState.forwardDirectoryGeneration,
                                            messageIds = forwardMessageSelection.forwardableMessages.map { it.id },
                                            mode = shareState.activeForwardSelectionMode.toForwardMessageSendMode(),
                                            note = shareState.forwardNote.text,
                                            requestId = shareState.forwardRequestId ?: newThreadForwardRequestId()
                                        )
                                    outcome.result
                                        ?.takeIf { it.deliveries.isNotEmpty() }
                                        ?.let { result ->
                                            onForwardMessagesDelivered(result)
                                            if (result.deliveries.size == 1 && result.failures.isEmpty()) {
                                                val delivery = result.deliveries.single()
                                                val receipt = selectedReceipts.singleOrNull()
                                                if (receipt != null) {
                                                    shareState.forwardSuccessBanner =
                                                        ThreadForwardSuccessBanner(
                                                            target = receipt.target,
                                                            destination = delivery.destination,
                                                            messageId = delivery.message.id,
                                                            conversationContext = delivery.message.conversationContext
                                                        )
                                                }
                                            }
                                        }
                                    if (outcome.failureCode == null && outcome.result != null && outcome.result.failures.isEmpty()) {
                                        activeModal = null
                                        shareState.forwardPickerFlow.sessionKey?.let { session ->
                                            forwardTargetRepository?.releaseDirectorySnapshot(session.directorySessionId())
                                        }
                                        shareState.forwardPickerFlow =
                                            shareState.forwardPickerFlow.reduce(RaftForwardPickerFlowEvent.Dismiss)
                                        shareState.forwardSelectedReceipts = emptyList()
                                        shareState.forwardRequestId = null
                                        shareState.forwardMessageIds = emptySet()
                                        shareState.forwardNote = TextFieldValue()
                                        clearShareSelection()
                                        if (outcome.result.deliveries.size != 1 || shareState.forwardSuccessBanner == null) {
                                            onThreadEvent(
                                                "forwardMessagesSent",
                                                selectedReceipts.joinToString(", ") { it.displayedTitle },
                                                null,
                                                shareState.activeForwardSelectionMode.wireValue
                                            )
                                        }
                                    } else {
                                        outcome.result?.let { result ->
                                            if (outcome.failedReceipts.isNotEmpty()) {
                                                val failedKeys = outcome.failedReceipts
                                                    .map { it.target.threadForwardSelectionKey() }
                                                    .toSet()
                                                shareState.forwardPickerFlow = shareState.forwardPickerFlow.reduce(
                                                    RaftForwardPickerFlowEvent.ClearSelected(
                                                        stableKeys = shareState.forwardPickerFlow.selectedKeys - failedKeys
                                                    )
                                                )
                                                shareState.forwardSelectedReceipts = outcome.failedReceipts
                                                shareState.forwardRequestId = null
                                            }
                                        }
                                        onThreadEvent(
                                            "forwardMessagesFailed",
                                            outcome.failureCode ?: ThreadForwardFailureCodePartial,
                                            null,
                                            outcome.traceId
                                        )
                                    }
                                } finally {
                                    shareState.forwardSending = false
                                }
                            }
                        }
                    }
                }
            )
    }
    shareState.forwardSuccessBanner?.let { banner ->
        ThreadForwardSuccessToastView(
            banner = banner,
            onViewInChat = {
                shareState.forwardSuccessBanner = null
                onOpenForwardDestination(banner)
            },
            onDismiss = { shareState.forwardSuccessBanner = null }
        )
    }
}

private fun ThreadForwardSelectionMode.toForwardMessageSendMode(): ForwardMessageSendMode =
    when (this) {
        ThreadForwardSelectionMode.Merged -> ForwardMessageSendMode.Merged
        ThreadForwardSelectionMode.Individual -> ForwardMessageSendMode.Individual
    }

@OptIn(ExperimentalUuidApi::class)
private fun newThreadForwardRequestId(): String = Uuid.random().toString()
