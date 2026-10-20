package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RaftGestureOwnerTest {
    @Test
    fun callbackSlotUpdatesWithoutChangingPointerOwnerToken() {
        val owner = owner(callback = "old")
        val token = owner.pointerInputOwnerToken
        val oldGesture = owner.beginGesture()

        owner.updateCallbacks(TestCallbacks("current"))
        assertEquals("old", oldGesture.currentCallbacksOrNull()?.callback)
        val currentGesture = owner.beginGesture()

        assertSame(token, owner.pointerInputOwnerToken)
        assertEquals("current", currentGesture.currentCallbacksOrNull()?.callback)
        assertFalse(oldGesture.generationToken === currentGesture.generationToken)
        assertFalse(oldGesture.isCurrent())
        assertTrue(currentGesture.isCurrent())
        assertTrue(owner.pointerInputKeys("tap").contentEquals(arrayOf(token, "tap")))
    }

    @Test
    fun callbackUpdateDoesNotChangeActiveGestureButDisposalInvalidatesIt() {
        val owner = owner(callback = "down")
        val gesture = owner.beginGesture()

        owner.updateCallbacks(TestCallbacks("next"))

        assertTrue(gesture.isCurrent())
        assertEquals("down", gesture.currentCallbacksOrNull()?.callback)
        owner.dispose()
        assertFalse(gesture.isCurrent())
        assertEquals(null, gesture.currentCallbacksOrNull())

        val lateGesture = owner.beginGesture()
        assertFalse(lateGesture.isCurrent())
        assertEquals(null, lateGesture.currentCallbacksOrNull())
    }

    @Test
    fun sameSemanticIdentityInReplacementCompositionGetsDifferentPointerOwnerToken() {
        val oldOwner = owner(callback = "old")
        val replacementOwner = owner(callback = "replacement")

        assertEquals(oldOwner.semanticIdentity, replacementOwner.semanticIdentity)
        assertFalse(
            oldOwner.pointerInputKeys("tap").contentEquals(replacementOwner.pointerInputKeys("tap")),
            "equal semantic keys must not let Kuikly retain the old composition handler"
        )
    }

    @Test
    fun independentBehaviorOwnersDoNotInvalidateEachOthersSessions() {
        val clickOwner = owner(surface = "row-click", callback = "click")
        val dragOwner = owner(surface = "row-drag", callback = "drag")
        val clickGesture = clickOwner.beginGesture()
        val dragGesture = dragOwner.beginGesture()

        assertTrue(clickGesture.isCurrent())
        assertTrue(dragGesture.isCurrent())
        assertEquals("click", clickGesture.currentCallbacksOrNull()?.callback)
        assertEquals("drag", dragGesture.currentCallbacksOrNull()?.callback)
    }

    private fun owner(
        callback: String,
        surface: String = "test-row"
    ): RaftGestureOwner<TestCallbacks> =
        RaftGestureOwner(
            semanticIdentity = RaftGestureSemanticIdentity(surface, "same-id"),
            initialCallbacks = TestCallbacks(callback)
        )

    private data class TestCallbacks(val callback: String)
}
