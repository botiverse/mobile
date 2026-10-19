package build.raft.app.shared

import android.content.Intent
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SlockLucideNativeCanvasConnectedTest {
    @Test
    fun cssShorthandPaintExpandsToOpaqueAndroidColor() {
        assertEquals(Color.rgb(0x11, 0x22, 0x33), parseLucideDirectPaintColor("#123"))
        assertEquals(Color.rgb(0x12, 0x34, 0x56), parseLucideDirectPaintColor("#123456"))
    }

    @Test
    fun nativeSpinTracksAttachVisibilityAndDestroyLifecycle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent =
            Intent(context, SlockKuiklyActivity::class.java)
                .putExtra("pageName", "compose_lucide_state_atlas_visual")
                .putExtra("pageData", JSONObject().put("instanceId", "lucide-native-spin-test").toString())
                .putExtra(SlockCmpActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)

        ActivityScenario.launch<SlockKuiklyActivity>(intent).use { scenario ->
            lateinit var host: SlockLucideIconRenderView
            lateinit var canvasView: View
            scenario.onActivity { activity ->
                host = SlockLucideIconRenderView(activity)
                activity.addContentView(
                    host,
                    FrameLayout.LayoutParams(96, 96),
                )
                host.setProp("name", "loader-circle")
                host.setProp("spinning", true)
                canvasView = host.getChildAt(0)
            }

            canvasView.assertRotationEventuallyAdvances("Attached native spin")

            scenario.onActivity { host.visibility = View.INVISIBLE }
            val hiddenFirst = canvasView.rotationAfterDelay(80L)
            val hiddenSecond = canvasView.rotationAfterDelay(160L)
            assertEquals("Hidden native spin must stop", hiddenFirst, hiddenSecond, 0.01f)

            scenario.onActivity { host.visibility = View.VISIBLE }
            canvasView.assertRotationEventuallyAdvances("Visible native spin")

            scenario.onActivity { host.onDestroy() }
            val destroyedFirst = canvasView.rotationAfterDelay(80L)
            val destroyedSecond = canvasView.rotationAfterDelay(160L)
            assertEquals("Destroyed native spin must stop", destroyedFirst, destroyedSecond, 0.01f)

            scenario.onActivity {
                host.visibility = View.INVISIBLE
                host.visibility = View.VISIBLE
                val parent = host.parent as ViewGroup
                parent.removeView(host)
                parent.addView(host, FrameLayout.LayoutParams(96, 96))
            }
            val reattachedFirst = canvasView.rotationAfterDelay(80L)
            val reattachedSecond = canvasView.rotationAfterDelay(160L)
            assertEquals(
                "Destroyed native spin must stay stopped after visibility and reattach callbacks",
                reattachedFirst,
                reattachedSecond,
                0.01f,
            )
        }
    }

    @Test
    fun reusableNativeSpinRestartsForEveryLazyRowBindingAfterDestroy() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent =
            Intent(context, SlockKuiklyActivity::class.java)
                .putExtra("pageName", "compose_lucide_state_atlas_visual")
                .putExtra("pageData", JSONObject().put("instanceId", "lucide-native-multi-spin-test").toString())
                .putExtra(SlockCmpActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)

        ActivityScenario.launch<SlockKuiklyActivity>(intent).use { scenario ->
            lateinit var host: SlockLucideIconRenderView
            lateinit var canvas: View
            scenario.onActivity { activity ->
                host = SlockLucideIconRenderView(activity)
                activity.addContentView(host, FrameLayout.LayoutParams(96, 96))
                host.setProp("name", "loader-circle")
                host.setProp("spinning", true)
                canvas = host.getChildAt(0)
            }

            canvas.assertRotationEventuallyAdvances("First native row binding")
            repeat(2) { generation ->
                scenario.onActivity {
                    host.onDestroy()
                    host.setProp("name", "loader-circle")
                    host.setProp("spinning", true)
                }
                canvas.assertRotationEventuallyAdvances("Reused native row binding ${generation + 2}")
            }
        }
    }

    private fun View.rotationAfterDelay(delayMs: Long): Float {
        SystemClock.sleep(delayMs)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        return rotation
    }

    private fun View.assertRotationEventuallyAdvances(label: String) {
        val initial = rotationAfterDelay(0L)
        val deadline = SystemClock.elapsedRealtime() + ROTATION_ADVANCE_TIMEOUT_MS
        var latest = initial
        while (SystemClock.elapsedRealtime() < deadline) {
            latest = rotationAfterDelay(ROTATION_POLL_INTERVAL_MS)
            if (angularDistance(initial, latest) > MINIMUM_OBSERVED_ROTATION_DEGREES) return
        }
        assertTrue("$label must advance: initial=$initial latest=$latest", false)
    }

    private fun angularDistance(first: Float, second: Float): Float {
        val direct = abs(second - first) % 360f
        return minOf(direct, 360f - direct)
    }

    companion object {
        private const val ROTATION_ADVANCE_TIMEOUT_MS = 600L
        private const val ROTATION_POLL_INTERVAL_MS = 40L
        private const val MINIMUM_OBSERVED_ROTATION_DEGREES = 1f
    }
}
