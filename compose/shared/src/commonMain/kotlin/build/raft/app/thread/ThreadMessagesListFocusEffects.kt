package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.designsystem.components.RaftLocatedMillis
import kotlinx.coroutines.delay

/**
 * The thread list's local-send bottom session, route focused-message session, focused origin
 * correction, inline-search jump and focus-highlight effects of [ThreadMessagesListContent], moved
 * here unchanged and called in the same place. Non-restartable, like [ThreadMessagesListOverlays]:
 * it runs whenever the list's content runs, so effect keys, call order and state reads stay those of
 * the list's content.
 */
@Composable
@NonRestartableComposable
internal fun ThreadMessagesListFocusEffects(
    threadInstanceKey: String,
    renderPayload: ThreadTimelineSlice,
    listState: ThreadListViewport,
    lazyItemCount: Int,
    latestItemIndex: Int,
    latestLocalSendBottomAnchorKey: String?,
    newerPageCycleActive: Boolean,
    focusedMessageTargetSignature: String,
    initialFocusedAnchorIdentity: String?,
    initialFocusedPlacementOwner: ThreadInitialFocusedPlacementOwner,
    inlineSearchVisible: Boolean,
    activeInlineSearchMatch: ThreadInlineSearchMatch?,
    jumpInputs: ThreadJumpInputs,
    imeProgrammaticViewportPolicy: ThreadImeProgrammaticViewportPolicy,
    userScrollGeneration: ThreadUserScrollGeneration,
    currentPayloadState: State<ThreadTimelineSlice>,
    currentLatestItemIndexState: State<Int>,
    currentSparseTopAnchoredState: State<Boolean>,
    dispatchViewportEvent: (LazyViewportEvent) -> Unit,
    awaitViewportCommandSettlement: suspend (LazyViewportCommandId) -> Unit,
    diagnosticsLogger: (String) -> Unit,
    allowBottomAutoAnchorState: MutableState<Boolean>,
    initialBottomAnchorReadyState: MutableState<Boolean>,
    viewportControllerMutableState: State<LazyViewportControllerState>,
    viewportLayoutGenerationState: State<Long>,
    latestLocalSendBottomAnchorActiveKeyState: MutableState<String?>,
    latestLocalSendBottomAnchorStartUserScrollGenerationState: MutableState<Int>,
    activeFocusedMessageIdState: MutableState<String?>,
    liveComposerKeyboardInsetDpState: State<Float>,
    focusedAnchorState: ThreadFocusedAnchorState,
) {
    var allowBottomAutoAnchor by allowBottomAutoAnchorState
    var initialBottomAnchorReady by initialBottomAnchorReadyState
    val viewportControllerState by viewportControllerMutableState
    val viewportLayoutGeneration by viewportLayoutGenerationState
    var latestLocalSendBottomAnchorActiveKey by latestLocalSendBottomAnchorActiveKeyState
    var latestLocalSendBottomAnchorStartUserScrollGeneration by latestLocalSendBottomAnchorStartUserScrollGenerationState
    var activeFocusedMessageId by activeFocusedMessageIdState
    val liveComposerKeyboardInsetDp by liveComposerKeyboardInsetDpState
    var consumedFocusedMessageAnchorIdentity by focusedAnchorState::consumedFocusedMessageAnchorIdentity
    var scrollInterruptedFocusedMessageAnchorIdentity by focusedAnchorState::scrollInterruptedFocusedMessageAnchorIdentity
    var focusedAnchorSessionIdentity by focusedAnchorState::focusedAnchorSessionIdentity
    var focusedAnchorSessionElapsedMs by focusedAnchorState::focusedAnchorSessionElapsedMs
    var focusedAnchorSessionReanchors by focusedAnchorState::focusedAnchorSessionReanchors
    var focusedAnchorStartUserScrollGeneration by focusedAnchorState::focusedAnchorStartUserScrollGeneration
    var correctedFocusedOriginIdentity by focusedAnchorState::correctedFocusedOriginIdentity
    var focusedAnchorPlacementState by focusedAnchorState::focusedAnchorPlacementState
    val bottomAnchorFocusedYieldElapsedMs by focusedAnchorState::bottomAnchorFocusedYieldElapsedMs
    val initialInFlightLocalSendKey = remember(threadInstanceKey) { latestLocalSendBottomAnchorKey }
    LaunchedEffect(
        threadInstanceKey,
        latestLocalSendBottomAnchorKey,
        newerPageCycleActive
    ) {
        if (newerPageCycleActive) {
            allowBottomAutoAnchor = false
            return@LaunchedEffect
        }
        if (
            !shouldAnchorLatestLocalSendToBottom(
                latestLocalSendBottomAnchorKey,
                currentLatestItemIndexState.value
            )
        ) {
            return@LaunchedEffect
        }
        if (latestLocalSendBottomAnchorKey != initialInFlightLocalSendKey) {
            listState.noteUserNavigation()
        }
        latestLocalSendBottomAnchorStartUserScrollGeneration = userScrollGeneration.value
        latestLocalSendBottomAnchorActiveKey = latestLocalSendBottomAnchorKey
        allowBottomAutoAnchor = true
        dispatchViewportEvent(
            LazyViewportEvent.FollowLatest(canOverrideUserDragging = false)
        )
    }
    LaunchedEffect(threadInstanceKey, latestLocalSendBottomAnchorActiveKey) {
        val anchorKey = latestLocalSendBottomAnchorActiveKey ?: return@LaunchedEffect
        val startUserScrollGeneration = latestLocalSendBottomAnchorStartUserScrollGeneration
        listState.awaitThreadMessagesLayoutFrame()
        listState.awaitThreadMessagesLayoutStability(currentLatestItemIndexState.value)
        var attempt = 0
        while (
            shouldContinueLatestLocalSendBottomAnchor(
                activeAnchorKey = latestLocalSendBottomAnchorActiveKey,
                targetAnchorKey = anchorKey,
                startUserScrollGeneration = startUserScrollGeneration,
                currentUserScrollGeneration = userScrollGeneration.value,
                isAtRenderedBottom = listState.isLatestItemAtRenderedBottom(
                    latestItemIndex = currentLatestItemIndexState.value,
                    allowSparseTopFit = currentSparseTopAnchoredState.value,
                ),
                isSendingAnchorVisible = currentPayloadState.value.hasSendingLocalSendAnchor(anchorKey),
                attempt = attempt
            )
        ) {
            dispatchViewportEvent(LazyViewportEvent.ContentMutated(LazyViewportMutation.Append))
            val commandId = viewportControllerState.pendingCommand?.id
            if (commandId != null) {
                awaitViewportCommandSettlement(commandId)
            }
            delay(ThreadLayoutStabilityFrameDelayMs)
            attempt += 1
        }
        if (latestLocalSendBottomAnchorActiveKey == anchorKey) {
            latestLocalSendBottomAnchorActiveKey = null
        }
    }
    LaunchedEffect(
        threadInstanceKey,
        renderPayload.focusedMessageId,
        renderPayload.focusedMessageRequestNonce,
        focusedMessageTargetSignature,
        lazyItemCount,
        jumpInputs.jumpFocusNonce
    ) {
        val targetMessageId = renderPayload.focusedMessageId?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val targetIdentity =
            focusedMessageAnchorIdentity(
                messageId = targetMessageId,
                requestNonce = renderPayload.focusedMessageRequestNonce
            )
        // Task #73: the unread-thread pill's own focus request lands on the parent's replies
        // summary; every other focus (links, search, Activity) keeps the top-aligned placement.
        val basePlacement =
            threadFocusedAnchorBasePlacement(
                requestNonce = renderPayload.focusedMessageRequestNonce,
                unreadThreadJumpFocusNonce = jumpInputs.jumpFocusNonce
            )
        if (
            !shouldRunFocusedMessageAnchor(
                targetIdentity = targetIdentity,
                consumedIdentity = consumedFocusedMessageAnchorIdentity,
                scrollInterruptedIdentity = scrollInterruptedFocusedMessageAnchorIdentity
            )
        ) {
            return@LaunchedEffect
        }
        val targetIndex = renderPayload.focusedMessageListItemIndex()
        if (targetIndex < 0) {
            return@LaunchedEffect
        }
        listState.awaitThreadMessagesLayoutFrame()
        if (!listState.canScrollToThreadItem(targetIndex)) {
            return@LaunchedEffect
        }
        // A drag that starts between the guard above and this point must win:
        // programmatic scrollToItem would cancel the gesture (task #554).
        if (listState.isScrollInProgress) {
            scrollInterruptedFocusedMessageAnchorIdentity = targetIdentity
            return@LaunchedEffect
        }
        activeFocusedMessageId = targetMessageId
        allowBottomAutoAnchor = false
        initialBottomAnchorReady = true
        // task #990 kill-switch: on stop-loss platforms, a focus request
        // arriving after the keyboard is already open never sees a 0->open
        // transition, so the rise-cancel can never fire for it — the initial
        // anchor itself must not dispatch, and with no anchored intent the
        // HeightChange replay has no source. This is a deliberate safe
        // degradation: the request is DROPPED, not deferred — the inset is not
        // among this effect's keys, so closing the keyboard does not rerun it.
        // Platforms that keep ordinary correction skip this gate.
        if (
            suppressFocusAnchorWhileOpen(
                keyboardInsetDp = liveComposerKeyboardInsetDp,
                targetMessageId = targetMessageId,
                policy = imeProgrammaticViewportPolicy,
                log = diagnosticsLogger,
            )
        ) {
            activeFocusedMessageId = null
            return@LaunchedEffect
        }
        if (
            focusedAnchorPlacementState.identity != targetIdentity ||
            (focusedAnchorPlacementState.placement != LazyViewportAnchorPlacement.RenderedBottom &&
                focusedAnchorPlacementState.placement != basePlacement)
        ) {
            focusedAnchorPlacementState = resetThreadFocusedAnchorPlacement(targetIdentity, basePlacement)
        }
        val targetAnchor = LazyViewportAnchorTarget.Message(targetMessageId)
        val currentVisibleOffset = listState.focusedAnchorTargetOffset(targetIndex)
        if (
            shouldKeepVisibleFocusedAnchorOffset(
                placement = focusedAnchorPlacementState.placement,
                currentVisibleOffset = currentVisibleOffset,
                targetIdentity = targetIdentity,
                sessionIdentity = focusedAnchorSessionIdentity,
                initialPlacementApplied = initialFocusedPlacementOwner.applied,
                initialFocusedIdentity = initialFocusedAnchorIdentity,
            ) && currentVisibleOffset != null
        ) {
            if ((viewportControllerState.intent as? LazyViewportIntent.Anchored)?.target != targetAnchor) {
                dispatchViewportEvent(
                    LazyViewportEvent.Anchor(
                        target = targetAnchor,
                        offset = currentVisibleOffset,
                        layoutGeneration = viewportLayoutGeneration,
                        placement = LazyViewportAnchorPlacement.ExactOffset,
                        fallback = LazyViewportAnchorFallback.Free
                    )
                )
            }
            dispatchViewportEvent(
                LazyViewportEvent.LockAnchorOffset(
                    target = targetAnchor,
                    offset = currentVisibleOffset,
                    layoutGeneration = viewportLayoutGeneration
                )
            )
        } else {
            listState.noteDispatchOwner(threadFocusedDispatchOwnerTag(focusedAnchorPlacementState.placement))
            dispatchViewportEvent(
                LazyViewportEvent.Anchor(
                    target = targetAnchor,
                    offset = focusedAnchorPlacementState.offsetPx,
                    layoutGeneration = viewportLayoutGeneration,
                    placement = focusedAnchorPlacementState.placement,
                    renderedTailMessageId =
                        focusedAnchorPlacementState.renderedTailMessageId.takeIf {
                            focusedAnchorPlacementState.identity == targetIdentity &&
                                focusedAnchorPlacementState.placement ==
                                    LazyViewportAnchorPlacement.RenderedBottom
                        },
                    renderedTailAnchorIdentity =
                        focusedAnchorPlacementState.identity.takeIf {
                            it == targetIdentity &&
                                focusedAnchorPlacementState.placement ==
                                    LazyViewportAnchorPlacement.RenderedBottom &&
                                focusedAnchorPlacementState.renderedTailMessageId != null
                        },
                    fallback = LazyViewportAnchorFallback.Free
                )
            )
            val focusCommandId = viewportControllerState.pendingCommand?.id
            if (focusCommandId != null) {
                awaitViewportCommandSettlement(focusCommandId)
            }
            val settledOffset = listState.focusedAnchorTargetOffset(targetIndex)
            if (settledOffset != null && focusedAnchorPlacementState.placement == LazyViewportAnchorPlacement.Focused) {
                dispatchViewportEvent(
                    LazyViewportEvent.LockAnchorOffset(
                        target = targetAnchor,
                        offset = settledOffset,
                        layoutGeneration = viewportLayoutGeneration
                    )
                )
            }
        }
        val didFocus = listState.focusedAnchorTargetOffset(targetIndex) != null
        if (!didFocus) {
            diagnosticsLogger(
                "thread_focused_message_anchor result=scroll_skipped_unmeasured " +
                    "messageId=$targetMessageId targetIndex=$targetIndex"
            )
            activeFocusedMessageId = null
            return@LaunchedEffect
        }
        focusedAnchorStartUserScrollGeneration = userScrollGeneration.value
        correctedFocusedOriginIdentity = null
        // task #567 first-principles invariant: once anchored, the target must
        // hold its viewport position until the user scrolls. The list is
        // item-key anchored and the target itself becomes the anchor item
        // after scrollToItem(target, 0), so drift can only come from another
        // scroll actor or an anchor handoff moment — instead of trusting any
        // single culprit fix, watch the position until it is provably quiet
        // (or the cap expires) and silently re-pin on drift. scrollToItem is
        // non-animated: corrections are invisible unless something moved the
        // viewport, in which case they restore what the user was promised.
        if (focusedAnchorSessionIdentity != targetIdentity) {
            focusedAnchorSessionIdentity = targetIdentity
            focusedAnchorSessionElapsedMs = 0L
            focusedAnchorSessionReanchors = 0
        }
        var anchoredOffset = listState.focusedAnchorTargetOffset(targetIndex)
        var quietMs = 0L
        var driftMaxPx = 0
        while (true) {
            delay(FocusedAnchorSessionRecheckIntervalMs)
            focusedAnchorSessionElapsedMs += FocusedAnchorSessionRecheckIntervalMs
            // task #1010: opening the IME hands viewport ownership from the
            // one-shot route focus to the composer. The composer effect marks
            // the identity consumed before it evaluates the inset layout; a
            // suspended focus watcher must observe that retirement before it
            // can re-pin into the shrinking viewport.
            if (consumedFocusedMessageAnchorIdentity == targetIdentity) {
                diagnosticsLogger(
                    "thread_focused_anchor_session outcome=consumed_ime_handoff " +
                        "reanchors=$focusedAnchorSessionReanchors driftMaxPx=$driftMaxPx " +
                        "elapsedMs=$focusedAnchorSessionElapsedMs messageId=$targetMessageId"
                )
                return@LaunchedEffect
            }
            val currentOffset = listState.focusedAnchorTargetOffset(targetIndex)
            val currentUserScrollGeneration = userScrollGeneration.value
            val rawScrollInProgress = listState.isScrollInProgress
            val explicitInterrupt = scrollInterruptedFocusedMessageAnchorIdentity == targetIdentity
            val action =
                focusedAnchorSessionAction(
                    keyboardOpen =
                        threadImeOpenSuppressesProgrammaticViewport(
                            keyboardInsetDp = liveComposerKeyboardInsetDp,
                            policy = imeProgrammaticViewportPolicy,
                        ),
                    userInteracting =
                        focusedAnchorSessionUserOverrideActive(
                            startUserScrollGeneration = focusedAnchorStartUserScrollGeneration,
                            currentUserScrollGeneration = currentUserScrollGeneration,
                            targetIdentity = targetIdentity,
                            scrollInterruptedIdentity = scrollInterruptedFocusedMessageAnchorIdentity
                        ),
                    drifted = focusedAnchorTargetDrifted(anchoredOffset, currentOffset),
                    quietMs = quietMs,
                    elapsedMs = focusedAnchorSessionElapsedMs
                )
            when (action) {
                FocusedAnchorSessionAction.Interrupt -> {
                    scrollInterruptedFocusedMessageAnchorIdentity = targetIdentity
                    diagnosticsLogger(
                        "thread_focused_anchor_session outcome=interrupted " +
                            "reanchors=$focusedAnchorSessionReanchors driftMaxPx=$driftMaxPx " +
                            "elapsedMs=$focusedAnchorSessionElapsedMs messageId=$targetMessageId " +
                            "rawScrollInProgress=$rawScrollInProgress explicitInterrupt=$explicitInterrupt " +
                            "startUserScrollGeneration=$focusedAnchorStartUserScrollGeneration " +
                            "currentUserScrollGeneration=$currentUserScrollGeneration"
                    )
                    return@LaunchedEffect
                }
                FocusedAnchorSessionAction.Consume -> {
                    consumedFocusedMessageAnchorIdentity = targetIdentity
                    val outcome =
                        if (quietMs >= FocusedAnchorSessionQuietMs) "consumed_quiet" else "consumed_cap"
                    diagnosticsLogger(
                        "thread_focused_anchor_session outcome=$outcome " +
                            "reanchors=$focusedAnchorSessionReanchors driftMaxPx=$driftMaxPx " +
                            "elapsedMs=$focusedAnchorSessionElapsedMs messageId=$targetMessageId"
                    )
                    return@LaunchedEffect
                }
                FocusedAnchorSessionAction.Reanchor -> {
                    focusedAnchorSessionReanchors += 1
                    val driftPx = focusedAnchorDriftMagnitude(anchoredOffset, currentOffset)
                    driftMaxPx = maxOf(driftMaxPx, driftPx)
                    diagnosticsLogger(
                        "thread_focused_anchor_session_drift deltaPx=$driftPx " +
                            "reanchor=$focusedAnchorSessionReanchors targetIndex=$targetIndex " +
                            "originStatus=${renderPayload.originStatus} totalItems=${listState.layoutInfo.totalItemsCount} " +
                            "firstVisible=${listState.firstVisibleItemIndex} messageId=$targetMessageId"
                    )
                    val resolvedPlacement =
                        focusedAnchorPlacementForIdentity(
                            targetIdentity = targetIdentity,
                            placementIdentity = focusedAnchorPlacementState.identity,
                            placement = focusedAnchorPlacementState.placement
                        )
                    val reanchorTarget =
                        resolveThreadFocusedReanchorTarget(
                            anchoredOffset = anchoredOffset,
                            resolvedPlacement = resolvedPlacement,
                            fallbackOffsetPx =
                                focusedAnchorPlacementOffsetForIdentity(
                                    targetIdentity = targetIdentity,
                                    placementIdentity = focusedAnchorPlacementState.identity,
                                    placement = focusedAnchorPlacementState.placement,
                                    placementOffsetPx = focusedAnchorPlacementState.offsetPx
                                ),
                        )
                    listState.noteDispatchOwner(threadFocusedDispatchOwnerTag(resolvedPlacement))
                    dispatchViewportEvent(
                        LazyViewportEvent.Anchor(
                            target = targetAnchor,
                            offset = reanchorTarget.offsetPx,
                            layoutGeneration = viewportLayoutGeneration,
                            placement = reanchorTarget.placement,
                            renderedTailMessageId = focusedAnchorRenderedTailMessageIdForIdentity(
                                targetIdentity = targetIdentity,
                                placementIdentity = focusedAnchorPlacementState.identity,
                                placement = focusedAnchorPlacementState.placement,
                                renderedTailMessageId = focusedAnchorPlacementState.renderedTailMessageId
                            ),
                            renderedTailAnchorIdentity =
                                focusedAnchorPlacementState.identity.takeIf {
                                    it == targetIdentity &&
                                        focusedAnchorPlacementState.placement ==
                                            LazyViewportAnchorPlacement.RenderedBottom &&
                                        focusedAnchorPlacementState.renderedTailMessageId != null
                                },
                            fallback = LazyViewportAnchorFallback.Free
                        )
                    )
                    val reanchorCommandId = viewportControllerState.pendingCommand?.id
                    if (reanchorCommandId != null) {
                        awaitViewportCommandSettlement(reanchorCommandId)
                    }
                    anchoredOffset = listState.focusedAnchorTargetOffset(targetIndex)
                    if (anchoredOffset != null && resolvedPlacement == LazyViewportAnchorPlacement.Focused) {
                        dispatchViewportEvent(
                            LazyViewportEvent.LockAnchorOffset(
                                target = targetAnchor,
                                offset = anchoredOffset,
                                layoutGeneration = viewportLayoutGeneration
                            )
                        )
                    }
                    quietMs = 0L
                }
                FocusedAnchorSessionAction.Continue -> {
                    quietMs += FocusedAnchorSessionRecheckIntervalMs
                }
            }
        }
    }
    LaunchedEffect(
        threadInstanceKey,
        renderPayload.focusedMessageId,
        renderPayload.focusedMessageRequestNonce,
        renderPayload.originStatus,
        renderPayload.originMessage?.id,
        lazyItemCount
    ) {
        val targetMessageId = renderPayload.focusedMessageId?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val targetIdentity =
            focusedMessageAnchorIdentity(
                messageId = targetMessageId,
                requestNonce = renderPayload.focusedMessageRequestNonce
            )
        val startUserScrollGeneration = focusedAnchorStartUserScrollGeneration ?: return@LaunchedEffect
        if (
            !shouldCorrectFocusedAnchorAfterOriginHydration(
                originStatus = renderPayload.originStatus,
                targetIdentity = targetIdentity,
                anchoredIdentity = focusedAnchorSessionIdentity,
                correctedIdentity = correctedFocusedOriginIdentity,
                scrollInterruptedIdentity = scrollInterruptedFocusedMessageAnchorIdentity,
                startUserScrollGeneration = startUserScrollGeneration,
                currentUserScrollGeneration = userScrollGeneration.value,
                keyboardOpen =
                    threadImeOpenSuppressesProgrammaticViewport(
                        keyboardInsetDp = liveComposerKeyboardInsetDp,
                        policy = imeProgrammaticViewportPolicy,
                    )
            )
        ) {
            return@LaunchedEffect
        }
        val targetIndex = renderPayload.focusedMessageListItemIndex()
        if (targetIndex < 0) return@LaunchedEffect
        listState.awaitThreadMessagesLayoutFrame()
        listState.awaitThreadMessagesLayoutStability(targetIndex)
        if (
            !shouldCorrectFocusedAnchorAfterOriginHydration(
                originStatus = renderPayload.originStatus,
                targetIdentity = targetIdentity,
                anchoredIdentity = focusedAnchorSessionIdentity,
                correctedIdentity = correctedFocusedOriginIdentity,
                scrollInterruptedIdentity = scrollInterruptedFocusedMessageAnchorIdentity,
                startUserScrollGeneration = startUserScrollGeneration,
                currentUserScrollGeneration = userScrollGeneration.value,
                keyboardOpen =
                    threadImeOpenSuppressesProgrammaticViewport(
                        keyboardInsetDp = liveComposerKeyboardInsetDp,
                        policy = imeProgrammaticViewportPolicy,
                    )
            ) ||
            listState.isScrollInProgress ||
            !listState.canScrollToThreadItem(targetIndex)
        ) {
            return@LaunchedEffect
        }
        correctedFocusedOriginIdentity = targetIdentity
        val originCorrectionPlacement =
            focusedAnchorPlacementForIdentity(
                targetIdentity = targetIdentity,
                placementIdentity = focusedAnchorPlacementState.identity,
                placement = focusedAnchorPlacementState.placement
            )
        listState.noteDispatchOwner(ThreadViewportOwnerTag.OriginCorrection)
        dispatchViewportEvent(
            LazyViewportEvent.Anchor(
                target = LazyViewportAnchorTarget.Message(targetMessageId),
                offset = focusedAnchorPlacementOffsetForIdentity(
                    targetIdentity = targetIdentity,
                    placementIdentity = focusedAnchorPlacementState.identity,
                    placement = focusedAnchorPlacementState.placement,
                    placementOffsetPx = focusedAnchorPlacementState.offsetPx
                ),
                layoutGeneration = viewportLayoutGeneration,
                placement = originCorrectionPlacement,
                renderedTailMessageId = focusedAnchorRenderedTailMessageIdForIdentity(
                    targetIdentity = targetIdentity,
                    placementIdentity = focusedAnchorPlacementState.identity,
                    placement = focusedAnchorPlacementState.placement,
                    renderedTailMessageId = focusedAnchorPlacementState.renderedTailMessageId
                ),
                renderedTailAnchorIdentity =
                    focusedAnchorPlacementState.identity.takeIf {
                        it == targetIdentity &&
                            focusedAnchorPlacementState.placement ==
                                LazyViewportAnchorPlacement.RenderedBottom &&
                            focusedAnchorPlacementState.renderedTailMessageId != null
                    },
                fallback = LazyViewportAnchorFallback.Free
            )
        )
        val correctionCommandId = viewportControllerState.pendingCommand?.id
        if (correctionCommandId != null) {
            awaitViewportCommandSettlement(correctionCommandId)
        }
        val correctedOffset = listState.focusedAnchorTargetOffset(targetIndex)
        if (correctedOffset != null && originCorrectionPlacement == LazyViewportAnchorPlacement.Focused) {
            dispatchViewportEvent(
                LazyViewportEvent.LockAnchorOffset(
                    target = LazyViewportAnchorTarget.Message(targetMessageId),
                    offset = correctedOffset,
                    layoutGeneration = viewportLayoutGeneration
                )
            )
        }
        val corrected = correctedOffset != null
        diagnosticsLogger(
            "thread_focused_origin_correction result=${if (corrected) "corrected" else "skipped_unmeasured"} " +
                "messageId=$targetMessageId targetIndex=$targetIndex originStatus=${renderPayload.originStatus} " +
                "userScrollGeneration=${userScrollGeneration.value}"
        )
    }
    LaunchedEffect(threadInstanceKey, inlineSearchVisible, activeInlineSearchMatch) {
        val match = activeInlineSearchMatch ?: return@LaunchedEffect
        if (!inlineSearchVisible) return@LaunchedEffect
        listState.awaitThreadMessagesLayoutFrame()
        if (!listState.canScrollToThreadItem(match.listItemIndex)) {
            return@LaunchedEffect
        }
        allowBottomAutoAnchor = false
        initialBottomAnchorReady = true
        listState.noteUserNavigation()
        dispatchViewportEvent(
            LazyViewportEvent.Anchor(
                target = LazyViewportAnchorTarget.Message(match.messageId),
                offset = 0,
                layoutGeneration = viewportLayoutGeneration,
                fallback = LazyViewportAnchorFallback.Free
            )
        )
    }
    LaunchedEffect(threadInstanceKey, activeFocusedMessageId) {
        val highlightedMessageId = activeFocusedMessageId ?: return@LaunchedEffect
        delay(ThreadFocusedMessageHighlightDurationMs)
        if (activeFocusedMessageId == highlightedMessageId) {
            activeFocusedMessageId = null
        }
    }
}

// raft-ui located highlight (0.22.1 #719): the jumped-to row is located for LOCATED_MS.
private const val ThreadFocusedMessageHighlightDurationMs = RaftLocatedMillis.toLong()
