package build.raft.app.thread

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import build.raft.app.RaftColor
import build.raft.app.RaftLiveRootAnchorRef
import build.raft.app.RaftPopoverAnchor
import build.raft.app.RaftPopoverLocalPoint
import build.raft.app.RaftThreadSurfaceKind
import build.raft.app.settings.observe
import build.raft.app.origin
import build.raft.app.raftCaptureLiveRootAnchor
import build.raft.app.resolve
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

// Mobile press feedback (task #555): the finger-down point washes gray and a
// ripple spreads across the row — replacing the web-style press border. The
// indication is delayed past touch-slop so a scroll that starts on a message
// never flashes it: a drag steals the pointer and cancels the press scope
// before the delay elapses.
private const val ThreadMessagePressIndicationDelayMs = 120L
private const val ThreadMessagePressFadeInMs = 120
internal const val ThreadMessageRippleFadeMs = 160
internal fun threadMessageUsesFullRowLongPressActions(shareSelectionActive: Boolean): Boolean =
    !shareSelectionActive

internal data class ThreadMessageLongPressDispatchDecision(
    val allowed: Boolean,
    val reason: String,
    val scrollInProgress: Boolean
)

internal fun decideThreadMessageLongPressDispatch(
    detectorAccepted: Boolean,
    directPointerMovementDetected: Boolean,
    isScrollInProgress: Boolean
): ThreadMessageLongPressDispatchDecision =
    // LazyListState reports both direct drags and programmatic anchor/focus movement as scrolling.
    // The gesture detector owns direct-drag cancellation; list motion is diagnostic context only.
    ThreadMessageLongPressDispatchDecision(
        allowed = detectorAccepted && !directPointerMovementDetected,
        reason =
            when {
                !detectorAccepted -> "detector_not_admitted"
                directPointerMovementDetected -> "direct_pointer_movement"
                else -> "admitted"
            },
        scrollInProgress = isScrollInProgress
    )

/**
 * A single tap on a message row opens that message's thread, whatever its reply count: a message
 * without replies opens its empty thread to start one, like the mobile web's `handleMobileTap`
 * (stdrc, 2026-10-29, #proj-raft-ui:911ccc08; replaces the opt-in "Single tap opens Thread"
 * setting). Not inside a reply thread (no reply action there), not while sharing, not on a local
 * send that is still sending or failed.
 */
/**
 * artin 11-06: with the keyboard up, a tap on a message row is the reader closing the keyboard, not
 * asking for the thread: since a single tap opens the thread (stdrc 10-29) it opened one by accident.
 * Only the row's own whole-row actions (open thread, the double tap's reaction bar) yield; a link,
 * mention, avatar, reaction or replies chip is something the reader aimed at and still acts.
 */
internal class ThreadKeyboardTapDismissal(
    private val keyboardInsetDp: () -> Float,
    private val hideKeyboard: () -> Unit,
) {
    /** True when the keyboard was up: it is closed and this tap does nothing else. */
    fun dismissIfUp(): Boolean {
        if (keyboardInsetDp() <= 0f) return false
        hideKeyboard()
        return true
    }
}

internal val LocalThreadKeyboardTapDismissal = staticCompositionLocalOf<ThreadKeyboardTapDismissal?> { null }

internal fun threadMessageTapOpensThread(
    showReplyAction: Boolean,
    shareSelectionActive: Boolean,
    hasLocalSendState: Boolean
): Boolean =
    threadMessageBodyTapEligible(showReplyAction, shareSelectionActive, hasLocalSendState)

internal fun threadMessageBodyTapEligible(
    showReplyAction: Boolean,
    shareSelectionActive: Boolean,
    hasLocalSendState: Boolean
): Boolean =
    showReplyAction && !shareSelectionActive && !hasLocalSendState

internal fun threadMessageOpenIgnoredForSurface(surfaceKind: String, channelKind: String? = null): Boolean =
    !RaftThreadSurfaceKind.allowsReplyInThread(surfaceKind, channelKind)

/**
 * A double tap on a message row shows its quick reaction bar (stdrc, 2026-10-29,
 * #proj-raft-ui:911ccc08): only where the long-press menu would show its quick reaction row
 * ([ThreadMessageMenuCapabilities.quickReactions]), inside a reply thread too, never while sharing
 * or on a local send. Where this is false the row has no double tap and a single tap runs at once.
 */
internal fun threadMessageDoubleTapShowsReactions(
    quickReactions: Boolean,
    shareSelectionActive: Boolean,
    hasLocalSendState: Boolean
): Boolean =
    quickReactions && !shareSelectionActive && !hasLocalSendState

/** Whether a message may be reacted to: the long-press menu's quick reaction row gate. */
internal fun threadMessageCanQuickReact(
    message: RaftThreadMessageItem,
    slice: ThreadTimelineSlice,
    writeActionsEnabled: Boolean
): Boolean =
    writeActionsEnabled && slice.showReactionActions && !message.hasLocalSendState() && !message.isSystemMessage()

/**
 * Whether a down of [type] gets the message row's tap gestures (tap opens the thread, double tap
 * shows the quick reaction bar). Only a finger (or a tool-less tap, [raftIsTouchLike]): with a mouse, a trackpad or a
 * stylus (desktop, an iPad with a pointer) a click on a message does nothing at row level and a
 * double click selects a word, like any text (stdrc + raft-design #878, "desktop unchanged").
 * Links, buttons, reactions and images inside the row keep their own clicks; the press-and-hold
 * menu off the body and the pressed wash (which also reveals a compact row's time) are not gated.
 */
internal fun threadMessageRowTapGesturesFor(type: PointerType): Boolean = type.raftIsTouchLike()

/**
 * A finger, or a down without a tool type ([PointerType.Unknown]: a tap injected without one, as
 * instrumentation's `MotionEvent.obtain` and some automation tools send). Those are taps, not a
 * mouse: the nightly visual capture's message-body tap stopped opening the thread when only
 * [PointerType.Touch] counted.
 */
internal fun PointerType.raftIsTouchLike(): Boolean = this == PointerType.Touch || this == PointerType.Unknown

