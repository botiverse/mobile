package build.raft.app.thread

import build.raft.app.data.RAFT_FRESHNESS_CONNECTED_FOREGROUND_SKIP_MILLIS
import build.raft.app.data.RaftFreshnessTrigger
import build.raft.app.data.raftForegroundGateNowMillis
import build.raft.app.data.realtime.RaftRealtimeConnectionCauses
import build.raft.app.messages
import build.raft.app.model.formatRaftTimestampLabel
import build.raft.app.thread.toRaftTimeCapabilities
import build.raft.app.thread.toRaftTimeContext
import build.raft.app.toRaftTimeCapabilities
import build.raft.app.toRaftTimeContext
import build.raft.app.model.RaftTimeContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import build.raft.app.settings.RAFT_APP_SHELL_PAGE
import build.raft.app.RaftTimeCapabilities
import androidx.compose.foundation.layout.size

// A focused-message anchor request is identified by (message id, request
// nonce) ONLY. Timeline signature / item count deliberately do not participate:
// they change on secondary refresh, realtime arrivals, and older pages, and a
// consumed request must not re-scroll on any of those (task #554). They still
// key the LaunchedEffect so an unmeasured target gets retried as items load.
internal fun focusedMessageAnchorIdentity(
    messageId: String,
    requestNonce: Int
): String =
    "${messageId.trim()}:$requestNonce"

internal fun shouldRunFocusedMessageAnchor(
    targetIdentity: String,
    consumedIdentity: String?,
    scrollInterruptedIdentity: String?
): Boolean =
    targetIdentity != consumedIdentity && targetIdentity != scrollInterruptedIdentity

// task #567 anchor-session tuning. Rationale, so future re-tuning needs no
// archaeology:
// - Recheck 250ms: a couple of frames past the layout-stability delay — fast
//   enough to correct drift before a user reads it as "wrong position",
//   cheap enough to poll for at most the cap.
// - Quiet 500ms: two consecutive clean rechecks; entry content that has not
//   moved for half a second has finished its late measurements (origin row,
//   image dimensions, markdown) in every trace we have.
// - Hard cap 1500ms: upper bound so a busy thread (typing indicators,
//   realtime arrivals restarting the effect) cannot hold the one-shot open
//   forever; covers the longest first-entry settle observed in the cddfcd85
//   device log (~1.4s from first probe to settled probe).
// - Bottom-yield cap 3000ms: how long the initial bottom anchor stays out of
//   the focused anchor's way before concluding the target will never become
//   anchorable and falling back — 2× the session cap plus fetch slack.
// - Drift tolerance 4px: item offsets are ints and re-layout rounding can
//   jitter by a pixel or two; anything beyond this is a real shift.
internal const val FocusedAnchorSessionRecheckIntervalMs = 250L
internal const val FocusedAnchorSessionQuietMs = 500L
internal const val FocusedAnchorSessionHardCapMs = 1_500L
internal const val FocusedAnchorBottomYieldCapMs = 3_000L
internal const val FocusedAnchorDriftTolerancePx = 4

internal enum class FocusedAnchorSessionAction { Interrupt, Consume, Reanchor, Continue }

internal fun focusedAnchorIdentityForViewportReplacement(
    pendingIdentity: String?,
    sessionIdentity: String?
): String? =
    pendingIdentity ?: sessionIdentity

internal fun focusedAnchorSessionUserOverrideActive(
    startUserScrollGeneration: Int?,
    currentUserScrollGeneration: Int,
    targetIdentity: String,
    scrollInterruptedIdentity: String?
): Boolean =
    scrollInterruptedIdentity == targetIdentity ||
        (
            startUserScrollGeneration != null &&
                startUserScrollGeneration != currentUserScrollGeneration
        )

internal fun shouldCorrectFocusedAnchorAfterOriginHydration(
    originStatus: String,
    targetIdentity: String,
    anchoredIdentity: String?,
    correctedIdentity: String?,
    scrollInterruptedIdentity: String?,
    startUserScrollGeneration: Int,
    currentUserScrollGeneration: Int,
    // task #990 kill-switch: the late origin correction is a programmatic
    // scroll; while the keyboard is open it must not run.
    keyboardOpen: Boolean = false
): Boolean =
    !keyboardOpen &&
        originStatus != "none" &&
        originStatus != "loading" &&
        anchoredIdentity == targetIdentity &&
        correctedIdentity != targetIdentity &&
        scrollInterruptedIdentity != targetIdentity &&
        startUserScrollGeneration == currentUserScrollGeneration

