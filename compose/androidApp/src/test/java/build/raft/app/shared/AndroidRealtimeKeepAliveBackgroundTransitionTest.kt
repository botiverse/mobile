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
    fun playServicesDeviceIsUntouched() {
        val recorder = Recorder()
        handler(recorder, fallbackApplies = false, decision = Decision.Run).onAppBackgrounded()
        handler(recorder, fallbackApplies = false, decision = Decision.Stop("play_services_available"), serviceActive = true)
            .onAppBackgrounded()

        assertTrue(recorder.starts.isEmpty())
        assertTrue(recorder.stops.isEmpty())
        assertEquals(0, recorder.decisionReads)
    }

    @Test
    fun alreadyRunningOrStartingServiceIsNotRequestedTwice() {
        val recorder = Recorder()
        handler(recorder, serviceActive = true).onAppBackgrounded()

        assertTrue(recorder.starts.isEmpty())
        assertTrue(recorder.stops.isEmpty())
    }

    @Test
    fun startRequestedAgainWhenOnlyAStopIsQueued() {
        val recorder = Recorder()
        handler(recorder, serviceActive = true, stopQueued = true).onAppBackgrounded()

        assertEquals(listOf(AndroidRealtimeKeepAliveController.SOURCE_APP_BACKGROUND), recorder.starts)
    }

    @Test
    fun stopDecisionStopsARunningServiceAndIgnoresAStoppedOne() {
        val running = Recorder()
        handler(running, decision = Decision.Stop("signed_out"), serviceActive = true).onAppBackgrounded()
        assertEquals(listOf("signed_out"), running.stops)
        assertTrue(running.starts.isEmpty())

        val stopped = Recorder()
        handler(stopped, decision = Decision.Stop("system_notifications_blocked")).onAppBackgrounded()
        assertTrue(stopped.stops.isEmpty())
        assertTrue(stopped.starts.isEmpty())
    }

    @Test
    fun policyTable() {
        fun action(fallback: Boolean, decision: Decision, active: Boolean, queued: Boolean) =
            RaftBackgroundConnectionPolicy.backgroundTransitionAction(fallback, decision, active, queued)

        assertEquals(BackgroundTransitionAction.RequestStart, action(true, Decision.Run, active = false, queued = false))
        assertEquals(BackgroundTransitionAction.RequestStart, action(true, Decision.Run, active = true, queued = true))
        assertEquals(BackgroundTransitionAction.None, action(true, Decision.Run, active = true, queued = false))
        assertEquals(BackgroundTransitionAction.None, action(false, Decision.Run, active = false, queued = false))
        assertEquals(
            BackgroundTransitionAction.None,
            action(false, Decision.Stop("play_services_available"), active = true, queued = false)
        )
        assertEquals(
            BackgroundTransitionAction.Stop("preference_disabled"),
            action(true, Decision.Stop("preference_disabled"), active = true, queued = false)
        )
        assertEquals(
            BackgroundTransitionAction.None,
            action(true, Decision.Stop("preference_disabled"), active = false, queued = false)
        )
    }
}
