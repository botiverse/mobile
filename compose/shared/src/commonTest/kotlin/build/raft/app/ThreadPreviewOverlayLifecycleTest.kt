package build.raft.app

import build.raft.app.data.realtime.RaftRealtimeNotifyContract
import build.raft.app.thread.THREAD_PAGE
import build.raft.app.thread.ThreadLifecycleVisibilityVerdict
import build.raft.app.thread.ThreadPreviewOverlayLifecycleFilter
import build.raft.app.thread.ThreadVisibleResumeGate
import build.raft.app.thread.threadLifecycleEventTargetsThread
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun threadConnectedForegroundWithin60sSkipsUntilSocketDropsOrMinutePasses() {
        var now = 5_000L
        var liveToken: Long? = 1L
        val gate =
            ThreadVisibleResumeGate(
                nowMillis = { now },
                liveConnectionToken = { liveToken },
            )
        gate.recordFreshCheck("server-1")
        now += 20_000L
        assertEquals(
            null,
            gate.onLifecycleVisibleResume(routeActive = true, serverId = "server-1"),
            "unbroken socket within 60 s skips redundant thread foreground refresh",
        )
        // Socket drops and reconnects: immediately refreshes inside 60 s.
        now += 10_000L
        liveToken = 2L
        assertEquals(
            true,
            gate.onLifecycleVisibleResume(routeActive = true, serverId = "server-1")?.refreshVisibleConversation,
            "socket drop inside 60 s refreshes visible thread on foreground",
        )
        // After 60 s on the same live token, foreground refreshes again.
        now += 60_000L
        assertEquals(
            true,
            gate.onLifecycleVisibleResume(routeActive = true, serverId = "server-1")?.refreshVisibleConversation,
            "foreground refreshes visible thread again after 60 s",
        )
    }
}