/**
 * Whether a pointer event on a message row is a right-click that opens the message menu at the
 * pointer (web: `contextmenu` on a message): the secondary button just went down, from anything
 * but a finger (a mouse, a trackpad's two-finger click, a stylus button; desktop or an iPad with a
 * pointer). A finger has no secondary button; its long press keeps opening the menu. Pure, so it
 * is tested apart from pointer input.
 */
internal fun threadMessageIsContextClick(
    pointerType: PointerType,
    secondaryButtonPressed: Boolean,
    pressStarted: Boolean,
): Boolean = pointerType != PointerType.Touch && secondaryButtonPressed && pressStarted

/** What one accepted tap on a message row does ([decideThreadMessageRowTap]). */
internal enum class ThreadMessageRowTapOutcome {
    /** Nothing: the row has no tap action, or the tap may still become a double tap. */
    None,

    /** The tap only dismissed an in-place text selection that was showing at its down. */
    DismissSelectionOnly,

    /** Run the single-tap action now: the row has no double tap. */
    RunSingleTap,

    /** Hold the single-tap action until the double-tap timeout passes without a second tap. */
    DeferSingleTap,

    /** The tap completed a double tap: show the quick reaction bar. */
    ShowReactionBar
}

/**
 * The row tap state machine (stdrc, 2026-10-29, #proj-raft-ui:911ccc08), kept pure so it is unit
 * tested apart from pointer input:
 *  - a tap the movement gate refused (a scroll, a child's own tap, a stale gesture) does nothing;
 *  - a tap whose gesture began while an in-place selection showed only dismisses it;
 *  - a click (mouse, trackpad, stylus) does nothing more at row level ([threadMessageRowTapGesturesFor]);
 *  - the second of two taps within [ThreadMessageDoubleTapTimeoutMillis] and
 *    [ThreadMessageDoubleTapSlop] shows the reaction bar;
 *  - otherwise, with a double tap available, the single-tap action waits out the timeout
 *    (the pressed feedback stays immediate); without one it runs at once, as before.
 */
internal fun decideThreadMessageRowTap(
    tapAccepted: Boolean,
    fromTouch: Boolean,
    selectionShowingAtDown: Boolean,
    hasSingleTapAction: Boolean,
    doubleTapAvailable: Boolean,
    completesDoubleTap: Boolean
): ThreadMessageRowTapOutcome =
    when {
        !tapAccepted -> ThreadMessageRowTapOutcome.None
        selectionShowingAtDown -> ThreadMessageRowTapOutcome.DismissSelectionOnly
        !fromTouch -> ThreadMessageRowTapOutcome.None
        doubleTapAvailable && completesDoubleTap -> ThreadMessageRowTapOutcome.ShowReactionBar
        doubleTapAvailable && hasSingleTapAction -> ThreadMessageRowTapOutcome.DeferSingleTap
        hasSingleTapAction -> ThreadMessageRowTapOutcome.RunSingleTap
        else -> ThreadMessageRowTapOutcome.None
    }

/**
 * The message row's pressed state (raft-ui States > Pressed on touch): the `fill-pressed` wash
 * over the row's own bounds, fading in while the finger is down and out on release or once the
 * action menu opens. It is drawn inside the row and clipped to it, so it never spills behind
 * the menu or onto neighbouring rows (task #51: the old circular ripple grew to the row's
 * diagonal from the touch point and was not clipped).
 */
@Composable
internal fun BoxScope.ThreadMessagePressRipple(
    pressed: Boolean,
    origin: Offset?
) {
    val washFraction by animateFloatAsState(
        targetValue = if (pressed && origin != null) 1f else 0f,
        animationSpec =
            tween(durationMillis = if (pressed) ThreadMessagePressFadeInMs else ThreadMessageRippleFadeMs),
        label = "thread-message-press-wash"
    )
    if (!shouldDrawThreadMessagePressWash(origin != null, washFraction)) return
    val wash = RaftColor.FillPressed
    Box(
        modifier =
            Modifier
                .matchParentSize()
                .clipToBounds()
                .background(wash.copy(alpha = wash.alpha * washFraction))
    )
}

internal fun shouldDrawThreadMessagePressWash(
    hasOrigin: Boolean,
    washFraction: Float
): Boolean = hasOrigin && washFraction > 0f

internal fun Modifier.threadMessagePointerInput(
    role: String,
    messageId: String,
    pointerSessionKey: Int,
    gestureOwner: ThreadMessageLongPressGestureOwner,
    block: suspend PointerInputScope.() -> Unit
): Modifier = pointerInput(role, messageId, pointerSessionKey, gestureOwner, block = block)

@Composable
internal fun rememberThreadMessageLongPressGestureOwner(
    instanceKey: Any,
    messageId: String
): ThreadMessageLongPressGestureOwner {
    val ownerStore = LocalThreadMessageLongPressGestureOwnerStore.current
    val fallbackTapDispatchScope = rememberCoroutineScope()
    val fallbackOwnerStore = remember(fallbackTapDispatchScope) {
        ThreadMessageLongPressGestureOwnerStore(tapDispatchScope = fallbackTapDispatchScope)
    }
    val effectiveOwnerStore = ownerStore ?: fallbackOwnerStore
    val ownerKey =
        remember(instanceKey, messageId) {
            ThreadMessageLongPressGestureOwnerKey(instanceKey, messageId)
        }
    val gestureOwner =
        remember(effectiveOwnerStore, ownerKey) {
            effectiveOwnerStore.ownerFor(ownerKey)
        }
    DisposableEffect(effectiveOwnerStore, ownerKey, gestureOwner) {
        effectiveOwnerStore.retain(ownerKey, gestureOwner)
        onDispose {
            effectiveOwnerStore.release(ownerKey, gestureOwner)
        }
    }
    return gestureOwner
}

