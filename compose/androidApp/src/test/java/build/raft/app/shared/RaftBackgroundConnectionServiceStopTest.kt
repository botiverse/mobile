package build.raft.app.shared

import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RaftBackgroundConnectionServiceStopTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Before
    fun resetBefore() {
        RaftBackgroundConnectionService.resetForTests()
    }

    @After
    fun resetAfter() {
        RaftBackgroundConnectionService.resetForTests()
    }

    @Test
    fun stopQueuesStopActionWithoutStopServiceWhenStartPending() {
        RaftBackgroundConnectionService.start(app, "activity_on_resume")
        val started = shadowOf(app).nextStartedService
        assertEquals(RaftBackgroundConnectionService::class.java.name, started?.component?.className)
        assertTrue(RaftBackgroundConnectionService.startPending)

        RaftBackgroundConnectionService.stop(app, "preference_disabled")

        assertTrue(RaftBackgroundConnectionService.stopQueued)
        assertNull("must not call stopService while startForegroundService is pending", shadowOf(app).nextStoppedService)
        val stopActionIntent = shadowOf(app).nextStartedService
        assertEquals("build.raft.app.action.STOP_BACKGROUND_CONNECTION", stopActionIntent?.action)
    }
}
