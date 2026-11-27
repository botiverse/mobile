package build.raft.app.thread

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import build.raft.app.messages
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The thread list's read-tail, follow-latest, initial bottom placement, typing-line end and
 * older / newer paging effects of [ThreadMessagesListContent], moved here unchanged and called in
 * the same place. Non-restartable, like [ThreadMessagesListOverlays]: it runs whenever the list's
 * content runs, so effect keys, call order and state reads stay those of the list's content.
 */
@Composable
@NonRestartableComposable
internal fun ThreadMessagesListPagingEffects(
    threadInstanceKey: String,
    renderPayload: ThreadTimelineSlice,
    listState: ThreadListViewport,
    lazyItemCount: Int,
    latestItemIndex: Int,
    latestMessageListItemIndex: Int,
    viewportMeasurementReady: Boolean,
    threadLazyWindowConsumerCounts: ThreadLazyWindowConsumerCounts,
    shareSelectionCaptureActive: Boolean,
    newerPageCycleActive: Boolean,
    agentTypingLineShown: Boolean,
    sparseTopAnchored: Boolean,
    imeProgrammaticViewportPolicy: ThreadImeProgrammaticViewportPolicy,
    firstViewportProbe: ThreadFirstViewportProbe,
    postFirstFrameBeyondBoundsEnabledRef: ThreadMutableBooleanRef,
    stackPageLeavingRef: ThreadMutableBooleanRef,
    userScrollGeneration: ThreadUserScrollGeneration,
    newerGestureDemandTracker: ThreadNewerGestureDemandTracker,
    currentSparseTopAnchoredState: State<Boolean>,
    currentViewportControllerState: State<LazyViewportControllerState>,
    readingAnchorPlacementSettledState: State<Boolean>,
    pendingFocusedMessageAnchorIdentityState: State<String?>,
    dispatchViewportEvent: (LazyViewportEvent) -> Unit,
    requestNewerPage: (String) -> Unit,
    onThreadEvent: ThreadEventHandler,
    onVisibleReadTailChange: (Int?) -> Unit,
    onViewportFollowsLatestChange: (Boolean) -> Unit,
    diagnosticsLogger: (String) -> Unit,
    allowBottomAutoAnchorState: MutableState<Boolean>,
    initialBottomAnchorReadyState: MutableState<Boolean>,
    viewportControllerMutableState: State<LazyViewportControllerState>,
    lastSettledNewerDemandGenerationState: MutableState<Int>,
    olderPageCycleActiveState: MutableState<Boolean>,
    olderPageSettledUserScrollGenerationState: MutableState<Int>,
    pendingOlderPageAnchorState: MutableState<ThreadVisibleMessageAnchor?>,
    previousOlderMessagesStatusState: MutableState<String>,
    firstViewportProbeActiveState: MutableState<Boolean>,
    liveComposerKeyboardInsetDpState: State<Float>,
    focusedAnchorState: ThreadFocusedAnchorState,
) {
    var allowBottomAutoAnchor by allowBottomAutoAnchorState
    var initialBottomAnchorReady by initialBottomAnchorReadyState
    val viewportControllerState by viewportControllerMutableState
    var lastSettledNewerDemandGeneration by lastSettledNewerDemandGenerationState
    var olderPageCycleActive by olderPageCycleActiveState
    var olderPageSettledUserScrollGeneration by olderPageSettledUserScrollGenerationState
    var pendingOlderPageAnchor by pendingOlderPageAnchorState
    var previousOlderMessagesStatus by previousOlderMessagesStatusState
    var firstViewportProbeActive by firstViewportProbeActiveState
    val liveComposerKeyboardInsetDp by liveComposerKeyboardInsetDpState
    val consumedFocusedMessageAnchorIdentity by focusedAnchorState::consumedFocusedMessageAnchorIdentity
    val scrollInterruptedFocusedMessageAnchorIdentity by focusedAnchorState::scrollInterruptedFocusedMessageAnchorIdentity
    val bottomAnchorFocusedYieldElapsedMs by focusedAnchorState::bottomAnchorFocusedYieldElapsedMs
    // task #232: report the rendered visible tail so the read marker tracks
    // actual visibility instead of the loaded payload tail.
    // The key must include the payload window: a bare threadInstanceKey key
    // captures firstMessageListItemIndex/messageCount from the entry payload,
    // so newer messages fall outside the [firstMessageListItemIndex,
    // firstMessageListItemIndex + messageCount) window and the tail index never
    // advances (task #1139 — the ↓ x new count froze at the entry value).
    LaunchedEffect(threadInstanceKey, renderPayload.messages, renderPayload.firstMessageListItemIndex()) {
        snapshotFlow {
            threadVisibleReadTailMessageIndex(
                visibleListItemIndexes = listState.layoutInfo.visibleItemsInfo.map { item -> item.index },
                firstMessageListItemIndex = renderPayload.firstMessageListItemIndex(),
                messageCount = renderPayload.messages.size,
            )
        }
            .distinctUntilChanged()
            .collect { index -> onVisibleReadTailChange(index) }
    }
    // Task #168 step 1: tell the store whether the list follows the latest message, so a large-gap
    // catch-up never replaces the timeline under a reader who scrolled up (it detaches from the tail
    // instead and the ↓ button / newer paging takes them forward).
    LaunchedEffect(threadInstanceKey) {
        snapshotFlow { allowBottomAutoAnchor }
            .distinctUntilChanged()
            .collect { follows -> onViewportFollowsLatestChange(follows) }
    }
    LaunchedEffect(
        threadInstanceKey,
        renderPayload.messages.size,
        lazyItemCount,
        viewportMeasurementReady,
        threadLazyWindowConsumerCounts
    ) {
        if (!viewportMeasurementReady) {
            return@LaunchedEffect
        }
        repeat(ThreadBottomAnchorMinimumAttempts + 1) { attempt ->
            listState.awaitThreadMessagesLayoutFrame()
            val currentBeyondBoundsItems =
                if (shareSelectionCaptureActive) {
                    0
                } else if (postFirstFrameBeyondBoundsEnabledRef.value) {
                    threadLazyWindowConsumerCounts.listBeyondBoundsItems
                } else {
                    0
                }
            val stats =
                listState.threadLazyWindowStats(
                    timelineSlice = renderPayload,
                    lazyItemCount = lazyItemCount,
                    beyondBoundsItems = currentBeyondBoundsItems
                )
            if (stats.visibleItems > 0) {
                diagnosticsLogger(stats.toDiagnosticsString(result = "laid_out"))
                if (firstViewportProbe.markLogged()) {
                    diagnosticsLogger(
                        firstViewportProbe.toDiagnosticsString(
                            result = "laid_out",
                            instanceId = threadInstanceKey,
                            timelineSlice = renderPayload,
                            lazyItemCount = lazyItemCount,
                            stats = stats
                        )
                    )
                    firstViewportProbeActive = false
                }
                return@LaunchedEffect
            }
            if (attempt >= ThreadBottomAnchorMinimumAttempts) {
                diagnosticsLogger(stats.toDiagnosticsString(result = "missing_layout"))
                if (firstViewportProbe.markLogged()) {
                    diagnosticsLogger(
                        firstViewportProbe.toDiagnosticsString(
                            result = "missing_layout",
                            instanceId = threadInstanceKey,
                            timelineSlice = renderPayload,
                            lazyItemCount = lazyItemCount,
                            stats = stats
                        )
                    )
                    firstViewportProbeActive = false
                }
            }
            awaitNextThreadFrame()
        }
    }
    LaunchedEffect(
        threadInstanceKey,
        latestItemIndex,
        shareSelectionCaptureActive,
        newerPageCycleActive
    ) {
        if (shareSelectionCaptureActive) {
            allowBottomAutoAnchor = false
            return@LaunchedEffect
        }
        snapshotFlow {
            newerPageCycleActive to
                ThreadBottomAutoAnchorAvailabilityState(
                    currentAllow = allowBottomAutoAnchor,
                    isScrollInProgress = listState.isScrollInProgress,
                    isAtRenderedBottom = listState.isLatestItemAtRenderedBottom(latestItemIndex, allowSparseTopFit = currentSparseTopAnchoredState.value),
                    focusedAnchorOwnsViewport =
                        focusedMessageAnchorOwnsViewport(
                            pendingIdentity = pendingFocusedMessageAnchorIdentityState.value,
                            consumedIdentity = consumedFocusedMessageAnchorIdentity,
                            scrollInterruptedIdentity = scrollInterruptedFocusedMessageAnchorIdentity,
                            yieldElapsedMs = bottomAnchorFocusedYieldElapsedMs
                        )
                )
        }
            .distinctUntilChanged()
            .collect { (cycleActive, state) ->
                val nextAllow =
                    if (cycleActive) {
                        false
                    } else {
                        resolveThreadBottomAutoAnchorAllowed(state)
                    }
                if (allowBottomAutoAnchor != nextAllow) {
                    allowBottomAutoAnchor = nextAllow
                }
            }
    }
    LaunchedEffect(
        threadInstanceKey,
        latestItemIndex,
        initialBottomAnchorReady,
        shareSelectionCaptureActive,
        newerPageCycleActive
    ) {
        if (shareSelectionCaptureActive || latestItemIndex < 0 || !initialBottomAnchorReady) {
            return@LaunchedEffect
        }
        snapshotFlow {
            newerPageCycleActive to
                ThreadBottomAnchorCorrectionState(
                    allowBottomAutoAnchor = allowBottomAutoAnchor,
                    isScrollInProgress = listState.isScrollInProgress,
                    isAtRenderedBottom = listState.isLatestItemAtRenderedBottom(latestItemIndex, allowSparseTopFit = currentSparseTopAnchoredState.value),
                    focusedAnchorOwnsViewport =
                        focusedMessageAnchorOwnsViewport(
                            pendingIdentity = pendingFocusedMessageAnchorIdentityState.value,
                            consumedIdentity = consumedFocusedMessageAnchorIdentity,
                            scrollInterruptedIdentity = scrollInterruptedFocusedMessageAnchorIdentity,
                            yieldElapsedMs = bottomAnchorFocusedYieldElapsedMs
                        )
                )
        }
            .distinctUntilChanged()
            .collect { (cycleActive, state) ->
                if (!cycleActive && shouldAutoCorrectThreadBottomAnchor(state)) {
                    // task #990 kill-switch: this collector runs for the life
                    // of the thread, so the inset must be read live at each
                    // decision — same seam as the stability-path correction.
                    if (
                        suppressAutoBottomHeightChange(
                            keyboardInsetDp = liveComposerKeyboardInsetDp,
                            policy = imeProgrammaticViewportPolicy,
                            log = diagnosticsLogger,
                        )
                    ) {
                        return@collect
                    }
                    dispatchViewportEvent(
                        LazyViewportEvent.ContentMutated(LazyViewportMutation.HeightChange)
                    )
                }
            }
    }
    // The typing / working line came or went (#proj-mobile:#271, web AgentTypingListEnd): the list's
    // end grows or shrinks by the line's slot in the layout after this composition; see
    // ThreadTypingLineEndEffect for who owns the viewport then.
    ThreadTypingLineEndEffect(
        threadInstanceKey = threadInstanceKey,
        lineShown = agentTypingLineShown,
        listState = listState,
        controllerState = currentViewportControllerState,
        placementSettled = readingAnchorPlacementSettledState,
        newerPagePending = renderPayload.canLoadMoreAfter || newerPageCycleActive,
        allowSparseTopFit = sparseTopAnchored,
        dispatchViewportEvent = dispatchViewportEvent,
        diagnosticsLogger = diagnosticsLogger,
    )
    LaunchedEffect(
        threadInstanceKey,
        renderPayload.canLoadMoreBefore,
        renderPayload.olderMessagesStatus,
        initialBottomAnchorReady,
        renderPayload.messages.size,
        olderPageCycleActive,
        olderPageSettledUserScrollGeneration
    ) {
        if (!initialBottomAnchorReady ||
            !renderPayload.canLoadMoreBefore ||
            renderPayload.olderMessagesStatus != "idle" ||
            olderPageCycleActive
        ) {
            return@LaunchedEffect
        }
        val boundaryIndex = renderPayload.threadHistoryBoundaryListItemIndex()
        snapshotFlow {
            listState.isThreadOlderAutoLoadThreshold(
                boundaryIndex = boundaryIndex,
                // Observed (task #253): a scroll at the list's top changes no layout, only the count.
                userScrollGeneration = userScrollGeneration.observed,
                lastSettledUserScrollGeneration = olderPageSettledUserScrollGeneration
            )
        }
            .distinctUntilChanged()
            .collect { shouldLoad ->
                if (shouldLoad) {
                    if (allowBottomAutoAnchor) {
                        allowBottomAutoAnchor = false
                    }
                    onThreadEvent("loadOlder", null, null, null)
                }
            }
    }
    LaunchedEffect(
        threadInstanceKey,
        renderPayload.olderMessagesStatus,
        renderPayload.messages.size,
        renderPayload.messages.firstOrNull()?.id
    ) {
        val previousStatus = previousOlderMessagesStatus
        if (renderPayload.olderMessagesStatus == "loading" && previousStatus != "loading") {
            olderPageCycleActive = true
            pendingOlderPageAnchor =
                listState.threadFirstVisibleMessageAnchor(
                    timelineSlice = renderPayload,
                    userScrollGeneration = userScrollGeneration.value
                )
            allowBottomAutoAnchor = false
            pendingOlderPageAnchor?.let { anchor ->
                diagnosticsLogger(
                    "thread_older_anchor_capture messageId=${anchor.messageId} offset=${anchor.offset} " +
                        "userScrollGeneration=${anchor.userScrollGeneration} " +
                        "isScrollInProgress=${listState.isScrollInProgress}"
                )
            }
            diagnosticsLogger(
                "thread_older_page_cycle result=loading " +
                    "viewportIntent=${viewportControllerState.intent} " +
                    "isScrollInProgress=${listState.isScrollInProgress}"
            )
        } else if (previousStatus == "loading" && renderPayload.olderMessagesStatus != "loading") {
            val anchor = pendingOlderPageAnchor
            pendingOlderPageAnchor = null
            allowBottomAutoAnchor = false
            if (anchor != null) {
                listState.awaitThreadMessagesLayoutFrame()
            }
            // Owner lifetime (Hands 712d8386): the older page can land after Back, while the page
            // slides out; its list must not be scrolled then.
            val restoreOwnerLive =
                listState.scrollOwnerLive(coroutineContext, pageLeaving = stackPageLeavingRef.value)
            if (
                anchor != null &&
                restoreOwnerLive &&
                shouldRestoreThreadOlderPageAnchor(
                    capturedUserScrollGeneration = anchor.userScrollGeneration,
                    currentUserScrollGeneration = userScrollGeneration.value
                )
            ) {
                val targetIndex = renderPayload.threadMessageListItemIndex(anchor.messageId)
                if (targetIndex >= 0) {
                    listState.noteViewportOwner(ThreadViewportOwnerTag.OlderRestore)
                    listState.scrollToItem(targetIndex, -anchor.offset)
                    diagnosticsLogger(
                        "thread_older_anchor_restore result=restored messageId=${anchor.messageId} " +
                            "targetIndex=$targetIndex offset=${anchor.offset} " +
                            "userScrollGeneration=${userScrollGeneration.value}"
                    )
                }
            } else if (anchor != null && !restoreOwnerLive) {
                diagnosticsLogger(
                    "thread_older_anchor_restore result=skipped_owner_gone messageId=${anchor.messageId}"
                )
            } else if (anchor != null) {
                diagnosticsLogger(
                    "thread_older_anchor_restore result=skipped_user_moved messageId=${anchor.messageId} " +
                        "capturedUserScrollGeneration=${anchor.userScrollGeneration} " +
                        "currentUserScrollGeneration=${userScrollGeneration.value}"
                )
            }
            diagnosticsLogger(
                "thread_older_page_cycle result=settled " +
                    "viewportIntent=${viewportControllerState.intent} " +
                    "userScrollGeneration=${userScrollGeneration.value}"
            )
            // A completed prepend consumes every pointer move observed through
            // that cycle. Require a fresh direct drag before another older page
            // can start; otherwise the transient top layout after 50 -> 100
            // rows can immediately cascade into 100 -> 150 without user demand.
            olderPageSettledUserScrollGeneration = userScrollGeneration.value
            olderPageCycleActive = false
        }
        previousOlderMessagesStatus = renderPayload.olderMessagesStatus
    }
    LaunchedEffect(
        threadInstanceKey,
        initialBottomAnchorReady,
        renderPayload.canLoadMoreAfter,
        renderPayload.newerMessagesStatus,
        newerPageCycleActive,
        lastSettledNewerDemandGeneration,
        latestMessageListItemIndex
    ) {
        snapshotFlow {
            val lastVisibleIndex =
                listState.layoutInfo.visibleItemsInfo.maxOfOrNull { item -> item.index } ?: -1
            shouldThreadAutoLoadNewerMessages(
                initialBottomAnchorReady = initialBottomAnchorReady,
                canLoadMoreAfter = renderPayload.canLoadMoreAfter,
                newerMessagesStatus = renderPayload.newerMessagesStatus,
                newerPageCycleActive = newerPageCycleActive,
                lastVisibleIndex = lastVisibleIndex,
                latestMessageListItemIndex = latestMessageListItemIndex,
                // Observed (task #258): a wheel/trackpad scroll at the bottom changes no layout, only the count.
                newerDemandGeneration = newerGestureDemandTracker.observedGeneration,
                lastSettledNewerDemandGeneration = lastSettledNewerDemandGeneration,
                listScrolls = listState.canScrollForward || listState.canScrollBackward,
            )
        }
            .distinctUntilChanged()
            .collect { shouldLoad ->
                if (shouldLoad) {
                    requestNewerPage("loadNewer")
                }
            }
    }
}