@Composable
internal fun Modifier.threadMessageLongPressActions(
    messageId: String,
    pointerSessionKey: Int,
    gestureOwner: ThreadMessageLongPressGestureOwner,
    isScrollInProgress: () -> Boolean = { false },
    diagnosticsLogger: (String) -> Unit = {},
    onTap: (() -> Unit)? = null,
    onTapRejected: (ThreadMessageTapMovementDecision) -> Unit = {},
    onShowActions: (RaftPopoverAnchor) -> Unit,
    onInputReady: ((String) -> Unit)? = null,
    deferLongPressAt: ((Offset) -> Boolean)? = null,
    isInPlaceSelectionTaken: (() -> Boolean)? = null,
    onPointerDown: ((Offset, PointerType) -> Unit)? = null,
    onDoubleTap: ((RaftPopoverAnchor) -> Unit)? = null,
    consumeSelectionDismissalTap: (() -> Boolean)? = null,
    onContextClick: ((RaftPopoverAnchor) -> Unit)? = null,
    deferContextClickAt: ((Offset) -> Boolean)? = null
): Modifier {
    SideEffect {
        gestureOwner.updateCallbacks(
            ThreadMessageLongPressCallbacks(
                isScrollInProgress = isScrollInProgress,
                diagnosticsLogger = diagnosticsLogger,
                onTap = onTap,
                onTapRejected = onTapRejected,
                onShowActions = onShowActions,
                onInputReady = onInputReady,
                deferLongPressAt = deferLongPressAt,
                isInPlaceSelectionTaken = isInPlaceSelectionTaken,
                onPointerDown = onPointerDown,
                onDoubleTap = onDoubleTap,
                consumeSelectionDismissalTap = consumeSelectionDismissalTap,
                onContextClick = onContextClick,
                deferContextClickAt = deferContextClickAt
            )
        )
    }
    gestureOwner.tapMovementGate.activatePointerSession(pointerSessionKey)
    return this
        .raftCaptureLiveRootAnchor(gestureOwner.rootAnchor)
        .threadMessagePointerInput(
            role = "context_click",
            messageId = messageId,
            pointerSessionKey = pointerSessionKey,
            gestureOwner = gestureOwner
        ) {
            // A right-click (secondary button) opens the message menu at the pointer, like web's
            // `contextmenu` on a message (artin, 2026-10-29). Initial pass: ahead of the body's
            // SelectionContainer, whose own right-click selects a word and shows a text menu with
            // only Copy; the press is consumed there and for the rest of the gesture, so that menu,
            // the row's tap and press wash, and the selection never see it. The selection the
            // message already has is left as is (the menu offers Copy for it).
            awaitEachGesture {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val press = event.changes.firstOrNull() ?: return@awaitEachGesture
                if (
                    !threadMessageIsContextClick(
                        pointerType = press.type,
                        secondaryButtonPressed = event.buttons.isSecondaryPressed,
                        pressStarted = event.changes.all { it.changedToDownIgnoreConsumed() }
                    )
                ) {
                    return@awaitEachGesture
                }
                val compositionLease =
                    gestureOwner.captureActiveLease() ?: return@awaitEachGesture
                val callbacks =
                    gestureOwner.callbacksSnapshot(compositionLease) ?: return@awaitEachGesture
                val onContextClickCallback = callbacks.onContextClick ?: return@awaitEachGesture
                if (callbacks.deferContextClickAt?.invoke(press.position) == true) return@awaitEachGesture
                event.changes.forEach { it.consume() }
                val anchor = gestureOwner.rootAnchor.resolve(RaftPopoverLocalPoint.Exact(press.position))
                if (anchor == null) {
                    callbacks.diagnosticsLogger("thread_message_context_click_anchor_unavailable messageId=$messageId")
                } else {
                    callbacks.diagnosticsLogger("thread_message_context_click_menu messageId=$messageId")
                    onContextClickCallback(anchor)
                }
                // The rest of this press (its moves and release). If the opened menu took the
                // release, stop at the next press instead of swallowing it.
                while (true) {
                    val next = awaitPointerEvent(PointerEventPass.Initial)
                    if (next.changes.any { it.changedToDownIgnoreConsumed() }) break
                    next.changes.forEach { it.consume() }
                    if (next.changes.none { it.pressed }) break
                }
            }
        }
        .threadMessagePointerInput(
            role = "tap_movement",
            messageId = messageId,
            pointerSessionKey = pointerSessionKey,
            gestureOwner = gestureOwner
        ) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                // A right-click is the "context_click" detector's: not a tap, and it leaves every
                // selection (here or on another row) alone.
                if (
                    threadMessageIsContextClick(
                        pointerType = down.type,
                        secondaryButtonPressed = currentEvent.buttons.isSecondaryPressed,
                        pressStarted = true
                    )
                ) {
                    return@awaitEachGesture
                }
                val compositionLease =
                    gestureOwner.captureActiveLease() ?: return@awaitEachGesture
                // Any new down (a second tap, a long press, a drag) ends a held single tap, here
                // or on another row: only a tap that stays alone opens the thread.
                val cancelledHere = gestureOwner.cancelPendingSingleTap()
                val cancelledElsewhere = gestureOwner.pendingTapSlot?.cancel() == true
                if (cancelledHere || cancelledElsewhere) {
                    gestureOwner.callbacksSnapshot(compositionLease)?.diagnosticsLogger?.invoke(
                        "thread_message_deferred_tap_cancelled messageId=$messageId reason=down " +
                            "sameRow=$cancelledHere"
                    )
                }
                gestureOwner.callbacksSnapshot(compositionLease)?.onPointerDown?.invoke(down.position, down.type)
                gestureOwner.lastDownFromTouch = threadMessageRowTapGesturesFor(down.type)
                gestureOwner.lastDownUptimeMillis = down.uptimeMillis
                gestureOwner.lastDownPosition = down.position
                gestureOwner.doubleTapSlopPx = ThreadMessageDoubleTapSlop.toPx()
                gestureOwner.tapMovementGate.begin(down.position, compositionLease)
                do {
                    val event = awaitPointerEvent()
                    val activeChange = event.changes.firstOrNull { it.id == down.id }
                    if (gestureOwner.isLeaseActive(compositionLease)) {
                        gestureOwner.tapMovementGate.observe(
                            position = activeChange?.position,
                            pointerCount = event.changes.size,
                            touchSlop = viewConfiguration.touchSlop
                        )
                    }
                } while (event.changes.any { it.pressed })
            }
        }
        .threadMessagePointerInput(
            role = "actions",
            messageId = messageId,
            pointerSessionKey = pointerSessionKey,
            gestureOwner = gestureOwner
        ) {
            // raw-click: message rows keep their own tap, long press and selection
            detectTapGestures(
                onPress = { downPosition ->
                    val compositionLease =
                        gestureOwner.captureActiveLease() ?: return@detectTapGestures
                    // The visual harness uses this optional seam to prove that the exact target
                    // row received the real ACTION_DOWN. The callback lives on the route-owned
                    // gesture owner, so a replacement composition updates the existing handler.
                    val pressCallbacks = gestureOwner.callbacksSnapshot(compositionLease)
                    pressCallbacks?.onInputReady?.invoke(messageId)
                    // In-place selection owns presses on the message body: no row press feedback.
                    if (pressCallbacks?.deferLongPressAt?.invoke(downPosition) == true) return@detectTapGestures
                    var indicated = false
                    try {
                        // Scrolls steal the pointer and cancel this scope
                        // within the delay — press feedback never shows for
                        // them (task #555).
                        delay(ThreadMessagePressIndicationDelayMs)
                        indicated = gestureOwner.showPressFeedback(compositionLease, downPosition)
                        tryAwaitRelease()
                    } finally {
                        if (indicated) {
                            gestureOwner.hidePressFeedback(compositionLease)
                        }
                    }
                },
                onTap = {
                    val gestureToken = gestureOwner.tapMovementGate.currentGestureToken()
                    val compositionLease =
                        gestureOwner.captureActiveLease(gestureToken.compositionGeneration)
                            ?: return@detectTapGestures
                    gestureOwner.tapDispatchScope.launch {
                        // Native rich-text annotations may dispatch their Clickable callback
                        // after the parent pointer detector completes. Commit on the next frame
                        // so every child callback from this gesture can reject its generation.
                        withFrameNanos { }
                        val callbacks =
                            gestureOwner.callbacksSnapshot(compositionLease) ?: return@launch
                        val decision = gestureOwner.tapMovementGate.decision(gestureToken)
                        if (!decision.allowed) {
                            // A tap a child took (a link, a reaction, a code block) breaks a pending pair.
                            gestureOwner.doubleTapTracker.reset()
                            if (callbacks.onTap != null || callbacks.onDoubleTap != null) {
                                callbacks.onTapRejected(decision)
                            }
                            return@launch
                        }
                        val selectionShowingAtDown = callbacks.consumeSelectionDismissalTap?.invoke() == true
                        val doubleTapAvailable =
                            callbacks.onDoubleTap != null &&
                                gestureOwner.lastDownFromTouch &&
                                callbacks.deferLongPressAt?.invoke(gestureOwner.lastDownPosition) != true
                        val completesDoubleTap =
                            doubleTapAvailable && !selectionShowingAtDown &&
                                gestureOwner.doubleTapTracker.onTap(
                                    gestureOwner.lastDownUptimeMillis,
                                    gestureOwner.lastDownPosition,
                                    gestureOwner.doubleTapSlopPx
                                )
                        when (
                            decideThreadMessageRowTap(
                                tapAccepted = true,
                                fromTouch = gestureOwner.lastDownFromTouch,
                                selectionShowingAtDown = selectionShowingAtDown,
                                hasSingleTapAction = callbacks.onTap != null,
                                doubleTapAvailable = doubleTapAvailable,
                                completesDoubleTap = completesDoubleTap
                            )
                        ) {
                            ThreadMessageRowTapOutcome.None -> Unit
                            ThreadMessageRowTapOutcome.DismissSelectionOnly ->
                                gestureOwner.doubleTapTracker.reset()
                            ThreadMessageRowTapOutcome.RunSingleTap -> callbacks.onTap?.invoke()
                            ThreadMessageRowTapOutcome.DeferSingleTap ->
                                gestureOwner.deferSingleTap(compositionLease)
                            ThreadMessageRowTapOutcome.ShowReactionBar -> {
                                // The first tap's held action never runs once the pair completed.
                                gestureOwner.cancelPendingSingleTap()
                                val anchor =
                                    gestureOwner.rootAnchor.resolve(
                                        RaftPopoverLocalPoint.Exact(gestureOwner.lastDownPosition)
                                    )
                                if (anchor == null) {
                                    callbacks.diagnosticsLogger(
                                        "thread_message_double_tap_anchor_unavailable messageId=$messageId"
                                    )
                                } else {
                                    callbacks.diagnosticsLogger(
                                        "thread_message_double_tap_reactions messageId=$messageId"
                                    )
                                    callbacks.onDoubleTap?.invoke(anchor)
                                }
                            }
                        }
                    }
                },
                onLongPress = { localOffset ->
                    val gestureToken = gestureOwner.tapMovementGate.currentGestureToken()
                    val compositionLease =
                        gestureOwner.captureActiveLease(gestureToken.compositionGeneration)
                            ?: run {
                                diagnosticsLogger(
                                    "thread_message_long_press_no_lease messageId=$messageId"
                                )
                                return@detectTapGestures
                            }
                    val callbacks =
                        gestureOwner.callbacksSnapshot(compositionLease)
                            ?: run {
                                diagnosticsLogger(
                                    "thread_message_long_press_no_callbacks messageId=$messageId"
                                )
                                return@detectTapGestures
                            }
                    val longPressRoute =
                        threadMessageLongPressRoute(
                            inPlaceSelectionEnabled = callbacks.deferLongPressAt != null,
                            pressOnSelectableBody = callbacks.deferLongPressAt?.invoke(localOffset) == true
                        )
                    if (longPressRoute == ThreadMessageLongPressRoute.InPlaceSelection) {
                        if (callbacks.isInPlaceSelectionTaken == null || !gestureOwner.lastDownFromTouch) {
                            callbacks.diagnosticsLogger(
                                "thread_message_long_press_deferred_to_selection messageId=$messageId"
                            )
                            return@detectTapGestures
                        }
                        gestureOwner.tapDispatchScope.launch {
                            // SelectionContainer only starts touch selection when the finger hits a
                            // BasicText node; both detectors wait longPressTimeoutMillis from the
                            // same DOWN. Yield one frame so SelectionModifierNode and link long-press
                            // finish updating ownership, then fall back to the row message menu if
                            // the press landed in a non-text gap (margin, spacer, divider, etc.).
                            withFrameNanos { }
                            if (!gestureOwner.isLeaseActive(compositionLease)) return@launch
                            if (gestureOwner.tapMovementGate.currentGestureToken() != gestureToken) {
                                return@launch
                            }
                            val latestCallbacks =
                                gestureOwner.callbacksSnapshot(compositionLease) ?: return@launch
                            if (latestCallbacks.isInPlaceSelectionTaken?.invoke() == true) {
                                latestCallbacks.diagnosticsLogger(
                                    "thread_message_long_press_deferred_to_selection messageId=$messageId"
                                )
                                return@launch
                            }
                            latestCallbacks.diagnosticsLogger(
                                "thread_message_long_press_selection_dead_zone_fallback messageId=$messageId"
                            )
                            dispatchThreadMessageLongPressMenu(
                                messageId = messageId,
                                localOffset = localOffset,
                                gestureToken = gestureToken,
                                compositionLease = compositionLease,
                                gestureOwner = gestureOwner,
                                callbacks = latestCallbacks,
                            )
                        }
                        return@detectTapGestures
                    }
                    dispatchThreadMessageLongPressMenu(
                        messageId = messageId,
                        localOffset = localOffset,
                        gestureToken = gestureToken,
                        compositionLease = compositionLease,
                        gestureOwner = gestureOwner,
                        callbacks = callbacks,
                    )
                }
            )
        }
}