// The session policy as a pure state machine (task #567). Precedence:
// user interaction always wins (#554 semantics), then the hard cap (never
// hold the one-shot open forever), then drift correction, then quiet
// consumption.
internal fun focusedAnchorSessionAction(
    userInteracting: Boolean,
    drifted: Boolean,
    quietMs: Long,
    elapsedMs: Long,
    // task #990 kill-switch: an open keyboard ends the watch quietly instead
    // of reanchoring — a reanchor is a programmatic scroll into the exact
    // window every white capture shows.
    keyboardOpen: Boolean = false
): FocusedAnchorSessionAction =
    when {
        userInteracting -> FocusedAnchorSessionAction.Interrupt
        keyboardOpen -> FocusedAnchorSessionAction.Consume
        elapsedMs >= FocusedAnchorSessionHardCapMs -> FocusedAnchorSessionAction.Consume
        drifted -> FocusedAnchorSessionAction.Reanchor
        quietMs >= FocusedAnchorSessionQuietMs -> FocusedAnchorSessionAction.Consume
        else -> FocusedAnchorSessionAction.Continue
    }

// Drift predicate: the target leaving the viewport entirely is always drift;
// otherwise compare against the offset captured right after anchoring.
internal fun focusedAnchorTargetDrifted(anchoredOffset: Int?, currentOffset: Int?): Boolean =
    when {
        currentOffset == null -> true
        anchoredOffset == null -> false
        else -> {
            val delta = currentOffset - anchoredOffset
            delta > FocusedAnchorDriftTolerancePx || delta < -FocusedAnchorDriftTolerancePx
        }
    }

// Marker magnitude: -1 encodes "target left the viewport" so the log line
// stays greppable next to pixel deltas.
internal fun focusedAnchorDriftMagnitude(anchoredOffset: Int?, currentOffset: Int?): Int =
    if (anchoredOffset != null && currentOffset != null) {
        val delta = currentOffset - anchoredOffset
        if (delta >= 0) delta else -delta
    } else {
        -1
    }

// task #567: the initial bottom anchor yields the viewport to a pending
// focused anchor (bounded by FocusedAnchorBottomYieldCapMs).
internal fun shouldYieldBottomAnchorToFocusedAnchor(
    pendingIdentity: String?,
    consumedIdentity: String?,
    scrollInterruptedIdentity: String?,
    yieldElapsedMs: Long
): Boolean =
    pendingIdentity != null &&
        shouldRunFocusedMessageAnchor(
            targetIdentity = pendingIdentity,
            consumedIdentity = consumedIdentity,
            scrollInterruptedIdentity = scrollInterruptedIdentity
        ) &&
        yieldElapsedMs < FocusedAnchorBottomYieldCapMs

internal fun RaftThreadScreenPayload.latestMessageListItemIndex(): Int {
    if (messages.isEmpty()) return -1
    return firstMessageListItemIndex() + messages.lastIndex
}

internal fun RaftThreadScreenPayload.latestMessageLayoutKey(): String {
    val latest = messages.lastOrNull()
        ?: return "empty:$originStatus:$canLoadMoreBefore:$canLoadMoreAfter"
    return buildString {
        append(originStatus)
        append(':')
        append(hasThreadHistoryBoundaryItem())
        append(':')
        append(canLoadMoreAfter)
        append(':')
        append(messages.size)
        append(':')
        append(latest.id)
        append(':')
        append(latest.body.length)
        append(':')
        append(latest.attachments.size)
        append(':')
        append(latest.taskId.orEmpty())
        append(':')
        append(latest.threadReactionLayoutSignature())
    }
}

internal fun shouldAnchorLatestLocalSendToBottom(
    latestLocalSendBottomAnchorKey: String?,
    latestItemIndex: Int
): Boolean =
    latestLocalSendBottomAnchorKey != null && latestItemIndex >= 0

internal fun shouldRevealLatestLocalSendBeforeInitialBottomAnchor(
    initialBottomAnchorReady: Boolean,
    latestLocalSendBottomAnchorKey: String?,
    latestItemIndex: Int,
    messageCount: Int
): Boolean =
    !initialBottomAnchorReady &&
        latestLocalSendBottomAnchorKey != null &&
        latestItemIndex >= 0 &&
        messageCount == 1

internal fun shouldContinueLatestLocalSendBottomAnchor(
    activeAnchorKey: String?,
    targetAnchorKey: String?,
    startUserScrollGeneration: Int,
    currentUserScrollGeneration: Int,
    isAtRenderedBottom: Boolean,
    isSendingAnchorVisible: Boolean,
    attempt: Int
): Boolean {
    if (targetAnchorKey == null || activeAnchorKey != targetAnchorKey) return false
    if (currentUserScrollGeneration != startUserScrollGeneration) return false
    return isSendingAnchorVisible || !isAtRenderedBottom || attempt < ThreadBottomAnchorMinimumAttempts
}

internal fun focusedMessageAnchorOwnsViewport(
    pendingIdentity: String?,
    consumedIdentity: String?,
    scrollInterruptedIdentity: String?,
    yieldElapsedMs: Long
): Boolean =
    shouldYieldBottomAnchorToFocusedAnchor(
        pendingIdentity = pendingIdentity,
        consumedIdentity = consumedIdentity,
        scrollInterruptedIdentity = scrollInterruptedIdentity,
        yieldElapsedMs = yieldElapsedMs
    )

