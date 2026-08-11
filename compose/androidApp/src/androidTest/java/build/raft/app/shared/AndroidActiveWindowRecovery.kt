package build.raft.app.shared

import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Bounded recovery of the active input window for connected tests (task #236).
 *
 * CI emulators leak state that steals input focus from the test activity, and
 * the theft is invisible to the product assertion that trips over it: a
 * launcher ANR dialog ("Application Not Responding: ...nexuslauncher") or a
 * keyguard after the display sleeps holds focus, so a freshly launched probe
 * activity reports `windowFocused=false` and the IME never accepts a show
 * request. The product code is fine; the emulator simply never handed us the
 * window.
 *
 * The visual capture harness solved this for its own gestures
 * (KmpVisualScreenshotCaptureTest.awaitFixtureIsActiveInputWindow, PRs #1755 /
 * #1760). This object is the same bounded strategy factored for connected
 * tests that do not run through that harness:
 *
 *  1. wake + dismiss keyguard when the display is asleep,
 *  2. BACK to dismiss a focused system dialog, poll again,
 *  3. after [FORCE_STOP_AFTER_DISMISSES] failed BACKs, force-stop the hung
 *     system app that owns the dialog (never the test or target package) so
 *     its window rebuilds — a launcher ANR once survived 39 BACK presses,
 *  4. give up at the deadline and return what was observed.
 *
 * Recovery is best-effort and never throws: callers decide whether a failure
 * to acquire the window is fatal. [Result.describe] is meant to be attached to
 * that decision so a red names the emulator state instead of the product.
 */
internal object AndroidActiveWindowRecovery {
    private const val TAG = "SlockWindowRecovery"
    private const val DEFAULT_TIMEOUT_MS = 10_000L
    private const val POLL_MS = 250L
    private const val FORCE_STOP_AFTER_DISMISSES = 8
    private const val SYSTEM_WINDOW_PACKAGE = "android"

    private val ANR_MARKER = Regex("""Application (?:Not Responding|Error): ([A-Za-z0-9_.]+)""")

    /** What recovery observed. [acquired] false means the window never became ours. */
    internal data class Result(
        val acquired: Boolean,
        val finalActivePackage: String?,
        val wokeDisplay: Boolean,
        val dismissedSystemDialogs: Int,
        val forceStoppedPackage: String?,
        val elapsedMs: Long
    ) {
        fun describe(): String =
            "activeWindow=${finalActivePackage ?: "unknown"} acquired=$acquired " +
                "wokeDisplay=$wokeDisplay dismissedSystemDialogs=$dismissedSystemDialogs " +
                "forceStopped=${forceStoppedPackage ?: "none"} elapsedMs=$elapsedMs"
    }

    /**
     * Poll until [expectedPackage] owns the active input window, recovering from
     * keyguard and focus-stealing system dialogs along the way.
     */
    @JvmStatic
    fun awaitActiveWindow(
        expectedPackage: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): Result {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val powerManager =
            instrumentation.targetContext.getSystemService(PowerManager::class.java)
        val started = SystemClock.uptimeMillis()
        val deadline = started + timeoutMs
        var activePackage: String? = null
        var wokeDisplay = false
        var dismissed = 0
        var forceStoppedPackage: String? = null

        while (SystemClock.uptimeMillis() < deadline) {
            if (powerManager?.isInteractive == false) {
                shell("input keyevent KEYCODE_WAKEUP")
                shell("wm dismiss-keyguard")
                wokeDisplay = true
            }
            activePackage = try {
                instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()
            } catch (t: Throwable) {
                null
            }
            if (activePackage == expectedPackage) {
                return Result(
                    acquired = true,
                    finalActivePackage = activePackage,
                    wokeDisplay = wokeDisplay,
                    dismissedSystemDialogs = dismissed,
                    forceStoppedPackage = forceStoppedPackage,
                    elapsedMs = SystemClock.uptimeMillis() - started
                )
            }
            if (activePackage == SYSTEM_WINDOW_PACKAGE) {
                // BACK targets the focused system window, never the backgrounded
                // test activity, so this cannot navigate the app under test.
                shell("input keyevent KEYCODE_BACK")
                dismissed++
                if (dismissed == FORCE_STOP_AFTER_DISMISSES && forceStoppedPackage == null) {
                    val offender = hungSystemAppPackage()
                    if (offender != null && !offender.startsWith("build.raft.app")) {
                        shell("am force-stop $offender")
                        forceStoppedPackage = offender
                        Log.w(TAG, "force-stopped hung system app $offender after $dismissed BACK dismisses")
                    }
                }
            }
            Thread.sleep(POLL_MS)
        }

        val result = Result(
            acquired = false,
            finalActivePackage = activePackage,
            wokeDisplay = wokeDisplay,
            dismissedSystemDialogs = dismissed,
            forceStoppedPackage = forceStoppedPackage,
            elapsedMs = SystemClock.uptimeMillis() - started
        )
        // A package name alone cannot separate keyguard from ANR dialog from a
        // stuck starting window, so name the focused window before giving up.
        Log.w(TAG, "active-window recovery gave up: ${result.describe()}")
        Log.w(TAG, "focused windows: ${focusedWindowSummary()}")
        return result
    }

    /** Package of the app owning a visible ANR/crash dialog, if any. */
    private fun hungSystemAppPackage(): String? =
        readShell("dumpsys window windows")
            .lineSequence()
            .mapNotNull { ANR_MARKER.find(it)?.groupValues?.get(1) }
            .firstOrNull()

    private fun focusedWindowSummary(): String =
        readShell("dumpsys window windows")
            .lineSequence()
            .filter { line ->
                listOf("mCurrentFocus", "mFocusedWindow", "mFocusedApp", "Application Not Responding")
                    .any { line.contains(it) }
            }
            .joinToString(" | ") { it.trim() }
            .take(400)

    private fun shell(command: String) {
        readShell(command)
    }

    private fun readShell(command: String): String =
        try {
            val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
            ParcelFileDescriptor.AutoCloseInputStream(fd).use { String(it.readBytes()) }
        } catch (t: Throwable) {
            ""
        }
}