private fun dispatchThreadMessageLongPressMenu(
    messageId: String,
    localOffset: Offset,
    gestureToken: ThreadMessageTapGestureToken,
    compositionLease: ThreadMessageLongPressCompositionLease,
    gestureOwner: ThreadMessageLongPressGestureOwner,
    callbacks: ThreadMessageLongPressCallbacks,
) {
    val movementDecision = gestureOwner.tapMovementGate.decision(gestureToken)
    val directPointerMovementDetected =
        movementDecision.reason == "movement" || movementDecision.reason == "multi_touch"
    val dispatchDecision =
        decideThreadMessageLongPressDispatch(
            detectorAccepted = movementDecision.allowed,
            directPointerMovementDetected = directPointerMovementDetected,
            isScrollInProgress = callbacks.isScrollInProgress()
        )
    if (!dispatchDecision.allowed) {
        callbacks.diagnosticsLogger(
            "thread_message_long_press_rejected messageId=$messageId " +
                "reason=${dispatchDecision.reason} movement=${movementDecision.maxDistance} " +
                "touchSlop=${movementDecision.touchSlop} pointers=${movementDecision.pointerCount}"
        )
        return
    }
    callbacks.diagnosticsLogger(
        "thread_message_long_press_admitted messageId=$messageId " +
            "scrollInProgress=${dispatchDecision.scrollInProgress}"
    )
    val anchor =
        gestureOwner.rootAnchor.resolve(RaftPopoverLocalPoint.Exact(localOffset))
    if (anchor == null) {
        callbacks.diagnosticsLogger(
            "thread_message_long_press_anchor_unavailable messageId=$messageId " +
                "scrollInProgress=${dispatchDecision.scrollInProgress}"
        )
        return
    }
    callbacks.diagnosticsLogger(
        "thread_message_long_press_menu_emit messageId=$messageId " +
            "scrollInProgress=${dispatchDecision.scrollInProgress}"
    )
    // The pressed wash belongs to the touch, not to the open menu: clear it
    // as the menu appears instead of waiting for the finger to lift.
    gestureOwner.hidePressFeedback(compositionLease)
    callbacks.onShowActions(anchor)
}

