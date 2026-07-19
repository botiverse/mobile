package build.raft.app

import com.tencent.kuikly.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertFalse
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
    fun ordinaryMessageBodyTapOpensItsThread() {
        assertTrue(
            threadMessageTapOpensThread(
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
            )
        )
        assertFalse(
            threadMessageTapOpensThread(
                showReplyAction = true,
                shareSelectionActive = true,
                hasLocalSendState = false
            )
        )
        assertFalse(
            threadMessageTapOpensThread(
                showReplyAction = true,
                shareSelectionActive = false,
                hasLocalSendState = true
            )
        )
    }

    @Test
    fun replyThreadIgnoresAnyStaleMessageOpenEvent() {
        assertTrue(threadMessageOpenIgnoredForSurface(KuiklyThreadSurfaceKind.ReplyThread))
        assertFalse(threadMessageOpenIgnoredForSurface(KuiklyThreadSurfaceKind.Channel))
        assertFalse(threadMessageOpenIgnoredForSurface(KuiklyThreadSurfaceKind.DirectMessage))
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
    fun pointerSessionKeyAdvancesAcrossHostResumeAndWrapsSafely() {
        assertTrue(nextThreadPointerSessionKey(0) == 1)
        assertTrue(nextThreadPointerSessionKey(41) == 42)
        assertTrue(nextThreadPointerSessionKey(Int.MAX_VALUE) == 0)
    }
}