// task #1010: the focused-message request is route intent, while an open IME
// establishes a new explicit composer viewport. Once the focus request has
// actually anchored, hand ownership to the composer exactly once. This keeps
// the ordinary inset correction available but prevents the focus watcher and
// late origin correction from dispatching a second scroll into the shrinking
// Lazy viewport. An unanchored request remains pending; user takeover remains
// authoritative.
internal fun focusedAnchorImeHandoffIdentity(
    keyboardInsetDp: Float,
    targetIdentity: String?,
    anchoredIdentity: String?,
    consumedIdentity: String?,
    scrollInterruptedIdentity: String?,
    startUserScrollGeneration: Int?,
    currentUserScrollGeneration: Int
): String? =
    targetIdentity?.takeIf { identity ->
        keyboardInsetDp > 0f &&
            anchoredIdentity == identity &&
            shouldRunFocusedMessageAnchor(
                targetIdentity = identity,
                consumedIdentity = consumedIdentity,
                scrollInterruptedIdentity = scrollInterruptedIdentity
            ) &&
            startUserScrollGeneration != null &&
            startUserScrollGeneration == currentUserScrollGeneration
    }

internal fun focusedAnchorImeHandoffTransfersToBottom(
    handoffIdentity: String?,
    targetItemIndex: Int,
    latestMessageItemIndex: Int,
    canLoadMoreAfter: Boolean
): Boolean =
    handoffIdentity != null &&
        targetItemIndex >= 0 &&
        targetItemIndex == latestMessageItemIndex &&
        !canLoadMoreAfter

internal data class ThreadBottomAutoAnchorAvailabilityState(
    val currentAllow: Boolean,
    val isScrollInProgress: Boolean,
    val isAtRenderedBottom: Boolean,
    val focusedAnchorOwnsViewport: Boolean
)

internal fun resolveThreadBottomAutoAnchorAllowed(
    state: ThreadBottomAutoAnchorAvailabilityState
): Boolean =
    when {
        state.focusedAnchorOwnsViewport -> false
        state.isScrollInProgress && !state.isAtRenderedBottom -> false
        state.isAtRenderedBottom -> true
        else -> state.currentAllow
    }

internal data class ThreadBottomAnchorCorrectionState(
    val allowBottomAutoAnchor: Boolean,
    val isScrollInProgress: Boolean,
    val isAtRenderedBottom: Boolean,
    val focusedAnchorOwnsViewport: Boolean = false
)

internal fun shouldAutoCorrectThreadBottomAnchor(state: ThreadBottomAnchorCorrectionState): Boolean =
    state.allowBottomAutoAnchor &&
        !state.focusedAnchorOwnsViewport &&
        !state.isScrollInProgress &&
        !state.isAtRenderedBottom

internal data class ThreadFocusedTailFit(
    val targetItemIndex: Int,
    val latestMessageItemIndex: Int,
    val bottomPaddingItemIndex: Int,
    val fitHeightPx: Int,
    val viewportHeightPx: Int,
    val trailingSpanPx: Int,
    val fitsViewport: Boolean
)

internal fun threadViewportEpochCoherent(
    measuredViewportHeightPx: Int,
    layoutViewportStartPx: Int,
    layoutViewportEndPx: Int,
    tolerancePx: Int = 1
): Boolean {
    val layoutViewportHeightPx = layoutViewportEndPx - layoutViewportStartPx
    return measuredViewportHeightPx > 0 &&
        layoutViewportHeightPx > 0 &&
        kotlin.math.abs(measuredViewportHeightPx - layoutViewportHeightPx) <= tolerancePx.coerceAtLeast(0)
}

internal fun shouldAdvanceThreadComposerViewportRetry(
    pendingLayoutChange: Boolean,
    measuredViewportHeightPx: Int,
    layoutViewportStartPx: Int,
    layoutViewportEndPx: Int
): Boolean =
    pendingLayoutChange &&
        threadViewportEpochCoherent(
            measuredViewportHeightPx = measuredViewportHeightPx,
            layoutViewportStartPx = layoutViewportStartPx,
            layoutViewportEndPx = layoutViewportEndPx
        )