internal data class ThreadMessageTapMovementDecision(
    val allowed: Boolean,
    val reason: String,
    val maxDistance: Float,
    val touchSlop: Float,
    val pointerCount: Int
)

internal data class ThreadMessageTapGestureToken(
    val pointerSessionKey: Int,
    val gestureGeneration: Int,
    val compositionGeneration: Long
)

internal val LocalThreadMessagePointerSessionKey = compositionLocalOf { 0 }

internal data class ThreadMessageLongPressGestureOwnerKey(
    val instanceKey: Any,
    val messageId: String
)

internal data class ThreadMessageLongPressCompositionLease(
    val generation: Long
)

internal data class ThreadMessageLongPressCallbacks(
    val isScrollInProgress: () -> Boolean = { false },
    val diagnosticsLogger: (String) -> Unit = {},
    val onTap: (() -> Unit)? = null,
    val onTapRejected: (ThreadMessageTapMovementDecision) -> Unit = {},
    val onShowActions: (RaftPopoverAnchor) -> Unit = {},
    val onInputReady: ((String) -> Unit)? = null,
    // In-place text selection (New Message Menu toggle): body presses belong to the selection.
    val deferLongPressAt: ((Offset) -> Boolean)? = null,
    // True once in-place selection or a link long-press actually claimed the body press; if false
    // after the long-press frame, the press hit a non-text gap and falls back to the row menu.
    val isInPlaceSelectionTaken: (() -> Boolean)? = null,
    // Every down on the row, with its pointer type (in-place selection: dismissal, and whether a
    // new selection is a finger's long press or a mouse drag).
    val onPointerDown: ((Offset, PointerType) -> Unit)? = null,
    // Two quick finger taps on the row: show its quick reaction bar at the anchor (null where the
    // message cannot be reacted to; then the row has no double tap and [onTap] runs at once).
    val onDoubleTap: ((RaftPopoverAnchor) -> Unit)? = null,
    // In-place selection: true when this tap's gesture began while a selection showed, so the tap
    // only dismissed it (the callback does the dismissal). Read once per accepted tap.
    val consumeSelectionDismissalTap: (() -> Boolean)? = null,
    // A right-click on the row: open the message menu at the anchor (the pointer). Null: the
    // right-click is left to the row's children (the text's own menu).
    val onContextClick: ((RaftPopoverAnchor) -> Unit)? = null,
    // Row-local point owned by a child's own context menu (e.g. an ArtifactMessageCard, web #16110):
    // leave the secondary press unconsumed so the child opens its menu instead of the message's.
    val deferContextClickAt: ((Offset) -> Boolean)? = null
)

