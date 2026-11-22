package build.raft.app.shared

import build.raft.app.shared.RaftBackgroundConnectionPolicy.BackgroundTransitionAction
import build.raft.app.shared.RaftBackgroundConnectionPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feedback 1af517cc (HUAWEI, no Play services): the background connection service was only ever requested
 * from activity resume, so an input that became true after the resume left the hidden app without it.
 * Leaving the foreground now re-evaluates the decision.
 */
class AndroidRealtimeKeepAliveBackgroundTransitionTest {
    private class Recorder {
        val starts = mutableListOf<String>()
        val stops = mutableListOf<String>()
        var decisionReads = 0
    }

    private fun handler(
        recorder: Recorder,
        fallbackApplies: Boolean = true,
        decision: Decision = Decision.Run,
        serviceActive: Boolean = false,
        stopQueued: Boolean = false,
    ) = AndroidRealtimeKeepAliveController.BackgroundTransitionHandler(
        fallbackApplies = { fallbackApplies },
        decision = {
            recorder.decisionReads += 1
            decision
        },
        serviceActive = { serviceActive },
        stopQueued = { stopQueued },
        requestStart = { source -> recorder.starts += source },
        requestStop = { reason -> recorder.stops += reason },
        log = {},
    )

    @Test
    fun backgroundTransitionStartsTheServiceWhenDecisionIsRunWithoutPlayServices() {
        val recorder = Recorder()
        // The wiring the app-scope realtime owner installs: last activity stopped -> background transition.
        val transition = handler(recorder)
        val tracker =
            AndroidProcessVisibilityTracker { foreground, _ ->
                if (!foreground) transition.onAppBackgrounded()
            }

        tracker.onActivityStarted()
        assertTrue(recorder.starts.isEmpty())
        tracker.onActivityStopped(changingConfigurations = false)

        assertEquals(listOf(AndroidRealtimeKeepAliveController.SOURCE_APP_BACKGROUND), recorder.starts)
        assertTrue(recorder.stops.isEmpty())
    }

    @Test
    fun configurationChangeIsNotABackgroundTransition() {
        val recorder = Recorder()
        val transition = handler(recorder)
        val tracker =
            AndroidProcessVisibilityTracker { foreground, _ ->
                if (!foreground) transition.onAppBackgrounded()
            }

        tracker.onActivityStarted()
        tracker.onActivityStopped(changingConfigurations = true)

        assertTrue(recorder.starts.isEmpty())
    }

    @Test
    fun startRequestedAgainWhenOnlyAStopIsQueued() {
        val recorder = Recorder()
        handler(recorder, serviceActive = true, stopQueued = true).onAppBackgrounded()

        assertEquals(listOf(AndroidRealtimeKeepAliveController.SOURCE_APP_BACKGROUND), recorder.starts)
    }
}