/** Whether the typing line showed in the last applied composition (written only from its SideEffect). */
private class ThreadTypingLineEndSeen(
    var shown: Boolean
)

/**
 * The typing / working line came or went ([lineShown] flipped; #proj-mobile:#271, web
 * AgentTypingListEnd): the layout after this composition grows or shrinks the list's end by the
 * line's slot ([threadBottomSpacerAndAnchorTrailingHeight]).
 *
 * The bottom-up list keeps a reader resting on the end there by itself (the rows ride with it) and
 * one scrolled into the history never sees the end change, so nothing scrolls for either. A reader
 * at the end under another intent (opened on the newest message, a focused tail, a drag let go
 * just above the end) is retired to FollowingLatest here, after the composition and before that
 * layout, as the keyboard correction retires a parked tail: the reading anchor keeper owns only
 * while not following, so it does not put the old position back under the line, and later replays
 * of the intent pin the end instead of an offset taken before it changed.
 *
 * Owner: the end the reader is at. Fence ([threadTypingLineEndFollowsLatest]), from the last
 * layout: the end row in view when the line comes ([listEndInView]), resting on the end when it
 * goes ([restsOnListEnd]); no scroll in progress, a settled placement, the live tail loaded, never
 * over a drag. Decided synchronously once per change of the line, no delayed job; the Latest
 * command it emits goes through the controller's executor and closes what is left of the gap to
 * the end (nothing when the reader rested on it).
 * Non-restartable, like [ThreadMessagesListOverlays]: it runs whenever the list's content runs.
 */