/**
 * Two finger taps on one message row within [ThreadMessageDoubleTapTimeoutMillis] and
 * [ThreadMessageDoubleTapSlop] show its quick reaction bar; a single tap opens its thread (stdrc,
 * 2026-10-29, #proj-raft-ui:911ccc08, replacing artin's double tap to open the thread).
 * Detected by hand rather than with the detector's double tap, so a row without a double tap (no
 * reactions there) keeps its single tap immediate; where both exist the single tap waits out the
 * timeout ([decideThreadMessageRowTap]). Only taps the row accepted count (a link, a button, a
 * reaction, an image or a code block takes its own tap), only from a finger (a mouse double click
 * selects a word), and the pair resets after a double tap so a third tap starts over.
 */
internal class ThreadMessageDoubleTapTracker {
    private var lastUptimeMillis = -1L
    private var lastPosition = Offset.Zero

    /** Records an accepted tap; true when it completes a double tap with the previous one. */
    fun onTap(uptimeMillis: Long, position: Offset, slopPx: Float): Boolean {
        val previous = lastUptimeMillis
        val isDouble =
            previous >= 0 &&
                uptimeMillis - previous in 0..ThreadMessageDoubleTapTimeoutMillis &&
                (position - lastPosition).getDistance() <= slopPx
        if (isDouble) {
            lastUptimeMillis = -1L
        } else {
            lastUptimeMillis = uptimeMillis
            lastPosition = position
        }
        return isDouble
    }

    fun reset() {
        lastUptimeMillis = -1L
    }
}

internal const val ThreadMessageDoubleTapTimeoutMillis = 250L
internal val ThreadMessageDoubleTapSlop = 10.dp

internal class ThreadMessageLongPressGestureOwner(
    val key: ThreadMessageLongPressGestureOwnerKey,
    val tapDispatchScope: CoroutineScope,
    /** The thread's shared held-tap slot ([ThreadMessageLongPressGestureOwnerStore.pendingTapSlot]). */
    val pendingTapSlot: ThreadMessagePendingTapSlot? = null
) {
    val rootAnchor =
        RaftLiveRootAnchorRef(identity = threadMessageMenuOwnerIdentity(key.messageId))
    val tapMovementGate = ThreadMessageTapMovementGate()
    val doubleTapTracker = ThreadMessageDoubleTapTracker()

    /** The single tap held back while a double tap may still come (one per thread, see the slot). */
    private var pendingSingleTap: Job? = null

    /**
     * Runs this row's single-tap action once the double-tap timeout passes, unless a down (here
     * or on another row of the thread: a second tap, a long press, a drag) or leaving composition
     * cancels it.
     */
    fun deferSingleTap(lease: ThreadMessageLongPressCompositionLease) {
        cancelPendingSingleTap()
        val slot = pendingTapSlot
        slot?.cancel()
        val job =
            tapDispatchScope.launch {
                delay(ThreadMessageDoubleTapTimeoutMillis)
                // A row that left composition has no callbacks. A scroll is not re-checked here:
                // the list also reports programmatic anchoring as scrolling, and a drag begins
                // with a down, which already cancelled this.
                callbacksSnapshot(lease)?.onTap?.invoke()
            }
        pendingSingleTap = job
        slot?.hold(job)
        job.invokeOnCompletion { if (pendingSingleTap === job) pendingSingleTap = null }
    }

    /** Cancels a held single tap; true when one was waiting. */
    fun cancelPendingSingleTap(): Boolean {
        val job = pendingSingleTap ?: return false
        pendingSingleTap = null
        job.cancel()
        return true
    }

    /**
     * The current gesture's down: from a finger ([threadMessageRowTapGesturesFor]: only then do the
     * row's tap and double tap act), when, where, and the slop in px (for the double tap).
     */
    var lastDownFromTouch = false
    var lastDownUptimeMillis = 0L
    var lastDownPosition = Offset.Zero
    var doubleTapSlopPx = 0f

    var pressed by mutableStateOf(false)
        private set
    var pressRippleOrigin by mutableStateOf<Offset?>(null)
        private set

    private var callbacks = ThreadMessageLongPressCallbacks()
    private var compositionGeneration = 0L
    private var compositionActive = false

    internal fun retainCompositionAuthority() {
        if (compositionActive) return
        compositionGeneration = nextThreadMessageCompositionGeneration(compositionGeneration)
        compositionActive = true
    }

    internal fun finalizeInactiveCompositionAuthority() {
        if (!compositionActive) return
        compositionActive = false
        callbacks = ThreadMessageLongPressCallbacks()
        cancelPendingSingleTap()
        doubleTapTracker.reset()
        tapMovementGate.invalidateCompositionAuthority()
        rootAnchor.invalidate()
        pressed = false
        pressRippleOrigin = null
    }

    fun captureActiveLease(): ThreadMessageLongPressCompositionLease? =
        if (compositionActive) {
            ThreadMessageLongPressCompositionLease(compositionGeneration)
        } else {
            null
        }

    fun captureActiveLease(
        expectedGeneration: Long
    ): ThreadMessageLongPressCompositionLease? =
        captureActiveLease()?.takeIf { it.generation == expectedGeneration }

    fun isLeaseActive(lease: ThreadMessageLongPressCompositionLease): Boolean =
        compositionActive && lease.generation == compositionGeneration

    fun updateCallbacks(next: ThreadMessageLongPressCallbacks) {
        if (compositionActive) callbacks = next
    }

    fun callbacksSnapshot(
        lease: ThreadMessageLongPressCompositionLease
    ): ThreadMessageLongPressCallbacks? = callbacks.takeIf { isLeaseActive(lease) }

    fun showPressFeedback(
        lease: ThreadMessageLongPressCompositionLease,
        origin: Offset
    ): Boolean {
        if (!isLeaseActive(lease)) return false
        pressRippleOrigin = origin
        pressed = true
        return true
    }

    fun hidePressFeedback(lease: ThreadMessageLongPressCompositionLease) {
        if (isLeaseActive(lease)) pressed = false
    }

    /**
     * A finger's long press on a link in this row's text ([position] in [source]'s local px): the
     * row's long-press menu at the press, as a long press anywhere else on the row opens it.
     * [beforeShow] gets the menu's anchor first (the link section is keyed to it). False when the
     * row cannot open its menu now (left composition, no anchor): the press is then left alone.
     */
    fun showActionsForLink(
        source: LayoutCoordinates,
        position: Offset,
        beforeShow: (RaftPopoverAnchor) -> Unit
    ): Boolean {
        val lease = captureActiveLease() ?: return false
        val current = callbacksSnapshot(lease) ?: return false
        val anchor =
            rootAnchor.resolveAt(source, position)
                ?: run {
                    current.diagnosticsLogger("thread_message_link_long_press_anchor_unavailable messageId=${key.messageId}")
                    return false
                }
        current.diagnosticsLogger("thread_message_link_long_press_menu messageId=${key.messageId}")
        hidePressFeedback(lease)
        beforeShow(anchor)
        current.onShowActions(anchor)
        return true
    }

    /**
     * A finger's long press on an interactive child card in this row ([position] in [source]'s
     * local px, such as an [ArtifactMessageCard] whose own click would otherwise swallow the
     * press): opens the row's long-press menu at the press, as a long press elsewhere on the row
     * does. False when the row cannot open its menu now (left composition, no anchor).
     */
    fun showActionsForChild(
        source: LayoutCoordinates,
        position: Offset,
        childRole: String,
    ): Boolean {
        val lease = captureActiveLease() ?: return false
        val current = callbacksSnapshot(lease) ?: return false
        val anchor =
            rootAnchor.resolveAt(source, position)
                ?: run {
                    current.diagnosticsLogger("thread_message_child_long_press_anchor_unavailable role=$childRole messageId=${key.messageId}")
                    return false
                }
        current.diagnosticsLogger("thread_message_child_long_press_menu role=$childRole messageId=${key.messageId}")
        hidePressFeedback(lease)
        current.onShowActions(anchor)
        return true
    }

    fun clearReleasedPressRipple(
        lease: ThreadMessageLongPressCompositionLease,
        expectedOrigin: Offset
    ) {
        if (isLeaseActive(lease) && !pressed && pressRippleOrigin == expectedOrigin) {
            pressRippleOrigin = null
        }
    }
}

