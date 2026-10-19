package build.raft.app.shared

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.view.PixelCopy
import android.view.Window
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import build.raft.app.SLOCK_CHILD_PAGE_VISIBILITY_ACTIVE_PAGE
import build.raft.app.SLOCK_CHILD_PAGE_VISIBILITY_PROBE_PAGE
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlockChildPageVisibilityConnectedTest {
    @Test
    fun probeReadinessRequiresASingleDominantProbeColor() {
        assertFalse(childPageProbeReady(redRatio = 0.49f, blueRatio = 0.02f))
        assertFalse(childPageProbeReady(redRatio = 0.3f, blueRatio = 0.3f))
        assertFalse(childPageProbeReady(redRatio = 0.0f, blueRatio = 0.0f))
        assertTrue(childPageProbeReady(redRatio = 0.51f, blueRatio = 0.0f))
        assertTrue(childPageProbeReady(redRatio = 0.0f, blueRatio = 0.51f))

        val loaded = SlockKuiklyLoadReadiness(succeeded = true, completedAtMillis = 3)
        val ready =
            SlockKuiklyRenderReadinessSnapshot(
                createdAtMillis = 1,
                resumedAtMillis = 2,
                topPageLoad = loaded,
                childPageLoad = loaded,
                hasWindowFocus = true,
                contentWidth = 1080,
                contentHeight = 1920
            )
        assertTrue(childPageHostReady(ready))
        assertFalse(childPageHostReady(ready.copy(topPageLoad = null)))
        assertFalse(childPageHostReady(ready.copy(childPageLoad = null)))
        assertFalse(childPageHostReady(ready.copy(hasWindowFocus = false)))
        assertFalse(childPageHostReady(ready.copy(contentWidth = 0)))
    }

    @Test
    fun inactiveChildPageDoesNotPaintOverActiveChildPage() {
        val bitmap = captureProbe()
        val redRatio = bitmap.matchingPixelRatio { red, green, blue ->
            red > 220 && green < 60 && blue < 60
        }
        val blueRatio = bitmap.matchingPixelRatio { red, green, blue ->
            red < 60 && green < 60 && blue > 220
        }
        saveAcceptedProbeFrame(bitmap)
        bitmap.recycle()

        println("SlockChildPageVisibilityProbe redRatio=$redRatio blueRatio=$blueRatio")
        assertTrue(
            "Inactive child page painted over the active page (redRatio=$redRatio blueRatio=$blueRatio)",
            redRatio > 0.5f && blueRatio < 0.1f
        )
    }

    private fun captureProbe(): Bitmap {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // This class runs late in a serialized device job and can cross the
        // emulator's screen timeout before its first screenshot.
        ensureInteractiveDisplay(instrumentation)
        val context = instrumentation.targetContext
        val intent =
            Intent(context, SlockCmpActivity::class.java)
                .putExtra("pageName", SLOCK_CHILD_PAGE_VISIBILITY_PROBE_PAGE)
                .putExtra("pageData", "{}")
        var bitmap: Bitmap? = null
        val powerManager = instrumentation.targetContext.getSystemService(PowerManager::class.java)
        val launchedAt = SystemClock.elapsedRealtime()
        ActivityScenario.launch<SlockCmpActivity>(intent).use { scenario ->
            instrumentation.waitForIdleSync()
            // Use the host's production load callbacks as the readiness barrier.
            // A display screenshot alone cannot tell a cold Kuikly init from a
            // stale/system surface, which made the old 0/0 poll ambiguous.
            val deadline = launchedAt + 30_000
            var lastSnapshot: SlockKuiklyRenderReadinessSnapshot? = null
            var loggedResumed = false
            var loggedTopPage = false
            var loggedActiveChild = false
            while (true) {
                if (!powerManager.isInteractive) {
                    instrumentation.shell("input keyevent KEYCODE_WAKEUP")
                }

                val window = AtomicReference<Window>()
                scenario.onActivity { activity ->
                    lastSnapshot = activity.renderReadinessSnapshot(SLOCK_CHILD_PAGE_VISIBILITY_ACTIVE_PAGE)
                    window.set(activity.window)
                }
                val snapshot = requireNotNull(lastSnapshot)
                if (!loggedResumed && snapshot.resumedAtMillis != null) {
                    loggedResumed = true
                    println("CHILD_PAGE_READINESS stage=resumed elapsedMs=${snapshot.resumedAtMillis - launchedAt}")
                }
                snapshot.topPageLoad?.let { load ->
                    check(load.succeeded) { "Child page visibility top-level probe failed to load: $snapshot" }
                    if (!loggedTopPage) {
                        loggedTopPage = true
                        println("CHILD_PAGE_READINESS stage=top_page_load elapsedMs=${load.completedAtMillis - launchedAt}")
                    }
                }
                snapshot.childPageLoad?.let { load ->
                    check(load.succeeded) { "Child page visibility active child failed to load: $snapshot" }
                    if (!loggedActiveChild) {
                        loggedActiveChild = true
                        println("CHILD_PAGE_READINESS stage=active_child_load elapsedMs=${load.completedAtMillis - launchedAt}")
                    }
                }

                if (childPageHostReady(snapshot)) {
                    val candidate = captureWindow(requireNotNull(window.get()))
                    val redRatio = candidate.matchingPixelRatio { red, green, blue ->
                        red > 220 && green < 60 && blue < 60
                    }
                    val blueRatio = candidate.matchingPixelRatio { red, green, blue ->
                        red < 60 && green < 60 && blue > 220
                    }
                    if (childPageProbeReady(redRatio, blueRatio)) {
                        println(
                            "CHILD_PAGE_READINESS stage=dominant_frame " +
                                "elapsedMs=${SystemClock.elapsedRealtime() - launchedAt} " +
                                "redRatio=$redRatio blueRatio=$blueRatio"
                        )
                        bitmap = candidate
                        break
                    }
                    candidate.recycle()
                }
                if (SystemClock.elapsedRealtime() >= deadline) break
                SystemClock.sleep(250)
            }

            if (bitmap == null) {
                val interactive = powerManager.isInteractive
                val stayOn = instrumentation.shell("settings get global stay_on_while_plugged_in").trim()
                val wakefulness = instrumentation.shell("dumpsys power")
                    .lineSequence().firstOrNull { it.contains("mWakefulness=") }?.trim()
                    ?: "mWakefulness=unknown"
                error(
                    "Child page visibility probe never reached its callback-backed dominant frame " +
                        "within 30s (snapshot=$lastSnapshot isInteractive=$interactive " +
                        "stayOnWhilePluggedIn=$stayOn $wakefulness)"
                )
            }
        }
        return requireNotNull(bitmap) { "Failed to capture child page visibility probe screenshot" }
    }

    private fun ensureInteractiveDisplay(instrumentation: Instrumentation) {
        val powerManager = instrumentation.targetContext.getSystemService(PowerManager::class.java)
        if (!powerManager.isInteractive) {
            instrumentation.shell("input keyevent KEYCODE_WAKEUP")
        }
        instrumentation.shell("wm dismiss-keyguard")

        val deadline = SystemClock.uptimeMillis() + 5_000
        while (!powerManager.isInteractive && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(100)
        }
        check(powerManager.isInteractive) { "Child page visibility probe display did not wake within 5s" }
        instrumentation.waitForIdleSync()
    }

    private fun Instrumentation.shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command)).use { input ->
            input.bufferedReader().readText()
        }

    private fun captureWindow(window: Window): Bitmap {
        val width = window.decorView.width
        val height = window.decorView.height
        check(width > 0 && height > 0) { "Child page visibility window has invalid bounds ${width}x$height" }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val result = AtomicReference<Int>()
        val completed = CountDownLatch(1)
        PixelCopy.request(
            window,
            bitmap,
            { copyResult ->
                result.set(copyResult)
                completed.countDown()
            },
            Handler(Looper.getMainLooper())
        )
        check(completed.await(5, TimeUnit.SECONDS)) {
            "Timed out waiting for child page visibility PixelCopy"
        }
        check(result.get() == PixelCopy.SUCCESS) {
            bitmap.recycle()
            "Child page visibility PixelCopy failed with result=${result.get()}"
        }
        return bitmap
    }

    private fun saveAcceptedProbeFrame(bitmap: Bitmap) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.openFileOutput(ACCEPTED_PROBE_FRAME_FILE, android.content.Context.MODE_PRIVATE).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "Failed to persist accepted child page visibility probe frame"
            }
        }
    }

    private fun Bitmap.matchingPixelRatio(matches: (red: Int, green: Int, blue: Int) -> Boolean): Float {
        val row = IntArray(width)
        var matched = 0
        var total = 0
        val left = (width * 0.15f).toInt()
        val right = (width * 0.85f).toInt()
        val top = (height * 0.15f).toInt()
        val bottom = (height * 0.85f).toInt()
        for (y in top until bottom) {
            getPixels(row, 0, width, 0, y, width, 1)
            for (x in left until right) {
                val pixel = row[x]
                val red = (pixel shr 16) and 0xFF
                val green = (pixel shr 8) and 0xFF
                val blue = pixel and 0xFF
                if (matches(red, green, blue)) {
                    matched++
                }
                total++
            }
        }
        return if (total == 0) 0f else matched.toFloat() / total.toFloat()
    }
}

internal fun childPageProbeReady(redRatio: Float, blueRatio: Float): Boolean =
    redRatio > 0.5f || blueRatio > 0.5f

internal fun childPageHostReady(snapshot: SlockKuiklyRenderReadinessSnapshot): Boolean =
    snapshot.resumedAtMillis != null &&
        snapshot.topPageLoad?.succeeded == true &&
        snapshot.childPageLoad?.succeeded == true &&
        snapshot.hasWindowFocus &&
        snapshot.contentWidth > 0 &&
        snapshot.contentHeight > 0

private const val ACCEPTED_PROBE_FRAME_FILE = "child-page-visibility-accepted.png"