internal fun resolveThreadFocusedTailFit(
    targetItemIndex: Int,
    latestMessageItemIndex: Int,
    bottomPaddingItemIndex: Int,
    targetOffsetPx: Int?,
    targetSizePx: Int?,
    latestMessageOffsetPx: Int?,
    latestMessageSizePx: Int?,
    viewportHeightPx: Int,
    trailingPaddingPx: Int
): ThreadFocusedTailFit {
    val indicesValid =
        targetItemIndex >= 0 &&
            latestMessageItemIndex >= targetItemIndex &&
            bottomPaddingItemIndex > latestMessageItemIndex
    val geometryValid =
        targetOffsetPx != null &&
            targetSizePx != null &&
            targetSizePx > 0 &&
            latestMessageOffsetPx != null &&
            latestMessageSizePx != null &&
            latestMessageSizePx > 0 &&
            viewportHeightPx > 0
    if (!indicesValid || !geometryValid) {
        return ThreadFocusedTailFit(
            targetItemIndex = targetItemIndex,
            latestMessageItemIndex = latestMessageItemIndex,
            bottomPaddingItemIndex = bottomPaddingItemIndex,
            fitHeightPx = -1,
            viewportHeightPx = viewportHeightPx,
            trailingSpanPx = 0,
            fitsViewport = false
        )
    }
    val targetOffset = requireNotNull(targetOffsetPx)
    val targetSize = requireNotNull(targetSizePx)
    val latestMessageOffset = requireNotNull(latestMessageOffsetPx)
    val latestMessageSize = requireNotNull(latestMessageSizePx)
    val targetBottomPx = targetOffset + targetSize
    val latestMessageBottomPx = latestMessageOffset + latestMessageSize
    val cleanTrailingPaddingPx = trailingPaddingPx.coerceAtLeast(0)
    val fitHeightPx = latestMessageBottomPx - targetOffset + cleanTrailingPaddingPx
    val trailingSpanPx = latestMessageBottomPx - targetBottomPx + cleanTrailingPaddingPx
    val orderedGeometry = fitHeightPx > 0 && trailingSpanPx >= cleanTrailingPaddingPx
    return ThreadFocusedTailFit(
        targetItemIndex = targetItemIndex,
        latestMessageItemIndex = latestMessageItemIndex,
        bottomPaddingItemIndex = bottomPaddingItemIndex,
        fitHeightPx = fitHeightPx,
        viewportHeightPx = viewportHeightPx,
        trailingSpanPx = trailingSpanPx.coerceAtLeast(0),
        fitsViewport = orderedGeometry && fitHeightPx <= viewportHeightPx
    )
}

internal data class ThreadComposerLayoutBottomAnchorState(
    val previousKeyboardInsetDp: Float,
    val nextKeyboardInsetDp: Float,
    val imeProgrammaticViewportPolicy: ThreadImeProgrammaticViewportPolicy,
    val previousViewportHeightDp: Float,
    val nextViewportHeightDp: Float,
    val pendingLayoutChange: Boolean,
    val initialBottomAnchorReady: Boolean,
    val latestItemIndex: Int,
    val allowBottomAutoAnchor: Boolean,
    val viewportEpochCoherent: Boolean,
    val focusedAnchorOwnsViewport: Boolean,
    val focusedActiveTailAnchorCanCorrectLayout: Boolean,
    val focusedTailAnchorCanCorrectLayout: Boolean
)

internal fun threadComposerLayoutChanged(state: ThreadComposerLayoutBottomAnchorState): Boolean =
    state.previousKeyboardInsetDp != state.nextKeyboardInsetDp ||
        (
            threadViewportMeasurementReady(state.previousViewportHeightDp) &&
                threadViewportMeasurementReady(state.nextViewportHeightDp) &&
                state.previousViewportHeightDp != state.nextViewportHeightDp
        )

internal fun shouldAnchorThreadComposerLayoutChange(
    state: ThreadComposerLayoutBottomAnchorState
): Boolean =
    // task #990 kill-switch: while the keyboard is open, stop-loss platforms
    // prevent composer-side programmatic correction from moving the viewport.
    // Platforms with ordinary correction enabled keep the existing path.
    !threadImeOpenSuppressesProgrammaticViewport(
        keyboardInsetDp = state.nextKeyboardInsetDp,
        policy = state.imeProgrammaticViewportPolicy,
    ) &&
        state.viewportEpochCoherent &&
        (threadComposerLayoutChanged(state) || state.pendingLayoutChange) &&
        state.initialBottomAnchorReady &&
        state.latestItemIndex >= 0 &&
        (
            (state.allowBottomAutoAnchor && !state.focusedAnchorOwnsViewport) ||
                state.focusedActiveTailAnchorCanCorrectLayout ||
                state.focusedTailAnchorCanCorrectLayout
        )

internal fun focusedActiveTailMessageComposerLayoutOwnsViewport(
    targetIdentity: String?,
    targetItemIndex: Int,
    latestMessageItemIndex: Int,
    targetToTailFitsViewport: Boolean,
    canLoadMoreAfter: Boolean,
    anchoredIdentity: String?,
    consumedIdentity: String?,
    scrollInterruptedIdentity: String?,
    startUserScrollGeneration: Int?,
    currentUserScrollGeneration: Int
): Boolean =
    targetIdentity != null &&
        targetItemIndex >= 0 &&
        latestMessageItemIndex >= targetItemIndex &&
        targetToTailFitsViewport &&
        !canLoadMoreAfter &&
        anchoredIdentity == targetIdentity &&
        consumedIdentity != targetIdentity &&
        scrollInterruptedIdentity != targetIdentity &&
        startUserScrollGeneration != null &&
        startUserScrollGeneration == currentUserScrollGeneration