internal fun nextThreadMessageCompositionGeneration(current: Long): Long =
    if (current == Long.MAX_VALUE) 0L else current + 1L

internal const val ThreadMessageLongPressGestureOwnerRetentionLimit = 256

/**
 * The one single tap a thread holds back for a possible double tap. A down on any row cancels it,
 * so a quick tap on another message (or a drag starting there) never lets the first one fire late.
 */
internal class ThreadMessagePendingTapSlot {
    private var job: Job? = null

    fun hold(next: Job) {
        job = next
        next.invokeOnCompletion { if (job === next) job = null }
    }

    /** Cancels the held tap; true when one was waiting. */
    fun cancel(): Boolean {
        val held = job ?: return false
        job = null
        held.cancel()
        return true
    }
}

internal class ThreadMessageLongPressGestureOwnerStore(
    private val tapDispatchScope: CoroutineScope,
    private val maxRetainedOwners: Int = ThreadMessageLongPressGestureOwnerRetentionLimit
) {
    init {
        require(maxRetainedOwners > 0) { "maxRetainedOwners must be positive" }
    }

    /** Shared by every row of this thread: see [ThreadMessagePendingTapSlot]. */
    val pendingTapSlot = ThreadMessagePendingTapSlot()

    private data class Entry(
        val owner: ThreadMessageLongPressGestureOwner,
        var activeOwnerCount: Int,
        var lastAccessOrdinal: Long,
        var lifecycleRevision: Long
    )

    private val owners = mutableMapOf<ThreadMessageLongPressGestureOwnerKey, Entry>()
    private var accessOrdinal = 0L

    fun ownerFor(instanceKey: Any, messageId: String): ThreadMessageLongPressGestureOwner =
        ownerFor(ThreadMessageLongPressGestureOwnerKey(instanceKey, messageId))

    fun ownerFor(key: ThreadMessageLongPressGestureOwnerKey): ThreadMessageLongPressGestureOwner {
        owners[key]?.let { entry ->
            entry.lastAccessOrdinal = nextAccessOrdinal()
            return entry.owner
        }
        return ThreadMessageLongPressGestureOwner(key, tapDispatchScope, pendingTapSlot)
            .also { owner ->
                owners[key] =
                    Entry(
                        owner = owner,
                        activeOwnerCount = 0,
                        lastAccessOrdinal = nextAccessOrdinal(),
                        lifecycleRevision = 0L
                    )
            }
    }

    fun retain(
        key: ThreadMessageLongPressGestureOwnerKey,
        owner: ThreadMessageLongPressGestureOwner
    ) {
        val entry = owners[key]
        if (entry == null || entry.owner !== owner) {
            entry?.owner?.finalizeInactiveCompositionAuthority()
            owner.retainCompositionAuthority()
            owners[key] =
                Entry(
                    owner = owner,
                    activeOwnerCount = 1,
                    lastAccessOrdinal = nextAccessOrdinal(),
                    lifecycleRevision = 1L
                )
        } else {
            entry.lifecycleRevision = nextThreadMessageCompositionGeneration(entry.lifecycleRevision)
            if (entry.activeOwnerCount == 0) owner.retainCompositionAuthority()
            entry.activeOwnerCount += 1
            entry.lastAccessOrdinal = nextAccessOrdinal()
        }
        pruneInactiveOwners()
    }

    fun release(
        key: ThreadMessageLongPressGestureOwnerKey,
        owner: ThreadMessageLongPressGestureOwner
    ) {
        val entry = owners[key]?.takeIf { it.owner === owner } ?: return
        if (entry.activeOwnerCount > 0) entry.activeOwnerCount -= 1
        if (entry.activeOwnerCount != 0) return
        entry.lifecycleRevision = nextThreadMessageCompositionGeneration(entry.lifecycleRevision)
        val expectedLifecycleRevision = entry.lifecycleRevision
        tapDispatchScope.launch(start = CoroutineStart.UNDISPATCHED) {
            // DisposableEffect replacement can apply as 1→0→1. Yield beyond the current apply
            // stack so a same-semantic retain can reclaim the continuing gesture authority.
            yield()
            val currentEntry = owners[key]
            if (
                currentEntry?.owner === owner &&
                    currentEntry.activeOwnerCount == 0 &&
                    currentEntry.lifecycleRevision == expectedLifecycleRevision
            ) {
                owner.finalizeInactiveCompositionAuthority()
                pruneInactiveOwners()
            }
        }
    }

    private fun pruneInactiveOwners() {
        while (owners.size > maxRetainedOwners) {
            val oldestInactive =
                owners.entries
                    .filter { (_, entry) ->
                        entry.activeOwnerCount == 0 && entry.owner.captureActiveLease() == null
                    }
                    .minByOrNull { (_, entry) -> entry.lastAccessOrdinal }
                    ?: return
            owners.remove(oldestInactive.key)?.owner?.finalizeInactiveCompositionAuthority()
        }
    }

    private fun nextAccessOrdinal(): Long {
        accessOrdinal += 1L
        return accessOrdinal
    }
}

