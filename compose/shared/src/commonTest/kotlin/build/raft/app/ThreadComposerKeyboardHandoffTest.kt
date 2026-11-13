package build.raft.app

import build.raft.app.ThreadComposerKeyboardHandoff.Phase
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * dm #119: [ThreadComposerKeyboardHandoff]'s decisions, with recording fakes for putting the
 * keyboard down, bringing it back and the page's resumed state. The composition side (focus,
 * the stack page's lifecycle) is ThreadComposerKeyboardHandoffUiTest's.
 */
class ThreadComposerKeyboardHandoffTest {
    private class Recorder {
        val calls = mutableListOf<String>()
        var resumed = true
        val handoff =
            ThreadComposerKeyboardHandoff(
                putKeyboardDown = { calls += "down" },
                bringKeyboardBack = { calls += "back" },
                pageResumed = { resumed },
            )
    }

    @Test
    fun aCancelledPickerBringsTheKeyboardBackAtOnce() {
        val r = Recorder()
        r.handoff.leave(keyboardOpen = true)
        assertEquals(listOf("down"), r.calls)
        // The picker's own window pauses and resumes the page: still leaving.
        r.handoff.onPageLifecycle(resumed = false)
        r.handoff.onPageLifecycle(resumed = true)
        assertEquals(listOf("down"), r.calls, "the picker's return is not the way back")

        r.handoff.settle()
        assertEquals(listOf("down", "back"), r.calls)
        assertEquals(Phase.Idle, r.handoff.phase)
        r.handoff.settle()
        assertEquals(listOf("down", "back"), r.calls, "settled twice")
    }

    @Test
    fun theEditorHoldsTheKeyboardUntilThePageIsResumedAgain() {
        val r = Recorder()
        r.handoff.leave(keyboardOpen = true)
        r.handoff.pageOpened()
        r.handoff.settle()
        assertEquals(Phase.Covered, r.handoff.phase)
        r.handoff.onPageLifecycle(resumed = true)
        assertEquals(listOf("down"), r.calls, "back before the page went under the editor")

        r.handoff.onPageLifecycle(resumed = false)
        r.handoff.onPageLifecycle(resumed = true)
        assertEquals(listOf("down", "back"), r.calls)
        r.handoff.onPageLifecycle(resumed = false)
        r.handoff.onPageLifecycle(resumed = true)
        assertEquals(listOf("down", "back"), r.calls, "back again on a later resume")
    }

    @Test
    fun anEditorOpenedWhileThePageIsPausedWaitsForItsResume() {
        val r = Recorder()
        r.handoff.leave(keyboardOpen = true)
        r.resumed = false
        r.handoff.pageOpened()
        assertEquals(Phase.Away, r.handoff.phase)
        r.handoff.onPageLifecycle(resumed = true)
        assertEquals(listOf("down", "back"), r.calls)
    }

    @Test
    fun aClosedKeyboardTakesNoTrip() {
        val r = Recorder()
        r.handoff.leave(keyboardOpen = false)
        r.handoff.pageOpened()
        r.handoff.settle()
        r.handoff.onPageLifecycle(resumed = false)
        r.handoff.onPageLifecycle(resumed = true)
        assertEquals(emptyList(), r.calls)
        assertEquals(Phase.Idle, r.handoff.phase)
    }
}
