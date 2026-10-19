package build.raft.app.shared

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry

internal fun ActivityScenario<SlockCmpActivity>.awaitVisualInputHostReadyForEditorFocus(
    description: String,
    timeoutMs: Long = 12_000L,
    inputReady: (SlockCmpActivity) -> Boolean,
) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    var lastReadiness = VisualInputHostReadiness(windowFocused = false, inputReady = false)

    while (SystemClock.uptimeMillis() < deadline) {
        instrumentation.waitForIdleSync()
        lastReadiness = visualInputHostReadiness(inputReady)
        if (lastReadiness.ready) {
            // The first window-focus cleanup posts one more clearFocus(). Require another idle
            // boundary before the harness is allowed to focus the editor.
            instrumentation.waitForIdleSync()
            lastReadiness = visualInputHostReadiness(inputReady)
            if (lastReadiness.ready) return
        }
        SystemClock.sleep(50L)
    }

    throw AssertionError(
        "Timed out waiting for $description before editor focus: " +
            "windowFocused=${lastReadiness.windowFocused} inputReady=${lastReadiness.inputReady}"
    )
}

private fun ActivityScenario<SlockCmpActivity>.visualInputHostReadiness(
    inputReady: (SlockCmpActivity) -> Boolean,
): VisualInputHostReadiness {
    var readiness = VisualInputHostReadiness(windowFocused = false, inputReady = false)
    onActivity { activity ->
        readiness =
            VisualInputHostReadiness(
                windowFocused = activity.hasWindowFocus(),
                inputReady = inputReady(activity),
            )
    }
    return readiness
}

private data class VisualInputHostReadiness(
    val windowFocused: Boolean,
    val inputReady: Boolean,
) {
    val ready: Boolean
        get() = windowFocused && inputReady
}