internal fun focusedTailMessageComposerLayoutOwnsViewport(
    targetIdentity: String?,
    targetItemIndex: Int,
    latestMessageItemIndex: Int,
    targetToTailFitsViewport: Boolean,
    canLoadMoreAfter: Boolean,
    anchoredIdentity: String?,
    consumedIdentity: String?,
    scrollInterruptedIdentity: String?,
    startUserScrollGeneration: Int?,
    currentUserScrollGeneration: Int
): Boolean =
    targetIdentity != null &&
        targetItemIndex >= 0 &&
        latestMessageItemIndex >= targetItemIndex &&
        targetToTailFitsViewport &&
        !canLoadMoreAfter &&
        anchoredIdentity == targetIdentity &&
        consumedIdentity == targetIdentity &&
        scrollInterruptedIdentity != targetIdentity &&
        startUserScrollGeneration != null &&
        startUserScrollGeneration == currentUserScrollGeneration

internal data class ThreadComposerLayoutBottomAnchorDecision(
    val shouldAnchor: Boolean,
    val pendingLayoutChange: Boolean,
    val anchorFocusedTailAtRenderedBottom: Boolean = false
)

internal fun resolveThreadComposerLayoutBottomAnchor(
    state: ThreadComposerLayoutBottomAnchorState
): ThreadComposerLayoutBottomAnchorDecision {
    val layoutChanged = threadComposerLayoutChanged(state)
    if (!state.viewportEpochCoherent && (layoutChanged || state.pendingLayoutChange)) {
        return ThreadComposerLayoutBottomAnchorDecision(
            shouldAnchor = false,
            pendingLayoutChange = true,
            anchorFocusedTailAtRenderedBottom = false
        )
    }
    val shouldAnchor = shouldAnchorThreadComposerLayoutChange(state)
    val pendingLayoutChange =
        when {
            shouldAnchor -> false
            layoutChanged && !state.initialBottomAnchorReady -> true
            layoutChanged && state.focusedAnchorOwnsViewport -> true
            state.focusedAnchorOwnsViewport -> state.pendingLayoutChange
            state.initialBottomAnchorReady ||
                state.latestItemIndex < 0 ||
                (
                    !state.allowBottomAutoAnchor &&
                        !state.focusedActiveTailAnchorCanCorrectLayout &&
                        !state.focusedTailAnchorCanCorrectLayout
                ) -> false
            else -> state.pendingLayoutChange
        }
    return ThreadComposerLayoutBottomAnchorDecision(
        shouldAnchor = shouldAnchor,
        pendingLayoutChange = pendingLayoutChange,
        anchorFocusedTailAtRenderedBottom =
            shouldAnchor &&
                (state.focusedActiveTailAnchorCanCorrectLayout || state.focusedTailAnchorCanCorrectLayout)
    )
}

/**
 * What the parked-bottom composer correction (keyboard / composer resize, intent owner: the
 * parked tail, fenced by `allowBottomAutoAnchor`) does with the viewport controller.
 *
 * A reversed list keeps its bottom edge fixed while the viewport shrinks, so the rows already
 * ride up with the keyboard. Replaying an `Anchored` intent there re-applies a top-relative
 * placement (`Focused` top-aligns or centres the row, `ExactOffset` restores the old
 * top offset) once the inset settles: the visible "moves up, then snaps to top-aligned" jump.
 */
internal enum class ThreadComposerInsetViewportAction(val diagnosticName: String) {
    /** `ContentMutated(InsetChange)`: replay the current intent (forward list, FollowingLatest, ...). */
    ReplayIntent("replay_intent"),

    /** The tail is parked at the rendered bottom: retire the stale anchor to `FollowingLatest`. */
    FollowLatest("follow_latest"),

    /** Mid-history reversed list: the rendered position already kept the anchor; do nothing. */
    KeepRenderedPosition("keep_rendered_position")
}

/** The controller event for [this] action; `null` leaves the viewport untouched. */
internal fun ThreadComposerInsetViewportAction.viewportEvent(): LazyViewportEvent? =
    when (this) {
        ThreadComposerInsetViewportAction.ReplayIntent ->
            LazyViewportEvent.ContentMutated(LazyViewportMutation.InsetChange)
        ThreadComposerInsetViewportAction.FollowLatest ->
            LazyViewportEvent.FollowLatest(canOverrideUserDragging = false)
        ThreadComposerInsetViewportAction.KeepRenderedPosition -> null
    }

