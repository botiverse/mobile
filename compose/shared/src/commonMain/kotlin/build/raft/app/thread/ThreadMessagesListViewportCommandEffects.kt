package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Density

/**
 * The thread list's viewport message-diff effect, the viewport command executor (the only
 * production owner allowed to mutate the list state) and the header original reporter of
 * [ThreadMessagesListContent], moved here unchanged and called in the same place. Non-restartable,
 * like [ThreadMessagesListOverlays]: it runs whenever the list's content runs, so effect keys, call
 * order and state reads stay those of the list's content.
 */
@Composable
@NonRestartableComposable
internal fun ThreadMessagesListViewportCommandEffects(
    threadInstanceKey: String,
    renderPayload: ThreadTimelineSlice,
    renderConfig: ThreadTimelineRenderConfig,
    listState: ThreadListViewport,
    messageIndexSignature: String,
    threadDensity: Density,
    currentPayloadState: State<ThreadTimelineSlice>,
    currentLatestItemIndexState: State<Int>,
    currentViewportControllerState: State<LazyViewportControllerState>,
    currentViewportLayoutGenerationState: State<Long>,
    pendingFocusedMessageAnchorIdentityState: State<String?>,
    dispatchViewportEvent: (LazyViewportEvent) -> Unit,
    diagnosticsLogger: (String) -> Unit,
    allowBottomAutoAnchorState: MutableState<Boolean>,
    viewportControllerMutableState: State<LazyViewportControllerState>,
    viewportLayoutGenerationState: MutableState<Long>,
    previousViewportMessageIdsState: MutableState<List<String>>,
    previousViewportLocalSendIdsState: MutableState<Set<String>>,
    focusedAnchorState: ThreadFocusedAnchorState,
) {
    var allowBottomAutoAnchor by allowBottomAutoAnchorState
    val viewportControllerState by viewportControllerMutableState
    var viewportLayoutGeneration by viewportLayoutGenerationState
    var previousViewportMessageIds by previousViewportMessageIdsState
    var previousViewportLocalSendIds by previousViewportLocalSendIdsState
    var focusedAnchorPlacementState by focusedAnchorState::focusedAnchorPlacementState
    LaunchedEffect(threadInstanceKey, messageIndexSignature) {
        val nextIds = renderPayload.messages.map { message -> message.id }
        val previousIds = previousViewportMessageIds
        val localSendIds = previousViewportLocalSendIds + renderPayload.messages.localSendIds()
        previousViewportMessageIds = nextIds
        previousViewportLocalSendIds = renderPayload.messages.localSendIds()
        if (previousIds == nextIds) return@LaunchedEffect
        val mutation = classifyThreadViewportMutation(previousIds, nextIds, localSendIds)
        viewportLayoutGeneration += 1L
        diagnosticsLogger(
            "thread_content_mutation key=$threadInstanceKey mutation=$mutation " +
                "intent=${viewportControllerState.intent.logTag()} " +
                "allowBottomAutoAnchor=$allowBottomAutoAnchor"
        )
        if (
            mutation == LazyViewportMutation.Append &&
            focusedAnchorPlacementState.placement == LazyViewportAnchorPlacement.RenderedBottom
        ) {
            focusedAnchorPlacementState =
                resetThreadFocusedAnchorPlacement(focusedAnchorPlacementState.identity)
        }
        dispatchViewportEvent(LazyViewportEvent.ContentMutated(mutation))
    }
    // Owner lifetime (Hands 712d8386): popping the page while this effect was still placing a
    // focused message scrolled a list whose LazyColumn was already leaving, and crashed in
    // prefetch cleanup. ThreadListScrollOwnerEffect restarts the effect (which then does nothing)
    // when the page starts leaving the stack, and retires the list before cancelling its job.
    // The only production owner allowed to mutate listState.
    ThreadListScrollOwnerEffect(listState, threadInstanceKey, viewportControllerState.pendingCommand?.id) {
        val command = viewportControllerState.pendingCommand ?: return@ThreadListScrollOwnerEffect
        // Job active, list still composed, page not leaving: checked before the command's own
        // currency, so a dead owner neither scrolls nor dispatches viewport events.
        fun commandOwnerLive(): Boolean = ownerLive()
        fun commandStillCurrent(): Boolean =
            currentViewportControllerState.value.acceptsRenderedTailCommand(
                command = command,
                currentLayoutGeneration = currentViewportLayoutGenerationState.value,
                currentTailMessageId = currentPayloadState.value.messages.lastOrNull()?.id,
                currentAnchorIdentity = pendingFocusedMessageAnchorIdentityState.value
            )
        var staleRenderedTailInvalidationDispatched = false
        fun commandIsLiveOrTerminallyInvalidate(): Boolean {
            if (!commandOwnerLive()) return false
            if (commandStillCurrent()) return true
            val anchor = command.target as? LazyViewportCommandTarget.Anchor
            val controller = currentViewportControllerState.value
            if (
                !staleRenderedTailInvalidationDispatched &&
                anchor?.renderedTailMessageId != null &&
                controller.accepts(command)
            ) {
                staleRenderedTailInvalidationDispatched = true
                if (
                    focusedAnchorPlacementState.identity == anchor.renderedTailAnchorIdentity &&
                    focusedAnchorPlacementState.placement == LazyViewportAnchorPlacement.RenderedBottom &&
                    focusedAnchorPlacementState.renderedTailMessageId == anchor.renderedTailMessageId
                ) {
                    focusedAnchorPlacementState =
                        resetThreadFocusedAnchorPlacement(anchor.renderedTailAnchorIdentity)
                }
                diagnosticsLogger(
                    "thread_rendered_tail_command result=invalidated command=${command.id.sequence} " +
                        "commandLayoutGeneration=${anchor.layoutGeneration} " +
                        "currentLayoutGeneration=${currentViewportLayoutGenerationState.value} " +
                        "expectedTail=${anchor.renderedTailMessageId} " +
                        "currentTail=${currentPayloadState.value.messages.lastOrNull()?.id ?: "none"} " +
                        "expectedIdentity=${anchor.renderedTailAnchorIdentity ?: "none"} " +
                        "currentIdentity=${pendingFocusedMessageAnchorIdentityState.value ?: "none"}"
                )
                dispatchViewportEvent(
                    LazyViewportEvent.RenderedTailCommandInvalidated(
                        commandId = command.id,
                        currentLayoutGeneration = currentViewportLayoutGenerationState.value
                    )
                )
            }
            return false
        }
        if (!commandIsLiveOrTerminallyInvalidate()) return@ThreadListScrollOwnerEffect
        listState.awaitThreadMessagesLayoutFrame()
        if (!commandIsLiveOrTerminallyInvalidate()) return@ThreadListScrollOwnerEffect
        listState.noteCommandOwner(command.target.openTraceOwnerTag())
        val executed =
            when (val target = command.target) {
                is LazyViewportCommandTarget.Top -> {
                    if (!commandIsLiveOrTerminallyInvalidate()) return@ThreadListScrollOwnerEffect
                    if (target.animated) {
                        raftExpressiveTopSettleAnchor(
                            firstVisibleItemIndex = listState.firstVisibleItemIndex,
                            visibleItemCount = listState.layoutInfo.visibleItemsInfo.size
                        )?.let { anchor ->
                            listState.scrollToItem(anchor)
                            if (!commandIsLiveOrTerminallyInvalidate()) return@ThreadListScrollOwnerEffect
                        }
                        listState.animateScrollToItem(0)
                        if (!commandIsLiveOrTerminallyInvalidate()) return@ThreadListScrollOwnerEffect
                    } else {
                        listState.scrollToItem(0)
                        if (!commandIsLiveOrTerminallyInvalidate()) return@ThreadListScrollOwnerEffect
                    }
                    true
                }
                LazyViewportCommandTarget.Latest -> {
                    val targetIndex = currentLatestItemIndexState.value
                    if (targetIndex < 0) {
                        false
                    } else {
                        if (!commandIsLiveOrTerminallyInvalidate()) return@ThreadListScrollOwnerEffect
                        listState.scrollLatestItemToRenderedBottom(targetIndex) {
                            commandIsLiveOrTerminallyInvalidate()
                        }
                        if (!commandIsLiveOrTerminallyInvalidate()) return@ThreadListScrollOwnerEffect
                        true
                    }
                }
                is LazyViewportCommandTarget.Anchor -> {
                    val targetIndex = currentPayloadState.value.threadViewportListItemIndex(target.target)
                    if (targetIndex < 0 || !listState.canScrollToThreadItem(targetIndex)) {
                        dispatchViewportEvent(LazyViewportEvent.AnchorUnavailable(command.id))
                        false
                    } else {
                        if (!commandIsLiveOrTerminallyInvalidate()) return@ThreadListScrollOwnerEffect
                        val anchorExecuted =
                            when (target.placement) {
                                LazyViewportAnchorPlacement.ExactOffset -> {
                                    listState.scrollToItem(targetIndex, -target.offset)
                                    true
                                }
                                LazyViewportAnchorPlacement.Focused ->
                                    listState.scrollThreadItemToFocusedViewport(targetIndex) {
                                        commandIsLiveOrTerminallyInvalidate()
                                    }
                                LazyViewportAnchorPlacement.FocusedThreadReplies -> {
                                    val minBottomReservePx =
                                        with(threadDensity) { ThreadRepliesFocusMinBottomReserve.roundToPx() }
                                    listState.scrollThreadItemToFocusedViewport(
                                        itemIndex = targetIndex,
                                        focusedScrollOffset = { itemSize, info ->
                                            threadRepliesFocusedItemScrollOffset(
                                                itemSize = itemSize,
                                                viewportEnd = info.viewportEndOffset,
                                                minBottomReservePx = minBottomReservePx
                                            )
                                        }
                                    ) {
                                        commandIsLiveOrTerminallyInvalidate()
                                    }
                                }
                                LazyViewportAnchorPlacement.RenderedBottom ->
                                    listState.scrollThreadItemToRenderedBottom(
                                        itemIndex = targetIndex,
                                        trailingPaddingSize = target.offset.coerceAtLeast(0)
                                    ) {
                                        commandIsLiveOrTerminallyInvalidate()
                                    }
                            }
                        if (!commandIsLiveOrTerminallyInvalidate()) return@ThreadListScrollOwnerEffect
                        anchorExecuted
                    }
                }
            }
        if (commandIsLiveOrTerminallyInvalidate()) {
            if (executed) {
                dispatchViewportEvent(LazyViewportEvent.CommandConsumed(command.id))
            } else if (command.target is LazyViewportCommandTarget.Anchor) {
                dispatchViewportEvent(LazyViewportEvent.AnchorUnavailable(command.id))
            }
        }
    }
    // The head's title names the original once its row scrolls away (ThreadHeaderOriginalTitle.kt).
    ThreadHeaderOriginalReporter(listState.raw, renderPayload, renderConfig.originErrorCode)
}
