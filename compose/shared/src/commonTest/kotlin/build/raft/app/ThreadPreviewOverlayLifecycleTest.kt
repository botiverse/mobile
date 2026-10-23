package build.raft.app

import build.raft.app.data.realtime.RaftRealtimeNotifyContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ThreadPreviewOverlayLifecycleTest {
    private val resume = RaftRealtimeNotifyContract.LifecycleVisibleResume
    private val pause = RaftRealtimeNotifyContract.LifecycleHiddenPause

    /** The thread listener's decision, with the gate that turns a counted return into a refresh. */
    private class FakeThread {
        val filter = ThreadPreviewOverlayLifecycleFilter()
        val gate = ThreadVisibleResumeGate()
        var refreshes = 0

        fun lifecycle(event: String, page: String, epoch: Long) {
            val verdict = filter.observe(event, page, epoch)
            if (!threadLifecycleEventTargetsThread(page)) return
            if (verdict == ThreadLifecycleVisibilityVerdict.CoveredByPreview) return
            if (event == RaftRealtimeNotifyContract.LifecycleVisibleResume) {
                if (gate.onLifecycleVisibleResume(routeActive = true)?.refreshVisibleConversation == true) refreshes += 1
            }
        }
    }

    @Test
    fun openingAndClosingThePreviewDoesNotRefreshTheThread() {
        val thread = FakeThread()
        thread.lifecycle(resume, THREAD_PAGE, epoch = 4)
        assertEquals(1, thread.refreshes)
        repeat(3) {
            thread.lifecycle(pause, THREAD_PAGE, epoch = 4)
            thread.lifecycle(resume, RAFT_FILE_PREVIEW_PAGE, epoch = 4)
            thread.lifecycle(pause, RAFT_FILE_PREVIEW_PAGE, epoch = 4)
            thread.lifecycle(resume, THREAD_PAGE, epoch = 4)
        }
        assertEquals(1, thread.refreshes)
    }

    @Test
    fun appBackgroundingStillCountsAfterAPlainPause() {
        val thread = FakeThread()
        thread.lifecycle(pause, THREAD_PAGE, epoch = 4)
        thread.lifecycle(resume, THREAD_PAGE, epoch = 5)
        assertEquals(1, thread.refreshes)
    }

    @Test
    fun appBackgroundingWhilePreviewIsOpenCountsOnReturnToThread() {
        val thread = FakeThread()
        thread.lifecycle(pause, THREAD_PAGE, epoch = 4)
        thread.lifecycle(resume, RAFT_FILE_PREVIEW_PAGE, epoch = 4)
        thread.lifecycle(pause, RAFT_FILE_PREVIEW_PAGE, epoch = 4)
        // Home button and back: the foreground epoch moves.
        thread.lifecycle(resume, RAFT_FILE_PREVIEW_PAGE, epoch = 5)
        thread.lifecycle(pause, RAFT_FILE_PREVIEW_PAGE, epoch = 5)
        thread.lifecycle(resume, THREAD_PAGE, epoch = 5)
        assertEquals(1, thread.refreshes)
    }

    @Test
    fun navigatingAwayToAnotherPageAndBackCounts() {
        val thread = FakeThread()
        thread.lifecycle(pause, THREAD_PAGE, epoch = 4)
        thread.lifecycle(resume, "compose_settings", epoch = 4)
        thread.lifecycle(pause, "compose_settings", epoch = 4)
        thread.lifecycle(resume, THREAD_PAGE, epoch = 4)
        assertEquals(1, thread.refreshes)
    }

    @Test
    fun previewOpenedFromAnotherPageDoesNotSwallowTheLaterThreadReturn() {
        val thread = FakeThread()
        thread.lifecycle(pause, THREAD_PAGE, epoch = 4)
        thread.lifecycle(resume, "compose_settings", epoch = 4)
        thread.lifecycle(pause, "compose_settings", epoch = 4)
        thread.lifecycle(resume, RAFT_FILE_PREVIEW_PAGE, epoch = 4)
        thread.lifecycle(pause, RAFT_FILE_PREVIEW_PAGE, epoch = 4)
        thread.lifecycle(resume, "compose_settings", epoch = 4)
        thread.lifecycle(pause, "compose_settings", epoch = 4)
        thread.lifecycle(resume, THREAD_PAGE, epoch = 4)
        assertEquals(1, thread.refreshes)
    }

    @Test
    fun coveredReturnLeavesNoPendingResumeLatched() {
        val thread = FakeThread()
        thread.lifecycle(pause, THREAD_PAGE, epoch = 4)
        thread.lifecycle(resume, RAFT_FILE_PREVIEW_PAGE, epoch = 4)
        thread.lifecycle(resume, THREAD_PAGE, epoch = 4)
        assertNull(thread.gate.onRouteBecameVisible())
        assertEquals(0, thread.refreshes)
        assertNotNull(thread.gate.onLifecycleVisibleResume(routeActive = true))
    }
}
