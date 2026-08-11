package build.raft.app.shared

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.ResultReceiver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidImeVisibilityProbeConnectedTest {
    @Test
    fun nativeEditTextCanShowSoftwareIme() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val completed = CountDownLatch(1)
        val resultCode = AtomicInteger(Int.MIN_VALUE)
        val resultData = AtomicReference<Bundle?>()
        val receiver =
            object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(code: Int, data: Bundle?) {
                    resultCode.set(code)
                    resultData.set(data)
                    completed.countDown()
                }
            }
        val intent =
            Intent().apply {
                component =
                    ComponentName(
                        targetContext.packageName,
                        AndroidImeVisibilityProbeActivity::class.java.name,
                    )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(AndroidImeVisibilityProbeActivity.ExtraResultReceiver, receiver)
            }

        // A leaked keyguard or a launcher ANR dialog owns input focus on CI
        // emulators often enough to turn this probe red without the product
        // being involved: the probe activity then reports windowFocused=false
        // and the IME never accepts the show request. Recover the active window
        // first (bounded; never throws) and carry what it saw into the failure
        // messages so an emulator-state red cannot read as a product red.
        val preLaunchRecovery =
            AndroidActiveWindowRecovery.awaitActiveWindow(targetContext.packageName)

        targetContext.startActivity(intent)

        val probeRecovery =
            AndroidActiveWindowRecovery.awaitActiveWindow(targetContext.packageName)
        val windowState =
            "preLaunch[${preLaunchRecovery.describe()}] probe[${probeRecovery.describe()}]"

        assertTrue(
            "native IME visibility probe did not finish; $windowState",
            completed.await(ProbeResultTimeoutSeconds, TimeUnit.SECONDS),
        )
        val result = resultData.get()
        assertNotNull("native IME visibility probe returned no diagnostics", result)
        if (resultCode.get() != Activity.RESULT_OK) {
            val failureReceipt = captureFailureReceipt(instrumentation, result!!)
            instrumentation.sendStatus(
                0,
                Bundle().apply { putString("imeVisibilityProbeFailure", failureReceipt) },
            )
            println(failureReceipt)
        }
        assertEquals(
            "native EditText could not make the software IME visible: $result; $windowState",
            Activity.RESULT_OK,
            resultCode.get(),
        )
        assertTrue(
            "software IME never became visible; $windowState",
            result!!.getBoolean(AndroidImeVisibilityProbeActivity.ResultImeVisible)
        )
        assertEquals(
            "unexpected IME show-request count; $windowState",
            1,
            result.getInt(AndroidImeVisibilityProbeActivity.ResultShowRequests)
        )
        assertEquals(
            "unexpected IME accepted-request count; $windowState",
            1,
            result.getInt(AndroidImeVisibilityProbeActivity.ResultAcceptedRequests)
        )
        assertTrue(
            "probe editor never took focus; $windowState",
            result.getBoolean(AndroidImeVisibilityProbeActivity.ResultEditorFocused)
        )
        assertTrue(
            "probe window never took focus (emulator focus theft, not product); $windowState",
            result.getBoolean(AndroidImeVisibilityProbeActivity.ResultWindowFocused)
        )
        val receipt =
            "ANDROID_IME_VISIBILITY_PROBE_PASS " +
                "showRequests=${result.getInt(AndroidImeVisibilityProbeActivity.ResultShowRequests)} " +
                "acceptedRequests=${result.getInt(AndroidImeVisibilityProbeActivity.ResultAcceptedRequests)} " +
                "editorFocused=${result.getBoolean(AndroidImeVisibilityProbeActivity.ResultEditorFocused)} " +
                "windowFocused=${result.getBoolean(AndroidImeVisibilityProbeActivity.ResultWindowFocused)} " +
                // Recovery facts ride the PASS receipt too: a green that needed
                // a force-stop is still green, but the emulator was unhealthy
                // and that trend is only visible if we record it when passing.
                "recovery=$windowState"
        instrumentation.sendStatus(
            0,
            Bundle().apply { putString("imeVisibilityProbe", receipt) },
        )
        println(receipt)
    }

    private companion object {
        const val ProbeResultTimeoutSeconds = 20L

        private val WindowNeedles =
            listOf(
                "mCurrentFocus=",
                "mFocusedApp=",
                "mObscuringWindow=",
                "mHoldScreenWindow=",
                "mShowingDream=",
                "mDreamingLockscreen=",
                "isKeyguardShowing=",
            )
        private val ActivityNeedles =
            listOf(
                "mResumedActivity:",
                "topResumedActivity=",
                "mFocusedRootTask=",
                "ResumedActivity:",
            )
        private val PowerNeedles =
            listOf(
                "mWakefulness=",
                "mIsPowered=",
                "mStayOn=",
                "Display Power: state=",
            )
        private val PolicyNeedles =
            listOf(
                "mShowingLockscreen=",
                "mShowingDream=",
                "mDreamingLockscreen=",
                "mKeyguardOccluded=",
                "mIsShowing=",
                "showing=",
                "dreaming=",
            )
    }

    private fun captureFailureReceipt(
        instrumentation: Instrumentation,
        result: Bundle,
    ): String =
        "ANDROID_IME_VISIBILITY_PROBE_FAILURE " +
            "showRequests=${result.getInt(AndroidImeVisibilityProbeActivity.ResultShowRequests)} " +
            "acceptedRequests=${result.getInt(AndroidImeVisibilityProbeActivity.ResultAcceptedRequests)} " +
            "editorFocused=${result.getBoolean(AndroidImeVisibilityProbeActivity.ResultEditorFocused)} " +
            "windowFocused=${result.getBoolean(AndroidImeVisibilityProbeActivity.ResultWindowFocused)} " +
            "window=${shellSummary(instrumentation, "dumpsys window", WindowNeedles)} " +
            "activity=${shellSummary(instrumentation, "dumpsys activity activities", ActivityNeedles)} " +
            "power=${shellSummary(instrumentation, "dumpsys power", PowerNeedles)} " +
            "policy=${shellSummary(instrumentation, "dumpsys window policy", PolicyNeedles)}"

    private fun shellSummary(
        instrumentation: Instrumentation,
        command: String,
        needles: List<String>,
    ): String =
        try {
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(command),
            ).bufferedReader().use { input ->
                input.lineSequence()
                    .map(String::trim)
                    .filter { line -> needles.any(line::contains) }
                    .take(12)
                    .joinToString(" || ")
                    .ifEmpty { "none" }
            }
        } catch (failure: Throwable) {
            "capture_error=${failure::class.java.simpleName}:${failure.message}"
        }
}
