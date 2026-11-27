package build.raft.app.thread

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import build.raft.app.compat.ui.LocalRaftHostImeInsets
import build.raft.app.compat.ui.raftImeBottomPx
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Usable main-axis viewport height of a thread `LazyColumn`, subtracting both content paddings.
 * Reading from `listState.raw.layoutInfo` avoids per-frame `ReversedThreadLayoutInfo` allocations;
 * `beforeContentPadding + afterContentPadding` is commutative so raw and forward usable heights match.
 */
internal fun LazyListLayoutInfo.usableViewportHeightPx(): Int =
    ((viewportEndOffset - viewportStartOffset) - beforeContentPadding - afterContentPadding)
        .coerceAtLeast(0)

/**
 * Remembered initial/programmatic focus placement (web `timelinePlacement.ts`, `botiverse/slock`
 * `#17076`): kept armed across viewport height changes until the reader takes over (scroll, drag,
 * wheel, IME open, back-to-bottom, or another reading action).
 */
internal data class ThreadTimelinePlacement(
    val messageId: String,
    val placement: LazyViewportAnchorPlacement,
    val startUserScrollGeneration: Int,
    val viewportHeightPx: Int?,
    val viewportWidthPx: Int?,
)

internal sealed interface ThreadTimelinePlacementResizeDecision {
    data object None : ThreadTimelinePlacementResizeDecision

    data object Cleared : ThreadTimelinePlacementResizeDecision

    data class Replace(
        val messageId: String,
        val placement: LazyViewportAnchorPlacement,
        val previousHeightPx: Int,
        val nextHeightPx: Int,
    ) : ThreadTimelinePlacementResizeDecision
}

/**
 * Pure state machine mirroring web `useTimelinePlacement` (`timelinePlacement.ts`, `slock #17076`):
 * tracks the last placed focused message and its viewport height so late chrome (announcement strip,
 * notices, offline tip, restored multi-line composer draft) that shrinks or grows the timeline
 * before the reader scrolls re-places the focused message instead of pushing it off-screen or
 * shifting its aligned position.
 */
internal class ThreadTimelinePlacementTracker {
    var current: ThreadTimelinePlacement? = null
        private set

    /**
     * Monotonic counter incremented after a height-change re-placement command settles; watched by
     * [ThreadMessagesListFocusEffects] so its short stabilization watch loop re-baselines
     * `anchoredOffset` instead of fighting the new geometry with a stale pre-resize offset.
     */
    var rePlacementSettledGeneration: Int = 0
        private set

    fun place(
        messageId: String,
        placement: LazyViewportAnchorPlacement,
        userScrollGeneration: Int,
        viewportHeightPx: Int?,
        viewportWidthPx: Int?,
    ) {
        if (
            messageId.isBlank() ||
            (placement != LazyViewportAnchorPlacement.Focused &&
                placement != LazyViewportAnchorPlacement.FocusedThreadReplies)
        ) {
            current = null
            return
        }
        current =
            ThreadTimelinePlacement(
                messageId = messageId,
                placement = placement,
                startUserScrollGeneration = userScrollGeneration,
                viewportHeightPx = viewportHeightPx?.takeIf { it > 0 },
                viewportWidthPx = viewportWidthPx?.takeIf { it > 0 },
            )
    }

    /**
     * Records placement only if this exact `(messageId, placement)` is not already armed (so a
     * Frame-1 pre-first-draw height recorded by [ThreadInitialFocusedPlacementOwner] is not
     * overwritten by a post-shrink height when `LaunchedEffect` runs on Frame 2).
     */
    fun placeIfAbsent(
        messageId: String,
        placement: LazyViewportAnchorPlacement,
        userScrollGeneration: Int,
        viewportHeightPx: Int?,
        viewportWidthPx: Int?,
    ) {
        val existing = current
        if (existing != null && existing.messageId == messageId && existing.placement == placement) {
            return
        }
        place(messageId, placement, userScrollGeneration, viewportHeightPx, viewportWidthPx)
    }

    fun clear() {
        current = null
    }

    fun noteRePlacementSettled() {
        rePlacementSettledGeneration += 1
    }