@Composable
@NonRestartableComposable
private fun ThreadTypingLineEndEffect(
    threadInstanceKey: String,
    lineShown: Boolean,
    listState: ThreadListViewport,
    controllerState: State<LazyViewportControllerState>,
    placementSettled: State<Boolean>,
    newerPagePending: Boolean,
    allowSparseTopFit: Boolean = false,
    dispatchViewportEvent: (LazyViewportEvent) -> Unit,
    diagnosticsLogger: (String) -> Unit,
) {
    val seen = remember(threadInstanceKey) { ThreadTypingLineEndSeen(lineShown) }
    SideEffect {
        if (seen.shown != lineShown) {
            seen.shown = lineShown
            val endInView = listState.listEndInView()
            val restsOnEnd = listState.restsOnListEnd(allowSparseTopFit = allowSparseTopFit)
            val intent = controllerState.value.intent
            val follow =
                threadTypingLineEndFollowsLatest(
                    reverseLayout = listState.reverse,
                    lineShown = lineShown,
                    intent = intent,
                    listEndInView = endInView,
                    restsOnListEnd = restsOnEnd,
                    scrollInProgress = listState.isScrollInProgress,
                    placementSettled = placementSettled.value,
                    newerPagePending = newerPagePending,
                )
            diagnosticsLogger(
                "thread_typing_line_end key=$threadInstanceKey shown=$lineShown endInView=$endInView " +
                    "restsOnEnd=$restsOnEnd intent=${intent.logTag()} action=${if (follow) "follow_latest" else "none"}"
            )
            if (follow) {
                listState.noteDispatchOwner(ThreadViewportOwnerTag.TypingLineEnd)
                dispatchViewportEvent(LazyViewportEvent.FollowLatest(canOverrideUserDragging = false))
            }
        }
    }
}
