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
        assertEquals(Color.rgb(0x11, 0x22, 0x33), resolveLucidePaintColor("#123", Color.RED))
        assertEquals(Color.RED, resolveLucidePaintColor("currentColor", Color.RED))
    }

    @Test
    fun nativeSpinTracksAttachVisibilityAndDestroyLifecycle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent =
            Intent(context, SlockKuiklyActivity::class.java)
                .putExtra("pageName", "compose_lucide_state_atlas_visual")
                .putExtra("pageData", JSONObject().put("instanceId", "lucide-native-spin-test").toString())
                .putExtra(SlockKuiklyActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)

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

            val first = canvasView.rotationAfterDelay(120L)
            val second = canvasView.rotationAfterDelay(160L)
            assertTrue("Attached native spin must advance: first=$first second=$second", angularDistance(first, second) > 10f)

            scenario.onActivity { host.visibility = View.INVISIBLE }
            val hiddenFirst = canvasView.rotationAfterDelay(80L)
            val hiddenSecond = canvasView.rotationAfterDelay(160L)
            assertEquals("Hidden native spin must stop", hiddenFirst, hiddenSecond, 0.01f)

            scenario.onActivity { host.visibility = View.VISIBLE }
            val resumedFirst = canvasView.rotationAfterDelay(120L)
            val resumedSecond = canvasView.rotationAfterDelay(160L)
            assertTrue(
                "Visible native spin must resume: first=$resumedFirst second=$resumedSecond",
                angularDistance(resumedFirst, resumedSecond) > 10f,
            )

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

    private fun View.rotationAfterDelay(delayMs: Long): Float {
        SystemClock.sleep(delayMs)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        return rotation
    }

    private fun angularDistance(first: Float, second: Float): Float {
        val direct = abs(second - first) % 360f
        return minOf(direct, 360f - direct)
    }
}
