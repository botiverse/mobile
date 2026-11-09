package build.raft.app.nav

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * nav_transition_frames sessions: one start and one end per push, pop and predictive back gesture,
 * page names only (RaftStackTransitionTracker, fed by RaftMobilePageStackDisplay).
 */
class RaftStackTransitionTrackerTest {
    private class Recorder : RaftStackTransitionListener {
        val events = mutableListOf<String>()

        override fun onTransitionStarted(kind: RaftStackTransitionKind, fromPage: String, toPage: String) {
            events += "start ${kind.wire} $fromPage>$toPage"
        }

        override fun onTransitionEnded(outcome: RaftStackTransitionOutcome) {
            events += "end ${outcome.wire}"
        }
    }

    private val recorder = Recorder()
    private val tracker = RaftStackTransitionTracker(recorder).apply { onStack(listOf(SHELL)) }

    @Test fun `a push and a pop each settle as completed with page names only`() {
        tracker.onStack(listOf(SHELL, ABOUT))
        tracker.onBackPreview(SHELL) // the covered shell scene leaving: not a gesture
        tracker.onSettled(ABOUT)
        tracker.onStack(listOf(SHELL))
        tracker.onSettled(SHELL)
        assertEquals(
            listOf(
                "start push compose_slock_app_shell>compose_settings_native_detail",
                "end completed",
                "start pop compose_settings_native_detail>compose_slock_app_shell",
                "end completed",
            ),
            recorder.events,
        )
    }

    @Test fun `a predictive back gesture let go on its page is cancelled`() {
        tracker.onStack(listOf(SHELL, ABOUT))
        tracker.onSettled(ABOUT)
        recorder.events.clear()
        tracker.onBackPreview(ABOUT)
        tracker.onBackPreview(ABOUT) // the same preview again: still one gesture
        tracker.onSettled(ABOUT)
        assertEquals(listOf("start predictive_back compose_settings_native_detail>compose_slock_app_shell", "end cancelled"), recorder.events)
    }

    @Test fun `a predictive back gesture released ends when the page below settles`() {
        tracker.onStack(listOf(SHELL, ABOUT))
        tracker.onSettled(ABOUT)
        recorder.events.clear()
        tracker.onBackPreview(ABOUT)
        tracker.onStack(listOf(SHELL)) // the gesture completed: the stack popped
        assertEquals(listOf("start predictive_back compose_settings_native_detail>compose_slock_app_shell"), recorder.events)
        tracker.onSettled(SHELL)
        assertEquals("end completed", recorder.events.last())
        assertEquals(2, recorder.events.size)
    }

    @Test fun `a change before the motion settles interrupts the open transition`() {
        tracker.onStack(listOf(SHELL, ABOUT))
        tracker.onStack(listOf(SHELL, ABOUT, CHAT))
        tracker.onSettled(ABOUT) // a stale settle of a page no longer on top: ignored
        tracker.onSettled(CHAT)
        assertEquals(
            listOf(
                "start push compose_slock_app_shell>compose_settings_native_detail",
                "end interrupted",
                "start push compose_settings_native_detail>compose_thread",
                "end completed",
            ),
            recorder.events,
        )
    }

    @Test fun `the root page alone or an unchanged top page starts nothing`() {
        tracker.onBackPreview(SHELL) // the root page alone: nothing to go back to
        tracker.onSettled(SHELL)
        assertEquals(emptyList<String>(), recorder.events)
        tracker.onStack(listOf(SHELL, CHAT, ABOUT))
        tracker.onSettled(ABOUT)
        recorder.events.clear()
        // The page under the top one closed (the top unchanged): no motion on screen.
        tracker.onStack(listOf(SHELL, ABOUT))
        assertEquals(emptyList<String>(), recorder.events)
    }

    private companion object {
        const val SHELL = "compose_slock_app_shell#0"
        const val ABOUT = "compose_settings_native_detail#1"
        const val CHAT = "compose_thread#2"
    }
}