internal fun resolveThreadComposerInsetViewportAction(
    reverseLayout: Boolean,
    intent: LazyViewportIntent,
    tailAtRenderedBottom: Boolean
): ThreadComposerInsetViewportAction =
    when {
        !reverseLayout || intent !is LazyViewportIntent.Anchored -> ThreadComposerInsetViewportAction.ReplayIntent
        tailAtRenderedBottom -> ThreadComposerInsetViewportAction.FollowLatest
        else -> ThreadComposerInsetViewportAction.KeepRenderedPosition
    }

internal data class ThreadFocusedAnchorPlacementState(
    val identity: String?,
    val placement: LazyViewportAnchorPlacement,
    val offsetPx: Int,
    val renderedTailMessageId: String?
)

internal fun resetThreadFocusedAnchorPlacement(
    identity: String?,
    placement: LazyViewportAnchorPlacement = LazyViewportAnchorPlacement.Focused
): ThreadFocusedAnchorPlacementState =
    ThreadFocusedAnchorPlacementState(
        identity = identity,
        placement = placement,
        offsetPx = 0,
        renderedTailMessageId = null
    )

internal fun renderedThreadFocusedAnchorPlacement(
    identity: String,
    offsetPx: Int,
    renderedTailMessageId: String
): ThreadFocusedAnchorPlacementState =
    ThreadFocusedAnchorPlacementState(
        identity = identity,
        placement = LazyViewportAnchorPlacement.RenderedBottom,
        offsetPx = offsetPx.coerceAtLeast(0),
        renderedTailMessageId = renderedTailMessageId
    )

internal fun focusedAnchorPlacementForIdentity(
    targetIdentity: String,
    placementIdentity: String?,
    placement: LazyViewportAnchorPlacement
): LazyViewportAnchorPlacement =
    if (targetIdentity == placementIdentity) placement else LazyViewportAnchorPlacement.Focused

internal fun focusedAnchorPlacementOffsetForIdentity(
    targetIdentity: String,
    placementIdentity: String?,
    placement: LazyViewportAnchorPlacement,
    placementOffsetPx: Int
): Int =
    if (targetIdentity == placementIdentity && placement == LazyViewportAnchorPlacement.RenderedBottom) {
        placementOffsetPx.coerceAtLeast(0)
    } else {
        0
    }

internal fun focusedAnchorRenderedTailMessageIdForIdentity(
    targetIdentity: String,
    placementIdentity: String?,
    placement: LazyViewportAnchorPlacement,
    renderedTailMessageId: String?
): String? =
    renderedTailMessageId?.takeIf {
        targetIdentity == placementIdentity && placement == LazyViewportAnchorPlacement.RenderedBottom
    }

/**
 * 1.30.0 P0-1: whether an already-visible `Focused` target row should keep its current visual
 * offset under [LazyViewportAnchorPlacement.ExactOffset] instead of re-dispatching `Focused`
 * (which would yank an already-visible or bottom-clamped row back to `y = 0`).
 *
 * True when either this `(messageId, requestNonce)` session already anchored the target (e.g. the
 * focused-anchor effect restarted on `network_applied` changing item count/signature) or the
 * pre-first-draw placement owner already aligned this initial focus target. Non-`Focused`
 * placements (`FocusedThreadReplies`, `RenderedBottom`) stay on their existing path.
 */
internal fun shouldKeepVisibleFocusedAnchorOffset(
    placement: LazyViewportAnchorPlacement,
    currentVisibleOffset: Int?,
    targetIdentity: String,
    sessionIdentity: String?,
    initialPlacementApplied: Boolean,
    initialFocusedIdentity: String?,
): Boolean =
    placement == LazyViewportAnchorPlacement.Focused &&
        currentVisibleOffset != null &&
        (
            sessionIdentity == targetIdentity ||
                (initialPlacementApplied && initialFocusedIdentity == targetIdentity)
        )

internal data class ThreadFocusedReanchorTarget(
    val placement: LazyViewportAnchorPlacement,
    val offsetPx: Int,
)

/**
 * 1.30.0 P0-1: drift reanchor target during a focused-anchor watch session. When the target row
 * already settled at a visible [anchoredOffset] under [LazyViewportAnchorPlacement.Focused],
 * restore that exact offset instead of yanking a bottom-clamped row to `y = 0`.
 */
internal fun resolveThreadFocusedReanchorTarget(
    anchoredOffset: Int?,
    resolvedPlacement: LazyViewportAnchorPlacement,
    fallbackOffsetPx: Int,
): ThreadFocusedReanchorTarget =
    if (anchoredOffset != null && resolvedPlacement == LazyViewportAnchorPlacement.Focused) {
        ThreadFocusedReanchorTarget(
            placement = LazyViewportAnchorPlacement.ExactOffset,
            offsetPx = anchoredOffset,
        )
    } else {
        ThreadFocusedReanchorTarget(
            placement = resolvedPlacement,
            offsetPx = fallbackOffsetPx,
        )
    }

