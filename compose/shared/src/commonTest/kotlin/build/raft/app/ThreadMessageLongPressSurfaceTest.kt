package build.raft.app

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThreadMessageLongPressSurfaceTest {
    @Test
    fun normalMessageUsesTheFullRowGestureSurface() {
        assertTrue(threadMessageUsesFullRowLongPressActions(shareSelectionActive = false))
    }

    @Test
    fun shareSelectionKeepsItsDedicatedSelectionGestures() {
        assertFalse(threadMessageUsesFullRowLongPressActions(shareSelectionActive = true))
    }

    @Test
    fun stationaryLongPressSurvivesProgrammaticListScroll() {
        val decision =
            decideThreadMessageLongPressDispatch(
                detectorAccepted = true,
                directPointerMovementDetected = false,
                isScrollInProgress = true
            )

        assertTrue(decision.allowed)
        assertTrue(decision.reason == "admitted")
        assertTrue(decision.scrollInProgress)
    }

    @Test
    fun directPointerMovementStillRejectsLongPress() {
        val decision =
            decideThreadMessageLongPressDispatch(
                detectorAccepted = true,
                directPointerMovementDetected = true,
                isScrollInProgress = false
            )

        assertFalse(decision.allowed)
        assertTrue(decision.reason == "direct_pointer_movement")
    }

    @Test
    fun ordinaryMessageBodyTapOpensItsThreadWithOrWithoutReplies() {
        // stdrc, 2026-10-29 (#proj-raft-ui:911ccc08): no setting, no reply-count requirement; a
        // message without replies opens its empty thread to start one (web handleMobileTap).
        assertTrue(
            threadMessageTapOpensThread(
                showReplyAction = true,
                shareSelectionActive = false,
                hasLocalSendState = false
            )
        )
        assertTrue(
            threadMessageBodyTapEligible(
                showReplyAction = true,
                shareSelectionActive = false,
                hasLocalSendState = false
            )
        )
    }

    @Test
    fun bodyTapDoesNotReplaceReplyThreadSelectionOrPendingSendActions() {
        assertFalse(
            threadMessageTapOpensThread(
                showReplyAction = false,
                shareSelectionActive = false,
                hasLocalSendState = false
            ),
            "inside a reply thread a tap does nothing"
        )
        assertFalse(
            threadMessageTapOpensThread(
                showReplyAction = true,
                shareSelectionActive = true,
                hasLocalSendState = false
            ),
            "while sharing a tap toggles the selection"
        )
        assertFalse(
            threadMessageTapOpensThread(
                showReplyAction = true,
                shareSelectionActive = false,
                hasLocalSendState = true
            ),
            "a sending or failed message has no tap action"
        )
    }

    @Test
    fun doubleTapShowsReactionsOnlyWhereTheMessageCanBeReactedTo() {
        assertTrue(
            threadMessageDoubleTapShowsReactions(
                quickReactions = true,
                shareSelectionActive = false,
                hasLocalSendState = false
            )
        )
        assertFalse(
            threadMessageDoubleTapShowsReactions(
                quickReactions = false,
                shareSelectionActive = false,
                hasLocalSendState = false
            ),
            "no reaction row in the menu: no double tap"
        )
        assertFalse(
            threadMessageDoubleTapShowsReactions(
                quickReactions = true,
                shareSelectionActive = true,
                hasLocalSendState = false
            ),
            "no double tap while sharing"
        )
        assertFalse(
            threadMessageDoubleTapShowsReactions(
                quickReactions = true,
                shareSelectionActive = false,
                hasLocalSendState = true
            ),
            "no double tap on a local send"
        )
    }

    @Test
    fun replyThreadIgnoresAnyStaleMessageOpenEvent() {
        assertTrue(threadMessageOpenIgnoredForSurface(RaftThreadSurfaceKind.ReplyThread))
        assertFalse(threadMessageOpenIgnoredForSurface(RaftThreadSurfaceKind.Channel))
        assertFalse(threadMessageOpenIgnoredForSurface(RaftThreadSurfaceKind.DirectMessage))
    }

    @Test
    fun rowTapMovementGateCancelsVerticalSwipeBeyondTouchSlop() {
        val gate = ThreadMessageTapMovementGate()
        gate.begin(Offset(500f, 995f))

        gate.observe(
            position = Offset(500f, 480f),
            pointerCount = 1,
            touchSlop = 18f
        )

        val decision = gate.decision()
        assertFalse(decision.allowed)
        assertTrue(decision.reason == "movement")
        assertTrue(decision.maxDistance == 515f)
    }

    @Test
    fun rowTapMovementGateAllowsJitterButRejectsMultiTouch() {
        val gate = ThreadMessageTapMovementGate()
        gate.begin(Offset(100f, 100f))
        gate.observe(Offset(105f, 104f), pointerCount = 1, touchSlop = 18f)
        assertTrue(gate.decision().allowed)

        gate.begin(Offset(100f, 100f))
        gate.observe(Offset(100f, 100f), pointerCount = 2, touchSlop = 18f)
        assertFalse(gate.decision().allowed)
        assertTrue(gate.decision().reason == "multi_touch")
    }

    @Test
    fun rowTapMovementGateResetsForFirstGestureAfterResume() {
        val gate = ThreadMessageTapMovementGate()
        gate.begin(Offset.Zero)
        gate.observe(Offset(100f, 100f), pointerCount = 1, touchSlop = 18f)
        assertFalse(gate.decision().allowed)

        gate.begin(Offset(20f, 20f))
        gate.observe(Offset(20f, 20f), pointerCount = 1, touchSlop = 18f)

        assertTrue(gate.decision().allowed)
    }

    @Test
    fun childActionRejectsOnlyTheOwningRowTapGesture() {
        val gate = ThreadMessageTapMovementGate()
        gate.begin(Offset(100f, 100f))
        val childGesture = gate.currentGestureToken()
        gate.rejectChildAction("markdown_url")

        val rejected = gate.decision(childGesture)
        assertFalse(rejected.allowed)
        assertTrue(rejected.reason == "child_action:markdown_url")

        gate.begin(Offset(120f, 120f))
        gate.observe(Offset(120f, 120f), pointerCount = 1, touchSlop = 18f)
        assertTrue(gate.decision().allowed)
        val staleDecision = gate.decision(childGesture)
        assertFalse(staleDecision.allowed)
        assertTrue(staleDecision.reason == "gesture_superseded")
    }

    @Test
    fun pendingRowTapIsRejectedWhenPointerSessionAdvancesWithoutNewDown() {
        val gate = ThreadMessageTapMovementGate()
        gate.activatePointerSession(7)
        gate.begin(Offset(100f, 100f))
        val pendingTap = gate.currentGestureToken()

        gate.activatePointerSession(8)

        val staleSessionDecision = gate.decision(pendingTap)
        assertFalse(staleSessionDecision.allowed)
        assertTrue(staleSessionDecision.reason == "session_superseded")

        gate.begin(Offset(120f, 120f))
        gate.observe(Offset(120f, 120f), pointerCount = 1, touchSlop = 18f)
        assertTrue(gate.decision().allowed)
    }

    @Test
    fun pointerSessionKeyAdvancesAcrossHostResumeAndWrapsSafely() {
        assertTrue(nextThreadPointerSessionKey(0) == 1)
        assertTrue(nextThreadPointerSessionKey(41) == 42)
        assertTrue(nextThreadPointerSessionKey(Int.MAX_VALUE) == 0)
    }

    @Test
    fun visibleThreadResumePlansOnlyTheCurrentConversationWork() {
        assertEquals(
            ThreadVisibleResumePlan(
                refreshVisibleConversation = true,
                rejoinVisibleRooms = true
            ),
            threadVisibleResumePlan(routeActive = true)
        )
        assertNull(threadVisibleResumePlan(routeActive = false))
    }

    @Test
    fun hiddenThreadSkipsAppShellResumeThenRefreshesExactlyOnceAfterAppearing() {
        val gate = ThreadVisibleResumeGate()
        var conversationRefreshes = 0
        var roomRejoins = 0

        assertNull(gate.onLifecycleVisibleResume(routeActive = false))
        gate.onRouteBecameVisible()?.let { plan ->
            if (plan.refreshVisibleConversation) conversationRefreshes += 1
            if (plan.rejoinVisibleRooms) roomRejoins += 1
        }
        assertNull(gate.onRouteBecameVisible())

        assertEquals(1, conversationRefreshes)
        assertEquals(1, roomRejoins)
    }

    @Test
    fun threadAppearanceWithoutPendingResumeDoesNotRefresh() {
        val gate = ThreadVisibleResumeGate()

        assertNull(gate.onRouteBecameVisible())
        assertNull(gate.onRouteBecameVisible())
    }

    @Test
    fun pendingResumeLatchedWhileHiddenStaysObservableUntilDrained() {
        val gate = ThreadVisibleResumeGate()

        assertFalse(gate.pendingVisibleResume)
        // The lifecycle listener can latch pending from a stale routeActive
        // snapshot after the route already became active; the drain effect
        // keys on this flag so the pending write alone re-triggers it.
        assertNull(gate.onLifecycleVisibleResume(routeActive = false))
        assertTrue(gate.pendingVisibleResume)

        assertEquals(
            ThreadVisibleResumePlan(
                refreshVisibleConversation = true,
                rejoinVisibleRooms = true
            ),
            gate.onRouteBecameVisible()
        )
        assertFalse(gate.pendingVisibleResume)
    }

    @Test
    fun firstVisibleLifecycleResumeRefreshesWithoutWaitingForScrollOrAppearance() {
        val gate = ThreadVisibleResumeGate()

        assertEquals(
            ThreadVisibleResumePlan(
                refreshVisibleConversation = true,
                rejoinVisibleRooms = true
            ),
            gate.onLifecycleVisibleResume(routeActive = true)
        )
        assertFalse(gate.pendingVisibleResume)
        assertNull(gate.onRouteBecameVisible())
    }
}
