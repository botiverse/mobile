package build.raft.app

import androidx.compose.ui.geometry.Offset
import build.raft.app.thread.ThreadMessageTapMovementGate
import build.raft.app.thread.ThreadVisibleResumeGate
import build.raft.app.thread.ThreadVisibleResumePlan
import build.raft.app.thread.decideThreadMessageLongPressDispatch
import build.raft.app.thread.threadMessageBodyTapEligible
import build.raft.app.thread.threadMessageDoubleTapShowsReactions
import build.raft.app.thread.threadMessageTapOpensThread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThreadMessageLongPressSurfaceTest {
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
