package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The thread list's initial bottom placement (with its focused-message yield), scroll-ahead window,
 * composer-layout and keyboard (IME) bottom-anchor effects of [ThreadMessagesListContent], moved here
 * unchanged and called in the same place. Non-restartable, like [ThreadMessagesListOverlays]: it runs
 * whenever the list's content runs, so effect keys, call order and state reads stay those of the
 * list's content.
 */
@Composable
@NonRestartableComposable
internal fun ThreadMessagesListBottomAnchorEffects(
    threadInstanceKey: String,
    renderPayload: ThreadTimelineSlice,
    listState: ThreadListViewport,
    reverseLayout: Boolean,
    latestItemIndex: Int,
    latestMessageListItemIndex: Int,
    latestMessageLayoutKey: String,
    initialBottomAnchorTargetItemIndex: Int,
    initialBottomAnchorTrailingPaddingPx: Int,
    focusedMessageTargetSignature: String,
    composerKeyboardInsetDp: Float,
    viewportMeasurementReady: Boolean,
    threadLazyWindowConsumerCounts: ThreadLazyWindowConsumerCounts,
    shareSelectionCaptureActive: Boolean,
    newerPageCycleActive: Boolean,
    threadDensity: Density,
    imeProgrammaticViewportPolicy: ThreadImeProgrammaticViewportPolicy,
    imeTailDrawProbe: ThreadImeTailDrawProbe,
    imeTailPixelProbeRunner: ThreadImeTailPixelProbeRunner,
    viewportPixelProbeBounds: ThreadViewportPixelProbeBounds,
    postFirstFrameBeyondBoundsEnabledRef: ThreadMutableBooleanRef,
    userScrollGeneration: ThreadUserScrollGeneration,
    currentPayloadState: State<ThreadTimelineSlice>,
    currentSparseTopAnchoredState: State<Boolean>,
    currentThreadInstanceKeyState: State<String>,
    currentViewportControllerState: State<LazyViewportControllerState>,
    currentViewportLayoutGenerationState: State<Long>,
    pendingFocusedMessageAnchorIdentityState: State<String?>,
    dispatchViewportEvent: (LazyViewportEvent) -> Unit,
    awaitViewportCommandSettlement: suspend (LazyViewportCommandId) -> Unit,
    onComposerLayoutProbe: (phase: String, metadata: Map<String, String>) -> Unit,
    onComposerPixelProbe: suspend (Float, Float, Float, Float, Long) -> JSONObject?,
    diagnosticsLogger: (String) -> Unit,
    allowBottomAutoAnchorState: MutableState<Boolean>,
    initialBottomAnchorReadyState: MutableState<Boolean>,
    viewportControllerMutableState: State<LazyViewportControllerState>,
    viewportLayoutGenerationState: MutableState<Long>,
    liveComposerKeyboardInsetDpState: State<Float>,
    adaptiveAnchorRestoreInFlightState: State<Boolean>,
    measuredThreadViewportHeightDpState: State<Float>,
    pendingKeyboardBottomAnchorState: MutableState<Boolean>,
    composerLayoutViewportRetryGenerationState: MutableState<Long>,
    lastComposerKeyboardInsetDpState: MutableState<Float>,
    lastThreadViewportHeightDpState: MutableState<Float>,
    pendingComposerLayoutBottomAnchorState: MutableState<Boolean>,
    focusedAnchorState: ThreadFocusedAnchorState,
) {
    var allowBottomAutoAnchor by allowBottomAutoAnchorState
    var initialBottomAnchorReady by initialBottomAnchorReadyState
    val viewportControllerState by viewportControllerMutableState
    var viewportLayoutGeneration by viewportLayoutGenerationState
    val liveComposerKeyboardInsetDp by liveComposerKeyboardInsetDpState
    val adaptiveAnchorRestoreInFlight by adaptiveAnchorRestoreInFlightState
    val measuredThreadViewportHeightDp by measuredThreadViewportHeightDpState
    var pendingKeyboardBottomAnchor by pendingKeyboardBottomAnchorState
    var composerLayoutViewportRetryGeneration by composerLayoutViewportRetryGenerationState
    var lastComposerKeyboardInsetDp by lastComposerKeyboardInsetDpState
    var lastThreadViewportHeightDp by lastThreadViewportHeightDpState
    var pendingComposerLayoutBottomAnchor by pendingComposerLayoutBottomAnchorState
    var consumedFocusedMessageAnchorIdentity by focusedAnchorState::consumedFocusedMessageAnchorIdentity
    var correctedFocusedOriginIdentity by focusedAnchorState::correctedFocusedOriginIdentity
    var focusedAnchorPlacementState by focusedAnchorState::focusedAnchorPlacementState
    var bottomAnchorFocusedYieldElapsedMs by focusedAnchorState::bottomAnchorFocusedYieldElapsedMs
    val scrollInterruptedFocusedMessageAnchorIdentity by focusedAnchorState::scrollInterruptedFocusedMessageAnchorIdentity
    val focusedAnchorSessionIdentity by focusedAnchorState::focusedAnchorSessionIdentity
    val focusedAnchorStartUserScrollGeneration by focusedAnchorState::focusedAnchorStartUserScrollGeneration
    LaunchedEffect(
        threadInstanceKey,
        renderPayload.focusedMessageId,
        renderPayload.focusedMessageRequestNonce,
        focusedMessageTargetSignature,
        latestMessageLayoutKey,
        latestItemIndex,
        initialBottomAnchorTargetItemIndex,
        initialBottomAnchorTrailingPaddingPx,
        shareSelectionCaptureActive,
        newerPageCycleActive
    ) {
        if (shareSelectionCaptureActive || latestItemIndex < 0) return@LaunchedEffect
        if (newerPageCycleActive) {
            allowBottomAutoAnchor = false
            return@LaunchedEffect
        }
        val focusedMessageTargetIndex =
            renderPayload.focusedMessageListItemIndex()
        if (focusedMessageTargetIndex >= 0) {
            return@LaunchedEffect
        }
        // task #567: while a focused-message anchor request is pending, the
        // bottom anchor must not touch the viewport at all — it was the second
        // scroll actor racing the focused anchor on cold entry
        // (the old initial anchor was a multi-step suspend that could
        // land a scroll after the focused anchor positioned the target). The
        // yield is bounded: if the focused target never becomes anchorable
        // (deleted message, failed context fetch), normal bottom anchoring
        // resumes after the cap so the entry is never left blank.
        while (
            shouldYieldBottomAnchorToFocusedAnchor(
                pendingIdentity = pendingFocusedMessageAnchorIdentityState.value,
                consumedIdentity = consumedFocusedMessageAnchorIdentity,
                scrollInterruptedIdentity = scrollInterruptedFocusedMessageAnchorIdentity,
                yieldElapsedMs = bottomAnchorFocusedYieldElapsedMs
            )
        ) {
            delay(FocusedAnchorSessionRecheckIntervalMs)
            bottomAnchorFocusedYieldElapsedMs += FocusedAnchorSessionRecheckIntervalMs
        }
        pendingFocusedMessageAnchorIdentityState.value?.let { pendingIdentity ->
            if (
                !shouldRunFocusedMessageAnchor(
                    targetIdentity = pendingIdentity,
                    consumedIdentity = consumedFocusedMessageAnchorIdentity,
                    scrollInterruptedIdentity = scrollInterruptedFocusedMessageAnchorIdentity
                )
            ) {
                // The focused anchor consumed or the user took over while we
                // waited — either way the viewport is owned; stay out.
                return@LaunchedEffect
            }
            diagnosticsLogger(
                "thread_initial_bottom_anchor result=focused_yield_cap_fallback " +
                    "elapsedMs=$bottomAnchorFocusedYieldElapsedMs"
            )
        }
        when (
            threadInitialBottomAnchorStage(
                initialBottomAnchorReady = initialBottomAnchorReady,
                userScrollInProgress = listState.isScrollInProgress,
                latestItemVisible = listState.isThreadItemVisible(initialBottomAnchorTargetItemIndex)
            )
        ) {
            ThreadInitialBottomAnchorStage.UserOwned -> {
                // A drag during the initial-anchor window wins: reveal the list where
                // the user already is instead of yanking to the bottom (task #554
                // pattern scan — entry-window race).
                initialBottomAnchorReady = true
                return@LaunchedEffect
            }
            ThreadInitialBottomAnchorStage.AdvanceThenAnchor -> Unit
            ThreadInitialBottomAnchorStage.Anchor,
            ThreadInitialBottomAnchorStage.Settled -> Unit
        }
        if (!initialBottomAnchorReady) {
            var initialBottomAnchorRetries = 0
            while (!initialBottomAnchorReady) {
                // Bounded: a copy that can never anchor (a kept-alive, unplaced tab's thread) retried
                // every 80 ms for as long as it stayed composed. Give up and accept the viewport.
                if (++initialBottomAnchorRetries > ThreadInitialBottomAnchorMaxRetries) {
                    diagnosticsLogger(
                        "thread_initial_bottom_anchor result=retry_cap retries=$ThreadInitialBottomAnchorMaxRetries " +
                            "latestItemIndex=$latestItemIndex targetItemIndex=$initialBottomAnchorTargetItemIndex"
                    )
                    initialBottomAnchorReady = true
                    return@LaunchedEffect
                }
                if (adaptiveAnchorRestoreInFlight) {
                    // A4 restore owns the viewport (task #18): yield this frame rather than
                    // dispatching FollowLatest on top of an in-flight staged-anchor scroll.
                    delay(ThreadInitialBottomAnchorRetryDelayMs)
                    continue
                }
                if (listState.isScrollInProgress) {
                    initialBottomAnchorReady = true
                    return@LaunchedEffect
                }
                // task #990 kill-switch: stop-loss platforms read the inset
                // live on every retry. A keyboard that opened mid-loop means
                // the current viewport is accepted as-is — retrying into a
                // shrinking window is the trigger every white capture shares.
                if (
                    suppressInitialBottomRetry(
                        keyboardInsetDp = liveComposerKeyboardInsetDp,
                        policy = imeProgrammaticViewportPolicy,
                        log = diagnosticsLogger,
                    )
                ) {
                    initialBottomAnchorReady = true
                    return@LaunchedEffect
                }
                listState.noteDispatchOwner(ThreadViewportOwnerTag.InitialBottomRetry, initialBottomAnchorRetries)
                dispatchViewportEvent(
                    LazyViewportEvent.FollowLatest(canOverrideUserDragging = false)
                )
                val initialCommandId = viewportControllerState.pendingCommand?.id
                if (initialCommandId != null) {
                    awaitViewportCommandSettlement(initialCommandId)
                }
                val anchored =
                    listState.isLatestItemAtRenderedBottom(
                        latestItemIndex = initialBottomAnchorTargetItemIndex,
                        trailingPaddingSize = initialBottomAnchorTrailingPaddingPx,
                        allowSparseTopFit = currentSparseTopAnchoredState.value,
                    )
                if (anchored) {
                    diagnosticsLogger(
                        "thread_initial_bottom_anchor result=anchored latestItemIndex=$latestItemIndex " +
                            "targetItemIndex=$initialBottomAnchorTargetItemIndex " +
                            "trailingPaddingPx=$initialBottomAnchorTrailingPaddingPx " +
                            listState.threadViewportGeometryDiagnostics(initialBottomAnchorTargetItemIndex)
                    )
                    initialBottomAnchorReady = true
                    return@LaunchedEffect
                }
                diagnosticsLogger(
                    "thread_initial_bottom_anchor result=retry latestItemIndex=$latestItemIndex " +
                        "targetItemIndex=$initialBottomAnchorTargetItemIndex " +
                        "trailingPaddingPx=$initialBottomAnchorTrailingPaddingPx " +
                        "totalItems=${listState.layoutInfo.totalItemsCount} " +
                        "visibleItems=${listState.layoutInfo.visibleItemsInfo.size} " +
                        listState.threadViewportGeometryDiagnostics(initialBottomAnchorTargetItemIndex)
                )
                delay(ThreadInitialBottomAnchorRetryDelayMs)
            }
            return@LaunchedEffect
        }
        if (!allowBottomAutoAnchor && !listState.isLatestItemAtRenderedBottom(latestItemIndex, allowSparseTopFit = currentSparseTopAnchoredState.value)) {
            return@LaunchedEffect
        }
        listState.awaitThreadMessagesLayoutStability(latestItemIndex)
        if (listState.isScrollInProgress && !listState.isLatestItemAtRenderedBottom(latestItemIndex, allowSparseTopFit = currentSparseTopAnchoredState.value)) {
            if (allowBottomAutoAnchor) {
                allowBottomAutoAnchor = false
            }
            return@LaunchedEffect
        }
        if (!listState.isLatestItemAtRenderedBottom(latestItemIndex, allowSparseTopFit = currentSparseTopAnchoredState.value)) {
            // task #990 kill-switch: on stop-loss platforms automatic
            // correction stays silent while the keyboard is open. Read live —
            // this coroutine awaited layout stability and can outlive the rise.
            if (
                suppressAutoBottomHeightChange(
                    keyboardInsetDp = liveComposerKeyboardInsetDp,
                    policy = imeProgrammaticViewportPolicy,
                    log = diagnosticsLogger,
                )
            ) {
                return@LaunchedEffect
            }
            dispatchViewportEvent(LazyViewportEvent.ContentMutated(LazyViewportMutation.HeightChange))
        }
    }
    // #171: content now shows while the page is still sliding in, so the scroll-ahead window also
    // waits for the slide to settle: during the push only the visible rows are composed. Isolate
    // LocalRaftStackPageInMotion inside a leaf so push/pop motion transitions never recompose
    // ThreadMessagesListContent.
    ThreadScrollAheadWindowEffect(
        threadInstanceKey = threadInstanceKey,
        initialBottomAnchorReady = initialBottomAnchorReady,
        viewportMeasurementReady = viewportMeasurementReady,
        listBeyondBoundsItems = threadLazyWindowConsumerCounts.listBeyondBoundsItems,
        enabledRef = postFirstFrameBeyondBoundsEnabledRef,
        diagnosticsLogger = diagnosticsLogger,
    )
    LaunchedEffect(
        threadInstanceKey,
        pendingComposerLayoutBottomAnchor,
        measuredThreadViewportHeightDp,
        pendingFocusedMessageAnchorIdentityState.value,
        latestMessageLayoutKey,
        userScrollGeneration.value
    ) {
        if (!pendingComposerLayoutBottomAnchor) return@LaunchedEffect
        val measuredViewportHeightPx =
            with(threadDensity) { measuredThreadViewportHeightDp.dp.roundToPx() }
        // Observe Lazy layout only while a mixed epoch is pending. Ordinary
        // scroll/remeasure must not invalidate Thread composition or perturb
        // the target dispose/reinsert/draw chain that this probe measures.
        snapshotFlow {
            listState.layoutInfo.viewportStartOffset to
                listState.layoutInfo.viewportEndOffset
        }
            .distinctUntilChanged()
            .first { (layoutStartPx, layoutEndPx) ->
                shouldAdvanceThreadComposerViewportRetry(
                    pendingLayoutChange = pendingComposerLayoutBottomAnchor,
                    measuredViewportHeightPx = measuredViewportHeightPx,
                    layoutViewportStartPx = layoutStartPx,
                    layoutViewportEndPx = layoutEndPx
                )
            }
        if (pendingComposerLayoutBottomAnchor) {
            composerLayoutViewportRetryGeneration += 1L
        }
    }
    LaunchedEffect(
        threadInstanceKey,
        composerKeyboardInsetDp,
        measuredThreadViewportHeightDp,
        composerLayoutViewportRetryGeneration,
        initialBottomAnchorReady,
        latestItemIndex,
        allowBottomAutoAnchor,
        pendingFocusedMessageAnchorIdentityState.value,
        consumedFocusedMessageAnchorIdentity,
        scrollInterruptedFocusedMessageAnchorIdentity,
        focusedAnchorSessionIdentity,
        focusedAnchorStartUserScrollGeneration,
        userScrollGeneration.value,
        latestMessageListItemIndex,
        latestMessageLayoutKey,
        bottomAnchorFocusedYieldElapsedMs,
        newerPageCycleActive
    ) {
        if (newerPageCycleActive) {
            lastComposerKeyboardInsetDp = composerKeyboardInsetDp
            lastThreadViewportHeightDp = measuredThreadViewportHeightDp
            pendingComposerLayoutBottomAnchor = false
            pendingKeyboardBottomAnchor = false
            allowBottomAutoAnchor = false
            return@LaunchedEffect
        }
        // task #990 kill-switch: on stop-loss platforms, the moment the
        // keyboard starts to rise an in-flight programmatic anchor is cancelled
        // rather than allowed to finish into the shrinking window; the gates
        // below then keep every correction path silent while it stays open.
        // The cancel itself lives in a shared seam so the behaviour test
        // executes this code, not a copy of it.
        cancelInflightAnchorOnImeRise(
            keyboardInsetDp = composerKeyboardInsetDp,
            lastKeyboardInsetDp = lastComposerKeyboardInsetDp,
            controllerState = viewportControllerState,
            policy = imeProgrammaticViewportPolicy,
            dispatch = { dispatchViewportEvent(it) },
            log = { diagnosticsLogger(it) },
        )
        val focusedTargetIdentity = pendingFocusedMessageAnchorIdentityState.value
        val focusedTargetMessageId = renderPayload.focusedMessageId?.takeIf { it.isNotBlank() }
        val focusedTargetItemIndex = renderPayload.focusedMessageListItemIndex()
        val focusedTailMessageId = renderPayload.messages.lastOrNull()?.id
        val imeHandoffIdentity =
            focusedAnchorImeHandoffIdentity(
                keyboardInsetDp = composerKeyboardInsetDp,
                targetIdentity = focusedTargetIdentity,
                anchoredIdentity = focusedAnchorSessionIdentity,
                consumedIdentity = consumedFocusedMessageAnchorIdentity,
                scrollInterruptedIdentity = scrollInterruptedFocusedMessageAnchorIdentity,
                startUserScrollGeneration = focusedAnchorStartUserScrollGeneration,
                currentUserScrollGeneration = userScrollGeneration.value
            )
        if (imeHandoffIdentity != null) {
            consumedFocusedMessageAnchorIdentity = imeHandoffIdentity
            val handoffTransfersToBottom =
                focusedAnchorImeHandoffTransfersToBottom(
                    handoffIdentity = imeHandoffIdentity,
                    targetItemIndex = focusedTargetItemIndex,
                    latestMessageItemIndex = latestMessageListItemIndex,
                    canLoadMoreAfter = renderPayload.canLoadMoreAfter
                )
            if (handoffTransfersToBottom) {
                allowBottomAutoAnchor = true
            }
            // The composer now owns the shrunken viewport. A late origin-row
            // correction belongs to the retired route-focus session and must
            // not become a second scroll actor after the handoff.
            correctedFocusedOriginIdentity = imeHandoffIdentity
            diagnosticsLogger(
                "thread_focused_anchor_ime_handoff outcome=consumed " +
                    "bottomAutoAnchor=$handoffTransfersToBottom " +
                    "messageId=${focusedTargetMessageId.orEmpty()}"
            )
        }
        val focusedAnchorOwnsViewport =
            focusedMessageAnchorOwnsViewport(
                pendingIdentity = focusedTargetIdentity,
                consumedIdentity = consumedFocusedMessageAnchorIdentity,
                scrollInterruptedIdentity = scrollInterruptedFocusedMessageAnchorIdentity,
                yieldElapsedMs = bottomAnchorFocusedYieldElapsedMs
            )
        val measuredFocusedTailViewportHeightPx =
            with(threadDensity) { measuredThreadViewportHeightDp.dp.roundToPx() }
        var focusedTailViewportEpochCoherent =
            threadViewportEpochCoherent(
                measuredViewportHeightPx = measuredFocusedTailViewportHeightPx,
                layoutViewportStartPx = listState.layoutInfo.viewportStartOffset,
                layoutViewportEndPx = listState.layoutInfo.viewportEndOffset
            )
        var focusedTailFit =
            listState.threadFocusedTailFit(
                targetItemIndex = focusedTargetItemIndex,
                latestMessageItemIndex = latestMessageListItemIndex,
                bottomPaddingItemIndex = latestItemIndex,
                viewportHeightPx =
                    measuredFocusedTailViewportHeightPx.takeIf { focusedTailViewportEpochCoherent } ?: -1,
                trailingPaddingPx = initialBottomAnchorTrailingPaddingPx
            )
        val measurementViewportEpoch = viewportControllerState.epoch
        val measurementLayoutGeneration = viewportLayoutGeneration
        val focusedTailMeasurementLayoutChanged =
            pendingComposerLayoutBottomAnchor ||
                lastComposerKeyboardInsetDp != composerKeyboardInsetDp ||
                lastThreadViewportHeightDp != measuredThreadViewportHeightDp
        if (focusedTailMeasurementLayoutChanged) {
            if (composerKeyboardInsetDp > 0f) {
                imeTailDrawProbe.beginOrContinue(focusedTargetMessageId ?: focusedTailMessageId)
            } else {
                imeTailDrawProbe.finish()
            }
        }
        fun publishImeTailDrawProbe(phase: String) {
            val snapshot = imeTailDrawProbe.snapshot()
            if (snapshot.generation <= 0L) return
            val metadata =
                mapOf(
                    "probeGeneration" to snapshot.generation.toString(),
                    "targetMessageId" to snapshot.targetMessageId.orEmpty(),
                    "placedCount" to snapshot.placedCount.toString(),
                    "drawCount" to snapshot.drawCount.toString(),
                    "measuredViewportPx" to measuredFocusedTailViewportHeightPx.toString(),
                    "layoutViewportPx" to
                        (listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset).toString(),
                    "viewportEpochCoherent" to focusedTailViewportEpochCoherent.toString(),
                    "targetVisible" to
                        listState.layoutInfo.visibleItemsInfo
                            .any { item -> item.index == focusedTargetItemIndex }
                            .toString()
                )
            diagnosticsLogger(
                "thread_ime_tail_draw_probe phase=$phase " +
                    metadata.entries.joinToString(separator = " ") { (key, value) -> "$key=$value" }
            )
            onComposerLayoutProbe("thread_ime_tail_draw_probe_$phase", metadata)
        }
        fun focusedTailMeasurementOwnerStillCurrent(): Boolean {
            val currentPayload = currentPayloadState.value
            if (
                currentThreadInstanceKeyState.value != threadInstanceKey ||
                currentViewportControllerState.value.epoch != measurementViewportEpoch ||
                currentViewportLayoutGenerationState.value != measurementLayoutGeneration ||
                pendingFocusedMessageAnchorIdentityState.value != focusedTargetIdentity ||
                currentPayload.focusedMessageId != focusedTargetMessageId ||
                currentPayload.messages.lastOrNull()?.id != focusedTailMessageId ||
                currentPayload.latestMessageLayoutKey() != latestMessageLayoutKey ||
                currentPayload.focusedMessageListItemIndex() != focusedTargetItemIndex
            ) {
                return false
            }
            val currentFocusedOwner =
                focusedMessageAnchorOwnsViewport(
                    pendingIdentity = focusedTargetIdentity,
                    consumedIdentity = consumedFocusedMessageAnchorIdentity,
                    scrollInterruptedIdentity = scrollInterruptedFocusedMessageAnchorIdentity,
                    yieldElapsedMs = bottomAnchorFocusedYieldElapsedMs
                )
            return (
                currentFocusedOwner &&
                    focusedActiveTailMessageComposerLayoutOwnsViewport(
                        targetIdentity = focusedTargetIdentity,
                        targetItemIndex = focusedTargetItemIndex,
                        latestMessageItemIndex = latestMessageListItemIndex,
                        targetToTailFitsViewport = true,
                        canLoadMoreAfter = currentPayload.canLoadMoreAfter,
                        anchoredIdentity = focusedAnchorSessionIdentity,
                        consumedIdentity = consumedFocusedMessageAnchorIdentity,
                        scrollInterruptedIdentity = scrollInterruptedFocusedMessageAnchorIdentity,
                        startUserScrollGeneration = focusedAnchorStartUserScrollGeneration,
                        currentUserScrollGeneration = userScrollGeneration.value
                    )
                ) ||
                focusedTailMessageComposerLayoutOwnsViewport(
                    targetIdentity = focusedTargetIdentity,
                    targetItemIndex = focusedTargetItemIndex,
                    latestMessageItemIndex = latestMessageListItemIndex,
                    targetToTailFitsViewport = true,
                    canLoadMoreAfter = currentPayload.canLoadMoreAfter,
                    anchoredIdentity = focusedAnchorSessionIdentity,
                    consumedIdentity = consumedFocusedMessageAnchorIdentity,
                    scrollInterruptedIdentity = scrollInterruptedFocusedMessageAnchorIdentity,
                    startUserScrollGeneration = focusedAnchorStartUserScrollGeneration,
                    currentUserScrollGeneration = userScrollGeneration.value
                )
        }
        if (
            focusedTailMeasurementLayoutChanged &&
            focusedTailFit.fitHeightPx < 0 &&
            focusedTailMeasurementOwnerStillCurrent()
        ) {
            var remainingMeasurementFrames = ThreadFocusedTailMeasurementRetryFrames
            while (remainingMeasurementFrames > 0 && focusedTailFit.fitHeightPx < 0) {
                remainingMeasurementFrames -= 1
                listState.awaitThreadMessagesLayoutFrame()
                if (!focusedTailMeasurementOwnerStillCurrent()) break
                focusedTailViewportEpochCoherent =
                    threadViewportEpochCoherent(
                        measuredViewportHeightPx = measuredFocusedTailViewportHeightPx,
                        layoutViewportStartPx = listState.layoutInfo.viewportStartOffset,
                        layoutViewportEndPx = listState.layoutInfo.viewportEndOffset
                    )
                focusedTailFit =
                    listState.threadFocusedTailFit(
                        targetItemIndex = focusedTargetItemIndex,
                        latestMessageItemIndex = latestMessageListItemIndex,
                        bottomPaddingItemIndex = latestItemIndex,
                        viewportHeightPx =
                            measuredFocusedTailViewportHeightPx
                                .takeIf { focusedTailViewportEpochCoherent }
                                ?: -1,
                        trailingPaddingPx = initialBottomAnchorTrailingPaddingPx
                    )
            }
        }
        val layoutState =
            ThreadComposerLayoutBottomAnchorState(
                previousKeyboardInsetDp = lastComposerKeyboardInsetDp,
                nextKeyboardInsetDp = composerKeyboardInsetDp,
                imeProgrammaticViewportPolicy = imeProgrammaticViewportPolicy,
                previousViewportHeightDp = lastThreadViewportHeightDp,
                nextViewportHeightDp = measuredThreadViewportHeightDp,
                pendingLayoutChange = pendingComposerLayoutBottomAnchor,
                initialBottomAnchorReady = initialBottomAnchorReady,
                latestItemIndex = latestItemIndex,
                allowBottomAutoAnchor = allowBottomAutoAnchor,
                viewportEpochCoherent = focusedTailViewportEpochCoherent,
                focusedAnchorOwnsViewport = focusedAnchorOwnsViewport,
                focusedActiveTailAnchorCanCorrectLayout =
                    focusedAnchorOwnsViewport &&
                        focusedActiveTailMessageComposerLayoutOwnsViewport(
                            targetIdentity = focusedTargetIdentity,
                            targetItemIndex = focusedTargetItemIndex,
                            latestMessageItemIndex = latestMessageListItemIndex,
                            targetToTailFitsViewport = focusedTailFit.fitsViewport,
                            canLoadMoreAfter = renderPayload.canLoadMoreAfter,
                            anchoredIdentity = focusedAnchorSessionIdentity,
                            consumedIdentity = consumedFocusedMessageAnchorIdentity,
                            scrollInterruptedIdentity = scrollInterruptedFocusedMessageAnchorIdentity,
                            startUserScrollGeneration = focusedAnchorStartUserScrollGeneration,
                            currentUserScrollGeneration = userScrollGeneration.value
                        ),
                focusedTailAnchorCanCorrectLayout =
                    focusedTailMessageComposerLayoutOwnsViewport(
                        targetIdentity = focusedTargetIdentity,
                        targetItemIndex = focusedTargetItemIndex,
                        latestMessageItemIndex = latestMessageListItemIndex,
                        targetToTailFitsViewport = focusedTailFit.fitsViewport,
                        canLoadMoreAfter = renderPayload.canLoadMoreAfter,
                        anchoredIdentity = focusedAnchorSessionIdentity,
                        consumedIdentity = consumedFocusedMessageAnchorIdentity,
                        scrollInterruptedIdentity = scrollInterruptedFocusedMessageAnchorIdentity,
                        startUserScrollGeneration = focusedAnchorStartUserScrollGeneration,
                        currentUserScrollGeneration = userScrollGeneration.value
                    )
            )
        val layoutDecision = resolveThreadComposerLayoutBottomAnchor(layoutState)
        val layoutChanged = threadComposerLayoutChanged(layoutState)
        // Only the parked-bottom path replays the controller intent; the focused-tail path
        // dispatches its own RenderedBottom anchor below.
        val insetViewportAction =
            if (layoutDecision.shouldAnchor && !layoutDecision.anchorFocusedTailAtRenderedBottom) {
                resolveThreadComposerInsetViewportAction(
                    reverseLayout = listState.reverse,
                    intent = viewportControllerState.intent,
                    tailAtRenderedBottom = listState.isLatestItemAtRenderedBottom(latestItemIndex, allowSparseTopFit = currentSparseTopAnchoredState.value)
                )
            } else {
                null
            }
        publishImeTailDrawProbe("decision")
        if (layoutChanged || layoutState.pendingLayoutChange) {
            val anchoredIntent = viewportControllerState.intent as? LazyViewportIntent.Anchored
            diagnosticsLogger(
                "thread_composer_layout_decision actor=composer_inset_relayout " +
                    "keyboardInset=${layoutState.previousKeyboardInsetDp}->${layoutState.nextKeyboardInsetDp} " +
                    "viewportHeight=${layoutState.previousViewportHeightDp}->${layoutState.nextViewportHeightDp} " +
                    "pendingBefore=${layoutState.pendingLayoutChange} pendingAfter=${layoutDecision.pendingLayoutChange} " +
                    "shouldAnchor=${layoutDecision.shouldAnchor} " +
                    "placement=${if (layoutDecision.anchorFocusedTailAtRenderedBottom) "rendered_tail_bottom" else "replay_intent"} " +
                    "targetIsLatest=${focusedTargetItemIndex >= 0 && focusedTargetItemIndex == latestMessageListItemIndex} " +
                    "targetIndex=$focusedTargetItemIndex latestMessageIndex=$latestMessageListItemIndex " +
                    "latestViewportItemIndex=$latestItemIndex trailingPaddingPx=$initialBottomAnchorTrailingPaddingPx " +
                    "fitHeightPx=${focusedTailFit.fitHeightPx} fitViewportPx=${focusedTailFit.viewportHeightPx} " +
                    "measuredViewportPx=$measuredFocusedTailViewportHeightPx " +
                    "viewportEpochCoherent=$focusedTailViewportEpochCoherent " +
                    "tailSpanPx=${focusedTailFit.trailingSpanPx} tailFits=${focusedTailFit.fitsViewport} " +
                    "measurementResult=${if (focusedTailFit.fitHeightPx >= 0) "available" else "measurement_unavailable"} " +
                    "canLoadMoreAfter=${renderPayload.canLoadMoreAfter} " +
                    "focusedOwns=$focusedAnchorOwnsViewport consumed=${consumedFocusedMessageAnchorIdentity == focusedTargetIdentity} " +
                    "interrupted=${scrollInterruptedFocusedMessageAnchorIdentity == focusedTargetIdentity} " +
                    "userGeneration=${focusedAnchorStartUserScrollGeneration ?: -1}->${userScrollGeneration.value} " +
                    "viewportIntent=${viewportControllerState.intent.threadViewportIntentDiagnosticName()} " +
                    "viewportPlacement=${anchoredIntent?.placement?.name ?: "none"} " +
                    "insetAction=${insetViewportAction?.diagnosticName ?: "none"} " +
                    listState.threadViewportGeometryDiagnostics(focusedTargetItemIndex)
            )
        }
        lastComposerKeyboardInsetDp = composerKeyboardInsetDp
        lastThreadViewportHeightDp = measuredThreadViewportHeightDp
        pendingComposerLayoutBottomAnchor = layoutDecision.pendingLayoutChange
        if (
            !layoutDecision.shouldAnchor ||
            insetViewportAction == ThreadComposerInsetViewportAction.KeepRenderedPosition
        ) {
            pendingKeyboardBottomAnchor = false
            return@LaunchedEffect
        }
        pendingKeyboardBottomAnchor = true
        viewportLayoutGeneration += 1L
        if (
            layoutDecision.anchorFocusedTailAtRenderedBottom &&
            focusedTargetMessageId != null &&
            focusedTailMessageId != null
        ) {
            focusedAnchorPlacementState =
                renderedThreadFocusedAnchorPlacement(
                    identity = requireNotNull(focusedTargetIdentity),
                    offsetPx = focusedTailFit.trailingSpanPx,
                    renderedTailMessageId = focusedTailMessageId
                )
            dispatchViewportEvent(
                LazyViewportEvent.Anchor(
                    target = LazyViewportAnchorTarget.Message(focusedTargetMessageId),
                    offset = focusedTailFit.trailingSpanPx,
                    layoutGeneration = viewportLayoutGeneration,
                    placement = LazyViewportAnchorPlacement.RenderedBottom,
                    renderedTailMessageId = focusedTailMessageId,
                    renderedTailAnchorIdentity = focusedTargetIdentity,
                    fallback = LazyViewportAnchorFallback.Free
                )
            )
        } else {
            // Parked-bottom owner. On a reversed list a stale Anchored intent is retired to
            // FollowingLatest rather than replayed (replaying top-aligns / centres the row).
            (insetViewportAction ?: ThreadComposerInsetViewportAction.ReplayIntent)
                .viewportEvent()
                ?.let { event ->
                    listState.noteDispatchOwner(ThreadViewportOwnerTag.ImeCorrection)
                    dispatchViewportEvent(event)
                }
        }
        val insetCommandId = viewportControllerState.pendingCommand?.id
        val pixelProbeThreadInstanceKey = threadInstanceKey
        val pixelProbeSnapshot = imeTailDrawProbe.snapshot()
        val pixelProbeGeneration = pixelProbeSnapshot.generation
        val pixelProbeTargetMessageId = pixelProbeSnapshot.targetMessageId
        val pixelProbeLayoutGeneration = viewportLayoutGeneration
        imeTailPixelProbeRunner.launch(
            awaitSettlement = {
                if (insetCommandId != null) {
                    awaitViewportCommandSettlement(insetCommandId)
                }
                // Command settlement commits placement, not necessarily the
                // subsequent native draw. Cross two frames before sampling.
                listState.awaitThreadMessagesLayoutFrame()
                listState.awaitThreadMessagesLayoutFrame()
            },
            runProbe = probe@{
                val settledSnapshot = imeTailDrawProbe.snapshot()
                val settledOwnerCurrent =
                    threadViewportPixelProbeOwnerCurrent(
                        requestedThreadInstanceKey = pixelProbeThreadInstanceKey,
                        currentThreadInstanceKey = currentThreadInstanceKeyState.value,
                        requestedLayoutGeneration = pixelProbeLayoutGeneration,
                        currentLayoutGeneration = currentViewportLayoutGenerationState.value,
                        requestedProbeGeneration = pixelProbeGeneration,
                        currentProbeGeneration = settledSnapshot.generation,
                        requestedTargetMessageId = pixelProbeTargetMessageId,
                        currentTargetMessageId = settledSnapshot.targetMessageId,
                        requestedFocusedMessageId = focusedTargetMessageId,
                        currentFocusedMessageId = currentPayloadState.value.focusedMessageId,
                        requestedTailMessageId = focusedTailMessageId,
                        currentTailMessageId = currentPayloadState.value.messages.lastOrNull()?.id
                    )
                if (!settledOwnerCurrent) {
                    diagnosticsLogger(
                        "thread_ime_tail_pixel_probe generation=$pixelProbeGeneration " +
                            "status=stale_before_claim threadInstanceCurrent=false " +
                            "layoutGeneration=$pixelProbeLayoutGeneration->${currentViewportLayoutGenerationState.value} " +
                            "targetMessageId=${pixelProbeTargetMessageId.orEmpty()}"
                    )
                    return@probe
                }
                pendingKeyboardBottomAnchor = false
                diagnosticsLogger(
                    "thread_composer_layout_settled actor=composer_inset_relayout " +
                        "command=${insetCommandId?.sequence ?: -1} " +
                        "placement=${if (layoutDecision.anchorFocusedTailAtRenderedBottom) "rendered_tail_bottom" else "replay_intent"} " +
                        "targetIndex=$focusedTargetItemIndex latestMessageIndex=$latestMessageListItemIndex " +
                        "latestViewportItemIndex=$latestItemIndex fitHeightPx=${focusedTailFit.fitHeightPx} " +
                        "fitViewportPx=${focusedTailFit.viewportHeightPx} tailSpanPx=${focusedTailFit.trailingSpanPx} " +
                        "measuredViewportPx=$measuredFocusedTailViewportHeightPx " +
                        "viewportEpochCoherent=$focusedTailViewportEpochCoherent " +
                        "renderedBottom=${listState.isLatestItemAtRenderedBottom(latestMessageListItemIndex, initialBottomAnchorTrailingPaddingPx, currentSparseTopAnchoredState.value)} " +
                        "latestItemOffBottom=${listState.shouldShowThreadBackToBottom(latestItemIndex)} " +
                        "viewportIntent=${viewportControllerState.intent.threadViewportIntentDiagnosticName()} " +
                        listState.threadViewportGeometryDiagnostics(focusedTargetItemIndex)
                )
                publishImeTailDrawProbe("settled")
                if (imeTailDrawProbe.claimPixelProbe() != pixelProbeGeneration) {
                    return@probe
                }
                if (!viewportPixelProbeBounds.ready()) {
                    diagnosticsLogger(
                        "thread_ime_tail_pixel_probe generation=$pixelProbeGeneration " +
                            "status=bounds_unavailable threadInstanceCurrent=true " +
                            "layoutGeneration=$pixelProbeLayoutGeneration->${currentViewportLayoutGenerationState.value} " +
                            "targetMessageId=${pixelProbeTargetMessageId.orEmpty()}"
                    )
                    return@probe
                }
                val pixelResult =
                    withTimeoutOrNull(ThreadViewportPixelProbeTimeoutMs) {
                        runCatching {
                            onComposerPixelProbe(
                                viewportPixelProbeBounds.x,
                                viewportPixelProbeBounds.y,
                                viewportPixelProbeBounds.width,
                                viewportPixelProbeBounds.height,
                                pixelProbeGeneration
                            )
                        }.getOrNull()
                    }
                val currentPixelProbeSnapshot = imeTailDrawProbe.snapshot()
                val pixelProbeOwnerCurrent =
                    threadViewportPixelProbeOwnerCurrent(
                        requestedThreadInstanceKey = pixelProbeThreadInstanceKey,
                        currentThreadInstanceKey = currentThreadInstanceKeyState.value,
                        requestedLayoutGeneration = pixelProbeLayoutGeneration,
                        currentLayoutGeneration = currentViewportLayoutGenerationState.value,
                        requestedProbeGeneration = pixelProbeGeneration,
                        currentProbeGeneration = currentPixelProbeSnapshot.generation,
                        requestedTargetMessageId = pixelProbeTargetMessageId,
                        currentTargetMessageId = currentPixelProbeSnapshot.targetMessageId,
                        requestedFocusedMessageId = focusedTargetMessageId,
                        currentFocusedMessageId = currentPayloadState.value.focusedMessageId,
                        requestedTailMessageId = focusedTailMessageId,
                        currentTailMessageId = currentPayloadState.value.messages.lastOrNull()?.id
                    )
                val pixelProbeReceiptMatches =
                    pixelResult?.optString("generation")?.toLongOrNull() == pixelProbeGeneration
                val acceptedPixelResult =
                    pixelResult.takeIf { pixelProbeOwnerCurrent && pixelProbeReceiptMatches }
                val diagnosticStatus =
                    when {
                        pixelResult == null -> "infra_timeout"
                        !pixelProbeOwnerCurrent -> "stale_owner"
                        !pixelProbeReceiptMatches -> "stale_receipt"
                        acceptedPixelResult?.optString("status") == "timeout" -> "infra_timeout"
                        else -> acceptedPixelResult?.optString("status").orEmpty()
                    }
                diagnosticsLogger(
                    "thread_ime_tail_pixel_probe generation=$pixelProbeGeneration " +
                        "status=$diagnosticStatus " +
                        "threadInstanceCurrent=$pixelProbeOwnerCurrent " +
                        "layoutGeneration=$pixelProbeLayoutGeneration->${currentViewportLayoutGenerationState.value} " +
                        "targetMessageId=${pixelProbeTargetMessageId.orEmpty()} " +
                        "pixelCopyResult=${acceptedPixelResult?.optString("pixelCopyResult").orEmpty()} " +
                        "sampleCount=${acceptedPixelResult?.optString("sampleCount").orEmpty()} " +
                        "nonWhiteCount=${acceptedPixelResult?.optString("nonWhiteCount").orEmpty()} " +
                        "darkCount=${acceptedPixelResult?.optString("darkCount").orEmpty()} " +
                        "whitePermille=${acceptedPixelResult?.optString("whitePermille").orEmpty()} " +
                        "descendants=${acceptedPixelResult?.optString("descendants").orEmpty()} " +
                        "visibleDescendants=${acceptedPixelResult?.optString("visibleDescendants").orEmpty()} " +
                        "attachedDescendants=${acceptedPixelResult?.optString("attachedDescendants").orEmpty()} " +
                        "layoutRequestedDescendants=${acceptedPixelResult?.optString("layoutRequestedDescendants").orEmpty()} " +
                        "rootChild=${acceptedPixelResult?.optString("rootChild").orEmpty()}"
                )
            }
        )
    }
}

private fun LazyViewportIntent.threadViewportIntentDiagnosticName(): String =
    when (this) {
        LazyViewportIntent.FollowingLatest -> "following_latest"
        LazyViewportIntent.PinnedTop -> "pinned_top"
        is LazyViewportIntent.Anchored -> "anchored"
        LazyViewportIntent.UserDragging -> "user_dragging"
    }

private const val ThreadInitialBottomAnchorRetryDelayMs = 80L

private const val ThreadFocusedTailMeasurementRetryFrames = 2
private const val ThreadViewportPixelProbeTimeoutMs = 2_500L
