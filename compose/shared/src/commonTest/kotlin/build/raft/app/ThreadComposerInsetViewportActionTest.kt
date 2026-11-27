package build.raft.app

import build.raft.app.thread.LazyViewportAnchorFallback
import build.raft.app.thread.LazyViewportAnchorPlacement
import build.raft.app.thread.LazyViewportAnchorTarget
import build.raft.app.thread.LazyViewportCommandTarget
import build.raft.app.thread.LazyViewportControllerState
import build.raft.app.thread.LazyViewportEvent
import build.raft.app.thread.LazyViewportIntent
import build.raft.app.thread.LazyViewportMutation
import build.raft.app.thread.ThreadComposerInsetViewportAction
import build.raft.app.thread.reduceLazyViewport
import build.raft.app.thread.resolveThreadComposerInsetViewportAction
import build.raft.app.thread.viewportEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The parked-bottom composer correction (keyboard rise / composer resize) on a reversed thread list
 * must not replay a stale `Anchored` intent: replaying re-applies a top-relative placement after the
 * IME animation settles, which is the "moves up with the keyboard, then snaps top-aligned" jump
 * (Hands f4e1e328 diagnostics, 1.15.1: consumed route-focus anchor on the newest message,
 * `placement=replay_intent viewportPlacement=Focused`, target row re-placed at offset 0).
 */
class ThreadComposerInsetViewportActionTest {
    private val focusedAnchor =
        reduceLazyViewport(
            LazyViewportControllerState(),
            LazyViewportEvent.Anchor(
                target = LazyViewportAnchorTarget.Message("latest"),
                offset = 0,
                layoutGeneration = 3L,
                placement = LazyViewportAnchorPlacement.Focused,
                fallback = LazyViewportAnchorFallback.Free
            )
        ).let { state -> reduceLazyViewport(state, LazyViewportEvent.CommandConsumed(state.pendingCommand!!.id)) }

    private fun applyAction(
        state: LazyViewportControllerState,
        reverseLayout: Boolean,
        tailAtRenderedBottom: Boolean
    ): Pair<ThreadComposerInsetViewportAction, LazyViewportControllerState> {
        val action =
            resolveThreadComposerInsetViewportAction(
                reverseLayout = reverseLayout,
                intent = state.intent,
                tailAtRenderedBottom = tailAtRenderedBottom
            )
        val next = action.viewportEvent()?.let { event -> reduceLazyViewport(state, event) } ?: state
        return action to next
    }

    @Test
    fun reversedParkedTailRetiresConsumedFocusedAnchorInsteadOfTopAligningIt() {
        // Root cause guard: the old path replayed Focused placement on InsetChange.
        val replayed =
            reduceLazyViewport(focusedAnchor, LazyViewportEvent.ContentMutated(LazyViewportMutation.InsetChange))
        val replayedTarget = assertIs<LazyViewportCommandTarget.Anchor>(replayed.pendingCommand?.target)
        assertEquals(LazyViewportAnchorPlacement.Focused, replayedTarget.placement)

        val (action, next) = applyAction(focusedAnchor, reverseLayout = true, tailAtRenderedBottom = true)

        assertEquals(ThreadComposerInsetViewportAction.FollowLatest, action)
        assertEquals(LazyViewportIntent.FollowingLatest, next.intent)
        assertEquals(LazyViewportCommandTarget.Latest, next.pendingCommand?.target)
    }

    @Test
    fun followingLatestStillReplaysTheLatestCommand() {
        val following = LazyViewportControllerState()
        listOf(true, false).forEach { reverse ->
            val (action, next) = applyAction(following, reverseLayout = reverse, tailAtRenderedBottom = true)
            assertEquals(ThreadComposerInsetViewportAction.ReplayIntent, action)
            assertEquals(LazyViewportCommandTarget.Latest, next.pendingCommand?.target)
        }
    }

    @Test
    fun userDraggingIsNeverOverriddenByTheInsetCorrection() {
        val dragging = reduceLazyViewport(focusedAnchor, LazyViewportEvent.PointerDown)
        listOf(true, false).forEach { tailAtBottom ->
            val (action, next) = applyAction(dragging, reverseLayout = true, tailAtRenderedBottom = tailAtBottom)
            assertEquals(ThreadComposerInsetViewportAction.ReplayIntent, action)
            assertEquals(LazyViewportIntent.UserDragging, next.intent)
            assertNull(next.pendingCommand)
        }
        // Even the FollowLatest event this correction emits cannot override a drag.
        val followed =
            reduceLazyViewport(dragging, ThreadComposerInsetViewportAction.FollowLatest.viewportEvent()!!)
        assertEquals(LazyViewportIntent.UserDragging, followed.intent)
        assertNull(followed.pendingCommand)
    }
}