    fun onViewportGeometry(
        viewportHeightPx: Int,
        viewportWidthPx: Int,
        currentUserScrollGeneration: Int,
        scrollInProgress: Boolean,
        imeOpen: Boolean,
        followingLatest: Boolean,
    ): ThreadTimelinePlacementResizeDecision {
        val placed = current ?: return ThreadTimelinePlacementResizeDecision.None
        if (
            currentUserScrollGeneration != placed.startUserScrollGeneration ||
            scrollInProgress ||
            imeOpen ||
            followingLatest
        ) {
            current = null
            return ThreadTimelinePlacementResizeDecision.Cleared
        }
        if (viewportHeightPx <= 0) return ThreadTimelinePlacementResizeDecision.None
        val cleanWidth = viewportWidthPx.takeIf { it > 0 }
        val previousHeight = placed.viewportHeightPx
        val previousWidth = placed.viewportWidthPx
        // Always update stored dimensions (matching web `timelinePlacement.ts`): a width change
        // yields to width-resize ownership without emitting a height-only Replace, while a later
        // height-only change compares against the post-width-change height.
        current =
            placed.copy(
                viewportHeightPx = viewportHeightPx,
                viewportWidthPx = cleanWidth ?: previousWidth,
            )
        val widthChanged = previousWidth != null && cleanWidth != null && cleanWidth != previousWidth
        if (widthChanged) return ThreadTimelinePlacementResizeDecision.None
        if (previousHeight != null && viewportHeightPx != previousHeight) {
            return ThreadTimelinePlacementResizeDecision.Replace(
                messageId = placed.messageId,
                placement = placed.placement,
                previousHeightPx = previousHeight,
                nextHeightPx = viewportHeightPx,
            )
        }
        return ThreadTimelinePlacementResizeDecision.None
    }
}

private data class ThreadTimelinePlacementGeometrySnapshot(
    val usableHeightPx: Int,
    val widthPx: Int,
    val scrollInProgress: Boolean,
    val hasPendingCommand: Boolean,
    val userDragging: Boolean,
    val userScrollGeneration: Int,
    val imeOpen: Boolean,
    val olderPageCycleActive: Boolean,
    val newerPageCycleActive: Boolean,
    val shareSelectionCaptureActive: Boolean,
    val adaptiveAnchorRestoreInFlight: Boolean,
)

/**
 * Leaf composable observing usable timeline viewport height changes and re-placing the remembered
 * focused message while the reader has not taken over (`#wg-mobile-sync:#350`, `slock #17076`).
 *
 * Isolated as a separate composable so [ThreadMessagesListContent] never recomposes on scroll,
 * IME inset frames, or page-pop transitions. All live IME / layout / controller reads occur inside
 * [snapshotFlow] (never in composition), and all scrolls run through [dispatchViewportEvent] under
 * [ThreadListScrollOwnerEffect] (`ownerLive()` + `LocalRaftStackPageLeaving` fences).
 */