internal fun threadFocusedDispatchOwnerTag(placement: LazyViewportAnchorPlacement): String =
    when (placement) {
        LazyViewportAnchorPlacement.FocusedThreadReplies -> ThreadViewportOwnerTag.JumpPill
        LazyViewportAnchorPlacement.RenderedBottom -> ThreadViewportOwnerTag.RenderedTail
        else -> ThreadViewportOwnerTag.FocusedReanchor
    }

internal data class ThreadBackToBottomButtonState(
    val initialBottomAnchorReady: Boolean,
    val pendingKeyboardBottomAnchor: Boolean,
    val bottomAutoAnchorOwnsViewport: Boolean = false,
    val canScrollTowardBottom: Boolean = true,
    val canonicalTailMissing: Boolean = false,
    val latestItemOffBottom: Boolean
)

internal fun shouldShowThreadBackToBottomButton(state: ThreadBackToBottomButtonState): Boolean =
    state.initialBottomAnchorReady &&
        !state.pendingKeyboardBottomAnchor &&
        !state.bottomAutoAnchorOwnsViewport &&
        (
            state.canonicalTailMissing ||
                (state.canScrollTowardBottom && state.latestItemOffBottom)
        )

internal fun shouldCompleteThreadBackToBottom(
    canonicalTailLoaded: Boolean,
    clickUserScrollGeneration: Int,
    currentUserScrollGeneration: Int
): Boolean =
    canonicalTailLoaded && clickUserScrollGeneration == currentUserScrollGeneration

internal fun shouldKeepThreadBackToBottomOverlayMounted(
    shouldShowButton: Boolean,
    interactionGuardActive: Boolean
): Boolean = shouldShowButton || interactionGuardActive

internal data class ThreadInitialBottomAnchorState(
    val canScrollToLatest: Boolean,
    val isAtRenderedBottom: Boolean,
    val attempt: Int
)

internal fun shouldRevealInitialThreadBottomAnchor(state: ThreadInitialBottomAnchorState): Boolean =
    state.canScrollToLatest &&
        state.isAtRenderedBottom &&
        state.attempt >= ThreadBottomAnchorMinimumAttempts

internal fun threadMessagesListAlpha(
    initialBottomAnchorReady: Boolean,
    latestItemIndex: Int,
    revealLatestLocalSendBeforeInitialAnchor: Boolean
): Float = 1f

internal fun RaftThreadScreenPayload.hasSendingLocalSendAnchor(anchorKey: String?): Boolean {
    val messageId = anchorKey?.substringBefore(':')?.takeIf { it.isNotBlank() } ?: return false
    return messages.any { message ->
        message.id == messageId &&
            message.deliveryState.equals(ThreadMessageDeliverySending, ignoreCase = true) &&
            message.pendingSendContext != null
    }
}

internal fun RaftThreadScreenPayload.latestLocalSendBottomAnchorKey(): String? {
    val latest = messages.lastOrNull() ?: return null
    if (!latest.deliveryState.equals(ThreadMessageDeliverySending, ignoreCase = true) ||
        latest.pendingSendContext == null
    ) {
        return null
    }
    return buildString {
        append(latest.id)
        append(':')
        append(latest.body.length)
        append(':')
        append(latest.attachments.size)
        append(':')
        append(latest.pendingSendContext.attachmentIds.size)
    }
}

internal fun RaftThreadMessageItem.threadReactionLayoutSignature(): String =
    reactions
        .filter { reaction -> reaction.count > 0 }
        .joinToString(separator = "|") { reaction ->
            "${reaction.emoji}:${reaction.count}:${reaction.selected}"
        }

internal fun RaftThreadScreenPayload.visibleReadMarkerKey(): String? {
    return toThreadTimelineSlice().visibleReadMarkerKey(sourceThreadId)
}

internal fun shouldMarkThreadVisibleRead(
    routeActive: Boolean,
    observedHostForeground: Boolean,
    currentHostVisibility: Boolean,
    markerKey: String?,
    hostInUse: Boolean = true
): Boolean =
    routeActive && observedHostForeground && currentHostVisibility && hostInUse && markerKey != null

internal fun threadLifecycleEventTargetsThread(pageName: String): Boolean =
    pageName == THREAD_PAGE || pageName == RAFT_APP_SHELL_PAGE

internal data class ThreadVisibleResumePlan(
    val refreshVisibleConversation: Boolean,
    val rejoinVisibleRooms: Boolean
)

internal fun threadVisibleResumePlan(routeActive: Boolean): ThreadVisibleResumePlan? =
    if (routeActive) {
        ThreadVisibleResumePlan(
            refreshVisibleConversation = true,
            rejoinVisibleRooms = true
        )
    } else {
        null
    }