internal val LocalThreadMessageLongPressGestureOwnerStore =
    compositionLocalOf<ThreadMessageLongPressGestureOwnerStore?> { null }

/**
 * Child regions inside a message row that own their own right-click context menu (live
 * [ArtifactMessageCard]s, web `ArtifactCardMenu.tsx`, botiverse/slock #16110).
 *
 * The row's `context_click` detector runs in [PointerEventPass.Initial] (ahead of the body's
 * `SelectionContainer`), before any descendant sees that pass. Active children register their live
 * [LayoutCoordinates] here so the row leaves right-clicks inside them unconsumed for the child.
 */
internal class ThreadMessageChildContextMenus {
    internal var rowCoordinates: LayoutCoordinates? = null
    private val childCoordinates = mutableMapOf<Any, LayoutCoordinates>()

    fun register(key: Any, coordinates: LayoutCoordinates) {
        childCoordinates[key] = coordinates
    }

    fun unregister(key: Any) {
        childCoordinates.remove(key)
    }

    fun containsRowOffset(offset: Offset): Boolean {
        val row = rowCoordinates?.takeIf { it.isAttached } ?: return false
        return childCoordinates.values.any { child ->
            if (!child.isAttached) return@any false
            val width = child.size.width.toFloat()
            val height = child.size.height.toFloat()
            if (width <= 0f || height <= 0f) return@any false
            val local = child.localPositionOf(row, offset)
            local.x in 0f..width && local.y in 0f..height
        }
    }
}

internal val LocalThreadMessageChildContextMenus =
    staticCompositionLocalOf<ThreadMessageChildContextMenus?> { null }

/** Where an interactive child card in a message row forwards a finger's long press. */
internal fun interface ThreadMessageChildLongPressHandler {
    fun onChildLongPress(source: LayoutCoordinates, position: Offset): Boolean
}

internal val LocalThreadMessageChildLongPress =
    staticCompositionLocalOf<ThreadMessageChildLongPressHandler?> { null }

internal fun nextThreadPointerSessionKey(current: Int): Int =
    if (current == Int.MAX_VALUE) 0 else current + 1

internal class ThreadMessageTapMovementGate {
    private var downPosition: Offset? = null
    private var canceled: Boolean = false
    private var reason: String = "none"
    private var maxDistance: Float = 0f
    private var touchSlop: Float = 0f
    private var pointerCount: Int = 1
    private var gestureGeneration: Int = 0
    private var pointerSessionKey: Int = 0
    private var compositionGeneration: Long = 0L

    fun activatePointerSession(pointerSessionKey: Int) {
        if (this.pointerSessionKey == pointerSessionKey) return
        this.pointerSessionKey = pointerSessionKey
        downPosition = null
        canceled = true
        reason = "session_superseded"
    }

    fun begin(
        position: Offset,
        compositionLease: ThreadMessageLongPressCompositionLease =
            ThreadMessageLongPressCompositionLease(generation = 0L)
    ) {
        gestureGeneration = nextThreadPointerSessionKey(gestureGeneration)
        compositionGeneration = compositionLease.generation
        downPosition = position
        canceled = false
        reason = "none"
        maxDistance = 0f
        touchSlop = 0f
        pointerCount = 1
    }

    fun observe(
        position: Offset?,
        pointerCount: Int,
        touchSlop: Float
    ) {
        this.touchSlop = touchSlop
        this.pointerCount = pointerCount
        val down = downPosition
        if (down == null || position == null) {
            canceled = true
            reason = "missing_pointer"
            return
        }
        if (pointerCount != 1) {
            canceled = true
            reason = "multi_touch"
            return
        }
        maxDistance = maxOf(maxDistance, (position - down).getDistance())
        if (maxDistance > touchSlop) {
            canceled = true
            reason = "movement"
        }
    }

    fun rejectChildAction(source: String) {
        if (downPosition == null) return
        canceled = true
        reason = "child_action:$source"
    }

    fun currentGestureToken(): ThreadMessageTapGestureToken =
        ThreadMessageTapGestureToken(
            pointerSessionKey = pointerSessionKey,
            gestureGeneration = gestureGeneration,
            compositionGeneration = compositionGeneration
        )

    fun invalidateCompositionAuthority() {
        gestureGeneration = nextThreadPointerSessionKey(gestureGeneration)
        downPosition = null
        canceled = true
        reason = "composition_inactive"
    }

    fun decision(
        expectedGestureToken: ThreadMessageTapGestureToken = currentGestureToken()
    ): ThreadMessageTapMovementDecision =
        ThreadMessageTapMovementDecision(
            allowed =
                expectedGestureToken.pointerSessionKey == pointerSessionKey &&
                    expectedGestureToken.gestureGeneration == gestureGeneration &&
                    downPosition != null &&
                    !canceled,
            reason =
                when {
                    expectedGestureToken.pointerSessionKey != pointerSessionKey -> "session_superseded"
                    expectedGestureToken.gestureGeneration != gestureGeneration -> "gesture_superseded"
                    else -> reason
                },
            maxDistance = maxDistance,
            touchSlop = touchSlop,
            pointerCount = pointerCount
        )
}
