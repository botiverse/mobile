package build.raft.app.shared

import android.content.Intent
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidAdaptivePaneHostConnectedTest {
    @Test
    fun halfOpenedWindowInfoFlowsThroughTheRunningHostEmitter() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val halfOpenedEvent = AtomicReference<Pair<String, Map<String, Any>>>()
        val halfOpenedLatch = CountDownLatch(1)
        val tracker =
            FixedWindowInfoTracker(
                WindowLayoutInfo(
                    listOf(TestFoldingFeature(state = FoldingFeature.State.HALF_OPENED))
                )
            )
        val intent =
            Intent(targetContext, SlockCmpActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("pageName", SlockCmpActivity.PAGE_LOGIN)
                .putExtra(SlockCmpActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)

        val activity = instrumentation.startActivitySync(intent) as SlockCmpActivity
        var host: AndroidAdaptivePaneHost? = null
        try {
            instrumentation.runOnMainSync {
                host =
                    AndroidAdaptivePaneHost(
                        activity = activity,
                        sendEvent = { event, data ->
                            if (data["posture"] == "half_folded") {
                                halfOpenedEvent.set(event to data)
                                halfOpenedLatch.countDown()
                            }
                        },
                        windowInfoTracker = tracker,
                    ).also(AndroidAdaptivePaneHost::start)
            }

            assertTrue(
                "Timed out waiting for HALF_OPENED WindowLayoutInfo to reach the host emitter; " +
                    "events=${halfOpenedEvent.get()}",
                halfOpenedLatch.await(5, TimeUnit.SECONDS),
            )
            val captured = halfOpenedEvent.get()
            assertNotNull(captured)
            val (event, data) = requireNotNull(captured)
            assertEquals(AndroidAdaptivePanePublisher.EventName, event)
            assertEquals("half_folded", data["posture"])
            assertTrue("Expected positive window width, data=$data", (data["widthPx"] as Int) > 0)
            assertTrue("Expected positive window height, data=$data", (data["heightPx"] as Int) > 0)
            assertTrue("Expected positive density, data=$data", (data["density"] as Float) > 0f)
            assertEquals(setOf("widthPx", "heightPx", "density", "posture"), data.keys)
        } finally {
            instrumentation.runOnMainSync {
                host?.stop()
                activity.finish()
            }
            instrumentation.waitForIdleSync()
        }
    }
}

private class FixedWindowInfoTracker(
    private val layoutInfo: WindowLayoutInfo,
) : WindowInfoTracker {
    override fun windowLayoutInfo(activity: android.app.Activity): Flow<WindowLayoutInfo> =
        flowOf(layoutInfo)
}

private class TestFoldingFeature(
    override val state: FoldingFeature.State,
) : FoldingFeature {
    override val bounds: Rect = Rect(0, 900, 2208, 920)
    override val isSeparating: Boolean = true
    override val occlusionType: FoldingFeature.OcclusionType = FoldingFeature.OcclusionType.FULL
    override val orientation: FoldingFeature.Orientation = FoldingFeature.Orientation.HORIZONTAL
}