internal class ThreadVisibleResumeGate(
    private val nowMillis: () -> Long = ::raftForegroundGateNowMillis,
    private val connectedForegroundSkipMillis: Long = RAFT_FRESHNESS_CONNECTED_FOREGROUND_SKIP_MILLIS,
    private val liveConnectionToken: (serverId: String) -> Long? = RaftRealtimeConnectionCauses::liveConnectionToken,
) {
    // Snapshot state: a pending resume must itself re-trigger the drain effect,
    // because the lifecycle listener can latch it after `active` already
    // became true (the listener reads a stale rememberUpdatedState snapshot).
    var pendingVisibleResume by mutableStateOf(false)
        private set

    private var lastCheckAtMillis: Long? = null
    private var lastCheckServerId: String? = null
    private var lastCheckConnectionToken: Long? = null

    /** Records a completed fresh load or silent catch-up (initial content, reconnect, network restore). */
    fun recordFreshCheck(serverId: String?) {
        val normalizedServerId = serverId?.trim()?.takeIf { it.isNotEmpty() }
        lastCheckAtMillis = nowMillis()
        lastCheckServerId = normalizedServerId
        lastCheckConnectionToken = normalizedServerId?.let(liveConnectionToken)
    }

    fun onLifecycleVisibleResume(routeActive: Boolean, serverId: String? = null): ThreadVisibleResumePlan? {
        if (!routeActive) {
            pendingVisibleResume = true
            return null
        }
        pendingVisibleResume = false
        if (shouldSkipConnectedForeground(serverId)) return null
        recordFreshCheck(serverId)
        return threadVisibleResumePlan(routeActive = true)
    }

    fun onRouteBecameVisible(serverId: String? = null): ThreadVisibleResumePlan? {
        if (!pendingVisibleResume) return null
        pendingVisibleResume = false
        if (shouldSkipConnectedForeground(serverId)) return null
        recordFreshCheck(serverId)
        return ThreadVisibleResumePlan(
            refreshVisibleConversation = true,
            rejoinVisibleRooms = true
        )
    }

    private fun shouldSkipConnectedForeground(serverId: String?): Boolean {
        if (connectedForegroundSkipMillis <= 0L) return false
        val normalizedServerId = serverId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val lastAt = lastCheckAtMillis ?: return false
        val now = nowMillis()
        if (now < lastAt || now - lastAt >= connectedForegroundSkipMillis) return false
        if (normalizedServerId != lastCheckServerId) return false
        val currentToken = liveConnectionToken(normalizedServerId) ?: return false
        return currentToken == lastCheckConnectionToken
    }
}

/**
 * Binds an open conversation page to [SharedThreadViewModel]'s store-owned outcome-tracked
 * [build.raft.app.data.RaftFreshnessGate] (#wg-mobile-feedback:#274 Phase 2) for socket reconnect
 * and network recovery, while stamping [resumeGate] when initial content settles or a catch-up
 * fires so an immediate unbroken-socket foreground return within 60 s skips a redundant refetch.
 */
@Composable
internal fun ThreadConversationFreshnessCatchUpEffect(
    threadViewModel: SharedThreadViewModel,
    serverId: String?,
    active: Boolean,
    contentReady: Boolean,
    resumeGate: ThreadVisibleResumeGate,
    onCatchUp: (RaftFreshnessTrigger) -> Unit,
) {
    val latestOnCatchUp by rememberUpdatedState(onCatchUp)
    val latestResumeGate by rememberUpdatedState(resumeGate)
    LaunchedEffect(resumeGate, serverId, contentReady) {
        if (contentReady) {
            resumeGate.recordFreshCheck(serverId)
        }
    }
    LaunchedEffect(threadViewModel, serverId, active) {
        threadViewModel.setFreshnessObserver(
            serverId = serverId,
            active = active,
            onTriggered = { trigger, triggeredServerId ->
                latestResumeGate.recordFreshCheck(triggeredServerId)
                latestOnCatchUp(trigger)
            },
        )
    }
    DisposableEffect(threadViewModel) {
        onDispose {
            threadViewModel.clearFreshnessObserver()
        }
    }
}

internal fun RaftThreadScreenPayload.toRaftTimeContext(): RaftTimeContext =
    RaftTimeContext(
        localOffsetMinutes = localOffsetMinutes,
        todayEpochDay = todayEpochDay,
        nowEpochMillis = nowEpochMillis,
        timeFormat = timeFormat
    )

internal fun RaftThreadScreenPayload.toRaftTimeCapabilities(): RaftTimeCapabilities =
    RaftTimeCapabilities(
        localOffsetMinutes = localOffsetMinutes,
        timeFormat = timeFormat
    )

internal fun RaftThreadScreenPayload.formatTimestampLabel(value: String): String =
    formatRaftTimestampLabel(value, toRaftTimeContext())
