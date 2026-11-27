package build.raft.app.thread

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import build.raft.app.shell.AppShellScrollToTopRequestFence
import build.raft.app.messages
import kotlinx.coroutines.delay

/**
 * The thread list's history-start focus, header scroll-to-top, outline origin jump and share-capture
 * placement effects of [ThreadMessagesListContent], moved here unchanged and called in the same
 * place. Non-restartable, like [ThreadMessagesListOverlays]: it runs whenever the list's content
 * runs, so effect keys, call order and state reads stay those of the list's content.
 */
@Composable
@NonRestartableComposable
internal fun ThreadMessagesListJumpEffects(
    threadInstanceKey: String,
    renderPayload: ThreadTimelineSlice,
    listState: ThreadListViewport,
    lazyItemCount: Int,
    messageIndexSignature: String,
    headerScrollToTopRequest: Int,
    headerScrollToTopRequestFence: AppShellScrollToTopRequestFence,
    shareSelectionCaptureActive: Boolean,
    shareSelectionCaptureAnchorMessageId: String?,
    shareSelectionCaptureAnchorBeforeTarget: Boolean,
    shareSelectionCaptureScrollOffsetPx: Int,
    selectedShareMessageIds: Set<String>,
    userScrollGeneration: ThreadUserScrollGeneration,
    pendingFocusedMessageAnchorIdentityState: State<String?>,
    dispatchViewportEvent: (LazyViewportEvent) -> Unit,
    awaitViewportCommandSettlement: suspend (LazyViewportCommandId) -> Unit,
    onThreadEvent: ThreadEventHandler,
    onShareSelectionBounds: (String, ThreadShareCaptureRect?) -> Unit,
    diagnosticsLogger: (String) -> Unit,
    allowBottomAutoAnchorState: MutableState<Boolean>,
    initialBottomAnchorReadyState: MutableState<Boolean>,
    viewportControllerMutableState: State<LazyViewportControllerState>,
    viewportLayoutGenerationState: State<Long>,
    latestLocalSendBottomAnchorActiveKeyState: MutableState<String?>,
    activeFocusedMessageIdState: MutableState<String?>,
    pendingKeyboardBottomAnchorState: MutableState<Boolean>,
    outlineOriginJumpRequestState: MutableState<Int>,
    outlineOriginJumpConsumedState: MutableState<Int>,
    focusedAnchorState: ThreadFocusedAnchorState,
) {
    var allowBottomAutoAnchor by allowBottomAutoAnchorState
    var initialBottomAnchorReady by initialBottomAnchorReadyState
    val viewportControllerState by viewportControllerMutableState
    val viewportLayoutGeneration by viewportLayoutGenerationState
    var latestLocalSendBottomAnchorActiveKey by latestLocalSendBottomAnchorActiveKeyState
    var activeFocusedMessageId by activeFocusedMessageIdState
    var pendingKeyboardBottomAnchor by pendingKeyboardBottomAnchorState
    var outlineOriginJumpRequest by outlineOriginJumpRequestState
    var outlineOriginJumpConsumed by outlineOriginJumpConsumedState
    var scrollInterruptedFocusedMessageAnchorIdentity by focusedAnchorState::scrollInterruptedFocusedMessageAnchorIdentity
    // A thread beside this conversation whose original is behind the history window: the same jump
    // to the start, where the history paywall card is (ThreadHeaderOriginalTitle.kt).
    ThreadHistoryStartFocusEffect(threadInstanceKey, renderPayload.historyStartFocusRequest) { outlineOriginJumpRequest += 1 }
    LaunchedEffect(threadInstanceKey, headerScrollToTopRequest, lazyItemCount) {
        if (!consumeThreadHeaderScrollToTopRequest(
                request = headerScrollToTopRequest,
                lazyItemCount = lazyItemCount,
                fence = headerScrollToTopRequestFence,
            )
        ) {
            return@LaunchedEffect
        }
        listState.noteUserNavigation()
        pendingFocusedMessageAnchorIdentityState.value?.let { pendingIdentity ->
            scrollInterruptedFocusedMessageAnchorIdentity = pendingIdentity
        }
        latestLocalSendBottomAnchorActiveKey = null
        pendingKeyboardBottomAnchor = false
        allowBottomAutoAnchor = false
        initialBottomAnchorReady = true
        userScrollGeneration.value += 1
        dispatchViewportEvent(LazyViewportEvent.PinTop)
        diagnosticsLogger(
            "thread_header_scroll_to_top request=$headerScrollToTopRequest " +
                "firstVisible=${listState.firstVisibleItemIndex} totalItems=$lazyItemCount"
        )
    }
    // The thread timeline's jump to the thread's first message (web `jumpToParent`): its row is the
    // list's first, so the thread's start is loaded first (older pages until there are none), then
    // the list pins its top and the row is located, as a focused message is.
    LaunchedEffect(
        threadInstanceKey,
        outlineOriginJumpRequest,
        renderPayload.canLoadMoreBefore,
        renderPayload.olderMessagesStatus,
        renderPayload.originStatus,
        lazyItemCount
    ) {
        val step =
            threadOutlineOriginJumpStep(
                request = outlineOriginJumpRequest,
                consumed = outlineOriginJumpConsumed,
                canLoadMoreBefore = renderPayload.canLoadMoreBefore,
                olderMessagesStatus = renderPayload.olderMessagesStatus,
                originRowShown = renderPayload.threadStartRowShown(),
            )
        when (step) {
            ThreadOutlineOriginJumpStep.Idle, ThreadOutlineOriginJumpStep.Wait -> Unit
            ThreadOutlineOriginJumpStep.LoadOlder -> onThreadEvent("loadOlder", null, null, null)
            ThreadOutlineOriginJumpStep.GiveUp -> outlineOriginJumpConsumed = outlineOriginJumpRequest
            ThreadOutlineOriginJumpStep.Pin -> {
                listState.noteUserNavigation()
                outlineOriginJumpConsumed = outlineOriginJumpRequest
                pendingFocusedMessageAnchorIdentityState.value?.let { pendingIdentity ->
                    scrollInterruptedFocusedMessageAnchorIdentity = pendingIdentity
                }
                latestLocalSendBottomAnchorActiveKey = null
                pendingKeyboardBottomAnchor = false
                allowBottomAutoAnchor = false
                initialBottomAnchorReady = true
                userScrollGeneration.value += 1
                dispatchViewportEvent(LazyViewportEvent.PinTop)
                activeFocusedMessageId = renderPayload.originMessage?.id
                diagnosticsLogger("thread_outline_origin_jump request=$outlineOriginJumpRequest totalItems=$lazyItemCount")
            }
        }
    }
    LaunchedEffect(
        threadInstanceKey,
        shareSelectionCaptureActive,
        shareSelectionCaptureAnchorMessageId,
        shareSelectionCaptureAnchorBeforeTarget,
        shareSelectionCaptureScrollOffsetPx,
        messageIndexSignature,
        lazyItemCount
    ) {
        val anchorMessageId =
            shareSelectionCaptureAnchorMessageId
                ?.takeIf { shareSelectionCaptureActive && it.isNotBlank() }
                ?: return@LaunchedEffect
        val targetIndex = renderPayload.threadMessageListItemIndex(anchorMessageId)
        val scrollIndex =
            threadShareCaptureAnchorListItemIndex(
                targetIndex = targetIndex,
                anchorBeforeTarget = shareSelectionCaptureAnchorBeforeTarget
            )
        diagnosticsLogger(
            "thread_share_anchor_start messageId=$anchorMessageId targetIndex=$targetIndex " +
                "scrollIndex=$scrollIndex beforeTarget=$shareSelectionCaptureAnchorBeforeTarget " +
                "offset=$shareSelectionCaptureScrollOffsetPx total=${listState.layoutInfo.totalItemsCount}"
        )
        if (!listState.canScrollToThreadItem(scrollIndex)) {
            diagnosticsLogger(
                "thread_share_anchor_rejected messageId=$anchorMessageId scrollIndex=$scrollIndex " +
                    "total=${listState.layoutInfo.totalItemsCount}"
            )
            return@LaunchedEffect
        }
        selectedShareMessageIds.forEach { messageId ->
            onShareSelectionBounds(messageId, null)
        }
        val scrollTarget =
            threadShareCaptureAnchorTarget(
                targetIndex = targetIndex,
                anchorBeforeTarget = shareSelectionCaptureAnchorBeforeTarget,
                originVisible = renderPayload.hasThreadOriginItem(),
                historyBoundaryVisible = renderPayload.hasThreadHistoryBoundaryItem(),
                messageIds = renderPayload.messages.map { message -> message.id }
            ) ?: return@LaunchedEffect
        val desiredScrollOffset =
            if (shareSelectionCaptureAnchorBeforeTarget) 0 else shareSelectionCaptureScrollOffsetPx
        dispatchViewportEvent(
            LazyViewportEvent.Anchor(
                target = scrollTarget,
                offset = -desiredScrollOffset,
                layoutGeneration = viewportLayoutGeneration,
                fallback = LazyViewportAnchorFallback.Free
            )
        )
        val commandId = viewportControllerState.pendingCommand?.id
        if (commandId != null) {
            awaitViewportCommandSettlement(commandId)
        }
        delay(ThreadShareCaptureScrollSettleMs)
        diagnosticsLogger(
            "thread_share_anchor_complete messageId=$anchorMessageId " +
                "visible=${listState.layoutInfo.visibleItemsInfo.joinToString(",") { it.index.toString() }}"
        )
    }
}
