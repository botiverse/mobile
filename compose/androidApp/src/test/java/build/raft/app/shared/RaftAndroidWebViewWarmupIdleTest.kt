package build.raft.app.shared

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cold-start guard: the production dispatch of the WebView warm-up must wait for the main
 * looper to go idle. Posted from `Application.onCreate` it used to run ahead of the launch
 * activity (300 ms+ interpreted, over a second on a loaded two-core emulator) and its renderer
 * process competed with the first composition, the block behind the alpha cold-start ANR.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RaftAndroidWebViewWarmupIdleTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val constructions = AtomicInteger(0)

    @Before
    fun setUp() {
        RaftAndroidWebViewWarmup.resetForTest()
        RaftAndroidWebViewWarmup.log = { }
        RaftAndroidWebViewWarmup.createWarmInstance = {
            constructions.incrementAndGet()
            Any()
        }
    }

    @After
    fun tearDown() {
        RaftAndroidWebViewWarmup.resetForTest()
    }

    @Test
    fun warmupWaitsForQueuedMainThreadWorkBeforeConstructing() {
        val mainLooper = shadowOf(Looper.getMainLooper())
        var constructionsSeenByLaterWork = -1

        RaftAndroidWebViewWarmup.schedule(context)
        // Work that arrives after scheduling (the launch transaction, the first frame) still wins:
        // a plain post would be FIFO and construct first.
        Handler(Looper.getMainLooper()).post { constructionsSeenByLaterWork = constructions.get() }

        assertEquals("schedule must not construct synchronously", 0, constructions.get())
        mainLooper.idle()
        assertEquals("main-thread work queued after schedule runs before the warm-up", 0, constructionsSeenByLaterWork)
        assertEquals("the warm-up runs once the main looper is idle", 1, constructions.get())

        RaftAndroidWebViewWarmup.schedule(context)
        mainLooper.idle()
        assertEquals("still single shot", 1, constructions.get())
    }
}
