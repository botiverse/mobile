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

        targetContext.startActivity(intent)

        assertTrue(
            "native IME visibility probe did not finish",
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
            "native EditText could not make the software IME visible: $result",
            Activity.RESULT_OK,
            resultCode.get(),
        )
        assertTrue(result!!.getBoolean(AndroidImeVisibilityProbeActivity.ResultImeVisible))
        assertEquals(1, result.getInt(AndroidImeVisibilityProbeActivity.ResultShowRequests))
        assertEquals(1, result.getInt(AndroidImeVisibilityProbeActivity.ResultAcceptedRequests))
        assertTrue(result.getBoolean(AndroidImeVisibilityProbeActivity.ResultEditorFocused))
        assertTrue(result.getBoolean(AndroidImeVisibilityProbeActivity.ResultWindowFocused))
        val receipt =
            "ANDROID_IME_VISIBILITY_PROBE_PASS " +
                "showRequests=${result.getInt(AndroidImeVisibilityProbeActivity.ResultShowRequests)} " +
                "acceptedRequests=${result.getInt(AndroidImeVisibilityProbeActivity.ResultAcceptedRequests)} " +
                "editorFocused=${result.getBoolean(AndroidImeVisibilityProbeActivity.ResultEditorFocused)} " +
                "windowFocused=${result.getBoolean(AndroidImeVisibilityProbeActivity.ResultWindowFocused)}"
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