@Composable
internal fun ThreadTimelinePlacementResizeEffect(
    threadInstanceKey: String,
    tracker: ThreadTimelinePlacementTracker,
    listState: ThreadListViewport,
    userScrollGeneration: ThreadUserScrollGeneration,
    imeProgrammaticViewportPolicy: ThreadImeProgrammaticViewportPolicy,
    currentPayloadState: State<ThreadTimelineSlice>,
    viewportControllerMutableState: State<LazyViewportControllerState>,
    viewportLayoutGenerationState: State<Long>,
    liveComposerKeyboardInsetDpState: State<Float>,
    olderPageCycleActiveState: State<Boolean>,
    newerPageCycleActiveState: State<Boolean>,
    shareSelectionCaptureActiveState: State<Boolean>,
    adaptiveAnchorRestoreInFlightState: State<Boolean>,
    dispatchViewportEvent: (LazyViewportEvent) -> Unit,
    awaitViewportCommandSettlement: suspend (LazyViewportCommandId) -> Unit,
    diagnosticsLogger: (String) -> Unit,
) {
    // Direct drag clears placement (catches Robolectric swipeDown() as well as touch drags;
    // ignores programmatic scrolls).
    ThreadUserScrollEffect(listState.raw) {
        tracker.clear()
    }
    // Capture non-snapshot handles once in composition; read live IME inset inside snapshotFlow
    // (never in composition) so keyboard animation frames never trigger recomposition (task #990).
    val ime = WindowInsets.ime
    val hostIme = LocalRaftHostImeInsets.current
    val densityState = rememberUpdatedState(LocalDensity.current)

    fun isLiveImeOpen(): Boolean =
        raftImeBottomPx(ime, hostIme, densityState.value) > 0 ||
            liveComposerKeyboardInsetDpState.value > 0f ||
            threadImeOpenSuppressesProgrammaticViewport(
                keyboardInsetDp = liveComposerKeyboardInsetDpState.value,
                policy = imeProgrammaticViewportPolicy,
            )

    ThreadListScrollOwnerEffect(listState, threadInstanceKey, tracker) {
        snapshotFlow {
            val rawInfo = listState.raw.layoutInfo
            val controller = viewportControllerMutableState.value
            ThreadTimelinePlacementGeometrySnapshot(
                usableHeightPx = rawInfo.usableViewportHeightPx(),
                widthPx = rawInfo.viewportSize.width,
                scrollInProgress = listState.isScrollInProgress,
                hasPendingCommand = controller.pendingCommand != null,
                userDragging = controller.intent == LazyViewportIntent.UserDragging,
                userScrollGeneration = userScrollGeneration.observed,
                imeOpen = isLiveImeOpen(),
                olderPageCycleActive = olderPageCycleActiveState.value,
                newerPageCycleActive = newerPageCycleActiveState.value,
                shareSelectionCaptureActive = shareSelectionCaptureActiveState.value,
                adaptiveAnchorRestoreInFlight = adaptiveAnchorRestoreInFlightState.value,
            )
        }
            .distinctUntilChanged()
            .collect { obs ->
                if (!ownerLive()) return@collect
                if (
                    obs.shareSelectionCaptureActive ||
                    obs.adaptiveAnchorRestoreInFlight ||
                    obs.imeOpen ||
                    obs.userDragging
                ) {
                    tracker.clear()
                    return@collect
                }
                val currentPlaced = tracker.current
                if (currentPlaced != null && obs.userScrollGeneration != currentPlaced.startUserScrollGeneration) {
                    tracker.clear()
                    return@collect
                }
                // Defer (do not clear) while any programmatic scroll, pending viewport command, or
                // pagination cycle is in flight; snapshotFlow re-emits when it settles. Real user
                // drags/scrolls are already retired via ThreadUserScrollEffect, TouchType.Down/Move,
                // userScrollGeneration.observed, and LazyViewportIntent.UserDragging.
                if (
                    obs.hasPendingCommand ||
                    obs.scrollInProgress ||
                    obs.olderPageCycleActive ||
                    obs.newerPageCycleActive
                ) {
                    return@collect
                }
                val decision =
                    tracker.onViewportGeometry(
                        viewportHeightPx = obs.usableHeightPx,
                        viewportWidthPx = obs.widthPx,
                        currentUserScrollGeneration = obs.userScrollGeneration,
                        scrollInProgress = false,
                        imeOpen = false,
                        followingLatest = false,
                    )
                if (decision !is ThreadTimelinePlacementResizeDecision.Replace) return@collect
                val targetIndex = currentPayloadState.value.threadMessageListItemIndex(decision.messageId)
                if (targetIndex < 0 || !listState.canScrollToThreadItem(targetIndex)) {
                    return@collect
                }
                if (isLiveImeOpen()) {
                    tracker.clear()
                    return@collect
                }
                if (!ownerLive()) return@collect
                val startGen = userScrollGeneration.value
                val targetAnchor = LazyViewportAnchorTarget.Message(decision.messageId)
                listState.noteDispatchOwner(ThreadViewportOwnerTag.FocusedResize)
                dispatchViewportEvent(
                    LazyViewportEvent.Anchor(
                        target = targetAnchor,
                        offset = 0,
                        layoutGeneration = viewportLayoutGenerationState.value,
                        placement = decision.placement,
                        fallback = LazyViewportAnchorFallback.Free,
                    )
                )
                val commandId = viewportControllerMutableState.value.pendingCommand?.id
                if (commandId != null) {
                    awaitViewportCommandSettlement(commandId)
                }
                if (!ownerLive()) return@collect
                // Verify we were not superseded by user scroll/drag, IME open, or another owner
                // while the scroll command settled.
                val controllerAfter = viewportControllerMutableState.value
                if (
                    userScrollGeneration.value != startGen ||
                    controllerAfter.intent == LazyViewportIntent.UserDragging ||
                    (controllerAfter.intent as? LazyViewportIntent.Anchored)?.target != targetAnchor ||
                    tracker.current?.messageId != decision.messageId ||
                    isLiveImeOpen()
                ) {
                    return@collect
                }
                val settledIndex = currentPayloadState.value.threadMessageListItemIndex(decision.messageId)
                val settledOffset =
                    if (settledIndex >= 0) listState.focusedAnchorTargetOffset(settledIndex) else null
                if (settledOffset != null && decision.placement == LazyViewportAnchorPlacement.Focused) {
                    dispatchViewportEvent(
                        LazyViewportEvent.LockAnchorOffset(
                            target = targetAnchor,
                            offset = settledOffset,
                            layoutGeneration = viewportLayoutGenerationState.value,
                        )
                    )
                }
                tracker.noteRePlacementSettled()
                diagnosticsLogger(
                    "thread_timeline_focus_resize_replace messageId=${decision.messageId.take(8)} " +
                        "fromPx=${decision.previousHeightPx} toPx=${decision.nextHeightPx} " +
                        "placement=${decision.placement}"
                )
            }
    }
}
